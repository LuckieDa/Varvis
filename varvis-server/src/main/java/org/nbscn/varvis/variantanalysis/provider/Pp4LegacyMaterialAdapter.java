package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Internal transport into the unchanged PP4 core, after native-original qualification.
 * Its projection mirrors satisfy the old transport contract; they are not primary sources
 * or an independent clinical qualification/approval mechanism.
 */
final class Pp4LegacyMaterialAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Pp4LegacyMaterialAdapter() { }

    static ObjectNode observation(Pp4QualifiedMaterial material) throws IOException {
        ObjectNode data = material.nativeFacts();data.set("case",material.currentCaseFacts());
        ObjectNode binding = binding(data);
        var observation = JSON.createObjectNode();
        ObjectNode facts = facts(data, binding, sourceId(material, "facts"));
        ObjectNode phenotype = phenotype(data, binding, sourceId(material, "phenotypeReview"));
        ObjectNode diagnosticYield = diagnosticYield(data, binding, sourceId(material, "diagnosticYield"));
        ObjectNode locus = locus(data, binding, sourceId(material, "locusAttribution"));
        observation.set("facts", facts); observation.set("phenotypeReview", phenotype);
        observation.set("diagnosticYield", diagnosticYield); observation.set("locusAttribution", locus);
        // A caller must add the typed current allocation before sealing any part.
        return observation;
    }

    static ObjectNode binding(ObjectNode data) {
        JsonNode context = data.path("context"), c = data.path("case");
        var binding = JSON.createObjectNode().put("observationId", c.path("observation_id").asText());
        for (var pair : java.util.Map.of("contextId", "/contextId", "alleleId", "/variant/alleleId",
                "hgncId", "/gene/hgncId", "mondoId", "/disease/mondoId", "moi", "/moi",
                "patientContextId", "/patientContext/patientContextId", "familyContextId", "/patientContext/familyContextId").entrySet())
            binding.set(pair.getKey(), context.at(pair.getValue()).deepCopy());
        binding.set("allele", context.at("/variant/evidenceQueryAllele").deepCopy()); return binding;
    }

    private static ObjectNode facts(ObjectNode data, ObjectNode binding, String source) {
        JsonNode c = data.path("case");
        var facts = JSON.createObjectNode().put("schemaVersion", "case-family-observation-v1")
                .put("observationId", c.path("observation_id").asText()).put("observationVersion", "native-profile-v1")
                .put("occurrenceId", c.path("occurrence_id").asText()).put("caseId", c.path("case_id").asText())
                .put("familyId", c.path("family_id").asText());
        facts.set("binding", binding.deepCopy());
        var member = facts.putArray("members").addObject().put("memberId", c.path("person_id").asText())
                .put("sampleId", c.path("sample_id").asText()).put("role", "PROBAND").put("affectedStatus", "AFFECTED");
        var detection = member.putObject("detection"); detection.set("binding", binding.deepCopy());
        detection.put("memberId", c.path("person_id").asText()).put("targetAltIndex", 1).put("ploidy", 2)
                .put("sampleFormat", "GT:DP:GQ:AD:FT").put("method", c.path("method").asText())
                .put("coverage", "COVERED").put("capability", "TARGET_DETECTION").put("quality", "VALID")
                .put("basis", "Exact acquired native sample row; raw GT/DP/GQ/AD/FT are replayed by the unchanged parser")
                .put("limitations", "Native autosomal diploid single heterozygous SNV profile"); refs(detection, source);
        var record = facts.putArray("records").addObject();
        var key = record.putObject("variantKey"); key.set("assembly", binding.at("/allele/assembly"));
        key.set("chromosome", binding.at("/allele/contig"));
        for (String field : List.of("position", "reference", "alternate")) key.set(field, binding.at("/allele/" + field));
        record.put("role", "PROBAND").put("sampleId", c.path("sample_id").asText()).put("geneSymbol", c.path("gene_symbol").asText())
                .put("recordFilter", c.path("record_filter").asText()).put("sampleFilter", c.path("sample_filter").asText());
        String gt = c.path("genotype").asText();
        record.put("rawSampleValue", String.join(":", gt, c.path("depth").asText(), c.path("genotype_quality").asText(),
                c.path("allele_depth").asText(), c.path("sample_filter").asText()));
        record.putObject("observation").put("role", "PROBAND").put("genotype", gt).put("call", "HET_ALT")
                .put("depth", Integer.parseInt(c.path("depth").asText())).put("genotypeQuality", Integer.parseInt(c.path("genotype_quality").asText()))
                .put("alleleDepth", c.path("allele_depth").asText()).put("phased", gt.contains("|"));
        facts.putArray("parentage"); var clinical = facts.putObject("clinical"); clinical.set("binding", binding.deepCopy());
        clinical.put("probandMemberId", c.path("person_id").asText()).put("observedAt", c.path("observed_at").asText())
                .put("age", c.path("age_years").asText()).put("phenotypeCategory", "HIGHLY_SPECIFIC")
                .put("heterogeneity", "NOT_HIGH").put("familyHistory", "UNKNOWN")
                .put("familyHistoryBasis", "Family-history evidence is not used by this single-case phenotype profile")
                .put("mosaicism", "REVIEWED_NONMOSAIC").put("scopeBasis", "Qualified independent definition and complete native clinical/cohort roster")
                .put("siblingRecurrence", "UNKNOWN");
        var present = clinical.putArray("phenotypeObservations"); var absent = clinical.putArray("exclusions");
        for (JsonNode row : data.path("observations")) (row.path("presence").asText().equals("PRESENT") ? present : absent).add(findingId(row));
        refs(clinical, source); return facts;
    }

    private static ObjectNode definition(ObjectNode data) {
        JsonNode d = data.path("definition");
        var result = JSON.createObjectNode();
        for (var pair : java.util.Map.of("definitionId", "definition_id", "version", "version", "hgncId", "hgnc_id", "mondoId", "mondo_id", "mechanism", "mechanism", "subtype", "subtype").entrySet())
            result.set(pair.getKey(), d.path(pair.getValue()));
        result.put("mode", "ALL_OF").put("ageMinimumYears", Integer.parseInt(d.path("minimum_age_years").asText()))
                .put("ageMaximumYears", Integer.parseInt(d.path("maximum_age_years").asText()));
        var requirements = result.putArray("requirements");
        for (JsonNode row : data.path("requirements")) requirements.addObject().put("featureId", row.path("feature_id").asText()).put("presence", row.path("presence").asText());
        return result;
    }

    private static ObjectNode phenotype(ObjectNode data, ObjectNode binding, String source) {
        JsonNode c = data.path("case"), d = data.path("definition");
        var result = JSON.createObjectNode().put("schemaVersion", "pp4-phenotype-review-v1"); result.set("binding", binding.deepCopy());
        result.put("caseId", c.path("case_id").asText()).put("occurrenceId", c.path("occurrence_id").asText()).put("probandMemberId", c.path("person_id").asText());
        result.set("definition", definition(data)); result.put("ageYears", Integer.parseInt(c.path("age_years").asText())).put("ageUnit", "YEARS");
        assay(result, c); result.put("rosterStatus", "COMPLETE_FROZEN_ROSTER").put("linkage", "NOT_REQUIRED");
        var findings = result.putArray("findings");
        for (JsonNode row : data.path("observations")) {
            var f = findings.addObject().put("findingId", findingId(row)).put("featureId", row.path("feature_id").asText())
                    .put("presence", row.path("presence").asText()).put("observedAt", row.path("observed_at").asText())
                    .put("examination", "PERFORMED").put("ageApplicable", "APPLICABLE").put("method", row.path("examination_method").asText()); refs(f, source);
        }
        var alternatives = result.putArray("alternatives");
        for (JsonNode row : data.path("reviews")) if (List.of("ALTERNATIVE_DIAGNOSIS", "PHENOCOPY").contains(row.path("kind").asText())) {
            var a = alternatives.addObject().put("kind", row.path("kind").asText()).put("caseId", c.path("case_id").asText())
                    .put("investigation", "COMPLETE").put("detected", false).put("method", row.path("method").asText()).put("basis", row.path("basis").asText());
            a.putArray("unresolvedFindings"); refs(a, source);
        }
        var basis = result.putObject("geneDiseaseBasis").put("hgncId", d.path("hgnc_id").asText()).put("mondoId", d.path("mondo_id").asText())
                .put("mechanism", d.path("mechanism").asText()).put("validity", "ESTABLISHED").put("basisId", d.path("basis_id").asText())
                .put("independence", "INDEPENDENT_OF_CURRENT_VARIANT_PP4");
        basis.putArray("excludedAlleleIds").add(binding.path("alleleId").asText()); refs(basis, source);
        result.set("review", review(data, source)); return result;
    }

    private static ObjectNode diagnosticYield(ObjectNode data, ObjectNode binding, String source) {
        JsonNode c = data.path("case"), closure = data.path("closure");
        var result = JSON.createObjectNode().put("schemaVersion", "pp4-diagnostic-yield-v1"); result.set("binding", binding.deepCopy());
        result.put("cohortId", closure.path("cohort_id").asText()).put("cohortVersion", closure.path("cohort_version").asText())
                .put("ascertainment", "PHENOTYPE_FIRST_COMPLETE_TESTED_SERIES").put("statistic", "GENE_SPECIFIC_DIAGNOSTIC_YIELD")
                .put("unit", "FRACTION").put("denominatorDefinition", "PHENOTYPE_ELIGIBLE_TESTED_HISTORICAL_CASES")
                .put("geneSpecificDiagnosedCount", data.path("numerator").asInt()).put("eligibleTestedCount", data.path("denominator").asInt())
                .put("comparability", "REVIEWED_SAME_SCOPE").put("independence", "EXCLUDES_CURRENT_CASE_RELATED_FAMILY_AND_VARIANT_PP4")
                .put("rosterStatus", "COMPLETE_FROZEN_ROSTER");
        assay(result, c); result.set("definition", definition(data));
        var design = result.putObject("ascertainmentDetails").put("schemaVersion", "pp4-cohort-ascertainment-v1")
                .put("description", "Complete acquired native enrollment, examination, test and independent diagnosis roster")
                .put("eligibility", "PHENOTYPE_DEFINITION_BEFORE_TESTING").put("testing", "ALL_ELIGIBLE_TESTED")
                .put("resultSelection", "NOT_SELECTED_BY_GENE_RESULT").put("basis", "Original export closure " + closure.path("export_id").asText()); refs(design, source);
        var cases = result.putArray("cases");
        for (JsonNode row : data.path("enrollment")) {
            var item = cases.addObject().put("caseId", row.path("case_id").asText()).put("personId", row.path("person_id").asText())
                    .put("familyId", row.path("family_id").asText()).put("phenotypeEligible", true).put("tested", true)
                    .put("diagnosedInGene", row.path("diagnosed_in_gene").asText().equals("TRUE"))
                    .put("classificationBasis", "INDEPENDENT_OF_CURRENT_VARIANT_PP4");
            var variants = item.putArray("diagnosticAlleleIds");
            String keys = row.path("diagnosis_variant_keys").asText(); if (!keys.isEmpty()) for (String key : keys.split("\\|")) variants.add(key);
            refs(item, source);
        }
        result.set("review", review(data, source)); return result;
    }

    private static ObjectNode locus(ObjectNode data, ObjectNode binding, String source) {
        JsonNode c = data.path("case");
        var result = JSON.createObjectNode().put("schemaVersion", "pp4-locus-attribution-v1"); result.set("binding", binding.deepCopy());
        result.put("caseId", c.path("case_id").asText()); assay(result, c); result.remove("populationId");
        result.put("coverage", "COMPLETE_CODING_AND_SPLICE").put("rosterStatus", "COMPLETE_FROZEN_ROSTER").put("allocation", "SOLE_TARGET_NO_SHARING");
        var candidates = result.putArray("candidates");
        for (JsonNode row : data.path("candidates")) {
            var target = candidates.addObject().put("candidateId", row.path("candidate_id").asText()).put("alleleId", binding.path("alleleId").asText())
                    .put("hgncId", row.path("hgnc_id").asText()).put("disposition", "TARGET").put("exclusion", "NOT_EXCLUDED")
                    .put("basis", "Original sole-target candidate roster and dated full coding/splice clinician review"); refs(target, source);
        }
        result.putArray("usedEvidence"); result.putArray("clinicalUse");
        // Deliberately incomplete until MAIN supplies an actual current-session allocation capability.
        result.put("usedEvidenceStatus", "UNKNOWN").put("clinicalUseStatus", "UNKNOWN");
        result.set("review", review(data, source)); return result;
    }

    static void seal(ObjectNode observation, Pp4QualifiedMaterial material) throws IOException {
        var all = JSON.createArrayNode();
        for (String part : Pp4PhenotypeBundle.PARTS) {
            ObjectNode document = (ObjectNode) observation.path(part);
            String source = sourceId(material, part); all.add(source);
            String raw = JSON.writeValueAsString(document);
            var s = document.putArray("sources").addObject().put("sourceId", source).put("recordId", source)
                    .put("version", "native-qualified-transport-v1").put("locator", "urn:varvis:pp4:qualified:" + material.sha256() + ":" + part)
                    .put("acquiredAt", inputsAsOf(material.nativeFacts())).put("rawUtf8", raw)
                    .put("rawSha256", Pp4ProductionMethodRegistration.rawSha(raw.getBytes(StandardCharsets.UTF_8)));
            ObjectNode projection = document.deepCopy(); projection.remove("sources"); s.put("canonicalSha256", Pp4ProductionMethodRegistration.canonical(projection));
        }
        var a = observation.putObject("assessment").put("schemaVersion", "pp4-assessment-v1")
                .put("assessmentId", "pp4-assessment-sha256-" + material.sha256()).put("assessmentVersion", "native-profile-v1");
        var pins = a.putObject("partPins");
        for (String part : Pp4PhenotypeBundle.PARTS) pins.put(part, Pp4ProductionMethodRegistration.canonical(observation.path(part)));
        a.set("factsReview", review(material.nativeFacts(), sourceId(material, "facts")));
        var review = review(material.nativeFacts(), sourceId(material, "facts")); review.set("sourceIds", all); a.set("review", review);
        a.put("contentSha256", Pp4ProductionMethodRegistration.canonical(a));
    }

    static String sourceId(Pp4QualifiedMaterial material, String part) { return "pp4-native-" + part + "-" + material.sha256(); }
    private static String findingId(JsonNode row) { return "pp4-finding-sha256-" + Pp4ProductionMethodRegistration.canonical(row); }
    private static void assay(ObjectNode target, JsonNode c) {
        target.put("method", c.path("method").asText()).put("testVersion", c.path("test_version").asText()).put("populationId", c.path("population_id").asText());
    }
    private static void refs(ObjectNode target, String source) { target.putArray("sourceIds").add(source); }
    private static String inputsAsOf(ObjectNode data) {
        return java.util.stream.StreamSupport.stream(data.at("/registeredInputs/originals").spliterator(), false)
                .map(pin -> java.time.Instant.parse(pin.path("acquiredAt").asText()))
                .max(java.util.Comparator.naturalOrder()).orElseThrow().toString();
    }
    private static ObjectNode review(ObjectNode data, String source) {
        var result = JSON.createObjectNode().put("status", "REVIEWED").put("reviewer", "native-profile-verifier-v1")
                .put("date", inputsAsOf(data))
                .put("basis", "Internal old-core transport at registered-input as-of, not clinical observation time; this label grants no qualification"); refs(result, source); return result;
    }
}
