#include "./mlv_processor_jni.h"
#include <cstring>

extern "C" JNIEXPORT jobject JNICALL
Java_fm_forum_mlvapp_MainActivity_openMlvForPreview(JNIEnv *env, jobject /* this */, jint fd,
                                                    jboolean isMlv) {
    int mlvErr = MLV_ERR_NONE;
    char mlvErrMsg[256] = {0};

    mlvObject_t *nativeClip = nullptr;

    // 1. Create the native mlvObject_t
    if (isMlv) {
        // We use a new function that accepts a file descriptor instead of a path.
        nativeClip = initMlvObjectWithClip(fd, MLV_OPEN_PREVIEW,
                                             &mlvErr, mlvErrMsg);
    } else {
        // For mcraw files, the mcraw library itself would need to be modified
        // to accept a file descriptor. For now, we'll show the MLV path.
        // A similar initMlvObjectWithMcrawClipFd would be needed.
//        nativeClip = initMlvObjectWithMcrawClip(fd,
//                                                  MLV_OPEN_PREVIEW, &mlvErr, mlvErrMsg);
    }

    if (mlvErrMsg[0]) { /* handle error */ return nullptr; }

    // 2. Perform the data formatting logic, ported from MainWindow.cpp
    float shutterSpeed = 1000000.0f / (float) (getMlvShutter(nativeClip));
    float shutterAngle = getMlvFramerate(nativeClip) * 360.0f / shutterSpeed;
    char shutterStr[128];
    snprintf(shutterStr, sizeof(shutterStr), "1/%.0f s, %.1f deg, %u µs", shutterSpeed,
             shutterAngle, getMlvShutter(nativeClip));

    LOGD("shutterStr: %s", shutterStr);

//    // ... port the complex ISO string logic here ...
//    char isoStr[64];
//    // ...
//    snprintf(isoStr, sizeof(isoStr), "...");

    return nullptr;

//    // 3. Find the Kotlin data class and its constructor
//    jclass clipDetailsClass = env->FindClass("fm/forum/mlvapp/data/MLVFile");
//    jmethodID constructor = env->GetMethodID(clipDetailsClass, "<init>",
//                                             "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"); // Simplified signature
//
//    // 4. Create Java strings from your formatted C strings
//    jstring jCameraName = env->NewStringUTF((const char *) getMlvCamera(nativeClip));
//    jstring jShutterInfo = env->NewStringUTF(shutterStr);
//    jstring jIsoInfo = env->NewStringUTF(isoStr);
//
//    // 5. Construct and return the clean Kotlin object
//    return env->NewObject(clipDetailsClass, constructor, clipId, jCameraName, jShutterInfo,
//                          jIsoInfo);
}
