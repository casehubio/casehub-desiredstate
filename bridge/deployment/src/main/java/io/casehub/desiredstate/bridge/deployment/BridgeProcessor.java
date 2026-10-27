package io.casehub.desiredstate.bridge.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;

import java.util.List;

public class BridgeProcessor {

    private static final String BRIDGE_MANIFEST_PREFIX = "META-INF/desiredstate/bridges/";

    @BuildStep
    NativeImageResourceBuildItem registerBridgeManifests() {
        return new NativeImageResourceBuildItem(List.of(BRIDGE_MANIFEST_PREFIX));
    }
}
