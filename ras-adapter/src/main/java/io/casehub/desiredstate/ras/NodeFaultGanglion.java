package io.casehub.desiredstate.ras;

import io.casehub.desiredstate.api.DesiredStateEventTypes;
import io.casehub.ras.api.JavaSwitchGanglion;
import io.casehub.ras.api.DetectionResult;
import io.casehub.ras.api.SituationContext;
import io.cloudevents.CloudEvent;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

@ApplicationScoped
public class NodeFaultGanglion extends JavaSwitchGanglion {

    public static final String ID = "desiredstate-node-fault";

    public NodeFaultGanglion() {
        super(ID, Set.of(
            DesiredStateEventTypes.NODE_FAULTED,
            DesiredStateEventTypes.NODE_RECOVERED));
    }

    @Override
    protected DetectionResult evaluate(CloudEvent event, SituationContext context) {
        return switch (event.getType()) {
            case DesiredStateEventTypes.NODE_FAULTED -> detected(1.0);
            case DesiredStateEventTypes.NODE_RECOVERED -> anti(1.0);
            default -> noise();
        };
    }
}
