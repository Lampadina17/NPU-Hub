#include <jni.h>
#include <iostream>
#include <string>
#include <memory>
#include <vector>
#include <mutex>
#include <chrono>

#if __has_include(<openvino/genai/llm_pipeline.hpp>)
#include "openvino/genai/llm_pipeline.hpp"
#include <openvino/openvino.hpp>
#define HAVE_OPENVINO_GENAI 1
#endif

static std::mutex g_pipeline_mutex;

#if HAVE_OPENVINO_GENAI
static std::unique_ptr<ov::genai::LLMPipeline> g_pipeline;
static JavaVM* g_vm = nullptr;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    return JNI_VERSION_1_8;
}
#endif

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeCheckDeviceAvailable(JNIEnv* env, jclass clazz) {
#if HAVE_OPENVINO_GENAI
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
#else
    return JNI_TRUE;
#endif
}

JNIEXPORT jstring JNICALL Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeGetDeviceName(JNIEnv* env, jclass clazz) {
#if HAVE_OPENVINO_GENAI
    try {
        ov::Core core;
        std::string name = core.get_property("NPU", ov::device::full_name);
        return env->NewStringUTF(name.c_str());
    } catch (...) {
        return env->NewStringUTF("Intel NPU");
    }
#else
    return env->NewStringUTF("Intel NPU (OpenVINO C++ Driver)");
#endif
}

JNIEXPORT jboolean JNICALL Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeLoadModel(JNIEnv* env, jclass clazz, jstring modelPath, jint contextWindow) {
    if (!modelPath) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    std::lock_guard<std::mutex> lock(g_pipeline_mutex);
#if HAVE_OPENVINO_GENAI
    try {
        if (g_pipeline) {
            g_pipeline.reset();
        }
        ov::AnyMap properties;
        g_pipeline = std::make_unique<ov::genai::LLMPipeline>(path, "NPU", properties);
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        std::cerr << "OpenVINO Load Error: " << e.what() << std::endl;
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_FALSE;
    }
#else
    std::cout << "[OpenVINO Native JNI] Loading model from path: " << path << std::endl;
    env->ReleaseStringUTFChars(modelPath, path);
    return JNI_TRUE;
#endif
}

JNIEXPORT jboolean JNICALL Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeUnloadModel(JNIEnv* env, jclass clazz) {
    std::lock_guard<std::mutex> lock(g_pipeline_mutex);
#if HAVE_OPENVINO_GENAI
    try {
        if (g_pipeline) {
            g_pipeline.reset();
        }
        return JNI_TRUE;
    } catch (const std::exception& e) {
        std::cerr << "OpenVINO Unload Error: " << e.what() << std::endl;
        return JNI_FALSE;
    }
#else
    return JNI_TRUE;
#endif
}

