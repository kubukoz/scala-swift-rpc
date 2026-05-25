#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SCALA_DIR="$HERE/scala"
GENERATED_DIR="$SCALA_DIR/generated"
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

echo "==> Generating smithy4s sources..."
rm -rf "$GENERATED_DIR"
mkdir -p "$GENERATED_DIR"
cs launch com.disneystreaming.smithy4s:smithy4s-codegen-cli_2.13:0.18.53 -- generate \
  --output "$GENERATED_DIR" \
  --resource-output "$BUILD_DIR/smithy-resources" \
  --dependencies tech.neander:jsonrpclib-smithy:0.1.2 \
  --skip resource \
  "$HERE/smithy"

echo "==> Building Swift codegen plugin..."
scala-cli --power publish local "$HERE/codegen/swift-plugin" \
  --m2 \
  --signer none

echo "==> Generating Swift wire types from smithy..."
SWIFT_GENERATED_DIR="$SWIFT_DIR/generated"
mkdir -p "$SWIFT_GENERATED_DIR"

cd "$HERE"
cs launch --contrib smithy-cli -- build --allow-unknown-traits

cp "$BUILD_DIR/smithy/source/swift-codegen/WireTypes.swift" "$SWIFT_GENERATED_DIR/WireTypes.swift"

echo "==> Building Swift app..."
swiftc -O "$SWIFT_DIR/main.swift" "$SWIFT_GENERATED_DIR/WireTypes.swift" -o "$BUILD_DIR/ssr-app"

if [[ "$MODE" == "native" ]]; then
  NATIVE_BIN="$BUILD_DIR/ssr-scala-native"
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
