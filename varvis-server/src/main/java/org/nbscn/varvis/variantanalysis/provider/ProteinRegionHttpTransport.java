package org.nbscn.varvis.variantanalysis.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/** Bounded JDK GET including the body. No redirects, retries, subprocesses or unbounded buffering. */
final class ProteinRegionHttpTransport {
    record Response(int status, Map<String,List<String>> headers, byte[] body) {
        String header(String key) {
            var values = headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(key)).map(mappedItem -> mappedItem.getValue()).toList();
            return values.size() == 1 && values.get(0).size() == 1 ? values.get(0).get(0) : "";
        }
    }
    @FunctionalInterface interface Fetcher { Response get(URI uri, Duration timeout, int maximumBytes) throws IOException, InterruptedException; }
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    static Response get(URI uri, Duration timeout, int maximumBytes) throws IOException, InterruptedException {
        return get(uri, timeout, maximumBytes, "application/json");
    }
    static Response getText(URI uri, Duration timeout, int maximumBytes) throws IOException, InterruptedException {
        return get(uri, timeout, maximumBytes, "text/plain");
    }
    private static Response get(URI uri, Duration timeout, int maximumBytes, String accept) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", accept)
                .header("Accept-Encoding", "identity").header("User-Agent", "Varvis-ProteinRegions/1.0").GET().build();
        var future = CLIENT.sendAsync(request, info -> info.statusCode() == 200 ? new LimitedBody(maximumBytes) : new RejectBody());
        try {
            var response = future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            return new Response(response.statusCode(), response.headers().map(), response.body());
        } catch (TimeoutException bad) { future.cancel(true); throw new IOException("PROTEIN_REGION_REQUEST_TIMEOUT", bad); }
        catch (ExecutionException bad) {
            Throwable cause = bad.getCause();
            if (cause instanceof java.net.http.HttpTimeoutException) throw new IOException("PROTEIN_REGION_REQUEST_TIMEOUT", cause);
            if (cause instanceof IOException io && "PROTEIN_REGION_BODY_LIMIT".equals(io.getMessage())) throw io;
            throw new IOException("PROTEIN_REGION_TRANSPORT_UNAVAILABLE", cause);
        }
        catch (InterruptedException bad) { future.cancel(true); throw bad; }

    }
    // Error status/Retry-After survive even if an error body never finishes. No error body is needed for facts.
    static final class RejectBody implements HttpResponse.BodySubscriber<byte[]> {
        public CompletionStage<byte[]> getBody() { return CompletableFuture.completedFuture(new byte[0]); }
        public void onSubscribe(Flow.Subscription subscription) { subscription.cancel(); }
        public void onNext(List<ByteBuffer> ignored) { }
        public void onError(Throwable ignored) { }
        public void onComplete() { }
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum; private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        @org.springframework.lang.Nullable private Flow.Subscription subscription;
        LimitedBody(int maximum) { this.maximum = maximum; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            long size = buffers.stream().mapToLong(mappedItem -> mappedItem.remaining()).sum();
            if (size > maximum - bytes.size()) {
                java.util.Objects.requireNonNull(subscription).cancel(); result.completeExceptionally(new IOException("PROTEIN_REGION_BODY_LIMIT")); return;
            }
            for (var buffer : buffers) { byte[] block = new byte[buffer.remaining()]; buffer.get(block); bytes.writeBytes(block); }
            java.util.Objects.requireNonNull(subscription).request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
