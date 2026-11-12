package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Pm3LiteratureScoring.Phase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Original-rule forms plus invented source records, not real clinical adoption.
 * Expectations: confirmed trans P=4 quarters, unknown P=2, homo=2; cis=NA,
 * opposing explicit claims=conflict, unsupported rules never become data gaps.
 */
class T06Pm3PublishedConditionsTest {
    static final String POLICY="CLINGEN_SVI_PM3_2019_V1";
    static final String URL="https://www.clinicalgenome.org/site/assets/files/3717/svi_proposal_for_pm3_criterion_-_version_1.pdf";
    static final String SHA="17877e54edd58decfe2c374b3d3e2103e52752ea808d48eb7147d007f1ca4c70";
    static final String DOI="10.5281/zenodo.9999999";
    static final String PHASE="* Follow SVI guidance for PM3 ([https://clinicalgenome.org/site/assets/files/3717/svi_proposal_for_pm3_criterion_-_version_1.pdf]"
        +"(https://clinicalgenome.org/site/assets/files/3717/svi_proposal_for_pm3_criterion_-_version_1.pdf)). "
        +"* Parental testing, or another appropriate molecular method (such as cloning each allele separately followed by sequencing), "
        +"must have been performed in order to confirm that the variants are in trans if the patient is compound heterozygous.";
    static final String APPROVAL="Any additional variants used to support the classification of the variant under assessment MUST have been "
        +"previously classified by the CCDS VCEP, using these criteria, and the classification MUST be approved. In other words, "
        +"when awarding points for PM3 for a compound heterozygote, the second variant MUST have been assessed by the VCEP and "
        +"an appropriate classification approved. As noted in the SVI guidance, circularity must be avoided. Variant A can support "
        +"the classification of Variant B, or vice versa but NOT both.";
    final ObjectMapper json=new ObjectMapper();
    final Pm3LiteratureAssessmentTest f=new Pm3LiteratureAssessmentTest();
    final Pm3PublishedConditions conditions=new Pm3PublishedConditions(json);

