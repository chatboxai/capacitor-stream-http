package com.chatbox.plugins.streamhttp;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

final class HttpConnectionConfig {

    static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 90_000;
    private static final int NO_READ_TIMEOUT_MILLIS = 0;
    private static final String INVALID_CONNECT_TIMEOUT_MESSAGE = "connectTimeoutMillis must be a non-negative 32-bit integer";

    private HttpConnectionConfig() {}

    static int resolveConnectTimeoutMillis(boolean provided, Object value) {
        if (!provided) {
            return DEFAULT_CONNECT_TIMEOUT_MILLIS;
        }
        if (!(value instanceof Integer) || ((Integer) value) < 0) {
            throw new IllegalArgumentException(INVALID_CONNECT_TIMEOUT_MESSAGE);
        }
        return (Integer) value;
    }

    static HttpURLConnection openForStreaming(URL url, int connectTimeoutMillis) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(connectTimeoutMillis);
        connection.setReadTimeout(NO_READ_TIMEOUT_MILLIS);
        return connection;
    }
}
