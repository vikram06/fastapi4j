package fastapi4j;
import java.lang.annotation.*;
/** Binds a request header, with the same required/default rules as QueryParam. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface HeaderParam {
    String value();
    boolean required() default true;
    String defaultValue() default "\u0000__NONE__";
}
