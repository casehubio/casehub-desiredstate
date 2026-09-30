package io.casehub.desiredstate.ts.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.desiredstate.api.BeanRegistration;
import io.casehub.desiredstate.runtime.spring.SpringJandexSupport;
import io.casehub.desiredstate.ts.TsDslDiscovery;
import io.casehub.desiredstate.ts.TsEnvelope;
import io.casehub.desiredstate.ts.TsGoalCompilerFactory;
import io.casehub.desiredstate.ts.TsLifecycleEnvelope;
import org.jboss.jandex.IndexView;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@AutoConfiguration
@ConditionalOnClass(TsGoalCompilerFactory.class)
public class DesiredStateTsDslAutoConfiguration implements SmartInitializingSingleton {

    private final GenericApplicationContext context;

    public DesiredStateTsDslAutoConfiguration(GenericApplicationContext context) {
        this.context = context;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            IndexView           index        = SpringJandexSupport.loadCompositeIndex();
            Map<String, String> typeRegistry = SpringJandexSupport.scanNodeTypes(index);
            if (typeRegistry.isEmpty()) {
                return;
            }

            ObjectMapper                            mapper    = new ObjectMapper();
            List<TsDslDiscovery.DiscoveredEnvelope> envelopes = discoverTsEnvelopes(mapper);

            new TsDslDiscovery().discover(envelopes, typeRegistry)
                                .forEach(this::registerBean);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to discover TypeScript DSL desired state graphs", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void registerBean(BeanRegistration reg) {
        context.registerBean(reg.name(), (Class<T>) reg.type(), () -> (T) reg.instance());
    }

    private List<TsDslDiscovery.DiscoveredEnvelope> discoverTsEnvelopes(ObjectMapper mapper) throws IOException {
        List<TsDslDiscovery.DiscoveredEnvelope> envelopes = new ArrayList<>();
        PathMatchingResourcePatternResolver     resolver  = new PathMatchingResourcePatternResolver();
        for (Resource resource : resolver.getResources("classpath*:META-INF/desiredstate/*.ds.json")) {
            if (resource.isReadable()) {
                try (InputStream is = resource.getInputStream()) {
                    String filename = resource.getFilename();
                    String name     = filename != null ? filename.replace(".ds.json", "") : "unknown";

                    var tree = mapper.readTree(is);
                    if (tree.has("phases")) {
                        TsLifecycleEnvelope lifecycle = mapper.treeToValue(tree, TsLifecycleEnvelope.class);
                        envelopes.add(new TsDslDiscovery.DiscoveredEnvelope(name, null, lifecycle));
                    } else {
                        TsEnvelope single = mapper.treeToValue(tree, TsEnvelope.class);
                        envelopes.add(new TsDslDiscovery.DiscoveredEnvelope(name, single, null));
                    }
                }
            }
        }
        return envelopes;
    }
}
