/* Yeas, we have another background thread */
#include <stdio.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>

#include "video_mlv.h"
#include "../debayer/debayer.h"
#include "../ca_correct/CA_correct_RT.h"
#include "../debayer/wb_conversion.h"

#include "librtprocesswrapper.h"

#define MIN(X, Y) (((X) < (Y)) ? (X) : (Y))
#define MAX(X, Y) (((X) > (Y)) ? (X) : (Y))
#define LIMIT16(X) MAX(MIN(X, 65535), 0)

#ifndef STDOUT_SILENT
#define DEBUG(CODE) CODE
#else
#define DEBUG(CODE)
#endif

void resetMlvCache(mlvObject_t * video)
{
    mark_mlv_uncached(video);
}

void disableMlvCaching(mlvObject_t * video)
{
    /* Stop caching and make sure by waiting */
    video->stop_caching = 1;
    while (isMlvObjectCaching(video)) usleep(100);
    /* Remove the memory (it's a tradition in MLV App libraries to leave a couple of bytes) */
    mark_mlv_uncached(video);
    free(video->cache_memory_block);
    video->cache_memory_block = malloc(2);
}

void enableMlvCaching(mlvObject_t * video)
{
    /* This will reset the memory and start cache thread */
    video->stop_caching = 0;
    setMlvRawCacheLimitMegaBytes(video, video->cache_limit_mb);
}

/* Hmmmm, did anyone need 2 ways of doing this? */

/* What I call MegaBytes is actually MebiBytes! I'm so upset to find that out :( */
void setMlvRawCacheLimitMegaBytes(mlvObject_t * video, uint64_t megaByteLimit)
{
    uint64_t frame_pix   = getMlvWidth(video) * getMlvHeight(video) * 3;
    uint64_t frame_size  = frame_pix * sizeof(uint16_t);
    uint64_t bytes_limit = megaByteLimit * (1 << 20);

    video->cache_limit_mb = megaByteLimit;
    video->cache_limit_bytes = bytes_limit;

    /* Protection against zero division, cuz that causes "Floating point exception: 8"... 
     * ...LOL there's not even a floating point in sight */
    if (isMlvActive(video) && frame_size != 0)
    {
        uint64_t cache_whole = frame_size * getMlvFrames(video);
        uint64_t frame_limit = MIN(bytes_limit, cache_whole) / frame_size;

        video->cache_limit_frames = frame_limit;

        DEBUG( printf("\nEnough memory allowed to cache %i frames (%i MiB)\n\n", (int)frame_limit, (int)megaByteLimit); )

        /* Stop all cache for a bit */
        int has_caching = 0;
        if (!video->stop_caching || isMlvObjectCaching(video))
        {
            has_caching = 1;
            video->stop_caching = 1;
            while (video->cache_thread_count) usleep(100);
        }

        /* Resize cache block - to maximum allowed or enough to fit whole clip if it is smaller */
        video->cache_memory_block = realloc(video->cache_memory_block, MIN(bytes_limit, cache_whole));
        /* Array of frame pointers within the memory block */
        video->rgb_raw_frames = realloc(video->rgb_raw_frames, frame_limit * sizeof(uint16_t *));
        for (uint64_t i = 0; i < getMlvRawCacheLimitFrames(video); ++i) video->rgb_raw_frames[i] = video->cache_memory_block + (frame_pix * i);

        /* Restart caching if it had caching before */
        if (has_caching)
        {
            video->stop_caching = 0;
            /* Begin updating cached frames */
            for (int i = 0; i < video->cpu_cores; ++i)
            {
                add_mlv_cache_thread(video);
            }
        }
    }

    /* No else - if video is not active we won't waste RAM */
}

