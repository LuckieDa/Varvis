package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.service.CodingTranscriptReferenceReader;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger;
import static org.junit.jupiter.api.Assertions.*;

/** Public allele, independently generated offline VEP, pinned production model,
 * original RNA and native qualified reference. Synthetic patient identity only;
 * neither the target's ClinVar label nor a prequalified PVS1 result is input. */
class Pvs1StartLossNativeTargetTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void realStartLossAlleleReconstructsMet42AndUsesOriginalModerateClause(int codingPosition) throws Exception {
        String configured = System.getProperty("pvs1.nativeExpertRoot"); org.junit.jupiter.api.Assumptions.assumeTrue(configured != null);
        Path root = Path.of(configured), captures = root.resolve("local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102/native-target-source-captures");
        byte[] expectedBytes = Files.readAllBytes(captures.resolve("GAMT-c" + codingPosition + "-expected.json"));
        assertEquals(codingPosition == 1 ? "5ba3be5a5fde937fbd29753d267dcd6fb6af92666d196cff0e501c464263eced"
                : "8530b2f83333f08dd124d7c86b9628f3e9b4aee330e0a6baa7b45a137decfe31", CodingTranscriptMaterialSupply.sha(expectedBytes));
        var expected = mapper.readTree(expectedBytes);
        if (codingPosition == 2) {
            byte[] identity = Files.readAllBytes(captures.resolve("VCV001452750-numeric.xml"));
            assertEquals("fb38e061f5adf99c4dd1731bb7f435cb630d12ed26880b3a2bc3ad0267520765", CodingTranscriptMaterialSupply.sha(identity));
            assertEquals(CodingTranscriptMaterialSupply.sha(identity), expected.path("targetClinVarRawSha256").asText());
            String sourceText = new String(identity, java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(sourceText.contains("NM_000156.6:c.2T&gt;C"));
            assertTrue(sourceText.contains("NC_000019.10:g.1401475A&gt;G"));
        }
        assertEquals("MODERATE", expected.path("expectedStrength").asText());
        var properties = new java.util.Properties();
        for (String name : List.of("evidence-base.properties", "evidence-overrides.properties"))
            try (var reader = Files.newBufferedReader(root.resolve("local/runtime/bp7-evidence-workbench/" + name))) { properties.load(reader); }
        var values = new LinkedHashMap<String, Object>();
        for (var name : properties.stringPropertyNames()) values.put(name, properties.getProperty(name)
                .replace("${VARVIS_PROJECT_ROOT}", root.toString().replace('\\', '/'))
                .replace("${VARVIS_RESOURCE_ROOT}", root.resolve("local/resources").toString().replace('\\', '/'))
                .replace("${VARVIS_TOOL_ROOT}", root.resolve("local/tools").toString().replace('\\', '/')));
        var settings = new org.springframework.boot.context.properties.bind.Binder(
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(values))
                .bind("variant-analysis", org.springframework.boot.context.properties.bind.Bindable.of(VariantAnalysisProperties.class)).get();
        String prefix = "variant-analysis.acmg-business.";
        var ledger = ExplicitContextSourceLedger.load(mapper, Path.of(values.get(prefix + "reference-receipt-file").toString()),
                values.get(prefix + "reference-receipt-sha256").toString());
        var reference = new CodingTranscriptReferenceReader(mapper, settings, root.resolve("local/resources"), ledger,
                values.get(prefix + "reference-receipt-locator").toString());
        Path models = Path.of(values.get(prefix + "transcript-material-directory").toString());
        var supply = new CodingTranscriptMaterialSupply(mapper, models, values.get(prefix + "transcript-material-manifest-sha256").toString(), reference);
        var model = (ObjectNode) mapper.readTree(Files.readAllBytes(models.resolve("ENST00000252288.8.model.json")));
        byte[] rna = Files.readAllBytes(captures.resolve("NM_000156.6.gb"));
        assertEquals("493fccc10f382da12106707e134588cb2530c30e6e3f728f0ac450045a248c98", CodingTranscriptMaterialSupply.sha(rna));
        assertEquals(CodingTranscriptMaterialSupply.sha(rna), model.at("/sourceRecords/0/sha256").asText());
        Path vep = captures.resolve(codingPosition == 1 ? "native-target.vep.jsonl" : "native-target-gamt-c2.vep.jsonl");
        assertEquals(codingPosition == 1 ? "233e5460175fa5e1a3e97cf28a339ab5eb51c4766054650c41da4d45b91bbab4"
                : "b3c57b54164a982e2d480926d194faa7bb4945628e038df87a772cfb0438e891", CodingTranscriptMaterialSupply.sha(Files.readAllBytes(vep)));
        var annotations = new VepJsonOutputParser(mapper).parse(vep, "VEP_OFFLINE", "116.0").stream()
                .filter(tx -> "ENST00000252288.8".equals(tx.getVersionedTranscriptId())).toList();
        assertEquals(1, annotations.size()); assertEquals("ENST00000252288.8:" + expected.path("coding").asText(), annotations.get(0).getHgvsc());
        var helper = new TruncatingNmdPvs1RuntimeTest(); var data = helper.inputs(1, -1, "ATGAAATAA", 9);
        data.model = model;
        var annotation = new ObjectMapper().findAndRegisterModules().valueToTree(annotations.get(0));
        ((ObjectNode) annotation).put("timestamp", annotations.get(0).getTimestamp().toString());
        ((ObjectNode) annotation).remove(List.of("hgvsp", "hgvsc"));
        helper.candidate(data, expected.at("/allele/position").asInt(), expected.at("/allele/reference").asText(),
                expected.at("/allele/alternate").asText(), (ObjectNode) annotation);
        data.context.withObject("transcript").put("accession", "ENST00000252288").put("version", 8);
        data.context.withObject("gene").put("hgncId", "HGNC:4136").put("symbol", "GAMT");
        data.context.withObject("disease").put("mondoId", "MONDO:0012999");
        data.context.put("moi", "AUTOSOMAL_RECESSIVE");
        data.context.withObject("variant").withObject("normalization").put("referenceSequenceSha256", settings.getNormalization().getGrch38ReferenceSha256());
        data.context.withObject("variant").put("alleleId", new RuntimeEvidenceV2Adapter(mapper).canonicalAlleleId(
                data.context.at("/variant/normalizedAllele"), data.context.at("/variant/normalization")));
        data.context.put("contextId", new RuntimeEvidenceV2Adapter(mapper).canonicalContextId(data.context));
        var originalContext = data.context.deepCopy();
        new RuntimeEvidenceV2Adapter(mapper).validateCompleteContext(data.context);
        var materialCheck = supply.resolveStructure(data.context, data.candidate);
        assertTrue(materialCheck.ready(), materialCheck.snapshot().toPrettyString());
        for (String mutation : List.of("partial", "sourceMissing", "wrongExecutionVersion", "cdsMismatch")) {
            var altered = model.deepCopy();
            switch (mutation) {
                case "partial" -> altered.withObject("transcriptBridge").put("status", "CODING_ONLY");
                case "sourceMissing" -> altered.putArray("sourceRecords");
                case "wrongExecutionVersion" -> altered.put("transcript", "ENST00000252288.9");
                case "cdsMismatch" -> altered.put("cdsSequence", "ATGAAATAA");
                default -> throw new AssertionError(mutation);
            }
            assertEquals(originalContext, Pvs1StartLossPublishedRule.sourceContext(originalContext, altered), mutation);
        }
        var catalog = Pvs1KnowledgeCatalog.load(mapper,
                Path.of(properties.getProperty(prefix + "pvs1-knowledge-source-file")), properties.getProperty(prefix + "pvs1-knowledge-source-sha256"),
                Path.of(properties.getProperty(prefix + "pvs1-knowledge-adoption-file")), properties.getProperty(prefix + "pvs1-knowledge-adoption-sha256"));
        var bundle = ReviewedRuleBundle.loadPvs1Selected(mapper, Path.of(properties.getProperty(prefix + "pvs1-bundle-file")),
                properties.getProperty(prefix + "pvs1-bundle-file-sha256"), properties.getProperty(prefix + "pvs1-bundle-sha256"),
                new Pvs1TranscriptMaterialSupply(mapper, supply), catalog);
        var guard = new Pvs1StartLossPublishedRuleTest().nativeGuard();
        var sources = new Pvs1StartLossPublicSources(mapper, true, (url, timeout, limit) -> { throw new AssertionError("Unconditional rule requires no upstream request"); });
        var preparation = new Pvs1StartLossAutomaticPreparation(mapper, () -> supply, sources, () -> guard);
        var prepared = preparation.prepareForEvaluation(data.context, data.candidate, bundle); var result = prepared.decision();
        assertNotNull(result, prepared.preparation().toPrettyString());
        assertEquals(originalContext, data.context);
        assertEquals("MODERATE", result.at("/evaluation/appliedStrength").asText());
        assertEquals(124, prepared.preparation().at("/sequence/candidateRestartCdsPosition").asInt());
        assertEquals("ATG", prepared.preparation().at("/sequence/referenceCodon").asText());
        assertEquals(codingPosition, prepared.preparation().at("/sequence/cdsPosition").asInt());
        assertEquals(codingPosition == 1 ? "GTG" : "ACG", prepared.preparation().at("/sequence/alternateCodon").asText());
        assertEquals("EXACT_FULL_TRANSCRIPT_EQUIVALENCE", prepared.preparation().at("/expertAssertions/transcriptBinding/method").asText());
        result.set("startLossAutomaticPreparation", prepared.preparation());
        var view = Pvs1SelectedBatch.publicSummary(mapper, result);
        var full = mapper.createObjectNode(); full.set("result", result); full.set("originals", preparation.originals());
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = Files.createDirectory(temporary.resolve("history"));
        persistence.saveAndRead(mapper.writeValueAsString(view), mapper.writeValueAsString(full));
        new Pvs1StartLossPublishedRuleTest().assertSelectedBatch(data, bundle, preparation, "MODERATE", Files.createDirectory(temporary.resolve("batch")));
        String output = System.getProperty("varvis.pvs1.startloss.output");
        if (output != null) {
            var row = mapper.createObjectNode().put("criterionId", "ACMG_AMP_2015:PVS1").put("status", "MET").put("appliedStrength", "MODERATE")
                    .put("reasonCode", "PVS1_START_LOSS_EXPLICIT_PUBLISHED_RULE");
            row.putObject("details").set("pvs1Facts", view);
            Files.writeString(Path.of(output).resolve("NATIVE_TARGET_GAMT_C" + codingPosition + "_MODERATE.json"), mapper.writeValueAsString(row));
            Files.writeString(Path.of(output).resolveSibling("native-target-gamt-c" + codingPosition + "-private.json"), mapper.writeValueAsString(full));
        }
    }
}
