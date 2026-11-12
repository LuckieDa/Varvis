package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

/** Offline extraction of pinned build snapshots. No discovery, evaluation or scientific approval. */
public final class MissenseReferenceDraftPreparation {
    public static final String SOURCE_FACT_SEMANTICS_VERSION = "1.0.0";
    public static final String ANNOTATION_VERSION_BINDING_VERSION = "1.0.0";
    static final int MAX_FILE = 4_000_000, MAX_TOTAL = 32_000_000, MAX_CANDIDATES = 256, MAX_ASSERTIONS = 1024;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final ArrayNode inputs = mapper.createArrayNode(), outputs = mapper.createArrayNode();
    private final Map<String, ObjectNode> origins = new LinkedHashMap<>();
    private int bytesRead;
    public record Result(boolean generated, ObjectNode report) { }

    public Result prepare(Path manifestPath, String manifestSha256, Path output) throws IOException {
        require(bytesRead == 0, "EXTRACTOR_ALREADY_USED");
        require(!Files.exists(output), "OUTPUT_EXISTS_REFUSE_OVERWRITE");
        Files.createDirectory(output);
        ObjectNode report = draft("missense-reference-draft-report-v1").put("extractionStatus", "BLOCKED")
                .put("scientificAdoption", false).put("runtimeExecution", "NOT_RUN");
        ArrayNode gaps = report.putArray("gaps");
        boolean generated = false;
        try {
            ObjectNode manifest = object(mapper.readTree(read(manifestPath, manifestSha256, "manifest")), "MANIFEST_OBJECT_REQUIRED");
            require("missense-reference-draft-manifest-v1".equals(manifest.path("schemaVersion").asText()), "MANIFEST_VERSION_UNSUPPORTED");
            String dataClass = text(manifest, "dataClass");
            require(Set.of("SYNTHETIC", "PUBLIC_REFERENCE").contains(dataClass), "DATA_CLASS_UNSUPPORTED");
            require(!"SYNTHETIC".equals(dataClass) || "TEST_ONLY".equals(manifest.path("purpose").asText()), "SYNTHETIC_TEST_ONLY_REQUIRED");
            Path root = Objects.requireNonNull(manifestPath.toAbsolutePath().getParent());
            ObjectNode context = input(root, manifest.path("context"), "context");
            ObjectNode target = input(root, manifest.path("target"), "target");
            ObjectNode built = input(root, manifest.path("comparatorInput"), "comparatorInput");
            ObjectNode sources = input(root, manifest.path("sourceSnapshot"), "sourceSnapshot");
            TranscriptConsequence selected = validateTarget(manifest, context, target, sources);
            String contextId = text(context, "contextId"), transcript = transcript(context.path("transcript"));
            ObjectNode targetAllele = allele(context.at("/variant/evidenceQueryAllele"), false);
            require(ClinVarConditionAwareComparatorInput.RESULT_SCHEMA_VERSION.equals(built.path("schemaVersion").asText()), "COMPARATOR_SCHEMA_INVALID");
            require(contextId.equals(text(built, "contextId")) && targetAllele.equals(allele(built.path("targetAllele"), true)), "COMPARATOR_CONTEXT_MISMATCH");
            require(Set.of("READY", "NO_RECORD", "NOT_EVALUATED").contains(text(built, "status")), "COMPARATOR_STATUS_INVALID");
            bounded(built.path("comparators"), MAX_CANDIDATES, "CANDIDATE_LIMIT_OR_ARRAY_INVALID");
            bounded(built.path("candidateDecisions"), MAX_CANDIDATES, "DECISION_LIMIT_OR_ARRAY_INVALID");
            bounded(manifest.path("referenceAnnotations"), MAX_CANDIDATES, "ANNOTATION_LIMIT_OR_ARRAY_INVALID");
            Map<String, ObjectNode> annotations = new LinkedHashMap<>();
            Map<String, ArrayNode> annotationOrigins = new LinkedHashMap<>();
            int index = 0;
            for (JsonNode descriptor : manifest.path("referenceAnnotations")) {
                String role = "referenceAnnotation-" + index++;
                ObjectNode snapshot = input(root, descriptor, role);
                require("missense-reference-annotation-snapshot-v1".equals(snapshot.path("schemaVersion").asText()), "REFERENCE_SNAPSHOT_SCHEMA_INVALID");
                require(contextId.equals(text(snapshot, "contextId")) && targetAllele.equals(allele(snapshot.path("targetAllele"), false)), "REFERENCE_CONTEXT_MISMATCH");
                require(transcript.equals(text(snapshot, "transcript")), "REFERENCE_TRANSCRIPT_MISMATCH");
                ObjectNode referenceAllele = allele(snapshot.path("referenceAllele"), false);
                String variation = variation(snapshot);
                annotation(object(snapshot.path("annotation"), "REFERENCE_ANNOTATION_INVALID"), transcript, sources.path("VEP"), snapshot.path("source"));
                String key = key(referenceAllele, variation, transcript);
                ObjectNode prior = annotations.putIfAbsent(key, snapshot);
                require(prior == null || prior.equals(snapshot), "REFERENCE_ANNOTATION_CONFLICT");
                annotationOrigins.computeIfAbsent(key, ignored -> mapper.createArrayNode()).add(origin(role, "/annotation"));
            }
            ObjectNode identity = mapper.createObjectNode();
            origins.forEach((role, value) -> identity.set(role, value));
            String snapshotId = "missense-draft-sha256-" + adapter.canonicalSha256(identity);
            ObjectNode referenceDraft = draft("missense-reference-audit-draft-v1").put("draftId", snapshotId).put("dataClass", dataClass);
            referenceDraft.set("context", context); referenceDraft.set("target", target);
            ObjectNode pairDraft = draft("missense-rna-pairs-draft-v1").put("draftId", snapshotId).put("dataClass", dataClass);
            pairDraft.put("rnaEvidenceStatus", "NOT_PROVIDED");
            ArrayNode references = referenceDraft.putArray("references"), pairs = pairDraft.putArray("pairs");
            Map<String, ObjectNode> referencesByKey = new LinkedHashMap<>(), pairsByKey = new LinkedHashMap<>();
            Map<String, Map<String, ObjectNode>> assertionsByReference = new HashMap<>();
            Set<String> usedAnnotations = new HashSet<>();
            int assertionCount = 0, summaryCount = 0;
            index = 0;
            for (JsonNode record : built.path("comparators")) {
                String pointer = "/comparators/" + index++;
                require(ClinVarConditionAwareComparatorInput.RECORD_SCHEMA_VERSION.equals(record.path("schemaVersion").asText()), "COMPARATOR_RECORD_SCHEMA_INVALID");
                require(contextId.equals(text(record, "targetContextId")), "REFERENCE_CONTEXT_MISMATCH");
                ObjectNode referenceAllele = allele(record.path("exactAllele"), true);
                require(targetAllele.path("contig").equals(referenceAllele.path("contig")), "REFERENCE_CONTIG_MISMATCH");
                String variation = variation(record), key = key(referenceAllele, variation, transcript);
                boolean decisionBound = false;
                for (JsonNode decision : built.path("candidateDecisions")) if (record.path("exactAllele").equals(decision.path("exactAllele"))
                        && record.path("variationId").equals(decision.path("variationId")) && "READY".equals(decision.path("status").asText())
                        && record.path("snapshotProvenance").equals(decision.path("snapshotProvenance"))) decisionBound = true;
                require(decisionBound, "COMPARATOR_DECISION_BINDING_MISMATCH");
                require(sourceMatches(record.path("snapshotProvenance"), sources.path("SIDECAR")), "SIDECAR_SOURCE_MISMATCH");
                String position = referenceAllele.path("position").asText();
                require((referenceAllele.path("contig").asText() + ":" + position + "-" + position + ";variationId=" + variation)
                        .equals(text(record.path("snapshotProvenance"), "recordLocator"))
                        && ("ClinVar:VariationID:" + variation).equals(text(record, "clinVarVcfRecordLocator")), "REFERENCE_LOCATOR_MISMATCH");
                require(Set.of("SAME_AMINO_ACID_CHANGE", "SAME_RESIDUE_DIFFERENT_AMINO_ACID").contains(text(record, "proteinRelation")), "PROTEIN_RELATION_UNSUPPORTED");
                checkProtein(record.path("targetProteinChange"), selected, transcript);
                require(transcript.equals(text(record.path("comparatorProteinChange"), "transcriptId")), "COMPARATOR_TRANSCRIPT_MISMATCH");
                ObjectNode snapshot = annotations.get(key);
                if (snapshot != null) {
                    TranscriptConsequence reference = annotation(object(snapshot.path("annotation"), "REFERENCE_ANNOTATION_INVALID"), transcript, sources.path("VEP"), snapshot.path("source"));
                    require(Objects.equals(selected.getGeneSymbol(), reference.getGeneSymbol()), "REFERENCE_ANNOTATION_GENE_MISMATCH");
                    checkProtein(record.path("comparatorProteinChange"), reference, transcript);
                    var relation = new VepProteinRelationClassifier().classify(selected, reference);
                    require(relation.isEvaluated() && record.path("proteinRelation").asText().equals(String.valueOf(relation.getRelation())), "PROTEIN_RELATION_SNAPSHOT_MISMATCH");
                    require(record.path("selectedComparatorConsequence").asText().equals(reference.getConsequence()), "COMPARATOR_CONSEQUENCE_MISMATCH");
                    usedAnnotations.add(key);
                } else gap(gaps, "REFERENCE_ANNOTATION_MISSING", pointer);
                require(record.path("selectedTargetConsequence").asText().equals(selected.getConsequence()), "TARGET_CONSEQUENCE_MISMATCH");
                bounded(record.path("submissions"), MAX_ASSERTIONS, "ASSERTION_LIMIT_OR_ARRAY_INVALID");
                bounded(record.path("variantSummaryRecords"), MAX_ASSERTIONS, "CONDITION_LIMIT_OR_ARRAY_INVALID");
                assertionCount += record.path("submissions").size(); summaryCount += record.path("variantSummaryRecords").size();
                require(assertionCount <= MAX_ASSERTIONS && summaryCount <= MAX_ASSERTIONS, "ASSERTION_OR_CONDITION_TOTAL_LIMIT");
                ObjectNode reference = referencesByKey.get(key);
                if (reference == null) {
                    reference = mapper.createObjectNode().put("referenceDraftId", itemId(snapshotId, key)).put("reviewStatus", "UNREVIEWED")
                            .put("variationId", variation).put("transcript", transcript);
                    reference.set("allele", referenceAllele); reference.putArray("observations"); reference.putArray("assertions");
                    if (snapshot != null) { reference.set("annotation", snapshot.path("annotation")); reference.set("annotationOrigins", annotationOrigins.get(key)); }
                    reference.putArray("reviewRequired").add("APPLICABLE_BACKGROUND").add("INDEPENDENCE_WITHOUT_PS1_PM5").add("SUFFICIENCY_WITHOUT_PS1_PM5").add("REVIEWER_AND_DECISION");
                    referencesByKey.put(key, reference); references.add(reference);
                } else gap(gaps, "REPEATED_REFERENCE_OBSERVATION", pointer);
                ObjectNode observation = reference.withArray("observations").addObject(); observation.set("record", record); observation.set("origin", origin("comparatorInput", pointer));
                Map<String, ObjectNode> assertions = assertionsByReference.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
                int scvIndex = 0;
                for (JsonNode assertion : record.path("submissions")) {
                    object(assertion, "SUBMISSION_OBJECT_REQUIRED");
                    String assertionKey = adapter.canonicalSha256(assertion);
                    ObjectNode fact = assertions.get(assertionKey);
                    String assertionPointer = pointer + "/submissions/" + scvIndex++;
                    if (fact == null) {
                        for (JsonNode prior : reference.path("assertions")) if (assertion.path("scv").equals(prior.at("/sourceAssertion/scv"))) gap(gaps, "CONFLICTING_ASSERTION_PAYLOADS", assertionPointer);
                        fact = mapper.createObjectNode().put("assertionDraftId", itemId(snapshotId, key + assertionKey)).put("reviewStatus", "UNREVIEWED");
                        fact.set("sourceAssertion", assertion); fact.putArray("origins"); assertions.put(assertionKey, fact); reference.withArray("assertions").add(fact);
                    } else gap(gaps, "DUPLICATE_ASSERTION_LOCATIONS", assertionPointer);
                    fact.withArray("origins").add(origin("comparatorInput", assertionPointer));
                    if (!assertion.path("scv").asText().matches("SCV[0-9]+\\.[1-9][0-9]*")) gap(gaps, "SCV_VERSION_MISSING", assertionPointer);
                    for (String field : List.of("classification", "dateLastEvaluated", "condition")) if (assertion.path(field).isMissingNode() || assertion.path(field).isNull()) gap(gaps, "ASSERTION_FIELD_MISSING_" + field, assertionPointer);
                }
                if (record.path("submissions").isEmpty()) gap(gaps, "SUBMISSIONS_MISSING", pointer);
                if (!Set.of("NO_CONFLICT", "NO_CONFLICT_REPORTED").contains(record.path("conflictState").asText()))
                    gap(gaps, "SOURCE_CONFLICT_REQUIRES_REVIEW", pointer);
                String pairKey = adapter.canonicalSha256(referenceAllele);
                ObjectNode pair = pairsByKey.get(pairKey);
                if (pair == null) {
                    pair = mapper.createObjectNode().put("pairDraftId", itemId(snapshotId, pairKey)).put("transcript", transcript)
                            .put("reviewStatus", "UNREVIEWED").put("rnaEvidenceStatus", "NOT_PROVIDED");
                    pair.set("targetAllele", targetAllele); pair.set("referenceAllele", referenceAllele); pair.putArray("referenceDraftIds");
                    pairsByKey.put(pairKey, pair); pairs.add(pair);
                }
                JsonNode refId = reference.path("referenceDraftId"); if (!contains(pair.path("referenceDraftIds"), refId)) pair.withArray("referenceDraftIds").add(refId);
                if (targetAllele.equals(referenceAllele)) gap(gaps, "TARGET_SELF_REFERENCE_REQUIRES_REVIEW", pointer);
            }
            // Every supplied annotation must bind an actual observed comparator, never silently attach by array position.
            require(usedAnnotations.equals(annotations.keySet()), "REFERENCE_ANNOTATION_NOT_BOUND_TO_COMPARATOR");
            for (JsonNode decision : built.path("candidateDecisions")) {
                ObjectNode decidedAllele = allele(decision.path("exactAllele"), true);
                require(targetAllele.path("contig").equals(decidedAllele.path("contig")), "DECISION_CONTIG_MISMATCH");
                String decisionKey = key(decidedAllele, variation(decision), transcript); text(decision, "status");
                if (decision.path("snapshotProvenance").isObject()) require(sourceMatches(decision.path("snapshotProvenance"), sources.path("SIDECAR")), "SIDECAR_SOURCE_MISMATCH");
                if ("READY".equals(decision.path("status").asText())) require(referencesByKey.containsKey(decisionKey), "READY_DECISION_WITHOUT_COMPARATOR");
                if (!"READY".equals(decision.path("status").asText())) gap(gaps, "CANDIDATE_OMITTED_SEE_SOURCE_DECISION", "/candidateDecisions");
            }
            // Preserve source state literally; even READY alone is not a claim of exhaustive discovery.
            report.set("sourceSearch", built.deepCopy());
            gap(gaps, "REFERENCE_SCIENTIFIC_REVIEW_REQUIRED", "/references"); gap(gaps, "RNA_EVIDENCE_NOT_PROVIDED", "/pairs");
            if (!MissenseComparisonPolicy.text(context.at("/gene/hgncId")) || !MissenseComparisonPolicy.text(context.at("/disease/mondoId"))
                    || !MissenseComparisonPolicy.text(context.path("moi"))) gap(gaps, "BACKGROUND_MAPPING_REQUIRES_REVIEW", "/context");
            report.put("draftId", snapshotId).put("dataClass", dataClass).put("referenceCount", references.size()).put("pairCount", pairs.size());
            ObjectNode sourceMap = draft("missense-draft-source-map-v1").put("draftId", snapshotId);
            sourceMap.set("sourceSnapshot", sources); sourceMap.set("inputs", identity);
            sourceMap.put("sourceIdentitySemantics", "EXTERNALLY_PINNED_SNAPSHOT_DECLARATIONS_NOT_LIVE_RESOURCE_VERIFICATION");
            write(output, "reference-audit.draft.json", referenceDraft); write(output, "rna-pairs.draft.json", pairDraft); write(output, "source-map.json", sourceMap);
            report.put("extractionStatus", "DRAFT_GENERATED"); generated = true;
        } catch (IllegalArgumentException | IOException invalid) {
            report.put("reasonCode", reason(invalid));
        }
        write(output, "draft-report.json", report);
        ObjectNode manifest = mapper.createObjectNode().put("schemaVersion", "missense-draft-artifact-manifest-v1").put("generatedAt", Instant.now().toString());
        manifest.set("inputs", inputs); manifest.set("outputs", outputs);
        Files.write(output.resolve("artifact-manifest.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest), StandardOpenOption.CREATE_NEW);
        return new Result(generated, report);
    }

    // Pure contract checks shared by the mechanical exporter; no reads, writes or approval.
    TranscriptConsequence validateTarget(JsonNode manifest, ObjectNode context, ObjectNode target, ObjectNode sources) throws IOException {
        String dataClass = text(manifest, "dataClass");
        require(Set.of("SYNTHETIC", "PUBLIC_REFERENCE").contains(dataClass), "DATA_CLASS_UNSUPPORTED");
        require(!"SYNTHETIC".equals(dataClass) || "TEST_ONLY".equals(manifest.path("purpose").asText()), "SYNTHETIC_TEST_ONLY_REQUIRED");
        require(dataClass.equals(sources.path("dataClass").asText()) && manifest.path("purpose").equals(sources.path("purpose")), "SOURCE_DATA_CLASS_MISMATCH");
        for (String role : List.of("CLINVAR_VCF", "SIDECAR", "VEP")) sourceIdentity(sources.path(role));
        require("evidence-context-v2".equals(context.path("schemaVersion").asText()), "CONTEXT_SCHEMA_INVALID");
        String contextId = text(context, "contextId"), transcript = transcript(context.path("transcript"));
        ObjectNode targetAllele = allele(context.at("/variant/evidenceQueryAllele"), false);
        require(dataClass.equals(context.path("dataClass").asText()), "CONTEXT_DATA_CLASS_MISMATCH");
        require("NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                && targetAllele.equals(allele(context.at("/variant/inputAllele"), false)) && targetAllele.equals(allele(context.at("/variant/normalizedAllele"), false)), "NATIVE_CONTEXT_REQUIRED");
        require(contextId.equals(adapter.canonicalContextId(context)) && context.at("/variant/alleleId").asText()
                .equals(adapter.canonicalAlleleId(targetAllele, context.at("/variant/normalization"))), "CONTEXT_IDENTITY_INVALID");
        require("missense-target-annotation-snapshot-v1".equals(target.path("schemaVersion").asText()), "TARGET_SNAPSHOT_SCHEMA_INVALID");
        require(contextId.equals(text(target, "contextId")) && targetAllele.equals(allele(target.path("allele"), false)), "TARGET_CONTEXT_MISMATCH");
        require(transcript.equals(text(target, "transcript")), "TARGET_TRANSCRIPT_MISMATCH");
        TranscriptConsequence selected = annotation(object(target.path("annotation"), "TARGET_ANNOTATION_MISSING"), transcript, sources.path("VEP"), target.path("source"));
        if (MissenseComparisonPolicy.text(context.at("/gene/symbol"))) require(context.at("/gene/symbol").asText().equals(selected.getGeneSymbol()), "TARGET_GENE_MISMATCH");
        if (MissenseComparisonPolicy.text(context.at("/transcript/annotationSourceVersion"))) require(context.at("/transcript/annotationSourceVersion").asText().equals(selected.getVersion()), "TARGET_ANNOTATION_VERSION_MISMATCH");
        return selected;
    }

    private ObjectNode draft(String schema) { return mapper.createObjectNode().put("schemaVersion", schema).put("draft", true).put("reviewStatus", "UNREVIEWED"); }
    String key(JsonNode allele, String variation, String transcript) { return adapter.canonicalSha256(allele) + ":" + variation + ":" + transcript; }
    private String itemId(String snapshotId, String identity) { return "draft-item-sha256-" + adapter.canonicalSha256(mapper.createObjectNode().put("snapshot", snapshotId).put("identity", identity)); }
    private static boolean contains(JsonNode values, JsonNode wanted) { for (JsonNode v : values) if (v.equals(wanted)) return true; return false; }
    private void gap(ArrayNode gaps, String code, String pointer) { gaps.addObject().put("reasonCode", code).put("jsonPointer", pointer).put("reviewStatus", "NEEDS_REVIEW"); }
    String variation(JsonNode node) { String id = text(node, "variationId"); require(id.matches("[1-9][0-9]*"), "VARIATION_ID_INVALID"); return id; }
    String transcript(JsonNode node) {
        require(node.path("version").isIntegralNumber() && node.path("version").canConvertToInt() && node.path("version").asInt() > 0, "TRANSCRIPT_VERSION_REQUIRED");
        String value = text(node, "accession") + "." + node.path("version").asInt();
        require(value.matches("(?:ENST[0-9]+|NM_[0-9]+)\\.[1-9][0-9]*"), "TRANSCRIPT_VERSION_REQUIRED"); return value;
    }
    ObjectNode allele(JsonNode node, boolean domain) {
        String assembly = text(node, domain ? "genomeAssembly" : "assembly"), contig = text(node, domain ? "chromosome" : "contig");
        String ref = text(node, "reference"), alt = text(node, "alternate");
        require("GRCh38".equals(assembly), "ASSEMBLY_UNSUPPORTED");
        require(contig.matches("(?:[1-9]|1[0-9]|2[0-2]|X|Y|MT)") && ref.matches("[ACGT]") && alt.matches("[ACGT]") && !ref.equals(alt)
                && node.path("position").isIntegralNumber() && node.path("position").canConvertToInt() && node.path("position").asInt() > 0, "EXACT_NATIVE_SNV_REQUIRED");
        return mapper.createObjectNode().put("assembly", assembly).put("contig", contig).put("position", node.path("position").asInt()).put("reference", ref).put("alternate", alt);
    }
    void sourceIdentity(JsonNode value) { text(value, "resourceId"); text(value, "resourceVersion"); require(MissenseComparisonPolicy.hash(value.path("identityFingerprintSha256")), "SOURCE_FINGERPRINT_REQUIRED"); }
    boolean sourceMatches(JsonNode actual, JsonNode expected) {
        sourceIdentity(actual); for (String field : List.of("resourceId", "resourceVersion", "identityFingerprintSha256")) if (!actual.path(field).equals(expected.path(field))) return false; return true;
    }
    TranscriptConsequence annotation(ObjectNode value, String transcript, JsonNode source, JsonNode actualSource) throws IOException {
        require(sourceMatches(actualSource, source), "ANNOTATION_SOURCE_MISMATCH");
        require("COMPLETED".equals(value.path("status").asText()) && text(value, "source").startsWith("VEP")
                && annotationVersionMatches(text(value, "version"), source), "ANNOTATION_STATUS_OR_VERSION_INVALID");
        require(Arrays.asList(text(value, "consequence").split("[,&]" )).contains("missense_variant"), "MISSENSE_ANNOTATION_REQUIRED");
        return parseAnnotation(value, transcript);
    }

    private boolean annotationVersionMatches(String version, JsonNode source) {
        // Historical exported annotations used the complete resource label. Actual VEP
        // DTOs keep the tool version; accept it only through the exact registered binding.
        if (version.equals(source.path("resourceVersion").asText())) return true;
        for (var registered : ClinVarMissenseResourceReadiness.identityPolicy(mapper).path("resources")) {
            if ("VEP".equals(registered.path("provider").asText()) && sourceMatches(source, registered))
                return version.equals(registered.at("/identity/tool/version").asText());
        }
        return false;
    }

    TranscriptConsequence parseAnnotation(ObjectNode value, String transcript) throws IOException {
        // The complete DTO serializes these read-only aliases; validate them strictly and
        // parse a copy, preserving every original input field and its pinned identity.
        ObjectNode parseValue = value.deepCopy();
        for (var alias : Map.of("hgvsc", "hgvsCoding", "hgvsp", "hgvsProtein").entrySet()) {
            if (parseValue.has(alias.getKey())) {
                require(parseValue.path(alias.getKey()).equals(parseValue.path(alias.getValue())), "ANNOTATION_HGVS_ALIAS_MISMATCH");
                parseValue.remove(alias.getKey());
            }
        }
        TranscriptConsequence parsed = mapper.treeToValue(parseValue, TranscriptConsequence.class);
        require(parsed != null && transcript.equals(parsed.getVersionedTranscriptId()), "ANNOTATION_TRANSCRIPT_MISMATCH");
        if (parsed == null) throw new IllegalArgumentException("ANNOTATION_MISSING");
        return parsed;
    }
    void checkProtein(JsonNode declared, TranscriptConsequence annotation, String transcript) {
        var relation = new VepProteinRelationClassifier().classify(annotation, annotation);
        require(relation.isEvaluated() && transcript.equals(declared.path("transcriptId").asText())
                && declared.equals(mapper.valueToTree(relation.getTarget())), "PROTEIN_CHANGE_SNAPSHOT_MISMATCH");
    }
    private ObjectNode input(Path root, JsonNode descriptor, String role) throws IOException {
        Path file = root.resolve(text(descriptor, "path"));
        ObjectNode value = object(mapper.readTree(read(file, text(descriptor, "rawSha256"), role)), "INPUT_OBJECT_REQUIRED");
        String canonical = adapter.canonicalSha256(value);
        require(canonical.equals(text(descriptor, "canonicalSha256")), "INPUT_CANONICAL_SHA_MISMATCH");
        noPrivatePaths(value);
        ObjectNode source = mapper.createObjectNode().put("inputRole", role).put("rawSha256", descriptor.path("rawSha256").asText()).put("canonicalSha256", canonical);
        origins.put(role, source); ((ObjectNode) inputs.get(inputs.size() - 1)).put("canonicalSha256", canonical); return value;
    }
    private ObjectNode origin(String role, String pointer) { ObjectNode result = Objects.requireNonNull(origins.get(role)).deepCopy(); return result.put("jsonPointer", pointer); }
    private byte[] read(Path file, String pin, String role) throws IOException {
        require(pin.matches("[0-9a-f]{64}"), "INPUT_RAW_PIN_REQUIRED"); require(Files.isRegularFile(file), "INPUT_FILE_MISSING");
        require(Files.size(file) <= MAX_FILE, "INPUT_SIZE_LIMIT"); byte[] bytes;
        try (var stream = Files.newInputStream(file)) { bytes = stream.readNBytes(MAX_FILE + 1); }
        bytesRead += bytes.length; require(bytes.length <= MAX_FILE && bytesRead <= MAX_TOTAL, "INPUT_SIZE_LIMIT");
        String sha = Pm4MaterialPreparation.sha(bytes);
        inputs.addObject().put("role", role).put("path", file.toAbsolutePath().normalize().toString()).put("bytes", bytes.length).put("rawSha256", sha);
        require(pin.equals(sha), "INPUT_RAW_SHA_MISMATCH"); return bytes;
    }
    private void write(Path output, String name, ObjectNode value) throws IOException {
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        Files.write(output.resolve(name), bytes, StandardOpenOption.CREATE_NEW);
        outputs.addObject().put("file", name).put("rawSha256", Pm4MaterialPreparation.sha(bytes)).put("canonicalSha256", adapter.canonicalSha256(value));
    }
    static void noPrivatePaths(JsonNode value) {
        if (value.isTextual()) { String s = value.asText(); require(!s.matches("^[A-Za-z]:[\\\\/].*") && !s.startsWith("\\\\") && !s.startsWith("file:") && !s.startsWith("/"), "PRIVATE_PATH_IN_SNAPSHOT"); }
        else value.forEach(MissenseReferenceDraftPreparation::noPrivatePaths);
    }
    private static void bounded(JsonNode values, int max, String code) { require(values.isArray() && values.size() <= max, code); }
    private static ObjectNode object(JsonNode value, String code) { require(value instanceof ObjectNode, code); return (ObjectNode) value; }
    private static String text(JsonNode node, String field) { require(MissenseComparisonPolicy.text(node.path(field)), "REQUIRED_FIELD_" + field); return node.path(field).asText(); }
    private static void require(boolean valid, String code) { if (!valid) throw new IllegalArgumentException(code); }
    private static String reason(Exception failure) {
        if (failure instanceof com.fasterxml.jackson.core.JsonProcessingException) return "INPUT_JSON_INVALID_OR_DUPLICATE_KEY";
        if (failure instanceof IOException) return "INPUT_OUTPUT_IO_FAILURE";
        String message = failure.getMessage(); return message != null && message.matches("[A-Za-z0-9_]+") ? message : "DRAFT_INPUT_INVALID";
    }
    public static void main(String[] args) {
        try {
            require(args.length == 6 && "--manifest".equals(args[0]) && "--manifest-sha256".equals(args[2]) && "--output".equals(args[4]), "USAGE_MANIFEST_MANIFEST_SHA256_OUTPUT");
            Result result = new MissenseReferenceDraftPreparation().prepare(Path.of(args[1]), args[3], Path.of(args[5]));
            System.out.println(result.report().toPrettyString()); if (!result.generated()) System.exit(2);
        } catch (Exception failure) { System.err.println(reason(failure)); System.exit(2); }
    }
}
