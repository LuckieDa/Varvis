package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.provider.SavedPredictionContextBridge.*;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.SavedPredictionContextBridgeFixtures.copy;

class SavedPredictionContextBridgeTest {
    @TempDir Path temporary;
    private SavedPredictionContextBridgeFixtures fixture() throws Exception { return new SavedPredictionContextBridgeFixtures(temporary); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void invalidExplicitIdentityIsIsolatedFromOtherCandidatesAndHypotheses(boolean sameCandidateSibling) throws Exception {
        var x = fixture();
        if (sameCandidateSibling) {
            x.inputs.add(x.input(0, "TEST_ONLY_SECOND", 1, "UNKNOWN", "AFFECTED", "TEST_ONLY_SELECTION")); x.reviewAll();
        }
        corruptExplicitResource(x, x.f.mapper.getNodeFactory().numberNode(0.5));
        var prepared = assertDoesNotThrow(x::prepare); var out = assess(x, prepared);
        assertEquals(sameCandidateSibling ? 6 : 4, out.size());
        for (int i = 0; i < out.size(); i++) {
            var result = out.get(i);
            assertEquals(i < 2 ? "CONTEXT_EXPLICIT_INPUT_IDENTITY_INVALID" : "CATALOG_INPUT_MISSING", result.path("reasonCode").asText());
            assertEquals("PASS", result.at("/stages/0/status").asText());
            if (i < 2) {
                assertEquals("ASSEMBLY_BLOCKED", result.at("/contextPreparation/preparationStatus").asText());
                assertEquals("explicitInput", result.at("/contextPreparation/blockers/0/field").asText());
                assertEquals("EXPLICIT_DECLARATION", result.at("/contextPreparation/blockers/0/sourceRole").asText());
                assertEquals("CALLER", result.at("/contextPreparation/blockers/0/neededParty").asText());
                assertFalse(result.path("contextPreparation").has("explicitInputSha256"));
                assertEquals(PredictionCalibration.sha256(x.f.mapper.writeValueAsBytes(x.inputs.get(0))),
                        result.at("/contextPreparation/rejectedExplicitInputByteSha256").asText());
            } else assertEquals("PASS", result.at("/stages/1/status").asText());
            assertFalse(result.toString().contains("TEST_ONLY_INVALID_EXTENSION"));
            assertFalse(result.toString().contains("0.5"));
        }
        for (String id : List.of("assessmentId", "candidateApplicationKey"))
            assertEquals(out.size(), out.stream().map(o -> o.path(id).asText()).distinct().count());
        assertEquals(out, assess(x, prepared));
    }

    @Test void serializationFailureHasNoInventedDigestAndDoesNotStopSibling() throws Exception {
        var x = fixture(); corruptExplicitResource(x, new com.fasterxml.jackson.databind.node.POJONode(new Object()));
        var out = assess(x, assertDoesNotThrow(x::prepare));
        assertEquals("CONTEXT_EXPLICIT_INPUT_IDENTITY_INVALID", out.get(0).path("reasonCode").asText());
        JsonNode entry = out.get(0).path("contextPreparation");
        assertFalse(entry.has("explicitInputSha256")); assertFalse(entry.has("rejectedExplicitInputByteSha256"));
        assertEquals("UNAVAILABLE", entry.path("rejectedExplicitInputByteSha256Status").asText());
        assertFalse(out.toString().contains("TEST_ONLY_INVALID_EXTENSION"));
        assertEquals("CATALOG_INPUT_MISSING", out.get(2).path("reasonCode").asText());
    }

    @Test void unknownReviewFieldsStayPrivateWhileWholeBytePinStillBindsIdentity() throws Exception {
        var x = fixture(); var saved = x.saved(); var evaluator = new PredictionApplicationEligibility(x.f.mapper);
        var legacy = evaluator.assess(saved).stream().map(r -> java.util.Objects.requireNonNull(r).json()).toList();
        Key key = x.reviews.keySet().iterator().next();
        ObjectNode review = (ObjectNode) x.f.mapper.readTree(x.reviews.get(key).bytes());
        review.put("unusedNumericNote", 0.5).put("sampleId", "TEST_ONLY_PRIVATE_SAMPLE")
                .put("executionRoot", "TEST_ONLY_PRIVATE_ROOT").put("validFrom", "TEST_ONLY_EXISTING_VALIDITY");
        x.putReview(key, review); byte[] original = x.reviews.get(key).bytes(); String pin = x.pins.get(key);
        var prepared = assertDoesNotThrow(x::prepare); var out = assess(x, prepared);
        assertEquals(4, out.size());
        for (ObjectNode result : out) {
            assertEquals("CATALOG_INPUT_MISSING", result.path("reasonCode").asText());
            assertEquals("PASS", result.at("/stages/1/status").asText());
            for (String flag : List.of("applicationReady", "formalEnabled", "criterionEvaluationCreated")) assertFalse(result.path(flag).asBoolean());
        }
        for (String output : List.of(prepared.entries().toString(), prepared.identity().toString(), out.toString()))
            for (String marker : List.of("unusedNumericNote", "TEST_ONLY_PRIVATE_SAMPLE", "TEST_ONLY_PRIVATE_ROOT")) assertFalse(output.contains(marker));
        JsonNode projected = prepared.entries().get(0).path("review");
        assertEquals(7, projected.size()); assertEquals("TEST_ONLY_EXISTING_VALIDITY", projected.path("validFrom").asText());
        assertEquals(pin, prepared.entries().get(0).path("reviewSourceSha256").asText());
        assertEquals(PredictionCalibration.sha256(original), pin); assertArrayEquals(original, x.reviews.get(key).bytes());
        assertEquals(pin, x.pins.get(key)); assertEquals(out, assess(x, prepared));
        review.put("unusedNumericNote", 0.75); x.putReview(key, review); var changed = x.assess();
        for (String id : List.of("assessmentId", "candidateApplicationKey")) assertNotEquals(out.get(0).path(id), changed.get(0).path(id));
        assertEquals(projected, x.prepare().entries().get(0).path("review"));
        assertEquals(legacy, evaluator.assess(saved).stream().map(r -> java.util.Objects.requireNonNull(r).json()).toList());
    }

    @Test void privateReviewTextIsNotExportedEvenWithoutNumericExtension() throws Exception {
        var x = fixture(); Key key = x.reviews.keySet().iterator().next();
        ObjectNode review = (ObjectNode) x.f.mapper.readTree(x.reviews.get(key).bytes());
        review.put("sampleId", "TEST_ONLY_PRIVATE_SAMPLE").put("executionRoot", "TEST_ONLY_PRIVATE_ROOT"); x.putReview(key, review);
        byte[] original = x.reviews.get(key).bytes(); var prepared = x.prepare(); var out = assess(x, prepared);
        for (String output : List.of(prepared.entries().toString(), prepared.identity().toString(), out.toString()))
            for (String marker : List.of("TEST_ONLY_PRIVATE_SAMPLE", "TEST_ONLY_PRIVATE_ROOT")) assertFalse(output.contains(marker));
        assertArrayEquals(original, x.reviews.get(key).bytes());
        assertEquals(PredictionCalibration.sha256(original), prepared.entries().get(0).path("reviewSourceSha256").asText());
    }

    @Test void noncanonicalSharedResourcesBlockSafelyWithoutAnyExplicitInputAndS0RemainsFirst() throws Exception {
        var x = fixture(); Path path = temporary.resolve("manifest.json");
        ObjectNode manifest = (ObjectNode) x.f.mapper.readTree(Files.readAllBytes(path));
        manifest.withObject("/resources/mane").put("TEST_ONLY_INVALID_RESOURCE", 0.5);
        byte[] bytes = x.f.mapper.writeValueAsBytes(manifest); Files.write(path, bytes); String pin = PredictionCalibration.sha256(bytes);
        x.snapshot = Snapshot.load(x.f.mapper, path, temporary.resolve("mane.json"), temporary.resolve("clingen.json"), pin);
        x.inputs.clear(); var prepared = assertDoesNotThrow(x::prepare);
        assertEquals("CONTEXT_RESOURCE_IDENTITY_INVALID", prepared.batchIssue());
        assertEquals(pin, prepared.identity().path("manifestSha256").asText()); assertFalse(prepared.identity().has("resources"));
        var out = assess(x, prepared); assertEquals(4, out.size());
        for (ObjectNode result : out) {
            assertEquals("PASS", result.at("/stages/0/status").asText());
            assertEquals("CONTEXT_RESOURCE_IDENTITY_INVALID", result.path("reasonCode").asText());
            assertEquals("NOT_CHECKED", result.at("/stages/2/status").asText());
            assertFalse(result.toString().contains("TEST_ONLY_INVALID_RESOURCE"));
        }
        x.f.candidate(0).withObject("/evidence/PROTEIN").put("predictionSha256", "a".repeat(64)); x.f.sealBytes();
        var bad = assess(x, x.prepare()); assertNotEquals("PASS", bad.get(0).at("/stages/0/status").asText());
        assertEquals("NOT_CHECKED", bad.get(0).at("/stages/1/status").asText());
        assertEquals("CONTEXT_RESOURCE_IDENTITY_INVALID", bad.get(2).path("reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"schemaVersion", "scope", "status", "reviewId", "reviewerId", "version", "validFrom", "validThrough"})
    void allAllowlistedReviewFieldsRequireNonblankTextWithoutCoercion(String field) throws Exception {
        var x = fixture(); Key key = x.reviews.keySet().iterator().next();
        ObjectNode review = (ObjectNode) x.f.mapper.readTree(x.reviews.get(key).bytes());
        for (JsonNode invalid : List.of(x.f.mapper.getNodeFactory().numberNode(1), x.f.mapper.getNodeFactory().numberNode(0.5),
                x.f.mapper.getNodeFactory().booleanNode(true), x.f.mapper.nullNode(), x.f.mapper.createObjectNode(), x.f.mapper.createArrayNode(),
                x.f.mapper.getNodeFactory().textNode("  "))) {
            review.set(field, invalid); x.putReview(key, review); var out = assess(x, assertDoesNotThrow(x::prepare));
            assertEquals("REVIEW_MISSING_OR_INVALID", out.get(0).at("/contextPreparation/preparationStatus").asText(), field);
            assertEquals("NOT_CHECKED", out.get(0).at("/stages/2/status").asText());
            assertFalse(out.get(0).path("contextPreparation").has("review"));
            assertEquals("CATALOG_INPUT_MISSING", out.get(2).path("reasonCode").asText());
        }
    }

    private static List<ObjectNode> assess(SavedPredictionContextBridgeFixtures x, PreparedContexts prepared) {
        return assertDoesNotThrow(() -> new PredictionApplicationEligibility(x.f.mapper).assess(x.saved(), prepared))
                .stream().map(r -> java.util.Objects.requireNonNull(r).json()).toList();
    }

    private static void corruptExplicitResource(SavedPredictionContextBridgeFixtures x, JsonNode extension) {
        ExplicitInput old = x.inputs.get(0); var b = old.binding(); var p = b.provenance();
        ObjectNode mane = (ObjectNode) p.maneResourceIdentity(); mane.set("TEST_ONLY_INVALID_EXTENSION", extension);
        var provenance = new BindingProvenance(p.patientHypothesis(), mane, p.clingenResourceIdentity(), p.crosswalkRecords(), p.relationshipRecords(), p.intakePolicyId(), p.intakePolicyVersion());
        var binding = new DiseaseContextBinding(b.candidateId(), b.hypothesisId(), b.hgncId(), b.symbol(), b.mondoId(), b.diseaseLabel(), b.normalizedMoi(),
                b.clingenClassification(), b.clingenClassificationDate(), b.clingenGcep(), b.clingenReportLocator(), b.status(), b.reasonCode(), provenance);
        x.inputs.set(0, new ExplicitInput(old.candidateId(), old.hypothesisId(), binding, old.dataClass(), old.transcript(), old.patient(), old.normalization()));
    }

    @Test void twoFictionalGenesReachS2UsingActualResolverLedgerAssemblerAndIndependentTestReview() throws Exception {
        var x = fixture(); var before = x.f.mapper.valueToTree(x.f.docs); var saved = x.saved();
        var original = new PredictionApplicationEligibility(x.f.mapper).assess(saved).stream().map(r -> java.util.Objects.requireNonNull(r).json()).toList();
        var prepared = x.prepare(); var results = x.assess(); assertEquals(4, results.size());
        for (ObjectNode result : results) {
            assertEquals("PASS", result.at("/stages/0/status").asText()); assertEquals("PASS", result.at("/stages/1/status").asText());
            assertEquals("CATALOG_INPUT_MISSING", result.path("reasonCode").asText(), result.toPrettyString());
            assertEquals("NONE", result.path("appliedStrength").asText()); assertEquals("NONE", result.path("proposedApplicationStrength").asText());
            for (String flag : List.of("applicationReady", "formalEnabled", "criterionEvaluationCreated")) assertFalse(result.path(flag).asBoolean());
            assertEquals("chr11", result.at("/inputContigComparison/originalContig").asText());
            assertEquals("11", result.at("/inputContigComparison/comparisonContig").asText());
            assertFalse(result.toString().contains("TEST_ONLY_SAMPLE"));
        }
        assertNotEquals(results.get(0).at("/contextPreparation/verifiedHgncId"), results.get(2).at("/contextPreparation/verifiedHgncId"));
        assertEquals(before, x.f.mapper.valueToTree(x.f.docs)); assertTrue(saved.settings().at("/candidates/0/hypotheses").isEmpty());
        assertFalse(x.published(0).has("hgncId"));
        assertEquals(original, new PredictionApplicationEligibility(x.f.mapper).assess(saved).stream().map(r -> java.util.Objects.requireNonNull(r).json()).toList());
        assertEquals(results, x.assess());
        assertThrows(UnsupportedOperationException.class, () -> prepared.entries().clear());
        prepared.entries().get(0).removeAll(); prepared.identity().removeAll(); saved.settings().removeAll(); saved.document("publication").deepCopy();
        assertEquals("VERIFIED_FOR_S1", prepared.entries().get(0).path("preparationStatus").asText());
    }

    @Test void emptyDeclarationsNeedNeitherDirectoryNorLedgerAndDirectorySuggestionsAreNotHypotheses() throws Exception {
        var x = fixture(); var saved = x.saved(); var bridge = new SavedPredictionContextBridge();
        for (TrustedContextSupply supply : List.of(TrustedContextSupply.empty(), x.supply(List.of()))) {
            var prepared = bridge.prepare(x.f.mapper, saved, supply); assertEquals(2, prepared.entries().size());
            for (var result : new PredictionApplicationEligibility(x.f.mapper).assess(saved, prepared)) {
                JsonNode out = result.json(); assertEquals("EXPLICIT_CONTEXT_MISSING", out.path("reasonCode").asText());
                assertEquals("PASS", out.at("/stages/0/status").asText()); assertEquals("NOT_CHECKED", out.at("/stages/2/status").asText());
                assertEquals("CALLER_CONFIRMATION", out.at("/contextPreparation/blockers/0/neededParty").asText());
                assertEquals("DECLARATION_NOT_SUPPLIED", out.at("/contextPreparation/blockers/0/kind").asText());
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"patient", "transcript", "normalization", "reference", "lineage", "snapshot", "ledgers"})
    void missingSourcesRetainOriginalAssemblerBlockers(String missing) throws Exception {
        var x = fixture(); ExplicitInput old = x.inputs.get(0); NormalizationInput n = old.normalization();
        String expected;
        switch (missing) {
            case "patient" -> { x.inputs.set(0, copy(old, old.transcript(), null, n)); expected = "PATIENT_CONTEXT_REQUIRED"; }
            case "transcript" -> { x.inputs.set(0, copy(old, null, old.patient(), n)); expected = "TRANSCRIPT_CHOICE_REQUIRED"; }
            case "normalization" -> { x.inputs.set(0, copy(old, old.transcript(), old.patient(), null)); expected = "NORMALIZATION_PROOF_REQUIRED"; }
            case "reference" -> { x.inputs.set(0, copy(old, old.transcript(), old.patient(), new NormalizationInput(n.originalAllele(), n.normalizedAllele(), n.selectedAltIndex(),
                    n.originalRecord(), n.method(), n.methodVersion(), n.source(), null, n.lineage()))); expected = "NORMALIZATION_PROOF_CONFLICT"; }
            case "lineage" -> { x.inputs.set(0, copy(old, old.transcript(), old.patient(), new NormalizationInput(n.originalAllele(), n.normalizedAllele(), n.selectedAltIndex(),
                    n.originalRecord(), n.method(), n.methodVersion(), n.source(), n.reference(), null))); expected = "ORIGINAL_ASSEMBLY_LINEAGE_REQUIRED"; }
            case "snapshot" -> { x.snapshot = null; expected = "CONTEXT_RELATIONSHIP_SNAPSHOT_MISSING"; }
            default -> { x.ledgerFiles.clear(); expected = "SOURCE_RECORD_NOT_VERIFIED"; }
        }
        var out = x.assess(); assertEquals(expected, out.get(0).path("reasonCode").asText());
        assertFalse(out.get(0).at("/contextPreparation/blockers/0/field").asText().isEmpty());
        if (!List.of("snapshot", "ledgers").contains(missing)) assertEquals("CATALOG_INPUT_MISSING", out.get(2).path("reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "pin", "status", "scope", "schemaVersion", "reviewId", "reviewerId", "version",
            "analysisId", "runId", "candidateId", "hypothesisId", "dataClass", "contextId", "contextSha256", "publicationByteSha256",
            "annotationSourceSha256", "versionedTranscriptId", "adoptedSources"})
    void reviewMustBeAnIndependentlyPinnedOriginalForTheFullContext(String field) throws Exception {
        var x = fixture(); Key key = x.reviews.keySet().iterator().next();
        if (field.equals("missing")) x.reviews.remove(key);
        else if (field.equals("pin")) x.pins.put(key, "a".repeat(64));
        else {
            ObjectNode review = (ObjectNode) x.f.mapper.readTree(x.reviews.get(key).bytes());
            if (List.of("reviewId", "reviewerId", "version").contains(field)) review.remove(field);
            else review.put(field, "VERIFIED"); // self-declared status and self-consistent bytes never substitute for scope/subject matching
            x.putReview(key, review);
        }
        var out = x.assess(); assertEquals("REVIEW_MISSING_OR_INVALID", out.get(0).at("/contextPreparation/preparationStatus").asText());
        assertEquals("BLOCKED", out.get(0).at("/stages/1/status").asText().equals("PASS") ? "PASS" : "BLOCKED");
        assertEquals("NOT_CHECKED", out.get(0).at("/stages/2/status").asText());
        assertEquals("CATALOG_INPUT_MISSING", out.get(2).path("reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"ncbi", "symbol", "hgnc", "ambiguous", "missing", "publishedHgnc", "resourcePin"})
    void independentGeneMappingNeverUsesSelfClaimedHgnc(String fault) throws Exception {
        var x = fixture();
        switch (fault) {
            case "ncbi" -> x.published(0).withObject("/exomiserCandidateFeatures").put("entrezGeneId", "909");
            case "symbol" -> x.published(0).withObject("/exomiserCandidateFeatures").put("geneSymbol", "TEST_ONLY_OTHER");
            case "hgnc" -> {
                var old = x.inputs.get(0); var hypothesis = old.binding().provenance().patientHypothesis();
                var bad = new PatientDiseaseHypothesis(hypothesis.candidateId(), hypothesis.hypothesisId(), "HGNC:999", hypothesis.mondoId(), hypothesis.moi(), hypothesis.provenance());
                var source = SavedPredictionApplicationInputs.contextSource(x.f.mapper, x.saved(), x.f.candidate(0));
                var binding = x.resolver.resolve(source.candidate(), List.of(bad)).bindings().get(0);
                x.inputs.set(0, new ExplicitInput(old.candidateId(), old.hypothesisId(), binding, old.dataClass(), old.transcript(), old.patient(), old.normalization()));
            }
            case "ambiguous" -> { ObjectNode extra = ((ObjectNode) x.crosswalk.get(0)).deepCopy().put("hgncId", "HGNC:999"); x.crosswalk.add(extra); x.resources(); }
            case "missing" -> { x.crosswalk.remove(0); x.resources(); }
            case "publishedHgnc" -> x.published(0).put("hgncId", "HGNC:999");
            case "resourcePin" -> {
                String oldPin = x.manifestPin; ((ObjectNode) x.crosswalk.get(0)).put("hgncId", "HGNC:999"); x.resources();
                var error = assertThrows(IllegalArgumentException.class, () -> Snapshot.load(x.f.mapper, temporary.resolve("manifest.json"),
                        temporary.resolve("mane.json"), temporary.resolve("clingen.json"), oldPin));
                assertEquals("CONTEXT_MANIFEST_PIN_MISMATCH", error.getMessage()); return;
            }
        }
        x.reseal(); var out = x.assess(); assertNotEquals("PASS", out.get(0).at("/stages/1/status").asText());
        assertTrue(out.get(0).path("reasonCode").asText().matches(".*(GENE|HGNC|SYMBOL|CROSSWALK|PROVENANCE).*"), out.get(0).toPrettyString());
    }

    @ParameterizedTest @ValueSource(strings = {"transcriptVersion", "annotationSource", "annotationVersion", "annotationDigest", "aliasConflict", "annotationReplacement", "sample", "numericSample", "geneType", "vcfType", "analysisMode"})
    void typedProjectionAndExplicitSelectionRemainBoundToTheSavedRawAnnotation(String fault) throws Exception {
        var x = fixture(); var old = x.inputs.get(0); var tx = old.transcript();
        switch (fault) {
            case "transcriptVersion" -> x.inputs.set(0, copy(old, new TranscriptChoice(tx.accession(), tx.version() + 1, tx.selectionPolicy(), tx.annotationSource(), tx.annotationSourceVersion(), tx.source()), old.patient(), old.normalization()));
            case "annotationSource" -> x.inputs.set(0, copy(old, new TranscriptChoice(tx.accession(), tx.version(), tx.selectionPolicy(), "OTHER", tx.annotationSourceVersion(), tx.source()), old.patient(), old.normalization()));
            case "annotationVersion" -> x.inputs.set(0, copy(old, new TranscriptChoice(tx.accession(), tx.version(), tx.selectionPolicy(), tx.annotationSource(), "OTHER", tx.source()), old.patient(), old.normalization()));
            case "annotationDigest" -> {
                Path file = x.ledgerFiles.get(tx.source().artifactSha256()); ObjectNode doc = (ObjectNode) x.f.mapper.readTree(Files.readAllBytes(file));
                doc.withObject("/records/transcript").put("annotationSourceSha256", "b".repeat(64)); Files.write(file, x.f.mapper.writeValueAsBytes(doc));
                assertThrows(IllegalArgumentException.class, x::supply); return;
            }
            case "aliasConflict" -> x.annotation(0).put("hgvsc", "ENST999999.9:c.1A>G");
            case "annotationReplacement" -> x.annotation(0).put("status", "UNAVAILABLE");
            case "sample" -> x.published(0).withObject("/observation").put("sampleId", "OTHER");
            case "numericSample" -> x.published(0).withObject("/observation").put("sampleId", 1);
            case "geneType" -> x.published(0).withObject("/exomiserCandidateFeatures").put("entrezGeneId", 101);
            case "vcfType" -> x.published(0).withObject("/analysisVcfCall").put("depth", "20");
            case "analysisMode" -> x.f.docs.get("publication").put("analysisMode", "wes");
        }
        x.reseal(); var out = x.assess(); assertNotEquals("PASS", out.get(0).at("/stages/1/status").asText());
        assertEquals("NOT_CHECKED", out.get(0).at("/stages/2/status").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"assembly", "position", "stringPosition", "reference", "alternate", "altIndex", "originalRecord", "contig", "ambiguousAlts"})
    void representationAdapterDoesNotChangeOtherAlleleSemantics(String fault) throws Exception {
        var x = fixture(); var old = x.inputs.get(0); var n = old.normalization();
        if (fault.equals("altIndex") || fault.equals("originalRecord")) {
            x.inputs.set(0, copy(old, old.transcript(), old.patient(), new NormalizationInput(n.originalAllele(), n.normalizedAllele(), fault.equals("altIndex") ? 2 : 1,
                    fault.equals("originalRecord") ? "11|100000|A|G|1" : n.originalRecord(), n.method(), n.methodVersion(), n.source(), n.reference(), n.lineage())));
            assertEquals("CONTEXT_MATCHED_RECORD_CONFLICT", x.assess().get(0).path("reasonCode").asText()); return;
        }
        if (fault.equals("ambiguousAlts")) {
            x.row(0).withObject("/matchedRecord").withArray("alternates").add("G"); x.reseal();
            assertNotEquals("PASS", x.assess().get(0).at("/stages/1/status").asText()); return;
        }
        ObjectNode view = SavedPredictionApplicationInputs.publicationCandidate(x.saved(), old.candidateId());
        ObjectNode allele = SavedPredictionContextBridge.queryAllele(x.f.mapper, view.path("queryAllele"));
        assertTrue(SavedPredictionContextBridge.contextInputAlleleMatches(view, allele));
        switch (fault) {
            case "assembly" -> allele.put("assembly", "GRCh37");
            case "position" -> allele.put("position", 100001);
            case "stringPosition" -> allele.put("position", "100000");
            case "reference" -> allele.put("reference", "a");
            case "alternate" -> allele.put("alternate", "T");
            case "contig" -> { view.withObject("/inputAllele").put("contig", "chrMT"); allele.put("contig", "MT"); }
        }
        assertFalse(SavedPredictionContextBridge.contextInputAlleleMatches(view, allele));
    }

    @Test void multipleBackgroundsPreserveFormalOrderAndPatientConsistency() throws Exception {
        var x = fixture(); x.inputs.add(0, x.input(0, "TEST_ONLY_H0_SECOND", 1, "UNKNOWN", "AFFECTED", "TEST_ONLY_SELECTION")); x.reviewAll();
        var out = x.assess(); assertEquals(6, out.size());
        assertEquals(List.of("TEST_ONLY_H0_SECOND", "TEST_ONLY_H0_SECOND", "TEST_ONLY_H0", "TEST_ONLY_H0", "TEST_ONLY_H1", "TEST_ONLY_H1"),
                out.stream().map(o -> o.path("hypothesisId").asText()).toList());
        assertEquals(List.of(1, 1, 1, 1, 2, 2), out.stream().map(o -> o.path("rank").asInt()).toList());
        out.forEach(o -> assertEquals("CATALOG_INPUT_MISSING", o.path("reasonCode").asText()));
        x.inputs.set(2, x.input(1, "TEST_ONLY_H1", 0, "FEMALE", "AFFECTED", "TEST_ONLY_SELECTION"));
        x.assess().forEach(o -> assertEquals("PATIENT_CONTEXT_CROSS_INPUT_CONFLICT", o.path("reasonCode").asText()));
    }

    @ParameterizedTest @ValueSource(strings = {"duplicate", "outside", "budget"})
    void scopeErrorsAreNotTruncatedToSuccessfulSubset(String fault) throws Exception {
        var x = fixture(); ExplicitInput old = x.inputs.get(0);
        if (fault.equals("duplicate")) x.inputs.add(old);
        else if (fault.equals("outside")) x.inputs.add(new ExplicitInput("TEST_ONLY_OUTSIDE", old.hypothesisId(), old.binding(), old.dataClass(), old.transcript(), old.patient(), old.normalization()));
        else { x.inputs.add(x.input(0, "SECOND", 1, "UNKNOWN", "AFFECTED", "TEST_ONLY_SELECTION")); x.f.settings.withObject("/task").put("maxHypothesesPerCandidate", 1); x.reseal(); }
        var out = x.assess(); for (var o : out) { assertNotEquals("PASS", o.at("/stages/1/status").asText()); assertEquals("NOT_CHECKED", o.at("/stages/2/status").asText()); }
    }

    @ParameterizedTest @ValueSource(strings = {"sex", "affectedStatus", "selectionPolicy"})
    void fullContextDigestProtectsFieldsOmittedFromFrozenContextId(String field) throws Exception {
        var x = fixture(); Key key = new Key("TEST_ONLY_0", "TEST_ONLY_H0"); String beforeId = x.contexts.get(key).path("contextId").asText();
        String oldAssessment = x.assess().get(0).path("assessmentId").asText();
        Map<Key, ReviewBytes> oldReviews = Map.copyOf(x.reviews); Map<Key, String> oldPins = Map.copyOf(x.pins);
        String sex = field.equals("sex") ? "FEMALE" : "UNKNOWN", affected = field.equals("affectedStatus") ? "UNAFFECTED" : "AFFECTED";
        String policy = field.equals("selectionPolicy") ? "TEST_ONLY_OTHER_SELECTION" : "TEST_ONLY_SELECTION";
        x.inputs.set(0, x.input(0, "TEST_ONLY_H0", 0, sex, affected, policy));
        if (field.equals("sex")) x.inputs.set(1, x.input(1, "TEST_ONLY_H1", 0, sex, "AFFECTED", "TEST_ONLY_SELECTION"));
        x.reviewAll(); assertEquals(beforeId, x.contexts.get(key).path("contextId").asText());
        String newAssessment = x.assess().get(0).path("assessmentId").asText(); assertNotEquals(oldAssessment, newAssessment);
        x.reviews.clear(); x.reviews.putAll(oldReviews); x.pins.clear(); x.pins.putAll(oldPins);
        var result = x.assess().get(0); assertEquals("CONTEXT_REVIEW_BINDING_CONFLICT", result.path("reasonCode").asText());
        assertEquals("review.contextSha256", result.at("/contextPreparation/blockers/0/field").asText());
    }

    @Test void preparedCannotCrossSettingsOrRunsAndS0AlwaysComesFirst() throws Exception {
        var x = fixture(); var prepared = x.prepare();
        x.f.settings.withObject("/task").put("version", "2"); x.reseal();
        var result = new PredictionApplicationEligibility(x.f.mapper).assess(x.saved(), prepared).get(0).json();
        assertEquals("PASS", result.at("/stages/0/status").asText()); assertEquals("CONTEXT_PREPARATION_SOURCE_IDENTITY_MISMATCH", result.path("reasonCode").asText());
        x.f.settings.put("runId", "OTHER_RUN"); x.f.docs.get("run").put("runId", "OTHER_RUN"); x.reseal();
        result = new PredictionApplicationEligibility(x.f.mapper).assess(x.saved(), prepared).get(0).json();
        assertEquals("CONTEXT_PREPARATION_SOURCE_IDENTITY_MISMATCH", result.path("reasonCode").asText());
        x.f.docs.get("run").put("runId", "test-calibration-run");
        x.f.settings.put("runId", "OTHER_RUN"); x.f.sealBytes();
        var bad = x.f.input(); assertFalse(bad.issue().isEmpty());
        result = new PredictionApplicationEligibility(x.f.mapper).assess(bad, prepared).get(0).json();
        assertNotEquals("PASS", result.at("/stages/0/status").asText()); assertEquals("NOT_CHECKED", result.at("/stages/1/status").asText());
    }

    @Test void nonemptySavedHypothesesRequireCompleteAgreementAndPreserveS6Pointer() throws Exception {
        var x = fixture(); var entries = x.prepare().entries();
        for (int i = 0; i < 2; i++) {
            var e = entries.get(i); ObjectNode h = x.f.candidate(i).withArray("hypotheses").addObject().put("hypothesisId", e.path("hypothesisId").asText())
                    .put("verification", "VERIFIED").put("effectSnapshotId", "KEEP_S6_" + i);
            h.set("context", e.path("context")); h.set("reviewId", e.at("/review/reviewId")); h.set("reviewerId", e.at("/review/reviewerId")); h.set("sourceSha256", e.path("reviewSourceSha256"));
        }
        x.reseal(); var result = x.assess().get(0); assertEquals("CATALOG_INPUT_MISSING", result.path("reasonCode").asText());
        // The exact old hypothesis remains part of the candidate and assessment identity, including the S6 pointer.
        assertEquals("KEEP_S6_0", x.saved().settings().at("/candidates/0/hypotheses/0/effectSnapshotId").asText());
        var empty = new SavedPredictionContextBridge().prepare(x.f.mapper, x.saved(), TrustedContextSupply.empty());
        assertEquals("CONTEXT_SAVED_HYPOTHESES_CONFLICT", new PredictionApplicationEligibility(x.f.mapper).assess(x.saved(), empty).get(0).json().path("reasonCode").asText());
        x.f.candidate(0).withObject("/hypotheses/0").put("reviewerId", "SELF"); x.reseal();
        assertEquals("CONTEXT_SAVED_HYPOTHESES_CONFLICT", x.assess().get(0).path("reasonCode").asText());
    }

    @Test void failedCandidateCannotPoisonSiblingAndFlatBridgeIsExplicitlyUnsupported() throws Exception {
        var x = fixture(); x.f.candidate(0).withObject("/evidence/PROTEIN").put("predictionSha256", "a".repeat(64)); x.f.sealBytes();
        var out = x.assess(); assertNotEquals("PASS", out.get(0).at("/stages/0/status").asText());
        assertEquals("CATALOG_INPUT_MISSING", out.get(2).path("reasonCode").asText());
        var flat = new PredictionEligibilityFixtures(); var prepared = new SavedPredictionContextBridge().prepare(flat.mapper, flat.input(), x.supply());
        assertEquals("CONTEXT_PUBLICATION_FORMAT_UNSUPPORTED", prepared.entries().get(0).at("/blockers/0/reasonCode").asText());
    }

    @Test void distinctSavedSelectionsCannotSilentlyChooseOneTranscript() throws Exception {
        var x = fixture(); String oldTx = x.f.prediction(0).at("/selection/versionedTranscriptId").asText(), otherTx = "ENST00000888888.2";
        ObjectNode annotation = x.annotation(0).deepCopy().put("transcriptId", "ENST00000888888").put("transcriptAccession", "ENST00000888888")
                .put("hgvsc", otherTx + ":c.1A>G").put("hgvsCoding", otherTx + ":c.1A>G");
        x.published(0).withArray("transcriptAnnotations").add(annotation); String sha = x.f.hash.canonicalSha256(annotation);
        ObjectNode prediction = x.f.prediction(0).deepCopy(), observation = prediction.withObject("/observation");
        ObjectNode row = (ObjectNode) observation.at("/rows/0"); row.put("transcriptId", otherTx).put("sourceRow", row.path("sourceRow").asText().replace(oldTx, otherTx));
        x.f.digest(observation, "observationSha256", "");
        prediction.withObject("/selection").put("selectedAnnotationId", "TEST_ONLY_SECOND").put("versionedTranscriptId", otherTx)
                .put("annotationSourceSha256", sha).set("sourceObservationSha256", observation.path("observationSha256"));
        prediction.withObject("/associationInput").put("transcriptId", otherTx).put("annotationSourceSha256", sha);
        for (String role : List.of("run", "parent")) ((com.fasterxml.jackson.databind.node.ArrayNode) x.f.docs.get(role).at("/candidates/0/attempts"))
                .addObject().set("prediction", prediction.deepCopy());
        ObjectNode second = x.f.candidate(0).withObject("/evidence/PROTEIN").deepCopy().put("predictionPointer", "/attempts/1/prediction")
                .put("parentPredictionPointer", "/attempts/1/prediction").put("annotationPointer", "/annotations/1");
        ((ObjectNode) x.f.docs.get("run").at("/candidates/0/attempts/1/prediction/associationInput"))
                .put("executionSourceSha256", PredictionCalibration.sha256(x.f.mapper.writeValueAsBytes(x.f.docs.get("publication"))));
        x.f.candidate(0).withObject("/evidence").set("SECOND_SELECTION", second); x.reseal();
        var saved = x.saved(); assertEquals("", SavedPredictionApplicationInputs.candidateIssue(x.f.mapper, saved, x.f.candidate(0)));
        assertEquals("CONTEXT_MULTIPLE_SELECTIONS_UNSUPPORTED", x.assess().get(0).path("reasonCode").asText());
    }

    @Test void duplicateSelectedAnnotationsRemainAmbiguousAndMissingReferenceLedgerStaysMissing() throws Exception {
        var x = fixture();
        x.published(0).withArray("transcriptAnnotations").add(x.annotation(0).deepCopy()); x.reseal();
        assertEquals("TRANSCRIPT_ANNOTATION_AMBIGUOUS", x.assess().get(0).path("reasonCode").asText());
        x.published(0).withArray("transcriptAnnotations").remove(1); x.reseal();
        x.ledgerFiles.remove(x.inputs.get(0).normalization().reference().artifactSha256());
        var out = x.assess().get(0); assertEquals("SOURCE_RECORD_NOT_VERIFIED", out.path("reasonCode").asText());
        assertEquals("normalization.reference", out.at("/contextPreparation/blockers/0/field").asText());
    }

    @Test void reviewVersionAndValidityRemainInBothNewIdentities() throws Exception {
        var x = fixture(); var before = x.assess().get(0); Key key = x.reviews.keySet().iterator().next();
        ObjectNode review = (ObjectNode) x.f.mapper.readTree(x.reviews.get(key).bytes());
        review.put("version", "2").put("validThrough", "2026-09-21T00:00:00Z"); x.putReview(key, review);
        var after = x.assess().get(0);
        assertEquals("CATALOG_INPUT_MISSING", after.path("reasonCode").asText());
        for (String field : List.of("candidateApplicationKey", "assessmentId")) assertNotEquals(before.path(field), after.path(field));
        assertEquals(after, x.assess().get(0));
    }
}
