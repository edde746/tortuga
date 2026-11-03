#!/bin/sh
# Encrypted Postgres backups of the Tortuga sync server to S3-compatible storage.
#
#   tortuga-backup schedule         run `backup` on BACKUP_SCHEDULE (cron syntax) until stopped (the default)
#   tortuga-backup backup           dump, encrypt, upload one backup now, then apply the retention
#   tortuga-backup list             list the stored backups, oldest first
#   tortuga-backup init             create the bucket if it doesn't exist (local MinIO, first setup)
#   tortuga-backup restore [NAME]   download NAME (default: the newest), decrypt, replace PGDATABASE's schema with it
#
# Dumps are encrypted with age before they leave the machine: the bucket only ever holds ciphertext. Backups need
# only the public recipient keys; restores need the private identity, which the operator keeps away from the bucket
# and the server and mounts only to restore.
#
# The server's data key (TORTUGA_DATA_KEY_FILE) is not part of the dump: synced records are in it sealed under that
# key, so the operator backs the key up separately, and a restored database is readable only with the same key.
#
# Environment:
#   PGHOST PGPORT PGUSER PGPASSWORD PGDATABASE      the database (libpq variables); PGPASSWORD_FILE instead of PGPASSWORD
#   BACKUP_S3_ENDPOINT                             e.g. https://s3.eu-central-1.amazonaws.com, http://minio:9000
#   BACKUP_S3_BUCKET                               bucket name
#   BACKUP_S3_ACCESS_KEY_ID, BACKUP_S3_SECRET_ACCESS_KEY (or BACKUP_S3_SECRET_ACCESS_KEY_FILE; never both)
#   BACKUP_S3_REGION      default us-east-1        BACKUP_S3_PROVIDER  rclone S3 provider, default Other
#   BACKUP_S3_PREFIX      default tortuga/         key prefix inside the bucket
#   BACKUP_SCHEDULE       default "17 3 * * *"     BACKUP_RETENTION_DAYS  default 30; 0 keeps everything
#   BACKUP_AGE_RECIPIENTS                          age public keys (age1…), separated by spaces or commas
#   BACKUP_AGE_IDENTITY_FILE  default /run/secrets/age-identity   the private key file, for restore only
set -eu
set -o pipefail

