#include <jni.h>
#include <onnxruntime_genai_cxx_api.h>
#include <iostream>
#include <string>
#include <memory>
#include <vector>

static std::unique_ptr<OgaModel> g_model;
static std::unique_ptr<OgaTokenizer> g_tokenizer;

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_npuhub_jni_ryzenai_RyzenAiNativeBridge_nativeCheckXdnaAvailable(JNIEnv* env, jclass clazz) {
    return JNI_TRUE; // Rely on Java-side /dev/amdxdna check for now
}

JNIEXPORT jboolean JNICALL Java_com_npuhub_jni_ryzenai_RyzenAiNativeBridge_nativeLoadOnnxGenieModel(JNIEnv* env, jclass clazz, jstring modelPath, jint contextWindow) {
    if (!modelPath) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    try {
        g_model = OgaModel::Create(path);
        g_tokenizer = OgaTokenizer::Create(*g_model);
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        std::cerr << "RyzenAI Load Error: " << e.what() << std::endl;
        env->ReleaseStringUTFChars(modelPath, path);
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL Java_com_npuhub_jni_ryzenai_RyzenAiNativeBridge_nativeUnloadOnnxGenieModel(JNIEnv* env, jclass clazz) {
    g_tokenizer.reset();
    g_model.reset();
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL Java_com_npuhub_jni_ryzenai_RyzenAiNativeBridge_nativeGenerateOnnxGenie(JNIEnv* env, jclass clazz, jstring prompt, jdouble temperature, jdouble topP, jint maxTokens) {
    if (!g_model || !g_tokenizer || !prompt) return env->NewStringUTF("");
    const char* c_prompt = env->GetStringUTFChars(prompt, nullptr);
    
    try {
        auto input = OgaSequences::Create();
        g_tokenizer->Encode(c_prompt, *input);
        const auto input_length = input->SequenceCount(0);
        
        auto params = OgaGeneratorParams::Create(*g_model);
        params->SetSearchOption("max_length", static_cast<double>(input_length + maxTokens));
        if (temperature > 0.0) {
            params->SetSearchOption("temperature", temperature);
            params->SetSearchOptionBool("do_sample", true);
        }
        if (topP > 0.0) {
            params->SetSearchOption("top_p", topP);
        }
        
        auto generator = OgaGenerator::Create(*g_model, *params);
        generator->AppendTokenSequences(*input);
        
        std::string result = "";
        auto stream = OgaTokenizerStream::Create(*g_tokenizer);
        
        size_t emitted = input_length;
        while (!generator->IsDone()) {
            generator->GenerateNextToken();
            const auto count = generator->GetSequenceCount(0);
            const auto* tokens = generator->GetSequenceData(0);
            while (emitted < count) {
                if (const char* text = stream->Decode(tokens[emitted++]); text && *text) {
                    result += text;
                }
            }
        }
        
        env->ReleaseStringUTFChars(prompt, c_prompt);
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception& e) {
        env->ReleaseStringUTFChars(prompt, c_prompt);
        std::cerr << "RyzenAI Generate Error: " << e.what() << std::endl;
        return env->NewStringUTF("");
    }
}

JNIEXPORT void JNICALL Java_com_npuhub_jni_ryzenai_RyzenAiNativeBridge_nativeGenerateOnnxGenieStream(JNIEnv* env, jclass clazz, jstring prompt, jdouble temperature, jdouble topP, jint maxTokens, jobject callback) {
    if (!g_model || !g_tokenizer || !prompt || !callback) return;
    
    const char* c_prompt = env->GetStringUTFChars(prompt, nullptr);
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;Z)V");
    
    try {
        auto input = OgaSequences::Create();
        g_tokenizer->Encode(c_prompt, *input);
        const auto input_length = input->SequenceCount(0);
        
        auto params = OgaGeneratorParams::Create(*g_model);
        params->SetSearchOption("max_length", static_cast<double>(input_length + maxTokens));
        if (temperature > 0.0) {
            params->SetSearchOption("temperature", temperature);
            params->SetSearchOptionBool("do_sample", true);
        }
        if (topP > 0.0) {
            params->SetSearchOption("top_p", topP);
        }
        
        auto generator = OgaGenerator::Create(*g_model, *params);
        generator->AppendTokenSequences(*input);
        auto stream = OgaTokenizerStream::Create(*g_tokenizer);
        
        size_t emitted = input_length;
        while (!generator->IsDone()) {
            generator->GenerateNextToken();
            const auto count = generator->GetSequenceCount(0);
            const auto* tokens = generator->GetSequenceData(0);
            while (emitted < count) {
                if (const char* text = stream->Decode(tokens[emitted++]); text && *text) {
                    jstring jtext = env->NewStringUTF(text);
                    env->CallVoidMethod(callback, onTokenMethod, jtext, JNI_FALSE);
                    env->DeleteLocalRef(jtext);
                }
            }
        }
        
        env->CallVoidMethod(callback, onTokenMethod, env->NewStringUTF(""), JNI_TRUE);
        env->ReleaseStringUTFChars(prompt, c_prompt);
    } catch (const std::exception& e) {
        env->ReleaseStringUTFChars(prompt, c_prompt);
        std::cerr << "RyzenAI Generate Stream Error: " << e.what() << std::endl;
    }
}

} // extern "C"
