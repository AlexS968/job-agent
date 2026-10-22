package com.alexs.jobagent.source.service;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;

class GreenhouseHttpTransportTest {
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
