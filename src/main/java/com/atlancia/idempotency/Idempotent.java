package com.atlancia.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    String key() default "";
    String headerName() default "";
    long ttl() default -1;
    TimeUnit timeUnit() default TimeUnit.HOURS;
    ConcurrentStrategy onConcurrent() default ConcurrentStrategy.DEFAULT;
}
