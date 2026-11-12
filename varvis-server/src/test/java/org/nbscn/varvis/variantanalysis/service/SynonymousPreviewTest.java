package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Loopback read-only bridge to actual H2-backed controllers, never a replay or a model trigger. */
class SynonymousPreviewTest extends SynonymousBusinessSmokeTest {
    @Test @EnabledIfSystemProperty(named="varvis.bp7.syn.phase", matches="preview")
    void readOnlyProductPreview() throws Exception {
        setup();base.analysisId=Files.readString(work.resolve("analysis-id.txt")).trim();wireBusiness();
        Path staticRoot=Path.of(System.getProperty("varvis.bp7.syn.previewRoot"));
        var stopped=new CountDownLatch(1);
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",19087),0);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();server.setExecutor(executor);
        server.createContext("/",exchange->{
            int code=404;byte[] body=new byte[0];String type="application/json; charset=utf-8";
            try {
                String path=exchange.getRequestURI().getPath();
                if ("/preview/stop".equals(path) && "POST".equals(exchange.getRequestMethod())) {code=200;stopped.countDown();}
                else if (!"GET".equals(exchange.getRequestMethod())) code=405;
                else if (path.startsWith("/variant-analyses/"+base.analysisId+"/acmg/")) {
                    var response=base.http.perform(get(java.util.Objects.requireNonNull(exchange.getRequestURI()))).andReturn().getResponse();code=response.getStatus();body=response.getContentAsByteArray();
                } else if (path.equals("/") || path.equals("/preview.js")) {
                    body=Files.readAllBytes(staticRoot.resolve(path.equals("/")?"index.html":"preview.js"));code=200;
                    type=path.equals("/")?"text/html; charset=utf-8":"application/javascript";
                }
            } catch(Exception error) {code=500;body=error.getClass().getSimpleName().getBytes(java.nio.charset.StandardCharsets.UTF_8);}
            exchange.getResponseHeaders().set("Content-Type",type);exchange.sendResponseHeaders(code,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        try {server.start();System.out.println("BP7_PREVIEW_READY http://127.0.0.1:19087/");
            // Only this opt-in test waits for browser inspection; the listener remains responsive.
            assertTrue(stopped.await(15,TimeUnit.MINUTES),"Preview timed out before explicit cleanup");
            assertTrue(calls().values().stream().allMatch(n->n==0));
            Files.writeString(work.resolve("page-readback-calls.json"),base.mapper.valueToTree(calls()).toPrettyString());
        } finally {server.stop(0);executor.shutdownNow();base.closeDatabase();}
    }
}
