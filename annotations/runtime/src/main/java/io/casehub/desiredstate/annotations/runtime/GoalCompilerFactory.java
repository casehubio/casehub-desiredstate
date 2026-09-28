package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.annotations.Customize;
import io.casehub.desiredstate.annotations.runtime.graph.GraphView;
import io.casehub.desiredstate.annotations.runtime.graph.MutableGraphView;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.Dependency;
import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.GraphMutation;
import io.casehub.desiredstate.api.NodeId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.Phase;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Framework-neutral factory for creating GoalCompiler instances from GraphDescriptor records.
 * Extracted from DesiredStateGraphRecorder — no Quarkus dependencies.
 */
public final class GoalCompilerFactory {

    private static final Logger LOG = Logger.getLogger(GoalCompilerFactory.class.getName());

    private GoalCompilerFactory() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static GoalCompiler<?> create(GraphDescriptor descriptor) {
        try {
            List<Dependency> capturedDeps = buildDependencies(descriptor);
            GoalCompiler<?> compiler;

            if (descriptor.implClassName() == null) {
                List<DesiredNode> capturedNodes = buildClassOnlyNodes(descriptor);
                compiler = (GoalCompiler) (goals, factory) ->
                        CompilationResult.single(factory.of(capturedNodes, capturedDeps));
            } else {
                Class<?> implClass = Thread.currentThread().getContextClassLoader()
                        .loadClass(descriptor.implClassName());
                Object instance = implClass.getDeclaredConstructor().newInstance();

                List<DesiredNode> capturedNodes = buildNodes(implClass, instance, descriptor);
                List<Method> graphCustomizers = findGraphCustomizers(implClass);

                if (descriptor.goalMethod() == null) {
                    compiler = (GoalCompiler) (goals, factory) -> {
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
                    };
                } else {
                    GoalMethodDescriptor gmd = descriptor.goalMethod();
                    Class<?> goalsType = Thread.currentThread().getContextClassLoader()
                            .loadClass(gmd.goalsTypeName());
                    Method goalMethod = gmd.hasFactoryParam()
                            ? implClass.getMethod(gmd.methodName(), goalsType,
                            DesiredStateGraph.class, DesiredStateGraphFactory.class)
                            : implClass.getMethod(gmd.methodName(), goalsType, DesiredStateGraph.class);

                    compiler = (GoalCompiler) (goals, factory) -> {
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
                    };
                }
            }

            if (!descriptor.graphRules().isEmpty()) {
                List<ResolvedRule<DesiredNode>> resolvedRules = resolveRules(descriptor.graphRules());
                @SuppressWarnings("rawtypes")
                GoalCompiler inner = compiler;
                compiler = (GoalCompiler) (goals, factory) ->
                        applyGraphRulesToResult(inner.compile(goals, factory), resolvedRules);
            }

            if (!descriptor.graphInvariants().isEmpty()) {
                List<ResolvedInvariant<DesiredNode>> resolvedInvariants = resolveInvariants(descriptor.graphInvariants());
                GraphInvariantEngine invariantEngine = new GraphInvariantEngine();
                @SuppressWarnings("rawtypes")
                GoalCompiler inner = compiler;
                compiler = (GoalCompiler) (goals, factory) ->
                        validateInvariantsOnResult(inner.compile(goals, factory), resolvedInvariants, invariantEngine);
            }

            return compiler;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize annotated desired-state graph: "
                    + (descriptor.interfaceName() != null ? descriptor.interfaceName()
                    : descriptor.namespace() + ":" + descriptor.name()), e);
        }
    }

    static CompilationResult applyGraphRulesToResult(CompilationResult result,
                                                     List<ResolvedRule<DesiredNode>> rules) {
        GraphRuleEngine engine = new GraphRuleEngine();
        DesiredStateGraphAdapter adapter = new DesiredStateGraphAdapter();
        return switch (result) {
            case CompilationResult.SingleGraph sg -> {
                var view = new DesiredStateGraphView(sg.graph(), adapter);
                var evaluated = engine.evaluate(view, rules);
                yield CompilationResult.single(((DesiredStateGraphView) evaluated).graph());
            }
            case CompilationResult.Lifecycle lc -> {
                List<Phase> rewritten = new ArrayList<>();
                for (Phase phase : lc.phases()) {
                    var view = new DesiredStateGraphView(phase.graph(), adapter);
                    var evaluated = engine.evaluate(view, rules);
                    rewritten.add(new Phase(phase.id(),
                            ((DesiredStateGraphView) evaluated).graph(), phase.completionCondition()));
                }
                yield CompilationResult.lifecycle(rewritten);
            }
        };
    }

    static CompilationResult validateInvariantsOnResult(CompilationResult result,
                                                        List<ResolvedInvariant<DesiredNode>> invariants,
                                                        GraphInvariantEngine engine) {
        DesiredStateGraphAdapter adapter = new DesiredStateGraphAdapter();
        switch (result) {
            case CompilationResult.SingleGraph sg ->
                    engine.validate(new DesiredStateGraphView(sg.graph(), adapter), invariants);
            case CompilationResult.Lifecycle lc -> {
                for (Phase phase : lc.phases()) {
                    engine.validate(new DesiredStateGraphView(phase.graph(), adapter), invariants);
                }
            }
        }
        return result;
    }

    static List<ResolvedInvariant<DesiredNode>> resolveInvariants(List<GraphInvariantDescriptor> descriptors) {
        List<ResolvedInvariant<DesiredNode>> invariants = new ArrayList<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        for (GraphInvariantDescriptor gid : descriptors) {
            try {
                Class<?> cls = classLoader.loadClass(gid.sourceClassName());
                Object instance = Modifier.isInterface(cls.getModifiers())
                        ? null : cls.getDeclaredConstructor().newInstance();
                Method method = findInvariantMethod(cls, gid);
                if (gid.imperative()) {
                    Method m = method;
                    Object inst = instance;
                    Consumer<GraphView<DesiredNode>> validator = view -> {
                        try {
                            DesiredStateGraph graph = ((DesiredStateGraphView) view).graph();
                            if (inst != null) { m.invoke(inst, graph); } else { m.invoke(null, graph); }
                        } catch (InvocationTargetException e) {
                            if (e.getCause() instanceof GraphViolationException gve) { throw gve; }
                            if (e.getCause() instanceof RuntimeException re) { throw re; }
                            throw new RuntimeException("Invariant " + gid.methodName() + " failed", e.getCause());
                        } catch (Exception e) {
                            throw new RuntimeException("Invariant " + gid.methodName() + " failed", e);
                        }
                    };
                    invariants.add(new ResolvedInvariant.ImperativeInvariant<>(gid.methodName(), validator));
                } else {
                    invariants.add(new ResolvedInvariant.ParameterizedReflectiveInvariant<>(
                            gid.methodName(), method, instance, gid.patterns()));
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to resolve graph invariant: " + gid.methodName(), e);
            }
        }
        return invariants;
    }

    static Method findInvariantMethod(Class<?> cls, GraphInvariantDescriptor gid)
            throws NoSuchMethodException {
        if (gid.imperative()) {
            return cls.getMethod(gid.methodName(), DesiredStateGraph.class);
        }
        Class<?>[] paramTypes = new Class<?>[gid.patterns().size()];
        for (int i = 0; i < gid.patterns().size(); i++) {
            paramTypes[i] = gid.patterns().get(i).kind() == PatternKind.NOT_EXISTS
                    ? Void.class : DesiredNode.class;
        }
        return cls.getMethod(gid.methodName(), paramTypes);
    }

    @SuppressWarnings("unchecked")
    static List<ResolvedRule<DesiredNode>> resolveRules(List<GraphRuleDescriptor> descriptors) {
        List<ResolvedRule<DesiredNode>> rules = new ArrayList<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        for (GraphRuleDescriptor grd : descriptors) {
            try {
                Class<?> ruleClass = classLoader.loadClass(grd.sourceClassName());
                Object ruleInstance = Modifier.isInterface(ruleClass.getModifiers())
                        ? null
                        : ruleClass.getDeclaredConstructor().newInstance();
                Method ruleMethod = findRuleMethod(ruleClass, grd);
                if (grd.imperative()) {
                    Method m = ruleMethod;
                    Object inst = ruleInstance;
                    Function<MutableGraphView<DesiredNode>, List<GraphMutation<DesiredNode>>> evaluator = view -> {
                        try {
                            DesiredStateGraph graph = ((DesiredStateGraphView) view).graph();
                            var result = (List<GraphMutation<DesiredNode>>) m.invoke(inst, graph);
                            return result != null ? result : List.of();
                        } catch (InvocationTargetException e) {
                            if (e.getCause() instanceof RuntimeException re) { throw re; }
                            throw new RuntimeException("Rule " + grd.methodName() + " failed", e.getCause());
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException("Rule " + grd.methodName() + " inaccessible", e);
                        }
                    };
                    rules.add(new ResolvedRule.ImperativeRule<>(grd.methodName(), evaluator));
                } else {
                    rules.add(new ResolvedRule.ParameterizedRule<>(
                            grd.methodName(), ruleMethod, ruleInstance, grd.patterns()));
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to resolve graph rule: " + grd.methodName(), e);
            }
        }
        return rules;
    }

    static Method findRuleMethod(Class<?> ruleClass, GraphRuleDescriptor grd)
            throws NoSuchMethodException {
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

    static List<DesiredNode> buildNodes(Class<?> implClass, Object instance,
                                        GraphDescriptor descriptor) throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        List<DesiredNode> nodes = new ArrayList<>();
        for (NodeDescriptor nd : descriptor.nodes()) {
            switch (nd) {
                case NodeDescriptor.InterfaceNode in -> {
                    Method method = implClass.getMethod(in.methodName());
                    NodeSpec spec = (NodeSpec) method.invoke(instance);
                    nodes.add(new DesiredNode(NodeId.of(in.id()), spec, in.humanGating()));
                }
                case NodeDescriptor.ClassNode cn -> {
                    Class<?> nodeClass = classLoader.loadClass(cn.className());
                    NodeSpec spec = (NodeSpec) nodeClass.getDeclaredConstructor().newInstance();
                    nodes.add(new DesiredNode(NodeId.of(cn.id()), spec, spec.humanGating()));
                }
                case NodeDescriptor.InlineNode ignored ->
                        throw new IllegalStateException("InlineNode cannot appear in annotation-path graphs");
            }
        }
        return List.copyOf(nodes);
    }

    static List<DesiredNode> buildClassOnlyNodes(GraphDescriptor descriptor) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        List<DesiredNode> nodes = new ArrayList<>();
        for (NodeDescriptor nd : descriptor.nodes()) {
            if (nd instanceof NodeDescriptor.ClassNode cn) {
                try {
                    Class<?> nodeClass = classLoader.loadClass(cn.className());
                    NodeSpec spec = (NodeSpec) nodeClass.getDeclaredConstructor().newInstance();
                    nodes.add(new DesiredNode(NodeId.of(cn.id()), spec, spec.humanGating()));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to instantiate @DeclareNode class: "
                            + cn.className(), e);
                }
            }
        }
        return List.copyOf(nodes);
    }

    static List<Dependency> buildDependencies(GraphDescriptor descriptor) {
        List<Dependency> deps = new ArrayList<>();
        for (DependencyDescriptor dd : descriptor.dependencies()) {
            deps.add(new Dependency(NodeId.of(dd.from()), NodeId.of(dd.to())));
        }
        return List.copyOf(deps);
    }

    static List<Method> findGraphCustomizers(Class<?> implClass) {
        List<Method> customizers = new ArrayList<>();
        for (Method m : implClass.getMethods()) {
            if (m.isAnnotationPresent(Customize.class)) {
                var customize = m.getAnnotation(Customize.class);
                if (customize.value().isEmpty() && m.getParameterCount() == 1
                        && DesiredStateGraph.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    customizers.add(m);
                }
            }
        }
        return customizers;
    }
}
