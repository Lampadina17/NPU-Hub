package com.npuhub.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SettingsService {
    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);
    private final Map<String, Object> settings = new ConcurrentHashMap<>();

    public SettingsService() {
        // Defaults
        settings.put("preferredBackend", "auto");
        settings.put("modelsDirectory", "models");
        settings.put("ollamaPort", 8080);

        settings.put("maxConcurrentInferences", 2);
        settings.put("defaultContextWindow", 4096);
    }

    public Map<String, Object> getSettings() {
        return new HashMap<>(settings);
    }

    public void updateSettings(Map<String, Object> newSettings) {
        if (newSettings != null) {
            settings.putAll(newSettings);
            log.info("Server settings updated: {}", settings);
        }
    }
}
