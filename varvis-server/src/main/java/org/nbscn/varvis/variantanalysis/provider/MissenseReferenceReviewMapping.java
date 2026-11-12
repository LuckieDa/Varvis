package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

/** Pure copying and binding of external declarations; no scientific evaluation or approval. */
final class MissenseReferenceReviewMapping {
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final MissenseReferenceDraftPreparation facts = new MissenseReferenceDraftPreparation();

    MissenseReferenceReviewMapping(ObjectMapper mapper) {
        this.mapper = mapper;
        adapter = new RuntimeEvidenceV2Adapter(mapper);
    }

    record Mapping(ObjectNode referenceAudit, ObjectNode rnaReview, ObjectNode selectionMap) { }

    Mapping map(ObjectNode referenceDraft, ObjectNode rnaDraft, ObjectNode draftReport,
                ObjectNode review, ObjectNode background, Set<String> registeredSourceIds,
                Set<String> evidenceSourceIds) throws IOException {
        String draftId = text(referenceDraft, "draftId");
        require(draftId.equals(text(rnaDraft, "draftId")) && draftId.equals(text(draftReport, "draftId")), "DRAFT_ID_MISMATCH");
        draftHeader(referenceDraft, "missense-reference-audit-draft-v1");
        draftHeader(rnaDraft, "missense-rna-pairs-draft-v1");
        draftHeader(draftReport, "missense-reference-draft-report-v1");
        require("DRAFT_GENERATED".equals(draftReport.path("extractionStatus").asText()), "DRAFT_REPORT_NOT_GENERATED");
        require("NOT_PROVIDED".equals(rnaDraft.path("rnaEvidenceStatus").asText()), "RNA_DRAFT_STATE_INVALID");
        require(referenceDraft.path("dataClass").equals(rnaDraft.path("dataClass"))
                && referenceDraft.path("dataClass").equals(draftReport.path("dataClass")), "DRAFT_DATA_CLASS_MISMATCH");
        require(background.equals(review.path("background")), "REVIEW_BACKGROUND_MISMATCH");
        ObjectNode context = object(referenceDraft.path("context"), "DRAFT_CONTEXT_REQUIRED");
        ObjectNode target = object(referenceDraft.path("target"), "DRAFT_TARGET_REQUIRED");
        String transcript = facts.transcript(context.path("transcript"));
        ObjectNode targetAllele = facts.allele(context.at("/variant/evidenceQueryAllele"), false);
        validateContext(referenceDraft, context, target, background, transcript, targetAllele);
        ObjectNode search = object(draftReport.path("sourceSearch"), "SOURCE_SEARCH_REQUIRED");
        require(ClinVarConditionAwareComparatorInput.RESULT_SCHEMA_VERSION.equals(search.path("schemaVersion").asText())
                && context.path("contextId").equals(search.path("contextId"))
                && targetAllele.equals(facts.allele(search.path("targetAllele"), true)), "SOURCE_SEARCH_CONTEXT_MISMATCH");
        require(Set.of("READY", "NO_RECORD", "NOT_EVALUATED").contains(text(search, "status")), "SOURCE_SEARCH_STATUS_INVALID");
        bounded(search.path("comparators"), 256, "SOURCE_COMPARATOR_LIMIT_OR_ARRAY_INVALID");
        bounded(search.path("candidateDecisions"), 256, "SOURCE_DECISION_LIMIT_OR_ARRAY_INVALID");
        bounded(draftReport.path("gaps"), 8192, "DRAFT_GAPS_INVALID");
        Map<String, ObjectNode> references = validateReferences(referenceDraft, search, draftId, transcript, target, context);
        Map<String, ObjectNode> pairs = validatePairs(rnaDraft, draftId, transcript, targetAllele, references);
        require(draftReport.path("referenceCount").isIntegralNumber() && draftReport.path("referenceCount").asInt() == references.size()
                && draftReport.path("pairCount").isIntegralNumber() && draftReport.path("pairCount").asInt() == pairs.size(), "DRAFT_REPORT_COUNTS_MISMATCH");

        ObjectNode audit = object(review.path("referenceAudit"), "REFERENCE_REVIEW_REQUIRED").deepCopy();
        require(MissenseComparisonPolicy.only(audit, Set.of("status", "reviewId", "sourceIds")), "REFERENCE_AUDIT_FIELDS_UNSUPPORTED");
        require(Set.of("REVIEWED", "SOURCE_VERIFIED").contains(text(audit, "status")), "REFERENCE_REVIEW_STATUS_INVALID");
        text(audit, "reviewId"); sources(audit.path("sourceIds"), registeredSourceIds, evidenceSourceIds, false);
        audit.put("sourceDraftId", draftId);
        ArrayNode adopted = audit.putArray("references");
        ObjectNode selectionMap = mapper.createObjectNode().put("schemaVersion", "missense-reference-review-selection-map-v1").put("draftId", draftId);
        ArrayNode selectedReferences = selectionMap.putArray("references");
        bounded(review.path("references"), 256, "REFERENCE_SELECTION_LIMIT_OR_ARRAY_INVALID");
        Set<String> selections = new HashSet<>(), adoptedIds = new HashSet<>(), materialIdentities = new HashSet<>();
        for (JsonNode selected : review.path("references")) {
            require(MissenseComparisonPolicy.only(selected, Set.of("referenceDraftId", "assertionDraftId", "decision", "reason", "review")), "REFERENCE_SELECTION_FIELDS_UNSUPPORTED");
            String referenceId = text(selected, "referenceDraftId"), assertionId = text(selected, "assertionDraftId");
            require(selections.add(referenceId + ":" + assertionId), "REFERENCE_SELECTION_DUPLICATE");
            ObjectNode reference = references.get(referenceId);
            require(reference != null, "REFERENCE_SELECTION_UNKNOWN");
            if (reference == null) throw new IllegalArgumentException("REFERENCE_SELECTION_UNKNOWN");
            JsonNode assertion = null;
            for (JsonNode candidate : reference.path("assertions")) if (assertionId.equals(candidate.path("assertionDraftId").asText())) assertion = candidate.path("sourceAssertion");
            require(assertion != null, "ASSERTION_SELECTION_UNKNOWN");
            if (assertion == null) throw new IllegalArgumentException("ASSERTION_SELECTION_UNKNOWN");
            String decision = text(selected, "decision");
            require(Set.of("ADOPT", "DO_NOT_ADOPT").contains(decision), "REFERENCE_DECISION_INVALID");
            ObjectNode selectedRecord = selectedReferences.addObject().put("referenceDraftId", referenceId).put("assertionDraftId", assertionId).put("decision", decision);
            if ("DO_NOT_ADOPT".equals(decision)) { selectedRecord.put("reason", text(selected, "reason")); continue; }
            if (selected.has("reason")) selectedRecord.put("reason", text(selected, "reason"));
            require(adoptedIds.add(referenceId), "REFERENCE_MATERIAL_IDENTITY_DUPLICATE");
            String scv = text(assertion, "scv");
            require(scv.matches("SCV[0-9]+\\.[1-9][0-9]*"), "SOURCE_SCV_VERSION_REQUIRED");
            String date = ClinVarAssertionDate.iso(text(assertion, "dateLastEvaluated"));
            require(!date.isEmpty(), "SOURCE_EVALUATION_DATE_INVALID");
            require(materialIdentities.add(adapter.canonicalSha256(reference.path("allele")) + ":" + text(reference, "variationId") + ":" + scv), "REFERENCE_MATERIAL_IDENTITY_DUPLICATE");
            ObjectNode declaration = object(selected.path("review"), "REFERENCE_SCIENTIFIC_REVIEW_REQUIRED");
            validateReferenceReview(declaration, reference, assertion, background, registeredSourceIds, evidenceSourceIds);
            require(!"SOURCE_VERIFIED".equals(audit.path("status").asText())
                    || MissenseExpertClassificationAdoption.sourceVerified(declaration), "AUTOMATED_REFERENCE_ROUTE_REQUIRED");
            ObjectNode material = declaration.deepCopy();
            material.put("referenceId", referenceId).put("sourceReferenceDraftId", referenceId).put("sourceAssertionDraftId", assertionId);
            for (String field : List.of("allele", "variationId", "annotation")) material.set(field, reference.path(field).deepCopy());
            material.set("scv", assertion.path("scv").deepCopy()); material.put("evaluationDate", date);
            material.set("sourceAssertion", assertion.deepCopy());
            adopted.add(material); selectedRecord.put("referenceId", referenceId).put("scv", scv);
        }

        ObjectNode rna = object(review.path("rnaReview"), "RNA_REVIEW_REQUIRED").deepCopy();
        require(MissenseComparisonPolicy.only(rna, Set.of("status", "rnaEffect", "normalRnaClaimed", "reviewId", "sourceIds", "knownConcerns", "mode", "sourceCoverage")), "RNA_REVIEW_FIELDS_UNSUPPORTED");
        rnaReview(rna, registeredSourceIds, evidenceSourceIds);
        require("UNKNOWN".equals(text(rna, "rnaEffect")) && rna.path("normalRnaClaimed").isBoolean()
                && !rna.path("normalRnaClaimed").asBoolean(), "RNA_REVIEW_EFFECT_INVALID");
        rna.put("sourceDraftId", draftId);
        ArrayNode comparisons = rna.putArray("comparisons"), selectedPairs = selectionMap.putArray("rnaComparisons");
        bounded(review.path("rnaComparisons"), 1024, "RNA_SELECTION_LIMIT_OR_ARRAY_INVALID");
        boolean automatedRna = MissenseRnaApplicability.automated(rna);
        require(!automatedRna || review.path("rnaComparisons").isEmpty(), "AUTOMATED_RNA_PAIR_DECLARATIONS_FORBIDDEN");
        Set<String> pairIds = new HashSet<>(), covered = new HashSet<>();
        for (JsonNode selected : review.path("rnaComparisons")) {
            require(MissenseComparisonPolicy.only(selected, Set.of("pairDraftId", "review")), "RNA_SELECTION_FIELDS_UNSUPPORTED");
            String pairId = text(selected, "pairDraftId");
            require(pairIds.add(pairId), "RNA_SELECTION_DUPLICATE");
            ObjectNode pair = pairs.get(pairId);
            require(pair != null, "RNA_SELECTION_UNKNOWN");
            if (pair == null) throw new IllegalArgumentException("RNA_SELECTION_UNKNOWN");
            boolean selectedReference = false;
            for (JsonNode id : pair.path("referenceDraftIds")) if (adoptedIds.contains(id.asText())) { selectedReference = true; covered.add(id.asText()); }
            require(selectedReference, "RNA_PAIR_WITHOUT_ADOPTED_REFERENCE");
            ObjectNode declaration = object(selected.path("review"), "RNA_PAIR_REVIEW_REQUIRED");
            require(MissenseComparisonPolicy.only(declaration, Set.of("status", "reviewId", "sourceIds", "knownConcerns", "targetAllele", "referenceAllele", "transcript")), "RNA_PAIR_REVIEW_FIELDS_UNSUPPORTED");
            for (String field : List.of("targetAllele", "referenceAllele", "transcript")) redundant(declaration, field, pair.path(field));
            rnaReview(declaration, registeredSourceIds, evidenceSourceIds);
            ObjectNode comparison = declaration.deepCopy().put("sourcePairDraftId", pairId);
            for (String field : List.of("targetAllele", "referenceAllele", "transcript")) comparison.set(field, pair.path(field).deepCopy());
            comparisons.add(comparison);
            selectedPairs.addObject().put("pairDraftId", pairId).set("referenceDraftIds", pair.path("referenceDraftIds").deepCopy());
        }
        require(automatedRna || covered.equals(adoptedIds), "RNA_REVIEW_MISSING_FOR_ADOPTED_REFERENCE");
        return new Mapping(audit, rna, selectionMap);
    }

