/*
 * Copyright (C) 2017 Bouncyball
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the
 * Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor,
 * Boston, MA  02110-1301, USA.
 */

#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#include "llrawproc.h"
#include "pixelproc.h"
#include "stripes.h"
#include "patternnoise.h"
#include "dualiso.h"
#include "hist.h"
#include "darkframe.h"
#include "../../processing/raw_processing.h"

#define MIN(a,b) (((a)<(b))?(a):(b))
#define MAX(a,b) (((a)>(b))?(a):(b))
#define COERCE(x,lo,hi) MAX(MIN((x),(hi)),(lo))
#define ABS(a) ((a) > 0 ? (a) : -(a))

static int finite_double(double value)
{
    uint64_t bits = 0;
    memcpy(&bits, &value, sizeof(bits));
    return (bits & UINT64_C(0x7ff0000000000000)) !=
           UINT64_C(0x7ff0000000000000);
}

/* this is DNG feature only */
static void deflicker(mlvObject_t * video, uint16_t * raw_image_buff, size_t raw_image_size)
{
    uint16_t black = video->RAWI.raw_info.black_level;
    uint16_t white = (1 << video->RAWI.raw_info.bits_per_pixel) - 1;

    struct histogram * hist = hist_create(white);
    hist_add(hist, raw_image_buff + 1, (uint32_t)((raw_image_size - 1) / 2), 1);
    uint16_t median = hist_median(hist);
    double correction = log2((double) (video->llrawproc->deflicker_target - black) / (median - black));
    video->RAWI.raw_info.exposure_bias[0] = correction * 10000;
    video->RAWI.raw_info.exposure_bias[1] = 10000;
}

/* convert uncompressed 10/12bit raw data to 14bit for subsequent processing */
static void make_14bit(uint16_t * raw_image_buff, size_t raw_image_size, struct raw_info * raw_info)
{
    uint32_t pixel_count = raw_image_size / 2;
    int bits_shift = 14 - raw_info->bits_per_pixel;
    raw_info->black_level <<= bits_shift;
    raw_info->white_level <<= bits_shift;
    raw_info->bits_per_pixel = 14;
    raw_info->frame_size = raw_info->width * raw_info->height * 14 / 8;

    #pragma omp parallel for
    for(uint32_t i = 0; i < pixel_count; ++i)
    {
        raw_image_buff[i] <<= bits_shift;
    }
}

/* undo 14bit conversion to initial bit depth with rounding error minimizing */
static void undo_14bit(uint16_t * raw_image_buff, size_t raw_image_size, uint32_t bpp)
{
    uint32_t pixel_count = raw_image_size / 2;
    int bits_shift = 14 - bpp;
    /* calculate rounding number to be added to the raw value before shifting right to minimize rounding error */
    uint32_t rounding_number = (uint32_t)pow(2, bits_shift - 1);

    #pragma omp parallel for
    for(uint32_t i = 0; i < pixel_count; ++i)
    {
        raw_image_buff[i] = (raw_image_buff[i] + rounding_number) >> bits_shift;
    }
}

/* rescale restricted to imaginary 10-12bit levels of lossless raw data to about real 14bit range */
static void _scale_restricted_range(struct raw_info * raw_info, uint16_t * image_data)
{
    uint32_t pixel_count = raw_info->width * raw_info->height;
    /* find min and max level values in the currecnt raw frame */
    int32_t min_level = image_data[0];
    int32_t max_level = image_data[0];
    for(uint32_t i = 1; i < pixel_count; ++i)
    {
        if(image_data[i] < min_level) min_level = image_data[i];
        if(image_data[i] > max_level) max_level = image_data[i];
    }
#ifndef STDOUT_SILENT
    printf("min_level = %d, max_level = %d\n", min_level, max_level);
#endif
    raw_info->black_level = MAX(min_level, raw_info->black_level);
    raw_info->white_level = MAX(max_level, raw_info->white_level);

    int32_t scaled_white_level = 16200;
    double scale_ratio = (double)(scaled_white_level - raw_info->black_level) / (double)(raw_info->white_level - raw_info->black_level);
    raw_info->white_level = scaled_white_level;

#pragma omp parallel for
    for(uint32_t i = 0; i < pixel_count; ++i)
    {
        image_data[i] = MIN( (uint16_t)((double)((image_data[i] - raw_info->black_level) * scale_ratio + raw_info->black_level) + 0.5), 16383);
    }
}

/* rescale restricted to imaginary 10-12bit levels of lossless raw data to about real 14bit range */
static void scale_restricted_range(struct raw_info * raw_info, uint16_t * image_data, int low_iso, int high_iso)
{
    int32_t bd = ceil(log2(raw_info->white_level - raw_info->black_level));

    // Digital gain? Add 1 bit…
    int32_t add_bit = 0;

    if (low_iso != high_iso && high_iso >= 6400)
    {
        add_bit = 1;
    }

    int32_t actual_white_level = raw_info->black_level + ((1 << (bd + add_bit)) - 1);
    int32_t scaled_white_level = (raw_info->white_level - raw_info->black_level) * (1 << (14 - bd));

    double scale_ratio = (double)(scaled_white_level - raw_info->black_level) / (double)(actual_white_level - raw_info->black_level);

    raw_info->white_level = scaled_white_level;

    uint32_t pixel_count = raw_info->width * raw_info->height;

    #pragma omp parallel for
    for (uint32_t i = 0; i < pixel_count; ++i)
    {
        image_data[i] = MIN((uint16_t)((double)((image_data[i] - raw_info->black_level) * scale_ratio + raw_info->black_level) + 0.5), 16383);
    }
}

