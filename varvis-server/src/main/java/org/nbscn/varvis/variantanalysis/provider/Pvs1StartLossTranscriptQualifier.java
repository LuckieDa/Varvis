package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Structural exclusions before scientific qualification. No coding/expression label
 * is promoted to functional rescue, and incomplete enumeration never proves absence. */
final class Pvs1StartLossTranscriptQualifier {
    static final String SOURCE_QUALIFICATION_VERSION = "1.0.0";
    private Pvs1StartLossTranscriptQualifier() { }

    /** Only this source qualifier may construct a conclusion. No caller JSON
     * or extraction flag can instantiate a verified scientific assessment. */
    static final class Verified {
        private final String contextSha256;
        private final ObjectNode snapshot;
        private final boolean functionalAlternativeFound;
        private final boolean completeNegative;
        private Verified(String contextSha256, ObjectNode snapshot, boolean functionalAlternativeFound, boolean completeNegative) {
            this.contextSha256 = contextSha256; this.snapshot = snapshot.deepCopy();
            this.functionalAlternativeFound = functionalAlternativeFound; this.completeNegative = completeNegative;
        }
        String contextSha256() { return contextSha256; }
        ObjectNode snapshot() { return snapshot.deepCopy(); }
        boolean functionalAlternativeFound() { return functionalAlternativeFound; }
        boolean completeNegative() { return completeNegative; }
        boolean conflicting() { return "CONFLICT".equals(snapshot.path("status").asText()); }
    }

    /** Re-read pinned expert originals in their declared coding scope after an
     * A1-only molecular bridge. This is not a JSON-to-qualified-result adapter. */
    @org.springframework.lang.Nullable
    static Verified sourceScopedExclusion(ObjectMapper mapper, ObjectNode context, JsonNode model,
            PredictionSpecializationGuard experts, Pvs1StartLossPublicSources sources) {
        var original = experts.startLossAssertions(mapper, context);
        var expected = new java.util.LinkedHashSet<String>();
        for (var entry : original.path("entries")) {
            if (!"UNRESOLVED".equals(entry.path("status").asText())
                    || !"EXPERT_EXACT_TRANSCRIPT_SCOPE_UNRESOLVED".equals(entry.path("reasonCode").asText())) return null;
            expected.add(entry.path("specificationKey").asText());
        }
        if (expected.isEmpty()) return null;
        var referenceContext = Pvs1StartLossPublishedRule.sourceContext(context, model);
        String selected = referenceContext.at("/transcript/accession").asText() + "." + referenceContext.at("/transcript/version").asText();
        if (!selected.matches("NM_[0-9]+\\.[1-9][0-9]*")) return null;
        for (String sourceTranscript : experts.startLossTranscriptCandidates(context)) {
            if (selected.equals(sourceTranscript) || !selected.split("\\.")[0].equals(sourceTranscript.split("\\.")[0])) continue;
            Pvs1StartLossCodingScopeBridge.Verified bridge;
            try { bridge = Pvs1StartLossCodingScopeBridge.verify(mapper, context, model, sourceTranscript, sources); }
            catch (IllegalArgumentException unsupported) { continue; }
            var scoped = experts.startLossAssertions(mapper, bridge.sourceContext());
            var keys = new java.util.LinkedHashSet<String>(); boolean accepted = !scoped.path("entries").isEmpty();
            for (var entry : scoped.path("entries")) {
                keys.add(entry.path("specificationKey").asText());
                accepted &= "QUALIFIED".equals(entry.path("status").asText()) && "PROHIBITED".equals(entry.path("policyDisposition").asText())
                        && "FOUND_FUNCTIONAL_ALTERNATIVE".equals(entry.path("transcriptStatus").asText());
            }
            if (!accepted || !keys.equals(expected)) continue;
            String contextSha = Pvs1StartLossRuntimeMaterial.contextIdentitySha256(new RuntimeEvidenceV2Adapter(mapper), context);
            var evidence = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-source-qualified-transcripts-v1")
                    .put("contextSha256", contextSha).put("status", "FOUND_FUNCTIONAL_ALTERNATIVE").put("complete", false)
                    .put("method", "PINNED_EXPERT_CODING_START_SCOPE_REPLAY_V1").put("scope", "CODING_START_LOSS_ONLY")
                    .put("qualificationBasis", "EXPLICIT_SOURCE_START_LOSS_EXCLUSION").put("policyDisposition", "PROHIBITED");
            var specificationKeys = evidence.putArray("expertSpecificationKeys"); keys.forEach(specificationKeys::add);
            evidence.set("codingScopeBridge", bridge.audit());
            var bindings = evidence.putArray("sourceBindings");
            for (var entry : scoped.path("entries")) {
                var source = entry.path("source");
                for (var claim : entry.path("claims")) bindings.addObject().put("role", "EXPERT_START_LOSS_EXCLUSION")
                        .put("sourceSha256", source.path("rawSha256").asText())
                        .put("sourceLocator", source.path("jsonPointer").asText() + claim.path("sourceLocator").asText())
                        .put("url", source.path("url").asText());
            }
            for (String field : java.util.List.of("sourceRecord", "selectedRecord")) {
                var record = bridge.audit().path(field);
                bindings.addObject().put("role", "REFSEQ_CODING_SCOPE_BRIDGE").put("sourceSha256", record.path("sha256").asText())
                        .put("sourceLocator", "GenBank:VERSION/FEATURES/CDS/exon/ORIGIN").put("url", record.path("sourceUrl").asText());
            }
            return new Verified(contextSha, evidence, true, false);
        }
        return null;
    }

