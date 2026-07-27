#include <jni.h>
#include "llama.h"
#include "ggml.h"
#include "ggml-backend.h"
#include <algorithm>
#include <array>
#include <cstdlib>
#include <dlfcn.h>
#include <filesystem>
#include <iostream>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <strings.h>
#include <vector>
#include <thread>
#include <cstring>
#include <chrono>

namespace fs = std::filesystem;

static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static std::vector<llama_token> g_cached_tokens;
static bool g_prompt_cache_enabled = true;
static constexpr uint32_t DEFAULT_ROCKET_BATCH_SIZE = 2048;
static constexpr uint32_t DEFAULT_ROCKET_UBATCH_SIZE = 512;
static JavaVM* g_java_vm = nullptr;
static jobject g_native_log_callback = nullptr;
static jmethodID g_native_log_method = nullptr;
static std::mutex g_native_log_mutex;

static void forward_native_log(enum ggml_log_level level, const char* text, void* user_data) {
    (void) user_data;
    if (!text || !*text) {
        return;
    }

    std::cerr << text;

    JavaVM* vm = nullptr;
    jobject callback = nullptr;
    jmethodID method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_native_log_mutex);
        vm = g_java_vm;
        method = g_native_log_method;
    }

    if (!vm || !method) {
        return;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) {
            env = nullptr;
        } else {
            attached = true;
        }
    }
    if (env) {
        {
            std::lock_guard<std::mutex> lock(g_native_log_mutex);
            if (g_native_log_callback && g_native_log_method == method) {
                callback = env->NewLocalRef(g_native_log_callback);
            }
        }
    }
    if (env && callback) {
        jstring message = env->NewStringUTF(text);
        env->CallVoidMethod(callback, method, static_cast<jint>(level), message);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        env->DeleteLocalRef(message);
        env->DeleteLocalRef(callback);
    }
    if (attached) {
        vm->DetachCurrentThread();
    }
}

static void emit_native_error(const std::string& message) {
    std::string line = message + "\n";
    forward_native_log(GGML_LOG_LEVEL_ERROR, line.c_str(), nullptr);
}

using llama_sampler_ptr = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;

static void throw_java_runtime(JNIEnv* env, const std::string& message) {
    jclass exception_class = env->FindClass("java/lang/IllegalStateException");
    if (exception_class) {
        env->ThrowNew(exception_class, message.c_str());
    }
}

static void unload_current_model() {
    g_cached_tokens.clear();
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
}

static uint32_t configured_batch_size(
        const char* variable,
        uint32_t fallback,
        uint32_t upper_bound) {
    const char* value = std::getenv(variable);
    if (!value || !*value) {
        return std::min(fallback, upper_bound);
    }

    char* end = nullptr;
    const unsigned long parsed = std::strtoul(value, &end, 10);
    if (end == value || *end != '\0' || parsed == 0) {
        std::cerr << "NPU Hub: ignoring invalid " << variable << "=" << value << std::endl;
        return std::min(fallback, upper_bound);
    }
    if (parsed >= upper_bound) {
        return upper_bound;
    }
    return static_cast<uint32_t>(parsed);
}

static bool environment_enabled(const char* variable, bool fallback) {
    const char* value = std::getenv(variable);
    if (!value || !*value) {
        return fallback;
    }
    return strcasecmp(value, "0") != 0
            && strcasecmp(value, "false") != 0
            && strcasecmp(value, "no") != 0
            && strcasecmp(value, "off") != 0;
}

static ggml_type configured_kv_cache_type() {
    const char* value = std::getenv("NPU_HUB_ROCKET_KV_TYPE");
    if (!value || !*value || strcasecmp(value, "q8_0") == 0) {
        return GGML_TYPE_Q8_0;
    }
    if (strcasecmp(value, "f16") == 0) {
        return GGML_TYPE_F16;
    }
    if (strcasecmp(value, "q4_0") == 0) {
        return GGML_TYPE_Q4_0;
    }
    std::cerr << "NPU Hub: unknown NPU_HUB_ROCKET_KV_TYPE=" << value
              << "; using q8_0" << std::endl;
    return GGML_TYPE_Q8_0;
}

