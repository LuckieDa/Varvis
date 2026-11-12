package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Base64;
import java.util.HashSet;

/** Saved-source integrity only. Reading an archive never restores a live review or adopts evidence. */
public final class Pp2OtherPhenotypeArchive {
    public static final String VERSION = "1.0.0";
    public static final String DERIVED_VERSION = "pp2-literature-assessment-v1";
    private static final String BOUNDED_QUALIFICATION = "pp2-bounded-literature-qualification-v1";
    private static final String NO_OTHER_SUPPORT = "NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE";
    private static final int MAX_FILE = 4 * 1024 * 1024;
    private static final long MAX_TOTAL = 32L * 1024 * 1024;

    private Pp2OtherPhenotypeArchive() { }

    public static ObjectNode create(ObjectMapper mapper, Pp2OtherPhenotypeSource.Observed observed, ObjectNode batch) {
        var hashes = new RuntimeEvidenceV2Adapter(mapper);
        var archive = mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-evidence-archive-v1")
                .put("methodVersion", VERSION).put("executionSourceSha256", batch.path("sourceSnapshotSha256").asText());
        archive.set("sourceObservationIdentity", observed.identity());
        archive.set("sourceArchive", observed.privateArchive());
        var judgments = archive.putArray("judgments"); int index = 0;
        for (var result : batch.at("/modules/pp2/results")) {
            if (result.has("otherPhenotypeReview")) {
                var judgment = judgments.addObject().put("modulePointer", "/modules/pp2/results/" + index)
                        .put("moduleSha256", hashes.canonicalSha256(result));
                judgment.set("review", result.path("otherPhenotypeReview").deepCopy());
            }
            index++;
        }
        archive.put("archiveSha256", hashes.canonicalSha256(archive));
        return archive;
    }

