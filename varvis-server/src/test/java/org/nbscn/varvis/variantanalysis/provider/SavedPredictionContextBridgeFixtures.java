package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.*;
import org.nbscn.varvis.variantanalysis.provider.SavedPredictionContextBridge.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.SavedPredictionContextBridge.REVIEW_SCHEMA;
import static org.nbscn.varvis.variantanalysis.provider.SavedPredictionContextBridge.REVIEW_SCOPE;

/** TEST_ONLY. Reuses saved native fixture and the existing assembler's finite ledger/resource contracts. */
final class SavedPredictionContextBridgeFixtures {
    final PredictionEligibilityFixtures f = PredictionEligibilityNativeSourceTest.nativeFixture();
    final Path root;
    final Map<String, Path> ledgerFiles = new LinkedHashMap<>();
    final List<ExplicitContextSourceLedger> ledgers = new ArrayList<>();
    final List<ExplicitInput> inputs = new ArrayList<>();
    final Map<Key, ReviewBytes> reviews = new LinkedHashMap<>();
    final Map<Key, String> pins = new LinkedHashMap<>();
    final Map<Key, ObjectNode> contexts = new LinkedHashMap<>();
    final ArrayNode crosswalk = f.mapper.createArrayNode(), relationships = f.mapper.createArrayNode();
    Snapshot snapshot;
    String manifestPin;
    PatientDiseaseContextBindingResolver resolver;
    int fileIndex;

    SavedPredictionContextBridgeFixtures(Path root) throws Exception {
        this.root = root;
        f.settings.remove(List.of("catalog", "policies"));
        f.docs.get("publication").put("sampleId", "TEST_ONLY_SAMPLE").put("analysisMode", "WES");
        for (int i = 0; i < 2; i++) {
            ObjectNode row = row(i), published = published(i), key = (ObjectNode) published.at("/observation/key");
            key.put("chromosome", "11"); row.withObject("/matchedRecord").put("contig", "chr11");
            String symbol = "TEST_ONLY_GENE_" + i, ncbi = "" + (101 + i), hgnc = "HGNC:" + (101 + i);
            published.withObject("/candidateIdentity").put("geneSymbol", symbol).put("modeOfInheritance", "AD")
                    .put("schemaVersion", "candidate-identity-v1").put("canonicalVariantId", "GRCh38|11|" + (100000 + i) + "|A|G")
                    .put("source", "EXOMISER").put("sourceOrdinal", i + 1);
            published.withObject("/observation").put("sampleId", "TEST_ONLY_SAMPLE").put("originalRecord", "UNPARSEABLE_LEGACY_TOKEN");
            published.putObject("exomiserCandidateFeatures").put("geneSymbol", symbol).put("entrezGeneId", ncbi)
                    .put("rawModeOfInheritance", "AD").put("normalizedModeOfInheritance", "AUTOSOMAL_DOMINANT")
                    .put("variantSourceOrdinal", i + 1).put("geneMoiGroupRank", 1);
            ObjectNode call = published.withObject("/analysisVcfCall").put("matchingRecordCount", 1).put("targetAlleleCall", "HET_TARGET")
                    .put("candidateRelativeGenotype", "REF/TARGET_ALT").put("filterEvaluation", "PASS").put("phasingStatus", "UNPHASED");
            row.withObject("/engineCandidate").set("analysisVcfCall", call.deepCopy());
            annotation(i).put("geneSymbol", symbol).put("status", "COMPLETED").put("timestamp", "2026-09-20T00:00:00Z");
            String annotationSha = f.hash.canonicalSha256(annotation(i));
            f.prediction(i).withObject("/selection").put("annotationSourceSha256", annotationSha);
            f.prediction(i).withObject("/associationInput").put("annotationSourceSha256", annotationSha).set("queryAllele", key.deepCopy());
            ObjectNode observation = f.prediction(i).withObject("/observation"); observation.set("queryAllele", key.deepCopy());
            ObjectNode score = (ObjectNode) observation.at("/rows/0"); score.put("sourceRow", score.path("sourceRow").asText().replace("chr1\t", "chr11\t"));
            f.digest(observation, "observationSha256", "");
            f.prediction(i).withObject("/selection").set("sourceObservationSha256", observation.path("observationSha256"));
            ((ObjectNode) f.docs.get("parent").at("/candidates/" + i + "/attempts/0/prediction")).set("observation", observation.deepCopy());
            crosswalk.addObject().put("ncbiGeneId", ncbi).put("hgncId", hgnc).put("symbol", symbol).putArray("sourceRows").add(i + 2);
            for (int d = 0; d < 2; d++) relationships.addObject().put("hgncId", hgnc).put("symbol", symbol)
                    .put("mondoId", "MONDO:000010" + (i * 2 + d + 1)).put("diseaseLabel", "TEST_ONLY disease " + i + d)
                    .put("moi", "AD").put("classification", "Definitive").put("classificationDate", "2026-09-05")
                    .put("gcep", "TEST_ONLY_GCEP").put("reportLocator", "https://example.invalid/TEST_ONLY/" + i + d).put("sourceRow", i * 2 + d + 7);
        }
        reseal(); resources();
        inputs.add(input(0, "TEST_ONLY_H0", 0, "UNKNOWN", "AFFECTED", "TEST_ONLY_SELECTION"));
        inputs.add(input(1, "TEST_ONLY_H1", 0, "UNKNOWN", "AFFECTED", "TEST_ONLY_SELECTION"));
        reviewAll();
    }
    ObjectNode row(int i) { return (ObjectNode) f.docs.get("publication").path("candidates").get(i); }
    ObjectNode published(int i) { return (ObjectNode) row(i).path("published"); }
    ObjectNode annotation(int i) { return (ObjectNode) published(i).at("/transcriptAnnotations/0"); }
    void reseal() throws Exception { PredictionEligibilityNativeSourceTest.resealNative(f); }
    SavedPredictionApplicationInputs.Input saved() { var saved = f.input(); assertEquals("", saved.issue()); return saved; }
    TrustedContextSupply supply() throws Exception { return supply(inputs); }
    TrustedContextSupply supply(List<ExplicitInput> selected) throws Exception {
        return TrustedContextSupply.load(f.mapper, snapshot, selected, ledgerFiles, reviews, pins);
    }
    PreparedContexts prepare() throws Exception { return new SavedPredictionContextBridge().prepare(f.mapper, saved(), supply()); }
    List<ObjectNode> assess() throws Exception {
        var saved = saved(); var prepared = new SavedPredictionContextBridge().prepare(f.mapper, saved, supply());
        return new PredictionApplicationEligibility(f.mapper).assess(saved, prepared).stream().map(r -> java.util.Objects.requireNonNull(r).json()).toList();
    }

