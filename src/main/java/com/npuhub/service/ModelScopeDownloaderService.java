package com.npuhub.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ModelScopeDownloaderService {
    private static final Logger log = LoggerFactory.getLogger(ModelScopeDownloaderService.class);
    private final Map<String, Double> downloadProgress = new ConcurrentHashMap<>();
    private final Map<String, String> downloadStatus = new ConcurrentHashMap<>();
    private final LogService logService;

    public ModelScopeDownloaderService(LogService logService) {
        this.logService = logService;
    }

    public String getStatus(String modelId) {
        return getStatus(modelId, null);
    }

    public String getStatus(String modelId, String quantization) {
        return downloadStatus.getOrDefault(downloadKey(modelId, quantization), "IDLE");
    }

    public double getProgress(String modelId) {
        return getProgress(modelId, null);
    }

    public double getProgress(String modelId, String quantization) {
        return downloadProgress.getOrDefault(downloadKey(modelId, quantization), 0.0);
    }

    private String downloadKey(String modelId, String quantization) {
        return quantization == null || quantization.isBlank()
                ? modelId
                : modelId + "#" + quantization.trim().toUpperCase(Locale.ROOT);
    }

    private File searchDownloaderScript(File location) throws Exception {
        File directory = location.isFile() ? location.getParentFile() : location;
        for (int depth = 0; directory != null && depth < 6; depth++, directory = directory.getParentFile()) {
            File candidate = new File(directory, "tools/download_model.py");
            if (candidate.isFile()) {
                return candidate.getCanonicalFile();
            }
        }
        return null;
    }

    private File resolveDownloaderScript() throws Exception {
        File workingDirectoryScript = searchDownloaderScript(new File("."));
        if (workingDirectoryScript != null) {
            return workingDirectoryScript;
        }

        // A Spring Boot executable JAR can expose a non-hierarchical jar:file: URI.
        // Only convert file: URLs to File; use the JVM classpath for the JAR case.
        var codeSourceUrl = ModelScopeDownloaderService.class.getProtectionDomain()
                .getCodeSource()
                .getLocation();
        if ("file".equalsIgnoreCase(codeSourceUrl.getProtocol())) {
            File codeLocation = new File(codeSourceUrl.toURI());
            File script = searchDownloaderScript(codeLocation);
            if (script != null) {
                return script;
            }
        }

        String classPath = System.getProperty("java.class.path", "");
        for (String entry : classPath.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                File script = searchDownloaderScript(new File(entry));
                if (script != null) {
                    return script;
                }
            }
        }

        throw new FileNotFoundException(
                "tools/download_model.py was not found relative to the working directory, classpath, or application location"
        );
    }

    private boolean matchesQuantization(File file, String quantization) {
        String normalized = quantization.trim().toUpperCase(Locale.ROOT);
        String name = file.getName().toUpperCase(Locale.ROOT);
        int start = name.indexOf(normalized);
        while (start >= 0) {
            int end = start + normalized.length();
            boolean leftBoundary = start == 0 || !Character.isLetterOrDigit(name.charAt(start - 1));
            boolean rightBoundary = end == name.length()
                    || (!Character.isLetterOrDigit(name.charAt(end)) && name.charAt(end) != '_');
            if (leftBoundary && rightBoundary) {
                return true;
            }
            start = name.indexOf(normalized, start + 1);
        }
        return false;
    }

    public boolean isDownloaded(String modelId, String targetDirectory) {
        return isDownloaded(modelId, targetDirectory, null);
    }

    public boolean isDownloaded(String modelId, String targetDirectory, String quantization) {
        String dirName = modelId.contains("/") ? modelId.substring(modelId.lastIndexOf('/') + 1) : modelId;
        File dir = new File(targetDirectory, dirName);
        if (!dir.exists() || !dir.isDirectory()) return false;

        // ModelScope repositories can contain nested paths. Walk the complete
        // tree; checking only dir.listFiles() makes a successful download look
        // unavailable to the WebUI when the model file is in a subdirectory.
        try (var files = Files.walk(dir.toPath())) {
            long totalBytes = files
                    .filter(Files::isRegularFile)
                    .filter(path -> quantization == null || quantization.isBlank()
                            || (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf")
                            && matchesQuantization(path.toFile(), quantization)))
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException ignored) {
                            return 0L;
                        }
                    })
                    .sum();
            // Verify that total downloaded size is > 50 MB (not just tiny LFS pointers!).
            return totalBytes > 50 * 1024 * 1024L;
        } catch (IOException e) {
            log.warn("Unable to inspect downloaded model directory {}: {}", dir, e.getMessage());
            return false;
        }
    }

    @Async
    public void downloadModelFromModelScope(String modelId, String targetDirectoryPath) {
        downloadModelFromModelScope(modelId, targetDirectoryPath, null);
    }

    @Async
    public void downloadModelFromModelScope(
            String modelId,
            String targetDirectoryPath,
            String quantization
    ) {
        String dirName = modelId.contains("/") ? modelId.substring(modelId.lastIndexOf('/') + 1) : modelId;
        File targetDir = new File(targetDirectoryPath, dirName);
        String key = downloadKey(modelId, quantization);
        String selection = quantization == null || quantization.isBlank()
                ? "complete repository"
                : quantization.trim().toUpperCase(Locale.ROOT);

        log.info("Starting {} download for {} into {}", selection, modelId, targetDir.getAbsolutePath());
        logService.addLog("DOWNLOAD", "Starting " + selection + " download for model " + modelId);
        downloadStatus.put(key, "DOWNLOADING");
        downloadProgress.put(key, 1.0);

        try {
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }

            File script = resolveDownloaderScript();
            log.info("Using model downloader script: {}", script.getAbsolutePath());
            ProcessBuilder pb = quantization == null || quantization.isBlank()
                    ? new ProcessBuilder("python3", script.getAbsolutePath(), modelId, targetDir.getAbsolutePath())
                    : new ProcessBuilder(
                    "python3",
                    script.getAbsolutePath(),
                    modelId,
                    targetDir.getAbsolutePath(),
                    quantization.trim().toUpperCase(Locale.ROOT)
            );
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[Downloader Output] {}", line);
                    logService.addLog("DOWNLOAD", line);
                    if (line.contains("PROGRESS:")) {
                        try {
                            String pStr = line.substring(line.indexOf("PROGRESS:") + 9);
                            if (pStr.contains("%")) {
                                pStr = pStr.substring(0, pStr.indexOf("%")).trim();
                                double pVal = Double.parseDouble(pStr);
                                downloadProgress.put(key, pVal);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            int exitCode = proc.waitFor();
            if (exitCode == 0 && isDownloaded(modelId, targetDirectoryPath, quantization)) {
                downloadStatus.put(key, "COMPLETED");
                downloadProgress.put(key, 100.0);
                log.info("Successfully completed {} download for {}", selection, modelId);
            } else {
                downloadStatus.put(key, "FAILED");
                log.error("Failed to complete {} download for {}, exit code: {}", selection, modelId, exitCode);
            }

        } catch (Exception e) {
            downloadStatus.put(key, "FAILED");
            log.error("Exception during {} download for {}: {}", selection, modelId, e.getMessage(), e);
        }
    }
}
