package fastapi4j;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;

final class Validation {
    private Validation() {}
    static void check(Object value, AnnotatedElement annotations, String name) {
        if (annotations.isAnnotationPresent(NotNull.class) && value == null)
            throw HttpException.unprocessable(name + " must not be null");
        if (value == null) return;
        Range range = annotations.getAnnotation(Range.class);
        if (range != null) {
            if (!(value instanceof Number n)) throw new IllegalArgumentException("@Range requires a number: " + name);
            BigDecimal number = new BigDecimal(n.toString());
            if (number.compareTo(BigDecimal.valueOf(range.min())) < 0 || number.compareTo(BigDecimal.valueOf(range.max())) > 0)
                throw HttpException.unprocessable(name + " must be between " + range.min() + " and " + range.max());
        }
        Size size = annotations.getAnnotation(Size.class);
        if (size != null) {
            int length;
            if (value instanceof CharSequence s) length = s.length();
            else if (value instanceof Collection<?> c) length = c.size();
            else if (value instanceof Map<?, ?> m) length = m.size();
            else if (value.getClass().isArray()) length = Array.getLength(value);
            else throw new IllegalArgumentException("@Size requires a string, collection, map or array: " + name);
            if (length < size.min() || length > size.max()) throw HttpException.unprocessable(name + " size must be between " + size.min() + " and " + size.max());
        }
    }
}
