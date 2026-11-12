package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Local deletion effects after the caller binds and qualifies the exact splice outcome and model. */
final class Pvs1SpliceInFrame {
    static final String VERSION = "1.1.1";
    static final String COMPOSITE_VERSION = "1.0.0";
    private static final String REVIEWED = "REVIEWED_FOR_PVS1_SPLICE_INFRAME";
    private static final Set<String> STRENGTHS = Set.of("VERY_STRONG", "STRONG", "MODERATE", "SUPPORTING");
    private Pvs1SpliceInFrame() { }

    static String policyIssue(JsonNode policy, JsonNode model) {
        var scope = policy.at("/spliceScope/inFrameScope");
        if (scope.isMissingNode()) return null;
        if (!Pvs1SpliceConsequence.SCHEMA.equals(policy.path("schemaVersion").asText())
                || !only(scope, Set.of("status", "reviewId", "basis", "sourceIds", "exonAssessments", "rules"))
                || !CodingTranscriptMapping.sourcesValid(policy.path("scientificSources")))
            return "PVS1_SPLICE_INFRAME_POLICY_INVALID";
        if (!REVIEWED.equals(scope.path("status").asText()) || !text(scope.path("reviewId")))
            return "PVS1_SPLICE_INFRAME_SCOPE_NOT_REVIEWED";
        if (!sourced(scope, policy.path("scientificSources")) || !positive(model.path("proteinLength")))
            return "PVS1_SPLICE_INFRAME_POLICY_INVALID";
        var exons = scope.path("exonAssessments");var ordinals = new HashSet<Integer>();
        if (!exons.isArray() || exons.size() > model.path("exons").size()) return "PVS1_SPLICE_INFRAME_POLICY_INVALID";
        for (var exon : exons)
            if (!only(exon, Set.of("ordinal", "biologicalRelevance", "populationLof", "basis", "sourceIds"))
                    || !positive(exon.path("ordinal")) || exon.path("ordinal").asInt() > model.path("exons").size()
                    || !ordinals.add(exon.path("ordinal").asInt()) || !sourced(exon, policy.path("scientificSources"))
                    || !Set.of("PRESENT", "ABSENT", "UNKNOWN").contains(exon.path("biologicalRelevance").asText())
                    || !Set.of("NOT_FREQUENT", "FREQUENT", "UNKNOWN").contains(exon.path("populationLof").asText()))
                return "PVS1_SPLICE_INFRAME_POLICY_INVALID";
        var rules = scope.path("rules");var ids = new HashSet<String>();
        if (!rules.isArray() || rules.isEmpty() || rules.size() > 32) return "PVS1_SPLICE_INFRAME_POLICY_INVALID";
        for (var rule : rules) {
            if (!text(rule.path("ruleId")) || !ids.add(rule.path("ruleId").asText())
                    || !sourced(rule, policy.path("scientificSources")) || !STRENGTHS.contains(rule.path("strength").asText()))
                return "PVS1_SPLICE_INFRAME_POLICY_INVALID";
            if ("CRITICAL_REGION".equals(rule.path("predicate").asText())) {
                if (!only(rule, Set.of("ruleId", "predicate", "start", "end", "minimumAffectedResidues", "strength", "basis", "sourceIds"))
                        || !positive(rule.path("start")) || !positive(rule.path("end"))
                        || rule.path("end").asInt() < rule.path("start").asInt()
                        || rule.path("end").asInt() > model.path("proteinLength").asInt()
                        || !positive(rule.path("minimumAffectedResidues"))
                        || rule.path("minimumAffectedResidues").asInt() > rule.path("end").asInt() - rule.path("start").asInt() + 1)
                    return "PVS1_SPLICE_INFRAME_POLICY_INVALID";
            } else if ("DELETED_PROTEIN_FRACTION".equals(rule.path("predicate").asText())) {
                if (!only(rule, Set.of("ruleId", "predicate", "comparison", "numerator", "denominator", "strength", "basis", "sourceIds"))
                        || !Set.of("GT", "LT").contains(rule.path("comparison").asText())
                        || !positive(rule.path("numerator")) || !positive(rule.path("denominator"))
                        || rule.path("numerator").asInt() >= rule.path("denominator").asInt())
                    return "PVS1_SPLICE_INFRAME_POLICY_INVALID";
            } else return "PVS1_SPLICE_INFRAME_POLICY_INVALID";
        }
        return null;
    }

