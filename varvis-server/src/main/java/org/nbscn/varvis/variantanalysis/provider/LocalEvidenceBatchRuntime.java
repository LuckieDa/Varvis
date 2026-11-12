package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.nbscn.varvis.variantanalysis.service.CurrentAcmgEvaluationSession;
import org.nbscn.varvis.variantanalysis.service.CurrentAdoptionInventory;
import org.springframework.lang.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/** Request-owned orchestration of existing modules; configuration and scientific approval remain server-owned. */
public final class LocalEvidenceBatchRuntime {
    private static final String FULL_RNA_COMPLETION_VERSION = "full-rna-completion-digest-v1";
    @Nullable private final DeNovoSelectedBatch deNovo;
    @Nullable private final Pm3SelectedBatch pm3;
    @Nullable private final Pp1SelectedBatch pp1;
    @Nullable private List<String> executionCriteria;
    public static final List<String> CRITERIA = List.of("ACMG_AMP_2015:PM2", "ACMG_AMP_2015:PP3",
            "ACMG_AMP_2015:BP4", "ACMG_AMP_2015:PS1", "ACMG_AMP_2015:PM5");
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final ObjectNode identity;
    @Nullable private final Supplier<Pm2LocalExomesRuntime> pm2Factory;
    @Nullable private final Supplier<SpliceAiLocalObservationSession> spliceFactory;
    @Nullable private final Function<SpliceAiLocalObservationSession, SelectedPredictionRuntime> predictionFactory;
    @Nullable private final ReferenceSetup reference;
    @Nullable private final Bp7SelectedBatch bp7;
    @Nullable private final Supplier<GeneralPm4Runtime> pm4Factory;
    @Nullable private final Supplier<Pvs1SelectedBatch> pvs1Factory;
    @Nullable private Supplier<PublishedPm5PtcRuntime> pm5PtcFactory;
    @Nullable private final Supplier<GeneralBp3Runtime> bp3Factory;
    @Nullable private final Supplier<GeneralPm1Runtime> pm1Factory;
    @Nullable private final Supplier<GeneralPp2Runtime> pp2Factory;
    @Nullable private final Supplier<GeneralBp1Runtime> bp1Factory;
    private final Map<SelectedPredictionRuntime.AttemptKey, Map<String,ObjectNode>> currentClinicalCalls=new LinkedHashMap<>();
    @Nullable private String requestId;
    @Nullable private ObjectNode saved;
    @Nullable private CurrentAcmgEvaluationSession currentSession;
    private boolean deferPp1;
    private boolean deNovoDependencyOnly;
    @Nullable private Supplier<ReviewedRuleBundle> fullRnaFactory;
    @Nullable private ReviewedRuleBundle fullRnaMaterials;
    @Nullable private ObjectNode fullRnaObservedIdentity;
    private final Map<String, ReviewedRuleBundle.FullRnaPrepared> fullRnaPrepared = new java.util.TreeMap<>();
    @Nullable private Supplier<Pp2OtherPhenotypeSource> pp2SourceFactory;
    @Nullable private Pp2OtherPhenotypeSource preparedPp2Source;
    @Nullable private java.util.function.Predicate<ObjectNode> pp2SourceScope;
    @Nullable private Pp2OtherPhenotypeSource.Observed pp2SourceObservation;
    @Nullable private ObjectNode pp2SourceIdentity;
    @Nullable private String pp2DiscoveryTargetsSha;

    /** Configuration metadata only. Source acquisition is selected and observed once before request reuse. */
    public LocalEvidenceBatchRuntime withPp2OtherPhenotypePreparation(@Nullable Supplier<Pp2OtherPhenotypeSource> factory,
            ObjectNode declaredSourceCapability, @Nullable java.util.function.Predicate<ObjectNode> scope) {
        if (requestId != null || pp2DiscoveryTargetsSha != null) throw new IllegalStateException("PP2_SOURCE_CONFIGURATION_ALREADY_BOUND");
        pp2SourceFactory = factory;
        pp2SourceScope = scope;
        if (pp2Factory != null) {
            var capability = identity.putObject("pp2HcmExecution").put("lifecycleVersion", "pp2-hcm-request-observation-v1")
                    .put("methodVersion", Pp2HcmPublishedSource.VERSION)
                    .put("transcriptScopeVersion", GeneralPp2Runtime.HCM_TRANSCRIPT_SCOPE_VERSION)
                    .put("sourceVersion", Pp2OtherPhenotypeSource.VERSION).put("archiveVersion", Pp2OtherPhenotypeArchive.VERSION)
                    .put("sourceMethodAdaptationVersion", PublishedCriterionKnowledge.PP2_HCM_SOURCE_METHOD_ADAPTATION_VERSION)
                    .put("sourceConfigured", factory != null);
            capability.set("sourceCapability", declaredSourceCapability.deepCopy());
        }
        return this;
    }

    public boolean needsPp2OtherPhenotypePreparation() { return pp2Factory != null && pp2SourceFactory != null && selected("PP2"); }

