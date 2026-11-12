package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.nbscn.varvis.variantanalysis.service.T04CurrentSessionFixture;
/** Literal SYNTHETIC engineering originals; no clinical approval or production adoption is implied. */
public final class T04NativeOriginalFixture {
        private static final Map<String, List<String>> COLUMNS = Map.ofEntries(
                Map.entry("case", List.of("case_id", "occurrence_id", "observation_id", "person_id", "family_id", "sample_id", "gene_symbol", "hgnc_id", "mondo_id", "moi", "assembly", "contig", "position", "reference", "alternate", "genotype", "depth", "genotype_quality", "allele_depth", "sample_filter", "record_filter", "method", "test_version", "population_id", "observed_at", "age_years", "sex", "affected_status")),
                Map.entry("observations", List.of("case_id", "observation_id", "feature_id", "presence", "examination_method", "observed_at")),
                Map.entry("candidates", List.of("case_id", "candidate_id", "hgnc_id", "assembly", "contig", "position", "reference", "alternate")),
                Map.entry("definition", List.of("definition_id", "version", "hgnc_id", "mondo_id", "moi", "mechanism", "subtype", "minimum_age_years", "maximum_age_years", "specified_at", "basis_id", "basis_method", "basis_date", "basis_record_id", "basis_variant_keys")),
                Map.entry("requirements", List.of("feature_id", "presence")),
                Map.entry("enrollment", List.of("cohort_id", "case_id", "person_id", "family_id", "observation_id", "age_years", "method", "test_version", "population_id", "eligibility_at", "tested_at", "diagnosis_at", "diagnosed_in_gene", "diagnostic_hgnc_id", "diagnostic_mondo_id", "diagnosis_basis_method", "diagnosis_basis_record_id", "diagnosis_basis_criteria", "diagnosis_variant_keys")),
                Map.entry("closure", List.of("cohort_id", "cohort_version", "definition_id", "definition_version", "method", "test_version", "population_id", "export_id", "eligible_case_ids", "export_method", "export_completed_at")),
                Map.entry("reviews", List.of("case_id", "person_id", "observation_id", "kind", "method", "reviewed_at", "result", "basis_record_id", "basis")),
                Map.entry("assertion", List.of("basis_id", "record_id", "hgnc_id", "mondo_id", "moi", "mechanism", "validity_assertion", "curator_id", "curator_affiliation", "curation_method", "curated_at", "evidence_record_ids")),
                Map.entry("evidence", List.of("record_id", "case_id", "person_id", "family_id", "hgnc_id", "mondo_id", "moi", "mechanism", "assembly", "contig", "position", "reference", "alternate", "diagnosis_method", "diagnosis_at", "diagnosis_basis_criteria", "diagnosis_basis_record_id")),
                Map.entry("reports", List.of("record_id", "report_id", "case_id", "person_id", "family_id", "sample_id", "hgnc_id", "mondo_id", "moi", "mechanism", "issued_at", "issuer_name", "issuer_method", "conclusion", "native_variant_keys", "variant_record_ids")),
                Map.entry("variants", List.of("record_id", "report_id", "assembly", "contig", "position", "reference", "alternate", "sample_id", "genotype", "method", "test_version", "classification", "conclusion_date", "criteria")),
                Map.entry("sample_links", List.of("case_id", "person_id", "family_id", "clinical_sample_id", "vcf_raw_sha256", "vcf_sample_id", "vcf_sample_ordinal", "analysis_assembly", "issuer", "issued_at", "basis_record_id")));
        private final Map<String, Map<String, List<List<String>>>> roles = new LinkedHashMap<>();
        private final Map<String, String> acquiredAt = new LinkedHashMap<>();
        private final ObjectNode actualContext;
        public T04NativeOriginalFixture(T04CurrentSessionFixture fixture, ObjectNode context) {
            this(fixture.candidateId(), fixture.geneSymbol(), context);
        }
        public T04NativeOriginalFixture(String candidateId, String geneSymbol, ObjectNode context) {
            actualContext = context.deepCopy();
            String hgnc = context.at("/gene/hgncId").asText(), mondo = context.at("/disease/mondoId").asText(), moi = context.path("moi").asText();
            var targetAllele = context.at("/variant/evidenceQueryAllele");
            add("CLINICAL", "case", row("case-target", "occurrence-target", "observation-target",
                    context.at("/patientContext/patientContextId").asText(), context.at("/patientContext/familyContextId").asText(),
                    "synthetic-proband", geneSymbol, hgnc, mondo, moi,
                    targetAllele.path("assembly").asText(), targetAllele.path("contig").asText(), targetAllele.path("position").asText(),
                    targetAllele.path("reference").asText(), targetAllele.path("alternate").asText(), "0/1", "40", "60", "20,20", "PASS", "PASS",
                    "EXOME_AND_CODING_SPLICE_VALIDATION", "assay-v1", "population-t04", "2024-01-01T00:00:00Z", "20", "FEMALE", "AFFECTED"));
            add("CLINICAL", "observations", row("case-target", "observation-target", "FEATURE_REQUIRED", "PRESENT", "Recorded specialist examination", "2024-01-01T00:00:00Z"));
            add("CLINICAL", "observations", row("case-target", "observation-target", "FEATURE_EXCLUDED", "ABSENT", "Recorded imaging examination", "2024-01-01T00:00:00Z"));
            add("CLINICAL", "candidates", row("case-target", candidateId, hgnc, targetAllele.path("assembly").asText(), targetAllele.path("contig").asText(),
                    targetAllele.path("position").asText(), targetAllele.path("reference").asText(), targetAllele.path("alternate").asText()));
            add("DEFINITION", "definition", row("definition-t04", "definition-v1", hgnc, mondo, moi,
                    "LOSS_OF_FUNCTION", "SYNTHETIC_FINITE_PHENOTYPE", "5", "60", "2019-01-01T00:00:00Z", "basis-t04", "Independent gene-disease curation",
                    "2019-01-01T00:00:00Z", "basis-original-t04", "GRCh38:11:4:G:A"));
            add("DEFINITION", "requirements", row("FEATURE_REQUIRED", "PRESENT"));
            add("DEFINITION", "requirements", row("FEATURE_EXCLUDED", "ABSENT"));
            add("GENE_DISEASE_BASIS", "assertion", row("basis-t04", "basis-original-t04", hgnc, mondo, moi, "LOSS_OF_FUNCTION",
                    "ESTABLISHED", "SYNTHETIC_CURATOR", "SYNTHETIC_RESEARCH_UNIT", "Independent gene-disease curation", "2019-01-01T00:00:00Z", "basis-evidence-t04"));
            add("GENE_DISEASE_BASIS", "evidence", row("basis-evidence-t04", "independent-basis-case", "independent-basis-person", "independent-basis-family",
                    hgnc, mondo, moi, "LOSS_OF_FUNCTION", "GRCh38", "11", "4", "G", "A",
                    "Independent clinical molecular diagnosis", "2018-01-01T00:00:00Z", "ACMG_AMP_2015:PVS1|ACMG_AMP_2015:PM2", "basis-diagnosis-original-t04"));
            report("basis-diagnosis-original-t04", "basis-report-t04", "independent-basis-case", "independent-basis-person", "independent-basis-family",
                    "2018-01-01T00:00:00Z", "Independent clinical molecular diagnosis", "4", "G", "A");
            var eligible = new ArrayList<String>();
            for (int index = 1; index <= 12; index++) {
                String id = String.format(java.util.Locale.ROOT, "cohort-case-%02d", index), observation = "observation-" + id;
                eligible.add(id);
                add("COHORT", "enrollment", row("cohort-t04", id, "person-" + id, "family-" + id, observation, "20",
                        "EXOME_AND_CODING_SPLICE_VALIDATION", "assay-v1", "population-t04", "2020-02-01T00:00:00Z",
                        "2020-03-01T00:00:00Z", "2020-04-01T00:00:00Z", index <= 5 ? "TRUE" : "FALSE", hgnc, mondo,
                        "Independent sequence and clinical diagnosis", "diagnosis-original-" + id,
                        index <= 5 ? "ACMG_AMP_2015:PVS1|ACMG_AMP_2015:PM2" : "", index <= 5 ? "GRCh38:11:3:C:G" : ""));
                if (index <= 5) report("diagnosis-original-" + id, "report-" + id, id, "person-" + id, "family-" + id,
                        "2020-04-01T00:00:00Z", "Independent sequence and clinical diagnosis", "3", "C", "G");
                add("COHORT", "observations", row(id, observation, "FEATURE_REQUIRED", "PRESENT", "Recorded specialist examination", "2020-02-01T00:00:00Z"));
                add("COHORT", "observations", row(id, observation, "FEATURE_EXCLUDED", "ABSENT", "Recorded imaging examination", "2020-02-01T00:00:00Z"));
            }
            add("COHORT", "closure", row("cohort-t04", "cohort-v1", "definition-t04", "definition-v1", "EXOME_AND_CODING_SPLICE_VALIDATION",
                    "assay-v1", "population-t04", "complete-export-t04", String.join("|", eligible),
                    "ALL_PHENOTYPE_FIRST_TESTED_RECORDS", "2020-05-01T00:00:00Z"));
            for (var pair : Map.of("NONMOSAIC", "NONMOSAIC", "ALTERNATIVE_DIAGNOSIS", "EXCLUDED", "PHENOCOPY", "EXCLUDED",
                    "LOCUS_COVERAGE", "COMPLETE_CODING_AND_SPLICE_SOLE_TARGET").entrySet()) {
                add("CLINICIAN", "reviews", row("case-target", context.at("/patientContext/patientContextId").asText(), "observation-target",
                        pair.getKey(), "Dated original specialist assessment with complete scope", "2024-03-01T00:00:00Z", pair.getValue(),
                        "clinical-original-" + pair.getKey(), "SYNTHETIC recorded examination and assay coverage evidence for " + pair.getKey()));
            }
        }
        /** Adds an explicit original clinical-to-VCF association for new native integration tests only. */
        public void nativeSampleLink(T04CurrentSessionFixture fixture, ObjectNode context) {
            nativeSampleLink(Pp4ProductionMethodRegistration.rawSha(fixture.originalVcfBytes()), fixture.sampleId(), 1, context);
        }
        public void nativeSampleLink(String actualVcfRawSha256, String actualVcfSampleId, int oneBasedGenotypeSampleOrdinal, ObjectNode context) {
            add("CLINICAL", "sample_links", row("case-target", context.at("/patientContext/patientContextId").asText(),
                    context.at("/patientContext/familyContextId").asText(), "synthetic-proband", actualVcfRawSha256, actualVcfSampleId,
                    Integer.toString(oneBasedGenotypeSampleOrdinal), context.at("/variant/evidenceQueryAllele/assembly").asText(),
                    "SYNTHETIC original clinical laboratory", "2024-03-01T00:00:00Z", "original-clinical-vcf-association-t04"));
        }

