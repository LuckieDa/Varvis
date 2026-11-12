package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Consumes only the trusted core reader's replay of the existing PM3 partner
 * source/criterion-dependency audit. A P/LP label in an event is insufficient. */
final class DeNovoPartnerEvidence {
    record Read(Verified value, List<String> issues) { Read { issues = List.copyOf(issues); } }
    static final class Verified {
        private final JsonNode target, partner;
        private final String hgnc, disease;
        private Verified(JsonNode target, JsonNode partner, String hgnc, String disease) {
            this.target = target.deepCopy(); this.partner = partner.deepCopy(); this.hgnc = hgnc; this.disease = disease;
        }
        boolean matches(JsonNode target, JsonNode partner, DeNovoEvidenceCalculator.Group group) {
            return same(this.target, target) && same(this.partner, partner) && hgnc.equals(group.hgncId()) && disease.equals(group.diseaseId());
        }
    }
    static Read read(JsonNode core, JsonNode target, JsonNode partner, DeNovoEvidenceCalculator.Group group) {
        var issues = new ArrayList<String>();
        var report = core.path("partnerQualification"); var proof = core.path("partnerQualificationProof");
        try {
            if (!"denovo-special-partner-qualification-v1".equals(report.path("schemaVersion").asText())
                    || !"VERIFIED".equals(report.path("status").asText()) || !same(target, report.path("targetAllele"))
                    || !same(partner, report.path("partnerAllele")) || same(target, partner)
                    || !group.hgncId().equals(report.at("/scope/hgncId").asText())
                    || !group.diseaseId().equals(report.at("/scope/diseaseId").asText())
                    || !group.inheritance().equals(report.at("/scope/inheritance").asText()))
                return unavailable("DENOVO_AR_PARTNER_INDEPENDENCE_NOT_VERIFIED");
            // Equal-length SNVs have unambiguous identity. Different net lengths
            // cannot be the same allele. Other equivalent indel/MNV representations
            // need the shared reference-backed normalization supplied by A5.
            boolean snvs = target.path("reference").asText().length() == 1 && target.path("alternate").asText().length() == 1
                    && partner.path("reference").asText().length() == 1 && partner.path("alternate").asText().length() == 1;
            int targetDelta = target.path("alternate").asText().length() - target.path("reference").asText().length();
            int partnerDelta = partner.path("alternate").asText().length() - partner.path("reference").asText().length();
            if (!snvs && targetDelta == partnerDelta) return unavailable("DENOVO_AR_PARTNER_NORMALIZATION_REQUIRED");
            var registered = core.path("sourcePins").path("special-partner-qualification");
            if (!proof.path("sha256").equals(registered.path("sha256")) || !"DERIVED_FACTS".equals(registered.path("role").asText()))
                return unavailable("DENOVO_AR_PARTNER_PROOF_INVALID");
            String raw = proof.path("canonicalUtf8").asText();
            if (raw.isBlank() || raw.length() > 4_000_000
                    || !FamilyDeNovoJson.sha256(raw.getBytes(StandardCharsets.UTF_8)).equals(proof.path("sha256").asText())
                    || !new FamilyDeNovoJson("DENOVO_PARTNER_").parse(new ObjectMapper(), raw.getBytes(StandardCharsets.UTF_8)).equals(report))
                return unavailable("DENOVO_AR_PARTNER_PROOF_INVALID");
            var q = report.path("qualification");
            if (!"EXACT_SOURCE_ASSERTIONS_AVAILABLE".equals(report.at("/sourceRead/status").asText())
                    || !"VERIFIED".equals(q.path("independentClassification").asText())
                    || !Set.of("PATHOGENIC", "LIKELY_PATHOGENIC").contains(q.path("classification").asText())
                    || !group.diseaseId().equals(q.path("diseaseId").asText())
                    || !q.path("qualifiedScvs").isArray() || q.path("qualifiedScvs").isEmpty())
                return unavailable("DENOVO_AR_PARTNER_CLASSIFICATION_AUDIT_REQUIRED");
            for (var scv : q.path("qualifiedScvs")) {
                boolean sourceBound = false, audited = false;
                for (var source : report.at("/sourceRead/assertions")) if (scv.equals(source.path("scv"))) {
                    String pin = source.path("sourceSha256").asText();
                    if (!pin.matches("[0-9a-f]{64}")) continue;
                    for (var original : core.path("sourcePins")) if (pin.equals(original.path("sha256").asText())) sourceBound = true;
                    boolean refFound = false;
                    for (var ref : proof.path("sourceRefs")) if (ref.path("sourceRef").asText().startsWith(pin + "#")
                            && "DERIVED_FACTS".equals(ref.path("role").asText()) && "XML".equals(ref.path("format").asText())) refFound = true;
                    boolean retained = false;
                    for (var record : report.path("sourceRecords")) {
                        var entry = core.path("sourcePins").path(record.path("sourceId").asText());
                        retained |= pin.equals(record.path("sha256").asText()) && pin.equals(entry.path("sha256").asText())
                                && "DERIVED_FACTS".equals(entry.path("role").asText());
                    }
                    sourceBound &= retained;
                    sourceBound &= refFound;
                }
                for (var assessment : q.path("assertionAssessments"))
                    audited |= scv.equals(assessment.path("scv")) && "VERIFIED".equals(assessment.path("status").asText());
                if (!sourceBound || !audited) return unavailable("DENOVO_AR_PARTNER_SOURCE_BINDING_INVALID");
            }
            for (String name : List.of("pm3_partner_sources.py", "pm3_partner_qualification.py", "pm3_partner_dependencies.py"))
                if (!report.path("implementation").path(name).asText().matches("[0-9a-f]{64}"))
                    return unavailable("DENOVO_AR_PARTNER_IMPLEMENTATION_BINDING_REQUIRED");
            return new Read(new Verified(target, partner, group.hgncId(), group.diseaseId()), issues);
        } catch (IllegalArgumentException invalid) { return unavailable("DENOVO_AR_PARTNER_PROOF_INVALID"); }
    }
    private static Read unavailable(String issue) { return new Read(null, List.of(issue)); }
    private static boolean same(JsonNode a, JsonNode b) {
        return a.isObject() && b.isObject() && a.path("assembly").equals(b.path("assembly"))
                && a.path("contig").equals(b.path("contig")) && a.path("position").asLong(-1) == b.path("position").asLong(-2)
                && a.path("reference").equals(b.path("reference")) && a.path("alternate").equals(b.path("alternate"));
    }
}
