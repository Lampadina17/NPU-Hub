package com.npuhub.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

@Service
public class SetupService {
    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    private final LogService logService;

    public SetupService(LogService logService) {
        this.logService = logService;
    }

    private static final String NPU_DRIVER_VERSION = "1.33.0";
    private static final String NPU_DRIVER_BUILD = "20260529-26625960453";
    private static final String LEVEL_ZERO_VERSION = "1.27.0-1~24.04~ppa2";

    private final Map<String, Double> taskProgress = new ConcurrentHashMap<>();
    private final Map<String, String> taskStatus = new ConcurrentHashMap<>();

    public String getStatus(String taskId) {
        return taskStatus.getOrDefault(taskId, "IDLE");
    }

    public double getProgress(String taskId) {
        return taskProgress.getOrDefault(taskId, 0.0);
    }

    public void installIntelRuntimeAsync() {
        String taskId = "intel-driver";
        taskStatus.put(taskId, "RUNNING");
        taskProgress.put(taskId, 10.0);

        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                String currentUser = System.getProperty("user.name");
                log.info("Starting Intel NPU Driver installation for user: {}", currentUser);

                Path tempDir = Files.createTempDirectory("npu-intel-setup");
                String driverArchive = String.format("linux-npu-driver-v%s.%s-ubuntu2404.tar.gz", NPU_DRIVER_VERSION, NPU_DRIVER_BUILD);
                String driverUrl = String.format("https://github.com/intel/linux-npu-driver/releases/download/v%s/%s", NPU_DRIVER_VERSION, driverArchive);

                String levelZeroArchive = String.format("libze1_%s_amd64.deb", LEVEL_ZERO_VERSION);
                String levelZeroUrl = String.format("https://snapshot.ppa.launchpadcontent.net/kobuk-team/intel-graphics/ubuntu/20260324T100000Z/pool/main/l/level-zero-loader/%s", levelZeroArchive);

                Path driverFile = tempDir.resolve(driverArchive);
                Path levelZeroFile = tempDir.resolve(levelZeroArchive);

                taskStatus.put(taskId, "Downloading Intel NPU Driver...");
                taskProgress.put(taskId, 25.0);
                runCommand(new String[]{"curl", "-fL", "--retry", "3", driverUrl, "-o", driverFile.toString()});

                taskStatus.put(taskId, "Downloading Level-Zero Loader...");
                taskProgress.put(taskId, 45.0);
                runCommand(new String[]{"curl", "-fL", "--retry", "3", levelZeroUrl, "-o", levelZeroFile.toString()});

                taskStatus.put(taskId, "Extracting packages...");
                taskProgress.put(taskId, 60.0);
                runCommand(new String[]{"tar", "-xzf", driverFile.toString(), "-C", tempDir.toString()});

                taskStatus.put(taskId, "Installing packages via pkexec/apt...");
                taskProgress.put(taskId, 75.0);

                String pkgSuffix = String.format("%s.%s~ubuntu24.04_amd64.deb", NPU_DRIVER_VERSION, NPU_DRIVER_BUILD);
                String cmdStr = String.format(
                        "pkexec bash -c 'apt-get update && apt-get install -y libtbb12 %s %s/intel-driver-compiler-npu_%s %s/intel-fw-npu_%s %s/intel-level-zero-npu_%s " +
                        "&& echo \"SUBSYSTEM==\\\"accel\\\", KERNEL==\\\"accel*\\\", GROUP=\\\"render\\\", MODE=\\\"0660\\\"\" > /etc/udev/rules.d/10-intel-vpu.rules " +
                        "&& udevadm control --reload-rules && udevadm trigger --subsystem-match=accel && gpasswd --add %s render'",
                        levelZeroFile.toString(), tempDir.toString(), pkgSuffix, tempDir.toString(), pkgSuffix, tempDir.toString(), pkgSuffix, currentUser
                );

                runCommand(new String[]{"bash", "-c", cmdStr});

                taskStatus.put(taskId, "COMPLETED");
                taskProgress.put(taskId, 100.0);
                log.info("Intel NPU driver setup completed successfully.");
            } catch (Exception e) {
                log.error("Intel NPU Driver installation failed", e);
                taskStatus.put(taskId, "FAILED: " + e.getMessage());
            }
        });
    }

    public void buildWorkerAsync(String workerType) {
        String taskId = "build-" + workerType;
        taskStatus.put(taskId, "RUNNING");
        taskProgress.put(taskId, 10.0);

        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                Path workerDir = resolveWorkerDirectory(workerType);
                Path projectRoot = workerDir.getParent().getParent();
                Path buildDir = workerDir.resolve("build");

                if (!Files.exists(workerDir)) {
                    throw new IllegalArgumentException("Worker directory not found: " + workerDir.toAbsolutePath());
                }

                Files.createDirectories(buildDir);

                taskStatus.put(taskId, "Configuring CMake...");
                taskProgress.put(taskId, 35.0);

                List<String> cmakeArguments = new ArrayList<>(List.of(
                        "cmake",
                        "-S", workerDir.toString(),
                        "-B", buildDir.toString(),
                        "-DCMAKE_BUILD_TYPE=Release"
                ));
                Path llamaDirectory = null;
                if ("rocket".equalsIgnoreCase(workerType)) {
                    llamaDirectory = resolveLlamaDirectory(projectRoot);
                    taskStatus.put(taskId, "Updating llama.cpp to origin/master...");
                    taskProgress.put(taskId, 25.0);
                    updateLlamaCheckout(llamaDirectory);
                    ensureLlamaPatch(projectRoot, llamaDirectory);
                    cmakeArguments.add("-DLLAMA_DIR=" + llamaDirectory);

                    Path rocketBackend = projectRoot.resolve(
                            ".rocket-runtime/ggml-rocket/build-dl/libggml-rocket.so"
                    ).normalize();
                    if (Files.isRegularFile(rocketBackend)) {
                        cmakeArguments.add("-DROCKET_BACKEND_LIBRARY=" + rocketBackend);
                    }
                }

                ProcessBuilder cmakeConfig = new ProcessBuilder(cmakeArguments);
                cmakeConfig.directory(projectRoot.toFile());
                runProcess(cmakeConfig);

                taskStatus.put(taskId, "Compiling C++ Worker...");
                taskProgress.put(taskId, 70.0);

                ProcessBuilder cmakeBuild = new ProcessBuilder("cmake", "--build", buildDir.toString(), "--parallel");
                cmakeBuild.directory(projectRoot.toFile());
                runProcess(cmakeBuild);

                if ("rocket".equalsIgnoreCase(workerType)) {
                    buildRocketBackend(projectRoot, llamaDirectory, buildDir, taskId);
                }

                taskStatus.put(taskId, "COMPLETED");
                taskProgress.put(taskId, 100.0);
                log.info("Build completed for worker: {}", workerType);
            } catch (Exception e) {
                log.error("Worker build failed for: " + workerType, e);
                taskStatus.put(taskId, "FAILED: " + e.getMessage());
            }
        });
    }

    private Path resolveLlamaDirectory(Path projectRoot) throws Exception {
        String configured = System.getenv("LLAMA_DIR");
        if (configured != null && !configured.isBlank()) {
            Path candidate = Paths.get(configured).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate.resolve("CMakeLists.txt"))) {
                return candidate;
            }
        }

        Path bundledRuntime = projectRoot.resolve(".rocket-runtime/llama.cpp").normalize();
        if (Files.isRegularFile(bundledRuntime.resolve("CMakeLists.txt"))) {
            return bundledRuntime;
        }

        Files.createDirectories(bundledRuntime.getParent());
        logService.addLog("BUILD", "Cloning latest llama.cpp into " + bundledRuntime);
        ProcessBuilder clone = new ProcessBuilder(
                "git", "clone", "--branch", "master", "--single-branch",
                "https://github.com/ggml-org/llama.cpp.git",
                bundledRuntime.toString()
        );
        clone.directory(projectRoot.toFile());
        runProcess(clone);
        return bundledRuntime;
    }

    private void updateLlamaCheckout(Path llamaDirectory) throws Exception {
        if (!Files.isDirectory(llamaDirectory.resolve(".git"))) {
            throw new IllegalArgumentException(
                    "LLAMA_DIR is not a Git checkout and cannot be updated: " + llamaDirectory
            );
        }

        logService.addLog("BUILD", "Fetching latest llama.cpp from origin/master");
        runProcess(new ProcessBuilder(
                "git", "-C", llamaDirectory.toString(),
                "fetch", "--prune", "origin", "master"
        ));
        runProcess(new ProcessBuilder(
                "git", "-C", llamaDirectory.toString(),
                "rebase", "--autostash", "origin/master"
        ));
    }

    private void ensureLlamaPatch(Path projectRoot, Path llamaDirectory) throws Exception {
        Path patch = projectRoot.resolve(
                "workers/rocket/patches/llama-rocket-strict.patch"
        ).normalize();
        if (!Files.isRegularFile(patch)) {
            throw new IllegalArgumentException("Rocket llama.cpp patch not found: " + patch);
        }

        if (commandSucceeds(
                "git", "-C", llamaDirectory.toString(),
                "apply", "--reverse", "--check", patch.toString()
        )) {
            return;
        }
        if (!commandSucceeds(
                "git", "-C", llamaDirectory.toString(),
                "apply", "--check", patch.toString()
        )) {
            throw new IllegalStateException(
                    "Latest llama.cpp is incompatible with Rocket patch " + patch
            );
        }

        logService.addLog("BUILD", "Applying Rocket scheduler patch to latest llama.cpp");
        runProcess(new ProcessBuilder(
                "git", "-C", llamaDirectory.toString(),
                "apply", patch.toString()
        ));
    }

    private void buildRocketBackend(
            Path projectRoot,
            Path llamaDirectory,
            Path workerBuildDirectory,
            String taskId
    ) throws Exception {
        Path backendSource = projectRoot.resolve(".rocket-runtime/ggml-rocket").normalize();
        Path userspaceSource = projectRoot.resolve(".rocket-runtime/rocket-userspace").normalize();
        if (!Files.isRegularFile(backendSource.resolve("CMakeLists.txt"))) {
            throw new IllegalArgumentException("ggml-rocket checkout not found: " + backendSource);
        }
        if (!Files.isRegularFile(userspaceSource.resolve("CMakeLists.txt"))) {
            throw new IllegalArgumentException("rocket-userspace checkout not found: " + userspaceSource);
        }

        Path backendBuild = backendSource.resolve("build-dl");
        taskStatus.put(taskId, "Building ggml-rocket against latest llama.cpp...");
        taskProgress.put(taskId, 90.0);
        ProcessBuilder configure = new ProcessBuilder(
                "cmake",
                "-S", backendSource.toString(),
                "-B", backendBuild.toString(),
                "-DCMAKE_BUILD_TYPE=Release",
                "-DGGML_ROCKET_DL=ON",
                "-DHOST_DIR=" + llamaDirectory,
                "-DGGML_LIB_DIR=" + workerBuildDirectory.resolve("bin"),
                "-DCMAKE_DISABLE_FIND_PACKAGE_rocketnpu=ON",
                "-DROCKETNPU_DIR=" + userspaceSource
        );
        configure.directory(projectRoot.toFile());
        runProcess(configure);

        ProcessBuilder build = new ProcessBuilder(
                "cmake", "--build", backendBuild.toString(), "--parallel"
        );
        build.directory(projectRoot.toFile());
        runProcess(build);

        Path backendLibrary = backendBuild.resolve("libggml-rocket.so");
        if (!Files.isRegularFile(backendLibrary)) {
            throw new IllegalStateException(
                    "ggml-rocket build completed without " + backendLibrary
            );
        }
        Files.copy(
                backendLibrary,
                workerBuildDirectory.resolve("bin/libggml-rocket.so"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
        );
    }

    private boolean commandSucceeds(String... command) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            processBuilder.redirectError(ProcessBuilder.Redirect.DISCARD);
            return processBuilder.start().waitFor() == 0;
        } catch (Exception error) {
            return false;
        }
    }

    private Path resolveWorkerDirectory(String workerType) {
        List<Path> roots = new ArrayList<>();
        Path workingDirectory = Paths.get(System.getProperty("user.dir", "."))
                .toAbsolutePath().normalize();
        roots.add(workingDirectory);
        roots.add(workingDirectory.resolve("NPU-Hub"));

        try {
            Path codeSource = Paths.get(
                    SetupService.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).toAbsolutePath().normalize();
            Path applicationDirectory = Files.isRegularFile(codeSource)
                    ? codeSource.getParent()
                    : codeSource;
            roots.add(applicationDirectory);
            roots.add(applicationDirectory.resolve(".."));
            roots.add(applicationDirectory.resolve("../.."));
        } catch (Exception error) {
            log.debug("Unable to resolve application location: {}", error.getMessage());
        }

        List<Path> checked = new ArrayList<>();
        for (Path root : roots) {
            Path candidate = root.normalize().resolve("workers").resolve(workerType).normalize();
            checked.add(candidate);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "Worker directory not found for '" + workerType + "'. Checked: " + checked
        );
    }

    public void installModelScopeAsync() {
        String taskId = "modelscope-setup";
        taskStatus.put(taskId, "RUNNING");
        taskProgress.put(taskId, 20.0);

        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                taskStatus.put(taskId, "Installing modelscope pip package...");
                taskProgress.put(taskId, 50.0);
                runCommand(new String[]{"python3", "-m", "pip", "install", "modelscope"});

                taskStatus.put(taskId, "COMPLETED");
                taskProgress.put(taskId, 100.0);
                log.info("ModelScope CLI installed successfully.");
            } catch (Exception e) {
                log.error("ModelScope setup failed", e);
                taskStatus.put(taskId, "FAILED: " + e.getMessage());
            }
        });
    }

    private void runCommand(String[] cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        runProcess(pb);
    }

    private void runProcess(ProcessBuilder pb) throws Exception {
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[SETUP PROCESS] {}", line);
                logService.addLog("BUILD", line);
            }
        }
        int code = p.waitFor();
        if (code != 0) {
            logService.addLog("ERROR", "Process exited with error code: " + code);
            throw new RuntimeException("Process exited with non-zero code: " + code);
        }
    }
}
