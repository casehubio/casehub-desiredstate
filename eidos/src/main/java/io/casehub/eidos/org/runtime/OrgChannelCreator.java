package io.casehub.eidos.org.runtime;

public interface OrgChannelCreator {
    boolean createGroupChannel(ChannelRequest request);

    default void deleteGroupChannel(String groupId, String tenancyId) {}

    record ChannelRequest(
            String groupId,
            String groupName,
            java.util.List<String> memberIds,
            String coordinatorId,
            String tenancyId
    ) {}
}