    static String evaluate(ObjectNode policy, JsonNode model, JsonNode fact, ObjectNode input, JsonNode selection) {
        var audit = input.putObject("inFrameAssessment").put("schemaVersion", "pvs1-splice-inframe-assessment-v1")
                .put("operatorVersion", VERSION).put("status", "NOT_EVALUATED");
        copyOptional(audit, "event", fact.path("event"));
        copyOptional(audit, "inFrameReview", fact.path("inFrameReview"));
        var event = fact.path("event");String cds = model.path("cdsSequence").asText();
        int length = model.path("proteinLength").asInt();
        if (!positive(model.path("proteinLength")) || !cds.matches("[ACGT]+") || cds.length() % 3 != 0
                || !positive(event.path("deleteStartCds")) || !positive(event.path("deleteEndCds")))
            return issue(audit, "PVS1_SPLICE_INFRAME_PROTEIN_EFFECT_UNRESOLVED");
        int lo = event.path("deleteStartCds").asInt(), hi = event.path("deleteEndCds").asInt();
        if (lo < 4 || hi < lo || hi > cds.length() - 3 || hi - lo >= 100000 || (hi - lo + 1) % 3 != 0)
            return issue(audit, "PVS1_SPLICE_INFRAME_EVENT_INVALID");
        String changed = cds.substring(0, lo - 1) + cds.substring(hi);
        String referenceProtein = translate(cds), protein = translate(changed);
        int deleted = (hi - lo + 1) / 3, firstTouched = (lo - 1) / 3 + 1, lastTouched = (hi - 1) / 3 + 1;
        boolean aligned = (lo - 1) % 3 == 0;
        audit.put("deleteStartCds", lo).put("deleteEndCds", hi).put("deletedCdsBases", hi - lo + 1)
                .put("referenceProteinLength", length).put("expectedAlteredProteinLength", length - deleted)
                .put("referenceProteinThroughTermination", referenceProtein).put("alteredProteinThroughTermination", protein)
                .put("alteredCdsSequence", changed).put("codonAligned", aligned)
                .put("firstTouchedReferenceResidue", firstTouched).put("lastTouchedReferenceResidue", lastTouched)
                .put("netCodonDeletion", deleted);
        if (referenceProtein.length() != length + 1 || referenceProtein.charAt(length) != '*')
            return issue(audit, "PVS1_SPLICE_INFRAME_REFERENCE_PROTEIN_UNRESOLVED");
        var effects = audit.putArray("referenceResidueEffects");int fullyRemoved = 0;
        for (int residue = firstTouched; residue <= lastTouched; residue++) {
            boolean removed = residue * 3 - 2 >= lo && residue * 3 <= hi;
            effects.addObject().put("referencePosition", residue).put("referenceAminoAcid", String.valueOf(referenceProtein.charAt(residue - 1)))
                    .put("referenceCdsStart", residue * 3 - 2).put("referenceCdsEnd", residue * 3)
                    .put("effect", removed ? "REMOVED" : "PARTIALLY_DELETED_JUNCTION_CODON");
            if (removed) fullyRemoved++;
        }
        int firstRemoved = firstTouched + (aligned ? 0 : 1), lastRemoved = hi / 3;
        audit.put("fullyRemovedReferenceResidues", fullyRemoved);
        if (fullyRemoved > 0) audit.put("firstFullyRemovedReferenceResidue", firstRemoved).put("lastFullyRemovedReferenceResidue", lastRemoved);
        else audit.putNull("firstFullyRemovedReferenceResidue").putNull("lastFullyRemovedReferenceResidue");
        String expectedProtein = referenceProtein.substring(0, firstTouched - 1);
        if (!aligned) {
            int junctionStart = (lo - 1) / 3 * 3;
            String codon = changed.substring(junctionStart, junctionStart + 3);
            char aminoAcid = TruncatingSequence.translateCodon(codon);
            audit.putObject("junctionEffect").put("alteredProteinPosition", firstTouched)
                    .put("alteredCodon", codon).put("alteredAminoAcid", String.valueOf(aminoAcid))
                    .put("leftReferenceResidue", firstTouched).put("rightReferenceResidue", lastTouched)
                    .put("leftRetainedBaseCount", (lo - 1) % 3).put("rightRetainedBaseCount", 3 - hi % 3);
            expectedProtein += aminoAcid;
        }
        expectedProtein += referenceProtein.substring(lastTouched);
        audit.put("expectedProteinThroughTermination", expectedProtein);
        if (input.has("alteredCdsSequence") && !changed.equals(input.path("alteredCdsSequence").asText())
                || input.has("referenceEditStart0") && !integerEquals(input.path("referenceEditStart0"), lo - 1)
                || input.has("referenceEditEnd0") && !integerEquals(input.path("referenceEditEnd0"), hi)
                || input.has("netLengthChange") && !integerEquals(input.path("netLengthChange"), -(hi - lo + 1)))
            return issue(audit, "PVS1_SPLICE_INFRAME_SEQUENCE_MISMATCH");
        int stop = protein.indexOf('*');
        if (stop >= 0 && stop < length - deleted)
            return issue(audit, "PVS1_SPLICE_INFRAME_PREMATURE_TERMINATION_REQUIRES_NMD");
        if (stop != length - deleted || !protein.equals(expectedProtein))
            return issue(audit, "PVS1_SPLICE_INFRAME_PROTEIN_EFFECT_UNRESOLVED");
        if (aligned) audit.put("firstDeletedReferenceResidue", firstTouched).put("lastDeletedReferenceResidue", lastTouched);
        audit.put("deletedProteinResidues", deleted).put("deletedProteinResidueMetric", "NET_PROTEIN_LENGTH_DECREASE")
                .put("alteredProteinLength", length - deleted).put("nativeStopRetained", true)
                .put("sequenceDerivationStatus", aligned ? "EXACT_LOCAL_CODON_ALIGNED_DELETION" : "EXACT_LOCAL_INFRAME_DELETION_WITH_FUSION_CODON");
        return qualifyAndEvaluate(policy, model, fact, selection, audit, length, deleted,
                fullyRemoved == 0 ? List.of() : List.of(new CodingConsequenceFacts.ReferenceResidueRange(firstRemoved, lastRemoved)),
                List.of(new CodingConsequenceFacts.ReferenceResidueRange(firstTouched, lastTouched)),
                List.of(new DeletionRange(lo, hi)), !aligned, false, "");
    }
    /** Uses actual composed facts, never reconstructs the single-deletion approximation. */
    static String evaluateComposite(JsonNode context, ObjectNode policy, JsonNode model, JsonNode fact,
                                    ObjectNode input, JsonNode selection, CodingConsequenceFacts.Facts facts) {
        var audit = input.putObject("inFrameAssessment").put("schemaVersion", "pvs1-splice-composite-inframe-assessment-v1")
                .put("operatorVersion", COMPOSITE_VERSION).put("compositeInFrameEffect", true).put("status", "NOT_EVALUATED");
        copyOptional(audit, "event", fact.path("event"));copyOptional(audit, "inFrameReview", fact.path("inFrameReview"));
        if (facts == null) return issue(audit, "PVS1_SPLICE_COMPOSITE_INFRAME_FACTS_REQUIRED");
        var mechanical = facts.snapshot();input.set("codingConsequenceFacts", mechanical.deepCopy());
        audit.put("codingConsequenceFactsSha256", facts.canonicalSha256()).put("mechanicalEffectSha256", facts.mechanicalEffectSha256());
        var hashes = new RuntimeEvidenceV2Adapter(new com.fasterxml.jackson.databind.ObjectMapper());
        String cds = model.path("cdsSequence").asText(), altered = input.path("alteredCdsSequence").asText();
        var pins = mechanical.path("binding");
        if (!hashes.canonicalSha256(model).equals(pins.path("modelSha256").asText())
                || !hashes.canonicalSha256(context).equals(pins.path("contextSha256").asText())
                || !CodingTranscriptMaterialSupply.sha(cds).equals(pins.path("referenceCdsSha256").asText())
                || !input.path("alteredCdsSequence").isTextual() || !altered.matches("[ACGT]*")
                || !CodingTranscriptMaterialSupply.sha(altered).equals(pins.path("alteredCdsSha256").asText())
                || !pins.path("modelSha256").equals(input.path("transcriptModelSha256"))
                || !pins.path("alteredCdsSha256").equals(input.path("alteredCdsSha256"))
                || !pins.path("modelSha256").equals(fact.path("transcriptModelSha256"))
                || !fact.path("transcript").equals(model.path("transcript"))
                || !fact.path("allele").equals(context.at("/variant/evidenceQueryAllele"))
                || !integerEquals(input.path("netLengthChange"), altered.length() - cds.length())
                || !integerEquals(mechanical.path("referenceProteinLength"), model.path("proteinLength").asInt()))
            return issue(audit, "PVS1_SPLICE_COMPOSITE_INFRAME_FACTS_BINDING_MISMATCH");
        var deletedIntervals = new ArrayList<DeletionRange>();boolean rnaBound = false;int rnaEvents = 0;
        for (var edit : mechanical.at("/editFacts/edits")) if ("APPLIED".equals(edit.path("status").asText())) {
            int start = edit.path("referenceEditStart0").asInt(), end = edit.path("referenceEditEnd0").asInt();
            if (end - start > edit.path("insertedSequence").asText().length()) deletedIntervals.add(new DeletionRange(start + 1, end));
            if ("SPLICE_PROJECTION".equals(edit.path("origin").asText())) rnaEvents++;
            if ("SPLICE_PROJECTION".equals(edit.path("origin").asText())
                    && integerEquals(fact.at("/event/deleteStartCds"), start + 1) && integerEquals(fact.at("/event/deleteEndCds"), end)
                    && edit.path("insertedSequence").asText().isEmpty()
                    && hashes.canonicalSha256(fact).equals(edit.path("evidenceBindingSha256").asText())) rnaBound = true;
        }
        if (!rnaBound || rnaEvents != 1) return issue(audit, "PVS1_SPLICE_COMPOSITE_INFRAME_FACTS_BINDING_MISMATCH");
        if (!facts.nativeStartRetained()) return issue(audit, "PVS1_SPLICE_COMPOSITE_INFRAME_START_MECHANISM_UNRESOLVED");
        if (!"NATIVE_STOP".equals(facts.firstStopKind())) return issue(audit, "PVS1_SPLICE_COMPOSITE_INFRAME_TERMINATION_REQUIRES_SEPARATE_BRANCH");
        Integer deleted = facts.inFrameNetDeletedProteinResidues();
        if (deleted == null) return issue(audit, "PVS1_SPLICE_COMPOSITE_INFRAME_LOCAL_LENGTH_EFFECT_UNRESOLVED");
        if (!Boolean.TRUE.equals(facts.proteinSequenceChanged())) return issue(audit, "PVS1_SPLICE_COMPOSITE_INFRAME_PROTEIN_UNCHANGED");
        var parentReview = fact.path("review");var sources = fact.path("sourceRecords");
        if (!only(parentReview, Set.of("status", "reviewId", "basis", "sourceIds", "maximumStrength", "functionalRescue", "outcomeMultiplicity", "residualNormalTranscript"))
                || !"REVIEWED_FOR_PVS1_SPLICE".equals(parentReview.path("status").asText()) || !text(parentReview.path("reviewId"))
                || !STRENGTHS.contains(parentReview.path("maximumStrength").asText())
                || !sourced(parentReview, sources) || !"SINGLE_SUPPORTED_OUTCOME".equals(parentReview.path("outcomeMultiplicity").asText())
                || !"EXCLUDED_IN_REVIEWED_SCOPE".equals(parentReview.path("functionalRescue").asText()))
            return issue(audit, "PVS1_SPLICE_COMPOSITE_INFRAME_PARENT_REVIEW_REQUIRED");
        var touched = facts.touchedReferenceResidueRanges();
        if (touched.isEmpty()) return issue(audit, "PVS1_SPLICE_COMPOSITE_INFRAME_LOCAL_LENGTH_EFFECT_UNRESOLVED");
        audit.set("referenceResidueEffects", mechanical.path("referenceResidueEffects").deepCopy());
        audit.set("wholeDeletedReferenceResidueRanges", mechanical.path("wholeDeletedReferenceResidueRanges").deepCopy());
        audit.set("touchedReferenceResidueRanges", mechanical.path("touchedReferenceResidueRanges").deepCopy());
        audit.put("referenceProteinLength", model.path("proteinLength").asInt()).set("alteredProteinLength", mechanical.path("alteredProteinLength").deepCopy());
        audit.put("fullyRemovedReferenceResidues", overlap(facts.wholeDeletedReferenceResidueRanges(), 1, model.path("proteinLength").asInt()))
                .put("deletedProteinResidues", deleted).put("deletedProteinResidueMetric", "NET_PROTEIN_LENGTH_DECREASE")
                .put("nativeStopRetained", true).put("sequenceDerivationStatus", "ACTUAL_COMPOSITE_CDS_BASE_PROVENANCE_AND_TRANSLATION");
        return qualifyAndEvaluate(policy, model, fact, selection, audit, model.path("proteinLength").asInt(), deleted,
                facts.wholeDeletedReferenceResidueRanges(), touched, deletedIntervals, true, true, facts.mechanicalEffectSha256());
    }

