package fastapi4j;

import java.lang.annotation.*;

/** Equivalent of FastAPI's {@code q: str = Query(default=..., required=...)}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface QueryParam {
    String value();
    boolean required() default true;
    /** Sentinel meaning "no default"; leave unset for required params. */
    String defaultValue() default "\u0000__NONE__";
}
