package io.casehub.desiredstate.persistence.jpa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.HookDescriptor;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.LifecycleStep;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.OrderingConstraint;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.TargetStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

class GraphSerializer {

    private static final Logger LOG = Logger.getLogger(GraphSerializer.class.getName());
    private final ObjectMapper mapper = new ObjectMapper();

    String serialize(DesiredStateGraph graph) {
        ObjectNode root = mapper.createObjectNode();

        ArrayNode nodesArray = root.putArray("nodes");
        for (var entry : graph.nodes().entrySet()) {
            DesiredNode node    = entry.getValue();
            ObjectNode  nodeObj = nodesArray.addObject();
            nodeObj.put("id", node.id().value());
            nodeObj.put("specClass", node.spec().getClass().getName());
            nodeObj.set("spec", mapper.valueToTree(node.spec()));
            nodeObj.put("humanGating", node.humanGating().name());
            nodeObj.put("targetStatus", node.targetStatus().name());
            if (node.hooks() != null && !node.hooks().isEmpty()) {
                nodeObj.set("hooks", serializeHooks(node.hooks()));
            } else {
                nodeObj.putNull("hooks");
            }
        }

        ArrayNode depsArray = root.putArray("dependencies");
        for (Dependency dep : graph.dependencies()) {
            ObjectNode depObj = depsArray.addObject();
            depObj.put("from", dep.from().value());
            depObj.put("to", dep.to().value());
        }

        if (!graph.orderingConstraints().isEmpty()) {
            ArrayNode constraintsArray = root.putArray("orderingConstraints");
            for (OrderingConstraint oc : graph.orderingConstraints()) {
                ObjectNode ocObj = constraintsArray.addObject();
                ocObj.put("before", oc.before().value());
                ocObj.put("after", oc.after().value());
            }
        }

        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize DesiredStateGraph", e);
        }
    }

    DesiredStateGraph deserialize(String json, DesiredStateGraphFactory factory) {
        try {
            JsonNode root = mapper.readTree(json);

            List<DesiredNode> nodes = new ArrayList<>();
            for (JsonNode nodeJson : root.get("nodes")) {
                String      id            = nodeJson.get("id").asText();
                String      specClassName = nodeJson.get("specClass").asText();
                Class<?>    specClass     = Class.forName(specClassName);
                NodeSpec    spec          = (NodeSpec) mapper.treeToValue(nodeJson.get("spec"), specClass);
                HumanGating gating        = HumanGating.valueOf(nodeJson.get("humanGating").asText());
                TargetStatus targetStatus = nodeJson.has("targetStatus")
                                            ? TargetStatus.valueOf(nodeJson.get("targetStatus").asText())
                                            : TargetStatus.ACTIVE;
                HookDescriptor hooks = deserializeHooks(nodeJson.get("hooks"));
                nodes.add(new DesiredNode(NodeId.of(id), spec, gating, hooks, targetStatus));
            }

            List<Dependency> deps = new ArrayList<>();
            for (JsonNode depJson : root.get("dependencies")) {
                deps.add(new Dependency(
                        NodeId.of(depJson.get("from").asText()),
                        NodeId.of(depJson.get("to").asText())
                ));
            }

            JsonNode constraintsJson = root.get("orderingConstraints");
            if (constraintsJson != null && constraintsJson.isArray() && !constraintsJson.isEmpty()) {
                java.util.Set<OrderingConstraint> constraints = new java.util.LinkedHashSet<>();
                for (JsonNode ocJson : constraintsJson) {
                    constraints.add(new OrderingConstraint(
                            NodeType.of(ocJson.get("before").asText()),
                            NodeType.of(ocJson.get("after").asText())
                    ));
                }
                return factory.of(nodes, deps, constraints);
            }

            return factory.of(nodes, deps);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to deserialize DesiredStateGraph: " + e.getMessage(), e);
            return null;
        }
    }

    private ObjectNode serializeHooks(HookDescriptor hooks) {
        ObjectNode obj = mapper.createObjectNode();
        obj.set("provisionPre", serializeSteps(hooks.provisionPre()));
        obj.set("provisionPost", serializeSteps(hooks.provisionPost()));
        obj.set("deprovisionPre", serializeSteps(hooks.deprovisionPre()));
        obj.set("deprovisionPost", serializeSteps(hooks.deprovisionPost()));
        return obj;
    }

    private ArrayNode serializeSteps(List<LifecycleStep> steps) {
        ArrayNode arr = mapper.createArrayNode();
        for (LifecycleStep step : steps) {
            ObjectNode stepObj = arr.addObject();
            stepObj.put("stepClass", step.getClass().getName());
            stepObj.set("data", mapper.valueToTree(step));
        }
        return arr;
    }

    private HookDescriptor deserializeHooks(JsonNode hooksJson) throws Exception {
        if (hooksJson == null || hooksJson.isNull()) return null;
        return new HookDescriptor(
                deserializeSteps(hooksJson.get("provisionPre")),
                deserializeSteps(hooksJson.get("provisionPost")),
                deserializeSteps(hooksJson.get("deprovisionPre")),
                deserializeSteps(hooksJson.get("deprovisionPost"))
        );
    }

    private List<LifecycleStep> deserializeSteps(JsonNode stepsJson) throws Exception {
        if (stepsJson == null || stepsJson.isNull()) return List.of();
        List<LifecycleStep> steps = new ArrayList<>();
        for (JsonNode stepJson : stepsJson) {
            String stepClassName = stepJson.get("stepClass").asText();
            Class<?> stepClass = Class.forName(stepClassName);
            steps.add((LifecycleStep) mapper.treeToValue(stepJson.get("data"), stepClass));
        }
        return steps;
    }
}
