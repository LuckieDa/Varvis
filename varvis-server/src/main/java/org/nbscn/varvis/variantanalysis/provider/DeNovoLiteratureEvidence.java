package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.lang.Nullable;
import static org.nbscn.varvis.variantanalysis.provider.DeNovoEvidenceCalculator.*;

/** Only accepts replayed source-reader output from the configured offline producer. */
final class DeNovoLiteratureEvidence {
    static final String VERSION = "denovo-literature-evidence-1.9.0";
    static final String REVIEWED_PAPER = "denovo-reviewed-paper-v1";
    private static final Set<String> REVIEWED_TARGET_METHODS = Set.of("SANGER", "TARGETED_SEQUENCING", "TARGETED_GENOTYPING", "EXOME", "GENOME");
    private final DeNovoLiteratureReader.Read source;
    DeNovoLiteratureEvidence(DeNovoLiteratureReader.Read source) { this.source = source; }
    void retainReviewed(DeNovoLiteratureReader reader) { reader.retainReviewed(source); }
    void appendCoreMaterials(ObjectNode out, ObjectNode context) {
        if (source.issue() == null && source.document() != null)
            DeNovoResultSummary.appendCoreMaterials(out, source.document(), context);
    }
    record Qualified(List<Observation> observations, List<DeNovoLocalEvidenceEvaluator.Qualification> qualifications) {}

    Qualified qualify(ObjectNode context, Group group, DeNovoApplicabilityPolicy policy,
                      List<Observation> current, List<DeNovoStructuredReport> currentReports) {
        return qualify(context, group, policy, current, currentReports, null);
    }

