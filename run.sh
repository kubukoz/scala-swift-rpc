#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCALA_DIR="$HERE/scala"
BUILD_DIR="$HERE/build"
APP="$BUILD_DIR/htmx-poc-app"

if [[ ! -x "$APP" ]]; then
  echo "App not built. Run ./build.sh first." >&2
  exit 1
fi

MODE="$(cat "$BUILD_DIR/.mode" 2>/dev/null || echo jvm)"

if [[ "$MODE" == "native" ]]; then
  NATIVE_BIN="$BUILD_DIR/htmx-poc-scala-native"
  if [[ ! -x "$NATIVE_BIN" ]]; then
    echo "Native binary missing. Run ./build.sh --native first." >&2
    exit 1
  fi
  SCALA_APP_BIN="$NATIVE_BIN" "$APP"
else
  SCALA_APP_PATH="$SCALA_DIR" "$APP"
fi
