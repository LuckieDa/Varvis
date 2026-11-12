package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.DateTimeException;
import java.time.ZoneOffset;
import java.util.*;

/** Single healthy-adult source and applicability gates. Labels never replace clinical facts. */
public final class Bs2HealthyAdultPolicy {
    private final ObjectMapper m = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter hash = new RuntimeEvidenceV2Adapter(m);
    public record Decision(String reason, String evidenceGroupId) { }
    private static final class Refusal extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        Refusal(String reason) { super("BS2_" + reason); }
    }
    private static void need(boolean ok, String reason) { if (!ok) throw new Refusal(reason); }
    private static String nfc(JsonNode n) { return Normalizer.normalize(n.asText(), Normalizer.Form.NFC); }
    private boolean eq(JsonNode a, JsonNode b) { return !a.isMissingNode() && !b.isMissingNode() && hash.canonicalSha256(a).equals(hash.canonicalSha256(b)); }
    private static boolean is(JsonNode n, String key, String value) { return value.equals(n.path(key).asText()); }
    private static boolean date(JsonNode n) { try { Instant.parse(n.asText()); return true; } catch (java.time.format.DateTimeParseException e) { return false; } }
    private Set<String> unique(JsonNode a, String reason) {
        Set<String> ids = new HashSet<>(); for (var n : a) need(ids.add(nfc(n)), reason); return ids;
    }
    private void refs(JsonNode ids, Set<String> allowed) { need(ids.isArray() && !ids.isEmpty(), "SOURCE_REFERENCE_REQUIRED"); need(allowed.containsAll(unique(ids,"SOURCE_REFERENCE_DUPLICATE")),"SOURCE_REFERENCE_UNRESOLVED"); }
    private void allRefs(JsonNode n, Set<String> allowed) { if(n.isObject() && n.has("sourceIds")) refs(n.path("sourceIds"),allowed); for(var child:n) if(child.isContainerNode())allRefs(child,allowed); }
    private void review(JsonNode n, Set<String> allowed) { need(is(n,"status","REVIEWED") && date(n.path("date")),"REVIEW_REQUIRED"); refs(n.path("sourceIds"),allowed); }
    public Decision validate(ObjectNode c, ObjectNode o, ObjectNode p) {
        try { return checked(c,o,p); } catch (Refusal refusal) { return new Decision(refusal.getMessage(),""); }
    }
    private Decision checked(ObjectNode c, ObjectNode o, ObjectNode p) {
        need(c.at("/patientContext/familyContextId").isTextual() && !c.at("/patientContext/familyContextId").asText().isBlank(),"FAMILY_CONTEXT_REQUIRED");
        var f=o.path("facts"); var b=f.path("binding"); var a=o.path("assessment");
        need(p.path("criterionIds").size()==1 && "ACMG_AMP_2015:BS2".equals(p.at("/criterionIds/0").asText()),"POLICY_CRITERION_SCOPE_MISMATCH");
        Set<String> sources=new HashSet<>(), factsSources=new HashSet<>(), clinicalSources=new HashSet<>();
        for(String part:Bs2HealthyAdultBundle.PARTS) {
            var doc=o.path(part); need(doc.path("sources").size()==1,"SINGLE_SOURCE_PER_PART_REQUIRED");
            Set<String> partSources=new HashSet<>(); for(var s:doc.path("sources"))partSources.add(nfc(s.path("sourceId")));
            allRefs(doc,partSources); ObjectNode projection=((ObjectNode)doc).deepCopy(); projection.remove("sources");
            for(var s:doc.path("sources")) {
                need(sources.add(nfc(s.path("sourceId"))) && date(s.path("acquiredAt")),"SOURCE_IDENTITY_INVALID");
                byte[] raw=s.path("rawUtf8").asText().getBytes(StandardCharsets.UTF_8);
                need(FamilyDeNovoJson.sha256(raw).equals(s.path("rawSha256").asText()),"SOURCE_RAW_PIN_MISMATCH");
                ObjectNode report; String canonical;
                try { report=Bs2HealthyAdultBundle.JSON.parse(m,raw); canonical=hash.canonicalSha256(report); }
                catch(IllegalArgumentException invalid) { throw new Refusal("SOURCE_DOCUMENT_INVALID"); }
                need(canonical.equals(s.path("canonicalSha256").asText()),"SOURCE_CANONICAL_PIN_MISMATCH");
                need(eq(report,projection),"SOURCE_FACTS_MISMATCH");
            }
            if(part.equals("facts"))factsSources.addAll(partSources);
            if(part.equals("healthyAdultReview"))clinicalSources.addAll(partSources);
            String digest=hash.canonicalSha256(doc);
            need(digest.equals(a.at("/partPins/"+part).asText()) && digest.equals(p.at("/partPins/"+part).asText()),"PART_PIN_MISMATCH");
            need(eq(doc.path("binding"),b),"PART_BINDING_MISMATCH");
            if(!part.equals("facts"))review(doc.path("review"),partSources);
        }
        review(a.path("factsReview"),factsSources); review(a.path("review"),sources);
        ObjectNode ap=((ObjectNode)a).deepCopy(); ap.remove("contentSha256");
        need(hash.canonicalSha256(ap).equals(a.path("contentSha256").asText()) && hash.canonicalSha256(a).equals(p.path("assessmentSha256").asText()),"ASSESSMENT_PIN_MISMATCH");
        need(is(p,"mode","SINGLE_HEALTHY_ADULT_AD") && is(p,"qualityProfile","bs2-diploid-snv-dp20-gq20-explicit-ft-v1"),"POLICY_SCOPE_UNSUPPORTED");
        for(var pair:Map.of("contextId","/contextId","alleleId","/variant/alleleId","hgncId","/gene/hgncId","mondoId","/disease/mondoId","moi","/moi","patientContextId","/patientContext/patientContextId","familyContextId","/patientContext/familyContextId").entrySet())need(eq(b.path(pair.getKey()),c.at(pair.getValue())),"CONTEXT_BINDING_MISMATCH");
        need(eq(f.path("observationId"),b.path("observationId")) && eq(f.path("familyId"),b.path("familyContextId")),"FAMILY_OR_OBSERVATION_BINDING_MISMATCH");
        var v=c.path("variant"); var allele=v.path("normalizedAllele");
        need(is(c,"moi","AUTOSOMAL_DOMINANT") && is(allele,"assembly","GRCh38") && allele.path("contig").asText().matches("([1-9]|1[0-9]|2[0-2])") && is(v,"variantType","SNV") && is(v.path("assemblyLineage"),"mappingStatus","NATIVE") && eq(allele,v.path("inputAllele")) && eq(allele,v.path("evidenceQueryAllele")) && eq(allele,b.path("allele")) && allele.path("reference").asText().matches("[ACGT]") && allele.path("alternate").asText().matches("[ACGT]") && !eq(allele.path("reference"),allele.path("alternate")),"NATIVE_AUTOSOMAL_AD_SNV_REQUIRED");
        need(f.path("members").size()==1 && f.path("records").size()==1 && f.path("parentage").isEmpty(),"SINGLE_PROBAND_REQUIRED");
        var member=f.path("members").get(0); var record=f.path("records").get(0); var detection=member.path("detection"); var cl=f.path("clinical");
        need(is(member,"role","PROBAND") && is(member,"affectedStatus","UNAFFECTED") && is(c.path("patientContext"),"role","PROBAND") && is(c.path("patientContext"),"affectedStatus","UNAFFECTED") && eq(member.path("memberId"),b.path("patientContextId")),"PROBAND_BINDING_MISMATCH");
        need(eq(detection.path("binding"),b) && eq(detection.path("memberId"),member.path("memberId")) && detection.path("targetAltIndex").bigIntegerValue().equals(java.math.BigInteger.ONE) && detection.path("ploidy").bigIntegerValue().equals(java.math.BigInteger.TWO) && is(detection,"coverage","COVERED") && is(detection,"capability","TARGET_DETECTION") && is(detection,"quality","VALID") && !Set.of("UNKNOWN","CONFLICT","UNRESOLVED").contains(detection.path("method").asText()),"DETECTION_UNESTABLISHED");
        ObjectNode recordAllele=((ObjectNode)record.path("variantKey")).deepCopy(); recordAllele.set("contig",recordAllele.remove("chromosome"));
        need(eq(recordAllele,allele) && eq(record.path("sampleId"),member.path("sampleId")) && eq(record.path("geneSymbol"),c.at("/gene/symbol")) && is(record,"role","PROBAND") && is(record.path("observation"),"role","PROBAND"),"RECORD_BINDING_MISMATCH");
        try { new Bs2HealthyAdultRecordAdapter().adapt((ObjectNode)f); } catch(IllegalArgumentException invalid) { throw new Refusal(invalid.getMessage().replaceFirst("^BS2_","")); }
        need(is(record.path("observation"),"call","HET_ALT"),"TARGET_HET_REQUIRED");
        need(eq(cl.path("binding"),b) && eq(cl.path("probandMemberId"),member.path("memberId")) && is(cl,"mosaicism","REVIEWED_NONMOSAIC") && date(cl.path("observedAt")),"CLINICAL_FACT_BINDING_INVALID");
        healthy(c,o,member,cl);
        String group=use(o,f,member,clinicalSources);
        return new Decision("",group);
    }
    private static boolean integer(JsonNode n,long min,long max) {
        return n.isIntegralNumber() && n.canConvertToLong() && n.longValue()>=min && n.longValue()<=max;
    }
    private Instant time(JsonNode n) { need(date(n),"CLINICAL_TIME_INVALID"); return Instant.parse(n.asText()); }
    /** Necessary calendar compatibility for the existing equal-completed-age scope; no birthday inference. */
    private static boolean sameAgeDatesCompatible(Instant observed, Instant assessed) {
        try {
            var observedDate = observed.atOffset(ZoneOffset.UTC).toLocalDate();
            var assessedDate = assessed.atOffset(ZoneOffset.UTC).toLocalDate();
            // Calendar years, not a fixed 365-day clinical validity limit. Feb 29 -> Feb 28 is < 1 year.
            return observedDate.until(assessedDate).getYears() == 0;
        } catch (DateTimeException outsideCalendarRange) {
            return false;
        }
    }
    private void healthy(JsonNode c,JsonNode o,JsonNode member,JsonNode cl) {
        var r=o.path("healthyAdultReview");var d=o.path("diseaseApplicability");var f=o.path("facts");var b=f.path("binding");
        need(eq(r.path("caseId"),f.path("caseId")) && eq(r.path("occurrenceId"),f.path("occurrenceId")) && eq(r.path("probandMemberId"),member.path("memberId")),"CLINICAL_CASE_MISMATCH");
        need(is(r,"sourceType","DIRECT_INDIVIDUAL_CLINICAL_ASSESSMENT") && is(r,"healthStatus","REVIEWED_UNAFFECTED"),"DIRECT_HEALTHY_CLINICAL_MATERIAL_REQUIRED");
        need(integer(r.path("ageYears"),18,130) && is(r,"ageUnit","YEARS") && cl.path("age").asText().equals(r.path("ageYears").asText()),"ADULT_AGE_UNESTABLISHED");
        Instant assessed=time(r.path("assessedAt"));need(assessed.equals(time(cl.path("observedAt"))),"CLINICAL_TIME_CONFLICT");
        Instant reviewed=time(o.at("/assessment/review/date"));
        for(String part:Bs2HealthyAdultBundle.PARTS) {
            JsonNode review=part.equals("facts")?o.at("/assessment/factsReview"):o.path(part).path("review");
            Instant partDate=time(review.path("date")); need(!partDate.isAfter(reviewed),"REVIEW_TIME_CONFLICT");
            for(var source:o.path(part).path("sources"))need(!time(source.path("acquiredAt")).isAfter(reviewed),"REVIEW_TIME_CONFLICT");
        }
        need(!assessed.isAfter(time(r.at("/review/date"))) && !assessed.isAfter(reviewed),"CLINICAL_TIME_CONFLICT");
        need(is(d,"validity","ESTABLISHED") && is(d,"independence","INDEPENDENT_OF_CURRENT_VARIANT_PERSON_AND_BS2") &&
                unique(d.path("excludedAlleleIds"),"DISEASE_BASIS_ID_DUPLICATE").contains(nfc(b.path("alleleId"))) &&
                unique(d.path("excludedPersonIds"),"DISEASE_BASIS_ID_DUPLICATE").contains(nfc(member.path("memberId"))),"DISEASE_BASIS_UNESTABLISHED");
        need(!Set.of("UNKNOWN","CONFLICT","UNRESOLVED").contains(d.path("mechanism").asText()) && !Set.of("UNKNOWN","CONFLICT","UNRESOLVED").contains(d.path("subtype").asText()) && eq(r.path("mechanism"),d.path("mechanism")) && eq(r.path("subtype"),d.path("subtype")) && is(d,"variantClass","HET_SNV"),"DISEASE_MECHANISM_OR_SUBTYPE_UNESTABLISHED");
        need(is(d,"onset","EARLY_ONSET") && is(d,"model","FULL_BY_EARLY_AGE") && is(d,"ageUnit","YEARS") && integer(d.path("fullyPenetrantAgeYears"),0,17) && r.path("ageYears").longValue()>=d.path("fullyPenetrantAgeYears").longValue(),"EARLY_FULL_PENETRANCE_UNESTABLISHED");
        Set<String> sexes=unique(d.path("sexScope"),"SEX_SCOPE_DUPLICATE"),populations=unique(d.path("populationIds"),"POPULATION_SCOPE_DUPLICATE");
        need(!sexes.isEmpty() && Set.of("MALE","FEMALE").containsAll(sexes) && sexes.contains(r.path("sex").asText()) && eq(r.path("sex"),c.at("/patientContext/sex")),"SEX_APPLICABILITY_UNESTABLISHED");
        need(!populations.isEmpty() && !populations.contains("UNKNOWN") && !is(r,"populationId","UNKNOWN") && populations.contains(nfc(r.path("populationId"))),"POPULATION_APPLICABILITY_UNESTABLISHED");
        var def=d.path("definition");need(is(def,"mode","ALL_OF") && !def.path("requirements").isEmpty() && eq(def.path("definitionId"),r.path("definitionId")) && eq(def.path("version"),r.path("definitionVersion")),"EXAM_DEFINITION_UNESTABLISHED");
        need(is(r,"rosterStatus","COMPLETE_FROZEN_ROSTER"),"EXAM_ROSTER_UNESTABLISHED");
        Map<String,JsonNode> findings=new HashMap<>();Set<String> ids=new HashSet<>();
        for(var finding:r.path("findings")) {
            need(findings.put(nfc(finding.path("featureId")),finding)==null && ids.add(nfc(finding.path("findingId"))),"EXAM_IDENTITY_DUPLICATE");
            need(Set.of("NORMAL","ABSENT").contains(finding.path("result").asText()),"EXAM_NEGATIVE_RESULT_UNESTABLISHED");
            need(is(finding,"examination","PERFORMED") && is(finding,"applicability","APPLICABLE") && is(finding,"reliability","REVIEWED_RELIABLE") && !is(finding,"methodId","UNKNOWN"),"EXAM_METHOD_OR_COMPLETION_UNESTABLISHED");
            need(eq(finding.path("ageYears"),r.path("ageYears")),"EXAM_AGE_CONFLICT");
        }
        need(Set.of("UNKNOWN","NOT_CONSISTENT").contains(cl.path("phenotypeCategory").asText()) && cl.path("phenotypeObservations").isEmpty() && unique(cl.path("exclusions"),"EXAM_IDENTITY_DUPLICATE").equals(ids),"CLINICAL_FACT_ROSTER_CONFLICT");
        Set<String> required=new HashSet<>();
        for(var q:def.path("requirements")) {
            need(required.add(nfc(q.path("featureId"))),"EXAM_REQUIREMENT_DUPLICATE");var actual=findings.get(nfc(q.path("featureId")));
            if (actual == null) throw new Refusal("EXAM_REQUIRED_MISSING");
            need(!is(q,"methodId","UNKNOWN") && Set.of("NORMAL","ABSENT").contains(q.path("expectedResult").asText()) && eq(q.path("methodId"),actual.path("methodId")) && eq(q.path("expectedResult"),actual.path("result")),"EXAM_DEFINITION_RESULT_CONFLICT");
            need(integer(q.path("ageMinimumYears"),0,130) && integer(q.path("ageMaximumYears"),0,130) && q.path("ageMinimumYears").longValue()<=q.path("ageMaximumYears").longValue() && r.path("ageYears").longValue()>=q.path("ageMinimumYears").longValue() && r.path("ageYears").longValue()<=q.path("ageMaximumYears").longValue(),"EXAM_AGE_WINDOW_UNSUPPORTED");
            need(integer(q.path("validityDays"),0,36500),"EXAM_VALIDITY_WINDOW_UNESTABLISHED");
            Instant observed=time(actual.path("observedAt"));
            need(!observed.isAfter(assessed) && java.time.Duration.between(observed,assessed).compareTo(java.time.Duration.ofDays(q.path("validityDays").longValue()))<=0,"EXAM_EXPIRED_OR_TIME_CONFLICT");
            need(sameAgeDatesCompatible(observed,assessed),"EXAM_AGE_TIME_CONFLICT");
        }
        need(required.equals(findings.keySet()),"UNRESOLVED_ADDITIONAL_FINDINGS");
        Set<String> masking=new HashSet<>();
        for(var item:r.path("masking"))need(masking.add(item.path("kind").asText()) && is(item,"status","REVIEWED_ABSENT"),"MASKING_FACTOR_UNESTABLISHED");
        need(masking.equals(Set.of("TREATMENT","PREVENTIVE_INTERVENTION","TRANSPLANT","OTHER_MASKING_OR_UNRESOLVED_FACTOR")),"MASKING_ROSTER_INCOMPLETE");
    }
    private String use(JsonNode o,JsonNode f,JsonNode member,Set<String> clinicalSources) {
        var u=o.path("evidenceUse");var b=f.path("binding");
        ObjectNode group=m.createObjectNode().put("domain","pp1-pp4-locus-family-v1");group.set("familyId",f.path("familyId"));
        for(String key:List.of("hgncId","mondoId","alleleId"))group.set(key,b.path(key));
        String groupId="pp1-pp4-group-sha256-"+hash.canonicalSha256(group);
        need(eq(u.path("personId"),member.path("memberId")) && eq(u.path("caseId"),f.path("caseId")) && eq(u.path("occurrenceId"),f.path("occurrenceId")) && eq(u.path("familyId"),f.path("familyId")) && groupId.equals(u.path("evidenceGroupId").asText()) && unique(u.path("clinicalSourceIds"),"CLINICAL_SOURCE_DUPLICATE").equals(clinicalSources),"EVIDENCE_USE_BINDING_MISMATCH");
        Set<String> criteria=new HashSet<>();
        for(var item:u.path("uses"))need(criteria.add(item.path("criterionId").asText()) && is(item,"status","NOT_APPLIED_NO_CONFLICT") && eq(item.path("personId"),member.path("memberId")) && eq(item.path("alleleId"),b.path("alleleId")) && unique(item.path("clinicalSourceIds"),"CLINICAL_SOURCE_DUPLICATE").equals(clinicalSources),"SHARED_CLINICAL_EVIDENCE_OCCUPIED_OR_CONFLICT");
        need(is(u,"rosterStatus","COMPLETE_FROZEN_ROSTER") && criteria.equals(Set.of("ACMG_AMP_2015:BS2","ACMG_AMP_2015:BS4","ACMG_AMP_2015:PP1","ACMG_AMP_2015:PP4")),"SHARED_CLINICAL_USE_ROSTER_INCOMPLETE");
        return groupId;
    }
}
