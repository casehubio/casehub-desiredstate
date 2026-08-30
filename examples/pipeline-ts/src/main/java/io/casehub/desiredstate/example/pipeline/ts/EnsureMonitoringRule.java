package io.casehub.desiredstate.example.pipeline.ts;

import io.casehub.desiredstate.annotations.runtime.Direction;
import io.casehub.desiredstate.annotations.GraphRule;
import io.casehub.desiredstate.annotations.Match;
import io.casehub.desiredstate.annotations.NotExists;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.GraphMutations;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.example.pipeline.MonitorSpec;

import java.util.List;

@GraphRule(graph = {"pipeline:*"})
public class EnsureMonitoringRule {

    @GraphRule
    public static List<GraphMutation> ensureMonitoring(
            @Match(type = "sink") DesiredNode sink,
            @NotExists(type = "monitor", of = "sink", direction = Direction.DEPENDENTS) Void guard) {
        return GraphMutations.addNodeDependingOn(
                new DesiredNode(NodeId.of("monitor-" + sink.id().value()),
                        new MonitorSpec(sink.id().value()), HumanGating.NONE),
                sink.id());
    }
}
