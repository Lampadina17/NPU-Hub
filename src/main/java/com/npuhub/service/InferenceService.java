package com.npuhub.service;

import com.npuhub.core.driver.NpuDriver;
import com.npuhub.core.driver.NpuDriverRegistry;
import com.npuhub.core.model.InferenceRequest;
import com.npuhub.core.model.InferenceResponse;
import com.npuhub.core.model.TokenChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@Service
public class InferenceService {
    private static final Logger log = LoggerFactory.getLogger(InferenceService.class);
    private final NpuDriverRegistry driverRegistry;
    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(4);

    public InferenceService(NpuDriverRegistry driverRegistry) {
        this.driverRegistry = driverRegistry;
    }

    public InferenceResponse processInference(InferenceRequest request, String requestedBackend) {
        NpuDriver driver = driverRegistry.selectActiveDriver(requestedBackend);
        log.info("Processing inference request on driver {}", driver.getBackendType());
        InferenceMetrics.Measurement measurement = InferenceMetrics.begin(driver.getBackendType());
        try {
            InferenceResponse response = driver.generate(request);
            measurement.complete(response);
            return response;
        } catch (RuntimeException | Error error) {
            measurement.fail();
            throw error;
        }
    }

    public void processInferenceStream(InferenceRequest request, String requestedBackend, Consumer<TokenChunk> tokenConsumer) {
        processInferenceStream(request, requestedBackend, tokenConsumer, error ->
                tokenConsumer.accept(new TokenChunk(
                        request.requestId(),
                        " [Error: " + error.getMessage() + "]",
                        true,
                        0.0
                ))
        );
    }

    public void processInferenceStream(
            InferenceRequest request,
            String requestedBackend,
            Consumer<TokenChunk> tokenConsumer,
            Consumer<Throwable> errorConsumer
    ) {
        processInferenceStream(request, requestedBackend, tokenConsumer, errorConsumer, () -> {
        });
    }

    public void processInferenceStream(
            InferenceRequest request,
            String requestedBackend,
            Consumer<TokenChunk> tokenConsumer,
            Consumer<Throwable> errorConsumer,
            Runnable completionConsumer
    ) {
        NpuDriver driver = driverRegistry.selectActiveDriver(requestedBackend);
        log.info("Streaming inference request on driver {}", driver.getBackendType());
        asyncExecutor.submit(() -> {
            InferenceMetrics.Measurement measurement =
                    InferenceMetrics.begin(driver.getBackendType());
            try {
                driver.generateStream(request, chunk -> {
                    double tokensPerSecond = chunk.currentTokensPerSecond();
                    if (!chunk.done() && chunk.token() != null && !chunk.token().isEmpty()) {
                        tokensPerSecond = measurement.onToken();
                    }
                    tokenConsumer.accept(new TokenChunk(
                            chunk.requestId(),
                            chunk.token(),
                            chunk.done(),
                            tokensPerSecond
                    ));
                });
                measurement.completeStream(true);
                completionConsumer.run();
            } catch (Throwable e) {
                measurement.fail();
                log.error("Error during streaming inference: {}", e.getMessage(), e);
                errorConsumer.accept(e);
            }
        });
    }
}
