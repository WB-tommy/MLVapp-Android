#include "jni_cache.h"

#include <android/log.h>

namespace {

constexpr const char *kLogTag = "MLVApp-JNI";

JniCache g_cache;
JavaVM *g_vm = nullptr;

bool CacheBitmapClasses(JNIEnv *env, JniCache &cache) {
    jclass localBitmapCls = env->FindClass("android/graphics/Bitmap");
    if (!localBitmapCls) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to find android/graphics/Bitmap");
        return false;
    }
    cache.bitmapClass = reinterpret_cast<jclass>(env->NewGlobalRef(localBitmapCls));
    env->DeleteLocalRef(localBitmapCls);
    if (!cache.bitmapClass) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to create global ref for Bitmap");
        return false;
    }

    jclass localConfigCls = env->FindClass("android/graphics/Bitmap$Config");
    if (!localConfigCls) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to find Bitmap$Config");
        return false;
    }
    cache.bitmapConfigClass = reinterpret_cast<jclass>(env->NewGlobalRef(localConfigCls));
    env->DeleteLocalRef(localConfigCls);
    if (!cache.bitmapConfigClass) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to create global ref for Bitmap$Config");
        return false;
    }

    cache.bitmapCreateMethod = env->GetStaticMethodID(
            cache.bitmapClass,
            "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (!cache.bitmapCreateMethod) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to cache Bitmap.createBitmap");
        return false;
    }

    jfieldID configArgb8888Field = env->GetStaticFieldID(
            cache.bitmapConfigClass,
            "ARGB_8888",
            "Landroid/graphics/Bitmap$Config;");
    if (!configArgb8888Field) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to cache Bitmap$Config.ARGB_8888 field");
        return false;
    }

    jobject localConfig = env->GetStaticObjectField(
            cache.bitmapConfigClass,
            configArgb8888Field);
    if (!localConfig) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to obtain Bitmap$Config.ARGB_8888 object");
        return false;
    }

    cache.bitmapConfigArgb8888 = env->NewGlobalRef(localConfig);
    env->DeleteLocalRef(localConfig);
    if (!cache.bitmapConfigArgb8888) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to promote ARGB_8888 to global ref");
        return false;
    }

    return true;
}

bool CacheClipClasses(JNIEnv *env, JniCache &cache) {
    jclass localPreviewCls = env->FindClass("fm/forum/mlvapp/data/ClipPreviewData");
    if (!localPreviewCls) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to find ClipPreviewData class");
        return false;
    }
    cache.clipPreviewDataClass = reinterpret_cast<jclass>(env->NewGlobalRef(localPreviewCls));
    env->DeleteLocalRef(localPreviewCls);
    if (!cache.clipPreviewDataClass) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to create global ref for ClipPreviewData");
        return false;
    }

    cache.clipPreviewCtor = env->GetMethodID(
            cache.clipPreviewDataClass,
            "<init>",
            "(IILandroid/graphics/Bitmap;JFFILjava/lang/String;)V");
    if (!cache.clipPreviewCtor) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to cache ClipPreviewData constructor");
        return false;
    }

    jclass localMetaCls = env->FindClass("fm/forum/mlvapp/data/ClipMetaData");
    if (!localMetaCls) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to find ClipMetaData class");
        return false;
    }
    cache.clipMetaDataClass = reinterpret_cast<jclass>(env->NewGlobalRef(localMetaCls));
    env->DeleteLocalRef(localMetaCls);
    if (!cache.clipMetaDataClass) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to create global ref for ClipMetaData");
        return false;
    }

    cache.clipMetaDataCtor = env->GetMethodID(
            cache.clipMetaDataClass,
            "<init>",
            "(JLjava/lang/String;Ljava/lang/String;IFIIIIIZILjava/lang/String;IIIIIIZIIZ)V");
    if (!cache.clipMetaDataCtor) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to cache ClipMetaData constructor");
        return false;
    }

    return true;
}

bool InitializeCache(JNIEnv *env, JniCache &cache) {
    if (cache.initialized) {
        return true;
    }
    if (!CacheBitmapClasses(env, cache)) {
        return false;
    }
    if (!CacheClipClasses(env, cache)) {
        return false;
    }
    cache.initialized = true;
    return true;
}

JNIEnv *GetEnvForCurrentThread(JavaVM *vm, bool *attachedHere = nullptr) {
    if (!vm) {
        return nullptr;
    }
    if (attachedHere) {
        *attachedHere = false;
    }
    JNIEnv *env = nullptr;
    jint getEnvResult = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (getEnvResult == JNI_OK) {
        return env;
    }
    if (getEnvResult == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            if (attachedHere) {
                *attachedHere = true;
            }
            return env;
        }
    }
    return nullptr;
}

} // namespace

JniCache &GetJniCache() {
    return g_cache;
}

bool EnsureJniCacheInitialized(JNIEnv *env) {
    return InitializeCache(env, g_cache);
}

void DestroyJniCache(JNIEnv *env) {
    if (!env || !g_cache.initialized) {
        return;
    }
    if (g_cache.bitmapClass) {
        env->DeleteGlobalRef(g_cache.bitmapClass);
        g_cache.bitmapClass = nullptr;
    }
    if (g_cache.bitmapConfigClass) {
        env->DeleteGlobalRef(g_cache.bitmapConfigClass);
        g_cache.bitmapConfigClass = nullptr;
    }
    if (g_cache.bitmapConfigArgb8888) {
        env->DeleteGlobalRef(g_cache.bitmapConfigArgb8888);
        g_cache.bitmapConfigArgb8888 = nullptr;
    }
    if (g_cache.clipPreviewDataClass) {
        env->DeleteGlobalRef(g_cache.clipPreviewDataClass);
        g_cache.clipPreviewDataClass = nullptr;
    }
    if (g_cache.clipMetaDataClass) {
        env->DeleteGlobalRef(g_cache.clipMetaDataClass);
        g_cache.clipMetaDataClass = nullptr;
    }
    g_cache.bitmapCreateMethod = nullptr;
    g_cache.clipPreviewCtor = nullptr;
    g_cache.clipMetaDataCtor = nullptr;
    g_cache.initialized = false;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    JNIEnv *env = GetEnvForCurrentThread(vm);
    if (!env) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "JNI_OnLoad: failed to obtain JNIEnv");
        return JNI_ERR;
    }
    if (!EnsureJniCacheInitialized(env)) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "JNI_OnLoad: cache initialization failed");
        DestroyJniCache(env);
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *) {
    bool attachedHere = false;
    JNIEnv *env = GetEnvForCurrentThread(vm, &attachedHere);
    if (!env) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "JNI_OnUnload: unable to obtain JNIEnv");
        return;
    }
    DestroyJniCache(env);
    if (attachedHere && vm) {
        vm->DetachCurrentThread();
    }
}
