package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import java.util.*;

/** Independent single-rule orchestrator. Instances are scoped to a run, never a cross-case result cache. */
public final class Bs1Runtime {
    public List<JsonNode> policyRecords() { return policies.stream().<JsonNode>map(value -> Objects.requireNonNull(value).deepCopy()).toList(); }
    /** Independent, score-free policy lookup; it does not assert variant/source readiness. */
    public static ObjectNode contextOption(ObjectMapper mapper, List<JsonNode> policies, JsonNode relation, String moi) {
        var matches = policies.stream().filter(p -> p.at("/scope/hgncId").equals(relation.path("hgncId"))
                && p.at("/scope/mondoId").equals(relation.path("mondoId"))
                && java.util.stream.StreamSupport.stream(p.at("/scope/modes").spliterator(), false).anyMatch(m -> m.asText().equals(moi))).toList();
        var value = mapper.createObjectNode().put("stableRelationshipKey", relation.path("stableRelationshipKey").asText());
        if (matches.size() != 1) return value.put("available", false).put("reasonCode", matches.isEmpty() ? "BS1_POLICY_NO_RECORD" : "BS1_POLICY_AMBIGUOUS");
        var policy = matches.get(0); String issue = Bs1Policy.issue(policy);
        value.put("policyId", policy.path("policyId").asText()).put("policyVersion", policy.path("version").asText())
                .put("policySha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(policy))
                .put("transcriptRequirement", policy.at("/requiredContext/transcript").asText());
        if (policy.at("/scope/transcripts").isArray()) value.set("transcripts", policy.at("/scope/transcripts").deepCopy());
        else value.putArray("transcripts");
        if (issue == null && !"COMPATIBLE".equals(policy.at("/resourceCompatibility/status").asText())) issue = "BS1_RESOURCE_COMPATIBILITY_PENDING";
        return value.put("available", issue == null).put("reasonCode", issue == null ? "BS1_CONTEXT_CONFIRMATION_REQUIRED" : issue);
    }
    public static final String DECLARATION = "bs1-explicit-development-v1";
    private final ObjectMapper mapper;
    private final List<JsonNode> policies;
    @org.springframework.lang.Nullable private final VariantEvidenceProvider provider;
    @org.springframework.lang.Nullable private PopulationRuntimeRouting populationRouting;
    @org.springframework.lang.Nullable private final Bs1NativeFilterSupplement nativeFilters;
    private final Map<String, ObjectNode> observations = new LinkedHashMap<>();
    private final Map<String, String> observationFailures = new LinkedHashMap<>();
    private final Map<String, ObjectNode> nativeObservations = new LinkedHashMap<>();
    private int providerCalls, evaluatorCalls, nativeCalls;
    private boolean formalActivation, executionAllowed;
    private boolean localExomes;
    @org.springframework.lang.Nullable private ObjectNode knowledgeIdentity;
    @org.springframework.lang.Nullable private Bs1KnowledgeCatalog knowledge;
    @org.springframework.lang.Nullable public Bs1KnowledgeCatalog knowledgeCatalog() { return knowledge; }
    public boolean isLocalExomes() { return localExomes; }
    public boolean isPolicyRouted() { return populationRouting != null; }
    public static Bs1Runtime localExomes(ObjectMapper mapper, org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties settings, boolean enabled) {
        return localExomes(mapper, settings, enabled, null);
    }
    public static Bs1Runtime localExomes(ObjectMapper mapper, org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties settings, boolean enabled,
            @org.springframework.lang.Nullable Bs1KnowledgeCatalog knowledge) {
        var records = Bs1LocalExomes.policies(mapper);
        var value = new Bs1Runtime(mapper, knowledge == null ? records : knowledge.merge(records), Ba1LocalExomes.provider(settings), null);
        if (knowledge != null) { value.knowledgeIdentity = knowledge.identity(); value.knowledge = knowledge; }
        value.localExomes = true; value.formalActivation = true; value.executionAllowed = enabled; return value;
    }
    public Bs1Runtime withFormalActivation(boolean allowed) {
        if (populationRouting != null) throw new IllegalArgumentException("BS1_FORMAL_ROUTING_FACTORY_REQUIRED");
        if (!policies.equals(formalPolicies(mapper))) throw new IllegalArgumentException("BS1_FORMAL_POLICY_SET_MISMATCH");
        formalActivation = true; executionAllowed = allowed; return this;
    }
    /** Server-pinned catalog and legacy base; request fields cannot supply policies or source settings. */
    public static Bs1Runtime routed(ObjectMapper mapper, boolean localExomesBase,
            org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties exomeSettings,
            org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties jointSettings, boolean allowed,
            @org.springframework.lang.Nullable Bs1KnowledgeCatalog knowledge,
            @org.springframework.lang.Nullable Bs1NativeFilterSupplement supplement) {
        var base=localExomesBase ? Bs1LocalExomes.policies(mapper) : formalPolicies(mapper);
        var records=knowledge == null ? base : knowledge.merge(base);
        var value=new Bs1Runtime(mapper,records,PopulationRuntimeRouting.configured(mapper,exomeSettings,jointSettings),supplement);
        value.formalActivation=true; value.executionAllowed=allowed;
        if (knowledge != null) { value.knowledge=knowledge; value.knowledgeIdentity=knowledge.identity(); }
        return value;
    }
    /** Explicit development providers; this constructor cannot acquire formal activation. */
    public static Bs1Runtime routedDevelopment(ObjectMapper mapper, List<JsonNode> policies,
            VariantEvidenceProvider exomes, VariantEvidenceProvider joint,
            @org.springframework.lang.Nullable Bs1NativeFilterSupplement supplement) {
        return new Bs1Runtime(mapper,policies,PopulationRuntimeRouting.development(mapper,exomes,joint),supplement);
    }
    private Bs1Runtime(ObjectMapper mapper, List<JsonNode> policies, PopulationRuntimeRouting routing,
            @org.springframework.lang.Nullable Bs1NativeFilterSupplement supplement) {
        this.mapper=Objects.requireNonNull(mapper); var copies=new ArrayList<JsonNode>();
        for (var policy:policies) { if (policy == null) throw new IllegalArgumentException("BS1_POLICY_REQUIRED"); copies.add(policy.deepCopy()); }
        this.policies=List.copyOf(copies); provider=null; nativeFilters=supplement; populationRouting=routing;
    }
    public static List<JsonNode> formalPolicies(ObjectMapper mapper) {
        try (var in = Bs1Runtime.class.getResourceAsStream("/acmg/bs1-local-release-v1.json")) {
            if (in == null) throw new java.io.IOException("missing local release");
            var release = mapper.readTree(in); var records = new ArrayList<JsonNode>();
            if (!"bs1-local-business-release-v1".equals(release.path("schemaVersion").asText()))
                throw new IllegalArgumentException("BS1_FORMAL_RELEASE_INVALID");
            for (var policy : release.path("policies")) {
                if (!"LOCAL_BUSINESS_RELEASE_V1".equals(policy.path("activation").asText())
                        || Bs1Policy.issue(policy) != null || !"COMPATIBLE".equals(policy.at("/resourceCompatibility/status").asText()))
                    throw new IllegalArgumentException("BS1_FORMAL_POLICY_NOT_EXECUTABLE");
                records.add(policy);
            }
            if (records.isEmpty()) throw new IllegalArgumentException("BS1_FORMAL_POLICY_SET_EMPTY");
            return List.copyOf(records);
        } catch (java.io.IOException invalid) { throw new IllegalStateException("BS1_FORMAL_POLICIES_UNAVAILABLE", invalid); }
    }
    public Bs1Runtime(ObjectMapper mapper, List<JsonNode> policies, VariantEvidenceProvider provider,
            @org.springframework.lang.Nullable Bs1NativeFilterSupplement nativeFilters) {
        this.mapper = Objects.requireNonNull(mapper);
        var copies = new ArrayList<JsonNode>();
        for (var policy : policies) { if (policy == null) throw new IllegalArgumentException("BS1_POLICY_REQUIRED"); copies.add(policy.deepCopy()); }
        this.policies = List.copyOf(copies);
        this.provider = Objects.requireNonNull(provider); this.nativeFilters = nativeFilters;
    }
    public static List<JsonNode> policies(ObjectMapper mapper) {
        try (var in = Bs1Runtime.class.getResourceAsStream("/acmg/bs1-policies-v2.json")) {
            if (in == null) throw new java.io.IOException("missing policies");
            var list = new ArrayList<JsonNode>(); mapper.readTree(in).path("policies").forEach(list::add); return List.copyOf(list);
        } catch (java.io.IOException invalid) { throw new IllegalStateException("BS1_POLICIES_UNAVAILABLE", invalid); }
    }
    /** Internal construction only: never activated by a client-supplied criterion or policy path. */
    public static Bs1Runtime configured(ObjectMapper mapper, List<JsonNode> policies,
            org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties settings,
            @org.springframework.lang.Nullable Bs1NativeFilterSupplement supplement) {
        return new Bs1Runtime(mapper, policies, new GnomadTabixProvider(settings,
                GnomadPopulationInputProfile.loadExecutableV2(), true, true), supplement);
    }
    public ObjectNode identity() {
        var routing=populationRouting;
        if (routing != null) return routedIdentity(routing);
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var result = mapper.createObjectNode().put("schemaVersion", "bs1-runtime-identity-v1").put("evaluatorVersion", Bs1CriterionEvaluator.VERSION)
                .put("qualityAdapterVersion", Bs1JointFilterSource.VERSION)
                .put("jointFilterSourceSha256", adapter.canonicalSha256(Bs1JointFilterSource.contract(mapper)))
                .put("contextVersion", "evidence-context-v3").put("observationVersion", PopulationObservation.VERSION)
                .put("policiesSha256", adapter.canonicalSha256(mapper.valueToTree(policies)))
                .put("inputProfileSha256", GnomadPopulationInputProfile.loadExecutableV2().getProfileSha256())
                .put("nativeSourceSha256", adapter.canonicalSha256(Bs1NativeFilterSupplement.contract(mapper)))
                .put("nativeSupplementEnabled", nativeFilters != null).put("formalEnabled", formalActivation && executionAllowed);
        if (formalActivation) result.put("activationContract", localExomes ? "LOCAL_EXOMES_BUSINESS_V1" : "LOCAL_BUSINESS_RELEASE_V1");
        if (localExomes) {
            result.set("dataScope", Bs1LocalExomes.scope(mapper));
            result.put("inputProfileSha256", GnomadPopulationInputProfile.loadLocalBa1().getProfileSha256());
            result.put("qualityAdapterVersion", Bs1LocalExomes.VERSION);
            result.remove(java.util.List.of("jointFilterSourceSha256", "nativeSourceSha256"));
            if (knowledgeIdentity != null) result.set("knowledgeIdentity", knowledgeIdentity.deepCopy());
        }
        if (policies.stream().anyMatch(Bs1SexChromosomePolicy::isJoint)) {
            result.put("jointSexStatisticVersion", Bs1SexChromosomeStatistic.JOINT_VERSION)
                    .put("jointSexObservationVersion", GnomadSexStratifiedObservation.JOINT_VERSION)
                    .put("jointSexHeaderSha256", GnomadSexStratifiedObservation.JOINT_HEADER_SHA256)
                    .put("jointSexFilterSourceSha256", adapter.canonicalSha256(Bs1JointFilterSource.sexContract(mapper)));
        }
        Bs1KnowledgeCatalog.appendCountEligibilityMethodIdentity(result, policies);
        return result;
    }
    private ObjectNode routedIdentity(PopulationRuntimeRouting routing) {
        var adapter=new RuntimeEvidenceV2Adapter(mapper);
        var result=mapper.createObjectNode().put("schemaVersion","bs1-policy-routed-runtime-identity-v1")
                .put("activationContract",PopulationRuntimeRouting.ACTIVATION).put("formalEnabled",formalActivation && executionAllowed)
                .put("contextVersion","evidence-context-v3").put("observationVersion",PopulationObservation.VERSION)
                .put("evaluatorVersion",Bs1CriterionEvaluator.VERSION).put("qualityAdapterVersion",PopulationRuntimeRouting.VERSION)
                .put("policiesSha256",adapter.canonicalSha256(mapper.valueToTree(policies)))
                .put("nativeSourceSha256",adapter.canonicalSha256(Bs1NativeFilterSupplement.contract(mapper)))
                .put("jointFilterSourceSha256",adapter.canonicalSha256(Bs1JointFilterSource.contract(mapper)))
                .put("localExomesAdapterVersion",Bs1LocalExomes.VERSION)
                .put("exomesSexObservationVersion",GnomadSexStratifiedObservation.VERSION)
                .put("exomesSexHeaderSha256",GnomadSexStratifiedObservation.HEADER_SHA256)
                .put("nativeSupplementEnabled",nativeFilters != null);
        result.set("sourceRouting",routing.identity());
        if (knowledgeIdentity != null) result.set("knowledgeIdentity",knowledgeIdentity.deepCopy());
        if (policies.stream().anyMatch(Bs1SexChromosomePolicy::isJoint)) result.put("jointSexStatisticVersion",Bs1SexChromosomeStatistic.JOINT_VERSION)
                .put("jointSexObservationVersion",GnomadSexStratifiedObservation.JOINT_VERSION)
                .put("jointSexHeaderSha256",GnomadSexStratifiedObservation.JOINT_HEADER_SHA256)
                .put("jointSexFilterSourceSha256",adapter.canonicalSha256(Bs1JointFilterSource.sexContract(mapper)));
        Bs1KnowledgeCatalog.appendCountEligibilityMethodIdentity(result, policies);
        return result;
    }
    public ObjectNode evaluateRouted(JsonNode context, VariantInterpretation candidate, AnalysisContext analysis, String sourceSnapshot) {
        return evaluateRouted(context,candidate,analysis,sourceSnapshot,null);
    }
    public ObjectNode evaluateRouted(JsonNode context, VariantInterpretation candidate, AnalysisContext analysis, String sourceSnapshot,
            @org.springframework.lang.Nullable JsonNode sharedObservation) {
        var routing=populationRouting;
        if (routing == null) return blocked("BS1_POLICY_ROUTING_NOT_CONFIGURED");
        if (formalActivation && !executionAllowed) return blocked("BS1_BUSINESS_EXECUTION_DISABLED");
        ObjectNode selection; QueryView query;
        try {
            new RuntimeEvidenceV2Adapter(mapper).validateVersionedContext(context);
            query=queryView(context,candidate,analysis);
            selection=Bs1Policy.select(mapper,policies,context);
            var catalog = knowledge;
            if (catalog != null && "BS1_POLICY_NO_RECORD".equals(selection.path("reasonCode").asText())) {
                var gap=catalog.explain(context.at("/gene/hgncId").asText(),context.at("/disease/mondoId").asText(),context.path("moi").asText());
                selection.put("reasonCode",gap.path("reasonCode").asText()); selection.set("knowledgeGap",gap);
            }
        } catch (IllegalArgumentException invalid) {
            String reason=invalid.getMessage(); return blocked(reason != null && reason.matches("BS1_[A-Z0-9_]+") ? reason : "BS1_CONTEXT_INVALID");
        }
        if (!"READY".equals(selection.path("status").asText())) return selectionBlocked(context,selection);
        var acquisition=routing.observe(selection.path("policy"),context,query.candidate(),query.analysis(),sourceSnapshot,sharedObservation);
        if (!"READY".equals(acquisition.path("status").asText())) {
            var result=blocked(acquisition.path("reasonCode").asText()); result.set("context",context.deepCopy()); result.set("preparation",selection);
            result.set("sourceRouting",acquisition); return result;
        }
        try {
            var result=evaluateRoutedObservation(context,acquisition.path("observation"),sourceSnapshot,selection);
            var sourceRoute=acquisition.deepCopy(); sourceRoute.remove(List.of("observation","observationReusedWithinRun","observationReusedFromBa1"));
            result.set("sourceRouting",sourceRoute);
            result.put("observationReusedWithinRun",acquisition.path("observationReusedWithinRun").asBoolean())
                    .put("observationReusedFromBa1",acquisition.path("observationReusedFromBa1").asBoolean());
            result.set("queryTransport",query.provenance()); return result;
        } catch (IllegalArgumentException invalid) {
            var result=blocked("BS1_OBSERVATION_VERIFICATION_FAILED"); result.set("context",context.deepCopy()); result.set("preparation",selection); return result;
        }
    }
    private ObjectNode selectionBlocked(JsonNode context,ObjectNode selection) {
        var result=blocked(selection.path("reasonCode").asText()); result.set("context",context.deepCopy()); result.set("preparation",selection);
        if ("NOT_APPLICABLE".equals(selection.path("applicability").asText())) result.withObject("/evaluation/criterionApplicability").put("status","NOT_APPLICABLE");
        if (selection.at("/policy/dataScope").isObject()) result.set("dataScope",selection.at("/policy/dataScope").deepCopy()); return result;
    }
    private ObjectNode evaluateRoutedObservation(JsonNode context,JsonNode observation,String sourceSnapshot,ObjectNode selection) {
        var route=PopulationRuntimeRouting.route(mapper,selection.path("policy"));
        if (!"READY".equals(route.path("status").asText())) return blocked(route.path("reasonCode").asText());
        if ("EXOMES".equals(route.path("dataset").asText())) {
            PopulationObservation.verify(mapper,observation,context,sourceSnapshot,GnomadPopulationInputProfile.loadLocalBa1());
            evaluatorCalls++; return Bs1LocalExomes.evaluate(mapper,context,observation,selection);
        }
        return replay(context,observation,sourceSnapshot,selection);
    }
    private record QueryView(VariantInterpretation candidate,AnalysisContext analysis,ObjectNode provenance) { }
    private QueryView queryView(JsonNode context,VariantInterpretation candidate,AnalysisContext analysis) {
        var original=candidate.getObservation().getKey(); var query=context.at("/variant/evidenceQueryAllele");
        if (analysis.getGenomeAssembly() == null || !original.getGenomeAssembly().equals(analysis.getGenomeAssembly().name())
                || !(sameAllele(original,context.at("/variant/inputAllele")) || sameAllele(original,context.at("/variant/normalizedAllele")) || sameAllele(original,query)))
            throw new IllegalArgumentException("BS1_CANDIDATE_ALLELE_CONFLICT");
        boolean mapped="MAPPED".equals(context.at("/variant/assemblyLineage/mappingStatus").asText());
        if (mapped && (!"GRCh37".equals(context.at("/variant/normalizedAllele/assembly").asText()) || !"GRCh38".equals(query.path("assembly").asText())
                || !"VERIFIED_GRCH37_TO_GRCH38_SMALL_VARIANT".equals(context.at("/variant/assemblyLineage/coordinateMapping/method").asText())
                || !"1.0.0".equals(context.at("/variant/assemblyLineage/coordinateMapping/methodVersion").asText())))
            throw new IllegalArgumentException("BS1_QUERY_MAPPING_UNVERIFIED");
        var provenance=mapper.createObjectNode().put("candidateId",candidate.getCandidateId()).put("mode",sameAllele(original,query) ? "UNCHANGED" : "VERIFIED_CONTEXT_QUERY")
                .put("inputAnalysisAssembly",analysis.getGenomeAssembly().name()).put("queryAssembly",query.path("assembly").asText());
        provenance.set("normalization",context.at("/variant/normalization").deepCopy()); provenance.set("assemblyLineage",context.at("/variant/assemblyLineage").deepCopy());
        provenance.set("originalCandidateAllele",mapper.createObjectNode().put("assembly",original.getGenomeAssembly()).put("contig",original.getChromosome())
                .put("position",original.getPosition()).put("reference",original.getReference()).put("alternate",original.getAlternate()));
        if (sameAllele(original,query)) return new QueryView(candidate,analysis,provenance);
        var key=new org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey(query.path("assembly").asText(),query.path("contig").asText(),query.path("position").asInt(),query.path("reference").asText(),query.path("alternate").asText());
        var source=candidate.getObservation();
        var detachedObservation=new org.nbscn.varvis.variantanalysis.domain.VariantQcObservation(key,source.getSampleId(),source.getGenotype(),source.getDepth(),source.getGenotypeQuality(),
                source.getAlleleDepths(),source.getAlleleBalance(),source.getFilterStatus(),source.getVariantType(),source.getOriginalRecord());
        var originalIdentity=candidate.getCandidateIdentity();
        var identity=new org.nbscn.varvis.variantanalysis.domain.CandidateIdentity(org.nbscn.varvis.variantanalysis.domain.CandidateIdentity.SCHEMA_VERSION,candidate.getCandidateId(),key.canonicalId(),
                "NORMALIZATION_QUERY",null,originalIdentity.getGeneSymbol(),originalIdentity.getModeOfInheritance(),null);
        var detached=new VariantInterpretation(identity,detachedObservation,candidate.getConsequences(),List.of()); detached.applyTranscriptAnnotations(candidate.getTranscriptAnnotations());
        var queryAnalysis=new AnalysisContext(analysis.getAnalysisId(),org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.valueOf(query.path("assembly").asText()),analysis.getAnalysisMode(),
                analysis.getPreflightSummary(),analysis.getPhenotypeSummary(),analysis.getBenchmarkTelemetry()); queryAnalysis.setPhenotypeRescueSummary(analysis.getPhenotypeRescueSummary());
        return new QueryView(detached,queryAnalysis,provenance);
    }
    private static boolean sameAllele(org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey key,JsonNode allele) {
        return key.getGenomeAssembly().equals(allele.path("assembly").asText()) && key.getChromosome().equals(allele.path("contig").asText())
                && key.getPosition()==allele.path("position").asInt() && key.getReference().equals(allele.path("reference").asText()) && key.getAlternate().equals(allele.path("alternate").asText());
    }
    public ObjectNode evaluateLocal(JsonNode context, JsonNode sharedBa1, String sourceSnapshot) {
        if (!localExomes || !executionAllowed) return blocked("BS1_BUSINESS_EXECUTION_DISABLED");
        try {
            new RuntimeEvidenceV2Adapter(mapper).validateVersionedContext(context);
            var selection = Bs1Policy.select(mapper, policies, context);
            var catalog = knowledge;
            if (catalog != null && "BS1_POLICY_NO_RECORD".equals(selection.path("reasonCode").asText())) {
                var gap = catalog.explain(context.at("/gene/hgncId").asText(), context.at("/disease/mondoId").asText(), context.path("moi").asText());
                selection.put("reasonCode", gap.path("reasonCode").asText()); selection.set("knowledgeGap", gap);
            }
            if (!"READY".equals(selection.path("status").asText())) {
                var r = blocked(selection.path("reasonCode").asText()); r.set("context", context); r.set("preparation", selection);
                if ("NOT_APPLICABLE".equals(selection.path("applicability").asText())) r.withObject("/evaluation/criterionApplicability").put("status", "NOT_APPLICABLE");
                r.set("dataScope", Bs1LocalExomes.scope(mapper)); return r;
            }
            if (Bs1SexChromosomePolicy.isJoint(selection.path("policy"))) {
                var r = blocked("BS1_SEX_JOINT_REQUIRES_JOINT_OBSERVATION"); r.set("context", context); r.set("preparation", selection);
                r.set("dataScope", selection.at("/policy/dataScope").deepCopy()); return r;
            }
            var observation = Bs1LocalExomes.sharedObservation(mapper, context, sharedBa1, sourceSnapshot);
            var result = Bs1LocalExomes.evaluate(mapper, context, observation, selection); evaluatorCalls++;
            result.put("observationReusedFromBa1", true); return result;
        } catch (IllegalArgumentException invalid) {
            String reason = invalid.getMessage();
            return blocked(reason != null && reason.matches("BS1_[A-Z0-9_]+") ? reason : "BS1_OBSERVATION_VERIFICATION_FAILED");
        }
    }
    public ObjectNode evaluate(JsonNode context, VariantInterpretation candidate, AnalysisContext analysis, String sourceSnapshot) {
        if (populationRouting != null) return evaluateRouted(context,candidate,analysis,sourceSnapshot);
        if (formalActivation && !executionAllowed) return blocked("BS1_BUSINESS_EXECUTION_DISABLED");
        ObjectNode selection;
        try {
            new RuntimeEvidenceV2Adapter(mapper).validateVersionedContext(context);
            var key = candidate.getObservation().getKey();
            JsonNode allele = context.at("/variant/evidenceQueryAllele");
            if (!key.getGenomeAssembly().equals(allele.path("assembly").asText()) || !key.getChromosome().equals(allele.path("contig").asText())
                    || key.getPosition() != allele.path("position").asInt() || !key.getReference().equals(allele.path("reference").asText())
                    || !key.getAlternate().equals(allele.path("alternate").asText())) return blocked("BS1_CANDIDATE_ALLELE_CONFLICT");
            selection = AcmgSpecificationSelector.selectBs1(mapper, policies, context);
        } catch (IllegalArgumentException invalid) { return blocked("BS1_CONTEXT_INVALID"); }
        if (!"READY".equals(selection.path("status").asText())) {
            var result = blocked(selection.path("reasonCode").asText()); result.set("preparation", selection);
            if ("NOT_APPLICABLE".equals(selection.path("applicability").asText())) result.withObject("/evaluation/criterionApplicability").put("status", "NOT_APPLICABLE");
            return result;
        }
        var resource = GnomadPopulationInputProfile.loadExecutableV2().getDocument().path("resources").get(0);
        if (resource == null || !selection.at("/policy/resourceCompatibility/resourceVersion").equals(resource.path("resourceVersion"))
                || !selection.at("/policy/resourceCompatibility/resourceSha256").equals(resource.path("identityFingerprintSha256")))
            return blocked("BS1_RESOURCE_PAIR_MISMATCH");
        String key = sourceSnapshot + "|" + context.path("dataClass").asText() + "|" + context.at("/variant/evidenceQueryAllele");
        if (observationFailures.containsKey(key)) {
            var failed = blocked(observationFailures.get(key)); failed.set("preparation", selection);
            failed.put("observationReusedWithinRun", true); return failed;
        }
        ObjectNode observation = observations.get(key);
        boolean reused = observation != null;
        try {
            if (observation == null) {
                providerCalls++;
                var activeProvider=provider;
                if (activeProvider == null) return blocked("BS1_POPULATION_SOURCE_NOT_CONFIGURED");
                observation = PopulationObservation.acquire(mapper, activeProvider, candidate, analysis,
                        GnomadPopulationInputProfile.loadExecutableV2(), sourceSnapshot, context.path("dataClass").asText());
                observations.put(key, observation);
            }
            ObjectNode result = replay(context, observation, sourceSnapshot, selection);
            result.put("observationReusedWithinRun", reused); return result;
        } catch (IllegalArgumentException invalid) {
            String reason = "BS1_OBSERVATION_VERIFICATION_FAILED";
            if (observation == null) observationFailures.put(key, reason);
            var result = blocked(reason); result.set("preparation", selection);
            String message = invalid.getMessage();
            int separator = message == null ? -1 : message.lastIndexOf(": ");
            String code = message == null ? "" : message.substring(separator < 0 ? 0 : separator + 2);
            if (code.matches("POPULATION_[A-Z0-9_]+")) result.put("observationFailureCode", code);
            result.put("observationFailureStage", "RAW_AND_BINDING_VERIFICATION"); return result;
        }
    }
    /** Offline replay uses the same policy selection, neutral validation, evaluator and output as acquisition. */
    public ObjectNode replay(JsonNode context, JsonNode observation, String sourceSnapshot) {
        if (formalActivation && !executionAllowed) return blocked("BS1_BUSINESS_EXECUTION_DISABLED");
        new RuntimeEvidenceV2Adapter(mapper).validateVersionedContext(context);
        var selection = AcmgSpecificationSelector.selectBs1(mapper, policies, context);
        if (populationRouting != null) {
            if (!"READY".equals(selection.path("status").asText())) return selectionBlocked(context,selection);
            var result=evaluateRoutedObservation(context,observation,sourceSnapshot,selection);
            result.set("sourceRouting",PopulationRuntimeRouting.route(mapper,selection.path("policy"))); return result;
        }
        if (!"READY".equals(selection.path("status").asText())) {
            var r = blocked(selection.path("reasonCode").asText()); r.set("preparation", selection);
            if ("NOT_APPLICABLE".equals(selection.path("applicability").asText())) r.withObject("/evaluation/criterionApplicability").put("status", "NOT_APPLICABLE");
            return r;
        }
        return replay(context, observation, sourceSnapshot, selection);
    }
    private ObjectNode replay(JsonNode context, JsonNode observation, String sourceSnapshot, ObjectNode selection) {
        PopulationObservation.verify(mapper, observation, context, sourceSnapshot);
        JsonNode policy = selection.path("policy");
        if (!policy.at("/resourceCompatibility/resourceVersion").equals(observation.at("/carrier/source/resourceVersion"))
                || !policy.at("/resourceCompatibility/resourceSha256").equals(observation.at("/carrier/source/resourceSha256"))) return blocked("BS1_RESOURCE_PAIR_MISMATCH");
        var needs = Bs1Quality.supplementNeeds(observation, policy);
        String nativeKey=observation.path("observationId").asText();
        if (populationRouting != null) nativeKey += "|" + String.join(",",new TreeSet<>(needs));
        var nativeFacts = nativeObservations.get(nativeKey);
        if (nativeFacts == null) {
            var supplement = nativeFilters;
            nativeFacts = supplement == null || needs.isEmpty() ? mapper.createObjectNode() : supplement.supplement(observation, needs);
            for (var fact : nativeFacts) nativeCalls += fact.path("diagnostics").size();
            nativeObservations.put(nativeKey, nativeFacts);
        }
        evaluatorCalls++;
        if (Bs1SexChromosomePolicy.isJoint(policy)) {
            // Only this BS1 module is extended here; shared multi-criterion entry wiring belongs to integration.
            if (!selection.equals(Bs1Policy.select(mapper, List.of(policy), context)))
                throw new IllegalArgumentException("BS1_SELECTION_INVALID");
            var quality = Bs1SexChromosomeStatistic.select(mapper, observation, context, sourceSnapshot, policy, nativeFacts);
            var evidence = new RuntimeEvidenceV2Adapter(mapper).adaptVersioned(context, (ObjectNode) observation.path("carrier"));
            var result = mapper.createObjectNode().put("schemaVersion", "bs1-rule-result-v1").put("criterionId", Bs1Policy.CRITERION);
            result.set("context", context); result.set("preparation", selection); result.set("observation", observation);
            result.set("qualityDecision", quality); result.set("evidence", evidence); result.set("dataScope", policy.path("dataScope").deepCopy());
            result.set("evaluation", Bs1CriterionEvaluator.evaluate(mapper, context, evidence, policy, selection, quality));
            result.put("overlapPolicy", "BA1_PRECEDENCE_NO_ADDITIVE_POPULATION_STRENGTH").put("classificationEnabled", false);
            return result;
        }
        return new PopulationFrequencyRuntime(mapper).evaluateBs1(context, observation, sourceSnapshot, selection, nativeFacts);
    }
    public ObjectNode counts() { var routing=populationRouting; return mapper.createObjectNode().put("provider", routing == null ? providerCalls : routing.providerCalls()).put("evaluator", evaluatorCalls).put("native", nativeCalls).put("allSites", 0); }
    public ObjectNode blocked(String reason) {
        var result = mapper.createObjectNode().put("schemaVersion", "bs1-rule-result-v1").put("criterionId", Bs1Policy.CRITERION);
        if (localExomes) result.set("dataScope", Bs1LocalExomes.scope(mapper));
        result.putObject("preparation").put("status", "BLOCKED").put("reasonCode", reason);
        var evaluation = result.putObject("evaluation").put("criterionId", Bs1Policy.CRITERION).put("appliedStrength", "NONE");
        evaluation.putObject("criterionApplicability").put("status", "UNKNOWN");
        evaluation.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", reason);
        return result;
    }
}
