// Safe, reusable planning helper for writing a GPU super-resolution output
// tile band back into the destination image.
//
// The NCNN GPU backends (waifu2x / srmd / realcugan / ...) process the image in
// horizontal, full-width bands, one per tile row. After the network runs on the
// GPU, each band's result (out_gpu) is downloaded and copied into the final
// output image at the right vertical offset.
//
// The historical code aliased the destination buffer with an external-pointer
// ncnn::Mat built from a fixed offset formula
// (yi * scale * TILE_SIZE_Y * w * scale * channels) and handed it straight to
// record_clone. When the last band's real height was not a full tile, or the
// model output size / boundary padding did not match that formula exactly, the
// declared band size or offset could disagree with the actual GPU result,
// making record_clone copy the wrong number of bytes or write outside the
// destination band (an out-of-bounds write on Android).
//
// This header provides the pure, side-effect-free arithmetic that decides how
// many rows/columns may be safely copied and where, clamped to the destination
// image. It has no NCNN or platform dependency so it can be unit tested on the
// host (see test_tile_copy_plan.cpp).

#ifndef REALSR_NCNN_TILE_COPY_PLAN_H
#define REALSR_NCNN_TILE_COPY_PLAN_H

#include <algorithm>

namespace realsr_ncnn {

struct TileBandCopyPlan
{
    int dst_y0;  // first destination row to write
    int copy_w;  // pixels per row to copy (clamped to destination width)
    int copy_h;  // number of rows to copy (clamped to destination height)
    bool valid;  // false => nothing should be copied
};

// Compute a copy plan that never reads past the source band or writes past the
// destination image. src_* describe the downloaded GPU band, dst_* the output
// image, and dst_y0 the vertical offset (in destination rows) where the band
// belongs. All quantities are in pixels/rows; the per-pixel channel size is
// handled by the caller.
//
// The plan is clamped so that:
//   * a band that starts at or beyond the bottom edge copies nothing,
//   * a band taller than the remaining rows is truncated to what fits,
//   * a band wider than the image is truncated to the image width.
// This makes the writeback robust to edge tiles whose real height is smaller
// than a full tile, and to any model whose output size does not match the
// assumed scale * tile formula exactly.
inline TileBandCopyPlan plan_tile_band_copy(int src_w, int src_h,
                                            int dst_w, int dst_h,
                                            int dst_y0)
{
    TileBandCopyPlan plan;
    plan.dst_y0 = dst_y0;
    plan.copy_w = 0;
    plan.copy_h = 0;
    plan.valid = false;

    // Nothing usable to copy from / into.
    if (src_w <= 0 || src_h <= 0 || dst_w <= 0 || dst_h <= 0)
        return plan;

    // Band starts outside the destination image vertically.
    if (dst_y0 < 0 || dst_y0 >= dst_h)
        return plan;

    plan.copy_w = std::min(src_w, dst_w);
    plan.copy_h = std::min(src_h, dst_h - dst_y0);
    plan.valid = plan.copy_w > 0 && plan.copy_h > 0;
    return plan;
}

} // namespace realsr_ncnn

#endif // REALSR_NCNN_TILE_COPY_PLAN_H
