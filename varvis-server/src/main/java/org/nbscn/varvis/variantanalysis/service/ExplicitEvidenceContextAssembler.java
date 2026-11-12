package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.utils.CoordinateUtils;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.BoundedSequenceDeletion;
import org.nbscn.varvis.variantanalysis.domain.NormalizationAlleleMapping;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.DiseaseContextBinding;
import org.nbscn.varvis.variantanalysis.service.TopNAcmgContextPlanner.ContextSeed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit, opt-in assembly of native GRCh38 contexts. No provider or patient workflow is invoked.
 * Sources are caller-trusted, checksum-verified receipts, not clinical validation or new producers.
 * Raw sample association is checked in memory and is deliberately absent from the returned provenance.
 */
public final class ExplicitEvidenceContextAssembler {
    public static final String ACCEPTANCE_SCOPE = "NATIVE_GRCH38_EXPLICIT_INPUTS";
    private final ObjectMapper mapper;
    private final PatientDiseaseContextBindingResolver bindingResolver;
    private final TopNAcmgContextPlanner planner;
    private final List<ExplicitContextSourceLedger> sources;
    private final boolean convertedBa1;
    private final boolean bs1Context;

    public ExplicitEvidenceContextAssembler(ObjectMapper mapper,
                                            PatientDiseaseContextBindingResolver bindingResolver,
                                            List<ExplicitContextSourceLedger> sources) {
        this(mapper, bindingResolver, sources, false);
    }

    ExplicitEvidenceContextAssembler(ObjectMapper mapper, PatientDiseaseContextBindingResolver bindingResolver,
                                     List<ExplicitContextSourceLedger> sources, boolean convertedBa1) {
        this(mapper, bindingResolver, sources, convertedBa1, false);
    }

