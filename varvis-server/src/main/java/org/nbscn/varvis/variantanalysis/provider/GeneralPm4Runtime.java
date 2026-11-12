package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.springframework.lang.Nullable;

/** Explicit project-local PM4 policy. Explicit opt-in operational inference; no disease-specific whitelist. */
public final class GeneralPm4Runtime {
    public static final String VERSION = "1.2.0";
    public static final String FACTS_VERSION = "1.3.0";
    public static final String CRITERION = "ACMG_AMP_2015:PM4";
    private final ObjectMapper mapper;
    private final CodingTranscriptMaterialSupply sequences;
    @Nullable private final UniProtProteinRegionSupply uniprot;
    @Nullable private final ProteinRegionDemandSupply demand;
    @Nullable private final CombinedProteinRegionSupply combined;
    private final Pm4ProteinRegionAssessment assessment;
    private final PredictionSpecializationGuard specializations;
    private final String nonRepeatPolicy;
    private final ProteinLengthRegionFactSupply facts;
    private int evaluations;

    public GeneralPm4Runtime(ObjectMapper mapper, CodingTranscriptMaterialSupply sequences,
            UniProtProteinRegionSupply uniprot, @Nullable CombinedProteinRegionSupply combined,
            Pm4ProteinRegionAssessment assessment, PredictionSpecializationGuard specializations) {
        this(mapper, sequences, uniprot, combined, assessment, specializations, Pm4OperationalNonRepeatPolicy.REVIEWED_ONLY);
    }
    public GeneralPm4Runtime(ObjectMapper mapper, CodingTranscriptMaterialSupply sequences,
            UniProtProteinRegionSupply uniprot, @Nullable CombinedProteinRegionSupply combined,
            Pm4ProteinRegionAssessment assessment, PredictionSpecializationGuard specializations, String nonRepeatPolicy) {
        this(mapper, sequences, uniprot, combined, assessment, specializations, nonRepeatPolicy, null);
    }
    public GeneralPm4Runtime(ObjectMapper mapper, CodingTranscriptMaterialSupply sequences,
            @Nullable UniProtProteinRegionSupply uniprot, @Nullable CombinedProteinRegionSupply combined,
            Pm4ProteinRegionAssessment assessment, PredictionSpecializationGuard specializations, String nonRepeatPolicy,
            @Nullable ProteinRegionDemandSupply demand) {
        if (uniprot == null && demand == null) throw new IllegalArgumentException("PROTEIN_REGION_LOCAL_NOT_CONFIGURED");
        this.demand = demand;
        this.nonRepeatPolicy = Pm4OperationalNonRepeatPolicy.mode(nonRepeatPolicy);
        this.mapper = mapper; this.sequences = Objects.requireNonNull(sequences); this.uniprot = uniprot;
        this.combined = combined; this.assessment = Objects.requireNonNull(assessment);
        this.specializations = Objects.requireNonNull(specializations);
        this.facts = new ProteinLengthRegionFactSupply(mapper, sequences, uniprot, combined, demand);
    }
    public ObjectNode identity() {
        var out = mapper.createObjectNode().put("policyId", "PROJECT_LOCAL_PM4_LENGTH_CHANGE")
                .put("policyVersion", VERSION).put("factsVersion", FACTS_VERSION).put("criterionId", CRITERION).put("clinicalValidationCompleted", false)
                .put("shortRepeatVersion", ProteinShortRepeatScreen.VERSION).put("automaticNegativeFromNoHit", false)
                .put("nonRepeatPolicy", nonRepeatPolicy).put("operationalPolicyVersion", Pm4OperationalNonRepeatPolicy.VERSION)
                .put("pvs1CombinedAdoptionSupported", false)
                .put("functionalContextRequired", false).put("stopLossSupported", true)
                .put("stopLossScope", sequences.complexAllelesEnabled() ? "NATIVE_GRCH38_VERIFIED_SMALL_ALLELE_WITH_RESOLVED_EXTENSION"
                        : "NATIVE_GRCH38_SNV_WITH_RESOLVED_EXTENSION");
        if (sequences.complexAllelesEnabled()) {
            out.put("complexAllelesEnabled", true).put("sequenceFactsVersion", "2.0.0");
            out.set("publicProjectionCapability", ProteinLengthPublicProof.identity(mapper));
        }
        out.set("stopLossSequenceSupply", sequences.stopLossIdentity());
        out.set("sequenceSupply", sequences.proteinLengthChangeIdentity());var localUniProt=uniprot;
        if (localUniProt != null) out.set("uniprot", localUniProt.identity());
        var localDemand=demand;
        if (localDemand != null) out.set("onDemand", localDemand.identity());
        out.set("regionAssessment", assessment.identity()); out.set("specializations", specializations.identity());
        var localCombined=combined;
        if (localCombined != null) out.set("combined", localCombined.identity());
        return out;
    }
    public int evaluations() { return evaluations; }
    /** Same request-owned source/cache instance; BP3 supplies its own functional assessment. */
    public ProteinLengthRegionFactSupply sharedFactSupply() { return facts; }
    public ObjectNode supplyCounts() { var source=demand;return source == null ? mapper.createObjectNode() : source.counts(); }

