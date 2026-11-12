package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.lang.Nullable;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Fixed-source JOINT count bounds, not a disease-policy approval.
 * There is deliberately no JSON material constructor or arbitrary cohort summation.
 * Native NON_PAR XY lower bounds and ALL-raw upper bounds have distinct source scopes.
 * Original provider/store recovery is the
 * caller's trust boundary; a checksum by itself is never a source-qualification claim.
 */
final class Ba1NativeIndividualCountSupply {
    static final String VERSION = "2.0.0";
    static final String SCOPE = "GNOMAD_JOINT_4_1_SOURCE_BOUND_INDIVIDUAL_COUNT_BOUNDS";
    private static final String PROFILE_SHA = "626320cc24bb480adb4d34c97646326c3ca358c5c68b8282eb7edf177731a0a3";
    private static final Set<String> EXTRA_FILTERS = Set.of("gnomad.joint.exomes.filtersPresence", "gnomad.joint.genomes.filtersPresence");
    private Ba1NativeIndividualCountSupply() { }

    static ObjectNode identity(ObjectMapper mapper) {
        var value = mapper.createObjectNode().put("schemaVersion", "ba1-native-individual-count-supply-v1")
                .put("version", VERSION).put("qualificationScope", SCOPE)
                .put("populationInputProfileSha256", PROFILE_SHA)
                .put("jointHeaderSha256", GnomadSexStratifiedObservation.JOINT_HEADER_SHA256)
                .put("countCompleteness", "UNVERIFIED_OR_PARTIAL").put("diseasePolicyApprovalGranted", false);
        var sources = value.putArray("sources");
        source(sources, "https://gnomad.broadinstitute.org/news/2024-04-gnomad-v4-1/",
                "0cc05a2d50c3c12641682565b0c844422bbc698a1a3a4469ad7e0f0bc0cb650e", "Sample set composition: exomes and genomes non-overlapping sample sets");
        source(sources, "https://gnomad.broadinstitute.org/news/2023-11-gnomad-v4-0/",
                "ec6c697ab318ac29cdf18103313f0cef841d5b161c1e701f29edaf0bcb95627b", "Relatedness inference: across exomes/genomes duplicate filtering and maximal independent set");
        source(sources, "https://raw.githubusercontent.com/broadinstitute/gnomad_qc/16fce5e8d4b1742153efe71166f2fd32eeac8dde/gnomad_qc/v4/create_release/create_combined_faf_release_ht.py",
                "b5a8bf3fd2d3cf1d7a1e6a9dbc984eac74734ffc43d4cc1b67ce30d6c0f7b693", "get_joint_freq_and_faf: same metadata frequency struct merge");
        source(sources, "https://raw.githubusercontent.com/broadinstitute/gnomad_qc/16fce5e8d4b1742153efe71166f2fd32eeac8dde/gnomad_qc/v4/create_release/validate_and_export_vcf.py",
                "ab17b6ba4771be37ee2eadfe754ce1070ec24c220bbdc0edf5a8d957d2a6b766", "joint_freq same index; homozygote_count -> nhomalt; _adj suffix removed");
        source(sources, "https://raw.githubusercontent.com/hail-is/hail/0.2.130/hail/src/main/scala/is/hail/expr/ir/agg/CallStatsAggregator.scala",
                "e78f7579c08cf0e1fcd01fb93093097280b9fd7f9c20ff8ff2964a6d4a513718", "_seqOp: hom count only for ploidy >1 with equal alleles; haploid ALT not counted");
        source(sources, "https://raw.githubusercontent.com/broadinstitute/gnomad-browser/db245371f1e572106bda8481ef0f9a6baac6c00d/data-pipeline/src/data_pipeline/datasets/gnomad_v4/gnomad_v4_variants.py",
                "1835918aa6f9b7f325f5b0eb9a59830be80e86cd976fe0e31245ca13c465696e", "prepare_gnomad_v4_variants_joint_frequency_helper: explicit XY-adj NON_PAR hemizygote interpretation; missing-to-zero browser defaults are not adopted");
        source(sources, "https://raw.githubusercontent.com/broadinstitute/gnomad_methods/7879adb21bad05d2ae151670efabfa6f58e394ad/gnomad/sample_qc/sex.py",
                "8f0b9add7e8e8eb715fc6407b9c1d0b992bc8341f72ae1e720333de2f5dccc46", "adjusted_sex_ploidy_expr: exact XY NON_PAR ALT haploid; heterozygotes missing; other karyotypes not inferred");
        value.put("hemizygoteLowerBoundScope", "XY_JOINT_ADJ_NON_PAR_TARGET_ALT_CALLS")
                .put("personUpperBoundScope", "ALL_JOINT_RAW_TARGET_ALT_CALLS_INCLUDING_PRE_ADJ");
        value.putArray("notGranted").add("COMPLETE_EXACT_HOMO_HEMI_COUNTS")
                .add("PATIENT_SEX_OR_PLOIDY").add("FINAL_CLASSIFICATION_OR_PUBLICATION_APPROVAL");
        return value;
    }

