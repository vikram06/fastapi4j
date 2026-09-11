# fastapi4j — API Reference

A zero-dependency Java web framework with FastAPI-flavored ergonomics, built on
`com.sun.net.httpserver` and a hand-rolled JSON engine (no Spring, no Jackson,
no Maven Central).

---

## 1. Installation

There's no build system dependency — just compile the framework sources
alongside your app. **Compile with `-parameters`** so the framework can read
your method parameter names (needed for automatic path/query binding without
annotations):

```bash
javac -parameters -d out src/fastapi4j/*.java YourApp.java
java -cp out YourApp
```

Requires Java 17+ (uses records); the reference implementation targets Java 21.

---

## 2. Starting an app

```java
App app = new App();
app.get("/", ctx -> Map.of("message", "hello"));
app.run(8000);
```

`App.run(port)` starts an HTTP server on virtual threads and prints a route
table. `App.stop()` shuts it down.

Every app automatically exposes `GET /__routes`, returning a JSON array of
`{method, path}` for every registered route — a lightweight stand-in for
FastAPI's `/docs`.

---

## 3. Route style 1 — lambdas

```java
App app = new App();

app.get("/items/{item_id}",  ctx -> ...);
app.post("/items",           ctx -> ...);
app.put("/items/{item_id}",  ctx -> ...);
app.delete("/items/{item_id}", ctx -> ...);
app.patch("/items/{item_id}", ctx -> ...);
```

Each handler is `Ctx -> Object`. Whatever you return is serialized to JSON
with a `200` status (use `Ctx`/`HttpException` to change that — see §6).

### `Ctx` — the request context

| Method | Description |
|---|---|
| `ctx.path(name)` | path param as `String` |
| `ctx.pathInt(name)` / `ctx.pathLong(name)` | path param parsed as number |
| `ctx.query(name)` | first query value, or `null` |
| `ctx.query(name, default)` | first query value, or `default` |
| `ctx.queryAll(name)` | all values for a repeated query key |
| `ctx.bodyString()` | raw request body as UTF-8 text |
| `ctx.body(SomeRecord.class)` | JSON body parsed into a record/POJO |
| `ctx.header(name)` | request header value |
| `ctx.exchange` | the raw `HttpExchange`, for anything not covered above |

---

## 4. Route style 2 — annotated controllers

Closest to FastAPI's `@app.get(...)` decorators.

```java
@RestController("/items")           // optional path prefix
public class Items {

    @Get("/{id}")
    public Item getOne(int id) { ... }        // "id" bound from the path

    @Get("")
    public List<Item> list(
            @QueryParam(value = "skip",  required = false, defaultValue = "0")  int skip,
            @QueryParam(value = "limit", required = false, defaultValue = "10") int limit) { ... }

    @Post("")
    @Status(201)
    public Item create(@Body Item item) { ... }

    @Put("/{id}")
    public Item update(int id, @Body Item item) { ... }

    @Delete("/{id}")
    @Status(204)
    public void remove(int id) { ... }
}

app.register(new Items());
```

### Annotations

| Annotation | Applies to | Purpose |
|---|---|---|
| `@RestController(prefix)` | class | optional path prefix for every route in the class |
| `@Get/@Post/@Put/@Delete/@Patch(path)` | method | registers a route; combines with the class prefix |
| `@PathParam(name)` | parameter | explicit path-param binding (usually not needed — see inference below) |
| `@QueryParam(name, required, defaultValue)` | parameter | query-param binding, with optional default |
| `@Body` | parameter | binds the JSON request body into a record/POJO/List/Map |
| `@Status(code)` | method | overrides the default `200` success status (e.g. `201`, `204`) |

### Automatic parameter inference

If a method parameter has **no annotation**, fastapi4j inspects its name
(requires `-parameters`):

- If the name matches a `{placeholder}` in the route path → bound as a path param.
- Otherwise → treated as a **required** query param with that name.

```java
@Get("/{id}")
public Item getOne(int id) { ... }          // "id" -> path param, no annotation needed
```

This mirrors how FastAPI infers parameters from your function signature.

---

## 5. Models = Java records

Any `record` works as a request or response model, the way FastAPI uses
Pydantic `BaseModel`s:

```java
public record Address(String city, String zip) {}
public record User(String name, Address address, List<String> tags) {}
```

