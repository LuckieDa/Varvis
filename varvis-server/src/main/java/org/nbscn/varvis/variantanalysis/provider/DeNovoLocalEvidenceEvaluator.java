package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.nbscn.varvis.utils.CoordinateUtils;
import org.springframework.lang.Nullable;
import static org.nbscn.varvis.variantanalysis.provider.DeNovoEvidenceCalculator.*;

/** Local reports to deterministic evidence using an already verified Top-N context. */
public final class DeNovoLocalEvidenceEvaluator {
    public static final String VERSION = "denovo-local-evidence-1.4.1";
    private final ObjectMapper mapper;
    @Nullable private final DeNovoSpecialEvent.RegionSupply regions;
    public DeNovoLocalEvidenceEvaluator(ObjectMapper mapper) { this(mapper, null); }
    DeNovoLocalEvidenceEvaluator(ObjectMapper mapper, @Nullable DeNovoSpecialEvent.RegionSupply regions) {
        this.mapper = mapper; this.regions = regions;
    }

    public record Qualification(String sourceIdentity, String sourceClass, List<String> reasons,
                                 List<String> matchedConditions, String phenotype, String eventId) {
        public Qualification { reasons = List.copyOf(reasons); matchedConditions = List.copyOf(matchedConditions); }
    }
    public record Evaluation(String version, String contextSha256, String policySha256,
                             DeNovoEvidenceCalculator.Result result, List<Qualification> qualifications,
                             int repeatedSourceCount, List<String> blockers, @Nullable ObjectNode adoptionProof) {
        public Evaluation(String version, String contextSha256, String policySha256, Result result, List<Qualification> qualifications,
                          int repeatedSourceCount, List<String> blockers) {
            this(version, contextSha256, policySha256, result, qualifications, repeatedSourceCount, blockers, null);
        }
        public Evaluation { qualifications = List.copyOf(qualifications); blockers = List.copyOf(blockers); }
    }

    /** No public JSON context overload: the normal selected-source preparation must succeed first. */
    public Evaluation evaluate(SelectedEvidencePreparation preparation, String candidateId, String hypothesisId,
                               DeNovoApplicabilityPolicy policy, List<DeNovoStructuredReport> reports) {
        ObjectNode context = preparation.context(candidateId, hypothesisId);
        ObjectNode formal = preparation.formalCandidate(candidateId);
        return evaluateBound(context, formal, policy, reports);
    }

