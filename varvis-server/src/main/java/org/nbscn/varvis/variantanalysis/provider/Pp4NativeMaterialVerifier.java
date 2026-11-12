package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixed native clinical/cohort row profile. No JSON-path mirrors, NLP, eligibility flags or new PP4 mathematics. */
public final class Pp4NativeMaterialVerifier {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, List<String>> HEADERS = Map.ofEntries(
            Map.entry("case", cols("case_id occurrence_id observation_id person_id family_id sample_id gene_symbol hgnc_id mondo_id moi assembly contig position reference alternate genotype depth genotype_quality allele_depth sample_filter record_filter method test_version population_id observed_at age_years sex affected_status")),
            Map.entry("observations", cols("case_id observation_id feature_id presence examination_method observed_at")),
            Map.entry("candidates", cols("case_id candidate_id hgnc_id assembly contig position reference alternate")),
            Map.entry("definition", cols("definition_id version hgnc_id mondo_id moi mechanism subtype minimum_age_years maximum_age_years specified_at basis_id basis_method basis_date basis_record_id basis_variant_keys")),
            Map.entry("requirements", cols("feature_id presence")),
            Map.entry("enrollment", cols("cohort_id case_id person_id family_id observation_id age_years method test_version population_id eligibility_at tested_at diagnosis_at diagnosed_in_gene diagnostic_hgnc_id diagnostic_mondo_id diagnosis_basis_method diagnosis_basis_record_id diagnosis_basis_criteria diagnosis_variant_keys")),
            Map.entry("closure", cols("cohort_id cohort_version definition_id definition_version method test_version population_id export_id eligible_case_ids export_method export_completed_at")),
            Map.entry("reviews", cols("case_id person_id observation_id kind method reviewed_at result basis_record_id basis")),
            Map.entry("assertion", cols("basis_id record_id hgnc_id mondo_id moi mechanism validity_assertion curator_id curator_affiliation curation_method curated_at evidence_record_ids")),
            Map.entry("evidence", cols("record_id case_id person_id family_id hgnc_id mondo_id moi mechanism assembly contig position reference alternate diagnosis_method diagnosis_at diagnosis_basis_criteria diagnosis_basis_record_id")),
            Map.entry("reports", cols("record_id report_id case_id person_id family_id sample_id hgnc_id mondo_id moi mechanism issued_at issuer_name issuer_method conclusion native_variant_keys variant_record_ids")),
            Map.entry("variants", cols("record_id report_id assembly contig position reference alternate sample_id genotype method test_version classification conclusion_date criteria")));
    private Pp4NativeMaterialVerifier() { }
    private static List<String> cols(String text) { return List.of(text.split(" ")); }
    public static Map<String, List<String>> headers() { return HEADERS; }

    /** Literal available inputs for a failed source review, not a second verifier. */
    static ObjectNode diagnosticInputs(Pp4ProductionMethodRegistration registration) {
        var out=JSON.createObjectNode().put("clinicalQualificationGranted",false);var available=out.putObject("literalOriginalFields");var reads=out.putArray("sourceReads");
        for(var pair:List.of(new String[]{"CLINICAL","case"},new String[]{"DEFINITION","definition"},new String[]{"DEFINITION","requirements"},
                new String[]{"CLINICAL","observations"},new String[]{"CLINICIAN","reviews"})) {
            var entry=reads.addObject().put("role",pair[0]).put("recordPart",pair[1]);var artifact=registration.inputs().artifact(pair[0]);
            if(artifact!=null) entry.put("sourceId",artifact.sourceId()).put("rawSha256",artifact.expectedRawSha256());
            try {
                var records=rows(registration,pair[0],pair[1],JSON.createObjectNode());var projected=available.putArray(pair[0]+":"+pair[1]);
                List<String> fields=switch(pair[1]) {
                    case "case" -> cols("case_id person_id family_id sample_id hgnc_id mondo_id moi assembly contig position reference alternate age_years observed_at affected_status method test_version population_id");
                    case "definition" -> cols("definition_id version hgnc_id mondo_id moi mechanism subtype minimum_age_years maximum_age_years specified_at");
                    case "reviews" -> cols("case_id person_id observation_id kind result reviewed_at basis_record_id");
                    default -> HEADERS.get(pair[1]);
                };
                for(var record:records) { var projectedRecord=projected.addObject();for(String field:fields) projectedRecord.put(field,record.get(field)); }
                entry.put("status","LITERAL_SOURCE_FIELDS_READ_NOT_QUALIFIED");
            }
            catch(IOException|IllegalArgumentException invalid) { entry.put("status",artifact==null?"ORIGINAL_NOT_REGISTERED":"SOURCE_PROFILE_NOT_READ").put("reasonCode",invalid.getMessage()==null?"PP4_NATIVE_SOURCE_READ_UNAVAILABLE":invalid.getMessage()); }
        }
        return out;
    }

