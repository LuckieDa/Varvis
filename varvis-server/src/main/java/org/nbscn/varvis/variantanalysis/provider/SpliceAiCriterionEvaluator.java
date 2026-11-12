package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Deterministic E3-C2 raw SpliceAI PP3/BP4 Supporting-only evaluator. */
public final class SpliceAiCriterionEvaluator {

    public static final String EVALUATOR_ID = "spliceai-criterion-evaluator";
    public static final String EVALUATOR_VERSION = "1.0.0-e3-c2";
    public static final String PROFILE_ID = "SPLICEAI_PP3_BP4_SUPPORTING";
    public static final String PROFILE_VERSION = "1.0.0";
    public static final String CALIBRATION_PROFILE_ID = PROFILE_ID + "@" + PROFILE_VERSION;
    public static final String BP7_PROFILE_ID = "SPLICEAI_PP3_BP4_BP7_SUPPORTING";
    public static final String BP7_PROFILE_VERSION = "1.1.0";

    private static final String PROFILE_RESOURCE =
            "/acmg/spliceai-pp3-bp4-supporting-profile-v1.json";
    private static final String REGISTRY_RESOURCE =
            "/acmg/rule-registry-v2/acmg-rule-registry-e3-c1-2026-09-02.json";
    private static final String BP7_PROFILE_RESOURCE =
            "/acmg/spliceai-bp7-supporting-profile-v1.json";
    private static final String BP7_REGISTRY_RESOURCE =
            "/acmg/rule-registry-v2/acmg-rule-registry-e3-c3-2026-09-02.json";
    private static final String PROFILE_CANONICAL_SHA256 =
            "4115c944f70eb7c2586f876a7083a6825b853cf9afd7c03588eb7e1ca970f099";
    private static final String REGISTRY_BYTE_SHA256 =
            "73fca655b6d76700242fc6b12ba4c6c4b9f34ac5c79b38621fe03fecc8303469";
    private static final String REGISTRY_CANONICAL_SHA256 =
            "61617eb005138ebc814b9c14afd9f3f34189fb7a93442c7c6843c780161b647e";
    private static final String REGISTRY_ID = "acmg-rule-registry-e3-c1-2026-09-02";
    private static final String BP7_PROFILE_CANONICAL_SHA256 =
            "b128e11f4d0e074422b771d195adbc3acdd893a66a56b600de9ff0e1518803e6";
    private static final String BP7_REGISTRY_BYTE_SHA256 =
            "5fb10c64a0d157708529e3617247375170117ee25d64f5a970efd484008b6f64";
    private static final String BP7_REGISTRY_CANONICAL_SHA256 =
            "1816c94310d9fc320143d02dd3f86e97155f9dfaa818a52145db4a5429d29942";
    private static final String BP7_REGISTRY_ID = "acmg-rule-registry-e3-c3-2026-09-02";
    private static final String AUTHORITY_ID = "authority-clingen-svi-splicing-2023";
    private static final String PP3 = "ACMG_AMP_2015:PP3";
    private static final String BP4 = "ACMG_AMP_2015:BP4";
    private static final String BP7 = "ACMG_AMP_2015:BP7";
    private static final String C14N = "varvis-json-c14n-v1";
    private static final String MAXIMUM_SCORE_METRIC = "spliceai.maximumRawDeltaScore";
    private static final String EXACT_ALLELE_METRIC = "spliceai.exactAlleleMatch";
    private static final String GENE_METRIC = "spliceai.geneSymbol";
    private static final String GENE_MATCH_METRIC = "spliceai.geneContextMatch";
    private static final String TRANSCRIPT_METRIC = "spliceai.selectedTranscriptId";
    private static final String VEP_TRANSCRIPT_METRIC = "vep.selectedTranscriptId";
    private static final String VEP_CONSEQUENCE_METRIC = "consequenceTerm";
    private static final String VEP_SYNONYMOUS_FIRST_METRIC =
            "vep.synonymousAtExonFirstBase";
    private static final String VEP_SYNONYMOUS_END_DISTANCE_METRIC =
            "vep.synonymousDistanceFromExonEnd";
    private static final String VEP_INTRONIC_OFFSET_METRIC = "vep.intronicSignedOffset";
    private static final List<String> DELTA_SCORE_METRICS = List.of(
            "spliceai.DS_AG", "spliceai.DS_AL", "spliceai.DS_DG", "spliceai.DS_DL");
    private static final List<String> DELTA_POSITION_METRICS = List.of(
            "spliceai.DP_AG", "spliceai.DP_AL", "spliceai.DP_DG", "spliceai.DP_DL");
    private static final Pattern SELECTION_ID =
            Pattern.compile("^cspec-selection-sha256-[0-9a-f]{64}$");

    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter canonicalizer;

