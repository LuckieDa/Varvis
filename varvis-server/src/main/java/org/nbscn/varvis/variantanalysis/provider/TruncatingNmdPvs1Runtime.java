package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.AcmgRecommendations;

/**
 * Explicitly requested, policy-bound stop-gained/simple-frameshift NMD evaluation.
 * A resource profile supplies only VEP/registry identity. Clinical relevance, LoF
 * mechanism and bounded NMD scope come from the independently selected policy.
 * Loading this runtime does not activate a policy, query VEP, or classify a variant.
 */
public final class TruncatingNmdPvs1Runtime {
    public static final String CRITERION = "ACMG_AMP_2015:PVS1";
    public static final String EVALUATOR_ID = "transcript-truncating-nmd-pvs1";
    public static final String EVALUATOR_VERSION = "1.1.0";
    public static final String STRENGTH_CAP_VERSION = "1.4.0";
    public static final String SPLICE_MODULE_VERSION = "1.7.0";
    public static final String FULL_RNA_EVALUATOR_VERSION = "1.10.1";
    static final String CODING_JUNCTION_VERSION = "1.11.0";
    static final String NON_RELEVANT_EXON = "PVS1_EXON_NOT_RELEVANT";
    public static final String COMPOSITE_SPLICE_VERSION = "1.8.0";
    static boolean hasStrengthCap(JsonNode policy) {
        return Set.of("STRONG", "MODERATE", "SUPPORTING").contains(policy.path("appliedStrength").asText());
    }
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final RuntimeEvidenceV2ProviderWiring wiring;
    private final RuntimeEvidenceV2ProviderWiring.ResourceBinding vep;
    private final ObjectNode resourceProfile;
    private final ObjectNode model;
    private final boolean complexAlleles;
    private final String resourceProfileSha256;
    private final java.util.concurrent.atomic.AtomicInteger projections = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger evaluations = new java.util.concurrent.atomic.AtomicInteger();
    public int storedVepProjections() { return projections.get(); }
    public int evaluatorCalls() { return evaluations.get(); }

    public TruncatingNmdPvs1Runtime(ObjectMapper mapper, ObjectNode resourceProfile, ObjectNode transcriptModel) {
        this(mapper, resourceProfile, transcriptModel, false);
    }

    /** Explicit mechanical capability; the independently selected policy must also authorize it. */
    public TruncatingNmdPvs1Runtime(ObjectMapper mapper, ObjectNode resourceProfile, ObjectNode transcriptModel, boolean complexAlleles) {
        this.complexAlleles = complexAlleles;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.resourceProfile = Objects.requireNonNull(resourceProfile, "resourceProfile").deepCopy();
        this.model = Objects.requireNonNull(transcriptModel, "transcriptModel").deepCopy();
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        resourceProfileSha256 = adapter.canonicalSha256(this.resourceProfile);
        vep = wiring.bind(this.resourceProfile, this.resourceProfile.path("vepResourceId").asText());
        if (!"VEP".equals(vep.getProvider()) || !"116.0".equals(vep.getProviderVersion()))
            throw new IllegalArgumentException("PVS1_VEP_VERSION_NOT_SUPPORTED");
        validateRegistry();
    }