static bool strict_mode_requested() {
    const char* mode = std::getenv("NPU_HUB_ROCKET_MODE");
    if (mode && *mode) {
        if (strcasecmp(mode, "strict") == 0
                || strcasecmp(mode, "npu-only") == 0) {
            return true;
        }
        if (strcasecmp(mode, "hybrid") != 0
                && strcasecmp(mode, "optimized") != 0) {
            std::cerr << "NPU Hub: unknown NPU_HUB_ROCKET_MODE=" << mode
                      << "; using hybrid" << std::endl;
        }
        return false;
    }
    return environment_enabled("ROCKET_STRICT", false);
}

static void fit_prompt_to_context(
        std::vector<llama_token>& tokens,
        jint requested_output_tokens) {
    const size_t context_size = llama_n_ctx(g_ctx);
    if (tokens.size() < context_size) {
        const size_t requested = requested_output_tokens > 0
                ? static_cast<size_t>(requested_output_tokens)
                : 1;
        const size_t minimum_prompt = std::min<size_t>(256, context_size - 1);
        const size_t output_reserve = std::min(
                requested,
                context_size - minimum_prompt);
        const size_t maximum_prompt = context_size - output_reserve;
        if (tokens.size() <= maximum_prompt) {
            return;
        }
    }

    if (!environment_enabled("NPU_HUB_ROCKET_TRUNCATE_PROMPT", true)) {
        std::ostringstream message;
        message << "Rocket prompt has " << tokens.size()
                << " tokens but the loaded context is " << context_size
                << "; reduce the prompt or increase num_ctx";
        throw std::runtime_error(message.str());
    }

    const size_t requested = requested_output_tokens > 0
            ? static_cast<size_t>(requested_output_tokens)
            : 1;
    const size_t minimum_prompt = std::min<size_t>(256, context_size - 1);
    const size_t output_reserve = std::min(
            requested,
            context_size - minimum_prompt);
    const size_t maximum_prompt = context_size - output_reserve;
    const size_t head_size = std::min<size_t>(512, maximum_prompt / 4);
    const size_t tail_size = maximum_prompt - head_size;
    const size_t original_size = tokens.size();

    std::vector<llama_token> compacted;
    compacted.reserve(maximum_prompt);
    compacted.insert(
            compacted.end(),
            tokens.begin(),
            tokens.begin() + static_cast<std::ptrdiff_t>(head_size));
    compacted.insert(
            compacted.end(),
            tokens.end() - static_cast<std::ptrdiff_t>(tail_size),
            tokens.end());
    tokens.swap(compacted);

    std::cerr << "NPU Hub: compacted Rocket prompt from " << original_size
              << " to " << tokens.size() << " tokens (ctx=" << context_size
              << ", output reserve=" << output_reserve << ")" << std::endl;
}

static size_t prepare_prompt_memory(const std::vector<llama_token>& tokens) {
    llama_memory_t memory = llama_get_memory(g_ctx);
    if (!g_prompt_cache_enabled || g_cached_tokens.empty()) {
        // Clearing only metadata avoids zero-filling the entire KV allocation
        // (512 MiB for Phi-4-mini at ctx=4096). Stale cells are not addressable
        // after the metadata reset and are overwritten by the next decode.
        llama_memory_clear(memory, false);
        g_cached_tokens.clear();
        return 0;
    }

    const size_t comparable = std::min(tokens.size(), g_cached_tokens.size());
    size_t reusable = 0;
    while (reusable < comparable
            && tokens[reusable] == g_cached_tokens[reusable]) {
        ++reusable;
    }

    // If the entire new prompt is already cached, re-evaluate its final token
    // so the logits correspond to that prompt rather than to an old generated
    // suffix that is about to be removed.
    if (reusable == tokens.size() && reusable > 0) {
        --reusable;
    }

    if (reusable == 0
            || !llama_memory_seq_rm(
                    memory,
                    0,
                    static_cast<llama_pos>(reusable),
                    -1)) {
        llama_memory_clear(memory, false);
        g_cached_tokens.clear();
        return 0;
    }

    g_cached_tokens.resize(reusable);
    return reusable;
}

