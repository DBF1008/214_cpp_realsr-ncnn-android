// Regression test for the progress-print throttle timestamp.
//
// Guards against this defect:
//   In the tiled processing loops, the "last progress print" reference was
//   declared as
//       high_resolution_clock::time_point time_print_progress;   // default ctor
//   and then used *before* being assigned, in
//       duration_cast<duration<double>>(end - time_print_progress).count() > 0.5
//
//   std::chrono::time_point's default constructor sets the value to the clock
//   epoch (time_since_epoch() == 0). So on the very first tile the expression
//   above does not measure elapsed work at all -- it evaluates to
//   end.time_since_epoch(), i.e. the absolute current time. That value is
//   implementation-defined (decades on the common system_clock-backed
//   high_resolution_clock) and is always > 0.5, which forces a spurious
//   progress line on the first tile regardless of how fast it was.
//
//   The fix anchors the reference to the loop-start timestamp:
//       high_resolution_clock::time_point time_print_progress = begin;
//
// Sites guarded by this test (all use the identical throttle expression):
//   RealSR/src/main/jni/realsr.cpp  RealSR::process      (GPU path)
//   RealSR/src/main/jni/realsr.cpp  RealSR::process_cpu  (CPU path)
//   MNN-SR/src/main/jni/mnnsr.cpp   MNNSR::process
//
// This test is intentionally self-contained: the production decision lives
// inside large methods that pull in NCNN / MNN / Vulkan / OpenCV and only build
// under the Android NDK toolchain, so we mirror the exact throttle decision
// here and drive it with deterministic (arithmetic, not wall-clock) timestamps.
// Build & run with: ./run.sh   (or any host C++11 compiler).

#include <chrono>
#include <cassert>
#include <cstdio>

using namespace std::chrono;

// Byte-for-byte mirror of the throttle decision used at the three sites above:
//   elapsed = duration_cast<duration<double>>(now - last).count();
//   if (elapsed > 0.5 || force) { last = now; return true; }
// On a print it advances `last` to `now`, exactly like the production code's
// `time_print_progress = end;`.
static bool should_print_progress(high_resolution_clock::time_point& last,
                                  high_resolution_clock::time_point now,
                                  bool force)
{
    double elapsed = duration_cast<duration<double>>(now - last).count();
    if (elapsed > 0.5 || force) {
        last = now;
        return true;
    }
    return false;
}

int main()
{
    int failures = 0;
    auto check = [&](bool cond, const char* what) {
        if (!cond) { fprintf(stderr, "FAIL: %s\n", what); ++failures; }
        else       { fprintf(stderr, "ok:   %s\n", what); }
    };

    // ---- Root-cause fact: default-constructed reference is the clock epoch ----
    // This is exactly why `end - time_print_progress` measured absolute time
    // instead of elapsed work. Guaranteed by the standard, so it is a stable
    // assertion (unlike the *magnitude* of now()-epoch, which is the very thing
    // that made the old bug non-deterministic across implementations).
    {
        high_resolution_clock::time_point uninitialized{}; // default ctor == epoch
        check(uninitialized.time_since_epoch() == high_resolution_clock::duration::zero(),
              "default-constructed time_point equals the clock epoch (root cause)");

        // Informational only (no assert): show how large the bogus "interval"
        // typically is when the epoch is used as the reference. Magnitude is
        // implementation-defined; on system_clock-backed clocks it is decades.
        double bogus = duration_cast<duration<double>>(
                           high_resolution_clock::now() - uninitialized).count();
        fprintf(stderr, "info: now() - default_time_point = %.1f s "
                        "(implementation-defined; > 0.5 forces a spurious first print)\n",
                bogus);
    }

    // ---- Fixed behavior: reference anchored to the loop-start timestamp ----
    // We simulate `begin` and a tile-completion `end` arithmetically so the test
    // is deterministic and flake-free (no real sleeping, no live now() in asserts).
    const high_resolution_clock::time_point begin = high_resolution_clock::now();

    // 1) A fast first tile (50 us of work) must NOT print: the throttle now sees
    //    a genuine ~0 elapsed, not the clock epoch.
    {
        high_resolution_clock::time_point ref = begin;          // the fix
        high_resolution_clock::time_point end = begin + microseconds(50);
        bool printed = should_print_progress(ref, end, /*force=*/false);
        check(!printed, "fast first tile does NOT spuriously print (the bug)");
    }

    // 2) Once >0.5 s of work has elapsed, it MUST print, and the reference must
    //    advance so the next print is throttled from that point.
    {
        high_resolution_clock::time_point ref = begin;
        high_resolution_clock::time_point end = begin + milliseconds(600);
        bool printed = should_print_progress(ref, end, /*force=*/false);
        check(printed, "tile after >0.5s elapsed prints");
        check(ref == end, "reference advances to the print time");

        // Immediately after, a fast follow-up tile must be throttled (no print).
        high_resolution_clock::time_point end2 = end + milliseconds(100);
        bool printed2 = should_print_progress(ref, end2, /*force=*/false);
        check(!printed2, "fast follow-up tile within 0.5s is throttled");

        // ...and another tile >0.5s later prints again.
        high_resolution_clock::time_point end3 = end + milliseconds(700);
        bool printed3 = should_print_progress(ref, end3, /*force=*/false);
        check(printed3, "tile >0.5s after last print prints again");
    }

    // 3) The "final tiles" flush condition (force=true) always prints regardless
    //    of elapsed time -- mirrors `(yi + 1 == ytiles && xi + 3 > xtiles)`.
    {
        high_resolution_clock::time_point ref = begin;
        high_resolution_clock::time_point end = begin + microseconds(10);
        bool printed = should_print_progress(ref, end, /*force=*/true);
        check(printed, "force flush prints even for a fast tile");
    }

    if (failures == 0) {
        fprintf(stderr, "\nPASS: all progress-timer regression checks passed\n");
        return 0;
    }
    fprintf(stderr, "\n%d check(s) FAILED\n", failures);
    return 1;
}
