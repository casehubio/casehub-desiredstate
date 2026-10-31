package io.casehub.desiredstate.bridge.ansible;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.bridge.BridgeImportResult;
import io.casehub.desiredstate.bridge.BridgePlan;
import io.casehub.desiredstate.bridge.BridgeProvider;
import io.casehub.desiredstate.bridge.BridgeReadRequest;
import io.casehub.desiredstate.bridge.BridgeRequest;
import io.casehub.desiredstate.bridge.BridgeResult;
import io.casehub.desiredstate.bridge.BridgeStateResult;
import io.casehub.desiredstate.bridge.ChangeAction;
import io.casehub.desiredstate.bridge.BridgeErrorCategory;
import io.casehub.desiredstate.bridge.ImportCapable;
import io.casehub.desiredstate.bridge.PlanCapable;
import io.casehub.desiredstate.bridge.ResourceChange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AnsibleBridgeProvider implements BridgeProvider, ImportCapable, PlanCapable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, AnsibleModuleConfig> moduleConfigs;
    private final String binaryPath;
    private final ProcessExecutor processExecutor;

    public AnsibleBridgeProvider(Map<String, AnsibleModuleConfig> moduleConfigs, String binaryPath) {
        this(moduleConfigs, binaryPath, AnsibleProcess::execute);
    }

    public AnsibleBridgeProvider(Map<String, AnsibleModuleConfig> moduleConfigs,
                                  String binaryPath, ProcessExecutor processExecutor) {
        this.moduleConfigs = moduleConfigs;
        this.binaryPath = binaryPath;
        this.processExecutor = processExecutor;
    }

    @Override
    public String protocolId() {
        return "ansible";
    }

    @Override
    public Set<String> supportedTypes() {
        return moduleConfigs.keySet();
    }

    @Override
    public BridgeResult apply(BridgeRequest request) {
        var config = requireConfig(request.typeId());
        var specParts = splitSpec(request.spec());
        String playbook = PlaybookGenerator.provision(config.fqProvisionModule(), specParts.moduleParams);

        return executeAndMapResult(playbook, specParts, config, request.tenancyId());
    }

    @Override
    public BridgeResult destroy(BridgeRequest request) {
        var config = requireConfig(request.typeId());
        var specParts = splitSpec(request.spec());

        String fqModule = "deprovision_module".equals(config.deprovisionStrategy())
            ? config.fqDeprovisionModule() : config.fqProvisionModule();
        String playbook = PlaybookGenerator.deprovision(fqModule, specParts.moduleParams,
            config.deprovisionStrategy());

        var pbResult = executePlaybook(playbook, specParts, request.tenancyId());
        if (pbResult.succeeded()) {
            return new BridgeResult.Success(Map.of());
        }
        return new BridgeResult.Failed(pbResult.taskResult().msg(),
            AnsibleErrorClassifier.classify(pbResult.taskResult()));
    }

    @Override
    public BridgeStateResult readState(BridgeReadRequest request) {
        var config = requireConfig(request.typeId());
        if (config.fqInfoModule() == null) {
            return new BridgeStateResult.NotFound();
        }

        Object filterValue = request.previousState().get(extractResourceIdKey(config));
        if (filterValue == null) {
            return new BridgeStateResult.NotFound();
        }

        var specParts = splitSpec(request.desiredSpec());
        String playbook = PlaybookGenerator.readState(config.fqInfoModule(),
            config.infoFilterKey(), asList(filterValue));

        var pbResult = executePlaybook(playbook, specParts, request.tenancyId());
        if (!pbResult.succeeded()) {
            return new BridgeStateResult.Error(pbResult.taskResult().msg());
        }

        var outputState = AnsibleOutputParser.extractOutputState(pbResult.taskResult(), config);
        if (outputState.isEmpty()) {
            return new BridgeStateResult.NotFound();
        }

        boolean drifted = AnsibleOutputParser.detectDrift(outputState,
            request.desiredSpec(), config.driftKeys());
        outputState.put("status", drifted ? "drifted" : "present");
        return new BridgeStateResult.Found(outputState);
    }

    @Override
    public BridgeImportResult importResource(String typeId, String importId, String tenancyId) {
        var config = requireConfig(typeId);
        if (config.fqInfoModule() == null) {
            return new BridgeImportResult.Failed("No info module configured for " + typeId);
        }

        var specParts = splitSpec(Map.of());
        String playbook = PlaybookGenerator.readState(config.fqInfoModule(),
            config.infoFilterKey(), asList(importId));

        var pbResult = executePlaybook(playbook, specParts, tenancyId);
        if (!pbResult.succeeded()) {
            return new BridgeImportResult.Failed(pbResult.taskResult().msg());
        }

        var state = AnsibleOutputParser.extractOutputState(pbResult.taskResult(), config);
        if (state.isEmpty()) {
            return new BridgeImportResult.Failed("Resource not found: " + importId);
        }
        return new BridgeImportResult.Success(state);
    }

    @Override
    public BridgePlan plan(BridgeRequest request) {
        var config = requireConfig(request.typeId());
        var specParts = splitSpec(request.spec());
        String playbook = PlaybookGenerator.check(config.fqProvisionModule(), specParts.moduleParams);

        var pbResult = executePlaybook(playbook, specParts, request.tenancyId());
        String summary = pbResult.succeeded()
            ? (pbResult.taskResult().changed() ? "Changes detected" : "No changes")
            : "Check mode failed: " + pbResult.taskResult().msg();

        return new BridgePlan(request.resourceId(), summary,
            List.of(new ResourceChange(request.typeId(), request.resourceId(),
                pbResult.taskResult().changed() ? ChangeAction.UPDATE : ChangeAction.CREATE)),
            pbResult);
    }

    @Override
    public BridgeResult applyPlan(BridgePlan plan) {
        if (!(plan.rawPlan() instanceof AnsiblePlaybookResult cachedResult)) {
            return new BridgeResult.Failed("Invalid plan reference", BridgeErrorCategory.CONFIGURATION);
        }
        return cachedResult.succeeded()
            ? new BridgeResult.Success(Map.of())
            : new BridgeResult.Failed(cachedResult.taskResult().msg(),
                AnsibleErrorClassifier.classify(cachedResult.taskResult()));
    }

    private BridgeResult executeAndMapResult(String playbook, SpecParts specParts,
                                              AnsibleModuleConfig config, String tenancyId) {
        var pbResult = executePlaybook(playbook, specParts, tenancyId);
        if (pbResult.succeeded()) {
            var outputState = AnsibleOutputParser.extractOutputState(pbResult.taskResult(), config);
            return new BridgeResult.Success(outputState);
        }
        return new BridgeResult.Failed(pbResult.taskResult().msg(),
            AnsibleErrorClassifier.classify(pbResult.taskResult()));
    }

    private AnsiblePlaybookResult executePlaybook(String playbook, SpecParts specParts,
                                                   String tenancyId) {
        Path tempDir = null;
        try {
            tempDir = AnsibleProcess.createTempDir();
            Path playbookFile = tempDir.resolve("playbook.yml");
            Path inventoryFile = tempDir.resolve("inventory.json");
            Files.writeString(playbookFile, playbook);
            Files.writeString(inventoryFile, InventoryGenerator.generate(specParts.reservedKeys));

            List<String> command = new ArrayList<>();
            command.add(binaryPath);
            command.addAll(List.of("-i", inventoryFile.toString(), playbookFile.toString()));

            Map<String, String> env = new HashMap<>();
            if (specParts.extraVars != null && !specParts.extraVars.isEmpty()) {
                Path extraVarsFile = tempDir.resolve("extra_vars.json");
                Files.writeString(extraVarsFile, JSON.writeValueAsString(specParts.extraVars));
                command.addAll(List.of("--extra-vars", "@" + extraVarsFile));
            }
            if (specParts.environment != null) {
                specParts.environment.forEach((k, v) -> env.put(k, String.valueOf(v)));
            }

            int timeout = specParts.timeout;
            var processResult = processExecutor.execute(tempDir, command, env, timeout);

            if (processResult.exitCode() == -1 && processResult.stderr().contains("timed out")) {
                return new AnsiblePlaybookResult(
                    new AnsibleTaskResult("timeout", processResult.stderr(), false), false);
            }

            return AnsibleOutputParser.parse(processResult.stdout());
        } catch (IOException e) {
            return new AnsiblePlaybookResult(
                new AnsibleTaskResult("failed", "IO error: " + e.getMessage(), false), false);
        } finally {
            AnsibleProcess.cleanupTempDir(tempDir);
        }
    }

    private AnsibleModuleConfig requireConfig(String typeId) {
        var config = moduleConfigs.get(typeId);
        if (config == null) {
            throw new IllegalArgumentException("No Ansible module config for type: " + typeId);
        }
        return config;
    }

    private String extractResourceIdKey(AnsibleModuleConfig config) {
        if (config.outputKeys().isEmpty()) return config.infoFilterKey();
        return config.outputKeys().get(0);
    }

    @SuppressWarnings("unchecked")
    private SpecParts splitSpec(Map<String, Object> spec) {
        Map<String, Object> reserved = new LinkedHashMap<>();
        Map<String, Object> moduleParams = new LinkedHashMap<>();
        Map<String, Object> extraVars = null;
        Map<String, Object> environment = null;
        int timeout = 300;

        for (var entry : spec.entrySet()) {
            if (entry.getKey().startsWith("_")) {
                reserved.put(entry.getKey(), entry.getValue());
                switch (entry.getKey()) {
                    case "_extra_vars" -> extraVars = (Map<String, Object>) entry.getValue();
                    case "_environment" -> environment = (Map<String, Object>) entry.getValue();
                    case "_timeout" -> timeout = ((Number) entry.getValue()).intValue();
                }
            } else {
                moduleParams.put(entry.getKey(), entry.getValue());
            }
        }

        return new SpecParts(reserved, moduleParams, extraVars, environment, timeout);
    }

    private static Object asList(Object value) {
        if (value instanceof List) return value;
        return List.of(value);
    }

    record SpecParts(Map<String, Object> reservedKeys, Map<String, Object> moduleParams,
                     Map<String, Object> extraVars, Map<String, Object> environment, int timeout) {}
}
