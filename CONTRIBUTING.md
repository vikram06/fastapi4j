# Contributing

Use JDK 21 or newer. The library and tests need no external dependencies.

```sh
sh build.sh test
sh build.sh jar
javac --release 21 -parameters -cp build/classes -d build/classes example/Main.java
```

The regression runner lives in `test/fastapi4j/FrameworkTest.java`. It tests strict
JSON parsing/conversion and real HTTP requests against ephemeral loopback ports,
including validation, routing, middleware, CORS, response framing, documentation,
concurrent requests and server lifecycle. Deliberate server-failure tests emit
server-side error logs; the final PASS line reports success.

Add regression coverage for behavioral changes. Keep the runtime dependency-free,
use Java 21 APIs, preserve existing public entry points where practical, and document
compatibility changes in CHANGELOG.md. Treat shared handler state as concurrent.
CI compiles with `--release 21 -parameters` and runs on Java 21 and 25.
