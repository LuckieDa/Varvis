package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.engine.EngineExecutionResult;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.AssemblyResult;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.Attempt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Request-scoped trusted preparation. Consumers get copies, never the formal ranking objects. */
public final class SelectedEvidencePreparation {
    private final ObjectMapper mapper;
    private final AssemblyResult assembly;
    private final List<ExplicitContextSourceLedger> sources;
    private final ObjectNode provenance;
    private final Map<String, ObjectNode> candidates = new LinkedHashMap<>();
    private final boolean pp1SelectionOnly;
    private final SelectedQueryAnnotations queryAnnotations;
    private boolean queryAnnotationsPrepared;
    private record ProjectionKey(String candidate, String hypothesis, boolean sequence) { }
    private final Map<ProjectionKey, LegacyContextProjection> projections = new LinkedHashMap<>();

    SelectedEvidencePreparation(ObjectMapper mapper, AssemblyResult assembly, List<ExplicitContextSourceLedger> sources,
            NativeSnvSourceProducer.Production production, EngineExecutionResult execution, List<VariantInterpretation> ranked) {
        this(mapper, assembly, sources, production, execution, ranked, false);
    }

    SelectedEvidencePreparation(ObjectMapper mapper, AssemblyResult assembly, List<ExplicitContextSourceLedger> sources,
            NativeSnvSourceProducer.Production production, EngineExecutionResult execution, List<VariantInterpretation> ranked,
            boolean pp1SelectionOnly) {
        this.pp1SelectionOnly = pp1SelectionOnly;
        this.mapper = mapper;
        this.assembly = assembly;
        this.sources = new java.util.ArrayList<>(sources);
        this.queryAnnotations = new SelectedQueryAnnotations(mapper);
        this.provenance = production.provenance();
        if (!production.belongsTo(execution)) throw new IllegalArgumentException("SELECTED_EXECUTION_SOURCE_MISMATCH");
        for (String id : assembly.candidateIds()) {
            var matches = ranked.stream().filter(c -> id.equals(c.getCandidateId())).toList();
            if (matches.size() == 1) candidates.put(id, mapper.valueToTree(matches.get(0)));
        }
    }

    public AssemblyResult assembly() { return mapper.convertValue(assembly, AssemblyResult.class); }
    public List<ExplicitContextSourceLedger> sources() { return List.copyOf(sources); }
    public ObjectNode provenance() { return provenance.deepCopy(); }

    /** Runs before request binding/receipt persistence; consumers never launch annotation work. */
    synchronized void prepareQueryAnnotations(SelectedQueryAnnotations.Provider provider,
            org.nbscn.varvis.variantanalysis.domain.AnalysisContext analysis) {
        if (pp1SelectionOnly || queryAnnotationsPrepared) return;
        queryAnnotationsPrepared = true;
        projections.clear(); // Annotation preparation changes provenance and the query view.
        var work = new java.util.ArrayList<VariantInterpretation>();
        String dataClass = null;
        for (var row : assembly.candidates()) for (var attempt : row.attempts()) {
            if (!attempt.blockers().isEmpty() || attempt.context() == null || !attempt.context().path("transcript").isObject()) continue;
            var candidate = queryCandidate(row.candidateId(), attempt.hypothesisId(), false);
            if (formalCandidate(row.candidateId()).at("/observation/key").equals(mapper.valueToTree(candidate.getObservation().getKey()))) continue;
            String currentClass = attempt.context().path("dataClass").asText();
            if (dataClass != null && !dataClass.equals(currentClass)) throw new IllegalArgumentException("QUERY_ANNOTATION_DATA_CLASS_CONFLICT");
            dataClass = currentClass; work.add(candidate);
        }
        queryAnnotations.prepare(work, analysis, dataClass, provider);
        sources.addAll(queryAnnotations.sources());
        if (!work.isEmpty()) {
            var refs = provenance.putArray("queryAnnotationSources");
            for (var source : queryAnnotations.sources()) refs.add(mapper.valueToTree(source.reference("annotation")));
        }
    }

    SourceRef queryAnnotationSource(String candidateId, String hypothesisId) {
        return queryAnnotations.reference(queryCandidate(candidateId, hypothesisId, false).getObservation().getKey());
    }

    /** Explicit compatibility for old transcript-bound evaluators; never replaces the prepared v3 context. */
    public synchronized LegacyContextProjection legacyProjection(String candidateId, String hypothesisId) {
        return projections.computeIfAbsent(new ProjectionKey(candidateId, hypothesisId, false),
                ignored -> new LegacyContextProjection(mapper, this, candidateId, hypothesisId));
    }

    /** Carries original annotation as an unverified claim for complete CDS/protein reconstruction.
     * Only a sequence-verifying consumer may use this; it does not authorize annotation-only inference. */
    public synchronized LegacyContextProjection codingSequenceProjection(String candidateId, String hypothesisId) {
        return projections.computeIfAbsent(new ProjectionKey(candidateId, hypothesisId, true),
                ignored -> new LegacyContextProjection(mapper, this, candidateId, hypothesisId, true));
    }

