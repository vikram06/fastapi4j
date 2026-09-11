package fastapi4j;

import java.lang.reflect.*;
import java.util.*;

/**
 * Minimal dependency-free JSON engine. No Jackson/Gson needed.
 *
 * - toJson(Object)        : Java object/record/POJO/List/Map -> JSON string
 * - parse(String)         : JSON string -> Map/List/String/Long/Double/Boolean/null
 * - convert(Object, Type) : generic parsed structure -> a typed Java object,
 *                           including Java records (used the way FastAPI uses
 *                           Pydantic models for request bodies).
 */
public final class Json {
    private Json() {}

    // ============================== SERIALIZE ==============================

    public static String toJson(Object o) {
        StringBuilder sb = new StringBuilder();
        write(o, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object o, StringBuilder sb) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String s) { writeString(s, sb); return; }
        if (o instanceof Boolean b) { sb.append(b); return; }
        if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (!Double.isNaN(d) && !Double.isInfinite(d) && d == Math.floor(d) && Math.abs(d) < 1e15) {
                sb.append((long) d).append(".0");
            } else {
                sb.append(d);
            }
            return;
        }
        if (o instanceof Number) { sb.append(o); return; }
        if (o instanceof Enum<?> e) { writeString(e.name(), sb); return; }
        if (o instanceof Optional<?> opt) { write(opt.orElse(null), sb); return; }
        if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
            return;
        }
        if (o instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object x : it) {
                if (!first) sb.append(',');
                first = false;
                write(x, sb);
            }
            sb.append(']');
            return;
        }
        if (o.getClass().isArray()) {
            sb.append('[');
            int len = Array.getLength(o);
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(',');
                write(Array.get(o, i), sb);
            }
            sb.append(']');
            return;
        }
        Class<?> cls = o.getClass();
        if (cls.isRecord()) {
            sb.append('{');
            RecordComponent[] comps = cls.getRecordComponents();
            boolean first = true;
            for (RecordComponent rc : comps) {
                try {
                    Object v = rc.getAccessor().invoke(o);
                    if (!first) sb.append(',');
                    first = false;
                    writeString(rc.getName(), sb);
                    sb.append(':');
                    write(v, sb);
                } catch (Exception ex) { throw new RuntimeException(ex); }
            }
            sb.append('}');
            return;
        }
        // plain POJO -> serialize declared instance fields
        sb.append('{');
        boolean first = true;
        for (Field f : cls.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(o);
                if (!first) sb.append(',');
                first = false;
                writeString(f.getName(), sb);
                sb.append(':');
                write(v, sb);
            } catch (Exception ex) { throw new RuntimeException(ex); }
        }
        sb.append('}');
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ================================ PARSE =================================

    public static Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        Parser p = new Parser(json);
        p.skipWs();
        Object v = p.parseValue();
        return v;
    }

    private static final class Parser {
        final String s;
        int i = 0;
        Parser(String s) { this.s = s; }

        void skipWs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char peek() { return s.charAt(i); }

        Object parseValue() {
            skipWs();
            char c = peek();
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return parseNumber();
            }
        }

        void expect(String lit) {
            if (!s.startsWith(lit, i)) throw new RuntimeException("Invalid JSON near index " + i);
            i += lit.length();
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (peek() == '}') { i++; return map; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                if (peek() != ':') throw new RuntimeException("Expected ':' at " + i);
                i++;
                Object val = parseValue();
                map.put(key, val);
                skipWs();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; break; }
                throw new RuntimeException("Expected ',' or '}' at " + i);
            }
            return map;
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            i++; // [
            skipWs();
            if (peek() == ']') { i++; return list; }
            while (true) {
                Object val = parseValue();
                list.add(val);
                skipWs();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; break; }
                throw new RuntimeException("Expected ',' or ']' at " + i);
            }
            return list;
        }

        String parseString() {
            if (peek() != '"') throw new RuntimeException("Expected string at " + i);
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            String hex = s.substring(i, i + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                            break;
                        default: throw new RuntimeException("Bad escape at " + i);
                    }
                } else sb.append(c);
            }
            return sb.toString();
        }

        Object parseNumber() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            boolean isDouble = false;
            if (i < s.length() && s.charAt(i) == '.') {
                isDouble = true; i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                isDouble = true; i++;
                if (s.charAt(i) == '+' || s.charAt(i) == '-') i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String numStr = s.substring(start, i);
            if (isDouble) return Double.parseDouble(numStr);
            try { return Long.parseLong(numStr); } catch (NumberFormatException ex) { return Double.parseDouble(numStr); }
        }
    }

    // ============================== CONVERT =================================

    /** Converts a generic parsed value (Map/List/String/Number/Boolean) into a typed
     *  Java value, following the given reflective Type. Supports records, POJOs,
     *  List&lt;T&gt;, Map&lt;String,T&gt;, enums, and primitives/wrappers. */
    @SuppressWarnings("unchecked")
    public static <T> T convert(Object value, Type type) {
        if (type instanceof Class<?> c) return (T) convertToClass(value, c);
        if (type instanceof ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            if (List.class.isAssignableFrom(raw)) {
                List<?> src = (value instanceof List<?> l) ? l : Collections.emptyList();
                List<Object> out = new ArrayList<>();
                Type elemType = pt.getActualTypeArguments()[0];
                for (Object o : src) out.add(convert(o, elemType));
                return (T) out;
            }
            if (Map.class.isAssignableFrom(raw)) {
                Map<?, ?> src = (value instanceof Map<?, ?> m) ? m : Collections.emptyMap();
                Map<String, Object> out = new LinkedHashMap<>();
                Type valType = pt.getActualTypeArguments()[1];
                for (Map.Entry<?, ?> e : src.entrySet()) out.put(String.valueOf(e.getKey()), convert(e.getValue(), valType));
                return (T) out;
            }
            return (T) convertToClass(value, raw);
        }
        return (T) value;
    }

    @SuppressWarnings("unchecked")
    private static Object convertToClass(Object value, Class<?> type) {
        if (value == null) return defaultForPrimitive(type);
        if (type == String.class) return String.valueOf(value);
        if (type == int.class || type == Integer.class) return toNumber(value).intValue();
        if (type == long.class || type == Long.class) return toNumber(value).longValue();
        if (type == double.class || type == Double.class) return toNumber(value).doubleValue();
        if (type == float.class || type == Float.class) return toNumber(value).floatValue();
        if (type == short.class || type == Short.class) return toNumber(value).shortValue();
        if (type == boolean.class || type == Boolean.class) return toBoolean(value);
        if (type.isEnum()) return Enum.valueOf((Class<Enum>) type, String.valueOf(value));
        if (type == Object.class) return value;
        if (type.isRecord()) return convertRecord(value, type);
        if (value instanceof Map) return convertPojo(value, type);
        return value;
    }

    private static Object defaultForPrimitive(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == boolean.class) return false;
        return null;
    }

    private static Number toNumber(Object v) {
        if (v instanceof Number n) return n;
        if (v instanceof String s) return Double.parseDouble(s);
        throw new HttpException(422, "Expected a number but got: " + v);
    }

    private static Boolean toBoolean(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        throw new HttpException(422, "Expected a boolean but got: " + v);
    }

    private static Object convertRecord(Object value, Class<?> type) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new HttpException(422, "Expected a JSON object for " + type.getSimpleName());
        }
        try {
            RecordComponent[] comps = type.getRecordComponents();
            Object[] args = new Object[comps.length];
            Class<?>[] paramTypes = new Class<?>[comps.length];
            for (int i = 0; i < comps.length; i++) {
                RecordComponent rc = comps[i];
                Object raw = map.get(rc.getName());
                args[i] = convert(raw, rc.getGenericType());
                paramTypes[i] = rc.getType();
            }
            Constructor<?> ctor = type.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to build " + type.getSimpleName() + " from JSON", e);
        }
    }

    private static Object convertPojo(Object value, Class<?> type) {
        Map<?, ?> map = (Map<?, ?>) value;
        try {
            Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object instance = ctor.newInstance();
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!map.containsKey(f.getName())) continue;
                f.setAccessible(true);
                f.set(instance, convert(map.get(f.getName()), f.getGenericType()));
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to build " + type.getSimpleName() + " from JSON " +
                    "(does it have a no-arg constructor?)", e);
        }
    }
}
