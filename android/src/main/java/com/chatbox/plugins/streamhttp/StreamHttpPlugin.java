package com.chatbox.plugins.streamhttp;

import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "StreamHttp")
public class StreamHttpPlugin extends Plugin {

    private static final String TAG = "StreamHttpPlugin";
    private final StreamRequestRegistry activeRequests = new StreamRequestRegistry();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PluginMethod
    public void startStream(PluginCall call) {
        String urlString = call.getString("url");
        String method = call.getString("method", "GET");
        JSObject headers = call.getObject("headers", new JSObject());
        String body = call.getString("body");

        if (urlString == null) {
            call.reject("URL is required");
            return;
        }

        int connectTimeoutMillis;
        try {
            JSObject callData = call.getData();
            connectTimeoutMillis = HttpConnectionConfig.resolveConnectTimeoutMillis(
                callData.has("connectTimeoutMillis"),
                callData.opt("connectTimeoutMillis")
            );
        } catch (IllegalArgumentException error) {
            call.reject(error.getMessage());
            return;
        }

        String streamId = UUID.randomUUID().toString();
        StreamRequestRegistry.Request request = activeRequests.register(streamId);

        executor.execute(() -> {
            try {
                if (!request.registerWorker(Thread.currentThread())) {
                    return;
                }

                URL url = new URL(urlString);
                HttpURLConnection connection = request.openConnection(url, connectTimeoutMillis);
                if (connection == null) {
                    return;
                }

                // Set request method
                connection.setRequestMethod(method);

                // Set headers
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = headers.getString(key);
                    if (value != null) {
                        connection.setRequestProperty(key, value);
                    }
                }

                // Enable streaming mode for better performance
                connection.setChunkedStreamingMode(0);

                // Write body if present
                boolean hasRequestBody = body != null && !body.isEmpty() && !method.equals("GET");
                if (hasRequestBody) {
                    connection.setDoOutput(true);
                }

                if (!request.connect(connection)) {
                    return;
                }

                if (hasRequestBody) {
                    try (OutputStream os = connection.getOutputStream()) {
                        byte[] input = body.getBytes("utf-8");
                        os.write(input, 0, input.length);
                    }
                }

                if (request.isCancelled()) {
                    return;
                }

                StreamResponsePipeline.consume(streamId, connection, request::isCancelled, eventSink(request));
            } catch (IOException e) {
                if (request.isCancelled()) {
                    return;
                }
                Log.e(TAG, "Stream error: " + e.getMessage(), e);

                // Send error event
                JSObject errorData = new JSObject();
                errorData.put("id", streamId);
                errorData.put("error", e.getMessage());
                request.runIfActive(() -> notifyListeners("error", errorData));
            } finally {
                activeRequests.complete(streamId, request);
            }
        });

        // Return stream ID immediately
        JSObject ret = new JSObject();
        ret.put("id", streamId);
        call.resolve(ret);
    }

    @PluginMethod
    public void cancelStream(PluginCall call) {
        String streamId = call.getString("id");

        if (streamId == null) {
            call.reject("Stream ID is required");
            return;
        }

        activeRequests.cancel(streamId);

        call.resolve();
    }

    StreamResponsePipeline.EventSink eventSink(StreamRequestRegistry.Request request) {
        return new StreamResponsePipeline.EventSink() {
            @Override
            public boolean onResponse(String streamId, int status, Map<String, String> headers) {
                JSObject responseHeaders = new JSObject();
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    responseHeaders.put(header.getKey(), header.getValue());
                }
                JSObject data = new JSObject();
                data.put("id", streamId);
                data.put("status", status);
                data.put("headers", responseHeaders);
                return request.runIfActive(() -> notifyListeners("response", data));
            }

            @Override
            public boolean onChunk(String streamId, String chunk) {
                JSObject data = new JSObject();
                data.put("id", streamId);
                data.put("chunk", chunk);
                return request.runIfActive(() -> notifyListeners("chunk", data));
            }

            @Override
            public boolean onEnd(String streamId) {
                JSObject data = new JSObject();
                data.put("id", streamId);
                return request.runIfActive(() -> notifyListeners("end", data));
            }
        };
    }

    @Override
    protected void handleOnDestroy() {
        activeRequests.cancelAll();
        executor.shutdown();
        super.handleOnDestroy();
    }
}
