package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded compiler for explicit, scoped initiation-codon strength clauses.
 * No gene table, default-strength inference, or permission to use a generic
 * tree when a specialist condition cannot be interpreted. The caller loads
 * and pins the released source before invoking this compiler. */
final class Pvs1StartLossPublishedRule {
    private static final Pattern ALL = Pattern.compile("All initiator codon variants will meet PVS1_(Moderate|Supporting)\\. The next in-frame methionine is at amino acid position ([1-9][0-9]*) \\(based on (NP_[0-9]+(?:\\.[1-9][0-9]*)?)\\)\\.");
    private static final Pattern SCOPED = Pattern.compile("PVS1_(Moderate|Supporting) is applicable for initiation codon variants in ([A-Za-z0-9-]+)\\.");
    private Pvs1StartLossPublishedRule() { }

    /** Called after the selected model's full reference verification. A MANE
     * label or a coding-only alias cannot change the expert transcript scope. */
    static ObjectNode sourceContext(ObjectNode context, JsonNode model) {
        String selected = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
        String refseq = model.at("/transcriptBridge/refseqTranscript").asText();
        if (!selected.equals(model.path("transcript").asText()) || !PublishedCriterionKnowledge.exactBridge(refseq, model)) return context.deepCopy();
        var out = context.deepCopy(); int dot = refseq.lastIndexOf('.');
        out.withObject("transcript").put("accession", refseq.substring(0, dot)).put("version", Integer.parseInt(refseq.substring(dot + 1)));
        return out;
    }

    static ObjectNode read(ObjectMapper mapper, JsonNode entity, JsonNode context, JsonNode scopeFacts) {
        var out = mapper.createObjectNode().put("status", "UNRESOLVED").put("method", "EXPLICIT_START_LOSS_STRENGTH_V1");
        var claims = out.putArray("claims");
        if (!"MATCHING_CLAUSE_REQUIRES_SELECTION".equals(scopeFacts.path("scopeRelation").asText())
                || Set.of("EXPERT_BACKGROUND_SCOPE_UNRESOLVED", "EXPERT_EXACT_TRANSCRIPT_SCOPE_UNRESOLVED",
                    "EXPERT_CLAUSE_GENE_SCOPE_UNRESOLVED").contains(scopeFacts.path("reasonCode").asText()))
            return out.put("reasonCode", "PVS1_START_LOSS_PUBLISHED_SCOPE_UNRESOLVED");
        String applied = ""; int restart = 0; String protein = ""; boolean unhandled = false;
        int ci = 0;
        for (var code : entity.at("/ld/CriteriaCode")) {
            String cp = "/ld/CriteriaCode/" + ci++ + "/entContent"; var c = code.path("entContent");
            if (!"PVS1".equals(c.path("label").asText())) continue;
            if (!"Applicable".equals(c.path("applicability").asText()) || c.path("gene").size() != 1
                    || !context.at("/gene/symbol").equals(c.path("gene").path(0)))
                return out.put("reasonCode", "PVS1_START_LOSS_PUBLISHED_SCOPE_UNRESOLVED");
            List<String[]> texts = new ArrayList<>();
            for (String field : List.of("instructionsToUse", "additionalComments"))
                if (c.path(field).isTextual()) texts.add(new String[]{cp + "/" + field, c.path(field).asText(), ""});
            int si = 0;
            for (var strength : c.path("strengthDescriptor")) {
                String sp = cp + "/strengthDescriptor/" + si++;
                if (!"Applicable".equals(strength.path("applicability").asText())
                        || strength.hasNonNull("status") && !"approved".equals(strength.path("status").asText())) continue;
                for (String field : List.of("text", "instructionsToUse"))
                    if (strength.path(field).isTextual()) texts.add(new String[]{sp + "/" + field, strength.path(field).asText(), strength.path("strength").asText()});
            }
            for (var text : texts) {
              boolean activeStartSection = false;
              for (String paragraph : text[1].split("(?:\\r?\\n){2,}")) {
                String normalized = paragraph.replaceAll("(?m)^\\s*\\*\\*Initiat(?:or|ion) codon variants\\*\\*[^\\S\\r\\n]*\\r?\\n", "")
                        .replace("\\_", "_").replace("\\<", "<").replace("**", "")
                        .replaceAll("(?m)^\\s*\\\\?\\*\\s+", "").replaceAll("(?U)\\s+", " ").strip()
                        .replaceAll("_([A-Za-z][A-Za-z0-9-]*\\.?)_", "$1");
                if (!normalized.toLowerCase(java.util.Locale.ROOT).contains("initiat")) {
                    boolean otherHeading = paragraph.strip().matches("(?is)\\*\\*[^*]*(?:nonsense|frameshift|splice|deletion|duplication)[^*]*\\*\\*(?:\\s.*)?");
                    if (otherHeading) activeStartSection = false;
                    else if (activeStartSection && !normalized.isEmpty()
                            || normalized.matches("(?is)^(?:Except|Unless|However|Only|Provided|But|Requires|Do not)\\b.*")) unhandled = true;
                    continue;
                }
                // Headings and the unchanged baseline definition are not new
                // executable assertions; do not derive strength from them.
                if (normalized.matches("(?i)Initiat(?:or|ion) codon(?: variants)?[:.]?")
                        || normalized.equals("Single exon or larger deletion resulting in loss of <10% of the protein, and initiator codon variants.")
                        || normalized.startsWith("Null variant (nonsense, frameshift, canonical") && normalized.endsWith("known mechanism of disease.")) continue;
                var all = ALL.matcher(normalized); var scoped = SCOPED.matcher(normalized);
                String strength; int next = 0; String prot = "";
                if (all.matches()) {
                    strength = all.group(1);
                    try { next = Integer.parseInt(all.group(2)); } catch (NumberFormatException invalid) { unhandled = true; continue; }
                    if (next < 2 || next > 100000) { unhandled = true; continue; }
                    prot = all.group(3);
                } else if (scoped.matches() && context.at("/gene/symbol").asText().equals(scoped.group(2))) strength = scoped.group(1);
                else { unhandled = true; continue; }
                if (!text[2].isBlank() && !strength.equals(text[2]))
                    return out.put("status", "CONFLICT").put("reasonCode", "PVS1_START_LOSS_PUBLISHED_STRENGTH_CONFLICT");
                if (!applied.isEmpty() && (!applied.equals(strength) || next != restart || !prot.equals(protein)))
                    return out.put("status", "CONFLICT").put("reasonCode", "PVS1_START_LOSS_PUBLISHED_RULE_CONFLICT");
                applied = strength; restart = next; protein = prot;
                activeStartSection = true;
                claims.addObject().put("sourceLocator", text[0]).put("sourceText", text[1]).put("matchedParagraph", normalized);
              }
            }
        }
        if (!claims.isEmpty() && "PROHIBITED".equals(scopeFacts.path("policyDisposition").asText()))
            return out.put("status", "CONFLICT").put("reasonCode", "PVS1_START_LOSS_PUBLISHED_RULE_CONFLICT");
        if (unhandled || claims.isEmpty()) return out.put("reasonCode", "PVS1_START_LOSS_PUBLISHED_CONDITIONS_UNSUPPORTED");
        return out.put("status", "QUALIFIED").put("reasonCode", "PVS1_START_LOSS_EXPLICIT_PUBLISHED_RULE")
                .put("strength", applied.toUpperCase(java.util.Locale.ROOT)).put("requiredRestartProteinPosition", restart)
                .put("requiredProtein", protein).put("basis", "EXPLICIT_UNCONDITIONAL_INITIATION_CODON_CLAUSE");
    }

