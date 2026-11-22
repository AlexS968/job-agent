package com.alexs.jobagent.source.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.alexs.jobagent.source.exception.InvalidGmailPageTokenException;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class GoogleGmailReadClientTest {
    private static final String CONFIG = "{\"client_id\":\"synthetic-client\","
            + "\"client_secret\":\"synthetic-secret\",\"refresh_token\":\"synthetic-refresh\"}";

    @Test
    void malformedResponseDoesNotExposeResponseFragments() {
        var client = new GoogleGmailReadClient(new JsonMapper());
        var failure = assertThrows(IllegalStateException.class,
                () -> client.parseResponse("not-json synthetic-secret".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Gmail response was invalid", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void refreshesOnceAndReadsBoundedArchivedAlertsWithExactSenderQuery() {
        List<HttpRequest> requests = new ArrayList<>();
        var client = scripted(CONFIG, requests, response(200, "{\"access_token\":\"synthetic-access\"}"),
                response(200, "{\"messages\":[{\"id\":\"mail-1\"},{\"id\":\"mail-2\"},"
                        + "{\"id\":\"mail-3\"}]}"),
                response(200, "{\"id\":\"mail-1\"}"), response(200, "{}"));
        assertEquals(List.of("mail-1", "mail-2"), client.alertIds(2,
                LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 18)));
        assertEquals("mail-1", client.alert("mail-1").path("id").asText());
        assertEquals(List.of(), client.alertIds(1, null, null));
        assertEquals(4, requests.size());
        assertEquals("POST", requests.getFirst().method());
        assertEquals("oauth2.googleapis.com", requests.getFirst().uri().getHost());
        String search = java.net.URLDecoder.decode(requests.get(1).uri().getRawQuery(), StandardCharsets.UTF_8);
        assertTrue(search.contains("in:anywhere from:jobalerts-noreply@linkedin.com"));
        assertTrue(search.contains("after:2026/11/09 before:2026/11/18"));
        assertTrue(search.contains("maxResults=2"));
        assertEquals("Bearer synthetic-access", requests.get(1).headers().firstValue("Authorization").orElseThrow());
        assertTrue(requests.get(2).uri().toString().endsWith("/mail-1?format=full"));
        assertFalse(requests.get(1).uri().toString().contains("synthetic-secret"));
        assertEquals("GET", requests.get(3).method());
    }

    @Test
    void invalidConfigurationAndIdentifiersFailBeforeHttp() {
        for (String configuration : List.of("{", "{\"client_id\":\"synthetic\"}")) {
            HttpClient http = mock(HttpClient.class);
            var client = new GoogleGmailReadClient(http, new JsonMapper(), () -> configuration);
            assertThrows(IllegalStateException.class, () -> client.alertIds(1, null, null));
            verifyNoInteractions(http);
        }
        HttpClient http = mock(HttpClient.class);
        var missing = new GoogleGmailReadClient(http, new JsonMapper(), () -> null);
        assertEquals("Private Gmail OAuth configuration is unavailable", assertThrows(IllegalStateException.class,
                () -> missing.alertIds(1, null, null)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> missing.alertIds(0, null, null));
        assertThrows(IllegalArgumentException.class, () -> missing.alertIds(21, null, null));
        assertThrows(IllegalArgumentException.class, () -> missing.alert(null));
        assertThrows(IllegalArgumentException.class, () -> missing.alert("../other"));
        verifyNoInteractions(http);
    }

    @Test
    void invalidListMetadataAndMissingOauthTokenAreSafeFailures() {
        var missingToken = scripted(CONFIG, new ArrayList<>(), response(200, "{}"));
        assertEquals("Gmail OAuth refresh returned no token", assertThrows(IllegalStateException.class,
                () -> missingToken.alertIds(1, null, null)).getMessage());
        var invalidId = scripted(CONFIG, new ArrayList<>(), response(200, "{\"access_token\":\"synthetic\"}"),
                response(200, "{\"messages\":[{\"id\":\"../private\"}]}"));
        assertEquals("Gmail returned invalid message metadata", assertThrows(IllegalStateException.class,
                () -> invalidId.alertIds(1, null, null)).getMessage());
    }

    @Test
    void responseLimitAndHttpFailureCloseBodiesWithoutLeakingThem() {
        Reply tooLarge = response(200, new byte[2_000_001]);
        var oversized = scripted(CONFIG, new ArrayList<>(), response(200, "{\"access_token\":\"synthetic\"}"),
                tooLarge);
        assertEquals("Gmail response exceeded the byte limit", assertThrows(IllegalStateException.class,
                () -> oversized.alertIds(1, null, null)).getMessage());

        Reply rejected = response(429, "synthetic private body");
        var httpError = scripted(CONFIG, new ArrayList<>(), response(200, "{\"access_token\":\"synthetic\"}"),
                rejected);
        assertEquals("Gmail request failed with HTTP 429", assertThrows(IllegalStateException.class,
                () -> httpError.alertIds(1, null, null)).getMessage());
    }

    @Test
    void transportFailureAndInterruptionKeepSafeDiagnostics() throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.failedFuture(new IOException("synthetic private detail")));
        var client = new GoogleGmailReadClient(http, new JsonMapper(), () -> CONFIG);
        assertEquals("Gmail request failed", assertThrows(IllegalStateException.class,
                () -> client.alertIds(1, null, null)).getMessage());

        HttpClient interruptedHttp = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        CompletableFuture<HttpResponse<byte[]>> call = mock(CompletableFuture.class);
        when(call.get(20_000, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException("synthetic private detail"));
        when(interruptedHttp.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(call);
        var interrupted = new GoogleGmailReadClient(interruptedHttp, new JsonMapper(), () -> CONFIG);
        try {
            assertEquals("Gmail request interrupted", assertThrows(IllegalStateException.class,
                    () -> interrupted.alertIds(1, null, null)).getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(call).cancel(true);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cutoverContinuesSearchAndUsesOnlyTheExistingProcessedLabel() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        var client = scripted(CONFIG, requests, response(200, "{\"access_token\":\"synthetic\"}"),
                response(200, "{\"messages\":[],\"nextPageToken\":\"following\"}"),
                response(200, "{\"labels\":[{\"name\":\"LinkedIn Processed\",\"type\":\"user\",\"id\":\"Label_1\"}]}"),
                response(200, "{\"id\":\"mail-1\"}"));
        assertEquals("following", client.livePage(100, 200, "cursor/+").nextToken());
        assertTrue(requests.get(1).uri().getRawQuery().contains("pageToken=cursor%2F%2B"));
        client.applyLabels("mail-1", client.processedLabel());
        var request = requests.get(3);
        assertEquals("POST", request.method());
        assertTrue(request.uri().getPath().endsWith("/messages/mail-1/modify"));
        var bytes = new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(java.nio.ByteBuffer buffer) {
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            public void onComplete() {
                // onNext already collected all bytes from the request body.
            }
            public void onError(Throwable error) { throw new AssertionError(error); }
        });
        var mapper = new JsonMapper();
        assertEquals(mapper.readTree("{\"addLabelIds\":[\"Label_1\"],\"removeLabelIds\":[\"UNREAD\",\"INBOX\"]}"),
                mapper.readTree(bytes.toByteArray()));
    }

    @Test
    void onlyExplicitContinuationRejectionIsRecoverable() {
        var client = scripted(CONFIG, new ArrayList<>(), response(200, "{\"access_token\":\"synthetic\"}"),
                response(400, "{\"error\":{\"errors\":[{\"reason\":\"invalid\",\"location\":\"pageToken\"}]}}"),
                response(400, "{\"error\":{\"message\":\"Invalid argument\"}}"),
                response(429, "{\"error\":{\"message\":\"Invalid page token\"}}"));
        assertThrows(InvalidGmailPageTokenException.class, () -> client.livePage(100, 200, "stale"));
        assertEquals("Gmail request failed with HTTP 400",
                assertThrows(IllegalStateException.class, () -> client.livePage(100, 200, "next")).getMessage());
        assertEquals("Gmail request failed with HTTP 429",
                assertThrows(IllegalStateException.class, () -> client.livePage(100, 200, "next")).getMessage());
    }

    @Test
    void malformedListMetadataCannotBeMistakenForAnEmptyOrFinishedWindow() {
        var client = scripted(CONFIG, new ArrayList<>(), response(200, "{\"access_token\":\"synthetic\"}"),
                response(200, "[]"), response(200, "{\"messages\":{}}"),
                response(200, "{\"nextPageToken\":123}"), response(200, "{\"nextPageToken\":\" \"}"),
                response(200, "{\"nextPageToken\":\"" + "x".repeat(4097) + "\"}"));
        assertEquals("Invalid Gmail list response",
                assertThrows(IllegalStateException.class, () -> client.livePage(100, 200, null)).getMessage());
        assertEquals("Gmail returned invalid message metadata",
                assertThrows(IllegalStateException.class, () -> client.livePage(100, 200, null)).getMessage());
        for (int i = 0; i < 3; i++) {
            assertEquals("Invalid Gmail pagination metadata",
                    assertThrows(IllegalStateException.class, () -> client.livePage(100, 200, null)).getMessage());
        }
    }

    @Test
    void rejectsAnUnusableProcessedLabelAndAnAcknowledgementForAnotherMessage() {
        List<HttpRequest> requests = new ArrayList<>();
        var client = scripted(CONFIG, requests, response(200, "{\"access_token\":\"synthetic\"}"),
                response(200, """
                        {"labels":[{"name":"Inbox","type":"system","id":"INBOX"},
                          {"name":"LinkedIn Processed","type":"system","id":"Label_1"}]}
                        """),
                response(200, """
                        {"labels":[{"name":"LinkedIn Processed","type":"user","id":"../unsafe"}]}
                        """),
                response(200, "{\"id\":\"other-mail\"}"));
        for (int i = 0; i < 2; i++) {
            assertEquals("Existing processed label was not uniquely identified",
                    assertThrows(IllegalStateException.class, client::processedLabel).getMessage());
        }
        assertEquals(3, requests.size());
        assertThrows(IllegalArgumentException.class, () -> client.applyLabels("../unsafe", "Label_1"));
        assertThrows(IllegalArgumentException.class, () -> client.applyLabels("mail-1", "../unsafe"));
        assertEquals(3, requests.size());
        assertEquals("Gmail modification acknowledgement was invalid",
                assertThrows(IllegalStateException.class, () -> client.applyLabels("mail-1", "Label_1")).getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void stalledBodyCancelsRequestWithSafeTimeout() throws Exception {
        HttpClient http = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> call = mock(CompletableFuture.class);
        when(call.get(20_000, TimeUnit.MILLISECONDS)).thenThrow(new TimeoutException("synthetic private detail"));
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(call);
        var client = new GoogleGmailReadClient(http, new JsonMapper(), () -> CONFIG);
        assertEquals("Gmail request timed out", assertThrows(IllegalStateException.class,
                () -> client.alertIds(1, null, null)).getMessage());
        verify(call).cancel(true);
    }

    @SuppressWarnings("unchecked")
    private static GoogleGmailReadClient scripted(String configuration, List<HttpRequest> requests,
                                                  Reply... replies) {
        HttpClient http = mock(HttpClient.class);
        var queued = new ArrayDeque<>(List.of(replies));
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    requests.add(invocation.getArgument(0));
                    var reply = queued.removeFirst();
                    HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                    var subscriber = handler.apply(null);
                    subscriber.onSubscribe(mock(Flow.Subscription.class));
                    subscriber.onNext(List.of(ByteBuffer.wrap(reply.body())));
                    subscriber.onComplete();
                    return subscriber.getBody().thenApply(body -> {
                        HttpResponse<byte[]> response = mock(HttpResponse.class);
                        when(response.statusCode()).thenReturn(reply.status());
                        when(response.body()).thenReturn(body);
                        return response;
                    }).toCompletableFuture();
                });
        return new GoogleGmailReadClient(http, new JsonMapper(), () -> configuration);
    }

    private static Reply response(int status, String body) {
        return response(status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static Reply response(int status, byte[] body) {
        return new Reply(status, body);
    }

    private record Reply(int status, byte[] body) { }
}
