package io.casehub.desiredstate.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BeanRegistrationTest {

    @Test
    void carriesNameTypeAndInstance() {
        var reg = new BeanRegistration("myBean", String.class, "hello");
        assertThat(reg.name()).isEqualTo("myBean");
        assertThat(reg.type()).isEqualTo(String.class);
        assertThat(reg.instance()).isEqualTo("hello");
    }

    @Test
    void rejectsNullName() {
        assertThatThrownBy(() -> new BeanRegistration(null, String.class, "hello"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullType() {
        assertThatThrownBy(() -> new BeanRegistration("myBean", null, "hello"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullInstance() {
        assertThatThrownBy(() -> new BeanRegistration("myBean", String.class, null))
            .isInstanceOf(NullPointerException.class);
    }
}
