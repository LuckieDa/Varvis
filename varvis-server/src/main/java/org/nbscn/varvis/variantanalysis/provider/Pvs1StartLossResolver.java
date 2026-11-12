package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

/** Independent start-loss sequence replay followed by source-bound deterministic assessment. */
final class Pvs1StartLossResolver {
    static final String VERSION = "1.2.0";
    private static final Pattern HGVS = Pattern.compile("^([^:]+):c\\.([1-3])([ACGT])>([ACGT])$");
    private static final Set<String> BASES = Set.of("A", "C", "G", "T");

    private Pvs1StartLossResolver() { }

    static boolean nuclearContig(JsonNode context) {
        return context.at("/variant/evidenceQueryAllele/contig").asText().replaceFirst("(?i)^chr", "")
                .matches("(?:[1-9]|1[0-9]|2[0-2]|X|Y)");
    }

    static boolean selectedStartLoss(JsonNode context, VariantInterpretation candidate) {
        if (candidate == null) return false;
        String selected = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
        return candidate.getTranscriptAnnotations().stream().anyMatch(tx -> selected.equals(tx.getVersionedTranscriptId())
                && terms(tx).contains("start_lost"));
    }

    static ObjectNode sequence(ObjectMapper mapper, JsonNode context, TranscriptConsequence tx, JsonNode model) {
        ObjectNode facts = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-sequence-facts-v1")
                .put("methodVersion", VERSION).put("clinicalScoringPerformed", false)
                .put("status", "UNRESOLVED");
        String reason = "PVS1_START_LOSS_INPUT_INVALID";
        try {
            if (!nuclearContig(context))
                throw new IllegalArgumentException("PVS1_START_LOSS_SCOPE_UNSUPPORTED");
            String referenceCds = model.path("cdsSequence").asText();
            if (referenceCds.length() >= 3 && referenceCds.matches("[ACGT]+") && !referenceCds.startsWith("ATG"))
                throw new IllegalArgumentException("PVS1_START_LOSS_NON_ATG_REFERENCE_UNSUPPORTED");
            if (!java.util.Collections.disjoint(terms(tx), Set.of("splice_donor_variant", "splice_acceptor_variant",
                    "intron_variant", "stop_gained", "frameshift_variant", "synonymous_variant")))
                throw new IllegalArgumentException("PVS1_START_LOSS_COMBINED_MECHANISM_CONFLICT");
            String modelIssue = CodingTranscriptMapping.modelIssue(model, context);
            if (modelIssue != null) throw new IllegalArgumentException(modelIssue);
            String selected = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
            String transcript = model.path("transcript").asText();
            if (!selected.equals(transcript) || !selected.equals(tx.getVersionedTranscriptId()))
                throw new IllegalArgumentException("PVS1_START_LOSS_TRANSCRIPT_IDENTITY_CONFLICT");
            JsonNode allele = context.at("/variant/evidenceQueryAllele");
            if (!"GRCh38".equals(allele.path("assembly").asText()) || !"SNV".equals(context.at("/variant/variantType").asText()))
                throw new IllegalArgumentException("PVS1_START_LOSS_SCOPE_UNSUPPORTED");
            int genomic = allele.path("position").asInt(-1);
            String genomicRef = allele.path("reference").asText(), genomicAlt = allele.path("alternate").asText();
            if (genomic < 1 || !BASES.contains(genomicRef) || !BASES.contains(genomicAlt) || genomicRef.equals(genomicAlt))
                throw new IllegalArgumentException("PVS1_START_LOSS_SCOPE_UNSUPPORTED");
            String cds = model.path("cdsSequence").asText();
            if (!cds.matches("[ACGT]+") || cds.length() != model.path("cdsLength").asInt()
                    || cds.length() < 6 || cds.length() % 3 != 0 || !cds.startsWith("ATG")
                    || !Set.of("TAA", "TAG", "TGA").contains(cds.substring(cds.length() - 3)))
                throw new IllegalArgumentException("PVS1_START_LOSS_REFERENCE_SEQUENCE_INVALID");
            int strand = model.path("strand").asInt();
            int cdsPosition = cdsPosition(model, genomic);
            if (cdsPosition < 1 || cdsPosition > 3)
                throw new IllegalArgumentException("PVS1_START_LOSS_NOT_START_CODON");
            String ref = strand == 1 ? genomicRef : complement(genomicRef);
            String alt = strand == 1 ? genomicAlt : complement(genomicAlt);
            if (!cds.substring(cdsPosition - 1, cdsPosition).equals(ref))
                throw new IllegalArgumentException("PVS1_START_LOSS_REFERENCE_ALLELE_CONFLICT");
            Matcher hgvs = HGVS.matcher(tx.getHgvsc() == null ? "" : tx.getHgvsc());
            if (!hgvs.matches() || !selected.equals(hgvs.group(1))
                    || Integer.parseInt(hgvs.group(2)) != cdsPosition
                    || !ref.equals(hgvs.group(3)) || !alt.equals(hgvs.group(4))
                    || !terms(tx).contains("start_lost"))
                throw new IllegalArgumentException("PVS1_START_LOSS_ANNOTATION_CONFLICT");
            String mutant = cds.substring(0, cdsPosition - 1) + alt + cds.substring(cdsPosition);
            Integer restart = null;
            for (int position = 4; position <= mutant.length() - 3; position += 3)
                if ("ATG".equals(mutant.substring(position - 1, position + 2))) { restart = position; break; }
            facts.put("status", "AVAILABLE").put("selectedTranscript", selected).put("strand", strand)
                    .put("cdsPosition", cdsPosition).put("referenceCodon", cds.substring(0, 3))
                    .put("alternateCodon", mutant.substring(0, 3)).put("candidateRestartInterpretation", "POTENTIAL_ATG_NOT_OBSERVED_TRANSLATION");
            if (restart == null) facts.putNull("candidateRestartCdsPosition"); else facts.put("candidateRestartCdsPosition", restart);
            return facts;
        } catch (IllegalArgumentException invalid) {
            reason = invalid.getMessage() == null ? reason : invalid.getMessage();
        } catch (RuntimeException invalid) {
            reason = "PVS1_START_LOSS_INPUT_INVALID";
        }
        facts.put("status", reason.endsWith("UNSUPPORTED") || reason.endsWith("NOT_START_CODON") ? "UNSUPPORTED"
                : reason.endsWith("CONFLICT") || reason.endsWith("INVALID") ? "CONFLICT" : "UNRESOLVED");
        facts.put("reasonCode", reason);
        return facts;
    }