    private void validateContext(ObjectNode referenceDraft, ObjectNode context, ObjectNode target, ObjectNode background,
                                 String transcript, ObjectNode targetAllele) throws IOException {
        require("evidence-context-v2".equals(context.path("schemaVersion").asText())
                && referenceDraft.path("dataClass").equals(context.path("dataClass"))
                && text(context, "contextId").equals(adapter.canonicalContextId(context)), "DRAFT_CONTEXT_IDENTITY_INVALID");
        require("NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                && targetAllele.equals(facts.allele(context.at("/variant/inputAllele"), false))
                && targetAllele.equals(facts.allele(context.at("/variant/normalizedAllele"), false))
                && context.at("/variant/alleleId").asText().equals(adapter.canonicalAlleleId(targetAllele, context.at("/variant/normalization"))), "DRAFT_CONTEXT_ALLELE_INVALID");
        require("missense-target-annotation-snapshot-v1".equals(target.path("schemaVersion").asText())
                && context.path("contextId").equals(target.path("contextId")) && targetAllele.equals(facts.allele(target.path("allele"), false))
                && transcript.equals(text(target, "transcript")), "DRAFT_TARGET_CONTEXT_MISMATCH");
        for (String[] binding : List.of(new String[]{"hgncId", "/gene/hgncId"}, new String[]{"geneSymbol", "/gene/symbol"},
                new String[]{"mondoId", "/disease/mondoId"}, new String[]{"moi", "/moi"}))
            require(text(background, binding[0]).equals(context.at(binding[1]).asText()), "DRAFT_BACKGROUND_MISMATCH");
        require(text(background, "assembly").equals(targetAllele.path("assembly").asText())
                && text(background, "contig").equals(targetAllele.path("contig").asText())
                && text(background, "transcript").equals(transcript), "DRAFT_BACKGROUND_MISMATCH");
        ObjectNode annotation = object(target.path("annotation"), "TARGET_ANNOTATION_REQUIRED");
        TranscriptConsequence parsed = facts.annotation(annotation, transcript, target.path("source"), target.path("source"));
        require(text(background, "geneSymbol").equals(parsed.getGeneSymbol())
                && text(annotation, "hgvsProtein").startsWith(text(background, "protein") + ":"), "DRAFT_BACKGROUND_ANNOTATION_MISMATCH");
        if (MissenseComparisonPolicy.text(context.at("/transcript/annotationSourceVersion")))
            require(context.at("/transcript/annotationSourceVersion").asText().equals(parsed.getVersion()), "DRAFT_TARGET_VERSION_MISMATCH");
    }

