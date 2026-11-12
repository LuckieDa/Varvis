package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks.CatalogSnapshot;
import org.springframework.lang.Nullable;
import static org.nbscn.varvis.variantanalysis.provider.Bp1PublishedPolicy.require;

/** Published scope knowledge plus exact mechanical checks. Does not manufacture per-variant reviews.
 * RAW <= 0.1 is an additional project research exclusion, not an expert BP1 threshold or normal RNA. */
public final class GeneralBp1Runtime {
    @Nullable private LiteratureSourceAssets reviewArchive;

    public GeneralBp1Runtime withReviewArchive(LiteratureSourceAssets archive) {
        reviewArchive=archive;
        return this;
    }
    public static final String VERSION = "1.2.0", CRITERION = "ACMG_AMP_2015:BP1";
    public static final String STRUCTURED_LOF_REUSE_VERSION = "1.0.0";
    public static final String PUBLISHED_BLOCKER_ROUTING_VERSION = "1.0.0";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter identities;
    private final CodingTranscriptMaterialSupply materials;
    private final PublishedCriterionKnowledge knowledge;
    private final RawLookup raw;
    private final ObjectNode rawIdentity;
    @Nullable private RawLookup wideRaw;
    @Nullable private ObjectNode wideRawIdentity;
    private final CatalogSnapshot catalog;
    private final Function<JsonNode, JsonNode> effects;
    private final String effectSourceSha256;
    @Nullable private Supplier<ReviewedRuleBundle> structuredLofSource;
    private int evaluations;

    public GeneralBp1Runtime(ObjectMapper mapper, @Nullable CodingTranscriptMaterialSupply materials,
            @Nullable PublishedCriterionKnowledge knowledge, @Nullable MissenseNativeSpliceBridge raw,
            @Nullable CatalogSnapshot catalog, @Nullable Function<JsonNode, JsonNode> effects,
            @Nullable String effectSourceSha256) {
        this(mapper, materials, knowledge, raw == null ? null : raw::run, raw == null ? null : raw.identity(), catalog, effects, effectSourceSha256);
    }
    public GeneralBp1Runtime(ObjectMapper mapper, @Nullable CodingTranscriptMaterialSupply materials,
            @Nullable PublishedCriterionKnowledge knowledge, @Nullable MissenseNativeSpliceBridge raw,
            @Nullable MissenseNativeSpliceBridge wide, @Nullable CatalogSnapshot catalog,
            @Nullable Function<JsonNode, JsonNode> effects, @Nullable String effectSourceSha256) {
        this(mapper,materials,knowledge,raw,catalog,effects,effectSourceSha256);
        if(wide!=null){wideRaw=wide::run;wideRawIdentity=wide.identity();}
    }

