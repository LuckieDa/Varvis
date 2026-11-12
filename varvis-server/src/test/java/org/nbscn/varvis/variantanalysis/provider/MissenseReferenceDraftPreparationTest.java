package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** SYNTHETIC / TEST_ONLY; source extraction is upstream of any reviewed materials. */
class MissenseReferenceDraftPreparationTest {
    @Test void upstreamNoConflictReportedDoesNotBecomeAnInventedConflict() throws Exception {
        var f=fixture(0,"NORMAL");((ObjectNode)f.built.at("/comparators/0")).put("conflictState","NO_CONFLICT_REPORTED");f.refresh();
        var out=temporary.resolve("no-reported-conflict");var result=f.prepare(out);assertTrue(result.generated());
        assertFalse(result.report().path("gaps").toString().contains("SOURCE_CONFLICT_REQUIRES_REVIEW"));
        assertTrue(result.report().path("gaps").toString().contains("REFERENCE_SCIENTIFIC_REVIEW_REQUIRED"));
        assertEquals("NO_CONFLICT_REPORTED",json(out.resolve("reference-audit.draft.json")).at("/references/0/observations/0/record/conflictState").asText());
    }
    @TempDir Path temporary;
    final ObjectMapper mapper=new ObjectMapper();
    MissenseReferenceDraftFixture fixture(int background,String scenario)throws Exception{return new MissenseReferenceDraftFixture(temporary.resolve("inputs"),background,scenario);}
    JsonNode json(Path file)throws Exception{return mapper.readTree(file.toFile());}
    @ParameterizedTest @ValueSource(ints={0,1})
    void upstreamBuildFactsVersionsLocatorsPairsAndDeterminism(int background)throws Exception{
        var f=fixture(background,"NORMAL");assertEquals(1,f.discoveryCalls);assertEquals(1,f.annotationCalls);assertEquals("READY",f.built.path("status").asText());
        Path out=temporary.resolve("draft"),again=temporary.resolve("again"); var result=f.prepare(out);assertTrue(result.generated(),result.report().toPrettyString());assertTrue(f.prepare(again).generated());
        for(String name:List.of("reference-audit.draft.json","rna-pairs.draft.json","source-map.json","draft-report.json"))assertArrayEquals(Files.readAllBytes(out.resolve(name)),Files.readAllBytes(again.resolve(name)));
        var draft=json(out.resolve("reference-audit.draft.json"));var ref=draft.at("/references/0");var record=f.built.at("/comparators/0");
        assertEquals(1,draft.path("references").size());assertEquals(2,ref.path("assertions").size());assertEquals(record,ref.at("/observations/0/record"));
        assertEquals(f.annotationSnapshot.path("annotation"),ref.path("annotation"));
        assertEquals(record.at("/submissions/1"),ref.at("/assertions/1/sourceAssertion"));
        assertEquals("SCV000000002.2",ref.at("/assertions/1/sourceAssertion/scv").asText());assertEquals("2026-02-03",ref.at("/assertions/1/sourceAssertion/dateLastEvaluated").asText());
        assertEquals("Uncertain significance",ref.at("/assertions/1/sourceAssertion/classification").asText());
        assertEquals(2,ref.at("/observations/0/record/variantSummaryRecords").size());assertEquals("CONFLICTING",ref.at("/observations/0/record/conflictState").asText());
        assertEquals(background==0?"SAME_AMINO_ACID_CHANGE":"SAME_RESIDUE_DIFFERENT_AMINO_ACID",record.path("proteinRelation").asText());
        var origin=ref.at("/assertions/1/origins/0");assertEquals("/comparators/0/submissions/1",origin.path("jsonPointer").asText());assertEquals(f.manifest.at("/comparatorInput/rawSha256"),origin.path("rawSha256"));
        var pairs=json(out.resolve("rna-pairs.draft.json"));assertEquals(f.target.path("allele"),pairs.at("/pairs/0/targetAllele"));assertEquals(f.annotationSnapshot.path("referenceAllele"),pairs.at("/pairs/0/referenceAllele"));
        assertEquals("NOT_PROVIDED",pairs.at("/pairs/0/rnaEvidenceStatus").asText());assertEquals("UNREVIEWED",draft.path("reviewStatus").asText());
        assertFalse(draft.toString().contains("REVIEWED_SUFFICIENT"));assertFalse(pairs.has("normalRnaClaimed"));assertFalse(Files.exists(out.resolve("bundle.json")));
        assertEquals(f.built,result.report().path("sourceSearch"));assertEquals(1,f.discoveryCalls);assertEquals(1,f.annotationCalls);
        assertThrows(IllegalArgumentException.class,()->f.prepare(out));
        archive("background-"+background,f.root);archive("draft-"+background,out);
        ((ObjectNode)f.built.at("/comparators/0/submissions/0")).put("dateLastEvaluated","2026-03-04");f.refresh();
        var updated=f.prepare(temporary.resolve("changed-source"));assertTrue(updated.generated());assertNotEquals(result.report().path("draftId"),updated.report().path("draftId"));
        archive("changed-background-"+background,f.root);archive("changed-draft-"+background,temporary.resolve("changed-source"));
        receipt("upstream-"+background,mapper.createObjectNode().put("discoveryCalls",f.discoveryCalls).put("annotationCalls",f.annotationCalls).put("publicEvaluateCalls",0).put("originalDraftId",result.report().path("draftId").asText()).put("changedDraftId",updated.report().path("draftId").asText()));
    }
    @ParameterizedTest @ValueSource(strings={"PIN","CANONICAL","CONTEXT","TRANSCRIPT","ALLELE","VARIATION","SOURCE","TARGET_PROTEIN","ASSEMBLY","DUPLICATE_KEY","TRAILING","FILE_LIMIT","CANDIDATE_LIMIT","SCV_LIMIT","ANNOTATION_CONFLICT","REFERENCE_GENE","LOCATOR"})
    void inputIntegrityBlocksWithoutDraft(String fault)throws Exception{
        var f=fixture(0,"NORMAL");String expected;
        switch(fault){
            case "PIN"->{((ObjectNode)f.manifest.path("target")).put("rawSha256","f".repeat(64));expected="INPUT_RAW_SHA_MISMATCH";}
            case "CANONICAL"->{((ObjectNode)f.manifest.path("target")).put("canonicalSha256","f".repeat(64));expected="INPUT_CANONICAL_SHA_MISMATCH";}
            case "CONTEXT"->{f.built.put("contextId","WRONG");f.refresh();expected="COMPARATOR_CONTEXT_MISMATCH";}
            case "TRANSCRIPT"->{f.annotationSnapshot.put("transcript","ENST00000647020.2");f.refresh();expected="REFERENCE_TRANSCRIPT_MISMATCH";}
            case "ALLELE"->{((ObjectNode)f.annotationSnapshot.path("referenceAllele")).put("position",999);f.refresh();expected="REFERENCE_ANNOTATION_NOT_BOUND_TO_COMPARATOR";}
            case "VARIATION"->{f.annotationSnapshot.put("variationId","900");f.refresh();expected="REFERENCE_ANNOTATION_NOT_BOUND_TO_COMPARATOR";}
            case "SOURCE"->{((ObjectNode)f.built.at("/comparators/0/snapshotProvenance")).put("resourceVersion","WRONG");((ObjectNode)f.built.at("/candidateDecisions/0/snapshotProvenance")).put("resourceVersion","WRONG");f.refresh();expected="SIDECAR_SOURCE_MISMATCH";}
            case "TARGET_PROTEIN"->{((ObjectNode)f.built.at("/comparators/0/targetProteinChange")).put("proteinPosition","999");f.refresh();expected="PROTEIN_CHANGE_SNAPSHOT_MISMATCH";}
            case "ASSEMBLY"->{((ObjectNode)f.built.path("targetAllele")).put("genomeAssembly","GRCh37");f.refresh();expected="ASSEMBLY_UNSUPPORTED";}
            case "CANDIDATE_LIMIT"->{ArrayNode refs=(ArrayNode)f.built.path("comparators");while(refs.size()<257)refs.add(refs.get(0).deepCopy());f.refresh();expected="CANDIDATE_LIMIT_OR_ARRAY_INVALID";}
            case "SCV_LIMIT"->{ArrayNode refs=(ArrayNode)f.built.at("/comparators/0/submissions");while(refs.size()<1025)refs.add(refs.get(0).deepCopy());f.refresh();expected="ASSERTION_LIMIT_OR_ARRAY_INVALID";}
            case "ANNOTATION_CONFLICT"->{var second=f.annotationSnapshot.deepCopy();((ObjectNode)second.path("annotation")).put("hgvsCoding","DIFFERENT");((ArrayNode)f.manifest.path("referenceAnnotations")).add(f.file("reference-conflict.json",second));expected="REFERENCE_ANNOTATION_CONFLICT";}
            case "REFERENCE_GENE"->{((ObjectNode)f.annotationSnapshot.path("annotation")).put("geneSymbol","WRONG");f.refresh();expected="REFERENCE_ANNOTATION_GENE_MISMATCH";}
            case "LOCATOR"->{((ObjectNode)f.built.at("/comparators/0")).put("clinVarVcfRecordLocator","ClinVar:VariationID:999");f.refresh();expected="REFERENCE_LOCATOR_MISMATCH";}
            default->{expected=fault.equals("FILE_LIMIT")?"INPUT_SIZE_LIMIT":"INPUT_JSON_INVALID_OR_DUPLICATE_KEY";}
        }
        f.save();
        if(fault.equals("DUPLICATE_KEY"))Files.writeString(f.manifestPath,"{\"a\":1,\"a\":2}");
        if(fault.equals("TRAILING"))Files.writeString(f.manifestPath," {}",StandardOpenOption.APPEND);
        if(fault.equals("FILE_LIMIT"))Files.writeString(f.manifestPath," ".repeat(4_000_001));
        Path out=temporary.resolve("blocked");var result=new MissenseReferenceDraftPreparation().prepare(f.manifestPath,f.pin(),out);
        assertFalse(result.generated());assertEquals(expected,result.report().path("reasonCode").asText(),result.report().toPrettyString());
        assertEquals("BLOCKED",result.report().path("extractionStatus").asText());assertTrue(Files.exists(out.resolve("artifact-manifest.json")));assertFalse(Files.exists(out.resolve("reference-audit.draft.json")));assertFalse(Files.exists(out.resolve("bundle.json")));
    }
    @ParameterizedTest @ValueSource(strings={"NO_RECORD","DISCOVERY_FAILED","SIDECAR_MISSING","ANNOTATION_OMITTED","MISSING_SNAPSHOT","DUPLICATE","UNVERSIONED_SCV","UNKNOWN_BACKGROUND","PARTIAL","ASSERTION_CONFLICT"})
    void incompleteSearchAndMissingMaterialsRemainUnreviewed(String scenario)throws Exception{
        var f=fixture(0,scenario);
        if(scenario.equals("DUPLICATE")){((ArrayNode)f.built.path("comparators")).add(f.built.at("/comparators/0").deepCopy());f.refresh();}
        if(scenario.equals("ASSERTION_CONFLICT")){var conflict=(ObjectNode)f.built.at("/comparators/0/submissions/0").deepCopy();conflict.put("classification","Benign");((ArrayNode)f.built.at("/comparators/0/submissions")).add(conflict);f.refresh();}
        if(scenario.equals("UNVERSIONED_SCV")){((ObjectNode)f.built.at("/comparators/0/submissions/0")).put("scv","SCV000000001");f.refresh();}
        if(scenario.equals("UNKNOWN_BACKGROUND")){((ObjectNode)f.context.path("gene")).remove("hgncId");f.context.remove("disease");f.context.putNull("moi");String id=f.adapter.canonicalContextId(f.context);f.context.put("contextId",id);f.target.put("contextId",id);f.annotationSnapshot.put("contextId",id);f.built.put("contextId",id);((ObjectNode)f.built.at("/comparators/0")).put("targetContextId",id);f.refresh();}
        if(scenario.equals("MISSING_SNAPSHOT"))f.manifest.putArray("referenceAnnotations");
        Path out=temporary.resolve("draft");var result=f.prepare(out);assertTrue(result.generated(),result.report().toPrettyString());
        assertEquals(f.built,result.report().path("sourceSearch"));assertEquals("UNREVIEWED",result.report().path("reviewStatus").asText());
        var draft=json(out.resolve("reference-audit.draft.json"));var pairs=json(out.resolve("rna-pairs.draft.json"));
        assertFalse(draft.toString().contains("REVIEWED_SUFFICIENT"));assertFalse(pairs.toString().contains("NO_CONTRADICTORY"));
        if(scenario.equals("DUPLICATE")){assertEquals(1,draft.path("references").size());assertEquals(1,pairs.path("pairs").size());assertEquals(2,draft.at("/references/0/observations").size());assertEquals(2,draft.at("/references/0/assertions/0/origins").size());}
        if(scenario.equals("MISSING_SNAPSHOT")){assertFalse(draft.at("/references/0").has("annotation"));assertTrue(result.report().path("gaps").toString().contains("REFERENCE_ANNOTATION_MISSING"));}
        if(scenario.equals("UNKNOWN_BACKGROUND"))assertTrue(result.report().path("gaps").toString().contains("BACKGROUND_MAPPING_REQUIRES_REVIEW"));
        if(scenario.equals("PARTIAL")){assertEquals("READY",f.built.path("status").asText());assertEquals(2,result.report().at("/sourceSearch/candidateDecisions").size());assertTrue(result.report().path("gaps").toString().contains("CANDIDATE_OMITTED"));}
        if(scenario.equals("ASSERTION_CONFLICT")){assertEquals(3,draft.at("/references/0/assertions").size());assertTrue(result.report().path("gaps").toString().contains("CONFLICTING_ASSERTION_PAYLOADS"));}
        archive("gap-"+scenario,out);
    }
    @ParameterizedTest @ValueSource(ints={0,1})
    void externalTestReviewerBridgesFactsToOriginalPreparerAndRejectsStaleApproval(int background)throws Exception{
        var f=fixture(background,"NORMAL");Path draftDir=temporary.resolve("draft");assertTrue(f.prepare(draftDir).generated());
        ObjectNode draft=(ObjectNode)json(draftDir.resolve("reference-audit.draft.json"));JsonNode fact=draft.at("/references/0"), pair=json(draftDir.resolve("rna-pairs.draft.json")).at("/pairs/0");
        var catalog=new Ps1TestCatalog(mapper);var prepared=new MissensePreparationFixture(temporary.resolve("prepared-inputs"),catalog,background==1);
        prepared.replace(background,"referenceAudit",audit->{audit.removeAll();audit.setAll(draft);});
        var rejected=prepared.prepare(temporary.resolve("unreviewed"));assertFalse(rejected.ready());assertEquals("PRIVATE_PATH_IN_SCIENTIFIC_MATERIAL",rejected.report().path("reasonCode").asText());
        prepared.replace(background,"referenceAudit",a->{a.removeAll();a.put("status","UNREVIEWED").put("draft",true);a.putArray("references");});
        var unreviewed=prepared.prepare(temporary.resolve("unreviewed-facts"));assertFalse(unreviewed.ready());assertEquals("MISSENSE_REFERENCE_AUDIT_MISSING",unreviewed.report().path("reasonCode").asText());
        // Explicit TEST_ONLY external reviewer, not production inference from the ClinVar label.
        ObjectNode source=prepared.file("extracted-facts.json",draft);ObjectNode sourceRecord=mapper.createObjectNode().put("sourceId","DRAFT_FACTS").put("version",draft.path("draftId").asText()).put("url","https://example.invalid/test-only-draft").put("sha256",source.path("canonicalSha256").asText());
        source.put("format","JSON").put("sourceDigestKind","CANONICAL_JSON").set("sourceRecord",sourceRecord);prepared.manifest.withArray("sources").add(source);
        prepared.replace(background,"policy",p->p.withArray("sources").add(sourceRecord));
        ObjectNode audit=mapper.createObjectNode().put("status","REVIEWED").put("reviewId","TEST_ONLY_EXTERNAL_DRAFT_REVIEW").put("sourceDraftId",draft.path("draftId").asText());audit.putArray("sourceIds").add("DRAFT_FACTS");
        ObjectNode ref=audit.putArray("references").addObject().put("referenceId",fact.path("referenceDraftId").asText()).put("variationId",fact.path("variationId").asText());
        ref.set("allele",fact.path("allele"));ref.set("annotation",fact.path("annotation"));
        JsonNode assertion=fact.at("/assertions/0/sourceAssertion");ref.set("scv",assertion.path("scv"));ref.set("evaluationDate",assertion.path("dateLastEvaluated"));ref.set("sourceAssertion",assertion);
        ref.put("classification","PATHOGENIC").put("assertionStatus","ACTIVE").put("conflictState","EXTERNALLY_REVIEWED_TEST_ONLY");
        ref.put("geneSymbol",f.context.at("/gene/symbol").asText()).put("hgncId",f.context.at("/gene/hgncId").asText()).put("mondoId",f.context.at("/disease/mondoId").asText()).put("moi",f.context.path("moi").asText());ref.putArray("sourceIds").add("DRAFT_FACTS");
        ObjectNode independence=ref.putObject("independence").put("status","REVIEWED_SUFFICIENT_WITHOUT_PS1_PM5").put("reviewId","TEST_ONLY_EXPLICIT_REVIEWER").put("originalSpecificationVersion","TEST_ONLY@1").put("classificationAfterExclusion","PATHOGENIC").put("dependencyReview","REVIEWED_NO_COMPARISON_DEPENDENCY");independence.putArray("sourceIds").add("DRAFT_FACTS");independence.putArray("criteria").add("PS3");independence.putArray("dependencyAlleles");
        prepared.replace(background,"referenceAudit",a->{a.removeAll();a.setAll(audit);});
        prepared.replace(background,"rnaReview",rna->{var c=(ObjectNode)rna.at("/comparisons/0");c.set("targetAllele",pair.path("targetAllele"));c.set("referenceAllele",pair.path("referenceAllele"));c.put("sourcePairDraftId",pair.path("pairDraftId").asText());});
        Path bundle=prepared.release("released");JsonNode published=json(bundle);JsonNode materials=published.at("/materials/missenseMaterials");
        archive("bridge-"+background,prepared.root);
        boolean found=false;for(JsonNode m:materials)if(m.at("/referenceAudit/sourceDraftId").equals(draft.path("draftId"))){
            found=true;JsonNode releasedReference=m.at("/referenceAudit/references/0");
            assertEquals(fact.path("annotation"),releasedReference.path("annotation"));assertEquals(assertion,releasedReference.path("sourceAssertion"));
            assertEquals(fact.path("allele"),releasedReference.path("allele"));assertEquals(assertion.path("scv"),releasedReference.path("scv"));
            JsonNode sourceDate=assertion.path("dateLastEvaluated");assertTrue(sourceDate.isTextual()&&!sourceDate.asText().isBlank());
            assertEquals(sourceDate.asText(),assertDoesNotThrow(()->java.time.LocalDate.parse(sourceDate.asText())).toString());
            assertEquals(sourceDate,releasedReference.path("evaluationDate"),"published reference evaluationDate must preserve the selected source date");
        }
        assertTrue(found); // release invokes the existing bundle loader; no runtime/evaluate call.
        ((ObjectNode)f.built.at("/comparators/0/submissions/0")).put("dateLastEvaluated","2026-04-05");f.refresh();var changed=f.prepare(temporary.resolve("changed-draft"));assertTrue(changed.generated());
        prepared.replace(background,"referenceAudit",a->a.put("sourceDraftId",changed.report().path("draftId").asText()));
        var stale=prepared.prepare(temporary.resolve("stale-review"));assertFalse(stale.ready());assertEquals("EXTERNAL_APPROVAL_BINDING_MISMATCH",stale.report().path("reasonCode").asText());
        archive("bridge-stale-"+background,temporary.resolve("stale-review"));receipt("bridge-"+background,mapper.createObjectNode().put("rawDraftRejection",rejected.report().path("reasonCode").asText()).put("unreviewedReason",unreviewed.report().path("reasonCode").asText()).put("published",true).put("staleReason",stale.report().path("reasonCode").asText()).put("evaluationCalls",0));
    }
    @Test void realJavaCliGeneratesOnlyDraftAndReportsBadPin()throws Exception{
        var f=fixture(0,"NORMAL");Path good=temporary.resolve("cli-good"),bad=temporary.resolve("cli-bad");assertEquals(0,cli(f,good,f.pin()));assertEquals(2,cli(f,bad,"f".repeat(64)));
        assertEquals("DRAFT_GENERATED",json(good.resolve("draft-report.json")).path("extractionStatus").asText());assertEquals("INPUT_RAW_SHA_MISMATCH",json(bad.resolve("draft-report.json")).path("reasonCode").asText());
        for(Path out:List.of(good,bad)){assertTrue(Files.exists(out.resolve("artifact-manifest.json")));assertFalse(Files.exists(out.resolve("bundle.json")));archive(out.getFileName().toString(),out);}archive("cli-input",f.root);
    }
    int cli(MissenseReferenceDraftFixture f,Path output,String pin)throws Exception{
        var command=List.of(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",System.getProperty("java.class.path"),MissenseReferenceDraftPreparation.class.getName(),"--manifest",f.manifestPath.toString(),"--manifest-sha256",pin,"--output",output.toString());
        Path log=temporary.resolve(output.getFileName()+".log");var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished=process.waitFor(45,java.util.concurrent.TimeUnit.SECONDS);if(!finished)process.destroyForcibly();assertTrue(finished);int exit=process.exitValue();
        receipt(output.getFileName()+"-command",mapper.valueToTree(Map.of("command",command,"exitCode",exit,"log",Files.readString(log))));return exit;
    }
    void archive(String name,Path from)throws Exception{
        String root=System.getProperty("varvis.missense.draft.artifacts");if(root==null)return;Path to=Path.of(root).resolve(name);
        try(var files=Files.walk(from)){for(Path p:files.toList()){Path q=to.resolve(from.relativize(p));if(Files.isDirectory(p))Files.createDirectories(q);else Files.copy(p,q);}}
    }
    void receipt(String name,JsonNode value)throws Exception{String root=System.getProperty("varvis.missense.draft.artifacts");if(root==null)return;Files.createDirectories(Path.of(root));mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(root,name+".json").toFile(),value);}
}
