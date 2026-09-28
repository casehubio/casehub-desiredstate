package io.casehub.desiredstate.ts.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.annotations.runtime.DependencyDescriptor;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.annotations.runtime.NodeDescriptor;
import io.casehub.desiredstate.annotations.runtime.ResolvedInvariant;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeTypeId;
import io.casehub.desiredstate.ts.TsEnvelope;
import io.casehub.desiredstate.ts.TsEnvelopeNode;
import io.casehub.desiredstate.ts.TsGoalCompilerFactory;
import io.casehub.desiredstate.ts.TsLifecycleEnvelope;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.IndexView;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AutoConfiguration
@ConditionalOnClass(TsGoalCompilerFactory.class)
public class DesiredStateTsDslAutoConfiguration implements SmartInitializingSingleton {

    private static final DotName NODE_SPEC = DotName.createSimple(NodeSpec.class.getName());
    private static final DotName NODE_TYPE_ID = DotName.createSimple(NodeTypeId.class.getName());

    private final GenericApplicationContext context;

    public DesiredStateTsDslAutoConfiguration(GenericApplicationContext context) {
        this.context = context;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            IndexView index = loadCompositeJandexIndex();
            Map<String, String> typeRegistry = scanNodeTypes(index);
            if (typeRegistry.isEmpty()) {
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            List<DiscoveredEnvelope> envelopes = discoverTsEnvelopes(mapper);

            for (DiscoveredEnvelope discovered : envelopes) {
                if (discovered.lifecycle != null) {
                    GoalCompiler<?> compiler = TsGoalCompilerFactory.createLifecycle(
                            discovered.lifecycle, typeRegistry, List.of());
                    String beanName = "goalCompiler_ts_" + discovered.name;
                    context.registerBean(beanName, GoalCompiler.class, () -> compiler);
                } else if (discovered.single != null) {
                    GraphDescriptor descriptor = toGraphDescriptor(discovered.single, typeRegistry);
                    GoalCompiler<?> compiler = TsGoalCompilerFactory.create(
                            descriptor, typeRegistry, List.of(), List.of(), List.of());
                    String beanName = "goalCompiler_ts_" + discovered.name;
                    context.registerBean(beanName, GoalCompiler.class, () -> compiler);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to discover TypeScript DSL desired state graphs", e);
        }
    }

    private List<DiscoveredEnvelope> discoverTsEnvelopes(ObjectMapper mapper) throws IOException {
        List<DiscoveredEnvelope> envelopes = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        for (Resource resource : resolver.getResources("classpath*:META-INF/desiredstate/*.ds.json")) {
            if (resource.isReadable()) {
                try (InputStream is = resource.getInputStream()) {
                    String filename = resource.getFilename();
                    String name = filename != null ? filename.replace(".ds.json", "") : "unknown";

                    var tree = mapper.readTree(is);
                    if (tree.has("phases")) {
                        TsLifecycleEnvelope lifecycle = mapper.treeToValue(tree, TsLifecycleEnvelope.class);
                        envelopes.add(new DiscoveredEnvelope(name, null, lifecycle));
                    } else {
                        TsEnvelope single = mapper.treeToValue(tree, TsEnvelope.class);
                        envelopes.add(new DiscoveredEnvelope(name, single, null));
                    }
                }
            }
        }
        return envelopes;
    }

    private GraphDescriptor toGraphDescriptor(TsEnvelope envelope, Map<String, String> typeRegistry) {
        List<NodeDescriptor> nodes = new ArrayList<>();

        for (TsEnvelopeNode en : envelope.nodes()) {
            String specClassName = typeRegistry.get(en.type());
            nodes.add(new NodeDescriptor.InlineNode(en.id(), specClassName,
                    en.spec() != null ? en.spec() : Map.of(), null));
        }

        return new GraphDescriptor(
                envelope.namespace(), envelope.name(),
                null, null, nodes, envelope.dependencies(),
                List.of(), null, List.of(), List.of());
    }

    private Map<String, String> scanNodeTypes(IndexView index) {
        Map<String, String> registry = new HashMap<>();
        for (AnnotationInstance ann : index.getAnnotations(NODE_TYPE_ID)) {
            if (ann.target().kind() == org.jboss.jandex.AnnotationTarget.Kind.CLASS) {
                ClassInfo cls = ann.target().asClass();
                if (index.getAllKnownImplementors(NODE_SPEC).contains(cls)) {
                    registry.put(ann.value().asString(), cls.name().toString());
                }
            }
        }
        return registry;
    }

    private IndexView loadCompositeJandexIndex() throws IOException {
        List<IndexView> indexes = new ArrayList<>();
        Enumeration<URL> resources = Thread.currentThread()
                .getContextClassLoader()
                .getResources("META-INF/jandex.idx");
        while (resources.hasMoreElements()) {
            try (InputStream is = resources.nextElement().openStream()) {
                indexes.add(new IndexReader(is).read());
            }
        }
        return indexes.isEmpty() ? Index.of(new Class<?>[0]) : CompositeIndex.create(indexes);
    }

    private record DiscoveredEnvelope(String name, TsEnvelope single, TsLifecycleEnvelope lifecycle) {}
}