    /** A scheduling hint only: exact native gene/disease/allele scope may require
     * identity proof after stage-one consumers. It does not qualify the source. */
    static boolean mayNeedNativeIdentity(Pp4ProductionMethodRegistration registration,ObjectNode context) throws IOException {
        var c=single(rows(registration,"CLINICAL","case",JSON.createObjectNode()));var allele=context.at("/variant/evidenceQueryAllele");
        return c.get("hgnc_id").equals(context.at("/gene/hgncId").asText())&&c.get("gene_symbol").equals(context.at("/gene/symbol").asText())
                &&c.get("mondo_id").equals(context.at("/disease/mondoId").asText())&&c.get("moi").equals(context.path("moi").asText())
                &&c.get("assembly").equals(allele.path("assembly").asText())&&c.get("contig").equals(allele.path("contig").asText())
                &&c.get("position").equals(allele.path("position").asText())&&c.get("reference").equals(allele.path("reference").asText())
                &&c.get("alternate").equals(allele.path("alternate").asText())
                &&(!c.get("person_id").equals(context.at("/patientContext/patientContextId").asText())||!c.get("family_id").equals(context.at("/patientContext/familyContextId").asText()));
    }
    static ObjectNode verify(Pp4ProductionMethodRegistration registration, ObjectNode context) throws IOException { return verify(registration,context,null); }
    static ObjectNode verify(Pp4ProductionMethodRegistration registration,ObjectNode context,
            @org.springframework.lang.Nullable org.nbscn.varvis.variantanalysis.service.CurrentClinicalAllocation.NativeClinicalIdentity identity) throws IOException {
        var data = JSON.createObjectNode(); data.set("context", context.deepCopy());
        var sourceTables = JSON.createObjectNode();
        var c = single(rows(registration, "CLINICAL", "case", sourceTables));
        var d = single(rows(registration, "DEFINITION", "definition", sourceTables));
        var requirements = rows(registration, "DEFINITION", "requirements", sourceTables);
        var clinical = rows(registration, "CLINICAL", "observations", sourceTables);
        var candidates = rows(registration, "CLINICAL", "candidates", sourceTables);
        var enrolled = rows(registration, "COHORT", "enrollment", sourceTables);
        var cohortFindings = rows(registration, "COHORT", "observations", sourceTables);
        var closure = single(rows(registration, "COHORT", "closure", sourceTables));
        var reviews = rows(registration, "CLINICIAN", "reviews", sourceTables);
        var basisAssertion = single(rows(registration, "GENE_DISEASE_BASIS", "assertion", sourceTables));
        var basisEvidence = rows(registration, "GENE_DISEASE_BASIS", "evidence", sourceTables);
        var reports = rows(registration, "DIAGNOSTIC_REPORTS", "reports", sourceTables);
        var reportVariants = rows(registration, "DIAGNOSTIC_REPORTS", "variants", sourceTables);
        var reportLinks = JSON.createArrayNode();
        var criteriaDirections = criteriaDirections();
        validateReportRecords(registration, reports, reportVariants);
        data.set("originalTableReplays", sourceTables);
        data.set("diagnosticReportLinks", reportLinks);
        need(context != null && "evidence-context-v3".equals(context.path("schemaVersion").asText()), "PP4_NATIVE_CURRENT_CONTEXT_REQUIRED");
        try { new RuntimeEvidenceV2Adapter(JSON).validateVersionedContext(context); }
        catch (IllegalArgumentException invalid) { throw new IOException("PP4_NATIVE_CONTEXT_INVALID", invalid); }
        need(registration.inputs().identity().path("dataClass").equals(context.path("dataClass")), "PP4_NATIVE_DATA_CLASS_MISMATCH");
        var allele = context.at("/variant/evidenceQueryAllele");
        need(c.get("hgnc_id").equals(context.at("/gene/hgncId").asText()) && c.get("gene_symbol").equals(context.at("/gene/symbol").asText())
                && c.get("mondo_id").equals(context.at("/disease/mondoId").asText()) && c.get("moi").equals(context.path("moi").asText()),"PP4_NATIVE_CASE_CONTEXT_MISMATCH");
        if(identity==null) need(c.get("person_id").equals(context.at("/patientContext/patientContextId").asText())
                &&c.get("family_id").equals(context.at("/patientContext/familyContextId").asText()),"PP4_NATIVE_CASE_CONTEXT_MISMATCH");
        else { need(identity.matches(registration,context,c),"PP4_NATIVE_CASE_IDENTITY_PROOF_MISMATCH");data.set("sourceIdentityBridge",identity.snapshot()); }
        need("AUTOSOMAL_DOMINANT".equals(c.get("moi")) && "AFFECTED".equals(c.get("affected_status"))
                && "PROBAND".equals(context.at("/patientContext/role").asText()) && "AFFECTED".equals(context.at("/patientContext/affectedStatus").asText())
                && "GRCh38".equals(c.get("assembly")) && c.get("contig").matches("(?:[1-9]|1[0-9]|2[0-2])")
                && c.get("reference").matches("[ACGT]") && c.get("alternate").matches("[ACGT]")
                && !c.get("reference").equals(c.get("alternate")), "PP4_NATIVE_METHOD_SCOPE_UNAVAILABLE");
        need(c.get("assembly").equals(allele.path("assembly").asText()) && c.get("contig").equals(allele.path("contig").asText())
                && integer(c, "position", 1, Integer.MAX_VALUE) == allele.path("position").asLong()
                && c.get("reference").equals(allele.path("reference").asText()) && c.get("alternate").equals(allele.path("alternate").asText()),
                "PP4_NATIVE_RAW_ALLELE_CONTEXT_MISMATCH");
        String sourceSex = c.get("sex"), contextSex = context.at("/patientContext/sex").asText();
        need(Set.of("FEMALE", "MALE", "UNKNOWN").contains(sourceSex) && Set.of("FEMALE", "MALE", "UNKNOWN").contains(contextSex), "PP4_NATIVE_SEX_VALUE_INVALID");
        need("UNKNOWN".equals(sourceSex) || "UNKNOWN".equals(contextSex) || sourceSex.equals(contextSex), "PP4_NATIVE_SEX_CONTEXT_CONFLICT");
        for (String key : List.of("case_id", "occurrence_id", "observation_id", "person_id", "family_id", "sample_id", "method", "test_version", "population_id")) nonempty(c, key);
        need(Set.of("0/1", "1/0", "0|1", "1|0").contains(c.get("genotype")), "PP4_NATIVE_TARGET_HET_REQUIRED");
        integer(c, "depth", 0, Integer.MAX_VALUE); integer(c, "genotype_quality", 0, Integer.MAX_VALUE);
        captured(registration, "CLINICAL", date(c, "observed_at"));
        int age = integer(c, "age_years", 0, 130), minimum = integer(d, "minimum_age_years", 0, 130), maximum = integer(d, "maximum_age_years", 0, 130);
        need(minimum <= maximum && age >= minimum && age <= maximum, "PP4_NATIVE_AGE_SCOPE_MISMATCH");
        for (String key : List.of("hgnc_id", "mondo_id", "moi")) need(c.get(key).equals(d.get(key)), "PP4_NATIVE_DEFINITION_SCOPE_MISMATCH");
        for (String key : List.of("definition_id", "version", "mechanism", "subtype", "basis_id", "basis_method", "basis_record_id")) nonempty(d, key);
        captured(registration, "DEFINITION", date(d, "basis_date"));
        captured(registration, "DEFINITION", date(d, "specified_at"));
        String targetKey = alleleKey(c);
        for(var record:basisEvidence) need(!context.at("/patientContext/patientContextId").asText().equals(record.get("person_id"))
                &&!context.at("/patientContext/familyContextId").asText().equals(record.get("family_id")),"PP4_NATIVE_GENE_DISEASE_CURRENT_CASE_OR_DUPLICATE");
        geneDiseaseBasis(registration, c, d, basisAssertion, basisEvidence, targetKey, reports, reportVariants, criteriaDirections, reportLinks, sourceTables);
        var wanted = new LinkedHashMap<String, String>();
        need(!requirements.isEmpty(), "PP4_NATIVE_DEFINITION_REQUIREMENTS_INVALID");
        for (var requirement : requirements) {
            nonempty(requirement, "feature_id"); presence(requirement.get("presence"));
            need(wanted.putIfAbsent(requirement.get("feature_id"), requirement.get("presence")) == null, "PP4_NATIVE_REQUIREMENT_DUPLICATE");
        }
        findings(clinical, c.get("case_id"), c.get("observation_id"), wanted, date(c, "observed_at"), false);
        need(candidates.size() == 1, "PP4_NATIVE_SOLE_TARGET_ROSTER_REQUIRED");
        var target = candidates.get(0);
        need(target.get("case_id").equals(c.get("case_id")) && target.get("hgnc_id").equals(c.get("hgnc_id"))
                && alleleKey(target).equals(targetKey), "PP4_NATIVE_CANDIDATE_ROSTER_MISMATCH"); nonempty(target, "candidate_id");
        var expectedReviews = Map.of("NONMOSAIC", "NONMOSAIC", "ALTERNATIVE_DIAGNOSIS", "EXCLUDED",
                "PHENOCOPY", "EXCLUDED", "LOCUS_COVERAGE", "COMPLETE_CODING_AND_SPLICE_SOLE_TARGET");
        var reviewKinds = new HashSet<String>();
        for (var review : reviews) {
            need(c.get("case_id").equals(review.get("case_id")) && c.get("person_id").equals(review.get("person_id"))
                    && c.get("observation_id").equals(review.get("observation_id")) && expectedReviews.containsKey(review.get("kind"))
                    && reviewKinds.add(review.get("kind")), "PP4_NATIVE_CLINICIAN_SCOPE_INVALID");
            for (String key : List.of("method", "basis_record_id", "basis")) nonempty(review, key);
            need(!date(review, "reviewed_at").isBefore(date(c, "observed_at")), "PP4_NATIVE_CLINICIAN_OBSERVATION_MISMATCH");
            captured(registration, "CLINICIAN", date(review, "reviewed_at"));
            need(expectedReviews.get(review.get("kind")).equals(review.get("result")), "PP4_NATIVE_CLINICIAN_EXCLUSION_NOT_CLEARED");
        }
        need(reviewKinds.equals(expectedReviews.keySet()), "PP4_NATIVE_CLINICIAN_REVIEW_INCOMPLETE");
        for (String key : List.of("method", "test_version", "population_id")) need(c.get(key).equals(closure.get(key)), "PP4_NATIVE_COHORT_COMPARABILITY_MISMATCH");
        need(d.get("definition_id").equals(closure.get("definition_id")) && d.get("version").equals(closure.get("definition_version"))
                && "ALL_PHENOTYPE_FIRST_TESTED_RECORDS".equals(closure.get("export_method")), "PP4_NATIVE_COHORT_EXPORT_SCOPE_INVALID");
        for (String key : List.of("cohort_id", "cohort_version", "export_id")) nonempty(closure, key);
        captured(registration, "COHORT", date(closure, "export_completed_at"));
        Set<String> closed = ids(closure.get("eligible_case_ids"), false), cases = new HashSet<>(), people = new HashSet<>(), families = new HashSet<>();
        need(!enrolled.isEmpty(), "PP4_NATIVE_COHORT_SIZE_INVALID");
        var seenObservations = new HashSet<String>(); int positives = 0;
        for (var row : enrolled) {
            need(cases.add(row.get("case_id")) && people.add(row.get("person_id")) && families.add(row.get("family_id"))
                    && !c.get("case_id").equals(row.get("case_id")) && !c.get("person_id").equals(row.get("person_id"))
                    && !c.get("family_id").equals(row.get("family_id"))
                    && !context.at("/patientContext/patientContextId").asText().equals(row.get("person_id"))
                    && !context.at("/patientContext/familyContextId").asText().equals(row.get("family_id")), "PP4_NATIVE_CURRENT_CASE_FAMILY_OR_DUPLICATE_IN_COHORT");
            for (String key : List.of("case_id", "person_id", "family_id", "observation_id")) nonempty(row, key);
            need(row.get("cohort_id").equals(closure.get("cohort_id")), "PP4_NATIVE_COHORT_ID_MISMATCH");
            for (String key : List.of("method", "test_version", "population_id")) need(row.get(key).equals(c.get(key)), "PP4_NATIVE_COHORT_COMPARABILITY_MISMATCH");
            int cohortAge = integer(row, "age_years", 0, 130);
            need(Pp4ProductionMethodRegistration.text(row.get("tested_at")) && !Set.of("UNKNOWN", "PENDING", "NOT_PERFORMED").contains(row.get("tested_at")), "PP4_NATIVE_TESTED_RECORD_UNESTABLISHED");
            Instant eligibility = date(row, "eligibility_at"), tested = date(row, "tested_at"), exported = date(closure, "export_completed_at");
            need(cohortAge >= minimum && cohortAge <= maximum && !eligibility.isAfter(tested) && !tested.isAfter(exported), "PP4_NATIVE_COHORT_CHRONOLOGY_OR_AGE_INVALID");
            need(!date(d, "specified_at").isAfter(eligibility), "PP4_NATIVE_COHORT_DEFINITION_NOT_PRESPECIFIED");
            captured(registration, "COHORT", eligibility); captured(registration, "COHORT", tested);
            if (!row.get("diagnosis_at").isEmpty()) {
                Instant diagnosed = date(row, "diagnosis_at");
                need((!"TRUE".equals(row.get("diagnosed_in_gene")) || !diagnosed.isBefore(tested)) && !diagnosed.isAfter(exported), "PP4_NATIVE_COHORT_CHRONOLOGY_OR_AGE_INVALID");
                captured(registration, "COHORT", diagnosed);
            }
            var sourceFindings = cohortFindings.stream().filter(f -> row.get("case_id").equals(f.get("case_id"))).toList();
            findings(sourceFindings, row.get("case_id"), row.get("observation_id"), wanted, eligibility, true);
            sourceFindings.forEach(f -> seenObservations.add(f.get("case_id") + "\0" + f.get("feature_id")));
            need(Set.of("TRUE", "FALSE").contains(row.get("diagnosed_in_gene")), "PP4_NATIVE_DIAGNOSIS_OUTCOME_UNKNOWN");
            if (row.get("diagnosed_in_gene").equals("TRUE")) {
                need(row.get("diagnostic_hgnc_id").equals(c.get("hgnc_id")) && row.get("diagnostic_mondo_id").equals(c.get("mondo_id")), "PP4_NATIVE_DIAGNOSIS_SCOPE_INVALID");
                for (String key : List.of("diagnosis_basis_method", "diagnosis_basis_record_id", "diagnosis_at")) nonempty(row, key);
                Set<String> variants = ids(row.get("diagnosis_variant_keys"), false), criteria = ids(row.get("diagnosis_basis_criteria"), false);
                need(!variants.contains(targetKey) && !criteria.contains("ACMG_AMP_2015:PP4") && !criteria.contains("ACMG_AMP_2015:PP1"), "PP4_NATIVE_CIRCULAR_DIAGNOSIS_BASIS");
                independentCriteria(criteria, criteriaDirections);
                resolveReport(row.get("diagnosis_basis_record_id"), row.get("case_id"), row.get("person_id"), row.get("family_id"),
                        c.get("hgnc_id"), c.get("mondo_id"), c.get("moi"), d.get("mechanism"), row.get("diagnosis_basis_method"),
                        date(row, "diagnosis_at"), variants, criteria, row.get("method"), row.get("test_version"), criteriaDirections, reports, reportVariants, reportLinks, sourceTables);
                positives++;
            }
            // Known tested negatives remain in the denominator, including an unresolved
            // diagnosis or a diagnosis in another gene. UNKNOWN and untested are not FALSE.
        }
        need(cases.equals(closed) && seenObservations.size() == cohortFindings.size(), "PP4_NATIVE_COHORT_ROSTER_INCOMPLETE_OR_EXTRA");
        data.set("case", JSON.valueToTree(c)); data.set("definition", JSON.valueToTree(d)); data.set("requirements", JSON.valueToTree(requirements));
        data.set("geneDiseaseBasisAssertion", JSON.valueToTree(basisAssertion)); data.set("geneDiseaseBasisEvidence", JSON.valueToTree(basisEvidence));
        data.set("observations", JSON.valueToTree(clinical)); data.set("candidates", JSON.valueToTree(candidates)); data.set("reviews", JSON.valueToTree(reviews));
        data.set("enrollment", JSON.valueToTree(enrolled)); data.set("cohortObservations", JSON.valueToTree(cohortFindings)); data.set("closure", JSON.valueToTree(closure));
        data.put("numerator", positives).put("denominator", enrolled.size()); data.set("registeredInputs", registration.inputs().identity());
        return data;
    }


