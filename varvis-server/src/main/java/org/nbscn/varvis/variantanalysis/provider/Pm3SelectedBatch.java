package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.*;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;

/** Request-owned normal PM3 evaluation from immutable server task originals.
 * Never accepts scores, policy choices, or clinical adoption from an HTTP body.
 */
public final class Pm3SelectedBatch {
    public static final String VERSION="pm3-selected-literature-v4";
    public static final String CRITERION="ACMG_AMP_2015:PM3";
    public interface Source {
        ObjectNode identity();
        ObjectNode capture(String analysisId,ObjectNode recovered) throws IOException;
        default ObjectNode captureAutomatic(String analysisId,ObjectNode recovered) throws IOException {
            return capture(analysisId,recovered);
        }
        default boolean acquire(String analysisId,ObjectNode recovered,String candidate,boolean localPass,long deadline)
                throws IOException {return false;}
    }
    private final ObjectMapper json;
    private final Source source;
    private final ObjectNode identity;
    private ObjectNode snapshot;
    private ObjectNode recovered;
    private int calls;
    public Pm3SelectedBatch(ObjectMapper json,Source source) {
        this.json=json;this.source=source;
        identity=json.createObjectNode().put("version",VERSION).put("assessmentVersion",Pm3LiteratureAssessment.VERSION)
                .put("sourceTaskReferenceProjection","CAPTURED_SAME_GENE_ALLELE_V1")
                .put("diagnosticProjectionVersion",LiteratureStageDiagnostics.VERSION);
        identity.set("source",source.identity());
        identity.set("publishedConditions",Pm3PublishedConditions.executionIdentity(json));
    }
    public ObjectNode identity(){return identity.deepCopy();}
    public int evaluations(){return calls;}
    public ObjectNode prepare(String analysis,ObjectNode recovered) {
        if(snapshot!=null)throw new IllegalStateException("PM3_SOURCE_SNAPSHOT_ALREADY_CAPTURED");
        this.recovered=recovered.deepCopy();
        try {
            snapshot=capture(analysis,false);
        }catch(IOException|IllegalArgumentException failed) {
            org.slf4j.LoggerFactory.getLogger(Pm3SelectedBatch.class)
                    .warn("PM3 source capture failed for analysis {}",analysis,failed);
            String reason=failed.getMessage();if(reason==null || !reason.matches("[A-Z][A-Z0-9_]{1,160}"))reason="PM3_SOURCE_CAPTURE_FAILED";
            snapshot=json.createObjectNode().put("schemaVersion","pm3-business-source-snapshot-v1").put("analysisId",analysis).put("reasonCode",reason);
            snapshot.putArray("documents");snapshot.put("snapshotSha256",Pm3SourceDigest.sha256(json,snapshot));
        }
        return json.createObjectNode().put("snapshotSha256",snapshot.path("snapshotSha256").asText())
                .put("documents",snapshot.path("documents").size()).put("reasonCode",snapshot.path("reasonCode").asText("PM3_SOURCE_SNAPSHOT_CAPTURED"));
    }
    private ObjectNode capture(String analysis,boolean includeAutomatic) throws IOException {
        if(!identity.path("source").equals(source.identity()))throw new IOException("PM3_SOURCE_CONFIGURATION_CHANGED");
        var captured=(includeAutomatic?source.captureAutomatic(analysis,recovered):source.capture(analysis,recovered)).deepCopy();
        String pin=captured.path("snapshotSha256").asText();
        var content=captured.deepCopy();content.remove("snapshotSha256");
        if(!"pm3-business-source-snapshot-v1".equals(captured.path("schemaVersion").asText())
                || !analysis.equals(captured.path("analysisId").asText())
                || !pin.equals(Pm3SourceDigest.sha256(json,content))
                || !captured.path("documents").isArray())throw new IOException("PM3_SOURCE_SNAPSHOT_INVALID");
        if(!identity.path("source").equals(source.identity()))throw new IOException("PM3_SOURCE_CONFIGURATION_CHANGED");
        return captured;
    }
    public void supply(SelectedEvidencePreparation selected) {
        if(snapshot==null || snapshot.has("reasonCode"))return;
        var assembly=selected.assembly();
        var contexts=new LinkedHashMap<String,List<ObjectNode>>();
        for(var candidate:assembly.candidates()) for(var attempt:candidate.attempts()) {
            if(attempt.context()==null || !attempt.blockers().isEmpty())continue;
            var context=selected.context(candidate.candidateId(),attempt.hypothesisId());
            if(recessive(context))contexts.computeIfAbsent(candidate.candidateId(),ignored->new ArrayList<>()).add(context);
        }
        String analysis=assembly.analysisId();
        long deadline=System.nanoTime()+java.time.Duration.ofMinutes(2).toNanos();
        for(boolean localPass:new boolean[]{true,false}) {
            for(var entry:contexts.entrySet()) {
                if(System.nanoTime()>=deadline)return;
                String candidate=entry.getKey();
                if(entry.getValue().stream().map(this::evaluateContext).allMatch(Pm3SelectedBatch::sufficient))continue;
                org.nbscn.varvis.variantanalysis.service.AcmgExecutionProgress.stage("SOURCE_SNAPSHOT",candidate,null,"PM3");
                try {
                    if(source.acquire(analysis,recovered,candidate,localPass,deadline))snapshot=capture(analysis,true);
                } catch(IOException failure) {
                    var code=failure.getMessage();
                    if("PM3_SOURCE_CONFIGURATION_CHANGED".equals(code) || "PM3_CAPTURE_CONFIGURATION_CHANGED".equals(code))
                        throw new IllegalStateException(code,failure);
                    org.slf4j.LoggerFactory.getLogger(Pm3SelectedBatch.class)
                            .warn("Automatic PM3 source preparation did not finish for analysis {}",analysis,failure);
                }
            }
        }
    }
    private static boolean sufficient(JsonNode evaluation) {
        return "MET".equals(evaluation.at("/evaluationResult/status").asText())
                || "NOT_APPLICABLE".equals(evaluation.at("/criterionApplicability/status").asText());
    }
    private static boolean recessive(JsonNode context) {
        return "AUTOSOMAL_RECESSIVE".equals(context.path("moi").asText())
                && "GERMLINE".equals(context.at("/scope/analysisDomain").asText())
                && context.at("/disease/mondoId").asText().matches("MONDO:[0-9]{7}");
    }
    public ObjectNode originals(){return snapshot==null?json.createObjectNode():snapshot.deepCopy();}
    public ObjectNode evaluate(SelectedEvidencePreparation selected,String candidate,String hypothesis) {
        return evaluateContext(selected.context(candidate,hypothesis)).put("candidateId",candidate).put("hypothesisId",hypothesis);
    }
    ObjectNode evaluateContext(JsonNode context) {
        var out=json.createObjectNode().put("criterionId",CRITERION).put("version",VERSION);
        var summary=out.putObject("publicSummary").put("schemaVersion","pm3-selected-facts-v1")
                .put("scope","SOURCE_CHECKED_LOCAL_RESEARCH").put("clinicalApproval",false)
                .put("accumulationScope","LOWER_BOUND_WITHIN_CHECKED_PAPER");
        var exclusions=summary.putArray("excludedObservations");
        if(snapshot==null)return unavailable(out,"PM3_SOURCE_SNAPSHOT_NOT_PREPARED");
        summary.put("sourceSnapshotSha256",snapshot.path("snapshotSha256").asText());
        summary.set("excludedTasks",snapshot.path("excludedTasks").isArray()?snapshot.path("excludedTasks").deepCopy():json.createArrayNode());
        if(snapshot.has("reasonCode"))return unavailable(out,snapshot.path("reasonCode").asText());
        if(!recessive(context))
            return unavailable(out,"PM3_SELECTED_RECESSIVE_DISEASE_CONTEXT_REQUIRED");
        var merged=json.createObjectNode().put("schemaVersion","pm3-discovery-process-v1");var papers=merged.putArray("papers");
        var requests=json.createObjectNode();requests.set("selectedAllele",context.at("/variant/evidenceQueryAllele"));
        requests.putObject("target").set("geneSymbol",context.at("/gene/symbol"));
        var seen=new HashSet<String>();var policies=summary.putArray("policySelections");var facts=summary.putArray("caseFacts");
        var coverage=summary.putArray("paperCoverage");
        var taskReferences=summary.putArray("sourceTaskReferences");
        var taskCoverage=summary.putArray("taskCoverage");
        var taskProjection=summary.putObject("taskCoverageProjection").put("projectionVersion",LiteratureStageDiagnostics.VERSION);
        var referencedTasks=new HashSet<String>();var coveredTasks=new HashSet<String>();int coveredTaskCount=0;
        int eligible=0;
        var conditionSupply=new Pm3PublishedConditions(json);
        var conditionDecisions=new HashMap<String,Pm3PublishedConditions.Decision>();
        for(var doc:snapshot.path("documents")) {
            var request=doc.path("request");
            if(!Pm3LiteratureAssessment.sameAllele(request.path("selectedAllele"),requests.path("selectedAllele"))
                    || !request.at("/target/geneSymbol").equals(context.at("/gene/symbol")))continue;
            var worker=doc.path("result");
            String taskId=doc.path("taskId").asText(), resultPin=doc.path("resultSha256").asText();
            String selectionPin=request.path("selectionSha256").asText();
            // Source acquisition may fail before any paper exists. Its captured
            // task must remain visible without fabricating a registered bundle.
            if(coveredTasks.add(taskId+"#"+resultPin)) {
                coveredTaskCount++;
                if(taskCoverage.size()<128)taskCoverage.add(LiteratureStageDiagnostics.task(json,doc,snapshot.path("snapshotSha256").asText()));
            }
            if(taskId.matches("[a-f0-9]{64}") && resultPin.matches("[a-f0-9]{64}")
                    && selectionPin.matches("[a-f0-9]{64}") && referencedTasks.add(taskId)) {
                var reference=taskReferences.addObject().put("kind","PM3_LITERATURE").put("taskId",taskId)
                        .put("analysisId",snapshot.path("analysisId").asText()).put("capturedResultSha256",resultPin)
                        .put("selectionSha256",selectionPin)
                        .put("bindingScope","GENE_AND_SELECTED_ALLELE_NOT_HYPOTHESIS_SPECIFIC");
                if(request.path("pmcid").isTextual() && request.path("pmcid").asText().length()<=32
                        && request.path("pmcid").asText().matches("PMC[1-9][0-9]*"))reference.set("pmcid",request.path("pmcid").deepCopy());
            }
            Iterable<JsonNode> originals="pm3-discovery-process-v1".equals(worker.path("schemaVersion").asText())?worker.path("papers"):List.of(worker);
            for(var p:originals) {
                if(!seen.add(Pm3SourceDigest.sha256(json,p)))continue;
                var progress=coverage.addObject().put("pmcid",p.path("pmcid").asText())
                        .put("status",p.path("status").asText()).put("absenceOfCasesEstablished",false);
                LiteratureStageDiagnostics.paper(json,progress,doc,p,snapshot.path("snapshotSha256").asText());
                LiteratureStageDiagnostics.progress(json,progress,p,worker);
                LiteratureStageDiagnostics.capPaper(json,progress);
                if(p.path("manualReviews").isArray())progress.set("manualReviews",p.path("manualReviews").deepCopy());
                progress.set("materialAssessments",p.at("/observations/materialAssessments").isArray()
                        ?p.at("/observations/materialAssessments").deepCopy():json.createArrayNode());
                if(p.at("/observations/unreviewedExtraction").isObject())
                    progress.set("unreviewedExtraction",p.at("/observations/unreviewedExtraction").deepCopy());
                var paper=json.createObjectNode();paper.set("pmcid",p.path("pmcid"));paper.set("bundleSha256",p.path("bundleSha256"));
                var cases=paper.putObject("observations").putArray("cases");papers.add(paper);
                for(var c:p.path("observations").path("cases")) {
                    var copy=((ObjectNode)c).deepCopy();var rows=copy.putArray("resolvedObservations");cases.add(copy);
                    if(c.path("resolvedObservations").isEmpty()) {
                        var fact=facts.addObject().put("pmcid",p.path("pmcid").asText()).put("caseLabel",c.path("caseLabel").asText())
                                .put("interpretationStatus","CASE_SEMANTICS_UNRESOLVED");
                        fact.set("reportedFacts",c.path("facts").isArray()?c.path("facts").deepCopy():json.createArrayNode());
                        if(c.path("cohortContext").isObject())fact.set("cohortContext",c.path("cohortContext").deepCopy());
                        copyObservationProvenance(c, fact);
                        fact.set("unverifiedFindings",c.at("/semantics/unverifiedFindings").isArray()?c.at("/semantics/unverifiedFindings").deepCopy():json.createArrayNode());
                        if(c.path("sourceAlleleCrosswalks").isArray())fact.set("sourceAlleleCrosswalks",c.path("sourceAlleleCrosswalks").deepCopy());
                        if(c.path("sourceAlleleCrosswalkIssues").isArray())fact.set("sourceAlleleCrosswalkIssues",c.path("sourceAlleleCrosswalkIssues").deepCopy());
                        if(c.path("tableLayoutIssues").isArray())fact.set("tableLayoutIssues",c.path("tableLayoutIssues").deepCopy());
                        fact.set("semanticIssues",c.at("/semantics/issues").isArray()?c.at("/semantics/issues").deepCopy():json.createArrayNode());
                        if(c.at("/semantics/assemblyGaps").isArray())fact.set("assemblyGaps",c.at("/semantics/assemblyGaps").deepCopy());
                        exclusions.addObject().put("pmcid",p.path("pmcid").asText()).put("caseLabel",c.path("caseLabel").asText())
                                .put("reasonCode","PM3_CASE_SEMANTICS_UNRESOLVED");
                    }
                    for(var row:c.path("resolvedObservations")) {
                        var fact=facts.addObject().put("pmcid",p.path("pmcid").asText()).put("caseLabel",c.path("caseLabel").asText());
                        fact.put("phaseEvidenceStatus",Pm3PublishedConditions.phaseEvidenceStatus(row.path("reportedObservation")));
                        fact.set("reportedFacts",row.at("/reportedObservation/facts").deepCopy());
                        if(c.path("sourceAlleleCrosswalks").isArray())fact.set("sourceAlleleCrosswalks",c.path("sourceAlleleCrosswalks").deepCopy());
                        if(c.path("sourceAlleleCrosswalkIssues").isArray())fact.set("sourceAlleleCrosswalkIssues",c.path("sourceAlleleCrosswalkIssues").deepCopy());
                        if(c.path("tableLayoutIssues").isArray())fact.set("tableLayoutIssues",c.path("tableLayoutIssues").deepCopy());
                        if(c.path("cohortContext").isObject())fact.set("cohortContext",c.path("cohortContext").deepCopy());
                        copyObservationProvenance(c, fact);
                        if(row.at("/diseaseContext/diseaseId").isTextual())fact.set("diseaseId",row.at("/diseaseContext/diseaseId").deepCopy());
                        else fact.putNull("diseaseId");
                        if(row.path("issues").isArray())fact.set("identityIssues",row.path("issues").deepCopy());
                        if(row.path("partnerQualification").isObject())fact.set("partnerQualification",row.path("partnerQualification").deepCopy());
                        if(row.path("publishedTranscriptBinding").isObject())
                            fact.set("publishedTranscriptBinding",row.path("publishedTranscriptBinding").deepCopy());
                        if(row.path("rarityQualification").isObject()) {
                            var rarity=fact.putObject("rarityQualification");
                            for(String role:List.of("target","partner")) {
                                var original=row.path("rarityQualification").path(role);
                                if(!original.isObject())continue;
                                var observed=rarity.putObject(role);
                                for(String field:List.of("schemaVersion","qualification","reasonCode","scope","wholeGnomadConclusion",
                                        "queryAllele","contextId","observationId","sourceSnapshot","profileSha256","populationEvidence"))
                                    if(original.has(field))observed.set(field,original.path(field).deepCopy());
                            }
                        }
                        if(row.path("partnerClassificationSources").isObject()) {
                            var src=row.path("partnerClassificationSources");var publicSources=fact.putObject("partnerClassificationSources");
                            for(String field:List.of("status","sourceScope","assertions","issues"))
                                if(src.has(field))publicSources.set(field,src.path(field).deepCopy());
                            var lookup=src.at("/acquisition/lookup");
                            if(lookup.isObject()) {
                                var publicLookup=publicSources.putObject("lookup");
                                for(String field:List.of("allele","geneSymbol","method","region","rawSha256","headerSha256","indexSha256","accessions"))
                                    if(lookup.has(field))publicLookup.set(field,lookup.path(field).deepCopy());
                            }
                        }
                        if(row.at("/diseaseContext/diseaseNameMapping").isObject())
                            fact.set("diseaseNameMapping",row.at("/diseaseContext/diseaseNameMapping").deepCopy());
                        var diseaseCheck=fact.putObject("diseaseContextCheck");
                        for(String field:List.of("qualification","reasonCode","reportedDiagnoses","reportedAffectedStatus",
                                "diseaseId","inheritance","scopeBasis","matchedRelationships"))
                            if(row.path("diseaseContext").has(field))diseaseCheck.set(field,row.path("diseaseContext").path(field).deepCopy());
                        var links=json.createObjectNode();
                        for(String role:List.of("target","partner")) {
                            var identity=row.at("/identities/"+role);
                            if(identity.path("sourceProteinCrosswalk").isArray() || identity.path("mappingMethod").isTextual()) {
                                var proof=links.putObject(role);
                                for(String field:List.of("allele","transcript","coding","proteinChange","sourceProteinCrosswalk","sourceTranscriptLink",
                                        "mappingMethod","genomicAccession"))
                                    if(identity.has(field))proof.set(field,identity.path(field).deepCopy());
                            }
                        }
                        if(!links.isEmpty())fact.set("sourceIdentityLinks",links);
                        String reason=null;var policy=row.path("policySelection");
                        if(!"LOCAL_IDENTITY_VERIFIED".equals(row.at("/identities/target/status").asText())
                                || !row.at("/identities/target/allele").isObject())reason="PM3_TARGET_IDENTITY_UNRESOLVED";
                        else if(!row.at("/diseaseContext/diseaseId").isTextual())reason="PM3_CASE_DISEASE_CONTEXT_UNRESOLVED";
                        else if(!context.at("/disease/mondoId").equals(row.at("/diseaseContext/diseaseId")))reason="PM3_CASE_DISEASE_DIFFERS_FROM_SELECTED_CONTEXT";
                        else if(!context.at("/gene/hgncId").equals(row.at("/identities/target/hgncId")))reason="PM3_CASE_GENE_DIFFERS_FROM_SELECTED_CONTEXT";
                        else if(!policy.isObject())reason="PM3_APPLICABLE_POLICY_NOT_SUPPLIED";
                        else if(!"pm3-specification-context-v2".equals(policy.path("schemaVersion").asText()))reason="PM3_APPLICABLE_POLICY_ORIGINALS_REQUIRED";
                        else if(!Set.of("SELECTED","NOT_APPLICABLE").contains(policy.path("status").asText()))reason=policy.path("reasonCode").asText("PM3_APPLICABLE_POLICY_UNRESOLVED");
                        else if("NOT_APPLICABLE".equals(policy.path("status").asText()) && policy.path("matches").size()!=1)reason="PM3_PUBLISHED_SOURCE_SELECTION_REQUIRED";
                        else if(("SELECTED".equals(policy.path("status").asText()) && !"CLINGEN_SVI_PM3_2019_V1".equals(policy.path("policy").asText()))
                                || !context.at("/gene/hgncId").equals(policy.at("/context/hgncId"))
                                || !context.at("/disease/mondoId").equals(policy.at("/context/diseaseId"))
                                || !"AR".equals(policy.at("/context/inheritance").asText()))reason="PM3_POLICY_CONTEXT_MISMATCH";
                        if(reason!=null){
                            var blocker=fact.putObject("qualificationBlocker").put("reasonCode",reason);
                            // A missing prerequisite is not several independent missing facts.
                            // Keep the precise cause and downstream dependencies separate.
                            if("PM3_TARGET_IDENTITY_UNRESOLVED".equals(reason)) {
                                blocker.put("stage","TARGET_IDENTITY");
                                blocker.putArray("dependentChecks").add("DISEASE_CONTEXT").add("POLICY").add("RARITY_ADOPTION");
                            } else if("PM3_CASE_DISEASE_CONTEXT_UNRESOLVED".equals(reason)) {
                                blocker.put("stage","DISEASE_CONTEXT");
                                blocker.put("causeReasonCode",row.at("/diseaseContext/reasonCode").asText(reason));
                                blocker.putArray("dependentChecks").add("POLICY").add("RARITY_ADOPTION");
                            } else blocker.put("stage","CONTEXT_OR_POLICY");
                            exclusions.addObject().put("pmcid",p.path("pmcid").asText()).put("caseLabel",c.path("caseLabel").asText()).put("reasonCode",reason);continue;
                        }
                        var conditions=conditionSupply.assess(p.path("observations"),row,context,c);
                        conditionDecisions.put(conditionSupply.rowKey(row),conditions);
                        fact.set("publishedConditions",conditions.display());
                        rows.add(row.deepCopy());eligible++;
                        policies.add(policy.deepCopy());
                    }
                }
            }
        }
        taskProjection.put("status","AVAILABLE").put("total",coveredTaskCount).put("omitted",coveredTaskCount-taskCoverage.size()).put("truncated",coveredTaskCount>taskCoverage.size());
        // Continuations retain immutable parent tasks. Collapse only identical public
        // fact projections; keep changed facts/citations, coverage and scoring inputs.
        var uniqueFacts=json.createArrayNode();var displayedFacts=new HashSet<JsonNode>();
        for(var fact:facts)if(displayedFacts.add(fact))uniqueFacts.add(fact);
        summary.set("caseFacts",uniqueFacts);
        if(eligible==0) {
            boolean identityMissing=!exclusions.isEmpty();
            for(var excluded:exclusions)identityMissing&="PM3_TARGET_IDENTITY_UNRESOLVED".equals(excluded.path("reasonCode").asText());
            return unavailable(out,exclusions.isEmpty()?"PM3_NO_COMPATIBLE_LITERATURE_OBSERVATIONS":
                    identityMissing?"PM3_TARGET_IDENTITY_UNRESOLVED":"PM3_LITERATURE_QUALIFICATION_INCOMPLETE");
        }
        var assessment=new Pm3LiteratureAssessment(json).assessWithPublishedConditions(merged,requests,conditionDecisions);calls+=assessment.path("pm3CalculatorCalls").asInt();
        var group=assessment.at("/diseaseAssessments/0");var calculation=group.path("calculation");
        String status=calculation.path("status").asText("NOT_EVALUATED");
        summary.set("assessment",group.deepCopy());
        out.putObject("criterionApplicability").put("status","NOT_APPLICABLE".equals(status)?"NOT_APPLICABLE":"APPLICABLE");
        out.put("appliedStrength","MET".equals(status)?calculation.path("strength").asText():"NONE");
        out.putObject("evaluationResult").put("status","NOT_APPLICABLE".equals(status)?"NOT_EVALUATED":status)
                .put("reasonCode","MET".equals(status)?"PM3_QUALIFIED_LITERATURE_THRESHOLD_MET":
                    "NOT_MET".equals(status)?"PM3_QUALIFIED_LITERATURE_BELOW_THRESHOLD":
                    "NOT_APPLICABLE".equals(status)?"PM3_OBSERVATIONS_NOT_APPLICABLE":"PM3_LITERATURE_REQUIREMENTS_UNRESOLVED");
        return out;
    }
    private void copyObservationProvenance(JsonNode source, ObjectNode target) {
        for (String field : List.of("identityKind", "patientIdentifier", "supplementContext", "primarySourceQualification"))
            if (source.has(field)) target.set(field, source.path(field).deepCopy());
    }
    private ObjectNode unavailable(ObjectNode out,String reason) {
        out.put("appliedStrength","NONE");out.putObject("criterionApplicability").put("status","UNKNOWN");
        out.putObject("evaluationResult").put("status","NOT_EVALUATED").put("reasonCode",reason);return out;
    }
}