    private Map<String, ObjectNode> validateReferences(ObjectNode draft, ObjectNode search, String draftId, String transcript,
                                                      ObjectNode target, ObjectNode context) throws IOException {
        bounded(draft.path("references"), 256, "REFERENCE_DRAFT_LIMIT_OR_ARRAY_INVALID");
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        Set<Integer> observations = new HashSet<>();
        String searchHash = adapter.canonicalSha256(search);
        TranscriptConsequence targetAnnotation = facts.annotation(object(target.path("annotation"), "TARGET_ANNOTATION_REQUIRED"), transcript, target.path("source"), target.path("source"));
        int assertionCount = 0, sourceAssertionCount = 0, sourceConditionCount = 0;
        for (JsonNode value : draft.path("references")) {
            ObjectNode reference = object(value, "REFERENCE_DRAFT_OBJECT_REQUIRED");
            String id = text(reference, "referenceDraftId");
            String key = facts.key(facts.allele(reference.path("allele"), false), facts.variation(reference), transcript);
            require(id.equals(itemId(draftId, key)) && transcript.equals(text(reference, "transcript")), "REFERENCE_DRAFT_IDENTITY_INVALID");
            require(result.putIfAbsent(id, reference) == null, "REFERENCE_DRAFT_ID_DUPLICATE");
            require("UNREVIEWED".equals(reference.path("reviewStatus").asText()), "REFERENCE_DRAFT_STATE_INVALID");
            require(reference.at("/allele/contig").equals(context.at("/variant/evidenceQueryAllele/contig")), "REFERENCE_CONTIG_MISMATCH");
            TranscriptConsequence referenceAnnotation = null;
            if (reference.has("annotation")) {
                referenceAnnotation = facts.annotation(object(reference.path("annotation"), "REFERENCE_ANNOTATION_REQUIRED"), transcript, target.path("source"), target.path("source"));
                require(context.at("/gene/symbol").asText().equals(referenceAnnotation.getGeneSymbol()), "REFERENCE_ANNOTATION_GENE_MISMATCH");
            }
            bounded(reference.path("observations"), 256, "REFERENCE_OBSERVATIONS_INVALID");
            require(!reference.path("observations").isEmpty(), "REFERENCE_OBSERVATIONS_MISSING");
            Set<String> assertionHashes = new HashSet<>();
            for (JsonNode observation : reference.path("observations")) {
                String pointer = text(observation.path("origin"), "jsonPointer");
                require(pointer.matches("/comparators/(?:0|[1-9][0-9]*)"), "OBSERVATION_POINTER_INVALID");
                int index;
                try { index = Integer.parseInt(pointer.substring("/comparators/".length())); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException("OBSERVATION_POINTER_INVALID", invalid); }
                require(index < search.path("comparators").size() && observations.add(index), "OBSERVATION_BINDING_DUPLICATE_OR_UNKNOWN");
                JsonNode record = search.at(pointer);
                require(observation.path("record").equals(record), "SOURCE_SEARCH_OBSERVATION_MISMATCH");
                require(ClinVarConditionAwareComparatorInput.RECORD_SCHEMA_VERSION.equals(record.path("schemaVersion").asText())
                        && context.path("contextId").equals(record.path("targetContextId"))
                        && reference.path("allele").equals(facts.allele(record.path("exactAllele"), true))
                        && reference.path("variationId").equals(record.path("variationId")), "SOURCE_SEARCH_REFERENCE_MISMATCH");
                origin(observation.path("origin"), searchHash);
                facts.checkProtein(record.path("targetProteinChange"), targetAnnotation, transcript);
                require(record.path("selectedTargetConsequence").asText().equals(targetAnnotation.getConsequence()), "SOURCE_TARGET_CONSEQUENCE_MISMATCH");
                if (referenceAnnotation != null) {
                    facts.checkProtein(record.path("comparatorProteinChange"), referenceAnnotation, transcript);
                    require(record.path("selectedComparatorConsequence").asText().equals(referenceAnnotation.getConsequence()), "SOURCE_REFERENCE_CONSEQUENCE_MISMATCH");
                    var relation = new VepProteinRelationClassifier().classify(targetAnnotation, referenceAnnotation);
                    require(relation.isEvaluated() && record.path("proteinRelation").asText().equals(String.valueOf(relation.getRelation())), "SOURCE_PROTEIN_RELATION_MISMATCH");
                }
                bounded(record.path("submissions"), 1024, "SOURCE_ASSERTIONS_INVALID");
                bounded(record.path("variantSummaryRecords"), 1024, "SOURCE_CONDITIONS_INVALID");
                sourceAssertionCount += record.path("submissions").size(); sourceConditionCount += record.path("variantSummaryRecords").size();
                require(sourceAssertionCount <= 1024 && sourceConditionCount <= 1024, "SOURCE_ASSERTION_OR_CONDITION_TOTAL_LIMIT");
                for (JsonNode assertion : record.path("submissions")) assertionHashes.add(adapter.canonicalSha256(object(assertion, "SOURCE_ASSERTION_OBJECT_REQUIRED")));
                boolean decisionBound = false;
                for (JsonNode decision : search.path("candidateDecisions")) if (record.path("exactAllele").equals(decision.path("exactAllele"))
                        && record.path("variationId").equals(decision.path("variationId")) && "READY".equals(decision.path("status").asText())
                        && record.path("snapshotProvenance").equals(decision.path("snapshotProvenance"))) decisionBound = true;
                require(decisionBound, "SOURCE_SEARCH_DECISION_MISMATCH");
            }
            bounded(reference.path("assertions"), 1024, "ASSERTION_DRAFT_LIMIT_OR_ARRAY_INVALID");
            assertionCount += reference.path("assertions").size(); require(assertionCount <= 1024, "ASSERTION_DRAFT_TOTAL_LIMIT");
            Set<String> actualHashes = new HashSet<>();
            for (JsonNode assertion : reference.path("assertions")) {
                String hash = adapter.canonicalSha256(object(assertion.path("sourceAssertion"), "SOURCE_ASSERTION_OBJECT_REQUIRED"));
                require(actualHashes.add(hash) && itemId(draftId, key + hash).equals(text(assertion, "assertionDraftId")), "ASSERTION_DRAFT_IDENTITY_INVALID");
                require("UNREVIEWED".equals(assertion.path("reviewStatus").asText()), "ASSERTION_DRAFT_STATE_INVALID");
                bounded(assertion.path("origins"), 1024, "ASSERTION_ORIGINS_INVALID");
                require(!assertion.path("origins").isEmpty(), "ASSERTION_ORIGINS_MISSING");
                Set<String> actualPointers = new HashSet<>(), expectedPointers = new HashSet<>();
                for (JsonNode observation : reference.path("observations")) {
                    int index = 0;
                    for (JsonNode submission : observation.at("/record/submissions")) {
                        if (submission.equals(assertion.path("sourceAssertion"))) expectedPointers.add(observation.at("/origin/jsonPointer").asText() + "/submissions/" + index);
                        index++;
                    }
                }
                for (JsonNode origin : assertion.path("origins")) {
                    String pointer = text(origin, "jsonPointer");
                    require(actualPointers.add(pointer) && pointer.matches("/comparators/(?:0|[1-9][0-9]*)/submissions/(?:0|[1-9][0-9]*)")
                            && search.at(pointer).equals(assertion.path("sourceAssertion")), "ASSERTION_ORIGIN_MISMATCH");
                    origin(origin, searchHash);
                }
                require(actualPointers.equals(expectedPointers), "ASSERTION_ORIGIN_SET_MISMATCH");
            }
            require(actualHashes.equals(assertionHashes), "SOURCE_ASSERTION_SET_MISMATCH");
        }
        require(observations.size() == search.path("comparators").size(), "SOURCE_SEARCH_COMPARATORS_UNBOUND");
        for (JsonNode decision : search.path("candidateDecisions")) {
            ObjectNode allele = facts.allele(decision.path("exactAllele"), true);
            require(allele.path("contig").equals(context.at("/variant/evidenceQueryAllele/contig")), "SOURCE_DECISION_CONTIG_MISMATCH");
            String id = itemId(draftId, facts.key(allele, facts.variation(decision), transcript));
            if ("READY".equals(text(decision, "status"))) require(result.containsKey(id), "SOURCE_READY_DECISION_WITHOUT_REFERENCE");
        }
        return result;
    }

