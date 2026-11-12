package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.DeNovoEvidenceCalculator.*;

/** Synthetic source-reader output used to exercise the ordinary published conditions. */
class DeNovoPublishedConditionsTest {
    @TempDir Path temporary;
    private static final Group GROUP = new Group("synthetic-allele", "HGNC:1748", "MONDO:0007648", "AD");
    private static final String MODE = "SVI_1_1_PUBLISHED_POINTS";
    private static final String TRANSCRIPT = "ENST00000012345";

    @ParameterizedTest
    @CsvSource({
            "false,false,PM6,SUPPORTING,2",
            "false,true,PM6,MODERATE,4",
            "true,false,PS2,MODERATE,4",
            "true,true,PS2,STRONG,8"
    })
    void measuredPhenotypeAndEachRelationshipDeterminePoints(boolean confirmed, boolean specific,
                                                              String criterion, String strength, int quarterPoints) throws Exception {
        var f = new DeNovoBatchFixture(temporary);
        var context = context(f);
        var source = f.mapper.createObjectNode();
        var row = reviewed(source, context);
        if (confirmed) ((ObjectNode) row.path("parentage")).put("maternity", "CONFIRMED").put("paternity", "CONFIRMED");
        if (specific) finding(row, "PROBAND", "SPECIFIC_FINDING", "PRESENT");
        var policy = publishedPolicy(f);
        var qualified = qualify(context, source, policy);
        assertEquals(specific ? "HIGHLY_SPECIFIC" : "CONSISTENT", qualified.qualifications().get(0).phenotype());
        var result = new DeNovoEvidenceCalculator().evaluate(GROUP, policy.calculation(), qualified.observations());
        assertEquals("MET", result.status());
        assertEquals(criterion, result.code());
        assertEquals(strength, result.strength());
        assertEquals(quarterPoints, result.effectiveQuarterPoints());
        assertEquals(confirmed, result.events().get(0).confirmedParentage());
    }

    @ParameterizedTest
    @CsvSource({
            "at-threshold,EXCLUDED,DENOVO_PHENOTYPE_CONDITIONS_NOT_ESTABLISHED",
            "no-phenotype,EXCLUDED,DENOVO_PHENOTYPE_BASIS_REQUIRED",
            "wrong-transcript,EXCLUDED,DENOVO_PUBLISHED_CODING_VARIANT_BASIS_REQUIRED",
            "unreported-family,EXCLUDED,DENOVO_PUBLISHED_FAMILY_HISTORY_REQUIRED",
            "relevant-family-history,ZERO_SUPPORT,DENOVO_PUBLISHED_FAMILY_HISTORY_CONDITION_NOT_MET",
            "conflicting-family,EXCLUDED,DENOVO_PUBLISHED_FAMILY_HISTORY_CONFLICT",
            "conflicting-phenotype,EXCLUDED,DENOVO_PHENOTYPE_SOURCE_CONFLICT",
            "assay-unqualified,EXCLUDED,DENOVO_PATERNAL_TARGET_TEST_REQUIRED",
            "target-conflict,EXCLUDED,DENOVO_TARGET_TEST_CONFLICT",
            "relationship-excluded,EXCLUDED,DENOVO_PARENTAGE_EXCLUDED",
            "relationship-inconclusive,EXCLUDED,DENOVO_PARENTAGE_INCONCLUSIVE",
            "inherited,ZERO_SUPPORT,DENOVO_PARENTAL_TARGET_PRESENT"
    })
    void missingEvidenceAndObservedDisqualifiersStayDistinct(String scenario, String disposition, String reason) throws Exception {
        var f = new DeNovoBatchFixture(temporary);
        var context = context(f);
        var source = f.mapper.createObjectNode();
        var row = reviewed(source, context);
        var conditions = (ObjectNode) row.path("publishedConditions");
        switch (scenario) {
            case "at-threshold" -> conditions.put("qtcMs", 480);
            case "no-phenotype" -> row.remove("publishedConditions");
            case "wrong-transcript" -> ((ObjectNode) row.path("identityProof")).put("ensemblTranscript", TRANSCRIPT + ".2");
            case "unreported-family" -> conditions.remove("findings");
            case "relevant-family-history" -> ((ObjectNode) conditions.at("/findings/0")).put("status", "PRESENT");
            case "conflicting-family" -> finding(row, "MOTHER", "QT_PROLONGATION", "PRESENT");
            case "conflicting-phenotype" -> {
                finding(row, "PROBAND", "SPECIFIC_FINDING", "PRESENT");
                finding(row, "PROBAND", "SPECIFIC_FINDING", "ABSENT");
            }
            case "assay-unqualified" -> ((ObjectNode) row.at("/members/father")).put("method", "UNKNOWN");
            case "target-conflict" -> ((ObjectNode) row.at("/members/father")).put("target", "CONFLICT");
            case "relationship-excluded" -> ((ObjectNode) row.path("parentage")).put("paternity", "EXCLUDED");
            case "relationship-inconclusive" -> ((ObjectNode) row.path("parentage")).put("paternity", "INCONCLUSIVE");
            case "inherited" -> {
                row.put("family", "MATERNALLY_INHERITED");
                ((ObjectNode) row.at("/members/mother")).put("target", "PRESENT");
            }
            default -> fail("Unknown scenario " + scenario);
        }
        var policy = publishedPolicy(f);
        var qualified = qualify(context, source, policy);
        var result = new DeNovoEvidenceCalculator().evaluate(GROUP, policy.calculation(), qualified.observations());
        assertEquals(0, result.effectiveQuarterPoints());
        assertEquals("NONE", result.code());
        assertEquals(disposition.equals("EXCLUDED") ? "NOT_EVALUATED" : "NOT_MET", result.status());
        assertEquals(disposition, result.events().get(0).disposition());
        assertEquals(reason, result.events().get(0).reasonCode());
    }

