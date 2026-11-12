package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.*;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.service.*;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.*;
import org.nbscn.varvis.variantanalysis.provider.SavedPredictionContextBridge.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.nbscn.varvis.variantanalysis.provider.SavedPredictionContextBridge.REVIEW_SCHEMA;
import static org.nbscn.varvis.variantanalysis.provider.SavedPredictionContextBridge.REVIEW_SCOPE;

/** TEST_ONLY native parser/scanner/publisher and existing bundle producers. No real providers or database. */
final class ReviewedClauseSourceFixtures {
    final PredictionEligibilityFixtures f = new PredictionEligibilityFixtures();
    final Path root;
    final Map<String,Path> ledgerFiles = new LinkedHashMap<>();
    final List<ExplicitContextSourceLedger> ledgers = new ArrayList<>();
    final List<ExplicitInput> inputs = new ArrayList<>();
    final Map<Key,ReviewBytes> reviews = new LinkedHashMap<>();
    final Map<Key,String> pins = new LinkedHashMap<>();
    final Map<Key,ObjectNode> contexts = new LinkedHashMap<>();
    final ArrayNode crosswalk = f.mapper.createArrayNode(), relationships = f.mapper.createArrayNode();
    final List<VariantInterpretation> candidates;
    final ParsedCandidateFixture.Run parsed;
    final List<PredictionClauseRuntimeTest.Fixture> observations = new ArrayList<>();
    final List<ObjectNode> policies = new ArrayList<>();
    final List<ExplicitAlphaMissenseDispatcher.CandidateDispatch> dispatches = new ArrayList<>();
    Snapshot snapshot;
    String manifestPin;
    PatientDiseaseContextBindingResolver resolver;
    int fileIndex;
    final RevelPredictionObservationTest revel = new RevelPredictionObservationTest();
    final ReviewedPredictionBundleTest producer = new ReviewedPredictionBundleTest();

