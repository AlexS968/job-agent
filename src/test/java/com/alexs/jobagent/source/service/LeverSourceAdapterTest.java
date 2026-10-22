package com.alexs.jobagent.source.service;

import static org.junit.jupiter.api.Assertions.*;

import com.alexs.jobagent.source.dto.LeverSiteDTO;
import com.alexs.jobagent.source.exception.LeverFetchException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LeverSourceAdapterTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC);
    private static final LeverSiteDTO GLOBAL = new LeverSiteDTO("global", "example", "Example Inc");
    private final JsonMapper json = new JsonMapper();

    @Test
    void globalAndEuUseRegionalApiAndPreserveUnknownFields() throws Exception {
        FakeTransport global = new FakeTransport(response(200, "[" + listing("1", "Engineer") + "]"));
        var result = adapter(global).fetch(GLOBAL);
        assertEquals("api.lever.co", global.lastUri.getHost());
        assertEquals("mode=json&skip=0&limit=50", global.lastUri.getQuery());
        assertEquals(1, result.requests());
        assertEquals(CLOCK.instant(), result.observationStartedAt());
        assertEquals("https://jobs.lever.co/example/1/apply", result.listings().getFirst().applyUrl());
        assertTrue(result.listings().getFirst().rawPayload().has("extra"));

        FakeTransport eu = new FakeTransport(response(200, "[]"));
        adapter(eu).fetch(new LeverSiteDTO("eu", "example", "Example EU"));
        assertEquals("api.eu.lever.co", eu.lastUri.getHost());
    }

    @Test
    void fullPageRequiresAnotherPageAndRepeatedIdFailsWholeFetch() {
        String full = fullPage();
        FakeTransport pages = new FakeTransport(response(200, full), response(200, "[]"));
        assertEquals(50, assertDoesNotThrow(() -> adapter(pages).fetch(GLOBAL)).listings().size());
        assertEquals(2, pages.calls);
        assertTrue(pages.lastUri.getQuery().contains("skip=50"));

        FakeTransport duplicate = new FakeTransport(response(200, full),
                response(200, "[" + listing("0", "Engineer") + "]"));
        assertEquals(2, assertThrows(LeverFetchException.class,
                () -> adapter(duplicate).fetch(GLOBAL)).requests());

        FakeTransport limit = new FakeTransport(response(200, fullPage(0)), response(200, fullPage(50)),
                response(200, fullPage(100)), response(200, fullPage(150)));
        assertEquals(4, assertThrows(LeverFetchException.class,
                () -> adapter(limit).fetch(GLOBAL)).requests());
        assertEquals(4, limit.calls);
    }

    @Test
    void malformedFieldsAndClientErrorsAreNotRetried() {
        for (String body : List.of("{}", "[null]", "[{\"id\":\"1\"}]",
                "[" + listing("1", "Engineer").replace("jobs.lever.co", "evil.example") + "]")) {
            FakeTransport transport = new FakeTransport(response(200, body));
            assertEquals(1, assertThrows(LeverFetchException.class,
                    () -> adapter(transport).fetch(GLOBAL)).requests());
            assertEquals(1, transport.calls);
        }
        FakeTransport missing = new FakeTransport(response(404, "[]"));
        assertEquals(1, assertThrows(LeverFetchException.class,
                () -> adapter(missing).fetch(GLOBAL)).requests());
        assertEquals(1, missing.calls);
        FakeTransport invalid = new FakeTransport(response(200, "[]"));
        assertThrows(IllegalArgumentException.class,
                () -> adapter(invalid).fetch(new LeverSiteDTO("global", "https://evil.example", "Bad")));
        assertEquals(0, invalid.calls);
    }

    @Test
    void retryAfterRespectsBudgetAndHttpDate() throws Exception {
        FakeTransport shortDelay = new FakeTransport(response(429, "[]", "2"), response(200, "[]"));
        List<Duration> sleeps = new ArrayList<>();
        assertEquals(2, new LeverSourceAdapter(shortDelay, json, CLOCK, sleeps::add).fetch(GLOBAL).requests());
        assertEquals(List.of(Duration.ofSeconds(2)), sleeps);

        FakeTransport longDelay = new FakeTransport(response(429, "[]", "9"), response(200, "[]"));
        assertEquals(1, assertThrows(LeverFetchException.class,
                () -> adapter(longDelay).fetch(GLOBAL)).requests());
        assertEquals(1, longDelay.calls);

        FakeTransport date = new FakeTransport(response(503, "[]", "Thu, 22 Oct 2026 10:00:03 GMT"),
                response(200, "[]"));
        sleeps.clear();
        assertEquals(2, new LeverSourceAdapter(date, json, CLOCK, sleeps::add).fetch(GLOBAL).requests());
        assertEquals(List.of(Duration.ofSeconds(3)), sleeps);
    }

    @Test
    void malformedHeaderFallsBackAndExtremeValueDoesNotRetry() throws Exception {
        FakeTransport malformed = new FakeTransport(response(429, "[]", "later"), response(200, "[]"));
        List<Duration> sleeps = new ArrayList<>();
        adapter(malformed, sleeps).fetch(GLOBAL);
        assertEquals(List.of(Duration.ofMillis(200)), sleeps);
        for (String value : List.of("9999999999999999999999999999999999",
                "Fri, 31 Dec 9999 23:59:59 GMT")) {
            FakeTransport extreme = new FakeTransport(response(429, "[]", value), response(200, "[]"));
            assertEquals(1, assertThrows(LeverFetchException.class,
                    () -> adapter(extreme).fetch(GLOBAL)).requests());
            assertEquals(1, extreme.calls);
        }
    }

    @Test
    void transportFailureIsBoundedAndVisibleInBatch() {
        FakeTransport transport = new FakeTransport(new IOException("offline"), new IOException("offline"),
                new IOException("offline"));
        var batch = new LeverBatchService(adapter(transport), null).run("global|example|Example Inc");
        assertEquals(3, batch.requests());
        assertEquals(1, batch.failed());
        assertEquals(0, batch.fetched());
        assertEquals(3, transport.calls);
        assertThrows(IllegalArgumentException.class, () -> new LeverBatchService(adapter(transport), null)
                .run("global|example|Example,global|example|Duplicate"));
    }

    private LeverSourceAdapter adapter(FakeTransport transport) {
        return adapter(transport, new ArrayList<>());
    }

    private LeverSourceAdapter adapter(FakeTransport transport, List<Duration> sleeps) {
        return new LeverSourceAdapter(transport, json, CLOCK, sleeps::add);
    }

    private static String listing(String id, String title) {
        return "{\"id\":\"" + id + "\",\"text\":\"" + title + "\",\"hostedUrl\":\""
                + "https://jobs.lever.co/example/" + id + "\",\"applyUrl\":\""
                + "https://jobs.lever.co/example/" + id + "/apply\",\"extra\":{\"key\":1}}";
    }

    private static String fullPage() {
        return fullPage(0);
    }

    private static String fullPage(int start) {
        List<String> postings = new ArrayList<>();
        for (int i = start; i < start + 50; i++) {
            postings.add(listing(Integer.toString(i), "Engineer"));
        }
        return "[" + String.join(",", postings) + "]";
    }

    private static BoundedSourceHttpTransport.Response response(int status, String body) {
        return response(status, body, null);
    }

    private static BoundedSourceHttpTransport.Response response(int status, String body, String retryAfter) {
        Map<String, List<String>> headers = retryAfter == null ? Map.of()
                : Map.of("retry-after", List.of(retryAfter));
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
