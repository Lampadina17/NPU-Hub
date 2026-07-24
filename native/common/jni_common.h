#ifndef NPU_JNI_COMMON_H
#define NPU_JNI_COMMON_H

#include <jni.h>
#include <string>
#include <iostream>

inline std::string jstring2string(JNIEnv *env, jstring jStr) {
    if (!jStr) return "";
    const char *cstr = env->GetStringUTFChars(jStr, nullptr);
    std::string str(cstr);
    env->ReleaseStringUTFChars(jStr, cstr);
    return str;
}

#endif // NPU_JNI_COMMON_H
