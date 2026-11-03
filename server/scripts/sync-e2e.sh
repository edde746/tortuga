#!/usr/bin/env bash
# End-to-end run of the shared sync client (shared/.../sync/account and sync/engine) and tracker connections
# (shared/.../trackers) against the real server: :shared:syncEndToEndTest (shared/src/jvmTest/.../sync/e2e/). The sync
# test signs up through Dex's password grant (user alice), sets a profile with a PIN, addons and a library item, signs
# a second device in to the same account, signs a TV in by device link, merges a stale push, checks that pg_dump holds
# none of the account's addon URLs, ids or titles, and checks with psql that deleting the account leaves none of its
# rows. The tracker test (user desktop) starts tools/media/test_trackers.py as Trakt on TRACKERS_PORT, connects
# it on one device, scrobbles from a second one that never signed in to Trakt, has the server refresh and revoke at
# the fake, and disconnects from the second device; the server must send its tracker calls to that fake
# (TORTUGA_DEV_TRACKERS_URL=http://host.docker.internal:<TRACKERS_PORT>).
#
#   server/scripts/sync-e2e.sh         a stack of its own here: starts compose.yaml + compose.dev.yaml (the server,
#                                      Postgres and Dex) under its own compose project, runs the tests with ./gradlew,
#                                      and tears everything down, volumes included, also on failure. Needs Docker,
#                                      curl, openssl, python3 and a JDK for Gradle.
#
# Extra Gradle arguments go in GRADLE_ARGS, e.g. GRADLE_ARGS=-Ptortuga.android=false on a host without an Android SDK.
# Env: TRACKERS_PORT (default 18772), TORTUGA_HTTP_PORT (default 18180), DEX_HTTP_PORT (default 15656),
# TRACKERS_HTTP_PORT (default 15658).
set -euo pipefail

server_dir=$(cd "$(dirname "$0")/.." && pwd)
repo_dir=$(cd "$server_dir/.." && pwd)
trackers_port=${TRACKERS_PORT:-18772}
step() { printf '\n== %s\n' "$*"; }

export COMPOSE_PROJECT_NAME=tortuga-sync-e2e
POSTGRES_PASSWORD=$(openssl rand -hex 16)
export POSTGRES_PASSWORD
export TORTUGA_HTTP_PORT="${TORTUGA_HTTP_PORT:-18180}"
export DEX_HTTP_PORT="${DEX_HTTP_PORT:-15656}"
export TRACKERS_HTTP_PORT="${TRACKERS_HTTP_PORT:-15658}"
export TORTUGA_DEV_TRACKERS_URL="http://host.docker.internal:$trackers_port"
server_url="http://127.0.0.1:$TORTUGA_HTTP_PORT"
dex_url="http://127.0.0.1:$DEX_HTTP_PORT/dex"

dev() { docker compose -f "$server_dir/compose.yaml" -f "$server_dir/compose.dev.yaml" "$@"; }
cleanup() { dev down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

step "building the server image"
dev build --quiet server

step "starting the server, Postgres and Dex"
dev up -d --wait postgres server dex
for _ in $(seq 1 30); do
    curl -fsS "$dex_url/.well-known/openid-configuration" >/dev/null 2>&1 && break
    sleep 1
done
curl -fsS "$server_url/v2/info"; echo
postgres=$(dev ps -q postgres)

step "sync client and tracker connections end to end"
# shellcheck disable=SC2086 # GRADLE_ARGS is a list of arguments.
(cd "$repo_dir" && ./gradlew ${GRADLE_ARGS:-} --console=plain :shared:syncEndToEndTest \
    -Ptortuga.e2e.server="$server_url" \
    -Ptortuga.e2e.dex="$dex_url" \
    -Ptortuga.e2e.postgresContainer="$postgres" \
    -Ptortuga.e2e.trackers="http://127.0.0.1:$trackers_port")

step "tearing down"
dev down -v --remove-orphans
echo "sync-e2e: all steps passed"
