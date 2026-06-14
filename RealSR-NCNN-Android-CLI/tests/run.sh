#!/usr/bin/env bash
# Build and run the progress-timer regression test on the host (no NDK needed).
set -euo pipefail

cd "$(dirname "$0")"

CXX="${CXX:-c++}"
SRC="progress_timer_regression.cpp"
BIN="$(mktemp -t progress_timer_regression.XXXXXX)"
trap 'rm -f "$BIN"' EXIT

echo "Compiling $SRC with $CXX ..."
"$CXX" -std=c++11 -Wall -Wextra -O2 "$SRC" -o "$BIN"

echo "Running ..."
"$BIN"