    static ObjectNode combineStructural(ObjectNode gencode, ObjectNode refseq) {
        var out = gencode.deepCopy(); var rows = out.withArray("findings");
        for (var row : refseq.path("findings")) rows.add(row.deepCopy());
        boolean complete = gencode.path("sourceEnumerationComplete").asBoolean() && refseq.path("sourceEnumerationComplete").asBoolean();
        boolean selected = gencode.path("selectedTranscriptPresent").asBoolean() || refseq.path("selectedTranscriptPresent").asBoolean();
        int unresolved = gencode.path("unresolvedCount").asInt(1) + refseq.path("unresolvedCount").asInt(1);
        out.put("sourceEnumerationComplete", complete).put("selectedTranscriptPresent", selected).put("unresolvedCount", unresolved);
        out.put("structuralReviewComplete", complete && selected && unresolved == 0);
        // RefSeq rows can only be structurally excluded after full RNA/reference
        // reconciliation. A different or unresolved start still needs function evidence.
        out.put("status", "UNRESOLVED").put("complete", false)
                .put("reasonCode", "TRANSCRIPT_SOURCE_QUALIFICATION_REQUIRED");
        if (complete && selected && unresolved == 0) out.put("status", "NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET")
                .put("complete", true).put("qualificationBasis", "COMPLETE_STRUCTURAL_SOURCE_SET_NO_DIFFERENT_START")
                .put("reasonCode", "PVS1_START_LOSS_NO_DIFFERENT_START_IN_SOURCE_SET");
        return out;
    }

    static ObjectNode withExpertAssertions(ObjectNode structural, JsonNode assertions) {
        var out = structural.deepCopy();
        var statuses = new HashSet<String>(); var claims = out.putArray("expertClaims");
        boolean conflict = false, unresolved = false;
        for (var entry : assertions.path("entries")) {
            conflict |= "CONFLICT".equals(entry.path("status").asText());
            if (!"QUALIFIED".equals(entry.path("status").asText())) { unresolved = true; continue; }
            statuses.add(entry.path("transcriptStatus").asText());
            claims.add(entry.deepCopy());
        }
        if (conflict || statuses.size() > 1) return out.put("status", "CONFLICT").put("complete", false)
                .put("reasonCode", "EXPERT_TRANSCRIPT_ASSERTIONS_CONFLICT");
        if (statuses.size() == 1) {
            String status = statuses.iterator().next();
            boolean negative = "NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET".equals(status);
            if (negative && unresolved) return out.put("status", "UNRESOLVED").put("complete", false)
                    .put("reasonCode", "EXPERT_SOURCE_COVERAGE_UNRESOLVED");
            return out.put("status", status).put("complete", negative).put("qualificationBasis", "EXPLICIT_SCOPED_EXPERT_ASSERTION")
                    .put("reasonCode", negative ? "EXPERT_NO_KNOWN_ALTERNATIVE_START" : "EXPERT_ALTERNATIVE_START_EXCLUSION");
        }
        if (unresolved && "NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET".equals(out.path("status").asText()))
            return out.put("status", "UNRESOLVED").put("complete", false).put("reasonCode", "EXPERT_SOURCE_COVERAGE_UNRESOLVED");
        return out;
    }

