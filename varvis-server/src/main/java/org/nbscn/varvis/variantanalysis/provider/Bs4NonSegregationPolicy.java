package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;

/** Bounded AD affected non-carrier sibling nonsegregation. Validate the whole roster before emitting any counted event. */
public final class Bs4NonSegregationPolicy {
    private final ObjectMapper m = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter hash = new RuntimeEvidenceV2Adapter(m);
    public record Decision(String reason, int count, ArrayNode events, String evidenceGroupId) {
    }
    private static final class Refusal extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        Refusal(String reason) {
            super(reason);
        }
    }
    private static void need(boolean condition, String reason) {
        if (!condition) throw new Refusal("BS4_" + reason);
    }
    static String nfc(JsonNode n) {
        return Normalizer.normalize(n.asText(), Normalizer.Form.NFC);
    }
    private boolean eq(JsonNode a, JsonNode b) {
        return !a.isMissingNode() && !b.isMissingNode() && hash.canonicalSha256(a).equals(hash.canonicalSha256(b));
    }
    static boolean date(JsonNode n) {
        try {
            Instant.parse(n.asText());
            return true;
        }
        catch (java.time.format.DateTimeParseException invalid) {
            return false;
        }
    }
    private void refs(JsonNode ids, Set<String> sources) {
        need(ids.isArray() && !ids.isEmpty(), "SOURCE_REFERENCE_REQUIRED");
        for (var id : ids) need(sources.contains(nfc(id)), "SOURCE_REFERENCE_UNRESOLVED");
    }
    private void review(JsonNode r, Set<String> sources) {
        need("REVIEWED".equals(r.path("status").asText()) && date(r.path("date")), "REVIEW_REQUIRED");
        refs(r.path("sourceIds"), sources);
    }
    private void allRefs(JsonNode n, Set<String> sources) {
        if(n.isObject() && n.has("sourceIds")) refs(n.path("sourceIds"), sources);
        for(var c:n) if(c.isContainerNode())allRefs(c,sources);
    }
    public Decision validate(ObjectNode context, ObjectNode o, ObjectNode policy) {
        try {
            return checked(context, o, policy);
        }
        catch (Refusal refusal) {
            return new Decision(refusal.getMessage(), 0, m.createArrayNode(), "");
        }
    }
    private Decision checked(ObjectNode c, ObjectNode o, ObjectNode policy) {
        JsonNode f = o.path("facts"), binding = f.path("binding"), a = o.path("assessment");
        need(policy.path("criterionIds").size() == 1 && "ACMG_AMP_2015:BS4".equals(policy.at("/criterionIds/0").asText()), "POLICY_CRITERION_SCOPE_MISMATCH");
        Set<String> sources = new HashSet<>();
        for(String part:Bs4NonSegregationBundle.PARTS) {
            JsonNode doc = o.path(part);
            need(!doc.path("sources").isEmpty(), "SOURCE_REQUIRED");
            Set<String> partSources = new HashSet<>();
            for (var source : doc.path("sources")) partSources.add(nfc(source.path("sourceId")));
            allRefs(doc, partSources);
            ObjectNode projection = ((ObjectNode)doc).deepCopy();
            projection.remove("sources");
            for(var source:doc.path("sources")) {
                need(sources.add(nfc(source.path("sourceId"))) && date(source.path("acquiredAt")), "SOURCE_IDENTITY_INVALID");
                byte[] raw=source.path("rawUtf8").asText().getBytes(StandardCharsets.UTF_8);
                need(FamilyDeNovoJson.sha256(raw).equals(source.path("rawSha256").asText()), "SOURCE_RAW_PIN_MISMATCH");
                ObjectNode report;
                String canonical;
                try {
                    report=Bs4NonSegregationBundle.JSON.parse(m,raw);
                    canonical=hash.canonicalSha256(report);
                }
                catch(IllegalArgumentException invalid) {
                    throw new Refusal("BS4_SOURCE_DOCUMENT_INVALID");
                }
                need(canonical.equals(source.path("canonicalSha256").asText()), "SOURCE_CANONICAL_PIN_MISMATCH");
                need(eq(report,projection), "SOURCE_FACTS_MISMATCH");
            }
            String digest=hash.canonicalSha256(doc);
            need(digest.equals(a.at("/partPins/"+part).asText()) && digest.equals(policy.at("/partPins/"+part).asText()), "PART_PIN_MISMATCH");
            need(eq(doc.path("binding"),binding), "PART_BINDING_MISMATCH");
        }
        allRefs(o,sources);
        review(a.path("review"),sources);
        for(String part:List.of("nonSegregationReview","diseaseApplicability","locusAttribution"))review(o.path(part).path("review"),sources);
        ObjectNode assessment=((ObjectNode)a).deepCopy();
        assessment.remove("contentSha256");
        need(hash.canonicalSha256(assessment).equals(a.path("contentSha256").asText()) && hash.canonicalSha256(a).equals(policy.path("assessmentSha256").asText()), "ASSESSMENT_PIN_MISMATCH");
        need("SINGLE_NUCLEAR_FAMILY_AD".equals(policy.path("mode").asText()) && "bs4-diploid-snv-dp20-gq20-explicit-ft-v1".equals(policy.path("qualityProfile").asText()), "POLICY_SCOPE_UNSUPPORTED");
        for(var pair:Map.of("contextId","/contextId","alleleId","/variant/alleleId","hgncId","/gene/hgncId","mondoId","/disease/mondoId","moi","/moi","patientContextId","/patientContext/patientContextId","familyContextId","/patientContext/familyContextId").entrySet())need(eq(binding.path(pair.getKey()),c.at(pair.getValue())),"CONTEXT_BINDING_MISMATCH");
        for(String field:List.of("observationId","occurrenceId","caseId"))need(eq(binding.path(field),f.path(field)),"CASE_BINDING_MISMATCH");
        need(eq(f.path("familyId"),binding.path("familyContextId")) && eq(f.path("variant"),c.path("variant")),"FAMILY_OR_TARGET_BINDING_MISMATCH");
        JsonNode v=c.path("variant"),allele=v.path("normalizedAllele");
        need("AUTOSOMAL_DOMINANT".equals(c.path("moi").asText()) && "GRCh38".equals(allele.path("assembly").asText()) && allele.path("contig").asText().matches("([1-9]|1[0-9]|2[0-2])") && "SNV".equals(v.path("variantType").asText()) && "NATIVE".equals(v.at("/assemblyLineage/mappingStatus").asText()) && eq(allele,v.path("inputAllele")) && eq(allele,v.path("evidenceQueryAllele")) && allele.path("reference").asText().matches("[ACGT]") && allele.path("alternate").asText().matches("[ACGT]") && !eq(allele.path("reference"),allele.path("alternate")),"NATIVE_AUTOSOMAL_AD_SNV_REQUIRED");
        need("NUCLEAR".equals(f.path("structure").asText()) && "UNRELATED_CONFIRMED".equals(f.path("parentRelatedness").asText()),"FAMILY_STRUCTURE_UNSUPPORTED");
        Map<String,JsonNode> members=new TreeMap<>();
        Set<String> people=new HashSet<>(),samples=new HashSet<>();
        List<JsonNode> children=new ArrayList<>(),fathers=new ArrayList<>(),mothers=new ArrayList<>(),probands=new ArrayList<>();
        for(var member:f.path("members")) {
            need(members.put(nfc(member.path("memberId")),member)==null && people.add(nfc(member.path("sourcePersonId"))) && samples.add(nfc(member.path("sampleId"))),"MEMBERS_NOT_DISTINCT");
            need("INDEPENDENT_SINGLETON".equals(member.path("independence").asText()),"MEMBER_INDEPENDENCE_UNESTABLISHED");
            switch(member.path("role").asText()) {
                case "FATHER"->fathers.add(member);
                case "MOTHER"->mothers.add(member);
                default->children.add(member);
            }
            if(member.path("proband").asBoolean())probands.add(member);
            var cl=member.path("clinical");
            var d=member.path("detection");
            review(cl.path("review"),sources);
            review(d.path("review"),sources);
            need(date(cl.path("observedAt")) && cl.path("ageYears").longValue()>=0 && cl.path("ageYears").longValue()<=130 && date(d.path("observedAt")),"CLINICAL_OR_DETECTION_FACT_INVALID");
            need("TESTED".equals(d.path("status").asText()) && "COMPLETE".equals(d.path("coverage").asText()) && "NONMOSAIC".equals(d.path("mosaicism").asText()),"DETECTION_INCOMPLETE");
            need(eq(cl.path("mondoId"),c.at("/disease/mondoId")) && eq(cl.path("mechanism"),o.at("/diseaseApplicability/mechanism")),"MEMBER_DISEASE_MISMATCH");
            String affected=member.path("affectedStatus").asText();
            need((affected.equals("AFFECTED")&&cl.path("diagnosis").asText().equals("SAME_DISEASE_CONFIRMED")) || (affected.equals("UNAFFECTED")&&cl.path("diagnosis").asText().equals("UNAFFECTED_CONFIRMED")),"CLINICAL_STATUS_UNESTABLISHED");
        }
        need(fathers.size()==1 && mothers.size()==1 && children.size()>=1 && children.size()<=5 && probands.size()==1,"NUCLEAR_FAMILY_CARDINALITY_INVALID");
        JsonNode father=fathers.get(0),mother=mothers.get(0),proband=probands.get(0);
        need(children.contains(proband) && eq(proband.path("memberId"),c.at("/patientContext/patientContextId")) && "PROBAND".equals(c.at("/patientContext/role").asText()) && eq(proband.path("affectedStatus"),c.at("/patientContext/affectedStatus")),"PROBAND_BINDING_MISMATCH");
        for(var parent:List.of(father,mother))need(parent.path("fatherId").isNull()&&parent.path("motherId").isNull(),"PARENT_PEDIGREE_OUTSIDE_SCOPE");
        for(var child:children)need(eq(child.path("fatherId"),father.path("memberId"))&&eq(child.path("motherId"),mother.path("memberId")),"PARENT_CHILD_BINDING_MISMATCH");
        Map<String,JsonNode> records=new HashMap<>(),calls=new HashMap<>();
        Set<String> recordIds=new HashSet<>(),locators=new HashSet<>();
        for(var record:f.path("records")) {
            String id=nfc(record.path("memberId"));
            JsonNode member=members.get(id);
            if(member==null) throw new Refusal("BS4_RECORD_MEMBERSHIP_OR_DUPLICATE");
            need(records.put(id,record)==null && recordIds.add(nfc(record.path("recordId"))) && locators.add(nfc(record.path("locator"))),"RECORD_MEMBERSHIP_OR_DUPLICATE");
            need(eq(record.path("sourcePersonId"),member.path("sourcePersonId")) && eq(record.path("sampleId"),member.path("sampleId")) && eq(record.path("allele"),allele) && eq(record.path("geneSymbol"),c.at("/gene/symbol")) && eq(record.path("method"),member.at("/detection/method")),"RECORD_BINDING_MISMATCH");
            try {
                calls.put(id,new NuclearFamilyRecordAdapter(Bs4NonSegregationBundle.JSON).adapt((ObjectNode)record));
            }
            catch(IllegalArgumentException invalid) {
                throw new Refusal(Objects.toString(invalid.getMessage(), "BS4_RECORD_INVALID"));
            }
        }
        need(records.keySet().equals(members.keySet()),"MEMBER_RECORD_MISSING");
        Map<String,JsonNode> relations=new HashMap<>();
        Set<String> relationIds=new HashSet<>();
        for(var r:f.path("parentage")) {
            String key=relationshipKey(r.path("parentMemberId"), r.path("childMemberId"));
            need(relationIds.add(nfc(r.path("relationshipId"))) && relations.put(key,r)==null,"PARENTAGE_DUPLICATE");
            need("BIOLOGICAL".equals(r.path("relationship").asText())&&"CONFIRMED".equals(r.path("status").asText()),"PARENTAGE_UNCONFIRMED");
            review(r.path("review"),sources);
        }
        need(relations.size()==children.size()*2,"PARENTAGE_ROSTER_INCOMPLETE");
        for(var child:children)for(var parent:List.of(father,mother))need(relations.containsKey(relationshipKey(parent.path("memberId"), child.path("memberId"))),"PARENTAGE_BINDING_MISMATCH");
        var segregation=o.path("nonSegregationReview");
        need("COMPLETE_FROZEN_ROSTER".equals(segregation.path("rosterStatus").asText()),"ROSTER_REVIEW_INCOMPLETE");
        Set<String> reviewed=new HashSet<>();
        for(var r:segregation.path("memberReviews")) {
            String id=nfc(r.path("memberId"));
            var member=members.get(id);
            if(member==null) throw new Refusal("BS4_ROSTER_REVIEW_MISMATCH");
            need(reviewed.add(id)&&eq(r.path("sourcePersonId"),member.path("sourcePersonId")),"ROSTER_REVIEW_MISMATCH");
            var negative=r.path("negativeTest");
            need(eq(negative.path("memberId"),member.path("memberId"))&&eq(negative.path("sampleId"),member.path("sampleId"))&&eq(negative.path("alleleId"),c.at("/variant/alleleId"))&&eq(negative.path("method"),member.at("/detection/method")),"NEGATIVE_TEST_BINDING_MISMATCH");
            need("COMPLETE".equals(negative.path("coverage").asText())&&"TARGET_SNV_DETECTION".equals(negative.path("capability").asText())&&eq(negative.path("result"),calls.get(id).path("call")),"RELIABLE_DETECTION_UNESTABLISHED");
            need("CONFIRMED".equals(r.path("independence").asText())&&"REVIEWED".equals(r.path("clinicalStatus").asText())&&"REVIEWED".equals(r.path("detectionStatus").asText())&&"NONE".equals(r.path("exclusionReason").asText()),"MEMBER_REVIEW_INCOMPLETE");
        }
        need(reviewed.equals(members.keySet()),"ROSTER_REVIEW_MISMATCH");
        disease(c,o.path("diseaseApplicability"),members,f.path("caseId"));
        String group=evidenceGroup(c,f);
        locus(c,o.path("locusAttribution"),members,group);
        List<JsonNode> transmitting=new ArrayList<>();
        for(var parent:List.of(father,mother)) {
            String call=calls.get(nfc(parent.path("memberId"))).path("call").asText();
            if("AFFECTED".equals(parent.path("affectedStatus").asText())&&call.equals("HET_ALT"))transmitting.add(parent);
            else need("UNAFFECTED".equals(parent.path("affectedStatus").asText())&&call.equals("HOM_REF"),"PARENT_GENOTYPE_CLINICAL_CONFLICT");
        }
        need(transmitting.size()==1,"UNIQUE_TRANSMITTING_PARENT_REQUIRED");
        JsonNode parent=transmitting.get(0);
        int count=0;
        Set<String> nonSegregating = new TreeSet<>();
        for(var child:children) {
            String call=calls.get(nfc(child.path("memberId"))).path("call").asText(),affected=child.path("affectedStatus").asText();
            boolean carrier=affected.equals("AFFECTED")&&call.equals("HET_ALT");
            boolean counted=child!=proband&&affected.equals("AFFECTED")&&call.equals("HOM_REF");
            need(carrier||counted||(affected.equals("UNAFFECTED")&&call.equals("HOM_REF")),"CHILD_GENOTYPE_CLINICAL_CONFLICT");
            if(counted) { count++; nonSegregating.add(nfc(child.path("memberId"))); }
            if(child==proband)need(carrier,"PROBAND_AFFECTED_HET_REQUIRED");
        }
        Set<String> declared=new TreeSet<>();
        for(var id:segregation.path("declaredNonSegregatingMembers")) need(declared.add(nfc(id)), "DECLARED_EVENTS_CONFLICT");
        need(declared.equals(nonSegregating), "DECLARED_EVENTS_CONFLICT");
        need(count>=1,"NONSEGREGATION_NOT_ESTABLISHED");
        ArrayNode events=m.createArrayNode();
        children.sort(Comparator.comparing(x->nfc(x.path("memberId"))));
        for(var child:children) {
            ObjectNode key=m.createObjectNode();
            key.set("familyId",f.path("familyId"));
            key.set("alleleId",c.at("/variant/alleleId"));
            key.set("fatherMemberId",father.path("memberId"));
            key.set("motherMemberId",mother.path("memberId"));
            key.set("childMemberId",child.path("memberId"));
            boolean counted=nonSegregating.contains(nfc(child.path("memberId")));
            var event=events.addObject().put("eventId","bs4-nonsegregation-sha256-"+hash.canonicalSha256(key)).put("counted",counted).put("reasonCode",counted?"AFFECTED_REFERENCE_SIBLING":child==proband?"PROBAND_BACKGROUND_ONLY":"NO_BS4_EVENT");
            event.set("identity",key);
            event.putArray("records").add(records.get(nfc(parent.path("memberId"))).deepCopy()).add(records.get(nfc(child.path("memberId"))).deepCopy()).add(records.get(nfc((parent==father?mother:father).path("memberId"))).deepCopy());
            event.putArray("parentage").add(relations.get(relationshipKey(father.path("memberId"), child.path("memberId"))).deepCopy()).add(relations.get(relationshipKey(mother.path("memberId"), child.path("memberId"))).deepCopy());
            event.set("clinical",child.path("clinical").deepCopy());
            event.putArray("parentClinical").add(father.path("clinical").deepCopy()).add(mother.path("clinical").deepCopy());
        }
        return new Decision("",count,events,group);
    }
    private String relationshipKey(JsonNode parent, JsonNode child) {
        return hash.canonicalSha256(m.createArrayNode().add(parent).add(child));
    }
    String evidenceGroup(JsonNode c,JsonNode f) {
        ObjectNode key=m.createObjectNode().put("domain","pp1-pp4-locus-family-v1");
        key.set("familyId",f.path("familyId"));
        key.set("hgncId",c.at("/gene/hgncId"));
        key.set("mondoId",c.at("/disease/mondoId"));
        key.set("alleleId",c.at("/variant/alleleId"));
        return "pp1-pp4-group-sha256-"+hash.canonicalSha256(key);
    }
    private void disease(JsonNode c,JsonNode d,Map<String,JsonNode> members,JsonNode caseId) {
        need("ESTABLISHED".equals(d.path("geneDiseaseValidity").asText())&&d.path("validityIndependentOfFamily").asBoolean(),"GENE_DISEASE_VALIDITY_UNESTABLISHED");
        need("NOT_REQUIRED".equals(d.path("linkageRequired").asText()),"FORMAL_LINKAGE_OUTSIDE_SCOPE");
        need("UNRESTRICTED".equals(d.path("conditionMode").asText())&&d.path("conditions").isEmpty(),"CONDITIONAL_APPLICABILITY_OUTSIDE_SCOPE");
        Set<String> affected=new HashSet<>();
        for(var member:members.values())if("AFFECTED".equals(member.path("affectedStatus").asText()))affected.add(nfc(member.path("memberId")));
        Set<String> observed=new HashSet<>();
        for(var r:d.path("affectedMembers"))need(observed.add(nfc(r.path("memberId")))&&eq(r.path("mondoId"),c.at("/disease/mondoId"))&&eq(r.path("mechanism"),d.path("mechanism"))&&"SAME_DISEASE_CONFIRMED".equals(r.path("conclusion").asText()),"AFFECTED_DISEASE_REVIEW_CONFLICT");
        need(affected.equals(observed),"AFFECTED_DISEASE_REVIEW_INCOMPLETE");
        Set<String> expected=new HashSet<>(), actual=new HashSet<>();
        for(String member:affected) for(String kind:List.of("PHENOCOPY","DIFFERENT_DIAGNOSIS","INCOMPLETE_CLINICAL_EVALUATION","GENETIC_HETEROGENEITY","DUAL_DIAGNOSIS")) expected.add(member+"/"+kind);
        for(var r:d.path("alternativeExplanations")) {
            String key=nfc(r.path("memberId"))+"/"+r.path("kind").asText();
            need(actual.add(key)&&expected.contains(key),"ALTERNATIVE_REVIEW_ROSTER_CONFLICT");
            var investigation=r.path("investigation");
            need(eq(investigation.path("memberId"),r.path("memberId"))&&eq(investigation.path("caseId"),caseId)&&eq(investigation.path("mondoId"),c.at("/disease/mondoId")),"ALTERNATIVE_INVESTIGATION_BINDING_MISMATCH");
            need("EXCLUDED".equals(r.path("assessment").asText())&&investigation.path("evaluationComplete").asBoolean()&&!investigation.path("alternativeDetected").asBoolean()&&investigation.path("unresolvedFindings").isEmpty(),"ALTERNATIVE_EXPLANATION_UNRESOLVED");
        }
        need(actual.equals(expected),"ALTERNATIVE_REVIEW_INCOMPLETE");
    }
    private void locus(JsonNode c,JsonNode l,Map<String,JsonNode> members,String group) {
        need("CODING_AND_SPLICE".equals(l.path("coverageScope").asText())&&"COMPLETE".equals(l.path("coverageStatus").asText())&&"COMPLETE_FROZEN_ROSTER".equals(l.path("candidateRosterStatus").asText())&&"SOLE_TARGET_NO_SHARING".equals(l.path("allocation").asText()),"LOCUS_COVERAGE_OR_ALLOCATION_UNESTABLISHED");
        for(var member:members.values())need(eq(l.path("method"),member.at("/detection/method")),"LOCUS_METHOD_CONFLICT");
        Set<String> ids=new HashSet<>(),alleles=new HashSet<>();
        int target=0;
        for(var candidate:l.path("candidates")) {
            need(ids.add(nfc(candidate.path("candidateId")))&&alleles.add(nfc(candidate.path("alleleId"))),"CANDIDATE_DUPLICATE");
            need(eq(candidate.path("hgncId"),c.at("/gene/hgncId")),"CANDIDATE_GENE_MISMATCH");
            if(eq(candidate.path("alleleId"),c.at("/variant/alleleId"))) {
                need("TARGET".equals(candidate.path("disposition").asText()),"TARGET_ATTRIBUTION_CONFLICT");
                target++;
            }
            else need("EXCLUDED_INDEPENDENTLY".equals(candidate.path("disposition").asText()),"OTHER_CANDIDATE_UNRESOLVED");
        }
        need(target==1,"SOLE_TARGET_REQUIRED");
        need("COMPLETE_FROZEN_ROSTER".equals(l.path("usedEvidenceStatus").asText()),"USED_EVIDENCE_INCOMPLETE");
        Set<String> criteria=new HashSet<>();
        for(var e:l.path("usedEvidence"))need(criteria.add(e.path("criterionId").asText())&&group.equals(e.path("evidenceGroupId").asText())&&"NOT_APPLIED_NO_CONFLICT".equals(e.path("status").asText()),"SHARED_EVIDENCE_OCCUPIED_OR_CONFLICT");
        need(criteria.equals(Set.of("ACMG_AMP_2015:PP1","ACMG_AMP_2015:PP4","ACMG_AMP_2015:BS4")),"USED_EVIDENCE_INCOMPLETE");
    }
}
