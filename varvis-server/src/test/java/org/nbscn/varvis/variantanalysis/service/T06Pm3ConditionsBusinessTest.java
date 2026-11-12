package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.controller.PublicLiteratureController;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Normal CURRENT18/PM3 queue/Python/capture/store paths, never a substituted Source or worker.
 * Ranking, reference, source and seeded cache transport are explicit SYNTHETIC TEST ONLY.
 * Source conditions and biological facts are qualified by the real pipeline. Writer exit precedes readonly Reader launch. */
class T06Pm3ConditionsBusinessTest {
    public static final class Directory implements org.junit.jupiter.api.io.TempDirFactory {
        @Override public Path createTempDirectory(org.junit.jupiter.api.extension.AnnotatedElementContext element,
                org.junit.jupiter.api.extension.ExtensionContext context) throws Exception {
            return newRoot();
        }
        static Path newRoot() throws Exception {
            String configured = System.getProperty("varvis.t06.testCheckout", "");
            Path checkout = configured.isBlank() ? Path.of("").toAbsolutePath().normalize().getParent()
                    : Path.of(configured).toAbsolutePath().normalize();
            if (checkout == null || !Files.isRegularFile(checkout.resolve("AGENTS.md"))) throw new IllegalStateException("T06_CHECKOUT_REQUIRED");
            Path root = Files.createDirectories(checkout.resolve("local/temp")).toRealPath();
            if (!root.startsWith(checkout.toRealPath())) throw new IllegalStateException("T06_TEMP_OUTSIDE_CHECKOUT");
            for (int i = 0; i < 100; i++) {
                try { return Files.createDirectory(root.resolve(java.util.UUID.randomUUID().toString().substring(0, 6))).toRealPath(); }
                catch (java.nio.file.FileAlreadyExistsException collision) { /* Only this disposable label is short. */ }
            }
            throw new IllegalStateException("T06_TEMP_COLLISION_LIMIT");
        }
    }
    @TempDir(factory = Directory.class, cleanup = CleanupMode.NEVER) Path temporary;
    private Bs1KnowledgeBusinessTest populationFixture;
    private AcmgBusinessIntegrationTest base;
    private AcmgBusinessService population;
    private AcmgBusinessWorkflows workflows;
    private PublicLiteratureController controller;
    private Pm3LiteratureRaritySupply.Factory populationRarity;
    private Pvs1UnifiedEighteenFixture pvs1;
    private UnifiedEighteenCompanionFixtures companions;
    private Path python, scripts, workflow;
    private String candidateId, selectionPin;
    private String scenario="GENERAL_HOMO";
    private String background="HBB";
    private Path resourceRoot;

    @AfterEach void close() {
        if (controller != null) controller.close();
        if (populationFixture != null) populationFixture.close();
    }

