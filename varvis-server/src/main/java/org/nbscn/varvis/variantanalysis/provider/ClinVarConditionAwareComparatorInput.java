package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Explicit, read-only assembly of condition-aware ClinVar comparator inputs.
 * This class discovers and relates comparator records; it does not evaluate
 * PS1 or PM5 and is not registered in the default analysis flow.
 */
public final class ClinVarConditionAwareComparatorInput {

    public static final String COMPARATOR_SCOPE_VERSION = "1.0.1";

    public static final String RESULT_SCHEMA_VERSION =
            "clinvar-condition-aware-comparator-input-v1";
    public static final String RECORD_SCHEMA_VERSION =
            "clinvar-condition-aware-comparator-record-v1";

    private final ClinVarTabixProvider clinVarProvider;
    private final VepTranscriptAnnotationProvider vepProvider;
    private final ClinVarComparatorSidecar sidecar;
    private final VepProteinRelationClassifier relationClassifier;

    public ClinVarConditionAwareComparatorInput(
            ClinVarTabixProvider clinVarProvider,
            VepTranscriptAnnotationProvider vepProvider,
            ClinVarComparatorSidecar sidecar,
            VepProteinRelationClassifier relationClassifier) {
        this.clinVarProvider = clinVarProvider;
        this.vepProvider = vepProvider;
        this.sidecar = sidecar;
        this.relationClassifier = relationClassifier;
    }

    public Result build(JsonNode evidenceContext,
                        VariantInterpretation target,
                        TranscriptConsequence selectedTargetConsequence) {
        return buildInternal(evidenceContext, target, selectedTargetConsequence, null);
    }

    /**
     * Captures the complete DTOs actually selected during this single build.
     * The separate value snapshot leaves the established Result JSON unchanged.
     */
    public SnapshotCapture buildWithSnapshots(JsonNode evidenceContext,
                                              VariantInterpretation target,
                                              TranscriptConsequence selectedTargetConsequence) {
        return capture(evidenceContext, target, selectedTargetConsequence, null);
    }

    /** Request-local reuse only; callers pin the resource snapshot before opening a session. */
    public Session openSession(JsonNode resourceSnapshot) {
        return new Session(Objects.requireNonNull(resourceSnapshot).deepCopy());
    }

    private SnapshotCapture capture(JsonNode evidenceContext, VariantInterpretation target,
                                    TranscriptConsequence selectedTargetConsequence, Session session) {
        ObjectMapper snapshotMapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        JsonNode contextSnapshot = snapshotMapper.valueToTree(evidenceContext);
        JsonNode targetSnapshot = snapshotMapper.valueToTree(selectedTargetConsequence);
        List<ReferenceAnnotationSnapshot> references = new ArrayList<>();
        Result result = buildInternal(evidenceContext, target, selectedTargetConsequence,
                references, session);
        return new SnapshotCapture(contextSnapshot, result, snapshotMapper.valueToTree(result),
                targetSnapshot, references);
    }

    /** Reuses source facts, never context-specific comparator decisions or evaluations. */
    public final class Session {
        private final JsonNode resources;
        private final Map<String, ClinVarTabixProvider.ComparatorDiscoveryResult> discoveries = new LinkedHashMap<>();
        private final Map<String, Map<NormalizedVariantKey, List<TranscriptConsequence>>> annotations = new LinkedHashMap<>();
        private final Map<String, ClinVarComparatorSidecar.LookupResult> assertions = new LinkedHashMap<>();
        private int builds, discoveryCalls, annotationCalls, sidecarCalls;

        private Session(JsonNode resources) { this.resources = resources; }

        public SnapshotCapture buildWithSnapshots(JsonNode context, VariantInterpretation target,
                                                   TranscriptConsequence selected, JsonNode resourceSnapshot) {
            if (!resources.equals(resourceSnapshot)) throw new IllegalArgumentException("REFERENCE_SESSION_RESOURCE_CHANGED");
            if (++builds > 256) throw new IllegalArgumentException("REFERENCE_SESSION_BUILD_LIMIT");
            return capture(context, target, selected, this);
        }

        public ObjectNode counts() {
            return new ObjectMapper().createObjectNode().put("builds", builds).put("discoveryCalls", discoveryCalls)
                    .put("annotationCalls", annotationCalls).put("sidecarCalls", sidecarCalls);
        }
    }

    private Result buildInternal(JsonNode evidenceContext,
                                 VariantInterpretation target,
                                 TranscriptConsequence selectedTargetConsequence,
                                 List<ReferenceAnnotationSnapshot> snapshots) {
        return buildInternal(evidenceContext, target, selectedTargetConsequence, snapshots, null);
    }

