package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.springframework.lang.Nullable;

/** Trusted knowledge for explicit legacy and versioned regional/missense execution; never loaded from a case declaration. */
public final class ReviewedRuleBundle {
    private static final int MAXIMUM_BYTES = 8_000_000;
    private static final List<String> CRITERIA = List.of(TruncatingNmdPvs1Runtime.CRITERION, FunctionalEvidenceRuntime.PS3);
    private static final String CURRENT = "CURRENT_CATALOG_RESULT";
    private static final String LEGACY = "PREEXISTING_POLICY_OUTSIDE_CATALOG_SCOPE";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    @Nullable private final AcmgSpecificationSelector selector;
    private final ObjectNode materials;
    private final ObjectNode identity;
    @Nullable private final ObjectNode missenseQualification;
    private final boolean bp1;
    private final java.util.concurrent.atomic.AtomicInteger bp1Sequences = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger bp1Runtimes = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger bp1Projections = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger bp1Evaluators = new java.util.concurrent.atomic.AtomicInteger();
    private final boolean pp2;
    private final java.util.concurrent.atomic.AtomicInteger pp2Sequences = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger pp2Runtimes = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger pp2Projections = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger pp2Evaluators = new java.util.concurrent.atomic.AtomicInteger();
    private final boolean bp3;
    private final java.util.concurrent.atomic.AtomicInteger bp3Runtimes = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger bp3Projections = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger bp3Evaluators = new java.util.concurrent.atomic.AtomicInteger();
    private final boolean pm4;
    private final boolean ps1;
    private final boolean pm5;
    private final boolean pm1;
    private final boolean pvs1Only;
    @Nullable private final Pvs1TranscriptMaterialSupply pvs1Materials;
    @Nullable private final Pvs1KnowledgeCatalog pvs1Knowledge;
    @Nullable private Path controlledMaterialRoot;
    public static final String FULL_RNA_PREPARATION_VERSION = "1.1.0";
    private final java.util.concurrent.atomic.AtomicInteger pvs1Runtimes = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger pvs1Projections = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger pvs1Evaluators = new java.util.concurrent.atomic.AtomicInteger();
    @Nullable private final java.util.function.Supplier<MissenseComparisonRuntime> missense;
    private final java.util.concurrent.atomic.AtomicInteger lengthCalculations = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger pm4Evaluators = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger pm1Runtimes = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger pm1Positions = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger pm1Evaluators = new java.util.concurrent.atomic.AtomicInteger();

    private ReviewedRuleBundle(ObjectMapper mapper, String expectedSha256) {
        this.mapper = Objects.requireNonNull(mapper);
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        selector = null;
        materials = mapper.createObjectNode();
        missenseQualification = null;
        bp1 = false; pp2 = false; bp3 = false; pm4 = false;
        ps1 = false; pm5 = false; pm1 = false; pvs1Only = false; missense = null; pvs1Materials = null; pvs1Knowledge = null;
        identity = binding().put("status", "UNAVAILABLE").put("reasonCode", "REVIEWED_RULE_CONFIGURATION_UNAVAILABLE")
                .put("expectedBundleSha256", expectedSha256.matches("[0-9a-f]{64}") ? expectedSha256 : "INVALID_OR_MISSING");
    }

    public static ReviewedRuleBundle unavailable(ObjectMapper mapper, String expectedSha256) {
        return new ReviewedRuleBundle(mapper, expectedSha256);
    }

    public static ReviewedRuleBundle load(ObjectMapper mapper, Path path, String expectedSha256) throws IOException {
        return load(mapper, path, expectedSha256, null, null);
    }