    private Map<String, ObjectNode> validatePairs(ObjectNode draft, String draftId, String transcript, ObjectNode targetAllele,
                                                 Map<String, ObjectNode> references) {
        bounded(draft.path("pairs"), 1024, "RNA_DRAFT_LIMIT_OR_ARRAY_INVALID");
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        Set<String> covered = new HashSet<>();
        for (JsonNode value : draft.path("pairs")) {
            ObjectNode pair = object(value, "RNA_DRAFT_OBJECT_REQUIRED");
            String id = text(pair, "pairDraftId");
            ObjectNode referenceAllele = facts.allele(pair.path("referenceAllele"), false);
            require(id.equals(itemId(draftId, adapter.canonicalSha256(referenceAllele))) && targetAllele.equals(pair.path("targetAllele"))
                    && transcript.equals(text(pair, "transcript")), "RNA_DRAFT_IDENTITY_INVALID");
            require(result.putIfAbsent(id, pair) == null, "RNA_DRAFT_ID_DUPLICATE");
            require("UNREVIEWED".equals(pair.path("reviewStatus").asText()) && "NOT_PROVIDED".equals(pair.path("rnaEvidenceStatus").asText()), "RNA_DRAFT_STATE_INVALID");
            bounded(pair.path("referenceDraftIds"), 256, "RNA_DRAFT_REFERENCES_INVALID");
            Set<String> actual = new HashSet<>(), expected = new HashSet<>();
            references.forEach((referenceId, reference) -> { if (reference.path("allele").equals(referenceAllele)) expected.add(referenceId); });
            for (JsonNode referenceId : pair.path("referenceDraftIds")) require(referenceId.isTextual() && actual.add(referenceId.asText()), "RNA_DRAFT_REFERENCE_ID_DUPLICATE");
            require(!actual.isEmpty() && actual.equals(expected), "RNA_DRAFT_REFERENCE_BINDING_MISMATCH");
            covered.addAll(actual);
        }
        require(covered.equals(references.keySet()), "RNA_DRAFT_REFERENCE_UNBOUND");
        return result;
    }

