package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Request-scoped local PM2 adapter. Formal candidates, scientific adoption and transport remain separate. */
public final class Pm2LocalExomesRuntime {
    private final ObjectMapper mapper;
    private final Pm2LocalExomesProfile profile;
    private final VariantEvidenceProvider provider;
    private final GnomadLocalAllSites allSites;
    private String allSitesConfigurationIssue;
    private final Map<String, ObjectNode> observations = new LinkedHashMap<>();
    private int providerCalls;
    private int evaluatorCalls;

    public Pm2LocalExomesRuntime(ObjectMapper mapper, VariantAnalysisProperties settings) {
        this(mapper, Pm2LocalExomesProfile.load(mapper), Ba1LocalExomes.provider(settings));
    }

    public Pm2LocalExomesRuntime(ObjectMapper mapper, VariantAnalysisProperties settings, Pm2LocalExomesProfile reviewedProfile) {
        this(mapper, reviewedProfile, Ba1LocalExomes.provider(settings));
    }

    public Pm2LocalExomesRuntime(ObjectMapper mapper, VariantAnalysisProperties settings, Pm2LocalExomesProfile profile,
            GnomadLocalAllSites allSites, boolean generalAbsence) {
        this(mapper, profile.withLocalAbsence(allSites.binding(), generalAbsence), Ba1LocalExomes.provider(settings), allSites);
    }

    /** Detection resource failure must not suppress independently valid observed-allele evaluation. */
    public static Pm2LocalExomesRuntime configured(ObjectMapper mapper, VariantAnalysisProperties settings,
            Pm2LocalExomesProfile profile, java.nio.file.Path manifest, String pin, boolean generalAbsence) {
        GnomadLocalAllSites resource;
        try { resource = new GnomadLocalAllSites(mapper, manifest, pin); }
        catch (IllegalArgumentException unavailable) {
            var runtime = new Pm2LocalExomesRuntime(mapper, settings, profile);
            runtime.allSitesConfigurationIssue = "PM2_ALL_SITES_RESOURCE_INVALID";
            return runtime;
        }
        return new Pm2LocalExomesRuntime(mapper, settings, profile, resource, generalAbsence);
    }

    Pm2LocalExomesRuntime(ObjectMapper mapper, Pm2LocalExomesProfile profile, VariantEvidenceProvider provider) {
        this(mapper, profile, provider, null);
    }

    Pm2LocalExomesRuntime(ObjectMapper mapper, Pm2LocalExomesProfile profile, VariantEvidenceProvider provider, GnomadLocalAllSites allSites) {
        this.mapper = Objects.requireNonNull(mapper);
        this.profile = Objects.requireNonNull(profile);
        this.provider = Objects.requireNonNull(provider);
        this.allSites = allSites;
    }

    public int providerCalls() { return providerCalls; }
    public int evaluatorCalls() { return evaluatorCalls; }
    public int allSitesCalls() { return allSites == null ? 0 : allSites.queries(); }
    public ObjectNode profile() { return profile.document(); }
    Pm2LocalExomesProfile evaluationProfile() { return profile; }

    /** Caller supplies the already-verified execution/source snapshot identity, not a client assertion. */
    public ObjectNode execute(SelectedEvidencePreparation selected, AnalysisContext analysis, String sourceSnapshot) {
        var result = mapper.createObjectNode().put("schemaVersion", "pm2-local-exomes-batch-v1")
                .put("profileSha256", profile.sha256());
        result.set("preparation", mapper.valueToTree(selected.assembly()));
        result.set("sourceProvenance", selected.provenance());
        result.set("policyInventory", profile.document().path("policyInventory"));
        var rows = result.putArray("results");
        for (var candidate : selected.assembly().candidates()) {
            if (candidate.attempts().isEmpty()) {
                var unavailable = resultEnvelope(candidate.candidateId(), null);
                unavailable.putObject("evaluationResult").put("status", "NOT_EVALUATED")
                        .put("reasonCode", "SELECTED_CONTEXT_UNAVAILABLE");
                rows.add(unavailable);
            } else for (var attempt : candidate.attempts())
                rows.add(evaluate(selected, candidate.candidateId(), attempt.hypothesisId(), analysis, sourceSnapshot, null));
        }
        result.put("providerCalls", providerCalls).put("evaluatorCalls", evaluatorCalls).put("allSitesCalls", allSitesCalls());
        return result;
    }

    /** A shared observation is accepted only after explicit-profile RAW verification; it carries no rule result. */
    public ObjectNode evaluate(SelectedEvidencePreparation selected, String candidateId, String hypothesisId,
                               AnalysisContext analysis, String sourceSnapshot, JsonNode sharedObservation) {
        try {
            if (analysis == null || analysis.getAnalysisId() == null
                    || !analysis.getAnalysisId().equals(selected.assembly().analysisId()))
                throw new IllegalArgumentException("PM2_ANALYSIS_ID_MISMATCH");
            return evaluateBound(candidateId,hypothesisId,selected.context(candidateId,hypothesisId),
                    selected.queryCandidate(candidateId,hypothesisId,false),analysis,sourceSnapshot,sharedObservation);
        } catch (IllegalArgumentException blocked) {
            var result=resultEnvelope(candidateId,hypothesisId);
            result.putObject("evaluationResult").put("status","NOT_EVALUATED").put("reasonCode",blocked.getMessage());
            return result;
        }
    }

    /** Internal source-bound literature allele: no insertion into the patient's ranked candidates.
     * The caller must verify the literature identity and construct its context before invoking.
     */
    public ObjectNode evaluateReference(ObjectNode context,
            org.nbscn.varvis.variantanalysis.domain.VariantInterpretation reference,
            AnalysisContext analysis,String sourceSnapshot,JsonNode sharedObservation) {
        var result=evaluateBound(null,null,context,reference,analysis,sourceSnapshot,sharedObservation);
        result.put("queryRole","LITERATURE_REFERENCE").put("patientCandidate",false);
        return result;
    }