    // Package-local for a source-bound adapter and explicitly synthetic contract tests.
    Evaluation evaluateBound(ObjectNode context, ObjectNode formal, DeNovoApplicabilityPolicy policy,
                             List<DeNovoStructuredReport> reports) {
        return evaluateBound(context, formal, policy, reports, null);
    }
    Evaluation evaluateBound(ObjectNode context, ObjectNode formal, DeNovoApplicabilityPolicy policy,
                             List<DeNovoStructuredReport> reports, DeNovoLiteratureEvidence literature) {
        if (reports.size() > 100) throw new IllegalArgumentException("DENOVO_REPORT_LIMIT");
        JsonNode query = context.at("/variant/evidenceQueryAllele");
        String gene = context.at("/gene/hgncId").asText(), disease = context.at("/disease/mondoId").asText();
        String moi = inheritance(context.path("moi").asText());
        Group group = new Group(alleleKey(query), gene, disease, moi);
        List<String> blockers = contextIssues(context, query, literature == null);
        Map<String, DeNovoStructuredReport> distinct = new TreeMap<>();
        for (var report : reports) distinct.putIfAbsent(report.sha256(), report);
        String contextSha = new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(context);
        if (!blockers.isEmpty()) return new Evaluation(VERSION, contextSha, policy.sha256(),
                new Result(DeNovoEvidenceCalculator.VERSION, policy.id(), "NOT_EVALUATED", "NONE", "NONE",
                        blockers.get(0), 0, 0, 0, 0, false, List.of()), List.of(), reports.size() - distinct.size(), blockers);

        String patient = context.at("/patientContext/patientContextId").asText();
        String family = context.at("/patientContext/familyContextId").asText("");
        // Generated from trusted case context, not an input claim of independence.
        String eventId = "current:" + digest(mapper.createArrayNode().add(family).add(patient).add(group.alleleKey()).toString());
        List<DeNovoStructuredReport> eligible = new ArrayList<>();
        List<Qualification> qualifications = new ArrayList<>();
        List<String> sharedIssues = new ArrayList<>();
        for (DeNovoStructuredReport report : distinct.values()) {
            var raw = report.snapshot();
            List<String> issues = new ArrayList<>();
            if (!patient.equals(report.memberId("PROBAND")) || !family.equals(report.familyId())) issues.add("DENOVO_REPORT_PATIENT_OR_FAMILY_MISMATCH");
            if (family.isBlank()) issues.add("DENOVO_FAMILY_CONTEXT_REQUIRED");
            if ("SYNTHETIC".equals(report.sourceClass()) && !policy.permitsSyntheticReports()) issues.add("DENOVO_SYNTHETIC_REPORT_NOT_FOR_RESEARCH");
            JsonNode target = raw.path("target");
            boolean alleleFound = List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele").stream()
                    .anyMatch(key -> sameAllele(target, context.path("variant").path(key)));
            if (!alleleFound || !gene.equals(target.path("hgncId").asText())) issues.add("DENOVO_REPORT_TARGET_IDENTITY_MISMATCH");
            boolean identityMatches = issues.isEmpty();
            // These are conditions on this observation, not on independently reported literature cases.
            issues.addAll(currentCaseIssues(context));
            TargetTest proband = report.targetStatus("PROBAND");
            String publishedCall = formal.at("/analysisVcfCall/targetAlleleCall").asText();
            if (Set.of("HET_TARGET", "HOM_TARGET", "HEMI_TARGET", "TARGET_WITH_OTHER_ALT", "PARTIAL_TARGET").contains(publishedCall)
                    && proband == TargetTest.ABSENT_QUALIFIED) issues.add("DENOVO_REPORT_PUBLISHED_PROBAND_CONFLICT");
            if (Set.of("HOM_REF", "HEMI_REF", "OTHER_ALT_ONLY").contains(publishedCall) && proband == TargetTest.PRESENT)
                issues.add("DENOVO_REPORT_PUBLISHED_PROBAND_CONFLICT");
            if (identityMatches) {
                eligible.add(report);
                sharedIssues.addAll(currentCaseIssues(context));
                if (issues.contains("DENOVO_REPORT_PUBLISHED_PROBAND_CONFLICT")) sharedIssues.add("DENOVO_REPORT_PUBLISHED_PROBAND_CONFLICT");
            }
            qualifications.add(new Qualification(report.sha256(), report.sourceClass(), issues, List.of(), "PENDING_COMBINED_FACTS", eventId));
        }
        List<Observation> observations = new ArrayList<>();
        if (!eligible.isEmpty()) {
            List<String> issues = new ArrayList<>(new LinkedHashSet<>(sharedIssues));
            for (String role : List.of("MOTHER", "FATHER")) if (eligible.stream().map(r -> r.memberId(role)).filter(java.util.Objects::nonNull).distinct().count() > 1)
                issues.add("DENOVO_CROSS_REPORT_PARENT_IDENTITY_CONFLICT");
            boolean typedHypothesis = eligible.stream().map(mappedItem -> mappedItem.specialEvent()).anyMatch(java.util.Objects::nonNull);
            boolean compatibleBackgrounds = eligible.stream().allMatch(r -> Set.of(group.diseaseId(), "UNKNOWN").contains(r.clinical().reportedDiseaseId())
                    && Set.of(group.inheritance(), "UNKNOWN").contains(r.clinical().inheritance()));
            if (typedHypothesis && !compatibleBackgrounds) issues.add("DENOVO_REPORTED_DISEASE_OR_INHERITANCE_UNRESOLVED");
            var clinical = mergedClinical(eligible, group, typedHypothesis && compatibleBackgrounds);
            var match = typedHypothesis ? policy.matchHypothesis(group, clinical) : policy.match(group, clinical);
            if (policy.calculation().publishedPoints()) issues.add("DENOVO_PUBLISHED_CASE_SOURCE_NOT_IMPLEMENTED");
            else issues.addAll(match.issues());
            var special = mergedSpecial(eligible, query, group, policy);
            var verifiedSpecial = special.value();
            issues.addAll(special.issues());
            if (verifiedSpecial != null) issues.addAll(DeNovoTrioReconciliation.local(verifiedSpecial, eligible));
            if (verifiedSpecial == null && !"AD".equals(group.inheritance())) issues.add("DENOVO_SPECIAL_EVENT_REQUIRED");
            if (verifiedSpecial != null) {
                eventId = verifiedSpecial.eventId();
                String qualifiedEventId = eventId;
                var eligibleIds = eligible.stream().map(mappedItem -> mappedItem.sha256()).collect(java.util.stream.Collectors.toSet());
                qualifications.replaceAll(q -> eligibleIds.contains(q.sourceIdentity())
                        ? new Qualification(q.sourceIdentity(), q.sourceClass(), q.reasons(), q.matchedConditions(), q.phenotype(), qualifiedEventId) : q);
            }
            Facts facts = new Facts(group, mergedTarget(eligible, "PROBAND"), mergedTarget(eligible, "MOTHER"), mergedTarget(eligible, "FATHER"),
                    mergedParentage(eligible, "MOTHER"), mergedParentage(eligible, "FATHER"), match.phenotype(), familyHistory(clinical), verifiedSpecial);
            List<String> refs = new ArrayList<>(); refs.add(policy.sha256() + "#");
            for (var report : eligible) {
                refs.add(report.sha256() + "#"); report.tests().forEach(t -> refs.add(t.sourceRef()));
                report.parentage().forEach(p -> refs.add(p.sourceRef())); refs.add(report.clinical().sourceRef());
                if (report.specialEvent() != null) refs.add(report.sha256() + "#/specialEvent");
            }
            String observationId = "combined:" + digest(String.join(":", eligible.stream().map(mappedItem -> mappedItem.sha256()).sorted().toList()));
            observations.add(new Observation(observationId, eventId, SourceKind.CURRENT_CASE, facts, Independence.ESTABLISHED, refs, issues));
            qualifications.add(new Qualification(observationId, "COMBINED_LOCAL_REPORTS", issues, match.matchedConditions(), match.phenotype().name(), eventId));
        }
        if (literature != null) {
            var extra = literature.qualify(context, group, policy, observations, eligible, regions);
            observations.addAll(extra.observations()); qualifications.addAll(extra.qualifications());
        }
        var result = new DeNovoEvidenceCalculator().evaluate(group, policy.calculation(), observations);
        return new Evaluation(VERSION, contextSha, policy.sha256(), result, qualifications, reports.size() - distinct.size(), List.of(),
                DeNovoAdoptionProof.build(mapper, policy, observations, result));
    }