    private void validateReferenceReview(ObjectNode review, ObjectNode reference, JsonNode assertion, ObjectNode background,
                                          Set<String> registered, Set<String> evidence) {
        require(MissenseComparisonPolicy.only(review, Set.of("reviewId", "classification", "assertionStatus", "conflictState", "hgncId", "geneSymbol", "mondoId", "moi", "sourceIds", "independence",
                "allele", "annotation", "variationId", "scv", "evaluationDate", "sourceAssertion", "conditionMappings", "qualification", "expertEvidenceReferenceId", "expertClassificationAdoption", "originalSpecificationBinding")), "REFERENCE_REVIEW_FIELDS_UNSUPPORTED");
        if (review.has("expertEvidenceReferenceId")) text(review, "expertEvidenceReferenceId");
        for (String field : List.of("reviewId", "classification", "assertionStatus", "conflictState")) text(review, field);
        for (String field : List.of("hgncId", "geneSymbol", "mondoId", "moi")) require(text(review, field).equals(text(background, field)), "REFERENCE_REVIEW_BACKGROUND_MISMATCH");
        for (String field : List.of("allele", "annotation", "variationId")) redundant(review, field, reference.path(field));
        redundant(review, "scv", assertion.path("scv")); redundant(review, "sourceAssertion", assertion);
        if (review.has("evaluationDate")) require(review.path("evaluationDate").equals(assertion.path("dateLastEvaluated"))
                || (review.path("evaluationDate").isTextual() && review.path("evaluationDate").asText().equals(
                        ClinVarAssertionDate.iso(assertion.path("dateLastEvaluated").asText()))), "REDUNDANT_SOURCE_FACT_CONFLICT");
        require(reference.path("annotation").isObject(), "SELECTED_REFERENCE_ANNOTATION_MISSING");
        sources(review.path("sourceIds"), registered, evidence, false);
        if (review.has("conditionMappings")) {
            var policySources = mapper.createObjectNode(); var declared = policySources.putArray("sources");
            for (String id : registered) declared.addObject().put("sourceId", id);
            String mappingIssue = MissenseConditionMapping.issue(review.path("conditionMappings"), policySources);
            require(mappingIssue == null, mappingIssue == null ? "REFERENCE_CONDITION_MAPPING_INVALID" : mappingIssue);
            for (JsonNode row : review.path("conditionMappings")) sources(row.path("sourceIds"), registered, evidence, true);
            require(text(background, "mondoId").equals(MissenseConditionMapping.resolve(
                    assertion.at("/condition/submittedRaw").asText(), assertion.at("/condition/reportedRaw").asText(), review.path("conditionMappings"))),
                    "REFERENCE_CONDITION_MAPPING_SOURCE_MISMATCH");
        }
        if (MissenseExpertClassificationAdoption.sourceVerified(review)) {
            require(!review.has("independence") && review.has("expertEvidenceReferenceId") && !review.has("qualification"),
                    "AUTOMATED_REFERENCE_REQUIRES_SOURCE_DERIVATION");
            return; // The original bridge derives and verifies it from the separately pinned expert packet.
        }
        ObjectNode independence = object(review.path("independence"), "INDEPENDENCE_REVIEW_REQUIRED");
        require(MissenseComparisonPolicy.only(independence, Set.of("status", "reviewId", "originalSpecificationVersion", "classificationAfterExclusion", "dependencyReview", "criteria", "dependencyAlleles", "sourceIds")), "INDEPENDENCE_REVIEW_FIELDS_UNSUPPORTED");
        for (String field : List.of("status", "reviewId", "originalSpecificationVersion", "classificationAfterExclusion", "dependencyReview")) text(independence, field);
        bounded(independence.path("criteria"), 256, "INDEPENDENCE_CRITERIA_REQUIRED");
        require(!independence.path("criteria").isEmpty(), "INDEPENDENCE_CRITERIA_REQUIRED");
        for (JsonNode criterion : independence.path("criteria")) require(MissenseComparisonPolicy.text(criterion) || MissenseComparisonPolicy.text(criterion.path("code")), "INDEPENDENCE_CRITERION_INVALID");
        bounded(independence.path("dependencyAlleles"), 256, "INDEPENDENCE_DEPENDENCY_ALLELES_REQUIRED");
        for (JsonNode allele : independence.path("dependencyAlleles")) facts.allele(allele, false);
        sources(independence.path("sourceIds"), registered, evidence, true);
        if (review.has("qualification")) {
            JsonNode qualification = review.path("qualification");
            require(adapter.canonicalSha256(assertion).equals(qualification.path("sourceAssertionSha256").asText()),
                    "REFERENCE_INDEPENDENT_ASSERTION_BINDING_MISMATCH");
            sources(qualification.path("sourceIds"), registered, evidence, true);
            bounded(qualification.path("evidence"), 11, "REFERENCE_INDEPENDENT_EVIDENCE_INCOMPLETE");
            for (JsonNode item : qualification.path("evidence")) sources(item.path("sourceIds"), registered, evidence, true);
        }
    }

