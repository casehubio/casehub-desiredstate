package io.casehub.desiredstate.plugin.deployment;

import io.casehub.desiredstate.plugin.model.PluginModel;
import io.quarkus.builder.item.MultiBuildItem;

public final class PluginBuildItem extends MultiBuildItem {

    private final String fileName;
    private final PluginModel model;

    public PluginBuildItem(String fileName, PluginModel model) {
        this.fileName = fileName;
        this.model = model;
    }

    public String fileName() { return fileName; }
    public PluginModel model() { return model; }
    public String type() { return model.header().type(); }
}
