#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCALA_DIR="$HERE/scala"
SWIFT_DIR="$HERE/swift"
BUILD_DIR="$HERE/build"

MODE="jvm"
for arg in "$@"; do
  case "$arg" in
    --native) MODE="native" ;;
    --jvm) MODE="jvm" ;;
    -h|--help)
      echo "Usage: $0 [--jvm|--native]"
      exit 0
      ;;
    *) echo "Unknown flag: $arg" >&2; exit 1 ;;
  esac
done

mkdir -p "$BUILD_DIR"

echo "==> Building Swift app..."
swiftc -O "$SWIFT_DIR/main.swift" -o "$BUILD_DIR/htmx-poc-app"

if [[ "$MODE" == "native" ]]; then
  NATIVE_BIN="$BUILD_DIR/htmx-poc-scala-native"
  echo "==> Building Scala Native binary..."
  scala-cli --power package "$SCALA_DIR" \
    --native \
    --force \
    -o "$NATIVE_BIN"
else
  echo "==> Warming Scala build (JVM)..."
  scala-cli compile "$SCALA_DIR"
fi

echo "$MODE" > "$BUILD_DIR/.mode"
echo "==> Done. Run with ./run.sh"
