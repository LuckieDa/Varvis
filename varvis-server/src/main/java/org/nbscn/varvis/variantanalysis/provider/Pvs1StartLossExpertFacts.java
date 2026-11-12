package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded reading of explicit expert assertions, not free-text clinical inference.
 * Caller must supply a pinned, released CSpec entity; scope and exact transcript
 * are rechecked here. Unknown/conditional wording is never converted to absence. */
final class Pvs1StartLossExpertFacts {
    private static final Pattern ABSENT = Pattern.compile("(?i)^No known alternative start codon in other transcripts\\.$");
    private static final Pattern ALTERNATIVE = Pattern.compile("(?i)^Initiation codon variants are not applicable due to the [A-Za-z0-9_-]+ alternative isoform that excludes exon [1-9][0-9]* with an (?:alternative|alterante) start codon\\.$");
    private Pvs1StartLossExpertFacts() { }

    static ObjectNode read(ObjectMapper mapper, JsonNode entity, JsonNode scope, JsonNode context) {
        return read(mapper, entity, scope, context, null);
    }

    static ObjectNode read(ObjectMapper mapper, JsonNode entity, JsonNode scope, JsonNode context,
            @org.springframework.lang.Nullable Pvs1StartLossDiseaseScope diseaseScope) {
        var result = mapper.createObjectNode().put("status", "UNRESOLVED").put("method", "EXPLICIT_CSPEC_START_LOSS_ASSERTION_V1")
                .put("policyDisposition", "UNRESOLVED").put("transcriptStatus", "UNRESOLVED");
        // The execution context uses the more specific X-linked modes. A source
        // explicitly covering X-linked inheritance includes both; do not change
        // the stored patient/background context or infer a more specific mode.
        JsonNode scopeContext = context;
        if (context instanceof ObjectNode object && java.util.Set.of("X_LINKED_DOMINANT", "X_LINKED_RECESSIVE")
                .contains(context.path("moi").asText())) scopeContext = object.deepCopy().put("moi", "X_LINKED");
        String relation = PredictionSpecificationScope.relation(entity, scopeContext, "PVS1");
        if ("SCOPE_UNRESOLVED".equals(relation) && diseaseScope != null) {
            var joined = diseaseScope.supplement(entity, context);
            result.set("inheritanceScopeJoin", joined);
            if ("QUALIFIED".equals(joined.path("status").asText())) relation = "MATCHING_CLAUSE_REQUIRES_SELECTION";
        }
        result.put("scopeRelation", relation);
        if (!"MATCHING_CLAUSE_REQUIRES_SELECTION".equals(relation)) return result.put("reasonCode", "EXPERT_BACKGROUND_SCOPE_UNRESOLVED");
        String tx = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
        boolean exact = false;
        for (var transcript : scope.path("transcripts")) exact |= tx.equals(transcript.asText());
        if (!"EXACT".equals(scope.path("transcriptScope").asText()) || !exact)
            return result.put("reasonCode", "EXPERT_EXACT_TRANSCRIPT_SCOPE_UNRESOLVED");
        var claims = result.putArray("claims"); boolean absent = false, alternative = false;
        int ci = 0;
        for (var code : entity.at("/ld/CriteriaCode")) {
            String pointer = "/ld/CriteriaCode/" + ci++ + "/entContent";
            var content = code.path("entContent");
            if (!"PVS1".equals(content.path("label").asText())) continue;
            if (content.path("gene").size() != 1 || !context.at("/gene/symbol").equals(content.path("gene").path(0)))
                return result.put("reasonCode", "EXPERT_CLAUSE_GENE_SCOPE_UNRESOLVED");
            List<String[]> texts = new ArrayList<>();
            for (String field : List.of("instructionsToUse", "additionalComments"))
                if (content.path(field).isTextual()) texts.add(new String[]{pointer + "/" + field, content.path(field).asText()});
            int si = 0;
            for (var strength : content.path("strengthDescriptor")) {
                String sp = pointer + "/strengthDescriptor/" + si++;
                if (!"approved".equals(strength.path("status").asText())) continue;
                for (String field : List.of("text", "instructionsToUse")) if (strength.path(field).isTextual())
                    texts.add(new String[]{sp + "/" + field, strength.path(field).asText()});
            }
            for (var text : texts) {
                String[] paragraphs = text[1].split("\\R");
                for (String paragraph : paragraphs) {
                    String normalized = paragraph.strip().replace("**", "").replaceFirst("^\\*\\s+", "");
                    // A source heading may precede the standalone assertion. No
                    // arbitrary substring matching inside negation/conditional text.
                    normalized = normalized.replaceFirst("^[A-Za-z0-9_-]+ EP Commentary:\\s*", "");
                    boolean no = false, yes = false;
                    for (String sentence : normalized.split("(?<=\\.)\\s+")) {
                        no |= ABSENT.matcher(sentence).matches();
                        yes |= ALTERNATIVE.matcher(sentence).matches();
                    }
                    if (!no && !yes) continue;
                    // Do not discard exceptions/qualifiers in the remainder of
                    // a paragraph while keeping only a favourable first sentence.
                    if (Pattern.compile("(?i)\\b(if|unless|except|however|but|conditional|not true)\\b").matcher(normalized).find())
                        return result.put("reasonCode", "EXPERT_CONDITIONAL_ASSERTION_NOT_IMPLEMENTED");
                    claims.addObject().put("sourceLocator", text[0]).put("sourceText", text[1])
                            .put("assertion", no ? "NO_KNOWN_ALTERNATIVE_START_IN_OTHER_TRANSCRIPTS" : "FUNCTIONAL_ALTERNATIVE_EXPERT_EXCLUSION");
                    absent |= no; alternative |= yes;
                }
            }
        }
        if (absent && alternative) return result.put("status", "CONFLICT").put("reasonCode", "EXPERT_TRANSCRIPT_ASSERTIONS_CONFLICT");
        if (alternative) return result.put("status", "QUALIFIED").put("policyDisposition", "PROHIBITED")
                .put("transcriptStatus", "FOUND_FUNCTIONAL_ALTERNATIVE").put("reasonCode", "EXPERT_ALTERNATIVE_START_EXCLUSION");
        if (absent) return result.put("status", "QUALIFIED").put("transcriptStatus", "NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET")
                .put("reasonCode", "EXPERT_NO_KNOWN_ALTERNATIVE_START");
        return result.put("reasonCode", "EXPERT_START_LOSS_ASSERTION_NOT_IMPLEMENTED");
    }
}
