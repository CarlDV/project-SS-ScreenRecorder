#!/usr/bin/env bash
# Sole build entry point. ~/.gradle, ~/.android and /tmp are read-only in this
# sandbox; each one breaks a different build stage, so all four redirections below
# are required. GRADLE_RO_DEP_CACHE is Gradle's shared read-only dependency cache --
# the mechanism that makes the immutable ~/.gradle/caches usable offline.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GRADLE_BIN="$HOME/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin/gradle"

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.build/gradle-home}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$ROOT/.build/android-home}"
export GRADLE_RO_DEP_CACHE="$HOME/.gradle/caches"
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME" "$ROOT/.build/jvmtmp"

exec "$GRADLE_BIN" --offline --console=plain "$@"