    /** Called only during internal preparation, after reference-verified sequence
     * acquisition and the pinned expert loader. Never accepts client material. */
    static ObjectNode resolve(ObjectMapper mapper, JsonNode assertions, JsonNode sequence, JsonNode model) {
        var out = mapper.createObjectNode().put("status", "UNRESOLVED");
        var entries = assertions.path("entries"); String strength = "", verifiedProtein = model.path("protein").asText();
        if (!"AVAILABLE".equals(sequence.path("status").asText()) || !entries.isArray() || entries.isEmpty())
            return out.put("reasonCode", "PVS1_START_LOSS_PUBLISHED_RULE_UNAVAILABLE");
        for (var entry : entries) {
            var rule = entry.path("startLossRule");
            if ("PROHIBITED".equals(entry.path("policyDisposition").asText()) || "CONFLICT".equals(entry.path("status").asText())
                    || "CONFLICT".equals(rule.path("status").asText()))
                return out.put("reasonCode", "PVS1_START_LOSS_PUBLISHED_RULE_CONFLICT");
            if (!"QUALIFIED".equals(rule.path("status").asText()) || !Set.of("SUPPORTING", "MODERATE").contains(rule.path("strength").asText()))
                return out.put("reasonCode", "PVS1_START_LOSS_PUBLISHED_CONDITIONS_UNSUPPORTED");
            if (!strength.isEmpty() && !strength.equals(rule.path("strength").asText()))
                return out.put("reasonCode", "PVS1_START_LOSS_PUBLISHED_STRENGTH_CONFLICT");
            strength = rule.path("strength").asText(); int next = rule.path("requiredRestartProteinPosition").asInt();
            String protein = rule.path("requiredProtein").asText(), actual = model.path("protein").asText();
            if (PublishedCriterionKnowledge.exactBridge(model.at("/transcriptBridge/refseqTranscript").asText(), model))
                actual = model.at("/transcriptBridge/refseqProtein").asText();
            verifiedProtein = actual;
            if (next > 0 && (sequence.path("candidateRestartCdsPosition").asInt() != (next - 1) * 3 + 1
                    || !(protein.contains(".") ? actual.equals(protein) : actual.matches(Pattern.quote(protein) + "\\.[1-9][0-9]*"))))
                return out.put("reasonCode", "PVS1_START_LOSS_PUBLISHED_SEQUENCE_CONDITION_MISMATCH");
        }
        return out.put("status", "QUALIFIED").put("strength", strength).put("verifiedProtein", verifiedProtein)
                .put("executionProtein", model.path("protein").asText())
                .put("reasonCode", "PVS1_START_LOSS_EXPLICIT_PUBLISHED_RULE");
    }
}
