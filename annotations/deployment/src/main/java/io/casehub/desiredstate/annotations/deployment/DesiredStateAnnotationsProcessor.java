package io.casehub.desiredstate.annotations.deployment;

import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.DesiredStateGraphRecorder;
import io.casehub.desiredstate.annotations.runtime.FaultPolicyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GoalMethodDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.annotations.runtime.TierDescriptor;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.ThresholdFaultPolicy;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.GeneratedClassGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.runtime.RuntimeValue;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DesiredStateAnnotationsProcessor {

    private static final DotName DESIRED_STATE = DotName.createSimple(
            "io.casehub.desiredstate.annotations.DesiredState");
    private static final DotName NODE = DotName.createSimple(
            "io.casehub.desiredstate.annotations.Node");
    private static final DotName DEPENDS_ON = DotName.createSimple(
            "io.casehub.desiredstate.annotations.DependsOn");
    private static final DotName FAULT_POLICY_DEF = DotName.createSimple(
            "io.casehub.desiredstate.annotations.FaultPolicyDef");
    private static final DotName FAULT_POLICIES = DotName.createSimple(
            "io.casehub.desiredstate.annotations.FaultPolicies");
    private static final DotName NODE_SPEC = DotName.createSimple(
            "io.casehub.desiredstate.api.NodeSpec");
    private static final DotName GOAL_METHOD = DotName.createSimple(
            "io.casehub.desiredstate.annotations.GoalMethod");
    private static final DotName COMPILATION_RESULT = DotName.createSimple(
            "io.casehub.desiredstate.api.CompilationResult");
    private static final DotName DESIRED_STATE_GRAPH = DotName.createSimple(
            "io.casehub.desiredstate.api.DesiredStateGraph");
    private static final DotName DESIRED_STATE_GRAPH_FACTORY = DotName.createSimple(
            "io.casehub.desiredstate.api.DesiredStateGraphFactory");
    private static final DotName DECLARE_NODE                = DotName.createSimple(
            "io.casehub.desiredstate.annotations.DeclareNode");


    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void generateDesiredStateGraphs(
            CombinedIndexBuildItem indexBuildItem,
            DesiredStateGraphRecorder recorder,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans) {
        IndexView index = indexBuildItem.getIndex();

        Map<String, List<NodeDescriptor.ClassNode>> classNodesByGraph  = scanDeclareNodes(index);
        Set<String>                                 interfaceGraphKeys = new HashSet<>();

        for (AnnotationInstance dsAnn : index.getAnnotations(DESIRED_STATE)) {
            ClassInfo       dsClass    = dsAnn.target().asClass();
            GraphDescriptor descriptor = buildGraphDescriptor(dsAnn, dsClass, index);

            String graphKey = descriptor.namespace() + ":" + descriptor.name();
            interfaceGraphKeys.add(graphKey);

            List<NodeDescriptor.ClassNode> classNodes = classNodesByGraph.getOrDefault(graphKey, List.of());
            if (!classNodes.isEmpty()) {
                List<NodeDescriptor> mergedNodes = new ArrayList<>(descriptor.nodes());
                mergedNodes.addAll(classNodes);

                List<DependencyDescriptor> mergedDeps = new ArrayList<>(descriptor.dependencies());
                mergedDeps.addAll(resolveClassDependencies(classNodes, index));

                descriptor = new GraphDescriptor(descriptor.namespace(), descriptor.name(),
                        descriptor.interfaceName(), descriptor.implClassName(),
                        mergedNodes, mergedDeps, descriptor.faultPolicies(), descriptor.goalMethod());
            }

            @SuppressWarnings("rawtypes")
            RuntimeValue<GoalCompiler> runtimeValue = recorder.createGoalCompiler(descriptor);

            registerGoalCompilerBean(runtimeValue, syntheticBeans, descriptor.namespace(), descriptor.name());

            for (FaultPolicyDescriptor fpd : descriptor.faultPolicies()) {
                RuntimeValue<ThresholdFaultPolicy> policyValue =
                        recorder.createFaultPolicy(fpd, descriptor.implClassName());

                syntheticBeans.produce(
                        SyntheticBeanBuildItem.configure(io.casehub.desiredstate.api.FaultPolicy.class)
                                              .scope(ApplicationScoped.class)
                                              .unremovable()
                                              .setRuntimeInit()
                                              .runtimeValue(policyValue)
                                              .done());
            }
        }

        for (var entry : classNodesByGraph.entrySet()) {
            if (interfaceGraphKeys.contains(entry.getKey())) {continue;}
            String[] parts = entry.getKey().split(":", 2);
            String   ns    = parts[0];
            String   nm    = parts.length > 1 ? parts[1] : "";

            List<NodeDescriptor>       nodes = new ArrayList<>(entry.getValue());
            List<DependencyDescriptor> deps  = resolveClassDependencies(entry.getValue(), index);
            List<FaultPolicyDescriptor> classFaultPolicies = collectClassFaultPolicies(entry.getValue(), index);

            GraphDescriptor descriptor = new GraphDescriptor(ns, nm, null, null,
                                                             nodes, deps, classFaultPolicies, null);

            @SuppressWarnings("rawtypes")
            RuntimeValue<GoalCompiler> runtimeValue = recorder.createGoalCompiler(descriptor);

            registerGoalCompilerBean(runtimeValue, syntheticBeans, descriptor.namespace(), descriptor.name());

            for (FaultPolicyDescriptor fpd : classFaultPolicies) {
                RuntimeValue<ThresholdFaultPolicy> policyValue =
                        recorder.createFaultPolicy(fpd, null);

                syntheticBeans.produce(
                        SyntheticBeanBuildItem.configure(io.casehub.desiredstate.api.FaultPolicy.class)
                                .scope(ApplicationScoped.class)
                                .unremovable()
                                .setRuntimeInit()
                                .runtimeValue(policyValue)
                                .done());
            }
        }
    }

    @BuildStep
    void generateImplementationClasses(
            CombinedIndexBuildItem indexBuildItem,
            BuildProducer<GeneratedClassBuildItem> generatedClasses) {

        IndexView index = indexBuildItem.getIndex();

        for (AnnotationInstance dsAnn : index.getAnnotations(DESIRED_STATE)) {
            ClassInfo dsClass = dsAnn.target().asClass();
            String implClassName = dsClass.name().toString() + "_DesiredStateImpl";

            try (ClassCreator creator = ClassCreator.builder()
                    .classOutput(new GeneratedClassGizmoAdaptor(generatedClasses, true))
                    .className(implClassName)
                    .interfaces(dsClass.name().toString())
                    .build()) {

                try (MethodCreator ctor = creator.getMethodCreator("<init>", void.class)) {
                    ctor.invokeSpecialMethod(
                            MethodDescriptor.ofConstructor(Object.class), ctor.getThis());
                    ctor.returnVoid();
                }
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private void registerGoalCompilerBean(
            RuntimeValue<GoalCompiler> runtimeValue,
            BuildProducer<SyntheticBeanBuildItem> syntheticBeans,
            String namespace, String name) {
        syntheticBeans.produce(
                SyntheticBeanBuildItem.configure(GoalCompiler.class)
                                      .scope(ApplicationScoped.class)
                                      .unremovable()
                                      .setRuntimeInit()
                                      .runtimeValue(runtimeValue)
                                      .addQualifier(jakarta.enterprise.inject.Default.class)
                                      .addQualifier()
                                      .annotation(io.casehub.desiredstate.annotations.DesiredStateQualifier.class)
                                      .addValue("namespace", namespace)
                                      .addValue("name", name)
                                      .done()
                                      .done());}


    private Map<String, List<NodeDescriptor.ClassNode>> scanDeclareNodes(IndexView index) {
        Map<String, List<NodeDescriptor.ClassNode>> byGraph = new HashMap<>();
        for (AnnotationInstance ann : index.getAnnotations(DECLARE_NODE)) {
            ClassInfo classInfo = ann.target().asClass();
            String    namespace = stringValueOrDefault(ann, index, "namespace", "");
            String    name      = stringValueOrDefault(ann, index, "name", "");
            String    id        = ann.value("id").asString();
            String    graphKey  = namespace + ":" + name;
            byGraph.computeIfAbsent(graphKey, k -> new ArrayList<>())
                   .add(new NodeDescriptor.ClassNode(id, classInfo.name().toString()));
        }
        return byGraph;
    }

    private List<DependencyDescriptor> resolveClassDependencies(
            List<NodeDescriptor.ClassNode> classNodes, IndexView index) {
        List<DependencyDescriptor> deps = new ArrayList<>();
        for (NodeDescriptor.ClassNode cn : classNodes) {
            ClassInfo classInfo = index.getClassByName(DotName.createSimple(cn.className()));
            if (classInfo == null) {continue;}

            AnnotationInstance dependsOnAnn = classInfo.declaredAnnotation(DEPENDS_ON);
            if (dependsOnAnn == null) {continue;}

            AnnotationValue stringDeps = dependsOnAnn.value();
            if (stringDeps != null) {
                for (String dep : stringDeps.asStringArray()) {
                    deps.add(new DependencyDescriptor(cn.id(), dep));
                }
            }

            AnnotationValue classDeps = dependsOnAnn.value("nodes");
            if (classDeps != null) {
                for (var classRef : classDeps.asClassArray()) {
                    ClassInfo targetClass = index.getClassByName(classRef.name());
                    if (targetClass != null) {
                        AnnotationInstance targetAnn = targetClass.declaredAnnotation(DECLARE_NODE);
                        if (targetAnn != null) {
                            String targetId = targetAnn.value("id").asString();
                            deps.add(new DependencyDescriptor(cn.id(), targetId));
                        }
                    }
                }
            }
        }
        return deps;
    }

    private List<FaultPolicyDescriptor> collectClassFaultPolicies(
            List<NodeDescriptor.ClassNode> classNodes, IndexView index) {
        List<FaultPolicyDescriptor> policies = new ArrayList<>();
        for (NodeDescriptor.ClassNode cn : classNodes) {
            ClassInfo classInfo = index.getClassByName(DotName.createSimple(cn.className()));
            if (classInfo == null) {continue;}

            AnnotationInstance single = classInfo.declaredAnnotation(FAULT_POLICY_DEF);
            if (single != null) {
                policies.add(buildFaultPolicyDescriptor(single, index, cn.className()));
            }
            AnnotationInstance container = classInfo.declaredAnnotation(FAULT_POLICIES);
            if (container != null) {
                for (AnnotationInstance nested : container.value().asNestedArray()) {
                    policies.add(buildFaultPolicyDescriptor(nested, index, cn.className()));
                }
            }
        }
        return policies;
    }

    private FaultPolicyDescriptor buildFaultPolicyDescriptor(
            AnnotationInstance fpAnn, IndexView index, String sourceClassName) {
        List<String>    faultTypes   = Arrays.asList(fpAnn.value("faultTypes").asStringArray());
        AnnotationValue nodeTypesVal = fpAnn.value("nodeTypes");
        List<String> nodeTypes = nodeTypesVal != null
                                 ? Arrays.asList(nodeTypesVal.asStringArray()) : List.of();
        AnnotationValue ignoreTypesVal = fpAnn.value("ignoreTypes");
        List<String> ignoreTypes = ignoreTypesVal != null
                                   ? Arrays.asList(ignoreTypesVal.asStringArray()) : List.of();
        String namespace = stringValueOrDefault(fpAnn, index, "namespace", "");

        List<TierDescriptor> tiers    = new ArrayList<>();
        AnnotationValue      tiersVal = fpAnn.value("tiers");
        if (tiersVal != null) {
            for (AnnotationInstance tierAnn : tiersVal.asNestedArray()) {
                int    threshold = tierAnn.value("threshold").asInt();
                String review    = tierAnn.value("review").asString();
                tiers.add(new TierDescriptor(threshold, review));
            }
        }

        return new FaultPolicyDescriptor(faultTypes, nodeTypes, ignoreTypes, namespace, tiers,
                                         sourceClassName);
    }


    private GraphDescriptor buildGraphDescriptor(
            AnnotationInstance dsAnn, ClassInfo dsClass, IndexView index) {

        String namespace = stringValueOrDefault(dsAnn, index, "namespace", "");
        String name = stringValueOrDefault(dsAnn, index, "name", "");
        String implClassName = dsClass.name().toString() + "_DesiredStateImpl";

        List<NodeDescriptor> nodes = new ArrayList<>();
        List<DependencyDescriptor> deps = new ArrayList<>();
        List<FaultPolicyDescriptor> faultPolicies = new ArrayList<>();

        for (MethodInfo method : dsClass.methods()) {
            AnnotationInstance nodeAnn = method.annotation(NODE);
            if (nodeAnn != null) {
                String nodeId = nodeAnn.value().asString();
                HumanGating gating = resolveHumanGating(nodeAnn, index);

                nodes.add(new NodeDescriptor.InterfaceNode(nodeId, method.name(),
                        method.returnType().name().toString(), gating));

                AnnotationInstance dependsOnAnn = method.annotation(DEPENDS_ON);
                if (dependsOnAnn != null) {
                    for (String dep : dependsOnAnn.value().asStringArray()) {
                        deps.add(new DependencyDescriptor(nodeId, dep));
                    }
                }

                collectMethodLevelFaultPolicies(method, nodeAnn, index, faultPolicies);
            }
        }

        collectClassLevelFaultPolicies(dsClass, index, faultPolicies);

        GoalMethodDescriptor goalMethod = null;
        for (MethodInfo method : dsClass.methods()) {
            if (method.hasAnnotation(GOAL_METHOD)) {
                String goalsTypeName = method.parameterType(0).name().toString();
                boolean returnsCompilationResult =
                        method.returnType().name().equals(COMPILATION_RESULT);
                boolean hasFactoryParam = method.parametersCount() >= 3
                        && method.parameterType(2).name().equals(DESIRED_STATE_GRAPH_FACTORY);
                goalMethod = new GoalMethodDescriptor(
                        method.name(), goalsTypeName, returnsCompilationResult, hasFactoryParam);
                break;
            }
        }

        return new GraphDescriptor(namespace, name, dsClass.name().toString(),
                implClassName, nodes, deps, faultPolicies, goalMethod);
    }

    private void collectMethodLevelFaultPolicies(
            MethodInfo method, AnnotationInstance nodeAnn, IndexView index,
            List<FaultPolicyDescriptor> faultPolicies) {

        List<AnnotationInstance> fpAnns = collectFaultPolicyAnnotations(method);
        for (AnnotationInstance fpAnn : fpAnns) {
            faultPolicies.add(buildFaultPolicyDescriptor(fpAnn, index));
        }
    }

    private void collectClassLevelFaultPolicies(
            ClassInfo dsClass, IndexView index,
            List<FaultPolicyDescriptor> faultPolicies) {

        AnnotationInstance singleFp = dsClass.declaredAnnotation(FAULT_POLICY_DEF);
        if (singleFp != null) {
            faultPolicies.add(buildFaultPolicyDescriptor(singleFp, index));
        }
        AnnotationInstance containerFp = dsClass.declaredAnnotation(FAULT_POLICIES);
        if (containerFp != null) {
            for (AnnotationInstance nested : containerFp.value().asNestedArray()) {
                faultPolicies.add(buildFaultPolicyDescriptor(nested, index));
            }
        }
    }

    private FaultPolicyDescriptor buildFaultPolicyDescriptor(
            AnnotationInstance fpAnn, IndexView index) {
        return buildFaultPolicyDescriptor(fpAnn, index, null);
    }

    private List<AnnotationInstance> collectFaultPolicyAnnotations(MethodInfo method) {
        List<AnnotationInstance> result = new ArrayList<>();
        AnnotationInstance single = method.annotation(FAULT_POLICY_DEF);
        if (single != null) {
            result.add(single);
        }
        AnnotationInstance container = method.annotation(FAULT_POLICIES);
        if (container != null) {
            result.clear();
            for (AnnotationInstance nested : container.value().asNestedArray()) {
                result.add(nested);
            }
        }
        return result;
    }

    private HumanGating resolveHumanGating(AnnotationInstance nodeAnn, IndexView index) {
        AnnotationValue gatingVal = nodeAnn.valueWithDefault(index, "humanGating");
        if (gatingVal == null) return HumanGating.NONE;
        return HumanGating.valueOf(gatingVal.asEnum());
    }

    private static String stringValueOrDefault(
            AnnotationInstance ann, IndexView index, String name, String defaultValue) {
        AnnotationValue value = ann.valueWithDefault(index, name);
        if (value == null) return defaultValue;
        String s = value.asString();
        return s != null ? s : defaultValue;
    }
}
