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
import io.casehub.desiredstate.api.ThresholdFaultPolicy;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

@Recorder
public class DesiredStateGraphRecorder {

    private static final Logger LOG = Logger.getLogger(DesiredStateGraphRecorder.class);

    @SuppressWarnings({"unchecked", "rawtypes"})
    public RuntimeValue<GoalCompiler> createGoalCompiler(GraphDescriptor descriptor) {
        try {
            Class<?> implClass = Thread.currentThread().getContextClassLoader()
                    .loadClass(descriptor.implClassName());
            Object instance = implClass.getDeclaredConstructor().newInstance();

            List<DesiredNode> capturedNodes = buildNodes(implClass, instance, descriptor);
            List<Dependency> capturedDeps = buildDependencies(descriptor);
            List<Method> graphCustomizers = findGraphCustomizers(implClass);

            if (descriptor.goalMethod() == null) {
                return new RuntimeValue<>((GoalCompiler) (goals, factory) -> {
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
            }

            GoalMethodDescriptor gmd = descriptor.goalMethod();
            Class<?> goalsType = Thread.currentThread().getContextClassLoader()
                    .loadClass(gmd.goalsTypeName());
            Method goalMethod = gmd.hasFactoryParam()
                    ? implClass.getMethod(gmd.methodName(), goalsType,
                            DesiredStateGraph.class, DesiredStateGraphFactory.class)
                    : implClass.getMethod(gmd.methodName(), goalsType, DesiredStateGraph.class);

            return new RuntimeValue<>((GoalCompiler) (goals, factory) -> {
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
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize annotated desired-state graph: "
                    + descriptor.interfaceName(), e);
        }
    }

    public RuntimeValue<ThresholdFaultPolicy> createFaultPolicy(
            FaultPolicyDescriptor descriptor, String implClassName) {
        try {
            Class<?> implClass = Thread.currentThread().getContextClassLoader()
                    .loadClass(implClassName);
            Object instance = implClass.getDeclaredConstructor().newInstance();

            Set<FaultType> faultTypes = descriptor.faultTypes().stream()
                    .map(FaultType::valueOf)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(FaultType.class)));

            Set<NodeType> nodeTypes = descriptor.nodeTypes().stream()
                    .map(NodeType::of)
                    .collect(Collectors.toSet());

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
                NodeType tierNodeType = probeReviewNodeType(instance, reviewMethod);
                builder.tier(td.threshold(),
                        io.casehub.desiredstate.api.FaultPolicy.addReviewNode(
                                (event, graph) -> {
                                    try {
                                        return (NodeSpec) reviewMethod.invoke(instance, event, graph);
                                    } catch (Exception e) {
                                        throw new RuntimeException("Review method invocation failed: "
                                                + reviewMethod.getName(), e);
                                    }
                                }),
                        tierNodeType);
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
        List<DesiredNode> nodes = new ArrayList<>();
        for (NodeDescriptor nd : descriptor.nodes()) {
            Method method = implClass.getMethod(nd.methodName());
            NodeSpec spec = (NodeSpec) method.invoke(instance);
            nodes.add(new DesiredNode(NodeId.of(nd.id()), spec, nd.humanGating()));
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

    private static NodeType probeReviewNodeType(Object instance, Method reviewMethod) {
        try {
            NodeSpec probeSpec = (NodeSpec) reviewMethod.invoke(instance,
                    new FaultEvent(NodeId.of("__probe__"), FaultType.PROVISION_FAILED, "probe"),
                    null);
            return probeSpec.nodeType();
        } catch (Exception e) {
            LOG.warnf("Could not probe review method '%s' for NodeType — using method name as fallback",
                    reviewMethod.getName());
            return NodeType.of(reviewMethod.getName());
        }
    }
}
