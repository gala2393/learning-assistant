#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

load_env() {
  local file="$1"
  if [[ -f "$file" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$file"
    set +a
  fi
}

load_env ".env"
load_env ".env.local"

MVN="./tools/apache-maven-3.9.11/bin/mvn"
if [[ ! -x "$MVN" ]]; then
  MVN="mvn"
fi

"$MVN" spring-boot:run