- **Serialization**: returning a `User` (or `List<User>`, `Map<String, User>`,
  etc.) from a handler serializes it to JSON automatically, including nested
  records, lists, and enums.
- **Deserialization**: `@Body User user` (or `ctx.body(User.class)`) parses
  the JSON request body straight into a `User`, recursively converting nested
  fields.
- Plain (non-record) classes work too, as long as they have a no-arg
  constructor — fields are matched by name.

Supported field/parameter types: `String`, `int`/`Integer`, `long`/`Long`,
`double`/`Double`, `float`/`Float`, `short`/`Short`, `boolean`/`Boolean`,
enums, records, POJOs, `List<T>`, and `Map<String, T>`.

---

## 6. Errors

Throw `HttpException` from any handler to return a specific status with a
JSON `{"detail": "..."}` body — like FastAPI's `HTTPException`:

```java
throw HttpException.notFound("Item " + id + " not found");   // 404
throw HttpException.badRequest("Invalid input");              // 400
throw HttpException.unprocessable("Missing field");            // 422
throw new HttpException(409, "Already exists");                // any status
```

Uncaught exceptions become `500 {"detail": "Internal Server Error: ..."}`,
and the stack trace is printed server-side.

A required `@QueryParam` (or an inferred required query param) that's missing
from the request automatically raises a `422`.

---

## 7. Automatic Swagger / OpenAPI docs

Every app automatically exposes:

| Path | What it is |
|---|---|
| `/openapi.json` | a generated OpenAPI 3.0 document |
| `/docs` | Swagger UI, rendered against `/openapi.json` |
| `/redoc` | ReDoc, rendered against `/openapi.json` |

No configuration needed — the spec is built by introspecting your
`@RestController` classes: route paths/methods, `@PathParam`/`@QueryParam`
(including `required`/`defaultValue`), `@Body` types, return types, and
`@Status` codes. Record and POJO types are recursively converted into
`components.schemas` entries, the same role Pydantic models play in FastAPI's
generated docs — nested records, lists, maps, and enums are all resolved.

Set the title/version/description shown in the docs UI:

```java
app.title("Item API").version("1.0.0").description("Demo API for fastapi4j");
```

**Swagger UI and ReDoc are loaded from a CDN** (`cdn.jsdelivr.net`) by the
browser that opens `/docs`/`/redoc` — the Java server itself has zero extra
dependencies; it just serves a small HTML page and the JSON spec.

**Limitation:** lambda routes (`app.get(...)`) don't carry reflective type
information, so they appear in the spec with just their path, method, and a
generic response — annotated controllers get full parameter/schema detail.

---

## 8. Status codes

- Default success status is `200`.
- `@Status(201)` (or any code) on an annotated method overrides it.
- A method with return type `void` automatically responds `204 No Content`.
- For lambda routes, the status defaults to `200`; throw `HttpException` for
  anything else.

---

## 9. Full example

```java
import fastapi4j.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Main {
    public record Item(String name, double price, boolean isOffer) {}

    static final Map<Integer, Item> DB = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        App app = new App();

        app.get("/", ctx -> Map.of("message", "Hello from fastapi4j"));

        app.register(new ItemController());
        app.run(8000);
    }

    @RestController("/api/items")
    public static class ItemController {

        @Get("")
        public Collection<Item> list() { return DB.values(); }

        @Get("/{id}")
        public Item getOne(int id) {
            Item item = DB.get(id);
            if (item == null) throw HttpException.notFound("Item " + id + " not found");
            return item;
        }

        @Post("")
        @Status(201)
        public Item create(@Body Item item) {
            DB.put(DB.size() + 1, item);
            return item;
        }

        @Delete("/{id}")
        @Status(204)
        public void remove(int id) {
            if (DB.remove(id) == null) throw HttpException.notFound("Item " + id + " not found");
        }
    }
}
```

```bash
curl http://localhost:8000/api/items
curl -X POST http://localhost:8000/api/items \
  -d '{"name":"Doohickey","price":4.5,"isOffer":true}'
```

---

## 10. What's intentionally not included

To stay dependency-free and readable, fastapi4j leaves out: HTTPS, WebSockets,
static file serving, declarative validation constraints (`@Min`/`@Max` etc.),
automatic OpenAPI/Swagger UI generation, and a middleware/interceptor chain.
All of these can be layered on top of the existing `App`/`Route` structure if
you need them.
