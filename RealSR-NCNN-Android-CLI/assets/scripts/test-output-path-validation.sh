#!/bin/bash
#
# Regression test: verify that directory-input mode rejects file-like output paths.
#
# This script compiles and runs the C++ unit tests for the
# collect_input_output_files() validation logic in image_processor.h.
#
# Usage:
#   chmod +x test-output-path-validation.sh
#   ./test-output-path-validation.sh
#

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMMON_DIR="$SCRIPT_DIR/../../common"
TEST_SRC="$SCRIPT_DIR/test_output_path_validation.cpp"
TEST_BIN="/tmp/test_output_path_validation"

echo "=== Building regression test ==="
echo "  Source:   $TEST_SRC"
echo "  Include:  $COMMON_DIR"

g++ -std=c++11 -Wall -Wextra -I"$COMMON_DIR" -o "$TEST_BIN" "$TEST_SRC"

echo "=== Running regression test ==="
"$TEST_BIN"
EXIT_CODE=$?

rm -f "$TEST_BIN"

if [ $EXIT_CODE -eq 0 ]; then
    echo ""
    echo "=== ALL TESTS PASSED ==="
else
    echo ""
    echo "=== SOME TESTS FAILED ==="
fi

exit $EXIT_CODE
