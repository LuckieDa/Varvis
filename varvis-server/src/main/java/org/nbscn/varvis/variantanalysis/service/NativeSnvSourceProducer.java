package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.EngineExecutionResult;
import org.nbscn.varvis.variantanalysis.provider.NormalizationResourceValidator;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.nbscn.varvis.variantanalysis.service.NativeSnvReference.require;

/** Explicit native-SNV source production from the existing execution's single VCF scan. No VCF reader or normalizer. */
public final class NativeSnvSourceProducer {
    public static final String METHOD = "NATIVE_SNV_IDENTITY_REF_CHECK";
    public static final String VERSION = "1.0.0";
    public static final String SELECTED_NORMALIZATION_VERSION = SelectedAlleleNormalizer.VERSION;
    public static final String SMALL_VARIANT_METHOD = "NATIVE_MINIMAL_SMALL_VARIANT_IDENTITY_REF_CHECK";
    private final ObjectMapper mapper;
    private final NativeSnvReference reference;
    private final NativeSnvReference originalReference;
    private final SelectedAlleleNormalizer selectedNormalizer;
    private final List<ExplicitContextSourceLedger> trustedSources;

    public NativeSnvSourceProducer(ObjectMapper mapper, VariantAnalysisProperties properties, java.nio.file.Path resourceRoot,
                                   List<ExplicitContextSourceLedger> sources, SourceRef acceptedChecksum) {
        this(mapper, properties, resourceRoot, sources, acceptedChecksum, new NormalizationResourceValidator(properties));
    }

    NativeSnvSourceProducer(ObjectMapper mapper, VariantAnalysisProperties properties, java.nio.file.Path resourceRoot,
                            List<ExplicitContextSourceLedger> sources, SourceRef acceptedChecksum, NormalizationResourceValidator validator) {
        this.mapper = Objects.requireNonNull(mapper);
        this.reference = new NativeSnvReference(mapper, Objects.requireNonNull(properties), validator, resourceRoot,
                sources == null ? List.of() : List.copyOf(sources), acceptedChecksum);
        this.originalReference = null;
        this.selectedNormalizer = new SelectedAlleleNormalizer(mapper, new org.nbscn.varvis.variantanalysis.service.impl.BcftoolsVcfNormalizer(properties));
        this.trustedSources = sources == null ? List.of() : List.copyOf(sources);
    }

    public NativeSnvSourceProducer(ObjectMapper mapper, VariantAnalysisProperties properties, java.nio.file.Path resourceRoot,
                                   List<ExplicitContextSourceLedger> sources, SourceRef acceptedChecksum,
                                   java.nio.file.Path originalResourceRoot, List<ExplicitContextSourceLedger> originalSources,
                                   SourceRef originalChecksum) {
        this.mapper = Objects.requireNonNull(mapper);
        var validator = new NormalizationResourceValidator(properties);
        this.reference = new NativeSnvReference(mapper, properties, validator, resourceRoot, List.copyOf(sources), acceptedChecksum);
        this.originalReference = originalChecksum == null ? null : new NativeSnvReference(mapper, properties, validator,
                originalResourceRoot, List.copyOf(originalSources), originalChecksum, GenomeAssembly.GRCh37);
        this.selectedNormalizer = new SelectedAlleleNormalizer(mapper, new org.nbscn.varvis.variantanalysis.service.impl.BcftoolsVcfNormalizer(properties));
        this.trustedSources = new ArrayList<>(sources);
        for (var source : originalSources) if (!this.trustedSources.contains(source)) this.trustedSources.add(source);
    }

    public Production produce(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
                               AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN, List<ExplicitInput> inputs) {
        return produce(job, conversion, execution, analysis, ranked, requestedN, inputs, input -> null);
    }

    // Invalid declarations retain their own blocker and never initiate a reference query.
    Production produce(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
                       AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN, List<ExplicitInput> inputs,
                       java.util.function.Function<ExplicitInput, Blocker> inputGate) {
        return produce(job, conversion, execution, analysis, ranked, requestedN, inputs, inputGate, false);
    }

    /** Candidate-only source proof: no patient/disease/transcript assertion is created. */
    public Production produceAllele(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
                                    AnalysisContext analysis, VariantInterpretation candidate, String dataClass) {
        var input = new ExplicitInput(candidate.getCandidateId(), null, null, dataClass, null, null, null);
        return produce(job, conversion, execution, analysis, List.of(candidate), 1, List.of(input), ignored -> null, true);
    }

