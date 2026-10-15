#!/usr/bin/env bash
# Copyright (c) 2026 CompanionShare. MIT License.
# Verify both installable APKs open on a real Android framework (emulator), not only Gradle.
set -euo pipefail

owner_apk="${1:-android/owner/build/outputs/apk/debug/owner-debug.apk}"
viewer_apk="${2:-android/viewer/build/outputs/apk/debug/viewer-debug.apk}"

for apk in "$owner_apk" "$viewer_apk"; do
  test -s "$apk" || { echo "APK missing: $apk" >&2; exit 1; }
  adb install -r "$apk"
done

api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
test "$api" = "36" || { echo "Expected API 36; found $api" >&2; exit 1; }

check_launch() {
  package="$1"
  activity="$2"
  adb logcat -c
  adb shell am start -W -n "$package/$activity"
  sleep 3
  pid="$(adb shell pidof "$package" | tr -d '\r')"
  test -n "$pid" || { echo "$package exited on initial launch" >&2; exit 1; }
  if adb logcat -d -v brief | grep -F -q "Process: $package, PID: $pid"; then
    echo "Crash in $package:" >&2
    adb logcat -d -v brief | tail -100 >&2
    exit 1
  fi
  echo "Launch passed: $package (pid $pid)"
  adb shell am force-stop "$package"
}

check_launch dev.companionshare.owner .OwnerActivity
check_launch dev.companionshare.viewer .ViewerActivity
echo "API 36 Owner and Viewer launch smoke passed; no media was authorized."