/* Not recommended */
void setMlvRawCacheLimitFrames(mlvObject_t * video, uint64_t frameLimit)
{
    uint64_t frame_pix   = getMlvWidth(video) * getMlvHeight(video) * 3;
    uint64_t frame_size  = frame_pix * sizeof(uint16_t);

    /* Do only if clip is loaded */
    if (isMlvActive(video) && frame_size != 0)
    {
        uint64_t bytes_limit = frame_size * frameLimit;
        uint64_t mbyte_limit = bytes_limit / (1 << 20);
        uint64_t cache_whole = frame_size * getMlvFrames(video);

        video->cache_limit_bytes = bytes_limit;
        video->cache_limit_mb = mbyte_limit;
        video->cache_limit_frames = frameLimit;

        /* Stop all cache for a bit */
        int has_caching = 0;
        if (!video->stop_caching || isMlvObjectCaching(video))
        {
            has_caching = 1;
            video->stop_caching = 1;
            while (video->cache_thread_count) usleep(100);
        }

        /* Resize cache block - to maximum allowed or enough to fit whole clip if it is smaller */
        video->cache_memory_block = realloc(video->cache_memory_block, MIN(bytes_limit, cache_whole));
        /* Array of frame pointers within the memory block */
        video->rgb_raw_frames = realloc(video->rgb_raw_frames, frameLimit * sizeof(uint16_t *));
        for (uint64_t i = 0; i < getMlvRawCacheLimitFrames(video); ++i) video->rgb_raw_frames[i] = video->cache_memory_block + (frame_pix * i);

        /* Restart caching if it had caching before */
        if (has_caching)
        {
            video->stop_caching = 0;
            /* Begin updating cached frames */
            for (int i = 0; i < video->cpu_cores; ++i)
            {
                add_mlv_cache_thread(video);
            }
        }
    }
}

/* Invalidates every completed cache entry and advances the generation fence.
 * A BEING_CACHED slot remains reserved until its owning worker observes the
 * generation change and retires it, preventing old/new workers from writing
 * the same RGB slot concurrently. */
void mark_mlv_uncached(mlvObject_t * video)
{
    pthread_mutex_lock( &video->g_mutexFind );
    video->cache_generation++;
    video->cache_next = 0;
    resetMlvCachedFrame(video);
    if (video->raw_frame_results != NULL)
    {
        memset(video->raw_frame_results, 0,
               getMlvFrames(video) * sizeof(*video->raw_frame_results));
    }
    if (video->cached_frames != NULL)
    {
        for (uint64_t i = 0; i < getMlvFrames(video); ++i)
        {
            if (video->cached_frames[i] != MLV_FRAME_BEING_CACHED)
            {
                video->cached_frames[i] = MLV_FRAME_NOT_CACHED;
            }
        }
    }
    /* Wake any playback thread waiting on cache_cond for a BEING_CACHED frame */
    pthread_cond_broadcast( &video->cache_cond );
    pthread_mutex_unlock( &video->g_mutexFind );
}

/* Clears cache by freeing then reallocating (RAM usage down until frames written) */
void clear_mlv_cache(mlvObject_t * video)
{
    mark_mlv_uncached(video);
    free(video->cache_memory_block);
    video->cache_memory_block = malloc(video->cache_limit_bytes);
}

/* Returns 1 on success, or 0 if all are cached */
int find_mlv_frame_to_cache(mlvObject_t * video, uint64_t * index,
                            uint64_t * generation)
{
    pthread_mutex_lock( &video->g_mutexFind );
    /* If a specific frame was requested */
    if (video->cache_next)
    {
        uint64_t requested = video->cache_next;
        video->cache_next = 0;
        if (requested < getMlvFrames(video) &&
            requested < getMlvRawCacheLimitFrames(video) &&
            video->cached_frames[requested] == MLV_FRAME_NOT_CACHED)
        {
            *index = requested;
            *generation = video->cache_generation;
            video->cached_frames[requested] = MLV_FRAME_BEING_CACHED;
            pthread_mutex_unlock( &video->g_mutexFind );
            return 1;
        }
    }

    uint64_t frame_limit = MIN(getMlvRawCacheLimitFrames(video),
                               getMlvFrames(video));
    for (uint64_t frame = 0; frame < frame_limit; ++frame)
    {
        /* Claim under the finder lock so two workers cannot choose the same
         * slot before either marks it as in flight. */
        if (video->cached_frames[frame] == MLV_FRAME_NOT_CACHED)
        {
            *index = frame;
            *generation = video->cache_generation;
            video->cached_frames[frame] = MLV_FRAME_BEING_CACHED;
            pthread_mutex_unlock( &video->g_mutexFind );
            return 1;
        }
    }
    pthread_mutex_unlock( &video->g_mutexFind );
    return 0;
}