    /** Selected-allele normalization used by BA1 and the explicit local BS1 successor. */
    public Production produceBa1Allele(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
                                      AnalysisContext analysis, VariantInterpretation candidate, String dataClass) {
        return produceSelectedAllele(job, conversion, execution, analysis, candidate, dataClass, false);
    }

    private Production produceSelectedAllele(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
            AnalysisContext analysis, VariantInterpretation candidate, String dataClass, boolean allowExtendedDeletion) {
        var old = produceAllele(job, conversion, execution, analysis, candidate, dataClass);
        var input = old.inputs().get(0);
        if (old.blockers(input).isEmpty() && (input.normalization().normalizedAllele().reference().length() > 50
                || input.normalization().normalizedAllele().alternate().length() > 50)) {
            var failures = new IdentityHashMap<ExplicitInput, List<Blocker>>();
            failures.put(input, List.of(new Blocker("CONTEXT_NOT_READY", "NORMALIZED_ALLELE_OUTSIDE_FROZEN_SCOPE", "normalization.production")));
            return new Production(old.inputs(), old.sources(), failures, old.provenance(), execution.getInputSource());
        }
        if (old.blockers(input).size() != 1 || !java.util.Set.of("SMALL_VARIANT_NORMALIZATION_REQUIRED", "NATIVE_SMALL_VARIANT_ALLELE_UNSUPPORTED")
                .contains(old.blockers(input).get(0).reasonCode())) return old;
        var provenance = old.provenance();
        var row = (ObjectNode) provenance.path("inputs").get(0);
        var ledgers = new ArrayList<>(trustedSources);
        var failures = new IdentityHashMap<ExplicitInput, List<Blocker>>();
        ExplicitInput produced = input;
        try {
            var key = candidate.getObservation().getKey();
            var record = execution.getInputSource().matchedRecord(key);
            if (record == null) {
                throw new NativeSnvReference.Blocked("ANALYSIS_VCF_MATCHED_RECORD_SOURCE_REQUIRED");
            }
            require(key.getReference().matches("[ACGT]+") && key.getAlternate().matches("[ACGT]+")
                    && !key.getReference().equals(key.getAlternate()), "NATIVE_SMALL_VARIANT_ALLELE_UNSUPPORTED");
            require(key.getReference().length() <= 4096 && key.getAlternate().length() <= 4096, "NORMALIZATION_INPUT_SPAN_LIMIT");
            require(NormalizationAlleleMapping.IDENTITY.equals(NormalizationAlleleMapping.from(record.mappingToken(), key).getStatus()),
                    "ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED");
            Allele analysisAllele = new Allele(key.getGenomeAssembly(), key.getChromosome(), key.getPosition(), key.getReference(), key.getAlternate());
            Allele uploadAllele = analysisAllele;
            String originalRecord = record.mappingToken();
            int originalAlt = record.selectedAltIndex();
            ObjectNode conversionProof = null;
            SourceRef originalRef = null;
            if (conversion.isConversionApplied()) {
                var checked = ConvertedAlleleSource.verify(mapper, conversion, candidate, record, reference, originalReference,
                        row.putObject("conversionEvidence"));
                uploadAllele = checked.original(); originalRecord = checked.originalRecord(); originalAlt = checked.originalAlt();
                conversionProof = checked.evidence();
                var source = ledger("BA1_NORMALIZATION_ORIGINAL_REFERENCE", dataClass, mapper.createObjectNode().set("reference", checked.originalReference()));
                ledgers.add(source); originalRef = source.reference("reference");
            }
            var verified = selectedNormalizer.normalize(job.getWorkspace(), analysisAllele, record, reference,
                    row.putObject("normalizationExecution"), allowExtendedDeletion && !conversion.isConversionApplied());
            var refLedger = ledger("BA1_NORMALIZATION_REFERENCE", dataClass, mapper.createObjectNode().set("reference", verified.reference()));
            ledgers.add(refLedger);
            SourceRef ref = refLedger.reference("reference");
            // Include actual tool version in the frozen normalizer projection used by allele/cache identity.
            String version = SelectedAlleleNormalizer.VERSION + "/" + verified.toolVersion();
            require(version.length() <= 64, "NORMALIZATION_TOOL_VERSION_UNSUPPORTED");
            String method = verified.proof().path("method").asText();
            var normalization = association(analysis, input, "NORMALIZATION").put("method", method)
                    .put("methodVersion", version).put("status", "NORMALIZED").put("executionStatus", "SUCCEEDED")
                    .put("refValidation", "MATCH").put("selectedAltIndex", originalAlt).put("originalRecord", originalRecord)
                    .put("analysisVcfArtifactSha256", execution.getInputSource().observedVcfSha256()).put("analysisVcfRecordOrdinal", record.recordOrdinal());
            normalization.set("originalAllele", mapper.valueToTree(uploadAllele));
            normalization.set("analysisAllele", mapper.valueToTree(analysisAllele));
            normalization.set("normalizedAllele", mapper.valueToTree(verified.allele()));
            normalization.set("reference", mapper.valueToTree(ref));
            normalization.set("normalizationProof", verified.proof());
            normalization.set("matchedRecord", mapper.valueToTree(record));
            normalization.set("analysisVcfCall", mapper.valueToTree(candidate.getAnalysisVcfCall()));
            normalization.set("sampleObservation", mapper.valueToTree(candidate.getObservation()));
            if (originalRef != null) normalization.set("originalReference", mapper.valueToTree(originalRef));
            if (conversionProof != null) normalization.set("conversionEvidence", conversionProof);
            String transformation = conversionProof == null ? null : "conversion-sha256-" + new org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts(mapper).requestHash(conversionProof);
            var lineage = association(analysis, input, "ASSEMBLY_LINEAGE").put("originalInputAssembly", uploadAllele.assembly())
                    .put("preRankingMappingStatus", conversionProof == null ? "NATIVE" : "MAPPED").put("transformationId", transformation);
            lineage.set("originalInputAllele", mapper.valueToTree(uploadAllele));
            lineage.set("analysisAllele", mapper.valueToTree(analysisAllele));
            if (conversionProof != null) lineage.set("conversionEvidence", conversionProof);
            var records = mapper.createObjectNode(); records.set("normalization", normalization); records.set("lineage", lineage);
            var source = ledger("BA1_SELECTED_NORMALIZATION", dataClass, records); ledgers.add(source);
            produced = new ExplicitInput(input.candidateId(), null, null, dataClass, null, null,
                    new NormalizationInput(uploadAllele, verified.allele(), originalAlt, originalRecord, method, version,
                            source.reference("normalization"), ref, new AssemblyLineage(uploadAllele.assembly(), conversionProof == null ? "NATIVE" : "MAPPED",
                            uploadAllele, transformation, source.reference("lineage"))));
            row.remove("blocker"); row.put("status", "PRODUCED").set("normalization", mapper.valueToTree(produced.normalization()));
            row.set("normalizationProof", verified.proof());
        } catch (NativeSnvReference.Blocked blocked) {
            failures.put(produced, List.of(new Blocker("CONTEXT_NOT_READY", blocked.reason, "normalization.production")));
        } catch (IOException | RuntimeException invalid) {
            failures.put(produced, List.of(new Blocker("CONTEXT_NOT_READY", "NORMALIZATION_SOURCE_PRODUCTION_FAILED", "normalization.production")));
        }
        if (!failures.isEmpty()) row.put("status", "BLOCKED").set("blocker", mapper.valueToTree(failures.get(produced).get(0)));
        provenance.put("producer", SelectedAlleleNormalizer.METHOD).put("version", SelectedAlleleNormalizer.VERSION);
        provenance.put("referenceQueryAttempts", row.at("/normalizationExecution/referenceQueryAttempts").asInt()
                + row.at("/conversionEvidence/referenceQueryAttempts").asInt());
        return new Production(List.of(produced), ledgers, failures, provenance, execution.getInputSource());
    }

