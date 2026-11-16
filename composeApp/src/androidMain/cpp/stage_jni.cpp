#include <jni.h>
#include "stage3d.h"

namespace {
class FloatInput {
    JNIEnv* env;
    jfloatArray source;

  public:
    const jsize size;
    jfloat* const data;
    FloatInput(JNIEnv* env, jfloatArray source)
        : env(env), source(source), size(env->GetArrayLength(source)),
          data(size ? env->GetFloatArrayElements(source, nullptr) : nullptr) {}
    ~FloatInput() {
        if (data)
            env->ReleaseFloatArrayElements(source, data, JNI_ABORT);
    }
};
} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_music_pinch_ui_CascadeGL_create(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(stage3d_create());
}

extern "C" JNIEXPORT void JNICALL Java_music_pinch_ui_CascadeGL_destroy(JNIEnv*, jobject, jlong stage) {
    stage3d_destroy(reinterpret_cast<void*>(stage));
}

extern "C" JNIEXPORT void JNICALL Java_music_pinch_ui_CascadeGL_upload(JNIEnv* env, jobject, jlong stage,
                                                                       jfloatArray keys, jfloatArray rails,
                                                                       jfloatArray lamps) {
    FloatInput k(env, keys), r(env, rails), l(env, lamps);
    if (env->ExceptionCheck())
        return;
    stage3d_set_course(reinterpret_cast<void*>(stage), k.data, k.size / STAGE3D_KEY_STRIDE,
                       r.data, r.size / 3, l.data, l.size / 3);
}

extern "C" JNIEXPORT void JNICALL Java_music_pinch_ui_CascadeGL_draw(JNIEnv* env, jobject, jlong stage,
                                                                     jint width, jint height,
                                                                     jfloatArray frame) {
    float values[STAGE3D_FRAME_SIZE];
    env->GetFloatArrayRegion(frame, 0, STAGE3D_FRAME_SIZE, values);
    if (!env->ExceptionCheck())
        stage3d_draw(reinterpret_cast<void*>(stage), width, height, values);
}
