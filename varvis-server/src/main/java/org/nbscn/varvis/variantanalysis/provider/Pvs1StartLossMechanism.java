package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The curated disease mechanism is one prerequisite, never a complete PVS1
 * policy. Reads the pinned catalog's original fields, not its convenience LoF
 * label. Does not relabel inferred mechanism as experimentally supported. */
final class Pvs1StartLossMechanism {
    static final String VERSION = "1.2.0";
    private Pvs1StartLossMechanism() { }

    /** The second route has its own original-source qualifier. It does not
     * relabel inferred G2P fields, erase an actual contrary mechanism, or grant
     * transcript relevance/variant strength. */
    static ObjectNode withClinGen(ObjectMapper mapper, JsonNode context, ObjectNode g2p,
            Pvs1StartLossClinGenMechanism.Assessment clingen) {
        var out = g2p.deepCopy(); var alternative = clingen.snapshot();
        out.set("clingenAssessment", alternative);
        if ("CURATED_MECHANISM_SUPPORTED".equals(g2p.path("status").asText()) || !clingen.qualified()) return out;
        String binding = Pvs1StartLossRuntimeMaterial.contextIdentitySha256(new RuntimeEvidenceV2Adapter(mapper), context);
        if (!binding.equals(alternative.path("contextSha256").asText()))
            return out.put("reasonCode", "PVS1_CLINGEN_MECHANISM_CONTEXT_MISMATCH");
        if ("CONFLICT".equals(g2p.path("status").asText())) return out;
        for (var finding : g2p.path("findings")) {
            var raw = finding.path("sourceRecord");
            String hgnc = raw.path("hgnc id").asText().strip();
            if (hgnc.matches("[1-9][0-9]*")) hgnc = "HGNC:" + hgnc;
            if (!hgnc.equals(context.at("/gene/hgncId").asText())
                    || !raw.path("disease MONDO").asText().equals(context.at("/disease/mondoId").asText())
                    || !"monoallelic_autosomal".equals(raw.path("allelic requirement").asText())) continue;
            if (Set.of("gain of function", "dominant negative", "undetermined non-loss-of-function")
                    .contains(raw.path("molecular mechanism").asText()))
                return out.put("status", "CONFLICT").put("reasonCode", "PVS1_START_LOSS_MECHANISM_CONFLICT");
            if (!raw.path("comments").asText().isBlank() || !raw.path("review").asText().isBlank()
                    || !raw.path("cross cutting modifier").asText().isBlank())
                return out.put("reasonCode", "PVS1_START_LOSS_MECHANISM_QUALIFIERS_UNSUPPORTED");
        }
        out.set("g2pAssessment", g2p.deepCopy());
        for (String field : List.of("status", "reasonCode", "basis", "binding", "contextSha256"))
            out.set(field, alternative.path(field).deepCopy());
        out.put("methodVersion", VERSION).put("runtimeEligible", false).put("primaryExperimentsReevaluated", false)
                .put("transcriptRelevanceEstablished", false);
        return out;
    }

