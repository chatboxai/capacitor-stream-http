package com.chatbox.plugins.streamhttp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class HttpResponseHeaders {

    private HttpResponseHeaders() {}

    static Map<String, String> flatten(Map<String, List<String>> headerFields) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
            String name = entry.getKey();
            List<String> values = entry.getValue();
            if (name == null || values == null || values.isEmpty()) {
                continue;
            }

            String value = joinValues(values);
            if (value == null) {
                continue;
            }

            String normalizedName = name.toLowerCase(Locale.ROOT);
            String existing = headers.get(normalizedName);
            headers.put(normalizedName, existing == null ? value : existing + ", " + value);
        }
        return headers;
    }

    private static String joinValues(List<String> values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(", ");
            }
            joined.append(value);
        }
        return joined.length() == 0 ? null : joined.toString();
    }
}
