package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.persistence.*;
import org.nbscn.varvis.variantanalysis.service.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real isolated file H2, repositories, controller, coordinator, bundle and comparator; SYNTHETIC science only. */
class RevelApplicationBusinessTest {
    @TempDir Path root;
    ReviewedClauseSourceFixtures x;
    ObjectMapper mapper;
    DriverManagerDataSource db;
    AcmgBusinessStore store;
    JdbcVariantAnalysisJobRepository jobs;
    AcmgExecutionSources sources;
    AcmgBusinessService service;
    MockMvc http;
    ObjectNode sourceRequest;
    ObjectNode source;
    ObjectNode adoption;
    RevelApplicationBusiness application;
    ReviewedPredictionBusinessRuntime runtime;
    NativeSnvSourceProducer producer;
    java.util.function.BiFunction<ReviewedPredictionBusinessRuntime.PreparedContext, VariantInterpretation, ReviewedPredictionBundle.ObservationProvider> syntheticObservations;
    AtomicInteger queries = new AtomicInteger();
    boolean approved;
    String failure;
    boolean failSave;
    String observationFailure;
    CountDownLatch queryStarted;
    CountDownLatch queryRelease;

    @BeforeEach void setup() throws Exception {
        x = new ReviewedClauseSourceFixtures(root.resolve("fixture"), false, "test-analysis"); mapper = x.f.mapper;
        // Same ordinary persistence mapper behavior as Spring's Jackson builder; request parsing remains strict.
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        db = new DriverManagerDataSource("jdbc:h2:file:" + root.resolve("isolated-db").toString().replace('\\','/') + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/variant-analysis-schema.sql"), new ClassPathResource("db/exomiser-analysis-schema.sql"), new ClassPathResource("db/acmg-business-schema.sql")).execute(db);
        store = new AcmgBusinessStore(db) {
            @Override public void finish(String id, String status, String response, String privateJson) {
                if (failSave) throw new IllegalStateException("TEST_ONLY_SAVE_FAILURE");
                super.finish(id, status, response, privateJson);
            }
        };
        var settings = new VariantAnalysisProperties(); settings.setStorageRoot(x.root);
        jobs = new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(Objects.requireNonNull(db)), new DataSourceTransactionManager(Objects.requireNonNull(db)), settings, mapper);
        for (var candidate : x.candidates) {
            ObjectNode tx = mapper.valueToTree(candidate.getTranscriptAnnotations().get(0)); tx.put("timestamp", "2026-09-19T00:00:00Z");
            candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(tx, TranscriptConsequence.class)));
        }
        var job = x.parsed.job(); job.applyEngineMetadata(x.parsed.execution().getMetadata()); job.transitionTo(VariantAnalysisStatus.READY); jobs.save(job); jobs.replaceInterpretations(job.getId(), x.candidates);
        sources = new AcmgExecutionSources(mapper, store); sources.publish(job, x.parsed.conversion(), x.parsed.execution(), x.candidates);
        producer = tinyReference();
        var bundleDocument = x.producer.document(x.policies.toArray(ObjectNode[]::new));
        for (var o : x.observations) bundleDocument.withObject("/materials/transcriptModels").set(x.f.hash.canonicalSha256(o.observation().path("transcriptModel")), o.observation().path("transcriptModel"));
        runtime = new ReviewedPredictionBusinessRuntime(mapper, x.producer.load(bundleDocument), mapper.createObjectNode().put("source", "TEST_ONLY"));
        ObjectNode binding=mapper.createObjectNode().put("testOnly",true).put("configurationSha256","1".repeat(64));
        var allowed=binding.putArray("allowedPolicySha256s");x.policies.forEach(p->allowed.add(x.f.hash.canonicalSha256(p)));
        syntheticObservations = (prepared, candidate) -> request -> {
            queries.incrementAndGet(); int i = x.candidates.get(0).getCandidateId().equals(candidate.getCandidateId()) ? 0 : 1;
            if (queryStarted != null && queryRelease != null) {
                queryStarted.countDown();
                try { if (!queryRelease.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_ONLY_WAIT_EXPIRED"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
            if ("unavailable".equals(observationFailure)) return null;
            var obs = x.observations.get(i).observation().deepCopy(); var context = (ObjectNode) request.snapshot().path("context"); obs.set("context", context);
            var fixture = new PredictionClauseRuntimeTest.Fixture(candidate, context, obs); x.revel.rebindEvidence(fixture);
            if ("no-record".equals(observationFailure)) {
                var atomic = obs.path("evidence");
                var carrier = mapper.createObjectNode().put("evidenceType", "COMPUTATIONAL_PREDICTION").put("producedAt", "2026-09-19T00:00:00Z");
                for (String field : List.of("source", "resourceExecution", "resourceAvailability", "resourceApplicability")) carrier.set(field, atomic.path(field).deepCopy());
                carrier.withObject("/source").putArray("sourceRecords");
                carrier.set("queryAllele", context.at("/variant/evidenceQueryAllele"));
                carrier.putObject("queryOutcome").put("status", "NO_RECORD").put("recordCount", 0)
                        .put("queryFingerprint", x.f.hash.canonicalSha256(context.at("/variant/evidenceQueryAllele")));
                carrier.putArray("measurements"); obs.putArray("rawRecords");
                carrier.put("sourcePayloadSha256", x.f.hash.canonicalSha256(obs.path("rawRecords")));
                obs.set("evidence", x.f.hash.adapt(context, carrier));
            }
            return obs;
        };
        application = new RevelApplicationBusiness(mapper, runtime, syntheticObservations, (runId, privateData) -> {
            if (!approved) throw new IllegalArgumentException("REVEL_APPLICATION_APPROVAL_MISSING");
            try { return approve(runId, privateData); }
            catch (Exception problem) { throw new AssertionError("TEST_ONLY approval fixture failed", problem); }
        }, binding);
        service = AcmgBusinessService.revelApplication(new AcmgBusinessService(mapper, jobs, store, sources,
                () -> new ExplicitCaseInputCoordinator(mapper, x.resolver, producer, List.of(checksum))), application,
                Files.createDirectory(root.resolve("functional-experiment-reviews")));
        http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
        sourceRequest = declaration(service.inputOptions("test-analysis", 2));
    }
    @AfterEach void close() { if (db != null) new JdbcTemplate(db).execute("SHUTDOWN"); }
    ExplicitContextSourceLedger checksum;
    NativeSnvSourceProducer tinyReference() throws Exception {
        Path fasta = root.resolve("tiny.fa"); Files.writeString(fasta, ">1\n" + "A".repeat(250) + "\n");
        Path index = Path.of(fasta + ".fai"); Files.writeString(index, "1\t250\t3\t250\t251\n");
        String sha = PredictionCalibration.sha256(Files.readAllBytes(fasta));
        var receipt = mapper.createObjectNode().put("resourceId", "TEST_ONLY_TINY_FASTA").put("resourceVersion", "1").put("assembly", "GRCh38")
                .put("logicalName", "tiny.fa").put("expectedSha256", sha).put("observedSha256", sha).put("hashStatus", "VERIFIED")
                .put("hashAlgorithm", "SHA-256").put("hashScope", "RAW_BYTES").put("unchangedDuringHash", true).put("byteLength", Files.size(fasta))
                .put("mtimeNs", Files.getLastModifiedTime(fasta).to(TimeUnit.NANOSECONDS)).put("startedAt", "2026-09-01T00:00:00Z")
                .put("completedAt", "2026-09-01T00:00:01Z").put("indexSha256", PredictionCalibration.sha256(Files.readAllBytes(index))).put("resourceDataClass", "SYNTHETIC");
        var envelope = mapper.createObjectNode().put("source", "TEST_ONLY_CHECKSUM").put("version", "1").put("dataClass", "SYNTHETIC");
        envelope.putObject("records").putObject("checksum").set("actualChecksumReceipt", receipt);
        byte[] bytes = mapper.writeValueAsBytes(envelope); checksum = ExplicitContextSourceLedger.verifiedBytes(mapper, bytes, PredictionCalibration.sha256(bytes));
        var props = new VariantAnalysisProperties(); props.getNormalization().setGrch38Reference(fasta.toString());
        props.getNormalization().setGrch38ReferenceSha256(sha); props.getNormalization().setGrch38ProfileId("TEST_ONLY_TINY");
        var validator = new NormalizationResourceValidator(props) {
            @Override public NormalizationReferenceReadiness validate(GenomeAssembly assembly) {
                return new NormalizationReferenceReadiness(assembly, "READY", "TEST_ONLY_TINY", "tiny.fa", sha, true, true, 1, "NO_CHR_PREFIX", List.of(), List.of("TEST_ONLY"));
            }
        };
        var constructor = NativeSnvSourceProducer.class.getDeclaredConstructor(ObjectMapper.class, VariantAnalysisProperties.class, Path.class, List.class, ExplicitContextSourceLedger.SourceRef.class, NormalizationResourceValidator.class);
        constructor.setAccessible(true);
        return constructor.newInstance(mapper, props, root, List.of(checksum), checksum.reference("checksum"), validator);
    }
    ObjectNode declaration(JsonNode options) {
        var body = mapper.createObjectNode().put("topN", 2).put("declarationVersion", RevelApplicationBusiness.DECLARATION)
                .put("sampleId", "synthetic-proband").put("dataClass", "SYNTHETIC");
        body.putObject("patient").put("patientContextId", "patient-context-test-only-bridge").put("role", "PROBAND").put("sex", "UNKNOWN")
                .putObject("source").put("source", "TEST_ONLY_DECLARATION").put("version", "1").put("recordLocator", "TEST_ONLY/patient");
        var hypotheses = body.putArray("hypotheses"); var refs = body.putArray("recommendations");
        for (int i = 0; i < 2; i++) {
            JsonNode row = options.path("candidates").get(i), rec = row.path("recommendation"), tx = rec.path("resourceCompatibleTranscript");
            assertTrue(tx.isObject(), rec.toPrettyString());
            JsonNode relation = rec.path("relationships").get(0);
            var h = hypotheses.addObject().put("affectedStatus", "AFFECTED");
            h.putObject("hypothesis").put("candidateId", row.path("candidateId").asText()).put("hypothesisId", rec.path("hypothesisId").asText())
                    .put("hgncId", relation.path("hgncId").asText()).put("mondoId", relation.path("mondoId").asText()).put("moi", relation.path("moi").asText())
                    .putObject("provenance").put("source", "TEST_ONLY_DECLARATION").put("version", "1").put("recordLocator", "TEST_ONLY/hypothesis/"+i);
            h.putObject("transcript").put("accession", "ENST00000"+(i+1)).put("version", 4)
                    .put("selectionPolicy", tx.path("predictionContextSelectionPolicy").asText()).put("annotationSource", "VEP_SYNTHETIC").put("annotationSourceVersion", "116.0")
                    .put("selectionReason", "TEST_ONLY explicit selected versioned native transcript, no clinical confirmation")
                    .putObject("source").put("source", "TEST_ONLY_DECLARATION").put("version", "1").put("recordLocator", "recommendations/"+rec.path("recommendationId").asText()+"/selection");
            var ref = refs.addObject(); for (String key : List.of("candidateId", "recommendationId", "policyVersion", "policyHash", "generatedAt")) ref.set(key, rec.path(key));
            ref.set("stableRelationshipKey", relation.path("stableRelationshipKey")); ref.set("annotationId", tx.path("annotationId"));
        }
        return body;
    }
    ObjectNode post(ObjectNode body) throws Exception {
        String text = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/variant-analyses/test-analysis/acmg/runs").contentType("application/json").content(Objects.requireNonNull(mapper.writeValueAsBytes(body))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return (ObjectNode) mapper.readTree(text);
    }
    void prepare() throws Exception {
        int before=queries.get();source = post(sourceRequest); assertEquals(before+2, queries.get(), source.toPrettyString());
        assertEquals(2, source.path("evaluatorCalls").asInt());
        adoption = mapper.createObjectNode().put("topN", 2).put("declarationVersion", RevelApplicationBusiness.DECLARATION).put("sampleId", "synthetic-proband").put("dataClass", "SYNTHETIC");
        adoption.putNull("patient"); adoption.putArray("hypotheses");
        adoption.putObject("revelApplication").put("operation", RevelApplicationBusiness.OPERATION).put("sourceRunId", source.path("runId").asText())
                .put("sourceRequestSha256", source.path("requestSha256").asText()).put("bindingSha256", application.identity().path("bindingSha256").asText());
    }

    RevelApplicationBusiness.Approval approve(String runId, JsonNode privateData) throws Exception {
        var saved = store.run("test-analysis", runId).orElseThrow();
        x.f.docs.put("run", (ObjectNode) mapper.readTree(saved.responseJson())); x.f.docs.put("request", (ObjectNode) mapper.readTree(saved.requestJson()));
        x.f.docs.put("private", (ObjectNode) privateData); x.f.docs.put("publication", (ObjectNode) mapper.readTree(store.source("test-analysis").orElseThrow().json()));
        x.f.settings.put("runId", runId); x.f.settings.putArray("candidates"); x.f.settings.putArray("clauseApplicationSidecars");
        x.f.settings.putObject("effectSnapshots");
        x.inputs.clear(); x.ledgers.clear(); x.ledgerFiles.clear(); x.reviews.clear(); x.pins.clear();
        for (JsonNode input : privateData.path("predictionContextInputs")) x.inputs.add(mapper.treeToValue(input, ExplicitEvidenceContextAssembler.ExplicitInput.class));
        for (JsonNode receipt : privateData.path("sourceReceipts")) {
            byte[] bytes = receipt.path("rawJson").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8); String sha = receipt.path("sha256").asText();
            Path file = root.resolve(sha+".json"); Files.write(file, bytes); x.ledgerFiles.put(sha, file); x.ledgers.add(ExplicitContextSourceLedger.verifiedBytes(mapper,bytes,sha));
        }
        for (int i=0;i<2;i++) {
            x.f.settings.withArray("candidates").addObject().put("candidateId", x.candidates.get(i).getCandidateId()).putArray("hypotheses");
            x.application(i,(ObjectNode)x.f.docs.get("run").at("/candidates/"+i+"/attempts/0/transcriptInput/predictionPreparation"));
        }
        for (String name : List.of("adoptions","modelProofs")) for (JsonNode value : x.f.settings.path(name)) ((ObjectNode)value).put("sourceRunId",runId);
        x.reseal(); x.reviewAll();
        for (var entry : new ArrayList<>(x.reviews.entrySet())) {
            ObjectNode review=(ObjectNode)mapper.readTree(entry.getValue().bytes()); review.put("runId",runId); x.putReview(entry.getKey(),review);
        }
        for(int i=0;i<2;i++) {
            var input=x.inputs.get(i); var key=new SavedPredictionContextBridge.Key(input.candidateId(),input.hypothesisId());
            var context=x.contexts.get(key);
            var reviewed=mapper.readTree(x.reviews.get(key).bytes());
            x.f.candidate(i).withArray("hypotheses").addObject().put("hypothesisId",input.hypothesisId()).put("verification","VERIFIED")
                    .put("reviewId",reviewed.path("reviewId").asText()).put("reviewerId",reviewed.path("reviewerId").asText()).put("sourceSha256",x.pins.get(key)).put("effectSnapshotId",input.candidateId()).set("context",context);
            ObjectNode snapshot=x.f.settings.withObject("/effectSnapshots").putObject(input.candidateId()).put("snapshotId",input.candidateId()).put("version","1")
                    .put("status","COMPLETE_FOR_DECLARED_SCOPE").put("reviewId","TEST_ONLY_LOCAL_EMPTY_STORE").put("externalWorldCovered",false)
                    .put("candidateId",input.candidateId()).put("hypothesisId",input.hypothesisId()).put("contextId",context.path("contextId").asText())
                    .put("equivalenceScope","VERIFIED").put("validFrom",Instant.now().minusSeconds(60).toString()).put("validThrough",Instant.now().plusSeconds(600).toString());
            snapshot.putObject("coverage").put("TEST_ONLY_PROTEIN","COMPLETE");snapshot.putArray("effects");
            if("expired".equals(failure))snapshot.put("validThrough","2026-09-01T00:00:00Z");
        }
        if("unapproved".equals(failure))for(JsonNode a:x.f.settings.path("adoptions"))((ObjectNode)a).put("status","PENDING");
        var packageDocument=mapper.createObjectNode();packageDocument.set("settings",x.f.settings);
        packageDocument.putObject("bindingSnapshot").put("manifest",x.root.resolve("manifest.json").toString())
                .put("mane",x.root.resolve("mane.json").toString()).put("clingen",x.root.resolve("clingen.json").toString())
                .put("manifestSha256",x.manifestPin);
        var references=packageDocument.putArray("contextReviews");
        int reviewIndex=0;
        for(var entry:x.reviews.entrySet()) {
            Path file=root.resolve("test-only-review-"+(++reviewIndex)+".json");Files.write(file,entry.getValue().bytes());
            references.addObject().put("candidateId",entry.getKey().candidateId()).put("hypothesisId",entry.getKey().hypothesisId())
                    .put("file",file.toString()).put("sha256",x.pins.get(entry.getKey()));
        }
        byte[] packageBytes=mapper.writeValueAsBytes(packageDocument);Path packageFile=root.resolve("test-only-approval.json");Files.write(packageFile,packageBytes);
        var config=mapper.createObjectNode();config.putObject("approvals").putObject(runId)
                .put("file",packageFile.toString()).put("sha256",PredictionCalibration.sha256(packageBytes));
        byte[] configBytes=mapper.writeValueAsBytes(config);Path configFile=root.resolve("test-only-binding.json");Files.write(configFile,configBytes);
        return RevelApplicationBusiness.loadApproval(mapper,configFile,PredictionCalibration.sha256(configBytes),runId,privateData);
    }

    ObjectNode configuration() {
        var config=mapper.createObjectNode().put("schemaVersion","revel-application-business-v1").put("testOnly",true);
        var allowed=config.putArray("allowedPolicySha256s");x.policies.forEach(p->allowed.add(x.f.hash.canonicalSha256(p)));
        config.putObject("approvals");return config;
    }
    void configuredService(Path config) throws Exception {
        var router=org.mockito.Mockito.mock(ReviewedPredictionObservationRouter.class);
        org.mockito.Mockito.when(router.open(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
            List<ReviewedPredictionObservationRouter.Input> inputs=call.getArgument(0);
            var input=inputs.get(0);var session=org.mockito.Mockito.mock(ReviewedPredictionObservationRouter.Session.class);
            org.mockito.Mockito.when(session.lookup(org.mockito.ArgumentMatchers.any())).thenAnswer(lookup->
                    syntheticObservations.apply(input.prepared(),input.candidate()).lookup(lookup.getArgument(0)));
            return session;
        });
        application=RevelApplicationBusiness.configured(mapper,runtime,router,config,PredictionCalibration.sha256(Files.readAllBytes(config)));
        service=AcmgBusinessService.revelApplication(new AcmgBusinessService(mapper,jobs,store,sources,
                ()->new ExplicitCaseInputCoordinator(mapper,x.resolver,producer,List.of(checksum))),application,root.resolve("functional-experiment-reviews"));
        http=MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
    }
    Path writeConfiguration(String name,ObjectNode config) throws Exception {
        Path path=root.resolve(name+".json");Files.write(path,mapper.writeValueAsBytes(config));return path;
    }
    ObjectNode registeredApproval(ObjectNode config) throws Exception {
        var saved=store.run("test-analysis",source.path("runId").asText()).orElseThrow();
        approve(saved.id(),mapper.readTree(saved.privateJson()));
        Path file=root.resolve("test-only-approval.json");var next=config.deepCopy();
        next.withObject("/approvals").putObject(saved.id()).put("file",file.toString())
                .put("sha256",PredictionCalibration.sha256(Files.readAllBytes(file)));return next;
    }
    @Test void configuredApprovalLifecycleReusesSourceWithoutNewScience() throws Exception {
        ObjectNode c0=configuration();Path before=writeConfiguration("C0",c0);configuredService(before);
        sourceRequest=declaration(service.inputOptions("test-analysis",2));prepare();
        var original=store.run("test-analysis",source.path("runId").asText()).orElseThrow();
        var options0=service.inputOptions("test-analysis",2);
        var binding0=application.identity();var preparation0=application.preparationIdentity();var missing=post(adoption);
        assertEquals("REVEL_APPLICATION_APPROVAL_MISSING",missing.at("/applications/0/reasonCode").asText());
        Path after=writeConfiguration("C1",registeredApproval(c0));configuredService(after);
        assertNotEquals(binding0,application.identity());assertEquals(preparation0,application.preparationIdentity());
        assertEquals("1.1.0",source.at("/executionProfile/profileVersion").asText());
        assertFalse(source.path("executionProfile").has("revelApplicationBinding"));
        assertEquals(preparation0,source.at("/executionProfile/revelPreparationBinding"));
        assertEquals(binding0.path("configurationSha256"),source.at("/revelSourceGenerationAudit/configurationSha256"));
        assertEquals(source.path("revelSourceGenerationAudit"),mapper.readTree(original.privateJson()).path("revelSourceGenerationAudit"));
        assertEquals(application.identity(),service.inputOptions("test-analysis",2).path("revelApplicationBinding"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/variant-analyses/test-analysis/acmg/runs")
                .contentType("application/json").content(Objects.requireNonNull(mapper.writeValueAsBytes(adoption)))).andExpect(status().isBadRequest());
        assertEquals(2,queries.get());
        var currentDeclaration=declaration(service.inputOptions("test-analysis",2));
        var oldDeclaration=sourceRequest.deepCopy();var compared=currentDeclaration.deepCopy();
        for(JsonNode ref:oldDeclaration.path("recommendations"))((ObjectNode)ref).remove("generatedAt");
        for(JsonNode ref:compared.path("recommendations"))((ObjectNode)ref).remove("generatedAt");
        assertEquals(oldDeclaration,compared);
        var repeated=post(currentDeclaration);
        var receipt=mapper.createObjectNode().put("dataClass","SYNTHETIC").put("testOnly",true)
                .put("sourceRunId",original.id()).put("sourceRequestHash",source.path("requestSha256").asText())
                .put("repeatedRunId",repeated.path("runId").asText()).put("repeatedRequestHash",repeated.path("requestSha256").asText())
                .put("initialProviderCalls",2).put("initialEvaluatorCalls",source.path("evaluatorCalls").asInt())
                .put("additionalProviderCalls",queries.get()-2).put("additionalEvaluatorCalls",original.id().equals(repeated.path("runId").asText())?0:repeated.path("evaluatorCalls").asInt());
        String export=System.getProperty("varvis.revel.lifecycle.export","");
        if(!export.isBlank()){Path dir=Path.of(export);Files.createDirectories(dir);Files.write(dir.resolve("receipt.json"),mapper.writeValueAsBytes(receipt));}
        assertEquals(original.id(),repeated.path("runId").asText(),receipt.toPrettyString());
        assertEquals(2,queries.get());assertEquals(source,repeated);
        adoption.withObject("/revelApplication").put("bindingSha256",application.identity().path("bindingSha256").asText());
        var result=post(adoption);assertEquals("MET",result.at("/applications/0/status").asText(),result.toPrettyString());
        assertEquals("SUPPORTING",result.at("/applications/0/appliedStrength").asText());
        assertEquals("NONE",result.at("/applications/0/eligibility/appliedStrength").asText());
        assertEquals("NOT_MET",result.at("/applications/1/status").asText());assertEquals("NONE",result.at("/applications/1/appliedStrength").asText());
        assertNotEquals(missing.path("runId"),result.path("runId"));
        store=new AcmgBusinessStore(db);sources=new AcmgExecutionSources(mapper,store);configuredService(after);
        assertEquals(result,post(adoption));assertEquals(source,post(declaration(service.inputOptions("test-analysis",2))));
        assertEquals(result,service.read("test-analysis",result.path("runId").asText()));
        var downloaded=http.perform(get("/variant-analyses/test-analysis/acmg/runs/"+result.path("runId").asText())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(result,mapper.readTree(downloaded));assertEquals(original,store.run("test-analysis",original.id()).orElseThrow());assertEquals(2,queries.get());
        receipt.put("adoptionRunId",result.path("runId").asText()).put("adoptionRequestHash",result.path("requestSha256").asText())
                .put("missingApprovalRunId",missing.path("runId").asText()).put("adoptionAdditionalProviderCalls",queries.get()-2)
                .put("adoptionAdditionalEvaluatorCalls",result.path("evaluatorCalls").asInt()).put("originalsUnchanged",true);
        receipt.set("C0Binding",binding0);receipt.set("C1Binding",application.identity());receipt.set("preparationBinding",preparation0);
        if(!export.isBlank()) {
            Path dir=Path.of(export);Files.write(dir.resolve("receipt.json"),mapper.writeValueAsBytes(receipt));
            for(Path file:List.of(before,after,root.resolve("test-only-approval.json"),root.resolve("test-only-review-1.json"),root.resolve("test-only-review-2.json"),
                    x.root.resolve("manifest.json"),x.root.resolve("mane.json"),x.root.resolve("clingen.json")))
                Files.copy(file,dir.resolve(file.getFileName()),StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(dir.resolve("source-request.json"),original.requestJson());Files.writeString(dir.resolve("source-private.json"),original.privateJson());
            Files.writeString(dir.resolve("source-publication.json"),store.source("test-analysis").orElseThrow().json());
            Files.write(dir.resolve("synthetic-source.json"),mapper.writeValueAsBytes(source));Files.write(dir.resolve("synthetic-application.json"),mapper.writeValueAsBytes(result));
            Files.write(dir.resolve("adoption-request.json"),mapper.writeValueAsBytes(adoption));
            Files.write(dir.resolve("C0-input-options.json"),mapper.writeValueAsBytes(options0));
            Files.write(dir.resolve("synthetic-input-options.json"),mapper.writeValueAsBytes(service.inputOptions("test-analysis",2)));
            Files.writeString(dir.resolve("application-private.json"),store.run("test-analysis",result.path("runId").asText()).orElseThrow().privateJson());
            Files.writeString(dir.resolve("application-request.json"),store.run("test-analysis",result.path("runId").asText()).orElseThrow().requestJson());
        }
    }

    @ParameterizedTest @ValueSource(strings={"configuration-pin","approval-pin","other-run"})
    void configuredApprovalRejectsTamperingAndOtherRunWithoutScience(String kind) throws Exception {
        var c0=configuration();configuredService(writeConfiguration("C0",c0));
        sourceRequest=declaration(service.inputOptions("test-analysis",2));prepare();
        var c1=registeredApproval(c0);Path file=root.resolve("test-only-approval.json");
        if("other-run".equals(kind)) {
            ObjectNode wrong=(ObjectNode)mapper.readTree(Files.readAllBytes(file));wrong.withObject("/settings").put("runId","TEST_ONLY_OTHER_RUN");
            Files.write(file,mapper.writeValueAsBytes(wrong));
            c1.withObject("/approvals/"+source.path("runId").asText()).put("sha256",PredictionCalibration.sha256(Files.readAllBytes(file)));
        }
        Path config=writeConfiguration("C1",c1);configuredService(config);
        adoption.withObject("/revelApplication").put("bindingSha256",application.identity().path("bindingSha256").asText());
        if("configuration-pin".equals(kind)) Files.writeString(config," ",StandardOpenOption.APPEND);
        if("approval-pin".equals(kind)) Files.writeString(file," ",StandardOpenOption.APPEND);
        var result=post(adoption);
        assertEquals("other-run".equals(kind)?"REVEL_APPLICATION_SOURCE_MISMATCH":"REVEL_APPLICATION_CONFIGURATION_CHANGED",result.at("/applications/0/reasonCode").asText());
        assertEquals("NONE",result.at("/applications/0/appliedStrength").asText());
        assertEquals(source,service.read("test-analysis",source.path("runId").asText()));assertEquals(2,queries.get());
    }
    @Test void approvalOnlyChangeCannotResetFailedSourceBudget() throws Exception {
        var config=configuration();configuredService(writeConfiguration("C0",config));
        sourceRequest=declaration(service.inputOptions("test-analysis",2));failSave=true;
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/variant-analyses/test-analysis/acmg/runs")
                .contentType("application/json").content(Objects.requireNonNull(mapper.writeValueAsBytes(sourceRequest)))).andExpect(status().isServiceUnavailable());
        failSave=false;var failed=post(sourceRequest);assertEquals("FAILED",failed.path("status").asText());assertEquals(2,queries.get());
        config.withObject("/approvals").putObject(failed.path("runId").asText()).put("file","TEST_ONLY_UNREAD_APPROVAL").put("sha256","2".repeat(64));
        configuredService(writeConfiguration("C1",config));
        assertEquals(failed,post(declaration(service.inputOptions("test-analysis",2))));assertEquals(2,queries.get());
    }
    @Test void preparationIdentityIncludesScienceButNotApprovalRegistration() throws Exception {
        var c0=configuration();configuredService(writeConfiguration("C0",c0));
        var stable=application.preparationIdentity();var original=application.identity();
        assertEquals(runtime.identity(),stable.path("sourceRuntime"));
        var c1=c0.deepCopy();c1.withObject("/approvals").putObject("TEST_ONLY_FUTURE_RUN").put("file","UNREAD").put("sha256","2".repeat(64));
        configuredService(writeConfiguration("C1",c1));assertEquals(stable,application.preparationIdentity());assertNotEquals(original,application.identity());
        var c2=c1.deepCopy();c2.withObject("/approvals/TEST_ONLY_FUTURE_RUN").put("sha256","3".repeat(64));
        var updated=application.identity();configuredService(writeConfiguration("C2",c2));
        assertEquals(stable,application.preparationIdentity());assertNotEquals(updated,application.identity());
        var policy=c0.deepCopy();policy.withArray("allowedPolicySha256s").add("4".repeat(64));
        configuredService(writeConfiguration("policy",policy));assertNotEquals(stable,application.preparationIdentity());
        var purpose=c0.deepCopy().put("testOnly",false);configuredService(writeConfiguration("purpose",purpose));assertNotEquals(stable,application.preparationIdentity());
        var document=x.producer.document(x.policies.toArray(ObjectNode[]::new));
        for(var o:x.observations)document.withObject("/materials/transcriptModels").set(x.f.hash.canonicalSha256(o.observation().path("transcriptModel")),o.observation().path("transcriptModel"));
        runtime=new ReviewedPredictionBusinessRuntime(mapper,x.producer.load(document),mapper.createObjectNode().put("source","TEST_ONLY").put("resourceVersion","CHANGED"));
        configuredService(writeConfiguration("resource",c0));assertNotEquals(stable,application.preparationIdentity());
        var model=x.observations.get(0).observation().path("transcriptModel").deepCopy();((ObjectNode)model).put("testOnlyIdentityChange",true);
        document.withObject("/materials/transcriptModels").set(x.f.hash.canonicalSha256(model),model);
        runtime=new ReviewedPredictionBusinessRuntime(mapper,x.producer.load(document),mapper.createObjectNode().put("source","TEST_ONLY"));
        configuredService(writeConfiguration("model",c0));assertNotEquals(stable,application.preparationIdentity());
        var changed=application.preparationIdentity();changed.put("bindingSha256","MUTATED_COPY");assertNotEquals(changed,application.preparationIdentity());
        assertEquals(0,queries.get());
    }

    @Test void normalControllerPersistsReusesAndDownloadsUnchangedSourceAndAdoption() throws Exception {
        prepare(); approved=true; String original=store.run("test-analysis",source.path("runId").asText()).orElseThrow().responseJson();
        var result=post(adoption); assertEquals("MET",result.at("/applications/0/status").asText(),result.toPrettyString());
        assertEquals("SUPPORTING",result.at("/applications/0/appliedStrength").asText());assertEquals("NONE",result.at("/applications/0/eligibility/appliedStrength").asText());
        assertEquals("NOT_MET",result.at("/applications/1/status").asText()); assertEquals("NONE",result.at("/applications/1/appliedStrength").asText());
        assertEquals(result,post(adoption));assertEquals(source,post(sourceRequest));
        var newStore=new AcmgBusinessStore(db);assertEquals(result,mapper.readTree(newStore.run("test-analysis",result.path("runId").asText()).orElseThrow().responseJson()));
        var downloaded=http.perform(get("/variant-analyses/test-analysis/acmg/runs/"+result.path("runId").asText())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(result,mapper.readTree(downloaded));assertEquals(original,store.run("test-analysis",source.path("runId").asText()).orElseThrow().responseJson());assertEquals(2,queries.get());
        assertEquals(result.path("applications").get(0),service.candidate("test-analysis",x.candidates.get(0).getCandidateId(),result.path("runId").asText()).path("applications").get(0));
        String export=System.getProperty("varvis.revel.business.export","");
        if(!export.isBlank()) {
            Path dir=Path.of(export);Files.createDirectories(dir);
            Files.write(dir.resolve("synthetic-source.json"),mapper.writeValueAsBytes(source));
            Files.write(dir.resolve("synthetic-application.json"),mapper.writeValueAsBytes(result));
            Files.write(dir.resolve("synthetic-request.json"),mapper.writeValueAsBytes(adoption));
            var saved=store.run("test-analysis",source.path("runId").asText()).orElseThrow();
            Files.writeString(dir.resolve("source-request.json"),saved.requestJson());
            Files.writeString(dir.resolve("source-private.json"),saved.privateJson());
            Files.writeString(dir.resolve("source-publication.json"),store.source("test-analysis").orElseThrow().json());
            Files.write(dir.resolve("synthetic-input-options.json"),mapper.writeValueAsBytes(service.inputOptions("test-analysis",2)));
        }
    }
    @ParameterizedTest @ValueSource(strings={"missing","unapproved","expired"}) void missingApprovalAndStaleEffectsStayUnapplied(String kind) throws Exception {
        prepare(); approved=!"missing".equals(kind);failure=kind;var result=post(adoption);
        assertEquals("NOT_EVALUATED",result.at("/applications/0/status").asText());assertEquals("NONE",result.at("/applications/0/appliedStrength").asText());
        assertEquals(switch(kind){case "missing"->"REVEL_APPLICATION_APPROVAL_MISSING";case "expired"->"EFFECT_SNAPSHOT_STALE";default->"LOCAL_APPLICATION_ADOPTION_REQUIRED";},result.at("/applications/0/reasonCode").asText(),result.toPrettyString());
        assertEquals(2,queries.get());
    }

    @ParameterizedTest @ValueSource(strings={"no-record","unavailable"}) void missingObservationNeverBecomesZeroOrNegative(String kind) throws Exception {
        observationFailure=kind; source=post(sourceRequest);
        for (JsonNode candidate:source.path("candidates")) {
            JsonNode clause=candidate.at("/attempts/0/transcriptInput/reviewedPrediction/clauses/PP3/result");
            assertEquals("NOT_EVALUATED",clause.at("/evaluationResult/status").asText(),clause.toPrettyString());
            assertEquals("no-record".equals(kind)?"REVEL_NO_RECORD":"PREDICTION_BUNDLE_OBSERVATION_MISSING",clause.at("/evaluationResult/reasonCode").asText());
            assertFalse(clause.has("validatedScore"));
        }
        assertEquals(source,post(sourceRequest)); assertEquals(2,queries.get());
    }

    @Test void concurrentSameSourceClaimsOneIdentityAndOneBudget() throws Exception {
        queryStarted=new CountDownLatch(1);queryRelease=new CountDownLatch(1);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            Future<ObjectNode> first=pool.submit(()->post(sourceRequest));
            assertTrue(queryStarted.await(15,TimeUnit.SECONDS));
            var second=post(sourceRequest); assertEquals("RUNNING",second.path("status").asText());
            queryRelease.countDown(); var completed=first.get(20,TimeUnit.SECONDS);
            assertEquals(second.path("runId"),completed.path("runId"));assertEquals(completed,post(sourceRequest));assertEquals(2,queries.get());
        } finally { queryRelease.countDown();pool.shutdownNow(); }
    }

    @Test void saveFailureReturnsFailureAndDoesNotSpendBudgetAgain() throws Exception {
        failSave=true;
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/variant-analyses/test-analysis/acmg/runs")
                .contentType("application/json").content(Objects.requireNonNull(mapper.writeValueAsBytes(sourceRequest)))).andExpect(status().isServiceUnavailable());
        assertEquals(2,queries.get());failSave=false;
        assertEquals("FAILED",post(sourceRequest).path("status").asText());assertEquals(2,queries.get());
    }

    @Test void inconsistentMoiRecommendationIsRejectedBeforeAnyQuery() throws Exception {
        sourceRequest.withObject("/hypotheses/0/hypothesis").put("moi","AD");
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/variant-analyses/test-analysis/acmg/runs")
                .contentType("application/json").content(Objects.requireNonNull(mapper.writeValueAsBytes(sourceRequest)))).andExpect(status().is4xxClientError());
        assertEquals(0,queries.get());
    }

    @Test void concurrentAdoptionReusesOneSavedIdentityWithoutQueries() throws Exception {
        prepare();approved=true;ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->post(adoption));var b=pool.submit(()->post(adoption));
            var first=a.get(20,TimeUnit.SECONDS);var second=b.get(20,TimeUnit.SECONDS);
            assertEquals(first.path("runId"),second.path("runId"));
            assertEquals("MET",service.read("test-analysis",first.path("runId").asText()).at("/applications/0/status").asText());
            assertEquals(2,queries.get());
        } finally {pool.shutdownNow();}
    }

    @Test void adoptionSaveFailureLeavesSourceImmutableAndCannotReturnSuccess() throws Exception {
        prepare();approved=true;failSave=true;
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/variant-analyses/test-analysis/acmg/runs")
                .contentType("application/json").content(Objects.requireNonNull(mapper.writeValueAsBytes(adoption)))).andExpect(status().isServiceUnavailable());
        failSave=false;assertEquals("FAILED",post(adoption).path("status").asText());
        assertEquals(source,service.read("test-analysis",source.path("runId").asText()));assertEquals(2,queries.get());
    }

    @Test void changedApprovalConfigurationPinIsRejectedBeforeUse() throws Exception {
        Path file=root.resolve("invalid-pin.json");Files.writeString(file,"{}");
        var failure=assertThrows(IllegalArgumentException.class,()->RevelApplicationBusiness.loadApproval(mapper,file,"0".repeat(64),"unclaimed",mapper.createObjectNode()));
        assertEquals("REVEL_APPLICATION_CONFIGURATION_CHANGED",failure.getMessage());assertEquals(0,queries.get());
    }

    @Test void genericRetryCannotSpendAnotherRevelBudget() throws Exception {
        prepare();var retry=sourceRequest.deepCopy().put("retryOf",source.path("runId").asText());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/variant-analyses/test-analysis/acmg/runs")
                .contentType("application/json").content(Objects.requireNonNull(mapper.writeValueAsBytes(retry)))).andExpect(status().isConflict());
        assertEquals(2,queries.get());
    }

    @Test void nextSourceCannotDoubleAdoptSameEffect() throws Exception {
        prepare();approved=true;var adopted=post(adoption);assertEquals("ADOPTED",adopted.at("/applications/0/adoptionStatus").asText());
        sourceRequest.withObject("/hypotheses/0/transcript").put("selectionReason","TEST_ONLY second explicit declaration of identical background");
        prepare();var duplicate=post(adoption);
        assertEquals("SAME_EFFECT_ALREADY_ADOPTED",duplicate.at("/applications/0/reasonCode").asText(),duplicate.toPrettyString());
        assertEquals("NONE",duplicate.at("/applications/0/appliedStrength").asText());
        assertEquals("NOT_MET",duplicate.at("/applications/1/status").asText());
    }
}
