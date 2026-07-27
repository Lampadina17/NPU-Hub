# Configuration and Operations

## Precedence

The effective server configuration follows Spring Boot precedence rules:

1. `--key=value` arguments;
2. environment variables;
3. `src/main/resources/application.yml`.

Example:

```bash
java -jar target/npu-hub-1.0.0-SNAPSHOT.jar \
  --server.port=11434 \
  --npu.models.directory=/srv/npu-hub/models
```

Equivalent with variables:

```bash
SERVER_PORT=11434 \
NPU_MODELS_DIRECTORY=/srv/npu-hub/models \
java -jar target/npu-hub-1.0.0-SNAPSHOT.jar
```

Settings modified in the control panel pass through `SettingsService`:
they are stored in memory, are lost on restart, and do not rebind Spring properties.

## Spring Properties

| Property | Typical Environment Variable | Default | Effect |
| --- | --- | --- | --- |
| `server.port` | `SERVER_PORT` | `8080` | HTTP Port |
| `spring.mvc.async.request-timeout` | `SPRING_MVC_ASYNC_REQUEST_TIMEOUT` | `600000` ms | Async MVC timeout |
| `npu.models.directory` | `NPU_MODELS_DIRECTORY` | `models` | Models root directory |
| `npu.ollama.aliases-file` | `NPU_OLLAMA_ALIASES_FILE` | `.npuhub/ollama-models.json` | Persistent Ollama aliases |
| `npu.ollama.compatibility-version` | `NPU_OLLAMA_COMPATIBILITY_VERSION` | `0.20.0-npuhub.17` | Value returned by `/api/version` |
| `npu.ollama.default-context` | `NPU_OLLAMA_DEFAULT_CONTEXT` | `4096` | `num_ctx` when missing |
| `npu.ollama.minimum-loaded-context` | `NPU_OLLAMA_MINIMUM_LOADED_CONTEXT` | `4096` | Minimum loaded context on Rockchip |
| `npu.ollama.default-max-tokens` | `NPU_OLLAMA_DEFAULT_MAX_TOKENS` | `512` | `num_predict` when missing |
| `npu.ollama.default-keep-alive` | `NPU_OLLAMA_DEFAULT_KEEP_ALIVE` | `-1` | Value parsed by facade |
| `npu.ollama.keep-alive-scan-ms` | `NPU_OLLAMA_KEEP_ALIVE_SCAN_MS` | `1000` | Expiration check frequency |

Note: the explicit lifecycle currently renders `keep_alive` non-operational in the
Ollama/OpenAI flow. `OllamaInferenceFacade.finish()` does not update expiration and panel loading sets infinite residency.

Default logging configuration:

```yaml
logging:
  level:
    root: WARN
    com.npuhub: WARN
```

For debugging:

```bash
java -jar target/npu-hub-1.0.0-SNAPSHOT.jar \
  --logging.level.com.npuhub=DEBUG
```

## Control Panel Settings

`SettingsService` exposes:

| Key | Default | Current Status |
| --- | --- | --- |
| `preferredBackend` | `auto` | Used by frontend for selection and sent on load |
| `modelsDirectory` | `models` | UI only; does not change `npu.models.directory` |
| `ollamaPort` | `8080` | UI only; does not change `server.port` |
| `maxConcurrentInferences` | `2` | Not connected to inference thread pool |
| `defaultContextWindow` | `4096` | UI only; load control still uses 4096 |

For real changes, use Spring properties and restart the process. Before making these settings persistent, a single source of truth should be defined to avoid divergence between UI and runtime.

## Build Variables

