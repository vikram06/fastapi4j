package fastapi4j;

/** Throw this from a route handler to return a specific status + JSON error body,
 *  just like FastAPI's HTTPException. */
public class HttpException extends RuntimeException {
    public final int statusCode;

    public HttpException(int statusCode, String detail) {
        super(detail);
        this.statusCode = statusCode;
    }

    public static HttpException notFound(String detail) { return new HttpException(404, detail); }
    public static HttpException badRequest(String detail) { return new HttpException(400, detail); }
    public static HttpException unprocessable(String detail) { return new HttpException(422, detail); }
}
