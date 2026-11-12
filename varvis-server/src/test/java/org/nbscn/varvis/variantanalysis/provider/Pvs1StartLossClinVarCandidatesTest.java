package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Engineered submission examples exercise absence qualification, not real
 * variant classifications. Native source positive replay is tested separately. */
class Pvs1StartLossClinVarCandidatesTest {
    @TempDir Path root;
    final Pvs1StartLossUpstreamCoverageTest helper = new Pvs1StartLossUpstreamCoverageTest();
    final AtomicInteger calls = new AtomicInteger();
    ObjectNode[] fixture() throws Exception { helper.temporary = root; return helper.sources(); }
    ObjectNode local(ObjectNode[] s, int cds) {
        return s[3].withArray("records").addObject().put("variationId", "123")
                .put("position", helper.helper.base.helper.genomic(s[1], cds)).put("reference", "A").put("alternate", "G");
    }
    String xml(ObjectNode[] s, String classification) {
        return "<ClinVarResult-Set><VariationArchive VariationID=\"123\" Accession=\"VCV000000123\" Version=\"1\" NumberOfSubmissions=\"1\">"
                + "<RecordStatus>current</RecordStatus><Species>Homo sapiens</Species><ClassifiedRecord><SimpleAllele VariationID=\"123\">"
                + "<GeneList><Gene HGNC_ID=\"" + s[0].at("/gene/hgncId").asText() + "\" Symbol=\"" + s[0].at("/gene/symbol").asText() + "\"/></GeneList>"
                + "<Location><SequenceLocation Assembly=\"GRCh38\" Chr=\"" + s[0].at("/variant/evidenceQueryAllele/contig").asText() + "\" positionVCF=\""
                + helper.helper.base.helper.genomic(s[1], 4) + "\" referenceAlleleVCF=\"A\" alternateAlleleVCF=\"G\"/></Location></SimpleAllele>"
                + "<ClinicalAssertionList><ClinicalAssertion><ClinVarAccession Accession=\"SCV000000123\" Version=\"1\" SubmitterName=\"Synthetic\"/>"
                + "<RecordStatus>current</RecordStatus><Classification DateLastEvaluated=\"2026-01-01\"><GermlineClassification>" + classification
                + "</GermlineClassification></Classification><TraitSet><Trait><XRef DB=\"MONDO\" ID=\"" + s[0].at("/disease/mondoId").asText()
                + "\"/></Trait></TraitSet></ClinicalAssertion></ClinicalAssertionList></ClassifiedRecord></VariationArchive></ClinVarResult-Set>";
    }
    ObjectNode review(ObjectNode[] s, String xml) {
        var source = new Pvs1StartLossPublicSources(helper.helper.mapper, true, (uri, timeout, maximum) -> {
            calls.incrementAndGet(); return new ProteinRegionHttpTransport.Response(200, Map.of(), xml.getBytes(StandardCharsets.UTF_8));
        });
        return Pvs1StartLossClinVarCandidates.review(helper.helper.mapper, s[0], s[1], 10, s[3], source);
    }
    @ParameterizedTest @ValueSource(strings = {"Likely pathogenic", "Uncertain significance", "Benign", "Likely benign"})
    void explicitNonPathogenicSubmissionsCanResolveTheCandidateButDoNotEstablishGlobalAbsence(String classification) throws Exception {
        var s = fixture(); local(s, 4); var result = review(s, xml(s, classification));
        assertEquals("ALL_CANDIDATES_EXCLUDED", result.path("status").asText(), result.toPrettyString());
        s[3].set("candidateReview", result);
        var coverage = Pvs1StartLossUpstreamCoverage.reconcile(s[0], s[1], 10, s[2], s[3]);
        assertEquals("NO_QUALIFYING_REFERENCE_WITHIN_SCOPE", coverage.path("status").asText());
        assertFalse(coverage.at("/coverage/globalAbsenceClaimed").asBoolean()); assertEquals(1, calls.get());
        result.put("sourceSha256", "f".repeat(64));
        assertFalse(Pvs1StartLossUpstreamCoverage.reconcile(s[0], s[1], 10, s[2], s[3]).path("complete").asBoolean());
    }
    @ParameterizedTest @ValueSource(strings = {"Pathogenic", "Pathogenic/Likely pathogenic", "", "not provided"})
    void possiblePOrUnknownAssertionsAreUnresolvedNotNegative(String classification) throws Exception {
        var s = fixture(); local(s, 4); var result = review(s, xml(s, classification));
        assertFalse(result.path("complete").asBoolean()); assertFalse(result.path("independentPathogenicityGranted").asBoolean());
        assertEquals("CLINVAR_CANDIDATE_QUALIFICATION_REQUIRED", result.at("/records/0/reasonCode").asText());
    }
    @Test void targetIsExcludedWithoutNetworkButNearbyIndelAndChangedSourceAreNotHidden() throws Exception {
        var s = fixture(); local(s, 1);
        var target = review(s, ""); assertEquals("TARGET_EXCLUDED", target.at("/records/0/reasonCode").asText()); assertEquals(0, calls.get());
        s[3].withArray("records").removeAll(); local(s, 4).put("alternate", "<DEL>");
        assertFalse(review(s, "").path("complete").asBoolean()); assertEquals(0, calls.get());
        s[3].withArray("records").removeAll(); local(s, 5);
        assertEquals("CLINVAR_LOCAL_SOURCE_ALLELE_MISMATCH", review(s, xml(s, "Benign")).at("/records/0/reasonCode").asText());
    }
    @Test void aSecondRelevantPSubmissionPreventsAbsenceEvenWhenFirstSubmissionIsBenign() throws Exception {
        var s = fixture(); local(s, 4); String raw = xml(s, "Benign");
        String assertion = raw.substring(raw.indexOf("<ClinicalAssertion>"), raw.indexOf("</ClinicalAssertion>") + "</ClinicalAssertion>".length());
        raw = raw.replace("NumberOfSubmissions=\"1\"", "NumberOfSubmissions=\"2\"").replace("</ClinicalAssertionList>",
                assertion.replace("SCV000000123", "SCV000000456").replace("Benign", "Pathogenic") + "</ClinicalAssertionList>");
        assertFalse(review(s, raw).path("complete").asBoolean());
    }
    @Test void unknownDateOrDiseaseCannotHideAPossibleReferenceButExplicitOtherDiseaseCanBeExcluded() throws Exception {
        var s = fixture(); local(s, 4);
        assertFalse(review(s, xml(s, "Benign").replace("DateLastEvaluated=\"2026-01-01\"", "")).path("complete").asBoolean());
        String current = s[0].at("/disease/mondoId").asText();
        assertFalse(review(s, xml(s, "Pathogenic").replace("DB=\"MONDO\"", "DB=\"UnmappedDatabase\"")).path("complete").asBoolean());
        assertTrue(review(s, xml(s, "Pathogenic").replace(current, "MONDO:9999999")).path("complete").asBoolean());
    }
    @Test void normalPreparationResolvesNegativeCandidatesBeforeDeclaringScopedAbsence() throws Exception {
        var s = fixture(); local(s, 4); var mapper = helper.helper.mapper;
        var source = new Pvs1StartLossPublicSources(mapper, true, (uri, timeout, maximum) -> {
            boolean clinvar = uri.getHost().equals("eutils.ncbi.nlm.nih.gov");
            if (clinvar) calls.incrementAndGet();
            return new ProteinRegionHttpTransport.Response(200, Map.of(), clinvar
                    ? xml(s, "Benign").getBytes(StandardCharsets.UTF_8) : mapper.writeValueAsBytes(helper.helper.document()));
        });
        var preparation = new Pvs1StartLossAutomaticPreparation(mapper, () -> null, source);
        var sequence = mapper.createObjectNode().put("cdsPosition", 1).put("candidateRestartCdsPosition", 10);
        var result = preparation.upstreamWithCoverage(s[0], s[1], sequence, s[3]);
        assertEquals("NO_QUALIFYING_REFERENCE_WITHIN_SCOPE", result.path("status").asText());
        assertEquals("ALL_CANDIDATES_EXCLUDED", s[3].at("/candidateReview/status").asText());
        assertEquals(1, calls.get());
    }
}
