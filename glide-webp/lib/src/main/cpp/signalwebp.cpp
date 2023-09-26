#include <jni.h>
#include <webp/demux.h>
#include <android/log.h>

jobject createBitmap(JNIEnv *env, int width, int height, const uint8_t *pixels) {
    static auto      jbitmapConfigClass         = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("android/graphics/Bitmap$Config")));
    static jfieldID  jbitmapConfigARGB8888Field = env->GetStaticFieldID(jbitmapConfigClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    static auto      jbitmapClass               = reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass("android/graphics/Bitmap")));
    static jmethodID jbitmapCreateBitmapMethod  = env->GetStaticMethodID(jbitmapClass, "createBitmap", "([IIIIILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");

    jintArray intArray = env->NewIntArray(width * height);
    env->SetIntArrayRegion(intArray, 0, width * height, reinterpret_cast<const jint *>(pixels));

    jobject argb8888Config = env->GetStaticObjectField(jbitmapConfigClass, jbitmapConfigARGB8888Field);
    jobject jbitmap        = env->CallStaticObjectMethod(jbitmapClass, jbitmapCreateBitmapMethod, intArray, 0, width, width, height, argb8888Config);
    env->DeleteLocalRef(argb8888Config);

    return jbitmap;
}

jobject nativeDecodeBitmapScaled(JNIEnv *env, jobject, jbyteArray data, jint requestedWidth, jint requestedHeight) {
    jbyte *javaBytes    = env->GetByteArrayElements(data, nullptr);
    auto  *buffer       = reinterpret_cast<uint8_t *>(javaBytes);
    jsize  bufferLength = env->GetArrayLength(data);

    WebPBitstreamFeatures features;
    if (WebPGetFeatures(buffer, bufferLength, &features) != VP8_STATUS_OK) {
        __android_log_write(ANDROID_LOG_WARN, "WebpResourceDecoder", "GetFeatures");
        env->ReleaseByteArrayElements(data, javaBytes, 0);
        return nullptr;
    }

    WebPDecoderConfig config;
    if (!WebPInitDecoderConfig(&config)) {
        __android_log_write(ANDROID_LOG_WARN, "WebpResourceDecoder", "Init decoder config");
        env->ReleaseByteArrayElements(data, javaBytes, 0);
        return nullptr;
    }

    float hRatio = 1.0;
    float vRatio = 1.0;
    if (features.width >= features.height && features.width > 0) {
        vRatio = static_cast<float>(features.height) / static_cast<float>(features.width);
    } else if (features.width < features.height && features.height > 0) {
        hRatio = static_cast<float>(features.width) / static_cast<float>(features.height);
    }

    config.options.no_fancy_upsampling = 1;
    config.options.use_scaling         = 1;
    config.options.scaled_width        = static_cast<int>(static_cast<float>(requestedWidth) * hRatio);
    config.options.scaled_height       = static_cast<int>(static_cast<float>(requestedHeight) * vRatio);
    config.output.colorspace           = MODE_BGRA;

    if (WebPDecode(buffer, bufferLength, &config) != VP8_STATUS_OK) {
        __android_log_write(ANDROID_LOG_WARN, "WebpResourceDecoder", "WebPDecode");
        env->ReleaseByteArrayElements(data, javaBytes, 0);
        return nullptr;
    }

    uint8_t *pixels = config.output.u.RGBA.rgba;
    jobject jbitmap = nullptr;
    if (pixels != nullptr) {
        jbitmap = createBitmap(env, config.output.width, config.output.height, pixels);
    }

    WebPFree(pixels);
    env->ReleaseByteArrayElements(data, javaBytes, 0);

    return jbitmap;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass c = env->FindClass("org/signal/glide/webp/WebpDecoder");
    if (c == nullptr) {
        return JNI_ERR;
    }

    static const JNINativeMethod methods[] = {
            {"nativeDecodeBitmapScaled", "([BII)Landroid/graphics/Bitmap;", reinterpret_cast<void *>(nativeDecodeBitmapScaled)}
    };

    int rc = env->RegisterNatives(c, methods, sizeof(methods) / sizeof(JNINativeMethod));

    if (rc != JNI_OK) {
        return rc;
    }

    return JNI_VERSION_1_6;
}
