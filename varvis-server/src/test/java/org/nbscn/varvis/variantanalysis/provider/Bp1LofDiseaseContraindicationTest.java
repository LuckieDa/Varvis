package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

/** Source/transport matrix fixed before implementation. The default source envelope and every
 * 363bp sequence/annotation/reference are SYNTHETIC. Only the opt-in source is real captured CSpec;
 * that test still uses a synthetic sequence fixture and never claims a real variant or LoF assay. */
class Bp1LofDiseaseContraindicationTest {
    @TempDir Path root;

    /** Shared with the combination test; reuses the consequence test's actual tiny material supply. */
    static final class Fixture {
        final Bp1TruncatingConsequenceTest.Fixture sequence;
        final ObjectMapper mapper;
        final RuntimeEvidenceV2Adapter adapter;
        final Path root;
        final ObjectNode context, model;
        final VariantInterpretation candidate;
        CodingTranscriptMaterialSupply supply;
        ObjectNode doc;
        Fixture(Path root) throws Exception { this(root, "PTPN11", "HGNC:9644", "ENST000001.4"); }
        Fixture(Path root, String symbol, String hgnc, String transcript) throws Exception {
            this.root = root;
            sequence = new Bp1TruncatingConsequenceTest.Fixture(root, "STOP", 1);
            mapper = sequence.mapper; adapter = sequence.adapter; context = sequence.context; model = sequence.model;
            model.put("geneSymbol", symbol).put("hgncId", hgnc).put("transcript", transcript);
            ((ObjectNode) context.path("gene")).put("symbol", symbol).put("hgncId", hgnc);
            ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0021060").put("label", "SYNTHETIC_ENGINEERING_RASOPATHY_CONTEXT");
            context.put("moi", "AUTOSOMAL_DOMINANT");
            int dot = transcript.lastIndexOf('.');
            String accession = transcript.substring(0, dot);
            ((ObjectNode) context.path("transcript")).put("accession", accession).put("version", Integer.parseInt(transcript.substring(dot + 1)))
                    .put("annotationSourceVersion", "116.0");
            sequence.annotation.put("geneSymbol", symbol).put("transcriptAccession", transcript).put("transcriptId", accession)
                    .put("transcriptSource", transcript.startsWith("NM_") ? "RefSeq" : "ENSEMBL")
                    .put("hgvsCoding", transcript + ":c.181C>T");
            sequence.canonicalizeContext(); sequence.applyAnnotation(); sequence.writeSupply();
            candidate = sequence.candidate; supply = sequence.supply;
            var template = new PublishedCriterionKnowledgeTest().document();
            var original = (ObjectNode) template.at("/specifications/0/original");
            original.withObject("/ld").remove("File");
            original.withObject("/entContent").put("notes", Pp2PublishedAssessment.NOTES)
                    .put("description", "SYNTHETIC_SOURCE_ENVELOPE_WITH_VERBATIM_KNOWN_GRAMMAR");
            ((ObjectNode) original.at("/ld/Gene/0/entContent/HGNC")).put("symbol", symbol).put("hgnc_id", hgnc);
            var gene = (ObjectNode) original.at("/ld/RuleSet/0/entContent/genes/0");
            gene.put("gene", symbol).put("preferredTranscript", transcript);
            ((ObjectNode) gene.at("/diseases/0")).put("preferredMondoId", "MONDO:0021060")
                    .put("preferredModeOfInheritance", "Autosomal dominant inheritance");
            var clause = (ObjectNode) original.at("/ld/CriteriaCode/0/entContent"); clause.removeAll();
            clause.put("label", "BP1").put("applicability", "Applicable").put("baseStrength", "Benign")
                    .put("defaultStrength", "Benign Supporting").put("instructionsToUse", Bp1PublishedPolicy.TRUNCATING_INSTRUCTION)
                    .put("additionalComments", Bp1PublishedPolicy.TRUNCATING_COMMENT);
            clause.putArray("gene").add(symbol);
            clause.putArray("strengthDescriptor").addObject().put("applicability", "Applicable").put("status", "approved")
                    .put("strength", "Supporting").put("text", Bp1PublishedPolicy.TRUNCATING_COMMENT).put("instructionsToUse", "");
            doc = document(mapper, original, "a".repeat(64), "/data/0",
                    "https://cspec.genome.network/cspec/SequenceVariantInterpretation/id/GN900");
        }
        CodingTranscriptMaterialSupply.Material material() { return supply.resolveStructure(context, candidate); }
        Bp1TruncatingConsequence.Observation observation() {
            var material = material(); return Bp1TruncatingConsequence.observe(mapper, context, candidate, material);
        }
        PublishedCriterionKnowledge catalog() throws Exception {
            byte[] bytes = mapper.writeValueAsBytes(doc);
            Path file = root.resolve("bp1-source-" + UUID.randomUUID() + ".json"); Files.write(file, bytes);
            return PublishedCriterionKnowledge.load(mapper, file, CodingTranscriptMaterialSupply.sha(bytes));
        }
        Bp1LofDiseaseContraindication.Assessment assessment() throws Exception {
            var material = material();
            var observation = Bp1TruncatingConsequence.observe(mapper, context, candidate, material);
            return Bp1LofDiseaseContraindication.assess(mapper, catalog(), context, material, observation);
        }
        void refreshPins() {
            var spec = (ObjectNode) doc.at("/specifications/0"); var row = (ObjectNode) doc.at("/records/0");
            spec.put("entitySha256", adapter.canonicalSha256(spec.path("original")));
            row.put("clauseSha256", adapter.canonicalSha256(spec.path("original").at(row.path("clausePointer").asText())));
        }
    }

