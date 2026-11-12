package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reconcile retained observations before selecting the event person's trio.
 * An unknown observation adds no negative fact. A known counter-observation
 * cannot disappear when a more favourable structured source is loaded. */
final class DeNovoTrioReconciliation {
    private DeNovoTrioReconciliation() { }

    static List<String> local(DeNovoSpecialEvent.Verified special, List<DeNovoStructuredReport> reports) {
        var issues = new ArrayList<String>(); var event = special.snapshot(); var trio = event.path("trio");
        for (var report : reports) {
            boolean actualProband = Objects.equals(report.memberId("PROBAND"), special.personId());
            if (actualProband) for (String parent : List.of("MOTHER", "FATHER")) {
                String expected = trio.path(parent.equals("MOTHER") ? "motherId" : "fatherId").asText();
                String reported = report.memberId(parent);
                if (reported != null && !expected.equals("UNKNOWN") && !expected.equals(reported))
                    issues.add("DENOVO_SPECIAL_PARENT_IDENTITY_CONFLICT:" + parent);
            }
            for (var test : report.tests()) {
                String role = test.memberId().equals(special.personId()) ? "subject"
                        : actualProband && test.memberId().equals(report.memberId("MOTHER")) ? "mother"
                        : actualProband && test.memberId().equals(report.memberId("FATHER")) ? "father" : null;
                if (role == null) continue;
                if (differentQualifiedTissues(special, role, test, issues)) continue;
                compareTarget(issues, role, test.result(), test.quality(), trio.path(role + "Target").asText());
                if (!role.equals("subject") && event.path("parentalAvailability").asText().equals("UNAVAILABLE")
                        && (!test.result().equals("UNKNOWN") || Set.of("FAILED", "CONFLICT").contains(test.quality())))
                    issues.add("DENOVO_PARENTAL_AVAILABILITY_SOURCE_CONFLICT:" + role);
            }
            for (var relation : report.parentage()) {
                if (actualProband) {
                    String role = relation.parentId().equals(report.memberId("MOTHER")) ? "maternity" : "paternity";
                    compareRelation(issues, role, relation.result(), trio.path(role).asText());
                } else if (special.kind().equals("MATERNAL_ORIGIN") && relation.parentId().equals(special.personId())
                        && relation.result().equals("EXCLUDED"))
                    issues.add("DENOVO_MATERNAL_TRANSMISSION_PARENTAGE_EXCLUDED");
            }
        }
        return List.copyOf(new LinkedHashSet<>(issues));
    }

    static List<String> core(DeNovoSpecialEvent.Verified special, JsonNode core) {
        var issues = new ArrayList<String>(); var event = special.snapshot(); var trio = event.path("trio");
        boolean actualProband = special.personId().equals(special.probandId());
        for (String ordinary : List.of("proband", "mother", "father")) {
            String role = actualProband ? ordinary.equals("proband") ? "subject" : ordinary
                    : special.kind().equals("MATERNAL_ORIGIN") && ordinary.equals("mother") ? "subject" : null;
            if (role == null) continue;
            String path = "/groups/" + ordinary + "/targetResult";
            String result = ordinaryValue(core, path, issues);
            compareTarget(issues, role, result, result.equals("QUALITY_FAILED") ? "FAILED" : "UNKNOWN", trio.path(role + "Target").asText());
            if (!role.equals("subject") && event.path("parentalAvailability").asText().equals("UNAVAILABLE")
                    && !result.equals("UNKNOWN") && !result.isBlank())
                issues.add("DENOVO_PARENTAL_AVAILABILITY_SOURCE_CONFLICT:" + role);
        }
        if (actualProband) for (String role : List.of("maternity", "paternity"))
            compareRelation(issues, role, ordinaryValue(core, "/groups/parentage/" + role, issues), trio.path(role).asText());
        else if (special.kind().equals("MATERNAL_ORIGIN") && ordinaryValue(core, "/groups/parentage/maternity", issues).equals("EXCLUDED"))
            issues.add("DENOVO_MATERNAL_TRANSMISSION_PARENTAGE_EXCLUDED");
        return List.copyOf(new LinkedHashSet<>(issues));
    }

