// Host-side regression test for the GPU output tile writeback planner.
//
// Builds with a normal host compiler (no NCNN / Vulkan needed):
//   g++ -std=c++11 -I . test_tile_copy_plan.cpp -o /tmp/test_tile_copy_plan
//   /tmp/test_tile_copy_plan
//
// It validates plan_tile_band_copy() (the pure arithmetic shared by the
// waifu2x / srmd / realcugan GPU writeback helpers) and, crucially, simulates
// the exact byte-copy loop the helpers run, asserting that an edge or
// mis-sized band can never write outside the destination buffer.

#include "tile_copy_plan.h"

#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <vector>

using realsr_ncnn::TileBandCopyPlan;
using realsr_ncnn::plan_tile_band_copy;

static int g_failures = 0;

static void check(bool cond, const char* what)
{
    if (!cond)
    {
        fprintf(stderr, "FAIL: %s\n", what);
        g_failures++;
    }
}

static void check_plan(const char* name,
                       int src_w, int src_h, int dst_w, int dst_h, int dst_y0,
                       bool want_valid, int want_w, int want_h)
{
    TileBandCopyPlan p = plan_tile_band_copy(src_w, src_h, dst_w, dst_h, dst_y0);
    bool ok = (p.valid == want_valid);
    if (want_valid)
        ok = ok && (p.copy_w == want_w) && (p.copy_h == want_h) && (p.dst_y0 == dst_y0);
    if (!ok)
    {
        fprintf(stderr,
                "FAIL [%s]: src=%dx%d dst=%dx%d y0=%d -> valid=%d w=%d h=%d "
                "(wanted valid=%d w=%d h=%d)\n",
                name, src_w, src_h, dst_w, dst_h, dst_y0,
                p.valid, p.copy_w, p.copy_h, want_valid, want_w, want_h);
        g_failures++;
    }
}

// Faithfully reproduce the helper's int8 writeback loop against guarded buffers
// and assert that nothing outside the destination image is touched and that the
// copied region matches the source band.
static void simulate_writeback(const char* name,
                               int src_w, int src_h, int dst_w, int dst_h,
                               int dst_y0, int channels)
{
    const size_t dst_bytes = (size_t)dst_w * dst_h * channels;
    const size_t guard = 64;
    const unsigned char SENTINEL = 0xCC;
    const unsigned char GUARD = 0xAB;

    // [guard][dst image][guard]
    std::vector<unsigned char> buf(guard + dst_bytes + guard, GUARD);
    unsigned char* image = buf.data() + guard;
    memset(image, SENTINEL, dst_bytes);

    // Source band with a deterministic, position-dependent pattern.
    std::vector<unsigned char> src((size_t)src_w * src_h * channels, 0);
    for (int y = 0; y < src_h; y++)
        for (int x = 0; x < src_w; x++)
            for (int c = 0; c < channels; c++)
                src[((size_t)y * src_w + x) * channels + c] =
                    (unsigned char)((y * 131 + x * 7 + c * 17) & 0xFF);

    TileBandCopyPlan plan = plan_tile_band_copy(src_w, src_h, dst_w, dst_h, dst_y0);

    if (plan.valid)
    {
        unsigned char* dst = image + (size_t)plan.dst_y0 * dst_w * channels;
        const int dst_stride = dst_w * channels;
        const int src_stride = src_w * channels;
        const size_t row_bytes = (size_t)plan.copy_w * channels;
        for (int y = 0; y < plan.copy_h; y++)
            memcpy(dst + (size_t)y * dst_stride, src.data() + (size_t)y * src_stride, row_bytes);
    }

    // Guards must be intact: proves no out-of-bounds write.
    bool guard_ok = true;
    for (size_t i = 0; i < guard; i++)
        if (buf[i] != GUARD || buf[guard + dst_bytes + i] != GUARD)
            guard_ok = false;
    check(guard_ok, name); // guard integrity

    // Every destination byte is either the correctly copied source byte or the
    // untouched sentinel — never garbage, never a wrong-offset write.
    bool content_ok = true;
    for (int y = 0; y < dst_h && content_ok; y++)
    {
        for (int x = 0; x < dst_w && content_ok; x++)
        {
            const bool in_band = plan.valid &&
                                 y >= plan.dst_y0 && y < plan.dst_y0 + plan.copy_h &&
                                 x < plan.copy_w;
            for (int c = 0; c < channels; c++)
            {
                unsigned char got = image[((size_t)y * dst_w + x) * channels + c];
                if (in_band)
                {
                    int sy = y - plan.dst_y0;
                    unsigned char want =
                        (unsigned char)((sy * 131 + x * 7 + c * 17) & 0xFF);
                    if (got != want) { content_ok = false; break; }
                }
                else
                {
                    if (got != SENTINEL) { content_ok = false; break; }
                }
            }
        }
    }
    check(content_ok, name); // content correctness + no stray writes
}