    public JsonNode resolve(SourceRef ref) {
        var found = new java.util.ArrayList<JsonNode>();
        for (var source : sources) try { found.add(source.resolve(ref)); } catch (IllegalArgumentException ignored) { }
        if (found.size() != 1) throw new IllegalArgumentException("NORMALIZATION_SOURCE_REFERENCE_AMBIGUOUS_OR_MISSING");
        return found.get(0).deepCopy();
    }

    public ObjectNode context(String candidateId, String hypothesisId) {
        if (pp1SelectionOnly) throw new IllegalArgumentException("PP1_SELECTION_NOT_GENERAL_EVIDENCE_CONTEXT");
        return checkedContext(candidateId, hypothesisId);
    }

    /** Identity and explicit hypothesis only. No inference of disease validity or PP1 eligibility. */
    public ObjectNode pp1SelectionContext(String candidateId, String hypothesisId) {
        return checkedContext(candidateId, hypothesisId);
    }

    private ObjectNode checkedContext(String candidateId, String hypothesisId) {
        var selected = attempt(candidateId, hypothesisId);
        var context = selected.context();
        if (!selected.blockers().isEmpty()) throw new IllegalArgumentException(selected.blockers().get(0).reasonCode());
        if (context == null) throw new IllegalArgumentException("SELECTED_CONTEXT_UNAVAILABLE");
        return context;
    }

    /** Full original snapshot, including the original ALT index, genotype and phase witnesses. */
    public ObjectNode formalCandidate(String candidateId) {
        var value = candidates.get(candidateId);
        if (value == null) throw new IllegalArgumentException("SELECTED_CANDIDATE_AMBIGUOUS_OR_MISSING");
        return value.deepCopy();
    }

    /** Changed coordinates use separately prepared query annotations; old annotations are never a fallback. */
    public VariantInterpretation queryCandidate(String candidateId, String hypothesisId, boolean requireAnnotations) {
        return queryCandidate(candidateId, hypothesisId, requireAnnotations, false);
    }

    VariantInterpretation codingSequenceCandidate(String candidateId, String hypothesisId) {
        return queryCandidate(candidateId, hypothesisId, true, true);
    }

    private VariantInterpretation queryCandidate(String candidateId, String hypothesisId, boolean requireAnnotations,
            boolean sequenceVerificationRequired) {
        var context = context(candidateId, hypothesisId);
        var query = context.at("/variant/evidenceQueryAllele");
        var key = new NormalizedVariantKey(query.path("assembly").asText(), query.path("contig").asText(),
                query.path("position").asInt(), query.path("reference").asText(), query.path("alternate").asText());
        var copy = formalCandidate(candidateId);
        JsonNode newKey = mapper.valueToTree(key);
        boolean changed = !copy.at("/observation/key").equals(newKey);
        var originalAnnotations = copy.path("transcriptAnnotations").deepCopy();
        if (changed) {
            ((ObjectNode) copy.path("observation")).set("key", newKey);
            copy.put("variantId", key.canonicalId());
            ((ObjectNode) copy.path("candidateIdentity")).put("canonicalVariantId", key.canonicalId())
                    .put("source", "NORMALIZATION_QUERY").putNull("sourceOrdinal").putNull("declaredRank");
            copy.putArray("consequences");
            if (!sequenceVerificationRequired) copy.putArray("transcriptAnnotations");
            copy.putArray("evidence");
            copy.putArray("acmgEvidenceSuggestions");
            copy.putNull("exomiserCandidateFeatures");
            if (requireAnnotations && !sequenceVerificationRequired)
                copy.set("transcriptAnnotations", queryAnnotations.annotations(key, context, originalAnnotations));
        }
        try {
            // This is our own DTO snapshot, with derived getter aliases; it is not a caller-supplied payload.
            VariantInterpretation result = mapper.readerFor(VariantInterpretation.class)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(copy);
            if (result == null) throw new IllegalArgumentException("SELECTED_QUERY_VIEW_INVALID");
            if (requireAnnotations) {
                String transcript = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
                if (result.getTranscriptAnnotations() == null || result.getTranscriptAnnotations().stream()
                        .filter(t -> t != null && transcript.equals(t.getVersionedTranscriptId())
                                && context.at("/transcript/annotationSourceVersion").asText().equals(t.getVersion())).count() != 1)
                    throw new IllegalArgumentException("SELECTED_TRANSCRIPT_ANNOTATION_UNAVAILABLE");
            }
            return result;
        }
        catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("SELECTED_QUERY_VIEW_INVALID", invalid);
        }
    }

    private Attempt attempt(String candidateId, String hypothesisId) {
        var found = assembly.candidates().stream().filter(c -> Objects.equals(c.candidateId(), candidateId))
                .flatMap(c -> c.attempts().stream()).filter(a -> Objects.equals(a.hypothesisId(), hypothesisId)).toList();
        if (found.size() != 1) throw new IllegalArgumentException("SELECTED_ATTEMPT_AMBIGUOUS_OR_MISSING");
        return found.get(0);
    }
}
