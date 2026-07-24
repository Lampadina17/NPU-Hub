# API del control panel

Base path:

```text
/api/v1/control
```

Queste API sono amministrative, non hanno autenticazione e accettano richieste
cross-origin. Possono scaricare o cancellare modelli, compilare codice e
installare driver. Usarle solo su una rete fidata.

## Hardware e diagnostica

| Metodo e path | Risposta |
| --- | --- |
| `GET /hardware` | Lista `HardwareInfo` di tutti i backend |
| `GET /diagnostics` | OS, JVM, CPU, RAM, swap, NPU e metriche inferenza |
| `GET /logs?afterId=0` | Log in-memory successivi all'ID |

Esempio:

```bash
curl http://localhost:8080/api/v1/control/hardware
```

`/diagnostics` campiona contatori cumulativi Linux: CPU e NPU hanno significato
tra due letture successive, non come misura istantanea assoluta.

## Stato API

### `GET /api/status`

```json
{
  "enabled": false,
  "modelLoaded": true
}
```

### `POST /api/start`

Abilita l'inferenza solo se un modello è caricato.

```bash
curl -X POST http://localhost:8080/api/v1/control/api/start
```

### `POST /api/stop`

Disabilita le route coperte dal gate. Non scarica il modello.

```bash
curl -X POST http://localhost:8080/api/v1/control/api/stop
```

Lo stato non è persistente.

## Modelli

### `GET /models`

Parametri:

- `all=false` per il backend auto-selezionato;
- `all=true` per tutto il catalogo.

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

Per `unsloth/*` la quantizzazione viene validata. Per gli altri repository il
campo viene ignorato e viene scaricato il repository completo. La chiamata
avvia un task asincrono.

### `GET /models/download/status`

```bash
curl --get http://localhost:8080/api/v1/control/models/download/status \
  --data-urlencode 'modelId=unsloth/Phi-4-mini-instruct-GGUF' \
  --data-urlencode 'quantization=Q4_K_M'
```

Risposta:

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

Il modello deve essere già presente. Il processo accetta un solo modello
caricato; questo endpoint non sostituisce automaticamente quello attivo.

Il payload non espone il context window: il controller usa 4096. Per caricare
un contesto diverso va esteso il contratto e chiamata la overload a quattro
argomenti di `ModelManagementService.loadModel`.

### `POST /models/unload`

Body non richiesto. Libera il modello corrente, ma non modifica il flag API.

### `POST /models/delete`

Intero modello:

```json
{
  "modelId": "OpenVINO/Phi-3.5-mini-instruct-int4-cw-ov"
}
```

Singola variante Rockchip:

```json
{
  "modelId": "unsloth/Phi-4-mini-instruct-GGUF",
  "quantization": "Q4_K_M"
}
```

La cancellazione è permanente. Per una variante Rockchip il modello deve
essere prima scaricato. Per una directory completa, il servizio scarica
automaticamente un eventuale modello attivo corrispondente.

## Impostazioni

### `GET /settings`

Restituisce impostazioni in memoria più:

- `configuredBackend`;
- `recommendedBackend`;
- `preferredBackend` effettivo;
- `backendSelectionMode`;
- `recommendationAvailable`.

### `POST /settings`

Accetta un oggetto JSON arbitrario e fa merge nella mappa in memoria:

```json
{
  "preferredBackend": "ROCKCHIP",
  "modelsDirectory": "models",
  "ollamaPort": 8080,
  "defaultContextWindow": 4096
}
```

Non c'è validazione dello schema. Porta, directory e contesto non
riconfigurano i bean Spring correnti. Vedere
[Configurazione e operatività](configuration.md).

## Setup

### `POST /setup/intel-driver`

Avvia l'installazione Intel Ubuntu 24.04:

- scarica driver e Level Zero;
- estrae pacchetti;
- invoca `pkexec` e `apt-get`;
- scrive una regola udev;
- aggiunge l'utente al gruppo `render`.

Task ID: `intel-driver`.

### `POST /setup/build-worker`

```json
{
  "workerType": "rocket"
}
```

Il valore viene usato per risolvere `workers/<workerType>`. Valori previsti:
`rocket`, `openvino`, `ryzenai`.

Per Rocket il task aggiorna `llama.cpp`, applica la patch e compila anche
`ggml-rocket`. Per gli altri target usa il relativo CMake e richiede lo SDK
vendor già installato.

Task ID: `build-<workerType>`.

### `POST /setup/modelscope`

Esegue:

```text
python3 -m pip install modelscope
```

Task ID: `modelscope-setup`.

Il downloader applicativo attuale usa direttamente HTTP e non richiede
ModelScope CLI; questo setup resta disponibile per compatibilità operativa.

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

Stato e progresso sono process-local. Un riavvio li azzera. Non è disponibile
un endpoint di cancellazione.

## Errori

Gli endpoint control restituiscono generalmente `400` per input o stato non
valido e un oggetto con `error`. Non condividono un envelope rigorosamente
uniforme.

Esempio:

```json
{
  "success": false,
  "modelId": "example/model",
  "error": "Model is already loaded"
}
```

Per i contratti Ollama/OpenAI vedere [Compatibilità API](ollama-api.md).
