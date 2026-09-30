package io.casehub.desiredstate.annotations.core;

import io.casehub.desiredstate.annotations.runtime.FaultPolicyDescriptor;
import io.casehub.desiredstate.annotations.runtime.FaultPolicyFactory;
import io.casehub.desiredstate.annotations.runtime.GoalCompilerFactory;
import io.casehub.desiredstate.annotations.runtime.GraphDescriptor;
import io.casehub.desiredstate.api.BeanRegistration;
import io.casehub.desiredstate.api.GoalCompiler;
import io.casehub.desiredstate.api.ThresholdFaultPolicy;
import org.jboss.jandex.IndexView;

import java.util.ArrayList;
import java.util.List;

public class AnnotationsDiscovery {

    public List<BeanRegistration> discover(IndexView index) {
        List<BeanRegistration> beans = new ArrayList<>();

        for (GraphDescriptor gd : DescriptorScanner.scanGraphs(index)) {
            GoalCompiler<?> compiler = GoalCompilerFactory.create(gd);
            beans.add(new BeanRegistration(
                "goalCompiler_" + gd.namespace() + "_" + gd.name(),
                GoalCompiler.class, compiler));
        }

        for (FaultPolicyDescriptor fpd : DescriptorScanner.scanFaultPolicies(index)) {
            ThresholdFaultPolicy policy = FaultPolicyFactory.create(fpd, fpd.sourceClassName());
            beans.add(new BeanRegistration(
                "faultPolicy_" + fpd.namespace(),
                ThresholdFaultPolicy.class, policy));
        }

        return beans;
    }
}