/* initialise low level raw processing struct */
llrawprocObject_t * initLLRawProcObject()
{
    llrawprocObject_t * llrawproc = calloc(1, sizeof(llrawprocObject_t));

    /* set defaults */
    llrawproc->vertical_stripes = 1;
    llrawproc->focus_pixels = 0;
    llrawproc->fpi_method = 0;
    llrawproc->bad_pixels = 1;
    llrawproc->bps_method = 0;
    llrawproc->bpi_method = 0;
    llrawproc->chroma_smooth = 0;
    llrawproc->pattern_noise = 0;
    llrawproc->deflicker_target = 0;
    llrawproc->fpm_status = 0;
    llrawproc->bpm_status = 0;
    llrawproc->compute_stripes = 0;
    llrawproc->dual_iso = 0;
    llrawproc->diso_pattern = 0;
    llrawproc->diso_auto_correction = -1;
    llrawproc->diso_ev_correction = 1;
    llrawproc->diso_black_delta = -1;
    llrawproc->diso_averaging = 0;
    llrawproc->diso_alias_map = 0;
    llrawproc->diso_frblending = 1;
    memset(&llrawproc->diso_last_frame_result, 0,
           sizeof(llrawproc->diso_last_frame_result));
    memset(&llrawproc->diso_presented_frame_result, 0,
           sizeof(llrawproc->diso_presented_frame_result));
    llrawproc->diso_processing_frame_applied = 0;
    llrawproc->dark_frame = 0;

    llrawproc->dark_frame_filename = NULL;
    llrawproc->dark_frame_fds[0] = -1;
    llrawproc->dark_frame_data = NULL;
    llrawproc->dark_frame_size = 0;
    llrawproc->dark_frame_data_source = DF_OFF;

    llrawproc->raw2ev = NULL;
    llrawproc->ev2raw = NULL;

    llrawproc->prev_black_level = -1;

    llrawproc->focus_pixel_map.type = PIX_FOCUS;
    llrawproc->focus_pixel_map.pixels = NULL;
    llrawproc->bad_pixel_map.type = PIX_BAD;
    llrawproc->bad_pixel_map.pixels = NULL;

    return llrawproc;
}

void freeLLRawProcObject(mlvObject_t * video)
{
    df_free_filename(video);
    df_free(video);
    free_luts(video->llrawproc->raw2ev, video->llrawproc->ev2raw);
    free_pixel_maps(&(video->llrawproc->focus_pixel_map), &(video->llrawproc->bad_pixel_map));
    free(video->llrawproc);
}

static llrpFrameResult_t default_frame_result(const mlvObject_t * video)
{
    llrpFrameResult_t result = { 0 };
    result.output_bit_depth = video->RAWI.raw_info.bits_per_pixel;
    result.black_level = video->RAWI.raw_info.black_level;
    result.white_level = video->RAWI.raw_info.white_level;
    result.cfa_pattern = video->RAWI.raw_info.cfa_pattern == 0
        ? UINT32_C(0x02010100)
        : video->RAWI.raw_info.cfa_pattern;
    result.pattern = ABS(video->llrawproc->diso_pattern);
    result.match_method = ABS(video->llrawproc->diso_auto_correction);
    if (result.match_method != DISO_MATCH_HISTOGRAM)
    {
        result.match_method = DISO_MATCH_ISO;
    }
    result.ev_correction = video->llrawproc->diso_ev_correction;
    result.black_delta = video->llrawproc->diso_black_delta;
    return result;
}

static void record_frame_result(mlvObject_t * video,
                                const llrpFrameResult_t * result)
{
    video->llrawproc->diso_last_frame_result = *result;
}

static void reset_dual_iso_results_locked(mlvObject_t * video)
{
    llrpFrameResult_t result = default_frame_result(video);
    video->llrawproc->diso_last_frame_result = result;
    video->llrawproc->diso_presented_frame_result = result;
    video->llrawproc->diso_processing_frame_applied = 0;
}

/* all low level raw processing takes place here.  The caller holds the
 * recursive processing mutex, so configuration and the auto-detection state
 * form one transaction with the resulting frame. */
