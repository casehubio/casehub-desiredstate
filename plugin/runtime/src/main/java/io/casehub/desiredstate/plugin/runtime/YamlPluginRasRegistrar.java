package io.casehub.desiredstate.plugin.runtime;

import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.desiredstate.plugin.model.PluginRasDef;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationDefinitionProvider;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.api.TriggerMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class YamlPluginRasRegistrar implements SituationDefinitionProvider {

    private static final String NODE_FAULT_GANGLION_ID = "desiredstate-node-fault";
    private static final String PERSISTENT_DRIFT_GANGLION_ID = "desiredstate-persistent-drift";

    private static final Map<String, String> EVENT_TYPE_MAP = Map.of(
        "NODE_FAULTED", DesiredStateEventTypes.NODE_FAULTED,
        "NODE_RECOVERED", DesiredStateEventTypes.NODE_RECOVERED,
        "NODE_DRIFTED", DesiredStateEventTypes.NODE_DRIFTED,
        "RECONCILIATION_COMPLETED", DesiredStateEventTypes.RECONCILIATION_COMPLETED
    );

    private static final Map<String, String> EVENT_TO_GANGLION = Map.of(
        "NODE_FAULTED", NODE_FAULT_GANGLION_ID,
        "NODE_RECOVERED", NODE_FAULT_GANGLION_ID,
        "NODE_DRIFTED", PERSISTENT_DRIFT_GANGLION_ID
    );

    private final String pluginType;
    private final PluginRasDef rasDef;

    public YamlPluginRasRegistrar(String pluginType, PluginRasDef rasDef) {
        this.pluginType = pluginType;
        this.rasDef = rasDef;
    }

    @Override
    public List<SituationRegistration> registrations() {
        List<SituationRegistration> result = new ArrayList<>();
        for (PluginRasDef.Situation sit : rasDef.situations()) {
            result.add(new SituationRegistration(buildDefinition(sit)));
        }
        return result;
    }

    SituationDefinition buildDefinition(PluginRasDef.Situation sit) {
        String situationId = "plugin." + pluginType + "." + sit.name();
        Set<String> eventTypes = mapEventTypes(sit.events());
        Duration correlationWindow = parseDuration(sit.correlationWindow());
        ChainMode chainMode = mapChainMode(sit.chainMode(), sit.events());
        TriggerAction triggerAction = mapTriggerAction(sit.trigger());
        TriggerMode triggerMode = mapTriggerMode(sit.triggerMode());

        return new SituationDefinition(
            situationId, eventTypes, correlationWindow, null,
            chainMode, triggerAction, triggerMode);
    }

    static Set<String> mapEventTypes(List<String> events) {
        Set<String> result = new HashSet<>();
        for (String event : events) {
            String mapped = EVENT_TYPE_MAP.get(event);
            if (mapped == null) {
                throw new IllegalArgumentException(
                    "Unknown event type: " + event + ". Known: " + EVENT_TYPE_MAP.keySet());
            }
            result.add(mapped);
        }
        return result;
    }

    static ChainMode mapChainMode(Map<String, Object> chainModeYaml, List<String> events) {
        if (chainModeYaml.containsKey("streak")) {
            int count = ((Number) chainModeYaml.get("streak")).intValue();
            String ganglionId = inferGanglionId(events);
            return new ChainMode.Streak(ganglionId, count);
        }
        if (chainModeYaml.containsKey("count")) {
            int count = ((Number) chainModeYaml.get("count")).intValue();
            String ganglionId = inferGanglionId(events);
            return new ChainMode.Count(ganglionId, count);
        }
        if (chainModeYaml.containsKey("rate")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> rateConfig = (Map<String, Object>) chainModeYaml.get("rate");
            double threshold = ((Number) rateConfig.get("threshold")).doubleValue();
            int window = ((Number) rateConfig.get("window")).intValue();
            Set<String> ganglia = inferGangliaSet(events);
            return new ChainMode.Rate(ganglia, threshold, window);
        }
        throw new IllegalArgumentException(
            "Unknown chain-mode: " + chainModeYaml.keySet()
                + ". Supported: streak, count, rate");
    }

    static TriggerAction mapTriggerAction(String trigger) {
        if (trigger == null || "create-case".equals(trigger)) {
            return new TriggerAction.CreateCase(
                new CaseTriggerConfig("desiredstate", "plugin-response", "1.0", Map.of()));
        }
        if ("emit-event".equals(trigger) || "notify-only".equals(trigger)) {
            return new TriggerAction.NotifyOnly();
        }
        throw new IllegalArgumentException("Unknown trigger: " + trigger);
    }

    static TriggerMode mapTriggerMode(String triggerMode) {
        if (triggerMode == null || "fire-once".equals(triggerMode)) {
            return new TriggerMode.FireOnce();
        }
        if (triggerMode.startsWith("repeating:")) {
            Duration cooldown = parseDuration(triggerMode.substring("repeating:".length()).trim());
            return new TriggerMode.Repeating(cooldown);
        }
        throw new IllegalArgumentException("Unknown trigger-mode: " + triggerMode);
    }

    private static String inferGanglionId(List<String> events) {
        Set<String> ganglia = new HashSet<>();
        for (String event : events) {
            if ("NODE_RECOVERED".equals(event)) continue;
            String ganglion = EVENT_TO_GANGLION.get(event);
            if (ganglion != null) ganglia.add(ganglion);
        }
        if (ganglia.size() != 1) {
            throw new IllegalArgumentException(
                "streak/count chain-mode requires events mapping to exactly one ganglion, "
                    + "but got " + ganglia + " from events " + events);
        }
        return ganglia.iterator().next();
    }

    private static Set<String> inferGangliaSet(List<String> events) {
        Set<String> ganglia = new HashSet<>();
        for (String event : events) {
            if ("NODE_RECOVERED".equals(event)) continue;
            String ganglion = EVENT_TO_GANGLION.get(event);
            if (ganglion != null) ganglia.add(ganglion);
        }
        return ganglia;
    }

    static Duration parseDuration(String duration) {
        if (duration == null) return Duration.ofMinutes(5);
        if (duration.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(
                duration.substring(0, duration.length() - 1)));
        }
        if (duration.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(
                duration.substring(0, duration.length() - 1)));
        }
        if (duration.endsWith("h")) {
            return Duration.ofHours(Long.parseLong(
                duration.substring(0, duration.length() - 1)));
        }
        return Duration.parse("PT" + duration.toUpperCase());
    }
}
