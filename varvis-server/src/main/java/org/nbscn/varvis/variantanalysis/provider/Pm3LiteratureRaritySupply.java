package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import org.nbscn.varvis.variantanalysis.domain.*;

/** Server-owned enrichment of verified worker observations. Never accepts HTTP/model rarity claims. */
public final class Pm3LiteratureRaritySupply {
    public static final String VERSION="pm3-literature-rarity-supply-v1";
    public interface Factory {
        ObjectNode identity();
        Pm2LocalExomesRuntime create();
    }
    private final ObjectMapper mapper;
    private final Factory factory;
    // A supply instance belongs to one source task. Exact full context and
    // factory identity bind reuse; nothing is shared across analyses/tasks.
    private final java.util.Map<String,ObjectNode> verifiedCache=new java.util.HashMap<>();
    public Pm3LiteratureRaritySupply(ObjectMapper mapper,Factory factory) {this.mapper=mapper;this.factory=factory;}

    public ObjectNode enrich(ObjectNode worker,JsonNode request,String analysisId,Duration remaining) {
        var result=worker.deepCopy();
        var summary=result.putObject("populationQualification").put("schemaVersion",VERSION)
                .put("scope","LOCAL_GNOMAD_EXOMES_ONLY").put("wholeGnomadConclusion",false)
                .put("pm3EvaluatorCalls",0);
        Pm2LocalExomesRuntime runtime=null;
        String setupFailure=null;
        int attempts=0,verified=0,unresolved=0,qualificationEvaluations=0,cacheHits=0,jointEvaluations=0;
        long deadline=System.nanoTime()+Math.max(0,remaining.toNanos());
        var contextFactory=new Pm3LiteraturePopulationContext(mapper);
        var rarity=new Pm3RarityQualification(mapper);
        var publishedRarity=new Pm3PublishedRarity(mapper);
        // WES describes the queried exome resource, not the uploaded patient's assay.
        var analysis=new AnalysisContext(analysisId,GenomeAssembly.GRCh38,AnalysisMode.WES);
        Iterable<JsonNode> papers="pm3-discovery-process-v1".equals(result.path("schemaVersion").asText())
                ?result.path("papers"):List.of(result);
        for(var paper:papers) for(var caseRow:paper.path("observations").path("cases"))
            for(var value:caseRow.path("resolvedObservations")) {
                if(!(value instanceof ObjectNode row)) continue;
                // Replace any worker-supplied qualification; only server evaluation is authoritative.
                var qualifications=row.putObject("rarityQualification");
                boolean homo="REPORTED_HOMOZYGOUS".equals(row.at("/reportedObservation/kind").asText());
                for(String role:homo?List.of("target"):List.of("target","partner")) {
                    ObjectNode qualification;
                    boolean cacheHit=false;
                    try {
                        if(!request.path("selectedAllele").isObject()
                                || !request.path("selectedAllele").equals(row.at("/identities/target/allele")))
                            throw new IllegalArgumentException("PM3_POPULATION_SELECTED_ALLELE_MISMATCH");
                        var context=contextFactory.build(paper.path("pmcid").asText(),paper.path("bundleSha256").asText(),
                                caseRow.path("caseLabel").asText(),row,role);
                        if(Pm3PublishedRarity.applies(row)) {
                            qualification=publishedRarity.qualify(paper.path("observations"),row,role,context);
                            jointEvaluations++;
                        } else {
                        String cacheKey=context.toString()+"\n"+factory.identity().toString();
                        if(verifiedCache.containsKey(cacheKey)) {
                            qualification=verifiedCache.get(cacheKey).deepCopy();cacheHit=true;cacheHits++;
                        } else {
                        if(System.nanoTime()>=deadline) throw new IllegalArgumentException("PM3_POPULATION_TASK_BUDGET_EXHAUSTED");
                        if(attempts>=100) throw new IllegalArgumentException("PM3_POPULATION_TASK_QUERY_LIMIT");
                        if(setupFailure!=null) throw new IllegalArgumentException(setupFailure);
                        if(runtime==null) {
                            try {runtime=factory.create();}
                            catch(IllegalArgumentException invalid) {setupFailure=code(invalid);throw invalid;}
                        }
                        attempts++;
                        var background=mapper.createObjectNode();
                        for(String field:List.of("gene","disease","moi","dataClass"))background.set(field,context.path(field));
                        // Bind population facts to the actual paper bytes; no invented patient source snapshot.
                        qualification=rarity.acquire(runtime,context.at("/variant/evidenceQueryAllele"),background,context,
                                Pm3LiteraturePopulationContext.queryView(context),analysis,runtime.evaluationProfile(),
                                paper.path("bundleSha256").asText(),null);
                        if("VERIFIED".equals(qualification.path("qualification").asText()))verifiedCache.put(cacheKey,qualification.deepCopy());
                        }
                        }
                    } catch(IllegalArgumentException invalid) {
                        String reason=code(invalid);
                        qualification=mapper.createObjectNode().put("schemaVersion","pm3-local-rarity-qualification-v1")
                                .put("qualification",reason.contains("MISMATCH") || reason.contains("CONFLICT")?"CONFLICT":"UNKNOWN").put("reasonCode",reason)
                                .put("scope","LOCAL_GNOMAD_EXOMES_ONLY").put("wholeGnomadConclusion",false)
                                .put("businessPublishable",false).put("providerCalls",0);
                    }
                    qualifications.set(role,qualification);
                    if(!cacheHit)qualificationEvaluations+=qualification.path("qualificationEvaluatorCalls").asInt(0);
                    if("VERIFIED".equals(qualification.path("qualification").asText()))verified++;else unresolved++;
                }
                if(homo) qualifications.putObject("partner").put("qualification","NOT_APPLICABLE")
                        .put("reasonCode","PM3_HOMOZYGOUS_NO_SECOND_ALLELE_REQUIRED");
            }
        summary.put("qualificationAttempts",attempts).put("verifiedRoles",verified).put("unresolvedRoles",unresolved)
                .put("exactContextCacheHits",cacheHits)
                .put("providerCalls",runtime==null?0:runtime.providerCalls())
                .put("populationRuntimeEvaluatorCalls",runtime==null?0:runtime.evaluatorCalls())
                .put("qualificationEvaluatorCalls",qualificationEvaluations)
                .put("pm2EvaluatorCalls",(runtime==null?0:runtime.evaluatorCalls())+qualificationEvaluations)
                .put("allSitesCalls",runtime==null?0:runtime.allSitesCalls());
        if(jointEvaluations>0)summary.put("scope",Pm3PublishedRarity.SCOPE)
                .put("publishedJointRarityEvaluations",jointEvaluations);
        result.put("scientificExecution",summary.path("pm2EvaluatorCalls").asInt()>0 || jointEvaluations>0)
                .put("pm3ScientificExecution",false);
        return result;
    }
    private static String code(IllegalArgumentException invalid) {
        String message=invalid.getMessage();
        return message!=null && message.matches("[A-Z][A-Z0-9_]{1,160}")?message:"PM3_POPULATION_CONFIGURATION_OR_QUERY_INVALID";
    }
}