    final boolean negative;
    ReviewedClauseSourceFixtures(Path root) throws Exception { this(root,false); }
    ReviewedClauseSourceFixtures(Path root, boolean negative) throws Exception {
        this(root, negative, "native");
    }
    ReviewedClauseSourceFixtures(Path root, boolean negative, String workspaceName) throws Exception {
        this.negative=negative;
        this.root=root; Files.createDirectories(root); f.mapper.findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        parsed=ParsedCandidateFixture.run(root.resolve(workspaceName), "test-analysis", GenomeAssembly.GRCh38,
            ParsedCandidateFixture.vcf("1\t100\t.\t"+ref()+"\t"+alt()+"\t.\tPASS\t.\tGT\t1/1", "1\t200\t.\t"+ref()+"\t"+alt()+"\t.\tPASS\t.\tGT\t1/1"),
            ParsedCandidateFixture.tsv("1\t1\t100\t"+ref()+"\t"+alt()+"\tSYNTH1\t101\tAR\t0.99", "2\t1\t200\t"+ref()+"\t"+alt()+"\tSYNTH2\t102\tAR\t0.95"));
        candidates=parsed.execution().getInterpretations(); assertEquals(2,candidates.size());
        for(int i=0;i<2;i++) {
            var sample=revel.input(i==0?"0.9":"0.5","GE","0.7");
            ObjectNode tx=f.mapper.valueToTree(sample.fixture().candidate().getTranscriptAnnotations().get(0));
            tx.remove(List.of("hgvsc","hgvsp"));
            tx.put("geneSymbol","SYNTH"+(i+1)).put("transcriptId","ENST00000"+(i+1)).put("transcriptAccession","ENST00000"+(i+1))
              .put("hgvsCoding","ENST00000"+(i+1)+".4:c.5A>G").put("hgvsProtein","NP_00000"+(i+1)+".1:p.Lys2Arg");
            candidates.get(i).applyTranscriptAnnotations(List.of(f.mapper.treeToValue(tx,TranscriptConsequence.class)));
            crosswalk.addObject().put("ncbiGeneId",""+(101+i)).put("hgncId","HGNC:"+(101+i)).put("symbol","SYNTH"+(i+1)).putArray("sourceRows").add(i+2);
            relationships.addObject().put("hgncId","HGNC:"+(101+i)).put("symbol","SYNTH"+(i+1)).put("mondoId","MONDO:000010"+(i*2+1))
              .put("diseaseLabel","TEST_ONLY disease").put("moi","AR").put("classification","Definitive").put("classificationDate","2026-09-05")
              .put("gcep","TEST_ONLY").put("reportLocator","https://example.invalid/TEST_ONLY/"+i).put("sourceRow",i+7);
        }
        AcmgBusinessStore store=mock(AcmgBusinessStore.class);
        doAnswer(call -> { f.docs.put("publication",(ObjectNode)f.mapper.readTree((String)call.getArgument(1))); return null; }).when(store).saveSource(anyString(),anyString());
        new AcmgExecutionSources(f.mapper,store).publish(parsed.job(),parsed.conversion(),parsed.execution(),candidates);
        f.docs.keySet().retainAll(Set.of("run","request","private","publication")); f.settings.remove(List.of("requestSourcePointer","calibrationContracts"));
        f.settings.put("runId","TEST_ONLY_reviewed_run").put("publicationAdapterVersion",SavedPredictionApplicationInputs.NATIVE_PUBLICATION);
        f.settings.putArray("candidates"); f.settings.putArray("clauseApplicationSidecars");
        ObjectNode run=f.docs.get("run"); run.remove("sourceRunId"); run.put("runId","TEST_ONLY_reviewed_run"); run.putArray("candidates"); run.putArray("candidateIds"); run.putArray("bundles");
        ObjectNode declaration=f.mapper.createObjectNode().put("dataClass","SYNTHETIC"); declaration.putArray("hypotheses");
        f.docs.get("request").set("declaration",declaration); f.docs.get("private").set("declaration",declaration.deepCopy());
        for(var c:candidates) { f.settings.withArray("candidates").addObject().put("candidateId",c.getCandidateId()).putArray("hypotheses"); run.withArray("candidateIds").add(c.getCandidateId()); }
        resources();
        for(int i=0;i<2;i++) inputs.add(input(i,"TEST_ONLY_H"+i,0,"UNKNOWN","AFFECTED","TEST_ONLY_SELECTION"));
        var assembly=new ExplicitEvidenceContextAssembler(f.mapper,resolver,ledgers).assemble(new AnalysisContext("test-analysis",GenomeAssembly.GRCh38,AnalysisMode.WES),candidates,2,inputs);
        for(int i=0;i<2;i++) {
            var attempt=assembly.candidates().get(i).attempts().get(0); assertTrue(attempt.blockers().isEmpty(),attempt.blockers().toString());
            ObjectNode context=Objects.requireNonNull(attempt.context());
            var toy=revel.input(i==0?"0.9":"0.5","GE","0.7"); ObjectNode obs=toy.fixture().observation(); obs.set("context",context.deepCopy());
            ObjectNode model=(ObjectNode)obs.path("transcriptModel");
            model.put("strand",negative?-1:1).put("modelId","TEST_ONLY_MODEL_"+i).put("hgncId","HGNC:"+(101+i)).put("geneSymbol","SYNTH"+(i+1))
              .put("transcript","ENST00000"+(i+1)+".4").put("protein","NP_00000"+(i+1)+".1");
            model.withArray("exons").removeAll().addObject().put("ordinal",1).put("genomicStart",96+i*100).put("genomicEnd",104+i*100).put("cdsStart",1).put("cdsEnd",9);
            obs.put("schemaVersion","revel-publisher-index-observation-v1");
            ObjectNode raw=(ObjectNode)obs.at("/rawRecords/0"); raw.put("rawCsv","1,90,"+(100+i*100)+","+ref()+","+alt()+",K,R,"+(i==0?"0.9":"0.5")+",ENST00000"+(i+1)); stamp(raw,1);
            obs.set("coordinateRecords",obs.path("rawRecords").deepCopy());
            var fixture=new PredictionClauseRuntimeTest.Fixture(candidates.get(i),context,obs); revel.rebindEvidence(fixture); observations.add(fixture);
            ObjectNode policy=revel.base.policy(fixture,"PP3","REVEL","GE","0.7").put("policyId","TEST_ONLY_REVEL_PP3_"+i);
            policy.withObject("/resourceBinding").put("sourceArtifactSha256","a".repeat(64));
            policy.putObject("revelTranscriptBinding").put("accession","ENST00000"+(i+1)).put("status","REVIEWED_ACCESSION_BRIDGE_WITH_ALLELE_SPECIFIC_SEQUENCE_CHECK").putArray("sourceIds").add("synthetic-source");
            policy.putObject("transcriptModelBinding").put("modelId",model.path("modelId").asText()).put("modelVersion","1").put("canonicalSha256",f.hash.canonicalSha256(model)); policies.add(policy);
            declaration.withArray("hypotheses").addObject().set("hypothesis",f.mapper.valueToTree(new PatientDiseaseHypothesis(candidates.get(i).getCandidateId(),"TEST_ONLY_H"+i,"HGNC:"+(101+i),"MONDO:000010"+(i*2+1),"AR",new HypothesisProvenance("TEST_ONLY_DECLARATION","1","TEST_ONLY/hypotheses/TEST_ONLY_H"+i))));
        }
        f.docs.get("private").set("declaration",declaration.deepCopy());
        ObjectNode document=producer.document(policies.toArray(ObjectNode[]::new));
        for(var o:observations) document.withObject("/materials/transcriptModels").set(f.hash.canonicalSha256(o.observation().path("transcriptModel")),o.observation().path("transcriptModel"));
        f.settings.set("catalog",document.path("catalog").deepCopy()); f.settings.set("policies",document.path("policies").deepCopy());
        var bundle=producer.load(document);
        for(int i=0;i<2;i++) {
            var original=assembly.candidates().get(i).attempts().get(0); var o=observations.get(i); var prep=bundle.prepare(o.context());
            ObjectNode result=bundle.execute(prep,o.candidate(),request -> o.observation().deepCopy());
            producer.assertStatus(result,"PP3",i==0?"MET":"NOT_MET");
            ObjectNode tx=f.mapper.createObjectNode(); tx.set("predictionPreparation",prep.snapshot()); tx.set("reviewedPrediction",result);
            var projector=ExplicitAlphaMissenseDispatcher.class.getDeclaredMethod("transcriptInput",TranscriptConsequence.class); projector.setAccessible(true);
            Object projection=projector.invoke(new ExplicitAlphaMissenseDispatcher(f.mapper,mock(AlphaMissenseProvider.class),(c,a,p,r,id)->{ throw new AssertionError("TEST_ONLY: evaluator must not run"); }),o.candidate().getTranscriptAnnotations().get(0));
            assertInstanceOf(ObjectNode.class,projection); tx.set("persistedAnnotation",(ObjectNode)projection);
            List<ObjectNode> atomics=new ArrayList<>(), evals=new ArrayList<>();
            for(JsonNode clause:result.path("clauses")) {
                JsonNode r=clause.path("result"); for(String field:List.of("evidence","vepEvidence")) if(r.path(field) instanceof ObjectNode node && !atomics.contains(node)) atomics.add(node);
                if(r.path("evaluation") instanceof ObjectNode node) evals.add(node);
            }
            var selection=new CSpecExecutionPreflight.SelectionAttempt(original,"TEST_ONLY",List.of(),null,null,null,List.of(),List.of(),List.of());
            var dispatch=new ExplicitAlphaMissenseDispatcher.DispatchAttempt(selection,o.context(),null,atomics,evals,tx,List.of(),1,1);
            dispatches.add(new ExplicitAlphaMissenseDispatcher.CandidateDispatch(o.candidate().getCandidateId(),List.of(dispatch)));
            run.withArray("candidates").add(f.mapper.valueToTree(dispatches.get(i)));
            application(i,prep.snapshot());
        }
        run.set("bundles",new AcmgBusinessArtifacts(f.mapper).bundles(new ExplicitAlphaMissenseDispatcher.DispatchResult(null,dispatches,2,2),List.of("ACMG_AMP_2015:PP3")));
        reseal(); reviewAll();
        for(int i=0;i<2;i++) {
            Key key=new Key(candidates.get(i).getCandidateId(),"TEST_ONLY_H"+i); ObjectNode context=contexts.get(key);
            ObjectNode h=f.candidate(i).withArray("hypotheses").addObject().put("hypothesisId",key.hypothesisId()).put("verification","VERIFIED").put("reviewId","TEST_ONLY_REVIEW_"+key.hypothesisId()).put("reviewerId","TEST_ONLY_INDEPENDENT_REVIEWER").put("sourceSha256",pins.get(key)).put("effectSnapshotId",key.candidateId()); h.set("context",context);
            ObjectNode e=f.settings.withObject("/effectSnapshots").putObject(key.candidateId()).put("snapshotId",key.candidateId()).put("version","1").put("status","COMPLETE_FOR_DECLARED_SCOPE").put("reviewId","TEST_ONLY_REVIEW").put("externalWorldCovered",false).put("candidateId",key.candidateId()).put("hypothesisId",key.hypothesisId()).put("contextId",context.path("contextId").asText()).put("equivalenceScope","VERIFIED").put("validFrom","2026-09-19T00:00:00Z").put("validThrough","2026-09-20T00:00:00Z"); e.putObject("coverage").put("TEST_ONLY_PROTEIN","COMPLETE"); e.putArray("effects");
        }
        reseal();
    }
    void stamp(ObjectNode row,int n) { row.put("sourceLine",n).put("recordId","revel-member-"+"a".repeat(64)+"-data-row-"+n).put("rawCsvSha256",PredictionCalibration.sha256(row.path("rawCsv").asText().getBytes(java.nio.charset.StandardCharsets.US_ASCII))); }
    ObjectNode annotation(int i) { return (ObjectNode)f.docs.get("publication").at("/candidates/"+i+"/published/transcriptAnnotations/0"); }
    record LocalSource(VariantInterpretation candidate,ObjectNode selection,String mappingToken) { }
    LocalSource localSource(int i) {
        ObjectNode selection=f.mapper.createObjectNode().put("versionedTranscriptId",SavedPredictionApplicationInputs.versionedAnnotationIdentity(annotation(i))).put("annotationSourceSha256",f.hash.canonicalSha256(annotation(i)));
        return new LocalSource(candidates.get(i),selection,new EngineExecutionResult.MatchedRecord("1",100+i*100,ref(),List.of(alt()),1,i+1).mappingToken());
    }
    void reseal() throws Exception {
        f.docs.get("run").put("executionSourceSha256",PredictionCalibration.sha256(f.mapper.writeValueAsBytes(f.docs.get("publication"))));
        f.docs.get("run").put("privateArtifactSha256",PredictionCalibration.sha256(f.mapper.writeValueAsBytes(f.docs.get("private"))));
        f.docs.get("run").put("requestSha256",f.hash.canonicalSha256(f.docs.get("request")));
        Map<String,String> bytePins=new LinkedHashMap<>(); f.artifacts=new LinkedHashMap<>();
        for(var e:f.docs.entrySet()) { byte[] bytes=f.mapper.writeValueAsBytes(e.getValue()); bytePins.put(e.getKey(),PredictionCalibration.sha256(bytes)); f.artifacts.put(e.getKey(),new SavedPredictionApplicationInputs.Artifact(bytes,f.mapper.readTree(bytes))); }
        f.roots=new SavedPredictionApplicationInputs.Supply("TEST_ONLY_CLAUSE",bytePins,f.hash.canonicalSha256(SavedPredictionApplicationInputs.semanticSettings(f.settings)));
    }
    SavedPredictionApplicationInputs.Input saved() { var saved=SavedPredictionApplicationInputs.verifyReviewedClause(f.mapper,f.artifacts,f.settings,f.roots); assertEquals("",saved.issue()); return saved; }
    TrustedContextSupply supply() throws Exception { return TrustedContextSupply.load(f.mapper,snapshot,inputs,ledgerFiles,reviews,pins); }
    List<ObjectNode> assess() throws Exception { var s=saved(); var p=new SavedPredictionContextBridge().prepare(f.mapper,s,supply()); return new PredictionApplicationEligibility(f.mapper).assess(s,p).stream().map(r->Objects.requireNonNull(r).json()).toList(); }
    void application(int i,ObjectNode preparation) {
        String id="TEST_ONLY_"+i; ObjectNode policy=policies.get(i); String sha=f.hash.canonicalSha256(policy);
        ObjectNode binding=f.mapper.createObjectNode().put("policyId",policy.path("policyId").asText()).put("policyVersion","1").put("policySha256",sha);
        ObjectNode side=f.settings.withArray("clauseApplicationSidecars").addObject().put("schemaVersion","prediction-clause-application-sidecar-v1").put("adoptionId",id).put("modelProofId",id); side.setAll(binding);
        ObjectNode template=side.putObject("template").put("templateId","TEST_ONLY_SHARED_REVEL").put("version","1"); template.putArray("requiredCoverage").add("TEST_ONLY_PROTEIN");
        template.putObject("criteria").putObject(policy.at("/criterionIds/0").asText().substring(14)).put("mode","SINGLE").putArray("mechanisms").add("PROTEIN");
        ObjectNode mechanism=template.putObject("mechanisms").putObject("PROTEIN").put("sourceKind",SavedPredictionApplicationInputs.SourceKind.REVIEWED_PREDICTION_CLAUSE_RESULT_V1.name()).put("model","REVEL").put("compatibilityId",id); mechanism.set("policyBinding",binding.deepCopy()); mechanism.putObject("strengthMap").put("SUPPORTING","SUPPORTING");
        String templateSha=f.hash.canonicalSha256(template); side.put("templateSha256",templateSha);
        for(String collection:List.of("adoptions","modelProofs")) {
            ObjectNode proof=f.settings.withObject("/"+collection).putObject(id).put("status","REVIEWED").put("reviewId","TEST_ONLY").put("reviewerId","TEST_ONLY_INDEPENDENT").put("version","1").put("sourceSha256","d".repeat(64)).put("sourceRunId","TEST_ONLY_reviewed_run").put("purpose","TEST_ONLY").put("templateSha256",templateSha); proof.setAll(binding.deepCopy());
            if(collection.equals("modelProofs")) proof.put("kind","PRESELECTED").putArray("preparationIds").add(preparation.path("preparationId").asText());
        }
        ObjectNode c=f.settings.withObject("/compatibilities").putObject(id).put("id",id).put("status","REVIEWED").put("reviewId","TEST_ONLY").put("reviewerId","TEST_ONLY_INDEPENDENT").put("version","1").put("sourceSha256","e".repeat(64)).put("sourceKind",SavedPredictionApplicationInputs.SourceKind.REVIEWED_PREDICTION_CLAUSE_RESULT_V1.name()).put("model","REVEL").put("scope","EXACT_SINGLE_ROW").put("purpose","TEST_ONLY");
        c.set("policyBinding",binding.deepCopy()); c.set("policyScope",policy.path("scope").deepCopy());
        for(String field:List.of("resourceBinding","transcriptModelBinding","revelTranscriptBinding")) c.set(field,policy.path(field).deepCopy()); c.putArray("annotationSourceSha256s").add(f.hash.canonicalSha256(annotation(i)));
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
        var source = localSource(i);
        String candidateId = source.candidate().getCandidateId(), patientId = "patient-context-test-only-bridge";
        var hypothesis = new PatientDiseaseHypothesis(candidateId, hypothesisId, "HGNC:" + (101 + i), "MONDO:000010" + (i * 2 + disease + 1), "AR",
                new HypothesisProvenance("TEST_ONLY_DECLARATION", "1", "TEST_ONLY/hypotheses/" + hypothesisId));
        var binding = resolver.resolve(source.candidate(), List.of(hypothesis)).bindings().get(0);
        assertEquals("READY", binding.status());
        String full = source.selection().path("versionedTranscriptId").asText(); int dot = full.lastIndexOf('.');
        String tx = full.substring(0, dot); int version = Integer.parseInt(full.substring(dot + 1));
        var allele = new Allele("GRCh38", "1", 100 + i * 100, ref(), alt());
        // Actual tiny TEST_ONLY reference bytes, never a patient resource or a production REF claim.
        byte[] fasta = (">1 TEST_ONLY\n"+ref()+ref()+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals(ref().charAt(0), (char) fasta[fasta.length - 3 + i]);
        String refSha = PredictionCalibration.sha256(fasta);
        ObjectNode records = f.mapper.createObjectNode();
        records.putObject("reference").put("kind", "REFERENCE_IDENTITY").put("assembly", "GRCh38")
                .put("observedSha256", refSha).put("expectedSha256", refSha).put("verificationStatus", "VERIFIED")
                .put("verificationMethod", "SHA-256").put("verificationVersion", "TEST_ONLY_1")
                .put("verificationRecordLocator", "TEST_ONLY/ref-check").put("referenceArtifactLocator", "TEST_ONLY/reference.fa");
        var referenceLedger = ledger(records); var reference = referenceLedger.reference("reference");
        records = f.mapper.createObjectNode();
        ObjectNode patient = associated("PATIENT_CONTEXT", candidateId, hypothesisId, patientId).put("sampleId", "synthetic-proband")
                .put("role", "PROBAND").put("sex", sex).put("affectedStatus", affected);
        patient.putObject("hypothesis").put("hgncId", binding.hgncId()).put("mondoId", binding.mondoId()).put("moi", binding.normalizedMoi())
                .set("provenance", f.mapper.valueToTree(hypothesis.provenance()));
        records.set("patient", patient);
        records.set("transcript", associated("TRANSCRIPT_CHOICE", candidateId, hypothesisId, patientId).put("accession", tx).put("version", version)
                .put("selectionPolicy", policy).put("annotationSource", "VEP_SYNTHETIC").put("annotationSourceVersion", "116.0")
                .put("annotationSourceSha256", source.selection().path("annotationSourceSha256").asText())
                .put("hgncId", binding.hgncId()).put("geneSymbol", binding.symbol()).put("annotationRecordLocator", "TEST_ONLY/annotation/" + tx));
        records.set("lineage", associated("ASSEMBLY_LINEAGE", candidateId, hypothesisId, patientId).put("originalInputAssembly", "GRCh38")
                .put("preRankingMappingStatus", "NATIVE").set("originalInputAllele", f.mapper.valueToTree(allele)));
        ObjectNode norm = associated("NORMALIZATION", candidateId, hypothesisId, patientId).put("originalRecord", source.mappingToken())
                .put("selectedAltIndex", 1).put("method", "TEST_ONLY_NORMALIZATION").put("methodVersion", "1").put("executionStatus", "SUCCEEDED")
                .put("status", "UNCHANGED").put("refValidation", "MATCH").put("inputObservedReference", ref()).put("normalizedObservedReference", ref())
                .put("refValidationMethod", "TEST_ONLY_REFERENCE_SLICE").put("refValidationVersion", "1").put("refValidationRecordLocator", "TEST_ONLY/ref-check");
        norm.set("originalAllele", f.mapper.valueToTree(allele)); norm.set("normalizedAllele", f.mapper.valueToTree(allele)); norm.set("reference", f.mapper.valueToTree(reference));
        records.set("normalization", norm); var ledger = ledger(records);
        return new ExplicitInput(candidateId, hypothesisId, binding, "SYNTHETIC",
                new TranscriptChoice(tx, version, policy, "VEP_SYNTHETIC", "116.0", ledger.reference("transcript")),
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
                    .put("testOnly", true).put("analysisId", "test-analysis").put("runId", "TEST_ONLY_reviewed_run")
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

    String ref() { return negative?"T":"A"; }
    String alt() { return negative?"C":"G"; }
    /** Regenerate only TEST_ONLY saved sources with the existing producer, never used by the adapter. */
    void reproduce() throws Exception {
        ObjectNode document=producer.document(policies.toArray(ObjectNode[]::new));
        for(var o:observations) document.withObject("/materials/transcriptModels").set(f.hash.canonicalSha256(o.observation().path("transcriptModel")),o.observation().path("transcriptModel"));
        f.settings.set("catalog",document.path("catalog").deepCopy()); f.settings.set("policies",document.path("policies").deepCopy());
        var bundle=producer.load(document); f.settings.putArray("clauseApplicationSidecars");
        var run=f.docs.get("run"); run.putArray("candidates");
        for(int i=0;i<2;i++) {
            var prior=dispatches.get(i).attempts().get(0); var o=observations.get(i); var prep=bundle.prepare(o.context());
            ObjectNode result=bundle.execute(prep,o.candidate(),request -> o.observation().deepCopy());
            ObjectNode tx=prior.transcriptInput(); tx.set("predictionPreparation",prep.snapshot()); tx.set("reviewedPrediction",result);
            List<ObjectNode> atomics=new ArrayList<>(),evals=new ArrayList<>();
            for(JsonNode clause:result.path("clauses")) {
                JsonNode r=clause.path("result"); for(String field:List.of("evidence","vepEvidence")) if(r.path(field) instanceof ObjectNode node && !atomics.contains(node)) atomics.add(node);
                if(r.path("evaluation") instanceof ObjectNode node) evals.add(node);
            }
            var attempt=new ExplicitAlphaMissenseDispatcher.DispatchAttempt(prior.preflight(),o.context(),null,atomics,evals,tx,List.of(),result.path("providerCalls").asInt(),result.at("/executionCounts/completedClauseEvaluations").asInt());
            dispatches.set(i,new ExplicitAlphaMissenseDispatcher.CandidateDispatch(o.candidate().getCandidateId(),List.of(attempt)));
            run.withArray("candidates").add(f.mapper.valueToTree(dispatches.get(i))); application(i,prep.snapshot());
        }
        run.set("bundles",new AcmgBusinessArtifacts(f.mapper).bundles(new ExplicitAlphaMissenseDispatcher.DispatchResult(null,dispatches,2,2),List.of("ACMG_AMP_2015:PP3","ACMG_AMP_2015:BP4")));
        reseal();
    }
}
