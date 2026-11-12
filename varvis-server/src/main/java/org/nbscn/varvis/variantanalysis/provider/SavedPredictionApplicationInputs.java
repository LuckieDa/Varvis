package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.NormalizationAlleleMapping;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.engine.EngineExecutionResult;

/**
 * Unwired internal memory-only supply seam. Pins come from the server-owned supply,
 * NOT from an HTTP request. Hash consistency is not scientific approval or a signature.
 * Package placement permits reuse of existing canonical projections without new public API.
 */
final class SavedPredictionApplicationInputs {
    static final String FLAT_PUBLICATION = "flat-publication-v1";
    static final String NATIVE_PUBLICATION = "native-grch38-execution-source-v1";
    enum SourceKind { SAVED_STANDARD_CALIBRATION, REVIEWED_PREDICTION_CLAUSE_RESULT_V1 }
    private SavedPredictionApplicationInputs() { }

    static final class Artifact {
        private final byte[] bytes;
        private final JsonNode tree;
        Artifact(byte[] bytes, JsonNode tree) { this.bytes = bytes.clone(); this.tree = tree.deepCopy(); }
    }

    /** Separate, fixed internal supply anchors. No production supplier is registered in this batch. */
    static final class Supply {
        private final Map<String, String> bytePins;
        private final String settingsPin;
        private final String supplyId;
        Supply(String supplyId, Map<String, String> bytePins, String settingsPin) {
            this.supplyId = supplyId;
            this.bytePins = Map.copyOf(bytePins);
            this.settingsPin = settingsPin;
        }
    }

    static final class Input {
        private final ObjectNode settings;
        private final Map<String, JsonNode> documents;
        private final ObjectNode identity;
        private final String issue;
        private final SourceKind sourceKind;
        private Input(ObjectNode settings, Map<String, JsonNode> documents, ObjectNode identity, String issue) {
            this(settings, documents, identity, issue, SourceKind.SAVED_STANDARD_CALIBRATION);
        }
        private Input(ObjectNode settings, Map<String, JsonNode> documents, ObjectNode identity, String issue, SourceKind kind) {
            this.sourceKind = kind;
            this.settings = settings.deepCopy();
            this.documents = new HashMap<>();
            documents.forEach((key, value) -> this.documents.put(key, value.deepCopy()));
            this.identity = identity.deepCopy(); this.issue = issue;
        }
        ObjectNode settings() { return settings.deepCopy(); }
        JsonNode document(String name) { return documents.getOrDefault(name, com.fasterxml.jackson.databind.node.MissingNode.getInstance()).deepCopy(); }
        ObjectNode identity() { return identity.deepCopy(); }
        String issue() { return issue; }
        SourceKind sourceKind() { return sourceKind; }
    }

