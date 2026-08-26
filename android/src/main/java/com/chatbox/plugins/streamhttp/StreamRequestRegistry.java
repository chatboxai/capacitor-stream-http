package com.chatbox.plugins.streamhttp;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

final class StreamRequestRegistry {
    private final Map<String, Request> requests = new HashMap<>();

    synchronized Request register(String streamId) {
        Request request = new Request();
        requests.put(streamId, request);
        return request;
    }

    boolean cancel(String streamId) {
        Request request;
        synchronized (this) {
            request = requests.remove(streamId);
        }
        if (request == null) {
            return false;
        }
        request.cancel();
        return true;
    }

    void complete(String streamId, Request request) {
        synchronized (this) {
            if (requests.get(streamId) == request) {
                requests.remove(streamId);
            }
        }
        request.close();
    }

    void cancelAll() {
        Request[] activeRequests;
        synchronized (this) {
            activeRequests = requests.values().toArray(new Request[0]);
            requests.clear();
        }
        for (Request request : activeRequests) {
            request.cancel();
        }
    }

    static final class Request {
        private boolean cancelled;
        private HttpURLConnection connection;
        private Thread workerThread;

        synchronized boolean registerWorker(Thread thread) {
            if (cancelled) {
                return false;
            }
            workerThread = thread;
            return true;
        }

        HttpURLConnection openConnection(URL url) throws IOException {
            synchronized (this) {
                if (cancelled) {
                    return null;
                }
            }

            HttpURLConnection openedConnection = HttpConnectionConfig.openForStreaming(url);
            synchronized (this) {
                if (cancelled) {
                    openedConnection.disconnect();
                    return null;
                }
                connection = openedConnection;
                return openedConnection;
            }
        }

        boolean connect(HttpURLConnection openedConnection) throws IOException {
            synchronized (this) {
                if (cancelled || connection != openedConnection) {
                    return false;
                }
            }

            openedConnection.connect();

            synchronized (this) {
                if (cancelled || connection != openedConnection) {
                    openedConnection.disconnect();
                    return false;
                }
                return true;
            }
        }

        synchronized boolean isCancelled() {
            return cancelled;
        }

        synchronized boolean runIfActive(Runnable action) {
            if (cancelled) {
                return false;
            }
            action.run();
            return true;
        }

        void cancel() {
            HttpURLConnection connectionToClose;
            Thread threadToInterrupt;
            synchronized (this) {
                cancelled = true;
                connectionToClose = connection;
                connection = null;
                threadToInterrupt = workerThread;
                workerThread = null;
            }
            if (connectionToClose != null) {
                connectionToClose.disconnect();
            }
            if (threadToInterrupt != null) {
                threadToInterrupt.interrupt();
            }
        }

        void close() {
            HttpURLConnection connectionToClose;
            synchronized (this) {
                cancelled = true;
                connectionToClose = connection;
                connection = null;
                workerThread = null;
            }
            if (connectionToClose != null) {
                connectionToClose.disconnect();
            }
        }
    }
}