    /** No source files, processes, providers, classifiers or opaque scientific handles are used here. */
    public static ObjectNode verifyAndExport(ObjectMapper mapper, JsonNode saved, JsonNode batch) {
        var hashes = new RuntimeEvidenceV2Adapter(mapper);
        require(saved instanceof ObjectNode && "pp2-other-phenotype-evidence-archive-v1".equals(saved.path("schemaVersion").asText())
                && VERSION.equals(saved.path("methodVersion").asText()), "REQUIRED");
        var archive = (ObjectNode) saved;
        checkHash(hashes, archive, "archiveSha256", "HASH_INVALID");
        require(archive.path("archiveSha256").equals(batch.path("pp2OtherPhenotypeArchiveSha256")), "REFERENCE_INVALID");
        boolean beforeExecution = "NOT_EXECUTED".equals(batch.path("executionMode").asText())
                && archive.path("judgments").isArray() && archive.path("judgments").isEmpty();
        require(archive.path("executionSourceSha256").equals(batch.path("sourceSnapshotSha256"))
                && (hash(archive.path("executionSourceSha256")) || beforeExecution
                    && "UNAVAILABLE".equals(archive.path("executionSourceSha256").asText())), "EXECUTION_SOURCE_INVALID");
        var identity = archive.path("sourceObservationIdentity");
        require(identity.isObject() && identity.equals(batch.path("pp2OtherPhenotypeSourceIdentity")), "OBSERVATION_IDENTITY_INVALID");
        var sources = archive.path("sourceArchive");
        require(sources instanceof ObjectNode && "pp2-other-phenotype-private-archive-v1".equals(sources.path("schemaVersion").asText()),
                "SOURCE_REQUIRED");
        checkHash(hashes, (ObjectNode) sources, "archiveSha256", "SOURCE_HASH_INVALID");
        var observation = sources.path("observation");
        require(observation instanceof ObjectNode
                && "pp2-other-phenotype-source-observation-v1".equals(observation.path("schemaVersion").asText()), "OBSERVATION_REQUIRED");
        checkHash(hashes, (ObjectNode) observation, "observationSha256", "OBSERVATION_HASH_INVALID");
        require(observation.path("observationSha256").equals(identity.path("observationSha256"))
                && observation.path("methodSourcesSha256").equals(identity.path("methodSourcesSha256"))
                && observation.path("readerStatus").equals(identity.path("readerStatus"))
                && observation.at("/sourceCapability/methodVersion").equals(identity.path("methodVersion")), "OBSERVATION_BINDING_INVALID");
        require(sources.path("methodSources").isObject() && sources.path("readerFacts").isObject()
                && hashes.canonicalSha256(sources.path("methodSources")).equals(observation.path("methodSourcesSha256").asText())
                && hashes.canonicalSha256(sources.path("readerFacts")).equals(observation.path("readerFactsSha256").asText()),
                "REPLAY_FACTS_INVALID");
        require(sources.at("/readerFacts/status").equals(observation.path("readerStatus")), "READER_STATUS_INVALID");
        if ("READ".equals(observation.path("readerStatus").asText())) require(hash(observation.path("readerInputSha256"))
                && observation.path("readerInputSha256").equals(sources.at("/readerFacts/inputRawSha256")), "READER_INPUT_INVALID");
        var originals = sources.path("originals"); require(originals.isObject(), "ORIGINAL_INDEX_INVALID");
        long total = 0;
        for (var field : originals.properties()) {
            var raw = field.getValue(); var encoded = raw.path("base64");
            require(field.getKey().matches("[a-f0-9]{64}") && field.getKey().equals(raw.path("rawSha256").asText())
                    && encoded.isTextual() && encoded.asText().length() <= ((MAX_FILE + 2) / 3) * 4
                    && raw.path("bytes").isIntegralNumber() && raw.path("bytes").canConvertToInt(), "ORIGINAL_METADATA_INVALID");
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(encoded.asText()); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("PP2_PHENOTYPE_ARCHIVE_ORIGINAL_ENCODING_INVALID"); }
            total += bytes.length;
            require(bytes.length <= MAX_FILE && total <= MAX_TOTAL && bytes.length == raw.path("bytes").asInt()
                    && field.getKey().equals(CodingTranscriptMaterialSupply.sha(bytes)), "ORIGINAL_BYTES_INVALID");
        }
        require(observation.path("readDispositions").isArray(), "READ_DISPOSITIONS_REQUIRED");
        var readIds = new HashSet<String>();
        for (var read : observation.path("readDispositions")) {
            require(!read.path("readId").asText().isBlank()
                    && java.util.Set.of("READ", "UNAVAILABLE").contains(read.path("status").asText()), "READ_ID_INVALID");
            // Preserve malformed/duplicate source attempts as recorded failures.
            // This archive verifies saved bytes, and grants no source qualification.
            readIds.add(read.path("readId").asText());
            if (read.has("archivedRawSha256")) require(hash(read.path("archivedRawSha256"))
                    && read.path("archivedRawSha256").equals(read.path("observedRawSha256"))
                    && originals.has(read.path("archivedRawSha256").asText()), "ARCHIVED_ORIGINAL_MISSING");
            // Earlier observations predate archivedRawSha256. A pin mismatch occurs
            // after successful archival, unlike a technical byte-budget failure.
            if (!read.has("archivedRawSha256") && "PP2_SOURCE_RAW_PIN_MISMATCH".equals(read.path("reasonCode").asText()))
                require(hash(read.path("observedRawSha256")) && originals.has(read.path("observedRawSha256").asText()),
                        "ARCHIVED_ORIGINAL_MISSING");
            if ("READ".equals(read.path("status").asText())) require(hash(read.path("expectedRawSha256"))
                    && read.path("expectedRawSha256").equals(read.path("observedRawSha256"))
                    && originals.has(read.path("observedRawSha256").asText()), "READ_ORIGINAL_MISSING");
        }
        int count = 0;
        for (var result : batch.at("/modules/pp2/results")) if (result.has("otherPhenotypeReview")) count++;
        require(archive.path("judgments").isArray() && archive.path("judgments").size() == count, "JUDGMENT_COUNT_INVALID");
        var pointers = new HashSet<String>();
        for (var judgment : archive.path("judgments")) {
            String pointer = judgment.path("modulePointer").asText();
            require(pointer.matches("/modules/pp2/results/(0|[1-9][0-9]{0,5})") && pointers.add(pointer), "JUDGMENT_POINTER_INVALID");
            var result = batch.at(pointer); var review = result.path("otherPhenotypeReview");
            require(result.isObject() && review.isObject() && judgment.path("review").equals(review)
                    && hashes.canonicalSha256(result).equals(judgment.path("moduleSha256").asText()), "JUDGMENT_BINDING_INVALID");
            require("pp2-other-phenotype-review-v1".equals(review.path("schemaVersion").asText())
                    && review.at("/binding/sourceSnapshotSha256").equals(identity.path("observationSha256"))
                    && review.at("/binding/candidateId").equals(result.path("candidateId")), "JUDGMENT_SOURCE_INVALID");
            if (review.path("binding").has("hypothesisId")) require(review.at("/binding/hypothesisId").equals(result.path("hypothesisId")),
                    "JUDGMENT_BACKGROUND_INVALID");
            require(result.path("hcmPolicyBinding").isObject()
                    && hashes.canonicalSha256(result.path("hcmPolicyBinding")).equals(review.at("/binding/hcmPolicyBindingSha256").asText())
                    && hash(review.at("/binding/contextSha256"))
                    && review.at("/binding/contextSha256").equals(result.at("/hcmPolicyBinding/preparedContextSha256")), "JUDGMENT_CONTEXT_INVALID");
            var projection = result.path("sourceProjection");
            require(projection instanceof ObjectNode
                    && "selected-legacy-context-projection-v1".equals(projection.path("schemaVersion").asText())
                    && projection.path("candidateId").equals(result.path("candidateId"))
                    && projection.path("hypothesisId").equals(result.path("hypothesisId"))
                    && projection.path("evaluationContext").isObject(), "JUDGMENT_PROJECTION_INVALID");
            checkHash(hashes, (ObjectNode) projection, "bindingSha256", "JUDGMENT_PROJECTION_HASH_INVALID");
            var prepared = projection.path("evaluationContext");
            var execution = result.at("/transcriptPreparation/executionContext");
            if (execution.isMissingNode()) execution = prepared;
            require(execution.isObject() && hashes.canonicalSha256(prepared).equals(review.at("/binding/contextSha256").asText())
                    && hashes.canonicalSha256(execution).equals(result.at("/hcmPolicyBinding/executionContextSha256").asText())
                    && prepared.at("/variant/evidenceQueryAllele").isObject()
                    && prepared.at("/variant/evidenceQueryAllele").equals(review.at("/binding/allele")), "JUDGMENT_SELECTED_CONTEXT_INVALID");
            require(review.path("sourceReadDispositionIds").isArray(), "JUDGMENT_READS_INVALID");
            for (var id : review.path("sourceReadDispositionIds")) require(id.isTextual() && readIds.contains(id.asText()), "JUDGMENT_READ_MISSING");
        }
        return archive.deepCopy();
    }

    /** Integrity of a separately saved after-claim ledger. The caller supplies the already
     * verified base archive. These JSON objects never recreate an Assessed or Review handle. */
    public static ObjectNode verifyDerivedAndExport(ObjectMapper mapper, JsonNode saved, JsonNode batch,
            JsonNode verifiedBaseArchive) {
        var hashes = new RuntimeEvidenceV2Adapter(mapper);
        require(saved instanceof ObjectNode && "pp2-other-phenotype-derived-archive-v1".equals(saved.path("schemaVersion").asText())
                && DERIVED_VERSION.equals(saved.path("methodVersion").asText()), "DERIVED_REQUIRED");
        var archive = (ObjectNode) saved;
        checkHash(hashes, archive, "archiveSha256", "DERIVED_HASH_INVALID");
        require(archive.path("archiveSha256").equals(batch.path("pp2OtherPhenotypeDerivedArchiveSha256")), "DERIVED_REFERENCE_INVALID");
        var observation = batch.path("pp2OtherPhenotypeSourceIdentity");
        require(verifiedBaseArchive.path("sourceObservationIdentity").equals(observation)
                && hash(archive.path("sourceSnapshotSha256"))
                && archive.path("sourceSnapshotSha256").equals(observation.path("observationSha256")), "DERIVED_OBSERVATION_INVALID");
        var methods = archive.path("methodSources");
        var sourceCapability = verifiedBaseArchive.at("/sourceArchive/observation/sourceCapability");
        require(methods.isObject() && hash(archive.path("methodSourcesSha256"))
                && hashes.canonicalSha256(methods).equals(archive.path("methodSourcesSha256").asText()), "DERIVED_METHODS_INVALID");
        require(methods.equals(verifiedBaseArchive.at("/sourceArchive/methodSources"))
                && archive.path("methodSourcesSha256").equals(verifiedBaseArchive.at("/sourceArchive/observation/methodSourcesSha256")),
                "DERIVED_METHOD_BINDING_INVALID");
        for (var field : methods.properties()) {
            if ("derivedReaderImplementation".equals(field.getKey())) {
                require(field.getValue().isObject() && !field.getValue().isEmpty(), "DERIVED_READER_METHODS_REQUIRED");
                for (var implementation : field.getValue().properties()) require(
                        implementation.getKey().matches("[A-Za-z][A-Za-z0-9_]*\\.(?:py|json)") && hash(implementation.getValue()),
                        "DERIVED_READER_METHOD_PIN_INVALID");
            } else require(!field.getKey().isBlank() && (hash(field.getValue()) || "workflowVersion".equals(field.getKey())
                    && "pp2-other-phenotype-workflow-v2".equals(field.getValue().asText())), "DERIVED_METHOD_PIN_INVALID");
        }
        var originals = archive.path("originals");
        require(originals.isObject(), "DERIVED_ORIGINALS_REQUIRED");
        long total = 0;
        for (var entry : originals.properties()) {
            byte[] raw = derivedOriginal(originals, entry.getKey());
            total += raw.length;
            require(total <= MAX_TOTAL, "DERIVED_TOTAL_BYTES_INVALID");
        }
        var assessments = archive.path("assessments");
        require(assessments.isArray() && assessments.size() <= 2_000, "DERIVED_ASSESSMENTS_REQUIRED");
        var bySha = new java.util.HashMap<String, JsonNode>();
        for (var assessment : assessments) {
            require(assessment instanceof ObjectNode, "DERIVED_ASSESSMENT_INVALID");
            checkHash(hashes, (ObjectNode) assessment, "assessmentSha256", "DERIVED_ASSESSMENT_HASH_INVALID");
            String key = assessment.path("assessmentSha256").asText();
            require(bySha.putIfAbsent(key, assessment) == null, "DERIVED_ASSESSMENT_DUPLICATE");
            var binding = assessment.path("binding");
            require(binding.isObject() && binding.size() == 6 && !binding.path("candidateId").asText().isBlank()
                    && !binding.path("hypothesisId").asText().isBlank() && hash(binding.path("contextSha256"))
                    && hash(binding.path("hcmPolicyBindingSha256"))
                    && binding.path("sourceSnapshotSha256").equals(archive.path("sourceSnapshotSha256"))
                    && binding.path("allele").isObject(), "DERIVED_ASSESSMENT_BINDING_INVALID");
            var reading = derivedJson(mapper, originals, assessment.path("readingLedgerRawSha256").asText());
            require("pp2-relation-reading-v1".equals(reading.path("schemaVersion").asText())
                    && java.util.Set.of("1.1.0", "1.2.0").contains(reading.path("methodVersion").asText())
                    && reading.path("scientificAdoption").isBoolean() && !reading.path("scientificAdoption").asBoolean()
                    && "NOT_GRANTED".equals(reading.path("relationQualification").asText()), "DERIVED_READING_INVALID");
            if ("1.2.0".equals(reading.path("methodVersion").asText())) {
                var questionMethod = methods.at("/derivedReaderImplementation/pp2_relationship_material.py");
                require(hash(questionMethod) && questionMethod.equals(reading.at("/implementation/pp2_relationship_material.py")),
                        "DERIVED_READING_METHOD_MISMATCH");
            }
            checkHash(hashes, reading, "ledgerSha256", "DERIVED_READING_HASH_INVALID");
            require(reading.path("originals").isObject(), "DERIVED_READING_ORIGINALS_REQUIRED");
            for (var original : reading.path("originals").properties()) require(original.getKey().equals(original.getValue().path("rawSha256").asText())
                    && originals.has(original.getKey()) && original.getValue().path("bytes").equals(originals.path(original.getKey()).path("bytes")),
                    "DERIVED_READING_ORIGINAL_MISSING");
            var qualification = derivedJson(mapper, originals, assessment.path("qualificationLedgerRawSha256").asText());
            require("pp2-other-phenotype-literature-qualification-v1".equals(qualification.path("schemaVersion").asText())
                    && DERIVED_VERSION.equals(qualification.path("methodVersion").asText())
                    && binding.equals(qualification.path("binding")) && assessment.path("outcome").equals(qualification.path("outcome"))
                    && assessment.path("reasonCode").equals(qualification.path("reasonCode"))
                    && qualification.path("publicSummary").isObject(), "DERIVED_QUALIFICATION_INVALID");
            verifyBoundedReading(qualification, reading, assessment, archive, sourceCapability);
        }
        var used = new HashSet<String>();
        for (var result : batch.at("/modules/pp2/results")) {
            var review = result.path("otherPhenotypeReview");
            var reference = review.at("/binding/derivedAssessmentSha256");
            if (reference.isMissingNode()) {
                require(!review.has("literatureAssessment") && !(review.isObject() && derivedProtocol(verifiedBaseArchive)),
                        "DERIVED_REVIEW_REFERENCE_MISSING");
                continue;
            }
            require(hash(reference) && bySha.containsKey(reference.asText()) && used.add(reference.asText()), "DERIVED_REVIEW_ASSESSMENT_MISSING");
            var assessment = java.util.Objects.requireNonNull(bySha.get(reference.asText()));
            var binding = (ObjectNode) review.path("binding").deepCopy(); binding.remove("derivedAssessmentSha256");
            require(binding.equals(assessment.path("binding")) && binding.path("candidateId").equals(result.path("candidateId"))
                    && binding.path("hypothesisId").equals(result.path("hypothesisId"))
                    && review.path("outcome").equals(assessment.path("outcome"))
                    && review.path("reasonCode").equals(assessment.path("reasonCode")), "DERIVED_REVIEW_BINDING_INVALID");
            var summary = review.path("literatureAssessment");
            require(summary instanceof ObjectNode && DERIVED_VERSION.equals(summary.path("methodVersion").asText())
                    && summary.path("assessmentSha256").equals(reference)
                    && summary.path("readingLedgerRawSha256").equals(assessment.path("readingLedgerRawSha256"))
                    && summary.path("qualificationLedgerRawSha256").equals(assessment.path("qualificationLedgerRawSha256")), "DERIVED_SUMMARY_REFERENCE_INVALID");
            var core = ((ObjectNode) summary).deepCopy();
            core.remove(java.util.List.of("assessmentSha256", "readingLedgerRawSha256", "qualificationLedgerRawSha256"));
            var qualification = derivedJson(mapper, originals, assessment.path("qualificationLedgerRawSha256").asText());
            require(core.equals(qualification.path("publicSummary")), "DERIVED_SUMMARY_INVALID");
            var bounded = qualification.path("boundedLiteratureReview");
            if (bounded.isObject()) {
                var material = bounded.path("materialIdentity");
                require(material.path("nativeModelSha256").equals(result.at("/hcmPolicyBinding/executionModelSha256"))
                        && material.path("alleleAssociationSha256").equals(result.at("/hcmPolicyBinding/alleleAssociationSha256")),
                        "DERIVED_BOUNDED_MATERIAL_INVALID");
            }
            if (NO_OTHER_SUPPORT.equals(review.path("outcome").asText())) verifyNonzeroConclusionScope(review, bounded);
        }
        require(used.size() == bySha.size(), "DERIVED_UNLINKED_ASSESSMENT");
        return archive.deepCopy();
    }

    private static void verifyNonzeroConclusionScope(JsonNode review, JsonNode bounded) {
        int literature = 0, nativeExpert = 0;
        for (var scope : review.path("requiredScope")) {
            if ("TARGET_LITERATURE_RELATIONSHIP_REVIEW".equals(scope.path("scope").asText())
                    && (bounded.isObject() || scope.path("candidatePapers").asInt() > 0 || !scope.path("knownCitationReferences").isEmpty())) {
                literature++;
                var known = scope.path("knownCitationReferences"); var candidates = scope.path("candidatePapers");
                require(bounded.isObject() && "COMPLETE_DECLARED_SCOPE".equals(bounded.path("scopeStatus").asText())
                        && "COMPLETED_DECLARED_TARGET_SCOPE".equals(scope.path("status").asText())
                        && nonnegativeCount(candidates) && known.isArray() && (candidates.intValue() > 0 || !known.isEmpty())
                        && candidates.equals(bounded.path("declaredCandidateCount"))
                        && integerEquals(bounded.path("declaredKnownCitationCount"), known.size())
                        && integerEquals(scope.path("assessedPapers"), bounded.path("papers").size())
                        && scope.path("qualificationVersion").equals(bounded.path("qualificationVersion"))
                        && scope.path("qualificationScopeStatus").equals(bounded.path("scopeStatus"))
                        && scope.path("retrievalScopeComplete").isBoolean() && scope.path("retrievalScopeComplete").asBoolean()
                        && scope.path("aliasIdentityVerified").isBoolean() && scope.path("aliasIdentityVerified").asBoolean(),
                        "DERIVED_BOUNDED_SCOPE_INVALID");
            }
            if ("EXACT_TARGET_NATIVE_EXPERT_ASSERTIONS".equals(scope.path("scope").asText())) {
                nativeExpert++;
                if (bounded.isObject()) require("COMPLETED_DECLARED_TARGET_SCOPE".equals(scope.path("status").asText())
                        && "COMPLETE".equals(scope.at("/nativeSourceCoverage/status").asText())
                        && integerEquals(scope.at("/nativeSourceCoverage/pendingRows"), 0)
                        && nonnegativeCount(scope.path("targetRecords")) && scope.path("targetRecords").equals(scope.path("qualifiedRecords")),
                        "DERIVED_BOUNDED_SCOPE_INVALID");
            }
        }
        if (bounded.isObject()) require(literature == 1 && nativeExpert == 1 && review.path("requiredScope").size() == 2,
                "DERIVED_BOUNDED_SCOPE_INVALID");
    }

    // Validate the saved owner's bindings and scope. History never reruns the qualifier or restores its authority.
    private static void verifyBoundedReading(JsonNode qualification, JsonNode reading, JsonNode assessment, JsonNode archive, JsonNode capability) {
        var bounded = qualification.path("boundedLiteratureReview"); var summary = qualification.path("publicSummary");
        boolean nonzeroConclusion = NO_OTHER_SUPPORT.equals(assessment.path("outcome").asText())
                && (!reading.path("papers").isEmpty() || summary.path("papersInScope").asInt() > 0);
        if (bounded.isMissingNode()) {
            require(!summary.has("qualificationVersion") && !nonzeroConclusion, "DERIVED_BOUNDED_REQUIRED");
            return; // A capability does not say this run had material or executed the qualifier.
        }
        require(bounded.isObject() && "pp2-bounded-literature-review-v1".equals(bounded.path("schemaVersion").asText())
                && BOUNDED_QUALIFICATION.equals(bounded.path("qualificationVersion").asText())
                && bounded.path("qualificationVersion").equals(capability.path("qualificationVersion"))
                && bounded.path("qualificationVersion").equals(summary.path("qualificationVersion"))
                && hash(archive.at("/methodSources/scripts~1ps1_pm5~1pp2_bounded_literature_qualification.py"))
                && "1.2.0".equals(reading.path("methodVersion").asText()), "DERIVED_BOUNDED_METHOD_INVALID");
        var binding = assessment.path("binding"); var material = bounded.path("materialIdentity");
        require(binding.equals(bounded.path("binding")) && binding.path("allele").equals(bounded.path("targetAllele"))
                && assessment.path("readingLedgerRawSha256").equals(bounded.path("readingLedgerRawSha256"))
                && archive.path("methodSourcesSha256").equals(bounded.path("methodSourcesSha256"))
                && material.isObject() && hash(material.path("nativeModelSha256")) && hash(material.path("alleleAssociationSha256"))
                && binding.path("contextSha256").equals(material.path("contextSha256"))
                && binding.path("hcmPolicyBindingSha256").equals(material.path("hcmPolicyBindingSha256")),
                "DERIVED_BOUNDED_BINDING_INVALID");
        require("DECLARED_AVAILABLE_MAIN_AND_PARSED_ATTACHMENT_TEXT_UNITS_ONLY".equals(bounded.path("scopeMeaning").asText())
                && bounded.path("scopeMeaning").equals(reading.path("readingScopeMeaning"))
                && bounded.path("wholePublicationRead").isBoolean() && !bounded.path("wholePublicationRead").asBoolean()
                && bounded.path("positiveOtherPhenotypeGranted").isBoolean() && !bounded.path("positiveOtherPhenotypeGranted").asBoolean()
                && bounded.path("scientificClassificationCreated").isBoolean() && !bounded.path("scientificClassificationCreated").asBoolean(),
                "DERIVED_BOUNDED_SCOPE_INVALID");
        var papers = bounded.path("papers"); var actualPapers = reading.path("papers");
        require(papers.isArray() && actualPapers.isArray() && !papers.isEmpty() && papers.size() == actualPapers.size(),
                "DERIVED_BOUNDED_SCOPE_INVALID");
        require(bounded.path("scopeStatus").equals(summary.path("qualificationScopeStatus"))
                && integerEquals(summary.path("papersInScope"), papers.size())
                && nonnegativeCount(bounded.path("declaredCandidateCount"))
                && bounded.path("declaredCandidateCount").equals(summary.path("declaredCandidateCount"))
                && nonnegativeCount(bounded.path("declaredKnownCitationCount"))
                && bounded.path("declaredKnownCitationCount").equals(summary.path("declaredKnownCitationCount"))
                && nonnegativeCount(bounded.path("unresolvedRecords")) && bounded.path("unresolvedRecords").equals(summary.path("unresolvedRecords"))
                && nonnegativeCount(bounded.path("unresolvedUnits")) && bounded.path("unresolvedUnits").equals(summary.path("unresolvedUnits"))
                && bounded.path("unresolvedCitationRefs").isArray()
                && integerEquals(summary.path("unresolvedCitationCount"), bounded.path("unresolvedCitationRefs").size()),
                "DERIVED_BOUNDED_SCOPE_INVALID");
        boolean complete = "COMPLETE_DECLARED_SCOPE".equals(bounded.path("scopeStatus").asText());
        if (!complete) {
            require("INCOMPLETE".equals(bounded.path("scopeStatus").asText()) && !nonzeroConclusion, "DERIVED_BOUNDED_SCOPE_INVALID");
            return;
        }
        require(reading.path("readingComplete").isBoolean() && reading.path("readingComplete").asBoolean()
                && emptyArray(reading.path("pending")) && emptyArray(bounded.path("gaps"))
                && emptyArray(bounded.path("unresolvedCitationRefs")) && integerEquals(bounded.path("unresolvedRecords"), 0)
                && integerEquals(bounded.path("unresolvedUnits"), 0) && integerEquals(summary.path("assessedPapers"), papers.size()),
                "DERIVED_BOUNDED_SCOPE_INVALID");
        for (int i = 0; i < papers.size(); i++) {
            var paper = papers.path(i); var actual = actualPapers.path(i);
            require("COMPLETE_DECLARED_SCOPE".equals(paper.path("scopeStatus").asText()) && integerEquals(paper.path("unresolvedRecords"), 0)
                    && emptyArray(paper.path("unresolvedUnitIds")) && emptyArray(paper.path("gaps"))
                    && paper.path("pmcid").asText().matches("PMC[1-9][0-9]*") && paper.path("pmcid").equals(actual.path("pmcid"))
                    && hash(paper.path("sourceRawSha256")) && paper.path("sourceRawSha256").equals(actual.path("sourceRawSha256"))
                    && hash(paper.path("bundleSha256")) && paper.path("bundleSha256").equals(actual.path("bundleSha256"))
                    && reading.path("originals").has(paper.path("sourceRawSha256").asText())
                    && reading.path("originals").has(paper.path("bundleSha256").asText())
                    && "RAW_QUESTION_SCOPE_READ".equals(actual.at("/questionCoverage/status").asText())
                    && paper.path("observations").isArray() && !paper.path("observations").isEmpty(), "DERIVED_BOUNDED_SCOPE_INVALID");
            for (var observation : paper.path("observations")) {
                String disposition = observation.path("disposition").asText();
                require(emptyArray(observation.path("gaps")) && ("CURRENT_TARGET_HCM_PROCESS".equals(disposition)
                        && "EXACT".equals(observation.path("targetMatch").asText()) || "EXACT_NON_TARGET".equals(disposition)
                        && "NON_TARGET".equals(observation.path("targetMatch").asText())), "DERIVED_BOUNDED_SCOPE_INVALID");
            }
        }
    }
    private static boolean emptyArray(JsonNode node) { return node.isArray() && node.isEmpty(); }
    private static boolean nonnegativeCount(JsonNode node) { return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() >= 0; }
    private static boolean integerEquals(JsonNode node, int value) {
        return node.isIntegralNumber() && node.canConvertToInt() && node.intValue() == value;
    }

    /** Detect a missing extension from the immutable saved protocol, including when
     * both the public reference and the private archive have disappeared. */
    public static boolean derivedEvidenceRequired(JsonNode batch, JsonNode verifiedBaseArchive) {
        if (batch.has("pp2OtherPhenotypeDerivedArchive") || batch.has("pp2OtherPhenotypeDerivedArchiveSha256")) return true;
        for (var result : batch.at("/modules/pp2/results")) {
            var review = result.path("otherPhenotypeReview");
            if (review.isObject() && (derivedProtocol(verifiedBaseArchive) || review.has("literatureAssessment")
                    || review.path("binding").has("derivedAssessmentSha256"))) return true;
        }
        return false;
    }
    private static boolean derivedProtocol(JsonNode archive) {
        return DERIVED_VERSION.equals(archive.at("/sourceArchive/observation/sourceCapability/derivedAssessment/methodVersion").asText());
    }

    private static byte[] derivedOriginal(JsonNode originals, String key) {
        var original = originals.path(key); var encoded = original.path("base64");
        require(key.matches("[a-f0-9]{64}") && key.equals(original.path("rawSha256").asText())
                && encoded.isTextual() && encoded.asText().length() <= ((MAX_FILE + 2) / 3) * 4
                && original.path("bytes").isIntegralNumber() && original.path("bytes").canConvertToInt(), "DERIVED_ORIGINAL_METADATA_INVALID");
        byte[] raw;
        try { raw = Base64.getDecoder().decode(encoded.asText()); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("PP2_PHENOTYPE_ARCHIVE_DERIVED_ORIGINAL_ENCODING_INVALID"); }
        require(raw.length <= MAX_FILE && raw.length == original.path("bytes").asInt()
                && key.equals(CodingTranscriptMaterialSupply.sha(raw)), "DERIVED_ORIGINAL_BYTES_INVALID");
        return raw;
    }

    private static ObjectNode derivedJson(ObjectMapper mapper, JsonNode originals, String key) {
        byte[] raw = derivedOriginal(originals, key);
        try {
            var parsed = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
            if (!(parsed instanceof ObjectNode object)) throw new IllegalArgumentException("PP2_PHENOTYPE_ARCHIVE_DERIVED_LEDGER_OBJECT_REQUIRED");
            return object;
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("PP2_PHENOTYPE_ARCHIVE_DERIVED_LEDGER_JSON_INVALID");
        }
    }

    private static boolean hash(JsonNode node) { return node.isTextual() && node.asText().matches("[a-f0-9]{64}"); }
    private static void checkHash(RuntimeEvidenceV2Adapter hashes, ObjectNode object, String field, String reason) {
        var body = object.deepCopy(); body.remove(field);
        require(hash(object.path(field)) && hashes.canonicalSha256(body).equals(object.path(field).asText()), reason);
    }
    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException("PP2_PHENOTYPE_ARCHIVE_" + reason);
    }
}
