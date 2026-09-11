import fastapi4j.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Main {

    // A "model" is just a Java record -- like a Pydantic BaseModel.
    public record Item(String name, double price, boolean isOffer) {}

    static final Map<Integer, Item> DB = new ConcurrentHashMap<>();
    static {
        DB.put(1, new Item("Widget", 9.99, false));
        DB.put(2, new Item("Gadget", 19.99, true));
    }

    public static void main(String[] args) {
        App app = new App();
        app.title("Item API").version("1.0.0").description("Demo API for fastapi4j");

        // ----- Style 1: lambda routes (Express/Javalin flavor) -----
        app.get("/", ctx -> Map.of("message", "Hello from fastapi4j"));

        app.get("/items/{item_id}", ctx -> {
            int id = ctx.pathInt("item_id");
            String q = ctx.query("q");
            Item item = DB.get(id);
            if (item == null) throw HttpException.notFound("Item " + id + " not found");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("item_id", id);
            out.put("item", item);
            out.put("q", q);
            return out;
        });

        // ----- Style 2: annotated controller (FastAPI decorator flavor) -----
        app.register(new ItemController());

        app.run(8000);
    }

    @RestController("/api/items")
    public static class ItemController {

        // GET /api/items?skip=0&limit=10
        @Get("")
        public List<Map<String, Object>> list(
                @QueryParam(value = "skip", required = false, defaultValue = "0") int skip,
                @QueryParam(value = "limit", required = false, defaultValue = "10") int limit) {
            List<Map<String, Object>> out = new ArrayList<>();
            DB.forEach((id, item) -> out.add(Map.of("id", id, "item", item)));
            return out.stream().skip(skip).limit(limit).toList();
        }

        // GET /api/items/{id}  -- param name is inferred from the Java parameter name
        @Get("/{id}")
        public Item getOne(int id) {
            Item item = DB.get(id);
            if (item == null) throw HttpException.notFound("Item " + id + " not found");
            return item;
        }

        // POST /api/items  -- body bound straight into the Item record
        @Post("")
        @Status(201)
        public Item create(@Body Item item) {
            int id = DB.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
            DB.put(id, item);
            return item;
        }

        // PUT /api/items/{id}
        @Put("/{id}")
        public Item update(int id, @Body Item item) {
            if (!DB.containsKey(id)) throw HttpException.notFound("Item " + id + " not found");
            DB.put(id, item);
            return item;
        }

        // DELETE /api/items/{id}
        @Delete("/{id}")
        @Status(204)
        public void remove(int id) {
            if (DB.remove(id) == null) throw HttpException.notFound("Item " + id + " not found");
        }
    }
}