    public static ReviewedRuleBundle load(ObjectMapper mapper, Path path, String expectedSha256,
            @Nullable java.util.function.Supplier<MissenseComparisonRuntime> runtime, @Nullable JsonNode binding) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > MAXIMUM_BYTES)
            throw new IllegalArgumentException("REVIEWED_RULE_BUNDLE_SIZE_INVALID");
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(MAXIMUM_BYTES + 1);
            if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("REVIEWED_RULE_BUNDLE_SIZE_INVALID");
            if (!(mapper.readTree(bytes) instanceof ObjectNode document))
                throw new IllegalArgumentException("REVIEWED_RULE_BUNDLE_NOT_OBJECT");
            return new ReviewedRuleBundle(mapper, document, expectedSha256, runtime, binding);
        }
    }

    public ReviewedRuleBundle(ObjectMapper mapper, ObjectNode document, String expectedSha256) {
        this(mapper, document, expectedSha256, null, null);
    }

    /** Server factory only: runtime configuration is bound before recommendations or run identity are issued. */
    public ReviewedRuleBundle(ObjectMapper mapper, ObjectNode document, String expectedSha256,
            @Nullable java.util.function.Supplier<MissenseComparisonRuntime> missense,
            @Nullable JsonNode runtimeBinding) {
        this(mapper, document, expectedSha256, missense, runtimeBinding, null);
    }

    private ReviewedRuleBundle(ObjectMapper mapper, ObjectNode document, String expectedSha256,
            @Nullable java.util.function.Supplier<MissenseComparisonRuntime> missense,
            @Nullable JsonNode runtimeBinding, @Nullable Pvs1TranscriptMaterialSupply pvs1Materials) {
        this(mapper, document, expectedSha256, missense, runtimeBinding, pvs1Materials, null);
    }
    private ReviewedRuleBundle(ObjectMapper mapper, ObjectNode document, String expectedSha256,
            @Nullable java.util.function.Supplier<MissenseComparisonRuntime> missense,
            @Nullable JsonNode runtimeBinding, @Nullable Pvs1TranscriptMaterialSupply pvs1Materials,
            @Nullable Pvs1KnowledgeCatalog pvs1Knowledge) {
        this.mapper = Objects.requireNonNull(mapper);
        this.missense = missense;
        this.pvs1Materials = pvs1Materials;
        this.pvs1Knowledge = pvs1Knowledge;
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        String digest = adapter.canonicalSha256(document);
        bp1 = "reviewed-rule-bundle-bp1-v9".equals(document.path("schemaVersion").asText());
        pp2 = "reviewed-rule-bundle-pp2-v8".equals(document.path("schemaVersion").asText());
        bp3 = "reviewed-rule-bundle-bp3-v7".equals(document.path("schemaVersion").asText());
        pm4 = "reviewed-rule-bundle-pm4-v2".equals(document.path("schemaVersion").asText());
        ps1 = "reviewed-rule-bundle-ps1-v3".equals(document.path("schemaVersion").asText());
        pm5 = "reviewed-rule-bundle-pm5-v4".equals(document.path("schemaVersion").asText());
        pvs1Only = "reviewed-rule-bundle-pvs1-v6".equals(document.path("schemaVersion").asText());
        if (pvs1Materials != null && !pvs1Only) throw new IllegalArgumentException("PVS1_SINGLE_RULE_MATERIAL_SUPPLY_REQUIRED");
        if (pvs1Knowledge != null && (!pvs1Only || pvs1Materials == null)) throw new IllegalArgumentException("PVS1_SELECTED_KNOWLEDGE_MATERIAL_SUPPLY_REQUIRED");
        pm1 = "reviewed-rule-bundle-pm1-v5".equals(document.path("schemaVersion").asText());
        if (!digest.equals(expectedSha256)) throw new IllegalArgumentException("REVIEWED_RULE_BUNDLE_INTEGRITY_MISMATCH");
        if (!(bp1 || pp2 || bp3 || pm4 || ps1 || pm5 || pm1 || pvs1Only || "reviewed-rule-bundle-v1".equals(document.path("schemaVersion").asText()))
                || document.path("bundleId").asText().isBlank() || document.path("bundleVersion").asText().isBlank()
                || !"REVIEWED_IMPLEMENTED".equals(document.at("/review/status").asText())
                || document.at("/review/reviewId").asText().isBlank()
                || document.at("/activation/automaticTrigger").asBoolean(true)
                || document.at("/activation/classificationEnabled").asBoolean(true)
                || !(document.path("catalog") instanceof ObjectNode catalog)
                || !(document.path("policies") instanceof ArrayNode policies)
                || !(document.path("materials") instanceof ObjectNode sourceMaterials))
            throw new IllegalArgumentException("REVIEWED_RULE_BUNDLE_REVIEW_REQUIRED");
        selector = new AcmgSpecificationSelector(mapper, catalog, document.path("catalogSha256").asText(), policies);
        materials = sourceMaterials.deepCopy();
        if ((ps1 || pm5) && document.has("materialQualification")) {
            if (!(document.path("materialQualification") instanceof ObjectNode qualification))
                throw new IllegalArgumentException("MISSENSE_MATERIAL_QUALIFICATION_INVALID");
            MissenseMaterialQualification.validate(mapper, document, qualification);
            missenseQualification = qualification.deepCopy();
        } else missenseQualification = null;
        identity = binding().put("status", "CONFIGURED").put("bundleId", document.path("bundleId").asText())
                .put("bundleVersion", document.path("bundleVersion").asText()).put("bundleSha256", digest)
                .put("catalogSha256", document.path("catalogSha256").asText());
        // Preserve legacy run identities; only bundles containing the new operator advertise it.
        if (pvs1Only)
            identity.put("codingApplicabilityVersion", Pvs1CodingApplicability.VERSION)
                    .put("codingJunctionVersion", TruncatingNmdPvs1Runtime.CODING_JUNCTION_VERSION);
        if (pvs1Only) for (var policy : policies)
            if (Pvs1StartLossPolicy.SCHEMA.equals(policy.path("schemaVersion").asText())) {
                identity.putObject("startLossEvaluator").put("id", "pvs1-start-loss")
                        .put("version", Pvs1StartLossResolver.VERSION).put("materialSchema", Pvs1StartLossRuntimeMaterial.SCHEMA);
                break;
            }
        if (identity.path("algorithms").has(TruncatingNmdPvs1Runtime.EVALUATOR_ID))
            for (var policy : policies) if (Pvs1NmdEscape.SCHEMA.equals(policy.path("schemaVersion").asText())) {
                identity.withObject("algorithms").put(TruncatingNmdPvs1Runtime.EVALUATOR_ID, Pvs1NmdEscape.VERSION);
                break;
            }
        if (identity.path("algorithms").has(TruncatingNmdPvs1Runtime.EVALUATOR_ID))
            for (var policy : policies) if (Pvs1SpliceConsequence.SCHEMA.equals(policy.path("schemaVersion").asText())) {
                identity.withObject("algorithms").put(TruncatingNmdPvs1Runtime.EVALUATOR_ID, Pvs1SpliceConsequence.VERSION);
                break;
            }
        if (identity.path("algorithms").has(TruncatingNmdPvs1Runtime.EVALUATOR_ID))
            for (var policy : policies) if (TruncatingNmdPvs1Runtime.hasStrengthCap(policy)) {
                identity.withObject("algorithms").put(TruncatingNmdPvs1Runtime.EVALUATOR_ID, TruncatingNmdPvs1Runtime.STRENGTH_CAP_VERSION);
                break;
            }
        if (ps1 || pm5) identity.put("runtimeBindingSha256", runtimeBinding == null ? "UNAVAILABLE" : adapter.canonicalSha256(runtimeBinding));
        if (identity.path("algorithms").has(TruncatingNmdPvs1Runtime.EVALUATOR_ID))
            for (var policy : policies) {
                var model = materials.path("transcriptModels").path(policy.at("/transcriptModelBinding/canonicalSha256").asText());
                if (TruncatingPvs1Model.extendedTopology(model)) {
                    identity.withObject("algorithms").put(TruncatingNmdPvs1Runtime.EVALUATOR_ID, TruncatingPvs1Model.TOPOLOGY_VERSION);
                    break;
                }
            }
        // Additive capability identity separates new mechanics/cache entries without
        // rewriting the evaluator versions retained by old saved results.
        if (pvs1Only) {
            ObjectNode capabilities = mapper.createObjectNode();
            for (var policy : policies) {
                if (TruncatingNmdPolicy.complexAllelesEnabled(policy)) {
                    ObjectNode edits = capabilities.withObject("codingTranscriptEdits");
                    edits.put("method", "CODING_TRANSCRIPT_EDITS").put("version", CodingTranscriptEdits.VERSION).put("runtimeAdapterVersion", TruncatingNmdPvs1Runtime.SPLICE_MODULE_VERSION)
                            .put("maximumAlleleLengthIncludingAnchor", Math.max(edits.path("maximumAlleleLengthIncludingAnchor").asInt(),
                                    policy.at("/codingEditScope/maximumAlleleLengthIncludingAnchor").asInt()));
                    edits.withArray("policyIds").add(policy.path("policyId").asText());
                }
                if (Pvs1SpliceConsequence.SCHEMA.equals(policy.path("schemaVersion").asText())) {
                    ObjectNode splice = capabilities.withObject("spliceRuntimeAdapter");
                    splice.put("version", TruncatingNmdPvs1Runtime.SPLICE_MODULE_VERSION)
                            .put("moduleVersion", Pvs1SpliceConsequence.MODULE_VERSION).put("escapePublicProofVersion",Pvs1EscapeAdoptionProof.VERSION);
                    splice.put("compositeModuleVersion", Pvs1SpliceConsequence.COMPOSITE_MODULE_VERSION)
                            .put("compositeRuntimeVersion", TruncatingNmdPvs1Runtime.COMPOSITE_SPLICE_VERSION)
                            .put("codingConsequenceFactsVersion", CodingConsequenceFacts.VERSION);
                    splice.withArray("policyIds").add(policy.path("policyId").asText());
                }
            }
            if (!capabilities.isEmpty()) identity.set("pvs1Capabilities", capabilities);
            if (materials.has("pvs1FullRnaOriginals")) identity.withObject("pvs1Capabilities")
                    .putObject("fullRnaPreparation").put("version", FULL_RNA_PREPARATION_VERSION)
                    .put("assayOutcomeDiagnosticVersion", "1.0.0")
                    .put("rnaSupplyVersion", CodingTranscriptMaterialSupply.RNA_VERSION)
                    .put("rnaEditVersion", CodingTranscriptEdits.RNA_VERSION)
                    .put("rnaFactsVersion", CodingConsequenceFacts.RNA_VERSION)
                    .put("fullRnaSourceSchema", Pvs1SourceQualification.FULL_RNA_ORIGINAL_SCHEMA)
                    .put("materialIndexSha256", adapter.canonicalSha256(materials.path("pvs1FullRnaOriginals")))
                    .put("criterionAdoptionGranted", false);
        }
        if ((ps1 || pm5) && runtimeBinding != null && runtimeBinding.path("materialIndex").isObject()) {
            var selection = identity.putObject("referenceMaterialSelection");
            selection.put("indexRawSha256", runtimeBinding.at("/materialIndex/indexRawSha256").asText())
                    .put("selectorVersion", runtimeBinding.at("/materialIndex/version").asText())
                    .put("selectedEntryId", runtimeBinding.path("selectedEntryId").asText());
        }
        if (missenseQualification != null) identity.put("materialQualificationSha256", adapter.canonicalSha256(missenseQualification));
        if (pvs1Materials != null) identity.set("transcriptMaterialSupply", pvs1Knowledge != null && pvs1Knowledge.sourceQualified()
                ? pvs1Materials.boundIdentity() : pvs1Materials.identity());
        if (pvs1Knowledge != null) {
            identity.set("pvs1Knowledge", pvs1Knowledge.identity());
            identity.put("contextPreparation", "SELECTED_TOPN_V3_WITH_EXPLICIT_LEGACY_PROJECTION_1.0.0");
        }
    }

    public boolean selectedPvs1Enabled() { return pvs1Knowledge != null; }

    /** One live typed preparation; a saved JSON summary can never recreate this object or its source token. */
    public static final class FullRnaPrepared {
        private final ObjectNode audit;
        @Nullable private final Pvs1SourceQualification.SourceBoundFullRna source;
        private final String contextSha256, executionSourceSha256, selectedProjectionSha256;
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DISABLED)
        private FullRnaPrepared(ObjectNode audit, @Nullable Pvs1SourceQualification.SourceBoundFullRna source,
                String contextSha256, String executionSourceSha256, String selectedProjectionSha256) {
            this.audit = audit.deepCopy(); this.source = source; this.contextSha256 = contextSha256;
            this.executionSourceSha256 = executionSourceSha256; this.selectedProjectionSha256 = selectedProjectionSha256;
        }
        public boolean configured() { return !"NOT_CONFIGURED".equals(audit.path("status").asText()); }
        public boolean targetsFullRna() { return configured() && !"NOT_FOUND".equals(audit.path("status").asText()); }
        public boolean ready() { return source != null; }
        public ObjectNode snapshot() { return audit.deepCopy(); }
        /** Private save/export material; never feed this JSON back into live qualification. */
        public ObjectNode privateSnapshot(ObjectMapper mapper) {
            var out = mapper.createObjectNode().put("schemaVersion", "full-rna-selected-private-material-v1")
                    .put("methodVersion", FULL_RNA_PREPARATION_VERSION).put("restoresLiveQualification", false)
                    .put("rawOriginalBytesPreserved", source != null);
            out.set("preparation", audit.deepCopy());var token = source;
            if (token != null) {
                out.put("originalSha256", token.originalSha256()).put("originalSerialization", "EXACT_UTF8_ORIGINAL_BYTES")
                        .put("originalUtf8", token.originalUtf8());
                out.set("sourceOriginal", token.original());out.set("provenance", token.provenance());
                out.set("referenceMaterial", token.result().reference().snapshot());
                out.set("rnaEditFacts", token.result().snapshot(mapper));out.set("consequenceFacts", token.facts().snapshot());
                out.set("topology", token.topology().snapshot());
            }
            return out;
        }
        @Nullable Pvs1SourceQualification.SourceBoundFullRna source() { return source; }
        FullRnaPrepared withoutQualification(String reason) {
            var failure = audit.deepCopy().put("status", "UNAVAILABLE").put("reasonCode", reason);
            failure.remove("sourceReplay");
            return new FullRnaPrepared(failure, null, contextSha256, executionSourceSha256, selectedProjectionSha256);
        }
        FullRnaPrepared withConfigurationReason(String reason) {
            var copy = audit.deepCopy();
            if (reason.matches("[A-Z][A-Z0-9_]{1,120}")) copy.put("configurationReasonCode", reason);
            return new FullRnaPrepared(copy, source, contextSha256, executionSourceSha256, selectedProjectionSha256);
        }
        FullRnaPrepared bindSelected(String sourceSha, String projectionSha) {
            var bound = audit.deepCopy().put("executionSourceSha256", sourceSha).put("selectedProjectionSha256", projectionSha);
            return new FullRnaPrepared(bound, source, contextSha256, sourceSha, projectionSha);
        }
        boolean matchesSelected(ObjectMapper mapper, JsonNode context, String sourceSha, String projectionSha) {
            return contextSha256.equals(new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(context))
                    && executionSourceSha256.equals(sourceSha) && selectedProjectionSha256.equals(projectionSha);
        }
        public ObjectNode publicSummary(ObjectMapper mapper) {
            var out = mapper.createObjectNode().put("schemaVersion", "full-rna-selected-source-summary-v1")
                    .put("methodVersion", FULL_RNA_PREPARATION_VERSION).put("layer", "NEUTRAL_SOURCE_PREPARATION")
                    .put("criterionConsumerStatus", "NOT_RUN_BY_NEUTRAL_PREPARATION")
                    .put("criterionAdoptionGranted", false).put("functionalQualificationGranted", false).put("phaseQualificationGranted", false);
            for (String key : List.of("status", "reasonCode", "configurationReasonCode", "entryId", "contextSha256", "executionSourceSha256", "selectedProjectionSha256"))
                if (audit.path(key).isValueNode()) out.set(key, audit.path(key).deepCopy());
            var material = audit.path("sourceReplay");
            var pins = out.putObject("binding");
            for (String key : List.of("originalSha256", "contextSha256", "modelSha256", "referenceRnaSha256", "alteredRnaSha256",
                    "mechanicalEffectSha256", "factsSha256", "resultSha256", "topologySha256", "eligibilityStage"))
                if (material.path(key).isValueNode()) pins.set(key, material.path(key).deepCopy());
            if (material.path("sourceRecord").isObject()) {
                var original = out.putObject("sourceRecord");
                for (String key : List.of("sourceId", "version", "url", "sha256"))
                    if (material.path("sourceRecord").path(key).isValueNode()) original.set(key, material.path("sourceRecord").path(key).deepCopy());
            }
            if (material.path("assayConditions").isObject()) {
                var conditions = out.putObject("assayConditions");
                for (String key : List.of("evidenceKind", "alleleAttribution", "assayQuality", "tissueRelevance", "residualNormalTranscript", "outcomeMultiplicity"))
                    if (material.path("assayConditions").path(key).isValueNode()) conditions.set(key, material.path("assayConditions").path(key).deepCopy());
            }
            var token = source;
            if (token != null) {
                var facts = token.facts().snapshot(); var summary = out.putObject("sequenceEffect");
                for (String key : List.of("coordinateSystem", "referenceRnaLength", "alteredRnaLength", "referenceProteinLength", "translationStatus",
                        "alteredProteinLength", "netProteinLengthChange", "proteinLengthOutcome"))
                    if (facts.path(key).isValueNode()) summary.set(key, facts.path(key).deepCopy());
                for (String key : List.of("referenceCdsLength", "editedReferenceCdsIntervalLength"))
                    if (facts.path("codingEffects").path(key).isIntegralNumber()) summary.set(key, facts.path("codingEffects").path(key).deepCopy());
                for (String name : List.of("nativeStart", "nativeStop", "firstStop")) if (facts.path(name).isObject()) {
                    var codon = summary.putObject(name);
                    for (String key : List.of("status", "retainedContiguously", "referenceStartRna1", "referenceEndRna1", "alteredStartRna1",
                            "alteredEndRna1", "inReadingFrameFromRetainedNativeStart", "kind", "baseOrigin"))
                        if (facts.path(name).path(key).isValueNode()) codon.set(key, facts.path(name).path(key).deepCopy());
                }
                var topology = token.topology().snapshot(); var summaryTopology = out.putObject("topology");
                for (String key : List.of("coordinateSystem", "topologyCompleteness", "sourceQualificationStatus", "singleExon", "lastJunctionRnaPosition0"))
                    if (topology.path(key).isValueNode()) summaryTopology.set(key, topology.path(key).deepCopy());
            }
            return out;
        }
    }

    public boolean hasFullRnaSources() { return materials.has("pvs1FullRnaOriginals"); }
    static FullRnaPrepared unavailableFullRna(ObjectMapper mapper, JsonNode context, String reason, String sourceSha) {
        String contextSha = new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(context);
        var audit = mapper.createObjectNode().put("schemaVersion", "full-rna-selected-preparation-v1")
                .put("methodVersion", FULL_RNA_PREPARATION_VERSION).put("status", "UNAVAILABLE").put("reasonCode", reason)
                .put("contextSha256", contextSha).put("criterionAdoptionGranted", false).put("functionalQualificationGranted", false).put("phaseQualificationGranted", false);
        return new FullRnaPrepared(audit, null, contextSha, "", "").bindSelected(sourceSha, "");
    }

    /** Transport identity only. Reuses the same bounded controlled-path reader as live replay. */
    public ObjectNode fullRnaObservedSourceIdentity() {
        var out = mapper.createObjectNode().put("schemaVersion", "full-rna-observed-source-identity-v1")
                .put("methodVersion", FULL_RNA_PREPARATION_VERSION).put("scientificAuthorityGranted", false);
        var entries = materials.path("pvs1FullRnaOriginals");
        out.put("indexSha256", adapter.canonicalSha256(entries)); var rows = out.putArray("sources");
        if (!entries.isArray() || entries.size() > 1024) return out.put("status", "UNAVAILABLE").put("reasonCode", "FULL_RNA_SOURCE_INDEX_INVALID");
        long bytes = 0;
        for (var entry : entries) {
            String entryId = entry.path("entryId").asText();
            var row = rows.addObject().put("entryId", entryId.length() <= 256 ? entryId : "INVALID_ENTRY_ID");
            row.put("bindingSha256", adapter.canonicalSha256(entry.path("binding")));
            try {
                var root = controlledMaterialRoot;
                if (root == null) throw new IllegalArgumentException("FULL_RNA_SOURCE_CONTROLLED_BUNDLE_ROOT_REQUIRED");
                if (!entry.at("/binding/relativePath").isTextual()) throw new IllegalArgumentException("FULL_RNA_SOURCE_CONFIGURATION_INVALID");
                if (bytes >= 32_000_000) throw new IllegalArgumentException("FULL_RNA_SOURCE_IDENTITY_BYTE_BUDGET_EXCEEDED");
                byte[] raw = Pvs1SourceQualification.readFullRnaOriginal(root, entry.at("/binding/relativePath").asText());
                bytes += raw.length;
                row.put("status", "READ").put("observedSha256", CodingTranscriptMaterialSupply.sha(raw)).put("bytes", raw.length);
            } catch (Exception invalid) { row.put("status", "UNAVAILABLE").put("reasonCode",
                    "FULL_RNA_SOURCE_IDENTITY_BYTE_BUDGET_EXCEEDED".equals(invalid.getMessage()) ? invalid.getMessage() : "FULL_RNA_SOURCE_IDENTITY_READ_UNAVAILABLE"); }
        }
        return out.put("status", "OBSERVED");
    }

    /** Selection only; no reference reads, criterion applicability or policy adoption. */
    public ObjectNode fullRnaSourceSelection(JsonNode context) {
        var out = mapper.createObjectNode().put("status", "NOT_CONFIGURED").put("reasonCode", "FULL_RNA_SOURCE_NOT_CONFIGURED");
        var entries = materials.path("pvs1FullRnaOriginals");
        if (entries.isMissingNode()) return out;
        out.put("status", "UNAVAILABLE");
        try {
            if (!entries.isArray() || entries.size() > 1024) throw new IllegalArgumentException("FULL_RNA_SOURCE_INDEX_INVALID");
            JsonNode match = null; int matches = 0; var ids = new java.util.HashSet<String>();
            String transcript = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
            for (var entry : entries) {
                if (!entry.isObject() || !entry.path("entryId").isTextual() || entry.path("entryId").asText().isBlank()
                        || entry.path("entryId").asText().length() > 256 || !ids.add(entry.path("entryId").asText())
                        || !entry.path("allele").isObject() || !entry.path("transcript").isTextual() || !entry.path("binding").isObject())
                    throw new IllegalArgumentException("FULL_RNA_SOURCE_INDEX_INVALID");
                if (entry.path("allele").equals(context.at("/variant/evidenceQueryAllele")) && transcript.equals(entry.path("transcript").asText())) {
                    matches++; match = entry;
                }
            }
            if (matches > 1) throw new IllegalArgumentException("FULL_RNA_SOURCE_INDEX_MULTIPLE_MATCHES");
            if (match == null) return out.put("status", "NOT_FOUND").put("reasonCode", "FULL_RNA_SOURCE_EXACT_ALLELE_TRANSCRIPT_NOT_FOUND");
            out.put("status", "SELECTED").put("reasonCode", "FULL_RNA_EXACT_SOURCE_SELECTED").put("entryId", match.path("entryId").asText());
            out.set("entry", match.deepCopy()); return out;
        } catch (IllegalArgumentException invalid) { return out.put("reasonCode", invalid.getMessage()); }
    }

    /** Shared neutral entry. Coding projection permits reconstruction; each criterion still checks its own annotation policy. */
    public FullRnaPrepared prepareFullRna(SelectedEvidencePreparation selected, String candidateId, String hypothesisId, String sourceSha) {
        var context = selected.context(candidateId, hypothesisId); var route = fullRnaSourceSelection(context);
        if (!"SELECTED".equals(route.path("status").asText())) {
            route.put("schemaVersion", "full-rna-selected-preparation-v1").put("methodVersion", FULL_RNA_PREPARATION_VERSION)
                    .put("contextSha256", adapter.canonicalSha256(context)).put("criterionAdoptionGranted", false)
                    .put("functionalQualificationGranted", false).put("phaseQualificationGranted", false);
            return new FullRnaPrepared(route, null, adapter.canonicalSha256(context), "", "").bindSelected(sourceSha, "");
        }
        try {
            var projection = selected.codingSequenceProjection(candidateId, hypothesisId);
            return prepareFullRna(projection.context(), projection.queryCandidate()).bindSelected(sourceSha, projection.bindingSha256());
        } catch (RuntimeException invalid) {
            route.remove("entry"); route.put("status", "UNAVAILABLE").put("reasonCode", "FULL_RNA_SELECTED_SEQUENCE_PREPARATION_UNAVAILABLE")
                    .put("schemaVersion", "full-rna-selected-preparation-v1").put("methodVersion", FULL_RNA_PREPARATION_VERSION);
            return new FullRnaPrepared(route, null, adapter.canonicalSha256(context), "", "").bindSelected(sourceSha, "");
        }
    }

    /** Optional bindings live inside the already byte/canonical-pinned server bundle, never a case submission. */
    public FullRnaPrepared prepareFullRna(ObjectNode context, @Nullable VariantInterpretation candidate) {
        String contextSha = adapter.canonicalSha256(context);
        var audit = mapper.createObjectNode().put("schemaVersion", "full-rna-selected-preparation-v1")
                .put("methodVersion", FULL_RNA_PREPARATION_VERSION).put("status", "NOT_CONFIGURED")
                .put("reasonCode", "FULL_RNA_SOURCE_NOT_CONFIGURED").put("contextSha256", contextSha)
                .put("criterionAdoptionGranted", false).put("functionalQualificationGranted", false).put("phaseQualificationGranted", false);
        var route = fullRnaSourceSelection(context); audit.setAll(route);
        var match = audit.remove("entry");
        if (!"SELECTED".equals(route.path("status").asText())) return new FullRnaPrepared(audit, null, contextSha, "", "");
        audit.put("status", "UNAVAILABLE");
        try {
            if (match == null) throw new IllegalArgumentException("FULL_RNA_SOURCE_INDEX_INVALID");
            audit.set("expectedSourceRecord", match.at("/binding/sourceRecord").deepCopy());
            var supply = pvs1Materials; var root = controlledMaterialRoot;
            if (root == null) throw new IllegalArgumentException("FULL_RNA_SOURCE_CONTROLLED_BUNDLE_ROOT_REQUIRED");
            if (supply == null || candidate == null) throw new IllegalArgumentException("FULL_RNA_REFERENCE_SUPPLY_UNAVAILABLE");
            var material = supply.resolveRna(context, candidate); audit.set("referenceMaterial", material.snapshot());
            var reference = material.verifiedRna();
            if (!material.ready() || reference == null) throw new IllegalArgumentException(material.snapshot().path("reasonCode").asText("FULL_RNA_REFERENCE_UNAVAILABLE"));
            var replay = Pvs1SourceQualification.resolveFullRna(mapper, root, match.path("binding"), context, reference);
            audit.set("sourceReplay", replay.snapshot());
            audit.put("status", replay.ready() ? "READY" : "UNAVAILABLE").put("reasonCode", replay.snapshot().path("reasonCode").asText());
            return new FullRnaPrepared(audit, replay.qualified(), contextSha, "", "");
        } catch (IllegalArgumentException failure) {
            audit.put("reasonCode", failure.getMessage() == null ? "FULL_RNA_PREPARATION_UNAVAILABLE" : failure.getMessage());
            return new FullRnaPrepared(audit, null, contextSha, "", "");
        }
    }

    ObjectNode startLossMechanism(JsonNode context) {
        var catalog = pvs1Knowledge;
        return catalog == null ? mapper.createObjectNode().put("status", "UNAVAILABLE")
                .put("reasonCode", "PVS1_START_LOSS_MECHANISM_SOURCE_NOT_FOUND") : catalog.startLossMechanism(context);
    }

    @Nullable Pvs1StartLossDiseaseScope startLossDiseaseScope(ObjectNode context) {
        var catalog = pvs1Knowledge;
        return catalog == null ? null : catalog.startLossDiseaseScope(context);
    }

    @Nullable Pvs1KnowledgeCatalog.StartLossAssociation startLossAssociation(JsonNode context) {
        var catalog = pvs1Knowledge;
        return catalog == null ? null : catalog.startLossAssociation(context);
    }

    /** New single-rule loader pins the exact bytes as well as the canonical document. */
    public static ReviewedRuleBundle loadPvs1(ObjectMapper mapper, Path path, String rawSha256, String canonicalSha256) throws IOException {
        return loadSingleRule(mapper, path, rawSha256, canonicalSha256, "PVS1");
    }
    public static ReviewedRuleBundle loadPvs1(ObjectMapper mapper, Path path, String rawSha256, String canonicalSha256,
            Pvs1TranscriptMaterialSupply supply) throws IOException {
        return loadSingleRule(mapper, path, rawSha256, canonicalSha256, "PVS1", Objects.requireNonNull(supply));
    }
    public static ReviewedRuleBundle loadPvs1Selected(ObjectMapper mapper, Path path, String rawSha256, String canonicalSha256,
            Pvs1TranscriptMaterialSupply supply, Pvs1KnowledgeCatalog knowledge) throws IOException {
        return loadSingleRule(mapper, path, rawSha256, canonicalSha256, "PVS1", Objects.requireNonNull(supply), Objects.requireNonNull(knowledge));
    }
    public static ReviewedRuleBundle loadBp3(ObjectMapper mapper, Path path, String rawSha256, String canonicalSha256) throws IOException {
        return loadSingleRule(mapper, path, rawSha256, canonicalSha256, "BP3");
    }
    public static ReviewedRuleBundle loadBp1(ObjectMapper mapper, Path path, String rawSha256, String canonicalSha256) throws IOException {
        return loadSingleRule(mapper, path, rawSha256, canonicalSha256, "BP1");
    }
    public static ReviewedRuleBundle loadPp2(ObjectMapper mapper, Path path, String rawSha256, String canonicalSha256) throws IOException {
        return loadSingleRule(mapper, path, rawSha256, canonicalSha256, "PP2");
    }
    private static ReviewedRuleBundle loadSingleRule(ObjectMapper mapper, Path path, String rawSha256, String canonicalSha256, String mode) throws IOException {
        return loadSingleRule(mapper, path, rawSha256, canonicalSha256, mode, null);
    }

    private static ReviewedRuleBundle loadSingleRule(ObjectMapper mapper, Path path, String rawSha256, String canonicalSha256,
            String mode, @Nullable Pvs1TranscriptMaterialSupply supply) throws IOException {
        return loadSingleRule(mapper, path, rawSha256, canonicalSha256, mode, supply, null);
    }
    private static ReviewedRuleBundle loadSingleRule(ObjectMapper mapper, Path path, String rawSha256, String canonicalSha256,
            String mode, @Nullable Pvs1TranscriptMaterialSupply supply, @Nullable Pvs1KnowledgeCatalog knowledge) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > MAXIMUM_BYTES)
            throw new IllegalArgumentException("REVIEWED_RULE_BUNDLE_SIZE_INVALID");
        byte[] bytes;
        try (var stream = Files.newInputStream(path)) { bytes = stream.readNBytes(MAXIMUM_BYTES + 1); }
        if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("REVIEWED_RULE_BUNDLE_SIZE_INVALID");
        String actual;
        try { actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        if (!actual.equals(rawSha256)) throw new IllegalArgumentException(mode + "_BUNDLE_RAW_SHA_MISMATCH");
        ObjectNode document;
        try (var parser = mapper.getFactory().createParser(bytes)) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode value = mapper.readTree(parser);
            if (!(value instanceof ObjectNode object) || parser.nextToken() != null)
                throw new IllegalArgumentException("REVIEWED_RULE_BUNDLE_NOT_OBJECT");
            document = object;
        }
        var bundle = new ReviewedRuleBundle(mapper, document, canonicalSha256, null, null, supply, knowledge);
        if (!("BP1".equals(mode) ? bundle.supportsBp1() : "PP2".equals(mode) ? bundle.supportsPp2() : "BP3".equals(mode) ? bundle.supportsBp3() : bundle.supportsPvs1Only())) throw new IllegalArgumentException(mode + "_TRUSTED_BUNDLE_REQUIRED");
        bundle.identity.put("bundleRawSha256", actual);
        bundle.controlledMaterialRoot = path.toAbsolutePath().normalize().toRealPath().getParent();
        return bundle;
    }

    private ObjectNode binding() {
        if (bp1) {
            ObjectNode value = mapper.createObjectNode().put("schemaVersion", "reviewed-rule-bundle-binding-bp1-v9")
                    .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
            value.putArray("criterionIds").add(Bp1CriterionEvaluator.CRITERION);
            value.putObject("algorithms").put(Bp1CriterionEvaluator.ID, Bp1CriterionEvaluator.VERSION);
            return value;
        }
        if (pp2) {
            ObjectNode value = mapper.createObjectNode().put("schemaVersion", "reviewed-rule-bundle-binding-pp2-v8")
                    .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
            value.putArray("criterionIds").add(Pp2CriterionEvaluator.CRITERION);
            value.putObject("algorithms").put(Pp2CriterionEvaluator.ID, Pp2CriterionEvaluator.VERSION);
            return value;
        }
        if (bp3) {
            ObjectNode value = mapper.createObjectNode().put("schemaVersion", "reviewed-rule-bundle-binding-bp3-v7")
                    .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
            value.putArray("criterionIds").add(Bp3CriterionEvaluator.CRITERION);
            value.putObject("algorithms").put(Bp3CriterionEvaluator.ID, Bp3CriterionEvaluator.VERSION);
            return value;
        }
        if (pvs1Only) {
            ObjectNode value = mapper.createObjectNode().put("schemaVersion", "reviewed-rule-bundle-binding-pvs1-v6")
                    .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
            value.putArray("criterionIds").add(TruncatingNmdPvs1Runtime.CRITERION);
            value.putObject("algorithms").put(TruncatingNmdPvs1Runtime.EVALUATOR_ID, TruncatingNmdPvs1Runtime.EVALUATOR_VERSION);
            return value;
        }
        if (pm1) {
            ObjectNode value = mapper.createObjectNode().put("schemaVersion", "reviewed-rule-bundle-binding-pm1-v5")
                    .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
            value.putArray("criterionIds").add(Pm1CriterionEvaluator.CRITERION);
            value.putObject("algorithms").put(Pm1CriterionEvaluator.ID, Pm1CriterionEvaluator.VERSION);
            return value;
        }
        if (ps1 || pm5) {
            ObjectNode value = mapper.createObjectNode().put("schemaVersion", pm5 ? "reviewed-rule-bundle-binding-pm5-v4" : "reviewed-rule-bundle-binding-ps1-v3")
                    .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
            value.putArray("criterionIds").add(missenseCriterion());
            value.putObject("algorithms").put("MissenseComparisonRuntime", "1.0.0");
            return value;
        }
        if (pm4) {
            ObjectNode value = mapper.createObjectNode().put("schemaVersion", "reviewed-rule-bundle-binding-pm4-v2")
                    .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
            value.putArray("criterionIds").add(Pm4CriterionEvaluator.CRITERION);
            value.putObject("algorithms").put(Pm4CriterionEvaluator.ID, Pm4CriterionEvaluator.VERSION);
            return value;
        }
        ObjectNode value = mapper.createObjectNode().put("schemaVersion", "reviewed-rule-bundle-binding-v1")
                .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
        value.putArray("criterionIds").add(TruncatingNmdPvs1Runtime.CRITERION).add(FunctionalEvidenceRuntime.PS3);
        value.putObject("algorithms").put(TruncatingNmdPvs1Runtime.EVALUATOR_ID, TruncatingNmdPvs1Runtime.EVALUATOR_VERSION)
                .put(FunctionalEvidenceRuntime.EVALUATOR_ID, FunctionalEvidenceRuntime.VERSION);
        return value;
    }

    public boolean supportsBp1() { return bp1 && "CONFIGURED".equals(identity.path("status").asText()); }
    public int bp1SequenceCalls() { return bp1Sequences.get(); }
    public int bp1RuntimeCalls() { return bp1Runtimes.get(); }
    public int bp1ProjectionCalls() { return bp1Projections.get(); }
    public int bp1EvaluatorCalls() { return bp1Evaluators.get(); }
    public ObjectNode refuseBp1(String reason) { return refusal(Bp1CriterionEvaluator.CRITERION, reason); }
    public boolean supportsPp2() { return pp2 && "CONFIGURED".equals(identity.path("status").asText()); }
    public int pp2SequenceCalls() { return pp2Sequences.get(); }
    public int pp2RuntimeCalls() { return pp2Runtimes.get(); }
    public int pp2ProjectionCalls() { return pp2Projections.get(); }
    public int pp2EvaluatorCalls() { return pp2Evaluators.get(); }
    public ObjectNode refusePp2(String reason) { return refusal(Pp2CriterionEvaluator.CRITERION, reason); }
    public boolean supportsBp3() { return bp3 && "CONFIGURED".equals(identity.path("status").asText()); }
    public int bp3RuntimeCalls() { return bp3Runtimes.get(); }
    public int bp3ProjectionCalls() { return bp3Projections.get(); }
    public int bp3EvaluatorCalls() { return bp3Evaluators.get(); }
    public ObjectNode refuseBp3(String reason) { return refusal(Bp3CriterionEvaluator.CRITERION, reason); }
    public ObjectNode identity() { return identity.deepCopy(); }

    /** A positive, already qualified mechanism fact for the exact scope. No PVS1 evaluation runs. */
    @Nullable ObjectNode establishedLofForBp1(ObjectNode context) {
        var currentSelector = selector;
        var knowledge = pvs1Knowledge;
        if (currentSelector == null || knowledge == null || !knowledge.sourceQualified()
                || !"PUBLIC_REFERENCE".equals(knowledge.identity().path("sourceDataClass").asText())
                || !adapter.canonicalContextId(context).equals(context.path("contextId").asText())) return null;
        var policy = currentSelector.select(context, TruncatingNmdPvs1Runtime.CRITERION).selectedPolicy();
        if (policy == null) return null;
        var assessment = knowledge.assess(context, policy);
        if (!"READY".equals(assessment.path("status").asText())) return null;
        var adopted = assessment.path("adoption");
        var fact = mapper.createObjectNode().put("methodVersion", GeneralBp1Runtime.STRUCTURED_LOF_REUSE_VERSION)
                .put("status", "VERIFIED_ESTABLISHED_LOF")
                .put("contextId", context.path("contextId").asText())
                .put("contextSha256", adapter.canonicalSha256(context))
                .put("geneSymbol", context.at("/gene/symbol").asText())
                .put("diseaseLabel", context.at("/disease/label").asText())
                .put("entryId", adopted.path("entryId").asText())
                .put("modelSha256", adopted.path("modelSha256").asText())
                .put("policySha256", adopted.path("policySha256").asText())
                .put("bundleSha256", identity.path("bundleSha256").asText())
                .put("adoptionRawSha256", assessment.at("/binding/adoptionRawSha256").asText())
                .put("sourceCandidatesRawSha256", assessment.at("/binding/sourceCandidatesRawSha256").asText())
                .put("mechanismSourceSetSha256", adopted.at("/mechanismEvidence/sourceSetSha256").asText());
        fact.set("scope", adopted.path("scope").deepCopy());
        fact.set("mechanismSources", assessment.path("policyMechanismSources").deepCopy());
        return fact;
    }
    public boolean supportsPm4() { return pm4 && "CONFIGURED".equals(identity.path("status").asText()); }
    public boolean supportsPs1() { return ps1 && "CONFIGURED".equals(identity.path("status").asText()); }
    public boolean supportsPm5() { return pm5 && "CONFIGURED".equals(identity.path("status").asText()); }
    public boolean supportsPvs1Only() { return pvs1Only && "CONFIGURED".equals(identity.path("status").asText()); }
    public int pvs1RuntimeCalls() { return pvs1Runtimes.get(); }
    public int pvs1ProjectionCalls() { return pvs1Projections.get(); }
    public int pvs1EvaluatorCalls() { return pvs1Evaluators.get(); }
    public ObjectNode refusePvs1(String reason) {
        if (!supportsPvs1Only()) throw new IllegalArgumentException("PVS1_TRUSTED_BUNDLE_REQUIRED");
        return refusal(TruncatingNmdPvs1Runtime.CRITERION, reason);
    }
    public boolean supportsPm1() { return pm1 && "CONFIGURED".equals(identity.path("status").asText()); }
    public ObjectNode refusePm1(String reason) {
        if (!supportsPm1()) throw new IllegalArgumentException("PM1_TRUSTED_BUNDLE_REQUIRED");
        return refusal(Pm1CriterionEvaluator.CRITERION, reason);
    }
    public String missenseCriterion() { return pm5 ? MissenseComparisonRuntime.PM5 : MissenseComparisonRuntime.PS1; }
    public ObjectNode refuseMissense(String reason) {
        if (!supportsPs1() && !supportsPm5()) throw new IllegalArgumentException("MISSENSE_TRUSTED_BUNDLE_REQUIRED");
        return refusal(missenseCriterion(), reason);
    }
    public ObjectNode refusePs1(String reason) {
        if (!supportsPs1()) throw new IllegalArgumentException("PS1_TRUSTED_BUNDLE_REQUIRED");
        return refusal(MissenseComparisonRuntime.PS1, reason);
    }
    public int lengthCalculations() { return lengthCalculations.get(); }
    public int pm4EvaluatorCalls() { return pm4Evaluators.get(); }
    public int pm1RuntimeCalls() { return pm1Runtimes.get(); }
    public int pm1PositionCalculations() { return pm1Positions.get(); }
    public int pm1EvaluatorCalls() { return pm1Evaluators.get(); }

    /** A valid catalog may route an uncovered clause to its predecessor; failed searches never do. */
    public ObjectNode evaluate(String criterion, JsonNode context, @Nullable VariantInterpretation candidate) {
        return evaluate(criterion, context, candidate, null, false);
    }

    ObjectNode evaluateFullRna(ObjectNode context, VariantInterpretation candidate, FullRnaPrepared prepared) {
        if (!prepared.targetsFullRna()) throw new IllegalArgumentException("FULL_RNA_EXACT_ROUTE_REQUIRED");
        if (!prepared.ready()) return refusePvs1(prepared.snapshot().path("reasonCode").asText("FULL_RNA_SOURCE_UNAVAILABLE"));
        return evaluate(TruncatingNmdPvs1Runtime.CRITERION, context, candidate, prepared.source(), true);
    }

    private ObjectNode evaluate(String criterion, JsonNode context, @Nullable VariantInterpretation candidate,
            @Nullable Pvs1SourceQualification.SourceBoundFullRna fullRna, boolean useFullRna) {
        if (!(bp1 ? Bp1CriterionEvaluator.CRITERION.equals(criterion) : pp2 ? Pp2CriterionEvaluator.CRITERION.equals(criterion) : bp3 ? Bp3CriterionEvaluator.CRITERION.equals(criterion) : (ps1 || pm5) ? missenseCriterion().equals(criterion) : pm4 ? Pm4CriterionEvaluator.CRITERION.equals(criterion)
                : pm1 ? Pm1CriterionEvaluator.CRITERION.equals(criterion) : pvs1Only ? TruncatingNmdPvs1Runtime.CRITERION.equals(criterion) : CRITERIA.contains(criterion)))
            throw new IllegalArgumentException("REVIEWED_RULE_CRITERION_NOT_IMPLEMENTED");
        if (missenseQualification != null) {
            String issue = missenseUseIssue(context);
            if (issue != null) return refusal(criterion, issue);
        }
        ObjectNode result = refusal(criterion, "REVIEWED_RULE_CONFIGURATION_UNAVAILABLE");
        var currentSelector = selector; var knowledgeCatalog = pvs1Knowledge; var materialSupply = pvs1Materials;
        if (currentSelector == null) return result;
        var selected = currentSelector.select(context, criterion);
        boolean startLossCandidate = !useFullRna && Pvs1StartLossResolver.selectedStartLoss(context, candidate);
        if (!bp1 && !pp2 && !bp3 && !pm4 && !ps1 && !pm5 && !pm1 && !pvs1Only && "CLAUSE_SEARCH_SCOPE_NOT_COVERED".equals(selected.receipt().path("reasonCode").asText()))
            result.put("clauseRouting", LEGACY);
        result.set("policySelection", selected.receipt());
        ObjectNode policy = selected.selectedPolicy();
        if (pvs1Only && !useFullRna) {
            var applicability = Pvs1CodingApplicability.exclude(mapper, context, candidate, materials, policy);
            if (applicability != null) {
                var excluded = refusal(criterion, Pvs1CodingApplicability.REASON);
                excluded.set("policySelection", selected.receipt());
                excluded.set("codingApplicability", applicability);
                return excluded;
            }
        }
        if (policy == null) {
            String reason = selected.receipt().path("reasonCode").asText("REVIEWED_RULE_POLICY_SELECTION_INCOMPLETE");
            result.withObject("evaluationResult").put("reasonCode", reason);
            result.withObject("criterionApplicability").put("reasonCode", reason);
            if (bp1 || pp2 || bp3 || ps1 || pm5 || pm1) result = refusal(criterion, reason).set("policySelection", selected.receipt());
            if (knowledgeCatalog != null && !startLossCandidate) {
                var knowledge = knowledgeCatalog.assess(context, null);
                result.set("knowledgeAssessment", knowledge);
                // Retain explicit specialist exclusions. Only explain an uncovered search using available facts.
                if ("CLAUSE_SEARCH_SCOPE_NOT_COVERED".equals(reason) && knowledge.path("missingExecutableConditions").isArray()) {
                    result.withObject("evaluationResult").set("reasonCode", knowledge.path("reasonCode"));
                    result.withObject("criterionApplicability").set("reasonCode", knowledge.path("reasonCode"));
                }
            }
            return result;
        }
        ObjectNode knowledgeAudit = null;
        try {
            if (!(context instanceof ObjectNode object) || candidate == null)
                throw new IllegalArgumentException("REVIEWED_RULE_VERIFIED_CONTEXT_MISSING");
            if (knowledgeCatalog != null && !startLossCandidate) {
                knowledgeAudit = knowledgeCatalog.assess(object, policy);
                if (!"READY".equals(knowledgeAudit.path("status").asText()))
                    return pvs1SupplyRefusal(selected.receipt(), knowledgeAudit.path("reasonCode").asText(), null)
                            .set("knowledgeAssessment", knowledgeAudit);
            }
            if (bp1) {
                ObjectNode model = material(policy, "transcriptModelBinding", "transcriptModels", "BP1_MODEL");
                ObjectNode mechanism = material(policy, "mechanismMaterialBinding", "bp1Mechanisms", "BP1_MECHANISM");
                if (!(materials.path("bp1ResourceProfile") instanceof ObjectNode profile)) throw new IllegalArgumentException("BP1_RESOURCE_PROFILE_MISSING");
                bp1Runtimes.incrementAndGet();
                var runtime = new Bp1CriterionEvaluator(mapper);
                try { result = runtime.evaluate(object, candidate, policy, selected.receipt(), model, mechanism, profile); }
                finally {
                    bp1Projections.addAndGet(runtime.projectionCalls());
                    bp1Evaluators.addAndGet(runtime.evaluatorCalls());
                    bp1Sequences.addAndGet(runtime.sequenceCalls());
                }
            } else if (pp2) {
                ObjectNode model = material(policy, "transcriptModelBinding", "transcriptModels", "PP2_MODEL");
                ObjectNode spectrum = material(policy, "spectrumMaterialBinding", "pp2Spectra", "PP2_SPECTRUM");
                if (!(materials.path("pp2ResourceProfile") instanceof ObjectNode profile)) throw new IllegalArgumentException("PP2_RESOURCE_PROFILE_MISSING");
                pp2Runtimes.incrementAndGet();
                result = new Pp2CriterionEvaluator(mapper).evaluate(object, candidate, policy, selected.receipt(), model, spectrum, profile);
                pp2Projections.addAndGet(result.path("storedVepProjections").asInt());
                pp2Evaluators.addAndGet(result.path("evaluatorCalls").asInt());
                pp2Sequences.addAndGet(result.path("sequenceCalculations").asInt());
            } else if (bp3) {
                ObjectNode model = material(policy, "transcriptModelBinding", "transcriptModels", "BP3_MODEL");
                ObjectNode regions = material(policy, "regionMaterialBinding", "bp3Regions", "BP3_REGION");
                if (!(materials.path("bp3ResourceProfile") instanceof ObjectNode profile)) throw new IllegalArgumentException("BP3_RESOURCE_PROFILE_MISSING");
                bp3Runtimes.incrementAndGet();
                result = new Bp3CriterionEvaluator(mapper).evaluate(object, candidate, policy, selected.receipt(), model, regions, profile);
                bp3Projections.addAndGet(result.path("storedVepProjections").asInt());
                bp3Evaluators.addAndGet(result.path("evaluatorCalls").asInt());
                lengthCalculations.addAndGet(result.path("lengthCalculations").asInt());
            } else if (ps1 || pm5) {
                String sha = policy.path("materialsSha256").asText();
                if (!(materials.path("missenseMaterials").path(sha) instanceof ObjectNode value))
                    throw new IllegalArgumentException((pm5 ? "PM5_" : "PS1_") + "REFERENCE_MATERIALS_MISSING");
                if (!sha.equals(adapter.canonicalSha256(value))) throw new IllegalArgumentException((pm5 ? "PM5_" : "PS1_") + "REFERENCE_MATERIALS_SHA_MISMATCH");
                var runtimeFactory = missense;
                if (runtimeFactory == null) throw new IllegalArgumentException((pm5 ? "PM5_" : "PS1_") + "COMPARISON_RUNTIME_UNAVAILABLE");
                var runtime = runtimeFactory.get();
                if (runtime == null) throw new IllegalArgumentException((pm5 ? "PM5_" : "PS1_") + "COMPARISON_RUNTIME_UNAVAILABLE");
                result = (pm5 ? runtime.requiringCompleteReferenceSearch() : runtime)
                        .evaluate(object, candidate, policy, selected.receipt(), value.deepCopy());
            } else if (pm1) {
                ObjectNode model = material(policy, "transcriptModelBinding", "transcriptModels", "PM1_MODEL");
                ObjectNode regions = material(policy, "regionMaterialBinding", "pm1Regions", "PM1_REGION");
                if (!(materials.path("pm1ResourceProfile") instanceof ObjectNode profile))
                    throw new IllegalArgumentException("PM1_RESOURCE_PROFILE_MISSING");
                pm1Runtimes.incrementAndGet();
                result = new Pm1CriterionEvaluator(mapper).evaluate(object, candidate, policy, selected.receipt(), model, regions, profile);
                pm1Positions.addAndGet(result.path("positionCalculations").asInt());
                pm1Evaluators.addAndGet(result.path("evaluatorCalls").asInt());
            } else if (pm4) {
                ObjectNode model = material(policy, "transcriptModelBinding", "transcriptModels", "PM4_MODEL");
                ObjectNode regions = material(policy, "regionMaterialBinding", "pm4Regions", "PM4_REGION");
                if (!(materials.path("pm4ResourceProfile") instanceof ObjectNode profile))
                    throw new IllegalArgumentException("PM4_RESOURCE_PROFILE_MISSING");
                result = new Pm4CriterionEvaluator(mapper).evaluate(object, candidate, policy, selected.receipt(), model, regions, profile);
                lengthCalculations.addAndGet(result.path("lengthCalculations").asInt());
                pm4Evaluators.addAndGet(result.path("evaluatorCalls").asInt());
            } else if (TruncatingNmdPvs1Runtime.CRITERION.equals(criterion)) {
                String modelSha = policy.at("/transcriptModelBinding/canonicalSha256").asText();
                ObjectNode suppliedAudit = null;
                JsonNode modelValue = materials.path("transcriptModels").path(modelSha);
                if (useFullRna && fullRna != null) modelValue = fullRna.result().reference().material().model();
                if (modelValue.isMissingNode() && materials.path("transcriptModels").isObject() && materialSupply != null) {
                    adapter.validateCompleteContext(object);
                    String confirmation = object.at("/transcript/selectionPolicy").asText();
                    String gate = org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.populationOnlySelection(confirmation)
                            || "PM2_CONTEXT_ONLY".equals(confirmation) ? "PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED"
                            : !"REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText()) ? "PVS1_REVIEWED_POLICY_REQUIRED"
                            : !"ESTABLISHED_LOSS_OF_FUNCTION".equals(policy.at("/mechanism/status").asText()) ? "PVS1_LOF_MECHANISM_NOT_ESTABLISHED" : null;
                    if (gate != null) return withKnowledge(pvs1SupplyRefusal(selected.receipt(), gate, null), knowledgeAudit);
                    var supplied = materialSupply.resolve(object, candidate);
                    suppliedAudit = supplied.audit();
                    if (!supplied.ready()) return withKnowledge(pvs1SupplyRefusal(selected.receipt(), suppliedAudit.path("reasonCode").asText(), suppliedAudit), knowledgeAudit);
                    modelValue = supplied.model();
                    if (!(modelValue instanceof ObjectNode) || !modelSha.equals(adapter.canonicalSha256(modelValue)))
                        return withKnowledge(pvs1SupplyRefusal(selected.receipt(), "PVS1_SUPPLIED_MODEL_NOT_BOUND_TO_REVIEWED_POLICY", suppliedAudit), knowledgeAudit);
                }
                if (!(modelValue instanceof ObjectNode model) || !modelSha.equals(adapter.canonicalSha256(model))
                        || !(materials.path("pvs1ResourceProfile") instanceof ObjectNode profile))
                    throw new IllegalArgumentException("REVIEWED_PVS1_MATERIAL_IDENTITY_UNAVAILABLE");
                if (!useFullRna && knowledgeCatalog != null && knowledgeCatalog.sourceQualified() && materialSupply != null && !startLossCandidate) {
                    // Source qualification is checked above. Current sequence/structure supplies facts;
                    // it cannot replace the exact disease policy, loosen its bounds or grant approval.
                    adapter.validateCompleteContext(object);
                    String confirmation = object.at("/transcript/selectionPolicy").asText();
                    String gate = org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.populationOnlySelection(confirmation)
                            || "PM2_CONTEXT_ONLY".equals(confirmation) ? "PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED"
                            : TruncatingNmdPolicy.issue(object, policy, selected.receipt(), model, adapter);
                    if (gate != null) return withKnowledge(pvs1SupplyRefusal(selected.receipt(), gate, null), knowledgeAudit);
                    var supplied = materialSupply.resolveBound(object, candidate, model);
                    suppliedAudit = supplied.audit();
                    if (!supplied.ready()) return withKnowledge(pvs1SupplyRefusal(selected.receipt(),
                            suppliedAudit.path("reasonCode").asText(), suppliedAudit), knowledgeAudit);
                }
                JsonNode startLossMaterial = mapper.missingNode();
                if (startLossCandidate) {
                    String startLossSha = Pvs1StartLossRuntimeMaterial.materialSha256(adapter, policy, object);
                    if (startLossSha.matches("[0-9a-f]{64}")) {
                        JsonNode suppliedStartLoss = materials.path("pvs1StartLossMaterials").path(startLossSha);
                        // The runtime verifies this binding on every entry path; preserve corrupt
                        // input for an explicit mismatch instead of silently reporting it missing.
                        if (suppliedStartLoss instanceof ObjectNode)
                            startLossMaterial = suppliedStartLoss;
                    }
                }
                if (!useFullRna && knowledgeCatalog != null && knowledgeCatalog.sourceQualified() && !startLossCandidate
                        && Pvs1SpliceConsequence.SCHEMA.equals(policy.path("schemaVersion").asText())) {
                    JsonNode selectedOutcome;
                    try {
                        selectedOutcome = Pvs1SpliceConsequence.select(materials.path("pvs1SpliceOutcomes"), object, model, adapter);
                    } catch (IllegalArgumentException invalid) {
                        String reason = invalid.getMessage();
                        return withKnowledge(pvs1SupplyRefusal(selected.receipt(), reason == null
                                ? "PVS1_SPLICE_MATERIAL_INVALID" : reason, suppliedAudit), knowledgeAudit);
                    }
                    // A v4 policy may also cover ordinary truncating alleles with
                    // no splice observation. The Runtime decides from the actual
                    // annotation whether a missing outcome is required.
                    if (!selectedOutcome.isMissingNode() && !selectedOutcome.isNull()) {
                        String qualificationIssue = knowledgeAudit == null ? "PVS1_SOURCE_QUALIFICATION_SPLICE_MATERIAL_REQUIRED"
                                : Pvs1SourceQualification.spliceMaterialIssue(knowledgeAudit, object, policy, model, selectedOutcome, adapter);
                        if (qualificationIssue != null && !Pvs1SourceQualification.unresolvedSplice(qualificationIssue))
                            return withKnowledge(pvs1SupplyRefusal(selected.receipt(), qualificationIssue, suppliedAudit), knowledgeAudit);
                        if (materialSupply == null || suppliedAudit == null)
                            return withKnowledge(pvs1SupplyRefusal(selected.receipt(),
                                    qualificationIssue == null ? "PVS1_SPLICE_CURRENT_REFERENCE_WINDOW_UNAVAILABLE" : qualificationIssue,
                                    suppliedAudit), knowledgeAudit);
                        String referenceIssue = materialSupply.spliceReferenceIssue(object, selectedOutcome.path("referenceWindow"), suppliedAudit);
                        if (qualificationIssue != null) {
                            var refused = pvs1SupplyRefusal(selected.receipt(), qualificationIssue, suppliedAudit);
                            if (referenceIssue == null) {
                                var assessment = Pvs1UnresolvedAssessment.splice(
                                        mapper, adapter, object, candidate, model, selectedOutcome, qualificationIssue);
                                if (assessment != null) {
                                    refused.set("spliceConsequenceMaterial", selectedOutcome.deepCopy());
                                    refused.set("unresolvedAssessment", assessment);
                                }
                            }
                            return withKnowledge(refused, knowledgeAudit);
                        }
                        if (referenceIssue != null)
                            return withKnowledge(pvs1SupplyRefusal(selected.receipt(), referenceIssue, suppliedAudit), knowledgeAudit);
                    }
                }
                var runtime = new TruncatingNmdPvs1Runtime(mapper, profile, model,
                        pvs1Only && TruncatingNmdPolicy.complexAllelesEnabled(policy));
                pvs1Runtimes.incrementAndGet();
                try { result = useFullRna ? runtime.evaluateFullRna(object, candidate, policy, selected.receipt(), fullRna)
                        : runtime.evaluate(object, candidate, policy, selected.receipt(), materials.path("pvs1SpliceOutcomes"), startLossMaterial); }
                finally {
                    pvs1Projections.addAndGet(runtime.storedVepProjections());
                    pvs1Evaluators.addAndGet(runtime.evaluatorCalls());
                }
                if (suppliedAudit != null) result.set("transcriptMaterialSupply", suppliedAudit);
                if (!useFullRna) Pvs1UnresolvedAssessment.recordEscape(mapper, model, materials.path("pvs1EscapeReviewGaps"), result);
            } else {
                String factSha = policy.at("/factBundle/canonicalSha256").asText();
                JsonNode facts = materials.path("functionalFacts").path(factSha);
                if (!facts.isObject() || !factSha.equals(adapter.canonicalSha256(facts)))
                    throw new IllegalArgumentException("REVIEWED_FUNCTIONAL_MATERIAL_IDENTITY_UNAVAILABLE");
                result = new FunctionalEvidenceRuntime(mapper).evaluate(object, candidate, policy, selected.receipt(), facts);
            }
            result.put("clauseRouting", CURRENT).put("adoptionSupported", false)
                    .put("adoptionReasonCode", "NEW_POLICY_ADOPTION_NOT_INTEGRATED");
            result.set("bundleIdentity", identity());
            return withKnowledge(result, knowledgeAudit);
        } catch (IllegalArgumentException invalid) {
            String reason = invalid.getMessage();
            if (reason == null || !List.of("REVIEWED_RULE_VERIFIED_CONTEXT_MISSING",
                    "REVIEWED_PVS1_MATERIAL_IDENTITY_UNAVAILABLE",
                    "REVIEWED_FUNCTIONAL_MATERIAL_IDENTITY_UNAVAILABLE", "PM4_MODEL_MISSING", "PM4_MODEL_SHA_MISMATCH",
                    "PM4_REGION_MISSING", "PM4_REGION_SHA_MISMATCH", "PM4_RESOURCE_PROFILE_MISSING",
                    "PS1_REFERENCE_MATERIALS_MISSING", "PS1_REFERENCE_MATERIALS_SHA_MISMATCH", "PS1_COMPARISON_RUNTIME_UNAVAILABLE",
                    "PM5_REFERENCE_MATERIALS_MISSING", "PM5_REFERENCE_MATERIALS_SHA_MISMATCH", "PM5_COMPARISON_RUNTIME_UNAVAILABLE",
                    "PM1_MODEL_MISSING", "PM1_MODEL_SHA_MISMATCH", "PM1_REGION_MISSING", "PM1_REGION_SHA_MISMATCH",
                    "BP1_MODEL_MISSING", "BP1_MODEL_SHA_MISMATCH", "BP1_MECHANISM_MISSING", "BP1_MECHANISM_SHA_MISMATCH", "BP1_RESOURCE_PROFILE_MISSING",
                    "PP2_MODEL_MISSING", "PP2_MODEL_SHA_MISMATCH", "PP2_SPECTRUM_MISSING", "PP2_SPECTRUM_SHA_MISMATCH", "PP2_RESOURCE_PROFILE_MISSING", "PM1_RESOURCE_PROFILE_MISSING", "BP3_MODEL_MISSING", "BP3_MODEL_SHA_MISMATCH", "BP3_REGION_MISSING", "BP3_REGION_SHA_MISMATCH", "BP3_RESOURCE_PROFILE_MISSING").contains(reason))
                reason = "REVIEWED_RULE_MATERIAL_OR_INPUT_INVALID";
            result = refusal(criterion, reason);
            result.set("policySelection", selected.receipt());
            return withKnowledge(result, knowledgeAudit);
        }
    }

    private static ObjectNode withKnowledge(ObjectNode result, @Nullable ObjectNode audit) {
        if (audit != null) result.set("knowledgeAssessment", audit.deepCopy());
        return result;
    }

    private ObjectNode pvs1SupplyRefusal(ObjectNode selection, String reason, @Nullable ObjectNode audit) {
        var result = refusal(TruncatingNmdPvs1Runtime.CRITERION, reason);
        result.set("policySelection", selection.deepCopy());
        result.set("bundleIdentity", identity());
        if (audit != null) result.set("transcriptMaterialSupply", audit.deepCopy());
        return result;
    }

    /** Technical match of the retained external scope, not a new scientific approval. */
    @Nullable public String missenseUseIssue(JsonNode context) {
        return MissenseMaterialQualification.issue(missenseQualification, context);
    }

    private ObjectNode material(JsonNode policy, String binding, String collection, String reason) {
        String sha = policy.path(binding).path("canonicalSha256").asText();
        if (!(materials.path(collection).path(sha) instanceof ObjectNode value)) throw new IllegalArgumentException(reason + "_MISSING");
        if (!sha.equals(adapter.canonicalSha256(value))) throw new IllegalArgumentException(reason + "_SHA_MISMATCH");
        return value.deepCopy();
    }

    private ObjectNode refusal(String criterion, String reason) {
        ObjectNode result = mapper.createObjectNode().put("criterionId", criterion).put("clauseRouting", CURRENT)
                .put("sourceOfTruth", "ACMG_EVIDENCE_V2").put("providerCalls", 0).put("evaluatorCalls", 0)
                .put("classificationEnabled", false).put("adoptionSupported", false)
                .put("adoptionReasonCode", "NEW_POLICY_ADOPTION_NOT_INTEGRATED");
        result.set("bundleIdentity", identity());
        if (pm4 || bp3) result.put("lengthCalculations", 0).put("inputDisposition",
                reason.contains("MISSING") || reason.contains("UNAVAILABLE") ? "MISSING_INPUT" : "INVALID_INPUT");
        result.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", reason);
        result.putObject("criterionApplicability").put("status", "UNKNOWN").put("reasonCode", reason);
        if (pm1) result.put("positionCalculations", 0).put("storedVepProjections", 0);
        if (bp1 || pp2 || bp3 || ps1 || pm5 || pm1) {
            ObjectNode evaluation = result.putArray("criterionEvaluations").addObject().put("criterionId", criterion);
            evaluation.set("evaluationResult", result.path("evaluationResult").deepCopy());
            evaluation.set("criterionApplicability", result.path("criterionApplicability").deepCopy());
        }
        return result;
    }

    public static boolean usesPreexistingClause(JsonNode result) {
        return LEGACY.equals(result.path("clauseRouting").asText());
    }
}
