package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual offline Python reader, source pins and common Java calculator. All clinical fixtures are SYNTHETIC. */
class DeNovoLiteratureIntegrationTest {
    @TempDir Path temporary;
    private DeNovoBatchFixture f;
    private Path python, scripts, cache;
    private byte[] paper, vcv;

    @BeforeEach void setup() throws Exception {
        python = Path.of(System.getProperty("denovo.test.python", "missing-denovo-test-python"));
        scripts = Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo"));
        Assumptions.assumeTrue(Files.isRegularFile(python), "Explicit existing Python required for process integration");
        f = new DeNovoBatchFixture(temporary.resolve("materials")); cache = temporary.resolve("cache");
        paper = getClass().getResourceAsStream("/denovo-automatic/synthetic-literature.xml").readAllBytes();
        vcv = getClass().getResourceAsStream("/denovo-automatic/synthetic-literature-clinvar.xml").readAllBytes();
        var term = f.phenotype.putArray("literatureTerms").addObject().put("hpoId", "HP:0001250");
        term.putArray("aliases").add("Synthetic trait"); term.putArray("sourceIds").add("s");
    }
    private DeNovoLiteratureReader reader() { return new DeNovoLiteratureReader(f.mapper, python, scripts, cache); }
    private ObjectNode binding(String id, String name, byte[] raw) throws Exception {
        Files.write(f.root.resolve(name), raw);
        return f.mapper.createObjectNode().put("sourceId", id).put("path", name).put("sha256", DeNovoBatchFixture.sha(raw));
    }
    private void save() throws Exception {
        f.save(); var sources = (ArrayNode) f.manifest.path("sources");
        while (sources.size() > 1) sources.remove(1);
        sources.add(binding("paper", "paper.xml", paper)); sources.add(binding("vcv", "vcv.xml", vcv));
        var document = f.manifest.putArray("literature").addObject().put("sourceId", "paper").put("pmcid", "PMC999999997");
        document.putArray("alleles").addObject().put("sourceId", "vcv").put("vcv", "VCV999999997");
        f.writeManifest();
    }
    private DeNovoSelectedBatch load(DeNovoLiteratureReader reader) throws Exception {
        return DeNovoSelectedBatch.load(f.mapper, f.root.resolve("manifest.json"), DeNovoBatchFixture.sha(Files.readAllBytes(f.root.resolve("manifest.json"))),
                DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION, reader);
    }
    @ParameterizedTest @ValueSource(strings={"missing-father", "no-reports", "no-family-id"})
    void literatureCanContributeWithoutCurrentParents(String scenario) throws Exception {
        ((ArrayNode) f.report.path("tests")).remove(2); save();
        if (!scenario.equals("missing-father")) { f.manifest.putArray("reports"); f.writeManifest(); }
        var context = f.context();
        if (scenario.equals("no-family-id")) {
            ((ObjectNode) context.path("patientContext")).remove("familyContextId");
            context.put("contextId", new RuntimeEvidenceV2Adapter(f.mapper).canonicalVersionedContextId(context));
        }
        var r = reader(); var module = load(r); var out = module.evaluateBound(context, f.mapper.createObjectNode());
        assertEquals("PS2", out.at("/qualifiedResult/result/code").asText(), out.toPrettyString());
        assertEquals("MODERATE", out.at("/qualifiedResult/result/strength").asText());
        assertEquals(4, out.at("/qualifiedResult/result/effectiveQuarterPoints").asInt());
        assertEquals(1, module.evaluations()); assertEquals(1, r.executions());
        assertTrue(out.toString().contains("LITERATURE_CASE"));
        assertEquals("CURRENT_CASE_AND_LITERATURE", out.at("/publicSummary/sourceScope").asText());
    }
    @Test void explicitlyUntestedParentageCanProducePm6WithoutInventingConfirmation() throws Exception {
        paper = new String(paper, StandardCharsets.UTF_8).replace("SNP analysis confirmed both maternity and paternity.", "No parentage testing was performed.").getBytes(StandardCharsets.UTF_8);
        save(); f.manifest.putArray("reports"); f.writeManifest();
        var out = load(reader()).evaluateBound(f.context(), f.mapper.createObjectNode());
        assertEquals("PM6", out.at("/qualifiedResult/result/code").asText(), out.toPrettyString());
        assertEquals(2, out.at("/qualifiedResult/result/effectiveQuarterPoints").asInt());
        assertEquals("SUPPORTING", out.at("/qualifiedResult/result/strength").asText());
    }
    @ParameterizedTest @ValueSource(strings={"UNKNOWN", "UNAFFECTED"})
    void literatureObservationDoesNotRequireCurrentPatientToBeAffected(String status) throws Exception {
        save(); f.manifest.putArray("reports"); f.writeManifest();
        var context = f.context(); ((ObjectNode) context.path("patientContext")).put("affectedStatus", status);
        context.put("contextId", new RuntimeEvidenceV2Adapter(f.mapper).canonicalVersionedContextId(context)); var before = context.deepCopy();
        var out = load(reader()).evaluateBound(context, f.mapper.createObjectNode());
        assertEquals("PS2", out.at("/qualifiedResult/result/code").asText(), out.toPrettyString());
        assertEquals(4, out.at("/qualifiedResult/result/effectiveQuarterPoints").asInt());
        assertEquals(before, context);
        // Same source with unconfirmed parentage follows PM6, not fabricated PS2 confirmation.
        paper = new String(paper, StandardCharsets.UTF_8).replace("SNP analysis confirmed both maternity and paternity.", "No parentage testing was performed.").getBytes(StandardCharsets.UTF_8);
        save(); f.manifest.putArray("reports"); f.writeManifest();
        out = load(reader()).evaluateBound(context, f.mapper.createObjectNode());
        assertEquals("PM6", out.at("/qualifiedResult/result/code").asText(), out.toPrettyString());
        assertEquals(2, out.at("/qualifiedResult/result/effectiveQuarterPoints").asInt());
    }
    @ParameterizedTest @ValueSource(strings={"UNKNOWN", "UNAFFECTED"})
    void excludedCurrentCaseDoesNotScoreButIndependentLiteratureCan(String status) throws Exception {
        f.report.putArray("literatureRelationships").addObject().put("pmcid", "PMC999999997").put("caseLabel", "A")
                .put("relationship", "UNRELATED_CASE").put("basis", "SYNTHETIC independent case");
        save(); var context = f.context(); ((ObjectNode) context.path("patientContext")).put("affectedStatus", status);
        context.put("contextId", new RuntimeEvidenceV2Adapter(f.mapper).canonicalVersionedContextId(context));
        var out = load(reader()).evaluateBound(context, f.mapper.createObjectNode());
        assertEquals(4, out.at("/qualifiedResult/result/effectiveQuarterPoints").asInt(), out.toPrettyString());
        assertTrue(out.toString().contains("DENOVO_AFFECTED_STATUS_REQUIRED"));
        assertTrue(out.at("/qualifiedResult/result/hasExcludedObservations").asBoolean());
    }
    @Test void linkedUnaffectedCaseCannotBeOverriddenByLiterature() throws Exception {
        f.report.putArray("literatureRelationships").addObject().put("pmcid", "PMC999999997").put("caseLabel", "A")
                .put("relationship", "SAME_CASE").put("basis", "SYNTHETIC same case");
        save(); var context=f.context(); ((ObjectNode)context.path("patientContext")).put("affectedStatus","UNAFFECTED");
        context.put("contextId", new RuntimeEvidenceV2Adapter(f.mapper).canonicalVersionedContextId(context));
        var out=load(reader()).evaluateBound(context,f.mapper.createObjectNode());
        assertEquals("NOT_EVALUATED",out.at("/qualifiedResult/result/status").asText(),out.toPrettyString());
        assertTrue(out.toString().contains("DENOVO_LINKED_CURRENT_CASE_STATUS_CONFLICT"));
    }
    @Test void unknownCurrentPatientCannotAcquirePointsFromFailedOrMissingLiterature() throws Exception {
        save(); var context=f.context(); ((ObjectNode)context.path("patientContext")).put("affectedStatus","UNKNOWN");
        context.put("contextId", new RuntimeEvidenceV2Adapter(f.mapper).canonicalVersionedContextId(context));
        var out=load(null).evaluateBound(context,f.mapper.createObjectNode());
        assertEquals("NOT_EVALUATED",out.at("/qualifiedResult/result/status").asText(),out.toPrettyString());
        assertEquals(0,out.at("/qualifiedResult/result/effectiveQuarterPoints").asInt());
        assertTrue(out.toString().contains("DENOVO_LITERATURE_READER_NOT_CONFIGURED"));
        assertTrue(out.toString().contains("DENOVO_AFFECTED_STATUS_REQUIRED"));
    }
    @ParameterizedTest @ValueSource(strings={"UNRELATED_CASE", "SAME_CASE", "UNKNOWN", "CONFLICT"})
    void sourceRelationshipControlsMixingOrDeduplication(String relationship) throws Exception {
        f.report.putArray("literatureRelationships").addObject().put("pmcid", "PMC999999997").put("caseLabel", "A")
                .put("relationship", relationship).put("basis", "SYNTHETIC explicit source relation; not a real patient statement");
        save(); var out = load(reader()).evaluateBound(f.context(), f.mapper.createObjectNode());
        int expected = relationship.equals("UNRELATED_CASE") ? 8 : 4;
        assertEquals(expected, out.at("/qualifiedResult/result/effectiveQuarterPoints").asInt(), out.toPrettyString());
        if (relationship.equals("SAME_CASE")) assertTrue(out.toString().contains("DENOVO_SAME_OCCURRENCE"));
        if (Set.of("UNKNOWN", "CONFLICT").contains(relationship)) assertTrue(out.toString().contains("DENOVO_CURRENT_AND_LITERATURE_RELATION_UNRESOLVED"));
    }
    @Test void duplicatePaperDoesNotCreateAnotherEventAndDifferentCasesAreNotAssumedIndependent() throws Exception {
        save(); f.manifest.putArray("reports");
        ((ArrayNode) f.manifest.path("sources")).add(binding("paper-copy", "paper-copy.xml", paper));
        var entry = ((ObjectNode) f.manifest.at("/literature/0")).deepCopy().put("sourceId", "paper-copy");
        ((ArrayNode) f.manifest.path("literature")).add(entry); f.writeManifest();
        var out = load(reader()).evaluateBound(f.context(), f.mapper.createObjectNode());
        assertEquals(4, out.at("/qualifiedResult/result/effectiveQuarterPoints").asInt(), out.toPrettyString());
        assertTrue(out.toString().contains("DENOVO_SAME_OCCURRENCE"));
        byte[] other = new String(paper, StandardCharsets.UTF_8).replace("Case A", "Case B").getBytes(StandardCharsets.UTF_8);
        ((ArrayNode) f.manifest.path("sources")).set(3, binding("paper-copy", "paper-copy.xml", other)); f.writeManifest();
        out = load(reader()).evaluateBound(f.context(), f.mapper.createObjectNode());
        assertEquals("NOT_EVALUATED", out.at("/qualifiedResult/result/status").asText());
        assertTrue(out.toString().contains("BETWEEN_CASE_INDEPENDENCE_UNRESOLVED"));
    }