    Production produceConvertedContexts(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
                                        AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN, List<ExplicitInput> inputs,
                                        java.util.function.Function<ExplicitInput, Blocker> inputGate) {
        require(conversion != null && conversion.isConversionApplied(), "CONVERSION_SOURCE_REQUIRED");
        var result = produce(job, conversion, execution, analysis, ranked, requestedN, inputs, inputGate, true);
        result.provenance.put("acceptanceScope", "VERIFIED_GRCH37_TO_GRCH38_EXPLICIT_RULE_CONTEXT");
        return result;
    }

    Production produceBs1Contexts(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
            AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN, List<ExplicitInput> inputs,
            java.util.function.Function<ExplicitInput, Blocker> inputGate) {
        // Same small-variant proof used by the allele path; declaration intake remains mandatory upstream.
        return produce(job, conversion, execution, analysis, ranked, requestedN, inputs, inputGate, true);
    }

    Production produceLocalBs1Contexts(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
            AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN, List<ExplicitInput> inputs,
            java.util.function.Function<ExplicitInput, Blocker> inputGate) {
        return produceSelectedContexts(job, conversion, execution, analysis, ranked, requestedN, inputs, inputGate, true, false);
    }

    Production produceSelectedContexts(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
            AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN, List<ExplicitInput> inputs,
            java.util.function.Function<ExplicitInput, Blocker> inputGate) {
        return produceSelectedContexts(job, conversion, execution, analysis, ranked, requestedN, inputs, inputGate, false);
    }