log() { printf '%s tortuga-backup: %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2; }
die() { log "$*"; exit 1; }
need() { eval "[ -n \"\${$1:-}\" ]" || die "$1 is not set"; }

# NAME from the file NAME_FILE names, when that is set; one form or the other, never both.
from_file() {
    eval "file=\${${1}_FILE:-}"
    [ -n "$file" ] || return 0
    eval "[ -z \"\${$1:-}\" ]" || die "$1 and ${1}_FILE are both set: keep one"
    eval "$1=\$(cat \"\$file\")"
    export "$1"
}
from_file PGPASSWORD

setup_remote() {
    need BACKUP_S3_ENDPOINT
    need BACKUP_S3_BUCKET
    need BACKUP_S3_ACCESS_KEY_ID
    from_file BACKUP_S3_SECRET_ACCESS_KEY
    need BACKUP_S3_SECRET_ACCESS_KEY
    export RCLONE_CONFIG_BACKUP_TYPE=s3
    export RCLONE_CONFIG_BACKUP_PROVIDER="${BACKUP_S3_PROVIDER:-Other}"
    export RCLONE_CONFIG_BACKUP_ENDPOINT="$BACKUP_S3_ENDPOINT"
    export RCLONE_CONFIG_BACKUP_REGION="${BACKUP_S3_REGION:-us-east-1}"
    export RCLONE_CONFIG_BACKUP_ACCESS_KEY_ID="$BACKUP_S3_ACCESS_KEY_ID"
    export RCLONE_CONFIG_BACKUP_SECRET_ACCESS_KEY="$BACKUP_S3_SECRET_ACCESS_KEY"
    export RCLONE_CONFIG_BACKUP_ENV_AUTH=false
    export RCLONE_CONFIG_BACKUP_FORCE_PATH_STYLE=true
    # Uploads mustn't need permission to create buckets.
    export RCLONE_CONFIG_BACKUP_NO_CHECK_BUCKET=true
    export RCLONE_CONFIG=/dev/null
    PREFIX="${BACKUP_S3_PREFIX-tortuga/}"
    DIR="backup:$BACKUP_S3_BUCKET/$PREFIX"
}

backup() {
    setup_remote
    need PGDATABASE
    need BACKUP_AGE_RECIPIENTS
    set --
    for recipient in $(printf '%s' "$BACKUP_AGE_RECIPIENTS" | tr ',' ' '); do
        set -- "$@" --recipient "$recipient"
    done
    name="tortuga-$(date -u +%Y%m%dT%H%M%SZ).dump.age"
    log "dumping $PGDATABASE to $BACKUP_S3_BUCKET/$PREFIX$name"
    # A failed dump must not leave a valid-looking backup: upload under a temporary name, rename once complete.
    if ! pg_dump --format=custom --no-owner --no-privileges | age --encrypt "$@" | rclone rcat "$DIR$name.partial"; then
        rclone deletefile "$DIR$name.partial" 2>/dev/null || true
        die "backup failed; nothing was stored"
    fi
    rclone moveto "$DIR$name.partial" "$DIR$name"
    size=$(rclone size --json "$DIR$name" | sed -n 's/.*"bytes":\([0-9]*\).*/\1/p')
    [ "${size:-0}" -gt 0 ] || die "uploaded object is empty"
    [ "$(rclone cat --count 21 "$DIR$name")" = "age-encryption.org/v1" ] || die "uploaded object isn't age-encrypted"
    log "uploaded $name ($size bytes)"
    days="${BACKUP_RETENTION_DAYS:-30}"
    if [ "$days" -gt 0 ]; then
        rclone delete --max-depth 1 --min-age "${days}d" --include 'tortuga-*.dump.age' "$DIR"
        log "removed backups older than $days days"
    fi
}

list() {
    setup_remote
    rclone lsf --max-depth 1 --include 'tortuga-*.dump.age' "$DIR" | sort
}

init() {
    setup_remote
    RCLONE_CONFIG_BACKUP_NO_CHECK_BUCKET=false rclone mkdir "backup:$BACKUP_S3_BUCKET"
    log "bucket $BACKUP_S3_BUCKET is ready"
}

restore() {
    setup_remote
    need PGDATABASE
    identity="${BACKUP_AGE_IDENTITY_FILE:-/run/secrets/age-identity}"
    [ -r "$identity" ] || die "no age identity at $identity: mount the private key file there to restore"
    name="${1:-latest}"
    if [ "$name" = latest ]; then
        name=$(list | tail -n 1)
        [ -n "$name" ] || die "no backups in $BACKUP_S3_BUCKET/$PREFIX"
    fi
    log "restoring $name into $PGDATABASE, replacing its schema"
    # One transaction drops the public schema, whatever it holds (a newer server's tables too), and creates the
    # backup's in its place. COMMIT is sent only once the whole backup has been read and decrypted: a restore that
    # fails part-way ends psql's input without it, Postgres rolls everything back, and the database is as it was.
    if ! {
        printf 'SET client_min_messages = warning;\nBEGIN;\nDROP SCHEMA public CASCADE;\nCREATE SCHEMA public;\n'
        rclone cat "$DIR$name" | age --decrypt --identity "$identity" |
            pg_restore --no-owner --no-privileges --exit-on-error --file=- &&
            printf 'COMMIT;\n'
    } | psql --no-psqlrc --quiet --set ON_ERROR_STOP=1 --dbname "$PGDATABASE" >/dev/null; then
        die "restore of $name failed; $PGDATABASE is as it was"
    fi
    log "restored $name"
}

schedule() {
    # Fail at start, not at the first run, if the configuration is incomplete.
    setup_remote
    need PGDATABASE
    need BACKUP_AGE_RECIPIENTS
    crontab=$(mktemp)
    printf '%s /usr/local/bin/tortuga-backup backup\n' "${BACKUP_SCHEDULE:-17 3 * * *}" > "$crontab"
    log "scheduled: ${BACKUP_SCHEDULE:-17 3 * * *} (UTC)"
    exec supercronic -passthrough-logs "$crontab"
}

command="${1:-schedule}"
[ $# -gt 0 ] && shift
case "$command" in
    schedule) schedule ;;
    backup) backup ;;
    list) list ;;
    init) init ;;
    restore) restore "$@" ;;
    *) die "unknown command: $command (schedule, backup, list, init, restore)" ;;
esac