    /** Ordinary saved business input, without a fabricated calibration parent or operation. */
    static Input verifyReviewedClause(ObjectMapper mapper, Map<String, Artifact> supplied, ObjectNode settings, Supply roots) {
        var kind = SourceKind.REVIEWED_PREDICTION_CLAUSE_RESULT_V1;
        var hashes = new RuntimeEvidenceV2Adapter(mapper);
        var docs = new HashMap<String, JsonNode>();
        ObjectNode identity = mapper.createObjectNode().put("sourceKind", kind.name()).put("supplyId", roots.supplyId);
        identity.set("sourceByteSha256", mapper.valueToTree(roots.bytePins));
        try {
            require(settings != null, "SETTINGS_OBJECT_MISSING");
            if (settings == null) throw new IllegalArgumentException("SETTINGS_OBJECT_MISSING");
            ObjectNode semantic = semanticSettings(settings);
            String pin = hashes.canonicalSha256(semantic);
            identity.put("settingsSha256", pin);
            require(!roots.supplyId.isBlank() && roots.settingsPin.equals(pin), "INTERNAL_SUPPLY_SETTINGS_MISMATCH");
            require(supplied.keySet().equals(Set.of("run", "request", "private", "publication"))
                    && supplied.keySet().equals(roots.bytePins.keySet()), "SOURCE_SET_MISMATCH");
            for (var entry : supplied.entrySet()) {
                Artifact artifact = entry.getValue();
                require(artifact != null, "SOURCE_MISSING");
                if (artifact == null) throw new IllegalArgumentException("SOURCE_MISSING");
                require(PredictionCalibration.sha256(artifact.bytes).equals(roots.bytePins.get(entry.getKey())), "SOURCE_BYTES_DIGEST_MISMATCH");
                JsonNode parsed = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(artifact.bytes);
                require(parsed != null && parsed.isObject() && parsed.equals(artifact.tree), "SOURCE_TREE_BYTES_MISMATCH");
                if (parsed == null) throw new IllegalArgumentException("SOURCE_TREE_BYTES_MISMATCH");
                docs.put(entry.getKey(), parsed);
            }
            JsonNode run = docs.get("run"), request = docs.get("request"), privateData = docs.get("private"), publication = docs.get("publication");
            if (run == null || request == null || privateData == null || publication == null) throw new IllegalArgumentException("SOURCE_CONTAINER_MISSING");
            require(nonblank(run, "analysisId", "runId", "requestSha256", "executionSourceSha256")
                    && run.path("analysisId").equals(semantic.path("analysisId")) && run.path("runId").equals(semantic.path("runId")), "RUN_IDENTITY_MISMATCH");
            identity.put("sourceRunId", run.path("runId").asText());
            for (JsonNode envelope : List.of(run, request, request.path("declaration"), privateData, privateData.path("declaration"))) {
                require(envelope.isObject(), "ORDINARY_DECLARATION_REQUIRED");
                for (String field : List.of("operation", "sourceRunId", "parent", "parentRunId", "rawAncestorRunId", "retryOf", "association", "calibration"))
                    require(!envelope.hasNonNull(field), "ORDINARY_SOURCE_INHERITANCE_OR_OPERATION_UNSUPPORTED");
            }
            require(Set.of("COMPLETED", "COMPLETED_WITH_BLOCKERS").contains(run.path("status").asText())
                    && run.path("dataClass").equals(semantic.at("/task/dataClass"))
                    && run.path("dataClass").equals(request.at("/declaration/dataClass")), "SOURCE_COMPLETION_OR_PURPOSE_MISMATCH");
            require(nonblank(run.path("executionProfile"), "profileId", "profileVersion")
                    && run.path("enabledCriteria").isArray() && !run.path("enabledCriteria").isEmpty()
                    && run.path("executionProfile").equals(request.path("executionProfile"))
                    && run.path("enabledCriteria").equals(request.at("/executionProfile/enabledCriteria"))
                    && request.path("declaration").equals(privateData.path("declaration")), "REQUEST_PRIVATE_PROFILE_MISMATCH");
            require(hashes.canonicalSha256(request).equals(run.path("requestSha256").asText()), "REQUEST_DIGEST_MISMATCH");
            require(run.path("privateArtifactSha256").asText().equals(roots.bytePins.get("private")), "PRIVATE_DIGEST_MISMATCH");
            require(NATIVE_PUBLICATION.equals(semantic.path("publicationAdapterVersion").asText())
                    && "acmg-execution-source-v1".equals(publication.path("version").asText())
                    && !publication.has("executionSourceSha256") && "GRCh38".equals(publication.path("inputAssembly").asText())
                    && "GRCh38".equals(publication.path("analysisAssembly").asText())
                    && publication.path("conversionApplied").isBoolean() && !publication.path("conversionApplied").booleanValue(), "PUBLICATION_NATIVE_ASSEMBLY_UNSUPPORTED");
            require(publication.path("analysisId").equals(run.path("analysisId"))
                    && run.path("executionSourceSha256").asText().equals(roots.bytePins.get("publication")), "PUBLICATION_SOURCE_MISMATCH");
            identity.put("publicationAdapterVersion", NATIVE_PUBLICATION);
            JsonNode candidates = semantic.path("candidates"), ids = run.path("candidateIds"), rows = run.path("candidates"), published = publication.path("candidates");
            require(candidates.isArray() && !candidates.isEmpty() && ids.isArray() && rows.isArray() && published.isArray()
                    && candidates.size() == ids.size() && ids.size() == rows.size() && published.size() >= ids.size(), "CANDIDATE_ORDER_MISMATCH");
            require(run.path("selectedCount").isIntegralNumber() && run.path("selectedCount").intValue() == ids.size()
                    && semantic.at("/task/maxCandidates").isIntegralNumber() && candidates.size() <= semantic.at("/task/maxCandidates").intValue()
                    && semantic.at("/task/maxHypothesesPerCandidate").isIntegralNumber() && semantic.at("/task/maxHypothesesPerCandidate").intValue() > 0
                    && semantic.at("/task/computeBudget").isIntegralNumber() && semantic.at("/task/computeBudget").intValue() == 0
                    && nonblank(semantic.path("task"), "id", "version", "purpose", "dataClass"), "TASK_SCOPE_INVALID");
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < ids.size(); i++) {
                JsonNode candidate = candidates.get(i); String id = ids.get(i).asText();
                require(ids.get(i).isTextual() && !id.isBlank() && seen.add(id) && candidate.isObject()
                        && id.equals(candidate.path("candidateId").asText()) && rows.get(i).isObject()
                        && id.equals(rows.get(i).path("candidateId").asText()) && id.equals(published.get(i).path("candidateId").asText())
                        && !rows.get(i).has("rank"), "CANDIDATE_ORDER_MISMATCH");
                require(!candidate.hasNonNull("evidence") || candidate.path("evidence").isObject() && candidate.path("evidence").isEmpty(), "CLAUSE_LEGACY_EVIDENCE_BINDING_UNSUPPORTED");
                if (candidate.has("rank")) require(candidate.path("rank").isIntegralNumber() && candidate.path("rank").intValue() == i + 1, "CANDIDATE_ORDER_MISMATCH");
                ((ObjectNode) candidate).put("rank", i + 1); // Detached view only; pin above covers supplied settings.
                require(candidate.path("hypotheses").isArray() && candidate.path("hypotheses").size() <= semantic.at("/task/maxHypothesesPerCandidate").intValue(), "HYPOTHESES_LIST_MISSING");
                Set<String> hypotheses = new HashSet<>();
                for (JsonNode hypothesis : candidate.path("hypotheses")) require(hypothesis.isObject() && nonblank(hypothesis, "hypothesisId")
                        && hypotheses.add(hypothesis.path("hypothesisId").asText()), "HYPOTHESIS_ID_INVALID_OR_DUPLICATE");
            }
            require(run.path("bundles").isArray(), "CLAUSE_BUSINESS_BUNDLES_MISSING");
            return new Input(semantic, docs, identity, "", kind);
        } catch (java.io.IOException invalid) {
            return new Input(mapper.createObjectNode(), Map.of(), identity, "SOURCE_JSON_INVALID", kind);
        } catch (RuntimeException invalid) {
            return new Input(mapper.createObjectNode(), Map.of(), identity, safeClauseIssue(invalid), kind);
        }
    }

    static String safeClauseIssue(RuntimeException invalid) {
        String message = invalid.getMessage();
        return invalid instanceof IllegalArgumentException && message != null && message.matches("[A-Z][A-Z0-9_]{1,120}")
                ? message : "CLAUSE_SOURCE_STRUCTURE_INVALID";
    }

    static Input verify(ObjectMapper mapper, Map<String, Artifact> supplied, ObjectNode settings, Supply roots) {
        var hashes = new RuntimeEvidenceV2Adapter(mapper);
        var docs = new HashMap<String, JsonNode>();
        var identity = mapper.createObjectNode().put("supplyId", roots.supplyId);
        var pins = identity.putObject("sourceByteSha256");
        roots.bytePins.forEach(pins::put);
        try {
            require(settings != null, "SETTINGS_OBJECT_MISSING");
            if (settings == null) throw new IllegalArgumentException("SETTINGS_OBJECT_MISSING");
            ObjectNode semanticSettings = semanticSettings(settings);
            String settingsSha;
            try { settingsSha = hashes.canonicalSha256(semanticSettings); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("SETTINGS_CANONICAL_FORMAT_INVALID", invalid); }
            identity.put("settingsSha256", settingsSha);
            require(!roots.supplyId.isBlank() && roots.settingsPin.equals(hashes.canonicalSha256(semanticSettings)),
                    "INTERNAL_SUPPLY_SETTINGS_MISMATCH");
            require(supplied.keySet().equals(roots.bytePins.keySet()), "SOURCE_SET_MISMATCH");
            for (String name : supplied.keySet().stream().sorted().toList()) {
                Artifact artifact = supplied.get(name);
                if (artifact == null) throw new IllegalArgumentException("SOURCE_MISSING");
                require(PredictionCalibration.sha256(artifact.bytes).equals(roots.bytePins.get(name)), "SOURCE_BYTES_DIGEST_MISMATCH");
                JsonNode parsed = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(artifact.bytes);
                require(parsed != null && parsed.equals(artifact.tree), "SOURCE_TREE_BYTES_MISMATCH");
                if (parsed == null) throw new IllegalArgumentException("SOURCE_TREE_BYTES_MISMATCH");
                docs.put(name, parsed);
            }
            for (String name : List.of("run", "request", "private", "parent", "publication"))
                require(docs.containsKey(name) && docs.get(name).isObject(), "SOURCE_REQUIRED_OBJECT_" + name.toUpperCase(java.util.Locale.ROOT));
            JsonNode run = docs.get("run"), request = docs.get("request"), privateData = docs.get("private"), parent = docs.get("parent");
            if (run == null || request == null || privateData == null || parent == null)
                throw new IllegalArgumentException("SOURCE_CONTAINER_MISSING");
            require(nonblank(run, "analysisId", "runId", "requestSha256", "executionSourceSha256")
                    && run.path("analysisId").equals(settings.path("analysisId"))
                    && run.path("runId").equals(settings.path("runId")), "RUN_IDENTITY_MISMATCH");
            require(run.path("analysisId").equals(parent.path("analysisId"))
                    && run.path("sourceRunId").equals(parent.path("runId"))
                    && !run.path("runId").equals(parent.path("runId"))
                    && run.path("executionSourceSha256").equals(parent.path("executionSourceSha256")), "PARENT_SOURCE_MISMATCH");
            for (JsonNode source : List.of(run, parent)) require(Set.of("COMPLETED", "COMPLETED_WITH_BLOCKERS").contains(source.path("status").asText())
                    && source.path("dataClass").equals(settings.at("/task/dataClass")), "SOURCE_COMPLETION_OR_PURPOSE_MISMATCH");
            require(nonblank(run.path("executionProfile"), "profileId", "profileVersion")
                    && run.path("enabledCriteria").isArray() && !run.path("enabledCriteria").isEmpty()
                    && run.path("executionProfile").equals(request.path("executionProfile"))
                    && run.path("enabledCriteria").equals(request.at("/executionProfile/enabledCriteria"))
                    && request.path("declaration").equals(privateData.path("declaration")), "REQUEST_PRIVATE_PROFILE_MISMATCH");
            String sourcePointer = settings.path("requestSourcePointer").asText();
            require(Set.of("/declaration/calibration", "/declaration/association").contains(sourcePointer), "REQUEST_SOURCE_FORMAT_UNSUPPORTED");
            // Association declarations identify raw observations/annotation selections, not calibrated results.
            // This adapter currently supports only direct saved-calibration result declarations.
            require("/declaration/calibration".equals(sourcePointer), "ASSOCIATION_REQUEST_SOURCE_UNSUPPORTED");
            JsonNode requestSource = request.at(sourcePointer);
            require(requestSource.isObject() && "CALIBRATE_SAVED_OBSERVATIONS".equals(requestSource.path("operation").asText()),
                    "CALIBRATION_REQUEST_OPERATION_INVALID");
            require(run.path("sourceRunId").equals(requestSource.path("sourceRunId"))
                    && nonblank(parent, "requestSha256") && parent.path("requestSha256").equals(requestSource.path("sourceRequestSha256"))
                    && run.path("executionSourceSha256").equals(requestSource.path("executionSourceSha256")), "REQUEST_PARENT_REFERENCE_MISMATCH");
            require(hashes.canonicalSha256(request).equals(run.path("requestSha256").asText()), "REQUEST_DIGEST_MISMATCH");
            require(run.path("privateArtifactSha256").asText().equals(roots.bytePins.get("private")), "PRIVATE_DIGEST_MISMATCH");
            JsonNode publication = docs.get("publication");
            if (publication == null) throw new IllegalArgumentException("PUBLICATION_MISSING");
            String adapter = settings.path("publicationAdapterVersion").asText();
            require(Set.of(FLAT_PUBLICATION, NATIVE_PUBLICATION).contains(adapter), "PUBLICATION_ADAPTER_UNSUPPORTED");
            require(publication.path("analysisId").equals(run.path("analysisId")), "PUBLICATION_SOURCE_MISMATCH");
            if (NATIVE_PUBLICATION.equals(adapter)) {
                require("acmg-execution-source-v1".equals(publication.path("version").asText())
                        && !publication.has("executionSourceSha256"), "PUBLICATION_FORMAT_UNSUPPORTED");
                require("GRCh38".equals(publication.path("inputAssembly").asText())
                        && "GRCh38".equals(publication.path("analysisAssembly").asText())
                        && publication.path("conversionApplied").isBoolean() && !publication.path("conversionApplied").booleanValue(),
                        "PUBLICATION_NATIVE_ASSEMBLY_UNSUPPORTED");
                require(run.path("executionSourceSha256").asText().equals(roots.bytePins.get("publication")), "PUBLICATION_SOURCE_MISMATCH");
            } else {
                require(!publication.has("version"), "PUBLICATION_FORMAT_UNSUPPORTED");
                require(publication.path("executionSourceSha256").equals(run.path("executionSourceSha256")), "PUBLICATION_SOURCE_MISMATCH");
            }
            identity.put("publicationAdapterVersion", adapter);
            JsonNode candidates = settings.path("candidates"), ids = run.path("candidateIds");
            for (String role : List.of("run", "parent", "publication")) {
                JsonNode list = docs.get(role).path("candidates");
                require(list.isArray(), "SOURCE_CANDIDATES_ARRAY_REQUIRED_" + role.toUpperCase(java.util.Locale.ROOT));
                for (JsonNode element : list) require(element.isObject() && nonblank(element, "candidateId"),
                        "SOURCE_CANDIDATE_OBJECT_INVALID_" + role.toUpperCase(java.util.Locale.ROOT));
            }
            require(candidates.isArray() && !candidates.isEmpty() && ids.isArray()
                    && candidates.size() == ids.size() && run.path("candidates").size() == ids.size()
                    && run.path("selectedCount").asInt(-1) == ids.size(), "CANDIDATE_ORDER_MISMATCH");
            int max = settings.at("/task/maxCandidates").asInt(0), hypotheses = settings.at("/task/maxHypothesesPerCandidate").asInt(0);
            require(nonblank(settings.path("task"), "id", "version", "purpose", "dataClass")
                    && max > 0 && hypotheses > 0 && settings.at("/task/computeBudget").isIntegralNumber()
                    && settings.at("/task/computeBudget").asInt(-1) == 0, "TASK_SCOPE_INVALID");
            require(candidates.size() <= max, "TASK_CANDIDATE_LIMIT_EXCEEDED");
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < candidates.size(); i++) {
                JsonNode candidate = candidates.get(i);
                require(candidate.isObject(), "SETTINGS_CANDIDATE_OBJECT_INVALID");
                String id = candidate.path("candidateId").asText();
                require(!id.isBlank() && seen.add(id) && id.equals(ids.get(i).asText())
                        && id.equals(run.path("candidates").get(i).path("candidateId").asText())
                        && candidate.path("rank").isIntegralNumber() && candidate.path("rank").canConvertToInt()
                        && candidate.path("rank").intValue() == i + 1
                        && run.path("candidates").get(i).path("rank").isIntegralNumber()
                        && run.path("candidates").get(i).path("rank").canConvertToInt()
                        && run.path("candidates").get(i).path("rank").intValue() == i + 1, "CANDIDATE_ORDER_MISMATCH");
                require(candidate.path("hypotheses").isArray(), "HYPOTHESES_LIST_MISSING");
                require(candidate.path("hypotheses").size() <= hypotheses, "TASK_HYPOTHESIS_LIMIT_EXCEEDED");
                Set<String> hypothesisIds = new HashSet<>();
                for (JsonNode h : candidate.path("hypotheses"))
                    require(nonblank(h, "hypothesisId") && hypothesisIds.add(h.path("hypothesisId").asText()), "HYPOTHESIS_ID_INVALID_OR_DUPLICATE");
            }
            require(nonblank(requestSource, "adoptionSha256", "calibrationCanonicalSha256"), "CALIBRATION_REQUEST_ADOPTION_REFERENCE_MISSING");
            validateDeclarationSet(requestSource, candidates);
            return new Input(semanticSettings, docs, identity, "");
        } catch (IllegalArgumentException invalid) {
            return new Input(mapper.createObjectNode(), Map.of(), identity, invalid.getMessage());
        } catch (java.io.IOException invalid) {
            return new Input(mapper.createObjectNode(), Map.of(), identity, "SOURCE_JSON_INVALID");
        }
    }

    /** Only declared wrapper read times are excluded; evidence validity and source bytes remain bound. */
    static ObjectNode semanticSettings(ObjectNode settings) {
        ObjectNode semantic = settings.deepCopy(); semantic.remove("readAt");
        if (semantic.path("effectSnapshots").isObject()) for (JsonNode snapshot : semantic.path("effectSnapshots"))
            if (snapshot instanceof ObjectNode object) object.remove("readAt");
        return semantic;
    }

    /** Detached interpretation only. The stored document and its byte identity are never rewritten. */
    static ObjectNode publicationCandidate(Input input, String candidateId) {
        JsonNode publication = input.document("publication");
        JsonNode row = unique(publication.path("candidates"), "candidateId", candidateId);
        String adapter = input.settings().path("publicationAdapterVersion").asText();
        ObjectNode view;
        int index = -1;
        for (int i = 0; i < publication.path("candidates").size(); i++)
            if (candidateId.equals(publication.path("candidates").get(i).path("candidateId").asText())) index = i;
        if (FLAT_PUBLICATION.equals(adapter)) {
            require(!row.has("published") && !row.has("engineCandidate") && !row.has("matchedRecord"), "PUBLICATION_FORMAT_UNSUPPORTED");
            view = ((ObjectNode) row).deepCopy();
        } else {
            require(NATIVE_PUBLICATION.equals(adapter) && !row.has("queryAllele") && !row.has("annotations") && !row.has("rank"),
                    "PUBLICATION_FORMAT_UNSUPPORTED");
            require(!row.has("sourceBlocker"), "PUBLICATION_SOURCE_BLOCKED");
            JsonNode published = row.path("published"), engine = row.path("engineCandidate");
            require(published.isObject() && engine.isObject()
                    && candidateId.equals(published.path("candidateId").asText())
                    && candidateId.equals(published.at("/candidateIdentity/candidateId").asText())
                    && candidateId.equals(engine.path("candidateId").asText()), "PUBLICATION_CANDIDATE_IDENTITY_MISMATCH");
            JsonNode key = published.at("/observation/key");
            require(key.isObject() && nonblank(key, "chromosome", "reference", "alternate")
                    && "GRCh38".equals(key.path("genomeAssembly").asText()), "PUBLICATION_QUERY_ALLELE_INVALID");
            var normalized = new NormalizedVariantKey("GRCh38", key.path("chromosome").asText(),
                    positiveInt(key.path("position")), key.path("reference").asText(), key.path("alternate").asText());
            JsonNode record = row.path("matchedRecord");
            require(record.isObject() && nonblank(record, "contig", "reference"), "PUBLICATION_MATCHED_RECORD_MISSING");
            int position = positiveInt(record.path("position")), ordinal = positiveInt(record.path("recordOrdinal")),
                    altIndex = positiveInt(record.path("selectedAltIndex"));
            require(record.path("alternates").isArray() && altIndex <= record.path("alternates").size(), "PUBLICATION_ALT_INDEX_INVALID");
            var alts = new java.util.ArrayList<String>();
            for (JsonNode alt : record.path("alternates")) {
                require(alt.isTextual() && !alt.asText().isBlank() && !alt.asText().contains(",") && !alt.asText().contains("|"),
                        "PUBLICATION_ALTERNATES_INVALID"); alts.add(alt.asText());
            }
            for (JsonNode call : List.of(engine.path("analysisVcfCall"), published.path("analysisVcfCall"))) {
                require("analysis-vcf-call-v1".equals(call.path("schemaVersion").asText())
                        && "MATCHED".equals(call.path("matchStatus").asText()) && "GRCh38".equals(call.path("assembly").asText())
                        && "EXOMISER_ANALYSIS_VCF".equals(call.path("artifactRole").asText())
                        && "ANALYSIS_VCF_EXACT_ALLELE".equals(call.path("provenance").asText())
                        && positiveInt(call.path("matchedRecordOrdinal")) == ordinal
                        && positiveInt(call.path("targetAlleleIndex")) == altIndex, "PUBLICATION_VCF_CALL_MISMATCH");
            }
            var matched = new EngineExecutionResult.MatchedRecord(record.path("contig").asText(), position,
                    record.path("reference").asText(), alts, altIndex, ordinal);
            require(NormalizationAlleleMapping.IDENTITY.equals(NormalizationAlleleMapping.from(matched.mappingToken(), normalized).getStatus()),
                    "PUBLICATION_INPUT_ALLELE_IDENTITY_UNSUPPORTED");
            view = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("candidateId", candidateId);
            view.set("rank", unique(input.sourceKind == SourceKind.REVIEWED_PREDICTION_CLAUSE_RESULT_V1
                    ? input.settings().path("candidates") : input.document("run").path("candidates"), "candidateId", candidateId).path("rank").deepCopy());
            view.set("queryAllele", key.deepCopy());
            view.set("annotations", published.path("transcriptAnnotations").deepCopy());
            if (published.has("hgncId")) view.set("hgncId", published.path("hgncId").deepCopy());
            view.putObject("inputAllele").put("assembly", "GRCh38").put("contig", record.path("contig").asText())
                    .put("position", position).put("reference", record.path("reference").asText()).put("alternate", alts.get(altIndex - 1));
        }
        require(view.path("annotations").isArray(), "PUBLICATION_ANNOTATIONS_ARRAY_REQUIRED");
        for (JsonNode annotation : view.path("annotations")) require(annotation.isObject(), "PUBLICATION_ANNOTATION_OBJECT_REQUIRED");
        view.putObject("sourceProjection").put("adapterVersion", adapter)
                .put("publicationByteSha256", input.identity().at("/sourceByteSha256/publication").asText())
                .put("originalPointer", "/candidates/" + index + (NATIVE_PUBLICATION.equals(adapter) ? "/published" : ""))
                .put("rankSource", "SAVED_RUN_CANDIDATE_ORDER");
        return view;
    }

    private static int positiveInt(JsonNode value) {
        require(value.isIntegralNumber() && value.canConvertToInt() && value.intValue() > 0, "PUBLICATION_POSITIVE_INTEGER_REQUIRED");
        return value.intValue();
    }

    /** A detached, strict native projection for the context bridge; no recovery, VCF or REF access. */
    record ContextSource(org.nbscn.varvis.variantanalysis.domain.AnalysisContext analysis,
                         org.nbscn.varvis.variantanalysis.domain.VariantInterpretation candidate,
                         ObjectNode selection, String mappingToken, int selectedAltIndex, ObjectNode view) { }

    static ContextSource contextSource(ObjectMapper mapper, Input input, JsonNode candidateSettings) {
        require(NATIVE_PUBLICATION.equals(input.settings().path("publicationAdapterVersion").asText()),
                "CONTEXT_PUBLICATION_FORMAT_UNSUPPORTED");
        JsonNode publication = input.document("publication");
        require(nonblank(publication, "analysisId", "inputAssembly", "analysisMode", "sampleId")
                && input.settings().path("analysisId").equals(publication.path("analysisId")), "CONTEXT_ANALYSIS_SOURCE_INVALID");
        org.nbscn.varvis.variantanalysis.domain.AnalysisContext analysis;
        try {
            analysis = new org.nbscn.varvis.variantanalysis.domain.AnalysisContext(publication.path("analysisId").textValue(),
                    org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.valueOf(publication.path("inputAssembly").textValue()),
                    org.nbscn.varvis.variantanalysis.domain.AnalysisMode.valueOf(publication.path("analysisMode").textValue()));
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("CONTEXT_ANALYSIS_SOURCE_INVALID", invalid); }
        String id = candidateSettings.path("candidateId").asText();
        ObjectNode view = publicationCandidate(input, id);
        JsonNode row = unique(publication.path("candidates"), "candidateId", id), raw = row.path("published");
        require(nonblank(raw, "candidateId") && nonblank(raw.path("candidateIdentity"), "candidateId")
                && raw.path("candidateId").equals(candidateSettings.path("candidateId")), "CONTEXT_TYPED_CANDIDATE_CONFLICT");
        JsonNode features = raw.path("exomiserCandidateFeatures");
        require(nonblank(features, "geneSymbol", "entrezGeneId"), "CONTEXT_CANDIDATE_GENE_FIELDS_MISSING");
        require(features.path("geneSymbol").equals(raw.at("/candidateIdentity/geneSymbol")), "CONTEXT_CANDIDATE_GENE_CONFLICT");
        require(nonblank(raw.path("observation"), "sampleId")
                && raw.at("/observation/sampleId").equals(publication.path("sampleId")), "CONTEXT_SAMPLE_IDENTITY_CONFLICT");
        ObjectNode selected = input.sourceKind == SourceKind.REVIEWED_PREDICTION_CLAUSE_RESULT_V1
                ? PredictionClauseSourceView.selectedAnnotation(mapper, input, candidateSettings) : null;
        var hashes = new RuntimeEvidenceV2Adapter(mapper);
        for (JsonNode evidence : candidateSettings.path("evidence")) {
            JsonNode prediction = prediction(input, candidateSettings, evidence);
            JsonNode annotation = view.at(evidence.path("annotationPointer").asText());
            require(hashes.canonicalSha256(annotation).equals(prediction.at("/selection/annotationSourceSha256").asText()),
                    "CONTEXT_SELECTED_ANNOTATION_DIGEST_MISMATCH");
            ObjectNode next = mapper.createObjectNode().put("versionedTranscriptId", versionedAnnotationIdentity(annotation))
                    .put("annotationSourceSha256", hashes.canonicalSha256(annotation));
            require(nonblank(annotation, "source", "version", "status"), "CONTEXT_ANNOTATION_SOURCE_MISSING");
            for (String field : List.of("source", "version", "status")) next.set(field, annotation.path(field));
            require(next.path("versionedTranscriptId").equals(prediction.at("/selection/versionedTranscriptId")),
                    "CONTEXT_SELECTED_TRANSCRIPT_CONFLICT");
            require(selected == null || selected.equals(next), "CONTEXT_MULTIPLE_SELECTIONS_UNSUPPORTED");
            selected = next;
        }
        if (selected == null) throw new IllegalArgumentException("CONTEXT_SAVED_SELECTION_MISSING");
        ObjectNode detached = ((ObjectNode) raw).deepCopy();
        for (JsonNode annotation : detached.path("transcriptAnnotations")) {
            // Only the detached typed representation receives the already checked aliases.
            ObjectNode node = (ObjectNode) annotation;
            for (var alias : Map.of("hgvsc", "hgvsCoding", "hgvsp", "hgvsProtein").entrySet()) {
                if (!node.has(alias.getKey())) continue;
                require(!node.has(alias.getValue()) || node.path(alias.getKey()).equals(node.path(alias.getValue())),
                        "ANNOTATION_HGVS_ALIAS_CONFLICT");
                node.set(alias.getValue(), node.path(alias.getKey())); node.remove(alias.getKey());
            }
        }
        try {
            ObjectMapper local = mapper.copy();
            local.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
            local.setConfig(local.getDeserializationConfig().without(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS));
            var typed = local.readerFor(org.nbscn.varvis.variantanalysis.domain.VariantInterpretation.class)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .<org.nbscn.varvis.variantanalysis.domain.VariantInterpretation>readValue(detached);
            require(typed != null && id.equals(typed.getCandidateId()), "CONTEXT_TYPED_CANDIDATE_CONFLICT");
            if (typed == null || typed.getObservation() == null || typed.getAnalysisVcfCall() == null
                    || typed.getExomiserCandidateFeatures() == null) throw new IllegalArgumentException("CONTEXT_TYPED_SOURCE_MISSING");
            require(raw.at("/observation/key").equals(mapper.valueToTree(typed.getObservation().getKey()))
                    && raw.at("/observation/sampleId").textValue().equals(typed.getObservation().getSampleId()),
                    "CONTEXT_TYPED_OBSERVATION_CONFLICT");
            require(raw.path("analysisVcfCall").equals(mapper.valueToTree(typed.getAnalysisVcfCall())),
                    "CONTEXT_TYPED_VCF_CALL_CONFLICT");
            require(features.path("geneSymbol").textValue().equals(typed.getExomiserCandidateFeatures().getGeneSymbol())
                    && features.path("entrezGeneId").textValue().equals(typed.getExomiserCandidateFeatures().getEntrezGeneId()),
                    "CONTEXT_TYPED_GENE_CONFLICT");
            for (int i = 0; i < raw.path("transcriptAnnotations").size(); i++) {
                JsonNode annotation = raw.path("transcriptAnnotations").get(i);
                if (!selected.path("annotationSourceSha256").asText().equals(hashes.canonicalSha256(annotation))) continue;
                TranscriptConsequence tx = typed.getTranscriptAnnotations().get(i);
                String full = versionedAnnotationIdentity(annotation);
                require(full.equals(tx.getVersionedTranscriptId()) && tx.getTranscriptVersion() != null
                        && full.endsWith("." + tx.getTranscriptVersion()) && annotation.path("source").asText().equals(tx.getSource())
                        && annotation.path("version").asText().equals(tx.getVersion()) && annotation.path("status").asText().equals(tx.getStatus()),
                        "CONTEXT_TYPED_ANNOTATION_CONFLICT");
            }
            JsonNode record = row.path("matchedRecord");
            var alts = new java.util.ArrayList<String>(); record.path("alternates").forEach(alt -> alts.add(alt.textValue()));
            require(alts.stream().distinct().count() == alts.size(), "CONTEXT_MATCHED_ALTERNATES_AMBIGUOUS");
            String token = new EngineExecutionResult.MatchedRecord(record.path("contig").textValue(), positiveInt(record.path("position")),
                    record.path("reference").textValue(), alts, positiveInt(record.path("selectedAltIndex")), positiveInt(record.path("recordOrdinal"))).mappingToken();
            return new ContextSource(analysis, typed, selected, token, positiveInt(record.path("selectedAltIndex")), view);
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("CONTEXT_TYPED_SOURCE_INVALID", invalid); }
    }

    /** Resolve only agreeing original identifiers; aliases and the complete annotation stay untouched. */
    static String versionedAnnotationIdentity(JsonNode raw) {
        require(raw.isObject(), "ANNOTATION_OBJECT_REQUIRED");
        ObjectNode typed = ((ObjectNode) raw).deepCopy();
        for (var alias : Map.of("hgvsc", "hgvsCoding", "hgvsp", "hgvsProtein").entrySet()) {
            if (typed.has(alias.getKey())) {
                require(!typed.has(alias.getValue()) || typed.path(alias.getKey()).equals(typed.path(alias.getValue())), "ANNOTATION_HGVS_ALIAS_CONFLICT");
                typed.set(alias.getValue(), typed.path(alias.getKey())); typed.remove(alias.getKey());
            }
        }
        String coding = annotationText(typed, "hgvsCoding"), protein = annotationText(typed, "hgvsProtein");
        var identifiers = new java.util.ArrayList<String>();
        for (String field : List.of("transcriptId", "transcriptAccession")) {
            String value = annotationText(typed, field); if (value != null) identifiers.add(value);
        }
        if (coding != null) {
            require(coding.indexOf(':') > 0, "ANNOTATION_HGVSC_IDENTITY_INVALID");
            identifiers.add(coding.substring(0, coding.indexOf(':')));
        }
        require(!identifiers.isEmpty(), "ANNOTATION_TRANSCRIPT_IDENTITY_MISSING");
        String base = null, versioned = null;
        for (String id : identifiers) {
            require(id.matches("[A-Za-z][A-Za-z0-9_]*(\\.[0-9]+)?"), "ANNOTATION_TRANSCRIPT_IDENTITY_INVALID");
            int dot = id.indexOf('.'); String currentBase = dot < 0 ? id : id.substring(0, dot);
            require(base == null || base.equals(currentBase), "ANNOTATION_TRANSCRIPT_BASE_CONFLICT"); base = currentBase;
            if (dot >= 0) {
                require(versioned == null || versioned.equals(id), "ANNOTATION_TRANSCRIPT_VERSION_CONFLICT"); versioned = id;
            }
        }
        require(versioned != null, "ANNOTATION_TRANSCRIPT_VERSION_MISSING");
        // A minimal typed projection supports historic full transcriptId without inventing source fields.
        String accession = annotationText(typed, "transcriptAccession");
        String transcriptId = annotationText(typed, "transcriptId");
        if (transcriptId != null && transcriptId.contains(".")) accession = transcriptId;
        if (accession == null) accession = transcriptId;
        String resolved = new TranscriptConsequence(null, null, accession, coding, protein, null).getVersionedTranscriptId();
        require(resolved != null && resolved.equals(versioned), "ANNOTATION_TRANSCRIPT_VERSION_MISSING");
        if (resolved == null) throw new IllegalArgumentException("ANNOTATION_TRANSCRIPT_VERSION_MISSING");
        return resolved;
    }

    private static String annotationText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        require(value.isTextual() && !value.asText().isBlank(), "ANNOTATION_IDENTITY_FIELD_INVALID"); return value.asText();
    }

    /** Per-candidate verification; a corrupt item cannot replace or erase another item. */
    static String candidateIssue(ObjectMapper mapper, Input input, JsonNode candidate) {
        if (input.sourceKind == SourceKind.REVIEWED_PREDICTION_CLAUSE_RESULT_V1)
            return PredictionClauseSourceView.candidateIssue(mapper, input, candidate);
        var hashes = new RuntimeEvidenceV2Adapter(mapper);
        try {
            String id = candidate.path("candidateId").asText();
            JsonNode run = input.document("run"), parent = input.document("parent");
            JsonNode item = unique(run.path("candidates"), "candidateId", id);
            JsonNode prior = unique(parent.path("candidates"), "candidateId", id);
            JsonNode published = publicationCandidate(input, id);
            require(published.path("rank").equals(candidate.path("rank")), "PUBLICATION_RANK_MISMATCH");
            JsonNode references = input.document("request").at("/declaration/calibration/results");
            JsonNode evidence = candidate.path("evidence");
            require(evidence.isObject() && !evidence.isEmpty(), "SAVED_EVIDENCE_REFERENCE_MISSING");
            Set<String> usedReferences = new HashSet<>();
            for (JsonNode binding : evidence) {
                require(binding.isObject(), "EVIDENCE_BINDING_OBJECT_INVALID");
                String pointer = binding.path("predictionPointer").asText();
                require(pointer.startsWith("/attempts/") && pointer.endsWith("/prediction"), "PREDICTION_POINTER_INVALID");
                String parentPointer = binding.path("parentPredictionPointer").asText();
                require(parentPointer.startsWith("/attempts/") && parentPointer.endsWith("/prediction"), "PARENT_PREDICTION_POINTER_INVALID");
                JsonNode prediction = item.at(pointer), raw = prior.at(parentPointer);
                require(prediction.isObject() && raw.isObject(), "PREDICTION_SOURCE_MISSING");
                checkDigest(hashes, prediction, "resultId", "prediction-result-sha256-");
                checkDigest(hashes, raw, "resultId", "prediction-result-sha256-");
                String resultId = raw.path("resultId").asText();
                JsonNode declared = null;
                for (JsonNode reference : references) if (id.equals(reference.path("candidateId").asText())
                        && resultId.equals(reference.path("sourceResultId").asText())) declared = reference;
                if (declared == null) throw new IllegalArgumentException("CALIBRATION_REQUEST_PARENT_RESULT_MISMATCH");
                require(declared.path("sourceObservationSha256").equals(raw.at("/observation/observationSha256")),
                        "CALIBRATION_REQUEST_OBSERVATION_MISMATCH");
                usedReferences.add(resultId);
                JsonNode observation = prediction.path("observation");
                require("prediction-observation-v1".equals(observation.path("schemaVersion").asText())
                        && nonblank(observation.path("resource"), "model", "dataVersion"), "OBSERVATION_RESOURCE_IDENTITY_MISSING");
                checkDigest(hashes, observation, "observationSha256", "");
                require(observation.equals(raw.path("observation")), "PARENT_OBSERVATION_MISMATCH");
                JsonNode selection = prediction.path("selection"), association = prediction.path("associationInput");
                JsonNode annotation = published.at(binding.path("annotationPointer").asText());
                require(nonblank(selection, "selectedAnnotationId", "versionedTranscriptId") && annotation.isObject()
                        && hashes.canonicalSha256(annotation).equals(selection.path("annotationSourceSha256").asText()), "ANNOTATION_REFERENCE_MISMATCH");
                require(id.equals(selection.path("candidateId").asText())
                        && selection.path("sourceObservationSha256").equals(observation.path("observationSha256"))
                        && selection.path("versionedTranscriptId").equals(association.path("transcriptId"))
                        && selection.path("versionedTranscriptId").asText().equals(versionedAnnotationIdentity(annotation))
                        && annotation.path("source").equals(association.path("annotationSource"))
                        && annotation.path("version").equals(association.path("annotationSourceVersion"))
                        && association.path("annotationSourceSha256").equals(selection.path("annotationSourceSha256"))
                        && association.path("executionSourceSha256").equals(run.path("executionSourceSha256")), "ASSOCIATION_SOURCE_MISMATCH");
                require(observation.path("queryAllele").isObject() && observation.path("queryAllele").equals(published.path("queryAllele"))
                        && observation.path("queryAllele").equals(association.path("queryAllele")), "CANDIDATE_ALLELE_MISMATCH");
                require(hashes.canonicalSha256(prediction).equals(binding.path("predictionSha256").asText())
                        && nonblank(binding, "model") && binding.path("model").equals(observation.at("/resource/model")), "MODEL_OR_PREDICTION_BINDING_MISMATCH");
                if ("HIT".equals(observation.path("status").asText())) {
                    require("ASSOCIATED".equals(prediction.at("/association/status").asText()), "ASSOCIATION_UNRESOLVED");
                    JsonNode indices = prediction.at("/association/matchedRowIndices"), rows = observation.path("rows");
                    require(indices.isArray() && !indices.isEmpty() && rows.isArray(), "ROW_REFERENCE_MISSING");
                    Set<Integer> used = new HashSet<>();
                    for (JsonNode index : indices) {
                        require(index.isInt() && index.asInt() >= 0 && index.asInt() < rows.size() && used.add(index.asInt()), "ROW_REFERENCE_INVALID");
                        JsonNode row = rows.get(index.asInt());
                        require(nonblank(row, "sourceRow", "rawScore", "transcriptId")
                                && row.path("transcriptId").equals(selection.path("versionedTranscriptId"))
                                && row.path("rawScore").equals(prediction.at("/association/rawScore")), "ROW_ASSOCIATION_MISMATCH");
                        require("AM_TSV_V1".equals(binding.path("rawRowFormat").asText()), "RAW_ROW_FORMAT_UNSUPPORTED");
                        require("GRCh38".equals(observation.at("/queryAllele/genomeAssembly").asText()), "RAW_ROW_ASSEMBLY_UNSUPPORTED");
                        String[] columns = SavedPredictionAssociation.validateRawRow(row, observation.path("queryAllele"));
                        String aminoAcids = annotation.path("aminoAcids").asText(), position = annotation.path("proteinPosition").asText();
                        require(aminoAcids.matches("[ACDEFGHIKLMNPQRSTVWY]/[ACDEFGHIKLMNPQRSTVWY]")
                                && position.matches("[1-9][0-9]*")
                                && columns[7].equals(aminoAcids.charAt(0) + position + aminoAcids.charAt(2)), "ROW_PROTEIN_ANNOTATION_MISMATCH");
                    }
                    JsonNode calibration = prediction.at(binding.path("calibrationPointer").asText());
                    require(calibration.isObject() && hashes.canonicalSha256(calibration).equals(binding.path("calibrationSha256").asText())
                            && calibration.path("rawScore").equals(prediction.at("/association/rawScore")), "CALIBRATION_BINDING_MISMATCH");
                    if ("prediction-standard-calibration-result-v1".equals(calibration.path("schemaVersion").asText()))
                        validateSavedSource(input, candidate, prediction, raw, calibration, indices, rows);
                    JsonNode witness = binding.path("sourceWitness");
                    require(witness.path("observationSha256").equals(observation.path("observationSha256"))
                            && witness.path("associationSha256").asText().equals(hashes.canonicalSha256(prediction.path("association")))
                            && witness.path("rowDigests").isArray() && witness.path("rowDigests").size() == rows.size(), "ROW_WITNESS_MISMATCH");
                    for (int i = 0; i < rows.size(); i++) require(witness.path("rowDigests").get(i).asText().equals(
                            PredictionCalibration.sha256(rows.get(i).path("sourceRow").asText().getBytes(StandardCharsets.UTF_8))), "ROW_WITNESS_MISMATCH");
                }
            }
            for (JsonNode reference : references) if (id.equals(reference.path("candidateId").asText()))
                require(usedReferences.contains(reference.path("sourceResultId").asText()), "CALIBRATION_REQUEST_UNUSED_RESULT");
            return "";
        } catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
    }

    private static void validateDeclarationSet(JsonNode reference, JsonNode candidates) {
        JsonNode results = reference.path("results");
        require(results.isArray() && !results.isEmpty(), "CALIBRATION_REQUEST_RESULTS_ARRAY_REQUIRED");
        Set<String> ids = new HashSet<>(), declaredCandidates = new HashSet<>(), pairs = new HashSet<>();
        for (JsonNode candidate : candidates) ids.add(candidate.path("candidateId").asText());
        for (JsonNode result : results) {
            require(result.isObject() && nonblank(result, "candidateId", "sourceResultId", "sourceObservationSha256"),
                    "CALIBRATION_REQUEST_RESULT_FIELDS_INVALID");
            String id = result.path("candidateId").asText();
            require(ids.contains(id), "CALIBRATION_REQUEST_CANDIDATE_EXTRA_OR_MISMATCH");
            require(pairs.add(id + "\n" + result.path("sourceResultId").asText()), "CALIBRATION_REQUEST_RESULT_DUPLICATE");
            declaredCandidates.add(id);
        }
        require(ids.equals(declaredCandidates), "CALIBRATION_REQUEST_CANDIDATE_MISSING");
    }

    private static void validateSavedSource(Input input, JsonNode candidate, JsonNode prediction, JsonNode raw,
                                            JsonNode calibration, JsonNode indices, JsonNode rows) {
        JsonNode source = calibration.path("savedSource"), parent = input.document("parent");
        require(source.isObject(), "CALIBRATION_SAVED_SOURCE_MISSING");
        Map<String, JsonNode> expected = new java.util.LinkedHashMap<>();
        expected.put("analysisId", input.document("run").path("analysisId"));
        expected.put("candidateId", candidate.path("candidateId"));
        expected.put("childRunId", parent.path("runId"));
        expected.put("parentRunId", parent.path("sourceRunId"));
        expected.put("childResultId", raw.path("resultId"));
        expected.put("observationSha256", raw.at("/observation/observationSha256"));
        expected.put("selection", raw.path("selection"));
        expected.put("associationInput", raw.path("associationInput"));
        expected.put("resource", raw.at("/observation/resource"));
        for (var field : expected.entrySet()) require(!field.getValue().isMissingNode() && !field.getValue().isNull()
                && source.path(field.getKey()).equals(field.getValue()), "CALIBRATION_SAVED_SOURCE_MISMATCH:" + field.getKey());
        for (String field : List.of("selection", "associationInput", "association"))
            require(prediction.path(field).equals(raw.path(field)), "CALIBRATION_PARENT_ASSOCIATION_MISMATCH:" + field);
        require(nonblank(parent, "sourceRunId") && !parent.path("sourceRunId").equals(parent.path("runId"))
                && !parent.path("sourceRunId").equals(input.document("run").path("runId")), "CALIBRATION_ANCESTRY_INVALID");
        // Standard savedSource identifies one associated row. It cannot certify an aggregation.
        require(indices.size() == 1, "CALIBRATION_SAVED_SOURCE_ROW_SCOPE_UNSUPPORTED");
        JsonNode row = rows.get(indices.get(0).asInt());
        require(source.path("sourceRowSha256").asText().equals(PredictionCalibration.sha256(
                row.path("sourceRow").asText().getBytes(StandardCharsets.UTF_8))), "CALIBRATION_SAVED_SOURCE_MISMATCH:sourceRowSha256");
        JsonNode reference = input.document("request").at("/declaration/calibration");
        require(source.path("compatibilityEvidenceSha256").equals(reference.path("adoptionSha256")), "CALIBRATION_REQUEST_ADOPTION_MISMATCH");
        require(source.path("inputArtifacts").isObject() && source.path("localAdoption").isObject(), "CALIBRATION_SOURCE_ADOPTION_PROOF_MISSING");
        if (source.has("inputArtifacts")) {
            JsonNode artifacts = source.path("inputArtifacts");
            require(artifacts.isObject() && !artifacts.isEmpty(), "CALIBRATION_INPUT_ARTIFACTS_INVALID");
            for (var entry : artifacts.properties()) {
                String role = "child".equals(entry.getKey()) ? "parent" : "calibrationArchive." + entry.getKey();
                require(entry.getValue().isTextual() && entry.getValue().equals(input.identity().path("sourceByteSha256").path(role)),
                        "CALIBRATION_INPUT_ARTIFACT_UNVERIFIED:" + entry.getKey());
            }
        }
        if (source.has("compatibilityEvidenceSha256") || source.has("localAdoption")) {
            JsonNode adoption = input.document("calibrationAdoption");
            require(adoption.isObject() && source.path("compatibilityEvidenceSha256").isTextual()
                    && source.path("compatibilityEvidenceSha256").equals(input.identity().at("/sourceByteSha256/calibrationAdoption")),
                    "CALIBRATION_SOURCE_ADOPTION_UNVERIFIED");
            for (String field : List.of("inputArtifacts", "localAdoption")) require(source.path(field).isObject()
                    && source.path(field).equals(adoption.path(field)), "CALIBRATION_SOURCE_ADOPTION_MISMATCH:" + field);
            if (calibration.has("localAdoption")) require(calibration.path("localAdoption").equals(source.path("localAdoption")),
                    "CALIBRATION_SOURCE_ADOPTION_MISMATCH:localAdoption");
        }
    }

    static String issuePath(String issue) {
        if (issue.startsWith("CALIBRATION_SAVED_SOURCE_MISMATCH:"))
            return "/candidate/evidence/calibration/savedSource/" + issue.substring(issue.indexOf(':') + 1);
        if (issue.startsWith("CALIBRATION_REQUEST")) return "/request/declaration/calibration/results";
        if (issue.startsWith("SOURCE_CANDIDATE")) return "/sources/" + issue.substring(issue.lastIndexOf('_') + 1).toLowerCase(java.util.Locale.ROOT) + "/candidates";
        if (issue.startsWith("SETTINGS")) return "/settings";
        return "/candidate/source";
    }

    static JsonNode prediction(Input input, JsonNode candidate, JsonNode binding) {
        return unique(input.document("run").path("candidates"), "candidateId", candidate.path("candidateId").asText())
                .at(binding.path("predictionPointer").asText()).deepCopy();
    }

    static JsonNode unique(JsonNode list, String field, String value) {
        JsonNode found = null;
        require(list.isArray(), "SOURCE_REFERENCE_LIST_MISSING");
        for (JsonNode item : list) if (value.equals(item.path(field).asText())) {
            require(found == null, "SOURCE_REFERENCE_AMBIGUOUS"); found = item;
        }
        if (found == null) throw new IllegalArgumentException("SOURCE_REFERENCE_MISSING");
        return found;
    }

    private static void checkDigest(RuntimeEvidenceV2Adapter hashes, JsonNode node, String field, String prefix) {
        require(node instanceof ObjectNode, "SOURCE_OBJECT_MISSING");
        ObjectNode projection = ((ObjectNode) node).deepCopy(); projection.remove(field);
        require((prefix + hashes.canonicalSha256(projection)).equals(node.path(field).asText()), "SOURCE_CANONICAL_DIGEST_MISMATCH");
    }
    static boolean nonblank(JsonNode node, String... fields) {
        for (String field : fields) if (!node.path(field).isTextual() || node.path(field).asText().isBlank()) return false;
        return true;
    }
    static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