    private static void source(ArrayNode sources, String url, String sha, String locator) {
        sources.addObject().put("url", url).put("sha256", sha).put("locator", locator);
    }

    /** Only a trusted source consumer may call this after actual provider execution or
     * verified store recovery. The fixed profile and original RAW are reparsed here;
     * caller-submitted status, reviewed flags or qualified-count JSON have no authority.
     */
    static Material read(ObjectMapper mapper, GnomadPopulationInputProfile profile, String alleleId,
                         JsonNode carrier, String rawRecord) {
        Objects.requireNonNull(mapper);
        JsonNode original = carrier == null ? mapper.nullNode() : carrier.deepCopy();
        String raw = rawRecord == null ? "" : rawRecord;
        var binding = mapper.createObjectNode().put("profileSha256", profile == null ? "" : profile.getProfileSha256())
                .put("alleleId", alleleId == null ? "" : alleleId)
                .put("carrierSha256", canonical(mapper, original)).put("rawRecordSha256", rawSha(raw))
                .put("producedAt", original.path("producedAt").asText()).put("dataset", "JOINT").put("release", "4.1")
                .put("ancestry", "ALL").put("sex", "ALL").put("genotypeQualityScope", "ADJ")
                .put("ploidyScope", "SOURCE_DIPLOID_HOMO_AND_QUALIFIED_XY_NONPAR_HAPLOID")
                .put("countScopeCompleteness", "UNVERIFIED_OR_PARTIAL");
        binding.set("queryAllele", original.path("queryAllele").deepCopy());
        binding.set("source", original.path("source").deepCopy());
        binding.set("region", SexChromosomeRegionResolver.assess(mapper, original.path("queryAllele")));
        binding.put("supplyIdentitySha256", canonical(mapper, identity(mapper)));
        ObjectNode details = mapper.createObjectNode().put("schemaVersion", "ba1-native-individual-count-material-v1")
                .put("version", VERSION).put("qualificationScope", SCOPE).put("status", "UNRESOLVED");
        details.set("binding", binding.deepCopy()); details.set("sourceContract", identity(mapper));
        details.putArray("required").add("EXACT_TARGET_ALT_RELEASE_PROFILE_AND_RAW_REPLAY")
                .add("UNIQUE_JOINT_ADJ_DIPLOID_HOMOZYGOUS_INDIVIDUALS");
        var available = details.putArray("available");
        var missing = details.putArray("missing").add("COMPLETE_EXACT_HOMO_HEMI_COUNT_COVERAGE");
        details.putArray("notInferred").add("UNQUALIFIED_AC_XY_AS_PERSON_COUNT")
                .add("UPPER_BOUND_AS_EXACT_OR_POSITIVE_PERSON_COUNT").add("COMPLETE_NEGATIVE_FROM_PARTIAL_SUM")
                .add("PATIENT_SEX_OR_PLOIDY").add("DISEASE_POLICY_OR_GLOBAL_CLASSIFICATION_APPROVAL");
        try {
            if (profile == null) throw new IllegalArgumentException("BA1_NATIVE_COUNT_PROFILE_UNSUPPORTED");
            require(PROFILE_SHA.equals(profile.getProfileSha256()) && "JOINT".equals(profile.getDatasetType()),
                    "BA1_NATIVE_COUNT_PROFILE_UNSUPPORTED");
            require(alleleId != null && alleleId.matches("allele-sha256-[0-9a-f]{64}"), "BA1_NATIVE_COUNT_ALLELE_ID_INVALID");
            require(original instanceof ObjectNode, "BA1_NATIVE_COUNT_CARRIER_INVALID");
            require("GRCh38".equals(original.at("/queryAllele/assembly").asText()), "BA1_NATIVE_COUNT_ASSEMBLY_UNSUPPORTED");
            var definitions = GnomadSexStratifiedObservation.headerDefinitions(new String(
                    GnomadSexStratifiedObservation.loadPinnedJointHeader(), StandardCharsets.UTF_8));
            String[] definition = definitions.get("nhomalt_joint");
            require(definition != null && "A".equals(definition[0]) && "Integer".equals(definition[1])
                    && "Count of homozygous individuals in joint dataset".equals(definition[2]), "BA1_NATIVE_COUNT_HEADER_UNSUPPORTED");
            verifyReplay(mapper, profile, (ObjectNode) original, raw);
            require(original.path("producedAt").equals(original.at("/source/retrievedAt")), "BA1_NATIVE_COUNT_SOURCE_TIME_MISMATCH");
            if (!"HIT".equals(original.at("/queryOutcome/status").asText()))
                return material(mapper, binding, details, null, "BA1_NATIVE_COUNT_NO_RECORD", null);
            require("APPLICABLE".equals(original.at("/resourceApplicability/status").asText()), "BA1_NATIVE_COUNT_SOURCE_NOT_APPLICABLE");
            available.add("FIXED_JOINT_4_1_PROFILE_AND_HEADER").add("ORIGINAL_RAW_AND_MEASUREMENTS_REPLAYED")
                    .add("SOURCE_NONOVERLAPPING_SAMPLE_SETS_AND_ALL_ADJ_HOMOZYGOTE_DEFINITION");
            String calibration = profile.getProfileId() + "@" + profile.getProfileVersion();
            BigInteger hom = integer(original, "gnomad.joint.nhomalt", "individuals", calibration);
            if (hom == null) missing.add("DIPLOID_HOMOZYGOTE_INDIVIDUAL_STATISTIC");
            BigInteger ac = integer(original, "gnomad.joint.ac", "alleles", calibration);
            BigInteger an = integer(original, "gnomad.joint.an", "alleles", calibration);
            if (ac == null || an == null) throw new IllegalArgumentException("BA1_NATIVE_COUNT_AC_AN_MISSING");
            require(an.signum() > 0 && ac.compareTo(an) <= 0 && (hom == null || hom.shiftLeft(1).compareTo(ac) <= 0),
                    "BA1_NATIVE_COUNT_AC_AN_HOMOZYGOTE_INCONSISTENT");
            var bounds = Ba1NativeCountBounds.read(mapper, raw, original.path("queryAllele"), binding.path("region"), definitions, hom, ac, an);
            details.set("additionalNativeBounds", bounds.details());
            var observed = details.putObject("observed").put("metricId", "gnomad.joint.nhomalt").put("infoId", "nhomalt_joint")
                    .put("unit", "individuals").put("AC", ac.toString()).put("AN", an.toString());
            if (hom != null) {
                observed.put("homozygotes", hom.toString());
                available.add("UNIQUE_JOINT_ADJ_DIPLOID_HOMOZYGOTE_INDIVIDUAL_LOWER_BOUND");
            }
            var hemi = bounds.hemizygoteLowerBound(); var upper = bounds.personUpperBound();
            if (hemi != null) {
                observed.put("hemizygoteLowerBound", hemi.toString());
                available.add("SOURCE_QUALIFIED_XY_ADJ_NONPAR_HEMIZYGOTE_LOWER_BOUND");
            } else missing.add("SOURCE_QUALIFIED_XY_NONPAR_HEMIZYGOTE_STATISTICS");
            if (upper != null) available.add("ALL_RAW_TARGET_ALT_PERSON_UPPER_BOUND");
            else missing.add("COMPLETE_ALL_RAW_CALLSET_PERSON_UPPER_BOUND");
            var counts = hom == null && hemi == null ? null : new Ba1ConditionalFrequencyRule.IndividualCounts(hom, hemi,
                    hom != null && hemi != null ? Ba1ConditionalFrequencyRule.SumRelationship.SAME_SOURCE_DISJOINT
                            : Ba1ConditionalFrequencyRule.SumRelationship.UNRESOLVED);
            details.put("status", counts == null ? upper == null ? "UNRESOLVED" : "QUALIFIED_UPPER_BOUND"
                    : upper == null ? "QUALIFIED_LOWER_BOUND" : "QUALIFIED_BOUNDS");
            return material(mapper, binding, details, counts, upper, counts == null ? upper == null
                    ? "BA1_NATIVE_COUNT_HOMOZYGOTES_MISSING" : "BA1_NATIVE_COUNT_ALL_RAW_UPPER_BOUND_AVAILABLE"
                    : "BA1_NATIVE_COUNT_SOURCE_LOWER_BOUND_AVAILABLE", null);
        } catch (RuntimeException invalid) {
            String message = invalid.getMessage();
            String reason = message != null && message.startsWith("BA1_NATIVE_COUNT_") ? message : "BA1_NATIVE_COUNT_RAW_OR_CARRIER_INVALID";
            missing.add("SOURCE_QUALIFIED_DIPLOID_HOMOZYGOTE_INDIVIDUALS");
            return material(mapper, binding, details, null, reason, reason);
        }
    }

