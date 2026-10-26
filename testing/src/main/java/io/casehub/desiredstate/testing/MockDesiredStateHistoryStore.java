package io.casehub.desiredstate.testing;

import io.casehub.desiredstate.api.InMemoryDesiredStateHistoryStore;

public class MockDesiredStateHistoryStore extends InMemoryDesiredStateHistoryStore {

    public void assertVersionCount(String tenancyId, int expected) {
        var versions = listVersions(tenancyId);
        if (versions.size() != expected) {
            throw new AssertionError("Expected " + expected + " versions for " + tenancyId
                + " but found " + versions.size());
        }
    }
}
