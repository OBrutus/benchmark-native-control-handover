#!/usr/bin/env bash
# Regenerates android/app/libs/gocore.aar from the Go module.
#
# Run this after every change to gocore/ — Gradle has no idea the Go source
# exists, so nothing rebuilds the .aar for you.
set -euo pipefail

cd "$(dirname "$0")"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"
export PATH="$PATH:$(go env GOPATH)/bin"

# Only arm64 by default: it covers every current device and the Apple Silicon
# emulator, and keeps the build to ~15s. Pass --all-abis for a release build.
TARGET="android/arm64"
if [[ "${1:-}" == "--all-abis" ]]; then
  TARGET="android"
  echo "Building all ABIs (arm64-v8a, armeabi-v7a, x86, x86_64) — slower."
  echo "Remember to widen abiFilters in app/build.gradle.kts to match."
fi

echo "==> go test ./..."
(cd gocore && go test ./...)

echo "==> gomobile bind ($TARGET)"
(cd gocore && gomobile bind \
  -target="$TARGET" \
  -androidapi 24 \
  -javapkg=sh.locus.gocore \
  -o ../android/app/libs/gocore.aar \
  ./mobile)

echo "==> done"
ls -lh android/app/libs/gocore.aar