    public SpliceAiCriterionEvaluator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.canonicalizer = new RuntimeEvidenceV2Adapter(mapper);
    }

    /** Loads the pinned raw SpliceAI Supporting-only profile. */
    public static ObjectNode loadProfile(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        ObjectNode profile = readObject(mapper, PROFILE_RESOURCE, null);
        String actual = new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(profile);
        if (!PROFILE_CANONICAL_SHA256.equals(actual)) {
            throw invalid("calibration profile canonical hash is not pinned");
        }
        return profile;
    }

    /** Loads the E3-C1 registry that already implements PP3 and BP4. */
    public static ObjectNode loadRuleRegistry(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        ObjectNode registry = readObject(mapper, REGISTRY_RESOURCE, REGISTRY_BYTE_SHA256);
        String canonical = new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(registry);
        if (!REGISTRY_CANONICAL_SHA256.equals(canonical)) {
            throw invalid("rule registry canonical hash is not pinned");
        }
        return registry;
    }

    /** Loads the E3-C3 BP7 successor profile. */
    public static ObjectNode loadBp7Profile(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        ObjectNode profile = readObject(mapper, BP7_PROFILE_RESOURCE, null);
        String actual = new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(profile);
        if (!BP7_PROFILE_CANONICAL_SHA256.equals(actual)) {
            throw invalid("BP7 profile canonical hash is not pinned");
        }
        return profile;
    }

    /** Loads the E3-C3 registry in which only BP7 advances beyond E3-C1. */
    public static ObjectNode loadBp7RuleRegistry(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        ObjectNode registry = readObject(
                mapper, BP7_REGISTRY_RESOURCE, BP7_REGISTRY_BYTE_SHA256);
        String actual = new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(registry);
        if (!BP7_REGISTRY_CANONICAL_SHA256.equals(actual)) {
            throw invalid("BP7 registry canonical hash is not pinned");
        }
        return registry;
    }

    /** Evaluates the classpath-pinned profile and registry. */
    public List<ObjectNode> evaluate(JsonNode evidenceContext,
                                     ObjectNode atomicEvidence,
                                     String selectedConsequence,
                                     String cspecSelectionId) {
        return evaluate(evidenceContext, atomicEvidence, selectedConsequence,
                loadProfile(mapper), loadRuleRegistry(mapper), cspecSelectionId);
    }

    /** Evaluates only PP3 and BP4 from one raw SpliceAI atomic evidence record. */
    public List<ObjectNode> evaluate(JsonNode evidenceContext,
                                     ObjectNode atomicEvidence,
                                     String selectedConsequence,
                                     ObjectNode profile,
                                     ObjectNode registry,
                                     String cspecSelectionId) {
        Objects.requireNonNull(evidenceContext, "evidenceContext");
        Objects.requireNonNull(atomicEvidence, "atomicEvidence");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(registry, "registry");
        if (cspecSelectionId == null || !SELECTION_ID.matcher(cspecSelectionId).matches()) {
            throw new IllegalArgumentException(
                    "cspecSelectionId must be an opaque canonical selection ID");
        }

        Bindings bindings = bindings(profile, registry);
        Decision decision = input(bindings, evidenceContext, atomicEvidence,
                selectedConsequence);
        return List.of(
                output(bindings, bindings.pp3(), atomicEvidence, cspecSelectionId, decision),
                output(bindings, bindings.bp4(), atomicEvidence, cspecSelectionId, decision));
    }

    /** Evaluates BP7 from the selected VEP position and the existing same-context BP4 result. */
    public ObjectNode evaluateBp7(JsonNode evidenceContext,
                                  ObjectNode spliceAiEvidence,
                                  ObjectNode vepEvidence,
                                  ObjectNode bp4Evaluation,
                                  String cspecSelectionId) {
        return evaluateBp7(evidenceContext, spliceAiEvidence, vepEvidence, bp4Evaluation,
                loadBp7Profile(mapper), loadBp7RuleRegistry(mapper), cspecSelectionId);
    }

    /** Evaluates only BP7; it does not recompute SpliceAI or BP4. */
    public ObjectNode evaluateBp7(JsonNode evidenceContext,
                                  ObjectNode spliceAiEvidence,
                                  ObjectNode vepEvidence,
                                  ObjectNode bp4Evaluation,
                                  ObjectNode profile,
                                  ObjectNode registry,
                                  String cspecSelectionId) {
        Objects.requireNonNull(evidenceContext, "evidenceContext");
        Objects.requireNonNull(spliceAiEvidence, "spliceAiEvidence");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(registry, "registry");
        if (cspecSelectionId == null || !SELECTION_ID.matcher(cspecSelectionId).matches()) {
            throw new IllegalArgumentException(
                    "cspecSelectionId must be an opaque canonical selection ID");
        }
        Bp7Bindings bindings = bp7Bindings(profile, registry);
        Bp7Decision decision = bp7Input(bindings, evidenceContext, spliceAiEvidence,
                vepEvidence, bp4Evaluation);
        return bp7Output(bindings, spliceAiEvidence, vepEvidence,
                cspecSelectionId, decision);
    }

    private Bindings bindings(ObjectNode profile, ObjectNode registry) {
        String profileHash = canonicalizer.canonicalSha256(profile);
        String issue = PROFILE_CANONICAL_SHA256.equals(profileHash)
                ? null : "PROFILE_HASH_MISMATCH";

        JsonNode registryBinding = profile.path("registryBinding");
        String registryId = registryBinding.path("registryId").asText();
        String registryHash = registryBinding.path("registrySha256").asText();
        if (issue == null && (!REGISTRY_CANONICAL_SHA256.equals(
                canonicalizer.canonicalSha256(registry))
                || !REGISTRY_ID.equals(registryId)
                || !REGISTRY_ID.equals(registry.path("registryId").asText())
                || !REGISTRY_BYTE_SHA256.equals(registryHash))) {
            issue = "RULE_REGISTRY_BINDING_MISMATCH";
        }

        Set<String> authorities = authorityIds(profile.path("authorityBindings"));
        Criterion pp3 = criterion(profile, registry, PP3, authorities);
        Criterion bp4 = criterion(profile, registry, BP4, authorities);
        if (issue == null && (!criterionBound(pp3, registry)
                || !criterionBound(bp4, registry))) {
            issue = "RULE_REGISTRY_BINDING_MISMATCH";
        }

        JsonNode resource = profile.path("resources").path(0);
        JsonNode benign = profile.path("thresholds").path("benignMaxInclusive");
        JsonNode pathogenic = profile.path("thresholds").path("pathogenicMinInclusive");
        Set<String> allowed = textSet(profile.path("applicability")
                .path("allowedConsequenceTerms"));
        Set<String> excluded = textSet(profile.path("applicability")
                .path("excludedConsequenceTerms"));
        if (issue == null && (!allowed.equals(Set.of("splice_region_variant",
                "intron_variant", "synonymous_variant"))
                || !excluded.equals(Set.of("missense_variant", "splice_donor_variant",
                "splice_acceptor_variant")))) {
            issue = "PROFILE_APPLICABILITY_MISMATCH";
        }
        return new Bindings(profileHash, registryId, registryHash, resource.deepCopy(),
                decimal(benign.path("rawValue")), decimal(pathogenic.path("rawValue")),
                Collections.unmodifiableSet(allowed), Collections.unmodifiableSet(excluded),
                pp3, bp4, issue);
    }

    /** E4-B3 invocation successor: unchanged calibration, explicitly pinned E3-C3 rule binding. */
    List<ObjectNode> evaluateBusiness(JsonNode context, ObjectNode evidence, String consequence, String selectionId) {
        return evaluateBusinessClauses(context, evidence, consequence, selectionId, Set.of(PP3, BP4));
    }
    List<ObjectNode> evaluateBusinessClauses(JsonNode context, ObjectNode evidence, String consequence, String selectionId, Set<String> clauses) {
        if (!Set.of(PP3, BP4).containsAll(clauses)) throw invalid("SPLICEAI_CLAUSE_MASK_INVALID");
        if (clauses.isEmpty()) return List.of();
        if (selectionId == null || !SELECTION_ID.matcher(selectionId).matches()) throw invalid("selection ID required");
        var inherited = bindings(loadProfile(mapper), loadRuleRegistry(mapper));
        var registry = loadBp7RuleRegistry(mapper);
        var b = new Bindings(inherited.profileHash(), BP7_REGISTRY_ID, BP7_REGISTRY_BYTE_SHA256,
                inherited.resource(), inherited.benignMaximum(), inherited.pathogenicMinimum(), inherited.allowedTerms(), inherited.excludedTerms(),
                businessCriterion(inherited.pp3(), registry), businessCriterion(inherited.bp4(), registry), inherited.issue());
        var decision = input(b, context, evidence, consequence);
        var outputs = new java.util.ArrayList<ObjectNode>();
        if (clauses.contains(PP3)) outputs.add(output(b, b.pp3(), evidence, selectionId, decision));
        if (clauses.contains(BP4)) outputs.add(output(b, b.bp4(), evidence, selectionId, decision));
        return List.copyOf(outputs);
    }
    private Criterion businessCriterion(Criterion inherited, ObjectNode registry) {
        var row = find(registry.path("criteria"), "criterionId", inherited.id());
        // E3-C3 changes BP7 only. Verify that inherited PP3/BP4 scientific registry records are identical.
        if (!row.equals(inherited.registered())) throw invalid("business successor criterion mismatch");
        return new Criterion(inherited.id(), inherited.designation(), inherited.strength(),
                textSet(row.path("currentGeneral").path("authoritySourceIds")), row);
    }
    ObjectNode evaluateBusinessBp7(JsonNode context, ObjectNode splice, ObjectNode vep, ObjectNode bp4, String selectionId) {
        if (selectionId == null || !SELECTION_ID.matcher(selectionId).matches()) throw invalid("selection ID required");
        var b = bp7Bindings(loadBp7Profile(mapper), loadBp7RuleRegistry(mapper));
        return bp7Output(b, splice, vep, selectionId, bp7Input(b, context, splice, vep, bp4, BP7_REGISTRY_ID));
    }

    private Bp7Bindings bp7Bindings(ObjectNode profile, ObjectNode registry) {
        String profileHash = canonicalizer.canonicalSha256(profile);
        String issue = BP7_PROFILE_CANONICAL_SHA256.equals(profileHash)
                ? null : "PROFILE_HASH_MISMATCH";

        JsonNode registryBinding = profile.path("registryBinding");
        String registryId = registryBinding.path("registryId").asText();
        String registryHash = registryBinding.path("registrySha256").asText();
        if (issue == null && (!BP7_REGISTRY_CANONICAL_SHA256.equals(
                canonicalizer.canonicalSha256(registry))
                || !BP7_REGISTRY_ID.equals(registryId)
                || !BP7_REGISTRY_ID.equals(registry.path("registryId").asText())
                || !BP7_REGISTRY_BYTE_SHA256.equals(registryHash))) {
            issue = "RULE_REGISTRY_BINDING_MISMATCH";
        }

        Set<String> authorities = authorityIds(profile.path("authorityBindings"));
        Criterion bp7 = criterion(profile, registry, BP7, authorities);
        if (issue == null && !criterionBound(bp7, registry)) {
            issue = "RULE_REGISTRY_BINDING_MISMATCH";
        }

        JsonNode rules = profile.path("bp7PositionRules");
        JsonNode vepResource = rules.path("resourceBinding");
        JsonNode synonymous = rules.path("synonymousVariant");
        JsonNode intronic = rules.path("intronicVariant");
        int endDistance = synonymous.path("minimumDistanceFromExonEndInclusive").asInt(-1);
        int donorOffset = intronic.path("donorMinimumSignedOffsetInclusive").asInt(-1);
        int acceptorOffset = intronic.path("acceptorMaximumSignedOffsetInclusive").asInt(1);
        if (issue == null && (!BP7_PROFILE_ID.equals(profile.path("profileId").asText())
                || !BP7_PROFILE_VERSION.equals(profile.path("profileVersion").asText())
                || endDistance != 3 || donorOffset != 7 || acceptorOffset != -21
                || !"VEP_116_GRCH38".equals(vepResource.path("resourceId").asText())
                || !"GRCh38".equals(vepResource.path("assembly").asText())
                || !"43c0422c1d2f2bc2221c60a96433f1602de7205113f107d027deea0b7eabdbed"
                .equals(vepResource.path("identityFingerprintSha256").asText()))) {
            issue = "BP7_PROFILE_BINDING_MISMATCH";
        }
        return new Bp7Bindings(profileHash, registryId, registryHash,
                vepResource.deepCopy(), endDistance, donorOffset, acceptorOffset,
                bp7, issue);
    }

    /** Reuses the raw-model identity, four axes and four-delta maximum checks before policy thresholding. */
    PredictionModelInput inputForPolicy(JsonNode context, ObjectNode evidence, String consequence) {
        Decision decision = input(bindings(loadProfile(mapper), loadRuleRegistry(mapper)), context, evidence, consequence);
        if (!decision.completed()) return new PredictionModelInput(null, decision.reason());
        for (ObjectNode parameter : decision.parameters())
            if ("score.raw".equals(parameter.path("name").asText()))
                return new PredictionModelInput(new BigDecimal(parameter.path("value").asText()), null);
        return new PredictionModelInput(null, "SPLICEAI_SCORE_INVALID");
    }

    private Criterion criterion(ObjectNode profile,
                                ObjectNode registry,
                                String criterionId,
                                Set<String> authorities) {
        JsonNode declared = find(profile.path("evaluation").path("criteria"),
                "criterionId", criterionId);
        JsonNode registered = find(registry.path("criteria"), "criterionId", criterionId);
        return new Criterion(criterionId,
                declared.path("currentDesignation").asText(),
                declared.path("baseStrength").asText(),
                Collections.unmodifiableSet(new LinkedHashSet<>(authorities)), registered);
    }

    private boolean criterionBound(Criterion criterion, JsonNode registry) {
        JsonNode declared = criterion.registered();
        Set<String> registeredAuthorities = textSet(
                declared.path("currentGeneral").path("authoritySourceIds"));
        return declared.isObject()
                && declared.equals(find(registry.path("criteria"),
                "criterionId", criterion.id()))
                && "V2_IMPLEMENTED".equals(declared.path("implementationStatus").asText())
                && criterion.designation().equals(
                declared.path("currentGeneral").path("designation").asText())
                && "SUPPORTING".equals(criterion.strength())
                && criterion.strength().equals(
                declared.path("currentGeneral").path("strength").asText())
                && criterion.authorities().equals(Set.of(AUTHORITY_ID))
                && registeredAuthorities.containsAll(criterion.authorities());
    }

    private Decision input(Bindings bindings,
                           JsonNode context,
                           ObjectNode evidence,
                           String selectedConsequence) {
        List<ObjectNode> steps = new ArrayList<>();
        List<ObjectNode> parameters = baseParameters(bindings, evidence);
        parameters.add(parameter("context.selectedConsequence",
                selectedConsequence == null ? "" : selectedConsequence, "SO-term"));
        if (bindings.issue() != null) {
            return blocked("UNKNOWN", bindings.issue(), steps, parameters, evidence);
        }
        if (!context.path("contextId").asText().equals(evidence.path("contextId").asText())) {
            return blocked("UNKNOWN", "ATOMIC_EVIDENCE_CONTEXT_MISMATCH",
                    steps, parameters, evidence);
        }
        if (!"evidence-context-v2".equals(context.path("schemaVersion").asText())
                || !C14N.equals(context.path("canonicalizationProfile").asText())
                || !"atomic-evidence-v2".equals(evidence.path("schemaVersion").asText())
                || !C14N.equals(evidence.path("canonicalizationProfile").asText())
                || !"COMPUTATIONAL_PREDICTION".equals(evidence.path("evidenceType").asText())
                || !RuntimeEvidenceV2Adapter.PRODUCER_ID.equals(
                evidence.path("provenance").path("producerId").asText())) {
            return blocked("UNKNOWN", "ATOMIC_EVIDENCE_BINDING_MISMATCH",
                    steps, parameters, evidence);
        }
        JsonNode source = evidence.path("source");
        JsonNode resource = bindings.resource();
        if (!resource.path("resourceId").asText().equals(source.path("sourceId").asText())
                || !resource.path("resourceVersion").asText()
                .equals(source.path("resourceVersion").asText())
                || !resource.path("identityFingerprintSha256").asText()
                .equals(source.path("resourceSha256").asText())) {
            return blocked("UNKNOWN", "RESOURCE_IDENTITY_MISMATCH",
                    steps, parameters, evidence);
        }
        if (!completeContext(context)) {
            return blocked("UNKNOWN", "CONTEXT_INCOMPLETE", steps, parameters, evidence);
        }
        if (selectedConsequence == null || selectedConsequence.isBlank()) {
            return blocked("UNKNOWN", "SELECTED_CONSEQUENCE_MISSING",
                    steps, parameters, evidence);
        }

        JsonNode variant = context.path("variant");
        JsonNode queryAllele = variant.path("evidenceQueryAllele");
        boolean nativeGrch38 = "GRCh38".equals(queryAllele.path("assembly").asText())
                && "NATIVE".equals(variant.path("assemblyLineage")
                .path("mappingStatus").asText());
        Set<String> consequenceTerms = consequenceTerms(selectedConsequence);
        boolean excluded = !Collections.disjoint(consequenceTerms, bindings.excludedTerms());
        boolean allowed = !Collections.disjoint(consequenceTerms, bindings.allowedTerms());
        if (!nativeGrch38 || excluded || !allowed
                || "NOT_APPLICABLE".equals(evidence.path("resourceApplicability")
                .path("status").asText())) {
            return blocked("NOT_APPLICABLE", "SPLICEAI_PROFILE_NOT_APPLICABLE",
                    steps, parameters, evidence);
        }
        steps.add(step("SELECTED_CONSEQUENCE_APPLICABLE", "TRUE", evidence));

        String execution = evidence.path("resourceExecution").path("status").asText();
        String availability = evidence.path("resourceAvailability").path("status").asText();
        String query = evidence.path("queryOutcome").path("status").asText();
        String applicability = evidence.path("resourceApplicability").path("status").asText();
        parameters.add(parameter("resource.executionStatus", execution, null));
        parameters.add(parameter("resource.availabilityStatus", availability, null));
        parameters.add(parameter("resource.queryStatus", query, null));
        parameters.add(parameter("resource.applicabilityStatus", applicability, null));
        if ("FAILED".equals(execution)) {
            return blocked("UNKNOWN", "RESOURCE_EXECUTION_FAILED",
                    steps, parameters, evidence);
        }
        if ("TIMEOUT".equals(execution)) {
            return blocked("UNKNOWN", "RESOURCE_EXECUTION_TIMEOUT",
                    steps, parameters, evidence);
        }
        if (!"SUCCEEDED".equals(execution)) {
            return blocked("UNKNOWN", "RESOURCE_EXECUTION_NOT_SUCCEEDED",
                    steps, parameters, evidence);
        }
        if (!"AVAILABLE".equals(availability)) {
            return blocked("UNKNOWN", "RESOURCE_NOT_AVAILABLE",
                    steps, parameters, evidence);
        }
        if ("NO_RECORD".equals(query)) {
            return blocked("UNKNOWN", "SPLICEAI_NO_RECORD",
                    steps, parameters, evidence);
        }
        if (!"HIT".equals(query)
                || evidence.path("queryOutcome").path("recordCount").asInt(-1) != 1) {
            return blocked("UNKNOWN", "QUERY_NOT_SINGLE_EXACT_HIT",
                    steps, parameters, evidence);
        }
        if (!"APPLICABLE".equals(applicability)) {
            return blocked("UNKNOWN", "RESOURCE_APPLICABILITY_UNKNOWN",
                    steps, parameters, evidence);
        }
        steps.add(step("RESOURCE_FOUR_AXES_READY", "TRUE", evidence));

        Reading exactAllele = reading(evidence, EXACT_ALLELE_METRIC);
        if (exactAllele.reason() != null) {
            return blocked("UNKNOWN", exactAllele.reason(), steps, parameters, evidence);
        }
        if (!trueMeasurement(exactAllele.node())) {
            return blocked("UNKNOWN", "EXACT_ALLELE_NOT_CONFIRMED",
                    steps, parameters, evidence);
        }
        steps.add(step("EXACT_ALLELE_CONFIRMED", "TRUE", evidence));

        String contextGene = context.path("gene").path("symbol").asText();
        Reading gene = reading(evidence, GENE_METRIC);
        Reading geneMatch = reading(evidence, GENE_MATCH_METRIC);
        if (gene.reason() != null || geneMatch.reason() != null) {
            return blocked("UNKNOWN", gene.reason() != null ? gene.reason() : geneMatch.reason(),
                    steps, parameters, evidence);
        }
        String evidenceGene = textMeasurement(gene.node(), "gene-symbol");
        if (!trueMeasurement(geneMatch.node()) || !contextGene.equals(evidenceGene)) {
            return blocked("UNKNOWN", "GENE_CONTEXT_MISMATCH",
                    steps, parameters, evidence);
        }
        parameters.add(parameter("context.geneSymbol", contextGene, "gene-symbol"));
        steps.add(step("UNIQUE_GENE_CONTEXT_MATCH_CONFIRMED", "TRUE", evidence));

        String contextTranscript = context.path("transcript").path("accession").asText()
                + "." + context.path("transcript").path("version").asInt();
        Reading transcript = reading(evidence, TRANSCRIPT_METRIC);
        if (transcript.reason() != null) {
            return blocked("UNKNOWN", transcript.reason(), steps, parameters, evidence);
        }
        String evidenceTranscript = textMeasurement(transcript.node(), "transcript-id");
        if (!contextTranscript.equals(evidenceTranscript)) {
            return blocked("UNKNOWN", "SELECTED_TRANSCRIPT_CONTEXT_MISMATCH",
                    steps, parameters, evidence);
        }
        parameters.add(parameter("context.transcriptId", contextTranscript, "transcript-id"));

        BigDecimal calculatedMaximum = null;
        for (String metricId : DELTA_SCORE_METRICS) {
            Reading reading = reading(evidence, metricId);
            if (reading.reason() != null) {
                return blocked("UNKNOWN", reading.reason(), steps, parameters, evidence);
            }
            BigDecimal score = score(reading.node());
            if (score == null) {
                return blocked("UNKNOWN", "SPLICEAI_SCORE_INVALID",
                        steps, parameters, evidence);
            }
            if (calculatedMaximum == null || score.compareTo(calculatedMaximum) > 0) {
                calculatedMaximum = score;
            }
            parameters.add(parameter("score." + metricId.substring("spliceai.".length()),
                    reading.node().path("rawValue").asText(), "score"));
        }
        for (String metricId : DELTA_POSITION_METRICS) {
            Reading reading = reading(evidence, metricId);
            if (reading.reason() != null) {
                return blocked("UNKNOWN", reading.reason(), steps, parameters, evidence);
            }
            if (!validPosition(reading.node())) {
                return blocked("UNKNOWN", "SPLICEAI_DELTA_POSITION_INVALID",
                        steps, parameters, evidence);
            }
        }
        Reading maximumReading = reading(evidence, MAXIMUM_SCORE_METRIC);
        if (maximumReading.reason() != null) {
            return blocked("UNKNOWN", maximumReading.reason(), steps, parameters, evidence);
        }
        BigDecimal maximum = score(maximumReading.node());
        if (maximum == null || calculatedMaximum == null
                || maximum.compareTo(calculatedMaximum) != 0) {
            return blocked("UNKNOWN", "SPLICEAI_MAXIMUM_SCORE_MISMATCH",
                    steps, parameters, evidence);
        }

        String rawMaximum = maximumReading.node().path("rawValue").asText();
        parameters.add(parameter("score.metricId", MAXIMUM_SCORE_METRIC, null));
        parameters.add(parameter("score.raw", rawMaximum, "score"));
        parameters.add(parameter("score.normalized", canonicalDecimal(maximum), "score"));
        parameters.add(parameter("threshold.bp4.maxInclusive", "0.10", "score"));
        parameters.add(parameter("threshold.pp3.minInclusive", "0.20", "score"));
        parameters.add(parameter("strength.cap", "SUPPORTING", null));
        boolean bp4Met = maximum.compareTo(bindings.benignMaximum()) <= 0;
        boolean pp3Met = maximum.compareTo(bindings.pathogenicMinimum()) >= 0;
        if (bp4Met && pp3Met) {
            return blocked("UNKNOWN", "PP3_BP4_MUTUAL_EXCLUSION_VIOLATION",
                    steps, parameters, evidence);
        }
        steps.add(step("BP4_MAXIMUM_SCORE_SATISFIED", bp4Met ? "TRUE" : "FALSE", evidence));
        steps.add(step("PP3_MINIMUM_SCORE_SATISFIED", pp3Met ? "TRUE" : "FALSE", evidence));
        return new Decision("APPLICABLE", pp3Met ? "MET" : "NOT_MET",
                bp4Met ? "MET" : "NOT_MET", null, true, steps, parameters);
    }

    private Bp7Decision bp7Input(Bp7Bindings bindings,
                                 JsonNode context,
                                 ObjectNode spliceAiEvidence,
                                 ObjectNode vepEvidence,
                                 ObjectNode bp4Evaluation) {
        return bp7Input(bindings, context, spliceAiEvidence, vepEvidence, bp4Evaluation, REGISTRY_ID);
    }
    private Bp7Decision bp7Input(Bp7Bindings bindings, JsonNode context, ObjectNode spliceAiEvidence,
                                 ObjectNode vepEvidence, ObjectNode bp4Evaluation, String bp4RegistryId) {
        List<ObjectNode> steps = new ArrayList<>();
        List<ObjectNode> parameters = bp7BaseParameters(bindings);
        List<String> inputIds = evidenceIds(spliceAiEvidence, vepEvidence);
        if (bindings.issue() != null) {
            return bp7Blocked("UNKNOWN", bindings.issue(), steps, parameters, inputIds);
        }
        String contextId = context.path("contextId").asText();
        if (!"evidence-context-v2".equals(context.path("schemaVersion").asText())
                || !C14N.equals(context.path("canonicalizationProfile").asText())
                || !completeContext(context)
                || !contextId.equals(spliceAiEvidence.path("contextId").asText())) {
            return bp7Blocked("UNKNOWN", "CONTEXT_INCOMPLETE_OR_MISMATCHED",
                    steps, parameters, inputIds);
        }
        if (vepEvidence == null) {
            return bp7Blocked("UNKNOWN", "VEP_POSITION_EVIDENCE_MISSING",
                    steps, parameters, inputIds);
        }
        if (!contextId.equals(vepEvidence.path("contextId").asText())
                || !"atomic-evidence-v2".equals(vepEvidence.path("schemaVersion").asText())
                || !C14N.equals(vepEvidence.path("canonicalizationProfile").asText())
                || !"VARIANT_CONSEQUENCE".equals(vepEvidence.path("evidenceType").asText())
                || !RuntimeEvidenceV2Adapter.PRODUCER_ID.equals(
                vepEvidence.path("provenance").path("producerId").asText())) {
            return bp7Blocked("UNKNOWN", "VEP_POSITION_EVIDENCE_BINDING_MISMATCH",
                    steps, parameters, inputIds);
        }
        JsonNode vepSource = vepEvidence.path("source");
        JsonNode vepResource = bindings.vepResource();
        if (!vepResource.path("resourceId").asText()
                .equals(vepSource.path("sourceId").asText())
                || !vepResource.path("resourceVersion").asText()
                .equals(vepSource.path("resourceVersion").asText())
                || !vepResource.path("identityFingerprintSha256").asText()
                .equals(vepSource.path("resourceSha256").asText())) {
            return bp7Blocked("UNKNOWN", "VEP_RESOURCE_IDENTITY_MISMATCH",
                    steps, parameters, inputIds);
        }
        String vepExecution = vepEvidence.path("resourceExecution").path("status").asText();
        String vepAvailability = vepEvidence.path("resourceAvailability").path("status").asText();
        String vepQuery = vepEvidence.path("queryOutcome").path("status").asText();
        String vepApplicability = vepEvidence.path("resourceApplicability")
                .path("status").asText();
        if (!"SUCCEEDED".equals(vepExecution) || !"AVAILABLE".equals(vepAvailability)
                || !"HIT".equals(vepQuery)
                || vepEvidence.path("queryOutcome").path("recordCount").asInt(-1) != 1
                || !"APPLICABLE".equals(vepApplicability)) {
            return bp7Blocked("UNKNOWN", "VEP_POSITION_EVIDENCE_NOT_READY",
                    steps, parameters, inputIds);
        }

        Reading vepTranscriptReading = plainReading(vepEvidence, VEP_TRANSCRIPT_METRIC);
        Reading consequenceReading = plainReading(vepEvidence, VEP_CONSEQUENCE_METRIC);
        if (vepTranscriptReading.reason() != null || consequenceReading.reason() != null) {
            return bp7Blocked("UNKNOWN", vepTranscriptReading.reason() != null
                    ? vepTranscriptReading.reason() : consequenceReading.reason(),
                    steps, parameters, inputIds);
        }
        String vepTranscript = plainTextMeasurement(
                vepTranscriptReading.node(), "transcript-id");
        String selectedConsequence = plainTextMeasurement(consequenceReading.node(), null);
        String contextTranscript = context.path("transcript").path("accession").asText()
                + "." + context.path("transcript").path("version").asInt();
        Reading spliceTranscriptReading = reading(spliceAiEvidence, TRANSCRIPT_METRIC);
        String spliceTranscript = spliceTranscriptReading.reason() == null
                ? textMeasurement(spliceTranscriptReading.node(), "transcript-id") : null;
        if (vepTranscript == null || selectedConsequence == null
                || !contextTranscript.equals(vepTranscript)
                || !contextTranscript.equals(spliceTranscript)) {
            return bp7Blocked("UNKNOWN", "SELECTED_TRANSCRIPT_CONTEXT_MISMATCH",
                    steps, parameters, inputIds);
        }
        parameters.add(parameter("context.selectedTranscriptId", contextTranscript,
                "transcript-id"));
        parameters.add(parameter("context.selectedConsequence", selectedConsequence,
                "SO-term"));
        steps.add(bp7Step("VEP_SELECTED_TRANSCRIPT_BOUND", "TRUE", inputIds));

        Set<String> terms = consequenceTerms(selectedConsequence);
        if (terms.contains("missense_variant") || terms.contains("splice_donor_variant")
                || terms.contains("splice_acceptor_variant")) {
            return bp7Blocked("NOT_APPLICABLE", "BP7_CONSEQUENCE_NOT_APPLICABLE",
                    steps, parameters, inputIds);
        }
        boolean synonymous = terms.contains("synonymous_variant");
        boolean intronic = terms.contains("intron_variant");
        if (!synonymous && !intronic) {
            return bp7Blocked("NOT_APPLICABLE", "BP7_CONSEQUENCE_NOT_APPLICABLE",
                    steps, parameters, inputIds);
        }
        if (synonymous && intronic) {
            return bp7Blocked("UNKNOWN", "BP7_POSITION_CLASS_AMBIGUOUS",
                    steps, parameters, inputIds);
        }

        if (synonymous) {
            Reading firstReading = plainReading(vepEvidence, VEP_SYNONYMOUS_FIRST_METRIC);
            Reading distanceReading = plainReading(
                    vepEvidence, VEP_SYNONYMOUS_END_DISTANCE_METRIC);
            if (firstReading.reason() != null || distanceReading.reason() != null) {
                return bp7Blocked("UNKNOWN", "TRANSCRIPT_RELATIVE_POSITION_MISSING",
                        steps, parameters, inputIds);
            }
            Boolean first = booleanMeasurement(firstReading.node());
            Integer distance = integerMeasurement(distanceReading.node(), "base-offset");
            if (first == null || distance == null || distance < 0) {
                return bp7Blocked("UNKNOWN", "TRANSCRIPT_RELATIVE_POSITION_INVALID",
                        steps, parameters, inputIds);
            }
            parameters.add(parameter("position.synonymousAtExonFirstBase",
                    first.toString(), "boolean"));
            parameters.add(parameter("position.synonymousDistanceFromExonEnd",
                    distance.toString(), "base-offset"));
            if (first || distance < bindings.minimumEndDistance()) {
                return bp7Blocked("NOT_APPLICABLE", "BP7_SYNONYMOUS_EXON_BOUNDARY",
                        steps, parameters, inputIds);
            }
            steps.add(bp7Step("BP7_SYNONYMOUS_POSITION_ELIGIBLE", "TRUE", inputIds));
        } else {
            Reading offsetReading = plainReading(vepEvidence, VEP_INTRONIC_OFFSET_METRIC);
            if (offsetReading.reason() != null) {
                return bp7Blocked("UNKNOWN", "TRANSCRIPT_RELATIVE_POSITION_MISSING",
                        steps, parameters, inputIds);
            }
            Integer offset = integerMeasurement(offsetReading.node(), "base-offset");
            if (offset == null || offset == 0) {
                return bp7Blocked("UNKNOWN", "TRANSCRIPT_RELATIVE_POSITION_INVALID",
                        steps, parameters, inputIds);
            }
            parameters.add(parameter("position.intronicSignedOffset",
                    offset.toString(), "base-offset"));
            boolean eligible = offset >= bindings.donorMinimum()
                    || offset <= bindings.acceptorMaximum();
            if (!eligible) {
                return bp7Blocked("NOT_APPLICABLE", "BP7_INTRONIC_SPLICE_PROXIMAL",
                        steps, parameters, inputIds);
            }
            steps.add(bp7Step("BP7_INTRONIC_POSITION_ELIGIBLE", "TRUE", inputIds));
        }

        if (bp4Evaluation == null) {
            return bp7Blocked("UNKNOWN", "SAME_CONTEXT_BP4_EVALUATION_MISSING",
                    steps, parameters, inputIds);
        }
        String spliceEvidenceId = spliceAiEvidence.path("evidenceId").asText();
        if (!"criterion-evaluation-v2".equals(
                bp4Evaluation.path("schemaVersion").asText())
                || !BP4.equals(bp4Evaluation.path("criterionId").asText())
                || !contextId.equals(bp4Evaluation.path("contextId").asText())
                || !bp4RegistryId.equals(bp4Evaluation.path("ruleBinding")
                .path("ruleRegistryId").asText())
                || bp4Evaluation.path("evidenceIds").size() != 1
                || !containsText(bp4Evaluation.path("evidenceIds"), spliceEvidenceId)
                || !containsText(bp4Evaluation.path("deterministicTrace")
                .path("inputEvidenceIds"), spliceEvidenceId)) {
            return bp7Blocked("UNKNOWN", "SAME_CONTEXT_BP4_BINDING_MISMATCH",
                    steps, parameters, inputIds);
        }
        String bp4Status = bp4Evaluation.path("evaluationResult").path("status").asText();
        parameters.add(parameter("dependency.bp4EvaluationId",
                bp4Evaluation.path("evaluationId").asText(), null));
        parameters.add(parameter("dependency.bp4Status", bp4Status, null));
        if ("NOT_EVALUATED".equals(bp4Status)) {
            return bp7Blocked("UNKNOWN", "BP4_NOT_EVALUATED",
                    steps, parameters, inputIds);
        }
        if (!"MET".equals(bp4Status) && !"NOT_MET".equals(bp4Status)) {
            return bp7Blocked("UNKNOWN", "BP4_EVALUATION_STATUS_INVALID",
                    steps, parameters, inputIds);
        }
        steps.add(bp7Step("SAME_CONTEXT_BP4_EVALUATED",
                "MET".equals(bp4Status) ? "TRUE" : "FALSE", inputIds));
        return new Bp7Decision("APPLICABLE",
                "MET".equals(bp4Status) ? "MET" : "NOT_MET",
                null, true, steps, parameters);
    }

    private boolean completeContext(JsonNode context) {
        JsonNode completeness = context.path("contextCompleteness");
        JsonNode transcript = context.path("transcript");
        JsonNode variant = context.path("variant");
        JsonNode queryAllele = variant.path("evidenceQueryAllele");
        return "COMPLETE".equals(completeness.path("status").asText())
                && completeness.path("missingFields").isArray()
                && completeness.path("missingFields").isEmpty()
                && context.path("gene").path("symbol").isTextual()
                && !context.path("gene").path("symbol").asText().isBlank()
                && queryAllele.path("assembly").isTextual()
                && queryAllele.path("reference").isTextual()
                && !queryAllele.path("reference").asText().isBlank()
                && queryAllele.path("alternate").isTextual()
                && !queryAllele.path("alternate").asText().isBlank()
                && variant.path("assemblyLineage").path("mappingStatus").isTextual()
                && transcript.path("accession").isTextual()
                && !transcript.path("accession").asText().isBlank()
                && transcript.path("version").isIntegralNumber()
                && transcript.path("version").asInt(-1) > 0;
    }

    private ObjectNode output(Bindings bindings,
                              Criterion criterion,
                              ObjectNode evidence,
                              String selectionId,
                              Decision decision) {
        String evaluationStatus = PP3.equals(criterion.id())
                ? decision.pp3Result() : decision.bp4Result();
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", "criterion-evaluation-v2");
        result.put("contextId", evidence.path("contextId").asText());
        result.put("canonicalizationProfile", C14N);
        result.put("criterionId", criterion.id());
        ObjectNode ruleBinding = result.putObject("ruleBinding");
        ruleBinding.put("ruleRegistryId", bindings.registryId());
        ruleBinding.put("ruleRegistrySha256", bindings.registryHash());
        ruleBinding.put("currentGeneralDesignation", criterion.designation());
        ArrayNode authorityValues = ruleBinding.putArray("authoritySourceIds");
        new TreeSet<>(criterion.authorities()).forEach(authorityValues::add);
        ruleBinding.put("cspecSelectionId", selectionId);

        ObjectNode applicability = result.putObject("criterionApplicability");
        applicability.put("status", decision.applicability());
        if (!"APPLICABLE".equals(decision.applicability())) {
            applicability.put("reasonCode", decision.reason());
        }
        ObjectNode evaluation = result.putObject("evaluationResult");
        evaluation.put("status", evaluationStatus);
        if ("NOT_EVALUATED".equals(evaluationStatus)) {
            evaluation.put("reasonCode", decision.reason());
        }
        result.put("baseStrength", "SUPPORTING");
        result.put("appliedStrength", "MET".equals(evaluationStatus)
                ? "SUPPORTING" : "NONE");
        result.putArray("evidenceIds").add(evidence.path("evidenceId").asText());

        ObjectNode trace = result.putObject("deterministicTrace");
        trace.put("executionStatus", decision.completed() ? "COMPLETED" : "NOT_RUN");
        trace.put("evaluatorId", EVALUATOR_ID);
        trace.put("evaluatorVersion", EVALUATOR_VERSION);
        trace.putArray("inputEvidenceIds").add(evidence.path("evidenceId").asText());
        trace.set("parameters", array(sortedParameters(decision.parameters())));
        trace.set("steps", array(decision.steps()));
        if (decision.completed()) {
            trace.put("algorithmId", "SPLICEAI_RAW_PP3_BP4_SUPPORTING_V1");
            trace.put("algorithmVersion", "1.0.0");
            trace.put("traceSha256", canonicalizer.canonicalSha256(trace));
        } else {
            trace.put("reasonCode", decision.reason());
        }
        result.set("conflict", summary("NONE", "conflictIds"));
        ObjectNode review = summary("NOT_REQUIRED", "reviewRecordIds");
        review.put("decision", "NONE");
        result.set("review", review);

        ObjectNode identity = result.deepCopy();
        identity.remove(List.of("evaluationId", "conflict", "review"));
        result.put("evaluationId", "evaluation-sha256-"
                + canonicalizer.canonicalSha256(identity));
        return result;
    }

    private ObjectNode bp7Output(Bp7Bindings bindings,
                                 ObjectNode spliceAiEvidence,
                                 ObjectNode vepEvidence,
                                 String selectionId,
                                 Bp7Decision decision) {
        List<String> inputIds = evidenceIds(spliceAiEvidence, vepEvidence);
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", "criterion-evaluation-v2");
        result.put("contextId", spliceAiEvidence.path("contextId").asText());
        result.put("canonicalizationProfile", C14N);
        result.put("criterionId", bindings.bp7().id());
        ObjectNode ruleBinding = result.putObject("ruleBinding");
        ruleBinding.put("ruleRegistryId", bindings.registryId());
        ruleBinding.put("ruleRegistrySha256", bindings.registryHash());
        ruleBinding.put("currentGeneralDesignation", bindings.bp7().designation());
        ArrayNode authorityValues = ruleBinding.putArray("authoritySourceIds");
        new TreeSet<>(bindings.bp7().authorities()).forEach(authorityValues::add);
        ruleBinding.put("cspecSelectionId", selectionId);

        ObjectNode applicability = result.putObject("criterionApplicability");
        applicability.put("status", decision.applicability());
        if (!"APPLICABLE".equals(decision.applicability())) {
            applicability.put("reasonCode", decision.reason());
        }
        ObjectNode evaluation = result.putObject("evaluationResult");
        evaluation.put("status", decision.result());
        if ("NOT_EVALUATED".equals(decision.result())) {
            evaluation.put("reasonCode", decision.reason());
        }
        result.put("baseStrength", "SUPPORTING");
        result.put("appliedStrength", "MET".equals(decision.result())
                ? "SUPPORTING" : "NONE");
        ArrayNode evidenceValues = result.putArray("evidenceIds");
        inputIds.forEach(evidenceValues::add);

        ObjectNode trace = result.putObject("deterministicTrace");
        trace.put("executionStatus", decision.completed() ? "COMPLETED" : "NOT_RUN");
        trace.put("evaluatorId", EVALUATOR_ID);
        trace.put("evaluatorVersion", "1.1.0-e3-c3");
        ArrayNode traceInputs = trace.putArray("inputEvidenceIds");
        inputIds.forEach(traceInputs::add);
        trace.set("parameters", array(sortedParameters(decision.parameters())));
        trace.set("steps", array(decision.steps()));
        if (decision.completed()) {
            trace.put("algorithmId", "SPLICEAI_BP4_VEP_POSITION_BP7_SUPPORTING_V1");
            trace.put("algorithmVersion", "1.0.0");
            trace.put("traceSha256", canonicalizer.canonicalSha256(trace));
        } else {
            trace.put("reasonCode", decision.reason());
        }
        result.set("conflict", summary("NONE", "conflictIds"));
        ObjectNode review = summary("NOT_REQUIRED", "reviewRecordIds");
        review.put("decision", "NONE");
        result.set("review", review);

        ObjectNode identity = result.deepCopy();
        identity.remove(List.of("evaluationId", "conflict", "review"));
        result.put("evaluationId", "evaluation-sha256-"
                + canonicalizer.canonicalSha256(identity));
        return result;
    }

    private Decision blocked(String applicability,
                             String reason,
                             List<ObjectNode> steps,
                             List<ObjectNode> parameters,
                             ObjectNode evidence) {
        List<ObjectNode> values = copy(steps);
        values.add(step(reason, "NOT_APPLICABLE".equals(applicability)
                ? "NOT_APPLICABLE" : "FALSE", evidence));
        return new Decision(applicability, "NOT_EVALUATED", "NOT_EVALUATED",
                reason, false, values, copy(parameters));
    }

    private Bp7Decision bp7Blocked(String applicability,
                                   String reason,
                                   List<ObjectNode> steps,
                                   List<ObjectNode> parameters,
                                   List<String> inputIds) {
        List<ObjectNode> values = copy(steps);
        values.add(bp7Step(reason, "NOT_APPLICABLE".equals(applicability)
                ? "NOT_APPLICABLE" : "FALSE", inputIds));
        return new Bp7Decision(applicability, "NOT_EVALUATED", reason,
                false, values, copy(parameters));
    }

    private Reading reading(ObjectNode evidence, String metricId) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode value : evidence.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) {
                found.add(value);
            }
        }
        if (found.isEmpty()) {
            return new Reading(null, "REQUIRED_MEASUREMENT_MISSING");
        }
        if (found.size() != 1) {
            return new Reading(null, "REQUIRED_MEASUREMENT_AMBIGUOUS");
        }
        if (!CALIBRATION_PROFILE_ID.equals(
                found.get(0).path("calibrationProfileId").asText())) {
            return new Reading(null, "PROFILE_CALIBRATION_MISMATCH");
        }
        return new Reading(found.get(0), null);
    }

    private Reading plainReading(ObjectNode evidence, String metricId) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode value : evidence.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) {
                found.add(value);
            }
        }
        if (found.isEmpty()) {
            return new Reading(null, "REQUIRED_VEP_MEASUREMENT_MISSING");
        }
        if (found.size() != 1) {
            return new Reading(null, "REQUIRED_VEP_MEASUREMENT_AMBIGUOUS");
        }
        return new Reading(found.get(0), null);
    }

    private boolean trueMeasurement(JsonNode measurement) {
        if (!"boolean".equals(measurement.path("unit").asText())) {
            return false;
        }
        JsonNode raw = measurement.path("rawValue");
        JsonNode normalized = measurement.path("normalizedValue");
        return raw.equals(normalized)
                && ((raw.isBoolean() && raw.booleanValue())
                || (raw.isTextual() && "true".equals(raw.asText())));
    }

    private String textMeasurement(JsonNode measurement, String unit) {
        JsonNode raw = measurement.path("rawValue");
        JsonNode normalized = measurement.path("normalizedValue");
        return unit.equals(measurement.path("unit").asText())
                && raw.isTextual() && raw.equals(normalized)
                ? raw.asText() : null;
    }

    private String plainTextMeasurement(JsonNode measurement, String unit) {
        JsonNode raw = measurement.path("rawValue");
        if (!raw.isTextual() || raw.asText().isBlank()) {
            return null;
        }
        if (unit != null && !unit.equals(measurement.path("unit").asText())) {
            return null;
        }
        JsonNode normalized = measurement.get("normalizedValue");
        return normalized == null || raw.equals(normalized) ? raw.asText() : null;
    }

    private Boolean booleanMeasurement(JsonNode measurement) {
        if (!"boolean".equals(measurement.path("unit").asText())) {
            return null;
        }
        String raw = plainTextMeasurement(measurement, "boolean");
        if ("true".equals(raw)) return Boolean.TRUE;
        if ("false".equals(raw)) return Boolean.FALSE;
        return null;
    }

    private Integer integerMeasurement(JsonNode measurement, String unit) {
        String raw = plainTextMeasurement(measurement, unit);
        if (raw == null) return null;
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BigDecimal score(JsonNode measurement) {
        if (!"score".equals(measurement.path("unit").asText())) {
            return null;
        }
        JsonNode rawNode = measurement.path("rawValue");
        JsonNode normalizedNode = measurement.path("normalizedValue");
        if (!rawNode.isTextual() || !normalizedNode.isTextual()) {
            return null;
        }
        BigDecimal value = decimal(rawNode);
        return value != null
                && value.compareTo(BigDecimal.ZERO) >= 0
                && value.compareTo(BigDecimal.ONE) <= 0
                && canonicalDecimal(value).equals(normalizedNode.asText()) ? value : null;
    }

    private boolean validPosition(JsonNode measurement) {
        if (!"base-offset".equals(measurement.path("unit").asText())) {
            return false;
        }
        JsonNode raw = measurement.path("rawValue");
        JsonNode normalized = measurement.path("normalizedValue");
        if (!raw.isTextual() || !normalized.isTextual() || !raw.equals(normalized)) {
            return false;
        }
        try {
            BigInteger value = new BigInteger(raw.asText());
            return value.abs().compareTo(BigInteger.valueOf(4999L)) <= 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private List<ObjectNode> baseParameters(Bindings bindings, ObjectNode evidence) {
        return new ArrayList<>(List.of(
                parameter("profile.id", PROFILE_ID, null),
                parameter("profile.version", PROFILE_VERSION, null),
                parameter("profile.sha256", bindings.profileHash(), "sha256"),
                parameter("resource.id", evidence.path("source").path("sourceId").asText(), null),
                parameter("resource.version",
                        evidence.path("source").path("resourceVersion").asText(), null),
                parameter("resource.sha256",
                        evidence.path("source").path("resourceSha256").asText(), "sha256")));
    }

    private List<ObjectNode> bp7BaseParameters(Bp7Bindings bindings) {
        return new ArrayList<>(List.of(
                parameter("profile.id", BP7_PROFILE_ID, null),
                parameter("profile.version", BP7_PROFILE_VERSION, null),
                parameter("profile.sha256", bindings.profileHash(), "sha256"),
                parameter("position.resourceId",
                        bindings.vepResource().path("resourceId").asText(), null),
                parameter("position.resourceVersion",
                        bindings.vepResource().path("resourceVersion").asText(), null),
                parameter("position.resourceSha256",
                        bindings.vepResource().path("identityFingerprintSha256").asText(),
                        "sha256"),
                parameter("threshold.synonymousMinimumEndDistance", "3", "base-offset"),
                parameter("threshold.intronicDonorMinimum", "7", "base-offset"),
                parameter("threshold.intronicAcceptorMaximum", "-21", "base-offset")));
    }

    private ObjectNode parameter(String name, String value, String unit) {
        ObjectNode result = mapper.createObjectNode().put("name", name).put("value", value);
        if (unit != null) {
            result.put("unit", unit);
        }
        return result;
    }

    private ObjectNode step(String code, String outcome, ObjectNode evidence) {
        ObjectNode result = mapper.createObjectNode().put("stepCode", code).put("outcome", outcome);
        result.putArray("inputEvidenceIds").add(evidence.path("evidenceId").asText());
        return result;
    }

    private ObjectNode bp7Step(String code, String outcome, List<String> inputIds) {
        ObjectNode result = mapper.createObjectNode().put("stepCode", code).put("outcome", outcome);
        ArrayNode values = result.putArray("inputEvidenceIds");
        inputIds.forEach(values::add);
        return result;
    }

    private ArrayNode array(List<ObjectNode> values) {
        ArrayNode result = mapper.createArrayNode();
        values.forEach(value -> result.add(value.deepCopy()));
        return result;
    }

    private ObjectNode summary(String status, String idsField) {
        ObjectNode result = mapper.createObjectNode().put("status", status);
        result.putArray(idsField);
        return result;
    }

    private Set<String> consequenceTerms(String value) {
        Set<String> terms = new LinkedHashSet<>();
        for (String term : value.split("&", -1)) {
            String normalized = term.trim();
            if (!normalized.isEmpty()) {
                terms.add(normalized);
            }
        }
        return terms;
    }

    private List<String> evidenceIds(ObjectNode... evidence) {
        Set<String> values = new TreeSet<>();
        for (ObjectNode item : evidence) {
            if (item != null && item.path("evidenceId").isTextual()
                    && !item.path("evidenceId").asText().isBlank()) {
                values.add(item.path("evidenceId").asText());
            }
        }
        return new ArrayList<>(values);
    }

    private boolean containsText(JsonNode values, String expected) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) {
            if (value.isTextual() && expected.equals(value.asText())) return true;
        }
        return false;
    }

    private static ObjectNode readObject(ObjectMapper mapper,
                                         String resource,
                                         String expectedByteHash) {
        byte[] bytes;
        try (InputStream input = SpliceAiCriterionEvaluator.class
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw invalid("classpath resource is missing: " + resource);
            }
            bytes = input.readAllBytes();
        } catch (IOException failure) {
            throw invalid("classpath resource cannot be read: " + resource);
        }
        if (expectedByteHash != null && !expectedByteHash.equals(sha256(bytes))) {
            throw invalid("classpath resource byte hash is not pinned: " + resource);
        }
        try {
            JsonNode value = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            if (!value.isObject()) {
                throw invalid("classpath resource is not an object: " + resource);
            }
            return (ObjectNode) value;
        } catch (IOException failure) {
            throw invalid("classpath resource is invalid JSON: " + resource);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static BigDecimal decimal(JsonNode value) {
        if (!value.isTextual()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String canonicalDecimal(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.signum() == 0 ? "0" : stripped.toPlainString();
    }

    private static JsonNode find(JsonNode values, String field, String expected) {
        if (values.isArray()) {
            for (JsonNode value : values) {
                if (expected.equals(value.path(field).asText())) {
                    return value;
                }
            }
        }
        return MissingNode.getInstance();
    }

    private static Set<String> authorityIds(JsonNode bindings) {
        Set<String> result = new LinkedHashSet<>();
        if (bindings.isArray()) {
            for (JsonNode binding : bindings) {
                String value = binding.path("authoritySourceId").asText();
                if (!value.isBlank()) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private static Set<String> textSet(JsonNode values) {
        Set<String> result = new LinkedHashSet<>();
        if (values.isArray()) {
            for (JsonNode value : values) {
                if (value.isTextual() && !value.asText().isBlank()) {
                    result.add(value.asText());
                }
            }
        }
        return result;
    }

    private static List<ObjectNode> copy(List<ObjectNode> values) {
        List<ObjectNode> result = new ArrayList<>();
        values.forEach(value -> result.add(value.deepCopy()));
        return result;
    }

    private static List<ObjectNode> sortedParameters(List<ObjectNode> values) {
        List<ObjectNode> result = copy(values);
        result.sort(Comparator.comparing((ObjectNode value) -> value.path("name").asText())
                .thenComparing(value -> value.path("unit").asText())
                .thenComparing(value -> value.path("value").asText()));
        return result;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid SpliceAI E3-C2 profile: " + message);
    }

    private record Bindings(String profileHash,
                            String registryId,
                            String registryHash,
                            JsonNode resource,
                            BigDecimal benignMaximum,
                            BigDecimal pathogenicMinimum,
                            Set<String> allowedTerms,
                            Set<String> excludedTerms,
                            Criterion pp3,
                            Criterion bp4,
                            String issue) {
    }

    private record Bp7Bindings(String profileHash,
                               String registryId,
                               String registryHash,
                               JsonNode vepResource,
                               int minimumEndDistance,
                               int donorMinimum,
                               int acceptorMaximum,
                               Criterion bp7,
                               String issue) {
    }

    private record Criterion(String id,
                             String designation,
                             String strength,
                             Set<String> authorities,
                             JsonNode registered) {
    }

    private record Reading(JsonNode node, String reason) {
    }

    private record Decision(String applicability,
                            String pp3Result,
                            String bp4Result,
                            String reason,
                            boolean completed,
                            List<ObjectNode> steps,
                            List<ObjectNode> parameters) {
    }

    private record Bp7Decision(String applicability,
                               String result,
                               String reason,
                               boolean completed,
                               List<ObjectNode> steps,
                               List<ObjectNode> parameters) {
    }
}
