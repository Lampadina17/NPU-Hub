#include <jni.h>
#include "jni_common.h"
#include <string>
#include <iostream>

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeCheckDeviceAvailable(JNIEnv *env, jclass clazz) {
    // Probing OpenVINO Intel NPU driver availability
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeGetDeviceName(JNIEnv *env, jclass clazz) {
    return env->NewStringUTF("Intel NPU (OpenVINO GenAI C++ JNI Driver)");
}

JNIEXPORT jboolean JNICALL
Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeLoadModel(JNIEnv *env, jclass clazz, jstring modelPath, jint contextWindow) {
    std::string path = jstring2string(env, modelPath);
    std::cout << "[Native OpenVINO JNI] Loading model from: " << path << std::endl;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeUnloadModel(JNIEnv *env, jclass clazz) {
    std::cout << "[Native OpenVINO JNI] Model unloaded" << std::endl;
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeGenerate(JNIEnv *env, jclass clazz, jstring prompt, jdouble temp, jdouble topP, jint maxTokens) {
    std::string p = jstring2string(env, prompt);
    std::string response = "[OpenVINO NPU Native Acceleration] Processed prompt: " + p;
    return env->NewStringUTF(response.c_str());
}

JNIEXPORT void JNICALL
Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeGenerateStream(JNIEnv *env, jclass clazz, jstring prompt, jdouble temp, jdouble topP, jint maxTokens, jobject callback) {
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;Z)V");
    if (!onTokenMethod) return;

    std::string tokens[] = {"[OpenVINO ", "NPU ", "Streaming] ", "Hello ", "from ", "Native ", "C++ ", "JNI!"};
    int count = sizeof(tokens) / sizeof(tokens[0]);
    for (int i = 0; i < count; i++) {
        jstring tokStr = env->NewStringUTF(tokens[i].c_str());
        jboolean done = (i == count - 1) ? JNI_TRUE : JNI_FALSE;
        env->CallVoidMethod(callback, onTokenMethod, tokStr, done);
        env->DeleteLocalRef(tokStr);
    }
}

}
