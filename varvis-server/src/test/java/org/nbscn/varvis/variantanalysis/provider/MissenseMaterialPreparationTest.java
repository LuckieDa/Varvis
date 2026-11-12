package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

/** SYNTHETIC / TEST_ONLY, no evaluation/runtime factory during preparation. */
class MissenseMaterialPreparationTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();
    MissensePreparationFixture fixture(boolean pm5) throws Exception { return new MissensePreparationFixture(temporary.resolve("input"), new Ps1TestCatalog(mapper), pm5); }
    static Stream<Arguments> catalogFaults() {
        return Stream.of(false,true).flatMap(pm5 -> Stream.of("UNKNOWN_SOURCE","EMPTY_SOURCES","MISSING_SOURCES","INVALID_SOURCE","BLANK_SOURCE","MISSING_RELATIONSHIP","BLANK_RELATIONSHIP")
                .map(fault -> Arguments.of(pm5,fault)));
    }
    @ParameterizedTest @MethodSource("catalogFaults")
    void catalogEntryMustBindPinnedSourcesAndRelationship(boolean pm5,String fault) throws Exception {
        var f=fixture(pm5); var catalog=(ObjectNode)mapper.readTree(f.root.resolve("catalog.json").toFile());
        var entry=(ObjectNode)catalog.at("/entries/0");
        switch(fault) {
            case "UNKNOWN_SOURCE" -> entry.putArray("sourceIds").add("MISSING_SOURCE");
            case "EMPTY_SOURCES" -> entry.putArray("sourceIds");
            case "MISSING_SOURCES" -> entry.remove("sourceIds");
            case "INVALID_SOURCE" -> entry.putArray("sourceIds").add(1);
            case "BLANK_SOURCE" -> entry.putArray("sourceIds").add(" ");
            case "MISSING_RELATIONSHIP" -> entry.remove("baseRuleRelationship");
            default -> entry.put("baseRuleRelationship"," ");
        }
        f.manifest.set("catalog",f.file("catalog.json",catalog));
        Path out=temporary.resolve("catalog-"+(pm5?"PM5":"PS1")+"-"+fault);
        var result=f.prepare(out);
        assertFalse(result.ready());
        assertBlockedReports(out,fault.endsWith("RELATIONSHIP")?"CATALOG_BASE_RULE_RELATIONSHIP_MISSING":"CATALOG_ENTRY_SOURCE_INVALID");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void missingRegistryReturnsStructuredBlock(boolean pm5) throws Exception {
        var f=missingRegistry(pm5); Path out=temporary.resolve("missing-registry-method-"+(pm5?"PM5":"PS1"));
        var result=assertDoesNotThrow(()->f.prepare(out));
        assertFalse(result.ready()); assertEquals("REGISTRY_RESOURCE_UNAVAILABLE_OR_INVALID",result.report().path("reasonCode").asText());
        assertBlockedReports(out,"REGISTRY_RESOURCE_UNAVAILABLE_OR_INVALID");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void missingRegistryCliLeavesStructuredBlock(boolean pm5) throws Exception {
        var f=missingRegistry(pm5); Path out=temporary.resolve("missing-registry-cli-"+(pm5?"PM5":"PS1"));
        assertEquals(2,cli(f,out)); assertBlockedReports(out,"REGISTRY_RESOURCE_UNAVAILABLE_OR_INVALID");
    }
    MissensePreparationFixture missingRegistry(boolean pm5) throws Exception {
        var f=fixture(pm5);
        f.replace(0,"policy",p->((ObjectNode)p.path("registryBinding")).put("registryClasspathResource","/acmg/rule-registry-v2/closeout-nonexistent.json"));
        return f;
    }
    void assertBlockedReports(Path out,String reason) throws Exception {
        assertTrue(Files.isRegularFile(out.resolve("preparation-report.json")),"structured preparation report");
        assertTrue(Files.isRegularFile(out.resolve("artifact-manifest.json")),"artifact manifest");
        var report=mapper.readTree(out.resolve("preparation-report.json").toFile());
        assertEquals("BLOCKED",report.path("status").asText()); assertEquals(reason,report.path("reasonCode").asText());
        assertFalse(Files.exists(out.resolve("bundle.json"))); assertFalse(Files.exists(out.resolve(".release-validation.tmp")));
        String archive=System.getProperty("varvis.missense.preparation.artifacts");
        if(archive!=null){Path dest=Path.of(archive).resolve("blocked").resolve(out.getFileName());Files.createDirectories(dest);
            for(String file:List.of("preparation-report.json","artifact-manifest.json"))Files.copy(out.resolve(file),dest.resolve(file));}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void externalApprovalDeterministicReleaseAndMaterialChange(boolean pm5) throws Exception {
        var f = fixture(pm5); Path draft=temporary.resolve("draft"); var result=f.prepare(draft);
        assertFalse(result.ready()); assertEquals("EXTERNAL_APPROVAL_MISSING", result.report().path("reasonCode").asText());
        assertFalse(Files.exists(draft.resolve("bundle.json"))); assertTrue(mapper.readTree(draft.resolve("candidate.json").toFile()).path("draft").asBoolean());
        f.approve(draft); var first=f.prepare(temporary.resolve("first")); var second=f.prepare(temporary.resolve("second"));
        assertTrue(first.ready(), first.report().toPrettyString()); assertTrue(second.ready());
        assertEquals(first.report(),second.report());
        assertArrayEquals(Files.readAllBytes(temporary.resolve("first/bundle.json")),Files.readAllBytes(temporary.resolve("second/bundle.json")));
        assertEquals(0,f.fixture.discoveryCalls); assertEquals(0,f.fixture.annotationCalls); assertEquals(0,f.fixture.spliceCalls);
        f.replace(0,"material",m->m.put("version","2"));
        var stale=f.prepare(temporary.resolve("stale")); assertFalse(stale.ready());
        assertEquals("EXTERNAL_APPROVAL_BINDING_MISMATCH",stale.report().path("reasonCode").asText()); assertFalse(Files.exists(temporary.resolve("stale/bundle.json")));
        f.approve(temporary.resolve("stale")); var changed=f.prepare(temporary.resolve("changed")); assertTrue(changed.ready());
        assertNotEquals(first.report().path("bundleSha256"),changed.report().path("bundleSha256"));
    }
    @ParameterizedTest @ValueSource(strings={"RAW_BYTES","PIN","SCOPE","MODEL","DUPLICATE_REFERENCE","SCV","RNA_NORMAL","PROOF","MATRIX_ROW","MATRIX_ASYMMETRIC","REVIEW","PROFILE","DUPLICATE_BACKGROUND"})
    void staticNegativesDoNotPublish(String fault) throws Exception {
        var f=fixture(true); String expected;
        switch(fault) {
            case "RAW_BYTES" -> { Files.writeString(f.root.resolve("source.json")," ",StandardOpenOption.APPEND); expected="INPUT_RAW_SHA_MISMATCH"; }
            case "PIN" -> { f.replace(0,"policy",p->p.put("nativeModelSha256","a".repeat(64))); expected="DECLARED_PIN_MISMATCH_nativeModelSha256"; }
            case "SCOPE" -> { f.replace(0,"material",m->((ObjectNode)m.path("scope")).put("mondoId","WRONG")); expected="MISSENSE_MATERIAL_SCOPE_MISMATCH"; }
            case "MODEL" -> { f.replace(0,"nativeModel",m->m.put("transcript","ENST00000647020.2")); expected="MODEL_BACKGROUND_MISMATCH"; }
            case "DUPLICATE_REFERENCE" -> { f.replace(0,"referenceAudit",a->a.withArray("references").add(a.at("/references/0").deepCopy())); expected="MISSENSE_REFERENCE_AUDIT_IDENTITY_INVALID"; }
            case "SCV" -> { f.replace(0,"referenceAudit",a->((ObjectNode)a.at("/references/0")).put("scv","SCV1")); expected="MISSENSE_REFERENCE_AUDIT_IDENTITY_INVALID"; }
            case "RNA_NORMAL" -> { f.replace(0,"rnaReview",a->a.put("normalRnaClaimed",true)); expected="MISSENSE_RNA_REVIEW_MISSING"; }
            case "PROOF" -> { f.replace(0,"spliceAnnotationProof",a->((ObjectNode)a.at("/exons/0")).put("genomicEnd",119)); expected="MISSENSE_SPLICE_ANNOTATION_EQUIVALENCE_MISSING"; }
            case "MATRIX_ROW" -> { f.replace(0,"substitutionMatrix",a->((ObjectNode)a.path("matrix")).remove("A")); expected="MISSENSE_SUBSTITUTION_MATRIX_INVALID"; }
            case "MATRIX_ASYMMETRIC" -> { f.replace(0,"substitutionMatrix",a->((ObjectNode)a.at("/matrix/E")).put("D",79)); expected="MISSENSE_SUBSTITUTION_MATRIX_INVALID"; }
            case "REVIEW" -> { f.replace(0,"referenceAudit",a->a.remove("reviewId")); expected="MISSENSE_REFERENCE_AUDIT_MISSING"; }
            case "PROFILE" -> { var profile=(ObjectNode)mapper.readTree(f.root.resolve("profile.json").toFile()); profile.put("version","WRONG"); f.manifest.set("comparatorProfile",f.file("profile.json",profile)); expected="COMPARATOR_PROFILE_MISMATCH"; }
            default -> { ((ArrayNode)f.manifest.path("backgrounds")).add(f.manifest.at("/backgrounds/0").deepCopy()); expected="BACKGROUND_DUPLICATE"; }
        }
        Path out=temporary.resolve("blocked"); var result=f.prepare(out);
        assertFalse(result.ready()); assertEquals(expected,result.report().path("reasonCode").asText(),result.report().toPrettyString());
        assertFalse(Files.exists(out.resolve("bundle.json"))); assertFalse(Files.exists(out.resolve(".release-validation.tmp")));
    }
    @ParameterizedTest @ValueSource(strings={"DUPLICATE_KEY","TRAILING","FILE_LIMIT","BACKGROUND_LIMIT","REFERENCE_LIMIT","WRONG_APPROVAL"})
    void strictInputsAndLimits(String fault) throws Exception {
        var f=fixture(false); String expected="INPUT_JSON_INVALID_OR_DUPLICATE_KEY";
        if(fault.equals("BACKGROUND_LIMIT")) {var a=f.manifest.withArray("backgrounds"); while(a.size()<33)a.add(a.get(0).deepCopy()); expected="BACKGROUND_COUNT_INVALID";}
        if(fault.equals("REFERENCE_LIMIT")) {f.replace(0,"referenceAudit",a->{var refs=a.withArray("references"); while(refs.size()<257)refs.add(refs.get(0).deepCopy());}); expected="REFERENCE_COUNT_INVALID";}
        if(fault.equals("WRONG_APPROVAL")) {Path draft=temporary.resolve("draft"); f.prepare(draft); f.approve(draft); var a=(ObjectNode)mapper.readTree(f.root.resolve("approval.json").toFile()); a.put("candidateSha256","a".repeat(64)); f.manifest.set("approval",f.file("approval.json",a)); expected="EXTERNAL_APPROVAL_BINDING_MISMATCH";}
        f.save();
        if(fault.equals("DUPLICATE_KEY"))Files.writeString(f.manifestPath,"{\"a\":1,\"a\":2}");
        if(fault.equals("TRAILING"))Files.writeString(f.manifestPath," {}",StandardOpenOption.APPEND);
        if(fault.equals("FILE_LIMIT")){Files.writeString(f.manifestPath," ".repeat(4_000_001));expected="INPUT_SIZE_LIMIT";}
        Path out=temporary.resolve("blocked"); var result=new MissenseMaterialPreparation().prepare(f.manifestPath,f.pin(),out);
        assertFalse(result.ready()); assertEquals(expected,result.report().path("reasonCode").asText()); assertFalse(Files.exists(out.resolve("bundle.json")));
        assertThrows(IllegalArgumentException.class,()->new MissenseMaterialPreparation().prepare(f.manifestPath,f.pin(),out));
    }
    @Test void realCliDraftAndRelease() throws Exception {
        var f=fixture(true); Path draft=temporary.resolve("cli-draft"),release=temporary.resolve("cli-release");
        assertEquals(2,cli(f,draft)); assertFalse(Files.exists(draft.resolve("bundle.json")));
        f.approve(draft); assertEquals(0,cli(f,release)); assertTrue(Files.exists(release.resolve("bundle.json")));
        String archive=System.getProperty("varvis.missense.preparation.artifacts");
        if(archive!=null) {Path dest=Path.of(archive).resolve("cli"); Files.createDirectories(dest);
            try(var paths=Files.walk(f.root)){for(Path p:paths.toList()){Path q=dest.resolve("inputs").resolve(f.root.relativize(p)); if(Files.isDirectory(p))Files.createDirectories(q);else Files.copy(p,q);}}
            for(Path dir:List.of(draft,release)){Path d=dest.resolve(dir.getFileName());Files.createDirectories(d);try(var files=Files.list(dir)){for(Path p:files.toList())Files.copy(p,d.resolve(p.getFileName()));}}
        }
    }
    int cli(MissensePreparationFixture f,Path out) throws Exception {
        String javaCommand=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        var command=List.of(javaCommand,"-cp",System.getProperty("java.class.path"),MissenseMaterialPreparation.class.getName(),"--manifest",f.manifestPath.toString(),"--manifest-sha256",f.pin(),"--output",out.toString());
        Path log=temporary.resolve(out.getFileName()+".log"); var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(process.waitFor(45,java.util.concurrent.TimeUnit.SECONDS)); int exit=process.exitValue();
        String archive=System.getProperty("varvis.missense.preparation.artifacts"); if(archive!=null){Path d=Path.of(archive).resolve("cli-commands");Files.createDirectories(d);Files.copy(log,d.resolve(log.getFileName()));mapper.writeValue(d.resolve(out.getFileName()+"-command.json").toFile(),Map.of("command",command,"exitCode",exit));}
        return exit;
    }
}