    static String evaluateFullRna(ObjectNode policy, JsonNode model, JsonNode fact, JsonNode selection,
            Pvs1SourceQualification.SourceBoundFullRna source, ObjectNode input) {
        var effects=source.facts().snapshot().path("codingEffects");
        var audit=input.putObject("inFrameAssessment").put("schemaVersion","pvs1-full-rna-inframe-assessment-v1")
                .put("operatorVersion","1.0.0").put("status","NOT_EVALUATED").put("mechanicalEffectSha256",source.facts().mechanicalEffectSha256());
        var removed=new ArrayList<CodingConsequenceFacts.ReferenceResidueRange>();var touched=new ArrayList<CodingConsequenceFacts.ReferenceResidueRange>();
        for(var row:effects.path("wholeDeletedReferenceResidueRanges")) removed.add(new CodingConsequenceFacts.ReferenceResidueRange(row.path("start").asInt(),row.path("end").asInt()));
        for(var row:effects.path("proteinEffectReferenceResidueRanges")) touched.add(new CodingConsequenceFacts.ReferenceResidueRange(row.path("start").asInt(),row.path("end").asInt()));
        if(!effects.path("localInFrameLengthChangeResolved").asBoolean() || touched.isEmpty()) return issue(audit,"PVS1_SPLICE_COMPOSITE_INFRAME_LOCAL_LENGTH_EFFECT_UNRESOLVED");
        if(!effects.path("proteinSequenceChanged").asBoolean()) return issue(audit,"PVS1_SPLICE_COMPOSITE_INFRAME_PROTEIN_UNCHANGED");
        int deleted=Math.max(0,-effects.path("netProteinLengthChange").asInt());var ranges=new ArrayList<DeletionRange>();
        for(var span:Pvs1SpliceConsequence.fullRnaCodingFootprint(source)) ranges.add(new DeletionRange(span[0],span[1]));
        audit.set("referenceResidueEffects",effects.path("referenceResidueEffects").deepCopy());
        audit.set("wholeDeletedReferenceResidueRanges",effects.path("wholeDeletedReferenceResidueRanges").deepCopy());
        audit.set("touchedReferenceResidueRanges",effects.path("touchedReferenceResidueRanges").deepCopy());
        audit.put("referenceProteinLength",model.path("proteinLength").asInt()).put("deletedProteinResidues",deleted)
                .put("deletedProteinResidueMetric","ACTUAL_NET_PROTEIN_LENGTH_DECREASE");
        return qualifyAndEvaluate(policy,model,fact,selection,audit,model.path("proteinLength").asInt(),deleted,removed,touched,ranges,true,true,source.facts().mechanicalEffectSha256());
    }

