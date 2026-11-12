package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** The sole current business rule set. Scientific snapshots remain unchanged. */
public final class AcmgExecutionProfile {
    private final ObjectNode document;
    private AcmgExecutionProfile(ObjectNode document) { this.document = document; }
    /** One explicitly confirmed run; component policy and resource identities remain separate. */
    public static AcmgExecutionProfile unifiedLocal(ObjectMapper mapper, JsonNode binding, JsonNode populationProfile) {
        boolean eleven = extendedLocal(binding), fourteen = publishedLocal(binding);
        if (!binding.path("bp7").isObject() || !eleven && binding.path("pm4Configured").asBoolean()
                || !exactCriteria(mapper.convertValue(populationProfile.path("enabledCriteria"),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {}),
                    List.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1"))
                || !Set.of("LOCAL_EXOMES_BUSINESS_V1", "POLICY_ROUTED_POPULATION_V1")
                        .contains(populationProfile.at("/bs1RuntimeBinding/activationContract").asText()))
            throw new IllegalArgumentException("UNIFIED_LOCAL_EIGHT_COMPONENTS_REQUIRED");
        var value = localBatch(mapper, binding).document();
        value.remove("manifestSha256");
        value.put("profileId", fourteen ? "acmg-unified-local-fourteen-v1" : eleven ? "acmg-unified-local-eleven-v1" : "acmg-unified-local-eight-v1").put("profileVersion", "1.0.0")
                .put("requiredDeclaration", unifiedDeclaration(binding));
        var criteria = value.putArray("enabledCriteria").add("ACMG_AMP_2015:BA1").add("ACMG_AMP_2015:BS1");
        LocalEvidenceBatchRuntime.criteria(true, eleven, eleven, eleven, fourteen).forEach(criteria::add);
        if (binding.path("deNovo").isObject()) {
            DeNovoSelectedBatch.CRITERIA.forEach(criteria::add);
            value.put("profileId", "acmg-unified-local-denovo-v1");
        }
        if(binding.path("pm3").isObject()){criteria.add(Pm3SelectedBatch.CRITERION);value.put("profileId","acmg-unified-local-pm3-v1");}
        if(binding.path("pp1").isObject()){criteria.add(Pp1SelectedBatch.CRITERION);value.put("profileId","acmg-unified-local-pp1-v1");}
        for (String key : List.of("ba1RuntimeBinding", "bs1RuntimeBinding", "ba1InputScope", "ba1EffectiveResultPolicy",
                "ba1DiseaseContextSelection", "ba1DiseaseContextPurpose", "bs1ContextPreparation", "populationDataScope"))
            if (populationProfile.has(key)) value.set(key, populationProfile.path(key).deepCopy());
        value.set("populationExecutionProfile", populationProfile.deepCopy());
        value.put("candidateSelectionPolicy", org.nbscn.varvis.variantanalysis.service.DistinctVariantSelection.POLICY);
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    /** Partial new configurations cannot silently masquerade as an eight- or eleven-rule entry. */
    public static boolean extendedLocal(JsonNode binding) {
        boolean extended = binding.path("pvs1Configured").asBoolean() || binding.path("bp3Configured").asBoolean();
        if (extended && (!binding.path("pvs1Configured").asBoolean() || !binding.path("bp3Configured").asBoolean()
                || !binding.path("pm4Configured").asBoolean() || !binding.path("bp7").isObject()))
            throw new IllegalArgumentException("UNIFIED_LOCAL_ELEVEN_COMPONENTS_REQUIRED");
        return extended;
    }
    public static boolean publishedLocal(JsonNode binding) {
        boolean published = binding.path("pm1Configured").asBoolean() || binding.path("pp2Configured").asBoolean() || binding.path("bp1Configured").asBoolean();
        if (published && (!binding.path("pm1Configured").asBoolean() || !binding.path("pp2Configured").asBoolean()
                || !binding.path("bp1Configured").asBoolean() || !extendedLocal(binding) || !binding.path("pp2GermlineDeclared").isBoolean()))
            throw new IllegalArgumentException("UNIFIED_LOCAL_FOURTEEN_COMPONENTS_REQUIRED");
        return published;
    }
    public static String unifiedDeclaration(JsonNode binding) {
        if (publishedLocal(binding)) return AcmgBusinessRuleScope.UNIFIED_FOURTEEN_DECLARATION;
        return extendedLocal(binding) ? AcmgBusinessRuleScope.UNIFIED_ELEVEN_DECLARATION : AcmgBusinessRuleScope.UNIFIED_LOCAL_DECLARATION;
    }
    public static AcmgExecutionProfile localBatch(ObjectMapper mapper, JsonNode binding) {
        ObjectNode value = mapper.createObjectNode().put("profileId", "acmg-local-computational-batch1-v1")
                .put("profileVersion", binding.path("pm4Configured").asBoolean() ? "1.2.0" : "1.1.0").put("requiredDeclaration", AcmgBusinessRuleScope.LOCAL_BATCH1_DECLARATION)
                .put("contextPreparation", "SELECTED_LOCAL_BATCH1_V1")
                .put("formalEnabled", false).put("automaticTrigger", false).put("classificationEnabled", false)
                .put("clinicalValidationCompleted", false);
        value.putObject("interpretationContextPolicy")
                .put("policyId", org.nbscn.varvis.variantanalysis.service.LocalInterpretationContextPolicy.ID)
                .put("policyVersion", org.nbscn.varvis.variantanalysis.service.LocalInterpretationContextPolicy.VERSION);
        boolean bp7 = binding.path("bp7").isObject(), pm4 = binding.path("pm4Configured").asBoolean();
        boolean extended = extendedLocal(binding), published = publishedLocal(binding);
        value.set("enabledCriteria", mapper.valueToTree(LocalEvidenceBatchRuntime.criteria(bp7, pm4, extended, extended, published)));
        if (binding.path("deNovo").isObject()) {
            if (!DeNovoSelectedBatch.VERSION.equals(binding.at("/deNovo/version").asText()))
                throw new IllegalArgumentException("DENOVO_RUNTIME_BINDING_INVALID");
            var enabled = (com.fasterxml.jackson.databind.node.ArrayNode) value.path("enabledCriteria");
            DeNovoSelectedBatch.CRITERIA.forEach(enabled::add);
            value.put("profileId", "acmg-local-material-and-computational-v1");
        }
        if (bp7) value.put("profileVersion", pm4 ? "1.3.0-bp7-pm4" : "1.2.0-bp7");
        if(binding.path("pm3").isObject()) {
            if(!Pm3SelectedBatch.VERSION.equals(binding.at("/pm3/version").asText()))throw new IllegalArgumentException("PM3_RUNTIME_BINDING_INVALID");
            ((com.fasterxml.jackson.databind.node.ArrayNode)value.path("enabledCriteria")).add(Pm3SelectedBatch.CRITERION);
        }
        if(binding.path("pp1").isObject()) {
            if(!Pp1SelectedBatch.VERSION.equals(binding.at("/pp1/version").asText()))throw new IllegalArgumentException("PP1_RUNTIME_BINDING_INVALID");
            ((com.fasterxml.jackson.databind.node.ArrayNode)value.path("enabledCriteria")).add(Pp1SelectedBatch.CRITERION);
        }
        if (extended) value.put("profileVersion", "1.4.0-extended");
        if (published) value.put("profileVersion", "1.5.0-published-three")
                .put("pp2GermlineDeclared", binding.path("pp2GermlineDeclared").asBoolean())
                .put("publishedKnowledgeUse", "PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS");
        var modules = value.putObject("moduleBindings");
        if(binding.path("pm3").isObject())modules.set("pm3",binding.path("pm3").deepCopy());
        if(binding.path("pp1").isObject())modules.set("pp1",binding.path("pp1").deepCopy());
        if (binding.path("deNovo").isObject()) modules.set("deNovo", binding.path("deNovo").deepCopy());
        for (String module : List.of("pm2", "prediction", "reference")) {
            var item = modules.putObject(module).put("readiness", "CHECKED_AT_SUBMIT")
                    .put("scientificApproval", "NOT_GRANTED_BY_CONFIGURATION");
            item.set("runtimeBinding", binding.deepCopy());
        }
        if (binding.path("bp7").isObject()) modules.putObject("bp7").put("readiness", "CHECKED_AT_SUBMIT")
                .put("scientificApproval", "NOT_GRANTED_BY_CONFIGURATION").set("runtimeBinding", binding.path("bp7").deepCopy());
        if (extended) for (String name : List.of("pvs1", "bp3")) modules.putObject(name).put("readiness", "CHECKED_AT_SUBMIT")
                .put("scientificApproval", "NOT_GRANTED_BY_CONFIGURATION").set("runtimeBinding", binding.deepCopy());
        if (published) for (String name : List.of("pm1", "pp2", "bp1")) modules.putObject(name).put("readiness", "CHECKED_AT_SUBMIT")
                .put("scientificApproval", "NOT_GRANTED_BY_CONFIGURATION").set("runtimeBinding", binding.deepCopy());
        if (binding.path("pm4Configured").asBoolean()) modules.putObject("pm4").put("readiness", "CHECKED_AT_SUBMIT")
                .put("scientificApproval", "NOT_GRANTED_BY_CONFIGURATION").set("runtimeBinding", binding.deepCopy());
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile ps2Development(ObjectMapper mapper, JsonNode configuredPins) {
        ObjectNode value = mapper.createObjectNode().put("profileId", "acmg-ps2-denovo-development-v1")
                .put("profileVersion", "1.0.0").put("requiredDeclaration", AcmgBusinessRuleScope.PS2_DECLARATION)
                .put("formalEnabled", false).put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
        value.putArray("enabledCriteria").add(Ps2DeNovoCriterionEvaluator.CRITERION);
        value.set("ps2MaterialPins", configuredPins.deepCopy());
        value.putObject("contextPreparation").put("schemaVersion", "ps2-denovo-context-preparation-v1").put("version", "1.0.0");
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile pp4Development(ObjectMapper mapper, JsonNode configuredPins) {
        ObjectNode value = mapper.createObjectNode().put("profileId", "acmg-pp4-phenotype-development-v1")
                .put("profileVersion", "1.0.0").put("requiredDeclaration", AcmgBusinessRuleScope.PP4_DECLARATION)
                .put("formalEnabled", false).put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false).put("clinicalValidationCompleted", false);
        value.putArray("enabledCriteria").add(Pp4PhenotypeCriterionEvaluator.CRITERION);
        value.set("pp4MaterialPins", configuredPins.deepCopy());
        value.putObject("contextPreparation").put("schemaVersion", "pp4-phenotype-context-preparation-v1").put("version", "1.0.0");
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile bs4Development(ObjectMapper mapper, JsonNode configuredPins) {
        ObjectNode value = mapper.createObjectNode().put("profileId", "acmg-bs4-nonsegregation-development-v1")
                .put("profileVersion", "1.0.0").put("requiredDeclaration", AcmgBusinessRuleScope.BS4_DECLARATION)
                .put("formalEnabled", false).put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false).put("clinicalValidationCompleted", false);
        value.putArray("enabledCriteria").add(Bs4NonSegregationCriterionEvaluator.CRITERION);
        value.set("bs4MaterialPins", configuredPins.deepCopy());
        value.putObject("contextPreparation").put("schemaVersion", "bs4-nonsegregation-context-preparation-v1").put("version", "1.0.0");
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile pp1Development(ObjectMapper mapper, JsonNode configuredPins) {
        ObjectNode value = mapper.createObjectNode().put("profileId", "acmg-pp1-segregation-development-v1")
                .put("profileVersion", "1.0.0").put("requiredDeclaration", AcmgBusinessRuleScope.PP1_DECLARATION)
                .put("formalEnabled", false).put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false).put("clinicalValidationCompleted", false);
        value.putArray("enabledCriteria").add(Pp1SegregationCriterionEvaluator.CRITERION);
        value.set("pp1MaterialPins", configuredPins.deepCopy());
        value.putObject("contextPreparation").put("schemaVersion", "pp1-segregation-context-preparation-v1").put("version", "1.0.0");
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile bp2Development(ObjectMapper mapper, JsonNode configuredPins) {
        ObjectNode value = mapper.createObjectNode().put("profileId", "acmg-bp2-phase-development-v1")
                .put("profileVersion", "1.0.0").put("requiredDeclaration", AcmgBusinessRuleScope.BP2_DECLARATION)
                .put("formalEnabled", false).put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
        value.putArray("enabledCriteria").add(Bp2PhaseCriterionEvaluator.CRITERION);
        value.set("bp2MaterialPins", configuredPins.deepCopy());
        value.putObject("contextPreparation").put("schemaVersion", "bp2-phase-context-preparation-v1").put("version", "1.0.0");
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile pm3Development(ObjectMapper mapper, JsonNode configuredPins) {
        ObjectNode value = mapper.createObjectNode().put("profileId", "acmg-pm3-in-trans-development-v1")
                .put("profileVersion", "1.0.0").put("requiredDeclaration", AcmgBusinessRuleScope.PM3_DECLARATION)
                .put("formalEnabled", false).put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
        value.putArray("enabledCriteria").add(Pm3InTransCriterionEvaluator.CRITERION);
        value.set("pm3MaterialPins", configuredPins.deepCopy());
        value.putObject("contextPreparation").put("schemaVersion", "pm3-in-trans-context-preparation-v1").put("version", "1.0.0");
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile pm6Development(ObjectMapper mapper, JsonNode configuredPins) {
        ObjectNode value = mapper.createObjectNode().put("profileId", "acmg-pm6-denovo-development-v1")
                .put("profileVersion", "1.0.0").put("requiredDeclaration", AcmgBusinessRuleScope.PM6_DECLARATION)
                .put("formalEnabled", false).put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
        value.putArray("enabledCriteria").add(Pm6DeNovoCriterionEvaluator.CRITERION);
        value.set("pm6MaterialPins", configuredPins.deepCopy());
        value.putObject("contextPreparation").put("schemaVersion", "pm6-denovo-context-preparation-v1").put("version", "1.0.0");
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile bp1Development(ObjectMapper mapper, ReviewedRuleBundle bundle) {
        if (!bundle.supportsBp1()) throw new IllegalArgumentException("BP1_TRUSTED_BUNDLE_REQUIRED");
        ObjectNode value = mapper.createObjectNode().put("profileId", "acmg-bp1-missense-development-v1")
                .put("profileVersion", "1.0.0").put("requiredDeclaration", AcmgBusinessRuleScope.BP1_DECLARATION)
                .put("formalEnabled", false).put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
        value.putArray("enabledCriteria").add(Bp1CriterionEvaluator.CRITERION);
        value.set("reviewedRuleBinding", bundle.identity());
        value.set("ruleContextPreparationBinding", new ReviewedRuleContextPreparation(mapper, bundle.identity()).identity());
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile pp2Development(ObjectMapper mapper, ReviewedRuleBundle bundle) {
        if (!bundle.supportsPp2()) throw new IllegalArgumentException("PP2_TRUSTED_BUNDLE_REQUIRED");
        ObjectNode value = mapper.createObjectNode().put("profileId", "acmg-pp2-missense-development-v1")
                .put("profileVersion", "1.0.0").put("requiredDeclaration", AcmgBusinessRuleScope.PP2_DECLARATION)
                .put("formalEnabled", false).put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
        value.putArray("enabledCriteria").add(Pp2CriterionEvaluator.CRITERION);
        value.set("reviewedRuleBinding", bundle.identity());
        value.set("ruleContextPreparationBinding", new ReviewedRuleContextPreparation(mapper, bundle.identity()).identity());
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile bp3Development(ObjectMapper mapper, ReviewedRuleBundle bundle) {
        if (!bundle.supportsBp3()) throw new IllegalArgumentException("BP3_TRUSTED_BUNDLE_REQUIRED");
        ObjectNode value = mapper.createObjectNode().put("profileId", "acmg-bp3-inframe-development-v1")
                .put("profileVersion", "1.0.0").put("requiredDeclaration", AcmgBusinessRuleScope.BP3_DECLARATION)
                .put("formalEnabled", false).put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
        value.putArray("enabledCriteria").add(Bp3CriterionEvaluator.CRITERION);
        value.set("reviewedRuleBinding", bundle.identity());
        value.set("ruleContextPreparationBinding", new ReviewedRuleContextPreparation(mapper, bundle.identity()).identity());
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile pvs1Development(ObjectMapper mapper, ReviewedRuleBundle bundle) {
        if (!bundle.supportsPvs1Only()) throw new IllegalArgumentException("PVS1_TRUSTED_BUNDLE_REQUIRED");
        ObjectNode value = mapper.createObjectNode().put("profileId", "acmg-pvs1-nmd-development-v1")
                .put("profileVersion", "1.0.0").put("requiredDeclaration", AcmgBusinessRuleScope.PVS1_DECLARATION)
                .put("formalEnabled", false).put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
        value.putArray("enabledCriteria").add(TruncatingNmdPvs1Runtime.CRITERION);
        value.set("reviewedRuleBinding", bundle.identity());
        value.set("ruleContextPreparationBinding", new ReviewedRuleContextPreparation(mapper, bundle.identity()).identity());
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile pm1Development(ObjectMapper mapper, ReviewedRuleBundle bundle) {
        if (!bundle.supportsPm1()) throw new IllegalArgumentException("PM1_TRUSTED_BUNDLE_REQUIRED");
        ObjectNode value = mapper.createObjectNode().put("profileId", "acmg-pm1-region-development-v1")
                .put("profileVersion", "1.0.0").put("requiredDeclaration", AcmgBusinessRuleScope.PM1_DECLARATION)
                .put("formalEnabled", false).put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
        value.putArray("enabledCriteria").add("ACMG_AMP_2015:PM1");
        value.set("reviewedRuleBinding", bundle.identity());
        value.set("ruleContextPreparationBinding", new ReviewedRuleContextPreparation(mapper, bundle.identity()).identity());
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile ps1Development(ObjectMapper mapper, ReviewedRuleBundle bundle) {
        if (!bundle.supportsPs1()) throw new IllegalArgumentException("PS1_TRUSTED_BUNDLE_REQUIRED");
        return missenseDevelopment(mapper, bundle, false);
    }
    public static AcmgExecutionProfile pm5Development(ObjectMapper mapper, ReviewedRuleBundle bundle) {
        if (!bundle.supportsPm5()) throw new IllegalArgumentException("PM5_TRUSTED_BUNDLE_REQUIRED");
        return missenseDevelopment(mapper, bundle, true);
    }
    private static AcmgExecutionProfile missenseDevelopment(ObjectMapper mapper, ReviewedRuleBundle bundle, boolean pm5) {
        ObjectNode value = mapper.createObjectNode().put("profileId", pm5 ? "acmg-pm5-missense-development-v1" : "acmg-ps1-missense-development-v1")
                .put("profileVersion", "1.0.0").put("requiredDeclaration", pm5 ? AcmgBusinessRuleScope.PM5_DECLARATION : AcmgBusinessRuleScope.PS1_DECLARATION)
                .put("formalEnabled", false).put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
        value.putArray("enabledCriteria").add(bundle.missenseCriterion());
        value.set("reviewedRuleBinding", bundle.identity());
        value.set("ruleContextPreparationBinding", new ReviewedRuleContextPreparation(mapper, bundle.identity()).identity());
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile pm4Development(ObjectMapper mapper, ReviewedRuleBundle bundle) {
        if (!bundle.supportsPm4()) throw new IllegalArgumentException("PM4_TRUSTED_BUNDLE_REQUIRED");
        ObjectNode value = mapper.createObjectNode().put("profileId", "acmg-pm4-inframe-development-v1")
                .put("profileVersion", "1.0.0").put("requiredDeclaration", Pm4CriterionEvaluator.DECLARATION)
                .put("formalEnabled", false).put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
        value.putArray("enabledCriteria").add(Pm4CriterionEvaluator.CRITERION);
        value.set("reviewedRuleBinding", bundle.identity());
        value.set("ruleContextPreparationBinding", new ReviewedRuleContextPreparation(mapper, bundle.identity()).identity());
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile revelApplication(ObjectMapper mapper, JsonNode binding) {
        ObjectNode value = mapper.createObjectNode().put("profileId", RevelApplicationBusiness.PROFILE).put("profileVersion", "1.1.0")
                .put("requiredDeclaration", RevelApplicationBusiness.DECLARATION).put("automaticTrigger", false)
                .put("classificationEnabled", false).put("formalEnabled", false).put("adoptionSupported", true)
                .put("contextPreparation", "REVEL_NATIVE_CONTEXT_V1");
        value.set("enabledCriteria", mapper.valueToTree(RevelApplicationBusiness.CRITERIA)); value.set("revelPreparationBinding", binding.deepCopy());
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value)); return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile predictionDevelopment(ObjectMapper mapper, JsonNode binding) {
        var value = mapper.createObjectNode().put("profileId", "acmg-pp3-bp4-development-v1").put("profileVersion", "1.0.0")
                .put("contextPreparation", "PREDICTION_CONTEXT_ONLY_V1").put("requiredDeclaration", PredictionDevelopmentRuntime.DECLARATION)
                .put("formalEnabled", false).put("automaticTrigger", false).put("classificationEnabled", false).put("adoptionSupported", false);
        value.set("enabledCriteria", mapper.valueToTree(PredictionDevelopmentRuntime.CRITERIA)); value.set("predictionBinding", binding.deepCopy());
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value)); return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile savedPredictionCalibration(ObjectMapper mapper, JsonNode binding) {
        var value = predictionDevelopment(mapper, binding).document(); value.remove("manifestSha256");
        value.put("profileVersion", "3.0.0").put("requiredDeclaration", org.nbscn.varvis.variantanalysis.service.SavedCalibrationBusiness.DECLARATION)
                .put("operation", org.nbscn.varvis.variantanalysis.service.SavedCalibrationBusiness.OPERATION)
                .put("newScientificAcquisitionAllowed", false).put("contextPreparation", "FIXED_SAVED_CALIBRATION_V1");
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value)); return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile savedPredictionAssociation(ObjectMapper mapper, JsonNode binding) {
        var value = predictionDevelopment(mapper, binding).document(); value.remove("manifestSha256");
        value.put("profileVersion", "2.0.0").put("requiredDeclaration", SavedPredictionAssociation.DECLARATION)
                .put("operation", SavedPredictionAssociation.OPERATION).put("newScientificAcquisitionAllowed", false)
                .put("contextPreparation", "INDEPENDENT_SAVED_VEP_SELECTION_V1");
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile bs1Acceptance(ObjectMapper mapper, JsonNode ba1Binding, JsonNode bs1Binding) {
        var value = legacyTwoRule(mapper, ba1Binding).document(); value.remove("manifestSha256");
        value.put("profileId", "acmg-ba1-pm2-bs1-acceptance-v1").put("profileVersion", "1.0.0")
                .put("formalEnabled", false).put("requiredDeclaration", AcmgBusinessRuleScope.BS1_DECLARATION)
                .put("bs1ContextPreparation", "BS1_CONTEXT_V3_POLICY_REQUIREMENTS")
                .put("transportOwnershipVersion", OwnedQueryExecution.VERSION);
        value.set("enabledCriteria", mapper.valueToTree(AcmgBusinessRuleScope.BS1_ACCEPTANCE));
        value.withObject("/convertedInputSupport").set("criteria", mapper.valueToTree(AcmgBusinessRuleScope.BS1_ACCEPTANCE));
        value.set("bs1RuntimeBinding", bs1Binding.deepCopy());
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    /** Current identity is bound to the exact locally released policy set, never the development catalog. */
    public static AcmgExecutionProfile current(ObjectMapper mapper, JsonNode ba1Binding, JsonNode bs1Binding) {
        if (!java.util.Set.of("LOCAL_BUSINESS_RELEASE_V1", "LOCAL_EXOMES_BUSINESS_V1", "POLICY_ROUTED_POPULATION_V1").contains(bs1Binding.path("activationContract").asText()))
            throw new IllegalArgumentException("BS1_FORMAL_ACTIVATION_REQUIRED");
        var value = bs1Acceptance(mapper, ba1Binding, bs1Binding).document(); value.remove("manifestSha256");
        value.put("profileId", "acmg-explicit-business-ba1-pm2-bs1-v1").put("profileVersion", "4.0.0")
                .put("formalEnabled", bs1Binding.path("formalEnabled").asBoolean()).put("adoptionSupported", false);
        value.set("enabledCriteria", mapper.valueToTree(AcmgBusinessRuleScope.CURRENT));
        value.withObject("/convertedInputSupport").set("criteria", mapper.valueToTree(AcmgBusinessRuleScope.CURRENT));
        if (Set.of("LOCAL_EXOMES_BUSINESS_V1", "POLICY_ROUTED_POPULATION_V1").contains(bs1Binding.path("activationContract").asText())) {
            value.put("profileVersion", "POLICY_ROUTED_POPULATION_V1".equals(bs1Binding.path("activationContract").asText()) ? "4.2.0" : "4.1.0");
            value.putArray("enabledCriteria").add("ACMG_AMP_2015:BA1").add("ACMG_AMP_2015:BS1");
            if ("POLICY_ROUTED_POPULATION_V1".equals(bs1Binding.path("activationContract").asText())) {
                if (!bs1Binding.path("sourceRouting").isObject()) throw new IllegalArgumentException("BS1_ROUTED_SOURCE_BINDING_REQUIRED");
                value.putObject("populationDataScope").put("policyId", "POLICY_ROUTED_POPULATION_V1").put("dataset", "POLICY_SELECTED")
                        .set("sourceRouting", bs1Binding.path("sourceRouting").deepCopy());
            } else value.set("populationDataScope", bs1Binding.path("dataScope").deepCopy());
            value.withObject("/convertedInputSupport").set("criteria", value.path("enabledCriteria").deepCopy());
        }
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public static AcmgExecutionProfile bs1Development(ObjectMapper mapper, JsonNode runtimeIdentity) {
        var value = mapper.createObjectNode().put("profileId", "acmg-bs1-isolated-development-v1").put("profileVersion", "1.0.0")
                .put("automaticTrigger", false).put("classificationEnabled", false).put("formalEnabled", false)
                .put("contextPreparation", "BS1_CONTEXT_V3_POLICY_REQUIREMENTS");
        value.putArray("enabledCriteria").add("ACMG_AMP_2015:BS1"); value.set("bs1RuntimeBinding", runtimeIdentity);
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }

    /** Preserved two-rule identity for historical and explicit legacy assembly. */
    public static AcmgExecutionProfile legacyTwoRule(ObjectMapper mapper, JsonNode ba1RuntimeBinding) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var value = mapper.createObjectNode().put("profileId", "acmg-explicit-business-ba1-pm2-v1")
                .put("profileVersion", "3.0.1").put("pm2ContextPreparationVersion", "pm2-context-preparation-v2")
                .put("automaticTrigger", false).put("classificationEnabled", false)
                .put("contextPreparation", "POPULATION_CONTEXT_ONLY_V1")
                .put("ba1EffectiveResultPolicy", "CATALOG_RESULT_ONLY_LEGACY_SOURCE_ONLY")
                .put("ba1InputScope", "NATIVE_GRCH38_OR_VERIFIED_GRCH37_TO_GRCH38_SMALL_VARIANT")
                .put("ba1DiseaseContextSelection", "BA1_DISEASE_CONTEXT_CONFIRMED@1.0.0")
                .put("ba1DiseaseContextPurpose", "BA1_ONLY_OTHER_RULES_AND_TRANSCRIPT_UNCONFIRMED")
                .put("pm2InputScope", "COMPLETE_DISEASE_CONTEXT_NATIVE_GRCH38_OR_VERIFIED_GRCH37_TO_GRCH38_SMALL_VARIANT")
                .put("pm2PreparationVersion", "POLICY_BEFORE_QUERY_MULTI_CONTEXT_V1");
        value.set("enabledCriteria", mapper.valueToTree(AcmgBusinessRuleScope.LEGACY_TWO_RULE));
        value.set("ba1RuntimeBinding", ba1RuntimeBinding == null ? mapper.nullNode() : ba1RuntimeBinding.deepCopy());
        var support = value.putObject("convertedInputSupport").put("schemaVersion", "criterion-converted-input-support-v1")
                .put("defaultSupported", false)
                .put("sourceVerificationVersion", org.nbscn.varvis.variantanalysis.engine.ConversionSourceProof.VERSION);
        support.set("criteria", mapper.valueToTree(AcmgBusinessRuleScope.LEGACY_TWO_RULE));
        var slices = value.putArray("slices");
        // Source profiles and their hashes remain intact; only the executable projection is narrowed.
        for (var profile : List.of(GnomadPopulationInputProfile.loadExecutableV2(), GnomadPopulationInputProfile.loadBusinessPm2Supporting())) {
            var source = profile.getDocument();
            var slice = slices.addObject().put("profileId", source.path("profileId").asText())
                    .put("profileVersion", source.path("profileVersion").asText())
                    .put("profileCanonicalSha256", adapter.canonicalSha256(source));
            slice.set("registryBinding", source.path("registryBinding").deepCopy());
            slice.set("resources", source.path("resources").deepCopy());
            var ids = slice.putArray("criterionIds");
            String criterion = slices.size() == 1 ? "ACMG_AMP_2015:BA1" : "ACMG_AMP_2015:PM2";
            if (AcmgBusinessRuleScope.LEGACY_TWO_RULE.contains(criterion)) ids.add(criterion);
        }
        value.put("manifestSha256", adapter.canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public AcmgExecutionProfile(ObjectMapper mapper) {
        this(mapper, null);
    }
    public AcmgExecutionProfile(ObjectMapper mapper, JsonNode runtimeBinding) {
        try (var input = getClass().getResourceAsStream("/acmg/acmg-business-execution-profile-joint-v8.json")) {
            if (input == null) throw new IllegalArgumentException("ACMG_EXECUTION_PROFILE_MISSING");
            var parsed = mapper.readTree(input);
            if (!(parsed instanceof ObjectNode manifest)) throw new IllegalArgumentException("ACMG_EXECUTION_PROFILE_NOT_OBJECT");
            document = manifest;
            AcmgHistoricalExecutionProfiles.validatePredecessors(mapper, document.path("historicalPredecessors"));
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            var population = GnomadPopulationInputProfile.loadExecutableV2();
            var am = AlphaMissenseCriterionEvaluator.loadProfile(mapper);
            var profiles = List.of(am, population.getDocument(), SpliceAiCriterionEvaluator.loadProfile(mapper), SpliceAiCriterionEvaluator.loadBp7Profile(mapper), GnomadPopulationInputProfile.loadBusinessPm2Supporting().getDocument(), Hnf1aPvs1Runtime.loadProfile(mapper), ClinVarMissenseBusinessRuntime.loadProfile(mapper), Hnf1aRevelRuntime.loadProfile(mapper), Hnf1aPs1Pm5Runtime.loadProfile(mapper), GnomadPopulationInputProfile.loadHnf1aFrequency().getDocument(), Hnf1aFunctionalRuntime.loadProfile(mapper), Hnf1aPm1Runtime.loadProfile(mapper));
            if (!"acmg-explicit-business-joint-v8".equals(document.path("profileId").asText())
                    || !document.path("slices").isArray() || document.path("slices").size() != profiles.size()
                    || document.path("automaticTrigger").asBoolean(true) || document.path("classificationEnabled").asBoolean(true))
                throw new IllegalArgumentException();
            for (int i = 0; i < profiles.size(); i++) {
                var pinned = profiles.get(i); var slice = document.path("slices").get(i);
                if (!slice.path("profileId").equals(pinned.path("profileId"))
                        || !slice.path("profileVersion").equals(pinned.path("profileVersion"))
                        || !slice.path("profileCanonicalSha256").asText().equals(adapter.canonicalSha256(pinned))
                        || !slice.path("registryBinding").equals(pinned.path("registryBinding"))) throw new IllegalArgumentException();
                for (JsonNode resource : slice.path("resources")) {
                    var found = pinned.path("resources").findValues("resourceId").stream().anyMatch(resource.path("resourceId")::equals);
                    if (!found) throw new IllegalArgumentException();
                    boolean exact = false;
                    for (var r : pinned.path("resources")) if (resource.path("resourceId").equals(r.path("resourceId")))
                        exact = resource.path("resourceVersion").equals(r.path("resourceVersion"))
                                && resource.path("identityFingerprintSha256").equals(r.path("identityFingerprintSha256"));
                    if (!exact) throw new IllegalArgumentException();
                }
            }
            var ids = new ArrayList<String>(); document.path("enabledCriteria").forEach(n -> ids.add(n.asText()));
            var slices = new ArrayList<String>(); document.path("slices").forEach(s -> s.path("criterionIds").forEach(n -> slices.add(n.asText())));
            if (!exactCriteria(ids, new LinkedHashSet<>(slices))) throw new IllegalArgumentException();
            var spliceBinding = document.path("spliceBusinessBinding");
            var pairing = CSpecExecutionPreflight.splice(mapper).registryPairing();
            if (pairing == null || !spliceBinding.path("ruleRegistryId").equals(pairing.path("ruleRegistryId"))
                    || !spliceBinding.path("ruleRegistrySha256").equals(pairing.path("ruleRegistrySha256"))
                    || !spliceBinding.path("cspecRegistryId").equals(pairing.path("overlayRegistryId"))
                    || !spliceBinding.path("cspecRegistrySha256").equals(pairing.path("overlayRegistrySha256"))
                    || !spliceBinding.path("calibrationUnchanged").asBoolean()
                    || !spliceBinding.path("patientFlowEnabled").asBoolean()
                    || spliceBinding.path("automaticTrigger").asBoolean(true)
                    || spliceBinding.path("classificationEnabled").asBoolean(true)) throw new IllegalArgumentException();
            document.set("missenseScientificPolicy", ClinVarMissenseBusinessRuntime.loadProfile(mapper));
            document.set("hnf1aMissenseScientificPolicy", Hnf1aPs1Pm5Runtime.loadProfile(mapper));
            if (runtimeBinding != null) document.put("missenseRuntimeBindingSha256", adapter.canonicalSha256(runtimeBinding));
            var position = document.path("synonymousPositionPolicy");
            if (!org.nbscn.varvis.variantanalysis.domain.SynonymousExonPosition.POLICY.equals(position.path("policyId").asText())
                    || !org.nbscn.varvis.variantanalysis.domain.SynonymousExonPosition.PLUGIN_SHA256.equals(position.path("pluginSha256").asText()))
                throw new IllegalArgumentException("SYNONYMOUS_POSITION_POLICY_MISMATCH");
            document.put("manifestSha256", adapter.canonicalSha256(document));
        } catch (Exception invalid) { throw new IllegalArgumentException("ACMG_EXECUTION_PROFILE_INVALID", invalid); }
    }
    /** Opt-in successor: the unchanged joint-v7 identity remains valid for existing configurations and saved runs. */
    public AcmgExecutionProfile(ObjectMapper mapper, JsonNode runtimeBinding, JsonNode populationBinding) {
        this(mapper, runtimeBinding);
        if (populationBinding == null) return;
        if (!"population-frequency-bundle-binding-v1".equals(populationBinding.path("schemaVersion").asText())
                || populationBinding.path("automaticTrigger").asBoolean(true)
                || populationBinding.path("classificationEnabled").asBoolean(true))
            throw new IllegalArgumentException("ACMG_POPULATION_BINDING_INVALID");
        document.set("baseExecutionProfile", document.deepCopy());
        document.put("profileId", "acmg-explicit-business-generalization-v1").put("profileVersion", "1.0.0");
        document.set("populationFrequencyBinding", populationBinding.deepCopy());
        document.remove("manifestSha256");
        document.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(document));
    }
    public AcmgExecutionProfile(ObjectMapper mapper, JsonNode runtimeBinding, JsonNode populationBinding, JsonNode reviewedRuleBinding) {
        this(mapper, runtimeBinding, populationBinding);
        if (reviewedRuleBinding == null) return;
        if (!"reviewed-rule-bundle-binding-v1".equals(reviewedRuleBinding.path("schemaVersion").asText())
                || reviewedRuleBinding.path("automaticTrigger").asBoolean(true)
                || reviewedRuleBinding.path("classificationEnabled").asBoolean(true)
                || reviewedRuleBinding.path("adoptionSupported").asBoolean(true))
            throw new IllegalArgumentException("ACMG_REVIEWED_RULE_BINDING_INVALID");
        document.set("baseExecutionProfile", document.deepCopy());
        document.put("profileId", "acmg-explicit-business-generalization-v2").put("profileVersion", "1.1.0");
        document.set("reviewedRuleBinding", reviewedRuleBinding.deepCopy());
        document.remove("manifestSha256");
        document.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(document));
    }
    public AcmgExecutionProfile(ObjectMapper mapper, JsonNode runtimeBinding, JsonNode populationBinding,
                                JsonNode reviewedRuleBinding, JsonNode ruleContextPreparationBinding) {
        this(mapper, runtimeBinding, populationBinding, reviewedRuleBinding);
        if (ruleContextPreparationBinding == null) return;
        // Rebuild the server capability from the same bundle. An unrelated or obsolete context
        // capability cannot share a request/cache identity with the configured rule algorithms.
        var expected = new ReviewedRuleContextPreparation(mapper, reviewedRuleBinding).identity();
        if (!expected.equals(ruleContextPreparationBinding))
            throw new IllegalArgumentException("ACMG_RULE_CONTEXT_PREPARATION_BINDING_MISMATCH");
        document.set("baseExecutionProfile", document.deepCopy());
        document.put("profileId", "acmg-explicit-business-generalization-v3").put("profileVersion", "1.2.0");
        document.set("ruleContextPreparationBinding", ruleContextPreparationBinding.deepCopy());
        document.remove("manifestSha256");
        document.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(document));
    }
    public AcmgExecutionProfile(ObjectMapper mapper, JsonNode runtimeBinding, JsonNode populationBinding,
            JsonNode reviewedRuleBinding, JsonNode ruleContextPreparationBinding,
            JsonNode predictionBusinessBinding, JsonNode predictionContextBinding) {
        this(mapper, runtimeBinding, populationBinding, reviewedRuleBinding, ruleContextPreparationBinding);
        if (predictionBusinessBinding == null && predictionContextBinding == null) return;
        if (predictionBusinessBinding == null || predictionContextBinding == null)
            throw new IllegalArgumentException("ACMG_PREDICTION_CONTEXT_BINDING_MISMATCH");
        var expected = new ReviewedPredictionContextPreparation(mapper, predictionBusinessBinding).identity();
        if (!expected.equals(predictionContextBinding)) throw new IllegalArgumentException("ACMG_PREDICTION_CONTEXT_BINDING_MISMATCH");
        document.set("baseExecutionProfile", document.deepCopy());
        document.put("profileId", "acmg-explicit-business-generalization-v4").put("profileVersion", "1.3.0")
                .put("adoptionSupported", false);
        document.set("predictionBusinessBinding", predictionBusinessBinding.deepCopy());
        document.set("predictionContextPreparationBinding", predictionContextBinding.deepCopy());
        document.remove("manifestSha256");
        document.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(document));
    }
    /** BA1 successor; a null binding preserves every pre-existing execution identity. */
    public AcmgExecutionProfile(ObjectMapper mapper, JsonNode runtimeBinding, JsonNode populationBinding,
            JsonNode reviewedRuleBinding, JsonNode ruleContextPreparationBinding,
            JsonNode predictionBusinessBinding, JsonNode predictionContextBinding, JsonNode ba1RuntimeBinding) {
        this(mapper, runtimeBinding, populationBinding, reviewedRuleBinding, ruleContextPreparationBinding,
                predictionBusinessBinding, predictionContextBinding);
        if (ba1RuntimeBinding == null) return;
        boolean historical = "ba1-runtime-identity-v2".equals(ba1RuntimeBinding.path("schemaVersion").asText())
                && "2.1.0-catalog-ba1-native-qc".equals(ba1RuntimeBinding.path("algorithmVersion").asText());
        boolean converted = "ba1-runtime-identity-v3".equals(ba1RuntimeBinding.path("schemaVersion").asText())
                && Ba1SpecificationCatalog.VERSION.equals(ba1RuntimeBinding.path("algorithmVersion").asText())
                && org.nbscn.varvis.variantanalysis.engine.ConversionSourceProof.VERSION.equals(ba1RuntimeBinding.path("conversionSourceVersion").asText());
        if (!(historical || converted)
                || !ba1RuntimeBinding.path("catalog").isObject() || ba1RuntimeBinding.path("catalog").isEmpty()
                || !ba1RuntimeBinding.path("nativeSubtypeFilters").isObject() || ba1RuntimeBinding.path("nativeSubtypeFilters").isEmpty()
                || !ba1RuntimeBinding.path("populationInputProfileSha256").asText().matches("[0-9a-f]{64}")
                || !ba1RuntimeBinding.path("exceptionPolicySha256").asText().matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("ACMG_BA1_RUNTIME_BINDING_INVALID");
        document.set("baseExecutionProfile", document.deepCopy());
        document.put("profileId", historical ? "acmg-explicit-business-ba1-catalog-v1" : "acmg-explicit-business-ba1-catalog-v2")
                .put("profileVersion", historical ? "2.0.0" : "2.1.0");
        document.set("ba1RuntimeBinding", ba1RuntimeBinding.deepCopy());
        if (converted) document.put("ba1InputScope", "NATIVE_GRCH38_OR_VERIFIED_GRCH37_TO_GRCH38_SMALL_VARIANT")
                .put("otherCriteriaConvertedInputSupport", false);
        document.put("ba1EffectiveResultPolicy", "CATALOG_RESULT_ONLY_LEGACY_SOURCE_ONLY");
        document.put("ba1DiseaseContextSelection", "BA1_DISEASE_CONTEXT_CONFIRMED@1.0.0")
                .put("ba1DiseaseContextPurpose", "BA1_ONLY_OTHER_RULES_AND_TRANSCRIPT_UNCONFIRMED");
        document.remove("manifestSha256");
        document.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(document));
    }
    public AcmgExecutionProfile withPm2VerifiedConversion(ObjectMapper mapper) {
        document.set("baseExecutionProfile", document.deepCopy());
        document.put("profileId", "acmg-explicit-business-pm2-conversion-v1").put("profileVersion", "2.2.0");
        document.remove("otherCriteriaConvertedInputSupport");
        var support = document.putObject("convertedInputSupport").put("schemaVersion", "criterion-converted-input-support-v1")
                .put("defaultSupported", false)
                .put("sourceVerificationVersion", org.nbscn.varvis.variantanalysis.engine.ConversionSourceProof.VERSION);
        var criteria = support.putArray("criteria");
        if (document.has("ba1InputScope")) criteria.add("ACMG_AMP_2015:BA1");
        criteria.add("ACMG_AMP_2015:PM2");
        document.put("pm2InputScope", "COMPLETE_DISEASE_CONTEXT_NATIVE_GRCH38_OR_VERIFIED_GRCH37_TO_GRCH38_SMALL_VARIANT")
                .put("pm2PreparationVersion", "POLICY_BEFORE_QUERY_MULTI_CONTEXT_V1");
        document.remove("manifestSha256");
        document.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(document));
        return this;
    }
    public AcmgExecutionProfile select(ObjectMapper mapper, List<String> requested) {
        if (requested == null) return this;
        var selected = AcmgCriterionSelection.validate(requested, criteria());
        var value = document();
        var scope = value.putObject("criterionSelection").put("schemaVersion", "acmg-criterion-selection-v1")
                .put("configuredManifestSha256", document.path("manifestSha256").asText());
        scope.set("configuredCriteria", mapper.valueToTree(criteria()));
        value.set("enabledCriteria", mapper.valueToTree(selected));
        value.remove("manifestSha256");
        value.put("manifestSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value));
        return new AcmgExecutionProfile(value);
    }
    public ObjectNode document() { return document.deepCopy(); }
    public List<String> criteria() { return values(document.path("enabledCriteria")); }
    public List<String> contextCriteria() { return List.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4").stream().filter(criteria()::contains).toList(); }
    public static List<String> contextCriteria(JsonNode transcriptInput) {
        return transcriptInput != null && (ReviewedPredictionContextPreparation.PATH.equals(transcriptInput.path("predictionPath").asText())
                || IntronicSplicePolicy.splicePath(transcriptInput.path("predictionPath").asText()))
                ? List.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4", "ACMG_AMP_2015:BP7")
                : List.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4");
    }
    private static List<String> values(JsonNode list) { var result = new ArrayList<String>(); list.forEach(n -> result.add(n.asText())); return List.copyOf(result); }
    public static boolean exactCriteria(Collection<String> actual, Collection<String> expected) {
        return actual != null && expected != null && new HashSet<>(actual).size() == actual.size()
                && new HashSet<>(expected).size() == expected.size() && new HashSet<>(actual).equals(new HashSet<>(expected));
    }
    public static boolean evaluationsMatch(List<? extends JsonNode> actual, Collection<String> expected) {
        return exactCriteria(actual.stream().map(n -> n.path("criterionId").asText()).toList(), expected);
    }
}
