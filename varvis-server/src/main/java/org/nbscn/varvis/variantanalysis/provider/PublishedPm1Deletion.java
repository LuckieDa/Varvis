package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;

/** Bounded PM1 location assessment for source-authorized direct protein deletions. */
final class PublishedPm1Deletion {
    static final String VERSION = "published-pm1-protein-deletion-v1";
    static final String RESOURCE_SHA = "aa531dbfc51105c6a03d7002ad70fd1426addd4e90df44d5db5736cb7301515b";
    private static final Set<String> DELETION_TERMS = Set.of("inframe_deletion", "NMD_transcript_variant");

    private PublishedPm1Deletion() { }

    private static final class Catalog {
        private static final JsonNode POLICIES = load();
    }

    private static JsonNode load() {
        try (var input = PublishedPm1Deletion.class.getResourceAsStream("/acmg/published-pm1-protein-deletion-v1.json")) {
            if (input == null) throw new IOException("Missing PM1 deletion policy");
            byte[] bytes = input.readNBytes(65_537);
            String canonical = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (bytes.length > 65_536 || !RESOURCE_SHA.equals(CodingTranscriptMaterialSupply.sha(canonical)))
                throw new IOException("Unexpected PM1 deletion policy checksum");
            var document = new ObjectMapper().readTree(canonical);
            if (!VERSION.equals(document.path("schemaVersion").asText()) || !document.path("policies").isArray())
                throw new IOException("Invalid PM1 deletion policy");
            return document.path("policies");
        } catch (IOException invalid) {
            throw new IllegalStateException("PM1_PROTEIN_DELETION_POLICY_UNAVAILABLE", invalid);
        }
    }

    static @Nullable JsonNode scope(@Nullable JsonNode context) {
        if (context == null || !"GERMLINE".equals(context.at("/scope/analysisDomain").asText())) return null;
        for (var policy : Catalog.POLICIES)
            if (policy.path("hgncId").equals(context.at("/gene/hgncId"))
                    && policy.path("mondoId").equals(context.at("/disease/mondoId"))
                    && coversMode(policy.path("moi").asText(), context.path("moi").asText())) return policy;
        return null;
    }

    private static boolean coversMode(String published, String selected) {
        return published.equals(selected) || "X_LINKED".equals(published)
                && Set.of("X_LINKED_DOMINANT", "X_LINKED_RECESSIVE").contains(selected);
    }

    static boolean includesDeletion(Set<String> terms) { return terms.contains("inframe_deletion"); }

