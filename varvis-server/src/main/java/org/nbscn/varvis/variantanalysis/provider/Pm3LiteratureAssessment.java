package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import static org.nbscn.varvis.variantanalysis.provider.Pm3LiteratureScoring.*;

/** Internal general-policy candidate calculation over server-enriched literature.
 * This is not an adopted business result: exact disease policy selection is still required.
 * Unknown overlap is not absence of cases; use a documented lower-bound subset.
 */
public final class Pm3LiteratureAssessment {
    public static final String VERSION="pm3-literature-assessment-v3";
    private final ObjectMapper json;
    private int calls;
    private record Entry(String paper,String disease,String label,String family,Set<String> unrelated,
                         Observation observation,List<Requirement> requirements) { }
    public Pm3LiteratureAssessment(ObjectMapper json){this.json=json;}

    public ObjectNode assess(JsonNode worker,JsonNode request) {
        return assess(worker,request,Map.of(),false);
    }
    ObjectNode assessWithPublishedConditions(JsonNode worker,JsonNode request,Map<String,Pm3PublishedConditions.Decision> decisions) {
        return assess(worker,request,decisions,true);
    }
    private ObjectNode assess(JsonNode worker,JsonNode request,Map<String,Pm3PublishedConditions.Decision> decisions,boolean requirePublished) {
        calls=0;
        var result=json.createObjectNode().put("schemaVersion",VERSION).put("businessPublishable",false)
                .put("policy","CLINGEN_SVI_PM3_2019_V1")
                .put("policySource","https://www.clinicalgenome.org/site/assets/files/3717/svi_proposal_for_pm3_criterion_-_version_1.pdf")
                .put("policyApplicability","SPECIFICATION_SELECTION_PENDING")
                .put("use","GENERAL_POLICY_CANDIDATE_NOT_ADOPTED");
        var groups=result.putArray("diseaseAssessments");
        String target=alleleId(request.path("selectedAllele"));
        var entries=new ArrayList<Entry>();
        Iterable<JsonNode> papers="pm3-discovery-process-v1".equals(worker.path("schemaVersion").asText())?worker.path("papers"):List.of(worker);
        int ordinal=0;
        for(var paper:papers) for(var caseRow:paper.path("observations").path("cases"))
            for(var row:caseRow.path("resolvedObservations"))entries.add(entry(paper,caseRow,row,request,target,ordinal++,decisions,requirePublished));
        var byDisease=new TreeMap<String,List<Entry>>();
        for(var entry:entries)byDisease.computeIfAbsent(entry.disease(),unused->new ArrayList<>()).add(entry);
        for(var group:byDisease.entrySet()) {
            var output=groups.addObject().put("diseaseId",group.getKey());
            var byPaper=new TreeMap<String,List<Entry>>();
            for(var e:group.getValue())byPaper.computeIfAbsent(e.paper(),unused->new ArrayList<>()).add(e);
            var selected=new LinkedHashSet<String>();
            var withheld=output.putArray("withheldOccurrences");
            String bestPaper=null;int bestPoints=-1;
            for(var paper:byPaper.entrySet()) {
                var candidates=selectWithinPaper(paper.getValue());
                int points=calculate(target,project(paper.getValue(),candidates),requirements(paper.getValue())).quarterPoints();calls++;
                if(points>bestPoints){bestPoints=points;bestPaper=paper.getKey();selected=candidates;}
            }
            // Distinct PMCIDs are not proof of distinct patients. Until cross-source
            // linkage is established, retain the strongest internally checked paper
            // as a lower bound and expose every withheld record instead of summing it.
            for(var e:group.getValue())if(!selected.contains(e.observation().independentOccurrenceId()))
                withheld.addObject().put("sourceCaseId",e.observation().independentOccurrenceId()).put("pmcid",e.paper())
                        .put("caseLabel",e.label()).put("reasonCode",Objects.equals(bestPaper,e.paper())
                            ?"PM3_WITHIN_PAPER_RELATED_OR_OVERLAP_NOT_ADDITIONAL":"PM3_CROSS_PAPER_OVERLAP_NOT_RECONCILED");
            var assessment=calculate(target,project(group.getValue(),selected),requirements(group.getValue()));calls++;
            output.set("calculation",json.valueToTree(assessment));
            output.put("accumulationScope","LOWER_BOUND_WITHIN_CHECKED_PAPER")
                    .put("selectedPaper",bestPaper).put("crossPaperAccumulation",false);
            output.set("selectedOccurrenceIds",json.valueToTree(selected));
        }
        if(entries.isEmpty())result.putArray("issues").add("PM3_NO_SOURCE_VERIFIED_GENOTYPE_OBSERVATIONS");
        result.put("pm3CalculatorCalls",calls).put("pm3FinalBusinessExecution",false);
        return result;
    }