    /** Replay the base measurements and exactly the optional two BA1 extensions. They
     * are never ignored wholesale: duplicated, changed or fabricated presence values fail.
     * sourcePayloadSha256 has an upstream provider-specific derivation, not a RAW hash;
     * it is validated structurally and bound in full by this opaque material.
     */
    private static void verifyReplay(ObjectMapper mapper, GnomadPopulationInputProfile profile, ObjectNode original, String raw) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        adapter.validatePopulationCarrier(original, original.path("queryAllele"));
        var seen = new HashSet<String>();
        int extras = 0;
        for (JsonNode measurement : original.path("measurements")) {
            String metric = measurement.path("metricId").asText();
            require(seen.add(metric), "BA1_NATIVE_COUNT_DUPLICATE_MEASUREMENT");
            if (EXTRA_FILTERS.contains(metric)) extras++;
        }
        require(extras == 0 || extras == 2, "BA1_NATIVE_COUNT_FILTER_EXTENSION_INVALID");
        ObjectNode base = original.deepCopy();
        ArrayNode measurements = mapper.createArrayNode();
        for (JsonNode measurement : original.path("measurements"))
            if (!EXTRA_FILTERS.contains(measurement.path("metricId").asText())) measurements.add(measurement.deepCopy());
        base.set("measurements", measurements);
        var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        wiring.verifyPopulationRecord(base, base.path("queryAllele"), raw, profile);
        if (extras == 2) {
            wiring.preserveBa1SubtypeFilters(base, raw);
            require(base.path("measurements").equals(original.path("measurements")), "BA1_NATIVE_COUNT_FILTER_EXTENSION_INVALID");
        }
    }

    @Nullable private static BigInteger integer(JsonNode carrier, String metric, String unit, String calibration) {
        for (JsonNode value : carrier.path("measurements")) if (metric.equals(value.path("metricId").asText())) {
            require(unit.equals(value.path("unit").asText()) && calibration.equals(value.path("calibrationProfileId").asText()),
                    "BA1_NATIVE_COUNT_UNIT_OR_CALIBRATION_INVALID");
            String raw = value.path("rawValue").asText(), normalized = value.path("normalizedValue").asText();
            require(raw.matches("[0-9]{1,64}") && normalized.matches("[0-9]{1,64}"), "BA1_NATIVE_COUNT_INTEGER_INVALID");
            var number = new BigInteger(raw);
            require(number.equals(new BigInteger(normalized)), "BA1_NATIVE_COUNT_INTEGER_INVALID");
            return number;
        }
        return null;
    }

    private static Material material(ObjectMapper mapper, ObjectNode binding, ObjectNode details,
                                     @Nullable Ba1ConditionalFrequencyRule.IndividualCounts counts, String reason, @Nullable String issue) {
        return material(mapper, binding, details, counts, null, reason, issue);
    }

    private static Material material(ObjectMapper mapper, ObjectNode binding, ObjectNode details,
                                     @Nullable Ba1ConditionalFrequencyRule.IndividualCounts counts, @Nullable BigInteger upperBound,
                                     String reason, @Nullable String issue) {
        details.put("reasonCode", reason).put("countScopeCompleteness", "UNVERIFIED_OR_PARTIAL");
        details.put("materialId", "ba1-native-count-material-sha256-" + canonical(mapper, details));
        return new Material(binding, details, counts, upperBound, reason, issue);
    }

    static final class Material {
        private final ObjectNode binding, details;
        @Nullable private final Ba1ConditionalFrequencyRule.IndividualCounts counts;
        @Nullable private final BigInteger upperBound;
        private final String reason;
        @Nullable private final String validationIssue;
        private Material(ObjectNode binding, ObjectNode details, @Nullable Ba1ConditionalFrequencyRule.IndividualCounts counts,
                         @Nullable BigInteger upperBound, String reason, @Nullable String validationIssue) {
            this.binding = binding.deepCopy(); this.details = details.deepCopy(); this.counts = counts;
            this.upperBound = upperBound;
            this.reason = reason; this.validationIssue = validationIssue;
        }
        @Nullable String issue(ObjectMapper mapper, GnomadPopulationInputProfile profile, String alleleId, JsonNode carrier, String jointRawSha256) {
            if (validationIssue != null) return validationIssue;
            if (profile == null || !binding.path("profileSha256").asText().equals(profile.getProfileSha256())
                    || !binding.path("alleleId").asText().equals(alleleId) || carrier == null
                    || !binding.path("carrierSha256").asText().equals(canonical(mapper, carrier))
                    || !binding.path("rawRecordSha256").asText().equals(jointRawSha256)
                    || !binding.path("supplyIdentitySha256").asText().equals(canonical(mapper, identity(mapper))))
                return "BA1_NATIVE_COUNT_MATERIAL_BINDING_MISMATCH";
            return null;
        }
        @Nullable Ba1ConditionalFrequencyRule.IndividualCounts counts() { return counts; }
        @Nullable BigInteger upperBound() { return upperBound; }
        Ba1ConditionalFrequencyRule.CountScopeCompleteness completeness() {
            return Ba1ConditionalFrequencyRule.CountScopeCompleteness.UNVERIFIED_OR_PARTIAL;
        }
        String reasonCode() { return reason; }
        ObjectNode publicDetails() { return details.deepCopy(); }
    }

    private static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
    private static String canonical(ObjectMapper mapper, JsonNode value) { return new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value); }
    private static String rawSha(String raw) { return org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(raw); }
}