    private record SpecialRead(@Nullable DeNovoSpecialEvent.Verified value, List<String> issues) { }

    private SpecialRead mergedSpecial(List<DeNovoStructuredReport> reports, JsonNode allele, Group group,
                                      DeNovoApplicabilityPolicy policy) {
        JsonNode selected = null;
        DeNovoSpecialEvent.Verified verified = null;
        List<String> issues = new ArrayList<>();
        for (var report : reports) {
            JsonNode event = report.specialEvent();
            if (event == null) continue;
            if (!report.familyId().equals(event.path("familyId").asText())
                    || !java.util.Objects.equals(report.memberId("PROBAND"), event.path("probandId").asText()))
                issues.add("DENOVO_SPECIAL_EVENT_REPORT_BINDING_MISMATCH");
            String expectedPerson = report.memberId("MATERNAL_ORIGIN".equals(event.path("kind").asText()) ? "MOTHER" : "PROBAND");
            boolean boundPostzygoticPerson = "POSTZYGOTIC".equals(event.path("kind").asText())
                    && List.of("PROBAND", "MOTHER", "FATHER").stream()
                        .anyMatch(role -> java.util.Objects.equals(report.memberId(role), event.path("personId").asText()));
            if (!boundPostzygoticPerson && (expectedPerson == null || !expectedPerson.equals(event.path("personId").asText())))
                issues.add("DENOVO_SPECIAL_EVENT_REPORT_PERSON_MISMATCH");
            if (selected != null && !selected.equals(event)) issues.add("DENOVO_SPECIAL_EVENT_SOURCE_CONFLICT");
            var read = DeNovoSpecialEvent.read(event, report.sha256(), allele, group, policy.specialEvents(), regions);
            issues.addAll(read.issues());
            if (selected == null) { selected = event; verified = read.value(); }
        }
        return new SpecialRead(verified, List.copyOf(new LinkedHashSet<>(issues)));
    }

