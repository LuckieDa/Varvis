package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.lang.Nullable;

/**
 * Source-native, local structured reports. Parsing preserves reported facts; it
 * does not authenticate a laboratory, qualify a disease, or approve evidence.
 * Literature drafts and model output cannot be loaded as this source format.
 */
public final class DeNovoStructuredReport {
    public static final String SCHEMA = "denovo-local-report-v1";
    public static final String SCHEMA_V2 = "denovo-local-report-v2";
    public static final String VERSION = "denovo-local-report-reader-1.3.0";
    private static final FamilyDeNovoJson JSON = new FamilyDeNovoJson("DENOVO_REPORT_");
    private static final Set<String> ROLES = Set.of("PROBAND", "MOTHER", "FATHER");
    private final ObjectNode raw;
    private final byte[] original;
    private final String sha256;
    private final Map<String, String> members;
    private final List<TestFact> tests;
    private final List<ParentageFact> relationships;

    public record ObservationScope(String specimenId, String tissue, String timepoint) { }
    public record TestFact(String memberId, String result, String method, String targetCoverage,
                           String detectionCapability, String quality, String basis, String limitations,
                           String sourceRef, @Nullable ObservationScope observationScope) {
        public TestFact(String memberId, String result, String method, String targetCoverage,
                        String detectionCapability, String quality, String basis, String limitations, String sourceRef) {
            this(memberId, result, method, targetCoverage, detectionCapability, quality, basis, limitations, sourceRef, null);
        }
    }
    public record ParentageFact(String parentId, String probandId, String result, String method,
                                String basis, String sourceRef) { }
    public record ClinicalFacts(String reportedDiseaseId, String inheritance, List<String> presentHpo,
                                List<String> absentHpo, String familyHistory, String mosaicism,
                                String siblingRecurrence, String sourceRef) {
        public ClinicalFacts { presentHpo = List.copyOf(presentHpo); absentHpo = List.copyOf(absentHpo); }
    }

    private DeNovoStructuredReport(ObjectNode raw, byte[] original, String sha256,
                                   Map<String, String> members, List<TestFact> tests,
                                   List<ParentageFact> relationships) {
        this.raw = raw.deepCopy(); this.original = original.clone(); this.sha256 = sha256;
        this.members = Map.copyOf(members); this.tests = List.copyOf(tests);
        this.relationships = List.copyOf(relationships);
    }