/* Adds one thread, active total can be checked in mlvObject->cache_thread_count */
void add_mlv_cache_thread(mlvObject_t * video)
{
    pthread_t thread;
    pthread_create(&thread, NULL, (void *)an_mlv_cache_thread, (void *)video);
}

/* Add as many of these as you want :) */
void an_mlv_cache_thread(mlvObject_t * video)
{
    if (!isMlvActive(video)) return;

    pthread_mutex_lock( &video->g_mutexCount );
    video->cache_thread_count++;
    pthread_mutex_unlock( &video->g_mutexCount );

    uint32_t height = getMlvHeight(video);
    uint32_t width = getMlvWidth(video);
    uint32_t pixelsize = width * height;

    /* 2d array uglyness */
    float  * __restrict imagefloat1d = (float *)malloc(pixelsize * sizeof(float));
    float ** __restrict imagefloat2d = (float **)malloc(height * sizeof(float *));
    for (volatile uint32_t y = 0; y < height; ++y) imagefloat2d[y] = (float *)(imagefloat1d+(y*width));
    float  * __restrict red1d = (float *)malloc(pixelsize * sizeof(float));
    float ** __restrict red2d = (float **)malloc(height * sizeof(float *));
    for (volatile uint32_t y = 0; y < height; ++y) red2d[y] = (float *)(red1d+(y*width));
    float  * __restrict green1d = (float *)malloc(pixelsize * sizeof(float));
    float ** __restrict green2d = (float **)malloc(height * sizeof(float *));
    for (volatile uint32_t y = 0; y < height; ++y) green2d[y] = (float *)(green1d+(y*width));
    float  * __restrict blue1d = (float *)malloc(pixelsize * sizeof(float));
    float ** __restrict blue2d = (float **)malloc(height * sizeof(float *));
    for (volatile uint32_t y = 0; y < height; ++y) blue2d[y] = (float *)(blue1d+(y*width));

    pthread_mutex_lock( &video->g_mutexCount );
    amazeinfo_t amaze_params = {
        .rawData =  imagefloat2d,
        .red     =  red2d,
        .green   =  green2d,
        .blue    =  blue2d,
        .winx    =  0,
        .winy    =  0,
        .winw    =  getMlvWidth(video),
        .winh    =  getMlvHeight(video),
        .cfa     =  0
    };
    pthread_mutex_unlock( &video->g_mutexCount );

    while (1 < 2)
    {
        if (video->stop_caching) break;

        uint64_t cache_frame;
        uint64_t cache_generation;

        /* If cache finder reurns false, it's time t stop caching */
        if (!find_mlv_frame_to_cache(video, &cache_frame,
                                     &cache_generation)) break;

        pthread_mutex_lock( &video->cache_mutex ); //cache mutex used first time ;)
        llrpFrameResult_t raw_result = get_mlv_raw_frame_float_result(
            video, cache_frame, imagefloat1d);
        pthread_mutex_unlock( &video->cache_mutex );

        /* Single thread AMaZE */
        demosaic(&amaze_params);

        /* To 16-bit */
        uint16_t * out = video->rgb_raw_frames[cache_frame];
        for (uint32_t i = 0; i < pixelsize-10; i++)
        {
            uint16_t * pix = out + (i*3);
            pix[0] = (uint16_t)MIN(red1d[i], 65535);
            pix[1] = (uint16_t)MIN(green1d[i], 65535);
            pix[2] = (uint16_t)MIN(blue1d[i], 65535);
        }

        pthread_mutex_lock( &video->g_mutexFind );
        int cache_published = 0;
        int generation_current =
            video->cache_generation == cache_generation;
        if (video->cache_generation == cache_generation &&
            raw_result.output_bit_depth > 0 &&
            video->cached_frames[cache_frame] == MLV_FRAME_BEING_CACHED)
        {
            if (video->raw_frame_results != NULL)
            {
                video->raw_frame_results[cache_frame] = raw_result;
            }
            video->cached_frames[cache_frame] = MLV_FRAME_IS_CACHED;
            cache_published = 1;
        }
        else if (video->cached_frames[cache_frame] ==
                 MLV_FRAME_BEING_CACHED)
        {
            /* Invalidation deliberately kept this slot reserved. Release it
             * without publishing the stale RGB/result pair. */
            video->cached_frames[cache_frame] = MLV_FRAME_NOT_CACHED;
        }
        pthread_cond_broadcast( &video->cache_cond );
        pthread_mutex_unlock( &video->g_mutexFind );

        DEBUG( if (cache_published) printf("Debayered frame %" PRIu64 "/%" PRIu64 " has been cached.\n", cache_frame+1, video->cache_limit_frames); )
        (void)cache_published;

        /* A persistent decode failure must not become a valid zero frame or
         * spin this worker forever retrying the same slot. */
        if (generation_current && raw_result.output_bit_depth <= 0) break;
    }

    free(red1d);
    free(red2d);
    free(green1d);
    free(green2d);
    free(blue1d);
    free(blue2d);
    free(imagefloat2d);
    free(imagefloat1d);

    pthread_mutex_lock( &video->g_mutexCount );
    video->cache_thread_count--;
    pthread_mutex_unlock( &video->g_mutexCount );
}

