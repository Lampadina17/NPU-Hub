package com.npuhub.jni;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public class NativeLibraryLoader {
    private static final Logger log = LoggerFactory.getLogger(NativeLibraryLoader.class);

    public static boolean loadLibrary(String libName) {
        String mappedName = System.mapLibraryName(libName);

        // Rocket must be loaded with the exact llama.cpp/ggml ABI bundled with
        // this application. A generic or stale libnpu_rockchip_jni.so from the
        // system/native build can load successfully and still miss JNI symbols.
        if ("npu_rockchip_jni".equals(libName) && loadBundledRocketRuntime(mappedName)) {
            return true;
        }

        // 1. Try system library path for standalone adapters.
        try {
            System.loadLibrary(libName);
            log.info("Successfully loaded native library '{}' from system library path.", libName);
            return true;
        } catch (UnsatisfiedLinkError e) {
            log.debug("Could not load '{}' from system library path: {}", libName, e.getMessage());
        }

        // 2. Try build directory
        File localFile = new File("native/build/" + mappedName);
        if (localFile.exists()) {
            try {
                System.load(localFile.getAbsolutePath());
                log.info("Successfully loaded native library '{}' from {}", libName, localFile.getAbsolutePath());
                return true;
            } catch (UnsatisfiedLinkError e) {
                log.error("Failed to load local native library file {}: {}", localFile.getAbsolutePath(), e.getMessage());
            }
        }

        // 3. Extract a standalone JNI adapter from classpath resources.
        try (InputStream in = NativeLibraryLoader.class.getResourceAsStream("/native/" + mappedName)) {
            if (in != null) {
                Path tempFile = Files.createTempFile("lib_" + libName, ".so");
                tempFile.toFile().deleteOnExit();
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
                System.load(tempFile.toAbsolutePath().toString());
                log.info("Successfully loaded native library '{}' extracted from jar resources.", libName);
                return true;
            }
        } catch (Exception e) {
            log.error("Error extracting resource library for {}: {}", libName, e.getMessage());
        }

        log.warn("Native library '{}' is not present or hardware dependencies are missing.", libName);
        return false;
    }

    private static boolean loadBundledRocketRuntime(String mappedJniName) {
        List<String> runtimeFiles = List.of(
                "libggml-base.so.0",
                "libggml.so.0",
                "libllama.so.0",
                "libggml-rocket.so",
                mappedJniName
        );
        try {
            Path runtimeDirectory = Files.createTempDirectory("npuhub-rocket-runtime-");
            runtimeDirectory.toFile().deleteOnExit();
            for (String fileName : runtimeFiles) {
                String resourceName = "/native/rocket/" + fileName;
                try (InputStream input = NativeLibraryLoader.class.getResourceAsStream(resourceName)) {
                    if (input == null) {
                        return false;
                    }
                    Path output = runtimeDirectory.resolve(fileName);
                    Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                    output.toFile().deleteOnExit();
                }
            }

            // GGML_CPU_ALL_VARIANTS builds dispatchable CPU plugins with
            // names such as libggml-cpu-armv8.2_1.so. They are staged as
            // individual resources; extract all of them beside the runtime.
            try (InputStream manifest = NativeLibraryLoader.class.getResourceAsStream(
                    "/native/rocket/ggml-cpu-variants.list")) {
                if (manifest == null) {
                    return false;
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(manifest))) {
                    String fileName;
                    while ((fileName = reader.readLine()) != null) {
                        if (fileName.isBlank()) {
                            continue;
                        }
                        try (InputStream input = NativeLibraryLoader.class.getResourceAsStream(
                                "/native/rocket/" + fileName)) {
                            if (input == null) {
                                return false;
                            }
                            Path output = runtimeDirectory.resolve(fileName);
                            Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
                            output.toFile().deleteOnExit();
                        }
                    }
                }
            }

            // Load linked libraries in dependency order. ggml discovers the two
            // plugin modules from this same directory when a model is opened.
            System.load(runtimeDirectory.resolve("libggml-base.so.0").toString());
            System.load(runtimeDirectory.resolve("libggml.so.0").toString());
            System.load(runtimeDirectory.resolve("libllama.so.0").toString());
            System.load(runtimeDirectory.resolve(mappedJniName).toString());
            log.info(
                    "Successfully loaded bundled Rocket runtime from {}",
                    runtimeDirectory
            );
            return true;
        } catch (Exception | UnsatisfiedLinkError error) {
            log.error("Failed to load bundled Rocket runtime: {}", error.getMessage());
            return false;
        }
    }
}
