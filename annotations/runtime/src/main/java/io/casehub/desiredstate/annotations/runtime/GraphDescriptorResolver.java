package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.DesiredNode;
import io.casehub.desiredstate.api.DesiredStateGraph;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class GraphDescriptorResolver {

    private GraphDescriptorResolver() {}

    public static List<ResolvedRule> resolveRules(List<GraphRuleDescriptor> descriptors) {
        List<ResolvedRule> rules = new ArrayList<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        for (GraphRuleDescriptor grd : descriptors) {
            try {
                Class<?> ruleClass = classLoader.loadClass(grd.sourceClassName());
                Object ruleInstance = java.lang.reflect.Modifier.isInterface(ruleClass.getModifiers())
                        ? null : ruleClass.getDeclaredConstructor().newInstance();
                Method ruleMethod = findRuleMethod(ruleClass, grd);
                if (grd.imperative()) {
                    rules.add(new ResolvedRule.ImperativeRule(grd.methodName(), ruleMethod, ruleInstance));
                } else {
                    rules.add(new ResolvedRule.ParameterizedReflectiveRule(
                            grd.methodName(), ruleMethod, ruleInstance, grd.patterns()));
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to resolve graph rule: " + grd.methodName(), e);
            }
        }
        return rules;
    }

    public static List<ResolvedInvariant> resolveInvariants(List<GraphInvariantDescriptor> descriptors) {
        List<ResolvedInvariant> invariants = new ArrayList<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        for (GraphInvariantDescriptor gid : descriptors) {
            try {
                Class<?> cls = classLoader.loadClass(gid.sourceClassName());
                Object instance = java.lang.reflect.Modifier.isInterface(cls.getModifiers())
                        ? null : cls.getDeclaredConstructor().newInstance();
                Method method = findInvariantMethod(cls, gid);
                if (gid.imperative()) {
                    invariants.add(new ResolvedInvariant.ImperativeInvariant(gid.methodName(), method, instance));
                } else {
                    invariants.add(new ResolvedInvariant.ParameterizedReflectiveInvariant(
                            gid.methodName(), method, instance, gid.patterns()));
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to resolve graph invariant: " + gid.methodName(), e);
            }
        }
        return invariants;
    }

    private static Method findRuleMethod(Class<?> cls, GraphRuleDescriptor grd)
            throws NoSuchMethodException {
        if (grd.imperative()) {
            return cls.getMethod(grd.methodName(), DesiredStateGraph.class);
        }
        Class<?>[] paramTypes = new Class<?>[grd.patterns().size()];
        for (int i = 0; i < grd.patterns().size(); i++) {
            paramTypes[i] = grd.patterns().get(i).kind() == PatternKind.NOT_EXISTS
                    ? Void.class : DesiredNode.class;
        }
        return cls.getMethod(grd.methodName(), paramTypes);
    }

    private static Method findInvariantMethod(Class<?> cls, GraphInvariantDescriptor gid)
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
}