    static void general(ObjectNode row) {
        var p=row.putObject("policySelection").put("schemaVersion","pm3-specification-context-v2")
                .put("status","SELECTED").put("policy",POLICY).put("reasonCode","PM3_GENERAL_WITH_NO_MATCH_IN_LOCAL_SNAPSHOT");
        p.putArray("matches");
        var scope=p.putObject("context").put("inheritance","AR");
        scope.set("hgncId",row.at("/identities/target/hgncId"));scope.set("diseaseId",row.at("/diseaseContext/diseaseId"));
        p.putObject("generalPolicy").put("policy",POLICY).put("version","1.0").put("sourceUrl",URL)
                .put("sourceRawSha256",SHA).put("basis","SOURCE_REVIEWED_FIXED_SVI_IMPLEMENTATION");
        p.putObject("source").put("catalogRawSha256","a".repeat(64)).put("scope","PINNED_RELEASED_LOCAL_SNAPSHOT")
                .put("worldwideAbsenceClaim",false).put("priorBa1ApprovalInherited",false);
    }
    ObjectNode caseRow() {var c=f.caseRow("PMC123","1",null);general(c.withObject("/resolvedObservations/0"));return c;}
    ObjectNode context(JsonNode row) {
        var c=json.createObjectNode().put("moi","AUTOSOMAL_RECESSIVE");
        c.putObject("scope").put("analysisDomain","GERMLINE");
        c.putObject("gene").set("hgncId",row.at("/identities/target/hgncId"));
        c.withObject("/gene").set("symbol",row.at("/identities/target/geneSymbol"));
        c.putObject("disease").set("mondoId",row.at("/diseaseContext/diseaseId"));
        return c;
    }
    ObjectNode compound() {
        var c=caseRow();var r=c.withObject("/resolvedObservations/0");
        r.withObject("/reportedObservation").put("kind","REPORTED_TWO_ALLELES_PHASE_UNRESOLVED")
                .put("targetRepresentation","c.1G>A").put("partnerRepresentation","c.1G>C");
        r.withObject("/identities").set("partner",f.fixture.row(false).at("/identities/partner"));
        r.withObject("/rarityQualification").putObject("partner").put("qualification","VERIFIED")
                .set("queryAllele",r.at("/identities/partner/allele"));
        r.putObject("partnerQualification").put("classification","PATHOGENIC").put("independentClassification","VERIFIED");
        return c;
    }
    void phase(ObjectNode row,String value) {
        row.withArray("/reportedObservation/facts").addObject().put("field","phaseText").put("value",value)
                .put("method","EXPLICIT_CASE_TYPED_TABLE_PHASE").putObject("citation").put("unitId","phase-cell").put("quote",value);
    }
    ObjectNode entity(JsonNode row,boolean partnerApproval) {
        var e=json.createObjectNode().put("entId","GN900");
        var content=e.putObject("entContent").put("version","1.0").put("approvedOn","2020-01-01T00:00:00Z");
        content.putArray("states").addObject().put("name","Released").put("current",true);
        var doi=content.putObject("doi").put("docDoi",DOI);
        var author=doi.putArray("authors").addObject();author.putObject("role").put("id","researchgroup");
        author.putObject("person_or_org").put("name","SYNTHETIC VCEP");
        var ld=e.putObject("ld");var h=ld.putArray("Gene").addObject().putObject("entContent").putObject("HGNC");
        h.set("symbol",row.at("/identities/target/geneSymbol"));h.set("hgnc_id",row.at("/identities/target/hgncId"));
        var gene=ld.putArray("RuleSet").addObject().putObject("entContent").putArray("genes").addObject();
        gene.set("gene",row.at("/identities/target/geneSymbol"));
        gene.putArray("diseases").addObject().put("preferredModeOfInheritance","Autosomal recessive inheritance")
                .set("preferredMondoId",row.at("/diseaseContext/diseaseId"));
        var clause=ld.putArray("CriteriaCode").addObject().putObject("entContent").put("label","PM3")
                .put("applicability","Applicable").put("baseStrength","Pathogenic").put("defaultStrength","Pathogenic Moderate");
        clause.putArray("gene").add(row.at("/identities/target/geneSymbol"));
        clause.putArray("disease").add(row.at("/diseaseContext/diseaseId"));
        if(partnerApproval)clause.put("instructionsToUse",APPROVAL);
        for(String strength:List.of("Supporting","Moderate","Strong","Very Strong"))
            clause.withArray("/strengthDescriptor").addObject().put("strength",strength).put("applicability","Applicable").put("text",PHASE);
        return e;
    }
    void published(ObjectNode observations,ObjectNode row,ObjectNode entity) throws Exception {
        var raw=json.writeValueAsBytes(entity);String sha=FamilyDeNovoJson.sha256(raw);
        observations.withObject("/specificationOriginals").put(sha,Base64.getEncoder().encodeToString(raw));
        var p=row.putObject("policySelection").put("schemaVersion","pm3-specification-context-v2").put("status","SELECTED").put("policy",POLICY);
        if("Not applicable".equals(entity.at("/ld/CriteriaCode/0/entContent/applicability").asText()))p.put("status","NOT_APPLICABLE");
        var ctx=p.putObject("context").put("inheritance","AR");ctx.set("hgncId",row.at("/identities/target/hgncId"));ctx.set("diseaseId",row.at("/diseaseContext/diseaseId"));
        var match=p.putArray("matches").addObject().put("specificationKey","GN900@1.0")
                .put("scopePointer","/ld/RuleSet/0/entContent/genes/0/diseases/0").put("clausePointer","/ld/CriteriaCode/0");
        match.putObject("source").put("rawSha256",sha).put("jsonPointer","");
        match.set("clause",entity.at("/ld/CriteriaCode/0"));
        match.put("clauseSha256",Pm3SourceDigest.sha256(json,match.path("clause")));
    }
    ObjectNode calculate(ObjectNode c,ObjectNode originals) {
        var row=c.withObject("/resolvedObservations/0");var d=conditions.assess(originals,row,context(row));
        var paper=f.paper("PMC123",c);
        return new Pm3LiteratureAssessment(json).assessWithPublishedConditions(paper,f.request(),java.util.Map.of(conditions.rowKey(row),d));
    }
    int points(JsonNode result){return result.at("/diseaseAssessments/0/calculation/quarterPoints").asInt();}