    private record DeletionRange(int start, int end) { }

    private static String qualifyAndEvaluate(ObjectNode policy, JsonNode model, JsonNode fact, JsonNode selection,
            ObjectNode audit, int length, int deleted, List<CodingConsequenceFacts.ReferenceResidueRange> removed,
            List<CodingConsequenceFacts.ReferenceResidueRange> touchedRanges, List<DeletionRange> deletionRanges,
            boolean unresolvedJunction, boolean composite, String mechanicalEffectSha) {
        int firstTouched = touchedRanges.get(0).start(), lastTouched = touchedRanges.get(touchedRanges.size() - 1).end();
        var scope = policy.at("/spliceScope/inFrameScope");
        if (scope.isMissingNode()) return issue(audit, "PVS1_SPLICE_INFRAME_EFFECT_NOT_IMPLEMENTED");
        String policyIssue = policyIssue(policy, model);
        if (policyIssue != null) return issue(audit, policyIssue);
        audit.set("policyReviewId", scope.path("reviewId").deepCopy());
        var sources = fact.path("sourceRecords");var review = fact.path("inFrameReview");
        if (!CodingTranscriptMapping.sourcesValid(sources)) return issue(audit, "PVS1_SPLICE_INFRAME_MATERIAL_INVALID");
        for (var source : sources) for (var declared : policy.path("scientificSources"))
            if (source.path("sourceId").equals(declared.path("sourceId")) && !source.equals(declared))
                return issue(audit, "PVS1_SPLICE_SOURCE_IDENTITY_CONFLICT");
        if (!only(review, composite ? Set.of("status", "reviewId", "basis", "sourceIds", "criticalRegionAssessment", "compositeEffectAssessment")
                : Set.of("status", "reviewId", "basis", "sourceIds", "criticalRegionAssessment"))
                || !REVIEWED.equals(review.path("status").asText()) || !text(review.path("reviewId")) || !sourced(review, sources))
            return issue(audit, "PVS1_SPLICE_INFRAME_OUTCOME_NOT_REVIEWED");
        if (composite) {
            var effect = review.path("compositeEffectAssessment");
            if (!only(effect, Set.of("schemaVersion", "status", "mechanicalEffectSha256", "reviewId", "basis", "sourceIds", "originalReview"))
                    || !"pvs1-composite-inframe-effect-review-v1".equals(effect.path("schemaVersion").asText())
                    || !"REVIEWED_FOR_ACTUAL_COMPOSITE_EFFECT".equals(effect.path("status").asText())
                    || !text(effect.path("reviewId")) || !sourced(effect, sources))
                return issue(audit, "PVS1_SPLICE_COMPOSITE_INFRAME_EFFECT_REVIEW_REQUIRED");
            if (!effect.path("mechanicalEffectSha256").asText().matches("[0-9a-f]{64}")
                    || !effect.path("mechanicalEffectSha256").asText().equals(mechanicalEffectSha))
                return issue(audit, "PVS1_SPLICE_COMPOSITE_INFRAME_EFFECT_BINDING_MISMATCH");
            audit.set("compositeEffectAssessment", effect.deepCopy());
        }
        audit.set("sourceRecords", sources.deepCopy());
        String exonIssue = exonIssue(policy, model, scope, audit, deletionRanges);
        if (exonIssue != null) return issue(audit, exonIssue);
        var regionAssessment = review.path("criticalRegionAssessment");
        if (!regionAssessment.isMissingNode() && !validRegionAssessment(regionAssessment, sources, length))
            return issue(audit, "PVS1_SPLICE_INFRAME_CRITICAL_REGION_ASSESSMENT_INVALID");
        var qualifyingCriticalRules = new ArrayList<JsonNode>();
        for (var rule : scope.path("rules")) if ("CRITICAL_REGION".equals(rule.path("predicate").asText())) {
            int affected = overlap(removed, rule.path("start").asInt(), rule.path("end").asInt());
            if (affected >= rule.path("minimumAffectedResidues").asInt()) qualifyingCriticalRules.add(rule);
        }
        var matches = new ArrayList<JsonNode>();var checks = audit.putArray("ruleChecks");
        String fractionIssue = null;boolean boundary = false, criticalOverlap = false, criticalRulePresent = false;
        for (var rule : scope.path("rules")) {
            var row = checks.addObject();row.set("rule", rule.deepCopy());boolean hit;
            if ("CRITICAL_REGION".equals(rule.path("predicate").asText())) {
                criticalRulePresent = true;
                int affected = overlap(removed, rule.path("start").asInt(), rule.path("end").asInt());
                int touched = overlap(touchedRanges, rule.path("start").asInt(), rule.path("end").asInt());
                row.put("affectedResiduesInRegion", affected).put("touchedReferenceResiduesInRegion", touched)
                        .put(composite ? "notWhollyDeletedReferenceResiduesInRegion" : "partiallyDeletedReferenceResiduesInRegion", touched - affected);
                // A whole-effect review can exclude critical damage from a touched
                // replacement/fused codon. Only a matching deletion rule contradicts
                // that review; mechanical touch alone grants no functional conclusion.
                criticalOverlap |= composite ? affected >= rule.path("minimumAffectedResidues").asInt() : touched > 0;
                hit = affected >= rule.path("minimumAffectedResidues").asInt();
            } else {
                // Known critical damage falsifies the fraction branch premise, regardless of rule order.
                if (!qualifyingCriticalRules.isEmpty()) {
                    row.put("status", "NOT_APPLICABLE_KNOWN_CRITICAL_REGION_AFFECTED")
                            .put("routingBasis", "SOURCE_BOUND_CRITICAL_REGION_RULE_MATCH");
                    String originalAssessmentIssue = fractionIssue(regionAssessment, firstTouched, lastTouched);
                    if (originalAssessmentIssue != null) row.put("criticalRegionAssessmentIssue", originalAssessmentIssue);
                    var routingSources = row.putArray("qualifyingCriticalRules");
                    for (var criticalRule : qualifyingCriticalRules) routingSources.add(criticalRule.deepCopy());
                    continue;
                }
                if (composite && deleted <= 0) { row.put("status", "NOT_APPLICABLE_NO_NET_PROTEIN_SHORTENING");continue; }
                long left = (long) deleted * rule.path("denominator").asInt();
                long right = (long) length * rule.path("numerator").asInt();
                row.put("actualNumerator", deleted).put("actualDenominator", length).put("leftProduct", left).put("rightProduct", right);
                String qualificationIssue = fractionIssue(regionAssessment, firstTouched, lastTouched);
                if (qualificationIssue != null) {
                    if ("PVS1_SPLICE_INFRAME_KNOWN_CRITICAL_REGION_AFFECTED".equals(qualificationIssue))
                        row.put("status", "NOT_APPLICABLE_KNOWN_CRITICAL_REGION_AFFECTED");
                    else { row.put("status", "CRITICAL_REGION_ASSESSMENT_UNRESOLVED");fractionIssue = qualificationIssue; }
                    continue;
                }
                boundary |= left == right;
                hit = "GT".equals(rule.path("comparison").asText()) ? left > right : left < right;
            }
            row.put("status", hit ? "MATCH" : "NO_MATCH");if (hit) matches.add(rule);
        }
        if (criticalOverlap && "NO_KNOWN_CRITICAL_REGION_AFFECTED".equals(regionAssessment.path("finding").asText())
                && fractionIssue(regionAssessment, firstTouched, lastTouched) == null)
            return issue(audit, "PVS1_SPLICE_INFRAME_CRITICAL_REGION_FACTS_CONFLICT");
        if (matches.size() > 1) return issue(audit, "PVS1_SPLICE_INFRAME_RULES_AMBIGUOUS");
        if (fractionIssue != null) return issue(audit, fractionIssue);
        if (matches.isEmpty()) return issue(audit, boundary ? "PVS1_SPLICE_INFRAME_FRACTION_BOUNDARY_UNSPECIFIED"
                : composite && deleted <= 0 ? "PVS1_SPLICE_COMPOSITE_INFRAME_NO_NET_PROTEIN_SHORTENING"
                : unresolvedJunction && criticalRulePresent ? "PVS1_SPLICE_INFRAME_JUNCTION_FUNCTION_UNRESOLVED"
                : "PVS1_SPLICE_INFRAME_NO_REVIEWED_RULE_MATCH");
        var rule = matches.get(0);
        if ("VERY_STRONG".equals(rule.path("strength").asText()) && !"SPECIALIZED_POLICY_SELECTED".equals(selection.path("outcome").asText()))
            return issue(audit, "PVS1_SPLICE_INFRAME_SPECIALIZED_STRENGTH_REQUIRED");
        audit.set("selectedRule", rule.deepCopy());audit.put("strength", rule.path("strength").asText()).put("status", "MET");
        return null;
    }