    static ObjectNode assess(ObjectMapper mapper, JsonNode policy, ObjectNode context, VariantInterpretation candidate,
            TranscriptConsequence annotation, CodingTranscriptMaterialSupply materials, ObjectNode out) {
        var facts = out.putObject("proteinDeletion").put("schemaVersion", VERSION).put("policySha256", RESOURCE_SHA)
                .put("selectedTranscript", annotation.getVersionedTranscriptId()).put("publishedInheritance", policy.path("moi").asText());
        for (String field : List.of("specificationKey", "sourceUrl", "sourceSha256", "clausePointer",
                "consequenceSourceUrl", "declaredTranscript", "coverage"))
            facts.set(field, policy.path(field).deepCopy());
        if (!"COMPLETE_DOMAIN_DELETION".equals(policy.path("coverage").asText()))
            return result(mapper, "NOT_EVALUATED", "PM1_DELETION_POLICY_CONDITION_UNSUPPORTED", "NONE");
        var scope = policy.path("transcripts").path(annotation.getVersionedTranscriptId());
        if (!scope.isObject()) return result(mapper, "NOT_EVALUATED", "PM1_DELETION_TRANSCRIPT_SCOPE_UNRESOLVED", "NONE");
        facts.set("domains", scope.path("domains").deepCopy());
        if (!DELETION_TERMS.containsAll(SelectedCriterionRouting.consequenceTerms(annotation.getConsequence())))
            return result(mapper, "NOT_EVALUATED", "PM1_DELETION_MIXED_CONSEQUENCE_UNRESOLVED", "NONE");
        var supplied = materials.resolveStructure(context, candidate);
        var model = supplied.model();
        out.set("sequenceMaterial", supplied.snapshot());
        if (model == null) return result(mapper, "NOT_EVALUATED",
                supplied.snapshot().path("reasonCode").asText("PM1_DELETION_SEQUENCE_UNAVAILABLE"), "NONE");
        if (!scope.path("cdsSha256").asText().equals(CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText())))
            return result(mapper, "NOT_EVALUATED", "PM1_DELETION_TRANSCRIPT_SEQUENCE_UNVERIFIED", "NONE");
        out.withObject("/sequenceMaterial").set("transcript", model.path("transcript").deepCopy());
        out.withObject("/sequenceMaterial").set("protein", model.path("protein").deepCopy());
        var observation = mapper.createObjectNode();
        String issue = ProteinLengthChangeObservation.resolveDeletionForLocation(context, model, annotation, observation);
        if (issue != null || !observation.path("hgvspSequenceEquivalent").asBoolean())
            return result(mapper, "NOT_EVALUATED", issue == null ? "PM1_DELETION_PROTEIN_ANNOTATION_REQUIRED" : issue, "NONE");
        facts.put("proteinHgvs", annotation.getHgvsp()).put("deletedAminoAcids", -observation.path("netAminoAcidChange").asInt());
        return assessDomains(mapper, scope.path("domains"), observation.path("referenceProtein").asText(),
                observation.path("alteredProtein").asText(), policy.path("strength").asText(), facts);
    }

    static ObjectNode assessDomains(ObjectMapper mapper, JsonNode domains, String reference, String altered,
            String strength, ObjectNode facts) {
        int deleted = reference.length() - altered.length();
        int prefix = 0, suffix = 0;
        while (prefix < altered.length() && reference.charAt(prefix) == altered.charAt(prefix)) prefix++;
        while (suffix < altered.length() && reference.charAt(reference.length() - 1 - suffix) == altered.charAt(altered.length() - 1 - suffix))
            suffix++;
        int first = altered.length() - suffix;
        if (deleted <= 0 || first > prefix)
            return result(mapper, "NOT_EVALUATED", "PM1_DELETION_PROTEIN_FOOTPRINT_UNRESOLVED", "NONE");
        // Equivalent deletions can slide through identical residues. Take the shared
        // footprint for adoption and the union when ruling out a domain hit.
        int minStart = first + 1, maxEnd = prefix + deleted;
        int sharedStart = prefix + 1, sharedEnd = first + deleted;
        facts.putObject("possibleDeletedResidues").put("start", minStart).put("end", maxEnd);
        if (sharedStart <= sharedEnd)
            facts.putObject("confirmedDeletedResidues").put("start", sharedStart).put("end", sharedEnd);
        var checks = facts.putArray("domains");
        boolean matched = false, partial = false;
        for (var domain : domains) {
            int lo = domain.path("start").asInt(), hi = domain.path("end").asInt();
            boolean complete = sharedStart <= lo && hi <= sharedEnd;
            boolean touched = minStart <= hi && lo <= maxEnd;
            var check = checks.addObject().put("name", domain.path("name").asText()).put("start", lo).put("end", hi);
            check.put("outcome", complete ? "COMPLETE" : touched ? "PARTIAL_OR_AMBIGUOUS" : "DISJOINT");
            matched |= complete;
            partial |= touched && !complete;
        }
        if (matched) return result(mapper, "MET", "PM1_PUBLISHED_DOMAIN_DELETION_MET", strength);
        if (partial) return result(mapper, "NOT_EVALUATED", "PM1_DELETION_DOMAIN_COVERAGE_REVIEW_REQUIRED", "NONE");
        return result(mapper, "NOT_MET", "PM1_DELETION_OUTSIDE_PUBLISHED_DOMAINS", "NONE");
    }

    private static ObjectNode result(ObjectMapper mapper, String status, String reason, String strength) {
        return mapper.createObjectNode().put("status", status).put("reasonCode", reason).put("appliedStrength", strength);
    }
}
