package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import static org.nbscn.varvis.variantanalysis.provider.DeNovoEvidenceCalculator.*;

/** Retained structured source facts. Construction proves binding and types;
 * scientific prerequisites are evaluated separately, including unresolved facts. */
final class DeNovoSpecialEvent {
    static final String VERSION = "denovo-special-event-1.0.0";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final FamilyDeNovoJson JSON = new FamilyDeNovoJson("DENOVO_SPECIAL_");
    private static final Set<String> METHODS = Set.of("SANGER", "TARGETED_SEQUENCING", "EXOME", "GENOME");
    interface RegionSupply { RegionFact resolve(JsonNode allele); }
    record RegionFact(String assembly, String alleleSha256, String region, String sourceSha256, String reasonCode) {
        RegionFact(String assembly, String alleleSha256, String region, String sourceSha256) { this(assembly, alleleSha256, region, sourceSha256, null); }
    }
    record Read(Verified value, List<String> issues) { Read { issues = List.copyOf(issues); } }

    record FieldSource(String fieldPath, String sourceSha256, String role, String kind, String locator) { }
    record TissueAssessment(boolean c1, boolean c2, int level, int affectedIndex, int comparisonIndex) { }

    static final class Verified {
        private final ObjectNode raw;
        private final String eventId, sourceSha, regionIssue, region;
        private final Group group;
        private final boolean partnerVerified;
        private final List<FieldSource> originalFields;
        private final String factRole;
        private Verified(ObjectNode raw, Group group, String eventId, String sourceSha, String regionIssue, String region, boolean partnerVerified) {
            this(raw, group, eventId, sourceSha, regionIssue, region, partnerVerified, List.of(), "LOCAL_REPORT");
        }
        private Verified(ObjectNode raw, Group group, String eventId, String sourceSha, String regionIssue, String region,
                         boolean partnerVerified, List<FieldSource> originalFields, String factRole) {
            this.originalFields = List.copyOf(originalFields); this.factRole = factRole;
            this.partnerVerified = partnerVerified;
            this.raw = raw.deepCopy(); this.group = group; this.eventId = eventId; this.sourceSha = sourceSha;
            this.regionIssue = regionIssue; this.region = region;
        }
        String eventId() { return eventId; }
        String familyId() { return raw.path("familyId").asText(); }
        String personId() { return raw.path("personId").asText(); }
        String probandId() { return raw.path("probandId").asText(); }
        String kind() { return raw.path("kind").asText(); }
        ObjectNode snapshot() { return raw.deepCopy(); }
        List<String> sourceRefs() { return List.of(sourceSha + "#/specialEvent"); }
        Verified withCoreOrigins(List<FieldSource> fields, String role) {
            return new Verified(raw, group, eventId, sourceSha, regionIssue, region, partnerVerified, fields, role);
        }
        String sourceSha256() { return sourceSha; }
        String factRole() { return factRole; }
        List<FieldSource> originalFields() { return originalFields; }
        String partnerStatus() { return raw.at("/partner/status").asText(); }
        boolean recurrence() { return "SIBLING_RECURRENCE".equals(kind()); }
        Facts eventFacts(Facts base) {
            var t = raw.path("trio");
            return new Facts(group, target(t.path("subjectTarget").asText()), target(t.path("motherTarget").asText()),
                    target(t.path("fatherTarget").asText()), relation("maternity"), relation("paternity"), base.phenotype(),
                    base.familyHistory(), this);
        }
        private TargetTest target(String value) {
            if ("CONFLICT".equals(value) || "CONFLICT".equals(raw.at("/trio/quality").asText())) return TargetTest.CONFLICT;
            if ("FAILED".equals(raw.at("/trio/quality").asText())) return TargetTest.QUALITY_FAILED;
            boolean adequate = METHODS.contains(raw.at("/trio/method").asText())
                    && "EXPLICIT_TARGET".equals(raw.at("/trio/targetCoverage").asText())
                    && "ADEQUATE".equals(raw.at("/trio/detectionCapability").asText())
                    && "VALID".equals(raw.at("/trio/quality").asText());
            if (!adequate) return TargetTest.UNKNOWN;
            return "PRESENT".equals(value) ? TargetTest.PRESENT : "ABSENT".equals(value) ? TargetTest.ABSENT_QUALIFIED : TargetTest.UNKNOWN;
        }
        private Parentage relation(String role) {
            String status = raw.path("trio").path(role).asText(), method = raw.path("trio").path(role + "Method").asText();
            Parentage result = Parentage.valueOf(status);
            if (result == Parentage.CONFIRMED && !Set.of("SNP_PANEL", "STR_PANEL", "SEQUENCING_KINSHIP").contains(method)) return Parentage.INCONCLUSIVE;
            if (result == Parentage.ASSUMED_NOT_TESTED && !"NOT_TESTED".equals(method)
                    || result == Parentage.ASSUMED_NOT_REPORTED && !"NOT_REPORTED".equals(method)) return Parentage.INCONCLUSIVE;
            return result;
        }
        List<String> prerequisites(Facts facts, DeNovoEventPolicy policy) {
            var out = new ArrayList<String>();
            if (policy == null) return List.of("DENOVO_SPECIAL_POLICY_REQUIRED");
            if (regionIssue != null) out.add(regionIssue);
            if (facts.familyHistory() == FamilyHistory.CONFLICT) out.add("DENOVO_FAMILY_HISTORY_CONFLICT");
            if (facts.familyHistory() == FamilyHistory.MOSAIC_OR_SIBLING_RECURRENCE && !recurrence() && !"POSTZYGOTIC".equals(kind()))
                out.add("DENOVO_SPECIAL_FAMILY_KIND_CONFLICT");
            if (facts.familyHistory() == FamilyHistory.UNKNOWN && "CONSTITUTIONAL".equals(kind()))
                out.add("DENOVO_FAMILY_HISTORY_UNRESOLVED");
            if (!personId().equals(raw.at("/trio/personId").asText())) out.add("DENOVO_EVENT_PERSON_TRIO_CONFLICT");
            if (!"MATERNAL_ORIGIN".equals(kind()) && !personId().equals(probandId()))
                out.add("POSTZYGOTIC".equals(kind()) ? "DENOVO_PARENTAL_POSTZYGOTIC_RULE_NOT_IMPLEMENTED" : "DENOVO_EVENT_PROBAND_IDENTITY_CONFLICT");
            if ("UNKNOWN".equals(raw.path("affectedStatus").asText())) out.add("DENOVO_EVENT_AFFECTED_STATUS_REQUIRED");
            if (!"MATERNAL_ORIGIN".equals(kind()) && "UNAFFECTED".equals(raw.path("affectedStatus").asText()))
                out.add("DENOVO_EVENT_AFFECTED_PHENOTYPE_CONFLICT");
            if ("MATERNAL_ORIGIN".equals(kind())) {
                if (!"X_LINKED".equals(group.inheritance()) || personId().equals(probandId())) out.add("DENOVO_MATERNAL_ORIGIN_SCOPE_INVALID");
                if ("UNAFFECTED".equals(raw.path("affectedStatus").asText())
                        && !"NONE_IN_CHECKED_SCOPE".equals(raw.path("otherAffectedMales").asText()))
                    out.add("DENOVO_MATERNAL_ORIGIN_FAMILY_HISTORY_REQUIRED");
                if ("UNAFFECTED".equals(raw.path("affectedStatus").asText()) && !"MALE".equals(raw.path("probandSex").asText()))
                    out.add("DENOVO_MATERNAL_ORIGIN_AFFECTED_SON_REQUIRED");
                if ("AFFECTED".equals(raw.path("affectedStatus").asText()))
                    out.add("DENOVO_MATERNAL_ORIGIN_PHENOTYPE_RULE_NOT_IMPLEMENTED");
            }
            if ("X_LINKED".equals(group.inheritance())) {
                String ploidy = raw.path("ploidy").asText(), gt = raw.path("genotype").asText();
                if (!Set.of("HAPLOID", "DIPLOID").contains(ploidy) || "UNKNOWN".equals(gt)) out.add("DENOVO_EVENT_PLOIDY_GENOTYPE_REQUIRED");
                else if ("HEMIZYGOUS_ALT".equals(gt) != "HAPLOID".equals(ploidy)) out.add("DENOVO_EVENT_PLOIDY_GENOTYPE_CONFLICT");
                if ("HOMOZYGOUS_ALT".equals(gt)) out.add("DENOVO_XLINKED_HOMOZYGOUS_ORIGIN_RULE_NOT_IMPLEMENTED");
                // PAR is diploid for the declared supported constitutional model.
                if ("PAR".equals(region) && !"DIPLOID".equals(ploidy)) out.add("DENOVO_PAR_PLOIDY_CONFLICT");
                if ("PAR".equals(region)) out.add("DENOVO_PAR_INHERITANCE_RULE_NOT_IMPLEMENTED");
            }
            if ("AR".equals(group.inheritance())) {
                if (!"DIPLOID".equals(raw.path("ploidy").asText()) || !"HETEROZYGOUS".equals(raw.path("genotype").asText()))
                    out.add("DENOVO_AR_EVENT_ORIGIN_MODEL_REQUIRED");
                if ("UNRESOLVED".equals(partnerStatus())) out.add("DENOVO_AR_PARTNER_REVIEW_REQUIRED");
                if ("CIRCULAR".equals(partnerStatus())) out.add("DENOVO_AR_PARTNER_CLASSIFICATION_CIRCULAR");
                if ("INDEPENDENT_P_LP".equals(partnerStatus()) && !partnerVerified) out.add("DENOVO_AR_PARTNER_INDEPENDENCE_NOT_VERIFIED");
                if ("INDEPENDENT_P_LP".equals(partnerStatus()) && raw.at("/partner/sourceRefs").isEmpty())
                    out.add("DENOVO_AR_PARTNER_SOURCE_REQUIRED");
                if (policy.mode() == DeNovoEventPolicy.Mode.SVI_1_1_SECOND_ALLELE_REQUIRED && !"INDEPENDENT_P_LP".equals(partnerStatus()))
                    out.add("DENOVO_EXPERT_SECOND_ALLELE_REQUIRED");
            }
            if (recurrence()) {
                if (!facts.confirmed()) out.add("DENOVO_RECURRENCE_CONFIRMED_PARENTAGE_REQUIRED");
                if (raw.path("siblings").isEmpty()) out.add("DENOVO_RECURRENCE_SIBLING_SOURCE_REQUIRED");
                if ("DISTINCT_ORIGIN_CONFIRMED".equals(raw.path("eventOrigin").asText()))
                    out.add("DENOVO_RECURRENCE_DISTINCT_ORIGIN_RULE_NOT_IMPLEMENTED");
                if (!Set.of("SHARED_EVENT", "DISTINCT_ORIGIN_CONFIRMED").contains(raw.path("eventOrigin").asText()))
                    out.add("DENOVO_RECURRENCE_EVENT_ORIGIN_RULE_REQUIRED");
            }
            if ("POSTZYGOTIC".equals(kind()) && !policy.tissueRule()) out.add("DENOVO_MOSAIC_EXACT_RULE_REQUIRED");
            if (policy.tissueRule() && !"AD".equals(group.inheritance())) out.add("DENOVO_TISSUE_RULE_SCOPE_UNSUPPORTED");
            if (policy.tissueRule()) {
                if (facts.proband() == TargetTest.QUALITY_FAILED || facts.mother() == TargetTest.QUALITY_FAILED || facts.father() == TargetTest.QUALITY_FAILED)
                    out.add("DENOVO_TARGET_TEST_QUALITY_FAILED");
                if (facts.proband() == TargetTest.CONFLICT || facts.mother() == TargetTest.CONFLICT || facts.father() == TargetTest.CONFLICT)
                    out.add("DENOVO_TARGET_TEST_CONFLICT");
                boolean c1 = facts.proband() == TargetTest.PRESENT && facts.mother() == TargetTest.ABSENT_QUALIFIED
                        && facts.father() == TargetTest.ABSENT_QUALIFIED && facts.confirmed();
                String available = raw.path("parentalAvailability").asText();
                if ("UNAVAILABLE".equals(available) && (!"UNKNOWN".equals(raw.at("/trio/motherTarget").asText())
                        || !"UNKNOWN".equals(raw.at("/trio/fatherTarget").asText())
                        || Set.of("CONFIRMED", "EXCLUDED").contains(raw.at("/trio/maternity").asText())
                        || Set.of("CONFIRMED", "EXCLUDED").contains(raw.at("/trio/paternity").asText())))
                    out.add("DENOVO_PARENTAL_AVAILABILITY_SOURCE_CONFLICT");
                if (!c1 && "UNKNOWN".equals(available)) out.add("DENOVO_TISSUE_PARENTAL_AVAILABILITY_REQUIRED");
                if (!c1 && "AVAILABLE".equals(available) && (facts.proband() == TargetTest.UNKNOWN
                        || facts.mother() == TargetTest.UNKNOWN || facts.father() == TargetTest.UNKNOWN
                        || facts.maternity() == Parentage.INCONCLUSIVE || facts.paternity() == Parentage.INCONCLUSIVE))
                    out.add("DENOVO_TISSUE_PARENTAL_FACTS_REQUIRED");
                if (!c1 && "UNAVAILABLE".equals(available)) {
                    long affected = 0, comparison = 0;
                    for (var row : raw.path("tissues")) if (measurementQualified(row)) {
                        if (row.path("affected").asBoolean()) affected++; else comparison++;
                    }
                    if (affected == 0 || comparison == 0) out.add("DENOVO_TISSUE_COMPARISON_REQUIRED");
                    else {
                        boolean detectableAffected = false, observableContrast = false;
                        for (var a : raw.path("tissues")) if (measurementQualified(a) && a.path("affected").asBoolean()
                                && a.path("altReads").asLong() > 0) {
                            detectableAffected = true;
                            for (var b : raw.path("tissues")) if (measurementQualified(b) && !b.path("affected").asBoolean()
                                    && !a.path("tissueId").equals(b.path("tissueId")))
                                observableContrast |= b.path("altReads").asLong() > 0 || b.path("limit").decimalValue().compareTo(fraction(a)) <= 0;
                        }
                        if (detectableAffected && !observableContrast) out.add("DENOVO_TISSUE_COMPARISON_SENSITIVITY_INADEQUATE");
                    }
                }
            }
            return List.copyOf(out);
        }
        /** Native tissue measurements, not a low-VAF threshold or asserted rescue. */
        int tissueStrength(Facts facts) { return tissueAssessment(facts).level(); }
        TissueAssessment tissueAssessment(Facts facts) {
            var tissues = raw.path("tissues");
            var affected = new ArrayList<Integer>(); var comparison = new ArrayList<Integer>();
            for (int i = 0; i < tissues.size(); i++) {
                var row = tissues.get(i);
                if (!personId().equals(row.path("personId").asText()) || "CONFLICT".equals(row.path("quality").asText()))
                    return new TissueAssessment(false, false, -1, -1, -1);
                if (!measurementQualified(row)) continue;
                if (row.path("affected").asBoolean()) affected.add(i); else comparison.add(i);
            }
            boolean c1 = facts.proband() == TargetTest.PRESENT && facts.mother() == TargetTest.ABSENT_QUALIFIED
                    && facts.father() == TargetTest.ABSENT_QUALIFIED && facts.confirmed();
            int ai = -1, bi = -1;
            for (int i : affected) for (int j : comparison) {
                var a = tissues.get(i); var b = tissues.get(j);
                boolean detectionAdequate = b.path("altReads").asLong() > 0 || b.path("limit").decimalValue().compareTo(fraction(a)) <= 0;
                if (!a.path("tissueId").equals(b.path("tissueId")) && a.path("altReads").asLong() > 0
                        && detectionAdequate && fraction(a).compareTo(fraction(b)) > 0 && ai < 0) { ai = i; bi = j; }
            }
            boolean c2 = ai >= 0;
            int level = c1 && c2 ? 2 : c1 || "UNAVAILABLE".equals(raw.path("parentalAvailability").asText()) && c2 ? 1 : 0;
            return new TissueAssessment(c1, c2, level, ai, bi);
        }
        private boolean measurementQualified(JsonNode row) {
            if (!"HIGH_CONFIDENCE".equals(row.path("quality").asText()) || !METHODS.contains(row.path("method").asText())) return false;
            long alt = row.path("altReads").asLong(), total = row.path("totalReads").asLong();
            BigDecimal limit = row.path("limit").decimalValue();
            return total > 0 && alt >= 0 && alt <= total && limit.signum() > 0 && limit.compareTo(BigDecimal.ONE) <= 0
                    && (alt == 0 || fraction(row).compareTo(limit) >= 0);
        }
        private BigDecimal fraction(JsonNode row) {
            return BigDecimal.valueOf(row.path("altReads").asLong()).divide(BigDecimal.valueOf(row.path("totalReads").asLong()),
                    java.math.MathContext.DECIMAL128);
        }
        boolean sharedOrigin() { return recurrence() && "SHARED_EVENT".equals(raw.path("eventOrigin").asText()); }
        boolean compatibleSharedOrigin(Verified other) {
            if (!sharedOrigin() || !other.sharedOrigin() || !group.equals(other.group) || !eventId.equals(other.eventId)) return false;
            for (String parent : List.of("mother", "father")) {
                if (!raw.at("/trio/" + parent + "Id").equals(other.raw.at("/trio/" + parent + "Id"))) return false;
                TargetTest a = target(raw.at("/trio/" + parent + "Target").asText());
                TargetTest b = other.target(other.raw.at("/trio/" + parent + "Target").asText());
                if (a == TargetTest.PRESENT && b == TargetTest.ABSENT_QUALIFIED || b == TargetTest.PRESENT && a == TargetTest.ABSENT_QUALIFIED) return false;
            }
            if (personId().equals(other.personId())) for (String field : List.of("sex", "ploidy", "genotype", "affectedStatus"))
                if (!"UNKNOWN".equals(raw.path(field).asText()) && !"UNKNOWN".equals(other.raw.path(field).asText())
                        && !raw.path(field).equals(other.raw.path(field))) return false;
            return true;
        }
        private ObjectNode occurrenceIdentity() {
            var n = raw.deepCopy(); n.remove(List.of("eventId", "probandId"));
            if (recurrence() && "SHARED_EVENT".equals(raw.path("eventOrigin").asText())) {
                n.remove(List.of("personId", "siblings")); ((ObjectNode) n.path("trio")).remove("personId");
            }
            return n;
        }
        @Override public boolean equals(Object other) {
            return other instanceof Verified v && group.equals(v.group) && eventId.equals(v.eventId) && partnerVerified == v.partnerVerified
                    && Objects.equals(regionIssue, v.regionIssue) && Objects.equals(region, v.region) && occurrenceIdentity().equals(v.occurrenceIdentity());
        }
        @Override public int hashCode() { return Objects.hash(group, eventId, regionIssue, region, occurrenceIdentity()); }
    }