    private static int overlap(List<CodingConsequenceFacts.ReferenceResidueRange> ranges, int start, int end) {
        int count = 0;for (var range : ranges) count += Math.max(0, Math.min(range.end(), end) - Math.max(range.start(), start) + 1);return count;
    }

    private static String exonIssue(JsonNode policy, JsonNode model, JsonNode scope, ObjectNode audit, List<DeletionRange> ranges) {
        var checks = audit.putArray("exonChecks");int absent = 0, frequent = 0;
        boolean missing = false, relevanceUnknown = false, populationUnknown = false, relevanceConflict = false;
        for (var exon : model.path("exons")) {
            boolean affected = false;
            for (var range : ranges) if (exon.path("cdsEnd").asInt() >= range.start() && exon.path("cdsStart").asInt() <= range.end()) affected = true;
            if (!affected) continue;
            JsonNode fact = null;
            for (var value : scope.path("exonAssessments")) if (value.path("ordinal").equals(exon.path("ordinal"))) fact = value;
            if (fact == null) { copyOptional(checks.addObject(), "ordinal", exon.path("ordinal"));missing = true;continue; }
            checks.add(fact.deepCopy());
            boolean reviewedRelevant = TruncatingNmdPolicy.contains(policy.at("/nmdScope/relevantExonOrdinals"), exon.path("ordinal").asInt());
            if ("ABSENT".equals(fact.path("biologicalRelevance").asText())) {
                absent++;
                // An explicit negative cannot negate a positive fact for the same policy background.
                if (reviewedRelevant) relevanceConflict = true;
            }
            if ("FREQUENT".equals(fact.path("populationLof").asText())) frequent++;
            if ("UNKNOWN".equals(fact.path("biologicalRelevance").asText())) relevanceUnknown = true;
            if ("UNKNOWN".equals(fact.path("populationLof").asText())) populationUnknown = true;
        }
        if (checks.isEmpty() || missing) return "PVS1_SPLICE_INFRAME_EXON_ASSESSMENT_MISSING";
        if (relevanceConflict) return "PVS1_SPLICE_INFRAME_EXON_FACTS_CONFLICT";
        if (relevanceUnknown) return "PVS1_SPLICE_INFRAME_EXON_RELEVANCE_UNRESOLVED";
        if (populationUnknown) return "PVS1_SPLICE_INFRAME_POPULATION_TOLERANCE_UNRESOLVED";
        if (absent == checks.size()) return "PVS1_SPLICE_INFRAME_EXON_NOT_RELEVANT";
        if (frequent == checks.size()) return "PVS1_SPLICE_INFRAME_EXON_LOF_TOLERANT";
        if (absent > 0 || frequent > 0) return "PVS1_SPLICE_INFRAME_EXON_FACTS_CONFLICT";
        return null;
    }