    Qualified qualify(ObjectNode context, Group group, DeNovoApplicabilityPolicy policy,
                      List<Observation> current, List<DeNovoStructuredReport> currentReports,
                      @Nullable DeNovoSpecialEvent.RegionSupply regions) {
        List<Observation> observations = new ArrayList<>();
        List<DeNovoLocalEvidenceEvaluator.Qualification> qualifications = new ArrayList<>();
        if (source.issue() != null) {
            qualifications.add(new DeNovoLocalEvidenceEvaluator.Qualification("literature-reader", "LITERATURE_CASE",
                    List.of(source.issue()), List.of(), "UNKNOWN", "UNRESOLVED"));
            return new Qualified(observations, qualifications);
        }
        var root = source.document(); var candidates = new ArrayList<Candidate>(); int d = 0;
        for (var document : root.path("documents")) {
            if (!"SOURCE_READ".equals(document.path("status").asText())) {
                qualifications.add(new DeNovoLocalEvidenceEvaluator.Qualification(document.path("sourceId").asText(), "LITERATURE_CASE",
                        List.of(document.path("reasonCode").asText("DENOVO_LITERATURE_SOURCE_UNAVAILABLE")), List.of(), "UNKNOWN", "UNRESOLVED")); d++; continue;
            }
            var facts = document.path("facts"); int c = 0;
            for (var issue : facts.path("issues")) {
                String reason = issue.isTextual() ? issue.asText() : issue.path("reasonCode").asText("DENOVO_LITERATURE_SOURCE_UNRESOLVED");
                qualifications.add(new DeNovoLocalEvidenceEvaluator.Qualification(document.path("sourceId").asText(), "LITERATURE_CASE",
                        List.of(reason), List.of(), "UNKNOWN", "UNRESOLVED"));
            }
            for (var row : facts.path("cases")) {
                var identity = row.path("alleleIdentity");
                boolean sameGene = group.hgncId().equals(identity.path("hgncId").asText());
                boolean sameAllele = identity.path("allele").equals(context.at("/variant/evidenceQueryAllele"));
                if (sameGene && sameAllele) candidates.add(new Candidate(facts.path("pmcid").asText(), facts.path("sourceSha256").asText(), row,
                        source.sha256() + "#/documents/" + d + "/facts/cases/" + c));
                else if ("denovo-bundle-case-qualification-1.0.0".equals(facts.path("methodVersion").asText())) {
                    var reasons = new ArrayList<String>();
                    identity.path("issues").forEach(i -> reasons.add(i.asText()));
                    row.path("coreEvaluation").path("blockers").forEach(b -> reasons.add("DENOVO_CORE:" + b.path("category").asText() + ":" + b.path("path").asText() + ":" + b.path("code").asText()));
                    row.path("issues").forEach(i -> reasons.add(i.asText()));
                    if (reasons.isEmpty()) reasons.add(sameGene ? "DENOVO_LITERATURE_DIFFERENT_ALLELE"
                            : "DENOVO_LITERATURE_DIFFERENT_GENE");
                    qualifications.add(new DeNovoLocalEvidenceEvaluator.Qualification(
                            source.sha256() + "#/documents/" + d + "/facts/cases/" + c, "LITERATURE_CASE",
                            List.copyOf(new LinkedHashSet<>(reasons)), List.of(), "UNKNOWN", "UNRESOLVED"));
                }
                c++;
            }
            d++;
        }
        // One positively established primary case can contribute alone. Multiple
        // independent occurrences must not be invented from paper/case counts.
        boolean independentCases = allIndependent(candidates, group);
        var eventPolicy = policy.specialEvents();
        boolean publishedPoints = eventPolicy != null && eventPolicy.publishedPoints();
        Map<String, DeNovoCoreFacts.Read> coreReads = new HashMap<>();
        String commonAuthority = null;
        boolean allTypedWithinAuthority = true, anyTyped = false;
        for (var candidate : candidates) {
            var core = !publishedPoints && candidate.row.has("coreEvaluation") ? DeNovoCoreFacts.read(candidate.row.path("coreEvaluation"),
                    context.at("/variant/evidenceQueryAllele"), group, policy, regions) : null;
            if (core != null) coreReads.put(candidate.ref, core);
            if (core == null || core.facts().special() == null) { allTypedWithinAuthority = false; continue; }
            anyTyped = true;
            String authority = core.eventAuthority();
            if (authority == null) allTypedWithinAuthority = false;
            else if (commonAuthority == null) commonAuthority = authority;
            else if (!commonAuthority.equals(authority)) allTypedWithinAuthority = false;
        }
        boolean identityScopeUnresolved = candidates.size() > 1 && anyTyped && !allTypedWithinAuthority;
        boolean currentContributes = current.stream().anyMatch(o -> o.qualificationIssues().isEmpty()
                && !o.sourceRefs().isEmpty() && o.independence() == Independence.ESTABLISHED
                && DeNovoEvidenceCalculator.prerequisite(eventFacts(o), policy.calculation()) == null);
        int index = 0;
        for (var candidate : candidates) {
            JsonNode row = candidate.row; List<String> issues = new ArrayList<>();
            JsonNode reviewed = row.path("reviewedCase");
            boolean reviewedPaper = REVIEWED_PAPER.equals(reviewed.path("schemaVersion").asText());
            row.path("issues").forEach(i -> issues.add(i.asText()));
            var features = new ArrayList<String>();
            if (!publishedPoints) for (var f : row.path("fields"))
                if ("reportedPhenotype".equals(f.path("field").asText()) && "REPORTED".equals(f.path("findingState").asText()))
                    features.addAll(Arrays.asList(f.path("sourceValue").asText().split("[,;]")));
            var negatives = new ArrayList<String>();
            if (reviewedPaper) {
                reviewed.path("phenotype").forEach(f -> features.add(f.asText()));
                reviewed.path("phenotypeNegatives").forEach(f -> negatives.add(f.asText()));
            }
            var phenotype = publishedPoints && reviewedPaper
                    ? policy.matchLiterature(group, features, negatives, reviewed.path("publishedConditions"))
                    : policy.matchLiterature(group, features, negatives);
            if (publishedPoints && !reviewedPaper) issues.add("DENOVO_PUBLISHED_CASE_SOURCE_NOT_IMPLEMENTED");
            else issues.addAll(phenotype.issues());
            var core = coreReads.get(candidate.ref);
            if (core != null) {
                issues.clear();
                // The source-replayed core independently resolves identity and
                // excludes unowned model claims. Preserve them in provenance,
                // but do not veto unrelated verified facts. Real conflicts and
                // every unknown issue still block; incomplete core cannot waive any.
                // A qualified core also replaces the legacy named-assay parser's
                // unresolved target-test flag; it never replaces a source conflict.
                for (var issue : row.path("issues")) if (!core.issues().isEmpty() || !Set.of(
                        "LITERATURE_ALLELE_ALIAS_NOT_FOUND",
                        "LITERATURE_CODING_ALIAS_UNSUPPORTED",
                        "LITERATURE_PRIMARY_CASE_SECTION_UNRESOLVED",
                        "LITERATURE_PRIMARY_COHORT_NOT_ESTABLISHED",
                        "LITERATURE_PARENTAL_TARGET_TESTS_NOT_RESOLVED",
                        "LITERATURE_PARENTAGE_NOT_RESOLVED",
                        "LITERATURE_FIELD_CONFLICT:BUNDLE_SUBJECT_NOT_VERIFIED").contains(issue.asText()))
                    issues.add(issue.asText());
                issues.addAll(core.issues()); phenotype = core.phenotype();
            }
            boolean target = qualifiedTargetTesting(row.path("targetTesting"));
            String parentageStatus = row.at("/parentage/status").asText();
            Parentage parentage = switch (parentageStatus) {
                case "PUBLISHED_BOTH_PARENTS_CONFIRMED" -> Parentage.CONFIRMED;
                case "PUBLISHED_PARENTAGE_NOT_TESTED" -> Parentage.ASSUMED_NOT_TESTED;
                case "PUBLISHED_PARENTAGE_NOT_REPORTED" -> Parentage.ASSUMED_NOT_REPORTED;
                case "PUBLISHED_PARENTAGE_NOT_CONFIRMED" -> Parentage.ASSUMED_NOT_CONFIRMED;
                default -> Parentage.INCONCLUSIVE;
            };
            if (reviewedPaper) {
                if (!group.diseaseId().equals(reviewed.path("diseaseId").asText())) issues.add("DENOVO_CORE_BACKGROUND_MISMATCH");
                if (!"AD".equals(group.inheritance()) || eventPolicy != null && !eventPolicy.rasopathyPoints()
                        && !eventPolicy.phenotypeOnly() && !publishedPoints)
                    issues.add("DENOVO_SPECIAL_EVENT_REQUIRED");
            } else if (core == null && !publishedPoints) {
                if (!target) issues.add("DENOVO_LITERATURE_PARENTAL_TARGET_TESTS_REQUIRED");
                if (parentage == Parentage.INCONCLUSIVE) issues.add("DENOVO_LITERATURE_PARENTAGE_UNRESOLVED");
                if (!"SUPPORTED_EXPLICIT_FAMILY_STATEMENTS".equals(row.path("contextVerification").asText()))
                    issues.add("DENOVO_LITERATURE_CLINICAL_CONTEXT_NOT_VERIFIED");
            }
            String key = candidate.key();
            String eventId = "literature:" + FamilyDeNovoJson.sha256((key + ":" + group.alleleKey()).getBytes(StandardCharsets.UTF_8));
            var verified = core == null ? null : core.facts().special();
            boolean special = verified != null;
            if (verified != null && core != null) eventId = core.eventId();
            else if (core != null && !eventId.equals(core.eventId())) issues.add("DENOVO_CORE_EVENT_IDENTITY_MISMATCH");
            if (!"AD".equals(group.inheritance()) && !special) issues.add("DENOVO_SPECIAL_EVENT_REQUIRED");
            Independence independence = special || independentCases ? Independence.ESTABLISHED : Independence.POSSIBLE_OVERLAP;
            if (!special && !independentCases) issues.add("DENOVO_LITERATURE_BETWEEN_CASE_INDEPENDENCE_UNRESOLVED");
            if (identityScopeUnresolved) {
                independence = Independence.POSSIBLE_OVERLAP;
                issues.add("DENOVO_LITERATURE_EVENT_IDENTITY_SCOPE_UNRESOLVED");
            }
            var refs = new ArrayList<String>(); refs.add(candidate.ref); refs.add(policy.sha256() + "#");
            if (reviewedPaper) reviewed.path("followUpReports").forEach(report -> refs.add(report.path("sourceSha256").asText() + "#"));
            if (reviewedPaper && independentCases) for (var peer : candidates) {
                if (candidate.key().equals(peer.key())) continue;
                int relation = independentRelation(candidate, peer);
                if (relation >= 0) refs.add(candidate.ref + "/reviewedCase/independentOf/" + relation);
            }
            if (verified != null) refs.addAll(verified.sourceRefs());
            if (!current.isEmpty()) {
                Set<String> relationships = new HashSet<>();
                for (var report : currentReports) {
                    int ri = 0;
                    for (var relation : report.snapshot().path("literatureRelationships")) {
                        if (candidate.pmcid.equals(relation.path("pmcid").asText()) && row.path("caseLabel").equals(relation.path("caseLabel"))) {
                            relationships.add(relation.path("relationship").asText()); refs.add(report.sha256() + "#/literatureRelationships/" + ri);
                        }
                        ri++;
                    }
                }
                if (relationships.equals(Set.of("SAME_CASE")) && current.size() == 1) {
                    if (special || current.get(0).facts().special() != null) {
                        independence = Independence.POSSIBLE_OVERLAP;
                        issues.add("DENOVO_CURRENT_AND_LITERATURE_EVENT_MAPPING_UNRESOLVED");
                    } else eventId = current.get(0).eventId();
                    if ("UNAFFECTED".equals(context.at("/patientContext/affectedStatus").asText()))
                        issues.add("DENOVO_LINKED_CURRENT_CASE_STATUS_CONFLICT");
                    // A source conflict cannot disappear merely because the current
                    // observation is excluded before occurrence deduplication.
                    if (current.get(0).qualificationIssues().stream().anyMatch(i -> i.contains("CONFLICT")))
                        issues.add("DENOVO_LINKED_CURRENT_CASE_SOURCE_CONFLICT");
                }
                else if (relationships.contains("CONFLICT") || relationships.size() > 1
                        || (currentContributes && !relationships.equals(Set.of("UNRELATED_CASE")))) {
                    independence = Independence.POSSIBLE_OVERLAP; issues.add("DENOVO_CURRENT_AND_LITERATURE_RELATION_UNRESOLVED");
                }
            }
            var facts = core != null ? core.facts() : reviewedPaper ? reviewedFacts(group, reviewed, phenotype.phenotype()) :
                    new Facts(group, target ? TargetTest.PRESENT : TargetTest.UNKNOWN,
                    target ? TargetTest.ABSENT_QUALIFIED : TargetTest.UNKNOWN, target ? TargetTest.ABSENT_QUALIFIED : TargetTest.UNKNOWN,
                    parentage, parentage,
                    phenotype.phenotype(), "REPORTED_NONE".equals(row.path("familyHistory").asText()) ? FamilyHistory.COMPATIBLE : FamilyHistory.UNKNOWN);
            if (reviewedPaper && eventPolicy != null && publishedPoints)
                facts = publishedFacts(context, reviewed, facts, eventPolicy, issues);
            String observationId = source.sha256() + ":" + index++;
            observations.add(new Observation(observationId, eventId, SourceKind.LITERATURE_CASE, facts, independence, refs, issues));
            qualifications.add(new DeNovoLocalEvidenceEvaluator.Qualification(candidate.ref, "LITERATURE_CASE", issues,
                    phenotype.matchedConditions(), phenotype.phenotype().name(), eventId));
        }
        if (candidates.isEmpty()) qualifications.add(new DeNovoLocalEvidenceEvaluator.Qualification(source.sha256(), "LITERATURE_CASE",
                List.of("DENOVO_LITERATURE_NO_RESOLVED_TARGET_CASE_IN_LOADED_SOURCES"), List.of(), "UNKNOWN", "UNRESOLVED"));
        return new Qualified(List.copyOf(observations), List.copyOf(qualifications));
    }

