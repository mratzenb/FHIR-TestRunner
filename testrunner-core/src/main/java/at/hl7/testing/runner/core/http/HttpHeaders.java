package at.hl7.testing.runner.core.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** HTTP header fields: case-insensitive names, repeated fields kept apart, order kept. Immutable. */
public final class HttpHeaders {

    private final Map<String, List<String>> fields;

    private HttpHeaders(Map<String, List<String>> fields) {
        this.fields = fields;
    }

    public static HttpHeaders empty() {
        return new HttpHeaders(Map.of());
    }

    public static HttpHeaders of(Map<String, List<String>> fields) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        fields.forEach((name, values) -> {
            if (name != null && !name.startsWith(":")) { // skip HTTP/2 pseudo headers
                copy.computeIfAbsent(name, n -> new ArrayList<>()).addAll(values);
            }
        });
        copy.replaceAll((name, values) -> List.copyOf(values));
        return new HttpHeaders(Map.copyOf(copy));
    }

    /** The first value of a field, compared case-insensitively. */
    public Optional<String> first(String name) {
        return all(name).stream().findFirst();
    }

    /** All values of a field, compared case-insensitively. */
    public List<String> all(String name) {
        List<String> values = new ArrayList<>();
        fields.forEach((field, v) -> {
            if (field.equalsIgnoreCase(name)) {
                values.addAll(v);
            }
        });
        return values;
    }

    /** All fields in the order received. */
    public Map<String, List<String>> asMap() {
        return fields;
    }

    /** A copy with {@code name} set to {@code value}, replacing other values of that field. */
    public HttpHeaders with(String name, String value) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        fields.forEach((field, v) -> {
            if (!field.equalsIgnoreCase(name)) {
                copy.put(field, v);
            }
        });
        copy.put(name, List.of(value));
        return of(copy);
    }

    public boolean contains(String name) {
        return fields.keySet().stream().anyMatch(field -> field.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)));
    }
}
