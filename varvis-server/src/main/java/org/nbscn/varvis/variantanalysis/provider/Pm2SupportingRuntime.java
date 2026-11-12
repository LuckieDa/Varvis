package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import java.util.*;

/** Explicit context-scoped population evaluation. No prediction model, ranking or classification. */
public final class Pm2SupportingRuntime {
    private final ObjectMapper mapper;
    private final GnomadPopulationInputProfile profile;
    private final VariantEvidenceProvider provider;
    private final VariantEvidenceProvider diagnosticProvider;
    public List<Map<String, Object>> queryDiagnostics() {
        return diagnosticProvider instanceof GnomadTabixProvider tabix ? tabix.queryDiagnostics() : List.of();
    }
    private final GnomadAllSitesHailBatchRunner allSitesRunner;
    private final String allSitesConfigurationIssue;
    private final Map<NormalizedVariantKey, ObjectNode> jointEvidence = new LinkedHashMap<>();
    private final Map<NormalizedVariantKey, List<ObjectNode>> allSitesEvidence = new LinkedHashMap<>();
    private final Map<NormalizedVariantKey, JsonNode> preparedAlleles = new LinkedHashMap<>();
    private final Map<NormalizedVariantKey, JsonNode> preparedContexts = new LinkedHashMap<>();
    private final Map<NormalizedVariantKey, String> preparationIssues = new LinkedHashMap<>();
    private final Map<NormalizedVariantKey, ObjectNode> reusedPopulation = new LinkedHashMap<>();
    private int allSitesBatches;
    private final java.util.concurrent.atomic.AtomicInteger providerCalls = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger evaluatorCalls = new java.util.concurrent.atomic.AtomicInteger();
    private final Map<NormalizedVariantKey, String> rawRecords = new java.util.concurrent.ConcurrentHashMap<>();
    public int providerCalls() { return providerCalls.get(); }
    public int evaluatorCalls() { return evaluatorCalls.get(); }
    public int allSitesBatches() { return allSitesBatches; }

    public Pm2SupportingRuntime(ObjectMapper mapper, VariantAnalysisProperties settings) {
        this(mapper, GnomadPopulationInputProfile.loadBusinessPm2Supporting(), settings);
    }
    public Pm2SupportingRuntime(ObjectMapper mapper, VariantAnalysisProperties settings, GnomadAllSitesHailBatchRunner runner) {
        this(mapper, settings, runner, null);
    }
    public Pm2SupportingRuntime(ObjectMapper mapper, VariantAnalysisProperties settings, GnomadAllSitesHailBatchRunner runner, String configurationIssue) {
        this(mapper, GnomadPopulationInputProfile.loadBusinessPm2Supporting(),
                new GnomadTabixProvider(settings, GnomadPopulationInputProfile.loadBusinessPm2Supporting()), runner, configurationIssue);
    }
    private Pm2SupportingRuntime(ObjectMapper mapper, GnomadPopulationInputProfile profile, VariantAnalysisProperties settings) {
        this(mapper, profile, new GnomadTabixProvider(settings, profile));
    }
    public Pm2SupportingRuntime(ObjectMapper mapper, GnomadPopulationInputProfile profile, VariantEvidenceProvider provider) {
        this(mapper, profile, provider, null);
    }
    public Pm2SupportingRuntime(ObjectMapper mapper, GnomadPopulationInputProfile profile, VariantEvidenceProvider provider,
                                GnomadAllSitesHailBatchRunner allSitesRunner) {
        this(mapper, profile, provider, allSitesRunner, null);
    }
    private Pm2SupportingRuntime(ObjectMapper mapper, GnomadPopulationInputProfile profile, VariantEvidenceProvider provider,
                                GnomadAllSitesHailBatchRunner allSitesRunner, String configurationIssue) {
        this.mapper = Objects.requireNonNull(mapper);
        this.profile = Objects.requireNonNull(profile);
        this.allSitesRunner = allSitesRunner;
        this.allSitesConfigurationIssue = allSitesRunner != null ? null
                : configurationIssue == null ? "PM2_ALL_SITES_NOT_CONFIGURED" : configurationIssue;
        Objects.requireNonNull(provider);
        this.diagnosticProvider = provider;
        this.provider = new VariantEvidenceProvider() {
            public String source() { return provider.source(); }
            public String version() { return provider.version(); }
            public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(List<VariantInterpretation> variants, AnalysisContext context) {
                providerCalls.incrementAndGet();
                for (var variant : variants) if (variant != null && variant.getObservation() != null
                        && variant.getObservation().getKey() != null) rawRecords.remove(variant.getObservation().getKey());
                var values = provider.annotate(variants, context);
                if (values != null) values.forEach((key, items) -> {
                    if (key != null && items != null && items.size() == 1 && items.get(0) != null) {
                        var details = items.get(0).getDetails();
                        if (details != null && details.get("rawPopulationRecord") instanceof String raw) rawRecords.put(key, raw);
                    }
                });
                return values;
            }
        };
    }

