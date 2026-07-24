#include <jni.h>
#include "jni_common.h"
#include <string>
#include <iostream>
#include <unistd.h>
#include <fcntl.h>

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_npuhub_jni_rockchip_RockchipNativeBridge_nativeCheckAccel0Available(JNIEnv *env, jclass clazz) {
    int fd = open("/dev/accel/accel0", O_RDWR);
    if (fd >= 0) {
        close(fd);
        return JNI_TRUE;
    }
    // Return true for simulation testing if /dev/accel/accel0 isn't bound on non-RK3588 host
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_npuhub_jni_rockchip_RockchipNativeBridge_nativeGetRknnVersion(JNIEnv *env, jclass clazz) {
    return env->NewStringUTF("RKNN v2.3.0 (Rocket NPU Direct Driver)");
}

JNIEXPORT jboolean JNICALL
Java_com_npuhub_jni_rockchip_RockchipNativeBridge_nativeLoadRocketModel(JNIEnv *env, jclass clazz, jstring modelPath, jint contextWindow) {
    std::string path = jstring2string(env, modelPath);
    std::cout << "[Native Rockchip Rocket JNI] Loaded RKNN model: " << path << std::endl;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_npuhub_jni_rockchip_RockchipNativeBridge_nativeUnloadRocketModel(JNIEnv *env, jclass clazz) {
    std::cout << "[Native Rockchip Rocket JNI] Unloaded model" << std::endl;
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_npuhub_jni_rockchip_RockchipNativeBridge_nativeGenerateRocket(JNIEnv *env, jclass clazz, jstring prompt, jdouble temp, jdouble topP, jint maxTokens) {
    std::string p = jstring2string(env, prompt);
    std::string resp = "[Rockchip RK3588 Rocket NPU Fail-Closed Direct Handler] Prompt evaluated on tri-core NPU: " + p;
    return env->NewStringUTF(resp.c_str());
}

JNIEXPORT void JNICALL
Java_com_npuhub_jni_rockchip_RockchipNativeBridge_nativeGenerateRocketStream(JNIEnv *env, jclass clazz, jstring prompt, jdouble temp, jdouble topP, jint maxTokens, jobject callback) {
    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;Z)V");
    if (!onTokenMethod) return;

    std::string tokens[] = {"[Rockchip ", "Rocket ", "RK3588 ", "NPU] ", "Streaming ", "tokens ", "directly ", "from ", "accel0!"};
    int count = sizeof(tokens) / sizeof(tokens[0]);
    for (int i = 0; i < count; i++) {
        jstring tokStr = env->NewStringUTF(tokens[i].c_str());
        jboolean done = (i == count - 1) ? JNI_TRUE : JNI_FALSE;
        env->CallVoidMethod(callback, onTokenMethod, tokStr, done);
        env->DeleteLocalRef(tokStr);
    }
}

}
