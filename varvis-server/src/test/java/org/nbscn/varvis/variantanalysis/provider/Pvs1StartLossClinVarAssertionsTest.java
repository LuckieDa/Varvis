package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossClinVarAssertionsTest {
    final ObjectMapper mapper = new ObjectMapper();
    @org.junit.jupiter.api.io.TempDir Path temporary;
    String nativeXml() throws Exception {
        String root = System.getProperty("pvs1.nativeExpertRoot");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null);
        byte[] raw = Files.readAllBytes(Path.of(root, "local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102/VCV000185200.xml"));
        assertEquals("645ab2738f8fc07ec2a2b9ed500eeb4bfb45d89019e176b49af893c1ee01edc0", CodingTranscriptMaterialSupply.sha(raw));
        return new String(raw, StandardCharsets.UTF_8);
    }
    @Test void nativeIndividualSubmissionsCanBeReviewedForEquivalenceWithoutInventingAnIndependentObservation() throws Exception {
        String xml = nativeXml();
        var context = mapper.createObjectNode().put("moi", "AUTOSOMAL_DOMINANT");
        context.putObject("gene").put("hgncId", "HGNC:9588").put("symbol", "PTEN");
        context.putObject("disease").put("mondoId", "MONDO:0017623");
        context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "10");
        var receipt = mapper.createObjectNode().put("status", "AVAILABLE").put("format", "XML")
                .put("sha256", CodingTranscriptMaterialSupply.sha(xml.getBytes(StandardCharsets.UTF_8)));
        var result = Pvs1StartLossClinVarAssertions.equivalentRecord(mapper, context, "185200",
                new Pvs1StartLossPublicSources.Snapshot(receipt, mapper.createObjectNode().put("xml", xml)));
        assertEquals("NO_ADVERSE_CURRENT_ASSERTION", result.path("status").asText(), result.toPrettyString());
        assertFalse(result.path("independentPathogenicityGranted").asBoolean());
        assertEquals(0, result.path("additionalEvidenceCount").asInt(-1));
        var archive = mapper.createObjectNode(); archive.set("assessment", result);
        archive.putObject("originals").putObject(receipt.path("sha256").asText()).put("rawXml", xml);
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = temporary;
        persistence.saveAndRead(mapper.writeValueAsString(result), mapper.writeValueAsString(archive));
    }
    ObjectNode read(String xml) throws Exception {
        var f = new Pvs1StartLossOriginalRuleReplayTest().nativeFixture();
        var context = mapper.createObjectNode();
        context.putObject("gene").put("hgncId", "HGNC:9588").put("symbol", "PTEN");
        context.putObject("disease").put("mondoId", "MONDO:0017623");
        context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "10");
        var receipt = mapper.createObjectNode().put("status", "AVAILABLE").put("format", "XML")
                .put("sha256", CodingTranscriptMaterialSupply.sha(xml.getBytes(StandardCharsets.UTF_8)));
        return Pvs1StartLossClinVarAssertions.read(mapper, context, f.summary(),
                new Pvs1StartLossPublicSources.Snapshot(receipt, mapper.createObjectNode().put("xml", xml)));
    }
    @Test void nativeExpertSubmissionIsSameAssertionNotAdditionalEvidence() throws Exception {
        var result = read(nativeXml());
        assertEquals("CONSISTENT_WITH_EREPO", result.path("status").asText(), result.toPrettyString());
        assertEquals(4, result.path("assertionsEnumerated").asInt());
        assertEquals(1, result.path("linkedExpertAssertions").asInt());
        assertEquals(87864539, result.at("/allele/position").asInt());
        assertFalse(result.path("independentPathogenicityGranted").asBoolean());
        assertEquals(0, result.at("/assertions/3/additionalEvidenceCount").asInt());
    }
    @Test void changedSummaryOrMissingLinkedSubmissionCannotBeReconciled() throws Exception {
        String xml = nativeXml();
        assertEquals("CLINVAR_EREPO_SUBMISSION_MISMATCH", read(xml.replace("score of this variant = 0.753", "score of this variant = 0.754")).path("reasonCode").asText());
        assertEquals("CLINVAR_LINKED_EXPERT_ASSERTION_UNRESOLVED", read(xml.replace("localKey=\"d663419a-413e-4b9a-9410-9355060d6e3f\"", "localKey=\"other\"")).path("reasonCode").asText());
    }
    @Test void adverseUnknownBackgroundIsNotExcludedAndExactBackgroundConflicts() throws Exception {
        String xml = nativeXml().replaceFirst("<GermlineClassification>Pathogenic</GermlineClassification>", "<GermlineClassification>Benign</GermlineClassification>");
        // First SCV has no disease identifier. Replacing its classification must
        // not be hidden by a later concordant expert submission.
        assertEquals("CLINVAR_REFERENCE_POTENTIAL_CONFLICT_UNRESOLVED", read(xml).path("reasonCode").asText());
        xml = xml.replaceFirst("<Trait Type=\"Disease\">\\s*<Name>\\s*<ElementValue Type=\"Preferred\">Not Provided</ElementValue>\\s*</Name>",
                        "<Trait Type=\"Disease\"><XRef DB=\"MONDO\" ID=\"MONDO:0017623\"/>");
        assertEquals("CONFLICT", read(xml).path("status").asText());
    }
    @Test void recordIdentityEnumerationAndExternalEntitiesAreRejected() throws Exception {
        String xml = nativeXml();
        assertEquals("CLINVAR_RECORD_IDENTITY_OR_STATUS_INVALID", read(xml.replace("Accession=\"VCV000185200\"", "Accession=\"VCV000000001\"")).path("reasonCode").asText());
        assertEquals("CLINVAR_ASSERTION_ENUMERATION_UNRESOLVED", read(xml.replace("NumberOfSubmissions=\"4\"", "NumberOfSubmissions=\"5\"")).path("reasonCode").asText());
        assertEquals("CLINVAR_ASSERTION_XML_INVALID", read("<!DOCTYPE ClinVarResult-Set [<!ENTITY x SYSTEM 'file:///nonexistent'>]><ClinVarResult-Set>&x;</ClinVarResult-Set>").path("reasonCode").asText());
    }

    @Test void overlappingGeneDoesNotInvalidateAnExactContextOrTransferEvidenceToItsNeighbor() throws Exception {
        String xml = nativeXml().replaceFirst("</GeneList>", "<Gene Symbol=\"OTHER\" HGNC_ID=\"HGNC:999999\"/></GeneList>");
        var result = read(xml);
        assertEquals("CONSISTENT_WITH_EREPO", result.path("status").asText());
        assertEquals(2, result.at("/geneMatch/sourceGeneCount").asInt());
        assertEquals("HGNC:9588", result.at("/geneMatch/hgncId").asText());
        for (String badGene : new String[]{"<Gene Symbol=\"PTEN\" HGNC_ID=\"HGNC:999999\"/>",
                "<Gene Symbol=\"OTHER\" HGNC_ID=\"HGNC:9588\"/>", "<Gene Symbol=\"PTEN\" HGNC_ID=\"HGNC:9588\"/>"})
            assertEquals("CLINVAR_REFERENCE_GENE_MISMATCH", read(nativeXml().replaceFirst("</GeneList>", badGene + "</GeneList>")).path("reasonCode").asText());
    }

    @Test void realMultiGeneVcvEnumeratesEveryNonPathogenicSubmissionWithoutGrantingTargetEvidence() throws Exception {
        String root = System.getProperty("pvs1.nativeExpertRoot"); org.junit.jupiter.api.Assumptions.assumeTrue(root != null);
        byte[] raw = Files.readAllBytes(Path.of(root, "local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102/native-target-source-captures/VCV000383439.xml"));
        String sha = CodingTranscriptMaterialSupply.sha(raw);
        assertEquals("7d6d6405b7579f8d2156b872b5126d6a524c1ab400fe0d775df0950cb3bfa8bd", sha);
        var context = mapper.createObjectNode().put("moi", "X_LINKED_RECESSIVE");
        context.putObject("gene").put("hgncId", "HGNC:11079").put("symbol", "SLC9A6");
        context.putObject("disease").put("mondoId", "MONDO:0010278");
        context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "X");
        var receipt = mapper.createObjectNode().put("status", "AVAILABLE").put("format", "XML").put("sha256", sha);
        var snapshot = new Pvs1StartLossPublicSources.Snapshot(receipt, mapper.createObjectNode().put("xml", new String(raw, StandardCharsets.UTF_8)));
        var result = Pvs1StartLossClinVarAssertions.candidate(mapper, context, "383439", snapshot);
        assertEquals("NO_PATHOGENIC_ASSERTION_IN_SCOPE", result.path("status").asText(), result.toPrettyString());
        assertEquals(4, result.path("assertionsEnumerated").asInt());
        assertEquals(2, result.at("/geneMatch/sourceGeneCount").asInt());
        assertTrue(result.at("/geneMatch/sourceLocator").asText().endsWith("Gene[2]"));
        assertEquals(135985504, result.at("/allele/position").asInt());
        assertFalse(result.path("independentPathogenicityGranted").asBoolean());
        var archive = mapper.createObjectNode(); archive.set("assessment", result);
        archive.putObject("originals").putObject(sha).put("rawXml", new String(raw, StandardCharsets.UTF_8));
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = temporary;
        persistence.saveAndRead(mapper.writeValueAsString(result), mapper.writeValueAsString(archive));
        context.withObject("gene").put("hgncId", "HGNC:999999");
        assertEquals("CLINVAR_REFERENCE_GENE_MISMATCH", Pvs1StartLossClinVarAssertions.candidate(mapper, context, "383439", snapshot).path("reasonCode").asText());
    }
}
