#include "./mlv_processor_jni.h"
#include <cstring>

extern "C" JNIEXPORT jobject JNICALL
Java_fm_forum_mlvapp_MainActivity_openMlvForPreview(JNIEnv *env, jobject /* this */, jint fd,
                                                    jstring fileName, jlong maxRam) {
    int mlvErr = MLV_ERR_NONE;
    char mlvErrMsg[256] = {0};

    mlvObject_t *nativeClip = nullptr;
    const char *filePath = env->GetStringUTFChars(fileName, nullptr);

    if (filePath != nullptr) { // Always check for null after GetStringUTFChars
        size_t filePathLen = strlen(filePath);

        if (filePathLen > 3 &&
            (strncmp(filePath + filePathLen - 4, ".mlv", 4) == 0) ||
            strncmp(filePath + filePathLen - 4, ".MLV", 4) == 0) {
            nativeClip = initMlvObjectWithClip(
                    fd,
                    (char *) filePath,
                    MLV_OPEN_PREVIEW,
                    &mlvErr,
                    mlvErrMsg);
        }
//        else {
//            initMlvObjectWithMcrawClip(
//                    fd,
//                    (char *) filePath,
//                    MLV_OPEN_PREVIEW,
//                    &mlvErr,
//                    mlvErrMsg);
//        }
    }

    if (!nativeClip) { /* handle error */
        return nullptr;
    }

    env->ReleaseStringUTFChars(fileName, filePath);

    auto m_pProcessingObject = initProcessingObject();

    uint32_t m_cacheSizeMB = 0;
    /* Limit frame cache to suitable amount of RAM (~33% at 8GB and below, ~50% at 16GB, then up and up) */
    if (maxRam < 7500) m_cacheSizeMB = maxRam * 0.33;
    else m_cacheSizeMB = (uint32_t) (0.66666f * (float) (maxRam - 4000));

    setMlvProcessing(nativeClip, m_pProcessingObject);
    disableMlvCaching(nativeClip);
    setMlvRawCacheLimitMegaBytes(nativeClip, m_cacheSizeMB);

    // Disable low level raw fixes for preview
    nativeClip->llrawproc->fix_raw = 0;

    // Allocate memory for the raw image data
    int width = getMlvWidth(nativeClip);
    int height = getMlvHeight(nativeClip);
    auto *m_pRawImage = new unsigned char[width * height * 3]; // RGB888

    // Get frame from library
    getMlvProcessedFrame8(nativeClip, 0, m_pRawImage, 4 /* Example thread count */);
    jstring jCameraName = env->NewStringUTF((const char *) getMlvCamera(nativeClip));

    // Convert RGB888 to ByteArray for PreviewData
    jbyteArray thumbnailBytes = env->NewByteArray(width * height * 3);
    env->SetByteArrayRegion(thumbnailBytes, 0, width * height * 3, (jbyte*)m_pRawImage);

    // Clean up the original RGB buffer
    delete[] m_pRawImage;

    // Find PreviewData class and constructor
    jclass previewDataClass = env->FindClass("fm/forum/mlvapp/data/PreviewData");
    jmethodID constructor = env->GetMethodID(previewDataClass, "<init>", "(Ljava/lang/String;II[B)V");

    return env->NewObject(previewDataClass, constructor, jCameraName, width, height, thumbnailBytes);
}
