package com.chatbox.plugins.streamhttp;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

final class HttpConnectionConfig {
    private static final int CONNECT_TIMEOUT_MILLIS = 30_000;
    private static final int NO_READ_TIMEOUT_MILLIS = 0;

    private HttpConnectionConfig() {}

    static HttpURLConnection openForStreaming(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(NO_READ_TIMEOUT_MILLIS);
        return connection;
    }
}
