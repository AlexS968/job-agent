package com.alexs.jobagent.source.service;

import static org.junit.jupiter.api.Assertions.*;

import com.alexs.jobagent.source.dto.SearchQueryDTO;
import com.alexs.jobagent.source.exception.SearchFailureException;
import java.net.http.HttpHeaders;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.net.URI;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TavilySearchGatewayTest {
    private static final SearchQueryDTO QUERY = new SearchQueryDTO("Java careers", "germany", "en", 10);

    @Test
    void fixedEndpointAuthorizationAndNormalizedMetadata() throws Exception {
        var calls = new AtomicInteger();
        BoundedSourceHttpTransport transport = new BoundedSourceHttpTransport((request, handler) -> {
            calls.incrementAndGet();
            assertEquals("https://api.tavily.com/search", request.uri().toString());
            assertEquals("POST", request.method());
            assertEquals("Bearer synthetic-secret", request.headers().firstValue("Authorization").orElseThrow());
            assertFalse(request.uri().toString().contains("synthetic-secret"));
            String payload = requestBody(request);
            assertTrue(payload.contains("\"search_depth\":\"basic\""));
            assertTrue(payload.contains("\"auto_parameters\":false"));
            assertTrue(payload.contains("\"include_answer\":false"));
            assertTrue(payload.contains("\"include_raw_content\":false"));
            assertTrue(payload.contains("\"include_images\":false"));
            assertTrue(payload.contains("\"include_usage\":true"));
            assertTrue(payload.contains("\"max_results\":10"));
            return CompletableFuture.completedFuture(response(200,
                    "{\"request_id\":\"req-1\",\"usage\":{\"credits\":1},\"results\":["
                    + "{\"url\":\"https://example.org/careers\",\"title\":\"Careers\",\"content\":\"Snippet\"}]}"));
        }, Duration.ofSeconds(1));
        var result = new TavilySearchGateway(transport, JsonMapper.builder().build(), () -> "synthetic-secret")
                .search(QUERY);
        assertEquals(1, calls.get());
        assertEquals("req-1", result.requestId());
        assertEquals(1, result.billedCredits());
        assertEquals("https://example.org/careers", result.results().getFirst().url());
    }

    @Test
    void quotaAndAuthenticationAreDistinctSafeFailures() {
        assertEquals(SearchFailureException.Kind.QUOTA, failureFor(432).kind());
        assertEquals(SearchFailureException.Kind.QUOTA, failureFor(433).kind());
        assertEquals(SearchFailureException.Kind.AUTHENTICATION, failureFor(401).kind());
        assertEquals(SearchFailureException.Kind.TRANSIENT, failureFor(429).kind());
        assertEquals(SearchFailureException.Kind.TRANSIENT, failureFor(503).kind());
        assertFalse(failureFor(401).getMessage().contains("synthetic-secret"));
    }

    @Test
    void missingKeyAndMalformedResponseDoNotExposePayload() {
        var transport = new BoundedSourceHttpTransport((request, handler) ->
                CompletableFuture.completedFuture(response(200, "not-json")), Duration.ofSeconds(1));
        var absent = new TavilySearchGateway(transport, JsonMapper.builder().build(), () -> null);
        assertEquals(SearchFailureException.Kind.AUTHENTICATION,
                assertThrows(SearchFailureException.class, () -> absent.search(QUERY)).kind());
        var malformed = new TavilySearchGateway(transport, JsonMapper.builder().build(), () -> "synthetic-secret");
        assertEquals(SearchFailureException.Kind.INVALID_RESPONSE,
                assertThrows(SearchFailureException.class, () -> malformed.search(QUERY)).kind());
    }

    @Test
    void interruptedHttpWaitIsCancellationAndCancelsRequest() {
        var pending = new CompletableFuture<HttpResponse<byte[]>>();
        var transport = new BoundedSourceHttpTransport((request, handler) -> {
            Thread.currentThread().interrupt();
            return pending;
        }, Duration.ofSeconds(1));
        var gateway = new TavilySearchGateway(transport, JsonMapper.builder().build(), () -> "synthetic-secret");
        try {
            var failure = assertThrows(SearchFailureException.class, () -> gateway.search(QUERY));
            assertEquals(SearchFailureException.Kind.CANCELLED, failure.kind());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(pending.isCancelled());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidUrlsAreDiscardedAndMissingMetadataIsUnknown() throws Exception {
        var transport = new BoundedSourceHttpTransport((request, handler) -> CompletableFuture.completedFuture(
                response(200, "{\"results\":[{\"url\":\"javascript:alert(1)\"},"
                        + "{\"url\":\"https://example.org/jobs\"}]}")), Duration.ofSeconds(1));
        var result = new TavilySearchGateway(transport, JsonMapper.builder().build(), () -> "synthetic-secret")
                .search(QUERY);
        assertEquals(1, result.results().size());
        assertEquals("https://example.org/jobs", result.results().getFirst().url());
        assertEquals(2, result.results().getFirst().rank());
        assertNull(result.results().getFirst().title());
        assertNull(result.billedCredits());
        assertEquals("req-1", result.requestId());
    }

    @Test
    void candidateUrlValidationKeepsPathAndQueryButRejectsUnsafeForms() {
        assertEquals("https://example.org/jobs/a?ref=search", SearchCandidateUrl.normalize(
                "https://example.org/jobs/a?ref=search"));
        assertNull(SearchCandidateUrl.normalize("http://example.org/jobs"));
        assertNull(SearchCandidateUrl.normalize("https://user:pass@example.org/jobs"));
        assertNull(SearchCandidateUrl.normalize("javascript:alert(1)"));
        assertNull(SearchCandidateUrl.normalize("not a url"));
    }

    private SearchFailureException failureFor(int status) {
        var transport = new BoundedSourceHttpTransport((request, handler) ->
                CompletableFuture.completedFuture(response(status, "synthetic-secret")), Duration.ofSeconds(1));
        var gateway = new TavilySearchGateway(transport, JsonMapper.builder().build(), () -> "synthetic-secret");
        return assertThrows(SearchFailureException.class, () -> gateway.search(QUERY));
    }

    private static String requestBody(HttpRequest request) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                bytes.writeBytes(chunk);
            }
            @Override public void onError(Throwable throwable) { fail(throwable); }
            @Override public void onComplete() {
                // The publisher has no completion value; onNext already captured its bytes.
            }
        });
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static HttpResponse<byte[]> response(int status, String body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return null; }
            @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() {
                return HttpHeaders.of(Map.of("X-Request-Id", List.of("req-1")), (name, value) -> true);
            }
            @Override public byte[] body() { return body.getBytes(StandardCharsets.UTF_8); }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create("https://api.tavily.com/search"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