    /** Discovery declarations do not become evidence contexts. The evaluator later binds its actual prepared context. */
    public ObjectNode preparePp2OtherPhenotypeSources(List<Pp2OtherPhenotypeSource.DiscoveryTarget> declaredTargets) {
        if (!needsPp2OtherPhenotypePreparation()) return mapper.createObjectNode().put("status", "NOT_SELECTED");
        String targetSha = CodingTranscriptMaterialSupply.sha(mapper.valueToTree(declaredTargets).toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var priorTargetsSha = pp2DiscoveryTargetsSha;
        if (priorTargetsSha != null) {
            if (!priorTargetsSha.equals(targetSha)) throw new IllegalStateException("PP2_DISCOVERY_TARGETS_CHANGED_DURING_REQUEST");
            return Objects.requireNonNull(pp2SourceIdentity).deepCopy();
        }
        pp2DiscoveryTargetsSha = targetSha;
        try {
            var scopePredicate = Objects.requireNonNull(pp2SourceScope);
            var targets = new ArrayList<Pp2OtherPhenotypeSource.DiscoveryTarget>();
            for (var target : declaredTargets) {
                var scope = mapper.createObjectNode().put("moi", target.moi());
                scope.putObject("gene").put("hgncId", target.hgncId()).put("symbol", target.geneSymbol());
                scope.putObject("disease").put("mondoId", target.mondoId());
                scope.putObject("variant").set("evidenceQueryAllele", target.genomicAllele());
                if (scopePredicate.test(scope)) targets.add(target);
            }
            if (targets.isEmpty()) pp2SourceIdentity = mapper.createObjectNode().put("status", "NOT_REQUIRED_FOR_SELECTED_BACKGROUNDS");
            else {
                var source = Objects.requireNonNull(Objects.requireNonNull(pp2SourceFactory).get());
                if (!source.identity().equals(identity.at("/pp2HcmExecution/sourceCapability")))
                    throw new IllegalArgumentException("PP2_SOURCE_CAPABILITY_CHANGED_DURING_REQUEST");
                preparedPp2Source = source;
                var observed = source.observe(List.copyOf(targets)); pp2SourceObservation = observed;
                pp2SourceIdentity = observed.identity();
            }
        } catch (RuntimeException unavailable) {
            pp2SourceIdentity = mapper.createObjectNode().put("status", "UNAVAILABLE")
                    .put("reasonCode", reason(unavailable, "PP2_SOURCE_PREPARATION_UNAVAILABLE"));
        }
        return Objects.requireNonNull(pp2SourceIdentity).deepCopy();
    }

    private void appendPp2SourceArchive(ObjectNode batch) {
        var observed = pp2SourceObservation;
        if (observed == null) return;
        batch.set("pp2OtherPhenotypeSourceIdentity", observed.identity());
        var archive = Pp2OtherPhenotypeArchive.create(mapper, observed, batch);
        batch.set("pp2OtherPhenotypeArchive", archive);
        batch.set("pp2OtherPhenotypeArchiveSha256", archive.path("archiveSha256").deepCopy());
        var source = preparedPp2Source;
        if (source != null) {
            var derived = source.derivedArchive(observed);
            if (derived != null) {
                batch.set("pp2OtherPhenotypeDerivedArchive", derived);
                batch.set("pp2OtherPhenotypeDerivedArchiveSha256", derived.path("archiveSha256").deepCopy());
            }
        }
    }

    /** Trusted optional material supplier; shared preparation does not select or evaluate PVS1. */
    public LocalEvidenceBatchRuntime withFullRnaPreparation(@Nullable Supplier<ReviewedRuleBundle> factory) {
        return withFullRnaPreparation(factory, mapper.createObjectNode());
    }
    public LocalEvidenceBatchRuntime withFullRnaPreparation(@Nullable Supplier<ReviewedRuleBundle> factory, ObjectNode declaredPins) {
        if (requestId != null || fullRnaObservedIdentity != null) throw new IllegalStateException("FULL_RNA_CONFIGURATION_ALREADY_BOUND");
        fullRnaFactory = factory;
        if (factory != null) {
            var capability = identity.putObject("fullRnaExecution")
                    .put("methodVersion", FullRnaCurrentUse.VERSION).put("capabilityConfigured", true)
                    .put("completionVersion", FULL_RNA_COMPLETION_VERSION)
                    .put("publicProjectionVersion", FullRnaCurrentUse.PUBLIC_PROJECTION_VERSION)
                    .put("preparationVersion", ReviewedRuleBundle.FULL_RNA_PREPARATION_VERSION)
                    .put("sourceObservationBindingVersion", "1.0.0")
                    .put("pvs1Version", TruncatingNmdPvs1Runtime.FULL_RNA_EVALUATOR_VERSION)
                    .put("pvs1InitiationReviewSourceAdmissionVersion", Pvs1RnaInitiation.REVIEW_SOURCE_ADMISSION_VERSION)
                    .put("pvs1InitiationRescueDecisionVersion", Pvs1RnaInitiation.RESCUE_DECISION_VERSION)
                    .put("pvs1InitiationPositionScopeVersion", Pvs1SpliceConsequence.INITIATION_POSITION_SCOPE_VERSION)
                    .put("pm4Version", GeneralPm4Runtime.FULL_RNA_VERSION).put("bp3Version", GeneralBp3Runtime.FULL_RNA_VERSION)
                    .put("archiveVersion", FullRnaEvidenceArchive.VERSION);
            capability.set("declaredPins", declaredPins.deepCopy());
        } else identity.remove("fullRnaExecution");
        return this;
    }
    public LocalEvidenceBatchRuntime withPublishedPm5Ptc(Supplier<PublishedPm5PtcRuntime> factory) {
        if (requestId != null || executionCriteria != null) throw new IllegalStateException("PM5_PTC_RUNTIME_ALREADY_BOUND");
        pm5PtcFactory = Objects.requireNonNull(factory);
        identity.putObject("pm5PtcExecution").put("methodVersion", PublishedPm5PtcRuntime.VERSION)
                .put("sourceTableSha256", PublishedPm5PtcTable.identity());
        return this;
    }

    public boolean needsFullRnaSourceIdentity() {
        return fullRnaFactory != null && (selected("PVS1") || selected("PM4") || selected("BP3")
                || pm5PtcFactory != null && selected("PM5"));
    }
    private boolean fullRnaSelectionRequiresPreparation() {
        var observed = fullRnaObservedIdentity;
        return needsFullRnaSourceIdentity() && (observed == null || observed.path("archiveRequired").asBoolean(true));
    }
    /** Must be called before the request/cache lookup. A source change has no timestamp-based exception. */
    public ObjectNode prepareFullRnaSourceIdentity() {
        var factory = fullRnaFactory;
        if (!needsFullRnaSourceIdentity() || factory == null) return mapper.createObjectNode().put("status", "NOT_SELECTED").put("archiveRequired", false);
        fullRnaMaterials = null;
        ObjectNode observed;
        try {
            var bundle = Objects.requireNonNull(factory.get()); fullRnaMaterials = bundle;
            observed = bundle.hasFullRnaSources() ? bundle.fullRnaObservedSourceIdentity()
                    : mapper.createObjectNode().put("schemaVersion", "full-rna-observed-source-identity-v1").put("status", "NOT_CONFIGURED");
            observed.put("sourceIndexConfigured", bundle.hasFullRnaSources()).put("archiveRequired", bundle.hasFullRnaSources())
                    .put("materialIdentitySha256", adapter.canonicalSha256(bundle.identity()));
        } catch (RuntimeException unavailable) {
            observed = mapper.createObjectNode().put("schemaVersion", "full-rna-observed-source-identity-v1")
                    .put("status", "UNAVAILABLE").put("reasonCode", "FULL_RNA_CONTROLLED_CONFIGURATION_UNAVAILABLE")
                    .put("sourceIndexConfigured", "UNKNOWN").put("archiveRequired", true);
            Throwable cause = unavailable;
            for (int depth = 0; cause != null && depth < 4; depth++, cause = cause.getCause()) {
                String code = cause.getMessage();
                if (code != null && code.matches("[A-Z][A-Z0-9_]{1,120}") && !code.equals("FULL_RNA_CONTROLLED_BUNDLE_UNAVAILABLE"))
                    observed.put("configurationReasonCode", code);
            }
        }
        fullRnaObservedIdentity = observed; return observed.deepCopy();
    }
    @Nullable private ReviewedRuleBundle.FullRnaPrepared fullRnaPrepare(SelectedEvidencePreparation selected,
            String candidate, String hypothesis, String sourceSha) {
        var bundle = fullRnaMaterials;
        if (!needsFullRnaSourceIdentity()) return null;
        String key = candidate + "/" + hypothesis;
        var previous = fullRnaPrepared.get(key); if (previous != null) return previous.targetsFullRna() ? previous : null;
        var observed = fullRnaObservedIdentity;
        if (observed != null && !observed.path("archiveRequired").asBoolean(true)) return null;
        if (bundle == null) {
            var failure = ReviewedRuleBundle.unavailableFullRna(mapper, selected.context(candidate, hypothesis), observed == null
                    ? "FULL_RNA_PRE_REQUEST_SOURCE_IDENTITY_REQUIRED" : observed.path("reasonCode").asText("FULL_RNA_CONTROLLED_CONFIGURATION_UNAVAILABLE"), sourceSha)
                    .withConfigurationReason(observed == null ? "" : observed.path("configurationReasonCode").asText(""));
            fullRnaPrepared.put(key, failure); return failure;
        }
        var prepared = bundle.prepareFullRna(selected, candidate, hypothesis, sourceSha);
        if (!prepared.targetsFullRna()) { fullRnaPrepared.put(key, prepared); return null; }
        var snapshot = prepared.snapshot();
        var originalSha = snapshot.at("/sourceReplay/originalSha256");
        if (observed == null) prepared = prepared.withoutQualification("FULL_RNA_PRE_REQUEST_SOURCE_IDENTITY_REQUIRED");
        else if (originalSha.isTextual()) {
            int found = 0; boolean exact = false; String observedIssue = "FULL_RNA_SOURCE_CHANGED_AFTER_REQUEST_IDENTITY";
            for (var row : observed.path("sources")) if (snapshot.path("entryId").equals(row.path("entryId"))) {
                found++; exact = "READ".equals(row.path("status").asText())
                        && originalSha.equals(row.path("observedSha256"));
                if (!"READ".equals(row.path("status").asText())) observedIssue = row.path("reasonCode").asText("FULL_RNA_SOURCE_IDENTITY_READ_UNAVAILABLE");
            }
            if (found != 1 || !exact) prepared = prepared.withoutQualification(observedIssue);
        }
        fullRnaPrepared.put(key, prepared); return prepared;
    }
    /** Before-gate audit only. UNAVAILABLE denotes a genuinely unrecovered execution source, never a scientific context. */
    public ObjectNode notExecutedFullRnaSnapshot(String sourceSha, String reasonCode) {
        if (!needsFullRnaSourceIdentity() && pp2SourceObservation == null) throw new IllegalStateException("FULL_RNA_NOT_SELECTED");
        if (!sourceSha.matches("[a-f0-9]{64}|UNAVAILABLE")) throw new IllegalArgumentException("FULL_RNA_EXECUTION_SOURCE_AUDIT_INVALID");
        var out = mapper.createObjectNode().put("schemaVersion", "local-evidence-batch-v1")
                .put("status", "NOT_EXECUTED").put("executionMode", "NOT_EXECUTED").put("reasonCode", reasonCode)
                .put("sourceSnapshotSha256", sourceSha).put("automaticTrigger", false).put("classificationEnabled", false);
        out.set("identity", identity()); out.set("executionCriteria", mapper.valueToTree(executionCriteria == null ? enabledCriteria() : executionCriteria));
        var observed = fullRnaObservedIdentity;
        if (observed != null) out.set("fullRnaObservedSourceIdentity", observed.deepCopy());
        out.putObject("modules"); out.putArray("results");
        if (FullRnaEvidenceArchive.required(out)) {
            var archive = FullRnaEvidenceArchive.create(mapper, List.of(), out);
            out.set("fullRnaArchive", archive); out.set("fullRnaArchiveSha256", archive.path("archiveSha256").deepCopy());
        }
        appendPp2SourceArchive(out); return out;
    }

    /** Fact declarations and approved bundle factories come from trusted configuration, never submitted JSON. */
    public record ReferenceSetup(Supplier<ClinVarConditionAwareComparatorInput> comparatorFactory,
            ObjectNode sourceSnapshot, @Nullable Path syntheticOutputRoot,
            @Nullable BiFunction<String, ClinVarConditionAwareComparatorInput.SnapshotCapture, ReviewedRuleBundle> approvedBundleFactory,
            @Nullable BiFunction<String, ObjectNode, String> materialUseGate,
            @Nullable Supplier<Ps1SpliceRuntime> spliceComparisonFactory) {
        public ReferenceSetup(Supplier<ClinVarConditionAwareComparatorInput> comparatorFactory, ObjectNode sourceSnapshot,
                @Nullable Path syntheticOutputRoot,
                @Nullable BiFunction<String, ClinVarConditionAwareComparatorInput.SnapshotCapture, ReviewedRuleBundle> approvedBundleFactory,
                @Nullable BiFunction<String, ObjectNode, String> materialUseGate) {
            this(comparatorFactory, sourceSnapshot, syntheticOutputRoot, approvedBundleFactory, materialUseGate, null);
        }
        public ReferenceSetup(Supplier<ClinVarConditionAwareComparatorInput> comparatorFactory, ObjectNode sourceSnapshot,
                @Nullable Path syntheticOutputRoot,
                @Nullable BiFunction<String, ClinVarConditionAwareComparatorInput.SnapshotCapture, ReviewedRuleBundle> approvedBundleFactory) {
            this(comparatorFactory, sourceSnapshot, syntheticOutputRoot, approvedBundleFactory, null);
        }
        public ReferenceSetup {
            Objects.requireNonNull(comparatorFactory); sourceSnapshot = Objects.requireNonNull(sourceSnapshot).deepCopy();
        }
        @Override public ObjectNode sourceSnapshot() { return sourceSnapshot.deepCopy(); }
    }

    /** Factories are lazy and request-local. Identity/capabilities never execute a provider. */
    public LocalEvidenceBatchRuntime(ObjectMapper mapper, ObjectNode trustedConfigurationIdentity,
            @Nullable Supplier<Pm2LocalExomesRuntime> pm2Factory,
            @Nullable Supplier<SpliceAiLocalObservationSession> spliceFactory,
            @Nullable Function<SpliceAiLocalObservationSession, SelectedPredictionRuntime> predictionFactory,
            @Nullable ReferenceSetup reference) {
        this(mapper, trustedConfigurationIdentity, pm2Factory, spliceFactory, predictionFactory, reference, null, null);
    }

    public LocalEvidenceBatchRuntime(ObjectMapper mapper, ObjectNode trustedConfigurationIdentity,
            @Nullable Supplier<Pm2LocalExomesRuntime> pm2Factory,
            @Nullable Supplier<SpliceAiLocalObservationSession> spliceFactory,
            @Nullable Function<SpliceAiLocalObservationSession, SelectedPredictionRuntime> predictionFactory,
            @Nullable ReferenceSetup reference, @Nullable Bp7SelectedBatch bp7) {
        this(mapper, trustedConfigurationIdentity, pm2Factory, spliceFactory, predictionFactory, reference, bp7, null);
    }

    public LocalEvidenceBatchRuntime(ObjectMapper mapper, ObjectNode trustedConfigurationIdentity,
            @Nullable Supplier<Pm2LocalExomesRuntime> pm2Factory,
            @Nullable Supplier<SpliceAiLocalObservationSession> spliceFactory,
            @Nullable Function<SpliceAiLocalObservationSession, SelectedPredictionRuntime> predictionFactory,
            @Nullable ReferenceSetup reference, @Nullable Supplier<GeneralPm4Runtime> pm4Factory) {
        this(mapper, trustedConfigurationIdentity, pm2Factory, spliceFactory, predictionFactory, reference, null, pm4Factory);
    }

    public LocalEvidenceBatchRuntime(ObjectMapper mapper, ObjectNode trustedConfigurationIdentity,
            @Nullable Supplier<Pm2LocalExomesRuntime> pm2Factory,
            @Nullable Supplier<SpliceAiLocalObservationSession> spliceFactory,
            @Nullable Function<SpliceAiLocalObservationSession, SelectedPredictionRuntime> predictionFactory,
            @Nullable ReferenceSetup reference, @Nullable Bp7SelectedBatch bp7,
            @Nullable Supplier<GeneralPm4Runtime> pm4Factory) {
        this(mapper, trustedConfigurationIdentity, pm2Factory, spliceFactory, predictionFactory, reference, bp7, pm4Factory, null, null);
    }

    public LocalEvidenceBatchRuntime(ObjectMapper mapper, ObjectNode trustedConfigurationIdentity,
            @Nullable Supplier<Pm2LocalExomesRuntime> pm2Factory, @Nullable Supplier<SpliceAiLocalObservationSession> spliceFactory,
            @Nullable Function<SpliceAiLocalObservationSession, SelectedPredictionRuntime> predictionFactory,
            @Nullable ReferenceSetup reference, @Nullable Bp7SelectedBatch bp7, @Nullable Supplier<GeneralPm4Runtime> pm4Factory,
            @Nullable Supplier<Pvs1SelectedBatch> pvs1Factory, @Nullable Supplier<GeneralBp3Runtime> bp3Factory) {
        this(mapper, trustedConfigurationIdentity, pm2Factory, spliceFactory, predictionFactory, reference, bp7,
                pm4Factory, pvs1Factory, bp3Factory, null, null, null, false);
    }

    public LocalEvidenceBatchRuntime(ObjectMapper mapper, ObjectNode trustedConfigurationIdentity,
            @Nullable Supplier<Pm2LocalExomesRuntime> pm2Factory, @Nullable Supplier<SpliceAiLocalObservationSession> spliceFactory,
            @Nullable Function<SpliceAiLocalObservationSession, SelectedPredictionRuntime> predictionFactory,
            @Nullable ReferenceSetup reference, @Nullable Bp7SelectedBatch bp7, @Nullable Supplier<GeneralPm4Runtime> pm4Factory,
            @Nullable Supplier<Pvs1SelectedBatch> pvs1Factory, @Nullable Supplier<GeneralBp3Runtime> bp3Factory,
            @Nullable Supplier<GeneralPm1Runtime> pm1Factory, @Nullable Supplier<GeneralPp2Runtime> pp2Factory,
            @Nullable Supplier<GeneralBp1Runtime> bp1Factory, boolean pp2GermlineDeclared) {
        this(mapper, trustedConfigurationIdentity, pm2Factory, spliceFactory, predictionFactory, reference, bp7,
                pm4Factory, pvs1Factory, bp3Factory, pm1Factory, pp2Factory, bp1Factory, pp2GermlineDeclared, null);
    }

    public LocalEvidenceBatchRuntime(ObjectMapper mapper, ObjectNode trustedConfigurationIdentity,
            @Nullable Supplier<Pm2LocalExomesRuntime> pm2Factory, @Nullable Supplier<SpliceAiLocalObservationSession> spliceFactory,
            @Nullable Function<SpliceAiLocalObservationSession, SelectedPredictionRuntime> predictionFactory,
            @Nullable ReferenceSetup reference, @Nullable Bp7SelectedBatch bp7, @Nullable Supplier<GeneralPm4Runtime> pm4Factory,
            @Nullable Supplier<Pvs1SelectedBatch> pvs1Factory, @Nullable Supplier<GeneralBp3Runtime> bp3Factory,
            @Nullable Supplier<GeneralPm1Runtime> pm1Factory, @Nullable Supplier<GeneralPp2Runtime> pp2Factory,
            @Nullable Supplier<GeneralBp1Runtime> bp1Factory, boolean pp2GermlineDeclared, @Nullable DeNovoSelectedBatch deNovo) {
        this(mapper,trustedConfigurationIdentity,pm2Factory,spliceFactory,predictionFactory,reference,bp7,pm4Factory,
                pvs1Factory,bp3Factory,pm1Factory,pp2Factory,bp1Factory,pp2GermlineDeclared,deNovo,null);
    }
    public LocalEvidenceBatchRuntime(ObjectMapper mapper, ObjectNode trustedConfigurationIdentity,
            @Nullable Supplier<Pm2LocalExomesRuntime> pm2Factory, @Nullable Supplier<SpliceAiLocalObservationSession> spliceFactory,
            @Nullable Function<SpliceAiLocalObservationSession, SelectedPredictionRuntime> predictionFactory,
            @Nullable ReferenceSetup reference, @Nullable Bp7SelectedBatch bp7, @Nullable Supplier<GeneralPm4Runtime> pm4Factory,
            @Nullable Supplier<Pvs1SelectedBatch> pvs1Factory, @Nullable Supplier<GeneralBp3Runtime> bp3Factory,
            @Nullable Supplier<GeneralPm1Runtime> pm1Factory, @Nullable Supplier<GeneralPp2Runtime> pp2Factory,
            @Nullable Supplier<GeneralBp1Runtime> bp1Factory, boolean pp2GermlineDeclared, @Nullable DeNovoSelectedBatch deNovo,
            @Nullable Pm3SelectedBatch pm3) {
        this(mapper,trustedConfigurationIdentity,pm2Factory,spliceFactory,predictionFactory,reference,bp7,pm4Factory,
                pvs1Factory,bp3Factory,pm1Factory,pp2Factory,bp1Factory,pp2GermlineDeclared,deNovo,pm3,null);
    }
    public LocalEvidenceBatchRuntime(ObjectMapper mapper, ObjectNode trustedConfigurationIdentity,
            @Nullable Supplier<Pm2LocalExomesRuntime> pm2Factory, @Nullable Supplier<SpliceAiLocalObservationSession> spliceFactory,
            @Nullable Function<SpliceAiLocalObservationSession, SelectedPredictionRuntime> predictionFactory,
            @Nullable ReferenceSetup reference, @Nullable Bp7SelectedBatch bp7, @Nullable Supplier<GeneralPm4Runtime> pm4Factory,
            @Nullable Supplier<Pvs1SelectedBatch> pvs1Factory, @Nullable Supplier<GeneralBp3Runtime> bp3Factory,
            @Nullable Supplier<GeneralPm1Runtime> pm1Factory, @Nullable Supplier<GeneralPp2Runtime> pp2Factory,
            @Nullable Supplier<GeneralBp1Runtime> bp1Factory, boolean pp2GermlineDeclared, @Nullable DeNovoSelectedBatch deNovo,
            @Nullable Pm3SelectedBatch pm3, @Nullable Pp1SelectedBatch pp1) {
        this.deNovo = deNovo;
        this.pm3=pm3;
        this.pp1=pp1;
        boolean published = pm1Factory != null || pp2Factory != null || bp1Factory != null;
        if (published && (pm1Factory == null || pp2Factory == null || bp1Factory == null || pvs1Factory == null || bp3Factory == null))
            throw new IllegalArgumentException("PUBLISHED_LOCAL_ALL_COMPONENTS_REQUIRED");
        this.pm1Factory = pm1Factory; this.pp2Factory = pp2Factory; this.bp1Factory = bp1Factory;
        if ((pvs1Factory != null || bp3Factory != null) && (pvs1Factory == null || bp3Factory == null || pm4Factory == null || bp7 == null))
            throw new IllegalArgumentException("EXTENDED_LOCAL_ALL_COMPONENTS_REQUIRED");
        this.pm4Factory = pm4Factory; this.pvs1Factory = pvs1Factory; this.bp3Factory = bp3Factory;
        this.mapper = Objects.requireNonNull(mapper); adapter = new RuntimeEvidenceV2Adapter(mapper);
        this.pm2Factory = pm2Factory; this.spliceFactory = spliceFactory; this.predictionFactory = predictionFactory; this.reference = reference;
        this.bp7 = bp7;
        identity = mapper.createObjectNode().put("schemaVersion", "local-evidence-batch-binding-v1")
                .put("applicabilityRoutingVersion", SelectedCriterionRouting.VERSION)
                .put("materialPreparationSummaryVersion", "material-preparation-summary-v1")
                .put("configurationSha256", adapter.canonicalSha256(Objects.requireNonNull(trustedConfigurationIdentity)))
                .put("pm2Configured", pm2Factory != null).put("predictionConfigured", predictionFactory != null)
                .put("referenceConfigured", reference != null).put("sharedSpliceConfigured", spliceFactory != null)
                .put("resourceReadiness", "NOT_CHECKED").put("scientificApproval", "NOT_GRANTED");
        if (reference != null) identity.put("referenceSourceSnapshotSha256", adapter.canonicalSha256(reference.sourceSnapshot()));
        if (reference != null && reference.materialUseGate() != null) identity.put("referenceDraftRoutingVersion", "1.0.0");
        identity.set("applicabilityRoutingCapabilities", SelectedCriterionRouting.executionIdentity(mapper));
        if (published) {
            identity.set("pm1ConditionalPeerExecution", GeneralPm1Runtime.executionIdentity(mapper));
            identity.set("bp1TruncatingConditionExecution", GeneralBp1Runtime.truncatingConditionIdentity(mapper));
        }
        if (bp7 != null) identity.set("bp7", bp7.identity());
        identity.set("capabilities", capabilities());
        if (pm4Factory != null) identity.put("pm4Configured", true).put("pm4PolicyVersion", GeneralPm4Runtime.VERSION);
        if (pvs1Factory != null) identity.put("pvs1Configured", true).put("pvs1SelectedVersion", Pvs1SelectedBatch.VERSION);
        if (bp3Factory != null) identity.put("bp3Configured", true).put("bp3PolicyVersion", GeneralBp3Runtime.VERSION)
                .put("bp3CodingSequenceProjectionVersion", org.nbscn.varvis.variantanalysis.service.LegacyContextProjection.CODING_SEQUENCE_VERSION);
        if (published) identity.put("pm1Configured", true).put("pp2Configured", true).put("bp1Configured", true)
                .put("pm1GeneralVersion", GeneralPm1Runtime.VERSION).put("pp2GeneralVersion", GeneralPp2Runtime.VERSION)
                .put("bp1GeneralVersion", GeneralBp1Runtime.VERSION).put("pp2GermlineDeclared", pp2GermlineDeclared);
        if (deNovo != null) identity.set("deNovo", deNovo.identity());
        if (pm3 != null) identity.set("pm3",pm3.identity());
        if (pp1 != null) identity.set("pp1",pp1.identity());
    }

    /** Native scheduler receipt issued only after all actual phase-one attempts finish.
     * Its JSON is an audit copy, never a constructor or authority restoration path.
     */
    public static final class CurrentConsumerExecution {
        private final CurrentAcmgEvaluationSession session;
        private final ObjectNode value;
        @com.fasterxml.jackson.annotation.JsonCreator(mode=com.fasterxml.jackson.annotation.JsonCreator.Mode.DISABLED)
        private CurrentConsumerExecution(CurrentAcmgEvaluationSession session, ObjectNode value) {
            this.session=session; this.value=value.deepCopy();
        }
        public boolean matches(CurrentAcmgEvaluationSession target) { return target==session; }
        public ObjectNode snapshot() { return value.deepCopy(); }
    }
    public java.util.Optional<Pp1CurrentContributionSource.Factory> currentPp1Factory() {
        return pp1==null ? java.util.Optional.empty() : pp1.currentNativeFactory();
    }
    public void bindCurrentPp1(CurrentAcmgEvaluationSession session, CurrentAdoptionInventory inventory) {
        var pp1 = this.pp1;
        if(pp1==null || currentSession!=session || !deferPp1) throw new IllegalStateException("PP1_CURRENT_LATE_BIND_PHASE_INVALID");
        pp1.bindCurrentContributions(session,inventory);
    }
    public boolean canSealPp4Registry() {
        return hasPp1() && pp1 != null && pp1.hasWorkbenchRegistry();
    }
    public void sealPp4Registry(CurrentAcmgEvaluationSession session, CurrentConsumerExecution execution) {
        var module = pp1;
        if (module == null || currentSession != session || !deferPp1)
            throw new IllegalStateException("PP1_CURRENT_LATE_BIND_PHASE_INVALID");
        module.bindUnregisteredPp4(session,execution);
    }
    public ObjectNode identity() { return identity.deepCopy(); }
    public boolean hasPm3(){return pm3!=null && selected("PM3");}
    public boolean hasPp1(){return pp1!=null && selected("PP1");}
    public void bindExecutionCriteria(List<String> criteria) {
        if(requestId!=null || executionCriteria!=null || criteria.isEmpty() || criteria.stream().anyMatch(c -> !enabledCriteria().contains(c) && !List.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1").contains(c)))
            throw new IllegalStateException("LOCAL_BATCH_EXECUTION_CRITERIA_INVALID");
        executionCriteria=List.copyOf(criteria);
    }
    public ObjectNode preparePp1Sources(String analysis,ObjectNode recovered) {
        var pp1 = this.pp1;
        return pp1 != null && selected("PP1") ? pp1.prepare(analysis, recovered) : mapper.createObjectNode();
    }
    /** Captured before ordinary business request hashing and result reuse. */
    public ObjectNode preparePm3Sources(String analysis,ObjectNode recovered) {
        var pm3 = this.pm3;
        return pm3 != null && selected("PM3") ? pm3.prepare(analysis, recovered) : mapper.createObjectNode();
    }
    private boolean selected(String code) { return executionCriteria == null || executionCriteria.contains("ACMG_AMP_2015:" + code); }
    private boolean needsPs1Baseline(JsonNode route) {
        if (!selected("PS1") || reference == null || reference.spliceComparisonFactory() == null || route == null) return false;
        for (var term : route.path("consequenceTerms"))
            if (Set.of("splice_donor_variant", "splice_acceptor_variant").contains(term.asText())) return true;
        return false;
    }
    // BP7 consumes a replayable BP4 splice result, even when BP4 is not an output criterion.
    private List<String> predictionDependencyCriteria(boolean pm1NeedsPp3) {
        var required = new java.util.ArrayList<String>();
        if (selected("PP3") || pm1NeedsPp3) required.add("ACMG_AMP_2015:PP3");
        if (selected("BP4") || selected("BP7")) required.add("ACMG_AMP_2015:BP4");
        return List.copyOf(required);
    }
    private boolean predictionsSelected() { return selected("PP3") || selected("BP4"); }
    private static boolean genomicPm5(JsonNode route) {
        return "GENOMIC_PTC".equals(route.at("/criteria/PM5/branch").asText());
    }

    private static boolean needsGenomicPtc(JsonNode route) {
        return genomicPm5(route) && !SelectedCriterionRouting.skip(route, "PM5");
    }

    private boolean classicPm5(JsonNode route) {
        return selected("PM5") && !genomicPm5(route) && !SelectedCriterionRouting.skip(route, "PM5");
    }
    private boolean deNovoSelected() { return deNovo != null && (selected("PS2") || selected("PM6") || deNovoDependencyOnly); }
    private List<String> deNovoEvaluationCriteria() {
        if(!deNovoDependencyOnly) return selectedLocalCriteria();
        var criteria=new java.util.LinkedHashSet<String>(selectedLocalCriteria());
        criteria.addAll(DeNovoSelectedBatch.CRITERIA); return List.copyOf(criteria);
    }
    private boolean eligibleCurrentDependency(@Nullable CurrentAcmgEvaluationSession session) {
        if(session==null || deNovo==null || selected("PS2") || selected("PM6")) return false;
        var inputs=currentPp1Factory().flatMap(value -> Objects.requireNonNull(value).registeredInputs());
        if(inputs.isEmpty()) return false;
        var registered=inputs.orElseThrow();
        for(var attempt:session.attempts()) {
            var candidate=attempt.at("/binding/identity/preparedContext");
            if("READY".equals(attempt.path("bindingStatus").asText()) && candidate instanceof ObjectNode context
                    && registered.rules().matchesContext(context)
                    && (Pp4QualifiedMaterial.verify(registered.registration(),context).isComplete()
                        || !registered.clinicalSampleLinks().isEmpty()&&Pp4QualifiedMaterial.mayNeedNativeIdentityForPlanning(registered.registration(),context))) return true;
        }
        return false;
    }
    private List<String> selectedLocalCriteria() { return enabledCriteria().stream().filter(c -> executionCriteria == null || executionCriteria.contains(c)).toList(); }
    public Set<String> deNovoDiscoveryCandidates(SelectedEvidencePreparation selected, List<String> criteria) {
        return deNovo == null ? Set.of() : deNovo.localDiscoveryCandidates(selected, criteria);
    }
    public List<DeNovoSelectedBatch.PublishedBackground> deNovoPublishedBackgrounds(List<String> criteria, String dataClass) {
        var module = deNovo;
        return module == null ? List.of() : module.publishedBackgrounds(criteria, dataClass);
    }

    public ObjectNode deNovoSourceTaskStatus(String id) throws java.io.IOException {
        if (deNovo==null) throw new java.io.IOException("DENOVO_TASK_NOT_CONFIGURED");
        return deNovo.sourceTaskStatus(id);
    }
    public void explicitDeNovoSourceRetry(String id) {
        if(requestId!=null) throw new IllegalStateException("DENOVO_TASK_RETRY_AFTER_EXECUTION");
        if(deNovo!=null) deNovo.explicitSourceRetry(id);
    }
    public List<String> enabledCriteria() {
        var result = new ArrayList<>(criteria(bp7 != null, pm4Factory != null, pvs1Factory != null, bp3Factory != null, pm1Factory != null));
        if (deNovo != null) result.addAll(DeNovoSelectedBatch.CRITERIA);
        if (pm3 != null) result.add(Pm3SelectedBatch.CRITERION);
        if (pp1 != null) result.add(Pp1SelectedBatch.CRITERION);
        return List.copyOf(result);
    }
    public static List<String> criteria(boolean pm4) { return criteria(false, pm4); }
    public static List<String> criteria(boolean bp7, boolean pm4) { return criteria(bp7, pm4, false, false); }
    public static List<String> criteria(boolean bp7, boolean pm4, boolean pvs1, boolean bp3) {
        return criteria(bp7, pm4, pvs1, bp3, false);
    }
    public static List<String> criteria(boolean bp7, boolean pm4, boolean pvs1, boolean bp3, boolean published) {
        var result = new ArrayList<>(CRITERIA);
        if (bp7) result.add(Bp7SelectedBatch.CRITERION);
        if (pvs1) result.add(Pvs1SelectedBatch.CRITERION);
        if (pm4) result.add(GeneralPm4Runtime.CRITERION);
        if (bp3) result.add(GeneralBp3Runtime.CRITERION);
        if (published) result.addAll(List.of(GeneralPm1Runtime.CRITERION, GeneralPp2Runtime.CRITERION, GeneralBp1Runtime.CRITERION));
        return List.copyOf(result);
    }
    public ObjectNode capabilities() {
        var out = mapper.createObjectNode().put("schemaVersion", "local-evidence-batch-capabilities-v1")
                .put("automaticTrigger", false).put("formalEnabled", false).put("classificationEnabled", false)
                .put("sharedSpliceScope", "OBSERVATION_ONLY").put("referenceScope", "NATIVE_SNV_CONTIGUOUS_CODON_ONLY")
                .put("patientMode", "FACT_CAPTURE_ONLY")
                .put("patientDraftGate", "REFERENCE_DRAFT_DATA_CLASS_UNSUPPORTED")
                .put("patientMaterialGate", "REFERENCE_PATIENT_MATERIAL_QUALIFICATION_UNAVAILABLE");
        if (reference != null && reference.materialUseGate() != null)
            out.put("patientMode", "EXPLICIT_QUALIFIED_PUBLIC_MATERIAL_REQUIRED");
        out.set("criteria", mapper.valueToTree(enabledCriteria())); return out;
    }

    /** Exact repeat reads the saved result; this object cannot be reused for a different prepared request. */
    public synchronized ObjectNode execute(SelectedEvidencePreparation selected, AnalysisContext analysis, String trustedSourceSha256) {
        return execute(selected, analysis, trustedSourceSha256, selected);
    }

    public synchronized ObjectNode execute(SelectedEvidencePreparation selected, AnalysisContext analysis,
            String trustedSourceSha256, SelectedEvidencePreparation pp1Selected) {
        return execute(selected,analysis,trustedSourceSha256,pp1Selected,null,null);
    }
    /** Normal postclaim path: the service consumes the native stage-one receipt, archives
     * the actual inventory, then late-binds PP1 once. Other modules are never rerun.
     */
    public synchronized ObjectNode execute(SelectedEvidencePreparation selected, AnalysisContext analysis,
            String trustedSourceSha256, SelectedEvidencePreparation pp1Selected,
            @Nullable CurrentAcmgEvaluationSession session,
            @Nullable java.util.function.Consumer<CurrentConsumerExecution> beforePp1) {
        var pp1 = this.pp1;
        var pm3 = this.pm3;
        var deNovo = this.deNovo;
        var bp7 = this.bp7;
        var pm4Factory = this.pm4Factory;
        var pvs1Factory = this.pvs1Factory;
        var bp3Factory = this.bp3Factory;
        var pm1Factory = this.pm1Factory;
        var pp2Factory = this.pp2Factory;
        var bp1Factory = this.bp1Factory;
        var spliceFactory = this.spliceFactory;
        var reference = this.reference;
        if((session==null)!=(beforePp1==null) || session!=null && !hasPp1())
            throw new IllegalArgumentException("CURRENT_CONSUMER_EXECUTION_PHASE_INVALID");
        if(currentSession!=null && currentSession!=session)
            throw new IllegalArgumentException("CURRENT_CONSUMER_EXECUTION_SESSION_MISMATCH");
        if(saved!=null && session!=null && currentSession==null)
            throw new IllegalArgumentException("CURRENT_CONSUMER_EXECUTION_SESSION_MISMATCH");
        Objects.requireNonNull(selected); Objects.requireNonNull(analysis);
        Objects.requireNonNull(pp1Selected);
        if (!selected.assembly().candidateIds().equals(pp1Selected.assembly().candidateIds())
                || !selected.assembly().analysisId().equals(pp1Selected.assembly().analysisId()))
            throw new IllegalArgumentException("PP1_SELECTION_SOURCE_ASSOCIATION_CONFLICT");
        var binding = mapper.createObjectNode().put("analysisId", analysis.getAnalysisId())
                .put("assembly", analysis.getGenomeAssembly() == null ? null : analysis.getGenomeAssembly().name())
                .put("sourceSnapshotSha256", trustedSourceSha256);
        binding.set("executionCriteria", mapper.valueToTree(executionCriteria == null ? enabledCriteria() : executionCriteria)); binding.set("identity", identity); binding.set("preparation", mapper.valueToTree(selected.assembly()));
        var observedFullRna = fullRnaObservedIdentity;
        if (observedFullRna != null) binding.set("fullRnaObservedSourceIdentity", observedFullRna.deepCopy());
        var observedPp2 = pp2SourceIdentity;
        if (observedPp2 != null) binding.set("pp2OtherPhenotypeSourceIdentity", observedPp2.deepCopy());
        if (pm3 != null && selected("PM3")) {
            if (saved == null) pm3.supply(selected);
            binding.put("pm3SourceSnapshotSha256",pm3.originals().path("snapshotSha256").asText());
        }
        if (pp1 != null && selected("PP1"))binding.put("pp1SourceSnapshotSha256",pp1.originals().path("snapshotSha256").asText());
        binding.set("sourceProvenance", selected.provenance());
        var receipts = binding.putArray("receipts"); selected.sources().forEach(source -> receipts.add(source.receipt(mapper)));
        if (hasPp1() && pp1Selected != selected) {
            binding.set("pp1SelectionPreparation", mapper.valueToTree(pp1Selected.assembly()));
            binding.set("pp1SelectionSourceProvenance", pp1Selected.provenance());
            // Preserve the exact references used by the separately scoped selection.
            pp1Selected.sources().forEach(source -> {
                var receipt = source.receipt(mapper);
                if (java.util.stream.StreamSupport.stream(receipts.spliterator(), false).noneMatch(receipt::equals))
                    receipts.add(receipt);
            });
        }
        var candidatePins = binding.putObject("candidateSnapshotSha256");
        if (pp1Selected != selected) for (String candidateId : selected.assembly().candidateIds()) {
            if (!selected.formalCandidate(candidateId).equals(pp1Selected.formalCandidate(candidateId)))
                throw new IllegalArgumentException("PP1_SELECTION_CANDIDATE_SNAPSHOT_CONFLICT");
        }
        for (String candidateId : selected.assembly().candidateIds()) try {
            candidatePins.put(candidateId, adapter.canonicalSha256(selected.formalCandidate(candidateId)));
        } catch (RuntimeException unavailable) { candidatePins.put(candidateId, "UNAVAILABLE"); }
        String next = "local-evidence-batch-sha256-" + adapter.canonicalSha256(binding);
        if (requestId != null && !requestId.equals(next)) throw new IllegalArgumentException("LOCAL_BATCH_REQUEST_IDENTITY_MISMATCH");
        if (saved != null) {
            var copy = saved.deepCopy(); copy.put("executionMode", "REQUEST_RESULT_REUSE");
            copy.put("newProviderInvocations", 0).put("newEvaluatorInvocations", 0); return copy;
        }
        requestId = next; currentSession=session; deferPp1=beforePp1!=null;
        deNovoDependencyOnly=deferPp1 && eligibleCurrentDependency(session);
        var out = binding.deepCopy().put("schemaVersion", "local-evidence-batch-v1").put("requestId", next)
                .put("executionMode", "EXECUTED").put("automaticTrigger", false).put("classificationEnabled", false);
        var modules = out.putObject("modules");
        if (pm3 != null && selected("PM3")){var module=modules.putObject("pm3").put("status","PREPARED");module.set("sourceSnapshot",pm3.originals());module.putArray("results");}
        if (pp1 != null && selected("PP1")){var module=modules.putObject("pp1").put("status","PREPARED");module.set("sourceSnapshot",pp1.originals());module.putArray("results");}
        if (deNovo != null && deNovoSelected()) {
            var module = modules.putObject("deNovo").put("status", "PREPARED")
                    .put("sourceSnapshotSha256", trustedSourceSha256);
            module.put("dependencyOnly",deNovoDependencyOnly);
            module.set("originalsByRawSha256", deNovo.originals()); module.putArray("results");
        }
        if (bp7 != null && selected("BP7")) modules.putObject("bp7").put("status", "PREPARED").putArray("results");
        ObjectNode predictionPreparation = mapper.createObjectNode();
        String globalIssue = trustedSourceSha256 == null || !trustedSourceSha256.matches("[0-9a-f]{64}")
                ? "LOCAL_BATCH_SOURCE_IDENTITY_REQUIRED" : !Objects.equals(analysis.getAnalysisId(), selected.assembly().analysisId())
                ? "LOCAL_BATCH_ANALYSIS_ID_MISMATCH" : analysis.getGenomeAssembly() != org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38
                ? "LOCAL_BATCH_ASSEMBLY_UNSUPPORTED" : null;
        SelectedPredictionRuntime prediction = null; SelectedPredictionRuntime.Batch predictionPlan = null;
        Pm2LocalExomesRuntime pm2 = null; SpliceAiLocalObservationSession splice = null;
        GeneralPm4Runtime pm4 = null; Pvs1SelectedBatch pvs1 = null; GeneralBp3Runtime bp3 = null;
        GeneralPm1Runtime pm1 = null; GeneralPp2Runtime pp2 = null; GeneralBp1Runtime bp1 = null;
        PublishedPm5PtcRuntime pm5Ptc = null;
        ClinVarConditionAwareComparatorInput.Session references = null;
        var pm1Plans = new LinkedHashMap<SelectedPredictionRuntime.AttemptKey, GeneralPm1Runtime.Prepared>();
        var pm1PeerCriteria = new java.util.LinkedHashSet<String>();
        boolean availableContext = false;
        var routing = new SelectedCriterionRouting(mapper);
        var routes = new LinkedHashMap<String, ObjectNode>();
        if (globalIssue == null) for (var candidate : selected.assembly().candidates()) for (var attempt : candidate.attempts()) {
            try { if (selected.context(candidate.candidateId(), attempt.hypothesisId()) != null) {
                availableContext = true;
                routes.put(candidate.candidateId() + "/" + attempt.hypothesisId(), routing.prepare(selected, candidate.candidateId(), attempt.hypothesisId()));
            } }
            catch (RuntimeException unavailable) { /* Preserve each original blocker below, before opening any resource factory. */ }
        }
        boolean referenceSelected = selected("PS1") || routes.values().stream().anyMatch(this::classicPm5);
        var ptcFactory = pm5PtcFactory;
        if (globalIssue == null && availableContext && (selected("PM5") || selected("PVS1"))
                && routes.values().stream().anyMatch(LocalEvidenceBatchRuntime::needsGenomicPtc)) {
            try {
                if (ptcFactory == null) throw new IllegalArgumentException("PM5_PTC_RUNTIME_UNAVAILABLE");
                pm5Ptc = Objects.requireNonNull(ptcFactory.get());
                var module = modules.putObject("pm5Ptc").put("dependencyOnly", !selected("PM5"));
                module.set("policy", pm5Ptc.identity());
                module.putArray("results");
            } catch (RuntimeException bad) { modules.set("pm5Ptc", unavailable(reason(bad, "PM5_PTC_RUNTIME_UNAVAILABLE"))); }
        }
        if (deNovo != null && deNovoSelected() && globalIssue == null && availableContext) {
            org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("MATERIAL_SUPPLY", null, null, "PS2 / PM6");
            deNovo.prepareSelectedSources(selected, deNovoEvaluationCriteria());
            var module=(ObjectNode) modules.path("deNovo"); module.set("originalsByRawSha256",deNovo.originals());
            module.set("sourceSupply",deNovo.sourceSupplySnapshot());
        }
        if (pm4Factory != null && selected("PM4")) {
            org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("LOCAL_RESOURCES", null, null, null);
            if (globalIssue == null && (needsRoute(routes, "PM4") || fullRnaSelectionRequiresPreparation())) try {
                pm4 = Objects.requireNonNull(pm4Factory.get());
                var module = modules.putObject("pm4").put("sourceSnapshotSha256", trustedSourceSha256);
                module.set("policy", pm4.identity()); module.putArray("results");
            } catch (RuntimeException bad) { modules.set("pm4", unavailable(reason(bad, "PM4_CONFIGURATION_UNAVAILABLE"))); }
            else modules.set("pm4", factoryNotNeeded(globalIssue, routes, "PM4"));
        }
        if (pvs1Factory != null && (selected("PVS1") || routes.values().stream().anyMatch(this::needsPs1Baseline))) {
            if (globalIssue == null && availableContext) try {
                pvs1 = Objects.requireNonNull(pvs1Factory.get());
                var module = modules.putObject("pvs1"); module.put("dependencyOnly", !selected("PVS1")); module.set("policy", pvs1.identity()); module.putArray("results");
            } catch (RuntimeException bad) { modules.set("pvs1", unavailable(reason(bad, "PVS1_CONFIGURATION_UNAVAILABLE"))); }
            else modules.set("pvs1", unavailable(globalIssue == null ? "SELECTED_CONTEXT_UNAVAILABLE" : globalIssue));
        }
        if (bp3Factory != null && selected("BP3")) {
            if (globalIssue == null && (needsRoute(routes, "BP3") || fullRnaSelectionRequiresPreparation())) try {
                bp3 = Objects.requireNonNull(bp3Factory.get());
                var module = modules.putObject("bp3").put("sourceSnapshotSha256", trustedSourceSha256);
                module.set("policy", bp3.identity()); module.putArray("results");
            } catch (RuntimeException bad) { modules.set("bp3", unavailable(reason(bad, "BP3_CONFIGURATION_UNAVAILABLE"))); }
            else modules.set("bp3", factoryNotNeeded(globalIssue, routes, "BP3"));
        }
        if (pm1Factory != null && selected("PM1")) {
            if (globalIssue == null && needsRoute(routes, "PM1")) try {
                pm1 = Objects.requireNonNull(pm1Factory.get());
                var module = modules.putObject("pm1").put("sourceSnapshotSha256", trustedSourceSha256);
                module.set("policy", pm1.identity()); module.putArray("results");
            } catch (RuntimeException bad) { modules.set("pm1", unavailable(reason(bad, "PM1_CONFIGURATION_UNAVAILABLE"))); }
            else modules.set("pm1", factoryNotNeeded(globalIssue, routes, "PM1"));
        }
        if (pm1 != null) for (var candidate : selected.assembly().candidates()) for (var attempt : candidate.attempts()) {
            var planRoute = routes.get(candidate.candidateId() + "/" + attempt.hypothesisId());
            if (planRoute == null || SelectedCriterionRouting.skip(planRoute, "PM1")) continue;
            var prepared = pm1.prepare(selected, candidate.candidateId(), attempt.hypothesisId());
            pm1Plans.put(new SelectedPredictionRuntime.AttemptKey(candidate.candidateId(), attempt.hypothesisId()), prepared);
            pm1PeerCriteria.addAll(prepared.requiredPeers());
        }
        if (pp2Factory != null && selected("PP2")) {
            if (globalIssue == null && needsRoute(routes, "PP2")) try {
                pp2 = Objects.requireNonNull(pp2Factory.get());
                var preparedSource = preparedPp2Source;
                if (preparedSource != null) pp2 = pp2.withOtherPhenotypeSource(preparedSource);
                if (pp2.identity().path("germlineDeclared").asBoolean() != identity.path("pp2GermlineDeclared").asBoolean())
                    throw new IllegalArgumentException("PP2_GERMLINE_DECLARATION_BINDING_MISMATCH");
                var module = modules.putObject("pp2"); module.set("policy", pp2.identity()); module.putArray("results");
            } catch (RuntimeException bad) { pp2 = null; modules.set("pp2", unavailable(reason(bad, "PP2_CONFIGURATION_UNAVAILABLE"))); }
            else modules.set("pp2", factoryNotNeeded(globalIssue, routes, "PP2"));
        }
        if (bp1Factory != null && selected("BP1")) {
            if (globalIssue == null && needsRoute(routes, "BP1")) try {
                bp1 = Objects.requireNonNull(bp1Factory.get());
                var module = modules.putObject("bp1"); module.set("policy", bp1.identity()); module.putArray("results");
                module.put("sourceSnapshotSha256", trustedSourceSha256).put("currentPublicBindingVersion", Bp1CurrentPublicBinding.VERSION);
            } catch (RuntimeException bad) { modules.set("bp1", unavailable(reason(bad, "BP1_CONFIGURATION_UNAVAILABLE"))); }
            else modules.set("bp1", factoryNotNeeded(globalIssue, routes, "BP1"));
        }
        if (globalIssue == null && availableContext) {
            try { if (spliceFactory != null && (predictionsSelected() || referenceSelected || selected("BP7")
                    || pm1PeerCriteria.contains("ACMG_AMP_2015:PP3") || pm1PeerCriteria.contains("ACMG_AMP_2015:PM5"))) splice = Objects.requireNonNull(spliceFactory.get()); }
            catch (RuntimeException failure) { modules.set("sharedSplice", unavailable(reason(failure, "SHARED_SPLICE_CONFIGURATION_UNAVAILABLE"))); }
            if (splice == null) {
                var settings = new VariantAnalysisProperties().getSpliceAi(); settings.setEnabled(false);
                splice = new SpliceAiLocalObservationSession(mapper, settings);
                if (!modules.has("sharedSplice")) modules.set("sharedSplice", unavailable("SHARED_SPLICE_CONFIGURATION_UNAVAILABLE"));
            }
            // The prediction route for every background is selected before acquiring any module's observations.
            if (predictionsSelected() || selected("BP7") || pm1PeerCriteria.contains("ACMG_AMP_2015:PP3")) try {
                if (predictionFactory == null) throw new IllegalArgumentException("PREDICTION_CONFIGURATION_UNAVAILABLE");
                prediction = Objects.requireNonNull(predictionFactory.apply(splice));
                if (predictionsSelected() || selected("BP7")) predictionPlan = prediction.prepare(selected, analysis);
                else {
                    var needed = new java.util.LinkedHashSet<SelectedPredictionRuntime.AttemptKey>();
                    pm1Plans.forEach((key, value) -> { if (value.requiredPeers().contains("ACMG_AMP_2015:PP3")) needed.add(key); });
                    predictionPlan = prediction.prepare(selected, analysis, needed);
                }
                predictionPreparation = predictionPlan.snapshot(); // Capture trusted plans before executing or reading result rows.
            } catch (RuntimeException failure) { modules.set("prediction", unavailable(reason(failure, "PREDICTION_PREPARATION_FAILED"))); }
            if (selected("PM2") || pm1PeerCriteria.contains("ACMG_AMP_2015:PM2")) try {
                if (pm2Factory == null) throw new IllegalArgumentException("PM2_CONFIGURATION_UNAVAILABLE");
                pm2 = Objects.requireNonNull(pm2Factory.get());
                var module = mapper.createObjectNode().put("sourceSnapshotSha256", trustedSourceSha256).put("dependencyOnly", !selected("PM2"));
                module.set("profile", pm2.profile()); module.putArray("results"); modules.set("pm2", module);
            } catch (RuntimeException failure) { modules.set("pm2", unavailable(reason(failure, "PM2_CONFIGURATION_UNAVAILABLE"))); }
            if (referenceSelected || pm1PeerCriteria.contains("ACMG_AMP_2015:PM5")) try {
                if (reference == null) throw new IllegalArgumentException("REFERENCE_CONFIGURATION_UNAVAILABLE");
                var sources = reference.sourceSnapshot(); var checks = new MissenseReferenceDraftPreparation();
                for (String role : List.of("CLINVAR_VCF", "SIDECAR", "VEP")) checks.sourceIdentity(sources.path(role));
                MissenseReferenceDraftPreparation.noPrivatePaths(sources);
                if (!Set.of("SYNTHETIC", "PUBLIC_REFERENCE").contains(sources.path("dataClass").asText()))
                    throw new IllegalArgumentException("REFERENCE_SOURCE_DATA_CLASS_UNSUPPORTED");
                if ("SYNTHETIC".equals(sources.path("dataClass").asText()) && !"TEST_ONLY".equals(sources.path("purpose").asText()))
                    throw new IllegalArgumentException("SYNTHETIC_TEST_ONLY_REQUIRED");
                references = Objects.requireNonNull(reference.comparatorFactory().get()).openSession(sources);
                var module = mapper.createObjectNode().put("sourceSnapshotSha256", trustedSourceSha256).put("dependencyOnly", !referenceSelected);
                module.set("sourceSnapshot", sources); module.putArray("results"); modules.set("reference", module);
            } catch (RuntimeException failure) { modules.set("reference", unavailable(reason(failure, "REFERENCE_CONFIGURATION_UNAVAILABLE"))); }
            if ((predictionsSelected() || selected("BP7") || pm1PeerCriteria.contains("ACMG_AMP_2015:PP3")) && prediction != null && predictionPlan != null) {
                org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("PREDICTION", null, null, "PP3 / BP4");
                try {
                    var predicted = executionCriteria == null ? prediction.execute(predictionPlan)
                            : prediction.execute(predictionPlan, predictionDependencyCriteria(pm1PeerCriteria.contains("ACMG_AMP_2015:PP3")));
                    predicted.put("sourceSnapshotSha256", trustedSourceSha256).put("dependencyOnly", !predictionsSelected() && !selected("BP7"));
                    for (var predictedRow : predicted.path("results")) if (predictedRow instanceof ObjectNode object) {
                        object.put("executionSourceSha256", trustedSourceSha256);
                        SelectedPredictionRuntime.sealResult(mapper, object);
                    }
                    modules.set("prediction", predicted);
                }
                catch (RuntimeException failure) {
                    var failed = unavailable(reason(failure, "PREDICTION_EXECUTION_FAILED")); failed.set("preparation", predictionPlan.snapshot()); modules.set("prediction", failed);
                }
            }
        } else for (String name : List.of("pm2", "prediction", "reference"))
            modules.set(name, unavailable(globalIssue == null ? "SELECTED_CONTEXT_UNAVAILABLE" : globalIssue));
        if (bp7 != null && selected("BP7")) ((ObjectNode) modules.path("bp7")).set("predictionPreparation", predictionPreparation.deepCopy());
        var rows = out.putArray("results");
        for (var candidate : selected.assembly().candidates()) {
            if (candidate.attempts().isEmpty()) addAttempt(rows, selected, pp1Selected, candidate.candidateId(), null, analysis,
                    trustedSourceSha256, globalIssue == null ? "SELECTED_CONTEXT_UNAVAILABLE" : globalIssue, null, modules, pm2, references, splice, predictionPreparation, pm4, pvs1, bp3, pm1, pp2, bp1, pm5Ptc, null);
            else for (var attempt : candidate.attempts()) addAttempt(rows, selected, pp1Selected, candidate.candidateId(), attempt.hypothesisId(), analysis,
                    trustedSourceSha256, globalIssue, routes.get(candidate.candidateId()+"/"+attempt.hypothesisId()), modules, pm2, references, splice, predictionPreparation, pm4, pvs1, bp3, pm1, pp2, bp1, pm5Ptc,
                    attempt.hypothesisId()==null ? null : pm1Plans.get(new SelectedPredictionRuntime.AttemptKey(candidate.candidateId(), attempt.hypothesisId())));
        }
        if(beforePp1!=null && session!=null) {
            if (pp1 == null) throw new IllegalStateException("CURRENT_CONSUMER_EXECUTION_PRE_PP1_PHASE_REQUIRED");
            CurrentConsumerExecution execution=currentConsumerExecution(session,out,modules);
            beforePp1.accept(execution);
            for(var row:rows) finishPp1((ObjectNode)row,pp1Selected,trustedSourceSha256,modules);
            ((ObjectNode)modules.path("pp1")).set("currentContributions",pp1.currentContributionOriginals());
            out.set("currentConsumerExecution",execution.snapshot());
        }
        if (deNovo != null && deNovoSelected()) ((ObjectNode) modules.path("deNovo")).put("evaluatorCalls", deNovo.evaluations())
                .put("providerCalls", deNovo.sourceSupplyCalls()).put("publicNetworkRequests",deNovo.publicNetworkRequests());
        if (pm3 != null && selected("PM3"))((ObjectNode)modules.path("pm3")).put("evaluatorCalls",pm3.evaluations()).put("providerCalls",0);
        if (pp1 != null && selected("PP1"))((ObjectNode)modules.path("pp1")).put("evaluatorCalls",pp1.evaluations()).put("providerCalls",0);
        if (pm2 != null) ((ObjectNode) modules.path("pm2")).put("providerCalls", pm2.providerCalls()).put("evaluatorCalls", pm2.evaluatorCalls()).put("allSitesCalls", pm2.allSitesCalls());
        if (pm4 != null) {
            var module = (ObjectNode) modules.path("pm4"); module.put("evaluatorCalls", pm4.evaluations());
            if (!pm4.supplyCounts().isEmpty()) module.set("regionSupplyCounts", pm4.supplyCounts());
        }
        if (pvs1 != null) {
            var module = (ObjectNode) modules.path("pvs1");
            module.put("providerCalls", pvs1.startLossAcquisitionCounts().path("httpRequests").asInt())
                    .put("evaluatorCalls", pvs1.evaluatorCalls()).put("storedVepProjections", pvs1.projectionCalls());
            var originals = pvs1.startLossOriginals();
            if (!originals.isEmpty()) module.set("startLossSourceDocuments", originals);
            if (!pvs1.startLossAcquisitionCounts().isEmpty()) module.set("startLossAcquisition", pvs1.startLossAcquisitionCounts());
        }
        if (bp3 != null) ((ObjectNode) modules.path("bp3")).put("evaluatorCalls", bp3.evaluations()).set("sharedRegionSupplyCounts", bp3.supplyCounts());
        if (pm1 != null) ((ObjectNode) modules.path("pm1")).put("evaluatorCalls", pm1.evaluations());
        if (pp2 != null) ((ObjectNode) modules.path("pp2")).put("evaluatorCalls", pp2.evaluations());
        if (bp1 != null) ((ObjectNode) modules.path("bp1")).put("evaluatorCalls", bp1.evaluations());
        if (references != null) ((ObjectNode) modules.path("reference")).set("providerCalls", references.counts());
        if (bp7 != null && selected("BP7")) ((ObjectNode) modules.path("bp7")).put("status", "RECORDED").put("providerCalls", 0).put("evaluatorCalls", bp7.evaluatorCalls());
        var counts = out.putObject("providerCounts"); counts.put("sharedSplice", splice == null ? 0 : splice.providerCalls());
        counts.set("pm2", modules.path("pm2").path("providerCalls")); counts.set("reference", modules.path("reference").path("providerCalls"));
        var developmentCalls = modules.path("prediction").path("developmentProviderCalls");
        if (developmentCalls.isIntegralNumber()) counts.set("predictionDevelopment", developmentCalls); else counts.putNull("predictionDevelopment");
        counts.put("semantics", "MODULE_NATIVE_COUNTS_SHARED_SPLICE_COUNTED_ONCE_NO_TRANSPORT_INFERENCE");
        if (!modules.has("sharedSplice")) modules.putObject("sharedSplice").put("status", "SESSION_CREATED");
        ((ObjectNode) modules.path("sharedSplice")).put("providerCalls", splice == null ? 0 : splice.providerCalls());
        boolean blocked = false;
        for (var row : rows) for (var criterion : row.at("/localBatch/criteria")) blocked |= "NOT_EVALUATED".equals(criterion.path("status").asText())
                && !"NOT_COUNTED".equals(criterion.at("/details/fullRnaUse/claim/application").asText());
        out.put("status", blocked ? "COMPLETED_WITH_BLOCKERS" : "COMPLETED");
        if (FullRnaEvidenceArchive.required(out)) {
            var archive = FullRnaEvidenceArchive.create(mapper, fullRnaPrepared.values(), out);
            out.set("fullRnaArchive", archive); out.set("fullRnaArchiveSha256", archive.path("archiveSha256").deepCopy());
        }
        appendPp2SourceArchive(out); saved = out.deepCopy(); return out;
    }

    private CurrentConsumerExecution currentConsumerExecution(CurrentAcmgEvaluationSession session,ObjectNode out,ObjectNode modules) {
        var pp1 = this.pp1;
        if(pp1==null || pp1.evaluations()!=0 || !session.identity().path("executionSourceSha256").equals(out.path("sourceSnapshotSha256")))
            throw new IllegalStateException("CURRENT_CONSUMER_EXECUTION_PRE_PP1_PHASE_REQUIRED");
        var value=mapper.createObjectNode().put("schemaVersion","current-native-consumer-execution-v1")
                .put("phase","ALL_ATTEMPTS_STAGE1_COMPLETE_BEFORE_PP1")
                .put("sourceSnapshotSha256",out.path("sourceSnapshotSha256").asText())
                .put("pp1EvaluatorCallsBeforeBind",pp1.evaluations());
        value.set("sessionIdentity",session.identity()); value.set("runtimeIdentity",identity());
        var dispatched=executionCriteria==null?enabledCriteria():executionCriteria;
        var claimed=new java.util.LinkedHashSet<String>();
        for(var criterion:session.identity().path("enabledCriteria")) claimed.add(criterion.asText());
        var publicClaim=new java.util.LinkedHashSet<>(claimed); publicClaim.remove("ACMG_AMP_2015:PP4");
        if(!publicClaim.equals(new java.util.LinkedHashSet<>(dispatched)))
            throw new IllegalArgumentException("CURRENT_CONSUMER_EXECUTION_CLAIMED_PROFILE_MISMATCH");
        var configured=new java.util.LinkedHashSet<>(enabledCriteria());
        // These two allele-only consumers are dispatched by the existing surrounding
        // unified service. They do not consume phenotype/family clinical facts.
        for(String criterion:List.of("ACMG_AMP_2015:BA1","ACMG_AMP_2015:BS1"))
            if(dispatched.contains(criterion)) configured.add(criterion);
        if(currentPp1Factory().isPresent()) configured.add("ACMG_AMP_2015:PP4");
        if(claimed.contains("ACMG_AMP_2015:PP4") && currentPp1Factory().isEmpty())
            throw new IllegalArgumentException("CURRENT_CONSUMER_EXECUTION_PP4_REGISTRATION_REQUIRED");
        // Public CURRENT18 selection remains unchanged. An internal caller can inspect
        // an explicitly claimed registered PP4 method; the normal service rejects that
        // selection before claim. PP1 dependency alone never selects/adopts PP4.
        value.set("configuredCriteria",mapper.valueToTree(configured));
        value.set("executionCriteria",mapper.valueToTree(claimed));
        var registry=value.putObject("registry");
        registry.putObject("pp1").put("phase","DEFERRED").set("identity",pp1.identity());
        var nativeFactory=currentPp1Factory();
        var pp4=registry.putObject("pp4").put("phase","DEFERRED_REGISTERED").put("configured",nativeFactory.isPresent());
        if(nativeFactory.isPresent()) pp4.set("identity",nativeFactory.orElseThrow().identity());
        var segregation=registry.putObject("pm3").put("configured",pm3!=null).put("selected",hasPm3())
                .put("actualEvaluatorCalls",pm3==null?0:pm3.evaluations());
        if(pm3!=null) segregation.set("identity",pm3.identity());
        var origin=registry.putObject("deNovo").put("configured",deNovo!=null).put("selected",deNovo!=null && (selected("PS2") || selected("PM6")))
                .put("dependencyOnly",deNovoDependencyOnly)
                .put("actualEvaluatorCalls",deNovo==null?0:deNovo.evaluations());
        if(deNovo!=null) origin.set("identity",deNovo.identity());
        // BS4 and PS4 have no dispatch in this concrete CURRENT local runtime. Their
        // separate development endpoints cannot write contributions into this claimed run.
        var outside=registry.putObject("outsideCurrentRun");
        for(String code:List.of("BS4","PS4")) outside.putObject(code).put("reasonCode","NO_CURRENT_LOCAL_DISPATCH")
                .put("scope","THIS_CLAIMED_CURRENT_LOCAL_RUNTIME_ONLY");
        var attempts=value.putArray("attempts");
        for(ObjectNode attempt:session.attempts()) {
            var matches=java.util.stream.StreamSupport.stream(out.path("results").spliterator(),false)
                    .filter(row->attempt.path("candidateId").equals(row.path("candidateId")) && attempt.path("hypothesisId").equals(row.path("hypothesisId"))).toList();
            if(matches.size()!=1) throw new IllegalArgumentException("CURRENT_CONSUMER_EXECUTION_ATTEMPT_COVERAGE_REQUIRED");
            var entry=attempts.addObject();
            for(String key:List.of("candidateId","hypothesisId","bindingStatus","bindingIdentitySha256"))
                if(attempt.has(key)) entry.set(key,attempt.path(key).deepCopy());
            if (pp1.hasWorkbenchRegistry() && nativeFactory.isEmpty()) continue;
            // Keep only actual clinically shared consumers in this authority audit.
            // All unrelated scientific originals remain in the normal full batch; their
            // display/ranking numbers are not new clinical-use facts or grant inputs.
            var originalRow=matches.get(0); var clinicalRow=entry.putObject("stage1Result");
            for(String field:List.of("candidateId","hypothesisId","context","applicabilityPlan"))
                if(originalRow.has(field)) clinicalRow.set(field,originalRow.path(field).deepCopy());
            var evaluations=clinicalRow.putArray("criterionEvaluations");
            for(var result:originalRow.path("criterionEvaluations"))
                if(Set.of("ACMG_AMP_2015:PS2","ACMG_AMP_2015:PM6","ACMG_AMP_2015:PM3").contains(result.path("criterionId").asText()))
                    evaluations.add(result.deepCopy());
            var actual=entry.putObject("moduleResults");
            var calls=currentClinicalCalls.get(new SelectedPredictionRuntime.AttemptKey(attempt.path("candidateId").asText(),attempt.path("hypothesisId").asText()));
            for(String module:List.of("pm3","deNovo")) {
                ObjectNode called=calls==null?null:calls.get(module);
                if(called!=null) actual.set(module,called.deepCopy()); else actual.putNull(module);
                actual.put(module+"MatchingResults",called==null?0:1);
            }
        }
        value.put("executionSha256",adapter.canonicalSha256(value));
        return new CurrentConsumerExecution(session,value);
    }
    private void finishPp1(ObjectNode row,SelectedEvidencePreparation selected,String source,ObjectNode modules) {
        Pp1SelectedBatch module=pp1;
        if(module==null) throw new IllegalStateException("PP1_CURRENT_MODULE_REQUIRED");
        String candidate=row.path("candidateId").asText(), hypothesis=row.path("hypothesisId").asText(null);
        ObjectNode raw;
        if(hypothesis==null) raw=unavailable("SELECTED_CONTEXT_UNAVAILABLE");
        else {
            var criteria=new ArrayList<String>();
            if(currentSession!=null) currentSession.identity().path("enabledCriteria").forEach(value->criteria.add(value.asText()));
            else criteria.addAll(executionCriteria==null?enabledCriteria():executionCriteria);
            raw=module.evaluate(selected,candidate,hypothesis,source,criteria);
        }
        var outputs=(ArrayNode)modules.at("/pp1/results"); String pointer="/modules/pp1/results/"+outputs.size(); outputs.add(raw);
        ObjectNode evaluation=normalize(Pp1SelectedBatch.CRITERION,raw,"PP1_RESULT_UNAVAILABLE");
        ObjectNode view=mapper.createObjectNode().put("criterionId",Pp1SelectedBatch.CRITERION)
                .put("status",evaluation.at("/evaluationResult/status").asText())
                .put("appliedStrength",evaluation.path("appliedStrength").asText())
                .put("reasonCode",evaluation.at("/evaluationResult/reasonCode").asText());
        view.put("reason",explanation(view.path("status").asText(),view.path("reasonCode").asText()));
        var details=view.putObject("details").put("modulePointer",pointer);
        details.set("originalResult",raw.deepCopy()); details.set("pp1Facts",raw.path("publicSummary").deepCopy());
        details.set("materialPreparation",MaterialPreparationSummary.build(mapper,view,mapper.createObjectNode(),modules.path("pp1"),"pp1",pointer));
        var explained=EvidenceReasonExplanation.build(mapper,view);
        if(explained!=null){details.set("explanation",explained);view.put("reason",explained.path("summary").asText());}
        replacePp1((ArrayNode)row.path("criterionEvaluations"),evaluation);
        replacePp1((ArrayNode)row.at("/localBatch/criteria"),view);
    }
    private static void replacePp1(ArrayNode rows,ObjectNode replacement) {
        int index=-1;
        for(int at=0;at<rows.size();at++) if(Pp1SelectedBatch.CRITERION.equals(rows.get(at).path("criterionId").asText())) {
            if(index>=0) throw new IllegalArgumentException("PP1_CURRENT_RESULT_AMBIGUOUS"); index=at;
        }
        if(index<0) throw new IllegalArgumentException("PP1_CURRENT_RESULT_REQUIRED");
        rows.set(index,replacement);
    }

    private ObjectNode factoryNotNeeded(String globalIssue, Map<String, ObjectNode> routes, String code) {
        if (globalIssue == null && !routes.isEmpty() && !needsRoute(routes, code))
            return mapper.createObjectNode().put("status", "NOT_REQUIRED").put("reasonCode", "ROUTING_ALL_SELECTED_CONTEXTS_SKIPPED")
                    .put("providerCalls", 0).put("evaluatorCalls", 0);
        return unavailable(globalIssue == null ? "SELECTED_CONTEXT_UNAVAILABLE" : globalIssue);
    }

    private static boolean needsRoute(Map<String, ObjectNode> routes, String code) {
        return routes.values().stream().anyMatch(plan -> !SelectedCriterionRouting.skip(plan, code));
    }

    private void addAttempt(ArrayNode rows, SelectedEvidencePreparation selected, SelectedEvidencePreparation pp1Selected, String candidateId, @Nullable String hypothesisId,
            AnalysisContext analysis, String sourceSha, @Nullable String blocked, @Nullable ObjectNode preparedRoute, ObjectNode modules,
            @Nullable Pm2LocalExomesRuntime pm2, @Nullable ClinVarConditionAwareComparatorInput.Session references,
            @Nullable SpliceAiLocalObservationSession splice, ObjectNode predictionPreparation, @Nullable GeneralPm4Runtime pm4, @Nullable Pvs1SelectedBatch pvs1, @Nullable GeneralBp3Runtime bp3, @Nullable GeneralPm1Runtime pm1, @Nullable GeneralPp2Runtime pp2, @Nullable GeneralBp1Runtime bp1,
            @Nullable PublishedPm5PtcRuntime pm5Ptc, @Nullable GeneralPm1Runtime.Prepared pm1Plan) {
        var pp1 = this.pp1;
        var pm3 = this.pm3;
        var deNovo = this.deNovo;
        var bp7 = this.bp7;
        var row = rows.addObject().put("candidateId", candidateId).put("hypothesisId", hypothesisId);
        ObjectNode deNovoResult = null;
        ObjectNode pm3Result = null;
        ObjectNode pp1Result = null;
        String contextId = null;
        boolean globalReady = blocked == null;
        if (blocked == null) try {
            var context = selected.context(candidateId, hypothesisId); contextId = context.path("contextId").asText(null); row.set("context", context);
        } catch (RuntimeException failure) { blocked = reason(failure, "SELECTED_CONTEXT_UNAVAILABLE"); }
        var route = blocked == null ? (preparedRoute == null ? new SelectedCriterionRouting(mapper).prepare(selected, candidateId, hypothesisId) : preparedRoute.deepCopy())
                : mapper.createObjectNode();
        boolean extendedDeletion = SelectedCriterionRouting.extendedDeletion(row.path("context"));
        row.set("applicabilityPlan", route);
        ObjectNode population = null, prediction = null, refs = null, bp7Result = null, proteinLength = null, truncating = null, benignLength = null;
        ObjectNode region = null, pathogenicMissense = null, benignMissense = null;
        PublishedPm5PtcRuntime.Result ptc = null;
        Map<String, String> pointers = new LinkedHashMap<>();
        ReviewedRuleBundle.FullRnaPrepared fullRna = blocked == null && hypothesisId != null && !extendedDeletion
                ? fullRnaPrepare(selected, candidateId, hypothesisId, sourceSha) : null;
        ObjectNode fullRnaLedger = null;
        if (globalReady && hypothesisId != null && pp1 != null && selected("PP1") && !deferPp1 && !extendedDeletion) {
                org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("VERIFY_EVALUATE", candidateId, hypothesisId, "PP1");
                pp1Result=pp1.evaluate(pp1Selected,candidateId,hypothesisId,sourceSha,executionCriteria==null?enabledCriteria():executionCriteria);
                var outputs=(ArrayNode)modules.at("/pp1/results");
                pointers.put("pp1","/modules/pp1/results/"+outputs.size());outputs.add(pp1Result);
        }
        if (blocked == null && !extendedDeletion) {
            if (pm3 != null && selected("PM3") && !SelectedCriterionRouting.skip(route, "PM3")) {
                org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("VERIFY_EVALUATE", candidateId, hypothesisId, "PM3");
                pm3Result=pm3.evaluate(selected,candidateId,hypothesisId);
                var outputs=(ArrayNode)modules.at("/pm3/results");
                pointers.put("pm3","/modules/pm3/results/"+outputs.size());outputs.add(pm3Result);
            }
            if (deNovo != null && deNovoSelected()) {
                org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("VERIFY_EVALUATE", candidateId, hypothesisId, "PS2 / PM6");
                deNovoResult = deNovo.evaluate(selected, candidateId, hypothesisId, deNovoEvaluationCriteria());
                if(deNovoDependencyOnly) deNovoResult.put("dependencyOnly",true).put("publicAdoptionRequested",false);
                var outputs = (ArrayNode) modules.at("/deNovo/results");
                pointers.put("deNovo", "/modules/deNovo/results/" + outputs.size()); outputs.add(deNovoResult);
            }
            if (pp2 != null && !SelectedCriterionRouting.skip(route, "PP2")) {
                org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("VERIFY_EVALUATE", candidateId, hypothesisId, "PP2");
                pathogenicMissense = pp2.evaluate(selected, candidateId, hypothesisId, pp2SourceObservation);
                var outputs = (ArrayNode) modules.at("/pp2/results");
                pointers.put("pp2", "/modules/pp2/results/" + outputs.size()); outputs.add(pathogenicMissense);
            }
            if (bp1 != null && !SelectedCriterionRouting.skip(route, "BP1")) {
                org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("VERIFY_EVALUATE", candidateId, hypothesisId, "BP1");
                benignMissense = bp1.evaluate(selected, candidateId, hypothesisId);
                var outputs = (ArrayNode) modules.at("/bp1/results");
                String pointer = "/modules/bp1/results/" + outputs.size();
                Bp1CurrentPublicBinding.capture(mapper, benignMissense, selected, candidateId, hypothesisId, sourceSha, pointer);
                pointers.put("bp1", pointer); outputs.add(benignMissense);
            }
            if (pm5Ptc != null && hypothesisId != null && needsGenomicPtc(route)
                    && (selected("PM5") || selected("PVS1"))) {
                org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("VERIFY_EVALUATE", candidateId, hypothesisId, "PM5");
                ptc = pm5Ptc.evaluate(selected, candidateId, hypothesisId, fullRna);
                var outputs = (ArrayNode) modules.at("/pm5Ptc/results");
                pointers.put("pm5Ptc", "/modules/pm5Ptc/results/" + outputs.size());
                var proof = outputs.addObject().put("candidateId", candidateId).put("hypothesisId", hypothesisId)
                        .put("executionSourceSha256", sourceSha);
                proof.set("pm5", ptc.pm5());
                if (ptc.pvs1() != null) proof.set("pvs1", ptc.pvs1());
                proof.set("facts", ptc.facts());
            }
            var publishedPvs1 = ptc == null ? null : ptc.pvs1();
            if (publishedPvs1 != null && selected("PVS1")) {
                truncating = mapper.createObjectNode();
                truncating.set("evaluation", publishedPvs1);
                pointers.put("pvs1", pointers.get("pm5Ptc"));
            } else if (pvs1 != null && (selected("PVS1") || needsPs1Baseline(route))) {
                org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("VERIFY_EVALUATE", candidateId, hypothesisId, "PVS1");
                truncating = pvs1.evaluate(selected, candidateId, hypothesisId, sourceSha, fullRna);
                var outputs = (ArrayNode) modules.at("/pvs1/results");
                pointers.put("pvs1", "/modules/pvs1/results/" + outputs.size()); outputs.add(truncating);
            }
            if (bp3 != null && (fullRna != null || !SelectedCriterionRouting.skip(route, "BP3"))) {
                org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("VERIFY_EVALUATE", candidateId, hypothesisId, "BP3");
                benignLength = fullRna == null ? bp3.evaluate(selected, candidateId, hypothesisId)
                        : bp3.evaluateFullRna(selected, candidateId, hypothesisId, fullRna.source());
                var outputs = (ArrayNode) modules.at("/bp3/results");
                pointers.put("bp3", "/modules/bp3/results/" + outputs.size()); outputs.add(benignLength);
            }
            if (pm4 != null && (fullRna != null || !SelectedCriterionRouting.skip(route, "PM4"))) {
                org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("VERIFY_EVALUATE", candidateId, hypothesisId, "PM4");
                proteinLength = fullRna == null ? pm4.evaluate(selected, candidateId, hypothesisId)
                        : pm4.evaluateFullRna(selected, candidateId, hypothesisId, fullRna.source());
                var outputs = (ArrayNode) modules.at("/pm4/results");
                pointers.put("pm4", "/modules/pm4/results/" + outputs.size()); outputs.add(proteinLength);
            }
            if (fullRna != null && hypothesisId != null) {
                var actual = new LinkedHashMap<String, ObjectNode>();
                if (truncating != null && selected("PVS1")) actual.put("PVS1", truncating);
                if (proteinLength != null) actual.put("PM4", proteinLength);
                if (benignLength != null) actual.put("BP3", benignLength);
                fullRnaLedger = FullRnaCurrentUse.apply(mapper, fullRna, sourceSha, candidateId, hypothesisId, actual, pointers);
                row.set("fullRnaApplication", fullRnaLedger);
            }
            if (pm2 != null && (selected("PM2") || pm1Plan != null && pm1Plan.requiredPeers().contains("ACMG_AMP_2015:PM2"))) {
                org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("VERIFY_EVALUATE", candidateId, hypothesisId, "PM2");
                try { population = pm2.evaluate(selected, candidateId, hypothesisId, analysis, sourceSha, null); }
                catch (RuntimeException failure) { population = unavailable(reason(failure, "PM2_EXECUTION_FAILED")); }
                var outputs = (ArrayNode) modules.at("/pm2/results");
                pointers.put("pm2", "/modules/pm2/results/" + outputs.size()); outputs.add(population);
            }
            prediction = predictionRow(modules.path("prediction"), candidateId, hypothesisId);
            if (bp7 != null && selected("BP7") && !SelectedCriterionRouting.skip(route, "BP7")) {
                org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("VERIFY_EVALUATE", candidateId, hypothesisId, "BP7");
                bp7Result = bp7.evaluate(selected, candidateId, hypothesisId, predictionPreparation, prediction);
                var outputs = (ArrayNode) modules.at("/bp7/results");
                pointers.put("bp7", "/modules/bp7/results/" + outputs.size()); outputs.add(bp7Result);
            }
            int index = 0;
            for (var value : modules.at("/prediction/results")) {
                if (value == prediction) pointers.put("prediction", "/modules/prediction/results/" + index); index++;
            }
            boolean classicPm5 = classicPm5(route) || !genomicPm5(route) && pm1Plan != null
                    && pm1Plan.requiredPeers().contains("ACMG_AMP_2015:PM5");
            if (references != null && splice != null && (selected("PS1") || classicPm5)) {
                org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("VERIFY_EVALUATE", candidateId, hypothesisId, "PS1 / PM5");
                var referenceCodes = new java.util.LinkedHashSet<String>();
                if (selected("PS1")) referenceCodes.add("PS1");
                if (classicPm5) referenceCodes.add("PM5");
                refs = reference(selected, candidateId, hypothesisId, references, splice, truncating, referenceCodes);
                refs.put("executionSourceSha256", sourceSha);
                var outputs = (ArrayNode) modules.at("/reference/results");
                pointers.put("reference", "/modules/reference/results/" + outputs.size()); outputs.add(refs);
            }
        }
        if (blocked == null && pm1 != null && !SelectedCriterionRouting.skip(route, "PM1")) {
            org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("VERIFY_EVALUATE", candidateId, hypothesisId, "PM1");
            var issues = mapper.createArrayNode();
            var peerFacts = pm1Plan == null ? null : currentPm1Peers(selected, candidateId, hypothesisId,
                    sourceSha, modules, pointers, pm1Plan, issues);
            region = pm1Plan == null ? unavailable("PM1_PREPARATION_NOT_AVAILABLE") : pm1.evaluate(pm1Plan, peerFacts, sourceSha);
            if (!issues.isEmpty()) region.set("peerPreparationIssues", issues);
            region.set("publicSummary", GeneralPm1Runtime.publicSummary(region));
            var outputs = (ArrayNode) modules.at("/pm1/results");
            pointers.put("pm1", "/modules/pm1/results/" + outputs.size()); outputs.add(region);
        }
        if(deferPp1 && hypothesisId!=null) {
            // Bind exact return values at their actual call site. The de novo module's
            // scientific JSON deliberately has no candidate/hypothesis fields; do not
            // mutate it under its original pins or infer association from array order.
            var called=new LinkedHashMap<String,ObjectNode>();
            if(pm3Result!=null) called.put("pm3",pm3Result.deepCopy());
            if(deNovoResult!=null) called.put("deNovo",deNovoResult.deepCopy());
            if(currentClinicalCalls.putIfAbsent(new SelectedPredictionRuntime.AttemptKey(candidateId,hypothesisId),Map.copyOf(called))!=null)
                throw new IllegalArgumentException("CURRENT_CONSUMER_EXECUTION_CALL_AMBIGUOUS");
        }
        var local = row.putObject("localBatch").put("schemaVersion", "local-batch1-attempt-v1")
                .put("candidateId", candidateId).put("hypothesisId", hypothesisId).put("contextId", contextId);
        var criteria = local.putArray("criteria"); var evaluations = row.putArray("criterionEvaluations");
        for (String id : selectedLocalCriteria()) {
            String code = id.substring(id.indexOf(':') + 1), module = "PP1".equals(code)?"pp1":"PM3".equals(code)?"pm3":Set.of("PS2", "PM6").contains(code) ? "deNovo" : Set.of("PM1", "PP2", "BP1").contains(code) ? code.toLowerCase(java.util.Locale.ROOT) : "PVS1".equals(code) ? "pvs1" : "BP3".equals(code) ? "bp3" : "PM4".equals(code) ? "pm4" : "BP7".equals(code) ? "bp7" : "PM2".equals(code) ? "pm2" : Set.of("PP3", "BP4").contains(code) ? "prediction" : "reference";
            if ("PM5".equals(code) && genomicPm5(route)) module = "pm5Ptc";
            JsonNode raw = "pm5Ptc".equals(module) ? ptc == null ? null : ptc.pm5()
                    : "deNovo".equals(module) ? deNovoResult == null ? null : deNovoResult.path("criteria").path(code)
                    : "pm3".equals(module) ? pm3Result
                    : "pp1".equals(module) ? pp1Result
                    : "PM1".equals(code) ? region == null ? null : region.path("evaluation")
                    : "PP2".equals(code) ? pathogenicMissense == null ? null : pathogenicMissense.path("evaluation")
                    : "BP1".equals(code) ? benignMissense == null ? null : benignMissense.path("evaluation")
                    : "PVS1".equals(code) ? truncating == null ? null : truncating.has("evaluation") ? truncating.path("evaluation") : truncating
                    : "BP3".equals(code) ? benignLength == null ? null : benignLength.path("evaluation") : "PM4".equals(code) ? proteinLength == null ? null : proteinLength.path("evaluation") : "BP7".equals(code) ? bp7Result : "PM2".equals(code) ? population == null ? null : population.has("evaluation") ? population.path("evaluation") : population
                    : "prediction".equals(module) ? prediction == null ? null : prediction.path("criteria").path(code)
                    : refs == null ? null : referenceCriterion(refs, id);
            String issue = blocked != null ? blocked : modules.path(module).path("reasonCode").asText(module.toUpperCase(java.util.Locale.ROOT) + "_RESULT_UNAVAILABLE");
            var dispatch = route.path("criteria").path(code);
            boolean fullRnaConsumer = fullRna != null && Set.of("PVS1", "PM4", "BP3").contains(code);
            if (!fullRnaConsumer && SelectedCriterionRouting.skip(route, code)) {
                raw = mapper.createObjectNode().put("status", "NOT_EVALUATED").put("reasonCode", dispatch.path("reasonCode").asText());
                ((ObjectNode) raw).putObject("criterionApplicability").put("status",
                        "NOT_APPLICABLE".equals(dispatch.path("status").asText()) ? "NOT_APPLICABLE" : "UNKNOWN");
            }
            var evaluation = normalize(id, raw, issue);
            if (fullRnaConsumer) FullRnaCurrentUse.applyEvaluation(evaluation, FullRnaCurrentUse.claim(fullRnaLedger, code));
            evaluations.add(evaluation);
            if (!fullRnaConsumer && SelectedCriterionRouting.skip(route, code))
                evaluation.putObject("criterionApplicability").put("status",
                        "NOT_APPLICABLE".equals(dispatch.path("status").asText()) ? "NOT_APPLICABLE" : "UNKNOWN");
            boolean publishedPtc = ptc != null && ("PM5".equals(code) || "PVS1".equals(code) && ptc.pvs1() != null);
            String status = publishedPtc && "NOT_APPLICABLE".equals(evaluation.at("/criterionApplicability/status").asText())
                    ? "NOT_APPLICABLE" : startLossPublicStatus(id, evaluation, truncating);
            var view = criteria.addObject().put("criterionId", id).put("status", status)
                    .put("appliedStrength", evaluation.path("appliedStrength").asText()).put("reasonCode", evaluation.at("/evaluationResult/reasonCode").asText());
            view.put("reason", explanation(view.path("status").asText(), view.path("reasonCode").asText()));
            var details = view.putObject("details").put("modulePointer", pointers.getOrDefault(module, "/modules/" + module));
            if (fullRnaConsumer) {
                FullRnaCurrentUse.applyView(view, FullRnaCurrentUse.claim(fullRnaLedger, code));
                ObjectNode actual = "PVS1".equals(code) ? truncating : "PM4".equals(code) ? proteinLength : benignLength;
                if (actual != null && actual.path("fullRnaCurrentUse").isObject()) details.set("fullRnaUse", FullRnaCurrentUse.publicProof(mapper, actual));
            }
            if (dispatch.isObject()) details.set("applicabilityRouting", dispatch.deepCopy());
            if (publishedPtc) details.set("pm5PtcFacts", Objects.requireNonNull(ptc).facts().deepCopy());
            details.set("originalResult", raw == null ? mapper.nullNode() : raw.deepCopy());
            if ("deNovo".equals(module) && deNovoResult != null) details.set("deNovoFacts", deNovoResult.path("publicSummary").deepCopy());
            if ("pm3".equals(module) && pm3Result != null) details.set("pm3Facts",pm3Result.path("publicSummary").deepCopy());
            if ("pp1".equals(module) && pp1Result != null) details.set("pp1Facts",pp1Result.path("publicSummary").deepCopy());
            if ("PM1".equals(code) && region != null) details.set("pm1Facts", region.path("publicSummary").deepCopy());
            if ("PP2".equals(code) && pathogenicMissense != null) details.set("pp2Facts", pathogenicMissense.path("publicSummary").deepCopy());
            if ("BP1".equals(code) && benignMissense != null) details.set("bp1Facts", benignMissense.path("publicSummary").deepCopy());
            if ("PVS1".equals(code) && truncating != null) details.set("pvs1Facts", truncating.path("fullRnaCurrentUse").isObject()
                    ? truncating.path("publicSummary").deepCopy() : Pvs1SelectedBatch.publicSummary(mapper, truncating));
            if ("BP3".equals(code) && benignLength != null) details.set("bp3Facts", benignLength.path("publicSummary").deepCopy());
            if ("BP7".equals(code) && bp7Result != null) details.set("bp7", Bp7SelectedBatch.publicSummary(mapper, bp7Result));
            if ("PM4".equals(code) && proteinLength != null) details.set("proteinLengthFacts", proteinLength.path("publicSummary").deepCopy());
            if ("prediction".equals(module) && prediction != null && prediction.path("mechanismSummary").isObject())
                details.set("predictionMechanisms", PredictionMechanismSummary.publicView(mapper, prediction.path("mechanismSummary")));
            if ("PM2".equals(code) && population != null
                    && Pm2LocalExomesProfile.validProjectScope(mapper, population.at("/dataScope/projectScope"))) {
                details.set("populationScope", population.at("/dataScope/projectScope").deepCopy());
                String populationStatus = view.path("status").asText();
                if (Set.of("MET", "NOT_MET").contains(populationStatus))
                    view.put("reasonCode", "PM2_PROJECT_LOCAL_" + (population.at("/observation/carrier/queryOutcome/status").asText().equals("NO_RECORD") ? "ABSENCE_" : "HIT_") + populationStatus);
                view.put("reason", explanation(populationStatus, view.path("reasonCode").asText())
                        + " 结论仅限 gnomAD 4.1 EXOMES，本轮没有建立全库或临床采用结论。");
            }
            if ("PM2".equals(code) && population != null && population.path("observation").isObject()) {
                var facts = details.putObject("populationFacts").put("schemaVersion", "pm2-local-facts-v1")
                        .put("dataset", "EXOMES").put("release", "4.1")
                        .put("queryStatus", population.at("/observation/carrier/queryOutcome/status").asText())
                        .put("policyRoute", population.at("/policySelection/policy/route").asText("SPECIALIST_OR_MISSING"));
                facts.set("measurements", population.at("/observation/carrier/measurements").deepCopy());
                var ruleDisplay=Pm2RuleSummary.build(mapper,population);
                if(ruleDisplay.path("conditions").isArray())details.set("populationRule",ruleDisplay);
                var detection = population.path("allSitesObservation");
                if (detection.isObject()) {
                    var publicDetection = facts.putObject("detection");
                    for (String field : List.of("status", "reasonCode", "adjAn", "regionFlags", "stratum", "ancestryStratified", "manifestSha256", "dataSha256"))
                        if (detection.has(field)) publicDetection.set(field, detection.path(field).deepCopy());
                }
            }
            if (refs != null && "reference".equals(module)) {
                details.set("materialPreparationGate", refs.path("materialPreparationGate"));
                details.set("referenceFacts", MissenseReferenceFactSummary.build(mapper, refs, id));
                if ("PS1".equals(code) && "PS1_SPLICE_COMPARISON".equals(refs.at("/evaluations/PS1/mode").asText()))
                    details.set("spliceComparisonFacts", Ps1SpliceRuntime.summary(mapper, refs.at("/evaluations/PS1")));
            }
            details.set("materialPreparation", MaterialPreparationSummary.build(mapper, view, dispatch,
                    modules.path(module), module, details.path("modulePointer").asText()));
            var reasonExplanation = EvidenceReasonExplanation.build(mapper, view);
            if (reasonExplanation != null) {
                details.set("explanation", reasonExplanation);
                view.put("reason", reasonExplanation.path("summary").asText());
            }
        }
    }

    private PublishedPm1CrossCriterionConditions.Facts currentPm1Peers(SelectedEvidencePreparation selected,
            String candidateId, String hypothesisId, String sourceSha, ObjectNode modules, Map<String, String> pointers,
            GeneralPm1Runtime.Prepared prepared, ArrayNode issues) {
        PublishedPm1CrossCriterionConditions.Facts combined = null;
        for (String criterion : prepared.requiredPeers()) {
            String code = criterion.substring(criterion.indexOf(':') + 1);
            String name = "PM2".equals(code) ? "pm2" : "PP3".equals(code) ? "prediction" : "reference";
            try {
                var projection = prepared.projection();
                if (projection == null || !selected.context(candidateId, hypothesisId).equals(projection.path("originalContext")))
                    throw new IllegalArgumentException("PM1_PEER_CURRENT_CONTEXT_MISMATCH");
                String pointer = pointers.get(name);
                var parent = modules.path(name);
                if (pointer == null || !sourceSha.equals(parent.path("sourceSnapshotSha256").asText())
                        || !pointer.matches("/modules/" + name + "/results/(?:0|[1-9][0-9]{0,5})"))
                    throw new IllegalArgumentException(parent.path("reasonCode").asText("PM1_PEER_CURRENT_MODULE_UNAVAILABLE"));
                var row = modules.at(pointer.substring("/modules".length()));
                if (!(row instanceof ObjectNode actual) || matchingPeerRows(parent.path("results"), candidateId, hypothesisId) != 1
                        || !peerIdentity(actual, candidateId, hypothesisId))
                    throw new IllegalArgumentException("PM1_PEER_CURRENT_MODULE_AMBIGUOUS");
                String evaluationPointer = "PM2".equals(code) ? "/evaluation" : "PP3".equals(code)
                        ? "/criteria/PP3" : pm5EvaluationPointer(actual);
                String contextPointer = "PM2".equals(code) ? "/context" : "/sourceProjection/evaluationContext";
                var facts = PublishedPm1CrossCriterionConditions.fromBoundCurrentModule(mapper, projection, actual,
                        evaluationPointer, contextPointer, sourceSha, pointer);
                combined = combined == null ? facts : PublishedPm1CrossCriterionConditions.combine(combined, facts);
            } catch (RuntimeException unavailable) {
                issues.addObject().put("criterionId", criterion).put("status", "UNAVAILABLE")
                        .put("reasonCode", reason(unavailable, "PM1_PEER_CURRENT_MODULE_UNAVAILABLE"));
            }
        }
        return combined;
    }
    private static boolean peerIdentity(JsonNode row, String candidate, String hypothesis) {
        JsonNode identity = row.path("candidateId").isTextual() ? row : row.path("plan");
        return candidate.equals(identity.path("candidateId").asText()) && hypothesis.equals(identity.path("hypothesisId").asText());
    }
    private static int matchingPeerRows(JsonNode rows, String candidate, String hypothesis) {
        int count = 0; for (var row : rows) if (peerIdentity(row, candidate, hypothesis)) count++; return count;
    }
    private static String pm5EvaluationPointer(JsonNode reference) {
        int match = -1, index = 0;
        for (var evaluation : reference.at("/evaluations/PM5/criterionEvaluations")) {
            if ("ACMG_AMP_2015:PM5".equals(evaluation.path("criterionId").asText())) {
                if (match >= 0) throw new IllegalArgumentException("PM1_PEER_PM5_FINAL_RESULT_AMBIGUOUS"); match = index;
            }
            index++;
        }
        if (match < 0) throw new IllegalArgumentException("PM1_PEER_PM5_FINAL_RESULT_UNAVAILABLE");
        return "/evaluations/PM5/criterionEvaluations/" + match;
    }

    private ObjectNode reference(SelectedEvidencePreparation selected, String candidateId, String hypothesisId,
            ClinVarConditionAwareComparatorInput.Session session, SpliceAiLocalObservationSession splice, @Nullable JsonNode pvs1Result,
            Set<String> referenceCodes) {
        var result = mapper.createObjectNode().put("candidateId", candidateId).put("hypothesisId", hypothesisId);
        try {
            var setup = Objects.requireNonNull(reference); var projection = selected.legacyProjection(candidateId, hypothesisId);
            var context = projection.context(); var candidate = projection.queryCandidate(); result.set("sourceProjection", projection.snapshot());
            if (!"SYNTHETIC".equals(context.path("dataClass").asText()) && "SYNTHETIC".equals(setup.sourceSnapshot().path("dataClass").asText()))
                throw new IllegalArgumentException("REFERENCE_SYNTHETIC_SOURCE_RUNTIME_FORBIDDEN");
            var tx = new RuntimeEvidenceV2ProviderWiring(mapper, adapter).selectedVepConsequence(context, candidate);
            if (tx == null) throw new IllegalArgumentException("MISSENSE_SELECTED_TRANSCRIPT_NOT_UNIQUE");
            // Additional annotation terms do not erase a missense consequence. In particular,
            // selecting PS1 must not divert PM5 away from its own protein comparison route.
            var terms = SelectedCriterionRouting.consequenceTerms(tx.getConsequence());
            if (terms.contains("missense_variant") && terms.contains("synonymous_variant"))
                throw new IllegalArgumentException("ROUTING_CONSEQUENCE_NOT_RECOGNIZED");
            var spliceComparisonFactory = setup.spliceComparisonFactory();
            if (selected("PS1") && spliceComparisonFactory != null && !terms.contains("missense_variant")) {
                result.put("mode", "PS1_SPLICE_COMPARISON");
                var evaluations = result.putObject("evaluations");
                try { evaluations.set("PS1", spliceComparisonFactory.get().evaluate(context, candidate, pvs1Result)); }
                catch (RuntimeException invalid) { evaluations.set("PS1", unavailable(reason(invalid, "PS1_SPLICE_CONFIGURATION_UNAVAILABLE"))); }
                evaluations.set("PM5", unavailable("PM5_SPLICE_COMPARISON_NOT_APPLICABLE"));
                result.putObject("materialPreparationGate").put("status", "NOT_REQUIRED").put("reasonCode", "PS1_SPLICE_SOURCE_QUALIFICATION_ROUTE");
                return result;
            }
            var captured = session.buildWithSnapshots(context, candidate, tx, setup.sourceSnapshot());
            result.put("mode", "CASE_BOUND_FACT_CAPTURE").put("dataClass", context.path("dataClass").asText());
            result.set("capture", mapper.valueToTree(captured)); result.set("sourceSnapshot", setup.sourceSnapshot());
            result.set("sharedSpliceObservations", referenceSplice(candidate.getObservation().getKey(), context.path("dataClass").asText(), captured, splice));
            // A selected reusable material route already has its own qualification gate.
            // Draft export is a separate preparation workflow, not a prerequisite for applying that material.
            if (setup.materialUseGate() != null)
                result.putObject("materialPreparationGate").put("status", "NOT_REQUIRED").put("reasonCode", "REFERENCE_EXISTING_MATERIAL_ROUTE");
            else if (!"SYNTHETIC".equals(context.path("dataClass").asText()))
                result.set("materialPreparationGate", unavailable("REFERENCE_DRAFT_DATA_CLASS_UNSUPPORTED"));
            else if (setup.syntheticOutputRoot() == null) result.set("materialPreparationGate", unavailable("REFERENCE_DRAFT_OUTPUT_NOT_CONFIGURED"));
            else try {
                Files.createDirectories(setup.syntheticOutputRoot());
                Path output = Files.createTempDirectory(setup.syntheticOutputRoot(), "reference-draft-");
                var exporter = new MissenseReferenceSnapshotExport();
                var exported = exporter.write(exporter.capture(captured, setup.sourceSnapshot()), output.resolve("snapshot"));
                result.set("snapshotExport", exported.report());
                if (exported.exported()) {
                    var draft = new MissenseReferenceDraftPreparation().prepare(exported.manifestPath(), exported.manifestSha256(), output.resolve("draft"));
                    result.set("draft", draft.report());
                    result.set("materialPreparationGate", unavailable("REFERENCE_SCIENTIFIC_REVIEW_REQUIRED"));
                } else result.set("materialPreparationGate", exported.report());
            } catch (Exception failure) { result.set("materialPreparationGate", unavailable(reason(failure, "REFERENCE_DRAFT_PREPARATION_FAILED"))); }
            var evaluations = result.putObject("evaluations");
            for (String code : List.of("PS1", "PM5")) {
            if (!referenceCodes.contains(code)) continue;
                try {
                    String criterion = "ACMG_AMP_2015:" + code;
                    if (!"SYNTHETIC".equals(context.path("dataClass").asText())) {
                        var materialUseGate = setup.materialUseGate();
                        if (materialUseGate == null) throw new IllegalArgumentException("REFERENCE_PATIENT_MATERIAL_QUALIFICATION_UNAVAILABLE");
                        String useIssue = materialUseGate.apply(criterion, context.deepCopy());
                        if (useIssue != null) throw new IllegalArgumentException(useIssue);
                    }
                    var approvedBundleFactory = setup.approvedBundleFactory();
                    if (approvedBundleFactory == null) throw new IllegalArgumentException("REFERENCE_APPROVED_MATERIAL_UNAVAILABLE");
                    var bundle = approvedBundleFactory.apply(criterion, captured);
                    if (bundle == null || ("PS1".equals(code) ? !bundle.supportsPs1() : !bundle.supportsPm5()))
                        throw new IllegalArgumentException("REFERENCE_APPROVED_MATERIAL_UNAVAILABLE");
                    if (!"SYNTHETIC".equals(context.path("dataClass").asText())) {
                        String useIssue = bundle.missenseUseIssue(context);
                        if (useIssue != null) throw new IllegalArgumentException(useIssue);
                    }
                    var value = bundle.evaluate(criterion, context, candidate); value.set("trustedBundleIdentity", bundle.identity()); evaluations.set(code, value);
                } catch (RuntimeException failure) { evaluations.set(code, unavailable(reason(failure, "REFERENCE_EVALUATION_FAILED"))); }
            }
        } catch (RuntimeException failure) { result.setAll(unavailable(reason(failure, "REFERENCE_CAPTURE_FAILED"))); }
        return result;
    }

    private ArrayNode referenceSplice(NormalizedVariantKey target, String dataClass,
            ClinVarConditionAwareComparatorInput.SnapshotCapture captured, SpliceAiLocalObservationSession splice) {
        var out = mapper.createArrayNode(); Map<NormalizedVariantKey, List<String>> keys = new LinkedHashMap<>();
        keys.put(target, new ArrayList<>(List.of("TARGET")));
        captured.getReferenceSnapshots().forEach(ref -> keys.computeIfAbsent(ref.getExactAllele(), ignored -> new ArrayList<>()).add("REFERENCE:" + ref.getVariationId()));
        keys.forEach((key, roles) -> {
            var row = out.addObject().put("applicationStatus", "NOT_EVALUATED").put("normalRnaClaimed", false);
            row.set("roles", mapper.valueToTree(roles)); row.set("queryAllele", mapper.valueToTree(key));
            try {
                var observation = splice.observe(key, dataClass); row.set("observation", observation.observation().snapshot());
                row.set("sourceReceipt", observation.source().receipt(mapper));
            } catch (RuntimeException failure) { row.setAll(unavailable(reason(failure, "SHARED_SPLICE_OBSERVATION_UNAVAILABLE"))); }
        }); return out;
    }

    @Nullable private ObjectNode predictionRow(JsonNode module, String candidate, @Nullable String hypothesis) {
        ObjectNode found = null;
        for (var row : module.path("results")) if (candidate.equals(row.at("/plan/candidateId").asText())
                && Objects.equals(hypothesis, row.at("/plan/hypothesisId").asText(null))) {
            if (found != null) return unavailable("PREDICTION_RESULT_AMBIGUOUS"); found = (ObjectNode) row;
        }
        return found;
    }

    private JsonNode referenceCriterion(ObjectNode result, String id) {
        var original = result.path("evaluations").path(id.substring(id.indexOf(':') + 1));
        if (original.isMissingNode()) return result;
        JsonNode found = null;
        for (var criterion : original.path("criterionEvaluations")) if (id.equals(criterion.path("criterionId").asText())) {
            if (found != null) return unavailable("REFERENCE_RESULT_AMBIGUOUS"); found = criterion;
        }
        return found == null ? original : found;
    }

    private ObjectNode normalize(String id, @Nullable JsonNode raw, String missingReason) {
        var out = raw instanceof ObjectNode object ? object.deepCopy() : mapper.createObjectNode();
        if (out.hasNonNull("criterionId") && !id.equals(out.path("criterionId").asText()))
            out = unavailable("LOCAL_BATCH_CRITERION_ID_MISMATCH");
        out.put("criterionId", id);
        JsonNode evaluation = out.path("evaluationResult").isObject() ? out.path("evaluationResult") : out;
        String status = evaluation.path("status").asText();
        if (Bp7SelectedBatch.CRITERION.equals(id)) out.putObject("criterionApplicability").put("status",
                "NOT_APPLICABLE".equals(status) ? "NOT_APPLICABLE" : Set.of("MET", "NOT_MET").contains(status) ? "APPLICABLE" : "UNKNOWN");
        if (!Set.of("MET", "NOT_MET", "NOT_EVALUATED").contains(status)) status = "NOT_EVALUATED";
        // Some original evaluators return a valid status without a reason. Missing
        // prose must not turn that successful evaluation into an unavailable result.
        String fallbackReason = Set.of("MET", "NOT_MET").contains(status)
                ? "LOCAL_BATCH_CRITERION_" + status : missingReason;
        String reason = evaluation.path("reasonCode").asText(fallbackReason);
        if (!out.path("evaluationResult").isObject()) out.putObject("evaluationResult").put("status", status).put("reasonCode", reason);
        else { ((ObjectNode) out.path("evaluationResult")).put("status", status); if (!evaluation.hasNonNull("reasonCode")) ((ObjectNode) out.path("evaluationResult")).put("reasonCode", reason); }
        if ("NOT_EVALUATED".equals(status)) out.put("appliedStrength", "NONE");
        else if (!out.hasNonNull("appliedStrength")) {
            if ("MET".equals(status)) { out.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", "LOCAL_BATCH_RESULT_STRENGTH_MISSING"); }
            out.put("appliedStrength", "NONE");
        }
        return out;
    }

    /** The evidence evaluator keeps NOT_EVALUATED as its application result when
     * the criterion is inapplicable. Preserve that original result while exposing
     * an already source-evaluated A1 exclusion as NOT_APPLICABLE in the list. */
    static String startLossPublicStatus(String criterion, JsonNode evaluation, @Nullable JsonNode source) {
        String status = evaluation.at("/evaluationResult/status").asText();
        if (!Pvs1SelectedBatch.CRITERION.equals(criterion) || source == null || !"NOT_EVALUATED".equals(status)
                || !"NOT_APPLICABLE".equals(evaluation.at("/criterionApplicability/status").asText())
                || !"NONE".equals(evaluation.path("appliedStrength").asText())
                || !"NONE".equals(source.at("/evaluation/appliedStrength").asText())
                || !"COMPLETED".equals(source.at("/evaluation/deterministicTrace/executionStatus").asText())) return status;
        String evaluator = source.at("/evaluation/deterministicTrace/evaluatorId").asText();
        if (!"pvs1-start-loss".equals(evaluator) && !evaluator.startsWith("pvs1-start-loss-")) return status;
        for (String section : List.of("automaticStartLossFacts", "expertStartLossFacts", "startLossFacts")) {
            var facts = source.path(section);
            if ("NOT_APPLICABLE".equals(facts.path("decisionOutcome").asText())
                    && "NONE".equals(facts.path("decisionStrength").asText())) return "NOT_APPLICABLE";
        }
        return status;
    }

    private static @Nullable JsonNode publishedPtcProof(JsonNode batch, JsonNode row, JsonNode criterion) {
        String kind = switch (criterion.path("criterionId").asText()) {
            case "ACMG_AMP_2015:PM5" -> "pm5";
            case "ACMG_AMP_2015:PVS1" -> "pvs1";
            default -> "";
        };
        String pointer = criterion.at("/details/modulePointer").asText();
        if (kind.isEmpty() || !pointer.matches("/modules/pm5Ptc/results/[0-9]+")) return null;
        var proof = batch.at(pointer);
        var facts = proof.path("facts");
        var decision = proof.path(kind);
        if (!proof.isObject() || !decision.isObject() || !facts.isObject()
                || !"published-pm5-ptc-facts-v1".equals(facts.path("schemaVersion").asText())
                || !proof.path("candidateId").equals(row.path("candidateId"))
                || !proof.path("hypothesisId").equals(row.path("hypothesisId"))
                || !facts.path("contextId").equals(row.at("/localBatch/contextId"))
                || !proof.path("executionSourceSha256").equals(batch.path("sourceSnapshotSha256"))
                || !decision.path("criterionId").equals(criterion.path("criterionId"))
                || !decision.path("appliedStrength").equals(criterion.path("appliedStrength"))
                || !decision.at("/evaluationResult/reasonCode").equals(criterion.path("reasonCode"))) return null;
        return proof;
    }

    public static boolean completedPublishedPtcExclusion(JsonNode batch, JsonNode candidate, JsonNode attempt, JsonNode criterion) {
        if (!"NOT_APPLICABLE".equals(criterion.path("status").asText())
                || !"NONE".equals(criterion.path("appliedStrength").asText())
                || !Set.of("PM5_PTC_PVS1_EXCLUDED_BY_RULE", "PVS1_PUBLISHED_PTC_RULE_EXCLUDED")
                        .contains(criterion.path("reasonCode").asText())) return false;
        for (var row : batch.path("results")) {
            if (!row.path("candidateId").equals(candidate.path("candidateId"))
                    || !row.path("hypothesisId").equals(attempt.path("hypothesisId"))) continue;
            var proof = publishedPtcProof(batch, row, criterion);
            return proof != null && "NOT_APPLICABLE".equals(proof.at("/facts/pvs1Status").asText())
                    && proof.path("facts").equals(criterion.at("/details/pm5PtcFacts"));
        }
        return false;
    }

    /** A verified pure missense finishes the truncating-only branch without awarding BP1. */
    public static boolean completedBp1TruncatingExclusion(ObjectMapper mapper, JsonNode batch, JsonNode candidate,
            JsonNode attempt, JsonNode criterion) {
        var facts = criterion.at("/details/bp1Facts");
        var check = facts.path("truncatingConditionCheck");
        String reason = "BP1_TRUNCATING_RULE_NOT_APPLICABLE_TO_PURE_MISSENSE";
        if (!GeneralBp1Runtime.CRITERION.equals(criterion.path("criterionId").asText())
                || !"NOT_EVALUATED".equals(criterion.path("status").asText())
                || !"NONE".equals(criterion.path("appliedStrength").asText())
                || !reason.equals(criterion.path("reasonCode").asText())
                || !facts.path("currentBinding").isObject()
                || !"bp1-truncating-applicability-v1".equals(check.path("schemaVersion").asText())
                || !"NOT_APPLICABLE".equals(check.path("status").asText())
                || !reason.equals(check.path("reasonCode").asText())
                || !"VERIFIED_PURE_CODING_MISSENSE".equals(check.at("/available/variantEffect").asText())) return false;
        for (var row : batch.path("results")) {
            if (row.path("candidateId").equals(candidate.path("candidateId"))
                    && row.path("hypothesisId").equals(attempt.path("hypothesisId"))
                    && row.path("context").equals(attempt.path("context"))) {
                // The public criterion omits the original evaluation that the receipt verifies.
                for (var original : row.at("/localBatch/criteria")) {
                    if (criterion.path("criterionId").equals(original.path("criterionId"))
                            && criterion.at("/details/modulePointer").equals(original.at("/details/modulePointer"))) {
                        return facts.equals(Bp1CurrentPublicBinding.project(mapper, batch, row, original));
                    }
                }
                return false;
            }
        }
        return false;
    }

    /** Recognize the same completed A1 exclusion after its deliberate public projection.
     * No other branch or merely labelled NOT_APPLICABLE result is made complete. */
    public static boolean completedStartLossExclusion(JsonNode criterion) {
        if (!Pvs1SelectedBatch.CRITERION.equals(criterion.path("criterionId").asText())
                || !"NOT_APPLICABLE".equals(criterion.path("status").asText())
                || !"NONE".equals(criterion.path("appliedStrength").asText())) return false;
        var evaluation = criterion.at("/details/originalResult");
        if (!"NOT_EVALUATED".equals(evaluation.at("/evaluationResult/status").asText())
                || !"NOT_APPLICABLE".equals(evaluation.at("/criterionApplicability/status").asText())
                || !"NONE".equals(evaluation.path("appliedStrength").asText())
                || !"COMPLETED".equals(evaluation.at("/deterministicTrace/executionStatus").asText())) return false;
        String evaluator = evaluation.at("/deterministicTrace/evaluatorId").asText();
        if (!"pvs1-start-loss".equals(evaluator) && !evaluator.startsWith("pvs1-start-loss-")) return false;
        var summary = criterion.at("/details/pvs1Facts");
        if (!"pvs1-selected-facts-v1".equals(summary.path("schemaVersion").asText())) return false;
        for (String section : List.of("automaticAssessment", "expertAssessment", "startLossAssessment")) {
            var facts = summary.path(section);
            if ("NOT_APPLICABLE".equals(facts.path("decisionOutcome").asText())
                    && "NONE".equals(facts.path("decisionStrength").asText())) return true;
        }
        return false;
    }

    /** A source-qualified full RNA exclusion is complete only when its saved raw, ledger and public projection agree. */
    public static boolean completedFullRnaExclusion(ObjectMapper mapper, JsonNode fullBatch, JsonNode candidate,
            JsonNode attempt, JsonNode criterion) {
        if (!Pvs1SelectedBatch.CRITERION.equals(criterion.path("criterionId").asText())
                || !"NOT_APPLICABLE".equals(criterion.path("status").asText()) || !"NONE".equals(criterion.path("appliedStrength").asText())) return false;
        try {
            JsonNode original = null; int matches = 0;
            for (var row : fullBatch.path("results")) if (row.path("candidateId").equals(candidate.path("candidateId"))
                    && row.path("hypothesisId").equals(attempt.path("hypothesisId"))) { original = row; matches++; }
            if (original == null || matches != 1) return false;
            JsonNode originalCriterion = null; int criteria = 0;
            for (var row : original.at("/localBatch/criteria")) if (Pvs1SelectedBatch.CRITERION.equals(row.path("criterionId").asText())) { originalCriterion = row; criteria++; }
            if (originalCriterion == null || criteria != 1) return false;
            var facts = FullRnaCurrentUse.project(mapper, fullBatch, original, originalCriterion); var proof = facts.path("fullRnaUse");
            var actualOriginal = originalCriterion.at("/details/originalResult"); var publicDetails = criterion.path("details");
            boolean hasDigest = publicDetails.has("originalResultSha256"), hasOriginal = publicDetails.has("originalResult");
            boolean sameOriginal = actualOriginal.isObject() && (hasDigest || hasOriginal)
                    && (!hasDigest || publicDetails.path("originalResultSha256").isTextual()
                        && new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(actualOriginal).equals(publicDetails.path("originalResultSha256").asText()))
                    && (!hasOriginal || actualOriginal.equals(publicDetails.path("originalResult")));
            return FullRnaCurrentUse.completedExclusionClaim(proof.path("claim"))
                    && proof.equals(criterion.at("/details/fullRnaUse")) && facts.equals(criterion.at("/details/pvs1Facts"))
                    && sameOriginal
                    && originalCriterion.path("status").equals(criterion.path("status"))
                    && originalCriterion.path("appliedStrength").equals(criterion.path("appliedStrength"))
                    && originalCriterion.path("reasonCode").equals(criterion.path("reasonCode"));
        } catch (RuntimeException invalid) { return false; }
    }

    /** A completed exact-effect allocation is not a missing-evidence blocker; scientific conflicts remain blockers. */
    public static boolean completedFullRnaAssignment(ObjectMapper mapper, JsonNode fullBatch, JsonNode candidate,
            JsonNode attempt, JsonNode criterion) {
        if (!"NOT_EVALUATED".equals(criterion.path("status").asText()) || !"NONE".equals(criterion.path("appliedStrength").asText())
                || !Set.of("FULL_RNA_SHARED_EFFECT_ASSIGNED_TO_PVS1", "FULL_RNA_SHARED_EFFECT_ASSIGNED_TO_PM4").contains(criterion.path("reasonCode").asText())) return false;
        try {
            JsonNode original = null; int found = 0;
            for (var row : fullBatch.path("results")) if (row.path("candidateId").equals(candidate.path("candidateId"))
                    && row.path("hypothesisId").equals(attempt.path("hypothesisId"))) { original = row; found++; }
            if (found != 1 || original == null) return false;
            JsonNode originalCriterion = null; int criterionCount = 0;
            for (var row : original.at("/localBatch/criteria")) if (row.path("criterionId").equals(criterion.path("criterionId"))) { originalCriterion = row; criterionCount++; }
            if (criterionCount != 1 || originalCriterion == null) return false;
            var facts = FullRnaCurrentUse.project(mapper, fullBatch, original, originalCriterion); var proof = facts.path("fullRnaUse");
            var claim = proof.path("claim");
            if (!proof.equals(criterion.at("/details/fullRnaUse")) || !"NOT_COUNTED".equals(claim.path("application").asText())
                    || !"MET".equals(claim.path("originalStatus").asText()) || !claim.path("reasonCode").equals(criterion.path("reasonCode"))) return false;
            String assigned = claim.path("assignedTo").asText(); JsonNode publicTarget = null, originalTarget = null; int targetCount = 0, rawTargetCount = 0;
            for (var row : attempt.at("/localBatch/criteria")) if (assigned.equals(row.path("criterionId").asText())) { publicTarget = row; targetCount++; }
            for (var row : original.at("/localBatch/criteria")) if (assigned.equals(row.path("criterionId").asText())) { originalTarget = row; rawTargetCount++; }
            if (targetCount != 1 || rawTargetCount != 1 || publicTarget == null || originalTarget == null || !"MET".equals(publicTarget.path("status").asText())) return false;
            var winner = FullRnaCurrentUse.project(mapper, fullBatch, original, originalTarget).path("fullRnaUse");
            return winner.equals(publicTarget.at("/details/fullRnaUse")) && "ADOPTED".equals(winner.at("/claim/application").asText())
                    && originalTarget.path("appliedStrength").equals(publicTarget.path("appliedStrength"))
                    && originalTarget.path("reasonCode").equals(publicTarget.path("reasonCode"))
                    && "MET".equals(winner.at("/claim/originalStatus").asText()) && winner.path("ledgerSha256").equals(proof.path("ledgerSha256"))
                    && winner.path("sourceOriginalSha256").equals(proof.path("sourceOriginalSha256"))
                    && winner.path("mechanicalEffectSha256").equals(proof.path("mechanicalEffectSha256"));
        } catch (RuntimeException invalid) { return false; }
    }

    /** Persist full output privately. Public transport uses a whitelist, never a recursive best-effort scrub. */
    public ObjectNode publicView(ObjectNode full) { return publicView(mapper, full); }
    public static ObjectNode publicView(ObjectMapper mapper, JsonNode full) {
        var out = mapper.createObjectNode();
        for (String field : List.of("schemaVersion", "analysisId", "sourceSnapshotSha256", "requestId", "executionMode",
                "automaticTrigger", "classificationEnabled", "status", "identity", "providerCounts", "newProviderInvocations", "newEvaluatorInvocations", "fullRnaArchiveSha256", "fullRnaObservedSourceIdentity",
                "pp2OtherPhenotypeSourceIdentity", "pp2OtherPhenotypeArchiveSha256", "pp2OtherPhenotypeDerivedArchiveSha256"))
            if (full.has(field)) out.set(field, full.path(field).deepCopy());
        var modules = out.putObject("modules");
        var moduleNames = new ArrayList<>(List.of("pm2", "prediction", "reference", "sharedSplice"));
        if (full.at("/identity/deNovo").isObject()) moduleNames.add("deNovo");
        if (full.at("/identity/pm3").isObject()) moduleNames.add("pm3");
        if (full.at("/identity/pp1").isObject()) moduleNames.add("pp1");
        if (full.path("modules").has("bp7")) moduleNames.add("bp7");
        if (full.at("/identity/pm4Configured").asBoolean()) moduleNames.add("pm4");
        if (full.at("/identity/pvs1Configured").asBoolean()) moduleNames.add("pvs1");
        if (full.path("modules").has("pm5Ptc")) moduleNames.add("pm5Ptc");
        if (full.at("/identity/bp3Configured").asBoolean()) moduleNames.add("bp3");
        for (String name : List.of("pm1", "pp2", "bp1"))
            if (full.at("/identity/" + name + "Configured").asBoolean()) moduleNames.add(name);
        for (String name : moduleNames) {
            var original = full.path("modules").path(name); var summary = modules.putObject(name);
            summary.put("status", original.path("status").asText(original.isMissingNode() ? "UNAVAILABLE" : "RECORDED"));
            if (original.has("reasonCode")) summary.put("reasonCode", publicReason(original.path("reasonCode").asText()));
            for (String field : List.of("providerCalls", "evaluatorCalls", "allSitesCalls", "sharedSpliceProviderCalls", "developmentProviderCalls", "storedVepProjections"))
                if (original.path(field).isIntegralNumber()) summary.set(field, original.path(field));
            summary.put("privateOriginalAvailable", !original.isMissingNode());
            if ("deNovo".equals(name)) {
                var supplied=original.path("sourceSupply");
                var task=supplied.has("sourceTask") ? supplied.path("sourceTask") : supplied;
                if (DeNovoSourceTasks.VERSION.equals(task.path("schemaVersion").asText()) && task.path("taskId").asText().matches("[a-f0-9]{64}"))
                    summary.set("sourceTask",task.deepCopy());
            }
        }
        var rows = out.putArray("results"); var hasher = new RuntimeEvidenceV2Adapter(mapper);
        for (var original : full.path("results")) {
            var local = original.path("localBatch"); var row = rows.addObject(); var view = row.putObject("localBatch");
            for (String field : List.of("schemaVersion", "candidateId", "hypothesisId", "contextId")) {
                view.set(field, local.path(field).deepCopy()); if (!"schemaVersion".equals(field)) row.set(field, local.path(field).deepCopy());
            }
            var criteria = view.putArray("criteria");
            for (var criterion : local.path("criteria")) {
                var entry = criteria.addObject();
                for (String field : List.of("criterionId", "status", "appliedStrength")) entry.set(field, criterion.path(field).deepCopy());
                String reason = publicReason(criterion.path("reasonCode").asText()); entry.put("reasonCode", reason);
                entry.put("reason", explanation(criterion.path("status").asText(), reason));
                var details = entry.putObject("details");
                var reasonExplanation = EvidenceReasonExplanation.build(mapper, entry);
                if (reasonExplanation != null) {
                    details.set("explanation", reasonExplanation);
                    entry.put("reason", reasonExplanation.path("summary").asText());
                }
                if (criterion.at("/details/applicabilityRouting").isObject())
                    details.set("applicabilityRouting", criterion.at("/details/applicabilityRouting").deepCopy());
                if (criterion.at("/details/materialPreparation").isObject())
                    details.set("materialPreparation", criterion.at("/details/materialPreparation").deepCopy());
                if (DeNovoSelectedBatch.CRITERIA.contains(criterion.path("criterionId").asText())
                        && "denovo-selected-facts-v1".equals(criterion.at("/details/deNovoFacts/schemaVersion").asText()))
                    details.set("deNovoFacts", criterion.at("/details/deNovoFacts").deepCopy());
                if(Pm3SelectedBatch.CRITERION.equals(criterion.path("criterionId").asText())
                        && "pm3-selected-facts-v1".equals(criterion.at("/details/pm3Facts/schemaVersion").asText()))
                    details.set("pm3Facts",criterion.at("/details/pm3Facts").deepCopy());
                if(Pp1SelectedBatch.CRITERION.equals(criterion.path("criterionId").asText())
                        && "pp1-selected-facts-v1".equals(criterion.at("/details/pp1Facts/schemaVersion").asText()))
                    details.set("pp1Facts",criterion.at("/details/pp1Facts").deepCopy());
                if (Bp7SelectedBatch.CRITERION.equals(criterion.path("criterionId").asText())
                        && !criterion.at("/details/applicabilityRouting/skipSpecializedAcquisition").asBoolean(false)
                        && criterion.at("/details/originalResult").isObject())
                    details.set("bp7", Bp7SelectedBatch.publicSummary(mapper, criterion.at("/details/originalResult")));
                if (criterion.at("/details/referenceFacts").isObject()) {
                    var facts = MissenseReferenceFactSummary.publicView(mapper, criterion.at("/details/referenceFacts"), criterion.path("criterionId").asText());
                    if (!facts.isEmpty()) details.set("referenceFacts", facts);
                }
                if (criterion.at("/details/spliceComparisonFacts").isObject())
                    details.set("spliceComparisonFacts", criterion.at("/details/spliceComparisonFacts").deepCopy());
                if (Set.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4").contains(criterion.path("criterionId").asText())
                        && criterion.at("/details/predictionMechanisms").isObject()) {
                    var summary = PredictionMechanismSummary.publicView(mapper, criterion.at("/details/predictionMechanisms"));
                    if (!summary.isEmpty()) details.set("predictionMechanisms", summary);
                }
                if ("ACMG_AMP_2015:PM2".equals(criterion.path("criterionId").asText())
                        && Pm2LocalExomesProfile.validProjectScope(mapper, criterion.at("/details/populationScope"))) {
                    details.set("populationScope", criterion.at("/details/populationScope").deepCopy());
                    entry.put("reason", entry.path("reason").asText()
                            + " 结论仅限 gnomAD 4.1 EXOMES，本轮没有建立全库或临床采用结论。");
                }
                JsonNode ptcProof = publishedPtcProof(full, original, criterion);
                if (ptcProof != null) details.set("pm5PtcFacts", ptcProof.path("facts").deepCopy());
                details.set("modulePointer", criterion.at("/details/modulePointer").deepCopy());
                boolean fullRnaConsumer = criterion.at("/details/fullRnaUse").isObject();
                if (fullRnaConsumer) {
                    String code = criterion.path("criterionId").asText();
                    String name = Pvs1SelectedBatch.CRITERION.equals(code) ? "pvs1Facts" : GeneralPm4Runtime.CRITERION.equals(code) ? "proteinLengthFacts" : "bp3Facts";
                    try {
                        var facts = FullRnaCurrentUse.project(mapper, full, original, criterion);
                        details.set(name, facts); details.set("fullRnaUse", facts.path("fullRnaUse").deepCopy());
                    } catch (RuntimeException invalid) {
                        details.putObject(name).put("publicProjectionIssue", "FULL_RNA_CURRENT_CENTRAL_BINDING_INVALID");
                        entry.put("status", "NOT_EVALUATED").put("appliedStrength", "NONE").put("reasonCode", "FULL_RNA_CURRENT_CENTRAL_BINDING_INVALID");
                    }
                }
                for (String name : List.of("pm1", "pp2", "bp1"))
                    if (("ACMG_AMP_2015:" + name.toUpperCase(java.util.Locale.ROOT)).equals(criterion.path("criterionId").asText())
                            && (name + "-general-facts-v1").equals(criterion.at("/details/" + name + "Facts/schemaVersion").asText()))
                        details.set(name + "Facts", "pm1".equals(name) ? pm1Facts(mapper, full, original, criterion)
                                : "bp1".equals(name) ? Bp1CurrentPublicBinding.project(mapper, full, original, criterion)
                                : criterion.at("/details/" + name + "Facts").deepCopy());
                if (!fullRnaConsumer && Pvs1SelectedBatch.CRITERION.equals(criterion.path("criterionId").asText())
                        && "pvs1-selected-facts-v1".equals(criterion.at("/details/pvs1Facts/schemaVersion").asText()))
                    details.set("pvs1Facts", criterion.at("/details/pvs1Facts").deepCopy());
                if (!fullRnaConsumer && GeneralBp3Runtime.CRITERION.equals(criterion.path("criterionId").asText())
                        && (criterion.at("/details/bp3Facts").isObject()
                        || criterion.at("/details/modulePointer").asText().startsWith("/modules/bp3/results/")))
                    details.set("bp3Facts", proteinLengthFacts(mapper, full, original, criterion, "bp3"));
                if (!fullRnaConsumer && GeneralPm4Runtime.CRITERION.equals(criterion.path("criterionId").asText())
                        && (criterion.at("/details/proteinLengthFacts").isObject()
                        || criterion.at("/details/modulePointer").asText().startsWith("/modules/pm4/results/")))
                    details.set("proteinLengthFacts", proteinLengthFacts(mapper, full, original, criterion, "pm4"));
                if ("ACMG_AMP_2015:PM2".equals(criterion.path("criterionId").asText())
                        && "pm2-local-facts-v1".equals(criterion.at("/details/populationFacts/schemaVersion").asText()))
                    details.set("populationFacts", criterion.at("/details/populationFacts").deepCopy());
                if ("ACMG_AMP_2015:PM2".equals(criterion.path("criterionId").asText())
                        && "pm2-rule-display-v1".equals(criterion.at("/details/populationRule/schemaVersion").asText()))
                    details.set("populationRule", criterion.at("/details/populationRule").deepCopy());
                var source = criterion.at("/details/originalResult");
                if (!source.isMissingNode() && !source.isNull()) details.put("originalResultSha256", hasher.canonicalSha256(source));
                var gate = criterion.at("/details/materialPreparationGate");
                if (gate.isObject()) details.putObject("materialPreparationGate").put("status", gate.path("status").asText("NOT_EVALUATED"))
                        .put("reasonCode", publicReason(gate.path("reasonCode").asText()));
            }
        }
        return out;
    }

    private static ObjectNode pm1Facts(ObjectMapper mapper, JsonNode full, JsonNode wrapper, JsonNode criterion) {
        // Preserve historical facts under their original contract; missing new capability never
        // upgrades old records to a current peer-adoption proof or triggers recomputation.
        if (!full.at("/modules/pm1/policy/conditionalPeerExecution").isObject()
                && !full.at("/identity/pm1ConditionalPeerExecution").isObject()
                && !criterion.at("/details/pm1Facts/currentEvaluationBinding").isObject()) {
            var old = criterion.at("/details/pm1Facts");
            var legacy = old instanceof ObjectNode object ? object.deepCopy() : mapper.createObjectNode().put("schemaVersion", "pm1-general-facts-v1");
            legacy.remove(List.of("currentEvaluationBinding", "crossCriterionConditionCheck", "peerPreparationIssues"));
            return legacy;
        }
        try {
            String candidate = wrapper.path("candidateId").asText(), hypothesis = wrapper.path("hypothesisId").asText();
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            JsonNode context = wrapper.path("context"), local = wrapper.path("localBatch"), module = full.at("/modules/pm1");
            String source = full.path("sourceSnapshotSha256").asText(), pointer = criterion.at("/details/modulePointer").asText();
            projectionBinding(source.matches("[a-f0-9]{64}") && source.equals(module.path("sourceSnapshotSha256").asText())
                    && GeneralPm1Runtime.executionIdentity(mapper).equals(module.at("/policy/conditionalPeerExecution"))
                    && full.path("analysisId").equals(full.at("/preparation/analysisId")) && !candidate.isBlank() && !hypothesis.isBlank()
                    && wrapper.path("candidateId").equals(local.path("candidateId")) && wrapper.path("hypothesisId").equals(local.path("hypothesisId"))
                    && context.isObject() && context.path("contextId").equals(local.path("contextId"))
                    && GeneralPm1Runtime.CRITERION.equals(criterion.path("criterionId").asText())
                    && pointer.matches("/modules/pm1/results/(?:0|[1-9][0-9]{0,5})"));
            adapter.validateVersionedContext(context);
            int enabled = 0, attempts = 0, wrappers = 0;
            for (var id : full.path("executionCriteria")) if (GeneralPm1Runtime.CRITERION.equals(id.asText())) enabled++;
            for (var row : full.path("results")) if (peerIdentity(row, candidate, hypothesis)) wrappers++;
            for (var row : full.at("/preparation/candidates")) if (candidate.equals(row.path("candidateId").asText()))
                for (var attempt : row.path("attempts")) if (hypothesis.equals(attempt.path("hypothesisId").asText())
                        && context.equals(attempt.path("context")) && attempt.path("blockers").isArray() && attempt.path("blockers").isEmpty()) attempts++;
            projectionBinding(enabled == 1 && wrappers == 1 && attempts == 1 && matchingPeerRows(module.path("results"), candidate, hypothesis) == 1);
            var raw = full.at(pointer); var projection = raw.path("sourceProjection");
            projectionBinding(raw instanceof ObjectNode && peerIdentity(raw, candidate, hypothesis)
                    && raw.path("policy").equals(module.path("policy")) && source.equals(raw.path("executionSourceSha256").asText())
                    && candidate.equals(projection.path("candidateId").asText()) && hypothesis.equals(projection.path("hypothesisId").asText())
                    && context.equals(projection.path("originalContext")) && raw.path("contextId").equals(projection.at("/evaluationContext/contextId"))
                    && GeneralPm1Runtime.CRITERION.equals(raw.at("/evaluation/criterionId").asText())
                    && criterion.path("status").equals(raw.at("/evaluation/evaluationResult/status"))
                    && criterion.path("appliedStrength").equals(raw.at("/evaluation/appliedStrength"))
                    && criterion.path("reasonCode").equals(raw.at("/evaluation/evaluationResult/reasonCode"))
                    && criterion.at("/details/originalResult").equals(raw.path("evaluation")));
            var body = projection.deepCopy(); ((ObjectNode) body).remove("bindingSha256");
            projectionBinding(adapter.canonicalSha256(body).equals(projection.path("bindingSha256").asText()));
            for (var check : raw.at("/regionAssessment/crossCriterionConditionCheck/checks")) if (check.path("available").isObject()) {
                var available = check.path("available"); String peerPointer = available.path("actualModulePointer").asText();
                projectionBinding(peerPointer.matches("/modules/(?:pm2|prediction|reference)/results/(?:0|[1-9][0-9]{0,5})"));
                var peer = full.at(peerPointer); String parentPointer = peerPointer.substring(0, peerPointer.indexOf("/results/"));
                projectionBinding(source.equals(full.at(parentPointer + "/sourceSnapshotSha256").asText())
                        && source.equals(peer.path("executionSourceSha256").asText())
                        && source.equals(available.path("executionSourceSha256").asText())
                        && peerIdentity(peer, candidate, hypothesis) && matchingPeerRows(full.at(parentPointer + "/results"), candidate, hypothesis) == 1
                        && "TRANSPORT_JSON_VALUE_C14N_V1".equals(available.path("auditHashMethod").asText())
                        && PublishedPm1CrossCriterionConditions.currentAuditSha256(mapper, peer).equals(available.path("actualModuleSha256").asText()));
                var evaluation = peer.at(available.path("evaluationPointer").asText()); var peerContext = peer.at(available.path("contextPointer").asText());
                projectionBinding((context.equals(peerContext) || projection.path("evaluationContext").equals(peerContext))
                        && available.path("criterionId").equals(check.at("/required/criterionId"))
                        && available.path("criterionId").equals(evaluation.path("criterionId"))
                        && PublishedPm1CrossCriterionConditions.currentAuditSha256(mapper, evaluation).equals(available.path("actualEvaluationSha256").asText())
                        && PublishedPm1CrossCriterionConditions.currentPeerReason(evaluation).equals(available.path("reasonCode").asText())
                        && available.path("appliedStrength").asText().equals(PublishedPm1CrossCriterionConditions.currentPeerStrength(evaluation))
                        && available.path("status").equals(evaluation.has("evaluationResult") ? evaluation.at("/evaluationResult/status") : evaluation.path("status")));
            }
            return GeneralPm1Runtime.publicSummary(((ObjectNode) raw).deepCopy());
        } catch (RuntimeException invalid) {
            return mapper.createObjectNode().put("schemaVersion", "pm1-general-facts-v1").put("clinicalValidationCompleted", false)
                    .put("publicProjectionIssue", "CENTRAL_PM1_MODULE_BINDING_INVALID");
        }
    }

    /** Only an executed result in this private batch can supply current material facts.
     * Cached summaries, arbitrary JSON pointers and content hashes are not authorities. */
    private static ObjectNode proteinLengthFacts(ObjectMapper mapper, JsonNode full, JsonNode wrapper,
            JsonNode criterion, String moduleName) {
        String schema = "pm4".equals(moduleName) ? "pm4-general-facts-v1" : "bp3-general-facts-v1";
        try {
            String id = "ACMG_AMP_2015:" + moduleName.toUpperCase(java.util.Locale.ROOT);
            String candidate = wrapper.path("candidateId").asText(), hypothesis = wrapper.path("hypothesisId").asText();
            JsonNode local = wrapper.path("localBatch"), context = wrapper.path("context");
            var module = full.path("modules").path(moduleName);
            String pin = full.path("sourceSnapshotSha256").asText();
            projectionBinding(pin.matches("[a-f0-9]{64}") && pin.equals(module.path("sourceSnapshotSha256").asText())
                    && full.path("analysisId").equals(full.at("/preparation/analysisId"))
                    && !candidate.isBlank() && !hypothesis.isBlank()
                    && wrapper.path("candidateId").equals(local.path("candidateId"))
                    && wrapper.path("hypothesisId").equals(local.path("hypothesisId"))
                    && context.isObject() && context.path("contextId").equals(local.path("contextId"))
                    && id.equals(criterion.path("criterionId").asText()));
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            adapter.validateVersionedContext(context);
            int selected = 0, wrappers = 0, prepared = 0;
            for (var enabled : full.path("executionCriteria")) if (id.equals(enabled.asText())) selected++;
            for (var row : full.path("results")) if (candidate.equals(row.path("candidateId").asText())
                    && hypothesis.equals(row.path("hypothesisId").asText())) wrappers++;
            for (var row : full.at("/preparation/candidates")) if (candidate.equals(row.path("candidateId").asText()))
                for (var attempt : row.path("attempts")) if (hypothesis.equals(attempt.path("hypothesisId").asText())
                        && context.equals(attempt.path("context")) && attempt.path("blockers").isArray()
                        && attempt.path("blockers").isEmpty()) prepared++;
            projectionBinding(selected == 1 && wrappers == 1 && prepared == 1);
            String prefix = "/modules/" + moduleName + "/results/";
            String pointer = criterion.at("/details/modulePointer").asText();
            projectionBinding(pointer.startsWith(prefix) && pointer.substring(prefix.length()).matches("0|[1-9][0-9]{0,5}")
                    && module.path("results").isArray());
            int index = Integer.parseInt(pointer.substring(prefix.length()));
            projectionBinding(index < module.path("results").size());
            JsonNode raw = module.path("results").get(index);
            int matching = 0;
            for (var row : module.path("results")) if (candidate.equals(row.path("candidateId").asText())
                    && hypothesis.equals(row.path("hypothesisId").asText())) matching++;
            projectionBinding(raw instanceof ObjectNode && matching == 1
                    && candidate.equals(raw.path("candidateId").asText()) && hypothesis.equals(raw.path("hypothesisId").asText())
                    && raw.path("policy").equals(module.path("policy"))
                    && id.equals(raw.at("/evaluation/criterionId").asText())
                    && criterion.path("status").equals(raw.at("/evaluation/evaluationResult/status"))
                    && criterion.path("appliedStrength").equals(raw.at("/evaluation/appliedStrength"))
                    && criterion.path("reasonCode").equals(raw.at("/evaluation/evaluationResult/reasonCode"))
                    && criterion.at("/details/originalResult").equals(raw.path("evaluation")));
            var projection = raw.path("sourceProjection");
            projectionBinding(candidate.equals(projection.path("candidateId").asText())
                    && hypothesis.equals(projection.path("hypothesisId").asText())
                    && context.equals(projection.path("originalContext"))
                    && raw.path("contextId").equals(projection.at("/evaluationContext/contextId")));
            // Runtime summary builders rebuild the finite legacy facts. New proof fields are
            // independently projected from the issued raw receipt, never from publicSummary.
            var facts = "pm4".equals(moduleName) ? GeneralPm4Runtime.publicSummary(((ObjectNode) raw).deepCopy())
                    : GeneralBp3Runtime.publicSummary(((ObjectNode) raw).deepCopy());
            facts.setAll(ProteinLengthPublicProof.project(mapper, raw));
            return facts;
        } catch (RuntimeException invalid) {
            var facts = mapper.createObjectNode().put("schemaVersion", schema).put("clinicalValidationCompleted", false);
            // No partial material/rule claims survive a broken wrapper or source association.
            var invalidRaw = mapper.createObjectNode().put("adoptionProof", "CENTRAL_MODULE_BINDING_INVALID");
            facts.setAll(ProteinLengthPublicProof.project(mapper, invalidRaw));
            facts.put("publicProjectionIssue", "CENTRAL_PROTEIN_LENGTH_MODULE_BINDING_INVALID");
            return facts;
        }
    }
    private static void projectionBinding(boolean valid) {
        if (!valid) throw new IllegalArgumentException("CENTRAL_PROTEIN_LENGTH_MODULE_BINDING_INVALID");
    }

    private static String publicReason(String value) { return value.matches("[A-Z][A-Z0-9_]{0,200}") ? value : "LOCAL_BATCH_SOURCE_REASON_REDACTED"; }

    private ObjectNode unavailable(String reason) { return mapper.createObjectNode().put("status", "NOT_EVALUATED").put("reasonCode", reason); }
    private static String reason(Exception failure, String fallback) {
        String text = failure.getMessage(); return text != null && text.matches("[A-Z][A-Z0-9_]*") ? text : fallback;
    }
    private static String explanation(String status, String reason) {
        return switch (reason) {
            case "PVS1_START_LOSS_FUNCTIONAL_ALTERNATIVE_TRANSCRIPT" -> "合格来源确认存在功能替代转录本，本次起始丢失 PVS1 分支不适用；这不表示变异良性。";
            case "PVS1_START_LOSS_UPSTREAM_PATHOGENIC_REFERENCE" -> "已核对起始丢失、疾病机制和替代转录本，并找到合格的上游独立致病参考，满足本次 PVS1 Moderate 条件。";
            case "PVS1_START_LOSS_NO_UPSTREAM_PATHOGENIC_REFERENCE" -> "已核对起始丢失、疾病机制和替代转录本；声明来源及区间内的检索已完成且未发现合格上游参考，满足本次 PVS1 Supporting 条件，不代表世界范围内无相关资料。";
            case "ROUTING_PM3_NON_RECESSIVE_CONTEXT" -> "当前是明确的非隐性疾病分析背景，不进入 PM3 隐性病例取证；其他疾病背景和证据分别处理，不据此判断变异良性。";
            case "PM5_PUBLISHED_GENOMIC_PTC_RULE_MET" -> "已核对基因组提前终止与同背景 PVS1，按终止密码子所在外显子采用 PM5。";
            case "PM5_PTC_NO_WEIGHT_IN_PUBLISHED_STOP_EXON" -> "同背景 PVS1 成立。专家规范对当前终止密码子所在外显子未赋予 PM5 权重。";
            case "PM5_PTC_PVS1_EXCLUDED_BY_RULE" -> "提前终止位置位于本规范的 PVS1 排除范围，本次不适用 PM5。";
            case "PM5_PTC_SPLICE_MECHANISM_EXCLUDED" -> "该专家规范的 PM5 适用于基因组变异直接引起的提前终止。当前变异属于剪接分支。";
            case "PM5_PTC_DIRECT_GENOMIC_STOP_REQUIRED" -> "当前后果未产生该专家规范要求的直接基因组提前终止密码子。";
            case "PVS1_PUBLISHED_GENOMIC_PTC_RULE_MET" -> "已验证编码变异及提前终止位置，满足当前专家规范的 PVS1。";
            case "PVS1_PUBLISHED_PTC_RULE_EXCLUDED" -> "提前终止位置属于当前专家规范明确排除的范围。";
            case "PM1_PUBLISHED_DOMAIN_DELETION_MET" -> "框内缺失完整覆盖适用专家规范列出的功能区。";
            case "PM1_DELETION_OUTSIDE_PUBLISHED_DOMAINS" -> "重建的缺失区间与规范列出的功能区均不重叠。";
            case "PM1_DELETION_DOMAIN_COVERAGE_REVIEW_REQUIRED" -> "已确认缺失触及功能区，完整覆盖尚待核实。";
            case "PM1_DELETION_TRANSCRIPT_SCOPE_UNRESOLVED" -> "所选转录本缺少已核对的功能区映射。";
            case "PM1_DELETION_TRANSCRIPT_SEQUENCE_UNVERIFIED" -> "所选转录本序列与已核对的功能区映射不一致。";
            case "PM1_DELETION_MIXED_CONSEQUENCE_UNRESOLVED" -> "同时记录了剪接或其他变异后果，实际蛋白缺失区间仍待核实。";
            case "PM1_DELETION_PROTEIN_FOOTPRINT_UNRESOLVED" -> "本次序列重建尚不能确认纯蛋白缺失区间。";
            case "ROUTING_EXTENDED_DELETION_PM1_ONLY" -> "当前证据尚未接通这类长序列缺失的判定资料。";
            case "ROUTING_MISSENSE_REQUIRED" -> "本背景绑定转录本的已核对注释不是错义变化，不进入这条错义规则的专用取证；不是数据库没有记录。";
            case "ROUTING_PM1_IMPLEMENTED_MISSENSE_SCOPE" -> "当前背景尚未接通这一变异后果的 PM1 条款。";
            case "ROUTING_NO_ANNOTATED_PROTEIN_LENGTH_CHANGE" -> "当前精确转录本仅注释为错义或同义变化，没有蛋白长度改变或剪接后果标记，跳过本次蛋白长度规则的区域取证；不表示已排除所有潜在 RNA 影响。";
            case "PP1_QUALIFIED_SEGREGATION_THRESHOLD_MET" -> "已核验家系中的患病、基因型、亲属关系及适用知识，合格共分离事件达到本次 PP1 强度；只覆盖已核查资料，不代表临床分类。";
            case "PP1_QUALIFIED_SEGREGATION_BELOW_THRESHOLD" -> "已有可评价的共分离事实，但有效分值或本次剩余位点容量不足，未达到 PP1 支持级；不据此推断变异良性。";
            case "PP1_LITERATURE_REQUIREMENTS_UNRESOLVED" -> "已保留文献事实，但仍缺少判定所需的身份、家系、疾病知识或共享计分条件；请展开具体要求、相关原句和缺项。";
            case "PP1_SOURCE_SNAPSHOT_NOT_PREPARED", "PP1_SOURCE_SNAPSHOT_INVALID", "PP1_SOURCE_CAPTURE_FAILED", "PP1_REPLAY_FAILED", "PP1_REPLAY_TIMEOUT" -> "PP1 资料读取或来源复核未完成，属于执行问题，不能据此认定论文没有共分离证据。";
            case "PM3_QUALIFIED_LITERATURE_THRESHOLD_MET" -> "文献病例的变异身份、隐性疾病背景、资料资格和适用政策已核对，独立观察积分达到本次 PM3 强度；范围限已核验论文与本地资料，不代表临床分类。";
            case "PM3_QUALIFIED_LITERATURE_BELOW_THRESHOLD" -> "已有可评价的文献观察，但核验后计入的分值未达到 PM3 支持级阈值；这不等于变异良性。";
            case "PM3_LITERATURE_REQUIREMENTS_UNRESOLVED" -> "已取得相关文献观察，但仍有身份、相位、稀有性或伙伴独立分类等缺项或冲突；具体条件和原文位置保留在逐条资料中。";
            case "PM3_NO_COMPATIBLE_LITERATURE_OBSERVATIONS" -> "本次尚无与所选变异相容的已核验 PM3 病例资料；这不表示世界范围内没有相关论文。";
            case "PM3_NO_POLICY_QUALIFIED_OBSERVATIONS" -> "已找到文献观察，但当前疾病背景或适用政策尚未核定、存在冲突或有未实现条件；请查看未采用观察的具体原因及原文。";
            case "PM3_TARGET_IDENTITY_UNRESOLVED" -> "已提取相关病例和变异描述，但尚未把文献中的目标变异精确对应到参考序列与转录本，本次未评价；具体缺项和原文位置见病例资料。";
            case "PM3_LITERATURE_QUALIFICATION_INCOMPLETE" -> "已保留文献观察，但尚无资料同时满足本次判定条件；请查看逐病例的身份、疾病背景及适用规则核查结果。未核实不等于原文不存在，也不等于已经证明不相容。";
            case "PM3_SELECTED_RECESSIVE_DISEASE_CONTEXT_REQUIRED" -> "PM3 需要明确的常染色体隐性疾病分析背景；当前选择尚不满足，不能借用其他疾病的文献分值。";
            case "PM3_SOURCE_SNAPSHOT_NOT_PREPARED", "PM3_SOURCE_SNAPSHOT_INVALID", "PM3_SOURCE_CAPTURE_FAILED" -> "PM3 资料绑定或读取未完成，属于软件执行问题，本次未评价；不能解释为文献中没有病例。";
            case "PM3_OBSERVATIONS_NOT_APPLICABLE" -> "已核查的病例观察不属于本次 PM3 适用条件；不据此推断变异良性。";
            case "PM1_PUBLISHED_CRITICAL_LOCATION_MATCH" -> "精确转录本的氨基酸变化落入发布专家规范明确规定的关键区域或位点，满足本项目科研 PM1 条件。";
            case "PM1_REVIEWED_REGION_MATCH" -> "当前错义变异所在的热点或关键功能区通过 PM1 区域核查。";
            case "PM1_REVIEWED_REGION_OUTSIDE_QUALIFIED" -> "当前蛋白位置处于已复核范围，未命中适用的热点或关键区域。";
            case "PM1_REVIEWED_REGION_BENIGN_OBSERVATION" -> "该区域存在已核实的相关良性错义变异，未满足 PM1。";
            case "PM1_REVIEWED_REGION_NOT_QUALIFIED" -> "区域复核已完成，该区域未达到 PM1 采用条件。";
            case "PM1_REVIEWED_REGION_OBSERVATION_UNRESOLVED" -> "区域内有错义变异的分类资料存在冲突，暂未采用 PM1。";
            case "PM1_REVIEWED_REGION_OBSERVATION_INCOMPLETE" -> "区域内有错义变异缺少可核实的分类或疾病背景，暂未采用 PM1。";
            case "PM1_REVIEWED_REGION_REVIEW_PENDING" -> "区域边界、疾病相关性或采用强度尚待复核。";
            case "PM1_REVIEWED_REGION_BENIGN_SURVEY_INCOMPLETE" -> "该区域的良性错义变异分布尚未核查完整。";
            case "PM1_REVIEWED_REGION_INDEPENDENT_OBSERVATION_MISSING" -> "缺少独立于当前变异的区域致病错义观察。";
            case "PM1_REVIEWED_REGION_POSITION_UNCOVERED" -> "已复核的区域资料尚未覆盖当前蛋白位置。";
            case "PM1_REVIEWED_REGION_OVERLAP" -> "同一蛋白位置命中多份重叠的区域复核，需要确认适用范围。";
            case "PM1_REVIEWED_REGION_SPECIALIZATION_UNRESOLVED" -> "已找到可能适用的专家规范，需先核对专规范围或接入其条款。";
            case "PM1_REVIEWED_REGION_RELEASE_CATALOG_UNAVAILABLE" -> "专家规范目录不可用，尚未确认可使用通用 PM1 路径。";
            case "PM1_REVIEWED_REGION_CATALOG_INVALID" -> "本地区域资料未通过文件摘要、记录结构或蛋白序列核查。";
            case "PM1_REGION_ENRICHMENT_CALIBRATION_REQUIRED" -> "该热点的富集方法尚缺适用的强度校准依据。";
            case "PM1_REGION_ENRICHMENT_DATA_INSUFFICIENT" -> "区域或背景缺少可用于比较的致病与良性资料。";
            case "PM1_REGION_ENRICHMENT_BELOW_THRESHOLD" -> "区域变异富集未达到适用的 PM1 强度门槛。";
            case "PM1_PUBLISHED_EXPLICIT_EXCLUSION" -> "该变化属于发布规范明确排除的范围，本次不满足 PM1。";
            case "PM1_PUBLISHED_PEER_RESULT_UNAVAILABLE" -> "本条PM1规范还要求核对同一变异和疾病背景的其他证据；当前实际判定结果尚不可用。请查看所需证据、已有结果与具体缺项。";
            case "PM1_PUBLISHED_REQUIRED_PEER_NOT_MET" -> "本条PM1规范要求的前置证据已完成核查，但未达到规定状态或强度，因此本次不满足PM1。";
            case "PM1_PUBLISHED_PEER_EXCLUSION_APPLIES" -> "同一背景的排除证据已达到该规范规定的强度，因此本次不再采用PM1。";
            case "PM1_PEER_CONTEXT_MISMATCH", "PM1_PEER_CURRENT_CONTEXT_MISMATCH", "PM1_PEER_SOURCE_SNAPSHOT_MISMATCH", "PM1_PEER_RESULT_CONFLICT", "PM1_PEER_CURRENT_MODULE_AMBIGUOUS" -> "关联证据的背景、来源或唯一结果绑定不一致，不能将其用于本次PM1判定；请查看具体冲突。";
            case "PM1_PUBLISHED_SOURCE_SCOPE_NOT_COVERED" -> "变化未命中当前规范可执行的区域或位点；不能据此推断所有功能区域均不存在。";
            case "PP2_PUBLISHED_MECHANISM_AND_LOW_BENIGN_BACKGROUND" -> "发布规范对当前精确疾病和转录本明确支持错义致病机制及低良性错义背景，满足本项目科研 PP2 条件；没有自行套用新版约束阈值。";
            case "PP2_MISSENSE_MECHANISM_NOT_SUPPORTED" -> "已核对的疾病变异谱不支持错义变异为常见致病机制。";
            case "PP2_LOW_BENIGN_BACKGROUND_NOT_SUPPORTED" -> "已核对的变异谱不符合低良性错义背景条件。";
            case "PP2_GERMLINE_SCOPE_NOT_DECLARED" -> "当前未明确声明适用的胚系科研范围，不能从基因型或样例数据自动推断并采用 PP2。";
            case "BP1_PUBLISHED_SCOPE_AND_PROJECT_CHECKS_MET" -> "发布规范允许当前精确背景采用 BP1，且本地实验引用与项目剪接排除检查通过；未收录实验不代表实验正常，也不等于临床分类。";
            case "BP1_PUBLISHED_REGION_AND_SPLICE_CONDITIONS_MET" -> "当前错义变异位于发布规范指定区域之外，精确转录本与10 kb未掩码剪接预测符合规范条件，给予BP1强级；已检查加载的功能资料，不代表完成临床分类。";
            case "BP1_INSIDE_PUBLISHED_EXCLUDED_REGION" -> "当前变异位于该规范明确排除的蛋白区域内，不能采用这条区域外BP1规则；这不等于变异已被判定有害。";
            case "BP1_PUBLISHED_REGION_FUNCTIONAL_REVIEW_REQUIRED" -> "发布附件中存在该替换的异常、中间或待解释功能/剪接结果，需先复核，不能直接给予BP1。";
            case "BP1_PUBLISHED_SPLICE_CONDITION_NOT_MET" -> "10 kb未掩码SpliceAI分数高于该发布规范的0.1界限，未满足BP1的剪接前提；不自动判定致病。";
            case "BP1_REQUIRED_WIDE_SPLICE_SOURCE_UNAVAILABLE", "BP1_REQUIRED_WIDE_SPLICE_IDENTITY_MISMATCH" -> "该规范要求10 kb未掩码剪接预测，当前对应工具或身份不合格；不能借用较短窗口结果。";
            case "BP1_REGION_ATTACHMENT_ASSESSMENT_INVALID", "BP1_REGION_ATTACHMENT_SOURCE_INVALID", "BP1_REGION_FUNCTIONAL_OBSERVATION_INVALID",
                    "BP1_REGION_INTERPRETATION_INVALID", "BP1_REGION_SOURCE_CONDITIONS_UNSUPPORTED", "BP1_REGION_STRENGTH_UNSUPPORTED",
                    "BP1_REGION_SOURCE_RANGE_UNSUPPORTED", "BP1_REGION_SOURCE_RANGE_MISMATCH" -> "BP1发布条款、附件、区域或强度未完整对应，资料核对失败，未开展判定。";
            case "BP1_REGION_RESIDUE_UNRESOLVED", "BP1_REGION_CODING_CHANGE_UNRESOLVED" -> "当前变异的编码或蛋白位置尚未精确确认，无法核对BP1区域条件。";
            case "BP1_RECORDED_FUNCTIONAL_EFFECT_REVIEW_REQUIRED", "BP1_RECORDED_SPLICE_EFFECT_REVIEW_REQUIRED" -> "存在与当前变异关联的功能或剪接资料，需要复核后才能采用 BP1。";
            case "BP1_PROJECT_SPLICE_PREDICTION_REQUIRES_REVIEW" -> "剪接预测达到本项目要求复核的范围，本次不自动采用 BP1；此阈值是项目额外限制，并非专家规范的 BP1 阈值。";
            case "BP1_PUBLISHED_FUNCTIONAL_EFFECT_REVIEW_REQUIRED" -> "已精确匹配到公开功能实验中的受损或中间结果，须复核后才能采用 BP1；这不是自动致病分类或PS3批准。";
            case "BP1_FUNCTION_SCREEN_RECORD_UNAVAILABLE" -> "当前变异位于已知功能研究的覆盖区域，但没有取得该替换的合格原始行；不能把未测或缺行当成功能正常。";
            case "BP1_FUNCTION_SCREEN_SEQUENCE_UNRESOLVED", "BP1_FUNCTION_SCREEN_VARIANT_IDENTITY_REQUIRED" -> "已找到功能研究，但研究蛋白、完整编码序列或当前氨基酸变化尚未精确对应；不能用相近编号代替。";
            case "BP1_FUNCTION_SCREEN_NOT_CONFIGURED" -> "公开功能研究检查资料尚未配置，BP1未评价；这属于资料配置缺项，不是数据库无记录。";
            case "PUBLISHED_KNOWLEDGE_BACKGROUND_NOT_COVERED" -> "当前合格发布知识未覆盖这个基因、疾病与遗传方式组合；不能借用别的背景，也不代表外部数据库没有资料。";
            case "PUBLISHED_KNOWLEDGE_TRANSCRIPT_NOT_COVERED" -> "已找到相应疾病与遗传方式的规范，但其带版本转录本尚未与本次注释精确对应；需要补齐转录本对应资料。";
            case "PUBLISHED_KNOWLEDGE_ASSEMBLY_NOT_COVERED" -> "已找到背景与转录本资料，但没有适用于本次参考基因组版本的合格绑定。";
            case "BP1_SOURCE_NOT_APPLICABLE" -> "匹配的专家规范明确不适用 BP1；本次未评价，不是缺少数据库记录，也不表示变异致病。";
            case "BP1_PUBLISHED_SOURCE_CONDITIONS_UNSUPPORTED", "BP1_PUBLISHED_SOURCE_SEMANTICS_UNSUPPORTED", "BP1_PUBLISHED_INTERPRETATION_UNSUPPORTED" -> "已经找到 BP1 规范，但软件尚未实现其中全部附加条件；保留未评价，不能简化为截短主导就采用。";
            case "BP1_PUBLISHED_ATTACHMENTS_UNSUPPORTED" -> "BP1 规范含有尚未完整解释和实现的附件条件，软件暂不自动采用；需要核定附件而非重新寻找基因记录。";
            case "BP1_PUBLISHED_STRENGTH_UNSUPPORTED" -> "BP1 规范的证据强度或强度条件尚未被软件完整支持；没有静默降为支持级。";
            case "BP1_SOURCE_APPLICABILITY_CONFLICT" -> "BP1 来源的适用标记与明确禁止使用的条文冲突，需要核定来源，不能自动选择有利的一项。";
            case "BP1_SOURCE_APPLICABILITY_UNRESOLVED" -> "BP1 来源未明确给出可识别的适用状态，不能将空值解释为专家禁止使用或允许采用。";
            case "PUBLISHED_KNOWLEDGE_CLAUSE_NOT_APPLICABLE" -> "匹配的发布规范明确不适用当前证据，保留未评价。";
            case "PM1_PUBLISHED_KNOWLEDGE_UNAVAILABLE", "PP2_PUBLISHED_KNOWLEDGE_UNAVAILABLE", "BP1_PUBLISHED_KNOWLEDGE_UNAVAILABLE" -> "对应发布规范资料未配置或未通过摘要绑定核对，需要补齐合格资料。";
            case "BP1_FUNCTIONAL_REFERENCES_UNAVAILABLE", "BP1_EXPERIMENT_SOURCE_UNAVAILABLE", "BP1_EXTERNAL_EXPERIMENT_DECLARATION_UNRESOLVED" -> "功能引用或实验目录的范围尚未核清，不能将缺失资料当作没有功能影响。";
            case "REFERENCE_MATERIAL_BACKGROUND_NOT_FOUND" -> "已读取合格参考材料集合，但没有与本次疾病、遗传方式及带版本转录本/蛋白精确匹配的材料；这不代表ClinVar没有参考变异。";
            case "REFERENCE_MATERIAL_SELECTION_AMBIGUOUS" -> "同一背景匹配到多份参考材料，需要先解决版本或范围冲突；系统没有挑选更有利的一份。";
            case "REFERENCE_MATERIAL_INDEX_INVALID" -> "参考材料集合无法通过文件摘要或原批准绑定核对，本次未采用；请维护者检查配置与材料版本。";
            case "REFERENCE_MATERIAL_PROTEIN_IDENTITY_REQUIRED" -> "所选注释缺少明确的带版本蛋白身份，不能精确选择参考材料。";
            case "REFERENCE_MATERIAL_CONTEXT_USE_NOT_APPROVED" -> "匹配的参考材料没有覆盖本次数据用途，科研材料不会自动用于患者分析。";
            case "BP7_LOCAL_COMPUTATIONAL_REQUIREMENTS_MET" -> "同源剪接预测、精确位置及本地实验检查满足 BP7 Supporting；未收录实验不代表实验正常，尚不构成临床分类。";
            case "BP7_RECORDED_ALTERED_SPLICING_REVIEW_REQUIRED", "BP7_EXPERIMENT_TRANSCRIPT_OR_GENE_UNRESOLVED",
                 "BP7_SPLICE_EXPERIMENT_REVIEW_REQUIRED", "BP7_EXPERIMENT_MECHANISM_UNRESOLVED" -> "存在异常剪接实验或尚未解决的实验关联，需要复核，不能自动采用 BP7。";
            case "BP7_EXPERIMENT_SOURCE_UNAVAILABLE", "BP7_FUNCTIONAL_REFERENCES_UNAVAILABLE" -> "实验目录或功能审核引用不可用，不能当作未发现冲突。";
            case "BP7_POSITION_SPLICE_PROXIMAL", "BP7_INTRONIC_SPLICE_PROXIMAL", "BP7_CONSEQUENCE_NOT_APPLICABLE" -> "所选变异的位置或后果不在当前计算 BP7 适用范围内。";
            case "BP7_PREDICTION_PREPARATION_BLOCKED" -> "共享预测准备未完成，BP7尚未评价；请查看同次PP3/BP4的具体阻断原因。";
            case "BP7_PREPARED_PLAN_UNAVAILABLE" -> "BP7准备记录缺失或结构不完整，需要维护者核查。";
            case "BP7_SELECTED_EXECUTION_UNAVAILABLE" -> "BP7软件执行失败；不能据此认定数据库无记录或证据不满足，需要维护者核查。";
            case "BP7_SPLICE_SCORE_UNINFORMATIVE", "BP7_SPLICE_NOT_BENIGN" -> "同源剪接预测尚不满足良性条件，本次 BP7 不满足；不据此推断致病。";
            case "PM4_PROJECT_LOCAL_SEQUENCE_DERIVED_STOP_LOSS_MET" -> "精确转录本和参考序列可确定终止丢失后的新终止位点与蛋白延长，满足本项目PM4条件；这是序列推导，未宣称RNA或蛋白实验。";
            case "PM4_STOP_LOSS_THREE_PRIME_SEQUENCE_UNAVAILABLE" -> "缺少该精确转录本原终止密码子之后的可用3′端序列，无法推导延长长度，PM4未评价；这不表示已完成查询且不存在新终止位点。";
            case "PM4_STOP_LOSS_NEW_STOP_NOT_FOUND_IN_TRANSCRIPT" -> "已取得的完整转录本序列范围内未找到新的同框终止密码子，无法确定延长终点，PM4未评价；不推断转录本范围之外的序列。";
            case "PM4_STOP_LOSS_NEW_STOP_BEYOND_TECHNICAL_WINDOW" -> "当前有界序列窗口内没有找到新终止位点，属于软件技术范围限制，PM4未评价。";
            case "PM4_PROJECT_LOCAL_REPEAT_OVERLAP_NOT_MET" -> "完整等价变化范围与来源明确的重复区相交，不满足本项目PM4非重复条件；这不代表BP3成立。";
            case "PM4_PROJECT_LOCAL_OPERATIONAL_NON_REPEAT_MET" -> "完整同序列UniProt/InterPro区域查询与原/变异蛋白短重复检查通过，按显式项目政策推定非重复；不是绝对非重复证明或临床采用。";
            case "PM4_OPERATIONAL_LOW_COMPLEXITY_OR_DISORDER_EXCLUDED" -> "影响范围重叠已注释的低复杂度或无序区域，当前通用非重复推定不适用；不自动认定BP3成立。";
            case "PM4_OPERATIONAL_SOURCE_COMPLETENESS_REQUIRED" -> "缺少同版本完整区域来源与序列一致性证明，不能采用通用非重复推定。";
            case "PM4_PROJECT_LOCAL_QUALIFIED_NON_REPEAT_MET" -> "精确框内长度变化及完整受审非重复范围满足本项目PM4条件；未作最终临床分类。";
            case "PM4_SHORT_REPEAT_CANDIDATE_REQUIRES_QUALIFICATION" -> "原始或变异后蛋白存在短串联重复候选，尚不能确认PM4区域适用性。";
            case "PM4_NON_REPEAT_METHOD_OR_CURATED_COVERAGE_NOT_QUALIFIED", "PM4_NON_REPEAT_QUALIFIED_PROTEIN_NOT_COVERED", "PM4_NON_REPEAT_QUALIFIED_COVERAGE_GAP" -> "序列变化已核对，但缺少覆盖完整影响范围的非重复依据；数据库或算法无命中不能替代。";
            case "PM4_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION" -> "本地目录存在该基因的专门规范，须先核对PM4条款，不能直接采用通用条件。";
            case "PM4_REGION_SOURCE_CONFLICT" -> "重复区来源与非重复资料在本次影响范围冲突，保留未评价。";
            case "PM4_COMBINED_ACCESSION_NOT_IN_SNAPSHOT", "PROTEIN_REGION_GENE_NOT_IN_LOCAL_SNAPSHOT", "PM4_COMBINED_RESOURCE_NOT_CONFIGURED" -> "当前本地区域快照未覆盖该精确蛋白或尚未配置，不能把资料缺失当作非重复。";
            case "PROTEIN_REGION_DEMAND_NETWORK_DISABLED" -> "本地未找到符合蛋白身份及版本的完整区域缓存；当前未启用在线补取。";
            case "PROTEIN_REGION_REQUEST_TIMEOUT", "PROTEIN_REGION_DEMAND_TOTAL_TIMEOUT", "PROTEIN_REGION_TRANSPORT_UNAVAILABLE" -> "蛋白区域资料获取未完成或超时；不能据此认定数据库没有记录或变异位于非重复区。";
            case "PROTEIN_REGION_RELEASE_MISMATCH" -> "在线资料的实际发布版本与本次配置不一致，已拒绝采用，需统一核对来源版本。";
            case "PROTEIN_REGION_SERVICE_COOLDOWN", "PROTEIN_REGION_HTTP_429", "PROTEIN_REGION_HTTP_503" -> "区域资料服务要求暂缓请求，本批已停止补取；已有合格本地缓存仍可复用。";
            case "PROTEIN_REGION_DEMAND_PROTEIN_LIMIT", "PROTEIN_REGION_DEMAND_REQUEST_LIMIT", "PROTEIN_REGION_DEMAND_BYTE_LIMIT", "PROTEIN_REGION_BODY_LIMIT" -> "本批区域资料获取达到数量或大小上限，尚未取得完整事实；没有用部分响应判定。";
            case "PROTEIN_REGION_SEARCH_INCOMPLETE", "PROTEIN_REGION_INTERPRO_PAGINATION_INCOMPLETE" -> "区域查询分页或结果数量不完整，不能据此推断没有重复区。";
            case "PROTEIN_REGION_CACHE_BYTES_CHANGED", "PROTEIN_REGION_CACHE_IDENTITY_INVALID", "PROTEIN_REGION_CACHE_MANIFEST_MISSING" -> "本地区域缓存内容或来源清单不一致，已拒绝复用；没有用联网结果掩盖损坏。";
            case "PROTEIN_REGION_NO_REVIEWED_RECORD", "PROTEIN_REGION_EXACT_MAPPING_UNAVAILABLE", "PROTEIN_REGION_RECORD_NOT_FOUND" -> "当前来源未提供能够精确对应所选转录本及完整蛋白序列的记录，不能借用相近蛋白。";
            case "PM2_LOCAL_INTERVAL_QC_FAILED" -> "本地记录虽为 FILTER=PASS，但另带区间覆盖质控失败标记，不能据此认定罕见性满足 PM2。";
            case "PM2_PROJECT_LOCAL_HIT_MET" -> "本地 EXOMES 精确观测满足已采用的 PM2 频率/计数条件。";
            case "PM2_PROJECT_LOCAL_ABSENCE_MET" -> "本地 EXOMES 未收录该精确 SNV，且该位置总体检测量与区域条件满足本地缺失规则；未证明祖源匹配或全库缺失。";
            case "PM2_OBSERVED_RARITY_THRESHOLD_REQUIRED" -> "已取得该变异的人群频率；缺少适用的罕见频率阈值，不能把有记录的低频变异按缺失规则判定。";
            case "PM2_ALL_SITES_AN_INSUFFICIENT" -> "未收录该变异，但该位置有效检测量不足，不能据此授予 PM2。";
            case "PM2_ALL_SITES_REGION_EXCLUDED" -> "未收录该变异，但位置不满足规则要求的捕获或调用区域条件。";
            case "PM2_ALL_SITES_LOCUS_NOT_DETECTED" -> "变异库及检测量表均未提供该位置的充分观测，不能当作未见变异的证据。";
            case "PM2_ALL_SITES_RESOURCE_CHANGED_OR_UNREADABLE", "PM2_ALL_SITES_RESOURCE_INVALID" -> "本地检测量文件无法读取或身份已变化，需要核对资源及索引。";
            case "PM2_ABSENCE_DETECTION_POLICY_REQUIRED" -> "检测量事实已保留，但当前专门规则尚未定义可采用的未检出条件。";
            case "PM2_INDEL_DETECTION_PROOF_REQUIRED" -> "单个位点的检测量不能证明该插入、缺失或复杂变异可被可靠检出。";
            case "PM2_SPECIALIST_POLICY_REVIEW_REQUIRED" -> "该基因存在专门规范，尚未完成对应规则接入；通用规则不能越过专门规范。";
            case "PM2_DISEASE_FREQUENCY_PARAMETERS_REQUIRED" -> "人口事实可查询，当前背景仍缺少适用的 PM2 频率或缺失判定规则。";
            case "PM2_PROJECT_LOCAL_HIT_NOT_MET" -> "本地 EXOMES 精确观测未满足已采用的 PM2 频率/计数条件。";
            case "PM2_LOCAL_WHOLE_DATABASE_HOMOZYGOTE_PROOF_MISSING" -> "已取得本地外显子观测；原政策还要求整个 gnomAD 中未见纯合个体，本地子集不能证明这一条件。";
            case "PM2_LOCAL_NO_FAF_SCOPE_UNSUPPORTED" -> "本地记录缺少适用的 FAF95；仅在外显子中观察到一个等位基因，不能替代原条款的全库无 FAF 特例。";
            case "PM2_LOCAL_ALL_SITES_DETECTION_PROOF_MISSING" -> "本地没有该精确等位基因的记录，但缺少该位点足够可检出的证明，不能当作频率为零或满足 PM2。";
            case "PM2_LOCAL_EXOMES_POLICY_REVIEW_REQUIRED" -> "人口事实已保留；现有 PM2 条款尚未明确采用到本地 EXOMES 子集，不能仅凭低频自动判定满足。";
            case "SELECTED_CONTEXT_UNAVAILABLE" -> "所选候选缺少可用解释背景。";
            case "REFERENCE_APPROVED_MATERIAL_UNAVAILABLE" -> "参考事实可保留，但尚无匹配的可信批准材料。";
            case "MISSENSE_CONTEXT_USE_NOT_APPROVED", "MISSENSE_CONTEXT_DATA_CLASS_NOT_APPROVED" -> "参考材料的原审核未允许本次输入类别和用途，未进入证据判定。";
            case "REFERENCE_MATERIAL_QUALIFICATION_INVALID" -> "参考材料与原审核绑定不一致或无法校验，需要核对原件和配置。";
            case "REFERENCE_BUNDLE_CRITERION_MISMATCH" -> "配置的参考材料不属于本次证据，不能跨证据使用。";
            case "REFERENCE_PATIENT_MATERIAL_QUALIFICATION_UNAVAILABLE" -> "病例参考事实已保留，现有材料尚不能证明患者应用资格，未调用批准材料评价。";
            case "REFERENCE_DRAFT_DATA_CLASS_UNSUPPORTED" -> "病例数据类别与现有公共参考草稿合同不相交，未更改类别。";
            case "PM2_CONFIGURATION_UNAVAILABLE", "PREDICTION_CONFIGURATION_UNAVAILABLE", "REFERENCE_CONFIGURATION_UNAVAILABLE" -> "此证据模块尚未完成可信配置。";
            case "QUERY_TRANSCRIPT_REANNOTATION_REQUIRED" -> "归一化后的查询发生变化，需要对应的新转录本注释。";
            case "QUERY_ANNOTATION_NOT_CONFIGURED", "QUERY_ANNOTATION_DISABLED" -> "归一化改变了查询坐标；对应的新注释通路尚未配置或已关闭，不能继续套用旧坐标注释。";
            case "QUERY_ANNOTATION_UNAVAILABLE", "QUERY_ANNOTATION_ASSEMBLY_UNSUPPORTED" -> "归一化改变了查询坐标；当前参考版本的 VEP 资源不可用，尚未取得新坐标注释。";
            case "QUERY_ANNOTATION_TIMEOUT" -> "已按归一化后的坐标请求 VEP 注释，但执行超时；这是工具执行问题，不代表没有相关记录。";
            case "QUERY_ANNOTATION_FAILED" -> "归一化后补注释执行失败；原始注释仍保留，但不能作为新查询身份的注释使用。";
            case "QUERY_ANNOTATION_NO_RECORD", "QUERY_ANNOTATION_TRANSCRIPT_MISSING" -> "已查询归一化后的变异，但未取得本次所选版本转录本的完整对应注释。";
            case "QUERY_ANNOTATION_TRANSCRIPT_AMBIGUOUS" -> "新坐标注释中所选转录本对应多条记录，无法唯一核对，未自动选择。";
            case "QUERY_ANNOTATION_IDENTITY_MISMATCH" -> "新坐标注释与已选转录本的基因、来源或版本不一致，不能混用。";
            case "DENOVO_QUALIFIED_POINTS" -> "合格且去重后的新生事件积分达到采用门槛；PS2与PM6共同计算，只采用其中一条。";
            case "DENOVO_SHARED_EVENT_ASSIGNED_TO_PS2" -> "这些事件已计入PS2，PM6不重复计分；不是缺少父母资料。";
            case "DENOVO_SHARED_EVENT_ASSIGNED_TO_PM6" -> "这些事件已计入PM6，PS2不重复计分；不是两条独立证据。";
            case "DENOVO_NO_OBSERVATIONS" -> "本次已接入资料中没有匹配当前变异和背景的病例观察；不代表所有文献均无记录。";
            case "DENOVO_NO_QUALIFIED_OCCURRENCES" -> "已找到观察记录，但没有事件通过全部资格核验；具体检测、亲缘或表型缺项保留在逐事件摘要中。";
            case "DENOVO_BELOW_SUPPORTING" -> "已有合格观察，但去重和封顶后的积分尚未达到支持级门槛。";
            default -> ("MET".equals(status) ? "原评价器判定满足条件。" : "NOT_MET".equals(status) ? "原评价器判定未满足条件。" : "现有材料或资格不足，保留未评价状态。") + "（" + reason + "）";
        };
    }
}
