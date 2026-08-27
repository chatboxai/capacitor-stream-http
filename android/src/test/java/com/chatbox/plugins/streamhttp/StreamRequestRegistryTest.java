package com.chatbox.plugins.streamhttp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class StreamRequestRegistryTest {

    @Test
    public void resolvesConnectTimeoutOptions() {
        assertEquals(90_000, HttpConnectionConfig.resolveConnectTimeoutMillis(false, null));
        assertEquals(0, HttpConnectionConfig.resolveConnectTimeoutMillis(true, 0));
        assertEquals(120_000, HttpConnectionConfig.resolveConnectTimeoutMillis(true, 120_000));
    }

    @Test
    public void rejectsInvalidConnectTimeoutOptions() {
        assertThrows(IllegalArgumentException.class, () -> HttpConnectionConfig.resolveConnectTimeoutMillis(true, -1));
        assertThrows(IllegalArgumentException.class, () -> HttpConnectionConfig.resolveConnectTimeoutMillis(true, 1_500.5));
        assertThrows(IllegalArgumentException.class, () -> HttpConnectionConfig.resolveConnectTimeoutMillis(true, "90000"));
        assertThrows(IllegalArgumentException.class, () ->
            HttpConnectionConfig.resolveConnectTimeoutMillis(true, (long) Integer.MAX_VALUE + 1)
        );
    }

    @Test
    public void opensProductionConnectionsWithDefaultStreamingTimeouts() throws Exception {
        StreamRequestRegistry registry = new StreamRequestRegistry();
        StreamRequestRegistry.Request request = registry.register("stream");
        TestHttpURLConnection expectedConnection = new TestHttpURLConnection();

        assertTrue(request.registerWorker(Thread.currentThread()));
        HttpURLConnection connection = request.openConnection(
            urlFor(expectedConnection, new AtomicInteger()),
            HttpConnectionConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS
        );

        assertSame(expectedConnection, connection);
        assertEquals(90_000, connection.getConnectTimeout());
        assertEquals(0, connection.getReadTimeout());
        assertTrue(request.connect(connection));

        registry.complete("stream", request);
        assertTrue(expectedConnection.isDisconnected());
    }

    @Test
    public void usesCallerProvidedConnectTimeout() throws Exception {
        StreamRequestRegistry registry = new StreamRequestRegistry();
        StreamRequestRegistry.Request request = registry.register("stream");
        TestHttpURLConnection expectedConnection = new TestHttpURLConnection();

        assertTrue(request.registerWorker(Thread.currentThread()));
        HttpURLConnection connection = request.openConnection(urlFor(expectedConnection, new AtomicInteger()), 120_000);

        assertSame(expectedConnection, connection);
        assertEquals(120_000, connection.getConnectTimeout());
        assertEquals(0, connection.getReadTimeout());

        registry.complete("stream", request);
        assertTrue(expectedConnection.isDisconnected());
    }

    @Test
    public void cancelBeforeWorkerRegistrationPreventsConnectionOpen() throws Exception {
        StreamRequestRegistry registry = new StreamRequestRegistry();
        StreamRequestRegistry.Request request = registry.register("stream");
        AtomicInteger openCount = new AtomicInteger();

        assertTrue(registry.cancel("stream"));

        assertFalse(registry.cancel("stream"));
        assertFalse(request.registerWorker(Thread.currentThread()));
        assertNull(
            request.openConnection(urlFor(new TestHttpURLConnection(), openCount), HttpConnectionConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS)
        );
        assertEquals(0, openCount.get());

        registry.complete("stream", request);
        assertFalse(registry.cancel("stream"));
    }

    @Test
    public void cancelAfterOpenPreventsConnectionAttempt() throws Exception {
        StreamRequestRegistry registry = new StreamRequestRegistry();
        StreamRequestRegistry.Request request = registry.register("stream");
        IgnoringEarlyDisconnectHttpURLConnection connection = new IgnoringEarlyDisconnectHttpURLConnection();

        assertTrue(request.registerWorker(new Thread()));
        assertSame(
            connection,
            request.openConnection(urlFor(connection, new AtomicInteger()), HttpConnectionConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS)
        );

        assertTrue(registry.cancel("stream"));

        assertFalse(request.connect(connection));
        assertEquals(0, connection.getConnectCount());
        assertFalse(connection.isDisconnected());
    }

    @Test
    public void cancelledRequestSuppressesTerminalActions() {
        StreamRequestRegistry registry = new StreamRequestRegistry();
        StreamRequestRegistry.Request request = registry.register("stream");
        AtomicInteger notificationCount = new AtomicInteger();

        assertTrue(request.runIfActive(notificationCount::incrementAndGet));
        assertTrue(registry.cancel("stream"));

        assertFalse(request.runIfActive(notificationCount::incrementAndGet));
        assertEquals(1, notificationCount.get());
    }

    @Test
    public void cancelDuringConnectStopsBeforeResponseRead() throws Exception {
        StreamRequestRegistry registry = new StreamRequestRegistry();
        StreamRequestRegistry.Request request = registry.register("stream");
        BlockingConnectHttpURLConnection connection = new BlockingConnectHttpURLConnection();
        AtomicReference<Boolean> connectResult = new AtomicReference<>();
        AtomicInteger responseReadCount = new AtomicInteger();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                if (!request.registerWorker(Thread.currentThread())) {
                    throw new AssertionError("worker registration was cancelled");
                }
                HttpURLConnection openedConnection = request.openConnection(
                    urlFor(connection, new AtomicInteger()),
                    HttpConnectionConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS
                );
                if (openedConnection == null) {
                    throw new AssertionError("connection was cancelled before opening");
                }
                boolean connected = request.connect(openedConnection);
                connectResult.set(connected);
                if (connected) {
                    responseReadCount.incrementAndGet();
                }
            } catch (Throwable error) {
                workerFailure.set(error);
            } finally {
                registry.complete("stream", request);
            }
        });

        worker.start();
        assertTrue(connection.awaitConnectStarted());
        assertEquals(90_000, connection.getConnectTimeout());

        assertTrue(registry.cancel("stream"));
        assertFalse(connection.isDisconnected());
        connection.finishConnect();

        worker.join(2_000);
        assertFalse("cancelled connect should finish after the connect call returns", worker.isAlive());
        assertEquals(Boolean.FALSE, connectResult.get());
        assertEquals(0, responseReadCount.get());
        assertTrue(connection.isDisconnected());
        assertNull(workerFailure.get());
    }

    @Test
    public void cancelDisconnectsAnInfiniteRead() throws Exception {
        StreamRequestRegistry registry = new StreamRequestRegistry();
        StreamRequestRegistry.Request request = registry.register("stream");
        BlockingHttpURLConnection connection = new BlockingHttpURLConnection();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                if (!request.registerWorker(Thread.currentThread())) {
                    throw new AssertionError("worker registration was cancelled");
                }
                HttpURLConnection openedConnection = request.openConnection(
                    urlFor(connection, new AtomicInteger()),
                    HttpConnectionConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS
                );
                if (openedConnection == null) {
                    throw new AssertionError("connection was cancelled before opening");
                }
                if (!request.connect(openedConnection)) {
                    throw new AssertionError("connection was cancelled while connecting");
                }
                if (openedConnection.getReadTimeout() != 0) {
                    throw new AssertionError("read timeout was not infinite");
                }
                openedConnection.getInputStream().read();
            } catch (Throwable error) {
                workerFailure.set(error);
            } finally {
                registry.complete("stream", request);
            }
        });

        worker.start();
        assertTrue(connection.awaitReadStarted());

        assertTrue(registry.cancel("stream"));

        worker.join(2_000);
        assertFalse("cancel should stop the blocked reader", worker.isAlive());
        assertTrue(connection.isDisconnected());
        assertFalse(registry.cancel("stream"));
        assertNull(workerFailure.get());
    }

    private static URL urlFor(HttpURLConnection connection, AtomicInteger openCount) throws IOException {
        return new URL(
            null,
            "test://stream",
            new URLStreamHandler() {
                @Override
                protected URLConnection openConnection(URL ignored) {
                    openCount.incrementAndGet();
                    return connection;
                }
            }
        );
    }

    private static class TestHttpURLConnection extends HttpURLConnection {

        private final AtomicBoolean connected = new AtomicBoolean();
        private final AtomicBoolean disconnected = new AtomicBoolean();
        private final AtomicInteger connectCount = new AtomicInteger();

        private TestHttpURLConnection() throws IOException {
            super(new URL("http://localhost"));
        }

        @Override
        public void disconnect() {
            disconnected.set(true);
            connected.set(false);
        }

        boolean isDisconnected() {
            return disconnected.get();
        }

        boolean isConnected() {
            return connected.get();
        }

        int getConnectCount() {
            return connectCount.get();
        }

        void markConnected() {
            connectCount.incrementAndGet();
            connected.set(true);
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() throws IOException {
            markConnected();
        }
    }

    private static class IgnoringEarlyDisconnectHttpURLConnection extends TestHttpURLConnection {

        private IgnoringEarlyDisconnectHttpURLConnection() throws IOException {}

        @Override
        public void disconnect() {
            if (isConnected()) {
                super.disconnect();
            }
        }
    }

    private static final class BlockingConnectHttpURLConnection extends IgnoringEarlyDisconnectHttpURLConnection {

        private final CountDownLatch connectStarted = new CountDownLatch(1);
        private final CountDownLatch finishConnect = new CountDownLatch(1);

        private BlockingConnectHttpURLConnection() throws IOException {}

        @Override
        public void connect() {
            connectStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    finishConnect.await();
                    break;
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
            markConnected();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        boolean awaitConnectStarted() throws InterruptedException {
            return connectStarted.await(2, TimeUnit.SECONDS);
        }

        void finishConnect() {
            finishConnect.countDown();
        }
    }

    private static final class BlockingHttpURLConnection extends TestHttpURLConnection {

        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch disconnected = new CountDownLatch(1);

        private BlockingHttpURLConnection() throws IOException {}

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                @Override
                public int read() {
                    readStarted.countDown();
                    boolean interrupted = false;
                    while (true) {
                        try {
                            disconnected.await();
                            break;
                        } catch (InterruptedException error) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return -1;
                }
            };
        }

        @Override
        public void disconnect() {
            super.disconnect();
            disconnected.countDown();
        }

        boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(2, TimeUnit.SECONDS);
        }
    }
}
