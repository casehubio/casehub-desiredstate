package io.casehub.desiredstate.bridge;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BridgeOperationTest {

    @Test
    void applyCarriesSpecFields() {
        var spec = Map.of("ami", (Object) "ami-123");
        var op = new BridgeOperation.Apply("aws_instance", "i-1", spec);
        assertEquals("aws_instance", op.typeId());
        assertEquals("i-1", op.resourceId());
        assertEquals("ami-123", op.spec().get("ami"));
    }

    @Test
    void destroyCarriesIdentity() {
        var op = new BridgeOperation.Destroy("aws_instance", "i-1");
        assertEquals("aws_instance", op.typeId());
    }

    @Test
    void sealedExhaustiveness() {
        BridgeOperation op = new BridgeOperation.ReadState("aws_instance", "i-1");
        var label = switch (op) {
            case BridgeOperation.Apply a -> "apply";
            case BridgeOperation.Destroy d -> "destroy";
            case BridgeOperation.ReadState r -> "read";
            case BridgeOperation.Plan p -> "plan";
            case BridgeOperation.ApplyPlan ap -> "apply-plan";
        };
        assertEquals("read", label);
    }
}
