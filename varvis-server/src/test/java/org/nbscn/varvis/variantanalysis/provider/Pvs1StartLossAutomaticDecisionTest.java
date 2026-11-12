package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Full preparation over fictional native-shaped sources. No qualified material
 * or classifier output is injected; these are engineering, not clinical cases. */
class Pvs1StartLossAutomaticDecisionTest {
    @TempDir Path temporary;
    @ParameterizedTest @ValueSource(strings={"complete", "inferred", "refseqPage", "differentStart", "clinvarUnavailable", "expertUnavailable", "productionSynthetic"})
    void qualifiedNativeSourceShapesReachSupportingButIncompletePrerequisitesCannot(String change) throws Exception {
        var helper = new Pvs1StartLossAutomaticPreparationTest(); helper.root = temporary;
        var mapper = helper.mapper; var f = helper.base.fixture(1); var d = f.data(); var model = d.model;
        if (change.equals("productionSynthetic")) {
            d.context.put("dataClass", "PRIVACY_BOUNDED_RUNTIME");
            d.context.put("contextId", helper.base.adapter.canonicalContextId(d.context));
        }
        var supply = spy(helper.technicalSupply(f));
        var inventory = mapper.createObjectNode().put("coverage", "ALL_SOURCE_TRANSCRIPTS_BEFORE_MODEL_FILTER").put("sourceCoverageComplete", true);
        inventory.putArray("transcripts").add(model.deepCopy());
        if (change.equals("differentStart")) {
            var other = model.deepCopy().put("transcript", "NM_999999.1");
            ((ObjectNode) other.at("/exons/0")).put("genomicStart", model.at("/exons/0/genomicStart").asInt() + 1);
            inventory.withArray("transcripts").add(other);
        }
        doReturn(inventory).when(supply).startLossInventory(any()); // technical inventory only
        var refseq = new Pvs1StartLossRefSeqInventoryTest();
        var metadata = refseq.metadata(); ((ObjectNode) metadata.at("/reports/0/gene")).put("symbol", model.path("geneSymbol").asText());
        var products = refseq.products(false); var product = (ObjectNode) products.at("/reports/0/product");
        product.put("symbol", model.path("geneSymbol").asText());
        var tx = (ObjectNode) product.at("/transcripts/0"); tx.put("accession_version", model.path("transcript").asText()).put("length", model.path("cdsLength").asInt());
        tx.withObject("cds").put("accession_version", model.path("transcript").asText());
        ((ObjectNode) tx.at("/cds/range/0")).put("begin", 1).put("end", model.path("cdsLength").asInt());
        var exons = ((ObjectNode) tx.at("/genomic_locations/0")).putArray("exons"); int order = 0;
        for (var exon : model.path("exons")) exons.addObject().put("order", ++order).put("begin", exon.path("genomicStart").asInt())
                .put("end", exon.path("genomicEnd").asInt()).put("orientation", "plus");
        if (change.equals("refseqPage")) products.put("next_page_token", "not-fetched");
        var sources = new Pvs1StartLossPublicSources(mapper, true, (url, timeout, limit) -> {
            byte[] raw;
            if (url.getHost().equals("eutils.ncbi.nlm.nih.gov")) raw = (">" + model.path("transcript").asText() + "\n" + model.path("cdsSequence").asText() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            else raw = mapper.writeValueAsBytes(url.getPath().contains("dataset_report") ? metadata
                    : url.getPath().contains("product_report") ? products : helper.document());
            return new ProteinRegionHttpTransport.Response(200, Map.of(), raw);
        });
        var local = new Pvs1StartLossClinVarSearchTest(); local.root = temporary;
        var search = new Pvs1StartLossClinVarSearch(mapper, local.properties(), (file, regions) -> {
            if (change.equals("clinvarUnavailable")) throw new java.io.IOException("synthetic unavailable");
            return List.of();
        });
        var facts = Pvs1KnowledgeCatalogTest.facts(mapper, List.of(d.policy));
        var raw = (ObjectNode) facts.at("/records/0/sourceRecord");
        raw.put("gene symbol", model.path("geneSymbol").asText()).put("molecular mechanism support", change.equals("inferred") ? "inferred" : "evidence")
                .put("molecular mechanism evidence", "123 -> function: biochemical").put("comments", "").put("review", "")
                .put("variant consequence", "absent gene product").put("variant types", "frameshift_variant")
                .put("publications", "123").put("date of last review", "2026-01-01 00:00:00+00:00");
        ((ObjectNode) facts.at("/records/0")).put("sourceRecordSha256", Pvs1KnowledgeCatalog.sourceRecordSha(mapper, raw));
        byte[] factBytes = mapper.writeValueAsBytes(facts); String factSha = CodingTranscriptMaterialSupply.sha(factBytes);
        var adoption = mapper.createObjectNode().put("schemaVersion", "pvs1-knowledge-adoption-v1").put("catalogId", "SYNTHETIC")
                .put("catalogVersion", "1").put("sourceCandidatesSha256", factSha).put("sourceDataClass", "SYNTHETIC"); adoption.putArray("entries");
        var catalog = new Pvs1KnowledgeCatalog(mapper, facts, factSha, adoption, "b".repeat(64));
        var doc = helper.base.bundle(f); doc.withObject("materials").remove("pvs1StartLossMaterials");
        byte[] bundleBytes = mapper.writeValueAsBytes(doc); Path bundlePath = temporary.resolve("bundle.json"); Files.write(bundlePath, bundleBytes);
        var bundle = ReviewedRuleBundle.loadPvs1Selected(mapper, bundlePath, CodingTranscriptMaterialSupply.sha(bundleBytes), helper.base.adapter.canonicalSha256(doc),
                new Pvs1TranscriptMaterialSupply(mapper, supply), catalog);
        var guard = new Pvs1StartLossPublishedRuleTest().nativeGuard();
        var preparation = new Pvs1StartLossAutomaticPreparation(mapper, () -> supply, sources,
                () -> { if (change.equals("expertUnavailable")) throw new IllegalArgumentException("unavailable"); return guard; }, search);
        var prepared = preparation.prepareForEvaluation(d.context, d.candidate, bundle);
        var result = prepared.decision();
        if (!change.equals("complete")) {
            if (change.equals("productionSynthetic")) {
                assertEquals("AVAILABLE", prepared.preparation().at("/sequence/status").asText());
                assertEquals("CURATED_MECHANISM_SUPPORTED", prepared.preparation().at("/mechanismAssessment/status").asText());
            }
            assertNull(result, prepared.preparation().toPrettyString()); return;
        }
        assertNotNull(result, prepared.preparation().toPrettyString());
        assertEquals("SUPPORTING", result.at("/evaluation/appliedStrength").asText());
        assertEquals("PVS1_START_LOSS_NO_UPSTREAM_PATHOGENIC_REFERENCE", result.at("/evaluation/evaluationResult/reasonCode").asText());
        assertEquals("GENERIC_FACTS_QUALIFIED", prepared.preparation().path("status").asText());
        assertTrue(preparation.originals().has(result.at("/automaticStartLossFacts/verifiedModelSha256").asText()));
        result.set("startLossAutomaticPreparation", prepared.preparation());
        var publicView = Pvs1SelectedBatch.publicSummary(mapper, result);
        var archive = mapper.createObjectNode(); archive.set("result", result); archive.set("originals", preparation.originals());
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = Files.createDirectory(temporary.resolve("history"));
        persistence.saveAndRead(mapper.writeValueAsString(publicView), mapper.writeValueAsString(archive));
        new Pvs1StartLossPublishedRuleTest().assertSelectedBatch(d, bundle, preparation, "SUPPORTING",
                Files.createDirectory(temporary.resolve("batch")));
        String output = System.getProperty("varvis.pvs1.startloss.output");
        if (output != null) {
            var row = mapper.createObjectNode().put("criterionId", "ACMG_AMP_2015:PVS1").put("status", "MET").put("appliedStrength", "SUPPORTING")
                    .put("reasonCode", "PVS1_START_LOSS_NO_UPSTREAM_PATHOGENIC_REFERENCE");
            row.putObject("details").set("pvs1Facts", publicView);
            Files.writeString(Path.of(output).resolve("AUTOMATIC_SYNTHETIC_SUPPORTING.json"), mapper.writeValueAsString(row));
        }
    }
}
