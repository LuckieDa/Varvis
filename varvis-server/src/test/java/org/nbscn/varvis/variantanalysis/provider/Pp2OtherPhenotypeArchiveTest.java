package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Synthetic saved-byte integrity controls, never source qualification or a live scientific review. */
class Pp2OtherPhenotypeArchiveTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter hashes = new RuntimeEvidenceV2Adapter(mapper);
    private record Saved(ObjectNode archive, ObjectNode batch) { }

    @Test void savedBytesAndTargetReferencesRoundTripWithoutRecreatingReview() {
        var saved = fixture();
        var exported = Pp2OtherPhenotypeArchive.verifyAndExport(mapper, saved.archive(), saved.batch());
        assertEquals(saved.archive(), exported);
        assertTrue(exported.isObject()); // JSON only; there is deliberately no Review restoration API.
        exported.remove("sourceArchive");
        assertTrue(saved.archive().path("sourceArchive").isObject());
    }

    @Test void missingOriginalIsRejectedEvenAfterEnvelopeRehash() {
        var saved = fixture();
        ((ObjectNode) saved.archive().at("/sourceArchive/originals")).removeAll();
        sealSources(saved); reject(saved, "READ_ORIGINAL_MISSING");
    }

    @Test void changedRawBytesAreRejectedEvenAfterEnvelopeRehash() {
        var saved = fixture();
        var originals = saved.archive().at("/sourceArchive/originals");
        ((ObjectNode) originals.iterator().next()).put("base64", Base64.getEncoder().encodeToString("changed".getBytes(StandardCharsets.UTF_8)));
        sealSources(saved); reject(saved, "ORIGINAL_BYTES_INVALID");
    }

    @Test void changedReaderFactsCannotKeepTheOriginalObservation() {
        var saved = fixture();
        ((ObjectNode) saved.archive().at("/sourceArchive/readerFacts")).put("status", "CHANGED");
        sealSources(saved); reject(saved, "REPLAY_FACTS_INVALID");
    }

    @Test void previousRequestObservationCannotSupplyCurrentHistory() {
        var saved = fixture();
        ((ObjectNode) saved.batch().path("pp2OtherPhenotypeSourceIdentity")).put("observationSha256", "b".repeat(64));
        reject(saved, "OBSERVATION_IDENTITY_INVALID");
    }

    @Test void removingJudgmentDoesNotMakeAnAdoptedModuleUntracked() {
        var saved = fixture();
        saved.archive().putArray("judgments"); sealOuter(saved);
        reject(saved, "JUDGMENT_COUNT_INVALID");
    }

    @Test void copiedReviewCannotBeMovedToAnotherCandidate() {
        var saved = fixture();
        ((ObjectNode) saved.batch().at("/modules/pp2/results/0")).put("candidateId", "another-candidate");
        refreshJudgment(saved); reject(saved, "JUDGMENT_SOURCE_INVALID");
    }

    @Test void currentReviewMustReferToAnActualArchivedRead() {
        var saved = fixture();
        ((ObjectNode) saved.batch().at("/modules/pp2/results/0/otherPhenotypeReview"))
                .putArray("sourceReadDispositionIds").add("invented-read");
        refreshJudgment(saved); reject(saved, "JUDGMENT_READ_MISSING");
    }

    @Test void changedModuleCannotKeepItsOriginalArchivedJudgment() {
        var saved = fixture();
        ((ObjectNode) saved.batch().at("/modules/pp2/results/0/evaluation")).put("appliedStrength", "STRONG");
        reject(saved, "JUDGMENT_BINDING_INVALID");
    }

    @Test void currentReviewCannotBeTransferredToAnotherVerifiedPolicyContext() {
        var saved = fixture();
        ((ObjectNode) saved.batch().at("/modules/pp2/results/0/hcmPolicyBinding"))
                .put("preparedContextSha256", "e".repeat(64));
        refreshJudgment(saved); reject(saved, "JUDGMENT_CONTEXT_INVALID");
    }

    @Test void failedPinComparisonMustKeepTheBytesActuallyRead() {
        var saved = fixture();
        ((ObjectNode) saved.archive().at("/sourceArchive/observation/readDispositions/0"))
                .put("status", "UNAVAILABLE").put("reasonCode", "PP2_SOURCE_RAW_PIN_MISMATCH")
                .put("expectedRawSha256", "f".repeat(64));
        rebindObservation(saved);
        assertEquals(saved.archive(), Pp2OtherPhenotypeArchive.verifyAndExport(mapper, saved.archive(), saved.batch()));
        ((ObjectNode) saved.archive().at("/sourceArchive/originals")).removeAll();
        sealSources(saved); reject(saved, "ARCHIVED_ORIGINAL_MISSING");
    }

    @Test void exhaustedArchiveBudgetDoesNotInventAnOriginalThatWasNeverStored() {
        var saved = fixture();
        ((ObjectNode) saved.archive().at("/sourceArchive/observation/readDispositions/0"))
                .put("status", "UNAVAILABLE").put("reasonCode", "PP2_SOURCE_TECHNICAL_TOTAL_LIMIT");
        ((ObjectNode) saved.archive().at("/sourceArchive/originals")).removeAll();
        rebindObservation(saved);
        assertEquals(saved.archive(), Pp2OtherPhenotypeArchive.verifyAndExport(mapper, saved.archive(), saved.batch()));
    }

    @Test void ambiguousFailedReadIsPreservedWithoutCreatingSourceQualification() {
        var saved = fixture();
        ((com.fasterxml.jackson.databind.node.ArrayNode) saved.archive().at("/sourceArchive/observation/readDispositions"))
                .addObject().put("readId", "original-control").put("status", "UNAVAILABLE")
                .put("reasonCode", "PP2_SOURCE_SCOPE_INVALID");
        rebindObservation(saved);
        assertEquals("UNKNOWN", saved.batch().at("/modules/pp2/results/0/otherPhenotypeReview/outcome").asText());
        assertEquals(saved.archive(), Pp2OtherPhenotypeArchive.verifyAndExport(mapper, saved.archive(), saved.batch()));
    }

    @Test void reviewCannotMoveToAnotherDiseaseBackgroundOfTheSameCandidate() {
        var saved = fixture();
        ((ObjectNode) saved.batch().at("/modules/pp2/results/0")).put("hypothesisId", "another-background");
        refreshJudgment(saved); reject(saved, "JUDGMENT_PROJECTION_INVALID");
    }

    @Test void recomputedProjectionStillMustMatchTheActuallyReviewedContext() {
        var saved = fixture();
        var projection = (ObjectNode) saved.batch().at("/modules/pp2/results/0/sourceProjection");
        ((ObjectNode) projection.path("evaluationContext")).put("contextId", "another-context");
        seal(projection, "bindingSha256"); refreshJudgment(saved);
        reject(saved, "JUDGMENT_SELECTED_CONTEXT_INVALID");
    }

    @Test void failureBeforeExecutionKeepsObservedSourcesWithoutInventingAnExecutionOrReview() {
        var saved = fixture();
        saved.batch().put("executionMode", "NOT_EXECUTED").put("sourceSnapshotSha256", "UNAVAILABLE");
        saved.batch().putObject("modules");
        saved.archive().put("executionSourceSha256", "UNAVAILABLE").putArray("judgments");
        sealOuter(saved);
        assertEquals(saved.archive(), Pp2OtherPhenotypeArchive.verifyAndExport(mapper, saved.archive(), saved.batch()));
        saved.batch().put("executionMode", "EXECUTED");
        reject(saved, "EXECUTION_SOURCE_INVALID");
    }

    private record Derived(Saved base, ObjectNode archive, String paperSha256) { }

    @Test void actualStoredDerivedLedgersRoundTripAsJsonWithoutCreatingScientificAuthority() throws Exception {
        var d = derivedFixture(); var before = d.archive().deepCopy();
        var verifiedBase = Pp2OtherPhenotypeArchive.verifyAndExport(mapper, d.base().archive(), d.base().batch());
        assertTrue(Pp2OtherPhenotypeArchive.derivedEvidenceRequired(d.base().batch(), verifiedBase));
        var exported = Pp2OtherPhenotypeArchive.verifyDerivedAndExport(mapper, d.archive(), d.base().batch(), verifiedBase);
        assertEquals(before, exported); assertEquals("UNKNOWN", exported.at("/assessments/0/outcome").asText());
        assertEquals("NONE", d.base().batch().at("/modules/pp2/results/0/evaluation/appliedStrength").asText());
        exported.remove("originals"); assertEquals(before, d.archive());
    }
    @Test void missingActualDerivedPaperIsRejectedAfterRehashingTheEnvelope() throws Exception {
        var d = derivedFixture(); ((ObjectNode) d.archive().path("originals")).remove(d.paperSha256());
        sealDerived(d); rejectDerived(d, "DERIVED_READING_ORIGINAL_MISSING");
    }
    @Test void changedActualDerivedBytesCannotHideBehindAnUnchangedRawReference() throws Exception {
        var d = derivedFixture(); ((ObjectNode) d.archive().path("originals").path(d.paperSha256()))
                .put("base64", Base64.getEncoder().encodeToString("changed".getBytes(StandardCharsets.UTF_8)));
        sealDerived(d); rejectDerived(d, "DERIVED_ORIGINAL_BYTES_INVALID");
    }
    @Test void publicCoreFactsMustBeTheExactSavedQualificationProjection() throws Exception {
        var d = derivedFixture(); ((ObjectNode) derivedReview(d).path("literatureAssessment")).put("scopeStatus", "COMPLETED");
        refreshJudgment(d.base()); rejectDerived(d, "DERIVED_SUMMARY_INVALID");
    }
    @Test void derivedReviewCannotBeTransferredToAnotherDiseaseBackground() throws Exception {
        var d = derivedFixture(); ((ObjectNode) derivedReview(d).path("binding")).put("hypothesisId", "another-background");
        refreshJudgment(d.base()); rejectDerived(d, "DERIVED_REVIEW_BINDING_INVALID");
    }
    @Test void removingBothDerivedReferencesDoesNotDowngradeANewReviewToLegacy() throws Exception {
        var d = derivedFixture(); var review = derivedReview(d);
        ((ObjectNode) review.path("binding")).remove("derivedAssessmentSha256"); review.remove("literatureAssessment");
        d.base().batch().remove("pp2OtherPhenotypeDerivedArchiveSha256"); refreshJudgment(d.base());
        assertTrue(Pp2OtherPhenotypeArchive.derivedEvidenceRequired(d.base().batch(), d.base().archive()));
        sealDerived(d); rejectDerived(d, "DERIVED_REVIEW_REFERENCE_MISSING");
    }
    @Test void absentDerivedAssessmentCannotBeInventedFromThePublicSummary() throws Exception {
        var d = derivedFixture(); d.archive().putArray("assessments"); sealDerived(d);
        rejectDerived(d, "DERIVED_REVIEW_ASSESSMENT_MISSING");
    }
    @Test void derivedMethodPinsMustBeTheActualFrozenObservationEvenAfterEnvelopeRehash() throws Exception {
        var d = derivedFixture(); var methods = d.archive().putObject("methodSources");
        d.archive().put("methodSourcesSha256", hashes.canonicalSha256(methods)); sealDerived(d);
        rejectDerived(d, "DERIVED_METHOD_BINDING_INVALID");
    }
    @Test void genuinelyLegacyArchiveDoesNotRequireAnUnexecutedDerivedFlow() {
        var old = fixture(); assertFalse(Pp2OtherPhenotypeArchive.derivedEvidenceRequired(old.batch(), old.archive()));
        assertEquals(old.archive(), Pp2OtherPhenotypeArchive.verifyAndExport(mapper, old.archive(), old.batch()));
    }
    @Test void implementedQuestionReaderKeepsItsExactSavedMethodAndDoesNotRecreateScientificAuthority() throws Exception {
        var d = derivedFixture("1.2.0", true); var before = d.archive().deepCopy();
        var verifiedBase = Pp2OtherPhenotypeArchive.verifyAndExport(mapper, d.base().archive(), d.base().batch());
        assertEquals(before, Pp2OtherPhenotypeArchive.verifyDerivedAndExport(mapper, d.archive(), d.base().batch(), verifiedBase));
        assertEquals("UNKNOWN", derivedReview(d).path("outcome").asText());
        assertEquals("NONE", d.base().batch().at("/modules/pp2/results/0/evaluation/appliedStrength").asText());
    }
    @Test void questionReaderCannotBorrowAMethodWithoutTheActuallyObservedQuestionModule() throws Exception {
        rejectDerived(derivedFixture("1.2.0", false), "DERIVED_READING_METHOD_MISMATCH");
    }
    @Test void anUnknownReaderVersionIsNotGrantedImplementedQuestionSemantics() throws Exception {
        rejectDerived(derivedFixture("99.0.0", true), "DERIVED_READING_INVALID");
    }
    @Test void boundedNonzeroScopeWithActualStoredProofRoundTripsWithoutAClassification() throws Exception {
        var d = derivedFixture("1.2.0", true, ignored -> { }, true);
        var verifiedBase = Pp2OtherPhenotypeArchive.verifyAndExport(mapper, d.base().archive(), d.base().batch());
        assertEquals(d.archive(), Pp2OtherPhenotypeArchive.verifyDerivedAndExport(mapper, d.archive(), d.base().batch(), verifiedBase));
        assertEquals("NONE", d.base().batch().at("/modules/pp2/results/0/evaluation/appliedStrength").asText());
    }
    @Test void nonzeroNoSupportCannotBorrowTheOldEmptyScopeWithoutItsStoredQualification() throws Exception {
        rejectDerived(derivedFixture("1.2.0", true, null, true), "DERIVED_BOUNDED_REQUIRED");
    }
    @Test void aReviewedKnownCitationIsNonzeroEvenWithoutDiscoveryHits() throws Exception {
        var d = derivedFixture("1.2.0", true, node -> node.put("declaredCandidateCount", 0).put("declaredKnownCitationCount", 1), true);
        var verifiedBase = Pp2OtherPhenotypeArchive.verifyAndExport(mapper, d.base().archive(), d.base().batch());
        assertEquals(d.archive(), Pp2OtherPhenotypeArchive.verifyDerivedAndExport(mapper, d.archive(), d.base().batch(), verifiedBase));
    }
    @Test void closedNonzeroScopeCannotUseAQualifierForDifferentMaterialOrReading() throws Exception {
        rejectDerived(derivedFixture("1.2.0", true, node -> ((ObjectNode) node.path("materialIdentity"))
                .put("nativeModelSha256", "d".repeat(64)), true), "DERIVED_BOUNDED_MATERIAL_INVALID");
        rejectDerived(derivedFixture("1.2.0", true, node -> node.put("readingLedgerRawSha256", "e".repeat(64)), true),
                "DERIVED_BOUNDED_BINDING_INVALID");
    }
    @Test void nonzeroNoSupportCannotBeSavedWithIncompleteQualificationScope() throws Exception {
        rejectDerived(derivedFixture("1.2.0", true, node -> node.put("scopeStatus", "INCOMPLETE"), true),
                "DERIVED_BOUNDED_SCOPE_INVALID");
    }
    private Derived derivedFixture() throws Exception {
        return derivedFixture("1.1.0", false);
    }
    private Derived derivedFixture(String readingVersion, boolean includeQuestionMethod) throws Exception {
        return derivedFixture(readingVersion, includeQuestionMethod, null, false);
    }
    private Derived derivedFixture(String readingVersion, boolean includeQuestionMethod,
            @org.springframework.lang.Nullable java.util.function.Consumer<ObjectNode> editBounded, boolean nonzeroConclusion) throws Exception {
        var base = fixture();
        var capability = (ObjectNode) base.archive().at("/sourceArchive/observation/sourceCapability");
        capability.putObject("derivedAssessment").put("methodVersion", Pp2OtherPhenotypeArchive.DERIVED_VERSION);
        String qualificationVersion = "pp2-bounded-literature-qualification-v1";
        boolean nonzero = editBounded != null || nonzeroConclusion;
        if (nonzero) capability.put("qualificationVersion", qualificationVersion);
        var baseMethods = (ObjectNode) base.archive().at("/sourceArchive/methodSources");
        baseMethods.removeAll(); baseMethods.put("synthetic-reader.py", CodingTranscriptMaterialSupply.sha("SYNTHETIC reader engineering control"));
        baseMethods.putObject("derivedReaderImplementation").put("synthetic_reader.py", CodingTranscriptMaterialSupply.sha("SYNTHETIC nested reader control"));
        if (includeQuestionMethod) ((ObjectNode) baseMethods.path("derivedReaderImplementation"))
                .put("pp2_relationship_material.py", CodingTranscriptMaterialSupply.sha("SYNTHETIC question method identity control"));
        if (nonzero) baseMethods.put("scripts/ps1_pm5/pp2_bounded_literature_qualification.py",
                CodingTranscriptMaterialSupply.sha("SYNTHETIC bounded helper identity control"));
        String methodSha = hashes.canonicalSha256(baseMethods);
        ((ObjectNode) base.archive().at("/sourceArchive/observation")).put("methodSourcesSha256", methodSha);
        ((ObjectNode) base.archive().path("sourceObservationIdentity")).put("methodSourcesSha256", methodSha);
        rebindObservation(base);
        var resultRow = (ObjectNode) base.batch().at("/modules/pp2/results/0"); var review = (ObjectNode) resultRow.path("otherPhenotypeReview");
        String outcome = nonzeroConclusion ? "NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE" : "UNKNOWN";
        review.put("outcome", outcome).put("reasonCode", nonzeroConclusion ? "PP2_NO_OTHER_PHENOTYPE_SUPPORT_IN_COMPLETED_DECLARED_SCOPE"
                : "PP2_OTHER_PHENOTYPE_QUESTIONS_NOT_ASSESSED");
        var binding = (ObjectNode) review.path("binding"); binding.put("hypothesisId", "background-control");
        String modelPin = CodingTranscriptMaterialSupply.sha("SYNTHETIC exact execution model"), associationPin = CodingTranscriptMaterialSupply.sha("SYNTHETIC exact association");
        if (nonzero) {
            var policy = (ObjectNode) resultRow.path("hcmPolicyBinding");
            policy.put("executionModelSha256", modelPin).put("alleleAssociationSha256", associationPin);
            binding.put("hcmPolicyBindingSha256", hashes.canonicalSha256(policy));
        }
        var archive = mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-derived-archive-v1")
                .put("methodVersion", Pp2OtherPhenotypeArchive.DERIVED_VERSION);
        archive.set("sourceSnapshotSha256", review.at("/binding/sourceSnapshotSha256"));
        archive.set("methodSources", baseMethods.deepCopy()); archive.put("methodSourcesSha256", methodSha);
        var originals = archive.putObject("originals");
        byte[] paper = "SYNTHETIC source: a literal observation, no disease causality was established.".getBytes(StandardCharsets.UTF_8);
        String paperSha = addDerivedOriginal(originals, paper);
        var reading = mapper.createObjectNode().put("schemaVersion", "pp2-relation-reading-v1").put("methodVersion", readingVersion)
                .put("scientificAdoption", false).put("relationQualification", "NOT_GRANTED");
        if ("1.2.0".equals(readingVersion)) reading.set("implementation", baseMethods.path("derivedReaderImplementation").deepCopy());
        reading.putObject("originals").putObject(paperSha).put("rawSha256", paperSha).put("bytes", paper.length);
        String scopeMeaning = "DECLARED_AVAILABLE_MAIN_AND_PARSED_ATTACHMENT_TEXT_UNITS_ONLY";
        if (nonzero) {
            byte[] bundle = "SYNTHETIC source bundle".getBytes(StandardCharsets.UTF_8);
            String bundlePin = addDerivedOriginal(originals, bundle);
            ((ObjectNode) reading.path("originals")).putObject(bundlePin).put("rawSha256", bundlePin).put("bytes", bundle.length);
            reading.put("readingScopeMeaning", scopeMeaning).put("readingComplete", true).putArray("pending");
            reading.putArray("papers").addObject().put("pmcid", "PMC123").put("pmid", "456").put("sourceRawSha256", paperSha).put("bundleSha256", bundlePin)
                    .putObject("questionCoverage").put("status", "RAW_QUESTION_SCOPE_READ");
        }
        seal(reading, "ledgerSha256");
        String readingSha = addDerivedOriginal(originals, mapper.writeValueAsBytes(reading));
        var summary = mapper.createObjectNode().put("methodVersion", Pp2OtherPhenotypeArchive.DERIVED_VERSION)
                .put("scopeStatus", "NOT_COMPLETED").put("assessedPapers", 1);
        summary.putArray("papers").addObject().put("pmcid", "PMC123").put("relationshipStatus", "NOT_ASSESSED");
        var qualification = mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-literature-qualification-v1")
                .put("methodVersion", Pp2OtherPhenotypeArchive.DERIVED_VERSION).put("outcome", outcome)
                .put("reasonCode", review.path("reasonCode").asText());
        if (nonzero) summary.put("papersInScope", 1);
        int candidateCount = 1, citationCount = 0;
        if (editBounded != null) {
            var bounded = qualification.putObject("boundedLiteratureReview").put("schemaVersion", "pp2-bounded-literature-review-v1")
                    .put("qualificationVersion", qualificationVersion).put("scopeMeaning", scopeMeaning).put("wholePublicationRead", false)
                    .put("positiveOtherPhenotypeGranted", false).put("scientificClassificationCreated", false)
                    .put("scopeStatus", "COMPLETE_DECLARED_SCOPE").put("readingLedgerRawSha256", readingSha).put("methodSourcesSha256", methodSha)
                    .put("unresolvedRecords", 0).put("unresolvedUnits", 0).put("declaredCandidateCount", 1).put("declaredKnownCitationCount", 0);
            bounded.set("binding", binding.deepCopy()); bounded.set("targetAllele", binding.path("allele").deepCopy());
            var material = bounded.putObject("materialIdentity").put("nativeModelSha256", modelPin).put("alleleAssociationSha256", associationPin);
            material.set("contextSha256", binding.path("contextSha256")); material.set("hcmPolicyBindingSha256", binding.path("hcmPolicyBindingSha256"));
            bounded.putArray("gaps"); bounded.putArray("unresolvedCitationRefs");
            var paperProof = bounded.putArray("papers").addObject().put("pmcid", "PMC123").put("sourceRawSha256", paperSha)
                    .put("bundleSha256", reading.at("/papers/0/bundleSha256").asText()).put("scopeStatus", "COMPLETE_DECLARED_SCOPE").put("unresolvedRecords", 0);
            paperProof.putArray("unresolvedUnitIds"); paperProof.putArray("gaps");
            paperProof.putArray("observations").addObject().put("targetMatch", "EXACT").put("disposition", "CURRENT_TARGET_HCM_PROCESS").putArray("gaps");
            editBounded.accept(bounded);
            candidateCount = bounded.path("declaredCandidateCount").asInt(); citationCount = bounded.path("declaredKnownCitationCount").asInt();
            summary.put("qualificationVersion", qualificationVersion).put("qualificationScopeStatus", bounded.path("scopeStatus").asText())
                    .put("declaredCandidateCount", candidateCount).put("declaredKnownCitationCount", citationCount)
                    .put("unresolvedRecords", 0).put("unresolvedUnits", 0).put("unresolvedCitationCount", 0);
        }
        if (nonzeroConclusion) {
            var scopes = review.putArray("requiredScope");
            var literature = scopes.addObject().put("scope", "TARGET_LITERATURE_RELATIONSHIP_REVIEW").put("status", "COMPLETED_DECLARED_TARGET_SCOPE")
                    .put("qualificationVersion", qualificationVersion).put("qualificationScopeStatus", "COMPLETE_DECLARED_SCOPE")
                    .put("candidatePapers", candidateCount).put("assessedPapers", 1).put("retrievalScopeComplete", true).put("aliasIdentityVerified", true);
            var citations = literature.putArray("knownCitationReferences");
            if (citationCount == 1) citations.addObject().put("pmid", "456");
            scopes.addObject().put("scope", "EXACT_TARGET_NATIVE_EXPERT_ASSERTIONS").put("status", "COMPLETED_DECLARED_TARGET_SCOPE")
                    .put("targetRecords", 0).put("qualifiedRecords", 0).putObject("nativeSourceCoverage").put("status", "COMPLETE").put("pendingRows", 0);
        }
        qualification.set("binding", binding.deepCopy()); qualification.set("publicSummary", summary.deepCopy());
        String qualificationSha = addDerivedOriginal(originals, mapper.writeValueAsBytes(qualification));
        var assessment = archive.putArray("assessments").addObject().put("readingLedgerRawSha256", readingSha)
                .put("qualificationLedgerRawSha256", qualificationSha).put("outcome", outcome)
                .put("reasonCode", review.path("reasonCode").asText());
        assessment.set("binding", review.path("binding").deepCopy()); seal(assessment, "assessmentSha256");
        ((ObjectNode) review.path("binding")).set("derivedAssessmentSha256", assessment.path("assessmentSha256"));
        summary.set("assessmentSha256", assessment.path("assessmentSha256"));
        summary.put("readingLedgerRawSha256", readingSha).put("qualificationLedgerRawSha256", qualificationSha);
        review.set("literatureAssessment", summary); refreshJudgment(base);
        var result = new Derived(base, archive, paperSha); sealDerived(result); return result;
    }
    private ObjectNode derivedReview(Derived d) { return (ObjectNode) d.base().batch().at("/modules/pp2/results/0/otherPhenotypeReview"); }
    private String addDerivedOriginal(ObjectNode originals, byte[] raw) {
        String pin = CodingTranscriptMaterialSupply.sha(raw);
        originals.putObject(pin).put("rawSha256", pin).put("bytes", raw.length).put("base64", Base64.getEncoder().encodeToString(raw));
        return pin;
    }
    private void sealDerived(Derived d) {
        seal(d.archive(), "archiveSha256"); d.base().batch().set("pp2OtherPhenotypeDerivedArchiveSha256", d.archive().path("archiveSha256"));
    }
    private void rejectDerived(Derived d, String reason) {
        var error = assertThrows(IllegalArgumentException.class, () -> Pp2OtherPhenotypeArchive.verifyDerivedAndExport(
                mapper, d.archive(), d.base().batch(), d.base().archive()));
        assertEquals("PP2_PHENOTYPE_ARCHIVE_" + reason, error.getMessage());
    }

    private Saved fixture() {
        byte[] bytes = "synthetic integrity-control original".getBytes(StandardCharsets.UTF_8);
        String rawSha = CodingTranscriptMaterialSupply.sha(bytes);
        var sources = mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-private-archive-v1");
        var method = sources.putObject("methodSources").put("engineeringControlOnly", true);
        var reader = sources.putObject("readerFacts").put("status", "READ").put("scientificAdoption", false)
                .put("inputRawSha256", "c".repeat(64));
        var observation = sources.putObject("observation").put("schemaVersion", "pp2-other-phenotype-source-observation-v1")
                .put("readerStatus", "READ").put("methodSourcesSha256", hashes.canonicalSha256(method))
                .put("readerFactsSha256", hashes.canonicalSha256(reader)).put("readerInputSha256", "c".repeat(64));
        observation.putObject("sourceCapability").put("methodVersion", Pp2OtherPhenotypeSource.VERSION);
        observation.putArray("readDispositions").addObject().put("readId", "original-control")
                .put("expectedRawSha256", rawSha).put("observedRawSha256", rawSha).put("status", "READ");
        seal(observation, "observationSha256");
        sources.putObject("originals").putObject(rawSha).put("rawSha256", rawSha)
                .put("bytes", bytes.length).put("base64", Base64.getEncoder().encodeToString(bytes));
        seal(sources, "archiveSha256");
        var identity = mapper.createObjectNode().put("methodVersion", Pp2OtherPhenotypeSource.VERSION)
                .put("readerStatus", "READ").put("observationSha256", observation.path("observationSha256").asText())
                .put("methodSourcesSha256", observation.path("methodSourcesSha256").asText());
        var batch = mapper.createObjectNode().put("sourceSnapshotSha256", "a".repeat(64));
        batch.set("pp2OtherPhenotypeSourceIdentity", identity.deepCopy());
        var result = batch.putObject("modules").putObject("pp2").putArray("results").addObject()
                .put("candidateId", "integrity-control").put("hypothesisId", "background-control");
        result.putObject("evaluation").put("appliedStrength", "NONE");
        var projection = result.putObject("sourceProjection").put("schemaVersion", "selected-legacy-context-projection-v1")
                .put("candidateId", "integrity-control").put("hypothesisId", "background-control");
        var prepared = projection.putObject("evaluationContext").put("contextId", "synthetic-integrity-control-context");
        var allele = prepared.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38")
                .put("contig", "1").put("position", 1).put("reference", "A").put("alternate", "C");
        seal(projection, "bindingSha256");
        String contextSha = hashes.canonicalSha256(prepared);
        var policy = result.putObject("hcmPolicyBinding").put("preparedContextSha256", contextSha)
                .put("executionContextSha256", contextSha);
        var review = result.putObject("otherPhenotypeReview").put("schemaVersion", "pp2-other-phenotype-review-v1")
                .put("outcome", "UNKNOWN");
        var binding = review.putObject("binding").put("candidateId", "integrity-control")
                .put("sourceSnapshotSha256", observation.path("observationSha256").asText())
                .put("contextSha256", contextSha).put("hcmPolicyBindingSha256", hashes.canonicalSha256(policy));
        binding.set("allele", allele.deepCopy());
        review.putArray("sourceReadDispositionIds").add("original-control");
        var archive = mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-evidence-archive-v1")
                .put("methodVersion", Pp2OtherPhenotypeArchive.VERSION).put("executionSourceSha256", "a".repeat(64));
        archive.set("sourceObservationIdentity", identity); archive.set("sourceArchive", sources);
        var judgment = archive.putArray("judgments").addObject().put("modulePointer", "/modules/pp2/results/0")
                .put("moduleSha256", hashes.canonicalSha256(result));
        judgment.set("review", review.deepCopy());
        var saved = new Saved(archive, batch); sealOuter(saved); return saved;
    }
    private void refreshJudgment(Saved saved) {
        var result = saved.batch().at("/modules/pp2/results/0");
        var judgment = (ObjectNode) saved.archive().at("/judgments/0");
        judgment.put("moduleSha256", hashes.canonicalSha256(result));
        judgment.set("review", result.path("otherPhenotypeReview").deepCopy()); sealOuter(saved);
    }
    private void sealSources(Saved saved) {
        seal((ObjectNode) saved.archive().path("sourceArchive"), "archiveSha256"); sealOuter(saved);
    }
    private void rebindObservation(Saved saved) {
        var observation = (ObjectNode) saved.archive().at("/sourceArchive/observation");
        seal(observation, "observationSha256");
        var identity = (ObjectNode) saved.archive().path("sourceObservationIdentity");
        identity.set("observationSha256", observation.path("observationSha256").deepCopy());
        saved.batch().set("pp2OtherPhenotypeSourceIdentity", identity.deepCopy());
        ((ObjectNode) saved.batch().at("/modules/pp2/results/0/otherPhenotypeReview/binding"))
                .set("sourceSnapshotSha256", observation.path("observationSha256").deepCopy());
        sealSources(saved); refreshJudgment(saved);
    }
    private void sealOuter(Saved saved) {
        seal(saved.archive(), "archiveSha256");
        saved.batch().set("pp2OtherPhenotypeArchiveSha256", saved.archive().path("archiveSha256").deepCopy());
    }
    private void seal(ObjectNode node, String field) { node.remove(field); node.put(field, hashes.canonicalSha256(node)); }
    private void reject(Saved saved, String reason) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> Pp2OtherPhenotypeArchive.verifyAndExport(mapper, saved.archive(), saved.batch()));
        assertEquals("PP2_PHENOTYPE_ARCHIVE_" + reason, failure.getMessage());
    }
}