static llrpFrameResult_t applyLLRawProcObjectInternal(mlvObject_t * video,
                                                       uint16_t * raw_image_buff,
                                                       size_t raw_image_size,
                                                       int prepared_as_rggb)
{
    llrpFrameResult_t frame_result = default_frame_result(video);
    if (prepared_as_rggb)
    {
        frame_result.cfa_pattern = UINT32_C(0x02010100);
    }

    /* if 'fix_raw == false' skip raw processing alltogether */
    if(!video->llrawproc->fix_raw)
    {
        record_frame_result(video, &frame_result);
        return frame_result;
    }

    /* Track what happened to this exact buffer.  A requested but unreadable or
     * size-incompatible dark frame must not suppress Dual ISO black matching. */
    int dark_frame_applied = 0;
    if (!df_init(video))
    {
#ifndef STDOUT_SILENT
        printf("Subtracting Dark Frame... ");
#endif
        dark_frame_applied =
            df_subtract(video, raw_image_buff, raw_image_size);
#ifndef STDOUT_SILENT
        printf("Done\n\n");
#endif
    }

    /* make copy of 'RAWI.raw_info' struct for subsequent modification */
    struct raw_info raw_info = video->RAWI.raw_info;
    if (prepared_as_rggb)
    {
        /* The MCRAW unpacker has already phase-normalized the Bayer plane.
         * Use that effective layout for CFA-sensitive corrections without
         * changing the clip metadata shared by other decoder workers. */
        raw_info.cfa_pattern = 0x02010100;
    }

    /* convert uncompressed 10/12bit raw data to 14bits for correct processing */
    if(video->RAWI.raw_info.bits_per_pixel < 14)
    {
        make_14bit(raw_image_buff, raw_image_size, &raw_info);
    }

    /* Initialize per-frame levels before attempting Dual ISO.  A failed frame
     * must never inherit 16-bit metadata from a previous successful frame. */
    video->llrawproc->dng_bit_depth =
        video->RAWI.raw_info.bits_per_pixel;
    video->llrawproc->dng_black_level =
        video->RAWI.raw_info.black_level;
    video->llrawproc->dng_white_level =
        video->RAWI.raw_info.white_level;

    /* initialise or update the LUTs if the black level has changed */
    if (video->llrawproc->prev_black_level != raw_info.black_level)
    {
        free_luts(video->llrawproc->raw2ev, video->llrawproc->ev2raw);
        video->llrawproc->raw2ev = get_raw2ev(raw_info.black_level);
        video->llrawproc->ev2raw = get_ev2raw(raw_info.black_level);

        video->llrawproc->prev_black_level = raw_info.black_level;
    }

    /* fix vertical stripes */
    if (video->llrawproc->vertical_stripes)
    {
        fix_vertical_stripes(&video->llrawproc->stripe_corrections,
                             raw_image_buff,
                             raw_info.black_level,
                             raw_info.white_level,
                             raw_info.frame_size,
                             video->RAWI.xRes,
                             video->RAWI.yRes,
                             video->llrawproc->vertical_stripes,
                             &video->llrawproc->compute_stripes);
    }

    /* fix focus pixels */
    if (video->llrawproc->focus_pixels && video->llrawproc->fpm_status < 3)
    {
        /* detect crop_rec mode */
        int crop_rec = (llrpDetectFocusDotFixMode(video) == 2) ? 1 : (video->llrawproc->focus_pixels == 2);
        /* if raw data is lossless set unified mode */
        int unified_mode = (video->MLVI.videoClass & MLV_VIDEO_CLASS_FLAG_LJ92) ? 5 : 0;
        fix_focus_pixels(&video->llrawproc->focus_pixel_map,
                         &video->llrawproc->fpm_status,
                         raw_image_buff,
                         video->IDNT.cameraModel,
                         video->RAWI.xRes,
                         video->RAWI.yRes,
                         video->VIDF.panPosX,
                         video->VIDF.panPosY,
                         video->RAWI.raw_info.width,
                         video->RAWI.raw_info.height,
                         crop_rec,
                         unified_mode,
                         video->llrawproc->fpi_method,
                         (video->llrawproc->dual_iso),
                         video->llrawproc->raw2ev,
                         video->llrawproc->ev2raw);
    }

    /* fix bad pixels */
    if (video->llrawproc->bad_pixels && video->llrawproc->bpm_status < 3)
    {
        fix_bad_pixels(&video->llrawproc->bad_pixel_map,
                       &video->llrawproc->bpm_status,
                       raw_image_buff,
                       video->IDNT.cameraModel,
                       video->RAWI.xRes,
                       video->RAWI.yRes,
                       video->VIDF.panPosX,
                       video->VIDF.panPosY,
                       video->RAWI.raw_info.width,
                       video->RAWI.raw_info.height,
                       raw_info.black_level,
                       video->llrawproc->bad_pixels,
                       video->llrawproc->bps_method,
                       video->llrawproc->bpi_method,
                       (video->llrawproc->dual_iso),
                       video->llrawproc->raw2ev,
                       video->llrawproc->ev2raw);
    }

    /* fix pattern noise */
    if (!video->llrawproc->diso_validity && video->llrawproc->pattern_noise)
    {
#ifndef STDOUT_SILENT
        printf("Fixing pattern noise... ");
#endif
        fix_pattern_noise((int16_t *)raw_image_buff, video->RAWI.xRes, video->RAWI.yRes, raw_info.white_level, 0);
#ifndef STDOUT_SILENT
        printf("Done\n\n");
#endif
    }

    /* If Dual ISO is requested, keep destructive restricted-range scaling on
     * a scratch plane. Failed auto-detection must leave both the source
     * representation and the resolved control state untouched. */
    if(video->llrawproc->diso_validity &&
       video->llrawproc->dual_iso == DISO_20BIT)
    {
        const int pre_diso_black_level = raw_info.black_level;
        raw_info.width = video->RAWI.xRes;
        raw_info.height = video->RAWI.yRes;
        raw_info.pitch = video->RAWI.xRes;
        raw_info.active_area.x1 = 0;
        raw_info.active_area.y1 = 0;
        raw_info.active_area.x2 = raw_info.width;
        raw_info.active_area.y2 = raw_info.height;
        
        const size_t diso_size =
            (size_t)raw_info.width * raw_info.height * sizeof(uint16_t);
        const int restricted_lossless =
            (video->MLVI.videoClass & MLV_VIDEO_CLASS_FLAG_LJ92) &&
            raw_info.white_level < 15000;
        uint16_t * diso_scratch = NULL;
        uint16_t * diso_buffer = raw_image_buff;
        if (restricted_lossless && diso_size <= raw_image_size)
        {
            /* Scaling a restricted lossless plane is destructive.  Keep only
             * that uncommon path transactional; ordinary Dual ISO writes the
             * uint16 input solely when its final conversion succeeds. */
            diso_scratch = malloc(diso_size);
            diso_buffer = diso_scratch;
        }

        if (diso_size <= raw_image_size &&
            (!restricted_lossless || diso_scratch != NULL))
        {
            if (diso_scratch != NULL)
            {
                memcpy(diso_scratch, raw_image_buff, diso_size);
            }

            struct raw_info diso_raw_info = raw_info;

            if(restricted_lossless)
            {
#ifndef STDOUT_SILENT
                printf("\nScaling raw data range...\n");
                printf("Raw_Black = %d, Raw_White = %d <= BEFORE SCALING\n",
                       diso_raw_info.black_level, diso_raw_info.white_level);
#endif
                int low_iso = MIN(video->llrawproc->diso1,
                                  video->llrawproc->diso2);
                int high_iso = MAX(video->llrawproc->diso1,
                                   video->llrawproc->diso2);

                scale_restricted_range(&diso_raw_info, diso_buffer,
                                       low_iso, high_iso);

#ifndef STDOUT_SILENT
                printf("Raw_Black = %d, Raw_White = %d <= AFTER SCALING\n",
                       diso_raw_info.black_level, diso_raw_info.white_level);
#endif
            }

            int resolved_pattern = video->llrawproc->diso_pattern;
            int resolved_match = video->llrawproc->diso_auto_correction;
            double resolved_ev = video->llrawproc->diso_ev_correction;
            int resolved_black = video->llrawproc->diso_black_delta;

            const int diso_applied = diso_get_full20bit(
                diso_raw_info,
                diso_buffer,
                dark_frame_applied,
                video->llrawproc->diso1,
                video->llrawproc->diso2,
                &resolved_pattern,
                &resolved_match,
                &resolved_ev,
                &resolved_black,
                video->llrawproc->diso_averaging,
                video->llrawproc->diso_alias_map,
                video->llrawproc->diso_frblending,
                video->llrawproc->chroma_smooth,
                video->cpu_cores);

            if (diso_applied)
            {
                if (diso_scratch != NULL)
                {
                    memcpy(raw_image_buff, diso_scratch, diso_size);
                }
                raw_info = diso_raw_info;

                /* Upstream uses negative values as one-shot auto-analysis
                 * requests.  Commit only successful results so a failed frame
                 * retries; pattern 5 remains 5 and detects every frame. */
                if (resolved_pattern < 0)
                {
                    resolved_pattern = -resolved_pattern;
                }
                if (resolved_match < 0)
                {
                    resolved_match = -resolved_match;
                }
                video->llrawproc->diso_pattern = resolved_pattern;
                video->llrawproc->diso_auto_correction = resolved_match;
                video->llrawproc->diso_ev_correction = resolved_ev;
                video->llrawproc->diso_black_delta = resolved_black;

                /* Full20bit produces true 16-bit samples. */
                int bits_shift = 16 - raw_info.bits_per_pixel;
                frame_result.dual_iso_applied = 1;
                frame_result.output_bit_depth = 16;
                frame_result.black_level = raw_info.black_level << bits_shift;
                frame_result.white_level = raw_info.white_level << bits_shift;
                frame_result.pattern = resolved_pattern;
                frame_result.match_method = resolved_match;
                frame_result.ev_correction = resolved_ev;
                frame_result.black_delta = resolved_black;
                video->llrawproc->dng_black_level = frame_result.black_level;
                video->llrawproc->dng_white_level = frame_result.white_level;
                video->llrawproc->dng_bit_depth = frame_result.output_bit_depth;

                /* For Dual ISO the black level changed to 16-bit. */
                free_luts(video->llrawproc->raw2ev,
                          video->llrawproc->ev2raw);
                video->llrawproc->raw2ev =
                    get_raw2ev(video->llrawproc->dng_black_level);
                video->llrawproc->ev2raw =
                    get_ev2raw(video->llrawproc->dng_black_level);

                /* fix focus pixels */
                if (video->llrawproc->focus_pixels &&
                    video->llrawproc->fpm_status < 3)
                {
                    int crop_rec =
                        (llrpDetectFocusDotFixMode(video) == 2) ? 1 :
                        (video->llrawproc->focus_pixels == 2);
                    int unified_mode =
                        (video->MLVI.videoClass &
                         MLV_VIDEO_CLASS_FLAG_LJ92) ? 5 : 0;
                    fix_focus_pixels(&video->llrawproc->focus_pixel_map,
                                     &video->llrawproc->fpm_status,
                                     raw_image_buff,
                                     video->IDNT.cameraModel,
                                     video->RAWI.xRes,
                                     video->RAWI.yRes,
                                     video->VIDF.panPosX,
                                     video->VIDF.panPosY,
                                     video->RAWI.raw_info.width,
                                     video->RAWI.raw_info.height,
                                     crop_rec,
                                     unified_mode,
                                     2,
                                     0,
                                     video->llrawproc->raw2ev,
                                     video->llrawproc->ev2raw);
                }

                /* fix bad pixels */
                if (video->llrawproc->bad_pixels &&
                    video->llrawproc->bpm_status < 3)
                {
                    fix_bad_pixels(&video->llrawproc->bad_pixel_map,
                                   &video->llrawproc->bpm_status,
                                   raw_image_buff,
                                   video->IDNT.cameraModel,
                                   video->RAWI.xRes,
                                   video->RAWI.yRes,
                                   video->VIDF.panPosX,
                                   video->VIDF.panPosY,
                                   video->RAWI.raw_info.width,
                                   video->RAWI.raw_info.height,
                                   raw_info.black_level,
                                   video->llrawproc->bad_pixels,
                                   video->llrawproc->bps_method,
                                   2,
                                   0,
                                   video->llrawproc->raw2ev,
                                   video->llrawproc->ev2raw);
                }

                /* revert LUTs */
                free_luts(video->llrawproc->raw2ev,
                          video->llrawproc->ev2raw);
                video->llrawproc->raw2ev =
                    get_raw2ev(pre_diso_black_level);
                video->llrawproc->ev2raw =
                    get_ev2raw(pre_diso_black_level);
                video->llrawproc->prev_black_level =
                    pre_diso_black_level;
            }

        }
        free(diso_scratch);

    }

    /* do chroma smoothing */
    if (video->llrawproc->chroma_smooth && !frame_result.dual_iso_applied)
    {
#ifndef STDOUT_SILENT
            printf("\nUsing chroma smooth method: '%dx%d'\n\n", video->llrawproc->chroma_smooth, video->llrawproc->chroma_smooth);
#endif
        chroma_smooth(video->llrawproc->chroma_smooth,
                      raw_image_buff,
                      video->RAWI.xRes,
                      video->RAWI.yRes,
                      raw_info.black_level,
                      raw_info.white_level,
                      video->llrawproc->raw2ev,
                      video->llrawproc->ev2raw);
    }

    /* undo 14bit conversion of uncompressed 10/12bit raw data, except when 20bit dual iso processing is active */
    if(video->RAWI.raw_info.bits_per_pixel < 14 &&
       !frame_result.dual_iso_applied)
    {
        undo_14bit(raw_image_buff, raw_image_size, video->RAWI.raw_info.bits_per_pixel);
    }

    /* deflicker RAW data by changing 'tcBaselineExposure' tag in the exported DNG */
    /*
    if (video->llrawproc->deflicker_target)
    {
#ifndef STDOUT_SILENT
        printf("Per-frame exposure compensation: 'ON'\nDeflicker target: '%d'\n\n", video->llrawproc->deflicker_target);
#endif
        deflicker(video, raw_image_buff, raw_image_size);
    }
    */

#ifndef STDOUT_SILENT
    printf("raw_image_buff[1000] = %u, Proc_Black = %.0f, Proc_White = %d, Raw_Black = %d, Raw_White = %d <= THE END OF LLRAWPROC\n",
           raw_image_buff[1000],
           (double)video->processing->black_level,
           video->processing->white_level,
           video->RAWI.raw_info.black_level,
           video->RAWI.raw_info.white_level);
#endif

    record_frame_result(video, &frame_result);
    return frame_result;
}

