# NPU Hub

NPU Hub is a local inference server for NPUs featuring:

- an integrated web control panel;
- APIs compatible with Ollama and partially with OpenAI;
- Java/Spring Boot orchestration;
- native JNI adapters for Rockchip, Intel, AMD, and Qualcomm;
- local model download and management.

The project is currently experimental. It should not be considered production-ready:
some backends are complete only at the interface level, there are no automated tests yet,
and the APIs do not have authentication.

## Read Before Modifying Code

Technical documentation is divided by responsibility:

- [Architecture and Flows](docs/architecture.md): components, global state,
  model lifecycle, concurrency, and boundaries between Java and C++.
- [Development Guide](docs/development.md): prerequisites, build, startup,
  modifying drivers/models/APIs, and JNI checklist.
- [Configuration and Operations](docs/configuration.md): Spring properties,
  Rocket variables, persistent directories, security, and operational limits.
- [Control Panel API](docs/control-api.md): administrative endpoints,
  payloads, and side effects.
- [Ollama and OpenAI Compatibility](docs/ollama-api.md): inference endpoints,
  streaming, and client behavior.

## Real Backend Status

| Backend | Real Implementation | Standard Build `tools/build-all.sh` |
| --- | --- | --- |
| Rockchip RK3588/RK3588S | Yes, `llama.cpp` + `ggml-rocket` in `workers/rocket` | Included when explicitly targeted |
| Intel OpenVINO GenAI | Present in `workers/openvino`, requires external SDK | Not included; generic stub is packaged |
| AMD Ryzen AI | Present in `workers/ryzenai`, requires external SDK | Not included; generic stub is packaged |
| Qualcomm QAIRT/Genie | Direct JNI binding to `libGenie.so` and QNN libraries bundled with the model | Included on Radxa ARM64 |

Most files under `native/` are compatibility adapters that simulate probing,
loading, and generation. The Qualcomm adapter is the exception: it loads the
model's native Genie/QNN libraries. For
OpenVINO and Ryzen AI, the real implementations are located under `workers/`.
For Qualcomm, the JNI adapter loads `libGenie.so` directly from each QAIRT model
directory and uses the native Genie dialog callback for token streaming.

This distinction is important: a properly loaded `.so` library is not,
by itself, proof of hardware acceleration.

The Rocket probe is also provisional: the native method always returns
available, and the Java driver combines it with the device node via an OR operation.
Real confirmation only comes when the Rocket plugin initializes the device and model loading succeeds.

## Quick Start

Minimum prerequisites to build the control plane:

- Linux;
- JDK 17;
- Maven 3.9 or compatible.

Verify the Java component:

```bash
mvn test
```

If Maven is not installed globally and the local tool is already present:

```bash
./.build-tools/apache-maven-3.9.9/bin/mvn test
```

Start for control panel and API development:

```bash
mvn spring-boot:run
```

The control panel will be available at `http://localhost:11434`. Without a supported NPU
and a real native library, you can inspect the interface, but real inference cannot be executed.

### Platform Build

The full build also requires Git, CMake, a C/C++ compiler, JNI headers, and network access:

```bash
tools/build-all.sh
java -jar target/npu-hub-1.0.0-SNAPSHOT.jar
```

On Radxa ARM64 boards, the script builds the Qualcomm/QAIRT adapter and does
not download or compile Rocket. Orange Pi ARM64 boards continue to use Rocket;
the selection is based on the device-tree model. `NPU_HUB_BOARD` can override
that model detection when needed. On x86, it builds the host-oriented generic
adapters. The Rocket runtime is also built when all platforms are requested
explicitly with `NPU_HUB_BUILD_ALL_PLATFORMS=1`.

When Rocket is selected, the script:

1. updates `llama.cpp` to `origin/master`;
2. applies the Rocket patch;
3. clones or reuses `ggml-rocket` and `rocket-userspace`;
4. compiles the Rocket runtime and generic adapters;
5. copies libraries to `src/main/resources/native`;
6. runs `mvn clean package`.

The build is not fully reproducible because it tracks the current branch of
`llama.cpp`. If the patch can no longer be applied, the script halts instead of producing a runtime with an inconsistent ABI.

## Operational Workflow

The intended sequence is:

1. open the control panel;
2. verify that the backend is marked as available;
3. download or place a compatible model;
4. explicitly load the model;
5. start the inference API from the control panel;
6. use the integrated chat or an Ollama/OpenAI client;
7. stop the API and unload the model when necessary.

The process maintains only one loaded model at a time. Downloading a model
to disk, loading it into memory, and enabling inference endpoints are three
distinct operations.

## Repository Structure

```text
src/main/java/com/npuhub/
  core/driver/       driver contract, registry, and Java implementations
  core/model/        shared records and enums
  jni/               JNI signatures and native library loading
  service/           models, inference, setup, metrics, and API status
  web/               filters, error handling, and HTTP controllers

src/main/resources/
  application.yml    default configuration
  templates/         Thymeleaf page
  static/            JavaScript, CSS, and frontend vendors
  native/            generated output for packaged libraries

native/               generic C++ adapters/stubs
workers/rocket/       real Rockchip runtime
workers/openvino/     real OpenVINO GenAI adapter
workers/ryzenai/      real ONNX Runtime GenAI adapter
tools/                build, cleanup, and model download scripts
docs/                 technical documentation
```

The frontend does not use Node, Vite, or a bundler: it is Thymeleaf HTML with static JavaScript and CSS. Modifications under `src/main/resources/static` are served directly by Spring Boot.

## Important Constraints

- There is no CPU/GPU fallback at the Java selection level. However, Rocket uses a
  hybrid mode: efficient prompt prefill on NPU and short decode on CPU.
- Control controllers are exposed without authentication and with permissive CORS.
  Do not publish the port on an untrusted network.
- Settings saved from the control panel exist only in memory and currently do not reconfigure already started Spring components.
- `mvn test` passes, but there are no source files under `src/test`: it does not cover
  inference, hardware, streaming, or API compatibility.
- The model catalog is hardcoded in `ModelManagementService`.
- The `LICENSE` file is not present in the current state of the project: clarify
  licensing before distributing binaries or source code.

## Cleanup

To remove only generated outputs:

```bash
tools/cleanup.sh --builds
```

To also include locally downloaded Maven and the `llama.cpp` checkout:

```bash
tools/cleanup.sh --all
```

The script displays targets and prompts for confirmation. It does not remove models,
configuration, `ggml-rocket`, or `rocket-userspace`.