    private static TargetTest mergedTarget(List<DeNovoStructuredReport> reports, String role) {
        Set<TargetTest> states = new LinkedHashSet<>(); reports.forEach(r -> states.add(r.targetStatus(role)));
        Set<String> reportedResults = new LinkedHashSet<>();
        for (var report : reports) report.tests().stream().filter(t -> t.memberId().equals(report.memberId(role)))
                .forEach(t -> reportedResults.add(t.result()));
        if (reportedResults.contains("PRESENT") && reportedResults.contains("ABSENT")) return TargetTest.CONFLICT;
        if (states.contains(TargetTest.CONFLICT) || (states.contains(TargetTest.PRESENT) && states.contains(TargetTest.ABSENT_QUALIFIED))) return TargetTest.CONFLICT;
        if (states.contains(TargetTest.QUALITY_FAILED)) return TargetTest.QUALITY_FAILED;
        if (states.contains(TargetTest.PRESENT)) return TargetTest.PRESENT;
        if (states.contains(TargetTest.ABSENT_QUALIFIED)) return TargetTest.ABSENT_QUALIFIED;
        return TargetTest.UNKNOWN;
    }
    private static Parentage mergedParentage(List<DeNovoStructuredReport> reports, String role) {
        Set<Parentage> states = new LinkedHashSet<>(); reports.forEach(r -> states.add(r.parentageStatus(role)));
        if (states.contains(Parentage.EXCLUDED)) return Parentage.EXCLUDED;
        states.remove(Parentage.ASSUMED_NOT_REPORTED);
        if (states.isEmpty()) return Parentage.ASSUMED_NOT_REPORTED;
        if (states.size() > 1 || states.contains(Parentage.INCONCLUSIVE)) return Parentage.INCONCLUSIVE;
        return states.iterator().next();
    }
    private static DeNovoStructuredReport.ClinicalFacts mergedClinical(List<DeNovoStructuredReport> reports, Group group,
                                                                      boolean allowUnknownBackground) {
        var matching = reports.stream().map(mappedItem -> mappedItem.clinical()).filter(c -> group.diseaseId().equals(c.reportedDiseaseId())
                && group.inheritance().equals(c.inheritance())).toList();
        Set<String> present = new LinkedHashSet<>(), absent = new LinkedHashSet<>();
        reports.forEach(r -> { present.addAll(r.clinical().presentHpo()); absent.addAll(r.clinical().absentHpo()); });
        Set<String> histories = new LinkedHashSet<>(), siblings = new LinkedHashSet<>(), mosaics = new LinkedHashSet<>();
        var historyReports = allowUnknownBackground ? reports.stream().map(mappedItem -> mappedItem.clinical()).toList() : matching;
        historyReports.forEach(c -> histories.add(c.familyHistory()));
        reports.forEach(r -> { mosaics.add(r.clinical().mosaicism()); siblings.add(r.clinical().siblingRecurrence()); });
        String history = histories.contains("CONFLICT") || (histories.contains("NO_SIMILAR_HISTORY") && histories.contains("SIMILAR_HISTORY"))
                ? "CONFLICT" : histories.contains("SIMILAR_HISTORY") ? "SIMILAR_HISTORY" : histories.contains("NO_SIMILAR_HISTORY") ? "NO_SIMILAR_HISTORY" : "UNKNOWN";
        String sibling = siblings.contains("PRESENT") ? "PRESENT" : siblings.contains("NONE_REPORTED") ? "NONE_REPORTED" : "UNKNOWN";
        String mosaic = mosaics.contains("KNOWN") ? "KNOWN" : mosaics.contains("SUSPECTED") ? "SUSPECTED" : "NOT_REPORTED";
        return new DeNovoStructuredReport.ClinicalFacts(matching.isEmpty() ? "UNKNOWN" : group.diseaseId(), matching.isEmpty() ? "UNKNOWN" : group.inheritance(),
                List.copyOf(present), List.copyOf(absent), history, mosaic, sibling, "COMBINED_SOURCE_REFERENCES");
    }