    public ObjectNode evaluate(SelectedEvidencePreparation selected, String candidateId, String hypothesisId) {
        var out = mapper.createObjectNode().put("candidateId", candidateId).put("hypothesisId", hypothesisId);
        try {
            var projection = sequences.complexAllelesEnabled()
                    ? selected.codingSequenceProjection(candidateId, hypothesisId)
                    : selected.legacyProjection(candidateId, hypothesisId);
            out.set("sourceProjection", projection.snapshot());
            return evaluate(projection.context(), projection.queryCandidate(), out);
        } catch (RuntimeException bad) { return finish(out, "NOT_EVALUATED", reason(bad)); }
    }
    ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, ObjectNode out) {
        evaluations++;
        out.set("policy", identity()); out.put("contextId", context.path("contextId").asText());
        CodingTranscriptMaterialSupply.Material proofMaterial = null;
        try {
            var spec = specializations.check(context); out.set("specializationSearch", spec);
            if ("BLOCKED".equals(spec.path("status").asText()))
                return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", "PM4_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION");
            var selected = new RuntimeEvidenceV2ProviderWiring(mapper, new RuntimeEvidenceV2Adapter(mapper))
                    .selectedVepConsequence(context, candidate);
            if (selected != null && ProteinStopLossObservation.annotated(selected)) {
                var material = sequences.resolveStopLoss(context, candidate);
                proofMaterial = material;
                out.set("sequenceMaterial", material.snapshot());
                if (material.ready()) out.put("annotationCorrespondence", "COMPLETE_SEQUENCE_COMPARISON_PASSED");
                out.putObject("stopLossAssessment").put("methodVersion", sequences.complexAllelesEnabled() ? "2.0.0" : ProteinStopLossObservation.VERSION)
                        .put("branch", sequences.complexAllelesEnabled() ? "VERIFIED_STOP_LOSS_SMALL_ALLELE" : "STOP_LOSS_SNV")
                        .put("source", "https://doi.org/10.1038/gim.2015.30")
                        .put("interpretation", "REFERENCE_BASED_PREDICTED_EXTENSION_NOT_OBSERVED_RNA_OR_PROTEIN")
                        .put("regionAnnotationRequired", false).put("functionalDisruptionExperimentClaimed", false);
                return finishWithProof(context, proofMaterial, out, material.ready() ? "MET" : "NOT_EVALUATED", material.ready()
                        ? "PM4_PROJECT_LOCAL_SEQUENCE_DERIVED_STOP_LOSS_MET" : material.snapshot().path("reasonCode").asText());
            }
            var supplied = facts.resolve(context, candidate, false); out.setAll(supplied.snapshot());
            var material = supplied.material(); proofMaterial = material;
            var model = material.model();
            if (!material.ready() || model == null)
                return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", material.snapshot().path("reasonCode").asText("PM4_SEQUENCE_MATERIAL_UNAVAILABLE"));
            out.put("annotationCorrespondence", "COMPLETE_SEQUENCE_COMPARISON_PASSED");
            var mapped = (ObjectNode) out.path("proteinAnnotations");
            var extra = (ObjectNode) out.path("combinedRegions");
            if (!"READY".equals(mapped.path("status").asText())) return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", mapped.path("reasonCode").asText());
            var region = assessment.assess(model, mapped); out.set("regionAssessment", region);
            if ("SYNTHETIC_TEST_ONLY".equals(region.at("/qualifiedCoverage/dataClass").asText())
                    && !"SYNTHETIC".equals(context.path("dataClass").asText()))
                return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", "PM4_SYNTHETIC_ASSERTION_RUNTIME_FORBIDDEN");

            var association = material.snapshot().path("alleleAssociation");
            var shortFacts = shortFacts(model, association, mapped); out.set("shortRepeats", shortFacts);
            String state = region.path("regionState").asText(), reason = region.path("reasonCode").asText();
            // Invalid/conflicting reviews are not concealed by a favorable source or by a prediction.
            if ("UNKNOWN".equals(state) && !List.of("PM4_NON_REPEAT_METHOD_OR_CURATED_COVERAGE_NOT_QUALIFIED",
                    "PM4_NON_REPEAT_QUALIFIED_PROTEIN_NOT_COVERED", "PM4_NON_REPEAT_QUALIFIED_COVERAGE_GAP",
                    "PM4_REGION_UNRESOLVED_SOURCE_COORDINATES").contains(reason))
                return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", reason);
            boolean combinedReady = "READY".equals(extra.path("status").asText());
            if (combinedReady) for (var hit : extra.path("repeatOverlaps")) for (var negative : region.at("/qualifiedCoverage/intervals")) {
                if ("NON_REPEAT".equals(negative.path("repeatStatus").asText())
                        && Math.max(mapped.at("/queryRange/start").asInt(), Math.max(hit.path("start").asInt(), negative.path("start").asInt()))
                        <= Math.min(mapped.at("/queryRange/end").asInt(), Math.min(hit.path("end").asInt(), negative.path("end").asInt())))
                    return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", "PM4_REGION_SOURCE_CONFLICT");
            }
            if ("REPEAT_OVERLAP".equals(state) || combinedReady && !extra.path("repeatOverlaps").isEmpty())
                return finishWithProof(context, proofMaterial, out, "NOT_MET", "PM4_PROJECT_LOCAL_REPEAT_OVERLAP_NOT_MET");
            if (!"COMPLETE".equals(shortFacts.path("status").asText()))
                return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", shortFacts.path("reasonCode").asText());
            if (shortFacts.path("candidateFound").asBoolean())
                return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", "PM4_SHORT_REPEAT_CANDIDATE_REQUIRES_QUALIFICATION");
            if (!combinedReady) return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", extra.path("reasonCode").asText());
            if (extra.path("unplacedCount").asInt() != 0) return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", "PM4_COMBINED_UNRESOLVED_COORDINATES");
            if (!"NON_REPEAT_SUPPORTED".equals(state)) {
                // A supplied but partial/unmatched review remains authoritative: never hide it with the fallback.
                if (!Pm4OperationalNonRepeatPolicy.MODE.equals(nonRepeatPolicy)
                        || !assessment.identity().path("assertionsSha256").asText().isEmpty())
                    return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", reason);
                var operational = Pm4OperationalNonRepeatPolicy.assess(mapper, mapped, extra, shortFacts);
                out.set("operationalNonRepeat", operational);
                return finishWithProof(context, proofMaterial, out, operational.path("qualified").asBoolean() ? "MET" : "NOT_EVALUATED",
                        operational.path("reasonCode").asText());
            }
            return finishWithProof(context, proofMaterial, out, "MET", "PM4_PROJECT_LOCAL_QUALIFIED_NON_REPEAT_MET");
        } catch (RuntimeException bad) { return finishWithProof(context, proofMaterial, out, "NOT_EVALUATED", reason(bad)); }
    }

    public static final String FULL_RNA_VERSION="1.0.0";
    public ObjectNode fullRnaIdentity() {
        return identity().put("fullRnaVersion",FULL_RNA_VERSION).put("fullRnaFactsVersion",CodingConsequenceFacts.RNA_VERSION)
                .put("fullRnaRegionVersion",ProteinLengthRegionFactSupply.FULL_RNA_REGION_VERSION)
                .put("fullRnaNonRepeatPolicy","REVIEWED_COVERAGE_REQUIRED");
    }
    public ObjectNode evaluateFullRna(SelectedEvidencePreparation selected,String candidateId,String hypothesisId,
            @Nullable Pvs1SourceQualification.SourceBoundFullRna source) {
        var out=mapper.createObjectNode().put("candidateId",candidateId).put("hypothesisId",hypothesisId);
        try {var projection=selected.codingSequenceProjection(candidateId,hypothesisId);out.set("sourceProjection",projection.snapshot());
            return evaluateFullRna(projection.context(),projection.queryCandidate(),source,out);
        } catch(RuntimeException invalid){return finishFullRna(out,"NOT_EVALUATED",reason(invalid));}
    }
    ObjectNode evaluateFullRna(ObjectNode context,VariantInterpretation candidate,
            @Nullable Pvs1SourceQualification.SourceBoundFullRna source,ObjectNode out) {
        evaluations++;out.remove(List.of("evaluation","publicSummary","sequenceMaterial","proteinRegionEffects","regionAssessment","fullRnaAssessment","fullRnaRegionAssessments","sharedEffectUse","knowledgeSelection","specializationSearch","regionSupply","proteinAnnotations","combinedRegions","shortRepeats","stopLossAssessment"));
        out.set("policy",fullRnaIdentity());out.put("contextId",context.path("contextId").asText());
        try {
            new RuntimeEvidenceV2Adapter(mapper).validateCompleteContext(context);
            var spec=specializations.check(context);out.set("specializationSearch",spec);
            if("BLOCKED".equals(spec.path("status").asText()))return finishFullRna(out,"NOT_EVALUATED","PM4_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION");
            var sequence=facts.resolveFullRnaSequence(context,candidate,source);out.setAll(sequence.snapshot());
            if(!sequence.material().ready())return finishFullRna(out,"NOT_EVALUATED",sequence.material().snapshot().path("reasonCode").asText());
            String assay=Pvs1SourceQualification.fullRnaAssayIssue(source);if(assay!=null)return finishFullRna(out,"NOT_EVALUATED",assay);
            var association=sequence.material().snapshot().path("alleleAssociation");
            out.putObject("sharedEffectUse").put("mechanicalEffectSha256",association.path("mechanicalEffectSha256").asText())
                    .put("sourceOriginalSha256",association.path("sourceOriginalSha256").asText()).put("mayNotCombineWithPvs1ForSameEffect",true);
            if("SOURCE_BOUND_RNA_STOP_LOSS_EXTENSION".equals(association.path("branch").asText()))
                return finishFullRna(out,"MET","PM4_SOURCE_BOUND_RNA_STOP_LOSS_EXTENSION_MET");
            var regional=facts.resolveFullRnaRegions(sequence,false);out.setAll(regional.snapshot());var model=sequence.material().model();
            if(model==null) return finishFullRna(out,"NOT_EVALUATED","FULL_RNA_MODEL_UNAVAILABLE");
            var checks=out.putArray("fullRnaRegionAssessments");String failure=null;boolean repeat=false,nonRepeat=false;
            for(var interval:out.at("/proteinRegionEffects/intervals")) {
                var row=checks.addObject();row.set("referenceProteinRange",interval.path("referenceProteinRange").deepCopy());
                if(!(interval.path("proteinAnnotations") instanceof ObjectNode mapped)||!(interval.path("combinedRegions") instanceof ObjectNode extra)) {
                    if(failure==null)failure="FULL_RNA_REGION_SOURCE_UNAVAILABLE";continue;
                }
                if(!"READY".equals(mapped.path("status").asText())||!"READY".equals(extra.path("status").asText())) {
                    String why=!"READY".equals(mapped.path("status").asText())?mapped.path("reasonCode").asText():extra.path("reasonCode").asText();row.put("reasonCode",why);
                    if(failure==null)failure=why;continue;
                }
                var region=assessment.assess(model,mapped);row.set("regionAssessment",region);String state=region.path("regionState").asText();
                if("SYNTHETIC_TEST_ONLY".equals(region.at("/qualifiedCoverage/dataClass").asText())&&!"SYNTHETIC".equals(context.path("dataClass").asText())) {
                    if(failure==null)failure="PM4_SYNTHETIC_ASSERTION_RUNTIME_FORBIDDEN";continue;
                }
                boolean contradiction=false;
                for(var hit:extra.path("repeatOverlaps"))for(var negative:region.at("/qualifiedCoverage/intervals"))
                    if("NON_REPEAT".equals(negative.path("repeatStatus").asText())&&Math.max(mapped.at("/queryRange/start").asInt(),Math.max(hit.path("start").asInt(),negative.path("start").asInt()))
                            <=Math.min(mapped.at("/queryRange/end").asInt(),Math.min(hit.path("end").asInt(),negative.path("end").asInt())))contradiction=true;
                if(contradiction){row.put("reasonCode","PM4_REGION_SOURCE_CONFLICT");if(failure==null)failure="PM4_REGION_SOURCE_CONFLICT";continue;}
                if(extra.path("unplacedCount").asInt(-1)!=0){if(failure==null)failure="PM4_COMBINED_UNRESOLVED_COORDINATES";continue;}
                if("REPEAT_OVERLAP".equals(state)||!extra.path("repeatOverlaps").isEmpty())repeat=true;
                else if("NON_REPEAT_SUPPORTED".equals(state))nonRepeat=true;
                else {if(failure==null)failure=region.path("reasonCode").asText("PM4_NON_REPEAT_QUALIFICATION_REQUIRED");}
            }
            if(failure!=null)return finishFullRna(out,"NOT_EVALUATED",failure);
            if(checks.isEmpty())return finishFullRna(out,"NOT_EVALUATED","FULL_RNA_REGION_EFFECT_INTERVALS_UNAVAILABLE");
            if(repeat&&nonRepeat)return finishFullRna(out,"NOT_EVALUATED","PM4_FULL_RNA_MIXED_REPEAT_CONTEXT_UNRESOLVED");
            if(repeat)return finishFullRna(out,"NOT_MET","PM4_SOURCE_BOUND_RNA_REPEAT_OVERLAP");
            var screen=out.putObject("shortRepeats");var scanned=screen.putArray("intervals");boolean complete=true,candidateFound=false;
            String ref=association.path("referenceProtein").asText(),alt=association.path("alteredProtein").asText();
            ref=ref.substring(0,ref.length()-1);alt=alt.substring(0,alt.length()-1);
            for(String field:List.of("proteinEffectReferenceResidueRanges","proteinEffectAlteredResidueRanges"))for(var range:association.path(field)) {
                var result=ProteinShortRepeatScreen.scan(mapper,field.equals("proteinEffectReferenceResidueRanges")?ref:alt,range.path("start").asInt(),range.path("end").asInt());
                scanned.addObject().put("sequence",field).set("screen",result);
                complete&="COMPLETE".equals(result.path("status").asText());candidateFound|=!result.path("hits").isEmpty();
            }
            screen.put("status",complete?"COMPLETE":"UNAVAILABLE").put("candidateFound",candidateFound).put("negativeQualificationGranted",false);
            if(!complete)return finishFullRna(out,"NOT_EVALUATED","PM4_SHORT_REPEAT_SCREEN_UNAVAILABLE");
            if(candidateFound)return finishFullRna(out,"NOT_EVALUATED","PM4_SHORT_REPEAT_CANDIDATE_REQUIRES_QUALIFICATION");
            return finishFullRna(out,"MET","PM4_SOURCE_BOUND_RNA_QUALIFIED_NON_REPEAT_MET");
        } catch(RuntimeException invalid){return finishFullRna(out,"NOT_EVALUATED",reason(invalid));}
    }
    private ObjectNode finishFullRna(ObjectNode out,String status,String reason) {
        out.put("status",status).put("reasonCode",reason);
        var evaluation=out.putObject("evaluation").put("criterionId",CRITERION).put("appliedStrength","MET".equals(status)?"MODERATE":"NONE");
        evaluation.putObject("evaluationResult").put("status",status).put("reasonCode",reason);
        var summary=out.putObject("fullRnaAssessment").put("schemaVersion","pm4-full-rna-assessment-v1").put("methodVersion",FULL_RNA_VERSION)
                .put("status",status).put("reasonCode",reason).put("clinicalValidationCompleted",false);
        for(String key:List.of("branch","sourceOriginalSha256","mechanicalEffectSha256","factsSha256","netAminoAcidChange","referenceProteinLength","alteredProteinLength")) {
            var value=out.at("/sequenceMaterial/alleleAssociation/"+key);if(!value.isMissingNode())summary.set(key,value.deepCopy());
        }
        return out;
    }

    private ObjectNode shortFacts(ObjectNode model, JsonNode edit, ObjectNode mapped) {
        String cds = model.path("cdsSequence").asText();
        String altered = cds.substring(0, edit.path("referenceEditStart0").asInt()) + edit.path("insertedSequence").asText()
                + cds.substring(edit.path("referenceEditEnd0").asInt());
        String ref = ProteinLengthChangeObservation.translate(cds), alt = ProteinLengthChangeObservation.translate(altered);
        if (!CodingTranscriptMaterialSupply.sha(alt).equals(edit.path("alteredProteinSha256").asText()))
            throw new IllegalArgumentException("PM4_ALTERED_PROTEIN_IDENTITY_MISMATCH");
        ref = ref.substring(0, ref.length() - 1); alt = alt.substring(0, alt.length() - 1);
        int start = mapped.at("/queryRange/start").asInt(), end = mapped.at("/queryRange/end").asInt();
        // Full equivalent edit envelope plus delta spans all possible altered positions; never just one HGVS placement.
        int altStart = Math.max(1, Math.min(start, alt.length()));
        int altEnd = Math.min(alt.length(), end + Math.max(0, edit.path("netAminoAcidChange").asInt()));
        var a = ProteinShortRepeatScreen.scan(mapper, ref, start, end);
        var b = ProteinShortRepeatScreen.scan(mapper, alt, altStart, Math.max(altStart, altEnd));
        var out = mapper.createObjectNode(); out.set("reference", a); out.set("altered", b);
        boolean ready = "COMPLETE".equals(a.path("status").asText()) && "COMPLETE".equals(b.path("status").asText());
        return out.put("status", ready ? "COMPLETE" : "UNAVAILABLE")
                .put("candidateFound", !a.path("hits").isEmpty() || !b.path("hits").isEmpty())
                .put("reasonCode", ready ? "PM4_SHORT_REPEAT_SCREEN_COMPLETE_NOT_NEGATIVE_PROOF" : "PM4_SHORT_REPEAT_SCREEN_UNAVAILABLE");
    }
    private ObjectNode finishWithProof(ObjectNode context, @org.springframework.lang.Nullable CodingTranscriptMaterialSupply.Material material,
            ObjectNode out, String status, String reason) {
        finish(out, status, reason);
        ProteinLengthPublicProof.attach(mapper, out, material, context, CRITERION);
        out.set("publicSummary", publicSummary(out));
        return out;
    }
    private ObjectNode finish(ObjectNode out, String status, String reason) {
        var evaluation = out.putObject("evaluation").put("criterionId", CRITERION)
                .put("appliedStrength", "MET".equals(status) ? "MODERATE" : "NONE");
        evaluation.putObject("evaluationResult").put("status", status).put("reasonCode", reason);
        out.put("status", status).put("reasonCode", reason);
        out.set("publicSummary", publicSummary(out)); return out;
    }
    public static ObjectNode publicSummary(ObjectNode out) {
        if (out.has("stopLossAssessment")) {
            var summary = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("schemaVersion", "pm4-general-facts-v1").put("policyVersion", VERSION).put("factsVersion", FACTS_VERSION)
                    .put("scope", "VERIFIED_STOP_LOSS_SMALL_ALLELE".equals(out.at("/stopLossAssessment/branch").asText())
                            ? "PROJECT_LOCAL_VERIFIED_STOP_LOSS_SMALL_ALLELE" : "PROJECT_LOCAL_STOP_LOSS_SNV").put("clinicalValidationCompleted", false)
                    .put("regionState", "NOT_APPLICABLE_STOP_LOSS").put("shortRepeatCandidate", false)
                    .put("nonRepeatFromNoHit", false).put("repeatObservation", "NOT_QUERIED_STOP_LOSS_BRANCH")
                    .put("qualificationBasis", "MET".equals(out.path("status").asText()) ? "SEQUENCE_DERIVED_STOP_LOSS" : "NONE")
                    .put("absoluteNonRepeatProof", false).put("pvs1CombinedAdoptionSupported", false)
                    .put("observedRnaOrProtein", false);
            for (String field : List.of("transcript", "protein", "netAminoAcidChange", "referenceProteinLength", "alteredProteinLength",
                    "alteredStopCdsPosition", "newTerminationResolved", "availableThreePrimeBases", "inspectedThreePrimeBases", "predictedHgvsp")) {
                var value = out.at("/sequenceMaterial/alleleAssociation/" + field);
                if (!value.isMissingNode()) summary.set(field, value.deepCopy());
            }
            summary.setAll(ProteinLengthPublicProof.project(new ObjectMapper(), out));
            return summary;
        }
        boolean operational = "MET".equals(out.path("status").asText()) && out.at("/operationalNonRepeat/qualified").asBoolean();
        var summary = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("schemaVersion", "pm4-general-facts-v1").put("policyVersion", VERSION).put("factsVersion", FACTS_VERSION)
                .put("scope", "VERIFIED_INFRAME_LENGTH_CHANGE".equals(out.at("/sequenceMaterial/associationKind").asText())
                        ? "PROJECT_LOCAL_VERIFIED_INFRAME_SMALL_ALLELE" : "PROJECT_LOCAL_SIMPLE_INFRAME").put("clinicalValidationCompleted", false)
                .put("regionState", "NOT_MET".equals(out.path("status").asText()) ? "REPEAT_OVERLAP"
                        : operational ? "OPERATIONAL_NON_REPEAT_SUPPORTED" : out.at("/regionAssessment/regionState").asText("UNKNOWN"))
                .put("shortRepeatCandidate", out.at("/shortRepeats/candidateFound").asBoolean())
                .put("nonRepeatFromNoHit", operational).put("repeatObservation", repeatObservation(out))
                .put("nonRepeatPolicy", out.at("/policy/nonRepeatPolicy").asText(Pm4OperationalNonRepeatPolicy.REVIEWED_ONLY))
                .put("qualificationBasis", operational ? "OPERATIONAL_INFERENCE" : "MET".equals(out.path("status").asText()) ? "REVIEWED_COVERAGE" : "NONE")
                .put("absoluteNonRepeatProof", false).put("pvs1CombinedAdoptionSupported", false);
        for (String field : List.of("transcript", "protein", "netAminoAcidChange")) {
            var value = out.at("/sequenceMaterial/alleleAssociation/" + field);
            if (!value.isMissingNode()) summary.set(field, value.deepCopy());
        }
        var range = out.at("/proteinAnnotations/queryRange"); if (range.isObject()) summary.set("queryRange", range.deepCopy());
        summary.setAll(ProteinLengthPublicProof.project(new ObjectMapper(), out));
        return summary;
    }
    /** Describes observations, not a new negative qualification or evidence strength. */
    private static String repeatObservation(ObjectNode out) {
        var mapped = out.path("proteinAnnotations"); var extra = out.path("combinedRegions");
        var screen = out.path("shortRepeats");
        if ("REPEAT_OVERLAP".equals(out.at("/regionAssessment/regionState").asText())
                || out.at("/regionAssessment/supportingAnnotationLocators").isArray()
                && !out.at("/regionAssessment/supportingAnnotationLocators").isEmpty()
                || "READY".equals(extra.path("status").asText()) && extra.path("repeatOverlaps").isArray()
                && !extra.path("repeatOverlaps").isEmpty()) return "KNOWN_REPEAT";
        if (screen.path("candidateFound").asBoolean()) return "SHORT_REPEAT_CANDIDATE";
        if ("READY".equals(mapped.path("status").asText()) && mapped.path("unmappedFeatures").isArray()
                && mapped.path("unmappedFeatures").isEmpty() && "READY".equals(extra.path("status").asText())
                && extra.path("unplacedCount").isIntegralNumber() && extra.path("unplacedCount").asInt(-1) == 0
                && "COMPLETE".equals(screen.path("status").asText())) return "NO_REPEAT_OBSERVED";
        return "INCOMPLETE";
    }
    private static String reason(RuntimeException bad) {
        String message = bad.getMessage();
        return message != null && message.matches("[A-Z][A-Z0-9_]{0,180}") ? message : "PM4_EXECUTION_UNAVAILABLE";
    }
}