    static ObjectNode resolve(ObjectMapper mapper, JsonNode context, TranscriptConsequence tx,
                              JsonNode policy, JsonNode model, JsonNode runtimeMaterial,
                              RuntimeEvidenceV2Adapter adapter) {
        ObjectNode facts = sequence(mapper, context, tx, model);
        if (!"AVAILABLE".equals(facts.path("status").asText())) return facts;
        try {
            return assess(mapper, context, policy, model, runtimeMaterial, adapter, facts);
        } catch (RuntimeException invalid) {
            return facts.put("status", "CONFLICT").put("reasonCode", "PVS1_START_LOSS_INPUT_INVALID");
        }
    }

    private static ObjectNode assess(ObjectMapper mapper, JsonNode context, JsonNode policy, JsonNode model,
                                     JsonNode runtimeMaterial, RuntimeEvidenceV2Adapter adapter, ObjectNode facts) {
        if (!"REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText()))
            return facts.put("status", "UNRESOLVED").put("reasonCode", "PVS1_START_LOSS_POLICY_OR_LOF_REVIEW_UNAVAILABLE");
        if (!context.at("/gene/hgncId").equals(policy.at("/scope/hgncId"))
                || !context.at("/disease/mondoId").equals(policy.at("/scope/mondoId"))
                || !context.path("moi").equals(policy.at("/scope/moi"))
                || !context.at("/variant/evidenceQueryAllele/assembly").equals(policy.at("/scope/assembly")))
            return facts.put("status", "UNRESOLVED").put("reasonCode", "PVS1_START_LOSS_POLICY_CONTEXT_MISMATCH");
        String policyStatus = Pvs1StartLossPolicy.status(policy);
        Pvs1StartLossRuntimeMaterial.Verified reviewed = "PERMITS".equals(policyStatus)
                ? Pvs1StartLossRuntimeMaterial.validate(mapper, adapter, runtimeMaterial, context, policy, facts, model)
                : Pvs1StartLossRuntimeMaterial.invalid("MATERIAL_NOT_REQUIRED");
        String transcriptStatus = "INVALID".equals(reviewed.transcriptStatus()) ? "UNRESOLVED"
                : "FOUND_FUNCTIONAL_ALTERNATIVE".equals(reviewed.transcriptStatus())
                ? "FOUND_FUNCTIONAL_ALTERNATIVE" : "NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET";
        boolean materialBound = reviewed.reasonCode() == null;
        String upstreamStatus = materialBound && reviewed.qualifyingIndependentPathogenicReference() ? "QUALIFYING_FOUND"
                : materialBound && reviewed.upstreamComplete() ? "COMPLETE" : "INCOMPLETE";
        facts.put("transcriptReviewStatus", materialBound ? reviewed.transcriptStatus() : "NOT_PREPARED")
                .put("upstreamReferenceReviewStatus", materialBound ? reviewed.upstreamStatus() : "NOT_PREPARED")
                .put("reviewReasonCode", materialBound ? "PVS1_START_LOSS_MATERIALS_REPLAYED" : reviewed.reasonCode());
        // Failed qualification is still an auditable result, not an empty page.
        if (!reviewed.evidenceSummary().isEmpty()) facts.set("materialEvidence", reviewed.evidenceSummary().deepCopy());
        if (materialBound) {
            facts.put("transcriptReviewComplete", reviewed.transcriptComplete())
                    .put("upstreamSearchComplete", reviewed.upstreamComplete())
                    .put("qualifyingReferenceCount", reviewed.referenceCount())
                    .put("runtimeMaterialSha256", adapter.canonicalSha256(runtimeMaterial));
            var usedSources = facts.putArray("sourceBindings");
            for (var source : policy.at("/startLossAssessment/sourceBindings"))
                if (reviewed.upstreamComplete() || reviewed.qualifyingIndependentPathogenicReference() || "TRANSCRIPT_REVIEW".equals(source.path("role").asText()))
                    usedSources.add(source.deepCopy());
            facts.set("materialEvidence", reviewed.evidenceSummary().deepCopy());
        }
        var decision = Pvs1StartLossDecision.evaluate(new Pvs1StartLossDecision.Facts("AVAILABLE",
                policyStatus,
                "ESTABLISHED_LOSS_OF_FUNCTION".equals(policy.at("/mechanism/status").asText()) ? "ESTABLISHED_LOF"
                        : "ESTABLISHED_NOT_LOSS_OF_FUNCTION".equals(policy.at("/mechanism/status").asText()) ? "NOT_LOF" : "UNRESOLVED",
                transcriptStatus, materialBound && reviewed.transcriptComplete(),
                facts.path("candidateRestartCdsPosition").isIntegralNumber() ? facts.path("candidateRestartCdsPosition").asInt() : null,
                upstreamStatus, materialBound && reviewed.upstreamComplete(),
                materialBound && reviewed.qualifyingIndependentPathogenicReference()));
        if (!materialBound && "PERMITS".equals(policyStatus)
                && "ESTABLISHED_LOSS_OF_FUNCTION".equals(policy.at("/mechanism/status").asText())) {
            String materialReason = reviewed.reasonCode();
            var outcome = materialReason != null && materialReason.endsWith("REFERENCE_CONFLICT")
                    ? Pvs1StartLossDecision.Outcome.CONFLICT
                    : materialReason != null && materialReason.endsWith("SOURCE_UNAVAILABLE")
                    ? Pvs1StartLossDecision.Outcome.SOURCE_UNAVAILABLE : Pvs1StartLossDecision.Outcome.INSUFFICIENT_INFORMATION;
            decision = new Pvs1StartLossDecision.Result(outcome, "NONE", materialReason);
        }
        facts.put("decisionOutcome", decision.outcome().name()).put("decisionStrength", decision.strength())
                .put("decisionReasonCode", decision.reasonCode());
        return facts;
    }

    private static int cdsPosition(JsonNode model, int genomic) {
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode exon : model.path("exons"))
            if (genomic >= exon.path("genomicStart").asInt() && genomic <= exon.path("genomicEnd").asInt()) matches.add(exon);
        if (matches.size() != 1) throw new IllegalArgumentException("PVS1_START_LOSS_GENOMIC_MAPPING_UNRESOLVED");
        JsonNode exon = matches.get(0);
        int position = model.path("strand").asInt() == 1
                ? exon.path("cdsStart").asInt() + genomic - exon.path("genomicStart").asInt()
                : exon.path("cdsStart").asInt() + exon.path("genomicEnd").asInt() - genomic;
        if (position < exon.path("cdsStart").asInt() || position > exon.path("cdsEnd").asInt())
            throw new IllegalArgumentException("PVS1_START_LOSS_VARIANT_OUTSIDE_CDS");
        return position;
    }

    private static String complement(String base) {
        return switch (base) { case "A" -> "T"; case "T" -> "A"; case "C" -> "G"; case "G" -> "C"; default -> ""; };
    }

    private static Set<String> terms(TranscriptConsequence tx) {
        return new java.util.HashSet<>(java.util.Arrays.asList((tx.getConsequence() == null ? "" : tx.getConsequence()).split("[&,|]")));
    }
}
