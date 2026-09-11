package fastapi4j;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tiny, dependency-free web framework with FastAPI-flavored ergonomics:
 *
 *   App app = new App();
 *   app.get("/items/{id}", ctx -> new Item(ctx.pathInt("id"), ctx.query("q")));
 *   app.run(8000);
 *
 * ...or, decorator-style using annotated controller classes:
 *
 *   @RestController("/items")
 *   class Items {
 *       @Get("/{id}")
 *       public Item get(int id, @QueryParam(value = "q", required = false) String q) { ... }
 *   }
 *   app.register(new Items());
 */
public class App {

    // ---------------------------------------------------------------- Handler / Ctx

    @FunctionalInterface
    public interface Handler {
        Object handle(Ctx ctx) throws Exception;
    }

    /** Per-request context passed to lambda-style handlers. */
    public static final class Ctx {
        public final Map<String, String> pathParams;
        public final Map<String, List<String>> queryParams;
        public final byte[] rawBody;
        public final HttpExchange exchange;

        Ctx(Map<String, String> p, Map<String, List<String>> q, byte[] body, HttpExchange ex) {
            this.pathParams = p;
            this.queryParams = q;
            this.rawBody = body;
            this.exchange = ex;
        }

        public String path(String name) { return pathParams.get(name); }
        public int pathInt(String name) { return Integer.parseInt(pathParams.get(name)); }
        public long pathLong(String name) { return Long.parseLong(pathParams.get(name)); }

        public String query(String name) {
            List<String> v = queryParams.get(name);
            return v == null || v.isEmpty() ? null : v.get(0);
        }
        public String query(String name, String def) {
            String v = query(name);
            return v == null ? def : v;
        }
        public List<String> queryAll(String name) { return queryParams.getOrDefault(name, List.of()); }

        public String bodyString() { return new String(rawBody, StandardCharsets.UTF_8); }
        public <T> T body(Class<T> type) { return Json.convert(Json.parse(bodyString()), type); }

        public String header(String name) { return exchange.getRequestHeaders().getFirst(name); }
    }

    // ------------------------------------------------------------------------ Route

    private static final class Route {
        String method;
        String rawPath;
        Pattern pattern;
        List<String> paramNames;

        // annotation-style dispatch
        Object controller;
        Method javaMethod;
        int successStatus = 200;

        // lambda-style dispatch
        Handler handler;
    }

    private final List<Route> routes = new ArrayList<>();
    private HttpServer server;

    // ------------------------------------------------------ lambda-style registration

    public App get(String path, Handler h)    { return add("GET", path, h); }
    public App post(String path, Handler h)   { return add("POST", path, h); }
    public App put(String path, Handler h)    { return add("PUT", path, h); }
    public App delete(String path, Handler h) { return add("DELETE", path, h); }
    public App patch(String path, Handler h)  { return add("PATCH", path, h); }

    private App add(String method, String path, Handler h) {
        Route r = new Route();
        r.method = method;
        r.rawPath = normalize(path);
        compilePath(r.rawPath, r);
        r.handler = h;
        routes.add(r);
        return this;
    }

    // ---------------------------------------------------- annotation-style registration

    public App register(Object controller) {
        Class<?> cls = controller.getClass();
        String prefix = "";
        RestController rc = cls.getAnnotation(RestController.class);
        if (rc != null) prefix = normalize(rc.value());

        for (Method m : cls.getMethods()) {
            String method = null, path = null;
            for (Annotation a : m.getAnnotations()) {
                if (a instanceof Get g) { method = "GET"; path = g.value(); }
                else if (a instanceof Post p) { method = "POST"; path = p.value(); }
                else if (a instanceof Put p) { method = "PUT"; path = p.value(); }
                else if (a instanceof Delete d) { method = "DELETE"; path = d.value(); }
                else if (a instanceof Patch p) { method = "PATCH"; path = p.value(); }
            }
            if (method == null) continue;

            Route r = new Route();
            r.method = method;
            r.rawPath = normalize(prefix + normalize(path));
            compilePath(r.rawPath, r);
            r.controller = controller;
            r.javaMethod = m;
            Status st = m.getAnnotation(Status.class);
            if (st != null) r.successStatus = st.value();
            routes.add(r);
        }
        return this;
    }

    // ------------------------------------------------------------------------- Server

    public App run(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", this::dispatch);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            printBanner(port);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return this;
    }

    public void stop() { if (server != null) server.stop(0); }

    private void printBanner(int port) {
        System.out.println("  ⚡ fastapi4j running on http://localhost:" + port);
        System.out.println("  ⚡ route list: http://localhost:" + port + "/__routes");
        for (Route r : routes) {
            System.out.printf("     %-6s %s%n", r.method, r.rawPath);
        }
    }

    // ---------------------------------------------------------------------- Dispatch