        /** Writes engineering original bytes before actual server-configuration loading; never repairs source facts. */
        public Path writeServerBundle(Path directory, ObjectNode context) throws Exception {
            assertTrue(roles.get("CLINICAL").containsKey("sample_links"), "explicit original sample association required in native fixture");
            Files.createDirectories(directory); var mapper = new ObjectMapper();
            byte[] guidance = Files.readAllBytes(Path.of(System.getProperty("varvis.t04.guidance-xml")));
            byte[] rules = mapper.writeValueAsBytes(ruleOriginal(context));
            Files.write(directory.resolve("guidance.xml"), guidance); Files.write(directory.resolve("strict-rules.json"), rules);
            var manifest = mapper.createObjectNode().put("schemaVersion", "pp4-server-registered-inputs-v1")
                    .put("registrationId", "SYNTHETIC_ENGINEERING_T04").put("registrationVersion", "1.0.0")
                    .put("registeredAt", "2026-11-04T00:00:00Z").put("dataClass", context.path("dataClass").asText());
            manifest.putObject("guidance").put("file", "guidance.xml").put("rawSha256", Pp4ProductionMethodRegistration.rawSha(guidance))
                    .put("acquiredAt", "2025-01-01T00:00:00Z");
            manifest.putObject("rules").put("file", "strict-rules.json").put("rawSha256", Pp4ProductionMethodRegistration.rawSha(rules))
                    .put("canonicalSha256", Pp4TestMaterials.hash(mapper.readTree(rules))).put("acquiredAt", "2025-01-01T00:00:00Z");
            var originalIndex = manifest.putArray("originals"); int index = 0;
            for (var original : originals()) {
                String file = "primary-" + (++index) + ".xml"; Files.write(directory.resolve(file), original.raw());
                var entry = originalIndex.addObject().put("role", original.role()).put("sourceId", original.sourceId()).put("pmcid", original.pmcid())
                        .put("file", file).put("rawSha256", original.expectedRawSha256()).put("locator", original.locator()).put("acquiredAt", original.acquiredAt());
                entry.set("tableIds", mapper.valueToTree(new java.util.TreeMap<>(original.tableIds())));
            }
            Path path = directory.resolve("manifest.json"); Files.write(path, mapper.writeValueAsBytes(manifest)); return path;
        }

