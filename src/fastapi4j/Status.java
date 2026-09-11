package fastapi4j;

import java.lang.annotation.*;

/** Sets the success status code for a route, like FastAPI's status_code=201. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Status {
    int value() default 200;
}