    static ObjectNode assess(ObjectMapper mapper, JsonNode context, List<ObjectNode> records, ObjectNode binding) {
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-mechanism-v1")
                .put("methodVersion", VERSION).put("status", "UNRESOLVED").put("runtimeEligible", false)
                .put("primaryExperimentsReevaluated", false).put("transcriptRelevanceEstablished", false)
                .put("basis", "CURATED_G2P_DISEASE_MECHANISM_AND_NULL_VARIANT_SPECTRUM");
        out.set("binding", binding.deepCopy());
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        out.put("contextSha256", Pvs1StartLossRuntimeMaterial.contextIdentitySha256(adapter, context));
        var findings = out.putArray("findings");
        String allelic = switch (context.path("moi").asText()) {
            case "AUTOSOMAL_DOMINANT" -> "monoallelic_autosomal";
            case "AUTOSOMAL_RECESSIVE" -> "biallelic_autosomal";
            case "X_LINKED_RECESSIVE" -> "monoallelic_X_hemizygous";
            case "X_LINKED_DOMINANT" -> "monoallelic_X_heterozygous";
            default -> "";
        };
        if (allelic.isEmpty()) return out.put("reasonCode", "PVS1_START_LOSS_MECHANISM_INHERITANCE_UNRESOLVED");
        boolean qualified = false, unresolved = false, contrary = false;
        for (var record : records) {
            var raw = record.path("sourceRecord");
            if (!allelic.equals(raw.path("allelic requirement").asText().strip())) continue;
            var row = findings.addObject().put("recordId", record.path("recordId").asText())
                    .put("sourceRecordSha256", record.path("sourceRecordSha256").asText())
                    .put("sourceRow", record.path("sourceRow").asInt()).put("status", "UNRESOLVED");
            // Complete original text is retained, including restrictions and publications.
            row.set("sourceRecord", raw.deepCopy());
            String hgnc = raw.path("hgnc id").asText().strip();
            if (hgnc.matches("[1-9][0-9]*")) hgnc = "HGNC:" + hgnc;
            String reason = null;
            if (!hgnc.equals(context.at("/gene/hgncId").asText())
                    || !raw.path("disease MONDO").asText().strip().equals(context.at("/disease/mondoId").asText())
                    || !raw.path("gene symbol").asText().equals(context.at("/gene/symbol").asText()))
                reason = "PVS1_START_LOSS_MECHANISM_IDENTITY_UNRESOLVED";
            else if (!"loss of function".equals(raw.path("molecular mechanism").asText())) {
                contrary |= Set.of("gain of function", "dominant negative", "undetermined non-loss-of-function")
                        .contains(raw.path("molecular mechanism").asText());
                reason = "PVS1_START_LOSS_MECHANISM_CONTRARY_OR_UNKNOWN";
            } else if (!Set.of("strong", "definitive").contains(raw.path("confidence").asText()))
                reason = "PVS1_START_LOSS_MECHANISM_ASSOCIATION_INSUFFICIENT";
            else if (!"evidence".equals(raw.path("molecular mechanism support").asText()))
                reason = "PVS1_START_LOSS_MECHANISM_INFERRED_ONLY";
            else if (!raw.path("comments").asText().isBlank() || !raw.path("review").asText().isBlank()
                    || !raw.path("cross cutting modifier").asText().isBlank())
                reason = "PVS1_START_LOSS_MECHANISM_QUALIFIERS_UNSUPPORTED";
            else if (!terms(raw.path("variant consequence").asText()).contains("absent gene product")
                    || terms(raw.path("variant types").asText()).stream().noneMatch(
                            Set.of("frameshift_variant", "frameshift_variant_NMD_triggering", "stop_gained", "stop_gained_NMD_triggering")::contains))
                reason = "PVS1_START_LOSS_NULL_MECHANISM_SCOPE_UNRESOLVED";
            else if (!evidenceReferencesBound(raw)) reason = "PVS1_START_LOSS_MECHANISM_EVIDENCE_REFERENCES_UNRESOLVED";
            else {
                try { java.time.OffsetDateTime.parse(raw.path("date of last review").asText().replace(' ', 'T')); }
                catch (java.time.DateTimeException invalid) { reason = "PVS1_START_LOSS_MECHANISM_SOURCE_DATE_UNRESOLVED"; }
            }
            if (reason == null) {
                qualified = true;
                row.put("status", "CURATED_MECHANISM_SUPPORTED").put("reasonCode", "PVS1_START_LOSS_CURATED_LOF_MECHANISM");
            } else { unresolved = true; row.put("reasonCode", reason); }
        }
        if (contrary) return out.put("status", "CONFLICT").put("reasonCode", "PVS1_START_LOSS_MECHANISM_CONFLICT");
        if (qualified && !unresolved) return out.put("status", "CURATED_MECHANISM_SUPPORTED")
                .put("reasonCode", "PVS1_START_LOSS_CURATED_LOF_MECHANISM");
        return out.put("reasonCode", findings.isEmpty() ? "PVS1_START_LOSS_MECHANISM_SOURCE_NOT_FOUND"
                : "PVS1_START_LOSS_MECHANISM_RECORDS_UNRESOLVED");
    }

    private static Set<String> terms(String value) {
        return new HashSet<>(Arrays.asList(value.strip().split("\\s*;\\s*")));
    }
    private static boolean evidenceReferencesBound(JsonNode raw) {
        Set<String> publications = terms(raw.path("publications").asText());
        String evidence = raw.path("molecular mechanism evidence").asText();
        if (evidence.isBlank() || evidence.length() > 16000) return false;
        for (String observation : evidence.split("\\s*&\\s*")) {
            var m = java.util.regex.Pattern.compile("([1-9][0-9]*) -> (.+)").matcher(observation.strip());
            if (!m.matches() || !publications.contains(m.group(1)) || m.group(2).isBlank()) return false;
        }
        return true;
    }
}
