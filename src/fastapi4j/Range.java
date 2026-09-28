package fastapi4j;

import java.lang.annotation.*;

/** Bounds a numeric value, inclusive. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface Range { double min() default -Double.MAX_VALUE;
    double max() default Double.MAX_VALUE; }
