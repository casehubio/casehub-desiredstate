package io.casehub.desiredstate.api;

import java.util.List;

public record HookDescriptor(
        List<LifecycleStep> provisionPre,
        List<LifecycleStep> provisionPost,
        List<LifecycleStep> deprovisionPre,
        List<LifecycleStep> deprovisionPost,
        List<LifecycleStep> suspendPre,
        List<LifecycleStep> suspendPost,
        List<LifecycleStep> resumePre,
        List<LifecycleStep> resumePost) {

    public HookDescriptor(List<LifecycleStep> provisionPre,
                          List<LifecycleStep> provisionPost,
                          List<LifecycleStep> deprovisionPre,
                          List<LifecycleStep> deprovisionPost) {
        this(provisionPre, provisionPost, deprovisionPre, deprovisionPost,
             List.of(), List.of(), List.of(), List.of());
    }

    public HookDescriptor {
        if (provisionPre == null) {provisionPre = List.of();}
        if (provisionPost == null) {provisionPost = List.of();}
        if (deprovisionPre == null) {deprovisionPre = List.of();}
        if (deprovisionPost == null) {deprovisionPost = List.of();}
        if (suspendPre == null) {suspendPre = List.of();}
        if (suspendPost == null) {suspendPost = List.of();}
        if (resumePre == null) {resumePre = List.of();}
        if (resumePost == null) {resumePost = List.of();}
    }

    public boolean isEmpty() {
        return provisionPre.isEmpty() && provisionPost.isEmpty()
               && deprovisionPre.isEmpty() && deprovisionPost.isEmpty()
               && suspendPre.isEmpty() && suspendPost.isEmpty()
               && resumePre.isEmpty() && resumePost.isEmpty();
    }
}
