#!/usr/bin/env bash
# Build and run the unit tests for the shared image_processor path logic.
#
# These tests are hermetic (no models / built CLI binaries needed) and run on
# Linux or macOS with any C++11 compiler. Override the compiler with CXX=...
#
#   ./run_tests.sh
#
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
CXX="${CXX:-c++}"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

BIN="$WORK/test_collect_input_output_files"

echo "Compiling with $CXX ..."
# -Wno-unused-function: image_processor.h / filesystem_utils.h define many static
# helpers; a single test translation unit legitimately leaves some unused.
"$CXX" -std=c++11 -Wall -Wno-unused-function \
    -o "$BIN" "$DIR/test_collect_input_output_files.cpp"

echo "Running tests ..."
"$BIN"