    private static boolean allIndependent(List<Candidate> candidates, Group group) {
        for (int i = 0; i < candidates.size(); i++) for (int j = i + 1; j < candidates.size(); j++) {
            var first = candidates.get(i); var second = candidates.get(j);
            if (first.key().equals(second.key())) continue;
            // A declared relation needs its separately replayed counterpart and
            // applies only to these two source revisions, cases and background.
            if (!group.diseaseId().equals(first.reviewed().path("diseaseId").asText())
                    || !group.diseaseId().equals(second.reviewed().path("diseaseId").asText())
                    || independentRelation(first, second) < 0 || independentRelation(second, first) < 0
                    || first.referencesFollowUp(second) || second.referencesFollowUp(first)) return false;
        }
        return true;
    }

    private static int independentRelation(Candidate source, Candidate peer) {
        if (!REVIEWED_PAPER.equals(source.reviewed().path("schemaVersion").asText())) return -1;
        int index = 0;
        for (var relation : source.reviewed().path("independentOf")) {
            if (peer.matches(relation) && relation.path("citations").isArray() && !relation.path("citations").isEmpty()) return index;
            index++;
        }
        return -1;
    }

    private static Facts reviewedFacts(Group group, JsonNode reviewed, Phenotype phenotype) {
        var family = switch (reviewed.path("family").asText()) {
            case "MATERNALLY_INHERITED", "PATERNALLY_INHERITED" -> FamilyHistory.INHERITED;
            case "COMPATIBLE" -> FamilyHistory.COMPATIBLE;
            case "CONFLICT" -> FamilyHistory.CONFLICT;
            case "SPECIAL_MODEL" -> FamilyHistory.MOSAIC_OR_SIBLING_RECURRENCE;
            default -> FamilyHistory.UNKNOWN;
        };
        return new Facts(group, reviewedTarget(reviewed.at("/members/proband")),
                reviewedTarget(reviewed.at("/members/mother")), reviewedTarget(reviewed.at("/members/father")),
                DeNovoCoreFacts.relation(reviewed.at("/parentage/maternity")),
                DeNovoCoreFacts.relation(reviewed.at("/parentage/paternity")), phenotype, family);
    }