    static ObjectNode structural(ObjectMapper mapper, JsonNode context, JsonNode model, JsonNode inventory, String sha) {
        var out = mapper.createObjectNode().put("status", "UNRESOLVED").put("complete", false)
                .put("inventorySha256", sha).put("coverage", inventory.path("coverage").asText());
        var findings = out.putArray("findings");
        Set<String> seen = new HashSet<>();
        String selected = model.path("transcript").asText();
        var starts = Pvs1StartLossAutomaticPreparation.startPositions(model);
        int target = context.at("/variant/evidenceQueryAllele/position").asInt();
        int unresolved = 0, index = 0;
        boolean selectedFound = false;
        for (var row : inventory.path("transcripts")) {
            String tx = row.path("transcript").asText();
            var finding = findings.addObject().put("transcript", tx).put("sourceSha256", sha)
                    .put("sourceLocator", "/transcripts/" + index++).put("disposition", "UNRESOLVED");
            finding.putNull("functionallyRelevant");
            if (tx.isBlank() || !seen.add(tx) || !row.path("hgncId").equals(context.at("/gene/hgncId"))
                    || !row.path("assembly").equals(context.at("/variant/evidenceQueryAllele/assembly"))) {
                finding.put("reasonCode", "TRANSCRIPT_IDENTITY_UNRESOLVED"); unresolved++; continue;
            }
            // Do not treat retained intron, NMD or ambiguous coding biotypes as absent function.
            if (Set.of("lncRNA", "miRNA", "rRNA", "snRNA", "snoRNA", "scaRNA", "scRNA", "sRNA", "ribozyme", "ncRNA")
                    .contains(row.path("transcriptType").asText())) {
                finding.put("disposition", "EXCLUDED").put("reasonCode", "ANNOTATED_NONCODING_TRANSCRIPT"); continue;
            }
            if ("NCBI_DATASETS_PRODUCT_REPORT".equals(inventory.path("coverage").asText())
                    && !row.path("sequenceVerified").asBoolean()) {
                finding.put("reasonCode", row.path("sequenceReasonCode").asText("REFSEQ_SEQUENCE_NOT_VERIFIED")); unresolved++; continue;
            }
            try {
                if (!row.path("contig").equals(model.path("contig")) || !row.path("strand").equals(model.path("strand")))
                    throw new IllegalArgumentException();
                List<Integer> other;
                if (row.path("codingStartReferenceVerified").asBoolean()) {
                    if (!row.path("verifiedStartPositions").isArray() || row.path("verifiedStartPositions").size() != 3
                            || !"AVAILABLE".equals(row.at("/startCodonSource/status").asText())
                            || !row.at("/startCodonSource/sha256").asText().matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
                    other = new java.util.ArrayList<>();
                    for (var position : row.path("verifiedStartPositions")) {
                        if (!position.isIntegralNumber() || !position.canConvertToInt() || position.asInt() <= 0) throw new IllegalArgumentException();
                        other.add(position.asInt());
                    }
                    if (new HashSet<>(other).size() != 3) throw new IllegalArgumentException();
                    finding.set("startCodonSource", row.path("startCodonSource").deepCopy());
                    finding.put("startProofScope", "ANNOTATED_START_GEOMETRY_AND_PRIMARY_REFERENCE_ONLY");
                } else other = Pvs1StartLossAutomaticPreparation.startPositions(row);
                var startPositions = finding.putArray("startPositions"); other.forEach(startPositions::add);
                // These are inventory identity facts, not proof of isoform function.
                for (String field : java.util.List.of("sequenceVerified", "rnaSha256", "cdsSha256"))
                    if (row.has(field)) finding.set(field, row.path(field).deepCopy());
                boolean different = !starts.equals(other), transcribed = false;
                JsonNode transcribedExons = row.path("transcriptExons").isArray() ? row.path("transcriptExons") : row.path("exons");
                for (var exon : transcribedExons)
                    transcribed |= target >= exon.path("genomicStart").asInt() && target <= exon.path("genomicEnd").asInt();
                finding.put("differentStart", different).put("targetOutsideTranscribedRegion", !transcribed);
                if (other.contains(target)) finding.put("targetUnaffected", false); else finding.putNull("targetUnaffected");
                if (tx.equals(selected)) {
                    if (different) throw new IllegalArgumentException();
                    selectedFound = true; finding.put("disposition", "EXCLUDED").put("reasonCode", "SELECTED_TRANSCRIPT");
                } else if (!different) finding.put("disposition", "EXCLUDED").put("reasonCode", "SAME_START_CODON");
                else { finding.put("reasonCode", "FUNCTION_AND_TARGET_EFFECT_REQUIRE_SOURCE"); unresolved++; }
            } catch (RuntimeException invalid) {
                finding.put("reasonCode", "ALTERNATIVE_STRUCTURE_UNRESOLVED"); unresolved++;
            }
        }
        out.put("unresolvedCount", unresolved).put("selectedTranscriptPresent", selectedFound);
        boolean sourceComplete = inventory.path("sourceCoverageComplete").isBoolean() && inventory.path("sourceCoverageComplete").asBoolean()
                && Set.of("ALL_SOURCE_TRANSCRIPTS_BEFORE_MODEL_FILTER", "NCBI_DATASETS_PRODUCT_REPORT").contains(inventory.path("coverage").asText());
        out.put("sourceEnumerationComplete", sourceComplete);
        // This is only a GENCODE structural negative. Other required transcript
        // sources/functional assertions remain distinct; never grant runtime eligibility here.
        out.put("structuralReviewComplete", sourceComplete && selectedFound && unresolved == 0);
        return out;
    }
}
