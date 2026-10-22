package com.alexs.jobagent.source.service;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class GreenhouseHttpTransportTest {
    private static final URI ENDPOINT = URI.create("https://boards-api.greenhouse.io/v1/boards/example/jobs");

    @Test
    void responseHasContentValueSemanticsAndDoesNotExposeBody() {
        byte[] original = {1, 2, 3};
        var mutableHeaders = new java.util.ArrayList<>(List.of("3"));
        var response = new GreenhouseHttpTransport.Response(429, original,
                Map.of("Retry-After", mutableHeaders));
        original[0] = 9;
        mutableHeaders.add("4");
        response.body()[1] = 9;
        var sameContent = new GreenhouseHttpTransport.Response(429, new byte[]{1, 2, 3},
                Map.of("Retry-After", List.of("3")));
        assertEquals(sameContent, response);
        assertEquals(sameContent.hashCode(), response.hashCode());
        assertEquals("3", response.firstHeader("retry-after"));
        assertFalse(response.toString().contains("Retry-After"));
        assertFalse(response.equals(new GreenhouseHttpTransport.Response(503, new byte[]{1, 2, 3},
                Map.of("Retry-After", List.of("3")))));
    }

    @Test
    void overallTimeoutCancelsOutstandingRequest() throws Exception {
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        GreenhouseHttpTransport transport = withFuture(pending, Duration.ofMillis(1));
        IOException failure = assertThrows(IOException.class, () -> transport.get(ENDPOINT));
        assertEquals("Greenhouse request timed out", failure.getMessage());
        assertInstanceOf(TimeoutException.class, failure.getCause());
        assertTrue(pending.isCancelled());
    }

    @Test
    void interruptionCancelsOutstandingRequestAndRestoresFlag() throws Exception {
        CompletableFuture<HttpResponse<byte[]>> pending = new CompletableFuture<>();
        GreenhouseHttpTransport transport = withFuture(pending, Duration.ofSeconds(9));
        try {
            Thread.currentThread().interrupt();
            IOException failure = assertThrows(IOException.class, () -> transport.get(ENDPOINT));
            assertEquals("Greenhouse request interrupted", failure.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(pending.isCancelled());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void oversizeFailureKeepsItsTypeAndOtherTransportFailuresStaySafe() throws Exception {
        var oversize = withFuture(CompletableFuture.failedFuture(
                new GreenhouseHttpTransport.ResponseTooLargeException()), Duration.ofSeconds(1));
        assertThrows(GreenhouseHttpTransport.ResponseTooLargeException.class, () -> oversize.get(ENDPOINT));
        var failed = withFuture(CompletableFuture.failedFuture(new IOException("synthetic private detail")),
                Duration.ofSeconds(1));
        IOException failure = assertThrows(IOException.class, () -> failed.get(ENDPOINT));
        assertEquals("Greenhouse transport failed", failure.getMessage());
        assertInstanceOf(IOException.class, failure.getCause());
    }

    private static GreenhouseHttpTransport withFuture(CompletableFuture<HttpResponse<byte[]>> response,
                                                       Duration timeout) {
        return new GreenhouseHttpTransport((request, handler) -> response, timeout);
    }

    @Test
    void responseSizeIsBoundedAndRedirectIsNotFollowed() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/large", exchange -> {
            byte[] bytes = new byte[4_000_001];
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "https://example.invalid/other");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            GreenhouseHttpTransport transport = new GreenhouseHttpTransport();
            assertThrows(GreenhouseHttpTransport.ResponseTooLargeException.class,
                    () -> transport.get(base.resolve("/large")));
            assertEquals(302, transport.get(base.resolve("/redirect")).status());
        } finally {
            server.stop(0);
        }
    }
}
