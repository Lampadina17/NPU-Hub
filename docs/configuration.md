# Configurazione e operatività

## Precedenza

La configurazione effettiva del server è quella Spring Boot:

1. argomenti `--chiave=valore`;
2. variabili d'ambiente;
3. `src/main/resources/application.yml`.

Esempio:

```bash
java -jar target/npu-hub-1.0.0-SNAPSHOT.jar \
  --server.port=11434 \
  --npu.models.directory=/srv/npu-hub/models
```

Equivalente con variabili:

```bash
SERVER_PORT=11434 \
NPU_MODELS_DIRECTORY=/srv/npu-hub/models \
java -jar target/npu-hub-1.0.0-SNAPSHOT.jar
```

Le impostazioni modificate nel pannello passano invece da `SettingsService`:
sono in memoria, si perdono al riavvio e non fanno rebind delle proprietà
Spring.

## Proprietà Spring

| Proprietà | Variabile tipica | Default | Effetto |
| --- | --- | --- | --- |
| `server.port` | `SERVER_PORT` | `8080` | Porta HTTP |
| `spring.mvc.async.request-timeout` | `SPRING_MVC_ASYNC_REQUEST_TIMEOUT` | `600000` ms | Timeout MVC async |
| `npu.models.directory` | `NPU_MODELS_DIRECTORY` | `models` | Root dei modelli |
| `npu.ollama.aliases-file` | `NPU_OLLAMA_ALIASES_FILE` | `.npuhub/ollama-models.json` | Alias Ollama persistenti |
| `npu.ollama.compatibility-version` | `NPU_OLLAMA_COMPATIBILITY_VERSION` | `0.20.0-npuhub.17` | Valore di `/api/version` |
| `npu.ollama.default-context` | `NPU_OLLAMA_DEFAULT_CONTEXT` | `4096` | `num_ctx` quando assente |
| `npu.ollama.minimum-loaded-context` | `NPU_OLLAMA_MINIMUM_LOADED_CONTEXT` | `4096` | Minimo contesto Rockchip caricato |
| `npu.ollama.default-max-tokens` | `NPU_OLLAMA_DEFAULT_MAX_TOKENS` | `512` | `num_predict` quando assente |
| `npu.ollama.default-keep-alive` | `NPU_OLLAMA_DEFAULT_KEEP_ALIVE` | `-1` | Valore analizzato dalla facade |
| `npu.ollama.keep-alive-scan-ms` | `NPU_OLLAMA_KEEP_ALIVE_SCAN_MS` | `1000` | Frequenza controllo scadenza |

Nota: il lifecycle esplicito rende oggi `keep_alive` non operativo nel percorso
Ollama/OpenAI. `OllamaInferenceFacade.finish()` non aggiorna la scadenza e il
load dal pannello imposta residenza infinita.

Logging di default:

```yaml
logging:
  level:
    root: WARN
    com.npuhub: WARN
```

Per debug:

```bash
java -jar target/npu-hub-1.0.0-SNAPSHOT.jar \
  --logging.level.com.npuhub=DEBUG
```

## Impostazioni del pannello

`SettingsService` espone:

| Chiave | Default | Stato corrente |
| --- | --- | --- |
| `preferredBackend` | `auto` | Usata dal frontend per la selezione e inviata al load |
| `modelsDirectory` | `models` | Solo UI; non cambia `npu.models.directory` |
| `ollamaPort` | `8080` | Solo UI; non cambia `server.port` |
| `maxConcurrentInferences` | `2` | Non collegata al pool di inferenza |
| `defaultContextWindow` | `4096` | Solo UI; il load control usa ancora 4096 |

Per una modifica reale usare proprietà Spring e riavviare il processo. Prima
di rendere persistenti queste impostazioni, va definita una sorgente unica per
evitare divergenza tra UI e runtime.

## Variabili di build

