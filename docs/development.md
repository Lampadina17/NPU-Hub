# Development Guide

## Prerequisites

To work solely on the Java control plane:

- Linux;
- JDK 17, including `javac`;
- Maven 3.9 or compatible.

For native adapters:

- CMake 3.16 or later;
- C/C++ compiler with C++17 support;
- JDK JNI headers;
- Git;
- SDK and drivers for the target backend.

Ryzen AI requires CMake 3.20 and C++20. Download and setup scripts also require Python 3, `curl`, `tar`, and network access.

## Java Verification

```bash
mvn test
```

The repository may include Maven under `.build-tools`:

```bash
./.build-tools/apache-maven-3.9.9/bin/mvn test
```

Currently, there are no tests under `src/test`. A `BUILD SUCCESS` result proves that the 41 Java classes compile and resources are copied, but not that:

- Spring starts correctly with a specific `.so`;
- JNI and Java have a consistent ABI;
- an NPU device is reachable;
- streaming and payloads are compatible with clients;
- the model generates correct output.

## Running in Development

```bash
mvn spring-boot:run
```

Or:

```bash
mvn package
java -jar target/npu-hub-1.0.0-SNAPSHOT.jar
```

To change port or models directory:

```bash
SERVER_PORT=11434 \
NPU_MODELS_DIRECTORY=/srv/npu-hub/models \
java -jar target/npu-hub-1.0.0-SNAPSHOT.jar
```

Spring Boot serves the frontend and APIs from the same process. No Node server needs to be started.

## Native Build Types

### Generic Adapters

```bash
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/build --parallel
```

This build creates stub libraries useful for testing loading and simple signatures. It must not be used to conclude that inference is hardware-accelerated. In particular, the Rockchip stub does not implement all current native signatures and is replaced by the full build.

### Rockchip Rocket

The supported path for an explicit Rocket build is:

```bash
NPU_HUB_BUILD_JOBS=2 NPU_HUB_BUILD_ALL_PLATFORMS=1 tools/build-all.sh
```

This path is not selected by default on Radxa ARM64 boards, where the platform
uses Qualcomm QAIRT. Orange Pi ARM64 boards still select Rocket. Detection uses
the device-tree model; `NPU_HUB_BOARD` can override it. To force a multi-platform build, set
`NPU_HUB_BUILD_ALL_PLATFORMS=1`; this also builds Rocket.

The script uses or creates:

```text
.rocket-runtime/llama.cpp
.rocket-runtime/ggml-rocket
.rocket-runtime/rocket-userspace
workers/rocket/build
native/build
src/main/resources/native
target
```

`llama.cpp` is updated to `origin/master` on every run. The patch `workers/rocket/patches/llama-rocket-strict.patch` must apply cleanly to the checkout. Before manually updating `llama.cpp`, verify:

```bash
git -C .rocket-runtime/llama.cpp apply --check \
  "$(pwd)/workers/rocket/patches/llama-rocket-strict.patch"
```

If the patch is already applied, the correct check is:

```bash
git -C .rocket-runtime/llama.cpp apply --reverse --check \
  "$(pwd)/workers/rocket/patches/llama-rocket-strict.patch"
```

The packaged `llama`, GGML, CPU, Rocket, and JNI libraries must come from the same build tree and compilation run. Mixing different ABIs causes symbol errors or runtime crashes.

### Real OpenVINO

The actual implementation resides in `workers/openvino/src/openvino_jni.cpp`. It requires a distribution providing the `OpenVINOGenAI` and `OpenVINO` CMake packages:

```bash
cmake -S workers/openvino -B workers/openvino/build \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_PREFIX_PATH=/path/to/openvino
cmake --build workers/openvino/build --parallel
```

When building the worker from the control panel, set `OpenVINOGenAI_DIR`,
`OPENVINO_GENAI_DIR`, `OpenVINO_DIR`, or `CMAKE_PREFIX_PATH` in the environment
of the NPU Hub process. These values are forwarded to CMake automatically.

The post-build step copies `libnpu_openvino_jni.so` into `native/build`. To bundle it into the JAR, it must be intentionally copied to `src/main/resources/native/libnpu_openvino_jni.so` before running `mvn package`. Also ensure that OpenVINO dependencies are accessible to the dynamic linker at runtime.

`tools/build-all.sh` recompiles generic adapters and may overwrite this file with the stub. Do not run both build workflows blindly.

### Real Ryzen AI

The actual implementation resides in `workers/ryzenai/src/ryzenai_jni.cpp`:

```bash
RYZEN_AI_INSTALLATION_PATH=/path/to/ryzen-ai \
cmake -S workers/ryzenai -B workers/ryzenai/build \
  -DCMAKE_BUILD_TYPE=Release
cmake --build workers/ryzenai/build --parallel
```

The build configuration looks for ONNX Runtime GenAI headers and library. You can pass them directly:

```text
ONNXRUNTIME_GENAI_INCLUDE_DIR
ONNXRUNTIME_GENAI_LIBRARY
```

Here too, the post-build step copies to `native/build`; packaging and runtime dependencies remain the responsibility of the distribution build.

### Qualcomm

There is no separate `workers/qualcomm` CMake tree. The Qualcomm adapter loads
`libGenie.so` directly through JNI and uses the native Genie dialog callback;
`genie-t2t-run` is only a reference tool. The QNN libraries are shipped in the
QAIRT model directory.
The model directory must contain at least:

