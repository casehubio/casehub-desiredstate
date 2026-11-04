package io.casehub.desiredstate.annotations.runtime;

import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.api.TargetStatus;

import java.util.Map;

public sealed interface NodeDescriptor
        permits NodeDescriptor.InterfaceNode, NodeDescriptor.ClassNode,
                NodeDescriptor.InlineNode {

    String id();

    record InterfaceNode(String id, String methodName, String returnTypeName,
                         HumanGating humanGating, TargetStatus targetStatus) implements NodeDescriptor {
        @io.quarkus.runtime.annotations.RecordableConstructor
        public InterfaceNode {}

        public InterfaceNode(String id, String methodName, String returnTypeName,
                             HumanGating humanGating) {
            this(id, methodName, returnTypeName, humanGating, TargetStatus.ACTIVE);
        }
    }

    record ClassNode(String id, String className) implements NodeDescriptor {}

    record InlineNode(String id, String specClassName,
                      Map<String, Object> specValues,
                      HumanGating humanGating, TargetStatus targetStatus) implements NodeDescriptor {
        @io.quarkus.runtime.annotations.RecordableConstructor
        public InlineNode {}

        public InlineNode(String id, String specClassName,
                          Map<String, Object> specValues,
                          HumanGating humanGating) {
            this(id, specClassName, specValues, humanGating, TargetStatus.ACTIVE);
        }
    }
}
