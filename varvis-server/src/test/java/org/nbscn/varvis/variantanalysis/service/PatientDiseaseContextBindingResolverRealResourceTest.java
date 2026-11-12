package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.CandidateBindingResult;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.DiseaseContextBinding;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.PatientDiseaseHypothesis;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolverTest.candidate;
import static org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolverTest.hypothesis;

/** Public resource records plus explicitly synthetic patient hypotheses; no clinical validation. */
@EnabledIfSystemProperty(named = "varvis.e4a1a.resourceDir", matches = ".+")
class PatientDiseaseContextBindingResolverRealResourceTest {

    @Test
    void bindsTwoPublicGenesAndTwoExplicitActbHypotheses() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path resources = Path.of(System.getProperty("varvis.e4a1a.resourceDir"));
        PatientDiseaseContextBindingResolver resolver = PatientDiseaseContextBindingResolver.load(mapper,
                resources.resolve("manifest.json"), resources.resolve("mane-crosswalk.json"), resources.resolve("clingen-catalog.json"));
        VariantInterpretation aars1 = candidate(1, "AARS1", "16", "AD", "AD", "AUTOSOMAL_DOMINANT");
        VariantInterpretation aars2 = candidate(2, "AARS2", "57505", "AR", "AR", "AUTOSOMAL_RECESSIVE");
        VariantInterpretation actb = candidate(3, "ACTB", "60", "AD", "AD", "AUTOSOMAL_DOMINANT");
        List<PatientDiseaseHypothesis> hypotheses = List.of(
                hypothesis(aars1, "synthetic-aars1-ad", "HGNC:20", "MONDO:0013212", "AD"),
                hypothesis(aars2, "synthetic-aars2-ar", "HGNC:21022", "MONDO:0044970", "AR"),
                hypothesis(actb, "synthetic-actb-ad-1", "HGNC:132", "MONDO:0017579", "AD"),
                hypothesis(actb, "synthetic-actb-ad-2", "HGNC:132", "MONDO:0100433", "AD"));
        List<VariantInterpretation> candidates = List.of(aars1, aars2, actb);
        List<CandidateBindingResult> results = resolver.resolveCandidates(candidates, hypotheses);
        List<DiseaseContextBinding> bindings = results.stream().flatMap(result -> result.bindings().stream()).toList();
        assertEquals(4, bindings.size());
        assertEquals(List.of(1, 1, 2), results.stream().map(result -> result.bindings().size()).toList());
        for (DiseaseContextBinding binding : bindings) {
            assertEquals("READY", binding.status(), binding.reasonCode());
            assertEquals("SYNTHETIC_PATIENT_HYPOTHESIS", binding.provenance().patientHypothesis().provenance().source());
            assertTrue(binding.clingenReportLocator().startsWith("https://search.clinicalgenome.org/"));
            assertFalse(binding.provenance().crosswalkRecords().isEmpty());
            assertEquals(1, binding.provenance().relationshipRecords().size());
        }
        assertEquals(mapper.valueToTree(results), mapper.valueToTree(resolver.resolveCandidates(candidates, hypotheses)));
        CandidateBindingResult single = resolver.resolve(actb, List.of(hypotheses.get(2)));
        assertEquals(1, single.bindings().size());
        assertEquals("MONDO:0017579", single.bindings().get(0).mondoId());
        CandidateBindingResult absent = resolver.resolve(actb, List.of());
        assertTrue(absent.bindings().isEmpty());
        assertEquals("DISEASE_HYPOTHESIS_REQUIRED", absent.blockers().get(0).reasonCode());

        String output = System.getProperty("varvis.e4a1a.smokeOutput");
        if (output != null && !output.isBlank()) {
            ObjectNode smoke = mapper.createObjectNode().put("stage", "E4-A1A")
                    .put("status", "PASS").put("patientHypothesisDataClass", "SYNTHETIC_PATIENT_HYPOTHESIS")
                    .put("candidateDataClass", "SYNTHETIC")
                    .put("clinicalValidation", false).put("fullEvidenceContextGenerated", false)
                    .put("evaluatorRun", false).put("patientTopNIntegratedCriterionCount", 0)
                    .put("publicGeneCount", 3).put("readyBindingCount", bindings.size())
                    .put("deterministic", true).put("catalogExpansionWithoutHypothesis", false)
                    .put("clingenNoRecordMeaning", "COVERAGE_ABSENT_NOT_RELATIONSHIP_ABSENT");
            smoke.set("candidateResults", mapper.valueToTree(results));
            smoke.set("actbSingleExplicitHypothesis", mapper.valueToTree(single));
            smoke.set("noHypothesis", mapper.valueToTree(absent));
            mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(output).toFile(), smoke);
        }
    }
}
