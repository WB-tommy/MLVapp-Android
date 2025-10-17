#ifndef MLVAPP_JNI_CACHE_H
#define MLVAPP_JNI_CACHE_H

#include <jni.h>

struct JniCache {
    bool initialized = false;

    jclass bitmapClass = nullptr;
    jclass bitmapConfigClass = nullptr;
    jmethodID bitmapCreateMethod = nullptr;
    jobject bitmapConfigArgb8888 = nullptr;

    jclass clipPreviewDataClass = nullptr;
    jmethodID clipPreviewCtor = nullptr;

    jclass clipMetaDataClass = nullptr;
    jmethodID clipMetaDataCtor = nullptr;
};

JniCache &GetJniCache();
bool EnsureJniCacheInitialized(JNIEnv *env);
void DestroyJniCache(JNIEnv *env);

#endif // MLVAPP_JNI_CACHE_H
