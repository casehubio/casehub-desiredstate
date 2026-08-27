package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.HumanGating;

import java.util.Map;

public sealed interface NodeDescriptor
        permits NodeDescriptor.InterfaceNode, NodeDescriptor.ClassNode,
                NodeDescriptor.InlineNode {

    String id();

    record InterfaceNode(String id, String methodName, String returnTypeName,
                         HumanGating humanGating) implements NodeDescriptor {}

    record ClassNode(String id, String className) implements NodeDescriptor {}

    record InlineNode(String id, String specClassName,
                      Map<String, Object> specValues,
                      HumanGating humanGating) implements NodeDescriptor {}
}
