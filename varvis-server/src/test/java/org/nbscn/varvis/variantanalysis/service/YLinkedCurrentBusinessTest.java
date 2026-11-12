package org.nbscn.varvis.variantanalysis.service;

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
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.vcf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual CURRENT Y path; every disease/location/rule/reference/population fixture is SYNTHETIC.
 * Existing finite rule threshold is retained; AF=.001 must NOT satisfy its >.002 condition.
 * This is not a new real Y rule adoption, human-reference/VEP run, or user download. */
class YLinkedCurrentBusinessTest {
    private static final String BS1="ACMG_AMP_2015:BS1", DISEASE="MONDO:0999001";
    @TempDir Path temporary;
    private Ba1BusinessIntegrationTest fixture;
    private AcmgBusinessIntegrationTest base;
    private Bs1KnowledgeCatalog knowledge;
    private VariantAnalysisProperties exomes,joint;
    private AcmgBusinessWorkflows workflows;
    private PatientDiseaseContextBindingResolver initialResolver;
    private ObjectNode lastOptions;
    private final java.util.List<String> analyses=new java.util.ArrayList<>();

    @BeforeEach void setup() throws Exception {
        fixture=new Ba1BusinessIntegrationTest(); fixture.temporary=temporary; fixture.setup(); base=fixture.base;
        base.producer=tinyReference(); initialResolver=base.fixture.resolver;
        joint=populationSettings("joint");exomes=populationSettings("exomes");
        fixture.ba1=new Ba1AlleleRuntime(base.mapper,joint,Ba1SpecificationCatalog.bundled(base.mapper));
        fixture.ba1Binding=fixture.ba1.identity();
        install(finiteYRecord()); publish("Y_LINKED","Y","T","A",GenomeAssembly.GRCh38,"chrY\t2\tSYNTHETIC_Y\tT\tA\t.\tPASS\t.\tGT\t1");
    }
    @AfterEach void close(org.junit.jupiter.api.TestInfo info) throws Exception {
        try {
            String output=System.getProperty("varvis.a10.y.output");
            if(output!=null && !output.isBlank() && base!=null) {
                Path dir=Path.of(output).resolve(AcmgBusinessStore.sha(info.getDisplayName()).substring(0,10)); Files.createDirectories(dir);
                var m=base.mapper.createObjectNode().put("scenario",info.getDisplayName()).put("dataClass","SYNTHETIC")
                    .put("layer","ACTUAL_CURRENT_BUSINESS_WITH_SYNTHETIC_RULE_REFERENCE_TRANSPORT_NOT_USER_DOWNLOAD");
                if(lastOptions!=null) Files.write(dir.resolve("options.json"),base.mapper.writeValueAsBytes(lastOptions));
                Files.write(dir.resolve("request.json"),base.mapper.writeValueAsBytes(base.request));
                var runs=m.putArray("records");int ordinal=0;
                for(String analysis:analyses) for(var row:base.store.runs(analysis)) {
                    var run=base.store.run(analysis,row.id()).orElseThrow(); String prefix=++ordinal+"-";
                    Files.writeString(dir.resolve(prefix+"public.json"),run.responseJson());
                    Files.writeString(dir.resolve(prefix+"private.json"),run.privateJson());
                    Files.write(dir.resolve(prefix+"full-get.json"),base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",analysis,row.id()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
                    runs.addObject().put("analysisId",analysis).put("runId",row.id()).put("prefix",prefix).put("responseSha256",run.responseSha256());
                }
                Files.write(dir.resolve("manifest.json"),base.mapper.writeValueAsBytes(m));
            }
        } finally {if(fixture!=null)fixture.close();}
    }
    private ObjectNode finiteYRecord() {
        var record=Bs1RoutedPopulationTestMaterials.record(true);
        record.put("recordId","SYNTHETIC_ROUTED_Y_HBB_NOT_REAL_BIOLOGY");
        record.withObject("/scope").putArray("modes").add("Y_LINKED");
        record.withObject("/scope/sexChromosome").putArray("contigs").add("Y");
        record.withObject("/expertPolicy/scope").putArray("modes").add("Y_LINKED");
        record.withObject("/expertPolicy/scope/sexChromosome").putArray("contigs").add("Y");
        record.withObject("/expertPolicy/tiers/0").put("moi","Y_LINKED");
        record.withObject("/relationship").put("diseaseLabel","SYNTHETIC Y routing fixture; not HBB biology");
        Bs1RoutedPopulationTestMaterials.qualify(record);
        return record;
    }
    private void install(ObjectNode... records) throws Exception {
        installBundle(Bs1RoutedPopulationTestMaterials.bundle(base.mapper,records));
    }
    private void installBundle(ObjectNode bundle) throws Exception {
        byte[] bytes=base.mapper.writeValueAsBytes(bundle);Path file=temporary.resolve("knowledge-"+Bs1KnowledgeCatalog.sha(bytes)+".json");Files.write(file,bytes);
        knowledge=Bs1KnowledgeCatalog.load(file,Bs1KnowledgeCatalog.sha(bytes));
        base.fixture.resolver=initialResolver.withBs1Knowledge(base.mapper,knowledge);
    }
    private void publish(String moi,String contig,String ref,String alt,GenomeAssembly assembly,String vcfRow) throws Exception {
        base.expectedPublishedCount=1;base.variantTsvOverride=tsv("1\t"+contig+"\t2\t"+ref+"\t"+alt+"\tHBB\t3043\t"+moi+"\t0.8");
        base.publish(false,assembly,vcf(vcfRow));analyses.add(base.analysisId);wire();
    }
    private ObjectNode options() throws Exception {
        lastOptions=(ObjectNode)base.mapper.readTree(base.http.perform(get("/variant-analyses/{id}/acmg/input-options",base.analysisId)
            .param("topN","1").param("workflow","CURRENT")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertEquals(0,queries());return lastOptions;
    }
    private void choose(boolean requirePolicy) throws Exception {
        var option=options();base.request.put("topN",1).put("declarationVersion",option.at("/executionProfile/requiredDeclaration").asText());
        base.request.putArray("hypotheses");base.request.putArray("selectedCriteria").add(BS1);
        var rec=option.at("/candidates/0/recommendation");var choice=base.request.putArray("recommendations").addObject();
        for(String key:List.of("candidateId","recommendationId","policyVersion","policyHash","generatedAt"))choice.set(key,rec.path(key));
        var key=rec.at("/bs1ContextPreparation/eligibleRelationshipKeys/0");
        if(requirePolicy) assertTrue(key.isTextual(),rec.toPrettyString());
        if(key.isTextual())choice.set("stableRelationshipKey",key);else choice.putNull("stableRelationshipKey");
        choice.putNull("annotationId");
    }
    private int queries() throws Exception {Path p=temporary.resolve("joint-queries.txt");return Files.exists(p)?Files.readAllLines(p).size():0;}
    private JsonNode result(JsonNode saved) {return saved.at("/candidates/0/attempts/0/bs1");}

    @Test void canonicalYTravelsThroughCurrentConfirmationSelectedTopNAndPersistsNotMet() throws Exception {
        var candidates=base.jobs.findInterpretations(base.analysisId,0,1);var c=candidates.get(0);
        assertEquals("Y_LINKED",c.getCandidateIdentity().getModeOfInheritance());
        assertEquals("Y_LINKED",c.getExomiserCandidateFeatures().getRawModeOfInheritance());
        assertEquals("Y_LINKED",c.getExomiserCandidateFeatures().getNormalizedModeOfInheritance());
        assertEquals(org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall.TargetAlleleCall.HEMI_TARGET,c.getAnalysisVcfCall().getTargetAlleleCall());
        var before=base.mapper.valueToTree(candidates);choose(true);var saved=base.postRequest(base.request);var r=result(saved);
        assertEquals("NOT_MET",r.at("/evaluation/evaluationResult/status").asText(),saved.toPrettyString());
        assertEquals("Y_LINKED",r.at("/context/moi").asText(),r.toPrettyString());
        assertEquals("JOINT",r.at("/qualityDecision/sexStratifiedObservation/dataset").asText());
        assertEquals(1,queries());assertEquals(before,base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId,0,1)));
        String id=saved.path("runId").asText();var old=base.store.run(base.analysisId,id).orElseThrow();
        assertEquals(saved,base.service.read(base.analysisId,id));
        assertEquals(saved,base.postRequest(base.request));assertEquals(1,queries());
        base.closeDatabase();base.recreateRepositories();fixture.wire();wire();
        assertEquals(saved,base.service.read(base.analysisId,id));assertEquals(old.responseJson(),base.store.run(base.analysisId,id).orElseThrow().responseJson());
        assertEquals(old.privateJson(),base.store.run(base.analysisId,id).orElseThrow().privateJson());assertEquals(1,queries());
    }
    @ParameterizedTest @ValueSource(strings={"Y-LINKED","Y LINKED","y_linked"})
    void preciseAliasesPreserveRawIdentityAndUseCanonicalYThroughCurrent(String raw) throws Exception {
        publish(raw,"Y","T","A",GenomeAssembly.GRCh38,"chrY\t2\tSYNTHETIC_ALIAS\tT\tA\t.\tPASS\t.\tGT\t1");
        var c=base.jobs.findInterpretations(base.analysisId,0,1).get(0);
        assertEquals(raw,c.getCandidateIdentity().getModeOfInheritance());assertEquals(raw,c.getExomiserCandidateFeatures().getRawModeOfInheritance());
        assertEquals("Y_LINKED",c.getExomiserCandidateFeatures().getNormalizedModeOfInheritance());
        choose(true);var saved=base.postRequest(base.request);
        assertEquals("NOT_MET",result(saved).at("/evaluation/evaluationResult/status").asText(),saved.toPrettyString());
        assertEquals("Y_LINKED",result(saved).at("/context/moi").asText());assertEquals(1,queries());
    }
    @ParameterizedTest @ValueSource(strings={"XL","Y","hemizygous","UNKNOWN"})
    void broadOrUnrecognizedModesCannotAcquireSpecificYContext(String mode) throws Exception {
        publish(mode,"Y","T","A",GenomeAssembly.GRCh38,"chrY\t2\tSYNTHETIC_UNKNOWN\tT\tA\t.\tPASS\t.\tGT\t1");
        assertNull(base.jobs.findInterpretations(base.analysisId,0,1).get(0).getExomiserCandidateFeatures().getNormalizedModeOfInheritance());
        choose(false);var saved=base.postRequest(base.request);
        assertEquals("NOT_EVALUATED",result(saved).at("/evaluation/evaluationResult/status").asText(),saved.toPrettyString());assertEquals(0,queries());
    }
    @ParameterizedTest @ValueSource(strings={"ALT","CONTIG","ASSEMBLY"})
    void inconsistentAnalysisVcfAlleleCannotReachPopulationScoring(String fault) throws Exception {
        String contig="CONTIG".equals(fault)?"chrX":"chrY",alt="ALT".equals(fault)?"G":"A";
        publish("Y_LINKED","Y","T","A","ASSEMBLY".equals(fault)?GenomeAssembly.GRCh37:GenomeAssembly.GRCh38,
            contig+"\t2\tSYNTHETIC_WRONG_ANALYSIS\tT\t"+alt+"\t.\tPASS\t.\tGT\t1");
        choose(false);var saved=base.postRequest(base.request);
        assertEquals("NOT_EVALUATED",result(saved).at("/evaluation/evaluationResult/status").asText(),saved.toPrettyString());assertEquals(0,queries());
    }
    @Test void exactYRelationshipWithoutSupportedPolicyDoesNotBecomeGenericMcf() throws Exception {
        var b=new Bs1KnowledgeCatalogTest().bundle();
        var row=b.withArray("/backgrounds").addObject().put("hgncId","HGNC:4827").put("mondoId",DISEASE).put("moi","Y_LINKED").put("reasonCode","BS1_MODEL_MOI_UNSUPPORTED");
        row.putArray("assertions").addObject().put("hgncId","HGNC:4827").put("mondoId",DISEASE).put("moi","Y_LINKED")
            .put("classification","Definitive").put("symbol","HBB").put("sourceId","synthetic-parameters");
        installBundle(b);wire();choose(false);var saved=base.postRequest(base.request);
        assertEquals("NOT_EVALUATED",result(saved).at("/evaluation/evaluationResult/status").asText(),saved.toPrettyString());
        assertTrue(saved.toString().contains("BS1_MODEL_MOI_UNSUPPORTED"),saved.toPrettyString());assertEquals(0,queries());
    }
    @Test void sourceWithDifferentPreciseMoiCannotAuthorizeTheYBackground() throws Exception {
        install(Bs1RoutedPopulationTestMaterials.record(true));wire();choose(false);
        var saved=base.postRequest(base.request);
        assertEquals("NOT_EVALUATED",result(saved).at("/evaluation/evaluationResult/status").asText(),saved.toPrettyString());
        assertEquals(0,queries());
    }

    @Test void mismatchedSampleDeclarationDoesNotBecomePopulationEvidence() throws Exception {
        choose(true);base.request.put("sampleId","SYNTHETIC_DIFFERENT_SAMPLE");
        var saved=base.postRequest(base.request);
        assertEquals("NOT_EVALUATED",result(saved).at("/evaluation/evaluationResult/status").asText(),saved.toPrettyString());
        assertEquals(0,queries());
    }

    @Test void targetNotCarriedInGenotypeIsPreservedForChiefReviewOfTheSharedGate() throws Exception {
        publish("Y_LINKED","Y","T","A",GenomeAssembly.GRCh38,"chrY\t2\tSYNTHETIC_REF_CALL\tT\tA\t.\tPASS\t.\tGT\t0");
        assertEquals(org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall.TargetAlleleCall.HEMI_REF,
            base.jobs.findInterpretations(base.analysisId,0,1).get(0).getAnalysisVcfCall().getTargetAlleleCall());
        choose(true);var saved=base.postRequest(base.request);
        // Diagnostic, NOT clinical acceptance: current frozen same-MOI gate does not reject HEMI_REF.
        // If the shared policy is fixed, update this regression to assert the precise new refusal.
        assertEquals("NOT_MET",result(saved).at("/evaluation/evaluationResult/status").asText(),saved.toPrettyString());
        assertEquals(1,queries());
    }

    @Test void syntheticLegacyRawYWithoutNormalizedValueRemainsUnchangedAndDoesNotGainExecutionPermission() throws Exception {
        // Rebuild a SYNTHETIC old-format publication in isolated repositories. This is not an old real run.
        var c=base.jobs.findInterpretations(base.analysisId,0,1).get(0);
        ObjectNode features=base.mapper.valueToTree(c.getExomiserCandidateFeatures());features.remove("normalizedModeOfInheritance");
        features.withObject("/fieldSources").remove("normalizedModeOfInheritance");
        features.withArray("/invalidFields").add("MOI:UNRECOGNIZED");
        var oldFeatures=base.mapper.treeToValue(features,org.nbscn.varvis.variantanalysis.domain.ExomiserCandidateFeatures.class);
        var legacy=c.withCandidateIdentityAndExomiserFeatures(c.getCandidateIdentity(),oldFeatures);
        assertNull(legacy.getExomiserCandidateFeatures().getNormalizedModeOfInheritance());
        base.jobs.replaceInterpretations(base.analysisId,List.of(legacy));
        var original=base.store.source(base.analysisId).orElseThrow();var snapshot=(ObjectNode)base.mapper.readTree(original.json());
        var sourceRow=(ObjectNode)snapshot.at("/candidates/0");
        sourceRow.set("published",base.mapper.valueToTree(legacy));
        sourceRow.withObject("/engineCandidate").set("exomiserCandidateFeatures",base.mapper.valueToTree(oldFeatures));
        // Legacy-fixture preseed only, inside this test's isolated H2. Production saveSource is immutable.
        // No execution result is forged: CURRENT still creates and saves the actual refusal below.
        String legacyJson=base.mapper.writeValueAsString(snapshot);
        assertEquals(1,new JdbcTemplate(java.util.Objects.requireNonNull(base.dataSource)).update(
            "UPDATE variant_analysis_acmg_source SET snapshot_json=?,snapshot_sha256=? WHERE analysis_id=?",
            legacyJson,AcmgBusinessStore.sha(legacyJson),base.analysisId));
        String legacyAnalysis=base.analysisId,sourceBytes=base.store.source(legacyAnalysis).orElseThrow().json();
        choose(false);var saved=base.postRequest(base.request);
        assertEquals("NOT_EVALUATED",result(saved).at("/evaluation/evaluationResult/status").asText(),saved.toPrettyString());
        assertTrue(saved.toString().contains("CANDIDATE_MOI_REQUIRED"),saved.toPrettyString());assertEquals(0,queries());
        String runId=saved.path("runId").asText();var stored=base.store.run(legacyAnalysis,runId).orElseThrow();
        byte[] full=base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",legacyAnalysis,runId)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        base.closeDatabase();base.recreateRepositories();fixture.wire();wire();
        assertEquals(saved,base.service.read(legacyAnalysis,runId));
        assertArrayEquals(full,base.http.perform(get("/variant-analyses/{id}/acmg/runs/{run}",legacyAnalysis,runId)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray());
        assertEquals(sourceBytes,base.store.source(legacyAnalysis).orElseThrow().json());
        assertEquals(stored.responseJson(),base.store.run(legacyAnalysis,runId).orElseThrow().responseJson());
        assertEquals(stored.privateJson(),base.store.run(legacyAnalysis,runId).orElseThrow().privateJson());
        assertNull(base.jobs.findInterpretations(legacyAnalysis,0,1).get(0).getExomiserCandidateFeatures().getNormalizedModeOfInheritance());
        assertEquals(0,queries());
        // A separately parsed and explicitly confirmed analysis can use new normalization, not the old history.
        publish("Y_LINKED","Y","T","A",GenomeAssembly.GRCh38,"chrY\t2\tSYNTHETIC_NEW_Y\tT\tA\t.\tPASS\t.\tGT\t1");
        choose(true);assertEquals("NOT_MET",result(base.postRequest(base.request)).at("/evaluation/evaluationResult/status").asText());
        assertEquals(saved,base.service.read(legacyAnalysis,runId));
        assertEquals(stored.responseJson(),base.store.run(legacyAnalysis,runId).orElseThrow().responseJson());assertEquals(1,queries());
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
                null, null, null, null, null, null, false, null, null, null));
        base.service = AcmgBusinessService.unifiedLocal(population, local);
        workflows = new AcmgBusinessWorkflows(base.service, null);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(workflows)).build();
    }

    /** Provider transport is a deterministic script; the production GnomadTabixProvider still owns parsing and provenance. */
    private VariantAnalysisProperties populationSettings(String dataset) throws Exception {
        Path script = temporary.resolve("SYNTHETIC-" + dataset + "-tabix.cmd");
        String suffix = "joint".equals(dataset) ? "_joint" : "";
        String info = "AC" + suffix + "=10;AN" + suffix + "=10000;AF" + suffix + "=0.001;nhomalt" + suffix + "=0"
                + ";grpmax" + suffix + "=nfe;AC_grpmax" + suffix + "=10;AN_grpmax" + suffix + "=10000;AF_grpmax" + suffix + "=0.001;nhomalt_grpmax" + suffix + "=0"
                + ";faf95" + suffix + "=0.0005;fafmax_faf95_max" + suffix + "=0.0005;fafmax_faf95_max_gen_anc" + suffix + "=nfe"
                + ";AC" + suffix + "_nfe=10;AN" + suffix + "_nfe=10000;AF" + suffix + "_nfe=0.001;nhomalt" + suffix + "_nfe=0;faf95" + suffix + "_nfe=0.0005"
                + ";AC" + suffix + "_nfe_XY=10;AN" + suffix + "_nfe_XY=10000;AF" + suffix + "_nfe_XY=0.001;faf95" + suffix + "_nfe_XY=0.0005"
                + ";exomes_filters=PASS;genomes_filters=PASS;stat_union_p_value=0.5;stat_union_test_name=cmh;stat_union_gen_ancs=nfe";
        Files.writeString(script, "@echo off\r\necho query>>\"%~dp0" + dataset + "-queries.txt\"\r\n"
                + "echo chrY\t2\tSYNTHETIC_POPULATION_RAW\tT\tA\t.\tPASS\t" + info + "\r\nexit /b 0\r\n");
        var settings = new VariantAnalysisProperties(); var gnomad = settings.getGnomad();
        gnomad.setEnabled(true); gnomad.setPopulationInputEnabled(true); gnomad.setVersion("gnomAD " + dataset + " v4.1 GRCh38");
        gnomad.setTabixExecutable(script.toString()); gnomad.setTimeout(Duration.ofSeconds(5));
        gnomad.setRemoteTotalTimeout(Duration.ofSeconds(5)); gnomad.setRemoteMaxAttempts(1); gnomad.setParallelism(1);
        gnomad.setLocalPopulationInProcessEnabled(false); gnomad.setAllowFullVcfFallback(false); gnomad.setAllowVcfInfoFallback(false);
        if ("exomes".equals(dataset)) {
            gnomad.setBa1LocalExomesEnabled(true);
            Path data = temporary.resolve("gnomad.exomes.v4.1.sites.chrY.vcf.bgz");
            Files.writeString(data, "SYNTHETIC script transport only"); Files.writeString(Path.of(data + ".tbi"), "SYNTHETIC index");
            gnomad.setGrch38Files(data.toString());
        }
        return settings;
    }

    /** Real indexed REF lookup over 45 synthetic bytes; only assembly readiness is a declared engineering seam. */
    private NativeSnvSourceProducer tinyReference() throws Exception {
        Path fasta = temporary.resolve("SYNTHETIC-routed.fa"), index = Path.of(fasta + ".fai");
        Files.writeString(fasta, ">chr11\nATCG\n>chr13\nAACT\n>chrX\nATCG\n>chrY\nATCG\n");
        Files.writeString(index, "chr11\t4\t7\t4\t5\nchr13\t4\t19\t4\t5\nchrX\t4\t30\t4\t5\nchrY\t4\t41\t4\t5\n");
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
                        fasta.getFileName().toString(), sha, true, true, 4, "CHR_PREFIXED", List.of(), List.of("SYNTHETIC_TEST_READINESS"));
            }
        };
        return new NativeSnvSourceProducer(base.mapper, settings, temporary, base.fixture.sources, ledger.reference("checksum"), validator);
    }

}