    private static void geneDiseaseBasis(Pp4ProductionMethodRegistration registration, Map<String, String> c, Map<String, String> d,
            Map<String, String> assertion, List<Map<String, String>> evidence, String targetKey,
            List<Map<String, String>> reports, List<Map<String, String>> reportVariants, Map<String, String> directions,
            com.fasterxml.jackson.databind.node.ArrayNode links, ObjectNode sourceTables) throws IOException {
        for (String key : List.of("basis_id", "hgnc_id", "mondo_id", "moi", "mechanism"))
            need(d.get(key).equals(assertion.get(key)), "PP4_NATIVE_GENE_DISEASE_ASSERTION_SCOPE_MISMATCH");
        need(d.get("basis_record_id").equals(assertion.get("record_id")) && d.get("basis_method").equals(assertion.get("curation_method"))
                && date(d, "basis_date").equals(date(assertion, "curated_at"))
                && "ESTABLISHED".equals(assertion.get("validity_assertion")), "PP4_NATIVE_GENE_DISEASE_ASSERTION_UNESTABLISHED");
        for (String key : List.of("curator_id", "curator_affiliation", "curation_method", "record_id")) nonempty(assertion, key);
        captured(registration, "GENE_DISEASE_BASIS", date(assertion, "curated_at"));
        need(!evidence.isEmpty(), "PP4_NATIVE_GENE_DISEASE_SUPPORT_REQUIRED");
        Set<String> closed = ids(assertion.get("evidence_record_ids"), false), records = new HashSet<>(), variants = new HashSet<>();
        for (var record : evidence) {
            for (String key : List.of("record_id", "case_id", "person_id", "family_id", "diagnosis_method")) nonempty(record, key);
            need(records.add(record.get("record_id")) && !record.get("record_id").equals(assertion.get("record_id"))
                    && !c.get("case_id").equals(record.get("case_id")) && !c.get("person_id").equals(record.get("person_id"))
                    && !c.get("family_id").equals(record.get("family_id")), "PP4_NATIVE_GENE_DISEASE_CURRENT_CASE_OR_DUPLICATE");
            for (String key : List.of("hgnc_id", "mondo_id", "moi", "mechanism"))
                need(assertion.get(key).equals(record.get(key)), "PP4_NATIVE_GENE_DISEASE_SUPPORT_SCOPE_MISMATCH");
            need("GRCh38".equals(record.get("assembly")) && record.get("contig").matches("(?:[1-9]|1[0-9]|2[0-2])")
                    && record.get("reference").matches("[ACGT]") && record.get("alternate").matches("[ACGT]")
                    && !record.get("reference").equals(record.get("alternate")), "PP4_NATIVE_GENE_DISEASE_SUPPORT_ALLELE_UNRESOLVED");
            integer(record, "position", 1, Integer.MAX_VALUE); String key = alleleKey(record);
            Set<String> criteria = ids(record.get("diagnosis_basis_criteria"), false);
            need(!targetKey.equals(key) && !criteria.contains("ACMG_AMP_2015:PP1") && !criteria.contains("ACMG_AMP_2015:PP4"), "PP4_NATIVE_GENE_DISEASE_CIRCULAR_BASIS");
            independentCriteria(criteria, directions);
            need(!date(record, "diagnosis_at").isAfter(date(assertion, "curated_at")), "PP4_NATIVE_GENE_DISEASE_CHRONOLOGY_INVALID");
            captured(registration, "GENE_DISEASE_BASIS", date(record, "diagnosis_at"));
            nonempty(record, "diagnosis_basis_record_id");
            resolveReport(record.get("diagnosis_basis_record_id"), record.get("case_id"), record.get("person_id"), record.get("family_id"),
                    record.get("hgnc_id"), record.get("mondo_id"), record.get("moi"), record.get("mechanism"), record.get("diagnosis_method"),
                    date(record, "diagnosis_at"), Set.of(key), criteria, "", "", directions, reports, reportVariants, links, sourceTables);
            variants.add(key);
        }
        need(closed.equals(records) && variants.equals(ids(d.get("basis_variant_keys"), false)), "PP4_NATIVE_GENE_DISEASE_SUPPORT_ROSTER_INCOMPLETE");
    }

