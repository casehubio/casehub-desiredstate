package io.casehub.desiredstate.api;

import java.util.List;

public record HookDescriptor(
        List<LifecycleStep> provisionPre,
        List<LifecycleStep> provisionPost,
        List<LifecycleStep> deprovisionPre,
        List<LifecycleStep> deprovisionPost) {

    public HookDescriptor {
        if (provisionPre == null) provisionPre = List.of();
        if (provisionPost == null) provisionPost = List.of();
        if (deprovisionPre == null) deprovisionPre = List.of();
        if (deprovisionPost == null) deprovisionPost = List.of();
    }

    public boolean isEmpty() {
        return provisionPre.isEmpty() && provisionPost.isEmpty()
                && deprovisionPre.isEmpty() && deprovisionPost.isEmpty();
    }
}