    ExplicitEvidenceContextAssembler(ObjectMapper mapper, PatientDiseaseContextBindingResolver bindingResolver,
                                     List<ExplicitContextSourceLedger> sources, boolean convertedBa1, boolean bs1Context) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.bindingResolver = Objects.requireNonNull(bindingResolver, "bindingResolver");
        this.planner = new TopNAcmgContextPlanner(mapper, convertedBa1);
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.convertedBa1 = convertedBa1;
        this.bs1Context = bs1Context;
    }

    /** Selects rows once, preserving duplicates, order and failures; never fills a failed row. */
    public AssemblyResult assemble(AnalysisContext analysis, List<VariantInterpretation> rankedCandidates,
                                   int requestedN, List<ExplicitInput> inputs) {
        return assemble(analysis, rankedCandidates, requestedN, inputs, (candidate, input) -> List.of());
    }

    // Additional execution association gates run before cross-input consistency; failed sources cannot poison siblings.
    AssemblyResult assemble(AnalysisContext analysis, List<VariantInterpretation> rankedCandidates,
                             int requestedN, List<ExplicitInput> inputs,
                             java.util.function.BiFunction<VariantInterpretation, ExplicitInput, List<Blocker>> inputGate) {
        Objects.requireNonNull(analysis, "analysis");
        Objects.requireNonNull(rankedCandidates, "rankedCandidates");
        if (requestedN <= 0) throw new IllegalArgumentException("TOP_N_MUST_BE_POSITIVE");
        List<ExplicitInput> supplied = inputs == null ? List.of() : inputs;
        List<VariantInterpretation> selected = rankedCandidates.subList(0, Math.min(requestedN, rankedCandidates.size()));
        List<String> ids = selected.stream().map(candidate -> candidate == null ? null : candidate.getCandidateId()).toList();
        Map<String, Long> counts = new HashMap<>();
        ids.forEach(id -> counts.merge(id, 1L, (left, right) -> left + right));
        List<CandidateAssembly> rows = new ArrayList<>();
        for (VariantInterpretation candidate : selected) {
            if (candidate == null) {
                rows.add(new CandidateAssembly(null, List.of(failed(null, null, "CANDIDATE_REQUIRED", "candidate"))));
                continue;
            }
            List<ExplicitInput> matching = supplied.stream().filter(Objects::nonNull)
                    .filter(input -> Objects.equals(candidate.getCandidateId(), input.candidateId())).toList();
            List<Attempt> attempts = new ArrayList<>();
            if (matching.isEmpty()) attempts.add(failed(candidate.getCandidateId(), null, "EXPLICIT_INPUT_MISSING", "input"));
            for (ExplicitInput input : matching) {
                if (counts.get(candidate.getCandidateId()) > 1) {
                    attempts.add(failed(candidate.getCandidateId(), input, "CANDIDATE_ID_AMBIGUOUS", "candidateId"));
                } else if (matching.stream().filter(other -> Objects.equals(other.hypothesisId(), input.hypothesisId())).count() > 1) {
                    attempts.add(failed(candidate.getCandidateId(), input, "HYPOTHESIS_ID_AMBIGUOUS", "hypothesisId"));
                } else {
                    var sourceBlockers = inputGate.apply(candidate, input);
                    if (bs1Context && !sourceBlockers.isEmpty()) {
                        attempts.add(new Attempt(candidate.getCandidateId(), input.hypothesisId(), input.binding(), provenance(input), null, null, sourceBlockers));
                        continue;
                    }
                    Attempt assembled = assembleOne(analysis, candidate, input);
                    List<Blocker> blockers = new ArrayList<>(assembled.blockers());
                    blockers.addAll(sourceBlockers);
                    attempts.add(blockers.isEmpty() ? assembled : new Attempt(assembled.candidateId(), assembled.hypothesisId(),
                            assembled.binding(), assembled.provenance(), null, null, blockers));
                }
            }
            rows.add(new CandidateAssembly(candidate.getCandidateId(), attempts));
        }
        List<Attempt> unmatched = new ArrayList<>();
        for (ExplicitInput input : supplied) {
            if (input != null && !hasText(input.candidateId())) {
                var original = inputGate.apply(null, input);
                if (!original.isEmpty()) {
                    unmatched.add(failed(null, input, original.get(0).reasonCode(), original.get(0).field()));
                    continue;
                }
            }
            if (input == null || !hasText(input.candidateId()) || !ids.contains(input.candidateId())) {
                unmatched.add(failed(input == null ? null : input.candidateId(), input,
                        "INPUT_OUTSIDE_SELECTED_TOP_N", "candidateId"));
            }
        }
        return new AssemblyResult(analysis.getAnalysisId(), requestedN, ids.size(), ids,
                verifyPatientConsistency(selected, rows), unmatched, false);
    }

    /** Only verified successful inputs can establish a conflicting patient assertion for a sibling. */
    private List<CandidateAssembly> verifyPatientConsistency(List<VariantInterpretation> candidates, List<CandidateAssembly> rows) {
        Map<String, PatientClaim> byPatient = new HashMap<>();
        Map<String, PatientClaim> bySample = new HashMap<>();
        Map<PatientDiseaseKey, String> affectedByDisease = new HashMap<>();
        Set<String> conflictingPatients = new HashSet<>();
        Set<PatientDiseaseKey> conflictingDiseases = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            for (Attempt attempt : rows.get(index).attempts()) {
                if (attempt.context() == null) continue;
                JsonNode patient = attempt.context().path("patientContext");
                String patientId = text(patient, "patientContextId");
                PatientClaim claim = new PatientClaim(patientId, candidates.get(index).getObservation().getSampleId(),
                        text(patient, "role"), text(patient, "sex"), nullableText(patient, "familyContextId"),
                        text(patient, "affectedStatus"));
                PatientClaim previous = byPatient.putIfAbsent(patientId, claim);
                if (previous != null && (!Objects.equals(previous.sampleId(), claim.sampleId())
                        || !Objects.equals(previous.role(), claim.role()) || !Objects.equals(previous.sex(), claim.sex())
                        || !Objects.equals(previous.familyId(), claim.familyId()))) {
                    conflictingPatients.add(patientId);
                }
                previous = bySample.putIfAbsent(claim.sampleId(), claim);
                if (previous != null && !previous.patientId().equals(patientId)) {
                    conflictingPatients.add(patientId);
                    conflictingPatients.add(previous.patientId());
                }
                // Input consistency only: disease identity comes from the validated context,
                // whose MONDO was assembled from the revalidated binding, not a local hypothesis label.
                PatientDiseaseKey diseaseKey = patientDiseaseKey(attempt.context());
                String previousAffected = affectedByDisease.putIfAbsent(diseaseKey, claim.affectedStatus());
                if (previousAffected != null && !previousAffected.equals(claim.affectedStatus())) {
                    conflictingDiseases.add(diseaseKey);
                }
            }
        }
        return rows.stream().map(row -> new CandidateAssembly(row.candidateId(), row.attempts().stream().map(attempt -> {
            if (attempt.context() == null) return attempt;
            PatientDiseaseKey diseaseKey = patientDiseaseKey(attempt.context());
            boolean patientConflict = conflictingPatients.contains(diseaseKey.patientId());
            if (!patientConflict && !conflictingDiseases.contains(diseaseKey)) {
                return attempt;
            }
            return new Attempt(attempt.candidateId(), attempt.hypothesisId(), attempt.binding(), attempt.provenance(), null, null,
                    List.of(new Blocker("CONTEXT_NOT_READY",
                            patientConflict ? "PATIENT_CONTEXT_CROSS_INPUT_CONFLICT" : "PATIENT_DISEASE_AFFECTED_STATUS_CONFLICT",
                            patientConflict ? "patient" : "patient.affectedStatus")));
        }).toList())).toList();
    }

    private static PatientDiseaseKey patientDiseaseKey(JsonNode verifiedContext) {
        return new PatientDiseaseKey(text(verifiedContext.path("patientContext"), "patientContextId"),
                text(verifiedContext.path("disease"), "mondoId"));
    }

    private Attempt assembleOne(AnalysisContext analysis, VariantInterpretation candidate, ExplicitInput input) {
        try {
            require(hasText(input.hypothesisId()), "HYPOTHESIS_ID_REQUIRED", "hypothesisId");
            require(List.of("SYNTHETIC", "PRIVACY_BOUNDED_RUNTIME").contains(orEmpty(input.dataClass())),
                    "DATA_CLASS_REQUIRED", "dataClass");
            DiseaseContextBinding binding = verifyBinding(candidate, input);
            verifyPatient(analysis, candidate, input, binding);
            verifyTranscript(analysis, candidate, input, binding);
            String status = verifyNormalization(analysis, candidate, input);
            ContextSeed seed = new ContextSeed(candidate.getCandidateId(), draft(input, binding, status, candidate));
            if (bs1Context) {
                var adapter = new org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter(mapper);
                ObjectNode context = seed.draft().deepCopy();
                context.put("schemaVersion", "evidence-context-v3").put("canonicalizationProfile", "varvis-json-c14n-v1");
                ((ObjectNode) context.path("variant")).put("alleleId", adapter.canonicalAlleleId(
                        context.at("/variant/normalizedAllele"), context.at("/variant/normalization")));
                context.putObject("contextCompleteness").put("status", "COMPLETE").putArray("missingFields");
                context.put("contextId", adapter.canonicalVersionedContextId(context));
                adapter.validateVersionedContext(context);
                return new Attempt(candidate.getCandidateId(), input.hypothesisId(), binding, provenance(input), seed, context, List.of());
            }
            TopNAcmgContextPlanner.CandidatePlan planned = planner.plan(analysis, List.of(candidate), 1, List.of(seed))
                    .candidates().get(0);
            if (!planned.blockers().isEmpty()) {
                var blocker = planned.blockers().get(0);
                return failed(candidate.getCandidateId(), input, blocker.reasonCode(), blocker.field());
            }
            require(planned.readyContexts().size() == 1, "CONTEXT_CONTRACT_INVALID", "context");
            return new Attempt(candidate.getCandidateId(), input.hypothesisId(), input.binding(), provenance(input),
                    seed, planned.readyContexts().get(0), List.of());
        } catch (Blocked failure) {
            return failed(candidate.getCandidateId(), input, failure.reason, failure.field);
        } catch (IllegalArgumentException failure) {
            return failed(candidate.getCandidateId(), input, "EXPLICIT_INPUT_INVALID", "input");
        }
    }

    private DiseaseContextBinding verifyBinding(VariantInterpretation candidate, ExplicitInput input) {
        DiseaseContextBinding binding = input.binding();
        if (binding == null) { throw new Blocked("BINDING_REQUIRED", "binding"); }
        require(bindingResolver.acceptsPreparation(binding),
                !bindingResolver.pp1SelectionContexts() || binding.reasonCode() == null
                        ? "BINDING_NOT_READY" : binding.reasonCode(), "binding.status");
        require(Objects.equals(input.candidateId(), binding.candidateId())
                        && Objects.equals(input.hypothesisId(), binding.hypothesisId()),
                "BINDING_INPUT_IDENTITY_CONFLICT", "binding");
        require(binding.provenance() != null && binding.provenance().patientHypothesis() != null,
                "BINDING_PROVENANCE_REQUIRED", "binding.provenance");
        var recomputed = bindingResolver.resolve(candidate, List.of(binding.provenance().patientHypothesis()));
        require(recomputed.bindings().size() == 1 && recomputed.blockers().isEmpty()
                        && bindingResolver.acceptsPreparation(recomputed.bindings().get(0)),
                "BINDING_REVALIDATION_FAILED", "binding");
        require(binding.equals(recomputed.bindings().get(0)), "BINDING_PROVENANCE_CONFLICT", "binding");
        return recomputed.bindings().get(0);
    }

    private void verifyPatient(AnalysisContext analysis, VariantInterpretation candidate, ExplicitInput input,
                               DiseaseContextBinding binding) {
        PatientContext patient = input.patient();
        if (patient == null) { throw new Blocked("PATIENT_CONTEXT_REQUIRED", "patient"); }
        require(hasText(patient.patientContextId()) && hasText(patient.role()) && hasText(patient.affectedStatus())
                        && hasText(patient.sex()), "PATIENT_FIELD_MISSING", "patient");
        JsonNode record = record(patient.source(), input.dataClass(), "PATIENT_CONTEXT", "patient.source");
        association(record, analysis, input, "patient.source");
        require(candidate.getObservation() != null && hasText(candidate.getObservation().getSampleId())
                        && candidate.getObservation().getSampleId().equals(text(record, "sampleId")),
                "PATIENT_SAMPLE_ASSOCIATION_CONFLICT", "patient.source");
        same(record, "role", patient.role(), "PATIENT_CONTEXT_CONFLICT");
        same(record, "affectedStatus", patient.affectedStatus(), "PATIENT_CONTEXT_CONFLICT");
        same(record, "sex", patient.sex(), "PATIENT_CONTEXT_CONFLICT");
        require(Objects.equals(nullableText(record, "familyContextId"), patient.familyContextId()),
                "PATIENT_CONTEXT_CONFLICT", "familyContextId");
        JsonNode hypothesis = record.path("hypothesis");
        same(hypothesis, "hgncId", binding.hgncId(), "PATIENT_HYPOTHESIS_CONFLICT");
        same(hypothesis, "mondoId", binding.mondoId(), "PATIENT_HYPOTHESIS_CONFLICT");
        same(hypothesis, "moi", binding.normalizedMoi(), "PATIENT_HYPOTHESIS_CONFLICT");
        require(mapper.valueToTree(binding.provenance().patientHypothesis().provenance())
                        .equals(hypothesis.path("provenance")),
                "PATIENT_HYPOTHESIS_CONFLICT", "hypothesis.provenance");
    }

    // Shared by the internal declaration intake; no normalization or context is invented for this check.
    Blocker verifySupplement(AnalysisContext analysis, VariantInterpretation candidate, ExplicitInput input) {
        try {
            DiseaseContextBinding binding = verifyBinding(candidate, input);
            verifyPatient(analysis, candidate, input, binding);
            verifyTranscript(analysis, candidate, input, binding);
            return null;
        } catch (Blocked failure) {
            return new Blocker("CONTEXT_NOT_READY", failure.reason, failure.field);
        } catch (IllegalArgumentException failure) {
            return new Blocker("CONTEXT_NOT_READY", "EXPLICIT_INPUT_INVALID", "input");
        }
    }

    private void verifyTranscript(AnalysisContext analysis, VariantInterpretation candidate, ExplicitInput input,
                                  DiseaseContextBinding binding) {
        // Contract completeness is separate from the selected BS1 policy's requirements receipt.
        if (bs1Context && input.transcript() == null) return;
        TranscriptChoice choice = input.transcript();
        if (choice == null) { throw new Blocked("TRANSCRIPT_CHOICE_REQUIRED", "transcript"); }
        require(hasText(choice.accession()) && choice.version() != null && choice.version() > 0,
                "TRANSCRIPT_VERSION_REQUIRED", "transcript.version");
        require(hasText(choice.selectionPolicy()) && hasText(choice.annotationSource())
                        && hasText(choice.annotationSourceVersion()), "TRANSCRIPT_SOURCE_REQUIRED", "transcript");
        JsonNode record = record(choice.source(), input.dataClass(), "TRANSCRIPT_CHOICE", "transcript.source");
        association(record, analysis, input, "transcript.source");
        for (String field : List.of("accession", "version", "selectionPolicy", "annotationSource", "annotationSourceVersion")) {
            require(mapper.valueToTree(choice).path(field).equals(record.path(field)),
                    "TRANSCRIPT_SOURCE_CONFLICT", "transcript." + field);
        }
        same(record, "hgncId", binding.hgncId(), "TRANSCRIPT_GENE_CONFLICT");
        same(record, "geneSymbol", binding.symbol(), "TRANSCRIPT_GENE_CONFLICT");
        require(safeLocator(text(record, "annotationRecordLocator")), "TRANSCRIPT_SOURCE_REQUIRED", "annotationRecordLocator");
        selectedTranscript(candidate, binding, choice);
    }

    // One exact selection implementation for both old receipts and newly received declarations.
    static TranscriptConsequence selectedTranscript(VariantInterpretation candidate, DiseaseContextBinding binding,
                                                    TranscriptChoice choice) {
        if (choice == null) { throw new Blocked("TRANSCRIPT_CHOICE_REQUIRED", "transcript"); }
        require(hasText(choice.accession()) && choice.version() != null && choice.version() > 0,
                "TRANSCRIPT_VERSION_REQUIRED", "transcript.version");
        require(hasText(choice.selectionPolicy()) && hasText(choice.annotationSource()) && hasText(choice.annotationSourceVersion()),
                "TRANSCRIPT_SOURCE_REQUIRED", "transcript");
        String selected = choice.accession() + "." + choice.version();
        // The caller chooses explicitly. Flags and consequence order never choose a transcript here.
        List<TranscriptConsequence> annotations = candidate.getTranscriptAnnotations();
        if (!(annotations != null && annotations.stream().noneMatch(Objects::isNull))) { throw new Blocked("TRANSCRIPT_ANNOTATION_INVALID", "transcript"); }
        List<TranscriptConsequence> matches = annotations.stream().filter(annotation ->
                binding.symbol().equals(annotation.getGeneSymbol())
                        && selected.equals(annotation.getVersionedTranscriptId())
                        && choice.version().equals(annotation.getTranscriptVersion())
                        && choice.annotationSource().equals(annotation.getSource())
                        && choice.annotationSourceVersion().equals(annotation.getVersion())
                        && "COMPLETED".equals(annotation.getStatus())).toList();
        require(matches.size() == 1, matches.isEmpty() ? "TRANSCRIPT_ANNOTATION_NOT_MATCHED" : "TRANSCRIPT_ANNOTATION_AMBIGUOUS",
                "transcript");
        TranscriptConsequence annotation = matches.get(0);
        String coding = annotation.getHgvsc();
        String codingIdentity = coding == null || !coding.contains(":") ? null : coding.substring(0, coding.indexOf(':'));
        for (String identity : new String[]{annotation.getTranscriptAccession(), annotation.getTranscriptId(), codingIdentity}) {
            require(!hasText(identity) || identity.equals(choice.accession()) || identity.equals(selected),
                    "TRANSCRIPT_IDENTITY_CONFLICT", "transcript");
        }
        return annotation;
    }

    private String verifyNormalization(AnalysisContext analysis, VariantInterpretation candidate, ExplicitInput input) {
        NormalizationInput normalization = input.normalization();
        if (normalization == null) { throw new Blocked("NORMALIZATION_PROOF_REQUIRED", "normalization"); }
        AssemblyLineage lineage = normalization.lineage();
        if (!(lineage != null && hasText(lineage.originalInputAssembly()) && hasText(lineage.preRankingMappingStatus())
                        && lineage.originalInputAllele() != null)) { throw new Blocked("ORIGINAL_ASSEMBLY_LINEAGE_REQUIRED", "normalization.lineage"); }
        if (convertedBa1) return verifyConvertedNormalization(analysis, candidate, input, normalization, lineage);
        if (bs1Context && selectedNormalizationMethod(normalization.method()))
            return verifySelectedNormalization(analysis, candidate, input);
        // Preserve supplied original coordinates and transformation identity in every outer outcome.
        require("GRCh38".equals(lineage.originalInputAssembly())
                        && "GRCh38".equals(lineage.originalInputAllele().assembly())
                        && "NATIVE".equals(lineage.preRankingMappingStatus()) && lineage.transformationId() == null,
                "PRE_RANKING_LIFTOVER_UNSUPPORTED", "normalization.lineage");
        JsonNode lineageRecord = record(lineage.source(), input.dataClass(), "ASSEMBLY_LINEAGE", "normalization.lineage.source");
        association(lineageRecord, analysis, input, "normalization.lineage.source");
        for (String field : List.of("originalInputAssembly", "preRankingMappingStatus", "originalInputAllele")) {
            require(mapper.valueToTree(lineage).path(field).equals(lineageRecord.path(field)),
                    "ASSEMBLY_LINEAGE_CONFLICT", field);
        }
        require(nullableText(lineageRecord, "transformationId") == null, "ASSEMBLY_LINEAGE_CONFLICT", "transformationId");
        require(normalization.originalAllele() != null && normalization.normalizedAllele() != null
                        && "GRCh38".equals(normalization.originalAllele().assembly())
                        && "GRCh38".equals(normalization.normalizedAllele().assembly())
                        && Objects.equals(lineage.originalInputAllele(), normalization.originalAllele()),
                "ORIGINAL_ALLELE_CONFLICT", "normalization.originalAllele");
        for (Allele allele : List.of(normalization.originalAllele(), normalization.normalizedAllele())) {
            require(hasText(allele.contig()) && allele.position() > 0 && hasText(allele.reference()) && hasText(allele.alternate()),
                    "ALLELE_FIELDS_REQUIRED", "normalization.allele");
        }
        require(candidate.getObservation() != null && candidate.getObservation().getKey() != null,
                "CANDIDATE_ALLELE_MISSING", "candidate");
        NormalizedVariantKey key = candidate.getObservation().getKey();
        require(allele(key).equals(normalization.normalizedAllele()), "CANDIDATE_NORMALIZED_ALLELE_CONFLICT", "normalizedAllele");
        NormalizationAlleleMapping mapping = NormalizationAlleleMapping.from(normalization.originalRecord(), key);
        require(mapping.isResolved(), "ORIGINAL_RECORD_REQUIRED", "normalization.originalRecord");
        require(Objects.equals(mapping.getRawAlternateIndex(), normalization.selectedAltIndex()),
                "ORIGINAL_ALT_INDEX_CONFLICT", "normalization.selectedAltIndex");
        Allele original = new Allele(key.getGenomeAssembly(), CoordinateUtils.normalizeChromosome(mapping.getRawChromosome()),
                mapping.getRawPosition(), mapping.getRawReference(), mapping.getRawAlternates().get(mapping.getRawAlternateIndex() - 1));
        require(original.equals(normalization.originalAllele()), "ORIGINAL_ALLELE_CONFLICT", "normalization.originalAllele");
        var candidateMapping = candidate.getObservation().normalizationMapping();
        require(!candidateMapping.isResolved() || (Objects.equals(candidateMapping.getRawRecordId(), mapping.getRawRecordId())
                        && Objects.equals(candidateMapping.getRawAlternateIndex(), mapping.getRawAlternateIndex())),
                "CANDIDATE_ORIGINAL_RECORD_CONFLICT", "normalization.originalRecord");
        JsonNode proof = record(normalization.source(), input.dataClass(), "NORMALIZATION", "normalization.source");
        association(proof, analysis, input, "normalization.source");
        for (String field : List.of("originalRecord", "originalAllele", "normalizedAllele", "selectedAltIndex", "method", "methodVersion", "reference")) {
            require(mapper.valueToTree(normalization).path(field).equals(proof.path(field)),
                    "NORMALIZATION_PROOF_CONFLICT", "normalization." + field);
        }
        require(hasText(normalization.method()) && hasText(normalization.methodVersion())
                        && "SUCCEEDED".equals(text(proof, "executionStatus")),
                "NORMALIZATION_PROOF_REQUIRED", "normalization.source");
        String status = NormalizationAlleleMapping.IDENTITY.equals(mapping.getStatus()) ? "UNCHANGED" : "NORMALIZED";
        same(proof, "status", status, "NORMALIZATION_PROOF_CONFLICT");
        require("MATCH".equals(text(proof, "refValidation"))
                        && normalization.originalAllele().reference().equals(text(proof, "inputObservedReference"))
                        && normalization.normalizedAllele().reference().equals(text(proof, "normalizedObservedReference")),
                "REF_VALIDATION_NOT_MATCH", "normalization.refValidation");
        require(hasText(text(proof, "refValidationMethod")) && hasText(text(proof, "refValidationVersion"))
                        && safeLocator(text(proof, "refValidationRecordLocator")),
                "REF_VALIDATION_PROOF_REQUIRED", "normalization.source");
        reference(normalization.reference(), input.dataClass());
        return status;
    }

    private static boolean selectedNormalizationMethod(String method) {
        return SelectedAlleleNormalizer.METHOD.equals(method) || BoundedSequenceDeletion.NORMALIZATION_METHOD.equals(method);
    }

    private String verifySelectedNormalization(AnalysisContext analysis, VariantInterpretation candidate, ExplicitInput input) {
        var n = input.normalization(); var proof = record(n.source(), input.dataClass(), "NORMALIZATION", "normalization.source");
        var lineage = record(n.lineage().source(), input.dataClass(), "ASSEMBLY_LINEAGE", "normalization.lineage.source");
        association(proof, analysis, input, "normalization.source"); association(lineage, analysis, input, "normalization.lineage.source");
        require("GRCh38".equals(n.lineage().originalInputAssembly()) && "NATIVE".equals(n.lineage().preRankingMappingStatus())
                && n.lineage().transformationId() == null && n.originalAllele().equals(n.lineage().originalInputAllele()),
                "ORIGINAL_ASSEMBLY_LINEAGE_CONFLICT", "normalization.lineage");
        for (String field : List.of("originalRecord", "originalAllele", "normalizedAllele", "selectedAltIndex", "method", "methodVersion", "reference"))
            require(mapper.valueToTree(n).path(field).equals(proof.path(field)), "NORMALIZATION_PROOF_CONFLICT", field);
        var key = candidate.getObservation().getKey();
        require(mapper.valueToTree(allele(key)).equals(proof.path("analysisAllele")) && allele(key).equals(n.originalAllele())
                && mapper.valueToTree(candidate.getObservation()).equals(proof.path("sampleObservation"))
                && mapper.valueToTree(candidate.getAnalysisVcfCall()).equals(proof.path("analysisVcfCall")),
                "CANDIDATE_ORIGINAL_RECORD_CONFLICT", "normalization.source");
        var mapping = NormalizationAlleleMapping.from(n.originalRecord(), key);
        require(NormalizationAlleleMapping.IDENTITY.equals(mapping.getStatus()) && Objects.equals(mapping.getRawAlternateIndex(), n.selectedAltIndex()),
                "ORIGINAL_ALT_INDEX_CONFLICT", "normalization.originalRecord");
        var equivalence = proof.path("normalizationProof");
        require("SUCCEEDED".equals(proof.path("executionStatus").asText()) && "MATCH".equals(proof.path("refValidation").asText())
                && "REFERENCE_HAPLOTYPE_EQUIVALENCE".equals(equivalence.path("equivalence").asText())
                && proof.path("analysisAllele").equals(equivalence.path("analysisAllele"))
                && proof.path("normalizedAllele").equals(equivalence.path("queryAllele"))
                && n.originalRecord().equals(equivalence.path("originalRecord").asText())
                && n.selectedAltIndex().equals(equivalence.path("selectedAltIndex").asInt())
                && "PASSED".equals(equivalence.at("/toolManifest/status").asText()), "NORMALIZATION_PROOF_REQUIRED", "normalization.source");
        var ref = reference(n.reference(), input.dataClass());
        require(ref.path("observedSha256").equals(equivalence.at("/toolManifest/configuredReferenceSha256")),
                "NORMALIZATION_REFERENCE_IDENTITY_CONFLICT", "normalization.reference");
        return "NORMALIZED";
    }

    private String verifyConvertedNormalization(AnalysisContext analysis, VariantInterpretation candidate, ExplicitInput input,
                                                NormalizationInput normalization, AssemblyLineage lineage) {
        require("GRCh37".equals(lineage.originalInputAssembly()) && "MAPPED".equals(lineage.preRankingMappingStatus())
                        && hasText(lineage.transformationId()) && normalization.originalAllele() != null
                        && normalization.normalizedAllele() != null
                        && "GRCh37".equals(normalization.originalAllele().assembly())
                        && "GRCh38".equals(normalization.normalizedAllele().assembly())
                        && Objects.equals(lineage.originalInputAllele(), normalization.originalAllele())
                        && ConvertedAlleleSource.METHOD.equals(normalization.method())
                        && ConvertedAlleleSource.VERSION.equals(normalization.methodVersion()),
                "CONVERSION_CONTEXT_LINEAGE_CONFLICT", "normalization.lineage");
        require(candidate.getObservation() != null && candidate.getObservation().getKey() != null
                        && allele(candidate.getObservation().getKey()).equals(normalization.normalizedAllele()),
                "CONVERSION_ANALYSIS_ALLELE_CONFLICT", "normalization.normalizedAllele");
        require(NativeSnvSourceProducer.isProvablyMinimal(normalization.originalAllele().reference(), normalization.originalAllele().alternate()),
                "CONVERSION_ORIGINAL_NORMALIZATION_REQUIRED", "normalization.originalAllele");
        JsonNode proof = record(normalization.source(), input.dataClass(), "NORMALIZATION", "normalization.source");
        JsonNode lineageProof = record(lineage.source(), input.dataClass(), "ASSEMBLY_LINEAGE", "normalization.lineage.source");
        association(proof, analysis, input, "normalization.source");
        association(lineageProof, analysis, input, "normalization.lineage.source");
        for (String field : List.of("originalRecord", "originalAllele", "normalizedAllele", "selectedAltIndex", "method", "methodVersion", "reference"))
            require(mapper.valueToTree(normalization).path(field).equals(proof.path(field)), "CONVERSION_NORMALIZATION_PROOF_CONFLICT", field);
        for (String field : List.of("originalInputAssembly", "preRankingMappingStatus", "originalInputAllele", "transformationId"))
            require(mapper.valueToTree(lineage).path(field).equals(lineageProof.path(field)), "CONVERSION_CONTEXT_LINEAGE_CONFLICT", field);
        require("SUCCEEDED".equals(proof.path("executionStatus").asText()) && "MATCH".equals(proof.path("refValidation").asText())
                        && "VERIFIED".equals(proof.at("/conversionEvidence/status").asText())
                        && proof.path("conversionEvidence").equals(lineageProof.path("conversionEvidence")),
                "CONVERSION_SOURCE_NOT_VERIFIED", "normalization.source");
        JsonNode targetReference = reference(normalization.reference(), input.dataClass());
        SourceRef originalRef = mapper.convertValue(proof.path("originalReference"), SourceRef.class);
        JsonNode originalReference = record(originalRef, input.dataClass(), "REFERENCE_IDENTITY", "normalization.originalReference");
        require("GRCh37".equals(originalReference.path("assembly").asText())
                        && "VERIFIED".equals(originalReference.path("verificationStatus").asText())
                        && originalReference.path("observedSha256").asText().matches("[0-9a-f]{64}")
                        && originalReference.path("observedSha256").equals(originalReference.path("expectedSha256"))
                        && originalReference.path("observedSha256").equals(proof.at("/conversionEvidence/originalReferenceSha256"))
                        && targetReference.path("observedSha256").equals(proof.at("/conversionEvidence/targetReferenceSha256")),
                "CONVERSION_REFERENCE_IDENTITY_CONFLICT", "normalization.reference");
        return "UNCHANGED"; // Normalization within the original assembly; conversion is a separate lineage step.
    }

    private JsonNode reference(SourceRef source, String dataClass) {
        JsonNode reference = record(source, dataClass, "REFERENCE_IDENTITY", "normalization.reference");
        require("GRCh38".equals(text(reference, "assembly"))
                        && text(reference, "observedSha256").matches("[0-9a-f]{64}")
                        && text(reference, "observedSha256").equals(text(reference, "expectedSha256"))
                        && "VERIFIED".equals(text(reference, "verificationStatus"))
                        && "SHA-256".equals(text(reference, "verificationMethod"))
                        && hasText(text(reference, "verificationVersion"))
                        && safeLocator(text(reference, "verificationRecordLocator"))
                        && safeLocator(text(reference, "referenceArtifactLocator")),
                "REFERENCE_IDENTITY_INSUFFICIENT", "normalization.reference");
        return reference;
    }

    private ObjectNode draft(ExplicitInput input, DiseaseContextBinding binding, String status, VariantInterpretation candidate) {
        ObjectNode draft = mapper.createObjectNode();
        draft.put("dataClass", input.dataClass());
        draft.putObject("scope").put("analysisDomain", "GERMLINE").put("diseaseModel", "MENDELIAN")
                .put("variantScope", "SMALL_SEQUENCE_VARIANT");
        NormalizationInput normalization = input.normalization();
        ObjectNode variant = draft.putObject("variant");
        variant.put("variantType", TopNAcmgContextPlanner.evidenceVariantType(candidate.getObservation().getKey()));
        if (bs1Context && selectedNormalizationMethod(normalization.method())) {
            var q = normalization.normalizedAllele();
            variant.put("variantType", TopNAcmgContextPlanner.evidenceVariantType(new NormalizedVariantKey(q.assembly(), q.contig(), q.position(), q.reference(), q.alternate())));
        }
        variant.set("inputAllele", mapper.valueToTree(normalization.originalAllele()));
        variant.set("normalizedAllele", mapper.valueToTree(normalization.normalizedAllele()));
        variant.set("evidenceQueryAllele", mapper.valueToTree(normalization.normalizedAllele()));
        variant.putObject("normalization").put("status", status).put("method", normalization.method())
                .put("methodVersion", normalization.methodVersion())
                .put("referenceSequenceSha256", text(reference(normalization.reference(), input.dataClass()), "observedSha256"))
                .put("refValidation", "MATCH");
        variant.putObject("assemblyLineage").put("mappingStatus", "NATIVE");
        if (convertedBa1) {
            JsonNode proof = record(normalization.source(), input.dataClass(), "NORMALIZATION", "normalization.source");
            JsonNode originalReference = record(mapper.convertValue(proof.path("originalReference"), SourceRef.class),
                    input.dataClass(), "REFERENCE_IDENTITY", "normalization.originalReference");
            variant.set("normalizedAllele", mapper.valueToTree(normalization.originalAllele()));
            variant.putObject("normalization").put("status", "UNCHANGED")
                    .put("method", NativeSnvSourceProducer.SMALL_VARIANT_METHOD).put("methodVersion", NativeSnvSourceProducer.VERSION)
                    .put("referenceSequenceSha256", originalReference.path("observedSha256").asText()).put("refValidation", "MATCH");
            ObjectNode mapping = variant.putObject("assemblyLineage").put("mappingStatus", "MAPPED").putObject("coordinateMapping");
            mapping.set("sourceAllele", mapper.valueToTree(normalization.originalAllele()));
            mapping.set("targetAllele", mapper.valueToTree(normalization.normalizedAllele()));
            mapping.put("method", normalization.method()).put("methodVersion", normalization.methodVersion()).put("refAltValidation", "MATCH");
        }
        draft.putObject("gene").put("hgncId", binding.hgncId()).put("symbol", binding.symbol());
        // An unlisted declared scope has an identifier, not an invented disease title.
        draft.putObject("disease").put("mondoId", binding.mondoId()).put("label",
                "DECLARED_SCOPE_ONLY".equals(binding.status()) ? binding.mondoId() : binding.diseaseLabel());
        draft.put("moi", binding.normalizedMoi());
        TranscriptChoice choice = input.transcript();
        if (choice != null) draft.putObject("transcript").put("accession", choice.accession()).put("version", choice.version())
                .put("selectionPolicy", choice.selectionPolicy()).put("annotationSourceVersion", choice.annotationSourceVersion());
        PatientContext patient = input.patient();
        ObjectNode projection = draft.putObject("patientContext").put("patientContextId", patient.patientContextId())
                .put("role", patient.role()).put("affectedStatus", patient.affectedStatus()).put("sex", patient.sex());
        if (patient.familyContextId() != null) projection.put("familyContextId", patient.familyContextId());
        return draft;
    }

    private JsonNode record(SourceRef ref, String dataClass, String kind, String field) {
        require(ref != null, "SOURCE_RECORD_REQUIRED", field);
        List<JsonNode> found = new ArrayList<>();
        for (ExplicitContextSourceLedger source : sources) {
            try {
                JsonNode record = source.resolve(ref);
                require(source.dataClass().equals(dataClass), "SOURCE_DATA_CLASS_CONFLICT", field);
                found.add(record);
            } catch (IllegalArgumentException ignored) {
                // Another explicitly supplied ledger may own the reference. Never fall back to a path or network.
            }
        }
        require(found.size() == 1, found.isEmpty() ? "SOURCE_RECORD_NOT_VERIFIED" : "SOURCE_RECORD_AMBIGUOUS", field);
        same(found.get(0), "kind", kind, "SOURCE_RECORD_KIND_CONFLICT");
        return found.get(0);
    }

    private void association(JsonNode record, AnalysisContext analysis, ExplicitInput input, String field) {
        require(analysis.getAnalysisId() != null && analysis.getAnalysisId().equals(text(record, "analysisId"))
                        && input.candidateId().equals(text(record, "candidateId"))
                        && input.hypothesisId().equals(text(record, "hypothesisId"))
                        && input.patient().patientContextId().equals(text(record, "patientContextId")),
                "INPUT_ASSOCIATION_CONFLICT", field);
    }

    private static Allele allele(NormalizedVariantKey key) {
        return new Allele(key.getGenomeAssembly(), key.getChromosome(), key.getPosition(), key.getReference(), key.getAlternate());
    }

    private static String text(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).asText() : ""; }
    private static String nullableText(JsonNode node, String field) {
        return !node.has(field) || node.path(field).isNull() ? null : text(node, field);
    }
    private static String orEmpty(String value) { return value == null ? "" : value; }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static boolean safeLocator(String value) {
        return hasText(value) && value.equals(value.strip()) && !value.startsWith("/") && !value.startsWith("\\")
                && !value.matches("^[A-Za-z]:.*") && !value.toLowerCase(java.util.Locale.ROOT).startsWith("file:")
                && !List.of(value.replace('\\', '/').split("/", -1)).contains("..");
    }

    private static void same(JsonNode node, String field, String expected, String reason) {
        require(expected != null && expected.equals(text(node, field)), reason, field);
    }

    private static void require(boolean condition, String reason, String field) {
        if (!condition) throw new Blocked(reason, field);
    }

    private static Provenance provenance(ExplicitInput input) {
        if (input == null) return new Provenance(null, null, null, null, null, null);
        AssemblyLineage lineage = input.normalization() == null ? null : input.normalization().lineage();
        if (lineage != null) {
            lineage = new AssemblyLineage(lineage.originalInputAssembly(), lineage.preRankingMappingStatus(),
                    lineage.originalInputAllele(), safeIdentity(lineage.transformationId()), safeRef(lineage.source()));
        }
        return new Provenance(input.dataClass(), input.transcript() == null ? null : safeRef(input.transcript().source()),
                input.patient() == null ? null : safeRef(input.patient().source()),
                input.normalization() == null ? null : safeRef(input.normalization().source()),
                input.normalization() == null ? null : safeRef(input.normalization().reference()), lineage);
    }

    private static SourceRef safeRef(SourceRef source) {
        return source == null ? null : new SourceRef(safeIdentity(source.source()), safeIdentity(source.version()),
                safeIdentity(source.recordLocator()), safeIdentity(source.artifactSha256()));
    }

    private static String safeIdentity(String value) {
        if (value == null) return null;
        String stripped = value.strip();
        boolean path = stripped.startsWith("/") || stripped.startsWith("\\") || stripped.matches("^[A-Za-z]:.*")
                || stripped.toLowerCase(java.util.Locale.ROOT).startsWith("file:")
                || List.of(stripped.replace('\\', '/').split("/", -1)).contains("..");
        return path ? "[LOCAL_PATH_REJECTED]" : value;
    }

    private static JsonNode safeNode(JsonNode node) {
        if (node.isTextual()) return com.fasterxml.jackson.databind.node.TextNode.valueOf(safeIdentity(node.asText()));
        JsonNode safe = node.deepCopy();
        if (safe.isObject()) {
            safe.properties().iterator().forEachRemaining(field -> ((ObjectNode) safe).set(field.getKey(), safeNode(field.getValue())));
        } else if (safe.isArray()) {
            for (int index = 0; index < safe.size(); index++) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) safe).set(index, safeNode(safe.get(index)));
            }
        }
        return safe;
    }

    private static DiseaseContextBinding safeBinding(DiseaseContextBinding binding) {
        if (binding == null) return null;
        var provenance = binding.provenance();
        var safe = provenance == null ? null : new PatientDiseaseContextBindingResolver.BindingProvenance(
                PatientDiseaseContextBindingResolver.safeHypothesis(provenance.patientHypothesis()),
                safeNode(provenance.maneResourceIdentity()), safeNode(provenance.clingenResourceIdentity()),
                provenance.crosswalkRecords().stream().map(ExplicitEvidenceContextAssembler::safeNode).toList(),
                provenance.relationshipRecords().stream().map(ExplicitEvidenceContextAssembler::safeNode).toList(),
                safeIdentity(provenance.intakePolicyId()), safeIdentity(provenance.intakePolicyVersion()));
        return new DiseaseContextBinding(binding.candidateId(), binding.hypothesisId(), binding.hgncId(), binding.symbol(),
                binding.mondoId(), binding.diseaseLabel(), binding.normalizedMoi(), binding.clingenClassification(),
                binding.clingenClassificationDate(), safeIdentity(binding.clingenGcep()), safeIdentity(binding.clingenReportLocator()),
                binding.status(), binding.reasonCode(), safe);
    }

    private static Attempt failed(String candidateId, ExplicitInput input, String reason, String field) {
        return new Attempt(candidateId, input == null ? null : input.hypothesisId(), input == null ? null : safeBinding(input.binding()),
                provenance(input), null, null, List.of(new Blocker("CONTEXT_NOT_READY", reason, field)));
    }

    public record Allele(String assembly, String contig, int position, String reference, String alternate) { }
    public record TranscriptChoice(String accession, Integer version, String selectionPolicy, String annotationSource,
                                   String annotationSourceVersion, SourceRef source) { }
    public record PatientContext(String patientContextId, String role, String affectedStatus, String sex,
                                 String familyContextId, SourceRef source) { }
    public record AssemblyLineage(String originalInputAssembly, String preRankingMappingStatus, Allele originalInputAllele,
                                  String transformationId, SourceRef source) { }
    public record NormalizationInput(Allele originalAllele, Allele normalizedAllele, Integer selectedAltIndex,
                                     String originalRecord, String method, String methodVersion, SourceRef source,
                                     SourceRef reference, AssemblyLineage lineage) { }
    public record ExplicitInput(String candidateId, String hypothesisId, DiseaseContextBinding binding, String dataClass,
                                TranscriptChoice transcript, PatientContext patient, NormalizationInput normalization) { }
    public record Provenance(String dataClass, SourceRef transcript, SourceRef patient, SourceRef normalization,
                             SourceRef reference, AssemblyLineage assemblyLineage) { }
    public record Blocker(String status, String reasonCode, String field) { }
    public record Attempt(String candidateId, String hypothesisId, DiseaseContextBinding binding, Provenance provenance,
                          ContextSeed seed, ObjectNode context, List<Blocker> blockers) {
        public Attempt {
            context = context == null ? null : context.deepCopy();
            blockers = List.copyOf(blockers);
        }
        @Override public ObjectNode context() { return context == null ? null : context.deepCopy(); }
    }
    public record CandidateAssembly(String candidateId, List<Attempt> attempts) {
        public CandidateAssembly { attempts = List.copyOf(attempts); }
    }
    public record AssemblyResult(String analysisId, int requestedN, int selectedCount, List<String> candidateIds,
                                 List<CandidateAssembly> candidates, List<Attempt> unmatchedInputs, boolean candidateOrderingChanged) {
        public AssemblyResult {
            candidateIds = java.util.Collections.unmodifiableList(new ArrayList<>(candidateIds));
            candidates = List.copyOf(candidates);
            unmatchedInputs = List.copyOf(unmatchedInputs);
        }
    }
    private record PatientClaim(String patientId, String sampleId, String role, String sex, String familyId, String affectedStatus) { }
    private record PatientDiseaseKey(String patientId, String mondoId) { }
    static final class Blocked extends RuntimeException {
        final String reason;
        final String field;
        private Blocked(String reason, String field) { this.reason = reason; this.field = field; }
    }
}