llrpFrameResult_t applyLLRawProcObject(mlvObject_t * video,
                                       uint16_t * raw_image_buff,
                                       size_t raw_image_size)
{
    pthread_mutex_lock(&video->processing_mutex);
    llrpFrameResult_t result =
        applyLLRawProcObjectInternal(video, raw_image_buff,
                                     raw_image_size, 0);
    pthread_mutex_unlock(&video->processing_mutex);
    return result;
}

llrpFrameResult_t applyLLRawProcObjectPreparedRggb(mlvObject_t * video,
                                                   uint16_t * raw_image_buff,
                                                   size_t raw_image_size)
{
    pthread_mutex_lock(&video->processing_mutex);
    llrpFrameResult_t result =
        applyLLRawProcObjectInternal(video, raw_image_buff,
                                     raw_image_size, 1);
    pthread_mutex_unlock(&video->processing_mutex);
    return result;
}

/* Detect focus dot fix mode according to RAWC block info (binning + skipping) and camera ID
   Return value 0 = off, 1 = On, 2 = CropRec */
int llrpDetectFocusDotFixMode(mlvObject_t * video)
{
    switch(video->IDNT.cameraModel)
    {
        case 0x80000331: // EOSM
        case 0x80000355: // EOSM2
        case 0x80000346: // 100D
        case 0x80000301: // 650D
        case 0x80000326: // 700D
            if(video->RAWC.blockType[0])
            {
                int sampling_x = video->RAWC.binning_x + video->RAWC.skipping_x;
                int sampling_y = video->RAWC.binning_y + video->RAWC.skipping_y;
                if( (video->RAWI.raw_info.height < 900) && !(sampling_y == 5 && sampling_x == 3) )
                {
                    return 2;
                }
            }
            return 1;

        default: // All other cameras
            return 0;
    }
}

