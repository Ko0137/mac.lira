#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="${1:-native}"

fail() {
  printf 'Error: %s\n' "$1" >&2
  exit 1
}

architecture_for() {
  local java_home="$1"
  "$java_home/bin/java" -XshowSettings:properties -version 2>&1 \
    | sed -n 's/^[[:space:]]*os.arch = //p' \
    | tail -n 1
}

build_image() {
  local label="$1"
  local java_home="$2"
  local expected_arch="$3"
  local actual_arch
  local install_lib
  local base_jar
  local graphics_jar
  local controls_jar
  local module_path
  local runtime_modules
  local destination
  local -a package_args

  [[ -x "$java_home/bin/java" ]] || fail "JDK 17 not found at $java_home"
  [[ -x "$java_home/bin/jpackage" ]] || fail "jpackage not found in $java_home"
  [[ -f "$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar" ]] \
    || fail "Gradle Wrapper is missing from the build kit."
  actual_arch="$(architecture_for "$java_home")"

  case "$expected_arch:$actual_arch" in
    arm64:aarch64|arm64:arm64|x64:x86_64|x64:amd64) ;;
    *) fail "JDK architecture mismatch: expected $expected_arch, got $actual_arch ($java_home)" ;;
  esac

  (
    export JAVA_HOME="$java_home"
    export PATH="$JAVA_HOME/bin:$PATH"
    bash "$SCRIPT_DIR/gradlew" --no-daemon \
      -Dorg.gradle.java.home="$java_home" \
      -p "$SCRIPT_DIR" clean test installDist fatJar
  )

  install_lib="$SCRIPT_DIR/build/install/lira-desktop/lib"
  base_jar="$(find "$install_lib" -maxdepth 1 -name 'javafx-base-*-mac*.jar' -print -quit)"
  graphics_jar="$(find "$install_lib" -maxdepth 1 -name 'javafx-graphics-*-mac*.jar' -print -quit)"
  controls_jar="$(find "$install_lib" -maxdepth 1 -name 'javafx-controls-*-mac*.jar' -print -quit)"
  [[ -n "$base_jar" && -n "$graphics_jar" && -n "$controls_jar" ]] \
    || fail "Gradle did not resolve JavaFX macOS libraries for $label."

  module_path="$java_home/jmods:$base_jar:$graphics_jar:$controls_jar"
  runtime_modules="javafx.controls,jdk.httpserver,java.sql,jdk.unsupported,java.logging,java.naming,java.management"
  destination="$SCRIPT_DIR/build/macos-package-$label"
  mkdir -p "$destination"

  package_args=(
    --type dmg
    --name LIRA
    --app-version 1.0.0
    --vendor "L.I.R.A."
    --input "$SCRIPT_DIR/build/libs"
    --main-jar lira-desktop-1.0.0-all.jar
    --main-class com.example.lira.desktop.LiraDesktopApp
    --module-path "$module_path"
    --add-modules "$runtime_modules"
    --java-options "--add-modules=$runtime_modules"
    --mac-package-identifier com.example.lira.desktop
    --mac-package-name "L.I.R.A."
    --mac-app-category public.app-category.productivity
    --dest "$destination"
  )

  if [[ -n "${APPLE_SIGN_IDENTITY:-}" ]]; then
    package_args+=(--mac-sign --mac-signing-key-user-name "$APPLE_SIGN_IDENTITY")
    if [[ -n "${APPLE_SIGN_KEYCHAIN:-}" ]]; then
      package_args+=(--mac-signing-keychain "$APPLE_SIGN_KEYCHAIN")
    fi
  fi

  "$java_home/bin/jpackage" "${package_args[@]}"

  local dmg
  dmg="$(find "$destination" -maxdepth 1 -name '*.dmg' -print -quit)"
  [[ -n "$dmg" ]] || fail "DMG was not created for $label."
  if [[ -n "${APPLE_NOTARY_PROFILE:-}" ]]; then
    [[ -n "${APPLE_SIGN_IDENTITY:-}" ]] \
      || fail "Notarization requires APPLE_SIGN_IDENTITY."
    xcrun notarytool submit "$dmg" --keychain-profile "$APPLE_NOTARY_PROFILE" --wait
    xcrun stapler staple "$dmg"
  fi

  printf 'Created macOS %s image in %s\n' "$label" "$destination"
}

case "$MODE" in
  native)
    [[ "$(uname -s)" == "Darwin" ]] || fail "Run this script on macOS."
    [[ -n "${JAVA_HOME:-}" ]] || fail "Set JAVA_HOME to a JDK 17 installation."
    arch="$(architecture_for "$JAVA_HOME")"
    case "$arch" in
      arm64|aarch64) build_image arm64 "$JAVA_HOME" arm64 ;;
      x86_64|amd64) build_image x64 "$JAVA_HOME" x64 ;;
      *) fail "Unsupported macOS architecture: $arch" ;;
    esac
    ;;
  universal)
    [[ "$(uname -s)" == "Darwin" ]] || fail "Run this script on macOS."
    [[ -n "${JAVA_HOME_ARM64:-}" ]] || fail "Set JAVA_HOME_ARM64 to a JDK 17 ARM64 installation."
    [[ -n "${JAVA_HOME_X64:-}" ]] || fail "Set JAVA_HOME_X64 to a JDK 17 Intel installation."
    if [[ -n "${APPLE_NOTARY_PROFILE:-}" && -z "${APPLE_SIGN_IDENTITY:-}" ]]; then
      fail "Notarization requires APPLE_SIGN_IDENTITY."
    fi
    staging_parent="$(mktemp -d)"
    staging="$staging_parent/universal"
    mkdir -p "$staging"
    trap 'rm -rf "$staging_parent"' EXIT
    build_image arm64 "$JAVA_HOME_ARM64" arm64
    arm64_dmg="$(find "$SCRIPT_DIR/build/macos-package-arm64" -maxdepth 1 -name '*.dmg' -print -quit)"
    [[ -n "$arm64_dmg" ]] || fail "ARM64 DMG was not created."
    cp "$arm64_dmg" "$staging/LIRA-macOS-Apple-Silicon.dmg"
    build_image x64 "$JAVA_HOME_X64" x64
    x64_dmg="$(find "$SCRIPT_DIR/build/macos-package-x64" -maxdepth 1 -name '*.dmg' -print -quit)"
    [[ -n "$x64_dmg" ]] || fail "Intel DMG was not created."
    cp "$x64_dmg" "$staging/LIRA-macOS-Intel.dmg"

    archive="$SCRIPT_DIR/build/LIRA-macOS-Universal.zip"
    temporary_archive="$staging_parent/LIRA-macOS-Universal.zip"
    mkdir -p "$SCRIPT_DIR/build"
    ditto -c -k --sequesterRsrc "$staging" "$temporary_archive"
    mv -f "$temporary_archive" "$archive"
    printf 'Created universal distribution: %s\n' "$archive"
    ;;
  *)
    fail "Usage: $0 [native|universal]"
    ;;
esac