    void resources() throws Exception {
        ObjectNode manifest = f.mapper.createObjectNode().put("profileId", "PATIENT_DISEASE_BINDING_INTAKE")
                .put("profileVersion", "1.0.0").put("parserVersion", "1.0.0").put("testDataClass", "TEST_ONLY");
        manifest.set("intakePolicy", f.mapper.readTree("""
                {"policyId":"PATIENT_DISEASE_BINDING_INTAKE","version":"1.0.0",
                 "readyClassifications":["Definitive","Strong","Moderate"],"reviewRequiredClassifications":["Limited"],
                 "notReadyClassifications":["Disputed","Refuted","No Known Disease Relationship",""],
                 "catalogMoiMappings":{"AD":"AUTOSOMAL_DOMINANT","AR":"AUTOSOMAL_RECESSIVE"},
                 "unmappedCatalogMoi":["XL","SD","UD","MT"],"claim":"VARVIS_BINDING_INTAKE_ONLY_NOT_PATIENT_DIAGNOSIS"}
                """));
        var resources = manifest.putObject("resources");
        resources.set("mane", resource("MANE_GRCh38_SUMMARY", "1.5", crosswalk, "mane.json"));
        resources.set("clingen", resource("CLINGEN_GENE_DISEASE_VALIDITY", "TEST_ONLY_1", relationships, "clingen.json"));
        byte[] bytes = f.mapper.writeValueAsBytes(manifest); Files.write(root.resolve("manifest.json"), bytes); manifestPin = PredictionCalibration.sha256(bytes);
        snapshot = Snapshot.load(f.mapper, root.resolve("manifest.json"), root.resolve("mane.json"), root.resolve("clingen.json"), manifestPin);
        resolver = PatientDiseaseContextBindingResolver.load(f.mapper, root.resolve("manifest.json"), root.resolve("mane.json"), root.resolve("clingen.json"));
    }
    private ObjectNode resource(String id, String version, ArrayNode rows, String filename) throws Exception {
        byte[] bytes = f.mapper.writeValueAsBytes(rows); Files.write(root.resolve(filename), bytes);
        ObjectNode identity = f.mapper.createObjectNode().put("resourceId", id).put("resourceVersion", version)
                .put("sourceUrl", id.equals("MANE_GRCh38_SUMMARY") ? "https://ftp.ncbi.nlm.nih.gov/refseq/MANE/MANE_human/release_1.5/MANE.GRCh38.v1.5.summary.txt.gz"
                        : "https://search.clinicalgenome.org/kb/gene-validity/download")
                .put("retrievedAt", "2026-09-05T00:00:00Z").put("fileDate", "2026-09-05").put("rawSha256", PredictionCalibration.sha256(bytes))
                .put("headerSha256", PredictionCalibration.sha256("TEST_ONLY".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .put("parserVersion", "1.0.0").put("derivedSha256", PredictionCalibration.sha256(bytes)).put("derivedRecordCount", rows.size());
        if (id.equals("MANE_GRCh38_SUMMARY")) identity.put("assembly", "GRCh38"); return identity;
    }

    ExplicitInput input(int i, String hypothesisId, int disease, String sex, String affected, String policy) throws Exception {
        var source = SavedPredictionApplicationInputs.contextSource(f.mapper, saved(), f.candidate(i));
        String candidateId = source.candidate().getCandidateId(), patientId = "patient-context-test-only-bridge";
        var hypothesis = new PatientDiseaseHypothesis(candidateId, hypothesisId, "HGNC:" + (101 + i), "MONDO:000010" + (i * 2 + disease + 1), "AD",
                new HypothesisProvenance("TEST_ONLY_DECLARATION", "1", "TEST_ONLY/hypotheses/" + hypothesisId));
        var binding = resolver.resolve(source.candidate(), List.of(hypothesis)).bindings().get(0);
        assertEquals("READY", binding.status());
        String full = source.selection().path("versionedTranscriptId").asText(); int dot = full.lastIndexOf('.');
        String tx = full.substring(0, dot); int version = Integer.parseInt(full.substring(dot + 1));
        var allele = new Allele("GRCh38", "11", 100000 + i, "A", "G");
        // Actual tiny TEST_ONLY reference bytes, never a patient resource or a production REF claim.
        byte[] fasta = ">11 TEST_ONLY\nAA\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals('A', (char) fasta[fasta.length - 3 + i]);
        String refSha = PredictionCalibration.sha256(fasta);
        ObjectNode records = f.mapper.createObjectNode();
        records.putObject("reference").put("kind", "REFERENCE_IDENTITY").put("assembly", "GRCh38")
                .put("observedSha256", refSha).put("expectedSha256", refSha).put("verificationStatus", "VERIFIED")
                .put("verificationMethod", "SHA-256").put("verificationVersion", "TEST_ONLY_1")
                .put("verificationRecordLocator", "TEST_ONLY/ref-check").put("referenceArtifactLocator", "TEST_ONLY/reference.fa");
        var referenceLedger = ledger(records); var reference = referenceLedger.reference("reference");
        records = f.mapper.createObjectNode();
        ObjectNode patient = associated("PATIENT_CONTEXT", candidateId, hypothesisId, patientId).put("sampleId", "TEST_ONLY_SAMPLE")
                .put("role", "PROBAND").put("sex", sex).put("affectedStatus", affected);
        patient.putObject("hypothesis").put("hgncId", binding.hgncId()).put("mondoId", binding.mondoId()).put("moi", binding.normalizedMoi())
                .set("provenance", f.mapper.valueToTree(hypothesis.provenance()));
        records.set("patient", patient);
        records.set("transcript", associated("TRANSCRIPT_CHOICE", candidateId, hypothesisId, patientId).put("accession", tx).put("version", version)
                .put("selectionPolicy", policy).put("annotationSource", "TEST_ONLY_VEP").put("annotationSourceVersion", "1")
                .put("annotationSourceSha256", source.selection().path("annotationSourceSha256").asText())
                .put("hgncId", binding.hgncId()).put("geneSymbol", binding.symbol()).put("annotationRecordLocator", "TEST_ONLY/annotation/" + tx));
        records.set("lineage", associated("ASSEMBLY_LINEAGE", candidateId, hypothesisId, patientId).put("originalInputAssembly", "GRCh38")
                .put("preRankingMappingStatus", "NATIVE").set("originalInputAllele", f.mapper.valueToTree(allele)));
        ObjectNode norm = associated("NORMALIZATION", candidateId, hypothesisId, patientId).put("originalRecord", source.mappingToken())
                .put("selectedAltIndex", 1).put("method", "TEST_ONLY_NORMALIZATION").put("methodVersion", "1").put("executionStatus", "SUCCEEDED")
                .put("status", "UNCHANGED").put("refValidation", "MATCH").put("inputObservedReference", "A").put("normalizedObservedReference", "A")
                .put("refValidationMethod", "TEST_ONLY_REFERENCE_SLICE").put("refValidationVersion", "1").put("refValidationRecordLocator", "TEST_ONLY/ref-check");
        norm.set("originalAllele", f.mapper.valueToTree(allele)); norm.set("normalizedAllele", f.mapper.valueToTree(allele)); norm.set("reference", f.mapper.valueToTree(reference));
        records.set("normalization", norm); var ledger = ledger(records);
        return new ExplicitInput(candidateId, hypothesisId, binding, "SYNTHETIC",
                new TranscriptChoice(tx, version, policy, "TEST_ONLY_VEP", "1", ledger.reference("transcript")),
                new PatientContext(patientId, "PROBAND", affected, sex, null, ledger.reference("patient")),
                new NormalizationInput(allele, allele, 1, source.mappingToken(), "TEST_ONLY_NORMALIZATION", "1", ledger.reference("normalization"), reference,
                        new AssemblyLineage("GRCh38", "NATIVE", allele, null, ledger.reference("lineage"))));
    }
    private ObjectNode associated(String kind, String candidate, String hypothesis, String patient) {
        return f.mapper.createObjectNode().put("kind", kind).put("analysisId", "test-analysis").put("candidateId", candidate)
                .put("hypothesisId", hypothesis).put("patientContextId", patient);
    }
    private ExplicitContextSourceLedger ledger(ObjectNode records) throws Exception {
        ObjectNode envelope = f.mapper.createObjectNode().put("source", "TEST_ONLY_LEDGER_" + (++fileIndex)).put("version", "1").put("dataClass", "SYNTHETIC");
        envelope.set("records", records); byte[] bytes = f.mapper.writeValueAsBytes(envelope); String pin = PredictionCalibration.sha256(bytes);
        Path path = root.resolve("ledger-" + fileIndex + ".json"); Files.write(path, bytes); ledgerFiles.put(pin, path);
        var loaded = ExplicitContextSourceLedger.load(f.mapper, path, pin); ledgers.add(loaded); return loaded;
    }
    void reviewAll() throws Exception {
        var saved = saved(); var sources = List.of(SavedPredictionApplicationInputs.contextSource(f.mapper, saved, f.candidate(0)),
                SavedPredictionApplicationInputs.contextSource(f.mapper, saved, f.candidate(1)));
        var assembled = new ExplicitEvidenceContextAssembler(f.mapper, resolver, ledgers).assemble(sources.get(0).analysis(),
                sources.stream().map(s -> java.util.Objects.requireNonNull(s).candidate()).toList(), 2, inputs);
        for (var row : assembled.candidates()) for (var attempt : row.attempts()) {
            assertTrue(attempt.blockers().isEmpty(), attempt.blockers().toString());
            ObjectNode context = attempt.context(); assertNotNull(context);
            var key = new Key(row.candidateId(), attempt.hypothesisId()); contexts.put(key, context);
            ExplicitInput input = inputs.stream().filter(x -> x.candidateId().equals(key.candidateId()) && x.hypothesisId().equals(key.hypothesisId())).findFirst().orElseThrow();
            var selected = sources.stream().filter(x -> x.candidate().getCandidateId().equals(row.candidateId())).findFirst().orElseThrow().selection();
            ObjectNode review = f.mapper.createObjectNode().put("schemaVersion", REVIEW_SCHEMA).put("scope", REVIEW_SCOPE).put("status", "APPROVED_FOR_S1")
                    .put("reviewId", "TEST_ONLY_REVIEW_" + key.hypothesisId()).put("reviewerId", "TEST_ONLY_INDEPENDENT_REVIEWER").put("version", "1")
                    .put("testOnly", true).put("analysisId", "test-analysis").put("runId", "test-calibration-run")
                    .put("candidateId", key.candidateId()).put("hypothesisId", key.hypothesisId()).put("dataClass", "SYNTHETIC")
                    .put("contextId", context.path("contextId").asText()).put("contextSha256", f.hash.canonicalSha256(context))
                    .put("publicationByteSha256", saved.identity().at("/sourceByteSha256/publication").asText());
            review.set("annotationSourceSha256", selected.path("annotationSourceSha256")); review.set("versionedTranscriptId", selected.path("versionedTranscriptId"));
            ObjectNode adopted = review.putObject("adoptedSources").put("manifestSha256", manifestPin);
            adopted.set("resources", f.mapper.readTree(Files.readAllBytes(root.resolve("manifest.json"))).path("resources"));
            ObjectNode refs = adopted.putObject("ledgerReferences"); refs.set("patient", f.mapper.valueToTree(input.patient().source()));
            refs.set("transcript", f.mapper.valueToTree(input.transcript().source())); refs.set("normalization", f.mapper.valueToTree(input.normalization().source()));
            refs.set("reference", f.mapper.valueToTree(input.normalization().reference())); refs.set("lineage", f.mapper.valueToTree(input.normalization().lineage().source()));
            putReview(key, review);
        }
    }
    void putReview(Key key, JsonNode review) throws Exception {
        byte[] bytes = f.mapper.writeValueAsBytes(review); reviews.put(key, new ReviewBytes(bytes)); pins.put(key, PredictionCalibration.sha256(bytes));
    }
    static ExplicitInput copy(ExplicitInput input, TranscriptChoice transcript, PatientContext patient, NormalizationInput norm) {
        return new ExplicitInput(input.candidateId(), input.hypothesisId(), input.binding(), input.dataClass(), transcript, patient, norm);
    }
}