    private void rnaReview(ObjectNode review, Set<String> registered, Set<String> evidence) {
        boolean automated = MissenseRnaApplicability.automated(review);
        require((automated ? MissenseRnaApplicability.STATUS : "REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED").equals(text(review, "status")), "RNA_REVIEW_STATUS_INVALID");
        require(automated ? MissenseRnaApplicability.COVERAGE.equals(review.path("sourceCoverage").asText())
                : !review.has("mode") && !review.has("sourceCoverage"), "RNA_SOURCE_COVERAGE_INVALID");
        text(review, "reviewId"); sources(review.path("sourceIds"), registered, evidence, true);
        bounded(review.path("knownConcerns"), 1024, "RNA_KNOWN_CONCERNS_REQUIRED");
        if (automated) for (JsonNode concern : review.path("knownConcerns")) {
            facts.allele(concern.path("allele"), false); text(concern, "reason");
            sources(concern.path("sourceIds"), registered, evidence, true);
        }
    }

    private static void sources(JsonNode ids, Set<String> registered, Set<String> evidence, boolean requireEvidence) {
        bounded(ids, 64, "REVIEW_SOURCE_IDS_INVALID");
        require(!ids.isEmpty(), "REVIEW_SOURCE_IDS_REQUIRED");
        Set<String> seen = new HashSet<>(); boolean hasEvidence = false;
        for (JsonNode id : ids) {
            require(MissenseComparisonPolicy.text(id) && seen.add(id.asText()) && registered.contains(id.asText()), "REVIEW_SOURCE_ID_UNBOUND_OR_DUPLICATE");
            hasEvidence |= evidence.contains(id.asText());
        }
        require(!requireEvidence || hasEvidence, "SCIENTIFIC_REVIEW_EVIDENCE_SOURCE_REQUIRED");
    }

