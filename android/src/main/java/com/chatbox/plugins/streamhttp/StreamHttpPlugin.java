package com.chatbox.plugins.streamhttp;

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
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.util.Log;

@CapacitorPlugin(name = "StreamHttp")
public class StreamHttpPlugin extends Plugin {
    private static final String TAG = "StreamHttpPlugin";
    private final Map<String, HttpURLConnection> activeConnections = new HashMap<>();
    private final Map<String, Thread> activeThreads = new HashMap<>();
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

        String streamId = UUID.randomUUID().toString();
        
        executor.execute(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                
                // Store connection for potential cancellation
                synchronized (activeConnections) {
                    activeConnections.put(streamId, connection);
                    activeThreads.put(streamId, Thread.currentThread());
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

                // Set timeouts
                connection.setConnectTimeout(30000); // 30 seconds
                connection.setReadTimeout(30000); // 30 seconds
                
                // Enable streaming mode for better performance
                connection.setChunkedStreamingMode(0);
                
                // Write body if present
                if (body != null && !body.isEmpty() && !method.equals("GET")) {
                    connection.setDoOutput(true);
                    try (OutputStream os = connection.getOutputStream()) {
                        byte[] input = body.getBytes("utf-8");
                        os.write(input, 0, input.length);
                    }
                }

                // Get response code
                int responseCode = connection.getResponseCode();
                Log.d(TAG, "Response code: " + responseCode);

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
                        if (!activeConnections.containsKey(streamId)) {
                            break;
                        }
                        
                        // Process line through SSE parser
                        String event = sseParser.processLine(line);
                        if (event != null) {
                            // Complete SSE event ready, send it
                            JSObject chunkData = new JSObject();
                            chunkData.put("id", streamId);
                            chunkData.put("chunk", event);
                            notifyListeners("chunk", chunkData);
                        }
                    }
                    
                    // Process empty line at the end to flush last event
                    String lastEvent = sseParser.processLine("");
                    if (lastEvent != null) {
                        JSObject chunkData = new JSObject();
                        chunkData.put("id", streamId);
                        chunkData.put("chunk", lastEvent);
                        notifyListeners("chunk", chunkData);
                    }
                    
                    // Send any remaining data
                    String remaining = sseParser.flush();
                    if (remaining != null && !remaining.isEmpty()) {
                        JSObject chunkData = new JSObject();
                        chunkData.put("id", streamId);
                        chunkData.put("chunk", remaining);
                        notifyListeners("chunk", chunkData);
                    }
                    
                    reader.close();
                }

                // Send end event
                JSObject endData = new JSObject();
                endData.put("id", streamId);
                notifyListeners("end", endData);

            } catch (IOException e) {
                Log.e(TAG, "Stream error: " + e.getMessage(), e);
                
                // Send error event
                JSObject errorData = new JSObject();
                errorData.put("id", streamId);
                errorData.put("error", e.getMessage());
                notifyListeners("error", errorData);
            } finally {
                // Clean up
                synchronized (activeConnections) {
                    HttpURLConnection connection = activeConnections.remove(streamId);
                    if (connection != null) {
                        connection.disconnect();
                    }
                    activeThreads.remove(streamId);
                }
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

        synchronized (activeConnections) {
            // Cancel the connection
            HttpURLConnection connection = activeConnections.remove(streamId);
            if (connection != null) {
                connection.disconnect();
            }
            
            // Interrupt the thread if it's still running
            Thread thread = activeThreads.remove(streamId);
            if (thread != null) {
                thread.interrupt();
            }
        }

        call.resolve();
    }
    
    @Override
    protected void handleOnDestroy() {
        // Clean up all active connections when plugin is destroyed
        synchronized (activeConnections) {
            for (HttpURLConnection connection : activeConnections.values()) {
                connection.disconnect();
            }
            activeConnections.clear();
            
            for (Thread thread : activeThreads.values()) {
                thread.interrupt();
            }
            activeThreads.clear();
        }
        
        executor.shutdown();
        super.handleOnDestroy();
    }
}