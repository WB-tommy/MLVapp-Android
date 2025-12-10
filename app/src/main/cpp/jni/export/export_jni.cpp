extern "C" {
#include "../../src/mlv/mlv_object.h"
#include "../../src/mlv/video_mlv.h"
#include "../../src/processing/raw_processing.h"
}
#include "../ffmpeg/ffmpeg_handler.h"
#include "export_handler.h"
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <jni.h>
#include <string>

static JavaVM *g_vm = nullptr;
static jobject g_progress_listener = nullptr;
static jmethodID g_on_progress_mid = nullptr;
static jobject g_file_provider = nullptr;
static jmethodID g_open_frame_fd_mid = nullptr;
static jmethodID g_open_container_fd_mid = nullptr;
static jmethodID g_open_audio_fd_mid = nullptr;
static std::atomic<bool> g_cancel_requested{false};

static std::string jstring_to_string(JNIEnv *env, jstring value) {
  if (!value)
    return {};
  const char *chars = env->GetStringUTFChars(value, nullptr);
  std::string out(chars ? chars : "");
  env->ReleaseStringUTFChars(value, chars);
  return out;
}

static int get_enum_ordinal(JNIEnv *env, jobject enum_obj) {
  if (!enum_obj)
    return 0;
  jclass enumClass = env->GetObjectClass(enum_obj);
  jmethodID ordinalMethod = env->GetMethodID(enumClass, "ordinal", "()I");
  jint ordinal = env->CallIntMethod(enum_obj, ordinalMethod);
  env->DeleteLocalRef(enumClass);
  return ordinal;
}

// Helper to get enum ordinal from a field
static int get_enum_field(JNIEnv *env, jobject obj, jclass clazz,
                          const char *name, const char *sig) {
  jfieldID fid = env->GetFieldID(clazz, name, sig);
  jobject val = env->GetObjectField(obj, fid);
  int ordinal = get_enum_ordinal(env, val);
  env->DeleteLocalRef(val);
  return ordinal;
}

// Helper to get string from a field
static std::string get_string_field(JNIEnv *env, jobject obj, jclass clazz,
                                    const char *name) {
  jfieldID fid = env->GetFieldID(clazz, name, "Ljava/lang/String;");
  jstring val = (jstring)env->GetObjectField(obj, fid);
  std::string result = jstring_to_string(env, val);
  env->DeleteLocalRef(val);
  return result;
}

// Helper to get int from a field
static int get_int_field(JNIEnv *env, jobject obj, jclass clazz,
                         const char *name) {
  jfieldID fid = env->GetFieldID(clazz, name, "I");
  return env->GetIntField(obj, fid);
}

// Helper to get float from a field
static float get_float_field(JNIEnv *env, jobject obj, jclass clazz,
                             const char *name) {
  jfieldID fid = env->GetFieldID(clazz, name, "F");
  return env->GetFloatField(obj, fid);
}

// Helper to get boolean from a field
static bool get_bool_field(JNIEnv *env, jobject obj, jclass clazz,
                           const char *name) {
  jfieldID fid = env->GetFieldID(clazz, name, "Z");
  return env->GetBooleanField(obj, fid);
}