    @Test void explicitGeneralSviNeedsItsSourceAndLocalScopeButNotAnInventedGeneSpecification() {
        var c=caseRow();var r=calculate(c,json.createObjectNode());assertEquals(2,points(r));
        assertEquals("SUPPORTING",r.at("/diseaseAssessments/0/calculation/strength").asText());
        c.withObject("/resolvedObservations/0/policySelection/generalPolicy").put("sourceRawSha256","b".repeat(64));
        r=calculate(c,json.createObjectNode());assertEquals(0,points(r));assertTrue(r.toString().contains("PM3_GENERAL_SVI_SOURCE_REQUIRED"));
    }
    @Test void bareTransDoesNotAcquireMethodButOneParentTestingBothAllelesIsSufficient() {
        var c=compound();var row=c.withObject("/resolvedObservations/0");phase(row,"trans");
        var d=conditions.assess(json.createObjectNode(),row,context(row));assertEquals(Phase.UNKNOWN,d.phase());
        assertEquals(0,points(calculate(c,json.createObjectNode())));
        assertEquals(List.of(new Pm3LiteratureScoring.Requirement("PM3_PHASE_ORIGINAL_MISSING","MISSING_INFORMATION")),d.requirements());
        var test=row.withArray("/reportedObservation/facts").addObject();test.putObject("citation").put("unitId","parent-test");
        var parent=test.putObject("parentTest").put("parent","MOTHER");
        parent.putObject("results").put("c.1G>A",true).put("c.1G>C",false);
        assertEquals(Phase.TRANS,conditions.assess(json.createObjectNode(),row,context(row)).phase());
        assertEquals(4,points(calculate(c,json.createObjectNode())));
    }
    @Test void molecularAndOppositeBareClaimsConflictSymmetricallyAndAreNotCisNa() {
        for(String[] values:List.of(new String[]{"cis","trans"},new String[]{"trans","cis"})) {
            var c=compound();var row=c.withObject("/resolvedObservations/0");
            phase(row,"Cloning and sequencing of both alleles confirmed the variants in "+values[0]+".");phase(row,values[1]);
            assertEquals(Phase.CONFLICT,conditions.assess(json.createObjectNode(),row,context(row)).phase());
            var r=calculate(c,json.createObjectNode());assertEquals(0,points(r));
            assertEquals("CONFLICT",r.at("/diseaseAssessments/0/calculation/contributions/0/qualificationStatus").asText());
        }
    }
    @Test void rawRuleIsReplayedAndUnknownConditionIsSoftwareGapRatherThanMissingClinicalData() throws Exception {
        var c=caseRow();var row=c.withObject("/resolvedObservations/0");var original=json.createObjectNode();
        var entity=entity(row,false);published(original,row,entity);assertEquals(2,points(calculate(c,original)));
        entity.withObject("/ld/CriteriaCode/0/entContent").put("additionalComments","Requires an unimplemented prerequisite");
        published(original,row,entity);var r=calculate(c,original);assertEquals(0,points(r));
        assertEquals("RULE_NOT_IMPLEMENTED",r.at("/diseaseAssessments/0/calculation/contributions/0/qualificationStatus").asText());
        assertEquals("NOT_EVALUATED",r.at("/diseaseAssessments/0/calculation/status").asText());
    }
    @Test void notApplicableSourceDoesNotRequireUnnecessaryRarityOrPartner() throws Exception {
        var c=compound();var row=c.withObject("/resolvedObservations/0");row.remove("rarityQualification");
        var original=json.createObjectNode();var e=entity(row,false);e.withObject("/ld/CriteriaCode/0/entContent").put("applicability","Not applicable");
        published(original,row,e);var result=calculate(c,original);
        assertEquals("NOT_APPLICABLE",result.at("/diseaseAssessments/0/calculation/status").asText());assertEquals(0,points(result));
    }
    @Test void sourceMutationWrongGeneAndDecisionRebindingCannotScore() throws Exception {
        var c=caseRow();var row=c.withObject("/resolvedObservations/0");var original=json.createObjectNode();published(original,row,entity(row,false));
        var d=conditions.assess(original,row,context(row));row.put("mutatedAfterQualification",true);assertFalse(d.binds(json,row));
        var result=new Pm3LiteratureAssessment(json).assessWithPublishedConditions(f.paper("PMC123",c),f.request(),java.util.Map.of(conditions.rowKey(row),d));
        assertEquals(0,points(result));
        row.remove("mutatedAfterQualification");row.withObject("/policySelection/matches/0/source").put("rawSha256","0".repeat(64));
        assertEquals(0,points(calculate(c,original)));
    }
    @Test void sourceDecimalMetadataIsNotAnIntegerOnlyScientificIdentityAndDoesNotAlterPoints() throws Exception {
        var c=caseRow();var row=c.withObject("/resolvedObservations/0");
        row.withObject("/rarityQualification/target").put("sourceAfMetadata",0.0000005);
        var original=json.createObjectNode();var entity=entity(row,false);
        entity.withObject("/ld/CriteriaCode/0").put("sourceRevisionMetadata",0.5);published(original,row,entity);
        var d=conditions.assess(original,row,context(row));assertTrue(d.requirements().isEmpty(),d.display().toPrettyString());
        assertTrue(d.binds(json,row));assertEquals(2,points(calculate(c,original)));
        row.withObject("/rarityQualification/target").put("sourceAfMetadata",0.0000006);assertFalse(d.binds(json,row));
    }

