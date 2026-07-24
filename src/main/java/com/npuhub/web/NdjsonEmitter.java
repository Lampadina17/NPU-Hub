package com.npuhub.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;

public final class NdjsonEmitter extends ResponseBodyEmitter {
    public static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");

    private final ObjectMapper objectMapper;

    public NdjsonEmitter(ObjectMapper objectMapper) {
        super(600_000L);
        this.objectMapper = objectMapper;
    }

    public synchronized void sendJson(Object value) throws IOException {
        send(objectMapper.writeValueAsString(value) + "\n", NDJSON);
    }
}
