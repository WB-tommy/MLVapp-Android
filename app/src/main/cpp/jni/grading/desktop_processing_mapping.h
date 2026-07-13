#pragma once

#include <algorithm>
#include <cmath>

extern "C" {
#include "../../src/processing/raw_processing.h"
}

// ColorGradingSettings stores the same user-facing values as the Qt sliders.
// Keep every Qt-slider -> processing-core conversion here so live preview and
// export cannot silently drift apart.
namespace desktop_processing {

constexpr double kExposureOffsetStops = 1.2;
constexpr double kDarkStrengthFactor = 22.5;
constexpr double kLightStrengthFactor = 11.2;
constexpr double kLighteningFactor = 0.6;
constexpr double kShadowHighlightFactor = 1.5;

inline double clampSlider(double value, double minimum, double maximum) {
  return std::clamp(value, minimum, maximum);
}

inline double saturationFactor(int value) {
  const double slider = clampSlider(value, -100.0, 100.0);
  const double normalized = (slider + 100.0) / 100.0;
  return std::pow(normalized, std::log(3.6) / std::log(2.0));
}

inline double exposureStops(double displayedStops) {
  return clampSlider(displayedStops, -4.0, 4.0) + kExposureOffsetStops;
}

inline double signedUnitFactor(int value) {
  return clampSlider(value, -100.0, 100.0) / 100.0;
}

inline double unitFactor(int value) {
  return clampSlider(value, 0.0, 100.0) / 100.0;
}

inline double darkStrengthFactor(int value) {
  return unitFactor(value) * kDarkStrengthFactor;
}

inline double lightStrengthFactor(int value) {
  return unitFactor(value) * kLightStrengthFactor;
}

inline double lighteningFactor(int value) {
  return unitFactor(value) * kLighteningFactor;
}

inline double shadowHighlightFactor(int value) {
  return signedUnitFactor(value) * kShadowHighlightFactor;
}

inline void setWhiteBalance(processingObject_t *processing, int kelvin,
                            int tint) {
  processingSetWhiteBalance(
      processing, clampSlider(kelvin, 2000.0, 10000.0),
      clampSlider(tint, -100.0, 100.0) / 10.0);
}

inline void setExposure(processingObject_t *processing,
                        double displayedStops) {
  processingSetExposureStops(
      processing, exposureStops(displayedStops));
}

inline void setContrast(processingObject_t *processing, int value) {
  processingSetSimpleContrast(processing, signedUnitFactor(value));
}

inline void setPivot(processingObject_t *processing, int value) {
  processingSetPivot(processing,
                     clampSlider(value, 0.0, 100.0) / 100.0);
}

inline void setClarity(processingObject_t *processing, int value) {
  processingSetClarity(processing, signedUnitFactor(value));
}

inline void setVibrance(processingObject_t *processing, int value) {
  processingSetVibrance(processing, saturationFactor(value));
}

inline void setSaturation(processingObject_t *processing, int value) {
  processingSetSaturation(processing, saturationFactor(value));
}

inline void setDarkStrength(processingObject_t *processing, int value) {
  processingSetDCFactor(processing, darkStrengthFactor(value));
}

inline void setDarkRange(processingObject_t *processing, int value) {
  processingSetDCRange(processing, unitFactor(value));
}

inline void setLightStrength(processingObject_t *processing, int value) {
  processingSetLCFactor(processing, lightStrengthFactor(value));
}

inline void setLightRange(processingObject_t *processing, int value) {
  processingSetLCRange(processing, unitFactor(value));
}

inline void setLightening(processingObject_t *processing, int value) {
  processingSetLightening(processing, lighteningFactor(value));
}

inline void setShadows(processingObject_t *processing, int value) {
  processingSetShadows(processing, shadowHighlightFactor(value));
}

inline void setHighlights(processingObject_t *processing, int value) {
  processingSetHighlights(processing, shadowHighlightFactor(value));
}

inline void setHighlightReconstruction(processingObject_t *processing,
                                       bool enabled) {
  if (processing->param_mutex != nullptr) {
    pthread_mutex_lock(processing->param_mutex);
  }
  if (enabled) {
    processingEnableHighlightReconstruction(processing);
  } else {
    processingDisableHighlightReconstruction(processing);
  }
  if (processing->param_mutex != nullptr) {
    pthread_mutex_unlock(processing->param_mutex);
  }
}

inline void setCreativeAdjustments(processingObject_t *processing,
                                   bool enabled) {
  if (processing->param_mutex != nullptr) {
    pthread_mutex_lock(processing->param_mutex);
  }
  if (enabled) {
    processingAllowCreativeAdjustments(processing);
  } else {
    processingDontAllowCreativeAdjustments(processing);
  }
  if (processing->param_mutex != nullptr) {
    pthread_mutex_unlock(processing->param_mutex);
  }
}

inline void setCameraMatrix(processingObject_t *processing, int mode) {
  if (processing->param_mutex != nullptr) {
    pthread_mutex_lock(processing->param_mutex);
  }
  switch (mode) {
  case 0:
    processingDontUseCamMatrix(processing);
    break;
  case 2:
    processingUseCamMatrixDanne(processing);
    break;
  case 1:
  default:
    processingUseCamMatrix(processing);
    break;
  }
  if (processing->param_mutex != nullptr) {
    pthread_mutex_unlock(processing->param_mutex);
  }
}

inline void setExr(processingObject_t *processing, bool enabled) {
  if (processing->param_mutex != nullptr) {
    pthread_mutex_lock(processing->param_mutex);
  }
  if (enabled) {
    processingEnableExr(processing);
  } else {
    processingDisableExr(processing);
  }
  if (processing->param_mutex != nullptr) {
    pthread_mutex_unlock(processing->param_mutex);
  }
}

inline void setAgx(processingObject_t *processing, bool enabled) {
  if (processing->param_mutex != nullptr) {
    pthread_mutex_lock(processing->param_mutex);
  }
  if (enabled) {
    processingEnableAgX(processing);
  } else {
    processingDisableAgX(processing);
  }
  if (processing->param_mutex != nullptr) {
    pthread_mutex_unlock(processing->param_mutex);
  }
}

} // namespace desktop_processing
