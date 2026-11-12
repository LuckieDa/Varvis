package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.persistence.JdbcVariantAnalysisJobRepository;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Opt-in replay of fresh source worker outputs and pinned public population data. */
class Pm3PublishedCaseReplayTest {
    private final ObjectMapper json=new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Test void stableFilesReplayTheStoredFormalCandidatesAndExposeMissingInputs() throws Exception {
        String resources=System.getProperty("pm3.case.replay.resourceRoot","");
        String archive=System.getProperty("pm3.case.relocation.formalArchive","");
        String work=System.getProperty("pm3.case.relocation.workerOutputs","");
        Assumptions.assumeFalse(resources.isBlank() || archive.isBlank() || work.isBlank(),
                "Provide the project root, isolated public ranking archive and fresh offline worker outputs");
        Path formal=Path.of(archive), outputs=Path.of(work);
        var saved=read(formal.resolve("original-export.json"));
        String analysis=saved.path("analysisId").asText();
        var dataSource=new DriverManagerDataSource("jdbc:h2:file:"+formal.resolve("formal-ranking").toAbsolutePath()
                +";MODE=MySQL;IFEXISTS=TRUE;ACCESS_MODE_DATA=r","sa","");
        var repository=new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource),new VariantAnalysisProperties(),json);
        var rank=repository.findRankingPage(analysis,0,4);
        var ids=rank.stream().map(candidate->candidate.getCandidateId()).toList();
        assertEquals(List.of("exomiser-v1-00000002","exomiser-v1-00000003","exomiser-v1-00000001",
                "exomiser-v1-00000004"),ids);
        var supply=new Pm3LiteratureRaritySupply(json,population(Path.of(resources)));
        var receipts=json.createObjectNode().put("analysisId",analysis).put("priorRunId",saved.path("runId").asText());
        receipts.set("formalRank",json.valueToTree(ids));
        var cases=receipts.putObject("cases");
        for(String gene:List.of("RPE65","GAMT","ASNS","CFTR")) {
            var request=read(formal.resolve("formal-requests").resolve(gene+"-request.json"));
            var allele=request.path("selectedAllele");
            var candidate=rank.stream().filter(r->{
                var key=r.getKey();
                return key!=null && key.getPosition()==allele.path("position").asInt()
                        && key.getGenomeAssembly().equals(allele.path("assembly").asText())
                        && key.getChromosome().equals(allele.path("contig").asText())
                        && key.getReference().equals(allele.path("reference").asText())
                        && key.getAlternate().equals(allele.path("alternate").asText());
            }).findFirst().orElseThrow();
            var context=savedContext(saved,candidate.getCandidateId(),"AUTOSOMAL_RECESSIVE");
            assertEquals(gene,context.at("/gene/symbol").asText());
            assertEquals(allele,context.at("/variant/evidenceQueryAllele"));
            var outcome=evaluateRelocated(outputs,gene,analysis,request,context,supply);
            var calculation=outcome.at("/publicSummary/assessment/calculation");
            assertEquals(Map.of("RPE65",2,"GAMT",4,"ASNS",2,"CFTR",0).get(gene),calculation.path("quarterPoints").asInt(-1),
                    outcome.toPrettyString());
            assertEquals(Map.of("RPE65","SUPPORTING","GAMT","MODERATE","ASNS","SUPPORTING","CFTR","NONE").get(gene),
                    outcome.path("appliedStrength").asText(),outcome.toPrettyString());
            cases.set(gene,outcome);
            if("CFTR".equals(gene))assertTrue(calculation.toString().contains("PM3_CIS_NOT_APPLICABLE"));
            if("GAMT".equals(gene))assertEquals("GN026@2.0",outcome.at("/publicSummary/caseFacts/0/publishedConditions/specificationKey").asText());
            if(!"RPE65".equals(gene))continue;
            var fact=outcome.at("/publicSummary/caseFacts/0");
            assertEquals("257",fact.path("caseLabel").asText());
            assertEquals("REPORTED_UNDETERMINED",fact.path("phaseEvidenceStatus").asText());
            assertEquals("0.00012285",fact.at("/rarityQualification/target/populationEvidence/popmaxFaf95").asText());
            assertEquals("0.00003234",fact.at("/rarityQualification/partner/populationEvidence/popmaxFaf95").asText());
            assertEquals(11,fact.at("/partnerQualification/independentPoints").asInt());
            assertEquals("1.0.0",checkedVersion(fact.path("publishedConditions"),"SAME_RELEASE_PARTNER_CLASSIFICATION"));
            assertTrue(fact.path("publishedConditions").toString().contains("Non-recordable responses"));
            assertEquals("NONE",evaluateRelocated(outputs,gene,analysis,request,
                    savedContext(saved,candidate.getCandidateId(),"AUTOSOMAL_DOMINANT"),supply).path("appliedStrength").asText());
            var failures=receipts.putObject("missingInputs");
            for(var missing:Map.of("release-index","PM3_PUBLISHED_RELEASE_INDEX_UNAVAILABLE",
                    "attachment","PM3_PUBLISHED_ATTACHMENT_ORIGINAL_UNAVAILABLE",
                    "frequency","PM3_PUBLISHED_JOINT_EXACT_SITE_REQUIRED",
                    "partner","PM3_PARTNER_DEPENDENCY_REVIEW_UNAVAILABLE").entrySet()) {
                var blocked=evaluateRelocated(outputs,gene+"-no-"+missing.getKey(),analysis,request,context,supply);
                assertEquals("NONE",blocked.path("appliedStrength").asText(),blocked.toPrettyString());
                assertTrue(blocked.path("publicSummary").toString().contains(missing.getValue()),blocked.toPrettyString());
                failures.set(missing.getKey(),blocked);
            }
        }
        Files.write(outputs.resolve("stable-score-receipts.json"),Objects.requireNonNull(json.writeValueAsBytes(receipts)));
    }

    private static ObjectNode savedContext(JsonNode saved,String candidateId,String moi) {
        for(var candidate:saved.path("candidates")) {
            if(!candidateId.equals(candidate.path("candidateId").asText()))continue;
            for(var attempt:candidate.path("attempts")) {
                if(attempt.path("context") instanceof ObjectNode context && moi.equals(context.path("moi").asText()))
                    return context;
            }
        }
        throw new AssertionError("Stored formal background missing: "+candidateId+"/"+moi);
    }

    private ObjectNode evaluateRelocated(Path outputs,String name,String analysis,ObjectNode request,ObjectNode context,
                                          Pm3LiteratureRaritySupply supply) throws Exception {
        var worker=read(outputs.resolve(name).resolve("result.json"));
        assertTrue(worker.path("localFirstPass").asBoolean());
        assertEquals(0,worker.path("networkRequests").asInt(-1));
        assertEquals(0,worker.at("/partnerAcquisition/networkRequests").asInt(-1));
        assertEquals(0,worker.path("modelCalls").asInt(-1));
        assertEquals(request.path("target"),worker.path("target"));
        assertEquals(FamilyDeNovoJson.sha256(json.writeValueAsBytes(request)),worker.path("requestSha256").asText());
        assertEquals(request.path("selectionSha256"),worker.path("selectionSha256"));
        var enriched=supply.enrich(worker,request,analysis,Duration.ofSeconds(60));
        return assess(enriched,request,context,false,analysis);
    }

    @Test void releasedEligibilityUsesOneExplicitlyUnphasedOccurrenceAndPreservesControls() throws Exception {
        String directory=System.getProperty("pm3.case.replay.directory","");
        String root=System.getProperty("pm3.case.replay.resourceRoot","");
        Assumptions.assumeFalse(directory.isBlank() || root.isBlank(),"Provide local public source replays and resource root");
        Path replay=Path.of(directory);
        var supply=new Pm3LiteratureRaritySupply(json, population(Path.of(root)));
        var receipts=json.createObjectNode();
        for(var gene:List.of("RPE65","GAMT","ASNS","CFTR")) {
            var request=read(replay.resolve(gene+"-request.json"));
            var worker=read(replay.resolve(gene).resolve("result.json"));
            assertEquals(0,worker.path("modelCalls").asInt(-1));
            var row=worker.at("/observations/cases/0/resolvedObservations/0");
            assertTrue(row.isObject(),gene);
            var context=json.createObjectNode().put("moi","AUTOSOMAL_RECESSIVE");
            context.putObject("scope").put("analysisDomain","GERMLINE");
            context.putObject("variant").set("evidenceQueryAllele",request.path("selectedAllele"));
            context.putObject("gene").put("symbol",gene).set("hgncId",row.at("/identities/target/hgncId"));
            context.putObject("disease").set("mondoId",row.at("/diseaseContext/diseaseId"));
            var enriched=supply.enrich(worker,request,"public-replay",Duration.ofSeconds(60));
            var outcome=assess(enriched,request,context,"RPE65".equals(gene));
            var facts=outcome.path("publicSummary");
            var calculation=facts.at("/assessment/calculation");
            receipts.set(gene,outcome);
            if("CFTR".equals(gene)) {
                assertEquals("NOT_APPLICABLE",outcome.at("/criterionApplicability/status").asText(),calculation.toPrettyString());
                assertEquals(0,calculation.path("quarterPoints").asInt(-1));
                assertTrue(calculation.toString().contains("PM3_CIS_NOT_APPLICABLE"));
                continue;
            }
            assertEquals("MET",outcome.at("/evaluationResult/status").asText(),gene+": "+facts.at("/assessment/calculation").toPrettyString()
                    +" "+facts.at("/caseFacts/0/rarityQualification"));
            assertEquals("GAMT".equals(gene)?"MODERATE":"SUPPORTING",outcome.path("appliedStrength").asText());
            assertEquals("GAMT".equals(gene)?4:2,calculation.path("quarterPoints").asInt(-1));
            if("GAMT".equals(gene))assertEquals("GN026@2.0",facts.at("/caseFacts/0/publishedConditions/specificationKey").asText());
            if("RPE65".equals(gene)) {
                assertEquals(2,enriched.at("/populationQualification/publishedJointRarityEvaluations").asInt(-1));
                assertEquals(0,enriched.at("/populationQualification/providerCalls").asInt(-1));
                var conditions=facts.at("/caseFacts/0/publishedConditions");
                assertEquals("GN120@1.0",conditions.path("specificationKey").asText());
                assertEquals("1.0.0",checkedVersion(conditions,"SAME_RELEASE_TARGET_CLASSIFICATION"));
                assertEquals("1.0.0",checkedVersion(conditions,"SAME_RELEASE_PARTNER_CLASSIFICATION"));
                assertEquals("UNKNOWN_PHASE_WEIGHT",conditions.at("/requirements/0/status").asText());
                assertTrue(conditions.toString().contains("Non-recordable responses"));
                var checked=enriched.at("/observations/cases/0/resolvedObservations/0");
                assertEquals("0.00012285",checked.at("/rarityQualification/target/populationEvidence/popmaxFaf95").asText());
                assertEquals("0.00003234",checked.at("/rarityQualification/partner/populationEvidence/popmaxFaf95").asText());
                assertEquals(11,checked.at("/partnerQualification/independentPoints").asInt(-1));
                assertEquals(2,calculation.path("contributions").size());
                assertTrue(calculation.toString().contains("PM3_DUPLICATE_OCCURRENCE"));
                var missing=enriched.deepCopy();
                ((ObjectNode)missing.path("observations")).remove("specificationAttachmentOriginals");
                assertEquals(0,assess(missing,request,context,false).at("/publicSummary/assessment/calculation/quarterPoints").asInt(-1));
                var differentCase=enriched.deepCopy();
                ((ObjectNode)differentCase.at("/observations/cases/0/anchors/0")).put("cell","R257C1");
                var withheld=assess(differentCase,request,context,false);
                assertEquals(0,withheld.at("/publicSummary/assessment/calculation/quarterPoints").asInt(-1));
                assertTrue(withheld.toString().contains("PM3_PUBLISHED_CASE_PHENOTYPE_BINDING_MISMATCH"));
            }
        }
        Files.write(replay.resolve("score-receipts.json"),Objects.requireNonNull(json.writeValueAsBytes(receipts)));
    }

    private ObjectNode read(Path path) throws Exception {
        return (ObjectNode)json.readTree(Files.readAllBytes(path));
    }

    private static String checkedVersion(JsonNode conditions,String id) {
        for(var check:conditions.path("requirements"))if(id.equals(check.path("conditionId").asText()))
            return check.at("/observed/version").asText();
        return "";
    }

    private ObjectNode assess(ObjectNode worker,ObjectNode request,ObjectNode context,boolean duplicate) {
        return assess(worker,request,context,duplicate,"public-replay");
    }

    private ObjectNode assess(ObjectNode worker,ObjectNode request,ObjectNode context,boolean duplicate,String analysis) {
        var snapshot=json.createObjectNode().put("schemaVersion","pm3-business-source-snapshot-v1").put("analysisId",analysis);
        var documents=snapshot.putArray("documents");
        var first=documents.addObject().put("taskId","a".repeat(64));
        first.set("request",request);
        first.set("result",worker);
        if(duplicate) {
            // Retain different task metadata but the same source occurrence.
            var copy=worker.deepCopy().put("status","SOURCE_REVIEWED_SEMANTICS_PENDING");
            var second=documents.addObject().put("taskId","b".repeat(64));
            second.set("request",request);
            second.set("result",copy);
        }
        snapshot.put("snapshotSha256",Pm3SourceDigest.sha256(json,snapshot));
        var batch=new Pm3SelectedBatch(json,new Pm3SelectedBatch.Source() {
            public ObjectNode identity(){return json.createObjectNode().put("publicReplay",true);}
            public ObjectNode capture(String id,ObjectNode recovered){return snapshot;}
        });
        batch.prepare(analysis,json.createObjectNode());
        return batch.evaluateContext(context);
    }

    private Pm3LiteratureRaritySupply.Factory population(Path root) throws Exception {
        Path config=root.resolve("local/runtime/bp7-evidence-workbench");
        var env=new StandardEnvironment();
        env.getPropertySources().addFirst(new ResourcePropertySource(new FileSystemResource(Objects.requireNonNull(config.resolve("evidence-base.properties")))));
        env.getPropertySources().addFirst(new ResourcePropertySource(new FileSystemResource(Objects.requireNonNull(config.resolve("evidence-overrides.properties")))));
        env.getPropertySources().addFirst(new MapPropertySource("public-replay-root",Objects.requireNonNull(Map.of(
                "VARVIS_PROJECT_ROOT",root.toString(),"VARVIS_TOOL_ROOT",root.resolve("local/tools").toString(),
                "VARVIS_RESOURCE_ROOT",root.resolve("local/resources").toString(),"VARVIS_RUNTIME_ROOT",root.resolve("local/runtime").toString()))));
        return new AcmgBusinessConfig().pm3LiteraturePopulation(json,env);
    }
}
