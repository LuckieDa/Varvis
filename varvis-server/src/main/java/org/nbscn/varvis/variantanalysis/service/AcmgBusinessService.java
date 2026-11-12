package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.persistence.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Synchronous bounded, explicitly submitted slice. Durable claim precedes any resource call. */
public final class AcmgBusinessService {
    private boolean unifiedLocal;
    @org.springframework.lang.Nullable private Supplier<ExplicitCaseInputCoordinator> localSelectedRuntime;
    @org.springframework.lang.Nullable private Supplier<LocalEvidenceBatchRuntime> localBatchFactory;
    @org.springframework.lang.Nullable private AcmgBusinessService localBatchBase;
    @org.springframework.lang.Nullable private ObjectNode localBatchBinding;
    @org.springframework.lang.Nullable private LocalEvidenceBatchRuntime taskStatusRuntime;
    @org.springframework.lang.Nullable private java.util.function.BiFunction<java.util.function.Function<JsonNode, JsonNode>, String, LocalEvidenceBatchRuntime> localBatchWithEffects;
    public static AcmgBusinessService localBatch(AcmgBusinessService base, Supplier<LocalEvidenceBatchRuntime> factory) {
        return new AcmgBusinessService(base, factory, 1);
    }
    public static AcmgBusinessService localBatch(AcmgBusinessService base,
            java.util.function.Function<java.util.function.Function<JsonNode, JsonNode>, LocalEvidenceBatchRuntime> factory,
            java.nio.file.Path effectReviewRoot) {
        var service = localBatch(base, () -> factory.apply(plan -> base.mapper.createObjectNode()
                .put("status", "UNAVAILABLE").put("reasonCode", "SAVED_EFFECT_RESTRICTIONS_UNAVAILABLE")));
        service.localBatchWithEffects = (effects, identity) -> factory.apply(effects);
        service.predictionEffects = new PredictionEffectReferences(service.mapper, service.store, effectReviewRoot, service::read);
        return service;
    }
    public static AcmgBusinessService localBatchWithEffectIdentity(AcmgBusinessService base,
            java.util.function.BiFunction<java.util.function.Function<JsonNode, JsonNode>, String, LocalEvidenceBatchRuntime> factory,
            java.nio.file.Path effectReviewRoot) {
        var service = localBatch(base, () -> factory.apply(plan -> base.mapper.createObjectNode()
                .put("status", "UNAVAILABLE").put("reasonCode", "SAVED_EFFECT_RESTRICTIONS_UNAVAILABLE"), null));
        service.localBatchWithEffects = factory;
        service.predictionEffects = new PredictionEffectReferences(service.mapper, service.store, effectReviewRoot, service::read);
        return service;
    }
    public static AcmgBusinessService unifiedLocal(AcmgBusinessService population,
            java.util.function.Function<java.util.function.Function<JsonNode, JsonNode>, LocalEvidenceBatchRuntime> factory,
            java.nio.file.Path effectReviewRoot) {
        var service = new AcmgBusinessService(population, () -> factory.apply(plan -> population.mapper.createObjectNode()
                .put("status", "UNAVAILABLE").put("reasonCode", "SAVED_EFFECT_RESTRICTIONS_UNAVAILABLE")), 2);
        service.localBatchWithEffects = (effects, identity) -> factory.apply(effects);
        service.predictionEffects = new PredictionEffectReferences(service.mapper, service.store, effectReviewRoot, service::read);
        return service;
    }
    /** Preserve both configured preparation chains, including local contig aliases and BS1 knowledge. */
    public static AcmgBusinessService unifiedLocal(AcmgBusinessService population, AcmgBusinessService local) {
        if (population.store != local.store || population.jobs != local.jobs || local.localBatchFactory == null)
            throw new IllegalArgumentException("UNIFIED_LOCAL_SHARED_STORE_REQUIRED");
        var service = new AcmgBusinessService(population, local.localBatchFactory, 2);
        service.localSelectedRuntime = local.runtime;
        service.localBatchWithEffects = local.localBatchWithEffects;
        service.predictionEffects = local.predictionEffects;
        return service;
    }
    private AcmgBusinessService(AcmgBusinessService base, Supplier<LocalEvidenceBatchRuntime> factory, int batch) {
        localBatchBase = base;
        unifiedLocal = batch == 2;
        mapper = base.mapper; jobs = base.jobs; store = base.store; sources = base.sources; runtime = base.runtime;
        artifacts = base.artifacts; populationOnly = true; ordinaryBs1 = unifiedLocal; bs1Runtime = unifiedLocal ? base.bs1Runtime : null;
        ba1Runtime = base.ba1Runtime; pm2Runtime = base.pm2Runtime; pvs1Runtime = base.pvs1Runtime;
        hnf1aFrequencyRuntime = base.hnf1aFrequencyRuntime; pm1Runtime = base.pm1Runtime;
        missenseRuntime = base.missenseRuntime; functionalRuntime = base.functionalRuntime;
        populationFrequency = null; reviewedRules = null; ba1RuntimeBinding = unifiedLocal ? base.ba1RuntimeBinding : null;
        localBatchFactory = Objects.requireNonNull(factory);
        var runtimeForStatus = Objects.requireNonNull(factory.get());
        taskStatusRuntime = runtimeForStatus;
        localBatchBinding = runtimeForStatus.identity();
        executionProfile = unifiedLocal ? AcmgExecutionProfile.unifiedLocal(mapper, localBatchBinding, base.executionProfile.document())
                : AcmgExecutionProfile.localBatch(mapper, localBatchBinding);
        recommendations = unifiedLocal ? base.recommendations.withUnifiedLocal(localBatchBinding)
                : base.recommendations.withLocalBatch(localBatchBinding);
    }
    /** After explicit public-source intake only: rebuild bindings, never execute or rewrite saved runs. */
    AcmgBusinessService withRefreshedLocalSources() {
        var base=localBatchBase;var factory=localBatchFactory;
        if(base==null||factory==null) throw new IllegalStateException("DENOVO_LOCAL_SOURCE_REFRESH_NOT_CONFIGURED");
        var service=new AcmgBusinessService(base,factory,unifiedLocal?2:1);
        service.localSelectedRuntime=localSelectedRuntime;service.localBatchWithEffects=localBatchWithEffects;
        service.predictionEffects=predictionEffects;
        return service;
    }
    @org.springframework.lang.Nullable private Ps2DeNovoContextPreparation ps2Preparation;
    public static AcmgBusinessService ps2Development(AcmgBusinessService base, Ps2DeNovoContextPreparation preparation) {
        return new AcmgBusinessService(base, preparation);
    }
    private AcmgBusinessService(AcmgBusinessService base, Ps2DeNovoContextPreparation preparation) {
        mapper = base.mapper; jobs = base.jobs; store = base.store; sources = base.sources; runtime = base.runtime;
        artifacts = base.artifacts; populationOnly = true; ordinaryBs1 = false; bs1Runtime = null;
        ba1Runtime = base.ba1Runtime; pm2Runtime = base.pm2Runtime; pvs1Runtime = base.pvs1Runtime;
        hnf1aFrequencyRuntime = base.hnf1aFrequencyRuntime; pm1Runtime = base.pm1Runtime;
        missenseRuntime = base.missenseRuntime; functionalRuntime = base.functionalRuntime;
        populationFrequency = null; reviewedRules = null; ba1RuntimeBinding = null; recommendations = base.recommendations;
        ps2Preparation = Objects.requireNonNull(preparation); executionProfile = AcmgExecutionProfile.ps2Development(mapper, preparation.pins());
    }
    @org.springframework.lang.Nullable private Pp4PhenotypeContextPreparation pp4Preparation;
    public static AcmgBusinessService pp4Development(AcmgBusinessService base, Pp4PhenotypeContextPreparation preparation) {
        return new AcmgBusinessService(base, preparation);
    }
    private AcmgBusinessService(AcmgBusinessService base, Pp4PhenotypeContextPreparation preparation) {
        mapper = base.mapper; jobs = base.jobs; store = base.store; sources = base.sources; runtime = base.runtime;
        artifacts = base.artifacts; populationOnly = true; ordinaryBs1 = false; bs1Runtime = null;
        ba1Runtime = base.ba1Runtime; pm2Runtime = base.pm2Runtime; pvs1Runtime = base.pvs1Runtime;
        hnf1aFrequencyRuntime = base.hnf1aFrequencyRuntime; pm1Runtime = base.pm1Runtime;
        missenseRuntime = base.missenseRuntime; functionalRuntime = base.functionalRuntime;
        populationFrequency = null; reviewedRules = null; ba1RuntimeBinding = null; recommendations = base.recommendations;
        pp4Preparation = Objects.requireNonNull(preparation); executionProfile = AcmgExecutionProfile.pp4Development(mapper, preparation.pins());
    }
    @org.springframework.lang.Nullable private Bs4NonSegregationContextPreparation bs4Preparation;
    public static AcmgBusinessService bs4Development(AcmgBusinessService base, Bs4NonSegregationContextPreparation preparation) {
        return new AcmgBusinessService(base, preparation);
    }
    private AcmgBusinessService(AcmgBusinessService base, Bs4NonSegregationContextPreparation preparation) {
        mapper = base.mapper; jobs = base.jobs; store = base.store; sources = base.sources; runtime = base.runtime;
        artifacts = base.artifacts; populationOnly = true; ordinaryBs1 = false; bs1Runtime = null;
        ba1Runtime = base.ba1Runtime; pm2Runtime = base.pm2Runtime; pvs1Runtime = base.pvs1Runtime;
        hnf1aFrequencyRuntime = base.hnf1aFrequencyRuntime; pm1Runtime = base.pm1Runtime;
        missenseRuntime = base.missenseRuntime; functionalRuntime = base.functionalRuntime;
        populationFrequency = null; reviewedRules = null; ba1RuntimeBinding = null; recommendations = base.recommendations;
        bs4Preparation = Objects.requireNonNull(preparation); executionProfile = AcmgExecutionProfile.bs4Development(mapper, preparation.pins());
    }
    @org.springframework.lang.Nullable private Pp1SegregationContextPreparation pp1Preparation;
    public static AcmgBusinessService pp1Development(AcmgBusinessService base, Pp1SegregationContextPreparation preparation) {
        return new AcmgBusinessService(base, preparation);
    }
    private AcmgBusinessService(AcmgBusinessService base, Pp1SegregationContextPreparation preparation) {
        mapper = base.mapper; jobs = base.jobs; store = base.store; sources = base.sources; runtime = base.runtime;
        artifacts = base.artifacts; populationOnly = true; ordinaryBs1 = false; bs1Runtime = null;
        ba1Runtime = base.ba1Runtime; pm2Runtime = base.pm2Runtime; pvs1Runtime = base.pvs1Runtime;
        hnf1aFrequencyRuntime = base.hnf1aFrequencyRuntime; pm1Runtime = base.pm1Runtime;
        missenseRuntime = base.missenseRuntime; functionalRuntime = base.functionalRuntime;
        populationFrequency = null; reviewedRules = null; ba1RuntimeBinding = null; recommendations = base.recommendations;
        pp1Preparation = Objects.requireNonNull(preparation); executionProfile = AcmgExecutionProfile.pp1Development(mapper, preparation.pins());
    }
    @org.springframework.lang.Nullable private Bp2PhaseContextPreparation bp2Preparation;
    public static AcmgBusinessService bp2Development(AcmgBusinessService base, Bp2PhaseContextPreparation preparation) {
        return new AcmgBusinessService(base, preparation);
    }
    private AcmgBusinessService(AcmgBusinessService base, Bp2PhaseContextPreparation preparation) {
        mapper = base.mapper; jobs = base.jobs; store = base.store; sources = base.sources; runtime = base.runtime;
        artifacts = base.artifacts; populationOnly = true; ordinaryBs1 = false; bs1Runtime = null;
        ba1Runtime = base.ba1Runtime; pm2Runtime = base.pm2Runtime; pvs1Runtime = base.pvs1Runtime;
        hnf1aFrequencyRuntime = base.hnf1aFrequencyRuntime; pm1Runtime = base.pm1Runtime;
        missenseRuntime = base.missenseRuntime; functionalRuntime = base.functionalRuntime;
        populationFrequency = null; reviewedRules = null; ba1RuntimeBinding = null; recommendations = base.recommendations;
        bp2Preparation = Objects.requireNonNull(preparation); executionProfile = AcmgExecutionProfile.bp2Development(mapper, preparation.pins());
    }
    @org.springframework.lang.Nullable private Pm3InTransContextPreparation pm3Preparation;
    public static AcmgBusinessService pm3Development(AcmgBusinessService base, Pm3InTransContextPreparation preparation) {
        return new AcmgBusinessService(base, preparation);
    }
    private AcmgBusinessService(AcmgBusinessService base, Pm3InTransContextPreparation preparation) {
        mapper = base.mapper; jobs = base.jobs; store = base.store; sources = base.sources; runtime = base.runtime;
        artifacts = base.artifacts; populationOnly = true; ordinaryBs1 = false; bs1Runtime = null;
        ba1Runtime = base.ba1Runtime; pm2Runtime = base.pm2Runtime; pvs1Runtime = base.pvs1Runtime;
        hnf1aFrequencyRuntime = base.hnf1aFrequencyRuntime; pm1Runtime = base.pm1Runtime;
        missenseRuntime = base.missenseRuntime; functionalRuntime = base.functionalRuntime;
        populationFrequency = null; reviewedRules = null; ba1RuntimeBinding = null; recommendations = base.recommendations;
        pm3Preparation = Objects.requireNonNull(preparation); executionProfile = AcmgExecutionProfile.pm3Development(mapper, preparation.pins());
    }
    @org.springframework.lang.Nullable private Pm6DeNovoContextPreparation pm6Preparation;
    public static AcmgBusinessService pm6Development(AcmgBusinessService base, Pm6DeNovoContextPreparation preparation) {
        return new AcmgBusinessService(base, preparation);
    }
    private AcmgBusinessService(AcmgBusinessService base, Pm6DeNovoContextPreparation preparation) {
        mapper = base.mapper; jobs = base.jobs; store = base.store; sources = base.sources; runtime = base.runtime;
        artifacts = base.artifacts; populationOnly = true; ordinaryBs1 = false; bs1Runtime = null;
        ba1Runtime = base.ba1Runtime; pm2Runtime = base.pm2Runtime; pvs1Runtime = base.pvs1Runtime;
        hnf1aFrequencyRuntime = base.hnf1aFrequencyRuntime; pm1Runtime = base.pm1Runtime;
        missenseRuntime = base.missenseRuntime; functionalRuntime = base.functionalRuntime;
        populationFrequency = null; reviewedRules = null; ba1RuntimeBinding = null; recommendations = base.recommendations;
        pm6Preparation = Objects.requireNonNull(preparation); executionProfile = AcmgExecutionProfile.pm6Development(mapper, preparation.pins());
    }
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AcmgBusinessService.class);
    private final ObjectMapper mapper;
    private final VariantAnalysisJobRepository jobs;
    private final AcmgBusinessStore store;
    private final AcmgExecutionSources sources;
    private final Supplier<ExplicitCaseInputCoordinator> runtime;
    private final AcmgBusinessArtifacts artifacts;
    private final AcmgExecutionProfile executionProfile;
    private final boolean populationOnly;
    private final boolean ordinaryBs1;
    @org.springframework.lang.Nullable private final Supplier<Bs1Runtime> bs1Runtime;
    @org.springframework.lang.Nullable private Supplier<PredictionDevelopmentRuntime> predictionRuntime;
    @org.springframework.lang.Nullable private SavedPredictionAssociation savedAssociation;
    @org.springframework.lang.Nullable private RevelApplicationBusiness revelApplication;
    @org.springframework.lang.Nullable private PredictionEffectReferences revelEffects;

    public static AcmgBusinessService bp1Development(AcmgBusinessService base, ReviewedRuleBundle bundle) {
        if (!bundle.supportsBp1()) throw new IllegalArgumentException("BP1_TRUSTED_BUNDLE_REQUIRED");
        return new AcmgBusinessService(base, bundle);
    }
    private boolean generalBp1() {
        var rules = reviewedRules;
        return rules != null && rules.supportsBp1();
    }
    public static AcmgBusinessService pp2Development(AcmgBusinessService base, ReviewedRuleBundle bundle) {
        if (!bundle.supportsPp2()) throw new IllegalArgumentException("PP2_TRUSTED_BUNDLE_REQUIRED");
        return new AcmgBusinessService(base, bundle);
    }
    private boolean generalPp2() {
        var rules = reviewedRules;
        return rules != null && rules.supportsPp2();
    }
    public static AcmgBusinessService bp3Development(AcmgBusinessService base, ReviewedRuleBundle bundle) {
        if (!bundle.supportsBp3()) throw new IllegalArgumentException("BP3_TRUSTED_BUNDLE_REQUIRED");
        return new AcmgBusinessService(base, bundle);
    }
    private boolean generalBp3() {
        var rules = reviewedRules;
        return rules != null && rules.supportsBp3();
    }
    public static AcmgBusinessService pvs1Development(AcmgBusinessService base, ReviewedRuleBundle bundle) {
        if (!bundle.supportsPvs1Only()) throw new IllegalArgumentException("PVS1_TRUSTED_BUNDLE_REQUIRED");
        return new AcmgBusinessService(base, bundle);
    }
    private boolean generalPvs1() {
        var rules = reviewedRules;
        return rules != null && rules.supportsPvs1Only();
    }
    public static AcmgBusinessService pm1Development(AcmgBusinessService base, ReviewedRuleBundle bundle) {
        if (!bundle.supportsPm1()) throw new IllegalArgumentException("PM1_TRUSTED_BUNDLE_REQUIRED");
        return new AcmgBusinessService(base, bundle);
    }
    private boolean generalPm1() {
        var rules = reviewedRules;
        return rules != null && rules.supportsPm1();
    }
    public static AcmgBusinessService ps1Development(AcmgBusinessService base, ReviewedRuleBundle bundle) {
        if (!bundle.supportsPs1()) throw new IllegalArgumentException("PS1_TRUSTED_BUNDLE_REQUIRED");
        return new AcmgBusinessService(base, bundle);
    }
    public static AcmgBusinessService pm5Development(AcmgBusinessService base, ReviewedRuleBundle bundle) {
        if (!bundle.supportsPm5()) throw new IllegalArgumentException("PM5_TRUSTED_BUNDLE_REQUIRED");
        return new AcmgBusinessService(base, bundle);
    }
    private boolean generalMissense() {
        var rules = reviewedRules;
        return rules != null && (rules.supportsPs1() || rules.supportsPm5());
    }
    private String missenseCode() { return enabled("PM5") ? "PM5" : "PS1"; }
    private String missenseDeclaration() { return enabled("PM5") ? AcmgBusinessRuleScope.PM5_DECLARATION : AcmgBusinessRuleScope.PS1_DECLARATION; }
    public static AcmgBusinessService pm4Development(AcmgBusinessService base, ReviewedRuleBundle bundle) {
        if (!bundle.supportsPm4()) throw new IllegalArgumentException("PM4_TRUSTED_BUNDLE_REQUIRED");
        return new AcmgBusinessService(base, bundle);
    }
    private AcmgBusinessService(AcmgBusinessService base, ReviewedRuleBundle bundle) {
        mapper = base.mapper; jobs = base.jobs; store = base.store; sources = base.sources; runtime = base.runtime;
        artifacts = base.artifacts; populationOnly = true; ordinaryBs1 = false; bs1Runtime = null;
        ba1Runtime = base.ba1Runtime; pm2Runtime = base.pm2Runtime; pvs1Runtime = base.pvs1Runtime;
        hnf1aFrequencyRuntime = base.hnf1aFrequencyRuntime; pm1Runtime = base.pm1Runtime;
        missenseRuntime = base.missenseRuntime; functionalRuntime = base.functionalRuntime;
        populationFrequency = null; reviewedRules = Objects.requireNonNull(bundle); ba1RuntimeBinding = null;
        executionProfile = bundle.supportsBp1() ? AcmgExecutionProfile.bp1Development(mapper, bundle) : bundle.supportsPp2() ? AcmgExecutionProfile.pp2Development(mapper, bundle) : bundle.supportsBp3() ? AcmgExecutionProfile.bp3Development(mapper, bundle) : bundle.supportsPvs1Only() ? AcmgExecutionProfile.pvs1Development(mapper, bundle) : bundle.supportsPm1() ? AcmgExecutionProfile.pm1Development(mapper, bundle) : bundle.supportsPm5() ? AcmgExecutionProfile.pm5Development(mapper, bundle) : bundle.supportsPs1() ? AcmgExecutionProfile.ps1Development(mapper, bundle) : AcmgExecutionProfile.pm4Development(mapper, bundle);
        recommendations = generalBp1() ? base.recommendations.withBp1(bundle) : generalPp2() ? base.recommendations.withPp2(bundle) : generalBp3() ? base.recommendations.withBp3(bundle) : generalPvs1() ? base.recommendations.withPvs1(bundle) : generalPm1() ? base.recommendations.withPm1(bundle) : generalMissense() ? base.recommendations.withMissense(bundle) : base.recommendations.withPm4(bundle);
    }

    public static AcmgBusinessService revelApplication(AcmgBusinessService base, RevelApplicationBusiness application, java.nio.file.Path reviewRoot) {
        var service = new AcmgBusinessService(base, application);
        service.revelEffects = new PredictionEffectReferences(service.mapper, service.store, reviewRoot, service::read);
        return service;
    }
    private AcmgBusinessService(AcmgBusinessService base, RevelApplicationBusiness application) {
        mapper = base.mapper; jobs = base.jobs; store = base.store; sources = base.sources; runtime = base.runtime;
        artifacts = base.artifacts; populationOnly = true; ordinaryBs1 = false; bs1Runtime = null;
        ba1Runtime = base.ba1Runtime; pm2Runtime = base.pm2Runtime; pvs1Runtime = base.pvs1Runtime;
        hnf1aFrequencyRuntime = base.hnf1aFrequencyRuntime; pm1Runtime = base.pm1Runtime;
        missenseRuntime = base.missenseRuntime; functionalRuntime = base.functionalRuntime;
        populationFrequency = base.populationFrequency; reviewedRules = base.reviewedRules; ba1RuntimeBinding = base.ba1RuntimeBinding;
        recommendations = base.recommendations.withPrediction(application.contextBinding(), RevelApplicationBusiness.DECLARATION); revelApplication = application;
        executionProfile = AcmgExecutionProfile.revelApplication(mapper, application.preparationIdentity());
    }

    @org.springframework.lang.Nullable private PredictionEffectReferences predictionEffects;
    public static AcmgBusinessService predictionDevelopment(AcmgBusinessService base, Supplier<PredictionDevelopmentRuntime> prediction,
                                                            java.nio.file.Path reviewRoot) {
        var service = new AcmgBusinessService(base, prediction);
        service.predictionEffects = new PredictionEffectReferences(service.mapper, service.store, reviewRoot, service::read);
        return service;
    }
    public static AcmgBusinessService predictionDevelopment(AcmgBusinessService base, Supplier<PredictionDevelopmentRuntime> prediction) {
        return new AcmgBusinessService(base, prediction);
    }
    private AcmgBusinessService(AcmgBusinessService base, Supplier<PredictionDevelopmentRuntime> prediction) {
        this(base, prediction, PredictionMode.ACQUIRE);
    }
    public static AcmgBusinessService savedPredictionAssociation(AcmgBusinessService base, Supplier<PredictionDevelopmentRuntime> identity) {
        return new AcmgBusinessService(base, identity, PredictionMode.ASSOCIATE_SAVED);
    }
    @org.springframework.lang.Nullable private SavedCalibrationBusiness savedCalibration;
    public static AcmgBusinessService savedPredictionCalibration(AcmgBusinessService base, SavedCalibrationBusiness calibration) {
        return new AcmgBusinessService(base, calibration);
    }
    private AcmgBusinessService(AcmgBusinessService base, SavedCalibrationBusiness calibration) {
        mapper = base.mapper; jobs = base.jobs; store = base.store; sources = base.sources; runtime = base.runtime;
        artifacts = base.artifacts; populationOnly = true; ordinaryBs1 = false; bs1Runtime = null;
        ba1Runtime = base.ba1Runtime; pm2Runtime = base.pm2Runtime; pvs1Runtime = base.pvs1Runtime;
        hnf1aFrequencyRuntime = base.hnf1aFrequencyRuntime; pm1Runtime = base.pm1Runtime;
        missenseRuntime = base.missenseRuntime; functionalRuntime = base.functionalRuntime;
        populationFrequency = base.populationFrequency; reviewedRules = base.reviewedRules; ba1RuntimeBinding = base.ba1RuntimeBinding;
        savedCalibration = calibration; recommendations = base.recommendations;
        executionProfile = AcmgExecutionProfile.savedPredictionCalibration(mapper, calibration.identity());
    }
    private enum PredictionMode { ACQUIRE, ASSOCIATE_SAVED }
    private AcmgBusinessService(AcmgBusinessService base, Supplier<PredictionDevelopmentRuntime> prediction, PredictionMode mode) {
        boolean associationOnly = mode == PredictionMode.ASSOCIATE_SAVED;
        mapper = base.mapper; jobs = base.jobs; store = base.store; sources = base.sources; runtime = base.runtime;
        artifacts = base.artifacts; populationOnly = true; ordinaryBs1 = false; bs1Runtime = null; predictionRuntime = prediction;
        ba1Runtime = base.ba1Runtime; pm2Runtime = base.pm2Runtime; pvs1Runtime = base.pvs1Runtime;
        hnf1aFrequencyRuntime = base.hnf1aFrequencyRuntime; pm1Runtime = base.pm1Runtime;
        missenseRuntime = base.missenseRuntime; functionalRuntime = base.functionalRuntime;
        populationFrequency = base.populationFrequency; reviewedRules = base.reviewedRules; ba1RuntimeBinding = base.ba1RuntimeBinding;
        var configured = prediction.get();
        var association = associationOnly ? new SavedPredictionAssociation(mapper, configured.associationResource()) : null;
        savedAssociation = association;
        var binding = association == null ? configured.identity() : association.identity();
        recommendations = base.recommendations.withPrediction(binding,
                association == null ? PredictionDevelopmentRuntime.DECLARATION : SavedPredictionAssociation.DECLARATION);
        executionProfile = association == null ? AcmgExecutionProfile.predictionDevelopment(mapper, binding)
                : AcmgExecutionProfile.savedPredictionAssociation(mapper, binding);
        if (associationOnly) predictionRuntime = null; // No acquisition runtime is retained by this service.
        log.info("ACMG_ASSEMBLY profile={}/{} formalEnabled=false criteria={} manifest={}",
                executionProfile.document().path("profileId").asText(), executionProfile.document().path("profileVersion").asText(),
                executionProfile.criteria(), executionProfile.document().path("manifestSha256").asText());
    }

    /** Fixed server-side acceptance scope; caller requests cannot expand it. */
    public static AcmgBusinessService bs1Acceptance(AcmgBusinessService base, Supplier<Bs1Runtime> bs1) {
        return new AcmgBusinessService(base, bs1, false);
    }
    public static AcmgBusinessService formalPopulation(AcmgBusinessService base, Supplier<Bs1Runtime> bs1) {
        return new AcmgBusinessService(base, bs1, true);
    }
    private AcmgBusinessService(AcmgBusinessService base, Supplier<Bs1Runtime> bs1, boolean formal) {
        mapper = base.mapper; jobs = base.jobs; store = base.store; sources = base.sources; runtime = base.runtime;
        artifacts = base.artifacts; populationOnly = true; ordinaryBs1 = true; bs1Runtime = bs1;
        ba1Runtime = base.ba1Runtime; pm2Runtime = base.pm2Runtime; pvs1Runtime = base.pvs1Runtime;
        hnf1aFrequencyRuntime = base.hnf1aFrequencyRuntime; pm1Runtime = base.pm1Runtime;
        missenseRuntime = base.missenseRuntime; functionalRuntime = base.functionalRuntime;
        populationFrequency = base.populationFrequency; reviewedRules = base.reviewedRules; ba1RuntimeBinding = base.ba1RuntimeBinding;
        var snapshot = bs1.get(); recommendations = base.recommendations.withBs1(snapshot, formal);
        executionProfile = formal ? AcmgExecutionProfile.current(mapper, ba1RuntimeBinding, snapshot.identity())
                : AcmgExecutionProfile.bs1Acceptance(mapper, ba1RuntimeBinding, snapshot.identity());
        var profile = executionProfile.document();
        log.info("ACMG_ASSEMBLY profile={}/{} formalEnabled={} criteria={} manifest={} bs1Policies={}",
                profile.path("profileId").asText(), profile.path("profileVersion").asText(), profile.path("formalEnabled").asBoolean(),
                executionProfile.criteria(), profile.path("manifestSha256").asText(), snapshot.identity().path("policiesSha256").asText());
    }
    /** Internal opt-in only. Never accepts a client rule list, path or policy object. */
    public static AcmgBusinessService bs1Development(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
            AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> contexts, Supplier<Bs1Runtime> bs1) {
        return new AcmgBusinessService(mapper, jobs, store, sources, contexts, () -> { throw new IllegalStateException("BA1_DISABLED"); },
                AcmgRecommendations.current(mapper, null), () -> { throw new IllegalStateException("PM2_DISABLED"); },
                () -> { throw new IllegalStateException("PVS1_DISABLED"); }, () -> { throw new IllegalStateException("PS1_DISABLED"); }, null,
                () -> { throw new IllegalStateException("PS3_DISABLED"); }, null, null, null, null, null,
                () -> { throw new IllegalStateException("PM1_DISABLED"); }, true, bs1);
    }
    private final Supplier<Ba1Runtime> ba1Runtime;
    private final Supplier<Pm2SupportingRuntime> pm2Runtime;
    private final Supplier<Hnf1aPvs1Runtime> pvs1Runtime;
    private final Supplier<Hnf1aPopulationRuntime> hnf1aFrequencyRuntime;
    private final Supplier<Hnf1aPm1Runtime> pm1Runtime;
    private final AcmgRecommendations recommendations;
    private final Supplier<ClinVarMissenseBusinessRuntime> missenseRuntime;
    private final Supplier<Hnf1aFunctionalRuntime> functionalRuntime;
    @org.springframework.lang.Nullable private final PopulationFrequencyBundle populationFrequency;
    @org.springframework.lang.Nullable private final ReviewedRuleBundle reviewedRules;
    @org.springframework.lang.Nullable private final JsonNode ba1RuntimeBinding;
    public record Ba1Runtime(NativeSnvSourceProducer producer, Ba1AlleleRuntime evaluator) { }
    public AcmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
                                AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> runtime) {
        this(mapper, jobs, store, sources, runtime, () -> { throw new IllegalArgumentException("BA1_RUNTIME_NOT_CONFIGURED"); });
    }
    public AcmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
                                AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> runtime, Supplier<Ba1Runtime> ba1Runtime) {
        this(mapper, jobs, store, sources, runtime, ba1Runtime, AcmgRecommendations.unavailable(mapper));
    }
    public AcmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
                                AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> runtime, Supplier<Ba1Runtime> ba1Runtime,
                                AcmgRecommendations recommendations) {
        this(mapper, jobs, store, sources, runtime, ba1Runtime, recommendations,
                () -> { throw new IllegalArgumentException("PM2_RUNTIME_NOT_CONFIGURED"); });
    }
    public AcmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
                                AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> runtime, Supplier<Ba1Runtime> ba1Runtime,
                                AcmgRecommendations recommendations, Supplier<Pm2SupportingRuntime> pm2Runtime) {
        this(mapper, jobs, store, sources, runtime, ba1Runtime, recommendations, pm2Runtime,
                () -> { throw new IllegalArgumentException("PVS1_RUNTIME_NOT_CONFIGURED"); });
    }
    public AcmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
                                AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> runtime, Supplier<Ba1Runtime> ba1Runtime,
                                AcmgRecommendations recommendations, Supplier<Pm2SupportingRuntime> pm2Runtime,
                                Supplier<Hnf1aPvs1Runtime> pvs1Runtime) {
        this(mapper, jobs, store, sources, runtime, ba1Runtime, recommendations, pm2Runtime, pvs1Runtime,
                () -> { throw new IllegalArgumentException("PS1_PM5_RUNTIME_NOT_CONFIGURED"); }, null);
    }
    public AcmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
                                AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> runtime, Supplier<Ba1Runtime> ba1Runtime,
                                AcmgRecommendations recommendations, Supplier<ClinVarMissenseBusinessRuntime> missenseRuntime, JsonNode runtimeBinding) {
        this(mapper, jobs, store, sources, runtime, ba1Runtime, recommendations,
                () -> { throw new IllegalArgumentException("PM2_RUNTIME_NOT_CONFIGURED"); },
                () -> { throw new IllegalArgumentException("PVS1_RUNTIME_NOT_CONFIGURED"); }, missenseRuntime, runtimeBinding);
    }
    public AcmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
                                AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> runtime, Supplier<Ba1Runtime> ba1Runtime,
                                AcmgRecommendations recommendations, Supplier<Pm2SupportingRuntime> pm2Runtime,
                                Supplier<Hnf1aPvs1Runtime> pvs1Runtime, Supplier<ClinVarMissenseBusinessRuntime> missenseRuntime, JsonNode runtimeBinding) {
        this(mapper, jobs, store, sources, runtime, ba1Runtime, recommendations, pm2Runtime, pvs1Runtime, missenseRuntime,
                runtimeBinding, () -> new Hnf1aFunctionalRuntime(mapper));
    }
    public AcmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
                                AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> runtime, Supplier<Ba1Runtime> ba1Runtime,
                                AcmgRecommendations recommendations, Supplier<Pm2SupportingRuntime> pm2Runtime,
                                Supplier<Hnf1aPvs1Runtime> pvs1Runtime, Supplier<ClinVarMissenseBusinessRuntime> missenseRuntime, JsonNode runtimeBinding,
                                Supplier<Hnf1aFunctionalRuntime> functionalRuntime) {
        this(mapper, jobs, store, sources, runtime, ba1Runtime, recommendations, pm2Runtime, pvs1Runtime,
                missenseRuntime, runtimeBinding, functionalRuntime, null);
    }
    public AcmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
                                AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> runtime, Supplier<Ba1Runtime> ba1Runtime,
                                AcmgRecommendations recommendations, Supplier<Pm2SupportingRuntime> pm2Runtime,
                                Supplier<Hnf1aPvs1Runtime> pvs1Runtime, Supplier<ClinVarMissenseBusinessRuntime> missenseRuntime, JsonNode runtimeBinding,
                                Supplier<Hnf1aFunctionalRuntime> functionalRuntime,
                                @org.springframework.lang.Nullable PopulationFrequencyBundle populationFrequency) {
        this(mapper, jobs, store, sources, runtime, ba1Runtime, recommendations, pm2Runtime, pvs1Runtime,
                missenseRuntime, runtimeBinding, functionalRuntime, populationFrequency, null);
    }
    public AcmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
                                AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> runtime, Supplier<Ba1Runtime> ba1Runtime,
                                AcmgRecommendations recommendations, Supplier<Pm2SupportingRuntime> pm2Runtime,
                                Supplier<Hnf1aPvs1Runtime> pvs1Runtime, Supplier<ClinVarMissenseBusinessRuntime> missenseRuntime, JsonNode runtimeBinding,
                                Supplier<Hnf1aFunctionalRuntime> functionalRuntime,
                                @org.springframework.lang.Nullable PopulationFrequencyBundle populationFrequency,
                                @org.springframework.lang.Nullable ReviewedRuleBundle reviewedRules) {
        this(mapper, jobs, store, sources, runtime, ba1Runtime, recommendations, pm2Runtime, pvs1Runtime,
                missenseRuntime, runtimeBinding, functionalRuntime, populationFrequency, reviewedRules, null);
    }
    public AcmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
                                AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> runtime, Supplier<Ba1Runtime> ba1Runtime,
                                AcmgRecommendations recommendations, Supplier<Pm2SupportingRuntime> pm2Runtime,
                                Supplier<Hnf1aPvs1Runtime> pvs1Runtime, Supplier<ClinVarMissenseBusinessRuntime> missenseRuntime, JsonNode runtimeBinding,
                                Supplier<Hnf1aFunctionalRuntime> functionalRuntime,
                                @org.springframework.lang.Nullable PopulationFrequencyBundle populationFrequency,
                                @org.springframework.lang.Nullable ReviewedRuleBundle reviewedRules,
                                @org.springframework.lang.Nullable ReviewedRuleContextPreparation ruleContexts) {
        this(mapper, jobs, store, sources, runtime, ba1Runtime, recommendations, pm2Runtime, pvs1Runtime,
                missenseRuntime, runtimeBinding, functionalRuntime, populationFrequency, reviewedRules, ruleContexts, null);
    }
    public AcmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
            AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> runtime, Supplier<Ba1Runtime> ba1Runtime,
            AcmgRecommendations recommendations, Supplier<Pm2SupportingRuntime> pm2Runtime, Supplier<Hnf1aPvs1Runtime> pvs1Runtime,
            Supplier<ClinVarMissenseBusinessRuntime> missenseRuntime, JsonNode runtimeBinding, Supplier<Hnf1aFunctionalRuntime> functionalRuntime,
            @org.springframework.lang.Nullable PopulationFrequencyBundle populationFrequency,
            @org.springframework.lang.Nullable ReviewedRuleBundle reviewedRules,
            @org.springframework.lang.Nullable ReviewedRuleContextPreparation ruleContexts,
            @org.springframework.lang.Nullable ReviewedPredictionBusinessRuntime prediction) {
        this(mapper, jobs, store, sources, runtime, ba1Runtime, recommendations, pm2Runtime, pvs1Runtime,
                missenseRuntime, runtimeBinding, functionalRuntime, populationFrequency, reviewedRules, ruleContexts,
                prediction, null);
    }
    public AcmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
            AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> runtime, Supplier<Ba1Runtime> ba1Runtime,
            AcmgRecommendations recommendations, Supplier<Pm2SupportingRuntime> pm2Runtime, Supplier<Hnf1aPvs1Runtime> pvs1Runtime,
            Supplier<ClinVarMissenseBusinessRuntime> missenseRuntime, JsonNode runtimeBinding, Supplier<Hnf1aFunctionalRuntime> functionalRuntime,
            @org.springframework.lang.Nullable PopulationFrequencyBundle populationFrequency,
            @org.springframework.lang.Nullable ReviewedRuleBundle reviewedRules,
            @org.springframework.lang.Nullable ReviewedRuleContextPreparation ruleContexts,
            @org.springframework.lang.Nullable ReviewedPredictionBusinessRuntime prediction,
            @org.springframework.lang.Nullable JsonNode ba1RuntimeBinding) {
        this(mapper, jobs, store, sources, runtime, ba1Runtime, recommendations, pm2Runtime, pvs1Runtime,
                missenseRuntime, runtimeBinding, functionalRuntime, populationFrequency, reviewedRules, ruleContexts,
                prediction, ba1RuntimeBinding, () -> new Hnf1aPm1Runtime(mapper));
    }
    public AcmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
            AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> runtime, Supplier<Ba1Runtime> ba1Runtime,
            AcmgRecommendations recommendations, Supplier<Pm2SupportingRuntime> pm2Runtime, Supplier<Hnf1aPvs1Runtime> pvs1Runtime,
            Supplier<ClinVarMissenseBusinessRuntime> missenseRuntime, JsonNode runtimeBinding, Supplier<Hnf1aFunctionalRuntime> functionalRuntime,
            @org.springframework.lang.Nullable PopulationFrequencyBundle populationFrequency,
            @org.springframework.lang.Nullable ReviewedRuleBundle reviewedRules,
            @org.springframework.lang.Nullable ReviewedRuleContextPreparation ruleContexts,
            @org.springframework.lang.Nullable ReviewedPredictionBusinessRuntime prediction,
            @org.springframework.lang.Nullable JsonNode ba1RuntimeBinding, Supplier<Hnf1aPm1Runtime> pm1Runtime) {
        this(mapper, jobs, store, sources, runtime, ba1Runtime, recommendations, pm2Runtime, pvs1Runtime,
                missenseRuntime, runtimeBinding, functionalRuntime, populationFrequency, reviewedRules, ruleContexts,
                prediction, ba1RuntimeBinding, pm1Runtime, false);
    }

    /** The production configuration explicitly selects the current scope; old constructors support isolated legacy tests. */
    public AcmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
            AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> runtime, Supplier<Ba1Runtime> ba1Runtime,
            AcmgRecommendations recommendations, Supplier<Pm2SupportingRuntime> pm2Runtime, Supplier<Hnf1aPvs1Runtime> pvs1Runtime,
            Supplier<ClinVarMissenseBusinessRuntime> missenseRuntime, JsonNode runtimeBinding, Supplier<Hnf1aFunctionalRuntime> functionalRuntime,
            @org.springframework.lang.Nullable PopulationFrequencyBundle populationFrequency,
            @org.springframework.lang.Nullable ReviewedRuleBundle reviewedRules,
            @org.springframework.lang.Nullable ReviewedRuleContextPreparation ruleContexts,
            @org.springframework.lang.Nullable ReviewedPredictionBusinessRuntime prediction,
            @org.springframework.lang.Nullable JsonNode ba1RuntimeBinding, Supplier<Hnf1aPm1Runtime> pm1Runtime, boolean currentScope) {
        this(mapper, jobs, store, sources, runtime, ba1Runtime, recommendations, pm2Runtime, pvs1Runtime, missenseRuntime,
                runtimeBinding, functionalRuntime, populationFrequency, reviewedRules, ruleContexts, prediction, ba1RuntimeBinding, pm1Runtime, currentScope, null);
    }

    private AcmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs, AcmgBusinessStore store,
            AcmgExecutionSources sources, Supplier<ExplicitCaseInputCoordinator> runtime, Supplier<Ba1Runtime> ba1Runtime,
            AcmgRecommendations recommendations, Supplier<Pm2SupportingRuntime> pm2Runtime, Supplier<Hnf1aPvs1Runtime> pvs1Runtime,
            Supplier<ClinVarMissenseBusinessRuntime> missenseRuntime, JsonNode runtimeBinding, Supplier<Hnf1aFunctionalRuntime> functionalRuntime,
            @org.springframework.lang.Nullable PopulationFrequencyBundle populationFrequency,
            @org.springframework.lang.Nullable ReviewedRuleBundle reviewedRules,
            @org.springframework.lang.Nullable ReviewedRuleContextPreparation ruleContexts,
            @org.springframework.lang.Nullable ReviewedPredictionBusinessRuntime prediction,
            @org.springframework.lang.Nullable JsonNode ba1RuntimeBinding, Supplier<Hnf1aPm1Runtime> pm1Runtime, boolean currentScope,
            @org.springframework.lang.Nullable Supplier<Bs1Runtime> bs1Runtime) {
        this.ordinaryBs1 = false;
        this.bs1Runtime = bs1Runtime;
        this.populationOnly = currentScope;
        this.pm1Runtime = Objects.requireNonNull(pm1Runtime);
        this.populationFrequency = populationFrequency;
        this.reviewedRules = reviewedRules;
        this.ba1RuntimeBinding = ba1RuntimeBinding == null ? null : ba1RuntimeBinding.deepCopy();
        this.functionalRuntime = Objects.requireNonNull(functionalRuntime);
        this.pvs1Runtime = Objects.requireNonNull(pvs1Runtime);
        this.pm2Runtime = Objects.requireNonNull(pm2Runtime);
        this.missenseRuntime = Objects.requireNonNull(missenseRuntime);
        this.recommendations = recommendations;
        this.executionProfile = bs1Runtime != null ? AcmgExecutionProfile.bs1Development(mapper, bs1Runtime.get().identity())
                : currentScope ? AcmgExecutionProfile.legacyTwoRule(mapper, this.ba1RuntimeBinding) : new AcmgExecutionProfile(mapper, runtimeBinding,
                populationFrequency == null ? null : populationFrequency.identity(),
                reviewedRules == null ? null : reviewedRules.identity(),
                ruleContexts == null ? null : ruleContexts.identity(),
                prediction == null ? null : prediction.identity(), prediction == null ? null : prediction.contexts().identity(),
                this.ba1RuntimeBinding).withPm2VerifiedConversion(mapper); this.ba1Runtime = ba1Runtime;
        this.mapper = mapper.copy().findAndRegisterModules(); this.jobs = jobs; this.store = store; this.sources = sources;
        this.hnf1aFrequencyRuntime = () -> new Hnf1aPopulationRuntime(this.mapper);
        this.runtime = runtime; this.artifacts = new AcmgBusinessArtifacts(this.mapper);
    }
    List<String> configuredCriteria() { return executionProfile.criteria(); }

    /** Runtime wiring only: no job lookup, material preparation, provider call or scoring. */
    public ObjectNode configurationSummary() {
        var result = mapper.createObjectNode().put("schemaVersion", "acmg-configuration-summary-v1")
                .put("workflow", AcmgBusinessWorkflows.CURRENT)
                .put("profileId", executionProfile.document().path("profileId").asText())
                .put("criterionCount", executionProfile.criteria().size())
                .put("resourceReadiness", "CHECKED_PER_ANALYSIS")
                .put("scientificApproval", "NOT_GRANTED_BY_CONFIGURATION");
        result.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria()));
        return result;
    }

    /** Read-only preparation: bounded AM identity projection, no scores, REF or criterion evaluation. */
    public ObjectNode inputOptions(String analysisId, int topN) {
        return inputOptions(analysisId, topN, null);
    }

    private ObjectNode inputOptions(String analysisId, int topN, List<VariantInterpretation> preparedRanking) {
        var pp4 = pp4Preparation;
        if (pp4 != null) return pp4Options(analysisId, topN, pp4);
        var bs4 = bs4Preparation;
        if (bs4 != null) return bs4Options(analysisId, topN, bs4);
        var pp1 = pp1Preparation;
        if (pp1 != null) return pp1Options(analysisId, topN, pp1);
        var bp2 = bp2Preparation;
        if (bp2 != null) return bp2Options(analysisId, topN, bp2);
        var pm3 = pm3Preparation;
        if (pm3 != null) return pm3Options(analysisId, topN, pm3);
        var pm6 = pm6Preparation;
        if (pm6 != null) return pm6Options(analysisId, topN, pm6);
        var ps2 = ps2Preparation;
        if (ps2 != null) return ps2Options(analysisId, topN, ps2);
        if (topN < 1 || topN > 100) throw error(HttpStatus.BAD_REQUEST, "TOP_N_OUT_OF_RANGE");
        var job = job(analysisId);
        if (job.getStatus() != VariantAnalysisStatus.READY) throw error(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY");
        var ranked = preparedRanking == null ? formalCandidates(analysisId, topN) : preparedRanking;
        ObjectNode result = mapper.createObjectNode().put("analysisId", analysisId).put("requestedN", topN)
                .put("selectedCount", ranked.size()).put("acceptanceScope", ExplicitEvidenceContextAssembler.ACCEPTANCE_SCOPE)
                .put("inputAssembly", job.getGenomeAssembly() == null ? null : job.getGenomeAssembly().name())
                .put("sampleId", job.getSelectedSampleId() == null ? job.getRequestedSampleId() : job.getSelectedSampleId())
                .put("providerCalls", 0).put("evaluatorCalls", 0);
        var common = result.putObject("commonBackground").put("source", "SAVED_ANALYSIS")
                .put("patientDiagnosisInferred", false).put("familyRelationshipsInferred", false);
        var phenotype = job.getPhenotypeSummary();
        if (phenotype == null) common.putNull("phenotypeSummary");
        else {
            var summary = common.putObject("phenotypeSummary");
            summary.set("validHpoTerms", mapper.valueToTree(phenotype.getValidHpoTerms()));
            summary.set("excludedHpoTerms", mapper.valueToTree(phenotype.getExcludedHpoTerms()));
            summary.set("invalidHpoTerms", mapper.valueToTree(phenotype.getInvalidHpoTerms()));
        }
        result.set("candidateIds", mapper.valueToTree(ranked.stream().map(candidate -> candidate.getCandidateId()).toList()));
        result.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria()));
        result.set("executionProfile", executionProfile.document());
        if (localBatchFactory != null) {
            result.set("requiredDeclaration", executionProfile.document().path("requiredDeclaration"));
            result.putObject("localBatchPreparation").put("status", "EXPLICIT_CONFIRMATION_REQUIRED")
                    .put("resourceReadiness", "NOT_CHECKED").put("scientificApproval", "NOT_GRANTED");
        }
        if (generalMissense()) {
            result.put("requiredDeclaration", missenseDeclaration());
            result.putObject(enabled("PM5") ? "pm5ReferencePreparation" : "ps1ReferencePreparation").put("status", "CATALOG_CONFIGURED")
                    .put("referenceEligibility", "NOT_EVALUATED").put("reasonCode", "REFERENCE_AND_SPLICE_GATES_CHECKED_AT_SUBMIT")
                    .put("transcriptConfirmationIsReferenceApproval", false);
        }
        if (localBatchFactory == null && enabled("PM4")) result.put("requiredDeclaration", Pm4CriterionEvaluator.DECLARATION);
        if (generalPm1()) result.put("requiredDeclaration", AcmgBusinessRuleScope.PM1_DECLARATION);
        if (generalBp1()) result.put("requiredDeclaration", AcmgBusinessRuleScope.BP1_DECLARATION);
        if (generalPp2()) result.put("requiredDeclaration", AcmgBusinessRuleScope.PP2_DECLARATION);
        if (generalBp3()) result.put("requiredDeclaration", AcmgBusinessRuleScope.BP3_DECLARATION);
        if (generalPvs1()) result.put("requiredDeclaration", AcmgBusinessRuleScope.PVS1_DECLARATION);
        var currentRevel = revelApplication;
        if (currentRevel != null) {
            result.put("requiredDeclaration", RevelApplicationBusiness.DECLARATION);
            result.set("revelApplicationBinding", currentRevel.identity());
        }
        if (executionProfile.document().has("ba1InputScope")) result.set("ba1InputScope", executionProfile.document().path("ba1InputScope"));
        var calibration = savedCalibration;
        if (calibration != null) {
            result.put("requiredDeclaration", SavedCalibrationBusiness.DECLARATION).put("runtimeConfigured", true);
            var blockers = result.putArray("blockers");
            if (!calibration.analysisId().equals(analysisId) || topN != 2) blockers.add("CALIBRATION_FIXED_ANALYSIS_TOP2_REQUIRED");
            else result.set("calibrationReference", calibration.reference());
            var rows = result.putArray("candidates");
            for (int i = 0; i < ranked.size(); i++) rows.addObject().put("candidateId", ranked.get(i).getCandidateId()).put("rank", i + 1);
            return result;
        }
        var issues = result.putArray("blockers");
        AcmgExecutionSources.Recovered recovered = null;
        AcmgBusinessStore.Source recoveredOriginal = null;
        ExplicitCaseInputCoordinator coordinator = null;
        try {
            var original = store.source(job.getId()).orElseThrow(() -> new IllegalArgumentException("EXECUTION_SOURCE_UNAVAILABLE"));
            recovered = sources.recover(job, ranked, original);
            recoveredOriginal = original;
        }
        catch (Exception missing) { issues.add(executionFailure(analysisId, null, "SOURCE_RECOVERY", missing)); }
        try { coordinator = runtime.get(); }
        catch (Exception missing) { issues.add(executionFailure(analysisId, null, "RUNTIME_INITIALIZATION", missing)); }
        result.put("executionSourceRecovered", recovered != null).put("runtimeConfigured", coordinator != null);
        if (recovered != null) result.put("analysisAssembly", recovered.execution().getInputSource().analysisAssembly().name());
        var rows = result.putArray("candidates");
        for (int i = 0; i < ranked.size(); i++) {
            var candidate = ranked.get(i); var feature = candidate.getExomiserCandidateFeatures();
            var row = rows.addObject().put("rank", i + 1).put("candidateId", candidate.getCandidateId())
                    .put("variantId", candidate.getVariantId()).put("rankingScore", candidate.getRankingScore())
                    .put("geneSymbol", feature == null ? null : feature.getGeneSymbol());
            row.set("allele", candidate.getObservation() == null ? mapper.nullNode() : mapper.valueToTree(candidate.getObservation().getKey()));
            var observation = candidate.getObservation();
            var observationSummary = row.putObject("savedObservation").put("source", "SAVED_RANKED_CANDIDATE");
            if (observation != null) {
                observationSummary.put("genotype", observation.getGenotype()).put("depth", observation.getDepth())
                        .put("genotypeQuality", observation.getGenotypeQuality()).put("filter", observation.getFilterStatus())
                        .put("alleleDepths", observation.getAlleleDepths());
            }
            row.set("candidateIdentity", mapper.valueToTree(candidate.getCandidateIdentity()));
            row.set("transcriptAnnotations", mapper.valueToTree(candidate.getTranscriptAnnotations()));
            var choices = row.putArray("transcriptChoices");
            var annotations = candidate.getTranscriptAnnotations();
            if (annotations != null) for (int index = 0; index < annotations.size(); index++) {
                var annotation = annotations.get(index);
                if (annotation != null) choices.addObject().put("annotationIndex", index)
                        .put("versionedTranscriptId", annotation.getVersionedTranscriptId())
                        .put("transcriptVersion", annotation.getTranscriptVersion());
            }
            var blockers = row.putArray("blockers");
            if (recovered != null && recovered.candidateBlockers().containsKey(candidate.getCandidateId()))
                blockers.add(mapper.valueToTree(recovered.candidateBlockers().get(candidate.getCandidateId())));
            if (coordinator != null) row.set("catalog", coordinator.referenceOptions(candidate));
            var recommendationSourceBlockers = blockers.deepCopy().addAll(issues);
            row.set("recommendation", recommendations.recommend(analysisId, candidate, row.path("catalog"),
                    recovered == null || !blockers.isEmpty() ? null : recovered.snapshotSha256(), recommendationSourceBlockers));
            var association = savedAssociation;
            if (association != null) row.set("associationOptions", association.options(candidate, row.path("recommendation")));
        }
        appendSampleCallSummaries(result, recovered, recoveredOriginal);
        return result;
    }

    /** Record facts only, after source recovery has verified each original candidate association.
     * Query/liftover copies and stored public summaries are never authorities for sample calls.
     * This is used only while constructing new options/results, never during history reads.
     */
    private void appendSampleCallSummaries(ObjectNode result,
            @org.springframework.lang.Nullable AcmgExecutionSources.Recovered recovered,
            @org.springframework.lang.Nullable AcmgBusinessStore.Source sourceOriginal) {
        if (recovered == null || sourceOriginal == null || !sourceOriginal.sha256().equals(recovered.snapshotSha256())) return;
        JsonNode snapshot;
        try { snapshot = mapper.readTree(sourceOriginal.json()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { return; }
        if (snapshot == null || !snapshot.path("candidates").isArray()) return;
        var originals = new HashMap<String, VariantInterpretation>();
        var sourceRows = new HashMap<String, JsonNode>();
        var ambiguous = new HashSet<String>();
        for (var original : recovered.execution().getInterpretations()) {
            String candidateId = original.getCandidateId();
            if (candidateId == null || candidateId.isBlank()) continue;
            if (originals.putIfAbsent(candidateId, original) != null) ambiguous.add(candidateId);
        }
        for (var sourceRow : snapshot.path("candidates")) {
            String candidateId = sourceRow.path("candidateId").asText();
            if (sourceRows.putIfAbsent(candidateId, sourceRow) != null) ambiguous.add(candidateId);
        }
        for (var candidate : result.path("candidates")) {
            String candidateId = candidate.path("candidateId").asText();
            var original = originals.get(candidateId);
            var sourceRow = sourceRows.get(candidateId);
            if (!(candidate instanceof ObjectNode row) || original == null || sourceRow == null || ambiguous.contains(candidateId)
                    || recovered.candidateBlockers().containsKey(candidateId)) continue;
            var call = original.getAnalysisVcfCall();
            if (!Objects.equals(sourceRow.at("/engineCandidate/analysisVcfCall"), mapper.valueToTree(call))
                    && !(call == null && sourceRow.at("/engineCandidate/analysisVcfCall").isMissingNode())) continue;
            if (!Objects.equals(sourceRow.at("/engineCandidate/analysisVcfCall"), sourceRow.at("/published/analysisVcfCall"))) continue;
            boolean matched = call != null && call.getMatchStatus() == AnalysisVcfCall.MatchStatus.MATCHED;
            if (matched && !verifiedSampleCallRecord(original, sourceRow, snapshot)) continue;
            row.set("sampleCallSummary", mapper.valueToTree(CandidateAlleleCallSummary.summarize(original)));
            if (matched) row.put("sampleCallAssociationStatus", "VERIFIED_ORIGINAL_ANALYSIS_SOURCE");
            row.put("sampleCallSourceSnapshotSha256", recovered.snapshotSha256());
        }
    }

    /** A typed MATCHED call requires this candidate's own immutable original record, not another
     * candidate's same-allele cache entry or a self-reported digest. No source is reread here.
     */
    private static boolean verifiedSampleCallRecord(VariantInterpretation original, JsonNode row, JsonNode snapshot) {
        var call = original.getAnalysisVcfCall();
        var key = original.getObservation() == null ? null : original.getObservation().getKey();
        var record = row.path("matchedRecord");
        if (call == null || key == null || !record.isObject()
                || !Objects.equals(call.getAssembly(), snapshot.path("analysisAssembly").asText())
                || !Objects.equals(call.getAssembly(), key.getGenomeAssembly())) return false;
        for (String field : List.of("recordOrdinal", "selectedAltIndex", "position"))
            if (!record.path(field).isIntegralNumber() || !record.path(field).canConvertToInt() || record.path(field).asInt() < 1) return false;
        int ordinal = record.path("recordOrdinal").asInt(), alt = record.path("selectedAltIndex").asInt();
        if (!Objects.equals(call.getMatchedRecordOrdinal(), ordinal) || !Objects.equals(call.getTargetAlleleIndex(), alt)
                || !record.path("alternates").isArray() || alt > record.path("alternates").size()
                || !record.path("contig").isTextual() || !record.path("reference").isTextual()) return false;
        var alternates = new ArrayList<String>();
        for (var value : record.path("alternates")) {
            if (!value.isTextual()) return false;
            alternates.add(value.asText());
        }
        var matchedRecord = new org.nbscn.varvis.variantanalysis.engine.EngineExecutionResult.MatchedRecord(
                record.path("contig").asText(), record.path("position").asInt(), record.path("reference").asText(), alternates, alt, ordinal);
        return NormalizationAlleleMapping.IDENTITY.equals(NormalizationAlleleMapping.from(matchedRecord.mappingToken(), key).getStatus());
    }

    private List<VariantInterpretation> formalCandidates(String analysisId, int topN) {
        if (unifiedLocal) {
            var selected = new ArrayList<VariantInterpretation>();
            for (var row : DistinctVariantSelection.top(jobs, analysisId, topN)) {
                var candidate = jobs.findCandidate(analysisId, row.getCandidateId()).orElseThrow(() ->
                        error(HttpStatus.CONFLICT, "FORMAL_CANDIDATE_ORDER_UNAVAILABLE"));
                if (!Objects.equals(candidate.getRankingScore(), row.getRankingScore()) ||
                        !Objects.equals(candidate.getObservation() == null ? null : candidate.getObservation().getKey(), row.getKey()))
                    throw error(HttpStatus.CONFLICT, "FORMAL_CANDIDATE_ORDER_UNAVAILABLE");
                selected.add(candidate);
            }
            return selected;
        }
        var ranked = jobs.findInterpretations(analysisId, 0, topN);
        var ranking = jobs.findRankingPage(analysisId, 0, topN);
        var ids = new HashSet<String>();
        boolean valid = ranking.size() == ranked.size() && ranked.size() <= topN;
        for (int i = 0; valid && i < ranked.size(); i++) {
            var candidate = ranked.get(i);
            valid = candidate != null && candidate.getCandidateId() != null && !candidate.getCandidateId().isBlank()
                    && ids.add(candidate.getCandidateId())
                    && Objects.equals(ranking.get(i).getCandidateId(), candidate.getCandidateId())
                    && Objects.equals(ranking.get(i).getRankingScore(), candidate.getRankingScore());
        }
        if (!valid) throw error(HttpStatus.CONFLICT, "FORMAL_CANDIDATE_ORDER_UNAVAILABLE");
        return ranked;
    }

    /** Prepares the same selected context as normal CURRENT evaluation, without
     * a run claim, provider evaluation or scientific result. Only source-bound
     * public target hints and a private analysis binding leave this method. */
    public record Pp1LiteraturePreparation(ObjectNode binding, ObjectNode target, ObjectNode aliasCoverage) {
        public Pp1LiteraturePreparation { binding = binding.deepCopy(); target = target.deepCopy(); aliasCoverage = aliasCoverage.deepCopy(); }
        @Override public ObjectNode binding() { return binding.deepCopy(); }
        @Override public ObjectNode target() { return target.deepCopy(); }
        @Override public ObjectNode aliasCoverage() { return aliasCoverage.deepCopy(); }
    }
    public Pp1LiteraturePreparation preparePp1Literature(String analysisId, byte[] bytes,
            String candidateId, String hypothesisId) {
        var selection = pp1LiteratureSelection(analysisId, bytes);
        return pp1LiteratureTarget(selection, candidateId, hypothesisId);
    }

    public record Pp1LiteratureAttempt(String candidateId, String hypothesisId) { }
    public record Pp1LiteratureBatch(Map<Pp1LiteratureAttempt, Pp1LiteraturePreparation> targets,
                                     Map<Pp1LiteratureAttempt, String> blockers) { }
    private record Pp1LiteratureSelection(SelectedEvidencePreparation preparation, String sourceSnapshot) { }

    public Pp1LiteratureBatch preparePp1LiteratureBatch(String analysisId, byte[] bytes) {
        var selection = pp1LiteratureSelection(analysisId, bytes);
        var targets = new java.util.LinkedHashMap<Pp1LiteratureAttempt, Pp1LiteraturePreparation>();
        var blockers = new java.util.LinkedHashMap<Pp1LiteratureAttempt, String>();
        for (var candidate : selection.preparation().assembly().candidates()) for (var attempt : candidate.attempts()) {
            var key = new Pp1LiteratureAttempt(candidate.candidateId(), attempt.hypothesisId());
            try { targets.put(key, pp1LiteratureTarget(selection, key.candidateId(), key.hypothesisId())); }
            catch (ResponseStatusException unavailable) { blockers.put(key, unavailable.getReason()); }
        }
        return new Pp1LiteratureBatch(java.util.Collections.unmodifiableMap(targets), java.util.Collections.unmodifiableMap(blockers));
    }

    private Pp1LiteratureSelection pp1LiteratureSelection(String analysisId, byte[] bytes) {
        if (!unifiedLocal || localBatchFactory == null || !enabled("PP1"))
            throw error(HttpStatus.CONFLICT, "PP1_CURRENT_PROFILE_NOT_CONFIGURED");
        var job = job(analysisId);
        if (job.getStatus() != VariantAnalysisStatus.READY) throw error(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY");
        AcmgSubmission request;
        try { request = AcmgSubmission.parse(bytes, mapper); }
        catch (IllegalArgumentException invalid) { throw error(HttpStatus.BAD_REQUEST, invalid.getMessage()); }
        if (request.topN() > 50 || !executionProfile.document().path("requiredDeclaration").asText().equals(request.declarationVersion())
                || request.retryOf() != null || request.association() != null || request.calibration() != null
                || request.revelApplication() != null || request.ps2Selection() != null || request.pm6Selection() != null
                || request.pm3Selection() != null || request.bp2Selection() != null || request.pp1Selection() != null
                || request.bs4Selection() != null || request.pp4Selection() != null)
            throw error(HttpStatus.BAD_REQUEST, "PP1_CURRENT_DECLARATION_REQUIRED");
        var ranked = formalCandidates(analysisId, request.topN());
        var verified = mapper.createArrayNode();
        if (request.recommendations() != null) {
            var options = inputOptions(analysisId, request.topN(), ranked); var seen = new HashSet<String>();
            try {
                for (var choice : request.recommendations()) {
                    if (choice == null || !seen.add(choice.candidateId())) throw new IllegalArgumentException();
                    JsonNode recommendation = null;
                    for (var row : options.path("candidates")) if (choice.candidateId().equals(row.path("candidateId").asText()))
                        recommendation = row.path("recommendation");
                    if (!(recommendation instanceof ObjectNode object)) throw new IllegalArgumentException();
                    recommendations.verify(choice, object, request.hypotheses()); verified.add(object);
                }
                if (seen.size() != options.path("selectedCount").asInt() || request.hypotheses().stream().anyMatch(h ->
                        h == null || h.hypothesis() == null || !seen.contains(h.hypothesis().candidateId())))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException stale) { throw error(HttpStatus.CONFLICT, "RECOMMENDATION_STALE"); }
        }
        try {
            var restored = sources.recover(job, ranked);
            var coordinator = localSelectedRuntime == null ? runtime.get() : localSelectedRuntime.get();
            var effective = recommendations.withPopulationContexts(request, verified);
            var declaration = new ExplicitCaseInputCoordinator.CaseDeclaration(analysisId, restored.execution().getInputSource(),
                    request.sampleId(), request.dataClass(), request.patient(), effective);
            var analysis = new AnalysisContext(analysisId, restored.execution().getInputSource().analysisAssembly(),
                    job.getAnalysisMode(), VcfPreflightSummary.empty(), job.getPhenotypeSummary(), null);
            var prepared = coordinator.preparePp1Selection(job, restored.conversion(), restored.execution(), analysis,
                    ranked, request.topN(), List.of(declaration), restored.candidateBlockers());
            return new Pp1LiteratureSelection(prepared, restored.snapshotSha256());
        } catch (ResponseStatusException error) { throw error; }
        catch (Exception failure) { throw pp1LiteraturePreparationFailure(failure); }
    }

    private Pp1LiteraturePreparation pp1LiteratureTarget(Pp1LiteratureSelection selection, String candidateId, String hypothesisId) {
        var prepared = selection.preparation();
        if (prepared.assembly().candidates().stream().noneMatch(candidate -> candidate.candidateId().equals(candidateId)))
            throw error(HttpStatus.BAD_REQUEST, "PP1_CANDIDATE_NOT_SELECTED");
        try {
            var binding = new Pp1AnalysisContext(mapper).prepare(prepared, candidateId, hypothesisId,
                    selection.sourceSnapshot(), executionProfile.criteria());
            var formal = prepared.formalCandidate(candidateId);
            String gene = binding.at("/selection/geneSymbol").asText();
            var aliases = Pp1SearchAliases.select(mapper, formal, gene);
            return new Pp1LiteraturePreparation(binding, aliases.target(), aliases.coverage());
        } catch (IllegalArgumentException failure) { throw pp1LiteraturePreparationFailure(failure); }
    }

    private static ResponseStatusException pp1LiteraturePreparationFailure(Exception failure) {
        String reason = failure.getMessage();
        return error(HttpStatus.CONFLICT, reason != null && reason.matches("[A-Z][A-Z0-9_]{1,160}")
                ? reason : "PP1_CONTEXT_PREPARATION_FAILED");
    }

    public ObjectNode planDeNovoLiterature(String analysisId, byte[] bytes) {
        var factory = localBatchFactory;
        if (!unifiedLocal || factory == null || !enabled("PS2") && !enabled("PM6"))
            throw error(HttpStatus.CONFLICT, "DENOVO_CURRENT_PROFILE_NOT_CONFIGURED");
        var job = job(analysisId);
        if (job.getStatus() != VariantAnalysisStatus.READY) throw error(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY");
        AcmgSubmission request;
        try { request = AcmgSubmission.parse(bytes, mapper); }
        catch (IllegalArgumentException invalid) { throw error(HttpStatus.BAD_REQUEST, invalid.getMessage()); }
        if (request.topN() > 50 || !executionProfile.document().path("requiredDeclaration").asText().equals(request.declarationVersion())
                || request.association() != null || request.calibration() != null || request.revelApplication() != null
                || request.ps2Selection() != null || request.pm6Selection() != null || request.pm3Selection() != null
                || request.bp2Selection() != null || request.pp1Selection() != null || request.bs4Selection() != null
                || request.pp4Selection() != null)
            throw error(HttpStatus.BAD_REQUEST, "DENOVO_CURRENT_DECLARATION_REQUIRED");
        var criteria = request.selectedCriteria() == null ? executionProfile.criteria() : request.selectedCriteria();
        var ranked = formalCandidates(analysisId, request.topN());
        var verified = mapper.createArrayNode();
        if (request.recommendations() != null) {
            var options = inputOptions(analysisId, request.topN(), ranked);
            var seen = new HashSet<String>();
            try {
                for (var choice : request.recommendations()) {
                    if (choice == null || !seen.add(choice.candidateId())) throw new IllegalArgumentException();
                    var matched = options.path("candidates").valueStream().filter(row ->
                            choice.candidateId().equals(row.path("candidateId").asText())).findFirst().orElseThrow(IllegalArgumentException::new);
                    if (!(matched.path("recommendation") instanceof ObjectNode recommendation)) throw new IllegalArgumentException();
                    recommendations.verify(choice, recommendation, request.hypotheses());
                    verified.add(recommendation);
                }
                if (seen.size() != options.path("selectedCount").asInt() || request.hypotheses().stream().anyMatch(h ->
                        h == null || h.hypothesis() == null || !seen.contains(h.hypothesis().candidateId())))
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException stale) { throw error(HttpStatus.CONFLICT, "RECOMMENDATION_STALE"); }
        }
        try {
            var restored = sources.recover(job, ranked);
            var batch = factory.get();
            if (!batch.identity().equals(localBatchBinding))
                throw error(HttpStatus.CONFLICT, "LOCAL_BATCH_DEPENDENCY_CHANGED_RESTART_REQUIRED");
            var coordinator = localSelectedRuntime == null ? runtime.get() : localSelectedRuntime.get();
            var declaration = new ExplicitCaseInputCoordinator.CaseDeclaration(analysisId, restored.execution().getInputSource(),
                    request.sampleId(), request.dataClass(), request.patient(), recommendations.withPopulationContexts(request, verified));
            var analysis = new AnalysisContext(analysisId, restored.execution().getInputSource().analysisAssembly(),
                    job.getAnalysisMode(), VcfPreflightSummary.empty(), job.getPhenotypeSummary(), null);
            var prepared = coordinator.withDeNovoPublishedBackgrounds(batch.deNovoPublishedBackgrounds(criteria, request.dataClass()))
                    .prepareLocalInterpretation(job, restored.conversion(), restored.execution(), analysis,
                            ranked, request.topN(), List.of(declaration), restored.candidateBlockers());
            var missing = batch.deNovoDiscoveryCandidates(prepared, criteria);
            var plan = mapper.createObjectNode().put("schemaVersion", "denovo-local-discovery-plan-v1");
            var candidateIds = plan.putArray("candidateIds");
            ranked.stream().map(VariantInterpretation::getCandidateId).filter(missing::contains).forEach(candidateIds::add);
            return plan;
        } catch (ResponseStatusException invalid) { throw invalid; }
        catch (Exception unavailable) { throw error(HttpStatus.CONFLICT, "DENOVO_LOCAL_PREPARATION_UNAVAILABLE"); }
    }

    /** Public allele and selected background only: no GT, sample, patient, phenotype or ranking scores leave this projection. */
    private List<Pp2OtherPhenotypeSource.DiscoveryTarget> pp2DiscoveryTargets(List<VariantInterpretation> ranked,
            List<ExplicitCaseInputCoordinator.HypothesisInput> inputs) {
        var targets = new ArrayList<Pp2OtherPhenotypeSource.DiscoveryTarget>();
        for (var input : inputs) {
            if (input == null || input.hypothesis() == null) continue;
            var hypothesis = input.hypothesis();
            var matches = ranked.stream().filter(candidate -> candidate.getCandidateId().equals(hypothesis.candidateId())).toList();
            if (matches.size() != 1) continue;
            var candidate = matches.get(0); var observation = candidate.getObservation();
            String symbol = candidate.getCandidateIdentity().getGeneSymbol();
            if (observation == null || observation.getKey() == null || symbol == null || symbol.isBlank()) continue;
            var key = observation.getKey();
            var allele = mapper.createObjectNode().put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome())
                    .put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
            var hints = mapper.createObjectNode().put("scope", "PUBLIC_VARIANT_DISCOVERY_HINTS_ONLY")
                    .put("candidateId", candidate.getCandidateId()).put("canonicalVariantId", candidate.getCanonicalVariantId()).put("geneSymbol", symbol);
            var annotations = hints.putArray("transcriptAnnotations");
            for (var annotation : candidate.getTranscriptAnnotations()) if (symbol.equals(annotation.getGeneSymbol())) {
                annotations.addObject().put("geneSymbol", symbol).put("versionedTranscriptId", annotation.getVersionedTranscriptId())
                        .put("hgvsCoding", annotation.getHgvsCoding()).put("hgvsProtein", annotation.getHgvsProtein())
                        .put("source", annotation.getSource()).put("status", annotation.getStatus()).put("version", annotation.getVersion());
            }
            var variationIds = hints.putArray("clinvarVariationIds"); var distinctIds = new HashSet<String>();
            for (var evidence : candidate.getEvidence()) if ("CLINVAR".equalsIgnoreCase(evidence.getSource())) {
                JsonNode value = mapper.valueToTree(evidence);
                String record = value.path("sourceRecordId").asText();
                if (record.matches("ClinVar:VariationID:[1-9][0-9]*")) distinctIds.add(record.substring("ClinVar:VariationID:".length()));
                for (String field : List.of("variationId", "clinvarVariationId", "VariationID")) {
                    String id = value.path("details").path(field).asText();
                    if (id.matches("[1-9][0-9]*")) distinctIds.add(id);
                }
            }
            distinctIds.stream().sorted().forEach(variationIds::add);
            targets.add(new Pp2OtherPhenotypeSource.DiscoveryTarget(hypothesis.candidateId(), hypothesis.hypothesisId(), allele,
                    hypothesis.hgncId(), symbol, hypothesis.mondoId(),
                    PatientDiseaseContextBindingResolver.normalizeCatalogMoi(hypothesis.moi()), hints));
        }
        return List.copyOf(targets);
    }

    public ObjectNode submit(String analysisId, byte[] bytes) {
        VariantAnalysisJob job = job(analysisId);
        if (job.getStatus() != VariantAnalysisStatus.READY) throw error(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY");
        AcmgSubmission request;
        try { request = AcmgSubmission.parse(bytes, mapper); }
        catch (IllegalArgumentException invalid) { throw error(HttpStatus.BAD_REQUEST, invalid.getMessage()); }
        if (localBatchFactory == null && Set.of(AcmgBusinessRuleScope.LOCAL_BATCH1_DECLARATION,
                AcmgBusinessRuleScope.UNIFIED_LOCAL_DECLARATION, AcmgBusinessRuleScope.UNIFIED_ELEVEN_DECLARATION, AcmgBusinessRuleScope.UNIFIED_FOURTEEN_DECLARATION).contains(request.declarationVersion()))
            throw error(HttpStatus.BAD_REQUEST, "LOCAL_BATCH_SCOPE_REQUIRED");
        if (localBatchFactory != null && !executionProfile.document().path("requiredDeclaration").asText().equals(request.declarationVersion()))
            throw error(HttpStatus.BAD_REQUEST, "LOCAL_BATCH_EXPLICIT_DECLARATION_REQUIRED");
        var pp4 = pp4Preparation;
        if (pp4 == null && (request.pp4Selection() != null || AcmgBusinessRuleScope.PP4_DECLARATION.equals(request.declarationVersion())
                || containsPp4Token(mapper.valueToTree(request)))) throw error(HttpStatus.BAD_REQUEST, "PP4_SCOPE_REQUIRED");
        var bs4 = bs4Preparation;
        if (bs4 == null && (request.bs4Selection() != null || AcmgBusinessRuleScope.BS4_DECLARATION.equals(request.declarationVersion())
                || containsBs4Token(mapper.valueToTree(request)))) throw error(HttpStatus.BAD_REQUEST, "BS4_SCOPE_REQUIRED");
        var pp1 = pp1Preparation;
        if (pp1 == null && (request.pp1Selection() != null || AcmgBusinessRuleScope.PP1_DECLARATION.equals(request.declarationVersion())
                || containsPp1Token(mapper.valueToTree(request)))) throw error(HttpStatus.BAD_REQUEST, "PP1_SCOPE_REQUIRED");
        var bp2 = bp2Preparation;
        if (bp2 == null && (request.bp2Selection() != null || AcmgBusinessRuleScope.BP2_DECLARATION.equals(request.declarationVersion())
                || containsBp2Token(mapper.valueToTree(request)))) throw error(HttpStatus.BAD_REQUEST, "BP2_SCOPE_REQUIRED");
        // Check isolated rule markers before any early/adoption dispatch, including dual selections.
        var pm3 = pm3Preparation;
        if (pm3 == null && (request.pm3Selection() != null || AcmgBusinessRuleScope.PM3_DECLARATION.equals(request.declarationVersion())
                || containsPm3Token(mapper.valueToTree(request)))) throw error(HttpStatus.BAD_REQUEST, "PM3_SCOPE_REQUIRED");
        var pm6 = pm6Preparation;
        var ps2 = ps2Preparation;
        if (pm6 == null && (request.pm6Selection() != null || AcmgBusinessRuleScope.PM6_DECLARATION.equals(request.declarationVersion())
                || containsPm6Token(mapper.valueToTree(request)))) throw error(HttpStatus.BAD_REQUEST, "PM6_SCOPE_REQUIRED");
        if (ps2 == null && (request.ps2Selection() != null || AcmgBusinessRuleScope.PS2_DECLARATION.equals(request.declarationVersion())
                || containsPs2Token(mapper.valueToTree(request)))) throw error(HttpStatus.BAD_REQUEST, "PS2_SCOPE_REQUIRED");
        if (pp4 != null) return submitPp4(job, request, pp4);
        if (bs4 != null) return submitBs4(job, request, bs4);
        if (pp1 != null) return submitPp1(job, request, pp1);
        if (bp2 != null) return submitBp2(job, request, bp2);
        if (pm3 != null) return submitPm3(job, request, pm3);
        if (pm6 != null) return submitPm6(job, request, pm6);
        if (ps2 != null) return submitPs2(job, request, ps2);
        if (request.revelApplication() != null) return adoptRevel(job, request);
        if (generalMissense() && !missenseDeclaration().equals(request.declarationVersion()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, missenseCode() + "_DECLARATION_REQUIRED");
        if (generalBp1() && !AcmgBusinessRuleScope.BP1_DECLARATION.equals(request.declarationVersion()))
            throw error(HttpStatus.BAD_REQUEST, "BP1_EXPLICIT_DECLARATION_REQUIRED");
        if (generalPp2() && !AcmgBusinessRuleScope.PP2_DECLARATION.equals(request.declarationVersion()))
            throw error(HttpStatus.BAD_REQUEST, "PP2_EXPLICIT_DECLARATION_REQUIRED");
        if (generalBp3() && !AcmgBusinessRuleScope.BP3_DECLARATION.equals(request.declarationVersion()))
            throw error(HttpStatus.BAD_REQUEST, "BP3_EXPLICIT_DECLARATION_REQUIRED");
        if (generalPvs1() && !AcmgBusinessRuleScope.PVS1_DECLARATION.equals(request.declarationVersion()))
            throw error(HttpStatus.BAD_REQUEST, "PVS1_EXPLICIT_DECLARATION_REQUIRED");
        if (generalPm1() && !AcmgBusinessRuleScope.PM1_DECLARATION.equals(request.declarationVersion()))
            throw error(HttpStatus.BAD_REQUEST, "PM1_EXPLICIT_DECLARATION_REQUIRED");
        if (localBatchFactory == null && enabled("PM4") && !Pm4CriterionEvaluator.DECLARATION.equals(request.declarationVersion()))
            throw error(HttpStatus.BAD_REQUEST, "PM4_EXPLICIT_DECLARATION_REQUIRED");
        if (revelApplication != null && !RevelApplicationBusiness.DECLARATION.equals(request.declarationVersion()))
            throw error(HttpStatus.BAD_REQUEST, "REVEL_EXPLICIT_DECLARATION_REQUIRED");
        if (revelApplication != null && request.retryOf() != null)
            throw error(HttpStatus.CONFLICT, "REVEL_SOURCE_RETRY_REQUIRES_SEPARATE_BUDGET");
        if (request.calibration() != null || savedCalibration != null || SavedCalibrationBusiness.DECLARATION.equals(request.declarationVersion()))
            return calibrateSaved(job, request);
        if (request.association() != null || savedAssociation != null || SavedPredictionAssociation.DECLARATION.equals(request.declarationVersion()))
            return associateSaved(job, request);
        if (predictionRuntime != null && !PredictionDevelopmentRuntime.DECLARATION.equals(request.declarationVersion()))
            throw error(HttpStatus.BAD_REQUEST, "PREDICTION_EXPLICIT_PURPOSE_REQUIRED");
        if (bs1Runtime != null && !unifiedLocal && !(ordinaryBs1 ? AcmgBusinessRuleScope.BS1_DECLARATION : Bs1Runtime.DECLARATION).equals(request.declarationVersion()))
            throw error(HttpStatus.BAD_REQUEST, "BS1_EXPLICIT_PURPOSE_REQUIRED");
        if ((request.selectedCriteria() != null || request.operationId() != null) && !unifiedLocal)
            throw error(HttpStatus.BAD_REQUEST, "ACMG_SELECTION_REQUIRES_CURRENT_WORKBENCH");
        AcmgExecutionProfile runProfile;
        try { runProfile = executionProfile.select(mapper, request.selectedCriteria()); }
        catch (IllegalArgumentException invalid) { throw error(HttpStatus.BAD_REQUEST, invalid.getMessage()); }
        JsonNode declaration = mapper.valueToTree(request);
        // Display-only timestamps do not change the declaration identity on input-options refresh.
        for (var selection : declaration.path("recommendations")) ((ObjectNode) selection).remove("generatedAt");
        if (("ordinary-ui-v3".equals(request.declarationVersion()) || localBatchFactory != null || ordinaryBs1 || predictionRuntime != null || revelApplication != null || enabled("PM4") || generalMissense() || generalPm1() || generalPvs1() || generalBp3() || generalPp2() || generalBp1()) && request.recommendations() == null)
            throw error(HttpStatus.BAD_REQUEST, "RECOMMENDATION_REQUIRED");
        ObjectNode requestIdentity = mapper.createObjectNode().set("declaration", declaration);
        requestIdentity.set("executionProfile", runProfile.document());
        // Fresh executions must not reuse a result published before original-record association
        // was required. Historical reads retain their original identity and saved bytes.
        requestIdentity.put("sampleCallSourceAssociationCapability", "ORIGINAL_CANDIDATE_MATCHED_RECORD_V1");
        if (runProfile.criteria().contains(GeneralBp1Runtime.CRITERION))
            requestIdentity.put("bp1PublishedBlockerRoutingVersion", GeneralBp1Runtime.PUBLISHED_BLOCKER_ROUTING_VERSION)
                    .put("bp1TruncatingSourceAdaptationVersion", PublishedCriterionKnowledge.BP1_TRUNCATING_SOURCE_ADAPTATION_VERSION);
        if (unifiedLocal && runProfile.criteria().containsAll(List.of("ACMG_AMP_2015:PS2", "ACMG_AMP_2015:PM6")))
            requestIdentity.put("deNovoExclusiveCompletionCapability", "SOURCE_BOUND_SHARED_EVENT_COMPLETION_V1");
        // One request owns this snapshot. Execution below still rereads the published ranking and
        // recovers/verifies source bytes; no mutable source or cross-request cache is introduced.
        var preparedRanking = AcmgExecutionProgress.measure("FORMAL_CANDIDATES",
                () -> formalCandidates(analysisId, request.topN()));
        ObjectNode preparedOptions = null;
        LocalEvidenceBatchRuntime localBatch = null;
        if (localBatchFactory != null) {
            localBatch = localBatchFactory.get();
            if (!localBatch.identity().equals(localBatchBinding))
                throw error(HttpStatus.CONFLICT, "LOCAL_BATCH_DEPENDENCY_CHANGED_RESTART_REQUIRED");
            try {
                var published = store.source(analysisId);
                requestIdentity.putObject("localBatchPublishedInputIdentity")
                        .put("candidatesRawSha256", AcmgBusinessStore.sha(mapper.writeValueAsString(preparedRanking)))
                        .put("executionSourceSha256", published.isPresent() ? published.get().sha256() : "UNAVAILABLE");
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw error(HttpStatus.CONFLICT, "LOCAL_BATCH_PUBLISHED_INPUT_IDENTITY_UNAVAILABLE");
            }
        }
        if (generalPm1() || generalPvs1() || generalBp3() || generalPp2() || generalBp1()) {
            // A saved single-rule result only belongs to the same published candidates and source snapshot.
            // Hash the existing serialized DTO representation as raw bytes, including its timestamps.
            try {
                var publishedSource = store.source(analysisId);
                String sourceSha = publishedSource.isPresent() ? Objects.requireNonNull(publishedSource.get()).sha256() : "UNAVAILABLE";
                requestIdentity.putObject(generalBp1() ? "bp1PublishedInputIdentity" : generalPp2() ? "pp2PublishedInputIdentity" : generalBp3() ? "bp3PublishedInputIdentity" : generalPvs1() ? "pvs1PublishedInputIdentity" : "pm1PublishedInputIdentity")
                        .put("candidatesRawSha256", AcmgBusinessStore.sha(mapper.writeValueAsString(preparedRanking)))
                        .put("executionSourceSha256", sourceSha);
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw error(HttpStatus.CONFLICT, generalBp1() ? "BP1_PUBLISHED_INPUT_IDENTITY_UNAVAILABLE" : generalPp2() ? "PP2_PUBLISHED_INPUT_IDENTITY_UNAVAILABLE" : generalBp3() ? "BP3_PUBLISHED_INPUT_IDENTITY_UNAVAILABLE" : generalPvs1() ? "PVS1_PUBLISHED_INPUT_IDENTITY_UNAVAILABLE" : "PM1_PUBLISHED_INPUT_IDENTITY_UNAVAILABLE");
            }
        }
        var effectAdapter = predictionEffects;
        boolean needsEffects = new AcmgCriterionSelection(runProfile.criteria()).needsFunctionalReferences();
        PredictionEffectReferences.Snapshot effectSnapshot = effectAdapter == null || !needsEffects ? null : effectAdapter.capture(analysisId,
                new java.util.HashSet<>(preparedRanking.stream().map((VariantInterpretation value) -> value.getCandidateId()).toList()));
        if (effectSnapshot != null) requestIdentity.put("effectSnapshotSha256", effectSnapshot.identity());
        if (localBatchWithEffects != null && effectSnapshot != null) {
            localBatch = localBatchWithEffects.apply(effectSnapshot::resolve, effectSnapshot.identity());
            if (!localBatch.identity().equals(localBatchBinding))
                throw error(HttpStatus.CONFLICT, "LOCAL_BATCH_DEPENDENCY_CHANGED_RESTART_REQUIRED");
        }
        var deNovoBackgrounds = localBatch == null ? List.<DeNovoSelectedBatch.PublishedBackground>of()
                : localBatch.deNovoPublishedBackgrounds(runProfile.criteria(), request.dataClass());
        if (!deNovoBackgrounds.isEmpty()) requestIdentity.set("deNovoPublishedBackgrounds", mapper.valueToTree(deNovoBackgrounds));
        if(localBatch!=null) {
            AcmgExecutionProgress.stage("SOURCE_SNAPSHOT", null, null, "PM3 / PP1");
            localBatch.bindExecutionCriteria(runProfile.criteria());
            if(localBatch.hasPm3() || localBatch.hasPp1()) {
                preparedOptions = AcmgExecutionProgress.measure("BACKGROUND_OPTIONS",
                        () -> inputOptions(analysisId, request.topN(), preparedRanking));
                if(localBatch.hasPm3())requestIdentity.set("pm3LiteratureSnapshot",localBatch.preparePm3Sources(analysisId,preparedOptions.deepCopy()));
                if(localBatch.hasPp1())requestIdentity.set("pp1LiteratureSnapshot",localBatch.preparePp1Sources(analysisId,preparedOptions.deepCopy()));
            }
            if (localBatch.needsFullRnaSourceIdentity())
                requestIdentity.set("fullRnaSourceSnapshot", localBatch.prepareFullRnaSourceIdentity());
        }
        var verifiedRecommendations = mapper.createArrayNode();
        if (request.recommendations() != null) {
            AcmgExecutionProgress.stage("BACKGROUND_VERIFY", null, null, null);
            var current = preparedOptions == null ? AcmgExecutionProgress.measure("BACKGROUND_OPTIONS",
                    () -> inputOptions(analysisId, request.topN(), preparedRanking)) : preparedOptions;
            var seen = new HashSet<String>();
            try {
                for (var selection : request.recommendations()) {
                    if (selection == null || !seen.add(selection.candidateId())) throw new IllegalArgumentException();
                    ObjectNode recommendation = null;
                    for (var row : current.path("candidates")) if (row.path("candidateId").asText().equals(selection.candidateId())) recommendation = (ObjectNode) row.path("recommendation");
                    if (recommendation == null) throw new IllegalArgumentException();
                    recommendations.verify(selection, recommendation, request.hypotheses());
                    verifiedRecommendations.add(recommendation);
                }
                if (request.hypotheses().stream().anyMatch(h -> h == null || h.hypothesis() == null || !seen.contains(h.hypothesis().candidateId()))) throw new IllegalArgumentException();
                if (seen.size() != current.path("selectedCount").asInt()) throw new IllegalArgumentException();
            } catch (Exception stale) { throw error(HttpStatus.CONFLICT, "RECOMMENDATION_STALE"); }
        }
        if (localBatch != null && localBatch.needsPp2OtherPhenotypePreparation()) {
            var discoveryTargets = pp2DiscoveryTargets(preparedRanking,
                    recommendations.withPopulationContexts(request, verifiedRecommendations));
            requestIdentity.set("pp2OtherPhenotypeSourceSnapshot", localBatch.preparePp2OtherPhenotypeSources(discoveryTargets));
        }
        AcmgExecutionProgress.stage("RUN_CLAIM", null, null, null);
        String hash = artifacts.requestHash(requestIdentity);
        Optional<AcmgBusinessStore.Run> previous = store.byRequest(analysisId, hash);
        if (previous.isPresent()) return read(analysisId, previous.get().id());
        if (request.retryOf() != null) {
            var old = store.run(analysisId, request.retryOf()).orElseThrow(() -> error(HttpStatus.CONFLICT, "RETRY_RUN_NOT_FOUND"));
            if (!Set.of("FAILED", "COMPLETED_WITH_BLOCKERS", "CANCELLED", "INTERRUPTED").contains(old.status())) throw error(HttpStatus.CONFLICT, "RETRY_REQUIRES_TERMINAL_FAILURE");
            if (localBatch != null) {
                try {
                    ObjectNode prior = (ObjectNode) mapper.readTree(old.requestJson());
                    if (!sameRetryIdentity(prior, requestIdentity)) throw error(HttpStatus.CONFLICT, "LOCAL_BATCH_RETRY_IDENTITY_MISMATCH");
                    var priorSupply=trustedRead(analysisId,request.retryOf()).privateData().at("/localBatch/modules/deNovo/sourceSupply");
                    String priorTask=(priorSupply.has("sourceTask")?priorSupply.path("sourceTask"):priorSupply).path("taskId").asText();
                    if(priorTask.matches("[a-f0-9]{64}")) localBatch.explicitDeNovoSourceRetry(priorTask);
                } catch (com.fasterxml.jackson.core.JsonProcessingException | ClassCastException invalid) {
                    throw error(HttpStatus.CONFLICT, "LOCAL_BATCH_RETRY_IDENTITY_MISMATCH");
                }
            }
        }
        // Check the publication lock before creating a run or spending minutes on a model.
        // Release it immediately; the final publication still locks and rechecks BA1 reuse.
        store.withAnalysisLock(analysisId, () -> null);
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        try {
            var pending = new AcmgBusinessStore.Run(id, analysisId, hash, mapper.writeValueAsString(requestIdentity), "RUNNING", now, null, null, null);
            if (!(request.selectedCriteria() == null ? store.claim(pending) : store.claimSelected(pending)))
                return read(analysisId, store.byRequest(analysisId, hash).orElseThrow().id());
        } catch (Exception failed) {
            if ("ACMG_ANALYSIS_ALREADY_RUNNING".equals(failed.getMessage())) throw error(HttpStatus.CONFLICT, failed.getMessage());
            throw persistenceFailure(failed, "ACMG_RUN_CLAIM_FAILED");
        }
        AcmgExecutionProgress.bindRun(analysisId, id);
        ObjectNode response = mapper.createObjectNode().put("runId", id).put("analysisId", analysisId)
                .put("declarationVersion", request.declarationVersion()).put("requestSha256", hash).put("createdAt", now)
                .put("acceptanceScope", ExplicitEvidenceContextAssembler.ACCEPTANCE_SCOPE)
                .put("automaticTrigger", false).put("clinicalValidationCompleted", false)
                .put("dataClass", request.dataClass())
                .put("runtimeRegistrationId", executionProfile.document().path("profileId").asText());
        response.set("enabledCriteria", mapper.valueToTree(runProfile.criteria()));
        response.set("executionProfile", runProfile.document());
        if (effectSnapshot != null) response.putObject("dependencyPreparation").set("functionalReferences", effectSnapshot.readiness());
        if (localBatch != null && request.retryOf() != null) response.put("retryOf", request.retryOf());
        ObjectNode privateData = mapper.createObjectNode().set("declaration", declaration);
        var sourceRevel = revelApplication;
        if (sourceRevel != null) {
            // Immutable generation audit, deliberately outside the request deduplication identity.
            var audit = mapper.createObjectNode().put("configurationSha256", sourceRevel.identity().path("configurationSha256").asText())
                    .put("bindingSha256", sourceRevel.identity().path("bindingSha256").asText());
            response.set("revelSourceGenerationAudit", audit);
            privateData.set("revelSourceGenerationAudit", audit.deepCopy());
        }
        if (request.recommendations() != null) {
            privateData.set("verifiedRecommendations", verifiedRecommendations);
            privateData.set("receivedRecommendationReferences", mapper.valueToTree(request.recommendations()));
        }
        // The durable RUNNING claim is already visible to identical concurrent submissions.
        // No transaction/checked-out JDBC connection spans source recovery or long external model calls.
        try (var control = AcmgRunControl.begin(store, analysisId, id)) {
            // This is the official persisted ranking query, never the enhancement reranker.
            AcmgExecutionProgress.stage("SOURCE_RECOVERY", null, null, null);
            List<VariantInterpretation> ranked = formalCandidates(analysisId, request.topN());
            if (!mapper.valueToTree(preparedRanking).equals(mapper.valueToTree(ranked)))
                throw error(HttpStatus.CONFLICT, "FORMAL_CANDIDATES_CHANGED_DURING_PREPARATION");
            response.put("requestedN", request.topN()).put("selectedCount", ranked.size()).put("candidateOrderingChanged", false);
            response.set("candidateIds", mapper.valueToTree(ranked.stream().map((VariantInterpretation value) -> value.getCandidateId()).toList()));
            String sourceFailure = null;
            AcmgExecutionSources.Recovered restored = null;
            AcmgBusinessStore.Source restoredOriginal = null;
            ExplicitCaseInputCoordinator coordinator = null;
            var effectiveHypotheses = request.hypotheses();
            try {
                var original = store.source(job.getId()).orElseThrow(() -> new IllegalArgumentException("EXECUTION_SOURCE_UNAVAILABLE"));
                restored = sources.recover(job, ranked, original);
                restoredOriginal = original;
            }
            catch (Exception unavailable) {
                var diagnostic = executionFailure(analysisId, id, "SOURCE_RECOVERY", unavailable);
                sourceFailure = diagnostic.path("reasonCode").asText();
                response.set("executionSourceFailure", diagnostic);
            }
            if (restored != null) try { coordinator = runtime.get(); }
            catch (Exception unavailable) {
                var diagnostic = executionFailure(analysisId, id, "RUNTIME_INITIALIZATION", unavailable);
                sourceFailure = diagnostic.path("reasonCode").asText();
                response.set("executionSourceFailure", diagnostic);
            }
            if (sourceFailure != null) {
                response.put("status", "COMPLETED_WITH_BLOCKERS").put("providerCalls", 0).put("evaluatorCalls", 0);
                var rows = response.putArray("candidates");
                var selected = new HashSet<String>();
                for (var candidate : ranked) {
                    selected.add(candidate.getCandidateId());
                    var row = rows.addObject().put("candidateId", candidate.getCandidateId());
                    var attempts = row.putArray("attempts");
                    var inputs = request.hypotheses().stream().filter(h -> h != null && h.hypothesis() != null
                            && Objects.equals(candidate.getCandidateId(), h.hypothesis().candidateId())).toList();
                    if (inputs.isEmpty()) addBlocked(attempts.addObject(), null, sourceFailure);
                    for (var h : inputs) addBlocked(attempts.addObject(), h.hypothesis().hypothesisId(), sourceFailure);
                }
                response.set("unmatchedInputs", mapper.valueToTree(request.hypotheses().stream()
                        .filter(h -> h == null || h.hypothesis() == null || !selected.contains(h.hypothesis().candidateId()))
                        .map(h -> h == null || h.hypothesis() == null ? Map.of("reasonCode", "CANDIDATE_DECLARATION_NOT_MATCHED")
                                : Map.of("candidateId", Objects.toString(h.hypothesis().candidateId(), ""), "hypothesisId", Objects.toString(h.hypothesis().hypothesisId(), ""), "reasonCode", "CANDIDATE_DECLARATION_NOT_MATCHED")).toList()));
                response.putArray("bundles");
            } else {
                Objects.requireNonNull(restored, "recovered execution");
                Objects.requireNonNull(coordinator, "explicit case runtime");
                response.put("executionSourceSha256", restored.snapshotSha256());
                effectiveHypotheses = recommendations.withPopulationContexts(request, verifiedRecommendations);
                var input = new ExplicitCaseInputCoordinator.CaseDeclaration(analysisId, restored.execution().getInputSource(),
                        request.sampleId(), request.dataClass(), request.patient(), effectiveHypotheses);
                AnalysisContext context = new AnalysisContext(analysisId, restored.execution().getInputSource().analysisAssembly(), job.getAnalysisMode(), VcfPreflightSummary.empty(), job.getPhenotypeSummary(), null);
                if (localBatch != null) {
                    AcmgExecutionProgress.stage("IDENTITY_ANNOTATION", null, null, null);
                    var selectedCoordinator = localSelectedRuntime == null ? coordinator : localSelectedRuntime.get();
                    var selected = selectedCoordinator.withDeNovoPublishedBackgrounds(deNovoBackgrounds)
                            .prepareLocalInterpretation(job, restored.conversion(), restored.execution(), context,
                                    ranked, request.topN(), List.of(input), restored.candidateBlockers(),
                                    unifiedLocal && runProfile.criteria().contains(GeneralPm1Runtime.CRITERION));
                    response.set("candidates", mapper.valueToTree(selected.assembly().candidates()));
                    response.set("unmatchedInputs", mapper.valueToTree(selected.assembly().unmatchedInputs()));
                    response.putArray("bundles");
                    var pp1Selected = localBatch.hasPp1() ? selectedCoordinator.preparePp1Selection(job,
                            restored.conversion(), restored.execution(), context, ranked, request.topN(),
                            List.of(input), restored.candidateBlockers()) : selected;
                    ObjectNode combined;
                    var nativeFactory=localBatch.currentPp1Factory();
                    if(localBatch.hasPp1() && nativeFactory.isPresent()) {
                        var factory=nativeFactory.orElseThrow();
                        var registered=factory.registeredInputs().orElseThrow(() -> new IllegalStateException("PP1_CURRENT_REGISTERED_INPUTS_REQUIRED"));
                        var session=CurrentAcmgEvaluationSession.issueAfterClaim(mapper,store,analysisId,id,hash,pp1Selected,ranked);
                        var claimedRecovery=restored; var claimedBatch=localBatch;
                        combined=claimedBatch.execute(selected,context,claimedRecovery.snapshotSha256(),pp1Selected,session,execution -> {
                            try {
                                var currentUse=CurrentClinicalAllocation.prepareAfterClaim(mapper,store,session,pp1Selected,
                                        claimedRecovery,ranked,registered,execution);
                                factory.bindCurrentUseSource(currentUse);
                                var inventory=CurrentAdoptionInventory.capture(session,factory.producer());
                                var archive=CurrentClinicalArchive.capture(mapper,session,registered,inventory);
                                // The exact archive is validated before PP1 can consume a complete
                                // inventory. Archive and result commit atomically in the existing
                                // run-private large-JSON storage, after all scoring finishes.
                                privateData.set("currentClinicalArchive",archive);
                                privateData.set("currentClinicalInventory",inventory.snapshot());
                                privateData.set("currentClinicalUse",currentUse.snapshot());
                                response.put("currentClinicalArchiveSha256",archive.path("archiveSha256").asText());
                                claimedBatch.bindCurrentPp1(session,inventory);
                            } catch(java.io.IOException invalid) {
                                throw new IllegalStateException("PP1_CURRENT_CLINICAL_PREPARATION_FAILED",invalid);
                            }
                        });
                    } else if (localBatch.canSealPp4Registry() && !executionProfile.criteria().contains("ACMG_AMP_2015:PP4")) {
                        // Only a claimed run with verified input coverage can attest a closed registry.
                        // A partially prepared run still retains its scoped PP1 materials and missing reason.
                        CurrentAcmgEvaluationSession session;
                        try { session=CurrentAcmgEvaluationSession.issueAfterClaim(mapper,store,analysisId,id,hash,pp1Selected,ranked); }
                        catch (IllegalArgumentException unavailable) { session=null; }
                        if (session == null) combined=localBatch.execute(selected,context,restored.snapshotSha256(),pp1Selected);
                        else {
                            var claimedSession=session;
                            var claimedBatch=localBatch;
                            combined=claimedBatch.execute(selected,context,restored.snapshotSha256(),pp1Selected,claimedSession,
                                    execution -> claimedBatch.sealPp4Registry(claimedSession,execution));
                        }
                    } else combined=localBatch.execute(selected,context,restored.snapshotSha256(),pp1Selected);
                    privateData.set("localBatch", combined);
                    privateData.set("sourceProduction", selected.provenance());
                    privateData.set("sourceReceipts", combined.path("receipts").deepCopy());
                    var publicBatch = LocalEvidenceBatchRuntime.publicView(mapper, combined);
                    response.set("localBatch", publicBatch);
                    attachLocalBatch(response, publicBatch);
                    for (var row : response.path("candidates")) for (var attempt : row.path("attempts")) {
                        var original = ranked.stream().filter(c -> c.getCandidateId().equals(row.path("candidateId").asText())).findFirst();
                        var hypothesis = effectiveHypotheses.stream().filter(h -> h != null && h.hypothesis() != null
                                && Objects.equals(h.hypothesis().candidateId(), row.path("candidateId").asText())
                                && Objects.equals(h.hypothesis().hypothesisId(), attempt.path("hypothesisId").asText())).findFirst();
                        if (original.isPresent() && hypothesis.isPresent()) ((ObjectNode) attempt).set("interpretationContext",
                                mapper.valueToTree(LocalInterpretationContextPolicy.assess(original.get(), hypothesis.get().hypothesis().moi())));
                    }
                    response.put("status", combined.path("status").asText("COMPLETED_WITH_BLOCKERS"));
                    // Module-native counters are not comparable transport counts.
                    response.putNull("providerCalls").putNull("evaluatorCalls");
                    response.set("localBatchProviderCounts", combined.path("providerCounts"));
                    if (unifiedLocal && (runProfile.criteria().contains("ACMG_AMP_2015:BA1") || runProfile.criteria().contains("ACMG_AMP_2015:BS1"))) {
                        AcmgExecutionProgress.stage("POPULATION_CONTEXT", null, null, "BA1 / BS1");
                        // Independent context builders preserve the existing population contracts and ledgers.
                        var population = coordinator.assemblePopulation(job, restored.conversion(), restored.execution(), context,
                                ranked, request.topN(), List.of(input), restored.candidateBlockers());
                        mergePopulationContexts(response, mapper.valueToTree(population.assembly().candidates()), "ba1");
                        privateData.set("populationSourceProduction", population.production().provenance());
                        var populationReceipts = privateData.putArray("populationSourceReceipts");
                        population.sources().forEach(source -> populationReceipts.add(source.receipt(mapper)));
                        if (runProfile.criteria().contains("ACMG_AMP_2015:BS1")) {
                        var bs1 = coordinator.assembleBs1(job, restored.conversion(), restored.execution(), context,
                                ranked, request.topN(), List.of(input), restored.candidateBlockers(), true);
                        mergePopulationContexts(response, mapper.valueToTree(bs1.assembly().candidates()), "bs1");
                        privateData.set("bs1SourceProduction", bs1.production().provenance());
                        var bs1Receipts = privateData.putArray("bs1SourceReceipts");
                        bs1.sources().forEach(source -> bs1Receipts.add(source.receipt(mapper)));
                        }
                    }
                } else if (selectedPvs1()) {
                    var selected = coordinator.prepareSelected(job, restored.conversion(), restored.execution(), context,
                            ranked, request.topN(), List.of(input), restored.candidateBlockers());
                    response.set("candidates", mapper.valueToTree(selected.assembly().candidates()));
                    response.set("unmatchedInputs", mapper.valueToTree(selected.assembly().unmatchedInputs()));
                    response.putArray("bundles");
                    response.put("providerCalls", 0).put("evaluatorCalls", 0).put("status", "COMPLETED");
                    privateData.set("sourceProduction", selected.provenance());
                    var receipts = privateData.putArray("sourceReceipts");
                    selected.sources().forEach(source -> receipts.add(source.receipt(mapper)));
                    appendSelectedPvs1(selected, restored.snapshotSha256(), response, privateData);
                } else if (populationOnly) {
                    var prepared = bs1Runtime == null || ordinaryBs1
                            ? coordinator.assemblePopulation(job, restored.conversion(), restored.execution(), context,
                                ranked, request.topN(), List.of(input), restored.candidateBlockers())
                            : coordinator.assembleBs1(job, restored.conversion(), restored.execution(), context,
                                ranked, request.topN(), List.of(input), restored.candidateBlockers());
                    response.set("candidates", mapper.valueToTree(prepared.assembly().candidates()));
                    if (ordinaryBs1) {
                        var bs1Prepared = coordinator.assembleBs1(job, restored.conversion(), restored.execution(), context,
                                ranked, request.topN(), List.of(input), restored.candidateBlockers(), localBs1());
                        var bs1Rows = mapper.valueToTree(bs1Prepared.assembly().candidates());
                        for (var row : response.path("candidates")) for (var attempt : row.path("attempts")) {
                            for (var bs1Row : bs1Rows) if (row.path("candidateId").equals(bs1Row.path("candidateId")))
                                for (var bs1Attempt : bs1Row.path("attempts")) if (attempt.path("hypothesisId").equals(bs1Attempt.path("hypothesisId"))) {
                                    ((ObjectNode) attempt).set("bs1Context", bs1Attempt.path("context"));
                                    ((ObjectNode) attempt).set("bs1ContextBlockers", bs1Attempt.path("blockers"));
                                }
                        }
                        privateData.set("bs1SourceProduction", bs1Prepared.production().provenance());
                        var bs1Receipts = privateData.putArray("bs1SourceReceipts");
                        bs1Prepared.sources().forEach(source -> bs1Receipts.add(source.receipt(mapper)));
                    }
                    response.set("unmatchedInputs", mapper.valueToTree(prepared.assembly().unmatchedInputs()));
                    response.put("providerCalls", 0).put("evaluatorCalls", 0).put("status", "COMPLETED");
                    response.putArray("bundles");
                    privateData.set("sourceProduction", prepared.production().provenance());
                    var receipts = privateData.putArray("sourceReceipts");
                    prepared.sources().forEach(s -> receipts.add(s.receipt(mapper)));
                    var revel = revelApplication;
                    if (revel != null) {
                        privateData.set("predictionContextInputs", mapper.valueToTree(prepared.production().inputs()));
                        revel.prepare(prepared.assembly(), ranked, response);
                    }
                } else {
                var result = coordinator.dispatch(job, restored.conversion(), restored.execution(), context, ranked, request.topN(), List.of(input), restored.candidateBlockers());
                var dispatch = result.adapterResult().dispatch();
                response.set("result", mapper.valueToTree(result));
                response.set("candidates", mapper.valueToTree(dispatch.candidates()));
                response.set("unmatchedInputs", mapper.valueToTree(dispatch.preflight().unmatchedInputs()));
                response.put("providerCalls", dispatch.providerCalls()).put("evaluatorCalls", dispatch.evaluatorCalls());
                response.set("bundles", artifacts.bundles(dispatch, executionProfile.contextCriteria()));
                boolean blocked = !dispatch.preflight().unmatchedInputs().isEmpty() || dispatch.candidates().stream().flatMap(c -> c.attempts().stream())
                        .anyMatch(a -> !a.blockers().isEmpty() || !AcmgExecutionProfile.evaluationsMatch(a.criterionEvaluations(), AcmgExecutionProfile.contextCriteria(a.transcriptInput()))
                                || a.criterionEvaluations().stream().anyMatch(e -> "NOT_EVALUATED".equals(e.at("/evaluationResult/status").asText())));
                response.put("status", blocked ? "COMPLETED_WITH_BLOCKERS" : "COMPLETED");
                privateData.set("sourceProduction", result.production().provenance());
                privateData.set("candidateInputProvenance", result.adapterResult().inputProvenance());
                privateData.set("declarationIntake", mapper.valueToTree(result.declarations()));
                var receipts = privateData.putArray("sourceReceipts"); result.sources().forEach(s -> receipts.add(s.receipt(mapper)));
                }
            }
            explainMissingHypotheses(request, verifiedRecommendations, response);
            if (localBatch != null && sourceFailure != null) {
                localBatchSourceFailure(response, sourceFailure);
                if (requestIdentity.at("/fullRnaSourceSnapshot/archiveRequired").asBoolean()
                        || requestIdentity.at("/pp2OtherPhenotypeSourceSnapshot/observationSha256").asText().matches("[a-f0-9]{64}")) {
                    // A selected RNA source was observed before the request was claimed,
                    // but no scientific evaluator ran after source/runtime recovery failed.
                    var notExecuted = localBatch.notExecutedFullRnaSnapshot(
                            restored == null ? "UNAVAILABLE" : restored.snapshotSha256(), sourceFailure);
                    privateData.set("localBatch", notExecuted);
                    response.set("localBatch", LocalEvidenceBatchRuntime.publicView(mapper, notExecuted));
                }
            }
            if (localBs1()) for (var row : response.path("candidates")) for (var attempt : row.path("attempts")) {
                if (!"EXPLICIT_INPUT_MISSING".equals(attempt.at("/bs1ContextBlockers/0/reasonCode").asText())) continue;
                for (var recommendation : verifiedRecommendations) if (row.path("candidateId").equals(recommendation.path("candidateId"))) {
                    var preparation = recommendation.path("bs1ContextPreparation");
                    String reason = preparation.path("reasonCode").asText("BS1_CONTEXT_CONFIRMATION_REQUIRED");
                    ((ObjectNode) attempt).putArray("bs1ContextBlockers").addObject().put("status", "CONTEXT_NOT_READY")
                            .put("reasonCode", reason).put("field", "bs1ContextPreparation");
                    ((ObjectNode) attempt).set("bs1ContextPreparation", preparation.deepCopy());
                }
            }
            if (predictionRuntime != null) appendPrediction(request, ranked, restored, response, effectSnapshot);
            if (enabled("BP7") && localBatchFactory == null) describeBp7Scope(response, ranked, restored);
            if (localBatch == null && enabled("PM2")) appendPm2(job, request, ranked, restored, response, privateData, effectiveHypotheses);
            if (enabled("BS1")) {
                if (bs1Runtime != null) { if (!localBs1()) appendBs1(job, ranked, restored, response); }
                else { appendHnf1aFrequency(restored, response); appendPopulationFrequency(restored, response); }
            }
            if (localBatch == null && enabled("PVS1") && !(selectedPvs1() && sourceFailure == null)) appendPvs1(ranked, restored, response);
            if (generalBp1()) appendBp1(ranked, restored, response);
            if (generalPp2()) appendPp2(ranked, restored, response);
            if (generalBp3()) appendBp3(ranked, restored, response);
            if (localBatch == null && enabled("PM4")) appendPm4(ranked, restored, response);
            if (localBatch == null && enabled("PM1")) appendPm1(ranked, restored, response);
            if (localBatch == null && (enabled("PS1") || enabled("PM5"))) appendMissense(ranked, restored, response);
            if (enabled("PS3")) appendFunctional(ranked, restored, response);
            AcmgExecutionSources.Recovered recovered = restored;
            AcmgBusinessStore.Source recoveredOriginal = restoredOriginal;
            var ba1Hypotheses = effectiveHypotheses;
            AcmgRunControl.checkpoint(); // Pause before acquiring a database publication lock.
            store.withAnalysisLock(analysisId, () -> {
                // Different declarations may finish models together. Serialize BA1 lookup/evaluation with
                // publication so the next declaration sees the committed allele evidence and reuses it.
                AcmgExecutionProgress.stage("EVALUATE", null, null, "BA1 / BS1");
                // Acquire neutral facts once for each trusted dataset/profile and allele.
                // BA1 consumes matching facts independently of the BS1 verdict.
                if (routedPopulation() && runProfile.criteria().contains("ACMG_AMP_2015:BS1")) appendBs1(job, ranked, recovered, response);
                if (runProfile.criteria().contains("ACMG_AMP_2015:BA1") || !routedPopulation() && unifiedLocal && runProfile.criteria().contains("ACMG_AMP_2015:BS1"))
                    appendBa1(job, request, ranked, recovered, response, privateData, ba1Hypotheses);
                if (!routedPopulation() && localBs1() && runProfile.criteria().contains("ACMG_AMP_2015:BS1")) appendBs1(job, ranked, recovered, response);
                if (unifiedLocal) {
                    finishCurrentScope(response, privateData);
                    response.putObject("populationCalls").set("provider", response.path("providerCalls").deepCopy());
                    response.withObject("/populationCalls").set("evaluator", response.path("evaluatorCalls").deepCopy());
                    // Population and module-native counters have different units; never report a misleading total.
                    response.putNull("providerCalls").putNull("evaluatorCalls");
                }
                if (populationOnly && localBatchFactory == null && predictionRuntime == null && revelApplication == null) finishCurrentScope(response, privateData);
                appendSampleCallSummaries(response, recovered, recoveredOriginal);
                try {
                    AcmgExecutionProgress.stage("SAVE", null, null, null);
                    String privateJson = mapper.writeValueAsString(privateData);
                    response.put("privateArtifactSha256", AcmgBusinessStore.sha(privateJson));
                    store.finish(id, response.path("status").asText(), mapper.writeValueAsString(response), privateJson);
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                    throw new IllegalStateException("ACMG_RESULT_SERIALIZATION_FAILED", invalid);
                }
                return null;
            });
            // The transaction has committed. A commit failure must never return an uncommitted result.
            AcmgExecutionProgress.committed(analysisId, id, response.path("status").asText());
            control.close(); // A late pause must not suspend delivery of an already committed result.
            return read(analysisId, id);
        } catch (AcmgRunControl.Stopped stopped) {
            store.endControlled(id, "CANCELLED");
            return read(analysisId, id);
        } catch (Exception failed) {
            // The failed transaction has been cleaned up; use a fresh connection to persist FAILED.
            log.error("ACMG execution or persistence failed: analysisId={}, runId={}", analysisId, id, failed);
            try { store.failed(id); }
            catch (Exception unavailable) { log.error("ACMG failure status could not be saved: runId={}", id, unavailable); }
            throw persistenceFailure(failed, "ACMG_EXECUTION_OR_SAVE_FAILED");
        }
    }
    private void attachLocalBatch(ObjectNode response, ObjectNode batch) {
        for (var candidate : response.path("candidates")) {
            var attempts = (com.fasterxml.jackson.databind.node.ArrayNode) candidate.path("attempts");
            for (var result : batch.path("results")) {
                if (!candidate.path("candidateId").equals(result.path("candidateId"))) continue;
                ObjectNode found = null;
                for (var attempt : attempts) if (Objects.equals(attempt.path("hypothesisId").asText(null), result.path("hypothesisId").asText(null))) {
                    if (found != null) throw new IllegalStateException("LOCAL_BATCH_DUPLICATE_ATTEMPT");
                    found = (ObjectNode) attempt;
                }
                if (found == null && result.path("hypothesisId").isNull() && attempts.isEmpty())
                    found = attempts.addObject().putNull("hypothesisId").putNull("context");
                if (found == null) throw new IllegalStateException("LOCAL_BATCH_ATTEMPT_NOT_MATCHED");
                found.set("localBatch", result.path("localBatch").deepCopy());
            }
        }
    }
    private void mergePopulationContexts(ObjectNode response, JsonNode populationRows, String prefix) {
        for (var row : response.path("candidates")) for (var attempt : row.path("attempts")) {
            var target = (ObjectNode) attempt;
            target.set("candidateId", row.path("candidateId"));
            target.putNull(prefix + "Context");
            target.putArray(prefix + "ContextBlockers").addObject().put("reasonCode", "EXPLICIT_INPUT_MISSING");
            int matches = 0;
            for (var populationRow : populationRows) if (row.path("candidateId").equals(populationRow.path("candidateId")))
                for (var source : populationRow.path("attempts"))
                    if (Objects.equals(attempt.path("hypothesisId").asText(null), source.path("hypothesisId").asText(null))) {
                        if (++matches > 1) throw new IllegalStateException("UNIFIED_POPULATION_CONTEXT_AMBIGUOUS");
                        target.set(prefix + "Context", source.path("context").deepCopy());
                        target.set(prefix + "ContextBlockers", source.path("blockers").deepCopy());
                    }
        }
    }
    private void localBatchSourceFailure(ObjectNode response, String reason) {
        var batch = mapper.createObjectNode().put("schemaVersion", "local-evidence-batch-public-v1")
                .put("status", "COMPLETED_WITH_BLOCKERS").put("reasonCode", reason);
        var results = batch.putArray("results");
        for (var candidate : response.path("candidates")) for (var attempt : candidate.path("attempts")) {
            var row = results.addObject().put("candidateId", candidate.path("candidateId").asText())
                    .put("hypothesisId", attempt.path("hypothesisId").asText(null));
            var local = row.putObject("localBatch").put("schemaVersion", "local-batch1-attempt-v1")
                    .put("candidateId", candidate.path("candidateId").asText())
                    .put("hypothesisId", attempt.path("hypothesisId").asText(null)).putNull("contextId");
            var criteria = local.putArray("criteria");
            for (String criterion : responseCriteria(response)) if (!unifiedLocal || !Set.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1").contains(criterion))
                criteria.addObject().put("criterionId", criterion)
                    .put("status", "NOT_EVALUATED").put("appliedStrength", "NONE").put("reasonCode", reason)
                    .put("reason", "正式来源或运行配置尚不可用，本条证据未执行。").putObject("details");
        }
        response.set("localBatch", batch); attachLocalBatch(response, batch);
    }
    private List<String> responseCriteria(ObjectNode response) {
        return mapper.convertValue(response.path("enabledCriteria"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
    }
    private boolean enabled(String code) { return executionProfile.criteria().contains("ACMG_AMP_2015:" + code); }

    private ObjectNode calibrateSaved(VariantAnalysisJob job, AcmgSubmission request) {
        var calibration = savedCalibration;
        if (calibration == null) throw error(HttpStatus.BAD_REQUEST, "SAVED_CALIBRATION_OPERATION_NOT_ENABLED");
        ObjectNode declaration;
        try { declaration = calibration.declaration(request, job); }
        catch (IllegalArgumentException invalid) { throw error(HttpStatus.BAD_REQUEST, invalid.getMessage()); }
        String analysisId = job.getId();
        ObjectNode identity = mapper.createObjectNode().set("declaration", declaration);
        identity.set("executionProfile", executionProfile.document()); identity.set("executionRoot", calibration.identity());
        String hash = artifacts.requestHash(identity);
        var previous = store.byRequest(analysisId, hash);
        if (previous.isPresent()) return read(analysisId, previous.get().id());
        // A representation-contract revision must not grant another attempt for the same saved source.
        var priorRuns = store.effectScopeRuns(analysisId);
        SavedCalibrationBusiness.require(priorRuns.size() <= 1000, "CALIBRATION_PRIOR_RUN_INDEX_LIMIT");
        for (var prior : priorRuns) {
            try {
                var priorRequest = mapper.readTree(prior.requestJson());
                var ref = priorRequest.path("declaration").path("calibration");
                if (SavedCalibrationBusiness.OPERATION.equals(ref.path("operation").asText())
                        && calibration.sourceId().equals(ref.path("sourceRunId").asText()))
                    return read(analysisId, prior.id());
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw error(HttpStatus.SERVICE_UNAVAILABLE, "ACMG_SAVED_RESULT_INTEGRITY_FAILURE");
            }
        }
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        try {
            if (!store.claim(new AcmgBusinessStore.Run(id, analysisId, hash, mapper.writeValueAsString(identity), "RUNNING", now, null, null, null)))
                return read(analysisId, store.byRequest(analysisId, hash).orElseThrow().id());
        } catch (Exception failed) { throw persistenceFailure(failed, "ACMG_RUN_CLAIM_FAILED"); }
        // Every archive/source load, prepare and comparison is after the durable unique claim.
        var result = mapper.createObjectNode().put("runId", id).put("analysisId", analysisId).put("createdAt", now)
                .put("declarationVersion", SavedCalibrationBusiness.DECLARATION).put("requestSha256", hash).put("dataClass", "SYNTHETIC")
                .put("operation", SavedCalibrationBusiness.OPERATION).put("sourceRunId", calibration.sourceId()).put("rawAncestorRunId", calibration.ancestorId())
                .put("executionSourceSha256", calibration.reference().path("executionSourceSha256").asText())
                .put("requestedN", 2).put("selectedCount", 2).put("candidateOrderingChanged", false).put("automaticTrigger", false)
                .put("clinicalValidationCompleted", false).put("status", "COMPLETED_WITH_BLOCKERS")
                .put("providerCalls", 0).put("evaluatorCalls", 0).put("newProviderInvocations", 0).put("newTransportAttempts", 0)
                .put("sourceObservationsRead", 0).put("associationEvaluations", 0).put("calibrationPrepares", 0).put("calibrationComparisons", 0).put("calibratedMechanisms", 0);
        result.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria())); result.set("executionProfile", executionProfile.document());
        var rows = result.putArray("candidates"); var candidateIds = result.putArray("candidateIds");
        result.putArray("unmatchedInputs"); result.putArray("bundles");
        result.putArray("applicationBlockers").add("SPLICING_MECHANISM_NOT_EVALUATED").add("DISEASE_APPLICABILITY_NOT_EVALUATED").add("APPLICATION_ADOPTION_NOT_EVALUATED");
        ObjectNode privateData = mapper.createObjectNode().set("declaration", declaration);
        privateData.set("executionRoot", calibration.identity());
        String stage = "SAVED_SOURCE_READ";
        try {
            var source = trustedRead(analysisId, calibration.sourceId()); var ancestor = trustedRead(analysisId, calibration.ancestorId());
            var stored = store.source(analysisId).orElseThrow(() -> new IllegalArgumentException("EXECUTION_SOURCE_UNAVAILABLE"));
            var ranked = formalCandidates(analysisId, 2);
            stage = "EXECUTION_SOURCE_RECOVERY";
            var recovered = sources.recover(job, ranked, stored);
            SavedCalibrationBusiness.require(recovered.candidateBlockers().isEmpty(), "CALIBRATION_EXECUTION_CANDIDATE_BLOCKED");
            stage = "SAVED_SUMMARY_READ";
            JsonNode summaries = mapper.valueToTree(jobs.findInterpretationSummaries(analysisId, 0, 2,
                    VariantInterpretationView.CANDIDATES, VariantInterpretationFilter.empty()));
            stage = "ARCHIVE_LOAD"; var reader = calibration.load();
            stage = "DUAL_SOURCE_VERIFICATION"; var representation = calibration.verify(reader, source, ancestor, stored, job, summaries);
            var provenance = mapper.createObjectNode().put("status", "CURRENT_SAVED_SOURCE_VERIFIED_FIXED_ARCHIVE_COMPARISON")
                    .put("sourceResponseStringSha256", source.record().responseSha256()).put("rawResponseStringSha256", ancestor.record().responseSha256())
                    .put("sourcePrivateStringSha256", AcmgBusinessStore.sha(source.record().privateJson()))
                    .put("rawPrivateStringSha256", AcmgBusinessStore.sha(ancestor.record().privateJson())).put("executionSourceStringSha256", stored.sha256());
            provenance.set("archiveByteSha256", calibration.identity().path("archiveByteSha256"));
            provenance.set("representationVerification", representation);
            privateData.set("sourceVerification", provenance.deepCopy());
            var completed = new LinkedHashMap<String, ObjectNode>();
            try {
                for (String candidate : calibration.candidates()) {
                    result.put("sourceObservationsRead", result.path("sourceObservationsRead").asInt() + 1);
                    stage = "PREPARE"; result.put("activeCandidateId", candidate);
                    result.put("calibrationPrepares", result.path("calibrationPrepares").asInt() + 1);
                    var verified = reader.prepare(candidate);
                    stage = "COMPARE"; result.put("calibrationComparisons", result.path("calibrationComparisons").asInt() + 1);
                    var comparison = PredictionClauseRuntime.calibrateStandard(mapper, verified);
                    completed.put(candidate, calibration.result(source.response(), candidate, comparison, provenance));
                    result.put("calibratedMechanisms", result.path("calibratedMechanisms").asInt() + 1);
                }
            } finally {
                // Public order remains the existing ranking; partial work is retained on failure.
                for (var candidate : ranked) {
                    var prediction = completed.get(candidate.getCandidateId()); if (prediction == null) continue;
                    candidateIds.add(candidate.getCandidateId());
                    var attempt = rows.addObject().put("candidateId", candidate.getCandidateId()).put("rank", ranked.indexOf(candidate) + 1).putArray("attempts").addObject()
                            .put("candidateId", candidate.getCandidateId()).putNull("hypothesisId").putNull("context");
                    attempt.putArray("blockers"); attempt.set("prediction", prediction);
                }
            }
            result.remove("activeCandidateId");
        } catch (Exception failed) {
            String reported = failed instanceof ResponseStatusException status ? status.getReason() : failed.getMessage();
            String reason = reported != null && reported.matches("[A-Za-z0-9_]{1,160}") ? reported : "SAVED_CALIBRATION_STAGE_FAILED";
            result.put("status", "FAILED"); result.putObject("executionFailure").put("stage", stage).put("reasonCode", reason)
                    .put("exceptionType", failed.getClass().getSimpleName());
            if (failed instanceof SavedCalibrationRepresentations.Failure representation) {
                var failure = (ObjectNode) result.path("executionFailure");
                failure.put("candidateIndex", representation.candidateIndex()).put("fieldPath", representation.fieldPath());
                int index = representation.candidateIndex();
                if (index >= 0 && index < calibration.candidates().size()) failure.put("candidateId", calibration.candidates().get(index));
            }
        }
        result.putObject("predictionCalls").put("provider", 0).put("newTransportAttempts", 0).put("associationEvaluations", 0)
                .put("sourceObservationsRead", result.path("sourceObservationsRead").asInt()).put("prepare", result.path("calibrationPrepares").asInt())
                .put("comparison", result.path("calibrationComparisons").asInt()).put("calibratedMechanisms", result.path("calibratedMechanisms").asInt());
        try {
            String privateJson = mapper.writeValueAsString(privateData); result.put("privateArtifactSha256", AcmgBusinessStore.sha(privateJson));
            store.finish(id, result.path("status").asText(), mapper.writeValueAsString(result), privateJson);
        } catch (Exception failed) { throw persistenceFailure(failed, "ACMG_CALIBRATION_SAVE_FAILED"); }
        return read(analysisId, id);
    }

    private ObjectNode associateSaved(VariantAnalysisJob job, AcmgSubmission request) {
        var association = savedAssociation;
        if (association == null) throw error(HttpStatus.BAD_REQUEST, "SAVED_OBSERVATION_OPERATION_NOT_ENABLED");
        var reference = request.association();
        if (!SavedPredictionAssociation.DECLARATION.equals(request.declarationVersion()) || reference == null
                || !SavedPredictionAssociation.OPERATION.equals(reference.operation()) || request.retryOf() != null
                || request.recommendations() != null || !request.hypotheses().isEmpty()
                || reference.selections() == null || reference.selections().isEmpty() || reference.selections().size() > 2)
            throw error(HttpStatus.BAD_REQUEST, "SAVED_OBSERVATION_DECLARATION_INVALID");
        String analysisId = job.getId();
        if (!Objects.equals(request.sampleId(), job.getSelectedSampleId() == null ? job.getRequestedSampleId() : job.getSelectedSampleId()))
            throw error(HttpStatus.CONFLICT, "SAVED_OBSERVATION_SAMPLE_MISMATCH");
        // read verifies response, request and private provenance hashes and analysis ownership.
        var parent = read(analysisId, reference.sourceRunId());
        var ranked = formalCandidates(analysisId, request.topN());
        AcmgExecutionSources.Recovered recovered;
        try { recovered = sources.recover(job, ranked); }
        catch (Exception invalid) { throw error(HttpStatus.CONFLICT, "SAVED_OBSERVATION_EXECUTION_SOURCE_UNAVAILABLE"); }
        var options = inputOptions(analysisId, request.topN());
        var prepared = new LinkedHashMap<String, SavedPredictionAssociation.Plan>();
        try {
            for (var selection : reference.selections()) {
                if (selection == null || selection.candidateId() == null || prepared.containsKey(selection.candidateId()))
                    throw new IllegalArgumentException("SAVED_OBSERVATION_DUPLICATE_OR_MISSING_CANDIDATE");
                var candidate = ranked.stream().filter(c -> selection.candidateId().equals(c.getCandidateId())).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("SAVED_OBSERVATION_CANDIDATE_NOT_SELECTED"));
                if (recovered.candidateBlockers().containsKey(candidate.getCandidateId()))
                    throw new IllegalArgumentException("SAVED_OBSERVATION_CANDIDATE_SOURCE_BLOCKED");
                JsonNode choices = mapper.createArrayNode();
                for (var row : options.path("candidates")) if (selection.candidateId().equals(row.path("candidateId").asText())) choices = row.path("associationOptions");
                prepared.put(selection.candidateId(), association.prepare(parent, request, selection, candidate, choices, recovered.snapshotSha256()));
            }
        } catch (IllegalArgumentException invalid) { throw error(HttpStatus.CONFLICT, invalid.getMessage()); }
        JsonNode declaration = mapper.valueToTree(request);
        ObjectNode identity = mapper.createObjectNode().set("declaration", declaration);
        identity.set("executionProfile", executionProfile.document());
        String hash = artifacts.requestHash(identity);
        var previous = store.byRequest(analysisId, hash);
        if (previous.isPresent()) return read(analysisId, previous.get().id());
        store.withAnalysisLock(analysisId, () -> null);
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        try {
            if (!store.claim(new AcmgBusinessStore.Run(id, analysisId, hash, mapper.writeValueAsString(identity), "RUNNING", now, null, null, null)))
                return read(analysisId, store.byRequest(analysisId, hash).orElseThrow().id());
        } catch (Exception failed) { throw persistenceFailure(failed, "ACMG_RUN_CLAIM_FAILED"); }
        try {
            var result = mapper.createObjectNode().put("runId", id).put("analysisId", analysisId).put("createdAt", now)
                    .put("declarationVersion", request.declarationVersion()).put("requestSha256", hash).put("dataClass", request.dataClass())
                    .put("operation", SavedPredictionAssociation.OPERATION).put("sourceRunId", reference.sourceRunId())
                    .put("executionSourceSha256", recovered.snapshotSha256()).put("requestedN", request.topN()).put("selectedCount", prepared.size())
                    .put("candidateOrderingChanged", false).put("automaticTrigger", false).put("clinicalValidationCompleted", false)
                    .put("status", "COMPLETED_WITH_BLOCKERS").put("providerCalls", 0).put("evaluatorCalls", 0)
                    .put("newProviderInvocations", 0).put("newTransportAttempts", 0).put("sourceObservationsRead", prepared.size())
                    .put("associationEvaluations", prepared.size()).put("calibratedMechanisms", 0);
            result.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria())); result.set("executionProfile", executionProfile.document());
            var ids = result.putArray("candidateIds"); var rows = result.putArray("candidates");
            ObjectNode privateData = mapper.createObjectNode().set("declaration", declaration);
            var plans = privateData.putArray("verifiedAssociationPlans");
            for (var candidate : ranked) {
                var plan = prepared.get(candidate.getCandidateId()); if (plan == null) continue;
                ids.add(candidate.getCandidateId()); plans.add(plan.snapshot());
                var row = rows.addObject().put("candidateId", candidate.getCandidateId());
                var attempt = row.putArray("attempts").addObject().put("candidateId", candidate.getCandidateId()).putNull("hypothesisId").putNull("context");
                attempt.putArray("blockers"); attempt.set("prediction", association.execute(plan));
            }
            result.putArray("unmatchedInputs"); result.putArray("bundles");
            result.putArray("applicationBlockers").add("SPEC_APPLICABILITY_UNRESOLVED_CONTEXT_MISSING");
            result.putObject("predictionCalls").put("provider", 0).put("plans", prepared.size()).put("calibratedMechanisms", 0)
                    .put("newTransportAttempts", 0).put("sourceObservationsRead", prepared.size()).put("associationEvaluations", prepared.size());
            store.withAnalysisLock(analysisId, () -> {
                try {
                    String privateJson = mapper.writeValueAsString(privateData);
                    result.put("privateArtifactSha256", AcmgBusinessStore.sha(privateJson));
                    store.finish(id, result.path("status").asText(), mapper.writeValueAsString(result), privateJson);
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
                return null;
            });
            return read(analysisId, id);
        } catch (Exception failed) {
            store.failed(id); throw persistenceFailure(failed, "ACMG_ASSOCIATION_OR_SAVE_FAILED");
        }
    }

    /** Live, source-bound dispatch may finish an inapplicable branch without running its
     * specialized acquisition. Keep the original NOT_EVALUATED result and science intact. */
    static boolean completedRoutingExclusion(JsonNode result, JsonNode attempt) {
        var route = result.at("/details/applicabilityRouting");
        var context = attempt.path("context");
        if (!"NOT_EVALUATED".equals(result.path("status").asText())
                || !"NONE".equals(result.path("appliedStrength").asText())
                || !"NOT_REQUIRED".equals(result.at("/details/materialPreparation/status").asText())
                || !result.at("/details/originalResultSha256").asText().matches("[a-f0-9]{64}")
                || !"NOT_APPLICABLE".equals(route.path("status").asText())
                || !route.path("skipSpecializedAcquisition").isBoolean()
                || !route.path("skipSpecializedAcquisition").asBoolean()
                || !attempt.path("blockers").isArray() || !attempt.path("blockers").isEmpty()
                || !context.isObject() || context.path("contextId").asText().isBlank()) return false;
        String id = result.path("criterionId").asText();
        String code = id.startsWith("ACMG_AMP_2015:") ? id.substring("ACMG_AMP_2015:".length()) : "";
        String reason = result.path("reasonCode").asText();
        if (!reason.equals(route.path("reasonCode").asText()) || !reason.equals(result.at("/details/materialPreparation/reasonCode").asText())) return false;
        if ("PM3".equals(code) && "ROUTING_PM3_NON_RECESSIVE_CONTEXT".equals(reason))
            return Set.of("AUTOSOMAL_DOMINANT", "X_LINKED_DOMINANT", "MITOCHONDRIAL").contains(context.path("moi").asText())
                    && context.path("contextId").equals(route.path("contextId")) && context.path("moi").equals(route.path("moi"));
        boolean recognized = Set.of("PM5", "PP2", "BP1").contains(code) && "ROUTING_MISSENSE_REQUIRED".equals(reason)
                || "PM5".equals(code) && SelectedCriterionRouting.isPublishedPm5Exclusion(route, context)
                || Set.of("PM4", "BP3").contains(code) && "ROUTING_NO_ANNOTATED_PROTEIN_LENGTH_CHANGE".equals(reason)
                || "BP7".equals(code) && IntronicSplicePolicy.notApplicable(reason);
        return recognized && context.path("contextId").equals(route.path("contextId"))
                && context.path("moi").equals(route.path("moi"))
                && !route.path("transcript").asText().isBlank() && !route.path("annotationVersion").asText().isBlank()
                && route.path("consequenceTerms").isArray() && !route.path("consequenceTerms").isEmpty();
    }

    /** A completed exclusive allocation is not an unfinished second evidence search.
     * Require this execution's private qualification and selected winning result;
     * the public shared-event reason alone never establishes completion. */
    static boolean completedDeNovoExclusiveAllocation(ObjectMapper mapper, JsonNode response, JsonNode candidate,
            JsonNode attempt, JsonNode opposite, JsonNode privateData) {
        try {
            String id = opposite.path("criterionId").asText();
            String winner = "ACMG_AMP_2015:PS2".equals(id) ? "PM6" : "ACMG_AMP_2015:PM6".equals(id) ? "PS2" : "";
            if (winner.isEmpty() || !"NOT_EVALUATED".equals(opposite.path("status").asText())
                    || !"NONE".equals(opposite.path("appliedStrength").asText())
                    || !("DENOVO_SHARED_EVENT_ASSIGNED_TO_" + winner).equals(opposite.path("reasonCode").asText())
                    || response.has("executionSourceFailure")
                    || !attempt.path("blockers").isArray() || !attempt.path("blockers").isEmpty()) return false;
            String winnerId = "ACMG_AMP_2015:" + winner;
            int selected = 0, oppositeSelected = 0;
            for (var criterion : response.path("enabledCriteria")) {
                if (winnerId.equals(criterion.asText())) selected++;
                if (id.equals(criterion.asText())) oppositeSelected++;
            }
            if (selected != 1 || oppositeSelected != 1 || !candidate.path("candidateId").equals(attempt.path("candidateId"))) return false;
            var full = privateData.path("localBatch"); var module = full.at("/modules/deNovo");
            String source = full.path("sourceSnapshotSha256").asText();
            var identity = full.at("/identity/deNovo");
            if (!source.matches("[a-f0-9]{64}") || !source.equals(response.path("executionSourceSha256").asText())
                    || !source.equals(module.path("sourceSnapshotSha256").asText())
                    || !full.path("analysisId").equals(response.path("analysisId"))
                    || !full.path("executionCriteria").equals(response.path("enabledCriteria"))
                    || !identity.isObject() || !identity.equals(response.at("/executionProfile/moduleBindings/deNovo"))
                    || !identity.equals(response.at("/localBatch/identity/deNovo"))) return false;
            JsonNode wrapper = null;
            for (var row : full.path("results")) if (row.path("candidateId").equals(candidate.path("candidateId"))
                    && row.path("hypothesisId").equals(attempt.path("hypothesisId"))) {
                if (wrapper != null) return false;
                wrapper = row;
            }
            if (wrapper == null || !wrapper.path("context").equals(attempt.path("context"))
                    || !wrapper.at("/localBatch/candidateId").equals(candidate.path("candidateId"))
                    || !wrapper.at("/localBatch/hypothesisId").equals(attempt.path("hypothesisId"))
                    || !wrapper.at("/localBatch/contextId").equals(attempt.at("/context/contextId"))) return false;
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            adapter.validateVersionedContext(attempt.path("context"));
            JsonNode adopted = uniqueLocalCriterion(attempt, winnerId);
            JsonNode privateWinner = uniqueLocalCriterion(wrapper, winnerId), privateOpposite = uniqueLocalCriterion(wrapper, id);
            if (adopted == null || privateWinner == null || privateOpposite == null
                    || !"MET".equals(adopted.path("status").asText())) return false;
            String pointer = opposite.at("/details/modulePointer").asText();
            String prefix = "/modules/deNovo/results/";
            if (!pointer.startsWith(prefix) || !pointer.substring(prefix.length()).matches("0|[1-9][0-9]{0,5}")
                    || !pointer.equals(adopted.at("/details/modulePointer").asText())
                    || !pointer.equals(privateWinner.at("/details/modulePointer").asText())
                    || !pointer.equals(privateOpposite.at("/details/modulePointer").asText())
                    || !module.path("results").isArray()) return false;
            int index = Integer.parseInt(pointer.substring(prefix.length()));
            if (index >= module.path("results").size()) return false;
            var raw = module.path("results").get(index); var qualified = raw.path("qualifiedResult");
            var result = qualified.path("result"); var summary = raw.path("publicSummary");
            if (!raw.path("version").equals(identity.path("version"))
                    || !qualified.path("version").equals(identity.path("qualifierVersion"))
                    || !adapter.canonicalSha256(attempt.path("context")).equals(qualified.path("contextSha256").asText())
                    || !qualified.path("blockers").isArray() || !qualified.path("blockers").isEmpty()
                    || !"MET".equals(result.path("status").asText()) || !winner.equals(result.path("code").asText())
                    || !adopted.path("appliedStrength").equals(result.path("strength"))
                    || !Set.of("SUPPORTING", "MODERATE", "STRONG", "VERY_STRONG").contains(result.path("strength").asText())
                    || !adopted.path("reasonCode").equals(result.path("reasonCode"))
                    || !summary.equals(adopted.at("/details/deNovoFacts"))
                    || !summary.equals(opposite.at("/details/deNovoFacts"))) return false;
            boolean counted = false;
            for (var event : result.path("events")) if ("COUNTED".equals(event.path("disposition").asText())) {
                for (var qualification : qualified.path("qualifications"))
                    if (event.path("eventId").equals(qualification.path("eventId"))
                            && qualification.path("reasons").isArray() && qualification.path("reasons").isEmpty()) counted = true;
            }
            if (!counted) return false;
            for (var pair : List.of(java.util.Map.entry(adopted, privateWinner), java.util.Map.entry(opposite, privateOpposite))) {
                var published = pair.getKey(); var original = pair.getValue();
                String code = published.path("criterionId").asText().substring("ACMG_AMP_2015:".length());
                var evaluated = raw.path("criteria").path(code);
                if (!published.path("criterionId").equals(evaluated.path("criterionId"))
                        || !published.path("status").equals(evaluated.at("/evaluationResult/status"))
                        || !published.path("reasonCode").equals(evaluated.at("/evaluationResult/reasonCode"))
                        || !published.path("appliedStrength").equals(evaluated.path("appliedStrength"))
                        || !original.at("/details/originalResult").equals(evaluated)
                        || !adapter.canonicalSha256(evaluated).equals(published.at("/details/originalResultSha256").asText())) return false;
            }
            var proof = summary.path("adoptionProof");
            if (!"denovo-adoption-proof-v1".equals(proof.path("schemaVersion").asText())
                    || !"COMPLETE".equals(proof.path("projectionStatus").asText())
                    || !"EXECUTED_QUALIFIED_OBSERVATIONS".equals(proof.path("basis").asText())
                    || !winner.equals(proof.path("selectedCriterion").asText())
                    || !proof.path("appliedStrength").equals(result.path("strength"))
                    || !proof.path("reasonCode").equals(result.path("reasonCode"))
                    || !proof.equals(qualified.path("adoptionProof"))) return false;
            if ("ADOPTED".equals(proof.path("status").asText())) {
                if (!proof.path("events").isArray() || proof.path("events").isEmpty()
                        || !proof.at("/policy/phenotypePolicySha256").equals(qualified.path("policySha256"))) return false;
            } else if (!"NOT_APPLICABLE".equals(proof.path("status").asText())
                    || !"SVI_POINTS".equals(proof.path("calculationMethod").asText())
                    || !proof.path("events").isArray() || !proof.path("events").isEmpty()) return false;
            var bindings = new HashSet<String>();
            for (var binding : raw.path("calculationBindings")) {
                String boundId = binding.path("criterionId").asText();
                if (!Set.of(id, winnerId).contains(boundId) || !bindings.add(boundId)) return false;
                if (!binding.path("runtimeCalculatorVersion").equals(result.path("calculatorVersion"))
                        || !binding.path("executedCalculatorVersion").equals(result.path("calculatorVersion"))
                        || !binding.path("runtimeCalculatorVersion").equals(identity.path("calculatorVersion"))
                        || binding.path("declaredCalculatorVersion").asText().isBlank()
                        || !binding.path("phenotypePolicySha256").equals(qualified.path("policySha256"))
                        || !binding.path("selectorPolicySha256").asText().matches("[a-f0-9]{64}")) return false;
            }
            return bindings.size() == 2 && summary.path("calculationBindings").equals(raw.path("calculationBindings"));
        } catch (RuntimeException invalid) { return false; }
    }
    private static JsonNode uniqueLocalCriterion(JsonNode attempt, String id) {
        JsonNode found = null;
        for (var row : attempt.at("/localBatch/criteria")) if (id.equals(row.path("criterionId").asText())) {
            if (found != null) return null;
            found = row;
        }
        return found;
    }

    private void finishCurrentScope(ObjectNode response, ObjectNode privateData) {
        boolean blocked = response.has("executionSourceFailure") || !response.path("unmatchedInputs").isEmpty();
        for (var row : response.path("candidates")) {
            boolean complete = true;
            for (String criterion : responseCriteria(response)) {
                if (unifiedLocal && !Set.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1").contains(criterion)) {
                    complete &= !row.path("attempts").isEmpty();
                    for (var attempt : row.path("attempts")) {
                        int matches = 0;
                        for (var result : attempt.at("/localBatch/criteria")) if (criterion.equals(result.path("criterionId").asText())) {
                            matches++;
                            complete &= Set.of("MET", "NOT_MET", "NOT_EVALUATED").contains(result.path("status").asText())
                                    || LocalEvidenceBatchRuntime.completedPublishedPtcExclusion(
                                            privateData.path("localBatch"), row, attempt, result)
                                    || LocalEvidenceBatchRuntime.completedStartLossExclusion(result)
                                    || LocalEvidenceBatchRuntime.completedFullRnaExclusion(mapper,
                                            privateData.path("localBatch"), row, attempt, result);
                            blocked |= "NOT_EVALUATED".equals(result.path("status").asText())
                                    && !"NOT_APPLICABLE".equals(result.at("/details/bp7/coreStatus").asText())
                                    && !completedRoutingExclusion(result, attempt)
                                    && !LocalEvidenceBatchRuntime.completedBp1TruncatingExclusion(mapper,
                                            privateData.path("localBatch"), row, attempt, result)
                                    && !LocalEvidenceBatchRuntime.completedFullRnaAssignment(mapper,
                                            privateData.path("localBatch"), row, attempt, result)
                                    && !completedDeNovoExclusiveAllocation(mapper, response, row, attempt, result, privateData);
                        }
                        complete &= matches == 1;
                    }
                    continue;
                }
                var results = new ArrayList<JsonNode>();
                if ("ACMG_AMP_2015:BA1".equals(criterion)) results.add(row.path("ba1"));
                if ("ACMG_AMP_2015:PM2".equals(criterion)) row.path("attempts").forEach(a -> results.add(a.path("pm2")));
                if ("ACMG_AMP_2015:BS1".equals(criterion)) row.path("attempts").forEach(a -> results.add(a.path("bs1")));
                if (generalPvs1() && TruncatingNmdPvs1Runtime.CRITERION.equals(criterion))
                    row.path("attempts").forEach(a -> results.add(a.path("pvs1")));
                if (Pm4CriterionEvaluator.CRITERION.equals(criterion)) row.path("attempts").forEach(a -> results.add(a.path("pm4")));
                if (generalPm1() && "ACMG_AMP_2015:PM1".equals(criterion)
                        || generalBp3() && Bp3CriterionEvaluator.CRITERION.equals(criterion)
                        || generalPp2() && Pp2CriterionEvaluator.CRITERION.equals(criterion)
                        || generalBp1() && Bp1CriterionEvaluator.CRITERION.equals(criterion)) {
                    complete &= row.path("attempts").isArray() && !row.path("attempts").isEmpty();
                    for (var attempt : row.path("attempts")) {
                        var value = attempt.path(generalBp1() ? "bp1" : generalPp2() ? "pp2" : generalBp3() ? "bp3" : "pm1");
                        var evaluations = value.path("criterionEvaluations");
                        JsonNode expected = value.path("evaluation");
                        if (!value.has("evaluation")) {
                            ObjectNode disposition = mapper.createObjectNode();
                            for (String field : List.of("criterionId", "evaluationResult", "criterionApplicability"))
                                disposition.set(field, value.path(field));
                            expected = disposition;
                        }
                        complete &= evaluations.isArray() && evaluations.size() == 1
                                && evaluations.path(0).equals(expected);
                        results.add(value);
                    }
                }
                if (MissenseComparisonRuntime.PS1.equals(criterion) || MissenseComparisonRuntime.PM5.equals(criterion)) {
                    complete &= row.path("attempts").isArray() && !row.path("attempts").isEmpty();
                    for (var attempt : row.path("attempts")) {
                        var evaluations = attempt.at("/missenseComparison/criterionEvaluations");
                        complete &= evaluations.isArray() && evaluations.size() == 1;
                        for (var evaluation : evaluations) {
                            results.add(evaluation.has("schemaVersion")
                                    ? mapper.createObjectNode().put("criterionId", criterion).set("evaluation", evaluation)
                                    : evaluation);
                        }
                    }
                }
                complete &= !results.isEmpty() && results.stream().allMatch(r -> ruleResultProvided(r, criterion)
                        || unifiedLocal && "ACMG_AMP_2015:BS1".equals(criterion) &&
                        "bs1-rule-result-v1".equals(r.path("schemaVersion").asText()) &&
                        criterion.equals(r.at("/evaluation/criterionId").asText()) &&
                        !r.path("evaluation").has("schemaVersion") &&
                        "NOT_EVALUATED".equals(r.at("/evaluation/evaluationResult/status").asText()) &&
                        "NONE".equals(r.at("/evaluation/appliedStrength").asText()) &&
                        Set.of("UNKNOWN", "NOT_APPLICABLE").contains(r.at("/evaluation/criterionApplicability/status").asText()) &&
                        resultTextPresent(r, "/evaluation/evaluationResult/reasonCode"));
                for (var result : results) {
                    var evaluation = result.has("evaluation") ? result.path("evaluation") : result;
                    blocked |= "NOT_EVALUATED".equals(evaluation.at("/evaluationResult/status").asText())
                            && !"NOT_APPLICABLE".equals(evaluation.at("/criterionApplicability/status").asText());
                    for (var context : result.path("contextResults"))
                        blocked |= "BLOCKED".equals(context.at("/selection/status").asText())
                                || "NOT_EVALUATED".equals(context.at("/evaluation/evaluationResult/status").asText());
                }
            }
            ((ObjectNode) row).put("enabledRuleResultsComplete", complete);
            blocked |= !complete;
        }
        response.put("status", blocked ? "COMPLETED_WITH_BLOCKERS" : "COMPLETED");
    }
    private void appendPrediction(AcmgSubmission request, List<VariantInterpretation> ranked,
            @org.springframework.lang.Nullable AcmgExecutionSources.Recovered restored, ObjectNode response,
            @org.springframework.lang.Nullable PredictionEffectReferences.Snapshot effectSnapshot) {
        var factory = predictionRuntime; if (factory == null) return;
        var prediction = effectSnapshot == null ? factory.get() : factory.get().withEffectRestrictions(effectSnapshot::resolve);
        if (effectSnapshot != null) response.put("effectSnapshotSha256", effectSnapshot.identity());
        record Pending(ObjectNode attempt, PredictionDevelopmentRuntime.Plan plan) { }
        var pending = new ArrayList<Pending>();
        // Complete all plans before the first lookup; no legacy dispatcher or disabled criterion preparation.
        for (var row : response.path("candidates")) for (var attempt : row.path("attempts")) {
            var candidate = ranked.stream().filter(c -> row.path("candidateId").asText().equals(c.getCandidateId())).findFirst().orElseThrow();
            String issue = restored == null ? "PREDICTION_EXECUTION_SOURCE_UNVERIFIED"
                    : restored.candidateBlockers().containsKey(candidate.getCandidateId()) ? restored.candidateBlockers().get(candidate.getCandidateId()).reasonCode() : null;
            var plan = prediction.prepare(candidate, attempt.path("context"), restored == null ? null : restored.snapshotSha256(),
                    attempt.path("hypothesisId").asText(), request.dataClass(), issue);
            pending.add(new Pending((ObjectNode) attempt, plan));
        }
        int calibrated = 0;
        boolean blocked = !response.path("unmatchedInputs").isEmpty();
        for (var p : pending) {
            var result = prediction.execute(p.plan()); p.attempt().set("prediction", result);
            for (var mechanism : result.path("mechanisms")) if ("CALIBRATED".equals(mechanism.path("status").asText())) calibrated++;
            for (var criterion : result.path("criteria")) blocked |= "NOT_EVALUATED".equals(criterion.path("status").asText());
        }
        response.put("providerCalls", prediction.providerCalls()).put("evaluatorCalls", calibrated).put("status", blocked ? "COMPLETED_WITH_BLOCKERS" : "COMPLETED");
        response.putObject("predictionCalls").put("provider", prediction.providerCalls()).put("plans", pending.size()).put("calibratedMechanisms", calibrated).put("bp7", 0);
    }
    private boolean localBs1() {
        return Set.of("LOCAL_EXOMES_BUSINESS_V1", "POLICY_ROUTED_POPULATION_V1")
                .contains(executionProfile.document().at("/bs1RuntimeBinding/activationContract").asText());
    }
    private boolean routedPopulation() {
        return "POLICY_ROUTED_POPULATION_V1".equals(executionProfile.document().at("/bs1RuntimeBinding/activationContract").asText());
    }
    private void appendBs1(VariantAnalysisJob job, List<VariantInterpretation> ranked,
            @org.springframework.lang.Nullable AcmgExecutionSources.Recovered restored, ObjectNode response) {
        var factory = bs1Runtime;
        if (factory == null) return;
        var runtime = factory.get();
        boolean populationBindingMatches = !routedPopulation()
                || executionProfile.document().path("bs1RuntimeBinding").equals(runtime.identity());
        for (var row : response.path("candidates")) for (var attempt : row.path("attempts")) {
            var candidates = ranked.stream().filter(c -> c.getCandidateId().equals(row.path("candidateId").asText())).toList();
            ObjectNode result;
            var bs1Context = attempt.path(ordinaryBs1 ? "bs1Context" : "context");
            var bs1Blockers = attempt.path(ordinaryBs1 ? "bs1ContextBlockers" : "blockers");
            if (!populationBindingMatches) result = runtime.blocked("BS1_RUNTIME_BINDING_MISMATCH");
            else if (restored == null || candidates.size() != 1) result = runtime.blocked("BS1_EXECUTION_SOURCE_UNAVAILABLE");
            else if (!bs1Context.isObject()) result = runtime.blocked(bs1Blockers.path(0).path("reasonCode").asText("BS1_CONTEXT_MISSING"));
            else {
                var analysis = new AnalysisContext(job.getId(), restored.execution().getInputSource().analysisAssembly(),
                        job.getAnalysisMode(), VcfPreflightSummary.empty(), job.getPhenotypeSummary(), null);
                try {
                    JsonNode shared = row.has("populationObservation") ? row.path("populationObservation") : row.path("ba1");
                    result = runtime.isPolicyRouted()
                            ? runtime.evaluateRouted(bs1Context, candidates.get(0), analysis, restored.snapshotSha256(), shared)
                            : runtime.isLocalExomes() ? runtime.evaluateLocal(bs1Context, shared, restored.snapshotSha256())
                            : runtime.evaluate(bs1Context, candidates.get(0), analysis, restored.snapshotSha256());
                } catch (AcmgRunControl.Stopped stopped) { throw stopped; }
                catch (Exception failure) {
                    result = runtime.blocked("BS1_EXECUTION_FAILED");
                    result.put("executionFailureCode", AcmgExecutionSources.code(failure));
                }
            }
            result.put("candidateId", row.path("candidateId").asText());
            result.set("hypothesisId", attempt.path("hypothesisId").isTextual()
                    ? attempt.path("hypothesisId").deepCopy() : mapper.nullNode());
            var reasonExplanation = EvidenceReasonExplanation.build(mapper, result.path("evaluation"));
            if (reasonExplanation != null) result.set("explanation", reasonExplanation);
            ((ObjectNode) attempt).set("bs1", result);
        }
        var calls = runtime.counts(); response.set("bs1Calls", calls);
        response.put("providerCalls", response.path("providerCalls").asInt() + calls.path("provider").asInt() + calls.path("native").asInt());
        response.put("evaluatorCalls", response.path("evaluatorCalls").asInt() + calls.path("evaluator").asInt());
    }

    /** Safe correlation only: never expose exception messages, SQL, source payloads or paths. */
    private ObjectNode executionFailure(String analysisId, @org.springframework.lang.Nullable String runId,
                                        String stage, Exception failure) {
        Throwable cause = failure;
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        boolean storage = false, deserialization = false, missingFile = false;
        for (int depth = 0; depth < 16 && seen.add(cause); depth++) {
            storage |= cause instanceof java.sql.SQLException || cause instanceof org.springframework.dao.DataAccessException;
            deserialization |= cause instanceof com.fasterxml.jackson.core.JsonProcessingException;
            missingFile |= cause instanceof java.nio.file.NoSuchFileException || cause instanceof java.io.FileNotFoundException;
            Throwable next = cause.getCause();
            if (next == null || seen.contains(next)) break;
            cause = next;
        }
        String reason = AcmgExecutionSources.code(failure);
        if ("EXECUTION_SOURCE_INVALID_OR_UNAVAILABLE".equals(reason)) {
            reason = !"SOURCE_RECOVERY".equals(stage) ? "ACMG_RUNTIME_INITIALIZATION_FAILED"
                    : storage ? "EXECUTION_SOURCE_STORAGE_UNAVAILABLE"
                    : deserialization ? "EXECUTION_SOURCE_DESERIALIZATION_FAILED"
                    : missingFile ? "EXECUTION_SOURCE_FILE_UNAVAILABLE" : "EXECUTION_SOURCE_RECOVERY_FAILED";
        }
        var result = mapper.createObjectNode().put("reasonCode", reason).put("stage", stage)
                .put("exceptionType", failure.getClass().getName()).put("causeType", cause.getClass().getName());
        log.warn("ACMG preparation blocked: analysisId={}, runId={}, stage={}, reason={}, exceptionType={}, causeType={}",
                analysisId, runId, stage, reason, failure.getClass().getName(), cause.getClass().getName());
        return result;
    }

    private void appendPm2(VariantAnalysisJob job, AcmgSubmission request, List<VariantInterpretation> ranked,
                           AcmgExecutionSources.Recovered restored, ObjectNode response, ObjectNode privateData,
                           List<ExplicitCaseInputCoordinator.HypothesisInput> effectiveHypotheses) {
        int providers = 0, evaluators = 0;
        Pm2SupportingRuntime population = null;
        String runtimeIssue = "EXECUTION_SOURCE_INVALID_OR_UNAVAILABLE";
        if (restored != null) try { population = Objects.requireNonNull(pm2Runtime.get()); }
        catch (Exception unavailable) { runtimeIssue = AcmgExecutionSources.code(unavailable); }
        if (population != null && restored != null && restored.conversion().isConversionApplied() && !effectiveHypotheses.isEmpty()) {
            try {
                var analysis = new AnalysisContext(job.getId(), restored.execution().getInputSource().analysisAssembly(),
                        job.getAnalysisMode(), VcfPreflightSummary.empty(), job.getPhenotypeSummary(), null);
                var declaration = new ExplicitCaseInputCoordinator.CaseDeclaration(job.getId(), restored.execution().getInputSource(),
                        request.sampleId(), request.dataClass(), request.patient(), effectiveHypotheses);
                var prepared = runtime.get().assembleConvertedPm2(job, restored.conversion(), restored.execution(), analysis,
                        ranked, request.topN(), List.of(declaration), restored.candidateBlockers());
                privateData.set("pm2ConvertedContextProduction", prepared.production().provenance());
                var receipts = privateData.putArray("pm2ConvertedContextReceipts");
                prepared.sources().forEach(source -> receipts.add(source.receipt(mapper)));
                for (var assembled : prepared.assembly().candidates()) for (var row : response.path("candidates")) {
                    if (!Objects.equals(assembled.candidateId(), row.path("candidateId").asText())) continue;
                    for (var contextAttempt : assembled.attempts()) for (var existing : row.path("attempts")) {
                        String hypothesisId = existing.path("hypothesisId").asText(null);
                        if (hypothesisId == null) hypothesisId = existing.at("/preflight/original/hypothesisId").asText(null);
                        if (!(existing instanceof ObjectNode node) || ba1OnlyConfirmation(existing)
                                || !Objects.equals(contextAttempt.hypothesisId(), hypothesisId)) continue;
                        node.set("pm2ContextBlockers", mapper.valueToTree(contextAttempt.blockers()));
                        if (contextAttempt.context() != null) node.set("pm2Context", contextAttempt.context());
                    }
                }
            } catch (Exception invalid) {
                response.put("pm2ConvertedContextFailure", AcmgExecutionSources.code(invalid));
            }
        }
        int providerStart = population == null ? 0 : population.providerCalls();
        int evaluatorStart = population == null ? 0 : population.evaluatorCalls();
        String preparationIssue = null;
        if (population != null && restored != null) {
            var contexts = new LinkedHashMap<NormalizedVariantKey, List<JsonNode>>();
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            for (var row : response.path("candidates")) {
                var candidate = ranked.stream().filter(c -> Objects.equals(c.getCandidateId(), row.path("candidateId").asText())).findFirst().orElse(null);
                if (candidate == null || candidate.getObservation() == null) continue;
                for (var attempt : row.path("attempts")) if (pm2Context(attempt).isObject() && !ba1OnlyConfirmation(attempt)) {
                    try {
                        adapter.validateCompleteContext(pm2Context(attempt));
                        contexts.computeIfAbsent(candidate.getObservation().getKey(), key -> new ArrayList<>()).add(pm2Context(attempt));
                    } catch (IllegalArgumentException invalidContext) {
                        // The individual attempt retains its own blocker below.
                    }
                }
            }
            try {
                // Explicit retry retains only the predecessor's verified successful population observations.
                // It never copies a disease evaluation or a failure, and never rewrites the predecessor.
                if (request.retryOf() != null) {
                    ObjectNode predecessor = null;
                    try { predecessor = read(job.getId(), request.retryOf()); }
                    catch (RuntimeException invalidSavedRun) { /* An invalid predecessor cannot seed population reuse. */ }
                    if (predecessor != null && restored.snapshotSha256().equals(predecessor.path("executionSourceSha256").asText())
                            && executionProfile.document().equals(predecessor.path("executionProfile"))) {
                        for (var candidate : ranked) {
                            if (candidate.getObservation() == null) continue;
                            var alternatives = contexts.get(candidate.getObservation().getKey());
                            if (alternatives == null || alternatives.isEmpty()) continue;
                            var currentContext = (ObjectNode) alternatives.get(0);
                            for (var row : predecessor.path("candidates")) if (candidate.getCandidateId().equals(row.path("candidateId").asText()))
                                for (var attempt : row.path("attempts")) population.reuseSuccessfulObservation(currentContext, candidate, attempt.path("pm2"), request.retryOf());
                        }
                    }
                }
                if (!contexts.isEmpty()) population.prepareContexts(contexts, ranked, new AnalysisContext(job.getId(), restored.execution().getInputSource().analysisAssembly(),
                        job.getAnalysisMode(), VcfPreflightSummary.empty(), job.getPhenotypeSummary(), null));
            } catch (Exception unavailable) { preparationIssue = "PM2_POPULATION_BATCH_FAILED"; }
        }
        for (var row : response.path("candidates")) {
            VariantInterpretation candidate = ranked.stream().filter(c -> Objects.equals(c.getCandidateId(), row.path("candidateId").asText())).findFirst().orElse(null);
            for (var attemptNode : row.path("attempts")) {
                var attempt = (ObjectNode) attemptNode;
                ObjectNode pm2 = mapper.createObjectNode().put("criterionId", "ACMG_AMP_2015:PM2");
                JsonNode contextPreparation = mapper.nullNode();
                for (var recommendation : privateData.path("verifiedRecommendations"))
                    if (row.path("candidateId").equals(recommendation.path("candidateId")))
                        contextPreparation = recommendation.at("/pm2ContextPreparation/diagnostics");
                boolean declared = effectiveHypotheses.stream().anyMatch(h -> h != null && h.hypothesis() != null
                        && Objects.equals(row.path("candidateId").asText(), h.hypothesis().candidateId()));
                String issue = ba1OnlyConfirmation(attempt) ? "BA1_ONLY_CONFIRMATION_OTHER_RULES_UNCONFIRMED"
                        : population == null ? runtimeIssue : preparationIssue;
                if (issue == null && (!pm2Context(attempt).isObject() || candidate == null))
                    issue = attempt.path("pm2ContextBlockers").path(0).path("reasonCode").asText(
                            response.path("pm2ConvertedContextFailure").asText(
                                    declared ? "PM2_VERIFIED_CONTEXT_MISSING"
                                            : contextPreparation.path("reasonCode").asText("PM2_VERIFIED_CONTEXT_MISSING")));
                if (issue == null && population != null && candidate != null && restored != null) try {
                    var analysis = new AnalysisContext(job.getId(), restored.execution().getInputSource().analysisAssembly(),
                            job.getAnalysisMode(), VcfPreflightSummary.empty(), job.getPhenotypeSummary(), null);
                    pm2 = population.evaluate((ObjectNode) pm2Context(attempt), candidate, analysis);
                    pm2.put("executionSourceSha256", restored.snapshotSha256()).put("candidateId", candidate.getCandidateId());
                } catch (Exception invalid) { issue = AcmgExecutionSources.code(invalid); }
                if (issue != null) pm2.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", issue);
                if (attempt.path("pm2ContextBlockers").isArray()) pm2.set("contextBlockers", attempt.path("pm2ContextBlockers").deepCopy());
                pm2.set("contextPreparation", finalPm2Preparation(row.path("candidateId").asText(), attempt,
                        effectiveHypotheses, contextPreparation, restored != null,
                        response.path("pm2ConvertedContextFailure").asText(null)));
                attempt.set("pm2", pm2);
                if (!pm2.path("evaluation").isObject() || "NOT_EVALUATED".equals(pm2.at("/evaluation/evaluationResult/status").asText()))
                    response.put("status", "COMPLETED_WITH_BLOCKERS");
            }
        }
        if (population != null) { providers = population.providerCalls() - providerStart; evaluators = population.evaluatorCalls() - evaluatorStart; }
        response.putObject("pm2Calls").put("provider", providers).put("evaluator", evaluators)
                .put("allSitesBatch", population == null ? 0 : population.allSitesBatches());
        response.set("pm2QueryDiagnostics", mapper.valueToTree(population == null ? List.of() : population.queryDiagnostics()));
        response.put("providerCalls", response.path("providerCalls").asInt() + providers);
        response.put("evaluatorCalls", response.path("evaluatorCalls").asInt() + evaluators);
    }
    private static JsonNode pm2Context(JsonNode attempt) {
        return attempt.path("pm2Context").isObject() ? attempt.path("pm2Context") : attempt.path("context");
    }

    /** Post-intake diagnostic only. Scientific policy, transport and evaluation remain independent. */
    ObjectNode finalPm2Preparation(String candidateId, JsonNode attempt,
            List<ExplicitCaseInputCoordinator.HypothesisInput> declarations, JsonNode recommendation,
            boolean sourceRecovered, String conversionFailure) {
        var context = pm2Context(attempt);
        var blockers = attempt.has("pm2ContextBlockers") ? attempt.path("pm2ContextBlockers") : attempt.path("blockers");
        String hypothesisId = attempt.path("hypothesisId").asText(null);
        var matches = declarations.stream().filter(d -> d != null && d.hypothesis() != null
                && Objects.equals(candidateId, d.hypothesis().candidateId())
                && hypothesisId != null && Objects.equals(hypothesisId, d.hypothesis().hypothesisId())).toList();
        String reason = !sourceRecovered ? "EXECUTION_SOURCE_INVALID_OR_UNAVAILABLE"
                : ba1OnlyConfirmation(attempt) ? "BA1_ONLY_CONFIRMATION_OTHER_RULES_UNCONFIRMED"
                : !blockers.isEmpty() ? blockers.path(0).path("reasonCode").asText("PM2_VERIFIED_CONTEXT_MISSING")
                : conversionFailure;
        if (matches.isEmpty() && "EXPLICIT_INPUT_MISSING".equals(reason) && recommendation.hasNonNull("reasonCode"))
            reason = recommendation.path("reasonCode").asText();
        if (reason == null && matches.size() != 1) reason = recommendation.path("reasonCode").asText("PM2_CONTEXT_CONFIRMATION_REQUIRED");
        if (reason == null) {
            var declaration = matches.get(0);
            var hypothesis = declaration.hypothesis();
            var transcript = declaration.transcript();
            if (!candidateId.equals(attempt.path("candidateId").asText()) || !context.isObject()
                    || !Objects.equals(hypothesis.hgncId(), context.at("/gene/hgncId").asText())
                    || !Objects.equals(hypothesis.mondoId(), context.at("/disease/mondoId").asText())
                    || !Objects.equals(PatientDiseaseContextBindingResolver.normalizeMoi(hypothesis.moi()), context.path("moi").asText())
                    || transcript == null || !Objects.equals(transcript.accession(), context.at("/transcript/accession").asText())
                    || !Objects.equals(transcript.version(), context.at("/transcript/version").isInt()
                            ? context.at("/transcript/version").intValue() : null)) {
                reason = "PM2_VERIFIED_CONTEXT_MISSING";
            } else try { new RuntimeEvidenceV2Adapter(mapper).validateCompleteContext(context); }
            catch (IllegalArgumentException invalid) { reason = AcmgExecutionSources.code(invalid); }
        }
        var result = mapper.createObjectNode().put("schemaVersion", "pm2-context-preparation-v2")
                .put("stage", "POST_SUBMISSION").put("candidateId", candidateId)
                .put("status", reason == null ? "READY" : "BLOCKED");
        if (context.isObject()) result.put("contextId", context.path("contextId").asText(null));
        if (reason != null) result.put("reasonCode", reason);
        result.putObject("transcriptCheck").put("status", reason == null ? "VALIDATED_EVIDENCE_CONTEXT" : "CONTEXT_NOT_VERIFIED");
        if (reason != null) for (String field : List.of("relationshipCheck", "policyCheck"))
            if (recommendation.path(field).isObject()) result.set(field, recommendation.path(field).deepCopy());
        return result;
    }
    private void appendHnf1aFrequency(AcmgExecutionSources.Recovered restored, ObjectNode response) {
        var runtime = Objects.requireNonNull(hnf1aFrequencyRuntime.get());
        int evaluators = 0;
        for (var row : response.path("candidates")) for (var value : row.path("attempts")) {
            var attempt = (ObjectNode) value;
            var frequency = ba1OnlyConfirmation(attempt) ? runtime.disposition("BA1_ONLY_CONFIRMATION_OTHER_RULES_UNCONFIRMED")
                    : restored == null ? runtime.disposition("EXECUTION_SOURCE_INVALID_OR_UNAVAILABLE")
                    : runtime.evaluate(attempt.path("context"), attempt.path("pm2"), restored.snapshotSha256());
            frequency.put("candidateId", row.path("candidateId").asText());
            attempt.set("hnf1aFrequency", frequency);
            evaluators += frequency.path("evaluatorCalls").asInt();
            for (var e : frequency.path("criterionEvaluations")) if ("NOT_EVALUATED".equals(e.at("/evaluationResult/status").asText())
                    && !"NOT_APPLICABLE".equals(e.at("/criterionApplicability/status").asText())) response.put("status", "COMPLETED_WITH_BLOCKERS");
        }
        response.putObject("hnf1aFrequencyCalls").put("provider", 0).put("evaluator", evaluators);
        response.put("evaluatorCalls", response.path("evaluatorCalls").asInt() + evaluators);
    }

    private void appendPopulationFrequency(AcmgExecutionSources.Recovered restored, ObjectNode response) {
        var bundle = populationFrequency;
        if (bundle == null) return;
        int evaluators = 0;
        for (var row : response.path("candidates")) for (var value : row.path("attempts")) {
            var attempt = (ObjectNode) value;
            var frequency = ba1OnlyConfirmation(attempt) ? ba1OnlyPopulationDisposition(bundle)
                    : bundle.evaluate(attempt.path("context"), attempt.path("pm2"), restored == null ? "" : restored.snapshotSha256());
            frequency.put("candidateId", row.path("candidateId").asText());
            attempt.set("populationFrequency", frequency);
            evaluators += frequency.path("evaluatorCalls").asInt();
            if ("NOT_COMBINABLE".equals(frequency.at("/overlap/status").asText()))
                response.put("status", "COMPLETED_WITH_BLOCKERS");
            for (var e : frequency.path("criterionEvaluations"))
                if ("NOT_EVALUATED".equals(e.at("/evaluationResult/status").asText())
                        && !"NOT_APPLICABLE".equals(e.at("/criterionApplicability/status").asText()))
                    response.put("status", "COMPLETED_WITH_BLOCKERS");
        }
        response.putObject("populationFrequencyCalls").put("provider", 0).put("evaluator", evaluators);
        response.put("evaluatorCalls", response.path("evaluatorCalls").asInt() + evaluators);
    }

    private ObjectNode ba1OnlyPopulationDisposition(PopulationFrequencyBundle bundle) {
        String reason = "BA1_ONLY_CONFIRMATION_OTHER_RULES_UNCONFIRMED";
        var result = mapper.createObjectNode().put("providerCalls", 0).put("evaluatorCalls", 0);
        result.set("bundleIdentity", bundle.identity());
        var routing = result.putObject("clauseRouting");
        var rules = result.putArray("ruleResults");
        var evaluations = result.putArray("criterionEvaluations");
        for (String criterion : List.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1")) {
            routing.put(criterion, "CURRENT_CATALOG_RESULT");
            var evaluation = mapper.createObjectNode().put("criterionId", criterion).put("appliedStrength", "NONE");
            evaluation.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", reason);
            evaluation.putObject("criterionApplicability").put("status", "UNKNOWN");
            evaluations.add(evaluation);
            rules.addObject().put("providerCalls", 0).put("evaluatorCalls", 0)
                    .putArray("criterionEvaluations").add(evaluation.deepCopy());
        }
        result.putObject("overlap").put("status", "NOT_COMBINABLE").put("populationStrengthCount", 0).put("reasonCode", reason);
        return result;
    }

    private void appendPm4(List<VariantInterpretation> ranked, AcmgExecutionSources.Recovered restored, ObjectNode response) {
        var bundle = reviewedRules;
        int evaluators = 0, calculations = 0, projections = 0;
        for (JsonNode row : response.path("candidates")) {
            var candidate = ranked.stream().filter(c -> Objects.equals(c.getCandidateId(), row.path("candidateId").asText())).findFirst().orElse(null);
            for (JsonNode node : row.path("attempts")) {
                ObjectNode attempt = (ObjectNode) node;
                ObjectNode value = mapper.createObjectNode().put("criterionId", Pm4CriterionEvaluator.CRITERION)
                        .put("providerCalls", 0).put("evaluatorCalls", 0).put("lengthCalculations", 0)
                        .put("adoptionSupported", false).put("classificationEnabled", false);
                String issue = bundle == null || !bundle.supportsPm4() ? "PM4_TRUSTED_BUNDLE_REQUIRED"
                        : restored == null || candidate == null ? "PM4_EXECUTION_SOURCE_UNAVAILABLE"
                        : restored.candidateBlockers().containsKey(candidate.getCandidateId()) ? restored.candidateBlockers().get(candidate.getCandidateId()).reasonCode()
                        : !attempt.path("context").isObject() ? "PM4_CONTEXT_MISSING"
                        : !new ReviewedRuleContextPreparation(mapper, bundle.identity()).accepts(attempt.at("/context/transcript/selectionPolicy").asText())
                            ? "PM4_TRANSCRIPT_CONFIRMATION_REQUIRED" : null;
                if (issue == null && bundle != null && candidate != null && restored != null) {
                    value = bundle.evaluate(Pm4CriterionEvaluator.CRITERION, attempt.path("context"), candidate);
                    value.put("executionSourceSha256", restored.snapshotSha256());
                } else {
                    value.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", issue);
                    value.putObject("criterionApplicability").put("status", "UNKNOWN");
                }
                attempt.set("pm4", value);
                evaluators += value.path("evaluatorCalls").asInt(); calculations += value.path("lengthCalculations").asInt();
                projections += value.path("storedVepProjections").asInt();
            }
        }
        response.putObject("pm4Calls").put("provider", 0).put("evaluator", evaluators)
                .put("lengthCalculations", calculations).put("storedVepProjections", projections);
        response.put("evaluatorCalls", response.path("evaluatorCalls").asInt() + evaluators);
    }

    private void appendBp1(List<VariantInterpretation> ranked, AcmgExecutionSources.Recovered restored, ObjectNode response) {
        var bundle = Objects.requireNonNull(reviewedRules);
        int evaluators = 0, projections = 0;
        for (JsonNode row : response.path("candidates")) {
            var candidate = ranked.stream().filter(c -> Objects.equals(c.getCandidateId(), row.path("candidateId").asText())).findFirst().orElse(null);
            for (JsonNode node : row.path("attempts")) {
                ObjectNode attempt = (ObjectNode) node;
                String issue = restored == null || candidate == null ? "BP1_EXECUTION_SOURCE_UNAVAILABLE"
                        : restored.candidateBlockers().containsKey(candidate.getCandidateId()) ? restored.candidateBlockers().get(candidate.getCandidateId()).reasonCode()
                        : !attempt.path("context").isObject() ? "BP1_CONTEXT_MISSING"
                        : !new ReviewedRuleContextPreparation(mapper, bundle.identity()).accepts(attempt.at("/context/transcript/selectionPolicy").asText())
                            ? "BP1_TRANSCRIPT_CONFIRMATION_REQUIRED" : null;
                ObjectNode value;
                if (issue == null && candidate != null && restored != null) {
                    value = bundle.evaluate(Bp1CriterionEvaluator.CRITERION, attempt.path("context"), candidate);
                    value.put("executionSourceSha256", restored.snapshotSha256());
                } else value = bundle.refuseBp1(issue == null ? "BP1_CONTEXT_MISSING" : issue);
                attempt.set("bp1", value);
                evaluators += value.path("evaluatorCalls").asInt(); projections += value.path("storedVepProjections").asInt();
            }
        }
        response.putObject("bp1Calls").put("provider", 0).put("evaluator", evaluators).put("storedVepProjections", projections);
        response.put("evaluatorCalls", response.path("evaluatorCalls").asInt() + evaluators);
    }

    private void appendPp2(List<VariantInterpretation> ranked, AcmgExecutionSources.Recovered restored, ObjectNode response) {
        var bundle = Objects.requireNonNull(reviewedRules);
        int evaluators = 0, projections = 0;
        for (JsonNode row : response.path("candidates")) {
            var candidate = ranked.stream().filter(c -> Objects.equals(c.getCandidateId(), row.path("candidateId").asText())).findFirst().orElse(null);
            for (JsonNode node : row.path("attempts")) {
                ObjectNode attempt = (ObjectNode) node;
                String issue = restored == null || candidate == null ? "PP2_EXECUTION_SOURCE_UNAVAILABLE"
                        : restored.candidateBlockers().containsKey(candidate.getCandidateId()) ? restored.candidateBlockers().get(candidate.getCandidateId()).reasonCode()
                        : !attempt.path("context").isObject() ? "PP2_CONTEXT_MISSING"
                        : !new ReviewedRuleContextPreparation(mapper, bundle.identity()).accepts(attempt.at("/context/transcript/selectionPolicy").asText())
                            ? "PP2_TRANSCRIPT_CONFIRMATION_REQUIRED" : null;
                ObjectNode value;
                if (issue == null && candidate != null && restored != null) {
                    value = bundle.evaluate(Pp2CriterionEvaluator.CRITERION, attempt.path("context"), candidate);
                    value.put("executionSourceSha256", restored.snapshotSha256());
                } else value = bundle.refusePp2(issue == null ? "PP2_CONTEXT_MISSING" : issue);
                attempt.set("pp2", value);
                evaluators += value.path("evaluatorCalls").asInt(); projections += value.path("storedVepProjections").asInt();
            }
        }
        response.putObject("pp2Calls").put("provider", 0).put("evaluator", evaluators).put("storedVepProjections", projections);
        response.put("evaluatorCalls", response.path("evaluatorCalls").asInt() + evaluators);
    }

    private void appendBp3(List<VariantInterpretation> ranked, AcmgExecutionSources.Recovered restored, ObjectNode response) {
        var bundle = Objects.requireNonNull(reviewedRules);
        int evaluators = 0, projections = 0;
        for (JsonNode row : response.path("candidates")) {
            var candidate = ranked.stream().filter(c -> Objects.equals(c.getCandidateId(), row.path("candidateId").asText())).findFirst().orElse(null);
            for (JsonNode node : row.path("attempts")) {
                ObjectNode attempt = (ObjectNode) node;
                String issue = restored == null || candidate == null ? "BP3_EXECUTION_SOURCE_UNAVAILABLE"
                        : restored.candidateBlockers().containsKey(candidate.getCandidateId()) ? restored.candidateBlockers().get(candidate.getCandidateId()).reasonCode()
                        : !attempt.path("context").isObject() ? "BP3_CONTEXT_MISSING"
                        : !new ReviewedRuleContextPreparation(mapper, bundle.identity()).accepts(attempt.at("/context/transcript/selectionPolicy").asText())
                            ? "BP3_TRANSCRIPT_CONFIRMATION_REQUIRED" : null;
                ObjectNode value;
                if (issue == null && candidate != null && restored != null) {
                    value = bundle.evaluate(Bp3CriterionEvaluator.CRITERION, attempt.path("context"), candidate);
                    value.put("executionSourceSha256", restored.snapshotSha256());
                } else value = bundle.refuseBp3(issue == null ? "BP3_CONTEXT_MISSING" : issue);
                attempt.set("bp3", value);
                evaluators += value.path("evaluatorCalls").asInt(); projections += value.path("storedVepProjections").asInt();
            }
        }
        response.putObject("bp3Calls").put("provider", 0).put("evaluator", evaluators).put("storedVepProjections", projections);
        response.put("evaluatorCalls", response.path("evaluatorCalls").asInt() + evaluators);
    }

    private boolean selectedPvs1() { return generalPvs1() && reviewedRules != null && reviewedRules.selectedPvs1Enabled(); }

    private void appendSelectedPvs1(SelectedEvidencePreparation selected, String sourceSha, ObjectNode response, ObjectNode privateData) {
        var bundle = Objects.requireNonNull(reviewedRules);
        var projections = privateData.putArray("pvs1ContextProjections");
        int evaluators = 0, storedProjections = 0;
        for (var row : response.path("candidates")) for (var node : row.path("attempts")) {
            var attempt = (ObjectNode) node;
            ObjectNode value;
            try {
                var projection = selected.legacyProjection(row.path("candidateId").asText(), attempt.path("hypothesisId").asText());
                var evaluationContext = projection.context();
                if (!new ReviewedRuleContextPreparation(mapper, bundle.identity()).accepts(evaluationContext.at("/transcript/selectionPolicy").asText()))
                    throw new IllegalArgumentException("PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED");
                projections.add(projection.snapshot());
                attempt.set("selectedContext", attempt.path("context").deepCopy());
                attempt.set("context", evaluationContext);
                value = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, evaluationContext, projection.queryCandidate());
                value.put("executionSourceSha256", sourceSha).put("selectedContextProjectionSha256", projection.bindingSha256());
            } catch (IllegalArgumentException blocked) {
                String reason = blocked.getMessage();
                // Only stable internal reason codes are exported; no paths or raw exception messages.
                if (reason == null || !reason.matches("[A-Z][A-Z0-9_]{1,120}")) reason = "PVS1_SELECTED_PREPARATION_UNAVAILABLE";
                if (attempt.path("context").isObject()) attempt.set("selectedContext", attempt.path("context").deepCopy());
                attempt.remove("context");
                value = bundle.refusePvs1(reason);
            }
            attempt.set("pvs1", value);
            evaluators += value.path("evaluatorCalls").asInt(); storedProjections += value.path("storedVepProjections").asInt();
        }
        response.putObject("pvs1Calls").put("provider", 0).put("evaluator", evaluators).put("storedVepProjections", storedProjections);
        response.put("evaluatorCalls", evaluators);
    }

    private void appendGeneralPvs1(List<VariantInterpretation> ranked, AcmgExecutionSources.Recovered restored, ObjectNode response) {
        var bundle = Objects.requireNonNull(reviewedRules);
        int evaluators = 0, projections = 0;
        for (JsonNode row : response.path("candidates")) {
            var candidate = ranked.stream().filter(c -> Objects.equals(c.getCandidateId(), row.path("candidateId").asText())).findFirst().orElse(null);
            for (JsonNode node : row.path("attempts")) {
                ObjectNode attempt = (ObjectNode) node;
                String issue = restored == null || candidate == null ? "PVS1_EXECUTION_SOURCE_UNAVAILABLE"
                        : restored.candidateBlockers().containsKey(candidate.getCandidateId()) ? restored.candidateBlockers().get(candidate.getCandidateId()).reasonCode()
                        : !attempt.path("context").isObject() ? "PVS1_CONTEXT_MISSING"
                        : !new ReviewedRuleContextPreparation(mapper, bundle.identity()).accepts(attempt.at("/context/transcript/selectionPolicy").asText())
                            ? "PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED" : null;
                ObjectNode value;
                if (issue == null && candidate != null && restored != null) {
                    value = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, attempt.path("context"), candidate);
                    value.put("executionSourceSha256", restored.snapshotSha256());
                } else value = bundle.refusePvs1(issue == null ? "PVS1_CONTEXT_MISSING" : issue);
                attempt.set("pvs1", value);
                evaluators += value.path("evaluatorCalls").asInt(); projections += value.path("storedVepProjections").asInt();
            }
        }
        response.putObject("pvs1Calls").put("provider", 0).put("evaluator", evaluators).put("storedVepProjections", projections);
        response.put("evaluatorCalls", response.path("evaluatorCalls").asInt() + evaluators);
    }

    private void appendPvs1(List<VariantInterpretation> ranked, AcmgExecutionSources.Recovered restored, ObjectNode response) {
        if (generalPvs1()) { appendGeneralPvs1(ranked, restored, response); return; }
        Hnf1aPvs1Runtime runtime = null;
        String runtimeIssue = "EXECUTION_SOURCE_INVALID_OR_UNAVAILABLE";
        if (restored != null) try { runtime = Objects.requireNonNull(pvs1Runtime.get()); }
        catch (RuntimeException unavailable) { runtimeIssue = AcmgExecutionSources.code(unavailable); }
        int start = runtime == null ? 0 : runtime.evaluatorCalls();
        int projectionStart = runtime == null ? 0 : runtime.storedVepProjections();
        int reviewedEvaluators = 0, reviewedProjections = 0;
        for (var row : response.path("candidates")) {
            var candidate = ranked.stream().filter(c -> Objects.equals(c.getCandidateId(), row.path("candidateId").asText())).findFirst().orElse(null);
            for (var node : row.path("attempts")) {
                var attempt = (ObjectNode) node;
                var value = mapper.createObjectNode().put("criterionId", Hnf1aPvs1Runtime.CRITERION);
                String issue = populationOnlyConfirmation(attempt) ? "PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED"
                        : restored == null || Set.of("PVS1_BUSINESS_EXECUTION_DISABLED", "ACMG_BUSINESS_EXECUTION_DISABLED")
                                .contains(runtimeIssue) ? runtimeIssue : null;
                if (issue == null && (!attempt.path("context").isObject() || candidate == null)) issue = "PVS1_VERIFIED_CONTEXT_MISSING";
                if (issue == null && reviewedRules != null && candidate != null && restored != null
                        && restored.candidateBlockers().containsKey(candidate.getCandidateId()))
                    issue = restored.candidateBlockers().get(candidate.getCandidateId()).reasonCode();
                if (issue == null && candidate != null && restored != null) try {
                    ObjectNode reviewed = evaluateReviewedRule(Hnf1aPvs1Runtime.CRITERION, attempt.path("context"), candidate);
                    if (reviewed != null) {
                        value = reviewed;
                        reviewedEvaluators += value.path("evaluatorCalls").asInt();
                        reviewedProjections += value.path("storedVepProjections").asInt();
                    } else if (runtime != null) value = runtime.evaluate((ObjectNode) attempt.path("context"), candidate);
                    else issue = runtimeIssue;
                    value.put("candidateId", candidate.getCandidateId()).put("executionSourceSha256", restored.snapshotSha256());
                } catch (RuntimeException invalid) { issue = AcmgExecutionSources.code(invalid); }
                if (issue != null) value.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", issue);
                attempt.set("pvs1", value);
                var disposition = value.has("evaluation") ? value.path("evaluation") : value;
                if (!"MET".equals(value.at("/evaluation/evaluationResult/status").asText())
                        && !"NOT_APPLICABLE".equals(disposition.at("/criterionApplicability/status").asText()))
                    response.put("status", "COMPLETED_WITH_BLOCKERS");
            }
        }
        int calls = reviewedEvaluators + (runtime == null ? 0 : runtime.evaluatorCalls() - start);
        response.putObject("pvs1Calls").put("provider", 0).put("evaluator", calls)
                .put("storedVepProjections", reviewedProjections + (runtime == null ? 0 : runtime.storedVepProjections() - projectionStart));
        response.put("evaluatorCalls", response.path("evaluatorCalls").asInt() + calls);
    }
    private void appendFunctional(List<VariantInterpretation> ranked, AcmgExecutionSources.Recovered restored, ObjectNode response) {
        Hnf1aFunctionalRuntime runtime = null;
        String runtimeIssue = "EXECUTION_SOURCE_INVALID_OR_UNAVAILABLE";
        if (restored != null) try { runtime = Objects.requireNonNull(functionalRuntime.get()); }
        catch (RuntimeException unavailable) { runtimeIssue = AcmgExecutionSources.code(unavailable); }
        int providers = 0, evaluators = 0;
        for (var row : response.path("candidates")) {
            var candidate = ranked.stream().filter(c -> Objects.equals(c.getCandidateId(), row.path("candidateId").asText())).findFirst().orElse(null);
            for (var node : row.path("attempts")) {
                var attempt = (ObjectNode) node;
                var value = mapper.createObjectNode().put("criterionId", Hnf1aFunctionalRuntime.CRITERION);
                String issue = populationOnlyConfirmation(attempt) ? "FUNCTIONAL_TRANSCRIPT_CONFIRMATION_REQUIRED"
                        : restored == null || Set.of("PS3_BUSINESS_EXECUTION_DISABLED", "ACMG_BUSINESS_EXECUTION_DISABLED")
                                .contains(runtimeIssue) ? runtimeIssue : null;
                if (issue == null && (!attempt.path("context").isObject() || candidate == null)) issue = "FUNCTIONAL_VERIFIED_CONTEXT_MISSING";
                if (issue == null && candidate != null && restored != null) try {
                    if (restored.candidateBlockers().containsKey(candidate.getCandidateId())) issue = restored.candidateBlockers().get(candidate.getCandidateId()).reasonCode();
                    else {
                        ObjectNode reviewed = evaluateReviewedRule(Hnf1aFunctionalRuntime.CRITERION, attempt.path("context"), candidate);
                        if (reviewed != null) value = reviewed;
                        else if (runtime != null) value = runtime.evaluate((ObjectNode) attempt.path("context"), candidate);
                        else issue = runtimeIssue;
                    }
                } catch (RuntimeException invalid) { issue = AcmgExecutionSources.code(invalid); }
                if (issue != null) value.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", issue);
                attempt.set("functional", value);
                if (value.path("bundle").isObject()) {
                    var bundleRow = ((com.fasterxml.jackson.databind.node.ArrayNode) response.path("bundles")).addObject();
                    bundleRow.set("candidateId", row.path("candidateId")); bundleRow.set("hypothesisId", attempt.path("hypothesisId"));
                    bundleRow.set("bundle", value.path("bundle"));
                }
                providers += value.path("providerCalls").asInt(); evaluators += value.path("evaluatorCalls").asInt();
                var disposition = value.has("evaluation") ? value.path("evaluation") : value;
                if (!"MET".equals(disposition.at("/evaluationResult/status").asText()) && !"NOT_APPLICABLE".equals(disposition.at("/criterionApplicability/status").asText()))
                    response.put("status", "COMPLETED_WITH_BLOCKERS");
            }
        }
        response.putObject("functionalCalls").put("provider", providers).put("evaluator", evaluators).put("wetExperiments", 0).put("externalModels", 0);
        response.put("providerCalls", response.path("providerCalls").asInt()+providers).put("evaluatorCalls", response.path("evaluatorCalls").asInt()+evaluators);
    }
    @org.springframework.lang.Nullable
    private ObjectNode evaluateReviewedRule(String criterion, JsonNode context, VariantInterpretation candidate) {
        var bundle = reviewedRules;
        if (bundle == null) return null;
        ObjectNode value = bundle.evaluate(criterion, context, candidate);
        return ReviewedRuleBundle.usesPreexistingClause(value) ? null : value;
    }
    private void appendPm1(List<VariantInterpretation> ranked, AcmgExecutionSources.Recovered restored, ObjectNode response) {
        if (generalPm1()) { appendGeneralPm1(ranked, restored, response); return; }
        Hnf1aPm1Runtime runtime = null;
        String runtimeIssue = "PM1_EXECUTION_SOURCE_UNAVAILABLE";
        if (restored != null) try { runtime = Objects.requireNonNull(pm1Runtime.get()); }
        catch (RuntimeException unavailable) { runtimeIssue = AcmgExecutionSources.code(unavailable); }
        int start = runtime == null ? 0 : runtime.evaluatorCalls(), projected = runtime == null ? 0 : runtime.storedVepProjections();
        for (var row : response.path("candidates")) {
            var candidate = ranked.stream().filter(v -> v.getCandidateId().equals(row.path("candidateId").asText())).findFirst().orElse(null);
            for (var node : row.path("attempts")) {
                var attempt = (ObjectNode) node;
                var value = mapper.createObjectNode().put("criterionId", Hnf1aPm1Runtime.CRITERION);
                String issue = populationOnlyConfirmation(attempt) ? "PM1_TRANSCRIPT_CONFIRMATION_REQUIRED"
                        : runtime == null ? runtimeIssue : null;
                if (issue == null && (!attempt.path("context").isObject() || candidate == null)) issue = "PM1_VERIFIED_CONTEXT_MISSING";
                if (issue == null && runtime != null && candidate != null && restored != null) try {
                    if (restored.candidateBlockers().containsKey(candidate.getCandidateId()))
                        issue = restored.candidateBlockers().get(candidate.getCandidateId()).reasonCode();
                    else {
                        value = runtime.evaluate((ObjectNode) attempt.path("context"), candidate);
                        value.put("candidateId", candidate.getCandidateId()).put("executionSourceSha256", restored.snapshotSha256());
                    }
                } catch (RuntimeException invalid) { issue = AcmgExecutionSources.code(invalid); }
                if (issue != null) value.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", issue);
                attempt.set("pm1", value);
                var disposition = value.has("evaluation") ? value.path("evaluation") : value;
                if ("NOT_EVALUATED".equals(disposition.at("/evaluationResult/status").asText())
                        && !"NOT_APPLICABLE".equals(disposition.at("/criterionApplicability/status").asText()))
                    response.put("status", "COMPLETED_WITH_BLOCKERS");
            }
        }
        int calls = runtime == null ? 0 : runtime.evaluatorCalls() - start;
        response.putObject("pm1Calls").put("provider", 0).put("evaluator", calls)
                .put("storedVepProjections", runtime == null ? 0 : runtime.storedVepProjections() - projected);
        response.put("evaluatorCalls", response.path("evaluatorCalls").asInt() + calls);
    }
    private void appendGeneralPm1(List<VariantInterpretation> ranked, AcmgExecutionSources.Recovered restored, ObjectNode response) {
        var bundle = Objects.requireNonNull(reviewedRules);
        int evaluators = 0, calculations = 0, projections = 0;
        for (JsonNode row : response.path("candidates")) {
            var candidate = ranked.stream().filter(c -> Objects.equals(c.getCandidateId(), row.path("candidateId").asText())).findFirst().orElse(null);
            for (JsonNode node : row.path("attempts")) {
                ObjectNode attempt = (ObjectNode) node;
                String issue = restored == null || candidate == null ? "PM1_EXECUTION_SOURCE_UNAVAILABLE"
                        : restored.candidateBlockers().containsKey(candidate.getCandidateId()) ? restored.candidateBlockers().get(candidate.getCandidateId()).reasonCode()
                        : !attempt.path("context").isObject() ? "PM1_CONTEXT_MISSING"
                        : !new ReviewedRuleContextPreparation(mapper, bundle.identity()).accepts(attempt.at("/context/transcript/selectionPolicy").asText())
                            ? "PM1_TRANSCRIPT_CONFIRMATION_REQUIRED" : null;
                ObjectNode value;
                if (issue == null && candidate != null && restored != null) {
                    value = bundle.evaluate("ACMG_AMP_2015:PM1", attempt.path("context"), candidate);
                    value.put("executionSourceSha256", restored.snapshotSha256());
                } else value = bundle.refusePm1(issue == null ? "PM1_CONTEXT_MISSING" : issue);
                attempt.set("pm1", value);
                evaluators += value.path("evaluatorCalls").asInt(); calculations += value.path("positionCalculations").asInt();
                projections += value.path("storedVepProjections").asInt();
            }
        }
        response.putObject("pm1Calls").put("provider", 0).put("evaluator", evaluators)
                .put("positionCalculations", calculations).put("storedVepProjections", projections);
        response.put("evaluatorCalls", response.path("evaluatorCalls").asInt() + evaluators);
    }
    private void describeBp7Scope(ObjectNode response, List<VariantInterpretation> ranked, AcmgExecutionSources.Recovered restored) {
        for (var row : response.path("candidates")) for (var attemptNode : row.path("attempts")) {
            var attempt = (ObjectNode) attemptNode;
            boolean evaluated = false;
            for (var e : attempt.path("criterionEvaluations")) evaluated |= "ACMG_AMP_2015:BP7".equals(e.path("criterionId").asText());
            if (evaluated) continue;
            JsonNode prediction = attempt.at("/transcriptInput/reviewedPrediction/clauses/BP7");
            if (prediction.isObject() && !ReviewedPredictionBundle.usesPreexistingClause(prediction)) {
                attempt.putObject("bp7Disposition").put("status", "NOT_EVALUATED").put("applicability", "UNKNOWN")
                        .put("reasonCode", ReviewedPredictionBusinessRuntime.refusalReason(prediction.path("result")));
                continue;
            }
            String reason = "EXPLICIT_INPUT_MISSING";
            if (prediction.isObject() && List.of("ALPHAMISSENSE_MISSENSE", "HNF1A_REVEL").contains(
                    attempt.at("/transcriptInput/preexistingPrediction/transcriptInput/predictionPath").asText()))
                reason = "BP7_CONSEQUENCE_NOT_APPLICABLE";
            if (List.of("ALPHAMISSENSE_MISSENSE", "HNF1A_REVEL").contains(attempt.at("/transcriptInput/predictionPath").asText()))
                reason = "BP7_CONSEQUENCE_NOT_APPLICABLE";
            for (var b : attempt.path("blockers")) { reason = b.path("reasonCode").asText(reason); break; }
            if (!attempt.hasNonNull("context") && restored != null
                    && restored.execution().getInputSource().inputAssembly() == org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38
                    && !restored.conversion().isConversionApplied()
                    && !restored.candidateBlockers().containsKey(row.path("candidateId").asText())) {
                var candidate = ranked.stream().filter(c -> c.getCandidateId().equals(row.path("candidateId").asText())).findFirst().orElse(null);
                var annotations = candidate == null ? List.<org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence>of() : candidate.getTranscriptAnnotations();
                var policy = new IntronicSplicePolicy(mapper);
                // No selected transcript: only a unanimous, source-backed out-of-scope result may be reported.
                if (annotations != null && !annotations.isEmpty() && annotations.stream().allMatch(t -> t != null
                        && t.getSource() != null && t.getSource().startsWith("VEP") && "COMPLETED".equals(t.getStatus())
                        && "116.0".equals(t.getVersion()) && t.getTimestamp() != null && t.getVersionedTranscriptId() != null
                        && IntronicSplicePolicy.notApplicable(policy.blocker(t)))) {
                    var reasons = annotations.stream().map(policy::blocker).distinct().toList();
                    reason = reasons.size() == 1 ? reasons.get(0) : "BP7_CONSEQUENCE_NOT_APPLICABLE";
                }
            }
            // An outer applicability disposition is not a manufactured criterion-evaluation-v2 instance.
            attempt.putObject("bp7Disposition").put("status", "NOT_EVALUATED")
                    .put("applicability", IntronicSplicePolicy.notApplicable(reason) ? "NOT_APPLICABLE" : "UNKNOWN")
                    .put("reasonCode", reason);
        }
    }
    private void explainMissingHypotheses(AcmgSubmission request, JsonNode verifiedRecommendations, ObjectNode response) {
        boolean localScope = localBatchFactory != null && executionProfile.document().path("requiredDeclaration").asText().equals(request.declarationVersion());
        if (!"ordinary-ui-v3".equals(request.declarationVersion()) && !localScope) return;
        for (var row : response.path("candidates")) {
            String candidateId = row.path("candidateId").asText();
            if (request.hypotheses().stream().anyMatch(h -> h != null && h.hypothesis() != null
                    && candidateId.equals(h.hypothesis().candidateId()))) continue;
            for (var recommendation : verifiedRecommendations) {
                if (!candidateId.equals(recommendation.path("candidateId").asText())) continue;
                var selection = request.recommendations().stream().filter(s -> candidateId.equals(s.candidateId())).findFirst().orElseThrow();
                var reasons = recommendations.missingHypothesisBlockers(recommendation, selection);
                if (reasons.isEmpty()) continue;
                for (var attempt : row.path("attempts")) {
                    if (!attempt.path("context").isNull() && !attempt.path("context").isMissingNode()
                            || !attempt.path("criterionEvaluations").isEmpty() || !attempt.path("atomicEvidence").isEmpty()) continue;
                    // Explain an omitted context, without replacing a source/runtime failure or any criterion result.
                    if (localScope && !"EXPLICIT_INPUT_MISSING".equals(attempt.at("/blockers/0/reasonCode").asText())) continue;
                    var merged = reasons.deepCopy(); var seen = new HashSet<String>();
                    for (var reason : merged) seen.add(reason.path("reasonCode").asText());
                    for (var blocker : attempt.path("blockers")) {
                        String code = blocker.path("reasonCode").asText();
                        if (!"EXPLICIT_INPUT_MISSING".equals(code) && seen.add(code)) merged.add(blocker.deepCopy());
                    }
                    ((ObjectNode) attempt).set("blockers", merged);
                }
            }
        }
    }
    private void appendBa1(VariantAnalysisJob job, AcmgSubmission request, List<VariantInterpretation> ranked,
                           AcmgExecutionSources.Recovered restored, ObjectNode response, ObjectNode privateData,
                           List<ExplicitCaseInputCoordinator.HypothesisInput> effectiveHypotheses) {
        boolean evaluateBa1 = responseCriteria(response).contains("ACMG_AMP_2015:BA1");
        Ba1Runtime runtime = null;
        String failure = response.at("/executionSourceFailure/reasonCode").asText("EXECUTION_SOURCE_INVALID_OR_UNAVAILABLE");
        if (restored != null) try {
            runtime = Objects.requireNonNull(ba1Runtime.get());
            if (ba1RuntimeBinding != null && !ba1RuntimeBinding.equals(runtime.evaluator().identity()))
                throw new IllegalArgumentException("BA1_RUNTIME_BINDING_MISMATCH");
            runtime.evaluator().beginExecution();
        } catch (Exception unavailable) { runtime = null; failure = AcmgExecutionSources.code(unavailable); }
        if (runtime != null && restored != null && restored.conversion().isConversionApplied() && !effectiveHypotheses.isEmpty()) {
            try {
                var analysis = new AnalysisContext(job.getId(), restored.execution().getInputSource().analysisAssembly(), job.getAnalysisMode(),
                        VcfPreflightSummary.empty(), job.getPhenotypeSummary(), null);
                var declaration = new ExplicitCaseInputCoordinator.CaseDeclaration(job.getId(), restored.execution().getInputSource(),
                        request.sampleId(), request.dataClass(), request.patient(), effectiveHypotheses);
                var prepared = this.runtime.get().assembleConvertedBa1(job, restored.conversion(), restored.execution(), analysis,
                        ranked, request.topN(), List.of(declaration), restored.candidateBlockers(), runtime.producer());
                privateData.set("ba1ConvertedContextProduction", prepared.production().provenance());
                var contextReceipts = privateData.putArray("ba1ConvertedContextReceipts");
                prepared.sources().forEach(source -> contextReceipts.add(source.receipt(mapper)));
                for (var assembled : prepared.assembly().candidates()) for (var resultRow : response.path("candidates")) {
                    if (!assembled.candidateId().equals(resultRow.path("candidateId").asText())) continue;
                    for (var attempt : assembled.attempts()) for (var existing : resultRow.path("attempts")) {
                        String hypothesisId = existing.path("hypothesisId").asText(null);
                        if (hypothesisId == null) hypothesisId = existing.at("/preflight/original/hypothesisId").asText(null);
                        if (!(existing instanceof ObjectNode node) || !Objects.equals(attempt.hypothesisId(), hypothesisId)) continue;
                        node.set("ba1ContextBlockers", mapper.valueToTree(attempt.blockers()));
                        if (attempt.context() != null) node.set("ba1Context", attempt.context());
                    }
                }
            } catch (Exception invalid) {
                response.put("ba1ConvertedContextFailure", AcmgExecutionSources.code(invalid));
            }
        }
        var receipts = privateData.putArray("ba1Sources");
        // One verified, compact history snapshot per publication transaction, shared by all alleles.
        var ba1History = new Ba1HistoryIndex();
        int providerCalls = 0, evaluatorCalls = 0;
        for (int i = 0; i < ranked.size(); i++) {
            var candidate = ranked.get(i); var row = (ObjectNode) response.path("candidates").get(i);
            ObjectNode ba1 = mapper.createObjectNode().put("criterionId", "ACMG_AMP_2015:BA1");
            String issue = runtime == null ? failure : null;
            if (restored == null) for (var attempt : row.path("attempts")) for (var blocker : attempt.path("blockers"))
                if ("executionSource".equals(blocker.path("field").asText())) issue = blocker.path("reasonCode").asText(issue);
            try {
                JsonNode declaredContexts = ba1RuntimeBinding == null ? mapper.createArrayNode() : ba1DeclaredContexts(row);
                if (issue == null && restored != null && restored.candidateBlockers().containsKey(candidate.getCandidateId()))
                    issue = restored.candidateBlockers().get(candidate.getCandidateId()).reasonCode();
                if (issue == null && restored != null && runtime != null) {
                    var analysis = new AnalysisContext(job.getId(), restored.execution().getInputSource().analysisAssembly(), job.getAnalysisMode(),
                            VcfPreflightSummary.empty(), job.getPhenotypeSummary(), null);
                    var production = runtime.producer().produceBa1Allele(job, restored.conversion(), restored.execution(), analysis, candidate, request.dataClass());
                    var input = production.inputs().get(0);
                    var stored = receipts.addObject().put("candidateId", candidate.getCandidateId());
                    stored.set("production", production.provenance());
                    var converted = production.provenance().path("inputs").path(0).path("conversionEvidence");
                    if (converted.isObject()) ba1.set("conversionSource", converted.deepCopy());
                    var sources = stored.putArray("receipts"); production.sources().forEach(source -> sources.add(source.receipt(mapper)));
                    if (!production.blockers(input).isEmpty()) issue = production.blockers(input).get(0).reasonCode();
                    else {
                        var normalization = input.normalization();
                        JsonNode ref = production.resolve(normalization.reference());
                        ObjectNode normalizer = mapper.createObjectNode().put("method", normalization.method()).put("methodVersion", normalization.methodVersion())
                                .put("referenceSequenceSha256", ref.path("observedSha256").asText()).put("refValidation", "MATCH");
                        var adapter = new RuntimeEvidenceV2Adapter(mapper);
                        String alleleId = adapter.canonicalAlleleId(mapper.valueToTree(normalization.normalizedAllele()), normalizer);
                        var cacheIdentity = mapper.createObjectNode().put("analysisId", job.getId()).put("candidateId", candidate.getCandidateId())
                                .put("alleleId", alleleId).put("executionSourceSha256", restored.snapshotSha256()).put("dataClass", request.dataClass());
                        cacheIdentity.set("executionProfile", executionProfile.document());
                        if (!evaluateBa1) cacheIdentity.put("purpose", "POPULATION_FACTS_FOR_BS1");
                        if (ba1RuntimeBinding != null) {
                            cacheIdentity.set("ba1RuntimeBinding", ba1RuntimeBinding);
                            cacheIdentity.set("declaredContexts", declaredContexts);
                        }
                        String reuseKey = artifacts.requestHash(cacheIdentity);
                        JsonNode cached = evaluateBa1 ? reusableBa1(job.getId(), reuseKey, ba1History) : null;
                        AcmgRunControl.checkpoint();
                        if (cached != null) {
                            ba1 = ((ObjectNode) cached.path("ba1")).deepCopy();
                            row.put("ba1ReusedFromRunId", cached.path("runId").asText());
                        } else {
                            JsonNode observation = null;
                            // Formal output may have separate MOI rows for the same normalized allele.
                            // General BA1 can use its verified population fact without borrowing that row's disease evaluation.
                            for (var populationRow : response.path("candidates")) {
                                for (var attempt : populationRow.path("attempts")) {
                                    var pm2 = attempt.path("pm2");
                                    if (alleleId.equals(pm2.at("/context/variant/alleleId").asText())
                                            && request.dataClass().equals(pm2.at("/context/dataClass").asText())
                                            && "SUCCEEDED".equals(pm2.at("/evidence/resourceExecution/status").asText())) { observation = pm2; break; }
                                }
                                if (observation != null) break;
                            }
                            if (routedPopulation()) {
                                for (var attempt : row.path("attempts")) {
                                    var neutral = attempt.at("/bs1/observation");
                                    if (!PopulationObservation.VERSION.equals(neutral.path("schemaVersion").asText())
                                            || !neutral.path("profileSha256").equals(runtime.evaluator().identity().path("populationInputProfileSha256"))) continue;
                                    var context = attempt.path("bs1Context");
                                    if (!context.isObject()) context = attempt.at("/bs1/context");
                                    if (!context.isObject() || !alleleId.equals(context.at("/variant/alleleId").asText())) continue;
                                    var envelope = mapper.createObjectNode(); envelope.set("populationObservation", neutral);
                                    envelope.set("context", context); observation = envelope; break;
                                }
                            }
                            ba1 = runtime.evaluator().evaluateNormalized(alleleId, candidate, analysis, observation,
                                    restored.snapshotSha256(), declaredContexts, mapper.valueToTree(normalization.normalizedAllele()), normalizer,
                                    production.resolve(normalization.source()), evaluateBa1);
                            providerCalls += ba1.path("providerCalls").asInt(); evaluatorCalls += ba1.path("evaluatorCalls").asInt();
                            ba1.put("reuseKey", reuseKey);
                            ba1.put("criterionId", "ACMG_AMP_2015:BA1").put("candidateId", candidate.getCandidateId()).put("alleleId", alleleId);
                            ba1.set("normalization", normalizer);
                            ba1.set("normalizationSource", mapper.valueToTree(normalization.source()));
                            ba1.set("lineage", mapper.valueToTree(normalization.lineage()));
                            ba1.set("inputAllele", mapper.valueToTree(normalization.originalAllele()));
                            ba1.set("evidenceQueryAllele", mapper.valueToTree(normalization.normalizedAllele()));
                            if (converted.isObject()) ba1.set("conversionSource", converted.deepCopy());
                            ba1.put("executionSourceSha256", restored.snapshotSha256());
                        }
                        // A reused fact retains its evidence identity, but source references must resolve
                        // against this run's saved ledger rather than a previous run's timestamped proof.
                        ba1.set("normalizationSource", mapper.valueToTree(normalization.source()));
                        ba1.set("lineage", mapper.valueToTree(normalization.lineage()));
                        var evaluation = ba1.path("evaluation");
                        if (evaluateBa1 && !"ACMG_AMP_2015:BA1".equals(evaluation.path("criterionId").asText())) issue = "BA1_EVALUATION_IDENTITY_MISMATCH";
                    }
                }
            } catch (AcmgRunControl.Stopped stopped) { throw stopped; }
            catch (Exception invalid) { issue = AcmgExecutionSources.code(invalid); }
            if (issue != null) {
                ba1.putArray("blockers").addObject().put("reasonCode", issue);
                ba1.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", issue);
            }
            var contextBlockers = mapper.createArrayNode();
            for (var attempt : row.path("attempts")) for (var blocker : attempt.path("ba1ContextBlockers"))
                contextBlockers.add(blocker.deepCopy());
            if (response.hasNonNull("ba1ConvertedContextFailure")) contextBlockers.addObject()
                    .put("reasonCode", response.path("ba1ConvertedContextFailure").asText());
            if (!contextBlockers.isEmpty()) ba1.set("contextAssemblyBlockers", contextBlockers);
            row.set(evaluateBa1 ? "ba1" : "populationObservation", ba1);
            if (!evaluateBa1) { ba1.remove("criterionId"); continue; }
            if (ba1RuntimeBinding != null) markBa1ResultAuthority(row, ba1);
            if (issue != null || "NOT_EVALUATED".equals(ba1.at("/evaluation/evaluationResult/status").asText()))
                response.put("status", "COMPLETED_WITH_BLOCKERS");
            for (var contextResult : ba1.path("contextResults"))
                if ("NOT_EVALUATED".equals(contextResult.at("/evaluation/evaluationResult/status").asText())
                        || "BLOCKED".equals(contextResult.at("/selection/status").asText()))
                    response.put("status", "COMPLETED_WITH_BLOCKERS");
            if (populationOnly) continue; // Current result completeness is computed once from the enabled snapshot.
            var covered = new ArrayList<String>();
            for (String criterion : executionProfile.contextCriteria()) {
                boolean present = true;
                for (var attempt : row.path("attempts")) {
                    var ids = new ArrayList<String>(); attempt.path("criterionEvaluations").forEach(e -> ids.add(e.path("criterionId").asText()));
                    if (!predictionClauseProvided(attempt, criterion)
                            && !AcmgExecutionProfile.exactCriteria(ids, AcmgExecutionProfile.contextCriteria(attempt.path("transcriptInput")))) present = false;
                }
                if (present && !row.path("attempts").isEmpty()) covered.add(criterion);
            }
            if (ba1.path("evaluation").isObject()) covered.add(ba1.path("evaluation").path("criterionId").asText());
            boolean bp7Present = !row.path("attempts").isEmpty();
            for (var attempt : row.path("attempts")) {
                int count = 0;
                for (var e : attempt.path("criterionEvaluations")) if ("ACMG_AMP_2015:BP7".equals(e.path("criterionId").asText())) count++;
                bp7Present &= predictionClauseProvided(attempt, "ACMG_AMP_2015:BP7") || count == 1
                        || count == 0 && "NOT_APPLICABLE".equals(attempt.at("/bp7Disposition/applicability").asText());
            }
            if (bp7Present) covered.add("ACMG_AMP_2015:BP7");
            boolean pm2Present = !row.path("attempts").isEmpty();
            for (var attempt : row.path("attempts")) pm2Present &= ruleResultProvided(attempt.path("pm2"), "ACMG_AMP_2015:PM2");
            if (pm2Present) covered.add("ACMG_AMP_2015:PM2");
            boolean bs1Present = !row.path("attempts").isEmpty();
            for (var attempt : row.path("attempts")) {
                int count = 0;
                JsonNode frequency = PopulationFrequencyBundle.usesCurrentClause(attempt.path("populationFrequency"), Hnf1aPopulationRuntime.BS1)
                        ? attempt.path("populationFrequency") : attempt.path("hnf1aFrequency");
                for (var e : frequency.path("criterionEvaluations")) if (Hnf1aPopulationRuntime.BS1.equals(e.path("criterionId").asText())) {
                    count++;
                    bs1Present &= e.has("schemaVersion")
                            ? ruleResultProvided(mapper.createObjectNode().put("criterionId", Hnf1aPopulationRuntime.BS1).set("evaluation", e), Hnf1aPopulationRuntime.BS1)
                            : ruleResultProvided(e, Hnf1aPopulationRuntime.BS1);
                }
                bs1Present &= count == 1;
            }
            if (bs1Present) covered.add(Hnf1aPopulationRuntime.BS1);
            boolean pvs1Present = row.path("attempts").isArray() && !row.path("attempts").isEmpty();
            for (var attempt : row.path("attempts")) pvs1Present &= pvs1ResultProvided(attempt.path("pvs1"));
            if (pvs1Present) covered.add(Hnf1aPvs1Runtime.CRITERION);
            boolean functionalPresent = row.path("attempts").isArray() && !row.path("attempts").isEmpty();
            for (var attempt : row.path("attempts")) functionalPresent &= ruleResultProvided(attempt.path("functional"), Hnf1aFunctionalRuntime.CRITERION);
            if (functionalPresent) covered.add(Hnf1aFunctionalRuntime.CRITERION);
            boolean pm1Present = !row.path("attempts").isEmpty();
            for (var attempt : row.path("attempts")) pm1Present &= ruleResultProvided(attempt.path("pm1"), Hnf1aPm1Runtime.CRITERION);
            if (pm1Present) covered.add(Hnf1aPm1Runtime.CRITERION);
            for (String criterion : List.of("ACMG_AMP_2015:PS1", "ACMG_AMP_2015:PM5")) {
                boolean complete = !row.path("attempts").isEmpty();
                for (var attempt : row.path("attempts")) {
                    var comparison = attempt.path("missenseComparison");
                    int count = 0;
                    for (var evaluation : comparison.path("criterionEvaluations"))
                        if (criterion.equals(evaluation.path("criterionId").asText())) {
                            count++;
                            complete &= evaluation.has("schemaVersion")
                                    ? ruleResultProvided(mapper.createObjectNode().put("criterionId", criterion).set("evaluation", evaluation), criterion)
                                    : ruleResultProvided(evaluation, criterion);
                        }
                    complete &= count == 1 || count == 0 && "NOT_APPLICABLE".equals(comparison.path("applicability").asText())
                            && comparison.path("blockers").isArray() && !comparison.path("blockers").isEmpty()
                            && java.util.stream.StreamSupport.stream(comparison.path("blockers").spliterator(), false)
                                    .allMatch(reason -> resultTextPresent(reason, "/reasonCode"));
                }
                if (complete) covered.add(criterion);
            }
            row.put("enabledRuleResultsComplete", AcmgExecutionProfile.exactCriteria(covered, executionProfile.criteria()));
        }
        response.put("providerCalls", response.path("providerCalls").asInt() + providerCalls);
        response.put("evaluatorCalls", response.path("evaluatorCalls").asInt() + evaluatorCalls);
        response.putObject("ba1Calls").put("provider", providerCalls).put("evaluator", evaluatorCalls);
        response.set("ba1QueryDiagnostics", mapper.valueToTree(runtime == null ? List.of() : runtime.evaluator().queryDiagnostics()));
    }
    /** Only server-assembled contexts. PM2-only selection remains explicit for the catalog to interpret. */
    private JsonNode ba1DeclaredContexts(JsonNode row) {
        var contexts = mapper.createArrayNode();
        var byId = new LinkedHashMap<String, JsonNode>();
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        for (var attempt : row.path("attempts")) {
            var context = (unifiedLocal ? attempt.has("ba1Context") : attempt.path("ba1Context").isObject())
                    ? attempt.path("ba1Context") : attempt.path("context");
            if (!context.isObject()) continue;
            adapter.validateCompleteContext(context);
            String id = context.path("contextId").asText();
            JsonNode previous = byId.putIfAbsent(id, context);
            if (previous != null && !previous.equals(context))
                throw new IllegalArgumentException("BA1_DECLARED_CONTEXT_IDENTITY_CONFLICT");
        }
        byId.values().forEach(context -> contexts.add(context.deepCopy()));
        return contexts;
    }
    /** Selection metadata is outside the sealed historical evaluations; it never rewrites their conclusions. */
    private void markBa1ResultAuthority(ObjectNode row, JsonNode ba1) {
        row.putObject("ba1ResultSelection").put("schemaVersion", "ba1-effective-result-selection-v1")
                .put("effectiveResultPath", "/ba1").put("contextResultsPath", "/ba1/contextResults")
                .put("legacyResultsRole", "SOURCE_ONLY").put("fallbackAllowed", false);
        for (var value : row.path("attempts")) {
            if (!(value instanceof ObjectNode attempt)) continue;
            var selection = attempt.putObject("ba1ResultSelection")
                    .put("schemaVersion", "ba1-effective-result-selection-v1")
                    .put("legacyResultsRole", "SOURCE_ONLY").put("fallbackAllowed", false);
            String contextId = (unifiedLocal ? attempt.has("ba1Context") : attempt.path("ba1Context").isObject())
                    ? attempt.at("/ba1Context/contextId").asText() : attempt.at("/context/contextId").asText();
            int index = 0;
            boolean found = false;
            for (var contextResult : ba1.path("contextResults")) {
                if (!contextId.isBlank() && contextId.equals(contextResult.path("contextId").asText())) {
                    selection.put("effectiveResultPath", "/ba1/contextResults/" + index);
                    found = true;
                    break;
                }
                index++;
            }
            if (!found) selection.put("effectiveResultPath", "/ba1")
                    .put("reasonCode", "BA1_CONTEXT_RESULT_UNAVAILABLE_CANDIDATE_DISPOSITION_ONLY");
        }
    }
    /** Result-shape completeness only; evidence identities and scientific decisions are not recalculated. */
    private static boolean pvs1ResultProvided(JsonNode value) {
        return ruleResultProvided(value, Hnf1aPvs1Runtime.CRITERION);
    }
    private static boolean predictionClauseProvided(JsonNode attempt, String criterion) {
        String code = criterion.substring(criterion.indexOf(':') + 1);
        JsonNode clause = attempt.at("/transcriptInput/reviewedPrediction/clauses/" + code);
        if (!criterion.equals(clause.path("criterionId").asText())) return false;
        if (ReviewedPredictionBundle.usesPreexistingClause(clause)) {
            int count = 0;
            for (JsonNode e : attempt.path("criterionEvaluations")) if (criterion.equals(e.path("criterionId").asText())) count++;
            return count == 1;
        }
        JsonNode result = clause.path("result");
        return criterion.equals(result.path("criterionId").asText()) && ("EVALUATED".equals(result.path("status").asText())
                && result.path("evaluation").isObject() || "NOT_EVALUATED".equals(result.path("status").asText())
                && !ReviewedPredictionBusinessRuntime.refusalReason(result).isBlank());
    }
    private static boolean ruleResultProvided(JsonNode value, String criterion) {
        if (!criterion.equals(value.path("criterionId").asText())) return false;
        boolean evaluated = value.has("evaluation");
        var result = evaluated ? value.path("evaluation") : value;
        String status = result.at("/evaluationResult/status").asText();
        if (!Set.of("MET", "NOT_MET", "NOT_EVALUATED").contains(status)) return false;
        if ("NOT_EVALUATED".equals(status) && !resultTextPresent(result, "/evaluationResult/reasonCode")) return false;
        var applicability = result.path("criterionApplicability");
        if (evaluated || result.has("criterionApplicability")) {
            String applies = applicability.path("status").asText();
            if (!Set.of("APPLICABLE", "NOT_APPLICABLE", "UNKNOWN").contains(applies)) return false;
            if (!"APPLICABLE".equals(applies) && (!"NOT_EVALUATED".equals(status)
                    || !resultTextPresent(applicability, "/reasonCode"))) return false;
        }
        // Runtime unavailability uses the existing minimal, reason-bearing business disposition.
        if (!evaluated) return "NOT_EVALUATED".equals(status);
        boolean alleleEvaluation = "ACMG_AMP_2015:BA1".equals(criterion)
                && "ba1-allele-evaluation-v1".equals(result.path("schemaVersion").asText());
        if (alleleEvaluation ? !resultTextPresent(result, "/alleleId", "/scope")
                : !resultTextPresent(result, "/contextId", "/ruleBinding/cspecSelectionId")) return false;
        if (!criterion.equals(result.path("criterionId").asText())
                || !(alleleEvaluation || "criterion-evaluation-v2".equals(result.path("schemaVersion").asText()))
                || !"varvis-json-c14n-v1".equals(result.path("canonicalizationProfile").asText())
                || !resultTextPresent(result, "/evaluationId", "/baseStrength", "/appliedStrength",
                    "/ruleBinding/ruleRegistryId", "/ruleBinding/ruleRegistrySha256", "/ruleBinding/currentGeneralDesignation",
                    "/deterministicTrace/evaluatorId", "/deterministicTrace/evaluatorVersion",
                    "/conflict/status", "/review/status", "/review/decision")) return false;
        for (String path : List.of("/evidenceIds", "/ruleBinding/authoritySourceIds", "/deterministicTrace/inputEvidenceIds",
                "/deterministicTrace/parameters", "/deterministicTrace/steps", "/conflict/conflictIds", "/review/reviewRecordIds"))
            if (!result.at(path).isArray()) return false;
        String execution = result.at("/deterministicTrace/executionStatus").asText();
        if ("COMPLETED".equals(execution)) {
            if (!resultTextPresent(result, "/deterministicTrace/algorithmId", "/deterministicTrace/algorithmVersion",
                    "/deterministicTrace/traceSha256")) return false;
        } else if (!Set.of("NOT_RUN", "FAILED").contains(execution)
                || !resultTextPresent(result, "/deterministicTrace/reasonCode")) return false;
        return "NOT_EVALUATED".equals(status) ? "NONE".equals(result.path("appliedStrength").asText())
                : "COMPLETED".equals(execution) && !result.path("evidenceIds").isEmpty()
                && ("MET".equals(status) ? !"NONE".equals(result.path("appliedStrength").asText())
                    : "NONE".equals(result.path("appliedStrength").asText()));
    }
    private static boolean resultTextPresent(JsonNode value, String... paths) {
        for (String path : paths) if (!value.at(path).isTextual() || value.at(path).asText().isBlank()) return false;
        return true;
    }
    /** Owned by one publication transaction; preserves list order and first eligible match. */
    static final class Ba1HistoryIndex {
        private java.util.Iterator<AcmgBusinessStore.Run> remaining;
        private final Map<String, ObjectNode> matches = new LinkedHashMap<>();
    }
    private JsonNode reusableBa1(String analysisId, String reuseKey, Ba1HistoryIndex history) {
        return AcmgExecutionProgress.measure("BA1_VERIFIED_HISTORY", () -> indexedBa1(analysisId, reuseKey, history));
    }
    private JsonNode indexedBa1(String analysisId, String reuseKey, Ba1HistoryIndex history) {
        AcmgRunControl.checkpoint();
        if (history.matches.containsKey(reuseKey)) return history.matches.get(reuseKey).deepCopy();
        if (history.remaining == null) history.remaining = store.runs(analysisId).iterator();
        while (history.remaining.hasNext()) {
            var run = history.remaining.next();
            if (!Set.of("COMPLETED", "COMPLETED_WITH_BLOCKERS").contains(run.status())) continue;
            ObjectNode saved;
            try {
                AcmgRunControl.checkpoint();
                {
                    var verified = trustedRead(analysisId,run.id()); // full integrity check once, never reuse unverified bytes
                    saved = mapper.createObjectNode();
                    saved.set("executionProfile", verified.response().path("executionProfile").deepCopy());
                    var rows = saved.putArray("candidates");
                    for (var row : verified.response().path("candidates")) {
                        var item=rows.addObject(); item.set("candidateId",row.path("candidateId").deepCopy());item.set("ba1",row.path("ba1").deepCopy());
                    }
                    saved.set("declaration",verified.privateData().path("declaration").deepCopy());
                }
            }
            catch (ResponseStatusException corrupt) { continue; } // A corrupt old run is not a usable cache or a sibling blocker.
            for (var candidate : saved.path("candidates")) {
                var ba1 = candidate.path("ba1");
                String storedKey = ba1.path("reuseKey").asText();
                if (ba1RuntimeBinding == null && storedKey.isEmpty() && ba1.hasNonNull("alleleId") && saved.path("executionProfile").equals(executionProfile.document())) {
                    // Derive the same key from verified historical fields without altering old JSON.
                    try {
                        var declaration = saved.path("declaration");
                        var identity = mapper.createObjectNode().put("analysisId", analysisId).put("candidateId", candidate.path("candidateId").asText())
                                .put("alleleId", ba1.path("alleleId").asText()).put("executionSourceSha256", ba1.path("executionSourceSha256").asText())
                                .put("dataClass", declaration.path("dataClass").asText());
                        identity.set("executionProfile", saved.path("executionProfile")); storedKey = artifacts.requestHash(identity);
                    } catch (IllegalArgumentException invalid) { continue; }
                }
                if (!storedKey.isEmpty() && "SUCCEEDED".equals(ba1.at("/evidence/resourceExecution/status").asText())
                        && "AVAILABLE".equals(ba1.at("/evidence/resourceAvailability/status").asText())
                        && (!ba1.has("subtypeFilterEvidence") || "VERIFIED_PASS".equals(ba1.at("/subtypeFilterEvidence/status").asText()))
                        && ba1.has("evaluation")) {
                    ObjectNode found = mapper.createObjectNode().put("runId", run.id());
                    found.set("ba1", ba1.deepCopy()); history.matches.putIfAbsent(storedKey, found);
                }
            }
            if (history.matches.containsKey(reuseKey)) return history.matches.get(reuseKey).deepCopy();
        }
        return null;
    }

    private static boolean populationOnlyConfirmation(JsonNode attempt) {
        return "PM2_CONTEXT_ONLY".equals(attempt.at("/transcriptInput/predictionPath").asText())
                || AcmgRecommendations.populationOnlySelection(attempt.at("/context/transcript/selectionPolicy").asText());
    }
    private static boolean ba1OnlyConfirmation(JsonNode attempt) {
        return AcmgRecommendations.BA1_CONTEXT_SELECTION.equals(attempt.at("/context/transcript/selectionPolicy").asText());
    }

    private void appendMissense(List<VariantInterpretation> ranked, AcmgExecutionSources.Recovered restored, ObjectNode response) {
        int providers = 0, evaluators = 0, rawBatches = 0;
        var scope = generalMissense() ? mapper.createObjectNode() : ClinVarMissenseBusinessRuntime.loadProfile(mapper).path("applicability");
        for (var row : response.path("candidates")) {
            VariantInterpretation candidate = ranked.stream()
                    .filter(c -> c.getCandidateId().equals(row.path("candidateId").asText())).findFirst().orElse(null);
            for (var node : row.path("attempts")) {
                if (!(node instanceof ObjectNode attempt)) continue;
                ObjectNode context = attempt.path("context") instanceof ObjectNode c ? c : null;
                ObjectNode comparison;
                boolean outside = !generalMissense() && context != null && !org.nbscn.varvis.variantanalysis.provider.Hnf1aPs1Pm5Runtime.matchesContext(context)
                        && (!scope.path("assembly").asText().equals(context.at("/variant/evidenceQueryAllele/assembly").asText())
                        || !scope.path("geneSymbol").asText().equals(context.at("/gene/symbol").asText())
                        || !scope.path("mondoId").asText().equals(context.at("/disease/mondoId").asText()));
                if (generalMissense()) {
                    var trusted = Objects.requireNonNull(reviewedRules);
                    if (restored == null || candidate == null) comparison = trusted.refuseMissense(missenseCode() + "_EXECUTION_SOURCE_UNAVAILABLE");
                    else if (restored.candidateBlockers().containsKey(candidate.getCandidateId()))
                        comparison = trusted.refuseMissense(restored.candidateBlockers().get(candidate.getCandidateId()).reasonCode());
                    else if (context == null) comparison = trusted.refuseMissense(missenseCode() + "_CONTEXT_MISSING");
                    else if (!new ReviewedRuleContextPreparation(mapper, trusted.identity()).accepts(context.at("/transcript/selectionPolicy").asText()))
                        comparison = trusted.refuseMissense(missenseCode() + "_TRANSCRIPT_CONFIRMATION_REQUIRED");
                    else {
                        comparison = trusted.evaluate(trusted.missenseCriterion(), context, candidate);
                        comparison.put("executionSourceSha256", restored.snapshotSha256());
                    }
                }
                else if (populationOnlyConfirmation(attempt)) comparison = ClinVarMissenseBusinessRuntime.disposition(mapper, context, "PS1_PM5_TRANSCRIPT_CONFIRMATION_REQUIRED", "UNKNOWN");
                else if (outside) comparison = ClinVarMissenseBusinessRuntime.disposition(mapper, context, "PS1_PM5_OUTSIDE_DISEASE_POLICY", "NOT_APPLICABLE");
                else if (context == null || candidate == null)
                    comparison = ClinVarMissenseBusinessRuntime.disposition(mapper, context, "PS1_PM5_CONTEXT_REQUIRED", "UNKNOWN");
                else {
                    String transcript = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
                    var matches = candidate.getTranscriptAnnotations() == null ? List.<TranscriptConsequence>of()
                            : candidate.getTranscriptAnnotations().stream().filter(t -> t != null
                                    && transcript.equals(t.getVersionedTranscriptId())
                                    && context.at("/transcript/annotationSourceVersion").asText().equals(t.getVersion())).toList();
                    if (matches.size() != 1) comparison = ClinVarMissenseBusinessRuntime.disposition(mapper, context, "PS1_PM5_SELECTED_TRANSCRIPT_AMBIGUOUS", "UNKNOWN");
                    else try { comparison = missenseRuntime.get().evaluate(context, candidate, matches.get(0)); }
                    catch (Exception unavailable) { comparison = ClinVarMissenseBusinessRuntime.disposition(mapper, context, AcmgExecutionSources.code(unavailable), "UNKNOWN"); }
                }
                attempt.set("missenseComparison", comparison);
                providers += comparison.path("providerCalls").asInt(); evaluators += comparison.path("evaluatorCalls").asInt();
                rawBatches += comparison.path("rawBatchCalls").asInt();
                if (comparison.path("bundle").isObject()) {
                    var bundleRow = ((com.fasterxml.jackson.databind.node.ArrayNode) response.path("bundles")).addObject();
                    bundleRow.set("candidateId", row.path("candidateId")); bundleRow.set("hypothesisId", attempt.path("hypothesisId"));
                    bundleRow.set("bundle", comparison.path("bundle"));
                }
                if (!"NOT_APPLICABLE".equals(comparison.path("applicability").asText())
                        && (comparison.path("criterionEvaluations").isEmpty()
                        || java.util.stream.StreamSupport.stream(comparison.path("criterionEvaluations").spliterator(), false)
                        .anyMatch(e -> "NOT_EVALUATED".equals(e.at("/evaluationResult/status").asText()))))
                    response.put("status", "COMPLETED_WITH_BLOCKERS");
            }
        }
        response.put("providerCalls", response.path("providerCalls").asInt() + providers);
        response.put("evaluatorCalls", response.path("evaluatorCalls").asInt() + evaluators);
        response.putObject("missenseCalls").put("provider", providers).put("evaluator", evaluators).put("rawBatch", rawBatches);
    }
    private void addBlocked(ObjectNode attempt, String hypothesisId, String reason) {
        attempt.put("hypothesisId", hypothesisId).putNull("context").putNull("selection");
        attempt.putArray("blockers").addObject().put("status", "CONTEXT_NOT_READY").put("reasonCode", reason).put("field", "executionSource");
        attempt.putArray("criterionEvaluations");
    }
    private ObjectNode adoptRevel(VariantAnalysisJob job, AcmgSubmission request) {
        var application = revelApplication; var reference = request.revelApplication();
        if (application == null || reference == null) throw error(HttpStatus.CONFLICT, "REVEL_APPLICATION_DISABLED");
        if (!RevelApplicationBusiness.DECLARATION.equals(request.declarationVersion())
                || !RevelApplicationBusiness.OPERATION.equals(reference.operation()) || request.retryOf() != null
                || request.association() != null || request.calibration() != null || request.patient() != null
                || !request.hypotheses().isEmpty() || request.recommendations() != null
                || !application.identity().path("bindingSha256").asText().equals(reference.bindingSha256()))
            throw error(HttpStatus.BAD_REQUEST, "REVEL_APPLICATION_REFERENCE_INVALID");
        String analysisId = job.getId();
        var source = trustedRead(analysisId, reference.sourceRunId());
        if (!source.response().path("requestSha256").asText().equals(reference.sourceRequestSha256())
                || source.response().hasNonNull("operation") || source.response().path("requestedN").asInt() != request.topN()
                || !source.response().path("dataClass").asText().equals(request.dataClass())
                || !source.request().at("/declaration/sampleId").asText().equals(request.sampleId())
                || !Set.of("COMPLETED", "COMPLETED_WITH_BLOCKERS").contains(source.record().status()))
            throw error(HttpStatus.CONFLICT, "REVEL_APPLICATION_SOURCE_MISMATCH");
        ObjectNode identity = mapper.createObjectNode(); identity.set("declaration", mapper.valueToTree(request));
        identity.set("executionProfile", executionProfile.document()); identity.put("sourceResponseSha256", source.record().responseSha256());
        identity.set("revelApplicationBinding", application.identity());
        String hash = artifacts.requestHash(identity);
        var previous = store.byRequest(analysisId, hash);
        if (previous.isPresent()) return read(analysisId, previous.get().id());
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        try {
            if (!store.claim(new AcmgBusinessStore.Run(id, analysisId, hash, mapper.writeValueAsString(identity), "RUNNING", now, null, null, null)))
                return read(analysisId, store.byRequest(analysisId, hash).orElseThrow().id());
            store.withAnalysisLock(analysisId, () -> {
                // All approval freshness and local same-effect checks occur in this publication transaction.
                var current = trustedRead(analysisId, reference.sourceRunId());
                if (!Objects.equals(current.record().responseSha256(), source.record().responseSha256()))
                    throw error(HttpStatus.CONFLICT, "REVEL_APPLICATION_SOURCE_CHANGED");
                var publication = store.source(analysisId).orElseThrow(() -> error(HttpStatus.CONFLICT, "REVEL_PUBLICATION_UNAVAILABLE"));
                if (!AcmgBusinessStore.sha(publication.json()).equals(publication.sha256()))
                    throw error(HttpStatus.CONFLICT, "REVEL_PUBLICATION_INTEGRITY_FAILURE");
                var local = new ArrayList<ObjectNode>(); var indexed = store.effectScopeRuns(analysisId);
                if (indexed.size() > 1000) throw error(HttpStatus.CONFLICT, "EFFECT_SCOPE_RUN_LIMIT_EXCEEDED");
                for (var run : indexed) {
                    if (run.id().equals(id) || !Set.of("COMPLETED", "COMPLETED_WITH_BLOCKERS").contains(run.status())) continue;
                    var saved = read(analysisId, run.id());
                    for (var value : saved.path("applications")) if (value instanceof ObjectNode object) local.add(object);
                }
                Map<String, String> originals = Map.of("run", current.record().responseJson(), "request", current.record().requestJson(),
                        "private", current.record().privateJson(), "publication", publication.json());
                var effects = revelEffects;
                if (effects == null) throw error(HttpStatus.CONFLICT, "EFFECT_REVIEW_STORE_UNAVAILABLE");
                var candidates = new HashSet<String>();
                current.response().path("candidateIds").forEach(c -> candidates.add(c.asText()));
                var effectSnapshot = effects.capture(analysisId, candidates);
                var result = application.adopt(originals, local, effectSnapshot::resolve);
                result.put("functionalEffectSnapshotSha256", effectSnapshot.identity());
                result.put("runId", id).put("analysisId", analysisId).put("requestSha256", hash).put("createdAt", now)
                        .put("operation", RevelApplicationBusiness.OPERATION).put("sourceRunId", reference.sourceRunId())
                        .put("dataClass", request.dataClass()).put("requestedN", request.topN()).put("candidateOrderingChanged", false)
                        .put("automaticTrigger", false).put("classificationEnabled", false);
                result.set("executionProfile", executionProfile.document()); result.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria()));
                result.set("revelApplicationBinding", application.identity());
                result.set("candidateIds", current.response().path("candidateIds").deepCopy()); result.set("selectedCount", current.response().path("selectedCount"));
                // Preserve source rows for interpretation. New application records do not overwrite their evaluations.
                result.set("candidates", current.response().path("candidates").deepCopy());
                ObjectNode privateData = mapper.createObjectNode(); privateData.set("declaration", identity.path("declaration"));
                privateData.put("sourceResponseSha256", source.record().responseSha256());
                try {
                    String privateJson = mapper.writeValueAsString(privateData);
                    result.put("privateArtifactSha256", AcmgBusinessStore.sha(privateJson));
                    store.finish(id, result.path("status").asText(), mapper.writeValueAsString(result), privateJson);
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("ACMG_RESULT_SERIALIZATION_FAILED", invalid); }
                return null;
            });
            return read(analysisId, id);
        } catch (Exception failed) {
            store.failed(id); throw persistenceFailure(failed, "ACMG_EXECUTION_OR_SAVE_FAILED");
        }
    }

    private static boolean containsPs2Token(JsonNode node) {
        if (node.isTextual() && node.textValue().startsWith("PS2_DENOVO_CONTEXT@")) return true;
        for (var child : node) if (containsPs2Token(child)) return true;
        return false;
    }
    private ObjectNode ps2PublishedIdentity(String analysisId) {
        try {
            var currentJob = job(analysisId);
            return mapper.createObjectNode().put("analysisId", analysisId)
                    .put("sampleId", currentJob.getSelectedSampleId() == null ? currentJob.getRequestedSampleId() : currentJob.getSelectedSampleId())
                    .put("uploadSha256", currentJob.getFileSha256()).put("inputAssembly", currentJob.getGenomeAssembly().name())
                    .put("analysisMode", currentJob.getAnalysisMode().name())
                    .put("candidatesRawSha256", AcmgBusinessStore.sha(mapper.writeValueAsString(formalCandidates(analysisId, 1))))
                    .put("executionSourceSha256", store.source(analysisId).map(mappedItem -> mappedItem.sha256()).orElse("UNAVAILABLE"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw error(HttpStatus.CONFLICT, "PS2_PUBLISHED_INPUT_UNAVAILABLE"); }
    }
    private Ps2DeNovoContextPreparation.Prepared preparePs2(VariantAnalysisJob job, int topN, Ps2DeNovoContextPreparation preparation) {
        if (topN != 1) throw error(HttpStatus.BAD_REQUEST, "PS2_TOP_ONE_REQUIRED");
        if (job.getStatus() != VariantAnalysisStatus.READY) throw error(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY");
        var ranked = formalCandidates(job.getId(), 1);
        if (ranked.size() != 1) throw error(HttpStatus.CONFLICT, "PS2_FORMAL_CANDIDATE_REQUIRED");
        try {
            var recovered = sources.recover(job, ranked);
            return preparation.prepare(job, ranked.get(0), recovered, ps2PublishedIdentity(job.getId()), executionProfile.document());
        } catch (Exception invalid) { throw error(HttpStatus.CONFLICT, AcmgExecutionSources.code(invalid)); }
    }
    private ObjectNode ps2Options(String analysisId, int topN, Ps2DeNovoContextPreparation preparation) {
        var prepared = preparePs2(job(analysisId), topN, preparation);
        var result = mapper.createObjectNode().put("analysisId", analysisId).put("requestedN", 1).put("selectedCount", 1)
                .put("acceptanceScope", AcmgBusinessRuleScope.PS2_DEVELOPMENT).put("requiredDeclaration", AcmgBusinessRuleScope.PS2_DECLARATION)
                .put("runtimeConfigured", true).put("formalEnabled", false).put("automaticTrigger", false)
                .put("classificationEnabled", false).put("adoptionSupported", false).put("criterionPolicySelected", false)
                .put("predictionCompatibilityClaimed", false).put("sampleId", prepared.document().path("sampleId").asText());
        result.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria())); result.set("executionProfile", executionProfile.document());
        result.set("ps2Preparation", prepared.document()); result.set("ps2Selection", prepared.selection());
        result.putArray("candidateIds").add(prepared.selection().path("candidateId").asText());
        return result;
    }
    private ObjectNode submitPs2(VariantAnalysisJob job, AcmgSubmission request, Ps2DeNovoContextPreparation preparation) {
        if (!AcmgBusinessRuleScope.PS2_DECLARATION.equals(request.declarationVersion())) throw error(HttpStatus.BAD_REQUEST, "PS2_EXPLICIT_DECLARATION_REQUIRED");
        if (request.topN() != 1) throw error(HttpStatus.BAD_REQUEST, "PS2_TOP_ONE_REQUIRED");
        if (request.recommendations() != null || request.association() != null || request.calibration() != null || request.revelApplication() != null
                || !request.hypotheses().isEmpty()) throw error(HttpStatus.BAD_REQUEST, "PS2_EXCLUSIVE_SELECTION_REQUIRED");
        if (request.ps2Selection() == null || !Boolean.TRUE.equals(request.ps2Selection().confirmed())) throw error(HttpStatus.BAD_REQUEST, "PS2_EXPLICIT_CONFIRMATION_REQUIRED");
        ObjectNode declaration = mapper.valueToTree(request), identity = mapper.createObjectNode();
        identity.set("declaration", declaration); identity.set("executionProfile", executionProfile.document());
        identity.set("ps2PublishedInputIdentity", ps2PublishedIdentity(job.getId()));
        String hash = artifacts.requestHash(identity);
        var previous = store.byRequest(job.getId(), hash);
        if (previous.isPresent()) return read(job.getId(), previous.get().id());
        if (request.retryOf() != null) {
            var old = store.run(job.getId(), request.retryOf()).orElseThrow(() -> error(HttpStatus.CONFLICT, "RETRY_RUN_NOT_FOUND"));
            if (!Set.of("FAILED", "COMPLETED_WITH_BLOCKERS").contains(old.status()))
                throw error(HttpStatus.CONFLICT, "RETRY_REQUIRES_TERMINAL_FAILURE");
        }
        var prepared = preparePs2(job, 1, preparation);
        try { preparation.verify(request, prepared); }
        catch (IllegalArgumentException invalid) { throw error(HttpStatus.CONFLICT, invalid.getMessage()); }
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        try {
            if (!store.claim(new AcmgBusinessStore.Run(id, job.getId(), hash, mapper.writeValueAsString(identity), "RUNNING", now, null, null, null)))
                return read(job.getId(), store.byRequest(job.getId(), hash).orElseThrow().id());
        } catch (Exception failed) { throw persistenceFailure(failed, "ACMG_RUN_CLAIM_FAILED"); }
        try {
            var core = preparation.evaluate(prepared);
            var evaluations = core.path("criterionEvaluations");
            boolean complete = evaluations.isArray() && evaluations.size() == 1
                    && Ps2DeNovoCriterionEvaluator.CRITERION.equals(evaluations.path(0).path("criterionId").asText());
            if (!complete) throw new IllegalStateException("PS2_CORE_RESULT_INCOMPLETE");
            boolean evaluated = core.path("evaluation").isObject()
                    && core.path("evaluation").equals(evaluations.path(0))
                    && Set.of("MET", "NOT_MET").contains(evaluations.path(0).at("/evaluationResult/status").asText());
            var response = mapper.createObjectNode().put("runId", id).put("analysisId", job.getId()).put("requestSha256", hash)
                    .put("createdAt", now).put("declarationVersion", request.declarationVersion()).put("dataClass", request.dataClass())
                    .put("acceptanceScope", AcmgBusinessRuleScope.PS2_DEVELOPMENT).put("requestedN", 1).put("selectedCount", 1)
                    .put("status", evaluated ? "COMPLETED" : "COMPLETED_WITH_BLOCKERS").put("formalEnabled", false)
                    .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false)
                    .put("clinicalValidationCompleted", false).put("candidateOrderingChanged", false);
            response.set("executionProfile", executionProfile.document()); response.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria()));
            String candidateId = prepared.selection().path("candidateId").asText(); response.putArray("candidateIds").add(candidateId);
            var row = response.putArray("candidates").addObject().put("candidateId", candidateId).put("enabledRuleResultsComplete", true);
            var attempt = row.putArray("attempts").addObject(); attempt.set("ps2", core); attempt.set("criterionEvaluations", evaluations.deepCopy());
            var blockers = attempt.putArray("blockers");
            if (!evaluated) blockers.addObject().put("reasonCode", core.at("/evaluationResult/reasonCode").asText());
            var artifact = response.putArray("bundles").addObject().put("schemaVersion", "ps2-denovo-business-artifact-v1")
                    .put("runId", id).put("analysisId", job.getId()).put("candidateId", candidateId)
                    .put("classificationEnabled", false).put("adoptionSupported", false);
            artifact.set("preparation", prepared.document()); artifact.set("core", core.deepCopy());
            // Core points may be fractional; the frozen identity canonicalizer intentionally forbids floats.
            // Bind the exact serialized core bytes without changing the core result or that canonicalizer.
            artifact.put("coreRawSha256", AcmgBusinessStore.sha(mapper.writeValueAsString(core)));
            var artifactIdentity = artifact.deepCopy(); artifactIdentity.remove("core");
            artifact.put("bundleId", "ps2-business-artifact-sha256-" + artifacts.requestHash(artifactIdentity));
            var privateData = mapper.createObjectNode(); privateData.set("declaration", declaration);
            String privateJson = mapper.writeValueAsString(privateData); response.put("privateArtifactSha256", AcmgBusinessStore.sha(privateJson));
            store.finish(id, response.path("status").asText(), mapper.writeValueAsString(response), privateJson);
            return read(job.getId(), id);
        } catch (Exception failed) { store.failed(id); throw persistenceFailure(failed, "PS2_EXECUTION_OR_SAVE_FAILED"); }
    }

    private static boolean containsPm6Token(JsonNode node) {
        if (node.isTextual() && node.textValue().startsWith("PM6_DENOVO_CONTEXT@")) return true;
        for (var child : node) if (containsPm6Token(child)) return true;
        return false;
    }
    private ObjectNode pm6PublishedIdentity(String analysisId) {
        try {
            var currentJob = job(analysisId);
            return mapper.createObjectNode().put("analysisId", analysisId)
                    .put("sampleId", currentJob.getSelectedSampleId() == null ? currentJob.getRequestedSampleId() : currentJob.getSelectedSampleId())
                    .put("uploadSha256", currentJob.getFileSha256()).put("inputAssembly", currentJob.getGenomeAssembly().name())
                    .put("analysisMode", currentJob.getAnalysisMode().name())
                    .put("candidatesRawSha256", AcmgBusinessStore.sha(mapper.writeValueAsString(formalCandidates(analysisId, 1))))
                    .put("executionSourceSha256", store.source(analysisId).map(mappedItem -> mappedItem.sha256()).orElse("UNAVAILABLE"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw error(HttpStatus.CONFLICT, "PM6_PUBLISHED_INPUT_UNAVAILABLE"); }
    }
    private Pm6DeNovoContextPreparation.Prepared preparePm6(VariantAnalysisJob job, int topN, Pm6DeNovoContextPreparation preparation) {
        if (topN != 1) throw error(HttpStatus.BAD_REQUEST, "PM6_TOP_ONE_REQUIRED");
        if (job.getStatus() != VariantAnalysisStatus.READY) throw error(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY");
        var ranked = formalCandidates(job.getId(), 1);
        if (ranked.size() != 1) throw error(HttpStatus.CONFLICT, "PM6_FORMAL_CANDIDATE_REQUIRED");
        try {
            var recovered = sources.recover(job, ranked);
            return preparation.prepare(job, ranked.get(0), recovered, pm6PublishedIdentity(job.getId()), executionProfile.document());
        } catch (Exception invalid) { throw error(HttpStatus.CONFLICT, AcmgExecutionSources.code(invalid)); }
    }
    private ObjectNode pm6Options(String analysisId, int topN, Pm6DeNovoContextPreparation preparation) {
        var prepared = preparePm6(job(analysisId), topN, preparation);
        var result = mapper.createObjectNode().put("analysisId", analysisId).put("requestedN", 1).put("selectedCount", 1)
                .put("acceptanceScope", AcmgBusinessRuleScope.PM6_DEVELOPMENT).put("requiredDeclaration", AcmgBusinessRuleScope.PM6_DECLARATION)
                .put("runtimeConfigured", true).put("formalEnabled", false).put("automaticTrigger", false)
                .put("classificationEnabled", false).put("adoptionSupported", false).put("criterionPolicySelected", false)
                .put("predictionCompatibilityClaimed", false).put("sampleId", prepared.document().path("sampleId").asText());
        result.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria())); result.set("executionProfile", executionProfile.document());
        result.set("pm6Preparation", prepared.document()); result.set("pm6Selection", prepared.selection());
        result.putArray("candidateIds").add(prepared.selection().path("candidateId").asText());
        return result;
    }
    private ObjectNode submitPm6(VariantAnalysisJob job, AcmgSubmission request, Pm6DeNovoContextPreparation preparation) {
        if (!AcmgBusinessRuleScope.PM6_DECLARATION.equals(request.declarationVersion())) throw error(HttpStatus.BAD_REQUEST, "PM6_EXPLICIT_DECLARATION_REQUIRED");
        if (request.topN() != 1) throw error(HttpStatus.BAD_REQUEST, "PM6_TOP_ONE_REQUIRED");
        if (request.ps2Selection() != null || request.recommendations() != null || request.association() != null || request.calibration() != null || request.revelApplication() != null
                || !request.hypotheses().isEmpty()) throw error(HttpStatus.BAD_REQUEST, "PM6_EXCLUSIVE_SELECTION_REQUIRED");
        if (request.pm6Selection() == null || !Boolean.TRUE.equals(request.pm6Selection().confirmed())) throw error(HttpStatus.BAD_REQUEST, "PM6_EXPLICIT_CONFIRMATION_REQUIRED");
        ObjectNode declaration = mapper.valueToTree(request), identity = mapper.createObjectNode();
        identity.set("declaration", declaration); identity.set("executionProfile", executionProfile.document());
        identity.set("pm6PublishedInputIdentity", pm6PublishedIdentity(job.getId()));
        String hash = artifacts.requestHash(identity);
        var previous = store.byRequest(job.getId(), hash);
        if (previous.isPresent()) return read(job.getId(), previous.get().id());
        if (request.retryOf() != null) {
            var old = store.run(job.getId(), request.retryOf()).orElseThrow(() -> error(HttpStatus.CONFLICT, "RETRY_RUN_NOT_FOUND"));
            if (!Set.of("FAILED", "COMPLETED_WITH_BLOCKERS").contains(old.status()))
                throw error(HttpStatus.CONFLICT, "RETRY_REQUIRES_TERMINAL_FAILURE");
            JsonNode oldIdentity;
            try {
                oldIdentity = mapper.readTree(old.requestJson());
                if (!artifacts.requestHash(oldIdentity).equals(old.requestSha256())) throw new IllegalArgumentException();
            } catch (Exception invalid) { throw error(HttpStatus.CONFLICT, "RETRY_SOURCE_INTEGRITY_FAILURE"); }
            if (!"acmg-pm6-denovo-development-v1".equals(oldIdentity.at("/executionProfile/profileId").asText())
                    || !AcmgBusinessRuleScope.PM6_DECLARATION.equals(oldIdentity.at("/declaration/declarationVersion").asText())
                    || !oldIdentity.at("/executionProfile/enabledCriteria").equals(mapper.valueToTree(List.of(Pm6DeNovoCriterionEvaluator.CRITERION))))
                throw error(HttpStatus.CONFLICT, "PM6_RETRY_SCOPE_MISMATCH");
        }
        var prepared = preparePm6(job, 1, preparation);
        try { preparation.verify(request, prepared); }
        catch (IllegalArgumentException invalid) { throw error(HttpStatus.CONFLICT, invalid.getMessage()); }
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        try {
            if (!store.claim(new AcmgBusinessStore.Run(id, job.getId(), hash, mapper.writeValueAsString(identity), "RUNNING", now, null, null, null)))
                return read(job.getId(), store.byRequest(job.getId(), hash).orElseThrow().id());
        } catch (Exception failed) { throw persistenceFailure(failed, "ACMG_RUN_CLAIM_FAILED"); }
        try {
            var core = preparation.evaluate(prepared);
            var evaluations = core.path("criterionEvaluations");
            boolean complete = evaluations.isArray() && evaluations.size() == 1
                    && Pm6DeNovoCriterionEvaluator.CRITERION.equals(evaluations.path(0).path("criterionId").asText());
            if (!complete) throw new IllegalStateException("PM6_CORE_RESULT_INCOMPLETE");
            boolean evaluated = core.path("evaluation").isObject()
                    && core.path("evaluation").equals(evaluations.path(0))
                    && Set.of("MET", "NOT_MET").contains(evaluations.path(0).at("/evaluationResult/status").asText());
            var response = mapper.createObjectNode().put("runId", id).put("analysisId", job.getId()).put("requestSha256", hash)
                    .put("createdAt", now).put("declarationVersion", request.declarationVersion()).put("dataClass", request.dataClass())
                    .put("acceptanceScope", AcmgBusinessRuleScope.PM6_DEVELOPMENT).put("requestedN", 1).put("selectedCount", 1)
                    .put("status", evaluated ? "COMPLETED" : "COMPLETED_WITH_BLOCKERS").put("formalEnabled", false)
                    .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false)
                    .put("clinicalValidationCompleted", false).put("candidateOrderingChanged", false);
            response.set("executionProfile", executionProfile.document()); response.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria()));
            String candidateId = prepared.selection().path("candidateId").asText(); response.putArray("candidateIds").add(candidateId);
            var row = response.putArray("candidates").addObject().put("candidateId", candidateId).put("enabledRuleResultsComplete", true);
            var attempt = row.putArray("attempts").addObject(); attempt.set("pm6", core); attempt.set("criterionEvaluations", evaluations.deepCopy());
            var blockers = attempt.putArray("blockers");
            if (!evaluated) blockers.addObject().put("reasonCode", core.at("/evaluationResult/reasonCode").asText());
            var artifact = response.putArray("bundles").addObject().put("schemaVersion", "pm6-denovo-business-artifact-v1")
                    .put("runId", id).put("analysisId", job.getId()).put("candidateId", candidateId)
                    .put("classificationEnabled", false).put("adoptionSupported", false);
            artifact.set("preparation", prepared.document()); artifact.set("core", core.deepCopy());
            // Core points may be fractional; the frozen identity canonicalizer intentionally forbids floats.
            // Bind the exact serialized core bytes without changing the core result or that canonicalizer.
            artifact.put("coreDigestKind", "RAW_BYTES_SHA256").put("artifactIdentityKind", "CANONICAL_WITH_CORE_RAW_DIGEST");
            artifact.put("coreRawSha256", AcmgBusinessStore.sha(mapper.writeValueAsString(core)));
            var artifactIdentity = artifact.deepCopy(); artifactIdentity.remove("core");
            artifact.put("bundleId", "pm6-business-artifact-sha256-" + artifacts.requestHash(artifactIdentity));
            var privateData = mapper.createObjectNode(); privateData.set("declaration", declaration);
            String privateJson = mapper.writeValueAsString(privateData); response.put("privateArtifactSha256", AcmgBusinessStore.sha(privateJson));
            store.finish(id, response.path("status").asText(), mapper.writeValueAsString(response), privateJson);
            return read(job.getId(), id);
        } catch (Exception failed) { store.failed(id); throw persistenceFailure(failed, "PM6_EXECUTION_OR_SAVE_FAILED"); }
    }

    private static boolean containsPp4Token(JsonNode node) {
        if (node.isTextual() && node.textValue().startsWith("PP4_PHENOTYPE_CONTEXT@")) return true;
        for (var child : node) if (containsPp4Token(child)) return true;
        return false;
    }
    private ObjectNode pp4PublishedIdentity(String analysisId) {
        try {
            var currentJob = job(analysisId);
            return mapper.createObjectNode().put("analysisId", analysisId)
                    .put("sampleId", currentJob.getSelectedSampleId() == null ? currentJob.getRequestedSampleId() : currentJob.getSelectedSampleId())
                    .put("uploadSha256", currentJob.getFileSha256()).put("inputAssembly", currentJob.getGenomeAssembly().name())
                    .put("analysisMode", currentJob.getAnalysisMode().name())
                    .put("candidatesRawSha256", AcmgBusinessStore.sha(mapper.writeValueAsString(formalCandidates(analysisId, 1))))
                    .put("executionSourceSha256", store.source(analysisId).map(mappedItem -> mappedItem.sha256()).orElse("UNAVAILABLE"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw error(HttpStatus.CONFLICT, "PP4_PUBLISHED_INPUT_UNAVAILABLE"); }
    }
    private Pp4PhenotypeContextPreparation.Prepared preparePp4(VariantAnalysisJob job, int topN, Pp4PhenotypeContextPreparation preparation) {
        if (topN != 1) throw error(HttpStatus.BAD_REQUEST, "PP4_TOP_ONE_REQUIRED");
        if (job.getStatus() != VariantAnalysisStatus.READY) throw error(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY");
        var ranked = formalCandidates(job.getId(), 1);
        if (ranked.isEmpty()) throw error(HttpStatus.CONFLICT, "PP4_FORMAL_CANDIDATE_REQUIRED");
        try {
            var bundle = preparation.load();
            var pair = preparation.select(ranked, bundle);
            var recovered = sources.recover(job, pair);
            var published = pp4PublishedIdentity(job.getId());
            if (!recovered.snapshotSha256().equals(published.path("executionSourceSha256").asText()))
                throw error(HttpStatus.CONFLICT, "PP4_PUBLISHED_INPUT_CHANGED");
            return preparation.prepare(job, pair, recovered, published, executionProfile.document(), bundle);
        } catch (Exception invalid) { throw error(HttpStatus.CONFLICT, AcmgExecutionSources.code(invalid)); }
    }
    private ObjectNode pp4Options(String analysisId, int topN, Pp4PhenotypeContextPreparation preparation) {
        var prepared = preparePp4(job(analysisId), topN, preparation);
        var result = mapper.createObjectNode().put("analysisId", analysisId).put("requestedN", 1).put("selectedCount", 1)
                .put("acceptanceScope", AcmgBusinessRuleScope.PP4_DEVELOPMENT).put("requiredDeclaration", AcmgBusinessRuleScope.PP4_DECLARATION)
                .put("runtimeConfigured", true).put("formalEnabled", false).put("automaticTrigger", false)
                .put("classificationEnabled", false).put("adoptionSupported", false).put("criterionPolicySelected", false)
                .put("predictionCompatibilityClaimed", false).put("clinicalValidationCompleted", false).put("sampleId", prepared.document().path("sampleId").asText());
        result.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria())); result.set("executionProfile", executionProfile.document());
        result.set("pp4Preparation", prepared.document()); result.set("pp4Selection", prepared.selection());
        result.putArray("candidateIds").add(prepared.selection().path("candidateId").asText());
        return result;
    }
    private ObjectNode submitPp4(VariantAnalysisJob job, AcmgSubmission request, Pp4PhenotypeContextPreparation preparation) {
        if (!AcmgBusinessRuleScope.PP4_DECLARATION.equals(request.declarationVersion())) throw error(HttpStatus.BAD_REQUEST, "PP4_EXPLICIT_DECLARATION_REQUIRED");
        if (request.topN() != 1) throw error(HttpStatus.BAD_REQUEST, "PP4_TOP_ONE_REQUIRED");
        if (request.bs4Selection() != null || request.pp1Selection() != null || request.bp2Selection() != null || request.pm3Selection() != null || request.ps2Selection() != null || request.pm6Selection() != null || request.recommendations() != null || request.association() != null || request.calibration() != null || request.revelApplication() != null
                || !request.hypotheses().isEmpty()) throw error(HttpStatus.BAD_REQUEST, "PP4_EXCLUSIVE_SELECTION_REQUIRED");
        if (request.pp4Selection() == null || !Boolean.TRUE.equals(request.pp4Selection().confirmed())) throw error(HttpStatus.BAD_REQUEST, "PP4_EXPLICIT_CONFIRMATION_REQUIRED");
        ObjectNode declaration = mapper.valueToTree(request), identity = mapper.createObjectNode();
        identity.set("declaration", declaration); identity.set("executionProfile", executionProfile.document());
        identity.set("pp4PublishedInputIdentity", pp4PublishedIdentity(job.getId()));
        String hash = artifacts.requestHash(identity);
        var previous = store.byRequest(job.getId(), hash);
        if (previous.isPresent()) return read(job.getId(), previous.get().id());
        if (request.retryOf() != null) {
            var old = store.run(job.getId(), request.retryOf()).orElseThrow(() -> error(HttpStatus.CONFLICT, "RETRY_RUN_NOT_FOUND"));
            if (!Set.of("FAILED", "COMPLETED_WITH_BLOCKERS").contains(old.status()))
                throw error(HttpStatus.CONFLICT, "RETRY_REQUIRES_TERMINAL_FAILURE");
            JsonNode oldIdentity;
            try {
                oldIdentity = mapper.readTree(old.requestJson());
                if (!artifacts.requestHash(oldIdentity).equals(old.requestSha256())) throw new IllegalArgumentException();
            } catch (Exception invalid) { throw error(HttpStatus.CONFLICT, "RETRY_SOURCE_INTEGRITY_FAILURE"); }
            if (!"acmg-pp4-phenotype-development-v1".equals(oldIdentity.at("/executionProfile/profileId").asText())
                    || !executionProfile.document().path("profileVersion").equals(oldIdentity.at("/executionProfile/profileVersion"))
                    || !AcmgBusinessRuleScope.PP4_DECLARATION.equals(oldIdentity.at("/declaration/declarationVersion").asText())
                    || !oldIdentity.at("/executionProfile/enabledCriteria").equals(mapper.valueToTree(List.of(Pp4PhenotypeCriterionEvaluator.CRITERION))))
                throw error(HttpStatus.CONFLICT, "PP4_RETRY_SCOPE_MISMATCH");
            // A retry must not legitimize a corrupt predecessor's saved scientific result.
            if ("COMPLETED_WITH_BLOCKERS".equals(old.status()) && old.responseJson() == null)
                throw error(HttpStatus.CONFLICT, "RETRY_SOURCE_INTEGRITY_FAILURE");
            trustedRead(job.getId(), old.id());
        }
        var prepared = preparePp4(job, 1, preparation);
        if (!identity.path("pp4PublishedInputIdentity").equals(prepared.document().at("/materialBinding/publishedInputIdentity")))
            throw error(HttpStatus.CONFLICT, "PP4_PUBLISHED_INPUT_CHANGED");
        try { preparation.verify(request, prepared); }
        catch (IllegalArgumentException invalid) { throw error(HttpStatus.CONFLICT, invalid.getMessage()); }
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        try {
            if (!store.claim(new AcmgBusinessStore.Run(id, job.getId(), hash, mapper.writeValueAsString(identity), "RUNNING", now, null, null, null)))
                return read(job.getId(), store.byRequest(job.getId(), hash).orElseThrow().id());
        } catch (Exception failed) { throw persistenceFailure(failed, "ACMG_RUN_CLAIM_FAILED"); }
        try {
            var core = preparation.evaluate(prepared);
            var evaluations = core.path("criterionEvaluations");
            boolean complete = evaluations.isArray() && evaluations.size() == 1
                    && Pp4PhenotypeCriterionEvaluator.CRITERION.equals(evaluations.path(0).path("criterionId").asText());
            if (!complete) throw new IllegalStateException("PP4_CORE_RESULT_INCOMPLETE");
            boolean evaluated = core.path("evaluation").isObject()
                    && core.path("evaluation").equals(evaluations.path(0))
                    && Set.of("MET", "NOT_MET").contains(evaluations.path(0).at("/evaluationResult/status").asText());
            var response = mapper.createObjectNode().put("runId", id).put("analysisId", job.getId()).put("requestSha256", hash)
                    .put("createdAt", now).put("declarationVersion", request.declarationVersion()).put("dataClass", request.dataClass())
                    .put("acceptanceScope", AcmgBusinessRuleScope.PP4_DEVELOPMENT).put("requestedN", 1).put("selectedCount", 1)
                    .put("status", evaluated ? "COMPLETED" : "COMPLETED_WITH_BLOCKERS").put("formalEnabled", false)
                    .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false)
                    .put("clinicalValidationCompleted", false).put("candidateOrderingChanged", false);
            if (request.retryOf() != null) response.put("retryOf", request.retryOf());
            response.set("executionProfile", executionProfile.document()); response.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria()));
            String candidateId = prepared.selection().path("candidateId").asText(); response.putArray("candidateIds").add(candidateId);
            var row = response.putArray("candidates").addObject().put("candidateId", candidateId).put("enabledRuleResultsComplete", true);
            var attempt = row.putArray("attempts").addObject(); attempt.set("pp4", core); attempt.set("criterionEvaluations", evaluations.deepCopy());
            var blockers = attempt.putArray("blockers");
            if (!evaluated) blockers.addObject().put("reasonCode", core.at("/evaluationResult/reasonCode").asText());
            var artifact = response.putArray("bundles").addObject().put("schemaVersion", "pp4-phenotype-business-artifact-v1")
                    .put("runId", id).put("analysisId", job.getId()).put("candidateId", candidateId)
                    .put("classificationEnabled", false).put("adoptionSupported", false);
            artifact.set("preparation", prepared.document()); artifact.set("core", core.deepCopy());
            // Bind the complete stored core bytes independently of business identity projections.
            // Bind the exact serialized core bytes without changing the core result or that canonicalizer.
            artifact.put("coreDigestKind", "RAW_BYTES_SHA256").put("artifactIdentityKind", "CANONICAL_WITH_CORE_RAW_DIGEST");
            artifact.put("coreRawSha256", AcmgBusinessStore.sha(mapper.writeValueAsString(core)));
            var artifactIdentity = artifact.deepCopy(); artifactIdentity.remove("core");
            artifact.put("bundleId", "pp4-business-artifact-sha256-" + artifacts.requestHash(artifactIdentity));
            var privateData = mapper.createObjectNode(); privateData.set("declaration", declaration);
            String privateJson = mapper.writeValueAsString(privateData); response.put("privateArtifactSha256", AcmgBusinessStore.sha(privateJson));
            store.finish(id, response.path("status").asText(), mapper.writeValueAsString(response), privateJson);
            return read(job.getId(), id);
        } catch (Exception failed) { store.failed(id); throw persistenceFailure(failed, "PP4_EXECUTION_OR_SAVE_FAILED"); }
    }

    private static boolean containsBs4Token(JsonNode node) {
        if (node.isTextual() && node.textValue().startsWith("BS4_NONSEGREGATION_CONTEXT@")) return true;
        for (var child : node) if (containsBs4Token(child)) return true;
        return false;
    }
    private ObjectNode bs4PublishedIdentity(String analysisId) {
        try {
            var currentJob = job(analysisId);
            return mapper.createObjectNode().put("analysisId", analysisId)
                    .put("sampleId", currentJob.getSelectedSampleId() == null ? currentJob.getRequestedSampleId() : currentJob.getSelectedSampleId())
                    .put("uploadSha256", currentJob.getFileSha256()).put("inputAssembly", currentJob.getGenomeAssembly().name())
                    .put("analysisMode", currentJob.getAnalysisMode().name())
                    .put("candidatesRawSha256", AcmgBusinessStore.sha(mapper.writeValueAsString(formalCandidates(analysisId, 1))))
                    .put("executionSourceSha256", store.source(analysisId).map(mappedItem -> mappedItem.sha256()).orElse("UNAVAILABLE"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw error(HttpStatus.CONFLICT, "BS4_PUBLISHED_INPUT_UNAVAILABLE"); }
    }
    private Bs4NonSegregationContextPreparation.Prepared prepareBs4(VariantAnalysisJob job, int topN, Bs4NonSegregationContextPreparation preparation) {
        if (topN != 1) throw error(HttpStatus.BAD_REQUEST, "BS4_TOP_ONE_REQUIRED");
        if (job.getStatus() != VariantAnalysisStatus.READY) throw error(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY");
        var ranked = formalCandidates(job.getId(), 1);
        if (ranked.isEmpty()) throw error(HttpStatus.CONFLICT, "BS4_FORMAL_CANDIDATE_REQUIRED");
        try {
            var bundle = preparation.load();
            var pair = preparation.select(ranked, bundle);
            var recovered = sources.recover(job, pair);
            var published = bs4PublishedIdentity(job.getId());
            if (!recovered.snapshotSha256().equals(published.path("executionSourceSha256").asText()))
                throw error(HttpStatus.CONFLICT, "BS4_PUBLISHED_INPUT_CHANGED");
            return preparation.prepare(job, pair, recovered, published, executionProfile.document(), bundle);
        } catch (Exception invalid) { throw error(HttpStatus.CONFLICT, AcmgExecutionSources.code(invalid)); }
    }
    private ObjectNode bs4Options(String analysisId, int topN, Bs4NonSegregationContextPreparation preparation) {
        var prepared = prepareBs4(job(analysisId), topN, preparation);
        var result = mapper.createObjectNode().put("analysisId", analysisId).put("requestedN", 1).put("selectedCount", 1)
                .put("acceptanceScope", AcmgBusinessRuleScope.BS4_DEVELOPMENT).put("requiredDeclaration", AcmgBusinessRuleScope.BS4_DECLARATION)
                .put("runtimeConfigured", true).put("formalEnabled", false).put("automaticTrigger", false)
                .put("classificationEnabled", false).put("adoptionSupported", false).put("criterionPolicySelected", false)
                .put("predictionCompatibilityClaimed", false).put("clinicalValidationCompleted", false).put("sampleId", prepared.document().path("sampleId").asText());
        result.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria())); result.set("executionProfile", executionProfile.document());
        result.set("bs4Preparation", prepared.document()); result.set("bs4Selection", prepared.selection());
        result.putArray("candidateIds").add(prepared.selection().path("candidateId").asText());
        return result;
    }
    private ObjectNode submitBs4(VariantAnalysisJob job, AcmgSubmission request, Bs4NonSegregationContextPreparation preparation) {
        if (!AcmgBusinessRuleScope.BS4_DECLARATION.equals(request.declarationVersion())) throw error(HttpStatus.BAD_REQUEST, "BS4_EXPLICIT_DECLARATION_REQUIRED");
        if (request.topN() != 1) throw error(HttpStatus.BAD_REQUEST, "BS4_TOP_ONE_REQUIRED");
        if (request.pp1Selection() != null || request.bp2Selection() != null || request.pm3Selection() != null || request.ps2Selection() != null || request.pm6Selection() != null || request.recommendations() != null || request.association() != null || request.calibration() != null || request.revelApplication() != null
                || !request.hypotheses().isEmpty()) throw error(HttpStatus.BAD_REQUEST, "BS4_EXCLUSIVE_SELECTION_REQUIRED");
        if (request.bs4Selection() == null || !Boolean.TRUE.equals(request.bs4Selection().confirmed())) throw error(HttpStatus.BAD_REQUEST, "BS4_EXPLICIT_CONFIRMATION_REQUIRED");
        ObjectNode declaration = mapper.valueToTree(request), identity = mapper.createObjectNode();
        identity.set("declaration", declaration); identity.set("executionProfile", executionProfile.document());
        identity.set("bs4PublishedInputIdentity", bs4PublishedIdentity(job.getId()));
        String hash = artifacts.requestHash(identity);
        var previous = store.byRequest(job.getId(), hash);
        if (previous.isPresent()) return read(job.getId(), previous.get().id());
        if (request.retryOf() != null) {
            var old = store.run(job.getId(), request.retryOf()).orElseThrow(() -> error(HttpStatus.CONFLICT, "RETRY_RUN_NOT_FOUND"));
            if (!Set.of("FAILED", "COMPLETED_WITH_BLOCKERS").contains(old.status()))
                throw error(HttpStatus.CONFLICT, "RETRY_REQUIRES_TERMINAL_FAILURE");
            JsonNode oldIdentity;
            try {
                oldIdentity = mapper.readTree(old.requestJson());
                if (!artifacts.requestHash(oldIdentity).equals(old.requestSha256())) throw new IllegalArgumentException();
            } catch (Exception invalid) { throw error(HttpStatus.CONFLICT, "RETRY_SOURCE_INTEGRITY_FAILURE"); }
            if (!"acmg-bs4-nonsegregation-development-v1".equals(oldIdentity.at("/executionProfile/profileId").asText())
                    || !executionProfile.document().path("profileVersion").equals(oldIdentity.at("/executionProfile/profileVersion"))
                    || !AcmgBusinessRuleScope.BS4_DECLARATION.equals(oldIdentity.at("/declaration/declarationVersion").asText())
                    || !oldIdentity.at("/executionProfile/enabledCriteria").equals(mapper.valueToTree(List.of(Bs4NonSegregationCriterionEvaluator.CRITERION))))
                throw error(HttpStatus.CONFLICT, "BS4_RETRY_SCOPE_MISMATCH");
            // A retry must not legitimize a corrupt predecessor's saved scientific result.
            if ("COMPLETED_WITH_BLOCKERS".equals(old.status()) && old.responseJson() == null)
                throw error(HttpStatus.CONFLICT, "RETRY_SOURCE_INTEGRITY_FAILURE");
            trustedRead(job.getId(), old.id());
        }
        var prepared = prepareBs4(job, 1, preparation);
        if (!identity.path("bs4PublishedInputIdentity").equals(prepared.document().at("/materialBinding/publishedInputIdentity")))
            throw error(HttpStatus.CONFLICT, "BS4_PUBLISHED_INPUT_CHANGED");
        try { preparation.verify(request, prepared); }
        catch (IllegalArgumentException invalid) { throw error(HttpStatus.CONFLICT, invalid.getMessage()); }
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        try {
            if (!store.claim(new AcmgBusinessStore.Run(id, job.getId(), hash, mapper.writeValueAsString(identity), "RUNNING", now, null, null, null)))
                return read(job.getId(), store.byRequest(job.getId(), hash).orElseThrow().id());
        } catch (Exception failed) { throw persistenceFailure(failed, "ACMG_RUN_CLAIM_FAILED"); }
        try {
            var core = preparation.evaluate(prepared);
            var evaluations = core.path("criterionEvaluations");
            boolean complete = evaluations.isArray() && evaluations.size() == 1
                    && Bs4NonSegregationCriterionEvaluator.CRITERION.equals(evaluations.path(0).path("criterionId").asText());
            if (!complete) throw new IllegalStateException("BS4_CORE_RESULT_INCOMPLETE");
            boolean evaluated = core.path("evaluation").isObject()
                    && core.path("evaluation").equals(evaluations.path(0))
                    && Set.of("MET", "NOT_MET").contains(evaluations.path(0).at("/evaluationResult/status").asText());
            var response = mapper.createObjectNode().put("runId", id).put("analysisId", job.getId()).put("requestSha256", hash)
                    .put("createdAt", now).put("declarationVersion", request.declarationVersion()).put("dataClass", request.dataClass())
                    .put("acceptanceScope", AcmgBusinessRuleScope.BS4_DEVELOPMENT).put("requestedN", 1).put("selectedCount", 1)
                    .put("status", evaluated ? "COMPLETED" : "COMPLETED_WITH_BLOCKERS").put("formalEnabled", false)
                    .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false)
                    .put("clinicalValidationCompleted", false).put("candidateOrderingChanged", false);
            if (request.retryOf() != null) response.put("retryOf", request.retryOf());
            response.set("executionProfile", executionProfile.document()); response.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria()));
            String candidateId = prepared.selection().path("candidateId").asText(); response.putArray("candidateIds").add(candidateId);
            var row = response.putArray("candidates").addObject().put("candidateId", candidateId).put("enabledRuleResultsComplete", true);
            var attempt = row.putArray("attempts").addObject(); attempt.set("bs4", core); attempt.set("criterionEvaluations", evaluations.deepCopy());
            var blockers = attempt.putArray("blockers");
            if (!evaluated) blockers.addObject().put("reasonCode", core.at("/evaluationResult/reasonCode").asText());
            var artifact = response.putArray("bundles").addObject().put("schemaVersion", "bs4-nonsegregation-business-artifact-v1")
                    .put("runId", id).put("analysisId", job.getId()).put("candidateId", candidateId)
                    .put("classificationEnabled", false).put("adoptionSupported", false);
            artifact.set("preparation", prepared.document()); artifact.set("core", core.deepCopy());
            // Bind the complete stored core bytes independently of business identity projections.
            // Bind the exact serialized core bytes without changing the core result or that canonicalizer.
            artifact.put("coreDigestKind", "RAW_BYTES_SHA256").put("artifactIdentityKind", "CANONICAL_WITH_CORE_RAW_DIGEST");
            artifact.put("coreRawSha256", AcmgBusinessStore.sha(mapper.writeValueAsString(core)));
            var artifactIdentity = artifact.deepCopy(); artifactIdentity.remove("core");
            artifact.put("bundleId", "bs4-business-artifact-sha256-" + artifacts.requestHash(artifactIdentity));
            var privateData = mapper.createObjectNode(); privateData.set("declaration", declaration);
            String privateJson = mapper.writeValueAsString(privateData); response.put("privateArtifactSha256", AcmgBusinessStore.sha(privateJson));
            store.finish(id, response.path("status").asText(), mapper.writeValueAsString(response), privateJson);
            return read(job.getId(), id);
        } catch (Exception failed) { store.failed(id); throw persistenceFailure(failed, "BS4_EXECUTION_OR_SAVE_FAILED"); }
    }

    private static boolean containsPp1Token(JsonNode node) {
        if (node.isTextual() && node.textValue().startsWith("PP1_SEGREGATION_CONTEXT@")) return true;
        for (var child : node) if (containsPp1Token(child)) return true;
        return false;
    }
    private ObjectNode pp1PublishedIdentity(String analysisId) {
        try {
            var currentJob = job(analysisId);
            return mapper.createObjectNode().put("analysisId", analysisId)
                    .put("sampleId", currentJob.getSelectedSampleId() == null ? currentJob.getRequestedSampleId() : currentJob.getSelectedSampleId())
                    .put("uploadSha256", currentJob.getFileSha256()).put("inputAssembly", currentJob.getGenomeAssembly().name())
                    .put("analysisMode", currentJob.getAnalysisMode().name())
                    .put("candidatesRawSha256", AcmgBusinessStore.sha(mapper.writeValueAsString(formalCandidates(analysisId, 1))))
                    .put("executionSourceSha256", store.source(analysisId).map(mappedItem -> mappedItem.sha256()).orElse("UNAVAILABLE"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw error(HttpStatus.CONFLICT, "PP1_PUBLISHED_INPUT_UNAVAILABLE"); }
    }
    private Pp1SegregationContextPreparation.Prepared preparePp1(VariantAnalysisJob job, int topN, Pp1SegregationContextPreparation preparation) {
        if (topN != 1) throw error(HttpStatus.BAD_REQUEST, "PP1_TOP_ONE_REQUIRED");
        if (job.getStatus() != VariantAnalysisStatus.READY) throw error(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY");
        var ranked = formalCandidates(job.getId(), 1);
        if (ranked.isEmpty()) throw error(HttpStatus.CONFLICT, "PP1_FORMAL_CANDIDATE_REQUIRED");
        try {
            var bundle = preparation.load();
            var pair = preparation.select(ranked, bundle);
            var recovered = sources.recover(job, pair);
            var published = pp1PublishedIdentity(job.getId());
            if (!recovered.snapshotSha256().equals(published.path("executionSourceSha256").asText()))
                throw error(HttpStatus.CONFLICT, "PP1_PUBLISHED_INPUT_CHANGED");
            return preparation.prepare(job, pair, recovered, published, executionProfile.document(), bundle);
        } catch (Exception invalid) { throw error(HttpStatus.CONFLICT, AcmgExecutionSources.code(invalid)); }
    }
    private ObjectNode pp1Options(String analysisId, int topN, Pp1SegregationContextPreparation preparation) {
        var prepared = preparePp1(job(analysisId), topN, preparation);
        var result = mapper.createObjectNode().put("analysisId", analysisId).put("requestedN", 1).put("selectedCount", 1)
                .put("acceptanceScope", AcmgBusinessRuleScope.PP1_DEVELOPMENT).put("requiredDeclaration", AcmgBusinessRuleScope.PP1_DECLARATION)
                .put("runtimeConfigured", true).put("formalEnabled", false).put("automaticTrigger", false)
                .put("classificationEnabled", false).put("adoptionSupported", false).put("criterionPolicySelected", false)
                .put("predictionCompatibilityClaimed", false).put("clinicalValidationCompleted", false).put("sampleId", prepared.document().path("sampleId").asText());
        result.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria())); result.set("executionProfile", executionProfile.document());
        result.set("pp1Preparation", prepared.document()); result.set("pp1Selection", prepared.selection());
        result.putArray("candidateIds").add(prepared.selection().path("candidateId").asText());
        return result;
    }
    private ObjectNode submitPp1(VariantAnalysisJob job, AcmgSubmission request, Pp1SegregationContextPreparation preparation) {
        if (!AcmgBusinessRuleScope.PP1_DECLARATION.equals(request.declarationVersion())) throw error(HttpStatus.BAD_REQUEST, "PP1_EXPLICIT_DECLARATION_REQUIRED");
        if (request.topN() != 1) throw error(HttpStatus.BAD_REQUEST, "PP1_TOP_ONE_REQUIRED");
        if (request.bp2Selection() != null || request.pm3Selection() != null || request.ps2Selection() != null || request.pm6Selection() != null || request.recommendations() != null || request.association() != null || request.calibration() != null || request.revelApplication() != null
                || !request.hypotheses().isEmpty()) throw error(HttpStatus.BAD_REQUEST, "PP1_EXCLUSIVE_SELECTION_REQUIRED");
        if (request.pp1Selection() == null || !Boolean.TRUE.equals(request.pp1Selection().confirmed())) throw error(HttpStatus.BAD_REQUEST, "PP1_EXPLICIT_CONFIRMATION_REQUIRED");
        ObjectNode declaration = mapper.valueToTree(request), identity = mapper.createObjectNode();
        identity.set("declaration", declaration); identity.set("executionProfile", executionProfile.document());
        identity.set("pp1PublishedInputIdentity", pp1PublishedIdentity(job.getId()));
        String hash = artifacts.requestHash(identity);
        var previous = store.byRequest(job.getId(), hash);
        if (previous.isPresent()) return read(job.getId(), previous.get().id());
        if (request.retryOf() != null) {
            var old = store.run(job.getId(), request.retryOf()).orElseThrow(() -> error(HttpStatus.CONFLICT, "RETRY_RUN_NOT_FOUND"));
            if (!Set.of("FAILED", "COMPLETED_WITH_BLOCKERS").contains(old.status()))
                throw error(HttpStatus.CONFLICT, "RETRY_REQUIRES_TERMINAL_FAILURE");
            JsonNode oldIdentity;
            try {
                oldIdentity = mapper.readTree(old.requestJson());
                if (!artifacts.requestHash(oldIdentity).equals(old.requestSha256())) throw new IllegalArgumentException();
            } catch (Exception invalid) { throw error(HttpStatus.CONFLICT, "RETRY_SOURCE_INTEGRITY_FAILURE"); }
            if (!"acmg-pp1-segregation-development-v1".equals(oldIdentity.at("/executionProfile/profileId").asText())
                    || !executionProfile.document().path("profileVersion").equals(oldIdentity.at("/executionProfile/profileVersion"))
                    || !AcmgBusinessRuleScope.PP1_DECLARATION.equals(oldIdentity.at("/declaration/declarationVersion").asText())
                    || !oldIdentity.at("/executionProfile/enabledCriteria").equals(mapper.valueToTree(List.of(Pp1SegregationCriterionEvaluator.CRITERION))))
                throw error(HttpStatus.CONFLICT, "PP1_RETRY_SCOPE_MISMATCH");
            // A retry must not legitimize a corrupt predecessor's saved scientific result.
            if ("COMPLETED_WITH_BLOCKERS".equals(old.status()) && old.responseJson() == null)
                throw error(HttpStatus.CONFLICT, "RETRY_SOURCE_INTEGRITY_FAILURE");
            trustedRead(job.getId(), old.id());
        }
        var prepared = preparePp1(job, 1, preparation);
        if (!identity.path("pp1PublishedInputIdentity").equals(prepared.document().at("/materialBinding/publishedInputIdentity")))
            throw error(HttpStatus.CONFLICT, "PP1_PUBLISHED_INPUT_CHANGED");
        try { preparation.verify(request, prepared); }
        catch (IllegalArgumentException invalid) { throw error(HttpStatus.CONFLICT, invalid.getMessage()); }
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        try {
            if (!store.claim(new AcmgBusinessStore.Run(id, job.getId(), hash, mapper.writeValueAsString(identity), "RUNNING", now, null, null, null)))
                return read(job.getId(), store.byRequest(job.getId(), hash).orElseThrow().id());
        } catch (Exception failed) { throw persistenceFailure(failed, "ACMG_RUN_CLAIM_FAILED"); }
        try {
            var core = preparation.evaluate(prepared);
            var evaluations = core.path("criterionEvaluations");
            boolean complete = evaluations.isArray() && evaluations.size() == 1
                    && Pp1SegregationCriterionEvaluator.CRITERION.equals(evaluations.path(0).path("criterionId").asText());
            if (!complete) throw new IllegalStateException("PP1_CORE_RESULT_INCOMPLETE");
            boolean evaluated = core.path("evaluation").isObject()
                    && core.path("evaluation").equals(evaluations.path(0))
                    && Set.of("MET", "NOT_MET").contains(evaluations.path(0).at("/evaluationResult/status").asText());
            var response = mapper.createObjectNode().put("runId", id).put("analysisId", job.getId()).put("requestSha256", hash)
                    .put("createdAt", now).put("declarationVersion", request.declarationVersion()).put("dataClass", request.dataClass())
                    .put("acceptanceScope", AcmgBusinessRuleScope.PP1_DEVELOPMENT).put("requestedN", 1).put("selectedCount", 1)
                    .put("status", evaluated ? "COMPLETED" : "COMPLETED_WITH_BLOCKERS").put("formalEnabled", false)
                    .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false)
                    .put("clinicalValidationCompleted", false).put("candidateOrderingChanged", false);
            if (request.retryOf() != null) response.put("retryOf", request.retryOf());
            response.set("executionProfile", executionProfile.document()); response.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria()));
            String candidateId = prepared.selection().path("candidateId").asText(); response.putArray("candidateIds").add(candidateId);
            var row = response.putArray("candidates").addObject().put("candidateId", candidateId).put("enabledRuleResultsComplete", true);
            var attempt = row.putArray("attempts").addObject(); attempt.set("pp1", core); attempt.set("criterionEvaluations", evaluations.deepCopy());
            var blockers = attempt.putArray("blockers");
            if (!evaluated) blockers.addObject().put("reasonCode", core.at("/evaluationResult/reasonCode").asText());
            var artifact = response.putArray("bundles").addObject().put("schemaVersion", "pp1-segregation-business-artifact-v1")
                    .put("runId", id).put("analysisId", job.getId()).put("candidateId", candidateId)
                    .put("classificationEnabled", false).put("adoptionSupported", false);
            artifact.set("preparation", prepared.document()); artifact.set("core", core.deepCopy());
            // Bind the complete stored core bytes independently of business identity projections.
            // Bind the exact serialized core bytes without changing the core result or that canonicalizer.
            artifact.put("coreDigestKind", "RAW_BYTES_SHA256").put("artifactIdentityKind", "CANONICAL_WITH_CORE_RAW_DIGEST");
            artifact.put("coreRawSha256", AcmgBusinessStore.sha(mapper.writeValueAsString(core)));
            var artifactIdentity = artifact.deepCopy(); artifactIdentity.remove("core");
            artifact.put("bundleId", "pp1-business-artifact-sha256-" + artifacts.requestHash(artifactIdentity));
            var privateData = mapper.createObjectNode(); privateData.set("declaration", declaration);
            String privateJson = mapper.writeValueAsString(privateData); response.put("privateArtifactSha256", AcmgBusinessStore.sha(privateJson));
            store.finish(id, response.path("status").asText(), mapper.writeValueAsString(response), privateJson);
            return read(job.getId(), id);
        } catch (Exception failed) { store.failed(id); throw persistenceFailure(failed, "PP1_EXECUTION_OR_SAVE_FAILED"); }
    }

    private static boolean containsBp2Token(JsonNode node) {
        if (node.isTextual() && node.textValue().startsWith("BP2_PHASE_CONTEXT@")) return true;
        for (var child : node) if (containsBp2Token(child)) return true;
        return false;
    }
    private ObjectNode bp2PublishedIdentity(String analysisId) {
        try {
            var currentJob = job(analysisId);
            return mapper.createObjectNode().put("analysisId", analysisId)
                    .put("sampleId", currentJob.getSelectedSampleId() == null ? currentJob.getRequestedSampleId() : currentJob.getSelectedSampleId())
                    .put("uploadSha256", currentJob.getFileSha256()).put("inputAssembly", currentJob.getGenomeAssembly().name())
                    .put("analysisMode", currentJob.getAnalysisMode().name())
                    .put("candidatesRawSha256", AcmgBusinessStore.sha(mapper.writeValueAsString(formalCandidates(analysisId, AcmgExecutionSources.MAX_CANDIDATES))))
                    .put("executionSourceSha256", store.source(analysisId).map(mappedItem -> mappedItem.sha256()).orElse("UNAVAILABLE"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw error(HttpStatus.CONFLICT, "BP2_PUBLISHED_INPUT_UNAVAILABLE"); }
    }
    private Bp2PhaseContextPreparation.Prepared prepareBp2(VariantAnalysisJob job, int topN, Bp2PhaseContextPreparation preparation) {
        if (topN != 1) throw error(HttpStatus.BAD_REQUEST, "BP2_TOP_ONE_REQUIRED");
        if (job.getStatus() != VariantAnalysisStatus.READY) throw error(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY");
        var ranked = formalCandidates(job.getId(), AcmgExecutionSources.MAX_CANDIDATES);
        if (ranked.isEmpty()) throw error(HttpStatus.CONFLICT, "BP2_FORMAL_CANDIDATE_REQUIRED");
        try {
            var bundle = preparation.load();
            var pair = preparation.select(ranked, bundle);
            var recovered = sources.recover(job, pair);
            var published = bp2PublishedIdentity(job.getId());
            if (!recovered.snapshotSha256().equals(published.path("executionSourceSha256").asText()))
                throw error(HttpStatus.CONFLICT, "BP2_PUBLISHED_INPUT_CHANGED");
            return preparation.prepare(job, pair, recovered, published, executionProfile.document(), bundle);
        } catch (Exception invalid) { throw error(HttpStatus.CONFLICT, AcmgExecutionSources.code(invalid)); }
    }
    private ObjectNode bp2Options(String analysisId, int topN, Bp2PhaseContextPreparation preparation) {
        var prepared = prepareBp2(job(analysisId), topN, preparation);
        var result = mapper.createObjectNode().put("analysisId", analysisId).put("requestedN", 1).put("selectedCount", 1)
                .put("acceptanceScope", AcmgBusinessRuleScope.BP2_DEVELOPMENT).put("requiredDeclaration", AcmgBusinessRuleScope.BP2_DECLARATION)
                .put("runtimeConfigured", true).put("formalEnabled", false).put("automaticTrigger", false)
                .put("classificationEnabled", false).put("adoptionSupported", false).put("criterionPolicySelected", false)
                .put("predictionCompatibilityClaimed", false).put("clinicalValidationCompleted", false).put("sampleId", prepared.document().path("sampleId").asText());
        result.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria())); result.set("executionProfile", executionProfile.document());
        result.set("bp2Preparation", prepared.document()); result.set("bp2Selection", prepared.selection());
        result.putArray("candidateIds").add(prepared.selection().path("candidateId").asText());
        return result;
    }
    private ObjectNode submitBp2(VariantAnalysisJob job, AcmgSubmission request, Bp2PhaseContextPreparation preparation) {
        if (!AcmgBusinessRuleScope.BP2_DECLARATION.equals(request.declarationVersion())) throw error(HttpStatus.BAD_REQUEST, "BP2_EXPLICIT_DECLARATION_REQUIRED");
        if (request.topN() != 1) throw error(HttpStatus.BAD_REQUEST, "BP2_TOP_ONE_REQUIRED");
        if (request.pm3Selection() != null || request.ps2Selection() != null || request.pm6Selection() != null || request.recommendations() != null || request.association() != null || request.calibration() != null || request.revelApplication() != null
                || !request.hypotheses().isEmpty()) throw error(HttpStatus.BAD_REQUEST, "BP2_EXCLUSIVE_SELECTION_REQUIRED");
        if (request.bp2Selection() == null || !Boolean.TRUE.equals(request.bp2Selection().confirmed())) throw error(HttpStatus.BAD_REQUEST, "BP2_EXPLICIT_CONFIRMATION_REQUIRED");
        ObjectNode declaration = mapper.valueToTree(request), identity = mapper.createObjectNode();
        identity.set("declaration", declaration); identity.set("executionProfile", executionProfile.document());
        identity.set("bp2PublishedInputIdentity", bp2PublishedIdentity(job.getId()));
        String hash = artifacts.requestHash(identity);
        var previous = store.byRequest(job.getId(), hash);
        if (previous.isPresent()) return read(job.getId(), previous.get().id());
        if (request.retryOf() != null) {
            var old = store.run(job.getId(), request.retryOf()).orElseThrow(() -> error(HttpStatus.CONFLICT, "RETRY_RUN_NOT_FOUND"));
            if (!Set.of("FAILED", "COMPLETED_WITH_BLOCKERS").contains(old.status()))
                throw error(HttpStatus.CONFLICT, "RETRY_REQUIRES_TERMINAL_FAILURE");
            JsonNode oldIdentity;
            try {
                oldIdentity = mapper.readTree(old.requestJson());
                if (!artifacts.requestHash(oldIdentity).equals(old.requestSha256())) throw new IllegalArgumentException();
            } catch (Exception invalid) { throw error(HttpStatus.CONFLICT, "RETRY_SOURCE_INTEGRITY_FAILURE"); }
            if (!"acmg-bp2-phase-development-v1".equals(oldIdentity.at("/executionProfile/profileId").asText())
                    || !AcmgBusinessRuleScope.BP2_DECLARATION.equals(oldIdentity.at("/declaration/declarationVersion").asText())
                    || !oldIdentity.at("/executionProfile/enabledCriteria").equals(mapper.valueToTree(List.of(Bp2PhaseCriterionEvaluator.CRITERION))))
                throw error(HttpStatus.CONFLICT, "BP2_RETRY_SCOPE_MISMATCH");
            // A retry must not legitimize a corrupt predecessor's saved scientific result.
            if ("COMPLETED_WITH_BLOCKERS".equals(old.status()) && old.responseJson() == null)
                throw error(HttpStatus.CONFLICT, "RETRY_SOURCE_INTEGRITY_FAILURE");
            trustedRead(job.getId(), old.id());
        }
        var prepared = prepareBp2(job, 1, preparation);
        if (!identity.path("bp2PublishedInputIdentity").equals(prepared.document().at("/materialBinding/publishedInputIdentity")))
            throw error(HttpStatus.CONFLICT, "BP2_PUBLISHED_INPUT_CHANGED");
        try { preparation.verify(request, prepared); }
        catch (IllegalArgumentException invalid) { throw error(HttpStatus.CONFLICT, invalid.getMessage()); }
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        try {
            if (!store.claim(new AcmgBusinessStore.Run(id, job.getId(), hash, mapper.writeValueAsString(identity), "RUNNING", now, null, null, null)))
                return read(job.getId(), store.byRequest(job.getId(), hash).orElseThrow().id());
        } catch (Exception failed) { throw persistenceFailure(failed, "ACMG_RUN_CLAIM_FAILED"); }
        try {
            var core = preparation.evaluate(prepared);
            var evaluations = core.path("criterionEvaluations");
            boolean complete = evaluations.isArray() && evaluations.size() == 1
                    && Bp2PhaseCriterionEvaluator.CRITERION.equals(evaluations.path(0).path("criterionId").asText());
            if (!complete) throw new IllegalStateException("BP2_CORE_RESULT_INCOMPLETE");
            boolean evaluated = core.path("evaluation").isObject()
                    && core.path("evaluation").equals(evaluations.path(0))
                    && Set.of("MET", "NOT_MET").contains(evaluations.path(0).at("/evaluationResult/status").asText());
            var response = mapper.createObjectNode().put("runId", id).put("analysisId", job.getId()).put("requestSha256", hash)
                    .put("createdAt", now).put("declarationVersion", request.declarationVersion()).put("dataClass", request.dataClass())
                    .put("acceptanceScope", AcmgBusinessRuleScope.BP2_DEVELOPMENT).put("requestedN", 1).put("selectedCount", 1)
                    .put("status", evaluated ? "COMPLETED" : "COMPLETED_WITH_BLOCKERS").put("formalEnabled", false)
                    .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false)
                    .put("clinicalValidationCompleted", false).put("candidateOrderingChanged", false);
            response.set("executionProfile", executionProfile.document()); response.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria()));
            String candidateId = prepared.selection().path("candidateId").asText(); response.putArray("candidateIds").add(candidateId);
            var row = response.putArray("candidates").addObject().put("candidateId", candidateId).put("enabledRuleResultsComplete", true);
            var attempt = row.putArray("attempts").addObject(); attempt.set("bp2", core); attempt.set("criterionEvaluations", evaluations.deepCopy());
            var blockers = attempt.putArray("blockers");
            if (!evaluated) blockers.addObject().put("reasonCode", core.at("/evaluationResult/reasonCode").asText());
            var artifact = response.putArray("bundles").addObject().put("schemaVersion", "bp2-phase-business-artifact-v1")
                    .put("runId", id).put("analysisId", job.getId()).put("candidateId", candidateId)
                    .put("classificationEnabled", false).put("adoptionSupported", false);
            artifact.set("preparation", prepared.document()); artifact.set("core", core.deepCopy());
            // Bind the complete stored core bytes independently of business identity projections.
            // Bind the exact serialized core bytes without changing the core result or that canonicalizer.
            artifact.put("coreDigestKind", "RAW_BYTES_SHA256").put("artifactIdentityKind", "CANONICAL_WITH_CORE_RAW_DIGEST");
            artifact.put("coreRawSha256", AcmgBusinessStore.sha(mapper.writeValueAsString(core)));
            var artifactIdentity = artifact.deepCopy(); artifactIdentity.remove("core");
            artifact.put("bundleId", "bp2-business-artifact-sha256-" + artifacts.requestHash(artifactIdentity));
            var privateData = mapper.createObjectNode(); privateData.set("declaration", declaration);
            String privateJson = mapper.writeValueAsString(privateData); response.put("privateArtifactSha256", AcmgBusinessStore.sha(privateJson));
            store.finish(id, response.path("status").asText(), mapper.writeValueAsString(response), privateJson);
            return read(job.getId(), id);
        } catch (Exception failed) { store.failed(id); throw persistenceFailure(failed, "BP2_EXECUTION_OR_SAVE_FAILED"); }
    }

    private static boolean containsPm3Token(JsonNode node) {
        if (node.isTextual() && node.textValue().startsWith("PM3_IN_TRANS_CONTEXT@")) return true;
        for (var child : node) if (containsPm3Token(child)) return true;
        return false;
    }
    private ObjectNode pm3PublishedIdentity(String analysisId) {
        try {
            var currentJob = job(analysisId);
            return mapper.createObjectNode().put("analysisId", analysisId)
                    .put("sampleId", currentJob.getSelectedSampleId() == null ? currentJob.getRequestedSampleId() : currentJob.getSelectedSampleId())
                    .put("uploadSha256", currentJob.getFileSha256()).put("inputAssembly", currentJob.getGenomeAssembly().name())
                    .put("analysisMode", currentJob.getAnalysisMode().name())
                    .put("candidatesRawSha256", AcmgBusinessStore.sha(mapper.writeValueAsString(formalCandidates(analysisId, AcmgExecutionSources.MAX_CANDIDATES))))
                    .put("executionSourceSha256", store.source(analysisId).map(mappedItem -> mappedItem.sha256()).orElse("UNAVAILABLE"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw error(HttpStatus.CONFLICT, "PM3_PUBLISHED_INPUT_UNAVAILABLE"); }
    }
    private Pm3InTransContextPreparation.Prepared preparePm3(VariantAnalysisJob job, int topN, Pm3InTransContextPreparation preparation) {
        if (topN != 1) throw error(HttpStatus.BAD_REQUEST, "PM3_TOP_ONE_REQUIRED");
        if (job.getStatus() != VariantAnalysisStatus.READY) throw error(HttpStatus.CONFLICT, "ANALYSIS_NOT_READY");
        var ranked = formalCandidates(job.getId(), AcmgExecutionSources.MAX_CANDIDATES);
        if (ranked.isEmpty()) throw error(HttpStatus.CONFLICT, "PM3_FORMAL_CANDIDATE_REQUIRED");
        try {
            var bundle = preparation.load();
            var pair = preparation.select(ranked, bundle);
            var recovered = sources.recover(job, pair);
            var published = pm3PublishedIdentity(job.getId());
            if (!recovered.snapshotSha256().equals(published.path("executionSourceSha256").asText()))
                throw error(HttpStatus.CONFLICT, "PM3_PUBLISHED_INPUT_CHANGED");
            return preparation.prepare(job, pair, recovered, published, executionProfile.document(), bundle);
        } catch (Exception invalid) { throw error(HttpStatus.CONFLICT, AcmgExecutionSources.code(invalid)); }
    }
    private ObjectNode pm3Options(String analysisId, int topN, Pm3InTransContextPreparation preparation) {
        var prepared = preparePm3(job(analysisId), topN, preparation);
        var result = mapper.createObjectNode().put("analysisId", analysisId).put("requestedN", 1).put("selectedCount", 1)
                .put("acceptanceScope", AcmgBusinessRuleScope.PM3_DEVELOPMENT).put("requiredDeclaration", AcmgBusinessRuleScope.PM3_DECLARATION)
                .put("runtimeConfigured", true).put("formalEnabled", false).put("automaticTrigger", false)
                .put("classificationEnabled", false).put("adoptionSupported", false).put("criterionPolicySelected", false)
                .put("predictionCompatibilityClaimed", false).put("clinicalValidationCompleted", false).put("sampleId", prepared.document().path("sampleId").asText());
        result.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria())); result.set("executionProfile", executionProfile.document());
        result.set("pm3Preparation", prepared.document()); result.set("pm3Selection", prepared.selection());
        result.putArray("candidateIds").add(prepared.selection().path("candidateId").asText());
        return result;
    }
    private ObjectNode submitPm3(VariantAnalysisJob job, AcmgSubmission request, Pm3InTransContextPreparation preparation) {
        if (!AcmgBusinessRuleScope.PM3_DECLARATION.equals(request.declarationVersion())) throw error(HttpStatus.BAD_REQUEST, "PM3_EXPLICIT_DECLARATION_REQUIRED");
        if (request.topN() != 1) throw error(HttpStatus.BAD_REQUEST, "PM3_TOP_ONE_REQUIRED");
        if (request.ps2Selection() != null || request.pm6Selection() != null || request.recommendations() != null || request.association() != null || request.calibration() != null || request.revelApplication() != null
                || !request.hypotheses().isEmpty()) throw error(HttpStatus.BAD_REQUEST, "PM3_EXCLUSIVE_SELECTION_REQUIRED");
        if (request.pm3Selection() == null || !Boolean.TRUE.equals(request.pm3Selection().confirmed())) throw error(HttpStatus.BAD_REQUEST, "PM3_EXPLICIT_CONFIRMATION_REQUIRED");
        ObjectNode declaration = mapper.valueToTree(request), identity = mapper.createObjectNode();
        identity.set("declaration", declaration); identity.set("executionProfile", executionProfile.document());
        identity.set("pm3PublishedInputIdentity", pm3PublishedIdentity(job.getId()));
        String hash = artifacts.requestHash(identity);
        var previous = store.byRequest(job.getId(), hash);
        if (previous.isPresent()) return read(job.getId(), previous.get().id());
        if (request.retryOf() != null) {
            var old = store.run(job.getId(), request.retryOf()).orElseThrow(() -> error(HttpStatus.CONFLICT, "RETRY_RUN_NOT_FOUND"));
            if (!Set.of("FAILED", "COMPLETED_WITH_BLOCKERS").contains(old.status()))
                throw error(HttpStatus.CONFLICT, "RETRY_REQUIRES_TERMINAL_FAILURE");
            JsonNode oldIdentity;
            try {
                oldIdentity = mapper.readTree(old.requestJson());
                if (!artifacts.requestHash(oldIdentity).equals(old.requestSha256())) throw new IllegalArgumentException();
            } catch (Exception invalid) { throw error(HttpStatus.CONFLICT, "RETRY_SOURCE_INTEGRITY_FAILURE"); }
            if (!"acmg-pm3-in-trans-development-v1".equals(oldIdentity.at("/executionProfile/profileId").asText())
                    || !AcmgBusinessRuleScope.PM3_DECLARATION.equals(oldIdentity.at("/declaration/declarationVersion").asText())
                    || !oldIdentity.at("/executionProfile/enabledCriteria").equals(mapper.valueToTree(List.of(Pm3InTransCriterionEvaluator.CRITERION))))
                throw error(HttpStatus.CONFLICT, "PM3_RETRY_SCOPE_MISMATCH");
        }
        var prepared = preparePm3(job, 1, preparation);
        if (!identity.path("pm3PublishedInputIdentity").equals(prepared.document().at("/materialBinding/publishedInputIdentity")))
            throw error(HttpStatus.CONFLICT, "PM3_PUBLISHED_INPUT_CHANGED");
        try { preparation.verify(request, prepared); }
        catch (IllegalArgumentException invalid) { throw error(HttpStatus.CONFLICT, invalid.getMessage()); }
        String id = UUID.randomUUID().toString(), now = Instant.now().toString();
        try {
            if (!store.claim(new AcmgBusinessStore.Run(id, job.getId(), hash, mapper.writeValueAsString(identity), "RUNNING", now, null, null, null)))
                return read(job.getId(), store.byRequest(job.getId(), hash).orElseThrow().id());
        } catch (Exception failed) { throw persistenceFailure(failed, "ACMG_RUN_CLAIM_FAILED"); }
        try {
            var core = preparation.evaluate(prepared);
            var evaluations = core.path("criterionEvaluations");
            boolean complete = evaluations.isArray() && evaluations.size() == 1
                    && Pm3InTransCriterionEvaluator.CRITERION.equals(evaluations.path(0).path("criterionId").asText());
            if (!complete) throw new IllegalStateException("PM3_CORE_RESULT_INCOMPLETE");
            boolean evaluated = core.path("evaluation").isObject()
                    && core.path("evaluation").equals(evaluations.path(0))
                    && Set.of("MET", "NOT_MET").contains(evaluations.path(0).at("/evaluationResult/status").asText());
            var response = mapper.createObjectNode().put("runId", id).put("analysisId", job.getId()).put("requestSha256", hash)
                    .put("createdAt", now).put("declarationVersion", request.declarationVersion()).put("dataClass", request.dataClass())
                    .put("acceptanceScope", AcmgBusinessRuleScope.PM3_DEVELOPMENT).put("requestedN", 1).put("selectedCount", 1)
                    .put("status", evaluated ? "COMPLETED" : "COMPLETED_WITH_BLOCKERS").put("formalEnabled", false)
                    .put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false)
                    .put("clinicalValidationCompleted", false).put("candidateOrderingChanged", false);
            response.set("executionProfile", executionProfile.document()); response.set("enabledCriteria", mapper.valueToTree(executionProfile.criteria()));
            String candidateId = prepared.selection().path("candidateId").asText(); response.putArray("candidateIds").add(candidateId);
            var row = response.putArray("candidates").addObject().put("candidateId", candidateId).put("enabledRuleResultsComplete", true);
            var attempt = row.putArray("attempts").addObject(); attempt.set("pm3", core); attempt.set("criterionEvaluations", evaluations.deepCopy());
            var blockers = attempt.putArray("blockers");
            if (!evaluated) blockers.addObject().put("reasonCode", core.at("/evaluationResult/reasonCode").asText());
            var artifact = response.putArray("bundles").addObject().put("schemaVersion", "pm3-in-trans-business-artifact-v1")
                    .put("runId", id).put("analysisId", job.getId()).put("candidateId", candidateId)
                    .put("classificationEnabled", false).put("adoptionSupported", false);
            artifact.set("preparation", prepared.document()); artifact.set("core", core.deepCopy());
            // Bind the complete stored core bytes independently of business identity projections.
            // Bind the exact serialized core bytes without changing the core result or that canonicalizer.
            artifact.put("coreDigestKind", "RAW_BYTES_SHA256").put("artifactIdentityKind", "CANONICAL_WITH_CORE_RAW_DIGEST");
            artifact.put("coreRawSha256", AcmgBusinessStore.sha(mapper.writeValueAsString(core)));
            var artifactIdentity = artifact.deepCopy(); artifactIdentity.remove("core");
            artifact.put("bundleId", "pm3-business-artifact-sha256-" + artifacts.requestHash(artifactIdentity));
            var privateData = mapper.createObjectNode(); privateData.set("declaration", declaration);
            String privateJson = mapper.writeValueAsString(privateData); response.put("privateArtifactSha256", AcmgBusinessStore.sha(privateJson));
            store.finish(id, response.path("status").asText(), mapper.writeValueAsString(response), privateJson);
            return read(job.getId(), id);
        } catch (Exception failed) { store.failed(id); throw persistenceFailure(failed, "PM3_EXECUTION_OR_SAVE_FAILED"); }
    }

    public ObjectNode read(String analysisId, String runId) { return trustedRead(analysisId, runId).response(); }
    private final AcmgResultViews resultViews = new AcmgResultViews();
    public ObjectNode readView(String analysisId, String runId, String candidateId, int backgroundIndex, String sourceSha) {
        job(analysisId);
        try {
            return resultViews.readWithDetails(() -> store.resultFingerprint(analysisId,runId),
                    () -> {var saved=trustedRead(analysisId,runId);return new AcmgResultViews.VerifiedResult(saved.response(),
                        full->AcmgEvidenceDetailProjection.enrich(mapper,full,saved.privateData().path("localBatch"),
                            saved.privateData().path("currentClinicalInventory"),saved.response().path("currentClinicalEvidence")));},
                    candidateId,backgroundIndex,sourceSha);
        } catch (IllegalArgumentException missing) { throw error(HttpStatus.NOT_FOUND,missing.getMessage()); }
        catch (IllegalStateException changed) { throw error(HttpStatus.CONFLICT,changed.getMessage()); }
    }
    public ObjectNode controlStatus(String analysisId, String runId) {
        job(analysisId);
        try { return AcmgRunControl.status(store,analysisId,runId); }
        catch (IllegalArgumentException missing) { throw error(HttpStatus.NOT_FOUND,missing.getMessage()); }
    }
    public ObjectNode control(String analysisId, String runId, String action) {
        job(analysisId);
        try { return AcmgRunControl.command(store,analysisId,runId,action); }
        catch (IllegalArgumentException invalid) { throw error(HttpStatus.CONFLICT,invalid.getMessage()); }
    }
    /** Live source progress is separate from the immutable saved scientific result. Never starts work. */
    public ObjectNode deNovoSourceTaskStatus(String analysisId, String runId) {
        var saved = trustedRead(analysisId,runId);
        var binding = saved.privateData().at("/localBatch/identity");
        if (taskStatusRuntime==null || localBatchBinding==null || !localBatchBinding.equals(binding))
            throw error(HttpStatus.CONFLICT,"DENOVO_TASK_SAVED_BINDING_UNAVAILABLE");
        var supply = saved.privateData().at("/localBatch/modules/deNovo/sourceSupply");
        String id = (supply.has("sourceTask") ? supply.path("sourceTask") : supply).path("taskId").asText();
        if (!id.matches("[a-f0-9]{64}")) throw error(HttpStatus.NOT_FOUND,"DENOVO_TASK_NOT_FOUND");
        try {
            var status=Objects.requireNonNull(taskStatusRuntime).deNovoSourceTaskStatus(id);
            status.put("analysisId",analysisId).put("runId",runId).put("savedResultChanged",false);
            return status;
        } catch (java.io.IOException unavailable) { throw error(HttpStatus.SERVICE_UNAVAILABLE,"DENOVO_TASK_STATUS_UNAVAILABLE"); }
    }
    boolean sameRetryIdentity(ObjectNode prior, ObjectNode wanted) {
        var left = prior.deepCopy(); var right = wanted.deepCopy();
        ((ObjectNode) left.path("declaration")).remove("retryOf");
        ((ObjectNode) right.path("declaration")).remove("retryOf");
        // Use the same numeric/key canonicalization as normal request deduplication. Jackson's
        // IntNode/LongNode/DecimalNode equality can change after a database JSON round trip.
        return artifacts.requestHash(left).equals(artifacts.requestHash(right));
    }
    record TrustedRun(AcmgBusinessStore.Run record, ObjectNode response, JsonNode request, JsonNode privateData) { }
    private TrustedRun trustedRead(String analysisId, String runId) {
        job(analysisId);
        var record = store.run(analysisId, runId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "ACMG_RUN_NOT_FOUND"));
        if (record.responseJson() == null) {
            try {
                JsonNode identity = mapper.readTree(record.requestJson());
                if (!artifacts.requestHash(identity).equals(record.requestSha256())) throw new IllegalStateException();
                var pending = mapper.createObjectNode().put("runId", record.id()).put("analysisId", analysisId)
                        .put("status", record.status()).put("createdAt", record.createdAt()).put("resultsAvailable", false);
                if ("RUNNING".equals(record.status())) {
                    pending.set("progress", AcmgExecutionProgress.forRun(analysisId, runId));
                    var control = controlStatus(analysisId,runId); pending.set("executionControl",control);
                    if (Set.of("INTERRUPTED","RECOVERY_REQUIRED").contains(control.path("state").asText())) pending.put("status",control.path("state").asText());
                }
                // Scope metadata only, not a fabricated result or a disclosure of the private declaration.
                if (identity.path("executionProfile").isObject())
                    pending.set("retryExecutionProfile", identity.path("executionProfile").deepCopy());
                return new TrustedRun(record, pending, identity, mapper.nullNode());
            } catch (Exception corrupt) { throw error(HttpStatus.SERVICE_UNAVAILABLE, "ACMG_SAVED_RESULT_INTEGRITY_FAILURE"); }
        }
        try {
            if (!AcmgBusinessStore.sha(record.responseJson()).equals(record.responseSha256())) throw new IllegalStateException();
            ObjectNode value = (ObjectNode) mapper.readTree(record.responseJson());
            JsonNode savedRequest = mapper.readTree(record.requestJson());
            JsonNode privateData = mapper.readTree(record.privateJson());
            JsonNode savedDeclaration = savedRequest.has("executionProfile") ? savedRequest.path("declaration") : savedRequest;
            if (savedRequest.has("executionProfile") && (!savedRequest.path("executionProfile").equals(value.path("executionProfile"))
                    || !savedRequest.path("executionProfile").path("enabledCriteria").equals(value.path("enabledCriteria")))) throw new IllegalStateException();
            if (!artifacts.requestHash(savedRequest).equals(record.requestSha256())
                    || !savedDeclaration.equals(privateData.path("declaration"))) throw new IllegalStateException();
            if (!record.id().equals(value.path("runId").asText()) || !record.analysisId().equals(value.path("analysisId").asText())
                    || !record.requestSha256().equals(value.path("requestSha256").asText()) || !record.status().equals(value.path("status").asText())
                    || !AcmgBusinessStore.sha(record.privateJson()).equals(value.path("privateArtifactSha256").asText())) throw new IllegalStateException();
            boolean hasArchive=privateData.has("currentClinicalArchive");
            boolean selectedPp1=false;
            for(var criterion:savedRequest.at("/executionProfile/enabledCriteria"))
                selectedPp1 |= "ACMG_AMP_2015:PP1".equals(criterion.asText());
            boolean nativeLifecycle=selectedPp1 && "POSTCLAIM_CONSUMER_TERMINALS_ONCE_BEFORE_PP1_V1".equals(
                    savedRequest.at("/executionProfile/moduleBindings/pp1/currentContributionLifecycle").asText());
            var closedPp4=privateData.at("/localBatch/modules/pp1/currentContributions");
            boolean closedWorkbench="pp1-closed-workbench-pp4-scope-v1".equals(closedPp4.path("schemaVersion").asText());
            if (closedWorkbench) {
                var actual=privateData.at("/localBatch/currentConsumerExecution");
                if (!(actual instanceof ObjectNode object)) throw new IllegalStateException("PP1_CURRENT_CONTRIBUTION_SAVED_REGISTRY_MISSING");
                var adapter=new RuntimeEvidenceV2Adapter(mapper);
                var execution=object.deepCopy();
                execution.remove("executionSha256");
                var proof=((ObjectNode)closedPp4).deepCopy();
                proof.remove("proofSha256");
                if (!selectedPp1 || !"POSTCLAIM_NO_PP4_REGISTERED_SCOPE_V1".equals(
                            savedRequest.at("/executionProfile/moduleBindings/pp1/currentContributionLifecycle").asText())
                        || !analysisId.equals(closedPp4.path("analysisId").asText()) || !runId.equals(closedPp4.path("runId").asText())
                        || !record.requestSha256().equals(closedPp4.path("requestSha256").asText())
                        || !closedPp4.path("executionSourceSha256").equals(value.path("executionSourceSha256"))
                        || !closedPp4.path("consumerExecutionSha256").equals(actual.path("executionSha256"))
                        || !adapter.canonicalSha256(execution).equals(actual.path("executionSha256").asText())
                        || !closedPp4.path("configuredCriteria").equals(actual.path("configuredCriteria"))
                        || !actual.at("/registry/pp4/configured").isBoolean() || actual.at("/registry/pp4/configured").asBoolean()
                        || !adapter.canonicalSha256(proof).equals(closedPp4.path("proofSha256").asText()))
                    throw new IllegalStateException("PP1_CURRENT_CONTRIBUTION_SAVED_REGISTRY_MISMATCH");
            }
            boolean hasNativeOutputs=privateData.has("currentClinicalInventory") || privateData.has("currentClinicalUse")
                    || !closedWorkbench && privateData.at("/localBatch/currentConsumerExecution").isObject()
                    || nativeLifecycle && (privateData.path("localBatch").isObject() || value.path("localBatch").isObject());
            // Both references disappearing together must not turn a native-current result
            // into a legacy history row with no required scientific originals. Failed runs
            // before phase one, and genuine legacy runs, have no such native outputs.
            if(hasArchive != value.has("currentClinicalArchiveSha256") || hasNativeOutputs && !hasArchive)
                throw new IllegalStateException("CURRENT_CLINICAL_ARCHIVE_REQUIRED");
            if(hasArchive) {
                if(!value.path("currentClinicalArchiveSha256").equals(privateData.at("/currentClinicalArchive/archiveSha256")))
                    throw new IllegalStateException("CURRENT_CLINICAL_ARCHIVE_REFERENCE_MISMATCH");
                // Read saved bytes only. No producer, source registration, native session or
                // current-use grant is recreated by history or the full JSON export.
                var expanded=CurrentClinicalArchive.verifyAndExpand(mapper,privateData.path("currentClinicalArchive"),
                        privateData.path("currentClinicalInventory"),analysisId,runId);
                value.set("currentClinicalEvidence",expanded);
            }
            var fullBatch = privateData.path("localBatch");
            var pp2Observation = savedRequest.path("pp2OtherPhenotypeSourceSnapshot");
            var pp2PublicArchive = value.at("/localBatch/pp2OtherPhenotypeArchiveSha256");
            var pp2DerivedPublicArchive = value.at("/localBatch/pp2OtherPhenotypeDerivedArchiveSha256");
            boolean pp2DerivedPresent = fullBatch.has("pp2OtherPhenotypeDerivedArchive")
                    || fullBatch.has("pp2OtherPhenotypeDerivedArchiveSha256") || !pp2DerivedPublicArchive.isMissingNode();
            boolean pp2Observed = pp2Observation.path("observationSha256").asText().matches("[a-f0-9]{64}");
            boolean pp2Products = pp2Observed && (Set.of("COMPLETED", "COMPLETED_WITH_BLOCKERS").contains(record.status())
                    || fullBatch.isObject() || value.path("localBatch").isObject());
            if (pp2Products || fullBatch.has("pp2OtherPhenotypeArchive") || !pp2PublicArchive.isMissingNode() || pp2DerivedPresent) {
                if (!pp2Observed || !pp2Observation.equals(fullBatch.path("pp2OtherPhenotypeSourceIdentity"))
                        || !pp2PublicArchive.isTextual() || !pp2PublicArchive.equals(fullBatch.path("pp2OtherPhenotypeArchiveSha256")))
                    throw new IllegalStateException("PP2_SAVED_SOURCE_ARCHIVE_BINDING_MISMATCH");
                var pp2VerifiedBase = Pp2OtherPhenotypeArchive.verifyAndExport(mapper, fullBatch.path("pp2OtherPhenotypeArchive"), fullBatch);
                value.set("pp2OtherPhenotypeEvidence", pp2VerifiedBase);
                if (pp2DerivedPresent || Pp2OtherPhenotypeArchive.derivedEvidenceRequired(fullBatch, pp2VerifiedBase)) {
                    if (!pp2DerivedPublicArchive.isTextual()
                            || !pp2DerivedPublicArchive.equals(fullBatch.path("pp2OtherPhenotypeDerivedArchiveSha256")))
                        throw new IllegalStateException("PP2_SAVED_DERIVED_ARCHIVE_BINDING_MISMATCH");
                    value.set("pp2OtherPhenotypeDerivedEvidence", Pp2OtherPhenotypeArchive.verifyDerivedAndExport(mapper,
                            fullBatch.path("pp2OtherPhenotypeDerivedArchive"), fullBatch, pp2VerifiedBase));
                }
            }
            boolean hasFullRnaArchive = fullBatch.has("fullRnaArchive");
            var publicRnaArchiveSha = value.at("/localBatch/fullRnaArchiveSha256");
            boolean savedFullRnaConfigured = false;
            for (var criterion : savedRequest.at("/executionProfile/enabledCriteria")) {
                String module = switch (criterion.asText()) {
                    case "ACMG_AMP_2015:PVS1" -> "pvs1";
                    case "ACMG_AMP_2015:PM4" -> "pm4";
                    case "ACMG_AMP_2015:BP3" -> "bp3";
                    default -> "";
                };
                var savedRnaBinding = savedRequest.at("/executionProfile/moduleBindings/" + module
                        + "/runtimeBinding/fullRnaExecution");
                if (!module.isEmpty() && savedRnaBinding.path("capabilityConfigured").asBoolean()) {
                    savedFullRnaConfigured = true;
                    if (fullBatch.isObject() && !savedRnaBinding.equals(fullBatch.at("/identity/fullRnaExecution")))
                        throw new IllegalStateException("FULL_RNA_SAVED_BINDING_MISMATCH");
                }
            }
            var savedRnaObservation = savedRequest.path("fullRnaSourceSnapshot");
            boolean savedRnaArchiveRequired = savedRnaObservation.path("archiveRequired").asBoolean();
            if (savedRnaArchiveRequired && !savedFullRnaConfigured)
                throw new IllegalStateException("FULL_RNA_SAVED_CAPABILITY_REQUIRED");
            if (savedFullRnaConfigured && fullBatch.isObject()
                    && !savedRnaObservation.equals(fullBatch.path("fullRnaObservedSourceIdentity")))
                throw new IllegalStateException("FULL_RNA_SAVED_OBSERVATION_MISMATCH");
            // The immutable submitted observation pins this requirement even if both
            // whole output batches disappear. Before-gate terminal results save an empty
            // NOT_EXECUTED archive; a failure before any product need not fabricate one.
            boolean savedFullRnaOutputs = savedFullRnaConfigured && savedRnaArchiveRequired
                    && (Set.of("COMPLETED", "COMPLETED_WITH_BLOCKERS").contains(record.status())
                        || fullBatch.isObject() || value.path("localBatch").isObject());
            if (savedFullRnaOutputs || FullRnaEvidenceArchive.required(fullBatch)
                    || hasFullRnaArchive || !publicRnaArchiveSha.isMissingNode()) {
                if (!hasFullRnaArchive || !publicRnaArchiveSha.isTextual()
                        || !publicRnaArchiveSha.equals(fullBatch.path("fullRnaArchiveSha256"))
                        || !publicRnaArchiveSha.equals(fullBatch.at("/fullRnaArchive/archiveSha256")))
                    throw new IllegalStateException("FULL_RNA_ARCHIVE_REFERENCE_MISMATCH");
                // Saved originals and mechanical facts only; history never rebuilds a live qualification token.
                value.set("fullRnaEvidence", FullRnaEvidenceArchive.verifyAndExport(mapper,
                        fullBatch.path("fullRnaArchive"), fullBatch));
            }
            return new TrustedRun(record, value, savedRequest, privateData);
        } catch (Exception corrupt) { throw error(HttpStatus.SERVICE_UNAVAILABLE, "ACMG_SAVED_RESULT_INTEGRITY_FAILURE"); }
    }
    public List<ObjectNode> list(String analysisId) {
        job(analysisId);
        return store.runs(analysisId).stream().map(r -> mapper.createObjectNode().put("runId", r.id()).put("status", "RUNNING".equals(r.status()) ? controlStatus(analysisId,r.id()).path("state").asText() : r.status()).put("createdAt", r.createdAt())
                .put("resultUrl", "/variant-analyses/" + analysisId + "/acmg/runs/" + r.id())).toList();
    }
    public ObjectNode candidate(String analysisId, String candidateId, String runId) {
        ObjectNode saved = read(analysisId, runId), view = mapper.createObjectNode().put("runId", runId).put("analysisId", analysisId).put("candidateId", candidateId);
        var rows = view.putArray("results"); for (var row : saved.path("candidates")) if (candidateId.equals(row.path("candidateId").asText())) rows.add(row.deepCopy());
        var bundles = view.putArray("bundles"); for (var row : saved.path("bundles")) if (candidateId.equals(row.path("candidateId").asText())) bundles.add(row.deepCopy());
        if (saved.has("applications")) {
            var applications = view.putArray("applications");
            for (var row : saved.path("applications")) if (!row.has("candidateId") || candidateId.equals(row.path("candidateId").asText())) applications.add(row.deepCopy());
        }
        view.set("status", saved.path("status")); view.set("enabledCriteria", saved.path("enabledCriteria"));
        view.set("executionProfile", saved.path("executionProfile")); return view;
    }
    private VariantAnalysisJob job(String id) { return jobs.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND")); }
    private static RuntimeException persistenceFailure(Exception failure, String fallback) {
        if (failure instanceof org.springframework.dao.QueryTimeoutException timeout) return timeout;
        if (failure instanceof org.springframework.dao.PessimisticLockingFailureException locked) return locked;
        return error(HttpStatus.SERVICE_UNAVAILABLE, fallback);
    }
    private static ResponseStatusException error(HttpStatus status, String reason) { return new ResponseStatusException(java.util.Objects.requireNonNull(status), reason); }
}