export_options_t parse_export_options(JNIEnv *env, jobject exportOptions) {
  export_options_t options{};
  jclass cls = env->GetObjectClass(exportOptions);

  options.codec = get_enum_field(env, exportOptions, cls, "codec",
                                 "Lfm/magiclantern/forum/export/ExportCodec;");
  options.codec_option = get_int_field(env, exportOptions, cls, "codecOption");
  options.naming_scheme =
      get_enum_field(env, exportOptions, cls, "cdngNaming",
                     "Lfm/magiclantern/forum/export/CdngNaming;");
  options.cdng_variant =
      get_enum_field(env, exportOptions, cls, "cdngVariant",
                     "Lfm/magiclantern/forum/export/CdngVariant;");

  // ProRes
  options.prores_profile =
      get_enum_field(env, exportOptions, cls, "proResProfile",
                     "Lfm/magiclantern/forum/export/ProResProfile;");
  options.prores_encoder =
      get_enum_field(env, exportOptions, cls, "proResEncoder",
                     "Lfm/magiclantern/forum/export/ProResEncoder;");

  options.debayer_quality =
      get_enum_field(env, exportOptions, cls, "debayerQuality",
                     "Lfm/magiclantern/forum/export/DebayerQuality;");
  options.smoothing =
      get_enum_field(env, exportOptions, cls, "smoothing",
                     "Lfm/magiclantern/forum/export/SmoothingOption;");

  options.include_audio =
      get_bool_field(env, exportOptions, cls, "includeAudio");
  options.enable_raw_fixes =
      get_bool_field(env, exportOptions, cls, "enableRawFixes");
  options.frame_rate_override =
      get_bool_field(env, exportOptions, cls, "frameRateOverrideEnabled");
  options.frame_rate_value =
      get_float_field(env, exportOptions, cls, "frameRateValue");
  options.hdr_blending = get_bool_field(env, exportOptions, cls, "hdrBlending");
  options.anti_aliasing =
      get_bool_field(env, exportOptions, cls, "antiAliasing");

  options.source_file_name =
      get_string_field(env, exportOptions, cls, "sourceFileName");
  options.source_base_name = options.source_file_name;
  const size_t dotPos = options.source_base_name.find_last_of('.');
  if (dotPos != std::string::npos) {
    options.source_base_name.erase(dotPos);
  }

  options.clip_uri_path =
      get_string_field(env, exportOptions, cls, "clipUriPath");
  options.audio_temp_dir =
      get_string_field(env, exportOptions, cls, "audioTempDir");
  options.stretch_factor_x =
      get_float_field(env, exportOptions, cls, "stretchFactorX");
  options.stretch_factor_y =
      get_float_field(env, exportOptions, cls, "stretchFactorY");

  options.stretch_factor_y =
      get_float_field(env, exportOptions, cls, "stretchFactorY");

  // Benchmark flags
  options.force_hardware =
      get_bool_field(env, exportOptions, cls, "forceHardware");
  options.force_software =
      get_bool_field(env, exportOptions, cls, "forceSoftware");

  // Codec usage
  options.h264_quality =
      get_enum_field(env, exportOptions, cls, "h264Quality",
                     "Lfm/magiclantern/forum/export/H264Quality;");
  options.h264_container =
      get_enum_field(env, exportOptions, cls, "h264Container",
                     "Lfm/magiclantern/forum/export/H264Container;");

  options.h265_bitdepth =
      get_enum_field(env, exportOptions, cls, "h265BitDepth",
                     "Lfm/magiclantern/forum/export/H265BitDepth;");
  options.h265_quality =
      get_enum_field(env, exportOptions, cls, "h265Quality",
                     "Lfm/magiclantern/forum/export/H265Quality;");
  options.h265_container =
      get_enum_field(env, exportOptions, cls, "h265Container",
                     "Lfm/magiclantern/forum/export/H265Container;");

  options.png_bitdepth =
      get_enum_field(env, exportOptions, cls, "pngBitDepth",
                     "Lfm/magiclantern/forum/export/PngBitDepth;");
  options.dnxhr_profile =
      get_enum_field(env, exportOptions, cls, "dnxhrProfile",
                     "Lfm/magiclantern/forum/export/DnxhrProfile;");
  options.dnxhd_profile =
      get_enum_field(env, exportOptions, cls, "dnxhdProfile",
                     "Lfm/magiclantern/forum/export/DnxhdProfile;");
  options.vp9_quality =
      get_enum_field(env, exportOptions, cls, "vp9Quality",
                     "Lfm/magiclantern/forum/export/Vp9Quality;");

  // Resize settings
  jfieldID resizeField = env->GetFieldID(
      cls, "resize", "Lfm/magiclantern/forum/export/ResizeSettings;");
  jobject resizeObj = env->GetObjectField(exportOptions, resizeField);
  if (resizeObj) {
    jclass resizeClass = env->GetObjectClass(resizeObj);
    options.resize_enabled =
        get_bool_field(env, resizeObj, resizeClass, "enabled");
    options.resize_width = get_int_field(env, resizeObj, resizeClass, "width");
    options.resize_height =
        get_int_field(env, resizeObj, resizeClass, "height");
    options.resize_lock_aspect =
        get_bool_field(env, resizeObj, resizeClass, "lockAspectRatio");
    options.resize_algorithm =
        get_enum_field(env, resizeObj, resizeClass, "algorithm",
                       "Lfm/magiclantern/forum/export/ScalingAlgorithm;");
    env->DeleteLocalRef(resizeClass);
  }
  env->DeleteLocalRef(resizeObj);

  env->DeleteLocalRef(cls);
  return options;
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_cancelExport(
    JNIEnv *, jobject /*thiz*/) {
  g_cancel_requested.store(true, std::memory_order_relaxed);
}

bool is_export_cancelled() {
  return g_cancel_requested.load(std::memory_order_relaxed);
}

void reset_export_cancel_flag() {
  g_cancel_requested.store(false, std::memory_order_relaxed);
}

static void progress_callback(int progress) {
  if (g_cancel_requested.load(std::memory_order_relaxed)) {
    return;
  }
  if (!g_progress_listener || !g_on_progress_mid || !g_vm) {
    return;
  }
  JNIEnv *env = nullptr;
  if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
    return;
  }
  env->CallVoidMethod(g_progress_listener, g_on_progress_mid, progress);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

static int acquire_frame_fd(void *, uint32_t frame_index,
                            const char *relative_name) {
  if (!g_file_provider || !g_open_frame_fd_mid || !g_vm) {
    return -1;
  }
  JNIEnv *env = nullptr;
  if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
    return -1;
  }
  jstring jName = env->NewStringUTF(relative_name);
  jint fd = env->CallIntMethod(g_file_provider, g_open_frame_fd_mid,
                               static_cast<jint>(frame_index), jName);
  env->DeleteLocalRef(jName);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return -1;
  }
  return fd;
}

