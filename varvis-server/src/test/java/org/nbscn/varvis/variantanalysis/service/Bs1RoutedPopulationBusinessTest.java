package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.persistence.JdbcVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.vcf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actual CURRENT options/SelectedTopN/publication/reference/HTTP/H2 path.
 * The X:2 HBB disease/MOI topology, reference readiness and population script are explicitly SYNTHETIC.
 * No real HBB location, clinical interpretation, resource availability or population adoption is asserted.
 */
class Bs1RoutedPopulationBusinessTest {
    private static final String BA1 = "ACMG_AMP_2015:BA1", BS1 = "ACMG_AMP_2015:BS1";
    @TempDir Path temporary;
    private Ba1BusinessIntegrationTest fixture;
    private AcmgBusinessIntegrationTest base;
    private Bs1KnowledgeCatalog knowledge;
    private VariantAnalysisProperties exomes, joint;
    private AcmgBusinessWorkflows workflows;
    private PatientDiseaseContextBindingResolver initialResolver;
    private boolean multipleBackgrounds;

    @BeforeEach void setup() throws Exception {
        fixture = new Ba1BusinessIntegrationTest(); fixture.temporary = temporary; fixture.setup(); base = fixture.base;
        base.producer = tinyReference();
        base.expectedPublishedCount = 1;
        base.variantTsvOverride = tsv("1\tX\t2\tT\tA\tHBB\t3043\tXD\t0.8");
        base.publish(false, GenomeAssembly.GRCh38, vcf("chrX\t2\tSYNTHETIC_ROUTED_X\tT\tA\t.\tPASS\t.\tGT\t0/1"));
        initialResolver = base.fixture.resolver;
        installKnowledge(Bs1RoutedPopulationTestMaterials.record(true));
        joint = populationSettings("joint"); exomes = populationSettings("exomes");
        fixture.ba1 = new Ba1AlleleRuntime(base.mapper, joint, Ba1SpecificationCatalog.bundled(base.mapper));
        fixture.ba1Binding = fixture.ba1.identity();
        wire();
    }

    @AfterEach void close(org.junit.jupiter.api.TestInfo test) throws Exception {
        try { archivePublicSamples(test); } finally { if (fixture != null) fixture.close(); }
    }