| Variable | Default | Used by |
| --- | --- | --- |
| `NPU_HUB_BUILD_JOBS` | `2` | Parallelism of `tools/build-all.sh` |
| `NPU_HUB_MAVEN_VERSION` | `3.9.9` | Local Maven downloaded by script |
| `LLAMA_DIR` | `.rocket-runtime/llama.cpp` | External `llama.cpp` checkout |
| `RYZEN_AI_INSTALLATION_PATH` | none | Ryzen AI SDK discovery |
| `OpenVINOGenAI_DIR` | none | OpenVINO GenAI CMake package directory for the OpenVINO worker |
| `OPENVINO_GENAI_DIR` | none | Alias for `OpenVINOGenAI_DIR` |
| `OpenVINO_DIR` | none | OpenVINO runtime CMake package directory |
| `CMAKE_PREFIX_PATH` | none | Additional CMake prefixes, including an OpenVINO SDK install |

When none of `OpenVINOGenAI_DIR`, `OPENVINO_GENAI_DIR`, or `CMAKE_PREFIX_PATH` are set, the OpenVINO worker build automatically downloads the OpenVINO GenAI 2025.4 SDK archive from `storage.openvinotoolkit.org`, extracts it under `.openvino-sdk/` in the project root, and passes the resolved path as `CMAKE_PREFIX_PATH` to the CMake configure step. Subsequent builds reuse the cached extraction. The standalone "Setup SDK" button in the control panel triggers only the download and extraction without starting a build.

## Rocket Runtime Variables

| Variable | Default | Values / Meaning |
| --- | --- | --- |
| `NPU_HUB_ROCKET_MODE` | `hybrid` | `hybrid` or `strict` |
| `NPU_HUB_ROCKET_BATCH` | `2048` | `n_batch`, limited by context |
| `NPU_HUB_ROCKET_UBATCH` | `512` | `n_ubatch`, limited by batch |
| `NPU_HUB_ROCKET_KV_TYPE` | `q8_0` | `q8_0`, `f16`, `q4_0` |
| `NPU_HUB_ROCKET_THREADS` | min(CPU, 4) | CPU decode threads |
| `NPU_HUB_ROCKET_THREADS_BATCH` | min(CPU, 5) | Batch/prefill threads |
| `NPU_HUB_ROCKET_PROMPT_CACHE` | enabled | `0` disables it |
| `NPU_HUB_ROCKET_TRUNCATE_PROMPT` | enabled | `0` turns overflow into an error |
| `NPU_HUB_GGML_BACKEND_DIR` | auto | GGML plugin directory |
| `NPU_HUB_ROCKET_PLUGIN` | auto | Explicit path to `libggml-rocket.so` |
| `GGML_BACKEND_PATH` | auto | GGML-compatible candidate path |
| `ROCKET_STRICT` | disabled | Compatibility; used if `MODE` is missing |

Strict diagnostic example:

```bash
NPU_HUB_ROCKET_MODE=strict \
java -jar target/npu-hub-1.0.0-SNAPSHOT.jar
```

Hybrid mode is the intentional default: large, cost-effective prefills can use the NPU, while short token-by-token decoding can use the CPU. Therefore, "NPU-only" in Java selection does not mean every GGML operation is forced onto the NPU.

On memory-constrained machines, changing `num_ctx`, batch, and KV type together can drastically increase RAM and swap usage. The KV cache automatically falls back to `f16` if the quantized type is unsupported, increasing consumption.

## Directories and Data

| Path | Persistence | Content |
| --- | --- | --- |
| `models/` | Preserve | Weights and model directories |
| `.npuhub/ollama-models.json` | Preserve | Ollama aliases |
| `target/` | Generated | JAR and Maven classes |
| `native/build/` | Generated | JNI adapters |
| `workers/*/build/` | Generated | Worker runtimes |
| `src/main/resources/native/` | Generated/staging | Packaged JAR libraries |
| `.rocket-runtime/llama.cpp` | Re-downloadable | Upstream checkout |
| `.rocket-runtime/ggml-rocket` | Local external source | Rocket plugin |
| `.rocket-runtime/rocket-userspace` | Local external source | Rocket userspace |
| `.build-tools/` | Re-downloadable | Local Maven |
| `/tmp/npuhub-rocket-runtime-*` | Temporary | Libraries extracted from JAR |

