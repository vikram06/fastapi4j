package fastapi4j;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Dependency-free JSON with strict syntax, exact integers and bounded nesting. */
public final class Json {
    private static final int MAX_DEPTH = 128;
    private Json() {}

    public static String toJson(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out, new IdentityHashMap<>(), 0);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON nesting limit exceeded");
        if (value == null) { out.append("null"); return; }
        if (value instanceof String || value instanceof Character || value instanceof Enum<?>) {
            string(value instanceof Enum<?> e ? e.name() : value.toString(), out); return;
        }
        if (value instanceof Boolean) { out.append(value); return; }
        if (value instanceof Number n) {
            String s = n.toString();
            if (!s.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
                throw new IllegalArgumentException("Non-finite or invalid JSON number");
            out.append(s); return;
        }
        if (seen.put(value, true) != null) throw new IllegalArgumentException("Cyclic JSON value");
        try {
            if (value instanceof Optional<?> optional) { write(optional.orElse(null), out, seen, depth + 1); return; }
            if (value instanceof Map<?, ?> map) {
                out.append('{'); boolean first = true;
                for (var e : map.entrySet()) {
                    if (!first) out.append(','); first = false;
                    string(String.valueOf(e.getKey()), out); out.append(':'); write(e.getValue(), out, seen, depth + 1);
                }
                out.append('}'); return;
            }
            if (value instanceof Iterable<?> items) {
                out.append('['); boolean first = true;
                for (Object item : items) { if (!first) out.append(','); first = false; write(item, out, seen, depth + 1); }
                out.append(']'); return;
            }
            if (value.getClass().isArray()) {
                out.append('[');
                for (int i = 0; i < Array.getLength(value); i++) { if (i > 0) out.append(','); write(Array.get(value, i), out, seen, depth + 1); }
                out.append(']'); return;
            }
            out.append('{'); boolean first = true;
            if (value.getClass().isRecord()) {
                for (RecordComponent c : value.getClass().getRecordComponents()) {
                    Method accessor = c.getAccessor(); accessor.setAccessible(true);
                    if (!first) out.append(','); first = false;
                    string(c.getName(), out); out.append(':'); write(accessor.invoke(value), out, seen, depth + 1);
                }
            } else {
                for (Field f : fields(value.getClass())) {
                    f.setAccessible(true);
                    if (!first) out.append(','); first = false;
                    string(f.getName(), out); out.append(':'); write(f.get(value), out, seen, depth + 1);
                }
            }
            out.append('}');
        } catch (ReflectiveOperationException e) { throw new IllegalArgumentException("Cannot serialize " + value.getClass().getName(), e); }
        finally { seen.remove(value); }
    }

