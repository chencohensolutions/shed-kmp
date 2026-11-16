#!/usr/bin/env bash
# Runs backend-journal's end-to-end test against a real journal server built from ../journal-server-go (or
# $JOURNAL_SERVER): a throwaway server folder and data folder, a fresh enrollment code, then the jvm tests.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
server_src="${JOURNAL_SERVER:-$here/../journal-server-go}"
work="$(mktemp -d)"
port="${PORT:-56499}"
trap 'kill "${pid:-0}" 2>/dev/null || true; rm -rf "$work"' EXIT

(cd "$server_src" && go build -o "$work/journal-server" .)
"$work/journal-server" setup --dir "$work/server" --user e2e --data-folder "$work/data" --port "$port" --no-downloads >/dev/null
(cd "$work/server" && exec "$work/journal-server" >"$work/server.log" 2>&1) &
pid=$!
for _ in $(seq 1 60); do grep -q "Listening on" "$work/server.log" 2>/dev/null && break; sleep 0.5; done
link="$(cd "$work/server" && "$work/journal-server" enroll-code e2e | grep -o 'journal://enroll?[^ ]*')"

cd "$here"
SHED_E2E_LINK="$link" SHED_E2E_ORIGIN="https://localhost:$port" ./gradlew --no-daemon -q :backend-journal:jvmTest "$@" \
  || { echo "--- server log"; tail -40 "$work/server.log"; exit 1; }
echo "End-to-end test passed against $(cd "$server_src" && git rev-parse --short HEAD)"
