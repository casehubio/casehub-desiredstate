package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.FaultType;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.Phase;
import io.casehub.desiredstate.api.ThresholdFaultPolicy;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import org.jboss.logging.Logger;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Recorder
public class DesiredStateGraphRecorder {

    private static final Logger LOG = Logger.getLogger(DesiredStateGraphRecorder.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RuntimeValue<GoalCompiler> createGoalCompiler(GraphDescriptor descriptor) {
        try {
            List<Dependency> capturedDeps = buildDependencies(descriptor);
            @SuppressWarnings("rawtypes")
            RuntimeValue<GoalCompiler> runtimeValue;

            if (descriptor.implClassName() == null) {
                List<DesiredNode> capturedNodes = buildClassOnlyNodes(descriptor);
                runtimeValue = new RuntimeValue<>((GoalCompiler) (goals, factory) ->
                                                                         CompilationResult.single(factory.of(capturedNodes, capturedDeps)));
            } else {
                Class<?> implClass = Thread.currentThread().getContextClassLoader()
                                           .loadClass(descriptor.implClassName());
                Object instance = implClass.getDeclaredConstructor().newInstance();

                List<DesiredNode> capturedNodes    = buildNodes(implClass, instance, descriptor);
                List<Method>      graphCustomizers = findGraphCustomizers(implClass);

                if (descriptor.goalMethod() == null) {
                    runtimeValue = new RuntimeValue<>((GoalCompiler) (goals, factory) -> {
                        try {
                            DesiredStateGraph graph = factory.of(capturedNodes, capturedDeps);
                            for (Method customizer : graphCustomizers) {
                                graph = (DesiredStateGraph) customizer.invoke(null, graph);
                            }
                            return CompilationResult.single(graph);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to compile annotated graph: "
                                                       + descriptor.interfaceName(), e);
                        }
                    });
                } else {
                    GoalMethodDescriptor gmd = descriptor.goalMethod();
                    Class<?> goalsType = Thread.currentThread().getContextClassLoader()
                                               .loadClass(gmd.goalsTypeName());
                    Method goalMethod = gmd.hasFactoryParam()
                                        ? implClass.getMethod(gmd.methodName(), goalsType,
                                                              DesiredStateGraph.class, DesiredStateGraphFactory.class)
                                        : implClass.getMethod(gmd.methodName(), goalsType, DesiredStateGraph.class);

                    runtimeValue = new RuntimeValue<>((GoalCompiler) (goals, factory) -> {
                        try {
                            DesiredStateGraph base = factory.of(capturedNodes, capturedDeps);
                            for (Method customizer : graphCustomizers) {
                                base = (DesiredStateGraph) customizer.invoke(null, base);
                            }

                            Object result = gmd.hasFactoryParam()
                                            ? goalMethod.invoke(instance, goals, base, factory)
                                            : goalMethod.invoke(instance, goals, base);

                            if (gmd.returnsCompilationResult()) {
                                return (CompilationResult) result;
                            }
                            return CompilationResult.single((DesiredStateGraph) result);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to compile composable graph: "
                                                       + descriptor.interfaceName(), e);
                        }
                    });
                }
            }

            if (!descriptor.graphRules().isEmpty()) {
                List<ResolvedRule> resolvedRules = resolveRules(descriptor.graphRules());
                @SuppressWarnings("rawtypes")
                GoalCompiler inner = runtimeValue.getValue();
                runtimeValue = new RuntimeValue<>((GoalCompiler) (goals, factory) ->
                                                                         applyGraphRulesToResult(inner.compile(goals, factory), resolvedRules));
            }

            if (!descriptor.graphInvariants().isEmpty()) {
                List<ResolvedInvariant> resolvedInvariants = resolveInvariants(descriptor.graphInvariants());
                GraphInvariantEngine invariantEngine = new GraphInvariantEngine();
                @SuppressWarnings("rawtypes")
                GoalCompiler inner = runtimeValue.getValue();
                runtimeValue = new RuntimeValue<>((GoalCompiler) (goals, factory) ->
                        validateInvariantsOnResult(inner.compile(goals, factory), resolvedInvariants, invariantEngine));
            }

            return runtimeValue;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize annotated desired-state graph: "
                                       + (descriptor.interfaceName() != null ? descriptor.interfaceName()
                                                                             : descriptor.namespace() + ":" + descriptor.name()), e);
        }}

    private CompilationResult applyGraphRulesToResult(CompilationResult result,
                                                      List<ResolvedRule> rules) {
        GraphRuleEngine engine = new GraphRuleEngine();
        return switch (result) {
            case CompilationResult.SingleGraph sg -> CompilationResult.single(engine.evaluate(sg.graph(), rules));
            case CompilationResult.Lifecycle lc -> {
                List<Phase> rewritten = new ArrayList<>();
                for (Phase phase : lc.phases()) {
                    rewritten.add(new Phase(phase.id(),
                                            engine.evaluate(phase.graph(), rules), phase.completionCondition()));
                }
                yield CompilationResult.lifecycle(rewritten);
            }
        };
    }

    private CompilationResult validateInvariantsOnResult(CompilationResult result,
                                                         List<ResolvedInvariant> invariants, GraphInvariantEngine engine) {
        switch (result) {
            case CompilationResult.SingleGraph sg -> engine.validate(sg.graph(), invariants);
            case CompilationResult.Lifecycle lc -> {
                for (Phase phase : lc.phases()) {
                    engine.validate(phase.graph(), invariants);
                }
            }
        }
        return result;
    }

    private List<ResolvedInvariant> resolveInvariants(List<GraphInvariantDescriptor> descriptors) {
        List<ResolvedInvariant> invariants  = new ArrayList<>();
        ClassLoader             classLoader = Thread.currentThread().getContextClassLoader();
        for (GraphInvariantDescriptor gid : descriptors) {
            try {
                Class<?> cls = classLoader.loadClass(gid.sourceClassName());
                Object instance = java.lang.reflect.Modifier.isInterface(cls.getModifiers())
                                  ? null : cls.getDeclaredConstructor().newInstance();
                Method method = findInvariantMethod(cls, gid);
                if (gid.imperative()) {
                    invariants.add(new ResolvedInvariant.ImperativeInvariant(gid.methodName(), method, instance));
                } else {
                    invariants.add(new ResolvedInvariant.ParameterizedReflectiveInvariant(gid.methodName(), method, instance, gid.patterns()));
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to resolve graph invariant: "
                                           + gid.methodName(), e);
            }
        }
        return invariants;
    }

    private Method findInvariantMethod(Class<?> cls, GraphInvariantDescriptor gid)
            throws NoSuchMethodException {
        if (gid.imperative()) {
            return cls.getMethod(gid.methodName(), DesiredStateGraph.class);
        }
        Class<?>[] paramTypes = new Class<?>[gid.patterns().size()];
        for (int i = 0; i < gid.patterns().size(); i++) {
            paramTypes[i] = gid.patterns().get(i).kind() == PatternKind.NOT_EXISTS
                    ? Void.class : io.casehub.desiredstate.api.DesiredNode.class;
        }
        return cls.getMethod(gid.methodName(), paramTypes);
    }

    private List<ResolvedRule> resolveRules(List<GraphRuleDescriptor> descriptors) {
        List<ResolvedRule> rules       = new ArrayList<>();
        ClassLoader        classLoader = Thread.currentThread().getContextClassLoader();
        for (GraphRuleDescriptor grd : descriptors) {
            try {
                Class<?> ruleClass = classLoader.loadClass(grd.sourceClassName());
                Object ruleInstance = java.lang.reflect.Modifier.isInterface(ruleClass.getModifiers())
                                      ? null
                                      : ruleClass.getDeclaredConstructor().newInstance();
                Method ruleMethod = findRuleMethod(ruleClass, grd);
                if (grd.imperative()) {
                    rules.add(new ResolvedRule.ImperativeRule(grd.methodName(), ruleMethod, ruleInstance));
                } else {
                    rules.add(new ResolvedRule.ParameterizedReflectiveRule(grd.methodName(), ruleMethod, ruleInstance, grd.patterns()));
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to resolve graph rule: " + grd.methodName(), e);
            }
        }
        return rules;
    }

    private Method findRuleMethod(Class<?> ruleClass, GraphRuleDescriptor grd) throws NoSuchMethodException {
        if (grd.imperative()) {
            return ruleClass.getMethod(grd.methodName(), DesiredStateGraph.class);
        }
        Class<?>[] paramTypes = new Class<?>[grd.patterns().size()];
        for (int i = 0; i < grd.patterns().size(); i++) {
            paramTypes[i] = grd.patterns().get(i).kind() == PatternKind.NOT_EXISTS
                            ? Void.class : DesiredNode.class;
        }
        return ruleClass.getMethod(grd.methodName(), paramTypes);
    }


    public RuntimeValue<ThresholdFaultPolicy> createFaultPolicy(
            FaultPolicyDescriptor descriptor, String implClassName) {
        try {
            String className = descriptor.sourceClassName() != null
                    ? descriptor.sourceClassName() : implClassName;
            Class<?> implClass = Thread.currentThread().getContextClassLoader()
                    .loadClass(className);
            Object instance = implClass.getDeclaredConstructor().newInstance();

            Set<FaultType> faultTypes = descriptor.faultTypes().stream()
                    .map(FaultType::valueOf)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(FaultType.class)));

            Set<NodeType> nodeTypes = descriptor.nodeTypes().stream()
                    .map(NodeType::of)
                    .collect(Collectors.toSet());

            if (nodeTypes.isEmpty() && descriptor.sourceClassName() != null
                    && instance instanceof NodeSpec nodeSpec) {
                nodeTypes = Set.of(nodeSpec.nodeType());
            }

            Set<NodeType> ignoreTypes = descriptor.ignoreTypes().stream()
                    .map(NodeType::of)
                    .collect(Collectors.toSet());

            ThresholdFaultPolicy.Builder builder = ThresholdFaultPolicy.builder()
                    .faultTypes(faultTypes)
                    .nodeTypes(nodeTypes)
                    .ignoreTypes(ignoreTypes);

            if (!descriptor.namespace().isEmpty()) {
                builder.namespace(descriptor.namespace());
            }

            for (TierDescriptor td : descriptor.tiers()) {
                Method reviewMethod = implClass.getMethod(td.reviewMethodName(),
                        FaultEvent.class, DesiredStateGraph.class);
                io.casehub.desiredstate.api.ReviewSpecFactory reviewFactory = (event, graph) -> {
                    try {
                        return (NodeSpec) reviewMethod.invoke(instance, event, graph);
                    } catch (Exception e) {
                        throw new RuntimeException("Review method invocation failed: "
                                + reviewMethod.getName(), e);
                    }
                };
                if (!td.nodeType().isEmpty()) {
                    NodeType declaredType = NodeType.of(td.nodeType());
                    io.casehub.desiredstate.api.ReviewSpecFactory delegate = reviewFactory;
                    reviewFactory = new io.casehub.desiredstate.api.ReviewSpecFactory() {
                        @Override
                        public NodeSpec create(FaultEvent event, DesiredStateGraph graph) {
                            return delegate.create(event, graph);
                        }
                        @Override
                        public NodeType nodeType() { return declaredType; }
                    };
                }
                builder.tier(td.threshold(),
                        io.casehub.desiredstate.api.FaultPolicy.addReviewNode(reviewFactory));
            }

            for (Method m : implClass.getMethods()) {
                if (m.isAnnotationPresent(io.casehub.desiredstate.annotations.Customize.class)) {
                    var customize = m.getAnnotation(io.casehub.desiredstate.annotations.Customize.class);
                    if (!customize.value().isEmpty() && m.getParameterCount() == 1
                            && ThresholdFaultPolicy.Builder.class.isAssignableFrom(m.getParameterTypes()[0])) {
                        m.invoke(null, builder);
                    }
                }
            }

            return new RuntimeValue<>(builder.build());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create fault policy from annotations: "
                    + e.getMessage(), e);
        }
    }

    private static List<DesiredNode> buildNodes(Class<?> implClass, Object instance,
            GraphDescriptor descriptor) throws Exception {
        ClassLoader       classLoader = Thread.currentThread().getContextClassLoader();
        List<DesiredNode> nodes       = new ArrayList<>();
        for (NodeDescriptor nd : descriptor.nodes()) {
            switch (nd) {
                case NodeDescriptor.InterfaceNode in -> {
                    Method   method = implClass.getMethod(in.methodName());
                    NodeSpec spec   = (NodeSpec) method.invoke(instance);
                    nodes.add(new DesiredNode(NodeId.of(in.id()), spec, in.humanGating()));
                }
                case NodeDescriptor.ClassNode cn -> {
                    Class<?> nodeClass = classLoader.loadClass(cn.className());
                    NodeSpec spec      = (NodeSpec) nodeClass.getDeclaredConstructor().newInstance();
                    nodes.add(new DesiredNode(NodeId.of(cn.id()), spec, spec.humanGating()));
                }
                case NodeDescriptor.InlineNode ignored ->
                        throw new IllegalStateException("InlineNode cannot appear in annotation-path graphs");
            }
        }
        return List.copyOf(nodes);
    }

    private static List<DesiredNode> buildClassOnlyNodes(GraphDescriptor descriptor) {
        ClassLoader       classLoader = Thread.currentThread().getContextClassLoader();
        List<DesiredNode> nodes       = new ArrayList<>();
        for (NodeDescriptor nd : descriptor.nodes()) {
            if (nd instanceof NodeDescriptor.ClassNode cn) {
                try {
                    Class<?> nodeClass = classLoader.loadClass(cn.className());
                    NodeSpec spec      = (NodeSpec) nodeClass.getDeclaredConstructor().newInstance();
                    nodes.add(new DesiredNode(NodeId.of(cn.id()), spec, spec.humanGating()));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to instantiate @DeclareNode class: "
                                               + cn.className(), e);
                }
            }
        }
        return List.copyOf(nodes);
    }


    private static List<Dependency> buildDependencies(GraphDescriptor descriptor) {
        List<Dependency> deps = new ArrayList<>();
        for (DependencyDescriptor dd : descriptor.dependencies()) {
            deps.add(new Dependency(NodeId.of(dd.from()), NodeId.of(dd.to())));
        }
        return List.copyOf(deps);
    }

    private static List<Method> findGraphCustomizers(Class<?> implClass) {
        List<Method> customizers = new ArrayList<>();
        for (Method m : implClass.getMethods()) {
            if (m.isAnnotationPresent(io.casehub.desiredstate.annotations.Customize.class)) {
                var customize = m.getAnnotation(io.casehub.desiredstate.annotations.Customize.class);
                if (customize.value().isEmpty() && m.getParameterCount() == 1
                        && DesiredStateGraph.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    customizers.add(m);
                }
            }
        }
        return customizers;
    }


}
