#include <jni.h>
#include <android/log.h>
#include <string>
#include <cstring>
#include "export_handler.h"
#include "../../src/mlv/video_mlv.h"
#include <atomic>

static JavaVM *g_vm = nullptr;
static jobject g_progress_listener = nullptr;
static jmethodID g_on_progress_mid = nullptr;
static jobject g_file_provider = nullptr;
static jmethodID g_open_frame_fd_mid = nullptr;
static jmethodID g_open_container_fd_mid = nullptr;
static jmethodID g_open_audio_fd_mid = nullptr;
static std::atomic<bool> g_cancel_requested{false};

static std::string jstring_to_string(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars ? chars : "");
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

static int get_enum_ordinal(JNIEnv *env, jobject enum_obj) {
    if (!enum_obj) return 0;
    jclass enumClass = env->GetObjectClass(enum_obj);
    jmethodID ordinalMethod = env->GetMethodID(enumClass, "ordinal", "()I");
    jint ordinal = env->CallIntMethod(enum_obj, ordinalMethod);
    env->DeleteLocalRef(enumClass);
    return ordinal;
}

static export_options_t parse_export_options(JNIEnv *env, jobject exportOptions) {
    export_options_t options{};
    jclass optionsClass = env->GetObjectClass(exportOptions);

    jfieldID codecField = env->GetFieldID(optionsClass, "codec", "Lfm/forum/mlvapp/export/ExportCodec;");
    jobject codecObj = env->GetObjectField(exportOptions, codecField);
    options.codec = get_enum_ordinal(env, codecObj);
    env->DeleteLocalRef(codecObj);

    jfieldID codecOptionField = env->GetFieldID(optionsClass, "codecOption", "I");
    options.codec_option = env->GetIntField(exportOptions, codecOptionField);

    jfieldID namingField = env->GetFieldID(optionsClass, "cdngNaming", "Lfm/forum/mlvapp/export/CdngNaming;");
    jobject namingObj = env->GetObjectField(exportOptions, namingField);
    options.naming_scheme = get_enum_ordinal(env, namingObj);
    env->DeleteLocalRef(namingObj);

    jfieldID cdngVariantField = env->GetFieldID(optionsClass, "cdngVariant", "Lfm/forum/mlvapp/export/CdngVariant;");
    jobject cdngVariantObj = env->GetObjectField(exportOptions, cdngVariantField);
    options.cdng_variant = get_enum_ordinal(env, cdngVariantObj);
    env->DeleteLocalRef(cdngVariantObj);

    jfieldID includeAudioField = env->GetFieldID(optionsClass, "includeAudio", "Z");
    options.include_audio = env->GetBooleanField(exportOptions, includeAudioField);

    jfieldID enableRawFixesField = env->GetFieldID(optionsClass, "enableRawFixes", "Z");
    options.enable_raw_fixes = env->GetBooleanField(exportOptions, enableRawFixesField);

    jfieldID frameOverrideField = env->GetFieldID(optionsClass, "frameRateOverrideEnabled", "Z");
    options.frame_rate_override = env->GetBooleanField(exportOptions, frameOverrideField);

    jfieldID frameValueField = env->GetFieldID(optionsClass, "frameRateValue", "F");
    options.frame_rate_value = env->GetFloatField(exportOptions, frameValueField);

    jfieldID sourceFileField = env->GetFieldID(optionsClass, "sourceFileName", "Ljava/lang/String;");
    jstring sourceFileString = (jstring) env->GetObjectField(exportOptions, sourceFileField);
    options.source_file_name = jstring_to_string(env, sourceFileString);
    env->DeleteLocalRef(sourceFileString);

    options.source_base_name = options.source_file_name;
    const size_t dotPos = options.source_base_name.find_last_of('.');
    if (dotPos != std::string::npos) {
        options.source_base_name.erase(dotPos);
    }

    jfieldID clipUriField = env->GetFieldID(optionsClass, "clipUriPath", "Ljava/lang/String;");
    jstring clipUriString = (jstring) env->GetObjectField(exportOptions, clipUriField);
    options.clip_uri_path = jstring_to_string(env, clipUriString);
    env->DeleteLocalRef(clipUriString);

    jfieldID audioDirField = env->GetFieldID(optionsClass, "audioTempDir", "Ljava/lang/String;");
    jstring audioDirString = (jstring) env->GetObjectField(exportOptions, audioDirField);
    options.audio_temp_dir = jstring_to_string(env, audioDirString);
    env->DeleteLocalRef(audioDirString);

    jfieldID stretchXField = env->GetFieldID(optionsClass, "stretchFactorX", "F");
    options.stretch_factor_x = env->GetFloatField(exportOptions, stretchXField);

    jfieldID stretchYField = env->GetFieldID(optionsClass, "stretchFactorY", "F");
    options.stretch_factor_y = env->GetFloatField(exportOptions, stretchYField);

    env->DeleteLocalRef(optionsClass);
    return options;
}

