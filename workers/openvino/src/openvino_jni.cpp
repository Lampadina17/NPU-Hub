#include <jni.h>
#include "openvino/genai/llm_pipeline.hpp"
#include <openvino/openvino.hpp>
#include <iostream>
#include <string>
#include <memory>
#include <vector>

static std::unique_ptr<ov::genai::LLMPipeline> g_pipeline;

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeCheckDeviceAvailable(JNIEnv* env, jclass clazz) {
    try {
        ov::Core core;
        std::vector<std::string> devices = core.get_available_devices();
        for (const auto& dev : devices) {
            if (dev.find("NPU") != std::string::npos) return JNI_TRUE;
        }
        return JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeGetDeviceName(JNIEnv* env, jclass clazz) {
    try {
        ov::Core core;
        std::string name = core.get_property("NPU", ov::device::full_name);
        return env->NewStringUTF(name.c_str());
    } catch (...) {
        return env->NewStringUTF("Intel NPU");
    }
}

JNIEXPORT jboolean JNICALL Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeLoadModel(JNIEnv* env, jclass clazz, jstring modelPath, jint contextWindow) {
    if (!modelPath) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    try {
        g_pipeline = std::make_unique<ov::genai::LLMPipeline>(path, "NPU");
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        std::cerr << "OpenVINO Load Error: " << e.what() << std::endl;
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeUnloadModel(JNIEnv* env, jclass clazz) {
    g_pipeline.reset();
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeGenerate(JNIEnv* env, jclass clazz, jstring prompt, jdouble temperature, jdouble topP, jint maxTokens) {
    if (!g_pipeline || !prompt) return env->NewStringUTF("");
    const char* c_prompt = env->GetStringUTFChars(prompt, nullptr);
    
    try {
        ov::genai::GenerationConfig config = g_pipeline->get_generation_config();
        config.max_new_tokens = maxTokens;
        if (temperature > 0.0) {
            config.temperature = temperature;
            config.do_sample = true;
        }
        if (topP > 0.0) {
            config.top_p = topP;
            config.do_sample = true;
        }
        
        std::string result = g_pipeline->generate(c_prompt, config);
        env->ReleaseStringUTFChars(prompt, c_prompt);
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception& e) {
        env->ReleaseStringUTFChars(prompt, c_prompt);
        std::cerr << "OpenVINO Generate Error: " << e.what() << std::endl;
        return env->NewStringUTF("");
    }
}

JNIEXPORT void JNICALL Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeGenerateStream(JNIEnv* env, jclass clazz, jstring prompt, jdouble temperature, jdouble topP, jint maxTokens, jobject callback) {
    if (!g_pipeline || !prompt || !callback) return;
    
    const char* c_prompt = env->GetStringUTFChars(prompt, nullptr);
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;Z)V");
    
    try {
        ov::genai::GenerationConfig config = g_pipeline->get_generation_config();
        config.max_new_tokens = maxTokens;
        if (temperature > 0.0) {
            config.temperature = temperature;
            config.do_sample = true;
        }
        if (topP > 0.0) {
            config.top_p = topP;
            config.do_sample = true;
        }
        
        auto streamer = [&env, callback, onTokenMethod](std::string subword) {
            jstring jsubword = env->NewStringUTF(subword.c_str());
            env->CallVoidMethod(callback, onTokenMethod, jsubword, JNI_FALSE);
            env->DeleteLocalRef(jsubword);
            return ov::genai::StreamingStatus::RUNNING;
        };
        
        g_pipeline->generate(c_prompt, config, streamer);
        
        env->CallVoidMethod(callback, onTokenMethod, env->NewStringUTF(""), JNI_TRUE);
        
        env->ReleaseStringUTFChars(prompt, c_prompt);
    } catch (const std::exception& e) {
        env->ReleaseStringUTFChars(prompt, c_prompt);
        std::cerr << "OpenVINO Generate Stream Error: " << e.what() << std::endl;
    }
}

} // extern "C"
