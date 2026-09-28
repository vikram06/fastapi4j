# Changelog

## 0.2.0 (unreleased)

- Strict JSON parsing, exact numeric conversion, bounded nesting and cycle detection.
- Arrays, BigInteger/BigDecimal, Optional, Set and inherited POJO field support.
- Request body limits, safe error responses, request IDs and correct path decoding.
- Live controller context, typed repeated query/header parameters and startup checks.
- Validation through NotNull, Range and Size on parameters/model fields.
- Immutable JSON/text/HTML/bytes/redirect responses and context status/header control.
- Middleware, custom error handling, configurable CORS and optional docs endpoints.
- Static route precedence, duplicate detection, HEAD fallback, OPTIONS and 405 support.
- Configurable bind address, ephemeral ports, graceful stop and AutoCloseable lifecycle.
- Improved OpenAPI schemas, validation metadata, operation annotations and stable IDs.
- Dependency-free build/test script, Java 21/25 CI and a safer CRUD example.

### Compatibility notes

- Java 21 is the actual minimum; the previous Java 17 claim was incorrect.
- Malformed JSON is rejected with 400. Conversion/validation errors use 422.
- Invalid boolean strings, fractional integer inputs and numeric overflow no longer coerce silently.
- Decimal JSON tokens now parse as BigDecimal; large integers use BigInteger.
- Empty JSON input is an error. Null/missing primitive record components fail validation.
- Required repeated query parameters are now actually required.
- Transient POJO fields are excluded; inherited fields are included.
- Duplicate routes and missing inferred parameter names fail during registration.
- Route/configuration mutation while running is rejected.
- OpenAPI schema names are qualified; operation IDs have changed to stable method/path IDs.
- Response status overrides work on void methods; default void status remains 204.
- Internal exception messages are no longer exposed in 500 responses.
