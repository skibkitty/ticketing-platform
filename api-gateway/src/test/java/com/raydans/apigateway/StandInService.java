package com.raydans.apigateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A real HTTP server standing in for a service behind the gateway, for the
 * resilience tests: they need to see which requests arrived, and the bulkhead
 * test needs a service that is slow on demand.
 *
 * <p>Answers every path with 200, or with whatever {@link #answerWith(int)} last
 * set, so a test can make the service fail. Requests are held while {@link #hold(int)} is in
 * effect, so a test can keep calls in flight for as long as it needs to observe
 * them, and {@link #release()} lets them all finish.
 */
final class StandInService {

    private final HttpServer server;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch gate = new CountDownLatch(0);
    private volatile int status = 200;
    private volatile CountDownLatch arrivals = new CountDownLatch(0);
    private final ExecutorService executor;

    private StandInService(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    static StandInService start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // One thread per request, so a held request does not stop the next one
        // from being accepted: the bulkhead is the thing under test, not this
        // server's executor.
        ExecutorService executor = Executors.newCachedThreadPool();
        StandInService service = new StandInService(server, executor);
        server.createContext("/", service::handle);
        server.setExecutor(executor);
        server.start();
        return service;
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Paths of the requests that reached this service, in arrival order. */
    List<String> received() {
        return received;
    }

    /**
     * Holds every request from now on until {@link #release()}, and returns a
     * latch that counts down once {@code expected} of them have arrived.
     */
    CountDownLatch hold(int expected) {
        CountDownLatch arrived = new CountDownLatch(expected);
        arrivals = arrived;
        gate = new CountDownLatch(1);
        return arrived;
    }

    /** Answers every request from now on with {@code status}, until {@link #reset()}. */
    void answerWith(int status) {
        this.status = status;
    }

    void release() {
        gate.countDown();
    }

    void reset() {
        release();
        status = 200;
        received.clear();
    }

    void stop() {
        release();
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        received.add(exchange.getRequestURI().getPath());
        arrivals.countDown();
        try {
            gate.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
