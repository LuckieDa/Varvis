package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import java.time.Instant;
import java.util.*;

/** Explicit, published Oxford protein observations. No new assay, predictor or clinical classification. */
public final class Hnf1aFunctionalRuntime {
    public static final String CRITERION = "ACMG_AMP_2015:PS3";
    public static final String PROFILE_SHA256 = "aecb00a1f5f0c65cba3484e0d1dc4b62856fa6e7d66f7219eb1915bf4044970a";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final ObjectNode profile;
    private final ObjectNode facts;
    private final RuntimeEvidenceV2ProviderWiring wiring;
    private final RuntimeEvidenceV2ProviderWiring.ResourceBinding vep;

    public Hnf1aFunctionalRuntime(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        profile = loadProfile(mapper);
        facts = load(mapper, profile.at("/factPackage/classpathResource").asText());
        require(adapter.canonicalSha256(facts).equals(profile.at("/factPackage/canonicalSha256").asText()), "FUNCTIONAL_FACT_IDENTITY_INVALID");
        validateObservations(facts);
        require(profile.at("/registryBinding/registrySha256").asText().equals(ClinVarMissenseBusinessArtifacts.resourceSha(profile.at("/registryBinding/registryClasspathResource").asText())), "FUNCTIONAL_REGISTRY_IDENTITY_INVALID");
        for (String binding : List.of("authorityBinding", "overlayBinding"))
            require(profile.path(binding).path("sha256").asText().equals(ClinVarMissenseBusinessArtifacts.resourceSha(profile.path(binding).path("classpathResource").asText())), "FUNCTIONAL_AUTHORITY_IDENTITY_INVALID");
        wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        vep = wiring.bind(Hnf1aPvs1Runtime.loadProfile(mapper), "VEP_116_GRCH38");
    }

    public static ObjectNode loadProfile(ObjectMapper mapper) {
        var profile = load(mapper, "/acmg/hnf1a-functional-business-profile-v1.json");
        require(PROFILE_SHA256.equals(new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(profile)), "FUNCTIONAL_PROFILE_IDENTITY_INVALID");
        return profile;
    }

    public static boolean contextTranscript(TranscriptConsequence tx) {
        return tx != null && "HNF1A".equals(tx.getGeneSymbol()) && Hnf1aPvs1Runtime.TRANSCRIPT.equals(tx.getVersionedTranscriptId())
                && "116.0".equals(tx.getVersion()) && List.of(Objects.toString(tx.getConsequence(), "").split("[&,|]")).contains("missense_variant");
    }

    public static boolean reviewedTranscript(ObjectMapper mapper, VariantInterpretation candidate, TranscriptConsequence tx) {
        var observation = candidate.getObservation();
        if (!contextTranscript(tx) || observation == null || observation.getKey() == null) return false;
        var key = observation.getKey();
        if (!"GRCh38".equals(key.getGenomeAssembly()) || !Set.of("12", "chr12").contains(key.getChromosome())) return false;
        var material = load(mapper, "/acmg/hnf1a-functional-facts-v1.json");
        for (var record : material.path("records")) {
            var allele = record.path("allele");
            if (allele.path("position").asInt() == key.getPosition()
                    && allele.path("reference").asText().equals(key.getReference()) && allele.path("alternate").asText().equals(key.getAlternate())
                    && (Hnf1aPvs1Runtime.TRANSCRIPT+":"+record.path("coding").asText()).equals(tx.getHgvsc())
                    && (material.at("/scope/ensemblProtein").asText()+":"+record.path("protein").asText()).equals(tx.getHgvsp())) return true;
        }
        return false;
    }

