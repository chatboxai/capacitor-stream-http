package com.chatbox.plugins.streamhttp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.getcapacitor.JSObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ResponseHeadersTest {

    @Test
    public void lowercasesNamesDropsStatusLineAndJoinsRepeatedValues() {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put(null, Collections.singletonList("HTTP/1.1 200 OK"));
        fields.put("Mcp-Session-Id", Collections.singletonList("abc"));
        fields.put("Set-Cookie", Arrays.asList("a=1", "b=2"));

        JSObject headers = ResponseHeaders.flatten(fields);

        assertEquals("abc", headers.getString("mcp-session-id"));
        assertEquals("a=1, b=2", headers.getString("set-cookie"));
        assertFalse(headers.has("null"));
        assertEquals(2, headers.length());
    }

    @Test
    public void nullFieldsProduceEmptyObject() {
        assertEquals(0, ResponseHeaders.flatten(null).length());
    }
}
