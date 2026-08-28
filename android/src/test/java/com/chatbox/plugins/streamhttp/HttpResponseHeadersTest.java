package com.chatbox.plugins.streamhttp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class HttpResponseHeadersTest {

    @Test
    public void flattensResponseHeadersForTheBridge() {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put(null, List.of("HTTP/1.1 429 Too Many Requests"));
        fields.put("Content-Type", List.of("application/json"));
        fields.put("X-RateLimit", Arrays.asList("10", null, "20"));
        fields.put("x-ratelimit", List.of("30"));

        Map<String, String> headers = HttpResponseHeaders.flatten(fields);

        assertEquals("application/json", headers.get("content-type"));
        assertEquals("10, 20, 30", headers.get("x-ratelimit"));
        assertFalse(headers.containsKey(null));
    }
}