    private void archivePublicSamples(org.junit.jupiter.api.TestInfo test) throws Exception {
        String output=System.getProperty("varvis.a10.routed.output");
        if (output==null || output.isBlank() || base==null) return;
        String name=org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(test.getDisplayName()).substring(0,10);
        Path folder=Path.of(output).resolve(name); Files.createDirectories(folder);
        var manifest=base.mapper.createObjectNode().put("scenario",test.getDisplayName()).put("dataClass","SYNTHETIC")
                .put("scope","ACTUAL_ISOLATED_BUSINESS_RESPONSE_NOT_USER_DOWNLOAD");
        var records=manifest.putArray("records");
        Path child=temporary.resolve("fresh-jvm-export.json");
        if(Files.exists(child)) {
            Files.copy(child,folder.resolve("full-get.json"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(temporary.resolve("expected-public.json"),folder.resolve("public.json"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(temporary.resolve("fresh-jvm-private.json"),folder.resolve("private.json"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            records.addObject().put("fullGetSha256",org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(Files.readString(child)))
                    .put("independentJvmVerified",true);
        } else {
            int ordinal=0;
            for(var run:base.store.runs(base.analysisId)) {
                var stored=base.store.run(base.analysisId,run.id()).orElseThrow();
                var get=base.service.read(base.analysisId,run.id());
                String prefix=Integer.toString(++ordinal)+"-";
                Files.writeString(folder.resolve(prefix+"public.json"),stored.responseJson());
                Files.writeString(folder.resolve(prefix+"private.json"),stored.privateJson());
                Files.write(folder.resolve(prefix+"full-get.json"),base.mapper.writeValueAsBytes(get));
                records.addObject().put("runId",run.id()).put("prefix",prefix).put("responseSha256",stored.responseSha256());
            }
        }
        Files.write(folder.resolve("manifest.json"),base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
    }

    private void installKnowledge(ObjectNode... records) throws Exception {
        byte[] bytes = base.mapper.writeValueAsBytes(Bs1RoutedPopulationTestMaterials.bundle(base.mapper, records));
        Path file = temporary.resolve("SYNTHETIC-routing-knowledge-" + Bs1KnowledgeCatalog.sha(bytes) + ".json"); Files.write(file, bytes);
        knowledge = Bs1KnowledgeCatalog.load(file, Bs1KnowledgeCatalog.sha(bytes));
        base.fixture.resolver = initialResolver.withBs1Knowledge(base.mapper, knowledge);
    }

    private void wire() throws Exception {
        fixture.wire();
        var population = AcmgBusinessService.formalPopulation(base.service,
                () -> Bs1Runtime.routed(base.mapper, true, exomes, joint, true, knowledge, null));
        Files.createDirectories(temporary.resolve("experiments"));
        var local = AcmgBusinessService.localBatch(population, () -> new LocalEvidenceBatchRuntime(base.mapper,
                base.mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("scope", "ROUTED_POPULATION_TEST"),
                null, null, null, null,
                new Bp7SelectedBatch(base.mapper, new SplicingExperimentChecks(base.mapper,
                        new ClassPathResource("acmg/public-splicing-experiments-v1.json"), temporary.resolve("experiments"),
                        (analysis, run) -> { throw new AssertionError("Catalog capture must not execute business"); }).captureCatalog()),
                null, null, null, null, null, null, false, null, null, multipleBackgrounds ? unselectedPp1() : null));
        base.service = AcmgBusinessService.unifiedLocal(population, local);
        workflows = new AcmgBusinessWorkflows(base.service, null);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(workflows)).build();
    }

    /** Enables the existing multi-background request contract; this unrelated module must remain unopened in BS1-only runs. */
    private Pp1SelectedBatch unselectedPp1() {
        return new Pp1SelectedBatch(base.mapper, new Pp1SelectedBatch.Source() {
            public ObjectNode identity() {
                return base.mapper.createObjectNode().put("scope", "SYNTHETIC_UNSELECTED_PP1_GUARD")
                        .put("version", "pp1-paper-job-v2").put("workflowConfigSha256", "a".repeat(64));
            }
            public ObjectNode capture(String analysis, ObjectNode recovered) { throw new AssertionError("Unselected PP1 must not capture sources"); }
            public ObjectNode evaluate(ObjectNode captured, ObjectNode binding) { throw new AssertionError("Unselected PP1 must not evaluate"); }
        });
    }

    /** Provider transport is a deterministic script; the production GnomadTabixProvider still owns parsing and provenance. */
    private VariantAnalysisProperties populationSettings(String dataset) throws Exception {
        Path script = temporary.resolve("SYNTHETIC-" + dataset + "-tabix.cmd");
        String suffix = "joint".equals(dataset) ? "_joint" : "";
        String info = "AC" + suffix + "=21;AN" + suffix + "=10000;AF" + suffix + "=0.0021;nhomalt" + suffix + "=0"
                + ";grpmax" + suffix + "=nfe;AC_grpmax" + suffix + "=21;AN_grpmax" + suffix + "=10000;AF_grpmax" + suffix + "=0.0021;nhomalt_grpmax" + suffix + "=0"
                + ";faf95" + suffix + "=0.0015;fafmax_faf95_max" + suffix + "=0.0015;fafmax_faf95_max_gen_anc" + suffix + "=nfe"
                + ";AC" + suffix + "_nfe=21;AN" + suffix + "_nfe=10000;AF" + suffix + "_nfe=0.0021;nhomalt" + suffix + "_nfe=0;faf95" + suffix + "_nfe=0.0015"
                + ";AC" + suffix + "_nfe_XY=21;AN" + suffix + "_nfe_XY=10000;AF" + suffix + "_nfe_XY=0.0021;faf95" + suffix + "_nfe_XY=0.0015"
                + ";exomes_filters=PASS;genomes_filters=PASS;stat_union_p_value=0.5;stat_union_test_name=cmh;stat_union_gen_ancs=nfe";
        Files.writeString(script, "@echo off\r\necho query>>\"%~dp0" + dataset + "-queries.txt\"\r\n"
                + "echo chrX\t2\tSYNTHETIC_POPULATION_RAW\tT\tA\t.\tPASS\t" + info + "\r\nexit /b 0\r\n");
        var settings = new VariantAnalysisProperties(); var gnomad = settings.getGnomad();
        gnomad.setEnabled(true); gnomad.setPopulationInputEnabled(true); gnomad.setVersion("gnomAD " + dataset + " v4.1 GRCh38");
        gnomad.setTabixExecutable(script.toString()); gnomad.setTimeout(Duration.ofSeconds(5));
        gnomad.setRemoteTotalTimeout(Duration.ofSeconds(5)); gnomad.setRemoteMaxAttempts(1); gnomad.setParallelism(1);
        gnomad.setLocalPopulationInProcessEnabled(false); gnomad.setAllowFullVcfFallback(false); gnomad.setAllowVcfInfoFallback(false);
        if ("exomes".equals(dataset)) {
            gnomad.setBa1LocalExomesEnabled(true);
            Path data = temporary.resolve("gnomad.exomes.v4.1.sites.chrX.vcf.bgz");
            Files.writeString(data, "SYNTHETIC script transport only"); Files.writeString(Path.of(data + ".tbi"), "SYNTHETIC index");
            gnomad.setGrch38Files(data.toString());
        }
        return settings;
    }

    /** Real indexed REF lookup over 35 synthetic bytes; only assembly readiness is a declared engineering seam. */
    private NativeSnvSourceProducer tinyReference() throws Exception {
        Path fasta = temporary.resolve("SYNTHETIC-routed.fa"), index = Path.of(fasta + ".fai");
        Files.writeString(fasta, ">chr11\nATCG\n>chr13\nAACT\n>chrX\nATCG\n");
        Files.writeString(index, "chr11\t4\t7\t4\t5\nchr13\t4\t19\t4\t5\nchrX\t4\t30\t4\t5\n");
        String sha = org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(Files.readString(fasta));
        var receipt = base.mapper.createObjectNode().put("resourceId", "SYNTHETIC_ROUTED_TINY_FASTA").put("resourceVersion", "1")
                .put("assembly", "GRCh38").put("logicalName", fasta.getFileName().toString()).put("expectedSha256", sha).put("observedSha256", sha)
                .put("hashStatus", "VERIFIED").put("hashAlgorithm", "SHA-256").put("hashScope", "RAW_BYTES").put("unchangedDuringHash", true)
                .put("byteLength", Files.size(fasta)).put("mtimeNs", Files.getLastModifiedTime(fasta).to(TimeUnit.NANOSECONDS))
                .put("startedAt", "2026-11-03T00:00:00Z").put("completedAt", "2026-11-03T00:00:01Z")
                .put("indexSha256", org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(Files.readString(index)))
                .put("resourceDataClass", "SYNTHETIC");
        var envelope = base.mapper.createObjectNode().put("source", "SYNTHETIC_ROUTED_REFERENCE_CHECKSUM").put("version", "1").put("dataClass", "SYNTHETIC");
        envelope.putObject("records").putObject("checksum").set("actualChecksumReceipt", receipt);
        var ledger = ExplicitContextSourceLedger.create(base.mapper, envelope); base.fixture.sources.add(ledger);
        var settings = new VariantAnalysisProperties();
        settings.getNormalization().setGrch38Reference(fasta.toString()); settings.getNormalization().setGrch38ReferenceSha256(sha);
        settings.getNormalization().setGrch38ProfileId("SYNTHETIC_ROUTED_TINY_PROFILE");
        var validator = new NormalizationResourceValidator(settings) {
            @Override public NormalizationReferenceReadiness validate(GenomeAssembly assembly) {
                return new NormalizationReferenceReadiness(assembly, "READY", "SYNTHETIC_ROUTED_TINY_PROFILE",
                        fasta.getFileName().toString(), sha, true, true, 3, "CHR_PREFIXED", List.of(), List.of("SYNTHETIC_TEST_READINESS"));
            }
        };
        return new NativeSnvSourceProducer(base.mapper, settings, temporary, base.fixture.sources, ledger.reference("checksum"), validator);
    }

    private ObjectNode chooseFromActualOptions(String... criteria) throws Exception {
        return chooseBackgrounds(List.of("MONDO:0999001"), criteria);
    }

    private ObjectNode chooseBackgrounds(List<String> diseases, String... criteria) throws Exception {
        int jointBefore=queries("joint"), exomesBefore=queries("exomes");
        var options = (ObjectNode) base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options", base.analysisId)
                .param("topN", Integer.toString(diseases.size())).param("workflow", "CURRENT")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertTrue(options.path("executionSourceRecovered").asBoolean(), options.toPrettyString());
        assertEquals(diseases.size(), options.path("selectedCount").asInt());
        base.request.put("topN", diseases.size()).put("declarationVersion", options.at("/executionProfile/requiredDeclaration").asText());
        base.request.putArray("hypotheses"); base.request.set("selectedCriteria", base.mapper.valueToTree(List.of(criteria)));
        var choices = base.request.putArray("recommendations");
        for (int index = 0; index < diseases.size(); index++) {
            var recommendation = options.path("candidates").path(index).path("recommendation");
            assertTrue(recommendation.at("/bs1ContextPreparation/available").asBoolean(), recommendation.toPrettyString());
            var choice = choices.addObject();
            for (String field : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) choice.set(field, recommendation.path(field));
            JsonNode key = null;
            for (var relation : recommendation.path("relationships")) if (diseases.get(index).equals(relation.path("mondoId").asText()))
                for (var eligible : recommendation.at("/bs1ContextPreparation/eligibleRelationshipKeys"))
                    if (eligible.equals(relation.path("stableRelationshipKey"))) key = eligible;
            assertNotNull(key, recommendation.toPrettyString());
            choice.set("stableRelationshipKey", key); choice.putNull("annotationId");
        }
        assertEquals(jointBefore, queries("joint")); assertEquals(exomesBefore, queries("exomes"));
        return options;
    }

    private int queries(String dataset) throws Exception {
        Path file = temporary.resolve(dataset + "-queries.txt"); return Files.exists(file) ? Files.readAllLines(file).size() : 0;
    }
    private JsonNode bs1(JsonNode saved) { return saved.at("/candidates/0/attempts/0/bs1"); }
    private ObjectNode submit() throws Exception { return base.postRequest(base.request); }

    private void prepareMixedSources() throws Exception {
        installKnowledge(Bs1RoutedPopulationTestMaterials.record(true), Bs1RoutedPopulationTestMaterials.record(false));
        base.expectedPublishedCount = 2;
        base.variantTsvOverride = tsv("1\tX\t2\tT\tA\tHBB\t3043\tXD\t0.8", "2\tX\t3\tC\tT\tHBB\t3043\tXD\t0.7");
        base.publish(false, GenomeAssembly.GRCh38, vcf("chrX\t2\tSYNTHETIC_ROUTED_JOINT\tT\tA\t.\tPASS\t.\tGT\t0/1",
                "chrX\t3\tSYNTHETIC_ROUTED_EXOMES\tC\tT\t.\tPASS\t.\tGT\t0/1"));
        Path script = temporary.resolve("SYNTHETIC-exomes-tabix.cmd");
        Files.writeString(script, Files.readString(script).replace("chrX\t2\tSYNTHETIC_POPULATION_RAW\tT\tA", "chrX\t3\tSYNTHETIC_POPULATION_RAW\tC\tT"));
        wire();
    }

    @Test void selectedBs1UsesConfirmedPublicBackgroundAndOneJointObservationWithoutBa1() throws Exception {
        var before = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100));
        chooseFromActualOptions(BS1); var saved = submit();
        assertEquals(base.mapper.valueToTree(List.of(BS1)), saved.path("enabledCriteria"));
        assertFalse(saved.at("/candidates/0").has("ba1"));
        assertEquals("MET", bs1(saved).at("/evaluation/evaluationResult/status").asText(), saved.toPrettyString());
        assertEquals("JOINT", bs1(saved).at("/qualityDecision/sexStratifiedObservation/dataset").asText());
        assertEquals("XY", bs1(saved).at("/qualityDecision/facts/karyotype").asText());
        assertEquals("remote-joint-vcf-population-input", bs1(saved).at("/observation/transport/querySource").asText());
        assertEquals(1, queries("joint")); assertEquals(0, queries("exomes"));
        assertEquals(before, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100)));
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));
        assertEquals(saved, submit()); assertEquals(1, queries("joint"));
        assertTrue(base.request.path("hypotheses").isEmpty(), "Business preparation must supply its own source-bound hypothesis");
    }