    private static void captured(Pp4ProductionMethodRegistration registration, String role, Instant event) throws IOException {
        var original = registration.inputs().artifact(role);
        need(original != null, "PP4_NATIVE_ORIGINAL_REQUIRED");
        if (original == null) throw new IOException("PP4_NATIVE_ORIGINAL_REQUIRED");
        need(!event.isAfter(Instant.parse(original.acquiredAt())), "PP4_NATIVE_SOURCE_ACQUISITION_CUTOFF_MISMATCH");
    }

    /** Vocabulary/direction only from the existing pinned registry; no classification is calculated. */
    private static Map<String, String> criteriaDirections() throws IOException {
        try (var stream = Pp4NativeMaterialVerifier.class.getResourceAsStream(Pp4PhenotypeCriterionEvaluator.REGISTRY)) {
            if (stream == null) throw new IOException("PP4_NATIVE_DIAGNOSIS_CRITERIA_REGISTRY_UNAVAILABLE");
            byte[] bytes = stream.readAllBytes();
            need("2d0f02bc9cd278577db9bf81f8dbd1898a8600c754a18fbf1e2a260ff67a6297".equals(Pp4ProductionMethodRegistration.rawSha(bytes)), "PP4_NATIVE_DIAGNOSIS_CRITERIA_REGISTRY_UNAVAILABLE");
            var root = JSON.readTree(bytes); need(root != null, "PP4_NATIVE_DIAGNOSIS_CRITERIA_REGISTRY_UNAVAILABLE");
            if (root == null) throw new IOException("PP4_NATIVE_DIAGNOSIS_CRITERIA_REGISTRY_UNAVAILABLE");
            var result = new LinkedHashMap<String, String>();
            for (var criterion : root.path("criteria")) result.put(criterion.path("criterionId").asText(), criterion.path("direction").asText());
            return Map.copyOf(result);
        }
    }
    private static void independentCriteria(Set<String> criteria, Map<String, String> directions) throws IOException {
        need(!criteria.isEmpty() && criteria.stream().allMatch(directions::containsKey), "PP4_NATIVE_DIAGNOSIS_CRITERION_UNKNOWN");
        need(!criteria.contains("ACMG_AMP_2015:PP1") && !criteria.contains("ACMG_AMP_2015:PP4"), "PP4_NATIVE_DIAGNOSIS_CIRCULAR_BASIS");
        // This finite native profile does not resolve mixed-direction classification
        // evidence. The source P/LP is retained, not recomputed or declared medically wrong.
        boolean benign = criteria.stream().anyMatch(id -> "BENIGN".equals(directions.get(id)));
        boolean pathogenic = criteria.stream().anyMatch(id -> "PATHOGENIC".equals(directions.get(id)));
        need(!(benign && pathogenic), "PP4_NATIVE_DIAGNOSIS_MIXED_BASIS_UNSUPPORTED");
        need(!benign, "PP4_NATIVE_DIAGNOSIS_BASIS_REQUIRES_CONFLICT_REVIEW");
    }
    private static void validateReportRecords(Pp4ProductionMethodRegistration registration,
            List<Map<String, String>> reports, List<Map<String, String>> variants) throws IOException {
        var records = new HashSet<String>(); var reportIds = new HashSet<String>(); var variantIds = new HashSet<String>();
        for (var report : reports) {
            nonempty(report, "record_id"); nonempty(report, "report_id");
            need(records.add(report.get("record_id")) && reportIds.add(report.get("report_id")), "PP4_NATIVE_DIAGNOSIS_REPORT_RECORD_AMBIGUOUS");
            captured(registration, "DIAGNOSTIC_REPORTS", date(report, "issued_at"));
        }
        for (var variant : variants) {
            nonempty(variant, "record_id"); nonempty(variant, "report_id");
            need(variantIds.add(variant.get("record_id")), "PP4_NATIVE_DIAGNOSIS_REPORT_RECORD_AMBIGUOUS");
            captured(registration, "DIAGNOSTIC_REPORTS", date(variant, "conclusion_date"));
        }
    }

