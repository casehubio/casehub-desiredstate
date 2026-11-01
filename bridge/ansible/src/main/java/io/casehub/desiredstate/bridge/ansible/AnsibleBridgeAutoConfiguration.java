package io.casehub.desiredstate.bridge.ansible;

import io.casehub.desiredstate.bridge.BridgeProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

@AutoConfiguration
@ConditionalOnClass(BridgeProvider.class)
public class AnsibleBridgeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "ansibleBridgeProvider")
    public AnsibleBridgeProvider ansibleBridgeProvider() {
        Map<String, AnsibleModuleConfig> configs = discoverConfigs();
        if (configs.isEmpty()) {
            return null;
        }
        return new AnsibleBridgeProvider(configs, "ansible-playbook");
    }

    private Map<String, AnsibleModuleConfig> discoverConfigs() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (Resource resource : resolver.getResources("classpath*:META-INF/desiredstate/bridges/*.yaml")) {
                if (resource.isReadable()) {
                    try (InputStream is = resource.getInputStream()) {
                        var configs = AnsibleManifestExtensionParser.parse(is);
                        if (!configs.isEmpty()) {
                            return configs;
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (IOException ignored) {}
        return Map.of();
    }
}
