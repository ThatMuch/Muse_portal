/* Pont Java vers le détecteur « Hey Muse » (muse-gadget-sdk, Apache-2.0). */
#include <jni.h>
#include <stdint.h>

#include "ww_runner.h"

#define WW_MAX_FRAMES 1600 /* 100 ms à 16 kHz */

JNIEXPORT jlong JNICALL
Java_com_portalmuse_gadget_WakeWordEngine_nativeCreate(JNIEnv *env, jobject self, jint sensitivity)
{
    (void)env;
    (void)self;
    ww_config_t config = { .sample_rate_hz = 16000, .sensitivity = (ww_sensitivity_t)sensitivity,
                           .vad = WW_VAD_ENERGY };
    return (jlong)(intptr_t)ww_runner_create(&config);
}

/* Vrai si le mot d'activation vient d'être entendu. */
JNIEXPORT jboolean JNICALL
Java_com_portalmuse_gadget_WakeWordEngine_nativeFeed(JNIEnv *env, jobject self, jlong handle,
                                                     jshortArray samples, jint count)
{
    (void)self;
    ww_runner_t *runner = (ww_runner_t *)(intptr_t)handle;
    if (runner == NULL || count <= 0 || count > WW_MAX_FRAMES || count > (*env)->GetArrayLength(env, samples)) {
        return JNI_FALSE;
    }
    jshort pcm[WW_MAX_FRAMES];
    (*env)->GetShortArrayRegion(env, samples, 0, count, pcm);
    return ww_runner_feed(runner, (const int16_t *)pcm, (size_t)count) == WW_EVENT_WAKE;
}

JNIEXPORT jboolean JNICALL
Java_com_portalmuse_gadget_WakeWordEngine_nativeOk(JNIEnv *env, jobject self, jlong handle)
{
    (void)env;
    (void)self;
    ww_runner_t *runner = (ww_runner_t *)(intptr_t)handle;
    return runner != NULL && ww_runner_ok(runner);
}

JNIEXPORT void JNICALL
Java_com_portalmuse_gadget_WakeWordEngine_nativeDestroy(JNIEnv *env, jobject self, jlong handle)
{
    (void)env;
    (void)self;
    ww_runner_destroy((ww_runner_t *)(intptr_t)handle);
}