    private Result buildInternal(JsonNode evidenceContext, VariantInterpretation target,
                                 TranscriptConsequence selectedTargetConsequence,
                                 List<ReferenceAnnotationSnapshot> snapshots, Session session) {
        NormalizedVariantKey targetAllele = allele(target);
        String contextReason = contextReason(
                evidenceContext, targetAllele, selectedTargetConsequence);
        if (contextReason != null) {
            return Result.notEvaluated(contextId(evidenceContext), targetAllele,
                    contextReason, null, null);
        }
        CodonWindow window = codonWindow(targetAllele, selectedTargetConsequence.getCodons());
        if (window == null) {
            return Result.notEvaluated(contextId(evidenceContext), targetAllele,
                    "CODON_WINDOW_NOT_UNIQUELY_RESOLVABLE", null, null);
        }
        if (clinVarProvider == null || vepProvider == null
                || sidecar == null || relationClassifier == null) {
            return Result.notEvaluated(contextId(evidenceContext), targetAllele,
                    "COMPARATOR_INPUT_DEPENDENCY_UNAVAILABLE", window, null);
        }

        String discoveryKey = targetAllele.canonicalId() + ":" + window.getStart() + "-" + window.getEnd();
        ClinVarTabixProvider.ComparatorDiscoveryResult discovery = session == null ? null : session.discoveries.get(discoveryKey);
        if (discovery == null) {
            if (session != null) session.discoveryCalls++;
            discovery = clinVarProvider.discoverBoundedComparators(targetAllele, window.getStart(), window.getEnd());
            if (session != null) session.discoveries.put(discoveryKey, discovery);
        }
        DiscoverySummary discoverySummary = new DiscoverySummary(discovery);
        if (!"READY".equals(discovery.getReadinessStatus())) {
            return Result.notEvaluated(contextId(evidenceContext), targetAllele,
                    firstText(discovery.getReasonCode(), "CLINVAR_COMPARATOR_DISCOVERY_NOT_READY"),
                    window, discoverySummary);
        }

        List<ClinVarTabixProvider.ComparatorCandidate> candidates =
                withoutTarget(discovery.getCandidates(), targetAllele);
        List<VariantInterpretation> candidateVariants = batchVariants(
                targetAllele, window.continuityProbeAlleles, candidates);
        AnalysisContext analysisContext = new AnalysisContext(
                contextId(evidenceContext), GenomeAssembly.GRCh38, null);
        if (candidates.size() > MissenseReferenceDraftPreparation.MAX_CANDIDATES)
            return Result.notEvaluated(contextId(evidenceContext), targetAllele,
                    "COMPARATOR_CANDIDATE_LIMIT_SEARCH_INCOMPLETE", window, discoverySummary);
        String annotationKey = candidateVariants.stream().map(v -> v.getObservation().getKey().canonicalId())
                .sorted().collect(java.util.stream.Collectors.joining(";"));
        Map<NormalizedVariantKey, List<TranscriptConsequence>> annotations = session == null ? null : session.annotations.get(annotationKey);
        if (session == null || !session.annotations.containsKey(annotationKey)) {
            if (session != null) session.annotationCalls++;
            annotations = vepProvider.annotateBatch(candidateVariants, analysisContext);
            // Failures are retained only for this invocation, with their original incomplete state.
            if (session != null)
                session.annotations.put(annotationKey, copyAnnotations(annotations));
        }
        if (!continuousCodonConfirmed(annotations, targetAllele,
                window.continuityProbeAlleles, selectedTargetConsequence)) {
            return Result.notEvaluated(contextId(evidenceContext), targetAllele,
                    "CODON_WINDOW_NOT_CONTIGUOUS", window, discoverySummary);
        }
        if (candidates.isEmpty()) {
            return Result.noRecord(contextId(evidenceContext), targetAllele,
                    "NO_BOUNDED_CLINVAR_COMPARATOR", window, discoverySummary,
                    Collections.<CandidateDecision>emptyList());
        }

        List<ComparatorRecord> records = new ArrayList<ComparatorRecord>();
        List<CandidateDecision> decisions = new ArrayList<CandidateDecision>();
        for (ClinVarTabixProvider.ComparatorCandidate candidate : candidates) {
            NormalizedVariantKey candidateAllele = candidate.getExactAllele();
            TranscriptSelection selection = selectComparatorConsequence(
                    annotations == null ? null : annotations.get(candidateAllele),
                    selectedTargetConsequence);
            if (selection.consequence == null) {
                decisions.add(CandidateDecision.notEvaluated(candidate,
                        selection.reasonCode));
                continue;
            }

            // Same codon alone does not make a stop/synonymous neighbor a missense
            // reference. Keep its exclusion in the search record, not in the draft.
            if (!containsTerm(selection.consequence.getConsequence(), "missense_variant")) {
                decisions.add(CandidateDecision.notApplicable(candidate,
                        "COMPARATOR_MISSENSE_CONSEQUENCE_REQUIRED"));
                continue;
            }

            VepProteinRelationClassifier.Result relation = relationClassifier.classify(
                    selectedTargetConsequence, selection.consequence);
            if (!relation.isEvaluated()) {
                decisions.add(CandidateDecision.notEvaluated(candidate,
                        firstText(relation.getReasonCode(), "PROTEIN_RELATION_NOT_EVALUATED")));
                continue;
            }
            if (!sameCodonRelation(relation.getRelation())) {
                decisions.add(CandidateDecision.notApplicable(candidate,
                        "NOT_A_SAME_RESIDUE_COMPARATOR"));
                continue;
            }

            String assertionKey = candidateAllele.canonicalId() + ":" + candidate.getVariationId();
            ClinVarComparatorSidecar.LookupResult lookup = session == null ? null : session.assertions.get(assertionKey);
            if (lookup == null) {
                if (session != null) session.sidecarCalls++;
                lookup = sidecar.lookup(candidateAllele, String.valueOf(candidate.getVariationId()));
                if (session != null) session.assertions.put(assertionKey, lookup);
            }
            if (lookup.getStatus() != ClinVarComparatorSidecar.Status.HIT
                    || lookup.getRecord() == null) {
                decisions.add(CandidateDecision.omitted(candidate,
                        lookup.getStatus().name(), lookup.getReasonCode(), lookup.getProvenance()));
                continue;
            }
            records.add(new ComparatorRecord(
                    contextId(evidenceContext), candidate, relation,
                    selectedTargetConsequence.getConsequence(),
                    selection.consequence.getConsequence(), lookup.getRecord()));
            if (snapshots != null) {
                snapshots.add(new ReferenceAnnotationSnapshot(candidateAllele,
                        String.valueOf(candidate.getVariationId()), selection.consequence));
            }
            decisions.add(CandidateDecision.ready(candidate, lookup.getProvenance()));
        }

        if (records.isEmpty()) {
            return Result.notEvaluated(contextId(evidenceContext), targetAllele,
                    "NO_UNIQUE_CONDITION_AWARE_COMPARATOR", window, discoverySummary,
                    decisions);
        }
        return Result.ready(contextId(evidenceContext), targetAllele, window,
                discoverySummary, records, decisions);
    }