    Production produceSelectedContexts(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
            AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN, List<ExplicitInput> inputs,
            java.util.function.Function<ExplicitInput, Blocker> inputGate, boolean allowExtendedDeletion) {
        return produceSelectedContexts(job, conversion, execution, analysis, ranked, requestedN, inputs, inputGate, false, allowExtendedDeletion);
    }

    private Production produceSelectedContexts(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
            AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN, List<ExplicitInput> inputs,
            java.util.function.Function<ExplicitInput, Blocker> inputGate, boolean legacyBs1, boolean allowExtendedDeletion) {
        var old = produceBs1Contexts(job, conversion, execution, analysis, ranked, requestedN, inputs, inputGate);
        var ledgers = new ArrayList<>(old.sources()); var output = new ArrayList<ExplicitInput>();
        var failures = new IdentityHashMap<ExplicitInput, List<Blocker>>(); var provenance = old.provenance();
        var extra = provenance.putArray("selectedNormalization");
        var byCandidate = new java.util.HashMap<String, Production>();
        for (var input : old.inputs()) {
            var reasons = old.blockers(input); var produced = input;
            // All prior declaration/source gates must succeed before the one normalization-specific retry.
            if (conversion != null && !conversion.isConversionApplied() && reasons.size() == 1
                    && java.util.Set.of("SMALL_VARIANT_NORMALIZATION_REQUIRED", "NATIVE_SMALL_VARIANT_ALLELE_UNSUPPORTED")
                    .contains(reasons.get(0).reasonCode())) {
                var matches = ranked.subList(0, Math.min(requestedN, ranked.size())).stream()
                        .filter(c -> c.getCandidateId().equals(input.candidateId())).toList();
                if (matches.size() == 1) try {
                    var cacheKey = legacyBs1 ? input.candidateId() : input.candidateId() + "\n" + input.dataClass();
                    var selected = byCandidate.computeIfAbsent(cacheKey, ignored ->
                            produceSelectedAllele(job, conversion, execution, analysis, matches.get(0), input.dataClass(), allowExtendedDeletion));
                    extra.add(selected.provenance());
                    var alleleInput = selected.inputs().get(0); reasons = selected.blockers(alleleInput);
                    if (reasons.isEmpty()) {
                        for (var source : selected.sources()) if (!ledgers.contains(source)) ledgers.add(source);
                        var n = alleleInput.normalization();
                        var proof = ((ObjectNode) selected.resolve(n.source())).deepCopy();
                        var lineage = ((ObjectNode) selected.resolve(n.lineage().source())).deepCopy();
                        for (var record : List.of(proof, lineage)) {
                            record.put("hypothesisId", input.hypothesisId());
                            record.put("patientContextId", input.patient().patientContextId());
                        }
                        var records = mapper.createObjectNode(); records.set("normalization", proof); records.set("lineage", lineage);
                        var ledger = ledger(legacyBs1 ? "BS1_SELECTED_NORMALIZATION_ASSOCIATION" : "SELECTED_NORMALIZATION_ASSOCIATION", input.dataClass(), records); ledgers.add(ledger);
                        produced = new ExplicitInput(input.candidateId(), input.hypothesisId(), input.binding(), input.dataClass(), input.transcript(), input.patient(),
                                new NormalizationInput(n.originalAllele(), n.normalizedAllele(), n.selectedAltIndex(), n.originalRecord(), n.method(), n.methodVersion(),
                                        ledger.reference("normalization"), n.reference(), new AssemblyLineage(n.lineage().originalInputAssembly(),
                                        n.lineage().preRankingMappingStatus(), n.lineage().originalInputAllele(), n.lineage().transformationId(), ledger.reference("lineage"))));
                    }
                } catch (IOException | RuntimeException invalid) {
                    reasons = List.of(new Blocker("CONTEXT_NOT_READY", legacyBs1 ? "BS1_NORMALIZATION_ASSOCIATION_FAILED" : "SELECTED_NORMALIZATION_ASSOCIATION_FAILED", "normalization"));
                }
            }
            if (!legacyBs1 && reasons.isEmpty() && produced.normalization() != null
                    && !BoundedSequenceDeletion.NORMALIZATION_METHOD.equals(produced.normalization().method())
                    && (produced.normalization().normalizedAllele().reference().length() > 50
                    || produced.normalization().normalizedAllele().alternate().length() > 50))
                reasons = List.of(new Blocker("CONTEXT_NOT_READY", "NORMALIZED_ALLELE_OUTSIDE_FROZEN_SCOPE", "normalization"));
            output.add(produced); if (!reasons.isEmpty()) failures.put(produced, reasons);
        }
        provenance.put(legacyBs1 ? "localBs1SelectedNormalization" : "sharedSelectedNormalization", true);
        return new Production(output, ledgers, failures, provenance, execution.getInputSource());
    }

