package io.casehub.desiredstate.annotations.deployment;

import io.casehub.desiredstate.api.FaultType;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AnnotationValidationStep {

    private static final Logger LOG = Logger.getLogger(AnnotationValidationStep.class);

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
    private static final DotName FAULT_EVENT = DotName.createSimple(
            "io.casehub.desiredstate.api.FaultEvent");
    private static final DotName DESIRED_STATE_GRAPH = DotName.createSimple(
            "io.casehub.desiredstate.api.DesiredStateGraph");
    private static final DotName DESIRED_STATE_GRAPH_FACTORY = DotName.createSimple(
            "io.casehub.desiredstate.api.DesiredStateGraphFactory");
    private static final DotName COMPILATION_RESULT = DotName.createSimple(
            "io.casehub.desiredstate.api.CompilationResult");
    private static final DotName GOAL_METHOD = DotName.createSimple(
            "io.casehub.desiredstate.annotations.GoalMethod");
    private static final DotName DECLARE_NODE = DotName.createSimple(
            "io.casehub.desiredstate.annotations.DeclareNode");
    private static final DotName CUSTOMIZE    = DotName.createSimple(
            "io.casehub.desiredstate.annotations.Customize");


    @BuildStep
    @Produce(ServiceStartBuildItem.class)
    void validate(CombinedIndexBuildItem indexBuildItem) {
        IndexView    index    = indexBuildItem.getIndex();
        List<String> errors   = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        Map<String, MergedGraph> graphsByKey     = new LinkedHashMap<>();
        Map<String, String>      interfacesByKey = new HashMap<>();

        for (AnnotationInstance dsAnn : index.getAnnotations(DESIRED_STATE)) {
            ClassInfo dsClass = dsAnn.target().asClass();

            if (!java.lang.reflect.Modifier.isInterface(dsClass.flags())) {
                errors.add("@DesiredState on '" + dsClass.name().local()
                           + "' which is not an interface — @DesiredState must annotate an interface");
                continue;
            }

            String graphKey = resolveGraphKey(dsAnn, index);

            String existingIface = interfacesByKey.put(graphKey, dsClass.name().local());
            if (existingIface != null) {
                errors.add("Multiple @DesiredState interfaces with graph key '"
                           + graphKey + "': " + existingIface + " and " + dsClass.name().local()
                           + " — use a single interface per graph,"
                           + " with @DeclareNode classes for extension nodes");
                continue;
            }

            MergedGraph mg = graphsByKey.computeIfAbsent(graphKey, k -> new MergedGraph(graphKey));

            Set<String>         localNodeIds   = new HashSet<>();
            Map<String, String> nodeIdToMethod = new HashMap<>();

            for (MethodInfo method : dsClass.methods()) {
                AnnotationInstance nodeAnn = method.annotation(NODE);
                if (nodeAnn != null) {
                    String nodeId = nodeAnn.value().asString();

                    if (!nodeIdToMethod.containsKey(nodeId)) {
                        localNodeIds.add(nodeId);
                        nodeIdToMethod.put(nodeId, method.name());
                    } else {
                        errors.add("Duplicate @Node id '" + nodeId + "' on methods '"
                                   + nodeIdToMethod.get(nodeId) + "' and '" + method.name() + "'");
                    }

                    if (!method.hasAnnotation(DotName.createSimple("java.lang.Override"))
                        && !isDefaultMethod(method)) {
                        errors.add("@Node on '" + method.name()
                                   + "' must be a default method returning NodeSpec");
                    }

                    Type returnType = method.returnType();
                    if (!implementsNodeSpec(returnType.name(), index)) {
                        errors.add("@Node '" + method.name() + "' return type "
                                   + returnType.name().local() + " does not implement NodeSpec");
                    }

                    mg.addNode(nodeId, "interface method "
                                       + dsClass.name().local() + "#" + method.name());
                    collectDepsIntoMergedGraph(method, index, nodeId, mg, errors, warnings);
                }

                validateFaultPolicyOnMethod(method, dsClass, index, errors);
            }

            validateFaultPolicyFaultTypes(dsClass, index, errors);
            validateTierReviewMethods(dsClass, index, errors);
            validateGoalMethod(dsClass, index, errors);

            if (localNodeIds.isEmpty()) {
                warnings.add("@DesiredState '" + dsClass.name().local()
                             + "' has no @Node methods — graph will be empty");
            }
        }

        validateDeclareNodes(index, graphsByKey, errors, warnings);

        for (MergedGraph mg : graphsByKey.values()) {
            mg.validateDuplicateIds(errors);
            mg.validateDependencyRefs(errors);
            mg.detectCycles(errors);
        }

        for (String warning : warnings) {
            LOG.warn(warning);
        }

        if (!errors.isEmpty()) {
            throw new RuntimeException(
                    "Annotation validation failed:\n- " + String.join("\n- ", errors));
        }}

    private void validateDeclareNodes(IndexView index,
            Map<String, MergedGraph> graphsByKey,
            List<String> errors, List<String> warnings) {
        for (AnnotationInstance dnAnn : index.getAnnotations(DECLARE_NODE)) {
            ClassInfo classInfo = dnAnn.target().asClass();
            String className = classInfo.name().local();

            if (java.lang.reflect.Modifier.isInterface(classInfo.flags())) {
                errors.add("@DeclareNode on interface '" + className
                        + "' — use @DesiredState for interfaces");
                continue;
            }
            if (java.lang.reflect.Modifier.isAbstract(classInfo.flags())) {
                errors.add("@DeclareNode on abstract class '" + className
                        + "' — must be concrete");
                continue;
            }
            if (!implementsNodeSpec(classInfo.name(), index)) {
                errors.add("@DeclareNode on '" + className
                        + "' which does not implement NodeSpec");
                continue;
            }

            if (classInfo.hasAnnotation(DESIRED_STATE)) {
                errors.add("'" + className
                        + "' has both @DeclareNode and @DesiredState — use one or the other");
            }

            for (MethodInfo method : classInfo.methods()) {
                if (method.hasAnnotation(GOAL_METHOD)) {
                    errors.add("@GoalMethod on @DeclareNode class '" + className
                            + "' — @GoalMethod requires a @DesiredState interface");
                }
                if (method.hasAnnotation(NODE)) {
                    errors.add("@Node on @DeclareNode class '" + className
                            + "' — @Node is for @DesiredState interfaces");
                }
                if (method.hasAnnotation(CUSTOMIZE)) {
                    errors.add("@Customize on @DeclareNode class '" + className
                            + "' — @Customize requires a @DesiredState interface");
                }
            }

            String graphKey = resolveGraphKey(dnAnn, index);
            MergedGraph mg = graphsByKey.computeIfAbsent(graphKey, k -> new MergedGraph(graphKey));
            String nodeId = dnAnn.value("id").asString();
            mg.addNode(nodeId, "@DeclareNode class " + className);

            AnnotationInstance dependsOnAnn = classInfo.declaredAnnotation(DEPENDS_ON);
            if (dependsOnAnn != null) {
                AnnotationValue stringDeps = dependsOnAnn.value();
                if (stringDeps != null) {
                    for (String dep : stringDeps.asStringArray()) {
                        mg.addDependency(nodeId, dep);
                    }
                }
                AnnotationValue classDeps = dependsOnAnn.value("nodes");
                if (classDeps != null) {
                    for (var classRef : classDeps.asClassArray()) {
                        ClassInfo targetClass = index.getClassByName(classRef.name());
                        if (targetClass == null) {
                            warnings.add("@DependsOn(nodes) on '" + className
                                    + "' references '" + classRef.name().local()
                                    + "' which is not in the Jandex index"
                                    + " (if the class is in an external JAR,"
                                    + " ensure a Jandex index is generated)");
                        } else if (targetClass.declaredAnnotation(DECLARE_NODE) == null) {
                            errors.add("@DependsOn(nodes) on '" + className
                                    + "' references '" + classRef.name().local()
                                    + "' which has no @DeclareNode annotation");
                        } else if (!implementsNodeSpec(classRef.name(), index)) {
                            errors.add("@DependsOn(nodes) on '" + className
                                    + "' references '" + classRef.name().local()
                                    + "' which does not implement NodeSpec");
                        } else {
                            mg.addDependency(nodeId,
                                    targetClass.declaredAnnotation(DECLARE_NODE)
                                               .value("id").asString());
                        }
                    }
                }
            }
        }
    }


    private void collectDepsIntoMergedGraph(MethodInfo method, IndexView index,
            String sourceNodeId, MergedGraph mg,
            List<String> errors, List<String> warnings) {
        AnnotationInstance dependsOnAnn = method.annotation(DEPENDS_ON);
        if (dependsOnAnn == null) return;

        AnnotationValue stringDeps = dependsOnAnn.value();
        if (stringDeps != null) {
            for (String dep : stringDeps.asStringArray()) {
                mg.addDependency(sourceNodeId, dep);
            }
        }

        AnnotationValue classDeps = dependsOnAnn.value("nodes");
        if (classDeps != null) {
            for (var classRef : classDeps.asClassArray()) {
                ClassInfo targetClass = index.getClassByName(classRef.name());
                if (targetClass == null) {
                    warnings.add("@DependsOn(nodes) on '" + sourceNodeId
                            + "' references '" + classRef.name().local()
                            + "' which is not in the Jandex index"
                            + " (if the class is in an external JAR,"
                            + " ensure a Jandex index is generated)");
                } else if (targetClass.declaredAnnotation(DECLARE_NODE) == null) {
                    errors.add("@DependsOn(nodes) on '" + sourceNodeId
                            + "' references '" + classRef.name().local()
                            + "' which has no @DeclareNode annotation");
                } else if (!implementsNodeSpec(classRef.name(), index)) {
                    errors.add("@DependsOn(nodes) on '" + sourceNodeId
                            + "' references '" + classRef.name().local()
                            + "' which does not implement NodeSpec");
                } else {
                    mg.addDependency(sourceNodeId,
                            targetClass.declaredAnnotation(DECLARE_NODE)
                                       .value("id").asString());
                }
            }
        }
    }

    private void validateGoalMethod(ClassInfo dsClass, IndexView index, List<String> errors) {
        int count = 0;
        for (MethodInfo method : dsClass.methods()) {
            if (!method.hasAnnotation(GOAL_METHOD)) continue;
            count++;

            String loc = dsClass.name().local() + "#" + method.name();

            if (count > 1) {
                errors.add(loc + ": multiple @GoalMethod annotations — at most one allowed");
                continue;
            }

            if (method.parametersCount() < 2) {
                errors.add(loc + ": @GoalMethod must accept at least (G goals, DesiredStateGraph base)");
                continue;
            }

            if (!method.parameterType(1).name().equals(DESIRED_STATE_GRAPH)) {
                errors.add(loc + ": @GoalMethod second parameter must be DesiredStateGraph");
            }

            if (method.parametersCount() == 3
                    && !method.parameterType(2).name().equals(DESIRED_STATE_GRAPH_FACTORY)) {
                errors.add(loc + ": @GoalMethod third parameter must be DesiredStateGraphFactory");
            }

            if (method.parametersCount() > 3) {
                errors.add(loc + ": @GoalMethod accepts at most 3 parameters"
                        + " (goals, DesiredStateGraph, DesiredStateGraphFactory)");
            }

            DotName returnType = method.returnType().name();
            if (!returnType.equals(DESIRED_STATE_GRAPH) && !returnType.equals(COMPILATION_RESULT)) {
                errors.add(loc + ": @GoalMethod must return DesiredStateGraph or CompilationResult");
            }

            if (method.hasAnnotation(NODE)) {
                errors.add(loc + ": @GoalMethod cannot also be annotated with @Node");
            }
        }
    }

    private void validateFaultPolicyOnMethod(MethodInfo method, ClassInfo dsClass,
            IndexView index, List<String> errors) {
        AnnotationInstance fpAnn = method.annotation(FAULT_POLICY_DEF);
        AnnotationInstance fpContainer = method.annotation(FAULT_POLICIES);
        if (fpAnn == null && fpContainer == null) return;

        AnnotationInstance nodeAnn = method.annotation(NODE);
        if (nodeAnn == null) {
            errors.add("@FaultPolicyDef on method '" + method.name()
                    + "' which is not annotated with @Node — "
                    + "use @FaultPolicyDef on the interface for cross-type policies");
        }
    }

    private void validateFaultPolicyFaultTypes(ClassInfo dsClass, IndexView index,
            List<String> errors) {
        Set<String> validFaultTypes = new HashSet<>();
        for (FaultType ft : FaultType.values()) {
            validFaultTypes.add(ft.name());
        }

        for (AnnotationInstance fpAnn : collectAllFaultPolicies(dsClass)) {
            for (String ft : fpAnn.value("faultTypes").asStringArray()) {
                if (!validFaultTypes.contains(ft)) {
                    errors.add("Unknown FaultType '" + ft + "' in @FaultPolicyDef — valid: "
                            + String.join(", ", validFaultTypes));
                }
            }
        }
    }

    private void validateTierReviewMethods(ClassInfo dsClass, IndexView index,
            List<String> errors) {
        for (AnnotationInstance fpAnn : collectAllFaultPolicies(dsClass)) {
            AnnotationValue tiersVal = fpAnn.value("tiers");
            if (tiersVal == null) continue;

            for (AnnotationInstance tierAnn : tiersVal.asNestedArray()) {
                String reviewName = tierAnn.value("review").asString();
                MethodInfo reviewMethod = findMethod(dsClass, reviewName);

                if (reviewMethod == null) {
                    errors.add("@Tier review '" + reviewName
                            + "' not found on interface " + dsClass.name().local());
                    continue;
                }

                if (reviewMethod.parametersCount() != 2
                        || !reviewMethod.parameterType(0).name().equals(FAULT_EVENT)
                        || !reviewMethod.parameterType(1).name().equals(DESIRED_STATE_GRAPH)) {
                    errors.add("Review method '" + reviewName
                            + "' must accept (FaultEvent, DesiredStateGraph)");
                }

                if (!implementsNodeSpec(reviewMethod.returnType().name(), index)) {
                    errors.add("Review method '" + reviewName
                            + "' return type must implement NodeSpec");
                }
            }
        }
    }

    private List<AnnotationInstance> collectAllFaultPolicies(ClassInfo dsClass) {
        List<AnnotationInstance> result = new ArrayList<>();
        AnnotationInstance single = dsClass.declaredAnnotation(FAULT_POLICY_DEF);
        if (single != null) result.add(single);
        AnnotationInstance container = dsClass.declaredAnnotation(FAULT_POLICIES);
        if (container != null) {
            for (AnnotationInstance nested : container.value().asNestedArray()) {
                result.add(nested);
            }
        }
        for (MethodInfo method : dsClass.methods()) {
            AnnotationInstance methodFp = method.annotation(FAULT_POLICY_DEF);
            if (methodFp != null) result.add(methodFp);
            AnnotationInstance methodContainer = method.annotation(FAULT_POLICIES);
            if (methodContainer != null) {
                for (AnnotationInstance nested : methodContainer.value().asNestedArray()) {
                    result.add(nested);
                }
            }
        }
        return result;
    }

    private MethodInfo findMethod(ClassInfo classInfo, String name) {
        for (MethodInfo method : classInfo.methods()) {
            if (method.name().equals(name)) return method;
        }
        return null;
    }

    private boolean implementsNodeSpec(DotName typeName, IndexView index) {
        if (typeName.equals(NODE_SPEC)) return true;
        ClassInfo classInfo = index.getClassByName(typeName);
        if (classInfo == null) return false;
        for (DotName iface : classInfo.interfaceNames()) {
            if (iface.equals(NODE_SPEC)) return true;
        }
        if (classInfo.superName() != null) {
            return implementsNodeSpec(classInfo.superName(), index);
        }
        return false;
    }

    private boolean isDefaultMethod(MethodInfo method) {
        return (method.flags() & 0x0001) != 0 && !method.isAbstract();
    }

    private String resolveGraphKey(AnnotationInstance ann, IndexView index) {
        String ns = stringValueOrDefault(ann, index, "namespace", "");
        String nm = stringValueOrDefault(ann, index, "name", "");
        return ns + ":" + nm;
    }

    private static String stringValueOrDefault(
            AnnotationInstance ann, IndexView index, String name, String defaultValue) {
        AnnotationValue value = ann.valueWithDefault(index, name);
        if (value == null) {return defaultValue;}
        String s = value.asString();
        return s != null ? s : defaultValue;
    }

    static class MergedGraph {
        final String                    graphKey;
        final Map<String, String>       nodeIdToSource  = new LinkedHashMap<>();
        final List<String>              duplicateErrors = new ArrayList<>();
        final Map<String, List<String>> adjacency       = new HashMap<>();

        MergedGraph(String graphKey) {
            this.graphKey = graphKey;
        }

        void addNode(String nodeId, String source) {
            String existing = nodeIdToSource.putIfAbsent(nodeId, source);
            if (existing != null) {
                duplicateErrors.add("Duplicate node id '" + nodeId
                                    + "' in graph '" + graphKey + "' — declared on "
                                    + existing + " and " + source);
            }
        }

        void addDependency(String fromId, String toId) {
            adjacency.computeIfAbsent(fromId, k -> new ArrayList<>()).add(toId);
        }

        void validateDuplicateIds(List<String> errors) {
            errors.addAll(duplicateErrors);
        }

        void validateDependencyRefs(List<String> errors) {
            for (var entry : adjacency.entrySet()) {
                for (String dep : entry.getValue()) {
                    if (!nodeIdToSource.containsKey(dep)) {
                        errors.add("@DependsOn on '" + entry.getKey()
                                   + "' in graph '" + graphKey + "' references '"
                                   + dep + "' which is not declared as @Node or @DeclareNode");
                    }
                }
            }
        }

        void detectCycles(List<String> errors) {
            Set<String> visited = new HashSet<>();
            Set<String> inStack = new HashSet<>();
            for (String node : adjacency.keySet()) {
                if (!visited.contains(node)) {
                    Deque<String> path = new ArrayDeque<>();
                    if (hasCycle(node, visited, inStack, path)) {
                        errors.add("Circular dependency detected in graph '"
                                   + graphKey + "': " + String.join(" → ", path));
                    }
                }
            }
        }

        private boolean hasCycle(String node, Set<String> visited,
                                 Set<String> inStack, Deque<String> path) {
            visited.add(node);
            inStack.add(node);
            path.addLast(node);

            for (String dep : adjacency.getOrDefault(node, List.of())) {
                if (!visited.contains(dep)) {
                    if (hasCycle(dep, visited, inStack, path)) {return true;}
                } else if (inStack.contains(dep)) {
                    path.addLast(dep);
                    return true;
                }
            }

            inStack.remove(node);
            path.removeLast();
            return false;
        }
    }

}
