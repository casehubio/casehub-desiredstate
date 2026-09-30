package io.casehub.desiredstate.annotations.core;

import io.casehub.desiredstate.api.BeanRegistration;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnnotationsDiscoveryTest {

    @Test
    void discover_returnsEmptyForEmptyIndex() throws Exception {
        IndexView index = Index.of(new Class<?>[0]);
        List<BeanRegistration> beans = new AnnotationsDiscovery().discover(index);
        assertThat(beans).isEmpty();
    }
}