    private static boolean sameAllele(JsonNode a, JsonNode b) {
        return a.path("assembly").equals(b.path("assembly")) && a.path("contig").equals(b.path("contig"))
                && a.path("position").asLong() == b.path("position").asLong()
                && a.path("reference").equals(b.path("reference")) && a.path("alternate").equals(b.path("alternate"));
    }
    static Read read(JsonNode input, String retainedSourceSha, JsonNode allele, Group group,
                     DeNovoEventPolicy policy, RegionSupply regions) {
        return read(input, retainedSourceSha, allele, group, policy, regions, null);
    }
    static Read read(JsonNode input, String retainedSourceSha, JsonNode allele, Group group,
                     DeNovoEventPolicy policy, RegionSupply regions, DeNovoPartnerEvidence.Verified partnerEvidence) {
        var issues = new ArrayList<String>();
        if (policy == null) return new Read(null, List.of("DENOVO_SPECIAL_POLICY_REQUIRED"));
        try {
            if (retainedSourceSha == null || !retainedSourceSha.matches("[0-9a-f]{64}")) throw JSON.invalid("SOURCE_PIN_REQUIRED");
            JSON.shape(input, "schemaVersion:s kind:s familyId:s eventId:s personId:s probandId:s sex:s ploidy:s genotype:s affectedStatus:s parentalAvailability:s otherAffectedMales:s trio:o partner:o siblings:a eventOrigin:s tissues:a probandSex:!s");
            JSON.equal(input, "schemaVersion", "denovo-special-event-v1");
            JSON.enumeration(input, "kind", "CONSTITUTIONAL MATERNAL_ORIGIN SIBLING_RECURRENCE POSTZYGOTIC");
            JSON.enumeration(input, "sex", "MALE FEMALE UNKNOWN");
            if (input.has("probandSex")) JSON.enumeration(input, "probandSex", "MALE FEMALE UNKNOWN"); JSON.enumeration(input, "ploidy", "HAPLOID DIPLOID UNKNOWN");
            JSON.enumeration(input, "genotype", "HEMIZYGOUS_ALT HETEROZYGOUS HOMOZYGOUS_ALT UNKNOWN");
            JSON.enumeration(input, "affectedStatus", "AFFECTED UNAFFECTED UNKNOWN");
            JSON.enumeration(input, "parentalAvailability", "AVAILABLE UNAVAILABLE UNKNOWN");
            JSON.enumeration(input, "otherAffectedMales", "NONE_IN_CHECKED_SCOPE PRESENT UNKNOWN");
            JSON.enumeration(input, "eventOrigin", "SHARED_EVENT DISTINCT_ORIGIN_CONFIRMED UNKNOWN");
            for (String id : List.of("familyId", "eventId", "personId", "probandId")) {
                String value = input.path(id).asText();
                if (!value.equals(value.strip()) || !java.text.Normalizer.isNormalized(value, java.text.Normalizer.Form.NFC))
                    throw JSON.invalid("NONCANONICAL_IDENTITY");
            }
            var t = input.path("trio");
            JSON.shape(t, "personId:s motherId:s fatherId:s subjectTarget:s motherTarget:s fatherTarget:s maternity:s paternity:s maternityMethod:s paternityMethod:s method:s targetCoverage:s detectionCapability:s quality:s observationScopes:!o");
            if (t.has("observationScopes")) {
                JSON.shape(t.path("observationScopes"), "subject:!o mother:!o father:!o");
                for (var scope : t.path("observationScopes")) DeNovoStructuredReport.readScope(scope);
            }
            for (String field : List.of("subjectTarget", "motherTarget", "fatherTarget")) JSON.enumeration(t, field, "PRESENT ABSENT UNKNOWN CONFLICT");
            for (String field : List.of("maternity", "paternity")) JSON.enumeration(t, field, "CONFIRMED ASSUMED_NOT_TESTED ASSUMED_NOT_REPORTED INCONCLUSIVE EXCLUDED");
            for (String field : List.of("maternityMethod", "paternityMethod")) JSON.enumeration(t, field, "SNP_PANEL STR_PANEL SEQUENCING_KINSHIP NOT_TESTED NOT_REPORTED UNKNOWN");
            JSON.enumeration(t, "method", "SANGER TARGETED_SEQUENCING EXOME GENOME UNKNOWN");
            JSON.enumeration(t, "targetCoverage", "EXPLICIT_TARGET UNKNOWN"); JSON.enumeration(t, "detectionCapability", "ADEQUATE UNKNOWN INADEQUATE");
            JSON.enumeration(t, "quality", "VALID UNKNOWN FAILED CONFLICT");
            boolean unavailableParentIdentities = policy.tissueRule() && "UNAVAILABLE".equals(input.path("parentalAvailability").asText())
                    && "UNKNOWN".equals(t.path("motherId").asText()) && "UNKNOWN".equals(t.path("fatherId").asText())
                    && !"UNKNOWN".equals(t.path("personId").asText());
            if (!unavailableParentIdentities && new HashSet<>(List.of(t.path("personId").asText(), t.path("motherId").asText(), t.path("fatherId").asText())).size() != 3)
                throw JSON.invalid("TRIO_PERSON_IDENTITY_CONFLICT");
            var partner = input.path("partner"); JSON.shape(partner, "status:s allele:?o phase:s sourceRefs:a");
            JSON.enumeration(partner, "status", "INDEPENDENT_P_LP NONE_IN_CHECKED_SCOPE UNRESOLVED CIRCULAR");
            JSON.enumeration(partner, "phase", "TRANS CIS UNKNOWN"); JSON.strings(partner.path("sourceRefs"));
            for (var ref : partner.path("sourceRefs")) if (!ref.asText().matches("[0-9a-f]{64}#/.+")) throw JSON.invalid("PARTNER_SOURCE_INVALID");
            if ("INDEPENDENT_P_LP".equals(partner.path("status").asText())) {
                var other = partner.path("allele");
                JSON.shape(other, "assembly:s contig:s position:i reference:s alternate:s");
                if (sameAllele(other, allele) || !other.path("assembly").equals(allele.path("assembly"))
                        || !other.path("contig").equals(allele.path("contig")) || other.path("position").asLong() <= 0
                        || !other.path("reference").asText().matches("[ACGT]+") || !other.path("alternate").asText().matches("[ACGT]+")
                        || other.path("reference").equals(other.path("alternate"))) throw JSON.invalid("PARTNER_ALLELE_INVALID");
            }
            JSON.strings(input.path("siblings"));
            if (input.path("siblings").size() > 100 || input.path("tissues").size() > 20) throw JSON.invalid("OBSERVATION_LIMIT");
            for (var row : input.path("tissues")) {
                var typed = row.deepCopy();
                ((ObjectNode) typed).remove("limit");
                JSON.shape(typed, "personId:s tissueId:s affected:b altReads:i totalReads:i method:s quality:s");
                if (!row.path("altReads").isIntegralNumber() || !row.path("totalReads").isIntegralNumber()
                        || row.path("altReads").asLong() < 0 || row.path("totalReads").asLong() <= 0
                        || row.path("altReads").asLong() > row.path("totalReads").asLong()) throw JSON.invalid("TISSUE_COUNTS_INVALID");
                JSON.enumeration(row, "method", "SANGER TARGETED_SEQUENCING EXOME GENOME UNKNOWN");
                JSON.enumeration(row, "quality", "HIGH_CONFIDENCE UNKNOWN FAILED CONFLICT");
                if (!row.path("limit").isNumber() || row.path("limit").decimalValue().signum() < 0
                        || row.path("limit").decimalValue().compareTo(BigDecimal.ONE) > 0) throw JSON.invalid("TISSUE_LIMIT_INVALID");
            }
            String regionIssue = null, region = null;
            if ("X_LINKED".equals(group.inheritance())) {
                if (!"X".equals(allele.path("contig").asText().replaceFirst("^chr", ""))) regionIssue = "DENOVO_XLINKED_CONTIG_CONFLICT";
                else if (regions == null) regionIssue = "DENOVO_SEX_REGION_DEPENDENCY_UNAVAILABLE";
                else {
                    RegionFact result = null;
                    try { result = regions.resolve(allele.deepCopy()); }
                    catch (RuntimeException failed) { regionIssue = "DENOVO_SEX_REGION_LOOKUP_FAILED"; }
                    String pin = new RuntimeEvidenceV2Adapter(MAPPER).canonicalSha256(allele);
                    if (regionIssue == null) {
                        if (result == null || !allele.path("assembly").asText().equals(result.assembly()) || !pin.equals(result.alleleSha256())
                                || result.sourceSha256() == null || !result.sourceSha256().matches("[0-9a-f]{64}")) regionIssue = "DENOVO_SEX_REGION_BINDING_INVALID";
                        else {
                            region = result.region();
                            if (region == null || !Set.of("PAR", "NON_PAR").contains(region)) {
                                String reason = result.reasonCode();
                                regionIssue = reason != null && reason.matches("(?:SEX_REGION_|DENOVO_A10_)[A-Z0-9_]+") ? reason : "DENOVO_SEX_REGION_UNRESOLVED";
                            }
                        }
                    }
                }
            }
            String identity = MAPPER.createArrayNode().add(group.alleleKey()).add(group.hgncId()).add(group.diseaseId())
                    .add(input.path("familyId").asText()).add(input.path("eventId").asText()).toString();
            String eventId = "source-event:" + FamilyDeNovoJson.sha256(identity.getBytes(StandardCharsets.UTF_8));
            return new Read(new Verified((ObjectNode) input, group, eventId, retainedSourceSha, regionIssue, region, partnerEvidence != null && partnerEvidence.matches(allele, input.at("/partner/allele"), group)), issues);
        } catch (RuntimeException invalid) {
            String reason = invalid.getMessage();
            issues.add(reason != null && reason.matches("DENOVO_SPECIAL_[A-Za-z0-9_]+") ? reason.toUpperCase(java.util.Locale.ROOT) : "DENOVO_SPECIAL_SOURCE_INVALID");
            return new Read(null, issues);
        }
    }
}
