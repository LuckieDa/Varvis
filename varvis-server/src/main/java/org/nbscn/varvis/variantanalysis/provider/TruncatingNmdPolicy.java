package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** Second gate after specification selection: explicit LoF mechanism, model, and bounded NMD policy. */
final class TruncatingNmdPolicy {
    private TruncatingNmdPolicy() { }

    static String issue(ObjectNode context, ObjectNode policy, ObjectNode receipt,
                        ObjectNode model, RuntimeEvidenceV2Adapter adapter) {
        if (!only(policy, Set.of("schemaVersion", "policyId", "policyVersion", "criterionIds", "review", "scope",
                "appliedStrength", "transcriptModelBinding", "scientificSources", "mechanism", "authoritySourceIds",
                "nmdScope", "scientificBoundaryNotes", "escapeScope", "spliceScope", "codingEditScope"))
                || !only(policy.path("scope"), Set.of("hgncId", "mondoId", "moi", "assembly", "variantType", "transcriptMode", "transcripts"))
                || !only(policy.path("mechanism"), Set.of("status", "mechanismId", "sourceIds", "basis", "generalMechanismFramework"))
                || !only(policy.path("nmdScope"), Set.of("minimumCdsPosition", "minimumDistanceToLastJunction", "startProximalScope",
                        "engineeringScopeRationale", "exonRelevance", "relevantExonOrdinals", "engineeringSupportedExonOrdinals",
                        "nonRelevantExonOrdinals", "sourceIds", "longExonScope", "maximumExonLength", "positionConditions", "utrJunctionApplicability")))
            return "PVS1_POLICY_OPERATOR_UNSUPPORTED";
        if (!Pvs1NmdConditions.valid(policy)) return "PVS1_POSITIONAL_CONDITIONS_INVALID";
        if (!Set.of("pvs1-truncating-nmd-policy-v1", Pvs1NmdConditions.SCHEMA, Pvs1NmdEscape.SCHEMA, Pvs1SpliceConsequence.SCHEMA).contains(policy.path("schemaVersion").asText())
                || !text(policy.path("policyId")) || !text(policy.path("policyVersion"))
                || !"REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText())
                || !text(policy.at("/review/reviewId")) || !singleCriterion(policy.path("criterionIds")))
            return "PVS1_REVIEWED_POLICY_REQUIRED";
        if (!Set.of("VERY_STRONG", "STRONG", "MODERATE", "SUPPORTING").contains(policy.path("appliedStrength").asText()))
            return "PVS1_POLICY_STRENGTH_CONDITIONS_REQUIRED";
        ObjectNode identity = receipt.deepCopy();
        identity.remove("selectionId");
        if (!"acmg-specification-selection-v1".equals(receipt.path("schemaVersion").asText())
                || !Set.of("SPECIALIZED_POLICY_SELECTED", "GENERAL_POLICY_SELECTED").contains(receipt.path("outcome").asText())
                || !("cspec-selection-sha256-" + adapter.canonicalSha256(identity)).equals(receipt.path("selectionId").asText())
                || !context.path("contextId").equals(receipt.path("contextId"))
                || !singleCriterion(receipt.path("criterionIds"))
                || !policy.path("policyId").equals(receipt.path("policyId"))
                || !policy.path("policyVersion").equals(receipt.path("policyVersion"))
                || !adapter.canonicalSha256(policy).equals(receipt.path("policySha256").asText())
                || !text(receipt.path("catalogId")) || !text(receipt.path("catalogVersion"))
                || !receipt.path("catalogSha256").asText().matches("[0-9a-f]{64}")
                || !text(receipt.path("catalogEntryId")) || !text(receipt.path("searchScope")))
            return "PVS1_POLICY_SELECTION_IDENTITY_INVALID";
        try { Instant.parse(receipt.path("searchedAt").asText()); }
        catch (RuntimeException invalid) { return "PVS1_POLICY_SELECTION_IDENTITY_INVALID"; }
        JsonNode scope = policy.path("scope");
        if (!context.at("/gene/hgncId").equals(scope.path("hgncId"))
                || !context.at("/disease/mondoId").equals(scope.path("mondoId"))
                || !context.path("moi").equals(scope.path("moi"))
                || !context.at("/variant/evidenceQueryAllele/assembly").equals(scope.path("assembly"))
                || !"EXACT_ACCESSIONS".equals(scope.path("transcriptMode").asText())
                || !exactTranscript(context, scope.path("transcripts")))
            return "PVS1_POLICY_NOT_APPLICABLE";
        if (!Set.of("NATIVE_NONPROJECTED_SNV", "NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT")
                .contains(scope.path("variantType").asText())
                || !"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText()))
            return "PVS1_NATIVE_SMALL_VARIANT_REQUIRED";
        if (policy.has("codingEditScope") && !complexAllelesEnabled(policy))
            return "PVS1_COMPLEX_EDIT_SCOPE_NOT_REVIEWED";
        String ref = context.at("/variant/evidenceQueryAllele/reference").asText();
        String alt = context.at("/variant/evidenceQueryAllele/alternate").asText();
        String type = context.at("/variant/variantType").asText();
        boolean snv = ref.matches("[ACGT]") && alt.matches("[ACGT]") && !ref.equals(alt);
        boolean insertion = ref.length() == 1 && alt.length() > 1 && alt.startsWith(ref);
        boolean deletion = alt.length() == 1 && ref.length() > 1 && ref.startsWith(alt);
        boolean complex = complexAllelesEnabled(policy);
        int maximum = complex ? policy.at("/codingEditScope/maximumAlleleLengthIncludingAnchor").asInt() : 50;
        String expectedType = ref.length() == alt.length() ? ref.length() == 1 ? "SNV" : "MNV"
                : ref.length() == 1 ? "SMALL_INSERTION" : alt.length() == 1 ? "SMALL_DELETION" : "SMALL_DELINS";
        if ((!complex && !snv && !insertion && !deletion) || ref.equals(alt)
                || !ref.matches("[ACGT]{1,50}") || !alt.matches("[ACGT]{1,50}")
                || Math.max(ref.length(), alt.length()) > maximum || !expectedType.equals(type)
                || ("NATIVE_NONPROJECTED_SNV".equals(scope.path("variantType").asText()) && !snv))
            return "PVS1_NATIVE_SMALL_VARIANT_REQUIRED";
        if (!adapter.canonicalSha256(model).equals(policy.at("/transcriptModelBinding/canonicalSha256").asText())
                || !model.path("modelId").equals(policy.at("/transcriptModelBinding/modelId"))
                || !model.path("modelVersion").equals(policy.at("/transcriptModelBinding/modelVersion")))
            return "PVS1_TRANSCRIPT_MODEL_IDENTITY_INVALID";
        String modelIssue = TruncatingPvs1Model.issue(model, context);
        if (modelIssue != null) return modelIssue;
        JsonNode sources = policy.path("scientificSources");
        if (!TruncatingPvs1Model.sourcesValid(sources)
                || !boundSources(receipt.path("sourceIds"), sources)
                || !"ESTABLISHED_LOSS_OF_FUNCTION".equals(policy.at("/mechanism/status").asText())
                || !text(policy.at("/mechanism/mechanismId"))
                || !boundSources(policy.at("/mechanism/sourceIds"), sources))
            return "PVS1_LOF_MECHANISM_NOT_ESTABLISHED";
        JsonNode nmd = policy.path("nmdScope");
        var utr = nmd.path("utrJunctionApplicability");
        if (!utr.isMissingNode() && (!only(utr, Set.of("status", "basis", "sourceIds"))
                || !"REVIEWED_FOR_3_PRIME_UTR_JUNCTION".equals(utr.path("status").asText())
                || !text(utr.path("basis")) || !boundSources(utr.path("sourceIds"), sources)))
            return "PVS1_UTR_JUNCTION_APPLICABILITY_INVALID";
        if (!positive(nmd.path("minimumCdsPosition"))
                || nmd.path("minimumCdsPosition").asInt() >= model.path("cdsLength").asInt() - 3
                || !positive(nmd.path("minimumDistanceToLastJunction"))
                || nmd.path("minimumDistanceToLastJunction").asInt() < 51
                || !"REVIEWED_FOR_INCLUDED_RANGE".equals(nmd.path("startProximalScope").asText())
                || !text(nmd.path("engineeringScopeRationale")) || !text(nmd.path("exonRelevance"))
                || !boundSources(nmd.path("sourceIds"), sources)
                || !validOrdinals(nmd.path("relevantExonOrdinals"), model.path("exons").size())
                || !(model.path("exons").size() == 1 && nmd.path("engineeringSupportedExonOrdinals").isArray()
                        && nmd.path("engineeringSupportedExonOrdinals").isEmpty())
                    && !validOrdinals(nmd.path("engineeringSupportedExonOrdinals"), model.path("exons").size() - 1))
            return "PVS1_NMD_SCOPE_NOT_REVIEWED";
        for (JsonNode ordinal : nmd.path("engineeringSupportedExonOrdinals"))
            if (!contains(nmd.path("relevantExonOrdinals"), ordinal.asInt())) return "PVS1_EXON_RELEVANCE_UNRESOLVED";
        if (nmd.has("nonRelevantExonOrdinals")) {
            var excluded = nmd.path("nonRelevantExonOrdinals");
            if (!validOrdinals(excluded, model.path("exons").size())) return "PVS1_EXON_RELEVANCE_UNRESOLVED";
            for (var ordinal : excluded)
                if (contains(nmd.path("relevantExonOrdinals"), ordinal.asInt())) return "PVS1_EXON_RELEVANCE_CONFLICT";
        }
        String longExon = nmd.path("longExonScope").asText();
        if (!("REVIEWED_WITHOUT_LENGTH_LIMIT".equals(longExon)
                || ("EXCLUDE_ABOVE_MAXIMUM".equals(longExon) && positive(nmd.path("maximumExonLength")))))
            return "PVS1_NMD_SCOPE_NOT_REVIEWED";
        if (!policy.path("authoritySourceIds").isArray() || policy.path("authoritySourceIds").isEmpty())
            return "PVS1_POLICY_AUTHORITY_IDENTITY_INVALID";
        for (JsonNode id : policy.path("authoritySourceIds"))
            if (!text(id)) return "PVS1_POLICY_AUTHORITY_IDENTITY_INVALID";
        String spliceIssue = Pvs1SpliceConsequence.policyIssue(policy);
        return spliceIssue == null ? Pvs1NmdEscape.policyIssue(policy, model) : spliceIssue;
    }

    static ObjectNode mechanics(ObjectNode policy, JsonNode model) {
        ObjectNode nmd = (ObjectNode) policy.path("nmdScope");
        ObjectNode result = nmd.objectNode();
        // The mechanical last-exon boundary is transcript structure, not the last
        // engineering-supported exon. The runtime checks that independent subset.
        result.put("minimumCdsPosition", nmd.path("minimumCdsPosition").asInt())
                .put("firstExon", 1).put("lastExon", model.path("exons").size() - 1)
                .put("maximumExonLength", "REVIEWED_WITHOUT_LENGTH_LIMIT".equals(nmd.path("longExonScope").asText())
                        ? Integer.MAX_VALUE : nmd.path("maximumExonLength").asInt());
        result.putObject("frameshift").put("maximumAlleleLengthIncludingAnchor", complexAllelesEnabled(policy)
                        ? policy.at("/codingEditScope/maximumAlleleLengthIncludingAnchor").asInt() : 50)
                .put("minimumDistanceToLastJunction", nmd.path("minimumDistanceToLastJunction").asInt());
        return result;
    }

    /** Explicit mechanical capability only; this never establishes LoF or source qualification. */
    static boolean complexAllelesEnabled(JsonNode policy) {
        var scope = policy.path("codingEditScope");
        return "NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT".equals(policy.at("/scope/variantType").asText())
                && "REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText())
                && only(scope, Set.of("status", "method", "methodVersion", "maximumAlleleLengthIncludingAnchor", "basis", "sourceIds"))
                && "REVIEWED_FOR_COMPLEX_SMALL_ALLELES".equals(scope.path("status").asText())
                && "CODING_TRANSCRIPT_EDITS".equals(scope.path("method").asText())
                && CodingTranscriptEdits.VERSION.equals(scope.path("methodVersion").asText())
                && positive(scope.path("maximumAlleleLengthIncludingAnchor"))
                && scope.path("maximumAlleleLengthIncludingAnchor").asInt() <= 50
                && text(scope.path("basis")) && TruncatingPvs1Model.sourcesValid(policy.path("scientificSources"))
                && boundSources(scope.path("sourceIds"), policy.path("scientificSources"));
    }

    /** Carrier type alone cannot distinguish an anchored indel from a replacement allele. */
    static boolean requiresComplexAllele(JsonNode context) {
        String ref = context.at("/variant/evidenceQueryAllele/reference").asText();
        String alt = context.at("/variant/evidenceQueryAllele/alternate").asText();
        return !(ref.matches("[ACGT]") && alt.matches("[ACGT]") && !ref.equals(alt)
                || ref.length() == 1 && alt.length() > 1 && alt.startsWith(ref)
                || alt.length() == 1 && ref.length() > 1 && ref.startsWith(alt));
    }

    static boolean contains(JsonNode values, int value) {
        for (JsonNode item : values) if (item.isIntegralNumber() && item.asInt() == value) return true;
        return false;
    }

    private static boolean validOrdinals(JsonNode values, int maximum) {
        if (!values.isArray() || values.isEmpty()) return false;
        Set<Integer> seen = new HashSet<>();
        for (JsonNode value : values)
            if (!positive(value) || value.asInt() > maximum || !seen.add(value.asInt())) return false;
        return true;
    }

    private static boolean exactTranscript(JsonNode context, JsonNode transcripts) {
        if (!transcripts.isArray() || transcripts.size() != 1) return false;
        JsonNode transcript = transcripts.path(0);
        return context.at("/transcript/accession").equals(transcript.path("accession"))
                && context.at("/transcript/version").equals(transcript.path("version"));
    }

    private static boolean boundSources(JsonNode ids, JsonNode sources) {
        if (!ids.isArray() || ids.isEmpty()) return false;
        for (JsonNode id : ids) {
            boolean found = false;
            for (JsonNode source : sources) if (text(id) && id.equals(source.path("sourceId"))) found = true;
            if (!found) return false;
        }
        return true;
    }

    private static boolean singleCriterion(JsonNode ids) {
        return ids.isArray() && ids.size() == 1 && "ACMG_AMP_2015:PVS1".equals(ids.path(0).asText());
    }
    private static boolean positive(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt() && node.asInt() > 0;
    }
    private static boolean only(JsonNode node, Set<String> fields) {
        if (!node.isObject()) return false;
        var names = node.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) return false;
        return true;
    }
    private static boolean text(JsonNode node) { return TruncatingPvs1Model.text(node); }
}
