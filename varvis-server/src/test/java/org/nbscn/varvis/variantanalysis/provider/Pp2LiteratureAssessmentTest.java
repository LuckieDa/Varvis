package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reads actual saved synthetic transport/cache artifacts; no clinical or causal positive is manufactured. */
class Pp2LiteratureAssessmentTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private Path sample() {
        String value = System.getProperty("varvis.pp2.readingSample");
        assertNotNull(value, "Actual saved reading sample is required; no skipped acceptance"); return Path.of(value);
    }
    private ObjectNode ledger() throws Exception { return (ObjectNode)mapper.readTree(Files.readAllBytes(sample().resolve("reading-ledger.json"))); }
    private ObjectNode base() {
        var value = mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-review-v1").put("methodVersion", Pp2OtherPhenotypeSource.VERSION)
                .put("outcome", "UNKNOWN").put("reasonCode", "PP2_OTHER_PHENOTYPE_REVIEW_INCOMPLETE");
        var binding = value.putObject("binding").put("candidateId", "engineering-candidate").put("hypothesisId", "engineering-hypothesis")
                .put("contextSha256", "b".repeat(64)).put("hcmPolicyBindingSha256", "c".repeat(64)).put("sourceSnapshotSha256", "a".repeat(64));
        binding.putObject("allele").put("assembly", "GRCh38").put("contig", "1").put("position", 100).put("reference", "A").put("alternate", "G");
        value.putArray("requiredScope"); value.putArray("pending"); value.putArray("conflicts"); value.putArray("qualifiedEvidence"); value.putArray("sourceReadDispositionIds"); return value;
    }
    private Pp2LiteratureAssessment owner() { return new Pp2LiteratureAssessment(mapper, "a".repeat(64), mapper.createObjectNode().put("fixture", "SAVED_SYNTHETIC_READER")); }
    private ObjectNode original(ObjectNode archive, String pin) throws Exception {
        return (ObjectNode)mapper.readTree(Base64.getDecoder().decode(archive.at("/originals/" + pin + "/base64").asText()));
    }
    private Path copiedOriginals() throws Exception {
        Path folder = Files.createDirectory(root.resolve("copy")); Path originals = Files.createDirectory(folder.resolve("originals"));
        try (var files = Files.list(sample().resolve("originals"))) { for (Path file : files.toList()) Files.copy(file, originals.resolve(file.getFileName())); }
        return folder;
    }
    @Test void actualBorrowedPs2ReadingIsPreservedButCannotClaimOtherPhenotypeCoverage() throws Exception {
        var owner = owner(); var actual = ledger(); assertEquals(1, actual.path("borrowedLiteralFactCacheHits").asInt());
        var result = owner.prepare(base(), new Pp2OtherPhenotypeWorkflow.Reading(actual, sample()));
        assertEquals("UNKNOWN", result.path("outcome").asText());
        assertEquals(1, result.at("/literatureAssessment/papersInScope").asInt()); assertEquals(0, result.at("/literatureAssessment/assessedPapers").asInt());
        assertEquals("NOT_ASSESSED", result.at("/literatureAssessment/papers/0/relationshipStatus").asText());
        assertTrue(result.at("/literatureAssessment/papers/0/gaps").toString().contains("QUESTIONS_NOT_ASSESSED"));
        var archive = owner.archive(); var row = archive.path("assessments").get(0); assertNotNull(row);
        var saved = original(archive, row.path("readingLedgerRawSha256").asText()); assertEquals(actual, saved);
        assertTrue(saved.at("/papers/0/checkedChunks/0/purposeBorrowedLiteralFacts").asBoolean());
        assertEquals("PS2", saved.at("/papers/0/checkedChunks/0/originalExtractionCriterion").asText());
        var qualification = original(archive, row.path("qualificationLedgerRawSha256").asText());
        var summary = ((ObjectNode)result.path("literatureAssessment")).deepCopy();
        summary.remove(java.util.List.of("assessmentSha256", "readingLedgerRawSha256", "qualificationLedgerRawSha256"));
        assertEquals(summary, qualification.path("publicSummary"));
    }
    @Test void wrongOriginalPinPreservesActualFailedBytesAndUnchangedAttemptedLedger() throws Exception {
        var actual = ledger(); Path folder = copiedOriginals(); String pin = actual.path("originals").fieldNames().next();
        byte[] wrong = "actual failed source bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(folder.resolve("originals/" + pin + ".body"), wrong);
        var owner = owner(); var result = owner.prepare(base(), new Pp2OtherPhenotypeWorkflow.Reading(actual, folder)); var archive = owner.archive();
        assertEquals("UNKNOWN", result.path("outcome").asText());
        assertTrue(archive.path("originals").has(CodingTranscriptMaterialSupply.sha(wrong)));
        var wrapped = original(archive, archive.at("/assessments/0/readingLedgerRawSha256").asText());
        assertEquals("REJECTED_READING_LEDGER", wrapped.path("status").asText());
        var attempted = original(archive, wrapped.at("/rejectedReadingLedger/archivedRawSha256").asText()); assertEquals(actual, attempted);
        assertEquals(pin, wrapped.at("/failedOriginalReads/0/expectedRawSha256").asText());
        assertEquals(CodingTranscriptMaterialSupply.sha(wrong), wrapped.at("/failedOriginalReads/0/observedRawSha256").asText());
    }
    @Test void falseLedgerHashRetainsFullAttemptWithoutResigningItsPaperFacts() throws Exception {
        var actual = ledger(); actual.put("ledgerSha256", "f".repeat(64)); var owner = owner();
        var result = owner.prepare(base(), new Pp2OtherPhenotypeWorkflow.Reading(actual, sample())); var archive = owner.archive();
        var wrapped = original(archive, archive.at("/assessments/0/readingLedgerRawSha256").asText());
        assertEquals("PP2_READING_LEDGER_PIN_INVALID", wrapped.at("/rejectedReadingLedger/reasonCode").asText());
        assertEquals(actual, original(archive, wrapped.at("/rejectedReadingLedger/archivedRawSha256").asText()));
        for (var entry : actual.path("originals").properties()) assertTrue(archive.path("originals").has(entry.getKey()),
                "A rejected outer hash does not erase the actual controlled raw source/model bytes");
        assertEquals(0, result.at("/literatureAssessment/assessedPapers").asInt());
    }
    @Test void failedReaderOutputRemainsAnActualOriginalAlongsideFailureReason() throws Exception {
        Path output = root.resolve("failed-output.json"); byte[] actual = "{truncated actual output".getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(output, actual);
        var owner = owner(); var result = owner.prepare(base(), new Pp2OtherPhenotypeWorkflow.Reading(
                Pp2LiteratureAssessment.emptyLedger(mapper, "PP2_READING_EXECUTION_FAILED"), root, output)); var archive = owner.archive();
        assertEquals("UNKNOWN", result.path("outcome").asText()); assertTrue(result.at("/literatureAssessment/gaps").toString().contains("EXECUTION_FAILED"));
        assertTrue(archive.path("originals").has(CodingTranscriptMaterialSupply.sha(actual)));
    }
    @Test void repeatedTargetDoesNotIssueAgainAndTwoTargetsShareTheSameActualOriginals() throws Exception {
        var owner = owner(); var actual = ledger(); var reading = new Pp2OtherPhenotypeWorkflow.Reading(actual, sample());
        var first = owner.prepare(base(), reading); assertEquals(first, owner.prepare(base(), reading));
        assertEquals(1, owner.archive().path("assessments").size()); int originals = owner.archive().path("originals").size();
        var second = base(); ((ObjectNode)second.path("binding")).put("candidateId", "another-target"); owner.prepare(second, reading);
        assertEquals(2, owner.archive().path("assessments").size());
        assertEquals(originals + 1, owner.archive().path("originals").size(), "Only target-bound qualification JSON differs; paper/model bytes remain once");
    }
    @Test void zeroWorkAuditPreservesBoundedEmptyScopeOutcomeWithoutInventingFacts() throws Exception {
        var base = base(); base.put("outcome", "NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE").put("reasonCode", "PP2_NO_OTHER_PHENOTYPE_SUPPORT_IN_COMPLETED_DECLARED_SCOPE");
        var owner = owner(); var result = owner.prepare(base, new Pp2OtherPhenotypeWorkflow.Reading(
                Pp2LiteratureAssessment.emptyLedger(mapper, "PP2_NO_TARGET_PAPER_REQUIRES_READING"), root));
        assertEquals(base.path("outcome"), result.path("outcome")); assertTrue(result.at("/literatureAssessment/papers").isEmpty());
        assertEquals("COMPLETED_EMPTY_DECLARED_SCOPE", result.at("/literatureAssessment/scopeStatus").asText());
        assertEquals(1, owner.archive().path("assessments").size());
    }
    private Path caseSample() {
        String value = System.getProperty("varvis.pp2.caseReadingSample");
        assertNotNull(value, "Actual saved two-case transport fixture is required; no skip is acceptance"); return Path.of(value);
    }
    @Test void actualTwoCaseCoreFixturePreservesCasesAndParentalSubjectsWithoutCombiningDiseaseAndAllele() throws Exception {
        var actual = (ObjectNode)mapper.readTree(Files.readAllBytes(caseSample().resolve("reading-ledger.json")));
        var owner = owner(); var result = owner.prepare(base(), new Pp2OtherPhenotypeWorkflow.Reading(actual, caseSample()));
        var paper = result.at("/literatureAssessment/papers/0"); assertFalse(paper.has("facts"), "No ambiguous flattened patient facts");
        assertEquals(2, paper.path("casesInSource").asInt()); assertEquals(14, paper.path("factsInSource").asInt());
        assertFalse(paper.path("factsTruncated").asBoolean());
        var one = paper.path("cases").get(0); var two = paper.path("cases").get(1); assertNotNull(one); assertNotNull(two);
        assertEquals("1", one.path("caseLabel").asText()); assertEquals("2", two.path("caseLabel").asText());
        assertTrue(one.path("facts").toString().contains("c.100A>G")); assertTrue(one.path("facts").toString().contains("hypertrophic"));
        assertFalse(one.path("facts").toString().contains("c.200C>T")); assertFalse(one.path("facts").toString().contains("dilated cardiomyopathy"));
        assertTrue(two.path("facts").toString().contains("c.200C>T")); assertTrue(two.path("facts").toString().contains("dilated"));
        assertFalse(two.path("facts").toString().contains("c.100A>G"));
        boolean mother = false, father = false;
        for (var fact : one.path("facts")) if ("reportedMotherTargetResult".equals(fact.path("field").asText())) mother = "MOTHER".equals(fact.path("subject").asText());
        for (var fact : two.path("facts")) if ("reportedFatherTargetResult".equals(fact.path("field").asText())) father = "FATHER".equals(fact.path("subject").asText());
        assertTrue(mother); assertTrue(father); assertTrue(one.path("caseCitation").isObject()); assertTrue(two.path("caseCitation").isObject());
        assertEquals("UNKNOWN", result.path("outcome").asText()); assertEquals(0, result.at("/literatureAssessment/assessedPapers").asInt());
        String artifacts = System.getProperty("varvis.pp2.assessmentArtifactRoot");
        if (artifacts != null) {
            Path folder = Path.of(artifacts); Files.createDirectories(folder);
            Files.write(folder.resolve("actual-case-projection.json"), mapper.writeValueAsBytes(result));
            Files.write(folder.resolve("actual-derived-archive.json"), mapper.writeValueAsBytes(owner.archive()));
        }
    }
    @Test void displayOnlyLimitReportsActualCountAndRetainsTheWholeEngineeringLedger() throws Exception {
        // This is only a display-budget metadata control. The actual two-case/raw model fixture remains unchanged.
        var actual = (ObjectNode)mapper.readTree(Files.readAllBytes(caseSample().resolve("reading-ledger.json")));
        var fields = (com.fasterxml.jackson.databind.node.ArrayNode)actual.at("/papers/0/checkedChunks/0/review/cases/0/fields");
        var copy = fields.deepCopy(); for (var field : copy) fields.add(field.deepCopy());
        actual.remove("ledgerSha256"); actual.put("ledgerSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(actual));
        var owner = owner(); var result = owner.prepare(base(), new Pp2OtherPhenotypeWorkflow.Reading(actual, caseSample()));
        var paper = result.at("/literatureAssessment/papers/0"); assertEquals(21, paper.path("factsInSource").asInt());
        assertEquals(16, paper.path("displayedFacts").asInt()); assertTrue(paper.path("factsTruncated").asBoolean());
        assertEquals(actual, original(owner.archive(), owner.archive().at("/assessments/0/readingLedgerRawSha256").asText()));
        assertEquals("UNKNOWN", result.path("outcome").asText());
    }

    private Path relationshipSample() {
        String value = System.getProperty("varvis.pp2.relationshipReadingSample");
        assertNotNull(value, "Actual saved1.2 reader sample root required; no skip is acceptance"); return Path.of(value);
    }
    private Pp2LiteratureAssessment owner12(ObjectNode actual) {
        var methods = mapper.createObjectNode(); methods.putObject("derivedReaderImplementation").set("pp2_relationship_material.py",
                actual.at("/implementation/pp2_relationship_material.py").deepCopy());
        return new Pp2LiteratureAssessment(mapper, "a".repeat(64), methods);
    }
    @Test void actual12QuestionsRetainCohortAndFunctionWithoutInventingCasesOrCausalQualification() throws Exception {
        Path sample = relationshipSample().resolve("complete-raw-only");
        var actual = (ObjectNode)mapper.readTree(Files.readAllBytes(sample.resolve("reading-ledger.json")));
        var owner = owner12(actual); var result = owner.prepare(base(), new Pp2OtherPhenotypeWorkflow.Reading(actual, sample));
        var paper = result.at("/literatureAssessment/papers/0");
        assertEquals("1.2.0", result.at("/literatureAssessment/readingMethodVersion").asText());
        assertEquals("RAW_QUESTION_SCOPE_READ", paper.at("/questionCoverage/status").asText());
        assertEquals(2, paper.path("relationshipRecordsInSource").asInt()); assertEquals(0, paper.path("casesInSource").asInt());
        assertEquals("COHORT", paper.at("/relationshipRecords/0/materialKindProposal").asText());
        assertEquals("FUNCTIONAL_STUDY", paper.at("/relationshipRecords/1/materialKindProposal").asText());
        assertEquals("UNRESOLVED", paper.at("/relationshipRecords/0/targetMatch").asText());
        assertEquals("STUDY_OBSERVATION", paper.at("/relationshipRecords/0/facts/0/group").asText());
        assertTrue(paper.at("/relationshipRecords/0/facts/0/citation/quote").isTextual());
        assertEquals("UNKNOWN", result.path("outcome").asText()); assertEquals(0, result.at("/literatureAssessment/assessedPapers").asInt());
        assertEquals("PP2_OTHER_PHENOTYPE_RELATIONSHIP_NOT_ESTABLISHED", result.path("reasonCode").asText());
        assertEquals(actual, original(owner.archive(), owner.archive().at("/assessments/0/readingLedgerRawSha256").asText()));
        String artifacts = System.getProperty("varvis.pp2.assessmentArtifactRoot");
        if (artifacts != null) { Path folder = Path.of(artifacts); Files.createDirectories(folder);
            Files.write(folder.resolve("actual-relationship-projection.json"), mapper.writeValueAsBytes(result));
            Files.write(folder.resolve("actual-relationship-derived-archive.json"), mapper.writeValueAsBytes(owner.archive())); }
    }
    @Test void actual12SharedBudgetGapKeepsKnownPlanCountsAndConcreteUnreadReason() throws Exception {
        Path sample = relationshipSample().resolve("shared-one-call-limit");
        var actual = (ObjectNode)mapper.readTree(Files.readAllBytes(sample.resolve("reading-ledger.json")));
        var result = owner12(actual).prepare(base(), new Pp2OtherPhenotypeWorkflow.Reading(actual, sample));
        var paper = result.at("/literatureAssessment/papers/0");
        assertEquals(1, paper.at("/questionCoverage/expectedPlans").asInt()); assertEquals(0, paper.at("/questionCoverage/checkedPlans").asInt());
        assertEquals("INCOMPLETE", paper.at("/questionCoverage/status").asText()); assertEquals(0, paper.path("relationshipRecordsInSource").asInt());
        assertTrue(paper.path("gaps").toString().contains("PP2_READING_NEW_WORK_LIMIT"));
        assertEquals("PP2_RELATIONSHIP_QUESTION_SCOPE_INCOMPLETE", result.path("reasonCode").asText());
        assertEquals("UNKNOWN", result.path("outcome").asText());
    }
    @Test void actualLongPublicArticleRetainsDistinctAttachmentGapAfterManyRepeatedCacheMisses() throws Exception {
        String folder = java.util.Objects.requireNonNull(System.getProperty("varvis.pp2.realOriginalSample"), "Actual offline public-source sample required");
        Path sample = Path.of(folder); var actual = (ObjectNode) mapper.readTree(Files.readAllBytes(sample.resolve("reading-ledger.json")));
        assertTrue(actual.at("/papers/0/pending").size() > 100);
        var owner = owner12(actual); var result = owner.prepare(base(), new Pp2OtherPhenotypeWorkflow.Reading(actual, sample));
        assertEquals("UNKNOWN", result.path("outcome").asText());
        var reasons = result.at("/literatureAssessment/papers/0/gaps");
        assertEquals(3, reasons.size());
        assertTrue(reasons.toString().contains("PP2_READING_MODEL_DISABLED_CACHE_MISSING"));
        assertTrue(reasons.toString().contains("PP2_READING_ATTACHMENT_COVERAGE_INCOMPLETE"));
        assertEquals(actual, original(owner.archive(), result.at("/literatureAssessment/readingLedgerRawSha256").asText()));
    }
    @Test void actual12RequiresObservedQuestionCodePinAndUnknownFutureReadingIsNotPromoted() throws Exception {
        Path sample = relationshipSample().resolve("complete-raw-only");
        var actual = (ObjectNode)mapper.readTree(Files.readAllBytes(sample.resolve("reading-ledger.json")));
        var mismatch = owner().prepare(base(), new Pp2OtherPhenotypeWorkflow.Reading(actual, sample));
        assertEquals("UNKNOWN", mismatch.path("outcome").asText());
        assertTrue(mismatch.at("/literatureAssessment/gaps").toString().contains("PP2_READING_QUESTION_METHOD_PIN_MISMATCH"));
        var future = actual.deepCopy().put("methodVersion", "9.9.9"); future.remove("ledgerSha256");
        future.put("ledgerSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(future));
        var rejected = owner12(actual).prepare(base(), new Pp2OtherPhenotypeWorkflow.Reading(future, sample));
        assertEquals("UNKNOWN", rejected.path("outcome").asText());
        assertTrue(rejected.at("/literatureAssessment/gaps").toString().contains("PP2_READING_LEDGER_CONTRACT_INVALID"));
    }

    private Path boundedSample() {
        String value = System.getProperty("varvis.pp2.boundedReadingSample");
        assertNotNull(value, "Actual bounded Python reading sample is required");
        return Path.of(value);
    }
    private ObjectNode boundedJson(String file) throws Exception {
        return (ObjectNode)mapper.readTree(Files.readAllBytes(boundedSample().resolve(file)));
    }
    private ObjectNode boundedBase(ObjectNode bounded, ObjectNode ledger, boolean nativeComplete, boolean retrievalComplete) {
        var base = base();
        var binding = (ObjectNode)base.path("binding");
        binding.set("allele", bounded.path("targetAllele").deepCopy());
        binding.set("contextSha256", bounded.at("/materialIdentity/contextSha256").deepCopy());
        binding.set("hcmPolicyBindingSha256", bounded.at("/materialIdentity/hcmPolicyBindingSha256").deepCopy());
        var scopes = (com.fasterxml.jackson.databind.node.ArrayNode)base.path("requiredScope");
        scopes.addObject().put("scope", "EXACT_TARGET_NATIVE_EXPERT_ASSERTIONS")
                .put("status", nativeComplete ? "COMPLETED_DECLARED_TARGET_SCOPE" : "INCOMPLETE")
                .put("targetRecords", 0).put("qualifiedRecords", 0)
                .putObject("nativeSourceCoverage").put("status", nativeComplete ? "COMPLETE" : "INCOMPLETE");
        var literature = scopes.addObject().put("scope", "TARGET_LITERATURE_RELATIONSHIP_REVIEW").put("status", "INCOMPLETE")
                .put("candidatePapers", 0).put("retrievalScopeComplete", retrievalComplete).put("aliasIdentityVerified", true);
        literature.set("knownCitationReferences", ledger.path("knownCitationReferences").deepCopy());
        var pending = (com.fasterxml.jackson.databind.node.ArrayNode)base.path("pending");
        pending.addObject().put("reasonCode", "PP2_OTHER_PHENOTYPE_TARGET_PAPERS_NOT_REVIEWED");
        if (!nativeComplete) pending.addObject().put("reasonCode", "PP2_TARGET_NATIVE_EXPERT_SCOPE_NOT_ESTABLISHED");
        return base;
    }
    @Test void actualCitationOnlyBoundedProofCanCloseFinitePaperAndExpertScopesWithoutInventingCausality() throws Exception {
        var ledger = boundedJson("reading-ledger.json"); var bounded = boundedJson("bounded-review.json");
        assertTrue(ledger.path("readingComplete").asBoolean()); assertEquals(1, ledger.path("papers").size());
        var owner = owner12(ledger);
        var review = owner.prepare(boundedBase(bounded, ledger, true, true), new Pp2OtherPhenotypeWorkflow.Reading(ledger, boundedSample(), null, bounded), true);
        assertEquals("NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE", review.path("outcome").asText());
        var summary = review.path("literatureAssessment");
        assertEquals("COMPLETED_DECLARED_REVIEWED_SCOPE", summary.path("scopeStatus").asText());
        assertEquals("COMPLETE_DECLARED_SCOPE", summary.path("qualificationScopeStatus").asText());
        assertEquals(0, summary.path("declaredCandidateCount").asInt(-1));
        assertEquals(1, summary.path("declaredKnownCitationCount").asInt());
        assertEquals(1, summary.path("assessedPapers").asInt()); assertEquals(1, summary.path("papersInScope").asInt());
        var observation = summary.at("/papers/0/relationshipRecords/0");
        assertEquals("EXACT", observation.path("targetMatch").asText());
        assertEquals("CURRENT_TARGET_HCM_PROCESS", observation.path("disposition").asText());
        assertTrue(review.path("qualifiedEvidence").isEmpty()); assertTrue(review.path("pending").isEmpty());
        var archive = owner.archive(); var saved = original(archive, summary.path("qualificationLedgerRawSha256").asText());
        var finite = (ObjectNode)saved.path("boundedLiteratureReview");
        assertEquals(summary.path("readingLedgerRawSha256"), finite.path("readingLedgerRawSha256"));
        assertEquals(archive.path("methodSourcesSha256"), finite.path("methodSourcesSha256"));
        var originalBounded = finite.deepCopy(); originalBounded.remove(java.util.List.of("binding", "readingLedgerRawSha256", "methodSourcesSha256"));
        assertEquals(bounded, originalBounded);
        assertEquals(ledger, original(archive, summary.path("readingLedgerRawSha256").asText()));
        String artifacts = System.getProperty("varvis.pp2.boundedArtifactRoot");
        if (artifacts != null) {
            Path output = Path.of(artifacts); Files.createDirectories(output);
            Files.write(output.resolve("actual-bounded-public-review.json"), mapper.writeValueAsBytes(review));
            Files.write(output.resolve("actual-bounded-derived-archive.json"), mapper.writeValueAsBytes(archive));
        }
    }
    @Test void actualBoundedPaperCannotClearAnIncompleteExpertOrDiscoveryScope() throws Exception {
        var ledger = boundedJson("reading-ledger.json"); var bounded = boundedJson("bounded-review.json");
        var reading = new Pp2OtherPhenotypeWorkflow.Reading(ledger, boundedSample(), null, bounded);
        var nativeGap = owner12(ledger).prepare(boundedBase(bounded, ledger, false, true), reading, true);
        assertEquals("UNKNOWN", nativeGap.path("outcome").asText());
        assertEquals("COMPLETE_DECLARED_SCOPE", nativeGap.at("/literatureAssessment/qualificationScopeStatus").asText());
        assertEquals("PP2_TARGET_NATIVE_EXPERT_SCOPE_NOT_ESTABLISHED", nativeGap.at("/pending/0/reasonCode").asText());
        assertEquals(1, nativeGap.at("/literatureAssessment/assessedPapers").asInt());
        var discoveryGap = owner12(ledger).prepare(boundedBase(bounded, ledger, true, false), reading, true);
        assertEquals("UNKNOWN", discoveryGap.path("outcome").asText()); assertEquals(1, discoveryGap.at("/literatureAssessment/assessedPapers").asInt());
        var mismatched = boundedBase(bounded, ledger, true, true);
        ((ObjectNode)mismatched.path("binding")).put("contextSha256", "f".repeat(64));
        var wrongContext = owner12(ledger).prepare(mismatched, reading, true);
        assertEquals("UNKNOWN", wrongContext.path("outcome").asText());
        assertFalse(wrongContext.path("literatureAssessment").has("qualificationVersion"));
    }

}
