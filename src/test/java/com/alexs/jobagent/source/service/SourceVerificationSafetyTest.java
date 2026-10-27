package com.alexs.jobagent.source.service;

import static org.junit.jupiter.api.Assertions.*;

import com.alexs.jobagent.source.dto.AtsSourceIdentityDTO;
import com.alexs.jobagent.source.exception.VerificationFetchException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SourceVerificationSafetyTest {
    @Test
    void unsafeUrlsAndSpecialAddressesAreRejected() throws Exception {
        for (String url : new String[] {"http://example.com", "https://user:pass@example.com",
                "https://example.com:8443/jobs", "https://127.0.0.1/jobs", "https://[::1]/jobs",
                "https://example.com/jobs#fragment", "https://localhost/jobs"}) {
            assertThrows(IllegalArgumentException.class, () -> PublicDestinationPolicy.requirePublicHttpsUri(url));
        }
        for (String address : new String[] {"127.0.0.1", "10.2.3.4", "169.254.1.2", "100.64.1.2",
                "192.168.1.1", "192.88.99.1", "198.18.0.1", "203.0.113.1", "::1", "fc00::1", "fe80::1",
                "2001:db8::1", "2001::1", "2002:0a00:0001::1", "::ffff:127.0.0.1"}) {
            assertFalse(PublicDestinationPolicy.isPublic(InetAddress.getByName(address)), address);
        }
        assertTrue(PublicDestinationPolicy.isPublic(InetAddress.getByName("8.8.8.8")));
        assertTrue(PublicDestinationPolicy.isPublic(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void connectionResolverRejectsMixedAnswersAndRebindingWithoutSecondLookup() throws Exception {
        var mixed = new PublicDestinationPolicy(host -> new InetAddress[] {
                InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1")});
        assertThrows(UnknownHostException.class, () -> mixed.resolve("example.com"));
        var calls = new AtomicInteger();
        var rebinding = new PublicDestinationPolicy(host -> calls.incrementAndGet() == 1
                ? new InetAddress[] {InetAddress.getByName("8.8.8.8")}
                : new InetAddress[] {InetAddress.getByName("127.0.0.1")});
        assertEquals("8.8.8.8", rebinding.resolve("example.com")[0].getHostAddress());
        assertThrows(UnknownHostException.class, () -> rebinding.resolve("example.com"));
        assertEquals(2, calls.get());
    }

    @Test
    void realConnectionManagerUsesValidatedResolverBeforeConnecting() {
        var calls = new AtomicInteger();
        var resolver = new PublicDestinationPolicy(host -> {
            calls.incrementAndGet();
            return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
        });
        var failure = assertThrows(VerificationFetchException.class,
                () -> new SafeHtmlFetcher(resolver).fetch("https://example.com/jobs"));
        assertEquals("FETCH_ERROR", failure.reason());
        assertEquals(1, calls.get());
    }

    @Test
    void atsIdentityRequiresExactSupportedHostAndSafeSite() {
        assertEquals(new AtsSourceIdentityDTO("GREENHOUSE", "global", "acme"),
                AtsSourceIdentityParser.parse(URI.create("https://job-boards.greenhouse.io/acme/jobs/1")).orElseThrow());
        assertEquals(new AtsSourceIdentityDTO("LEVER", "eu", "acme"),
                AtsSourceIdentityParser.parse(URI.create("https://jobs.eu.lever.co/acme/123")).orElseThrow());
        assertTrue(AtsSourceIdentityParser.parse(URI.create("https://jobs.lever.co.evil.example/acme")).isEmpty());
        assertTrue(AtsSourceIdentityParser.parse(URI.create("https://jobs.lever.co/ACME")).isEmpty());
    }

    @Test
    void redirectsAreValidatedCountedAndLoopBounded() {
        var calls = new AtomicInteger();
        SafeHtmlFetcher fetcher = new SafeHtmlFetcher((uri, remaining) -> {
            calls.incrementAndGet();
            return new SafeHtmlFetcher.PageResponse(302, null, "/careers", "identity", new byte[0]);
        });
        var failure = assertThrows(VerificationFetchException.class,
                () -> fetcher.fetch("https://example.com/jobs"));
        assertEquals("REDIRECT_LOOP", failure.reason());
        assertEquals(2, failure.requests());
        assertEquals(2, calls.get());
        var privateRedirect = new SafeHtmlFetcher((uri, remaining) ->
                new SafeHtmlFetcher.PageResponse(302, null, "http://127.0.0.1/", "identity", new byte[0]));
        assertEquals("UNSAFE_REDIRECT", assertThrows(VerificationFetchException.class,
                () -> privateRedirect.fetch("https://example.com/jobs")).reason());
    }

    @Test
    void timeoutOversizeEncodingAndUnsupportedContentFailSafely() {
        assertReason("TIMEOUT", (uri, remaining) -> { throw new SafeHtmlFetcher.PageTimeoutException(); });
        assertReason("TOO_LARGE", (uri, remaining) ->
                new SafeHtmlFetcher.PageResponse(200, "text/html", null, "identity",
                        new byte[SafeHtmlFetcher.MAX_BYTES + 1]));
        assertReason("UNSUPPORTED_ENCODING", (uri, remaining) ->
                new SafeHtmlFetcher.PageResponse(200, "text/html", null, "gzip", new byte[0]));
        assertReason("UNSUPPORTED_CONTENT", (uri, remaining) ->
                new SafeHtmlFetcher.PageResponse(200, "application/json", null, "identity", new byte[0]));
        assertReason("FETCH_ERROR", (uri, remaining) -> { throw new IOException("synthetic failure"); });
    }

    private void assertReason(String reason, SafeHtmlFetcher.PageTransport transport) {
        var failure = assertThrows(VerificationFetchException.class,
                () -> new SafeHtmlFetcher(transport).fetch("https://example.com/jobs"));
        assertEquals(reason, failure.reason());
        assertEquals(1, failure.requests());
    }
}
