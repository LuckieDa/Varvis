package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.nbscn.varvis.variantanalysis.config.*;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Separate Writer: real publication, normalization/material/PM1/PM2 core, CURRENT
 * Service/Controller and file H2. Clinical declarations, sequence, published-shaped
 * rules, population policy and source observations are SYNTHETIC engineering controls.
 * Other configured criteria retain their actual unavailable inputs; no new source approval.
 */
public final class MainPm1PeerBusinessFixture {
    private static final String PREFIX = Pm4Bp3ComplexPublicFixture.PREFIX;
    private static final Path LOCAL = Path.of("D:/LSD/Project/Code/local");
    private final Path root, output;
    private AcmgBusinessIntegrationTest base;
    private Pm4Bp3ComplexPublicFixture transport;
    private Ba1BusinessIntegrationTest ba1Transport;
    private Path ba1Queries;
    private final Map<String, TranscriptConsequence> annotations = new LinkedHashMap<>();
    private final AtomicInteger queries = new AtomicInteger();
    private ObjectNode manifest;
    private MainPm1PeerBusinessFixture(Path root, Path output) { this.root = root; this.output = output; }
    public static void main(String[] args) throws Exception {
        assertEquals(2, args.length); var fixture = new MainPm1PeerBusinessFixture(Path.of(args[0]), Path.of(args[1]));
        Files.createDirectories(fixture.root); Files.createDirectories(fixture.output);
        try { fixture.prepare(); fixture.run(); } finally { if (fixture.base != null) fixture.base.closeDatabase(); }
        fixture.manifest.put("databaseClosed", true).put("writerCompleted", true);
        fixture.write(fixture.output.resolve("reader-manifest.json"), fixture.manifest);
    }
    private void prepare() throws Exception {
        base = new AcmgBusinessIntegrationTest() {
            @Override VepTranscriptAnnotationEnrichmentService publicationAnnotations() {
                var service = mock(VepTranscriptAnnotationEnrichmentService.class);
                when(service.annotateCandidates(any(), any(), any(), any(), any(GenomeAssembly.class))).thenAnswer(call -> {
                    List<VariantInterpretation> candidates = call.getArgument(0); var values = new LinkedHashMap<String, List<TranscriptConsequence>>();
                    for (var candidate : candidates) values.put(candidate.getVariantId(), List.of(annotations.get(candidate.getExomiserCandidateFeatures().getGeneSymbol())));
                    return new VepTranscriptAnnotationEnrichmentService.VepAnnotationResult(values, VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary.notRequested());
                }); return service;
            }
        };
        base.temporary = root; base.setup();
        transport = new Pm4Bp3ComplexPublicFixture(base.mapper, root.resolve("transport"), root.resolve("synthetic-reference"));
        transport.applyNormalizationTo(base.properties); base.fixture.sources.add(transport.referenceLedger()); base.producer = transport.producer(base.fixture.sources);
        for (var scope : MainPm1PeerBusinessMaterials.SCOPES) {
            var dto = transport.annotation(scope.gene()); var model = transport.model(scope.gene());
            dto.put("hgvsCoding", scope.transcript() + ":c.8C>T").put("hgvsProtein", model.path("protein").asText() + ":p.Pro3Leu")
                    .put("consequence", "missense_variant").put("proteinPosition", "3").put("aminoAcids", "P/L")
                    .put("codons", "CCT/CTT").put("exon", "1/1").put("timestamp", Instant.parse("2026-11-04T00:00:00Z").toString());
            annotations.put(scope.gene(), base.mapper.treeToValue(dto, TranscriptConsequence.class));
        }
        Path knowledge = MainPm1PeerBusinessMaterials.writeKnowledge(base.mapper, root.resolve("synthetic-pm1-peer-knowledge.json"));
        for (String code : List.of("pm1", "pp2", "bp1")) transport.env().withProperty(PREFIX + "local-batch-" + code + "-enabled", "true");
        transport.env().withProperty(PREFIX + "pm1-published-knowledge-file", requireNonNull(knowledge.toString()))
                .withProperty(PREFIX + "pm1-published-knowledge-sha256", requireNonNull(sha(Files.readAllBytes(knowledge))))
                .withProperty(PREFIX + "pp2-published-knowledge-file", requireNonNull(LOCAL.resolve("runtime/bp7-evidence-workbench/materials/fourteen-v1/pp2-knowledge.json").toString()))
                .withProperty(PREFIX + "pp2-published-knowledge-sha256", "d633875c63d21bb736fba881931b00b124b5c119fd10d277f3482d7485dbfd30")
                .withProperty(PREFIX + "bp1-published-knowledge-file", requireNonNull(LOCAL.resolve("runtime/bp7-evidence-workbench/materials/fourteen-v1/bp1-knowledge.json").toString()))
                .withProperty(PREFIX + "bp1-published-knowledge-sha256", requireNonNull(sha(Files.readAllBytes(LOCAL.resolve("runtime/bp7-evidence-workbench/materials/fourteen-v1/bp1-knowledge.json")))));
        base.variantTsvOverride = tsv("1\t11\t107\tC\tT\tHBB\t3043\tAR\t0.8", "2\t13\t107\tC\tT\tGJB2\t2706\tAR\t0.7");
        prepareBa1Transport();
        base.publish(false, GenomeAssembly.GRCh38, vcf("chr11\t107\t.\tC\tT\t.\tPASS\t.\tGT\t0/1", "chr13\t107\t.\tC\tT\t.\tPASS\t.\tGT\t0/1"));
        install();
        manifest = base.mapper.createObjectNode().put("schemaVersion", "main-pm1-peer-business-reader-manifest-v1")
                .put("analysisId", base.analysisId).put("writerPid", ProcessHandle.current().pid())
                .put("sourceAndPolicyTier", "SYNTHETIC_TEST_ONLY").put("clinicalAdoption", false);
        manifest.putArray("runs");
    }
    private void install() {
        // The same complete, actual BA1 resource/catalog configuration is used by
        // every selection. Its identity is not invented only for the all-selected view.
        ba1Transport.wire();
        var population = AcmgBusinessService.formalPopulation(base.service, () -> Bs1Runtime.localExomes(base.mapper, new VariantAnalysisProperties(), false));
        var local = AcmgBusinessService.localBatchWithEffectIdentity(population,
                (effects, sourcePin) -> MainPm1PeerBusinessMaterials.withSyntheticPopulationTransport(base.mapper, transport.centralRuntime(effects, sourcePin), queries), root.resolve("effect-reviews"));
        base.service = AcmgBusinessService.unifiedLocal(population, local);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(base.service)).build();
    }
    private void prepareBa1Transport() throws Exception {
        ba1Transport = new Ba1BusinessIntegrationTest(); ba1Transport.base = base; ba1Transport.temporary = root;
        ba1Transport.am = new BusinessAlphaMissenseFixture();
        var catalog = ba1Transport.syntheticCatalog("SYNTHETIC_MAIN_PM1_COMPLETE_CONFIGURATION");
        var mappings = catalog.withArray("/geneMappings");
        for (var scope : MainPm1PeerBusinessMaterials.SCOPES) {
            var mapping = mappings.addObject().put("hgncId", scope.hgnc()); mapping.putArray("geneSymbols").add(scope.gene());
            mapping.putArray("ensemblGeneIds").add(annotations.get(scope.gene()).getGeneId());
        }
        byte[] mappingBytes = base.mapper.writeValueAsBytes(mappings);
        Files.write(root.resolve("synthetic-ba1-gene-mapping-original.json"), mappingBytes);
        catalog.putObject("geneMappingSource").put("status", "COMPLETE").put("rawSha256", sha(mappingBytes))
                .put("url", "https://example.test/SYNTHETIC/main-pm1/gene-mapping").put("recordCount", mappings.size());
        byte[] catalogBytes = base.mapper.writeValueAsBytes(catalog);
        Files.write(root.resolve("synthetic-ba1-catalog-original.json"), catalogBytes);
        String info = "AC_joint=800;AN_joint=10000;AF_joint=0.08;nhomalt_joint=25;grpmax_joint=nfe;AC_grpmax_joint=720;AN_grpmax_joint=9000;AF_grpmax_joint=0.08;nhomalt_grpmax_joint=22"
                + ";faf95_joint=0.054;fafmax_faf95_max_joint=0.055;fafmax_faf95_max_gen_anc_joint=nfe;AC_joint_nfe=720;AN_joint_nfe=9000;AF_joint_nfe=0.08;nhomalt_joint_nfe=22;faf95_joint_nfe=0.055"
                + ";exomes_filters=.;genomes_filters=.;stat_union_p_value=0.5;stat_union_test_name=chi_squared;stat_union_gen_ancs=nfe";
        ba1Queries = root.resolve("synthetic-ba1-query-commands.txt"); Path script = root.resolve("synthetic-ba1-tabix.cmd");
        Files.writeString(script, "@echo off\r\necho query>> \"%~dp0synthetic-ba1-query-commands.txt\"\r\necho chr11\t107\t.\tC\tT\t.\tPASS\t" + info
                + "\r\necho chr13\t107\t.\tC\tT\t.\tPASS\t" + info + "\r\nexit /b 0\r\n");
        ba1Transport.ba1 = new Ba1AlleleRuntime(base.mapper, ba1Transport.populationSettings(script.toString()), new Ba1SpecificationCatalog(base.mapper, catalog));
        ba1Transport.ba1Binding = ba1Transport.ba1.identity();
        var originals = base.mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("catalogSha256", sha(catalogBytes))
                .put("geneMappingRawSha256", sha(mappingBytes)).put("rawRecordTransportSha256", sha(Files.readAllBytes(script)));
        originals.set("actualRuntimeBinding", requireNonNull(ba1Transport.ba1Binding).deepCopy());
        write(output.resolve("ba1-synthetic-input-contract.json"), originals);
        Files.write(output.resolve("synthetic-ba1-catalog-original.json"), catalogBytes);
        Files.write(output.resolve("synthetic-ba1-gene-mapping-original.json"), mappingBytes);
        Files.copy(script, output.resolve("synthetic-ba1-tabix.cmd"));
    }
    private ObjectNode request(String mode) throws Exception {
        JsonNode options = base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId).param("topN", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        var request = base.request.deepCopy().put("declarationVersion", options.at("/executionProfile/requiredDeclaration").asText());
        var criteria = request.putArray("selectedCriteria");
        if ("ALL_CONFIGURED".equals(mode)) criteria.addAll((com.fasterxml.jackson.databind.node.ArrayNode) options.at("/executionProfile/enabledCriteria"));
        else { criteria.add("ACMG_AMP_2015:PM1"); if ("RELATED".equals(mode)) criteria.add("ACMG_AMP_2015:PM2"); }
        var hypotheses = request.putArray("hypotheses"); var recommendations = request.putArray("recommendations");
        for (int index = 0; index < MainPm1PeerBusinessMaterials.SCOPES.size(); index++) {
            var scope = MainPm1PeerBusinessMaterials.SCOPES.get(index); var rec = options.at("/candidates/" + index + "/recommendation");
            JsonNode relation = null, annotation = null;
            for (var row : rec.path("relationships")) if (scope.hgnc().equals(row.path("hgncId").asText()) && scope.disease().equals(row.path("mondoId").asText())) relation = row;
            for (var row : rec.path("annotations")) if (scope.transcript().equals(row.path("versionedTranscriptId").asText())) annotation = row;
            assertNotNull(relation, rec::toPrettyString); assertNotNull(annotation, rec::toPrettyString);
            var item = hypotheses.addObject().put("affectedStatus", "AFFECTED"); var hypothesis = item.putObject("hypothesis");
            hypothesis.put("candidateId", rec.path("candidateId").asText()).put("hypothesisId", rec.path("hypothesisId").asText())
                    .put("hgncId", scope.hgnc()).put("mondoId", scope.disease()).set("moi", relation.path("moi"));
            hypothesis.set("provenance", base.declaredSource("private/synthetic-pm1-peer/hypothesis"));
            int dot = scope.transcript().lastIndexOf('.');
            var tx = item.putObject("transcript").put("accession", scope.transcript().substring(0, dot)).put("version", Integer.parseInt(scope.transcript().substring(dot + 1)))
                    .put("selectionPolicy", rec.path("policyId").asText() + "@" + rec.path("policyVersion").asText()).put("selectionReason", "Explicit synthetic PM1 peer control");
            tx.set("annotationSource", annotation.path("annotationSource")); tx.set("annotationSourceVersion", annotation.path("annotationSourceVersion"));
            tx.putObject("source").put("source", "SYNTHETIC_PM1_PEER_SELECTION").put("version", "1").put("recordLocator", "recommendations/" + rec.path("recommendationId").asText() + "/selection");
            var ref = recommendations.addObject();
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) ref.set(field, rec.path(field));
            ref.set("stableRelationshipKey", relation.path("stableRelationshipKey")); ref.set("annotationId", annotation.path("annotationId"));
        }
        return request;
    }
    private void run() throws Exception {
        var ranking = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100));
        for (String mode : List.of("SINGLE", "RELATED", "ALL_CONFIGURED")) {
            var request = request(mode); int before = queries.get(); var saved = base.postRequest(request);
            assertEquals("4.1.0", saved.at("/executionProfile/populationExecutionProfile/profileVersion").asText());
            assertTrue(saved.at("/executionProfile/ba1RuntimeBinding").isObject());
            assertEquals(saved.at("/executionProfile/ba1RuntimeBinding"), saved.at("/executionProfile/populationExecutionProfile/ba1RuntimeBinding"));
            assertEquals(2, queries.get() - before, "Two real PM2 computations, not a mock criterion outcome");
            String runId = saved.path("runId").asText(); var run = base.store.run(base.analysisId, runId).orElseThrow();
            var batch = base.mapper.readTree(run.privateJson()).path("localBatch");
            assertEquals(2, batch.at("/modules/pm1/evaluatorCalls").asInt()); assertEquals(2, batch.at("/modules/pm2/evaluatorCalls").asInt());
            assertEquals(2, batch.at("/modules/pm2/results").size());
            for (int index = 0; index < 2; index++) {
                var raw = batch.at("/modules/pm1/results/" + index); String expected = index == 0 ? "MET" : "NOT_MET";
                assertEquals(expected, raw.path("status").asText(), raw::toPrettyString);
                assertEquals(expected, raw.at("/regionAssessment/crossCriterionConditionCheck/checks/0/available/status").asText());
                var facts = criterion(saved.at("/candidates/" + index + "/attempts/0/localBatch/criteria"), "ACMG_AMP_2015:PM1").at("/details/pm1Facts");
                assertFalse(facts.has("publicProjectionIssue"), facts::toPrettyString);
                assertEquals(expected, facts.at("/currentEvaluationBinding/status").asText());
                if ("SINGLE".equals(mode)) assertEquals(1, saved.at("/candidates/" + index + "/attempts/0/localBatch/criteria").size());
            }
            assertEquals(ranking, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100)));
            int frozen = queries.get(); long frozenBa1 = Files.exists(ba1Queries) ? Files.readAllLines(ba1Queries).size() : 0;
            assertEquals(saved, base.postRequest(request));
            assertEquals(saved, base.mapper.readTree(base.http.perform(get("/variant-analyses/{a}/acmg/runs/{r}", base.analysisId, runId))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()));
            assertEquals(frozen, queries.get());
            assertEquals(frozenBa1, Files.exists(ba1Queries) ? Files.readAllLines(ba1Queries).size() : 0);
            Path folder = Files.createDirectory(output.resolve(mode));
            Files.write(folder.resolve("expected-public.json"), run.responseJson().getBytes(StandardCharsets.UTF_8));
            Files.write(folder.resolve("expected-private.json"), run.privateJson().getBytes(StandardCharsets.UTF_8));
            Files.write(folder.resolve("expected-request.json"), run.requestJson().getBytes(StandardCharsets.UTF_8));
            manifest.withArray("runs").addObject().put("name", mode).put("runId", runId).put("enabledCriteriaCount", saved.path("enabledCriteria").size());
        }
        var source = base.store.source(base.analysisId).orElseThrow(); Files.write(output.resolve("expected-source.json"), source.json().getBytes(StandardCharsets.UTF_8));
        manifest.put("sourceSha256", source.sha256()).put("queries", queries.get()).put("pm2Queries", queries.get())
                .put("ba1QueryCommands", Files.exists(ba1Queries) ? Files.readAllLines(ba1Queries).size() : 0);
        if (Files.exists(ba1Queries)) Files.copy(ba1Queries, output.resolve("synthetic-ba1-query-commands.txt"));
    }
    private JsonNode criterion(JsonNode criteria, String id) { for (var value : criteria) if (id.equals(value.path("criterionId").asText())) return value; throw new AssertionError(id); }
    private void write(Path path, JsonNode value) throws Exception { Files.write(path, base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value)); }
    private static String sha(byte[] bytes) throws Exception { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
