package io.casehub.desiredstate.annotations.spring;

import io.casehub.desiredstate.annotations.core.DescriptorScanner;
import io.casehub.desiredstate.annotations.runtime.FaultPolicyDescriptor;
import io.casehub.desiredstate.annotations.runtime.FaultPolicyFactory;
import io.casehub.desiredstate.annotations.runtime.GoalCompilerFactory;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.ThresholdFaultPolicy;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.IndexView;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.support.GenericApplicationContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

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
        List<IndexView> indexes = loadJandexIndexes();
        if (indexes.isEmpty()) {
            return;
        }
        CompositeIndex composite = CompositeIndex.create(indexes);

        List<GraphDescriptor> graphs = DescriptorScanner.scanGraphs(composite);
        for (GraphDescriptor gd : graphs) {
            GoalCompiler<?> compiler = GoalCompilerFactory.create(gd);
            String beanName = "goalCompiler_" + gd.namespace() + "_" + gd.name();
            context.registerBean(beanName, GoalCompiler.class, () -> compiler);
        }

        List<FaultPolicyDescriptor> policies = DescriptorScanner.scanFaultPolicies(composite);
        for (FaultPolicyDescriptor fpd : policies) {
            ThresholdFaultPolicy policy = FaultPolicyFactory.create(fpd, fpd.sourceClassName());
            String beanName = "faultPolicy_" + fpd.namespace();
            context.registerBean(beanName, ThresholdFaultPolicy.class, () -> policy);
        }
    }

    private List<IndexView> loadJandexIndexes() {
        List<IndexView> indexes = new ArrayList<>();
        try {
            Enumeration<URL> resources = Thread.currentThread()
                    .getContextClassLoader()
                    .getResources("META-INF/jandex.idx");
            while (resources.hasMoreElements()) {
                try (InputStream is = resources.nextElement().openStream()) {
                    indexes.add(new IndexReader(is).read());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load Jandex indexes", e);
        }
        return indexes;
    }
}
