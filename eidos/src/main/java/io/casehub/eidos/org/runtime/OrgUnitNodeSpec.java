package io.casehub.eidos.org.runtime;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.eidos.org.api.OrganizationalUnit;

@NodeTypeId("org:unit")
public record OrgUnitNodeSpec(OrganizationalUnit unit) implements NodeSpec {
    @Override
    public NodeType nodeType() {return OrgNodeTypes.UNIT;}
}
