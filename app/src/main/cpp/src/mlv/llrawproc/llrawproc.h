/*
 * Copyright (C) 2017 bouncyball
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

#ifndef _llrawproc_h
#define _llrawproc_h

#include "llrawproc_object.h"
#include "../mlv_object.h"

llrawprocObject_t * initLLRawProcObject();
void freeLLRawProcObject(mlvObject_t * video);

/* all low level raw processing takes place here */
llrpFrameResult_t applyLLRawProcObject(mlvObject_t * video,
                                       uint16_t * raw_image_buff,
                                       size_t raw_image_size);
/* Same corrections for an input Bayer plane already phase-normalized to RGGB. */
llrpFrameResult_t applyLLRawProcObjectPreparedRggb(mlvObject_t * video,
                                                   uint16_t * raw_image_buff,
                                                   size_t raw_image_size);

/* Detect focus dot fix mode according to RAWC block info (binning + skipping) and camera ID
   Return value 0 = off, 1 = On, 2 = CropRec */
int llrpDetectFocusDotFixMode(mlvObject_t * video);

/* LLRawProcObject all member variable handling functions */
enum { FR_OFF, FR_ON };
int llrpGetFixRawMode(mlvObject_t * video);
void llrpSetFixRawMode(mlvObject_t * video, int value);

enum { VS_OFF, VS_ON, VS_FORCE };
int llrpGetVerticalStripeMode(mlvObject_t * video);
void llrpSetVerticalStripeMode(mlvObject_t * video, int value);
void llrpComputeStripesOn(mlvObject_t * video);

enum { FP_OFF, FP_ON, FP_CROPREC };
int llrpGetFocusPixelMode(mlvObject_t * video);
void llrpSetFocusPixelMode(mlvObject_t * video, int value);

enum { FPI_MLVFS, FPI_RAW2DNG };
int llrpGetFocusPixelInterpolationMethod(mlvObject_t * video);
void llrpSetFocusPixelInterpolationMethod(mlvObject_t * video, int value);

enum { BP_OFF, BP_ON, FP_AGGRESSIVE };
int llrpGetBadPixelMode(mlvObject_t * video);
void llrpSetBadPixelMode(mlvObject_t * video, int value);

enum { BPS_NORMAL, BPS_FORCE };
int llrpGetBadPixelSearchMethod(mlvObject_t * video);
void llrpSetBadPixelSearchMethod(mlvObject_t * video, int value);

enum { BPI_MLVFS, BPI_RAW2DNG };
int llrpGetBadPixelInterpolationMethod(mlvObject_t * video);
void llrpSetBadPixelInterpolationMethod(mlvObject_t * video, int value);

enum { CS_OFF, CS_2x2, CS_3x3, CS_5x5 };
int llrpGetChromaSmoothMode(mlvObject_t * video);
void llrpSetChromaSmoothMode(mlvObject_t * video, int value);

enum { PN_OFF, PN_ON };
int llrpGetPatternNoiseMode(mlvObject_t * video);
void llrpSetPatternNoiseMode(mlvObject_t * video, int value);

int llrpGetDeflickerTarget(mlvObject_t * video);
void llrpSetDeflickerTarget(mlvObject_t * video, int value);

/* dual iso stuff */
enum { DISO_OFF, DISO_20BIT, DISO_FAST };
enum { DISO_PATTERN_AUTO, DISO_PATTERN_1, DISO_PATTERN_2,
       DISO_PATTERN_3, DISO_PATTERN_4, DISO_PATTERN_AUTO_EVERY_FRAME };
enum { DISO_MATCH_ISO = 1, DISO_MATCH_HISTOGRAM = 2 };

/* Atomic UI/receipt/export contract.  Setters accept EV 1.0 and black -1 as
 * automatic sentinels.  The getter returns the normalized values resolved by
 * the latest successful frame, plus the actual latest-frame status. */
typedef struct
{
    int mode;                  /* DISO_OFF or DISO_20BIT; legacy FAST -> 20BIT */
    int force;                 /* force legacy clips without a DISO block */
    int pattern;               /* 0 auto, 1..4 fixed, 5 auto every frame */
    int match_method;          /* DISO_MATCH_ISO or DISO_MATCH_HISTOGRAM */
    double ev_correction;      /* 1.0 auto, otherwise -6.0 .. 0.0 EV */
    int black_delta;           /* -1 auto, otherwise 0 .. 100 */
    int interpolation;         /* DISOI_AMAZE or DISOI_MEAN23 */
    int alias_map;
    int fullres_blending;
    int validity;              /* output-only snapshot: DISO_* validity */
    int last_frame_applied;     /* output-only snapshot */
    int last_output_bit_depth;  /* output-only snapshot */
} llrpDualIsoConfig_t;

int llrpSetDualIsoConfig(mlvObject_t * video,
                         const llrpDualIsoConfig_t * config);
int llrpGetDualIsoConfig(mlvObject_t * video,
                         llrpDualIsoConfig_t * config);
void llrpPublishDualIsoFrameResult(mlvObject_t * video,
                                   const llrpFrameResult_t * result);
void llrpSetProcessingDualIsoFrameResult(mlvObject_t * video,
                                         const llrpFrameResult_t * result);

int llrpGetDualIsoMode(mlvObject_t * video);
void llrpSetDualIsoMode(mlvObject_t * video, int value);

enum { DISOI_AMAZE, DISOI_MEAN23 };
int llrpGetDualIsoInterpolationMethod(mlvObject_t * video);
void llrpSetDualIsoInterpolationMethod(mlvObject_t * video, int value);

int llrpGetDualIsoAliasMapMode(mlvObject_t * video);
void llrpSetDualIsoAliasMapMode(mlvObject_t * video, int value);

int llrpGetDualIsoFullResBlendingMode(mlvObject_t * video);
void llrpSetDualIsoFullResBlendingMode(mlvObject_t * video, int value);

enum { DISO_INVALID, DISO_FORCED, DISO_VALID }; // Return values
int llrpGetDualIsoValidity(mlvObject_t * video);
void llrpSetDualIsoValidity(mlvObject_t * video, int diso_force);

int llrpHQDualIso(mlvObject_t * video);

void llrpResetDngBWLevels(mlvObject_t * video);

/* reset focus/bad pixel map status */
void llrpResetFpmStatus(mlvObject_t * video);
void llrpResetBpmStatus(mlvObject_t * video);

/* dark frame stuff */
void llrpInitDarkFrameExtFileName(mlvObject_t * video, char * df_filename);
void llrpFreeDarkFrameExtFileName(mlvObject_t * video);

int llrpGetDarkFrameMode(mlvObject_t * video);
void llrpSetDarkFrameMode(mlvObject_t * video, int value);

int llrpGetDarkFrameExtStatus(mlvObject_t * video);
int llrpGetDarkFrameIntStatus(mlvObject_t * video);

int llrpValidateExtDarkFrame(mlvObject_t * video, char * df_filename, char * error_message);

#endif
