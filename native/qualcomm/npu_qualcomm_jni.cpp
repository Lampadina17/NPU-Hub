#include <jni.h>
#include "jni_common.h"

#include <dlfcn.h>
#include <cstdlib>
#include <cctype>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <unistd.h>

namespace {

using GenieStatus = int;
using GenieHandle = void *;

enum GenieSentenceCode {
    GENIE_SENTENCE_COMPLETE = 0,
    GENIE_SENTENCE_BEGIN = 1,
    GENIE_SENTENCE_CONTINUE = 2,
    GENIE_SENTENCE_END = 3,
    GENIE_SENTENCE_ABORT = 4
};

using DialogConfigCreate = GenieStatus (*)(const char *, GenieHandle *);
using DialogCreate = GenieStatus (*)(GenieHandle, GenieHandle *);
using DialogQueryCallback = void (*)(const char *, GenieSentenceCode, const void *);
using DialogQuery = GenieStatus (*)(GenieHandle, const char *, GenieSentenceCode,
                                    DialogQueryCallback, const void *);
using DialogReset = GenieStatus (*)(GenieHandle);
using HandleFree = GenieStatus (*)(GenieHandle);

struct GenieApi {
    void *library = nullptr;
    DialogConfigCreate config_create = nullptr;
    DialogCreate dialog_create = nullptr;
    DialogQuery query = nullptr;
    DialogReset reset = nullptr;
    HandleFree config_free = nullptr;
    HandleFree dialog_free = nullptr;
};

struct GenieState {
    std::mutex mutex;
    GenieApi api;
    GenieHandle config = nullptr;
    GenieHandle dialog = nullptr;
    std::filesystem::path model_directory;
    std::string config_json;
    jint max_tokens = 0;
};

GenieState g_state;

template <typename T>
T load_symbol(void *library, const char *name) {
    return reinterpret_cast<T>(dlsym(library, name));
}

std::string read_file(const std::filesystem::path &path) {
    std::ifstream input(path);
    if (!input) {
        return {};
    }
    std::ostringstream contents;
    contents << input.rdbuf();
    return contents.str();
}

std::string replace_all(std::string value, const std::string &from, const std::string &to) {
    std::size_t offset = 0;
    while ((offset = value.find(from, offset)) != std::string::npos) {
        value.replace(offset, from.size(), to);
        offset += to.size();
    }
    return value;
}

std::string prepare_config(const std::filesystem::path &model_directory,
                           const std::filesystem::path &config_path) {
    std::string config = read_file(config_path);
    const std::string root = model_directory.string();
    // Genie resolves these paths relative to the process working directory.
    // The JVM must not chdir globally, so make the bundle paths absolute.
    config = replace_all(config, "\"tokenizer.json\"", "\"" + (model_directory / "tokenizer.json").string() + "\"");
    config = replace_all(config, "\"htp_backend_ext_config.json\"",
                         "\"" + (model_directory / "htp_backend_ext_config.json").string() + "\"");
    config = replace_all(config, "\"models/", "\"" + root + "/models/");
    return config;
}

std::filesystem::path find_config(const std::filesystem::path &model_directory) {
    if (!std::filesystem::is_directory(model_directory)) {
        return {};
    }
    for (const auto &entry : std::filesystem::directory_iterator(model_directory)) {
        if (!entry.is_regular_file() || entry.path().extension() != ".json") {
            continue;
        }
        std::string content = read_file(entry.path());
        if (content.find("\"dialog\"") != std::string::npos) {
            return entry.path();
        }
    }
    return {};
}

void free_state_locked() {
    if (g_state.dialog != nullptr && g_state.api.dialog_free != nullptr) {
        g_state.api.dialog_free(g_state.dialog);
    }
    if (g_state.config != nullptr && g_state.api.config_free != nullptr) {
        g_state.api.config_free(g_state.config);
    }
    g_state.dialog = nullptr;
    g_state.config = nullptr;
    g_state.model_directory.clear();
    g_state.config_json.clear();
    g_state.max_tokens = 0;
    if (g_state.api.library != nullptr) {
        dlclose(g_state.api.library);
    }
    g_state.api = {};
}

bool load_state_locked(const std::filesystem::path &model_directory) {
    free_state_locked();
    const auto library_path = model_directory / "libGenie.so";
    const auto config_path = find_config(model_directory);
    if (config_path.empty() || !std::filesystem::is_regular_file(library_path)) {
        return false;
    }

    const char *old_library_path = std::getenv("LD_LIBRARY_PATH");
    std::string library_path_env = model_directory.string();
    if (old_library_path != nullptr && *old_library_path != '\0') {
        library_path_env += ":";
        library_path_env += old_library_path;
    }
    setenv("LD_LIBRARY_PATH", library_path_env.c_str(), 1);

    // QNN loads the HTP skeleton on the DSP through ADSP_LIBRARY_PATH; the
    // host-side LD_LIBRARY_PATH above is not sufficient for FastRPC.
    const char *old_adsp_path = std::getenv("ADSP_LIBRARY_PATH");
    std::string adsp_library_path = model_directory.string();
    if (old_adsp_path != nullptr && *old_adsp_path != '\0') {
        adsp_library_path += ":";
        adsp_library_path += old_adsp_path;
    }
    setenv("ADSP_LIBRARY_PATH", adsp_library_path.c_str(), 1);

    g_state.api.library = dlopen(library_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (g_state.api.library == nullptr) {
        return false;
    }
    g_state.api.config_create = load_symbol<DialogConfigCreate>(g_state.api.library, "GenieDialogConfig_createFromJson");
    g_state.api.dialog_create = load_symbol<DialogCreate>(g_state.api.library, "GenieDialog_create");
    g_state.api.query = load_symbol<DialogQuery>(g_state.api.library, "GenieDialog_query");
    g_state.api.reset = load_symbol<DialogReset>(g_state.api.library, "GenieDialog_reset");
    g_state.api.config_free = load_symbol<HandleFree>(g_state.api.library, "GenieDialogConfig_free");
    g_state.api.dialog_free = load_symbol<HandleFree>(g_state.api.library, "GenieDialog_free");

    if (g_state.api.config_create == nullptr || g_state.api.dialog_create == nullptr
            || g_state.api.query == nullptr || g_state.api.config_free == nullptr
            || g_state.api.dialog_free == nullptr) {
        free_state_locked();
        return false;
    }

    const std::string config = prepare_config(model_directory, config_path);
    if (g_state.api.config_create(config.c_str(), &g_state.config) != 0
            || g_state.api.dialog_create(g_state.config, &g_state.dialog) != 0) {
        free_state_locked();
        return false;
    }
    g_state.model_directory = model_directory;
    g_state.config_json = config;
    return true;
}

bool set_max_tokens_locked(jint max_tokens) {
    if (max_tokens <= 0 || g_state.max_tokens == max_tokens) {
        return true;
    }

    std::string config = g_state.config_json;
    const std::string key = "\"max-num-tokens\"";
    const std::size_t key_position = config.find(key);
    if (key_position == std::string::npos) {
        return true;
    }
    const std::size_t colon = config.find(':', key_position + key.size());
    if (colon == std::string::npos) {
        return false;
    }
    std::size_t value_start = colon + 1;
    while (value_start < config.size() && std::isspace(static_cast<unsigned char>(config[value_start]))) {
        ++value_start;
    }
    std::size_t value_end = value_start;
    while (value_end < config.size() && std::isdigit(static_cast<unsigned char>(config[value_end]))) {
        ++value_end;
    }
    config.replace(value_start, value_end - value_start, std::to_string(max_tokens));

    if (g_state.dialog != nullptr && g_state.api.dialog_free != nullptr) {
        g_state.api.dialog_free(g_state.dialog);
        g_state.dialog = nullptr;
    }
    if (g_state.config != nullptr && g_state.api.config_free != nullptr) {
        g_state.api.config_free(g_state.config);
        g_state.config = nullptr;
    }
    if (g_state.api.config_create(config.c_str(), &g_state.config) != 0
            || g_state.api.dialog_create(g_state.config, &g_state.dialog) != 0) {
        return false;
    }
    g_state.max_tokens = max_tokens;
    return true;
}

struct QueryContext {
    JNIEnv *env;
    jobject callback;
    jmethodID on_token;
    std::string response;
};

void query_callback(const char *response, GenieSentenceCode code, const void *user_data) {
    auto *context = const_cast<QueryContext *>(static_cast<const QueryContext *>(user_data));
    if (response == nullptr || context == nullptr) {
        return;
    }
    context->response += response;
    if (context->callback == nullptr) {
        return;
    }

    jstring token = context->env->NewStringUTF(response);
    // The Java streaming layer treats a done chunk as terminal and does not
    // append its token. Send all Genie payloads as non-terminal chunks; the
    // JNI entry point emits one empty terminal chunk after query returns.
    context->env->CallVoidMethod(context->callback, context->on_token, token, JNI_FALSE);
    context->env->DeleteLocalRef(token);
}

void throw_genie_error(JNIEnv *env, const std::string &message) {
    jclass runtime = env->FindClass("java/lang/IllegalStateException");
    if (runtime != nullptr) {
        env->ThrowNew(runtime, message.c_str());
        env->DeleteLocalRef(runtime);
    }
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_npuhub_jni_qualcomm_QualcommNativeBridge_nativeCheckGenieAvailable(JNIEnv *, jclass) {
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_npuhub_jni_qualcomm_QualcommNativeBridge_nativeLoadGenieModel(
        JNIEnv *env, jclass, jstring model_path, jint) {
    const std::string path = jstring2string(env, model_path);
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!load_state_locked(std::filesystem::absolute(path).lexically_normal())) {
        throw_genie_error(env, "Unable to initialize the native QAIRT Genie dialog");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_npuhub_jni_qualcomm_QualcommNativeBridge_nativeUnloadGenieModel(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    free_state_locked();
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_npuhub_jni_qualcomm_QualcommNativeBridge_nativeGenerateGenie(
        JNIEnv *env, jclass, jstring prompt, jdouble, jdouble, jint max_tokens) {
    const std::string input = jstring2string(env, prompt);
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (g_state.dialog == nullptr) {
        throw_genie_error(env, "QAIRT Genie dialog is not loaded");
        return nullptr;
    }
    if (!set_max_tokens_locked(max_tokens)) {
        throw_genie_error(env, "Unable to apply the QAIRT output-token limit");
        return nullptr;
    }
    QueryContext context{env, nullptr, nullptr, {}};
    if (g_state.api.reset != nullptr) {
        g_state.api.reset(g_state.dialog);
    }
    if (g_state.api.query(g_state.dialog, input.c_str(), GENIE_SENTENCE_COMPLETE,
                          query_callback, &context) != 0) {
        throw_genie_error(env, "QAIRT Genie query failed");
        return nullptr;
    }
    return env->NewStringUTF(context.response.c_str());
}

JNIEXPORT void JNICALL
Java_com_npuhub_jni_qualcomm_QualcommNativeBridge_nativeGenerateGenieStream(
        JNIEnv *env, jclass, jstring prompt, jdouble, jdouble, jint max_tokens, jobject callback) {
    const std::string input = jstring2string(env, prompt);
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (g_state.dialog == nullptr) {
        throw_genie_error(env, "QAIRT Genie dialog is not loaded");
        return;
    }
    if (!set_max_tokens_locked(max_tokens)) {
        throw_genie_error(env, "Unable to apply the QAIRT output-token limit");
        return;
    }
    QueryContext context{
            env,
            callback,
            env->GetMethodID(env->GetObjectClass(callback), "onToken", "(Ljava/lang/String;Z)V"),
            {}
    };
    if (context.on_token == nullptr) {
        throw_genie_error(env, "QAIRT Genie callback method is unavailable");
        return;
    }
    if (g_state.api.reset != nullptr) {
        g_state.api.reset(g_state.dialog);
    }
    if (g_state.api.query(g_state.dialog, input.c_str(), GENIE_SENTENCE_COMPLETE,
                          query_callback, &context) != 0) {
        throw_genie_error(env, "QAIRT Genie streaming query failed");
        return;
    }
    jstring empty = env->NewStringUTF("");
    env->CallVoidMethod(callback, context.on_token, empty, JNI_TRUE);
    env->DeleteLocalRef(empty);
}

}