    private static TargetTest reviewedTarget(JsonNode member) {
        return DeNovoCoreFacts.target(member.path("target"), REVIEWED_TARGET_METHODS.contains(member.path("method").asText()));
    }

    private static Facts publishedFacts(JsonNode context, JsonNode reviewed, Facts facts, DeNovoEventPolicy policy, List<String> issues) {
        if (!facts.hasNegativeParentalTests()) return facts;
        var conditions = reviewed.path("publishedConditions");
        Parentage maternity = facts.maternity(), paternity = facts.paternity();
        // The policy can recognize a separately source-bound trio assay. An
        // explicit exclusion or an inconclusive relationship still controls.
        if (policy.trioExomeConfirmsParentage() && conditions.path("trioExome").asBoolean(false)
                && permitsTrioConfirmation(maternity) && permitsTrioConfirmation(paternity)) {
            maternity = Parentage.CONFIRMED;
            paternity = Parentage.CONFIRMED;
        }
        FamilyHistory family = facts.familyHistory();
        if (facts.phenotype() != Phenotype.UNKNOWN && facts.phenotype() != Phenotype.NOT_CONSISTENT) {
            var transcript = context.path("transcript");
            String selectedTranscript = transcript.path("accession").asText() + "." + transcript.path("version").asText();
            var identity = reviewed.path("identityProof");
            if (!identity.path("coding").asText().matches("c\\.[1-9][0-9]*[ACGT]>[ACGT]")
                    || !(selectedTranscript.equals(identity.path("ensemblTranscript").asText())
                    || selectedTranscript.equals(identity.path("transcript").asText())))
                issues.add("DENOVO_PUBLISHED_CODING_VARIANT_BASIS_REQUIRED");
            var findings = DeNovoApplicabilityPolicy.publishedFindings(conditions);
            var statuses = policy.requiredAbsentFindings().stream().map(key -> findings.getOrDefault(key, "UNKNOWN")).toList();
            if (statuses.contains("CONFLICT") || family == FamilyHistory.CONFLICT) {
                family = FamilyHistory.CONFLICT;
                issues.add("DENOVO_PUBLISHED_FAMILY_HISTORY_CONFLICT");
            } else if (family == FamilyHistory.COMPATIBLE || family == FamilyHistory.UNKNOWN) {
                if (statuses.contains("PRESENT")) family = FamilyHistory.RELEVANT_HISTORY_PRESENT;
                else if (statuses.contains("UNKNOWN")) {
                    family = FamilyHistory.UNKNOWN;
                    issues.add("DENOVO_PUBLISHED_FAMILY_HISTORY_REQUIRED");
                } else family = FamilyHistory.COMPATIBLE;
            }
        }
        return new Facts(facts.group(), facts.proband(), facts.mother(), facts.father(), maternity, paternity, facts.phenotype(), family);
    }

