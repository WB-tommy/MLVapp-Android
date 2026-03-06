#include <stdlib.h>
#include <stdint.h>

#include "llrawproc/llrawproc.h"
#include "../debayer/debayer.h"
#include "mlv_object.h"
#include "video_mlv.h"

/* Shared output helper: writes processed uint16 RGB (3ch) to out_buffer.
 * On Android: output is RGBA_8888 (4 bytes/pixel, A=255) for AndroidBitmap.
 * On Desktop: output is plain RGB (3 bytes/pixel). */
static void write_thumbnail_output(uint8_t *out_buffer,
                                   const uint16_t *processed,
                                   int pixel_count) {
#ifdef ANDROID
    for (int i = 0; i < pixel_count; i++) {
        out_buffer[i * 4 + 0] = (uint8_t) (processed[i * 3 + 0] >> 8); /* R */
        out_buffer[i * 4 + 1] = (uint8_t) (processed[i * 3 + 1] >> 8); /* G */
        out_buffer[i * 4 + 2] = (uint8_t) (processed[i * 3 + 2] >> 8); /* B */
        out_buffer[i * 4 + 3] = 255;                                  /* A */
    }
#else
    for (int i = 0; i < pixel_count * 3; i++)
        out_buffer[i] = (uint8_t)(processed[i] >> 8);
#endif
}

/* Fast thumbnail: pixel-skip downscale before debayer.
 * Quick preview quality — debayering on a subsampled bayer grid. */
void get_sub_sampling_downscale_thumnail(mlvObject_t *video, uint8_t *out_buffer,
                                         int downscale_factor, int threads) {
    int raw_w = video->RAWI.xRes;
    int raw_h = video->RAWI.yRes;
    int width = raw_w / downscale_factor;
    int height = raw_h / downscale_factor;

    int i, j;

    uint16_t *raw_frame = (uint16_t *) malloc(raw_w * raw_h * sizeof(uint16_t));
    if (getMlvRawFrameUint16(video, 0, raw_frame)) {
        free(raw_frame);
        return ;
    }

    int pixel_count = width * height;

    uint16_t *downscaled_frame = (uint16_t *) malloc(pixel_count * sizeof(uint16_t));
    if (!downscaled_frame) {
        free(raw_frame);
        return ;
    }

    for (i = 0; i < height; i++)
        for (j = 0; j < width; j++)
            downscaled_frame[i * width + j] =
                    raw_frame[(i * downscale_factor) * raw_w + (j * downscale_factor)];

    int shift_val = llrpHQDualIso(video) ? 0 : (16 - video->RAWI.raw_info.bits_per_pixel);

    float *float_thumb = (float *) malloc(pixel_count * sizeof(float));
    if (!float_thumb) {
        free(raw_frame);
        free(downscaled_frame);
        return ;
    }

    for (i = 0; i < pixel_count; i++)
        float_thumb[i] = (float) (downscaled_frame[i] << shift_val);

    uint16_t *debayered_frame = (uint16_t *) malloc(pixel_count * 3 * sizeof(uint16_t));
    if (!debayered_frame) {
        free(raw_frame);
        free(downscaled_frame);
        free(float_thumb);
        return ;
    }

    debayerBasic(debayered_frame, float_thumb, width, height, 1);

    uint16_t *processed_frame = (uint16_t *) malloc(pixel_count * 3 * sizeof(uint16_t));
    if (!processed_frame) {
        free(raw_frame);
        free(downscaled_frame);
        free(debayered_frame);
        free(float_thumb);
        return ;
    }

    applyProcessingObject(video->processing, width, height,
                          debayered_frame, processed_frame,
                          threads, 1, 0);

    write_thumbnail_output(out_buffer, processed_frame, pixel_count);

    free(processed_frame);
    free(debayered_frame);
    free(float_thumb);
    free(downscaled_frame);
    free(raw_frame);
}

/* Quality thumbnail: full debayer then area-average downscale.
 * Slower but accurate colours and no aliasing. */
void get_area_average_downscale_thumnail(mlvObject_t *video, uint8_t *out_buffer,
                                         int downscale_factor, int threads) {
    if (!video || !out_buffer) return;

    int raw_w = video->RAWI.xRes;
    int raw_h = video->RAWI.yRes;
    if (raw_w <= 0 || raw_h <= 0) return;

    float *raw_frame = (float *) malloc(raw_w * raw_h * sizeof(float));
    if (!raw_frame) return;

    getMlvRawFrameFloat(video, 0, raw_frame);

    uint16_t *debayered_frame = (uint16_t *) malloc(
            (size_t) (raw_w * raw_h * 3) * sizeof(uint16_t));
    if (!debayered_frame) {
        free(raw_frame);
        return;
    }

    debayerBasic(debayered_frame, raw_frame, raw_w, raw_h, 1);

    const int thumb_w = raw_w / downscale_factor;
    const int thumb_h = raw_h / downscale_factor;

    int pixel_count = thumb_w * thumb_h;

    uint16_t *downscaled_frame = (uint16_t *) malloc(pixel_count * 3 * sizeof(uint16_t));
    if (!downscaled_frame) {
        free(raw_frame);
        free(debayered_frame);
        return;
    }

    /* Area-average downscale */
    for (int out_y = 0; out_y < thumb_h; ++out_y) {
        for (int out_x = 0; out_x < thumb_w; ++out_x) {
            uint64_t sum_r = 0, sum_g = 0, sum_b = 0;
            int start_y = out_y * downscale_factor;
            int start_x = out_x * downscale_factor;

            for (int dy = 0; dy < downscale_factor; dy++) {
                for (int dx = 0; dx < downscale_factor; dx++) {
                    size_t src = ((size_t) (start_y + dy) * raw_w + (start_x + dx)) * 3;
                    sum_r += debayered_frame[src + 0];
                    sum_g += debayered_frame[src + 1];
                    sum_b += debayered_frame[src + 2];
                }
            }

            int area = downscale_factor * downscale_factor;
            size_t dst = ((size_t) out_y * thumb_w + out_x) * 3;
            downscaled_frame[dst + 0] = (uint16_t) (sum_r / area);
            downscaled_frame[dst + 1] = (uint16_t) (sum_g / area);
            downscaled_frame[dst + 2] = (uint16_t) (sum_b / area);
        }
    }

    uint16_t *processed_frame = (uint16_t *) malloc(pixel_count * 3 * sizeof(uint16_t));
    if (!processed_frame) {
        free(raw_frame);
        free(debayered_frame);
        free(downscaled_frame);
        return;
    }

    applyProcessingObject(video->processing, thumb_w, thumb_h,
                          downscaled_frame, processed_frame,
                          threads, 1, 0);

    write_thumbnail_output(out_buffer, processed_frame, pixel_count);

    free(processed_frame);
    free(downscaled_frame);
    free(debayered_frame);
    free(raw_frame);
}
