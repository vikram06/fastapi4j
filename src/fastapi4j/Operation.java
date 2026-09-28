package fastapi4j;
import java.lang.annotation.*;
/** Optional documentation for an annotated route. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Operation {
    String summary() default "";
    String description() default "";
    String[] tags() default {};
    boolean deprecated() default false;
}
