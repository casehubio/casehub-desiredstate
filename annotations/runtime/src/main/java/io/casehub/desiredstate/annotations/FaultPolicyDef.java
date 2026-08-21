package io.casehub.desiredstate.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(FaultPolicies.class)
public @interface FaultPolicyDef {
    String[] faultTypes();
    String[] nodeTypes() default {};
    String[] ignoreTypes() default {};
    String namespace() default "";
    Tier[] tiers();
}
