package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

import static org.junit.jupiter.api.Assertions.*;

/** Normal production material factory with fictional sequence/source files. No real approval,
 * VEP execution, clinical case or final business/store acceptance is claimed by these tests. */
class ProteinLengthMaterialFactoryTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private static final String CHANGED_CDS = "ATGGAAGCTGAAGGCTTTAAATAA";
    private static final String CHANGED_PROTEIN = "MEAEGFK*";

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void factoryEnablesBoundDelinsWhileEveryLegacyConstructorKeepsItsScope(int strand) throws Exception {
        var f = fixture(strand, "GCTGAA", "7_9delinsGCTGAA", "Pro3delinsAlaGlu", "SMALL_DELINS");
        String pin = modelFiles(f.root, f.data.model);
        var legacy = new CodingTranscriptMaterialSupply(mapper, f.root, pin, f::read);
        var legacyFive = new CodingTranscriptMaterialSupply(mapper, f.root, pin, f::read, null);
        var legacySix = new CodingTranscriptMaterialSupply(mapper, f.root, pin, f::read, null, false);
        for (var old : List.of(legacy, legacyFive, legacySix)) {
            var refused = old.resolveProteinLengthChange(f.data.context, f.data.candidate);
            assertFalse(refused.ready());
            assertEquals("PM4_COMPLEX_ALLELE_UNSUPPORTED", refused.snapshot().path("reasonCode").asText());
            assertFalse(old.complexAllelesEnabled());
            assertFalse(old.proteinLengthChangeIdentity().has("sequenceCapability"));
            assertFalse(old.stopLossIdentity().has("sequenceCapability"));
        }
        var supply = factory(f, pin);
        ObjectNode before = f.data.context.deepCopy();
        JsonNode candidateBefore = mapper.valueToTree(f.data.candidate);
        var material = supply.resolveProteinLengthChange(f.data.context, f.data.candidate);
        assertTrue(material.ready(), material.snapshot()::toPrettyString);
        assertTrue(supply.complexAllelesEnabled());
        assertEquals(legacy.identity(), supply.identity(), "General/missense identity does not borrow the PM4/BP3 capability");
        assertFalse(supply.identity().has("sequenceCapability"));
        assertCapability(supply);
        assertLengthFacts(material.snapshot());
        assertEquals(before, f.data.context);
        assertEquals(candidateBefore, mapper.valueToTree(f.data.candidate));
        artifact("bound-delins-strand-" + strand, material.snapshot());
    }

    @Test void aLegacySimpleAlleleKeepsItsExactAlgorithmAndEnvelopeUnderTheFactory() throws Exception {
        var f = new ProteinLengthChangeMaterialSupplyTest(); f.root = Files.createDirectory(root.resolve("simple"));
        f.setup(1, 105, "A", "AGCT", "6_7insGCT", "Glu2_Pro3insAla");
        String pin = modelFiles(f.root, f.data.model);
        var old = new CodingTranscriptMaterialSupply(mapper, f.root, pin, f::read)
                .resolveProteinLengthChange(f.data.context, f.data.candidate);
        var supply = factory(f, pin);
        var material = supply.resolveProteinLengthChange(f.data.context, f.data.candidate);
        assertTrue(old.ready(), old.snapshot()::toPrettyString);
        assertTrue(material.ready(), material.snapshot()::toPrettyString);
        var facts = material.snapshot().path("alleleAssociation");
        assertEquals("SIMPLE_INFRAME_INSERTION", facts.path("branch").asText());
        assertEquals("complete-cds-simple-edit-envelope-v1", facts.at("/equivalentEditEnvelope/algorithmId").asText());
        assertFalse(facts.has("editDerivation"));
        assertEquals(old.snapshot().path("alleleAssociation"), facts);
        assertEquals(CodingTranscriptMaterialSupply.sha("ATGGAAGCTCCTGGCTTTAAATAA"), facts.path("alteredCdsSha256").asText());
        assertEquals(CodingTranscriptMaterialSupply.sha("MEAPGFK*"), facts.path("alteredProteinSha256").asText());
        assertEquals("VERIFIED_INFRAME_LENGTH_CHANGE", material.snapshot().path("associationKind").asText());
        assertCapability(supply);
        artifact("old-simple-algorithm-preserved", material.snapshot());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void actualPm4AndBp3KeepIndependentUnknownsWhenOnlySequenceAndRawAnnotationsAreReady(boolean repeat) throws Exception {
        var f = fixture(1, "GCTGAA", "7_9delinsGCTGAA", "Pro3delinsAlaGlu", "SMALL_DELINS");
        var supply = factory(f, modelFiles(f.root, f.data.model));
        var runtimes = runtimes(f, supply, repeat);
        var pm4 = runtimes.pm4().evaluate(f.data.context, f.data.candidate, mapper.createObjectNode());
        var bp3 = runtimes.bp3().evaluate(f.data.context, f.data.candidate, mapper.createObjectNode());
        assertEquals(repeat ? "NOT_MET" : "NOT_EVALUATED", pm4.path("status").asText(), pm4::toPrettyString);
        assertEquals(repeat ? "PM4_PROJECT_LOCAL_REPEAT_OVERLAP_NOT_MET"
                : "PM4_NON_REPEAT_METHOD_OR_CURATED_COVERAGE_NOT_QUALIFIED", pm4.path("reasonCode").asText());
        assertEquals("NOT_EVALUATED", bp3.path("status").asText(), bp3::toPrettyString);
        assertEquals("BP3_REPEAT_FUNCTION_KNOWLEDGE_REQUIRED", bp3.path("reasonCode").asText());
        for (var result : List.of(pm4, bp3)) {
            assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
            assertEquals("READY", result.at("/sequenceMaterial/status").asText());
            assertLengthFacts(result.path("sequenceMaterial"));
            assertEquals("PROJECT_LOCAL_VERIFIED_INFRAME_SMALL_ALLELE", result.at("/publicSummary/scope").asText());
            assertFalse(result.at("/publicSummary/clinicalValidationCompleted").asBoolean(true));
            assertFalse(result.path("publicSummary").toString().contains(Pm4TestFixtures.CDS));
            assertFalse(result.path("publicSummary").toString().contains(CHANGED_PROTEIN));
            // Jackson may read a small LongNode as IntNode; exported JSON values must stay exact.
            assertEquals(result.toString(), mapper.readTree(mapper.writeValueAsBytes(result)).toString());
        }
        assertEquals(repeat ? "KNOWN_REPEAT" : "NO_REPEAT_OBSERVED", pm4.at("/publicSummary/repeatObservation").asText());
        assertFalse(bp3.at("/publicSummary/functionAbsenceInferredFromNoHit").asBoolean(true));
        assertEquals("protein_altering_variant", f.data.candidate.getTranscriptAnnotations().get(0).getConsequence());
        ObjectNode output = mapper.createObjectNode().put("dataClass", "SYNTHETIC_ENGINEERING_ONLY");
        output.set("pm4", pm4); output.set("bp3", bp3);
        artifact("normal-consumers-repeat-" + repeat, output);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void relatedConsumersShareOnlyReferenceProofAcrossBothCallOrders(boolean pm4First) throws Exception {
        var f = fixture(1, "GCTGAA", "7_9delinsGCTGAA", "Pro3delinsAlaGlu", "SMALL_DELINS");
        var supply = factory(f, modelFiles(f.root, f.data.model));
        var runtimes = runtimes(f, supply, true);
        ObjectNode contextBefore = f.data.context.deepCopy();
        JsonNode candidateBefore = mapper.valueToTree(f.data.candidate);
        ObjectNode first = pm4First ? runtimes.pm4().evaluate(f.data.context, f.data.candidate, mapper.createObjectNode())
                : runtimes.bp3().evaluate(f.data.context, f.data.candidate, mapper.createObjectNode());
        int afterFirst = f.bases.get();
        ObjectNode second = pm4First ? runtimes.bp3().evaluate(f.data.context, f.data.candidate, mapper.createObjectNode())
                : runtimes.pm4().evaluate(f.data.context, f.data.candidate, mapper.createObjectNode());
        int incrementalReads = f.bases.get() - afterFirst;
        assertTrue(afterFirst >= Pm4TestFixtures.CDS.length());
        assertTrue(incrementalReads > 0 && incrementalReads < Pm4TestFixtures.CDS.length(), "Fresh receipt proof, no full-exon reread");
        assertTrue(second.at("/sequenceMaterial/modelCacheHit").asBoolean());
        assertEquals(first.at("/sequenceMaterial/verification"), second.at("/sequenceMaterial/verification"));
        assertEquals(first.at("/sequenceMaterial/alleleAssociation"), second.at("/sequenceMaterial/alleleAssociation"));
        ObjectNode pm4 = pm4First ? first : second, bp3 = pm4First ? second : first;
        assertEquals("NOT_MET", pm4.path("status").asText());
        assertEquals("NOT_EVALUATED", bp3.path("status").asText());
        var repeatedPm4 = runtimes.pm4().evaluate(f.data.context, f.data.candidate, mapper.createObjectNode());
        assertEquals(pm4.path("publicSummary"), repeatedPm4.path("publicSummary"));
        assertEquals(2, runtimes.pm4().evaluations());
        assertEquals(1, runtimes.bp3().evaluations());
        assertEquals(contextBefore, f.data.context); assertEquals(candidateBefore, mapper.valueToTree(f.data.candidate));
        // A new candidate gets a new effect check even after the sequence cache is warm.
        f.data.variant(106, "CCT", "GCTGAA", "6_8delinsGCTGAA", "Pro3delinsAlaGlu");
        type(f.data, "SMALL_DELINS");
        var invalid = supply.resolveProteinLengthChange(f.data.context, f.data.candidate);
        assertFalse(invalid.ready()); assertEquals("PM4_HGVSC_SEQUENCE_MISMATCH", invalid.snapshot().path("reasonCode").asText());
        artifact("shared-order-pm4-first-" + pm4First, mapper.createObjectNode().set("invalidNextEffect", invalid.snapshot()));
    }

    @ParameterizedTest @CsvSource({
            "GCTG,7_9delinsGCTG,SMALL_DELINS,PM4_FRAMESHIFT_UNSUPPORTED",
            "GAA,7_9delinsGAA,MNV,PM4_NO_NET_LENGTH_CHANGE"})
    void capabilityDoesNotTurnFrameshiftOrEqualLengthMnvIntoLengthEvidence(String alternate, String coding,
            String type, String reason) throws Exception {
        var f = fixture(1, alternate, coding, null, type);
        var supply = factory(f, modelFiles(f.root, f.data.model));
        var material = supply.resolveProteinLengthChange(f.data.context, f.data.candidate);
        assertFalse(material.ready()); assertNull(material.model());
        assertEquals(reason, material.snapshot().path("reasonCode").asText());
        assertFalse(material.snapshot().has("alleleAssociation"));
        assertFalse(material.snapshot().path("scientificEvaluationPerformed").asBoolean(true));
        artifact("rejected-" + type + "-" + alternate, material.snapshot());
    }

    @ParameterizedTest @ValueSource(strings = {"candidate-allele", "model-bytes", "model-binding", "duplicate-vep", "reference"})
    void identityOrUniqueProjectionFailuresCannotBorrowTheFactoryCapability(String fault) throws Exception {
        var f = fixture(1, "GCTGAA", "7_9delinsGCTGAA", "Pro3delinsAlaGlu", "SMALL_DELINS");
        String pin = modelFiles(f.root, f.data.model);
        if (fault.equals("model-binding")) {
            ObjectNode manifest = (ObjectNode) mapper.readTree(Files.readAllBytes(f.root.resolve("manifest.json")));
            ((ObjectNode) manifest.at("/entries/0")).put("canonicalSha256", "f".repeat(64));
            byte[] bytes = mapper.writeValueAsBytes(manifest); Files.write(f.root.resolve("manifest.json"), bytes);
            pin = CodingTranscriptMaterialSupply.sha(bytes);
        }
        var supply = factory(f, pin);
        String reason;
        switch (fault) {
            case "candidate-allele" -> {
                for (String field : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele"))
                    f.data.context.withObject("variant").withObject(field).put("alternate", "GCTGAC");
                type(f.data, "SMALL_DELINS");
                reason = "TRANSCRIPT_MATERIAL_CANDIDATE_ALLELE_MISMATCH";
            }
            case "model-bytes" -> {
                Files.writeString(f.root.resolve("ENST000001.1.model.json"), "{}");
                reason = "TRANSCRIPT_MATERIAL_BYTES_MISMATCH";
            }
            case "model-binding" -> reason = "TRANSCRIPT_MATERIAL_MODEL_IDENTITY_MISMATCH";
            case "duplicate-vep" -> {
                TranscriptConsequence tx = f.data.candidate.getTranscriptAnnotations().get(0);
                f.data.candidate.applyTranscriptAnnotations(List.of(tx, tx));
                reason = "LENGTH_CHANGE_SELECTED_TRANSCRIPT_NOT_UNIQUE";
            }
            case "reference" -> {
                f.reference = "T" + f.reference.substring(1);
                reason = "TRANSCRIPT_MATERIAL_REFERENCE_SEQUENCE_MISMATCH";
            }
            default -> throw new AssertionError(fault);
        }
        var rejected = supply.resolveProteinLengthChange(f.data.context, f.data.candidate);
        assertFalse(rejected.ready()); assertNull(rejected.model());
        assertEquals(reason, rejected.snapshot().path("reasonCode").asText(), rejected.snapshot()::toPrettyString);
        assertFalse(rejected.snapshot().has("alleleAssociation"));
        artifact("rejected-identity-" + fault, rejected.snapshot());
    }

    @Test void wrongManifestPinIsRejectedBeforeAnyReferenceRead() throws Exception {
        var f = fixture(1, "GCTGAA", "7_9delinsGCTGAA", "Pro3delinsAlaGlu", "SMALL_DELINS");
        modelFiles(f.root, f.data.model);
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ProteinLengthMaterialFactory.reviewedSmallAlleles(mapper, f.root, "f".repeat(64), f::read, null));
        assertEquals("TRANSCRIPT_MATERIAL_FILE_IDENTITY_MISMATCH", failure.getMessage());
        assertEquals(0, f.bases.get());
    }

    @Test void stopLossCapabilitySuppliesVerifiedExtensionWithoutClaimingMechanismOrRegionApproval() throws Exception {
        var f = new GeneralPm4StopLossTest(); f.root = Files.createDirectory(root.resolve("stop-loss"));
        f.setup(1, "GCTGGTTGACCC", null);
        f.data.model.putArray("exons").addObject().put("ordinal", 1).put("genomicStart", 100)
                .put("genomicEnd", 100 + f.spliced.length() - 1).put("cdsStart", 1).put("cdsEnd", f.spliced.length());
        f.data.variant(118, "TAA", "CGA", "19_21delinsCGA", null);
        f.annotation = f.data.annotation("19_21delinsCGA", "Ter7ArgextTer3", "stop_lost").put("exon", "1/1");
        f.applyAnnotation(); type(f.data, "MNV");
        String pin = modelFiles(f.root, f.data.model);
        var old = new CodingTranscriptMaterialSupply(mapper, f.root, pin, f::read);
        var legacy = old.resolveStopLoss(f.data.context, f.data.candidate);
        assertFalse(legacy.ready()); assertEquals("PM4_STOP_LOSS_SIMPLE_SNV_REQUIRED", legacy.snapshot().path("reasonCode").asText());
        var supply = ProteinLengthMaterialFactory.reviewedSmallAlleles(mapper, f.root, pin, f::read, null);
        var extension = supply.resolveStopLoss(f.data.context, f.data.candidate);
        assertTrue(extension.ready(), extension.snapshot()::toPrettyString);
        var facts = extension.snapshot().path("alleleAssociation");
        assertEquals("VERIFIED_STOP_LOSS_SMALL_ALLELE", facts.path("branch").asText());
        assertEquals(3, facts.path("netAminoAcidChange").asInt());
        assertEquals(CodingTranscriptMaterialSupply.sha("MEPGFKRAG*"), facts.path("alteredProteinSha256").asText());
        assertTrue(facts.path("newTerminationResolved").asBoolean());
        assertFalse(extension.snapshot().path("clinicalAcceptance").asBoolean(true));
        assertFalse(extension.snapshot().path("scientificEvaluationPerformed").asBoolean(true));
        assertFalse(extension.snapshot().has("evaluation"));
        assertCapability(supply);
        artifact("stop-loss-material-only", extension.snapshot());
    }

    private ProteinLengthChangeMaterialSupplyTest fixture(int strand, String alternate, String coding,
            String protein, String variantType) throws Exception {
        var f = new ProteinLengthChangeMaterialSupplyTest();
        f.root = Files.createDirectory(root.resolve("variant-" + strand));
        String genomicAlternate = strand == 1 ? alternate : TruncatingSequence.reverseComplement(alternate);
        f.setup(strand, strand == 1 ? 106 : 112, strand == 1 ? "CCT" : "AGG", genomicAlternate, coding, protein);
        f.data.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(
                f.data.annotation(coding, protein, "protein_altering_variant"), TranscriptConsequence.class)));
        type(f.data, variantType);
        return f;
    }

    private CodingTranscriptMaterialSupply factory(ProteinLengthChangeMaterialSupplyTest f, String manifestSha) throws Exception {
        return ProteinLengthMaterialFactory.reviewedSmallAlleles(mapper, f.root, manifestSha, f::read, null);
    }

    private String modelFiles(Path directory, ObjectNode model) throws Exception {
        String transcript = model.path("transcript").asText();
        byte[] bytes = mapper.writeValueAsBytes(model); Files.write(directory.resolve(transcript + ".model.json"), bytes);
        ObjectNode manifest = mapper.createObjectNode().put("schemaVersion", "portable-native-transcript-model-derivation-v1");
        manifest.putArray("entries").addObject().put("transcript", transcript).put("file", transcript + ".model.json")
                .put("fileBytes", bytes.length).put("fileSha256", CodingTranscriptMaterialSupply.sha(bytes))
                .put("canonicalSha256", adapter.canonicalSha256(model)).put("modelId", model.path("modelId").asText())
                .put("modelVersion", model.path("modelVersion").asText());
        bytes = mapper.writeValueAsBytes(manifest); Files.write(directory.resolve("manifest.json"), bytes);
        return CodingTranscriptMaterialSupply.sha(bytes);
    }

    private record Runtimes(GeneralPm4Runtime pm4, GeneralBp3Runtime bp3) { }
    private Runtimes runtimes(ProteinLengthChangeMaterialSupplyTest f, CodingTranscriptMaterialSupply supply,
            boolean repeat) throws Exception {
        var source = new UniProtProteinRegionSupplyTest(); source.root = Files.createDirectory(root.resolve("uniprot"));
        ObjectNode raw = source.raw("P12345"); raw.putArray("features");
        if (repeat) raw.withArray("features").add(source.feature("Repeat", 1, 6));
        var uniprot = source.supply(raw);
        ObjectNode mapped = uniprot.mapModel(f.data.model, 2, 3);
        assertEquals("READY", mapped.path("status").asText(), mapped::toPrettyString);
        byte[] bytes = mapper.writeValueAsBytes(GeneralPm4TestSupport.combined(mapper, mapped));
        Path combinedFile = root.resolve("combined.json"); Files.write(combinedFile, bytes);
        var combined = new CombinedProteinRegionSupply(mapper, combinedFile, CodingTranscriptMaterialSupply.sha(bytes));
        var catalog = mapper.createObjectNode().put("schemaVersion", Ba1SpecificationCatalog.SCHEMA);
        catalog.putObject("metadata").put("status", "COMPLETE").put("entryCount", 1).put("snapshotId", "SYNTHETIC_FACTORY_TEST_ONLY");
        catalog.putArray("entries").addObject().putObject("scope").putArray("hgncIds").add("HGNC:999999");
        bytes = mapper.writeValueAsBytes(catalog); Path catalogFile = root.resolve("catalog.json"); Files.write(catalogFile, bytes);
        var guard = PredictionSpecializationGuard.load(mapper, catalogFile, CodingTranscriptMaterialSupply.sha(bytes));
        var pm4 = new GeneralPm4Runtime(mapper, supply, uniprot, combined, new Pm4ProteinRegionAssessment(mapper), guard);
        var bp3 = new GeneralBp3Runtime(mapper, pm4.sharedFactSupply(), new Bp3ProteinRegionAssessment(mapper), guard);
        return new Runtimes(pm4, bp3);
    }

    private void assertLengthFacts(JsonNode material) {
        assertEquals("VERIFIED_INFRAME_LENGTH_CHANGE", material.path("associationKind").asText());
        assertEquals("2.0.0", material.path("associationVersion").asText());
        assertFalse(material.path("clinicalAcceptance").asBoolean(true));
        assertFalse(material.path("scientificEvaluationPerformed").asBoolean(true));
        var facts = material.path("alleleAssociation");
        assertEquals(3, facts.path("netNucleotideChange").asInt()); assertEquals(1, facts.path("netAminoAcidChange").asInt());
        assertEquals(CodingTranscriptMaterialSupply.sha(CHANGED_CDS), facts.path("alteredCdsSha256").asText());
        assertEquals(CodingTranscriptMaterialSupply.sha(CHANGED_PROTEIN), facts.path("alteredProteinSha256").asText());
        assertFalse(facts.has("referenceCds")); assertFalse(facts.has("alteredCds"));
        assertFalse(facts.has("referenceProtein")); assertFalse(facts.has("alteredProtein"));
    }

    private void assertCapability(CodingTranscriptMaterialSupply supply) {
        JsonNode length = supply.proteinLengthChangeIdentity().path("sequenceCapability");
        JsonNode stop = supply.stopLossIdentity().path("sequenceCapability");
        assertTrue(length.isObject() && !length.isEmpty(), "Factory capability must be publicly identified");
        assertEquals(length, stop);
        assertEquals("a5-protein-length-sequence-capability-v1", length.path("schemaVersion").asText());
        assertEquals("A5_NATIVE_GRCH38_SMALL_ALLELE_SEQUENCE_FACTS", length.path("capabilityId").asText());
        assertEquals("1.0.0", length.path("capabilityVersion").asText());
        assertEquals("GRCh38", length.path("assembly").asText()); assertEquals("NATIVE", length.path("mappingStatus").asText());
        assertEquals(50, length.path("maximumAlleleLengthIncludingAnchor").asInt());
        assertTrue(length.path("singlePhysicalAllele").asBoolean());
        assertFalse(length.path("qualificationGranted").asBoolean(true));
        assertFalse(length.path("scoringPermissionGranted").asBoolean(true));
        assertTrue(length.path("fullReferenceAndAnnotationVerificationRequired").asBoolean());
        ObjectNode scope = ((ObjectNode) length).deepCopy();
        String pin = scope.path("canonicalSha256").asText(); scope.remove("canonicalSha256");
        assertEquals(adapter.canonicalSha256(scope), pin);
        ((ObjectNode) length).put("scoringPermissionGranted", true);
        assertFalse(supply.proteinLengthChangeIdentity().at("/sequenceCapability/scoringPermissionGranted").asBoolean(true),
                "The public snapshot cannot mutate the code-owned capability");
        assertFalse(supply.identity().path("clinicalAcceptance").asBoolean(true));
    }

    private void type(Pm4TestFixtures f, String type) {
        f.context.withObject("variant").put("variantType", type);
        f.context.withObject("variant").put("alleleId", adapter.canonicalAlleleId(
                f.context.at("/variant/normalizedAllele"), f.context.at("/variant/normalization")));
        f.context.put("contextId", adapter.canonicalContextId(f.context));
    }

    private void artifact(String name, JsonNode result) throws Exception {
        String configured = System.getProperty("a5.round4.artifacts");
        if (configured == null || configured.isBlank()) return;
        Path directory = Path.of(configured);
        assertTrue(directory.isAbsolute(), "Explicit private artifact directory is required");
        Files.createDirectories(directory);
        ObjectNode envelope = mapper.createObjectNode().put("dataClass", "SYNTHETIC_ENGINEERING_ONLY")
                .put("scope", "NORMAL_JAVA_MATERIAL_FACTORY_TO_DIRECT_CONSUMER_NOT_CENTRAL_BUSINESS_ACCEPTANCE")
                .put("scientificApprovalFabricated", false).put("clinicalAcceptanceClaimed", false);
        envelope.set("actualJavaResult", result.deepCopy());
        Files.write(directory.resolve(name + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(envelope));
    }
}