        private void report(String record, String report, String caseId, String person, String family, String date,
                String method, String position, String reference, String alternate) {
            String key = "GRCh38:11:" + position + ":" + reference + ":" + alternate, variantRecord = "variant-" + record;
            add("DIAGNOSTIC_REPORTS", "reports", row(record, report, caseId, person, family, "sample-" + caseId, actualContext.at("/gene/hgncId").asText(),
                    actualContext.at("/disease/mondoId").asText(), actualContext.path("moi").asText(),
                    "LOSS_OF_FUNCTION", date, "SYNTHETIC original molecular diagnostic laboratory", method, "MOLECULAR_DIAGNOSIS", key, variantRecord));
            add("DIAGNOSTIC_REPORTS", "variants", row(variantRecord, report, "GRCh38", "11", position, reference, alternate,
                    "sample-" + caseId, "0/1", "EXOME_AND_CODING_SPLICE_VALIDATION", "assay-v1", "PATHOGENIC", date,
                    "ACMG_AMP_2015:PVS1|ACMG_AMP_2015:PM2"));
        }
        public static List<String> row(String... cells) { return new ArrayList<>(List.of(cells)); }
        public void add(String role, String table, List<String> cells) {
            assertEquals(COLUMNS.get(table).size(), cells.size(), "independent source row width: " + table);
            roles.computeIfAbsent(role, ignored -> new LinkedHashMap<>()).computeIfAbsent(table, ignored -> new ArrayList<>()).add(cells);
        }
        public void set(String role, String table, int rowIndex, String field, String value) {
            int column = COLUMNS.get(table).indexOf(field); assertTrue(column >= 0);
            roles.get(role).get(table).get(rowIndex).set(column, value);
        }
        public void remove(String role, String table, int rowIndex) { roles.get(role).get(table).remove(rowIndex); }
        public void acquiredAt(String role, String instant) { acquiredAt.put(role, instant); }
        public List<Pp4ProductionMethodRegistration.PrimaryArtifact> originals() {
            var artifacts = new ArrayList<Pp4ProductionMethodRegistration.PrimaryArtifact>(); int index = 1;
            for (var role : roles.entrySet()) {
                String pmcid = "PMC990000" + index++, id = "SYNTHETIC_" + role.getKey();
                var tableIds = new LinkedHashMap<String, String>();
                var xml = new StringBuilder("<article><front><article-meta><article-id pub-id-type=\"pmc\">").append(pmcid)
                        .append("</article-id><title-group><article-title>SYNTHETIC engineering fixture; not clinical data</article-title></title-group></article-meta></front><body>");
                for (var table : role.getValue().entrySet()) {
                    tableIds.put(table.getKey(), "table-" + table.getKey());
                    xml.append("<table-wrap id=\"table-").append(table.getKey()).append("\"><table><thead><tr>");
                    for (String header : COLUMNS.get(table.getKey())) xml.append("<th>").append(escape(header)).append("</th>");
                    xml.append("</tr></thead><tbody>");
                    for (var row : table.getValue()) {
                        xml.append("<tr>"); for (String cell : row) xml.append("<td>").append(escape(cell)).append("</td>"); xml.append("</tr>");
                    }
                    xml.append("</tbody></table></table-wrap>");
                }
                byte[] raw = xml.append("</body></article>").toString().getBytes(StandardCharsets.UTF_8);
                artifacts.add(new Pp4ProductionMethodRegistration.PrimaryArtifact(role.getKey(), id, pmcid, tableIds, raw,
                        Pp4ProductionMethodRegistration.rawSha(raw), "urn:varvis:synthetic:t04:" + role.getKey(), acquiredAt.getOrDefault(role.getKey(), "2025-01-01T00:00:00Z")));
            }
            return List.copyOf(artifacts);
        }
        private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
        public static ObjectNode context(T04CurrentSessionFixture fixture) {
        var json = new ObjectMapper();
        var adapter = new RuntimeEvidenceV2Adapter(json);
        var value = json.createObjectNode().put("schemaVersion", "evidence-context-v3")
                .put("canonicalizationProfile", "varvis-json-c14n-v1").put("dataClass", "SYNTHETIC")
                .put("moi", "AUTOSOMAL_DOMINANT");
        value.putObject("scope").put("analysisDomain", "GERMLINE").put("diseaseModel", "MENDELIAN")
                .put("variantScope", "SMALL_SEQUENCE_VARIANT");
        var allele = json.createObjectNode().put("assembly", "GRCh38").put("contig", "11").put("position", 2)
                .put("reference", "T").put("alternate", "A");
        var variant = value.putObject("variant").put("variantType", "SNV");
        variant.set("inputAllele", allele.deepCopy()); variant.set("normalizedAllele", allele.deepCopy());
        variant.set("evidenceQueryAllele", allele.deepCopy());
        var normalization = variant.putObject("normalization").put("status", "UNCHANGED")
                .put("method", "T04_SYNTHETIC_NATIVE_REFERENCE_MATCH").put("methodVersion", "1.0.0")
                .put("referenceSequenceSha256", fixture.referenceSha256()).put("refValidation", "MATCH");
        variant.put("alleleId", adapter.canonicalAlleleId(allele, normalization));
        variant.putObject("assemblyLineage").put("mappingStatus", "NATIVE");
        value.putObject("gene").put("hgncId", "HGNC:1").put("symbol", fixture.geneSymbol());
        value.putObject("disease").put("mondoId", "MONDO:0000001").put("label", "SYNTHETIC finite phenotype");
        value.putObject("patientContext").put("patientContextId", "patient-context-t04-proband")
                .put("familyContextId", "family-context-t04-family").put("role", "PROBAND")
                .put("affectedStatus", "AFFECTED").put("sex", "FEMALE");
        value.putObject("contextCompleteness").put("status", "COMPLETE").putArray("missingFields");
        value.put("contextId", adapter.canonicalVersionedContextId(value));
        adapter.validateVersionedContext(value);
        return value;
    }

