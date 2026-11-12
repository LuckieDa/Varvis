package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts;
import org.nbscn.varvis.variantanalysis.provider.Hnf1aFunctionalRuntimeTest;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class FunctionalExperimentReviewsTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final AcmgBusinessArtifacts hashes = new AcmgBusinessArtifacts(mapper);
    ObjectNode run(String label) throws Exception {
        var run=mapper.createObjectNode().put("analysisId","synthetic-functional-analysis").put("runId","synthetic-functional-run").put("syntheticRankingScore",0.12345d);
        run.putArray("candidates").addObject().put("candidateId","synthetic-public-allele")
                .putArray("attempts").addObject().set("functional",new Hnf1aFunctionalRuntimeTest().result(label));
        return run;
    }
    FunctionalExperimentReviews service(ObjectNode run) { return new FunctionalExperimentReviews(mapper,root,(a,r)->run.deepCopy(),(a,op)->op.get()); }
    FunctionalExperimentReviews.Input input(ObjectNode run,String decision,String previous,String rationale) {
        var f=run.at("/candidates/0/attempts/0/functional");
        return new FunctionalExperimentReviews.Input(f.at("/evaluation/evaluationId").asText(),hashes.requestHash(f.path("factPackage")),f.path("profileSha256").asText(),previous,
                decision,"reviewer-synthetic-qa","SYSTEM_ADMINISTRATOR",rationale);
    }
    @Test void pendingAcceptedRejectedHistorySurvivesReconstructionAndIdenticalRetry() throws Exception {
        var run=run("P112L"); var original=run.deepCopy(); var reviews=service(run);
        var pending=input(run,"NONE",null,"Engineering test: request more material; no user adoption.");
        var first=reviews.submit("a","r",pending);
        assertEquals(first,reviews.submit("a","r",pending));
        assertEquals("IN_PROGRESS",first.at("/reviewRecord/status").asText());
        var accepted=input(run,"ACCEPTED",first.at("/reviewRecord/reviewRecordId").asText(),"Synthetic test actor accepts the calculated evidence for persistence testing only.");
        var second=reviews.submit("a","r",accepted);
        var rebuilt=service(run);
        assertEquals(second,rebuilt.submit("a","r",accepted));
        assertEquals(2,rebuilt.read("a","r").path("records").size());
        var prettyMapper=mapper.copy().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
        var prettyRead=new FunctionalExperimentReviews(prettyMapper,root,(a,r)->run.deepCopy(),(a,op)->op.get());
        assertEquals(rebuilt.read("a","r"),prettyRead.read("a","r"));
        assertEquals(original,rebuilt.export("a","r").path("calculation"));
        var third=rebuilt.submit("a","r",input(run,"REJECTED",second.at("/reviewRecord/reviewRecordId").asText(),"Engineering test: decline adoption without changing calculation."));
        assertEquals("REJECTED",third.at("/reviewRecord/decision").asText());
        assertEquals(3,service(run).read("a","r").path("records").size());
        assertEquals(original,run);assertEquals("MET",run.at("/candidates/0/attempts/0/functional/evaluation/evaluationResult/status").asText());
    }
    @Test void stalePredecessorCrossRunChangedMaterialAndPolicyCannotReuseAcceptance() throws Exception {
        var run=run("P112L");var service=service(run);var in=input(run,"ACCEPTED",null,"Synthetic test actor only.");
        service.submit("a","r",in);
        assertThrows(ResponseStatusException.class,()->service.submit("a","r",input(run,"REJECTED",null,"Stale form.")));
        var changedFact=new FunctionalExperimentReviews.Input(in.evaluationId(),"0".repeat(64),in.profileSha256(),null,in.decision(),in.actorId(),in.role(),in.rationale());
        assertThrows(ResponseStatusException.class,()->service.submit("a","r",changedFact));
        var changedPolicy=new FunctionalExperimentReviews.Input(in.evaluationId(),in.factPackageSha256(),"0".repeat(64),null,in.decision(),in.actorId(),in.role(),in.rationale());
        assertThrows(ResponseStatusException.class,()->service.submit("a","r",changedPolicy));
        assertEquals(0,service.read("a","another-run").path("records").size());
        var old=run.deepCopy();((ObjectNode)old.at("/candidates/0/attempts/0/functional")).put("profileSha256","1".repeat(64));
        assertThrows(ResponseStatusException.class,()->service(old).submit("a","old",input(old,"ACCEPTED",null,"Cannot newly adopt superseded policy.")));
    }
    @Test void unevaluatedNormalOrConflictingRecordCannotBeAccepted() throws Exception {
        for(String label:new String[]{"Q175R","D526N"}) {
            var r=run(label);assertThrows(ResponseStatusException.class,()->service(r).submit("a",label,input(r,"ACCEPTED",null,"No unsafe normal adoption.")));
            assertEquals("IN_PROGRESS",service(r).submit("a",label,input(r,"NONE",null,"Required mechanism review remains outstanding.")).at("/reviewRecord/status").asText());
        }
    }
    @Test void changedSavedResultOrCorruptReviewIsRejectedAndLegacyRunRemainsReadable() throws Exception {
        var r=run("P112L");service(r).submit("a","r",input(r,"NONE",null,"Test pending."));
        var changed=r.deepCopy().put("unrelatedSavedChange",true);
        assertThrows(ResponseStatusException.class,()->service(changed).read("a","r"));
        var legacy=mapper.createObjectNode().putArray("candidates");
        ObjectNode empty=mapper.createObjectNode().set("candidates",legacy);
        assertEquals(0,service(empty).read("old","legacy").path("records").size());
        try(var paths=Files.walk(root,2)) {
            var file=paths.filter(p->p.toString().endsWith(".json")).findFirst().orElseThrow();
            Files.writeString(file,"{}");
        }
        assertThrows(ResponseStatusException.class,()->service(r).read("a","r"));
    }
    @Test void metWithConflictOrUnknownApplicabilityCannotBeAdopted() throws Exception {
        for (String field : new String[]{"conflict", "criterionApplicability"}) {
            var r = run("P112L");
            var disposition = (ObjectNode) r.at("/candidates/0/attempts/0/functional/evaluation/" + field);
            disposition.put("status", field.equals("conflict") ? "PRESENT" : "UNKNOWN");
            if (field.equals("conflict")) disposition.putArray("conflictIds").add("conflict-sha256-" + "1".repeat(64));
            assertThrows(ResponseStatusException.class, () -> service(r).submit("a", field, input(r,"ACCEPTED",null,"Synthetic conflict gate test.")));
        }
    }
    @Test void changedFactsCannotBorrowCurrentProfileIdentity() throws Exception {
        var r = run("P112L");
        ((ObjectNode) r.at("/candidates/0/attempts/0/functional/factPackage")).put("version", "synthetic-changed-material");
        assertThrows(ResponseStatusException.class, () -> service(r).submit("a","r",input(r,"ACCEPTED",null,"Synthetic material identity mismatch.")));
    }
    @Test void missingPredecessorInTemporaryFixtureBlocksReadAndAppend() throws Exception {
        var r = run("P112L"); var reviews = service(r);
        var first = reviews.submit("a","r",input(r,"NONE",null,"Synthetic first event."));
        var second = reviews.submit("a","r",input(r,"ACCEPTED",first.at("/reviewRecord/reviewRecordId").asText(),"Synthetic second event."));
        var third = reviews.submit("a","r",input(r,"REJECTED",second.at("/reviewRecord/reviewRecordId").asText(),"Synthetic third event."));
        // Only delete this test's @TempDir fixture, never the retained real review store.
        try (var paths = Files.walk(root, 2)) {
            var middle = paths.filter(p -> p.getFileName().toString().equals(second.at("/reviewRecord/reviewRecordId").asText()+".json")).findFirst().orElseThrow();
            assertTrue(middle.normalize().startsWith(root.normalize())); Files.delete(middle);
        }
        assertThrows(ResponseStatusException.class, () -> service(r).read("a","r"));
        assertThrows(ResponseStatusException.class, () -> service(r).submit("a","r",input(r,"NONE",third.at("/reviewRecord/reviewRecordId").asText(),"Broken chain cannot advance.")));
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="varvis.functional.saved-proof",matches=".+")
    void retainedPublishedRunAndReviewsRemainReadOnlyUnderCorrectedProfile() throws Exception {
        Path proof = Path.of(System.getProperty("varvis.functional.saved-proof")).toAbsolutePath().normalize();
        assertEquals("network-check-288cad1f-cdb3-4a74-b58f-da89a8f0d568", proof.getFileName().toString());
        byte[] bytes = Files.readAllBytes(proof.resolve("evaluation-post.json"));
        assertEquals("ded64c021194c8f88fc5a056e4879b08ca97413a478be7bc4befcbb989aa5d2d", org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
        var preservedMapper = mapper.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        var calculation = (ObjectNode) preservedMapper.readTree(bytes);
        var exported = preservedMapper.readTree(Files.readAllBytes(proof.resolve("review-export.json")));
        String analysis = calculation.path("analysisId").asText(), runId = calculation.path("runId").asText();
        Path store = proof.getParent().resolve("jobs/functional-experiment-reviews");
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var reviews = new FunctionalExperimentReviews(preservedMapper,store,(a,b)-> {
            assertEquals(analysis,a); assertEquals(runId,b); reads.incrementAndGet(); return calculation.deepCopy();
        },(a,op)->op.get());
        var result = reviews.export(analysis, runId);
        assertEquals(exported.path("records"), result.path("records"));
        assertEquals(exported.path("calculation"), result.path("calculation"));
        assertNotEquals(exported.path("currentProfileSha256"), result.path("currentProfileSha256"));
        var functional = calculation.path("candidates").get(0).path("attempts").get(0).path("functional");
        var stale = new FunctionalExperimentReviews.Input(functional.at("/evaluation/evaluationId").asText(), hashes.requestHash(functional.path("factPackage")),
                functional.path("profileSha256").asText(), null,"ACCEPTED","reviewer-synthetic-premerge","SYSTEM_ADMINISTRATOR","Synthetic old-profile gate test; must not write.");
        assertThrows(ResponseStatusException.class,()->reviews.submit(analysis, runId, stale));
        var rebuilt = new FunctionalExperimentReviews(preservedMapper,store,(a,b)->calculation.deepCopy(),(a,op)->op.get());
        assertEquals(result, rebuilt.export(analysis, runId));
        assertTrue(reads.get() >= 2);
        assertArrayEquals(bytes, Files.readAllBytes(proof.resolve("evaluation-post.json")));
    }
}
