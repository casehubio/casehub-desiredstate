package io.casehub.desiredstate.yaml;

import io.casehub.desiredstate.api.HookDescriptor;
import io.casehub.desiredstate.api.LifecycleStep;
import io.casehub.desiredstate.yaml.model.YamlNode;
import io.casehub.desiredstate.yaml.resolver.VariableResolver;

import java.util.List;
import java.util.Map;

final class HookResolver {
    private HookResolver() {}

    static HookDescriptor resolveHooks(YamlNode yamlNode, VariableResolver resolver, String nodeId) {
        if (yamlNode.provision() == null && yamlNode.deprovision() == null) {
            return null;
        }

        List<LifecycleStep> provisionPre = resolveSteps(
                yamlNode.provision() != null ? yamlNode.provision().pre() : List.of(), resolver, nodeId);
        List<LifecycleStep> provisionPost = resolveSteps(
                yamlNode.provision() != null ? yamlNode.provision().post() : List.of(), resolver, nodeId);
        List<LifecycleStep> deprovisionPre = resolveSteps(
                yamlNode.deprovision() != null ? yamlNode.deprovision().pre() : List.of(), resolver, nodeId);
        List<LifecycleStep> deprovisionPost = resolveSteps(
                yamlNode.deprovision() != null ? yamlNode.deprovision().post() : List.of(), resolver, nodeId);

        HookDescriptor hooks = new HookDescriptor(provisionPre, provisionPost, deprovisionPre, deprovisionPost);
        return hooks.isEmpty() ? null : hooks;
    }

    private static List<LifecycleStep> resolveSteps(List<Map<String, Object>> steps,
                                                     VariableResolver resolver, String nodeId) {
        return steps.stream().map(step -> resolveStep(step, resolver, nodeId)).toList();
    }

    @SuppressWarnings("unchecked")
    private static LifecycleStep resolveStep(Map<String, Object> step,
                                              VariableResolver resolver, String nodeId) {
        if (step.containsKey("verify")) {
            Map<String, Object> params = (Map<String, Object>) step.get("verify");
            String url = resolver.resolveString((String) params.get("url"), nodeId);
            int timeout = params.containsKey("timeout") ? ((Number) params.get("timeout")).intValue() : 30;
            return new LifecycleStep.Verify(url, timeout);
        }
        if (step.containsKey("notify")) {
            Map<String, Object> params = (Map<String, Object>) step.get("notify");
            String channel = (String) params.get("channel");
            String message = resolver.resolveString((String) params.get("message"), nodeId);
            return new LifecycleStep.Notify(channel, message);
        }
        if (step.containsKey("wait")) {
            Map<String, Object> params = (Map<String, Object>) step.get("wait");
            int seconds = ((Number) params.get("seconds")).intValue();
            return new LifecycleStep.Wait(seconds);
        }
        throw new IllegalArgumentException("Unknown hook step type in node '" + nodeId + "': " + step.keySet());
    }
}
