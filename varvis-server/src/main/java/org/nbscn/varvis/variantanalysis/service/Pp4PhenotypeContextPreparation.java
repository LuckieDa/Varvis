package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.engine.PublishedSampleFilter;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.*;

/** Read-only Top1/proband binding. Phenotype scientific applicability is evaluated only after the durable claim. */
public final class Pp4PhenotypeContextPreparation {
    public static final String VERSION = "1.0.0";
    public static final String POLICY = "1.0.0-pp4-phenotype-development";
    public static final String TOKEN = "PP4_PHENOTYPE_CONTEXT@1.0.0:";
    private static final List<String> PARTS = List.of("facts", "phenotypeReview", "diagnosticYield", "locusAttribution");
    private final ObjectMapper mapper;
    private final Path file;
    private final String rawPin, canonicalPin;
    private final AcmgBusinessArtifacts hashes;
    private final Pp4PhenotypeCriterionEvaluator evaluator;
    public Pp4PhenotypeContextPreparation(ObjectMapper mapper, Path file, String rawPin, String canonicalPin) {
        this.mapper = mapper; this.file = file; this.rawPin = rawPin; this.canonicalPin = canonicalPin;
        hashes = new AcmgBusinessArtifacts(mapper); evaluator = new Pp4PhenotypeCriterionEvaluator(mapper);
    }
    public ObjectNode pins() { return mapper.createObjectNode().put("bundleRawSha256", rawPin).put("bundleCanonicalSha256", canonicalPin); }
    public Pp4PhenotypeBundle load() {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(Ps2DeNovoBundle.MAX_BYTES + 1);
            require(bytes.length <= Ps2DeNovoBundle.MAX_BYTES, "PP4_MATERIAL_SIZE_LIMIT");
            return Pp4PhenotypeBundle.load(mapper, bytes, rawPin, canonicalPin);
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("PP4_MATERIAL_UNAVAILABLE"); }
    }
    public record Prepared(Pp4PhenotypeBundle bundle, ObjectNode document, ObjectNode selection) {
        public Prepared { Objects.requireNonNull(bundle); document = document.deepCopy(); selection = selection.deepCopy(); }
        @Override public ObjectNode document() { return document.deepCopy(); }
        @Override public ObjectNode selection() { return selection.deepCopy(); }
    }
    private boolean same(JsonNode a, JsonNode b) {
        return !a.isMissingNode() && !b.isMissingNode() && hashes.requestHash(a).equals(hashes.requestHash(b));
    }
    private ObjectNode allele(VariantInterpretation candidate) {
        var observation = candidate.getObservation();
        require(observation != null, "PP4_CANDIDATE_ALLELE_REQUIRED");
        if (observation == null) throw new IllegalArgumentException("PP4_CANDIDATE_ALLELE_REQUIRED");
        var key = observation.getKey();
        return mapper.createObjectNode().put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome())
                .put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
    }
    public List<VariantInterpretation> select(List<VariantInterpretation> published, Pp4PhenotypeBundle bundle) {
        require(!published.isEmpty(), "PP4_FORMAL_CANDIDATE_REQUIRED");
        var root = bundle.snapshot(); var context = root.path("context");
        require(root.path("observations").size() == 1, "PP4_SINGLE_OBSERVATION_REQUIRED");
        var target = published.get(0); var identity = target.getCandidateIdentity();
        require(same(allele(target), context.at("/variant/inputAllele")), "PP4_TARGET_ALLELE_CONFLICT");
        require(identity != null && context.at("/gene/symbol").asText().equals(identity.getGeneSymbol())
                && List.of("AD", "AUTOSOMAL_DOMINANT").contains(Objects.toString(identity.getModeOfInheritance(), ""))
                && "AUTOSOMAL_DOMINANT".equals(context.path("moi").asText()), "PP4_CANDIDATE_BACKGROUND_CONFLICT");
        return List.of(target);
    }
    public Prepared prepare(VariantAnalysisJob job, List<VariantInterpretation> selected, AcmgExecutionSources.Recovered recovered,
                            JsonNode publishedIdentity, JsonNode profile, Pp4PhenotypeBundle bundle) {
        var root = bundle.snapshot(); var context = root.path("context"); var observation = root.at("/observations/0");
        var facts = observation.path("facts"); var patient = context.path("patientContext");
        for (String field : List.of("patientContextId", "familyContextId")) require(patient.path(field).isTextual() && !patient.path(field).asText().isBlank(),
                field.equals("familyContextId") ? "PP4_FAMILY_CONTEXT_REQUIRED" : "PP4_PATIENT_CONTEXT_REQUIRED");
        try { new RuntimeEvidenceV2Adapter(mapper).validateVersionedContext(context); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("PP4_CONTEXT_IDENTITY_INVALID"); }
        require("evidence-context-v3".equals(context.path("schemaVersion").asText()), "PP4_EXPLICIT_V3_REQUIRED");
        require(selected.size() == 1, "PP4_TOP_ONE_REQUIRED");
        for (String field : List.of("caseId", "observationId", "occurrenceId", "familyId")) require(facts.path(field).isTextual() && !facts.path(field).asText().isBlank(), "PP4_CASE_BINDING_REQUIRED");
        var factBinding = facts.path("binding");
        for (String field : List.of("observationId")) require(same(factBinding.path(field), facts.path(field)), "PP4_CASE_BINDING_CONFLICT");
        for (var entry : Map.of("contextId", "/contextId", "alleleId", "/variant/alleleId", "hgncId", "/gene/hgncId", "mondoId", "/disease/mondoId", "moi", "/moi",
                "patientContextId", "/patientContext/patientContextId", "familyContextId", "/patientContext/familyContextId").entrySet())
            require(same(factBinding.path(entry.getKey()), context.at(entry.getValue())), "PP4_CONTEXT_BINDING_CONFLICT");
        integrity(root, observation, factBinding);
        for (var blocker : recovered.candidateBlockers().values()) require(!blocker.reasonCode().startsWith("EXECUTION_SAMPLE_FILTER_"), "PP4_PUBLISHED_SAMPLE_FILTER_BINDING_CONFLICT");
        require(recovered.candidateBlockers().isEmpty(), "PP4_PUBLISHED_CANDIDATE_SOURCE_CONFLICT");
        var source = recovered.execution().getInputSource();
        if (source == null) throw new IllegalArgumentException("PP4_PUBLISHED_SOURCE_REQUIRED");
        require(Objects.equals(source.uploadSha256(), source.observedVcfSha256()), "PP4_NATIVE_SOURCE_DIGEST_CONFLICT");
        var probands = new ArrayList<JsonNode>();
        for (var member : facts.path("members")) if ("PROBAND".equals(member.path("role").asText())) probands.add(member);
        require(facts.path("members").size() == 1 && probands.size() == 1, "PP4_PROBAND_REQUIRED");
        var proband = probands.get(0);
        String sample = job.getSelectedSampleId() == null ? job.getRequestedSampleId() : job.getSelectedSampleId();
        if (!(sample != null && sample.equals(proband.path("sampleId").asText())
                && (job.getRequestedSampleId() == null || sample.equals(job.getRequestedSampleId())))) {
            throw new IllegalArgumentException("PP4_SAMPLE_CONFLICT");
        }
        require("PROBAND".equals(patient.path("role").asText()) && same(patient.path("patientContextId"), proband.path("memberId"))
                && same(patient.path("affectedStatus"), proband.path("affectedStatus")) && same(patient.path("familyContextId"), facts.path("familyId")), "PP4_PATIENT_MATERIAL_CONFLICT");
        var candidate = selected.get(0);
        var issue = CandidateAcmgInputAdapter.sourceIssue(mapper, job, recovered.conversion(), recovered.execution(),
                new AnalysisContext(job.getId(), job.getGenomeAssembly(), job.getAnalysisMode()), candidate, false);
        require(issue == null, issue == null ? "PP4_SOURCE_INVALID" : issue.reasonCode());
        var target = candidate.getObservation();
        if (target == null) throw new IllegalArgumentException("PP4_CANDIDATE_ALLELE_REQUIRED");
        var key = target.getKey(); var allele = allele(candidate);
        require("GRCh38".equals(key.getGenomeAssembly()), "PP4_NATIVE_GRCH38_REQUIRED");
        for (String field : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) require(same(allele, context.path("variant").path(field)), "PP4_CORE_ALLELE_CONFLICT");
        require(same(factBinding.path("allele"), allele), "PP4_TARGET_CONTEXT_CONFLICT");
        var record = source.matchedRecord(key);
        require(record != null && record.selectedAltIndex() == 1 && record.alternates().size() == 1 && record.position() == key.getPosition()
                && record.reference().equals(key.getReference()) && record.alternates().get(0).equals(key.getAlternate())
                && record.contig().replaceFirst("^chr", "").equals(key.getChromosome()), "PP4_ORIGINAL_RECORD_CONFLICT");
        var records = new ArrayList<JsonNode>();
        for (var r : facts.path("records")) if ("PROBAND".equals(r.path("role").asText())) records.add(r);
        require(facts.path("records").size() == 1 && records.size() == 1, "PP4_PROBAND_RECORD_CONFLICT"); var probandRecord = records.get(0);
        var recordAllele = ((ObjectNode) probandRecord.path("variantKey")).deepCopy();
        recordAllele.set("contig", recordAllele.remove("chromosome"));
        require(sample.equals(probandRecord.path("sampleId").asText()) && same(recordAllele, allele), "PP4_PROBAND_RECORD_CONFLICT");
        var filter = source.sampleFilter(key);
        if (filter == null) throw new IllegalArgumentException("PP4_PUBLISHED_SAMPLE_FILTER_WITNESS_REQUIRED");
        require(sample.equals(filter.sampleId()) && Objects.equals(key.getGenomeAssembly(), filter.assembly()) && Objects.equals(record, filter.record()), "PP4_PUBLISHED_SAMPLE_FILTER_BINDING_CONFLICT");
        require(filter.state() != PublishedSampleFilter.State.FILTERED, "PP4_PUBLISHED_SAMPLE_FILTER_FAILED");
        require(filter.state() == PublishedSampleFilter.State.PASS, "PP4_PUBLISHED_SAMPLE_FILTER_UNKNOWN");
        require("PASS".equals(probandRecord.path("sampleFilter").asText()), "PP4_PUBLISHED_SAMPLE_FILTER_MATERIAL_CONFLICT");
        new Pp4PhenotypeRecordAdapter().adapt((ObjectNode) facts);
        verifyCall(candidate.getAnalysisVcfCall(), probandRecord);
        ObjectNode binding = mapper.createObjectNode().put("schemaVersion", "pp4-phenotype-business-binding-v1");
        binding.put("analysisId", job.getId()).put("sampleId", sample).put("dataClass", context.path("dataClass").asText());
        binding.set("materialPins", pins()); binding.set("context", context.deepCopy()); binding.set("familyBinding", factBinding.deepCopy());
        binding.set("publishedInputIdentity", publishedIdentity.deepCopy()); binding.put("candidateId", candidate.getCandidateId());
        var parts = binding.putObject("partPins"); for (String part : PARTS) parts.put(part, hashes.requestHash(observation.path(part)));
        binding.put("assessmentSha256", hashes.requestHash(observation.path("assessment"))).put("policySha256", hashes.requestHash(root.path("policy")))
                .put("catalogSha256", hashes.requestHash(root.path("catalog")));
        binding.set("registryBinding", root.path("registryBinding").deepCopy()); binding.set("executionProfile", profile.deepCopy());
        String bindingHash = hashes.requestHash(binding);
        var document = mapper.createObjectNode().put("schemaVersion", "pp4-phenotype-context-preparation-v1").put("version", VERSION).put("policyVersion", POLICY)
                .put("status", "BOUND_NOT_EVALUATED").put("scientificApplicability", "NOT_EVALUATED").put("analysisId", job.getId()).put("sampleId", sample)
                .put("candidateId", candidate.getCandidateId()).put("materialBindingSha256", bindingHash).put("criterionPolicySelected", false).put("predictionCompatibilityClaimed", false);
        document.set("materialBinding", binding); document.set("patientContext", patient.deepCopy()); document.set("reviewedObservation", observation.deepCopy());
        String token = TOKEN + hashes.requestHash(document); document.put("preparationToken", token);
        var selection = mapper.createObjectNode().put("candidateId", candidate.getCandidateId());
        for (String field : List.of("contextId", "alleleId", "observationId", "occurrenceId", "caseId", "familyContextId")) selection.set(field, (List.of("caseId", "occurrenceId").contains(field) ? facts : factBinding).path(field).deepCopy());
        selection.put("materialBindingSha256", bindingHash).put("preparationToken", token).put("policyVersion", POLICY).put("confirmed", false);
        return new Prepared(bundle, document, selection);
    }
    /** Integrity and exact binding only, not a scientific policy selection or family evaluation. */
    private void integrity(JsonNode root, JsonNode observation, JsonNode binding) {
        for (String part : PARTS) {
            var document = observation.path(part); String digest = hashes.requestHash(document);
            require(same(document.path("binding"), binding), "PP4_PART_BINDING_CONFLICT");
            require(digest.equals(observation.at("/assessment/partPins/" + part).asText()) && digest.equals(root.at("/policy/partPins/" + part).asText()), "PP4_PART_PIN_MISMATCH");
            var projection = ((ObjectNode) document).deepCopy(); projection.remove("sources");
            for (var source : document.path("sources")) {
                String raw = source.path("rawUtf8").asText();
                require(AcmgBusinessStore.sha(raw).equals(source.path("rawSha256").asText()), "PP4_SOURCE_RAW_PIN_MISMATCH");
                JsonNode report; String canonical;
                try {
                    report = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
                    if (report == null || !report.isObject()) throw new IllegalArgumentException();
                    canonical = hashes.requestHash(report);
                } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid) {
                    throw new IllegalArgumentException("PP4_SOURCE_DOCUMENT_INVALID");
                }
                require(canonical.equals(source.path("canonicalSha256").asText()), "PP4_SOURCE_CANONICAL_PIN_MISMATCH");
                require(same(report, projection), "PP4_SOURCE_FACTS_MISMATCH");
            }
        }
        var assessment = ((ObjectNode) observation.path("assessment")).deepCopy(); assessment.remove("contentSha256");
        require(hashes.requestHash(assessment).equals(observation.at("/assessment/contentSha256").asText())
                && hashes.requestHash(observation.path("assessment")).equals(root.at("/policy/assessmentSha256").asText()), "PP4_ASSESSMENT_PIN_MISMATCH");
        require(hashes.requestHash(root.path("catalog")).equals(root.path("catalogSha256").asText()), "PP4_CATALOG_PIN_MISMATCH");
        // Verify referenced content pins without selecting a policy or deciding its applicability.
        for (var entry : root.at("/catalog/entries")) {
            var reference = entry.path("policyBinding");
            if (same(reference.path("policyId"), root.at("/policy/policyId"))
                    && same(reference.path("policyVersion"), root.at("/policy/policyVersion")))
                require(hashes.requestHash(root.path("policy")).equals(reference.path("policySha256").asText()), "PP4_POLICY_PIN_MISMATCH");
        }
        var registryBinding = root.path("registryBinding");
        require(Pp4PhenotypeCriterionEvaluator.REGISTRY.equals(registryBinding.path("registryClasspathResource").asText()), "PP4_REGISTRY_BINDING_CONFLICT");
        try (var input = getClass().getResourceAsStream(Pp4PhenotypeCriterionEvaluator.REGISTRY)) {
            if (input == null) throw new IllegalArgumentException("PP4_REGISTRY_UNAVAILABLE");
            byte[] bytes = input.readAllBytes();
            require(Ps2DeNovoBundle.sha256(bytes).equals(registryBinding.path("registrySha256").asText()), "PP4_REGISTRY_PIN_MISMATCH");
            JsonNode registry = mapper.readTree(bytes);
            require(registry != null && same(registry.path("registryId"), registryBinding.path("registryId")), "PP4_REGISTRY_BINDING_CONFLICT");
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("PP4_REGISTRY_UNAVAILABLE"); }
    }
    public void verify(AcmgSubmission request, Prepared prepared) {
        JsonNode actual = mapper.valueToTree(request.pp4Selection());
        require(same(prepared.selection().put("confirmed", true), actual), "PP4_SELECTION_STALE");
        var context = prepared.bundle().snapshot().path("context");
        require(context.path("dataClass").asText().equals(request.dataClass()), "PP4_DATA_CLASS_CONFLICT");
        require(prepared.document().path("sampleId").asText().equals(request.sampleId()), "PP4_SAMPLE_CONFLICT");
        require(request.patient() != null, "PP4_PATIENT_REQUIRED"); JsonNode patient = mapper.valueToTree(request.patient());
        for (String field : List.of("patientContextId", "familyContextId", "role", "sex")) require(same(context.path("patientContext").path(field), patient.path(field)), "PP4_PATIENT_CONFLICT");
    }
    public ObjectNode evaluate(Prepared prepared) { return evaluator.evaluate(prepared.bundle()); }
    private static void require(boolean condition, String code) { if (!condition) throw new IllegalArgumentException(code); }
    private static void verifyCall(AnalysisVcfCall call, JsonNode record) {
        require(call != null && call.getInvalidFields().isEmpty() && call.getMissingFields().stream().allMatch(field ->
                "PS".equals(field) && call.getPhasingStatus() == AnalysisVcfCall.PhasingStatus.RECORD_ONLY), "PP4_PROBAND_CALL_INCOMPLETE");
        if (call == null) throw new IllegalArgumentException("PP4_PROBAND_CALL_INCOMPLETE");
        var observed = record.path("observation");
        String gt = observed.path("genotype").asText();
        require(gt.matches("[01][/|][01]"), "PP4_PROBAND_GT_UNSUPPORTED");
        String relative = gt.replace("0", "REF").replace("1", "TARGET_ALT");
        require(relative.equals(call.getCandidateRelativeGenotype()), "PP4_PROBAND_GT_CONFLICT");
        String expectedCall = switch (observed.path("call").asText()) { case "HET_ALT" -> "HET_TARGET"; case "HOM_ALT" -> "HOM_TARGET"; case "HOM_REF" -> "HOM_REF"; default -> "UNSUPPORTED"; };
        require(call.getTargetAlleleCall() != null && expectedCall.equals(call.getTargetAlleleCall().name()), "PP4_PROBAND_CALL_CONFLICT");
        require(Objects.equals(call.getDepth(), observed.path("depth").isInt() ? observed.path("depth").intValue() : null)
                && call.getDepth() != null && call.getGenotypeQuality() != null
                && observed.path("genotypeQuality").isInt() && call.getGenotypeQuality() == observed.path("genotypeQuality").intValue()
                && call.getReferenceDepth() != null && call.getTargetAlleleDepth() != null
                && (call.getReferenceDepth() + "," + call.getTargetAlleleDepth()).equals(observed.path("alleleDepth").asText()), "PP4_PROBAND_QUALITY_CONFLICT");
        require(observed.path("phased").isBoolean() && call.getPhasingStatus() != null
                // Single-site PP4 uses the preserved GT delimiter, not phase-set linkage or parental origin inference.
                && (observed.path("phased").booleanValue() ? (call.getPhasingStatus() == AnalysisVcfCall.PhasingStatus.RECORD_ONLY
                || call.getPhasingStatus() == AnalysisVcfCall.PhasingStatus.PHASE_SET)
                : call.getPhasingStatus() == AnalysisVcfCall.PhasingStatus.UNPHASED), "PP4_PROBAND_PHASE_CONFLICT");
        require(call.getFilterEvaluation() == AnalysisVcfCall.FilterEvaluation.PASS
                && "PASS".equals(record.path("recordFilter").asText()), "PP4_PROBAND_FILTER_CONFLICT");
    }
}