    static ObjectNode document(ObjectMapper mapper, JsonNode original, String rawSha, String pointer, String url) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var doc = mapper.createObjectNode().put("schemaVersion", PublishedCriterionKnowledge.SCHEMA).put("methodVersion", "1.0.0")
                .put("adoptionMode", "PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS").put("clinicalValidationCompleted", false)
                .put("policyId", "SOURCE_TRANSPORT_TEST_ONLY").put("policyVersion", "1.0.0");
        String key = original.path("entId").asText() + "@" + original.at("/entContent/version").asText();
        var spec = doc.putArray("specifications").addObject().put("specificationKey", key)
                .put("entitySha256", adapter.canonicalSha256(original));
        spec.set("original", original.deepCopy());
        spec.putObject("source").put("rawSha256", rawSha).put("jsonPointer", pointer).put("sourceUrl", url);
        String clausePointer = null;
        for (int i = 0; i < original.at("/ld/CriteriaCode").size(); i++)
            if ("BP1".equals(original.at("/ld/CriteriaCode/" + i + "/entContent/label").asText())) {
                if (clausePointer != null) throw new IllegalArgumentException("Duplicate test BP1 source");
                clausePointer = "/ld/CriteriaCode/" + i;
            }
        if (clausePointer == null) throw new IllegalArgumentException("Missing test BP1 source");
        var gene = original.at("/ld/RuleSet/0/entContent/genes/0"); String symbol = gene.path("gene").asText();
        String hgnc = "";
        for (var g : original.at("/ld/Gene"))
            if (symbol.equals(g.at("/entContent/HGNC/symbol").asText())) hgnc = g.at("/entContent/HGNC/hgnc_id").asText();
        var row = doc.putArray("records").addObject().put("recordId", "bp1-condition-source")
                .put("criterionId", Bp1LofDiseaseContraindication.CRITERION).put("specificationKey", key)
                .put("clausePointer", clausePointer).put("clauseSha256", adapter.canonicalSha256(original.at(clausePointer)))
                .put("scopePointer", "/ld/RuleSet/0/entContent/genes/0/diseases/0")
                .put("status", "UNSUPPORTED_CONDITIONS").put("reasonCode", "BP1_PUBLISHED_TRUNCATING_LOF_CONDITIONS_REQUIRED");
        row.putObject("scope").put("hgncId", hgnc).put("mondoId", gene.at("/diseases/0/preferredMondoId").asText())
                .put("moi", "AUTOSOMAL_DOMINANT").put("assembly", "GRCh38").put("transcript", gene.path("preferredTranscript").asText());
        row.putObject("interpretation").put("type", Bp1PublishedPolicy.TRUNCATING_TYPE).put("version", "1.0.0")
                .put("appliedStrength", "SUPPORTING").put("germlineOnly", true).put("requiresNoEstablishedLofDiseaseCorrelation", true);
        return doc;
    }

    @Test void admittedNamedRelationIsContraindicationOnlyAndCannotAwardOrProveAlleleLof() throws Exception {
        var f = new Fixture(root); var a = f.assessment(); var out = a.snapshot();
        assertTrue(a.established(), out::toPrettyString); assertTrue(a.sourceChecked());
        assertEquals("CHECKED", out.path("sourceCheckStatus").asText());
        assertEquals("PTPN11", out.at("/relation/geneSymbol").asText());
        assertEquals("HGNC:9644", out.at("/relation/hgncId").asText());
        assertEquals("metachondromatosis", out.at("/relation/diseaseLabel").asText());
        assertEquals("LOSS_OF_FUNCTION", out.at("/relation/molecularMechanism").asText());
        assertFalse(out.path("absenceQualificationGranted").asBoolean(true));
        assertFalse(out.path("scoringPermissionGranted").asBoolean(true));
        assertFalse(out.path("lossOfFunctionEstablishedForQueryAllele").asBoolean(true));
        assertFalse(out.has("evaluation")); assertFalse(out.has("appliedStrength"));
        assertEquals(f.context.at("/variant/evidenceQueryAllele"), out.path("queryAllele"));
        assertEquals("GN900@1.0", out.at("/sourceBinding/specificationKey").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"MAP2K1", "OTHER_GENE", "GENEX"})
    void explicitOtherNamedGeneIsCheckedUnknownRatherThanAbsenceOrSourceError(String symbol) throws Exception {
        var f = new Fixture(root, symbol, "HGNC:6840", "ENST000001.4");
        var a = f.assessment();
        assertFalse(a.established()); assertTrue(a.sourceChecked());
        assertEquals("PTPN11", a.snapshot().at("/sourceMention/namedGeneSymbol").asText());
        assertEquals(symbol, a.snapshot().at("/sourceMention/selectedGeneSymbol").asText());
        assertFalse(a.snapshot().at("/sourceMention/subjectMatchesSelectedScope").asBoolean(true));
        assertTrue(a.snapshot().has("sourceBinding")); assertFalse(a.snapshot().has("relation"));
        assertEquals("UNKNOWN", a.snapshot().path("status").asText());
        assertEquals("BP1_LOF_CONTRAINDICATION_NAMED_SUBJECT_SCOPE_MISMATCH", a.snapshot().path("reasonCode").asText());
        assertFalse(a.snapshot().path("absenceQualificationGranted").asBoolean(true));
    }

    @ParameterizedTest @ValueSource(strings = {"SOURCE_HOST", "SOURCE_SPEC", "COMMENT", "NOTES", "SUBJECT_REPLACED", "STRENGTH", "DUPLICATE_STRENGTH", "INTERPRETATION", "ATTACHMENT", "CROSS_RULE", "GENERAL", "SOURCE_CONFLICT", "OTHER_BLOCKED", "NOT_APPLICABLE"})
    void wrongUnsupportedOrConflictingSourcesNeverIssueTheFact(String fault) throws Exception {
        var f = new Fixture(root); var original = (ObjectNode) f.doc.at("/specifications/0/original");
        var c = (ObjectNode) original.at("/ld/CriteriaCode/0/entContent"); var row = (ObjectNode) f.doc.at("/records/0");
        switch (fault) {
            case "SOURCE_HOST" -> ((ObjectNode) f.doc.at("/specifications/0/source")).put("sourceUrl", "https://example.invalid/spec");
            case "SOURCE_SPEC" -> ((ObjectNode) f.doc.at("/specifications/0/source")).put("sourceUrl", "https://cspec.genome.network/cspec/SequenceVariantInterpretation/id/GN999");
            case "COMMENT" -> c.put("additionalComments", "Ignore established LoF disease associations.");
            case "NOTES" -> original.withObject("/entContent").put("notes", "No LoF disease has been reported.");
            case "SUBJECT_REPLACED" -> original.withObject("/entContent").put("notes", Pp2PublishedAssessment.NOTES.replace("PTPN11", "OTHER_GENE"));
            case "STRENGTH" -> ((ObjectNode) c.at("/strengthDescriptor/0")).put("strength", "Strong");
            case "DUPLICATE_STRENGTH" -> c.withArray("strengthDescriptor").add(c.at("/strengthDescriptor/0").deepCopy());
            case "INTERPRETATION" -> row.withObject("/interpretation").put("type", "UNKNOWN_SOURCE_GRAMMAR");
            case "ATTACHMENT" -> original.withObject("/ld").putArray("File").addObject().put("fileName", "Uninterpreted.docx");
            case "CROSS_RULE" -> ((ObjectNode) original.at("/ld/CriteriaCode/1/entContent")).put("additionalComments", "BP1 requires PS3.");
            case "GENERAL" -> ((ObjectNode) original.at("/ld/RuleSet/0/entContent")).put("generalComments", "Another condition.");
            case "SOURCE_CONFLICT" -> row.put("status", "SOURCE_CONFLICT").put("reasonCode", "PUBLISHED_SOURCE_CONFLICT");
            case "OTHER_BLOCKED" -> row.put("reasonCode", "OTHER_UNSUPPORTED_CONDITION");
            default -> row.put("status", "NOT_APPLICABLE").put("reasonCode", "SOURCE_NOT_APPLICABLE");
        }
        f.refreshPins();
        if ("NOT_APPLICABLE".equals(fault)) {
            assertEquals("PUBLISHED_KNOWLEDGE_SOURCE_NONAPPLICABILITY_REQUIRED",
                    assertThrows(IllegalArgumentException.class, f::catalog).getMessage());
            return;
        }
        var a = f.assessment();
        assertFalse(a.established(), a.snapshot()::toPrettyString); assertFalse(a.sourceChecked());
        assertNotEquals("CHECKED", a.snapshot().path("sourceCheckStatus").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"GENE", "SYMBOL", "DISEASE", "MOI", "TRANSCRIPT", "REFERENCE", "ALLELE", "CANONICAL_ID"})
    void currentContextCannotBorrowAnObservationFromAnotherBinding(String fault) throws Exception {
        var f = new Fixture(root); var m = f.material(); var obs = Bp1TruncatingConsequence.observe(f.mapper, f.context, f.candidate, m);
        assertTrue(obs.ready(), obs.snapshot()::toPrettyString); var context = f.context.deepCopy();
        switch (fault) {
            case "GENE" -> ((ObjectNode) context.path("gene")).put("hgncId", "HGNC:6840");
            case "SYMBOL" -> ((ObjectNode) context.path("gene")).put("symbol", "MAP2K1");
            case "DISEASE" -> ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0000001");
            case "MOI" -> context.put("moi", "AUTOSOMAL_RECESSIVE");
            case "TRANSCRIPT" -> ((ObjectNode) context.path("transcript")).put("version", 5);
            case "REFERENCE" -> ((ObjectNode) context.at("/variant/normalization")).put("referenceSequenceSha256", "0".repeat(64));
            case "ALLELE" -> ((ObjectNode) context.at("/variant/evidenceQueryAllele")).put("alternate", "A");
            default -> context.put("contextId", "context:v2:tampered");
        }
        if (!"CANONICAL_ID".equals(fault)) context.put("contextId", f.adapter.canonicalContextId(context));
        var a = Bp1LofDiseaseContraindication.assess(f.mapper, f.catalog(), context, m, obs);
        assertFalse(a.established()); assertFalse(a.sourceChecked()); assertEquals("INVALID", a.snapshot().path("sourceCheckStatus").asText());
    }

    @Test void sourceScopeMissIsUnavailableAndCannotBecomeAnAbsenceFinding() throws Exception {
        var f = new Fixture(root); var row = (ObjectNode) f.doc.at("/records/0");
        ((ObjectNode) f.doc.at("/specifications/0/original/ld/RuleSet/0/entContent/genes/0/diseases/0")).put("preferredMondoId", "MONDO:0000001");
        row.withObject("/scope").put("mondoId", "MONDO:0000001"); f.refreshPins();
        var a = f.assessment(); assertFalse(a.established()); assertFalse(a.sourceChecked());
        assertEquals("UNAVAILABLE", a.snapshot().path("sourceCheckStatus").asText());
    }

    @Test void duplicateNamedGeneOrCompetingPoliciesStayAmbiguous() throws Exception {
        var f = new Fixture(root); var genes = ((ObjectNode) f.doc.at("/specifications/0/original/ld")).withArray("Gene");
        genes.add(genes.get(0).deepCopy()); f.refreshPins(); var a = f.assessment();
        assertFalse(a.established()); assertEquals("INVALID", a.snapshot().path("sourceCheckStatus").asText());
        f = new Fixture(root.resolve("another"));
        var duplicate = (ObjectNode) f.doc.at("/records/0").deepCopy(); duplicate.put("recordId", "second-source"); f.doc.withArray("records").add(duplicate);
        assertFalse(f.assessment().established());
    }

    @ParameterizedTest @ValueSource(strings = {"ENTITY", "CLAUSE", "RAW", "STATE"})
    void unacceptedSourcePinsAndStatesAreRejectedByTheExistingLoader(String fault) throws Exception {
        var f = new Fixture(root);
        switch (fault) {
            case "ENTITY" -> ((ObjectNode) f.doc.at("/specifications/0/original")).put("tamper", true);
            case "CLAUSE" -> ((ObjectNode) f.doc.at("/records/0")).put("clauseSha256", "0".repeat(64));
            case "RAW" -> ((ObjectNode) f.doc.at("/specifications/0/source")).put("rawSha256", "NOT_A_PIN");
            default -> { ((ObjectNode) f.doc.at("/specifications/0/original/entContent/states/0")).put("name", "Draft"); f.refreshPins(); }
        }
        assertThrows(IllegalArgumentException.class, f::catalog);
    }

    @Test void missingWrongPurposeFailedOrChangedActualMaterialCannotMintAContraindication() throws Exception {
        var f = new Fixture(root); var m = f.material(); var obs = Bp1TruncatingConsequence.observe(f.mapper, f.context, f.candidate, m);
        assertTrue(obs.ready(), obs.snapshot()::toPrettyString);
        assertFalse(Bp1LofDiseaseContraindication.assess(f.mapper, f.catalog(), f.context, null, obs).established());
        assertFalse(Bp1LofDiseaseContraindication.assess(f.mapper, f.catalog(), f.context, m, null).established());
        assertFalse(Bp1LofDiseaseContraindication.assess(f.mapper, null, f.context, m, obs).established());
        var missense = f.supply.resolve(f.context, f.candidate); assertFalse(missense.ready());
        assertFalse(Bp1LofDiseaseContraindication.assess(f.mapper, f.catalog(), f.context, missense, obs).established());
        f.model.put("modelVersion", "changed-model"); f.sequence.writeSupply(); f.supply = f.sequence.supply;
        var otherModel = f.material(); assertTrue(otherModel.ready());
        var invalid = Bp1LofDiseaseContraindication.assess(f.mapper, f.catalog(), f.context, otherModel, obs);
        assertFalse(invalid.established()); assertEquals("INVALID", invalid.snapshot().path("sourceCheckStatus").asText());
    }

    @Test void snapshotsAreImmutableAndPublicSourceBindingHasNoRawOrMachinePaths() throws Exception {
        var f = new Fixture(root); var a = f.assessment(); assertTrue(a.established(), a.snapshot()::toPrettyString);
        var copy = a.snapshot(); copy.put("status", "UNKNOWN"); copy.withObject("/relation").put("geneSymbol", "MAP2K1");
        assertTrue(a.established()); assertEquals("PTPN11", a.snapshot().at("/relation/geneSymbol").asText());
        var fields = a.snapshot().path("sourceBinding");
        assertEquals(Set.of("catalogRawSha256", "catalogPolicyId", "catalogPolicyVersion", "sourceRawSha256", "sourceUrl", "sourceJsonPointer",
                "recordId", "specificationKey", "entitySha256", "clausePointer", "clauseSha256", "scopePointer", "relationPointer", "relationTextUtf8Sha256", "scope"),
                new java.util.HashSet<>(java.util.stream.StreamSupport.stream(((Iterable<String>) () -> fields.fieldNames()).spliterator(), false).toList()));
        assertFalse(fields.toString().contains(root.toString())); assertFalse(fields.has("rawJson")); assertFalse(fields.has("rawRef"));
        var m = f.material(); var observation = Bp1TruncatingConsequence.observe(f.mapper, f.context, f.candidate, m);
        observation.snapshot().put("contextCanonicalSha256", "0".repeat(64));
        assertTrue(Bp1LofDiseaseContraindication.assess(f.mapper, f.catalog(), f.context, m, observation).established());
    }

    @Test void callerLabelsCannotConvertOtherNamedSubjectOrNoRecordIntoNoLofQualification() throws Exception {
        var f = new Fixture(root, "OTHER_GENE", "HGNC:6840", "ENST000001.4");
        f.doc.put("hiScore", 0).put("noEstablishedLofCorrelation", true).put("mechanism", "GAIN_OF_FUNCTION");
        var a = f.assessment(); assertFalse(a.established()); assertTrue(a.sourceChecked());
        assertFalse(a.snapshot().path("absenceQualificationGranted").asBoolean(true));
        f.doc.withArray("records").removeAll();
        a = f.assessment(); assertFalse(a.established()); assertFalse(a.sourceChecked());
    }

    @Test @EnabledIfSystemProperty(named = "t05.ras.raw", matches = ".+")
    void realCapturedCspecSourceWithSyntheticSequenceKeepsItsNamedSubjectAndNoScientificAdoption() throws Exception {
        Path file = Path.of(java.util.Objects.requireNonNull(System.getProperty("t05.ras.raw")));
        byte[] raw = Files.readAllBytes(file); String pin = CodingTranscriptMaterialSupply.sha(raw);
        assertEquals("0c112a41986f1e0b85491717e1d1b474e38cf639e68c3579a21294d5ad164d6c", pin);
        var mapper = new ObjectMapper(); var originals = mapper.readTree(raw).path("data");
        for (int index : new int[] {0, 2}) {
            var original = originals.get(index); var gene = original.at("/ld/RuleSet/0/entContent/genes/0");
            String symbol = gene.path("gene").asText(); String hgnc = original.at("/ld/Gene/0/entContent/HGNC/hgnc_id").asText();
            var f = new Fixture(root.resolve("real-source-" + index), symbol, hgnc, gene.path("preferredTranscript").asText());
            f.doc = document(f.mapper, original, pin, "/data/" + index,
                    "https://cspec.genome.network/cspec/SequenceVariantInterpretation/id?ids=GN043%2CGN044%2CGN045%2CGN046%2CGN047&detail=high");
            var a = f.assessment(); assertTrue(a.sourceChecked(), a.snapshot()::toPrettyString);
            assertEquals(index == 0, a.established(), "Real source relation; sequence/annotation remain SYNTHETIC");
            assertFalse(a.snapshot().path("absenceQualificationGranted").asBoolean(true));
            assertFalse(a.snapshot().path("scoringPermissionGranted").asBoolean(true));
            if (a.established()) assertEquals(pin, a.snapshot().at("/sourceBinding/sourceRawSha256").asText());
            var combined = Bp1TruncatingAssessment.assess(f.mapper, f.catalog(), f.context, f.candidate, f.material());
            assertEquals(index == 0 ? "NOT_MET" : "NOT_EVALUATED", combined.status(), combined.snapshot()::toPrettyString);
            assertEquals("NONE", combined.snapshot().path("appliedStrength").asText());
            String artifacts = System.getProperty("t05.bp1.condition-artifacts");
            if (artifacts != null) {
                var receipt = f.mapper.createObjectNode().put("schemaVersion", "t05-bp1-actual-condition-replay-v1")
                        .put("sourceLayer", "REAL_CAPTURED_CSPEC_ORIGINAL")
                        .put("sequenceLayer", "SYNTHETIC_ENGINEERING_NOT_A_REPORTED_VARIANT")
                        .put("rawOriginalSha256", pin).put("entityPointer", "/data/" + index)
                        .put("realReportedVariant", false).put("clinicalValidationCompleted", false);
                receipt.set("actualSourceAssessment", a.snapshot());
                receipt.set("actualResult", combined.snapshot());
                receipt.set("actualPublicSummary", combined.publicSummary());
                Path output = Files.createDirectories(Path.of(artifacts));
                Files.write(output.resolve("real-cspec-synthetic-sequence-" + index + ".json"),
                        f.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
            }
        }
    }
    @Test void neitherRawFactsNorConstructorEnvelopeCanMintTheTypedContraindication() throws Exception {
        var f = new Fixture(root); var assessment = f.assessment();
        assertTrue(assessment.established(), assessment.snapshot()::toPrettyString);
        var facts = assessment.snapshot();
        var envelope = f.mapper.createObjectNode(); envelope.set("value", facts);
        for (var input : java.util.List.of(facts, envelope)) {
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> f.mapper.treeToValue(input, Bp1LofDiseaseContraindication.Assessment.class));
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> new ObjectMapper().treeToValue(input, Bp1LofDiseaseContraindication.Assessment.class));
        }
    }

}