    @Test void selectedBa1AndBs1ShareTheActualProfileBoundParentAndKeepIndependentResults() throws Exception {
        chooseFromActualOptions(BA1, BS1); var saved = submit();
        assertEquals(base.mapper.valueToTree(List.of(BA1, BS1)), saved.path("enabledCriteria"));
        assertEquals("MET", bs1(saved).at("/evaluation/evaluationResult/status").asText(), saved.toPrettyString());
        var ba1 = saved.at("/candidates/0/ba1");
        assertEquals("ACMG_AMP_2015:BA1", ba1.at("/evaluation/criterionId").asText(), ba1.toPrettyString());
        assertEquals("HIT", ba1.at("/evidence/queryOutcome/status").asText());
        assertEquals(bs1(saved).at("/observation/carrier/source"), ba1.at("/evidence/source"));
        assertEquals(1, queries("joint"), "Same-profile BA1 must consume the sealed observation acquired by normal BS1 execution");
        assertEquals(0, queries("exomes"));
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));
        assertEquals(saved, submit()); assertEquals(1, queries("joint"));
    }

    @Test void allEnabledCriteriaKeepTheRoutedPopulationSourceAndIndependentMissingModules() throws Exception {
        var options = chooseFromActualOptions(BA1, BS1);
        base.request.set("selectedCriteria", options.path("enabledCriteria").deepCopy());
        var saved = submit();
        assertEquals(options.path("enabledCriteria"), saved.path("enabledCriteria"));
        assertEquals("MET", bs1(saved).at("/evaluation/evaluationResult/status").asText(), saved.toPrettyString());
        assertEquals("JOINT", bs1(saved).at("/qualityDecision/sexStratifiedObservation/dataset").asText());
        assertEquals(1, queries("joint")); assertEquals(0, queries("exomes"));
        assertTrue(saved.at("/candidates/0/attempts/0/localBatch/criteria").size() > 0);
        assertTrue(saved.at("/candidates/0/enabledRuleResultsComplete").asBoolean(), saved.toPrettyString());
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));
        assertEquals(saved, submit()); assertEquals(1, queries("joint"));
    }

    @Test void sameCandidateBackgroundsShareFactsWhileEachPolicyKeepsItsOwnThresholdAndDecision() throws Exception {
        var first = Bs1RoutedPopulationTestMaterials.record(true);
        var second = Bs1RoutedPopulationTestMaterials.record(true);
        second.put("recordId", "SYNTHETIC_ROUTED_OTHER_JOINT_BACKGROUND");
        second.withObject("/scope").put("mondoId", "MONDO:0999003");
        second.withObject("/expertPolicy/scope").put("mondoId", "MONDO:0999003");
        second.withObject("/expertPolicy/tiers/0/conditions/0").put("decimal", "0.003")
                .put("originalText", "SYNTHETIC second background: target AF > 0.003");
        Bs1RoutedPopulationTestMaterials.qualify(second);
        installKnowledge(first, second); multipleBackgrounds = true; wire();
        var options = chooseFromActualOptions(BS1); var recommendation = options.at("/candidates/0/recommendation");
        var choice = (ObjectNode) base.request.at("/recommendations/0"); choice.putNull("stableRelationshipKey").putNull("annotationId");
        var selections = choice.putArray("backgrounds"); var inputs = base.request.putArray("hypotheses");
        for (String disease : List.of("MONDO:0999001", "MONDO:0999003")) {
            JsonNode relation = null;
            for (var row : recommendation.path("relationships")) if (disease.equals(row.path("mondoId").asText()) && row.path("selectable").asBoolean()) relation = row;
            if (relation == null) throw new AssertionError("Actual options omitted a source-qualified background: " + disease);
            var source = new PatientDiseaseContextBindingResolver.HypothesisProvenance("EXPLICIT_USER_DECLARATION", "1.0.0",
                    "recommendations/" + recommendation.path("recommendationId").asText() + "/selection");
            String candidate = recommendation.path("candidateId").asText();
            var scope = new PatientDiseaseContextBindingResolver.PatientDiseaseHypothesis(candidate, "SYNTHETIC_PENDING",
                    relation.path("hgncId").asText(), relation.path("mondoId").asText(), relation.path("moi").asText(), source);
            String id = AcmgRecommendations.backgroundId(scope);
            var hypothesis = new PatientDiseaseContextBindingResolver.PatientDiseaseHypothesis(candidate, id,
                    scope.hgncId(), scope.mondoId(), scope.moi(), source);
            inputs.addObject().put("affectedStatus", "UNKNOWN").set("hypothesis", base.mapper.valueToTree(hypothesis));
            selections.addObject().put("hypothesisId", id).putNull("stableRelationshipKey").putNull("annotationId");
        }
        var saved = submit(); var attempts = saved.at("/candidates/0/attempts"); assertEquals(2, attempts.size(), saved.toPrettyString());
        JsonNode met = null, notMet = null;
        for (var attempt : attempts) {
            var result = attempt.path("bs1");
            if ("MONDO:0999001".equals(result.at("/context/disease/mondoId").asText())) met = result;
            if ("MONDO:0999003".equals(result.at("/context/disease/mondoId").asText())) notMet = result;
        }
        if (met == null || notMet == null) throw new AssertionError("Each selected background must reach its own BS1 context");
        assertEquals("MET", met.at("/evaluation/evaluationResult/status").asText());
        assertEquals("NOT_MET", notMet.at("/evaluation/evaluationResult/status").asText());
        assertEquals(met.at("/observation/observationId"), notMet.at("/observation/observationId"));
        assertNotEquals(met.at("/context/contextId"), notMet.at("/context/contextId"));
        assertNotEquals(met.at("/evaluation/evaluationId"), notMet.at("/evaluation/evaluationId"));
        assertEquals(1, queries("joint")); assertEquals(0, queries("exomes"));
        assertEquals(2, saved.at("/bs1Calls/evaluator").asInt());
        assertEquals(saved, submit()); assertEquals(1, queries("joint"));
    }

    @Test void changedTrustedPolicyRetainsOldHistoryWithoutReusingItsDecisionForANewExecution() throws Exception {
        chooseFromActualOptions(BS1); var old=submit(); String run=old.path("runId").asText();
        var oldRequest=base.request.deepCopy(); var oldStored=base.store.run(base.analysisId,run).orElseThrow();
        assertEquals("MET",old.at("/candidates/0/attempts/0/bs1/evaluation/evaluationResult/status").asText());
        var replacement=Bs1RoutedPopulationTestMaterials.record(true);
        replacement.withObject("/expertPolicy/tiers/0/conditions/0").put("decimal","0.003")
                .put("originalText","SYNTHETIC revised source policy threshold >0.003");
        Bs1RoutedPopulationTestMaterials.qualify(replacement); installKnowledge(replacement); wire();
        assertEquals(old,base.service.read(base.analysisId,run));
        oldRequest.put("operationId",java.util.UUID.randomUUID().toString()).put("retryOf",run);
        base.http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/variant-analyses/{id}/acmg/runs",base.analysisId)
                .contentType("application/json").content(requireNonNull(base.mapper.writeValueAsBytes(oldRequest)))).andExpect(status().isConflict());
        assertEquals(1,queries("joint"));
        chooseFromActualOptions(BS1); var current=submit();
        assertNotEquals(old.path("executionProfile"),current.path("executionProfile"));
        assertEquals("NOT_MET",current.at("/candidates/0/attempts/0/bs1/evaluation/evaluationResult/status").asText());
        assertEquals(old,base.service.read(base.analysisId,run));
        assertEquals(oldStored.privateJson(),base.store.run(base.analysisId,run).orElseThrow().privateJson());
        assertEquals(oldStored.responseJson(),base.store.run(base.analysisId,run).orElseThrow().responseJson());
        assertEquals(2,queries("joint"));
    }

    @Test void freshJvmReadsTheActualBusinessStoreThroughServiceAndExportsTheFullGetWithoutExecution() throws Exception {
        chooseFromActualOptions(BS1); var saved = submit(); String run = saved.path("runId").asText();
        var stored = base.store.run(base.analysisId, run).orElseThrow();
        byte[] get = base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", base.analysisId, run))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        Files.writeString(temporary.resolve("expected-public.json"), stored.responseJson());
        Files.writeString(temporary.resolve("expected-private.json"), stored.privateJson());
        Files.write(temporary.resolve("expected-full-get.json"), get);
        String calls = Files.readString(temporary.resolve("joint-queries.txt"));
        base.closeDatabase();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(java)) java = Path.of(System.getProperty("java.home"), "bin", "java");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = temporary.resolve("fresh-jvm-reader.log");
        Process process = new ProcessBuilder(java.toString(), "-cp", classpath, FreshJvmHistoryReader.class.getName(),
                temporary.toString(), base.analysisId, run).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean ended = process.waitFor(45, TimeUnit.SECONDS);
        if (!ended) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        assertTrue(ended, "Independent business history read timed out");
        assertEquals(0, process.exitValue(), Files.readString(log));
        assertArrayEquals(get, Files.readAllBytes(temporary.resolve("fresh-jvm-export.json")));
        assertEquals(stored.privateJson(), Files.readString(temporary.resolve("fresh-jvm-private.json")));
        assertEquals(saved, base.mapper.readTree(Files.readAllBytes(temporary.resolve("fresh-jvm-export.json"))));
        assertEquals(calls, Files.readString(temporary.resolve("joint-queries.txt")));
    }

    @Test void oneCurrentRequestRoutesEachSelectedBackgroundToItsOwnDatasetOnce() throws Exception {
        prepareMixedSources(); chooseBackgrounds(List.of("MONDO:0999001", "MONDO:0999002"), BS1);
        var saved = submit();
        var first = saved.at("/candidates/0/attempts/0/bs1"); var second = saved.at("/candidates/1/attempts/0/bs1");
        assertEquals("MET", first.at("/evaluation/evaluationResult/status").asText(), saved.toPrettyString());
        assertEquals("MET", second.at("/evaluation/evaluationResult/status").asText(), saved.toPrettyString());
        assertEquals("JOINT", first.at("/qualityDecision/sexStratifiedObservation/dataset").asText());
        assertEquals("EXOMES", second.at("/qualityDecision/sexStratifiedObservation/dataset").asText());
        assertNotEquals(first.at("/observation/profileSha256"), second.at("/observation/profileSha256"));
        assertEquals(1, queries("joint")); assertEquals(1, queries("exomes"));
        assertEquals(saved, submit()); assertEquals(1, queries("joint")); assertEquals(1, queries("exomes"));
    }

    @ParameterizedTest @ValueSource(strings = {"NO_RECORD", "FILTERED", "FAILED", "DISABLED"})
    void oneSourceFailureDoesNotEraseTheOtherDatasetOrBecomeAfZero(String mode) throws Exception {
        prepareMixedSources();
        Path script = temporary.resolve("SYNTHETIC-joint-tabix.cmd");
        if ("FILTERED".equals(mode)) Files.writeString(script, Files.readString(script).replace("\tPASS\t", "\tEXOMES_FILTERED\t"));
        else if ("DISABLED".equals(mode)) joint.getGnomad().setEnabled(false);
        else Files.writeString(script, "@echo off\r\necho query>>\"%~dp0joint-queries.txt\"\r\nexit /b " + ("FAILED".equals(mode) ? "7" : "0") + "\r\n");
        wire(); chooseBackgrounds(List.of("MONDO:0999001", "MONDO:0999002"), BS1);
        var saved = submit(); var first = saved.at("/candidates/0/attempts/0/bs1"); var second = saved.at("/candidates/1/attempts/0/bs1");
        assertEquals("NOT_EVALUATED", first.at("/evaluation/evaluationResult/status").asText(), saved.toPrettyString());
        assertEquals("MET", second.at("/evaluation/evaluationResult/status").asText(), saved.toPrettyString());
        assertEquals("EXOMES", second.at("/qualityDecision/sexStratifiedObservation/dataset").asText());
        assertEquals("COMPLETED_WITH_BLOCKERS", saved.path("status").asText());
        assertEquals("DISABLED".equals(mode) ? 0 : 1, queries("joint")); assertEquals(1, queries("exomes"));
        switch (mode) {
            case "NO_RECORD" -> {
                assertEquals("BS1_NO_RECORD", first.at("/evaluation/evaluationResult/reasonCode").asText());
                assertEquals("NO_RECORD", first.at("/observation/carrier/queryOutcome/status").asText());
                assertTrue(first.at("/qualityDecision/facts").isEmpty());
            }
            case "FILTERED" -> assertEquals("BS1_JOINT_FILTER_NOT_PASS", first.at("/evaluation/evaluationResult/reasonCode").asText());
            case "FAILED" -> {
                assertEquals("BS1_RESOURCE_EXECUTION_UNAVAILABLE", first.at("/evaluation/evaluationResult/reasonCode").asText());
                assertEquals("FAILED", first.at("/observation/carrier/resourceExecution/status").asText());
                assertEquals("NOT_QUERIED", first.at("/observation/carrier/queryOutcome/status").asText());
            }
            case "DISABLED" -> assertEquals("BS1_POPULATION_SOURCE_DISABLED", first.at("/evaluation/evaluationResult/reasonCode").asText());
            default -> fail("Unexpected synthetic scenario");
        }
        assertEquals(saved, base.service.read(base.analysisId, saved.path("runId").asText()));
    }

    /** Actual business repository/service/HTTP readback; this child never installs or runs a scientific factory. */
    public static final class FreshJvmHistoryReader {
        public static void main(String[] args) throws Exception {
            Path directory = Path.of(args[0]); String analysis = args[1], run = args[2];
            var mapper = Jackson2ObjectMapperBuilder.json().build().enable(SerializationFeature.INDENT_OUTPUT)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            var ds = new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("business-db").toString().replace('\\', '/')
                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
            try {
                var settings = new VariantAnalysisProperties(); settings.setStorageRoot(directory.resolve("jobs"));
                settings.getPersistence().setRetryBackoff(Duration.ZERO);
                var jobs = new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(ds), new DataSourceTransactionManager(ds), settings, mapper);
                var store = new AcmgBusinessStore(ds); var sources = new AcmgExecutionSources(mapper, store);
                var service = new AcmgBusinessService(mapper, jobs, store, sources,
                        () -> { throw new AssertionError("Historical read must never acquire a scientific runtime"); });
                var stored = store.run(analysis, run).orElseThrow();
                if (!Files.readString(directory.resolve("expected-public.json")).equals(stored.responseJson())
                        || !Files.readString(directory.resolve("expected-private.json")).equals(stored.privateJson())
                        || !AcmgBusinessStore.sha(stored.responseJson()).equals(stored.responseSha256()))
                    throw new IllegalStateException("Business population source/decision bytes changed");
                var full = service.read(analysis, run);
                if (!full.equals(mapper.readTree(stored.responseJson())) || service.list(analysis).stream().noneMatch(value -> run.equals(value.path("runId").asText())))
                    throw new IllegalStateException("Actual service read/list changed saved history");
                var http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
                byte[] get = http.perform(get("/variant-analyses/{id}/acmg/runs/{run}", analysis, run))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
                if (!Arrays.equals(Files.readAllBytes(directory.resolve("expected-full-get.json")), get))
                    throw new IllegalStateException("Full HTTP export changed after an independent JVM read");
                Files.write(directory.resolve("fresh-jvm-export.json"), get, StandardOpenOption.CREATE_NEW);
                Files.writeString(directory.resolve("fresh-jvm-private.json"), stored.privateJson(), StandardOpenOption.CREATE_NEW);
                System.out.println("PASS: actual business Service read/list/full GET and source bytes unchanged; no execution");
            } finally { new JdbcTemplate(ds).execute("SHUTDOWN"); }
        }
    }
}
