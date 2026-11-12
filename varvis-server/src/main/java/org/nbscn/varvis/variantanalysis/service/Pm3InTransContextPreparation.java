package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.provider.*;

/** Read-only binding of two published alleles; scientific review remains exclusively in the pinned material. */
public final class Pm3InTransContextPreparation {
    public static final String VERSION = "1.0.0";
    public static final String POLICY = "1.0.0-pm3-in-trans-development";
    public static final String TOKEN = "PM3_IN_TRANS_CONTEXT@1.0.0:";
    private final ObjectMapper mapper;
    private final Path file;
    private final String rawPin, canonicalPin;
    private final AcmgBusinessArtifacts hashes;
    private final Pm3InTransCriterionEvaluator evaluator;
    public Pm3InTransContextPreparation(ObjectMapper mapper, Path file, String rawPin, String canonicalPin) {
        this.mapper = mapper; this.file = file; this.rawPin = rawPin; this.canonicalPin = canonicalPin;
        hashes = new AcmgBusinessArtifacts(mapper); evaluator = new Pm3InTransCriterionEvaluator(mapper);
    }
    public ObjectNode pins() { return mapper.createObjectNode().put("bundleRawSha256", rawPin).put("bundleCanonicalSha256", canonicalPin); }
    public Pm3InTransBundle load() {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(Ps2DeNovoBundle.MAX_BYTES + 1);
            require(bytes.length <= Ps2DeNovoBundle.MAX_BYTES, "PM3_MATERIAL_SIZE_LIMIT");
            return Pm3InTransBundle.load(mapper, bytes, rawPin, canonicalPin);
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("PM3_MATERIAL_UNAVAILABLE"); }
    }
    public record Prepared(Pm3InTransBundle bundle, ObjectNode document, ObjectNode selection) { }
    private ObjectNode allele(VariantInterpretation candidate) {
        require(candidate.getObservation() != null, "PM3_CANDIDATE_ALLELE_REQUIRED");
        var key = candidate.getObservation().getKey();
        return mapper.createObjectNode().put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome())
                .put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
    }
    private boolean background(VariantInterpretation candidate, JsonNode context) {
        var identity = candidate.getCandidateIdentity();
        return identity != null && context.at("/gene/symbol").asText().equals(identity.getGeneSymbol())
                && List.of("AR", "AUTOSOMAL_RECESSIVE").contains(Objects.toString(identity.getModeOfInheritance(), ""));
    }
    /** No re-ranking, no Top2 shortcut, and no search beyond the published window. */
    public List<VariantInterpretation> select(List<VariantInterpretation> published, Pm3InTransBundle bundle) {
        require(!published.isEmpty() && published.size() <= AcmgExecutionSources.MAX_CANDIDATES, "PM3_FORMAL_CANDIDATE_REQUIRED");
        var root = bundle.snapshot(); var context = root.path("context");
        require(root.path("observations").size() == 1, "PM3_SINGLE_OBSERVATION_REQUIRED");
        var target = published.get(0); var partnerAllele = root.at("/observations/0/facts/alleles/partner/inputAllele");
        require(allele(target).equals(context.at("/variant/inputAllele")), "PM3_TARGET_ALLELE_CONFLICT");
        require(background(target, context), "PM3_CANDIDATE_BACKGROUND_CONFLICT");
        require(!allele(target).equals(partnerAllele), "PM3_DISTINCT_PARTNER_REQUIRED");
        var matches = published.stream().filter(c -> allele(c).equals(partnerAllele) && background(c, context)).toList();
        require(matches.size() == 1, matches.isEmpty() ? "PM3_PUBLISHED_PARTNER_REQUIRED" : "PM3_PARTNER_AMBIGUOUS");
        return List.of(target, matches.get(0));
    }
    public Prepared prepare(VariantAnalysisJob job, List<VariantInterpretation> pair, AcmgExecutionSources.Recovered recovered,
                            JsonNode publishedIdentity, JsonNode profile, Pm3InTransBundle bundle) {
        var root = bundle.snapshot(); var context = root.path("context"); var observation = root.at("/observations/0");
        var facts = observation.path("facts"); var patient = context.path("patientContext");
        for (String field : List.of("patientContextId", "familyContextId"))
            require(patient.path(field).isTextual() && !patient.path(field).asText().isBlank(), "PM3_" + (field.equals("familyContextId") ? "FAMILY_CONTEXT_REQUIRED" : "PATIENT_CONTEXT_REQUIRED"));
        for (String field : List.of("caseId", "observationId", "occurrenceId", "familyId"))
            require(facts.path(field).isTextual() && !facts.path(field).asText().isBlank(), "PM3_CASE_BINDING_REQUIRED");
        require("AUTOSOMAL_RECESSIVE".equals(context.path("moi").asText()), "PM3_AR_BACKGROUND_REQUIRED");
        require(recovered.candidateBlockers().isEmpty(), "PM3_PUBLISHED_CANDIDATE_SOURCE_CONFLICT");
        var source = recovered.execution().getInputSource();
        require(source != null && Objects.equals(source.uploadSha256(), source.observedVcfSha256()), "PM3_NATIVE_SOURCE_DIGEST_CONFLICT");
        var probands = new ArrayList<JsonNode>();
        for (var member : facts.path("members")) if ("PROBAND".equals(member.path("role").asText())) probands.add(member);
        require(probands.size() == 1, "PM3_PROBAND_REQUIRED"); var proband = probands.get(0);
        String sample = job.getSelectedSampleId() == null ? job.getRequestedSampleId() : job.getSelectedSampleId();
        require(Objects.equals(sample, proband.path("sampleId").asText())
                && (job.getRequestedSampleId() == null || Objects.equals(sample, job.getRequestedSampleId())), "PM3_SAMPLE_CONFLICT");
        require(patient.path("patientContextId").equals(proband.path("memberId"))
                && patient.path("affectedStatus").equals(proband.path("affectedStatus"))
                && patient.path("familyContextId").equals(facts.path("familyId")), "PM3_PATIENT_MATERIAL_CONFLICT");
        for (int i = 0; i < 2; i++) {
            var candidate = pair.get(i); String role = i == 0 ? "target" : "partner";
            var issue = CandidateAcmgInputAdapter.sourceIssue(mapper, job, recovered.conversion(), recovered.execution(),
                    new AnalysisContext(job.getId(), job.getGenomeAssembly(), job.getAnalysisMode()), candidate, false);
            require(issue == null, issue == null ? "PM3_SOURCE_INVALID" : issue.reasonCode());
            var key = candidate.getObservation().getKey(); var allele = allele(candidate);
            require("GRCh38".equals(key.getGenomeAssembly()), "PM3_NATIVE_GRCH38_REQUIRED");
            var variant = facts.path("alleles").path(role);
            for (String field : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele"))
                require(allele.equals(variant.path(field)), "PM3_CORE_ALLELE_CONFLICT");
            if (i == 0) require(variant.equals(context.path("variant")), "PM3_TARGET_CONTEXT_CONFLICT");
            var record = recovered.execution().getInputSource().matchedRecord(key);
            require(record != null && record.selectedAltIndex() == 1 && record.alternates().size() == 1
                    && record.position() == key.getPosition() && record.reference().equals(key.getReference())
                    && record.alternates().get(0).equals(key.getAlternate())
                    && record.contig().replaceFirst("^chr", "").equals(key.getChromosome()), "PM3_ORIGINAL_RECORD_CONFLICT");
            var records = new ArrayList<JsonNode>();
            for (var r : facts.path("records")) if ("PROBAND".equals(r.path("role").asText())
                    && r.at("/variantKey/position").equals(allele.path("position"))
                    && r.at("/variantKey/chromosome").equals(allele.path("contig"))
                    && r.at("/variantKey/reference").equals(allele.path("reference"))
                    && r.at("/variantKey/alternate").equals(allele.path("alternate"))
                    && r.at("/variantKey/assembly").equals(allele.path("assembly"))) records.add(r);
            require(records.size() == 1 && Objects.equals(sample, records.get(0).path("sampleId").asText()), "PM3_PROBAND_RECORD_CONFLICT");
            verifyCall(candidate.getAnalysisVcfCall(), records.get(0));
        }
        ObjectNode binding = mapper.createObjectNode().put("schemaVersion", "pm3-in-trans-business-binding-v1");
        binding.set("materialPins", pins()); binding.set("context", context.deepCopy());
        binding.set("pairBinding", facts.path("binding").deepCopy()); binding.set("publishedInputIdentity", publishedIdentity.deepCopy());
        binding.put("candidateId", pair.get(0).getCandidateId()).put("partnerCandidateId", pair.get(1).getCandidateId());
        var parts = binding.putObject("partPins");
        for (String part : List.of("facts", "phase", "partnerClassification", "targetRarity", "partnerRarity")) parts.put(part, hashes.requestHash(observation.path(part)));
        binding.put("assessmentSha256", hashes.requestHash(observation.path("assessment")))
                .put("policySha256", hashes.requestHash(root.path("policy"))).put("catalogSha256", hashes.requestHash(root.path("catalog")));
        binding.set("registryBinding", root.path("registryBinding").deepCopy()); binding.set("executionProfile", profile.deepCopy());
        String bindingHash = hashes.requestHash(binding);
        var document = mapper.createObjectNode().put("schemaVersion", "pm3-in-trans-context-preparation-v1")
                .put("version", VERSION).put("policyVersion", POLICY).put("status", "BOUND_NOT_EVALUATED")
                .put("scientificApplicability", "NOT_EVALUATED").put("analysisId", job.getId()).put("sampleId", sample)
                .put("candidateId", pair.get(0).getCandidateId()).put("partnerCandidateId", pair.get(1).getCandidateId())
                .put("materialBindingSha256", bindingHash).put("criterionPolicySelected", false).put("predictionCompatibilityClaimed", false);
        document.set("materialBinding", binding); document.set("patientContext", patient.deepCopy());
        document.set("reviewedObservation", observation.deepCopy());
        String token = TOKEN + hashes.requestHash(document); document.put("preparationToken", token);
        var selection = mapper.createObjectNode().put("candidateId", pair.get(0).getCandidateId()).put("partnerCandidateId", pair.get(1).getCandidateId());
        for (String field : List.of("contextId", "targetAlleleId", "partnerAlleleId", "pairId", "observationId", "occurrenceId", "caseId", "familyContextId"))
            selection.set(field, facts.path("binding").path(field).deepCopy());
        selection.put("materialBindingSha256", bindingHash).put("preparationToken", token).put("policyVersion", POLICY).put("confirmed", false);
        return new Prepared(bundle, document, selection);
    }
    public void verify(AcmgSubmission request, Prepared prepared) {
        JsonNode actual = mapper.valueToTree(request.pm3Selection());
        require(prepared.selection().deepCopy().put("confirmed", true).equals(actual), "PM3_SELECTION_STALE");
        var context = prepared.bundle().snapshot().path("context");
        require(context.path("dataClass").asText().equals(request.dataClass()), "PM3_DATA_CLASS_CONFLICT");
        require(prepared.document().path("sampleId").asText().equals(request.sampleId()), "PM3_SAMPLE_CONFLICT");
        require(request.patient() != null, "PM3_PATIENT_REQUIRED");
        JsonNode patient = mapper.valueToTree(request.patient());
        for (String field : List.of("patientContextId", "familyContextId", "role", "sex"))
            require(context.path("patientContext").path(field).equals(patient.path(field)), "PM3_PATIENT_CONFLICT");
    }
    public ObjectNode evaluate(Prepared prepared) { return evaluator.evaluate(prepared.bundle()); }
    private static void require(boolean condition, String code) { if (!condition) throw new IllegalArgumentException(code); }
    private static void verifyCall(AnalysisVcfCall call, JsonNode record) {
        if (!(call != null && call.getInvalidFields().isEmpty() && call.getMissingFields().stream().allMatch(field ->
                "PS".equals(field) && call.getPhasingStatus() == AnalysisVcfCall.PhasingStatus.RECORD_ONLY))) {
            throw new IllegalArgumentException("PM3_PROBAND_CALL_INCOMPLETE");
        }
        var observed = record.path("observation");
        String gt = observed.path("genotype").asText();
        require(gt.matches("[01][/|][01]"), "PM3_PROBAND_GT_UNSUPPORTED");
        String relative = gt.replace("0", "REF").replace("1", "TARGET_ALT");
        require(relative.equals(call.getCandidateRelativeGenotype()), "PM3_PROBAND_GT_CONFLICT");
        String expectedCall = switch (observed.path("call").asText()) { case "HET_ALT" -> "HET_TARGET"; case "HOM_ALT" -> "HOM_TARGET"; case "HOM_REF" -> "HOM_REF"; default -> "UNSUPPORTED"; };
        require(call.getTargetAlleleCall() != null && expectedCall.equals(call.getTargetAlleleCall().name()), "PM3_PROBAND_CALL_CONFLICT");
        require(Objects.equals(call.getDepth(), observed.path("depth").isInt() ? observed.path("depth").intValue() : null)
                && call.getDepth() != null && call.getGenotypeQuality() != null
                && observed.path("genotypeQuality").isInt() && call.getGenotypeQuality() == observed.path("genotypeQuality").intValue()
                && call.getReferenceDepth() != null && call.getTargetAlleleDepth() != null
                && (call.getReferenceDepth() + "," + call.getTargetAlleleDepth()).equals(observed.path("alleleDepth").asText()), "PM3_PROBAND_QUALITY_CONFLICT");
        require(observed.path("phased").isBoolean() && call.getPhasingStatus() != null
                // Single-site PM3 uses the preserved GT delimiter, not phase-set linkage or parental origin inference.
                && (observed.path("phased").booleanValue() ? (call.getPhasingStatus() == AnalysisVcfCall.PhasingStatus.RECORD_ONLY
                || call.getPhasingStatus() == AnalysisVcfCall.PhasingStatus.PHASE_SET)
                : call.getPhasingStatus() == AnalysisVcfCall.PhasingStatus.UNPHASED), "PM3_PROBAND_PHASE_CONFLICT");
        require(call.getFilterEvaluation() == AnalysisVcfCall.FilterEvaluation.PASS
                && "PASS".equals(record.path("recordFilter").asText()), "PM3_PROBAND_FILTER_CONFLICT");
    }
}
