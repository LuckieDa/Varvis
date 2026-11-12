package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;

class Pm1ClinVarIntakeTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void projectsExactNativeCodonsAndKeepsUnreviewedRecordsOutOfScoring() throws Exception {
        var sidecar = mock(ClinVarComparatorSidecar.class);
        var sameDiseaseConflict = lookup(submission("Pathogenic", "MONDO:MONDO:0012345"),
                submission("Likely benign", "MONDO:0012345"));
        var differentDisease = lookup(submission("Benign", "MONDO:0012346"));
        when(sidecar.lookupBatch(anyList(), eq("tabix"))).thenReturn(List.of(sameDiseaseConflict, differentDisease));
        var scan = scan(List.of(
                vcf(105, 1, "A", "G", "Pathogenic"),
                vcf(105, 2, "A", "C", "Pathogenic"),
                vcf(105, 2, "A", "C", "Pathogenic"),
                vcf(106, 3, "A", "G", "Benign"),
                vcf(204, 4, "T", "C", "Likely_benign"),
                vcf(202, 5, "A", "C", "Benign")),
                List.of("7:101-106", "7:201-206"));
        var result = new Pm1ClinVarIntake(mapper, scan, "tabix", () -> sidecar).collect(context(), model(1), target(), region());
        assertEquals("ENUMERATED", result.path("status").asText(), result::toPrettyString);
        assertFalse(result.path("referenceSearchComplete").asBoolean(), "REF mismatch prevents claiming complete projection");
        assertFalse(result.path("usedForScoring").asBoolean());
        assertEquals(1, result.at("/excluded/targetProteinChange").asInt());
        assertEquals(1, result.at("/excluded/outsideMissenseScope").asInt());
        assertEquals(1, result.at("/excluded/referenceMismatch").asInt());
        assertEquals(2, result.path("candidateRecordCount").asInt());
        assertEquals(1, result.at("/regions/0/candidateRecordsInside").asInt());
        assertEquals(1, result.at("/regions/0/candidateRecordsOutside").asInt());
        assertEquals("p.Lys2Thr", result.at("/records/0/proteinChange").asText());
        assertEquals("SAME_DISEASE_CONFLICT", result.at("/records/0/reviewStatus").asText());
        assertEquals(2, result.at("/records/0/sameDiseaseSubmissionCount").asInt());
        assertEquals("SCV000123.1", result.at("/records/0/submissionReferences/0/scv").asText());
        assertEquals("p.Phe4Leu", result.at("/records/1/proteinChange").asText());
        assertEquals("DIFFERENT_DISEASE", result.at("/records/1/reviewStatus").asText());
    }

    @Test void projectsReverseStrandAndDoesNotClaimAnIndependentBasisFromAnAggregate() throws Exception {
        var scan = scan(List.of(vcf(305, 1, "T", "C", "Pathogenic"),
                vcf(206, 6, "A", "G", "Pathogenic")), List.of("7:304-309", "7:204-209"));
        var result = new Pm1ClinVarIntake(mapper, scan, "tabix", null).collect(context(), model(-1), target(), mapper.createObjectNode());
        assertEquals("ENUMERATED", result.path("status").asText(), result::toPrettyString);
        assertTrue(result.path("referenceSearchComplete").asBoolean());
        assertEquals("NOT_CONFIGURED", result.path("submissionSourceStatus").asText());
        assertTrue(result.path("regions").isEmpty());
        assertEquals(1, result.at("/excluded/targetProteinChange").asInt());
        assertEquals("p.Phe4Leu", result.at("/records/0/proteinChange").asText());
        assertEquals("SUBMISSIONS_UNAVAILABLE", result.at("/records/0/reviewStatus").asText());
        assertTrue(result.at("/records/0/insideRegionIds").isEmpty());
        assertFalse(result.path("usedForScoring").asBoolean());
    }

    @Test void missingOrFailedResourcesRemainExplicit() {
        var properties = new VariantAnalysisProperties();
        properties.getClinVar().setEnabled(true);
        var unavailable = new Pm1ClinVarIntake(mapper, properties, null).collect(context(), model(1), target(), region());
        assertEquals("UNAVAILABLE", unavailable.path("status").asText());
        assertEquals("CLINVAR_UPSTREAM_RESOURCE_UNAVAILABLE", unavailable.path("reasonCode").asText());
        assertFalse(unavailable.path("referenceSearchComplete").asBoolean());
        assertFalse(unavailable.path("usedForScoring").asBoolean());
    }

    @Test void resolvesSourceIdsAndMissingSubmissionGeneFromTheExactSingleGeneAlleleOnly() throws Exception {
        var reference = submission("Pathogenic", "OMIM:123456");
        reference.remove("geneSymbol");
        ((ObjectNode) reference.path("condition")).put("reportedRaw", "C1234567:TEST disease");
        var result = mappedIntake(List.of("TEST"), reference);
        var row = result.at("/records/0");
        assertEquals("INDEPENDENT_BASIS_UNVERIFIED", row.path("reviewStatus").asText());
        assertEquals(1, row.path("sameDiseaseSubmissionCount").asInt());
        assertEquals("EXACT_ALLELE_SINGLE_GENE", row.at("/submissionReferences/0/geneBinding").asText());
        assertEquals("EXACT", row.at("/submissionReferences/0/conditionIdentity/status").asText());
        assertEquals("READY", result.at("/diseaseMappingSource/status").asText());
        assertFalse(result.path("usedForScoring").asBoolean());
        assertEquals("DISEASE_SCOPE_UNVERIFIED", mappedIntake(List.of("TEST", "OTHER"), reference).at("/records/0/reviewStatus").asText());
        reference.put("geneSymbol", "OTHER");
        assertEquals("DISEASE_SCOPE_UNVERIFIED", mappedIntake(List.of("TEST"), reference).at("/records/0/reviewStatus").asText());
    }

    @Test void unspecifiedBenignConditionDoesNotBecomeSameDiseaseConflictOrBenignEvidence() throws Exception {
        var unknown = submission("Likely benign", "MedGen:CN517202");
        ((ObjectNode) unknown.path("condition")).put("reportedRaw", "C3661900:not provided");
        var uncertain = submission("Uncertain significance", "OMIM:123456");
        uncertain.remove("geneSymbol");
        var result = mappedIntake(List.of("TEST"), unknown, uncertain);
        var row = result.at("/records/0");
        assertEquals("AGGREGATE_CONFLICT_SCOPE_UNVERIFIED", row.path("reviewStatus").asText());
        assertEquals(1, row.path("sameDiseaseSubmissionCount").asInt());
        assertEquals(1, row.path("unresolvedDiseaseSubmissionCount").asInt());
        assertEquals(mapper.createArrayNode().add("UNCERTAIN"), row.path("sameDiseaseClassifications"));
        assertFalse(result.path("usedForScoring").asBoolean());
        ((ObjectNode) unknown.path("condition")).put("submittedRaw", "OMIM:123456").put("reportedRaw", "C1234567:TEST disease");
        assertEquals("SAME_DISEASE_CONFLICT", mappedIntake(List.of("TEST"), unknown, uncertain).at("/records/0/reviewStatus").asText());
    }

    private ObjectNode mappedIntake(List<String> genes, ObjectNode... submissions) throws Exception {
        var sidecar = mock(ClinVarComparatorSidecar.class);
        var result = lookup(submissions);
        when(java.util.Objects.requireNonNull(result.getRecord()).getGeneSymbols()).thenReturn(genes);
        when(sidecar.lookupBatch(anyList(), eq("tabix"))).thenReturn(List.of(result));
        var file = Files.writeString(root.resolve("mondo.obo"), ClinVarConditionIdentityTest.ontology());
        var identity = ClinVarConditionIdentity.load(file, CodingTranscriptMaterialSupply.sha(Files.readAllBytes(file)));
        var scan = scan(List.of(vcf(105, 2, "A", "C", submissions.length > 1 ? "Conflicting_classifications_of_pathogenicity" : "Pathogenic")),
                List.of("7:101-106", "7:201-206"));
        return new Pm1ClinVarIntake(mapper, scan, "tabix", () -> sidecar, () -> identity).collect(context(), model(1), target(), region());
    }

    private Pvs1StartLossClinVarSearch scan(List<String> rows, List<String> expected) throws Exception {
        Path vcf = Files.write(root.resolve("clinvar.vcf.gz"), new byte[0]);
        Files.write(root.resolve("clinvar.vcf.gz.tbi"), new byte[0]);
        var properties = new VariantAnalysisProperties();
        properties.getClinVar().setEnabled(true);
        properties.getClinVar().setGrch38File(vcf.toString());
        return new Pvs1StartLossClinVarSearch(mapper, properties, (file, regions) -> {
            assertEquals(vcf, file);
            assertEquals(expected, regions);
            return rows;
        });
    }

    private ObjectNode context() {
        var context = mapper.createObjectNode().put("moi", "AUTOSOMAL_DOMINANT");
        context.putObject("disease").put("mondoId", "MONDO:0012345");
        context.putObject("gene").put("symbol", "TEST");
        context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "7");
        return context;
    }

    private ObjectNode model(int strand) {
        var model = mapper.createObjectNode().put("contig", "7").put("strand", strand)
                .put("transcript", "ENST00000000001.1").put("protein", "ENSP00000000001.1")
                .put("cdsSequence", "ATGAAAGGATTTTAA").put("cdsLength", 15);
        var exons = model.putArray("exons");
        int first = strand == 1 ? 101 : 304;
        int second = 201;
        exons.addObject().put("genomicStart", first).put("genomicEnd", first + 5).put("cdsStart", 1).put("cdsEnd", 6);
        exons.addObject().put("genomicStart", second).put("genomicEnd", second + 8).put("cdsStart", 7).put("cdsEnd", 15);
        return model;
    }

    private ObjectNode region() {
        var reviewed = mapper.createObjectNode();
        reviewed.putArray("assessments").addObject().put("regionId", "TEST_DOMAIN").put("label", "Test domain")
                .put("start", 2).put("end", 3);
        return reviewed;
    }

    private ObjectNode target() {
        return mapper.createObjectNode().put("proteinPosition", 2).put("altAminoAcid", "R");
    }

    private ClinVarComparatorSidecar.LookupResult lookup(ObjectNode... submissions) {
        var result = mock(ClinVarComparatorSidecar.LookupResult.class);
        var record = mock(ClinVarComparatorSidecar.ComparatorRecord.class);
        var provenance = mock(ClinVarComparatorSidecar.Provenance.class);
        var facts = mapper.createArrayNode();
        for (var submission : submissions) facts.add(submission);
        when(result.getProvenance()).thenReturn(provenance);
        when(result.getRecord()).thenReturn(record);
        when(result.getStatus()).thenReturn(ClinVarComparatorSidecar.Status.HIT);
        when(record.getProvenance()).thenReturn(provenance);
        when(record.getGeneSymbols()).thenReturn(List.of("TEST"));
        when(record.getSubmissions()).thenReturn(facts);
        return result;
    }

    private ObjectNode submission(String classification, String disease) {
        var out = mapper.createObjectNode().put("classification", classification).put("geneSymbol", "TEST").put("scv", "SCV000123.1");
        out.putObject("condition").put("submittedRaw", disease);
        out.putObject("sourceLocator").put("logicalName", "submission_summary.txt.gz").put("line", 5);
        return out;
    }

    private static String vcf(int position, int id, String ref, String alt, String classification) {
        return "7\t" + position + "\t" + id + "\t" + ref + "\t" + alt + "\t.\t.\tCLNSIG=" + classification;
    }
}