int main()
{
    // --- pure plan arithmetic ---------------------------------------------
    // Interior, full tile.
    check_plan("interior", 800, 800, 800, 4000, 800, true, 800, 800);
    // Exact-fit last band.
    check_plan("exact-last", 800, 400, 800, 4000, 3600, true, 800, 400);
    // Oversized band (model emitted more rows than fit) -> clamped, no overflow.
    check_plan("oversized-last", 800, 500, 800, 4000, 3600, true, 800, 400);
    // Band whose start is exactly at the bottom edge -> nothing to copy.
    check_plan("at-bottom", 800, 400, 800, 4000, 4000, false, 0, 0);
    // Band start beyond the bottom edge -> nothing to copy.
    check_plan("beyond-bottom", 800, 400, 800, 4000, 4200, false, 0, 0);
    // Wider-than-image band -> width clamped.
    check_plan("width-clamp", 900, 100, 800, 4000, 0, true, 800, 100);
    // Whole image as a single band.
    check_plan("whole-image", 800, 4000, 800, 4000, 0, true, 800, 4000);
    // Small interior band (source smaller than remaining space).
    check_plan("small-interior", 800, 400, 800, 4000, 0, true, 800, 400);
    // Degenerate inputs.
    check_plan("zero-src-w", 0, 100, 800, 4000, 0, false, 0, 0);
    check_plan("zero-src-h", 800, 0, 800, 4000, 0, false, 0, 0);
    check_plan("neg-y0", 800, 100, 800, 4000, -10, false, 0, 0);
    check_plan("zero-dst", 800, 100, 0, 0, 0, false, 0, 0);

    // Real-coordinate example: w=10 h=10 scale=4 TILE=4 channels=3 -> out 40x40.
    // Last row band yi=2: out_tile_y0=8, band rows=(10-8)*4=8, dst_y0=8*4=32.
    check_plan("scaled-last-band", 40, 8, 40, 40, 32, true, 40, 8);
    // Same band but the model emitted one extra row (padding mismatch): clamp.
    check_plan("scaled-last-band-overshoot", 40, 9, 40, 40, 32, true, 40, 8);

    // --- byte-level writeback simulation (mirrors the helper's memcpy loop) -
    simulate_writeback("sim-interior",        800, 800, 800, 4000, 800, 3);
    simulate_writeback("sim-exact-last",      800, 400, 800, 4000, 3600, 3);
    simulate_writeback("sim-oversized-last",  800, 500, 800, 4000, 3600, 3); // the bug case
    simulate_writeback("sim-at-bottom",       800, 400, 800, 4000, 4000, 3);
    simulate_writeback("sim-width-clamp",     900, 100, 800, 4000, 0, 3);
    simulate_writeback("sim-rgba",            64, 64, 64, 256, 192, 4);
    simulate_writeback("sim-rgba-overshoot",  64, 80, 64, 256, 192, 4);   // clamp on RGBA
    simulate_writeback("sim-scaled-last",     40, 8, 40, 40, 32, 3);
    simulate_writeback("sim-scaled-overshoot",40, 9, 40, 40, 32, 3);      // clamp, no OOB

    if (g_failures == 0)
    {
        printf("ALL TESTS PASSED\n");
        return 0;
    }
    fprintf(stderr, "%d CHECK(S) FAILED\n", g_failures);
    return EXIT_FAILURE;
}
