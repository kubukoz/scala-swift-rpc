#!/usr/bin/env bash
# Build the SSR iOS host (embedded Scala Native FFI app) and run it on a
# physical device. Headless flow — mirrors ios/README.md "Physical device".
#
# Usage:
#   ios/run-device.sh                 # auto-pick the one connected iPhone
#   ios/run-device.sh <UDID>          # target a specific device
#   SSR_DEV_TEAM=XXXXXXXXXX ios/run-device.sh   # override signing team
#
# Prereqs (see ios/README.md): Xcode, sbt, a device trusted for development.
# First-time signing may need one GUI run from Xcode to mint the profile.
set -euo pipefail

cd "$(dirname "$0")/.."   # repo root
BUNDLE_ID="com.kubukoz.ssr.SSRiOS"
DD="ios/build/dd-device"
APP="$DD/Build/Products/Debug-iphoneos/SSRiOS.app"

# --- resolve target device -------------------------------------------------
UDID="${1:-}"
if [[ -z "$UDID" ]]; then
  # Pick the single available iPhone. Fail loudly if 0 or >1. The identifier is
  # a standard dashed UUID (8-4-4-4-12); match that shape on lines naming an
  # available iPhone.
  mapfile -t IPHONES < <(xcrun devicectl list devices 2>/dev/null \
    | grep -iE 'iPhone' | grep -i 'available' \
    | grep -oiE '[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}')
  if [[ ${#IPHONES[@]} -eq 0 ]]; then
    echo "No available iPhone found. Connect + unlock one, or pass a UDID." >&2
    xcrun devicectl list devices >&2
    exit 1
  elif [[ ${#IPHONES[@]} -gt 1 ]]; then
    echo "Multiple iPhones found — pass one explicitly:" >&2
    printf '  %s\n' "${IPHONES[@]}" >&2
    exit 1
  fi
  UDID="${IPHONES[0]}"
fi
echo ">> target device: $UDID"

# --- 1. Scala Native static lib for the device triple ----------------------
# nativeLink APPENDS to libssr-demos.a and doesn't drop stale objects, so a
# previous macOS/simulator build leaves wrong-arch objects that break linking.
# Always wipe before a device relink (README "Stale ar archive" gotcha).
echo ">> building Scala Native lib (arm64-apple-ios)"
rm -rf scala/demos/target/native-3
sbt -Dssr.ios.device=true demosNative3/Compile/nativeLink

# --- 2. build + sign the app -----------------------------------------------
echo ">> xcodebuild (iphoneos)"
TEAM_ARG=()
[[ -n "${SSR_DEV_TEAM:-}" ]] && TEAM_ARG=(DEVELOPMENT_TEAM="$SSR_DEV_TEAM")
xcodebuild -project ios/SSRiOS.xcodeproj -scheme SSRiOS \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath "$DD" -allowProvisioningUpdates \
  "${TEAM_ARG[@]}" build

# --- 3. install + launch ---------------------------------------------------
echo ">> installing $APP"
xcrun devicectl device install app --device "$UDID" "$APP"
echo ">> launching $BUNDLE_ID"
xcrun devicectl device process launch --device "$UDID" "$BUNDLE_ID"
echo ">> done — app is running on device $UDID"
