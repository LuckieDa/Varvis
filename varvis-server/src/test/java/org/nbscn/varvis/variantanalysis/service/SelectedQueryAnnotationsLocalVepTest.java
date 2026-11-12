package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.provider.VepTranscriptAnnotationProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in public-site component check. No application, database, model or network startup. */
class SelectedQueryAnnotationsLocalVepTest {
    @Test void existingNormalizedPublicAlleleObtainsMatchingRealAnnotation() throws Exception {
        String directory = System.getProperty("selected.query.vep.fixture");
        org.junit.jupiter.api.Assumptions.assumeTrue(directory != null);
        Path root = Path.of("..").toAbsolutePath().normalize(), evidence = Path.of(directory);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var fixed = mapper.readTree(Files.readAllBytes(evidence.resolve("rpgr-fixed.json")));
        var context = (ObjectNode) fixed.path("context"); var q = context.at("/variant/evidenceQueryAllele");
        var key = new NormalizedVariantKey(q.path("assembly").asText(), q.path("contig").asText(),
                q.path("position").asInt(), q.path("reference").asText(), q.path("alternate").asText());
        var configured = new Properties();
        try (var stream = Files.newInputStream(root.resolve("varvis-server/config/variant-analysis-local.properties"))) { configured.load(stream); }
        var values = new LinkedHashMap<String, Object>();
        for (var name : configured.stringPropertyNames()) if (name.startsWith("variant-analysis.vep."))
            values.put(name, configured.getProperty(name).replace("${VARVIS_TOOL_ROOT}", root.resolve("local/tools").toString().replace('\\', '/')));
        var settings = new Binder(new MapConfigurationPropertySource(values)).bind("variant-analysis", VariantAnalysisProperties.class).get();
        // Isolated output location only; execution/resource flags and scientific parameters stay as configured.
        settings.getVep().setTempDir(evidence.resolve("vep-work").toString()); settings.getVep().setCleanupTemp(false);
        var candidate = new VariantInterpretation(key.canonicalId(),
                new VariantQcObservation(key, null, null, null, null, null, null, null, null, null), List.of(), List.of());
        var annotations = new SelectedQueryAnnotations(mapper); var calls = new AtomicInteger();
        var provider = new VepTranscriptAnnotationProvider(settings);
        long started = System.nanoTime();
        boolean replay = Boolean.getBoolean("selected.query.vep.replay");
        var originalReceipts = replay ? mapper.readTree(Files.readAllBytes(evidence.resolve("real-vep-receipts.json"))) : null;
        List<TranscriptConsequence> frozen = List.of();
        if (replay) {
            var receipt = requireNonNull(originalReceipts).get(0);
            var ledger = ExplicitContextSourceLedger.verifiedBytes(mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS),
                    receipt.path("rawJson").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8), receipt.path("sha256").asText());
            var record = ledger.resolve(ledger.reference("annotation"));
            assertEquals(mapper.valueToTree(key), record.path("queryAllele"));
            frozen = mapper.readerForListOf(TranscriptConsequence.class)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(record.path("annotations"));
        }
        final var savedAnnotations = frozen;
        annotations.prepare(List.of(candidate, candidate), new AnalysisContext("public-query-component", GenomeAssembly.GRCh38, AnalysisMode.WES),
                context.path("dataClass").asText(), (c, a) -> { calls.incrementAndGet();
                    return replay ? Map.of(key, savedAnnotations) : provider.annotateBatch(c, a); });
        var receipts = mapper.createArrayNode(); for (var source : annotations.sources()) receipts.add(source.receipt(mapper));
        mapper.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve(replay ? "verified-vep-receipts.json" : "real-vep-receipts.json").toFile(), receipts);
        var result = annotations.annotations(key, context, fixed.at("/originalCandidate/transcriptAnnotations"));
        var summary = mapper.createObjectNode().put("providerBatchCalls", replay ? 0 : calls.get())
                .put("verificationMode", replay ? "FROZEN_REAL_OUTPUT_REPLAY" : "REAL_LOCAL_VEP")
                .put("elapsedMillis", (System.nanoTime()-started)/1_000_000)
                .put("scientificEvaluationCalls", 0).put("modelCalls", 0).put("databaseCalls", 0);
        summary.set("queryAllele", mapper.valueToTree(key)); summary.set("selectedAnnotation", result);
        mapper.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("real-vep-summary.json").toFile(), summary);
        assertEquals(1, calls.get()); assertEquals(1, result.size());
        assertEquals("RPGR", result.get(0).path("geneSymbol").asText());
        assertEquals("116.0", result.get(0).path("version").asText());
        assertEquals("inframe_deletion", result.get(0).path("consequence").asText());
        // Repeated consumers read the same receipt, never rerun the provider.
        assertEquals(result, annotations.annotations(key, context, fixed.at("/originalCandidate/transcriptAnnotations")));
        assertEquals(1, calls.get());
    }
}
