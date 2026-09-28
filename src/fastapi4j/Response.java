package fastapi4j;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** An immutable response. Ordinary handler return values still become JSON. */
public final class Response {
    private final int status;
    private final byte[] body;
    private final Map<String, String> headers;

    private Response(int status, byte[] body, Map<String, String> headers) {
        if (status < 200 || status > 599) throw new IllegalArgumentException("Response status must be 200..599");
        headers.forEach(Response::validateHeader);
        this.status = status; this.body = body.clone(); this.headers = Map.copyOf(headers);
    }
    public static Response json(int status, Object body) { return bytes(status, Json.toJson(body).getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8"); }
    public static Response text(String text) { return bytes(200, text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8"); }
    public static Response html(String html) { return bytes(200, html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8"); }
    public static Response bytes(int status, byte[] body, String contentType) { return new Response(status, Objects.requireNonNull(body), Map.of("Content-Type", Objects.requireNonNull(contentType))); }
    public static Response noContent() { return new Response(204, new byte[0], Map.of()); }
    public static Response redirect(String location) { return new Response(303, new byte[0], Map.of()).header("Location", location); }
    public Response status(int status) { return new Response(status, body, headers); }
    public Response header(String name, String value) {
        validateHeader(name, value);
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.keySet().removeIf(k -> k.equalsIgnoreCase(name)); copy.put(name, value);
        return new Response(status, body, copy);
    }
    private static void validateHeader(String name, String value) {
        if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value.chars().anyMatch(c -> c == 127 || (c < 32 && c != '\t')))
            throw new IllegalArgumentException("Invalid response header");
    }
    public int status() { return status; }
    public byte[] body() { return body.clone(); }
    public Map<String, String> headers() { return headers; }
}