    private static String ordinaryValue(JsonNode core, String path, List<String> issues) {
        var fact = core.path("fieldProofs").path(path).path("fact");
        // Evaluation projections intentionally turn conflicting facts into null;
        // the retained source conflict is still material to the same trio.
        if (fact.path("verification").asText().equals("CONFLICT")
                && !fact.path("origin").asText().equals("MODEL_PROPOSAL"))
            issues.add("DENOVO_ORDINARY_SOURCE_FACT_CONFLICT:" + path);
        return core.path("values").path(path).asText("UNKNOWN");
    }

    private static void compareTarget(List<String> issues, String role, String ordinary, String quality, String special) {
        if (ordinary.equals("CONFLICT") || quality.equals("CONFLICT")) issues.add("DENOVO_TARGET_TEST_CONFLICT:" + role);
        if (ordinary.equals("QUALITY_FAILED") || quality.equals("FAILED")) issues.add("DENOVO_TARGET_TEST_QUALITY_FAILED:" + role);
        if (Set.of("PRESENT", "ABSENT").contains(ordinary) && Set.of("PRESENT", "ABSENT").contains(special) && !ordinary.equals(special))
            issues.add("DENOVO_SPECIAL_TARGET_SOURCE_CONFLICT:" + role + ":ordinary=" + ordinary + ":special=" + special);
    }

    private static void compareRelation(List<String> issues, String role, String ordinary, String special) {
        if (ordinary.equals("EXCLUDED")) issues.add("DENOVO_PARENTAGE_EXCLUDED:" + role);
        else if (ordinary.equals("INCONCLUSIVE") && special.equals("CONFIRMED"))
            issues.add("DENOVO_SPECIAL_PARENTAGE_SOURCE_UNRESOLVED:" + role);
    }

    private static boolean differentQualifiedTissues(DeNovoSpecialEvent.Verified special, String role,
                                                      DeNovoStructuredReport.TestFact test, List<String> issues) {
        var scope = test.observationScope(); var raw = special.snapshot();
        if (scope == null || !special.kind().equals("POSTZYGOTIC") || !role.equals("subject")) return false;
        var targetScope = raw.at("/trio/observationScopes/subject");
        if (!targetScope.isObject()) return false;
        String targetTissue = targetScope.path("tissue").asText();
        if (!known(scope.tissue()) || !known(targetTissue) || scope.tissue().equals(targetTissue)) return false;
        if (known(scope.specimenId()) && scope.specimenId().equals(targetScope.path("specimenId").asText())) {
            issues.add("DENOVO_SPECIAL_SPECIMEN_SCOPE_CONFLICT"); return false;
        }
        if (!test.quality().equals("VALID") || !test.targetCoverage().equals("EXPLICIT_TARGET")
                || !test.detectionCapability().equals("ADEQUATE")) return false;
        boolean ordinaryMeasured = false, specialMeasured = false;
        for (var row : raw.path("tissues")) {
            if (!special.personId().equals(row.path("personId").asText()) || !row.path("quality").asText().equals("HIGH_CONFIDENCE")) continue;
            String observed = row.path("altReads").asLong() > 0 ? "PRESENT" : "ABSENT";
            ordinaryMeasured |= scope.tissue().equals(row.path("tissueId").asText()) && test.result().equals(observed)
                    && test.method().equals(row.path("method").asText());
            specialMeasured |= targetTissue.equals(row.path("tissueId").asText()) && raw.at("/trio/subjectTarget").asText().equals(observed)
                    && raw.at("/trio/method").equals(row.path("method"));
        }
        // Actual measurements in two different source-bound tissues, not labels
        // alone. Timepoint/specimen metadata stays in the retained originals.
        return ordinaryMeasured && specialMeasured;
    }

    private static boolean known(String value) { return !value.isBlank() && !value.equals("UNKNOWN"); }
}
