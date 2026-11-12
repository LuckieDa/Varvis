package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Source transport, opaque use binding and rejection of the historical project-authored
 * template. No source-native negative interpretation or real scientific adoption is claimed. */
class Bp1NoEstablishedLofSupplyTest {
    @TempDir Path root;
    static final Clock NOW = at("2026-11-04");
    static Clock at(String day) { return Clock.fixed(Instant.parse(day + "T12:00:00Z"), ZoneOffset.UTC); }
    static String statement(String symbol, String hgnc, String review, String through) {
        return "No established germline loss-of-function disease association is known for " + symbol + " (" + hgnc
                + ") across all human diseases. Expert review date: " + review + ". Valid through: " + through + ".";
    }

    /** Package fixture for the real GeneralBp1 runtime test; every source here is explicitly synthetic.
     * GN900 retains complete historical BP1 notes, GN901 independently supplies the new exact grammar. */
    static final class Fixture {
        final Bp1LofDiseaseContraindicationTest.Fixture base;
        final ObjectMapper mapper;
        final RuntimeEvidenceV2Adapter adapter;
        final ObjectNode context, doc;
        ObjectNode reviewOriginal;
        Fixture(Path root) throws Exception { this(root, "MAP2K1", "HGNC:6840"); }
        Fixture(Path root, String symbol, String hgnc) throws Exception {
            base = new Bp1LofDiseaseContraindicationTest.Fixture(root, symbol, hgnc, "ENST000001.4");
            mapper = base.mapper; adapter = base.adapter; context = base.context; doc = base.doc;
            addSyntheticReview(symbol, hgnc, "2026-11-01", "2026-11-30");
        }
        void addSyntheticReview(String symbol, String hgnc, String reviewDate, String validThrough) throws Exception {
            var specs = (ArrayNode) doc.path("specifications");
            for (int i = specs.size() - 1; i >= 0; i--) if ("GN901@1.0".equals(specs.get(i).path("specificationKey").asText())) specs.remove(i);
            reviewOriginal = ((ObjectNode) specs.get(0).path("original")).deepCopy();
            reviewOriginal.put("entId", "GN901");
            reviewOriginal.withObject("/entContent").put("version", "1.0")
                    .put("description", "SYNTHETIC_INDEPENDENT_REVIEW_SOFTWARE_CONTRACT_ONLY")
                    .put("notes", statement(symbol, hgnc, reviewDate, validThrough));
            var released = reviewOriginal.withObject("/entContent").putArray("states").addObject().put("name", "Released").put("current", true);
            released.putObject("event").put("name", "cspec-released").put("timeStamp", reviewDate + "T00:00:00Z");
            reviewOriginal.withObject("/ld").remove("File");
            ((ObjectNode) reviewOriginal.at("/ld/Gene/0/entContent/HGNC")).put("symbol", symbol).put("hgnc_id", hgnc);
            var spec = specs.addObject().put("specificationKey", "GN901@1.0"); spec.set("original", reviewOriginal);
            spec.putObject("source").put("jsonPointer", "/data/0")
                    .put("sourceUrl", "https://cspec.genome.network/cspec/SequenceVariantInterpretation/id/GN901");
            rebindRaw();
        }
        ObjectNode reviewSpec() {
            for (var spec : doc.path("specifications")) if ("GN901@1.0".equals(spec.path("specificationKey").asText())) return (ObjectNode) spec;
            throw new IllegalStateException("Missing synthetic review spec");
        }
        void rebindRaw() throws Exception {
            var raw = mapper.createObjectNode(); raw.putArray("data").add(reviewOriginal.deepCopy());
            setRaw(mapper.writeValueAsString(raw));
            reviewSpec().put("entitySha256", adapter.canonicalSha256(reviewOriginal));
            reviewSpec().withObject("/source").put("rawSha256", doc.at("/bp1LofAssociationRawSources/0/rawSha256").asText());
        }
        void setRaw(String text) {
            doc.putArray("bp1LofAssociationRawSources").addObject().put("rawJsonUtf8", text)
                    .put("rawSha256", CodingTranscriptMaterialSupply.sha(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        String compiledSha() throws Exception { return CodingTranscriptMaterialSupply.sha(mapper.writeValueAsBytes(doc)); }
        Bp1NoEstablishedLofSupply build() throws Exception {
            var admitted = new HashMap<String, ObjectNode>();
            for (var spec : doc.path("specifications")) admitted.put(spec.path("specificationKey").asText(), (ObjectNode) spec);
            return Bp1NoEstablishedLofSupply.fromCatalog(mapper, doc, admitted, compiledSha());
        }
        PublishedCriterionKnowledge catalog() throws Exception { return catalog(NOW); }
        PublishedCriterionKnowledge catalog(Clock clock) throws Exception { return new PublishedCriterionKnowledge(mapper, doc, compiledSha(), clock); }
        CodingTranscriptMaterialSupply.Material material() { return base.material(); }
        Bp1NoEstablishedLofSupply.Qualification assess() throws Exception { return assess(NOW); }
        Bp1NoEstablishedLofSupply.Qualification assess(Clock clock) throws Exception {
            var material = material();
            var observation = Bp1TruncatingConsequence.observe(mapper, context, base.candidate, material);
            assertTrue(material.ready(), material.snapshot()::toPrettyString);
            assertTrue(observation.ready(), observation.snapshot()::toPrettyString);
            return build().assess(context, material, observation, clock);
        }
    }

    @Test void historicalProjectTemplateIsNotAScientificNegativeReview() throws Exception {
        var f = new Fixture(root); var q = f.assess(); var out = q.snapshot();
        assertFalse(q.qualified(), out::toPrettyString);
        assertEquals("UNKNOWN", out.path("status").asText());
        assertEquals("CHECKED", out.path("sourceCheckStatus").asText());
        assertEquals("BP1_NO_LOF_LEGACY_PROJECT_TEMPLATE_NOT_SCIENTIFIC_REVIEW", out.path("reasonCode").asText());
        assertFalse(out.path("absenceQualificationGranted").asBoolean(true));
        assertEquals("SOURCE_NATIVE_SCOPE_NOT_YET_INTERPRETED", out.at("/claim/diseaseScope").asText());
        assertEquals("SOFTWARE_UNSUPPORTED", out.at("/reviewCoverage/status").asText());
        assertEquals(f.adapter.canonicalSha256(f.context), out.at("/useBinding/contextSha256").asText());
        assertFalse(f.build().identity().path("absenceQualificationSupported").asBoolean(true));
    }

    @Test void nativeGeneMetadataAndNoExpiryAreValidTransportButDoNotInventReviewedCoverage() throws Exception {
        var f = new Fixture(root);
        f.reviewOriginal.withObject("/entContent").put("notes", Pp2PublishedAssessment.NOTES); f.rebindRaw();
        var q = f.assess(); var out = q.snapshot();
        assertFalse(q.qualified()); assertEquals("CHECKED", out.path("sourceCheckStatus").asText());
        assertEquals("MAP2K1", out.at("/claim/geneSymbol").asText());
        assertEquals("HGNC:6840", out.at("/claim/hgncId").asText());
        assertEquals("BP1_NO_LOF_NATIVE_REVIEW_SCOPE_NOT_INTERPRETED", out.path("reasonCode").asText());
        assertEquals("NO_SOURCE_EXPIRY_POLICY_ADOPTED", out.at("/validity/status").asText());
        assertFalse(out.at("/validity/expiryRequiredByScientificRule").asBoolean(true));
        assertFalse(out.has("validThrough"));
    }

    @ParameterizedTest @ValueSource(strings = {"Only gain-of-function variants cause this disease.", "ClinGen HI score 0.",
            "No records found.", "No loss of function is known for this disease.", "Expert review only.", "SOMATIC", ""})
    void sourceTextNeverProvidesAComplementOrAnUnreviewedAbsenceShortcut(String text) throws Exception {
        var f = new Fixture(root); f.reviewOriginal.withObject("/entContent").put("notes", text); f.rebindRaw();
        var q = f.assess(); assertFalse(q.qualified());
        assertEquals("UNKNOWN", q.snapshot().path("status").asText());
        assertEquals("SOFTWARE_UNSUPPORTED", q.snapshot().at("/reviewCoverage/status").asText());
    }

    @Test void aChangedWallClockDoesNotExpireKnowledgeIdentityOrApproveAnUninterpretedSource() throws Exception {
        var f = new Fixture(root);
        var before = f.catalog(at("2026-11-04")).identity(); var later = f.catalog(at("2030-01-01")).identity();
        assertEquals(before, later); assertFalse(before.toString().contains("reviewValidityDate"));
        assertFalse(f.assess(at("2026-10-01")).qualified()); assertFalse(f.assess(at("2030-01-01")).qualified());
        assertEquals("2030-01-01", f.assess(at("2030-01-01")).snapshot().path("evaluatedDate").asText());
    }

    @Test void missingOptionalRawLeavesTheLegacyCatalogUsableAndNoAbsenceQualification() throws Exception {
        var f = new Fixture(root); f.doc.remove("bp1LofAssociationRawSources");
        assertEquals("BP1_NO_LOF_SOURCE_NOT_CONFIGURED", f.assess().snapshot().path("reasonCode").asText());
        assertFalse(f.catalog().identity().has("bp1NoEstablishedLofSource"));
    }

    @ParameterizedTest @ValueSource(strings = {"HOST", "POINTER", "ENTITY", "DRAFT", "REPLACED", "LEGACY_TYPE"})
    void nativeTransportIntegrityRemainsRequiredWithoutAScientificExpiryGate(String fault) throws Exception {
        var f = new Fixture(root);
        switch (fault) {
            case "HOST" -> f.reviewSpec().withObject("/source").put("sourceUrl", "https://example.invalid/GN901");
            case "POINTER" -> f.reviewSpec().withObject("/source").put("jsonPointer", "/data/999");
            case "ENTITY" -> f.reviewOriginal.withObject("/entContent").put("description", "CHANGED_WITHOUT_RAW");
            case "DRAFT" -> ((ObjectNode) f.reviewOriginal.at("/entContent/states/0")).put("name", "Draft");
            case "REPLACED" -> f.reviewOriginal.withObject("/entContent").put("legacyReplaced", true);
            default -> f.reviewOriginal.withObject("/entContent").put("legacyFullySuperseded", "true");
        }
        if (!Set.of("HOST", "POINTER", "ENTITY").contains(fault)) f.rebindRaw();
        var q = f.assess(); assertFalse(q.qualified()); assertEquals("INVALID", q.snapshot().path("sourceCheckStatus").asText());
    }

    @Test void anUninterpretedAttachmentIsASoftwareGapAndNotAScientificContraindication() throws Exception {
        var f = new Fixture(root); f.reviewOriginal.withObject("/ld").putArray("File").addObject().put("fileName", "table.pdf"); f.rebindRaw();
        var q = f.assess(); assertFalse(q.qualified());
        assertEquals("CHECKED", q.snapshot().path("sourceCheckStatus").asText());
        assertEquals("BP1_NO_LOF_ATTACHMENT_INTERPRETATION_UNSUPPORTED", q.snapshot().path("reasonCode").asText());
    }

    @Test void snapshotsCannotMintAnOpaqueQualificationAndOriginalBindingRemainsFixed() throws Exception {
        var f = new Fixture(root); var supply = f.build(); var q = f.assess(); var out = q.snapshot();
        out.put("status", "QUALIFIED").put("absenceQualificationGranted", true);
        out.withObject("/useBinding").put("contextSha256", "0".repeat(64));
        assertFalse(q.qualified()); assertEquals(f.adapter.canonicalSha256(f.context), q.snapshot().at("/useBinding/contextSha256").asText());
        assertThrows(com.fasterxml.jackson.databind.exc.InvalidDefinitionException.class,
                () -> f.mapper.readValue(out.toString(), Bp1NoEstablishedLofSupply.Qualification.class));
        assertFalse(supply.identity().path("performsIo").asBoolean(true));
    }
    @ParameterizedTest @ValueSource(strings = {"RAW_BYTES", "RAW_PIN", "ENTITY_REPACK", "POINTER", "DUPLICATE_RAW", "DUPLICATE_JSON_KEY", "TRAILING_JSON", "RAW_ARRAY", "RAW_EXTRA_FIELD", "EMPTY_RAW", "TOO_MANY", "INVALID_UTF16"})
    void alteredOrAmbiguousRawCannotBeRepairedByRecomputingCompiledMetadata(String fault) throws Exception {
        var f = new Fixture(root); var raw = (ObjectNode) f.doc.at("/bp1LofAssociationRawSources/0");
        switch (fault) {
            case "RAW_BYTES" -> raw.put("rawJsonUtf8", raw.path("rawJsonUtf8").asText() + " ");
            case "RAW_PIN" -> raw.put("rawSha256", "0".repeat(64));
            case "ENTITY_REPACK" -> { f.reviewOriginal.withObject("/entContent").put("notes", statement("MAP2K1", "HGNC:6840", "2026-11-02", "2027-01-01"));
                f.reviewSpec().put("entitySha256", f.adapter.canonicalSha256(f.reviewOriginal)); }
            case "POINTER" -> f.reviewSpec().withObject("/source").put("jsonPointer", "/data/1");
            case "DUPLICATE_RAW" -> ((ArrayNode) f.doc.path("bp1LofAssociationRawSources")).add(raw.deepCopy());
            case "DUPLICATE_JSON_KEY" -> { f.setRaw("{\"data\":[],\"data\":[" + f.reviewOriginal + "]}"); f.reviewSpec().withObject("/source").put("rawSha256", f.doc.at("/bp1LofAssociationRawSources/0/rawSha256").asText()); }
            case "TRAILING_JSON" -> { f.setRaw(raw.path("rawJsonUtf8").asText() + " {}"); f.reviewSpec().withObject("/source").put("rawSha256", f.doc.at("/bp1LofAssociationRawSources/0/rawSha256").asText()); }
            case "RAW_ARRAY" -> f.doc.set("bp1LofAssociationRawSources", f.mapper.createObjectNode());
            case "RAW_EXTRA_FIELD" -> raw.put("qualified", true);
            case "EMPTY_RAW" -> ((ArrayNode) f.doc.path("bp1LofAssociationRawSources")).removeAll();
            case "TOO_MANY" -> { for (int i = 0; i < 8; i++) ((ArrayNode) f.doc.path("bp1LofAssociationRawSources")).add(raw.deepCopy()); }
            default -> raw.put("rawJsonUtf8", "{\"data\":\"" + '\ud800' + "\"}");
        }
        var q = f.assess(); assertFalse(q.qualified(), q.snapshot()::toPrettyString);
        assertEquals("INVALID", q.snapshot().path("sourceCheckStatus").asText());
        assertNotNull(f.catalog(), "BP1 raw failure remains criterion-local and does not reject the admitted catalog");
    }

    @ParameterizedTest @ValueSource(strings = {"GENE", "SYMBOL", "DISEASE", "MOI", "TRANSCRIPT", "REFERENCE", "ALLELE", "CANONICAL_ID"})
    void everyUseRechecksCompleteContextAndTypedSequenceBinding(String fault) throws Exception {
        var f = new Fixture(root); var material = f.material();
        var observation = Bp1TruncatingConsequence.observe(f.mapper, f.context, f.base.candidate, material);
        assertTrue(observation.ready(), observation.snapshot()::toPrettyString); var ctx = f.context.deepCopy();
        switch (fault) {
            case "GENE" -> ctx.withObject("/gene").put("hgncId", "HGNC:42");
            case "SYMBOL" -> ctx.withObject("/gene").put("symbol", "OTHER");
            case "DISEASE" -> ctx.withObject("/disease").put("mondoId", "MONDO:0000001");
            case "MOI" -> ctx.put("moi", "AUTOSOMAL_RECESSIVE");
            case "TRANSCRIPT" -> ctx.withObject("/transcript").put("version", 5);
            case "REFERENCE" -> ctx.withObject("/variant/normalization").put("referenceSequenceSha256", "0".repeat(64));
            case "ALLELE" -> ctx.withObject("/variant/evidenceQueryAllele").put("alternate", "A");
            default -> ctx.put("contextId", "context:v2:tampered");
        }
        if (!"CANONICAL_ID".equals(fault)) ctx.put("contextId", f.adapter.canonicalContextId(ctx));
        var q = f.build().assess(ctx, material, observation, NOW);
        assertFalse(q.qualified()); assertEquals("INVALID", q.snapshot().path("sourceCheckStatus").asText());
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "t05.bp1.nativePrepared", matches = ".+")
    void actualNativeRuleCaptureIsReplayedWithoutProjectProseOrExpiryRequirements() throws Exception {
        var f = new Fixture(root);
        Path file = Path.of(java.util.Objects.requireNonNull(System.getProperty("t05.bp1.nativePrepared")));
        byte[] raw = java.nio.file.Files.readAllBytes(file);
        var catalog = PublishedCriterionKnowledge.load(f.mapper, file, CodingTranscriptMaterialSupply.sha(raw));
        var material = f.material();
        var observation = Bp1TruncatingConsequence.observe(f.mapper, f.context, f.base.candidate, material);
        var q = catalog.assessBp1NoEstablishedLof(f.context, material, observation); var out = q.snapshot();
        assertFalse(q.qualified()); assertEquals("CHECKED", out.path("sourceCheckStatus").asText(), out::toPrettyString);
        assertEquals("BP1_NO_LOF_NATIVE_REVIEW_SCOPE_NOT_INTERPRETED", out.path("reasonCode").asText());
        assertEquals("HGNC:6840", out.at("/claim/hgncId").asText());
        assertEquals("NO_SOURCE_EXPIRY_POLICY_ADOPTED", out.at("/validity/status").asText());
        assertFalse(out.path("sourceReviews").isEmpty());
        assertEquals("SOFTWARE_UNSUPPORTED", out.at("/reviewCoverage/status").asText());
        assertFalse(out.path("absenceQualificationGranted").asBoolean(true));
    }
}