/* LLRawProcObject variable handling */
int llrpGetFixRawMode(mlvObject_t * video)
{
    return video->llrawproc->fix_raw;
}

void llrpSetFixRawMode(mlvObject_t * video, int value)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->fix_raw = value;
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpGetVerticalStripeMode(mlvObject_t * video)
{
    return video->llrawproc->vertical_stripes;
}

void llrpSetVerticalStripeMode(mlvObject_t * video, int value)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->vertical_stripes = value;
    pthread_mutex_unlock(&video->processing_mutex);
}

void llrpComputeStripesOn(mlvObject_t * video)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->compute_stripes = 1;
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpGetFocusPixelMode(mlvObject_t * video)
{
    return video->llrawproc->focus_pixels;
}

void llrpSetFocusPixelMode(mlvObject_t * video, int value)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->focus_pixels = value;
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpGetFocusPixelInterpolationMethod(mlvObject_t * video)
{
    return video->llrawproc->fpi_method;
}

void llrpSetFocusPixelInterpolationMethod(mlvObject_t * video, int value)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->fpi_method = value;
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpGetBadPixelMode(mlvObject_t * video)
{
    return video->llrawproc->bad_pixels;
}

void llrpSetBadPixelMode(mlvObject_t * video, int value)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->bad_pixels = value;
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpGetBadPixelSearchMethod(mlvObject_t *video)
{
    return video->llrawproc->bps_method;
}

