package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.utils.CoordinateUtils;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.ExomiserCandidateFeatures;
import org.nbscn.varvis.variantanalysis.domain.NormalizationAlleleMapping;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Plans complete evidence-v2 contexts for an already ordered Top-N candidate list.
 *
 * <p>This opt-in bridge does not rank, call providers, evaluate criteria or join the patient
 * workflow. Context seeds contain the fields that the current candidate model cannot establish
 * reliably; generated identifiers use the existing runtime-v2 canonicalization implementation.</p>
 */
public final class TopNAcmgContextPlanner {

    public static final String CONTEXT_NOT_READY = "CONTEXT_NOT_READY";

    private static final List<String> REQUIRED_SEED_POINTERS = List.of(
            "/dataClass",
            "/scope",
            "/scope/analysisDomain",
            "/scope/diseaseModel",
            "/scope/variantScope",
            "/variant",
            "/variant/variantType",
            "/variant/inputAllele",
            "/variant/normalizedAllele",
            "/variant/evidenceQueryAllele",
            "/variant/normalization/status",
            "/variant/normalization/method",
            "/variant/normalization/methodVersion",
            "/variant/normalization/referenceSequenceSha256",
            "/variant/normalization/refValidation",
            "/variant/assemblyLineage",
            "/variant/assemblyLineage/mappingStatus",
            "/gene/hgncId",
            "/gene/symbol",
            "/disease/mondoId",
            "/disease/label",
            "/moi",
            "/transcript/accession",
            "/transcript/version",
            "/transcript/selectionPolicy",
            "/transcript/annotationSourceVersion",
            "/patientContext/patientContextId",
            "/patientContext/role",
            "/patientContext/affectedStatus",
            "/patientContext/sex"
    );

    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter contextContract;
    private final boolean convertedBa1;

    public TopNAcmgContextPlanner(ObjectMapper mapper) {
        this(mapper, false);
    }

