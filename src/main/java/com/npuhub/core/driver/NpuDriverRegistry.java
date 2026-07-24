package com.npuhub.core.driver;

import com.npuhub.core.model.BackendType;
import com.npuhub.core.model.HardwareInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NpuDriverRegistry {
    private static final Logger log = LoggerFactory.getLogger(NpuDriverRegistry.class);
    private static final List<BackendType> AUTO_SELECTION_PRIORITY = List.of(
            BackendType.ROCKCHIP,
            BackendType.OPENVINO,
            BackendType.QUALCOMM,
            BackendType.RYZENAI
    );
    private static final List<BackendType> DISPLAY_ORDER = List.of(
            BackendType.OPENVINO,
            BackendType.RYZENAI,
            BackendType.ROCKCHIP,
            BackendType.QUALCOMM
    );
    private final Map<BackendType, NpuDriver> drivers = new ConcurrentHashMap<>();

    public NpuDriverRegistry(List<NpuDriver> driverList) {
        for (NpuDriver driver : driverList) {
            drivers.put(driver.getBackendType(), driver);
            log.info("Registered NPU Hardware Driver: {} (Available: {})", driver.getBackendType(), driver.isAvailable());
        }
    }

    public List<HardwareInfo> getAllHardwareStatus() {
        List<HardwareInfo> infoList = new ArrayList<>();
        for (BackendType backend : DISPLAY_ORDER) {
            NpuDriver driver = drivers.get(backend);
            if (driver != null) {
                infoList.add(driver.probeHardware());
            }
        }
        for (Map.Entry<BackendType, NpuDriver> entry : drivers.entrySet()) {
            if (!DISPLAY_ORDER.contains(entry.getKey())) {
                infoList.add(entry.getValue().probeHardware());
            }
        }
        return infoList;
    }

    public Optional<NpuDriver> getDriver(BackendType backendType) {
        return Optional.ofNullable(drivers.get(backendType));
    }

    public Optional<BackendType> getRecommendedBackend() {
        for (BackendType priority : AUTO_SELECTION_PRIORITY) {
            NpuDriver driver = drivers.get(priority);
            if (driver != null && driver.isAvailable()) {
                return Optional.of(priority);
            }
        }
        return Optional.empty();
    }

    public NpuDriver selectActiveDriver(String preferredBackend) {
        if (preferredBackend != null && !preferredBackend.isBlank() && !"auto".equalsIgnoreCase(preferredBackend)) {
            for (NpuDriver driver : drivers.values()) {
                if (driver.getBackendType().name().equalsIgnoreCase(preferredBackend) ||
                    driver.getBackendType().getDisplayName().toLowerCase().contains(preferredBackend.toLowerCase())) {
                    
                    if (driver.isAvailable()) {
                        log.info("NPU-Only Policy: Selected explicit backend {}", driver.getBackendType());
                        return driver;
                    } else {
                        throw new IllegalStateException("NPU-Only Policy Error: Explicitly requested NPU backend '" 
                                + preferredBackend + "' is NOT available or failed hardware healthcheck!");
                    }
                }
            }
        }

        Optional<BackendType> recommendedBackend = getRecommendedBackend();
        if (recommendedBackend.isPresent()) {
            BackendType backend = recommendedBackend.get();
            log.info("NPU-Only Policy: Auto-selected active NPU hardware accelerator: {}", backend);
            return drivers.get(backend);
        }

        // STRICT NPU-ONLY CONTRACT: No CPU or GPU fallback permitted!
        log.error("STRICT NPU-ONLY FAILURE: No physical NPU hardware accelerator detected or healthy on system!");
        throw new IllegalStateException("NPU-Only Contract Enforced: No supported NPU hardware accelerator is available. CPU/GPU fallback is strictly disabled.");
    }
}
