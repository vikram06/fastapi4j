package fastapi4j;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import java.util.regex.*;

/** Small Java 21 HTTP framework. Configure before run(); close() releases server resources. */
public class App implements AutoCloseable {
    @FunctionalInterface public interface Handler { Object handle(Ctx ctx) throws Exception; }

    /** Request context shared by middleware and the selected handler. */
    public static final class Ctx {
        public final Map<String, String> pathParams;
        public final Map<String, List<String>> queryParams;
        public final byte[] rawBody;
        public final HttpExchange exchange;
        private final Map<String, Object> attributes = new HashMap<>();
        private int status;
        Ctx(Map<String, String> paths, Map<String, List<String>> query, byte[] body, HttpExchange exchange, int status) {
            this.pathParams = Map.copyOf(paths);
            Map<String, List<String>> copy = new LinkedHashMap<>(); query.forEach((k, v) -> copy.put(k, List.copyOf(v)));
            this.queryParams = Collections.unmodifiableMap(copy); this.rawBody = body;
            this.exchange = exchange; this.status = status;
        }
        public String path(String name) { return pathParams.get(name); }
        public int pathInt(String name) { return Json.convert(path(name), int.class); }
        public long pathLong(String name) { return Json.convert(path(name), long.class); }
        public String query(String name) { List<String> values = queryAll(name); return values.isEmpty() ? null : values.get(0); }
        public String query(String name, String fallback) { String v = query(name); return v == null ? fallback : v; }
        public List<String> queryAll(String name) { return queryParams.getOrDefault(name, List.of()); }
        public int queryInt(String name, int fallback) { String v = query(name); return v == null ? fallback : Json.convert(v, int.class); }
        public String bodyString() {
            try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(rawBody)).toString(); }
            catch (CharacterCodingException e) { throw HttpException.badRequest("Body must be valid UTF-8"); }
        }
        public <T> T body(Class<T> type) { return Json.convert(jsonBody(), type); }
        private Object jsonBody() {
            String contentType = header("Content-Type");
            if (contentType != null) {
                String mime = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (!mime.equals("application/json") && !(mime.startsWith("application/") && mime.endsWith("+json")))
                    throw new HttpException(415, "Expected application/json");
            }
            return Json.parse(bodyString());
        }
        public String header(String name) { return exchange.getRequestHeaders().getFirst(name); }
        public String method() { return exchange.getRequestMethod(); }
        public String requestId() { return exchange.getResponseHeaders().getFirst("X-Request-ID"); }
        public Ctx status(int code) { if (code < 200 || code > 599) throw new IllegalArgumentException("Status must be 200..599"); status = code; return this; }
        public Ctx responseHeader(String name, String value) { Response.noContent().header(name, value); exchange.getResponseHeaders().set(name, value); return this; }
        public Ctx attribute(String name, Object value) { attributes.put(name, value); return this; }
        public Object attribute(String name) { return attributes.get(name); }
    }

    private static final class Route {
        String method, rawPath;
        Pattern pattern;
        List<String> paramNames;
        Object controller;
        Method javaMethod;
        int successStatus = 200;
        int literalLength;
        Handler handler;
    }
    private final List<Route> routes = new ArrayList<>();
    private final List<Middleware> middleware = new ArrayList<>();
    private HttpServer server;
    private ExecutorService executor;
    private volatile boolean running;
    private String apiTitle = "fastapi4j", apiVersion = "0.2.0", apiDescription;
    private int maxBodyBytes = 1024 * 1024;
    private boolean docsEnabled = true;
    private Cors cors;
    private BiFunction<Ctx, Exception, Response> errorHandler;
    private static final String NO_DEFAULT = "\u0000__NONE__";

    private void configurable() { if (running) throw new IllegalStateException("Configure the app before run()"); }
    public App title(String title) { configurable(); apiTitle = Objects.requireNonNull(title); return this; }
    public App version(String version) { configurable(); apiVersion = Objects.requireNonNull(version); return this; }
    public App description(String description) { configurable(); apiDescription = description; return this; }
    public App maxBodyBytes(int bytes) { configurable(); if (bytes < 0 || bytes == Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid body limit"); maxBodyBytes = bytes; return this; }
    public App docs(boolean enabled) { configurable(); docsEnabled = enabled; return this; }
    public App cors(Cors policy) { configurable(); cors = Objects.requireNonNull(policy); return this; }
    public App use(Middleware handler) { configurable(); middleware.add(Objects.requireNonNull(handler)); return this; }
    public App onError(BiFunction<Ctx, Exception, Response> handler) { configurable(); errorHandler = Objects.requireNonNull(handler); return this; }
    public App get(String path, Handler handler) { return route("GET", path, handler); }
    public App post(String path, Handler handler) { return route("POST", path, handler); }
    public App put(String path, Handler handler) { return route("PUT", path, handler); }
    public App patch(String path, Handler handler) { return route("PATCH", path, handler); }
    public App delete(String path, Handler handler) { return route("DELETE", path, handler); }
    public App head(String path, Handler handler) { return route("HEAD", path, handler); }
    public App options(String path, Handler handler) { return route("OPTIONS", path, handler); }
    public App route(String method, String path, Handler handler) {
        configurable(); Route route = new Route(); route.method = method.toUpperCase(Locale.ROOT);
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE").contains(route.method)) throw new IllegalArgumentException("Unsupported method");
        route.rawPath = normalize(path); route.handler = Objects.requireNonNull(handler); compilePath(route.rawPath, route); addRoute(route); return this;
    }
    private void addRoute(Route route) {
        String shape = route.pattern.pattern();
        for (Route old : routes) if (old.method.equals(route.method) && old.pattern.pattern().equals(shape))
            throw new IllegalArgumentException("Duplicate route: " + route.method + " " + route.rawPath);
        routes.add(route);
        // Exact routes first, then patterns with more literal content. Stable for ties.
        routes.sort(Comparator.comparingInt((Route r) -> r.paramNames.isEmpty() ? 0 : 1)
                .thenComparing(Comparator.comparingInt((Route r) -> r.literalLength).reversed()));
    }
    public App register(Object controller) {
        configurable(); Objects.requireNonNull(controller);
        RestController annotation = controller.getClass().getAnnotation(RestController.class);
        String prefix = annotation == null ? "" : normalize(annotation.value());
        if (prefix.equals("/")) prefix = "";
        Method[] methods = controller.getClass().getMethods(); Arrays.sort(methods, Comparator.comparing(Method::toGenericString));
        for (Method method : methods) {
            String verb = null, path = null;
            for (Annotation a : method.getAnnotations()) {
                String candidate = null, value = null;
                if (a instanceof Get v) { candidate = "GET"; value = v.value(); }
                if (a instanceof Post v) { candidate = "POST"; value = v.value(); }
                if (a instanceof Put v) { candidate = "PUT"; value = v.value(); }
                if (a instanceof Patch v) { candidate = "PATCH"; value = v.value(); }
                if (a instanceof Delete v) { candidate = "DELETE"; value = v.value(); }
                if (candidate != null) { if (verb != null) throw new IllegalArgumentException("Multiple HTTP annotations: " + method); verb = candidate; path = value; }
            }
            if (verb == null) continue;
            Route route = new Route(); route.method = verb;
            route.rawPath = normalize(prefix + (normalize(path).equals("/") ? "" : normalize(path)));
            compilePath(route.rawPath, route); route.controller = controller; route.javaMethod = method;
            if (!method.trySetAccessible()) throw new IllegalArgumentException("Inaccessible handler: " + method);
            Status status = method.getAnnotation(Status.class);
            route.successStatus = status == null ? (method.getReturnType() == void.class ? 204 : 200) : status.value();
            if (route.successStatus < 200 || route.successStatus > 599) throw new IllegalArgumentException("Invalid @Status");
            int bodies = 0;
            for (Parameter p : method.getParameters()) {
                int bindings = (p.isAnnotationPresent(Body.class) ? 1 : 0) + (p.isAnnotationPresent(PathParam.class) ? 1 : 0)
                        + (p.isAnnotationPresent(QueryParam.class) ? 1 : 0) + (p.isAnnotationPresent(HeaderParam.class) ? 1 : 0);
                if (bindings > 1) throw new IllegalArgumentException("Conflicting bindings: " + p);
                if (p.isAnnotationPresent(Body.class)) bodies++;
                if (p.isAnnotationPresent(PathParam.class) && !route.paramNames.contains(p.getAnnotation(PathParam.class).value())) throw new IllegalArgumentException("Unknown path parameter: " + p);
                if (bindings == 0 && p.getType() != Ctx.class && !p.isNamePresent()) throw new IllegalArgumentException("Compile with -parameters or annotate parameter: " + p);
            }
            if (bodies > 1) throw new IllegalArgumentException("Only one @Body parameter is supported");
            addRoute(route);
        }
        return this;
    }

    public App run(int port) { return run("0.0.0.0", port); }
    public synchronized App run(String host, int port) {
        if (running) throw new IllegalStateException("App already running");
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
            server.createContext("/", this::dispatch); running = true; server.start();
            System.out.println("fastapi4j listening on " + host + ":" + port());
            return this;
        } catch (IOException | RuntimeException e) {
            if (server != null) server.stop(0); if (executor != null) executor.shutdownNow(); running = false;
            throw new IllegalStateException("Unable to start HTTP server", e);
        }
    }
    public int port() { if (!running) throw new IllegalStateException("App is not running"); return server.getAddress().getPort(); }
    public void stop() { stop(0); }
    public synchronized void stop(int delaySeconds) {
        if (delaySeconds < 0) throw new IllegalArgumentException("Negative shutdown delay");
        if (server != null) { server.stop(delaySeconds); server = null; }
        if (executor != null) { executor.shutdownNow(); executor = null; }
        running = false;
    }
    @Override public void close() { stop(); }

    private void dispatch(HttpExchange exchange) throws IOException {
        Ctx ctx = null;
        exchange.getResponseHeaders().set("X-Request-ID", UUID.randomUUID().toString());
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        try {
            String path = normalize(exchange.getRequestURI().getRawPath());
            String method = exchange.getRequestMethod();
            Route selected = null; Map<String, String> paths = new LinkedHashMap<>(); Set<String> allowed = new TreeSet<>();
            for (Route route : routes) {
                Matcher match = route.pattern.matcher(path); if (!match.matches()) continue;
                allowed.add(route.method); if (route.method.equals("GET")) allowed.add("HEAD");
                if (selected == null && route.method.equals(method)) selected = route;
            }
            if (selected == null && method.equals("HEAD")) {
                for (Route route : routes) if (route.method.equals("GET") && route.pattern.matcher(path).matches()) { selected = route; break; }
            }
            boolean builtin = docsEnabled && Set.of("/docs", "/redoc", "/openapi.json", "/__routes").contains(path);
            if (builtin) { allowed.add("GET"); allowed.add("HEAD"); }
            if (!allowed.isEmpty()) allowed.add("OPTIONS");
            if (selected != null) {
                Matcher match = selected.pattern.matcher(path); match.matches();
                for (int i = 0; i < selected.paramNames.size(); i++) paths.put(selected.paramNames.get(i), decode(match.group(i + 1), false));
            }
            Map<String, List<String>> query = parseQuery(exchange.getRequestURI().getRawQuery());
            ctx = new Ctx(paths, query, readBody(exchange), exchange, selected == null ? 200 : selected.successStatus);
            Ctx context = ctx; Route route = selected;
            Response corsResponse = applyCors(context, allowed);
            Object result = corsResponse != null ? corsResponse : invokeMiddleware(0, ctx, () -> {
                if (builtin && (method.equals("GET") || method.equals("HEAD"))) {
                    return switch (path) {
                        case "/docs" -> Response.html(swaggerUiHtml());
                        case "/redoc" -> Response.html(redocHtml());
                        case "/openapi.json" -> buildOpenApiSpec();
                        default -> routes.stream().map(r -> Map.of("method", r.method, "path", r.rawPath)).toList();
                    };
                }
                if (route != null) {
                    if (route.handler != null) return route.handler.handle(context);
                    try { return route.javaMethod.invoke(route.controller, resolveArgs(route.javaMethod, context)); }
                    catch (InvocationTargetException e) { if (e.getCause() instanceof Exception cause) throw cause; throw new IllegalStateException("Handler failed", e.getCause()); }
                }
                if (!allowed.isEmpty()) {
                    context.responseHeader("Allow", String.join(", ", allowed));
                    if (method.equals("OPTIONS")) return Response.noContent();
                    throw new HttpException(405, "Method Not Allowed");
                }
                throw HttpException.notFound("Not Found");
            });
            send(exchange, result instanceof Response response ? response : Response.json(ctx.status, result));
        } catch (Exception e) {
            if (exchange.getResponseCode() == -1) {
                Response response;
                try { response = errorHandler != null && ctx != null ? Objects.requireNonNull(errorHandler.apply(ctx, e)) : defaultError(e); }
                catch (Exception handlerError) { response = defaultError(handlerError); }
                send(exchange, response);
            }
        } finally { exchange.close(); }
    }
    private Object invokeMiddleware(int index, Ctx ctx, Middleware.Next terminal) throws Exception {
        if (index == middleware.size()) return terminal.handle();
        return middleware.get(index).handle(ctx, () -> invokeMiddleware(index + 1, ctx, terminal));
    }
    private Response defaultError(Exception e) {
        if (e instanceof HttpException h) return Response.json(h.statusCode, Map.of("detail", Objects.toString(h.getMessage(), "Request failed")));
        System.getLogger(App.class.getName()).log(System.Logger.Level.ERROR, "Unhandled request error", e);
        return Response.json(500, Map.of("detail", "Internal Server Error"));
    }
    private byte[] readBody(HttpExchange exchange) throws IOException {
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try { long n = Long.parseLong(length); if (n < 0) throw new NumberFormatException(); if (n > maxBodyBytes) throw new HttpException(413, "Request body too large"); }
            catch (NumberFormatException e) { throw HttpException.badRequest("Invalid Content-Length"); }
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(maxBodyBytes + 1);
        if (bytes.length > maxBodyBytes) throw new HttpException(413, "Request body too large");
        return bytes;
    }
    private Response applyCors(Ctx ctx, Set<String> allowed) {
        if (cors == null) return null;
        String origin = ctx.header("Origin"); if (origin == null) return null;
        ctx.responseHeader("Vary", "Origin");
        boolean preflight = ctx.method().equals("OPTIONS") && ctx.header("Access-Control-Request-Method") != null;
        if (!cors.origins.contains("*") && !cors.origins.contains(origin)) {
            if (preflight) throw new HttpException(403, "Origin not allowed"); return null;
        }
        ctx.responseHeader("Access-Control-Allow-Origin", cors.origins.contains("*") ? "*" : origin);
        if (cors.credentials) ctx.responseHeader("Access-Control-Allow-Credentials", "true");
        if (!preflight) return null;
        ctx.responseHeader("Vary", "Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
        String requested = ctx.header("Access-Control-Request-Method");
        if (!cors.methods.contains(requested) || !allowed.contains(requested)) throw new HttpException(403, "CORS method not allowed");
        String headers = ctx.header("Access-Control-Request-Headers");
        if (headers != null) for (String header : headers.split(","))
            if (!cors.headers.contains(header.trim().toLowerCase(Locale.ROOT))) throw new HttpException(403, "CORS header not allowed");
        Set<String> methods = new TreeSet<>(allowed); methods.retainAll(cors.methods);
        ctx.responseHeader("Access-Control-Allow-Methods", String.join(", ", methods));
        ctx.responseHeader("Access-Control-Allow-Headers", String.join(", ", cors.headers));
        ctx.responseHeader("Access-Control-Max-Age", Long.toString(cors.maxAge));
        return Response.noContent();
    }
    private Object[] resolveArgs(Method method, Ctx ctx) {
        Parameter[] params = method.getParameters(); Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i]; Type type = p.getParameterizedType();
            if (p.getType() == Ctx.class) { args[i] = ctx; continue; }
            if (p.isAnnotationPresent(Body.class)) {
                Object body = ctx.jsonBody(); if (body == null) throw HttpException.unprocessable("Request body must not be null");
                args[i] = Json.convert(body, type);
            } else if (p.isAnnotationPresent(PathParam.class)) args[i] = Json.convert(ctx.path(p.getAnnotation(PathParam.class).value()), type);
            else if (p.isAnnotationPresent(HeaderParam.class)) {
                HeaderParam h = p.getAnnotation(HeaderParam.class);
                args[i] = bind(h.value(), h.required(), h.defaultValue(), ctx.exchange.getRequestHeaders().get(h.value()), type);
            } else if (p.isAnnotationPresent(QueryParam.class)) {
                QueryParam q = p.getAnnotation(QueryParam.class);
                args[i] = bind(q.value(), q.required(), q.defaultValue(), ctx.queryParams.get(q.value()), type);
            } else if (ctx.pathParams.containsKey(p.getName())) args[i] = Json.convert(ctx.path(p.getName()), type);
            else args[i] = bind(p.getName(), true, NO_DEFAULT, ctx.queryParams.get(p.getName()), type);
            Validation.check(args[i], p, p.getName());
        }
        return args;
    }
    private Object bind(String name, boolean required, String fallback, List<String> values, Type type) {
        boolean multiple = type instanceof ParameterizedType pt && (pt.getRawType() == List.class || pt.getRawType() == Collection.class || pt.getRawType() == Set.class) || type == List.class;
        if (values == null || values.isEmpty()) {
            if (!fallback.equals(NO_DEFAULT)) values = List.of(fallback);
            else if (required) throw HttpException.unprocessable("Missing required parameter: " + name);
            else if (multiple) values = List.of();
            else {
                if (type instanceof Class<?> c && c.isPrimitive()) {
                    if (c == boolean.class) return false; if (c == char.class) return '\0'; return Json.convert("0", type);
                }
                return Json.convert(null, type);
            }
        }
        return Json.convert(multiple ? values : values.get(0), type);
    }
    private void send(HttpExchange exchange, Response response) throws IOException {
        response.headers().forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
        byte[] bytes = response.body(); int status = response.status();
        // Framing is owned by the server, never by user-provided headers.
        exchange.getResponseHeaders().remove("Content-Length"); exchange.getResponseHeaders().remove("Transfer-Encoding");
        if (status == 204 || status == 304 || status == 205) {
            if (status == 205) exchange.getResponseHeaders().set("Content-Length", "0");
            exchange.sendResponseHeaders(status, -1); return;
        }
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
            exchange.sendResponseHeaders(status, -1); return;
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
    }
    private static String normalize(String path) {
        if (path == null || path.isEmpty()) return "/";
        if (!path.startsWith("/")) path = "/" + path;
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }
    private void compilePath(String path, Route route) {
        List<String> names = new ArrayList<>(); StringBuilder regex = new StringBuilder("^");
        Matcher matcher = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}").matcher(path); int last = 0;
        while (matcher.find()) {
            String literal = path.substring(last, matcher.start()); validateLiteral(literal);
            regex.append(Pattern.quote(literal)); route.literalLength += literal.length();
            if (names.contains(matcher.group(1))) throw new IllegalArgumentException("Duplicate path parameter");
            names.add(matcher.group(1)); regex.append("([^/]+)"); last = matcher.end();
        }
        String literal = path.substring(last); validateLiteral(literal); route.literalLength += literal.length();
        regex.append(Pattern.quote(literal)).append('$'); route.pattern = Pattern.compile(regex.toString()); route.paramNames = names;
    }
    private void validateLiteral(String literal) {
        if (literal.indexOf('{') >= 0 || literal.indexOf('}') >= 0 || literal.indexOf('?') >= 0 || literal.indexOf('#') >= 0)
            throw new IllegalArgumentException("Invalid route path");
    }
    private static Map<String, List<String>> parseQuery(String raw) {
        Map<String, List<String>> out = new LinkedHashMap<>(); if (raw == null) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue; int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq), value = eq < 0 ? "" : pair.substring(eq + 1);
            out.computeIfAbsent(decode(key, true), k -> new ArrayList<>()).add(decode(value, true));
        }
        return out;
    }
    private static String decode(String value, boolean form) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < value.length();) {
            char c = value.charAt(i++);
            if (c == '%') {
                if (i + 1 >= value.length()) throw HttpException.badRequest("Invalid URL encoding");
                int hi = Character.digit(value.charAt(i++), 16), lo = Character.digit(value.charAt(i++), 16);
                if (hi < 0 || lo < 0) throw HttpException.badRequest("Invalid URL encoding"); bytes.write(hi * 16 + lo);
            } else {
                if (c == '+' && form) c = ' ';
                int cp = c;
                if (Character.isHighSurrogate(c) && i < value.length() && Character.isLowSurrogate(value.charAt(i))) cp = Character.toCodePoint(c, value.charAt(i++));
                bytes.writeBytes(new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8));
            }
        }
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString(); }
        catch (CharacterCodingException e) { throw HttpException.badRequest("Invalid UTF-8 in URL"); }
    }
    // ============================================================= OpenAPI

    /** Builds an OpenAPI 3.0 document describing every registered route, by
     *  introspecting parameter annotations and record/POJO return & body types.
     *  Lambda routes (no type info available) are listed with a generic schema. */
    public Map<String, Object> buildOpenApiSpec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", "3.0.3");

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", apiTitle);
        info.put("version", apiVersion);
        if (apiDescription != null) info.put("description", apiDescription);
        spec.put("info", info);

        Map<String, Object> schemas = new LinkedHashMap<>();
        Map<String, Map<String, Object>> paths = new LinkedHashMap<>();

        for (Route r : routes) {
            String pathKey = r.rawPath.isEmpty() ? "/" : r.rawPath;
            Map<String, Object> pathItem = paths.computeIfAbsent(pathKey, k -> new LinkedHashMap<>());
            pathItem.put(r.method.toLowerCase(), r.javaMethod != null
                    ? buildOperation(r, schemas)
                    : buildLambdaOperation(r));
        }

        spec.put("paths", paths);
        spec.put("components", Map.of("schemas", schemas));
        return spec;
    }

    private Map<String, Object> buildOperation(Route r, Map<String, Object> schemas) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", operationId(r));
        Operation doc = r.javaMethod.getAnnotation(Operation.class);
        if (doc != null) {
            if (!doc.summary().isEmpty()) op.put("summary", doc.summary());
            if (!doc.description().isEmpty()) op.put("description", doc.description());
            op.put("deprecated", doc.deprecated());
        }
        op.put("tags", doc != null && doc.tags().length > 0 ? List.of(doc.tags()) : List.of(r.controller.getClass().getSimpleName()));

        List<Map<String, Object>> parameters = new ArrayList<>();
        Object requestBodySchema = null;

        for (Parameter p : r.javaMethod.getParameters()) {
            if (p.getType() == Ctx.class) continue;

            if (p.getAnnotation(Body.class) != null) {
                requestBodySchema = constrained(schemaFor(p.getParameterizedType(), schemas), p);
                continue;
            }
            PathParam pathAnn = p.getAnnotation(PathParam.class);
            QueryParam queryAnn = p.getAnnotation(QueryParam.class);
            HeaderParam headerAnn = p.getAnnotation(HeaderParam.class);

            String name;
            String in;
            boolean required;
            String defaultVal = null;
            if (pathAnn != null) {
                name = pathAnn.value(); in = "path"; required = true;
            } else if (queryAnn != null) {
                name = queryAnn.value(); in = "query"; required = queryAnn.required();
                if (!queryAnn.defaultValue().equals(NO_DEFAULT)) defaultVal = queryAnn.defaultValue();
            } else if (headerAnn != null) {
                name = headerAnn.value(); in = "header"; required = headerAnn.required();
                if (!headerAnn.defaultValue().equals(NO_DEFAULT)) defaultVal = headerAnn.defaultValue();
            } else if (r.paramNames.contains(p.getName())) {
                name = p.getName(); in = "path"; required = true;
            } else {
                name = p.getName(); in = "query"; required = true;
            }

            Map<String, Object> param = new LinkedHashMap<>();
            param.put("name", name);
            param.put("in", in);
            param.put("required", in.equals("path") || (required && defaultVal == null));
            Map<String, Object> sch = constrained(schemaFor(p.getParameterizedType(), schemas), p);
            if (defaultVal != null) sch.put("default", bind(name, false, defaultVal, null, p.getParameterizedType()));
            param.put("schema", sch);
            parameters.add(param);
        }
        if (!parameters.isEmpty()) op.put("parameters", parameters);

        if (requestBodySchema != null) {
            op.put("requestBody", Map.of(
                    "required", true,
                    "content", Map.of("application/json", Map.of("schema", requestBodySchema))));
        }

        Map<String, Object> responses = new LinkedHashMap<>();
        Map<String, Object> resp = new LinkedHashMap<>();
        if (r.javaMethod.getReturnType() == void.class || r.successStatus == 204 || r.successStatus == 304 || r.successStatus == 205) {
            resp.put("description", "No Content");
        } else {
            resp.put("description", "Successful Response");
            Object schema = schemaFor(r.javaMethod.getGenericReturnType(), schemas);
            resp.put("content", Map.of("application/json", Map.of("schema", schema)));
        }
        responses.put(String.valueOf(r.successStatus), resp);
        for (String code : List.of("400", "413", "415", "422", "500")) {
            responses.putIfAbsent(code, Map.of("description", "Request error", "content", Map.of("application/json", Map.of("schema",
                    Map.of("type", "object", "properties", Map.of("detail", Map.of("type", "string")))))));
        }
        op.put("responses", responses);
        return op;
    }

    /** Lambda routes carry no reflective type info, so we describe them generically. */
    private Map<String, Object> buildLambdaOperation(Route r) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", operationId(r));
        if (!r.paramNames.isEmpty()) {
            List<Map<String, Object>> parameters = new ArrayList<>();
            for (String name : r.paramNames) {
                parameters.add(Map.of("name", name, "in", "path", "required", true,
                        "schema", Map.of("type", "string")));
            }
            op.put("parameters", parameters);
        }
        op.put("responses", Map.of("200", Map.of("description", "Successful Response")));
        return op;
    }

    /** Resolves a reflective Type into an OpenAPI schema, registering object
     *  schemas (records/POJOs) under components.schemas as it goes. */
    private Object schemaFor(Type type, Map<String, Object> schemas) {
        if (type instanceof ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            if (raw == Optional.class) {
                return Map.of("allOf", List.of(schemaFor(pt.getActualTypeArguments()[0], schemas)), "nullable", true);
            }
            if (Collection.class.isAssignableFrom(raw)) {
                Map<String, Object> arr = new LinkedHashMap<>();
                arr.put("type", "array");
                arr.put("items", schemaFor(pt.getActualTypeArguments()[0], schemas));
                return arr;
            }
            if (Map.class.isAssignableFrom(raw)) {
                Map<String, Object> obj = new LinkedHashMap<>();
                obj.put("type", "object");
                obj.put("additionalProperties", schemaFor(pt.getActualTypeArguments()[1], schemas));
                return obj;
            }
            return schemaFor(raw, schemas);
        }
        if (!(type instanceof Class<?> c)) return Map.of();
        if (c == void.class || c == Void.class || c == Object.class || c == Response.class) return Map.of();
        if (c.isArray()) return Map.of("type", "array", "items", schemaFor(c.getComponentType(), schemas));
        if (isPrimitiveLike(c)) return primitiveSchema(c);
        if (c.isEnum()) {
            List<String> vals = new ArrayList<>();
            for (Object o : c.getEnumConstants()) vals.add(((Enum<?>) o).name());
            return Map.of("type", "string", "enum", vals);
        }
        if (Collection.class.isAssignableFrom(c)) return Map.of("type", "array", "items", Map.of());
        if (Map.class.isAssignableFrom(c)) return Map.of("type", "object");

        String name = c.getName().replace('$', '.');
        if (!schemas.containsKey(name)) {
            schemas.put(name, new LinkedHashMap<>()); // placeholder guards against self-referential recursion
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            if (c.isRecord()) {
                for (RecordComponent rc : c.getRecordComponents()) {
                    props.put(rc.getName(), constrained(schemaFor(rc.getGenericType(), schemas), rc));
                    if (rc.getType().isPrimitive() || rc.isAnnotationPresent(NotNull.class)) required.add(rc.getName());
                }
            } else {
                for (Field f : Json.fields(c)) {
                    props.put(f.getName(), constrained(schemaFor(f.getGenericType(), schemas), f));
                    if (f.isAnnotationPresent(NotNull.class)) required.add(f.getName());
                }
            }
            Map<String, Object> objSchema = new LinkedHashMap<>();
            objSchema.put("type", "object");
            objSchema.put("properties", props);
            if (!required.isEmpty()) objSchema.put("required", required);
            schemas.put(name, objSchema);
        }
        return Map.of("$ref", "#/components/schemas/" + name);
    }

    private static boolean isPrimitiveLike(Class<?> c) {
        return c == String.class || c == char.class || c == Character.class
                || c == byte.class || c == Byte.class || Number.class.isAssignableFrom(c)
                || c == int.class || c == Integer.class
                || c == long.class || c == Long.class
                || c == short.class || c == Short.class
                || c == double.class || c == Double.class
                || c == float.class || c == Float.class
                || c == boolean.class || c == Boolean.class;
    }

    private static Map<String, Object> primitiveSchema(Class<?> c) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (c == int.class || c == Integer.class || c == short.class || c == Short.class || c == byte.class || c == Byte.class) {
            m.put("type", "integer"); m.put("format", "int32");
        } else if (c == long.class || c == Long.class) {
            m.put("type", "integer"); m.put("format", "int64");
        } else if (c == java.math.BigInteger.class) {
            m.put("type", "integer");
        } else if (c == java.math.BigDecimal.class || c == Number.class) {
            m.put("type", "number");
        } else if (c == double.class || c == Double.class || c == float.class || c == Float.class) {
            m.put("type", "number");
        } else if (c == boolean.class || c == Boolean.class) {
            m.put("type", "boolean");
        } else {
            m.put("type", "string");
        }
        return m;
    }

    private static String operationId(Route r) {
        return r.method.toLowerCase(Locale.ROOT) + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(r.rawPath.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> constrained(Object schema, AnnotatedElement element) {
        Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) schema);
        if (out.containsKey("$ref")) out = new LinkedHashMap<>(Map.of("allOf", List.of(out)));
        Range range = element.getAnnotation(Range.class);
        if (range != null) { out.put("minimum", range.min()); out.put("maximum", range.max()); }
        Size size = element.getAnnotation(Size.class);
        if (size != null) {
            String type = Objects.toString(out.get("type"), "string");
            String suffix = type.equals("array") ? "Items" : type.equals("object") ? "Properties" : "Length";
            out.put("min" + suffix, size.min()); out.put("max" + suffix, size.max());
        }
        return out;
    }
    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String swaggerUiHtml() {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="utf-8"/>
                  <title>%s - Swagger UI</title>
                  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui.css">
                </head>
                <body>
                  <div id="swagger-ui"></div>
                  <script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
                  <script>
                    window.onload = () => {
                      window.ui = SwaggerUIBundle({
                        url: '/openapi.json',
                        dom_id: '#swagger-ui',
                        presets: [SwaggerUIBundle.presets.apis],
                        layout: 'BaseLayout'
                      });
                    };
                  </script>
                </body>
                </html>
                """.formatted(escapeHtml(apiTitle));
    }

    private String redocHtml() {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="utf-8"/>
                  <title>%s - ReDoc</title>
                </head>
                <body>
                  <redoc spec-url="/openapi.json"></redoc>
                  <script src="https://cdn.jsdelivr.net/npm/redoc@next/bundles/redoc.standalone.js"></script>
                </body>
                </html>
                """.formatted(escapeHtml(apiTitle));
    }
}
