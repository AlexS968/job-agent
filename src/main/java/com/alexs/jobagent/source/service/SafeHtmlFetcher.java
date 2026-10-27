package com.alexs.jobagent.source.service;

import com.alexs.jobagent.source.exception.VerificationFetchException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SafeHtmlFetcher {
    public static final int MAX_REQUESTS = 3;
    public static final int MAX_BYTES = 256 * 1024;
    private static final Duration TOTAL = Duration.ofSeconds(5);
    private final PageTransport transport;

    @Autowired
    public SafeHtmlFetcher() {
        this(new ApachePageTransport(new PublicDestinationPolicy()));
    }

    SafeHtmlFetcher(PublicDestinationPolicy dns) {
        this(new ApachePageTransport(dns));
    }

    public SafeHtmlFetcher(PageTransport transport) {
        this.transport = transport;
    }

    public FetchResult fetch(String rawUrl) throws VerificationFetchException {
        URI current = PublicDestinationPolicy.requirePublicHttpsUri(rawUrl);
        String officialHost = current.getHost();
        Set<URI> visited = new HashSet<>();
        long deadline = System.nanoTime() + TOTAL.toNanos();
        for (int requests = 1; requests <= MAX_REQUESTS; requests++) {
            if (!visited.add(current)) {
                throw new VerificationFetchException("REDIRECT_LOOP", requests - 1);
            }
            Duration remaining = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
            if (remaining.isZero()) {
                throw new VerificationFetchException("TIMEOUT", requests - 1);
            }
            PageResponse response;
            try {
                response = transport.get(current, remaining);
            } catch (PageTimeoutException timeout) {
                throw new VerificationFetchException("TIMEOUT", requests);
            } catch (PageTooLargeException tooLarge) {
                throw new VerificationFetchException("TOO_LARGE", requests);
            } catch (IOException failure) {
                throw new VerificationFetchException("FETCH_ERROR", requests);
            }
            if (response.contentEncoding() != null && !"identity".equalsIgnoreCase(response.contentEncoding())) {
                throw new VerificationFetchException("UNSUPPORTED_ENCODING", requests);
            }
            if (response.status() >= 300 && response.status() < 400) {
                URI next = redirect(current, response.location(), requests);
                if (!officialHost.equalsIgnoreCase(next.getHost())) {
                    return new FetchResult(current, next, null, requests);
                }
                current = next;
            } else {
                if (response.status() != 200) {
                    throw new VerificationFetchException("HTTP_STATUS", requests);
                }
                if (response.contentType() == null || !response.contentType().toLowerCase(java.util.Locale.ROOT)
                        .startsWith("text/html")) {
                    throw new VerificationFetchException("UNSUPPORTED_CONTENT", requests);
                }
                if (response.body().length > MAX_BYTES) {
                    throw new VerificationFetchException("TOO_LARGE", requests);
                }
                return new FetchResult(current, null, new String(response.body(), StandardCharsets.UTF_8), requests);
            }
        }
        throw new VerificationFetchException("REDIRECT_LIMIT", MAX_REQUESTS);
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
    }

    @FunctionalInterface
    public interface PageTransport {
        PageResponse get(URI uri, Duration remaining) throws IOException;
    }

    public static final class PageTimeoutException extends IOException { }
    public static final class PageTooLargeException extends IOException { }

    private static final class ApachePageTransport implements PageTransport {
        private final PublicDestinationPolicy dns;

        private ApachePageTransport(PublicDestinationPolicy dns) { this.dns = dns; }

        @Override
        public PageResponse get(URI uri, Duration remaining) throws IOException {
            HttpGet request = new HttpGet(uri);
            request.setHeader("Accept", "text/html");
            request.setHeader("Accept-Encoding", "identity");
            long millis = Math.max(1, Math.min(2000, remaining.toMillis()));
            request.setConfig(RequestConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(millis))
                    .setResponseTimeout(Timeout.ofMilliseconds(millis)).setRedirectsEnabled(false)
                    .setAuthenticationEnabled(false).setHardCancellationEnabled(true).build());
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            Future<PageResponse> task = executor.submit(() -> execute(request));
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
                executor.shutdownNow();
            }
        }

        private PageResponse execute(HttpGet request) throws IOException {
            var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(dns)
                    .setMaxConnTotal(1).setMaxConnPerRoute(1).build();
            try (var client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling()
                    .disableContentCompression().disableAutomaticRetries().disableCookieManagement()
                    .disableAuthCaching().setConnectionReuseStrategy((req, res, context) -> false).build()) {
                return client.execute(request, response -> {
                    String encoding = response.getFirstHeader("Content-Encoding") == null ? "identity"
                            : response.getFirstHeader("Content-Encoding").getValue();
                    String type = response.getFirstHeader("Content-Type") == null ? null
                            : response.getFirstHeader("Content-Type").getValue();
                    String location = response.getFirstHeader("Location") == null ? null
                            : response.getFirstHeader("Location").getValue();
                    byte[] body = readBounded(response.getEntity() == null ? InputStream.nullInputStream()
                            : response.getEntity().getContent());
                    return new PageResponse(response.getCode(), type, location, encoding, body);
                });
            }
        }

        private byte[] readBounded(InputStream input) throws IOException {
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
    }
}