    private ObjectNode evaluateBound(String candidateId,String hypothesisId,ObjectNode context,
            org.nbscn.varvis.variantanalysis.domain.VariantInterpretation candidate,
            AnalysisContext analysis,String sourceSnapshot,JsonNode sharedObservation) {
        var result=resultEnvelope(candidateId,hypothesisId);
        int before=providerCalls,beforeAllSites=allSitesCalls();
        try {
            if(sourceSnapshot==null || !sourceSnapshot.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("POPULATION_SOURCE_IDENTITY_REQUIRED");
            if(context==null || candidate==null || candidate.getObservation()==null)
                throw new IllegalArgumentException("PM2_QUERY_IDENTITY_REQUIRED");
            var key = candidate.getObservation().getKey();
            if (analysis == null || analysis.getGenomeAssembly() == null || !"GRCh38".equals(key.getGenomeAssembly())
                    || !key.getGenomeAssembly().equals(analysis.getGenomeAssembly().name()))
                throw new IllegalArgumentException("PM2_ASSEMBLY_UNSUPPORTED");
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            adapter.validateVersionedContext(context);
            var expected=context.at("/variant/evidenceQueryAllele");
            if(!key.getGenomeAssembly().equals(expected.path("assembly").asText())
                    || !key.getChromosome().equals(expected.path("contig").asText())
                    || key.getPosition()!=expected.path("position").asInt()
                    || !key.getReference().equals(expected.path("reference").asText())
                    || !key.getAlternate().equals(expected.path("alternate").asText()))
                throw new IllegalArgumentException("PM2_QUERY_CONTEXT_ALLELE_MISMATCH");
            result.set("context", context);
            result.set("policySelection", profile.select(context));
            result.set("dataScope", profile.document());
            result.put("executionSourceSha256", sourceSnapshot);
            String queryIdentity = queryIdentity(context, sourceSnapshot);
            ObjectNode observation;
            String reuse;
            if (sharedObservation != null) {
                observation = PopulationObservation.restoreSealed(mapper, sharedObservation, context, sourceSnapshot, profile.factProfile());
                reuse = "VERIFIED_SHARED_OBSERVATION";
            } else if (observations.containsKey(queryIdentity)) {
                observation = PopulationObservation.restoreSealed(mapper, observations.get(queryIdentity), context, sourceSnapshot, profile.factProfile());
                reuse = "SUCCEEDED".equals(observation.at("/carrier/resourceExecution/status").asText())
                        ? "REQUEST_OBSERVATION" : "REQUEST_DIAGNOSTIC";
            } else {
                providerCalls++;
                observation = PopulationObservation.acquire(mapper, provider, candidate, analysis, profile.factProfile(),
                        sourceSnapshot, context.path("dataClass").asText());
                PopulationObservation.verify(mapper, observation, context, sourceSnapshot, profile.factProfile());
                reuse = "NEW_LOCAL_PROVIDER_EXECUTION";
            }
            observations.put(queryIdentity, observation.deepCopy());
            result.put("queryIdentity", queryIdentity).put("observationUse", reuse);
            result.set("observation", observation);
            result.set("evidence", adapter.adaptVersioned(context, (ObjectNode) observation.path("carrier")));
            result.put("rawPopulationRecord", observation.path("rawRecord").asText());
            ObjectNode detection = null;
            if (allSitesConfigurationIssue != null) {
                detection = mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", allSitesConfigurationIssue);
                result.set("allSitesObservation", detection);
            }
            if (allSites != null && "SUCCEEDED".equals(observation.at("/carrier/resourceExecution/status").asText())
                    && "NO_RECORD".equals(observation.at("/carrier/queryOutcome/status").asText())
                    && context.at("/variant/evidenceQueryAllele/reference").asText().length() == 1
                    && context.at("/variant/evidenceQueryAllele/alternate").asText().length() == 1) {
                detection = allSites.query(context.at("/variant/evidenceQueryAllele"));
                result.set("allSitesObservation", detection);
            }
            evaluatorCalls++;
            result.set("evaluation", new GnomadPopulationCriterionEvaluator(mapper)
                    .evaluatePm2LocalExomes(context, observation, profile, sourceSnapshot, detection));
            result.put("evaluatorCalls", 1);
        } catch (IllegalArgumentException blocked) {
            result.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", blocked.getMessage());
        }
        result.put("providerCalls", providerCalls - before);
        result.put("allSitesCalls", allSitesCalls() - beforeAllSites);
        return result;
    }

    private ObjectNode resultEnvelope(String candidateId, String hypothesisId) {
        return mapper.createObjectNode().put("schemaVersion", "pm2-local-exomes-result-v1")
                .put("criterionId", "ACMG_AMP_2015:PM2").put("candidateId", candidateId).put("hypothesisId", hypothesisId)
                .put("profileSha256", profile.sha256()).put("providerCalls", 0).put("evaluatorCalls", 0)
                .put("allSitesCalls", 0).put("classificationEnabled", false);
    }

    private String queryIdentity(JsonNode context, String sourceSnapshot) {
        var identity = mapper.createObjectNode().put("sourceSnapshot", sourceSnapshot)
                .put("factProfileSha256", profile.factProfile().getProfileSha256());
        identity.set("dataClass", context.path("dataClass"));
        identity.set("query", context.at("/variant/evidenceQueryAllele"));
        var normalization = identity.putObject("normalization");
        for (String field : java.util.List.of("method", "methodVersion", "referenceSequenceSha256"))
            normalization.set(field, context.at("/variant/normalization").path(field));
        return new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(identity);
    }
}
