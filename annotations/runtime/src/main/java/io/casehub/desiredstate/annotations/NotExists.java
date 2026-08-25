package io.casehub.desiredstate.annotations;

import io.casehub.desiredstate.annotations.runtime.Direction;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface NotExists {
    String type();
    String of() default "";
    Direction direction() default Direction.DEPENDENCIES;
}
