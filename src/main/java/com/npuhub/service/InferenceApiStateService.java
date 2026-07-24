package com.npuhub.service;

import org.springframework.stereotype.Service;

@Service
public class InferenceApiStateService {
    private final ModelManagementService modelManagementService;
    private volatile boolean enabled;

    public InferenceApiStateService(ModelManagementService modelManagementService) {
        this.modelManagementService = modelManagementService;
        this.enabled = false;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public synchronized void start() {
        if (modelManagementService.getLoadedModelState().isEmpty()) {
            throw new IllegalStateException("Load a model before starting the inference API");
        }
        enabled = true;
    }

    public void stop() {
        enabled = false;
    }
}
