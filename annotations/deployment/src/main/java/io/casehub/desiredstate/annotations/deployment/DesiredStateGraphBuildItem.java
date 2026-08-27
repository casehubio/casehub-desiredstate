package io.casehub.desiredstate.annotations.deployment;

import io.quarkus.builder.item.MultiBuildItem;

public final class DesiredStateGraphBuildItem extends MultiBuildItem {

    private final String namespace;
    private final String name;
    private final String source;

    public DesiredStateGraphBuildItem(String namespace, String name, String source) {
        this.namespace = namespace;
        this.name = name;
        this.source = source;
    }

    public String namespace() { return namespace; }

    public String name() { return name; }

    public String source() { return source; }

    public String qualifiedName() { return namespace + ":" + name; }
}
