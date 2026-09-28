# fastapi4j

A small, dependency-free **Java 21+** HTTP framework inspired by FastAPI.
Use lambdas or annotated controllers, return records as JSON, and get OpenAPI,
Swagger UI and ReDoc automatically. Built on the JDK HTTP server and virtual threads.

## Quick start

```sh
sh build.sh test       # compile and run the dependency-free regression suite
sh build.sh example    # start the example on port 8000
sh build.sh jar        # produce build/fastapi4j.jar
```

No Maven, Gradle, or downloaded runtime dependencies are required. Linux/macOS
need a JDK 21+ and a POSIX shell. On Windows, compile directly with `javac`:

```sh
javac --release 21 -parameters -d out src/fastapi4j/*.java example/Main.java
java -cp out Main
```

Compile application controllers with **`-parameters`** for parameter-name inference.
Explicit `@PathParam`, `@QueryParam`, `@HeaderParam` and `@Body` bindings do not
need parameter names. Missing names fail at registration instead of at request time.

```java
import fastapi4j.*;
import java.util.Map;

public class Hello {
    public static void main(String[] args) {
        App app = new App();
        app.get("/", ctx -> Map.of("message", "Hello"));
        app.get("/hello/{name}", ctx -> Map.of("hello", ctx.path("name")));
        app.run("127.0.0.1", 8000);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> app.stop(3)));
    }
}
```

Open `/docs`, `/redoc`, `/openapi.json`, or `/__routes` on your server.

## Annotated controllers and validation

```java
import fastapi4j.*;
import java.util.List;

public class Catalog {
    public record Item(@NotNull @Size(min = 1, max = 100) String name,
                       @Range(min = 0) double price) {}

    @RestController("/items")
    public static class Items {
        @Get("/{id}")
        public Object get(long id) {
            return java.util.Map.of("id", id);
        }

        @Post("")
        @Status(201)
        @Operation(summary = "Create item", tags = {"Items"})
        public Item create(@Body Item item, App.Ctx ctx) {
            ctx.responseHeader("X-Created-By", "fastapi4j");
            return item;
        }

        @Get("")
        public List<String> list(
                @QueryParam(value = "tag", required = false) List<String> tags,
                @QueryParam(value = "limit", defaultValue = "10")
                @Range(min = 1, max = 100) int limit) {
            return tags.stream().limit(limit).toList();
        }
    }
}
```

Register with `app.register(new Catalog.Items())` before starting the server.
`@Get`, `@Post`, `@Put`, `@Patch` and `@Delete` combine with the controller prefix.
An unannotated parameter matching a path placeholder binds from the path;
other unannotated parameters are required query parameters. `App.Ctx` is injected
with the live exchange and the same attributes used by middleware.

| Binding / constraint | Behavior |
|---|---|
| `@Body` | One JSON body per method; empty/malformed JSON → 400, null body → 422 |
| `@PathParam("id")` | Explicit path parameter name |
| `@QueryParam("q")` | Required query value by default |
| `@HeaderParam("X-Token")` | Case-insensitive request header binding |
| `required = false` | Missing reference → null; `Optional<T>` → empty; list → empty; primitive → zero/false |
| `defaultValue = "10"` | Used when missing, even when `required` is true |
| `List<T>`, `Set<T>`, `Collection<T>` | Repeated query/header values converted element by element |
| `@NotNull` | Reject null |
| `@Range(min = ..., max = ...)` | Inclusive numeric bounds |
| `@Size(min = ..., max = ...)` | Bounds string UTF-16 length, collection/map size, or array length |

Validation constraints work on parameters, record components and POJO fields.
Nested body models are converted and validated recursively. Missing/null primitive
record fields fail with 422; nullable reference fields remain optional unless marked
`@NotNull`. POJOs retain constructor/field defaults for absent fields. Unknown JSON
properties are ignored. POJOs need a no-argument constructor; inherited fields are
included, while static, synthetic and transient fields are excluded.

## Responses

Returning an ordinary Java value serializes it to JSON, with status 200 by default.
`@Status` changes an annotated route's status. A `void` method defaults to 204;
an explicit `@Status` is honored. Lambda handlers can call `ctx.status(201)`.

Use `Response` when status, representation or headers matter:

```java
app.post("/created", ctx -> Response.json(201, Map.of("id", 1))
        .header("Location", "/items/1"));
app.get("/text", ctx -> Response.text("Hello"));
app.get("/page", ctx -> Response.html("<h1>Hello</h1>"));
app.get("/old", ctx -> Response.redirect("/text")); // 303; .status(307) to override
app.delete("/cache", ctx -> Response.noContent());
app.get("/download", ctx -> Response.bytes(200, new byte[]{1, 2, 3}, "application/octet-stream"));
```

Responses are immutable. A returned `Response` takes precedence over `@Status`
and `ctx.status`. HEAD responses omit bodies, and 204/205/304 always suppress bodies.
The server owns Content-Length and Transfer-Encoding. Do not write directly to the
exchange response stream; use response objects or `ctx.responseHeader`.

## Middleware and errors

```java
app.use((ctx, next) -> {
    long start = System.nanoTime();
    ctx.attribute("service", "catalog");
    try {
        return next.handle();
    } finally {
        ctx.responseHeader("X-Response-Time-Ms",
                Long.toString((System.nanoTime() - start) / 1_000_000));
    }
});

app.use((ctx, next) -> {
    // Supply your application's authentication check here.
    if (ctx.header("Authorization") == null) {
        return Response.json(401, Map.of("detail", "Authentication required"));
    }
    return next.handle();
});
```

Middleware runs in registration order and wraps the handler, including docs,
404 and 405 responses. Call `next.handle()` once or return early. Request bodies
are bounded and read before middleware; malformed URLs and oversized bodies fail
before middleware runs. Configured CORS preflight responses run before middleware.