    @Test void reviewedIndependenceRequiresBothExactCaseBindingsAndNoFollowUpConflict() throws Exception {
        var context = f.context();
        var group = new DeNovoEvidenceCalculator.Group("synthetic-allele", "HGNC:1748", "MONDO:0007648", "AD");
        var policyBytes = f.mapper.writeValueAsBytes(f.phenotype);
        var policy = DeNovoApplicabilityPolicy.load(policyBytes, DeNovoBatchFixture.sha(policyBytes),
                Map.of("s", Files.readAllBytes(f.root.resolve("source.txt"))), DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION);
        var result = f.mapper.createObjectNode();
        var documents = result.putArray("documents");
        var first = reviewedCase(documents, context, "PMC999999997", "A", "a".repeat(64));
        var second = reviewedCase(documents, context, "PMC999999998", "B", "b".repeat(64));
        first.putArray("independentOf").addObject().put("pmcid", "PMC999999998").put("caseLabel", "B")
                .put("sourceSha256", "b".repeat(64)).putArray("citations").addObject().put("locator", "A original");
        var reverse = second.putArray("independentOf").addObject().put("pmcid", "PMC999999997").put("caseLabel", "A")
                .put("sourceSha256", "a".repeat(64));
        reverse.putArray("citations").addObject().put("locator", "B original");
        var calculator = new DeNovoEvidenceCalculator();
        for (String state : List.of("verified", "wrong-pin", "follow-up")) {
            if (state.equals("wrong-pin")) reverse.put("sourceSha256", "c".repeat(64));
            if (state.equals("follow-up")) {
                reverse.put("sourceSha256", "a".repeat(64));
                first.putArray("followUpReports").addObject().put("pmcid", "PMC999999998").put("caseLabel", "B")
                        .put("sourceSha256", "b".repeat(64));
            }
            var readerOutput = new DeNovoLiteratureReader.Read(result, "d".repeat(64), null, null, false);
            var qualified = new DeNovoLiteratureEvidence(readerOutput).qualify(context, group, policy, List.of(), List.of());
            var score = calculator.evaluate(group, policy.calculation(), qualified.observations());
            assertEquals(state.equals("verified") ? 4 : 0, score.effectiveQuarterPoints());
            assertEquals(2, score.events().size());
            if (state.equals("verified")) {
                assertEquals("PM6", score.code());
                assertTrue(score.events().stream().allMatch(event -> event.sourceRefs().stream()
                        .anyMatch(ref -> ref.endsWith("/reviewedCase/independentOf/0"))));
            } else assertEquals("NOT_EVALUATED", score.status());
        }
    }

