package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/** Same-owner after-claim reading archive. Actual raw facts are not a causal grant. */
final class Pp2LiteratureAssessment {
    static final String VERSION = "pp2-literature-assessment-v1";
    private static final int MAX_FILE = 4 * 1024 * 1024;
    private static final long MAX_TOTAL = 32L * 1024 * 1024;
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final ObjectNode originals, methodSources;
    private final com.fasterxml.jackson.databind.node.ArrayNode assessments;
    private final String sourceSnapshotSha256;
    private final java.util.Map<String, ObjectNode> cached = new java.util.LinkedHashMap<>();
    Pp2LiteratureAssessment(ObjectMapper mapper, String sourceSnapshotSha256, ObjectNode methodSources) {
        this.mapper = mapper; this.adapter = new RuntimeEvidenceV2Adapter(mapper);
        this.sourceSnapshotSha256 = sourceSnapshotSha256; this.methodSources = methodSources.deepCopy();
        this.originals = mapper.createObjectNode(); this.assessments = mapper.createArrayNode();
    }
    @org.springframework.lang.Nullable ObjectNode prior(ObjectNode baseReview) {
        var value = cached.get(adapter.canonicalSha256(baseReview.path("binding"))); return value == null ? null : value.deepCopy();
    }
    ObjectNode prepare(ObjectNode baseReview, Pp2OtherPhenotypeWorkflow.Reading reading) {
        return prepare(baseReview, reading, false);
    }
    ObjectNode prepare(ObjectNode baseReview, Pp2OtherPhenotypeWorkflow.Reading reading, boolean boundedCapability) {
        String key = adapter.canonicalSha256(baseReview.path("binding"));
        var cachedResult = cached.get(key); if (cachedResult != null) return cachedResult.deepCopy();
        var ledger = reading.ledger().deepCopy(); boolean retained = true;
        var failures = mapper.createArrayNode();
        byte[] attemptedLedger;
        try { attemptedLedger = mapper.writeValueAsBytes(ledger); }
        catch (IOException invalid) { throw new IllegalArgumentException("PP2_READING_LEDGER_SERIALIZATION_FAILED", invalid); }
        String attemptedLedgerSha = CodingTranscriptMaterialSupply.sha(attemptedLedger);
        String readReason = "PP2_OTHER_PHENOTYPE_QUESTIONS_NOT_ASSESSED";
        try {
            String ledgerIssue = null;
            if (!"pp2-relation-reading-v1".equals(ledger.path("schemaVersion").asText())
                    || !List.of("1.1.0", "1.2.0").contains(ledger.path("methodVersion").asText())
                    || !"NOT_GRANTED".equals(ledger.path("relationQualification").asText())
                    || !ledger.path("scientificAdoption").isBoolean() || ledger.path("scientificAdoption").asBoolean())
                ledgerIssue = "PP2_READING_LEDGER_CONTRACT_INVALID";
            if ("1.2.0".equals(ledger.path("methodVersion").asText())) {
                String actualQuestion = methodSources.at("/derivedReaderImplementation/pp2_relationship_material.py").asText();
                if (!actualQuestion.matches("[a-f0-9]{64}") || !actualQuestion.equals(ledger.at("/implementation/pp2_relationship_material.py").asText()))
                    ledgerIssue = "PP2_READING_QUESTION_METHOD_PIN_MISMATCH";
            }
            var unhashed = ledger.deepCopy(); unhashed.remove("ledgerSha256");
            if (!adapter.canonicalSha256(unhashed).equals(ledger.path("ledgerSha256").asText()))
                ledgerIssue = "PP2_READING_LEDGER_PIN_INVALID";
            for (var entry : ledger.path("originals").properties()) {
                String pin = entry.getKey(); var value = entry.getValue();
                if (!pin.matches("[a-f0-9]{64}") || !pin.equals(value.path("rawSha256").asText())
                        || !("originals/" + pin + ".body").equals(value.path("relativePath").asText()))
                    throw new IllegalArgumentException("PP2_READING_ORIGINAL_REFERENCE_INVALID");
                Path root = reading.controlledWork().toRealPath(), path = root.resolve(value.path("relativePath").asText()).toRealPath();
                if (!path.startsWith(root)) throw new IllegalArgumentException("PP2_READING_ORIGINAL_PATH_OUTSIDE_CONTROL");
                long size = Files.size(path);
                if (size > MAX_FILE) {
                    String actual = streamedSha(path);
                    failures.addObject().put("expectedRawSha256", pin).put("observedRawSha256", actual).put("bytes", size)
                            .put("archiveRetained", false).put("reasonCode", "PP2_READING_ARCHIVE_TECHNICAL_LIMIT");
                    throw new IllegalArgumentException("PP2_READING_ARCHIVE_TECHNICAL_LIMIT");
                }
                byte[] raw;
                try (var stream = Files.newInputStream(path)) { raw = stream.readNBytes(MAX_FILE + 1); }
                String actual = CodingTranscriptMaterialSupply.sha(raw);
                // Preserve bytes actually read even when their expected pin is false.
                if (raw.length <= MAX_FILE) retainInput(raw, value.path("provenance"));
                if (raw.length != value.path("bytes").asInt() || !pin.equals(actual)) {
                    failures.addObject().put("expectedRawSha256", pin).put("observedRawSha256", actual).put("bytes", raw.length)
                            .put("reasonCode", "PP2_READING_ORIGINAL_PIN_INVALID");
                    throw new IllegalArgumentException("PP2_READING_ORIGINAL_PIN_INVALID");
                }
            }
            if (ledgerIssue != null) throw new IllegalArgumentException(ledgerIssue);
        } catch (IOException | RuntimeException failed) {
            retained = false; readReason = safe(failed, "PP2_READING_ORIGINALS_UNAVAILABLE");
            ledger = emptyLedger(mapper, readReason); ledger.put("status", "REJECTED_READING_LEDGER");
            var audit = ledger.putObject("rejectedReadingLedger").put("actualRawSha256", attemptedLedgerSha).put("bytes", attemptedLedger.length)
                    .put("claimedCanonicalSha256", reading.ledger().path("ledgerSha256").asText()).put("reasonCode", readReason);
            try { audit.put("archivedRawSha256", retainInput(attemptedLedger, provenance("ACTUAL_REJECTED_READING_LEDGER"))); }
            catch (RuntimeException limit) { audit.put("archiveRetained", false).put("reasonCode", "PP2_READING_ARCHIVE_TECHNICAL_LIMIT"); }
            ledger.set("failedOriginalReads", failures); rehash(ledger);
        }
        Path output = reading.attemptedOutput();
        if (output != null && Files.isRegularFile(output)) {
            try {
                long size = Files.size(output);
                var audit = ledger.putObject("attemptedReaderOutput").put("bytes", size);
                if (size <= MAX_FILE) {
                    byte[] actualOutput = Files.readAllBytes(output); String actual = CodingTranscriptMaterialSupply.sha(actualOutput);
                    audit.put("rawSha256", actual);
                    try { audit.put("archivedRawSha256", retainInput(actualOutput, provenance("ACTUAL_FAILED_READER_OUTPUT"))); }
                    catch (RuntimeException limit) { audit.put("archiveRetained", false).put("reasonCode", "PP2_READING_ARCHIVE_TECHNICAL_LIMIT"); }
                } else {
                    var digest = java.security.MessageDigest.getInstance("SHA-256");
                    try (var stream = Files.newInputStream(output)) { byte[] buffer = new byte[8192]; int count;
                        while ((count = stream.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count); }
                    audit.put("rawSha256", java.util.HexFormat.of().formatHex(digest.digest())).put("archiveRetained", false)
                            .put("reasonCode", "PP2_READING_ARCHIVE_TECHNICAL_LIMIT");
                }
                rehash(ledger);
            } catch (IOException | java.security.NoSuchAlgorithmException failure) {
                ledger.putObject("attemptedReaderOutput").put("status", "UNAVAILABLE").put("reasonCode", "PP2_READER_OUTPUT_AUDIT_UNAVAILABLE"); rehash(ledger);
            }
        }
        byte[] ledgerRaw;
        try { ledgerRaw = mapper.writeValueAsBytes(ledger); }
        catch (IOException invalid) { throw new IllegalArgumentException("PP2_READING_LEDGER_SERIALIZATION_FAILED", invalid); }
        if (ledgerRaw.length > MAX_FILE || total() + (originals.has(CodingTranscriptMaterialSupply.sha(ledgerRaw)) ? 0 : ledgerRaw.length) > MAX_TOTAL - 65536) {
            retained = false; readReason = "PP2_READING_ARCHIVE_TECHNICAL_LIMIT";
            ledger = emptyLedger(mapper, readReason);
            ledger.putObject("unretainedReadingLedger").put("rawSha256", CodingTranscriptMaterialSupply.sha(ledgerRaw))
                    .put("bytes", ledgerRaw.length).put("archiveRetained", false).put("reasonCode", readReason);
            rehash(ledger);
            try { ledgerRaw = mapper.writeValueAsBytes(ledger); }
            catch (IOException invalid) { throw new IllegalArgumentException("PP2_READING_LEDGER_SERIALIZATION_FAILED", invalid); }
        }
        String ledgerPin = retain(ledgerRaw, provenance("ACTUAL_AFTER_CLAIM_READING_LEDGER"));
        var summary = mapper.createObjectNode().put("methodVersion", VERSION).put("scopeStatus", "NOT_COMPLETED")
                .put("papersInScope", ledger.path("papers").size()).put("assessedPapers", 0)
                .put("readingMethodVersion", ledger.path("methodVersion").asText());
        if (ledger.path("readingScopeMeaning").isTextual()) summary.set("readingScopeMeaning", ledger.path("readingScopeMeaning").deepCopy());
        if (ledger.path("wholePublicationRead").isBoolean()) summary.set("wholePublicationRead", ledger.path("wholePublicationRead").deepCopy());
        var cards = summary.putArray("papers");
        int paperCount = 0;
        for (var paper : ledger.path("papers")) {
            if (++paperCount > 200) break;
            var card = cards.addObject().put("status", paper.path("status").asText("UNAVAILABLE"))
                    .put("relationshipStatus", "NOT_ASSESSED");
            for (String field : List.of("pmid", "pmcid", "sourceRawSha256", "bundleSha256"))
                if (paper.path(field).isTextual()) card.set(field, paper.path(field).deepCopy());
            if (paper.path("title").isTextual() && paper.path("title").asText().length() <= 600) card.set("title", paper.path("title").deepCopy());
            if (paper.path("unreviewedVisualCaptionUnitIds").isArray()) card.put("unreviewedVisualCaptionCount", paper.path("unreviewedVisualCaptionUnitIds").size());
            var cases = card.putArray("cases"); int casesInSource = 0, factsInSource = 0, displayedFacts = 0;
            if (retained) for (var chunk : paper.path("checkedChunks")) {
                int caseOrdinal = 0;
                for (var row : chunk.at("/review/cases")) {
                    casesInSource++; caseOrdinal++;
                    ObjectNode group = null;
                    if (cases.size() < 8) {
                        group = cases.addObject().put("chunkIndex", chunk.path("chunkIndex").asInt()).put("caseOrdinal", caseOrdinal)
                                .put("caseLabel", row.path("caseLabel").asText());
                        if (row.path("caseOriginProposal").isTextual()) group.set("caseOriginProposal", row.path("caseOriginProposal").deepCopy());
                        var anchor = readableCitation(row.path("caseCitation")); if (anchor != null) group.set("caseCitation", anchor);
                        group.putArray("facts");
                    }
                    int groupFacts = 0, groupDisplayed = 0;
                    for (var field : row.path("fields")) {
                        if (!List.of("geneSymbol", "variantText", "transcript", "proteinChange", "reportedDiagnosis", "reportedPhenotype",
                                "reportedInheritance", "reportedFamilyId", "reportedFatherGenotype", "reportedMotherGenotype", "reportedProbandTargetResult",
                                "reportedMotherTargetResult", "reportedFatherTargetResult", "reportedProbandGenotype", "reportedAge", "reportedOnset", "reportedDiagnosisCertainty").contains(field.path("field").asText())) continue;
                        var citation = readableCitation(field.path("citation")); if (citation == null) continue;
                        factsInSource++; groupFacts++;
                        if (group == null || displayedFacts >= 16) continue;
                        var fact = ((com.fasterxml.jackson.databind.node.ArrayNode)group.path("facts")).addObject()
                                .put("field", field.path("field").asText()).put("value", field.path("sourceValue").asText());
                        if (field.path("subject").isTextual()) fact.set("subject", field.path("subject").deepCopy());
                        fact.set("citation", citation); displayedFacts++; groupDisplayed++;
                    }
                    if (group != null) group.put("factsInSource", groupFacts).put("displayedFacts", groupDisplayed).put("factsTruncated", groupFacts > groupDisplayed);
                }
            }
            card.put("casesInSource", casesInSource).put("displayedCases", cases.size()).put("casesTruncated", casesInSource > cases.size())
                    .put("factsInSource", factsInSource).put("displayedFacts", displayedFacts).put("factsTruncated", factsInSource > displayedFacts);
            var rawQuestions = paper.path("questionCoverage");
            if ("1.2.0".equals(ledger.path("methodVersion").asText())) {
                var coverage = card.putObject("questionCoverage");
                for (String field : List.of("questionVersion", "status", "expectedPlans", "checkedPlans", "relationQualification"))
                    if (rawQuestions.has(field)) coverage.set(field, rawQuestions.path(field).deepCopy());
                projectRelationships(card, paper);
            }
            var gaps = card.putArray("gaps");
            for (var issue : card.path("relationshipProjectionIssues")) gaps.addObject().put("requirement", "EXACT_PP2_QUESTION_PROFILE_AND_PURPOSE")
                    .put("available", "PROFILE_OR_PURPOSE_NOT_VERIFIED").put("missing", issue.path("reasonCode").asText()).put("reasonCode", issue.path("reasonCode").asText());
            for (var gap : paper.path("pending")) if (gaps.size() < 12 && newGapReason(gaps, gap)) gaps.addObject()
                    .put("requirement", "COMPLETE_DECLARED_SOURCE_READING").put("available", paper.path("status").asText())
                    .put("missing", gap.path("reasonCode").asText()).put("reasonCode", gap.path("reasonCode").asText());
            if ("1.2.0".equals(ledger.path("methodVersion").asText())) {
                for (var gap : rawQuestions.path("gaps")) if (gaps.size() < 16 && newGapReason(gaps, gap)) gaps.addObject()
                        .put("requirement", "COMPLETE_DECLARED_RELATIONSHIP_QUESTIONS").put("available", rawQuestions.path("status").asText())
                        .put("missing", gap.path("reasonCode").asText()).put("reasonCode", gap.path("reasonCode").asText());
            }
            String condition = "1.2.0".equals(ledger.path("methodVersion").asText())
                    ? "RAW_QUESTION_SCOPE_READ".equals(rawQuestions.path("status").asText())
                        ? "PP2_OTHER_PHENOTYPE_RELATIONSHIP_NOT_ESTABLISHED" : "PP2_RELATIONSHIP_QUESTION_SCOPE_INCOMPLETE"
                    : "PP2_OTHER_PHENOTYPE_QUESTIONS_NOT_ASSESSED";
            gaps.addObject().put("requirement", "TARGET_VARIANT_OTHER_PHENOTYPE_RELATIONSHIP_ASSESSMENT")
                    .put("available", "1.2.0".equals(ledger.path("methodVersion").asText()) ? "QUOTE_CHECKED_RELATIONSHIP_MATERIAL" : "QUOTE_CHECKED_CORE_MATERIAL")
                    .put("missing", "QUALIFIED_TARGET_IDENTITY_AND_RELATED_CLINICAL_BACKGROUND").put("reasonCode", condition);
        }
        var publicReview = baseReview.deepCopy();
        String outcome = baseReview.path("outcome").asText(), reason = baseReview.path("reasonCode").asText();
        if ("NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE".equals(outcome)) summary.put("scopeStatus", "COMPLETED_EMPTY_DECLARED_SCOPE");
        else if ("SUPPORTS_OTHER_PHENOTYPE".equals(outcome)) summary.put("scopeStatus", "NATIVE_EXPERT_EXCLUSION_ALREADY_ESTABLISHED");
        else if ("UNKNOWN".equals(outcome) && !cards.isEmpty()) {
            boolean questionRead = "1.2.0".equals(ledger.path("methodVersion").asText());
            if (questionRead) for (var card : cards) if (!"RAW_QUESTION_SCOPE_READ".equals(card.at("/questionCoverage/status").asText()) || !card.path("relationshipProjectionIssues").isEmpty()) questionRead = false;
            reason = retained ? questionRead ? "PP2_OTHER_PHENOTYPE_RELATIONSHIP_NOT_ESTABLISHED"
                    : "1.2.0".equals(ledger.path("methodVersion").asText()) ? "PP2_RELATIONSHIP_QUESTION_SCOPE_INCOMPLETE"
                    : "PP2_OTHER_PHENOTYPE_QUESTIONS_NOT_ASSESSED" : readReason;
            publicReview.put("reasonCode", reason);
            ((com.fasterxml.jackson.databind.node.ArrayNode)publicReview.path("pending")).addObject().put("reasonCode", reason);
        }
        summary.put("outcome", outcome).put("reasonCode", reason);
        var reviewGaps = summary.putArray("gaps");
        for (var gap : ledger.path("pending")) if (reviewGaps.size() < 16 && newGapReason(reviewGaps, gap)) reviewGaps.addObject()
                .put("requirement", "COMPLETE_DECLARED_READING_AND_RELATIONSHIP_REVIEW")
                .put("available", ledger.path("status").asText()).put("missing", gap.path("reasonCode").asText()).put("reasonCode", gap.path("reasonCode").asText());
        if (!failures.isEmpty()) reviewGaps.addObject().put("requirement", "ACTUAL_SOURCE_BYTES_MATCH_PIN")
                .put("available", "ACTUAL_FAILED_BYTES_RETAINED").put("missing", "MATCHING_SOURCE_PIN").put("reasonCode", readReason);
        ObjectNode qualification = qualification(baseReview, summary);
        var bounded = reading.boundedReview();
        if (boundedCapability && bounded != null && retained && boundedBound(baseReview, ledger, bounded)) {
            var qualifiedSummary = summary.deepCopy(); var qualifiedReview = publicReview.deepCopy();
            projectQualification(baseReview, ledger, bounded, qualifiedReview, qualifiedSummary);
            var candidate = qualification(baseReview, qualifiedSummary);
            var saved = bounded.deepCopy();
            saved.set("binding", baseReview.path("binding").deepCopy());
            saved.put("readingLedgerRawSha256", ledgerPin).put("methodSourcesSha256", adapter.canonicalSha256(methodSources));
            candidate.set("boundedLiteratureReview", saved);
            byte[] proposed = qualificationBytes(candidate);
            if (proposed.length <= MAX_FILE && total() + proposed.length <= MAX_TOTAL) {
                qualification = candidate; summary = qualifiedSummary; publicReview = qualifiedReview;
                outcome = summary.path("outcome").asText(); reason = summary.path("reasonCode").asText();
            } else {
                reviewGaps.addObject().put("requirement", "BOUNDED_REVIEW_ORIGINALS_ARCHIVED")
                        .put("available", "ACTUAL_SCOPE_REVIEW_NOT_ARCHIVED").put("missing", "AVAILABLE_ARCHIVE_BUDGET")
                        .put("reasonCode", "PP2_QUALIFICATION_ARCHIVE_TECHNICAL_LIMIT");
                qualification = qualification(baseReview, summary);
            }
        }
        String qualificationPin = retain(qualificationBytes(qualification), provenance("ACTUAL_BOUNDED_RELATIONSHIP_ASSESSMENT"));
        var assessment = mapper.createObjectNode().put("readingLedgerRawSha256", ledgerPin).put("qualificationLedgerRawSha256", qualificationPin)
                .put("outcome", outcome).put("reasonCode", reason);
        assessment.set("binding", baseReview.path("binding").deepCopy());
        String assessmentPin = adapter.canonicalSha256(assessment); assessment.put("assessmentSha256", assessmentPin); assessments.add(assessment);
        ((ObjectNode)publicReview.path("binding")).put("derivedAssessmentSha256", assessmentPin);
        summary.put("assessmentSha256", assessmentPin).put("readingLedgerRawSha256", ledgerPin).put("qualificationLedgerRawSha256", qualificationPin);
        publicReview.set("literatureAssessment", summary); cached.put(key, publicReview.deepCopy()); return publicReview;
    }
    private ObjectNode qualification(ObjectNode base, ObjectNode summary) {
        var qualification = mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-literature-qualification-v1")
                .put("methodVersion", VERSION).put("outcome", summary.path("outcome").asText()).put("reasonCode", summary.path("reasonCode").asText());
        qualification.set("binding", base.path("binding").deepCopy()); qualification.set("publicSummary", summary.deepCopy());
        return qualification;
    }
    private byte[] qualificationBytes(ObjectNode qualification) {
        try { return mapper.writeValueAsBytes(qualification); }
        catch (IOException invalid) { throw new IllegalArgumentException("PP2_QUALIFICATION_SERIALIZATION_FAILED", invalid); }
    }
    private boolean boundedBound(ObjectNode base, ObjectNode ledger, ObjectNode bounded) {
        if (!"1.2.0".equals(ledger.path("methodVersion").asText()) || !"UNKNOWN".equals(base.path("outcome").asText())
                || !Pp2OtherPhenotypeSource.QUALIFICATION_VERSION.equals(bounded.path("qualificationVersion").asText())
                || !bounded.path("targetAllele").equals(base.at("/binding/allele"))
                || !bounded.at("/materialIdentity/contextSha256").equals(base.at("/binding/contextSha256"))
                || !bounded.at("/materialIdentity/hcmPolicyBindingSha256").equals(base.at("/binding/hcmPolicyBindingSha256"))
                || !bounded.path("wholePublicationRead").isBoolean() || bounded.path("wholePublicationRead").asBoolean()
                || !bounded.path("scientificClassificationCreated").isBoolean() || bounded.path("scientificClassificationCreated").asBoolean()
                || !bounded.path("papers").isArray() || bounded.path("papers").isEmpty()
                || bounded.path("papers").size() != ledger.path("papers").size()) return false;
        for (int i = 0; i < bounded.path("papers").size(); i++) {
            var paper = bounded.path("papers").path(i); var raw = ledger.path("papers").path(i);
            for (String field : List.of("pmcid", "sourceRawSha256", "bundleSha256"))
                if (!paper.path(field).equals(raw.path(field))) return false;
        }
        return true;
    }
    private void projectQualification(ObjectNode base, ObjectNode ledger, ObjectNode bounded, ObjectNode review, ObjectNode summary) {
        var cards = summary.path("papers"); var papers = bounded.path("papers");
        summary.put("qualificationVersion", Pp2OtherPhenotypeSource.QUALIFICATION_VERSION)
                .put("qualificationScopeStatus", bounded.path("scopeStatus").asText())
                .put("unresolvedRecords", bounded.path("unresolvedRecords").asInt())
                .put("unresolvedUnits", bounded.path("unresolvedUnits").asInt())
                .put("unresolvedCitationCount", bounded.path("unresolvedCitationRefs").size())
                .put("declaredCandidateCount", bounded.path("declaredCandidateCount").asInt())
                .put("declaredKnownCitationCount", bounded.path("declaredKnownCitationCount").asInt());
        int assessed = 0;
        for (int i = 0; i < papers.size(); i++) {
            var paper = papers.path(i);
            boolean complete = "COMPLETE_DECLARED_SCOPE".equals(paper.path("scopeStatus").asText());
            if (complete) assessed++;
            if (!(cards.path(i) instanceof ObjectNode card)) continue;
            card.put("relationshipStatus", complete ? "COMPLETE_DECLARED_SCOPE" : "INCOMPLETE");
            var gaps = (com.fasterxml.jackson.databind.node.ArrayNode)card.path("gaps");
            if (complete && !gaps.isEmpty() && "PP2_OTHER_PHENOTYPE_RELATIONSHIP_NOT_ESTABLISHED".equals(gaps.path(gaps.size() - 1).path("reasonCode").asText()))
                gaps.remove(gaps.size() - 1);
            for (var gap : paper.path("gaps")) if (gaps.size() < 16) qualificationGap(gaps, gap, "COMPLETE_DECLARED_SOURCE_OBSERVATION_REVIEW");
            for (var record : card.path("relationshipRecords")) if (record instanceof ObjectNode row) {
                JsonNode found = mapper.missingNode(); int matches = 0;
                for (var observation : paper.path("observations")) for (var key : observation.path("recordKeys"))
                    if (key.path("workUnitSha256").isTextual() && key.path("workUnitSha256").equals(row.path("workUnitSha256"))
                            && key.path("recordOrdinal").equals(row.path("recordOrdinal"))) { found = observation; matches++; }
                if (matches != 1) continue;
                for (String field : List.of("targetMatch", "disposition", "clinicalDisposition"))
                    if (found.path(field).isTextual()) row.set(field, found.path(field).deepCopy());
                var recordGaps = row.putArray("qualificationGaps");
                for (var gap : found.path("gaps")) if (recordGaps.size() < 8) qualificationGap(recordGaps, gap, "SOURCE_OBSERVATION_QUALIFICATION");
            }
        }
        summary.put("assessedPapers", assessed);
        var summaryGaps = (com.fasterxml.jackson.databind.node.ArrayNode)summary.path("gaps");
        for (var gap : bounded.path("gaps")) if (summaryGaps.size() < 16) qualificationGap(summaryGaps, gap, "COMPLETE_BOUNDED_LITERATURE_REVIEW");
        if (!boundedScopeCompleted(base, ledger, bounded, assessed)) {
            if ("COMPLETE_DECLARED_SCOPE".equals(bounded.path("scopeStatus").asText())) {
                // The papers were reviewed even when another required source did not close.
                // Preserve the unresolved non-literature source rather than report the papers as unread.
                String incomplete = base.path("reasonCode").asText();
                review.put("reasonCode", incomplete); summary.put("reasonCode", incomplete);
                var remaining = review.putArray("pending");
                for (var item : base.path("pending"))
                    if (!"PP2_OTHER_PHENOTYPE_TARGET_PAPERS_NOT_REVIEWED".equals(item.path("reasonCode").asText())) remaining.add(item.deepCopy());
                if (remaining.isEmpty()) remaining.addObject().put("reasonCode", "PP2_OTHER_PHENOTYPE_DECLARED_SCOPE_INCOMPLETE");
            }
            return;
        }
        String reason = "PP2_NO_OTHER_PHENOTYPE_SUPPORT_IN_COMPLETED_DECLARED_SCOPE";
        String outcome = Pp2OtherPhenotypeSource.Outcome.NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE.name();
        review.put("outcome", outcome).put("reasonCode", reason); review.putArray("pending");
        for (var scope : review.path("requiredScope"))
            if (scope instanceof ObjectNode literature && "TARGET_LITERATURE_RELATIONSHIP_REVIEW".equals(literature.path("scope").asText()))
                literature.put("status", "COMPLETED_DECLARED_TARGET_SCOPE").put("assessedPapers", assessed)
                        .put("qualificationVersion", Pp2OtherPhenotypeSource.QUALIFICATION_VERSION)
                        .put("qualificationScopeStatus", "COMPLETE_DECLARED_SCOPE");
        summary.put("outcome", outcome).put("reasonCode", reason).put("scopeStatus", "COMPLETED_DECLARED_REVIEWED_SCOPE");
    }
    private static void qualificationGap(com.fasterxml.jackson.databind.node.ArrayNode gaps, JsonNode gap, String requirement) {
        String reason = gap.path("reasonCode").asText();
        if (reason.matches("[A-Z][A-Z0-9_]{1,150}") && newGapReason(gaps, gap)) gaps.addObject().put("requirement", requirement)
                .put("available", "SOURCE_MATERIAL_REVIEWED_WITH_LIMITATIONS").put("missing", reason).put("reasonCode", reason);
    }
    // The archive keeps each attempted work unit. Repeated causes must not crowd distinct gaps out of the card.
    private static boolean newGapReason(JsonNode gaps, JsonNode gap) {
        var reason = gap.path("reasonCode");
        if (!reason.isTextual() || reason.asText().isBlank()) return false;
        for (var row : gaps) if (reason.equals(row.path("reasonCode"))) return false;
        return true;
    }
    private boolean boundedScopeCompleted(ObjectNode base, ObjectNode ledger, ObjectNode bounded, int assessed) {
        if (!"COMPLETE_DECLARED_SCOPE".equals(bounded.path("scopeStatus").asText()) || assessed <= 0
                || assessed != bounded.path("papers").size() || !ledger.path("readingComplete").isBoolean() || !ledger.path("readingComplete").asBoolean()
                || !bounded.path("gaps").isArray() || !bounded.path("gaps").isEmpty()
                || bounded.path("unresolvedRecords").asInt(-1) != 0 || bounded.path("unresolvedUnits").asInt(-1) != 0
                || !bounded.path("unresolvedCitationRefs").isArray() || !bounded.path("unresolvedCitationRefs").isEmpty()
                || !base.path("conflicts").isArray() || !base.path("conflicts").isEmpty()
                || !base.path("pending").isArray() || base.path("pending").size() != 1
                || !"PP2_OTHER_PHENOTYPE_TARGET_PAPERS_NOT_REVIEWED".equals(base.path("pending").path(0).path("reasonCode").asText())) return false;
        for (var paper : bounded.path("papers")) {
            if (!paper.path("gaps").isArray() || !paper.path("gaps").isEmpty() || !paper.path("observations").isArray()
                    || paper.path("observations").isEmpty()) return false;
            for (var observation : paper.path("observations"))
                if (!List.of("EXACT_NON_TARGET", "CURRENT_TARGET_HCM_PROCESS").contains(observation.path("disposition").asText())
                        || !observation.path("gaps").isArray() || !observation.path("gaps").isEmpty()) return false;
        }
        int expert = 0, literature = 0;
        for (var scope : base.path("requiredScope")) {
            switch (scope.path("scope").asText()) {
                case "EXACT_TARGET_NATIVE_EXPERT_ASSERTIONS" -> {
                    if (!"COMPLETED_DECLARED_TARGET_SCOPE".equals(scope.path("status").asText())
                            || !"COMPLETE".equals(scope.at("/nativeSourceCoverage/status").asText())
                            || !scope.path("targetRecords").isIntegralNumber() || !scope.path("targetRecords").equals(scope.path("qualifiedRecords"))) return false;
                    expert++;
                }
                case "TARGET_LITERATURE_RELATIONSHIP_REVIEW" -> {
                    if (!scope.path("retrievalScopeComplete").asBoolean() || !scope.path("aliasIdentityVerified").asBoolean()
                            || !scope.path("candidatePapers").isIntegralNumber() || scope.path("candidatePapers").asInt(-1) < 0
                            || !scope.path("candidatePapers").equals(bounded.path("declaredCandidateCount"))
                            || !scope.path("knownCitationReferences").isArray() || scope.path("knownCitationReferences").size() != bounded.path("declaredKnownCitationCount").asInt(-1)
                            || (scope.path("candidatePapers").asInt() == 0 && scope.path("knownCitationReferences").isEmpty())) return false;
                    literature++;
                }
                default -> { return false; }
            }
        }
        return expert == 1 && literature == 1;
    }
    ObjectNode archive() {
        var result = mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-derived-archive-v1").put("methodVersion", VERSION)
                .put("sourceSnapshotSha256", sourceSnapshotSha256);
        result.set("methodSources", methodSources.deepCopy()); result.put("methodSourcesSha256", adapter.canonicalSha256(methodSources));
        result.set("originals", originals.deepCopy());
        var sorted = result.putArray("assessments"); var items = new java.util.ArrayList<JsonNode>(); assessments.forEach(items::add);
        items.sort(java.util.Comparator.comparing(row -> row.at("/binding/candidateId").asText() + "\u0000" + row.at("/binding/hypothesisId").asText()));
        items.forEach(sorted::add); result.put("archiveSha256", adapter.canonicalSha256(result)); return result;
    }
    @org.springframework.lang.Nullable private ObjectNode readableCitation(JsonNode quote) {
        String pin = quote.path("sourceSha256").asText();
        if (!originals.has(pin) || !quote.path("quote").isTextual() || quote.path("quote").asText().length() > 600
                || !quote.path("unitId").isTextual()) return null;
        var result = mapper.createObjectNode().put("sourceRawSha256", pin);
        for (String field : List.of("unitId", "quote", "start", "end")) if (quote.has(field)) result.set(field, quote.path(field).deepCopy());
        return result;
    }
    private void projectRelationships(ObjectNode card, JsonNode paper) {
        var records = card.putArray("relationshipRecords"); int recordCount = 0, factCount = 0, shownFacts = 0;
        for (var chunk : paper.path("relationshipChunks")) {
            if (!"pp2-relationship-material-v1".equals(chunk.path("originalExtractionProfile").asText())
                    || !"PP2".equals(chunk.path("originalExtractionCriterion").asText())
                    || !"pp2-relationship-material-v1".equals(chunk.path("questionVersion").asText())
                    || !"pp2-relationship-material-v1".equals(chunk.at("/review/questionVersion").asText())) {
                var issues = card.path("relationshipProjectionIssues").isArray() ? (com.fasterxml.jackson.databind.node.ArrayNode)card.path("relationshipProjectionIssues")
                        : card.putArray("relationshipProjectionIssues");
                issues.addObject().put("chunkIndex", chunk.path("chunkIndex").asInt()).put("reasonCode", "PP2_RELATIONSHIP_CHUNK_PROFILE_OR_PURPOSE_INVALID"); continue;
            }
            int ordinal = 0;
            for (var source : chunk.at("/review/records")) {
                ordinal++; recordCount++; ObjectNode record = null;
                if (records.size() < 8) {
                    record = records.addObject().put("chunkIndex", chunk.path("chunkIndex").asInt()).put("recordOrdinal", ordinal)
                            .put("materialKindProposal", source.path("materialKindProposal").asText()).put("targetMatch", "UNRESOLVED");
                    if (chunk.path("workUnitSha256").isTextual()) record.set("workUnitSha256", chunk.path("workUnitSha256").deepCopy());
                    var anchor = readableCitation(source.path("anchor")); if (anchor != null) record.set("anchor", anchor);
                    record.putArray("facts");
                }
                int itemFacts = 0, itemShown = 0;
                for (var field : source.path("facts")) {
                    String name = field.path("field").asText();
                    String group = switch (name) {
                        case "reportedClinicalDefinition", "reportedDiseaseCourse", "reportedClinicalAssessmentBasis" -> "DISEASE_CONTEXT";
                        case "reportedVariantDiseaseStatement" -> "AUTHOR_ASSERTION";
                        case "reportedRelationshipBasis" -> "REPORTED_BASIS";
                        case "reportedStudyDesign", "reportedObservationOrResult", "reportedComparatorOrControl" -> "STUDY_OBSERVATION";
                        case "reportedAlternativeExplanation", "reportedLimitationsOrContradiction", "reportedSupportingReference" -> "ALTERNATIVES_AND_REFERENCES";
                        case "reportedSubject" -> "SOURCE_SUBJECT"; default -> "";
                    };
                    var citation = readableCitation(field.path("citation")); if (group.isEmpty() || citation == null) continue;
                    factCount++; itemFacts++;
                    if (record == null || shownFacts >= 16) continue;
                    var fact = ((com.fasterxml.jackson.databind.node.ArrayNode)record.path("facts")).addObject().put("field", name)
                            .put("group", group).put("value", field.path("sourceValue").asText()); fact.set("citation", citation); itemShown++; shownFacts++;
                }
                if (record != null) record.put("factsInSource", itemFacts).put("displayedFacts", itemShown).put("factsTruncated", itemFacts > itemShown);
            }
        }
        card.put("relationshipRecordsInSource", recordCount).put("displayedRelationshipRecords", records.size()).put("relationshipRecordsTruncated", recordCount > records.size())
                .put("relationshipFactsInSource", factCount).put("displayedRelationshipFacts", shownFacts).put("relationshipFactsTruncated", factCount > shownFacts);
    }
    private static String streamedSha(Path path) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(path)) { byte[] buffer = new byte[8192]; int count;
                while ((count = stream.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count); }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private void rehash(ObjectNode ledger) { ledger.remove("ledgerSha256"); ledger.put("ledgerSha256", adapter.canonicalSha256(ledger)); }
    private com.fasterxml.jackson.databind.node.ArrayNode provenance(String kind) {
        var result = mapper.createArrayNode(); result.addObject().put("sourceKind", kind); return result;
    }
    private long total() { long n = 0; for (var value : originals) n += value.path("bytes").asLong(); return n; }
    private String retainInput(byte[] raw, JsonNode provenance) {
        if (!originals.has(CodingTranscriptMaterialSupply.sha(raw)) && total() + raw.length > MAX_TOTAL - 65536)
            throw new IllegalArgumentException("PP2_READING_ARCHIVE_TECHNICAL_LIMIT");
        return retain(raw, provenance);
    }
    private String retain(byte[] raw, JsonNode provenance) {
        String pin = CodingTranscriptMaterialSupply.sha(raw);
        if (originals.has(pin)) return pin;
        if (raw.length > MAX_FILE || total() + raw.length > MAX_TOTAL)
            throw new IllegalArgumentException("PP2_READING_ARCHIVE_TECHNICAL_LIMIT");
        originals.putObject(pin).put("rawSha256", pin).put("bytes", raw.length).put("base64", Base64.getEncoder().encodeToString(raw))
                .set("provenance", provenance.isArray() ? provenance.deepCopy() : mapper.createArrayNode()); return pin;
    }
    static ObjectNode emptyLedger(ObjectMapper mapper, String reason) {
        var value = mapper.createObjectNode().put("schemaVersion", "pp2-relation-reading-v1").put("methodVersion", "1.1.0")
                .put("status", "NOT_EXECUTED").put("readingComplete", false).put("relationQualification", "NOT_GRANTED")
                .put("scientificAdoption", false).put("businessPublishable", false).put("clinicalAdoption", "NONE");
        value.putObject("archiveBudget").put("maximumOriginalBytes", MAX_FILE).put("maximumTotalBytes", MAX_TOTAL);
        value.putArray("papers"); value.putObject("originals"); value.putObject("unretainedOriginals");
        value.putArray("pending").addObject().put("reasonCode", reason);
        value.put("ledgerSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value)); return value;
    }
    private static String safe(Exception error, String fallback) {
        String reason = error.getMessage(); return reason != null && reason.matches("[A-Z][A-Z0-9_]{1,120}") ? reason : fallback;
    }
}
