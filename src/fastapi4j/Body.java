package fastapi4j;

import java.lang.annotation.*;

/** Binds the JSON request body to a Java record/POJO, like a Pydantic model param. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Body {
}