    private static ObjectNode reviewedCase(ArrayNode documents, ObjectNode context, String pmcid, String label, String pin) {
        var facts = documents.addObject().put("status", "SOURCE_READ").putObject("facts").put("pmcid", pmcid).put("sourceSha256", pin);
        var row = facts.putArray("cases").addObject().put("caseLabel", label);
        row.putObject("alleleIdentity").put("hgncId", "HGNC:1748").set("allele", context.at("/variant/evidenceQueryAllele"));
        var reviewed = row.putObject("reviewedCase").put("schemaVersion", DeNovoLiteratureEvidence.REVIEWED_PAPER)
                .put("diseaseId", "MONDO:0007648").put("family", "COMPATIBLE");
        reviewed.putArray("phenotype").add("Synthetic trait");
        var parents = reviewed.putObject("parentage");
        parents.put("maternity", "ASSUMED_NOT_REPORTED").put("paternity", "ASSUMED_NOT_REPORTED");
        var members = reviewed.putObject("members");
        members.putObject("proband").put("target", "PRESENT").put("method", "SANGER");
        members.putObject("mother").put("target", "ABSENT").put("method", "SANGER");
        members.putObject("father").put("target", "ABSENT").put("method", "SANGER");
        return reviewed;
    }
    @ParameterizedTest @ValueSource(strings={"unsupported-family-text", "wrong-allele", "wrong-phenotype", "parentage-inconclusive"})
    void missingOrUnsupportedSourceSemanticsCannotBeScored(String scenario) throws Exception {
        String text = new String(paper, StandardCharsets.UTF_8);
        if (scenario.equals("unsupported-family-text")) text = text.replace("</p></sec>", "The father's reported result was contradicted.</p></sec>");
        if (scenario.equals("parentage-inconclusive")) text = text.replace("SNP analysis confirmed both maternity and paternity.", "Parentage was inconclusive.");
        if (scenario.equals("wrong-phenotype")) text = text.replace("Synthetic trait", "Unsupported feature");
        paper = text.getBytes(StandardCharsets.UTF_8);
        if (scenario.equals("wrong-allele")) vcv = new String(vcv, StandardCharsets.UTF_8).replace("positionVCF=\"2\"", "positionVCF=\"3\"").getBytes(StandardCharsets.UTF_8);
        save(); f.manifest.putArray("reports"); f.writeManifest();
        var out = load(reader()).evaluateBound(f.context(), f.mapper.createObjectNode());
        assertEquals("NOT_EVALUATED", out.at("/qualifiedResult/result/status").asText(), out.toPrettyString());
        assertEquals(0, out.at("/qualifiedResult/result/effectiveQuarterPoints").asInt());
    }
    @Test void sourceReaderCacheReopensWithoutProcessAndDetectsCorruption() throws Exception {
        save(); var first = reader(); var one = load(first);
        var second = reader(); var two = load(second);
        assertEquals(1, first.executions()); assertEquals(0, second.executions()); assertEquals(one.identity(), two.identity());
        assertEquals(one.originals(), two.originals());
        try (var directories = Files.list(cache)) { Files.writeString(directories.findFirst().orElseThrow().resolve("result.json"), "{}"); }
        var third = reader(); var corrupt = load(third);
        assertEquals(0, third.executions());
        assertEquals("DENOVO_LITERATURE_CACHE_PIN_MISMATCH", corrupt.identity().path("literatureQualification").asText());
        // Current report remains usable even when the optional literature path fails.
        assertEquals("MET", corrupt.evaluateBound(f.context(), f.mapper.createObjectNode()).at("/criteria/PS2/evaluationResult/status").asText());
    }
    @Test void noConfiguredReaderIsAnExplicitGapAndDoesNotEraseCurrentEvidence() throws Exception {
        save(); var out = load(null).evaluateBound(f.context(), f.mapper.createObjectNode());
        assertEquals("MET", out.at("/criteria/PS2/evaluationResult/status").asText());
        assertTrue(out.toString().contains("DENOVO_LITERATURE_READER_NOT_CONFIGURED"));
    }
    @Test void sameCaseCannotLoseKnownPublishedSourceConflictThroughLiterature() throws Exception {
        f.report.putArray("literatureRelationships").addObject().put("pmcid", "PMC999999997").put("caseLabel", "A")
                .put("relationship", "SAME_CASE").put("basis", "SYNTHETIC same occurrence");
        save(); var formal = f.mapper.createObjectNode(); formal.putObject("analysisVcfCall").put("targetAlleleCall", "HOM_REF");
        var out = load(reader()).evaluateBound(f.context(), formal);
        assertEquals("NOT_EVALUATED", out.at("/qualifiedResult/result/status").asText(), out.toPrettyString());
        assertTrue(out.toString().contains("DENOVO_LINKED_CURRENT_CASE_SOURCE_CONFLICT"));
    }
    @Test void cachedReaderResultMustStillReferToTheRequestedOriginal() throws Exception {
        save(); load(reader()); Path directory;
        try (var paths = Files.list(cache)) { directory = paths.findFirst().orElseThrow(); }
        var result = (ObjectNode) f.mapper.readTree(Files.readAllBytes(directory.resolve("result.json")));
        ((ObjectNode) result.at("/documents/0")).put("sourceId", "another-source");
        byte[] bytes = f.mapper.writeValueAsBytes(result); Files.write(directory.resolve("result.json"), bytes);
        var receipt = (ObjectNode) f.mapper.readTree(Files.readAllBytes(directory.resolve("receipt.json")));
        receipt.put("resultSha256", DeNovoBatchFixture.sha(bytes)); Files.write(directory.resolve("receipt.json"), f.mapper.writeValueAsBytes(receipt));
        assertEquals("DENOVO_LITERATURE_READER_RESULT_MISMATCH", load(reader()).identity().path("literatureQualification").asText());
    }
    @ParameterizedTest @ValueSource(strings={"no-table", "unrecognized-table"})
    void unsupportedPaperLayoutIsExplainedWithoutErasingCurrentEvidence(String scenario) throws Exception {
        String text = new String(paper, StandardCharsets.UTF_8);
        text = scenario.equals("no-table") ? text.replaceAll("(?s)<table-wrap>.*?</table-wrap>", "") : text.replace("<th>Case A</th>", "<th>Unrecognized layout</th>");
        paper = text.getBytes(StandardCharsets.UTF_8);
        save(); var out = load(reader()).evaluateBound(f.context(), f.mapper.createObjectNode());
        assertEquals("MET", out.at("/criteria/PS2/evaluationResult/status").asText());
        assertTrue(out.toString().contains(scenario.equals("no-table") ? "LITERATURE_NARRATIVE_EXTRACTION_NOT_SUPPORTED" : "LITERATURE_CASE_LAYOUT_UNRESOLVED"), out.toPrettyString());
    }
}
