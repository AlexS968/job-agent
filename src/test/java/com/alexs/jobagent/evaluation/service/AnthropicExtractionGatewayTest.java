package com.alexs.jobagent.evaluation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alexs.jobagent.evaluation.dto.ExtractionInputDTO;
import com.alexs.jobagent.evaluation.exception.ExtractionFailureException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AnthropicExtractionGatewayTest {
    private final JsonMapper json = new JsonMapper();
    private final ExtractionInputDTO input = new ExtractionInputDTO(1, 2, "a".repeat(64),
            Map.of("title", "Backend Engineer"));

    @Test
    void successfulStructuredResponseUsesPinnedSchemaAndBoundedUsage() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (LocalServer server = new LocalServer(200, """
                {"stop_reason":"end_turn","content":[{"type":"text","text":"{\\"claims\\":[]}"}],
                "usage":{"input_tokens":100,"output_tokens":20}}
                """, requests)) {
            var response = gateway(server).extract(input, ExtractionPolicy.PRIMARY_MODEL, "synthetic-key");
            assertEquals(100, response.inputTokens());
            assertEquals("{\"claims\":[]}", response.content());
            assertEquals(1, requests.get());
            JsonNode request = json.readTree(server.requestBody());
            assertEquals(ExtractionPolicy.PRIMARY_MODEL, request.path("model").asText());
            assertEquals("json_schema", request.path("output_config").path("format").path("type").asText());
            assertEquals("disabled", request.path("thinking").path("type").asText());
        }
    }

    @Test
    void authRateLimitAndServerErrorsExposeOnlySafeStatus() throws Exception {
        for (int code : new int[] {401, 429, 529}) {
            try (LocalServer server = new LocalServer(code, "secret provider body", new AtomicInteger())) {
                AnthropicExtractionGateway client = gateway(server);
                ExtractionFailureException failure = assertThrows(ExtractionFailureException.class,
                        () -> client.extract(input, ExtractionPolicy.PRIMARY_MODEL, "synthetic-key"));
                assertFalse(failure.getMessage().contains("secret"));
                assertEquals(code == 401 ? "AUTH" : "UNCERTAIN", failure.outcome());
            }
        }
    }

    @Test
    void refusalTruncationAndMalformedProviderBodiesFailClosed() throws Exception {
        for (String response : new String[] {
                "{\"stop_reason\":\"refusal\",\"content\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
                "{\"stop_reason\":\"max_tokens\",\"content\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}",
                "not-json"}) {
            try (LocalServer server = new LocalServer(200, response, new AtomicInteger())) {
                AnthropicExtractionGateway client = gateway(server);
                assertEquals("INVALID", assertThrows(ExtractionFailureException.class,
                        () -> client.extract(input, ExtractionPolicy.PRIMARY_MODEL, "synthetic-key"))
                        .outcome());
            }
        }
    }

    @Test
    void oversizedInputIsRejectedBeforeSubmissionAndOversizedResponseIsUncertain() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (LocalServer server = new LocalServer(200, "x".repeat(100_001), requests)) {
            var gateway = gateway(server);
            var oversized = new ExtractionInputDTO(1, 2, "a".repeat(64),
                    Map.of("description", "x".repeat(12_000)));
            assertEquals("INVALID", assertThrows(ExtractionFailureException.class,
                    () -> gateway.extract(oversized, ExtractionPolicy.PRIMARY_MODEL, "synthetic-key"))
                    .outcome());
            assertEquals(0, requests.get());
            assertEquals("UNCERTAIN", assertThrows(ExtractionFailureException.class,
                    () -> gateway.extract(input, ExtractionPolicy.PRIMARY_MODEL, "synthetic-key"))
                    .outcome());
            assertEquals(1, requests.get());
        }
    }

    @Test
    void cacheUsageIsReturnedForConservativeAccounting() throws Exception {
        try (LocalServer server = new LocalServer(200, """
                {"stop_reason":"end_turn","content":[{"type":"text","text":"{\\"claims\\":[]}"}],
                "usage":{"input_tokens":100,"cache_creation_input_tokens":20,
                "cache_read_input_tokens":30,"output_tokens":40}}
                """, new AtomicInteger())) {
            var response = gateway(server).extract(input, ExtractionPolicy.PRIMARY_MODEL, "synthetic-key");
            assertEquals(20, response.cacheCreationTokens());
            assertEquals(30, response.cacheReadTokens());
            assertEquals(40, response.outputTokens());
        }
    }

    @Test
    void malformedUsageAndContentAreRejectedWithoutInventingBillableTokens() throws Exception {
        for (String response : new String[] {
                """
                {"stop_reason":"end_turn","content":[{"type":"text","text":"{}"}],
                "usage":{"input_tokens":-1,"output_tokens":20}}
                """,
                """
                {"stop_reason":"end_turn","content":[{"type":"text","text":"{}"}],
                "usage":{"input_tokens":100,"cache_creation_input_tokens":-2,"output_tokens":20}}
                """,
                """
                {"stop_reason":"end_turn","content":[],
                "usage":{"input_tokens":100,"output_tokens":20}}
                """}) {
            try (LocalServer server = new LocalServer(200, response, new AtomicInteger())) {
                AnthropicExtractionGateway client = gateway(server);
                ExtractionFailureException failure = assertThrows(ExtractionFailureException.class,
                        () -> client.extract(input, ExtractionPolicy.PRIMARY_MODEL, "synthetic-key"));
                assertEquals("INVALID", failure.outcome());
            }
        }
    }

    @Test
    void preInterruptedGatewayPreservesFlagWithoutSubmittingRequest() {
        HttpClient client = mock(HttpClient.class);
        var gateway = new AnthropicExtractionGateway(client,
                URI.create("http://127.0.0.1:1/v1/messages"), json);
        Thread.currentThread().interrupt();
        try {
            ExtractionFailureException failure = assertThrows(ExtractionFailureException.class,
                    () -> gateway.extract(input, ExtractionPolicy.PRIMARY_MODEL, "synthetic-key"));
            assertEquals("CANCELLED", failure.outcome());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        verifyNoInteractions(client);
    }

    @Test
    void timeoutAndInterruptionDoNotRetryAndPreserveInterrupt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newFixedThreadPool(2);
        server.setExecutor(executor);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/v1/messages", exchange -> {
            requests.incrementAndGet();
            entered.countDown();
            bothEntered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages");
            var timeoutGateway = new AnthropicExtractionGateway(client, endpoint, json, Duration.ofMillis(100));
            assertEquals("UNCERTAIN", assertThrows(ExtractionFailureException.class,
                    () -> timeoutGateway.extract(input, ExtractionPolicy.PRIMARY_MODEL, "synthetic-key"))
                    .outcome());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            AtomicBoolean interrupted = new AtomicBoolean();
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            var cancellable = new AnthropicExtractionGateway(client, endpoint, json, Duration.ofSeconds(2));
            Thread worker = new Thread(() -> {
                try {
                    ExtractionFailureException failure = assertThrows(ExtractionFailureException.class,
                            () -> cancellable.extract(input, ExtractionPolicy.PRIMARY_MODEL, "synthetic-key"));
                    assertEquals("CANCELLED", failure.outcome());
                    interrupted.set(Thread.currentThread().isInterrupted());
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                }
            });
            worker.start();
            assertTrue(bothEntered.await(2, TimeUnit.SECONDS));
            assertEquals(2, requests.get());
            worker.interrupt();
            worker.join(2_000);
            assertFalse(worker.isAlive());
            assertNull(workerFailure.get());
            assertTrue(interrupted.get());
            assertEquals(2, requests.get());
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private AnthropicExtractionGateway gateway(LocalServer server) {
        return new AnthropicExtractionGateway(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                URI.create("http://127.0.0.1:" + server.port() + "/v1/messages"), json);
    }

    private static final class LocalServer implements AutoCloseable {
        private final HttpServer server;
        private volatile String requestBody;

        LocalServer(int status, String response, AtomicInteger requests) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/messages", exchange -> {
                requests.incrementAndGet();
                requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(bytes);
                }
            });
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        String requestBody() {
            return requestBody;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
