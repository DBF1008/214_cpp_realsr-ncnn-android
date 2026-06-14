/*
 * Regression test: time_print_progress initialization
 *
 * Demonstrates the undefined behavior from using an uninitialized
 * high_resolution_clock::time_point in arithmetic, and verifies that
 * initializing it to `begin` produces correct, bounded time deltas.
 *
 * Build & run:
 *   g++ -std=c++11 -O2 -o test_timepoint_init test_timepoint_init.cpp && ./test_timepoint_init
 *
 * Exit code: 0 on success (all assertions pass), non-zero on failure.
 */

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>

using namespace std::chrono;

// Simulate the progress-print timing pattern found in realsr.cpp / mnnsr.cpp.
// Returns the first time_span_print_progress value computed.
double simulate_progress_pattern(bool initialize_time_print_progress) {
    high_resolution_clock::time_point begin = high_resolution_clock::now();

    // This is the critical line under test:
    //   FIXED:   time_print_progress = begin;
    //   BUG:     time_print_progress;  (uninitialized)
    high_resolution_clock::time_point time_print_progress;
    if (initialize_time_print_progress) {
        time_print_progress = begin;
    }

    // Simulate a small amount of work (like processing the first tile)
    volatile int dummy = 0;
    for (int i = 0; i < 100000; ++i) {
        dummy += i;
    }

    high_resolution_clock::time_point end = high_resolution_clock::now();
    double time_span_print_progress = duration_cast<duration<double>>(
            end - time_print_progress).count();

    return time_span_print_progress;
}

int main() {
    int failures = 0;

    printf("=== Regression test: time_print_progress initialization ===\n\n");

    // --- Test 1: Initialized variable should produce a small, non-negative delta ---
    printf("Test 1: Initialized time_print_progress = begin\n");
    for (int trial = 0; trial < 5; ++trial) {
        double delta = simulate_progress_pattern(/* initialize= */ true);
        printf("  Trial %d: delta = %.6f s\n", trial, delta);

        // With initialization to begin, the first delta should be very small
        // (just the time for the dummy loop) — certainly less than 5 seconds.
        if (delta < 0.0) {
            printf("  FAIL: negative delta (%.6f) — impossible with initialized timepoint\n", delta);
            ++failures;
        } else if (delta > 5.0) {
            printf("  FAIL: unexpectedly large delta (%.6f) — should be < 1s for trivial work\n", delta);
            ++failures;
        } else {
            printf("  PASS: delta is small and non-negative\n");
        }
    }

    printf("\n");

    // --- Test 2: Uninitialized variable produces unreliable results ---
    // Note: We don't assert failure here since UB can sometimes "accidentally"
    // produce a reasonable value. We just show that the behavior is unreliable.
    printf("Test 2: Uninitialized time_print_progress (demonstrating UB)\n");
    printf("  (Informational only — UB may produce any value)\n");
    for (int trial = 0; trial < 5; ++trial) {
        double delta = simulate_progress_pattern(/* initialize= */ false);
        printf("  Trial %d: delta = %.6f s", trial, delta);
        if (delta < 0.0 || delta > 3600.0) {
            printf("  <-- garbage value (abs value > 1 hour or negative)");
        }
        printf("\n");
    }

    printf("\n");

    // --- Test 3: Verify the fix matches the waifu2x.cpp pattern ---
    // The correct pattern initializes time_print_progress = begin, so the
    // first `end - time_print_progress` is essentially `end - begin`,
    // which is the elapsed time since the start of processing.
    printf("Test 3: Verify initialized delta ≈ elapsed time from begin\n");
    {
        high_resolution_clock::time_point begin = high_resolution_clock::now();
        high_resolution_clock::time_point time_print_progress = begin;  // THE FIX

        // Simulate work
        volatile int dummy = 0;
        for (int i = 0; i < 1000000; ++i) {
            dummy += i;
        }

        high_resolution_clock::time_point end = high_resolution_clock::now();
        double delta_from_tpp = duration_cast<duration<double>>(end - time_print_progress).count();
        double delta_from_begin = duration_cast<duration<double>>(end - begin).count();

        printf("  delta from time_print_progress: %.6f s\n", delta_from_tpp);
        printf("  delta from begin:               %.6f s\n", delta_from_begin);

        // Since time_print_progress == begin, both deltas should be identical
        double diff = std::abs(delta_from_tpp - delta_from_begin);
        if (diff < 0.001) {
            printf("  PASS: deltas match (diff = %.9f s)\n", diff);
        } else {
            printf("  FAIL: deltas diverge (diff = %.9f s)\n", diff);
            ++failures;
        }
    }

    printf("\n=== Summary ===\n");
    if (failures == 0) {
        printf("All tests PASSED.\n");
        return 0;
    } else {
        printf("%d test(s) FAILED.\n", failures);
        return 1;
    }
}
