package fastapi4j;

import java.util.*;

/** Opt-in CORS. Credentials require explicit origins; methods/headers are allowlisted. */
public final class Cors {
    final Set<String> origins;
    final Set<String> methods;
    final Set<String> headers;
    final boolean credentials;
    final long maxAge;
    public Cors(Set<String> origins, Set<String> methods, Set<String> headers, boolean credentials, long maxAge) {
        this.origins = Set.copyOf(origins);
        this.methods = normalized(methods, true);
        this.headers = normalized(headers, false);
        this.credentials = credentials; this.maxAge = maxAge;
        if (credentials && origins.contains("*")) throw new IllegalArgumentException("Credentialed CORS requires explicit origins");
        if (maxAge < 0) throw new IllegalArgumentException("Negative CORS max age");
        for (String origin : origins) if (origin.contains("\r") || origin.contains("\n")) throw new IllegalArgumentException("Invalid origin");
    }
    public static Cors allowOrigins(String... origins) {
        return new Cors(Set.of(origins), Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"), Set.of("content-type", "authorization"), false, 600);
    }
    private static Set<String> normalized(Set<String> values, boolean upper) {
        Set<String> out = new TreeSet<>();
        for (String s : values) {
            if (!s.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) throw new IllegalArgumentException("Invalid CORS token");
            out.add(upper ? s.toUpperCase(Locale.ROOT) : s.toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(out);
    }
}