    private void prepare() throws Exception {
        String setting = System.getProperty("varvis.pp1.python", System.getProperty("denovo.test.python", ""));
        Assumptions.assumeFalse(setting.isBlank(), "Existing Python must be explicit; skipped is not validation");
        python = Path.of(setting); Assumptions.assumeTrue(Files.isRegularFile(python));
        scripts = Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")).toAbsolutePath().normalize();
        populationFixture = new Bs1KnowledgeBusinessTest(); populationFixture.temporary = temporary; populationFixture.setup();
        base = populationFixture.local.fixture.base;
        String sourceRoot=System.getProperty("t06.pm3.sourceRoot", "");
        if(sourceRoot.isBlank())throw new IllegalArgumentException("T06_EXPLICIT_PINNED_SOURCE_ROOT_REQUIRED");
        resourceRoot=Path.of(sourceRoot).toAbsolutePath().normalize();
        if (background.equals("CFTR")) {
            Path context=resourceRoot.resolve("local/resources/acmg-context/e4-a1a-2026-09-05");
            base.fixture.resolver=PatientDiseaseContextBindingResolver.load(base.mapper,context.resolve("manifest.json"),
                    context.resolve("mane-crosswalk.json"),context.resolve("clingen-catalog.json"));
            publishCftr();
        }
        population = base.service;
        pvs1 = new Pvs1UnifiedEighteenFixture(base.mapper, temporary.resolve("pvs1"), "NMD");
        companions = new UnifiedEighteenCompanionFixtures(base.mapper, temporary.resolve("companions"), pvs1);
        bootstrap();
        Path options = temporary.resolve("cold-options.json");
        Files.write(options, base.mapper.writeValueAsBytes(population.inputOptions(base.analysisId, 1)));
        seed("COLD", options); install();
        controller = new PublicLiteratureController(workflows, workflow.toString(),populationRarity);
        base.http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(workflows), controller).build();
        var response = base.http.perform(get("/variant-analyses/{id}/acmg/literature/pm3/options", base.analysisId).param("topN", "1"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        var source = base.mapper.readTree(response.getContentAsByteArray());
        selectionPin = source.path("selectionSha256").asText(); candidateId = source.at("/selection/candidates/0/candidateId").asText();
        assertTrue(selectionPin.matches("[a-f0-9]{64}")); assertFalse(candidateId.isBlank());
    }

    private void publishCftr() throws Exception {
        // These are synthetic ranking/sequence/annotation inputs, never an actual
        // CFTR variant or a substitute for the PM3/PM2 scientific authorities.
        var cftr = new TranscriptConsequence("CFTR", "1080", "NM_000492.4", "NM_000492.4:c.20A>G",
                "NP_000483.3:p.Glu7Gly", "missense_variant")
                .withAnnotationMetadata("VEP", "COMPLETED", "116.0", java.time.Instant.parse("2026-09-01T00:00:00Z"));
        base.expectedPublishedCount=1;
        base.variantTsvOverride=org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv("1\t7\t20\tA\tG\tCFTR\t1080\tAR\t0.8");
        base.syntheticPublicationEnrichment=rows->rows.forEach(row->row.applyTranscriptAnnotations(List.of(cftr)));
        base.publish(false,GenomeAssembly.GRCh38,org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.vcf("chr7\t20\tSYNTHETIC_T06\tA\tG\t.\tPASS\t.\tGT\t."));
        var snapshot=populationFixture.local.fixture.syntheticCatalog("T06_SYNTHETIC_CFTR_RANKING");
        var mapping=snapshot.withArray("/geneMappings").addObject().put("hgncId","HGNC:1884");
        mapping.putArray("geneSymbols").add("CFTR");mapping.putArray("ensemblGeneIds").add(cftr.getGeneId());
        snapshot.putObject("geneMappingSource").put("status","COMPLETE").put("rawSha256","b".repeat(64))
                .put("url","https://example.invalid/synthetic-ranking-mapping").put("recordCount",1);
        populationFixture.local.fixture.configureCatalog(new Ba1SpecificationCatalog(base.mapper,snapshot));
        var knowledge=new Bs1KnowledgeCatalogTest();
        byte[] rows=base.mapper.writeValueAsBytes(knowledge.bundle(knowledge.record("HGNC:1884","MONDO:0009061","AUTOSOMAL_RECESSIVE")));
        Path file=temporary.resolve("cftr-ranking-context.json");Files.write(file,rows);
        populationFixture.catalog=Bs1KnowledgeCatalog.load(file,Bs1KnowledgeCatalog.sha(rows));populationFixture.wire();
    }

    private void installCftrReference() throws Exception {
        Path local=temporary.resolve("local");var source=base.mapper.readTree(Files.readAllBytes(local.resolve("receipt.json")));
        var actual=source.at("/records/reference/actualChecksumReceipt").deepCopy();
        assertTrue(actual.isObject(),source.toPrettyString());((ObjectNode)actual).put("logicalName","reference.fa");
        var envelope=base.mapper.createObjectNode().put("source","T06_SYNTHETIC_REFERENCE").put("version","1").put("dataClass","SYNTHETIC");
        envelope.putObject("records").putObject("checksum").set("actualChecksumReceipt",actual);
        var ledger=ExplicitContextSourceLedger.create(base.mapper,envelope);
        var settings=new VariantAnalysisProperties();var normalization=settings.getNormalization();
        normalization.setGrch38Reference(local.resolve("reference.fa").toString());
        normalization.setGrch38ReferenceSha256(actual.path("observedSha256").asText());normalization.setGrch38ProfileId("T06_SYNTHETIC_REFERENCE");
        // Explicit tiny-reference resource-readiness fixture, matching the
        // existing test pattern. Indexed bases/hash/REF are actually checked;
        // no PM3/PM2 qualification or scoring result is substituted.
        var validator=new NormalizationResourceValidator(settings) {
            @Override public NormalizationReferenceReadiness validate(GenomeAssembly assembly) {
                return new NormalizationReferenceReadiness(assembly,"READY","T06_SYNTHETIC_REFERENCE","reference.fa",
                        actual.path("observedSha256").asText(),true,true,1,"CHR_PREFIXED",List.of(),List.of("SYNTHETIC_RESOURCE_READINESS_ONLY"));
            }
        };
        base.producer=new NativeSnvSourceProducer(base.mapper,settings,local,List.of(ledger),ledger.reference("checksum"),validator);
        populationFixture.local.fixture.wire();populationFixture.wire();population=base.service;
    }

    private Pm3LiteratureRaritySupply.Factory populationFactory(VariantAnalysisProperties settings) throws Exception {
        String prefix="variant-analysis.acmg-business.";
        Path adoption=resourceRoot.resolve("local/private/config/local-computational-batch1/pm2-local-adoption.json");
        assertEquals("85ad9333d569c5441f8a8b6cde5d4773273f36aa3ffd1e7c7e32c8393ab48bff",GnomadLocalAllSites.sha(adoption));
        var env=new org.springframework.mock.env.MockEnvironment().withProperty(prefix+"local-batch-workflow-enabled","true")
                .withProperty(prefix+"local-batch-pm2-adoption-file",Objects.requireNonNull(adoption.toString()))
                .withProperty(prefix+"local-batch-pm2-adoption-sha256",Objects.requireNonNull(GnomadLocalAllSites.sha(adoption)))
                .withProperty("variant-analysis.gnomad.tabix-executable",Objects.requireNonNull(settings.getGnomad().getTabixExecutable()))
                .withProperty("variant-analysis.gnomad.timeout","30s")
                .withProperty("variant-analysis.gnomad.grch38-files",Objects.requireNonNull(String.join(",",settings.getGnomad().getGrch38Files().stream().map(path->Objects.requireNonNull(path).toString()).toList())));
        if(background.equals("CFTR")) {
            var fixture=base.mapper.readTree(Files.readAllBytes(temporary.resolve("local/t06-pm3-fixture.json")));
            Path data=temporary.resolve("synthetic-all-sites.tsv.bgz"),index=temporary.resolve("synthetic-all-sites.index"),manifest=temporary.resolve("synthetic-all-sites.json");
            String header="locus\tAN\t"+String.join("\t",GnomadLocalAllSites.FLAGS)+"\n";var rows=new StringBuilder(header);
            for(String key:List.of("allele","partnerAllele")) {
                var allele=fixture.path(key);rows.append("chr").append(allele.path("contig").asText()).append(":").append(allele.path("position").asLong())
                        .append("\t2002\tfalse\tfalse\tfalse\tfalse\n");
            }
            try(var output=new htsjdk.samtools.util.BlockCompressedOutputStream(data.toFile())) {
                output.write(rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            GnomadLocalAllSites.build(base.mapper,data,index,manifest,GnomadLocalAllSites.sha(data));
            env.withProperty(prefix+"local-batch-pm2-all-sites-file",Objects.requireNonNull(manifest.toString()))
                    .withProperty(prefix+"local-batch-pm2-all-sites-sha256",Objects.requireNonNull(GnomadLocalAllSites.sha(manifest)))
                    .withProperty(prefix+"local-batch-pm2-general-absence-enabled","true");
        }
        var factory=new AcmgBusinessConfig().pm3LiteraturePopulation(base.mapper,env);var identity=factory.identity();
        assertEquals(adoption.toString(),identity.path("local-batch-pm2-adoption-file").asText());
        assertEquals(env.getProperty(prefix+"local-batch-pm2-adoption-sha256"),identity.path("local-batch-pm2-adoption-sha256").asText());
        if(background.equals("CFTR"))assertEquals("true",identity.path("local-batch-pm2-general-absence-enabled").asText());
        Files.write(temporary.resolve("population-factory-identity.json"),base.mapper.writeValueAsBytes(identity));return factory;
    }

    private void bootstrap() throws Exception {
        Path prepared = companions.prepareEmptyLiteratureWorkflow(scripts, python).getParent().getParent();
        // Production files only; no scientific fixtures or source observations are copied.
        try (var paths = Files.walk(prepared.resolve("scripts"))) {
            for (Path from : paths.filter(Files::isRegularFile).toList()) {
                Path to = temporary.resolve("scripts").resolve(prepared.resolve("scripts").relativize(from));
                Files.createDirectories(to.getParent()); Files.copy(from, to);
            }
        }
        for (String relative : List.of("ps1_pm5/prepare_reference_evidence.py", "ps1_pm5/prepare_expert_reference_evidence.py", "ps1_pm5/pp2_relationship_material.py",
                "pvs1/prepare_applicability_sources.py", "pvs1/assemble_materials.py", "pvs1/prepare_g2p_mechanisms.py",
                "e4_a1a/prepare_binding_resources.py", "acmg-generalization/import_cspec_catalog.py")) {
            Path to = temporary.resolve("scripts").resolve(relative); Files.createDirectories(to.getParent());
            Files.copy(scripts.getParent().resolve(relative), to);
        }
        // Preserve the existing catalog parser's complete local import bundle;
        // this is production source reuse, with no replacement parser/approval.
        try(var files=Files.list(scripts.getParent().resolve("acmg-generalization"))) {
            for(Path from:files.filter(p->Files.isRegularFile(p) && p.toString().endsWith(".py")).toList())
                Files.copy(from,temporary.resolve("scripts/acmg-generalization").resolve(from.getFileName()),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.copy(scripts.resolve("core_review_replay_profiles.json"),temporary.resolve("scripts/denovo/core_review_replay_profiles.json"));
        Path dictionary = temporary.resolve("docs/design/pp1/field-dictionary.json"); Files.createDirectories(dictionary.getParent());
        Files.copy(prepared.resolve("docs/design/pp1/field-dictionary.json"), dictionary);
        Path local = Files.createDirectories(temporary.resolve("local"));
        Files.copy(prepared.resolve("local/python.cmd"), local.resolve("python.cmd"));
        var config = base.mapper.createObjectNode().put("schemaVersion", "denovo-public-literature-workflow-v1")
                .put("enabled", true).put("projectRoot", temporary.toString()).put("work", "local/w")
                .put("library", "local/library").put("modelConfig", "local/model.json").put("modelBudget", "local/budget")
                .put("searchCache", "local/search").put("python", "local/python.cmd")
                .put("allowModel", false).put("allowPublicNetwork", false).put("pm3NeedDrivenReading", true)
                .put("pm3MaximumReadingRounds", 4);
        workflow = local.resolve("w.json"); Files.write(workflow, base.mapper.writeValueAsBytes(config));
    }

    private void install() throws Exception {
        var c = companions;
        if(background.equals("CFTR"))installCftrReference();
        var populationSettings = populationFixture.local.fixture.populationSettings(temporary.resolve("synthetic-tabix.cmd").toString());
        populationSettings.getGnomad().setBa1LocalExomesEnabled(true);
        populationSettings.getGnomad().setGrch38Files(String.join(",", (background.equals("CFTR")?List.of("7"):List.of("11", "13")).stream()
                .map(contig -> temporary.resolve("gnomad.exomes.v4.1.sites.chr" + contig + ".vcf.bgz").toString()).toList()));
        if(background.equals("CFTR")) {
            Path vcf=temporary.resolve("gnomad.exomes.v4.1.sites.chr7.vcf.bgz");
            Files.writeString(vcf,"SYNTHETIC TRANSPORT ONLY; this is not gnomAD source data");
            Files.writeString(Path.of(vcf+".tbi"),"SYNTHETIC TRANSPORT INDEX ONLY");
        }
        var local = new VariantAnalysisProperties();
        local.getClinVar().setEnabled(true); local.getClinVar().setGrch38File(temporary.resolve("missing-clinvar.vcf.gz").toString());
        local.getSpliceAi().setEnabled(true); local.getSpliceAi().setGrch38File(temporary.resolve("missing-spliceai.vcf.gz").toString());
        local.getAlphaMissense().setEnabled(true); local.getAlphaMissense().setGrch38File(temporary.resolve("missing-alphamissense.tsv.gz").toString());
        populationRarity = populationFactory(populationSettings);
        var batch = AcmgBusinessService.localBatchWithEffectIdentity(population, (effects, pin) -> {
            try {
                return new LocalEvidenceBatchRuntime(base.mapper, base.mapper.createObjectNode().put("dataClass", "SYNTHETIC_TEST_ONLY")
                        .put("purpose", "T06_CURRENT18_REAL_PM3_WORKER"), populationRarity::create, c.spliceFactory(local),
                        c.predictionFactory(local, effects), c.reference(local), c.bp7(), c::pm4, pvs1::selectedBatch, c::bp3,
                        c::pm1, () -> c.pp2(false), () -> c.bp1(effects, pin), false, c.deNovo(), c.pm3(workflow, populationRarity), c.pp1(workflow));
            } catch (Exception invalid) { throw new IllegalArgumentException("T06_PM3_COMPANION_CONFIGURATION_FAILED", invalid); }
        }, temporary.resolve("reviews"));
        base.service = AcmgBusinessService.unifiedLocal(population, batch); workflows = new AcmgBusinessWorkflows(base.service, batch);
        var options = workflows.inputOptions(base.analysisId, 1, "CURRENT");
        assertEquals(18, options.path("enabledCriteria").size());
        base.request.put("topN", 1).put("declarationVersion", options.path("requiredDeclaration").asText());
        var row = options.at("/candidates/0"); var rec = row.path("recommendation"); JsonNode relation = null, annotation = null;
        for (var value : rec.path("relationships")) if (value.path("selectable").asBoolean() && "AR".equals(value.path("moi").asText())) { relation = value; break; }
        for (var value : rec.path("annotations")) if (value.path("contextSelectable").asBoolean()) { annotation = value; break; }
        assertNotNull(relation, rec.toPrettyString()); assertNotNull(annotation, rec.toPrettyString());
        var choice = base.request.putArray("recommendations").addObject();
        for (String key : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) choice.set(key, rec.path(key));
        choice.set("stableRelationshipKey", relation.path("stableRelationshipKey")); choice.set("annotationId", annotation.path("annotationId"));
        var source = base.mapper.createObjectNode().put("source", "EXPLICIT_USER_DECLARATION").put("version", "1.0.0")
                .put("recordLocator", "recommendations/" + rec.path("recommendationId").asText() + "/selection");
        var hypothesis = base.request.putArray("hypotheses").addObject().put("affectedStatus", "UNKNOWN"); var h = hypothesis.putObject("hypothesis");
        h.set("candidateId", row.path("candidateId")); h.set("hypothesisId", rec.path("hypothesisId")); h.set("provenance", source);
        for (String key : List.of("hgncId", "mondoId", "moi")) h.set(key, relation.path(key));
        String tx = annotation.path("versionedTranscriptId").asText(); int dot = tx.lastIndexOf('.');
        var t = hypothesis.putObject("transcript").put("accession", tx.substring(0, dot)).put("version", Integer.parseInt(tx.substring(dot + 1)))
                .put("selectionPolicy", rec.path("policyId").asText() + "@" + rec.path("policyVersion").asText())
                .put("selectionReason", "SYNTHETIC explicit CURRENT source-chain test; missing biological facts stay missing");
        t.set("annotationSource", annotation.path("annotationSource")); t.set("annotationSourceVersion", annotation.path("annotationSourceVersion")); t.set("source", source);
    }

    private JsonNode seed(String phase, Path input) throws Exception {
        var child = new SystemExternalProcessRunner().run(List.of(python.toString(), "-E", "-s", "-B",
                scripts.resolve("test_t06_pm3_business_fixture.py").toString(), temporary.toString(), input.toString(), phase, scenario, resourceRoot.toString()), scripts, Duration.ofSeconds(60));
        assertTrue(child.isSuccess(), child.getOutput());
        return base.mapper.readTree(Files.readAllBytes(temporary.resolve("local/t06-pm3-fixture.json")));
    }

    private ObjectNode postJson(String path, ObjectNode body) throws Exception {
        var response = base.http.perform(post(Objects.requireNonNull(path)).contentType("application/json")
                .content(Objects.requireNonNull(base.mapper.writeValueAsBytes(body)))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getErrorMessage() + " " + response.getContentAsString());
        return (ObjectNode) base.mapper.readTree(response.getContentAsByteArray());
    }
    private ObjectNode task(String id) throws Exception {
        var response = base.http.perform(get("/variant-analyses/{id}/acmg/literature/pm3/tasks/{task}", base.analysisId, id)).andReturn().getResponse();
        assertEquals(200, response.getStatus()); return (ObjectNode) base.mapper.readTree(response.getContentAsByteArray());
    }
    private ObjectNode await(String id) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            var result = task(id);
            if (!Set.of("QUEUED", "RUNNING").contains(result.path("status").asText())) {
                assertEquals("READY", result.path("status").asText(), result.toPrettyString()); return result;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Actual PM3 Python task did not finish within the test bound");
    }
    private Path taskFolder(String id) { return temporary.resolve("local/w/pm3-paper-tasks").resolve(AcmgBusinessStore.sha(base.analysisId)).resolve(id); }
    private JsonNode pm3(ObjectNode saved) {
        for (var row : saved.at("/candidates/0/attempts/0/localBatch/criteria")) if (Pm3SelectedBatch.CRITERION.equals(row.path("criterionId").asText())) return row;
        throw new AssertionError("Actual PM3 row missing: " + saved.toPrettyString());
    }
    private String queries() throws Exception { Path p = temporary.resolve("queries.txt"); return Files.exists(p) ? Files.readString(p) : ""; }


    private void verifyNativeFrequency(JsonNode task) throws Exception {
        if(!background.equals("CFTR"))return;
        int checked=0;var factoryIdentity=populationRarity.identity();
        for(var caseRow:task.at("/result/observations/cases"))for(var row:caseRow.path("resolvedObservations")) {
            boolean homo="REPORTED_HOMOZYGOUS".equals(row.at("/reportedObservation/kind").asText());
            for(String role:homo?List.of("target"):List.of("target","partner")) {
                var q=row.path("rarityQualification").path(role);var query=q.path("populationQuery");
                assertEquals("VERIFIED",q.path("qualification").asText(),q.toPrettyString());
                assertEquals("PRIVACY_BOUNDED_RUNTIME",query.at("/context/dataClass").asText());
                assertEquals("HGNC:1884",query.at("/context/gene/hgncId").asText());
                assertEquals("MONDO:0009061",query.at("/context/disease/mondoId").asText());
                assertEquals("AUTOSOMAL_RECESSIVE",query.at("/context/moi").asText());
                assertEquals("PROJECT_LOCAL_EXOMES_ABSENCE_V1",query.at("/policySelection/policy/policyId").asText());
                assertEquals("NO_RECORD",query.at("/observation/carrier/queryOutcome/status").asText());
                assertEquals("SUCCEEDED",query.at("/observation/carrier/resourceExecution/status").asText());
                assertEquals(0,query.at("/observation/carrier/queryOutcome/recordCount").asInt(-1));
                assertTrue(query.at("/observation/carrier/measurements").isEmpty());
                var detection=query.path("allSitesObservation");assertEquals("HIT",detection.path("status").asText());
                assertEquals("2002",detection.path("adjAn").asText());
                assertEquals(factoryIdentity.path("local-batch-pm2-all-sites-sha256").asText(),detection.path("manifestSha256").asText());
                assertEquals(q.path("queryAllele"),detection.path("queryAllele"));
                for(String flag:GnomadLocalAllSites.FLAGS)assertEquals(base.mapper.getNodeFactory().booleanNode(false),detection.path("regionFlags").path(flag));
                assertEquals("MET",query.at("/evaluation/evaluationResult/status").asText());
                assertEquals("SUPPORTING",query.at("/evaluation/appliedStrength").asText());checked++;
            }
        }
        assertTrue(checked>0,"Actual native source qualifications must be present");
        var receipt=base.mapper.createObjectNode().put("checkedNativeQualifications",checked).put("policyId","PROJECT_LOCAL_EXOMES_ABSENCE_V1")
                .put("queryOutcome","NO_RECORD").put("allSitesAdjAN","2002").put("fourRegionFlags",false)
                .put("allSitesManifestSha256",factoryIdentity.path("local-batch-pm2-all-sites-sha256").asText())
                .put("sourceClass","SYNTHETIC_CASE_AND_POPULATION_WITH_PINNED_REAL_BACKGROUND").put("clinicalAdoption",false);
        Files.write(temporary.resolve("native-facts-check.json"),base.mapper.writeValueAsBytes(receipt));
    }

    private void executeAndSave() throws Exception {
        prepare();var source=base.mapper.readTree(Files.readAllBytes(temporary.resolve("local/t06-pm3-fixture.json")));
        var request=base.mapper.createObjectNode().put("topN",1).put("candidateId",candidateId).put("selectionSha256",selectionPin)
                .put("pmcid",source.path("pmcid").asText()).put("bundleSha256",source.path("bundleSha256").asText()).put("confirmPublicModel",true);
        String endpoint="/variant-analyses/"+base.analysisId+"/acmg/literature/pm3/tasks";
        String parentId=postJson(endpoint,request).path("taskId").asText();await(parentId);
        Path parentRequest=taskFolder(parentId).resolve("request.json");seed("WARM",parentRequest);
        var continuation=base.mapper.createObjectNode().put("topN",1).put("selectionSha256",selectionPin).put("confirmPublicModel",true);
        String childId=postJson(endpoint+"/"+parentId+"/continue",continuation).path("taskId").asText();var child=await(childId);
        assertEquals(0,child.at("/result/modelCalls").asInt(-1));
        assertFalse(child.path("canContinueProcessing").asBoolean(),child.toPrettyString());
        Files.write(temporary.resolve("actual-task.json"),base.mapper.writeValueAsBytes(child));
        verifyNativeFrequency(child);
        var manifest=base.mapper.createObjectNode().put("scenario",scenario).put("writerPid",ProcessHandle.current().pid());
        var savedRuns=manifest.putArray("runs");var artifacts=base.mapper.createObjectNode();var results=artifacts.putObject("publicResults");
        byte[] budget=Files.readAllBytes(temporary.resolve("local/budget/budget.json"));
        JsonNode prior=null;
        for(String selection:List.of("single","related","all")) {
            var declaration=base.request.deepCopy();declaration.remove("selectedCriteria");
            if(!selection.equals("all")) {
                var selected=declaration.putArray("selectedCriteria").add(Pm3SelectedBatch.CRITERION);
                if(selection.equals("related"))selected.add("ACMG_AMP_2015:PS2").add("ACMG_AMP_2015:PM6");
            }
            var response=base.http.perform(post("/variant-analyses/{id}/acmg/runs",base.analysisId).param("workflow","CURRENT")
                    .contentType("application/json").content(Objects.requireNonNull(base.mapper.writeValueAsBytes(declaration)))).andReturn().getResponse();
            assertEquals(200,response.getStatus(),response.getContentAsString());
            var saved=(ObjectNode)base.mapper.readTree(response.getContentAsByteArray());
            assertNotEquals("FAILED",saved.path("status").asText());
            assertEquals(selection.equals("all")?18:selection.equals("related")?3:1,saved.path("enabledCriteria").size());
            var criterion=pm3(saved);boolean positive=!Set.of("PHASE_CONFLICT","APPROVAL_MISSING","HOMO_CONFLICT").contains(scenario);
            Files.write(temporary.resolve("actual-"+selection+"-criterion.json"),base.mapper.writeValueAsBytes(criterion));
            boolean nativeRarity=background.equals("CFTR");
            assertEquals(positive && nativeRarity?"MET":"NOT_EVALUATED",criterion.path("status").asText(),criterion.toPrettyString());
            assertEquals(positive && nativeRarity?(scenario.equals("GENERAL_HOMO")?"SUPPORTING":"MODERATE"):"NONE",criterion.path("appliedStrength").asText(),criterion.toPrettyString());
            assertEquals(positive && nativeRarity?(scenario.equals("GENERAL_HOMO")?2:4):0,
                    criterion.at("/details/pm3Facts/assessment/calculation/quarterPoints").asInt(-1),criterion.toPrettyString());
            if(selection.equals("all") && nativeRarity) {
                JsonNode pm2=null;for(var item:saved.at("/candidates/0/attempts/0/localBatch/criteria"))
                    if("ACMG_AMP_2015:PM2".equals(item.path("criterionId").asText()))pm2=item;
                assertNotNull(pm2);assertEquals("MET",pm2.path("status").asText());assertEquals("SUPPORTING",pm2.path("appliedStrength").asText());
            }
            if(!nativeRarity)assertTrue(criterion.toString().contains("PM2_SPECIALIST_POLICY_REVIEW_REQUIRED"),"Do not bypass the real missing PM2 policy");
            var facts=criterion.at("/details/pm3Facts/caseFacts");assertFalse(facts.isEmpty());
            String expectedCondition=Set.of("PHASE_CONFLICT","HOMO_CONFLICT").contains(scenario)?"CONFLICT":scenario.equals("APPROVAL_MISSING")?"NOT_QUALIFIED":"QUALIFIED";
            boolean foundCondition=false;for(var fact:facts)if(expectedCondition.equals(fact.at("/publishedConditions/status").asText()))foundCondition=true;
            assertTrue(foundCondition,"Expected actual source-qualified condition status "+expectedCondition+": "+criterion.toPrettyString());
            var science=base.mapper.createObjectNode();for(String key:List.of("status","appliedStrength","reasonCode"))science.set(key,criterion.path(key));
            if(prior==null)prior=science;else assertEquals(prior,science);
            String queries=queries();var record=base.store.run(base.analysisId,saved.path("runId").asText()).orElseThrow();
            assertEquals(saved,base.postRequest(declaration));assertEquals(saved,base.service.read(base.analysisId,record.id()));
            assertEquals(queries,queries());assertArrayEquals(budget,Files.readAllBytes(temporary.resolve("local/budget/budget.json")));
            Path out=Files.createDirectories(temporary.resolve("reopen").resolve(selection));
            Files.writeString(out.resolve("expected-public.json"),Objects.requireNonNull(record.responseJson()));
            Files.writeString(out.resolve("expected-private.json"),Objects.requireNonNull(record.privateJson()));
            Files.writeString(out.resolve("expected-request.json"),Objects.requireNonNull(record.requestJson()));
            var executionSource=base.store.source(base.analysisId).orElseThrow();
            Files.writeString(out.resolve("expected-source.json"),executionSource.json());
            manifest.put("expectedSourceSha256",executionSource.sha256());savedRuns.addObject().put("name",selection).put("runId",record.id());results.set(selection,saved);
        }
        Path out=Files.createDirectories(temporary.resolve("reopen"));
        Files.write(out.resolve("manifest.json"),base.mapper.writeValueAsBytes(manifest));
        Files.write(temporary.resolve("actual-current18.json"),base.mapper.writeValueAsBytes(artifacts));
        var measuredSeed=base.mapper.readTree(Files.readAllBytes(temporary.resolve("local/t06-pm3-fixture.json")));
        var receipt=base.mapper.createObjectNode().put("analysisId",base.analysisId).put("scenario",scenario).put("writerPid",ProcessHandle.current().pid())
                .put("externalModelProviderCalls","NOT_MEASURED").put("publicNetworkRequests","NOT_MEASURED")
                .put("workerReportedModelCalls",child.at("/result/modelCalls").asInt(-1))
                .put("syntheticSeedTransportCalls",measuredSeed.path("syntheticTransportCalls").asInt(-1))
                .put("populationQueryLogLines",queries().lines().filter(line->!line.isBlank()).count())
                .put("fixturePublicNetworkEnabled",base.mapper.readTree(Files.readAllBytes(workflow)).path("allowPublicNetwork").asBoolean())
                .put("clinicalAdoption",false).put("savedSelections",3)
                .put("nativeRaritySupplied",background.equals("CFTR")).put("background",background)
                .put("closure","NORMAL_SOURCE_AND_PERSISTENCE_CHECK_NOT_T06_COMPLETE");
        Files.write(temporary.resolve("writer-receipt.json"),base.mapper.writeValueAsBytes(receipt));
        base.closeDatabase();
    }

    public static final class Writer {
        public static void main(String[] args) throws Exception {
            var test=new T06Pm3ConditionsBusinessTest();test.temporary=Path.of(args[0]);test.scenario=args[1];test.background=args[2];
            try {test.executeAndSave();}finally{test.close();}
        }
    }

    @Test void optionalExistingTestCheckoutRetainsRealpathAndAgentBoundary() throws Exception {
        String key="varvis.t06.testCheckout",previous=System.getProperty(key);
        Path checkout=Objects.requireNonNull(Path.of("").toAbsolutePath().normalize().getParent());
        try {
            System.clearProperty(key);
            assertEquals(checkout.resolve("local/temp").toRealPath(),Directory.newRoot().getParent());
            System.setProperty(key,checkout.toString());
            assertEquals(checkout.resolve("local/temp").toRealPath(),Directory.newRoot().getParent());
            System.setProperty(key,checkout.resolve("local").toString());
            assertThrows(IllegalStateException.class,Directory::newRoot,"An existing directory without AGENTS must be rejected before creating anything");
        } finally {
            if(previous==null)System.clearProperty(key);else System.setProperty(key,previous);
        }
    }

    @Test void normalSourceConditionsAndCurrentSelectionsSurviveWriterExitAndAnotherReadonlyJvm() throws Exception {
        String setting=System.getProperty("denovo.test.python","");
        Assumptions.assumeFalse(setting.isBlank(),"Explicit existing Python required");
        String executable=Path.of(System.getProperty("java.home"),"bin/java.exe").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var runner=new SystemExternalProcessRunner();
        for(String requested:System.getProperty("t06.pm3.business.scenarios","GENERAL_HOMO,PHASE_TRANS,APPROVED_TRANS,PHASE_CONFLICT,APPROVAL_MISSING,HOMO_CONFLICT").split(",")) {
            String[] parts=requested.split(":",-1);assertTrue(parts.length==1 || parts.length==2);
            String name=parts[parts.length-1],background=parts.length==2?parts[0]:System.getProperty("t06.pm3.background","HBB");
            assertTrue(Set.of("CFTR","HBB").contains(background));
            // Keep full immutable task hash paths within the Windows file API
            // bound; scenario names belong in the manifest, not the root path.
            Path run=Directory.newRoot();
            Files.writeString(temporary.resolve("last-scenario-root.txt"),name+"\n"+run);
            var writer=runner.run(List.of(executable,"-Ddenovo.test.python="+setting,
                    "-Ddenovo.test.scripts="+System.getProperty("denovo.test.scripts"),"-Dt06.pm3.sourceRoot="+System.getProperty("t06.pm3.sourceRoot",""),"-cp",classpath,Writer.class.getName(),run.toString(),name,background),
                    Path.of("").toAbsolutePath(),Duration.ofSeconds(180));
            Files.writeString(run.resolve("writer-process.log"),writer.getOutput());assertTrue(writer.isSuccess(),writer.getOutput());
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var receipt=mapper.readTree(Files.readAllBytes(run.resolve("writer-receipt.json")));
            long writerPid=receipt.path("writerPid").asLong();assertFalse(ProcessHandle.of(writerPid).map(h->Objects.requireNonNull(h).isAlive()).orElse(false));
            var reader=runner.run(List.of(executable,"-cp",classpath,DeNovoUnifiedEighteenReopenReader.class.getName(),run.toString(),
                    receipt.path("analysisId").asText(),run.resolve("reopen").toString()),Path.of("").toAbsolutePath(),Duration.ofSeconds(60));
            Files.writeString(run.resolve("reader-process.log"),reader.getOutput());assertTrue(reader.isSuccess(),reader.getOutput());
            var reopened=mapper.readTree(Files.readAllBytes(run.resolve("reopen/unified18-reopen-receipt.json")));
            assertTrue(reopened.path("passed").asBoolean());assertNotEquals(writerPid,reopened.path("processId").asLong());
            String output=System.getProperty("t06.pm3.business.output","");
            if(!output.isBlank()) {
                Path destination=Files.createDirectories(Path.of(output).resolve(background).resolve(name));
                try(var files=Files.walk(run)) {for(Path from:files.filter(Files::isRegularFile).toList()) {
                    Path to=destination.resolve(run.relativize(from));Files.createDirectories(to.getParent());Files.copy(from,to,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }}
            }
        }
    }
}
