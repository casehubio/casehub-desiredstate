package io.casehub.desiredstate.annotations.spring;

import io.casehub.desiredstate.annotations.core.AnnotationsDiscovery;
import io.casehub.desiredstate.api.BeanRegistration;
import io.casehub.desiredstate.annotations.runtime.GoalCompilerFactory;
import io.casehub.desiredstate.runtime.spring.SpringJandexSupport;
import org.jboss.jandex.IndexView;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.support.GenericApplicationContext;

@AutoConfiguration
@ConditionalOnClass(GoalCompilerFactory.class)
public class DesiredStateAnnotationsAutoConfiguration
        implements SmartInitializingSingleton {

    private final GenericApplicationContext context;

    public DesiredStateAnnotationsAutoConfiguration(GenericApplicationContext ctx) {
        this.context = ctx;
    }

    @Override
    public void afterSingletonsInstantiated() {
        IndexView index = SpringJandexSupport.loadCompositeIndex();
        new AnnotationsDiscovery().discover(index)
            .forEach(reg -> registerBean(reg));
    }

    @SuppressWarnings("unchecked")
    private <T> void registerBean(BeanRegistration reg) {
        context.registerBean(reg.name(), (Class<T>) reg.type(), () -> (T) reg.instance());
    }

}
