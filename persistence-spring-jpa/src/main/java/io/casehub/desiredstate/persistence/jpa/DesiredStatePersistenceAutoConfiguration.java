package io.casehub.desiredstate.persistence.jpa;

import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.FaultCountStore;
import io.casehub.desiredstate.api.ReconciliationStateStore;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

@AutoConfiguration
@ConditionalOnBean(DataSource.class)
public class DesiredStatePersistenceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(FaultCountStore.class)
    public FaultCountStore faultCountStore(EntityManager em) {
        return new SpringJpaFaultCountStore(em);
    }

    @Bean
    @ConditionalOnMissingBean(ReconciliationStateStore.class)
    public ReconciliationStateStore reconciliationStateStore(EntityManager em,
                                                             DesiredStateGraphFactory graphFactory) {
        return new SpringJpaReconciliationStateStore(em, graphFactory);
    }
}
