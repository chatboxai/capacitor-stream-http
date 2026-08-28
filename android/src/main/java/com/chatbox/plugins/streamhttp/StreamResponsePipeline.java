package com.chatbox.plugins.streamhttp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.util.Map;

final class StreamResponsePipeline {

    interface Cancellation {
        boolean isCancelled();
    }

    interface EventSink {
        boolean onResponse(String streamId, int status, Map<String, String> headers);

        boolean onChunk(String streamId, String chunk);

        boolean onEnd(String streamId);
    }

    private StreamResponsePipeline() {}

    static void consume(String streamId, HttpURLConnection connection, Cancellation cancellation, EventSink events) throws IOException {
        int status = connection.getResponseCode();
        if (cancellation.isCancelled()) {
            return;
        }

        if (!events.onResponse(streamId, status, HttpResponseHeaders.flatten(connection.getHeaderFields()))) {
            return;
        }

        InputStream inputStream = status < HttpURLConnection.HTTP_BAD_REQUEST ? connection.getInputStream() : connection.getErrorStream();
        if (inputStream != null && !consumeBody(streamId, inputStream, cancellation, events)) {
            return;
        }

        if (!cancellation.isCancelled()) {
            events.onEnd(streamId);
        }
    }

    private static boolean consumeBody(String streamId, InputStream inputStream, Cancellation cancellation, EventSink events)
        throws IOException {
        SSEParser parser = new SSEParser();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "utf-8"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancellation.isCancelled()) {
                    return false;
                }

                String event = parser.processLine(line);
                if (event != null && !events.onChunk(streamId, event)) {
                    return false;
                }
            }
        }

        if (cancellation.isCancelled()) {
            return false;
        }

        String lastEvent = parser.processLine("");
        if (lastEvent != null && !events.onChunk(streamId, lastEvent)) {
            return false;
        }

        String remaining = parser.flush();
        return remaining == null || remaining.isEmpty() || events.onChunk(streamId, remaining);
    }
}