    public ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate) {
        var result = mapper.createObjectNode().put("criterionId", CRITERION).put("sourceOfTruth", "ACMG_EVIDENCE_V2")
                .put("classificationEnabled", false).put("providerCalls", 0).put("evaluatorCalls", 0)
                .put("profileSha256", PROFILE_SHA256);
        result.set("policy", profile.path("policy").deepCopy());
        result.set("disabledCriteria", profile.path("disabledCriteria").deepCopy());
        try { adapter.validateCompleteContext(context); }
        catch (RuntimeException invalid) { return disposition(result, "FUNCTIONAL_CONTEXT_IDENTITY_INVALID"); }
        result.set("context", context.deepCopy());
        if (!"HGNC:11621".equals(context.at("/gene/hgncId").asText()) || !"HNF1A".equals(context.at("/gene/symbol").asText())
                || !"MONDO:0015967".equals(context.at("/disease/mondoId").asText()) || !"AUTOSOMAL_DOMINANT".equals(context.path("moi").asText()))
            return disposition(result, "FUNCTIONAL_POLICY_NOT_APPLICABLE");
        String selectionPolicy = context.at("/transcript/selectionPolicy").asText();
        if ("PM2_CONTEXT_ONLY".equals(selectionPolicy)
                || org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.populationOnlySelection(selectionPolicy))
            return disposition(result, "FUNCTIONAL_TRANSCRIPT_CONFIRMATION_REQUIRED");
        if (!Hnf1aPvs1Runtime.TRANSCRIPT.equals(context.at("/transcript/accession").asText()+"."+context.at("/transcript/version").asInt())
                || !"116.0".equals(context.at("/transcript/annotationSourceVersion").asText())) return disposition(result, "FUNCTIONAL_TRANSCRIPT_NOT_SUPPORTED");
        var allele = context.at("/variant/evidenceQueryAllele");
        if (!"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText()) || !"GRCh38".equals(allele.path("assembly").asText())
                || !"SNV".equals(context.at("/variant/variantType").asText())) return disposition(result, "FUNCTIONAL_NATIVE_SNV_REQUIRED");
        JsonNode record = null;
        for (var item : facts.path("records")) if (item.path("allele").equals(allele)) record = item;
        if (record == null) return disposition(result, "FUNCTIONAL_NO_REVIEWED_RECORD");
        var tx = wiring.selectedVepConsequence(context, candidate);
        if (tx == null) return disposition(result, "FUNCTIONAL_SELECTED_TRANSCRIPT_NOT_UNIQUE");
        try { wiring.projectStoredTranscript(context, candidate, tx, vep); }
        catch (RuntimeException invalid) { return disposition(result, "FUNCTIONAL_VEP_SOURCE_IDENTITY_INVALID"); }
        if (!contextTranscript(tx) || !(Hnf1aPvs1Runtime.TRANSCRIPT+":"+record.path("coding").asText()).equals(tx.getHgvsc())
                || !(facts.at("/scope/ensemblProtein").asText()+":"+record.path("protein").asText()).equals(tx.getHgvsp())
                || !facts.at("/scope/ensemblProtein").asText().equals(tx.getProteinIdentity()))
            return disposition(result, "FUNCTIONAL_CODING_PROTEIN_IDENTITY_MISMATCH");
        result.put("storedVepProjections", 1).put("recordId", record.path("recordId").asText());
        result.set("factPackage", facts.deepCopy());
        result.set("factRecord", record.deepCopy());
        var atomic = result.putArray("atomicEvidence");
        for (var observation : record.path("observations")) atomic.add(atomic(context, record, observation));
        result.put("providerCalls", 1); // One bounded local fact lookup, not a wet experiment or external model.
        String issue = "PASS".equals(record.at("/gates/ps3").asText()) ? null : record.at("/gates/ps3").asText();
        if (issue == null) {
            boolean approved = false;
            for (var id : profile.at("/policy/approvedPs3Records")) approved |= id.equals(record.path("recordId"));
            if (!approved) issue = "FUNCTIONAL_RECORD_NOT_APPROVED_FOR_PS3";
            var hela = record.path("observations").get(0);
            if (!"HELA_RA".equals(hela.path("conditionId").asText()) || !thresholdSatisfied(hela, "LTE", 40)) issue = "FUNCTIONAL_RESULT_INSUFFICIENT";
        }
        var selection = ClinVarMissenseBusinessArtifacts.selection(mapper, context, profile.at("/overlayBinding/classpathResource").asText());
        require("SINGLE_SELECTABLE_MATCH".equals(selection.path("outcome").asText()), "FUNCTIONAL_CSPEC_CONTEXT_MISMATCH");
        result.set("policySelection", selection);
        result.set("evaluation", evaluation(context, atomic, selection, issue));
        var requests = result.putArray("reviewRecords");
        for (var evidence : atomic) requestReview((ObjectNode) evidence, "ATOMIC_EVIDENCE", "evidenceId", requests);
        requestReview((ObjectNode) result.path("evaluation"), "CRITERION_EVALUATION", "evaluationId", requests);
        ObjectNode bundleInput = mapper.createObjectNode().set("context", context);
        bundleInput.set("selection", selection);
        bundleInput.set("atomicEvidence", atomic);
        bundleInput.putArray("criterionEvaluations").add(result.path("evaluation"));
        bundleInput.set("reviewRecords", requests);
        result.set("bundle", ClinVarMissenseBusinessArtifacts.bundle(mapper, bundleInput,
                load(mapper, profile.at("/registryBinding/registryClasspathResource").asText()), profile.at("/registryBinding/registryClasspathResource").asText(),
                profile.at("/overlayBinding/classpathResource").asText(), "varvis-hnf1a-functional-business-entry", Instant.now().toString()));
        result.put("evaluatorCalls", 1);
        return result;
    }

    /** Conservative bounds, not interpolation or threshold-rounded means. Also used by labelled synthetic boundary tests. */
    static boolean thresholdSatisfied(JsonNode observation, String operator, int threshold) {
        String representation = observation.path("valueRepresentation").asText();
        if (!Set.of("REVIEWED_PUBLISHED_FIGURE_RANGE", "REPORTED_RANGE", "REPORTED_INEQUALITY", "SYNTHETIC_ENGINEERING_RANGE").contains(representation)
                || !"CLEAR_FOR_RECORDED_BOUND_ONLY".equals(observation.path("technicalReview").asText())
                || !"percent_of_study_WT".equals(observation.path("unit").asText())) return false;
        var lo = observation.path("lower"); var hi = observation.path("upper");
        if (!lo.isNumber() || !hi.isNumber() || !Double.isFinite(lo.asDouble()) || !Double.isFinite(hi.asDouble())
                || lo.asDouble()<0 || lo.asDouble()>hi.asDouble()) return false;
        return switch (operator) {
            case "LTE" -> hi.asDouble() <= threshold;
            case "LT" -> hi.asDouble() < threshold || hi.asDouble() == threshold && observation.has("upperInclusive") && !observation.path("upperInclusive").asBoolean();
            case "GTE" -> lo.asDouble() >= threshold;
            default -> false;
        };
    }

    static void validateObservations(JsonNode material) {
        var ids = new HashSet<String>(); var alleles = new HashSet<JsonNode>();
        for (var record : material.path("records")) {
            require(alleles.add(record.path("allele")), "FUNCTIONAL_DUPLICATE_ALLELE");
            require(record.path("observations").isArray() && record.path("observations").size()==2, "FUNCTIONAL_CONDITIONS_MISSING");
            for (var observation : record.path("observations")) {
                String origin = observation.path("originObservationId").asText();
                require(!origin.isBlank() && ids.add(origin), "FUNCTIONAL_DUPLICATE_ORIGIN_OBSERVATION");
                require(!observation.path("independentCalibrator").asBoolean(true), "FUNCTIONAL_CIRCULAR_CALIBRATION_LABEL");
            }
        }
    }

    private ObjectNode atomic(ObjectNode context, JsonNode record, JsonNode observation) {
        String now = Instant.now().toString();
        String reviewed = facts.path("reviewedAt").asText();
        String factSha = profile.at("/factPackage/canonicalSha256").asText();
        var carrier = mapper.createObjectNode().put("evidenceType", "FUNCTIONAL_STUDY").put("producedAt", now)
                .put("sourcePayloadSha256", factSha).put("correlationGroupId", "correlation-sha256-"+adapter.canonicalSha256(
                        mapper.createObjectNode().put("study", "PMID:32910913").put("assay", "OXFORD_TRANSACTIVATION").put("recordId", record.path("recordId").asText())));
        carrier.set("queryAllele", context.at("/variant/evidenceQueryAllele").deepCopy());
        var source = carrier.putObject("source").put("sourceId", "HNF1A_OXFORD_FACTS").put("resourceVersion", facts.path("version").asText())
                .put("resourceSha256", factSha).put("retrievedAt", reviewed);
        var locator = mapper.createObjectNode().put("locatorType", "FIGURE").put("locatorValue", facts.at("/sourceManifest/1/url").asText()+"; supplement p12 "+observation.path("figure").asText())
                .put("sourceRecordId", "PMID:32910913")
                .put("artifactSha256", facts.at("/sourceManifest/1/sha256").asText()).put("retrievedAt", now);
        source.putArray("sourceRecords").addObject().put("recordId", observation.path("originObservationId").asText()).set("locator", locator);
        source.putArray("sourceLocators").add(locator.deepCopy()).addObject().put("locatorType", "REPOSITORY_ARTIFACT")
                .put("locatorValue", "acmg/hnf1a-functional-facts-v1.json#"+record.path("recordId").asText())
                .put("artifactSha256", factSha).put("retrievedAt", now);
        carrier.putObject("resourceExecution").put("status", "SUCCEEDED").put("attemptedAt", now).put("completedAt", now);
        carrier.putObject("resourceAvailability").put("status", "AVAILABLE");
        carrier.putObject("resourceApplicability").put("status", "APPLICABLE");
        carrier.putObject("queryOutcome").put("status", "HIT").put("recordCount", 1)
                .put("queryFingerprint", adapter.canonicalSha256(mapper.createObjectNode().put("originObservationId", observation.path("originObservationId").asText())));
        carrier.putArray("measurements").addObject().put("metricId", "transactivation.publishedRange")
                .put("rawValue", "["+observation.path("lower").asText()+","+observation.path("upper").asText()+"]")
                .put("unit", "percent_of_study_WT").put("calibrationProfileId", profile.at("/policy/policyId").asText());
        return adapter.adapt(context, carrier);
    }

    private ObjectNode evaluation(ObjectNode context, ArrayNode atomic, ObjectNode selection, String issue) {
        boolean met = issue == null;
        var result = mapper.createObjectNode().put("schemaVersion", "criterion-evaluation-v2").put("canonicalizationProfile", "varvis-json-c14n-v1")
                .put("contextId", context.path("contextId").asText()).put("criterionId", CRITERION).put("baseStrength", "MODERATE").put("appliedStrength", met ? "MODERATE" : "NONE");
        var binding = result.putObject("ruleBinding").put("ruleRegistryId", profile.at("/registryBinding/registryId").asText())
                .put("ruleRegistrySha256", profile.at("/registryBinding/registrySha256").asText()).put("currentGeneralDesignation", "PS3")
                .put("cspecSelectionId", selection.path("selectionId").asText());
        // General rule authority stays separate from the selected gene-specific overlay authority.
        binding.putArray("authoritySourceIds").add("authority-clingen-svi-ps3-bs3-2019").add("authority-clingen-svi-splicing-2023");
        result.putObject("criterionApplicability").put("status", met ? "APPLICABLE" : "UNKNOWN");
        var status = result.putObject("evaluationResult").put("status", met ? "MET" : "NOT_EVALUATED");
        if (!met) { status.put("reasonCode", issue); ((ObjectNode) result.path("criterionApplicability")).put("reasonCode", issue); }
        // Both correlated conditions remain in the bundle and fact review. HeLa is the single
        // criterion input; INS1 is not a second supporting observation or a strength upgrade.
        var ids = result.putArray("evidenceIds"); ids.add(atomic.get(0).path("evidenceId"));
        var trace = result.putObject("deterministicTrace").put("executionStatus", met ? "COMPLETED" : "NOT_RUN")
                .put("evaluatorId", "hnf1a-oxford-functional-ps3").put("evaluatorVersion", "1.0.0");
        trace.set("inputEvidenceIds", ids.deepCopy());
        var parameters = trace.putArray("parameters");
        parameters.addObject().put("name", "factPackageSha256").put("value", profile.at("/factPackage/canonicalSha256").asText());
        parameters.addObject().put("name", "pairedConditionUse").put("value", "REVIEW_BOTH_COUNT_HELA_ONLY_NO_STRENGTH_ADDITION");
        parameters.addObject().put("name", "profileSha256").put("value", PROFILE_SHA256);
        parameters.addObject().put("name", "selectionSha256").put("value", adapter.canonicalSha256(selection));
        trace.putArray("steps").addObject().put("stepCode", met ? "OXFORD_HELA_RANGE_ENTIRELY_LTE40" : issue).put("outcome", met ? "TRUE" : "UNKNOWN").set("inputEvidenceIds", ids.deepCopy());
        if (met) { trace.put("algorithmId", "HNF1A_GN017_3_1_OXFORD_PS3").put("algorithmVersion", "1.0.0"); trace.put("traceSha256", adapter.canonicalSha256(trace)); }
        else trace.put("reasonCode", issue);
        result.putObject("conflict").put("status", "NONE").putArray("conflictIds");
        result.putObject("review").put("status", "REQUIRED").put("decision", "NONE").putArray("reviewRecordIds");
        var identity = result.deepCopy(); identity.remove(List.of("conflict", "review"));
        result.put("evaluationId", "evaluation-sha256-"+adapter.canonicalSha256(identity));
        return result;
    }

    private ObjectNode disposition(ObjectNode result, String issue) {
        result.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", issue);
        result.putObject("criterionApplicability").put("status", "FUNCTIONAL_POLICY_NOT_APPLICABLE".equals(issue) ? "NOT_APPLICABLE" : "UNKNOWN").put("reasonCode", issue);
        return result;
    }
    private void requestReview(ObjectNode subject, String type, String idField, ArrayNode requests) {
        var request = mapper.createObjectNode().put("schemaVersion", "review-record-v2").put("contextId", subject.path("contextId").asText())
                .put("subjectType", type).put("subjectId", subject.path(idField).asText()).put("eventType", "REQUESTED")
                .put("status", "REQUIRED").put("decision", "NONE").put("reasonCode", "FUNCTIONAL_EXPLICIT_ADOPTION_REQUIRED")
                .put("rationaleSummary", "Automatic review request only; no human review or adoption has occurred.").put("createdAt", Instant.now().toString());
        request.putObject("actor").put("actorId", "reviewer-system-functional-request").put("role", "SYSTEM_ADMINISTRATOR");
        request.put("reviewRecordId", "review-sha256-"+adapter.canonicalSha256(request));
        ((ObjectNode) subject.path("review")).putArray("reviewRecordIds").add(request.path("reviewRecordId"));
        requests.add(request);
    }
    private static ObjectNode load(ObjectMapper mapper, String path) {
        try (var input = Hnf1aFunctionalRuntime.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalArgumentException("FUNCTIONAL_RESOURCE_MISSING");
            JsonNode parsed = mapper.readTree(input);
            if (parsed instanceof ObjectNode object) return object;
            throw new IllegalArgumentException("FUNCTIONAL_RESOURCE_INVALID");
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("FUNCTIONAL_RESOURCE_UNAVAILABLE", invalid); }
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
