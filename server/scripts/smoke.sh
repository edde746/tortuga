#!/usr/bin/env bash
# End-to-end check of the self-hosting stack, run from anywhere:  server/scripts/smoke.sh
#
#  1. compose.yaml alone starts the server and Postgres, with a throwaway data key file as its compose secret.
#  2. With compose.dev.yaml (Dex as the OIDC provider, a fake GitHub, MinIO as the backup target, a generated data
#     key) the smoke client (src/smoke, `:server:smoke`) signs a phone in through Dex, pushes records, signs a TV in
#     through a device link and reads everything back on it; scripts/github-e2e.py signs in with GitHub's web and
#     device flows, links a second GitHub user and deletes that account, which deletes both users' grants.
#  3. psql and pg_dump: the database holds the records, but sealed at rest: none of the synced ids, URLs or titles,
#     and not the data key.
#  4. An age-encrypted backup goes to MinIO, once on demand and once from the schedule.
#  5. The account is deleted through the API; psql shows no rows left.
#  6. The backup is restored into an empty database, then over one that isn't empty and holds a table the backup
#     doesn't (as a newer server's), which goes; a restore that fails changes nothing. The restored account syncs
#     again with the same data key.
#
# Everything runs under its own compose project and is torn down at the end (also on failure). Needs Docker,
# python3 and a JDK 27 for Gradle.
set -euo pipefail

server_dir=$(cd "$(dirname "$0")/.." && pwd)
repo_dir=$(cd "$server_dir/.." && pwd)
work=$(mktemp -d)

export COMPOSE_PROJECT_NAME=tortuga-smoke
POSTGRES_PASSWORD=$(openssl rand -hex 16)
export POSTGRES_PASSWORD
export TORTUGA_HTTP_PORT="${TORTUGA_HTTP_PORT:-18080}"
export DEX_HTTP_PORT="${DEX_HTTP_PORT:-15556}"
export GITHUB_HTTP_PORT="${GITHUB_HTTP_PORT:-15557}"
export TRACKERS_HTTP_PORT="${TRACKERS_HTTP_PORT:-15558}"
export MINIO_HTTP_PORT="${MINIO_HTTP_PORT:-19000}"
server_url="http://127.0.0.1:$TORTUGA_HTTP_PORT"