    private Map<NormalizedVariantKey, List<TranscriptConsequence>> copyAnnotations(Map<NormalizedVariantKey, List<TranscriptConsequence>> values) {
        if (values == null) return null;
        Map<NormalizedVariantKey, List<TranscriptConsequence>> copy = new LinkedHashMap<>();
        values.forEach((key, rows) -> copy.put(key, rows == null ? null : rows.stream()
                .map(t -> t == null ? null : t.withAnnotationMetadata(t.getSource(), t.getStatus(), t.getVersion(), t.getTimestamp())).toList()));
        return copy;
    }

    private String contextReason(JsonNode context,
                                 NormalizedVariantKey target,
                                 TranscriptConsequence selected) {
        if (context == null || !"evidence-context-v2".equals(
                context.path("schemaVersion").asText()) || !hasText(contextId(context))) {
            return "EVIDENCE_CONTEXT_INVALID";
        }
        if (!simpleGrch38Snv(target)) {
            return "TARGET_MISSENSE_GRCH38_SNV_REQUIRED";
        }
        JsonNode queryAllele = context.path("variant").path("evidenceQueryAllele");
        if (!target.getGenomeAssembly().equals(queryAllele.path("assembly").asText())
                || !target.getChromosome().equals(normalizeChromosome(
                queryAllele.path("contig").asText()))
                || target.getPosition() != queryAllele.path("position").asInt(-1)
                || !target.getReference().equalsIgnoreCase(queryAllele.path("reference").asText())
                || !target.getAlternate().equalsIgnoreCase(queryAllele.path("alternate").asText())) {
            return "EVIDENCE_CONTEXT_ALLELE_MISMATCH";
        }
        if (selected == null || !"COMPLETED".equals(selected.getStatus())
                || selected.getSource() == null || !selected.getSource().startsWith("VEP")
                || !containsTerm(selected.getConsequence(), "missense_variant")
                || !hasText(selected.getVersionedTranscriptId())) {
            return "SELECTED_TARGET_MISSENSE_CONSEQUENCE_REQUIRED";
        }
        JsonNode transcript = context.path("transcript");
        String accession = transcript.path("accession").asText(null);
        int version = transcript.path("version").asInt(-1);
        if (!hasText(accession) || version < 1
                || !(accession + "." + version).equals(selected.getVersionedTranscriptId())) {
            return "SELECTED_TRANSCRIPT_CONTEXT_MISMATCH";
        }
        String contextGene = context.path("gene").path("symbol").asText(null);
        if (hasText(contextGene) && !contextGene.equals(selected.getGeneSymbol())) {
            return "SELECTED_GENE_CONTEXT_MISMATCH";
        }
        String annotationVersion = transcript.path("annotationSourceVersion").asText(null);
        if (hasText(annotationVersion) && !annotationVersion.equals(selected.getVersion())) {
            return "SELECTED_ANNOTATION_VERSION_MISMATCH";
        }
        return null;
    }

