package org.nbscn.varvis.variantanalysis.provider;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProteinRegionHttpTransportTest {
    @Test void fastaNegotiationDoesNotChangeExistingJsonRequests() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestHeaders().getFirst("Accept").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        }); server.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals("application/json", new String(ProteinRegionHttpTransport.get(uri, Duration.ofSeconds(3), 100).body(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("text/plain", new String(ProteinRegionHttpTransport.getText(uri, Duration.ofSeconds(3), 100).body(), java.nio.charset.StandardCharsets.UTF_8));
        } finally { server.stop(0); }
    }
    @Test void actualBodyLimitRejectsOversizeResponseAndDoesNotFollowRedirect() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/large", exchange -> { exchange.sendResponseHeaders(200,10000); try (var out = exchange.getResponseBody()) { out.write(new byte[10000]); } });
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().set("Location","/large"); exchange.sendResponseHeaders(302,-1); exchange.close(); });
        server.start(); var base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            assertEquals("PROTEIN_REGION_BODY_LIMIT",assertThrows(java.io.IOException.class, () -> ProteinRegionHttpTransport.get(URI.create(base+"/large"),Duration.ofSeconds(3),256)).getMessage());
            assertEquals(302,ProteinRegionHttpTransport.get(URI.create(base+"/redirect"),Duration.ofSeconds(3),256).status());
        } finally { server.stop(0); }
    }
    @Test void throttlingHeadersSurviveAnErrorBodyThatNeverFinishes() throws Exception {
        var release = new CountDownLatch(1); var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().set("Retry-After","123"); exchange.sendResponseHeaders(429,1000);
            try { release.await(3,TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start();
        try {
            var response = ProteinRegionHttpTransport.get(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofMillis(700),256);
            assertEquals(429,response.status()); assertEquals("123",response.header("retry-after")); assertEquals(0,response.body().length);
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void totalTimeoutIncludesPartialBody() throws Exception {
        var release = new CountDownLatch(1); var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200,1000); exchange.getResponseBody().write(1); exchange.getResponseBody().flush();
            try { release.await(3,TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start();
        try {
            long start = System.nanoTime();
            assertThrows(java.io.IOException.class, () -> ProteinRegionHttpTransport.get(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofMillis(250),2000));
            assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<1500);
        } finally { release.countDown(); server.stop(0); }
    }
}