step() { printf '\n== %s\n' "$*"; }
base() { docker compose -f "$server_dir/compose.yaml" "$@"; }
dev() { docker compose -f "$server_dir/compose.yaml" -f "$server_dir/compose.dev.yaml" --profile backup "$@"; }
sql() { dev exec -T postgres psql -U tortuga -d tortuga -tA -c "$1"; }
smoke() { (cd "$repo_dir" && ./gradlew -q :server:smoke --args="$*"); }
# Restores the newest backup with the age private key in file $1.
restore() { dev run --rm --no-deps -v "$1:/run/secrets/age-identity:ro" backup restore latest; }
cleanup() {
    dev down -v --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT

# Row counts of every application table (Flyway's history aside), as "table=count" lines.
table_counts() {
    local tables table
    tables=$(sql "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history' ORDER BY 1")
    for table in $tables; do printf '%s=%s\n' "$table" "$(sql "SELECT count(*) FROM $table")"; done
}

step "building the server and backup images"
dev build --quiet

step "compose.yaml alone: server + Postgres"
# The data key compose.yaml mounts as a secret: 32 random bytes, readable by the server's user in the container.
head -c 32 /dev/urandom > "$work/data.key"
chmod 0444 "$work/data.key"
export TORTUGA_DATA_KEY_PATH="$work/data.key"
base up -d --wait postgres server
curl -fsS "$server_url/v2/info"; echo
base down -v

step "dev stack: server, Postgres, Dex, fake GitHub, MinIO"
# This run's age key pair: backups are encrypted to the public key; the private key is mounted only to restore.
docker run --rm --entrypoint age-keygen "${TORTUGA_BACKUP_IMAGE:-tortuga-backup:local}" > "$work/age-identity.txt" 2>/dev/null
BACKUP_AGE_RECIPIENTS=$(sed -n 's/^# public key: //p' "$work/age-identity.txt")
export BACKUP_AGE_RECIPIENTS
dev up -d --wait postgres server dex github minio

step "smoke client: sign in through Dex, push, link a TV, pull"
smoke setup --server "$server_url" --dex "http://127.0.0.1:$DEX_HTTP_PORT/dex" \
    --state "$work/state.json" --markers "$work/markers.txt"

step "Sign in with GitHub: web flow, device flow, link, deletion"
python3 "$server_dir/scripts/github-e2e.py" "$server_url"

step "psql: what the database holds"
table_counts
sql "SELECT left(encode(id_index, 'hex'), 16) AS id_index, version, deleted, payload_size,
            octet_length(sealed_payload) AS sealed_bytes FROM records ORDER BY version"
dev exec -T postgres pg_dump -U tortuga tortuga > "$work/dump.sql"
grep -q 'COPY public.records' "$work/dump.sql"
# The dev stack's data key, in hex: it must not be in the database either.
dev exec -T server od -An -tx1 -v /run/tortuga-dev-key/data.key | tr -d ' \n' > "$work/data-key.hex"
python3 - "$work/dump.sql" "$work/markers.txt" "$work/data-key.hex" <<'EOF'
import sys
dump = open(sys.argv[1], encoding="utf-8").read()
markers = [m for m in open(sys.argv[2], encoding="utf-8").read().splitlines() if m]
key = open(sys.argv[3], encoding="ascii").read().strip()
assert len(key) == 64, f"data key: {len(key)} hex digits"
found = [m for m in markers if m in dump or m.encode().hex() in dump]
if found:
    sys.exit(f"synced plaintext in the database dump: {found}")
if key in dump:
    sys.exit("the data key is in the database dump")
print(f"pg_dump ({len(dump)} bytes) contains none of {len(markers)} synced markers (as text or bytea hex), "
      "nor the data key: records are sealed at rest")
EOF

step "backup to MinIO (age-encrypted before upload)"
dev run --rm --no-deps backup init
dev run --rm --no-deps backup backup
dev run --rm --no-deps backup list

step "scheduled backups: the backup service on a once-a-minute schedule"
BACKUP_SCHEDULE='* * * * *' dev up -d --no-deps backup
for _ in $(seq 1 45); do
    [ "$(dev run --rm --no-deps backup list 2>/dev/null | grep -c '\.dump\.age$')" -ge 2 ] && break
    sleep 3
done
dev logs --no-log-prefix backup | grep 'tortuga-backup'
dev stop backup
test "$(dev run --rm --no-deps backup list 2>/dev/null | grep -c '\.dump\.age$')" -ge 2

step "delete the account through the API"
smoke delete --state "$work/state.json"
counts=$(table_counts)
echo "$counts"
# Only the replay guard and the data key's check may keep rows: SHA-256 hashes of used ID tokens until they expire,
# linked to no account, and the one row of data_key.
echo "$counts" | awk -F= '$1 != "used_id_tokens" && $1 != "data_key" && $2 != 0 { left = 1 } END { exit left }' ||
    { echo "rows left behind after account deletion" >&2; exit 1; }
sql "SELECT count(*) FROM used_id_tokens WHERE expires_at > now() + interval '1 hour'" | grep -qx 0
echo "no rows left (used_id_tokens: $(sql 'SELECT count(*) FROM used_id_tokens') unlinked token hashes)"

step "restore the backup into an empty database"
dev stop server
dev exec -T postgres psql -U tortuga -d postgres -q -c 'DROP DATABASE tortuga WITH (FORCE)' -c 'CREATE DATABASE tortuga OWNER tortuga'
test "$(sql "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")" = 0
restore "$work/age-identity.txt"
restored=$(table_counts)
echo "$restored"

step "restore it again over a database that isn't empty"
# A table the backup doesn't know, with a row, as a newer server's migration would have left.
sql "CREATE TABLE smoke_newer (id int PRIMARY KEY); INSERT INTO smoke_newer VALUES (1)" >/dev/null
# A restore that fails (a key that can't decrypt the backup) changes nothing, though it starts by dropping the schema.
docker run --rm --entrypoint age-keygen "${TORTUGA_BACKUP_IMAGE:-tortuga-backup:local}" > "$work/wrong-identity.txt" 2>/dev/null
if restore "$work/wrong-identity.txt"; then
    echo "a restore with the wrong key succeeded" >&2
    exit 1
fi
test "$(sql "SELECT count(*) FROM smoke_newer")" = 1
test "$(table_counts | grep -v '^smoke_newer=')" = "$restored"
# The real one replaces the whole schema: the backup's tables and rows, nothing else.
restore "$work/age-identity.txt"
test "$(sql "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'smoke_newer'")" = 0
test "$(table_counts)" = "$restored"
echo "smoke_newer is gone; the tables hold what the backup holds"
# The server comes back with the same data key (the dev-data-key volume outlives `stop`): the backup holds the
# records sealed, and only that key opens them.
dev up -d --wait server
test "$(dev exec -T server od -An -tx1 -v /run/tortuga-dev-key/data.key | tr -d ' \n')" = "$(cat "$work/data-key.hex")"

step "smoke client: the restored account syncs again"
smoke verify-restored --state "$work/state.json"

step "tearing down"
dev down -v --remove-orphans
echo "smoke: all steps passed"
