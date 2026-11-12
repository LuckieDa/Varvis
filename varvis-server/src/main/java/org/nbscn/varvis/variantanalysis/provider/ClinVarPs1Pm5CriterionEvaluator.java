package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic E3-K1B ClinVar missense PS1/PM5 evaluator. */
public final class ClinVarPs1Pm5CriterionEvaluator {

    public static final String EVALUATOR_ID = "clinvar-ps1-pm5-criterion-evaluator";
    public static final String EVALUATOR_VERSION = "1.0.0-e3-k1b";
    public static final String PROFILE_ID = "CLINVAR_MISSENSE_PS1_PM5";
    public static final String PROFILE_VERSION = "1.0.0";

    private static final String PROFILE_RESOURCE =
            "/acmg/clinvar-missense-ps1-pm5-profile-v1.json";
    private static final String REGISTRY_RESOURCE =
            "/acmg/rule-registry-v2/acmg-rule-registry-e3-k1b-2026-09-04.json";
    private static final String PROFILE_CANONICAL_SHA256 =
            "69a886bab3933796b4bc14b9b60960dd3da65cecce206b9244cbf2ba9674d79e";
    private static final String REGISTRY_BYTE_SHA256 =
            "4f2e96746541bf6269a9d06a919d3eb0759ee3bf079f06f4868a23561514e9ed";
    private static final String REGISTRY_CANONICAL_SHA256 =
            "a9a5485463ace52e6a21ded2e9a129a016ddec2aa3ebb5c9086c7987c5e46c1f";
    private static final String REGISTRY_ID = "acmg-rule-registry-e3-k1b-2026-09-04";
    private static final String C14N = "varvis-json-c14n-v1";
    private static final String PS1 = "ACMG_AMP_2015:PS1";
    private static final String PM5 = "ACMG_AMP_2015:PM5";
    private static final Pattern SELECTION_ID =
            Pattern.compile("^cspec-selection-sha256-[0-9a-f]{64}$");
    private static final Pattern MONDO =
            Pattern.compile("^MONDO:(?:MONDO:)?([0-9]{7})$");
    private static final Set<String> HIGH_REVIEW = Set.of(
            "reviewed by expert panel", "practice guideline");
    private static final Set<String> PATHOGENIC_DIRECTION = Set.of(
            "Pathogenic", "Likely pathogenic", "Pathogenic/Likely pathogenic");
    private static final Set<String> BENIGN_DIRECTION = Set.of(
            "Benign", "Likely benign", "Benign/Likely benign");

    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter canonicalizer;