    private static boolean validRegionAssessment(JsonNode fact, JsonNode sources, int length) {
        return only(fact, Set.of("status", "coverage", "finding", "start", "end", "basis", "sourceIds"))
                && Set.of("REVIEWED_IN_DECLARED_SCOPE", "UNREVIEWED").contains(fact.path("status").asText())
                && Set.of("COMPLETE_FOR_AFFECTED_PROTEIN_REGION", "INCOMPLETE", "UNKNOWN").contains(fact.path("coverage").asText())
                && Set.of("NO_KNOWN_CRITICAL_REGION_AFFECTED", "KNOWN_CRITICAL_REGION_AFFECTED", "UNKNOWN").contains(fact.path("finding").asText())
                && positive(fact.path("start")) && positive(fact.path("end")) && fact.path("start").asInt() <= fact.path("end").asInt()
                && fact.path("end").asInt() <= length && sourced(fact, sources);
    }

    private static String fractionIssue(JsonNode assessment, int start, int end) {
        if (!"REVIEWED_IN_DECLARED_SCOPE".equals(assessment.path("status").asText())
                || !"COMPLETE_FOR_AFFECTED_PROTEIN_REGION".equals(assessment.path("coverage").asText())
                || assessment.path("start").asInt() > start || assessment.path("end").asInt() < end
                || "UNKNOWN".equals(assessment.path("finding").asText()))
            return "PVS1_SPLICE_INFRAME_CRITICAL_REGION_ASSESSMENT_REQUIRED";
        return "KNOWN_CRITICAL_REGION_AFFECTED".equals(assessment.path("finding").asText())
                ? "PVS1_SPLICE_INFRAME_KNOWN_CRITICAL_REGION_AFFECTED" : null;
    }

