# NPU Hub

NPU Hub è un server di inferenza locale per NPU con:

- control panel web integrato;
- API compatibili con Ollama e, in parte, OpenAI;
- orchestrazione Java/Spring Boot;
- adapter nativi JNI per Rockchip, Intel, AMD e Qualcomm;
- download e gestione locale dei modelli.

Il progetto è in fase sperimentale. Non va considerato pronto per produzione:
alcuni backend sono completi solo a livello di interfaccia, non ci sono ancora
test automatici e le API non hanno autenticazione.

## Da leggere prima di modificare il codice

La documentazione tecnica è divisa per responsabilità:

- [Architettura e flussi](docs/architecture.md): componenti, stato globale,
  ciclo di vita di un modello, concorrenza e confini tra Java e C++.
- [Guida allo sviluppo](docs/development.md): prerequisiti, build, avvio,
  modifica di driver/modelli/API e checklist JNI.
- [Configurazione e operatività](docs/configuration.md): proprietà Spring,
  variabili Rocket, directory persistenti, sicurezza e limiti operativi.
- [API del control panel](docs/control-api.md): endpoint amministrativi,
  payload e side effect.
- [Compatibilità Ollama e OpenAI](docs/ollama-api.md): endpoint di inferenza,
  streaming e comportamento dei client.

## Stato reale dei backend

| Backend | Implementazione reale | Build standard `tools/build-all.sh` |
| --- | --- | --- |
| Rockchip RK3588/RK3588S | Sì, `llama.cpp` + `ggml-rocket` in `workers/rocket` | Inclusa e impacchettata nel JAR |
| Intel OpenVINO GenAI | Presente in `workers/openvino`, richiede SDK esterno | Non inclusa; viene impacchettato lo stub generico |
| AMD Ryzen AI | Presente in `workers/ryzenai`, richiede SDK esterno | Non inclusa; viene impacchettato lo stub generico |
| Qualcomm QAIRT/Genie | Non ancora integrata con lo SDK reale | Solo stub generico |

I file sotto `native/` sono adapter di compatibilità che simulano probe,
caricamento e generazione. Non dimostrano che l'inferenza avvenga su NPU. Per
OpenVINO e Ryzen AI le implementazioni reali sono quelle sotto `workers/`.
Per Qualcomm non è ancora presente un worker reale.

Questa distinzione è importante: una libreria `.so` caricata correttamente non
è, da sola, prova di accelerazione hardware.

Anche il probe Rocket è ancora provvisorio: il metodo nativo restituisce
sempre disponibile e il driver Java lo combina con il device node tramite un
OR. La conferma reale arriva solo quando il plugin Rocket inizializza il device
e il load del modello riesce.

## Percorso rapido

Prerequisiti minimi per compilare il control plane:

- Linux;
- JDK 17;
- Maven 3.9 o compatibile.

Verifica della parte Java:

```bash
mvn test
```

Se Maven non è installato globalmente ed è già presente il tool locale:

```bash
./.build-tools/apache-maven-3.9.9/bin/mvn test
```

Avvio per sviluppo del pannello e delle API:

```bash
mvn spring-boot:run
```

Il pannello è disponibile su `http://localhost:8080`. Senza una NPU supportata
e una libreria nativa reale sarà possibile ispezionare l'interfaccia, ma non
eseguire inferenza reale.

### Build completa Rockchip

La build completa richiede anche Git, CMake, un compilatore C/C++, gli header
JNI e accesso alla rete:

```bash
tools/build-all.sh
java -jar target/npu-hub-1.0.0-SNAPSHOT.jar
```

Lo script:

1. aggiorna `llama.cpp` a `origin/master`;
2. applica la patch Rocket;
3. clona o riusa `ggml-rocket` e `rocket-userspace`;
4. compila il runtime Rocket e gli adapter generici;
5. copia le librerie in `src/main/resources/native`;
6. esegue `mvn clean package`.

La build non è completamente riproducibile perché segue il branch corrente di
`llama.cpp`. Se la patch non è più applicabile, lo script si interrompe invece
di produrre un runtime con ABI incoerente.

## Flusso operativo

L'ordine previsto è:

1. aprire il pannello;
2. verificare che il backend sia marcato come disponibile;
3. scaricare o posizionare un modello compatibile;
4. caricare esplicitamente il modello;
5. avviare l'API di inferenza dal pannello;
6. usare la chat integrata o un client Ollama/OpenAI;
7. fermare l'API e scaricare il modello quando necessario.

Il processo mantiene un solo modello caricato alla volta. Scaricare un modello
su disco, caricarlo in memoria e abilitare gli endpoint di inferenza sono tre
operazioni distinte.

## Struttura del repository

```text
src/main/java/com/npuhub/
  core/driver/       contratto dei driver, registry e implementazioni Java
  core/model/        record ed enum condivisi
  jni/               firme JNI e caricamento delle librerie native
  service/           modelli, inferenza, setup, metriche e stato API
  web/               filtri, error handling e controller HTTP

src/main/resources/
  application.yml    configurazione di default
  templates/         pagina Thymeleaf
  static/            JavaScript, CSS e vendor frontend
  native/            output generato per le librerie impacchettate

native/               adapter C++ generici/stub
workers/rocket/       runtime Rockchip reale
workers/openvino/     adapter OpenVINO GenAI reale
workers/ryzenai/      adapter ONNX Runtime GenAI reale
tools/                build, pulizia e download modelli
docs/                 documentazione tecnica
```

Il frontend non usa Node, Vite o un bundler: è HTML Thymeleaf con JavaScript e
CSS statici. Le modifiche sotto `src/main/resources/static` sono servite
direttamente da Spring Boot.

## Vincoli importanti

- Non esiste fallback CPU/GPU a livello di selezione Java. Rocket usa però una
  modalità ibrida: prefill conveniente su NPU e decode breve su CPU.
- I controller di controllo sono esposti senza autenticazione e con CORS
  permissivo. Non pubblicare la porta su una rete non fidata.
- Le impostazioni salvate dal pannello sono solo in memoria e, oggi, diverse
  voci non riconfigurano i componenti Spring già avviati.
- `mvn test` passa, ma non ci sono sorgenti sotto `src/test`: non copre
  inferenza, hardware, streaming o compatibilità API.
- Il catalogo modelli è hardcoded in `ModelManagementService`.
- Il file `LICENSE` non è presente nello stato attuale del progetto: chiarire
  la licenza prima di distribuire binari o sorgenti.

## Pulizia

Per rimuovere solo gli output generati:

```bash
tools/cleanup.sh --builds
```

Per includere anche Maven scaricato localmente e il checkout di `llama.cpp`:

```bash
tools/cleanup.sh --all
```

Lo script mostra i target e chiede conferma. Non rimuove modelli,
configurazione, `ggml-rocket` o `rocket-userspace`.