    private Production produce(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
                               AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN, List<ExplicitInput> inputs,
                               java.util.function.Function<ExplicitInput, Blocker> inputGate, boolean alleleOnly) {
        Objects.requireNonNull(analysis);
        Objects.requireNonNull(ranked);
        if (requestedN <= 0) throw new IllegalArgumentException("TOP_N_MUST_BE_POSITIVE");
        List<VariantInterpretation> selected = ranked.subList(0, Math.min(requestedN, ranked.size()));
        List<ExplicitInput> produced = new ArrayList<>();
        List<ExplicitContextSourceLedger> ledgers = new ArrayList<>();
        Map<ExplicitInput, List<Blocker>> failures = new IdentityHashMap<>();
        ObjectNode provenance = mapper.createObjectNode().put("producer", METHOD).put("version", VERSION)
                .put("acceptanceScope", ExplicitEvidenceContextAssembler.ACCEPTANCE_SCOPE)
                .put("additionalFullVcfScans", 0).put("fullReferenceHashesComputed", 0);
        if (execution != null && execution.getInputSource() != null) {
            var source = execution.getInputSource();
            provenance.put("originalInputAssembly", source.inputAssembly() == null ? null : source.inputAssembly().name())
                    .put("analysisAssembly", source.analysisAssembly() == null ? null : source.analysisAssembly().name());
        }
        if (conversion != null) {
            provenance.put("conversionApplied", conversion.isConversionApplied());
            if (conversion.isConversionApplied() && alleleOnly)
                provenance.put("acceptanceScope", "BA1_VERIFIED_GRCH37_TO_GRCH38_SMALL_VARIANT");
        }
        var rows = provenance.putArray("inputs");
        int queries = 0;
        for (ExplicitInput input : inputs == null ? List.<ExplicitInput>of() : inputs) {
            if (input == null) { produced.add(null); continue; }
            ObjectNode row = rows.addObject().put("candidateId", input.candidateId()).put("hypothesisId", input.hypothesisId())
                    .put("dataClass", input.dataClass());
            row.set("binding", mapper.valueToTree(input.binding()));
            row.set("suppliedNormalization", mapper.valueToTree(input.normalization()));
            var matches = selected.stream().filter(Objects::nonNull).filter(c -> Objects.equals(c.getCandidateId(), input.candidateId())).toList();
            if (matches.isEmpty()) {
                produced.add(input);
                var missing = inputGate.apply(input);
                if (missing != null && (input.candidateId() == null || input.candidateId().isBlank())) {
                    failures.put(input, List.of(missing));
                    row.put("status", "BLOCKED").put("reasonCode", missing.reasonCode());
                } else row.put("status", "OUTSIDE_SELECTED_CANDIDATES");
                continue;
            }
            ExplicitInput result = input;
            Blocker issue = inputGate.apply(input);
            try {
                if (issue != null) throw new NativeSnvReference.Blocked(issue.reasonCode());
                require(matches.size() == 1, "CANDIDATE_ID_AMBIGUOUS");
                VariantInterpretation candidate = matches.get(0);
                if (candidate.getObservation() != null) {
                    row.put("candidateCanonicalVariantId", candidate.getCanonicalVariantId());
                    if (execution != null && execution.getInputSource() != null)
                        row.set("matchedRecord", mapper.valueToTree(execution.getInputSource().matchedRecord(candidate.getObservation().getKey())));
                }
                issue = CandidateAcmgInputAdapter.sourceIssue(mapper, job, conversion, execution, analysis, candidate, alleleOnly);
                if (issue != null) throw new NativeSnvReference.Blocked(issue.reasonCode());
                if (conversion == null) throw new NativeSnvReference.Blocked("ANALYSIS_CONVERSION_SOURCE_REQUIRED");
                var source = Objects.requireNonNull(execution).getInputSource();
                row.put("analysisId", source.analysisId()).put("originalInputAssembly", source.inputAssembly().name())
                        .put("analysisAssembly", source.analysisAssembly().name()).put("analysisVcfArtifactSha256", source.observedVcfSha256())
                        .put("fullVcfScans", source.fullVcfScans()).put("requestSha256", execution.getRequestSha256())
                        .put("commandSha256", execution.getCommandSha256()).put("outputSha256", execution.getMetadata().getOutputSha256());
                require(source.fullVcfScans() == 1 && source.unchangedDuringExecution()
                                && (conversion.isConversionApplied() || source.uploadSha256().equals(source.observedVcfSha256())), "ANALYSIS_VCF_CONTENT_IDENTITY_NOT_VERIFIED");
                require(input.normalization() == null, "EXPLICIT_NORMALIZATION_ALREADY_SUPPLIED");
                require(alleleOnly || input.patient() != null && input.patient().patientContextId() != null, "PATIENT_CONTEXT_REQUIRED");
                require("SYNTHETIC".equals(input.dataClass()) || "PRIVACY_BOUNDED_RUNTIME".equals(input.dataClass()), "INPUT_DATA_CLASS_REQUIRED");
                var key = candidate.getObservation().getKey();
                var record = source.matchedRecord(key);
                var call = candidate.getAnalysisVcfCall();
                if (record == null) { throw new NativeSnvReference.Blocked("ANALYSIS_VCF_MATCHED_RECORD_SOURCE_REQUIRED"); }
                row.set("matchedRecord", mapper.valueToTree(record));
                require(record.recordOrdinal() > 0 && Objects.equals(call.getMatchedRecordOrdinal(), record.recordOrdinal())
                                && record.selectedAltIndex() > 0 && record.selectedAltIndex() <= record.alternates().size()
                                && Objects.equals(call.getTargetAlleleIndex(), record.selectedAltIndex()), "ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED");
                String alt = record.alternates().get(record.selectedAltIndex() - 1);
                require(record.reference().matches("[ACGT]{1,51}") && alt.matches("[ACGT]{1,51}")
                        && !record.reference().equals(alt), "NATIVE_SMALL_VARIANT_ALLELE_UNSUPPORTED");
                require(isProvablyMinimal(record.reference(), alt), "SMALL_VARIANT_NORMALIZATION_REQUIRED");
                if (conversion.isConversionApplied()) {
                    ObjectNode converted = row.putObject("conversionEvidence");
                    var checked = ConvertedAlleleSource.verify(mapper, conversion, candidate, record, reference, originalReference, converted);
                    String name = "CONVERTED_ALLELE_SOURCE_" + rows.size();
                    var targetLedger = ledger(name + "_REFERENCE", input.dataClass(), mapper.createObjectNode().set("reference", checked.targetReference()));
                    var originalLedger = ledger(name + "_ORIGINAL_REFERENCE", input.dataClass(), mapper.createObjectNode().set("reference", checked.originalReference()));
                    SourceRef targetRef = targetLedger.reference("reference");
                    ObjectNode normalization = association(analysis, input, "NORMALIZATION")
                            .put("method", ConvertedAlleleSource.METHOD).put("methodVersion", ConvertedAlleleSource.VERSION)
                            .put("originalRecord", checked.originalRecord()).put("selectedAltIndex", checked.originalAlt())
                            .put("status", "NORMALIZED").put("executionStatus", "SUCCEEDED").put("refValidation", "MATCH")
                            .put("inputObservedReference", checked.original().reference()).put("normalizedObservedReference", checked.target().reference())
                            .put("analysisVcfArtifactSha256", source.observedVcfSha256()).put("analysisVcfRecordOrdinal", record.recordOrdinal());
                    normalization.set("originalAllele", mapper.valueToTree(checked.original()));
                    normalization.set("normalizedAllele", mapper.valueToTree(checked.target()));
                    normalization.set("reference", mapper.valueToTree(targetRef));
                    normalization.set("originalReference", mapper.valueToTree(originalLedger.reference("reference")));
                    normalization.set("conversionEvidence", checked.evidence());
                    String transformationId = "conversion-sha256-" + new org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts(mapper).requestHash(normalization);
                    ObjectNode lineage = association(analysis, input, "ASSEMBLY_LINEAGE").put("originalInputAssembly", "GRCh37")
                            .put("preRankingMappingStatus", "MAPPED").put("transformationId", transformationId);
                    lineage.set("originalInputAllele", mapper.valueToTree(checked.original()));
                    lineage.set("conversionEvidence", checked.evidence());
                    var records = mapper.createObjectNode(); records.set("normalization", normalization); records.set("lineage", lineage);
                    var sourceLedger = ledger(name, input.dataClass(), records);
                    result = new ExplicitInput(input.candidateId(), input.hypothesisId(), input.binding(), input.dataClass(), input.transcript(), input.patient(),
                            new NormalizationInput(checked.original(), checked.target(), checked.originalAlt(), checked.originalRecord(),
                                    ConvertedAlleleSource.METHOD, ConvertedAlleleSource.VERSION, sourceLedger.reference("normalization"), targetRef,
                                    new AssemblyLineage("GRCh37", "MAPPED", checked.original(), transformationId, sourceLedger.reference("lineage"))));
                    ledgers.add(targetLedger); ledgers.add(originalLedger); ledgers.add(sourceLedger);
                    row.put("status", "PRODUCED").set("normalization", mapper.valueToTree(result.normalization()));
                    produced.add(result);
                    continue;
                }
                String method = record.reference().length() == 1 && alt.length() == 1 ? METHOD : SMALL_VARIANT_METHOD;
                var mapping = NormalizationAlleleMapping.from(record.mappingToken(), key);
                require(NormalizationAlleleMapping.IDENTITY.equals(mapping.getStatus()), "NATIVE_SNV_IDENTITY_NOT_ESTABLISHED");
                Allele allele = new Allele(key.getGenomeAssembly(), key.getChromosome(), key.getPosition(), key.getReference(), key.getAlternate());
                queries++;
                var observed = reference.observe(record.contig(), record.position(), record.reference());
                row.set("referenceObservation", observed.observation());
                require("MATCH".equals(observed.observation().path("status").asText()), "REF_VALIDATION_NOT_MATCH");
                String sourceName = "NATIVE_SNV_SOURCE_" + rows.size();
                var refLedger = ledger(sourceName + "_REFERENCE", input.dataClass(), mapper.createObjectNode().set("reference", observed.identity()));
                SourceRef ref = refLedger.reference("reference");
                ObjectNode normalization = association(analysis, input, "NORMALIZATION").put("originalRecord", record.mappingToken())
                        .put("selectedAltIndex", record.selectedAltIndex()).put("method", method).put("methodVersion", VERSION)
                        .put("status", "UNCHANGED").put("executionStatus", "SUCCEEDED").put("refValidation", "MATCH")
                        .put("inputObservedReference", observed.observation().path("observedReference").asText())
                        .put("normalizedObservedReference", observed.observation().path("observedReference").asText())
                        .put("refValidationMethod", observed.observation().path("method").asText()).put("refValidationVersion", NativeSnvReference.QUERY_VERSION)
                        .put("refValidationRecordLocator", "normalization/referenceObservation")
                        .put("analysisVcfArtifactSha256", source.observedVcfSha256()).put("analysisVcfRecordOrdinal", record.recordOrdinal());
                normalization.set("originalAllele", mapper.valueToTree(allele));
                normalization.set("normalizedAllele", mapper.valueToTree(allele));
                normalization.set("reference", mapper.valueToTree(ref));
                normalization.set("referenceObservation", observed.observation());
                normalization.set("matchedRecord", mapper.valueToTree(record));
                normalization.putObject("execution").put("requestSha256", execution.getRequestSha256())
                        .put("commandSha256", execution.getCommandSha256()).put("outputSha256", execution.getMetadata().getOutputSha256())
                        .put("analysisVcfByteLength", source.observedVcfByteLength()).put("analysisVcfMtimeNs", source.observedVcfMtimeNs())
                        .put("unchangedDuringExecution", source.unchangedDuringExecution()).put("fullVcfScans", source.fullVcfScans());
                ObjectNode lineage = association(analysis, input, "ASSEMBLY_LINEAGE").put("originalInputAssembly", "GRCh38")
                        .put("preRankingMappingStatus", "NATIVE").putNull("transformationId");
                lineage.set("originalInputAllele", mapper.valueToTree(allele));
                lineage.put("analysisVcfArtifactSha256", source.observedVcfSha256()).put("analysisVcfRecordOrdinal", record.recordOrdinal())
                        .put("selectedAltIndex", record.selectedAltIndex()).set("execution", normalization.path("execution").deepCopy());
                ObjectNode records = mapper.createObjectNode();
                records.set("normalization", normalization);
                records.set("lineage", lineage);
                var ledger = ledger(sourceName, input.dataClass(), records);
                result = new ExplicitInput(input.candidateId(), input.hypothesisId(), input.binding(), input.dataClass(), input.transcript(), input.patient(),
                        new NormalizationInput(allele, allele, record.selectedAltIndex(), record.mappingToken(), method, VERSION,
                                ledger.reference("normalization"), ref, new AssemblyLineage("GRCh38", "NATIVE", allele, null, ledger.reference("lineage"))));
                ledgers.add(refLedger);
                ledgers.add(ledger);
                row.put("status", "PRODUCED").set("normalization", mapper.valueToTree(result.normalization()));
            } catch (NativeSnvReference.Blocked blocked) {
                if (issue == null) issue = new Blocker("CONTEXT_NOT_READY", blocked.reason, "normalization.production");
                if (row.path("conversionEvidence") instanceof ObjectNode converted)
                    converted.put("status", "NOT_VERIFIED").put("reasonCode", blocked.reason);
            } catch (IOException | RuntimeException ex) {
                issue = new Blocker("CONTEXT_NOT_READY", "SOURCE_PRODUCTION_FAILED", "normalization.production");
            }
            if (issue != null) {
                failures.put(result, List.of(issue));
                row.put("status", "BLOCKED").set("blocker", mapper.valueToTree(issue));
            }
            produced.add(result);
        }
        for (var row : rows) queries += row.path("conversionEvidence").path("referenceQueryAttempts").asInt();
        provenance.put("referenceQueryAttempts", queries);
        return new Production(produced, ledgers, failures, provenance, execution == null ? null : execution.getInputSource());
    }

