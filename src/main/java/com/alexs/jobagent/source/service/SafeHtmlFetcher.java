package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.exception.VerificationFetchException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SafeHtmlFetcher {
    public static final int MAX_REQUESTS = 3;
    public static final int MAX_BYTES = 256 * 1024;
    private static final Duration TOTAL = Duration.ofSeconds(5);
    private static final String IDENTITY_ENCODING = "identity";
    private final PageTransport transport;
    private final Duration total;

    @Autowired
    public SafeHtmlFetcher() {
        this(new ApachePageTransport(new PublicDestinationPolicy()));
    }

    SafeHtmlFetcher(PublicDestinationPolicy dns) {
        this(new ApachePageTransport(dns));
    }

    public SafeHtmlFetcher(PageTransport transport) {
        this(transport, TOTAL);
    }

    SafeHtmlFetcher(PublicDestinationPolicy dns, Duration total) {
        this(new ApachePageTransport(dns), total);
    }

    private SafeHtmlFetcher(PageTransport transport, Duration total) {
        if (total.isZero() || total.isNegative() || total.compareTo(TOTAL) > 0) {
            throw new IllegalArgumentException("Verification time budget is outside the allowed range");
        }
        this.transport = transport;
        this.total = total;
    }

    public FetchResult fetch(String rawUrl) throws VerificationFetchException {
        URI current = PublicDestinationPolicy.requirePublicHttpsUri(rawUrl);
        String officialHost = current.getHost();
        Set<URI> visited = new HashSet<>();
        long deadline = System.nanoTime() + total.toNanos();
        for (int requests = 1; requests <= MAX_REQUESTS; requests++) {
            if (!visited.add(current)) {
                throw new VerificationFetchException("REDIRECT_LOOP", requests - 1);
            }
            Duration remaining = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
            if (remaining.isZero()) {
                throw new VerificationFetchException("TIMEOUT", requests - 1);
            }
            PageResponse response = request(current, remaining, requests);
            if (response.contentEncoding() != null
                    && !IDENTITY_ENCODING.equalsIgnoreCase(response.contentEncoding())) {
                throw new VerificationFetchException("UNSUPPORTED_ENCODING", requests);
            }
            if (response.status() >= 300 && response.status() < 400) {
                URI next = redirect(current, response.location(), requests);
                if (!officialHost.equalsIgnoreCase(next.getHost())) {
                    return new FetchResult(current, next, null, requests);
                }
                current = next;
            } else {
                return complete(current, response, requests);
            }
        }
        throw new VerificationFetchException("REDIRECT_LIMIT", MAX_REQUESTS);
    }

    private PageResponse request(URI current, Duration remaining, int requests) throws VerificationFetchException {
        try {
            return transport.get(current, remaining);
        } catch (PageTimeoutException timeout) {
            throw new VerificationFetchException("TIMEOUT", requests);
        } catch (PageTooLargeException tooLarge) {
            throw new VerificationFetchException("TOO_LARGE", requests);
        } catch (IOException failure) {
            throw new VerificationFetchException("FETCH_ERROR", requests);
        }
    }

    private FetchResult complete(URI current, PageResponse response, int requests)
            throws VerificationFetchException {
        if (response.status() != 200) {
            throw new VerificationFetchException("HTTP_STATUS", requests);
        }
        if (response.contentType() == null || !response.contentType().toLowerCase(java.util.Locale.ROOT)
                .startsWith("text/html")) {
            throw new VerificationFetchException("UNSUPPORTED_CONTENT", requests);
        }
        byte[] body = response.body();
        if (body.length > MAX_BYTES) {
            throw new VerificationFetchException("TOO_LARGE", requests);
        }
        return new FetchResult(current, null, new String(body, StandardCharsets.UTF_8), requests);
    }

    private URI redirect(URI current, String location, int requests) throws VerificationFetchException {
        try {
            if (location == null) {
                throw new IllegalArgumentException("Missing location");
            }
            return PublicDestinationPolicy.requirePublicHttpsUri(current.resolve(location).toString());
        } catch (IllegalArgumentException unsafe) {
            throw new VerificationFetchException("UNSAFE_REDIRECT", requests);
        }
    }

    public record FetchResult(URI pageUrl, URI externalRedirect, String html, int requests) { }
    public record PageResponse(int status, String contentType, String location, String contentEncoding, byte[] body) {
        public PageResponse { body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof PageResponse response
                    && status == response.status && Objects.equals(contentType, response.contentType)
                    && Objects.equals(location, response.location)
                    && Objects.equals(contentEncoding, response.contentEncoding)
                    && Arrays.equals(body, response.body);
        }
        @Override public int hashCode() {
            return 31 * Objects.hash(status, contentType, location, contentEncoding) + Arrays.hashCode(body);
        }
        @Override public String toString() {
            return "PageResponse[status=" + status + ", contentType=" + contentType
                    + ", bodyLength=" + body.length + ", bodyHash=" + Arrays.hashCode(body) + "]";
        }
    }

    @FunctionalInterface
    public interface PageTransport {
        PageResponse get(URI uri, Duration remaining) throws IOException;
    }

    public static final class PageTimeoutException extends IOException { }
    public static final class PageTooLargeException extends IOException { }

    static PageResponse readResponse(ClassicHttpResponse response) throws IOException {
        String encoding = response.getFirstHeader("Content-Encoding") == null ? IDENTITY_ENCODING
                : response.getFirstHeader("Content-Encoding").getValue();
        String type = response.getFirstHeader("Content-Type") == null ? null
                : response.getFirstHeader("Content-Type").getValue();
        String location = response.getFirstHeader("Location") == null ? null
                : response.getFirstHeader("Location").getValue();
        byte[] body = readBounded(response.getEntity() == null ? InputStream.nullInputStream()
                : response.getEntity().getContent());
        return new PageResponse(response.getCode(), type, location, encoding, body);
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = input.read(buffer)) != -1) {
            if (out.size() + length > MAX_BYTES) {
                throw new PageTooLargeException();
            }
            out.write(buffer, 0, length);
        }
        return out.toByteArray();
    }

    private static final class ApachePageTransport implements PageTransport {
        private final PublicDestinationPolicy dns;

        private ApachePageTransport(PublicDestinationPolicy dns) { this.dns = dns; }

        @Override
        public PageResponse get(URI uri, Duration remaining) throws IOException {
            HttpGet request = new HttpGet(uri);
            request.setHeader("Accept", "text/html");
            request.setHeader("Accept-Encoding", IDENTITY_ENCODING);
            long millis = Math.clamp(remaining.toMillis(), 1, 2000);
            request.setConfig(RequestConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(millis))
                    .setResponseTimeout(Timeout.ofMilliseconds(millis)).setRedirectsEnabled(false)
                    .setAuthenticationEnabled(false).setHardCancellationEnabled(true).build());
            FutureTask<PageResponse> task = new FutureTask<>(() -> execute(request));
            Thread.ofPlatform().daemon().name("source-verification-http").start(task);
            try {
                return task.get(Math.max(1, remaining.toMillis()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                request.cancel();
                task.cancel(true);
                throw new IOException("Verification request interrupted", interrupted);
            } catch (TimeoutException timeout) {
                request.cancel();
                task.cancel(true);
                throw new PageTimeoutException();
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException("Verification transport failed", cause);
            } finally {
                if (!task.isDone()) {
                    request.cancel();
                    task.cancel(true);
                }
            }
        }

        private PageResponse execute(HttpGet request) throws IOException {
            var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(dns)
                    .setMaxConnTotal(1).setMaxConnPerRoute(1).build();
            try (var client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling()
                    .disableContentCompression().disableAutomaticRetries().disableCookieManagement()
                    .disableAuthCaching().setConnectionReuseStrategy((req, res, context) -> false).build()) {
                return client.execute(request, SafeHtmlFetcher::readResponse);
            }
        }
    }
}