    private static void string(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 32 || Character.isSurrogate(c)) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        out.append('"');
    }

    /** Throws HTTP 400 for malformed JSON. Empty input is not a JSON value. */
    public static Object parse(String json) {
        if (json == null) throw HttpException.badRequest("Expected JSON body");
        Parser p = new Parser(json);
        Object value = p.value(0); p.ws();
        if (p.i != json.length()) throw p.error("Trailing content");
        return value;
    }

    private static final class Parser {
        final String s; int i;
        Parser(String s) { this.s = s; }
        HttpException error(String message) { return HttpException.badRequest(message + " at index " + i); }
        void ws() { while (i < s.length() && " \t\n\r".indexOf(s.charAt(i)) >= 0) i++; }
        char peek() { if (i >= s.length()) throw error("Unexpected end of JSON"); return s.charAt(i); }
        boolean take(char c) { if (i < s.length() && s.charAt(i) == c) { i++; return true; } return false; }
        void need(char c) { if (!take(c)) throw error("Expected '" + c + "'"); }
        Object value(int depth) {
            if (depth > MAX_DEPTH) throw error("JSON nesting limit exceeded");
            ws(); char c = peek();
            if (c == '"') return text();
            if (c == '{') {
                i++; ws(); Map<String, Object> map = new LinkedHashMap<>();
                if (take('}')) return map;
                do {
                    ws(); String key = text(); ws(); need(':');
                    if (map.containsKey(key)) throw error("Duplicate object key");
                    map.put(key, value(depth + 1)); ws();
                    if (take('}')) return map;
                    need(',');
                } while (true);
            }
            if (c == '[') {
                i++; ws(); List<Object> list = new ArrayList<>();
                if (take(']')) return list;
                do { list.add(value(depth + 1)); ws(); if (take(']')) return list; need(','); } while (true);
            }
            for (String literal : List.of("true", "false", "null")) {
                if (s.startsWith(literal, i)) { i += literal.length(); return literal.equals("null") ? null : literal.equals("true"); }
            }
            int start = i; take('-');
            if (!take('0')) { if (peek() < '1' || peek() > '9') throw error("Expected JSON value"); digits(); }
            if (take('.')) { int before = i; digits(); if (before == i) throw error("Expected fractional digits"); }
            if (take('e') || take('E')) { if (!take('+')) take('-'); int before = i; digits(); if (before == i) throw error("Expected exponent digits"); }
            String number = s.substring(start, i);
            try {
                if (number.contains(".") || number.contains("e") || number.contains("E")) return new BigDecimal(number);
                try { return Long.parseLong(number); } catch (NumberFormatException e) { return new BigInteger(number); }
            } catch (NumberFormatException e) { throw error("Invalid number"); }
        }
        void digits() { while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++; }
        String text() {
            need('"'); StringBuilder out = new StringBuilder();
            while (true) {
                char c = peek(); i++;
                if (c == '"') return out.toString();
                if (c < 32) throw error("Unescaped control character");
                if (c != '\\') { out.append(c); continue; }
                char e = peek(); i++;
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'b' -> out.append('\b'); case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw error("Incomplete Unicode escape");
                        int code = 0;
                        for (int j = 0; j < 4; j++) {
                            char hex = s.charAt(i++);
                            int digit = "0123456789abcdef".indexOf(Character.toLowerCase(hex));
                            if (digit < 0) throw error("Invalid Unicode escape"); code = code * 16 + digit;
                        }
                        out.append((char)code);
                    }
                    default -> throw error("Invalid escape");
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> T convert(Object value, Type type) {
        if (type instanceof ParameterizedType pt) {
            Type raw = pt.getRawType();
            if (raw == Optional.class) return (T) Optional.ofNullable(convert(value, pt.getActualTypeArguments()[0]));
            if (value == null) return null;
            if (raw == List.class || raw == Collection.class || raw == Set.class) {
                if (!(value instanceof List<?> list)) throw invalid("Expected an array");
                Collection<Object> out = raw == Set.class ? new LinkedHashSet<>() : new ArrayList<>();
                for (Object item : list) out.add(convert(item, pt.getActualTypeArguments()[0]));
                return (T) out;
            }
            if (raw == Map.class) {
                if (pt.getActualTypeArguments()[0] != String.class) throw new IllegalArgumentException("Only String map keys are supported");
                if (!(value instanceof Map<?, ?> map)) throw invalid("Expected an object");
                Map<String, Object> out = new LinkedHashMap<>();
                for (var e : map.entrySet()) out.put(String.valueOf(e.getKey()), convert(e.getValue(), pt.getActualTypeArguments()[1]));
                return (T) out;
            }
            throw new IllegalArgumentException("Unsupported generic type: " + type);
        }
        if (!(type instanceof Class<?> c)) throw new IllegalArgumentException("Unsupported type: " + type);
        if (value == null) {
            if (c.isPrimitive()) throw invalid("Null is not allowed for " + c.getSimpleName());
            return null;
        }
        try {
            if (c == Object.class) return (T) value;
            if (c == String.class) { if (!(value instanceof String)) throw invalid("Expected a string"); return (T) value; }
            if (c == boolean.class || c == Boolean.class) {
                if (value instanceof Boolean) return (T) value;
                if (value instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) return (T) Boolean.valueOf(s);
                throw invalid("Expected true or false");
            }
            if (c == char.class || c == Character.class) { if (value instanceof String s && s.length() == 1) return (T) Character.valueOf(s.charAt(0)); throw invalid("Expected one character"); }
            if (c.isPrimitive() || Number.class.isAssignableFrom(c)) {
                if (!(value instanceof Number || value instanceof String)) throw invalid("Expected a number");
                BigDecimal n = new BigDecimal(value.toString());
                if (c == int.class || c == Integer.class) return (T) Integer.valueOf(n.intValueExact());
                if (c == long.class || c == Long.class) return (T) Long.valueOf(n.longValueExact());
                if (c == short.class || c == Short.class) return (T) Short.valueOf(n.shortValueExact());
                if (c == byte.class || c == Byte.class) return (T) Byte.valueOf(n.byteValueExact());
                if (c == BigInteger.class) return (T) n.toBigIntegerExact();
                if (c == BigDecimal.class || c == Number.class) return (T) n;
                if (c == double.class || c == Double.class) { double d = n.doubleValue(); if (!Double.isFinite(d)) throw invalid("Number out of range"); return (T) Double.valueOf(d); }
                if (c == float.class || c == Float.class) { float f = n.floatValue(); if (!Float.isFinite(f)) throw invalid("Number out of range"); return (T) Float.valueOf(f); }
            }
            if (c.isEnum()) return (T) Enum.valueOf((Class<Enum>)c, value.toString());
            if (c.isArray()) {
                if (!(value instanceof List<?> list)) throw invalid("Expected an array");
                Object out = Array.newInstance(c.getComponentType(), list.size());
                for (int i = 0; i < list.size(); i++) Array.set(out, i, convert(list.get(i), c.getComponentType()));
                return (T) out;
            }
            if (c.isInstance(value)) return (T) value;
            if (!(value instanceof Map<?, ?> map)) throw invalid("Expected an object for " + c.getSimpleName());
            if (c.isRecord()) {
                RecordComponent[] components = c.getRecordComponents();
                Object[] args = new Object[components.length]; Class<?>[] types = new Class<?>[components.length];
                for (int i = 0; i < components.length; i++) {
                    RecordComponent rc = components[i];
                    args[i] = convert(map.get(rc.getName()), rc.getGenericType());
                    Validation.check(args[i], rc, rc.getName()); types[i] = rc.getType();
                }
                Constructor<?> ctor = c.getDeclaredConstructor(types); ctor.setAccessible(true);
                return (T) ctor.newInstance(args);
            }
            Constructor<?> ctor = c.getDeclaredConstructor(); ctor.setAccessible(true); Object out = ctor.newInstance();
            for (Field f : fields(c)) {
                f.setAccessible(true);
                if (map.containsKey(f.getName())) f.set(out, convert(map.get(f.getName()), f.getGenericType()));
                Validation.check(f.get(out), f, f.getName());
            }
            return (T) out;
        } catch (NumberFormatException | ArithmeticException e) { throw invalid("Invalid or out-of-range " + c.getSimpleName()); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof HttpException he) throw he;
            if (e.getCause() instanceof IllegalArgumentException) throw invalid("Invalid " + c.getSimpleName());
            throw new IllegalStateException("Model constructor failed: " + c.getName(), e.getCause());
        } catch (ReflectiveOperationException e) { throw new IllegalArgumentException("Cannot construct " + c.getName(), e); }
        catch (IllegalArgumentException e) { if (c.isEnum()) throw invalid("Invalid " + c.getSimpleName()); throw e; }
    }

    static List<Field> fields(Class<?> c) {
        Map<String, Field> fields = new LinkedHashMap<>();
        for (; c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields())
                if (!Modifier.isStatic(f.getModifiers()) && !Modifier.isTransient(f.getModifiers()) && !f.isSynthetic()) fields.putIfAbsent(f.getName(), f);
        return new ArrayList<>(fields.values());
    }
    private static HttpException invalid(String detail) { return HttpException.unprocessable(detail); }
}