`HttpException.notFound(...)`, `.badRequest(...)`, `.unprocessable(...)`, or
`new HttpException(status, detail)` produce a JSON `detail` response. Malformed JSON
returns 400; type/constraint errors return 422; unsupported JSON body media types
return 415. JSON Content-Type may be absent for compatibility; if present it must
be `application/json` or an `application/*+json` type.

Unexpected exceptions return a generic 500, with details logged only on the server.
Every response gets a generated `X-Request-ID` and `X-Content-Type-Options: nosniff`.

```java
app.onError((ctx, exception) -> {
    if (exception instanceof HttpException error) {
        return Response.json(error.statusCode, Map.of("detail", error.getMessage()));
    }
    return Response.json(500, Map.of("detail", "Unexpected error", "requestId", ctx.requestId()));
});
```

The custom error handler receives errors after a context exists, including binding
and middleware errors. Failures before context creation use the default handler.

## CORS

CORS is off by default. Enable an explicit origin allowlist:

```java
app.cors(Cors.allowOrigins("https://app.example.com", "http://localhost:3000"));
```

This allows standard API methods and the `Content-Type`/`Authorization` headers.
For credentials or custom headers, use a policy:

```java
app.cors(new Cors(
        java.util.Set.of("https://app.example.com"),
        java.util.Set.of("GET", "HEAD", "POST", "OPTIONS"),
        java.util.Set.of("content-type", "authorization", "x-token"),
        true, 600));
```

Credentialed CORS rejects wildcard origins. Preflight checks both the policy and
registered route methods. CORS does not authenticate requests; browsers enforce
access to responses. Disallowed origins receive no CORS grant, and disallowed
preflight requests receive 403. Validation/handler errors retain CORS headers;
errors before context creation do not.

## Routing and request context

- Literal routes take priority over parameter routes, independent of registration order.
- Duplicate method/path shapes (such as `/x/{id}` and `/x/{name}`) fail at startup.
- Matching ignores trailing slashes. Path parameters decode once, preserving `+`;
  `%2F` stays inside its captured parameter and becomes `/` in its value.
- Literal URL components in registered routes should use their URI-encoded form.
- GET provides implicit HEAD; explicit `app.head(...)` overrides it.
- OPTIONS supplies an Allow header; unsupported methods on known paths return 405.
- `app.route(method, path, handler)` supports GET, HEAD, POST, PUT, PATCH, DELETE,
  OPTIONS and TRACE. `app.options(...)` registers a custom OPTIONS handler.

`Ctx` exposes `path`, `pathInt`, `pathLong`, `query`, `query(name, fallback)`,
`queryAll`, `queryInt(name, fallback)`, `bodyString`, `body(Class)`, `header`,
`method`, `requestId`, `status`, `responseHeader` and request-local `attribute`
get/set methods. `pathParams` and `queryParams` are immutable snapshots;
`rawBody` and the raw `exchange` remain available for advanced integrations.

## JSON behavior

The parser rejects trailing garbage, duplicate keys, unescaped control characters,
invalid number syntax, invalid escapes and nesting beyond 128 levels. Integer
values use Long or BigInteger; decimal/exponent values use BigDecimal. Conversion
to integer types is exact, with overflow/fraction rejection; booleans accept only
true/false (case-insensitive when converting strings).

Supported models include records, POJOs, primitives/wrappers, enums, arrays,
`Optional<T>`, `List<T>`, `Set<T>`, `Collection<T>` and `Map<String,T>`.
Serialization rejects cycles, non-finite numbers and excessive nesting. Generic
user-defined models and unresolved type variables are not supported for conversion.

## OpenAPI and documentation

`app.title(...)`, `.version(...)` and `.description(...)` customize the API info.
`@Operation(summary = ..., description = ..., tags = {...}, deprecated = true)`
adds route documentation. Generated schemas include nested models, arrays, enums,
validation constraints, typed repeated parameters and header bindings. Schema
names use qualified class names to avoid collisions, and operation IDs are stable.
Use `app.buildOpenApiSpec()` to inspect/export the spec without starting a server.

Lambda routes and `Response` return values have no reflective payload schema;
add application-specific documentation when you use those. Dynamic statuses and
custom error handlers are not inferred. Nullable reference fields are optional in
schemas, but explicit-null acceptance is not fully described for every model type.

Swagger UI and ReDoc assets load from jsDelivr in the browser. Disable the built-in
documentation and route listing with `app.docs(false)`. Built-in documentation paths
are reserved while enabled. HTML titles are escaped.

## Server lifecycle and deployment

```java
try (App app = new App().maxBodyBytes(256 * 1024).docs(false)) {
    app.get("/health", ctx -> Map.of("status", "ok"));
    app.run("127.0.0.1", 0); // OS-assigned port; read app.port()
    // Keep your application running here. Closing this scope stops the server.
}
```

Configure routes, middleware and settings before `run`. `run(port)` binds all
interfaces; `run(host, port)` selects an address. `stop()` stops immediately;
`stop(delaySeconds)` allows in-flight requests up to that delay, then releases
the virtual-thread executor. Restarting a stopped app is supported.

The default body limit is **1 MiB**, enforced for fixed-length and chunked requests.
This is a compact framework, not a full production server platform. Use a reverse
proxy for TLS, request/header timeouts, connection limits and rate limiting.
Handlers run concurrently, so application state must be thread-safe. There is no
built-in persistence, WebSocket support, multipart upload, streaming response,
static-file hosting, authentication provider or dependency-injection container.

See [CHANGELOG.md](CHANGELOG.md) for compatibility changes and
[CONTRIBUTING.md](CONTRIBUTING.md) for development instructions.
