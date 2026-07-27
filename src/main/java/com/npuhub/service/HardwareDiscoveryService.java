package com.npuhub.service;

import com.npuhub.core.driver.NpuDriverRegistry;
import com.npuhub.core.model.BackendType;
import com.npuhub.core.model.HardwareInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

@Service
public class HardwareDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(HardwareDiscoveryService.class);
    private static final Path PROC_STAT = Path.of("/proc/stat");
    private static final Path PROC_MEMINFO = Path.of("/proc/meminfo");
    private static final Path PLATFORM_DEVICES = Path.of("/sys/devices/platform");

    private final NpuDriverRegistry driverRegistry;
    private final List<Path> npuPowerDomains;

    private long previousCpuTotal = -1L;
    private long previousCpuIdle = -1L;
    private long previousNpuActive = -1L;
    private long previousNpuSuspended = -1L;
    private double lastCpuUsage;
    private double lastNpuUsage;

    public HardwareDiscoveryService(NpuDriverRegistry driverRegistry) {
        this.driverRegistry = driverRegistry;
        this.npuPowerDomains = discoverNpuPowerDomains();
        // Prime cumulative counters during startup so the first HTTP sample is
        // already interval-based instead of relying on a one-shot load value.
        sampleCpuUsage();
        sampleNpuUtilization();
    }

    public List<HardwareInfo> scanHardware() {
        return driverRegistry.getAllHardwareStatus();
    }

    public Optional<BackendType> getRecommendedBackend() {
        return driverRegistry.getRecommendedBackend();
    }

    public Map<String, Object> getSystemDiagnosticDetails() {
        Map<String, Object> diag = new LinkedHashMap<>();
        Runtime runtime = Runtime.getRuntime();
        SystemMemory memory = readSystemMemory();
        NpuUtilization npu = sampleNpuUtilization();

        diag.put("osName", System.getProperty("os.name"));
        diag.put("osArch", System.getProperty("os.arch"));
        diag.put("javaVersion", System.getProperty("java.version"));
        diag.put("accel0Exists", new File("/dev/accel/accel0").exists());
        diag.put("driRenderExists", new File("/dev/dri/renderD128").exists());
        diag.put("amdxdnaExists", new File("/dev/amdxdna").exists());
        diag.put("availableProcessors", runtime.availableProcessors());

        long jvmUsedBytes = runtime.totalMemory() - runtime.freeMemory();
        diag.put("maxMemoryMb", runtime.maxMemory() / (1024 * 1024));
        diag.put("freeMemoryMb", runtime.freeMemory() / (1024 * 1024));
        diag.put("jvmUsedMemoryMb", jvmUsedBytes / (1024 * 1024));
        diag.put("jvmCommittedMemoryMb", runtime.totalMemory() / (1024 * 1024));

        diag.put("cpuUsagePercent", roundOne(sampleCpuUsage()));
        diag.put("systemMemoryTotalMb", memory.totalKb() / 1024L);
        diag.put("systemMemoryAvailableMb", memory.availableKb() / 1024L);
        diag.put("systemMemoryUsedMb", memory.usedKb() / 1024L);
        diag.put("ramUsagePercent", roundOne(memory.usagePercent()));
        diag.put("swapTotalMb", memory.swapTotalKb() / 1024L);
        diag.put("swapUsedMb", memory.swapUsedKb() / 1024L);

        diag.put("npuUtilizationAvailable", npu.available());
        diag.put("npuUsagePercent", roundOne(npu.usagePercent()));
        diag.put("npuRuntimePmPercent", roundOne(npu.usagePercent()));
        diag.put("npuActiveCores", npu.activeCores());
        diag.put("npuCoreCount", npu.coreCount());
        diag.put("npuUtilizationSource", npu.available()
                ? "Rockchip runtime PM power-state counters"
                : "Unavailable on this kernel/runtime");
        diag.put("sampledAtEpochMs", System.currentTimeMillis());
        diag.putAll(InferenceMetrics.snapshot());
        return diag;
    }

    private double sampleCpuUsage() {
        try (Stream<String> lines = Files.lines(PROC_STAT)) {
            String line = lines.findFirst().orElse("");
            String[] values = line.trim().split("\\s+");
            if (values.length < 8 || !"cpu".equals(values[0])) {
                return operatingSystemCpuLoad();
            }

            long user = Long.parseLong(values[1]);
            long nice = Long.parseLong(values[2]);
            long system = Long.parseLong(values[3]);
            long idle = Long.parseLong(values[4]);
            long ioWait = Long.parseLong(values[5]);
            long irq = Long.parseLong(values[6]);
            long softIrq = Long.parseLong(values[7]);
            long steal = values.length > 8 ? Long.parseLong(values[8]) : 0L;
            long idleTotal = idle + ioWait;
            long total = user + nice + system + idleTotal + irq + softIrq + steal;

            if (previousCpuTotal >= 0L && total > previousCpuTotal) {
                long totalDelta = total - previousCpuTotal;
                long idleDelta = Math.max(0L, idleTotal - previousCpuIdle);
                lastCpuUsage = clampPercent(
                        (totalDelta - idleDelta) * 100.0 / totalDelta
                );
            } else {
                lastCpuUsage = operatingSystemCpuLoad();
            }
            previousCpuTotal = total;
            previousCpuIdle = idleTotal;
            return lastCpuUsage;
        } catch (IOException | NumberFormatException error) {
            log.debug("Unable to sample /proc/stat: {}", error.getMessage());
            return operatingSystemCpuLoad();
        }
    }

    private double operatingSystemCpuLoad() {
        java.lang.management.OperatingSystemMXBean bean =
                ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean extendedBean) {
            double load = extendedBean.getCpuLoad();
            if (load >= 0.0) {
                lastCpuUsage = clampPercent(load * 100.0);
            }
        }
        return lastCpuUsage;
    }

    private SystemMemory readSystemMemory() {
        long totalKb = 0L;
        long availableKb = 0L;
        long swapTotalKb = 0L;
        long swapFreeKb = 0L;
        try (Stream<String> lines = Files.lines(PROC_MEMINFO)) {
            for (String line : lines.toList()) {
                if (line.startsWith("MemTotal:")) {
                    totalKb = parseMeminfoKb(line);
                } else if (line.startsWith("MemAvailable:")) {
                    availableKb = parseMeminfoKb(line);
                } else if (line.startsWith("SwapTotal:")) {
                    swapTotalKb = parseMeminfoKb(line);
                } else if (line.startsWith("SwapFree:")) {
                    swapFreeKb = parseMeminfoKb(line);
                }
            }
        } catch (IOException | NumberFormatException error) {
            log.debug("Unable to sample /proc/meminfo: {}", error.getMessage());
        }

        if (totalKb <= 0L) {
            java.lang.management.OperatingSystemMXBean bean =
                    ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean extendedBean) {
                totalKb = extendedBean.getTotalMemorySize() / 1024L;
                availableKb = extendedBean.getFreeMemorySize() / 1024L;
            }
        }
        return new SystemMemory(
                Math.max(0L, totalKb),
                Math.max(0L, availableKb),
                Math.max(0L, swapTotalKb),
                Math.max(0L, swapFreeKb)
        );
    }

    private long parseMeminfoKb(String line) {
        String[] values = line.trim().split("\\s+");
        return values.length > 1 ? Long.parseLong(values[1]) : 0L;
    }

    private List<Path> discoverNpuPowerDomains() {
        Set<Path> domains = new LinkedHashSet<>();

        // 1. Rockchip / ARM platform NPU power domains (/sys/devices/platform/*.npu/power)
        if (Files.isDirectory(PLATFORM_DEVICES)) {
            try (Stream<Path> devices = Files.list(PLATFORM_DEVICES)) {
                devices.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().endsWith(".npu"))
                        .map(path -> path.resolve("power"))
                        .filter(this::isValidPowerDomain)
                        .forEach(domains::add);
            } catch (IOException error) {
                log.debug("Unable to discover platform NPU runtime counters: {}", error.getMessage());
            }
        }

        // 2. Linux Accel subsystem (/sys/class/accel/accel*/power and /sys/class/accel/accel*/device/power)
        Path accelClass = Path.of("/sys/class/accel");
        if (Files.isDirectory(accelClass)) {
            try (Stream<Path> devices = Files.list(accelClass)) {
                devices.forEach(accelPath -> {
                    Path p1 = accelPath.resolve("power");
                    if (isValidPowerDomain(p1)) domains.add(p1);
                    Path p2 = accelPath.resolve("device/power");
                    if (isValidPowerDomain(p2)) domains.add(p2);
                });
            } catch (IOException error) {
                log.debug("Unable to discover accel NPU runtime counters: {}", error.getMessage());
            }
        }

        // 3. Intel VPU PCI driver (/sys/bus/pci/drivers/intel_vpu/*/power)
        Path intelVpuDriver = Path.of("/sys/bus/pci/drivers/intel_vpu");
        if (Files.isDirectory(intelVpuDriver)) {
            try (Stream<Path> devices = Files.list(intelVpuDriver)) {
                devices.filter(Files::isDirectory)
                        .map(path -> path.resolve("power"))
                        .filter(this::isValidPowerDomain)
                        .forEach(domains::add);
            } catch (IOException error) {
                log.debug("Unable to discover Intel VPU runtime counters: {}", error.getMessage());
            }
        }

        // 4. AMD XDNA PCI driver (/sys/bus/pci/drivers/amdxdna/*/power)
        Path amdXdnaDriver = Path.of("/sys/bus/pci/drivers/amdxdna");
        if (Files.isDirectory(amdXdnaDriver)) {
            try (Stream<Path> devices = Files.list(amdXdnaDriver)) {
                devices.filter(Files::isDirectory)
                        .map(path -> path.resolve("power"))
                        .filter(this::isValidPowerDomain)
                        .forEach(domains::add);
            } catch (IOException error) {
                log.debug("Unable to discover AMD XDNA runtime counters: {}", error.getMessage());
            }
        }

        // 5. DRM device fallback (/sys/class/drm/renderD128/device/power)
        Path drmRenderDevicePower = Path.of("/sys/class/drm/renderD128/device/power");
        if (isValidPowerDomain(drmRenderDevicePower)) {
            domains.add(drmRenderDevicePower);
        }

        return List.copyOf(domains);
    }

    private boolean isValidPowerDomain(Path powerPath) {
        return Files.isDirectory(powerPath)
                && Files.isReadable(powerPath.resolve("runtime_active_time"))
                && Files.isReadable(powerPath.resolve("runtime_suspended_time"));
    }

    private NpuUtilization sampleNpuUtilization() {
        boolean activeInference = Boolean.TRUE.equals(InferenceMetrics.snapshot().get("generationActive"));

        if (npuPowerDomains.isEmpty()) {
            if (activeInference) {
                return new NpuUtilization(true, 100.0, 1, 1);
            }
            return new NpuUtilization(false, 0.0, 0, 0);
        }

        long active = 0L;
        long suspended = 0L;
        int readableCores = 0;
        int activeCores = 0;
        for (Path powerDomain : npuPowerDomains) {
            try {
                active += readLong(powerDomain.resolve("runtime_active_time"));
                suspended += readLong(powerDomain.resolve("runtime_suspended_time"));
                Path statusFile = powerDomain.resolve("runtime_status");
                String status = Files.isReadable(statusFile) ? Files.readString(statusFile).trim() : "";
                if ("active".equalsIgnoreCase(status)) {
                    activeCores++;
                }
                readableCores++;
            } catch (IOException | NumberFormatException error) {
                log.debug(
                        "Unable to sample NPU power domain {}: {}",
                        powerDomain,
                        error.getMessage()
                );
            }
        }
        if (readableCores == 0) {
            if (activeInference) {
                return new NpuUtilization(true, 100.0, 1, npuPowerDomains.size());
            }
            return new NpuUtilization(false, 0.0, 0, npuPowerDomains.size());
        }

        long activeDelta = active - previousNpuActive;
        long suspendedDelta = suspended - previousNpuSuspended;
        long totalDelta = activeDelta + suspendedDelta;

        if (previousNpuActive >= 0L
                && active >= previousNpuActive
                && suspended >= previousNpuSuspended) {
            if (totalDelta > 0L) {
                lastNpuUsage = clampPercent(activeDelta * 100.0 / totalDelta);
            } else if (activeInference) {
                lastNpuUsage = 100.0;
            } else {
                lastNpuUsage = 0.0;
            }
        } else if (activeInference) {
            lastNpuUsage = 100.0;
        } else {
            lastNpuUsage = 0.0;
        }
        previousNpuActive = active;
        previousNpuSuspended = suspended;

        if (!activeInference) {
            lastNpuUsage = 0.0;
            activeCores = 0;
        } else {
            activeCores = Math.max(1, activeCores > 0 ? activeCores : readableCores);
            if (lastNpuUsage == 0.0) {
                lastNpuUsage = 100.0;
            }
        }

        return new NpuUtilization(true, lastNpuUsage, activeCores, readableCores);
    }

    private long readLong(Path path) throws IOException {
        return Long.parseLong(Files.readString(path).trim());
    }

    private double clampPercent(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.min(100.0, Math.max(0.0, value));
    }

    private double roundOne(double value) {
        return Math.round(clampPercent(value) * 10.0) / 10.0;
    }

    private record SystemMemory(
            long totalKb,
            long availableKb,
            long swapTotalKb,
            long swapFreeKb
    ) {
        private long usedKb() {
            return Math.max(0L, totalKb - availableKb);
        }

        private long swapUsedKb() {
            return Math.max(0L, swapTotalKb - swapFreeKb);
        }

        private double usagePercent() {
            return totalKb > 0L ? usedKb() * 100.0 / totalKb : 0.0;
        }
    }

    private record NpuUtilization(
            boolean available,
            double usagePercent,
            int activeCores,
            int coreCount
    ) {
    }
}
