# Guida allo sviluppo

## Prerequisiti

Per lavorare sul solo control plane Java:

- Linux;
- JDK 17, incluso `javac`;
- Maven 3.9 o compatibile.

Per gli adapter nativi:

- CMake 3.16 o successivo;
- compilatore C/C++ con supporto C++17;
- header JNI del JDK;
- Git;
- SDK e driver del backend target.

Ryzen AI richiede CMake 3.20 e C++20. Gli script di download e setup richiedono
anche Python 3, `curl`, `tar` e accesso alla rete.

## Verifica Java

```bash
mvn test
```

Il repository può contenere Maven in `.build-tools`:

```bash
./.build-tools/apache-maven-3.9.9/bin/mvn test
```

Oggi non esistono test sotto `src/test`. Il risultato `BUILD SUCCESS` prova
che le 41 classi Java compilano e che le risorse vengono copiate, non che:

- Spring avvii correttamente con una specifica `.so`;
- JNI e Java abbiano ABI coerente;
- un device NPU sia raggiungibile;
- streaming e payload siano compatibili con i client;
- il modello generi output corretto.

## Avvio in sviluppo

```bash
mvn spring-boot:run
```

Oppure:

```bash
mvn package
java -jar target/npu-hub-1.0.0-SNAPSHOT.jar
```

Per cambiare porta o directory modelli:

```bash
SERVER_PORT=11434 \
NPU_MODELS_DIRECTORY=/srv/npu-hub/models \
java -jar target/npu-hub-1.0.0-SNAPSHOT.jar
```

Spring Boot serve frontend e API dallo stesso processo. Non serve avviare un
server Node.

## Tipi di build nativa

### Adapter generici

```bash
cmake -S native -B native/build -DCMAKE_BUILD_TYPE=Release
cmake --build native/build --parallel
```

Questa build crea stub utili per verificare caricamento e firme semplici. Non
va usata per concludere che l'inferenza sia accelerata. In particolare lo stub
Rockchip non implementa tutte le firme correnti e viene sostituito dalla build
completa.

### Rockchip Rocket

Il percorso supportato dalla build completa è:

```bash
NPU_HUB_BUILD_JOBS=2 tools/build-all.sh
```

Lo script usa o crea:

```text
.rocket-runtime/llama.cpp
.rocket-runtime/ggml-rocket
.rocket-runtime/rocket-userspace
workers/rocket/build
native/build
src/main/resources/native
target
```

`llama.cpp` viene aggiornato a `origin/master` a ogni esecuzione. La patch
`workers/rocket/patches/llama-rocket-strict.patch` deve applicarsi al checkout.
Prima di aggiornare manualmente `llama.cpp`, verificare:

```bash
git -C .rocket-runtime/llama.cpp apply --check \
  "$(pwd)/workers/rocket/patches/llama-rocket-strict.patch"
```

Se la patch è già applicata, il controllo corretto è:

```bash
git -C .rocket-runtime/llama.cpp apply --reverse --check \
  "$(pwd)/workers/rocket/patches/llama-rocket-strict.patch"
```

Le librerie `llama`, GGML, CPU, Rocket e JNI impacchettate devono provenire
dallo stesso albero e dalla stessa build. Mescolare ABI diverse produce errori
di simboli o crash a runtime.

### OpenVINO reale

L'implementazione reale è `workers/openvino/src/openvino_jni.cpp`.
Occorre una distribuzione che esponga i package CMake `OpenVINOGenAI` e
`OpenVINO`:

```bash
cmake -S workers/openvino -B workers/openvino/build \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_PREFIX_PATH=/percorso/openvino
cmake --build workers/openvino/build --parallel
```

Il post-build copia `libnpu_openvino_jni.so` in `native/build`. Per inserirla
nel JAR, va copiata intenzionalmente in
`src/main/resources/native/libnpu_openvino_jni.so` prima di `mvn package`.
Verificare anche che le dipendenze OpenVINO siano raggiungibili dal loader
dinamico a runtime.

`tools/build-all.sh` ricompila gli adapter generici e può sovrascrivere questo
file con lo stub. Non eseguire i due flussi alla cieca.

### Ryzen AI reale

L'implementazione reale è `workers/ryzenai/src/ryzenai_jni.cpp`:

```bash
RYZEN_AI_INSTALLATION_PATH=/percorso/ryzen-ai \
cmake -S workers/ryzenai -B workers/ryzenai/build \
  -DCMAKE_BUILD_TYPE=Release
cmake --build workers/ryzenai/build --parallel
```

La configurazione cerca header e libreria ONNX Runtime GenAI. È possibile
passare direttamente:

```text
ONNXRUNTIME_GENAI_INCLUDE_DIR
ONNXRUNTIME_GENAI_LIBRARY
```

Anche qui il post-build copia in `native/build`; packaging e dipendenze runtime
restano responsabilità della build di distribuzione.

### Qualcomm

Non esiste ancora `workers/qualcomm`. `native/qualcomm` è uno stub. Per
aggiungere il backend reale servono almeno:

1. integrazione QAIRT/Genie C++;
2. probe reale dello SDK;
3. load/unload e generazione streaming;
4. gestione delle librerie dipendenti;
5. target CMake e staging nel JAR;
6. test su hardware.

## Regole per modificare JNI

Ogni modifica al contratto nativo deve essere atomica:

