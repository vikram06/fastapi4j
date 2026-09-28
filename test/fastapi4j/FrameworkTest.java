package fastapi4j;

import java.net.URI;
import java.net.http.*;
import java.math.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/** Dependency-free regression suite; failures exit nonzero. */
public class FrameworkTest {
    static int checks;
    static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
    record Item(@NotNull @Size(min=1, max=10) String name, @Range(min=0, max=100) int quantity) {}
    record Nested(@NotNull Item item, List<Item> items) {}
    record Types(List<Integer> list, Map<String, Long> map, Optional<String> optional) {}
    record Node(String name, Node child) {}
    public static class Parent { public String inherited; }
    public static class Pojo extends Parent { @NotNull public String name; public transient String secret; }
    enum Color { RED, BLUE }
    static class One { record Same(String a) {} }
    static class Two { record Same(int b) {} }
    @RestController("/api")
    public static class Controller {
        @Get("/context") public Object context(App.Ctx ctx, @HeaderParam("X-Test") String header) { return Map.of("header", ctx.header("X-Test"), "bound", header, "attribute", ctx.attribute("seen")); }
        @Get("/id/{id}") public long id(long id) { return id; }
        @Get("/list") public Object list(@QueryParam("n") List<Integer> numbers) { return numbers; }
        @Get("/default") public int defaultValue(@QueryParam(value="n", defaultValue="7") int n) { return n; }
        @Get("/optional") public Object optional(@QueryParam(value="n", required=false) int n, @QueryParam(value="q", required=false) Optional<String> q) { return Map.of("n", n, "q", q); }
        @Get("/enum") public Color color(@QueryParam("color") Color color) { return color; }
        @Post("/items") @Status(201) @Operation(summary="Create item", tags={"Items"}) public Item item(@Body Item item) { return item; }
        @Post("/nested") public Nested nested(@Body Nested value) { return value; }
        @Post("/pojo") public Pojo pojo(@Body Pojo value) { return value; }
        @Delete("/empty") public void empty() {}
        @Post("/accepted") @Status(202) public void accepted() {}
        @Get("/bounded") public int bounded(@QueryParam("n") @Range(min=1,max=3) int n) { return n; }
        @Get("/array") public int[] array() { return new int[]{1,2}; }
        @Get("/one") public One.Same one() { return new One.Same("a"); }
        @Get("/two") public Two.Same two() { return new Two.Same(2); }
        @Get("/recursive") public Node recursive() { return new Node("a", null); }
        @Get("/failure") public Object failure() { throw new IllegalStateException("secret-password"); }
    }
    @RestController("/") public static class RootController { @Get("/") public String root() { return "root"; } }
    @RestController public static class InvalidController { @Get("/x") public String x(@PathParam("missing") String x) { return x; } }
    public static void main(String[] args) throws Exception {
        json(); http(); config();
        System.out.println("PASS: " + checks + " checks");
        CLIENT.close();
    }
    static void eq(Object expected, Object actual) { checks++; if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual); }
    static void yes(boolean value) { checks++; if (!value) throw new AssertionError("Condition failed"); }
    static void fails(Class<? extends Throwable> type, Runnable action) {
        checks++; try { action.run(); } catch (Throwable e) { if (type.isInstance(e)) return; throw new AssertionError("Wrong exception", e); } throw new AssertionError("Expected " + type.getSimpleName());
    }
    static void json() throws Exception {
        eq(9007199254740993L, Json.convert("9007199254740993", long.class));
        eq(Long.MAX_VALUE, Json.convert(Long.toString(Long.MAX_VALUE), long.class));
        eq(new BigInteger("9223372036854775808"), Json.parse("9223372036854775808"));
        eq(new BigDecimal("0.1234567890123456789"), Json.parse("0.1234567890123456789"));
        for (String input : List.of("", " ", "{", "[", "[1,]", "{\"a\":1,}", "{\"a\":1,\"a\":2}", "true x", "01", "1.", "1e", "+1", "-", "NaN", "\"a\nb\"", "\"\\uXYZZ\"", "\"\\u12\"", "\"unterminated", "\u00a01")) fails(HttpException.class, () -> Json.parse(input));
        for (String input : List.of("1.9", "2147483648", "NaN", "abc")) fails(HttpException.class, () -> Json.convert(input, int.class));
        fails(HttpException.class, () -> Json.convert("wrong", boolean.class));
        fails(HttpException.class, () -> Json.convert("1e1000", double.class));
        eq(true, Json.convert("TRUE", boolean.class));
        eq((byte)127, Json.convert("127", byte.class));
        eq('a', Json.convert("a", char.class));
        fails(IllegalArgumentException.class, () -> Json.toJson(Double.NaN));
        fails(IllegalArgumentException.class, () -> Json.toJson(Double.POSITIVE_INFINITY));
        List<Object> cycle = new ArrayList<>(); cycle.add(cycle);
        fails(IllegalArgumentException.class, () -> Json.toJson(cycle));
        fails(HttpException.class, () -> Json.parse("[".repeat(140) + "0" + "]".repeat(140)));
        String text = "quote\" backslash\\ newline\n emoji 😀";
        eq(text, Json.parse(Json.toJson(text)));
        eq("{\"name\":\"hi\",\"quantity\":2}", Json.toJson(new Item("hi",2)));
        eq(new Item("hi",2), Json.convert(Json.parse("{\"name\":\"hi\",\"quantity\":2}"), Item.class));
        fails(HttpException.class, () -> Json.convert(Json.parse("{}"), Item.class));
        fails(HttpException.class, () -> Json.convert(Json.parse("{\"name\":\"\",\"quantity\":1}"), Item.class));
        fails(HttpException.class, () -> Json.convert(Json.parse("{\"name\":\"x\",\"quantity\":101}"), Item.class));
        Type listType = Types.class.getRecordComponents()[0].getGenericType();
        eq(List.of(1,2), Json.convert(Json.parse("[1,2]"), listType));
        fails(HttpException.class, () -> Json.convert(Map.of(), listType));
        Type mapType = Types.class.getRecordComponents()[1].getGenericType();
        eq(Map.of("id", 9007199254740993L), Json.convert(Json.parse("{\"id\":9007199254740993}"), mapType));
        fails(HttpException.class, () -> Json.convert(List.of(), mapType));
        yes(Arrays.equals(new int[]{1,2}, Json.convert(Json.parse("[1,2]"), int[].class)));
        Pojo pojo = Json.convert(Json.parse("{\"name\":\"ok\",\"inherited\":\"yes\",\"secret\":\"hidden\"}"), Pojo.class);
        eq("yes", pojo.inherited); yes(!Json.toJson(pojo).contains("secret"));
        // Deterministic round trips across exact integers and escaped strings.
        Random random = new Random(42);
        for (int i=0; i<100; i++) { long n=random.nextLong(); eq(n, Json.parse(Json.toJson(n))); }
    }
    static HttpResponse<String> request(App app, String method, String path, String body, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.port() + path)).timeout(java.time.Duration.ofSeconds(5));
        if (headers.length > 0) builder.headers(headers);
        return CLIENT.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @SuppressWarnings("unchecked") static Map<String,Object> object(String body) { return (Map<String,Object>)Json.parse(body); }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    static void status(int status, HttpResponse<String> response) { eq(status,response.statusCode()); }
    static void http() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (App app = new App().title("<test>").maxBodyBytes(128).cors(Cors.allowOrigins("https://example.com"))) {
            app.use((ctx,next) -> { ctx.attribute("seen", "yes"); ctx.responseHeader("X-Middleware", "true"); calls.incrementAndGet(); return next.handle(); });
            app.use((ctx,next) -> ctx.header("X-Deny") == null ? next.handle() : Response.json(401, Map.of("detail", "Denied")));
            app.get("/echo/{value}", c -> c.path("value"));
            app.get("/echo/static", c -> "static");
            app.get("/text", c -> Response.text("hello").header("X-Custom", "ok"));
            app.get("/html", c -> Response.html("<b>hi</b>"));
            app.get("/redirect", c -> Response.redirect("/text"));
            app.get("/status", c -> { c.status(202); return Map.of("ok",true); });
            app.get("/no-content", c -> Response.json(204, Map.of("discarded", true)));
            app.get("/query", c -> c.query("q"));
            app.get("/bad-number", c -> Double.NaN);
            app.post("/lambda-body", c -> c.body(Item.class));
            app.register(new Controller()).run("127.0.0.1",0);
            fails(IllegalStateException.class, () -> app.get("/late", c -> "no"));
            fails(IllegalStateException.class, () -> app.run(0));
            var res=request(app,"GET","/api/context",null,"X-Test","hello"); status(200,res);
            eq(Map.of("header","hello","bound","hello","attribute","yes"), object(res.body()));
            yes(res.headers().firstValue("X-Request-ID").isPresent()); eq("true",res.headers().firstValue("X-Middleware").orElseThrow());
            status(422,request(app,"GET","/api/context",null));
            eq("9007199254740993", request(app,"GET","/api/id/9007199254740993",null).body());
            status(422,request(app,"GET","/api/id/1.9",null));
            status(422,request(app,"GET","/api/id/nope",null));
            eq("[1,2]",request(app,"GET","/api/list?n=1&n=2",null).body());
            status(422,request(app,"GET","/api/list",null));
            status(422,request(app,"GET","/api/list?n=bad",null));
            eq("7",request(app,"GET","/api/default",null).body());
            Map<String,Object> optional = object(request(app,"GET","/api/optional",null).body());
            eq(0L, optional.get("n")); eq(null, optional.get("q"));
            eq("\"RED\"",request(app,"GET","/api/enum?color=RED",null).body());
            status(422,request(app,"GET","/api/enum?color=GREEN",null));
            status(422,request(app,"GET","/api/bounded?n=4",null));
            eq("[1,2]",request(app,"GET","/api/array",null).body());
            for (String path : List.of("a+b", "a%2Bb", "a%252Fb", "a%2Fb", "%E2%9C%93")) {
                String expected = switch(path) { case "a+b", "a%2Bb" -> "a+b"; case "a%252Fb" -> "a%2Fb"; case "a%2Fb" -> "a/b"; default -> "✓"; };
                eq(expected, Json.parse(request(app,"GET","/echo/"+path,null).body()));
            }
            eq("static",Json.parse(request(app,"GET","/echo/static/",null).body()));
            status(400,request(app,"GET","/echo/%FF",null));
            eq("a b",Json.parse(request(app,"GET","/query?q=a+b",null).body()));
            status(400,request(app,"GET","/query?q=%FF",null));
            String item="{\"name\":\"apple\",\"quantity\":3}";
            status(201,request(app,"POST","/api/items",item,"Content-Type","application/json"));
            status(201,request(app,"POST","/api/items",item,"Content-Type","application/vnd.test+json"));
            status(415,request(app,"POST","/api/items",item,"Content-Type","text/plain"));
            status(400,request(app,"POST","/api/items","{"));
            status(400,request(app,"POST","/api/items",item+"garbage"));
            status(400,request(app,"POST","/api/items",""));
            status(422,request(app,"POST","/api/items","null"));
            status(422,request(app,"POST","/api/items","{}"));
            status(422,request(app,"POST","/api/items","{\"name\":\"x\",\"quantity\":-1}"));
            status(422,request(app,"POST","/api/nested","{\"item\":{\"name\":\"\",\"quantity\":1}}"));
            status(422,request(app,"POST","/api/pojo","{}"));
            status(413,request(app,"POST","/api/items","x".repeat(129)));
            var chunked = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.port()+"/api/items")).POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(new byte[129]))).build();
            status(413,CLIENT.send(chunked,HttpResponse.BodyHandlers.ofString()));
            status(200,request(app,"POST","/lambda-body",item));
            res=request(app,"DELETE","/api/empty",null); status(204,res); eq("",res.body());
            status(202,request(app,"POST","/api/accepted",null));
            res=request(app,"GET","/no-content",null); status(204,res); eq("",res.body());
            res=request(app,"HEAD","/text",null); status(200,res); eq("",res.body()); eq("5",res.headers().firstValue("Content-Length").orElseThrow());
            res=request(app,"OPTIONS","/text",null); status(204,res); yes(res.headers().firstValue("Allow").orElseThrow().contains("HEAD"));
            res=request(app,"POST","/text",null); status(405,res); yes(res.headers().firstValue("Allow").isPresent());
            status(404,request(app,"GET","/missing",null));
            res=request(app,"GET","/text",null); eq("hello",res.body()); eq("text/plain; charset=utf-8",res.headers().firstValue("Content-Type").orElseThrow());
            eq("<b>hi</b>",request(app,"GET","/html",null).body());
            res=request(app,"GET","/redirect",null); status(303,res); eq("/text",res.headers().firstValue("Location").orElseThrow());
            status(202,request(app,"GET","/status",null));
            res=request(app,"GET","/text",null,"X-Deny","yes","Origin","https://example.com"); status(401,res);
            eq("https://example.com",res.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            status(401,request(app,"GET","/docs",null,"X-Deny","yes"));
            res=request(app,"GET","/api/failure",null); status(500,res); yes(!res.body().contains("secret"));
            status(500,request(app,"GET","/bad-number",null));
            res=request(app,"OPTIONS","/api/items",null,"Origin","https://example.com","Access-Control-Request-Method","POST","Access-Control-Request-Headers","Content-Type"); status(204,res);
            eq("https://example.com",res.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
            status(403,request(app,"OPTIONS","/api/items",null,"Origin","https://evil.example","Access-Control-Request-Method","POST"));
            status(403,request(app,"OPTIONS","/api/items",null,"Origin","https://example.com","Access-Control-Request-Method","TRACE"));
            status(403,request(app,"OPTIONS","/api/items",null,"Origin","https://example.com","Access-Control-Request-Method","POST","Access-Control-Request-Headers","X-Forbidden"));
            res=request(app,"POST","/api/items","{}","Origin","https://example.com"); status(422,res); yes(res.headers().firstValue("Access-Control-Allow-Origin").isPresent());
            yes(request(app,"GET","/docs",null).body().contains("&lt;test&gt;"));
            res=request(app,"GET","/openapi.json",null); status(200,res);
            Map<String,Object> spec=object(res.body()), paths=map(spec.get("paths"));
            Map<String,Object> operation=map(map(paths.get("/api/items")).get("post")); eq("Create item",operation.get("summary"));
            yes(map(map(map(paths.get("/api/empty")).get("delete")).get("responses")).containsKey("204"));
            Map<String,Object> listParam=map(((List<?>)map(map(paths.get("/api/list")).get("get")).get("parameters")).get(0));
            eq("array",map(listParam.get("schema")).get("type"));
            Map<String,Object> defaultParam=map(((List<?>)map(map(paths.get("/api/default")).get("get")).get("parameters")).get(0)); eq(false,defaultParam.get("required"));
            Map<String,Object> schemas=map(map(spec.get("components")).get("schemas"));
            yes(schemas.containsKey("fastapi4j.FrameworkTest.One.Same")); yes(schemas.containsKey("fastapi4j.FrameworkTest.Two.Same"));
            eq(res.body(),request(app,"GET","/openapi.json",null).body());
            // Concurrent requests exercise shared controller and immutable route configuration.
            try (var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<HttpResponse<String>>> futures=new ArrayList<>();
                for(int i=0;i<30;i++) futures.add(pool.submit(() -> request(app,"GET","/api/id/42",null)));
                for(var f:futures) eq("42",f.get().body());
            }
            yes(calls.get()>60);
        }
    }
    static void config() throws Exception {
        fails(IllegalArgumentException.class, () -> new App().maxBodyBytes(-1));
        fails(IllegalArgumentException.class, () -> new App().get("/x/{a}", c->null).get("/x/{b}", c->null));
        fails(IllegalArgumentException.class, () -> new App().get("/{a}/{a}", c->null));
        fails(IllegalArgumentException.class, () -> new App().get("/{invalid", c->null));
        fails(IllegalArgumentException.class, () -> new App().register(new InvalidController()));
        fails(IllegalArgumentException.class, () -> new Cors(Set.of("*"),Set.of("GET"),Set.of(),true,1));
        fails(IllegalArgumentException.class, () -> Response.text("x").header("X-Test", "a\r\nb"));
        fails(IllegalArgumentException.class, () -> Response.bytes(200, new byte[0], "text/plain\r\nX-Evil: yes"));
        fails(IllegalArgumentException.class, () -> Response.text("x").header("X-Test", "\u0000"));
        try(App app=new App().docs(false).register(new RootController()).run("127.0.0.1",0)) {
            eq("\"root\"",request(app,"GET","/",null).body()); status(404,request(app,"GET","/docs",null));
            app.stop(); app.run("127.0.0.1",0); status(200,request(app,"GET","/",null));
        }
        try(App app=new App().onError((ctx,e)->Response.json(409,Map.of("detail","custom"))).get("/",c->{throw new IllegalStateException();}).run("127.0.0.1",0)) {
            status(409,request(app,"GET","/",null));
        }
        try(App app=new App().get("/",c->"get").head("/",c->Response.text("head").header("X-Explicit","yes")).run("127.0.0.1",0)) {
            eq("yes",request(app,"HEAD","/",null).headers().firstValue("X-Explicit").orElseThrow());
        }
    }
}
