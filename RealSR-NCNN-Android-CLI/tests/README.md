# Host regression tests

Small, dependency-free C++ tests that pin behavior which is otherwise buried in
methods that only build under the Android NDK toolchain (NCNN / MNN / Vulkan /
OpenCV). They compile and run with any host C++11 compiler.

## progress_timer_regression.cpp

Guards the progress-print throttle timestamp in the tiled processing loops.

The "last progress print" reference (`time_print_progress`) used to be
default-constructed and then read before being assigned. A default-constructed
`std::chrono::time_point` equals the clock epoch, so the first interval
`end - time_print_progress` measured the absolute current time instead of
elapsed work — always `> 0.5` — forcing a spurious progress line on the very
first tile (magnitude implementation-defined, hence the non-deterministic
behavior). The fix anchors the reference to the loop-start timestamp `begin`.

Sites guarded (identical throttle expression in each):

- `RealSR/src/main/jni/realsr.cpp` — `RealSR::process` (GPU) and
  `RealSR::process_cpu` (CPU)
- `MNN-SR/src/main/jni/mnnsr.cpp` — `MNNSR::process`

### Run

```sh
./run.sh
```

Exit code `0` and a final `PASS` line mean the throttle behaves correctly:
a fast first tile does not print, a tile after >0.5 s prints, follow-up tiles
are throttled, and the final-tiles flush always prints.
