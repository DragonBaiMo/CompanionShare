#!/usr/bin/env sh
# Copyright (c) 2026 CompanionShare. MIT License.
set -eu
cd "$(dirname "$0")/.."
command -v node >/dev/null || { echo 'Node.js 22+ is required' >&2; exit 1; }
export BIND="${BIND:-127.0.0.1}"
export PORT="${PORT:-8087}"
export DATA_FILE="${DATA_FILE:-./data/devices.json}"
exec node server/src/server.mjs