    /** Adopt an external original diagnostic conclusion only after every referenced native
     * record resolves. Text P/LP is not recalculated here and cannot grant approval by itself.
     */
    private static void resolveReport(String recordId, String caseId, String person, String family,
            String hgnc, String mondo, String moi, String mechanism, String method, Instant diagnosed,
            Set<String> declaredVariants, Set<String> declaredCriteria, String assayMethod, String assayVersion, Map<String, String> directions,
            List<Map<String, String>> reports, List<Map<String, String>> variants,
            com.fasterxml.jackson.databind.node.ArrayNode links, ObjectNode sourceTables) throws IOException {
        var found = reports.stream().filter(r -> recordId.equals(r.get("record_id"))).toList();
        need(found.size() == 1, "PP4_NATIVE_DIAGNOSIS_REPORT_RECORD_UNRESOLVED"); var report = found.get(0);
        need(caseId.equals(report.get("case_id")) && person.equals(report.get("person_id")) && family.equals(report.get("family_id")), "PP4_NATIVE_DIAGNOSIS_REPORT_CASE_MISMATCH");
        need(hgnc.equals(report.get("hgnc_id")) && mondo.equals(report.get("mondo_id")) && moi.equals(report.get("moi"))
                && mechanism.equals(report.get("mechanism")) && "AUTOSOMAL_DOMINANT".equals(moi), "PP4_NATIVE_DIAGNOSIS_REPORT_SCOPE_MISMATCH");
        for (String key : List.of("issuer_name", "issuer_method", "sample_id")) nonempty(report, key);
        need(method.equals(report.get("issuer_method")) && diagnosed.equals(date(report, "issued_at")), "PP4_NATIVE_DIAGNOSIS_REPORT_BASIS_MISMATCH");
        need("MOLECULAR_DIAGNOSIS".equals(report.get("conclusion")), "PP4_NATIVE_DIAGNOSIS_REPORT_CONCLUSION_UNSUPPORTED");
        Set<String> ids = ids(report.get("variant_record_ids"), false), keys = ids(report.get("native_variant_keys"), false);
        var referred = variants.stream().filter(r -> ids.contains(r.get("record_id")) || report.get("report_id").equals(r.get("report_id"))).toList();
        Set<String> actualIds = new HashSet<>(), actualKeys = new HashSet<>(), criteria = new HashSet<>(), samples = new HashSet<>();
        for (var variant : referred) {
            need(report.get("report_id").equals(variant.get("report_id")), "PP4_NATIVE_DIAGNOSIS_REPORT_VARIANTS_INCOMPLETE");
            actualIds.add(variant.get("record_id"));
            need("GRCh38".equals(variant.get("assembly")) && variant.get("contig").matches("(?:[1-9]|1[0-9]|2[0-2])")
                    && variant.get("reference").matches("[ACGT]") && variant.get("alternate").matches("[ACGT]")
                    && !variant.get("reference").equals(variant.get("alternate")), "PP4_NATIVE_DIAGNOSIS_REPORT_VARIANT_UNSUPPORTED");
            integer(variant, "position", 1, Integer.MAX_VALUE);
            need(actualKeys.add(alleleKey(variant)), "PP4_NATIVE_DIAGNOSIS_REPORT_VARIANTS_INCOMPLETE");
            need(report.get("sample_id").equals(variant.get("sample_id")) && Set.of("0/1", "1/0", "0|1", "1|0").contains(variant.get("genotype")), "PP4_NATIVE_DIAGNOSIS_REPORT_SAMPLE_OR_GENOTYPE_UNSUPPORTED");
            samples.add(variant.get("sample_id")); nonempty(variant, "method"); nonempty(variant, "test_version");
            if (!assayMethod.isEmpty()) need(assayMethod.equals(variant.get("method")) && assayVersion.equals(variant.get("test_version")), "PP4_NATIVE_COHORT_COMPARABILITY_MISMATCH");
            need(!date(variant, "conclusion_date").isAfter(diagnosed), "PP4_NATIVE_DIAGNOSIS_REPORT_BASIS_MISMATCH");
            need(Set.of("PATHOGENIC", "LIKELY_PATHOGENIC").contains(variant.get("classification")), "PP4_NATIVE_DIAGNOSIS_REPORT_CONCLUSION_UNSUPPORTED");
            criteria.addAll(ids(variant.get("criteria"), false));
        }
        need(actualIds.equals(ids) && actualKeys.equals(keys), "PP4_NATIVE_DIAGNOSIS_REPORT_VARIANTS_INCOMPLETE");
        need(ids.size() == 1 && samples.size() == 1, "PP4_NATIVE_DIAGNOSIS_REPORT_COMBINATION_UNSUPPORTED");
        independentCriteria(criteria, directions);
        need(actualKeys.equals(declaredVariants) && criteria.equals(declaredCriteria), "PP4_NATIVE_DIAGNOSIS_REPORT_BASIS_MISMATCH");
        var link = links.addObject().put("declaredRecordId", recordId).put("externalDiagnosticConclusion", report.get("conclusion"))
                .put("classificationOrigin", "ORIGINAL_REGISTERED_DIAGNOSTIC_REPORT_NOT_RECLASSIFIED_BY_VARVIS");
        link.set("report", rowReference(sourceTables, "reports", recordId));
        var variantLinks = link.putArray("variants");
        for (var variant : referred) {
            var reference = rowReference(sourceTables, "variants", variant.get("record_id"));
            reference.put("originalClassification", variant.get("classification")).put("nativeAlleleKey", alleleKey(variant));
            variantLinks.add(reference);
        }
    }

