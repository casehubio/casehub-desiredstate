package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.LifecycleManager;
import io.casehub.eidos.org.api.OrgRegistry;
import io.casehub.eidos.org.api.spi.OrgRegistrar;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.List;

@ApplicationScoped
public class OrgBootstrap {

    private final OrgRegistry              registry;
    private final List<OrgRegistrar>       registrars;
    private final OrgGoalStore             goalStore;
    private final OrgGoalCompiler          compiler;
    private final DesiredStateGraphFactory graphFactory;
    private final LifecycleManager         lifecycleManager;

    @Inject
    public OrgBootstrap(OrgRegistry registry, @Any Instance<OrgRegistrar> registrars,
                        OrgGoalStore goalStore, OrgGoalCompiler compiler,
                        DesiredStateGraphFactory graphFactory,
                        LifecycleManager lifecycleManager) {
        this(registry, registrars.stream().toList(), goalStore, compiler, graphFactory, lifecycleManager);
    }

    OrgBootstrap(OrgRegistry registry, List<OrgRegistrar> registrars,
                 OrgGoalStore goalStore, OrgGoalCompiler compiler,
                 DesiredStateGraphFactory graphFactory,
                 LifecycleManager lifecycleManager) {
        this.registry         = registry;
        this.registrars       = registrars;
        this.goalStore        = goalStore;
        this.compiler         = compiler;
        this.graphFactory     = graphFactory;
        this.lifecycleManager = lifecycleManager;
    }

    void onStartup(@Observes StartupEvent ev) {
        onStartup();
    }

    void onStartup() {
        var goalsByTenancy = new HashMap<String, OrgGoal>();

        for (var registrar : registrars) {
            var def = registrar.organization();
            def.units().forEach(unit -> {
                registry.registerUnit(unit);
                goalsByTenancy.compute(unit.tenancyId(), (tid, goal) -> {
                    var g = goal != null ? goal : OrgGoal.empty();
                    return g.withUnitGoal(new OrgUnitGoal(unit));
                });
            });
            def.relationships().forEach(rel -> {
                registry.addRelationship(rel);
                goalsByTenancy.compute(rel.tenancyId(), (tid, goal) -> {
                    var g = goal != null ? goal : OrgGoal.empty();
                    return g.withRelationship(rel);
                });
            });
        }

        for (var entry : goalsByTenancy.entrySet()) {
            String  tenancyId = entry.getKey();
            OrgGoal goal      = goalStore.load(tenancyId).orElse(entry.getValue());

            CompilationResult result = compiler.compile(goal, graphFactory);
            lifecycleManager.start(tenancyId, result);

            if (goalStore.load(tenancyId).isEmpty()) {
                goalStore.store(tenancyId, goal);
            }
        }
    }
}
