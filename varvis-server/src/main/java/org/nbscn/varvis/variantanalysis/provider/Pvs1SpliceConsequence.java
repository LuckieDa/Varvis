package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

/** Source-bound RNA/predicted edits, not an inference of an RNA event from a score. */
final class Pvs1SpliceConsequence {
    static final String SCHEMA = "pvs1-truncating-nmd-policy-v4";
    // Keep the existing public evaluator contract for unchanged legacy paths.
    static final String VERSION = "1.3.0";
    static final String MODULE_VERSION = "1.5.2";
    static final String COMPOSITE_MODULE_VERSION = "1.6.0";
    static final String INITIATION_POSITION_SCOPE_VERSION = "1.0.0";
    private static final List<String> STRENGTHS = List.of("SUPPORTING", "MODERATE", "STRONG", "VERY_STRONG");
    private static final Set<String> KINDS = Set.of("OBSERVED_RNA", "PREDICTED_CANONICAL");
    private static final Set<String> EVENTS = Set.of("EXON_SKIPPING", "EXON_SHORTENING");
    private Pvs1SpliceConsequence() { }

    /** Bounded full-RNA mechanics for subsequent independently qualified consumers.
     * This entry returns no evidence status/strength and never treats a declaration as source authority.
     */
    static ObjectNode describeFullRnaMechanics(JsonNode context, CodingTranscriptEdits.RnaResult result,
            @org.springframework.lang.Nullable Pvs1SpliceTopology.RnaTopologyDeclaration declaration) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();var hashes = new RuntimeEvidenceV2Adapter(mapper);
        var facts = CodingConsequenceFacts.deriveRna(mapper, context, result);var sequence = facts.snapshot();
        var topology = Pvs1SpliceTopology.projectFullRna(result, declaration);
        if (facts.nativeStartRetained() != topology.nativeStart().retainedContiguously())
            throw new IllegalArgumentException("PVS1_FULL_RNA_SEQUENCE_TOPOLOGY_BINDING_MISMATCH");
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-full-rna-mechanical-assessment-v1")
                .put("methodVersion", "1.0.0").put("layer", "MECHANICAL_FACTS_ONLY")
                .put("evidenceAdoptionGranted", false).put("sourceQualificationGranted", false).put("phaseQualificationGranted", false)
                .put("mechanicalFactsSha256", facts.canonicalSha256()).put("mechanicalEffectSha256", facts.mechanicalEffectSha256())
                .put("rnaEditFactsSha256", hashes.canonicalSha256(result.snapshot(mapper)));
        out.set("sequence", sequence);out.set("topology", topology.snapshot());
        boolean hasStop = sequence.at("/firstStop/alteredEndRna1").isIntegralNumber();
        boolean nativeStopRetained = sequence.at("/nativeStop/retainedContiguously").asBoolean();
        String branch;
        if (!facts.nativeStartRetained()) branch = "INITIATION_UNRESOLVED_AFTER_RNA_EDIT";
        else if (!hasStop) branch = "TERMINATION_UNRESOLVED_IN_DECLARED_RNA";
        else if (!nativeStopRetained) branch = sequence.path("netProteinLengthChange").asInt() > 0
                ? "REFERENCE_STOP_REMOVAL_WITH_EXTENSION" : "REFERENCE_STOP_NOT_RETAINED_WITH_RESOLVED_NON_NATIVE_TERMINATION";
        else if ("NATIVE_REFERENCE_STOP".equals(facts.firstStopKind())) branch = "REFERENCE_TERMINATION_RETAINED";
        else branch = "NON_NATIVE_TERMINATION_WITH_REFERENCE_STOP_BASES_RETAINED";
        out.put("mechanicalBranch", branch);
        var geometry = out.putObject("nmdGeometry").put("interpretation", "DECLARED_TOPOLOGY_DISTANCE_ONLY_NO_NMD_OR_PVS1_ADOPTION")
                .put("sourceQualificationGranted", false).putNull("distanceToLastJunctionRna");
        if (topology.completeness() != Pvs1SpliceTopology.RnaTopologyCompleteness.COMPLETE) geometry.put("status", "TOPOLOGY_UNRESOLVED");
        else if (!facts.nativeStartRetained()) geometry.put("status", "INITIATION_UNRESOLVED");
        else if (!hasStop) geometry.put("status", "TERMINATION_UNRESOLVED");
        else {
            Integer junction = topology.lastJunctionRnaPosition0();
            if (junction == null) geometry.put("status", "NO_EXON_JUNCTION_IN_DECLARED_TOPOLOGY");
            else geometry.put("status", "DISTANCE_RESOLVED_NO_POLICY_APPLIED")
                    .put("lastJunctionRnaPosition0", junction)
                    .put("firstStopEndRna1", sequence.at("/firstStop/alteredEndRna1").asInt())
                    .put("distanceToLastJunctionRna", junction - sequence.at("/firstStop/alteredEndRna1").asInt());
        }
        var requirements = out.putArray("independentAdoptionRequirements");
        for (String condition : List.of("SOURCE_QUALIFIED_COMPLETE_RNA_EFFECT_AND_ALLELE_ATTRIBUTION", "QUALIFIED_PHYSICAL_ALLELE_OR_CIS_SCOPE",
                "SOURCE_QUALIFIED_COMPLETE_JUNCTIONS_FOR_NMD_USE", "CRITERION_SPECIFIC_FUNCTION_AND_APPLICABLE_RULE"))
            requirements.addObject().put("condition", condition).put("state", "NOT_GRANTED_BY_MECHANICAL_CONSUMER");
        return out;
    }

    static final String FULL_RNA_MODULE_VERSION = "1.1.1";

    /** Source original replay and the selected policy are independent prerequisites.
     * This method never infers initiation, functional rescue, phase or junctions. */
    static String resolveFullRnaForIntegration(ObjectNode context, ObjectNode policy, ObjectNode selection,
            @org.springframework.lang.Nullable Pvs1SourceQualification.SourceBoundFullRna source, ObjectNode input) {
        clearDerived(input);input.remove(List.of("fullRnaAssessment", "fullRnaFacts", "fullRnaSource", "fullRnaInitiationAssessment"));
        var audit = input.putObject("fullRnaAssessment").put("schemaVersion", "pvs1-full-rna-assessment-v1")
                .put("methodVersion", FULL_RNA_MODULE_VERSION).put("status", "NOT_EVALUATED");
        String issue;
        try { issue = resolveFullRna(context, policy, selection, source, input, audit); }
        catch (IllegalArgumentException invalid) { issue = "PVS1_FULL_RNA_INPUT_OR_REPLAY_INVALID"; }
        if (issue != null) {
            if (!Set.of("NOT_MET", "NOT_APPLICABLE").contains(audit.path("status").asText())) audit.put("status", "NOT_EVALUATED");
            audit.put("reasonCode", issue);
        }
        return issue;
    }

    private static String resolveFullRna(ObjectNode context, ObjectNode policy, ObjectNode selection,
            @org.springframework.lang.Nullable Pvs1SourceQualification.SourceBoundFullRna source, ObjectNode input, ObjectNode audit) {
        if (source == null) return "PVS1_FULL_RNA_SOURCE_REQUIRED";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();var hashes = new RuntimeEvidenceV2Adapter(mapper);
        var material = source.result().reference().material();var model = material.model();var sequence = source.facts().snapshot();
        if (!hashes.canonicalSha256(context).equals(material.contextSha256())) return "PVS1_FULL_RNA_CONTEXT_BINDING_MISMATCH";
        hashes.validateCompleteContext(context);
        String parentIssue = TruncatingNmdPolicy.issue(context, policy, selection, model, hashes);
        if (parentIssue != null) return parentIssue;
        if (!SCHEMA.equals(policy.path("schemaVersion").asText())) return "PVS1_FULL_RNA_POLICY_SCOPE_REQUIRED";
        var original = source.original();var assessment = original.path("pvs1Assessment");
        if (!only(assessment, Set.of("method", "policySha256", "mechanicalEffectSha256", "parentReview", "inFrameReview", "initiationReview"))
                || !"SOURCE_BOUND_FULL_RNA_PVS1".equals(assessment.path("method").asText())) return "PVS1_FULL_RNA_EFFECT_REVIEW_REQUIRED";
        if (!hashes.canonicalSha256(policy).equals(assessment.path("policySha256").asText())
                || !source.facts().mechanicalEffectSha256().equals(assessment.path("mechanicalEffectSha256").asText()))
            return "PVS1_FULL_RNA_EFFECT_REVIEW_BINDING_MISMATCH";
        var sources = policy.path("scientificSources").deepCopy();
        if (!(sources instanceof com.fasterxml.jackson.databind.node.ArrayNode sourceRecords)) return "PVS1_FULL_RNA_POLICY_SOURCE_INVALID";
        var originalSource = source.provenance().path("sourceRecord");
        for (var declared : sourceRecords) if (declared.path("sourceId").equals(originalSource.path("sourceId")))
            return "PVS1_FULL_RNA_SELF_REFERENTIAL_POLICY_SOURCE";
        sourceRecords.add(originalSource.deepCopy());var review = assessment.path("parentReview");
        if (!only(review, Set.of("status", "reviewId", "basis", "sourceIds", "maximumStrength", "functionalRescue", "outcomeMultiplicity", "residualNormalTranscript"))
                || !"REVIEWED_FOR_PVS1_SPLICE".equals(review.path("status").asText()) || !text(review.path("reviewId"))
                || !sourced(review, sourceRecords) || !contains(review.path("sourceIds"), originalSource.path("sourceId").asText())
                || !STRENGTHS.contains(review.path("maximumStrength").asText())) return "PVS1_FULL_RNA_PARENT_REVIEW_REQUIRED";
        var required = audit.putArray("conditionChecks");
        for (String field : List.of("assayQuality", "tissueRelevance", "alleleAttribution", "residualNormalTranscript", "outcomeMultiplicity")) {
            String wanted = switch (field) { case "residualNormalTranscript" -> "NOT_DETECTED_IN_ASSESSED_SCOPE";
                case "outcomeMultiplicity" -> "SINGLE_SUPPORTED_OUTCOME";default -> "ASSESSED"; };
            var actual = original.at("/assay/" + field);required.addObject().put("condition",field).put("required",wanted)
                    .set("actual",actual.deepCopy());
            if (!wanted.equals(actual.asText())) return "PVS1_FULL_RNA_ASSAY_" + field.toUpperCase(java.util.Locale.ROOT) + "_UNRESOLVED";
        }
        if (!"SINGLE_SUPPORTED_OUTCOME".equals(review.path("outcomeMultiplicity").asText())) return "PVS1_FULL_RNA_MULTIPLE_OUTCOMES_UNRESOLVED";
        if (source.facts().nativeStartRetained() && !"EXCLUDED_IN_REVIEWED_SCOPE".equals(review.path("functionalRescue").asText()))
            return "PVS1_FULL_RNA_FUNCTIONAL_RESCUE_UNRESOLVED";
        if (!"NOT_DETECTED_IN_ASSESSED_SCOPE".equals(review.path("residualNormalTranscript").asText())) return "PVS1_FULL_RNA_RESIDUAL_TRANSCRIPT_UNRESOLVED";
        input.set("fullRnaFacts", sequence);input.set("fullRnaSource", source.provenance());
        audit.put("sourceOriginalSha256",source.originalSha256()).put("factsSha256",source.facts().canonicalSha256())
                .put("mechanicalEffectSha256",source.facts().mechanicalEffectSha256())
                .put("maximumStrength",lower(policy.at("/spliceScope/maximumStrength").asText(),review.path("maximumStrength").asText()));
        if (!source.facts().nativeStartRetained()) {
            if(policy.at("/spliceScope/initiationScope").isMissingNode()) return "PVS1_FULL_RNA_INITIATION_MECHANISM_UNRESOLVED";
            String initiationIssue=Pvs1RnaInitiation.evaluate(context,policy,selection,source,input);
            var initiation=input.path("fullRnaInitiationAssessment");
            boolean completedRescue = "NOT_APPLICABLE".equals(initiation.path("status").asText());
            if (initiationIssue != null && !completedRescue) return initiationIssue;
            String positionIssue = fullRnaPositionIssue(policy, source, input);
            if (positionIssue != null) return positionIssue;
            if (completedRescue) {
                audit.put("status", "NOT_APPLICABLE");
                return initiationIssue;
            }
            return completeFullRna(policy,audit,initiation.path("strength").asText());
        }
        if (!sequence.path("alteredProteinLength").isIntegralNumber()) return "PVS1_FULL_RNA_TERMINATION_UNRESOLVED";
        String ref = sequence.at("/codingEffects/referenceProteinThroughTermination").asText();
        String alt = sequence.path("alteredProteinThroughFirstTermination").asText();
        if (ref.equals(alt)) { audit.put("status","NOT_APPLICABLE");return "PVS1_FULL_RNA_PROTEIN_UNCHANGED"; }
        if (sequence.at("/codingEffects/localInFrameLengthChangeResolved").asBoolean()) {
            var fact = mapper.createObjectNode();fact.set("sourceRecords",sourceRecords);fact.set("review",review.deepCopy());
            fact.set("inFrameReview",assessment.path("inFrameReview").deepCopy());
            String inFrameIssue = Pvs1SpliceInFrame.evaluateFullRna(policy,model,fact,selection,source,input);
            if (inFrameIssue != null) { if (Pvs1SpliceInFrame.negative(inFrameIssue)) audit.put("status","NOT_MET");return inFrameIssue; }
            String positionIssue = fullRnaPositionIssue(policy,source,input);
            if (positionIssue != null) return positionIssue;
            return completeFullRna(policy,audit,input.at("/inFrameAssessment/strength").asText());
        }
        String referencePeptide = ref.substring(0, ref.length() - 1), alteredPeptide = alt.substring(0, alt.length() - 1);
        if (alteredPeptide.length() > referencePeptide.length() && alteredPeptide.startsWith(referencePeptide)) {
            audit.put("status","NOT_APPLICABLE");return "PVS1_FULL_RNA_NON_TRUNCATING_EXTENSION_REQUIRES_INDEPENDENT_EVIDENCE";
        }
        int referenceCdsEndAfterEdit = source.result().mapReferenceRnaBoundary(material.cdsEndRna1(), CodingTranscriptEdits.Bias.BEFORE);
        int nativeStopStart = sequence.at("/nativeStop/alteredStartRna1").asInt(-1);
        if (nativeStopStart < 1 && referenceCdsEndAfterEdit < 0) return "PVS1_FULL_RNA_CDS_UTR_BOUNDARY_UNRESOLVED";
        boolean premature = nativeStopStart > 0 ? sequence.at("/firstStop/alteredStartRna1").asInt() < nativeStopStart
                : sequence.at("/firstStop/alteredEndRna1").asInt() < referenceCdsEndAfterEdit;
        audit.put("prematureTerminationBeforeNativeCodingEnd", premature);
        if (!premature) {
            audit.put("status", "NOT_APPLICABLE");return "PVS1_FULL_RNA_NON_PREMATURE_TERMINATION_REQUIRES_INDEPENDENT_EVIDENCE";
        }
        var topology = source.topology();
        if (topology.completeness() != Pvs1SpliceTopology.RnaTopologyCompleteness.COMPLETE) return "PVS1_FULL_RNA_TOPOLOGY_UNRESOLVED";
        int origin0 = sequence.path("alteredStartRna1").asInt() - 1;
        int stopStart = sequence.at("/firstStop/alteredStartRna1").asInt(), stopEnd = sequence.at("/firstStop/alteredEndRna1").asInt();
        var ptc = topology.classifyTriplet(stopStart,stopEnd);int first = 0;
        while (first < Math.min(ref.length(),alt.length()) && ref.charAt(first) == alt.charAt(first)) first++;
        input.put("variantMechanism","SOURCE_BOUND_FULL_RNA_EVENT_SET").put("coordinateSystem","ALTERED_CODING_ORIGIN_1_BASED")
                .put("firstChangedProteinPosition",first+1).put("proteinPosition",first+1)
                .put("ptcStart",stopStart-origin0).put("ptcEnd",stopEnd-origin0)
                .put("alteredExonTotal",topology.exons().size()).put("ptcAlteredExonOrdinal",ptc.exons().get(ptc.exons().size()-1).alteredOrdinal())
                .put("alteredProteinThroughTermination",alt).put("sequenceDerivationStatus","SOURCE_BOUND_SPLICE_EDIT_AND_TRANSCRIPT_SEQUENCE_AGREE");
        Integer junction = topology.lastJunctionRnaPosition0();
        if (junction == null) input.putNull("lastJunctionCdsPosition").putNull("distanceToLastJunction");
        else input.put("lastJunctionCdsPosition",junction-origin0).put("distanceToLastJunction",junction-stopEnd);
        String positionIssue = fullRnaPositionIssue(policy,source,input);if (positionIssue != null) return positionIssue;
        var footprint = fullRnaCodingFootprint(source);var ordinals = new java.util.LinkedHashSet<Integer>();
        for (var interval : footprint) for (var exon : model.path("exons"))
            if (exon.path("cdsStart").asInt() <= interval[1] && exon.path("cdsEnd").asInt() >= interval[0]) ordinals.add(exon.path("ordinal").asInt());
        for (var carrier : ptc.exons()) for (int ordinal : carrier.referenceOrdinals()) ordinals.add(ordinal);
        if (footprint.isEmpty()) return "PVS1_FULL_RNA_CODING_EVENT_SCOPE_UNRESOLVED";
        for (var interval : footprint) if (interval[0] < policy.at("/nmdScope/minimumCdsPosition").asInt()) return "PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE";
        var touched = audit.putArray("affectedReferenceExonOrdinals");ordinals.forEach(touched::add);
        boolean nmd = junction != null && junction-stopEnd >= policy.at("/nmdScope/minimumDistanceToLastJunction").asInt();
        if (nmd && junction != null) {
            if ("EXCLUDE_ABOVE_MAXIMUM".equals(policy.at("/nmdScope/longExonScope").asText()))
                for (var exon : ptc.exons()) if (exon.alteredEnd0()-exon.alteredStart0() > policy.at("/nmdScope/maximumExonLength").asInt()) return "PVS1_LONG_EXON_BRANCH_NOT_IMPLEMENTED";
            for (int ordinal : ordinals) {
                if (!TruncatingNmdPolicy.contains(policy.at("/nmdScope/relevantExonOrdinals"),ordinal)) return "PVS1_EXON_RELEVANCE_UNRESOLVED";
                if (!TruncatingNmdPolicy.contains(policy.at("/nmdScope/engineeringSupportedExonOrdinals"),ordinal)) return "PVS1_ENGINEERING_EXON_SCOPE_NOT_IMPLEMENTED";
            }
            if (referenceCdsEndAfterEdit < 0) return "PVS1_FULL_RNA_CDS_UTR_BOUNDARY_UNRESOLVED";
            boolean utr = junction >= referenceCdsEndAfterEdit;
            if (utr && !"REVIEWED_FOR_3_PRIME_UTR_JUNCTION".equals(policy.at("/nmdScope/utrJunctionApplicability/status").asText()))
                return "PVS1_UTR_JUNCTION_APPLICABILITY_UNRESOLVED";
            input.put("nmdPrediction","PREDICTED_NMD");return completeFullRna(policy,audit,"VERY_STRONG");
        }
        for (var exon:model.path("exons")) if(exon.path("cdsEnd").asInt()>=first*3+1 && exon.path("cdsStart").asInt()<=model.path("proteinLength").asInt()*3)
            ordinals.add(exon.path("ordinal").asInt());
        touched.removeAll();ordinals.forEach(touched::add);
        String exonIssue=fullRnaEscapeExonIssue(policy,ordinals,audit);
        if(exonIssue!=null) {if(Pvs1NmdEscape.negative(exonIssue))audit.put("status","NOT_MET");return exonIssue;}
        String escapeIssue = Pvs1NmdEscape.evaluate(policy,model,input,selection);
        input.put("sequenceDerivationStatus","SOURCE_BOUND_FULL_RNA_EVENT_PRODUCT_AND_TRANSLATION_AGREE");
        if (escapeIssue != null) { if (Pvs1NmdEscape.negative(escapeIssue)) audit.put("status","NOT_MET");return escapeIssue; }
        // Preserve the checked union separately from the old tail-only operator output.
        if(input.path("escapeAssessment") instanceof ObjectNode escape) {
            escape.set("affectedReferenceExonOrdinals",touched.deepCopy());escape.set("exonChecks",audit.path("escapeExonChecks").deepCopy());
        }
        audit.set("escapePhysicalEventExons",touched.deepCopy());
        return completeFullRna(policy,audit,input.at("/escapeAssessment/selectedRule/strength").asText());
    }

    private static String fullRnaEscapeExonIssue(ObjectNode policy,java.util.Set<Integer> ordinals,ObjectNode audit) {
        if(policy.path("escapeScope").isMissingNode()) return "PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED";
        var checks=audit.putArray("escapeExonChecks");int absent=0,frequent=0;
        for(int ordinal:ordinals) {
            JsonNode fact=null;for(var row:policy.at("/escapeScope/exonAssessments"))if(row.path("ordinal").asInt()==ordinal)fact=row;
            if(fact==null)return "PVS1_ESCAPE_EXON_ASSESSMENT_MISSING";checks.add(fact.deepCopy());
            String relevance=fact.path("biologicalRelevance").asText(),population=fact.path("populationLof").asText();
            boolean positive=TruncatingNmdPolicy.contains(policy.at("/nmdScope/relevantExonOrdinals"),ordinal);
            if("ABSENT".equals(relevance)) {if(positive)return "PVS1_ESCAPE_EXON_FACTS_CONFLICT";absent++;}
            if("UNKNOWN".equals(relevance)||"PRESENT".equals(relevance)&&!positive)return "PVS1_ESCAPE_EXON_RELEVANCE_UNRESOLVED";
            if("UNKNOWN".equals(population))return "PVS1_ESCAPE_POPULATION_TOLERANCE_UNRESOLVED";
            if("FREQUENT".equals(population))frequent++;
        }
        if(checks.isEmpty())return "PVS1_ESCAPE_EXON_ASSESSMENT_MISSING";
        if(absent==checks.size())return "PVS1_ESCAPE_EXON_NOT_RELEVANT";
        if(frequent==checks.size())return "PVS1_ESCAPE_EXON_LOF_TOLERANT";
        return absent>0||frequent>0?"PVS1_ESCAPE_EXON_FACTS_CONFLICT":null;
    }

    private static String completeFullRna(ObjectNode policy,ObjectNode audit,String strength) {
        if (!STRENGTHS.contains(strength)) return "PVS1_FULL_RNA_STRENGTH_UNRESOLVED";
        audit.put("status","MET").put("adoptedStrength",lower(lower(strength,audit.path("maximumStrength").asText()),policy.path("appliedStrength").asText()));return null;
    }
    static List<int[]> fullRnaCodingFootprint(Pvs1SourceQualification.SourceBoundFullRna source) {
        var material=source.result().reference().material();int offset=material.cdsStartRna1()-1, end=material.cdsEndRna1();var spans=new java.util.ArrayList<int[]>();
        for (var edit:source.result().appliedEdits()) {
            int a=edit.referenceEditStart0(),b=edit.referenceEditEnd0();
            if(a==b) { if(a>offset && a<end) spans.add(new int[]{a-offset,a-offset+1}); }
            else { int lo=Math.max(a,offset),hi=Math.min(b,end);if(lo<hi) spans.add(new int[]{lo-offset+1,hi-offset}); }
        }
        return spans;
    }
    private static String fullRnaPositionIssue(ObjectNode policy,Pvs1SourceQualification.SourceBoundFullRna source,ObjectNode input) {
        var conditions=policy.at("/nmdScope/positionConditions");if(conditions.isMissingNode()) return null;
        var checks=input.putArray("positionConditionChecks");var sequence=source.facts().snapshot();
        // Only the current, already-classified premature termination supplies PTC coordinates.
        // Native termination after a local in-frame deletion is not a PTC.
        boolean premature=input.path("ptcStart").isIntegralNumber() && input.path("ptcEnd").isIntegralNumber();
        for(var condition:conditions) {
            var values=new java.util.ArrayList<Integer>();String coordinate=condition.path("coordinate").asText();
            if("REFERENCE_EVENT_SPAN".equals(coordinate)) for(var span:fullRnaCodingFootprint(source)){values.add(span[0]);values.add(span[1]);}
            else if("REFERENCE_PTC_SPAN".equals(coordinate) && premature) for(var base:sequence.at("/firstStop/bases")) {
                if(!positive(base.path("referenceCdsPosition1"))) {values.clear();break;}values.add(base.path("referenceCdsPosition1").asInt());
            } else if("ALTERED_PTC_SPAN".equals(coordinate) && premature && sequence.at("/firstStop/alteredStartFromNativeCodingOrigin1").isIntegralNumber()) {
                int start=sequence.at("/firstStop/alteredStartFromNativeCodingOrigin1").asInt();values.add(start);values.add(start+2);
            }
            var row=checks.addObject();row.set("condition",condition.deepCopy());var actual=row.putArray("checkedPositions");values.forEach(actual::add);
            if(values.isEmpty()) {row.put("status","UNRESOLVED");return "PVS1_POLICY_POSITION_UNRESOLVED";}
            for(int value:values) if(condition.has("minimumInclusive") && value<condition.path("minimumInclusive").asInt()
                    || condition.has("maximumExclusive") && value>=condition.path("maximumExclusive").asInt()) {
                row.put("status","OUTSIDE_SUPPORTED_RANGE");return "PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE";
            }
            row.put("status","PASS");
        }
        return null;
    }

    static String policyIssue(JsonNode policy) {
        var scope = policy.path("spliceScope");
        if (!SCHEMA.equals(policy.path("schemaVersion").asText()))
            return scope.isMissingNode() ? null : "PVS1_SPLICE_POLICY_INVALID";
        if (!only(scope, Set.of("status", "reviewId", "basis", "sourceIds", "maximumStrength", "evidenceKinds", "eventTypes", "inFrameScope", "initiationScope"))
                || !"REVIEWED_FOR_PVS1_SPLICE".equals(scope.path("status").asText())
                || !text(scope.path("reviewId")) || !sourced(scope, policy.path("scientificSources"))
                || !STRENGTHS.contains(scope.path("maximumStrength").asText())
                || !subset(scope.path("evidenceKinds"), KINDS) || !subset(scope.path("eventTypes"), EVENTS))
            return "PVS1_SPLICE_SCOPE_NOT_REVIEWED";
        return Pvs1RnaInitiation.policyIssue(policy);
    }

    static JsonNode select(JsonNode records, JsonNode context, JsonNode model, RuntimeEvidenceV2Adapter adapter) {
        if (records.isMissingNode() || records.isNull()) return records;
        if (!records.isArray() || records.size() > 1024) throw new IllegalArgumentException("PVS1_SPLICE_MATERIAL_INVALID");
        JsonNode found = com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        for (var r : records) {
            if (!r.isObject()) throw new IllegalArgumentException("PVS1_SPLICE_MATERIAL_INVALID");
            boolean alleleMatch = true;
            for (String f : List.of("assembly", "contig", "position", "reference", "alternate"))
                alleleMatch &= r.path("allele").path(f).equals(context.at("/variant/evidenceQueryAllele").path(f));
            if (!alleleMatch || !r.path("hgncId").equals(context.at("/gene/hgncId"))
                    || !r.path("mondoId").equals(context.at("/disease/mondoId")) || !r.path("moi").equals(context.path("moi"))
                    || !r.path("transcript").equals(model.path("transcript"))) continue;
            if (!found.isMissingNode()) throw new IllegalArgumentException("PVS1_SPLICE_OUTCOMES_AMBIGUOUS");
            found = r;
        }
        if (!found.isMissingNode() && !adapter.canonicalSha256(model).equals(found.path("transcriptModelSha256").asText()))
            throw new IllegalArgumentException("PVS1_SPLICE_MODEL_IDENTITY_MISMATCH");
        return found;
    }

    /** Compatibility entry: new scientific paths stay blocked until their public
     * Runtime adapter consumes the distinct method and altered topology. */
    static String resolve(JsonNode context, TranscriptConsequence tx, ObjectNode policy, JsonNode model,
                          JsonNode fact, ObjectNode input, JsonNode selection) {
        String issue = resolveForIntegration(context, tx, policy, model, fact, input, selection);
        if (input.at("/spliceAssessment/requiresRuntimeAdapter").asBoolean()
                && (issue == null || Pvs1SpliceInFrame.negative(issue) || Pvs1NmdEscape.negative(issue)))
            return "PVS1_SPLICE_RUNTIME_ADAPTER_REQUIRED";
        return issue;
    }

    /** Module entry for pinned, selected source material. No source qualification
     * is created here; the same review/identity gates remain mandatory. */
    static String resolveForIntegration(JsonNode context, TranscriptConsequence tx, ObjectNode policy, JsonNode model,
                          JsonNode fact, ObjectNode input, JsonNode selection) {
        String issue = resolveModule(context, tx, policy, model, fact, input, selection);
        if (input.path("spliceAssessment") instanceof ObjectNode audit && issue != null) {
            if (!"NOT_MET".equals(audit.path("status").asText())) audit.put("status", "NOT_EVALUATED");
            audit.put("reasonCode", issue);
        }
        return issue;
    }

    private static String resolveModule(JsonNode context, TranscriptConsequence tx, ObjectNode policy, JsonNode model,
                          JsonNode fact, ObjectNode input, JsonNode selection) {
        clearDerived(input);
        String policyIssue = policyIssue(policy);
        if (policyIssue != null) return policyIssue;
        if (!SCHEMA.equals(policy.path("schemaVersion").asText())) return "PVS1_SPLICE_BRANCH_NOT_IMPLEMENTED";
        if (fact.isMissingNode() || fact.isNull()) return "PVS1_SPLICE_OUTCOME_MATERIAL_REQUIRED";
        if (!only(fact, Set.of("schemaVersion", "outcomeId", "evidenceKind", "allele", "hgncId", "mondoId", "moi",
                "transcript", "transcriptModelSha256", "review", "event", "referenceWindow", "assessment", "sourceRecords", "inFrameReview", "codingEditComposition"))
                || !"pvs1-splice-outcome-v1".equals(fact.path("schemaVersion").asText()) || !text(fact.path("outcomeId"))
                || !CodingTranscriptMapping.sourcesValid(fact.path("sourceRecords"))) return "PVS1_SPLICE_MATERIAL_INVALID";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        if (!(context instanceof ObjectNode contextObject) || !(model instanceof ObjectNode modelObject)
                || !(selection instanceof ObjectNode selectionObject)) return "PVS1_SPLICE_MODULE_INPUT_INVALID";
        try {
            adapter.validateCompleteContext(contextObject);
            String parentIssue = TruncatingNmdPolicy.issue(contextObject, policy, selectionObject, modelObject, adapter);
            if (parentIssue != null) return parentIssue;
        } catch (IllegalArgumentException invalid) { return "PVS1_SPLICE_MODULE_CONTEXT_OR_POLICY_INVALID"; }
        try {
            if (select(mapper.createArrayNode().add(fact), context, model, adapter).isMissingNode())
                return "PVS1_SPLICE_OUTCOME_SCOPE_MISMATCH";
        } catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
        String modelIssue = TruncatingPvs1Model.issue(model, context);
        if (modelIssue != null) return modelIssue;
        String inFramePolicyIssue = Pvs1SpliceInFrame.policyIssue(policy, model);
        if (inFramePolicyIssue != null) return inFramePolicyIssue;
        input.put("transcriptModelSha256", adapter.canonicalSha256(model)).put("spliceMaterialSha256", adapter.canonicalSha256(fact));
        for (var source : fact.path("sourceRecords")) for (var declared : policy.path("scientificSources"))
            if (source.path("sourceId").equals(declared.path("sourceId")) && !source.equals(declared))
                return "PVS1_SPLICE_SOURCE_IDENTITY_CONFLICT";
        var review = fact.path("review");var assessment = fact.path("assessment");var scope = policy.path("spliceScope");
        if (!only(review, Set.of("status", "reviewId", "basis", "sourceIds", "maximumStrength", "functionalRescue",
                "outcomeMultiplicity", "residualNormalTranscript")) || !sourced(review, fact.path("sourceRecords"))
                || !"REVIEWED_FOR_PVS1_SPLICE".equals(review.path("status").asText()) || !text(review.path("reviewId"))
                || !STRENGTHS.contains(review.path("maximumStrength").asText())) return "PVS1_SPLICE_OUTCOME_NOT_REVIEWED";
        if (!"SINGLE_SUPPORTED_OUTCOME".equals(review.path("outcomeMultiplicity").asText())) return "PVS1_SPLICE_OUTCOME_UNRESOLVED";
        if (!"EXCLUDED_IN_REVIEWED_SCOPE".equals(review.path("functionalRescue").asText())) return "PVS1_SPLICE_FUNCTIONAL_RESCUE_UNRESOLVED";
        String kind = fact.path("evidenceKind").asText();
        if (!KINDS.contains(kind) || !contains(scope.path("evidenceKinds"), kind)) return "PVS1_SPLICE_EVIDENCE_KIND_NOT_SUPPORTED";
        boolean predicted = "PREDICTED_CANONICAL".equals(kind);
        if (!only(assessment, Set.of("status", "basis", "sourceIds", "tissueRelevance", "alleleAttribution", "assayQuality",
                "nearbyRescueAssessment", "alternativeOutcomesAssessment")) || !sourced(assessment, fact.path("sourceRecords"))
                || !(predicted ? "QUALIFIED_CANONICAL_PREDICTION" : "QUALIFIED_RNA_ASSAY").equals(assessment.path("status").asText()))
            return "PVS1_SPLICE_EVIDENCE_QUALIFICATION_REQUIRED";
        for (String f : predicted ? List.of("nearbyRescueAssessment", "alternativeOutcomesAssessment")
                : List.of("tissueRelevance", "alleleAttribution", "assayQuality", "alternativeOutcomesAssessment"))
            if (!"ASSESSED".equals(assessment.path(f).asText())) return "PVS1_SPLICE_EVIDENCE_QUALIFICATION_REQUIRED";
        if (!residualTranscriptCompatible(review, predicted))
            return "PVS1_SPLICE_RESIDUAL_TRANSCRIPT_UNRESOLVED";
        var event = fact.path("event");String type = event.path("type").asText();
        if (!only(event, Set.of("type", "exonOrdinal", "deleteStartCds", "deleteEndCds"))
                || !EVENTS.contains(type) || !contains(scope.path("eventTypes"), type)) return "PVS1_SPLICE_EVENT_NOT_SUPPORTED";
        for (String f : List.of("exonOrdinal", "deleteStartCds", "deleteEndCds"))
            if (!positive(event.path(f))) return "PVS1_SPLICE_EVENT_INVALID";
        var exons = model.path("exons");int ordinal = event.path("exonOrdinal").asInt();
        if (ordinal < 1 || ordinal > exons.size()) return "PVS1_SPLICE_TERMINAL_EXON_NOT_SUPPORTED";
        var exon = exons.path(ordinal - 1);String cds = model.path("cdsSequence").asText();
        int lo = event.path("deleteStartCds").asInt(), hi = event.path("deleteEndCds").asInt();
        if (hi < lo || hi > cds.length() || hi - lo >= 100000
                || lo < Math.max(1, exon.path("cdsStart").asInt()) || hi > Math.min(cds.length(), exon.path("cdsEnd").asInt()))
            return "PVS1_SPLICE_EVENT_INVALID";
        boolean whole = lo == Math.max(1, exon.path("cdsStart").asInt()) && hi == Math.min(cds.length(), exon.path("cdsEnd").asInt());
        if ("EXON_SKIPPING".equals(type) != whole || !whole && lo != exon.path("cdsStart").asInt() && hi != exon.path("cdsEnd").asInt())
            return "PVS1_SPLICE_EVENT_MODEL_MISMATCH";
        if (lo <= 3) return "PVS1_SPLICE_START_REMOVAL_BRANCH_NOT_IMPLEMENTED";
        if (hi > cds.length() - 3) return "PVS1_SPLICE_TERMINAL_EXTENSION_BRANCH_NOT_IMPLEMENTED";
        String refIssue = referenceIssue(context, fact.path("referenceWindow"));
        if (refIssue != null) return refIssue;
        if (predicted) {
            String issue = canonicalIssue(context, tx, model, exon, fact.path("referenceWindow"), event);
            if (issue != null) return issue;
        }
        var allele = context.at("/variant/evidenceQueryAllele");
        String ref = allele.path("reference").asText(), alt = allele.path("alternate").asText();
        boolean simple = ref.length() == 1 && alt.length() == 1
                || alt.length() == 1 && ref.startsWith(alt) || ref.length() == 1 && alt.startsWith(ref);
        if (!simple && !TruncatingNmdPolicy.complexAllelesEnabled(policy))
            return "PVS1_SPLICE_COMPLEX_ALLELE_POLICY_SCOPE_REQUIRED";
        if (!simple && Math.max(ref.length(), alt.length()) > policy.at("/codingEditScope/maximumAlleleLengthIncludingAnchor").asInt())
            return "PVS1_SPLICE_COMPLEX_ALLELE_OUTSIDE_POLICY_SCOPE";
        var audit = input.putObject("spliceAssessment").put("schemaVersion", "pvs1-splice-assessment-v1")
                .put("methodVersion", MODULE_VERSION).put("status", "NOT_EVALUATED")
                .put("outcomeId", fact.path("outcomeId").asText()).put("evidenceKind", kind).put("reviewId", review.path("reviewId").asText())
                .put("maximumStrength", lower(scope.path("maximumStrength").asText(), review.path("maximumStrength").asText()))
                .put("interpretation", predicted ? "PREDICTED_OUTCOME_NOT_OBSERVED_RNA" : "SOURCE_REVIEWED_RNA_OUTCOME")
                .put("doubleCountingConstraint", "DO_NOT_COMBINE_WITH_PP3_OR_PS3_FOR_THE_SAME_SPLICE_EFFECT");
        audit.set("event", event.deepCopy());audit.set("sourceRecords", fact.path("sourceRecords").deepCopy());
        int start = lo - 1, end = hi, deleted = hi - lo + 1;
        audit.put("rnaReadingFrame", deleted % 3 == 0 ? "PRESERVED" : "ALTERED");
        Pvs1SpliceComposition.Composition composition;
        try { composition = Pvs1SpliceComposition.compose(mapper, context, model, fact, start, end); }
        catch (CodingTranscriptEdits.Rejected rejected) {
            audit.set("codingEditRejection", rejected.snapshot(mapper));return rejected.reasonCode();
        } catch (IllegalArgumentException invalid) {
            if ("PVS1_SPLICE_RETAINED_CODING_ALLELE_NOT_SUPPORTED".equals(invalid.getMessage()))
                audit.putObject("retainedCodingAlleleAssessment").put("requiresReviewedComposition", true)
                        .put("status", "RETAINED_CODING_EDIT_COMPOSITION_REVIEW_REQUIRED");
            return invalid.getMessage();
        }
        var edited = composition.result();
        String changed = edited.alteredCds();
        input.set("codingEditComposition", composition.snapshot());
        audit.put("compositionStatus", composition.snapshot().path("compositionStatus").asText())
                .put("combinedCdsReadingFrame", edited.delta() % 3 == 0 ? "PRESERVED" : "ALTERED")
                .put("retainedCodingEdit", composition.retainedCodingEdit())
                .put("rnaReferenceEditStart0", start).put("rnaReferenceEditEnd0", end);
        audit.putObject("retainedCodingAlleleAssessment").put("requiresEditCore", false)
                .put("editCoreConsumed", true).put("retainedCodingEdit", composition.retainedCodingEdit());
        input.put("variantMechanism", "SPLICE_CDS_DELETION").put("coordinateSystem", "ALTERED_CDS")
                .put("selectedTranscript", model.path("transcript").asText()).put("cdsPosition", lo)
                .put("referenceEditStart0", composition.referenceEventStart0()).put("referenceEditEnd0", composition.referenceEventEnd0())
                .put("referenceEventSpanMeaning", "BOUNDING_SPAN_OF_RNA_DELETION_AND_APPLIED_CODING_EDIT_WITH_INSERTION_FLANKS")
                .put("netLengthChange", edited.delta())
                .put("exonOrdinal", ordinal).put("exonTotal", exons.size()).put("referenceExonTotal", exons.size())
                .put("referenceCdsSha256", sha(cds)).put("alteredCdsSequence", changed).put("alteredCdsLength", changed.length())
                .put("alteredCdsSha256", sha(changed));
        Pvs1SpliceTopology.Projection topology;
        try { topology = Pvs1SpliceTopology.project(model, start, end, edited, whole ? ordinal : null); }
        catch (IllegalArgumentException invalid) { return "PVS1_SPLICE_TOPOLOGY_INVALID"; }
        describeTopology(model, input, audit, topology);
        boolean newAlleleScope = allele.path("reference").asText().length() != 1 || allele.path("alternate").asText().length() != 1;
        boolean extended = TruncatingPvs1Model.extendedTopology(model) || ordinal == 1 || ordinal == exons.size()
                || topology.singleExon() || newAlleleScope;
        boolean compositionAdapter = composition.explicitlyReviewed() || composition.retainedCodingEdit();
        audit.put("requiresRuntimeAdapter", extended || deleted % 3 == 0 || compositionAdapter);
        String referenceProtein = translate(cds), protein = translate(changed);int stop = protein.indexOf('*');
        int nativeStop = composition.nativeStopStart1();
        // A codon-phase-preserving RNA deletion can create a junction STOP.
        // Classify the actual translated sequence before choosing the regional branch.
        if (edited.delta() % 3 == 0 && (stop < 0 || stop * 3 + 1 >= nativeStop)) {
            String issue;
            if (composition.retainedCodingEdit()) {
                var boundReference = CodingTranscriptEdits.bind(mapper, context, model,
                        adapter.canonicalSha256(model), sha(cds));
                var effects = CodingConsequenceFacts.derive(mapper, boundReference, edited);
                input.set("codingConsequenceFacts", effects.snapshot());
                audit.put("methodVersion", COMPOSITE_MODULE_VERSION).put("compositeInFrameEffect", true)
                        .put("mechanicalEffectSha256", effects.mechanicalEffectSha256())
                        .put("codingConsequenceFactsSha256", effects.canonicalSha256());
                issue = Pvs1SpliceInFrame.evaluateComposite(context, policy, model, fact, input, selection, effects);
            } else issue = Pvs1SpliceInFrame.evaluate(policy, model, fact, input, selection);
            if (issue == null) {
                String conditionIssue = Pvs1NmdConditions.evaluate(policy, input);
                if (conditionIssue != null) return conditionIssue;
                return completeModule(policy, input, input.at("/inFrameAssessment/strength").asText());
            }
            if (Pvs1SpliceInFrame.negative(issue)) audit.put("status", "NOT_MET").put("reasonCode", issue);
            return issue;
        }
        if (stop < 0) return "PVS1_TERMINATION_UNRESOLVED";
        int ptcStart = stop * 3 + 1, ptcEnd = ptcStart + 2;
        if (ptcStart >= nativeStop) return "PVS1_PTC_NOT_PREMATURE";
        int first = 0;while (first < Math.min(referenceProtein.length(), protein.length()) && referenceProtein.charAt(first) == protein.charAt(first)) first++;
        if (first > stop || first >= referenceProtein.length() - 1) return "PVS1_PTC_NOT_PREMATURE";
        Pvs1SpliceTopology.PtcClassification ptc;
        try { ptc = topology.classifyPtc(ptcStart, ptcEnd); }
        catch (IllegalArgumentException invalid) { return "PVS1_SPLICE_PTC_JUNCTION_UNRESOLVED"; }
        audit.put("requiresRuntimeAdapter", extended || ptc.crossesJunction() || deleted % 3 == 0 || compositionAdapter);
        audit.put("ptcCrossesJunction", ptc.crossesJunction());
        var carriers = audit.putArray("ptcExons");
        for (var e : ptc.exons()) carriers.addObject().put("referenceOrdinal", e.referenceOrdinal()).put("alteredOrdinal", e.alteredOrdinal());
        input.put("firstChangedProteinPosition", first + 1).put("proteinPosition", first + 1)
                .put("ptcStart", ptcStart).put("ptcEnd", ptcEnd).put("ptcExonOrdinal", ptc.endExon().referenceOrdinal())
                .put("ptcAlteredExonOrdinal", ptc.endExon().alteredOrdinal())
                .put("alteredProteinThroughTermination", protein.substring(0, stop + 1))
                .put("ptcReferenceStart", edited.alteredToReferenceBase(ptcStart))
                .put("ptcReferenceMiddle", edited.alteredToReferenceBase(ptcStart + 1))
                .put("ptcReferenceEnd", edited.alteredToReferenceBase(ptcEnd))
                .put("sequenceDerivationStatus", "SOURCE_BOUND_SPLICE_EDIT_AND_TRANSCRIPT_SEQUENCE_AGREE");
        int mapped = 0;
        for (int p = ptcStart; p <= ptcEnd; p++) if (edited.alteredToReferenceBase(p) > 0) mapped++;
        input.put("ptcReferenceMapping", mapped == 3 ? "REFERENCE_BASES" : mapped == 0 ? "NO_REFERENCE_BASES" : "MIXED_REFERENCE_AND_INSERTED_BASES");
        if (mapped != 3) input.put("ptcReferenceMappingReason", "INSERTED_OR_REPLACEMENT_BASES_HAVE_NO_REFERENCE_IDENTITY");
        if (!TruncatingNmdPolicy.contains(policy.at("/nmdScope/relevantExonOrdinals"), ordinal)) return "PVS1_EXON_RELEVANCE_UNRESOLVED";
        if (composition.referenceEventStart0() + 1 < policy.at("/nmdScope/minimumCdsPosition").asInt())
            return "PVS1_START_PROXIMAL_BRANCH_NOT_IMPLEMENTED";
        Integer junction = topology.lastJunctionCdsPosition();
        if (junction != null) input.put("distanceToLastJunction", junction - ptcEnd);
        if (junction != null && junction >= changed.length()
                && !policy.at("/nmdScope/utrJunctionApplicability").isObject()) return "PVS1_UTR_JUNCTION_APPLICABILITY_UNRESOLVED";
        if (junction == null || junction - ptcEnd < policy.at("/nmdScope/minimumDistanceToLastJunction").asInt()) {
            String issue = Pvs1NmdEscape.evaluate(policy, model, input, selection);
            input.put("sequenceDerivationStatus", "SOURCE_BOUND_SPLICE_EDIT_AND_TRANSCRIPT_SEQUENCE_AGREE");
            if (issue != null) {
                if (Pvs1NmdEscape.negative(issue)) audit.put("status", "NOT_MET").put("reasonCode", issue);
                return issue;
            }
            return completeModule(policy, input, input.at("/escapeAssessment/selectedRule/strength").asText());
        }
        if ("EXCLUDE_ABOVE_MAXIMUM".equals(policy.at("/nmdScope/longExonScope").asText())
                && ptc.maximumExonLength() > policy.at("/nmdScope/maximumExonLength").asInt()) return "PVS1_LONG_EXON_BRANCH_NOT_IMPLEMENTED";
        for (var e : ptc.exons()) if (!TruncatingNmdPolicy.contains(policy.at("/nmdScope/relevantExonOrdinals"), e.referenceOrdinal()))
            return "PVS1_EXON_RELEVANCE_UNRESOLVED";
        var supported = policy.at("/nmdScope/engineeringSupportedExonOrdinals");
        if (!TruncatingNmdPolicy.contains(supported, ordinal)) return "PVS1_ENGINEERING_EXON_SCOPE_NOT_IMPLEMENTED";
        for (var e : ptc.exons()) if (!TruncatingNmdPolicy.contains(supported, e.referenceOrdinal()))
            return "PVS1_ENGINEERING_EXON_SCOPE_NOT_IMPLEMENTED";
        String conditionIssue = Pvs1NmdConditions.evaluate(policy, input);
        if (conditionIssue != null) return conditionIssue;
        input.put("nmdPrediction", "PREDICTED_NMD").put("exonRelevance", policy.at("/nmdScope/exonRelevance").asText())
                .put("mechanism", policy.at("/mechanism/mechanismId").asText());
        return completeModule(policy, input, "VERY_STRONG");
    }

    private static String completeModule(ObjectNode policy, ObjectNode input, String strength) {
        if (!STRENGTHS.contains(strength) || !STRENGTHS.contains(policy.path("appliedStrength").asText()))
            return "PVS1_SPLICE_MODULE_STRENGTH_UNRESOLVED";
        var audit = (ObjectNode) input.path("spliceAssessment");
        String adopted = lower(lower(strength, audit.path("maximumStrength").asText()), policy.path("appliedStrength").asText());
        audit.put("status", "MET").put("adoptedStrength", adopted);
        return null;
    }

    private static void describeTopology(JsonNode model, ObjectNode input, ObjectNode audit, Pvs1SpliceTopology.Projection topology) {
        var retained = audit.putArray("retainedExons");
        for (var e : topology.retainedExons()) retained.addObject().put("referenceOrdinal", e.referenceOrdinal()).put("alteredOrdinal", e.alteredOrdinal())
                .put("alteredCdsStart", e.alteredCdsStart()).put("alteredCdsEnd", e.alteredCdsEnd());
        var junctions = audit.putArray("alteredJunctions");
        for (var j : topology.junctions()) junctions.addObject().put("leftReferenceOrdinal", j.leftReferenceOrdinal())
                .put("rightReferenceOrdinal", j.rightReferenceOrdinal()).put("alteredCdsPosition", j.alteredCdsPosition());
        input.put("alteredExonTotal", topology.retainedExons().size());
        if (model.path("lastJunctionCdsPosition").isIntegralNumber())
            input.set("referenceLastJunctionCdsPosition", model.path("lastJunctionCdsPosition").deepCopy());
        else input.putNull("referenceLastJunctionCdsPosition");
        Integer junction = topology.lastJunctionCdsPosition();
        var altered = audit.putObject("alteredTopology").put("methodVersion", Pvs1SpliceTopology.VERSION)
                .put("exonCount", topology.retainedExons().size()).put("cdsLength", topology.alteredCdsLength())
                .put("kind", junction == null ? "SINGLE_EXON" : junction >= topology.alteredCdsLength()
                        ? "LAST_JUNCTION_IN_3_PRIME_UTR" : "MULTI_EXON_CODING_JUNCTION");
        var reference = audit.putObject("referenceTopology").put("exonCount", model.path("exons").size())
                .put("cdsLength", model.path("cdsLength").asInt()).put("scope", "REFERENCE_TRANSCRIPT");
        if (model.path("lastJunctionCdsPosition").isIntegralNumber())
            reference.set("lastJunctionCdsPosition", model.path("lastJunctionCdsPosition").deepCopy());
        else reference.putNull("lastJunctionCdsPosition");
        if (model.at("/sequenceVerification/fullSplicedTranscriptSha256").isTextual())
            reference.set("fullSplicedTranscriptSha256", model.at("/sequenceVerification/fullSplicedTranscriptSha256").deepCopy());
        input.set("referenceTranscriptTopology", reference.deepCopy());
        if (junction == null) {
            input.putNull("mutantLastJunctionCdsPosition").putNull("lastJunctionCdsPosition").putNull("distanceToLastJunction");
            altered.putNull("lastJunctionCdsPosition");
        } else {
            input.put("mutantLastJunctionCdsPosition", junction).put("lastJunctionCdsPosition", junction);
            altered.put("lastJunctionCdsPosition", junction);
        }
        input.set("transcriptTopology", altered.deepCopy().put("scope", "ALTERED_TRANSCRIPT_FROM_SOURCE_BOUND_SPLICE"));
    }

    private static void clearDerived(ObjectNode input) {
        input.remove(List.of("transcriptModelSha256", "spliceMaterialSha256", "spliceAssessment", "escapeAssessment", "inFrameAssessment", "codingEditComposition", "codingConsequenceFacts", "nmdPrediction", "variantMechanism",
                "coordinateSystem", "selectedTranscript", "cdsPosition", "referenceEditStart0", "referenceEditEnd0", "netLengthChange",
                "exonOrdinal", "exonTotal", "referenceExonTotal", "alteredExonTotal", "referenceCdsSha256", "alteredCdsSequence",
                "alteredCdsLength", "alteredCdsSha256", "firstChangedProteinPosition", "proteinPosition", "ptcStart", "ptcEnd",
                "ptcExonOrdinal", "ptcAlteredExonOrdinal", "referenceLastJunctionCdsPosition", "mutantLastJunctionCdsPosition",
                "lastJunctionCdsPosition", "distanceToLastJunction", "alteredProteinThroughTermination", "ptcReferenceStart",
                "ptcReferenceMiddle", "ptcReferenceEnd", "ptcReferenceMapping", "ptcReferenceMappingReason", "referenceEventSpanMeaning", "sequenceDerivationStatus",
                "exonRelevance", "mechanism", "scientificRelevantExonOrdinals", "engineeringSupportedExonOrdinals",
                "positionConditionChecks", "escapePositionConditionChecks", "transcriptTopology", "referenceTranscriptTopology"));
    }

    private static String referenceIssue(JsonNode context, JsonNode window) {
        if (!only(window, Set.of("start", "sequence")) || !positive(window.path("start"))
                || !window.path("sequence").asText().matches("[ACGT]{1,100000}")) return "PVS1_SPLICE_REFERENCE_WITNESS_INVALID";
        long offset = context.at("/variant/evidenceQueryAllele/position").asLong() - window.path("start").asLong();
        String sequence = window.path("sequence").asText(), ref = context.at("/variant/evidenceQueryAllele/reference").asText();
        if (offset < 0 || offset + ref.length() > sequence.length() || !sequence.substring((int) offset, (int) offset + ref.length()).equals(ref))
            return "PVS1_SPLICE_REFERENCE_MISMATCH";
        return null;
    }

    static String canonicalIssue(JsonNode context, TranscriptConsequence tx, JsonNode model, JsonNode exon, JsonNode window, JsonNode event) {
        var allele = context.at("/variant/evidenceQueryAllele");
        if (!allele.path("reference").asText().matches("[ACGT]") || !allele.path("alternate").asText().matches("[ACGT]"))
            return "PVS1_SPLICE_CANONICAL_SNV_REQUIRED";
        int strand = model.path("strand").asInt(), pos = allele.path("position").asInt();
        int donor = strand == 1 ? exon.path("genomicEnd").asInt() + 1 : exon.path("genomicStart").asInt() - 1;
        int acceptor = strand == 1 ? exon.path("genomicStart").asInt() - 1 : exon.path("genomicEnd").asInt() + 1;
        boolean isDonor = pos == donor || pos == donor + strand;
        boolean isAcceptor = pos == acceptor || pos == acceptor - strand;
        if (!isDonor && !isAcceptor) return "PVS1_SPLICE_CANONICAL_SITE_REQUIRED";
        String term = isDonor ? "splice_donor_variant" : "splice_acceptor_variant";
        String consequence = tx.getConsequence();
        if (consequence == null || !List.of(consequence.split("[&,|]")).contains(term)) return "PVS1_SPLICE_ANNOTATION_SITE_MISMATCH";
        int expectedC = isDonor ? exon.path("cdsEnd").asInt() : exon.path("cdsStart").asInt();
        int distance = Math.abs(pos - (isDonor ? donor : acceptor)) + 1;
        String ref = CodingTranscriptMapping.orientAllele(model, allele.path("reference").asText());
        String alt = CodingTranscriptMapping.orientAllele(model, allele.path("alternate").asText());
        String expected = model.path("transcript").asText() + ":c." + expectedC + (isDonor ? "+" : "-") + distance + ref + ">" + alt;
        if (!expected.equals(tx.getHgvsc())) return "PVS1_SPLICE_HGVSC_SITE_MISMATCH";
        int motifStart = isDonor ? Math.min(donor, donor + strand) : Math.min(acceptor, acceptor - strand);
        int off = motifStart - window.path("start").asInt();String sequence = window.path("sequence").asText();
        if (off < 0 || off + 2 > sequence.length()) return "PVS1_SPLICE_MOTIF_WITNESS_REQUIRED";
        String motif = CodingTranscriptMapping.orientAllele(model, sequence.substring(off, off + 2));
        if (!(isDonor ? "GT" : "AG").equals(motif)) return "PVS1_SPLICE_NONCANONICAL_MOTIF_NOT_SUPPORTED";
        if ("EXON_SHORTENING".equals(event.path("type").asText())
                && (isDonor ? event.path("deleteEndCds").asInt() != exon.path("cdsEnd").asInt()
                : event.path("deleteStartCds").asInt() != exon.path("cdsStart").asInt())) return "PVS1_SPLICE_EVENT_SITE_MISMATCH";
        return null;
    }

    /** Absence of an RNA assay is compatible with a reviewed prediction; an
     * explicitly detected residual or malformed finding cannot be discarded by
     * choosing the prediction path. No quantitative threshold is inferred here.
     */
    static boolean residualTranscriptCompatible(JsonNode review, boolean predicted) {
        var residual = review.path("residualNormalTranscript");
        if (predicted && residual.isMissingNode()) return true;
        if (!residual.isTextual()) return false;
        String finding = residual.asText();
        return "NOT_DETECTED_IN_ASSESSED_SCOPE".equals(finding)
                || predicted && Set.of("NOT_MEASURED", "UNKNOWN").contains(finding);
    }

    static String lower(String a, String b) { return STRENGTHS.get(Math.min(STRENGTHS.indexOf(a), STRENGTHS.indexOf(b))); }
    private static String translate(String s) { var p = new StringBuilder();for (int i = 0; i + 3 <= s.length(); i += 3) { char c = TruncatingSequence.translateCodon(s.substring(i, i + 3));p.append(c);if (c == '*') break; }return p.toString(); }
    private static String sha(String s) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII))); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private static boolean only(JsonNode n, Set<String> fields) { if (!n.isObject()) return false;var names=n.fieldNames();while(names.hasNext()) if(!fields.contains(names.next()))return false;return true; }
    private static boolean positive(JsonNode n) { return n.isIntegralNumber() && n.canConvertToInt() && n.asInt() > 0; }
    private static boolean text(JsonNode n) { return n.isTextual() && !n.asText().isBlank(); }
    private static boolean contains(JsonNode values, String s) { for (var v : values) if (v.isTextual() && s.equals(v.asText())) return true;return false; }
    private static boolean subset(JsonNode values, Set<String> allowed) { if(!values.isArray()||values.isEmpty())return false;var seen=new HashSet<String>();for(var v:values)if(!v.isTextual()||!allowed.contains(v.asText())||!seen.add(v.asText()))return false;return true; }
    private static boolean sourced(JsonNode value, JsonNode sources) { if(!text(value.path("basis"))||!value.path("sourceIds").isArray()||value.path("sourceIds").isEmpty())return false;var seen=new HashSet<String>();for(var id:value.path("sourceIds")){if(!text(id)||!seen.add(id.asText()))return false;boolean found=false;for(var s:sources)if(id.equals(s.path("sourceId")))found=true;if(!found)return false;}return true; }
}
