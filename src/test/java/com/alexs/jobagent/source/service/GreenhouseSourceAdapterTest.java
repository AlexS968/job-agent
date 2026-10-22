package com.alexs.jobagent.source.service;

import static org.junit.jupiter.api.Assertions.*;

import com.alexs.jobagent.source.dto.GreenhouseBoardDTO;
import com.alexs.jobagent.source.exception.GreenhouseFetchException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GreenhouseSourceAdapterTest {
    private static final GreenhouseBoardDTO BOARD = new GreenhouseBoardDTO("example-board", "Example Board Inc");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC);
    private final JsonMapper json = new JsonMapper();

    @Test
    void officialEndpointAndFieldsArePreserved() throws Exception {
        FakeTransport transport = new FakeTransport(response(200, "{\"jobs\":[{\"id\":123,"
                + "\"title\":\"Engineer\",\"absolute_url\":\"https://boards.greenhouse.io/example-board/jobs/123\","
                + "\"updated_at\":\"2026-10-21T10:00:00Z\",\"content\":\"<p>Example</p>\","
                + "\"location\":{\"name\":\"Berlin\"},\"unknown\":{\"value\":true}}]}"));
        var result = adapter(transport).fetch(BOARD);
        assertEquals(URI.create("https://boards-api.greenhouse.io/v1/boards/example-board/jobs?content=true"),
                transport.lastUri);
        assertEquals(1, result.requests());
        assertEquals("123", result.listings().getFirst().id());
        assertEquals("<p>Example</p>", result.listings().getFirst().description());
        assertEquals("Berlin", result.listings().getFirst().location());
        assertEquals(Instant.parse("2026-10-21T10:00:00Z"), result.listings().getFirst().updatedAt());
        assertTrue(result.listings().getFirst().rawPayload().has("unknown"));
    }

    @Test
    void emptyResponseAndUnknownOptionalFieldsStayUnknown() throws Exception {
        assertTrue(adapter(new FakeTransport(response(200, "{\"jobs\":[]}"))).fetch(BOARD).listings().isEmpty());
        var listing = adapter(new FakeTransport(response(200, "{\"jobs\":[{\"id\":1,\"title\":\"Engineer\","
                + "\"absolute_url\":\"https://example.org/jobs/1\"}]}"))).fetch(BOARD).listings().getFirst();
        assertNull(listing.location());
        assertNull(listing.description());
        assertNull(listing.updatedAt());
    }

    @Test
    void originalHttpUrlIsPreservedAsDataWithoutFetchingIt() throws Exception {
        var listing = adapter(new FakeTransport(response(200, "{\"jobs\":[{\"id\":2,\"title\":\"Engineer\","
                + "\"absolute_url\":\"http://example.org/careers?gh_jid=2\"}]}")))
                .fetch(BOARD).listings().getFirst();
        assertEquals("http://example.org/careers?gh_jid=2", listing.originalUrl());
    }

    @Test
    void malformedResponseAndNotFoundDoNotRetry() {
        FakeTransport malformed = new FakeTransport(response(200, "{\"jobs\":[{\"id\":1}]}"));
        assertThrows(IOException.class, () -> adapter(malformed).fetch(BOARD));
        assertEquals(1, malformed.calls);
        FakeTransport missing = new FakeTransport(response(404, "{}"));
        assertThrows(IOException.class, () -> adapter(missing).fetch(BOARD));
        assertEquals(1, missing.calls);
    }

    @Test
    void malformedListingsFailBeforeAnyPartialBoardResult() {
        List<String> malformedBodies = List.of(
                "{}", "{\"jobs\":{}}", "{\"jobs\":[null]}",
                "{\"jobs\":[{\"id\":\"not-numeric\",\"title\":\"Engineer\","
                        + "\"absolute_url\":\"https://example.org/1\"}]}",
                "{\"jobs\":[{\"id\":1,\"title\":\"Engineer\","
                        + "\"absolute_url\":\"https://user@example.org/1\"}]}",
                "{\"jobs\":[{\"id\":1,\"title\":\"Engineer\","
                        + "\"absolute_url\":\"https://example.org/1\",\"updated_at\":\"bad-date\"}]}",
                "{\"jobs\":[{\"id\":1,\"title\":\"Engineer\","
                        + "\"absolute_url\":\"https://example.org/1\"},null]}");
        for (String body : malformedBodies) {
            FakeTransport transport = new FakeTransport(response(200, body));
            GreenhouseFetchException failure = assertThrows(GreenhouseFetchException.class,
                    () -> adapter(transport).fetch(BOARD));
            assertEquals("Malformed Greenhouse response", failure.getMessage());
            assertEquals(1, failure.attempts());
            assertEquals(1, transport.calls);
        }
    }

    @Test
    void invalidBoardCannotIssueRequest() {
        FakeTransport transport = new FakeTransport(response(200, "{\"jobs\":[]}"));
        assertThrows(IllegalArgumentException.class,
                () -> adapter(transport).fetch(new GreenhouseBoardDTO("https://evil.example/path", "Example")));
        assertEquals(0, transport.calls);
    }

    @Test
    void rateLimitAndTransportFailuresRetryWithinBudget() throws Exception {
        FakeTransport transport = new FakeTransport(response(429, "{}", Map.of("retry-after", List.of("2"))),
                new IOException("transient"), response(200, "{\"jobs\":[]}"));
        var delays = new java.util.ArrayList<Duration>();
        var result = new GreenhouseSourceAdapter(transport, json, CLOCK, delays::add).fetch(BOARD);
        assertEquals(3, result.requests());
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofMillis(400)), delays);
        assertEquals(3, transport.calls);
    }

    @Test
    void retryAfterBeyondRemainingBudgetStopsBoardAndIsVisibleInBatch() {
        FakeTransport transport = new FakeTransport(response(429, "{}", Map.of("retry-after", List.of("9"))),
                response(200, "{\"jobs\":[]}"));
        var delays = new java.util.ArrayList<Duration>();
        GreenhouseSourceAdapter source = new GreenhouseSourceAdapter(transport, json, CLOCK, delays::add);
        GreenhouseFetchException failure = assertThrows(GreenhouseFetchException.class, () -> source.fetch(BOARD));
        assertTrue(failure.getMessage().contains("retry budget"));
        assertEquals(1, failure.attempts());
        assertEquals(1, transport.calls);
        assertTrue(delays.isEmpty());
        FakeTransport batchTransport = new FakeTransport(response(429, "{}",
                Map.of("retry-after", List.of("9"))), response(200, "{\"jobs\":[]}"));
        var batch = new GreenhouseBatchService(adapter(batchTransport), null, CLOCK)
                .run("example-board|Example Board Inc");
        assertEquals(1, batch.failed());
        assertEquals(1, batch.requests());
        assertEquals(0, batch.fetched());
        assertEquals(1, batchTransport.calls);
    }

    @Test
    void futureHttpDateIsHonoredWhenWithinBudget() throws Exception {
        FakeTransport transport = new FakeTransport(response(503, "{}", Map.of("retry-after",
                List.of("Thu, 22 Oct 2026 10:00:03 GMT"))), response(200, "{\"jobs\":[]}"));
        var delays = new java.util.ArrayList<Duration>();
        assertEquals(2, new GreenhouseSourceAdapter(transport, json, CLOCK, delays::add).fetch(BOARD).requests());
        assertEquals(List.of(Duration.ofSeconds(3)), delays);
        assertEquals(2, transport.calls);
    }

    @Test
    void malformedHeaderFallsBackButExtremeDeltaAndDateCannotRetryEarly() throws Exception {
        FakeTransport malformed = new FakeTransport(response(429, "{}", Map.of("retry-after", List.of("later"))),
                response(200, "{\"jobs\":[]}"));
        var delays = new java.util.ArrayList<Duration>();
        assertEquals(2, new GreenhouseSourceAdapter(malformed, json, CLOCK, delays::add).fetch(BOARD).requests());
        assertEquals(List.of(Duration.ofMillis(200)), delays);
        assertEquals(2, malformed.calls);

        for (String value : List.of("999999999999999999999999999999999999999999",
                "Fri, 31 Dec 9999 23:59:59 GMT")) {
            FakeTransport extreme = new FakeTransport(response(429, "{}", Map.of("retry-after", List.of(value))),
                    response(200, "{\"jobs\":[]}"));
            var sleeps = new java.util.ArrayList<Duration>();
            GreenhouseFetchException failure = assertThrows(GreenhouseFetchException.class,
                    () -> new GreenhouseSourceAdapter(extreme, json, CLOCK, sleeps::add).fetch(BOARD));
            assertEquals(1, failure.attempts());
            assertEquals(1, extreme.calls);
            assertTrue(sleeps.isEmpty());
        }
    }

    @Test
    void cumulativeRetryBudgetPreventsThirdRequestAndPastDateIsImmediate() throws Exception {
        FakeTransport budget = new FakeTransport(response(429, "{}", Map.of("retry-after", List.of("3"))),
                response(503, "{}", Map.of("retry-after", List.of("2"))), response(200, "{\"jobs\":[]}"));
        var delays = new java.util.ArrayList<Duration>();
        GreenhouseFetchException failure = assertThrows(GreenhouseFetchException.class,
                () -> new GreenhouseSourceAdapter(budget, json, CLOCK, delays::add).fetch(BOARD));
        assertEquals(2, failure.attempts());
        assertEquals(2, budget.calls);
        assertEquals(List.of(Duration.ofSeconds(3)), delays);

        FakeTransport past = new FakeTransport(response(429, "{}", Map.of("retry-after",
                List.of("Thu, 22 Oct 2026 09:59:59 GMT"))), response(200, "{\"jobs\":[]}"));
        var pastDelays = new java.util.ArrayList<Duration>();
        assertEquals(2, new GreenhouseSourceAdapter(past, json, CLOCK, pastDelays::add).fetch(BOARD).requests());
        assertEquals(List.of(Duration.ZERO), pastDelays);
    }

    @Test
    void interruptedRetryStopsWithoutAnotherRequest() {
        FakeTransport transport = new FakeTransport(response(429, "{}"), response(200, "{\"jobs\":[]}"));
        try {
            GreenhouseFetchException failure = assertThrows(GreenhouseFetchException.class,
                    () -> new GreenhouseSourceAdapter(transport, json, CLOCK, delay -> {
                        throw new InterruptedException("synthetic cancellation");
                    }).fetch(BOARD));
            assertEquals(1, failure.attempts());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, transport.calls);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void transientFailuresExhaustThreeAttempts() {
        FakeTransport transport = new FakeTransport(new IOException("one"), new IOException("two"),
                new IOException("three"));
        assertThrows(IOException.class, () -> adapter(transport).fetch(BOARD));
        assertEquals(3, transport.calls);
    }

    @Test
    void serverErrorRetriesButClientErrorDoesNot() throws Exception {
        FakeTransport retryable = new FakeTransport(response(503, "{}"), response(200, "{\"jobs\":[]}"));
        assertEquals(2, adapter(retryable).fetch(BOARD).requests());
        FakeTransport clientError = new FakeTransport(response(400, "{}"));
        assertEquals(1, assertThrows(GreenhouseFetchException.class,
                () -> adapter(clientError).fetch(BOARD)).attempts());
        assertEquals(1, clientError.calls);
    }

    @Test
    void oversizedResponseIsNotRetriedAndTimeoutUsesBoundedRetryBudget() {
        FakeTransport oversized = new FakeTransport(new BoundedSourceHttpTransport.ResponseTooLargeException());
        GreenhouseFetchException rejected = assertThrows(GreenhouseFetchException.class,
                () -> adapter(oversized).fetch(BOARD));
        assertEquals(1, rejected.attempts());
        assertEquals(1, oversized.calls);

        FakeTransport timedOut = new FakeTransport(new java.net.http.HttpTimeoutException("timeout"),
                new java.net.http.HttpTimeoutException("timeout"),
                new java.net.http.HttpTimeoutException("timeout"));
        GreenhouseFetchException exhausted = assertThrows(GreenhouseFetchException.class,
                () -> adapter(timedOut).fetch(BOARD));
        assertEquals(3, exhausted.attempts());
        assertEquals(3, timedOut.calls);
    }

    private GreenhouseSourceAdapter adapter(FakeTransport transport) {
        return new GreenhouseSourceAdapter(transport, json, CLOCK, delay -> {});
    }

    private static BoundedSourceHttpTransport.Response response(int status, String body) {
        return response(status, body, Map.of());
    }

    private static BoundedSourceHttpTransport.Response response(int status, String body, Map<String, List<String>> headers) {
        return new BoundedSourceHttpTransport.Response(status, body.getBytes(StandardCharsets.UTF_8), headers);
    }

    private static final class FakeTransport extends BoundedSourceHttpTransport {
        private final Queue<Object> outcomes = new ArrayDeque<>();
        private URI lastUri;
        private int calls;

        FakeTransport(Object... outcomes) {
            this.outcomes.addAll(List.of(outcomes));
        }

        @Override
        public Response get(URI uri) throws IOException {
            calls++;
            lastUri = uri;
            Object outcome = outcomes.remove();
            if (outcome instanceof IOException failure) {
                throw failure;
            }
            return (Response) outcome;
        }
    }
}
