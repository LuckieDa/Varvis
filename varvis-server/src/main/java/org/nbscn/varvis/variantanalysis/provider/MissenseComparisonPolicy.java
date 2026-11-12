package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Implemented comparison operators. Reviewed source knowledge is supplied by a pinned catalog. */
final class MissenseComparisonPolicy {
    static final String DIRECT_RESIDUE = "SAME_RESIDUE_DIFFERENT_SUBSTITUTION_SINGLE_REFERENCE";
    static final String COUNTED_GRANTHAM = "GRANTHAM_WITH_DISTINCT_PATHOGENIC_COUNT";
    static final String COUNTED_DIRECT = "DIRECT_RESIDUE_WITH_DISTINCT_PATHOGENIC_COUNT";
    static boolean countedMode(String mode) { return COUNTED_GRANTHAM.equals(mode) || COUNTED_DIRECT.equals(mode); }
    private static final Set<String> AMINO = Set.of("A","C","D","E","F","G","H","I","K","L","M","N","P","Q","R","S","T","V","W","Y");
    private MissenseComparisonPolicy() { }

    @Nullable static String issue(JsonNode policy, String criterion) {
        if (!Set.of(MissenseComparisonRuntime.PS1, MissenseComparisonRuntime.PM5).contains(criterion)) return "MISSENSE_RULE_OPERATOR_UNSUPPORTED";
        if (!only(policy, Set.of("schemaVersion", "policyId", "policyVersion", "criterionIds", "review", "scope", "sources",
                "nativeModelSha256", "annotationResourceSha256", "comparatorProfileSha256", "materialsSha256", "spliceAnnotationProofSha256",
                "registryBinding", "overlapPolicy", "splicePolicy", "rules", "referenceQualification", "expertSourceAdoption", "rnaApplicability"))) return "MISSENSE_POLICY_OPERATOR_UNSUPPORTED";
        if (!"missense-comparison-policy-v1".equals(policy.path("schemaVersion").asText())
                || !text(policy.path("policyId")) || !text(policy.path("policyVersion"))
                || !"REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText()) || !text(policy.at("/review/reviewId")))
            return "MISSENSE_POLICY_IDENTITY_OR_REVIEW_INVALID";
        boolean contained = false;
        for (JsonNode id : policy.path("criterionIds")) if (criterion.equals(id.asText())) contained = true;
        if (!contained) return "MISSENSE_POLICY_CRITERION_MISMATCH";
        JsonNode scope = policy.path("scope");
        if (!only(scope, Set.of("hgncId", "geneSymbol", "mondoId", "moi", "assembly", "protein", "variantType", "transcripts", "supportedEffects")))
            return "MISSENSE_POLICY_SCOPE_UNSUPPORTED";
        for (String field : Set.of("hgncId", "geneSymbol", "mondoId", "moi", "assembly", "protein"))
            if (!text(scope.path(field))) return "MISSENSE_POLICY_SCOPE_INCOMPLETE";
        if (!"GRCh38".equals(scope.path("assembly").asText())
                || !"NATIVE_NONPROJECTED_SNV".equals(scope.path("variantType").asText())
                || !scope.path("transcripts").isArray() || scope.path("transcripts").size() != 1
                || !text(scope.at("/transcripts/0/accession")) || !scope.at("/transcripts/0/version").isIntegralNumber()
                || scope.at("/transcripts/0/version").asInt() < 1
                || !"MISSENSE_EXCLUDING_START_AND_SPLICE".equals(scope.path("supportedEffects").asText()))
            return "MISSENSE_POLICY_SCOPE_UNSUPPORTED";
        if (!sourcesValid(policy.path("sources"))) return "MISSENSE_POLICY_SOURCES_INVALID";
        String expertIssue = MissenseExpertClassificationAdoption.policyIssue(policy);
        if (expertIssue != null) return expertIssue;
        String qualificationIssue = MissenseIndependentReference.policyIssue(policy);
        if (qualificationIssue != null) return qualificationIssue;
        for (String binding : Set.of("nativeModelSha256", "annotationResourceSha256", "comparatorProfileSha256", "materialsSha256", "spliceAnnotationProofSha256"))
            if (!hash(policy.path(binding))) return "MISSENSE_POLICY_INPUT_BINDING_MISSING";
        if (!text(policy.at("/registryBinding/registryId")) || !hash(policy.at("/registryBinding/registrySha256"))
                || !policy.at("/registryBinding/registryClasspathResource").asText().startsWith("/acmg/rule-registry-v2/"))
            return "MISSENSE_POLICY_REGISTRY_BINDING_INVALID";
        if (!"PS1_PRECEDENCE_NO_ADDITIVE_REFERENCE_SUPPORT".equals(policy.path("overlapPolicy").asText()))
            return "MISSENSE_OVERLAP_POLICY_UNSUPPORTED";
        JsonNode splice = policy.path("splicePolicy");
        if (!only(splice, Set.of("method", "operator", "maximumScore", "distanceBp", "mask", "aboveThresholdOutcome", "resourceSha256", "sourceIds", "basis"))
                || !"SpliceAI_RAW".equals(splice.path("method").asText())
                || !Set.of("LTE", "LT").contains(splice.path("operator").asText())
                || decimal(splice.path("maximumScore")) == null
                || !splice.path("distanceBp").isIntegralNumber() || splice.path("distanceBp").asInt() != 4999
                || !splice.path("mask").isIntegralNumber() || splice.path("mask").asInt() != 0
                || !"NOT_EVALUATED".equals(splice.path("aboveThresholdOutcome").asText())
                || !hash(splice.path("resourceSha256")) || !text(splice.path("basis")) || !boundSources(policy, splice.path("sourceIds")))
            return "MISSENSE_SPLICE_POLICY_UNSUPPORTED";
        BigDecimal maximum = decimal(splice.path("maximumScore"));
        if (maximum == null || maximum.signum() < 0 || maximum.compareTo(BigDecimal.ONE) > 0) return "MISSENSE_SPLICE_POLICY_UNSUPPORTED";
        String rnaPolicyIssue = MissenseRnaApplicability.policyIssue(policy, criterion);
        if (rnaPolicyIssue != null) return rnaPolicyIssue;
        String code = criterion.substring("ACMG_AMP_2015:".length());
        JsonNode rule = policy.path("rules").path(code);
        if (!only(rule, Set.of("mode", "classificationStrengths", "matrixKind", "matrixSha256", "comparison", "failureStrengths", "sourceIds", "classificationCeiling", "countRule"))
                || !boundSources(policy, rule.path("sourceIds"))) return "MISSENSE_RULE_OPERATOR_UNSUPPORTED";
        String mode = rule.path("mode").asText();
        boolean counted = countedMode(mode);
        if (counted ? !"PM5".equals(code) || countRuleIssue(policy, rule) != null : rule.has("countRule"))
            return "MISSENSE_RULE_OPERATOR_UNSUPPORTED";
        if ("PS1".equals(code)) {
            if (!"SAME_PROTEIN_CHANGE_SINGLE_REFERENCE".equals(mode)
                    || rule.has("matrixKind") || rule.has("matrixSha256") || rule.has("comparison") || rule.has("failureStrengths"))
                return "MISSENSE_RULE_OPERATOR_UNSUPPORTED";
        } else if ("PM5".equals(code)) {
            if ("AUDITED_AMINO_ACID_TRIPLES_SINGLE_REFERENCE".equals(mode) || DIRECT_RESIDUE.equals(mode)) {
                if (rule.has("matrixKind") || rule.has("matrixSha256") || rule.has("comparison") || rule.has("failureStrengths"))
                    return "MISSENSE_RULE_OPERATOR_UNSUPPORTED";
            } else if (COUNTED_DIRECT.equals(mode)) {
                // Fixed P-only Moderate fallback; no score or classification-strength map is accepted.
            } else if ("SUBSTITUTION_MATRIX_SINGLE_REFERENCE".equals(mode) || COUNTED_GRANTHAM.equals(mode)) {
                if (!hash(rule.path("matrixSha256"))
                        || !("GRANTHAM".equals(rule.path("matrixKind").asText()) && "TARGET_GTE_REFERENCE".equals(rule.path("comparison").asText())
                        || "BLOSUM62".equals(rule.path("matrixKind").asText()) && "TARGET_LTE_REFERENCE".equals(rule.path("comparison").asText())))
                    return "MISSENSE_RULE_OPERATOR_UNSUPPORTED";
                if (!strengths(rule.path("failureStrengths"), true, false)) return "MISSENSE_RULE_OPERATOR_UNSUPPORTED";
            } else return "MISSENSE_RULE_OPERATOR_UNSUPPORTED";
        } else return "MISSENSE_RULE_OPERATOR_UNSUPPORTED";
        if (!COUNTED_DIRECT.equals(mode) && !strengths(rule.path("classificationStrengths"), false, "PS1".equals(code)))
            return "MISSENSE_RULE_OPERATOR_UNSUPPORTED";
        if (rule.has("classificationCeiling")) {
            JsonNode ceiling = rule.path("classificationCeiling");
            if (!only(ceiling, Set.of("referenceClassification", "maximumTargetClassification", "enforcement"))
                    || !"LIKELY_PATHOGENIC".equals(ceiling.path("referenceClassification").asText())
                    || !"LIKELY_PATHOGENIC".equals(ceiling.path("maximumTargetClassification").asText())
                    || !"REFUSE_WITHOUT_FINAL_CLASSIFIER".equals(ceiling.path("enforcement").asText()))
                return "MISSENSE_CLASSIFICATION_CEILING_POLICY_UNSUPPORTED";
        }
        // BLOSUM62 LP-derived PM5 still requires the existing final-classification ceiling contract.
        if ("BLOSUM62".equals(rule.path("matrixKind").asText()) && rule.at("/classificationStrengths/LIKELY_PATHOGENIC").isTextual()
                && !rule.has("classificationCeiling"))
            return "MISSENSE_LP_CLASSIFICATION_CEILING_NOT_IMPLEMENTED";
        return null;
    }

    /** Closed count family: the original single-reference branches remain the fallback. */
    @Nullable private static String countRuleIssue(JsonNode policy, JsonNode rule) {
        JsonNode count = rule.path("countRule");
        if (!only(count, Set.of("countedClassification", "countBy", "minimum", "strength", "sourceIds"))
                || !"PATHOGENIC".equals(count.path("countedClassification").asText())
                || !"DISTINCT_REFERENCE_PROTEIN_SUBSTITUTION".equals(count.path("countBy").asText())
                || !count.path("minimum").isIntegralNumber() || !count.path("minimum").canConvertToInt()
                || count.path("minimum").asInt() != 2 || !"STRONG".equals(count.path("strength").asText())
                || !boundSources(policy, count.path("sourceIds"))) return "MISSENSE_RULE_OPERATOR_UNSUPPORTED";
        if (COUNTED_DIRECT.equals(rule.path("mode").asText()))
            return only(rule, Set.of("mode", "sourceIds", "countRule")) ? null : "MISSENSE_RULE_OPERATOR_UNSUPPORTED";
        if (!"GRANTHAM".equals(rule.path("matrixKind").asText())
                || !"TARGET_GTE_REFERENCE".equals(rule.path("comparison").asText())
                || !"MODERATE".equals(rule.at("/classificationStrengths/PATHOGENIC").asText())
                || !"SUPPORTING".equals(rule.at("/classificationStrengths/LIKELY_PATHOGENIC").asText())
                || !"SUPPORTING".equals(rule.at("/failureStrengths/PATHOGENIC").asText())
                || !"SUPPORTING".equals(rule.at("/failureStrengths/LIKELY_PATHOGENIC").asText()))
            return "MISSENSE_RULE_OPERATOR_UNSUPPORTED";
        return null;
    }

    private static boolean strengths(JsonNode values, boolean allowNone, boolean allowStrong) {
        if (!only(values, Set.of("PATHOGENIC", "LIKELY_PATHOGENIC")) || values.isEmpty()) return false;
        for (JsonNode strength : values) if (!(Set.of("SUPPORTING", "MODERATE").contains(strength.asText())
                || allowStrong && "STRONG".equals(strength.asText()) || allowNone && "NONE".equals(strength.asText()))) return false;
        return true;
    }

    @Nullable static String contextIssue(JsonNode policy, JsonNode context) {
        JsonNode scope = policy.path("scope");
        if (!scope.path("hgncId").equals(context.at("/gene/hgncId")) || !scope.path("geneSymbol").equals(context.at("/gene/symbol"))
                || !scope.path("mondoId").equals(context.at("/disease/mondoId")) || !scope.path("moi").equals(context.path("moi"))
                || !scope.path("assembly").equals(context.at("/variant/evidenceQueryAllele/assembly"))
                || !scope.at("/transcripts/0/accession").equals(context.at("/transcript/accession"))
                || !scope.at("/transcripts/0/version").equals(context.at("/transcript/version"))) return "MISSENSE_POLICY_CONTEXT_MISMATCH";
        if (!"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText()) || !"SNV".equals(context.at("/variant/variantType").asText()))
            return "MISSENSE_NATIVE_SNV_REQUIRED";
        String selection = context.at("/transcript/selectionPolicy").asText();
        if ("PM2_CONTEXT_ONLY".equals(selection) || org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.populationOnlySelection(selection))
            return "MISSENSE_TRANSCRIPT_CONFIRMATION_REQUIRED";
        return null;
    }

    @Nullable static String materialsIssue(JsonNode policy, JsonNode material, String criterion, RuntimeEvidenceV2Adapter adapter) {
        if (!"reviewed-missense-materials-v1".equals(material.path("schemaVersion").asText())
                || !text(material.path("materialId")) || !text(material.path("version"))
                || !"REVIEWED".equals(material.at("/review/status").asText()) || !text(material.at("/review/reviewId"))
                || !policy.path("materialsSha256").asText().equals(adapter.canonicalSha256(material))) return "MISSENSE_MATERIAL_IDENTITY_INVALID";
        if (!policy.path("scope").equals(material.path("scope"))) return "MISSENSE_MATERIAL_SCOPE_MISMATCH";
        if (!policy.path("nativeModelSha256").asText().equals(adapter.canonicalSha256(material.path("nativeModel")))
                || !policy.path("annotationResourceSha256").asText().equals(adapter.canonicalSha256(material.path("annotationResource")))
                || !policy.path("spliceAnnotationProofSha256").asText().equals(adapter.canonicalSha256(material.path("spliceAnnotationProof"))))
            return "MISSENSE_MATERIAL_INPUT_BINDING_MISMATCH";
        if (!modelSourcesBound(material.path("nativeModel"), policy.path("sources")))
            return "MISSENSE_MODEL_SOURCE_UNBOUND";
        JsonNode audit = material.path("referenceAudit");
        boolean sourceVerified = "SOURCE_VERIFIED".equals(audit.path("status").asText());
        if (!("REVIEWED".equals(audit.path("status").asText()) || sourceVerified) || !text(audit.path("reviewId"))
                || !boundSources(policy, audit.path("sourceIds")) || !audit.path("references").isArray()) return "MISSENSE_REFERENCE_AUDIT_MISSING";
        Set<String> ids = new HashSet<>();
        for (JsonNode ref : audit.path("references")) if (!text(ref.path("referenceId")) || !ids.add(ref.path("referenceId").asText())
                || !ref.path("scv").asText().matches("SCV[0-9]+\\.[1-9][0-9]*") || !boundSources(policy, ref.path("sourceIds"))) return "MISSENSE_REFERENCE_AUDIT_IDENTITY_INVALID";
        for (JsonNode ref : audit.path("references")) if (ref.has("conditionMappings")) {
            String mappingIssue = MissenseConditionMapping.issue(ref.path("conditionMappings"), policy);
            if (mappingIssue != null) return mappingIssue;
        }
        for (JsonNode ref : audit.path("references")) if (ref.has("qualification")) {
            String qualificationIssue = MissenseIndependentReference.issue(policy, ref, adapter);
            if (qualificationIssue != null) return qualificationIssue;
        }
        for (JsonNode ref : audit.path("references")) {
            if (sourceVerified && !MissenseExpertClassificationAdoption.sourceVerified(ref)) return "MISSENSE_AUTOMATED_REFERENCE_ROUTE_REQUIRED";
            String expertIssue = MissenseExpertReferenceEvidence.issue(ref, policy);
            if (expertIssue != null) return expertIssue;
        }
        JsonNode rna = material.path("rnaReview");
        if (MissenseRnaApplicability.automated(rna) || policy.has("rnaApplicability")) {
            String rnaIssue = MissenseRnaApplicability.materialIssue(policy, rna);
            if (rnaIssue != null) return rnaIssue;
        } else if (!"REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED".equals(rna.path("status").asText())
                || !"UNKNOWN".equals(rna.path("rnaEffect").asText()) || !rna.path("normalRnaClaimed").isBoolean() || rna.path("normalRnaClaimed").asBoolean()
                || !text(rna.path("reviewId")) || !boundSources(policy, rna.path("sourceIds"))
                || !rna.path("knownConcerns").isArray() || !rna.path("comparisons").isArray()) return "MISSENSE_RNA_REVIEW_MISSING";
        JsonNode proof = material.path("spliceAnnotationProof");
        if (!"FULL_EXON_BOUNDARIES_MATCH".equals(proof.path("status").asText()) || !text(proof.path("reviewId"))
                || !policy.path("nativeModelSha256").equals(proof.path("modelSha256"))
                || !policy.at("/splicePolicy/resourceSha256").equals(proof.path("resourceSha256"))
                || !material.at("/nativeModel/transcript").equals(proof.path("transcript"))
                || !boundSources(policy, proof.path("sourceIds")) || !proof.path("exons").equals(material.at("/nativeModel/exons")))
            return "MISSENSE_SPLICE_ANNOTATION_EQUIVALENCE_MISSING";
        JsonNode rule = policy.path("rules").path(criterion.substring("ACMG_AMP_2015:".length()));
        if ("SUBSTITUTION_MATRIX_SINGLE_REFERENCE".equals(rule.path("mode").asText())
                || COUNTED_GRANTHAM.equals(rule.path("mode").asText())) {
            JsonNode matrix = material.path("substitutionMatrix");
            if (!rule.path("matrixSha256").asText().equals(adapter.canonicalSha256(matrix))
                    || !rule.path("matrixKind").equals(matrix.path("matrixKind")) || !text(matrix.path("reviewId"))
                    || !boundSources(policy, matrix.path("sourceIds"))) return "MISSENSE_SUBSTITUTION_MATRIX_UNREVIEWED";
            JsonNode values = matrix.path("matrix");
            if (!only(values, AMINO) || values.size() != 20) return "MISSENSE_SUBSTITUTION_MATRIX_INVALID";
            for (String a : AMINO) {
                if (!only(values.path(a), AMINO) || values.path(a).size() != 20) return "MISSENSE_SUBSTITUTION_MATRIX_INVALID";
                for (String b : AMINO)
                if (!values.path(a).path(b).isIntegralNumber() || !values.path(a).path(b).canConvertToInt()
                        || !values.path(a).path(b).equals(values.path(b).path(a))) return "MISSENSE_SUBSTITUTION_MATRIX_INVALID";
            }
        }
        return null;
    }

    static boolean boundSources(JsonNode policy, JsonNode ids) {
        if (!ids.isArray() || ids.isEmpty()) return false;
        for (JsonNode id : ids) if (!id.isTextual() || source(policy, id.asText()) == null) return false;
        return true;
    }
    @Nullable static JsonNode source(JsonNode policy, String id) { for (JsonNode source : policy.path("sources")) if (id.equals(source.path("sourceId").asText())) return source; return null; }
    static boolean sourcesValid(JsonNode sources) {
        if (!sources.isArray() || sources.isEmpty()) return false;
        Set<String> ids = new HashSet<>();
        for (JsonNode source : sources) if (!text(source.path("sourceId")) || !ids.add(source.path("sourceId").asText())
                || !text(source.path("version")) || !hash(source.path("sha256")) || !sourceLocationValid(source)) return false;
        return true;
    }
    private static boolean sourceLocationValid(JsonNode source) {
        String location = source.path("url").asText();
        // The preparer verifies the actual pinned bytes; this locator exposes no private path
        // and does not pretend that a local review was published by an upstream organisation.
        return location.startsWith("https://")
                || location.equals("urn:varvis:source:sha256:" + source.path("sha256").asText());
    }
    private static boolean modelSourcesBound(JsonNode model, JsonNode sources) {
        if (!sourcesValid(sources)) return false;
        var upstream = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (JsonNode source : sources) if (source.path("url").asText().startsWith("https://")) upstream.add(source);
        // Local review records supplement policy provenance, never replace model authorities.
        return CodingTranscriptMapping.modelSourcesBound(model, upstream);
    }
    static boolean only(JsonNode node, Set<String> fields) { if (!node.isObject()) return false; var names = node.fieldNames(); while (names.hasNext()) if (!fields.contains(names.next())) return false; return true; }
    static boolean text(JsonNode node) { return node.isTextual() && !node.asText().isBlank(); }
    static boolean hash(JsonNode node) { return node.isTextual() && node.asText().matches("[0-9a-f]{64}"); }
    @Nullable static BigDecimal decimal(JsonNode node) { if (!node.isTextual()) return null; try { return new BigDecimal(node.asText()); } catch (NumberFormatException invalid) { return null; } }
}
