package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Explicit business successor. Reference audit, target identity and both splice checks precede MET. */
public final class ClinVarMissenseBusinessRuntime {
    public static final String PROFILE_RESOURCE = "/acmg/clinvar-missense-business-profile-v2.json";
    public static final String ANNOTATION_PROOF_RESOURCE = "/acmg/spliceai-braf-mane-window-equivalence-v1.json";
    public static final String AUDIT_RESOURCE = "/acmg/clinvar-missense-braf-reference-audit-v2.json";
    public static final String RNA_REVIEW_RESOURCE = "/acmg/braf-missense-rna-review-v1.json";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final ClinVarConditionAwareComparatorInput comparator;
    private final Function<List<SpliceAiRawBatchRunner.Query>, SpliceAiRawBatchRunner.BatchResult> splice;
    private final ObjectNode policy;
    private final ObjectNode audit;

    public ClinVarMissenseBusinessRuntime(ObjectMapper mapper, ClinVarConditionAwareComparatorInput comparator,
                                          SpliceAiRawBatchRunner runner) {
        this(mapper, comparator, runner == null ? null : runner::run, loadProfile(mapper), loadAudit(mapper));
    }

    ClinVarMissenseBusinessRuntime(ObjectMapper mapper, ClinVarConditionAwareComparatorInput comparator,
                                   Function<List<SpliceAiRawBatchRunner.Query>, SpliceAiRawBatchRunner.BatchResult> splice,
                                   ObjectNode policy, ObjectNode audit) {
        this.mapper = Objects.requireNonNull(mapper); this.adapter = new RuntimeEvidenceV2Adapter(mapper);
        this.comparator = comparator; this.splice = splice;
        this.policy = Objects.requireNonNull(policy).deepCopy(); this.audit = Objects.requireNonNull(audit).deepCopy();
    }

