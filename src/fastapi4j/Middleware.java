package fastapi4j;

/** Middleware executes in registration order; it may return early or call next. */
@FunctionalInterface
public interface Middleware {
    Object handle(App.Ctx context, Next next) throws Exception;
    @FunctionalInterface interface Next { Object handle() throws Exception; }
}
