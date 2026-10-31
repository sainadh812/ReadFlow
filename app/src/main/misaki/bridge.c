// Android JNI bridge. Byte arrays preserve standard UTF-8 (JNI's modified
// UTF-8 string API does not), including supplementary Unicode input.
#ifdef __ANDROID__
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "_cgo_export.h"

static char *utf8(JNIEnv *env, jbyteArray bytes) {
    jsize size = (*env)->GetArrayLength(env, bytes);
    char *copy = malloc((size_t) size + 1);
    if (copy == NULL) {
        jclass error = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
        if (error != NULL) (*env)->ThrowNew(env, error, "Misaki input allocation failed");
        return NULL;
    }
    (*env)->GetByteArrayRegion(env, bytes, 0, size, (jbyte *) copy);
    if ((*env)->ExceptionCheck(env)) { free(copy); return NULL; }
    copy[size] = 0;
    return copy;
}

static jbyteArray bytes_and_free(JNIEnv *env, char *text) {
    size_t size = strlen(text);
    jbyteArray result = (*env)->NewByteArray(env, (jsize) size);
    if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, (jsize) size, (jbyte *) text);
    ReadFlowMisakiFree(text);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_app_readflow_speech_misaki_MisakiFrontend_nativeCreate(JNIEnv *env, jobject self, jbyteArray library, jint threads) {
    char *path = utf8(env, library);
    if (path == NULL) return NULL;
    char *result = ReadFlowMisakiCreate(path, threads);
    free(path);
    return bytes_and_free(env, result);
}

JNIEXPORT jbyteArray JNICALL
Java_app_readflow_speech_misaki_MisakiFrontend_nativePhonemize(JNIEnv *env, jobject self, jlong handle, jbyteArray text) {
    char *input = utf8(env, text);
    if (input == NULL) return NULL;
    char *result = ReadFlowMisakiPhonemize((uint64_t) handle, input);
    free(input);
    return bytes_and_free(env, result);
}

JNIEXPORT jbyteArray JNICALL
Java_app_readflow_speech_misaki_MisakiFrontend_nativeClose(JNIEnv *env, jobject self, jlong handle) {
    return bytes_and_free(env, ReadFlowMisakiClose((uint64_t) handle));
}
#endif
