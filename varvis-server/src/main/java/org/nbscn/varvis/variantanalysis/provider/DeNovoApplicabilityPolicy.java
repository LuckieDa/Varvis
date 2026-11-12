package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reusable, configuration-pinned phenotype conditions, never a per-case approval. */
public final class DeNovoApplicabilityPolicy {
    public static final String SCHEMA = "denovo-phenotype-policy-v1";
    public static final String VERSION = "denovo-phenotype-matcher-1.1.0";
    public enum Use { SYNTHETIC_VALIDATION, RESEARCH }
    private static final FamilyDeNovoJson JSON = new FamilyDeNovoJson("DENOVO_POLICY_");
    private final ObjectNode policy;
    private final byte[] original;
    private final String sha256;
    private final Use use;

    private DeNovoApplicabilityPolicy(ObjectNode policy, byte[] original, String sha256, Use use) {
        this.policy = policy.deepCopy(); this.original = original.clone(); this.sha256 = sha256; this.use = java.util.Objects.requireNonNull(use);
    }

    /**
     * expectedSha256 is the adopted configuration pin, not a request/model field.
     * Source bytes prove provenance, not that an arbitrary translation of prose is
     * correct. Installing a new research policy still requires source-condition
     * assessment; matching later cases does not require a new policy signature.
     */
    public static DeNovoApplicabilityPolicy load(byte[] bytes, String expectedSha256,
                                                Map<String, byte[]> sourceBytes, Use use) {
        byte[] retained = bytes.clone();
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")
                || !FamilyDeNovoJson.sha256(retained).equals(expectedSha256)) throw JSON.invalid("PIN_MISMATCH");
        ObjectNode n = JSON.parse(new ObjectMapper(), retained);
        JSON.shape(n, "schemaVersion:s policyId:s version:s materialClass:s scope:o allowAssumedParentage:b expertPolicyStatus:s sources:a conditions:a literatureTerms:!a specialEvents:!o");
        JSON.equal(n, "schemaVersion", SCHEMA);
        JSON.enumeration(n, "materialClass", "SYNTHETIC SOURCE_ASSESSED_RESEARCH");
        if ("SYNTHETIC".equals(n.path("materialClass").asText()) && use != Use.SYNTHETIC_VALIDATION)
            throw JSON.invalid("SYNTHETIC_POLICY_NOT_FOR_RESEARCH");
        JSON.enumeration(n, "expertPolicyStatus", "GENERAL_ALLOWED APPLICABLE_IMPLEMENTED APPLICABLE_UNRESOLVED");
        JSON.shape(n.path("scope"), "hgncId:s diseaseId:s inheritance:s");
        JSON.enumeration(n.path("scope"), "inheritance", "AD AR X_LINKED");
        if (n.path("sources").isEmpty() || n.path("sources").size() > 30) throw JSON.invalid("SOURCE_LIMIT");
        Set<String> sourceIds = new HashSet<>();
        for (JsonNode s : n.path("sources")) {
            JSON.shape(s, "sourceId:s sha256:s locator:s quote:s");
            String id = s.path("sourceId").asText();
            byte[] raw = sourceBytes.get(id);
            if (!sourceIds.add(id) || raw == null || raw.length == 0 || raw.length > 2_000_000
                    || !FamilyDeNovoJson.sha256(raw).equals(s.path("sha256").asText())) throw JSON.invalid("SOURCE_BYTES_MISMATCH");
            try {
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
                if (!text.contains(s.path("quote").asText())) throw JSON.invalid("SOURCE_QUOTE_MISMATCH");
            } catch (CharacterCodingException invalid) { throw JSON.invalid("SOURCE_UTF8_INVALID"); }
        }
        if (n.path("conditions").isEmpty() || n.path("conditions").size() > 100) throw JSON.invalid("CONDITION_LIMIT");
        boolean rasopathy = DeNovoEventPolicy.Mode.RASOPATHY_POINTS.name().equals(n.at("/specialEvents/mode").asText());
        boolean phenotypeOnly = DeNovoEventPolicy.Mode.SVI_1_1_PHENOTYPE_ONLY.name().equals(n.at("/specialEvents/mode").asText());
        boolean published = DeNovoEventPolicy.Mode.SVI_1_1_PUBLISHED_POINTS.name().equals(n.at("/specialEvents/mode").asText());
        if (rasopathy && !"AD".equals(n.at("/scope/inheritance").asText())) throw JSON.invalid("RASOPATHY_SCOPE_REQUIRED");
        if (phenotypeOnly && !"AD".equals(n.at("/scope/inheritance").asText())) throw JSON.invalid("PHENOTYPE_ONLY_SCOPE_REQUIRED");
        if (published && !"AD".equals(n.at("/scope/inheritance").asText())) throw JSON.invalid("PUBLISHED_POINTS_SCOPE_REQUIRED");
        Set<String> ruleIds = new HashSet<>();
        for (JsonNode r : n.path("conditions")) {
            JSON.shape(r, "conditionId:s category:s presentAll:a absentAll:a sourceIds:a"
                    + (published ? " qtcAboveMs:!i presentFindingsAny:!a" : ""));
            if (!ruleIds.add(r.path("conditionId").asText())) throw JSON.invalid("CONDITION_ID_COLLISION");
            JSON.enumeration(r, "category", published ? "HIGHLY_SPECIFIC CONSISTENT NOT_CONSISTENT"
                    : rasopathy ? "RASOPATHY_COMPATIBLE CONSISTENT NOT_CONSISTENT"
                    : "HIGHLY_SPECIFIC CONSISTENT CONSISTENT_HIGH_HETEROGENEITY NOT_CONSISTENT");
            Set<String> positive = terms(r.path("presentAll")), negative = terms(r.path("absentAll"));
            if (published && !"NOT_CONSISTENT".equals(r.path("category").asText())) {
                if (r.path("qtcAboveMs").longValue() <= 0 || r.path("qtcAboveMs").longValue() >= 10000)
                    throw JSON.invalid("QTC_THRESHOLD_REQUIRED");
                if ("HIGHLY_SPECIFIC".equals(r.path("category").asText()))
                    DeNovoEventPolicy.findingKeys(r.path("presentFindingsAny"), "PROBAND", JSON);
                else if (r.has("presentFindingsAny")) throw JSON.invalid("SPECIFIC_FINDING_CATEGORY_INVALID");
            } else if (r.has("qtcAboveMs") || r.has("presentFindingsAny") || positive.isEmpty() && negative.isEmpty()) {
                throw JSON.invalid("PHENOTYPE_CONDITIONS_REQUIRED");
            }
            if (!java.util.Collections.disjoint(positive, negative)) throw JSON.invalid("CONDITION_CONTRADICTION");
            JSON.strings(r.path("sourceIds"));
            if (r.path("sourceIds").isEmpty()) throw JSON.invalid("CONDITION_SOURCE_REQUIRED");
            for (JsonNode ref : r.path("sourceIds")) if (!sourceIds.contains(ref.asText())) throw JSON.invalid("CONDITION_SOURCE_MISSING");
        }
        if (n.has("literatureTerms")) {
            if (n.path("literatureTerms").size() > 1000) throw JSON.invalid("LITERATURE_TERM_LIMIT");
            for (var term : n.path("literatureTerms")) {
                JSON.shape(term, "hpoId:s aliases:a sourceIds:a");
                if (!term.path("hpoId").asText().matches("HP:[0-9]{7}")) throw JSON.invalid("TERM_INVALID");
                JSON.strings(term.path("aliases")); JSON.strings(term.path("sourceIds"));
                if (term.path("aliases").isEmpty() || term.path("aliases").size() > 30 || term.path("sourceIds").isEmpty())
                    throw JSON.invalid("LITERATURE_TERM_SOURCE_REQUIRED");
                for (var id : term.path("sourceIds")) if (!sourceIds.contains(id.asText())) throw JSON.invalid("CONDITION_SOURCE_MISSING");
            }
        }
        if (n.has("specialEvents")) DeNovoEventPolicy.read(n.path("specialEvents"), n.path("expertPolicyStatus").asText(), sourceIds);
        else if (!"AD".equals(n.at("/scope/inheritance").asText())) throw JSON.invalid("SPECIAL_POLICY_REQUIRED");
        return new DeNovoApplicabilityPolicy(n, retained, expectedSha256, use);
    }

    public String id() { return policy.path("policyId").asText() + "@" + policy.path("version").asText(); }
    public String sha256() { return sha256; }
    public byte[] originalBytes() { return original.clone(); }
    public ObjectNode snapshot() { return policy.deepCopy(); }
    public boolean permitsSyntheticReports() { return use == Use.SYNTHETIC_VALIDATION; }
    DeNovoEvidenceCalculator.Policy calculation() {
        return new DeNovoEvidenceCalculator.Policy(id(), policy.path("allowAssumedParentage").asBoolean(),
                "APPLICABLE_UNRESOLVED".equals(policy.path("expertPolicyStatus").asText()), specialEvents());
    }
    DeNovoEventPolicy specialEvents() {
        if (!policy.has("specialEvents")) return null;
        var refs = new HashSet<String>(); for (var source : policy.path("sources")) refs.add(source.path("sourceId").asText());
        return DeNovoEventPolicy.read(policy.path("specialEvents"), policy.path("expertPolicyStatus").asText(), refs);
    }
    record Match(DeNovoEvidenceCalculator.Phenotype phenotype, List<String> matchedConditions, List<String> issues) {
        Match { matchedConditions = List.copyOf(matchedConditions); issues = List.copyOf(issues); }
    }
    Match match(DeNovoEvidenceCalculator.Group group, DeNovoStructuredReport.ClinicalFacts facts) {
        JsonNode scope = policy.path("scope");
        if (!group.hgncId().equals(scope.path("hgncId").asText()) || !group.diseaseId().equals(scope.path("diseaseId").asText())
                || !group.inheritance().equals(scope.path("inheritance").asText())) return unknown("DENOVO_POLICY_BACKGROUND_MISMATCH");
        if (!group.diseaseId().equals(facts.reportedDiseaseId()) || !group.inheritance().equals(facts.inheritance()))
            return unknown("DENOVO_REPORTED_DISEASE_OR_INHERITANCE_UNRESOLVED");
        return matchFeatures(Set.copyOf(facts.presentHpo()), Set.copyOf(facts.absentHpo()));
    }
    /** A selected disease background is a hypothesis, not a demand for a prior diagnosis.
     * Typed source facts may use verified phenotypes when the report explicitly leaves
     * diagnosis/MOI unknown. A known contradictory label is not silently rewritten. */
    Match matchHypothesis(DeNovoEvidenceCalculator.Group group, DeNovoStructuredReport.ClinicalFacts facts) {
        JsonNode scope = policy.path("scope");
        if (!group.hgncId().equals(scope.path("hgncId").asText()) || !group.diseaseId().equals(scope.path("diseaseId").asText())
                || !group.inheritance().equals(scope.path("inheritance").asText())) return unknown("DENOVO_POLICY_BACKGROUND_MISMATCH");
        if (!Set.of(group.diseaseId(), "UNKNOWN").contains(facts.reportedDiseaseId())
                || !Set.of(group.inheritance(), "UNKNOWN").contains(facts.inheritance()))
            return unknown("DENOVO_REPORTED_DISEASE_OR_INHERITANCE_UNRESOLVED");
        return matchFeatures(Set.copyOf(facts.presentHpo()), Set.copyOf(facts.absentHpo()));
    }
    /** Literature need not have used today's diagnostic label. Never changes its reported diagnosis. */
    Match matchLiterature(DeNovoEvidenceCalculator.Group group, List<String> reportedPositiveFeatures,
                          List<String> reportedNegativeFeatures) {
        return matchLiterature(group, reportedPositiveFeatures, reportedNegativeFeatures,
                com.fasterxml.jackson.databind.node.MissingNode.getInstance());
    }
    Match matchLiterature(DeNovoEvidenceCalculator.Group group, List<String> reportedPositiveFeatures,
                          List<String> reportedNegativeFeatures, JsonNode publishedConditions) {
        var scope = policy.path("scope");
        if (!group.hgncId().equals(scope.path("hgncId").asText()) || !group.diseaseId().equals(scope.path("diseaseId").asText())
                || !group.inheritance().equals(scope.path("inheritance").asText())) return unknown("DENOVO_POLICY_BACKGROUND_MISMATCH");
        var qtc = publishedConditions.path("qtcMs");
        if (reportedPositiveFeatures.isEmpty() && reportedNegativeFeatures.isEmpty() && !qtc.isNumber()
                && publishedConditions.path("findings").isEmpty()) return unknown("DENOVO_PHENOTYPE_BASIS_REQUIRED");
        Set<String> present = new HashSet<>();
        Set<String> absent = new HashSet<>();
        if (!mapLiteratureTerms(reportedPositiveFeatures, present) || !mapLiteratureTerms(reportedNegativeFeatures, absent))
            return unknown("DENOVO_LITERATURE_PHENOTYPE_ALIAS_AMBIGUOUS");
        return matchFeatures(present, absent, publishedConditions);
    }
    private boolean mapLiteratureTerms(List<String> features, Set<String> mapped) {
        for (String feature : features) {
            Set<String> ids = new HashSet<>();
            for (var term : policy.path("literatureTerms")) for (var alias : term.path("aliases"))
                if (feature.strip().equalsIgnoreCase(alias.asText().strip())) ids.add(term.path("hpoId").asText());
            if (ids.size() > 1) return false;
            mapped.addAll(ids);
        }
        return true;
    }
    private Match matchFeatures(Set<String> present, Set<String> absent) {
        return matchFeatures(present, absent, com.fasterxml.jackson.databind.node.MissingNode.getInstance());
    }
    private Match matchFeatures(Set<String> present, Set<String> absent, JsonNode publishedConditions) {
        if (!java.util.Collections.disjoint(present, absent)) return unknown("DENOVO_PHENOTYPE_SOURCE_CONFLICT");
        var findings = publishedFindings(publishedConditions);
        if (findings.entrySet().stream().anyMatch(e -> e.getKey().startsWith("PROBAND:") && "CONFLICT".equals(e.getValue())))
            return unknown("DENOVO_PHENOTYPE_SOURCE_CONFLICT");
        List<String> ids = new ArrayList<>();
        Set<DeNovoEvidenceCalculator.Phenotype> matches = new HashSet<>();
        var qtc = publishedConditions.path("qtcMs");
        boolean measured = false;
        for (JsonNode condition : policy.path("conditions")) {
            // An unreported feature is not a reported negative. No HPO similarity shortcut.
            if (!present.containsAll(terms(condition.path("presentAll"))) || !absent.containsAll(terms(condition.path("absentAll"))))
                continue;
            if (condition.has("qtcAboveMs")) {
                if (!qtc.isNumber() || qtc.decimalValue().compareTo(condition.path("qtcAboveMs").decimalValue()) <= 0) continue;
                measured = true;
            }
            if (condition.has("presentFindingsAny")) {
                boolean specific = false;
                for (var key : condition.path("presentFindingsAny")) specific |= "PRESENT".equals(findings.get(key.asText()));
                if (!specific) continue;
            }
            matches.add(DeNovoEvidenceCalculator.Phenotype.valueOf(condition.path("category").asText()));
            ids.add(condition.path("conditionId").asText());
        }
        // A source-defined specific finding can refine a qualifying measurement.
        // A simultaneous match to an incompatible phenotype still blocks scoring.
        if (measured && matches.equals(Set.of(DeNovoEvidenceCalculator.Phenotype.HIGHLY_SPECIFIC, DeNovoEvidenceCalculator.Phenotype.CONSISTENT)))
            return new Match(DeNovoEvidenceCalculator.Phenotype.HIGHLY_SPECIFIC, ids, List.of());
        if (matches.isEmpty()) return unknown("DENOVO_PHENOTYPE_CONDITIONS_NOT_ESTABLISHED");
        if (matches.size() > 1)
            return unknown("DENOVO_PHENOTYPE_POLICY_CONFLICT");
        return new Match(matches.iterator().next(), ids, List.of());
    }
    static Map<String, String> publishedFindings(JsonNode publishedConditions) {
        Map<String, String> findings = new java.util.HashMap<>();
        for (var finding : publishedConditions.path("findings")) {
            String status = finding.path("status").asText();
            if (Set.of("PRESENT", "ABSENT", "CONFLICT").contains(status))
                findings.merge(finding.path("role").asText() + ":" + finding.path("finding").asText(), status,
                        (first, second) -> first.equals(second) ? first : "CONFLICT");
        }
        return findings;
    }
    private static Match unknown(String reason) { return new Match(DeNovoEvidenceCalculator.Phenotype.UNKNOWN, List.of(), List.of(reason)); }
    private static Set<String> terms(JsonNode array) {
        if (array.size() > 100) throw JSON.invalid("TERM_LIMIT");
        Set<String> result = new HashSet<>();
        for (JsonNode term : array) if (!term.isTextual() || !term.asText().matches("HP:[0-9]{7}") || !result.add(term.asText()))
            throw JSON.invalid("TERM_INVALID");
        return result;
    }
}
