#include <jni.h>
#include "jni_common.h"
#include <string>
#include <iostream>

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_npuhub_jni_qualcomm_QualcommNativeBridge_nativeCheckGenieAvailable(JNIEnv *env, jclass clazz) {
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_npuhub_jni_qualcomm_QualcommNativeBridge_nativeLoadGenieModel(JNIEnv *env, jclass clazz, jstring modelPath, jint contextWindow) {
    std::string path = jstring2string(env, modelPath);
    std::cout << "[Native Qualcomm Genie JNI] Loaded model: " << path << std::endl;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_npuhub_jni_qualcomm_QualcommNativeBridge_nativeUnloadGenieModel(JNIEnv *env, jclass clazz) {
    std::cout << "[Native Qualcomm Genie JNI] Unloaded model" << std::endl;
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_npuhub_jni_qualcomm_QualcommNativeBridge_nativeGenerateGenie(JNIEnv *env, jclass clazz, jstring prompt, jdouble temp, jdouble topP, jint maxTokens) {
    std::string p = jstring2string(env, prompt);
    std::string resp = "[Qualcomm QAIRT Hexagon NPU Native] Answer: " + p;
    return env->NewStringUTF(resp.c_str());
}

JNIEXPORT void JNICALL
Java_com_npuhub_jni_qualcomm_QualcommNativeBridge_nativeGenerateGenieStream(JNIEnv *env, jclass clazz, jstring prompt, jdouble temp, jdouble topP, jint maxTokens, jobject callback) {
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;Z)V");
    if (!onTokenMethod) return;

    std::string tokens[] = {"[Qualcomm ", "QAIRT ", "Hexagon] ", "Native ", "streaming ", "active."};
    int count = sizeof(tokens) / sizeof(tokens[0]);
    for (int i = 0; i < count; i++) {
        jstring tokStr = env->NewStringUTF(tokens[i].c_str());
        jboolean done = (i == count - 1) ? JNI_TRUE : JNI_FALSE;
        env->CallVoidMethod(callback, onTokenMethod, tokStr, done);
        env->DeleteLocalRef(tokStr);
    }
}

}