    private static FamilyHistory familyHistory(DeNovoStructuredReport.ClinicalFacts c) {
        if (Set.of("SUSPECTED", "KNOWN").contains(c.mosaicism()) || "PRESENT".equals(c.siblingRecurrence())) return FamilyHistory.MOSAIC_OR_SIBLING_RECURRENCE;
        if ("CONFLICT".equals(c.familyHistory())) return FamilyHistory.CONFLICT;
        if (!"NO_SIMILAR_HISTORY".equals(c.familyHistory()) || "UNKNOWN".equals(c.siblingRecurrence())) return FamilyHistory.UNKNOWN;
        return FamilyHistory.COMPATIBLE;
    }
    static List<String> contextIssues(JsonNode context, JsonNode query) {
        return contextIssues(context, query, true);
    }
    static List<String> contextIssues(JsonNode context, JsonNode query, boolean requireCurrentCase) {
        List<String> issues = new ArrayList<>();
        String moi = inheritance(context.path("moi").asText());
        String contig = CoordinateUtils.normalizeChromosome(query.path("contig").asText());
        boolean autosome = contig != null && contig.matches("([1-9]|1[0-9]|2[0-2])");
        boolean supportedAssembly = "GRCh38".equals(query.path("assembly").asText())
                || (!"AD".equals(moi) && "GRCh37".equals(query.path("assembly").asText()));
        if (!supportedAssembly || ("X_LINKED".equals(moi) ? !"X".equals(contig) : !autosome))
            issues.add("DENOVO_ASSEMBLY_OR_CHROMOSOME_NOT_SUPPORTED");
        String ref = query.path("reference").asText(), alt = query.path("alternate").asText();
        if (!ref.matches("[ACGT]{1,50}") || !alt.matches("[ACGT]{1,50}") || ref.equals(alt)
                || (ref.length() > 1 && alt.length() > 1)) issues.add("DENOVO_VARIANT_TYPE_NOT_SUPPORTED");
        if (!Set.of("AD", "AR", "X_LINKED").contains(moi)) issues.add("DENOVO_INHERITANCE_NOT_SUPPORTED");
        if (!"GERMLINE".equals(context.at("/scope/analysisDomain").asText())) issues.add("DENOVO_GERMLINE_CONTEXT_REQUIRED");
        if (context.at("/patientContext/patientContextId").asText().isBlank()) issues.add("DENOVO_PATIENT_CONTEXT_REQUIRED");
        if (requireCurrentCase) issues.addAll(currentCaseIssues(context));
        return List.copyOf(issues);
    }
    private static List<String> currentCaseIssues(JsonNode context) {
        List<String> issues = new ArrayList<>();
        if (!"PROBAND".equals(context.at("/patientContext/role").asText())) issues.add("DENOVO_PROBAND_CONTEXT_REQUIRED");
        if (!"AFFECTED".equals(context.at("/patientContext/affectedStatus").asText())) issues.add("DENOVO_AFFECTED_STATUS_REQUIRED");
        if (context.at("/patientContext/familyContextId").asText("").isBlank()) issues.add("DENOVO_FAMILY_CONTEXT_REQUIRED");
        return List.copyOf(issues);
    }
    static String inheritance(String moi) {
        return switch (moi) {
            case "AUTOSOMAL_DOMINANT", "AD" -> "AD";
            case "AUTOSOMAL_RECESSIVE", "AR" -> "AR";
            case "X_LINKED_DOMINANT", "X_LINKED_RECESSIVE", "X_LINKED" -> "X_LINKED";
            default -> moi;
        };
    }
    private static boolean sameAllele(JsonNode a, JsonNode b) {
        if (!a.isObject() || !b.isObject()) return false;
        for (String field : List.of("assembly", "reference", "alternate")) if (!a.path(field).equals(b.path(field))) return false;
        return a.path("position").isIntegralNumber() && b.path("position").isIntegralNumber()
                && a.path("position").longValue() == b.path("position").longValue()
                && java.util.Objects.equals(CoordinateUtils.normalizeChromosome(a.path("contig").asText()),
                CoordinateUtils.normalizeChromosome(b.path("contig").asText()));
    }
    private static String alleleKey(JsonNode allele) {
        return allele.path("assembly").asText() + ":" + allele.path("contig").asText() + ":" + allele.path("position").asLong()
                + ":" + allele.path("reference").asText() + ":" + allele.path("alternate").asText();
    }
    private static String digest(String value) { return FamilyDeNovoJson.sha256(value.getBytes(StandardCharsets.UTF_8)); }
}
