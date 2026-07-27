package com.npuhub.web.controller;

import com.npuhub.service.HardwareDiscoveryService;
import com.npuhub.service.ModelManagementService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardWebController {

    private final ModelManagementService modelManagementService;
    private final HardwareDiscoveryService hardwareDiscoveryService;

    public DashboardWebController(ModelManagementService modelManagementService, HardwareDiscoveryService hardwareDiscoveryService) {
        this.modelManagementService = modelManagementService;
        this.hardwareDiscoveryService = hardwareDiscoveryService;
    }

    @GetMapping("/")
    public String dashboard(Model model) {
        var allModels = modelManagementService.listAllModels();

        java.util.Map<String, java.util.List<com.npuhub.core.model.ModelMetadata>> groupedModels = new java.util.LinkedHashMap<>();
        groupedModels.put("ROCKCHIP", new java.util.ArrayList<>());
        groupedModels.put("OPENVINO", new java.util.ArrayList<>());
        groupedModels.put("QUALCOMM", new java.util.ArrayList<>());
        groupedModels.put("RYZENAI", new java.util.ArrayList<>());

        for (var m : allModels) {
            String backend = m.compatibleBackend().name();
            groupedModels.computeIfAbsent(backend, k -> new java.util.ArrayList<>()).add(m);
        }

        model.addAttribute("groupedModels", groupedModels);
        model.addAttribute("rockchipQuantizations", modelManagementService.getRockchipQuantizations());
        model.addAttribute("hardwareList", hardwareDiscoveryService.scanHardware());
        model.addAttribute("diagnostics", hardwareDiscoveryService.getSystemDiagnosticDetails());
        return "index";
    }
}
