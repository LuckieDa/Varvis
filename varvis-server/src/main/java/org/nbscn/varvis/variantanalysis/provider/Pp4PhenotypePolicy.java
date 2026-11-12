package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import org.springframework.lang.Nullable;

/** Single-case source and applicability gates. Labels never replace underlying clinical/count facts. */
public final class Pp4PhenotypePolicy {
    private final ObjectMapper m = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter hash = new RuntimeEvidenceV2Adapter(m);
    public record Decision(String reason, String evidenceGroupId, @Nullable Pp4DiagnosticYield.Result yield) { }
    private static final class Refusal extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        Refusal(String reason) { super("PP4_" + reason); }
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
        try { return checked(c,o,p); } catch (Refusal refusal) { return new Decision(refusal.getMessage(),"",null); }
    }
    private Decision checked(ObjectNode c, ObjectNode o, ObjectNode p) {
        var f=o.path("facts"); var b=f.path("binding"); var a=o.path("assessment");
        need(p.path("criterionIds").size()==1 && "ACMG_AMP_2015:PP4".equals(p.at("/criterionIds/0").asText()),"POLICY_CRITERION_SCOPE_MISMATCH");
        Set<String> sources=new HashSet<>(), factsSources=new HashSet<>(), clinicalSources=new HashSet<>();
        for(String part:Pp4PhenotypeBundle.PARTS) {
            var doc=o.path(part); need(doc.path("sources").size()==1,"SINGLE_SOURCE_PER_PART_REQUIRED");
            Set<String> partSources=new HashSet<>(); for(var s:doc.path("sources"))partSources.add(nfc(s.path("sourceId")));
            allRefs(doc,partSources); ObjectNode projection=((ObjectNode)doc).deepCopy(); projection.remove("sources");
            for(var s:doc.path("sources")) {
                need(sources.add(nfc(s.path("sourceId"))) && date(s.path("acquiredAt")),"SOURCE_IDENTITY_INVALID");
                byte[] raw=s.path("rawUtf8").asText().getBytes(StandardCharsets.UTF_8);
                need(FamilyDeNovoJson.sha256(raw).equals(s.path("rawSha256").asText()),"SOURCE_RAW_PIN_MISMATCH");
                ObjectNode report; String canonical;
                try { report=Pp4PhenotypeBundle.JSON.parse(m,raw); canonical=hash.canonicalSha256(report); }
                catch(IllegalArgumentException invalid) { throw new Refusal("SOURCE_DOCUMENT_INVALID"); }
                need(canonical.equals(s.path("canonicalSha256").asText()),"SOURCE_CANONICAL_PIN_MISMATCH");
                need(eq(report,projection),"SOURCE_FACTS_MISMATCH");
            }
            if(part.equals("facts"))factsSources.addAll(partSources);
            if(part.equals("phenotypeReview"))clinicalSources.addAll(partSources);
            String digest=hash.canonicalSha256(doc);
            need(digest.equals(a.at("/partPins/"+part).asText()) && digest.equals(p.at("/partPins/"+part).asText()),"PART_PIN_MISMATCH");
            need(eq(doc.path("binding"),b),"PART_BINDING_MISMATCH");
            if(!part.equals("facts"))review(doc.path("review"),partSources);
        }
        review(a.path("factsReview"),factsSources); review(a.path("review"),sources);
        ObjectNode ap=((ObjectNode)a).deepCopy(); ap.remove("contentSha256");
        need(hash.canonicalSha256(ap).equals(a.path("contentSha256").asText()) && hash.canonicalSha256(a).equals(p.path("assessmentSha256").asText()),"ASSESSMENT_PIN_MISMATCH");
        need(is(p,"mode","SINGLE_CASE_AD") && is(p,"qualityProfile","pp4-diploid-snv-dp20-gq20-explicit-ft-v1"),"POLICY_SCOPE_UNSUPPORTED");
        for(var pair:Map.of("contextId","/contextId","alleleId","/variant/alleleId","hgncId","/gene/hgncId","mondoId","/disease/mondoId","moi","/moi","patientContextId","/patientContext/patientContextId","familyContextId","/patientContext/familyContextId").entrySet())need(eq(b.path(pair.getKey()),c.at(pair.getValue())),"CONTEXT_BINDING_MISMATCH");
        need(eq(f.path("observationId"),b.path("observationId")) && eq(f.path("familyId"),b.path("familyContextId")),"FAMILY_OR_OBSERVATION_BINDING_MISMATCH");
        var v=c.path("variant"); var allele=v.path("normalizedAllele");
        need(is(c,"moi","AUTOSOMAL_DOMINANT") && is(allele,"assembly","GRCh38") && allele.path("contig").asText().matches("([1-9]|1[0-9]|2[0-2])") && is(v,"variantType","SNV") && is(v.path("assemblyLineage"),"mappingStatus","NATIVE") && eq(allele,v.path("inputAllele")) && eq(allele,v.path("evidenceQueryAllele")) && eq(allele,b.path("allele")) && allele.path("reference").asText().matches("[ACGT]") && allele.path("alternate").asText().matches("[ACGT]") && !eq(allele.path("reference"),allele.path("alternate")),"NATIVE_AUTOSOMAL_AD_SNV_REQUIRED");
        need(f.path("members").size()==1 && f.path("records").size()==1 && f.path("parentage").isEmpty(),"SINGLE_PROBAND_REQUIRED");
        var member=f.path("members").get(0); var record=f.path("records").get(0); var detection=member.path("detection"); var cl=f.path("clinical");
        need(is(member,"role","PROBAND") && is(member,"affectedStatus","AFFECTED") && is(c.path("patientContext"),"role","PROBAND") && is(c.path("patientContext"),"affectedStatus","AFFECTED") && eq(member.path("memberId"),b.path("patientContextId")),"PROBAND_BINDING_MISMATCH");
        need(eq(detection.path("binding"),b) && eq(detection.path("memberId"),member.path("memberId")) && detection.path("targetAltIndex").bigIntegerValue().equals(java.math.BigInteger.ONE) && detection.path("ploidy").bigIntegerValue().equals(java.math.BigInteger.TWO) && is(detection,"coverage","COVERED") && is(detection,"capability","TARGET_DETECTION") && is(detection,"quality","VALID"),"DETECTION_UNESTABLISHED");
        ObjectNode recordAllele=((ObjectNode)record.path("variantKey")).deepCopy(); recordAllele.set("contig",recordAllele.remove("chromosome"));
        need(eq(recordAllele,allele) && eq(record.path("sampleId"),member.path("sampleId")) && eq(record.path("geneSymbol"),c.at("/gene/symbol")) && is(record,"role","PROBAND") && is(record.path("observation"),"role","PROBAND"),"RECORD_BINDING_MISMATCH");
        try { new Pp4PhenotypeRecordAdapter().adapt((ObjectNode)f); } catch(IllegalArgumentException invalid) { throw new Refusal(invalid.getMessage().replaceFirst("^PP4_","")); }
        need(is(record.path("observation"),"call","HET_ALT"),"TARGET_HET_REQUIRED");
        need(eq(cl.path("binding"),b) && eq(cl.path("probandMemberId"),member.path("memberId")) && is(cl,"mosaicism","REVIEWED_NONMOSAIC") && date(cl.path("observedAt")),"CLINICAL_FACT_BINDING_INVALID");
        clinical(c,o,member,cl);
        Pp4DiagnosticYield.Result yield=diagnosticYield(o,f,member);
        String group=attribution(o,f,member,clinicalSources);
        return new Decision("",group,yield);
    }
    private void clinical(JsonNode c, JsonNode o, JsonNode member, JsonNode cl) {
        var r=o.path("phenotypeReview"); var f=o.path("facts"); var d=r.path("definition"); var gd=r.path("geneDiseaseBasis");
        need(eq(r.path("caseId"),f.path("caseId")) && eq(r.path("occurrenceId"),f.path("occurrenceId")) && eq(r.path("probandMemberId"),member.path("memberId")),"CLINICAL_CASE_MISMATCH");
        need(eq(d.path("hgncId"),c.at("/gene/hgncId")) && eq(d.path("mondoId"),c.at("/disease/mondoId")) && is(d,"mode","ALL_OF") && !d.path("requirements").isEmpty(),"PHENOTYPE_DEFINITION_UNESTABLISHED");
        need(r.path("ageYears").isIntegralNumber() && r.path("ageYears").longValue()>=0 && r.path("ageYears").longValue()<=130 && is(r,"ageUnit","YEARS") && cl.path("age").asText().equals(r.path("ageYears").asText()) && d.path("ageMinimumYears").longValue()>=0 && d.path("ageMaximumYears").longValue()<=130 && d.path("ageMinimumYears").longValue()<=d.path("ageMaximumYears").longValue() && r.path("ageYears").longValue()>=d.path("ageMinimumYears").longValue() && r.path("ageYears").longValue()<=d.path("ageMaximumYears").longValue(),"AGE_APPLICABILITY_UNESTABLISHED");
        need(eq(gd.path("hgncId"),d.path("hgncId")) && eq(gd.path("mondoId"),d.path("mondoId")) && eq(gd.path("mechanism"),d.path("mechanism")) && is(gd,"validity","ESTABLISHED") && is(gd,"independence","INDEPENDENT_OF_CURRENT_VARIANT_PP4") && unique(gd.path("excludedAlleleIds"),"VALIDITY_ID_DUPLICATE").contains(nfc(f.at("/binding/alleleId"))),"GENE_DISEASE_BASIS_UNESTABLISHED");
        need(is(r,"rosterStatus","COMPLETE_FROZEN_ROSTER") && eq(r.path("method"),member.at("/detection/method"))
                && !is(r,"method","UNKNOWN") && !is(r,"testVersion","UNKNOWN") && !is(r,"populationId","UNKNOWN"),"CLINICAL_METHOD_OR_ROSTER_UNESTABLISHED");
        need(!is(d,"mechanism","UNKNOWN") && !is(d,"subtype","UNKNOWN") && is(cl,"phenotypeCategory","HIGHLY_SPECIFIC"),"CLINICAL_DEFINITION_CONFLICT");
        Map<String,JsonNode> findings=new HashMap<>(); Set<String> ids=new HashSet<>(), present=new HashSet<>(),absent=new HashSet<>();
        for(var finding:r.path("findings")) {
            need(findings.put(nfc(finding.path("featureId")),finding)==null && ids.add(nfc(finding.path("findingId"))),"CLINICAL_FINDING_DUPLICATE");
            need(date(finding.path("observedAt")) && is(finding,"examination","PERFORMED") && is(finding,"ageApplicable","APPLICABLE")
                    && !is(finding,"method","UNKNOWN") && Set.of("PRESENT","ABSENT").contains(finding.path("presence").asText()),"CLINICAL_FINDING_UNESTABLISHED");
            (is(finding,"presence","PRESENT")?present:absent).add(nfc(finding.path("findingId")));
        }
        need(present.equals(unique(cl.path("phenotypeObservations"),"CLINICAL_FINDING_DUPLICATE")) && absent.equals(unique(cl.path("exclusions"),"CLINICAL_FINDING_DUPLICATE")),"CLINICAL_ROSTER_MISMATCH");
        Set<String> required=new HashSet<>();
        for(var requirement:d.path("requirements")) {
            need(required.add(nfc(requirement.path("featureId"))),"PHENOTYPE_REQUIREMENT_DUPLICATE"); var actual=findings.get(nfc(requirement.path("featureId")));
            need(actual!=null && Set.of("PRESENT","ABSENT").contains(requirement.path("presence").asText()) && eq(requirement.path("presence"),actual.path("presence")),"PHENOTYPE_REQUIREMENT_NOT_MET");
        }
        need(required.equals(findings.keySet()),"UNRESOLVED_ADDITIONAL_FINDINGS");
        Set<String> kinds=new HashSet<>();
        for(var alternative:r.path("alternatives")) {
            need(kinds.add(nfc(alternative.path("kind"))) && eq(alternative.path("caseId"),f.path("caseId")) && is(alternative,"investigation","COMPLETE") && !alternative.path("detected").booleanValue() && alternative.path("unresolvedFindings").isEmpty(),"ALTERNATIVE_EXPLANATION_UNRESOLVED");
            need(!is(alternative,"method","UNKNOWN"),"ALTERNATIVE_METHOD_UNESTABLISHED");
        }
        need(kinds.equals(Set.of("ALTERNATIVE_DIAGNOSIS","PHENOCOPY")) && is(r,"linkage","NOT_REQUIRED"),"ALTERNATIVE_OR_FORMAL_ANALYSIS_REQUIRED");
    }
    private Pp4DiagnosticYield.Result diagnosticYield(JsonNode o, JsonNode f, JsonNode member) {
        var y=o.path("diagnosticYield"); var r=o.path("phenotypeReview");
        need(is(y,"statistic","GENE_SPECIFIC_DIAGNOSTIC_YIELD") && is(y,"unit","FRACTION") && is(y,"denominatorDefinition","PHENOTYPE_ELIGIBLE_TESTED_HISTORICAL_CASES"),"YIELD_DEFINITION_INVALID");
        // A bounded mode plus source-bound enrollment facts, never an inference from the old narrative.
        String mode=y.path("ascertainment").asText();
        need(!Set.of("POSITIVE_ONLY","GENOTYPE_FIRST","RESOLVED_CASES_ONLY").contains(mode),"COHORT_ASCERTAINMENT_INCOMPATIBLE");
        need(mode.equals("PHENOTYPE_FIRST_COMPLETE_TESTED_SERIES") && y.has("ascertainmentDetails"),"COHORT_ASCERTAINMENT_UNESTABLISHED");
        var design=y.path("ascertainmentDetails");
        need(!is(design,"eligibility","UNKNOWN") && !is(design,"testing","UNKNOWN") && !is(design,"resultSelection","UNKNOWN"),"COHORT_ASCERTAINMENT_UNESTABLISHED");
        need(is(design,"eligibility","PHENOTYPE_DEFINITION_BEFORE_TESTING") && is(design,"testing","ALL_ELIGIBLE_TESTED")
                && is(design,"resultSelection","NOT_SELECTED_BY_GENE_RESULT"),"COHORT_ASCERTAINMENT_INCOMPATIBLE");
        need(eq(y.path("definition"),r.path("definition")) && eq(y.path("method"),r.path("method")) && eq(y.path("testVersion"),r.path("testVersion")) && eq(y.path("populationId"),r.path("populationId")) && !is(y,"populationId","UNKNOWN") && is(y,"comparability","REVIEWED_SAME_SCOPE") && is(y,"independence","EXCLUDES_CURRENT_CASE_RELATED_FAMILY_AND_VARIANT_PP4") && is(y,"rosterStatus","COMPLETE_FROZEN_ROSTER"),"YIELD_COMPARABILITY_OR_INDEPENDENCE_UNESTABLISHED");
        long n=y.path("geneSpecificDiagnosedCount").longValue(),den=y.path("eligibleTestedCount").longValue();
        need(den>0 && den<=Pp4DiagnosticYield.MAX_COUNT && n>=0 && n<=den,"YIELD_COUNTS_INVALID");
        Set<String> cases=new HashSet<>(),people=new HashSet<>(),families=new HashSet<>(); long diagnosed=0;
        for(var item:y.path("cases")) {
            need(cases.add(nfc(item.path("caseId"))) && people.add(nfc(item.path("personId"))) && families.add(nfc(item.path("familyId"))),"COHORT_IDENTITY_DUPLICATE");
            need(!eq(item.path("caseId"),f.path("caseId")) && !eq(item.path("personId"),member.path("memberId")) && !eq(item.path("familyId"),f.path("familyId")),"CURRENT_CASE_OR_FAMILY_IN_COHORT");
            need(item.path("phenotypeEligible").booleanValue() && item.path("tested").booleanValue(),"COHORT_DENOMINATOR_INELIGIBLE");
            need(is(item,"classificationBasis","INDEPENDENT_OF_CURRENT_VARIANT_PP4") && !unique(item.path("diagnosticAlleleIds"),"COHORT_ALLELE_DUPLICATE").contains(nfc(f.at("/binding/alleleId"))),"COHORT_CIRCULAR_CLASSIFICATION");
            if(item.path("diagnosedInGene").booleanValue()){ need(!item.path("diagnosticAlleleIds").isEmpty(),"COHORT_DIAGNOSIS_BASIS_MISSING"); diagnosed++; }
        }
        need(y.path("cases").size()==den && diagnosed==n,"COHORT_COUNTS_MISMATCH");
        Pp4DiagnosticYield.Result result;
        try { result=Pp4DiagnosticYield.calculate(n,den); } catch(IllegalArgumentException invalid) { throw new Refusal(invalid.getMessage().replaceFirst("^PP4_","")); }
        if(y.has("declaredYield")) {
            String declaration=y.path("declaredYield").asText(); need(declaration.matches("0\\.[0-9]{1,12}"),"DECLARED_YIELD_INVALID");
            need(new BigDecimal(declaration).multiply(BigDecimal.valueOf(den)).compareTo(BigDecimal.valueOf(n))==0,"DECLARED_YIELD_MISMATCH");
        }
        if(y.has("declaredRawHalfPoints"))need(y.path("declaredRawHalfPoints").longValue()==result.rawHalfPoints(),"DECLARED_POINTS_MISMATCH");
        return result;
    }
    private String attribution(JsonNode o, JsonNode f, JsonNode member, Set<String> clinicalSources) {
        var l=o.path("locusAttribution"); var r=o.path("phenotypeReview"); var b=f.path("binding");
        need(eq(l.path("caseId"),f.path("caseId")) && eq(l.path("method"),r.path("method")) && eq(l.path("testVersion"),r.path("testVersion")) && is(l,"coverage","COMPLETE_CODING_AND_SPLICE") && is(l,"rosterStatus","COMPLETE_FROZEN_ROSTER") && is(l,"allocation","SOLE_TARGET_NO_SHARING"),"LOCUS_COVERAGE_OR_ALLOCATION_UNESTABLISHED");
        Set<String> ids=new HashSet<>(),alleles=new HashSet<>(); int targets=0;
        for(var candidate:l.path("candidates")) {
            need(ids.add(nfc(candidate.path("candidateId"))) && alleles.add(nfc(candidate.path("alleleId"))),"CANDIDATE_IDENTITY_DUPLICATE");
            if(is(candidate,"disposition","TARGET")) { targets++; need(eq(candidate.path("alleleId"),b.path("alleleId")) && eq(candidate.path("hgncId"),b.path("hgncId")) && is(candidate,"exclusion","NOT_EXCLUDED"),"TARGET_ALLOCATION_MISMATCH"); }
            else need(is(candidate,"disposition","EXCLUDED_INDEPENDENTLY") && is(candidate,"exclusion","INDEPENDENT_DIAGNOSTIC_EXCLUSION"),"CANDIDATE_UNRESOLVED_NO_REALLOCATION");
        }
        need(targets==1,"SOLE_TARGET_REQUIRED");
        ObjectNode group=m.createObjectNode().put("domain","pp1-pp4-locus-family-v1");
        group.set("familyId",f.path("familyId")); for(String key:List.of("hgncId","mondoId","alleleId"))group.set(key,b.path(key));
        String groupId="pp1-pp4-group-sha256-"+hash.canonicalSha256(group);
        Set<String> used=new HashSet<>();
        for(var e:l.path("usedEvidence"))need(used.add(e.path("criterionId").asText()) && groupId.equals(e.path("evidenceGroupId").asText()) && is(e,"status","NOT_APPLIED_NO_CONFLICT"),"SHARED_EVIDENCE_OCCUPIED_OR_CONFLICT");
        need(is(l,"usedEvidenceStatus","COMPLETE_FROZEN_ROSTER") && used.equals(Set.of("ACMG_AMP_2015:PP1","ACMG_AMP_2015:BS4","ACMG_AMP_2015:PP4")),"SHARED_EVIDENCE_ROSTER_INCOMPLETE");
        Set<String> clinicalUsed=new HashSet<>();
        for(var e:l.path("clinicalUse"))need(clinicalUsed.add(e.path("criterionId").asText()) && eq(e.path("caseId"),f.path("caseId")) && eq(e.path("personId"),member.path("memberId")) && unique(e.path("clinicalSourceIds"),"CLINICAL_SOURCE_DUPLICATE").equals(clinicalSources) && is(e,"status","NOT_APPLIED_NO_CONFLICT"),"CLINICAL_EVIDENCE_OCCUPIED_OR_CONFLICT");
        need(is(l,"clinicalUseStatus","COMPLETE_FROZEN_ROSTER") && clinicalUsed.equals(Set.of("ACMG_AMP_2015:PS2","ACMG_AMP_2015:PM6","ACMG_AMP_2015:PM3","ACMG_AMP_2015:PS4")),"CLINICAL_EVIDENCE_ROSTER_INCOMPLETE");
        return groupId;
    }
}
