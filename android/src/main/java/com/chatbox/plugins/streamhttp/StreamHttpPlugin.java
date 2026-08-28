package com.chatbox.plugins.streamhttp;

import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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

                // Get response code
                int responseCode = connection.getResponseCode();
                Log.d(TAG, "Response code: " + responseCode);

                if (request.isCancelled()) {
                    return;
                }

                JSObject responseHeaders = new JSObject();
                for (Map.Entry<String, String> header : HttpResponseHeaders.flatten(connection.getHeaderFields()).entrySet()) {
                    responseHeaders.put(header.getKey(), header.getValue());
                }
                JSObject responseData = new JSObject();
                responseData.put("id", streamId);
                responseData.put("status", responseCode);
                responseData.put("headers", responseHeaders);
                if (!request.runIfActive(() -> notifyListeners("response", responseData))) {
                    return;
                }

                // Read response stream
                InputStream inputStream;
                if (responseCode >= 200 && responseCode < 300) {
                    inputStream = connection.getInputStream();
                } else {
                    inputStream = connection.getErrorStream();
                }

                if (inputStream != null) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "utf-8"));
                    SSEParser sseParser = new SSEParser();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        // Check if stream was cancelled
                        if (request.isCancelled()) {
                            break;
                        }

                        // Process line through SSE parser
                        String event = sseParser.processLine(line);
                        if (event != null) {
                            // Complete SSE event ready, send it
                            JSObject chunkData = new JSObject();
                            chunkData.put("id", streamId);
                            chunkData.put("chunk", event);
                            if (!request.runIfActive(() -> notifyListeners("chunk", chunkData))) {
                                break;
                            }
                        }
                    }

                    reader.close();
                    if (request.isCancelled()) {
                        return;
                    }

                    // Process empty line at the end to flush last event
                    String lastEvent = sseParser.processLine("");
                    if (lastEvent != null) {
                        JSObject chunkData = new JSObject();
                        chunkData.put("id", streamId);
                        chunkData.put("chunk", lastEvent);
                        if (!request.runIfActive(() -> notifyListeners("chunk", chunkData))) {
                            return;
                        }
                    }

                    // Send any remaining data
                    String remaining = sseParser.flush();
                    if (remaining != null && !remaining.isEmpty()) {
                        JSObject chunkData = new JSObject();
                        chunkData.put("id", streamId);
                        chunkData.put("chunk", remaining);
                        if (!request.runIfActive(() -> notifyListeners("chunk", chunkData))) {
                            return;
                        }
                    }
                }

                // Send end event
                JSObject endData = new JSObject();
                endData.put("id", streamId);
                request.runIfActive(() -> notifyListeners("end", endData));
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

    @Override
    protected void handleOnDestroy() {
        activeRequests.cancelAll();
        executor.shutdown();
        super.handleOnDestroy();
    }
}
