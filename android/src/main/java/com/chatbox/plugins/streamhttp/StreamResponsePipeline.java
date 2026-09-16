package com.chatbox.plugins.streamhttp;

import com.getcapacitor.JSObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;

final class StreamResponsePipeline {

    interface Cancellation {
        boolean isCancelled();
    }

    interface EventSink {
        boolean onResponse(String streamId, int status, JSObject headers);

        boolean onChunk(String streamId, String chunk);

        boolean onEnd(String streamId);
    }

    private StreamResponsePipeline() {}

    static void consume(String streamId, HttpURLConnection connection, Cancellation cancellation, EventSink events) throws IOException {
        consume(streamId, connection, cancellation, events, false);
    }

    static void consume(String streamId, HttpURLConnection connection, Cancellation cancellation, EventSink events, boolean rejectRedirects)
        throws IOException {
        int status = connection.getResponseCode();
        if (rejectRedirects && (status == 301 || status == 302 || status == 303 || status == 307 || status == 308)) {
            throw new IOException("HTTP redirect rejected; use the final server URL");
        }
        if (cancellation.isCancelled()) {
            return;
        }

        if (!events.onResponse(streamId, status, ResponseHeaders.flatten(connection.getHeaderFields()))) {
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
        try (InputStreamReader reader = new InputStreamReader(inputStream, "utf-8")) {
            char[] buffer = new char[4096];
            char pending = 0;
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (cancellation.isCancelled()) return false;
                String chunk = (pending == 0 ? "" : String.valueOf(pending)) + new String(buffer, 0, count);
                pending = 0;
                if (!chunk.isEmpty() && Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))) {
                    pending = chunk.charAt(chunk.length() - 1);
                    chunk = chunk.substring(0, chunk.length() - 1);
                }
                if (!chunk.isEmpty() && !events.onChunk(streamId, chunk)) return false;
            }
            if (cancellation.isCancelled()) return false;
            return pending == 0 || events.onChunk(streamId, "\uFFFD");
        }
    }
}