    void approvalOriginal(ObjectNode originals,ObjectNode row,String comment,String attribute,String issuer,String document) {
        var allele=row.at("/identities/partner/allele");String gene=row.at("/identities/target/geneSymbol").asText(),hgnc=row.at("/identities/target/hgncId").asText();
        String xml="<VariationArchive Accession='VCV000000001' Version='1'><ClassifiedRecord><SimpleAllele><GeneList><Gene Symbol='"+gene+"' HGNC_ID='"+hgnc+"'/></GeneList><Location><SequenceLocation Assembly='GRCh38' Chr='12' positionVCF='"+allele.path("position").asLong()+"' referenceAlleleVCF='G' alternateAlleleVCF='C'/></Location></SimpleAllele></ClassifiedRecord><ClinicalAssertion><ClinVarAccession Accession='SCV000000001' Version='1' OrgID='999' SubmitterName='"+issuer+"'/><RecordStatus>current</RecordStatus><Classification DateLastEvaluated='2025-01-01'><ReviewStatus>reviewed by expert panel</ReviewStatus><GermlineClassification>Pathogenic</GermlineClassification><Comment>"+comment.replace("&","&amp;").replace("<","&lt;")+"</Comment></Classification><TraitSet><Trait><XRef DB='MONDO' ID='0009861'/></Trait></TraitSet>";
        if(attribute!=null)xml+="<AttributeSet><Attribute Type='AssertionMethod'>"+attribute+document+"</Attribute></AttributeSet>";
        xml+="</ClinicalAssertion></VariationArchive>";
        byte[] raw=xml.getBytes(StandardCharsets.UTF_8);String sha=FamilyDeNovoJson.sha256(raw);
        originals.withObject("/partnerSourceOriginals").put(sha,Base64.getEncoder().encodeToString(raw));
        var q=row.withObject("/partnerQualification");q.putArray("qualifiedScvs").add("SCV000000001.1");
        phase(row,"phase unknown");
        q.putArray("sourceAssertions").addObject().put("vcv","VCV000000001.1").put("scv","SCV000000001.1")
                .put("sourceSha256",sha).put("sourceClassification","Pathogenic").put("sourceLocator","/VariationArchive/ClinicalAssertion[1]");
    }
    @Test void actualExpertApprovalSupportsDifferentSourceStatementFormsWithoutMandatoryPunctuation() throws Exception {
        for(String comment:List.of("Classification performed using https://doi.org/"+DOI+".",
                "This variant was classified according to the specifications (doi:"+DOI+").",
                "We applied the criteria published at https://doi.org/"+DOI+" for this classification.",
                "Classification performed using https://doi.org/"+DOI+". The variant was not observed in population databases.",
                "The variant was not observed in population databases. Classification performed using https://doi.org/"+DOI+".")) {
            var c=compound();var row=c.withObject("/resolvedObservations/0");var original=json.createObjectNode();published(original,row,entity(row,true));
            approvalOriginal(original,row,comment,null,"SYNTHETIC VCEP",DOI);
            var d=conditions.assess(original,row,context(row));assertTrue(d.requirements().isEmpty(),d.display().toPrettyString());
            assertEquals(2,points(calculate(c,original)));
        }
    }
    @Test void structuredAssertionMethodDoesNotRequireAnyCommentTemplate() throws Exception {
        var c=compound();var row=c.withObject("/resolvedObservations/0");var original=json.createObjectNode();published(original,row,entity(row,true));
        approvalOriginal(original,row,"Unrelated clinical description.","https://doi.org/","SYNTHETIC VCEP",DOI);
        assertTrue(conditions.assess(original,row,context(row)).requirements().isEmpty());assertEquals(2,points(calculate(c,original)));
    }
    void structuredMethodUse(String statement,boolean qualified) throws Exception {
        var c=compound();var row=c.withObject("/resolvedObservations/0");var original=json.createObjectNode();published(original,row,entity(row,true));
        approvalOriginal(original,row,"Unrelated clinical description.",statement,"SYNTHETIC VCEP","");
        var decision=conditions.assess(original,row,context(row));
        assertEquals(qualified,decision.requirements().isEmpty(),decision.display().toPrettyString());
        assertEquals(qualified?2:0,points(calculate(c,original)));
        assertTrue(decision.display().toString().contains(statement));
    }
    @Test void structuredMethodExplicitInapplicabilityCannotQualify() throws Exception {
        structuredMethodUse("Specifications "+DOI+" are not applicable to this classification",false);
    }
    @Test void structuredMethodNeitherAppliedNorFollowedCannotQualify() throws Exception {
        structuredMethodUse("Specifications "+DOI+" were neither applied nor followed",false);
    }
    @Test void structuredMethodRetainsUnrelatedPopulationNegativeControl() throws Exception {
        structuredMethodUse("Specifications "+DOI+" were applied. The variant was not observed in population databases.",true);
    }
    @Test void sameScvContradictionCannotBeHiddenByAnEarlierStructuredApprovalOrPositiveSentence() throws Exception {
        for(String comment:List.of("Classification was not performed using https://doi.org/"+DOI+".",
                "Classification performed using https://doi.org/"+DOI+". Classification was not performed using https://doi.org/"+DOI+".")) {
            var c=compound();var row=c.withObject("/resolvedObservations/0");var original=json.createObjectNode();published(original,row,entity(row,true));
            approvalOriginal(original,row,comment,"https://doi.org/","SYNTHETIC VCEP",DOI);
            var d=conditions.assess(original,row,context(row));
            assertEquals("CONFLICT",d.display().path("status").asText());assertEquals(0,points(calculate(c,original)));
            assertTrue(d.display().toString().contains("PM3_PARTNER_SPECIFICATION_METHOD_CONFLICT"));
        }
    }
    @Test void mereCitationWrongIssuerWrongVersionAndExplicitNonuseDoNotEstablishApproval() throws Exception {
        for(String comment:List.of("See https://doi.org/"+DOI+".","Classification was not performed using https://doi.org/"+DOI+".",
                "Classification performed using https://doi.org/10.5281/zenodo.9999998.",
                "Classification will be performed using https://doi.org/"+DOI+".")) {
            var c=compound();var row=c.withObject("/resolvedObservations/0");var original=json.createObjectNode();published(original,row,entity(row,true));
            approvalOriginal(original,row,comment,null,"SYNTHETIC VCEP",DOI);assertEquals(0,points(calculate(c,original)));
        }
        var c=compound();var row=c.withObject("/resolvedObservations/0");var original=json.createObjectNode();published(original,row,entity(row,true));
        approvalOriginal(original,row,"Classification performed using https://doi.org/"+DOI+".",null,"WRONG VCEP",DOI);
        assertEquals(0,points(calculate(c,original)));
        approvalOriginal(original,row,"Unrelated comment.","We did not use https://doi.org/","SYNTHETIC VCEP",DOI);
        assertEquals(0,points(calculate(c,original)));
    }
    @Test void homozygousObservationDoesNotInventASecondVariantApproval() throws Exception {
        var c=caseRow();var row=c.withObject("/resolvedObservations/0");var original=json.createObjectNode();published(original,row,entity(row,true));
        assertEquals(2,points(calculate(c,original)));
    }
    @Test void homozygousKnownConflictIsNotOverwrittenByTheNoPartnerUnknownPhaseDefault() {
        for(String phase:List.of("CONFLICT","CIS")) {
            var c=caseRow();var row=c.withObject("/resolvedObservations/0");row.withObject("/reportedObservation").put("phase",phase);
            var d=conditions.assess(json.createObjectNode(),row,context(row));assertEquals(Phase.valueOf(phase),d.phase());
            var result=calculate(c,json.createObjectNode());assertEquals(0,points(result));
            assertEquals("CONFLICT",result.at("/diseaseAssessments/0/calculation/contributions/0/qualificationStatus").asText());
        }
        assertEquals(2,points(calculate(caseRow(),json.createObjectNode())));
    }
    @Test void actualReleasedOriginalsAreReplayedByBothPythonSelectionAndJavaConditionCompiler() throws Exception {
        String root=System.getProperty("t06.pm3.sourceRoot","");
        org.junit.jupiter.api.Assumptions.assumeFalse(root.isBlank(),"Actual pinned rule source path must be explicit; skip is not source validation");
        String output=System.getProperty("t06.pm3.sourceOutput"),scriptRoot=System.getProperty("denovo.test.scripts"),python=System.getProperty("denovo.test.python");
        if(output==null || scriptRoot==null || python==null)throw new IllegalStateException("T06_EXPLICIT_TEST_PATHS_REQUIRED");
        Path out=Files.createDirectories(Path.of(output));
        Path seed=out.resolve("synthetic-policy-case-seed.json"),result=out.resolve("actual-rule-replay-input.json");
        Files.write(seed,json.writeValueAsBytes(caseRow().at("/resolvedObservations/0")));
        Path scripts=Path.of(scriptRoot);
        var execution=new SystemExternalProcessRunner().run(List.of(
                python,"-E","-s","-B",scripts.resolve("test_t06_pm3_source_fixture.py").toString(),
                root,seed.toString(),result.toString()),scripts,Duration.ofSeconds(30));
        assertTrue(execution.isSuccess(),execution.getOutput());
        var input=json.readTree(Files.readAllBytes(result));var actual=json.createObjectNode().put("clinicalAdoption",false);
        var examples=actual.putArray("examples");
        for(var example:input.path("examples")) {
            var observations=example.path("observations");var row=observations.at("/cases/0/resolvedObservations/0");
            var d=conditions.assess(observations,row,context(row));
            assertTrue(d.requirements().isEmpty(),d.display().toPrettyString());
            assertEquals(example.path("expectedFamily").asText(),d.display().path("family").asText());
            examples.add(d.display());
        }
        assertEquals(3,examples.size());Files.write(out.resolve("actual-java-rule-replay.json"),json.writeValueAsBytes(actual));
    }
}
