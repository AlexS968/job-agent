package com.alexs.jobagent.source.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

@Component
public class GreenhouseHttpTransport {
    private static final int MAX_BYTES = 4_000_000;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public Response get(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build();
        CompletableFuture<HttpResponse<byte[]>> call = client.sendAsync(request, info -> new LimitedBodySubscriber());
        try {
            HttpResponse<byte[]> response = call.get(9, TimeUnit.SECONDS);
            return new Response(response.statusCode(), response.body(), response.headers().map());
        } catch (TimeoutException timeout) {
            call.cancel(true);
            throw new IOException("Greenhouse request timed out", timeout);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof ResponseTooLargeException tooLarge) {
                throw tooLarge;
            }
            throw new IOException("Greenhouse transport failed", cause);
        } catch (InterruptedException interrupted) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Greenhouse request interrupted", interrupted);
        }
    }

    public record Response(int status, byte[] body, Map<String, List<String>> headers) {
        public String firstHeader(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .flatMap(entry -> entry.getValue().stream()).findFirst().orElse(null);
        }
    }

    public static final class ResponseTooLargeException extends IOException {
        public ResponseTooLargeException() {
            super("Greenhouse response exceeds configured size limit");
        }
    }

    private static final class LimitedBodySubscriber implements BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new ResponseTooLargeException());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}
