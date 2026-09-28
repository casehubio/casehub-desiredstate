package io.casehub.desiredstate.annotations.core;

import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.Direction;
import io.casehub.desiredstate.annotations.runtime.FaultPolicyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GoalMethodDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphInvariantDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphRuleDescriptor;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.annotations.runtime.PatternKind;
import io.casehub.desiredstate.annotations.runtime.PatternParameterDescriptor;
import io.casehub.desiredstate.annotations.runtime.TierDescriptor;
import io.casehub.desiredstate.api.HumanGating;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-neutral Jandex scanning logic for annotation-based desired state declarations.
 * Extracts descriptor records from a Jandex {@link IndexView} — shared by both the Quarkus
 * deployment module and the Spring auto-configuration.
 */
public final class DescriptorScanner {

    private DescriptorScanner() {}

    private static final DotName DESIRED_STATE               = DotName.createSimple(
            "io.casehub.desiredstate.annotations.DesiredState");
    private static final DotName NODE                        = DotName.createSimple(
            "io.casehub.desiredstate.annotations.Node");
    private static final DotName DEPENDS_ON                  = DotName.createSimple(
            "io.casehub.desiredstate.annotations.DependsOn");
    private static final DotName FAULT_POLICY_DEF            = DotName.createSimple(
            "io.casehub.desiredstate.annotations.FaultPolicyDef");
    private static final DotName FAULT_POLICIES              = DotName.createSimple(
            "io.casehub.desiredstate.annotations.FaultPolicies");
    private static final DotName GOAL_METHOD                 = DotName.createSimple(
            "io.casehub.desiredstate.annotations.GoalMethod");
    private static final DotName COMPILATION_RESULT          = DotName.createSimple(
            "io.casehub.desiredstate.api.CompilationResult");
    private static final DotName DESIRED_STATE_GRAPH_FACTORY = DotName.createSimple(
            "io.casehub.desiredstate.api.DesiredStateGraphFactory");
    private static final DotName DESIRED_STATE_GRAPH         = DotName.createSimple(
            "io.casehub.desiredstate.api.DesiredStateGraph");
    private static final DotName DECLARE_NODE                = DotName.createSimple(
            "io.casehub.desiredstate.annotations.DeclareNode");
    private static final DotName GRAPH_RULE                  = DotName.createSimple(
            "io.casehub.desiredstate.annotations.GraphRule");
    private static final DotName MATCH                       = DotName.createSimple(
            "io.casehub.desiredstate.annotations.Match");
    private static final DotName DIRECT_DEP                  = DotName.createSimple(
            "io.casehub.desiredstate.annotations.DirectDep");
    private static final DotName REACHES                     = DotName.createSimple(
            "io.casehub.desiredstate.annotations.Reaches");
    private static final DotName NOT_EXISTS                  = DotName.createSimple(
            "io.casehub.desiredstate.annotations.NotExists");
    private static final DotName GRAPH_INVARIANT             = DotName.createSimple(
            "io.casehub.desiredstate.annotations.GraphInvariant");

    // --- Top-level scanning entry points ---

    public static List<GraphDescriptor> scanGraphs(IndexView index) {
        List<GraphDescriptor> result = new ArrayList<>();
        for (AnnotationInstance dsAnn : index.getAnnotations(DESIRED_STATE)) {
            ClassInfo dsClass = dsAnn.target().asClass();
            result.add(buildGraphDescriptor(dsAnn, dsClass, index));
        }
        return result;
    }

    public static List<FaultPolicyDescriptor> scanFaultPolicies(IndexView index) {
        List<FaultPolicyDescriptor> result = new ArrayList<>();
        for (AnnotationInstance dsAnn : index.getAnnotations(DESIRED_STATE)) {
            ClassInfo dsClass = dsAnn.target().asClass();
            GraphDescriptor gd = buildGraphDescriptor(dsAnn, dsClass, index);
            result.addAll(gd.faultPolicies());
        }
        return result;
    }

