# Compatibilità API Ollama e OpenAI

NPU Hub exposes its control panel and Ollama-compatible API from the same
Spring Boot process. The default base URL is:

```text
http://<device-address>:8080
```

Set `SERVER_PORT=11434` when a client requires Ollama's conventional port.
Do not append `/api` or `/v1` to the base URL configured as an Ollama
connection.

## Authentication

Authentication is disabled. All Ollama and OpenAI-compatible routes accept
requests without an API key and without an `Authorization: Bearer ...` header.

For Open WebUI, add NPU Hub as an **Ollama** connection under
**Admin Settings > Connections > Ollama** and enter only:

```text
http://<device-address>:8080
```

Do not configure it as an OpenAI connection: that connection type asks for an
API key even when the target server does not require one. If Open WebUI runs in
Docker on the same device, `localhost` and `0.0.0.0` refer to the Open WebUI
container itself. With Docker's default bridge, use the host gateway (for
example `http://172.17.0.1:8080`) or another host address reachable from
the container.

## Native Ollama API

Implemented routes:

- `GET /` and `GET /api/version`
- `GET /api/tags`
- `GET /api/ps`
- `POST /api/show`
- `POST /api/generate`
- `POST /api/chat`
- `POST /api/create`
- `POST /api/copy`
- `POST /api/pull`
- `DELETE /api/delete`
- `POST /api/embed` and legacy `POST /api/embeddings`
- `POST /api/push`
- `HEAD|POST /api/blobs/{digest}`

Streaming routes use newline-delimited JSON with the
`application/x-ndjson` content type. `stream: false` returns one JSON object.
Errors use Ollama's `{"error":"..."}` envelope and an appropriate HTTP status.

`tags` advertises only model variants that are present on disk. A selected
GGUF variant can be addressed explicitly as
`repository/model:Q4_K_M`; an untagged model resolves to its recommended
downloaded quantization.

`create` and `copy` create lightweight, persistent aliases in
`.npuhub/ollama-models.json`. `pull` downloads models from the NPU Hub catalog
and defaults Rockchip models to the recommended quantization.

The routes for embeddings, registry push, and blob upload return a compliant
`501` error while the active NPU runtime lacks those capabilities. Image input
and image generation are rejected for the same reason; no fabricated result or
CPU-only substitute is returned.

## OpenAI compatibility

Implemented routes:

- `GET /v1/models`
- `GET /v1/models/{model}`
- `POST /v1/chat/completions`
- `POST /v1/completions`
- `POST /v1/responses`
- `POST /v1/embeddings`
- `POST /v1/images/generations`

Chat and completion streaming uses server-sent events. An API key may be
supplied by SDKs that require one syntactically, but NPU Hub ignores it.
The header is not required:

Example:

```bash
curl http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "unsloth/Phi-4-mini-instruct-GGUF",
    "messages": [{"role": "user", "content": "Ciao"}],
    "max_tokens": 64
  }'
```

## Runtime behaviour

The requested model is **not** loaded automatically. Download and load it from
the control panel, then start the inference API before sending generation
requests. A request for any model other than the one already loaded fails
instead of replacing the active model.

`num_ctx`, `num_predict`,
`temperature`, `top_k`, `top_p`, `min_p`, `seed`, `repeat_last_n`,
`repeat_penalty`, `frequency_penalty`, `presence_penalty`, `stop`, and
`keep_alive` are accepted by the API; supported sampling values are forwarded
to the Rocket llama.cpp runtime.

`/api/show` reports the model's real GGUF context metadata.
`/api/ps` reports the context size actually allocated for the loaded runtime.
The default and minimum loaded runtime context are 4096, avoiding allocation of
the full 131072-token KV cache. Requests with a smaller context reuse the
already loaded 4096-token runtime instead of unloading and rebuilding it. The
current control endpoint loads with context 4096 and does not expose a larger
context value. `keep_alive` is parsed for compatibility but intentionally does
not control residency. The model remains loaded until it is explicitly
unloaded or the service stops.

The Rocket runtime keeps a logical `n_batch=2048` while using
`n_ubatch=512` by default. This preserves large prompt support while reducing
the reusable compute allocation. The K/V cache defaults to `q8_0`, cutting its
memory footprint roughly in half versus `f16` without shrinking the requested
context. If the selected llama.cpp build cannot create a quantized cache, NPU
Hub automatically retries with `f16`. Prompts larger than one micro-batch are
decoded in safe chunks.
Before rendering a chat prompt, NPU Hub removes the oldest complete
user/assistant turns when the history would exceed the requested context.
System messages and the newest turn are retained. If one indivisible message,
tool schema, or system prompt is still too large, the native runtime performs
an exact token-level head/tail compaction instead of terminating the JVM.
Set `NPU_HUB_ROCKET_TRUNCATE_PROMPT=0` only when a controlled out-of-context
error is preferable to automatic compaction.

The native runtime retains the decoded KV prefix between requests. Consecutive
chat turns therefore decode only the newly appended assistant/user tokens
instead of evaluating the full Open WebUI history again. The cache is safely
discarded when the prompt has no reusable prefix, the model changes, or a
decode fails. Set `NPU_HUB_ROCKET_PROMPT_CACHE=0` for diagnostics only.

Rocket defaults to optimized hybrid routing: profitable large prefill batches
run on the NPU, while short batches and token-by-token decode use the CPU.
Forcing every supported operation through the NPU is substantially slower for
interactive generation and is intended only for diagnostics:

```bash
NPU_HUB_ROCKET_MODE=strict java -jar target/npu-hub-1.0.0-SNAPSHOT.jar
```

The runtime knobs are:

- `NPU_HUB_ROCKET_BATCH` (default `2048`)
- `NPU_HUB_ROCKET_UBATCH` (default `512`)
- `NPU_HUB_ROCKET_KV_TYPE=q8_0|f16|q4_0` (default `q8_0`)
- `NPU_HUB_ROCKET_THREADS` (default `4`)
- `NPU_HUB_ROCKET_THREADS_BATCH` (default `5`)
- `NPU_HUB_ROCKET_MODE=hybrid|strict` (default `hybrid`)

See [Configurazione e operatività](configuration.md) for the complete list,
path rules and security notes. See [API del control panel](control-api.md) for
download, load and start/stop requests.

On an 8 GB Orange Pi 5, do not enable `ROCKET_QUANT_RESIDENT=auto` for
Phi-4-mini Q4: the dequantized FP16 copy needs roughly another 7.7 GB and will
cause swap pressure. A Rocket kernel/module build that runs the NPU at 600 MHz
can improve large-prefill throughput further, but installing it changes the
kernel module and requires a reboot; it is not enabled by NPU Hub.