void llrpSetBadPixelSearchMethod(mlvObject_t * video, int value)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->bps_method = value;
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpGetBadPixelInterpolationMethod(mlvObject_t * video)
{
    return video->llrawproc->bpi_method;
}

void llrpSetBadPixelInterpolationMethod(mlvObject_t * video, int value)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->bpi_method = value;
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpGetChromaSmoothMode(mlvObject_t * video)
{
    return video->llrawproc->chroma_smooth;
}

void llrpSetChromaSmoothMode(mlvObject_t * video, int value)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->chroma_smooth = value;
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpGetPatternNoiseMode(mlvObject_t * video)
{
    return video->llrawproc->pattern_noise;
}

void llrpSetPatternNoiseMode(mlvObject_t * video, int value)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->pattern_noise = value;
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpGetDeflickerTarget(mlvObject_t * video)
{
    return video->llrawproc->deflicker_target;
}

void llrpSetDeflickerTarget(mlvObject_t * video, int value)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->deflicker_target = value;
    pthread_mutex_unlock(&video->processing_mutex);
}

static void set_dual_iso_validity_locked(mlvObject_t * video,
                                         int diso_force)
{
    int iso1 = (int)video->EXPO.isoValue;
    if (iso1 < 100)
    {
        iso1 = 100;
    }

    if (diso_force)
    {
        video->llrawproc->diso_validity = DISO_FORCED;
        video->llrawproc->diso1 = iso1;
        video->llrawproc->diso2 = iso1;
    }
    else if (video->DISO.blockType[0] && video->DISO.dualMode)
    {
        video->llrawproc->diso_validity = DISO_VALID;

        int iso2 = (int)video->DISO.isoValue;
        if (iso2 < 0)
        {
            if (iso2 < -6)
            {
                iso2 = iso1 / pow(2, ABS(iso2) - 6);
            }
            else
            {
                iso2 = iso1 * pow(2, ABS(7 + iso2));
            }
            iso2 = COERCE(iso2, 100, 3200);
        }
        else if (iso2 < 100)
        {
            iso2 = iso1 * pow(2, iso2) / (iso1 / 100);
        }

        video->llrawproc->diso1 = iso1;
        video->llrawproc->diso2 = iso2;
    }
    else
    {
        video->llrawproc->diso_validity = DISO_INVALID;
        video->llrawproc->diso1 = iso1;
        video->llrawproc->diso2 = iso1;
    }
}

static void rearm_dual_iso_locked(mlvObject_t * video,
                                   int pattern,
                                   int match_method,
                                   double ev_correction,
                                   int black_delta)
{
    video->llrawproc->diso_pattern = pattern;
    video->llrawproc->diso_auto_correction =
        (ev_correction == 1.0 || black_delta == -1)
            ? -match_method
            : match_method;
    video->llrawproc->diso_ev_correction = ev_correction;
    video->llrawproc->diso_black_delta = black_delta;
    reset_dual_iso_results_locked(video);
}