    static boolean negative(String issue) {
        return "PVS1_SPLICE_INFRAME_EXON_NOT_RELEVANT".equals(issue) || "PVS1_SPLICE_INFRAME_EXON_LOF_TOLERANT".equals(issue);
    }
    private static String issue(ObjectNode audit, String issue) {
        audit.put("status", negative(issue) ? "NOT_MET" : "NOT_EVALUATED").put("reasonCode", issue);return issue;
    }
    private static void copyOptional(ObjectNode target, String field, JsonNode value) {
        if (value.isMissingNode()) target.putNull(field);
        else target.set(field, value.deepCopy());
    }
    private static String translate(String cds) {
        var protein = new StringBuilder();
        for (int i = 0; i + 3 <= cds.length(); i += 3) {
            char aminoAcid = TruncatingSequence.translateCodon(cds.substring(i, i + 3));protein.append(aminoAcid);
            if (aminoAcid == '*') break;
        }
        return protein.toString();
    }
    private static boolean integerEquals(JsonNode value, int expected) { return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() == expected; }
    private static boolean positive(JsonNode value) { return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() > 0; }
    private static boolean text(JsonNode value) { return value.isTextual() && !value.asText().isBlank(); }
    private static boolean only(JsonNode value, Set<String> fields) {
        if (!value.isObject()) return false;var names = value.fieldNames();while (names.hasNext()) if (!fields.contains(names.next())) return false;return true;
    }
    private static boolean sourced(JsonNode value, JsonNode sources) {
        if (!text(value.path("basis")) || !value.path("sourceIds").isArray() || value.path("sourceIds").isEmpty()) return false;
        var ids = new HashSet<String>();
        for (var id : value.path("sourceIds")) {
            if (!text(id) || !ids.add(id.asText())) return false;
            boolean found = false;for (var source : sources) if (id.equals(source.path("sourceId"))) found = true;
            if (!found) return false;
        }
        return true;
    }
}
