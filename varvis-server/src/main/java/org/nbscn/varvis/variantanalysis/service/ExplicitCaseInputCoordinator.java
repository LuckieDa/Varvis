package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.EngineExecutionResult;
import org.nbscn.varvis.variantanalysis.provider.ExplicitAlphaMissenseDispatcher;
import org.nbscn.varvis.variantanalysis.provider.DeNovoSelectedBatch.PublishedBackground;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.*;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Internal, explicitly invoked single-proband intake. Declarations are caller assertions, not clinical verification.
 * The live execution witness is supplied by the existing analysis service, never reconstructed from a caller label.
 * No pedigree affected flag is read: affectedStatus is an explicit disease-specific declaration, including UNKNOWN.
 */
public final class ExplicitCaseInputCoordinator {
    public static final String VERSION = "1.0.0";
    public static final String CLAIM = "CALLER_DECLARATION_NOT_INDEPENDENT_CLINICAL_VERIFICATION";
    private final ObjectMapper mapper;
    private final PatientDiseaseContextBindingResolver resolver;
    private final NativeSnvSourceProducer producer;
    private final List<ExplicitContextSourceLedger> configuredSources;
    @org.springframework.lang.Nullable private final ExplicitAlphaMissenseDispatcher dispatcher;
    private SelectedQueryAnnotations.Provider queryAnnotations;

    public ExplicitCaseInputCoordinator withQueryAnnotations(SelectedQueryAnnotations.Provider provider) {
        var copy = dispatcher == null ? new ExplicitCaseInputCoordinator(mapper, resolver, producer, configuredSources)
                : new ExplicitCaseInputCoordinator(mapper, resolver, producer, configuredSources, dispatcher);
        copy.queryAnnotations = Objects.requireNonNull(provider);
        return copy;
    }

    ExplicitCaseInputCoordinator withDeNovoPublishedBackgrounds(List<PublishedBackground> backgrounds) {
        if (backgrounds.isEmpty()) return this;
        var copy = new ExplicitCaseInputCoordinator(mapper, resolver.withDeNovoPublishedBackgrounds(mapper, backgrounds), producer, configuredSources);
        copy.queryAnnotations = queryAnnotations;
        return copy;
    }

    /** Population preparation has no dependency on a prediction dispatcher. */
    public ExplicitCaseInputCoordinator(ObjectMapper mapper, PatientDiseaseContextBindingResolver resolver,
                                        NativeSnvSourceProducer producer, List<ExplicitContextSourceLedger> configuredSources) {
        this.mapper = Objects.requireNonNull(mapper).copy().findAndRegisterModules();
        this.resolver = Objects.requireNonNull(resolver);
        this.producer = Objects.requireNonNull(producer);
        this.configuredSources = configuredSources == null ? List.of() : List.copyOf(configuredSources);
        this.dispatcher = null;
    }

    public ExplicitCaseInputCoordinator(ObjectMapper mapper, PatientDiseaseContextBindingResolver resolver,
                                        NativeSnvSourceProducer producer, List<ExplicitContextSourceLedger> configuredSources,
                                        ExplicitAlphaMissenseDispatcher dispatcher) {
        this.mapper = Objects.requireNonNull(mapper).copy().findAndRegisterModules();
        this.resolver = Objects.requireNonNull(resolver);
        this.producer = Objects.requireNonNull(producer);
        this.configuredSources = configuredSources == null ? List.of() : List.copyOf(configuredSources);
        this.dispatcher = Objects.requireNonNull(dispatcher);
    }

    public record PatientDeclaration(String patientContextId, String role, String sex, String familyContextId,
                                     HypothesisProvenance source) { }

