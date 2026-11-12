package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Storage contract only: deliberately fake source check, never real scientific adoption. */
class DeNovoCandidateRepositoryTest {
    @TempDir Path root;
    final ObjectMapper json=new ObjectMapper();
    ObjectNode intake(int count) {
        byte[] source="SYNTHETIC source".getBytes(java.nio.charset.StandardCharsets.UTF_8);String pin=FamilyDeNovoJson.sha256(source);
        var a=json.createObjectNode().put("schemaVersion","denovo-bundle-candidate-intake-v1").put("pmcid","PMC123")
                .put("sourceSha256",pin).put("bundleSha256","b".repeat(64));
        a.putObject("files").putObject("main.xml").put("sha256",pin).put("base64",Base64.getEncoder().encodeToString(source));
        var artifacts=a.putArray("artifacts");for(int i=0;i<count;i++) artifacts.addObject().putObject("binding").put("chunkIndex",i);
        return a;
    }
    ObjectNode fakeCheck(ObjectNode intake) throws Exception {
        var value=json.createObjectNode().put("status","SOURCE_REPLAYED_NOT_SCORED").put("transportKind","REAL_PROVIDER")
                .put("intakeSha256",FamilyDeNovoJson.sha256(json.writeValueAsBytes(intake))).put("modelCalls",0).put("networkCalls",0);
        for(String key:java.util.List.of("pmcid","sourceSha256","bundleSha256")) value.set(key,intake.path(key));return value;
    }
    @Test void idempotentCumulativeReplacementKeepsOldOriginalAndRejectsRollbackConflict() throws Exception {
        var repository=new DeNovoCandidateRepository(root);var one=intake(1);var two=intake(2);
        var first=repository.accept(json.writeValueAsBytes(one),fakeCheck(one),"a".repeat(64));
        assertFalse(first.path("reused").asBoolean());
        assertTrue(repository.accept(json.writeValueAsBytes(one),fakeCheck(one),"a".repeat(64)).path("reused").asBoolean());
        var next=repository.accept(json.writeValueAsBytes(two),fakeCheck(two),"c".repeat(64));
        assertNotEquals(first.path("snapshotSha256"),next.path("snapshotSha256"));
        assertTrue(Files.isRegularFile(root.resolve(first.path("intakeSha256").asText()).resolve("intake.json")));
        assertEquals(1,repository.snapshot().entries().size());
        assertThrows(java.io.IOException.class,()->repository.accept(json.writeValueAsBytes(one),fakeCheck(one),"a".repeat(64)));
        var conflict=two.deepCopy();((ObjectNode)conflict.path("artifacts").get(0)).put("altered",true);
        assertThrows(java.io.IOException.class,()->repository.accept(json.writeValueAsBytes(conflict),fakeCheck(conflict),"a".repeat(64)));
        assertEquals(next.path("snapshotSha256").asText(),new DeNovoCandidateRepository(root).snapshot().sha256());
    }
    @Test void syntheticOrChangedSourceCannotBeAccepted() throws Exception {
        var repository=new DeNovoCandidateRepository(root);var one=intake(1);var check=fakeCheck(one).put("transportKind","SYNTHETIC_TEST");
        assertThrows(java.io.IOException.class,()->repository.accept(json.writeValueAsBytes(one),check,"a".repeat(64)));
        repository.accept(json.writeValueAsBytes(one),fakeCheck(one),"a".repeat(64));
        var changed=one.deepCopy().put("bundleSha256","c".repeat(64));
        assertThrows(java.io.IOException.class,()->repository.accept(json.writeValueAsBytes(changed),fakeCheck(changed),"a".repeat(64)));
        String pin=FamilyDeNovoJson.sha256(json.writeValueAsBytes(one));Files.writeString(root.resolve(pin).resolve("intake.json"),"{}");
        assertThrows(java.io.IOException.class,repository::snapshot);
    }
    @Test void reviewedIntakeCannotBeReplacedByAnOlderUnreviewedOrChangedAudit() throws Exception {
        var old=intake(1);var reviewed=old.deepCopy();
        reviewed.putArray("sourceReviews").addObject().put("originalArtifactSha256","a".repeat(64)).put("fixture","SYNTHETIC");
        DeNovoCandidateRepository.cumulative(old,reviewed);
        DeNovoCandidateRepository.cumulative(reviewed,reviewed.deepCopy());
        assertThrows(java.io.IOException.class,()->DeNovoCandidateRepository.cumulative(reviewed,old));
        var changed=reviewed.deepCopy();((ObjectNode)changed.path("sourceReviews").get(0)).put("fixture","CHANGED");
        assertThrows(java.io.IOException.class,()->DeNovoCandidateRepository.cumulative(reviewed,changed));
    }
}
