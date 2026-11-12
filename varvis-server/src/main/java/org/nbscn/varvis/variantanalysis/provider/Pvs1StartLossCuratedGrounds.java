package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Checks a bounded set of explicitly documented expert-curated grounds for
 * independence from start-loss inference. This is not a fresh PS2/PS3/PP3
 * evaluation, a reconstruction of private cases, or qualification of the target.
 * Unknown grounds are omitted; the retained grounds must still reproduce P
 * under the same original guideline. Source/allele conflicts are checked later. */
final class Pvs1StartLossCuratedGrounds {
    private static final String NUMBER = "(-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?)";
    private static final Pattern ORIGIN = Pattern.compile("^At least (?:two|[2-9][0-9]*) proven OR one proven plus (?:two|[2-9][0-9]*) assumed de novo observations in a patient with the disease and no family history\\. \\(internal laboratory contributors (SCV[0-9]+\\.[1-9][0-9]*) and (SCV[0-9]+\\.[1-9][0-9]*)\\)\\.$");
    private static final Pattern FUNCTION = Pattern.compile("^Functional studies supportive of a damaging effect on the gene or gene product\\. Score of this variant = \\[" + NUMBER
            + "\\] \\((≤|>=|<=|≥|>|<) " + NUMBER + "\\) on a ([A-Za-z][A-Za-z -]{1,100} assay) \\(PMID:([1-9][0-9]*)\\)\\.$");
    private static final Pattern COMPUTATION = Pattern.compile("^([A-Z][A-Za-z0-9_-]{1,40}) score (>|<|>=|<=|≥|≤) " + NUMBER
            + " \\(score of this variant = " + NUMBER + "\\)$");
    private static final Pattern POPULATION_ABSENCE = Pattern.compile("^This variant is absent in (gnomAD) (v[0-9]+(?:\\.[0-9]+){1,2})\\. \\((PM2_(?:Supporting|Moderate|Strong))\\)\\.$");
    private static final Pattern CLINICAL_MRS = Pattern.compile("^PMID: ([1-9][0-9]*): ([1-9][0-9]*) individuals? with this variant had significantly reduced ([A-Za-z][A-Za-z -]{1,60}) peak on (brain MRS) \\(([A-Z][A-Z0-9]{1,9}) not reported\\) \\(([1-9][0-9]*)pts\\) \\((PP4_(?:Supporting|Moderate|Strong))\\); no biochemical data reported$");
    private static final Pattern CLINICAL_BIOCHEMICAL_MRS = Pattern.compile("^(First cousins|Siblings|Two siblings), who are homozygous for the variant, have been reported\\. One of these individuals had elevated ([A-Za-z][A-Za-z -]{1,60}) in (urine|plasma|blood) and decreased ([A-Za-z][A-Za-z -]{1,60}) peak on MRS \\((PP4_(?:Supporting|Moderate|Strong))\\)\\.$");
    private Pvs1StartLossCuratedGrounds() { }

    static ObjectNode assess(ObjectMapper mapper, JsonNode summary, JsonNode original,
            PredictionSpecializationGuard.ReferenceSpecification specification) {
        return assess(mapper, summary, original, specification, null);
    }

