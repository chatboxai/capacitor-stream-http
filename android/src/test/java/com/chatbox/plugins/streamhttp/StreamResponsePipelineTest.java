package com.chatbox.plugins.streamhttp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.getcapacitor.JSObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class StreamResponsePipelineTest {

    @Test
    public void streamsRedirectBodyFromInputStream() throws Exception {
        TestHttpURLConnection connection = new TestHttpURLConnection(302, body("data: redirect\n\n"), body("data: wrong-stream\n\n"));
        RecordingEvents events = new RecordingEvents();

        StreamResponsePipeline.consume("redirect-id", connection, () -> false, events);

        events.assertResponseChunkEnd("redirect-id", 302, "data: redirect\n\n");
        assertEquals(1, connection.inputStreamReadCount);
        assertEquals(0, connection.errorStreamReadCount);
    }

    @Test
    public void streamsErrorResponseBodyFromErrorStream() throws Exception {
        TestHttpURLConnection connection = new TestHttpURLConnection(429, body("data: wrong-stream\n\n"), body("data: rate-limited\n\n"));
        RecordingEvents events = new RecordingEvents();

        StreamResponsePipeline.consume("error-id", connection, () -> false, events);

        events.assertResponseChunkEnd("error-id", 429, "data: rate-limited\n\n");
        assertEquals(0, connection.inputStreamReadCount);
        assertEquals(1, connection.errorStreamReadCount);
    }

    @Test
    public void endsAfterResponseWhenErrorStreamIsAbsent() throws Exception {
        TestHttpURLConnection connection = new TestHttpURLConnection(404, body("data: wrong-stream\n\n"), null);
        RecordingEvents events = new RecordingEvents();

        StreamResponsePipeline.consume("empty-error-id", connection, () -> false, events);

        assertEquals(List.of("response", "end"), events.names);
        assertEquals(List.of("empty-error-id", "empty-error-id"), events.streamIds);
        assertEquals(List.of(404), events.statuses);
        assertEquals(0, connection.inputStreamReadCount);
        assertEquals(1, connection.errorStreamReadCount);
    }

    @Test
    public void pluginEventSinkNotifiesBridgeWithOrderedIdsAndPayloads() {
        TestPlugin plugin = new TestPlugin();
        StreamRequestRegistry registry = new StreamRequestRegistry();
        StreamRequestRegistry.Request request = registry.register("bridge-id");
        StreamResponsePipeline.EventSink events = plugin.eventSink(request);

        assertTrue(events.onResponse("bridge-id", 429, Map.of("content-type", "text/event-stream")));
        assertTrue(events.onChunk("bridge-id", "data: rate-limited\n\n"));
        assertTrue(events.onEnd("bridge-id"));

        assertEquals(List.of("response", "chunk", "end"), plugin.names);
        assertEquals(List.of("bridge-id", "bridge-id", "bridge-id"), plugin.streamIds());
        assertEquals(Integer.valueOf(429), plugin.payloads.get(0).getInteger("status"));
        assertEquals("text/event-stream", plugin.payloads.get(0).getJSObject("headers").getString("content-type"));
        assertEquals("data: rate-limited\n\n", plugin.payloads.get(1).getString("chunk"));

        registry.complete("bridge-id", request);
    }

    private static InputStream body(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class RecordingEvents implements StreamResponsePipeline.EventSink {

        private final List<String> names = new ArrayList<>();
        private final List<String> streamIds = new ArrayList<>();
        private final List<Integer> statuses = new ArrayList<>();
        private final List<String> chunks = new ArrayList<>();

        @Override
        public boolean onResponse(String streamId, int status, Map<String, String> headers) {
            names.add("response");
            streamIds.add(streamId);
            statuses.add(status);
            assertEquals("text/event-stream", headers.get("content-type"));
            return true;
        }

        @Override
        public boolean onChunk(String streamId, String chunk) {
            names.add("chunk");
            streamIds.add(streamId);
            chunks.add(chunk);
            return true;
        }

        @Override
        public boolean onEnd(String streamId) {
            names.add("end");
            streamIds.add(streamId);
            return true;
        }

        private void assertResponseChunkEnd(String streamId, int status, String chunk) {
            assertEquals(List.of("response", "chunk", "end"), names);
            assertEquals(List.of(streamId, streamId, streamId), streamIds);
            assertEquals(List.of(status), statuses);
            assertEquals(List.of(chunk), chunks);
        }
    }

    private static final class TestPlugin extends StreamHttpPlugin {

        private final List<String> names = new ArrayList<>();
        private final List<JSObject> payloads = new ArrayList<>();

        @Override
        protected void notifyListeners(String eventName, JSObject data) {
            names.add(eventName);
            payloads.add(data);
        }

        private List<String> streamIds() {
            List<String> ids = new ArrayList<>();
            for (JSObject payload : payloads) {
                ids.add(payload.getString("id"));
            }
            return ids;
        }
    }

    private static final class TestHttpURLConnection extends HttpURLConnection {

        private final int status;
        private final InputStream inputStream;
        private final InputStream errorStream;
        private int inputStreamReadCount;
        private int errorStreamReadCount;

        private TestHttpURLConnection(int status, InputStream inputStream, InputStream errorStream) throws IOException {
            super(new URL("http://localhost/stream"));
            this.status = status;
            this.inputStream = inputStream;
            this.errorStream = errorStream;
        }

        @Override
        public int getResponseCode() {
            return status;
        }

        @Override
        public Map<String, List<String>> getHeaderFields() {
            return Map.of("Content-Type", List.of("text/event-stream"));
        }

        @Override
        public InputStream getInputStream() {
            inputStreamReadCount++;
            return inputStream;
        }

        @Override
        public InputStream getErrorStream() {
            errorStreamReadCount++;
            return errorStream;
        }

        @Override
        public void disconnect() {}

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {}
    }
}
