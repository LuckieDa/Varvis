package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

/** A source-bound preflight for the coding branch, never a claim that RNA effects are absent. */
final class Pvs1CodingApplicability {
    static final String VERSION = "1.0.0";
    static final String REASON = "PVS1_CODING_BRANCH_NOT_APPLICABLE";
    private Pvs1CodingApplicability() {}

    static ObjectNode exclude(ObjectMapper mapper, JsonNode context, VariantInterpretation candidate,
                              JsonNode materials, JsonNode policy) {
        if (candidate == null || policy != null && Pvs1SpliceConsequence.SCHEMA.equals(policy.path("schemaVersion").asText())) return null;
        try {
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            adapter.validateCompleteContext(context);
            String selection = context.at("/transcript/selectionPolicy").asText();
            if (org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.populationOnlySelection(selection)
                    || "PM2_CONTEXT_ONLY".equals(selection)) return null;
            var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
            var annotation = wiring.selectedVepConsequence(context, candidate);
            if (annotation == null || annotation.getConsequence() == null) return null;
            Set<String> terms = new java.util.HashSet<>(Arrays.asList(annotation.getConsequence().split("[&,|]")));
            if (terms.isEmpty() || !Set.of("missense_variant", "synonymous_variant", "NMD_transcript_variant").containsAll(terms)
                    || terms.contains("missense_variant") == terms.contains("synonymous_variant")) return null;
            String protein = java.util.Objects.toString(annotation.getHgvsProtein(), "");
            if (protein.contains("Ter") || protein.contains("*") || protein.contains("fs")
                    || java.util.Objects.toString(annotation.getAminoAcids(), "").contains("*")) return null;
            // A supplied outcome may redirect even a coding annotation into the splice branch.
            var outcomes = materials.path("pvs1SpliceOutcomes");
            if (!outcomes.isMissingNode() && !outcomes.isNull()) {
                if (!outcomes.isArray()) return null;
                for (var record : outcomes) {
                    if (!record.isObject() || !record.path("allele").isObject()) return null;
                    boolean match = true;
                    for (String field : java.util.List.of("assembly", "contig", "position", "reference", "alternate"))
                        match &= record.path("allele").path(field).equals(context.at("/variant/evidenceQueryAllele").path(field));
                    if (match) return null;
                }
            }
            var profile = materials.path("pvs1ResourceProfile");
            var binding = wiring.bind(profile, profile.path("vepResourceId").asText());
            if (!"VEP".equals(binding.getProvider()) || !"116.0".equals(binding.getProviderVersion())) return null;
            var evidence = wiring.projectStoredTranscript(context, candidate, annotation, binding);
            var out = mapper.createObjectNode().put("version", VERSION).put("status", "NOT_APPLICABLE")
                    .put("reasonCode", REASON).put("scope", "CODING_STOP_GAINED_OR_FRAMESHIFT_BRANCH")
                    .put("selectedTranscript", annotation.getVersionedTranscriptId()).put("consequence", annotation.getConsequence())
                    .put("spliceEffectsExcluded", false).put("clinicalBenignConclusion", false);
            out.set("evidence", evidence);
            return out;
        } catch (RuntimeException invalid) {
            // Incomplete/ambiguous identity must follow the normal refusal path, never become non-applicability.
            return null;
        }
    }
}
