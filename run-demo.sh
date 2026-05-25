#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
exec env SCALA_APP_MAIN="ssr.demo.MirrorMain" "$HERE/run.sh" "$@"