static void decode_prompt_in_chunks(
        const std::vector<llama_token>& tokens,
        size_t offset) {
    const uint32_t context_size = llama_n_ctx(g_ctx);
    const uint32_t batch_size = llama_n_batch(g_ctx);

    if (tokens.size() >= context_size) {
        std::ostringstream message;
        message << "Rocket prompt has " << tokens.size()
                << " tokens but the loaded context is " << context_size
                << "; reduce the prompt or increase num_ctx";
        throw std::runtime_error(message.str());
    }
    if (batch_size == 0) {
        throw std::runtime_error("Rocket context reported an invalid zero batch size");
    }

    while (offset < tokens.size()) {
        const int32_t chunk_size = static_cast<int32_t>(
                std::min<size_t>(batch_size, tokens.size() - offset));
        llama_batch batch = llama_batch_get_one(
                const_cast<llama_token*>(tokens.data() + offset),
                chunk_size);
        if (llama_decode(g_ctx, batch) != 0) {
            std::ostringstream message;
            message << "Rocket NPU graph execution failed during prompt prefill at token "
                    << offset << " (chunk " << chunk_size << ")";
            throw std::runtime_error(message.str());
        }
        offset += static_cast<size_t>(chunk_size);
    }

    if (g_prompt_cache_enabled) {
        g_cached_tokens = tokens;
    }
}

static void cache_decoded_token(llama_token token) {
    if (g_prompt_cache_enabled) {
        g_cached_tokens.push_back(token);
    }
}

static void invalidate_prompt_cache() {
    g_cached_tokens.clear();
    if (g_ctx) {
        llama_memory_clear(llama_get_memory(g_ctx), false);
    }
}

static uint32_t generation_budget(size_t prompt_tokens, jint requested_tokens) {
    if (requested_tokens <= 0) {
        return 0;
    }

    const uint32_t context_size = llama_n_ctx(g_ctx);
    const size_t available = context_size > prompt_tokens
            ? static_cast<size_t>(context_size) - prompt_tokens
            : 0;
    return static_cast<uint32_t>(std::min<size_t>(
            static_cast<size_t>(requested_tokens),
            available));
}

static llama_sampler* create_sampler(
        jdouble temperature,
        jdouble top_p,
        jint top_k,
        jdouble min_p,
        jlong seed,
        jint repeat_last_n,
        jdouble repeat_penalty,
        jdouble frequency_penalty,
        jdouble presence_penalty) {
    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = true;
    llama_sampler* sampler = llama_sampler_chain_init(sampler_params);
    if (repeat_last_n != 0
            && (repeat_penalty != 1.0 || frequency_penalty != 0.0 || presence_penalty != 0.0)) {
        llama_sampler_chain_add(
                sampler,
                llama_sampler_init_penalties(
                        repeat_last_n,
                        static_cast<float>(repeat_penalty),
                        static_cast<float>(frequency_penalty),
                        static_cast<float>(presence_penalty)));
    }
    if (top_k > 0) {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
    }
    if (top_p > 0.0 && top_p < 1.0) {
        llama_sampler_chain_add(
                sampler,
                llama_sampler_init_top_p(static_cast<float>(top_p), 1));
    }
    if (min_p > 0.0 && min_p < 1.0) {
        llama_sampler_chain_add(
                sampler,
                llama_sampler_init_min_p(static_cast<float>(min_p), 1));
    }
    if (temperature > 0.0) {
        llama_sampler_chain_add(
                sampler,
                llama_sampler_init_temp(static_cast<float>(temperature)));
        const uint32_t sampler_seed = seed < 0
                ? LLAMA_DEFAULT_SEED
                : static_cast<uint32_t>(seed);
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(sampler_seed));
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    }
    return sampler;
}