    public static Map<String, List<NodeDescriptor.ClassNode>> scanDeclareNodes(IndexView index) {
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

    public static List<DependencyDescriptor> resolveClassDependencies(
            List<NodeDescriptor.ClassNode> classNodes, IndexView index) {
        List<DependencyDescriptor> deps = new ArrayList<>();
        for (NodeDescriptor.ClassNode cn : classNodes) {
            ClassInfo classInfo = index.getClassByName(DotName.createSimple(cn.className()));
            if (classInfo == null) { continue; }

            AnnotationInstance dependsOnAnn = classInfo.declaredAnnotation(DEPENDS_ON);
            if (dependsOnAnn == null) { continue; }

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

    public static List<FaultPolicyDescriptor> collectClassFaultPolicies(
            List<NodeDescriptor.ClassNode> classNodes, IndexView index) {
        List<FaultPolicyDescriptor> policies = new ArrayList<>();
        for (NodeDescriptor.ClassNode cn : classNodes) {
            ClassInfo classInfo = index.getClassByName(DotName.createSimple(cn.className()));
            if (classInfo == null) { continue; }

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

    public static List<Map.Entry<String[], List<GraphRuleDescriptor>>> scanStandaloneGraphRules(
            IndexView index) {
        List<Map.Entry<String[], List<GraphRuleDescriptor>>> result = new ArrayList<>();
        for (AnnotationInstance grAnn : index.getAnnotations(GRAPH_RULE)) {
            if (grAnn.target().kind() != AnnotationTarget.Kind.CLASS) { continue; }
            ClassInfo       classInfo = grAnn.target().asClass();
            AnnotationValue graphVal  = grAnn.value("graph");
            if (graphVal == null) { continue; }
            String[] graphPatterns = graphVal.asStringArray();
            if (graphPatterns.length == 0) { continue; }

            List<GraphRuleDescriptor> classRules = new ArrayList<>();
            for (MethodInfo method : classInfo.methods()) {
                if (method.hasAnnotation(GRAPH_RULE)
                    && !java.lang.reflect.Modifier.isStatic(method.flags())
                    && java.lang.reflect.Modifier.isPublic(method.flags())) {
                    classRules.add(buildGraphRuleDescriptor(method, index,
                                                            classInfo.name().toString()));
                }
            }
            if (!classRules.isEmpty()) {
                result.add(Map.entry(graphPatterns, classRules));
            }
        }
        return result;
    }

    public static List<Map.Entry<String[], List<GraphInvariantDescriptor>>> scanStandaloneGraphInvariants(
            IndexView index) {
        List<Map.Entry<String[], List<GraphInvariantDescriptor>>> result = new ArrayList<>();
        for (AnnotationInstance giAnn : index.getAnnotations(GRAPH_INVARIANT)) {
            if (giAnn.target().kind() != AnnotationTarget.Kind.CLASS) { continue; }
            ClassInfo       classInfo = giAnn.target().asClass();
            AnnotationValue graphVal  = giAnn.value("graph");
            if (graphVal == null) { continue; }
            String[] graphPatterns = graphVal.asStringArray();
            if (graphPatterns.length == 0) { continue; }

            List<GraphInvariantDescriptor> classInvariants = new ArrayList<>();
            for (MethodInfo method : classInfo.methods()) {
                if (method.hasAnnotation(GRAPH_INVARIANT)
                    && !java.lang.reflect.Modifier.isStatic(method.flags())
                    && java.lang.reflect.Modifier.isPublic(method.flags())) {
                    classInvariants.add(buildGraphInvariantDescriptor(method, index,
                                                                      classInfo.name().toString()));
                }
            }
            if (!classInvariants.isEmpty()) {
                result.add(Map.entry(graphPatterns, classInvariants));
            }
        }
        return result;
    }

    // --- Descriptor building helpers ---

    public static GraphDescriptor buildGraphDescriptor(
            AnnotationInstance dsAnn, ClassInfo dsClass, IndexView index) {

        String namespace     = stringValueOrDefault(dsAnn, index, "namespace", "");
        String name          = stringValueOrDefault(dsAnn, index, "name", "");
        String implClassName = dsClass.name().toString() + "_DesiredStateImpl";

        List<NodeDescriptor>        nodes         = new ArrayList<>();
        List<DependencyDescriptor>  deps          = new ArrayList<>();
        List<FaultPolicyDescriptor> faultPolicies = new ArrayList<>();

        for (MethodInfo method : dsClass.methods()) {
            AnnotationInstance nodeAnn = method.annotation(NODE);
            if (nodeAnn != null) {
                String      nodeId = nodeAnn.value().asString();
                HumanGating gating = resolveHumanGating(nodeAnn, index);

                nodes.add(new NodeDescriptor.InterfaceNode(nodeId, method.name(),
                                                           method.returnType().name().toString(), gating));

                AnnotationInstance dependsOnAnn = method.annotation(DEPENDS_ON);
                if (dependsOnAnn != null) {
                    AnnotationValue stringDeps = dependsOnAnn.value();
                    if (stringDeps != null) {
                        for (String dep : stringDeps.asStringArray()) {
                            deps.add(new DependencyDescriptor(nodeId, dep));
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
                                    deps.add(new DependencyDescriptor(nodeId, targetId));
                                }
                            }
                        }
                    }
                }

                collectMethodLevelFaultPolicies(method, index, faultPolicies);
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

        List<GraphRuleDescriptor>      graphRules      = new ArrayList<>();
        List<GraphInvariantDescriptor> graphInvariants = new ArrayList<>();
        for (MethodInfo method : dsClass.methods()) {
            if (method.hasAnnotation(GRAPH_RULE)) {
                graphRules.add(buildGraphRuleDescriptor(method, index, dsClass.name().toString()));
            }
            if (method.hasAnnotation(GRAPH_INVARIANT)) {
                graphInvariants.add(buildGraphInvariantDescriptor(method, index, dsClass.name().toString()));
            }
        }

        return new GraphDescriptor(namespace, name, dsClass.name().toString(),
                                   implClassName, nodes, deps, faultPolicies, goalMethod,
                                   graphRules, graphInvariants);
    }

    public static FaultPolicyDescriptor buildFaultPolicyDescriptor(
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
                String nodeType  = stringValueOrDefault(tierAnn, index, "nodeType", "");
                tiers.add(new TierDescriptor(threshold, review, nodeType));
            }
        }

        return new FaultPolicyDescriptor(faultTypes, nodeTypes, ignoreTypes, namespace, tiers,
                                         sourceClassName);
    }

    public static FaultPolicyDescriptor buildFaultPolicyDescriptor(
            AnnotationInstance fpAnn, IndexView index) {
        return buildFaultPolicyDescriptor(fpAnn, index, null);
    }

    public static HumanGating resolveHumanGating(AnnotationInstance nodeAnn, IndexView index) {
        AnnotationValue gatingVal = nodeAnn.valueWithDefault(index, "humanGating");
        if (gatingVal == null) { return HumanGating.NONE; }
        return HumanGating.valueOf(gatingVal.asEnum());
    }

    public static GraphRuleDescriptor buildGraphRuleDescriptor(MethodInfo method, IndexView index,
                                                                String sourceClassName) {
        if (method.parametersCount() == 1
            && method.parameterType(0).name().equals(DESIRED_STATE_GRAPH)) {
            return new GraphRuleDescriptor(method.name(), true, List.of(), sourceClassName);
        }

        List<PatternParameterDescriptor> patterns = new ArrayList<>();
        for (int i = 0; i < method.parametersCount(); i++) {
            PatternParameterDescriptor ppd = buildPatternForParameter(method, i, index);
            if (ppd != null) {
                patterns.add(ppd);
            }
        }
        return new GraphRuleDescriptor(method.name(), false, patterns, sourceClassName);
    }

    public static GraphInvariantDescriptor buildGraphInvariantDescriptor(MethodInfo method,
                                                                         IndexView index,
                                                                         String sourceClassName) {
        if (method.parametersCount() == 1
            && method.parameterType(0).name().equals(DESIRED_STATE_GRAPH)) {
            return new GraphInvariantDescriptor(method.name(), true, List.of(), sourceClassName);
        }

        List<PatternParameterDescriptor> patterns = new ArrayList<>();
        for (int i = 0; i < method.parametersCount(); i++) {
            PatternParameterDescriptor ppd = buildPatternForParameter(method, i, index);
            if (ppd != null) {
                patterns.add(ppd);
            }
        }
        return new GraphInvariantDescriptor(method.name(), false, patterns, sourceClassName);
    }

    public static PatternParameterDescriptor buildPatternForParameter(MethodInfo method,
                                                                       int paramIndex,
                                                                       IndexView index) {
        for (AnnotationInstance ann : method.annotations()) {
            if (ann.target().kind() != AnnotationTarget.Kind.METHOD_PARAMETER) { continue; }
            if (ann.target().asMethodParameter().position() != paramIndex) { continue; }

            DotName annName = ann.name();
            if (annName.equals(MATCH)) {
                int minCount = ann.valueWithDefault(index, "minCount").asInt();
                int maxCount = ann.valueWithDefault(index, "maxCount").asInt();
                return new PatternParameterDescriptor(
                        PatternKind.MATCH, ann.value("type").asString(),
                        "", Direction.DEPENDENCIES, minCount, maxCount);
            }
            if (annName.equals(DIRECT_DEP)) {
                int minCount = ann.valueWithDefault(index, "minCount").asInt();
                int maxCount = ann.valueWithDefault(index, "maxCount").asInt();
                return new PatternParameterDescriptor(
                        PatternKind.DIRECT_DEP,
                        ann.value("type").asString(),
                        ann.valueWithDefault(index, "of").asString(),
                        Direction.valueOf(ann.valueWithDefault(index, "direction").asEnum()),
                        minCount, maxCount);
            }
            if (annName.equals(REACHES)) {
                int minCount = ann.valueWithDefault(index, "minCount").asInt();
                int maxCount = ann.valueWithDefault(index, "maxCount").asInt();
                return new PatternParameterDescriptor(
                        PatternKind.REACHES,
                        ann.value("type").asString(),
                        ann.valueWithDefault(index, "of").asString(),
                        Direction.valueOf(ann.valueWithDefault(index, "direction").asEnum()),
                        minCount, maxCount);
            }
            if (annName.equals(NOT_EXISTS)) {
                return new PatternParameterDescriptor(
                        PatternKind.NOT_EXISTS,
                        ann.value("type").asString(),
                        ann.valueWithDefault(index, "of").asString(),
                        Direction.valueOf(ann.valueWithDefault(index, "direction").asEnum()));
            }
        }
        return null;
    }

    // --- Internal helpers ---

    public static void collectMethodLevelFaultPolicies(
            MethodInfo method, IndexView index,
            List<FaultPolicyDescriptor> faultPolicies) {

        List<AnnotationInstance> fpAnns = collectFaultPolicyAnnotations(method);
        for (AnnotationInstance fpAnn : fpAnns) {
            faultPolicies.add(buildFaultPolicyDescriptor(fpAnn, index));
        }
    }

    public static void collectClassLevelFaultPolicies(
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

    static List<AnnotationInstance> collectFaultPolicyAnnotations(MethodInfo method) {
        List<AnnotationInstance> result = new ArrayList<>();
        AnnotationInstance       single = method.annotation(FAULT_POLICY_DEF);
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

    public static String stringValueOrDefault(
            AnnotationInstance ann, IndexView index, String name, String defaultValue) {
        AnnotationValue value = ann.valueWithDefault(index, name);
        if (value == null) { return defaultValue; }
        String s = value.asString();
        return s != null ? s : defaultValue;
    }
}