Use absolute paths in production. With relative paths, models and aliases depend on the working directory from which the JAR is launched.

## Model Downloads

`tools/download_model.py` selects the source as follows:

- IDs starting with `OpenVINO/*` and `unsloth/*`: Hugging Face;
- other IDs: ModelScope.

For Rockchip, it filters GGUF files based on quantization. Each download first writes `<file>.part` and then renames it, but does not support resume. The following are absent:

- checksums;
- authentication for private repositories;
- retries in the Python downloader;
- cancellation;
- disk quotas;
- format validation beyond filename and size.

The 50 MiB threshold avoids considering small LFS pointer files valid, but does not guarantee integrity.

## Security

The server does not implement authentication. Additionally:

- `ControlPanelApiController` enables CORS from any origin;
- setup endpoints can download, compile, and install software;
- the Intel installer uses `pkexec`, `apt-get`, modifies udev rules, and adds the user to the `render` group;
- model endpoints can download and delete files;
- Ollama and OpenAI accept requests without API keys;
- Spring Boot normally listens on all network interfaces.

Recommended usage:

1. bind to loopback or a trusted VLAN;
2. set up a firewall on the port;
3. use a reverse proxy with authentication if exposed;
4. do not expose `/api/v1/control/setup/*` endpoints;
5. run with a user lacking unneeded privileges;
6. separate models and source code from sensitive data.

The project must not be exposed directly to the Internet in its current state.

## Operations and Side Effects

| Operation | Effect |
| --- | --- |
| Download model | Network activity and writing under models |
| Delete model | Recursive deletion or deletion of GGUF variant |
| Load | Runtime allocation, model, and KV cache |
| Unload | Frees native state, does not stop the API |
| Start API | Changes process-local flag |
| Stop API | Blocks four routes, does not free model |
| Build worker | Updates/clones sources, applies patch, and compiles |
| Setup ModelScope | Creates/reuses `.modelscope-venv` and installs ModelScope there |
| Setup Intel | Download, `pkexec`, packages, udev, user group |

Progress, logs, and flags are lost on every restart.

## Diagnostics

Useful endpoints:

```text
GET /api/v1/control/hardware
GET /api/v1/control/diagnostics
GET /api/v1/control/logs?afterId=0
GET /api/v1/control/setup/status?taskId=<id>
GET /api/ps
```

System checks:

```bash
ls -l /dev/accel/accel0 /dev/dri/renderD128 /dev/amdxdna /dev/kgsl-3d0
ldd path/to/libnpu_backend_jni.so
file path/to/libnpu_backend_jni.so
```

Not all devices exist on all platforms; `No such file` is expected for non-present backends.

### Backend Unavailable

Check:

1. device node and permissions;
2. host architecture;
3. loaded JNI library;
4. dynamic dependencies;
5. vendor SDK probe;
6. Spring logs and control panel terminal.

### Library Loaded but Generation Simulated

Check the path of the `.so`. If it comes from `native/build` or from the generic resource, it may be a stub. For OpenVINO/Ryzen AI it must come from the build of the respective `workers/`; for Rockchip from the Rocket bundle.

### Model Appears Not Downloaded

Check:

- directory calculated by `npu.models.directory`;
- repository/folder name;
- requested GGUF variant;
- total size over 50 MiB;
- any `.part` files;
- read permissions.

### `503` Request Error

Load a model and call:

```bash
curl -X POST http://localhost:8080/api/v1/control/api/start
```

The API flag always defaults to disabled after a restart.

### Context Window Too Large

A request cannot increase the context of an already loaded model. You must unload it and reload it with a larger context using a flow that exposes `requestedContextWindow`; the current control endpoint uses a fixed 4096. Reduce `num_ctx` or explicitly extend the load contract.
