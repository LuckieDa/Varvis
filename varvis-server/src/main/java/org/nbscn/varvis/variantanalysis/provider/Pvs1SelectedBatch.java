package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;

/** Adapts the existing reviewed PVS1 path to the common prepared Top-N batch, without changing policy. */
public final class Pvs1SelectedBatch {
    public static final String VERSION = "1.6.0";
    public static final String LEGACY_FACTS_VERSION = "1.1.0";
    public static final String SPLICE_VERSION = "1.7.0";
    public static final String COMPOSITE_SPLICE_VERSION = "1.8.0";
    public static final String CRITERION = TruncatingNmdPvs1Runtime.CRITERION;
    private final ObjectMapper mapper;
    private final ReviewedRuleBundle bundle;
    private int expertEvaluatorCalls;
    private final java.util.Map<String, ReviewedRuleBundle.FullRnaPrepared> fullRnaMaterials = new java.util.TreeMap<>();
    @org.springframework.lang.Nullable private final Pvs1StartLossAutomaticPreparation startLossPreparation;
    public Pvs1SelectedBatch(ObjectMapper mapper, ReviewedRuleBundle bundle) {
        this(mapper, bundle, null);
    }
    public Pvs1SelectedBatch(ObjectMapper mapper, ReviewedRuleBundle bundle,
            @org.springframework.lang.Nullable Pvs1StartLossAutomaticPreparation startLossPreparation) {
        this.mapper = mapper; this.bundle = Objects.requireNonNull(bundle);
        this.startLossPreparation = startLossPreparation;
        if (!bundle.supportsPvs1Only() || !bundle.selectedPvs1Enabled())
            throw new IllegalArgumentException("PVS1_SELECTED_KNOWLEDGE_BUNDLE_REQUIRED");
    }
    public ObjectNode identity() {
        var out = bundle.identity();
        var preparation = startLossPreparation;
        if (preparation != null) out.set("startLossAutomaticPreparation", preparation.identity());
        return out;
    }
    public ObjectNode startLossOriginals() { var preparation = startLossPreparation; return preparation == null ? mapper.createObjectNode() : preparation.originals(); }
    public ObjectNode startLossAcquisitionCounts() { var preparation = startLossPreparation; return preparation == null ? mapper.createObjectNode() : preparation.counts(); }
    public int evaluatorCalls() { return bundle.pvs1EvaluatorCalls() + expertEvaluatorCalls; }
    public int projectionCalls() { return bundle.pvs1ProjectionCalls(); }
    /** Neutral material only; callers may pass this same live object to independent criterion consumers. */
    public ReviewedRuleBundle.FullRnaPrepared prepareFullRna(SelectedEvidencePreparation selected,
            String candidateId, String hypothesisId, String sourceSha) {
        var prepared = bundle.prepareFullRna(selected, candidateId, hypothesisId, sourceSha);
        rememberFullRna(prepared, sourceSha, prepared.snapshot().path("selectedProjectionSha256").asText());
        return prepared;
    }
    /** Only live preparations from this batch; saved JSON is never used to populate this collection. */
    public ObjectNode privateFullRnaMaterials() {
        var out = mapper.createObjectNode().put("schemaVersion", "full-rna-selected-private-collection-v1")
                .put("methodVersion", ReviewedRuleBundle.FULL_RNA_PREPARATION_VERSION).put("restoresLiveQualification", false);
        var rows = out.putArray("materials");
        fullRnaMaterials.forEach((key, value) -> rows.addObject().put("bindingKey", key).set("material", value.privateSnapshot(mapper)));
        return out;
    }
    @org.springframework.lang.Nullable ReviewedRuleBundle.FullRnaPrepared preparedFullRna(String sourceSha, String projectionSha) {
        return fullRnaMaterials.get(sourceSha + "/" + projectionSha);
    }
    private void rememberFullRna(ReviewedRuleBundle.FullRnaPrepared prepared, String sourceSha, String projectionSha) {
        if (prepared.configured()) fullRnaMaterials.put(sourceSha + "/" + projectionSha, prepared);
    }
    public ObjectNode evaluate(SelectedEvidencePreparation selected, String candidateId, String hypothesisId, String sourceSha) {
        return evaluate(selected, candidateId, hypothesisId, sourceSha, null);
    }
    public ObjectNode evaluate(SelectedEvidencePreparation selected, String candidateId, String hypothesisId, String sourceSha,
            @org.springframework.lang.Nullable ReviewedRuleBundle.FullRnaPrepared suppliedFullRna) {
        try {
            var fullRna = suppliedFullRna == null ? prepareFullRna(selected, candidateId, hypothesisId, sourceSha) : suppliedFullRna;
            if (fullRna.targetsFullRna()) {
                if (!fullRna.ready()) return withFullRna(fullRna, bundle.refusePvs1(fullRna.snapshot().path("reasonCode").asText()));
                var rnaProjection = selected.codingSequenceProjection(candidateId, hypothesisId);
                if (!fullRna.matchesSelected(mapper, rnaProjection.context(), sourceSha, rnaProjection.bindingSha256()))
                    throw new IllegalArgumentException("FULL_RNA_SELECTED_BINDING_MISMATCH");
                var result = bundle.evaluateFullRna(rnaProjection.context(), rnaProjection.queryCandidate(), fullRna);
                result.set("sourceProjection", rnaProjection.snapshot());
                result.put("executionSourceSha256", sourceSha).put("selectedContextProjectionSha256", rnaProjection.bindingSha256());
                rememberFullRna(fullRna, sourceSha, rnaProjection.bindingSha256());
                return withFullRna(fullRna, result);
            }
            var projection = selected.legacyProjection(candidateId, hypothesisId);
            var context = projection.context();
            String selection = context.at("/transcript/selectionPolicy").asText();
            // SelectedEvidencePreparation has already bound the explicit recommendation and exact
            // annotation. Keep that original confirmation/context hash; do not relabel it as a policy review.
            boolean batchConfirmation = List.of("1.1.0-local-batch1", "1.0.0-unified-local-eleven", "1.0.0-unified-local-fourteen").stream()
                    .anyMatch(version -> (org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.POLICY_ID + "@" + version).equals(selection));
            if (!batchConfirmation && !new ReviewedRuleContextPreparation(mapper, bundle.identity()).accepts(selection))
                throw new IllegalArgumentException("PVS1_TRANSCRIPT_CONFIRMATION_REQUIRED");
            var preparation = startLossPreparation;
            Pvs1StartLossAutomaticPreparation.Prepared prepared = preparation != null
                    && Pvs1StartLossResolver.selectedStartLoss(context, projection.queryCandidate())
                    ? preparation.prepareForEvaluation(context, projection.queryCandidate(), bundle) : null;
            ObjectNode result = prepared == null ? null : prepared.decision();
            if (result != null) expertEvaluatorCalls++;
            if (result == null && prepared != null) {
                var expert = prepared.preparation().path("expertAssertions");
                // A known but unimplemented specialist rule must not fall through
                // to a different generic/legacy rule.
                if (prepared.sourceQualifiedConflict()) result = bundle.refusePvs1("PVS1_START_LOSS_EVIDENCE_CONFLICT");
                else if ("UNAVAILABLE".equals(expert.path("status").asText())
                        || expert.path("entries").isArray() && !expert.path("entries").isEmpty()) {
                    String ruleReason = prepared.preparation().at("/publishedRuleCheck/reasonCode").asText();
                    result = bundle.refusePvs1(java.util.Set.of("PVS1_START_LOSS_PUBLISHED_RULE_CONFLICT",
                            "PVS1_START_LOSS_PUBLISHED_STRENGTH_CONFLICT", "PVS1_START_LOSS_PUBLISHED_SEQUENCE_CONDITION_MISMATCH",
                            "PVS1_START_LOSS_PUBLISHED_CONDITIONS_UNSUPPORTED").contains(ruleReason)
                            ? ruleReason : "PVS1_START_LOSS_EXPERT_RULE_UNRESOLVED");
                }
            }
            if (result == null) result = bundle.evaluate(CRITERION, context, projection.queryCandidate());
            if (prepared != null) {
                var audit = prepared.preparation();
                if (result.path("policySelection").isObject()) {
                    var selectionAudit = audit.withObject("policySelection");
                    for (String key : List.of("outcome", "reasonCode", "policyId"))
                        if (result.path("policySelection").has(key)) selectionAudit.set(key, result.path("policySelection").path(key));
                }
                result.set("startLossAutomaticPreparation", audit);
            }
            result.set("sourceProjection", projection.snapshot());
            if (fullRna.configured()) result.set("fullRnaPreparation", fullRna.publicSummary(mapper));
            result.put("executionSourceSha256", sourceSha).put("selectedContextProjectionSha256", projection.bindingSha256());
            return result;
        } catch (RuntimeException failure) {
            String reason = failure.getMessage();
            return bundle.refusePvs1(reason != null && reason.matches("[A-Z][A-Z0-9_]{1,120}")
                    ? reason : "PVS1_SELECTED_PREPARATION_UNAVAILABLE");
        }
    }
    private ObjectNode withFullRna(ReviewedRuleBundle.FullRnaPrepared prepared, ObjectNode result) {
        result.set("fullRnaPreparation", prepared.publicSummary(mapper)); return result;
    }
    /** Deliberate public fields only; full sequence, source records and model stay in private material. */
    public static ObjectNode publicSummary(ObjectMapper mapper, JsonNode result) {
        var savedSummary = result.path("publicSummary");
        if (savedSummary instanceof ObjectNode storedSummary && "SOURCE_BOUND_FULL_RNA_CONSEQUENCE".equals(savedSummary.path("method").asText())
                && !result.at("/resolvedInput/fullRnaAssessment").isObject()
                && result.at("/evaluation/deterministicTrace/evaluatorVersion").isMissingNode()) {
            var saved = storedSummary.deepCopy();
            if (List.of("1.9.0", "1.10.0", "1.10.1").contains(saved.path("methodVersion").asText()))
                saved.setAll(FullRnaCurrentUse.pvs1MethodMetadata(mapper, result, false, null));
            return saved; // Legacy unexecuted summaries without a saved method stay under their original contract.
        }
        if (result.at("/resolvedInput/fullRnaAssessment").isObject()) {
            var out = mapper.createObjectNode().put("schemaVersion", "pvs1-selected-facts-v1")
                    .put("method", "SOURCE_BOUND_FULL_RNA_CONSEQUENCE").put("clinicalValidationCompleted", false);
            out.setAll(FullRnaCurrentUse.pvs1MethodMetadata(mapper, result, false, null));
            out.set("fullRnaAssessment", result.at("/resolvedInput/fullRnaAssessment").deepCopy());
            return withFullRnaSummary(mapper, result, out);
        }
        if (result.path("automaticStartLossFacts").isObject()) {
            var out = mapper.createObjectNode().put("schemaVersion", "pvs1-selected-facts-v1").put("methodVersion",
                            result.path("automaticStartLossFacts").has("sourceQualifiedTranscriptAssessment") ? "1.6.0" : "1.5.0")
                    .put("scope", "AUTOMATIC_GENERIC_START_LOSS").put("clinicalValidationCompleted", false);
            out.set("automaticAssessment", result.path("automaticStartLossFacts").deepCopy());
            return withFullRnaSummary(mapper, result, out);
        }
        if (result.path("expertStartLossFacts").isObject()) {
            var out = mapper.createObjectNode().put("schemaVersion", "pvs1-selected-facts-v1").put("methodVersion",
                    "pvs1-start-loss-expert-facts-v2".equals(result.at("/expertStartLossFacts/schemaVersion").asText()) ? "1.4.0" : "1.3.0")
                    .put("scope", "PUBLISHED_EXPERT_START_LOSS").put("clinicalValidationCompleted", false);
            out.set("expertAssessment", result.path("expertStartLossFacts").deepCopy());
            if (out.at("/expertAssessment/expertAssertions") instanceof ObjectNode expert)
                Pvs1StartLossAutomaticPreparation.summarizeExpertAssertions(mapper, expert);
            out.set("automaticPreparation", Pvs1StartLossAutomaticPreparation.publicSummary(mapper, result.path("startLossAutomaticPreparation")));
            return withFullRnaSummary(mapper, result, out);
        }
        boolean startLoss = result.path("startLossFacts").isObject();
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-selected-facts-v1").put("methodVersion", startLoss ? "1.2.0" : LEGACY_FACTS_VERSION)
                .put("scope", "REVIEWED_SELECTED_TRANSCRIPT").put("clinicalValidationCompleted", false)
                .put("knowledgeStatus", result.at("/knowledgeAssessment/status").asText("NOT_CHECKED"))
                .put("knowledgeReason", result.at("/knowledgeAssessment/reasonCode").asText("PVS1_KNOWLEDGE_ASSESSMENT_UNAVAILABLE"))
                .put("policySchema", result.at("/policy/schemaVersion").asText("UNAVAILABLE"))
                .put("doubleCountingConstraint", "NO_AUTOMATIC_COMBINATION_OF_PVS1_WITH_OTHER_EFFECT_EVIDENCE");
        if (result.path("startLossAutomaticPreparation").isObject()) out.set("automaticPreparation",
                Pvs1StartLossAutomaticPreparation.publicSummary(mapper, result.path("startLossAutomaticPreparation")));
        if (startLoss) {
            var facts = result.path("startLossFacts");
            var summary = out.putObject("startLossAssessment");
            for (String field : List.of("schemaVersion", "methodVersion", "status", "reasonCode", "selectedTranscript", "strand",
                    "cdsPosition", "referenceCodon", "alternateCodon", "candidateRestartCdsPosition",
                    "candidateRestartInterpretation", "transcriptReviewStatus", "upstreamReferenceReviewStatus", "reviewReasonCode",
                    "decisionOutcome", "decisionStrength", "decisionReasonCode", "clinicalScoringPerformed",
                    "transcriptReviewComplete", "upstreamSearchComplete", "qualifyingReferenceCount", "runtimeMaterialSha256"))
                if (facts.path(field).isValueNode()) summary.set(field, facts.path(field).deepCopy());
            if (facts.path("sourceBindings").isArray()) {
                var sources = summary.putArray("sources");
                for (var source : facts.path("sourceBindings")) {
                    var item = sources.addObject();
                    for (String field : List.of("sourceId", "role", "url", "sha256", "reviewId"))
                        if (source.path(field).isTextual()) item.set(field, source.path(field).deepCopy());
                }
            }
            if (facts.path("materialEvidence").isObject())
                summary.set("materialEvidence", facts.path("materialEvidence").deepCopy());
            return withFullRnaSummary(mapper, result, out);
        }
        var input = result.path("resolvedInput");
        boolean newSplice = input.at("/spliceAssessment/requiresRuntimeAdapter").asBoolean();
        if (input.at("/spliceAssessment/compositeInFrameEffect").asBoolean())
            out.put("methodVersion", COMPOSITE_SPLICE_VERSION).put("method", "SOURCE_BOUND_COMPOSITE_SPLICE_CONSEQUENCE");
        else if (newSplice) out.put("methodVersion", SPLICE_VERSION).put("method", "SOURCE_BOUND_SPLICE_CONSEQUENCE");
        else if(input.path("codingEditDerivation").isObject())out.put("methodVersion",SPLICE_VERSION).put("method","SOURCE_BOUND_CODING_EDITS");
        if (result.path("codingApplicability").isObject()) {
            var applicability = result.path("codingApplicability").deepCopy();
            ((ObjectNode) applicability).remove("evidence");
            out.set("codingApplicability", applicability);
        }
        out.put("policyStrengthCap", result.at("/policy/appliedStrength").asText("UNAVAILABLE"));
        out.put("sourceQualifiedResearch", Pvs1EscapeAdoptionProof.sourceQualifiedResearch(mapper, result));
        if (result.path("unresolvedAssessment").isObject())
            out.set("unresolvedAssessment", result.path("unresolvedAssessment").deepCopy());
        var supply = result.path("transcriptMaterialSupply");
        if ("EXACT_FULL_TRANSCRIPT_EQUIVALENCE".equals(supply.path("bindingMethod").asText())) {
            var summary = out.putObject("materialSupply");
            for (String field : List.of("status", "reasonCode", "bindingMethod", "verifiedExonCount", "policyModelUnchanged"))
                if (supply.path(field).isValueNode()) summary.set(field, supply.path(field).deepCopy());
        }
        if (input.path("transcriptTopology").isObject()) {
            var topology = out.putObject("transcriptTopology");
            for (String field : List.of("version", "methodVersion", "kind", "exonCount", "cdsLength", "lastJunctionCdsPosition", "referenceLastJunctionCdsPosition"))
                if (input.path("transcriptTopology").path(field).isValueNode())
                    topology.set(field, input.path("transcriptTopology").path(field).deepCopy());
            topology.put("utrApplicabilityBound", "REVIEWED_FOR_3_PRIME_UTR_JUNCTION"
                    .equals(result.at("/policy/nmdScope/utrJunctionApplicability/status").asText()));
        }
        for (String field : List.of("selectedTranscript", "variantMechanism", "nmdPrediction", "sequenceDerivationStatus", "coordinateSystem",
                "cdsPosition", "exonOrdinal", "exonTotal", "ptcStart", "ptcEnd", "distanceToLastJunction", "lastJunctionCdsPosition",
                "referenceLastJunctionCdsPosition", "mutantLastJunctionCdsPosition", "referenceProteinLength", "alteredProteinLength",
                "referenceExonTotal", "alteredExonTotal", "ptcAlteredExonOrdinal", "ptcReferenceStart", "ptcReferenceMiddle", "ptcReferenceEnd", "ptcReferenceMapping", "ptcReferenceMappingReason", "referenceEventSpanMeaning"))
            if (input.path(field).isValueNode() && (!input.path(field).isNull() || input.path("transcriptTopology").isObject()))
                out.set(field, input.path(field).deepCopy());
        if (input.path("ptcExonOrdinals").isArray()) out.set("ptcExonOrdinals", input.path("ptcExonOrdinals").deepCopy());
        for (String name : List.of("spliceAssessment", "escapeAssessment")) if (input.path(name).isObject()) {
            var subset = out.putObject(name);
            for (String field : List.of("schemaVersion", "methodVersion", "outcomeId", "evidenceKind", "interpretation", "maximumStrength", "doubleCountingConstraint",
                    "status", "reasonCode", "basis", "appliedStrength", "adoptedStrength", "rnaReadingFrame", "compositionStatus", "ptcCrossesJunction"))
                if (input.path(name).path(field).isValueNode()) subset.set(field, input.path(name).path(field).deepCopy());
        }
        if (input.path("spliceAssessment").isObject()) {
            var audit = input.path("spliceAssessment");
            var summary = out.withObject("spliceAssessment");
            for (String name : List.of("referenceTopology", "alteredTopology")) if (audit.path(name).isObject())
                copyScalars(summary.putObject(name), audit.path(name), List.of("methodVersion", "scope", "kind", "exonCount", "cdsLength", "lastJunctionCdsPosition"));
            for (String name : List.of("retainedExons", "alteredJunctions", "ptcExons"))
                copyRows(summary, name, audit.path(name), List.of("referenceOrdinal", "alteredOrdinal", "alteredCdsStart", "alteredCdsEnd", "leftReferenceOrdinal", "rightReferenceOrdinal", "alteredCdsPosition"));
            copyRows(summary, "sourceRecords", audit.path("sourceRecords"), List.of("sourceId", "sourceVersion", "url", "sha256"));
            if (audit.path("codingEditRejection").isObject()) copyScalars(summary.putObject("codingEditRejection"), audit.path("codingEditRejection"),
                    List.of("schemaVersion", "methodVersion", "reasonCode", "bindingStatus", "partialResultAvailable", "scientificQualificationGranted"));
        }
        if (input.path("inFrameAssessment").isObject()) {
            var audit = input.path("inFrameAssessment");var summary = out.putObject("inFrameAssessment");
            copyScalars(summary, audit, List.of("schemaVersion", "operatorVersion", "status", "reasonCode", "strength", "policyReviewId",
                    "deleteStartCds", "deleteEndCds", "deletedCdsBases", "referenceProteinLength", "alteredProteinLength", "codonAligned",
                    "firstTouchedReferenceResidue", "lastTouchedReferenceResidue", "fullyRemovedReferenceResidues", "firstFullyRemovedReferenceResidue",
                    "lastFullyRemovedReferenceResidue", "netCodonDeletion", "deletedProteinResidues", "deletedProteinResidueMetric", "nativeStopRetained", "sequenceDerivationStatus",
                    "compositeInFrameEffect", "codingConsequenceFactsSha256", "mechanicalEffectSha256"));
            copyRows(summary, "wholeDeletedReferenceResidueRanges", audit.path("wholeDeletedReferenceResidueRanges"), List.of("start", "end"));
            copyRows(summary, "touchedReferenceResidueRanges", audit.path("touchedReferenceResidueRanges"), List.of("start", "end"));
            if (audit.path("selectedRule").isObject()) copyScalars(summary.putObject("selectedRule"), audit.path("selectedRule"),
                    List.of("ruleId", "predicate", "start", "end", "minimumAffectedResidues", "numerator", "denominator", "comparison", "strength"));
            copyRows(summary,"ruleChecks",audit.path("ruleChecks"),List.of("ruleId","predicate","status","matched","affectedResidues",
                    "partiallyDeletedReferenceResiduesInRegion","actualNumerator","actualDenominator","leftProduct","rightProduct","comparison","strength","routingBasis","criticalRegionAssessmentIssue"));
            if (audit.path("junctionEffect").isObject()) copyScalars(summary.putObject("junctionEffect"), audit.path("junctionEffect"),
                    List.of("alteredProteinPosition", "alteredCodon", "alteredAminoAcid", "leftReferenceResidue", "rightReferenceResidue", "leftRetainedBaseCount", "rightRetainedBaseCount"));
        }
        if (input.path("codingEditComposition").isObject()) {
            var audit = input.path("codingEditComposition");var summary = out.putObject("codingEditComposition");
            copyScalars(summary, audit, List.of("schemaVersion", "methodVersion", "status", "reasonCode", "sequenceScope", "alleleGroupSha256",
                    "referenceCdsSha256", "alteredCdsSha256", "netLengthChange", "codingEditStatus", "spliceEventSha256", "compositionStatus",
                    "scope", "topologyScope", "scientificQualificationGranted", "referenceCdsLength", "alteredCdsLength", "netCdsLengthChange",
                    "explicitCompositionReviewed", "rnaSequenceScope", "completeAllelicCdsWitnessVerified", "fullRnaOrEjcProofGranted"));
            if (audit.path("binding").isObject()) copyScalars(summary.putObject("binding"), audit.path("binding"),
                    List.of("assembly", "contig", "transcript", "modelSha256", "referenceCdsSha256", "contextSha256"));
            if (audit.path("sourceDeclaration").isObject()) copyScalars(summary.putObject("sourceDeclaration"),audit.path("sourceDeclaration"),
                    List.of("schemaVersion","status","reviewId","alleleGroupSha256","referenceCdsSha256","spliceEventSha256","sequenceScope"));
            copyRows(summary,"edits",audit.path("edits"),List.of("id","origin","status","referenceEditStart0","referenceEditEnd0","evidenceBindingSha256","alleleGroupSha256","insertionExonOrdinal"));
        }
        if(input.path("codingEditDerivation").isObject()) {
            var audit=input.path("codingEditDerivation");var summary=out.putObject("codingEditDerivation");
            copyScalars(summary,audit,List.of("schemaVersion","methodVersion","scope","scientificQualificationGranted","topologyScope",
                    "alteredCdsSha256","referenceCdsLength","alteredCdsLength","netCdsLengthChange"));
            if(audit.path("binding").isObject())copyScalars(summary.putObject("binding"),audit.path("binding"),
                    List.of("assembly","contig","transcript","modelSha256","referenceCdsSha256","contextSha256"));
            copyRows(summary,"edits",audit.path("edits"),List.of("id","origin","status","referenceEditStart0","referenceEditEnd0","evidenceBindingSha256","alleleGroupSha256","insertionExonOrdinal"));
        }
        if(input.path("escapeAssessment").isObject() || "NMD_NOT_PREDICTED".equals(input.path("nmdPrediction").asText())) {
            var proof = Pvs1EscapeAdoptionProof.project(mapper,result);out.set("escapeAdoptionProof",proof);
            if (!input.path("spliceAssessment").isObject())
                out.set("codingEscapeAssessment",Pvs1EscapeAdoptionProof.codingAssessment(mapper,result,proof));
        }
        return withFullRnaSummary(mapper, result, out);
    }
    private static ObjectNode withFullRnaSummary(ObjectMapper mapper, JsonNode result, ObjectNode out) {
        var source = result.path("fullRnaPreparation");
        if (!"full-rna-selected-source-summary-v1".equals(source.path("schemaVersion").asText())) return out;
        var summary = out.putObject("fullRnaPreparation");
        copyScalars(summary, source, List.of("schemaVersion", "methodVersion", "status", "reasonCode", "configurationReasonCode", "entryId", "contextSha256",
                "executionSourceSha256", "selectedProjectionSha256", "layer", "criterionConsumerStatus"));
        summary.put("criterionAdoptionGranted", false).put("functionalQualificationGranted", false).put("phaseQualificationGranted", false);
        for (String name : List.of("binding", "sourceRecord", "assayConditions", "topology")) if (source.path(name).isObject())
            copyScalars(summary.putObject(name), source.path(name), List.of("originalSha256", "contextSha256", "modelSha256", "referenceRnaSha256",
                    "alteredRnaSha256", "mechanicalEffectSha256", "factsSha256", "resultSha256", "topologySha256", "eligibilityStage",
                    "sourceId", "version", "url", "sha256", "evidenceKind", "alleleAttribution", "assayQuality", "tissueRelevance",
                    "residualNormalTranscript", "outcomeMultiplicity", "coordinateSystem", "topologyCompleteness", "sourceQualificationStatus",
                    "singleExon", "lastJunctionRnaPosition0"));
        if (source.path("sequenceEffect").isObject()) {
            var effect = summary.putObject("sequenceEffect");
            copyScalars(effect, source.path("sequenceEffect"), List.of("coordinateSystem", "referenceRnaLength", "alteredRnaLength", "referenceProteinLength",
                    "translationStatus", "alteredProteinLength", "netProteinLengthChange", "proteinLengthOutcome", "referenceCdsLength", "editedReferenceCdsIntervalLength"));
            for (String name : List.of("nativeStart", "nativeStop", "firstStop")) if (source.path("sequenceEffect").path(name).isObject())
                copyScalars(effect.putObject(name), source.path("sequenceEffect").path(name), List.of("status", "retainedContiguously",
                        "referenceStartRna1", "referenceEndRna1", "alteredStartRna1", "alteredEndRna1", "inReadingFrameFromRetainedNativeStart", "kind", "baseOrigin"));
        }
        return out;
    }
    private static void copyScalars(ObjectNode target, JsonNode source, List<String> fields) {
        for (String field : fields) if (source.path(field).isValueNode()) target.set(field, source.path(field).deepCopy());
    }
    private static void copyRows(ObjectNode target, String field, JsonNode source, List<String> fields) {
        if (!source.isArray()) return;
        var rows = target.putArray(field);int count = 0;
        for (var row : source) { if (count++ == 32) break;copyScalars(rows.addObject(), row, fields); }
        target.put(field + "Total", source.size()).put(field + "Truncated", source.size() > 32);
    }
}