    private LinkedHashSet<String> selectWithinPaper(List<Entry> entries) {
        var byCase=new TreeMap<String,List<Entry>>();
        for(var e:entries)byCase.computeIfAbsent(e.observation().independentOccurrenceId(),unused->new ArrayList<>()).add(e);
        var representatives=new ArrayList<Entry>();
        var scores=new HashMap<String,Integer>();
        for(var group:byCase.entrySet()) {
            var first=group.getValue().get(0);
            var r=calculate(first.observation().targetAlleleId(),group.getValue().stream().map(e->Objects.requireNonNull(e).observation()).toList(),requirements(group.getValue()));calls++;
            scores.put(group.getKey(),r.quarterPoints());representatives.add(first);
        }
        Comparator<Entry> order=Comparator.<Entry>comparingInt(e->scores.get(e.observation().independentOccurrenceId())).reversed()
                .thenComparing(e->e.observation().independentOccurrenceId());
        representatives.sort(order);
        // One proband per explicitly named family. A missing family identifier is
        // not a veto for one source-verified observation.
        var families=new HashSet<String>();var chosen=new ArrayList<Entry>();
        for(var e:representatives)if(e.family().isEmpty() || families.add(e.family()))chosen.add(e);
        boolean distinctFamilies=chosen.stream().allMatch(e->!e.family().isEmpty());
        Set<String> labels=new HashSet<>();for(var e:chosen)labels.add(e.label());
        boolean explicitlyUnrelated=chosen.stream().allMatch(e->e.unrelated().containsAll(labels));
        if(chosen.size()>1 && !distinctFamilies && !explicitlyUnrelated)chosen=new ArrayList<>(List.of(chosen.get(0)));
        var ids=new LinkedHashSet<String>();for(var e:chosen)ids.add(e.observation().independentOccurrenceId());return ids;
    }

    private List<Observation> project(List<Entry> entries,Set<String> selected) {
        return entries.stream().map(e->{
            var o=e.observation();
            if(selected.contains(o.independentOccurrenceId()) || o.caseIdentity()!=Check.VERIFIED)return o;
            return new Observation(o.id(),o.independentOccurrenceId(),o.targetAlleleId(),o.partnerAlleleId(),o.homozygous(),
                    o.phase(),o.partnerClassification(),Check.UNKNOWN,o.affectedRecessiveContext(),o.targetIdentity(),o.partnerIdentity(),
                    o.targetRare(),o.partnerRare(),o.independentClassification(),o.sourceReferences());
        }).toList();
    }

