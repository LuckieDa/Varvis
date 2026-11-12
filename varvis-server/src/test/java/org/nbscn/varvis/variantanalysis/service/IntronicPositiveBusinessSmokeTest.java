package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.engine.AnalysisScopePolicy;
import org.nbscn.varvis.variantanalysis.provider.IntronicSplicePolicy;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed expected-positive branch control, using the existing HTTP/file-H2 real business smoke.
 * No browser, listening service or external completion marker is required.
 */
class IntronicPositiveBusinessSmokeTest {
    @Test @EnabledIfSystemProperty(named="varvis.e4b3.positiveReal", matches="true")
    void fixedPositiveUploadEvaluateGetAndRepeat() throws Exception {
        Path parent=Path.of("target/e4-b3-bp7-positive").toAbsolutePath();
        Path work=Files.createDirectory(parent.resolve("backend-attempt-"+UUID.randomUUID()));
        var smoke=IntronicScopeBusinessSmokeTest.positiveAt(work);
        var mapper=smoke.base.mapper;
        var report=mapper.createObjectNode().put("status","BLOCKED").put("pageCovered",false).put("dataClass","SYNTHETIC");
        System.out.println("POSITIVE_BACKEND_ATTEMPT="+work);
        try {
            // Verify the retained tiny query against the actual effective policy and resource identity
            // before invoking VEP or Exomiser. Missing frequency is never treated as zero.
            smoke.setup();
            var p=smoke.base.properties.getExomiser();
            var frequency=mapper.readTree(Files.readString(parent.resolve("frequency-query.json")));
            var panel=mapper.readTree(Files.readString(smoke.panelPath));
            var policy=AnalysisScopePolicy.resolve(p,AnalysisScopePolicy.Scope.INCLUDE_INTRONS,false);
            assertTrue(policy.supplementsZeroPassed());
            assertEquals("dfc159f3c09bc1327dd15d0ac831513e7358f679e63c7180e42a5addab912498",panel.at("/frequencyPrecheck/policySha256").asText());
            assertEquals(Files.readString(Path.of("target/e4-b3-bp7-acceptance/effective-analysis.yml")),policy.effectiveYaml());
            assertEquals("15.1.0",p.getVersion());assertEquals("2602",p.getDataVersion());
            assertEquals("edee8dc48a95ca876f91e0105c4d8566b88b51924417d000df20b574591b7278",p.getResourceFingerprint());
            Path database=Path.of(p.getDataDirectory()).resolve("2602_hg38/2602_hg38_variants.mv.db");
            assertEquals(frequency.path("databaseBytes").asLong(),Files.size(database));
            assertEquals(java.time.Instant.parse("2026-08-23T15:45:12Z").getEpochSecond(),Files.getLastModifiedTime(database).toInstant().getEpochSecond());
            assertEquals("GRCh38:11:5225756:T:G",frequency.at("/alleles/0/allele").asText());
            assertEquals("percent",frequency.path("frequencyUnit").asText());
            assertEquals("0.013224015",frequency.at("/alleles/0/selectedMaxPercent").asText());
            report.put("frequencyQueryReused",true).put("frequencyQuerySha256",IntronicScopeRuntimeProbe.sha(parent.resolve("frequency-query.json")))
                    .put("databaseMtime",Files.getLastModifiedTime(database).toInstant().toString())
                    .put("resourceFingerprint",p.getResourceFingerprint()).put("policySha256",policy.policySha256());
            Files.copy(parent.resolve("frequency-query.json"),work.resolve("frequency-query.json"));
            Files.copy(smoke.panelPath,work.resolve("fixed-panel.json"));
            smoke.base.closeDatabase();
            String retained=System.getProperty("varvis.e4b3.positivePreflightEvidence","");
            if(retained.isBlank())smoke.preflightFixedAllelesBeforeExomiser();
            else preflightWithRetainedVep(smoke,Path.of(retained));
            report.put("preflightPassed",true);
            smoke.ordinaryScopeUploadExecuteSaveRecreateAndReuse();
            var observed=mapper.readTree(Files.readString(smoke.artifacts.resolve("real-result.json")));
            report.set("businessResult",observed);
            report.put("bp7MetCovered",observed.path("bp7MetCovered").asBoolean());
            // All real responses and GET/repeat observations have already been saved even if the
            // scientific result differs from the expectation. Never retry with a different allele.
            assertTrue(observed.path("bp7MetCovered").asBoolean(),"Expected positive control was not MET; retain actual result");
            assertEquals(1,smoke.exomiserCalls);assertEquals(retained.isBlank()?2:1,smoke.vepCalls);assertEquals(1,smoke.spliceBatches);
            report.put("status","REAL_BP7_MET_BACKEND_VERIFIED_PAGE_NOT_COVERED");
        } catch(Exception|AssertionError failure) {report.put("failure",failure.toString());throw failure;
        } finally {
            report.set("observedCalls",mapper.valueToTree(smoke.calls()));
            Files.writeString(work.resolve("positive-result.json"),report.toPrettyString(),StandardOpenOption.CREATE_NEW);
            smoke.base.closeDatabase();
        }
    }
    void preflightWithRetainedVep(IntronicScopeBusinessSmokeTest smoke,Path retained) throws Exception {
        smoke.setup();var mapper=smoke.base.mapper;
        var report=mapper.createObjectNode().put("status","BLOCKED").put("pageCovered",false);
        try {
            Path previous=retained.resolve("preflight-report.json");
            assertEquals(System.getProperty("varvis.e4b3.positivePreflightSha256"),IntronicScopeRuntimeProbe.sha(previous));
            var old=mapper.readTree(Files.readString(previous));
            assertEquals(smoke.configurationProof(),old.path("businessConfiguration"));
            assertEquals(smoke.hash(Files.readString(smoke.panelPath)),old.path("panelSha256").asText());
            assertEquals(smoke.vcf,Files.readString(retained.resolve("fixed-public-panel.vcf")));
            assertEquals(smoke.hash(smoke.vcf),old.path("vcfSha256").asText());
            assertEquals(Files.readString(smoke.work.resolve("frequency-query.json")),Files.readString(retained.resolve("frequency-query.json")));
            var policy=AnalysisScopePolicy.resolve(smoke.base.properties.getExomiser(),AnalysisScopePolicy.Scope.INCLUDE_INTRONS,false);
            assertEquals(Files.readString(retained.resolve("effective-analysis.yml")),policy.effectiveYaml());
            assertTrue(policy.supplementsZeroPassed());
            var parsed=new org.nbscn.varvis.variantanalysis.provider.VepJsonOutputParser(mapper)
                    .parseByVariantId(retained.resolve("preflight-vep.jsonl"),"VEP","116.0").getAnnotationsByVariantId();
            var retainedAnnotations=old.path("annotations").deepCopy();
            com.fasterxml.jackson.databind.JsonNode reparsedAnnotations=mapper.valueToTree(parsed);
            // Parser timestamps describe this parse, not the VEP scientific evidence.
            for(var rows:retainedAnnotations)for(var tx:rows)((com.fasterxml.jackson.databind.node.ObjectNode)tx).remove("timestamp");
            for(var rows:reparsedAnnotations)for(var tx:rows)((com.fasterxml.jackson.databind.node.ObjectNode)tx).remove("timestamp");
            assertEquals(retainedAnnotations,reparsedAnnotations);
            assertTrue(parsed.values().stream().flatMap(rows -> rows.stream()).anyMatch(tx->smoke.hgvsc.equals(tx.getHgvsc())
                    &&"ENST00000335295.4".equals(tx.getVersionedTranscriptId())&&new IntronicSplicePolicy(mapper).blocker(tx)==null));
            var env=smoke.environment;
            var ledger=ExplicitContextSourceLedger.load(mapper,Path.of(env.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-file")),env.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-sha256"));
            var ref=new NativeSnvReference(mapper,smoke.base.properties,new org.nbscn.varvis.variantanalysis.provider.NormalizationResourceValidator(smoke.base.properties),
                    Path.of(env.getRequiredProperty("variant-analysis.acmg-business.resource-root")),List.of(ledger),ledger.reference("reference"));
            var freshRef=ref.observe("chr11",smoke.position,smoke.ref).observation();
            for(String key:List.of("assembly","contig","referenceContig","position","expectedReference","observedReference","status","method","version","contigAssociation"))
                assertEquals(old.at("/referenceObservations/0").path(key),freshRef.path(key));
            report.set("freshReferenceObservation",freshRef);
            for(String key:List.of("panelSha256","vcfSha256","policySha256","businessConfiguration","frequencyPrecheck","annotations","referenceObservations"))report.set(key,old.path(key));
            report.put("retainedFilterPolicySha256",old.path("policySha256").asText()).put("policySha256",policy.policySha256())
                    .put("outputCoveragePolicy",policy.outputCoveragePolicy());
            report.put("retainedPreflightSha256",IntronicScopeRuntimeProbe.sha(previous)).put("vepEvidenceReused",true).put("vepProcessCalls",0);
            Files.copy(retained.resolve("fixed-public-panel.vcf"),smoke.work.resolve("fixed-public-panel.vcf"));
            Files.copy(retained.resolve("preflight-vep.jsonl"),smoke.work.resolve("preflight-vep.jsonl"));
            Files.writeString(smoke.work.resolve("effective-analysis.yml"),policy.effectiveYaml(),StandardOpenOption.CREATE_NEW);
            smoke.verifyConfiguredJavaRuntime(report);
            var runtime=IntronicScopeRuntimeProbe.run(mapper,smoke.spliceCommand(),env.getRequiredProperty("variant-analysis.acmg-business.spliceai-reference-fasta"),smoke.work,
                    new org.nbscn.varvis.variantanalysis.provider.SystemExternalProcessRunner());
            report.set("runtimeProbe",runtime);assertEquals("READY",runtime.path("status").asText(),runtime.toPrettyString());
            report.put("status","READY");
        } catch(Exception|AssertionError failure){report.put("failure",failure.toString());throw failure;
        } finally {Files.writeString(smoke.work.resolve("preflight-report.json"),report.toPrettyString(),StandardOpenOption.CREATE_NEW);smoke.base.closeDatabase();}
    }
}
