#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

PORT="${PORT:-5174}"

if [[ ! -d node_modules ]]; then
  npm ci
fi

npm run dev -- --host 0.0.0.0 --port "$PORT"