| Variabile | Default | Usata da |
| --- | --- | --- |
| `NPU_HUB_BUILD_JOBS` | `2` | Parallelismo di `tools/build-all.sh` |
| `NPU_HUB_MAVEN_VERSION` | `3.9.9` | Maven locale scaricato dallo script |
| `LLAMA_DIR` | `.rocket-runtime/llama.cpp` | Checkout `llama.cpp` esterno |
| `RYZEN_AI_INSTALLATION_PATH` | nessuno | Discovery SDK Ryzen AI |

`tools/build-all.sh` può scaricare Maven, clonare repository e aggiornare
`llama.cpp`; non è una build offline.

## Variabili runtime Rocket

| Variabile | Default | Valori / significato |
| --- | --- | --- |
| `NPU_HUB_ROCKET_MODE` | `hybrid` | `hybrid` o `strict` |
| `NPU_HUB_ROCKET_BATCH` | `2048` | `n_batch`, limitato dal contesto |
| `NPU_HUB_ROCKET_UBATCH` | `512` | `n_ubatch`, limitato da batch |
| `NPU_HUB_ROCKET_KV_TYPE` | `q8_0` | `q8_0`, `f16`, `q4_0` |
| `NPU_HUB_ROCKET_THREADS` | min(CPU, 4) | Thread decode CPU |
| `NPU_HUB_ROCKET_THREADS_BATCH` | min(CPU, 5) | Thread batch/prefill |
| `NPU_HUB_ROCKET_PROMPT_CACHE` | abilitata | `0` la disabilita |
| `NPU_HUB_ROCKET_TRUNCATE_PROMPT` | abilitata | `0` trasforma overflow in errore |
| `NPU_HUB_GGML_BACKEND_DIR` | auto | Directory dei plugin GGML |
| `NPU_HUB_ROCKET_PLUGIN` | auto | Path esplicito a `libggml-rocket.so` |
| `GGML_BACKEND_PATH` | auto | Candidate path compatibile GGML |
| `ROCKET_STRICT` | disabilitata | Compatibilità; usata se `MODE` manca |

Esempio diagnostico strict:

```bash
NPU_HUB_ROCKET_MODE=strict \
java -jar target/npu-hub-1.0.0-SNAPSHOT.jar
```

La modalità ibrida è il default intenzionale: prefill grandi e convenienti
possono usare la NPU, mentre decode breve/token-by-token può usare CPU. Quindi
“NPU-only” nella selezione Java non significa che ogni operazione GGML venga
forzata sulla NPU.

Con macchine a memoria limitata, cambiare insieme `num_ctx`, batch e tipo KV
può aumentare drasticamente RAM e swap. La KV cache ripiega automaticamente su
`f16` se il tipo quantizzato non è supportato, aumentando il consumo.

## Directory e dati

| Path | Persistenza | Contenuto |
| --- | --- | --- |
| `models/` | Da preservare | Pesi e directory modello |
| `.npuhub/ollama-models.json` | Da preservare | Alias Ollama |
| `target/` | Generato | JAR e classi Maven |
| `native/build/` | Generato | Adapter JNI |
| `workers/*/build/` | Generato | Runtime worker |
| `src/main/resources/native/` | Generato/staging | Librerie incluse nel JAR |
| `.rocket-runtime/llama.cpp` | Riscaricabile | Checkout upstream |
| `.rocket-runtime/ggml-rocket` | Sorgente esterna locale | Plugin Rocket |
| `.rocket-runtime/rocket-userspace` | Sorgente esterna locale | Userspace Rocket |
| `.build-tools/` | Riscaricabile | Maven locale |
| `/tmp/npuhub-rocket-runtime-*` | Temporaneo | Librerie estratte dal JAR |

Usare path assoluti in produzione. Con path relativi, modelli e alias dipendono
dalla working directory da cui viene lanciato il JAR.

## Download modelli

`tools/download_model.py` sceglie la sorgente così:

- ID `OpenVINO/*` e `unsloth/*`: Hugging Face;
- altri ID: ModelScope.