    private CodonWindow codonWindow(NormalizedVariantKey target, String codons) {
        if (!hasText(codons)) return null;
        String[] pair = codons.trim().split("/", -1);
        if (pair.length != 2 || !pair[0].matches("[ACGTacgt]{3}")
                || !pair[1].matches("[ACGTacgt]{3}")) return null;
        String referenceCodon = pair[0].toUpperCase(Locale.ROOT);
        String alternateCodon = pair[1].toUpperCase(Locale.ROOT);
        int difference = -1;
        for (int index = 0; index < 3; index++) {
            if (referenceCodon.charAt(index) != alternateCodon.charAt(index)) {
                if (difference >= 0) return null;
                difference = index;
            }
        }
        if (difference < 0) return null;
        char codonReference = referenceCodon.charAt(difference);
        char codonAlternate = alternateCodon.charAt(difference);
        char genomicReference = Character.toUpperCase(target.getReference().charAt(0));
        char genomicAlternate = Character.toUpperCase(target.getAlternate().charAt(0));
        boolean forward = codonReference == genomicReference && codonAlternate == genomicAlternate;
        boolean reverse = codonReference == complement(genomicReference)
                && codonAlternate == complement(genomicAlternate);
        if (forward == reverse) return null;
        int offset = forward ? difference : 2 - difference;
        int start = target.getPosition() - offset;
        if (start < 1) return null;
        List<NormalizedVariantKey> probes = new ArrayList<NormalizedVariantKey>();
        for (int codingIndex = 0; codingIndex < 3; codingIndex++) {
            if (codingIndex == difference) continue;
            int genomicOffset = forward ? codingIndex : 2 - codingIndex;
            char genomicReferenceBase = forward
                    ? referenceCodon.charAt(codingIndex)
                    : complement(referenceCodon.charAt(codingIndex));
            String probeReference = String.valueOf(genomicReferenceBase);
            String probeAlternate = "A".equals(probeReference) ? "C" : "A";
            probes.add(new NormalizedVariantKey("GRCh38", target.getChromosome(),
                    start + genomicOffset, probeReference, probeAlternate));
        }
        return new CodonWindow(start, start + 2, difference,
                forward ? "FORWARD" : "REVERSE_COMPLEMENT", probes);
    }

    private boolean continuousCodonConfirmed(
            Map<NormalizedVariantKey, List<TranscriptConsequence>> annotations,
            NormalizedVariantKey targetAllele,
            List<NormalizedVariantKey> probes,
            TranscriptConsequence selectedTarget) {
        if (annotations == null) return false;
        TranscriptSelection targetSelection = selectComparatorConsequence(
                annotations.get(targetAllele), selectedTarget);
        if (!sameProteinLocus(selectedTarget, targetSelection.consequence)) return false;
        for (NormalizedVariantKey probe : probes) {
            TranscriptSelection selection = selectComparatorConsequence(
                    annotations.get(probe), selectedTarget);
            if (!sameProteinLocus(selectedTarget, selection.consequence)) return false;
        }
        return true;
    }

    private boolean sameProteinLocus(TranscriptConsequence target,
                                     TranscriptConsequence observed) {
        if (target == null || observed == null
                || !sameText(target.getProteinPosition(), observed.getProteinPosition())) {
            return false;
        }
        String targetReference = referenceAminoAcid(target.getAminoAcids());
        String observedReference = referenceAminoAcid(observed.getAminoAcids());
        return targetReference != null && targetReference.equals(observedReference);
    }

    private String referenceAminoAcid(String aminoAcids) {
        if (!hasText(aminoAcids)) return null;
        String normalized = aminoAcids.trim();
        if (normalized.length() == 1) return normalized;
        String[] pair = normalized.split("/", -1);
        return pair.length == 2 && pair[0].length() == 1 ? pair[0] : null;
    }