int llrpSetDualIsoConfig(mlvObject_t * video,
                         const llrpDualIsoConfig_t * config)
{
    if (video == NULL || video->llrawproc == NULL || config == NULL ||
        config->pattern < DISO_PATTERN_AUTO ||
        config->pattern > DISO_PATTERN_AUTO_EVERY_FRAME ||
        (config->match_method != DISO_MATCH_ISO &&
         config->match_method != DISO_MATCH_HISTOGRAM) ||
        (config->ev_correction != 1.0 &&
         (!finite_double(config->ev_correction) ||
          config->ev_correction < -6.0 || config->ev_correction > 0.0)) ||
        (config->black_delta != -1 &&
         (config->black_delta < 0 || config->black_delta > 100)) ||
        (config->interpolation != DISOI_AMAZE &&
         config->interpolation != DISOI_MEAN23))
    {
        return -1;
    }

    pthread_mutex_lock(&video->processing_mutex);

    const int force = config->force ? 1 : 0;
    const int match_method = force
        ? DISO_MATCH_HISTOGRAM
        : config->match_method;

    set_dual_iso_validity_locked(video, force);
    video->llrawproc->dual_iso =
        config->mode == DISO_OFF ? DISO_OFF : DISO_20BIT;
    rearm_dual_iso_locked(video,
                           config->pattern,
                           match_method,
                           config->ev_correction,
                           config->black_delta);
    video->llrawproc->diso_averaging = config->interpolation;
    video->llrawproc->diso_alias_map = config->alias_map ? 1 : 0;
    video->llrawproc->diso_frblending =
        config->fullres_blending ? 1 : 0;

    video->llrawproc->dng_bit_depth =
        video->RAWI.raw_info.bits_per_pixel;
    video->llrawproc->dng_black_level =
        video->RAWI.raw_info.black_level;
    video->llrawproc->dng_white_level =
        video->RAWI.raw_info.white_level;

    /* Both maps depend on the line pattern and Dual ISO interpolation path. */
    reset_fpm_status(&video->llrawproc->focus_pixel_map,
                     &video->llrawproc->fpm_status);
    reset_bpm_status(&video->llrawproc->bad_pixel_map,
                     &video->llrawproc->bpm_status);

    pthread_mutex_unlock(&video->processing_mutex);
    return 0;
}

int llrpGetDualIsoConfig(mlvObject_t * video,
                         llrpDualIsoConfig_t * config)
{
    if (video == NULL || video->llrawproc == NULL || config == NULL)
    {
        return -1;
    }

    pthread_mutex_lock(&video->processing_mutex);
    config->mode = video->llrawproc->dual_iso;
    config->force = video->llrawproc->diso_validity == DISO_FORCED;
    const llrpFrameResult_t * presented =
        &video->llrawproc->diso_presented_frame_result;
    config->pattern = presented->pattern;
    config->match_method = presented->match_method;
    config->ev_correction = presented->ev_correction;
    config->black_delta = presented->black_delta;
    config->interpolation = video->llrawproc->diso_averaging;
    config->alias_map = video->llrawproc->diso_alias_map;
    config->fullres_blending = video->llrawproc->diso_frblending;
    config->validity = video->llrawproc->diso_validity;
    config->last_frame_applied = presented->dual_iso_applied;
    config->last_output_bit_depth = presented->output_bit_depth;
    pthread_mutex_unlock(&video->processing_mutex);
    return 0;
}

void llrpPublishDualIsoFrameResult(mlvObject_t * video,
                                   const llrpFrameResult_t * result)
{
    if (video == NULL || video->llrawproc == NULL || result == NULL)
    {
        return;
    }
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->diso_presented_frame_result = *result;
    video->llrawproc->diso_processing_frame_applied =
        result->dual_iso_applied;
    /* Cached decode workers can finish out of display order.  Install the
     * normalization paired with the frame being presented, rather than the
     * most recently decoded frame.  Successful Dual ISO results already
     * carry 16-bit levels; ordinary/failed frames carry source-scale levels. */
    if (video->processing != NULL && result->output_bit_depth > 0)
    {
        processingSetBlackAndWhiteLevel(video->processing,
                                        result->black_level,
                                        result->white_level,
                                        result->output_bit_depth);
    }
    pthread_mutex_unlock(&video->processing_mutex);
}

