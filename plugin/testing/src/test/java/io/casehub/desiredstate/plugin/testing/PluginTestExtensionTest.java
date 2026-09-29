package io.casehub.desiredstate.plugin.testing;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.stream.Stream;

class PluginTestExtensionTest {

    @RegisterExtension
    static PluginTestExtension ext = PluginTestExtension.forPlugin("mock-resource");

    @TestFactory
    Stream<DynamicTest> tests() {
        return ext.discoverTests();
    }
}