/* Gets a freshly debayered frame every time ( temp memory should be Width * Height * sizeof(float) ) */
void get_mlv_raw_frame_debayered( mlvObject_t * video, 
                                  uint64_t frame_index,
                                  float * temp_memory, 
                                  uint16_t * output_frame, 
                                  int debayer_type,
                                  llrpFrameResult_t *frame_result ) /* 0=bilinear 1=amaze ... */
{
    int width = getMlvWidth(video);
    int height = getMlvHeight(video);

    /* Get the raw data in B&W */
    llrpFrameResult_t raw_result = get_mlv_raw_frame_float_result(
        video, frame_index, temp_memory);
    if (frame_result != NULL)
    {
        *frame_result = raw_result;
    }

    wb_convert_info_t wb_info;

    /* WB conversion for ideal debayer result, not for bilinear, easy and non debayer */
    if( !( debayer_type == 0 || debayer_type == 2 || debayer_type == 3 ) )
    {
        wb_convert(&wb_info, temp_memory, width, height, getMlvBlackLevel(video));

        /* CA correction, multithreaded, not for bilinear, easy and non debayer because not visible and slow */
        if( video->ca_red <= -0.1 || video->ca_red >= 0.1
         || video->ca_blue <= -0.1 || video->ca_blue >= 0.1 )
        {
            /* 2d array for CA correction */
            float ** __restrict imagefloat2d = (float **)malloc(height * sizeof(float *));
            for (int y = 0; y < height; ++y) imagefloat2d[y] = (float *)(temp_memory+(y*width));

            /* the magic CA correction function */
            /*CA_correct_RT(imagefloat2d, 0, 0, width, height,
                          0, 0, width, height,
                          0, video->ca_red, video->ca_blue);*/ /*auto, red, blue*/

            lrtpCaCorrect( imagefloat2d, 0, 0, width, height,
                           0, 0, video->ca_red, video->ca_blue, 0 );
        }
    }

    /* Debayer */
    if (/*debayer_type == 1 ||*/ debayer_type == 4 || debayer_type == 5 || /*debayer_type == 6 ||*/ debayer_type == 7 || debayer_type == 8)
    {
        //AMaZE and AHD disabled from librtprocess because of bad artifacts
        debayerLibRtProcess(output_frame, temp_memory, width, height, debayer_type, video->processing->cam_matrix);
    }
    else if (debayer_type == 1 )
    {
        debayerAmaze(output_frame, temp_memory, width, height, getMlvCpuCores(video), getMlvBlackLevel(video));
    }
    else if(debayer_type == 2 || debayer_type == 3)
    {
        /* threaded easy types */
        debayerEasy(output_frame, temp_memory, width, height, getMlvCpuCores(video), debayer_type);
    }
    else if (debayer_type == 6 )
    {
        debayerAhd(output_frame, temp_memory, width, height);
    }
    else
    {
        /* Debayer quickly (bilinearly) */
        debayerBasic(output_frame, temp_memory, width, height, 1);
    }

    /* WB conversion undo for ideal debayer result */
    if( !( debayer_type == 0 || debayer_type == 2 || debayer_type == 3 ) )
        wb_undo(&wb_info, output_frame, width, height, getMlvBlackLevel(video));
}