    private static ObjectNode rowReference(ObjectNode tables, String key, String recordId) throws IOException {
        var replay = tables.path("DIAGNOSTIC_REPORTS:" + key); var found = JSON.createArrayNode();
        for (var row : replay.at("/table/bodyRows")) if (row.path("cells").size() > 0 && row.at("/cells/0/text").asText().trim().equals(recordId)) found.add(row);
        need(found.size() == 1, "PP4_NATIVE_DIAGNOSIS_REPORT_RECORD_UNRESOLVED"); var row = found.get(0);
        var reference = JSON.createObjectNode().put("sourceId", replay.path("sourceId").asText()).put("rawSha256", replay.path("rawSha256").asText())
                .put("tableId", replay.at("/table/tableId").asText()).put("tableLocator", replay.at("/table/tableLocator").asText())
                .put("sourceRowNumber", row.path("sourceRowNumber").asInt()).put("bodyRowNumber", row.path("sectionRowNumber").asInt());
        reference.put("recordId", recordId).put("recordCellLocator", row.at("/cells/0/locator").asText());
        int conclusionColumn = HEADERS.get(key).indexOf(key.equals("reports") ? "conclusion" : "classification");
        reference.put("conclusionCellNumber", conclusionColumn + 1)
                .put("conclusionCellLocator", row.path("cells").get(conclusionColumn).path("locator").asText());
        return reference;
    }

