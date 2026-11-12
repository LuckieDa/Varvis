package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.DeNovoEvidenceCalculator.*;

/** Synthetic source-reader facts only; real paper replay is verified separately. */
class DeNovoCohortSourceStatusTest {
    @TempDir Path temporary;
    private DeNovoBatchFixture f;
    private ObjectNode root, row;
    private DeNovoApplicabilityPolicy policy;
    private Group group;

    @BeforeEach void setup() throws Exception {
        f = new DeNovoBatchFixture(temporary.resolve("materials"));
        var term = f.phenotype.putArray("literatureTerms").addObject().put("hpoId", "HP:0001250");
        term.putArray("aliases").add("Synthetic trait"); term.putArray("sourceIds").add("s");
        byte[] bytes = f.mapper.writeValueAsBytes(f.phenotype);
        policy = DeNovoApplicabilityPolicy.load(bytes, FamilyDeNovoJson.sha256(bytes),
                Map.of("s", Files.readAllBytes(f.root.resolve("source.txt"))), DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION);
        group = new Group("synthetic-allele", "HGNC:1748", "MONDO:0007648", "AD");
        root = f.mapper.createObjectNode();
        var facts = root.putArray("documents").addObject().put("status", "SOURCE_READ").putObject("facts");
        facts.put("pmcid", "PMC999999997").put("methodVersion", "denovo-bundle-case-qualification-1.0.0");
        row = facts.putArray("cases").addObject().put("caseLabel", "3")
                .put("familyHistory", "REPORTED_NONE").put("contextVerification", "SUPPORTED_EXPLICIT_FAMILY_STATEMENTS");
        row.putObject("alleleIdentity").put("hgncId", group.hgncId()).set("allele", f.context().at("/variant/evidenceQueryAllele"));
        row.putArray("fields").addObject().put("field", "reportedPhenotype").put("findingState", "REPORTED").put("sourceValue", "Synthetic trait");
        row.putObject("parentage").put("status", "PUBLISHED_PARENTAGE_NOT_CONFIRMED");
        var target = row.putObject("targetTesting").put("status", "PUBLISHED_TARGETED_PARENTAL_TESTING_DENOVO")
                .put("basis", "EXPLICIT_COHORT_TARGET_RESULTS_PLUS_CASE_SPECIFIC_METHOD").put("method", "SANGER")
                .put("proband", "REPORTED_PRESENT").put("mother", "REPORTED_ABSENT").put("father", "REPORTED_ABSENT");
        var refs = target.putArray("citations");
        refs.addObject().put("unitId", "synthetic-body"); refs.addObject().put("unitId", "synthetic-row");
    }
    private Result evaluate() throws Exception {
        byte[] raw = f.mapper.writeValueAsBytes(root);
        var source = new DeNovoLiteratureReader.Read(root, FamilyDeNovoJson.sha256(raw), raw, null, false);
        var qualified = new DeNovoLiteratureEvidence(source).qualify(f.context(), group, policy, List.of(), List.of());
        return new DeNovoEvidenceCalculator().evaluate(group, policy.calculation(), qualified.observations());
    }
    @ParameterizedTest @ValueSource(strings={"SANGER", "TRIO_EXOME", "TRIO_GENOME"})
    void explicitUnconfirmedParentageIsAssumedNeverConfirmed(String method) throws Exception {
        ((ObjectNode) row.path("targetTesting")).put("method", method);
        var r = evaluate();
        assertEquals("PM6", r.code()); assertEquals(2, r.effectiveQuarterPoints());
        assertFalse(r.events().get(0).confirmedParentage());
    }
    @ParameterizedTest @ValueSource(strings={"context", "family", "father", "parentage", "method", "citations", "basis"})
    void otherGapsStillBlockScoring(String gap) throws Exception {
        var target = (ObjectNode) row.path("targetTesting");
        switch (gap) {
            case "context" -> row.put("contextVerification", "UNRESOLVED");
            case "family" -> row.put("familyHistory", "UNKNOWN");
            case "father" -> target.put("father", "UNKNOWN");
            case "parentage" -> ((ObjectNode) row.path("parentage")).put("status", "UNKNOWN");
            case "method" -> target.put("method", "AUTHOR_LABEL_ONLY");
            case "citations" -> target.putArray("citations");
            case "basis" -> target.remove("basis");
        }
        assertEquals("NOT_EVALUATED", evaluate().status());
        assertEquals(0, evaluate().effectiveQuarterPoints());
    }
    @Test void oldConfirmedSourceIsUnchanged() throws Exception {
        ((ObjectNode) row.path("targetTesting")).put("status", "PUBLISHED_TRIO_SANGER_DENOVO");
        ((ObjectNode) row.path("parentage")).put("status", "PUBLISHED_BOTH_PARENTS_CONFIRMED");
        assertEquals("PS2", evaluate().code()); assertEquals(4, evaluate().effectiveQuarterPoints());
    }
    @Test void explicitPolicyStillControlsAssumedParentage() throws Exception {
        f.phenotype.put("allowAssumedParentage", false);
        byte[] bytes = f.mapper.writeValueAsBytes(f.phenotype);
        policy = DeNovoApplicabilityPolicy.load(bytes, FamilyDeNovoJson.sha256(bytes),
                Map.of("s", Files.readAllBytes(f.root.resolve("source.txt"))), DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION);
        var result = evaluate();
        assertEquals("NOT_EVALUATED", result.status());
        assertEquals("DENOVO_ASSUMED_PARENTAGE_POLICY_REQUIRED", result.events().get(0).reasonCode());
    }
}
