package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Resolves local interval leads against their individual current submissions.
 * Only exclusions are granted here; a P label never establishes independence. */
final class Pvs1StartLossClinVarCandidates {
    private Pvs1StartLossClinVarCandidates() { }

    static ObjectNode review(ObjectMapper mapper, JsonNode context, JsonNode model, int restart,
            JsonNode scan, Pvs1StartLossPublicSources sources) {
        var out = mapper.createObjectNode().put("status", "INCOMPLETE").put("complete", false)
                .put("sourceSha256", scan.path("sourceSha256").asText()).put("independentPathogenicityGranted", false);
        var records = out.putArray("records");
        if (!"ENUMERATED".equals(scan.path("status").asText()) || !scan.path("complete").asBoolean()
                || !scan.path("records").isArray() || scan.path("records").size() > 10000)
            return out.put("reasonCode", "CLINVAR_REQUIRED_INTERVAL_NOT_COMPLETE");
        var allele = context.at("/variant/evidenceQueryAllele");
        var target = Pvs1StartLossReferenceMapping.genomic(model, allele.path("position").asInt(),
                allele.path("reference").asText(), allele.path("alternate").asText());
        int unresolved = 0, index = 0;
        for (var local : scan.path("records")) {
            String id = local.path("variationId").asText();
            var row = records.addObject().put("variationId", id).put("sourceLocator", "/records/" + index++)
                    .put("disposition", "UNRESOLVED");
            var mapped = Pvs1StartLossReferenceMapping.genomic(model, local.path("position").asInt(),
                    local.path("reference").asText(), local.path("alternate").asText());
            if (!mapped.mapped()) { row.put("reasonCode", mapped.reason()); unresolved++; continue; }
            row.put("alleleKey", mapped.key()).put("cdsStart", mapped.first()).put("cdsEnd", mapped.last());
            if (target.mapped() && target.key().equals(mapped.key())) {
                row.put("disposition", "EXCLUDED").put("reasonCode", "TARGET_EXCLUDED"); continue;
            }
            if (mapped.first() >= restart) {
                row.put("disposition", "EXCLUDED").put("reasonCode", "OUTSIDE_UPSTREAM_INTERVAL"); continue;
            }
            if (mapped.last() >= restart) { row.put("reasonCode", "REFERENCE_UPSTREAM_BOUNDARY_UNRESOLVED"); unresolved++; continue; }
            var assertions = Pvs1StartLossClinVarAssertions.candidate(mapper, context, id, sources.clinvarRecord(id));
            row.set("assertions", assertions);
            var sourceAllele = assertions.path("allele");
            var sourceMapping = Pvs1StartLossReferenceMapping.genomic(model, sourceAllele.path("position").asInt(),
                    sourceAllele.path("reference").asText(), sourceAllele.path("alternate").asText());
            if (assertions.has("allele") && (!sourceMapping.mapped() || !mapped.key().equals(sourceMapping.key()))) {
                row.put("reasonCode", "CLINVAR_LOCAL_SOURCE_ALLELE_MISMATCH"); unresolved++; continue;
            }
            if ("NO_PATHOGENIC_ASSERTION_IN_SCOPE".equals(assertions.path("status").asText()) && sourceMapping.mapped())
                row.put("disposition", "EXCLUDED").put("reasonCode", "CLINVAR_NO_CURRENT_PATHOGENIC_ASSERTION_IN_SCOPE");
            else { row.put("reasonCode", assertions.path("reasonCode").asText()); unresolved++; }
        }
        out.put("unresolvedRecords", unresolved).put("recordCount", records.size());
        return unresolved == 0 ? out.put("status", "ALL_CANDIDATES_EXCLUDED").put("complete", true)
                .put("reasonCode", "CLINVAR_INTERVAL_CANDIDATES_RESOLVED")
                : out.put("reasonCode", "CLINVAR_REFERENCE_QUALIFICATION_REQUIRED");
    }
}