    private static Map<String,List<Requirement>> requirements(List<Entry> entries) {
        var requirements=new HashMap<String,List<Requirement>>();
        for(var e:entries)requirements.put(e.observation().id(),e.requirements());
        return requirements;
    }
    private Entry entry(JsonNode paper,JsonNode caseRow,JsonNode row,JsonNode request,String target,int index,
                        Map<String,Pm3PublishedConditions.Decision> decisions,boolean requirePublished) {
        String pmcid=paper.path("pmcid").asText(),label=caseRow.path("caseLabel").asText();
        var proof=caseRow.path("occurrenceQualification");
        String occurrence="pm3-occurrence-"+sha(pmcid+"\n"+label);
        var identity=row.path("identities");var reported=row.path("reportedObservation");
        boolean homo="REPORTED_HOMOZYGOUS".equals(reported.path("kind").asText());
        var targetAllele=identity.path("target").path("allele");
        Check targetCheck=targetAllele.isObject()
                && "LOCAL_IDENTITY_VERIFIED".equals(identity.path("target").path("status").asText())
                && "MATCHED".equals(row.path("selectedTargetBinding").asText())?Check.VERIFIED:Check.UNKNOWN;
        if(targetAllele.isObject() && !sameAllele(request.path("selectedAllele"),targetAllele))targetCheck=Check.CONFLICT;
        Check caseCheck=check(proof.path("qualification").asText());
        if(!"pm3-source-occurrence-v1".equals(proof.path("schemaVersion").asText()) || !pmcid.equals(proof.path("pmcid").asText())
                || !label.equals(proof.path("caseLabel").asText()) || !pmcid.matches("PMC[1-9][0-9]*") || label.isBlank())caseCheck=Check.UNKNOWN;
        else if("CONFLICT".equals(caseRow.at("/primarySourceQualification/qualification").asText()))caseCheck=Check.CONFLICT;
        else if(!"VERIFIED".equals(caseRow.at("/primarySourceQualification/qualification").asText()))caseCheck=Check.UNKNOWN;
        String family=proof.path("familyIdentity").isTextual()?proof.path("familyIdentity").asText():"";
        var unrelated=new HashSet<String>();
        for(var group:proof.path("unrelatedGroups"))for(var member:group.path("caseLabels"))unrelated.add(member.asText());
        String disease=row.at("/diseaseContext/diseaseId").asText("UNRESOLVED");
        Check diseaseCheck=check(row.at("/diseaseContext/qualification").asText());
        if(!request.at("/target/geneSymbol").equals(reported.path("geneSymbol"))
                || "CONFLICT".equals(reported.path("reportedAffectedStatus").asText()))diseaseCheck=Check.CONFLICT;
        else if("UNAFFECTED".equals(reported.path("reportedAffectedStatus").asText()))diseaseCheck=Check.FAILED;
        else if(diseaseCheck==Check.VERIFIED && (!"AR".equals(row.at("/diseaseContext/inheritance").asText())
                || !"AFFECTED".equals(reported.path("reportedAffectedStatus").asText())))diseaseCheck=Check.UNKNOWN;
        var refs=new LinkedHashSet<String>();
        for(var fact:reported.path("facts"))if(fact.at("/citation/unitId").isTextual())
            refs.add(pmcid+"#"+fact.at("/citation/unitId").asText());
        for(var ref:reported.at("/phaseAssayProof/references"))if(ref.path("unitId").isTextual())
            refs.add(pmcid+"#"+ref.path("unitId").asText());
        if(refs.isEmpty()){refs.add(pmcid+"#case:"+label);caseCheck=Check.UNKNOWN;}
        var partnerAllele=identity.path("partner").path("allele");
        String partner=homo || !partnerAllele.isObject()?null:alleleId(partnerAllele);
        var decision=decisions.get(Pm3SourceDigest.sha256(json,row));
        List<Requirement> required=requirePublished && (decision==null || !decision.binds(json,row))
            ?List.of(new Requirement("PM3_PUBLISHED_CONDITIONS_NOT_EVALUATED","MISSING_INFORMATION"))
            :decision==null?List.of():decision.requirements();
        var observation=new Observation(occurrence+":"+index,occurrence,target,partner,homo,
                decision==null?phase(reported.path("phase").asText()):decision.phase(),classification(row.at("/partnerQualification/classification").asText()),
                caseCheck,diseaseCheck,targetCheck,
                "LOCAL_IDENTITY_VERIFIED".equals(identity.path("partner").path("status").asText())?Check.VERIFIED:Check.UNKNOWN,
                rarity(row,"target"),rarity(row,"partner"),check(row.at("/partnerQualification/independentClassification").asText()),List.copyOf(refs));
        return new Entry(pmcid,disease,label,family,unrelated,observation,required);
    }
    private Check rarity(JsonNode row,String role) {
        var q=row.path("rarityQualification").path(role);var status=check(q.path("qualification").asText());
        if(status==Check.VERIFIED && !sameAllele(row.path("identities").path(role).path("allele"),q.path("queryAllele")))return Check.CONFLICT;
        return status;
    }
    /** JSON integer storage width is not biological identity. Reject coercions or extra fields. */
    static boolean sameAllele(JsonNode left,JsonNode right) {
        if(!left.isObject() || !right.isObject() || left.size()!=5 || right.size()!=5)return false;
        for(String key:java.util.List.of("assembly","contig","reference","alternate"))
            if(!left.path(key).isTextual() || left.path(key).asText().isBlank()
                    || !left.path(key).equals(right.path(key)))return false;
        var a=left.path("position");var b=right.path("position");
        return a.isIntegralNumber() && b.isIntegralNumber() && a.canConvertToLong() && b.canConvertToLong()
                && a.longValue()>0 && a.longValue()==b.longValue();
    }
    private String alleleId(JsonNode allele){return "allele-sha256-"+new RuntimeEvidenceV2Adapter(json).canonicalSha256(allele);}
    private static String sha(String text){return FamilyDeNovoJson.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static Check check(String value){try{return Check.valueOf(value);}catch(IllegalArgumentException invalid){return Check.UNKNOWN;}}
    private static Phase phase(String value){try{return Phase.valueOf(value);}catch(IllegalArgumentException invalid){return Phase.UNKNOWN;}}
    private static Classification classification(String value){try{return Classification.valueOf(value);}catch(IllegalArgumentException invalid){return Classification.UNKNOWN;}}
}
