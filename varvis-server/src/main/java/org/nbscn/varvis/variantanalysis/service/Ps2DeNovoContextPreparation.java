package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.provider.*;

/** Read-only binding of an existing published top candidate to one independently pinned core bundle. */
public final class Ps2DeNovoContextPreparation {
    public static final String VERSION = "1.0.0";
    public static final String POLICY = "1.0.0-ps2-denovo-development";
    public static final String TOKEN = "PS2_DENOVO_CONTEXT@1.0.0:";
    private final ObjectMapper mapper;
    private final Path file;
    private final String rawPin;
    private final String canonicalPin;
    private final AcmgBusinessArtifacts hashes;
    private final Ps2DeNovoCriterionEvaluator evaluator;

    public Ps2DeNovoContextPreparation(ObjectMapper mapper, Path file, String rawPin, String canonicalPin) {
        this.mapper = mapper; this.file = file; this.rawPin = rawPin; this.canonicalPin = canonicalPin;
        hashes = new AcmgBusinessArtifacts(mapper); evaluator = new Ps2DeNovoCriterionEvaluator(mapper);
    }
    /** Stable even when the file is unavailable; never includes the configured server path. */
    public ObjectNode pins() {
        return mapper.createObjectNode().put("bundleRawSha256", rawPin).put("bundleCanonicalSha256", canonicalPin);
    }
    public Ps2DeNovoBundle load() {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(Ps2DeNovoBundle.MAX_BYTES + 1);
            require(bytes.length <= Ps2DeNovoBundle.MAX_BYTES, "PS2_MATERIAL_SIZE_LIMIT");
            return Ps2DeNovoBundle.load(mapper, bytes, rawPin, canonicalPin);
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("PS2_MATERIAL_UNAVAILABLE"); }
    }
    public record Prepared(Ps2DeNovoBundle bundle, ObjectNode document, ObjectNode selection) { }

    public Prepared prepare(VariantAnalysisJob job, VariantInterpretation candidate, AcmgExecutionSources.Recovered recovered,
                            JsonNode publishedIdentity, JsonNode profile) {
        var bundle = load(); var root = bundle.snapshot(); var context = root.path("context");
        require(root.path("observations").size() == 1, "PS2_SINGLE_OBSERVATION_REQUIRED");
        var facts = root.at("/observations/0/facts"); var assessment = root.at("/observations/0/assessment");
        require(recovered.candidateBlockers().isEmpty(), "PS2_PUBLISHED_CANDIDATE_SOURCE_CONFLICT");
        var issue = CandidateAcmgInputAdapter.sourceIssue(mapper, job, recovered.conversion(), recovered.execution(),
                new AnalysisContext(job.getId(), job.getGenomeAssembly(), job.getAnalysisMode()), candidate, false);
        require(issue == null, issue == null ? "PS2_SOURCE_INVALID" : issue.reasonCode());
        var key = candidate.getObservation().getKey();
        var allele = mapper.createObjectNode().put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome())
                .put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
        require("GRCh38".equals(key.getGenomeAssembly()), "PS2_NATIVE_GRCH38_REQUIRED");
        for (String field : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele"))
            require(allele.equals(context.path("variant").path(field)), "PS2_CORE_ALLELE_CONFLICT");
        require(allele.equals(facts.at("/binding/allele")), "PS2_FACT_ALLELE_CONFLICT");
        var candidateIdentity = candidate.getCandidateIdentity();
        require(candidateIdentity != null && context.at("/gene/symbol").asText().equals(candidateIdentity.getGeneSymbol())
                && candidateIdentity.getModeOfInheritance() != null && ("AUTOSOMAL_DOMINANT".equals(context.path("moi").asText())
                ? List.of("AD", "AUTOSOMAL_DOMINANT").contains(candidateIdentity.getModeOfInheritance())
                : context.path("moi").asText().equals(candidateIdentity.getModeOfInheritance())), "PS2_CANDIDATE_BACKGROUND_CONFLICT");
        var record = recovered.execution().getInputSource().matchedRecord(key);
        require(record != null && record.selectedAltIndex() == 1 && record.alternates().size() == 1
                && record.position() == key.getPosition() && record.reference().equals(key.getReference())
                && record.alternates().get(0).equals(key.getAlternate())
                && record.contig().replaceFirst("^chr", "").equals(key.getChromosome()), "PS2_ORIGINAL_RECORD_CONFLICT");
        var members = new java.util.ArrayList<JsonNode>();
        for (var member : facts.path("members")) if ("PROBAND".equals(member.path("role").asText())) members.add(member);
        require(members.size() == 1, "PS2_PROBAND_REQUIRED"); var proband = members.get(0);
        String sample = job.getSelectedSampleId() == null ? job.getRequestedSampleId() : job.getSelectedSampleId();
        require(Objects.equals(sample, proband.path("sampleId").asText()), "PS2_SAMPLE_CONFLICT");
        require(context.at("/patientContext/patientContextId").equals(proband.path("memberId"))
                && context.at("/patientContext/affectedStatus").equals(proband.path("affectedStatus"))
                && context.at("/patientContext/familyContextId").equals(facts.path("familyId")), "PS2_PATIENT_MATERIAL_CONFLICT");
        var records = new java.util.ArrayList<JsonNode>();
        for (var r : facts.path("records")) if ("PROBAND".equals(r.path("role").asText())) records.add(r);
        require(records.size() == 1 && sample.equals(records.get(0).path("sampleId").asText()), "PS2_PROBAND_RECORD_CONFLICT");
        verifyCall(candidate.getAnalysisVcfCall(), records.get(0));
        ObjectNode binding = mapper.createObjectNode().put("schemaVersion", "ps2-denovo-business-binding-v1");
        binding.set("materialPins", pins()); binding.set("context", context.deepCopy());
        for (String field : List.of("caseId", "observationId", "observationVersion", "occurrenceId", "familyId")) binding.set(field, facts.path(field));
        binding.put("factsSha256", hashes.requestHash(facts)).put("assessmentSha256", hashes.requestHash(assessment))
                .put("policySha256", hashes.requestHash(root.path("policy"))).put("catalogSha256", hashes.requestHash(root.path("catalog")));
        binding.set("registryBinding", root.path("registryBinding").deepCopy()); binding.set("executionProfile", profile.deepCopy());
        String bindingHash = hashes.requestHash(binding);
        ObjectNode preparation = mapper.createObjectNode().put("schemaVersion", "ps2-denovo-context-preparation-v1")
                .put("version", VERSION).put("policyVersion", POLICY).put("status", "BOUND_NOT_EVALUATED")
                .put("scientificApplicability", "NOT_EVALUATED").put("analysisId", job.getId())
                .put("candidateId", candidate.getCandidateId()).put("sampleId", sample)
                .put("materialBindingSha256", bindingHash).put("criterionPolicySelected", false).put("predictionCompatibilityClaimed", false);
        preparation.set("publishedInputIdentity", publishedIdentity.deepCopy()); preparation.set("materialBinding", binding);
        preparation.set("clinicalFacts", facts.path("clinical").deepCopy()); preparation.set("review", assessment.deepCopy());
        String token = TOKEN + hashes.requestHash(preparation); preparation.put("preparationToken", token);
        ObjectNode selection = mapper.createObjectNode().put("candidateId", candidate.getCandidateId())
                .put("contextId", context.path("contextId").asText()).put("observationId", facts.path("observationId").asText())
                .put("occurrenceId", facts.path("occurrenceId").asText()).put("caseId", facts.path("caseId").asText())
                .put("materialBindingSha256", bindingHash).put("preparationToken", token).put("policyVersion", POLICY).put("confirmed", false);
        return new Prepared(bundle, preparation, selection);
    }
    private static void verifyCall(AnalysisVcfCall call, JsonNode record) {
        if (!(call != null && call.getInvalidFields().isEmpty() && call.getMissingFields().stream().allMatch(field ->
                "PS".equals(field) && call.getPhasingStatus() == AnalysisVcfCall.PhasingStatus.RECORD_ONLY))) {
            throw new IllegalArgumentException("PS2_PROBAND_CALL_INCOMPLETE");
        }
        var observed = record.path("observation");
        String gt = observed.path("genotype").asText();
        require(gt.matches("[01][/|][01]"), "PS2_PROBAND_GT_UNSUPPORTED");
        String relative = gt.replace("0", "REF").replace("1", "TARGET_ALT");
        require(relative.equals(call.getCandidateRelativeGenotype()), "PS2_PROBAND_GT_CONFLICT");
        String expectedCall = switch (observed.path("call").asText()) { case "HET_ALT" -> "HET_TARGET"; case "HOM_ALT" -> "HOM_TARGET"; case "HOM_REF" -> "HOM_REF"; default -> "UNSUPPORTED"; };
        require(call.getTargetAlleleCall() != null && expectedCall.equals(call.getTargetAlleleCall().name()), "PS2_PROBAND_CALL_CONFLICT");
        require(Objects.equals(call.getDepth(), observed.path("depth").isInt() ? observed.path("depth").intValue() : null)
                && call.getDepth() != null && call.getGenotypeQuality() != null
                && observed.path("genotypeQuality").isInt() && call.getGenotypeQuality() == observed.path("genotypeQuality").intValue()
                && call.getReferenceDepth() != null && call.getTargetAlleleDepth() != null
                && (call.getReferenceDepth() + "," + call.getTargetAlleleDepth()).equals(observed.path("alleleDepth").asText()), "PS2_PROBAND_QUALITY_CONFLICT");
        require(observed.path("phased").isBoolean() && call.getPhasingStatus() != null
                // Single-site PS2 uses the preserved GT delimiter, not phase-set linkage or parental origin inference.
                && (observed.path("phased").booleanValue() ? (call.getPhasingStatus() == AnalysisVcfCall.PhasingStatus.RECORD_ONLY
                || call.getPhasingStatus() == AnalysisVcfCall.PhasingStatus.PHASE_SET)
                : call.getPhasingStatus() == AnalysisVcfCall.PhasingStatus.UNPHASED), "PS2_PROBAND_PHASE_CONFLICT");
        require(call.getFilterEvaluation() == AnalysisVcfCall.FilterEvaluation.PASS
                && "PASS".equals(record.path("recordFilter").asText()), "PS2_PROBAND_FILTER_CONFLICT");
    }
    public void verify(AcmgSubmission request, Prepared prepared) {
        var selection = mapper.<ObjectNode>valueToTree(request.ps2Selection());
        var expected = prepared.selection().deepCopy().put("confirmed", true);
        require(expected.equals(selection), "PS2_SELECTION_STALE");
        var context = prepared.bundle().snapshot().path("context");
        require(context.path("dataClass").asText().equals(request.dataClass()), "PS2_DATA_CLASS_CONFLICT");
        require(prepared.document().path("sampleId").asText().equals(request.sampleId()), "PS2_SAMPLE_CONFLICT");
        require(request.patient() != null, "PS2_PATIENT_REQUIRED");
        JsonNode patient = mapper.valueToTree(request.patient());
        for (String field : List.of("patientContextId", "familyContextId", "role", "sex"))
            require(context.path("patientContext").path(field).equals(patient.path(field)), "PS2_PATIENT_CONFLICT");
    }
    public ObjectNode evaluate(Prepared prepared) { return evaluator.evaluate(prepared.bundle()); }
    static void require(boolean condition, String code) { if (!condition) throw new IllegalArgumentException(code); }
}
