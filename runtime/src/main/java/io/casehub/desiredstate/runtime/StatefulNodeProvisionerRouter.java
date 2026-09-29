package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.NodeLifecycleDefinition;
import io.casehub.desiredstate.api.NodeProvisioner;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.platform.api.preferences.PreferenceProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.StreamSupport;

@ApplicationScoped
public class StatefulNodeProvisionerRouter extends DefaultNodeProvisionerRouter {

    private static final Logger LOG = Logger.getLogger(StatefulNodeProvisionerRouter.class.getName());

    protected StatefulNodeProvisionerRouter() {}

    @Inject
    public StatefulNodeProvisionerRouter(Instance<NodeProvisioner> provisioners,
                                          Instance<NodeLifecycleDefinition> lifecycles,
                                          PreferenceProvider preferenceProvider,
                                          CdiTransitionActionHandler actionHandler) {
        super(wrapProvisioners(
                StreamSupport.stream(provisioners.spliterator(), false).toList(),
                StreamSupport.stream(lifecycles.spliterator(), false).toList(),
                actionHandler),
              preferenceProvider);
    }

    public StatefulNodeProvisionerRouter(Collection<NodeProvisioner> provisioners,
                                          Collection<NodeLifecycleDefinition> lifecycles,
                                          TransitionActionHandler actionHandler) {
        super(wrapProvisioners(new ArrayList<>(provisioners), new ArrayList<>(lifecycles), actionHandler));
    }

    private static Collection<NodeProvisioner> wrapProvisioners(
            List<NodeProvisioner> provisioners,
            List<NodeLifecycleDefinition> lifecycles,
            TransitionActionHandler actionHandler) {

        Map<NodeType, NodeLifecycleDefinition> lifecycleMap = new HashMap<>();
        for (var lifecycle : lifecycles) {
            List<String> errors = lifecycle.validate();
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(
                    "Invalid lifecycle definition for " + lifecycle.nodeType().value() + ": " + errors);
            }
            lifecycleMap.put(lifecycle.nodeType(), lifecycle);
        }

        List<NodeProvisioner> result = new ArrayList<>(provisioners.size());
        for (NodeProvisioner prov : provisioners) {
            NodeLifecycleDefinition matched = null;
            for (NodeType type : prov.handledTypes()) {
                matched = lifecycleMap.get(type);
                if (matched != null) break;
            }
            if (matched != null) {
                LOG.log(Level.INFO, "Wrapping provisioner {0} with lifecycle state machine for type {1}",
                    new Object[]{prov.getClass().getSimpleName(), matched.nodeType().value()});
                result.add(new StatefulNodeProvisioner(prov, matched, actionHandler));
            } else {
                result.add(prov);
            }
        }
        return result;
    }
}
