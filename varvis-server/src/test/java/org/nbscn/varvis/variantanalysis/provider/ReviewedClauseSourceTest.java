package org.nbscn.varvis.variantanalysis.provider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class ReviewedClauseSourceTest {
    @ParameterizedTest @ValueSource(strings={"embedded","changed","explicitConflict","missing"})
    void ordinaryPinnedAnnotationMustMatchSavedSelection(String kind) throws Exception {
        var x=new ReviewedClauseSourceFixtures(root);var input=x.inputs.get(0);String old=input.transcript().source().artifactSha256();
        var file=x.ledgerFiles.get(old);ObjectNode ledger=(ObjectNode)x.f.mapper.readTree(java.nio.file.Files.readAllBytes(file));
        ObjectNode record=ledger.withObject("/records/transcript");record.remove("annotationSourceSha256");
        if(!kind.equals("missing"))record.set("annotation",x.annotation(0).deepCopy());
        if(kind.equals("changed"))record.withObject("/annotation").put("aminoAcids","K/Q");
        if(kind.equals("explicitConflict"))record.put("annotationSourceSha256","0".repeat(64));
        byte[] bytes=x.f.mapper.writeValueAsBytes(ledger);String pin=PredictionCalibration.sha256(bytes);java.nio.file.Files.write(file,bytes);
        x.ledgerFiles.remove(old);x.ledgerFiles.put(pin,file);
        JsonNode inputTree=x.f.mapper.valueToTree(input);
        replaceLedgerPin(inputTree,old,pin);
        x.inputs.set(0,x.f.mapper.treeToValue(inputTree,org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.ExplicitInput.class));
        x.ledgers.clear();for(var e:x.ledgerFiles.entrySet())x.ledgers.add(org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.load(x.f.mapper,e.getValue(),e.getKey()));
        x.reviewAll();var prepared=new SavedPredictionContextBridge().prepare(x.f.mapper,x.saved(),x.supply());
        var entry=prepared.candidateEntries(input.candidateId()).get(0);
        if(kind.equals("embedded"))assertEquals("VERIFIED_FOR_S1",entry.path("preparationStatus").asText(),entry.toPrettyString());
        else assertEquals("CONTEXT_TRANSCRIPT_RECORD_DIGEST_CONFLICT",entry.at("/blockers/0/reasonCode").asText(),entry.toPrettyString());
        assertEquals("VERIFIED_FOR_S1",prepared.candidateEntries(x.inputs.get(1).candidateId()).get(0).path("preparationStatus").asText());
    }
    private static void replaceLedgerPin(JsonNode node,String old,String replacement) {
        if(node instanceof ObjectNode object && old.equals(object.path("artifactSha256").asText()))object.put("artifactSha256",replacement);
        for(JsonNode child:node)replaceLedgerPin(child,old,replacement);
    }
    @TempDir Path root;
    @Test void nativeOrdinaryTwoContextsRemainReadOnly() throws Exception {
        var fixture=new ReviewedClauseSourceFixtures(root);
        var input=fixture.saved();
        for(var c:input.settings().path("candidates")) assertEquals("",SavedPredictionApplicationInputs.candidateIssue(fixture.f.mapper,input,c));
        var receipts=fixture.assess();
        assertEquals("PRECONDITIONS_PASS",receipts.get(0).path("status").asText(),receipts.get(0).toPrettyString());
        assertEquals("NO_SUPPORT",receipts.get(2).path("status").asText(),receipts.get(2).toPrettyString());
        export(fixture,"positive-and-not-met",receipts);
    }

    @Test void declaredAdCannotReuseSavedArContextAfterAllPinsAreResealed() throws Exception {
        var x = new ReviewedClauseSourceFixtures(root);
        var baseline = x.assess();
        assertEquals("PRECONDITIONS_PASS", baseline.get(0).path("status").asText());
        assertEquals("NO_SUPPORT", baseline.get(2).path("status").asText());
        replaceDeclaredMoi(x, x.f.mapper.getNodeFactory().textNode("AD"));
        assertDeclaredMoiRejected(x);
    }

    @ParameterizedTest @ValueSource(strings={"missing", "null", "number", "boolean", "array", "object", "empty", "blank", "unsupported", "lowercase", "padded"})
    void invalidDeclaredMoiCannotBorrowTheSavedContext(String kind) throws Exception {
        var x = new ReviewedClauseSourceFixtures(root);
        com.fasterxml.jackson.databind.JsonNode value = switch (kind) {
            case "missing" -> null;
            case "null" -> x.f.mapper.nullNode();
            case "number" -> x.f.mapper.getNodeFactory().numberNode(1);
            case "boolean" -> x.f.mapper.getNodeFactory().booleanNode(true);
            case "array" -> x.f.mapper.createArrayNode().add("AR");
            case "object" -> x.f.mapper.createObjectNode().put("moi", "AR");
            case "empty" -> x.f.mapper.getNodeFactory().textNode("");
            case "blank" -> x.f.mapper.getNodeFactory().textNode("   ");
            case "lowercase" -> x.f.mapper.getNodeFactory().textNode("ar");
            case "padded" -> x.f.mapper.getNodeFactory().textNode(" AR ");
            default -> x.f.mapper.getNodeFactory().textNode("UNSUPPORTED_PRIVATE_MOI_TEXT");
        };
        replaceDeclaredMoi(x, value);
        assertDeclaredMoiRejected(x);
    }

    @ParameterizedTest @ValueSource(strings={"AR", "AUTOSOMAL_RECESSIVE"})
    void existingRecessiveSpellingsKeepTheNativeSourceEligible(String moi) throws Exception {
        var x = new ReviewedClauseSourceFixtures(root);
        replaceDeclaredMoi(x, x.f.mapper.getNodeFactory().textNode(moi));
        var out = x.assess();
        assertEquals("PASS", out.get(0).at("/stages/0/status").asText());
        assertEquals("PRECONDITIONS_PASS", out.get(0).path("status").asText());
        assertEquals("NO_SUPPORT", out.get(2).path("status").asText());
    }

    private static void replaceDeclaredMoi(ReviewedClauseSourceFixtures x, com.fasterxml.jackson.databind.JsonNode moi) throws Exception {
        var f = x.f;
        var publication = f.docs.get("publication").deepCopy();
        var candidates = f.docs.get("run").path("candidates").deepCopy();
        var bundles = f.docs.get("run").path("bundles").deepCopy();
        var settings = f.settings.deepCopy();
        int matches = 0;
        for (var entry : f.docs.get("request").at("/declaration/hypotheses")) {
            if (!f.candidate(0).path("candidateId").equals(entry.at("/hypothesis/candidateId"))
                    || !"TEST_ONLY_H0".equals(entry.at("/hypothesis/hypothesisId").asText())) continue;
            ObjectNode hypothesis = (ObjectNode) entry.path("hypothesis");
            if (moi == null) hypothesis.remove("moi"); else hypothesis.set("moi", moi);
            matches++;
        }
        assertEquals(1, matches);
        f.docs.get("private").set("declaration", f.docs.get("request").path("declaration").deepCopy());
        x.reseal();
        assertEquals("", x.saved().issue()); // Cross-artifact hashes and independent byte pins remain valid.
        assertEquals(publication, f.docs.get("publication"));
        assertEquals(candidates, f.docs.get("run").path("candidates"));
        assertEquals(bundles, f.docs.get("run").path("bundles"));
        assertEquals(settings, f.settings); // Includes the separately reviewed AR contexts.
    }

    private static void assertDeclaredMoiRejected(ReviewedClauseSourceFixtures x) throws Exception {
        var out = x.assess();
        assertEquals("PASS", out.get(2).at("/stages/0/status").asText());
        assertEquals("NO_SUPPORT", out.get(2).path("status").asText());
        assertEquals("SOURCE_CLAUSE_NOT_MET", out.get(2).path("reasonCode").asText());
        for (int i : List.of(0, 1)) {
            assertEquals("BLOCKED", out.get(i).at("/stages/0/status").asText(),
                    "Actual verdict=" + out.get(i).path("status").asText() + "; reason=" + out.get(i).path("reasonCode").asText());
            assertEquals("CLAUSE_DECLARED_BACKGROUND_MISMATCH", out.get(i).at("/stages/0/reasons/0/code").asText());
            assertEquals("CLAUSE_DECLARED_BACKGROUND_MISMATCH", out.get(i).path("reasonCode").asText());
        }
        assertFalse(out.toString().contains("UNSUPPORTED_PRIVATE_MOI_TEXT"));
    }

    @ParameterizedTest @ValueSource(strings={"operation","parent","request","private","profile","publication","order","naked","rank"})
    void rejectsSharedSourceMismatch(String kind) throws Exception {
        var x=new ReviewedClauseSourceFixtures(root); var f=x.f;
        switch(kind) {
            case "operation" -> f.docs.get("request").withObject("/declaration").put("operation","APPLY_REVIEWED_PREDICTION_CLAUSES");
            case "parent" -> f.docs.get("run").put("sourceRunId","invented-parent");
            case "request" -> f.docs.get("request").put("executionProfile","wrong");
            case "private" -> f.docs.get("private").withObject("/declaration").put("dataClass","WRONG");
            case "profile" -> f.docs.get("run").withObject("/executionProfile").put("profileVersion","wrong");
            case "publication" -> f.docs.get("publication").put("inputAssembly","GRCh37");
            case "order" -> f.docs.get("run").withArray("candidateIds").remove(0);
            case "naked" -> f.docs.get("run").remove("bundles");
            case "rank" -> ((ObjectNode)f.docs.get("run").at("/candidates/0")).put("rank",1);
            default -> throw new AssertionError(kind);
        }
        x.reseal(); var in=SavedPredictionApplicationInputs.verifyReviewedClause(f.mapper,f.artifacts,f.settings,f.roots);
        assertFalse(in.issue().isEmpty()); assertEquals(1,new PredictionApplicationEligibility(f.mapper).assess(in).size());
    }

    @Test void pinsParsingAndTreesRemainIndependent() throws Exception {
        var x=new ReviewedClauseSourceFixtures(root); var f=x.f;
        var sources=new LinkedHashMap<>(f.artifacts);
        sources.put("run",new SavedPredictionApplicationInputs.Artifact("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),f.docs.get("run")));
        assertEquals("SOURCE_BYTES_DIGEST_MISMATCH",SavedPredictionApplicationInputs.verifyReviewedClause(f.mapper,sources,f.settings,f.roots).issue());
        sources.put("run",new SavedPredictionApplicationInputs.Artifact(f.mapper.writeValueAsBytes(f.docs.get("run")),f.mapper.createObjectNode()));
        assertEquals("SOURCE_TREE_BYTES_MISMATCH",SavedPredictionApplicationInputs.verifyReviewedClause(f.mapper,sources,f.settings,f.roots).issue());
        for(String bad:List.of("{\"x\":1,\"x\":2}","{} {}")) {
            sources=new LinkedHashMap<>(f.artifacts); byte[] b=bad.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            sources.put("run",new SavedPredictionApplicationInputs.Artifact(b,f.mapper.createObjectNode()));
            Map<String,String> pins=new LinkedHashMap<>(); f.docs.forEach((k,v)->{try{pins.put(k,PredictionCalibration.sha256(f.mapper.writeValueAsBytes(v)));}catch(Exception e){throw new AssertionError(e);}});
            pins.put("run",PredictionCalibration.sha256(b));
            var roots=new SavedPredictionApplicationInputs.Supply("TEST_ONLY",pins,f.hash.canonicalSha256(SavedPredictionApplicationInputs.semanticSettings(f.settings)));
            assertEquals("SOURCE_JSON_INVALID",SavedPredictionApplicationInputs.verifyReviewedClause(f.mapper,sources,f.settings,roots).issue());
        }
    }

    @ParameterizedTest @ValueSource(strings={"candidate","hypothesis","duplicateBundle","execution","preparation","result","evaluation","atomic","decimal"})
    void candidateCorruptionCannotBlockSibling(String kind) throws Exception {
        var x=new ReviewedClauseSourceFixtures(root); var f=x.f;
        ObjectNode a=(ObjectNode)f.docs.get("run").at("/candidates/0/attempts/0");
        switch(kind) {
            case "candidate" -> a.withObject("/preflight/original").put("candidateId","wrong");
            case "hypothesis" -> a.withObject("/preflight/original").put("hypothesisId","wrong");
            case "duplicateBundle" -> f.docs.get("run").withArray("bundles").add(f.docs.get("run").at("/bundles/0").deepCopy());
            case "execution" -> ((ObjectNode)f.docs.get("run").at("/bundles/0/bundle/execution")).put("changed",true);
            case "preparation" -> a.withObject("/transcriptInput/predictionPreparation").put("preparationId","wrong");
            case "result" -> a.withObject("/transcriptInput/reviewedPrediction").put("resultId","wrong");
            case "evaluation" -> ((ObjectNode)a.at("/criterionEvaluations/0")).put("evaluationId","wrong");
            case "atomic" -> ((ObjectNode)a.at("/atomicEvidence/0")).put("evidenceId","wrong");
            case "decimal" -> a.put("private_secret",0.25);
            default -> throw new AssertionError(kind);
        }
        if(!Set.of("duplicateBundle","execution","decimal").contains(kind)) {
            ObjectNode business=(ObjectNode)f.docs.get("run").at("/bundles/0/bundle");
            business.set("execution",a.path("transcriptInput").deepCopy());
            for(String field:List.of("context","atomicEvidence","criterionEvaluations","blockers","providerCalls","evaluatorCalls")) business.set(field,a.path(field).deepCopy());
            f.digest(business,"bundleId","prediction-business-artifact-sha256-");
        }
        x.reseal(); var input=x.saved();
        assertFalse(SavedPredictionApplicationInputs.candidateIssue(f.mapper,input,f.candidate(0)).isEmpty());
        assertEquals("",SavedPredictionApplicationInputs.candidateIssue(f.mapper,input,f.candidate(1)));
        var out=x.assess(); assertEquals("NO_SUPPORT",out.get(2).path("status").asText(),out.get(2).toPrettyString());
        assertFalse(out.toString().contains("private_secret"));
    }

    @ParameterizedTest @ValueSource(strings={"missing","duplicate","wrongSha","template","adoption","model","compatibility","expired","alreadyApplied","private"})
    void sidecarAndSameEffectGatesAreIndependent(String kind) throws Exception {
        var x=new ReviewedClauseSourceFixtures(root); var f=x.f;
        ObjectNode side=(ObjectNode)f.settings.at("/clauseApplicationSidecars/0");
        switch(kind) {
            case "missing" -> f.settings.withArray("clauseApplicationSidecars").remove(0);
            case "duplicate" -> f.settings.withArray("clauseApplicationSidecars").add(side.deepCopy());
            case "wrongSha" -> side.put("policySha256","a".repeat(64));
            case "template" -> side.withObject("/template").put("version","wrong");
            case "adoption" -> f.settings.withObject("/adoptions").remove("TEST_ONLY_0");
            case "model" -> f.settings.withObject("/modelProofs").remove("TEST_ONLY_0");
            case "compatibility" -> f.settings.withObject("/compatibilities").remove("TEST_ONLY_0");
            case "expired" -> ((ObjectNode)f.settings.path("effectSnapshots").path(f.candidate(0).path("candidateId").asText())).put("validThrough","2020-01-01T00:00:00Z");
            case "alreadyApplied" -> ((ObjectNode)f.settings.path("effectSnapshots").path(f.candidate(0).path("candidateId").asText())).withArray("effects").addObject().put("kind","SAME_EFFECT").put("referenceId","TEST_ONLY").put("sourceSha256","a".repeat(64)).put("equivalence","VERIFIED").put("adoption","ADOPTED");
            case "private" -> side.put("private_patient_text","DO_NOT_EXPORT");
            default -> throw new AssertionError(kind);
        }
        x.reseal(); var out=x.assess(); assertNotEquals("PRECONDITIONS_PASS",out.get(0).path("status").asText());
        assertEquals("NO_SUPPORT",out.get(2).path("status").asText(),out.get(2).toPrettyString()); assertFalse(out.toString().contains("DO_NOT_EXPORT"));
    }

    @Test void immutableIdentitiesAndUnappliedFlags() throws Exception {
        var x=new ReviewedClauseSourceFixtures(root); var original=x.assess(); assertEquals(original,x.assess());
        for(var r:original) { assertEquals("NONE",r.path("appliedStrength").asText()); for(String k:List.of("applicationReady","formalEnabled","criterionEvaluationCreated")) assertFalse(r.path(k).asBoolean()); }
        assertEquals("SOURCE_CLAUSE_NOT_MET",original.get(2).path("reasonCode").asText());
        assertFalse(original.get(0).has("sourceCalibratedStrength"));
        var saved=x.saved(); saved.settings().put("changed",true); assertFalse(saved.settings().has("changed"));
        var view=PredictionClauseSourceView.read(x.f.mapper,saved,x.f.candidate(0),"TEST_ONLY_H0","PP3"); view.json().put("changed",true); assertFalse(view.json().has("changed"));
        x.f.settings.withObject("/adoptions/TEST_ONLY_0").put("version","2"); x.reseal(); var changed=x.assess();
        assertNotEquals(original.get(0).path("assessmentId"),changed.get(0).path("assessmentId"));
    }

    @ParameterizedTest @ValueSource(strings={"sameScore","differentScore","unrelatedTranscript","zero","timeout"})
    void exactMatchingCardinalityAndUnavailableAreNotThresholdDecisions(String kind) throws Exception {
        var x=new ReviewedClauseSourceFixtures(root); var o=x.observations.get(0).observation();
        if(kind.equals("zero")||kind.equals("timeout")) {
            o.putArray("rawRecords"); o.putArray("coordinateRecords");
            ObjectNode a=(ObjectNode)o.path("evidence"); ObjectNode carrier=x.f.mapper.createObjectNode();
            for(String field:List.of("evidenceType","source","resourceExecution","resourceAvailability","queryOutcome","resourceApplicability","measurements")) carrier.set(field,a.path(field).deepCopy());
            carrier.set("queryAllele",x.observations.get(0).context().at("/variant/evidenceQueryAllele")); carrier.put("producedAt",a.at("/provenance/producedAt").asText()).put("sourcePayloadSha256",x.f.hash.canonicalSha256(o.path("rawRecords")));
            carrier.withObject("/source").putArray("sourceRecords"); carrier.putArray("measurements");
            carrier.withObject("/queryOutcome").put("status",kind.equals("zero")?"NO_RECORD":"NOT_QUERIED").put("recordCount",0);
            if(kind.equals("timeout")) { carrier.withObject("/queryOutcome").remove("queryFingerprint"); carrier.withObject("/resourceExecution").put("status","TIMEOUT").put("reasonCode","TEST_ONLY_TIMEOUT"); carrier.putObject("resourceApplicability").put("status","UNKNOWN").put("reasonCode","TEST_ONLY_TIMEOUT"); }
            o.set("evidence",x.f.hash.adapt(x.observations.get(0).context(),carrier));
        } else {
            ObjectNode extra=((ObjectNode)o.at("/rawRecords/0")).deepCopy();
            if(kind.equals("differentScore")) extra.put("rawCsv",extra.path("rawCsv").asText().replace("0.9","0.1"));
            if(kind.equals("unrelatedTranscript")) extra.put("rawCsv",extra.path("rawCsv").asText().replace("ENST000001","ENST999999"));
            x.stamp(extra,2); o.withArray("coordinateRecords").add(extra);
            if(!kind.equals("unrelatedTranscript")) o.withArray("rawRecords").add(extra.deepCopy());
            x.revel.rebindEvidence(x.observations.get(0));
        }
        x.reproduce(); var view=PredictionClauseSourceView.read(x.f.mapper,x.saved(),x.f.candidate(0),"TEST_ONLY_H0","PP3").json();
        String expected=switch(kind) {case "zero"->"REVEL_NO_RECORD"; case "timeout"->"REVEL_RESOURCE_NOT_READY"; case "unrelatedTranscript"->""; default->"REVEL_EXACT_SINGLE_ROW_REQUIRED";};
        assertEquals(expected,view.path("issue").asText(),view.toPrettyString());
        if(kind.equals("unrelatedTranscript")) {assertEquals(2,view.path("coordinateRecords").size());assertEquals("PRECONDITIONS_PASS",x.assess().get(0).path("status").asText());}
        else assertNotEquals("NOT_MET",view.path("sourceClauseOutcome").asText());
    }

    @Test void negativeStrandUsesSameSequenceProofAndFactory() throws Exception {
        var x=new ReviewedClauseSourceFixtures(root,true); var out=x.assess();
        assertEquals("PRECONDITIONS_PASS",out.get(0).path("status").asText(),out.get(0).toPrettyString());
        assertEquals("NO_SUPPORT",out.get(2).path("status").asText());
    }

    @ParameterizedTest @ValueSource(strings={"protein","codon","modelVersion","refAlt","annotation"})
    void savedSequenceAndAnnotationTamperingCannotBecomeApplicable(String kind) throws Exception {
        var x=new ReviewedClauseSourceFixtures(root); var o=x.observations.get(0).observation();
        switch(kind) {
            case "protein" -> o.withObject("/transcriptModel").put("protein","NP_000001.2");
            case "codon" -> o.withObject("/transcriptModel").put("cdsSequence","ATGCAATAA");
            case "modelVersion" -> o.withObject("/transcriptModel").put("modelVersion","2");
            case "refAlt" -> {ObjectNode row=(ObjectNode)o.at("/rawRecords/0");row.put("rawCsv",row.path("rawCsv").asText().replace(",A,G,",",A,T,"));x.stamp(row,1);o.set("coordinateRecords",o.path("rawRecords").deepCopy());x.revel.rebindEvidence(x.observations.get(0));}
            case "annotation" -> x.annotation(0).put("aminoAcids","K/Q");
            default -> throw new AssertionError(kind);
        }
        x.reproduce(); var out=x.assess();assertNotEquals("PRECONDITIONS_PASS",out.get(0).path("status").asText());
        if(kind.equals("annotation")) assertEquals("",SavedPredictionApplicationInputs.candidateIssue(x.f.mapper,x.saved(),x.f.candidate(1))); // Shared publication pin invalidates old S1 reviews.
        else assertEquals("NO_SUPPORT",out.get(2).path("status").asText(),out.get(2).toPrettyString());
    }

    @Test void gn005AdditionalSplicingRequirementIsNotRemovedBySavedProteinMet() throws Exception {
        var x=new ReviewedClauseSourceFixtures(root); ObjectNode p=x.policies.get(0);
        p.put("policyId","TEST_ONLY_BP4_PROTEIN_NOT_GN005_APPROVAL");p.withArray("criterionIds").removeAll().add("ACMG_AMP_2015:BP4");p.withObject("/threshold").put("operator","LE").put("decimal","0.15");
        ObjectNode row=(ObjectNode)x.observations.get(0).observation().at("/rawRecords/0");row.put("rawCsv",row.path("rawCsv").asText().replace("0.9","0.1")); x.stamp(row,1);
        x.observations.get(0).observation().set("coordinateRecords",x.observations.get(0).observation().path("rawRecords").deepCopy()); x.revel.rebindEvidence(x.observations.get(0));x.reproduce();
        var protein=PredictionClauseSourceView.read(x.f.mapper,x.saved(),x.f.candidate(0),"TEST_ONLY_H0","BP4").json(); assertEquals("BENIGN",protein.path("direction").asText());
        // Current application policy explicitly retains GN005 detailed p19's extra check.
        ObjectNode app=p.deepCopy();app.withArray("additionalRequirements").add("GN005_V2_P19_MAXENTSCAN_CRYPTIC_SPLICE_CHECK");
        ObjectNode doc=x.producer.document(app,x.policies.get(1));x.f.settings.set("policies",doc.path("policies"));x.f.settings.set("catalog",doc.path("catalog"));x.policies.set(0,app);
        x.f.settings.putArray("clauseApplicationSidecars");x.application(0,(ObjectNode)x.f.docs.get("run").at("/candidates/0/attempts/0/transcriptInput/predictionPreparation"));x.application(1,(ObjectNode)x.f.docs.get("run").at("/candidates/1/attempts/0/transcriptInput/predictionPreparation"));x.reseal();
        var out=x.assess();assertEquals("PREDICTION_SPECIALIZED_REQUIREMENT_NOT_IMPLEMENTED",out.get(1).path("reasonCode").asText(),out.get(1).toPrettyString());
        export(x,"bp4-additional-requirement",out);
    }

    private static void export(ReviewedClauseSourceFixtures x,String name,List<ObjectNode> receipts) throws Exception {
        String directory=System.getProperty("varvis.clause.test.receipts"); if(directory==null) return;
        Path target=Path.of(directory).resolve(name); java.nio.file.Files.createDirectories(target);
        for(var e:x.f.docs.entrySet()) java.nio.file.Files.write(target.resolve(e.getKey()+".json"),x.f.mapper.writeValueAsBytes(e.getValue()));
        java.nio.file.Files.write(target.resolve("settings.json"),x.f.mapper.writeValueAsBytes(x.f.settings));
        ObjectNode record=x.f.mapper.createObjectNode().put("testOnly",true).put("scientificApproval",false).put("productionProviderCalls",0).put("adapterEvaluatorCalls",0).put("testStore","MOCK_NO_DATABASE");record.set("receipts",x.f.mapper.valueToTree(receipts));
        java.nio.file.Files.write(target.resolve("receipt.json"),x.f.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(record));
    }

    @Test void legacyAdRankingCannotAcceptAnArHypothesis() throws Exception {
        var old=new SavedPredictionContextBridgeFixtures(root);
        var source=SavedPredictionApplicationInputs.contextSource(old.f.mapper,old.saved(),old.f.candidate(0));
        var hypothesis=new org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.PatientDiseaseHypothesis(
                source.candidate().getCandidateId(),"TEST_ONLY_AR","HGNC:101","MONDO:0000101","AR",
                new org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.HypothesisProvenance("TEST_ONLY","1","TEST_ONLY/AR"));
        var binding=old.resolver.resolve(source.candidate(),List.of(hypothesis)).bindings().get(0);
        assertNotEquals("READY",binding.status());
    }

    @ParameterizedTest @ValueSource(strings={"lookup","bundleHypothesis","rawDigest","rawId","lookupContainer","sidecarContainer"})
    void linkedIdentitiesAndContainerTypesAreChecked(String kind) throws Exception {
        var x=new ReviewedClauseSourceFixtures(root); var f=x.f;
        ObjectNode a=(ObjectNode)f.docs.get("run").at("/candidates/0/attempts/0");
        ObjectNode prep=a.withObject("/transcriptInput/predictionPreparation"), result=a.withObject("/transcriptInput/reviewedPrediction");
        if(kind.equals("sidecarContainer")) f.settings.put("clauseApplicationSidecars","private_invalid_container");
        else if(kind.equals("bundleHypothesis")) ((ObjectNode)f.docs.get("run").at("/bundles/0")).put("hypothesisId","TEST_ONLY_H1");
        else if(kind.equals("lookupContainer")) prep.put("lookupRequests",42);
        else if(kind.equals("lookup")) prep.withObject("/criterionLookupIds").put("ACMG_AMP_2015:PP3",f.docs.get("run").at("/candidates/1/attempts/0/transcriptInput/predictionPreparation/criterionLookupIds/ACMG_AMP_2015:PP3").asText());
        else {
            ObjectNode obs=x.observations.get(0).observation(); ObjectNode raw=(ObjectNode)obs.at("/rawRecords/0");
            raw.put(kind.equals("rawId")?"recordId":"rawCsvSha256","wrong");obs.set("coordinateRecords",obs.path("rawRecords").deepCopy());x.revel.rebindEvidence(x.observations.get(0));x.reproduce();
        }
        if(kind.equals("lookup")||kind.equals("lookupContainer")) {
            f.digest(prep,"preparationId","prediction-preparation-sha256-");f.digest(result,"resultId","prediction-bundle-result-sha256-");
            ObjectNode business=(ObjectNode)f.docs.get("run").at("/bundles/0/bundle");business.set("execution",a.path("transcriptInput").deepCopy());f.digest(business,"bundleId","prediction-business-artifact-sha256-");
        }
        x.reseal();var out=x.assess();assertNotEquals("PRECONDITIONS_PASS",out.get(0).path("status").asText());
        assertFalse(out.toString().contains("private_invalid_container"));
        if(!kind.equals("sidecarContainer")) assertEquals("NO_SUPPORT",out.get(2).path("status").asText(),out.get(2).toPrettyString());
    }
}
