package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

/** Server-owned context capability. It neither selects a criterion policy nor asserts clinical or sequence validity. */
public final class ReviewedRuleContextPreparation {
    public static final String VERSION = "1.0.0";
    public static final String PATH = "REVIEWED_RULE_CONTEXT";
    private final ObjectNode identity;
    private final String selectionPolicy;
    private final boolean strictMissense;

    public ReviewedRuleContextPreparation(ObjectMapper mapper, JsonNode reviewedRuleBinding) {
        Objects.requireNonNull(mapper);
        if (reviewedRuleBinding == null || !reviewedRuleBinding.isObject()
                || !java.util.Set.of("reviewed-rule-bundle-binding-v1", "reviewed-rule-bundle-binding-pm4-v2", "reviewed-rule-bundle-binding-ps1-v3", "reviewed-rule-bundle-binding-pm5-v4", "reviewed-rule-bundle-binding-pm1-v5", "reviewed-rule-bundle-binding-pvs1-v6", "reviewed-rule-bundle-binding-bp3-v7", "reviewed-rule-bundle-binding-pp2-v8", "reviewed-rule-bundle-binding-bp1-v9").contains(reviewedRuleBinding.path("schemaVersion").asText())
                || !"CONFIGURED".equals(reviewedRuleBinding.path("status").asText())
                || reviewedRuleBinding.path("bundleId").asText().isBlank() || reviewedRuleBinding.path("bundleVersion").asText().isBlank()
                || !reviewedRuleBinding.path("bundleSha256").asText().matches("[0-9a-f]{64}")
                || !reviewedRuleBinding.path("catalogSha256").asText().matches("[0-9a-f]{64}")
                || reviewedRuleBinding.path("automaticTrigger").asBoolean(true)
                || reviewedRuleBinding.path("classificationEnabled").asBoolean(true)
                || reviewedRuleBinding.path("adoptionSupported").asBoolean(true)
                || !reviewedRuleBinding.path("algorithms").isObject() || reviewedRuleBinding.path("algorithms").isEmpty())
            throw new IllegalArgumentException("REVIEWED_RULE_CONTEXT_BINDING_UNAVAILABLE");
        boolean pm4 = "reviewed-rule-bundle-binding-pm4-v2".equals(reviewedRuleBinding.path("schemaVersion").asText());
        boolean ps1 = "reviewed-rule-bundle-binding-ps1-v3".equals(reviewedRuleBinding.path("schemaVersion").asText());
        boolean pm5 = "reviewed-rule-bundle-binding-pm5-v4".equals(reviewedRuleBinding.path("schemaVersion").asText());
        boolean pm1 = "reviewed-rule-bundle-binding-pm1-v5".equals(reviewedRuleBinding.path("schemaVersion").asText());
        boolean pvs1 = "reviewed-rule-bundle-binding-pvs1-v6".equals(reviewedRuleBinding.path("schemaVersion").asText());
        boolean bp3 = "reviewed-rule-bundle-binding-bp3-v7".equals(reviewedRuleBinding.path("schemaVersion").asText());
        boolean bp1 = "reviewed-rule-bundle-binding-bp1-v9".equals(reviewedRuleBinding.path("schemaVersion").asText());
        boolean pp2 = "reviewed-rule-bundle-binding-pp2-v8".equals(reviewedRuleBinding.path("schemaVersion").asText());
        strictMissense = bp1 || pp2 || ps1 || pm5 || pm1;
        String version = bp1 ? "9.0.0" : pp2 ? "8.0.0" : bp3 ? "7.0.0" : pvs1 ? "6.0.0" : pm1 ? "5.0.0" : pm5 ? "4.0.0" : ps1 ? "3.0.0" : pm4 ? "2.0.0" : VERSION;
        identity = mapper.createObjectNode().put("schemaVersion", bp1 ? "reviewed-rule-context-preparation-bp1-v9" : pp2 ? "reviewed-rule-context-preparation-pp2-v8" : bp3 ? "reviewed-rule-context-preparation-bp3-v7" : pvs1 ? "reviewed-rule-context-preparation-pvs1-v6" : pm1 ? "reviewed-rule-context-preparation-pm1-v5" : pm5 ? "reviewed-rule-context-preparation-pm5-v4" : ps1 ? "reviewed-rule-context-preparation-ps1-v3" : pm4 ? "reviewed-rule-context-preparation-pm4-v2" : "reviewed-rule-context-preparation-v1")
                .put("version", version).put("mode", "EXPLICIT_VERSIONED_TRANSCRIPT_CONFIRMATION")
                .put("automaticTrigger", false).put("classificationEnabled", false)
                .put("criterionPolicySelected", false).put("predictionCompatibilityClaimed", false);
        identity.set("reviewedRuleBinding", reviewedRuleBinding.deepCopy());
        selectionPolicy = PATH + "@" + version + ":" + new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(identity);
    }

    public ObjectNode identity() { return identity.deepCopy(); }
    public String selectionPolicy() { return selectionPolicy; }
    public boolean accepts(String value) { return selectionPolicy.equals(value); }
    public static boolean requested(String value) { return value != null && value.startsWith(PATH + "@"); }

    public boolean eligible(VariantInterpretation candidate, TranscriptConsequence annotation) {
        if (candidate == null || annotation == null || candidate.getObservation() == null || !eligibleAnnotation(candidate, annotation)) return false;
        if (!strictMissense) return true;
        var key = candidate.getObservation().getKey();
        return key != null && key.getReference() != null && key.getAlternate() != null
                && key.getReference().length() == 1 && key.getAlternate().length() == 1
                && "missense_variant".equals(annotation.getConsequence())
                && ("VEP".equals(annotation.getSource()) || "VEP_CACHED".equals(annotation.getSource()));
    }

    /** Representation eligibility only. Native lineage and actual reference evidence are verified during assembly. */
    public static boolean eligibleAnnotation(VariantInterpretation candidate, TranscriptConsequence annotation) {
        if (candidate == null || candidate.getObservation() == null || annotation == null) return false;
        var allele = candidate.getObservation().getKey();
        if (allele == null || !"GRCh38".equals(allele.getGenomeAssembly())
                || !smallAllele(allele.getReference(), allele.getAlternate())) return false;
        return annotation.getSource() != null && annotation.getSource().startsWith("VEP")
                && "116.0".equals(annotation.getVersion()) && "COMPLETED".equals(annotation.getStatus())
                && annotation.getTimestamp() != null && annotation.getTranscriptVersion() != null
                && annotation.getTranscriptVersion() > 0 && annotation.getVersionedTranscriptId() != null;
    }

    public static boolean smallAllele(String reference, String alternate) {
        if (reference == null || alternate == null || !reference.matches("[ACGT]{1,50}")
                || !alternate.matches("[ACGT]{1,50}") || reference.equals(alternate)) return false;
        return reference.length() == 1 && alternate.length() == 1
                || reference.length() == 1 && alternate.startsWith(reference)
                || alternate.length() == 1 && reference.startsWith(alternate);
    }
}
