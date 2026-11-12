package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real Java/Python intake; synthetic sources and transport, no external calls. */
class DeNovoBundleIntakeTest {
    @TempDir Path temporary;
    private DeNovoBatchFixture fixture;
    private Path python, scripts, index;

    @BeforeEach void setup() throws Exception {
        python = Path.of(System.getProperty("denovo.test.python", "missing-python"));
        scripts = Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(python), "Explicit existing Python required");
        fixture = new DeNovoBatchFixture(temporary.resolve("materials"));
        fixture.addSyntheticLiterature(); fixture.manifest.putArray("reports");
        index = fixture.root.resolve("model-artifacts.json");
    }

    private void prepare(String mode) throws Exception {
        Path path = fixture.root.resolve("synthetic-literature.xml");
        String source = Files.readString(path).replace("<body>", "<body><sec><title>Methods</title><p>Patients were recruited for this study.</p></sec>")
                .replace("The EXAMPLE variant", "Case A was studied. The EXAMPLE variant");
        if (mode.startsWith("pm6")) source = source.replace("SNP analysis confirmed both maternity and paternity.", "No parentage testing was performed.");
        if (mode.equals("pm6-scoped-unreported")) source = source.replace("No parentage testing was performed.", "");
        if (mode.equals("pm6-unnamed-assay")) source = source
                .replace("Sanger sequencing confirmed the heterozygous variant in the proband and its absence in both parents.",
                        "Genetic testing identified the heterozygous EXAMPLE variant c.123A&gt;G, p.T41A, in the proband. Both parents tested negative for the variant.")
                .replaceAll("(?s)<fig>.*?</fig>", "");
        if (mode.equals("pm6-narrative-family")) source = source
                .replace("<tr><td>Family history</td><td>None</td></tr>", "")
                .replace("Case A was studied.", "Case A was studied. There was no family history of similar symptoms.");
        if (mode.equals("pm6-unaffected-parents")) source = source
                .replace("<tr><td>Family history</td><td>None</td></tr>", "")
                .replace("Case A was studied.", "Case A was studied. Both parents were clinically unaffected.");
        if (mode.startsWith("pm6-unassigned-history")) source = source.replace("</sec></body>",
                "<p>Three mothers reported recurrent miscarriages.</p></sec></body>");
        if (mode.equals("pm6-unassigned-history-missing")) source = source
                .replace("<tr><td>Family history</td><td>None</td></tr>", "");
        if (mode.equals("pm6-special-family")) source = source.replace("</sec></body>",
                "<p>Three mothers had mosaicism.</p></sec></body>");
        if (mode.equals("conflict")) source = source.replace("</p></sec></sec>", "The variant was inherited from the mother.</p></sec></sec>");
        if (mode.startsWith("cumulative")) source = source.replace("</p></sec></sec>",
                "</p><p>Case A was studied. The EXAMPLE variant c.123A&gt;G, p.T41A, was observed again.</p></sec></sec>");
        Files.writeString(path, source); byte[] bytes = Files.readAllBytes(path);
        for (var entry : fixture.manifest.path("sources")) if (entry.path("path").asText().equals(path.getFileName().toString()))
            ((ObjectNode)entry).put("sha256", DeNovoBatchFixture.sha(bytes));
        fixture.writeManifest();
        var process = new SystemExternalProcessRunner().run(List.of(python.toString(), "-E", "-s", "-B", "-c",
                mode.startsWith("cumulative")
                    ? "from test_literature_continuation import write_cumulative_fixture; import sys; write_cumulative_fixture(sys.argv[1], " + (mode.endsWith("conflict") ? "True" : "False") + ")"
                    : "from test_bundle_qualification import write_synthetic_fixture; import sys; write_synthetic_fixture(sys.argv[1])",
                fixture.root.toString()), scripts, Duration.ofSeconds(30));
        assertTrue(process.isSuccess(), process.getOutput());
    }

    private DeNovoLiteratureReader reader() throws Exception {
        return new DeNovoLiteratureReader(fixture.mapper, python, scripts, temporary.resolve("replay-cache"), index,
                DeNovoBatchFixture.sha(Files.readAllBytes(index)));
    }

    private DeNovoLiteratureReader coreReader() throws Exception {
        Path config = fixture.root.resolve("local/core-config.json");
        return reader().withCoreContext(fixture.root, config, DeNovoBatchFixture.sha(Files.readAllBytes(config)));
    }

    private void prepareCore(String mode) throws Exception {
        prepare(mode);
        var process = new SystemExternalProcessRunner().run(List.of(python.toString(), "-E", "-s", "-B", "-c",
                "from test_core_evaluation_intake import write_java_fixture; import sys; write_java_fixture(sys.argv[1])",
                fixture.root.toString()), scripts, Duration.ofSeconds(30));
        assertTrue(process.isSuccess(), process.getOutput());
    }

    @ParameterizedTest @ValueSource(strings={"ps2", "pm6", "pm6-unnamed-assay", "pm6-scoped-unreported"})
    void corePackageReachesOriginalScorerAndFreshReaderReuses(String mode) throws Exception {
        prepareCore(mode);
        var first = coreReader(); var module = load(first);
        var result = module.evaluateBound(fixture.context(), fixture.mapper.createObjectNode());
        assertEquals("MET", result.at("/qualifiedResult/result/status").asText(), result.toPrettyString());
        assertEquals(mode.equals("ps2") ? "PS2" : "PM6", result.at("/qualifiedResult/result/code").asText());
        assertEquals(1, result.at("/qualifiedResult/result/events").size());
        assertEquals(1, first.executions());
        var reopened = coreReader(); var again = load(reopened);
        assertEquals(0, reopened.executions());
        assertEquals(result, again.evaluateBound(fixture.context(), fixture.mapper.createObjectNode()));
        assertEquals(module.originals(), again.originals());
    }

    @ParameterizedTest @ValueSource(strings={"pm6-narrative-family", "pm6-unassigned-history",
            "pm6-unaffected-parents", "pm6-unassigned-history-missing", "pm6-special-family"})
    void familySourceFormatsAndUnassignedHistoryReachOriginalGate(String mode) throws Exception {
        prepareCore(mode); var reader = coreReader(); var module = load(reader);
        var result = module.evaluateBound(fixture.context(), fixture.mapper.createObjectNode());
        if (List.of("pm6-narrative-family", "pm6-unassigned-history", "pm6-unaffected-parents").contains(mode)) {
            assertEquals("MET", result.at("/qualifiedResult/result/status").asText(), result.toPrettyString());
            assertEquals("PM6", result.at("/qualifiedResult/result/code").asText());
            assertEquals("SUPPORTING", result.at("/qualifiedResult/result/strength").asText());
            if (mode.equals("pm6-unassigned-history"))
                assertTrue(result.toString().contains("UNASSIGNED_NONSPECIFIC_HISTORY_NOT_CASE_CONFLICT"));
        } else {
            assertEquals("NOT_EVALUATED", result.at("/qualifiedResult/result/status").asText(), result.toPrettyString());
            String pin = module.identity().path("literatureSourceResultSha256").asText();
            String facts = new String(java.util.Base64.getDecoder().decode(module.originals().path(pin).asText()),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(facts.contains(mode.equals("pm6-special-family")
                    ? "DENOVO_FAMILY_HISTORY_CASE_ASSIGNMENT_UNRESOLVED" : "DENOVO_FAMILY_HISTORY_NOT_ESTABLISHED"));
        }
        var reopened = coreReader(); var repeated = load(reopened);
        assertEquals(0, reopened.executions());
        assertEquals(result, repeated.evaluateBound(fixture.context(), fixture.mapper.createObjectNode()));
    }

    @Test void coreConfigAndReferenceChangesCannotUseSuccessfulLegacyOrCache() throws Exception {
        prepareCore("ps2"); var first = coreReader(); load(first);
        Path config = fixture.root.resolve("local/core-config.json");
        byte[] saved = Files.readAllBytes(config); Files.writeString(config, "{}");
        assertEquals("DENOVO_LITERATURE_READER_UNAVAILABLE", load(first).identity().path("literatureQualification").asText());
        Files.write(config, saved);
        Path fasta = fixture.root.resolve("local/reference.fa");
        Files.setLastModifiedTime(fasta, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        assertThrows(java.io.IOException.class, this::coreReader);
        assertEquals(1, first.executions());
    }

    @ParameterizedTest @ValueSource(strings={"cumulative", "cumulative-conflict", "conflict"})
    void coreCumulativeTargetsNeverDuplicateOrHideConflicts(String mode) throws Exception {
        prepareCore(mode); var result = load(coreReader()).evaluateBound(fixture.context(), fixture.mapper.createObjectNode());
        if (mode.equals("cumulative")) {
            assertEquals("MET", result.at("/qualifiedResult/result/status").asText(), result.toPrettyString());
            assertEquals(1, result.at("/qualifiedResult/result/events").size());
            assertEquals(4, result.at("/qualifiedResult/result/effectiveQuarterPoints").asInt());
        } else assertEquals("NOT_EVALUATED", result.at("/qualifiedResult/result/status").asText(), result.toPrettyString());
    }

    private DeNovoSelectedBatch load(DeNovoLiteratureReader reader) throws Exception {
        return DeNovoSelectedBatch.load(fixture.mapper, fixture.root.resolve("manifest.json"),
                DeNovoBatchFixture.sha(Files.readAllBytes(fixture.root.resolve("manifest.json"))),
                DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION, reader);
    }

    @Test void coreReadinessCannotOverrideExactBackgroundPolicyOrActualTargetResults() throws Exception {
        prepareCore("ps2"); var module = load(coreReader());
        String resultPin = module.identity().path("literatureSourceResultSha256").asText();
        var replay = fixture.mapper.readTree(java.util.Base64.getDecoder().decode(module.originals().path(resultPin).asText()));
        var core = (ObjectNode) replay.at("/documents/0/facts/cases/0/coreEvaluation");
        var allele = fixture.context().at("/variant/evidenceQueryAllele");
        var group = new DeNovoEvidenceCalculator.Group("GRCh38:11:2:T:A", "HGNC:1748", "MONDO:0007648", "AD");
        byte[] bytes = Files.readAllBytes(fixture.root.resolve("phenotype.json"));
        var policy = DeNovoApplicabilityPolicy.load(bytes, DeNovoBatchFixture.sha(bytes),
                java.util.Map.of("s", Files.readAllBytes(fixture.root.resolve("source.txt"))), DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION);
        assertTrue(DeNovoCoreFacts.read(core, allele, group, policy).issues().isEmpty());
        for (String path : List.of("/context/diseaseId", "/context/hgncId", "/context/inheritance", "/context/policyId")) {
            var wrong = core.deepCopy(); ((ObjectNode) wrong.path("values")).put(path, "wrong");
            assertFalse(DeNovoCoreFacts.read(wrong, allele, group, policy).issues().isEmpty(), path);
        }
        var wrongPin = core.deepCopy().put("policySha256", "0".repeat(64));
        assertTrue(DeNovoCoreFacts.read(wrongPin, allele, group, policy).issues().contains("DENOVO_CORE_POLICY_MISMATCH"));
        var wrongAllele = ((ObjectNode) allele).deepCopy().put("position", 3);
        assertTrue(DeNovoCoreFacts.read(core, wrongAllele, group, policy).issues().contains("DENOVO_CORE_EXACT_ALLELE_MISMATCH"));
        var negative = core.deepCopy(); ((ObjectNode) negative.path("values")).put("/groups/proband/targetResult", "ABSENT");
        assertEquals(DeNovoEvidenceCalculator.TargetTest.ABSENT_QUALIFIED, DeNovoCoreFacts.read(negative, allele, group, policy).facts().proband());
        ((ObjectNode) negative.path("values")).put("/groups/mother/targetResult", "PRESENT");
        assertEquals(DeNovoEvidenceCalculator.TargetTest.PRESENT, DeNovoCoreFacts.read(negative, allele, group, policy).facts().mother());
    }

    @Test void anotherSelectedAlleleCannotReuseReadyCoreFacts() throws Exception {
        prepareCore("ps2"); var module = load(coreReader()); var context = fixture.context();
        for (String key : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele"))
            ((ObjectNode) context.path("variant").path(key)).put("position", 3);
        var adapter = new RuntimeEvidenceV2Adapter(fixture.mapper);
        ((ObjectNode) context.path("variant")).put("alleleId", adapter.canonicalAlleleId(context.at("/variant/evidenceQueryAllele"), context.at("/variant/normalization")));
        context.put("contextId", adapter.canonicalVersionedContextId(context));
        var result = module.evaluateBound(context, fixture.mapper.createObjectNode());
        assertNotEquals("MET", result.at("/qualifiedResult/result/status").asText());
        assertTrue(result.toString().contains("DIFFERENT_ALLELE"), result.toPrettyString());
    }

    @Test void verifiedCoreSupersedesOnlyUnadoptedProposalIssuesNotRealConflicts() throws Exception {
        prepareCore("ps2");var module=load(coreReader());
        String pin=module.identity().path("literatureSourceResultSha256").asText();
        var replay=(ObjectNode)fixture.mapper.readTree(java.util.Base64.getDecoder().decode(module.originals().path(pin).asText()));
        var row=(ObjectNode)replay.at("/documents/0/facts/cases/0");
        row.putArray("issues").add("LITERATURE_ALLELE_ALIAS_NOT_FOUND").add("LITERATURE_FIELD_CONFLICT:BUNDLE_SUBJECT_NOT_VERIFIED")
                .add("LITERATURE_PARENTAL_TARGET_TESTS_NOT_RESOLVED");
        var group=new DeNovoEvidenceCalculator.Group("GRCh38:11:2:T:A","HGNC:1748","MONDO:0007648","AD");
        byte[] raw=Files.readAllBytes(fixture.root.resolve("phenotype.json"));
        var policy=DeNovoApplicabilityPolicy.load(raw,DeNovoBatchFixture.sha(raw),
            java.util.Map.of("s",Files.readAllBytes(fixture.root.resolve("source.txt"))),DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION);
        var qualified=new DeNovoLiteratureEvidence(new DeNovoLiteratureReader.Read(replay,pin,null,null,false))
            .qualify(fixture.context(),group,policy,List.of(),List.of());
        assertEquals(1,qualified.observations().size());
        assertTrue(qualified.observations().get(0).qualificationIssues().isEmpty());
        ((com.fasterxml.jackson.databind.node.ArrayNode)row.path("issues")).add("LITERATURE_SOURCE_CONFLICT_TEXT");
        var conflict=new DeNovoLiteratureEvidence(new DeNovoLiteratureReader.Read(replay,pin,null,null,false))
            .qualify(fixture.context(),group,policy,List.of(),List.of());
        assertTrue(conflict.observations().get(0).qualificationIssues().contains("LITERATURE_SOURCE_CONFLICT_TEXT"));
        ((ObjectNode)row.path("coreEvaluation")).put("policySha256","0".repeat(64));
        var wrongPolicy=new DeNovoLiteratureEvidence(new DeNovoLiteratureReader.Read(replay,pin,null,null,false))
            .qualify(fixture.context(),group,policy,List.of(),List.of());
        assertTrue(wrongPolicy.observations().get(0).qualificationIssues().contains("DENOVO_CORE_POLICY_MISMATCH"));
        assertTrue(wrongPolicy.observations().get(0).qualificationIssues().contains("LITERATURE_PARENTAL_TARGET_TESTS_NOT_RESOLVED"));
    }

    @ParameterizedTest @ValueSource(strings={"ps2", "pm6"})
    void boundedCandidateReachesExistingScoringAndReopenReuses(String mode) throws Exception {
        prepare(mode); var first = reader(); var module = load(first);
        var result = module.evaluateBound(fixture.context(), fixture.mapper.createObjectNode());
        assertEquals("MET", result.at("/qualifiedResult/result/status").asText(), result.toPrettyString());
        assertEquals(mode.equals("pm6") ? "PM6" : "PS2", result.at("/qualifiedResult/result/code").asText());
        assertEquals("SYNTHETIC_MODEL_REPLAY_ONLY", result.at("/publicSummary/modelValidation").asText());
        assertFalse(module.originals().isEmpty());
        assertEquals(1, first.executions());
        var second = reader(); var reopened = load(second);
        assertEquals(0, second.executions());
        assertEquals(module.originals(), reopened.originals());
        assertEquals(result, reopened.evaluateBound(fixture.context(), fixture.mapper.createObjectNode()));
    }

    @Test void uncitedOriginalContradictionCannotBeHidden() throws Exception {
        prepare("conflict"); var result = load(reader()).evaluateBound(fixture.context(), fixture.mapper.createObjectNode());
        assertEquals("NOT_EVALUATED", result.at("/qualifiedResult/result/status").asText(), result.toPrettyString());
        assertTrue(result.toString().contains("CONFLICT_TEXT"));
    }

    @Test void modifiedIntakeCannotUsePriorCache() throws Exception {
        prepare("ps2"); load(reader()); Files.writeString(fixture.root.resolve("bundle-intake.json"), "{}");
        assertEquals("DENOVO_MODEL_ARTIFACT_PIN_MISMATCH", load(reader()).identity().path("literatureQualification").asText());
    }

    @Test void cumulativeSameCaseDoesNotIncreasePointsAndLateConflictBlocks() throws Exception {
        prepare("cumulative");
        var summary=fixture.mapper.readTree(fixture.root.resolve("continuation-summary.json").toFile());
        assertEquals(1,summary.at("/cumulativeCases/uniqueCases").asInt());
        assertEquals(2,summary.at("/cumulativeCases/proposals").asInt());
        var first=reader();var module=load(first);
        var result=module.evaluateBound(fixture.context(),fixture.mapper.createObjectNode());
        assertEquals("MET",result.at("/qualifiedResult/result/status").asText(),result.toPrettyString());
        assertEquals(4,result.at("/qualifiedResult/result/effectiveQuarterPoints").asInt(),result.toPrettyString());
        assertEquals(1,result.at("/qualifiedResult/result/events").size());
        var again=reader();assertEquals(result,load(again).evaluateBound(fixture.context(),fixture.mapper.createObjectNode()));
        assertEquals(0,again.executions());
    }

    @Test void cumulativeLaterUnclearOriginCannotKeepEarlierFavourableScore() throws Exception {
        prepare("cumulative-conflict");
        var result=load(reader()).evaluateBound(fixture.context(),fixture.mapper.createObjectNode());
        assertEquals("NOT_EVALUATED",result.at("/qualifiedResult/result/status").asText(),result.toPrettyString());
        assertTrue(result.toString().contains("ORIGIN_NOT_PRIMARY"),result.toPrettyString());
    }

    @ParameterizedTest @ValueSource(strings={"ps2","pm6"})
    void newPaperFromCandidateRepositoryUsesExistingAllelesAndScorerWithoutManifestPaper(String mode) throws Exception {
        prepare(mode);
        // Test fixture publication only; production accept rejects SYNTHETIC_TEST.
        byte[] intake=Files.readAllBytes(fixture.root.resolve("bundle-intake.json"));
        String pin=DeNovoBatchFixture.sha(intake);var value=fixture.mapper.readTree(intake);
        Path repository=temporary.resolve("candidate-repository");Files.createDirectories(repository.resolve(pin));
        Files.write(repository.resolve(pin).resolve("intake.json"),intake);
        var registry=fixture.mapper.createObjectNode().put("schemaVersion",DeNovoCandidateRepository.VERSION);
        var entry=registry.putArray("entries").addObject().put("intakeSha256",pin);
        for(String key:List.of("pmcid","sourceSha256","bundleSha256")) entry.set(key,value.path(key));
        fixture.mapper.writeValue(repository.resolve("index.json").toFile(),registry);
        fixture.manifest.set("citationRecords",fixture.manifest.path("literature").get(0).path("alleles").deepCopy());
        fixture.manifest.remove("literature");fixture.writeManifest();
        var r=new DeNovoLiteratureReader(fixture.mapper,python,scripts,temporary.resolve("candidate-reader-cache")).withCandidates(repository);
        var module=load(r);var result=module.evaluateBound(fixture.context(),fixture.mapper.createObjectNode());
        assertEquals("MET",result.at("/qualifiedResult/result/status").asText(),result.toPrettyString());
        assertEquals(mode.equals("ps2")?"PS2":"PM6",result.at("/qualifiedResult/result/code").asText());
        assertTrue(module.originals().has(value.path("sourceSha256").asText()));
        assertTrue(module.identity().path("modelArtifactReplay").has("candidateSnapshotSha256"));
        var reopened=new DeNovoLiteratureReader(fixture.mapper,python,scripts,temporary.resolve("candidate-reader-cache")).withCandidates(repository);
        assertEquals(result,load(reopened).evaluateBound(fixture.context(),fixture.mapper.createObjectNode()));
        assertEquals(0,reopened.executions());
    }

    @Test void wrongAlleleHasSpecificReasonWithoutObservation() throws Exception {
        prepare("ps2");
        Path path = fixture.root.resolve("synthetic-literature-clinvar.xml");
        String original = Files.readString(path);
        var match = java.util.regex.Pattern.compile("positionVCF=\"([0-9]+)\"").matcher(original);
        String changed = match.replaceAll(m -> "positionVCF=\"" + (Long.parseLong(m.group(1)) + 1) + "\"");
        Files.writeString(path, changed);
        for (var s : fixture.manifest.path("sources")) if (path.getFileName().toString().equals(s.path("path").asText()))
            ((ObjectNode)s).put("sha256", DeNovoBatchFixture.sha(Files.readAllBytes(path)));
        fixture.writeManifest();
        var result = load(reader()).evaluateBound(fixture.context(), fixture.mapper.createObjectNode());
        assertEquals("NOT_EVALUATED", result.at("/qualifiedResult/result/status").asText());
        assertTrue(result.toString().contains("DIFFERENT_ALLELE"), result.toPrettyString());
    }
}
