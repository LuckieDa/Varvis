package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Invented source-qualified records verify engineering qualification, never clinical approval. */
class MissenseAutomatedResearchQualificationTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    Path manifestPath;
    ObjectNode manifest;
    @ParameterizedTest @ValueSource(strings={"matching","id","name","missing","unconfigured"})
    void selectedPolicyPanelRequirementUsesTheActualSourceSummary(String change) {
        var policy=mapper.createObjectNode();
        policy.putObject("expertSourceAdoption").putObject("requiredExpertPanel").put("affiliationId","TEST_ONLY_42").put("name","Invented expert panel");
        var reference=mapper.createObjectNode();var summary=reference.putObject("expertEvidence").putObject("record").putObject("originalExpertSummary");
        summary.put("affiliationId","TEST_ONLY_42").put("ep","Invented expert panel");
        if("id".equals(change))summary.put("affiliationId","different");
        if("name".equals(change))summary.put("ep","different");
        if("missing".equals(change))summary.removeAll();
        if("unconfigured".equals(change))((ObjectNode)policy.path("expertSourceAdoption")).remove("requiredExpertPanel");
        if("matching".equals(change)||"unconfigured".equals(change))assertNull(MissenseExpertClassificationAdoption.panelIssue(reference,policy));
        else assertEquals("EXPERT_REQUIRED_PANEL_MISMATCH",MissenseExpertClassificationAdoption.panelIssue(reference,policy));
    }
    @ParameterizedTest @ValueSource(strings={"matching","no-summary","id","name","summary-conflict","missing-submitter"})
    void exactClinvarSubmitterCanEstablishPanelWithoutAnOptionalSummary(String change) {
        var policy=mapper.createObjectNode();
        policy.putObject("expertSourceAdoption").putObject("requiredExpertPanel")
                .put("affiliationId","TEST_42").put("name","Test panel")
                .put("clinvarOrgId","TEST_ORG").put("clinvarSubmitterName","Test expert submission");
        var reference=mapper.createObjectNode();var row=reference.putObject("expertEvidence").putObject("record");
        var summary=row.putObject("originalExpertSummary").put("affiliationId","TEST_42").put("ep","Test panel");
        var accession=row.putObject("originalAssertion").putObject("accession")
                .put("OrgID","TEST_ORG").put("SubmitterName","Test expert submission");
        if("no-summary".equals(change))row.putNull("originalExpertSummary");
        if("id".equals(change))accession.put("OrgID","wrong");
        if("name".equals(change))accession.put("SubmitterName","wrong");
        if("summary-conflict".equals(change))summary.put("affiliationId","wrong");
        if("missing-submitter".equals(change))accession.removeAll();
        if("matching".equals(change)||"no-summary".equals(change))assertNull(MissenseExpertClassificationAdoption.panelIssue(reference,policy));
        else assertEquals("EXPERT_REQUIRED_PANEL_MISMATCH",MissenseExpertClassificationAdoption.panelIssue(reference,policy));
    }
    void sourceQualified() throws Exception {
        sourceQualified(true, 1);
    }
    void sourceQualified(boolean pm5, int index) throws Exception {
        var f=new GeneratedMissenseReferenceFixture(root.resolve("fixture"),mapper,pm5,GeneratedMissenseReferenceFixture.Scenario.SOURCE_AUTOMATED).build();
        manifestPath=f.root.resolve("background-"+index+"/bridge/manifest-automatic-research.json");
        manifest=(ObjectNode)mapper.readTree(f.root.resolve("background-"+index+"/bridge/manifest.json").toFile());
        manifest.put("releaseMode","SOURCE_VERIFIED_PROJECT_RESEARCH");
        manifest.putObject("contextUse").put("schemaVersion","missense-context-use-v1").put("purpose","PROJECT_RESEARCH").putArray("allowedDataClasses").add("SYNTHETIC");
    }
    @Test void ps1UsesTheSameSourceQualificationButKeepsItsOwnRuleAndStrength() throws Exception {
        sourceQualified(false,1);var result=prepare("ps1");assertTrue(result.ready(),result.report().toPrettyString());
        var bundle=(ObjectNode)mapper.readTree(root.resolve("ps1/bundle.json").toFile());
        assertEquals(2,bundle.path("policies").size());
        for(var policy:bundle.path("policies")) {
        assertEquals("SAME_PROTEIN_CHANGE_SINGLE_REFERENCE",policy.at("/rules/PS1/mode").asText());
        assertFalse(policy.path("rules").has("PM5"));
        assertEquals("STRONG",policy.at("/rules/PS1/classificationStrengths/PATHOGENIC").asText());
        assertEquals("MODERATE",policy.at("/rules/PS1/classificationStrengths/LIKELY_PATHOGENIC").asText());
        }
        assertNull(new ReviewedRuleBundle(mapper,bundle,adapter.canonicalSha256(bundle)).missenseUseIssue(mapper.createObjectNode().put("dataClass","SYNTHETIC")));
        assertFalse(bundle.path("materialQualification").has("externalApproval"));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void oldResearchProofVersionStillMeansPm5Only(boolean pm5) throws Exception {
        sourceQualified(pm5,1);assertTrue(prepare("version").ready());
        var bundle=(ObjectNode)mapper.readTree(root.resolve("version/bundle.json").toFile());
        ((ObjectNode)bundle.path("materialQualification")).put("qualificationVersion","1.0.0");
        if(pm5)assertDoesNotThrow(()->new ReviewedRuleBundle(mapper,bundle,adapter.canonicalSha256(bundle)));
        else assertThrows(IllegalArgumentException.class,()->new ReviewedRuleBundle(mapper,bundle,adapter.canonicalSha256(bundle)));
    }
    MissenseMaterialPreparation.Result prepare(String name) throws Exception {
        mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(),manifest);
        return new MissenseMaterialPreparation().prepare(manifestPath,Pm4MaterialPreparation.sha(Files.readAllBytes(manifestPath)),root.resolve(name));
    }
    @Test void explicitlySelectedSourceQualificationReleasesWithoutInventingExternalApproval() throws Exception {
        sourceQualified();var result=prepare("automatic");assertTrue(result.ready(),result.report().toPrettyString());
        var bundle=(ObjectNode)mapper.readTree(root.resolve("automatic/bundle.json").toFile());
        var proof=bundle.path("materialQualification");assertEquals("missense-material-qualification-v2",proof.path("schemaVersion").asText());
        assertEquals("SOURCE_VERIFIED_PROJECT_RESEARCH",proof.path("qualificationMode").asText());
        assertFalse(proof.has("externalApproval"));assertFalse(proof.path("scientificApprovalGranted").asBoolean(true));
        var loaded=new ReviewedRuleBundle(mapper,bundle,adapter.canonicalSha256(bundle));
        assertNull(loaded.missenseUseIssue(mapper.createObjectNode().put("dataClass","SYNTHETIC")));
        assertEquals("MISSENSE_CONTEXT_DATA_CLASS_NOT_APPROVED",loaded.missenseUseIssue(mapper.createObjectNode().put("dataClass","PRIVACY_BOUNDED_RUNTIME")));
        assertFalse(result.report().has("externalReviewBinding"));
    }
    @Test void publicSourceResearchMayExplicitlySupportNormalPrivateRuntime() throws Exception {
        sourceQualified();manifest.put("dataClass","PUBLIC_REFERENCE").put("purpose","PROJECT_RESEARCH");
        ((ObjectNode)manifest.path("contextUse")).putArray("allowedDataClasses").add("PRIVACY_BOUNDED_RUNTIME");
        var result=prepare("public");assertTrue(result.ready(),result.report().toPrettyString());
        var bundle=(ObjectNode)mapper.readTree(root.resolve("public/bundle.json").toFile());
        assertNull(new ReviewedRuleBundle(mapper,bundle,adapter.canonicalSha256(bundle))
                .missenseUseIssue(mapper.createObjectNode().put("dataClass","PRIVACY_BOUNDED_RUNTIME")));
    }
    @ParameterizedTest @ValueSource(strings={"unknown-mode","approval","no-context","clinical","synthetic-private","legacy-mode"})
    void releaseRequiresAnExplicitUnambiguousResearchRoute(String change) throws Exception {
        sourceQualified();
        switch(change) {
            case "unknown-mode" -> manifest.put("releaseMode","SILENT_APPROVAL");
            case "approval" -> manifest.putObject("approval");
            case "no-context" -> manifest.remove("contextUse");
            case "clinical" -> manifest.put("dataClass","PUBLIC_REFERENCE").put("purpose","CLINICAL_USE");
            case "synthetic-private" -> ((ObjectNode)manifest.path("contextUse")).putArray("allowedDataClasses").add("PRIVACY_BOUNDED_RUNTIME");
            default -> manifest.remove("releaseMode");
        }
        var result=prepare("denied");assertFalse(result.ready());
        assertFalse(Files.exists(root.resolve("denied/bundle.json")));
        if("legacy-mode".equals(change))assertEquals("EXTERNAL_APPROVAL_MISSING",result.report().path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"metadata","material","background","approval","scientific","version","purpose","sources"})
    void rehashingOuterBundleCannotQualifyChangedContents(String change) throws Exception {
        sourceQualified();assertTrue(prepare("ready").ready());
        var bundle=(ObjectNode)mapper.readTree(root.resolve("ready/bundle.json").toFile());
        var proof=(ObjectNode)bundle.path("materialQualification");
        var metadata=(ObjectNode)proof.path("candidateMetadata");
        switch(change) {
            case "metadata" -> metadata.put("releaseMode","UNKNOWN");
            case "material" -> ((ObjectNode)bundle.at("/materials/missenseMaterials")).removeAll();
            case "background" -> metadata.putArray("backgrounds");
            case "approval" -> proof.putObject("externalApproval").put("reviewId","INVENTED");
            case "scientific" -> proof.put("scientificApprovalGranted",true);
            case "version" -> proof.put("qualificationVersion","future");
            case "sources" -> metadata.putArray("sources");
            default -> metadata.put("purpose","CLINICAL");
        }
        assertThrows(IllegalArgumentException.class,()->new ReviewedRuleBundle(mapper,bundle,adapter.canonicalSha256(bundle)));
    }
    @ParameterizedTest @ValueSource(strings={"empty-references","manual-audit","manual-adoption","missing-rna","source-vus","source-conflict","wrong-background","unbound-source"})
    void sourceConditionsAreRecheckedEvenWithFreshCandidateDigest(String change) throws Exception {
        sourceQualified();assertTrue(prepare("ready").ready());
        var candidate=(ObjectNode)mapper.readTree(root.resolve("ready/candidate.json").toFile());
        var policy=(ObjectNode)candidate.path("policies").get(0);
        var directory=(ObjectNode)candidate.path("missenseMaterials");
        String old=policy.path("materialsSha256").asText();
        var material=(ObjectNode)directory.path(old);
        var audit=(ObjectNode)material.path("referenceAudit");
        var ref=(ObjectNode)audit.path("references").get(0);
        switch(change) {
            case "empty-references" -> audit.putArray("references");
            case "manual-audit" -> audit.put("status","REVIEWED");
            case "manual-adoption" -> ((ObjectNode)policy.path("expertSourceAdoption")).put("mode",MissenseExpertClassificationAdoption.MODE);
            case "missing-rna" -> policy.remove("rnaApplicability");
            case "source-vus" -> ((ObjectNode)ref.at("/expertEvidence/record/originalAssertion")).put("classification","Uncertain significance");
            case "source-conflict" -> ((ObjectNode)ref.at("/expertEvidence/record/originalAssertion")).put("recordStatus","retracted");
            case "wrong-background" -> ((ObjectNode)candidate.at("/backgrounds/0/background")).put("mondoId","MONDO:9999999");
            default -> candidate.putArray("sources");
        }
        directory.remove(old);String materialSha=adapter.canonicalSha256(material);directory.set(materialSha,material);policy.put("materialsSha256",materialSha);
        var background=(ObjectNode)candidate.path("backgrounds").get(0);
        background.put("materialSha256",materialSha).put("policySha256",adapter.canonicalSha256(policy))
                .put("backgroundSha256",adapter.canonicalSha256(background.path("background")));
        assertThrows(IllegalArgumentException.class,()->MissenseResearchQualification.preserve(mapper,candidate));
    }
}