    private TranscriptSelection selectComparatorConsequence(
            List<TranscriptConsequence> annotations,
            TranscriptConsequence selectedTarget) {
        List<TranscriptConsequence> matches = new ArrayList<TranscriptConsequence>();
        if (annotations != null) {
            for (TranscriptConsequence annotation : annotations) {
                if (annotation != null
                        && "COMPLETED".equals(annotation.getStatus())
                        && selectedTarget.getVersionedTranscriptId().equals(
                        annotation.getVersionedTranscriptId())
                        && sameText(selectedTarget.getGeneSymbol(), annotation.getGeneSymbol())
                        && sameText(selectedTarget.getVersion(), annotation.getVersion())) {
                    matches.add(annotation);
                }
            }
        }
        if (matches.isEmpty()) {
            return new TranscriptSelection(null, "SELECTED_COMPARATOR_TRANSCRIPT_NOT_FOUND");
        }
        if (matches.size() != 1) {
            return new TranscriptSelection(null, "SELECTED_COMPARATOR_TRANSCRIPT_AMBIGUOUS");
        }
        return new TranscriptSelection(matches.get(0), null);
    }

    private List<ClinVarTabixProvider.ComparatorCandidate> withoutTarget(
            List<ClinVarTabixProvider.ComparatorCandidate> candidates,
            NormalizedVariantKey target) {
        List<ClinVarTabixProvider.ComparatorCandidate> result =
                new ArrayList<ClinVarTabixProvider.ComparatorCandidate>();
        if (candidates == null) return result;
        for (ClinVarTabixProvider.ComparatorCandidate candidate : candidates) {
            if (candidate != null && candidate.getExactAllele() != null
                    && !sameAllele(target, candidate.getExactAllele())) {
                result.add(candidate);
            }
        }
        return result;
    }

    private List<VariantInterpretation> batchVariants(
            NormalizedVariantKey target,
            List<NormalizedVariantKey> probes,
            List<ClinVarTabixProvider.ComparatorCandidate> candidates) {
        Map<String, NormalizedVariantKey> alleles = new LinkedHashMap<String, NormalizedVariantKey>();
        alleles.put(target.canonicalId(), target);
        for (NormalizedVariantKey probe : probes) {
            alleles.put(probe.canonicalId(), probe);
        }
        for (ClinVarTabixProvider.ComparatorCandidate candidate : candidates) {
            NormalizedVariantKey key = candidate.getExactAllele();
            alleles.put(key.canonicalId(), key);
        }
        List<VariantInterpretation> variants = new ArrayList<VariantInterpretation>();
        for (NormalizedVariantKey key : alleles.values()) {
            VariantQcObservation observation = new VariantQcObservation(
                    key, null, null, null, null, null, null,
                    "PASS", "SNV", null);
            variants.add(new VariantInterpretation(
                    key.canonicalId(), observation,
                    Collections.<TranscriptConsequence>emptyList(),
                    Collections.emptyList()));
        }
        return variants;
    }

    private boolean sameCodonRelation(VepProteinRelationClassifier.Relation relation) {
        return relation == VepProteinRelationClassifier.Relation.SAME_AMINO_ACID_CHANGE
                || relation == VepProteinRelationClassifier.Relation.SAME_RESIDUE_DIFFERENT_AMINO_ACID;
    }

    private boolean simpleGrch38Snv(NormalizedVariantKey key) {
        return key != null && "GRCh38".equals(key.getGenomeAssembly())
                && key.getReference() != null && key.getReference().matches("[ACGTacgt]")
                && key.getAlternate() != null && key.getAlternate().matches("[ACGTacgt]")
                && !key.getReference().equalsIgnoreCase(key.getAlternate());
    }

    private NormalizedVariantKey allele(VariantInterpretation variant) {
        return variant == null || variant.getObservation() == null
                ? null : variant.getObservation().getKey();
    }

    private boolean sameAllele(NormalizedVariantKey left, NormalizedVariantKey right) {
        return left != null && right != null
                && left.getGenomeAssembly().equals(right.getGenomeAssembly())
                && left.getChromosome().equals(right.getChromosome())
                && left.getPosition() == right.getPosition()
                && left.getReference().equalsIgnoreCase(right.getReference())
                && left.getAlternate().equalsIgnoreCase(right.getAlternate());
    }

