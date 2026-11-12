package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.function.Executable;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.service.CodingTranscriptReferenceReader;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in retained actual VEP diagnostic replay. The source owner failed lifecycle acceptance;
 * successful independent parsing or sequence facts do not turn that execution into PASS.
 * Contexts are synthetic engineering hypotheses. No scientific adoption or final MET is called.
 */
@EnabledIfSystemProperty(named = "a5.reportedReplayRoot", matches = ".+")
class ReportedComplexAlleleVepReplayTest {
    private static final String ROUND2 = "local/development/handoffs/acmg-six-framework-tasks-20261101/"
            + "parallel-branches-20261103/dispatch/a5/round2/";
    private static final String SCOPE = "RETAINED_ACTUAL_VEP_DIAGNOSTIC_REPLAY";
    private static final String REFERENCE_SHA = "8d5e868ddc6d9600b82cd7b7e625c240a43f369f6f1a9f16b0c516eb781e7988";
    private static final String SOURCE_SHA = "33d7d395514047ec0fb2e7f4392efff6812de5511958dd5241fe49a7abd2d233";
    private static final String EXPECTED_SHA = "39c01d8f3fcee8eddd6fa5d514e545616a87778373bb03c6d44cb91216dd3376";
    private static final String RAW_SHA = "53793345c1054de8e31aa13361f683fd2df5f93e0045d0abad1ca0f5b6182d5d";
    private static final String EXECUTION_SHA = "b57bfc4fdc369c43260c580e2f5dcaba06058b678a691050c629825238dd46fe";
    private static final String OWNER_SHA = "209c63644807705764d5e5253ea6719958ed6a701720c446ff51222d79437315";
    private static final String PROFILE = "varvis-server/src/main/resources/acmg/pvs1-hnf1a-business-profile-v3.json";
    private static final String PROFILE_SHA = "af90bc0bc4a6b9cfcc6ba1b1eaf12d8fa50259abfd35dd489de71a7797492ef1";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @Test void retainedActualParserOutputFeedsUnscoredMechanicalConsumersWithoutInventedQualification() throws Exception {
        String configured = java.util.Objects.requireNonNull(System.getProperty("a5.reportedReplayRoot"));
        Path root = Path.of(configured).toRealPath();
        Path artifacts = root.resolve(ROUND2);
        JsonNode source = pinned(artifacts.resolve("source-receipt.json"), SOURCE_SHA);
        JsonNode expected = pinned(artifacts.resolve("independent-expected.json"), EXPECTED_SHA);
        JsonNode semantic = pinned(artifacts.resolve("carrier-semantic-adaptation.json"),
                "37904afaadc7e1ee6a299d2902b883c221af8a0762661e6e06c5af24cd4eeb26");
        for (JsonNode file : source.path("files"))
            assertEquals(file.path("rawSha256").asText(), sha(Files.readAllBytes(root.resolve(file.path("path").asText()))));
        Path rawPath = artifacts.resolve("vep-02/real-vep-output.jsonl");
        assertEquals(RAW_SHA, sha(Files.readAllBytes(rawPath)));
        JsonNode execution = pinned(artifacts.resolve("vep-02/execution-receipt.json"), EXECUTION_SHA);
        JsonNode owner = pinned(artifacts.resolve("vep-02/owner-result.json"), OWNER_SHA);
        assertEquals("FAILED", execution.path("status").asText());
        assertFalse(owner.path("success").asBoolean(true));
        assertFalse(owner.at("/ownedExecutionReceipt/outputReadComplete").asBoolean(true));
        assertTrue(owner.at("/ownedExecutionReceipt/ownedProcessesStopped").asBoolean());
        assertTrue(owner.at("/ownedExecutionReceipt/cleanupWithinBudget").asBoolean());
        assertEquals(0, owner.at("/ownedExecutionReceipt/activeProcessCount").asInt(-1));
        JsonNode preflight = retainedArtifact(artifacts, execution, "preflight.json");
        String actualVersion = preflight.at("/toolVersion/VEP").asText();
        assertEquals("116.0", actualVersion);
        JsonNode normalizedKeys = retainedArtifact(artifacts, execution, "normalized-keys.json");
        assertEquals("8e243d66d06f37a9843d0c6d4e34b1b95709a7a25b83268652626e659db48b1e",
                sha(Files.readAllBytes(artifacts.resolve("vep-02/normalized.vcf"))));
        // Pin the failed source receipt; never replace it with the independent reader's outcome.
        ObjectNode replay = mapper.createObjectNode().put("schemaVersion", "a5-reported-complex-diagnostic-replay-v1")
                .put("scope", SCOPE).put("sourceExecutionAccepted", false).put("scientificQualificationGranted", false)
                .put("clinicalAdoption", "NOT_GRANTED").put("finalMetCalled", false)
                .put("sourceReceiptSha256", SOURCE_SHA).put("independentExpectedSha256", EXPECTED_SHA)
                .put("actualVepRawSha256", RAW_SHA).put("sourceOwnerReceiptSha256", OWNER_SHA);
        replay.set("sourceExecutionReceipt", execution);
        replay.set("sourceOwnerReceipt", owner);
        replay.set("scientificQualification", semantic.path("scientificQualification").deepCopy());
        var parsed = new VepJsonOutputParser(mapper).parseByVariantId(rawPath, "VEP_CACHED", actualVersion);
        assertTrue(parsed.getWarnings().isEmpty(), parsed.getWarnings().toString());
        assertEquals(Set.of("clinvar-817605", "clinvar-997923"), parsed.getAnnotationsByVariantId().keySet());
        replay.put("independentProductionParserCompleted", true);
        replay.put("annotationTimestampMeaning", "CURRENT_PARSER_INGESTION_TIME_NOT_VEP_EXECUTION_TIME");
        var rawById = new java.util.LinkedHashMap<String, JsonNode>();
        for (String line : Files.readAllLines(rawPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode record = mapper.readTree(line);
            assertNull(rawById.put(record.path("id").asText(), record), "Duplicate raw VEP record");
        }
        JsonNode profile = pinned(root.resolve(PROFILE), PROFILE_SHA);
        ObjectNode mechanics = mechanics(profile);
        replay.put("mechanicalParametersSourceRawSha256", PROFILE_SHA)
                .put("mechanicalParametersAdoptScientificPolicy", false);
        replay.set("mechanicalParameters", mechanics.deepCopy());
        var reader = reference(root);
        var index = new GencodeTranscriptIndex(mapper, root.resolve("local/resources/gencode/derived/v38-transcripts-v1"),
                "21d5d8db2fc4015d4f44ff2e6efbc5ca2f52a03352e08144b5177a7f2717c801");
        var supply = new CodingTranscriptMaterialSupply(mapper, null, "", reader, index, true);
        var rows = replay.putArray("cases");
        List<Executable> checks = new ArrayList<>();
        for (JsonNode vector : expected.path("cases")) {
            String id = vector.path("caseId").asText();
            JsonNode allele = vector.path("queryAllele");
            JsonNode normalized = java.util.stream.StreamSupport.stream(normalizedKeys.spliterator(), false)
                    .filter(n -> id.equals(n.path("id").asText())).findFirst().orElseThrow();
            for (String field : List.of("assembly", "contig", "position", "reference", "alternate"))
                assertEquals(allele.path(field), normalized.path(field), "Actual normalization changed " + field);
            var key = new NormalizedVariantKey(allele.path("assembly").asText(), allele.path("contig").asText(),
                    allele.path("position").asInt(), allele.path("reference").asText(), allele.path("alternate").asText());
            JsonNode rawRecord = java.util.Objects.requireNonNull(rawById.get(id));
            var exact = parsed.getAnnotationsByVariantId().get(id).stream()
                    .filter(tx -> "ENST00000257555.11".equals(tx.getVersionedTranscriptId())).toList();
            assertEquals(1, exact.size(), id);
            TranscriptConsequence tx = exact.get(0);
            var rawExact = java.util.stream.StreamSupport.stream(rawRecord.path("transcript_consequences").spliterator(), false)
                    .filter(n -> "ENST00000257555.11".equals(n.path("transcript_id").asText())).toList();
            assertEquals(1, rawExact.size(), id);
            JsonNode rawTx = rawExact.get(0);
            assertEquals(rawTx.path("hgvsc").asText(), tx.getHgvsc());
            assertEquals(rawTx.path("hgvsp").asText(), tx.getHgvsp());
            assertEquals(rawTx.path("exon").asText(), tx.getExon());
            assertEquals(rawTx.path("codons").asText(), tx.getCodons());
            assertEquals(rawTx.path("amino_acids").asText(), tx.getAminoAcids());
            assertTrue(rawTx.path("protein_start").isIntegralNumber());
            assertTrue(rawTx.path("protein_end").isIntegralNumber());
            String localPosition = rawTx.path("protein_start").equals(rawTx.path("protein_end"))
                    ? rawTx.path("protein_start").asText()
                    : rawTx.path("protein_start").asText() + "-" + rawTx.path("protein_end").asText();
            assertEquals(localPosition, tx.getProteinPosition());
            assertEquals(actualVersion, tx.getVersion());
            assertEquals(vector.at("/expectedVep/hgvsCoding").asText(), tx.getHgvsc());
            assertEquals(vector.at("/expectedVep/hgvsProtein").asText(), tx.getHgvsp());
            JsonNode carrier = caseById(semantic.path("cases"), id);
            ObjectNode context = context(profile, allele, carrier.path("runtimeCarrierVariantType").asText());
            adapter.validateCompleteContext(context);
            assertEquals(key.getReference(), reader.read(key.getChromosome(), key.getPosition(), key.getReference().length()).bases());
            var observation = new VariantQcObservation(key, "PUBLIC_ALLELE_ENGINEERING_CONTEXT", null, null, null,
                    null, null, "PASS", carrier.path("runtimeCarrierVariantType").asText(), SCOPE);
            var candidate = new VariantInterpretation(key.canonicalId(), observation, List.of(), List.of());
            candidate.applyTranscriptAnnotations(parsed.getAnnotationsByVariantId().get(id));
            var structure = supply.resolveStructure(context, candidate);
            ObjectNode row = rows.addObject().put("caseId", id).put("scope", SCOPE).put("sourceExecutionAccepted", false)
                    .put("biologicalChange", "delins").put("runtimeCarrierVariantType", carrier.path("runtimeCarrierVariantType").asText());
            row.set("queryAllele", allele.deepCopy()); row.set("engineeringContext", context.deepCopy());
            row.set("rawSelectedTranscript", rawTx.deepCopy()); row.set("parsedSelectedAnnotation", mapper.valueToTree(tx));
            row.set("structure", structure.snapshot());
            String predeclaredTerm = vector.at("/expectedVep/consequenceTerm").asText();
            boolean exactTerm = tx.getConsequence() != null && List.of(tx.getConsequence().split("&")).contains(predeclaredTerm);
            row.put("predeclaredConsequenceTerm", predeclaredTerm).put("predeclaredTermPresent", exactTerm)
                    .put("consequenceMismatchPreserved", !exactTerm);
            checks.add(() -> assertTrue(structure.ready(), row::toPrettyString));
            if (!structure.ready()) continue;
            ObjectNode model = java.util.Objects.requireNonNull(structure.model());
            assertEquals(expected.at("/reference/cdsSha256").asText(), sha(model.path("cdsSequence").asText().getBytes(StandardCharsets.US_ASCII)));
            assertEquals(expected.at("/reference/fullSplicedSha256").asText(), structure.snapshot().at("/verification/fullSplicedTranscriptSha256").asText());
            var bound = CodingTranscriptEdits.bind(mapper, context, model, adapter.canonicalSha256(model),
                    expected.at("/reference/cdsSha256").asText());
            var edit = CodingTranscriptEdits.projectAllele(bound, allele, adapter.canonicalSha256(allele));
            var sequence = CodingTranscriptEdits.apply(bound, List.of(edit));
            row.set("sequenceDerivation", sequence.snapshot(mapper));
            checks.add(() -> assertEquals(vector.path("alteredCds").asText(), sequence.alteredCds()));
            checks.add(() -> assertEquals(vector.path("alteredToReferenceSegments"), sequence.snapshot(mapper).path("alteredToReference")));
            checks.add(() -> assertEquals(vector.path("netNucleotideChange").asInt(), sequence.delta()));
            checks.add(() -> assertEquals(vector.path("alteredLastJunctionCdsPosition").asInt(),
                    sequence.junctions().get(sequence.junctions().size() - 1).intValue()));
            if (vector.path("frameChange").asBoolean()) {
                var nmd = new Pvs1TranscriptMaterialSupply(mapper, supply).resolve(context, candidate);
                row.set("technicalNmdStructure", nmd.audit());
                checks.add(() -> assertTrue(nmd.ready(), row::toPrettyString));
                if (!nmd.ready()) continue;
                ObjectNode nmdModel = java.util.Objects.requireNonNull(nmd.model());
                ObjectNode facts = mapper.createObjectNode();
                String issue = new TruncatingFrameshiftResolver(nmdModel, mechanics, "ENST00000257555.11",
                        "UNRESOLVED", "UNRESOLVED", true).resolve(context, tx, facts);
                row.put("frameshiftIssue", issue).set("mechanicalFacts", facts);
                checks.add(() -> assertNull(issue, row::toPrettyString));
                if (issue == null) {
                    checks.add(() -> assertEquals(vector.path("alteredCdsSha256").asText(), facts.path("alteredCdsSha256").asText()));
                    checks.add(() -> assertEquals(vector.path("alteredCds").asText(), facts.path("alteredCdsSequence").asText()));
                    checks.add(() -> assertEquals(vector.path("ptcStart1").asInt(), facts.path("ptcStart").asInt()));
                    checks.add(() -> assertEquals(vector.path("ptcEnd1").asInt(), facts.path("ptcEnd").asInt()));
                    checks.add(() -> assertEquals(vector.path("distanceToLastJunction").asInt(), facts.path("distanceToLastJunction").asInt()));
                    checks.add(() -> assertEquals(vector.path("alteredLastJunctionCdsPosition").asInt(), facts.path("mutantLastJunctionCdsPosition").asInt()));
                    checks.add(() -> assertEquals(vector.path("firstChangedProteinPosition").asInt(), facts.path("firstChangedProteinPosition").asInt()));
                    checks.add(() -> assertEquals(vector.path("ptcExonOrdinal").asInt(), facts.path("ptcExonOrdinal").asInt()));
                    checks.add(() -> assertEquals(vector.path("alteredVariantExonLength").asInt(), facts.path("alteredVariantExonLength").asInt()));
                    checks.add(() -> assertEquals(vector.path("alteredVariantExonLength").asInt(), facts.path("alteredPtcExonLength").asInt()));
                    String[] ptcFields = {"ptcReferenceStart", "ptcReferenceMiddle", "ptcReferenceEnd"};
                    for (int i = 0; i < ptcFields.length; i++) {
                        int indexPosition = i;
                        checks.add(() -> assertEquals(vector.path("ptcReferenceBases1").path(indexPosition).asInt(), facts.path(ptcFields[indexPosition]).asInt()));
                    }
                    checks.add(() -> assertEquals("288", tx.getProteinPosition()));
                    checks.add(() -> assertEquals("GGG", facts.path("refCodon").asText()));
                    checks.add(() -> assertEquals("AGG", facts.path("altCodon").asText()));
                    checks.add(() -> assertEquals("UNRESOLVED", facts.path("mechanism").asText()));
                    checks.add(() -> assertEquals("UNRESOLVED", facts.path("exonRelevance").asText()));
                }
                assertFalse(facts.has("evaluation")); assertFalse(facts.has("appliedStrength"));
            } else {
                var length = supply.resolveProteinLengthChange(context, candidate);
                row.set("lengthMaterial", length.snapshot());
                checks.add(() -> assertTrue(length.ready(), row::toPrettyString));
                if (length.ready()) {
                    JsonNode facts = length.snapshot().path("alleleAssociation");
                    checks.add(() -> assertEquals(vector.path("alteredCdsSha256").asText(), facts.path("alteredCdsSha256").asText()));
                    checks.add(() -> assertEquals(vector.path("netAminoAcidChange").asInt(), facts.path("netAminoAcidChange").asInt()));
                    checks.add(() -> assertEquals(vector.path("alteredProteinThroughTerminationSha256").asText(), facts.path("alteredProteinSha256").asText()));
                }
            }
        }
        Path output = artifacts.resolve("direct-diagnostic-replay"); Files.createDirectories(output);
        Files.write(output.resolve("retained-actual-replay-" + UUID.randomUUID() + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(replay), StandardOpenOption.CREATE_NEW);
        assertAll("Retained actual VEP diagnostic sequence facts; source execution remains unaccepted", checks);
    }

    private ObjectNode mechanics(JsonNode profile) {
        ObjectNode mechanics = mapper.createObjectNode();
        for (String field : List.of("minimumCdsPosition", "firstExon", "lastExon", "maximumExonLength"))
            mechanics.set(field, profile.path("policy").path(field).deepCopy());
        ObjectNode frameshift = mechanics.putObject("frameshift");
        for (String field : List.of("maximumAlleleLengthIncludingAnchor", "minimumDistanceToLastJunction"))
            frameshift.set(field, profile.at("/policy/frameshift").path(field).deepCopy());
        return mechanics;
    }

    private ObjectNode context(JsonNode profile, JsonNode allele, String carrierType) {
        ObjectNode context = mapper.createObjectNode().put("schemaVersion", "evidence-context-v2")
                .put("canonicalizationProfile", "varvis-json-c14n-v1").put("dataClass", "SYNTHETIC");
        context.putObject("scope").put("analysisDomain", "GERMLINE").put("diseaseModel", "MENDELIAN").put("variantScope", "SMALL_SEQUENCE_VARIANT");
        ObjectNode variant = context.putObject("variant").put("variantType", carrierType);
        for (String field : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) variant.set(field, allele.deepCopy());
        ObjectNode normalization = variant.putObject("normalization").put("status", "UNCHANGED")
                .put("method", "BCFTOOLS_NORM_RETAINED_PUBLIC_REPLAY").put("methodVersion", "1.23.1")
                .put("referenceSequenceSha256", REFERENCE_SHA).put("refValidation", "MATCH");
        variant.putObject("assemblyLineage").put("mappingStatus", "NATIVE");
        variant.put("alleleId", adapter.canonicalAlleleId(allele, normalization));
        context.putObject("gene").put("hgncId", profile.at("/context/hgncId").asText()).put("symbol", "HNF1A");
        context.putObject("disease").put("mondoId", profile.at("/context/mondoId").asText())
                .put("label", "ENGINEERING_HYPOTHESIS_ONLY_SCIENTIFIC_DISEASE_QUALIFICATION_UNRESOLVED");
        context.put("moi", profile.at("/context/moi").asText());
        context.putObject("transcript").put("accession", "ENST00000257555").put("version", 11)
                .put("selectionPolicy", "A5_RETAINED_ACTUAL_VEP_TECHNICAL_REPLAY").put("annotationSourceVersion", "116.0");
        context.putObject("patientContext").put("patientContextId", "patient-context-a5-public-engineering")
                .put("role", "PROBAND").put("affectedStatus", "UNKNOWN").put("sex", "UNKNOWN");
        context.putObject("contextCompleteness").put("status", "COMPLETE").putArray("missingFields");
        context.put("contextId", adapter.canonicalContextId(context));
        return context;
    }

    private CodingTranscriptReferenceReader reference(Path root) throws Exception {
        var properties = new VariantAnalysisProperties(); var settings = properties.getNormalization();
        settings.setGrch38Reference(root.resolve("local/resources/reference/grch38.p14.primary-chr.fa").toString());
        settings.setGrch38ReferenceSha256(REFERENCE_SHA); settings.setGrch38ProfileId("grch38-primary-chr");
        settings.setGrch38ContigAliasFile(root.resolve("varvis-server/config/grch38-canonical-to-chr-contigs.tsv").toString());
        var ledger = ExplicitContextSourceLedger.load(mapper, root.resolve("local/private/receipts/reference38-runtime.json"),
                "c7f794b9f7831c6130ea190729777473931cb4046993027aec52f16711a31a7b");
        return new CodingTranscriptReferenceReader(mapper, properties, root.resolve("local/resources"), ledger, "reference");
    }

    private JsonNode pinned(Path path, String pin) throws Exception {
        byte[] raw = Files.readAllBytes(path); assertEquals(pin, sha(raw), path.toString()); return mapper.readTree(raw);
    }
    private JsonNode retainedArtifact(Path artifacts, JsonNode execution, String name) throws Exception {
        for (JsonNode file : execution.path("artifacts"))
            if (Path.of(file.path("path").asText()).getFileName().toString().equals(name))
                return pinned(artifacts.resolve("vep-02").resolve(name), file.path("sha256").asText());
        throw new AssertionError("Pinned execution artifact missing: " + name);
    }
    private static JsonNode caseById(JsonNode cases, String id) {
        for (JsonNode value : cases) if (id.equals(value.path("caseId").asText())) return value;
        throw new AssertionError("Missing frozen case " + id);
    }
    private static String sha(byte[] raw) { return CodingTranscriptMaterialSupply.sha(raw); }
}
