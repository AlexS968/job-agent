package com.alexs.jobagent.source.service;

import static org.junit.jupiter.api.Assertions.*;

import com.alexs.jobagent.source.dto.AtsSourceIdentityDTO;
import com.alexs.jobagent.source.exception.VerificationFetchException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
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
    void hostileLongHostAndMalformedLabelsAreRejectedWithoutRegexBacktracking() {
        for (String host : new String[] {"a".repeat(10_000) + ".com", "-bad.example",
                "bad-.example", "bad..example", "example.123", "example.com."}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PublicDestinationPolicy.requirePublicHttpsUri("https://" + host + "/jobs"));
        }
        assertEquals("Careers.Example", PublicDestinationPolicy.requirePublicHttpsUri(
                "https://Careers.Example/jobs").getHost());
        assertEquals(443, PublicDestinationPolicy.requirePublicHttpsUri(
                "https://careers.example:443/jobs").getPort());
        assertThrows(IllegalArgumentException.class, () -> PublicDestinationPolicy.requirePublicHttpsUri(
                "https://careers.example/" + "a".repeat(2049)));
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
        assertEquals("example.com", rebinding.resolveCanonicalHostname("Example.Com"));
        assertThrows(UnknownHostException.class, () -> rebinding.resolve("bad-.example"));
        var empty = new PublicDestinationPolicy(host -> new InetAddress[0]);
        assertThrows(UnknownHostException.class, () -> empty.resolve("example.com"));
        var publicAnswer = new PublicDestinationPolicy(host ->
                new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        Thread.currentThread().interrupt();
        try {
            assertThrows(UnknownHostException.class, () -> publicAnswer.resolve("example.com"));
        } finally {
            Thread.interrupted();
        }
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
    void interruptingARealTransportRequestCancelsConnectionTimeDns() throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        var resolver = new PublicDestinationPolicy(host -> {
            resolving.countDown();
            try {
                if (!cancelled.await(10, TimeUnit.SECONDS)) {
                    throw new UnknownHostException("synthetic DNS wait expired");
                }
            } catch (InterruptedException interrupted) {
                cancelled.countDown();
                Thread.currentThread().interrupt();
                throw new UnknownHostException("synthetic DNS interrupted");
            }
            return new InetAddress[] {InetAddress.getByName("8.8.8.8")};
        });
        AtomicReference<String> reason = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                new SafeHtmlFetcher(resolver).fetch("https://example.com/jobs");
            } catch (VerificationFetchException failure) {
                reason.set(failure.reason());
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        });
        assertTrue(resolving.await(5, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(2_000);
        assertFalse(caller.isAlive());
        assertEquals("FETCH_ERROR", reason.get());
        assertTrue(interruptPreserved.get());
        assertTrue(cancelled.await(2, TimeUnit.SECONDS));
    }

    @Test
    void realTransportTimeoutCancelsBlockedDnsWithoutConnecting() throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        var resolver = new PublicDestinationPolicy(host -> {
            resolving.countDown();
            try {
                neverReleased.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                cancelled.countDown();
                Thread.currentThread().interrupt();
            }
            throw new UnknownHostException("synthetic DNS did not resolve");
        });
        var fetcher = new SafeHtmlFetcher(resolver, Duration.ofMillis(100));
        var failure = assertThrows(VerificationFetchException.class,
                () -> fetcher.fetch("https://example.com/jobs"));
        assertEquals("TIMEOUT", failure.reason());
        assertEquals(1, failure.requests());
        assertTrue(resolving.await(1, TimeUnit.SECONDS));
        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
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
        var missingLocation = new SafeHtmlFetcher((uri, remaining) ->
                new SafeHtmlFetcher.PageResponse(302, null, null, "identity", new byte[0]));
        assertEquals("UNSAFE_REDIRECT", assertThrows(VerificationFetchException.class,
                () -> missingLocation.fetch("https://example.com/jobs")).reason());
    }

    @Test
    void crossHostRedirectIsRecordedWithoutFollowingAndRequestLimitIsEnforced() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var crossHost = new SafeHtmlFetcher((uri, remaining) -> {
            calls.incrementAndGet();
            return new SafeHtmlFetcher.PageResponse(302, null, "https://other.example/jobs", "identity", new byte[0]);
        });
        var result = crossHost.fetch("https://example.com/careers");
        assertEquals(1, result.requests());
        assertEquals(URI.create("https://other.example/jobs"), result.externalRedirect());
        assertEquals(1, calls.get());

        AtomicInteger redirects = new AtomicInteger();
        var chain = new SafeHtmlFetcher((uri, remaining) -> new SafeHtmlFetcher.PageResponse(
                302, null, "/step-" + redirects.incrementAndGet(), "identity", new byte[0]));
        var limit = assertThrows(VerificationFetchException.class,
                () -> chain.fetch("https://example.com/start"));
        assertEquals("REDIRECT_LIMIT", limit.reason());
        assertEquals(SafeHtmlFetcher.MAX_REQUESTS, redirects.get());
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
        assertReason("HTTP_STATUS", (uri, remaining) ->
                new SafeHtmlFetcher.PageResponse(503, "text/html", null, "identity", new byte[0]));
    }

    @Test
    void pageResponseUsesContentValueSemanticsWithoutLeakingBody() {
        byte[] bytes = "private synthetic page".getBytes(StandardCharsets.UTF_8);
        var first = new SafeHtmlFetcher.PageResponse(200, "text/html", null, "identity", bytes);
        var second = new SafeHtmlFetcher.PageResponse(200, "text/html", null, "identity", bytes.clone());
        bytes[0] = 0;
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertFalse(first.toString().contains("private synthetic page"));
        first.body()[0] = 0;
        assertEquals(second, first);
    }

    @Test
    void apacheResponseReaderPreservesHeadersAndBoundsUntrustedBytes() throws Exception {
        var response = new BasicClassicHttpResponse(302);
        response.addHeader("Location", "/jobs");
        response.addHeader("Content-Type", "text/html; charset=utf-8");
        response.setEntity(new ByteArrayEntity("ok".getBytes(StandardCharsets.UTF_8), ContentType.TEXT_HTML));
        var page = SafeHtmlFetcher.readResponse(response);
        assertEquals(302, page.status());
        assertEquals("/jobs", page.location());
        assertEquals("identity", page.contentEncoding());
        assertEquals("text/html; charset=utf-8", page.contentType());
        assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), page.body());

        var oversized = new BasicClassicHttpResponse(200);
        oversized.setEntity(new ByteArrayEntity(new byte[SafeHtmlFetcher.MAX_BYTES + 1], ContentType.TEXT_HTML));
        assertThrows(SafeHtmlFetcher.PageTooLargeException.class, () -> SafeHtmlFetcher.readResponse(oversized));
        assertEquals(0, SafeHtmlFetcher.readResponse(new BasicClassicHttpResponse(204)).body().length);
    }

    private void assertReason(String reason, SafeHtmlFetcher.PageTransport transport) {
        var failure = assertThrows(VerificationFetchException.class,
                () -> new SafeHtmlFetcher(transport).fetch("https://example.com/jobs"));
        assertEquals(reason, failure.reason());
        assertEquals(1, failure.requests());
    }
}