    public ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate,
                               ObjectNode selectedPolicy, ObjectNode selectionReceipt) {
        return evaluate(context, candidate, selectedPolicy, selectionReceipt, mapper.missingNode());
    }

    public ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate,
                               ObjectNode selectedPolicy, ObjectNode selectionReceipt, JsonNode spliceMaterials) {
        return evaluate(context, candidate, selectedPolicy, selectionReceipt, spliceMaterials, mapper.missingNode());
    }

    public ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate,
                               ObjectNode selectedPolicy, ObjectNode selectionReceipt,
                               JsonNode spliceMaterials, JsonNode startLossMaterials) {
        ObjectNode policy = Objects.requireNonNull(selectedPolicy, "selectedPolicy").deepCopy();
        ObjectNode selection = Objects.requireNonNull(selectionReceipt, "selectionReceipt").deepCopy();
        JsonNode startLossSnapshot = startLossMaterials == null ? mapper.missingNode() : startLossMaterials.deepCopy();
        ObjectNode result = mapper.createObjectNode().put("criterionId", CRITERION).put("sourceOfTruth", "ACMG_EVIDENCE_V2")
                .put("classificationEnabled", false).put("providerCalls", 0).put("evaluatorCalls", 0)
                .put("resourceProfileSha256", resourceProfileSha256).put("policySha256", adapter.canonicalSha256(policy));
        result.set("context", context.deepCopy()); result.set("policy", policy);
        result.set("policySelection", selection);
        result.set("transcriptModelBinding", policy.path("transcriptModelBinding").deepCopy());
        String issue;
        boolean startLoss = Pvs1StartLossResolver.selectedStartLoss(context, candidate);
        if (startLoss && !Pvs1StartLossResolver.nuclearContig(context))
            return disposition(result, "PVS1_START_LOSS_SCOPE_UNSUPPORTED");
        try {
            adapter.validateCompleteContext(context);
            String confirmation = context.at("/transcript/selectionPolicy").asText();
            issue = AcmgRecommendations.populationOnlySelection(confirmation) || "PM2_CONTEXT_ONLY".equals(confirmation)
                    ? "PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED" : startLoss ? Pvs1StartLossPolicy.bindingIssue(context, policy, selection, model, adapter)
                    : TruncatingNmdPolicy.issue(context, policy, selection, model, adapter);
        } catch (RuntimeException invalid) { issue = "PVS1_CONTEXT_OR_POLICY_INPUT_INVALID"; }
        if (issue != null) return disposition(result, issue);
        if (!startLoss && !complexAlleles && TruncatingNmdPolicy.requiresComplexAllele(context))
            return disposition(result, "PVS1_COMPLEX_ALLELE_CAPABILITY_NOT_ENABLED");
        TranscriptConsequence annotation;
        ObjectNode atomic;
        try {
            annotation = wiring.selectedVepConsequence(context, candidate);
            if (annotation == null) return disposition(result, "PVS1_SELECTED_TRANSCRIPT_NOT_UNIQUE");
            if (!"116.0".equals(context.at("/transcript/annotationSourceVersion").asText()))
                return disposition(result, "PVS1_VEP_VERSION_NOT_SUPPORTED");
            projections.incrementAndGet();
            atomic = wiring.projectStoredTranscript(context, candidate, annotation, vep);
        } catch (RuntimeException invalid) { return disposition(result, "PVS1_VEP_SOURCE_IDENTITY_INVALID"); }
        result.put("storedVepProjections", 1).set("evidence", atomic);
        ObjectNode annotationSource = mapper.valueToTree(annotation);
        annotationSource.put("timestamp", Objects.toString(annotation.getTimestamp(), null));
        result.set("storedAnnotation", annotationSource);
        if (startLoss) {
            ObjectNode facts = Pvs1StartLossResolver.resolve(mapper, context, annotation, policy, model,
                    startLossSnapshot, adapter);
            try {
                JsonNode splice = Pvs1SpliceConsequence.select(spliceMaterials, context, model, adapter);
                if (!splice.isMissingNode() && !splice.isNull()) {
                    result.set("spliceConsequenceMaterial", splice.deepCopy());
                    facts.put("decisionOutcome", "CONFLICT").put("decisionStrength", "NONE")
                            .put("decisionReasonCode", "PVS1_START_LOSS_COMBINED_MECHANISM_CONFLICT");
                }
            } catch (RuntimeException invalid) {
                facts.put("decisionOutcome", "INSUFFICIENT_INFORMATION").put("decisionStrength", "NONE")
                        .put("decisionReasonCode", "PVS1_START_LOSS_SPLICE_MATERIAL_UNRESOLVED");
            }
            result.set("startLossFacts", facts);
            if (startLossSnapshot.isObject()) result.set("startLossMaterial", startLossSnapshot);
            String reason = facts.path("status").asText().equals("AVAILABLE")
                    ? facts.path("decisionReasonCode").asText("PVS1_START_LOSS_POLICY_UNRESOLVED")
                    : facts.path("reasonCode").asText("PVS1_START_LOSS_INPUT_INVALID");
            result.put("evaluatorCalls", 1).set("evaluation", startLossEvaluation(context, atomic, policy, selection, facts, reason));
            evaluations.incrementAndGet();
            return result;
        }
        ObjectNode inputs = mapper.createObjectNode().put("transcriptModelSha256", adapter.canonicalSha256(model))
                .put("storedAnnotationSha256", adapter.canonicalSha256(annotationSource));
        TruncatingPvs1Model.describeTopology(model, inputs);
        try {
            JsonNode spliceFact = mapper.missingNode();
            if (Pvs1SpliceConsequence.SCHEMA.equals(policy.path("schemaVersion").asText())) {
                spliceFact = Pvs1SpliceConsequence.select(spliceMaterials, context, model, adapter);
                if (!spliceFact.isMissingNode() && !spliceFact.isNull()) {
                    result.set("spliceConsequenceMaterial", spliceFact.deepCopy());
                    inputs.put("spliceMaterialSha256", adapter.canonicalSha256(spliceFact));
                }
            }
            issue = resolve(context, annotation, policy, selection, inputs, spliceFact);
        }
        catch (IllegalArgumentException invalid) {
            String code = invalid.getMessage();
            issue = code != null && Set.of("PVS1_SPLICE_MATERIAL_INVALID", "PVS1_SPLICE_OUTCOMES_AMBIGUOUS", "PVS1_SPLICE_MODEL_IDENTITY_MISMATCH").contains(code)
                    ? code : "PVS1_VARIANT_TRANSCRIPT_INPUT_INVALID";
        }
        catch (RuntimeException invalid) { issue = "PVS1_VARIANT_TRANSCRIPT_INPUT_INVALID"; }
        result.set("resolvedInput", inputs);
        JsonNode modelFacts = mapper.missingNode();
        if (inputs.path("escapeAssessment").isObject()) {
            modelFacts = mapper.createObjectNode().put("schemaVersion", "pvs1-transcript-model-facts-v1")
                    .put("modelSha256", adapter.canonicalSha256(model)).put("proteinLength", model.path("proteinLength").asInt())
                    .put("cdsLength", model.path("cdsLength").asInt());
            result.set("transcriptModelFacts", modelFacts);
        }
        evaluations.incrementAndGet();
        result.put("evaluatorCalls", 1).set("evaluation", evaluation(context, atomic, policy, selection, inputs, issue, modelFacts));
        return result;
    }

    /** Additive opaque source entry. Normal preparation must issue a current
     * VerifiedRna/reference replay; a saved JSON never recreates this authority. */
    public ObjectNode evaluateFullRna(ObjectNode context, VariantInterpretation candidate,
            ObjectNode selectedPolicy, ObjectNode selectionReceipt,
            @org.springframework.lang.Nullable Pvs1SourceQualification.SourceBoundFullRna source) {
        var policy=selectedPolicy.deepCopy();var selection=selectionReceipt.deepCopy();
        var result=mapper.createObjectNode().put("criterionId",CRITERION).put("sourceOfTruth","ACMG_EVIDENCE_V2")
                .put("classificationEnabled",false).put("providerCalls",0).put("evaluatorCalls",0)
                .put("resourceProfileSha256",resourceProfileSha256).put("policySha256",adapter.canonicalSha256(policy));
        result.set("context",context.deepCopy());result.set("policy",policy);result.set("policySelection",selection);
        result.set("transcriptModelBinding",policy.path("transcriptModelBinding").deepCopy());
        if(source==null)return disposition(result,"PVS1_FULL_RNA_SOURCE_REQUIRED");
        result.set("fullRnaSource",source.provenance());result.set("fullRnaSourceOriginal",source.original());
        if(!adapter.canonicalSha256(model).equals(source.result().reference().material().modelSha256()))
            return disposition(result,"PVS1_FULL_RNA_RUNTIME_MODEL_BINDING_MISMATCH");
        ObjectNode atomic;
        try {
            adapter.validateCompleteContext(context);
            String confirmation=context.at("/transcript/selectionPolicy").asText();
            if(AcmgRecommendations.populationOnlySelection(confirmation)||"PM2_CONTEXT_ONLY".equals(confirmation))
                return disposition(result,"PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED");
            if(!"116.0".equals(context.at("/transcript/annotationSourceVersion").asText()))
                return disposition(result,"PVS1_VEP_VERSION_NOT_SUPPORTED");
            var annotation=wiring.selectedVepConsequence(context,candidate);
            if(annotation==null)return disposition(result,"PVS1_SELECTED_TRANSCRIPT_NOT_UNIQUE");
            atomic=wiring.projectStoredTranscript(context,candidate,annotation,vep);projections.incrementAndGet();
            ObjectNode stored=mapper.valueToTree(annotation);stored.put("timestamp",Objects.toString(annotation.getTimestamp(),null));
            result.set("storedAnnotation",stored);result.set("evidence",atomic);result.put("storedVepProjections",1);
        } catch(RuntimeException invalid){return disposition(result,"PVS1_VEP_SOURCE_IDENTITY_INVALID");}
        var input=mapper.createObjectNode();
        String issue=Pvs1SpliceConsequence.resolveFullRnaForIntegration(context,policy,selection,source,input);
        result.set("resolvedInput",input);evaluations.incrementAndGet();result.put("evaluatorCalls",1);
        result.set("evaluation",evaluation(context,atomic,policy,selection,input,issue,mapper.missingNode()));return result;
    }

    private ObjectNode startLossEvaluation(ObjectNode context, ObjectNode atomic, ObjectNode policy,
                                           ObjectNode selection, ObjectNode facts, String reason) {
        String outcome = facts.path("decisionOutcome").asText();
        String strength = facts.path("decisionStrength").asText("NONE");
        boolean met = "MET".equals(outcome) && Set.of("SUPPORTING", "MODERATE").contains(strength);
        boolean notApplicable = "NOT_APPLICABLE".equals(outcome);
        if ("MET".equals(outcome) && !met) reason = "PVS1_START_LOSS_DECISION_STRENGTH_INVALID";
        ObjectNode evaluation = mapper.createObjectNode().put("schemaVersion", "criterion-evaluation-v2")
                .put("canonicalizationProfile", "varvis-json-c14n-v1").put("contextId", context.path("contextId").asText())
                .put("criterionId", CRITERION).put("baseStrength", met ? strength : "NONE")
                .put("appliedStrength", met ? strength : "NONE");
        ObjectNode binding = evaluation.putObject("ruleBinding").put("currentGeneralDesignation", "PVS1")
                .put("cspecSelectionId", selection.path("selectionId").asText());
        binding.set("authoritySourceIds", policy.path("authoritySourceIds").deepCopy());
        evaluation.putObject("criterionApplicability").put("status", met ? "APPLICABLE" : notApplicable ? "NOT_APPLICABLE" : "UNKNOWN")
                .put("reasonCode", reason);
        evaluation.putObject("evaluationResult").put("status", met ? "MET" : "NOT_EVALUATED").put("reasonCode", reason);
        evaluation.putArray("evidenceIds").add(atomic.path("evidenceId").asText());
        ObjectNode trace = evaluation.putObject("deterministicTrace").put("executionStatus", met || notApplicable ? "COMPLETED" : "NOT_RUN")
                .put("evaluatorId", "pvs1-start-loss").put("evaluatorVersion", Pvs1StartLossResolver.VERSION)
                .put("reasonCode", reason).put("factsSha256", adapter.canonicalSha256(facts))
                .put("policySha256", adapter.canonicalSha256(policy))
                .put("transcriptModelSha256", adapter.canonicalSha256(model));
        trace.putArray("inputEvidenceIds").add(atomic.path("evidenceId").asText());
        if (met || notApplicable) {
            trace.put("algorithmId", policy.path("policyId").asText())
                    .put("algorithmVersion", policy.path("policyVersion").asText())
                    .put("traceSha256", adapter.canonicalSha256(trace));
        }
        evaluation.putObject("conflict").put("status", "NONE").putArray("conflictIds");
        evaluation.putObject("review").put("status", "NOT_REQUIRED").put("decision", "NONE").putArray("reviewRecordIds");
        ObjectNode identity = evaluation.deepCopy(); identity.remove(List.of("conflict", "review"));
        evaluation.put("evaluationId", "evaluation-sha256-" + adapter.canonicalSha256(identity));
        return evaluation;
    }

    private String resolve(JsonNode context, TranscriptConsequence tx, ObjectNode policy, ObjectNode selection, ObjectNode inputs, JsonNode spliceFact) {
        Set<String> consequences = new java.util.HashSet<>(List.of(Objects.toString(tx.getConsequence(), "").split("[&,|]")));
        boolean splice = consequences.stream().anyMatch(t -> t.contains("splice") || "intron_variant".equals(t))
                || !spliceFact.isMissingNode() && !spliceFact.isNull();
        // Preserve the old refusal for legacy policies that never enabled a splice method.
        // A reviewed v4 policy must reach the altered-topology module instead.
        if (splice && TruncatingPvs1Model.extendedTopology(model)
                && !Pvs1SpliceConsequence.SCHEMA.equals(policy.path("schemaVersion").asText()))
            return "PVS1_SPECIAL_TOPOLOGY_SPLICE_NOT_IMPLEMENTED";
        if (!splice && consequences.contains("start_lost")) return "PVS1_START_LOSS_BRANCH_NOT_IMPLEMENTED";
        String transcript = model.path("transcript").asText();
        String relevance = policy.at("/nmdScope/exonRelevance").asText();
        String mechanism = policy.at("/mechanism/mechanismId").asText();
        ObjectNode mechanics = TruncatingNmdPolicy.mechanics(policy, model);
        String issue;
        if (splice) {
            // The module has already applied the altered topology, exact rule and all caps.
            // Running the legacy NMD tail would reinterpret an in-frame/negative result.
            return Pvs1SpliceConsequence.resolveForIntegration(context, tx, policy, model, spliceFact, inputs, selection);
        } else if ("SNV".equals(context.at("/variant/variantType").asText())) {
            if (!consequences.contains("stop_gained")) return "PVS1_CONSEQUENCE_NOT_APPLICABLE";
            issue = new TruncatingNonsenseResolver(model, mechanics, transcript, relevance, mechanism, false)
                    .resolve(context, tx, inputs);
        } else {
            if (!consequences.contains("frameshift_variant")) return "PVS1_CONSEQUENCE_NOT_APPLICABLE";
            issue = new TruncatingFrameshiftResolver(model, mechanics, transcript, relevance, mechanism,
                    complexAlleles && TruncatingNmdPolicy.complexAllelesEnabled(policy))
                    .resolve(context, tx, inputs);
        }
        boolean escape = "PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED".equals(issue);
        if (issue != null && !escape) return issue;
        int variantExon = inputs.path("exonOrdinal").asInt();
        if (TruncatingNmdPolicy.contains(policy.at("/nmdScope/nonRelevantExonOrdinals"), variantExon)) {
            inputs.remove("nmdPrediction");
            return NON_RELEVANT_EXON;
        }
        if ("LAST_JUNCTION_IN_3_PRIME_UTR".equals(inputs.at("/transcriptTopology/kind").asText())
                && !policy.at("/nmdScope/utrJunctionApplicability").isObject()) {
            inputs.remove("nmdPrediction");
            return "PVS1_UTR_JUNCTION_APPLICABILITY_UNRESOLVED";
        }
        if (escape) {
            inputs.put("nmdPrediction", "NMD_NOT_PREDICTED_IN_IMPLEMENTED_SCOPE");
            return Pvs1NmdEscape.evaluate(policy, model, inputs, selection);
        }
        JsonNode supported = policy.at("/nmdScope/engineeringSupportedExonOrdinals");
        JsonNode relevant = policy.at("/nmdScope/relevantExonOrdinals");
        int ptcExon = inputs.has("ptcExonOrdinal") ? inputs.path("ptcExonOrdinal").asInt() : variantExon;
        boolean relevantStop = TruncatingNmdPolicy.contains(relevant, ptcExon);
        boolean supportedStop = TruncatingNmdPolicy.contains(supported, ptcExon);
        for (var carrier : inputs.path("ptcExonOrdinals")) {
            relevantStop &= TruncatingNmdPolicy.contains(relevant, carrier.asInt());
            supportedStop &= TruncatingNmdPolicy.contains(supported, carrier.asInt());
        }
        if (!TruncatingNmdPolicy.contains(relevant, variantExon) || !relevantStop) {
            inputs.remove("nmdPrediction");
            return "PVS1_EXON_RELEVANCE_UNRESOLVED";
        }
        if (!TruncatingNmdPolicy.contains(supported, variantExon) || !supportedStop) {
            inputs.remove("nmdPrediction");
            return "PVS1_ENGINEERING_EXON_SCOPE_NOT_IMPLEMENTED";
        }
        String conditionIssue = Pvs1NmdConditions.evaluate(policy, inputs);
        if (conditionIssue != null) { inputs.remove("nmdPrediction"); return conditionIssue; }
        inputs.put("selectedTranscript", transcript).put("sequenceDerivationStatus", splice
                ? "SOURCE_BOUND_SPLICE_EDIT_AND_TRANSCRIPT_SEQUENCE_AGREE" : "GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE");
        inputs.set("scientificRelevantExonOrdinals", policy.at("/nmdScope/relevantExonOrdinals").deepCopy());
        inputs.set("engineeringSupportedExonOrdinals", supported.deepCopy());
        return null;
    }

    private ObjectNode evaluation(ObjectNode context, ObjectNode atomic, ObjectNode policy,
                                  ObjectNode selection, ObjectNode inputs, String issue, JsonNode modelFacts) {
        boolean fullRna = inputs.path("fullRnaAssessment").isObject();
        boolean splice = inputs.path("spliceAssessment").isObject();
        boolean newSpliceMethod = splice && inputs.at("/spliceAssessment/requiresRuntimeAdapter").asBoolean();
        boolean codingEditMethod = inputs.path("codingEditDerivation").isObject();
        boolean ready = issue == null;
        boolean nonApplicable = fullRna && "NOT_APPLICABLE".equals(inputs.at("/fullRnaAssessment/status").asText()) && issue != null;
        boolean negative = fullRna ? "NOT_MET".equals(inputs.at("/fullRnaAssessment/status").asText()) && issue != null
                : splice ? "NOT_MET".equals(inputs.at("/spliceAssessment/status").asText())
                && issue != null && issue.equals(inputs.at("/spliceAssessment/reasonCode").asText())
                : NON_RELEVANT_EXON.equals(issue) || Pvs1NmdEscape.negative(issue);
        boolean escape = inputs.has("escapeAssessment");
        String strength = fullRna ? inputs.at("/fullRnaAssessment/adoptedStrength").asText()
                : splice ? inputs.at("/spliceAssessment/adoptedStrength").asText()
                : escape ? inputs.at("/escapeAssessment/selectedRule/strength").asText() : "VERY_STRONG";
        if (ready && fullRna && (!"MET".equals(inputs.at("/fullRnaAssessment/status").asText()) || !Set.of("VERY_STRONG","STRONG","MODERATE","SUPPORTING").contains(strength))) {
            issue="PVS1_FULL_RNA_MODULE_RESULT_INVALID";ready=false;
        }
        if (ready && splice && (!"MET".equals(inputs.at("/spliceAssessment/status").asText())
                || !Set.of("VERY_STRONG", "STRONG", "MODERATE", "SUPPORTING").contains(strength))) {
            issue = "PVS1_SPLICE_MODULE_RESULT_INVALID";
            ready = false;
        }
        if (ready && !splice && !fullRna && hasStrengthCap(policy)) strength = Pvs1SpliceConsequence.lower(strength, policy.path("appliedStrength").asText());
        ObjectNode result = mapper.createObjectNode().put("schemaVersion", "criterion-evaluation-v2")
                .put("canonicalizationProfile", "varvis-json-c14n-v1").put("contextId", context.path("contextId").asText())
                .put("criterionId", CRITERION).put("baseStrength", fullRna && !ready ? "NONE" : ready && (newSpliceMethod || fullRna) ? strength : "VERY_STRONG").put("appliedStrength", ready ? strength : "NONE");
        ObjectNode binding = result.putObject("ruleBinding");
        binding.put("ruleRegistryId", resourceProfile.at("/registryBinding/registryId").asText())
                .put("ruleRegistrySha256", resourceProfile.at("/registryBinding/registrySha256").asText())
                .put("currentGeneralDesignation", "PVS1").put("cspecSelectionId", selection.path("selectionId").asText());
        binding.set("authoritySourceIds", policy.path("authoritySourceIds").deepCopy());
        ObjectNode applicability = result.putObject("criterionApplicability").put("status", ready ? "APPLICABLE" : negative || nonApplicable ? "NOT_APPLICABLE" : "UNKNOWN");
        ObjectNode status = result.putObject("evaluationResult").put("status", ready ? "MET" : negative ? "NOT_MET" : "NOT_EVALUATED");
        if (!ready) { applicability.put("reasonCode", issue); status.put("reasonCode", issue); }
        String evidenceId = atomic.path("evidenceId").asText();
        result.putArray("evidenceIds").add(evidenceId);
        ObjectNode trace = result.putObject("deterministicTrace").put("executionStatus", ready || negative || nonApplicable ? "COMPLETED" : "NOT_RUN")
                .put("evaluatorId", EVALUATOR_ID).put("evaluatorVersion",
                        fullRna ? FULL_RNA_EVALUATOR_VERSION : inputs.at("/spliceAssessment/compositeInFrameEffect").asBoolean() ? COMPOSITE_SPLICE_VERSION
                                : inputs.has("ptcExonOrdinals") || NON_RELEVANT_EXON.equals(issue) ? CODING_JUNCTION_VERSION
                                : newSpliceMethod || codingEditMethod ? SPLICE_MODULE_VERSION
                                : TruncatingPvs1Model.extendedTopology(model) ? TruncatingPvs1Model.TOPOLOGY_VERSION
                                : hasStrengthCap(policy) ? STRENGTH_CAP_VERSION
                                : Pvs1SpliceConsequence.SCHEMA.equals(policy.path("schemaVersion").asText()) ? Pvs1SpliceConsequence.VERSION
                                : Pvs1NmdEscape.SCHEMA.equals(policy.path("schemaVersion").asText()) ? Pvs1NmdEscape.VERSION
                                : Pvs1NmdConditions.SCHEMA.equals(policy.path("schemaVersion").asText()) ? EVALUATOR_VERSION : "1.0.4");
        trace.putArray("inputEvidenceIds").add(evidenceId);
        ArrayNode parameters = trace.putArray("parameters");
        parameters.addObject().put("name", "resourceProfileSha256").put("value", resourceProfileSha256);
        parameters.addObject().put("name", "policySha256").put("value", adapter.canonicalSha256(policy));
        parameters.addObject().put("name", "policySelectionSha256").put("value", adapter.canonicalSha256(selection));
        parameters.addObject().put("name", "resolvedInputSha256").put("value", adapter.canonicalSha256(inputs));
        if (modelFacts.isObject()) parameters.addObject().put("name", "transcriptModelFactsSha256").put("value", adapter.canonicalSha256(modelFacts));
        inputs.properties().forEach(entry -> {
            // Full objects remain in resolvedInput. A trace pin avoids serializing the
            // complete RNA, residue tables and provenance a second time as JSON strings.
            if (fullRna && entry.getValue().isContainerNode()) parameters.addObject()
                    .put("name", entry.getKey() + "Sha256").put("value", adapter.canonicalSha256(entry.getValue()));
            else parameters.addObject().put("name", entry.getKey()).put("value",
                    entry.getValue().isContainerNode() ? entry.getValue().toString() : entry.getValue().asText());
        });
        List<JsonNode> ordered = new ArrayList<>(); parameters.forEach(ordered::add);
        ordered.sort(Comparator.comparing(p -> p.path("name").asText())); parameters.removeAll(); ordered.forEach(parameters::add);
        ArrayNode steps = trace.putArray("steps");
        for (String step : ready ? List.of("REVIEWED_CLAUSE_POLICY_AND_MODEL_BOUND", fullRna ? "SOURCE_BOUND_FULL_RNA_PRODUCT_AND_POLICY_VERIFIED"
                : inputs.has("spliceAssessment") ? "SOURCE_BOUND_SPLICE_CONSEQUENCE_DERIVED" : "GENOMIC_CDS_AND_VEP_AGREE",
                inputs.has("fullRnaInitiationAssessment") ? "SOURCE_REVIEWED_RNA_INITIATION_RULE_SUPPORTED"
                        : inputs.has("inFrameAssessment") ? "REVIEWED_INFRAME_SPLICE_RULE_SUPPORTED"
                        : escape ? "REVIEWED_NMD_ESCAPE_RULE_SUPPORTED" : "REVIEWED_NMD_SCOPE_SUPPORTED") : List.of(issue))
            steps.addObject().put("stepCode", step).put("outcome", ready ? "TRUE" : negative ? "FALSE" : "UNKNOWN").putArray("inputEvidenceIds").add(evidenceId);
        if (ready || negative) {
            trace.put("algorithmId", policy.path("policyId").asText()).put("algorithmVersion", policy.path("policyVersion").asText());
            trace.put("traceSha256", adapter.canonicalSha256(trace));
        } else trace.put("reasonCode", issue);
        result.putObject("conflict").put("status", "NONE").putArray("conflictIds");
        result.putObject("review").put("status", "NOT_REQUIRED").put("decision", "NONE").putArray("reviewRecordIds");
        ObjectNode identity = result.deepCopy(); identity.remove(List.of("conflict", "review"));
        result.put("evaluationId", "evaluation-sha256-" + adapter.canonicalSha256(identity));
        return result;
    }

    private ObjectNode disposition(ObjectNode result, String issue) {
        result.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", issue);
        result.putObject("criterionApplicability").put("status", "UNKNOWN").put("reasonCode", issue);
        return result;
    }

    private void validateRegistry() {
        JsonNode binding = resourceProfile.path("registryBinding");
        String path = binding.path("registryClasspathResource").asText();
        try (var input = TruncatingNmdPvs1Runtime.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalArgumentException("PVS1_REGISTRY_IDENTITY_INVALID");
            byte[] bytes = input.readAllBytes();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            JsonNode registry = mapper.readTree(bytes);
            if (registry == null || !hash.equals(binding.path("registrySha256").asText())
                    || !registry.path("registryId").equals(binding.path("registryId")))
                throw new IllegalArgumentException("PVS1_REGISTRY_IDENTITY_INVALID");
            int count = 0;
            for (JsonNode criterion : registry.path("criteria"))
                if (CRITERION.equals(criterion.path("criterionId").asText())) count++;
            if (count != 1) throw new IllegalArgumentException("PVS1_REGISTRY_IDENTITY_INVALID");
        } catch (IOException | NoSuchAlgorithmException invalid) {
            throw new IllegalArgumentException("PVS1_REGISTRY_IDENTITY_INVALID", invalid);
        }
    }
}