1. aggiornare la classe bridge Java;
2. aggiornare tutte le implementazioni C++ con lo stesso simbolo;
3. aggiornare il driver Java che invoca il metodo;
4. aggiornare CMake e staging se cambiano dipendenze o nomi;
5. pulire le vecchie `.so`;
6. ricompilare nativo e JAR;
7. verificare con `nm -D` i simboli esportati;
8. avviare con il backend reale e fare load, generate, stream e unload.

Esempio:

```bash
nm -D workers/rocket/build/bin/libnpu_rockchip_jni.so \
  | rg 'Java_com_npuhub_jni_rockchip_RockchipNativeBridge'
```

Se compare `UnsatisfiedLinkError`, controllare nell'ordine:

- quale copia della libreria è stata caricata;
- simbolo e signature JNI;
- dipendenze con `ldd`;
- coerenza ABI tra `libllama`, `libggml-*` e plugin Rocket;
- architettura del binario con `file`.

## Modificare il catalogo modelli

Il catalogo è in
`ModelManagementService.initCatalogModels()`. Ogni voce definisce:

```text
id, name, path, architecture, quantization, parameterCount,
contextWindow, compatibleBackend
```

Checklist:

1. usare un ID uguale al repository remoto;
2. scegliere un path sotto `models/`;
3. impostare l'architettura perché decide il template chat;
4. per Rockchip usare una quantizzazione ammessa;
5. verificare il filename GGUF reale;
6. verificare download, rilevamento >50 MiB, load e `/api/tags`;
7. verificare `/api/show` e context length GGUF.

Per aggiungere una nuova quantizzazione Rockchip, aggiornare
`ROCKCHIP_QUANTIZATIONS`. Il matcher viene usato da catalogo, downloader,
risoluzione Ollama e cancellazione: un cambiamento ha effetto su tutti questi
flussi.

## Aggiungere un backend

Un nuovo backend attraversa più livelli:

1. valore in `BackendType`;
2. implementazione Spring di `NpuDriver`;
3. bridge Java con firme native;
4. implementazione C++ reale;
5. probe hardware;
6. target CMake e packaging;
7. priorità e ordine display in `NpuDriverRegistry`;
8. catalogo modelli;
9. gruppi e selettori nel frontend;
10. documentazione e test.

Puntare a un probe fail-closed: un backend non deve dichiararsi disponibile
perché la libreria si è caricata. Il probe deve verificare device, runtime
vendor e una operazione minima significativa. Rockchip oggi non rispetta
ancora questa regola perché il probe nativo restituisce sempre `true`.

## Modificare le API

Le superfici compatibili condividono `OllamaInferenceFacade`; la logica di
prompt e sampling va modificata lì quando deve rimanere coerente tra Ollama e
OpenAI.

Prestare attenzione ai protocolli:

- Ollama streaming: `application/x-ndjson`, un JSON per riga;
- OpenAI streaming: `text/event-stream`, eventi SSE e terminatore previsto;
- non streaming: un singolo JSON;
- errori Ollama: `{"error":"..."}`;
- errori OpenAI: oggetto `error` annidato.

Quando si aggiunge un endpoint di inferenza che deve rispettare start/stop,
aggiornare anche `InferenceApiGateFilter.isInferencePath()`.

Le API amministrative stanno in `ControlPanelApiController`. Non inserire
comandi esterni nei controller: vanno confinati in un servizio e devono esporre
stato/progresso.

## Modificare il frontend

File principali:

- `templates/index.html`: struttura e contenuto server-rendered;
- `static/js/app.js`: stato, fetch, stream chat e azioni;
- `static/css/style.css`: layout e temi;
- `static/vendor`: librerie vendorizzate.

Non c'è build frontend. Dopo una modifica:

1. avviare Spring con DevTools;
2. fare hard refresh del browser;
3. controllare console browser e network;
4. provare viewport desktop e mobile;
5. verificare che Markdown passi ancora da DOMPurify.

## Test da introdurre

Priorità consigliata:

1. unit test per risoluzione nomi/quantizzazioni e path;
2. unit test per `GgufMetadataReader`;
3. test MVC per gate, error envelope e control API;
4. test di streaming NDJSON/SSE;
5. test di alias e persistenza su directory temporanea;
6. fake `NpuDriver` per lifecycle e concorrenza;
7. smoke test nativo separato per backend/hardware.

I test Java non devono caricare automaticamente le `.so` reali. Iniettare un
driver fake permette di rendere la suite ripetibile su CI.

## Checklist prima di consegnare una modifica

Per modifiche Java o frontend:

```bash
mvn test
git diff --check
```

In più, quando applicabile:

- avvio del contesto Spring;
- apertura dashboard;
- download/status/delete su directory temporanea;
- load/start/generate/stream/stop/unload;
- `/api/tags`, `/api/ps`, `/api/show`;
- una richiesta OpenAI non streaming e una SSE;
- controllo che nessun modello o `.so` generata sia entrato nel commit.

Per modifiche native:

- build pulita del target;
- `ldd` senza dipendenze `not found`;
- simboli JNI presenti;
- test sul dispositivo target;
- log di fallback o failure verificati;
- nessun output simulato scambiato per inferenza reale.

## Pulizia

```bash
tools/cleanup.sh --builds
tools/cleanup.sh --downloads
tools/cleanup.sh --all
```

`--builds` elimina output CMake, `target` e risorse native generate.
`--downloads` elimina `.build-tools` e il checkout di `llama.cpp`.
`--all` combina i due gruppi.

Lo script non elimina:

- modelli;
- `.npuhub/ollama-models.json`;
- sorgenti `ggml-rocket`;
- sorgenti `rocket-userspace`;
- log o configurazioni esterne al repository.
