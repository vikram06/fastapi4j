package fastapi4j;

import java.lang.annotation.*;

/** Requires a non-null value. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface NotNull {  }