    private String normalizeChromosome(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).startsWith("chr")
                ? value.substring(3) : value;
    }

    private boolean containsTerm(String terms, String expected) {
        if (!hasText(terms)) return false;
        for (String term : terms.split("[&,]")) {
            if (expected.equals(term.trim())) return true;
        }
        return false;
    }

    private char complement(char nucleotide) {
        switch (nucleotide) {
            case 'A': return 'T';
            case 'C': return 'G';
            case 'G': return 'C';
            case 'T': return 'A';
            default: return 'N';
        }
    }

    private String contextId(JsonNode context) {
        return context == null ? null : context.path("contextId").asText(null);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private boolean sameText(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private String firstText(String first, String fallback) {
        return hasText(first) ? first : fallback;
    }

    private static final class TranscriptSelection {
        private final TranscriptConsequence consequence;
        private final String reasonCode;

        private TranscriptSelection(TranscriptConsequence consequence, String reasonCode) {
            this.consequence = consequence;
            this.reasonCode = reasonCode;
        }
    }

    /** Independent snapshots; all JSON accessors return defensive copies. */
    public static final class SnapshotCapture {
        private final JsonNode evidenceContext;
        private final Result input;
        private final JsonNode result;
        private final JsonNode selectedTargetAnnotation;
        private final List<ReferenceAnnotationSnapshot> referenceSnapshots;

        private SnapshotCapture(JsonNode evidenceContext,
                                Result input,
                                JsonNode result,
                                JsonNode selectedTargetAnnotation,
                                List<ReferenceAnnotationSnapshot> referenceSnapshots) {
            this.evidenceContext = evidenceContext.deepCopy();
            this.input = input;
            this.result = result.deepCopy();
            this.selectedTargetAnnotation = selectedTargetAnnotation.deepCopy();
            this.referenceSnapshots = Collections.unmodifiableList(
                    new ArrayList<>(referenceSnapshots));
        }

        public JsonNode getEvidenceContext() { return evidenceContext.deepCopy(); }
        public JsonNode getResult() { return result.deepCopy(); }
        public JsonNode getSelectedTargetAnnotation() { return selectedTargetAnnotation.deepCopy(); }
        public List<ReferenceAnnotationSnapshot> getReferenceSnapshots() { return referenceSnapshots; }
        Result input() { return input; }
    }

    /** Identity is explicit even when several ClinVar records share an allele. */
    public static final class ReferenceAnnotationSnapshot {
        private final NormalizedVariantKey exactAllele;
        private final String variationId;
        private final String transcript;
        private final JsonNode annotation;

        private ReferenceAnnotationSnapshot(NormalizedVariantKey exactAllele,
                                            String variationId,
                                            TranscriptConsequence consequence) {
            this.exactAllele = new NormalizedVariantKey(exactAllele.getGenomeAssembly(),
                    exactAllele.getChromosome(), exactAllele.getPosition(),
                    exactAllele.getReference(), exactAllele.getAlternate());
            this.variationId = variationId;
            this.transcript = consequence.getVersionedTranscriptId();
            this.annotation = new ObjectMapper().registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .valueToTree(consequence);
        }

        public NormalizedVariantKey getExactAllele() { return exactAllele; }
        public String getVariationId() { return variationId; }
        public String getTranscript() { return transcript; }
        public JsonNode getAnnotation() { return annotation.deepCopy(); }
    }

    public static final class Result {
        private final String schemaVersion;
        private final String status;
        private final String reasonCode;
        private final String contextId;
        private final NormalizedVariantKey targetAllele;
        private final CodonWindow codonWindow;
        private final DiscoverySummary discovery;
        private final List<ComparatorRecord> comparators;
        private final List<CandidateDecision> candidateDecisions;

        private Result(String status,
                       String reasonCode,
                       String contextId,
                       NormalizedVariantKey targetAllele,
                       CodonWindow codonWindow,
                       DiscoverySummary discovery,
                       List<ComparatorRecord> comparators,
                       List<CandidateDecision> candidateDecisions) {
            this.schemaVersion = RESULT_SCHEMA_VERSION;
            this.status = status;
            this.reasonCode = reasonCode;
            this.contextId = contextId;
            this.targetAllele = targetAllele;
            this.codonWindow = codonWindow;
            this.discovery = discovery;
            this.comparators = Collections.unmodifiableList(
                    new ArrayList<ComparatorRecord>(comparators));
            this.candidateDecisions = Collections.unmodifiableList(
                    new ArrayList<CandidateDecision>(candidateDecisions));
        }

        private static Result ready(String contextId,
                                    NormalizedVariantKey target,
                                    CodonWindow window,
                                    DiscoverySummary discovery,
                                    List<ComparatorRecord> records,
                                    List<CandidateDecision> decisions) {
            return new Result("READY", null, contextId, target, window, discovery,
                    records, decisions);
        }

        private static Result noRecord(String contextId,
                                       NormalizedVariantKey target,
                                       String reasonCode,
                                       CodonWindow window,
                                       DiscoverySummary discovery,
                                       List<CandidateDecision> decisions) {
            return new Result("NO_RECORD", reasonCode, contextId, target, window, discovery,
                    Collections.<ComparatorRecord>emptyList(), decisions);
        }

        private static Result notEvaluated(String contextId,
                                           NormalizedVariantKey target,
                                           String reasonCode,
                                           CodonWindow window,
                                           DiscoverySummary discovery) {
            return notEvaluated(contextId, target, reasonCode, window, discovery,
                    Collections.<CandidateDecision>emptyList());
        }

        private static Result notEvaluated(String contextId,
                                           NormalizedVariantKey target,
                                           String reasonCode,
                                           CodonWindow window,
                                           DiscoverySummary discovery,
                                           List<CandidateDecision> decisions) {
            return new Result("NOT_EVALUATED", reasonCode, contextId, target, window,
                    discovery, Collections.<ComparatorRecord>emptyList(), decisions);
        }

        public String getSchemaVersion() { return schemaVersion; }
        public String getStatus() { return status; }
        public String getReasonCode() { return reasonCode; }
        public String getContextId() { return contextId; }
        public NormalizedVariantKey getTargetAllele() { return targetAllele; }
        public CodonWindow getCodonWindow() { return codonWindow; }
        public DiscoverySummary getDiscovery() { return discovery; }
        public List<ComparatorRecord> getComparators() { return comparators; }
        public List<CandidateDecision> getCandidateDecisions() { return candidateDecisions; }
    }

    public static final class CodonWindow {
        private final int start;
        private final int end;
        private final int codingDifferenceIndex;
        private final String orientation;
        private final List<NormalizedVariantKey> continuityProbeAlleles;

        private CodonWindow(int start,
                            int end,
                            int codingDifferenceIndex,
                            String orientation,
                            List<NormalizedVariantKey> continuityProbeAlleles) {
            this.start = start;
            this.end = end;
            this.codingDifferenceIndex = codingDifferenceIndex;
            this.orientation = orientation;
            this.continuityProbeAlleles = Collections.unmodifiableList(
                    new ArrayList<NormalizedVariantKey>(continuityProbeAlleles));
        }

        public int getStart() { return start; }
        public int getEnd() { return end; }
        public int getCodingDifferenceIndex() { return codingDifferenceIndex; }
        public String getOrientation() { return orientation; }
    }

    public static final class DiscoverySummary {
        private final String resourceExecutionStatus;
        private final String resourceAvailabilityStatus;
        private final String queryOutcomeStatus;
        private final String resourceApplicabilityStatus;
        private final String readinessStatus;
        private final String reasonCode;
        private final String queriedRegion;
        private final int candidateCount;

        private DiscoverySummary(ClinVarTabixProvider.ComparatorDiscoveryResult result) {
            this.resourceExecutionStatus = result.getResourceExecutionStatus();
            this.resourceAvailabilityStatus = result.getResourceAvailabilityStatus();
            this.queryOutcomeStatus = result.getQueryOutcomeStatus();
            this.resourceApplicabilityStatus = result.getResourceApplicabilityStatus();
            this.readinessStatus = result.getReadinessStatus();
            this.reasonCode = result.getReasonCode();
            this.queriedRegion = result.getQueriedRegion();
            this.candidateCount = result.getCandidates().size();
        }

        public String getResourceExecutionStatus() { return resourceExecutionStatus; }
        public String getResourceAvailabilityStatus() { return resourceAvailabilityStatus; }
        public String getQueryOutcomeStatus() { return queryOutcomeStatus; }
        public String getResourceApplicabilityStatus() { return resourceApplicabilityStatus; }
        public String getReadinessStatus() { return readinessStatus; }
        public String getReasonCode() { return reasonCode; }
        public String getQueriedRegion() { return queriedRegion; }
        int candidateCount() { return candidateCount; }
    }

    public static final class ComparatorRecord {
        private final String schemaVersion;
        private final String targetContextId;
        private final NormalizedVariantKey exactAllele;
        private final String variationId;
        private final List<String> contributingSubmissionIdentifiers;
        private final String clinVarVcfRecordLocator;
        private final String proteinRelation;
        private final String selectedTargetConsequence;
        private final String selectedComparatorConsequence;
        private final VepProteinRelationClassifier.ProteinChange targetProteinChange;
        private final VepProteinRelationClassifier.ProteinChange comparatorProteinChange;
        private final List<String> geneSymbols;
        private final String conflictState;
        private final ArrayNode variantSummaryRecords;
        private final ArrayNode submissions;
        private final ClinVarComparatorSidecar.Provenance snapshotProvenance;

        private ComparatorRecord(String targetContextId,
                                 ClinVarTabixProvider.ComparatorCandidate candidate,
                                 VepProteinRelationClassifier.Result relation,
                                 String selectedTargetConsequence,
                                 String selectedComparatorConsequence,
                                 ClinVarComparatorSidecar.ComparatorRecord sidecarRecord) {
            this.schemaVersion = RECORD_SCHEMA_VERSION;
            this.targetContextId = targetContextId;
            this.exactAllele = candidate.getExactAllele();
            this.variationId = String.valueOf(candidate.getVariationId());
            this.contributingSubmissionIdentifiers = Collections.unmodifiableList(
                    new ArrayList<String>(candidate.getContributingSubmissionIdentifiers()));
            this.clinVarVcfRecordLocator = candidate.getRecordLocator();
            this.proteinRelation = relation.getRelation().name();
            this.selectedTargetConsequence = selectedTargetConsequence;
            this.selectedComparatorConsequence = selectedComparatorConsequence;
            this.targetProteinChange = relation.getTarget();
            this.comparatorProteinChange = relation.getComparator();
            this.geneSymbols = Collections.unmodifiableList(
                    new ArrayList<String>(sidecarRecord.getGeneSymbols()));
            this.conflictState = sidecarRecord.getConflictState();
            this.variantSummaryRecords = sidecarRecord.getVariantSummaryRecords();
            this.submissions = sidecarRecord.getSubmissions();
            this.snapshotProvenance = sidecarRecord.getProvenance();
        }

        public String getSchemaVersion() { return schemaVersion; }
        public String getTargetContextId() { return targetContextId; }
        public NormalizedVariantKey getExactAllele() { return exactAllele; }
        public String getVariationId() { return variationId; }
        public List<String> getContributingSubmissionIdentifiers() {
            return contributingSubmissionIdentifiers;
        }
        public String getClinVarVcfRecordLocator() { return clinVarVcfRecordLocator; }
        public String getProteinRelation() { return proteinRelation; }
        public String getSelectedTargetConsequence() { return selectedTargetConsequence; }
        public String getSelectedComparatorConsequence() { return selectedComparatorConsequence; }
        public VepProteinRelationClassifier.ProteinChange getTargetProteinChange() {
            return targetProteinChange;
        }
        public VepProteinRelationClassifier.ProteinChange getComparatorProteinChange() {
            return comparatorProteinChange;
        }
        public List<String> getGeneSymbols() { return geneSymbols; }
        public String getConflictState() { return conflictState; }
        public ArrayNode getVariantSummaryRecords() { return variantSummaryRecords.deepCopy(); }
        public ArrayNode getSubmissions() { return submissions.deepCopy(); }
        public ClinVarComparatorSidecar.Provenance getSnapshotProvenance() {
            return snapshotProvenance;
        }
    }

    public static final class CandidateDecision {
        private final NormalizedVariantKey exactAllele;
        private final String variationId;
        private final String status;
        private final String reasonCode;
        private final ClinVarComparatorSidecar.Provenance snapshotProvenance;

        private CandidateDecision(ClinVarTabixProvider.ComparatorCandidate candidate,
                                  String status,
                                  String reasonCode,
                                  ClinVarComparatorSidecar.Provenance snapshotProvenance) {
            this.exactAllele = candidate.getExactAllele();
            this.variationId = String.valueOf(candidate.getVariationId());
            this.status = status;
            this.reasonCode = reasonCode;
            this.snapshotProvenance = snapshotProvenance;
        }

        private static CandidateDecision ready(
                ClinVarTabixProvider.ComparatorCandidate candidate,
                ClinVarComparatorSidecar.Provenance provenance) {
            return new CandidateDecision(candidate, "READY", null, provenance);
        }

        private static CandidateDecision notEvaluated(
                ClinVarTabixProvider.ComparatorCandidate candidate, String reasonCode) {
            return new CandidateDecision(candidate, "NOT_EVALUATED", reasonCode, null);
        }

        private static CandidateDecision notApplicable(
                ClinVarTabixProvider.ComparatorCandidate candidate, String reasonCode) {
            return new CandidateDecision(candidate, "NOT_APPLICABLE", reasonCode, null);
        }

        private static CandidateDecision omitted(
                ClinVarTabixProvider.ComparatorCandidate candidate,
                String status,
                String reasonCode,
                ClinVarComparatorSidecar.Provenance provenance) {
            return new CandidateDecision(candidate, status, reasonCode, provenance);
        }

        public NormalizedVariantKey getExactAllele() { return exactAllele; }
        public String getVariationId() { return variationId; }
        public String getStatus() { return status; }
        public String getReasonCode() { return reasonCode; }
        public ClinVarComparatorSidecar.Provenance getSnapshotProvenance() {
            return snapshotProvenance;
        }
    }
}
