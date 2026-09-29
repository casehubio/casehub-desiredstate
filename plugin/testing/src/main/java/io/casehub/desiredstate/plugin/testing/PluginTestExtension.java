package io.casehub.desiredstate.plugin.testing;

import io.casehub.desiredstate.api.NodeStatus;
import io.casehub.desiredstate.api.ProvisionResult;
import io.casehub.desiredstate.api.DeprovisionResult;
import io.casehub.desiredstate.plugin.model.PluginModel;
import io.casehub.desiredstate.plugin.model.PluginParser;
import io.casehub.desiredstate.plugin.runtime.PluginDescriptor;
import io.casehub.desiredstate.plugin.runtime.PluginValidationException;
import io.casehub.desiredstate.plugin.runtime.PluginValidator;
import io.casehub.desiredstate.plugin.testing.PluginTestAssertions.ActionResult;
import io.casehub.yaml.step.testing.infrastructure.InfrastructureFactory;
import io.casehub.yaml.step.testing.infrastructure.TestInfrastructure;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class PluginTestExtension implements BeforeAllCallback, AfterAllCallback {

    private final String pluginType;

    private PluginDescriptor descriptor;
    private TestInfrastructure infrastructure;
    private List<PluginTestSuite> suites;
    private PluginTestRunner runner;

    private PluginTestExtension(String pluginType) {
        this.pluginType = pluginType;
    }

    public static PluginTestExtension forPlugin(String pluginType) {
        return new PluginTestExtension(pluginType);
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        PluginModel model = loadPluginModel();
        PluginValidator.validatePlugin(model, Map.of(), PluginValidator.BUILT_IN_PRIMITIVES);

        descriptor = toDescriptor(model);

        suites = discoverTestSuites();
        if (suites.isEmpty()) {
            throw new IllegalStateException(
                "No test YAML files found for plugin '" + pluginType
                    + "' at META-INF/desiredstate/tests/");
        }

        String infraType = suites.get(0).infrastructureType();
        infrastructure = InfrastructureFactory.create(infraType);
        infrastructure.start();

        Map<String, Object> authVariables = mergeAuthVariables(suites);
        runner = new PluginTestRunner(descriptor, authVariables);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (infrastructure != null) {
            infrastructure.stop();
        }
    }

    public Stream<DynamicTest> discoverTests() {
        List<DynamicTest> tests = new ArrayList<>();
        for (PluginTestSuite suite : suites) {
            List<Map<String, Object>> setupStubs = suite.setup().stubs();
            boolean firstInSuite = true;

            for (PluginTestCase testCase : suite.testCases()) {
                final boolean isFirst = firstInSuite;
                firstInSuite = false;

                tests.add(DynamicTest.dynamicTest(testCase.name(), () -> {
                    if (isFirst && !setupStubs.isEmpty()) {
                        infrastructure.configure(setupStubs);
                    } else {
                        infrastructure.resetBetweenTests(setupStubs);
                    }

                    if (!testCase.expectations().isEmpty()) {
                        infrastructure.configure(testCase.expectations());
                    }

                    Map<String, Object> specFields = mergeSpec(suite, testCase);
                    specFields = runner.resolveInfraBindings(specFields,
                        infrastructure.variableBindings());

                    ActionResult result = executeAction(testCase, specFields);
                    PluginTestAssertions.evaluate(testCase.assertions(), result);
                }));
            }
        }
        return tests.stream();
    }

    private ActionResult executeAction(PluginTestCase testCase, Map<String, Object> specFields) {
        return switch (testCase.action()) {
            case "provision" -> {
                ProvisionResult r;
                if (testCase.faultInjection() != null) {
                    r = runner.runProvisionWithFaultInjection(specFields,
                        testCase.faultInjection().failCount(),
                        testCase.faultInjection().error());
                } else {
                    r = runner.runProvision(specFields);
                }
                yield ActionResult.ofProvision(r);
            }
            case "deprovision" -> {
                DeprovisionResult r = runner.runDeprovision(specFields);
                yield ActionResult.ofDeprovision(r);
            }
            case "actual-state" -> {
                NodeStatus status = runner.runActualState(specFields);
                yield ActionResult.ofActualState(status);
            }
            case "validate" -> {
                try {
                    PluginModel model = loadPluginModel();
                    PluginValidator.validatePlugin(model, Map.of(),
                        PluginValidator.BUILT_IN_PRIMITIVES);
                    yield ActionResult.ofValidationError(null);
                } catch (PluginValidationException e) {
                    yield ActionResult.ofValidationError(e.getMessage());
                } catch (IOException e) {
                    yield ActionResult.ofValidationError("Failed to load plugin: " + e.getMessage());
                }
            }
            default -> throw new IllegalArgumentException(
                "Unknown test action: '" + testCase.action() + "'");
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeSpec(PluginTestSuite suite, PluginTestCase testCase) {
        Map<String, Object> merged = new HashMap<>();
        Object setupSpec = suite.setup().variables().get("spec");
        if (setupSpec instanceof Map<?, ?> m) {
            merged.putAll((Map<String, Object>) m);
        }
        merged.putAll(testCase.spec());
        return merged;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeAuthVariables(List<PluginTestSuite> suites) {
        Map<String, Object> merged = new HashMap<>();
        for (PluginTestSuite suite : suites) {
            Object auth = suite.setup().variables().get("auth");
            if (auth instanceof Map<?, ?> m) {
                merged.putAll((Map<String, Object>) m);
            }
        }
        return merged;
    }

    private PluginModel loadPluginModel() throws IOException {
        String path = "META-INF/desiredstate/plugins/" + pluginType + ".yaml";
        try (InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException(
                    "Plugin YAML not found at " + path);
            }
            return PluginParser.parse(is);
        }
    }

    private List<PluginTestSuite> discoverTestSuites() throws IOException {
        List<PluginTestSuite> result = new ArrayList<>();
        String basePath = "META-INF/desiredstate/tests/";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> urls = cl.getResources(basePath);

        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if ("file".equals(url.getProtocol())) {
                java.io.File dir = new java.io.File(url.getPath());
                java.io.File[] files = dir.listFiles((d, name) ->
                    name.endsWith(".test.yaml") || name.endsWith(".test.yml"));
                if (files != null) {
                    for (java.io.File file : files) {
                        try (InputStream is = new java.io.FileInputStream(file)) {
                            PluginTestSuite suite = PluginTestYamlParser.parse(is);
                            if (pluginType.equals(suite.pluginType())) {
                                result.add(suite);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    private static PluginDescriptor toDescriptor(PluginModel model) {
        Map<String, String> authRefs = new HashMap<>();
        if (model.header().auth() != null) {
            model.header().auth().forEach((k, v) -> authRefs.put(k, v.credentialRef()));
        }
        return new PluginDescriptor(
            model.header().type(), model.header().version(),
            model.header().resyncInterval() != null
                ? Duration.parse("PT" + model.header().resyncInterval().toUpperCase())
                : Duration.ofMinutes(5),
            authRefs, model.spec(),
            model.actualStateSteps(),
            model.provisioner().provisionSteps(),
            model.provisioner().deprovisionSteps(),
            model.faultPolicies(), model.cbr(), model.ras());
    }
}