    /** expectedSha256 must come from the retained source, not an extraction's claimed digest. */
    public static DeNovoStructuredReport read(byte[] bytes, String expectedSha256) {
        Objects.requireNonNull(bytes); Objects.requireNonNull(expectedSha256);
        if (!expectedSha256.matches("[0-9a-f]{64}")) throw JSON.invalid("SOURCE_PIN_INVALID");
        byte[] retained = bytes.clone();
        String sha = FamilyDeNovoJson.sha256(retained);
        if (!sha.equals(expectedSha256)) throw JSON.invalid("SOURCE_PIN_MISMATCH");
        ObjectNode n = JSON.parse(new ObjectMapper(), retained);
        boolean extended = SCHEMA_V2.equals(n.path("schemaVersion").asText());
        JSON.shape(n, "schemaVersion:s sourceClass:s reportId:s reportVersion:s issuer:s caseId:s familyId:s target:o members:a tests:a parentage:a clinical:o literatureRelationships:!a"
                + (extended ? " specialEvent:!o" : ""));
        if (!extended) JSON.equal(n, "schemaVersion", SCHEMA);
        JSON.enumeration(n, "sourceClass", "SYNTHETIC LOCAL_REPORT");
        canonicalIds(n, "reportId", "reportVersion", "caseId", "familyId");
        JsonNode t = n.path("target");
        JSON.shape(t, "assembly:s contig:s position:i reference:s alternate:s hgncId:s");
        JSON.enumeration(t, "assembly", "GRCh38 GRCh37");
        canonicalIds(t, "contig", "hgncId");
        if (t.path("position").longValue() <= 0 || !t.path("reference").asText().matches("[ACGT]{1,4096}")
                || !t.path("alternate").asText().matches("[ACGT]{1,4096}")
                || t.path("reference").equals(t.path("alternate"))) throw JSON.invalid("TARGET_INVALID");
        // This is the reported identity, not an assertion that normalization has run.
        if (n.path("members").size() < 1 || n.path("members").size() > 3) throw JSON.invalid("MEMBER_LIMIT");
        Map<String, String> members = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode m : n.path("members")) {
            JSON.shape(m, "role:s memberId:s"); JSON.enumeration(m, "role", "PROBAND MOTHER FATHER");
            canonicalIds(m, "memberId");
            if (!ids.add(m.path("memberId").asText()) || members.putIfAbsent(m.path("role").asText(), m.path("memberId").asText()) != null)
                throw JSON.invalid("MEMBER_IDENTITY_CONFLICT");
        }
        if (!members.containsKey("PROBAND")) throw JSON.invalid("PROBAND_REQUIRED");
        if (n.path("tests").size() > 30 || n.path("parentage").size() > 20) throw JSON.invalid("RECORD_LIMIT");
        List<TestFact> tests = new ArrayList<>();
        int index = 0;
        for (JsonNode test : n.path("tests")) {
            JSON.shape(test, "memberId:s result:s method:s targetCoverage:s detectionCapability:s quality:s basis:s limitations:s"
                    + (extended ? " observationScope:!o" : ""));
            if (!ids.contains(test.path("memberId").asText())) throw JSON.invalid("TEST_MEMBER_MISMATCH");
            JSON.enumeration(test, "result", "PRESENT ABSENT UNKNOWN CONFLICT");
            JSON.enumeration(test, "method", "SANGER TARGETED_SEQUENCING EXOME GENOME OTHER UNKNOWN");
            JSON.enumeration(test, "targetCoverage", "EXPLICIT_TARGET UNKNOWN NOT_COVERED");
            JSON.enumeration(test, "detectionCapability", "ADEQUATE UNKNOWN INADEQUATE");
            JSON.enumeration(test, "quality", "VALID UNKNOWN FAILED CONFLICT");
            tests.add(new TestFact(test.path("memberId").asText(), test.path("result").asText(), test.path("method").asText(),
                    test.path("targetCoverage").asText(), test.path("detectionCapability").asText(), test.path("quality").asText(),
                    test.path("basis").asText(), test.path("limitations").asText(), sha + "#/tests/" + index++,
                    test.has("observationScope") ? readScope(test.path("observationScope")) : null));
        }
        List<ParentageFact> relationships = new ArrayList<>(); index = 0;
        for (JsonNode p : n.path("parentage")) {
            JSON.shape(p, "parentId:s probandId:s result:s method:s basis:s");
            if (!members.get("PROBAND").equals(p.path("probandId").asText())
                    || (!Objects.equals(members.get("MOTHER"), p.path("parentId").asText())
                    && !Objects.equals(members.get("FATHER"), p.path("parentId").asText())))
                throw JSON.invalid("PARENTAGE_MEMBER_MISMATCH");
            JSON.enumeration(p, "result", "CONFIRMED NOT_TESTED NOT_REPORTED INCONCLUSIVE EXCLUDED");
            JSON.enumeration(p, "method", "SNP_PANEL STR_PANEL SEQUENCING_KINSHIP OTHER NOT_DONE NOT_REPORTED");
            relationships.add(new ParentageFact(p.path("parentId").asText(), p.path("probandId").asText(),
                    p.path("result").asText(), p.path("method").asText(), p.path("basis").asText(), sha + "#/parentage/" + index++));
        }
        JsonNode c = n.path("clinical");
        JSON.shape(c, "probandId:s reportedDiseaseId:s inheritance:s presentHpo:a absentHpo:a familyHistory:s mosaicism:s siblingRecurrence:s");
        if (!members.get("PROBAND").equals(c.path("probandId").asText())) throw JSON.invalid("CLINICAL_MEMBER_MISMATCH");
        JSON.enumeration(c, "inheritance", "AD AR X_LINKED OTHER UNKNOWN");
        JSON.enumeration(c, "familyHistory", "NO_SIMILAR_HISTORY SIMILAR_HISTORY UNKNOWN CONFLICT");
        JSON.enumeration(c, "mosaicism", "NOT_REPORTED SUSPECTED KNOWN");
        JSON.enumeration(c, "siblingRecurrence", "NONE_REPORTED PRESENT UNKNOWN");
        hpo(c.path("presentHpo")); hpo(c.path("absentHpo"));
        if (n.has("literatureRelationships")) {
            if (n.path("literatureRelationships").size() > 100) throw JSON.invalid("LITERATURE_RELATION_LIMIT");
            for (var relation : n.path("literatureRelationships")) {
                JSON.shape(relation, "pmcid:s caseLabel:s relationship:s basis:s");
                if (!relation.path("pmcid").asText().matches("PMC[1-9][0-9]*")) throw JSON.invalid("LITERATURE_ID_INVALID");
                JSON.enumeration(relation, "relationship", "SAME_CASE UNRELATED_CASE UNKNOWN CONFLICT");
            }
        }
        return new DeNovoStructuredReport(n, retained, sha, members, tests, relationships);
    }

    public String sha256() { return sha256; }
    public String sourceClass() { return raw.path("sourceClass").asText(); }
    public String caseId() { return raw.path("caseId").asText(); }
    public String familyId() { return raw.path("familyId").asText(); }
    public String memberId(String role) { checkRole(role); return members.get(role); }
    public ObjectNode snapshot() { return raw.deepCopy(); }
    public byte[] originalBytes() { return original.clone(); }
    public List<TestFact> tests() { return tests; }
    public List<ParentageFact> parentage() { return relationships; }
    /** Preserved source facts only; the event reader independently grants scientific qualification. */
    @Nullable JsonNode specialEvent() {
        JsonNode event = raw.get("specialEvent");
        return event == null ? null : event.deepCopy();
    }
    public ClinicalFacts clinical() {
        JsonNode c = raw.path("clinical");
        return new ClinicalFacts(c.path("reportedDiseaseId").asText(), c.path("inheritance").asText(),
                texts(c.path("presentHpo")), texts(c.path("absentHpo")), c.path("familyHistory").asText(),
                c.path("mosaicism").asText(), c.path("siblingRecurrence").asText(), sha256 + "#/clinical");
    }

    // Only source-level interpretations. External callers cannot supply these directly to the calculator.
    DeNovoEvidenceCalculator.TargetTest targetStatus(String role) {
        checkRole(role);
        List<TestFact> rows = tests.stream().filter(t -> t.memberId().equals(members.get(role))).toList();
        if (rows.isEmpty()) return DeNovoEvidenceCalculator.TargetTest.UNKNOWN;
        if (rows.stream().anyMatch(t -> "CONFLICT".equals(t.result()) || "CONFLICT".equals(t.quality()))
                || rows.stream().filter(t -> Set.of("PRESENT", "ABSENT").contains(t.result())).map(mappedItem -> mappedItem.result()).distinct().count() > 1)
            return DeNovoEvidenceCalculator.TargetTest.CONFLICT;
        if (rows.stream().anyMatch(t -> "FAILED".equals(t.quality()))) return DeNovoEvidenceCalculator.TargetTest.QUALITY_FAILED;
        boolean qualified = rows.stream().allMatch(t -> Set.of("SANGER", "TARGETED_SEQUENCING", "EXOME", "GENOME").contains(t.method())
                && "EXPLICIT_TARGET".equals(t.targetCoverage()) && "ADEQUATE".equals(t.detectionCapability())
                && "VALID".equals(t.quality()) && Set.of("PRESENT", "ABSENT").contains(t.result()));
        if (!qualified) return DeNovoEvidenceCalculator.TargetTest.UNKNOWN;
        return "PRESENT".equals(rows.get(0).result()) ? DeNovoEvidenceCalculator.TargetTest.PRESENT : DeNovoEvidenceCalculator.TargetTest.ABSENT_QUALIFIED;
    }

    DeNovoEvidenceCalculator.Parentage parentageStatus(String role) {
        if (!Set.of("MOTHER", "FATHER").contains(role)) throw JSON.invalid("PARENT_ROLE_INVALID");
        List<ParentageFact> rows = relationships.stream().filter(p -> p.parentId().equals(members.get(role))).toList();
        if (rows.isEmpty()) return DeNovoEvidenceCalculator.Parentage.ASSUMED_NOT_REPORTED;
        if (rows.stream().anyMatch(p -> "EXCLUDED".equals(p.result()))) return DeNovoEvidenceCalculator.Parentage.EXCLUDED;
        if (rows.stream().map(mappedItem -> mappedItem.result()).distinct().count() != 1) return DeNovoEvidenceCalculator.Parentage.INCONCLUSIVE;
        String status = rows.get(0).result();
        if ("CONFIRMED".equals(status)) return rows.stream().allMatch(p -> Set.of("SNP_PANEL", "STR_PANEL", "SEQUENCING_KINSHIP").contains(p.method()))
                ? DeNovoEvidenceCalculator.Parentage.CONFIRMED : DeNovoEvidenceCalculator.Parentage.INCONCLUSIVE;
        if ("NOT_TESTED".equals(status) && rows.stream().allMatch(p -> "NOT_DONE".equals(p.method()))) return DeNovoEvidenceCalculator.Parentage.ASSUMED_NOT_TESTED;
        if ("NOT_REPORTED".equals(status) && rows.stream().allMatch(p -> "NOT_REPORTED".equals(p.method()))) return DeNovoEvidenceCalculator.Parentage.ASSUMED_NOT_REPORTED;
        return DeNovoEvidenceCalculator.Parentage.INCONCLUSIVE;
    }

    private static void checkRole(String role) { if (!ROLES.contains(role)) throw JSON.invalid("ROLE_INVALID"); }
    static ObservationScope readScope(JsonNode scope) {
        JSON.shape(scope, "specimenId:s tissue:s timepoint:s");
        canonicalIds(scope, "specimenId", "tissue", "timepoint");
        return new ObservationScope(scope.path("specimenId").asText(), scope.path("tissue").asText(), scope.path("timepoint").asText());
    }
    private static void canonicalIds(JsonNode n, String... fields) {
        for (String field : fields) {
            String text = n.path(field).asText();
            if (!text.equals(text.strip()) || !Normalizer.isNormalized(text, Normalizer.Form.NFC)) throw JSON.invalid("IDENTIFIER_NOT_CANONICAL");
        }
    }
    private static List<String> texts(JsonNode array) {
        List<String> result = new ArrayList<>(); array.forEach(n -> result.add(n.asText())); return List.copyOf(result);
    }
    private static void hpo(JsonNode array) {
        if (array.size() > 500) throw JSON.invalid("PHENOTYPE_LIMIT");
        Set<String> terms = new HashSet<>();
        for (JsonNode term : array) if (!term.isTextual() || !term.asText().matches("HP:[0-9]{7}") || !terms.add(term.asText())) throw JSON.invalid("HPO_INVALID");
        // Presence in both lists is deliberately retained for the applicability layer to reject.
    }
}
