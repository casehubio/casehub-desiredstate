package io.casehub.desiredstate.bridge.ansible;

import io.casehub.desiredstate.bridge.BridgeProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
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
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            Enumeration<URL> urls = cl.getResources("META-INF/desiredstate/bridges/");
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                try (InputStream is = url.openStream()) {
                    if (is != null) {
                        var configs = AnsibleManifestExtensionParser.parse(is);
                        if (!configs.isEmpty()) {
                            return configs;
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (IOException ignored) {}
        return Map.of();
    }
}