    /** Uses the actual binding builder before claim; it does not issue a native evaluation capability. */
    public static ObjectNode analysisBinding(ObjectNode context, T04CurrentSessionFixture fixture, List<String> criteria) {
        return new Pp1AnalysisContext(new ObjectMapper()).bind(context, fixture.candidateId(), "hypothesis", fixture.executionSourceSha256(), criteria);
    }

    /** Original synthetic parent/child reports are re-scoped before sealing, never replaced by a criterion result. */
    public static DeNovoSelectedBatch inheritedDeNovo(Path directory, ObjectNode context) throws Exception {
        var original = new DeNovoBatchFixture(directory);
        String person = context.at("/patientContext/patientContextId").asText();
        original.report.put("familyId", context.at("/patientContext/familyContextId").asText());
        original.report.withObject("/target").set("hgncId", context.at("/gene/hgncId"));
        for (String key : List.of("assembly", "contig", "position", "reference", "alternate"))
            original.report.withObject("/target").set(key, context.at("/variant/evidenceQueryAllele/" + key));
        for (var member : original.report.path("members")) if (member.path("role").asText().equals("PROBAND")) ((ObjectNode) member).put("memberId", person);
        for (var test : original.report.path("tests")) {
            if (test.path("memberId").asText().equals(DeNovoBatchFixture.PATIENT)) ((ObjectNode) test).put("memberId", person);
            if (test.path("memberId").asText().equals("father")) ((ObjectNode) test).put("result", "PRESENT");
        }
        for (var parentage : original.report.path("parentage")) ((ObjectNode) parentage).put("probandId", person);
        original.report.withObject("/clinical").put("probandId", person).set("reportedDiseaseId", context.at("/disease/mondoId"));
        original.phenotype.withObject("/scope").set("hgncId", context.at("/gene/hgncId"));
        original.phenotype.withObject("/scope").set("diseaseId", context.at("/disease/mondoId"));
        for (var policy : original.selectorPolicies.path("policies")) {
            ((ObjectNode) policy.path("scope")).set("hgncId", context.at("/gene/hgncId"));
            ((ObjectNode) policy.path("scope")).set("mondoId", context.at("/disease/mondoId"));
        }
        for (var entry : original.catalog.path("entries")) {
            ((ObjectNode) entry.path("scope")).set("hgncId", context.at("/gene/hgncId"));
            ((ObjectNode) entry.path("scope")).set("mondoId", context.at("/disease/mondoId"));
        }
        original.save(); return original.load();
    }
    public static String canonicalSha(JsonNode value) throws Exception { return Pp4TestMaterials.hash(value); }
    public static String rawSha(byte[] value) throws Exception { return Pp4TestMaterials.raw(value); }
    /** A newly generated, explicitly SYNTHETIC rule original; never an acquired expert catalog. */
    public static ObjectNode ruleOriginal(ObjectNode context) throws Exception {
        var template = Pp4TestMaterials.base("alpha"); template.set("context", context.deepCopy());
        var scope = (ObjectNode) template.at("/policy/scope");
        scope.set("hgncId", context.at("/gene/hgncId")); scope.set("mondoId", context.at("/disease/mondoId")); scope.set("moi", context.path("moi"));
        ((ObjectNode) template.at("/catalog/entries/0")).set("scope", scope.deepCopy());
        Pp4TestMaterials.catalog(template);
        return template;
    }

}