    private static void origin(JsonNode value, String searchHash) {
        require("comparatorInput".equals(value.path("inputRole").asText()) && MissenseComparisonPolicy.hash(value.path("rawSha256"))
                && searchHash.equals(value.path("canonicalSha256").asText()), "SOURCE_ORIGIN_IDENTITY_MISMATCH");
    }

    private String itemId(String draftId, String identity) {
        return "draft-item-sha256-" + adapter.canonicalSha256(mapper.createObjectNode().put("snapshot", draftId).put("identity", identity));
    }

    private static void draftHeader(ObjectNode value, String schema) {
        require(schema.equals(value.path("schemaVersion").asText()) && value.path("draft").isBoolean() && value.path("draft").asBoolean()
                && "UNREVIEWED".equals(value.path("reviewStatus").asText()), "DRAFT_SCHEMA_OR_STATE_INVALID");
    }

    private static void redundant(JsonNode review, String field, JsonNode fact) {
        require(!review.has(field) || review.path(field).equals(fact), "REDUNDANT_SOURCE_FACT_CONFLICT");
    }

    private static void bounded(JsonNode value, int limit, String reason) { require(value.isArray() && value.size() <= limit, reason); }
    private static ObjectNode object(JsonNode value, String reason) { require(value instanceof ObjectNode, reason); return (ObjectNode) value; }
    private static String text(JsonNode value, String field) { require(MissenseComparisonPolicy.text(value.path(field)), "REQUIRED_FIELD_" + field); return value.path(field).asText(); }
    private static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
}