    public ClinVarPs1Pm5CriterionEvaluator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.canonicalizer = new RuntimeEvidenceV2Adapter(mapper);
    }

    public static ObjectNode loadProfile(ObjectMapper mapper) {
        ObjectNode profile = readObject(mapper, PROFILE_RESOURCE, null);
        if (!PROFILE_CANONICAL_SHA256.equals(
                new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(profile))) {
            throw invalid("profile canonical hash is not pinned");
        }
        return profile;
    }

    public static ObjectNode loadSuccessorRegistry(ObjectMapper mapper) {
        ObjectNode registry = readObject(mapper, REGISTRY_RESOURCE, REGISTRY_BYTE_SHA256);
        if (!REGISTRY_CANONICAL_SHA256.equals(
                new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(registry))) {
            throw invalid("successor registry canonical hash is not pinned");
        }
        return registry;
    }

    public List<ObjectNode> evaluate(JsonNode evidenceContext,
                                     List<ObjectNode> comparatorAtomics,
                                     String cspecSelectionId) {
        return evaluate(evidenceContext, comparatorAtomics, loadProfile(mapper),
                loadSuccessorRegistry(mapper), cspecSelectionId);
    }

    public List<ObjectNode> evaluate(JsonNode evidenceContext,
                                     List<ObjectNode> comparatorAtomics,
                                     ObjectNode profile,
                                     ObjectNode successorRegistry,
                                     String cspecSelectionId) {
        Objects.requireNonNull(evidenceContext, "evidenceContext");
        Objects.requireNonNull(comparatorAtomics, "comparatorAtomics");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(successorRegistry, "successorRegistry");
        if (cspecSelectionId == null || !SELECTION_ID.matcher(cspecSelectionId).matches()) {
            throw new IllegalArgumentException(
                    "cspecSelectionId must be an opaque canonical selection ID");
        }
        Bindings bindings = bindings(profile, successorRegistry);
        Analysis analysis = analyze(bindings, evidenceContext, comparatorAtomics);
        return List.of(
                output(bindings, bindings.ps1(), analysis, cspecSelectionId),
                output(bindings, bindings.pm5(), analysis, cspecSelectionId));
    }

    /** Business successor: preserves the frozen comparator identity checks and v2 output contract.
     * Audited reference/splicing decisions are supplied only by the dedicated runtime, not HTTP input. */
    List<ObjectNode> evaluateBusiness(JsonNode context, List<ObjectNode> atomics,
                                     String selectionId, ObjectNode policy, ObjectNode decisions) {
        return evaluateBusiness(context, atomics, selectionId, policy, decisions, false);
    }

    List<ObjectNode> evaluateHnf1aBusiness(JsonNode context, List<ObjectNode> atomics,
                                          String selectionId, ObjectNode policy, ObjectNode decisions) {
        return evaluateBusiness(context, atomics, selectionId, policy, decisions, true);
    }

    /** Reviewed-policy successor; legacy profiles, algorithms and saved identities remain unchanged. */
    ObjectNode evaluateReviewedPolicy(JsonNode context, List<ObjectNode> atomics, String criterionId,
                                      JsonNode policy, JsonNode selection, ObjectNode decision,
                                      JsonNode inputTrace, JsonNode registry) {
        if (!Set.of(PS1, PM5).contains(criterionId)) throw invalid("unsupported reviewed criterion");
        Bindings bound = bindings(loadProfile(mapper), loadSuccessorRegistry(mapper));
        Criterion criterion = PS1.equals(criterionId) ? bound.ps1() : bound.pm5();
        List<ObjectNode> sorted = uniqueSorted(atomics);
        List<String> ids = evidenceIds(sorted);
        List<String> accepted = new ArrayList<>();
        for (JsonNode id : decision.path("evidenceIds")) if (ids.contains(id.asText())) accepted.add(id.asText());
        String status = decision.path("status").asText();
        String reason = decision.path("reasonCode").asText();
        String issue = businessInputIssue(context, sorted);
        for (ObjectNode atomic : sorted) if (accepted.contains(atomic.path("evidenceId").asText())) {
            String referenceIssue = businessReferenceIssue(context, atomic);
            if (referenceIssue != null) issue = referenceIssue;
        }
        String strength = decision.path("appliedStrength").asText();
        if ("MET".equals(status)) {
            boolean contributorsValid = PM5.equals(criterionId) && "STRONG".equals(strength)
                    ? accepted.size() == 2 && MissenseComparisonRuntime.validCountedDecision(mapper, policy, decision, inputTrace, sorted)
                    : accepted.size() == 1;
            if (!Set.of("SUPPORTING", "MODERATE", "STRONG").contains(strength) || !contributorsValid)
                issue = "MISSENSE_REVIEWED_DECISION_INVALID";
        }
        List<ObjectNode> parameters = new ArrayList<>(baseParameters(bound));
        parameters.add(parameter("reviewed.policy.sha256", canonicalizer.canonicalSha256(policy)));
        parameters.add(parameter("reviewed.selection.sha256", canonicalizer.canonicalSha256(selection)));
        parameters.add(parameter("reviewed.inputs.sha256", canonicalizer.canonicalSha256(inputTrace)));
        parameters.add(parameter("reviewed.decision.sha256", canonicalizer.canonicalSha256(decision)));
        parameters.sort(Comparator.comparing(value -> value.path("name").asText()));
        Analysis analysis = issue != null || !Set.of("MET", "NOT_MET").contains(status)
                ? Analysis.blocked(context.path("contextId").asText(), "UNKNOWN", issue == null ? reason : issue, ids, parameters)
                : Analysis.completed(context.path("contextId").asText(), ids,
                    PS1.equals(criterionId) && "MET".equals(status) ? accepted : List.of(),
                    PM5.equals(criterionId) && "MET".equals(status) ? accepted : List.of(), parameters);
        ObjectNode result = output(bound, criterion, analysis, selection.path("selectionId").asText());
        result.set("evidenceIds", arrayStrings(ids));
        ((ObjectNode) result.path("ruleBinding")).put("ruleRegistryId", registry.path("registryId").asText())
                .put("ruleRegistrySha256", policy.at("/registryBinding/registrySha256").asText());
        if (analysis.completed() && "MET".equals(status)) result.put("appliedStrength", strength);
        ObjectNode trace = (ObjectNode) result.path("deterministicTrace");
        trace.put("evaluatorVersion", "4.0.3-reviewed-policy").put("algorithmId", policy.path("policyId").asText())
                .put("algorithmVersion", policy.path("policyVersion").asText());
        trace.set("steps", mapper.createArrayNode().add(step(issue == null ? reason : issue,
                analysis.completed() ? "MET".equals(status) ? "TRUE" : "FALSE" : "UNKNOWN", ids)));
        trace.remove("traceSha256"); trace.put("traceSha256", canonicalizer.canonicalSha256(trace));
        ObjectNode projection = result.deepCopy(); projection.remove(List.of("evaluationId", "conflict", "review"));
        result.put("evaluationId", "evaluation-sha256-" + canonicalizer.canonicalSha256(projection));
        return result;
    }

    private List<ObjectNode> evaluateBusiness(JsonNode context, List<ObjectNode> atomics,
                                              String selectionId, ObjectNode policy, ObjectNode decisions,
                                              boolean hnf1a) {
        Bindings bound = bindings(loadProfile(mapper), loadSuccessorRegistry(mapper));
        List<ObjectNode> sorted = uniqueSorted(atomics);
        List<String> ids = evidenceIds(sorted);
        String issue = businessInputIssue(context, sorted);
        List<ObjectNode> parameters = baseParameters(bound);
        parameters.add(parameter("business.profile.sha256", canonicalizer.canonicalSha256(policy)));
        parameters.add(parameter("business.science.sha256", canonicalizer.canonicalSha256(decisions)));
        parameters.sort(Comparator.comparing(value -> value.path("name").asText()));
        List<ObjectNode> results = new ArrayList<>();
        for (Criterion criterion : List.of(bound.ps1(), bound.pm5())) {
            JsonNode decision = decisions.path(criterion.designation());
            String status = decision.path("status").asText("NOT_EVALUATED");
            List<String> accepted = new ArrayList<>();
            for (JsonNode id : decision.path("evidenceIds")) if (ids.contains(id.asText())) accepted.add(id.asText());
            String criterionIssue = issue;
            String strength = decision.path("appliedStrength").asText();
            if (hnf1a && "MET".equals(status) && !("PS1".equals(criterion.designation())
                    ? Set.of("STRONG", "MODERATE") : Set.of("MODERATE", "SUPPORTING")).contains(strength))
                criterionIssue = "HNF1A_COMPARISON_STRENGTH_INVALID";
            if (criterionIssue == null && "MET".equals(status)) {
                for (ObjectNode atomic : sorted) if (accepted.contains(atomic.path("evidenceId").asText())) {
                    criterionIssue = businessReferenceIssue(context, atomic);
                    if (criterionIssue != null) break;
                }
            }
            String reason = criterionIssue == null ? decision.path("reasonCode").asText("SCIENTIFIC_INPUT_MISSING") : criterionIssue;
            Analysis analysis;
            if (criterionIssue != null || "NOT_EVALUATED".equals(status) || "MET".equals(status) && accepted.isEmpty()) {
                analysis = Analysis.blocked(context.path("contextId").asText(),
                        criterionIssue == null ? decision.path("applicability").asText("UNKNOWN") : "UNKNOWN", reason, ids, parameters);
            } else {
                analysis = Analysis.completed(context.path("contextId").asText(), ids,
                        PS1.equals(criterion.id()) && "MET".equals(status) ? accepted : List.of(),
                        PM5.equals(criterion.id()) && "MET".equals(status) ? accepted : List.of(), parameters);
            }
            ObjectNode result = output(bound, criterion, analysis, selectionId);
            // All compared submissions are inputs to source-identity and conflict screening.
            // Audited positive references remain the separate scientificDecisions subset.
            result.set("evidenceIds", arrayStrings(ids));
            String registryResource = hnf1a ? Hnf1aPs1Pm5Runtime.REGISTRY_RESOURCE : ClinVarMissenseBusinessArtifacts.REGISTRY_RESOURCE;
            ObjectNode registry = ClinVarMissenseBusinessRuntime.read(mapper, registryResource);
            ((ObjectNode) result.path("ruleBinding")).put("ruleRegistryId", registry.path("registryId").asText())
                    .put("ruleRegistrySha256", ClinVarMissenseBusinessArtifacts.resourceSha(registryResource));
            ObjectNode trace = (ObjectNode) result.path("deterministicTrace");
            trace.put("evaluatorVersion", "2.1.0-braf-business-rna-review");
            trace.put("algorithmId", "CLINVAR_BRAF_RASOPATHY_PS1_PM5_V2").put("algorithmVersion", "2.0.0");
            if (hnf1a) {
                trace.put("evaluatorVersion", "3.0.1-hnf1a-business");
                trace.put("algorithmId", "HNF1A_GN017_3_1_PS1_PM5").put("algorithmVersion", "1.0.1");
                if (analysis.completed() && "MET".equals(status)) result.put("appliedStrength", strength);
            }
            if (analysis.completed()) {
                trace.set("steps", mapper.createArrayNode().add(step(reason, "MET".equals(status) ? "TRUE" : "FALSE", ids)));
                ObjectNode traceProjection = trace.deepCopy(); traceProjection.remove("traceSha256");
                trace.put("traceSha256", canonicalizer.canonicalSha256(traceProjection));
            }
            if (hnf1a) {
                ObjectNode traceProjection = trace.deepCopy(); traceProjection.remove("traceSha256");
                trace.put("traceSha256", canonicalizer.canonicalSha256(traceProjection));
            }
            ObjectNode projection = result.deepCopy(); projection.remove(List.of("evaluationId", "conflict", "review"));
            result.put("evaluationId", "evaluation-sha256-" + canonicalizer.canonicalSha256(projection));
            results.add(result);
        }
        return List.copyOf(results);
    }

    private ArrayNode arrayStrings(List<String> values) {
        ArrayNode result = mapper.createArrayNode(); values.forEach(result::add); return result;
    }

    String businessInputIssue(JsonNode context, List<ObjectNode> atomics) {
        String issue = contextIssue(context);
        if (issue != null) return issue;
        Bindings bound = bindings(loadProfile(mapper), loadSuccessorRegistry(mapper));
        for (ObjectNode atomic : atomics) {
            issue = atomicBindingIssue(bound, context, atomic);
            if (issue != null) return issue;
            String execution = atomic.at("/resourceExecution/status").asText();
            String availability = atomic.at("/resourceAvailability/status").asText();
            String query = atomic.at("/queryOutcome/status").asText();
            if (!"SUCCEEDED".equals(execution) || !"AVAILABLE".equals(availability)
                    || !("HIT".equals(query) || "NO_RECORD".equals(query))) return resourceReason(execution, availability, query);
        }
        return null;
    }

    /** Completeness belongs to an audited reference, not every incidental bounded-query submission. */
    String businessReferenceIssue(JsonNode context, ObjectNode atomic) {
        return assertion(atomic, context.at("/disease/mondoId").asText()).issue();
    }

    private Bindings bindings(ObjectNode profile, ObjectNode registry) {
        String profileHash = canonicalizer.canonicalSha256(profile);
        String issue = PROFILE_CANONICAL_SHA256.equals(profileHash)
                ? null : "PROFILE_HASH_MISMATCH";
        JsonNode activation = profile.path("activation");
        if (issue == null && (!PROFILE_ID.equals(profile.path("profileId").asText())
                || !PROFILE_VERSION.equals(profile.path("profileVersion").asText())
                || !activation.path("runtimeBound").asBoolean(false)
                || !activation.path("evaluationEnabled").asBoolean(false)
                || activation.path("patientFlowEnabled").asBoolean(true)
                || activation.path("classificationEnabled").asBoolean(true))) {
            issue = "PROFILE_ACTIVATION_MISMATCH";
        }

        JsonNode registryBinding = profile.path("registryBinding");
        if (issue == null && (!REGISTRY_ID.equals(registry.path("registryId").asText())
                || !REGISTRY_ID.equals(registryBinding.path("registryId").asText())
                || !REGISTRY_BYTE_SHA256.equals(registryBinding.path("registrySha256").asText())
                || !REGISTRY_CANONICAL_SHA256.equals(canonicalizer.canonicalSha256(registry)))) {
            issue = "RULE_REGISTRY_BINDING_MISMATCH";
        }

        Map<String, JsonNode> resources = resources(profile);
        if (issue == null && resources.size() != 3) {
            issue = "RESOURCE_PROFILE_BINDING_MISMATCH";
        }
        Criterion ps1 = criterion(profile, registry, PS1);
        Criterion pm5 = criterion(profile, registry, PM5);
        if (issue == null && (!criterionBound(ps1) || !criterionBound(pm5))) {
            issue = "RULE_REGISTRY_BINDING_MISMATCH";
        }
        if (issue != null) {
            ObjectNode frozenProfile = loadProfile(mapper);
            ObjectNode frozenRegistry = loadSuccessorRegistry(mapper);
            resources = resources(frozenProfile);
            ps1 = criterion(frozenProfile, frozenRegistry, PS1);
            pm5 = criterion(frozenProfile, frozenRegistry, PM5);
        }
        return new Bindings(profileHash, REGISTRY_ID, REGISTRY_BYTE_SHA256,
                Collections.unmodifiableMap(resources), ps1, pm5, issue);
    }

    private Map<String, JsonNode> resources(JsonNode profile) {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        for (JsonNode resource : profile.path("resources")) {
            values.put(resource.path("resourceId").asText(), resource);
        }
        return values;
    }

    private Criterion criterion(ObjectNode profile, ObjectNode registry, String id) {
        JsonNode declared = find(profile.path("criteria"), "criterionId", id);
        JsonNode registered = find(registry.path("criteria"), "criterionId", id);
        return new Criterion(id, declared.path("currentDesignation").asText(),
                declared.path("baseStrength").asText(),
                textSet(declared.path("authoritySourceIds")), registered);
    }

    private boolean criterionBound(Criterion criterion) {
        JsonNode current = criterion.registered().path("currentGeneral");
        return criterion.registered().isObject()
                && "V2_IMPLEMENTED".equals(
                criterion.registered().path("implementationStatus").asText())
                && criterion.designation().equals(current.path("designation").asText())
                && criterion.strength().equals(current.path("strength").asText())
                && criterion.authorities().equals(textSet(current.path("authoritySourceIds")));
    }

    private Analysis analyze(Bindings bindings,
                             JsonNode context,
                             List<ObjectNode> inputAtomics) {
        List<ObjectNode> atomics = uniqueSorted(inputAtomics);
        List<String> allIds = evidenceIds(atomics);
        List<ObjectNode> parameters = baseParameters(bindings);
        String contextId = context.path("contextId").asText();
        if (bindings.issue() != null) {
            return Analysis.blocked(contextId, "UNKNOWN", bindings.issue(), allIds, parameters);
        }
        String contextIssue = contextIssue(context);
        if (contextIssue != null) {
            String applicability = contextIssue.startsWith("OUTSIDE_")
                    ? "NOT_APPLICABLE" : "UNKNOWN";
            return Analysis.blocked(contextId, applicability, contextIssue, allIds, parameters);
        }
        if (atomics.isEmpty()) {
            return Analysis.blocked(contextId, "UNKNOWN", "COMPARATOR_ATOMIC_EVIDENCE_MISSING",
                    allIds, parameters);
        }

        String contextMondo = context.path("disease").path("mondoId").asText();
        String contextGene = context.path("gene").path("symbol").asText();
        Set<String> ps1Ids = new TreeSet<>();
        Set<String> pm5Ids = new TreeSet<>();
        Map<String, DirectionEvidence> directionsByComparator = new LinkedHashMap<>();
        boolean exactMondoSeen = false;
        boolean unresolvedConditionSeen = false;

        for (ObjectNode atomic : atomics) {
            String bindingIssue = atomicBindingIssue(bindings, context, atomic);
            if (bindingIssue != null) {
                return Analysis.blocked(contextId, "UNKNOWN", bindingIssue, allIds, parameters);
            }
            String applicability = atomic.path("resourceApplicability").path("status").asText();
            if ("NOT_APPLICABLE".equals(applicability)) {
                return Analysis.blocked(contextId, "NOT_APPLICABLE", "OUTSIDE_PROFILE_RESOURCE_APPLICABILITY",
                        allIds, parameters);
            }
            String execution = atomic.path("resourceExecution").path("status").asText();
            String availability = atomic.path("resourceAvailability").path("status").asText();
            String query = atomic.path("queryOutcome").path("status").asText();
            if (!"SUCCEEDED".equals(execution) || !"AVAILABLE".equals(availability)
                    || !("HIT".equals(query) || "NO_RECORD".equals(query))) {
                return Analysis.blocked(contextId, "UNKNOWN", resourceReason(execution, availability, query),
                        allIds, parameters);
            }
            if ("NO_RECORD".equals(query)) {
                continue;
            }

            String assertionMondo = mondo(
                    text(atomic, "clinvar.assertion.condition.submittedRaw"),
                    text(atomic, "clinvar.assertion.condition.reportedRaw"));
            if (assertionMondo == null) {
                unresolvedConditionSeen = true;
                continue;
            }
            if (!contextMondo.equals(assertionMondo)) {
                continue;
            }
            exactMondoSeen = true;

            String targetConsequence = text(atomic, "vep.target.consequence");
            String comparatorConsequence = text(atomic, "vep.comparator.consequence");
            if (targetConsequence == null || comparatorConsequence == null) {
                return Analysis.blocked(contextId, "UNKNOWN", "MISSENSE_ROUTE_MISSING",
                        allIds, parameters);
            }
            if (!missenseOnly(targetConsequence) || !missenseOnly(comparatorConsequence)) {
                return Analysis.blocked(contextId, "NOT_APPLICABLE", "OUTSIDE_MISSENSE_NON_SPLICING_ROUTE",
                        allIds, parameters);
            }

            Assertion assertion = assertion(atomic, assertionMondo);
            if (assertion.issue() != null) {
                return Analysis.blocked(contextId, "UNKNOWN", assertion.issue(), allIds, parameters);
            }
            if (!contextGene.equals(assertion.comparatorGene())) {
                continue;
            }
            if (assertion.highReview()) {
                DirectionEvidence directions = directionsByComparator.computeIfAbsent(
                        assertion.mondoId() + "|" + assertion.comparatorKey(),
                        ignored -> new DirectionEvidence());
                if (PATHOGENIC_DIRECTION.contains(assertion.classification())) {
                    directions.pathogenicIds().add(assertion.evidenceId());
                }
                if (BENIGN_DIRECTION.contains(assertion.classification())) {
                    directions.benignIds().add(assertion.evidenceId());
                }
            }
            if (!"Pathogenic".equals(assertion.classification())
                    || !assertion.highReview()) {
                continue;
            }
            if ("SAME_AMINO_ACID_CHANGE".equals(assertion.relation())) {
                ps1Ids.add(assertion.evidenceId());
            }
            if ("SAME_RESIDUE_DIFFERENT_AMINO_ACID".equals(assertion.relation())) {
                pm5Ids.add(assertion.evidenceId());
            }
        }

        if (!exactMondoSeen && unresolvedConditionSeen) {
            return Analysis.blocked(contextId, "UNKNOWN",
                    "ASSERTION_CONDITION_NOT_RELIABLY_RESOLVED", allIds, parameters);
        }
        Set<String> participants = new TreeSet<>();
        for (DirectionEvidence directions : directionsByComparator.values()) {
            if (!directions.pathogenicIds().isEmpty() && !directions.benignIds().isEmpty()) {
                participants.addAll(directions.pathogenicIds());
                participants.addAll(directions.benignIds());
            }
        }
        if (!participants.isEmpty()) {
            return Analysis.conflict(contextId, allIds, new ArrayList<>(participants), parameters);
        }
        return Analysis.completed(contextId, allIds, new ArrayList<>(ps1Ids),
                new ArrayList<>(pm5Ids), parameters);
    }

    private String contextIssue(JsonNode context) {
        if (!"evidence-context-v2".equals(context.path("schemaVersion").asText())
                || !C14N.equals(context.path("canonicalizationProfile").asText())
                || !"COMPLETE".equals(
                context.path("contextCompleteness").path("status").asText())
                || !context.path("contextCompleteness").path("missingFields").isArray()
                || !context.path("contextCompleteness").path("missingFields").isEmpty()
                || context.path("gene").path("symbol").asText().isBlank()
                || !context.path("disease").path("mondoId").asText().matches("MONDO:[0-9]{7}")
                || context.path("transcript").path("accession").asText().isBlank()
                || context.path("transcript").path("version").asInt(-1) < 1) {
            return "CONTEXT_INCOMPLETE";
        }
        JsonNode variant = context.path("variant");
        JsonNode allele = variant.path("evidenceQueryAllele");
        if (!"SNV".equals(variant.path("variantType").asText())
                || !"GRCh38".equals(allele.path("assembly").asText())
                || !"NATIVE".equals(
                variant.path("assemblyLineage").path("mappingStatus").asText())
                || allele.path("reference").asText().length() != 1
                || allele.path("alternate").asText().length() != 1) {
            return "OUTSIDE_NATIVE_GRCH38_SIMPLE_SNV";
        }
        return null;
    }

    private String atomicBindingIssue(Bindings bindings,
                                      JsonNode context,
                                      ObjectNode atomic) {
        if (!"atomic-evidence-v2".equals(atomic.path("schemaVersion").asText())
                || !C14N.equals(atomic.path("canonicalizationProfile").asText())
                || !context.path("contextId").asText().equals(atomic.path("contextId").asText())
                || !"CLINICAL_DATABASE".equals(atomic.path("evidenceType").asText())
                || !RuntimeEvidenceV2Adapter.PRODUCER_ID.equals(
                atomic.path("provenance").path("producerId").asText())) {
            return "ATOMIC_EVIDENCE_BINDING_MISMATCH";
        }
        JsonNode sidecar = bindings.resources().get(
                "CLINVAR_COMPARATOR_SIDECAR_2026_03_05_GRCH38");
        JsonNode source = atomic.path("source");
        if (sidecar == null
                || !sidecar.path("resourceId").asText().equals(source.path("sourceId").asText())
                || !sidecar.path("resourceVersion").asText()
                .equals(source.path("resourceVersion").asText())
                || !sidecar.path("identityFingerprintSha256").asText()
                .equals(source.path("resourceSha256").asText())) {
            return "RESOURCE_IDENTITY_MISMATCH";
        }
        for (JsonNode resource : bindings.resources().values()) {
            if (!hasResourceLocator(source.path("sourceLocators"), resource)) {
                return "RESOURCE_IDENTITY_MISMATCH";
            }
        }
        return null;
    }

    private boolean hasResourceLocator(JsonNode locators, JsonNode resource) {
        String expectedValue = "resource-identity:" + resource.path("resourceId").asText()
                + "@" + resource.path("assembly").asText();
        String expectedHash = resource.path("identityFingerprintSha256").asText();
        for (JsonNode locator : locators) {
            if (expectedValue.equals(locator.path("locatorValue").asText())
                    && expectedHash.equals(locator.path("artifactSha256").asText())) {
                return true;
            }
        }
        return false;
    }

    private Assertion assertion(ObjectNode atomic, String assertionMondo) {
        List<String> required = List.of(
                "clinvar.comparator.assembly", "clinvar.comparator.contig",
                "clinvar.comparator.position", "clinvar.comparator.reference",
                "clinvar.comparator.alternate",
                "clinvar.comparator.geneSymbols", "vep.proteinRelation",
                "vep.target.transcriptId", "vep.target.proteinPosition",
                "vep.target.referenceAminoAcid", "vep.target.alternateAminoAcid",
                "vep.comparator.transcriptId", "vep.comparator.proteinPosition",
                "vep.comparator.referenceAminoAcid", "vep.comparator.alternateAminoAcid",
                "clinvar.assertion.scv", "clinvar.assertion.classification",
                "clinvar.assertion.reviewStatus", "clinvar.assertion.dateLastEvaluated",
                "clinvar.assertion.source.logicalName", "clinvar.assertion.source.line");
        for (String metric : required) {
            if (text(atomic, metric) == null) {
                return Assertion.invalid("ASSERTION_REQUIRED_FIELD_MISSING");
            }
        }
        String recordGenes = text(atomic, "clinvar.comparator.geneSymbols");
        String assertionGene = text(atomic, "clinvar.assertion.geneSymbol");
        String comparatorGene = assertionGene == null ? recordGenes : assertionGene;
        if (recordGenes == null || comparatorGene == null || recordGenes.contains("|")) {
            return Assertion.invalid("ASSERTION_GENE_IDENTITY_MISSING_OR_AMBIGUOUS");
        }
        if (assertionGene != null && !recordGenes.equals(assertionGene)) {
            return Assertion.invalid("ASSERTION_GENE_IDENTITY_CONFLICT");
        }
        String variationId = text(atomic, "clinvar.comparator.variationId");
        String comparatorKey = variationId == null
                ? "ALLELE:" + text(atomic, "clinvar.comparator.assembly") + ":"
                + text(atomic, "clinvar.comparator.contig") + ":"
                + text(atomic, "clinvar.comparator.position") + ":"
                + text(atomic, "clinvar.comparator.reference") + ":"
                + text(atomic, "clinvar.comparator.alternate")
                : "VARIATION_ID:" + variationId;
        String review = text(atomic, "clinvar.assertion.reviewStatus");
        return new Assertion(atomic.path("evidenceId").asText(), assertionMondo, comparatorGene,
                text(atomic, "clinvar.assertion.classification"),
                HIGH_REVIEW.contains(review), text(atomic, "vep.proteinRelation"),
                comparatorKey, null);
    }

    private String mondo(String... rawValues) {
        Set<String> values = new LinkedHashSet<>();
        for (String raw : rawValues) {
            if (raw == null) continue;
            Matcher matcher = MONDO.matcher(raw);
            if (matcher.matches()) values.add("MONDO:" + matcher.group(1));
        }
        return values.size() == 1 ? values.iterator().next() : null;
    }

    private boolean missenseOnly(String consequence) {
        boolean missense = false;
        for (String term : consequence.split("[&,]")) {
            String normalized = term.trim();
            if ("missense_variant".equals(normalized)) missense = true;
            if (normalized.startsWith("splice_") || "intron_variant".equals(normalized)
                    || "synonymous_variant".equals(normalized)) return false;
        }
        return missense;
    }

    private String resourceReason(String execution, String availability, String query) {
        if ("TIMEOUT".equals(execution)) return "COMPARATOR_RESOURCE_TIMEOUT";
        if ("FAILED".equals(execution)) return "COMPARATOR_RESOURCE_FAILED";
        if (!"AVAILABLE".equals(availability)) return "COMPARATOR_RESOURCE_UNAVAILABLE";
        return "COMPARATOR_QUERY_NOT_COMPLETED";
    }

    private ObjectNode output(Bindings bindings,
                              Criterion criterion,
                              Analysis analysis,
                              String selectionId) {
        List<String> eligible = PS1.equals(criterion.id())
                ? analysis.ps1EvidenceIds() : analysis.pm5EvidenceIds();
        String resultStatus = analysis.completed()
                ? (eligible.isEmpty() ? "NOT_MET" : "MET") : "NOT_EVALUATED";
        List<String> cited = "MET".equals(resultStatus) ? eligible : analysis.allEvidenceIds();

        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", "criterion-evaluation-v2");
        result.put("contextId", analysis.contextId());
        result.put("canonicalizationProfile", C14N);
        result.put("criterionId", criterion.id());
        ObjectNode rule = result.putObject("ruleBinding");
        rule.put("ruleRegistryId", bindings.registryId());
        rule.put("ruleRegistrySha256", bindings.registryHash());
        rule.put("currentGeneralDesignation", criterion.designation());
        ArrayNode authorities = rule.putArray("authoritySourceIds");
        new TreeSet<>(criterion.authorities()).forEach(authorities::add);
        rule.put("cspecSelectionId", selectionId);

        ObjectNode applicability = result.putObject("criterionApplicability");
        applicability.put("status", analysis.applicability());
        if (!"APPLICABLE".equals(analysis.applicability())) {
            applicability.put("reasonCode", analysis.reason());
        }
        ObjectNode evaluation = result.putObject("evaluationResult");
        evaluation.put("status", resultStatus);
        if ("NOT_EVALUATED".equals(resultStatus)) {
            evaluation.put("reasonCode", analysis.reason());
        }
        result.put("baseStrength", criterion.strength());
        result.put("appliedStrength", "MET".equals(resultStatus)
                ? criterion.strength() : "NONE");
        ArrayNode evidenceIds = result.putArray("evidenceIds");
        cited.forEach(evidenceIds::add);

        ObjectNode trace = result.putObject("deterministicTrace");
        trace.put("executionStatus", analysis.completed() ? "COMPLETED" : "NOT_RUN");
        trace.put("evaluatorId", EVALUATOR_ID);
        trace.put("evaluatorVersion", EVALUATOR_VERSION);
        ArrayNode inputs = trace.putArray("inputEvidenceIds");
        analysis.allEvidenceIds().forEach(inputs::add);
        trace.set("parameters", array(analysis.parameters()));
        ArrayNode steps = trace.putArray("steps");
        if (analysis.completed()) {
            steps.add(step(criterion.designation() + "_ELIGIBLE_COMPARATOR_FOUND",
                    eligible.isEmpty() ? "FALSE" : "TRUE", cited));
            trace.put("algorithmId", "CLINVAR_MISSENSE_PS1_PM5_V1");
            trace.put("algorithmVersion", "1.0.0");
            trace.put("traceSha256", canonicalizer.canonicalSha256(trace));
        } else {
            steps.add(step(analysis.reason(), "NOT_APPLICABLE".equals(analysis.applicability())
                    ? "NOT_APPLICABLE" : "UNKNOWN", analysis.allEvidenceIds()));
            trace.put("reasonCode", analysis.reason());
        }

        if (analysis.conflict()) {
            String conflictId = conflictId(analysis);
            ObjectNode conflict = result.putObject("conflict");
            conflict.put("status", "PRESENT");
            conflict.putArray("conflictIds").add(conflictId);
            ObjectNode review = result.putObject("review");
            review.put("status", "REQUIRED");
            review.put("decision", "NONE");
            review.putArray("reviewRecordIds").add(reviewId(analysis.contextId(), conflictId));
        } else {
            result.set("conflict", summary("NONE", "conflictIds"));
            ObjectNode review = summary("NOT_REQUIRED", "reviewRecordIds");
            review.put("decision", "NONE");
            result.set("review", review);
        }

        ObjectNode identity = result.deepCopy();
        identity.remove(List.of("evaluationId", "conflict", "review"));
        result.put("evaluationId", "evaluation-sha256-"
                + canonicalizer.canonicalSha256(identity));
        return result;
    }

    private String conflictId(Analysis analysis) {
        ObjectNode value = mapper.createObjectNode();
        value.put("contextId", analysis.contextId());
        value.put("type", "SOURCE_VALUE_DISAGREEMENT");
        ArrayNode ids = value.putArray("participantEvidenceIds");
        analysis.conflictEvidenceIds().forEach(ids::add);
        return "conflict-sha256-" + canonicalizer.canonicalSha256(value);
    }

    private String reviewId(String contextId, String conflictId) {
        ObjectNode value = mapper.createObjectNode();
        value.put("contextId", contextId);
        value.put("conflictId", conflictId);
        value.put("status", "REQUIRED");
        return "review-sha256-" + canonicalizer.canonicalSha256(value);
    }

    private List<ObjectNode> baseParameters(Bindings bindings) {
        List<ObjectNode> values = new ArrayList<>();
        values.add(parameter("profile.id", PROFILE_ID));
        values.add(parameter("profile.version", PROFILE_VERSION));
        values.add(parameter("profile.sha256", bindings.profileHash()));
        for (JsonNode resource : bindings.resources().values()) {
            String prefix = "resource." + resource.path("resourceId").asText();
            values.add(parameter(prefix + ".version", resource.path("resourceVersion").asText()));
            values.add(parameter(prefix + ".sha256",
                    resource.path("identityFingerprintSha256").asText()));
        }
        values.sort(Comparator.comparing(value -> value.path("name").asText()));
        return values;
    }

    private ObjectNode parameter(String name, String value) {
        return mapper.createObjectNode().put("name", name).put("value", value);
    }

    private ObjectNode step(String code, String outcome, List<String> evidenceIds) {
        ObjectNode value = mapper.createObjectNode().put("stepCode", code).put("outcome", outcome);
        ArrayNode ids = value.putArray("inputEvidenceIds");
        evidenceIds.forEach(ids::add);
        return value;
    }

    private ArrayNode array(List<ObjectNode> values) {
        ArrayNode result = mapper.createArrayNode();
        values.forEach(value -> result.add(value.deepCopy()));
        return result;
    }

    private ObjectNode summary(String status, String idsField) {
        ObjectNode value = mapper.createObjectNode().put("status", status);
        value.putArray(idsField);
        return value;
    }

    private List<ObjectNode> uniqueSorted(List<ObjectNode> values) {
        Map<String, ObjectNode> unique = new LinkedHashMap<>();
        for (ObjectNode value : values) {
            if (value != null) unique.putIfAbsent(value.path("evidenceId").asText(), value);
        }
        List<ObjectNode> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparing(value -> value.path("evidenceId").asText()));
        return result;
    }

    private List<String> evidenceIds(List<ObjectNode> atomics) {
        List<String> result = new ArrayList<>();
        atomics.forEach(value -> result.add(value.path("evidenceId").asText()));
        return result;
    }

    private String text(ObjectNode evidence, String metricId) {
        JsonNode found = null;
        for (JsonNode value : evidence.path("measurements")) {
            if (metricId.equals(value.path("metricId").asText())) {
                if (found != null) return null;
                found = value.path("rawValue");
            }
        }
        if (found == null || found.isNull() || !found.isValueNode()
                || found.asText().trim().isEmpty()) return null;
        return found.asText().trim();
    }

    private static JsonNode find(JsonNode values, String field, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.path(field).asText())) return value;
        }
        return MissingNode.getInstance();
    }

    private static Set<String> textSet(JsonNode values) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode value : values) {
            if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText());
        }
        return Collections.unmodifiableSet(result);
    }

    private static ObjectNode readObject(ObjectMapper mapper,
                                         String resource,
                                         String expectedByteHash) {
        byte[] bytes;
        try (InputStream input = ClinVarPs1Pm5CriterionEvaluator.class
                .getResourceAsStream(resource)) {
            if (input == null) throw invalid("classpath resource is missing: " + resource);
            bytes = input.readAllBytes();
        } catch (IOException failure) {
            throw invalid("classpath resource cannot be read: " + resource);
        }
        if (expectedByteHash != null && !expectedByteHash.equals(sha256(bytes))) {
            throw invalid("classpath resource byte hash is not pinned: " + resource);
        }
        try {
            JsonNode value = mapper.readTree(new String(bytes, StandardCharsets.UTF_8));
            if (!value.isObject()) throw invalid("classpath resource is not an object: " + resource);
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

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid ClinVar E3-K1B profile: " + message);
    }

    private record Bindings(String profileHash,
                            String registryId,
                            String registryHash,
                            Map<String, JsonNode> resources,
                            Criterion ps1,
                            Criterion pm5,
                            String issue) {
    }

    private record Criterion(String id,
                             String designation,
                             String strength,
                             Set<String> authorities,
                             JsonNode registered) {
    }

    private record Assertion(String evidenceId,
                             String mondoId,
                             String comparatorGene,
                             String classification,
                             boolean highReview,
                             String relation,
                             String comparatorKey,
                             String issue) {
        private static Assertion invalid(String issue) {
            return new Assertion(null, null, null, null, false, null, null, issue);
        }
    }

    private record DirectionEvidence(Set<String> pathogenicIds, Set<String> benignIds) {
        private DirectionEvidence() {
            this(new TreeSet<>(), new TreeSet<>());
        }
    }

    private record Analysis(String contextId,
                            String applicability,
                            String reason,
                            boolean completed,
                            List<String> allEvidenceIds,
                            List<String> ps1EvidenceIds,
                            List<String> pm5EvidenceIds,
                            List<ObjectNode> parameters,
                            boolean conflict,
                            List<String> conflictEvidenceIds) {
        private static Analysis blocked(String contextId,
                                        String applicability,
                                        String reason,
                                        List<String> ids,
                                        List<ObjectNode> parameters) {
            return new Analysis(contextId, applicability, reason, false,
                    List.copyOf(ids), List.of(), List.of(), List.copyOf(parameters), false, List.of());
        }

        private static Analysis completed(String contextId,
                                          List<String> ids,
                                          List<String> ps1,
                                          List<String> pm5,
                                          List<ObjectNode> parameters) {
            return new Analysis(contextId, "APPLICABLE", null, true, List.copyOf(ids),
                    List.copyOf(ps1), List.copyOf(pm5), List.copyOf(parameters), false, List.of());
        }

        private static Analysis conflict(String contextId,
                                         List<String> ids,
                                         List<String> participants,
                                         List<ObjectNode> parameters) {
            return new Analysis(contextId, "APPLICABLE", "SAME_CONDITION_ASSERTION_CONFLICT", false,
                    List.copyOf(ids), List.of(), List.of(), List.copyOf(parameters), true,
                    List.copyOf(participants));
        }
    }
}
