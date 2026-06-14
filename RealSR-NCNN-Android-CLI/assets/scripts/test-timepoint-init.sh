#!/bin/bash
# Regression test: verify that all time_print_progress time_point variables
# are initialized at declaration to avoid undefined behavior.
#
# Bug: realsr.cpp and mnnsr.cpp previously declared
#   high_resolution_clock::time_point time_print_progress;
# without initialization, then used it in `end - time_print_progress`
# before first assignment — causing UB (garbage time delta, progress spam).
#
# The correct pattern (already used in waifu2x.cpp) is:
#   high_resolution_clock::time_point time_print_progress = begin;
#
# This test scans all .cpp source files for uninitialized declarations
# and fails if any are found.
#
# Usage:
#   test-timepoint-init.sh              # scan from repo root
#   test-timepoint-init.sh /path/to/src # scan specific directory

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Allow overriding scan root via argument
SCAN_ROOT="${1:-$REPO_ROOT}"

echo "=== Regression test: time_print_progress initialization ==="
echo "Scanning: $SCAN_ROOT"
echo ""

# Find all .cpp files that declare time_print_progress
violations=0
files_checked=0

while IFS= read -r file; do
    ((files_checked++)) || true
    rel_path="${file#$SCAN_ROOT/}"

    # Match lines declaring time_point time_print_progress WITHOUT "= begin" or "= ..."
    # Good:  high_resolution_clock::time_point time_print_progress = begin;
    # Bad:   high_resolution_clock::time_point time_print_progress;
    bad_lines=$(grep -n 'time_point\s\+time_print_progress\s*;' "$file" 2>/dev/null || true)

    if [[ -n "$bad_lines" ]]; then
        echo "FAIL: $rel_path has uninitialized time_print_progress:"
        echo "$bad_lines" | while IFS= read -r line; do
            echo "  $line"
        done
        ((violations++)) || true
    fi

    # Also verify that initialized declarations exist (positive check)
    good_lines=$(grep -n 'time_point\s\+time_print_progress\s*=' "$file" 2>/dev/null || true)
    if [[ -n "$good_lines" ]]; then
        echo "OK:   $rel_path — time_print_progress properly initialized:"
        echo "$good_lines" | while IFS= read -r line; do
            echo "  $line"
        done
    fi
done < <(find "$SCAN_ROOT" -name '*.cpp' -type f \
    -not -name 'test_*' -not -name '*_test.cpp' \
    -exec grep -l 'time_print_progress' {} +)

echo ""
echo "=== Summary ==="
echo "Files with time_print_progress: $files_checked"
echo "Violations (uninitialized):     $violations"
echo ""

if [[ "$violations" -gt 0 ]]; then
    echo "FAIL: Found uninitialized time_print_progress declarations."
    echo "Fix:  Add '= begin' after the variable name, e.g.:"
    echo "      high_resolution_clock::time_point time_print_progress = begin;"
    exit 1
else
    echo "PASS: All time_print_progress declarations are properly initialized."
    exit 0
fi
