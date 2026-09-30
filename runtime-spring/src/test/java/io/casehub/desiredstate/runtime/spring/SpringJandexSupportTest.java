package io.casehub.desiredstate.runtime.spring;

import org.jboss.jandex.IndexView;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class SpringJandexSupportTest {

    @Test
    void loadCompositeIndex_returnsNonNullIndex() {
        IndexView index = SpringJandexSupport.loadCompositeIndex();
        assertThat(index).isNotNull();
    }

    @Test
    void scanNodeTypes_returnsEmptyMapForEmptyIndex() throws Exception {
        IndexView index = org.jboss.jandex.Index.of(new Class<?>[0]);
        Map<String, String> types = SpringJandexSupport.scanNodeTypes(index);
        assertThat(types).isEmpty();
    }
}
