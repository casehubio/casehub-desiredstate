package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.NodeTypeId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrgNodeTypeIdTest {

    @Test void orgUnitSpecHasNodeTypeId() {
        var annotation = OrgUnitNodeSpec.class.getAnnotation(NodeTypeId.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("org:unit");
    }

    @Test void orgRelationshipSpecHasNodeTypeId() {
        var annotation = OrgRelationshipNodeSpec.class.getAnnotation(NodeTypeId.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("org:relationship");
    }

    @Test void annotationValueMatchesNodeType() {
        var unitSpec = new OrgUnitNodeSpec(null);
        assertThat(OrgUnitNodeSpec.class.getAnnotation(NodeTypeId.class).value())
                .isEqualTo(unitSpec.nodeType().value());

        var relSpec = new OrgRelationshipNodeSpec(null);
        assertThat(OrgRelationshipNodeSpec.class.getAnnotation(NodeTypeId.class).value())
                .isEqualTo(relSpec.nodeType().value());
    }
}
