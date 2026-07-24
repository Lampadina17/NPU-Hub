package com.npuhub.core.driver;

import com.npuhub.core.model.BackendType;
import com.npuhub.core.model.HardwareInfo;
import com.npuhub.core.model.InferenceRequest;
import com.npuhub.core.model.InferenceResponse;
import com.npuhub.core.model.TokenChunk;

import java.util.function.Consumer;

public interface NpuDriver {
    BackendType getBackendType();
    
    HardwareInfo probeHardware();
    
    boolean isAvailable();
    
    boolean loadModel(String modelPath, int contextWindow);
    
    boolean unloadModel();
    
    InferenceResponse generate(InferenceRequest request);
    
    void generateStream(InferenceRequest request, Consumer<TokenChunk> tokenConsumer);
}
