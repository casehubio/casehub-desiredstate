package io.casehub.eidos.org.runtime;

public interface OrgChannelCreator {
    boolean createGroupChannel(String groupId, String tenancyId);
    default void deleteGroupChannel(String groupId, String tenancyId) {}
}
