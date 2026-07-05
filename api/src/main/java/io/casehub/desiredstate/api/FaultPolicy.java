package io.casehub.desiredstate.api;

import java.util.List;

public interface FaultPolicy {
    List<GraphMutation> onFault(FaultEvent event, DesiredStateGraph current, ActualState actual);
}
