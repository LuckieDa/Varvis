package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;

/** Reuses the server-owned native preparation's exact GENCODE annotation, without additional I/O.
 * As with Bp7PredictionDependency, the caller must not derive its trusted plan from a submitted row.
 * Geometry and computational readiness are distinct from final BP7 adoption. */
public final class Bp7PositionAssessment {
    public static final String VERSION = "1.0.0";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final Bp7PredictionDependency dependency;
    private final ObjectNode policy;

    static boolean consequenceApplicable(Set<String> terms) {
        return (terms.contains("synonymous_variant") || terms.contains("intron_variant"))
                && Set.of("synonymous_variant", "intron_variant", "splice_region_variant",
                "splice_donor_region_variant", "splice_polypyrimidine_tract_variant").containsAll(terms);
    }

    public Bp7PositionAssessment(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        dependency = new Bp7PredictionDependency(mapper); policy = dependency.policy();
    }

    /** Computes both transcript-oriented boundaries; never infers coordinates from EXON/INTRON ordinals. */
    public ObjectNode assess(@Nullable ObjectNode preparedPlan, @Nullable VariantInterpretation candidate) {
        var out = mapper.createObjectNode().put("schemaVersion", "bp7-position-assessment-v1").put("methodVersion", VERSION)
                .put("status", "NOT_EVALUATED").put("coordinateSystem", "ONE_BASED_CLOSED")
                .put("providerCalls", 0).put("annotationReused", true).put("finalEvaluationProduced", false);
        out.put("policySha256", adapter.canonicalSha256(policy)); out.set("positionRules", policy.path("positionRules").deepCopy());
        try {
            require(preparedPlan != null && candidate != null, "BP7_PREPARED_CONTEXT_REQUIRED");
            if (preparedPlan == null || candidate == null) return finish(out);
            require("selected-prediction-plan-v1".equals(preparedPlan.path("schemaVersion").asText())
                    && "PREPARED".equals(preparedPlan.path("status").asText()), "BP7_PREPARED_PLAN_UNAVAILABLE");
            require(preparedPlan.path("context") instanceof ObjectNode, "BP7_PREPARED_CONTEXT_REQUIRED");
            var context = (ObjectNode) preparedPlan.path("context"); adapter.validateCompleteContext(context);
            require(adapter.canonicalContextId(context).equals(context.path("contextId").asText()), "BP7_CONTEXT_IDENTITY_INVALID");
            require(preparedPlan.path("sourceProjection").isObject() && candidate.getCandidateId().equals(preparedPlan.path("candidateId").asText()),
                    "BP7_SOURCE_PROJECTION_MISMATCH");
            out.put("contextId", context.path("contextId").asText()).put("preparedPlanSha256", adapter.canonicalSha256(preparedPlan));
            var tx = new RuntimeEvidenceV2ProviderWiring(mapper, adapter).selectedVepConsequence(context, candidate);
            require(tx != null, "BP7_SELECTED_TRANSCRIPT_NOT_UNIQUE");
            if (tx == null) return finish(out);
            var terms = new java.util.HashSet<>(List.of(Objects.toString(tx.getConsequence(), "").split("[&,|]")));
            boolean synonymous = terms.contains("synonymous_variant"), intronic = terms.contains("intron_variant");
            require(!(synonymous && intronic), "BP7_CONSEQUENCE_AMBIGUOUS");
            if (!consequenceApplicable(terms))
                return finish(out.put("status", "NOT_APPLICABLE").put("reasonCode", "BP7_CONSEQUENCE_NOT_APPLICABLE"));
            var key = candidate.getObservation() == null ? null : candidate.getObservation().getKey();
            require(key != null && "GRCh38".equals(key.getGenomeAssembly()) && key.getPosition() > 0
                    && key.getReference() != null && key.getReference().matches("[ACGT]")
                    && key.getAlternate() != null && key.getAlternate().matches("[ACGT]")
                    && !key.getReference().equals(key.getAlternate())
                    && "NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText()), "BP7_NATIVE_GRCH38_SNV_REQUIRED");
            if (key == null) return finish(out);
            var allele = context.at("/variant/evidenceQueryAllele");
            require("GRCh38".equals(allele.path("assembly").asText()) && Objects.equals(key.getChromosome(), allele.path("contig").asText())
                    && key.getPosition() == allele.path("position").asLong() && key.getReference().equals(allele.path("reference").asText())
                    && key.getAlternate().equals(allele.path("alternate").asText()) && same(allele, preparedPlan.path("queryAllele")), "BP7_QUERY_ALLELE_MISMATCH");
            var nativePlan = preparedPlan.path("nativeSplicePreparation");
            require("PREPARED".equals(nativePlan.path("status").asText()), "BP7_NATIVE_PREPARATION_UNAVAILABLE");
            var identity = nativePlan.path("identity");
            require(policy.at("/predictionBinding/method").equals(identity.path("method"))
                    && policy.at("/predictionBinding/runtimeVersion").equals(identity.path("version")), "BP7_NATIVE_METHOD_INCOMPATIBLE");
            var gencode = identity.path("gencode"); var model = nativePlan.path("annotation");
            require("EXACT_GENCODE_LOCAL_ANNOTATION".equals(gencode.path("method").asText())
                    && GencodeTranscriptIndex.VERSION.equals(gencode.path("version").asText())
                    && sha(gencode.path("manifestSha256")) && sha(gencode.path("sourceGtfSha256"))
                    && gencode.path("release").isIntegralNumber() && gencode.path("release").canConvertToInt() && gencode.path("release").asInt() > 0
                    && sha(model.path("rawGtfSha256")), "BP7_POSITION_SOURCE_IDENTITY_REQUIRED");
            require("GRCh38".equals(model.path("assembly").asText()) && model.path("hgncId").equals(context.at("/gene/hgncId")),
                    "BP7_POSITION_SOURCE_CONTEXT_MISMATCH");
            var annotation = NativeSpliceAiRuntime.selectedAnnotation(mapper, model);
            require(same(annotation, nativePlan.path("selectedAnnotation"))
                    && model.path("transcript").asText().equals(tx.getVersionedTranscriptId())
                    && model.path("geneSymbol").asText().equals(tx.getGeneSymbol()) && tx.getGeneId() != null
                    && model.path("geneId").asText().replaceFirst("\\.[0-9]+$", "").equals(tx.getGeneId().replaceFirst("\\.[0-9]+$", ""))
                    && model.path("contig").asText().equals(key.getChromosome()), "BP7_SELECTED_ANNOTATION_MISMATCH");
            var query = mapper.createObjectNode(); query.set("allele", allele); query.set("annotation", annotation);
            require(("selected-" + adapter.canonicalSha256(query)).equals(nativePlan.path("rawQueryId").asText()), "BP7_NATIVE_QUERY_IDENTITY_INVALID");
            require(sha(context.at("/variant/normalization/referenceSequenceSha256"))
                    && context.at("/variant/normalization/referenceSequenceSha256").equals(nativePlan.at("/referenceWindow/qualifiedReference/expectedSha256")),
                    "BP7_REFERENCE_BINDING_INVALID");
            var source = out.putObject("source"); source.set("gencode", gencode.deepCopy());
            source.put("annotationSha256", adapter.canonicalSha256(model)).put("rawGtfRowSha256", model.path("rawGtfSha256").asText())
                    .put("nativeIdentitySha256", adapter.canonicalSha256(identity)).put("rawQueryId", nativePlan.path("rawQueryId").asText());
            out.set("queryAllele", allele.deepCopy()); out.set("selectedAnnotation", annotation);
            out.put("selectedTranscriptId", tx.getVersionedTranscriptId()).put("consequenceClass", synonymous ? "SYNONYMOUS" : "INTRONIC");
            long position = key.getPosition(); var exons = annotation.path("exons"); int strand = model.path("strand").asInt();
            int containing = -1, gap = -1;
            for (int i = 0; i < exons.size(); i++) {
                long start = exons.get(i).get(0).asLong(), end = exons.get(i).get(1).asLong();
                if (position >= start && position <= end) containing = i;
                if (i + 1 < exons.size() && position > end && position < exons.get(i + 1).get(0).asLong()) gap = i;
            }
            require(containing >= 0 || gap >= 0, "BP7_POSITION_OUTSIDE_TRANSCRIPT");
            require(synonymous ? containing >= 0 : gap >= 0, "BP7_CONSEQUENCE_STRUCTURE_CONFLICT");
            var geometry = out.putObject("geometry").put("strand", strand);
            var exclusions = out.putArray("excludedReasons");
            if (synonymous) {
                long start = exons.get(containing).get(0).asLong(), end = exons.get(containing).get(1).asLong();
                long fromStart = strand == 1 ? position - start : end - position;
                long fromEnd = strand == 1 ? end - position : position - start;
                geometry.put("exonStart", start).put("exonEnd", end).put("transcriptExonOrdinal", strand == 1 ? containing + 1 : exons.size() - containing)
                        .put("zeroBasedDistanceFromExonStart", fromStart).put("zeroBasedDistanceFromExonEnd", fromEnd);
                JsonNode codingExon = mapper.missingNode();
                for (var exon : model.path("exons")) if (exon.path("genomicStart").asLong() == start && exon.path("genomicEnd").asLong() == end) codingExon = exon;
                require(model.path("cdsLength").isIntegralNumber() && model.path("cdsLength").canConvertToInt() && model.path("cdsLength").asInt() > 0
                        && codingExon.path("cdsStart").isIntegralNumber() && codingExon.path("cdsStart").canConvertToInt()
                        && codingExon.path("cdsEnd").isIntegralNumber() && codingExon.path("cdsEnd").canConvertToInt()
                        && codingExon.path("cdsEnd").asLong() - codingExon.path("cdsStart").asLong() == end - start, "BP7_CODING_POSITION_UNAVAILABLE");
                long codingPosition = codingExon.path("cdsStart").asLong() + fromStart;
                require(codingPosition >= 1 && codingPosition <= model.path("cdsLength").asLong(), "BP7_CONSEQUENCE_CODING_CONFLICT");
                geometry.put("codingPosition", codingPosition);
                var proof = tx.getSynonymousExonPosition();
                if (proof != null) require(proof.matches(key, tx.getVersionedTranscriptId()) && proof.annotationVersion().equals(tx.getVersion())
                        && proof.strand() == strand && proof.exonStart() == start && proof.exonEnd() == end, "BP7_VEP_POSITION_CONFLICT");
                out.put("vepPositionCrossCheck", proof == null ? "NO_POSITION_OBSERVATION" : "MATCHED");
                require(tx.getBp7IntronicSignedOffset() == null, "BP7_HGVS_POSITION_CONFLICT");
                if (policy.at("/positionRules/synonymous/firstExonBaseExcluded").asBoolean() && fromStart == 0) exclusions.add("BP7_SYNONYMOUS_EXON_FIRST_BASE");
                if (fromEnd < policy.at("/positionRules/synonymous/minimumZeroBasedDistanceFromExonEndInclusive").asInt()) exclusions.add("BP7_SYNONYMOUS_EXON_LAST_THREE");
            } else {
                long leftEnd = exons.get(gap).get(1).asLong(), rightStart = exons.get(gap + 1).get(0).asLong();
                long donor = strand == 1 ? position - leftEnd : rightStart - position;
                long acceptor = strand == 1 ? rightStart - position : position - leftEnd;
                geometry.put("intronStart", leftEnd + 1).put("intronEnd", rightStart - 1)
                        .put("transcriptIntronOrdinal", strand == 1 ? gap + 1 : exons.size() - gap - 1)
                        .put("donorBoundary", strand == 1 ? leftEnd : rightStart).put("acceptorBoundary", strand == 1 ? rightStart : leftEnd)
                        .put("donorDistance", donor).put("acceptorDistance", acceptor);
                require(tx.getSynonymousExonPosition() == null, "BP7_VEP_POSITION_CONFLICT");
                Integer offset = tx.getBp7IntronicSignedOffset();
                if (tx.getHgvsCoding() != null && !tx.getHgvsCoding().isBlank()) {
                    require(offset != null && (offset > 0 ? (long) offset == donor : -(long) offset == acceptor), "BP7_HGVS_POSITION_CONFLICT");
                    geometry.put("observedHgvsSignedOffset", offset);
                    out.put("hgvsOffsetCrossCheck", "MATCHED");
                } else out.put("hgvsOffsetCrossCheck", "NO_POSITION_OBSERVATION");
                // Only the intronic offset is cross-checked here; this is not a complete HGVS validator.
                out.put("hgvsCodingAnchorValidated", false);
                if (donor < policy.at("/positionRules/intronic/donorDistanceMinimumInclusive").asInt()) exclusions.add("BP7_INTRONIC_DONOR_PROXIMAL");
                if (acceptor < policy.at("/positionRules/intronic/acceptorDistanceMinimumInclusive").asInt()) exclusions.add("BP7_INTRONIC_ACCEPTOR_PROXIMAL");
            }
            out.put("status", exclusions.isEmpty() ? "ELIGIBLE" : "EXCLUDED")
                    .put("reasonCode", exclusions.isEmpty() ? "BP7_POSITION_ELIGIBLE" : "BP7_POSITION_SPLICE_PROXIMAL");
        } catch (IllegalArgumentException invalid) {
            out.put("reasonCode", code(invalid, "BP7_POSITION_INPUT_INVALID"));
        }
        return finish(out);
    }

    /** Rechecks both inputs rather than trusting externally supplied assessment flags. */
    public ObjectNode inspect(@Nullable ObjectNode preparedPlan, @Nullable VariantInterpretation candidate, @Nullable ObjectNode savedPrediction) {
        var position = assess(preparedPlan, candidate); var prediction = dependency.inspect(preparedPlan, candidate, savedPrediction);
        var out = mapper.createObjectNode().put("schemaVersion", "bp7-computational-checks-v1").put("methodVersion", VERSION)
                .put("status", "NOT_EVALUATED").put("appliedStrength", "NONE").put("finalEvaluationProduced", false)
                .put("experimentalConflictCheck", "PENDING").put("providerCalls", 0);
        out.set("position", position); out.set("predictionDependency", prediction);
        String state = position.path("status").asText();
        if ("EXCLUDED".equals(state) || "NOT_APPLICABLE".equals(state)) out.put("status", "NOT_APPLICABLE").put("reasonCode", position.path("reasonCode").asText());
        else if (!"ELIGIBLE".equals(state)) out.put("reasonCode", position.path("reasonCode").asText());
        else if ("READY_FOR_POSITION_AND_CONFLICT_CHECKS".equals(prediction.path("status").asText()))
            out.put("status", "READY_FOR_EXPERIMENTAL_CONFLICT_CHECK").put("reasonCode", "BP7_COMPUTATIONAL_CHECKS_PASSED");
        else out.put("status", prediction.path("status").asText()).put("reasonCode", prediction.path("reasonCode").asText());
        out.put("assessmentId", "bp7-computational-sha256-" + adapter.canonicalSha256(out)); return out;
    }
    private ObjectNode finish(ObjectNode out) { out.put("positionId", "bp7-position-sha256-" + adapter.canonicalSha256(out)); return out; }
    private boolean same(JsonNode a, JsonNode b) { return !a.isMissingNode() && !b.isMissingNode() && adapter.canonicalSha256(a).equals(adapter.canonicalSha256(b)); }
    private static boolean sha(JsonNode value) { return value.isTextual() && value.asText().matches("[0-9a-f]{64}"); }
    private static void require(boolean ok, String reason) { if (!ok) throw new IllegalArgumentException(reason); }
    private static String code(IllegalArgumentException error, String fallback) {
        String message = error.getMessage(); return message != null && message.matches("[A-Z][A-Z0-9_]+") ? message : fallback;
    }
}
