package com.npuhub.service;

import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class LogService {
    private static final int MAX_LOGS = 2000;
    private final List<LogEntry> logBuffer = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong counter = new AtomicLong(1);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    public LogService() {
        addLog("SYSTEM", "NPU-Hub System Console initialized.");
        addLog("INFO", "Real-time download, driver & CMake compilation terminal stream ready.");
    }

    public synchronized void addLog(String level, String message) {
        if (message == null || message.isBlank()) return;
        String timeStr = LocalTime.now().format(TIME_FORMATTER);
        long id = counter.getAndIncrement();
        logBuffer.add(new LogEntry(id, timeStr, level, message));

        while (logBuffer.size() > MAX_LOGS) {
            logBuffer.remove(0);
        }
    }

    public synchronized List<LogEntry> getLogsAfter(long lastId) {
        List<LogEntry> result = new ArrayList<>();
        for (LogEntry entry : logBuffer) {
            if (entry.id() > lastId) {
                result.add(entry);
            }
        }
        return result;
    }

    public record LogEntry(long id, String timestamp, String level, String message) {}
}
