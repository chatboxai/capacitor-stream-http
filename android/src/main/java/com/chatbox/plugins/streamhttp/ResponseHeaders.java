package com.chatbox.plugins.streamhttp;

import com.getcapacitor.JSObject;
import java.util.List;
import java.util.Map;

/** Flattens HttpURLConnection header fields into the shape the JS `response` event exposes. */
final class ResponseHeaders {

    private ResponseHeaders() {}

    /** Lowercases header names, drops the status line (null key) and joins repeated values with ", ". */
    static JSObject flatten(Map<String, List<String>> headerFields) {
        JSObject headers = new JSObject();
        if (headerFields == null) {
            return headers;
        }
        for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
            String name = entry.getKey();
            List<String> values = entry.getValue();
            if (name == null || values == null || values.isEmpty()) {
                continue;
            }
            headers.put(name.toLowerCase(java.util.Locale.ROOT), String.join(", ", values));
        }
        return headers;
    }
}