    @Test void publishedCapDoesNotEraseQualifiedEventPointsOrConvertThemToPs2() throws Exception {
        var f = new DeNovoBatchFixture(temporary);
        var policy = publishedPolicy(f);
        var facts = new Facts(GROUP, TargetTest.PRESENT, TargetTest.ABSENT_QUALIFIED, TargetTest.ABSENT_QUALIFIED,
                Parentage.ASSUMED_NOT_REPORTED, Parentage.ASSUMED_NOT_REPORTED, Phenotype.HIGHLY_SPECIFIC, FamilyHistory.COMPATIBLE);
        var events = java.util.stream.IntStream.range(0, 4).mapToObj(i -> new Observation("obs" + i, "event" + i,
                SourceKind.LITERATURE_CASE, facts, Independence.ESTABLISHED, List.of("source" + i), List.of())).toList();
        var result = new DeNovoEvidenceCalculator().evaluate(GROUP, policy.calculation(), events);
        assertEquals(16, result.effectiveQuarterPoints());
        assertEquals("PM6", result.code());
        assertEquals("STRONG", result.strength());
        assertEquals("PM6_PUBLISHED_STRENGTH_CAP", result.strengthAdjustment());
    }

    @Test void aCaseBoundTrioAssayCanConfirmBothParentsButCannotOverrideAnExclusion() throws Exception {
        var f = new DeNovoBatchFixture(temporary);
        var context = context(f);
        var source = f.mapper.createObjectNode();
        var reviewed = reviewed(source, context);
        for (var member : reviewed.path("members")) ((ObjectNode) member).put("method", "EXOME");
        var policy = publishedPolicy(f);
        var calculator = new DeNovoEvidenceCalculator();
        assertEquals("PM6", calculator.evaluate(GROUP, policy.calculation(), qualify(context, source, policy).observations()).code());
        ((ObjectNode) reviewed.path("publishedConditions")).put("trioExome", true);
        assertEquals("PS2", calculator.evaluate(GROUP, policy.calculation(), qualify(context, source, policy).observations()).code());
        ((ObjectNode) reviewed.path("parentage")).put("maternity", "EXCLUDED");
        var excluded = calculator.evaluate(GROUP, policy.calculation(), qualify(context, source, policy).observations());
        assertEquals("DENOVO_PARENTAGE_EXCLUDED", excluded.events().get(0).reasonCode());
    }

