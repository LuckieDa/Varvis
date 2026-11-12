package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import static org.junit.jupiter.api.Assertions.*;

/** Real captured ERepo/CSpec/SCV sources and real local ClinVar interval.
 * Reference qualification only: PTEN target scoring must obey its own CSpec,
 * and is NOT granted by this generic upstream-reference existence test. */
class Pvs1StartLossNativeReferenceTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();

    @Test void nativeIntronicReferenceHasAnAuditableScopeExclusionButPartialGenePageIsNotNegativeCoverage() throws Exception {
        String configured = System.getProperty("pvs1.nativeExpertRoot");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null);
        Path root = Path.of(configured), folder = root.resolve("local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102");
        byte[] raw = Files.readAllBytes(folder.resolve("native-target-source-captures/ACADVL-erepo-pass1-page1.json"));
        assertEquals("9b6bc5a37c4bf85c031cbae9931ad24d275ba8f026350d8e5ed902d0a022ed8d", CodingTranscriptMaterialSupply.sha(raw));
        byte[] modelRaw = Files.readAllBytes(root.resolve("local/runtime/bp7-evidence-workbench/materials/bp3-published-20261017/transcript-models/ENST00000356839.10.model.json"));
        assertEquals("cbf89a2bc131ddb60a7d9ebf65b4312a6b73c67ef6e237aa4f1445a6cf14dfb9", CodingTranscriptMaterialSupply.sha(modelRaw));
        var model = (ObjectNode) mapper.readTree(modelRaw);
        var context = mapper.createObjectNode().put("moi", "AUTOSOMAL_RECESSIVE");
        context.putObject("gene").put("hgncId", "HGNC:92").put("symbol", "ACADVL");
        context.putObject("disease").put("mondoId", "MONDO:0008723");
        // Scope replay only; the target below is an engineering coordinate, not a patient result.
        context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "17")
                .put("position", 7219985).put("reference", "A").put("alternate", "G");
        var sources = new Pvs1StartLossPublicSources(mapper, true, (url, timeout, limit) ->
                url.getPath().contains("/summary/") && url.getQuery().endsWith("&pg=1")
                        ? new ProteinRegionHttpTransport.Response(200, Map.of(), raw)
                        : new ProteinRegionHttpTransport.Response(404, Map.of(), new byte[0]));
        var prep = new Pvs1StartLossAutomaticPreparation(mapper, () -> null, sources, () -> null);
        var sequence = mapper.createObjectNode().put("cdsPosition", 1).put("candidateRestartCdsPosition", 16);
        assertEquals("ATG", model.path("cdsSequence").asText().substring(15, 18));
        var upstream = prep.upstream(context, model, sequence);
        assertEquals(100, upstream.path("records").size());
        assertFalse(upstream.path("sourceEnumerationComplete").asBoolean());
        String id = "eea05423-c79b-4da3-8287-653465cfc9d1";
        com.fasterxml.jackson.databind.JsonNode original = null, assessed = null;
        for (var row : mapper.readTree(raw).path("data")) if (id.equals(row.path("uuid").asText())) original = row;
        for (var row : upstream.path("records")) if (id.equals(row.path("recordId").asText())) assessed = row;
        assertNotNull(original); assertNotNull(assessed);
        // Independent original genomic HGVS agrees with c.62+5 derived from exon geometry.
        assertTrue(original.path("hgvs").toString().contains("NC_000017.11:g.7220051G>A"));
        assertEquals(7220051, assessed.at("/scopeExclusion/genomicPosition").asInt());
        assertEquals("EXCLUDED", assessed.path("disposition").asText());
        assertEquals("OUTSIDE_UPSTREAM_INTERVAL", assessed.path("reasonCode").asText());
        assertFalse(assessed.path("nativeAssessment").isObject());
        assertFalse(upstream.path("negativeCoverageGranted").asBoolean());
        var archive = mapper.createObjectNode(); archive.set("upstreamReview", upstream); archive.set("originals", prep.originals());
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = temporary;
        persistence.saveAndRead(mapper.writeValueAsString(upstream), mapper.writeValueAsString(archive));
        Files.writeString(folder.resolve("native-intron-scope-private.json"), mapper.writeValueAsString(archive));
        String output = System.getProperty("varvis.pvs1.startloss.output");
        if (output != null) {
            var prepared = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-automatic-preparation-v1")
                    .put("methodVersion", "1.2.0").put("runtimeEligible", false).put("scientificEvaluationPerformed", false).put("status", "INCOMPLETE");
            prepared.set("upstreamReview", upstream);
            prepared.putArray("issues").add("PVS1_START_LOSS_AUTO_UPSTREAM_COVERAGE_INCOMPLETE");
            Files.createDirectories(Path.of(output));
            Files.writeString(Path.of(output).resolve("AUTO_NATIVE_INTRON_SCOPE.json"),
                    mapper.writeValueAsString(Pvs1StartLossAutomaticPreparation.publicSummary(mapper, prepared)));
        }
    }

    @Test void realIntronicDeletionOriginalAgreesWithIndependentlyAcquiredRefseqGeometry() throws Exception {
        String configured = System.getProperty("pvs1.nativeExpertRoot");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null);
        Path folder = Path.of(configured).resolve("local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102");
        Path captures = folder.resolve("native-target-source-captures");
        byte[] raw = Files.readAllBytes(captures.resolve("SLC9A6-erepo-pass1-page1.json"));
        assertEquals("744ac738cac6bd928900f1a9b9c138fc4ff4c651e22eb504317daec9dcb62ead", CodingTranscriptMaterialSupply.sha(raw));
        byte[] metadata = Files.readAllBytes(captures.resolve("SLC9A6-dataset_report.json"));
        byte[] products = Files.readAllBytes(captures.resolve("SLC9A6-product_report.json"));
        assertEquals("dae0fc38031fda7da0066d5e2c40bd5cfe32a744b5fb1daca43b8f74c2660e21", CodingTranscriptMaterialSupply.sha(metadata));
        assertEquals("4c6375444bdec9b4b1ca32520778d5a39ee1099bb22768ec93847cfd1d724129", CodingTranscriptMaterialSupply.sha(products));
        var context = mapper.createObjectNode().put("moi", "X_LINKED_RECESSIVE");
        context.putObject("gene").put("hgncId", "HGNC:11079").put("symbol", "SLC9A6");
        context.putObject("disease").put("mondoId", "MONDO:0010278");
        context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "X")
                .put("position", 135985503).put("reference", "A").put("alternate", "G");
        var sources = new Pvs1StartLossPublicSources(mapper, true, (url, timeout, limit) -> {
            if (url.getPath().endsWith("/dataset_report")) return new ProteinRegionHttpTransport.Response(200, Map.of(), metadata);
            if (url.getPath().endsWith("/product_report")) return new ProteinRegionHttpTransport.Response(200, Map.of(), products);
            if (url.getPath().contains("/summary/")) return new ProteinRegionHttpTransport.Response(200, Map.of(), raw);
            return new ProteinRegionHttpTransport.Response(404, Map.of(), new byte[0]);
        });
        var inventory = Pvs1StartLossRefSeqInventory.read(mapper, context, sources.refseqReport("SLC9A6", false), sources.refseqReport("SLC9A6", true));
        ObjectNode geometry = null;
        for (var tx : inventory.path("transcripts")) if ("NM_006359.3".equals(tx.path("transcript").asText())) geometry = (ObjectNode) tx;
        assertNotNull(geometry, inventory.toPrettyString());
        assertEquals("AVAILABLE", geometry.path("structureStatus").asText());
        assertFalse(geometry.path("sequenceVerified").asBoolean()); // Scope geometry only, not a qualified runtime coding model.
        com.fasterxml.jackson.databind.JsonNode original = null;
        String id = "db0297b9-0514-429a-a4f0-7933c2cd4d05";
        for (var row : mapper.readTree(raw).path("data")) if (id.equals(row.path("uuid").asText())) original = row;
        assertNotNull(original);
        var mapped = Pvs1StartLossReferenceMapping.intronicDeletion(geometry, original.path("hgvs"));
        assertTrue(mapped.resolved(), mapped.reason()); assertEquals(135998099, mapped.first()); assertEquals(135998103, mapped.last());
        var prep = new Pvs1StartLossAutomaticPreparation(mapper, () -> null, sources, () -> null);
        var sequence = mapper.createObjectNode().put("cdsPosition", 1).put("candidateRestartCdsPosition", 73);
        var upstream = prep.upstream(context, geometry, sequence);
        com.fasterxml.jackson.databind.JsonNode assessed = null;
        for (var row : upstream.path("records")) if (id.equals(row.path("recordId").asText())) assessed = row;
        assertNotNull(assessed); assertEquals("EXCLUDED", assessed.path("disposition").asText());
        assertEquals("OUTSIDE_UPSTREAM_INTERVAL", assessed.path("reasonCode").asText());
        assertEquals(135998103, assessed.at("/scopeExclusion/genomicEnd").asInt());
        assertFalse(upstream.path("negativeCoverageGranted").asBoolean());
        var archive = mapper.createObjectNode(); archive.set("upstreamReview", upstream); archive.set("originals", prep.originals());
        archive.set("sourceGeometry", geometry); archive.set("sourceInventory", inventory);
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = temporary;
        persistence.saveAndRead(mapper.writeValueAsString(upstream), mapper.writeValueAsString(archive));
        Files.writeString(folder.resolve("native-intron-deletion-private.json"), mapper.writeValueAsString(archive));
        String output = System.getProperty("varvis.pvs1.startloss.output");
        if (output != null) {
            var prepared = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-automatic-preparation-v1")
                    .put("methodVersion", "1.2.0").put("runtimeEligible", false).put("scientificEvaluationPerformed", false).put("status", "INCOMPLETE");
            prepared.set("upstreamReview", upstream); prepared.putArray("issues").add("PVS1_START_LOSS_AUTO_UPSTREAM_COVERAGE_INCOMPLETE");
            Files.writeString(Path.of(output).resolve("AUTO_NATIVE_INTRON_DELETION.json"),
                    mapper.writeValueAsString(Pvs1StartLossAutomaticPreparation.publicSummary(mapper, prepared)));
        }
    }

    @Test void nativeReferenceCanBeQualifiedWithoutClaimingGlobalAbsenceAndAllOriginalsSurviveHistory() throws Exception {
        String configured = System.getProperty("pvs1.nativeExpertRoot");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null);
        Path root = Path.of(configured), folder = root.resolve("local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102");
        var nativeFixture = new Pvs1StartLossOriginalRuleReplayTest().nativeFixture();
        byte[] modelRaw = Files.readAllBytes(root.resolve("local/resources/ten-rule-generalization-20260913/models/PTEN-NM_000314.8-model-v1.json"));
        assertEquals("ee18138b0911a5118f4e3a024858da918ea9349cd146de073a7da6a4f01c181f", CodingTranscriptMaterialSupply.sha(modelRaw));
        var model = (ObjectNode) mapper.readTree(modelRaw);
        assertEquals("FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE", model.at("/transcriptBridge/status").asText());
        assertEquals(model.at("/sequenceVerification/cdsSha256").asText(), CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText()));
        for (var record : model.path("sourceRecords")) {
            String name = record.path("sourceId").asText().split(":", 2)[1];
            Path original = root.resolve("local/resources/ten-rule-generalization-20260913/sources/" + name
                    + (record.path("sourceId").asText().startsWith("RefSeq:") ? ".gb" : ".json"));
            assertEquals(record.path("sha256").asText(), CodingTranscriptMaterialSupply.sha(Files.readAllBytes(original)));
        }
        var context = mapper.createObjectNode().put("moi", "AUTOSOMAL_DOMINANT");
        context.putObject("gene").put("hgncId", "HGNC:9588").put("symbol", "PTEN");
        context.putObject("disease").put("mondoId", "MONDO:0017623");
        context.putObject("transcript").put("accession", "NM_000314").put("version", 8);
        context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "10")
                .put("position", 87864470).put("reference", "A").put("alternate", "G");
        String cds = model.path("cdsSequence").asText();
        int restart = 0;
        for (int i = 3; i + 3 <= cds.length(); i += 3) if (cds.substring(i, i + 3).equals("ATG")) { restart = i + 1; break; }
        assertEquals(103, restart, "The native CDS determines the upstream interval before reference selection");
        assertEquals(Pvs1StartLossReferenceMapping.map(model, "NM_000314.8:c.70G>A").key(),
                Pvs1StartLossReferenceMapping.genomic(model, 87864539, "G", "A").key());
        var clinvarCalls = new java.util.concurrent.atomic.AtomicInteger();
        var sources = new Pvs1StartLossPublicSources(mapper, true, (url, timeout, limit) -> {
            if (url.getHost().equals("eutils.ncbi.nlm.nih.gov")) clinvarCalls.incrementAndGet();
            String file = null;
            if (url.getPath().contains("/summary/") && url.getQuery().endsWith("&pg=1")) file = "erepo-pten-page1.json";
            else if (url.getPath().endsWith("/interpretation/" + nativeFixture.summary().path("uuid").asText())) file = "erepo-pten-independent-lead.json";
            else if (url.toString().endsWith("&id=VCV000185200")) file = "VCV000185200.xml";
            return file == null ? new ProteinRegionHttpTransport.Response(404, Map.of(), new byte[0])
                    : new ProteinRegionHttpTransport.Response(200, Map.of(), Files.readAllBytes(folder.resolve(file)));
        });
        var guard = PredictionSpecializationGuard.load(mapper, root.resolve("local/resources/acmg/ba1/cspec-20260914-a01/snapshot.json"),
                "b2d2e43216ea15758490a1eb97d55eccd8ded98494e60230102754091fde32e0",
                root.resolve("local/development/handoffs/A01_BA1_SHARED_REVIEW_20260915/active/capture"));
        var preparation = new Pvs1StartLossAutomaticPreparation(mapper, () -> null, sources, () -> guard);
        var sequence = mapper.createObjectNode().put("cdsPosition", 1).put("candidateRestartCdsPosition", restart);
        var p = new VariantAnalysisProperties(); p.getClinVar().setEnabled(true);
        p.getClinVar().setGrch38File(root.resolve("local/resources/clinvar/clinvar.vcf.gz").toString());
        try (var header = new java.io.BufferedReader(new java.io.InputStreamReader(new java.util.zip.GZIPInputStream(
                Files.newInputStream(root.resolve("local/resources/clinvar/clinvar.vcf.gz"))), java.nio.charset.StandardCharsets.UTF_8))) {
            assertTrue(header.lines().limit(8).anyMatch(line -> line.equals("##fileDate=2026-03-21")));
        }
        p.getClinVar().setVersion("2026-03-21");
        var scan = new Pvs1StartLossClinVarSearch(mapper, p).query(context, model, restart);
        assertEquals("ENUMERATED", scan.path("status").asText(), scan.toPrettyString());
        String scanRaw = mapper.writeValueAsString(scan); scan.put("sourceSha256", CodingTranscriptMaterialSupply.sha(scanRaw));
        var result = preparation.upstreamWithCoverage(context, model, sequence, scan);
        assertEquals("NOT_REQUIRED", scan.at("/candidateReview/status").asText(),
                "Do not fetch every unrelated SCV merely to prove absence after a valid witness exists");
        assertEquals(1, clinvarCalls.get());
        ObjectNode reference = null;
        for (var row : result.path("records")) if (nativeFixture.summary().path("uuid").equals(row.path("recordId"))) reference = (ObjectNode) row;
        if (reference == null) fail("Captured expert reference was not retained in the upstream result");
        reference = java.util.Objects.requireNonNull(reference);
        assertEquals("QUALIFYING", reference.path("disposition").asText(), reference.toPrettyString());
        assertEquals("WITNESS_VALIDATED", result.path("status").asText());
        assertFalse(result.path("complete").asBoolean()); assertFalse(result.path("negativeCoverageGranted").asBoolean());
        assertEquals(0, reference.at("/nativeAssessment/clinvarAssertions/assertions/3/additionalEvidenceCount").asInt());
        var prepared = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-automatic-preparation-v1")
                .put("methodVersion", "1.2.0").put("runtimeEligible", false).put("scientificEvaluationPerformed", false).put("status", "INCOMPLETE");
        prepared.set("upstreamReview", result); prepared.set("clinvarSearch", scan);
        prepared.putArray("issues").add("PVS1_START_LOSS_AUTO_FUNCTIONAL_TRANSCRIPT_COVERAGE_UNRESOLVED");
        var archive = mapper.createObjectNode(); archive.set("preparation", prepared);
        var originals = preparation.originals();
        originals.putObject(CodingTranscriptMaterialSupply.sha(scanRaw)).put("rawJson", scanRaw);
        originals.putObject(CodingTranscriptMaterialSupply.sha(modelRaw)).put("rawJson", new String(modelRaw, java.nio.charset.StandardCharsets.UTF_8));
        archive.set("originals", originals);
        assertTrue(originals.path("645ab2738f8fc07ec2a2b9ed500eeb4bfb45d89019e176b49af893c1ee01edc0").has("rawXml"));
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = temporary;
        var publicView = Pvs1StartLossAutomaticPreparation.publicSummary(mapper, prepared);
        persistence.saveAndRead(mapper.writeValueAsString(publicView), mapper.writeValueAsString(archive));
        String output = System.getProperty("varvis.pvs1.startloss.output");
        if (output != null) {
            Files.createDirectories(Path.of(output));
            Files.writeString(Path.of(output).resolve("AUTO_NATIVE_CLINVAR_REFERENCE.json"), mapper.writeValueAsString(publicView));
            Files.writeString(Path.of(output).resolveSibling("native-reference-private.json"), mapper.writeValueAsString(archive));
        }
    }
}