    private void dispatch(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

        if (method.equals("GET") && path.equals("/__routes")) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Route r : routes) list.add(Map.of("method", r.method, "path", r.rawPath));
            sendJson(exchange, 200, list);
            return;
        }

        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());

        for (Route r : routes) {
            if (!r.method.equals(method)) continue;
            Matcher matcher = r.pattern.matcher(path);
            if (!matcher.matches()) continue;

            Map<String, String> pathParams = new LinkedHashMap<>();
            for (int i = 0; i < r.paramNames.size(); i++) {
                pathParams.put(r.paramNames.get(i), urlDecode(matcher.group(i + 1)));
            }

            try {
                if (r.handler != null) {
                    Ctx ctx = new Ctx(pathParams, query, body, exchange);
                    Object result = r.handler.handle(ctx);
                    sendJson(exchange, r.successStatus, result);
                } else {
                    if (r.javaMethod.getReturnType() == void.class) {
                        Object[] args = resolveArgs(r.javaMethod, pathParams, query, body);
                        r.javaMethod.invoke(r.controller, args);
                        exchange.sendResponseHeaders(204, -1);
                    } else {
                        Object[] args = resolveArgs(r.javaMethod, pathParams, query, body);
                        Object result = r.javaMethod.invoke(r.controller, args);
                        sendJson(exchange, r.successStatus, result);
                    }
                }
            } catch (InvocationTargetException ite) {
                handleError(exchange, ite.getCause());
            } catch (Exception e) {
                handleError(exchange, e);
            }
            return;
        }

        sendJson(exchange, 404, Map.of("detail", "Not Found: " + method + " " + path));
    }

    private Object[] resolveArgs(Method m, Map<String, String> pathParams,
                                  Map<String, List<String>> query, byte[] body) {
        Parameter[] params = m.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];

            if (p.getType() == Ctx.class) {
                args[i] = new Ctx(pathParams, query, body, null);
                continue;
            }
            Body bodyAnn = p.getAnnotation(Body.class);
            if (bodyAnn != null) {
                Object parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
                args[i] = Json.convert(parsed, p.getParameterizedType());
                continue;
            }
            PathParam pathAnn = p.getAnnotation(PathParam.class);
            if (pathAnn != null) {
                args[i] = Json.convert(pathParams.get(pathAnn.value()), p.getType());
                continue;
            }
            QueryParam queryAnn = p.getAnnotation(QueryParam.class);
            if (queryAnn != null) {
                args[i] = resolveQueryValue(queryAnn.value(), queryAnn.required(), queryAnn.defaultValue(), query, p.getType());
                continue;
            }

            // No annotation: infer like FastAPI does from the parameter name.
            // Requires compiling with `javac -parameters`.
            String name = p.getName();
            if (pathParams.containsKey(name)) {
                args[i] = Json.convert(pathParams.get(name), p.getType());
            } else {
                args[i] = resolveQueryValue(name, true, "\u0000__NONE__", query, p.getType());
            }
        }
        return args;
    }

    private static final String NO_DEFAULT = "\u0000__NONE__";

    private Object resolveQueryValue(String name, boolean required, String defaultValue,
                                      Map<String, List<String>> query, Class<?> targetType) {
        if (targetType == List.class) {
            return query.getOrDefault(name, List.of());
        }
        List<String> values = query.get(name);
        String raw = (values == null || values.isEmpty()) ? null : values.get(0);
        if (raw == null) {
            if (!defaultValue.equals(NO_DEFAULT)) raw = defaultValue;
            else if (required) throw HttpException.unprocessable("Missing required query parameter: " + name);
        }
        return Json.convert(raw, targetType);
    }

    // ------------------------------------------------------------------------ Errors

    private void handleError(HttpExchange exchange, Throwable t) throws IOException {
        if (t instanceof HttpException he) {
            sendJson(exchange, he.statusCode, Map.of("detail", he.getMessage()));
        } else {
            t.printStackTrace();
            sendJson(exchange, 500, Map.of("detail", "Internal Server Error: " + t.getMessage()));
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object result) throws IOException {
        byte[] out = Json.toJson(result).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    // -------------------------------------------------------------------- Path utils

    private static String normalize(String p) {
        if (p == null || p.isEmpty()) return "";
        if (!p.startsWith("/")) p = "/" + p;
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private void compilePath(String path, Route r) {
        if (path.isEmpty()) path = "/";
        List<String> names = new ArrayList<>();
        StringBuilder regex = new StringBuilder("^");
        Matcher m = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}").matcher(path);
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(path.substring(last, m.start())));
            names.add(m.group(1));
            regex.append("([^/]+)");
            last = m.end();
        }
        regex.append(Pattern.quote(path.substring(last)));
        regex.append("$");
        r.pattern = Pattern.compile(regex.toString());
        r.paramNames = names;
    }

    private static Map<String, List<String>> parseQuery(String rawQuery) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return map;
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            map.computeIfAbsent(urlDecode(k), x -> new ArrayList<>()).add(urlDecode(v));
        }
        return map;
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
