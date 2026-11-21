package com.alexs.jobagent.source.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HimalayasSearchAdapterTest {
    private static final Instant START = Instant.parse("2026-11-21T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(START, ZoneOffset.UTC);
    private final BoundedSourceHttpTransport transport = mock(BoundedSourceHttpTransport.class);
    private final HimalayasSearchAdapter adapter = new HimalayasSearchAdapter(transport, new JsonMapper(), CLOCK);

    @Test
    void reportsEachFilterFailureWithoutRetryingOrCountingARejectedPage() throws Exception {
        when(transport.get(any(URI.class), any(Duration.class)))
                .thenReturn(ok("{\"jobs\":["))
                .thenThrow(new HttpTimeoutException("Timed out"),
                        new BoundedSourceHttpTransport.ResponseTooLargeException());

        var batch = adapter.fetch(Instant.MAX);

        assertEquals(Map.of("DE", "INVALID_RESPONSE", "AT", "TRANSPORT_FAILURE",
                "WORLDWIDE", "RESPONSE_TOO_LARGE"), batch.failedFilters());
        assertEquals(3, batch.requests());
        assertEquals(0, batch.fetched());
        assertEquals(0, batch.invalidRows());
        assertTrue(batch.listings().isEmpty());
        verify(transport, times(3)).get(any(URI.class), any(Duration.class));
    }

    @Test
    void keepsValidNeighborWhenAPageContainsUnusablePostingRows() throws Exception {
        when(transport.get(any(URI.class), any(Duration.class))).thenReturn(ok("""
                {"jobs":[
                  {"title":" ", "guid":"https://himalayas.app/companies/example/jobs/missing-title"},
                  {"title":"Java Engineer", "guid":"http://himalayas.app/companies/example/jobs/unsafe"},
                  {"title":"Java Engineer", "guid":"https://user@himalayas.app/companies/example/jobs/unsafe"},
                  {"title":"Java Engineer", "guid":"https://himalayas.app/companies/example"},
                  {"title":"Java Engineer", "guid":"https://himalayas.app/companies/example/jobs/malformed",
                   "description":{"text":"Backend"}},
                  {"title":"Backend Engineer", "guid":"https://himalayas.app/companies/example/jobs/backend/",
                   "locationRestrictions":["Germany", 42]}
                ]}
                """), ok("{\"jobs\":{}}"), ok("{\"jobs\":[]}"));

        var batch = adapter.fetch(Instant.MAX);

        assertEquals(6, batch.fetched());
        assertEquals(5, batch.invalidRows());
        assertEquals(Map.of("AT", "INVALID_RESPONSE"), batch.failedFilters());
        assertEquals(1, batch.listings().size());
        var listing = batch.listings().getFirst();
        assertEquals("https://himalayas.app/companies/example/jobs/backend/", listing.postingUrl());
        assertNull(listing.description());
        assertNull(listing.advertisedLocation());
    }

    @Test
    void interruptionDuringTransportStopsRemainingFiltersAndPreservesTheInterruptFlag() throws Exception {
        var interruption = new BoundedSourceHttpTransport.RequestInterruptedException(new InterruptedException());
        when(transport.get(any(URI.class), any(Duration.class))).thenThrow(interruption);

        try {
            var failure = assertThrows(IllegalStateException.class, () -> adapter.fetch(Instant.MAX));
            assertTrue(Thread.currentThread().isInterrupted());
            assertSame(interruption, failure.getCause());
        } finally {
            Thread.interrupted();
        }
        verify(transport).get(any(URI.class), any(Duration.class));
    }

    @Test
    void preInterruptedSearchDoesNotStartTransport() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(IllegalStateException.class, () -> adapter.fetch(Instant.MAX));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        verifyNoInteractions(transport);
    }

    @Test
    void passesRemainingBudgetToTransportAndSkipsFiltersAfterDeadline() throws Exception {
        Clock advancing = mock(Clock.class);
        Instant deadline = START.plusSeconds(2);
        when(advancing.instant()).thenReturn(START, deadline);
        var bounded = new HimalayasSearchAdapter(transport, new JsonMapper(), advancing);
        when(transport.get(any(URI.class), any(Duration.class))).thenReturn(ok("{\"jobs\":[]}"));

        var result = bounded.fetch(deadline);

        assertEquals(1, result.requests());
        assertEquals(Map.of("AT", "DEADLINE_REACHED", "WORLDWIDE", "DEADLINE_REACHED"), result.failedFilters());
        verify(transport).get(any(URI.class), eq(Duration.ofSeconds(2)));
        verifyNoMoreInteractions(transport);
    }

    private static BoundedSourceHttpTransport.Response ok(String body) {
        return new BoundedSourceHttpTransport.Response(200, body.getBytes(StandardCharsets.UTF_8), Map.of());
    }
}