JNIEXPORT jstring JNICALL Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeGenerate(JNIEnv* env, jclass clazz, jstring prompt, jdouble temperature, jdouble topP, jint maxTokens) {
    if (!prompt) return env->NewStringUTF("");
    const char* c_prompt = env->GetStringUTFChars(prompt, nullptr);
    std::lock_guard<std::mutex> lock(g_pipeline_mutex);

#if HAVE_OPENVINO_GENAI
    if (!g_pipeline) {
        env->ReleaseStringUTFChars(prompt, c_prompt);
        return env->NewStringUTF("");
    }
    try {
        ov::genai::GenerationConfig config = g_pipeline->get_generation_config();
        config.max_new_tokens = maxTokens > 0 ? maxTokens : 4096;
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
#else
    std::string res = "[OpenVINO NPU Fallback] Generated response for: " + std::string(c_prompt);
    env->ReleaseStringUTFChars(prompt, c_prompt);
    return env->NewStringUTF(res.c_str());
#endif
}

JNIEXPORT void JNICALL Java_com_npuhub_jni_openvino_OpenVinoNativeBridge_nativeGenerateStream(JNIEnv* env, jclass clazz, jstring prompt, jdouble temperature, jdouble topP, jint maxTokens, jobject callback) {
    if (!prompt || !callback) return;

    const char* c_prompt = env->GetStringUTFChars(prompt, nullptr);
    jobject globalCallback = env->NewGlobalRef(callback);
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;Z)V");
    if (!globalCallback || !onTokenMethod) {
        if (globalCallback) env->DeleteGlobalRef(globalCallback);
        env->ReleaseStringUTFChars(prompt, c_prompt);
        return;
    }

    std::lock_guard<std::mutex> lock(g_pipeline_mutex);

#if HAVE_OPENVINO_GENAI
    JavaVM* vm = g_vm;
    if (!vm) env->GetJavaVM(&vm);

    if (g_pipeline) {
        try {
            ov::genai::GenerationConfig config = g_pipeline->get_generation_config();
            config.max_new_tokens = maxTokens > 0 ? maxTokens : 4096;
            if (temperature > 0.0) {
                config.temperature = temperature;
                config.do_sample = true;
            }
            if (topP > 0.0) {
                config.top_p = topP;
                config.do_sample = true;
            }

            std::string pending;
            auto lastFlush = std::chrono::steady_clock::now();
            auto streamer = [vm, globalCallback, onTokenMethod, &pending, &lastFlush](std::string subword) -> ov::genai::StreamingStatus {
                pending += subword;
                const auto now = std::chrono::steady_clock::now();
                if (pending.size() < 96 && now - lastFlush < std::chrono::milliseconds(20)) {
                    return ov::genai::StreamingStatus::RUNNING;
                }
                JNIEnv* currentEnv = nullptr;
                bool shouldDetach = false;
                if (vm && vm->GetEnv(reinterpret_cast<void**>(&currentEnv), JNI_VERSION_1_8) != JNI_OK) {
                    if (vm->AttachCurrentThread(reinterpret_cast<void**>(&currentEnv), nullptr) == JNI_OK) {
                        shouldDetach = true;
                    }
                }
                if (currentEnv) {
                    jstring jsubword = currentEnv->NewStringUTF(pending.c_str());
                    currentEnv->CallVoidMethod(globalCallback, onTokenMethod, jsubword, JNI_FALSE);
                    currentEnv->DeleteLocalRef(jsubword);
                    if (shouldDetach) {
                        vm->DetachCurrentThread();
                    }
                }
                pending.clear();
                lastFlush = now;
                return ov::genai::StreamingStatus::RUNNING;
            };

            g_pipeline->generate(c_prompt, config, streamer);
            if (!pending.empty()) {
                jstring jpending = env->NewStringUTF(pending.c_str());
                env->CallVoidMethod(globalCallback, onTokenMethod, jpending, JNI_FALSE);
                env->DeleteLocalRef(jpending);
            }
            jstring doneStr = env->NewStringUTF("");
            env->CallVoidMethod(globalCallback, onTokenMethod, doneStr, JNI_TRUE);
            env->DeleteLocalRef(doneStr);
        } catch (const std::exception& e) {
            std::cerr << "OpenVINO Generate Stream Error: " << e.what() << std::endl;
        }
    }
#else
    std::string tokens[] = {"[OpenVINO ", "NPU ", "Streaming] ", "Hello ", "from ", "Native ", "C++!"};
    int count = sizeof(tokens) / sizeof(tokens[0]);
    for (int i = 0; i < count; i++) {
        jstring tokStr = env->NewStringUTF(tokens[i].c_str());
        jboolean done = (i == count - 1) ? JNI_TRUE : JNI_FALSE;
        env->CallVoidMethod(globalCallback, onTokenMethod, tokStr, done);
        env->DeleteLocalRef(tokStr);
    }
#endif

    env->ReleaseStringUTFChars(prompt, c_prompt);
    env->DeleteGlobalRef(globalCallback);
}

} // extern "C"
