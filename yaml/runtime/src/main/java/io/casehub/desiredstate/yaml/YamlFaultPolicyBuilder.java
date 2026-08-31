package io.casehub.desiredstate.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.FaultCountStore;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.GraphMutations;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.ThresholdFaultPolicy;
import io.casehub.desiredstate.api.TypedFaultPolicy;
import io.casehub.desiredstate.yaml.model.YamlFaultPolicy;
import io.casehub.desiredstate.yaml.model.YamlFaultTier;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class YamlFaultPolicyBuilder {

    private static final Logger LOG = Logger.getLogger(YamlFaultPolicyBuilder.class);

    public static ThresholdFaultPolicy build(
            YamlFaultPolicy yamlPolicy,
            Map<String, String> typeRegistryMap,
            FaultCountStore faultCountStore) {

        ObjectMapper coercionMapper = createCoercionMapper();

        var builder = ThresholdFaultPolicy.builder()
                .faultTypes(yamlPolicy.faultTypes().stream()
                        .map(FaultType::valueOf).collect(Collectors.toSet()))
                .namespace(yamlPolicy.namespace())
                .faultCountStore(faultCountStore);

        if (!yamlPolicy.nodeTypes().isEmpty()) {
            builder.nodeTypes(yamlPolicy.nodeTypes().stream()
                    .map(NodeType::of).collect(Collectors.toSet()));
        }
        if (!yamlPolicy.ignoreTypes().isEmpty()) {
            builder.ignoreTypes(yamlPolicy.ignoreTypes().stream()
                    .map(NodeType::of).collect(Collectors.toSet()));
        }

        for (YamlFaultTier tier : yamlPolicy.tiers()) {
            NodeType outputType = NodeType.of(tier.reviewNode().type());
            String className = typeRegistryMap.get(tier.reviewNode().type());
            if (className == null) {
                throw new IllegalArgumentException("Unknown node type in fault policy tier: '"
                        + tier.reviewNode().type() + "'. Available: " + typeRegistryMap.keySet());
            }
            @SuppressWarnings("unchecked")
            Class<? extends NodeSpec> specClass = loadSpecClass(className);
            Map<String, Object> specTemplate = tier.reviewNode().spec();
            HumanGating gating = tier.reviewNode().humanGating();

            TypedFaultPolicy action = createTemplateTierAction(
                    outputType, specClass, specTemplate, gating, coercionMapper);

            builder.tier(tier.threshold(), action);
        }

        return builder.build();
    }

    private static TypedFaultPolicy createTemplateTierAction(
            NodeType outputType,
            Class<? extends NodeSpec> specClass,
            Map<String, Object> specTemplate,
            HumanGating gating,
            ObjectMapper coercionMapper) {

        return new TypedFaultPolicy() {
            @Override
            public NodeType outputNodeType() {
                return outputType;
            }

            @Override
            public List<GraphMutation> onFault(String tenancyId, FaultEvent event,
                    io.casehub.desiredstate.api.DesiredStateGraph current,
                    io.casehub.desiredstate.api.ActualState actual) {

                NodeId reviewId = NodeId.of(outputType.value() + "-" + event.node().value());
                if (current.nodes().containsKey(reviewId)) {
                    return List.of();
                }

                Map<String, Object> resolved = resolveFaultTemplate(specTemplate, event);
                try {
                    NodeSpec spec = coercionMapper.convertValue(resolved, specClass);
                    DesiredNode node = new DesiredNode(reviewId, spec, gating);
                    return GraphMutations.addNodeDependingOn(node, event.node());
                } catch (IllegalArgumentException e) {
                    LOG.warnf("Fault policy template deserialization failed for type '%s': %s",
                            outputType.value(), e.getMessage());
                    return List.of();
                }
            }
        };
    }

    private static Map<String, Object> resolveFaultTemplate(
            Map<String, Object> template, FaultEvent event) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (var entry : template.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof String s) {
                resolved.put(entry.getKey(), resolveFaultString(s, event));
            } else if (val instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) nested;
                resolved.put(entry.getKey(), resolveFaultTemplate(nestedMap, event));
            } else {
                resolved.put(entry.getKey(), val);
            }
        }
        return resolved;
    }

    private static String resolveFaultString(String template, FaultEvent event) {
        return template
                .replace("${fault.nodeId}", event.node().value())
                .replace("${fault.type}", event.type().name())
                .replace("${fault.detail}", event.detail() != null ? event.detail() : "");
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends NodeSpec> loadSpecClass(String className) {
        try {
            return (Class<? extends NodeSpec>) Thread.currentThread()
                                                     .getContextClassLoader().loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("NodeSpec class not found: " + className, e);
        }
    }


    private static ObjectMapper createCoercionMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.coercionConfigDefaults()
              .setCoercion(CoercionInputShape.String, CoercionAction.TryConvert);
        // Support String → NodeId deserialization for review spec fields like targetNodeId
        com.fasterxml.jackson.databind.module.SimpleModule module =
                new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addDeserializer(io.casehub.desiredstate.api.NodeId.class,
                               new com.fasterxml.jackson.databind.deser.std.StdDeserializer<>(
                                       io.casehub.desiredstate.api.NodeId.class) {
                                   @Override
                                   public io.casehub.desiredstate.api.NodeId deserialize(
                                           com.fasterxml.jackson.core.JsonParser p,
                                           com.fasterxml.jackson.databind.DeserializationContext ctxt)
                                           throws java.io.IOException {
                                       return io.casehub.desiredstate.api.NodeId.of(p.getValueAsString());
                                   }
                               });
        mapper.registerModule(module);
        return mapper;
    }
}
