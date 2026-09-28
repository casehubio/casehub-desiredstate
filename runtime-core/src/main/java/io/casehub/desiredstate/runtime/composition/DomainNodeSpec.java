package io.casehub.desiredstate.runtime.composition;

import io.casehub.desiredstate.api.DomainId;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;

public record DomainNodeSpec(DomainId domainId, DomainRegistration registration) implements NodeSpec {
    static final NodeType DOMAIN_NODE_TYPE = NodeType.of("domain");

    @Override
    public NodeType nodeType() { return DOMAIN_NODE_TYPE; }
}