    /** A shared suffix permits trimming/left rotation; a shared prefix with both alleles longer than one permits trimming. */
    static boolean isProvablyMinimal(String ref, String alt) {
        if (!ref.matches("[ACGT]{1,51}") || !alt.matches("[ACGT]{1,51}") || ref.equals(alt)) return false;
        if (ref.charAt(ref.length() - 1) == alt.charAt(alt.length() - 1)) return false;
        return ref.length() == 1 || alt.length() == 1 || ref.charAt(0) != alt.charAt(0);
    }

    private ObjectNode association(AnalysisContext analysis, ExplicitInput input, String kind) {
        return mapper.createObjectNode().put("kind", kind).put("analysisId", analysis.getAnalysisId())
                .put("candidateId", input.candidateId()).put("hypothesisId", input.hypothesisId())
                .put("patientContextId", input.patient() == null ? null : input.patient().patientContextId());
    }
    private ExplicitContextSourceLedger ledger(String source, String dataClass, ObjectNode records) throws IOException {
        return ExplicitContextSourceLedger.create(mapper, mapper.createObjectNode().put("source", source).put("version", VERSION)
                .put("dataClass", dataClass).set("records", records));
    }

    public static final class Production {
        private final List<ExplicitInput> inputs;
        private final List<ExplicitContextSourceLedger> sources;
        private final Map<ExplicitInput, List<Blocker>> failures;
        private final ObjectNode provenance;
        private final EngineExecutionResult.InputSource executionSource;
        private Production(List<ExplicitInput> inputs, List<ExplicitContextSourceLedger> sources,
                           Map<ExplicitInput, List<Blocker>> failures, ObjectNode provenance, EngineExecutionResult.InputSource executionSource) {
            this.inputs = Collections.unmodifiableList(new ArrayList<>(inputs));
            this.sources = List.copyOf(sources);
            this.failures = new IdentityHashMap<>(failures);
            this.provenance = provenance.deepCopy();
            this.executionSource = executionSource;
        }
        public List<ExplicitInput> inputs() { return inputs; }
        public List<ExplicitContextSourceLedger> sources() { return sources; }
        public ObjectNode provenance() { return provenance.deepCopy(); }
        public com.fasterxml.jackson.databind.JsonNode resolve(SourceRef ref) {
            var found = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            for (var source : sources) try { found.add(source.resolve(ref)); } catch (IllegalArgumentException ignored) { }
            require(found.size() == 1, "NORMALIZATION_SOURCE_REFERENCE_AMBIGUOUS_OR_MISSING");
            return found.get(0);
        }
        public List<Blocker> blockers(ExplicitInput input) { return failures.getOrDefault(input, List.of()); }
        boolean belongsTo(EngineExecutionResult execution) { return execution != null && executionSource == execution.getInputSource(); }
    }
}