    private static List<Map<String, String>> rows(Pp4ProductionMethodRegistration registration, String role, String key, ObjectNode sourceTables) throws IOException {
        var artifact = registration.inputs().artifact(role);
        need(artifact != null && artifact.tableIds().containsKey(key), "PP4_NATIVE_" + role + "_" + key.toUpperCase(java.util.Locale.ROOT) + "_ORIGINAL_REQUIRED");
        if (artifact == null) throw new IOException("PP4_NATIVE_ORIGINAL_REQUIRED");
        var table = Pp4PrimaryTableReplay.replay(artifact.raw(), artifact.expectedRawSha256(), artifact.pmcid(), artifact.tableIds().get(key));
        var replay = sourceTables.putObject(role + ":" + key).put("sourceId", artifact.sourceId()).put("rawSha256", artifact.expectedRawSha256());
        replay.set("table", JSON.valueToTree(table));
        var expected = HEADERS.get(key);
        need(table.headerRows().size() == 1 && table.footerRows().isEmpty() && table.headerRows().get(0).cells().size() == expected.size(), "PP4_NATIVE_PROFILE_HEADERS_UNSUPPORTED");
        for (int i = 0; i < expected.size(); i++) {
            var cell = table.headerRows().get(0).cells().get(i);
            need(cell.rowSpan() == 1 && cell.columnSpan() == 1 && cell.text().equals(expected.get(i)), "PP4_NATIVE_PROFILE_HEADERS_UNSUPPORTED");
        }
        need(table.bodyRows().size() <= 256, "PP4_NATIVE_PROFILE_ROW_LIMIT");
        var result = new ArrayList<Map<String, String>>();
        for (var row : table.bodyRows()) {
            need(row.cells().size() == expected.size(), "PP4_NATIVE_PROFILE_ROW_WIDTH_INVALID");
            var values = new LinkedHashMap<String, String>();
            for (int i = 0; i < expected.size(); i++) {
                var cell = row.cells().get(i);
                need(cell.rowSpan() == 1 && cell.columnSpan() == 1 && cell.references().isEmpty()
                        && cell.text().length() <= 4096 && cell.text().codePoints().noneMatch(Character::isISOControl), "PP4_NATIVE_CELL_QUALIFIER_OR_VALUE_UNRESOLVED");
                values.put(expected.get(i), cell.text().trim());
            }
            result.add(Map.copyOf(values));
        }
        return List.copyOf(result);
    }
    private static void findings(List<Map<String, String>> rows, String caseId, String observationId, Map<String, String> wanted, Instant latest, boolean eligible) throws IOException {
        var seen = new HashSet<String>();
        for (var row : rows) {
            need(caseId.equals(row.get("case_id")) && observationId.equals(row.get("observation_id")) && wanted.containsKey(row.get("feature_id"))
                    && seen.add(row.get("feature_id")), "PP4_NATIVE_OBSERVATION_SCOPE_OR_DUPLICATE");
            presence(row.get("presence")); nonempty(row, "examination_method");
            need(date(row, "observed_at").equals(latest), "PP4_NATIVE_OBSERVATION_DATE_MISMATCH");
            if (eligible) need(wanted.get(row.get("feature_id")).equals(row.get("presence")), "PP4_NATIVE_COHORT_PHENOTYPE_INELIGIBLE");
        }
        need(seen.equals(wanted.keySet()), "PP4_NATIVE_EXAMINATION_ROSTER_INCOMPLETE");
    }
    private static Map<String, String> single(List<Map<String, String>> rows) throws IOException { need(rows.size() == 1, "PP4_NATIVE_SINGLE_RECORD_REQUIRED"); return rows.get(0); }
    private static Set<String> ids(String value, boolean empty) throws IOException {
        need(value != null, "PP4_NATIVE_LIST_REQUIRED"); if (value == null) throw new IOException("PP4_NATIVE_LIST_REQUIRED");
        if (value.isEmpty() && empty) return Set.of();
        var out = new HashSet<String>();
        for (String id : value.split("\\|", -1)) need(Pp4ProductionMethodRegistration.text(id) && out.add(id), "PP4_NATIVE_LIST_DUPLICATE_OR_INVALID");
        need(out.size() <= 256, "PP4_NATIVE_PROFILE_ENGINEERING_CAPACITY_UNSUPPORTED"); return Set.copyOf(out);
    }
    private static String alleleKey(Map<String, String> values) { return String.join(":", values.get("assembly"), values.get("contig"), values.get("position"), values.get("reference"), values.get("alternate")); }
    private static int integer(Map<String, String> row, String key, int minimum, int maximum) throws IOException {
        String value = row.get(key); need(value != null && value.matches("(?:0|[1-9][0-9]*)"), "PP4_NATIVE_INTEGER_REQUIRED");
        try { int parsed = Integer.parseInt(value); need(parsed >= minimum && parsed <= maximum, "PP4_NATIVE_INTEGER_RANGE"); return parsed; }
        catch (NumberFormatException invalid) { throw new IOException("PP4_NATIVE_INTEGER_RANGE", invalid); }
    }
    private static Instant date(Map<String, String> row, String key) throws IOException {
        try { return Instant.parse(row.get(key)); } catch (RuntimeException invalid) { throw new IOException("PP4_NATIVE_DATE_REQUIRED", invalid); }
    }
    private static void nonempty(Map<String, String> row, String key) throws IOException { need(Pp4ProductionMethodRegistration.text(row.get(key)) && !Set.of("UNKNOWN", "PENDING", "NOT_PERFORMED").contains(row.get(key)), "PP4_NATIVE_" + key.toUpperCase(java.util.Locale.ROOT) + "_REQUIRED"); }
    private static void presence(String value) throws IOException { need(value != null && Set.of("PRESENT", "ABSENT").contains(value), "PP4_NATIVE_EXAMINATION_UNKNOWN"); }
    private static void need(boolean condition, String reason) throws IOException { Pp4ProductionMethodRegistration.need(condition, reason); }
}