    public GeneralBp1Runtime(ObjectMapper mapper, @Nullable CodingTranscriptMaterialSupply materials,
            @Nullable PublishedCriterionKnowledge knowledge, @Nullable MissenseNativeSpliceBridge raw,
            @Nullable MissenseNativeSpliceBridge wide, @Nullable CatalogSnapshot catalog,
            @Nullable Function<JsonNode, JsonNode> effects, @Nullable String effectSourceSha256,
            @Nullable Supplier<ReviewedRuleBundle> structuredLofSource) {
        this(mapper, materials, knowledge, raw, wide, catalog, effects, effectSourceSha256);
        this.structuredLofSource = structuredLofSource;
    }
    GeneralBp1Runtime(ObjectMapper mapper, CodingTranscriptMaterialSupply materials, PublishedCriterionKnowledge knowledge,
            RawLookup raw, ObjectNode rawIdentity, CatalogSnapshot catalog, Function<JsonNode, JsonNode> effects, String effectSourceSha256) {
        this.mapper = Objects.requireNonNull(mapper); identities = new RuntimeEvidenceV2Adapter(mapper);
        this.materials = materials; this.knowledge = knowledge; this.raw = raw;
        this.rawIdentity = rawIdentity == null ? mapper.createObjectNode().put("status", "UNAVAILABLE") : rawIdentity.deepCopy();
        this.catalog = catalog; this.effects = effects; this.effectSourceSha256 = effectSourceSha256;
    }
    @FunctionalInterface interface RawLookup {
        SpliceAiRawBatchRunner.BatchResult run(ObjectNode context, JsonNode model, List<SpliceAiRawBatchRunner.Query> queries);
    }
    GeneralBp1Runtime withWideRaw(RawLookup lookup,ObjectNode identity) {
        wideRaw=lookup;wideRawIdentity=identity.deepCopy();return this;
    }
    /** Code-owned and without I/O; bind this before a byRequest cache lookup. */
    public static ObjectNode truncatingConditionIdentity(ObjectMapper mapper) {
        var out = Bp1TruncatingAssessment.identity(mapper);
        out.put("runtimeDispatch", "EXACT_SOURCE_SUBTYPE_BEFORE_LEGACY_MISSENSE_READY_V1")
                .put("pureMissenseApplicability", "bp1-truncating-applicability-v1")
                .put("publicProjection", "BOUNDED_BP1_TRUNCATING_CONDITION_V1")
                .put("codingProjectionScope", "TRUNCATING_SUBTYPE_ONLY");
        out.put("canonicalSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(out));
        return out;
    }
    public ObjectNode identity() {
        var out = mapper.createObjectNode().put("policyId", "PROJECT_PUBLISHED_BP1").put("policyVersion", VERSION)
                .put("criterionId", CRITERION).put("clinicalValidationCompleted", false).put("createsReviewReceipt", false)
                .put("interpretationType", Bp1PublishedPolicy.TYPE).put("experimentAbsenceMeansNormal", false);
        out.putObject("projectRestriction").put("policyId", Bp1PublishedPolicy.RESTRICTION).put("maximumRawDelta", "0.1")
                .put("expertBp1Threshold", false).put("experimentalNormalityEstablished", false);
        out.set("materialSupply", materials == null ? missing() : materials.identity());
        out.set("knowledge", knowledge == null ? missing() : knowledge.identity()); out.set("raw", rawIdentity.deepCopy());
        out.set("regionalRaw",wideRawIdentity==null ? missing() : wideRawIdentity.deepCopy());
        out.put("regionalInterpretationType",Bp1RegionPolicy.TYPE);
        out.set("experimentCatalog", catalog == null ? missing() : catalog.content());
        out.put("effectSourceSha256", effectSourceSha256 == null ? "UNAVAILABLE" : effectSourceSha256);
        out.set("truncatingConditionExecution", truncatingConditionIdentity(mapper));
        return out;
    }
    public int evaluations() { return evaluations; }
    public ObjectNode evaluate(SelectedEvidencePreparation selected, String candidateId, String hypothesisId) {
        var out = mapper.createObjectNode().put("candidateId", candidateId).put("hypothesisId", hypothesisId);
        try {
            var projection = selected.legacyProjection(candidateId, hypothesisId); out.set("sourceProjection", projection.snapshot());
            return evaluate(projection.context(), projection.queryCandidate(), out);
        } catch (RuntimeException bad) {
            // Only a missing replacement annotation may use complete sequence reconstruction.
            // The fallback remains unavailable to the old missense/region scoring branches.
            if ("QUERY_TRANSCRIPT_REANNOTATION_REQUIRED".equals(reason(bad))) try {
                var projection = selected.codingSequenceProjection(candidateId, hypothesisId);
                out.set("sourceProjection", projection.snapshot());
                return evaluate(projection.context(), projection.queryCandidate(), out, reason(bad));
            } catch (RuntimeException invalid) { return finish(out, "NOT_EVALUATED", reason(invalid)); }
            return finish(out, "NOT_EVALUATED", reason(bad));
        }
    }
    ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, ObjectNode out) {
        return evaluate(context, candidate, out, null);
    }
    private ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, ObjectNode out,
            @Nullable String legacyAnnotationIssue) {
        evaluations++; out.set("policy", identity());
        try {
            identities.validateCompleteContext(context);
            require(identities.canonicalContextId(context).equals(context.path("contextId").asText()), "BP1_CONTEXT_ID_MISMATCH");
            out.put("contextId", context.path("contextId").asText());
            if (materials == null) throw new IllegalArgumentException("BP1_TRANSCRIPT_MATERIAL_UNAVAILABLE");
            var material = materials.resolve(context, candidate); out.set("sequenceMaterial", material.snapshot());
            var model = material.model();
            if (!material.ready() || model == null) {
                // The old missense association is an attempted observation, not a prerequisite
                // for the distinct published truncating rule. Preserve its error for missense.
                var structure = materials.resolveStructure(context, candidate);
                var structureModel = structure.model();
                out.set("structureMaterial", structure.snapshot());
                if (knowledge != null && structure.ready() && structureModel != null) {
                    var probe = knowledge.select(CRITERION, context, structureModel);
                    out.set("publishedKnowledge", probe);
                    if (Bp1PublishedPolicy.TRUNCATING_TYPE.equals(probe.at("/record/interpretation/type").asText())) {
                        return truncating(context, candidate, out, material, structure, probe);
                    }
                    if (legacyAnnotationIssue == null && "BLOCKED".equals(probe.path("status").asText())
                            && "UNSUPPORTED_CONDITIONS".equals(probe.path("sourceDisposition").asText())) {
                        return finish(out, "NOT_EVALUATED", probe.path("sourceReasonCode").asText());
                    }
                }
                if (legacyAnnotationIssue != null) throw new IllegalArgumentException(legacyAnnotationIssue);
                String issue = material.snapshot().path("reasonCode").asText("BP1_TRANSCRIPT_MATERIAL_UNAVAILABLE");
                if (!structure.ready() && List.of("CODING_MISSENSE_PROTEIN_EFFECT_NOT_SUPPORTED",
                        "CODING_MISSENSE_NATIVE_SNV_REQUIRED", "CODING_MISSENSE_START_OR_NONCODING_EFFECT_NOT_SUPPORTED").contains(issue))
                    issue = structure.snapshot().path("reasonCode").asText(issue);
                throw new IllegalArgumentException(issue);
            }
            // Keep the verified model identity alongside the private proof, without exposing sequence.
            out.withObject("/sequenceMaterial").set("transcript", model.path("transcript").deepCopy());
            out.withObject("/sequenceMaterial").set("protein", model.path("protein").deepCopy());
            if (knowledge == null) throw new IllegalArgumentException("BP1_PUBLISHED_KNOWLEDGE_UNAVAILABLE");
            var selection = knowledge.selectForVerifiedMissense(CRITERION, context, material); out.set("publishedKnowledge", selection);
            if (Bp1PublishedPolicy.TRUNCATING_TYPE.equals(selection.at("/record/interpretation/type").asText()))
                return truncating(context, candidate, out, material, null, selection);
            if (legacyAnnotationIssue != null) throw new IllegalArgumentException(legacyAnnotationIssue);
            require("READY".equals(selection.path("status").asText()), selection.path("reasonCode").asText("BP1_PUBLISHED_SCOPE_UNAVAILABLE"));
            boolean regional=Bp1RegionPolicy.TYPE.equals(selection.at("/record/interpretation/type").asText());
            if(regional) {
                Bp1RegionPolicy.validate(mapper,selection);
                var check=Bp1RegionPolicy.assess(mapper,selection,material.snapshot().path("alleleAssociation"),model);
                out.set("regionConditionCheck",check);
                require("READY".equals(check.path("status").asText()),check.path("reasonCode").asText());
            } else Bp1PublishedPolicy.validate(selection);
            var publishedFunctions = knowledge.assessBp1FunctionScreen(model, material.snapshot().path("alleleAssociation"));
            out.set("publishedFunctionalScreen", publishedFunctions);
            retainPublishedScreen(publishedFunctions);
            require("CHECKED".equals(publishedFunctions.path("status").asText()), publishedFunctions.path("reasonCode").asText());
            var plan = mapper.createObjectNode().put("candidateId", out.path("candidateId").asText());
            plan.set("context", context.deepCopy()); var allele = context.at("/variant/evidenceQueryAllele"); plan.set("queryAllele", allele.deepCopy());
            var experiments = new Bp7ComputationalRuntime(mapper).assessExperiments(plan, catalog); out.set("experimentalConflictCheck", experiments);
            require("NO_MATCH_IN_DECLARED_CATALOG".equals(experiments.path("status").asText()), "REVIEW_REQUIRED".equals(experiments.path("status").asText())
                    ? "BP1_RECORDED_SPLICE_EFFECT_REVIEW_REQUIRED" : "BP1_EXPERIMENT_SOURCE_UNAVAILABLE");
            var checkedEffects = checkEffects(plan); out.set("functionalEffectCheck", checkedEffects);
            require("NO_LINKED_RESTRICTION_IN_DECLARED_SCOPE".equals(checkedEffects.path("status").asText()), checkedEffects.path("reasonCode").asText());
            RawLookup lookup=regional?wideRaw:raw;
            ObjectNode lookupIdentity=regional?wideRawIdentity:rawIdentity;
            require(lookup != null && lookupIdentity != null,regional?"BP1_REQUIRED_WIDE_SPLICE_SOURCE_UNAVAILABLE":"BP1_RAW_SPLICE_SOURCE_UNAVAILABLE");
            if(regional) require(lookupIdentity!=null && lookupIdentity.path("distance").isIntegralNumber()
                    && lookupIdentity.path("distance").asInt()==10000 && "RAW".equals(lookupIdentity.path("scoreMode").asText())
                    && lookupIdentity.path("mask").isIntegralNumber() && lookupIdentity.path("mask").asInt()==0,
                    "BP1_REQUIRED_WIDE_SPLICE_IDENTITY_MISMATCH");
            String queryId = "bp1-" + context.path("contextId").asText();
            var key = new NormalizedVariantKey(allele.path("assembly").asText(), allele.path("contig").asText(), allele.path("position").asInt(),
                    allele.path("reference").asText(), allele.path("alternate").asText());
            var batch = java.util.Objects.requireNonNull(lookup).run(context, model, List.of(new SpliceAiRawBatchRunner.Query(queryId, key, model.path("geneSymbol").asText(), model.path("transcript").asText())));
            if (batch == null) {
                throw new IllegalArgumentException("BP1_RAW_SPLICE_SOURCE_UNAVAILABLE");
            }
            out.putObject("rawSpliceExecution").put("status", batch.getStatus()).put("reasonCode", batch.getReasonCode()).put("exitCode", batch.getExitCode());
            if (batch.getResult() != null) out.set("rawSpliceResult", batch.getResult());
            require("SUCCEEDED".equals(batch.getStatus()) && batch.getResult() != null,
                    batch.getReasonCode() != null && batch.getReasonCode().matches("[A-Z][A-Z0-9_]+") ? batch.getReasonCode() : "BP1_RAW_SPLICE_QUERY_FAILED");
            var body = batch.getResult();
            require("spliceai-raw-batch-result-v1".equals(body.path("resultSchemaVersion").asText())
                    && java.util.Objects.requireNonNull(lookupIdentity).equals(body.path("nativeIdentity"))
                    && identities.canonicalSha256(lookupIdentity).equals(body.path("nativeResourceSha256").asText())
                    && body.path("results").isArray() && body.path("results").size() == 1, "BP1_RAW_SPLICE_IDENTITY_MISMATCH");
            var fact = body.path("results").get(0);
            require(queryId.equals(fact.path("queryId").asText()) && "HIT".equals(fact.path("status").asText())
                    && fact.path("exactAlleleMatch").isBoolean() && fact.path("exactAlleleMatch").asBoolean()
                    && allele.equals(fact.path("allele")), "BP1_RAW_SPLICE_ALLELE_MISMATCH");
            var scores = NativeSpliceAiRuntime.associate(mapper, fact, model).path("deltaScores"); BigDecimal max = BigDecimal.ZERO;
            for (String field : List.of("DS_AG", "DS_AL", "DS_DG", "DS_DL")) {
                var number = PredictionCalibration.decimal(scores.path(field).asText());
                require(scores.path(field).isTextual() && number != null, "BP1_RAW_SPLICE_SCORE_INVALID");
                max = max.max(number);
            }
            out.putObject("projectSpliceCheck").put("policyId", regional?Bp1RegionPolicy.TYPE:Bp1PublishedPolicy.RESTRICTION).put("maximumObservedDelta", max.toPlainString())
                    .put("maximumAllowedDelta", "0.1").put("expertBp1Threshold", regional).put("experimentalNormalityEstablished", false);
            require(max.compareTo(new BigDecimal("0.1")) <= 0,regional?"BP1_PUBLISHED_SPLICE_CONDITION_NOT_MET":"BP1_PROJECT_SPLICE_PREDICTION_REQUIRES_REVIEW");
            return finish(out, "MET", regional?"BP1_PUBLISHED_REGION_AND_SPLICE_CONDITIONS_MET":"BP1_PUBLISHED_SCOPE_AND_PROJECT_CHECKS_MET");
        } catch (RuntimeException bad) { return finish(out, "NOT_EVALUATED", reason(bad)); }
    }
    private ObjectNode truncating(ObjectNode context, VariantInterpretation candidate, ObjectNode out,
            CodingTranscriptMaterialSupply.Material missense, @Nullable CodingTranscriptMaterialSupply.Material structure,
            ObjectNode selection) {
        String disposition = selection.at("/record/status").asText();
        require("READY".equals(selection.path("status").asText()) && "EXECUTABLE".equals(disposition)
                || "BLOCKED".equals(selection.path("status").asText()) && "UNSUPPORTED_CONDITIONS".equals(disposition)
                && "BP1_PUBLISHED_TRUNCATING_LOF_CONDITIONS_REQUIRED".equals(selection.path("reasonCode").asText()),
                selection.path("reasonCode").asText("BP1_TRUNCATING_SOURCE_ADMISSION_UNRESOLVED"));
        Bp1PublishedPolicy.validateTruncatingSource(selection);
        if (missense.ready()) {
            var tx = new RuntimeEvidenceV2ProviderWiring(mapper, identities).selectedVepConsequence(context, candidate);
            require(tx != null && SelectedCriterionRouting.proteinMissenseOnly(tx.getConsequence()),
                    "BP1_TRUNCATING_VERIFIED_PURE_MISSENSE_REQUIRED");
            var check = Bp1TruncatingApplicability.forVerifiedMissense(mapper, selection, context, missense);
            out.set("truncatingConditionCheck", check);
            if ("NOT_APPLICABLE".equals(check.path("status").asText()))
                out.putObject("criterionApplicability").put("status", "NOT_APPLICABLE")
                        .put("reasonCode", check.path("reasonCode").asText());
            return finish(out, "NOT_EVALUATED", check.path("reasonCode").asText());
        }
        // A claimed missense with corrupt HGVS/codons stays a quality/binding failure.
        var tx = new RuntimeEvidenceV2ProviderWiring(mapper, identities).selectedVepConsequence(context, candidate);
        if (tx != null && SelectedCriterionRouting.proteinMissenseOnly(tx.getConsequence()))
            return finish(out, "NOT_EVALUATED", missense.snapshot().path("reasonCode").asText("BP1_TRANSCRIPT_MATERIAL_UNAVAILABLE"));
        if (structure == null || !structure.ready()) throw new IllegalArgumentException("BP1_TRUNCATING_STRUCTURE_MATERIAL_REQUIRED");
        if (knowledge == null) throw new IllegalArgumentException("BP1_PUBLISHED_KNOWLEDGE_UNAVAILABLE");
        out.set("sequenceMaterial", structure.snapshot());
        var model = structure.model();
        if (model != null) for (String key : List.of("transcript", "protein"))
            out.withObject("/sequenceMaterial").set(key, model.path(key).deepCopy());
        var check = Bp1TruncatingAssessment.assess(mapper, knowledge, context, candidate, structure, structuredLofSource);
        require(List.of("NOT_MET", "NOT_EVALUATED", "READY").contains(check.status()), "BP1_TRUNCATING_UNSUPPORTED_ADOPTION_RESULT");
        out.set("truncatingConditionCheck", check.snapshot());
        if ("COMPLETE".equals(check.snapshot().at("/sequenceConsequence/annotationCorrespondence").asText()))
            out.put("annotationCorrespondence", "COMPLETE_SEQUENCE_COMPARISON_PASSED");
        if ("READY".equals(check.status())) {
            var plan = mapper.createObjectNode().put("candidateId", candidate.getCandidateId());
            plan.set("context", context.deepCopy());
            plan.set("queryAllele", context.at("/variant/evidenceQueryAllele").deepCopy());
            var restrictions = checkEffects(plan);
            out.set("functionalEffectCheck", restrictions);
            if (!"NO_LINKED_RESTRICTION_IN_DECLARED_SCOPE".equals(restrictions.path("status").asText())) {
                String failure = restrictions.path("reasonCode").asText("BP1_FUNCTIONAL_REFERENCES_UNAVAILABLE");
                var unresolved = check.snapshot();
                var missing = mapper.createObjectNode().put("conditionId", "CURRENT_FUNCTIONAL_RESTRICTIONS")
                        .put("required", "VERIFIED_CURRENT_SAVED_REFERENCES_WITHOUT_ADOPTED_RESTRICTION")
                        .put("availableReasonCode", failure);
                unresolved.withArray("missing").add(missing);
                unresolved.withObject("/publicSummary").withArray("missing").add(missing.deepCopy());
                finalTruncatingCheck(out, unresolved, "NOT_EVALUATED", failure);
                return finish(out, "NOT_EVALUATED", failure);
            }
            String finalReason = "BP1_PUBLISHED_TRUNCATING_AND_NO_LOF_CONDITIONS_MET";
            finalTruncatingCheck(out, check.snapshot(), "MET", finalReason);
            return finish(out, "MET", finalReason);
        }
        return finish(out, check.status(), check.reasonCode());
    }
    private static void finalTruncatingCheck(ObjectNode out, ObjectNode checked, String status, String reason) {
        String strength = "MET".equals(status) ? "SUPPORTING" : "NONE";
        checked.put("status", status).put("appliedStrength", strength).put("reasonCode", reason);
        checked.withObject("/evaluation").put("appliedStrength", strength);
        checked.withObject("/evaluation/evaluationResult").put("status", status).put("reasonCode", reason);
        checked.withObject("/publicSummary").put("status", status).put("appliedStrength", strength).put("reasonCode", reason);
        out.set("truncatingConditionCheck", checked);
    }
    private void retainPublishedScreen(JsonNode checked) {
        var archive=reviewArchive;
        if (archive==null) return;
        for (var match : checked.path("matches")) try { archive.retainPublishedScreen(match); }
        catch (java.io.IOException unavailable) {
            org.slf4j.LoggerFactory.getLogger(GeneralBp1Runtime.class).warn("Could not retain a public BP1 functional screen record");
        }
    }

    private ObjectNode checkEffects(ObjectNode plan) {
        var out = mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", "BP1_FUNCTIONAL_REFERENCES_UNAVAILABLE");
        try {
            require(effects != null && effectSourceSha256 != null && effectSourceSha256.matches("[0-9a-f]{64}"), "BP1_FUNCTIONAL_REFERENCES_UNAVAILABLE");
            var refs = effects.apply(plan);
            if (refs == null) throw new IllegalArgumentException("BP1_FUNCTIONAL_REFERENCES_UNAVAILABLE");
            out.set("source", refs.deepCopy());
            require("VERIFIED_SAVED_REFERENCES".equals(refs.path("status").asText()) && "COMPLETE_FOR_DECLARED_SCOPE".equals(refs.path("coverage").asText())
                    && effectSourceSha256.equals(refs.path("sourceSha256").asText()) && effectSourceSha256.equals(refs.at("/snapshotIdentity/sha256").asText())
                    && refs.path("contextId").equals(plan.at("/context/contextId")) && refs.path("references").isArray(), "BP1_FUNCTIONAL_REFERENCES_UNAVAILABLE");
            require("UNSPECIFIED".equals(refs.path("externalDeclaration").asText()) && refs.path("externalWorldCovered").isBoolean()
                    && !refs.path("externalWorldCovered").asBoolean(), "BP1_EXTERNAL_EXPERIMENT_DECLARATION_UNRESOLVED");
            require(refs.at("/scope/candidateId").equals(plan.path("candidateId")) && refs.at("/scope/contextId").equals(plan.at("/context/contextId"))
                    && refs.at("/scope/queryAllele").equals(plan.path("queryAllele")), "BP1_FUNCTIONAL_REFERENCE_SCOPE_MISMATCH");
            require(refs.path("references").isEmpty() && "NO_ADOPTED_OVERLAP_IN_DECLARED_SCOPE".equals(refs.path("restriction").asText()),
                    "BP1_RECORDED_FUNCTIONAL_EFFECT_REVIEW_REQUIRED");
            return out.put("status", "NO_LINKED_RESTRICTION_IN_DECLARED_SCOPE").put("reasonCode", "BP1_LOCAL_FUNCTIONAL_SCOPE_CHECKED");
        } catch (RuntimeException bad) { return out.put("reasonCode", reason(bad)); }
    }
    private ObjectNode missing() { return mapper.createObjectNode().put("status", "UNAVAILABLE"); }
    private static String reason(RuntimeException e) { String m = e.getMessage(); return m != null && m.matches("[A-Z][A-Z0-9_]+") ? m : "BP1_RUNTIME_CHECK_FAILED"; }
    private ObjectNode finish(ObjectNode out, String status, String reason) {
        out.put("status", status).put("reasonCode", reason);
        out.putObject("evaluation").put("criterionId", CRITERION).put("appliedStrength", "MET".equals(status)
                ? "BP1_PUBLISHED_REGION_AND_SPLICE_CONDITIONS_MET".equals(reason)?"STRONG":"SUPPORTING" : "NONE")
                .putObject("evaluationResult").put("status", status).put("reasonCode", reason);
        if (out.path("criterionApplicability").isObject())
            out.withObject("/evaluation").set("criterionApplicability", out.path("criterionApplicability").deepCopy());
        out.set("publicSummary", publicSummary(out)); return out;
    }
    public static ObjectNode publicSummary(ObjectNode out) {
        var summary = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("schemaVersion", "bp1-general-facts-v1")
                .put("policyVersion", VERSION).put("scope", "PUBLISHED_SCOPE_PLUS_PROJECT_RESEARCH_RESTRICTION")
                .put("clinicalValidationCompleted", false).put("experimentalNormalityEstablished", false)
                .put("projectSplicePolicy", Bp1PublishedPolicy.RESTRICTION).put("expertBp1SpliceThreshold", false);
        summary.put("sourceSpecification", out.at("/publishedKnowledge/record/specificationKey").asText());
        var effectCheck = out.path("functionalEffectCheck");
        summary.set("functionalReferences", EffectReferenceReadiness.view(effectCheck.path("source").isObject()
                ? effectCheck.path("source") : effectCheck));
        if(Bp1RegionPolicy.TYPE.equals(out.at("/publishedKnowledge/record/interpretation/type").asText())) {
            summary.put("scope","PUBLISHED_REGION_AND_SPLICE_CONDITIONS").put("projectSplicePolicy",Bp1RegionPolicy.TYPE)
                    .put("expertBp1SpliceThreshold",true).put("requiredDistanceBp",10000);
            summary.set("regionConditionCheck",out.path("regionConditionCheck").deepCopy());
        }
        summary.put("sourceTranscript", out.at("/publishedKnowledge/record/scope/transcript").asText());
        summary.put("knowledgeStatus", out.at("/publishedKnowledge/status").asText("NOT_CHECKED"));
        summary.put("knowledgeDisposition", out.at("/publishedKnowledge/record/status").asText("UNRESOLVED"));
        summary.put("knowledgeReason", out.at("/publishedKnowledge/reasonCode").asText("BP1_KNOWLEDGE_NOT_CHECKED"));
        for (String field : List.of("sourceDisposition", "sourceReasonCode", "sourceClausePointer")) {
            var value = out.path("publishedKnowledge").path(field); if (value.isTextual()) summary.set(field, value.deepCopy());
        }
        var screened = out.path("publishedFunctionalScreen");
        var publicScreen = summary.putObject("functionalScreen").put("status", screened.path("status").asText("NOT_CHECKED"))
                .put("reasonCode", screened.path("reasonCode").asText("BP1_FUNCTION_SCREEN_NOT_CHECKED"))
                .put("catalogSha256", screened.path("catalogSha256").asText()).put("coverage", "LOADED_PUBLICATIONS_ONLY")
                .put("absenceMeansNormal", false).put("clinicalAdoption", false);
        var matches = publicScreen.putArray("matches");
        for (var match : screened.path("matches")) {
            var item = matches.addObject().put("screenId",match.path("screenId").asText()).put("sourceId",match.at("/source/sourceId").asText())
                    .put("sourceUrl",match.at("/source/url").asText()).put("sourceRawSha256",match.at("/source/rawSha256").asText())
                    .put("outcome",match.path("outcome").asText()).put("reportedClass",match.at("/record/reportedClass").asText())
                    .put("proteinVariantKey",match.path("proteinVariantKey").asText());
            if (match.path("record").isObject()) item.put("sourceRow",match.at("/record/sourceRow").asInt()).put("sourceSheet",match.at("/record/sourceSheet").asText());
        }
        summary.put("maximumObservedDelta", out.at("/projectSpliceCheck/maximumObservedDelta").asText("UNAVAILABLE"));
        var allowedDelta = out.at("/projectSpliceCheck/maximumAllowedDelta");
        if (allowedDelta.isTextual() && PredictionCalibration.decimal(allowedDelta.asText()) != null)
            summary.put("maximumAllowedDelta", allowedDelta.asText());
        if (out.path("truncatingConditionCheck").isObject()) {
            summary.put("scope", "PUBLISHED_TRUNCATING_GOF_CONDITIONS");
            summary.remove(List.of("projectSplicePolicy", "expertBp1SpliceThreshold", "maximumObservedDelta", "maximumAllowedDelta", "functionalScreen"));
            if (!out.path("functionalEffectCheck").isObject()) summary.remove("functionalReferences");
            summary.set("truncatingConditionCheck", boundedTruncatingCheck(out.path("truncatingConditionCheck")));
            if ("COMPLETE_SEQUENCE_COMPARISON_PASSED".equals(out.path("annotationCorrespondence").asText()))
                summary.put("annotationCorrespondence", "COMPLETE_SEQUENCE_COMPARISON_PASSED");
        }
        for (String field : List.of("transcript", "protein")) {
            var value = out.path("sequenceMaterial").path(field); if (!value.isMissingNode()) summary.set(field, value.deepCopy());
        }
        var variant = out.at("/sequenceMaterial/alleleAssociation/proteinVariantKey");
        if (!variant.isMissingNode()) summary.set("proteinVariantKey", variant.deepCopy());
        return summary;
    }
    private static ObjectNode boundedTruncatingCheck(JsonNode check) {
        JsonNode source = check.path("publicSummary").isObject() ? check.path("publicSummary") : check;
        var out = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        texts(out, source, "schemaVersion", "methodVersion", "status", "reasonCode");
        out.put("appliedStrength", "MET".equals(source.path("status").asText()) ? "SUPPORTING" : "NONE")
                .put("scientificQualificationGranted", false).put("clinicalValidationCompleted", false);
        var required = out.putObject("required");
        texts(required, source.path("required"), "publishedRule", "variantConsequence", "variantEffect", "setting", "diseaseMechanism", "geneLofDiseaseCorrelation");
        bools(required, source.path("required"), "germlineOnly");
        for (String key : List.of("noRecordMeansNoCorrelation", "missingRecordMeansNoCorrelation"))
            if (source.path("required").has(key)) required.put(key, false);
        var available = out.putObject("available");
        texts(available, source.path("available"), "variantConsequence", "variantEffect", "geneLofDiseaseCorrelation", "sourceCondition", "modelCanonicalSha256", "materialSha256");
        if (source.path("available").has("functionalLossEstablishedBySequence"))
            available.put("functionalLossEstablishedBySequence", false);
        var missing = out.putArray("missing");
        for (var row : source.path("missing")) {
            if (missing.size() == 10) break;
            if (row.isObject()) texts(missing.addObject(), row, "conditionId", "required", "availableReasonCode");
        }
        if (source.path("sourceClause").isTextual()) texts(out, source, "sourceClause");
        if (source.path("publishedSource").isObject()) {
            var published = out.putObject("publishedSource");
            texts(published, source.path("publishedSource"), "specificationKey", "entitySha256", "clausePointer", "clauseSha256", "sourceRawSha256");
            scope(published.putObject("scope"), source.at("/publishedSource/scope"));
            texts(published.putObject("catalogIdentity"), source.at("/publishedSource/catalogIdentity"),
                    "schemaVersion", "version", "rawSha256", "policyId", "policyVersion", "adoptionMode", "sourceScope");
        }
        if (source.path("sequenceConsequence").isObject()) {
            var sequence = out.putObject("sequenceConsequence");
            texts(sequence, source.path("sequenceConsequence"), "status", "reasonCode", "nativeModelSha256", "structureMaterialSha256");
            sequence.put("functionalLossEstablishedBySequence", false);
        }
        if (source.path("noEstablishedLofQualification").isObject()) {
            var q = source.path("noEstablishedLofQualification");
            var bounded = out.putObject("noEstablishedLofQualification");
            texts(bounded, q, "schemaVersion", "methodVersion", "status", "reasonCode", "sourceCheckStatus", "evaluatedDate");
            texts(bounded.putObject("validity"), q.path("validity"), "status", "reviewDate", "validThrough");
            bools(bounded.withObject("/validity"), q.path("validity"), "inclusive");
            bools(bounded, q, "absenceQualificationGranted");
            texts(bounded.putObject("claim"), q.path("claim"), "hgncId", "geneSymbol", "diseaseScope", "inheritanceScope",
                    "molecularMechanism", "reviewDate", "validThrough");
            texts(bounded.putObject("sourceBinding"), q.path("sourceBinding"), "catalogRawSha256", "specificationKey", "entitySha256",
                    "sourceRawSha256", "sourceUrl", "sourceJsonPointer", "sourceReleasedAt", "statementPointer", "statementTextSha256");
            texts(bounded.putObject("useBinding"), q.path("useBinding"), "contextId", "contextSha256", "alleleSha256",
                    "modelCanonicalSha256", "structureMaterialSha256", "sequenceObservationSha256");
            if (q.path("sourceCheckStatus").isTextual()) {
                var transport = bounded.putObject("sourceTransport");
                if (q.path("sourceCheckStatus").asText().length() <= 1000)
                    transport.set("status", q.path("sourceCheckStatus").deepCopy());
                transport.put("isBiologicalAbsenceConclusion", false);
            }
            var coverage = q.path("reviewCoverage");
            if (coverage.isObject()) {
                var review = bounded.putObject("reviewCoverage");
                bools(review, coverage, "sameGeneOtherDiseasesChecked", "absenceFromCatalogMeansNoLof");
                if ("SOFTWARE_UNSUPPORTED".equals(coverage.path("status").asText())) {
                    var software = bounded.putObject("softwareCapability").put("status", "UNSUPPORTED");
                    texts(software, q, "reasonCode");
                    if (coverage.path("sameGeneOtherDiseasesChecked").isBoolean()
                            && !coverage.path("sameGeneOtherDiseasesChecked").asBoolean())
                        review.put("status", "NOT_ESTABLISHED");
                } else texts(review, coverage, "status");
            }
            if (q.path("sourceReviews").isArray()) {
                var reviews = bounded.putArray("sourceReviews");
                for (var sourceReview : q.path("sourceReviews")) {
                    if (reviews.size() == 16) break;
                    if (!sourceReview.isObject()) continue;
                    var item = reviews.addObject();
                    texts(item, sourceReview, "softwareInterpretationStatus", "reasonCode");
                    texts(item.putObject("subject"), sourceReview.path("claim"), "hgncId", "geneSymbol");
                    texts(item.putObject("sourceBinding"), sourceReview.path("sourceBinding"), "sourceId", "specificationKey", "catalogRawSha256",
                            "entitySha256", "sourceRawSha256", "sourceUrl", "sourceJsonPointer", "sourceReleasedAt",
                            "statementPointer", "statementTextSha256");
                }
                bounded.put("sourceReviewsTotal", q.path("sourceReviews").size())
                        .put("sourceReviewsTruncated", q.path("sourceReviews").size() > 16);
            }
        }
        if (source.path("lofDiseaseCorrelation").isObject()) {
            var correlation = out.putObject("lofDiseaseCorrelation"); var c = source.path("lofDiseaseCorrelation");
            texts(correlation, c, "status", "reasonCode", "sourceCheckStatus");
            if (c.path("relation").isObject()) texts(correlation.putObject("relation"), c.path("relation"),
                    "hgncId", "geneSymbol", "molecularMechanism", "diseaseLabel", "basis", "sourceInterpretationVersion");
            if (c.path("sourceMention").isObject()) {
                var mention = correlation.putObject("sourceMention");
                texts(mention, c.path("sourceMention"), "namedGeneSymbol", "diseaseLabel", "selectedGeneSymbol");
                bools(mention, c.path("sourceMention"), "subjectMatchesSelectedScope");
            }
            if (c.path("sourceBinding").isObject()) {
                var binding = correlation.putObject("sourceBinding"); var b = c.path("sourceBinding");
                texts(binding, b, "catalogRawSha256", "catalogPolicyId", "catalogPolicyVersion", "sourceRawSha256", "sourceUrl", "sourceJsonPointer",
                        "recordId", "specificationKey", "entitySha256", "clausePointer", "clauseSha256", "scopePointer", "relationPointer", "relationTextUtf8Sha256");
                scope(binding.putObject("scope"), b.path("scope"));
            }
        }
        if (source.path("structuredLof").isObject()) {
            var proof = source.path("structuredLof");
            var bounded = out.putObject("structuredLof");
            texts(bounded, proof, "methodVersion", "status", "contextId", "contextSha256", "geneSymbol", "diseaseLabel",
                    "entryId", "modelSha256", "policySha256", "bundleSha256", "adoptionRawSha256",
                    "sourceCandidatesRawSha256", "mechanismSourceSetSha256");
            scope(bounded.putObject("scope"), proof.path("scope"));
            var sources = bounded.putArray("mechanismSources");
            for (var item : proof.path("mechanismSources")) {
                if (sources.size() == 8) break;
                texts(sources.addObject(), item, "sourceId", "url", "sha256");
            }
        }
        return out;
    }
    private static void scope(ObjectNode out, JsonNode source) { texts(out, source, "hgncId", "mondoId", "moi", "assembly", "transcript"); }
    private static void texts(ObjectNode out, JsonNode source, String... keys) {
        for (String key : keys) if (source.path(key).isTextual() && source.path(key).asText().length() <= 1000)
            out.set(key, source.path(key).deepCopy());
    }
    private static void bools(ObjectNode out, JsonNode source, String... keys) {
        for (String key : keys) if (source.path(key).isBoolean()) out.set(key, source.path(key).deepCopy());
    }

}
