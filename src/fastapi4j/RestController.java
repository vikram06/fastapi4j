package fastapi4j;

import java.lang.annotation.*;

/** Marks a class as a controller. {@code value} is an optional path prefix,
 *  similar to FastAPI's {@code APIRouter(prefix=...)}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RestController {
    String value() default "";
}