1. a Genie dialog configuration such as `htp-model-config-llama32-1b-gqa.json`;
2. `libGenie.so`;
3. the QNN libraries;
4. the serialized model weights.

## Rules for Modifying JNI

Every modification to the native contract must be atomic:

1. update the Java bridge class;
2. update all C++ implementations with the matching symbol;
3. update the Java driver invoking the method;
4. update CMake and staging if dependencies or names change;
5. clean up old `.so` files;
6. recompile native libraries and the JAR;
7. verify exported symbols using `nm -D`;
8. launch with the real backend and perform load, generate, stream, and unload.

Example:

```bash
nm -D workers/rocket/build/bin/libnpu_rockchip_jni.so \
  | rg 'Java_com_npuhub_jni_rockchip_RockchipNativeBridge'
```

If `UnsatisfiedLinkError` occurs, check in order:

- which copy of the library was loaded;
- JNI symbol and signature;
- dependencies using `ldd`;
- ABI consistency between `libllama`, `libggml-*`, and Rocket plugin;
- binary architecture using `file`.

## Modifying the Model Catalog

The catalog is defined in `ModelManagementService.initCatalogModels()`. Each entry defines:

```text
id, name, path, architecture, quantization, parameterCount,
contextWindow, compatibleBackend
```

Checklist:

1. use an ID matching the remote repository;
2. choose a path under `models/`;
3. set the architecture as it determines the chat template;
4. for Rockchip, use a permitted quantization;
5. check the real GGUF filename;
6. verify download, >50 MiB detection, load, and `/api/tags`;
7. verify `/api/show` and GGUF context length.

To add a new Rockchip quantization, update `ROCKCHIP_QUANTIZATIONS`. The matcher is used by the catalog, downloader, Ollama resolution, and deletion: a change affects all these workflows.

## Adding a Backend

Adding a new backend involves multiple layers:

1. enum value in `BackendType`;
2. Spring implementation of `NpuDriver`;
3. Java bridge with native signatures;
4. real C++ implementation;
5. hardware probe;
6. CMake targets and packaging;
7. priority and display order in `NpuDriverRegistry`;
8. model catalog;
9. frontend groups and selectors;
10. documentation and tests.

Aim for a fail-closed probe: a backend should not declare itself available merely because its library loaded. The probe must verify the device, vendor runtime, and perform a minimal meaningful operation. Currently, Rockchip does not follow this rule yet because its native probe always returns `true`.

## Modifying the APIs

Compatible API surfaces share `OllamaInferenceFacade`; prompt and sampling logic should be modified there whenever it needs to remain consistent across Ollama and OpenAI.

Pay attention to protocol differences:

- Ollama streaming: `application/x-ndjson`, one JSON per line;
- OpenAI streaming: `text/event-stream`, SSE events and expected terminator;
- non-streaming: a single JSON object;
- Ollama errors: `{"error":"..."}`;
- OpenAI errors: nested error object.

When adding an inference endpoint that must obey start/stop, also update `InferenceApiGateFilter.isInferencePath()`.

Administrative APIs reside in `ControlPanelApiController`. Do not put external command execution inside controllers: wrap them in a service and expose status/progress.

## Modifying the Frontend

Main files:

- `templates/index.html`: server-rendered structure and content;
- `static/js/app.js`: state, fetch, chat streaming, and actions;
- `static/css/style.css`: layout and themes;
- `static/vendor`: vendorized libraries.

There is no frontend build step. After making a change:

1. start Spring with DevTools;
2. perform a hard refresh in the browser;
3. check the browser console and network tab;
4. test both desktop and mobile viewports;
5. verify that Markdown is still sanitized by DOMPurify.

## Tests to Add

Recommended priority:

1. unit tests for name/quantization resolution and paths;
2. unit tests for `GgufMetadataReader`;
3. MVC tests for gate, error envelope, and control APIs;
4. NDJSON/SSE streaming tests;
5. alias and persistence tests on temporary directories;
6. fake `NpuDriver` for lifecycle and concurrency;
7. separate native smoke tests for backend/hardware.

Java tests must not automatically load real `.so` files. Injecting a fake driver allows the test suite to run reliably on CI.

## Checklist Before Submitting a Change

For Java or frontend changes:

```bash
mvn test
git diff --check
```

Additionally, when applicable:

- Spring context startup;
- dashboard opening;
- download/status/delete on a temporary directory;
- load/start/generate/stream/stop/unload;
- `/api/tags`, `/api/ps`, `/api/show`;
- one non-streaming OpenAI request and one SSE request;
- check that no model files or generated `.so` files are included in the commit.

For native changes:

- clean build of the target;
- `ldd` check with no `not found` dependencies;
- JNI symbols present;
- testing on target hardware;
- verified fallback or failure logs;
- ensure no mock output is mistaken for real inference.

## Cleanup

```bash
tools/cleanup.sh --builds
tools/cleanup.sh --downloads
tools/cleanup.sh --all
```

`--builds` deletes CMake outputs, `target`, and generated native resources.
`--downloads` deletes `.build-tools` and the `llama.cpp` checkout.
`--all` combines both groups.

The script does not delete:

- models;
- `.npuhub/ollama-models.json`;
- `ggml-rocket` sources;
- `rocket-userspace` sources;
- logs or configuration files external to the repository.
