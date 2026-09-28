package fastapi4j;

import java.lang.annotation.*;

/** Bounds string, collection, map or array length. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface Size { int min() default 0;
    int max() default Integer.MAX_VALUE; }