static void append_unique(std::vector<fs::path>& paths, const fs::path& path) {
    if (path.empty()) {
        return;
    }

    std::error_code ec;
    const fs::path normalized = fs::weakly_canonical(path, ec);
    const fs::path candidate = ec ? path.lexically_normal() : normalized;
    if (std::find(paths.begin(), paths.end(), candidate) == paths.end()) {
        paths.push_back(candidate);
    }
}

static fs::path module_directory(const void* symbol) {
    Dl_info info{};
    if (dladdr(symbol, &info) == 0 || !info.dli_fname) {
        return {};
    }

    std::error_code ec;
    fs::path module = fs::weakly_canonical(fs::path(info.dli_fname), ec);
    if (ec) {
        module = fs::path(info.dli_fname);
    }
    return module.parent_path();
}

static bool path_is_regular_file(const fs::path& path) {
    std::error_code ec;
    return fs::is_regular_file(path, ec);
}

static bool has_backend_device(const char* expected_name) {
    for (size_t index = 0; index < ggml_backend_dev_count(); ++index) {
        const char* name = ggml_backend_dev_name(ggml_backend_dev_get(index));
        if (name && strcasecmp(name, expected_name) == 0) {
            return true;
        }
    }
    return false;
}

static bool has_cpu_backend() {
    for (size_t index = 0; index < ggml_backend_dev_count(); ++index) {
        if (ggml_backend_dev_type(ggml_backend_dev_get(index)) == GGML_BACKEND_DEVICE_TYPE_CPU) {
            return true;
        }
    }
    return false;
}

static std::string registered_backend_summary() {
    std::ostringstream summary;
    for (size_t index = 0; index < ggml_backend_dev_count(); ++index) {
        if (index != 0) {
            summary << ", ";
        }
        ggml_backend_dev_t device = ggml_backend_dev_get(index);
        summary << ggml_backend_dev_name(device);
    }
    return ggml_backend_dev_count() == 0 ? "none" : summary.str();
}

static void add_repository_plugin_candidate(std::vector<fs::path>& plugins, fs::path start) {
    for (int depth = 0; depth < 6 && !start.empty(); ++depth) {
        append_unique(plugins, start / ".rocket-runtime/ggml-rocket/build-dl/libggml-rocket.so");
        const fs::path parent = start.parent_path();
        if (parent == start) {
            break;
        }
        start = parent;
    }
}

