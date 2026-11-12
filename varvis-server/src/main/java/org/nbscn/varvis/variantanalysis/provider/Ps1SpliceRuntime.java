package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;
import org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks.CatalogSnapshot;
import static org.nbscn.varvis.variantanalysis.provider.Ps1SpliceComparison.require;

/** Normalized selected candidate -> local bounded discovery -> source-qualified reference -> native comparison. */
public final class Ps1SpliceRuntime {
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final CodingTranscriptMaterialSupply materials;
    private final Ps1SpliceKnowledge knowledge;
    private final ClinVarTabixProvider clinvar;
    private final MissenseNativeSpliceBridge nativeRaw;
    private final CatalogSnapshot experiments;
    private final GencodeTranscriptIndex annotationIndex;
    public Ps1SpliceRuntime(ObjectMapper mapper, CodingTranscriptMaterialSupply materials, Ps1SpliceKnowledge knowledge,
            ClinVarTabixProvider clinvar, MissenseNativeSpliceBridge nativeRaw, CatalogSnapshot experiments, GencodeTranscriptIndex annotationIndex) {
        this.mapper = mapper; adapter = new RuntimeEvidenceV2Adapter(mapper);
        this.materials = materials; this.knowledge = knowledge; this.clinvar = clinvar; this.nativeRaw = nativeRaw; this.experiments = experiments; this.annotationIndex = annotationIndex;
    }

