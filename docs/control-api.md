# Control Panel API

Base path:

```text
/api/v1/control
```

These administrative APIs operate without authentication and accept cross-origin requests. They can download or delete models, compile code, and install drivers. Only use them on a trusted network.

## Hardware and Diagnostics

| Method and path | Response |
| --- | --- |
| `GET /hardware` | List of `HardwareInfo` for all backends |
| `GET /diagnostics` | OS, JVM, CPU, RAM, swap, NPU, and inference metrics |
| `GET /logs?afterId=0` | In-memory logs following the given ID |

Example:

```bash
curl http://localhost:8080/api/v1/control/hardware
```

`/diagnostics` samples cumulative Linux counters: CPU and NPU values are meaningful between two consecutive readings, not as an absolute instantaneous measurement.

## API Status

### `GET /api/status`

```json
{
  "enabled": false,
  "modelLoaded": true
}
```

### `POST /api/start`

Enables inference only if a model is loaded.

```bash
curl -X POST http://localhost:8080/api/v1/control/api/start
```

### `POST /api/stop`

Disables routes covered by the gate. Does not unload the model.

```bash
curl -X POST http://localhost:8080/api/v1/control/api/stop
```

The status is not persistent.

## Models

### `GET /models`

Parameters:

- `all=false` for the auto-selected backend;
- `all=true` for the entire catalog.

```bash
curl 'http://localhost:8080/api/v1/control/models?all=true'
```

### `POST /models/download`

```json
{
  "modelId": "unsloth/Phi-4-mini-instruct-GGUF",
  "quantization": "Q4_K_M"
}
```

For `unsloth/*`, the quantization is validated. For other repositories, this field is ignored and the full repository is downloaded. The call starts an asynchronous task.

### `GET /models/download/status`

```bash
curl --get http://localhost:8080/api/v1/control/models/download/status \
  --data-urlencode 'modelId=unsloth/Phi-4-mini-instruct-GGUF' \
  --data-urlencode 'quantization=Q4_K_M'
```

Response:

```json
{
  "modelId": "unsloth/Phi-4-mini-instruct-GGUF",
  "quantization": "Q4_K_M",
  "status": "DOWNLOADING",
  "progress": 42.0,
  "isDownloaded": false
}
```

### `POST /models/load`

```json
{
  "modelId": "unsloth/Phi-4-mini-instruct-GGUF",
  "preferredBackend": "ROCKCHIP",
  "quantization": "Q4_K_M"
}
```

The model must already be present on disk. The process supports only a single loaded model at a time; this endpoint does not automatically replace the currently active one.

The payload does not expose the context window size: the controller defaults to 4096. To load a different context size, the API contract must be extended and the four-argument overload of `ModelManagementService.loadModel` must be invoked.

### `POST /models/unload`

No body required. Unloads the current model, but does not modify the API enabled flag.

### `POST /models/delete`

Entire model:

```json
{
  "modelId": "OpenVINO/Phi-3.5-mini-instruct-int4-cw-ov"
}
```

Single Rockchip variant:

```json
{
  "modelId": "unsloth/Phi-4-mini-instruct-GGUF",
  "quantization": "Q4_K_M"
}
```

Deletion is permanent. For a Rockchip variant, the model must first be unloaded. For an entire directory, the service automatically unloads any corresponding active model.

## Settings

### `GET /settings`

Returns in-memory settings plus:

- `configuredBackend`;
- `recommendedBackend`;
- effective `preferredBackend`;
- `backendSelectionMode`;
- `recommendationAvailable`.

### `POST /settings`

Accepts an arbitrary JSON object and merges it into the in-memory map:

```json
{
  "preferredBackend": "ROCKCHIP",
  "modelsDirectory": "models",
  "ollamaPort": 8080,
  "defaultContextWindow": 4096
}
```

There is no schema validation. Port, directory, and context settings do not reconfigure the active Spring beans. See [Configuration and operation](configuration.md).

## Setup

### `POST /setup/intel-driver`

Starts the Intel Ubuntu 24.04 installation:

- downloads driver and Level Zero;
- extracts packages;
- invokes `pkexec` and `apt-get`;
- writes a udev rule;
- adds the user to the `render` group.

Task ID: `intel-driver`.

### `POST /setup/build-worker`

```json
{
  "workerType": "rocket"
}
```

The value is used to resolve `workers/<workerType>`. Expected values: `rocket`, `openvino`, `ryzenai`.

For Rocket, the task updates `llama.cpp`, applies the patch, and compiles `ggml-rocket` as well. For other targets, it invokes the respective CMake build and requires the vendor SDK to be already installed.

Task ID: `build-<workerType>`.

### `POST /setup/modelscope`

Creates or reuses the project-local `.modelscope-venv` virtual environment and
installs ModelScope into it:

```text
python3 -m venv .modelscope-venv
.modelscope-venv/bin/python -m pip install --upgrade modelscope
```

Task ID: `modelscope-setup`.

The current application downloader uses HTTP directly and does not require the ModelScope CLI; this setup option remains available for operational compatibility.

### `GET /setup/status`

```bash
curl --get http://localhost:8080/api/v1/control/setup/status \
  --data-urlencode 'taskId=build-rocket'
```

```json
{
  "taskId": "build-rocket",
  "status": "RUNNING",
  "progress": 70.0
}
```

Status and progress are process-local. A restart resets them. No cancellation endpoint is available.

## Errors

Control endpoints generally return `400` for invalid input or state along with an object containing `error`. They do not share a strictly uniform error envelope.

Example:

```json
{
  "success": false,
  "modelId": "example/model",
  "error": "Model is already loaded"
}
```

For Ollama/OpenAI API contracts, see [API Compatibility](ollama-api.md).