static int acquire_container_fd(void *, const char *relative_name) {
  if (!g_file_provider || !g_open_container_fd_mid || !g_vm) {
    return -1;
  }
  JNIEnv *env = nullptr;
  if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
    return -1;
  }
  jstring jName = env->NewStringUTF(relative_name);
  jint fd = env->CallIntMethod(g_file_provider, g_open_container_fd_mid, jName);
  env->DeleteLocalRef(jName);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return -1;
  }
  return fd;
}

static int acquire_audio_fd(void *, const char *relative_name) {
  if (!g_file_provider || !g_open_audio_fd_mid || !g_vm) {
    return -1;
  }
  JNIEnv *env = nullptr;
  if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
    return -1;
  }
  jstring jName = env->NewStringUTF(relative_name);
  jint fd = env->CallIntMethod(g_file_provider, g_open_audio_fd_mid, jName);
  env->DeleteLocalRef(jName);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return -1;
  }
  return fd;
}

extern "C" JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_nativeInterface_NativeLib_exportHandler(
    JNIEnv *env, jobject /* thiz */, jlong cacheSize, jint cores,
    jintArray clipFds, jobject exportOptions, jobject progressListener,
    jobject fileProvider) {
  env->GetJavaVM(&g_vm);

  g_progress_listener = env->NewGlobalRef(progressListener);
  jclass progressClazz = env->GetObjectClass(g_progress_listener);
  g_on_progress_mid = env->GetMethodID(progressClazz, "onProgress", "(I)V");
  env->DeleteLocalRef(progressClazz);

  if (fileProvider) {
    g_file_provider = env->NewGlobalRef(fileProvider);
    jclass providerClazz = env->GetObjectClass(g_file_provider);
    g_open_frame_fd_mid = env->GetMethodID(providerClazz, "openFrameFd",
                                           "(ILjava/lang/String;)I");
    g_open_container_fd_mid = env->GetMethodID(providerClazz, "openContainerFd",
                                               "(Ljava/lang/String;)I");
    g_open_audio_fd_mid =
        env->GetMethodID(providerClazz, "openAudioFd", "(Ljava/lang/String;)I");
    env->DeleteLocalRef(providerClazz);
  } else {
    g_file_provider = nullptr;
    g_open_frame_fd_mid = nullptr;
    g_open_container_fd_mid = nullptr;
    g_open_audio_fd_mid = nullptr;
  }

  export_options_t options = parse_export_options(env, exportOptions);
  reset_export_cancel_flag();

  jstring jFileName = env->NewStringUTF(options.source_file_name.c_str());
  mlvObject_t *video =
      getMlvObject(env, clipFds, jFileName, cacheSize, cores, true);
  env->DeleteLocalRef(jFileName);
  if (!video) {
    env->DeleteGlobalRef(g_progress_listener);
    g_progress_listener = nullptr;
    if (g_file_provider) {
      env->DeleteGlobalRef(g_file_provider);
      g_file_provider = nullptr;
    }
    return;
  }

  setMlvProcessing(video, video->processing);
  disableMlvCaching(video);
  const int focusMode = llrpDetectFocusDotFixMode(video);
  if (focusMode != 0) {
    llrpSetFixRawMode(video, 1);
    llrpSetFocusPixelMode(video, focusMode);
    llrpResetFpmStatus(video);
    llrpResetBpmStatus(video);
    resetMlvCache(video);
    resetMlvCachedFrame(video);
  }

  export_fd_provider_t provider = {};
  if (g_file_provider) {
    provider.acquire_frame_fd =
        g_open_frame_fd_mid ? acquire_frame_fd : nullptr;
    provider.acquire_container_fd =
        g_open_container_fd_mid ? acquire_container_fd : nullptr;
    provider.acquire_audio_fd =
        g_open_audio_fd_mid ? acquire_audio_fd : nullptr;
    provider.ctx = nullptr;
  }

  int result = startExportJob(video, options, provider, progress_callback);

  freeProcessingObject(video->processing);
  freeMlvObject(video);

  env->DeleteGlobalRef(g_progress_listener);
  g_progress_listener = nullptr;
  if (g_file_provider) {
    env->DeleteGlobalRef(g_file_provider);
    g_file_provider = nullptr;
  }

  if (result == EXPORT_CANCELLED) {
    jclass cancellationCls =
        env->FindClass("kotlin/coroutines/CancellationException");
    if (!cancellationCls) {
      env->ExceptionClear();
      cancellationCls =
          env->FindClass("java/util/concurrent/CancellationException");
    }
    if (!cancellationCls) {
      env->ExceptionClear();
      cancellationCls = env->FindClass("java/lang/RuntimeException");
    }
    if (cancellationCls) {
      env->ThrowNew(cancellationCls, "Export cancelled");
    } else {
      env->ExceptionClear();
    }
    return;
  }

  if (result != EXPORT_SUCCESS) {
    jclass exceptionCls = env->FindClass("java/lang/RuntimeException");
    if (exceptionCls) {
      const char *errorMessage;
      switch (result) {
      case EXPORT_ERROR_CODEC_UNAVAILABLE:
        errorMessage =
            "Export failed: No suitable video encoder available. "
            "Both hardware and software encoders failed to initialize.";
        break;
      case EXPORT_ERROR_INSUFFICIENT_MEMORY:
        errorMessage =
            "Export failed: Insufficient memory to complete the operation.";
        break;
      case EXPORT_ERROR_IO:
        errorMessage =
            "Export failed: I/O error occurred while writing output file.";
        break;
      case EXPORT_ERROR_INVALID_PARAMETERS:
        errorMessage =
            "Export failed: Invalid export parameters or configuration.";
        break;
      case EXPORT_ERROR_ENCODER_INIT_FAILED:
        errorMessage = "Export failed: Unable to initialize video encoder.";
        break;
      case EXPORT_ERROR_FRAME_PROCESSING_FAILED:
        errorMessage = "Export failed: Error processing video frames.";
        break;
      default:
        errorMessage =
            "Export failed: An unknown error occurred during export.";
        break;
      }
      env->ThrowNew(exceptionCls, errorMessage);
    }
  }
}
