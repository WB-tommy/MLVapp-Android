//
// Created by Sungmin Choi on 2025. 10. 12..
//
#include "clip/clip_jni.h"
#include <unistd.h>

extern "C" {
JNIEXPORT void JNICALL
Java_fm_magiclantern_forum_NativeInterface_NativeLib_setBaseDir(
        JNIEnv *env, jobject /* this */, jstring baseDir) {
    const char *path = env->GetStringUTFChars(baseDir, nullptr);
    chdir(path);
}
}