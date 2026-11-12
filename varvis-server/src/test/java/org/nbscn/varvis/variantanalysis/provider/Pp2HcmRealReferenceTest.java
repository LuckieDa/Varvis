package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.service.CodingTranscriptReferenceReader;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger;

/** Real full GENCODE/reference material with a synthetic SNV and stored VEP-shaped annotation.
 * No new VEP run, independent RefSeq RNA, patient observation or phenotype-clear qualification is claimed. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Pp2HcmRealReferenceTest {
    private static final String REFERENCE_SHA = "8d5e868ddc6d9600b82cd7b7e625c240a43f369f6f1a9f16b0c516eb781e7988";
    @TempDir static Path work;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    ObjectNode context, model;
    VariantInterpretation candidate;
    CodingTranscriptMaterialSupply materials;
    CodingTranscriptMaterialSupply.Material material;
    PublishedCriterionKnowledge knowledge;
    Pp2OtherPhenotypeSource source;
    Pp2OtherPhenotypeSource.Observed observed;

    @BeforeAll void existingRealReferenceAndExplicitSyntheticCandidate() throws Exception {
        String configured = System.getProperty("t05.hcm.sourceRoot"), file = System.getProperty("t05.hcm.knowledge");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null && file != null, "Explicit existing root and compiled method required");
        if (configured == null || file == null) throw new IllegalStateException("EXPLICIT_SOURCE_PATHS_REQUIRED");
        Path root = Path.of(configured).toRealPath();
        var properties = new VariantAnalysisProperties(); var normalization = properties.getNormalization();
        normalization.setGrch38Reference(root.resolve("local/resources/reference/grch38.p14.primary-chr.fa").toString());
        normalization.setGrch38ReferenceSha256(REFERENCE_SHA); normalization.setGrch38ProfileId("grch38-primary-chr");
        normalization.setGrch38ContigAliasFile(root.resolve("varvis-server/config/grch38-canonical-to-chr-contigs.tsv").toString());
        var ledger = ExplicitContextSourceLedger.load(mapper, root.resolve("local/private/receipts/reference38-runtime.json"),
                "c7f794b9f7831c6130ea190729777473931cb4046993027aec52f16711a31a7b");
        var reader = new CodingTranscriptReferenceReader(mapper, properties, root.resolve("local/resources"), ledger, "reference");
        var index = new GencodeTranscriptIndex(mapper, root.resolve("local/resources/gencode/derived/v38-transcripts-a1-inventory-20261102"),
                "44a09eb4bfc2e1cf5c4e5d7bc90b56958866e2f3fe6b9fce6697554c87e8cc42");
        context = new RevelPredictionObservationTest().input("0.99", "GE", "0.7").fixture().context().deepCopy();
        context.put("dataClass", "SYNTHETIC");
        ((ObjectNode) context.path("gene")).put("hgncId", "HGNC:12010").put("symbol", "TPM1");
        ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0005045").put("label", "HCM interpretation hypothesis, synthetic candidate");
        context.put("moi", "AUTOSOMAL_DOMINANT");
        ((ObjectNode) context.path("transcript")).put("accession", "ENST00000403994").put("version", 9)
                .put("selectionPolicy", "EXACT_GENCODE_ON_REAL_REFERENCE_ENGINEERING_REPLAY").put("annotationSourceVersion", "116.0");
        String placeholder = reader.read("15", 63042833, 1).bases();
        setAllele(63042833, placeholder, "A".equals(placeholder) ? "G" : "A");
        model = index.model(context, reader);
        assertEquals(855, model.path("cdsLength").asInt()); assertEquals(284, model.path("proteinLength").asInt());
        assertEquals(1217, model.path("transcriptLength").asInt()); assertEquals(10, model.path("exons").size());
        assertFalse(model.at("/sequenceVerification/independentRnaVerification").asBoolean(true));
        String cds = model.path("cdsSequence").asText();
        int coding = 4; String refCodon = cds.substring(3, 6), changedCodon = null; char alt = 'N';
        for (char proposed : new char[]{'A', 'C', 'G', 'T'}) {
            var changed = new StringBuilder(refCodon); changed.setCharAt(0, proposed);
            char refAa = TruncatingSequence.translateCodon(refCodon), altAa = TruncatingSequence.translateCodon(changed.toString());
            if (refAa != altAa && altAa != '*') { alt = proposed; changedCodon = changed.toString(); break; }
        }
        if (changedCodon == null) throw new IllegalStateException("SYNTHETIC_MISSENSE_CONTROL_UNAVAILABLE");
        int genomic = 0, ordinal = 0;
        for (var exon : model.path("exons")) if (coding >= exon.path("cdsStart").asInt() && coding <= exon.path("cdsEnd").asInt()) {
            genomic = exon.path("genomicStart").asInt() + coding - exon.path("cdsStart").asInt(); ordinal = exon.path("ordinal").asInt(); break;
        }
        assertTrue(genomic > 0); String ref = reader.read("15", genomic, 1).bases(); assertEquals(cds.substring(3, 4), ref);
        setAllele(genomic, ref, Character.toString(alt));
        char refAa = TruncatingSequence.translateCodon(refCodon), altAa = TruncatingSequence.translateCodon(changedCodon);
        var annotation = mapper.createObjectNode().put("geneSymbol", "TPM1").put("transcriptAccession", "ENST00000403994")
                .put("transcriptId", "ENST00000403994").put("hgvsCoding", "ENST00000403994.9:c.4" + ref + ">" + alt)
                .put("hgvsProtein", "ENSP00000385107.4:p." + TruncatingSequence.aminoName(refAa) + "2" + TruncatingSequence.aminoName(altAa))
                .put("consequence", "missense_variant").put("exon", ordinal + "/10").put("proteinPosition", "2")
                .put("aminoAcids", refAa + "/" + altAa).put("codons", refCodon + "/" + changedCodon)
                .put("source", "VEP_SYNTHETIC_T05_REAL_REFERENCE_CONTROL").put("status", "COMPLETED")
                .put("version", "116.0").put("timestamp", "2026-09-01T00:00:00Z");
        var key = new NormalizedVariantKey("GRCh38", "15", genomic, ref, Character.toString(alt));
        candidate = new VariantInterpretation("SYNTHETIC_HCM_SCOPE_REAL_REFERENCE", new VariantQcObservation(key,
                "SYNTHETIC_HCM_SCOPE_REAL_REFERENCE", "0/1", null, null, null, null, "PASS", "SNV", null), List.of(), List.of());
        candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
        materials = new CodingTranscriptMaterialSupply(mapper, null, "NOT_CONFIGURED", reader, index);
        material = materials.resolve(context, candidate); assertTrue(material.ready(), material.snapshot().toPrettyString());
        byte[] bytes = Files.readAllBytes(Path.of(file)); knowledge = PublishedCriterionKnowledge.load(mapper, Path.of(file), CodingTranscriptMaterialSupply.sha(bytes));
        Path project = Path.of("..").toAbsolutePath().normalize();
        source = new Pp2OtherPhenotypeSource(mapper, root.resolve("local/tools/alphagenome/python/python.exe"), project,
                work.resolve("observed-source"), List.of(), List.of()); observed = source.observe();
    }
    private void setAllele(int position, String ref, String alt) {
        var variant = (ObjectNode) context.path("variant");
        var allele = mapper.createObjectNode().put("assembly", "GRCh38").put("contig", "15").put("position", position).put("reference", ref).put("alternate", alt);
        for (String field : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) variant.set(field, allele.deepCopy());
        if (variant.has("legacyCanonicalVariantId")) variant.put("legacyCanonicalVariantId", new NormalizedVariantKey("GRCh38", "15", position, ref, alt).canonicalId());
        variant.put("variantType", "SNV"); var normalization = (ObjectNode) variant.path("normalization");
        normalization.put("referenceSequenceSha256", REFERENCE_SHA).put("refValidation", "MATCH")
                .put("method", "SYNTHETIC_NATIVE_SNV_ON_QUALIFIED_REAL_REFERENCE").put("methodVersion", "1.0.0-test-only");
        variant.put("alleleId", adapter.canonicalAlleleId(allele, normalization));
        context.put("contextId", adapter.canonicalVersionedContextId(context)); adapter.validateVersionedContext(context);
    }
    @Test void fullRealModelCanMatchOnlyTheExplicitSourceFamilyThroughTheVerifiedMissenseEntry() {
        var before = context.deepCopy();
        assertEquals("PUBLISHED_KNOWLEDGE_TRANSCRIPT_NOT_COVERED", knowledge.select(GeneralPp2Runtime.CRITERION, context, model).path("reasonCode").asText());
        var selected = knowledge.selectForVerifiedMissense(GeneralPp2Runtime.CRITERION, context, material);
        assertEquals("READY", selected.path("status").asText(), selected.toPrettyString());
        assertEquals("NM_001018005.2", selected.path("declaredTranscript").asText());
        assertEquals("ENST00000403994.9", selected.path("executionTranscript").asText());
        assertEquals("ENST00000403994", selected.at("/sourceTranscriptScope/sourceAccession").asText());
        assertFalse(selected.at("/sourceTranscriptScope/refseqEquivalenceGranted").asBoolean(true));
        assertFalse(selected.has("transcriptBridge")); assertFalse(selected.has("codingTranscriptTransfer")); assertEquals(before, context);
    }
    @Test void currentVersionProofCannotBeInheritedByAnotherVersionOrAccession() {
        for (String change : List.of("VERSION", "ACCESSION", "GENE", "OTHER_CRITERION")) {
            var altered = context.deepCopy(); String criterion = GeneralPp2Runtime.CRITERION;
            switch (change) {
                case "VERSION" -> ((ObjectNode) altered.path("transcript")).put("version", 10);
                case "ACCESSION" -> ((ObjectNode) altered.path("transcript")).put("accession", "ENST000001");
                case "GENE" -> ((ObjectNode) altered.path("gene")).put("symbol", "OTHER");
                case "OTHER_CRITERION" -> criterion = "ACMG_AMP_2015:PM1";
                default -> fail(change);
            }
            assertNotEquals("READY", knowledge.selectForVerifiedMissense(criterion, altered, material).path("status").asText(), change);
        }
    }
    @Test void actualEmptyObservationRemainsUnknownAndIsNotReopenedPerVariant() throws Exception {
        var runtime = new GeneralPp2Runtime(mapper, materials, knowledge, true, source);
        var original = context.deepCopy(); var observation = observed.snapshot();
        var out = runtime.evaluate(context, candidate, mapper.createObjectNode().put("candidateId", "engineering-hcm").put("hypothesisId", "engineering-hcm-context"), observed);
        assertEquals("NOT_EVALUATED", out.path("status").asText(), out.toPrettyString());
        assertEquals("PP2_HCM_OTHER_PHENOTYPE_REVIEW_INCOMPLETE", out.path("reasonCode").asText());
        assertEquals("NONE", out.at("/evaluation/appliedStrength").asText());
        assertEquals("UNKNOWN", out.at("/otherPhenotypeReview/outcome").asText()); assertTrue(out.path("hcmReviewBindingVerified").asBoolean());
        assertEquals(adapter.canonicalSha256(context), out.at("/otherPhenotypeReview/binding/contextSha256").asText());
        assertEquals(out.at("/hcmPolicyBinding/preparedContextSha256"), out.at("/hcmPolicyBinding/executionContextSha256"));
        assertEquals("ENST00000403994.9", out.at("/publicSummary/sourceTranscriptScope/executionTranscript").asText());
        assertEquals(original, context); assertEquals(observation, observed.snapshot());
        String artifacts = System.getProperty("t05.hcm.realArtifacts");
        if (artifacts != null) {
            Path directory = Path.of(artifacts); Files.createDirectories(directory);
            var result = mapper.createObjectNode().put("layer", "REAL_REFERENCE_SYNTHETIC_VARIANT_AND_STORED_ANNOTATION_MODULE_NE_NOT_NORMAL_RUN")
                    .put("liveVepExecuted", false).put("independentRefseqRnaClaimed", false).put("phenotypeClearGranted", false);
            result.set("result", out); result.set("context", context); result.set("model", model);
            Files.write(directory.resolve("real-reference-hcm-module-ne.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
        }
    }
    @Test void missingSourceAndMissingObservationHaveSpecificNonAdoptionReasons() {
        var missing = new GeneralPp2Runtime(mapper, materials, knowledge, true).evaluate(context, candidate, mapper.createObjectNode().put("candidateId", "engineering-hcm").put("hypothesisId", "engineering-hcm-context"));
        assertEquals("PP2_HCM_OTHER_PHENOTYPE_SOURCE_UNAVAILABLE", missing.path("reasonCode").asText());
        var unprepared = new GeneralPp2Runtime(mapper, materials, knowledge, true, source).evaluate(context, candidate, mapper.createObjectNode().put("candidateId", "engineering-hcm").put("hypothesisId", "engineering-hcm-context"));
        assertEquals("PP2_HCM_OTHER_PHENOTYPE_OBSERVATION_REQUIRED", unprepared.path("reasonCode").asText());
        assertEquals("NOT_EVALUATED", missing.path("status").asText()); assertEquals("NOT_EVALUATED", unprepared.path("status").asText());
        assertFalse(unprepared.has("otherPhenotypeReview"));
    }
    @Test void declaredMultipleBackgroundsBindTheExplicitHypothesisWithoutChoosingFromArrayOrder() {
        var allele = (ObjectNode) context.at("/variant/evidenceQueryAllele");
        var hints = mapper.createObjectNode().put("scope", "ENGINEERING_DISCOVERY_ONLY_NO_PATIENT_OR_SOURCE_GRANT");
        var first = new Pp2OtherPhenotypeSource.DiscoveryTarget("engineering-hcm", "background-first", allele,
                "HGNC:12010", "TPM1", "MONDO:0005045", "AUTOSOMAL_DOMINANT", hints);
        var second = new Pp2OtherPhenotypeSource.DiscoveryTarget("engineering-hcm", "background-second", allele,
                "HGNC:12010", "TPM1", "MONDO:0005045", "AUTOSOMAL_DOMINANT", hints);
        var declared = source.observe(List.of(first, second)); var before = declared.snapshot();
        var runtime = new GeneralPp2Runtime(mapper, materials, knowledge, true, source);
        var out = runtime.evaluate(context, candidate, mapper.createObjectNode().put("candidateId", "engineering-hcm")
                .put("hypothesisId", "background-second"), declared);
        assertEquals("NOT_EVALUATED", out.path("status").asText());
        assertEquals("UNKNOWN", out.at("/otherPhenotypeReview/outcome").asText());
        assertTrue(out.path("hcmReviewBindingVerified").asBoolean());
        assertEquals("background-second", out.at("/otherPhenotypeReview/binding/hypothesisId").asText());
        var wrong = runtime.evaluate(context, candidate, mapper.createObjectNode().put("candidateId", "engineering-hcm")
                .put("hypothesisId", "background-not-declared"), declared);
        assertEquals("NOT_EVALUATED", wrong.path("status").asText());
        assertEquals("INVALID", wrong.at("/otherPhenotypeReview/outcome").asText());
        var missing = runtime.evaluate(context, candidate, mapper.createObjectNode().put("candidateId", "engineering-hcm"), declared);
        assertEquals("PP2_HCM_EXPLICIT_HYPOTHESIS_REQUIRED", missing.path("reasonCode").asText());
        assertFalse(missing.has("otherPhenotypeReview")); assertEquals(before, declared.snapshot());
        assertEquals("NONE", out.at("/evaluation/appliedStrength").asText());
        assertEquals("NONE", wrong.at("/evaluation/appliedStrength").asText());
    }
}