static void load_ggml_backends() {
    static std::once_flag once;
    static std::string initialization_error;

    std::call_once(once, [] {
        try {
            std::vector<fs::path> backend_directories;
            if (const char* configured = std::getenv("NPU_HUB_GGML_BACKEND_DIR")) {
                append_unique(backend_directories, configured);
            }

            append_unique(
                backend_directories,
                module_directory(reinterpret_cast<const void*>(&ggml_backend_load_all_from_path)));
            append_unique(
                backend_directories,
                module_directory(reinterpret_cast<const void*>(&load_ggml_backends)));

            std::error_code ec;
            const fs::path working_directory = fs::current_path(ec);
            if (!ec) {
                append_unique(backend_directories, working_directory);
                append_unique(backend_directories, working_directory / "workers/rocket/build/bin");
                append_unique(backend_directories, working_directory / "native/build");
            }

            fs::path selected_backend_directory;
            for (const fs::path& directory : backend_directories) {
                bool has_cpu_plugin = path_is_regular_file(directory / "libggml-cpu.so");
                if (!has_cpu_plugin) {
                    std::error_code scan_error;
                    for (const auto& entry : fs::directory_iterator(directory, scan_error)) {
                        const std::string name = entry.path().filename().string();
                        if (entry.is_regular_file() &&
                            name.rfind("libggml-cpu-", 0) == 0 &&
                            entry.path().extension() == ".so") {
                            has_cpu_plugin = true;
                            break;
                        }
                    }
                }
                if (has_cpu_plugin) {
                    selected_backend_directory = directory;
                    ggml_backend_load_all_from_path(directory.string().c_str());
                    break;
                }
            }

            if (selected_backend_directory.empty()) {
                ggml_backend_load_all();
            }

            if (!has_cpu_backend()) {
                std::ostringstream message;
                message << "GGML CPU backend was not loaded. Searched:";
                for (const fs::path& directory : backend_directories) {
                    message << " " << directory;
                }
                throw std::runtime_error(message.str());
            }

            if (!has_backend_device("ROCKET")) {
                std::vector<fs::path> plugin_candidates;
                if (const char* configured = std::getenv("NPU_HUB_ROCKET_PLUGIN")) {
                    append_unique(plugin_candidates, configured);
                }
                if (const char* configured = std::getenv("GGML_BACKEND_PATH")) {
                    append_unique(plugin_candidates, configured);
                }
                if (!selected_backend_directory.empty()) {
                    append_unique(plugin_candidates, selected_backend_directory / "libggml-rocket.so");
                    add_repository_plugin_candidate(plugin_candidates, selected_backend_directory);
                }
                if (!working_directory.empty()) {
                    append_unique(plugin_candidates, working_directory / "workers/rocket/build/bin/libggml-rocket.so");
                    add_repository_plugin_candidate(plugin_candidates, working_directory);
                }

                for (const fs::path& plugin : plugin_candidates) {
                    if (path_is_regular_file(plugin) && ggml_backend_load(plugin.string().c_str())) {
                        std::cerr << "NPU Hub: loaded Rocket backend from " << plugin << std::endl;
                        break;
                    }
                }

                if (!has_backend_device("ROCKET")) {
                    std::ostringstream message;
                    message << "ROCKET backend was not loaded. Set NPU_HUB_ROCKET_PLUGIN to "
                            << "libggml-rocket.so. Registered devices: "
                            << registered_backend_summary() << ". Searched:";
                    for (const fs::path& plugin : plugin_candidates) {
                        message << " " << plugin;
                    }
                    throw std::runtime_error(message.str());
                }
            }

            std::cerr << "NPU Hub: GGML backends ready ("
                      << registered_backend_summary() << ")" << std::endl;
        } catch (const std::exception& error) {
            initialization_error = error.what();
        }
    });

    if (!initialization_error.empty()) {
        throw std::runtime_error(initialization_error);
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_npuhub_jni_rockchip_RockchipNativeBridge_nativeCheckAccel0Available(JNIEnv* env, jclass clazz) {
    return JNI_TRUE; // Assume handled in Java
}

JNIEXPORT jstring JNICALL Java_com_npuhub_jni_rockchip_RockchipNativeBridge_nativeGetRknnVersion(JNIEnv* env, jclass clazz) {
    return env->NewStringUTF("1.6.0"); // Dummy for now
}

JNIEXPORT void JNICALL Java_com_npuhub_jni_rockchip_RockchipNativeBridge_nativeSetLogCallback(
        JNIEnv* env,
        jclass clazz,
        jobject callback) {
    env->GetJavaVM(&g_java_vm);
    {
        std::lock_guard<std::mutex> lock(g_native_log_mutex);
        if (g_native_log_callback) {
            env->DeleteGlobalRef(g_native_log_callback);
            g_native_log_callback = nullptr;
        }
        g_native_log_method = nullptr;
        if (callback) {
            g_native_log_callback = env->NewGlobalRef(callback);
            jclass callback_class = env->GetObjectClass(callback);
            g_native_log_method = env->GetMethodID(
                    callback_class,
                    "onLog",
                    "(ILjava/lang/String;)V");
            env->DeleteLocalRef(callback_class);
        }
    }

    llama_log_set(callback ? forward_native_log : nullptr, nullptr);
    ggml_log_set(callback ? forward_native_log : nullptr, nullptr);
}

JNIEXPORT jboolean JNICALL Java_com_npuhub_jni_rockchip_RockchipNativeBridge_nativeLoadRocketModel(JNIEnv* env, jclass clazz, jstring modelPath, jint contextWindow) {
    if (!modelPath) return JNI_FALSE;
    const char* utfPath = env->GetStringUTFChars(modelPath, nullptr);
    if (!utfPath) return JNI_FALSE;
    const std::string path(utfPath);
    env->ReleaseStringUTFChars(modelPath, utfPath);

    try {
        if (!path_is_regular_file(path)) {
            throw std::runtime_error(
                "Expected a GGUF model file, but the path is missing or is a directory: " + path);
        }

        const bool strict_mode = strict_mode_requested();
        if (strict_mode) {
            setenv("ROCKET_STRICT", "1", 1);
        } else {
            unsetenv("ROCKET_STRICT");
        }
        setenv("ROCKET_KACC", "1", 1);
        unsetenv("ROCKET_NO_KACC");
        setenv("ROCKET_INT8", "0", 1);
        setenv("ROCKET_INT4", "0", 1);
        setenv("ROCKET_BF16", "0", 1);
        setenv("ROCKET_MOE", "0", 1);
        setenv("ROCKET_MOE_NATIVE", "0", 1);
        setenv("ROCKET_MOE_COSINE", "0", 1);
        setenv("ROCKET_ATTN_HOST_SOFTMAX", "0", 1);
        setenv("ROCKET_FA_TILE_KV", "0", 1);
        g_prompt_cache_enabled = environment_enabled(
                "NPU_HUB_ROCKET_PROMPT_CACHE",
                true);
        
        load_ggml_backends();

        // Loading a second model into the process must replace the first one;
        // otherwise llama.cpp keeps both the context and model allocations alive.
        unload_current_model();
        
        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0; // Handled by ggml backend
        model_params.use_extra_bufts = false;
        
        g_model = llama_model_load_from_file(path.c_str(), model_params);
        if (!g_model) return JNI_FALSE;
        
        auto ctx_params = llama_context_default_params();
        const uint32_t requested_context = contextWindow > 0
                ? static_cast<uint32_t>(contextWindow)
                : 4096u;
        ctx_params.n_ctx = requested_context;
        ctx_params.n_batch = configured_batch_size(
                "NPU_HUB_ROCKET_BATCH",
                DEFAULT_ROCKET_BATCH_SIZE,
                requested_context);
        ctx_params.n_ubatch = configured_batch_size(
                "NPU_HUB_ROCKET_UBATCH",
                DEFAULT_ROCKET_UBATCH_SIZE,
                ctx_params.n_batch);
        ctx_params.n_outputs_max = 1;
        ctx_params.type_k = configured_kv_cache_type();
        ctx_params.type_v = ctx_params.type_k;
        ctx_params.swa_full = false;
        ctx_params.no_perf = true;
        ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        ctx_params.offload_kqv = true;
        ctx_params.op_offload = true;
        
        const unsigned int hardware_threads = std::thread::hardware_concurrency();
        if (hardware_threads > 0) {
            ctx_params.n_threads = static_cast<int32_t>(configured_batch_size(
                    "NPU_HUB_ROCKET_THREADS",
                    std::min(hardware_threads, 4u),
                    hardware_threads));
            ctx_params.n_threads_batch = static_cast<int32_t>(configured_batch_size(
                    "NPU_HUB_ROCKET_THREADS_BATCH",
                    std::min(hardware_threads, 5u),
                    hardware_threads));
        }
        
        g_ctx = llama_init_from_model(g_model, ctx_params);
        if (!g_ctx && ctx_params.type_k != GGML_TYPE_F16) {
            std::cerr << "NPU Hub: KV cache type "
                      << ggml_type_name(ctx_params.type_k)
                      << " is unsupported by this model/runtime; retrying with f16"
                      << std::endl;
            ctx_params.type_k = GGML_TYPE_F16;
            ctx_params.type_v = GGML_TYPE_F16;
            g_ctx = llama_init_from_model(g_model, ctx_params);
        }
        if (!g_ctx) {
            llama_model_free(g_model);
            g_model = nullptr;
            return JNI_FALSE;
        }

        std::cerr << "NPU Hub: Rocket context ready (ctx=" << llama_n_ctx(g_ctx)
                  << ", batch=" << llama_n_batch(g_ctx)
                  << ", ubatch=" << llama_n_ubatch(g_ctx)
                  << ", kv=" << ggml_type_name(ctx_params.type_k)
                  << ", threads=" << ctx_params.n_threads
                  << ", mode=" << (strict_mode ? "strict" : "hybrid")
                  << ", prompt-cache="
                  << (g_prompt_cache_enabled ? "on" : "off")
                  << ")" << std::endl;
        
        return JNI_TRUE;
    } catch (const std::exception& e) {
        emit_native_error("Rocket Load Error: " + std::string(e.what()));
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL Java_com_npuhub_jni_rockchip_RockchipNativeBridge_nativeUnloadRocketModel(JNIEnv* env, jclass clazz) {
    unload_current_model();
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL Java_com_npuhub_jni_rockchip_RockchipNativeBridge_nativeGenerateRocket(
        JNIEnv* env,
        jclass clazz,
        jstring prompt,
        jdouble temperature,
        jdouble topP,
        jint maxTokens,
        jint topK,
        jdouble minP,
        jlong seed,
        jint repeatLastN,
        jdouble repeatPenalty,
        jdouble frequencyPenalty,
        jdouble presencePenalty) {
    if (!g_model || !g_ctx || !prompt) {
        throw_java_runtime(env, "Rocket model is not loaded");
        return nullptr;
    }
    const char* c_prompt = env->GetStringUTFChars(prompt, nullptr);
    
    try {
        const llama_vocab* vocab = llama_model_get_vocab(g_model);
        const size_t prompt_size = std::strlen(c_prompt);
        const int count = -llama_tokenize(
                vocab,
                c_prompt,
                prompt_size,
                nullptr,
                0,
                true,
                true);
        if (count <= 0) {
            throw std::runtime_error("Rocket failed to tokenize the prompt");
        }
        std::vector<llama_token> tokens(count);
        llama_tokenize(
                vocab,
                c_prompt,
                prompt_size,
                tokens.data(),
                count,
                true,
                true);
        
        fit_prompt_to_context(tokens, maxTokens);
        const size_t reused_tokens = prepare_prompt_memory(tokens);
        decode_prompt_in_chunks(tokens, reused_tokens);
        const uint32_t token_budget = generation_budget(tokens.size(), maxTokens);

        llama_sampler_ptr sampler(
                create_sampler(
                temperature,
                topP,
                topK,
                minP,
                seed,
                repeatLastN,
                repeatPenalty,
                frequencyPenalty,
                presencePenalty),
                llama_sampler_free);

        llama_token next_token = LLAMA_TOKEN_NULL;
        uint32_t generated = 0;
        
        std::string result;
        result.reserve(static_cast<size_t>(token_budget) * 4);
        
        while (generated < token_budget) {
            next_token = llama_sampler_sample(sampler.get(), g_ctx, -1);
            if (llama_vocab_is_eog(vocab, next_token)) {
                break;
            }
            
            std::array<char, 256> buffer{};
            int size = llama_token_to_piece(
                    vocab,
                    next_token,
                    buffer.data(),
                    buffer.size(),
                    0,
                    true);
            if (size > 0) {
                result.append(buffer.data(), static_cast<size_t>(size));
            }
            
            generated++;
            if (generated < token_budget) {
                llama_batch batch = llama_batch_get_one(&next_token, 1);
                if (llama_decode(g_ctx, batch) != 0) {
                    throw std::runtime_error(
                            "Rocket NPU graph execution failed during token decode");
                }
                cache_decoded_token(next_token);
            }
        }
        
        env->ReleaseStringUTFChars(prompt, c_prompt);
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception& e) {
        invalidate_prompt_cache();
        env->ReleaseStringUTFChars(prompt, c_prompt);
        emit_native_error("Rocket Generate Error: " + std::string(e.what()));
        throw_java_runtime(env, e.what());
        return nullptr;
    }
}

JNIEXPORT void JNICALL Java_com_npuhub_jni_rockchip_RockchipNativeBridge_nativeGenerateRocketStream(
        JNIEnv* env,
        jclass clazz,
        jstring prompt,
        jdouble temperature,
        jdouble topP,
        jint maxTokens,
        jint topK,
        jdouble minP,
        jlong seed,
        jint repeatLastN,
        jdouble repeatPenalty,
        jdouble frequencyPenalty,
        jdouble presencePenalty,
        jobject callback) {
    if (!g_model || !g_ctx || !prompt || !callback) {
        throw_java_runtime(env, "Rocket model is not loaded");
        return;
    }
    
    const char* c_prompt = env->GetStringUTFChars(prompt, nullptr);
    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;Z)V");
    
    try {
        const llama_vocab* vocab = llama_model_get_vocab(g_model);
        const size_t prompt_size = std::strlen(c_prompt);
        const int count = -llama_tokenize(
                vocab,
                c_prompt,
                prompt_size,
                nullptr,
                0,
                true,
                true);
        if (count <= 0) {
            throw std::runtime_error("Rocket failed to tokenize the prompt");
        }
        std::vector<llama_token> tokens(count);
        llama_tokenize(
                vocab,
                c_prompt,
                prompt_size,
                tokens.data(),
                count,
                true,
                true);
        
        fit_prompt_to_context(tokens, maxTokens);
        const size_t reused_tokens = prepare_prompt_memory(tokens);
        decode_prompt_in_chunks(tokens, reused_tokens);
        const uint32_t token_budget = generation_budget(tokens.size(), maxTokens);

        llama_sampler_ptr sampler(
                create_sampler(
                temperature,
                topP,
                topK,
                minP,
                seed,
                repeatLastN,
                repeatPenalty,
                frequencyPenalty,
                presencePenalty),
                llama_sampler_free);

        llama_token next_token = LLAMA_TOKEN_NULL;
        uint32_t generated = 0;
        
        while (generated < token_budget) {
            next_token = llama_sampler_sample(sampler.get(), g_ctx, -1);
            if (llama_vocab_is_eog(vocab, next_token)) {
                break;
            }
            
            std::array<char, 257> buffer{};
            int size = llama_token_to_piece(
                    vocab,
                    next_token,
                    buffer.data(),
                    buffer.size() - 1,
                    0,
                    true);
            if (size > 0) {
                buffer[static_cast<size_t>(size)] = '\0';
                jstring jtext = env->NewStringUTF(buffer.data());
                env->CallVoidMethod(callback, onTokenMethod, jtext, JNI_FALSE);
                env->DeleteLocalRef(jtext);
            }
            
            generated++;
            if (generated < token_budget) {
                llama_batch batch = llama_batch_get_one(&next_token, 1);
                if (llama_decode(g_ctx, batch) != 0) {
                    throw std::runtime_error(
                            "Rocket NPU graph execution failed during token decode");
                }
                cache_decoded_token(next_token);
            }
        }
        
        jstring doneText = env->NewStringUTF("");
        env->CallVoidMethod(callback, onTokenMethod, doneText, JNI_TRUE);
        env->DeleteLocalRef(doneText);
        env->ReleaseStringUTFChars(prompt, c_prompt);
    } catch (const std::exception& e) {
        invalidate_prompt_cache();
        env->ReleaseStringUTFChars(prompt, c_prompt);
        emit_native_error("Rocket Generate Stream Error: " + std::string(e.what()));
        throw_java_runtime(env, e.what());
    }
}

} // extern "C"