extern "C"
JNIEXPORT void JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_cancelExport(
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

static int acquire_frame_fd(void *, uint32_t frame_index, const char *relative_name) {
    if (!g_file_provider || !g_open_frame_fd_mid || !g_vm) {
        return -1;
    }
    JNIEnv *env = nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        return -1;
    }
    jstring jName = env->NewStringUTF(relative_name);
    jint fd = env->CallIntMethod(
            g_file_provider,
            g_open_frame_fd_mid,
            static_cast<jint>(frame_index),
            jName
    );
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
    jint fd = env->CallIntMethod(
            g_file_provider,
            g_open_container_fd_mid,
            jName
    );
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
    jint fd = env->CallIntMethod(
            g_file_provider,
            g_open_audio_fd_mid,
            jName
    );
    env->DeleteLocalRef(jName);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return -1;
    }
    return fd;
}

extern "C"
JNIEXPORT void JNICALL
Java_fm_forum_mlvapp_NativeInterface_NativeLib_exportHandler(
        JNIEnv *env,
        jobject /* thiz */,
        jlong cacheSize,
        jint cores,
        jintArray clipFds,
        jobject exportOptions,
        jobject progressListener,
        jobject fileProvider
) {
    env->GetJavaVM(&g_vm);

    g_progress_listener = env->NewGlobalRef(progressListener);
    jclass progressClazz = env->GetObjectClass(g_progress_listener);
    g_on_progress_mid = env->GetMethodID(progressClazz, "onProgress", "(I)V");
    env->DeleteLocalRef(progressClazz);

    if (fileProvider) {
        g_file_provider = env->NewGlobalRef(fileProvider);
        jclass providerClazz = env->GetObjectClass(g_file_provider);
        g_open_frame_fd_mid = env->GetMethodID(providerClazz, "openFrameFd", "(ILjava/lang/String;)I");
        g_open_container_fd_mid = env->GetMethodID(providerClazz, "openContainerFd", "(Ljava/lang/String;)I");
        g_open_audio_fd_mid = env->GetMethodID(providerClazz, "openAudioFd", "(Ljava/lang/String;)I");
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
    mlvObject_t *video = getMlvObject(env, clipFds, jFileName, cacheSize, cores, true);
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
        provider.acquire_frame_fd = g_open_frame_fd_mid ? acquire_frame_fd : nullptr;
        provider.acquire_container_fd = g_open_container_fd_mid ? acquire_container_fd : nullptr;
        provider.acquire_audio_fd = g_open_audio_fd_mid ? acquire_audio_fd : nullptr;
        provider.ctx = nullptr;
    }

    int result = startExportJob(
            video,
            options,
            provider,
            progress_callback
    );

    freeProcessingObject(video->processing);
    freeMlvObject(video);

    env->DeleteGlobalRef(g_progress_listener);
    g_progress_listener = nullptr;
    if (g_file_provider) {
        env->DeleteGlobalRef(g_file_provider);
        g_file_provider = nullptr;
    }

    if (result == EXPORT_CANCELLED) {
        jclass cancellationCls = env->FindClass("kotlin/coroutines/CancellationException");
        if (cancellationCls) {
            env->ThrowNew(cancellationCls, "Export cancelled");
        }
        return;
    }

    if (result != 0) {
        jclass exceptionCls = env->FindClass("java/lang/RuntimeException");
        if (exceptionCls) {
            env->ThrowNew(exceptionCls, "Export pipeline failed for selected codec");
        }
    }
}