    private static boolean permitsTrioConfirmation(Parentage parentage) {
        return parentage == Parentage.CONFIRMED || parentage == Parentage.ASSUMED_NOT_TESTED || parentage == Parentage.ASSUMED_NOT_REPORTED;
    }

    private static boolean qualifiedTargetTesting(JsonNode testing) {
        if ("PUBLISHED_TRIO_SANGER_DENOVO".equals(testing.path("status").asText())) return true;
        // This is source-reader output, never a model's free-form method label.
        // Target results do not establish parentage or compatible family context.
        return "PUBLISHED_TARGETED_PARENTAL_TESTING_DENOVO".equals(testing.path("status").asText())
                && "EXPLICIT_COHORT_TARGET_RESULTS_PLUS_CASE_SPECIFIC_METHOD".equals(testing.path("basis").asText())
                && Set.of("SANGER", "TRIO_EXOME", "TRIO_GENOME").contains(testing.path("method").asText())
                && "REPORTED_PRESENT".equals(testing.path("proband").asText())
                && "REPORTED_ABSENT".equals(testing.path("mother").asText())
                && "REPORTED_ABSENT".equals(testing.path("father").asText())
                && testing.path("citations").isArray() && testing.path("citations").size() == 2;
    }
    private static Facts eventFacts(Observation observation) {
        var special = observation.facts().special();
        return special == null ? observation.facts() : special.eventFacts(observation.facts());
    }
    private record Candidate(String pmcid, String paperSha256, JsonNode row, String ref) {
        String key() { return pmcid + ":" + row.path("caseLabel").asText(); }
        JsonNode reviewed() { return row.path("reviewedCase"); }
        boolean matches(JsonNode relation) {
            return pmcid.equals(relation.path("pmcid").asText()) && row.path("caseLabel").equals(relation.path("caseLabel"))
                    && paperSha256.matches("[0-9a-f]{64}") && paperSha256.equals(relation.path("sourceSha256").asText());
        }
        boolean referencesFollowUp(Candidate peer) {
            for (var followUp : reviewed().path("followUpReports")) {
                if (peer.pmcid.equals(followUp.path("pmcid").asText()) && peer.row.path("caseLabel").equals(followUp.path("caseLabel")))
                    return true;
            }
            return false;
        }
    }
}
