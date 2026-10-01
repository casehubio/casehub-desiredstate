package io.casehub.desiredstate.annotations;

import io.casehub.desiredstate.api.NodeSpec;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface OrderBefore {
    Class<? extends NodeSpec> value();
    Class<? extends NodeSpec> after();
}