    /** Caller must first verify the saved run's request, response and private-source integrity.
     * Only successful HIT observations are retained; policy evaluation always runs against the new context.
     */
    public boolean reuseSuccessfulObservation(ObjectNode context, VariantInterpretation candidate, JsonNode saved, String runId) {
        try {
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            adapter.validateCompleteContext(context);
            var oldContext = saved.path("context"); adapter.validateCompleteContext(oldContext);
            var key = candidate.getObservation().getKey();
            var allele = mapper.createObjectNode().put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome())
                    .put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
            var evidence = saved.path("evidence"); var resource = profile.getDocument().path("resources").get(0);
            if (jointEvidence.containsKey(key) || !profile.getProfileSha256().equals(saved.path("profileSha256").asText())
                    || !resource.path("assembly").asText().equals(key.getGenomeAssembly())
                    || !allele.equals(context.at("/variant/evidenceQueryAllele")) || !allele.equals(oldContext.at("/variant/evidenceQueryAllele"))
                    || !context.path("dataClass").equals(oldContext.path("dataClass")) || !oldContext.path("contextId").equals(evidence.path("contextId"))
                    || !context.path("variant").equals(oldContext.path("variant"))
                    || !"SUCCEEDED".equals(evidence.at("/resourceExecution/status").asText())
                    || !"AVAILABLE".equals(evidence.at("/resourceAvailability/status").asText())
                    || !"APPLICABLE".equals(evidence.at("/resourceApplicability/status").asText())
                    || !"HIT".equals(evidence.at("/queryOutcome/status").asText())
                    || !profile.getPopulationResourceId().equals(evidence.at("/source/sourceId").asText())
                    || !resource.path("resourceVersion").equals(evidence.at("/source/resourceVersion"))
                    || !resource.path("identityFingerprintSha256").equals(evidence.at("/source/resourceSha256"))) return false;
            String raw = saved.path("rawPopulationRecord").asText("");
            if (raw.length() > 1024 * 1024 || raw.isBlank() || raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) return false;
            var fields = raw.split("\t", -1);
            if (fields.length < 8 || !fields[0].replaceFirst("^chr", "").equals(key.getChromosome().replaceFirst("^chr", ""))
                    || Integer.parseInt(fields[1]) != key.getPosition() || !fields[3].equals(key.getReference()) || !fields[4].equals(key.getAlternate())) return false;
            var carrier = ((ObjectNode) evidence).deepCopy(); carrier.set("queryAllele", allele);
            carrier.put("producedAt", evidence.at("/provenance/producedAt").asText())
                    .put("sourcePayloadSha256", evidence.at("/provenance/sourcePayloadSha256").asText());
            // Re-adaptation checks carrier fields and every original atomic identity, including raw-payload identity.
            if (!adapter.adapt(oldContext, carrier).equals(evidence)) return false;
            new RuntimeEvidenceV2ProviderWiring(mapper, adapter).reparsePopulationObservation(oldContext, saved,
                    saved.path("executionSourceSha256").asText(), profile, profile);
            jointEvidence.put(key, adapter.adapt(context, carrier)); preparedAlleles.put(key, allele); rawRecords.put(key, raw);
            preparedContexts.put(key, context.deepCopy());
            reusedPopulation.put(key, mapper.createObjectNode().put("sourceRunId", runId)
                    .put("sourceEvidenceId", evidence.path("evidenceId").asText()).put("scope", "ALLELE_OBSERVATION_ONLY"));
            return true;
        } catch (RuntimeException invalid) { return false; }
    }

    /** One request-scoped population batch; disease-specific evaluations are never cached here. */
    public void prepare(Map<NormalizedVariantKey, JsonNode> contexts, List<VariantInterpretation> candidates, AnalysisContext analysis) {
        var grouped = new LinkedHashMap<NormalizedVariantKey, List<JsonNode>>();
        contexts.forEach((key, context) -> grouped.put(key, List.of(context)));
        prepareContexts(grouped, candidates, analysis);
    }

    /** Keep every disease context; only verified resource observations are shared by allele. */
    public void prepareContexts(Map<NormalizedVariantKey, List<JsonNode>> grouped, List<VariantInterpretation> candidates, AnalysisContext analysis) {
        var contexts = new LinkedHashMap<NormalizedVariantKey, JsonNode>();
        var absenceAllowed = new HashSet<NormalizedVariantKey>();
        grouped.forEach((key, alternatives) -> {
            var allele = mapper.createObjectNode().put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome())
                    .put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
            boolean invalidInput = false;
            for (var context : alternatives) {
                try { new RuntimeEvidenceV2Adapter(mapper).validateCompleteContext(context); }
                catch (IllegalArgumentException invalid) { invalidInput = true; continue; }
                if (!allele.equals(context.at("/variant/evidenceQueryAllele"))) { invalidInput = true; continue; }
                var policies = policies(context);
                if (policies.size() != 1) continue;
                contexts.putIfAbsent(key, context);
                if (policies.get(0).path("allowWellCoveredAbsence").asBoolean()) absenceAllowed.add(key);
            }
            if (contexts.containsKey(key)) preparationIssues.remove(key, "PM2_POPULATION_INPUT_INVALID");
            else if (invalidInput) preparationIssues.putIfAbsent(key, "PM2_POPULATION_INPUT_INVALID");
        });
        var unique = new LinkedHashMap<NormalizedVariantKey, VariantInterpretation>();
        for (var candidate : candidates) {
            if (candidate == null || candidate.getObservation() == null) continue;
            var key = candidate.getObservation().getKey();
            if (key == null || !contexts.containsKey(key) || jointEvidence.containsKey(key) || preparationIssues.containsKey(key)) continue;
            var context = contexts.get(key);
            try {
                new RuntimeEvidenceV2Adapter(mapper).validateCompleteContext(context);
                var allele = mapper.createObjectNode().put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome())
                        .put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
                if (!allele.equals(context.at("/variant/evidenceQueryAllele"))) throw new IllegalArgumentException("PM2_PREPARED_ALLELE_CONFLICT");
                if (!"GRCh38".equals(key.getGenomeAssembly()) || analysis == null || analysis.getGenomeAssembly() == null
                        || !key.getGenomeAssembly().equals(analysis.getGenomeAssembly().name()))
                    throw new IllegalArgumentException("PM2_ASSEMBLY_UNSUPPORTED");
                unique.putIfAbsent(key, candidate);
            } catch (IllegalArgumentException invalid) {
                preparationIssues.put(key, "PM2_POPULATION_INPUT_INVALID");
            }
        }
        if (unique.isEmpty()) return;
        var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, new RuntimeEvidenceV2Adapter(mapper));
        var values = wiring.annotateVariantProviderBatch(contexts, provider, List.copyOf(unique.values()), analysis,
                wiring.bind(profile.getDocument(), profile.getPopulationResourceId()));
        var absent = new ArrayList<VariantInterpretation>();
        var absentContexts = new LinkedHashMap<NormalizedVariantKey, JsonNode>();
        values.forEach((key, evidence) -> {
            if (evidence == null || evidence.size() != 1) {
                preparationIssues.put(key, "PM2_ATOMIC_EVIDENCE_AMBIGUOUS"); return;
            }
            jointEvidence.put(key, evidence.get(0));
            preparedAlleles.put(key, contexts.get(key).at("/variant/evidenceQueryAllele").deepCopy());
            preparedContexts.put(key, contexts.get(key).deepCopy());
            if ("NO_RECORD".equals(evidence.get(0).at("/queryOutcome/status").asText())
                    && "SUCCEEDED".equals(evidence.get(0).at("/resourceExecution/status").asText())
                    && "AVAILABLE".equals(evidence.get(0).at("/resourceAvailability/status").asText())
                    && "APPLICABLE".equals(evidence.get(0).at("/resourceApplicability/status").asText())
                    && absenceAllowed.contains(key)
                    && key.getReference().length() == 1 && key.getAlternate().length() == 1) {
                absent.add(unique.get(key)); absentContexts.put(key, contexts.get(key));
            }
        });
        if (!absent.isEmpty() && allSitesRunner != null) {
            allSitesBatches++; providerCalls.incrementAndGet();
            try {
                allSitesEvidence.putAll(wiring.annotateGnomadAllSitesBatch(absentContexts, absent, allSitesRunner, profile));
            } catch (RuntimeException failure) {
                // Joint observations remain usable. Only absence assessments require this resource.
                for (var candidate : absent) preparationIssues.put(candidate.getObservation().getKey(), "PM2_ALL_SITES_QUERY_FAILED");
            }
        }
    }

    private List<JsonNode> policies(JsonNode context) {
        var matches = new ArrayList<JsonNode>();
        for (var policy : profile.getDocument().at("/evaluation/criteria/2/populationRule/policySnapshot/records"))
            if (policy.at("/gene/hgncId").equals(context.at("/gene/hgncId"))
                    && policy.at("/disease/mondoId").equals(context.at("/disease/mondoId"))
                    && policy.path("moi").equals(context.path("moi"))) matches.add(policy);
        return matches;
    }

    private ObjectNode forContext(ObjectNode context, NormalizedVariantKey key, ObjectNode original) {
        if (!context.at("/variant/evidenceQueryAllele").equals(preparedAlleles.get(key)))
            throw new IllegalArgumentException("PM2_PREPARED_ALLELE_CONFLICT");
        var prepared = preparedContexts.get(key);
        if (prepared == null || !context.path("dataClass").equals(prepared.path("dataClass"))
                || !context.path("variant").equals(prepared.path("variant")))
            throw new IllegalArgumentException("PM2_PREPARED_OBSERVATION_IDENTITY_CONFLICT");
        if (context.path("contextId").equals(original.path("contextId"))) return original.deepCopy();
        // Reuse the same allele observation, re-adapt it to the new context with new evidence identity.
        var carrier = original.deepCopy();
        carrier.set("queryAllele", preparedAlleles.get(key));
        carrier.put("producedAt", original.at("/provenance/producedAt").asText());
        carrier.put("sourcePayloadSha256", original.at("/provenance/sourcePayloadSha256").asText());
        return new RuntimeEvidenceV2Adapter(mapper).adapt(context, carrier);
    }

    public ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, AnalysisContext analysis) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        adapter.validateCompleteContext(context);
        int providerStart = providerCalls();
        var key = candidate.getObservation().getKey();
        var document = profile.getDocument();
        JsonNode rule = document.at("/evaluation/criteria/2/populationRule");
        List<JsonNode> matches = policies(context);
        var result = mapper.createObjectNode().put("criterionId", "ACMG_AMP_2015:PM2")
                .put("providerCalls", 0).put("evaluatorCalls", 0);
        result.set("context", context);
        result.put("profileSha256", profile.getProfileSha256());
        result.put("allSitesConfigurationIssue", allSitesConfigurationIssue);
        result.set("policySnapshot", rule.path("policySnapshot"));
        if (matches.size() != 1) {
            String reason = matches.isEmpty() ? "PM2_DISEASE_FREQUENCY_PARAMETERS_REQUIRED" : "PM2_POLICY_AMBIGUOUS";
            var known = mapper.createArrayNode();
            for (var scope : rule.at("/policySnapshot/knownSpecificationScopes"))
                if (scope.path("hgncId").equals(context.at("/gene/hgncId"))) known.add(scope);
            if (matches.isEmpty() && !known.isEmpty()) reason = "PM2_SPECIALIST_POLICY_REVIEW_REQUIRED";
            result.set("knownSpecifications", known);
            result.putObject("evaluationResult").put("status", "NOT_EVALUATED")
                    .put("reasonCode", reason);
            return result;
        }
        JsonNode policy = matches.get(0);
        // This is a PM2-only CSpec policy selection, not a claim that all VHL criteria are implemented.
        var selection = mapper.createObjectNode().put("schemaVersion", "pm2-cspec-policy-selection-v1")
                .put("contextId", context.path("contextId").asText()).put("criterionId", "ACMG_AMP_2015:PM2")
                .put("profileSha256", profile.getProfileSha256()).put("policySnapshotSha256", rule.at("/policySnapshot/sha256").asText())
                .put("selectedPolicyId", policy.path("policyId").asText()).put("outcome", "SINGLE_EXACT_MATCH");
        selection.set("authority", policy.path("authority"));
        selection.put("selectionId", "cspec-selection-sha256-" + adapter.canonicalSha256(selection));
        result.set("policySelection", selection); result.set("policy", policy);
        if (!jointEvidence.containsKey(key)) prepare(Map.of(key, context), List.of(candidate), analysis);
        result.put("providerCalls", providerCalls() - providerStart);
        if (!jointEvidence.containsKey(key)) {
            result.putObject("evaluationResult").put("status", "NOT_EVALUATED")
                    .put("reasonCode", preparationIssues.getOrDefault(key, "PM2_ATOMIC_EVIDENCE_MISSING"));
            return result;
        }
        if (preparationIssues.containsKey(key)) result.put("populationPreparationIssue", preparationIssues.get(key));
        var evidence = forContext(context, key, Objects.requireNonNull(jointEvidence.get(key)));
        result.set("evidence", evidence);
        if (reusedPopulation.containsKey(key)) result.set("populationReuse", reusedPopulation.get(key).deepCopy());
        result.put("rawPopulationRecord", rawRecords.get(key));
        var allSites = allSitesEvidence.getOrDefault(key, List.of()).stream()
                .map(value -> forContext(context, key, value)).toList();
        result.set("allSitesEvidence", mapper.valueToTree(allSites));
        evaluatorCalls.incrementAndGet();
        ObjectNode exomes = null, genomes = null;
        for (var value : allSites) {
            if (GnomadPopulationInputProfile.EXOME_ALL_SITES_RESOURCE_ID.equals(value.at("/source/sourceId").asText())) exomes = value;
            if (GnomadPopulationInputProfile.GENOME_ALL_SITES_RESOURCE_ID.equals(value.at("/source/sourceId").asText())) genomes = value;
        }
        result.set("evaluation", new GnomadPopulationCriterionEvaluator(mapper).evaluatePm2Supporting(
                context, evidence, exomes, genomes, profile, profile.getSuccessorRegistryDocument(), selection.path("selectionId").asText()));
        result.put("evaluatorCalls", 1);
        result.put("sourceOfTruth", "ACMG_EVIDENCE_V2").put("classificationEnabled", false);
        return result;
    }
}