    private static ObjectNode context(DeNovoBatchFixture f) {
        var context = f.context();
        context.putObject("transcript").put("accession", TRANSCRIPT).put("version", 1);
        return context;
    }

    private static ObjectNode reviewed(ObjectNode source, ObjectNode context) {
        var facts = source.putArray("documents").addObject().put("status", "SOURCE_READ")
                .putObject("facts").put("pmcid", "PMC999999997").put("sourceSha256", "a".repeat(64));
        var row = facts.putArray("cases").addObject().put("caseLabel", "Synthetic case");
        row.putObject("alleleIdentity").put("hgncId", GROUP.hgncId()).set("allele", context.at("/variant/evidenceQueryAllele"));
        var reviewed = row.putObject("reviewedCase").put("schemaVersion", DeNovoLiteratureEvidence.REVIEWED_PAPER)
                .put("diseaseId", GROUP.diseaseId()).put("family", "UNKNOWN");
        reviewed.putObject("identityProof").put("coding", "c.100T>A").put("ensemblTranscript", TRANSCRIPT + ".1");
        reviewed.putObject("parentage").put("maternity", "ASSUMED_NOT_TESTED").put("paternity", "ASSUMED_NOT_REPORTED");
        var members = reviewed.putObject("members");
        members.putObject("proband").put("target", "PRESENT").put("method", "SANGER");
        members.putObject("mother").put("target", "ABSENT").put("method", "SANGER");
        members.putObject("father").put("target", "ABSENT").put("method", "SANGER");
        reviewed.putObject("publishedConditions").put("qtcMs", 480.5).putArray("findings");
        finding(reviewed, "MOTHER", "QT_PROLONGATION", "ABSENT");
        finding(reviewed, "FATHER", "QT_PROLONGATION", "ABSENT");
        finding(reviewed, "FAMILY", "SUDDEN_DEATH", "ABSENT");
        return reviewed;
    }

    private static void finding(ObjectNode row, String role, String finding, String status) {
        ((ObjectNode) row.path("publishedConditions")).withArray("findings").addObject()
                .put("role", role).put("finding", finding).put("status", status);
    }

    private static DeNovoApplicabilityPolicy publishedPolicy(DeNovoBatchFixture f) throws Exception {
        f.phenotype.put("expertPolicyStatus", "APPLICABLE_IMPLEMENTED");
        var event = f.phenotype.putObject("specialEvents").put("mode", MODE).put("maximumPm6Strength", "STRONG")
                .put("trioExomeConfirmsParentage", true);
        event.putArray("sourceIds").add("s");
        event.putArray("requiredAbsentFindings").add("MOTHER:QT_PROLONGATION").add("FATHER:QT_PROLONGATION").add("FAMILY:SUDDEN_DEATH");
        var conditions = f.phenotype.putArray("conditions");
        for (var category : List.of("CONSISTENT", "HIGHLY_SPECIFIC")) {
            var condition = conditions.addObject().put("conditionId", category).put("category", category).put("qtcAboveMs", 480);
            condition.putArray("presentAll"); condition.putArray("absentAll"); condition.putArray("sourceIds").add("s");
            if (category.equals("HIGHLY_SPECIFIC")) condition.putArray("presentFindingsAny").add("PROBAND:SPECIFIC_FINDING");
        }
        byte[] raw = f.mapper.writeValueAsBytes(f.phenotype);
        return DeNovoApplicabilityPolicy.load(raw, DeNovoBatchFixture.sha(raw), Map.of("s", Files.readAllBytes(f.root.resolve("source.txt"))),
                DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION);
    }

    private static DeNovoLiteratureEvidence.Qualified qualify(ObjectNode context, ObjectNode source, DeNovoApplicabilityPolicy policy) {
        return new DeNovoLiteratureEvidence(new DeNovoLiteratureReader.Read(source, "d".repeat(64), null, null, false))
                .qualify(context, GROUP, policy, List.of(), List.of());
    }
}
