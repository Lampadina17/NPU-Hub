package com.npuhub;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class Application {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    @Value("${server.address:}")
    private String serverAddress;

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void printStartupUrl(ApplicationReadyEvent event) {
        int actualPort = ((WebServerApplicationContext) event.getApplicationContext()).getWebServer().getPort();
        String host = serverAddress.isBlank() ? "localhost" : serverAddress;
        log.info("NPU Hub pronto: http://{}:{}", host, actualPort);
        log.info("Ollama API e Open WebUI: http://{}:{}", host, actualPort);
    }
}
