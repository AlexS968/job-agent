package com.alexs.jobagent.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.function.Supplier;

public final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final int maximumBytes;
    private final Supplier<? extends Throwable> overflow;
    private Flow.Subscription subscription;

    public BoundedBodySubscriber(int maximumBytes, Supplier<? extends Throwable> overflow) {
        this.maximumBytes = maximumBytes;
        this.overflow = overflow;
    }

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
    public void onNext(List<ByteBuffer> items) {
        for (ByteBuffer item : items) {
            if (item.remaining() > maximumBytes - bytes.size()) {
                subscription.cancel();
                body.completeExceptionally(overflow.get());
                return;
            }
            byte[] chunk = new byte[item.remaining()];
            item.get(chunk);
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