    public static ObjectNode loadProfile(ObjectMapper mapper) {
        verifyArtifact(PROFILE_RESOURCE, "82955ffdcac348f61e77e61e3a09093fcc1aa4a6c4e4a7c51a21e930fe01bda2");
        ObjectNode result = read(mapper, PROFILE_RESOURCE);
        ObjectNode rawAudit = read(mapper, AUDIT_RESOURCE);
        verifyArtifact(AUDIT_RESOURCE, result.at("/referencePolicy/auditSha256").asText());
        verifyArtifact(ANNOTATION_PROOF_RESOURCE, result.at("/splicePolicy/annotationEquivalenceSha256").asText());
        verifyArtifact(ClinVarMissenseBusinessArtifacts.REGISTRY_RESOURCE, result.at("/registryBinding/registrySha256").asText());
        verifyArtifact(ClinVarMissenseBusinessArtifacts.AUTHORITY_RESOURCE, result.at("/scientificArtifactBinding/authoritySha256").asText());
        verifyArtifact(ClinVarMissenseBusinessArtifacts.OVERLAY_RESOURCE, result.at("/scientificArtifactBinding/overlaySha256").asText());
        verifyArtifact("/acmg/cspec-gn049-v2.3-snapshot.json", result.at("/scientificArtifactBinding/cspecJsonSha256").asText());
        verifyArtifact("/acmg/cspec-gn049-v2.3-specification.pdf", result.at("/scientificArtifactBinding/cspecPdfSha256").asText());
        for (JsonNode reference : rawAudit.path("references")) verifyArtifact(reference.path("erepoSnapshotResource").asText(), reference.path("erepoSnapshotSha256").asText());
        for (JsonNode relation : rawAudit.at("/pm5PhysicochemicalReview/reviewedRelations")) verifyArtifact(relation.path("snapshotResource").asText(), relation.path("snapshotSha256").asText());
        verifyArtifact(RNA_REVIEW_RESOURCE, result.at("/rnaReviewPolicy/snapshotSha256").asText());
        result.put("referenceAuditSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(rawAudit));
        result.set("annotationEquivalence", read(mapper, ANNOTATION_PROOF_RESOURCE));
        result.set("rnaEvidenceReview", read(mapper, RNA_REVIEW_RESOURCE));
        return result;
    }
    private static void verifyArtifact(String resource, String expected) {
        if (expected.length() != 64 || !expected.equals(ClinVarMissenseBusinessArtifacts.resourceSha(resource)))
            throw new IllegalStateException("Missense scientific resource identity mismatch: " + resource);
    }
    static ObjectNode loadAudit(ObjectMapper mapper) {
        ObjectNode document = read(mapper, AUDIT_RESOURCE);
        for (JsonNode value : document.path("references")) {
            ObjectNode ref = (ObjectNode) value;
            ref.put("scv", ref.path("scvAccession").asText()).put("vcv", ref.path("vcvAccessionAtReview").asText());
            ref.put("geneSymbol", "BRAF").put("mondoId", ref.path("erepoDiseaseMondoId").asText());
            ref.put("transcriptId", "ENST00000646891.2").put("proteinChange", ref.path("proteinHgvs").asText());
            ref.put("independentEvidenceStatus", ref.path("independentPathogenicClassification").asBoolean() ? "VERIFIED" : "UNVERIFIED");
            ref.put("independentCriteriaSufficient", ref.path("independentPathogenicClassification").asBoolean());
            ref.set("independentCriteria", ref.path("appliedCriteria").deepCopy());
            ref.put("independentEvidenceReason", ref.path("independenceBasis").asText());
            ref.putObject("allele").put("assembly", ref.path("assembly").asText()).put("contig", ref.path("chromosome").asText())
                    .put("position", ref.path("position").asInt()).put("reference", ref.path("referenceAllele").asText()).put("alternate", ref.path("alternateAllele").asText());
            ref.put("referenceAminoAcid", aminoAcid(ref.path("referenceAminoAcid").asText()));
            ref.put("alternateAminoAcid", aminoAcid(ref.path("alternateAminoAcid").asText()));
        }
        return document;
    }
    private static String aminoAcid(String value) { return switch (value) {
        case "Phe" -> "F"; case "Leu" -> "L"; case "Val" -> "V";
        case "Thr" -> "T"; case "Arg" -> "R"; case "Lys" -> "K";
        default -> value;
    }; }
    public static ObjectNode loadRegistry(ObjectMapper mapper) { return read(mapper, ClinVarMissenseBusinessArtifacts.REGISTRY_RESOURCE); }

    public ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, TranscriptConsequence selected) {
        if (Hnf1aPs1Pm5Runtime.matchesContext(context))
            return new Hnf1aPs1Pm5Runtime(mapper, comparator, splice).evaluate(context, candidate, selected);
        String reason = scopeReason(context, selected);
        if (reason != null) return disposition(mapper, context, reason,
                reason.startsWith("OUTSIDE_") ? "NOT_APPLICABLE" : "UNKNOWN");
        if (comparator == null) return disposition(mapper, context, "COMPARATOR_INPUT_UNAVAILABLE", "UNKNOWN");
        ObjectNode result = base(context);
        List<ObjectNode> atomics;
        try {
            ClinVarConditionAwareComparatorInput.Result input = comparator.build(context, candidate, selected);
            result.set("comparatorInput", mapper.valueToTree(input));
            result.put("providerCalls", 1);
            atomics = new RuntimeEvidenceV2ProviderWiring(mapper, adapter).projectClinVarComparatorEvidence(
                    context, input, ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper));
            if (!"READY".equals(input.getStatus()) && !"NO_RECORD".equals(input.getStatus())) {
                return finish(result, atomics, blocked(input.getReasonCode() == null
                        ? "COMPARATOR_INPUT_UNAVAILABLE" : input.getReasonCode(), "UNKNOWN"));
            }
        } catch (RuntimeException failure) {
            result.put("providerCalls", 1);
            return finish(result, List.of(), blocked("COMPARATOR_INPUT_UNAVAILABLE", "UNKNOWN"));
        }
        return assess(result, atomics, selected);
    }

    // Package-local deterministic seam: real provider atomics are unchanged, only query execution is injected.
    ObjectNode assess(ObjectNode result, List<ObjectNode> atomics, TranscriptConsequence selected) {
        ObjectNode context = (ObjectNode) result.path("context");
        String inputIssue = new ClinVarPs1Pm5CriterionEvaluator(mapper).businessInputIssue(context, atomics);
        if (inputIssue != null) return finish(result, atomics, blocked(inputIssue, "UNKNOWN"));
        JsonNode targetConcern = knownSpliceConcern(context.at("/variant/evidenceQueryAllele"));
        if (targetConcern != null) {
            result.withArray("rnaReviewEvidence").add(targetConcern.deepCopy());
            return finish(result, atomics, blocked("PUBLIC_SPLICE_CONCERN_REQUIRES_REVIEW", "UNKNOWN"));
        }
        List<Reference> references = new ArrayList<>();
        String issue = null;
        for (ObjectNode atomic : atomics) {
            if (!"HIT".equals(atomic.path("queryOutcome").path("status").asText())) continue;
            String relation = metric(atomic, "vep.proteinRelation");
            if (!List.of("SAME_AMINO_ACID_CHANGE", "SAME_RESIDUE_DIFFERENT_AMINO_ACID").contains(relation)) continue;
            String scv = metric(atomic, "clinvar.assertion.scv");
            JsonNode trusted = reference(scv);
            if (trusted == null) continue;
            String eligibility = referenceIssue(context, atomic, trusted, atomics);
            if (eligibility != null) { issue = eligibility; continue; }
            String rnaIssue = rnaReviewIssue(atomic, trusted);
            if (rnaIssue != null) { issue = rnaIssue; continue; }
            references.add(new Reference(atomic, trusted, relation));
        }
        if (references.isEmpty()) {
            return finish(result, atomics, issue == null
                    ? decisions("NOT_EVALUATED", "INDEPENDENT_REFERENCE_EVIDENCE_MISSING", "UNKNOWN")
                    : blocked(issue, "UNKNOWN"));
        }
        // A conflicting audited assertion is not silently discarded in favour of another assertion.
        if (issue != null) return finish(result, atomics, blocked(issue, "UNKNOWN"));
        Map<String, SpliceAiRawBatchRunner.Query> queries = new LinkedHashMap<>();
        JsonNode targetAllele = context.at("/variant/evidenceQueryAllele");
        NormalizedVariantKey target = key(targetAllele);
        queries.put("TARGET", new SpliceAiRawBatchRunner.Query("TARGET", target, "BRAF", selected.getVersionedTranscriptId()));
        ArrayNode displayed = result.withArray("references");
        for (Reference reference : references) {
            String id = reference.audit().path("scv").asText();
            queries.putIfAbsent(id, new SpliceAiRawBatchRunner.Query(id, key(reference.audit().path("allele")),
                    "BRAF", selected.getVersionedTranscriptId()));
            ObjectNode display = ((ObjectNode) reference.audit()).deepCopy();
            display.put("independentEvidenceStatus", "VERIFIED");
            display.put("independentEvidenceReason", reference.audit().path("independentEvidenceReason").asText());
            display.put("proteinRelation", reference.relation());
            JsonNode reviewed = matchingRnaReview(reference.atomic(), reference.audit());
            if (reviewed != null) {
                display.set("rnaReview", reviewed.deepCopy());
                result.withArray("rnaReviewEvidence").add(reviewed.deepCopy());
            }
            displayed.add(display);
        }
        ObjectNode proof = read(mapper, ANNOTATION_PROOF_RESOURCE);
        for (SpliceAiRawBatchRunner.Query query : queries.values()) {
            int position = query.getKey().getPosition();
            if (!"GRCh38".equals(query.getKey().getGenomeAssembly()) || !"7".equals(query.getKey().getChromosome())
                    || position < proof.at("/inferencePolicy/minimumUnpaddedPositionInclusive").asInt()
                    || position > proof.at("/inferencePolicy/maximumUnpaddedPositionInclusive").asInt()) {
                return finish(result, atomics, blocked("SPLICEAI_MANE_WINDOW_EQUIVALENCE_UNPROVEN", "UNKNOWN"));
            }
        }
        result.set("annotationEquivalence", proof);
        if (splice == null) return finish(result, atomics, blocked("SPLICE_EVIDENCE_UNAVAILABLE", "UNKNOWN"));
        SpliceAiRawBatchRunner.BatchResult batch;
        result.put("rawBatchCalls", 1);
        try { batch = splice.apply(List.copyOf(queries.values())); }
        catch (RuntimeException failure) { return finish(result, atomics, blocked("SPLICE_EVIDENCE_UNAVAILABLE", "UNKNOWN")); }
        ObjectNode raw = batch == null ? null : batch.getResult();
        if (batch == null || !"SUCCEEDED".equals(batch.getStatus()) || raw == null) {
            return finish(result, atomics, blocked("SPLICE_EVIDENCE_UNAVAILABLE", "UNKNOWN"));
        }
        result.set("rawSpliceBatch", raw);
        Map<String, Double> scores = new LinkedHashMap<>();
        ArrayNode spliceEvidence = result.withArray("spliceEvidence");
        for (SpliceAiRawBatchRunner.Query query : queries.values()) {
            Double score = score(raw, query);
            ObjectNode evidence = spliceEvidence.addObject().put("role", "TARGET".equals(query.getQueryId()) ? "TARGET" : "REFERENCE")
                    .put("queryId", query.getQueryId()).put("method", "SpliceAI").put("status", score == null ? "UNAVAILABLE" : "AVAILABLE")
                    .put("sourceUrl", policy.at("/splicePolicy/sourceUrl").asText());
            evidence.set("allele", allele(query.getKey()));
            if (score != null) { evidence.put("maximumScore", score); scores.put(query.getQueryId(), score); }
        }
        ObjectNode decisions = decisions("NOT_MET", "NO_ELIGIBLE_PROTEIN_RELATION", "APPLICABLE");
        for (String criterion : List.of("PS1", "PM5")) {
            List<String> accepted = new ArrayList<>(); boolean related = false; String missing = null; boolean spliceRejected = false;
            for (Reference ref : references) {
                if (!("PS1".equals(criterion) ? "SAME_AMINO_ACID_CHANGE" : "SAME_RESIDUE_DIFFERENT_AMINO_ACID").equals(ref.relation())) continue;
                related = true;
                if ("PM5".equals(criterion) && !auditedRelationship(ref.atomic())) { missing = "PM5_AMINO_ACID_RELATION_NOT_AUDITED"; continue; }
                Double targetScore = scores.get("TARGET"); Double referenceScore = scores.get(ref.audit().path("scv").asText());
                if (targetScore == null || referenceScore == null) { missing = "SPLICE_EVIDENCE_UNAVAILABLE"; continue; }
                if (targetScore > 0.1 || referenceScore > 0.1) { spliceRejected = true; continue; }
                accepted.add(ref.atomic().path("evidenceId").asText());
            }
            ObjectNode decision = (ObjectNode) decisions.path(criterion);
            if (!accepted.isEmpty()) decision.put("status", "MET").put("reasonCode", "TRUSTED_REFERENCE_AND_SPLICE_CONDITIONS_MET")
                    .set("evidenceIds", mapper.valueToTree(accepted));
            else if (missing != null) decision.put("status", "NOT_EVALUATED").put("reasonCode", missing).put("applicability", "UNKNOWN");
            else if (related && spliceRejected) decision.put("reasonCode", "SPLICE_CONDITION_NOT_MET");
        }
        if ("MET".equals(decisions.at("/PS1/status").asText())) {
            ((ObjectNode) decisions.path("PM5")).put("status", "NOT_MET").put("reasonCode", "PS1_TAKES_PRECEDENCE").putArray("evidenceIds");
        }
        decisions.put("referenceAuditSha256", adapter.canonicalSha256(audit));
        decisions.put("rawSpliceBatchSha256", adapter.canonicalSha256(raw));
        return finish(result, atomics, decisions);
    }

    private String referenceIssue(JsonNode context, ObjectNode atomic, JsonNode ref, List<ObjectNode> atomics) {
        String completenessIssue = new ClinVarPs1Pm5CriterionEvaluator(mapper).businessReferenceIssue(context, atomic);
        if (completenessIssue != null) return completenessIssue;
        if (!"VERIFIED".equals(ref.path("independentEvidenceStatus").asText()) || !ref.path("independentCriteriaSufficient").asBoolean()
                || ref.path("independentCriteria").isEmpty()) return "REFERENCE_NOT_INDEPENDENT";
        for (JsonNode criterion : ref.path("independentCriteria")) {
            String code = criterion.isTextual() ? criterion.asText() : criterion.path("code").asText();
            if (code.startsWith("PS1") || code.startsWith("PM5")) return "REFERENCE_NOT_INDEPENDENT";
        }
        if (!context.at("/disease/mondoId").asText().equals(ref.path("mondoId").asText())
                || !"BRAF".equals(ref.path("geneSymbol").asText())) return "REFERENCE_DISEASE_MISMATCH";
        JsonNode expected = ref.path("allele");
        if (expected.equals(context.at("/variant/evidenceQueryAllele"))) return "REFERENCE_SELF_COMPARISON";
        if (knownSpliceConcern(expected) != null) return "REFERENCE_PUBLIC_SPLICE_CONCERN_REQUIRES_REVIEW";
        for (String field : List.of("assembly", "contig", "position", "reference", "alternate")) {
            if (!expected.path(field).asText().equals(metric(atomic, "clinvar.comparator." + field))) return "REFERENCE_IDENTITY_MISMATCH";
        }
        if (!ref.path("classification").asText().equals(metric(atomic, "clinvar.assertion.classification"))
                || !"reviewed by expert panel".equals(metric(atomic, "clinvar.assertion.reviewStatus"))) return "REFERENCE_ASSERTION_CONFLICT";
        String tx = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
        for (String role : List.of("target", "comparator")) if (!tx.equals(metric(atomic, "vep." + role + ".transcriptId"))) return "REFERENCE_TRANSCRIPT_MISMATCH";
        if (!ref.path("proteinPosition").asText().equals(metric(atomic, "vep.comparator.proteinPosition"))
                || !ref.path("referenceAminoAcid").asText().equals(metric(atomic, "vep.comparator.referenceAminoAcid"))
                || !ref.path("alternateAminoAcid").asText().equals(metric(atomic, "vep.comparator.alternateAminoAcid"))) return "REFERENCE_PROTEIN_MISMATCH";
        if (!metric(atomic, "vep.target.proteinPosition").equals(metric(atomic, "vep.comparator.proteinPosition"))
                || !metric(atomic, "vep.target.referenceAminoAcid").equals(metric(atomic, "vep.comparator.referenceAminoAcid"))) return "REFERENCE_PROTEIN_MISMATCH";
        boolean sameChange = metric(atomic, "vep.target.alternateAminoAcid").equals(metric(atomic, "vep.comparator.alternateAminoAcid"));
        if (!(sameChange ? "SAME_AMINO_ACID_CHANGE" : "SAME_RESIDUE_DIFFERENT_AMINO_ACID").equals(metric(atomic, "vep.proteinRelation"))) return "REFERENCE_PROTEIN_MISMATCH";
        if (!ref.path("dateLastEvaluated").asText().equals(normalizedDate(metric(atomic, "clinvar.assertion.dateLastEvaluated")))) return "REFERENCE_ASSERTION_VERSION_MISMATCH";
        for (ObjectNode other : atomics) {
            if (Objects.equals(metric(atomic, "clinvar.comparator.variationId"), metric(other, "clinvar.comparator.variationId"))
                    && List.of("Benign", "Likely benign", "Benign/Likely benign").contains(metric(other, "clinvar.assertion.classification"))
                    && List.of("reviewed by expert panel", "practice guideline").contains(metric(other, "clinvar.assertion.reviewStatus")))
                return "REFERENCE_ASSERTION_CONFLICT";
        }
        return null;
    }

    private static String normalizedDate(String value) {
        try { return java.time.LocalDate.parse(value).toString(); }
        catch (java.time.format.DateTimeParseException notIso) {
            try { return java.time.LocalDate.parse(value, java.time.format.DateTimeFormatter.ofPattern("MMM d, uuuu", java.util.Locale.US)).toString(); }
            catch (java.time.format.DateTimeParseException invalid) { return ""; }
        }
    }

    private boolean auditedRelationship(ObjectNode atomic) {
        for (JsonNode relation : policy.at("/pm5RelationshipPolicy/relationships")) {
            if (relation.path("referenceAminoAcid").asText().equals(metric(atomic, "vep.target.referenceAminoAcid"))
                    && relation.path("targetAlternate").asText().equals(metric(atomic, "vep.target.alternateAminoAcid"))
                    && relation.path("comparatorAlternate").asText().equals(metric(atomic, "vep.comparator.alternateAminoAcid"))) return true;
        }
        return false;
    }

    private JsonNode knownSpliceConcern(JsonNode allele) {
        for (JsonNode concern : policy.at("/rnaEvidenceReview/knownConcerns")) {
            if (allele.equals(concern.path("allele")) && !"RESOLVED_NO_COMPARISON_CONFLICT".equals(concern.path("reviewStatus").asText()))
                return concern;
        }
        return null;
    }

    private JsonNode matchingRnaReview(ObjectNode atomic, JsonNode ref) {
        JsonNode match = null;
        for (JsonNode review : policy.at("/rnaEvidenceReview/reviews")) {
            JsonNode relation = review.path("aminoAcidRelation");
            if (ref.path("scv").equals(review.path("referenceScv"))
                    && relation.path("referenceAminoAcid").asText().equals(metric(atomic, "vep.target.referenceAminoAcid"))
                    && relation.path("targetAlternate").asText().equals(metric(atomic, "vep.target.alternateAminoAcid"))
                    && relation.path("comparatorAlternate").asText().equals(metric(atomic, "vep.comparator.alternateAminoAcid"))) {
                if (match != null) return null;
                match = review;
            }
        }
        return match;
    }

    private String rnaReviewIssue(ObjectNode atomic, JsonNode ref) {
        boolean required = true;
        for (JsonNode scv : policy.at("/rnaEvidenceReview/legacyReferenceScvs")) if (scv.equals(ref.path("scv"))) required = false;
        for (JsonNode scv : policy.at("/rnaEvidenceReview/requiredReferenceScvs")) if (scv.equals(ref.path("scv"))) required = true;
        // Existing audited references retain their prior boundary; no new normal-RNA claim is inferred.
        if (!required) return null;
        if ("SAME_RESIDUE_DIFFERENT_AMINO_ACID".equals(metric(atomic, "vep.proteinRelation")) && !auditedRelationship(atomic))
            return "PM5_AMINO_ACID_RELATION_NOT_AUDITED";
        JsonNode scope = policy.at("/rnaEvidenceReview/scope");
        for (String axis : List.of("assembly", "geneSymbol", "hgncId", "mondoId", "moi", "transcript", "protein")) {
            if (!scope.path(axis).equals(policy.path("applicability").path(axis))) return "RNA_REVIEW_SCOPE_MISMATCH";
        }
        JsonNode review = matchingRnaReview(atomic, ref);
        if (review == null || !"REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED".equals(review.path("status").asText())
                || !"UNKNOWN".equals(review.path("rnaEffect").asText()) || review.path("normalRnaClaimed").asBoolean(true)
                || review.path("sourceUrls").isEmpty() || review.path("sourceArtifacts").isEmpty()) return "RNA_REVIEW_NOT_AVAILABLE_FOR_COMPARISON";
        return null;
    }

    private JsonNode reference(String scv) {
        for (JsonNode ref : audit.path("references")) if (ref.path("scv").asText().equals(scv)) return ref;
        return null;
    }
    private static String metric(JsonNode atomic, String name) {
        String found = null;
        for (JsonNode measurement : atomic.path("measurements")) if (name.equals(measurement.path("metricId").asText())) {
            if (found != null) return ""; found = measurement.path("rawValue").asText();
        }
        return found == null ? "" : found;
    }
    private String scopeReason(JsonNode context, TranscriptConsequence selected) {
        try { adapter.validateCompleteContext(context); } catch (RuntimeException failure) { return "CONTEXT_INCOMPLETE"; }
        if (!"GRCh38".equals(context.at("/variant/evidenceQueryAllele/assembly").asText())
                || !"SNV".equals(context.at("/variant/variantType").asText())
                || !"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                || !"BRAF".equals(context.at("/gene/symbol").asText())
                || !"HGNC:1097".equals(context.at("/gene/hgncId").asText())
                || !"MONDO:0021060".equals(context.at("/disease/mondoId").asText())
                || !"AUTOSOMAL_DOMINANT".equals(context.path("moi").asText())) return "OUTSIDE_BRAF_RASOPATHY_POLICY";
        if (selected == null || selected.getConsequence() == null || !selected.getConsequence().contains("missense_variant")) return "OUTSIDE_MISSENSE_POLICY";
        if (!"ENSP00000493543.1".equals(selected.getProteinIdentity())) return "SELECTED_PROTEIN_VERSION_MISMATCH";
        if (!"ENST00000646891.2".equals(selected.getVersionedTranscriptId())
                || !"ENST00000646891".equals(context.at("/transcript/accession").asText())
                || context.at("/transcript/version").asInt() != 2) return "SELECTED_TRANSCRIPT_VERSION_MISMATCH";
        return null;
    }
    private Double score(JsonNode batch, SpliceAiRawBatchRunner.Query query) {
        JsonNode found = null;
        for (JsonNode row : batch.path("results")) if (query.getQueryId().equals(row.path("queryId").asText())) {
            if (found != null) return null; found = row;
        }
        if (found == null || !"HIT".equals(found.path("status").asText()) || !found.path("exactAlleleMatch").asBoolean()
                || !allele(query.getKey()).equals(found.path("allele")) || found.path("annotations").size() != 1) return null;
        JsonNode annotation = found.path("annotations").get(0);
        if (annotation == null || !"BRAF".equals(annotation.path("geneSymbol").asText())) return null;
        double maximum = 0;
        for (String field : List.of("DS_AG", "DS_AL", "DS_DG", "DS_DL")) {
            double value;
            try { value = Double.parseDouble(annotation.path("deltaScores").path(field).asText()); }
            catch (NumberFormatException invalid) { return null; }
            if (!Double.isFinite(value) || value < 0 || value > 1) return null;
            maximum = Math.max(maximum, value);
        }
        return maximum;
    }
    ObjectNode base(ObjectNode context) {
        ObjectNode result = mapper.createObjectNode().put("applicability", "APPLICABLE"); result.set("context", context.deepCopy());
        result.set("policy", policy.deepCopy()); result.putArray("references"); result.putArray("spliceEvidence");
        result.putArray("blockers"); result.put("providerCalls", 0).put("evaluatorCalls", 0).put("rawBatchCalls", 0);
        return result;
    }
    private ObjectNode finish(ObjectNode result, List<ObjectNode> atomics, ObjectNode decisions) {
        ObjectNode context = (ObjectNode) result.path("context");
        ObjectNode selection = ClinVarMissenseBusinessArtifacts.selection(mapper, context);
        result.set("selection", selection); result.set("atomicEvidence", mapper.valueToTree(atomics));
        result.set("scientificDecisions", decisions);
        List<ObjectNode> evaluations = new ClinVarPs1Pm5CriterionEvaluator(mapper).evaluateBusiness(context, atomics,
                selection.path("selectionId").asText(), policy, decisions);
        result.set("criterionEvaluations", mapper.valueToTree(evaluations)); result.put("evaluatorCalls", 1);
        for (String rule : List.of("PS1", "PM5")) if ("NOT_EVALUATED".equals(decisions.at("/" + rule + "/status").asText()))
            result.withArray("blockers").addObject().put("criterionId", "ACMG_AMP_2015:" + rule).put("reasonCode", decisions.at("/" + rule + "/reasonCode").asText());
        result.set("bundle", ClinVarMissenseBusinessArtifacts.bundle(mapper, result));
        return result;
    }
    public static ObjectNode disposition(ObjectMapper mapper, ObjectNode context, String reason, String applicability) {
        if (Hnf1aPs1Pm5Runtime.matchesContext(context)) return Hnf1aPs1Pm5Runtime.disposition(mapper, context, reason, applicability);
        ObjectNode result = baseDisposition(mapper, context, reason, applicability);
        result.set("policy", loadProfile(mapper)); return result;
    }
    static ObjectNode baseDisposition(ObjectMapper mapper, ObjectNode context, String reason, String applicability) {
        ObjectNode result = mapper.createObjectNode().put("applicability", applicability); if (context != null) result.set("context", context.deepCopy());
        result.putArray("references"); result.putArray("spliceEvidence");
        result.putArray("atomicEvidence"); result.putArray("blockers").addObject().put("reasonCode", reason);
        result.put("providerCalls", 0).put("evaluatorCalls", 0).put("rawBatchCalls", 0);
        ArrayNode values = result.putArray("criterionEvaluations");
        for (String rule : List.of("PS1", "PM5")) {
            ObjectNode value = values.addObject().put("criterionId", "ACMG_AMP_2015:" + rule).put("baseStrength", "PS1".equals(rule) ? "STRONG" : "MODERATE").put("appliedStrength", "NONE");
            value.putObject("criterionApplicability").put("status", applicability).put("reasonCode", reason);
            value.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", reason);
        }
        return result;
    }
    private ObjectNode blocked(String reason, String applicability) { return decisions("NOT_EVALUATED", reason, applicability); }
    private ObjectNode decisions(String status, String reason, String applicability) {
        ObjectNode result = mapper.createObjectNode();
        for (String rule : List.of("PS1", "PM5")) result.putObject(rule).put("status", status).put("reasonCode", reason).put("applicability", applicability).putArray("evidenceIds");
        return result;
    }
    private NormalizedVariantKey key(JsonNode allele) { return new NormalizedVariantKey(allele.path("assembly").asText(), allele.path("contig").asText(), allele.path("position").asInt(), allele.path("reference").asText(), allele.path("alternate").asText()); }
    private ObjectNode allele(NormalizedVariantKey key) { return mapper.createObjectNode().put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome()).put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate()); }
    static ObjectNode read(ObjectMapper mapper, String path) {
        try (InputStream input = ClinVarMissenseBusinessRuntime.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing missense business resource: " + path);
            JsonNode parsed = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(input);
            if (!(parsed instanceof ObjectNode object)) throw new IllegalStateException("Invalid missense business resource: " + path);
            return object;
        } catch (IOException failure) { throw new IllegalStateException("Cannot read missense business resource: " + path, failure); }
    }
    private record Reference(ObjectNode atomic, JsonNode audit, String relation) { }
}