Per Rockchip filtra i file GGUF in base alla quantizzazione. Ogni download
scrive prima `<file>.part` e poi fa rename, ma non supporta resume. Non ci sono:

- checksum;
- autenticazione per repository privati;
- retry nel downloader Python;
- cancellazione;
- quota disco;
- validazione del formato oltre a filename e dimensione.

La soglia di 50 MiB evita di considerare validi piccoli pointer LFS, ma non
garantisce integrità.

## Sicurezza

Il server non implementa autenticazione. Inoltre:

- `ControlPanelApiController` abilita CORS da qualsiasi origine;
- gli endpoint di setup possono scaricare, compilare e installare software;
- l'installer Intel usa `pkexec`, `apt-get`, modifica regole udev e aggiunge
  l'utente al gruppo `render`;
- gli endpoint modello possono scaricare e cancellare file;
- Ollama e OpenAI accettano richieste senza API key;
- Spring Boot ascolta normalmente su tutte le interfacce.

Uso consigliato:

1. bind su loopback o VLAN fidata;
2. firewall sulla porta;
3. reverse proxy con autenticazione se esposto;
4. non esporre gli endpoint `/api/v1/control/setup/*`;
5. eseguire con un utente senza privilegi non necessari;
6. separare modelli e sorgenti da dati sensibili.

Il progetto non deve essere esposto direttamente a Internet nello stato
attuale.

## Operazioni e side effect

| Operazione | Effetto |
| --- | --- |
| Download modello | Rete e scrittura sotto models |
| Delete modello | Cancellazione ricorsiva o della variante GGUF |
| Load | Allocazione runtime, modello e KV cache |
| Unload | Libera stato nativo, non ferma l'API |
| Start API | Cambia flag process-local |
| Stop API | Blocca quattro route, non libera il modello |
| Build worker | Aggiorna/clona sorgenti, applica patch e compila |
| Setup ModelScope | Esegue `python3 -m pip install modelscope` |
| Setup Intel | Download, `pkexec`, pacchetti, udev, gruppo utente |

Progressi, log e flag si perdono a ogni riavvio.

## Diagnostica

Endpoint utili:

```text
GET /api/v1/control/hardware
GET /api/v1/control/diagnostics
GET /api/v1/control/logs?afterId=0
GET /api/v1/control/setup/status?taskId=<id>
GET /api/ps
```

Controlli di sistema:

```bash
ls -l /dev/accel/accel0 /dev/dri/renderD128 /dev/amdxdna /dev/kgsl-3d0
ldd path/to/libnpu_backend_jni.so
file path/to/libnpu_backend_jni.so
```

Non tutti i device esistono su tutte le piattaforme; un `No such file` è
atteso per i backend non presenti.

### Il backend non è disponibile

Verificare:

1. device node e permessi;
2. architettura host;
3. libreria JNI caricata;
4. dipendenze dinamiche;
5. probe dello SDK vendor;
6. log Spring e terminale del pannello.

### La libreria è caricata ma la generazione è simulata

Controllare il path della `.so`. Se proviene da `native/build` o dalla risorsa
generica, può essere uno stub. Per OpenVINO/Ryzen AI deve provenire dalla build
del relativo `workers/`; per Rockchip dal bundle Rocket.

### Il modello risulta non scaricato

Verificare:

- directory calcolata da `npu.models.directory`;
- nome repository/folder;
- variante GGUF richiesta;
- dimensione totale oltre 50 MiB;
- eventuali `.part`;
- permessi di lettura.

### Richiesta `503`

Caricare un modello e chiamare:

```bash
curl -X POST http://localhost:8080/api/v1/control/api/start
```

Il flag API riparte sempre disabilitato dopo un restart.

### Contesto troppo grande

Una richiesta non può aumentare il contesto del modello già caricato. Occorre
scaricarlo e ricaricarlo con un contesto maggiore tramite un percorso che
esponga `requestedContextWindow`; l'endpoint control attuale usa 4096 fisso.
Ridurre `num_ctx` o estendere esplicitamente il contratto di load.