    public ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, @Nullable JsonNode pvs1) {
        var out = mapper.createObjectNode().put("mode", "PS1_SPLICE_COMPARISON").put("methodVersion", Ps1SpliceComparison.VERSION)
                .put("contextId", context.path("contextId").asText()).put("providerCalls", 0).put("rawBatchCalls", 0);
        out.set("knowledgeIdentity", knowledge.identity(mapper));
        out.put("scientificApprovalGranted", false).put("observedRna", false);
        try {
            adapter.validateCompleteContext(context);
            require(adapter.canonicalContextId(context).equals(context.path("contextId").asText()), "PS1_SPLICE_CONTEXT_ID_MISMATCH");
            require(Set.of("SYNTHETIC", "PRIVACY_BOUNDED_RUNTIME").contains(context.path("dataClass").asText()), "PS1_SPLICE_RESEARCH_USE_REQUIRED");
            JsonNode target = context.at("/variant/evidenceQueryAllele");
            require("GRCh38".equals(target.path("assembly").asText()) && "NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                    && target.path("reference").asText().matches("[ACGT]") && target.path("alternate").asText().matches("[ACGT]"),
                    "PS1_SPLICE_NATIVE_SNV_SCOPE_REQUIRED");
            var supplied = materials.resolveStructure(context, candidate); out.set("sequenceMaterial", supplied.snapshot());
            require(supplied.ready() && supplied.model() != null, supplied.snapshot().path("reasonCode").asText("PS1_SPLICE_TRANSCRIPT_UNAVAILABLE"));
            ObjectNode model = java.util.Objects.requireNonNull(supplied.model());
            require(CodingTranscriptMapping.modelIssue(model, context) == null, "PS1_SPLICE_TRANSCRIPT_INVALID");
            var nativeAnnotation = annotationIndex.annotation(context);
            out.set("nativeAnnotationSource", annotationIndex.identity()); out.set("nativeAnnotation", nativeAnnotation);
            var tx = new RuntimeEvidenceV2ProviderWiring(mapper, adapter).selectedVepConsequence(context, candidate);
            if (!(tx != null && tx.getGeneId() != null)) {
                throw new IllegalArgumentException("PS1_SPLICE_TRANSCRIPT_INVALID");
            }
            model = bindNativeAnnotation(model, nativeAnnotation, tx.getGeneId());
            out.put("transcript", model.path("transcript").asText());
            var policy = knowledge.select(context, model); out.set("policy", policy);
            var tm = Ps1SpliceComparison.motif(mapper, model, target.path("position").asInt()); out.set("targetMotif", tm);
            out.set("registeredRnaSnapshot", experiments.content()); checkRecordedRna(target);
            // Protein effects of exonic changes require separate comparison. This first branch does not guess them.
            require(!tm.path("exonic").asBoolean(), "PS1_SPLICE_EXONIC_PROTEIN_REVIEW_REQUIRED");
            out.set("motifReferenceProof", nativeRaw.verifyGtAgMotif(context, tm));
            String baseline = tm.path("canonical").asBoolean() ? pvs1Baseline(context, pvs1) : "PP3";
            if (baseline.isEmpty() && "SOURCE_ORIGINAL_EXPERT_PVS1".equals(policy.path("targetBaselineMode").asText())) {
                require(sourceBaselineAllowed(context, pvs1), "PS1_SPLICE_BASELINE_CURRENT_RESULT_CONFLICT");
                var existing = knowledge.targetBaseline(mapper, context, model, policy);
                out.set("baselineSource", existing); baseline = existing.path("strength").asText();
            }
            require(!baseline.isEmpty(), "PS1_SPLICE_PVS1_BASELINE_REQUIRED");
            out.put("baseline", baseline);
            if (!out.has("baselineSource")) out.putObject("baselineSource").put("origin", tm.path("canonical").asBoolean() ? "CURRENT_CONTEXT_PVS1" : "TARGET_SPLICE_PREDICTION")
                    .put("strength", baseline).put("currentPvs1EvaluationCreated", false);
            var discovery = clinvar.discoverBoundedComparators(key(target), tm.path("start").asInt(), tm.path("end").asInt());
            out.put("providerCalls", 1); out.set("discovery", mapper.valueToTree(discovery));
            require("READY".equals(discovery.getReadinessStatus()), "PS1_SPLICE_REFERENCE_QUERY_UNAVAILABLE");
            require(discovery.getCandidates().size() <= 64, "PS1_SPLICE_REFERENCE_SEARCH_LIMIT");
            var eligible = new ArrayList<JsonNode>(); var assessments = out.putArray("referenceAssessments");
            for (var found : discovery.getCandidates()) {
                var assessment = assessments.addObject().put("variationId", String.valueOf(found.getVariationId()));
                JsonNode record = null;
                for (var ref : knowledge.references()) if (ref.path("variationId").asText().equals(String.valueOf(found.getVariationId()))
                        && key(ref.path("allele")).equals(found.getExactAllele())) {
                    require(record == null, "PS1_SPLICE_REFERENCE_AMBIGUOUS"); record = ref;
                }
                if (record == null) { assessment.put("status", "NOT_ADOPTED").put("reasonCode", "PS1_SPLICE_REFERENCE_SOURCE_MISSING"); continue; }
                try {
                    require(!target.equals(record.path("allele")), "PS1_SPLICE_SELF_REFERENCE");
                    var rm = Ps1SpliceComparison.motif(mapper, model, record.at("/allele/position").asInt());
                    checkRecordedRna(record.path("allele"));
                    require(!rm.path("exonic").asBoolean(), "PS1_SPLICE_EXONIC_PROTEIN_REVIEW_REQUIRED");
                    var qualified = Ps1SpliceKnowledge.qualify(mapper, record, policy);
                    var ref = ((ObjectNode) record).deepCopy(); ref.set("qualification", qualified); ref.set("motif", rm);
                    eligible.add(ref); assessment.put("status", "SOURCE_QUALIFIED"); assessment.set("qualification", qualified);
                } catch (IllegalArgumentException missing) { assessment.put("status", "NOT_ADOPTED").put("reasonCode", reason(missing)); }
            }
            require(!eligible.isEmpty(), "PS1_SPLICE_NO_INDEPENDENT_REFERENCE");
            require(eligible.size() <= 9, "PS1_SPLICE_REFERENCE_EXECUTION_LIMIT");
            var queries = new ArrayList<SpliceAiRawBatchRunner.Query>();
            queries.add(new SpliceAiRawBatchRunner.Query("TARGET", key(target), model.path("geneSymbol").asText(), model.path("transcript").asText()));
            for (var ref : eligible) queries.add(new SpliceAiRawBatchRunner.Query(ref.path("scv").asText(), key(ref.path("allele")), model.path("geneSymbol").asText(), model.path("transcript").asText()));
            var batch = nativeRaw.run(context, model, queries); out.put("rawBatchCalls", 1); out.set("nativeAudit", nativeRaw.audit());
            require("SUCCEEDED".equals(batch.getStatus()) && batch.getResult() != null, batch.getReasonCode() == null ? "PS1_SPLICE_NATIVE_UNAVAILABLE" : batch.getReasonCode());
            var raw = java.util.Objects.requireNonNull(batch.getResult()); out.set("nativeFacts", raw);
            String eventScope = policy.path("eventScope").asText("SINGLE_NATIVE_SITE_LOSS");
            var targetScore = score(raw, "TARGET", model); var targetEvent = Ps1SpliceComparison.event(mapper, targetScore, target, tm, eventScope);
            out.set("targetEvent", targetEvent);
            var comparisons = out.putArray("comparisons"); var accepted = new ArrayList<ObjectNode>();
            for (var ref : eligible) {
                var row = comparisons.addObject().put("scv", ref.path("scv").asText()); row.set("reference", ref);
                try {
                    var event = Ps1SpliceComparison.event(mapper, score(raw, ref.path("scv").asText(), model), ref.path("allele"), ref.path("motif"), eventScope);
                    var result = Ps1SpliceComparison.compare(mapper, target, ref.path("allele"), tm, ref.path("motif"), targetEvent, event,
                            ref.path("classification").asText(), ref.at("/qualification/clinicalSupport").asBoolean(), baseline, policy.path("similarityMode").asText());
                    row.set("comparison", result); accepted.add(row);
                } catch (IllegalArgumentException missing) { row.put("reasonCode", reason(missing)).put("status", "NOT_EVALUATED"); }
            }
            require(!accepted.isEmpty(), "PS1_SPLICE_NO_COMPARABLE_EVENT");
            accepted.sort(Comparator.comparingInt((ObjectNode r) -> Map.of("STRONG", 3, "MODERATE", 2, "SUPPORTING", 1).get(r.at("/comparison/appliedStrength").asText())).reversed());
            var best = accepted.get(0); out.set("selectedComparison", best.deepCopy());
            return finish(out, "MET", "PS1_SAME_PREDICTED_NATIVE_SPLICE_LOSS", best.at("/comparison/appliedStrength").asText());
        } catch (Exception failed) { return finish(out, "NOT_EVALUATED", reason(failed), "NONE"); }
    }

    private JsonNode score(JsonNode batch, String id, JsonNode model) {
        JsonNode found = null;
        for (var row : batch.path("results")) if (id.equals(row.path("queryId").asText())) {
            require(found == null, "PS1_SPLICE_RAW_AMBIGUOUS"); found = row;
        }
        require(found != null, "PS1_SPLICE_RAW_MISSING");
        return NativeSpliceAiRuntime.associate(mapper, java.util.Objects.requireNonNull(found), model);
    }
    /** Old sequence models can omit ENSG. Fill only from an exact pinned GENCODE row after structural and VEP agreement. */
    static ObjectNode bindNativeAnnotation(ObjectNode model, JsonNode annotation, String vepGeneId) {
        for (String field : List.of("transcript", "geneSymbol", "hgncId", "assembly", "contig", "strand"))
            require(model.path(field).equals(annotation.path(field)), "PS1_SPLICE_NATIVE_MODEL_MISMATCH");
        require(annotation.path("geneId").asText().matches("ENSG[0-9]+(?:\\.[1-9][0-9]*)?")
                && annotation.path("geneId").asText().replaceFirst("\\.[0-9]+$", "").equals(vepGeneId.replaceFirst("\\.[0-9]+$", ""))
                && (!model.has("geneId") || model.path("geneId").equals(annotation.path("geneId")))
                && model.path("exons").size() == annotation.path("exons").size(), "PS1_SPLICE_NATIVE_MODEL_MISMATCH");
        for (int i=0; i<model.path("exons").size(); i++) for (String field : List.of("genomicStart", "genomicEnd", "cdsStart", "cdsEnd"))
            require(model.path("exons").get(i).path(field).equals(annotation.path("exons").get(i).path(field)), "PS1_SPLICE_NATIVE_MODEL_MISMATCH");
        return model.deepCopy().put("geneId", annotation.path("geneId").asText());
    }
    private void checkRecordedRna(JsonNode allele) {
        var material = experiments.content().path("material");
        require("AVAILABLE".equals(material.path("status").asText()), "PS1_SPLICE_REGISTERED_RNA_SOURCE_UNAVAILABLE");
        for (var record : material.at("/catalog/records"))
            require(!allele.equals(record.path("allele")), "PS1_SPLICE_RECORDED_RNA_REVIEW_REQUIRED");
    }
    static String pvs1Baseline(ObjectNode context, @Nullable JsonNode result) {
        if (result == null || !context.path("contextId").equals(result.at("/context/contextId"))) return "";
        JsonNode c = result.path("evaluation");
        if (context.path("contextId").equals(c.path("contextId")) && "ACMG_AMP_2015:PVS1".equals(c.path("criterionId").asText())
                && "MET".equals(c.at("/evaluationResult/status").asText())) {
            String strength = c.path("appliedStrength").asText();
            if ("VERY_STRONG".equals(strength)) return "PVS1";
            if (Set.of("STRONG", "MODERATE", "SUPPORTING").contains(strength)) return "PVS1_" + strength;
        }
        return "";
    }
    static boolean sourceBaselineAllowed(ObjectNode context, @Nullable JsonNode result) {
        if (result == null) return true;
        boolean bound = context.path("contextId").equals(result.at("/context/contextId"))
                || context.path("contextId").equals(result.at("/sourceProjection/evaluationContext/contextId"));
        JsonNode status = result.has("evaluation") ? result.at("/evaluation/evaluationResult") : result.path("evaluationResult");
        return bound && "NOT_EVALUATED".equals(status.path("status").asText())
                && "CLAUSE_SEARCH_SCOPE_NOT_COVERED".equals(status.path("reasonCode").asText())
                && result.path("evaluatorCalls").isIntegralNumber() && result.path("evaluatorCalls").asInt() == 0;
    }
    private ObjectNode finish(ObjectNode out, String status, String reason, String strength) {
        out.put("status", status).put("reasonCode", reason);
        out.putArray("criterionEvaluations").addObject().put("criterionId", Ps1SpliceComparison.CRITERION).put("appliedStrength", strength)
                .putObject("evaluationResult").put("status", status).put("reasonCode", reason);
        out.set("publicSummary", summary(mapper, out)); return out;
    }
    public static ObjectNode summary(ObjectMapper mapper, JsonNode result) {
        var out = mapper.createObjectNode().put("schemaVersion", "ps1-splice-facts-v1").put("methodVersion", Ps1SpliceComparison.VERSION)
                .put("status", result.path("status").asText()).put("reasonCode", result.path("reasonCode").asText())
                .put("scope", "MATCHED_NATIVE_LOSS_PROFILE".equals(result.at("/policy/eventScope").asText())
                        ? "INTRONIC_SNV_MATCHED_NATIVE_LOSS_PROFILE" : "INTRONIC_SNV_SINGLE_NATIVE_GT_AG_SITE_LOSS").put("observedRna", false)
                .put("transcript", result.path("transcript").asText()).put("baseline", result.path("baseline").asText());
        if (result.has("targetEvent")) out.set("targetEvent", result.path("targetEvent").deepCopy());
        if (result.has("baselineSource")) {
            var source = result.path("baselineSource"); var brief = out.putObject("baselineSource");
            for (String field : List.of("origin", "strength", "currentPvs1EvaluationCreated", "sourceTranscript", "runtimeTranscript", "codingHgvs"))
                if (source.has(field)) brief.set(field, source.path(field).deepCopy());
            if (source.has("assertions")) {
                var refs = brief.putArray("assertions");
                for (var row : source.path("assertions")) { var ref = refs.addObject();
                    for (String field : List.of("scv", "originalVersion", "evaluationDate", "xmlSha256")) ref.set(field, row.path("original").path(field).deepCopy());
                }
            }
        }
        out.put("rnaSearchCoverage", "REGISTERED_REPORTS_ONLY_NOT_EXHAUSTIVE");
        var reasons = out.putArray("unresolvedReasons"); var seen = new java.util.LinkedHashSet<String>();
        for (String field : List.of("referenceAssessments", "comparisons")) for (var row : result.path(field))
            if (row.path("reasonCode").isTextual() && seen.add(row.path("reasonCode").asText())) reasons.add(row.path("reasonCode").asText());
        if (result.has("selectedComparison")) {
            out.put("referenceScv", result.at("/selectedComparison/scv").asText());
            out.set("comparison", result.at("/selectedComparison/comparison").deepCopy());
        }
        return out;
    }
    private static NormalizedVariantKey key(JsonNode a) { return new NormalizedVariantKey(a.path("assembly").asText(), a.path("contig").asText(), a.path("position").asInt(), a.path("reference").asText(), a.path("alternate").asText()); }
    private static String reason(Exception e) { String m = e.getMessage(); return m != null && m.matches("[A-Z][A-Z0-9_]{1,150}") ? m : "PS1_SPLICE_RUNTIME_FAILED"; }
}
