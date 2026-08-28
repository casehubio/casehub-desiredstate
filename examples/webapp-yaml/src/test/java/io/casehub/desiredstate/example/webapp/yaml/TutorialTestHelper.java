package io.casehub.desiredstate.example.webapp.yaml;

import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.yaml.model.YamlGraph;
import io.casehub.desiredstate.yaml.model.YamlNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class TutorialTestHelper {

    private TutorialTestHelper() {}

    static GraphDescriptor toGraphDescriptor(YamlGraph yamlGraph, Map<String, String> typeRegistry) {
        List<NodeDescriptor> nodes = new ArrayList<>();
        List<DependencyDescriptor> deps = new ArrayList<>();

        for (Map.Entry<String, YamlNode> entry : yamlGraph.nodes().entrySet()) {
            String nodeId = entry.getKey();
            YamlNode yamlNode = entry.getValue();
            String specClassName = typeRegistry.get(yamlNode.type());

            nodes.add(new NodeDescriptor.InlineNode(
                    nodeId, specClassName,
                    yamlNode.spec() != null ? yamlNode.spec() : Map.of(),
                    yamlNode.humanGating()));

            for (String dep : yamlNode.dependencyNodeIds()) {
                deps.add(new DependencyDescriptor(nodeId, dep));
            }
        }

        return new GraphDescriptor(
                yamlGraph.desiredState().namespace(),
                yamlGraph.desiredState().name(),
                null, null, nodes, deps,
                List.of(), null, List.of(), List.of());
    }
}