    // Only the assembler with verified converted source receipts uses this mode.
    TopNAcmgContextPlanner(ObjectMapper mapper, boolean convertedBa1) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.contextContract = new RuntimeEvidenceV2Adapter(mapper);
        this.convertedBa1 = convertedBa1;
    }

    /**
     * Selects exactly the first {@code min(requestedN, rankedCandidates.size())} rows and plans
     * every supplied seed for those rows. A not-ready row is never replaced by a later row.
     */
    public PlanResult plan(AnalysisContext analysisContext,
                           List<VariantInterpretation> rankedCandidates,
                           int requestedN,
                           List<ContextSeed> seeds) {
        Objects.requireNonNull(analysisContext, "analysisContext");
        Objects.requireNonNull(rankedCandidates, "rankedCandidates");
        if (requestedN <= 0) {
            throw new IllegalArgumentException("TOP_N_MUST_BE_POSITIVE");
        }

        Map<String, List<IndexedSeed>> indexedSeeds = indexSeeds(seeds);
        int selectedCount = Math.min(requestedN, rankedCandidates.size());
        List<VariantInterpretation> selectedCandidates = new ArrayList<VariantInterpretation>(selectedCount);
        List<String> candidateIds = new ArrayList<String>(selectedCount);
        Map<String, Integer> candidateIdCounts = new LinkedHashMap<String, Integer>();

        for (int index = 0; index < selectedCount; index++) {
            VariantInterpretation candidate = Objects.requireNonNull(
                    rankedCandidates.get(index), "rankedCandidates contains null"
            );
            String candidateId = candidate.getCandidateId();
            selectedCandidates.add(candidate);
            candidateIds.add(candidateId);
            candidateIdCounts.merge(candidateId, 1, (left, right) -> left + right);
        }

        List<CandidatePlan> candidatePlans = new ArrayList<CandidatePlan>(selectedCount);
        for (VariantInterpretation candidate : selectedCandidates) {
            if (candidateIdCounts.get(candidate.getCandidateId()) > 1) {
                candidatePlans.add(new CandidatePlan(
                        candidate.getCandidateId(),
                        List.of(),
                        List.of(blocker("CANDIDATE_ID_AMBIGUOUS", null, null))
                ));
                continue;
            }
            candidatePlans.add(planCandidate(
                    analysisContext,
                    candidate,
                    indexedSeeds.get(candidate.getCandidateId())
            ));
        }

        return new PlanResult(
                analysisContext.getAnalysisId(),
                requestedN,
                selectedCount,
                candidateIds,
                candidatePlans,
                false
        );
    }

    private CandidatePlan planCandidate(AnalysisContext analysisContext,
                                        VariantInterpretation candidate,
                                        List<IndexedSeed> candidateSeeds) {
        List<ObjectNode> readyContexts = new ArrayList<ObjectNode>();
        List<ContextBlocker> blockers = new ArrayList<ContextBlocker>();
        if (candidateSeeds == null || candidateSeeds.isEmpty()) {
            blockers.add(blocker("CONTEXT_SEED_MISSING", null, null));
            return new CandidatePlan(candidate.getCandidateId(), readyContexts, blockers);
        }

        for (IndexedSeed indexedSeed : candidateSeeds) {
            try {
                readyContexts.add(buildContext(analysisContext, candidate, indexedSeed.seed.draft()));
            } catch (ContextNotReadyException exception) {
                blockers.add(blocker(
                        exception.reasonCode,
                        indexedSeed.ordinal,
                        exception.field
                ));
            } catch (IllegalArgumentException exception) {
                blockers.add(blocker("CONTEXT_CONTRACT_INVALID", indexedSeed.ordinal, null));
            }
        }
        return new CandidatePlan(candidate.getCandidateId(), readyContexts, blockers);
    }

    private ObjectNode buildContext(AnalysisContext analysisContext,
                                    VariantInterpretation candidate,
                                    ObjectNode draft) {
        requireSeedFields(draft);
        verifyCandidateFields(analysisContext, candidate, draft);

        ObjectNode context = mapper.createObjectNode();
        context.put("schemaVersion", "evidence-context-v2");
        context.put("canonicalizationProfile", "varvis-json-c14n-v1");
        context.set("dataClass", draft.path("dataClass").deepCopy());
        context.set("scope", draft.path("scope").deepCopy());

        ObjectNode variant = (ObjectNode) draft.path("variant").deepCopy();
        variant.remove("alleleId");
        variant.put("legacyCanonicalVariantId", candidate.getCanonicalVariantId());
        variant.put(
                "alleleId",
                contextContract.canonicalAlleleId(
                        variant.path("normalizedAllele"),
                        variant.path("normalization")
                )
        );
        context.set("variant", variant);
        context.set("gene", draft.path("gene").deepCopy());
        context.set("disease", draft.path("disease").deepCopy());
        context.set("moi", draft.path("moi").deepCopy());
        context.set("transcript", draft.path("transcript").deepCopy());
        context.set("patientContext", draft.path("patientContext").deepCopy());

        ObjectNode completeness = mapper.createObjectNode();
        completeness.put("status", "COMPLETE");
        completeness.set("missingFields", mapper.createArrayNode());
        context.set("contextCompleteness", completeness);
        context.put("contextId", contextContract.canonicalContextId(context));
        contextContract.validateCompleteContext(context);
        return context;
    }

    private void requireSeedFields(ObjectNode draft) {
        if (draft == null) {
            throw notReady("CONTEXT_SEED_MISSING", null);
        }
        if (draft.has("schemaVersion") || draft.has("contextId")
                || draft.has("canonicalizationProfile") || draft.has("contextCompleteness")
                || draft.path("variant").has("alleleId")) {
            throw notReady("CONTEXT_SEED_DERIVED_FIELD_PRESENT", null);
        }
        requirePointers(draft, REQUIRED_SEED_POINTERS);
    }

    private void requirePointers(ObjectNode draft, List<String> pointers) {
        for (String pointer : pointers) {
            JsonNode value = draft.at(pointer);
            if (value.isMissingNode() || value.isNull()
                    || (value.isTextual() && value.asText().trim().isEmpty())) {
                throw notReady("CONTEXT_REQUIRED_FIELD_MISSING", pointer);
            }
            if (value.isArray()) {
                throw notReady("CONTEXT_FIELD_AMBIGUOUS", pointer);
            }
        }
    }

    private void verifyCandidateFields(AnalysisContext analysisContext,
                                       VariantInterpretation candidate,
                                       ObjectNode draft) {
        VariantQcObservation observation = candidate.getObservation();
        NormalizedVariantKey key = observation == null ? null : observation.getKey();
        if (key == null) {
            throw notReady("CANDIDATE_ALLELE_MISSING", "/variant/normalizedAllele");
        }
        if (analysisContext.getGenomeAssembly() == null) {
            throw notReady("ANALYSIS_ASSEMBLY_MISSING", "/variant/normalizedAllele/assembly");
        }
        if (!analysisContext.getGenomeAssembly().name().equals(key.getGenomeAssembly())) {
            throw notReady("CANDIDATE_ANALYSIS_ASSEMBLY_CONFLICT", "/variant/normalizedAllele/assembly");
        }
        if (!key.canonicalId().equals(candidate.getCanonicalVariantId())) {
            throw notReady("CANDIDATE_CANONICAL_ALLELE_CONFLICT", "/variant/legacyCanonicalVariantId");
        }

        JsonNode variant = draft.path("variant");
        if (!alleleMatches(variant.path(convertedBa1 ? "evidenceQueryAllele" : "normalizedAllele"), key)) {
            throw notReady("CANDIDATE_NORMALIZED_ALLELE_CONFLICT", "/variant/normalizedAllele");
        }
        JsonNode suppliedLegacyId = variant.get("legacyCanonicalVariantId");
        if (suppliedLegacyId != null
                && (!suppliedLegacyId.isTextual()
                || !candidate.getCanonicalVariantId().equals(suppliedLegacyId.asText()))) {
            throw notReady("CANDIDATE_CANONICAL_ALLELE_CONFLICT", "/variant/legacyCanonicalVariantId");
        }
        String expectedVariantType = evidenceVariantType(key);
        if (expectedVariantType == null) {
            throw notReady("CANDIDATE_VARIANT_TYPE_UNRESOLVED", "/variant/variantType");
        }
        if (!expectedVariantType.equals(variant.path("variantType").asText())) {
            throw notReady("CANDIDATE_VARIANT_TYPE_CONFLICT", "/variant/variantType");
        }

        CandidateIdentity identity = candidate.getCandidateIdentity();
        if (hasText(identity.getGeneSymbol())
                && !identity.getGeneSymbol().equals(draft.path("gene").path("symbol").asText())) {
            throw notReady("CANDIDATE_GENE_CONFLICT", "/gene/symbol");
        }
        String candidateMoi = evidenceMoi(candidate, identity);
        if (candidateMoi != null && !candidateMoi.equals(draft.path("moi").asText())) {
            throw notReady("CANDIDATE_MOI_CONFLICT", "/moi");
        }

        if (convertedBa1) {
            if (!"MAPPED".equals(variant.at("/assemblyLineage/mappingStatus").asText())
                    || !"GRCh37".equals(variant.at("/normalizedAllele/assembly").asText())
                    || !ConvertedAlleleSource.METHOD.equals(variant.at("/assemblyLineage/coordinateMapping/method").asText()))
                throw notReady("CONVERSION_CONTEXT_LINEAGE_CONFLICT", "/variant/assemblyLineage");
        } else verifyResolvedInputAllele(observation, variant);
    }

    private void verifyResolvedInputAllele(VariantQcObservation observation, JsonNode variant) {
        NormalizationAlleleMapping mapping = observation.normalizationMapping();
        if (!mapping.isResolved()) {
            return;
        }
        int alternateIndex = mapping.getRawAlternateIndex() == null
                ? -1 : mapping.getRawAlternateIndex().intValue() - 1;
        if (alternateIndex < 0 || alternateIndex >= mapping.getRawAlternates().size()) {
            throw notReady("CANDIDATE_INPUT_ALLELE_CONFLICT", "/variant/inputAllele");
        }
        JsonNode input = variant.path("inputAllele");
        String contig = CoordinateUtils.normalizeChromosome(mapping.getRawChromosome());
        String alternate = mapping.getRawAlternates().get(alternateIndex);
        if (!alleleMatches(
                input,
                observation.getKey().getGenomeAssembly(),
                contig,
                mapping.getRawPosition(),
                mapping.getRawReference(),
                alternate
        )) {
            throw notReady("CANDIDATE_INPUT_ALLELE_CONFLICT", "/variant/inputAllele");
        }
        String expectedStatus = NormalizationAlleleMapping.IDENTITY.equals(mapping.getStatus())
                ? "UNCHANGED" : "NORMALIZED";
        if (!expectedStatus.equals(variant.path("normalization").path("status").asText())) {
            throw notReady("CANDIDATE_NORMALIZATION_STATUS_CONFLICT", "/variant/normalization/status");
        }
    }

    private boolean alleleMatches(JsonNode allele, NormalizedVariantKey key) {
        return alleleMatches(
                allele,
                key.getGenomeAssembly(),
                key.getChromosome(),
                key.getPosition(),
                key.getReference(),
                key.getAlternate()
        );
    }

    static String evidenceVariantType(NormalizedVariantKey key) {
        String reference = upper(key.getReference());
        String alternate = upper(key.getAlternate());
        if (reference.isEmpty() || alternate.isEmpty() || reference.equals(alternate)) {
            return null;
        }
        if (reference.length() == alternate.length()) {
            return reference.length() == 1 ? "SNV" : "MNV";
        }
        if (reference.length() == 1) {
            return "SMALL_INSERTION";
        }
        if (alternate.length() == 1) {
            return "SMALL_DELETION";
        }
        return "SMALL_DELINS";
    }

    private String evidenceMoi(VariantInterpretation candidate, CandidateIdentity identity) {
        ExomiserCandidateFeatures features = candidate.getExomiserCandidateFeatures();
        String value = features == null ? null : features.getNormalizedModeOfInheritance();
        if (!hasText(value)) {
            value = identity.getModeOfInheritance();
        }
        if (value == null || !hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (normalized) {
            case "AD", "AUTOSOMAL_DOMINANT" -> "AUTOSOMAL_DOMINANT";
            case "AR", "AUTOSOMAL_RECESSIVE" -> "AUTOSOMAL_RECESSIVE";
            case "XD", "X_DOMINANT", "X_LINKED_DOMINANT" -> "X_LINKED_DOMINANT";
            case "XR", "X_RECESSIVE", "X_LINKED_RECESSIVE" -> "X_LINKED_RECESSIVE";
            case "Y_LINKED" -> "Y_LINKED";
            default -> throw notReady("CANDIDATE_MOI_UNSUPPORTED", "/moi");
        };
    }

    private boolean alleleMatches(JsonNode allele,
                                  String assembly,
                                  String contig,
                                  Integer position,
                                  String reference,
                                  String alternate) {
        return position != null
                && assembly.equals(allele.path("assembly").asText())
                && contig.equals(allele.path("contig").asText())
                && allele.path("position").isIntegralNumber()
                && position.intValue() == allele.path("position").asInt()
                && upper(reference).equals(allele.path("reference").asText())
                && upper(alternate).equals(allele.path("alternate").asText());
    }

    private Map<String, List<IndexedSeed>> indexSeeds(List<ContextSeed> seeds) {
        Map<String, List<IndexedSeed>> result = new LinkedHashMap<String, List<IndexedSeed>>();
        if (seeds == null) {
            return result;
        }
        for (int index = 0; index < seeds.size(); index++) {
            ContextSeed seed = Objects.requireNonNull(seeds.get(index), "seeds contains null");
            result.computeIfAbsent(seed.candidateId(), ignored -> new ArrayList<IndexedSeed>())
                    .add(new IndexedSeed(index + 1, seed));
        }
        return result;
    }

    private ContextBlocker blocker(String reasonCode, Integer seedOrdinal, String field) {
        return new ContextBlocker(CONTEXT_NOT_READY, reasonCode, seedOrdinal, field);
    }

    private ContextNotReadyException notReady(String reasonCode, String field) {
        return new ContextNotReadyException(reasonCode, field);
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /** One explicit context draft bound to an analysis-local candidate row. */
    public record ContextSeed(String candidateId, ObjectNode draft) {
        public ContextSeed {
            if (candidateId == null || candidateId.trim().isEmpty()) {
                throw new IllegalArgumentException("CONTEXT_SEED_CANDIDATE_ID_REQUIRED");
            }
            candidateId = candidateId.trim();
            draft = Objects.requireNonNull(draft, "draft").deepCopy();
        }

        @Override
        public ObjectNode draft() {
            return draft.deepCopy();
        }
    }

    /** One failed seed attempt; status is always CONTEXT_NOT_READY. */
    public record ContextBlocker(String status,
                                 String reasonCode,
                                 Integer seedOrdinal,
                                 String field) {
    }

    /** Context outcomes for one selected candidate row; each seed succeeds or blocks independently. */
    public record CandidatePlan(String candidateId,
                                List<ObjectNode> readyContexts,
                                List<ContextBlocker> blockers) {
        public CandidatePlan {
            readyContexts = List.copyOf(readyContexts);
            blockers = List.copyOf(blockers);
        }
    }

    /** Ordered Top-N planning result; it deliberately contains no evidence or evaluations. */
    public record PlanResult(String analysisId,
                             int requestedN,
                             int selectedCount,
                             List<String> candidateIds,
                             List<CandidatePlan> candidates,
                             boolean candidateOrderingChanged) {
        public PlanResult {
            candidateIds = List.copyOf(candidateIds);
            candidates = List.copyOf(candidates);
        }
    }

    private record IndexedSeed(int ordinal, ContextSeed seed) {
    }

    private static final class ContextNotReadyException extends RuntimeException {
        private final String reasonCode;
        private final String field;

        private ContextNotReadyException(String reasonCode, String field) {
            this.reasonCode = reasonCode;
            this.field = field;
        }
    }
}