void llrpSetProcessingDualIsoFrameResult(mlvObject_t * video,
                                         const llrpFrameResult_t * result)
{
    if (video == NULL || video->llrawproc == NULL || result == NULL)
    {
        return;
    }
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->diso_processing_frame_applied =
        result->dual_iso_applied;
    if (video->processing != NULL && result->output_bit_depth > 0)
    {
        processingSetBlackAndWhiteLevel(video->processing,
                                        result->black_level,
                                        result->white_level,
                                        result->output_bit_depth);
    }
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpGetDualIsoMode(mlvObject_t * video)
{
    pthread_mutex_lock(&video->processing_mutex);
    int value = video->llrawproc->dual_iso;
    pthread_mutex_unlock(&video->processing_mutex);
    return value;
}

void llrpSetDualIsoMode(mlvObject_t * video, int value)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->dual_iso =
        value == DISO_OFF ? DISO_OFF : DISO_20BIT;
    reset_dual_iso_results_locked(video);
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpGetDualIsoInterpolationMethod(mlvObject_t * video)
{
    return video->llrawproc->diso_averaging;
}

void llrpSetDualIsoInterpolationMethod(mlvObject_t * video, int value)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->diso_averaging = value;
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpGetDualIsoAliasMapMode(mlvObject_t * video)
{
    return video->llrawproc->diso_alias_map;
}

void llrpSetDualIsoAliasMapMode(mlvObject_t * video, int value)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->diso_alias_map = value;
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpGetDualIsoFullResBlendingMode(mlvObject_t * video)
{
    return video->llrawproc->diso_frblending;
}

void llrpSetDualIsoFullResBlendingMode(mlvObject_t * video, int value)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->diso_frblending = value;
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpGetDualIsoValidity(mlvObject_t * video)
{
    pthread_mutex_lock(&video->processing_mutex);
    int value = video->llrawproc->diso_validity;
    pthread_mutex_unlock(&video->processing_mutex);
    return value;
}

void llrpSetDualIsoValidity(mlvObject_t * video, int diso_force)
{
    pthread_mutex_lock(&video->processing_mutex);
    set_dual_iso_validity_locked(video, diso_force ? 1 : 0);
    rearm_dual_iso_locked(video,
                           DISO_PATTERN_AUTO,
                           diso_force ? DISO_MATCH_HISTOGRAM
                                      : DISO_MATCH_ISO,
                           1.0,
                           -1);
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpHQDualIso(mlvObject_t * video)
{
    pthread_mutex_lock(&video->processing_mutex);
    int value = video->llrawproc->diso_presented_frame_result.dual_iso_applied;
    pthread_mutex_unlock(&video->processing_mutex);
    return value;
}

void llrpResetDngBWLevels(mlvObject_t * video)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->dng_bit_depth = video->RAWI.raw_info.bits_per_pixel;
    video->llrawproc->dng_black_level = video->RAWI.raw_info.black_level;
    video->llrawproc->dng_white_level = video->RAWI.raw_info.white_level;
    video->llrawproc->diso_last_frame_result = default_frame_result(video);
    pthread_mutex_unlock(&video->processing_mutex);
}

void llrpResetFpmStatus(mlvObject_t * video)
{
    pthread_mutex_lock(&video->processing_mutex);
    reset_fpm_status(&(video->llrawproc->focus_pixel_map), &(video->llrawproc->fpm_status));
    pthread_mutex_unlock(&video->processing_mutex);
}

void llrpResetBpmStatus(mlvObject_t * video)
{
    pthread_mutex_lock(&video->processing_mutex);
    reset_bpm_status(&(video->llrawproc->bad_pixel_map), &(video->llrawproc->bpm_status));
    pthread_mutex_unlock(&video->processing_mutex);
}

/* dark frame stuff */
void llrpInitDarkFrameExtFileName(mlvObject_t * video, char * df_filename)
{
    pthread_mutex_lock(&video->processing_mutex);
    df_free_filename(video);
    df_init_filename(video, df_filename);
    pthread_mutex_unlock(&video->processing_mutex);
}

void llrpFreeDarkFrameExtFileName(mlvObject_t * video)
{
    pthread_mutex_lock(&video->processing_mutex);
    df_free_filename(video);
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpGetDarkFrameMode(mlvObject_t * video)
{
    return video->llrawproc->dark_frame;
}

void llrpSetDarkFrameMode(mlvObject_t * video, int value)
{
    pthread_mutex_lock(&video->processing_mutex);
    video->llrawproc->dark_frame = value;

    /* Dark-frame subtraction changes the two exposure black offsets.  Rearm
     * even for same-mode calls: replacing an external DF while DF_EXT is
     * active is still a new source. */
    int match_method = ABS(video->llrawproc->diso_auto_correction);
    if (match_method != DISO_MATCH_HISTOGRAM)
    {
        match_method = video->llrawproc->diso_validity == DISO_FORCED
            ? DISO_MATCH_HISTOGRAM
            : DISO_MATCH_ISO;
    }
    video->llrawproc->diso_auto_correction = -match_method;
    video->llrawproc->diso_black_delta = -1;
    reset_dual_iso_results_locked(video);
    reset_bpm_status(&video->llrawproc->bad_pixel_map,
                     &video->llrawproc->bpm_status);
    video->llrawproc->compute_stripes = 1;
    pthread_mutex_unlock(&video->processing_mutex);
}

int llrpGetDarkFrameExtStatus(mlvObject_t * video)
{
    if(video->llrawproc->dark_frame_filename) return 1;
    return 0;
}

int llrpGetDarkFrameIntStatus(mlvObject_t * video)
{
    if(video->DARK.blockType[0]) return 1;
    return 0;
}

int llrpValidateExtDarkFrame(mlvObject_t * video, char * df_filename, char * error_message)
{
    return df_validate(video, df_filename, error_message);
}