    static ObjectNode assess(ObjectMapper mapper, JsonNode summary, JsonNode original,
            PredictionSpecializationGuard.ReferenceSpecification specification,
            @org.springframework.lang.Nullable JsonNode referenceVerifiedModel) {
        var out = mapper.createObjectNode().put("method", "EXPLICIT_CURATED_START_LOSS_INDEPENDENCE_V2")
                .put("status", "UNRESOLVED").put("independenceEstablished", false)
                .put("basis", "ORIGINAL_EXPERT_CURATED_ASSERTIONS")
                .put("primaryExperimentReevaluated", false).put("privateCaseRecordsReconstructed", false);
        var claims = out.putArray("grounds");
        try {
            require("Pathogenic".equals(summary.path("classification").asText())
                    && summary.path("retracted").isBoolean() && !summary.path("retracted").asBoolean(), "ACTIVE_PATHOGENIC_REFERENCE_REQUIRED");
            LocalDate.parse(summary.path("approvedDate").asText()); LocalDate.parse(summary.path("publishedDate").asText());
            String affiliation = summary.path("affiliationId").asText();
            require(affiliation.matches("[1-9][0-9]*") && !summary.path("ep").asText().isBlank(), "EXPERT_ATTRIBUTION_UNRESOLVED");
            var replay = Pvs1StartLossOriginalRuleReplay.replay(mapper, summary, original, specification);
            out.put("originalRuleReplaySha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(replay));
            out.put("originalReplayStatus", replay.path("status").asText());
            require("ORIGINAL_CLASSIFICATION_REPLAYED".equals(replay.path("status").asText()), "ORIGINAL_CLASSIFICATION_REPLAY_REQUIRED");
            var retained = new LinkedHashSet<String>();
            var meanings = new LinkedHashSet<String>();
            for (var applied : replay.path("actualAppliedCriteria")) {
                String code = applied.path("code").asText(), pointer = applied.path("sourceLocator").asText();
                // A contrary applied criterion must not be silently removed.
                require(!code.startsWith("B"), "ADVERSE_APPLIED_EVIDENCE_REQUIRES_REVIEW");
                var row = claims.addObject().put("code", code).put("sourceLocator", pointer)
                        .put("disposition", "NOT_USED").put("reasonCode", "CURATED_SCIENTIFIC_GROUND_NOT_SUPPORTED");
                var item = original.at(pointer);
                String observationPointer = pointer + "/evidenceLine/0/evidenceItem/0";
                var observation = original.at(observationPointer);
                // Fully embedded claims have a precise identity even if this
                // prototype SEPIO export reuses a curator-inference bucket ID.
                // Never dereference an ambiguous external/bare identifier here.
                if (item.path("evidenceLine").size() != 1 || item.at("/evidenceLine/0/evidenceItem").size() != 1
                        || !Set.of("CuratorInference", "InfFromBkgrndSciKnow").contains(observation.path("type").asText())
                        || observation.path("contribution").size() != 1 || observation.has("evidenceLine")) continue;
                boolean extraFields = false;
                for (var field : observation.properties()) extraFields |= !Set.of("id", "@id", "type", "contribution", "traceableAuthorStatement").contains(field.getKey());
                if (extraFields) continue; // do not discard additional scientific qualifiers
                var contribution = observation.at("/contribution/0");
                String agent = contribution.at("/agent/id").asText();
                if (!agent.matches("CG-PCER-AGENT:CG_" + affiliation + "_EP[0-9]+")
                        || !"curator role".equals(contribution.at("/contributionRole/label").asText())
                        || item.path("contribution").size() != 1 || !agent.equals(item.at("/contribution/0/agent/id").asText())
                        || !contribution.path("comments").isTextual()) continue;
                String text = contribution.path("comments").asText().strip();
                if (text.isEmpty() || text.length() > 2000) continue;
                var fact = mapper.createObjectNode();
                String kind = readGround(mapper, code, applied.path("appliedCode").asText(), text, observation, fact, summary, referenceVerifiedModel);
                if (kind.isEmpty()) {
                    if (!fact.isEmpty()) row.set("sourceClaim", fact);
                    String failed = fact.at("/truncatingMechanism/reasonCode").asText();
                    if (!failed.isEmpty()) row.put("reasonCode", failed).put("sourceText", text)
                            .put("claimLocator", observationPointer + "/contribution/0/comments");
                    continue;
                }
                if ("COMPUTATIONAL_SCORE".equals(kind) && !nonStartLossComputationalScope(summary, referenceVerifiedModel)) {
                    row.put("reasonCode", "NON_START_LOSS_COMPUTATIONAL_SCOPE_NOT_ESTABLISHED").put("sourceText", text)
                            .put("claimLocator", observationPointer + "/contribution/0/comments");
                    row.set("sourceClaim", fact); continue;
                }
                // Independent scientific modalities, not reused source labels,
                // provide the separation for this bounded interpreter.
                if (!meanings.add(kind)) return out.put("reasonCode", "REUSED_SCIENTIFIC_GROUND_REQUIRES_REVIEW");
                retained.add(applied.path("appliedCode").asText());
                row.put("disposition", "INDEPENDENT_CURATED_GROUND").put("reasonCode", "EXPLICIT_GROUND_UNRELATED_TO_START_LOSS_INFERENCE")
                        .put("groundKind", kind).put("observationLocator", observationPointer)
                        .put("claimLocator", observationPointer + "/contribution/0/comments").put("sourceText", text)
                        .put("sourceAppliedCode", applied.path("appliedCode").asText())
                        .put("sourceAppliedStrength", applied.path("sourceAppliedStrength").asText())
                        .put("observationId", observation.path("id").asText())
                        .put("sourceIdentityBasis", "EMBEDDED_JSON_POINTER_AND_SCIENTIFIC_MODALITY");
                row.set("sourceClaim", fact);
            }
            // These interpretations of effect can share the same causal
            // observation with a truncating PVS1 claim. Do not count them as a
            // second independent modality merely because their codes differ.
            if (meanings.contains("PREDICTED_NON_START_LOSS_TRUNCATION")) {
                for (var row : claims) if (Set.of("PP3", "PS3", "PM4").contains(row.path("code").asText())
                        && "INDEPENDENT_CURATED_GROUND".equals(row.path("disposition").asText())) {
                    retained.remove(row.path("sourceAppliedCode").asText());
                    ((ObjectNode) row).put("disposition", "NOT_USED").put("reasonCode", "SCIENTIFIC_EFFECT_OVERLAP_WITH_TRUNCATION_REQUIRES_REVIEW");
                }
            }
            var independent = Pvs1StartLossCombinationReplay.replay(mapper,
                    specification.entity().at("/ld/RuleSet/0/entContent/rules"), retained);
            out.set("independentCombination", independent);
            if (!"REPLAYED".equals(independent.path("status").asText())
                    || !"Pathogenic".equals(independent.path("classification").asText()))
                return out.put("reasonCode", "INDEPENDENT_CURATED_GROUNDS_NOT_SUFFICIENT_FOR_PATHOGENIC");
            return out.put("status", "INDEPENDENT_CURATED_BASIS_ESTABLISHED").put("independenceEstablished", true)
                    .put("reasonCode", "INDEPENDENT_CURATED_PATHOGENIC_COMBINATION")
                    .put("referenceQualificationComplete", false); // allele/conflict review is separate
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            return out.put("reasonCode", invalid.getMessage() != null && invalid.getMessage().matches("[A-Z_]+")
                    ? invalid.getMessage() : "CURATED_REFERENCE_FACTS_INVALID");
        }
    }

    private static String readGround(ObjectMapper mapper, String code, String appliedCode, String text, JsonNode observation, ObjectNode fact,
            JsonNode summary, @org.springframework.lang.Nullable JsonNode referenceVerifiedModel) {
        if ("PVS1".equals(code) && referenceVerifiedModel != null) {
            var replay = Pvs1StartLossTruncatingGround.assess(mapper, text, summary, referenceVerifiedModel);
            fact.set("truncatingMechanism", replay);
            if ("NON_START_LOSS_TRUNCATION_CONFIRMED".equals(replay.path("status").asText()))
                return "PREDICTED_NON_START_LOSS_TRUNCATION";
            return "";
        }
        if ("PM2".equals(code)) {
            var m = POPULATION_ABSENCE.matcher(text);
            if (!m.matches() || !appliedCode.equals(m.group(3))) return "";
            fact.put("statement", "CURATOR_REPORTS_ABSENCE_IN_NAMED_POPULATION_RELEASE")
                    .put("populationResource", m.group(1)).put("populationRelease", m.group(2))
                    .put("populationCoverageRechecked", false).put("populationFrequencyInferredZero", false);
            return "POPULATION_OBSERVATION";
        }
        if ("PP4".equals(code)) {
            var m = CLINICAL_MRS.matcher(text);
            if (!m.matches()) {
                var biochemical = CLINICAL_BIOCHEMICAL_MRS.matcher(text);
                if (!biochemical.matches() || !appliedCode.equals(biochemical.group(5))) return "";
                fact.put("statement", "CURATOR_REPORTS_MEASURED_BIOCHEMICAL_AND_IMAGING_FINDINGS")
                        .put("reportedFamilyRelationship", biochemical.group(1)).put("reportedElevatedAnalyte", biochemical.group(2))
                        .put("sampleType", biochemical.group(3)).put("reportedReducedAnalyte", biochemical.group(4))
                        .put("method", "MRS").put("measurementValuesKnown", false).put("atLeastOneMeasuredIndividualReported", true)
                        .put("clinicalRelevanceReevaluated", false).put("casesIndependentlyReconstructed", false);
                return "MEASURED_CLINICAL_FINDING";
            }
            if (!appliedCode.equals(m.group(7)) || !Set.of("", "https://www.ncbi.nlm.nih.gov/pubmed/" + m.group(1))
                    .contains(observation.path("traceableAuthorStatement").asText())) return "";
            fact.put("statement", "CURATOR_REPORTS_MEASURED_CLINICAL_IMAGING_FINDING")
                    .put("pmid", m.group(1)).put("reportedIndividuals", Integer.parseInt(m.group(2)))
                    .put("reportedReducedAnalyte", m.group(3)).put("method", m.group(4)).put("unreportedMeasurement", m.group(5))
                    .put("sourceReportedPoints", Integer.parseInt(m.group(6)))
                    .put("clinicalRelevanceReevaluated", false).put("casesIndependentlyReconstructed", false);
            return "MEASURED_CLINICAL_FINDING";
        }
        if ("PS2".equals(code)) {
            var m = ORIGIN.matcher(text);
            if (!m.matches() || m.group(1).split("\\.")[0].equals(m.group(2).split("\\.")[0])) return "";
            fact.put("statement", "CURATOR_REPORTS_PROVEN_OR_MIXED_DENOVO_OBSERVATIONS")
                    .put("exactCaseCountsKnown", false).put("parentageIndependentlyRechecked", false);
            fact.putArray("sourceAccessions").add(m.group(1)).add(m.group(2));
            return "CLINICAL_DENOVO_OBSERVATIONS";
        }
        if ("PS3".equals(code)) {
            var m = FUNCTION.matcher(text);
            if (!m.matches() || !compare(m.group(1), m.group(2), m.group(3))
                    || !observation.path("traceableAuthorStatement").asText().equals("https://www.ncbi.nlm.nih.gov/pubmed/" + m.group(5))) return "";
            fact.put("reportedScore", m.group(1)).put("reportedThreshold", m.group(3)).put("comparison", m.group(2))
                    .put("assay", m.group(4)).put("pmid", m.group(5)).put("rawExperimentalQualityRechecked", false);
            return "MEASURED_FUNCTIONAL_ASSAY";
        }
        if ("PP3".equals(code)) {
            var m = COMPUTATION.matcher(text);
            if (!m.matches() || !Set.of("REVEL", "CADD", "AlphaMissense").contains(m.group(1))
                    || !compare(m.group(4), m.group(2), m.group(3))) return "";
            fact.put("algorithm", m.group(1)).put("reportedScore", m.group(4)).put("reportedThreshold", m.group(3))
                    .put("comparison", m.group(2)).put("predictorRerun", false);
            return "COMPUTATIONAL_SCORE";
        }
        return "";
    }
    private static boolean compare(String score, String op, String threshold) {
        int order = new BigDecimal(score).compareTo(new BigDecimal(threshold));
        return switch (op) { case "<" -> order < 0; case ">" -> order > 0;
            case "<=", "≤" -> order <= 0; case ">=", "≥" -> order >= 0; default -> false; };
    }
    private static boolean nonStartLossComputationalScope(JsonNode summary, @org.springframework.lang.Nullable JsonNode model) {
        var codingEdit = Pattern.compile("^((?:NM_[0-9]+|ENST[0-9]+)\\.[1-9][0-9]*):c\\.([1-9][0-9]*)(?:_([1-9][0-9]*))?(?:[ACGT]>[ACGT]|(?:delins|del|dup|ins)[ACGT]*)$");
        boolean established = false;
        for (var description : summary.path("hgvs")) {
            if (!description.isTextual() || description.asText().length() > 300) continue;
            var m = codingEdit.matcher(description.asText()); if (!m.matches()) continue;
            try {
                if (Integer.parseInt(m.group(2)) <= 3) return false;
                if (model == null) { established = true; continue; }
                JsonNode sourceModel = model;
                if (!m.group(1).equals(model.path("transcript").asText())) {
                    if (!PublishedCriterionKnowledge.exactBridge(m.group(1), model) || !(model instanceof ObjectNode object)) continue;
                    var bridge = object.deepCopy(); bridge.put("transcript", m.group(1)); sourceModel = bridge;
                }
                var mapping = Pvs1StartLossReferenceMapping.map(sourceModel, description.asText());
                if (!mapping.mapped() || mapping.first() <= 3) return false;
                established = true;
            } catch (IllegalArgumentException unresolved) { return false; }
        }
        return established;
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
