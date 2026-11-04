package io.casehub.desiredstate.bridge;

import java.util.List;

public interface DiscoveryCapable {
    List<DiscoveredResource> discover(String typeId, String tenancyId);
}