    com.fasterxml.jackson.databind.JsonNode referenceOptions(VariantInterpretation candidate) {
        return resolver.referenceOptions(mapper, candidate);
    }
    public record TranscriptSelection(String accession, Integer version, String selectionPolicy,
                                      String annotationSource, String annotationSourceVersion,
                                      HypothesisProvenance source, String selectionReason) { }
    public record HypothesisInput(PatientDiseaseHypothesis hypothesis, String affectedStatus, TranscriptSelection transcript) { }
    public record CaseDeclaration(String analysisId, @JsonIgnore EngineExecutionResult.InputSource executionSource,
                                  @JsonIgnore String sampleId, String dataClass, PatientDeclaration patient,
                                  List<HypothesisInput> hypotheses) {
        public CaseDeclaration {
            hypotheses = hypotheses == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(hypotheses));
        }
    }
    public record DeclarationOutcome(int ordinal, String candidateId, String hypothesisId, SourceRef declaration,
                                     Blocker blocker) { }
    /** Only the existing privacy-bounded result and logical references are serialized. Raw ledgers stay internal. */
    public record Result(CandidateAcmgInputAdapter.Result adapterResult, List<DeclarationOutcome> declarations,
                         @JsonIgnore List<ExplicitContextSourceLedger> sources,
                         @JsonIgnore NativeSnvSourceProducer.Production production) {
        public Result { declarations = List.copyOf(declarations); sources = List.copyOf(sources); }
    }

    public Result dispatch(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
                           AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN,
                           List<CaseDeclaration> declarations) {
        return dispatch(job, conversion, execution, analysis, ranked, requestedN, declarations, Map.of());
    }

    Result dispatch(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
                    AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN,
                    List<CaseDeclaration> declarations, Map<String, Blocker> recoveredSourceIssues) {
        var intake = intake(job, conversion, execution, analysis, ranked, requestedN, declarations, recoveredSourceIssues, false);
        var sources = intake.sources();
        var production = producer.produce(job, conversion, execution, analysis, ranked, requestedN, intake.inputs(), intake.failures()::get);
        sources.addAll(production.sources());
        var result = new CandidateAcmgInputAdapter(mapper, resolver, sources, Objects.requireNonNull(dispatcher, "Explicit legacy dispatcher required"))
                .dispatch(job, conversion, execution, analysis, ranked, requestedN, production);
        return new Result(result, intake.outcomes(), sources, production);
    }

    /** Shared provenance/context assembly only: never constructs or calls a scientific dispatcher. */
    ConvertedContexts assemblePopulation(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
            AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN,
            List<CaseDeclaration> declarations, Map<String, Blocker> recoveredSourceIssues) {
        boolean converted = conversion.isConversionApplied();
        var intake = intake(job, conversion, execution, analysis, ranked, requestedN, declarations, recoveredSourceIssues, converted);
        var production = converted
                ? producer.produceConvertedContexts(job, conversion, execution, analysis, ranked, requestedN, intake.inputs(), intake.failures()::get)
                : producer.produce(job, conversion, execution, analysis, ranked, requestedN, intake.inputs(), intake.failures()::get);
        intake.sources().addAll(production.sources());
        var assembly = new ExplicitEvidenceContextAssembler(mapper, resolver, intake.sources(), converted)
                .assemble(analysis, ranked, requestedN, production.inputs(), (candidate, input) -> production.blockers(input));
        return new ConvertedContexts(assembly, intake.sources(), production);
    }

    /** Opt-in allele interpretation only; legacy entry points retain ranking-MOI equality. */
    SelectedEvidencePreparation prepareLocalInterpretation(VariantAnalysisJob job, BuildConversionResult conversion,
            EngineExecutionResult execution, AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN,
            List<CaseDeclaration> declarations, Map<String, Blocker> recoveredSourceIssues) {
        return prepareLocalInterpretation(job, conversion, execution, analysis, ranked, requestedN, declarations, recoveredSourceIssues, false);
    }

    SelectedEvidencePreparation prepareLocalInterpretation(VariantAnalysisJob job, BuildConversionResult conversion,
            EngineExecutionResult execution, AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN,
            List<CaseDeclaration> declarations, Map<String, Blocker> recoveredSourceIssues, boolean allowExtendedDeletion) {
        var coordinator = new ExplicitCaseInputCoordinator(mapper, resolver.withLocalInterpretationContexts(), producer, configuredSources);
        coordinator.queryAnnotations = queryAnnotations;
        return coordinator.prepareSelected(job, conversion, execution, analysis, ranked, requestedN, declarations,
                recoveredSourceIssues, allowExtendedDeletion);
    }

    /** PP1-only selection: absent relationship coverage remains explicit, never READY. */
    SelectedEvidencePreparation preparePp1Selection(VariantAnalysisJob job, BuildConversionResult conversion,
            EngineExecutionResult execution, AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN,
            List<CaseDeclaration> declarations, Map<String, Blocker> recoveredSourceIssues) {
        return new ExplicitCaseInputCoordinator(mapper, resolver.withPp1SelectionContexts(), producer, configuredSources)
                .prepareSelected(job, conversion, execution, analysis, ranked, requestedN, declarations, recoveredSourceIssues);
    }

    /** Native selected contexts for multiple consumers, with optional exact-query annotation and no evidence evaluation. */
    public SelectedEvidencePreparation prepareSelected(VariantAnalysisJob job, BuildConversionResult conversion,
            EngineExecutionResult execution, AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN,
            List<CaseDeclaration> declarations, Map<String, Blocker> recoveredSourceIssues) {
        return prepareSelected(job, conversion, execution, analysis, ranked, requestedN, declarations, recoveredSourceIssues, false);
    }

    private SelectedEvidencePreparation prepareSelected(VariantAnalysisJob job, BuildConversionResult conversion,
            EngineExecutionResult execution, AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN,
            List<CaseDeclaration> declarations, Map<String, Blocker> recoveredSourceIssues, boolean allowExtendedDeletion) {
        var issues = new java.util.HashMap<>(recoveredSourceIssues);
        if (conversion == null || conversion.isConversionApplied() || analysis.getGenomeAssembly() != GenomeAssembly.GRCh38)
            for (var candidate : ranked) issues.put(candidate.getCandidateId(),
                    new Blocker("CONTEXT_NOT_READY", "SELECTED_NATIVE_GRCH38_REQUIRED", "variant"));
        var intake = intake(job, conversion, execution, analysis, ranked, requestedN, declarations, issues, false, true);
        var production = producer.produceSelectedContexts(job, conversion, execution, analysis, ranked, requestedN,
                intake.inputs(), intake.failures()::get, allowExtendedDeletion);
        intake.sources().addAll(production.sources());
        var assembly = new ExplicitEvidenceContextAssembler(mapper, resolver, intake.sources(), false, true)
                .assemble(analysis, ranked, requestedN, production.inputs(), (candidate, input) -> production.blockers(input));
        var prepared = new SelectedEvidencePreparation(mapper, assembly, intake.sources(), production, execution, ranked,
                resolver.pp1SelectionContexts());
        if (queryAnnotations != null) prepared.prepareQueryAnnotations(queryAnnotations, analysis);
        return prepared;
    }

    /** Explicit BS1 successor preparation, without dispatching any other criterion. */
    ConvertedContexts assembleBs1(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
            AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN,
            List<CaseDeclaration> declarations, Map<String, Blocker> recoveredSourceIssues) {
        return assembleBs1(job, conversion, execution, analysis, ranked, requestedN, declarations, recoveredSourceIssues, false);
    }
    ConvertedContexts assembleBs1(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
            AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN,
            List<CaseDeclaration> declarations, Map<String, Blocker> recoveredSourceIssues, boolean localExomes) {
        boolean converted = conversion.isConversionApplied();
        var intake = intake(job, conversion, execution, analysis, ranked, requestedN, declarations, recoveredSourceIssues, converted, true);
        var production = localExomes ? producer.produceLocalBs1Contexts(job, conversion, execution, analysis, ranked, requestedN,
                intake.inputs(), intake.failures()::get) : producer.produceBs1Contexts(job, conversion, execution, analysis, ranked, requestedN,
                intake.inputs(), intake.failures()::get);
        intake.sources().addAll(production.sources());
        var assembly = new ExplicitEvidenceContextAssembler(mapper, resolver, intake.sources(), converted, true)
                .assemble(analysis, ranked, requestedN, production.inputs(), (candidate, input) -> production.blockers(input));
        return new ConvertedContexts(assembly, intake.sources(), production);
    }

    ConvertedContexts assembleConvertedBa1(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
                                          AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN,
                                          List<CaseDeclaration> declarations, Map<String, Blocker> recoveredSourceIssues,
                                          NativeSnvSourceProducer convertedProducer) {
        return assembleConverted(ConvertedCriterion.BA1, job, conversion, execution, analysis, ranked, requestedN,
                declarations, recoveredSourceIssues, convertedProducer);
    }

    ConvertedContexts assembleConvertedPm2(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
                                          AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN,
                                          List<CaseDeclaration> declarations, Map<String, Blocker> recoveredSourceIssues) {
        return assembleConverted(ConvertedCriterion.PM2, job, conversion, execution, analysis, ranked, requestedN,
                declarations, recoveredSourceIssues, producer);
    }

    private enum ConvertedCriterion { BA1, PM2 }

    private ConvertedContexts assembleConverted(ConvertedCriterion criterion, VariantAnalysisJob job, BuildConversionResult conversion,
                                          EngineExecutionResult execution, AnalysisContext analysis, List<VariantInterpretation> ranked,
                                          int requestedN, List<CaseDeclaration> declarations, Map<String, Blocker> recoveredSourceIssues,
                                          NativeSnvSourceProducer convertedProducer) {
        Objects.requireNonNull(criterion);
        var intake = intake(job, conversion, execution, analysis, ranked, requestedN, declarations, recoveredSourceIssues, true);
        var production = convertedProducer.produceConvertedContexts(job, conversion, execution, analysis, ranked,
                requestedN, intake.inputs(), intake.failures()::get);
        intake.sources().addAll(production.sources());
        var assembly = new ExplicitEvidenceContextAssembler(mapper, resolver, intake.sources(), true)
                .assemble(analysis, ranked, requestedN, production.inputs(), (candidate, input) -> production.blockers(input));
        return new ConvertedContexts(assembly, intake.sources(), production);
    }

    record ConvertedContexts(AssemblyResult assembly, List<ExplicitContextSourceLedger> sources,
                             NativeSnvSourceProducer.Production production) { }
    private record Intake(List<ExplicitInput> inputs, List<ExplicitContextSourceLedger> sources,
                          List<DeclarationOutcome> outcomes, Map<ExplicitInput, Blocker> failures) { }

    private Intake intake(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
                          AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN,
                          List<CaseDeclaration> declarations, Map<String, Blocker> recoveredSourceIssues, boolean allowConvertedBa1) {
        return intake(job, conversion, execution, analysis, ranked, requestedN, declarations, recoveredSourceIssues, allowConvertedBa1, false);
    }

    private Intake intake(VariantAnalysisJob job, BuildConversionResult conversion, EngineExecutionResult execution,
                          AnalysisContext analysis, List<VariantInterpretation> ranked, int requestedN,
                          List<CaseDeclaration> declarations, Map<String, Blocker> recoveredSourceIssues, boolean allowConvertedBa1, boolean bs1) {
        Objects.requireNonNull(analysis);
        Objects.requireNonNull(ranked);
        if (requestedN <= 0) throw new IllegalArgumentException("TOP_N_MUST_BE_POSITIVE");
        List<ExplicitInput> inputs = new ArrayList<>();
        List<ExplicitContextSourceLedger> sources = new ArrayList<>(configuredSources);
        List<DeclarationOutcome> outcomes = new ArrayList<>();
        Map<ExplicitInput, Blocker> failures = new IdentityHashMap<>();
        for (CaseDeclaration declaration : declarations == null ? List.<CaseDeclaration>of() : declarations) {
            List<HypothesisInput> hypotheses = declaration == null || declaration.hypotheses().isEmpty()
                    ? Collections.singletonList(null) : declaration.hypotheses();
            for (HypothesisInput supplied : hypotheses) {
                PatientDiseaseHypothesis hypothesis = supplied == null ? null : supplied.hypothesis();
                String candidateId = hypothesis == null ? null : hypothesis.candidateId();
                String hypothesisId = hypothesis == null ? null : hypothesis.hypothesisId();
                int ordinal = outcomes.size() + 1;
                String receiptSource = "EXPLICIT_CASE_DECLARATION_" + ordinal;
                var matches = ranked.stream().filter(Objects::nonNull)
                        .filter(c -> Objects.equals(c.getCandidateId(), candidateId)).toList();
                VariantInterpretation candidate = matches.size() == 1 ? matches.get(0) : null;
                DiseaseContextBinding binding = null;
                if (candidate != null && hypothesis != null) {
                    // The raw caller locator can identify a sample. Keep it in the private receipt;
                    // the resolver and returned binding refer to that receipt's logical declaration record.
                    var privateHypothesis = new PatientDiseaseHypothesis(candidateId, hypothesisId, hypothesis.hgncId(),
                            hypothesis.mondoId(), hypothesis.moi(), sourceValid(hypothesis.provenance())
                            ? new HypothesisProvenance(receiptSource, VERSION, "declaration") : null);
                    binding = resolver.resolve(candidate, List.of(privateHypothesis)).bindings().get(0);
                }
                String dataClass = declaration == null ? null : declaration.dataClass();
                PatientDeclaration patient = declaration == null ? null : declaration.patient();
                TranscriptSelection selection = supplied == null ? null : supplied.transcript();
                ExplicitInput input = new ExplicitInput(candidateId, hypothesisId, binding, dataClass, null, null, null);
                SourceRef declarationRef = null;
                Blocker issue = null;
                try {
                    if (declaration == null) { throw new IntakeBlocked(blocker("CASE_DECLARATION_REQUIRED", "declaration")); }
                    require(List.of("SYNTHETIC", "PRIVACY_BOUNDED_RUNTIME").contains(orEmpty(dataClass)), "DATA_CLASS_REQUIRED", "dataClass");
                    // A receipt records what was received even when validation subsequently blocks it. No invented confirmation time.
                    ObjectNode received = mapper.createObjectNode().put("kind", "CASE_DECLARATION").put("claim", CLAIM)
                            .put("receivedAt", Instant.now().toString()).put("analysisId", declaration.analysisId())
                            .put("sampleId", declaration.sampleId());
                    received.set("patient", mapper.valueToTree(patient));
                    received.set("input", mapper.valueToTree(supplied));
                    received.set("execution", executionIdentity(declaration.executionSource(), execution));
                    ObjectNode records = mapper.createObjectNode().set("declaration", received);
                    ObjectNode patientRecord = association(declaration, hypothesis, "PATIENT_CONTEXT")
                            .put("role", patient == null ? null : patient.role()).put("sex", patient == null ? null : patient.sex())
                            .put("affectedStatus", supplied == null ? null : supplied.affectedStatus())
                            .put("familyContextId", patient == null ? null : patient.familyContextId());
                    patientRecord.set("declarationSource", mapper.valueToTree(patient == null ? null : patient.source()));
                    ObjectNode disease = patientRecord.putObject("hypothesis");
                    if (hypothesis != null) {
                        disease.put("hgncId", hypothesis.hgncId()).put("mondoId", hypothesis.mondoId())
                                .put("moi", binding == null ? null : binding.normalizedMoi())
                                .set("provenance", mapper.valueToTree(binding == null ? null : binding.provenance().patientHypothesis().provenance()));
                    }
                    records.set("patient", patientRecord);
                    ObjectNode transcriptRecord = association(declaration, hypothesis, "TRANSCRIPT_CHOICE");
                    TranscriptChoice choice = choice(selection, null);
                    if (selection != null && choice != null) {
                        for (String field : List.of("accession", "version", "selectionPolicy", "annotationSource", "annotationSourceVersion"))
                            transcriptRecord.set(field, mapper.valueToTree(choice).path(field));
                        transcriptRecord.put("hgncId", hypothesis == null ? null : hypothesis.hgncId())
                                .put("geneSymbol", binding == null ? null : binding.symbol())
                                .put("selectionReason", selection.selectionReason())
                                .set("declarationSource", mapper.valueToTree(selection.source()));
                    }
                    // This pointer denotes the actual in-memory annotation snapshot, not a claimed new VEP execution.
                    transcriptRecord.put("annotationRecordLocator", "transcript/annotation");
                    if (candidate != null && binding != null && resolver.acceptsPreparation(binding) && (!bs1 || selection != null)) {
                        try {
                            var annotation = ExplicitEvidenceContextAssembler.selectedTranscript(candidate, binding, choice);
                            transcriptRecord.put("candidateAnnotationIndex", candidate.getTranscriptAnnotations().indexOf(annotation));
                            transcriptRecord.set("annotation", mapper.valueToTree(annotation));
                        } catch (ExplicitEvidenceContextAssembler.Blocked invalid) {
                            issue = blocker(invalid.reason, invalid.field);
                        }
                    }
                    records.set("transcript", transcriptRecord);
                    var ledger = ExplicitContextSourceLedger.create(mapper, mapper.createObjectNode()
                            .put("source", receiptSource).put("version", VERSION)
                            .put("dataClass", dataClass).set("records", records));
                    sources.add(ledger);
                    declarationRef = ledger.reference("declaration");
                    input = new ExplicitInput(candidateId, hypothesisId, binding, dataClass, choice(selection, ledger.reference("transcript")),
                            patient == null ? null : new PatientContext(patient.patientContextId(), patient.role(),
                                    supplied == null ? null : supplied.affectedStatus(), patient.sex(), patient.familyContextId(), ledger.reference("patient")), null);
                    verifyDeclaration(declaration, supplied, execution, analysis, bs1);
                    require(candidate != null, matches.isEmpty() ? "CANDIDATE_DECLARATION_NOT_MATCHED" : "CANDIDATE_ID_AMBIGUOUS", "candidateId");
                    if (recoveredSourceIssues.containsKey(candidateId)) throw new IntakeBlocked(recoveredSourceIssues.get(candidateId));
                    Blocker sourceIssue = CandidateAcmgInputAdapter.sourceIssue(mapper, job, conversion, execution, analysis, candidate, allowConvertedBa1 || bs1);
                    if (sourceIssue != null) throw new IntakeBlocked(sourceIssue);
                    if (issue == null) issue = new ExplicitEvidenceContextAssembler(mapper, resolver, sources, false, bs1).verifySupplement(analysis, candidate, input);
                } catch (IntakeBlocked blocked) {
                    issue = blocked.blocker;
                } catch (IOException | IllegalArgumentException error) {
                    issue = blocker("DECLARATION_RECEIPT_INVALID", "declaration");
                }
                inputs.add(input);
                if (issue != null) failures.put(input, issue);
                outcomes.add(new DeclarationOutcome(ordinal, candidateId, hypothesisId, declarationRef, issue));
            }
        }
        return new Intake(inputs, sources, outcomes, failures);
    }

    private void verifyDeclaration(CaseDeclaration declaration, HypothesisInput input, EngineExecutionResult execution,
                                   AnalysisContext analysis, boolean bs1) {
        require(Objects.equals(declaration.analysisId(), analysis.getAnalysisId()), "DECLARATION_ANALYSIS_CONFLICT", "declaration.analysisId");
        if (!(execution != null && execution.getInputSource() != null && declaration.executionSource() == execution.getInputSource())) { throw new IntakeBlocked(blocker("DECLARATION_EXECUTION_CONFLICT", "declaration.executionSource")); }
        require(text(declaration.sampleId()) && declaration.sampleId().equals(execution.getInputSource().sampleId()),
                "DECLARATION_SAMPLE_CONFLICT", "declaration.sampleId");
        PatientDeclaration patient = declaration.patient();
        if (patient == null) { throw new IntakeBlocked(blocker("PATIENT_DECLARATION_REQUIRED", "patient")); }
        require(text(patient.patientContextId()) && text(patient.role()) && text(patient.sex()), "PATIENT_FIELD_MISSING", "patient");
        require("PROBAND".equals(patient.role()), "SINGLE_PROBAND_SCOPE_REQUIRED", "patient.role");
        require(List.of("MALE", "FEMALE", "UNKNOWN").contains(patient.sex()), "PATIENT_SEX_INVALID", "patient.sex");
        require(sourceValid(patient.source()), "PATIENT_DECLARATION_SOURCE_REQUIRED", "patient.source");
        if (!(input != null && input.hypothesis() != null)) { throw new IntakeBlocked(blocker("DISEASE_HYPOTHESIS_REQUIRED", "hypothesis")); }
        require(sourceValid(input.hypothesis().provenance()), "HYPOTHESIS_PROVENANCE_REQUIRED", "hypothesis.provenance");
        require(List.of("AFFECTED", "UNAFFECTED", "UNKNOWN").contains(orEmpty(input.affectedStatus())),
                "DISEASE_AFFECTED_STATUS_REQUIRED", "hypothesis.affectedStatus");
        if (bs1 && input.transcript() == null) return;
        require(input.transcript() != null, "TRANSCRIPT_CHOICE_REQUIRED", "transcript");
        require(sourceValid(input.transcript().source()) && text(input.transcript().selectionReason()),
                "TRANSCRIPT_DECLARATION_SOURCE_REQUIRED", "transcript.source");
    }

    private ObjectNode association(CaseDeclaration declaration, PatientDiseaseHypothesis hypothesis, String kind) {
        return mapper.createObjectNode().put("kind", kind).put("claim", CLAIM).put("declarationRecordLocator", "declaration")
                .put("analysisId", declaration.analysisId()).put("sampleId", declaration.sampleId())
                .put("candidateId", hypothesis == null ? null : hypothesis.candidateId())
                .put("hypothesisId", hypothesis == null ? null : hypothesis.hypothesisId())
                .put("patientContextId", declaration.patient() == null ? null : declaration.patient().patientContextId());
    }

    private ObjectNode executionIdentity(EngineExecutionResult.InputSource witness, EngineExecutionResult execution) {
        ObjectNode identity = mapper.createObjectNode().put("liveExecutionMatched", execution != null && witness != null && witness == execution.getInputSource());
        if (witness != null) identity.put("analysisId", witness.analysisId()).put("analysisVcfArtifactSha256", witness.observedVcfSha256());
        if (execution != null && witness != null && witness == execution.getInputSource()) identity.put("requestSha256", execution.getRequestSha256())
                .put("commandSha256", execution.getCommandSha256()).put("outputSha256", execution.getMetadata().getOutputSha256());
        return identity;
    }

    private static TranscriptChoice choice(TranscriptSelection selection, SourceRef ref) {
        return selection == null ? null : new TranscriptChoice(selection.accession(), selection.version(), selection.selectionPolicy(),
                selection.annotationSource(), selection.annotationSourceVersion(), ref);
    }
    private static boolean sourceValid(HypothesisProvenance source) {
        if (source == null || !text(source.source()) || !text(source.version()) || !text(source.recordLocator())) return false;
        var hypothesis = new PatientDiseaseHypothesis(null, null, null, null, null, source);
        return hypothesis.equals(PatientDiseaseContextBindingResolver.safeHypothesis(hypothesis))
                && List.of(source.source(), source.version(), source.recordLocator()).stream().noneMatch(value ->
                value.chars().anyMatch(Character::isISOControl) || List.of(value.replace('\\', '/').split("/", -1)).contains(".."));
    }
    private static boolean text(String value) { return value != null && !value.isBlank() && value.equals(value.strip()); }
    private static String orEmpty(String value) { return value == null ? "" : value; }
    private static Blocker blocker(String reason, String field) { return new Blocker("CONTEXT_NOT_READY", reason, field); }
    private static void require(boolean condition, String reason, String field) {
        if (!condition) throw new IntakeBlocked(blocker(reason, field));
    }
    private static final class IntakeBlocked extends RuntimeException {
        private final Blocker blocker;
        private IntakeBlocked(Blocker blocker) { this.blocker = blocker; }
    }
}
