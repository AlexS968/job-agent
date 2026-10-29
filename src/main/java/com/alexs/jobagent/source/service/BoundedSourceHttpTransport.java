package com.alexs.jobagent.source.service;

import com.alexs.jobagent.http.BoundedBodySubscriber;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

@Component
public class BoundedSourceHttpTransport {
    private static final int MAX_BYTES = 4_000_000;
    private final RequestSender sender;
    private final Duration completionTimeout;

    public BoundedSourceHttpTransport() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build()::sendAsync, Duration.ofSeconds(9));
    }

    BoundedSourceHttpTransport(RequestSender sender, Duration completionTimeout) {
        this.sender = sender;
        this.completionTimeout = completionTimeout;
    }

    public Response get(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build();
        return send(request);
    }

    public Response postJson(URI uri, String authorization, String json) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return send(request);
    }

    private Response send(HttpRequest request) throws IOException {
        CompletableFuture<HttpResponse<byte[]>> call = sender.send(request,
                info -> new BoundedBodySubscriber(MAX_BYTES, ResponseTooLargeException::new));
        try {
            HttpResponse<byte[]> response = call.get(completionTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Response(response.statusCode(), response.body(), response.headers().map());
        } catch (TimeoutException timeout) {
            call.cancel(true);
            throw new IOException("Source request timed out", timeout);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof ResponseTooLargeException tooLarge) {
                throw tooLarge;
            }
            throw new IOException("Source transport failed", cause);
        } catch (InterruptedException interrupted) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new RequestInterruptedException(interrupted);
        }
    }

    public record Response(int status, byte[] body, Map<String, List<String>> headers) {
        public Response {
            body = body.clone();
            headers = headers.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Response response && status == response.status
                    && Arrays.equals(body, response.body) && headers.equals(response.headers);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, Arrays.hashCode(body), headers);
        }

        @Override
        public String toString() {
            return "Response[status=" + status + ", bodyLength=" + body.length + "]";
        }

        public String firstHeader(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream()).findFirst().orElse(null);
        }
    }

    public static final class ResponseTooLargeException extends IOException {
        public ResponseTooLargeException() {
            super("Source response exceeds configured size limit");
        }
    }

    public static final class RequestInterruptedException extends IOException {
        public RequestInterruptedException(InterruptedException cause) {
            super("Source request interrupted", cause);
        }
    }

    @FunctionalInterface
    interface RequestSender {
        CompletableFuture<HttpResponse<byte[]>> send(HttpRequest request, HttpResponse.BodyHandler<byte[]> handler);
    }

}
