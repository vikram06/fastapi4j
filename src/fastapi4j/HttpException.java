package fastapi4j;

/** Throw this from a route handler to return a specific status + JSON error body,
 *  just like FastAPI's HTTPException. */
public class HttpException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int statusCode;

    public HttpException(int statusCode, String detail) {
        super(detail);
        if (statusCode < 200 || statusCode > 599) throw new IllegalArgumentException("Status must be 200..599");
        this.statusCode = statusCode;
    }

    public static HttpException notFound(String detail) { return new HttpException(404, detail); }
    public static HttpException badRequest(String detail) { return new HttpException(400, detail); }
    public static HttpException unprocessable(String detail) { return new HttpException(422, detail); }
}
