/* MLV Core Library - Main wrapper file
 * This file serves as the main entry point for the shared library
 */

#include "mlv_include.h"
#include <stdint.h>
#include <stdlib.h>

void get_mlv_processed_thumbnail_8(
        mlvObject_t *video,
        int frame_index, int downscale_factor,
        int cpu_cores,
        unsigned char *out_buffer) {

    if (!video || !out_buffer) {
        return;
    }

    /* Get RAW frame info */
    int raw_w = video->RAWI.xRes;
    int raw_h = video->RAWI.yRes;

    if (raw_w <= 0 || raw_h <= 0) {
        return;
    }

    /* Allocate memory for the full raw frame */
    float *raw_frame = (float *) malloc(raw_w * raw_h * sizeof(float));
    if (!raw_frame) {
        return;
    }

    /* Get the float B&W raw bayer data */
    getMlvRawFrameFloat(video, frame_index, raw_frame);

    uint16_t *debayered_raw_frame = (uint16_t *) malloc(
            (size_t) (raw_w * raw_h * 3) * sizeof(uint16_t));
    if (!debayered_raw_frame) {
        free(raw_frame);
        return;
    }

    debayerBasic(debayered_raw_frame, raw_frame, raw_w, raw_h, 1);

    const int thumbW = raw_w / downscale_factor;
    const int thumbH = raw_h / downscale_factor;

    uint16_t *downscaled_image = (uint16_t *) malloc(
            (size_t) (thumbW * thumbH * 3) * sizeof(uint16_t));
    if (!downscaled_image) {
        free(raw_frame);
        return;
    }
    /* Downscale and debayer */
    for (int outY = 0; outY < thumbH; ++outY) {
        for (int outX = 0; outX < thumbW; ++outX) {
            uint64_t sum_r = 0;
            uint64_t sum_g = 0;
            uint64_t sum_b = 0;

            int start_y = outY * downscale_factor;
            int start_x = outX * downscale_factor;

            for (int j = 0; j < downscale_factor; j++) {
                for (int i = 0; i < downscale_factor; i++) {
                    size_t pixel_index = ((size_t) (start_y + j) * raw_w + (start_x + i)) * 3;
                    sum_r += debayered_raw_frame[pixel_index + 0];
                    sum_g += debayered_raw_frame[pixel_index + 1];
                    sum_b += debayered_raw_frame[pixel_index + 2];
                }
            }

            size_t out_pixel_index = ((size_t) outY * thumbW + outX) * 3;
            downscaled_image[out_pixel_index + 0] = (uint16_t) (sum_r / (downscale_factor *
                                                                         downscale_factor));
            downscaled_image[out_pixel_index + 1] = (uint16_t) (sum_g / (downscale_factor *
                                                                         downscale_factor));
            downscaled_image[out_pixel_index + 2] = (uint16_t) (sum_b / (downscale_factor *
                                                                         downscale_factor));
        }
    }

    uint16_t *downscaled_processed_image = (uint16_t *) malloc(
            (size_t) (thumbW * thumbH * 3) * sizeof(uint16_t));

    applyProcessingObject(video->processing,
                          thumbW, thumbH,
                          downscaled_image,
                          downscaled_processed_image,
                          cpu_cores, 1, frame_index);

    for (size_t i = 0; i < thumbW * thumbH; i++) {
        out_buffer[i * 4 + 0] = downscaled_processed_image[i * 3 + 0] >> 8; // R
        out_buffer[i * 4 + 1] = downscaled_processed_image[i * 3 + 1] >> 8; // G
        out_buffer[i * 4 + 2] = downscaled_processed_image[i * 3 + 2] >> 8; // B
        out_buffer[i * 4 + 3] = 255; // A
    }

    /* Cleanup */
    free(downscaled_processed_image);
    free(debayered_raw_frame);
    free(downscaled_image);
    free(raw_frame);
}
