package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Versioned gnomAD population input contract for E3-P0 and the isolated E3-P1A slice.
 *
 * <p>This class parses exact VCF lexemes into measurements. Criterion decisions remain in
 * the evidence-v2 evaluator; the executable profile only freezes its inputs and authorities.</p>
 */
public final class GnomadPopulationInputProfile {
    public static final String LOCAL_BA1_PROFILE_ID = "GNOMAD_BA1_LOCAL_EXOMES";
    private static final String LOCAL_BA1_SHA256 = "bce4c90293c0bd5028d5d240a9bdabea9bf4118e27adf2af245f61d98c8e7a1d";
    private final boolean localBa1;
    public static final String HNF1A_PROFILE_ID = "GNOMAD_HNF1A_DISEASE_FREQUENCY";
    public static final String HNF1A_PROFILE_SHA256 = "1280dd3ebd9f7c6b0fe25ac5cba005000f7a7c30f39b3f4d6668f82afb473a3c";
    private static final String HNF1A_REGISTRY_ID = "acmg-rule-registry-bs1-hnf1a-2026-09-10";
    private static final String HNF1A_REGISTRY_RESOURCE = "/acmg/rule-registry-v2/" + HNF1A_REGISTRY_ID + ".json";
    private static final String HNF1A_REGISTRY_SHA256 = "c0604267adb55441b71e4905d00b286936d62b865ca84239218e2b47c5434216";
    private final boolean hnf1aFrequency;

    private static final String V1_RESOURCE = "/acmg/gnomad-population-input-profile-v1.json";
    private static final String V2_RESOURCE = "/acmg/gnomad-population-input-profile-v2.json";
    private static final String PM2_RESOURCE =
            "/acmg/gnomad-population-input-profile-v2-pm2.json";
    private static final String V1_SCHEMA_VERSION = "gnomad-population-input-profile-v1";
    private static final String V2_SCHEMA_VERSION = "gnomad-population-input-profile-v2";
    private static final String V1_PROFILE_ID = "GNOMAD_POPULATION_INPUT_V1";
    private static final String V2_PROFILE_ID = "GNOMAD_POPULATION_INPUT_V2";
    private static final String PM2_PROFILE_ID = "GNOMAD_POPULATION_INPUT_V2_PM2_SUPPORTING";
    private static final String V1_PROFILE_VERSION = "1.0.0";
    private static final String V2_PROFILE_VERSION = "2.0.0";
    private static final String PM2_PROFILE_VERSION = "2.1.0";
    private static final String V2_PROFILE_SHA256 =
            "626320cc24bb480adb4d34c97646326c3ca358c5c68b8282eb7edf177731a0a3";
    private static final String PM2_PROFILE_SHA256 =
            "221386bf48b12cc3d8671697b04ce27b0b933e2ec921e5c57040b5813df69ec5";
    static final String FULL_SITES_RESOURCE_ID = "GNOMAD_EXOMES_V4_1_GRCH38_FULL_SITES";
    static final String JOINT_RESOURCE_ID = "GNOMAD_JOINT_V4_1_GRCH38_SITES";
    static final String EXOME_ALL_SITES_RESOURCE_ID =
            "GNOMAD_EXOMES_V4_1_GRCH38_ALL_SITES_AN";
    static final String GENOME_ALL_SITES_RESOURCE_ID =
            "GNOMAD_GENOMES_V4_1_GRCH38_ALL_SITES_AN";
    private static final String COMPACT_RESOURCE_ID = "GNOMAD_EXOMES_V4_1_GRCH38_COMPACT";
    private static final String BASE_REGISTRY_ID = "acmg-rule-registry-general-2026-08-31";
    private static final String BASE_REGISTRY_SCHEMA_VERSION = "acmg-rule-registry-v1";
    private static final String BASE_REGISTRY_CANONICAL_SHA256 =
            "1865ae07534d90244df4d9f77f94d824befbe88e31b6e138611f425c7e19a557";
    private static final String V2_REGISTRY_ID = "acmg-rule-registry-e3-p1a-2026-09-02";
    private static final String V2_REGISTRY_SCHEMA_VERSION = "acmg-rule-registry-v2";
    private static final String V2_REGISTRY_SHA256 =
            "fa036bb3ca9eecc1f6272e8d7170d965eb21555e5656a5fea9eeba42e8118c88";
    private static final String V2_REGISTRY_RESOURCE =
            "/acmg/rule-registry-v2/acmg-rule-registry-e3-p1a-2026-09-02.json";
    private static final String PM2_REGISTRY_ID = "acmg-rule-registry-e3-p1b-2026-09-02";
    private static final String PM2_REGISTRY_SHA256 =
            "e2941718dbfa5f8eb59f7dd4498c1494215e18c9f6ff63acdec0cce02efae7ad";
    private static final String PM2_REGISTRY_RESOURCE =
            "/acmg/rule-registry-v2/acmg-rule-registry-e3-p1b-2026-09-02.json";
    private static final String AUTHORITY_CUTOFF = "2026-08-31";
    private static final String OFFICIAL_JOINT_DATA_URI_TEMPLATE =
            "https://storage.googleapis.com/gcp-public-data--gnomad/release/4.1/vcf/joint/"
                    + "gnomad.joint.v4.1.sites.chr{chromosome}.vcf.bgz";
    private static final String OFFICIAL_JOINT_INDEX_URI_TEMPLATE =
            OFFICIAL_JOINT_DATA_URI_TEMPLATE + ".tbi";
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> VALUE_TYPES = Set.of(
            "BIG_INTEGER", "BIG_DECIMAL_FRACTION", "ANCESTRY", "RAW_STRING"
    );
    private static final Set<String> NUMBER_SEMANTICS = Set.of("1", "A", ".");
    private static final Set<String> CHROMOSOMES = Set.of(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12",
            "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "X", "Y");

    private final ObjectMapper mapper;
    private final ObjectNode document;
    private final boolean executableV2;
    private final boolean pm2Successor;
    private final boolean pm2Business;
    private final boolean pm2Multigene;
    private final String profileId;
    private final String profileVersion;
    private final String calibrationProfileId;
    private final String profileSha256;
    private final ObjectNode successorRegistryDocument;
    private final String datasetType;
    private final boolean jointOrCombinedAvailable;
    private final boolean genomesAvailable;
    private final boolean allSitesAlleleNumberAvailable;
    private final String generalCriterionDatasetReadinessStatus;
    private final List<String> generalCriterionDatasetReasonCodes;
    private final BigInteger minimumOverallAlleleNumber;
    private final Set<String> acceptedFilters;
    private final Set<String> ancestryGroups;
    private final Set<String> faf95AncestryGroups;
    private final List<String> selectedGroupRequiredInfoTemplates;
    private final String overallAlleleCountInfoId;
    private final String overallAlleleNumberInfoId;
    private final String groupmaxAncestryInfoId;
    private final String groupmaxAlleleCountInfoId;
    private final String groupmaxAlleleNumberInfoId;
    private final String faf95maxAncestryInfoId;
    private final String faf95InfoPrefix;
    private final String filterMetricId;
    private final List<FieldSpec> fields;
    private final List<String> infoFieldIds;
    private final List<String> requiredInfoFieldIds;

    private GnomadPopulationInputProfile(ObjectMapper mapper, ObjectNode document) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.document = Objects.requireNonNull(document, "document").deepCopy();
        String schemaVersion = requireText(this.document, "profileSchemaVersion");
        this.executableV2 = V2_SCHEMA_VERSION.equals(schemaVersion);
        if (!executableV2 && !V1_SCHEMA_VERSION.equals(schemaVersion)) {
            throw invalid("unsupported profileSchemaVersion: " + schemaVersion);
        }
        this.profileId = requireText(this.document, "profileId");
        this.localBa1 = LOCAL_BA1_PROFILE_ID.equals(profileId);
        this.profileVersion = requireText(this.document, "profileVersion");
        this.hnf1aFrequency = HNF1A_PROFILE_ID.equals(profileId);
        this.pm2Successor = PM2_PROFILE_ID.equals(profileId);
        this.pm2Business = pm2Successor && "2.2.0".equals(profileVersion);
        this.pm2Multigene = pm2Successor && "2.3.0".equals(profileVersion);
        this.calibrationProfileId = profileId + "@" + profileVersion;
        validateTopLevel();
        this.successorRegistryDocument = executableV2 || localBa1 ? loadSuccessorRegistry() : null;

        JsonNode dataset = this.document.path("datasetSemantics");
        this.datasetType = requireText(dataset, "datasetType");
        this.jointOrCombinedAvailable = requireBoolean(dataset, "jointOrCombinedAvailable");
        this.genomesAvailable = requireBoolean(dataset, "genomesAvailable");
        this.allSitesAlleleNumberAvailable = requireBoolean(dataset, "allSitesAlleleNumberAvailable");
        JsonNode datasetReadiness = requireObject(dataset, "generalCriterionDatasetReadiness");
        this.generalCriterionDatasetReadinessStatus = requireText(datasetReadiness, "status");
        this.generalCriterionDatasetReasonCodes = Collections.unmodifiableList(
                textListAllowEmpty(datasetReadiness.path("reasonCodes"),
                        "generalCriterionDatasetReadiness.reasonCodes"));
        if (pm2Successor) {
            if (!"READY_FOR_BA1_BS1_AND_POLICY_GATED_PM2_SUPPORTING"
                    .equals(generalCriterionDatasetReadinessStatus)
                    || !new LinkedHashSet<String>(generalCriterionDatasetReasonCodes)
                    .equals(pm2Multigene ? Set.of("PM2_CONTEXT_POLICY_REQUIRED")
                            : pm2Business ? Set.of("PM2_CONTEXT_POLICY_REQUIRED", "PM2_ABSENCE_DETECTION_NOT_ENABLED")
                            : Set.of("PM2_PRODUCTION_POLICY_SNAPSHOT_EMPTY"))) {
                throw invalid("PM2 successor dataset readiness is not frozen");
            }
        } else if (localBa1) {
            if (!"READY_FOR_LOCAL_EXOMES_BA1".equals(generalCriterionDatasetReadinessStatus)
                    || !generalCriterionDatasetReasonCodes.isEmpty()) throw invalid("local BA1 readiness mismatch");
        } else if (executableV2) {
            if (!"READY_FOR_HIT_BASED_BA1_BS1".equals(generalCriterionDatasetReadinessStatus)
                    || !generalCriterionDatasetReasonCodes.isEmpty()) {
                throw invalid("V2 hit-based dataset readiness is not frozen");
            }
        } else {
            if (!"NOT_READY".equals(generalCriterionDatasetReadinessStatus)
                    || !new LinkedHashSet<String>(generalCriterionDatasetReasonCodes).equals(Set.of(
                    "JOINT_OR_COMBINED_DATASET_UNAVAILABLE", "ALL_SITES_AN_UNAVAILABLE"))) {
                throw invalid("general criterion dataset readiness reasons are incomplete");
            }
        }
        validateResourceIdentity();

        JsonNode gate = this.document.path("qualityGate");
        this.acceptedFilters = immutableTextSet(gate.path("acceptedFilters"), "acceptedFilters");
        this.minimumOverallAlleleNumber = requirePositiveInteger(gate, "minimumOverallAlleleNumber");

        JsonNode contract = this.document.path("fieldContract");
        if (contract.path("altIndexBase").asInt(-1) != 0) {
            throw invalid("fieldContract.altIndexBase must be zero");
        }
        this.ancestryGroups = immutableTextSet(contract.path("ancestryGroups"), "ancestryGroups");
        this.faf95AncestryGroups = immutableTextSet(
                contract.path("faf95AncestryGroups"), "faf95AncestryGroups");
        if (!ancestryGroups.containsAll(faf95AncestryGroups)) {
            throw invalid("faf95AncestryGroups must be a subset of ancestryGroups");
        }
        if (executableV2) {
            this.selectedGroupRequiredInfoTemplates = Collections.unmodifiableList(textList(
                    contract.path("selectedGroupRequiredInfoTemplates"),
                    "selectedGroupRequiredInfoTemplates"));
        } else {
            List<String> templates = new ArrayList<String>();
            for (String suffix : textList(contract.path("selectedGroupRequiredSuffixes"),
                    "selectedGroupRequiredSuffixes")) {
                templates.add(("nhomalt".equals(suffix)
                        ? "nhomalt_" : suffix.toUpperCase(Locale.ROOT) + "_") + "{ancestry}");
            }
            this.selectedGroupRequiredInfoTemplates = Collections.unmodifiableList(templates);
        }
        this.overallAlleleCountInfoId = executableV2 ? "AC_joint" : "AC";
        this.overallAlleleNumberInfoId = executableV2 ? "AN_joint" : "AN";
        this.groupmaxAncestryInfoId = executableV2 ? "grpmax_joint" : "grpmax";
        this.groupmaxAlleleCountInfoId = executableV2 ? "AC_grpmax_joint" : "AC_grpmax";
        this.groupmaxAlleleNumberInfoId = executableV2 ? "AN_grpmax_joint" : "AN_grpmax";
        this.faf95maxAncestryInfoId = executableV2
                ? "fafmax_faf95_max_gen_anc_joint" : "fafmax_faf95_max_gen_anc";
        this.faf95InfoPrefix = executableV2 ? "faf95_joint_" : "faf95_";
        this.filterMetricId = executableV2 ? "gnomad.joint.filter" : "gnomad.exomes.filter";
        this.fields = Collections.unmodifiableList(expandFields(contract));
        this.infoFieldIds = Collections.unmodifiableList(fieldIds(fields, false));
        this.requiredInfoFieldIds = Collections.unmodifiableList(fieldIds(fields, true));
        validateRequiredFields();

        this.profileSha256 = new RuntimeEvidenceV2Adapter(mapper)
                .canonicalSha256(this.document);
    }

    /** Loads and validates the committed profile from the classpath. */
    public static GnomadPopulationInputProfile loadDefault() {
        return loadClasspath(V1_RESOURCE);
    }

    public static GnomadPopulationInputProfile loadLocalBa1() {
        return loadClasspath("/acmg/gnomad-ba1-local-exomes-profile-v1.json");
    }

    public boolean isLocalBa1() { return localBa1; }

    /** Loads the isolated E3-P1A runtime/evaluation profile; it is not patient-flow enabled. */
    public static GnomadPopulationInputProfile loadExecutableV2() {
        return loadClasspath(V2_RESOURCE);
    }

    public static GnomadPopulationInputProfile loadHnf1aFrequency() {
        return loadClasspath("/acmg/gnomad-population-input-profile-hnf1a-v1.json");
    }

    /** Pinned, reviewed PM2 policies; invoked only by explicit business evaluation. */
    public static GnomadPopulationInputProfile loadBusinessPm2Supporting() {
        var profile = loadClasspath("/acmg/gnomad-population-input-profile-v2-pm2-multigene.json");
        try (InputStream source = GnomadPopulationInputProfile.class.getResourceAsStream("/acmg/pm2-cspec-reviewed-sources-20260909.json")) {
            if (source == null) throw invalid("reviewed PM2 source pack is missing");
            JsonNode pack = new ObjectMapper().readTree(source);
            if (pack == null || !profile.document.at("/evaluation/criteria/2/populationRule/policySnapshot/sourcePackSha256")
                    .asText().equals(profile.canonicalSha256(pack))) throw invalid("reviewed PM2 source pack identity mismatch");
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read reviewed PM2 source pack", failure);
        }
        return profile;
    }

    /** Loads the isolated E3-P1B PM2_Supporting successor profile. */
    public static GnomadPopulationInputProfile loadExecutablePm2Supporting() {
        return loadClasspath(PM2_RESOURCE);
    }

    private static GnomadPopulationInputProfile loadClasspath(String resource) {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream input = GnomadPopulationInputProfile.class.getResourceAsStream(resource)) {
            if (input == null) throw invalid("classpath profile is missing: " + resource);
            JsonNode parsed = mapper.readTree(input);
            if (!parsed.isObject()) throw invalid("profile root must be an object");
            return new GnomadPopulationInputProfile(mapper, (ObjectNode) parsed);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load gnomAD population input profile", failure);
        }
    }

    static GnomadPopulationInputProfile fromDocument(ObjectMapper mapper, ObjectNode document) {
        return new GnomadPopulationInputProfile(mapper, document);
    }

    public String getProfileId() {
        return profileId;
    }

    public String getProfileVersion() {
        return profileVersion;
    }

    /** Runtime canonical fingerprint; it is intentionally not stored inside the profile. */
    public String getProfileSha256() {
        return profileSha256;
    }

    public String getFullSitesResourceId() {
        return getPopulationResourceId();
    }

    public String getFullSitesResourceIdentityFingerprintSha256() {
        return getPopulationResourceIdentityFingerprintSha256();
    }

    public String getPopulationResourceId() {
        return document.path("resources").get(0).path("resourceId").asText();
    }

    public String getPopulationResourceIdentityFingerprintSha256() {
        return document.path("resources").get(0).path("identityFingerprintSha256").asText();
    }

    public boolean isCriterionEvaluationEnabled() {
        return executableV2 || localBa1;
    }

    public ObjectNode getSuccessorRegistryDocument() {
        if (successorRegistryDocument == null) {
            throw new IllegalStateException("Successor registry is available only for executable V2");
        }
        return successorRegistryDocument.deepCopy();
    }

    public String getRemoteJointUri(String chromosome) {
        return expandRemoteJointUri(OFFICIAL_JOINT_DATA_URI_TEMPLATE, chromosome);
    }

    public String getRemoteJointIndexUri(String chromosome) {
        return expandRemoteJointUri(OFFICIAL_JOINT_INDEX_URI_TEMPLATE, chromosome);
    }

    public String getDatasetType() {
        return datasetType;
    }

    public List<String> getInfoFieldIds() {
        return infoFieldIds;
    }

    public List<String> getRequiredInfoFieldIds() {
        return requiredInfoFieldIds;
    }

    /** Returns a defensive copy suitable for the existing resource-binding wiring. */
    public ObjectNode getDocument() {
        return document.deepCopy();
    }

    /**
     * Parses one matched ALT from FILTER and INFO values.
     *
     * @param filter VCF FILTER column
     * @param info raw INFO lexemes keyed by INFO ID
     * @param altIndex zero-based index of the matched ALT
     * @param altCount number of ALT alleles in the record
     */
    public Result parse(String filter, Map<String, String> info, int altIndex, int altCount) {
        Map<String, String> safeInfo = info == null ? Collections.emptyMap() : info;
        List<Map<String, String>> measurements = new ArrayList<Map<String, String>>();
        LinkedHashSet<String> reasons = new LinkedHashSet<String>();
        String qualityStatus = parseFilter(filter, measurements, reasons);

        if (altCount < 1 || altIndex < 0 || altIndex >= altCount) {
            reasons.add("ALT_INDEX_OUT_OF_RANGE");
            return result(measurements, qualityStatus, reasons);
        }

        Map<String, String> normalizedByInfoId = new LinkedHashMap<String, String>();
        for (FieldSpec field : fields) {
            String source = trimmedOrNull(safeInfo.get(field.infoId));
            if (source == null || ".".equals(source)) {
                if (field.required) reasons.add("REQUIRED_INFO_FIELD_MISSING");
                continue;
            }
            String selected = selectValue(field, source, altIndex, altCount, reasons);
            if (selected == null || ".".equals(selected)) {
                if (field.required) reasons.add("REQUIRED_INFO_FIELD_MISSING");
                continue;
            }
            String normalized = normalize(field, selected, reasons);
            if (normalized == null) continue;
            normalizedByInfoId.put(field.infoId, normalized);
            measurements.add(measurement(field.metricId, selected, normalized, field.unit));
        }

        validateOverallAlleleNumber(normalizedByInfoId, reasons);
        validateCountPair(normalizedByInfoId,
                overallAlleleCountInfoId, overallAlleleNumberInfoId, reasons);
        validateCountPair(normalizedByInfoId,
                groupmaxAlleleCountInfoId, groupmaxAlleleNumberInfoId, reasons);
        validateSelectedGroupFields(
                normalizedByInfoId.get(groupmaxAncestryInfoId), normalizedByInfoId,
                "GROUPMAX_ANCESTRY_FIELDS_MISSING", reasons);
        validateSelectedGroupCounts(
                normalizedByInfoId.get(groupmaxAncestryInfoId), normalizedByInfoId, reasons);
        String faf95Group = normalizedByInfoId.get(faf95maxAncestryInfoId);
        if (faf95Group != null) {
            if (!faf95AncestryGroups.contains(faf95Group)) {
                reasons.add("FAF95_ANCESTRY_UNSUPPORTED");
            } else {
                validateSelectedGroupFields(
                        faf95Group, normalizedByInfoId,
                        "FAF95_ANCESTRY_FIELDS_MISSING", reasons);
                validateSelectedGroupCounts(faf95Group, normalizedByInfoId, reasons);
                if (!normalizedByInfoId.containsKey(faf95InfoPrefix + faf95Group)) {
                    reasons.add("FAF95_ANCESTRY_FIELD_MISSING");
                }
            }
        }
        return result(measurements, qualityStatus, reasons);
    }

    private void validateTopLevel() {
        if (localBa1) {
            validateLocalBa1();
        } else if (executableV2) {
            validateV2TopLevel();
        } else {
            validateV1TopLevel();
        }
    }

    private void validateLocalBa1() {
        // Dedicated local policy: never repurpose the frozen joint or input-only identities.
        requireEquals(document, "profileSchemaVersion", V1_SCHEMA_VERSION);
        requireEquals(document, "profileVersion", "1.0.0");
        requireEquals(document, "status", "LOCAL_EXOMES_BA1_EVALUATION");
        requireEquals(document.path("datasetSemantics"), "datasetType", "EXOMES");
        requireFalse(document.path("datasetSemantics"), "jointOrCombinedAvailable");
        requireFalse(document.path("datasetSemantics"), "genomesAvailable");
        if (!LOCAL_BA1_SHA256.equals(canonicalSha256(document))) throw invalid("local BA1 profile hash mismatch");
    }

    private void validateV1TopLevel() {
        if (document.has("profileSha256")) {
            throw invalid("profile must not self-hash");
        }
        requireEquals(document, "profileSchemaVersion", V1_SCHEMA_VERSION);
        requireEquals(document, "profileId", V1_PROFILE_ID);
        requireEquals(document, "profileVersion", V1_PROFILE_VERSION);
        requireEquals(document, "status", "INPUT_ONLY");
        requireEquals(document, "canonicalizationProfile", "varvis-json-c14n-v1");
        requireEquals(document, "authorityCutoff", AUTHORITY_CUTOFF);
        requireEquals(document, "scope", "GERMLINE_MENDELIAN_SMALL_SEQUENCE_VARIANT");
        requireText(document, "asOf");

        JsonNode registry = requireObject(document, "registryBinding");
        requireEquals(registry, "registryId", BASE_REGISTRY_ID);
        requireSha256(registry, "registrySha256");
        Set<String> criteria = immutableTextSet(registry.path("criterionIds"), "criterionIds");
        if (!criteria.equals(Set.of(
                "ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1", "ACMG_AMP_2015:PM2"))) {
            throw invalid("registryBinding must bind exactly BA1, BS1 and PM2");
        }

        JsonNode evaluation = requireObject(document, "evaluation");
        if (!evaluation.path("evaluationEnabled").isBoolean()
                || evaluation.path("evaluationEnabled").asBoolean()) {
            throw invalid("criterion evaluation must remain disabled");
        }
        Set<String> forbidden = immutableTextSet(
                evaluation.path("forbiddenCriterionResults"), "forbiddenCriterionResults");
        if (!forbidden.equals(Set.of("MET", "NOT_MET"))) {
            throw invalid("MET and NOT_MET must remain forbidden");
        }
        JsonNode criterionMetadata = evaluation.path("criteria");
        if (!criterionMetadata.isArray() || criterionMetadata.size() != 3) {
            throw invalid("evaluation.criteria must contain three metadata entries");
        }
        for (JsonNode criterion : criterionMetadata) {
            if (!criterion.path("evaluationEnabled").isBoolean()
                    || criterion.path("evaluationEnabled").asBoolean()) {
                throw invalid("criterion metadata cannot enable evaluation");
            }
        }

        validateAuthorityBindings();
        JsonNode output = requireObject(document, "outputPolicy");
        if (!output.path("atomicEvidenceOnly").asBoolean(false)) {
            throw invalid("output must remain atomic-evidence-only");
        }
        requireFalse(output, "legacySuggestionEnabled");
        requireFalse(output, "classificationEnabled");
        requireEquals(output, "direction", "UNDETERMINED");
        requireEquals(output, "readinessMeaning",
                "MATCHED_RECORD_FIELDS_READY_FOR_FUTURE_CONTEXTUAL_EVALUATION_NOT_CRITERION_READY");

        JsonNode identitySemantics = requireObject(document, "resourceIdentitySemantics");
        requireEquals(identitySemantics, "scope", "CANONICAL_RESOURCE_IDENTITY_NOT_FULL_BYTES");
        requireFalse(identitySemantics, "byteIntegrityClaimed");

        JsonNode compact = requireObject(document, "retrievalOnlyResourceReference");
        requireEquals(compact, "resourceId", COMPACT_RESOURCE_ID);
        requireEquals(compact, "use", "RETRIEVAL_ONLY");
        if (!compact.path("criterionInputAllowed").isBoolean()
                || compact.path("criterionInputAllowed").asBoolean()) {
            throw invalid("compact resource cannot be criterion input");
        }

        JsonNode dataset = requireObject(document, "datasetSemantics");
        requireEquals(dataset, "datasetType", "EXOMES");
        requireEquals(dataset, "assembly", "GRCh38");
        requireFalse(dataset, "jointOrCombinedAvailable");
        requireFalse(dataset, "genomesAvailable");
        requireFalse(dataset, "allSitesAlleleNumberAvailable");
        requireFalse(dataset, "noRecordInputReady");
        requireEquals(dataset, "noRecordReasonCode", "NO_RECORD_WITHOUT_ALL_SITES_AN");

        JsonNode numeric = requireObject(document, "numericSemantics");
        requireEquals(numeric, "integerRuntimeType", "java.math.BigInteger");
        requireEquals(numeric, "decimalRuntimeType", "java.math.BigDecimal");
        requireFalse(numeric, "binaryFloatingPointAllowedForDecisions");
        if (!numeric.path("rawLexemePreserved").asBoolean(false)) {
            throw invalid("raw decimal lexemes must be preserved");
        }
    }

    private void validateV2TopLevel() {
        if (document.has("profileSha256")) throw invalid("profile must not self-hash");
        String expectedProfileHash = hnf1aFrequency ? HNF1A_PROFILE_SHA256 : pm2Multigene ? "a4b716c75857b60ce4dd6a05f867a1ec1fb95ecf9d548888e76aca2d6861ae88"
                : pm2Business ? "649f05daac794da9ca57075f394b8e86f57ebd400e1e9e2734028bf41dbe69e7"
                : pm2Successor ? PM2_PROFILE_SHA256 : V2_PROFILE_SHA256;
        if (!expectedProfileHash.equals(canonicalSha256(document))) {
            throw invalid("executable V2 profile canonical hash is not pinned");
        }
        requireEquals(document, "profileSchemaVersion", V2_SCHEMA_VERSION);
        requireEquals(document, "profileId", hnf1aFrequency ? HNF1A_PROFILE_ID : pm2Successor ? PM2_PROFILE_ID : V2_PROFILE_ID);
        requireEquals(document, "profileVersion",
                hnf1aFrequency ? "1.0.0" : pm2Multigene ? "2.3.0" : pm2Business ? "2.2.0" : pm2Successor ? PM2_PROFILE_VERSION : V2_PROFILE_VERSION);
        requireEquals(document, "status", "RUNTIME_EVALUATION_SLICE");
        requireEquals(document, "canonicalizationProfile", "varvis-json-c14n-v1");

        if (pm2Successor) {
            JsonNode lineage = requireObject(document, "lineage");
            requireEquals(lineage, "predecessorProfileId", pm2Business || pm2Multigene ? PM2_PROFILE_ID : V2_PROFILE_ID);
            requireEquals(lineage, "predecessorProfileVersion", pm2Multigene ? "2.2.0" : pm2Business ? PM2_PROFILE_VERSION : V2_PROFILE_VERSION);
            requireEquals(lineage, "predecessorProfileSha256", pm2Multigene
                    ? "649f05daac794da9ca57075f394b8e86f57ebd400e1e9e2734028bf41dbe69e7"
                    : pm2Business ? PM2_PROFILE_SHA256 : V2_PROFILE_SHA256);
        }

        JsonNode activation = requireObject(document, "activation");
        requireTrue(activation, "runtimeBound");
        requireTrue(activation, "evaluationEnabled");
        requireFalse(activation, "patientFlowEnabled");
        requireFalse(activation, "classificationEnabled");

        JsonNode registry = requireObject(document, "registryBinding");
        requireEquals(registry, "registryId", hnf1aFrequency ? HNF1A_REGISTRY_ID : pm2Successor ? PM2_REGISTRY_ID : V2_REGISTRY_ID);
        requireEquals(registry, "registrySchemaVersion", V2_REGISTRY_SCHEMA_VERSION);
        requireEquals(registry, "registrySha256",
                hnf1aFrequency ? HNF1A_REGISTRY_SHA256 : pm2Successor ? PM2_REGISTRY_SHA256 : V2_REGISTRY_SHA256);
        requireEquals(registry, "registryClasspathResource",
                hnf1aFrequency ? HNF1A_REGISTRY_RESOURCE : pm2Successor ? PM2_REGISTRY_RESOURCE : V2_REGISTRY_RESOURCE);
        Set<String> expectedCriteria = pm2Successor
                ? Set.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1", "ACMG_AMP_2015:PM2")
                : Set.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1");
        if (!immutableTextSet(registry.path("criterionIds"), "criterionIds")
                .equals(expectedCriteria)) {
            throw invalid("V2 registry binding criterion set is invalid");
        }

        JsonNode dataset = requireObject(document, "datasetSemantics");
        requireEquals(dataset, "datasetType",
                pm2Successor ? "JOINT_WITH_SEPARATE_ALL_SITES_AN" : "JOINT");
        requireEquals(dataset, "assembly", "GRCh38");
        requireTrue(dataset, "jointOrCombinedAvailable");
        requireTrue(dataset, "genomesAvailable");
        if (pm2Successor) {
            requireTrue(dataset, "allSitesAlleleNumberAvailable");
            requireTrue(dataset, "noRecordInputReady");
        } else {
            requireFalse(dataset, "allSitesAlleleNumberAvailable");
            requireFalse(dataset, "noRecordInputReady");
        }

        JsonNode numeric = requireObject(document, "numericSemantics");
        requireEquals(numeric, "integerRuntimeType", "java.math.BigInteger");
        requireEquals(numeric, "decimalRuntimeType", "java.math.BigDecimal");
        requireFalse(numeric, "binaryFloatingPointAllowedForDecisions");
        requireTrue(numeric, "rawLexemePreserved");
    }

    private ObjectNode loadSuccessorRegistry() {
        String registryResource = hnf1aFrequency ? HNF1A_REGISTRY_RESOURCE : pm2Successor ? PM2_REGISTRY_RESOURCE : V2_REGISTRY_RESOURCE;
        String expectedByteHash = hnf1aFrequency ? HNF1A_REGISTRY_SHA256 : pm2Successor ? PM2_REGISTRY_SHA256 : V2_REGISTRY_SHA256;
        String expectedRegistryId = hnf1aFrequency ? HNF1A_REGISTRY_ID : pm2Successor ? PM2_REGISTRY_ID : V2_REGISTRY_ID;
        byte[] bytes = readClasspathBytes(registryResource);
        if (!expectedByteHash.equals(sha256(bytes))) {
            throw invalid("successor registry byte hash does not match registryBinding");
        }
        JsonNode registry;
        try {
            registry = mapper.readTree(bytes);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot parse successor ACMG rule registry", failure);
        }
        if (!registry.isObject()) throw invalid("successor registry root must be an object");
        requireEquals(registry, "schemaVersion", V2_REGISTRY_SCHEMA_VERSION);
        requireEquals(registry, "registryId", expectedRegistryId);
        JsonNode lineage = requireObject(registry, "lineage");
        requireEquals(lineage, "baseRegistryId", BASE_REGISTRY_ID);
        requireEquals(lineage, "baseSchemaVersion", BASE_REGISTRY_SCHEMA_VERSION);
        requireEquals(lineage, "baseRegistrySha256", BASE_REGISTRY_CANONICAL_SHA256);
        requireEquals(registry, "authorityCutoff", hnf1aFrequency ? "2026-09-10" : AUTHORITY_CUTOFF);
        JsonNode criteria = registry.path("criteria");
        if (!criteria.isArray() || criteria.size() != 28) {
            throw invalid("successor registry must contain all 28 criteria");
        }
        Set<String> ids = new LinkedHashSet<String>();
        Set<String> implemented = new LinkedHashSet<String>();
        for (JsonNode criterion : criteria) {
            String id = requireText(criterion, "criterionId");
            if (!ids.add(id)) throw invalid("duplicate registry criterion: " + id);
            String status = requireText(criterion, "implementationStatus");
            if ("V2_IMPLEMENTED".equals(status)) implemented.add(id);
            else if (!Set.of("NOT_IMPLEMENTED", "LEGACY_HEURISTIC_ONLY").contains(status)) {
                throw invalid("unsupported successor registry implementation status: " + status);
            }
        }
        Set<String> expectedImplemented = pm2Successor
                ? Set.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1", "ACMG_AMP_2015:PM2")
                : Set.of("ACMG_AMP_2015:BA1", "ACMG_AMP_2015:BS1");
        if (!implemented.equals(expectedImplemented)) {
            throw invalid("successor registry implementation set is invalid");
        }
        return ((ObjectNode) registry).deepCopy();
    }

    private void validateAuthorityBindings() {
        JsonNode bindings = document.path("authorityBindings");
        if (!bindings.isArray() || bindings.size() != 2) {
            throw invalid("authorityBindings must contain the two pinned sources");
        }
        Map<String, String> expected = Map.of(
                "authority-clingen-vcep-gnomad-v4-v3.0",
                "b84b9c30696aea6fa5cddc0916fe07a3ca647fcbd89d77b3d510bd5cf499106f",
                "authority-clingen-svi-pm2-v1.0",
                "30745e928c7146a479afdfb7f22230192a8d1d66f9239a463c53667dd9598c59"
        );
        Map<String, String> actual = new LinkedHashMap<String, String>();
        for (JsonNode binding : bindings) {
            String id = requireText(binding, "authoritySourceId");
            String sha256 = requireSha256(binding, "snapshotSha256");
            if (actual.put(id, sha256) != null) throw invalid("duplicate authority binding: " + id);
        }
        if (!expected.equals(actual)) throw invalid("authority bindings do not match the E1 snapshots");
    }

    private void validateResourceIdentity() {
        JsonNode resources = document.path("resources");
        if (pm2Successor) {
            if (!resources.isArray() || resources.size() != 3) {
                throw invalid("PM2 successor resources must contain joint, exome and genome identities");
            }
            Map<String, String> expectedDatasets = Map.of(
                    JOINT_RESOURCE_ID, "joint",
                    EXOME_ALL_SITES_RESOURCE_ID, "exomes",
                    GENOME_ALL_SITES_RESOURCE_ID, "genomes");
            Set<String> seen = new LinkedHashSet<String>();
            for (JsonNode resource : resources) {
                String resourceId = requireText(resource, "resourceId");
                String dataset = expectedDatasets.get(resourceId);
                if (dataset == null || !seen.add(resourceId)) {
                    throw invalid("PM2 successor resource identity set is invalid");
                }
                requireEquals(resource, "provider", "GNOMAD");
                requireEquals(resource, "assembly", "GRCh38");
                requireText(resource, "resourceVersion");
                String fingerprint = requireSha256(resource, "identityFingerprintSha256");
                JsonNode identity = requireObject(resource, "identity");
                requireEquals(identity, "provider", "GNOMAD");
                requireEquals(identity, "release", "4.1");
                requireEquals(identity, "dataset", dataset);
                requireEquals(identity, "assembly", "GRCh38");
                if (!fingerprint.equals(new RuntimeEvidenceV2Adapter(mapper)
                        .canonicalSha256(identity))) {
                    throw invalid("PM2 successor resource identity fingerprint is invalid");
                }
                String serialized = identity.toString();
                if (serialized.matches("(?is).*\"[A-Z]:[\\\\/].*")
                        || serialized.contains("${")) {
                    throw invalid("resource identity must not contain a local absolute path");
                }
                if (JOINT_RESOURCE_ID.equals(resourceId)) {
                    if (!immutableTextSet(identity.path("chromosomes"), "chromosomes")
                            .equals(CHROMOSOMES)
                            || !OFFICIAL_JOINT_DATA_URI_TEMPLATE.equals(
                            requireText(identity, "dataUriTemplate"))
                            || !OFFICIAL_JOINT_INDEX_URI_TEMPLATE.equals(
                            requireText(identity, "indexUriTemplate"))) {
                        throw invalid("PM2 successor joint identity is invalid");
                    }
                } else if (!requireText(identity, "tableUri")
                        .startsWith("gs://gcp-public-data--gnomad/release/4.1/ht/")) {
                    throw invalid("PM2 successor all-sites table URI is not official");
                }
            }
            if (!seen.equals(expectedDatasets.keySet())) {
                throw invalid("PM2 successor resource identity set is incomplete");
            }
            return;
        }
        if (!resources.isArray() || resources.size() != 1) {
            throw invalid("resources must contain exactly one population resource identity");
        }
        JsonNode resource = resources.get(0);
        requireEquals(resource, "resourceId", executableV2 ? JOINT_RESOURCE_ID : FULL_SITES_RESOURCE_ID);
        requireEquals(resource, "provider", "GNOMAD");
        requireEquals(resource, "assembly", "GRCh38");
        requireText(resource, "resourceVersion");
        String fingerprint = requireSha256(resource, "identityFingerprintSha256");
        JsonNode identity = requireObject(resource, "identity");
        requireEquals(identity, "provider", "GNOMAD");
        requireEquals(identity, "release", "4.1");
        requireEquals(identity, "dataset", executableV2 ? "joint" : "exomes");
        requireEquals(identity, "assembly", "GRCh38");
        if (!fingerprint.equals(new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(identity))) {
            throw invalid("population resource identity fingerprint is invalid");
        }
        String serialized = identity.toString();
        if (serialized.matches("(?is).*\"[A-Z]:[\\\\/].*") || serialized.contains("${")) {
            throw invalid("resource identity must not contain a local absolute path");
        }
        if (!immutableTextSet(identity.path("chromosomes"), "chromosomes").equals(CHROMOSOMES)) {
            throw invalid("resource chromosomes must be canonical 1-22,X,Y");
        }
        if (executableV2) {
            if (!OFFICIAL_JOINT_DATA_URI_TEMPLATE.equals(requireText(identity, "dataUriTemplate"))
                    || !OFFICIAL_JOINT_INDEX_URI_TEMPLATE.equals(
                    requireText(identity, "indexUriTemplate"))) {
                throw invalid("V2 remote joint URI templates are not the pinned official HTTPS URLs");
            }
        }
    }

    private List<FieldSpec> expandFields(JsonNode contract) {
        List<FieldSpec> expanded = new ArrayList<FieldSpec>();
        JsonNode declared = contract.path("fields");
        if (!declared.isArray() || declared.isEmpty()) {
            throw invalid("fieldContract.fields must not be empty");
        }
        for (JsonNode field : declared) expanded.add(field(field, null));

        JsonNode templates = contract.path("ancestryFieldTemplates");
        if (!templates.isArray() || templates.isEmpty()) {
            throw invalid("ancestryFieldTemplates must not be empty");
        }
        for (JsonNode template : templates) {
            String metricTemplate = requireText(template,
                    template.has("metricIdTemplate") ? "metricIdTemplate" : "metricId");
            String infoTemplate = requireText(template,
                    template.has("infoIdTemplate") ? "infoIdTemplate" : "infoId");
            if (!metricTemplate.contains("{ancestry}") || !infoTemplate.contains("{ancestry}")) {
                throw invalid("ancestry templates must contain {ancestry}");
            }
            Set<String> appliesTo = template.has("appliesTo")
                    ? immutableTextSet(template.path("appliesTo"), "appliesTo")
                    : ancestryGroups;
            if (!ancestryGroups.containsAll(appliesTo)) {
                throw invalid("template appliesTo contains an unknown ancestry");
            }
            for (String ancestry : ancestryGroups) {
                if (!appliesTo.contains(ancestry)) continue;
                expanded.add(field(template, ancestry));
            }
        }

        Set<String> metricIds = new LinkedHashSet<String>();
        Set<String> infoIds = new LinkedHashSet<String>();
        for (FieldSpec field : expanded) {
            if (!metricIds.add(field.metricId)) throw invalid("duplicate metricId: " + field.metricId);
            if (!infoIds.add(field.infoId)) throw invalid("duplicate INFO ID: " + field.infoId);
        }
        return expanded;
    }

    private FieldSpec field(JsonNode declaration, String ancestry) {
        String metricKey = ancestry == null || !declaration.has("metricIdTemplate")
                ? "metricId" : "metricIdTemplate";
        String infoKey = ancestry == null || !declaration.has("infoIdTemplate")
                ? "infoId" : "infoIdTemplate";
        String metricId = requireText(declaration, metricKey);
        String infoId = requireText(declaration, infoKey);
        if (ancestry != null) {
            metricId = metricId.replace("{ancestry}", ancestry);
            infoId = infoId.replace("{ancestry}", ancestry);
        }
        String number = requireText(declaration, "number");
        String valueType = requireText(declaration, "valueType");
        if (!NUMBER_SEMANTICS.contains(number)) throw invalid("unsupported Number semantics: " + number);
        if (!VALUE_TYPES.contains(valueType)) throw invalid("unsupported field valueType: " + valueType);
        if (!declaration.path("required").isBoolean()) throw invalid("field required flag is missing");
        return new FieldSpec(
                metricId,
                infoId,
                number,
                valueType,
                requireText(declaration, "unit"),
                declaration.path("required").asBoolean()
        );
    }

    private void validateRequiredFields() {
        Set<String> required = new LinkedHashSet<String>(requiredInfoFieldIds);
        Set<String> expected = pm2Multigene ? Set.of("AC_joint", "AN_joint", "AF_joint", "nhomalt_joint") : executableV2 ? Set.of(
                "AC_joint", "AN_joint", "AF_joint", "nhomalt_joint", "grpmax_joint",
                "AC_grpmax_joint", "AN_grpmax_joint", "AF_grpmax_joint",
                "nhomalt_grpmax_joint", "faf95_joint", "fafmax_faf95_max_joint",
                "fafmax_faf95_max_gen_anc_joint") : Set.of(
                "AC", "AN", "AF", "nhomalt", "grpmax", "AC_grpmax", "AN_grpmax",
                "AF_grpmax", "nhomalt_grpmax", "faf95", "fafmax_faf95_max",
                "fafmax_faf95_max_gen_anc");
        if (!required.containsAll(expected)) {
            throw invalid("the population core and GroupMax/FAF fields must be required");
        }
    }

    private String parseFilter(String filter,
                               List<Map<String, String>> measurements,
                               Set<String> reasons) {
        String raw = trimmedOrNull(filter);
        if (raw == null || ".".equals(raw)) {
            reasons.add("FILTER_MISSING");
            return "UNKNOWN";
        }
        measurements.add(measurement(
                filterMetricId, raw, raw, "vcf-filter"));
        if (!acceptedFilters.contains(raw)) {
            reasons.add("FILTER_NOT_PASS");
            return "FAIL";
        }
        return "PASS";
    }

    private String selectValue(FieldSpec field,
                               String raw,
                               int altIndex,
                               int altCount,
                               Set<String> reasons) {
        if ("1".equals(field.number)) {
            if (raw.indexOf(',') >= 0) {
                reasons.add("FIELD_CARDINALITY_MISMATCH");
                return null;
            }
            return raw;
        }
        if (".".equals(field.number)) return raw;
        String[] values = raw.split(",", -1);
        if (values.length != altCount) {
            reasons.add("ALLELE_CARDINALITY_MISMATCH");
            return null;
        }
        return trimmedOrNull(values[altIndex]);
    }

    private String normalize(FieldSpec field, String raw, Set<String> reasons) {
        try {
            if ("BIG_INTEGER".equals(field.valueType)) {
                BigInteger value = new BigInteger(raw);
                if (value.signum() < 0) throw new NumberFormatException("negative integer");
                return value.toString();
            }
            if ("BIG_DECIMAL_FRACTION".equals(field.valueType)) {
                BigDecimal value = new BigDecimal(raw);
                if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
                    throw new NumberFormatException("fraction is outside [0,1]");
                }
                return canonicalDecimal(value);
            }
            if ("RAW_STRING".equals(field.valueType)) return raw;
            String ancestry = raw.toLowerCase(Locale.ROOT);
            if (!ancestryGroups.contains(ancestry)) {
                reasons.add("ANCESTRY_VALUE_UNSUPPORTED");
                return null;
            }
            return ancestry;
        } catch (NumberFormatException failure) {
            reasons.add("INVALID_NUMERIC_VALUE");
            return null;
        }
    }

    private void validateOverallAlleleNumber(Map<String, String> normalizedByInfoId,
                                             Set<String> reasons) {
        String normalized = normalizedByInfoId.get(overallAlleleNumberInfoId);
        if (normalized != null
                && new BigInteger(normalized).compareTo(minimumOverallAlleleNumber) < 0) {
            reasons.add("ALLELE_NUMBER_BELOW_PROFILE_MINIMUM");
        }
        String groupMaximum = normalizedByInfoId.get(groupmaxAlleleNumberInfoId);
        if (groupMaximum != null
                && new BigInteger(groupMaximum).compareTo(minimumOverallAlleleNumber) < 0) {
            reasons.add("ALLELE_NUMBER_BELOW_PROFILE_MINIMUM");
        }
    }

    private void validateCountPair(Map<String, String> values,
                                   String alleleCountField,
                                   String alleleNumberField,
                                   Set<String> reasons) {
        String alleleCount = values.get(alleleCountField);
        String alleleNumber = values.get(alleleNumberField);
        if (alleleCount != null && alleleNumber != null
                && new BigInteger(alleleCount).compareTo(new BigInteger(alleleNumber)) > 0) {
            reasons.add("COUNT_INCONSISTENT");
        }
    }

    private void validateSelectedGroupCounts(String ancestry,
                                             Map<String, String> values,
                                             Set<String> reasons) {
        if (ancestry == null) return;
        validateCountPair(values,
                selectedGroupInfoId(executableV2 ? "AC_joint_{ancestry}" : "AC_{ancestry}", ancestry),
                selectedGroupInfoId(executableV2 ? "AN_joint_{ancestry}" : "AN_{ancestry}", ancestry),
                reasons);
    }

    private void validateSelectedGroupFields(String ancestry,
                                             Map<String, String> normalizedByInfoId,
                                             String reason,
                                             Set<String> reasons) {
        if (ancestry == null) return;
        for (String template : selectedGroupRequiredInfoTemplates) {
            if (!normalizedByInfoId.containsKey(selectedGroupInfoId(template, ancestry))) {
                reasons.add(reason);
                return;
            }
        }
    }

    private String selectedGroupInfoId(String template, String ancestry) {
        if (!template.contains("{ancestry}")) {
            throw invalid("selected-group INFO template must contain {ancestry}");
        }
        return template.replace("{ancestry}", ancestry);
    }

    private Result result(List<Map<String, String>> measurements,
                          String qualityStatus,
                          LinkedHashSet<String> reasons) {
        String readinessStatus = reasons.isEmpty() ? "READY" : "NOT_READY";
        if (executableV2 || localBa1) {
            measurements.add(measurement(
                    "gnomad.exactAlleleMatch", "true", "true", "boolean"));
        }
        measurements.add(measurement("gnomad.datasetType", datasetType, datasetType, "dataset-type"));
        measurements.add(measurement(
                "gnomad.inputProfileId", profileId, profileId, "profile-id"));
        measurements.add(measurement(
                "gnomad.inputProfileVersion", profileVersion, profileVersion, "profile-version"));
        measurements.add(measurement(
                "gnomad.inputProfileSha256", profileSha256, profileSha256, "sha256"));
        measurements.add(measurement(
                "gnomad.inputQualityStatus", qualityStatus, qualityStatus, "status"));
        measurements.add(measurement(
                "gnomad.recordInputReadinessStatus", readinessStatus, readinessStatus, "status"));
        if (!reasons.isEmpty()) {
            String reasonValue = String.join(",", reasons);
            measurements.add(measurement(
                    "gnomad.recordInputReadinessReasonCodes",
                    reasonValue, reasonValue, "reason-code-list"));
        }
        measurements.add(booleanMeasurement(
                "gnomad.jointOrCombinedAvailable", jointOrCombinedAvailable));
        measurements.add(booleanMeasurement("gnomad.genomesAvailable", genomesAvailable));
        measurements.add(booleanMeasurement(
                "gnomad.allSitesAlleleNumberAvailable", allSitesAlleleNumberAvailable));
        measurements.add(booleanMeasurement(
                "gnomad.criterionEvaluationEnabled", isCriterionEvaluationEnabled()));
        measurements.add(measurement(
                "gnomad.generalCriterionDatasetReadinessStatus",
                generalCriterionDatasetReadinessStatus,
                generalCriterionDatasetReadinessStatus, "status"));
        if (!generalCriterionDatasetReasonCodes.isEmpty()) {
            String datasetReasons = String.join(",", generalCriterionDatasetReasonCodes);
            measurements.add(measurement(
                    "gnomad.generalCriterionDatasetReadinessReasonCodes",
                    datasetReasons, datasetReasons, "reason-code-list"));
        }
        return new Result(
                measurements,
                qualityStatus,
                readinessStatus,
                new ArrayList<String>(reasons)
        );
    }

    private Map<String, String> booleanMeasurement(String metricId, boolean value) {
        String text = Boolean.toString(value);
        return measurement(metricId, text, text, "boolean");
    }

    private Map<String, String> measurement(String metricId,
                                            String rawValue,
                                            String normalizedValue,
                                            String unit) {
        Map<String, String> value = new LinkedHashMap<String, String>();
        value.put("metricId", metricId);
        value.put("rawValue", rawValue);
        value.put("normalizedValue", normalizedValue);
        value.put("unit", unit);
        value.put("calibrationProfileId", calibrationProfileId);
        return Collections.unmodifiableMap(value);
    }

    private String expandRemoteJointUri(String template, String chromosome) {
        if (!executableV2 || template == null) {
            throw new IllegalStateException("Remote joint URI is available only for the executable V2 profile");
        }
        String canonical = trimmedOrNull(chromosome);
        if (canonical == null || !CHROMOSOMES.contains(canonical)) {
            throw new IllegalArgumentException("Unsupported canonical gnomAD chromosome: " + chromosome);
        }
        String uri = template.replace("{chromosome}", canonical);
        if (!uri.startsWith("https://storage.googleapis.com/gcp-public-data--gnomad/")
                || uri.contains("{") || uri.contains("}") || uri.contains("${")) {
            throw invalid("expanded remote joint URI is not an official pinned HTTPS URI");
        }
        return uri;
    }

    private String canonicalSha256(JsonNode value) {
        return new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value);
    }

    private static byte[] readClasspathBytes(String resource) {
        try (InputStream input = GnomadPopulationInputProfile.class.getResourceAsStream(resource)) {
            if (input == null) throw invalid("classpath resource is missing: " + resource);
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read classpath resource " + resource, failure);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String canonicalDecimal(BigDecimal value) {
        if (value.signum() == 0) return "0";
        return value.stripTrailingZeros().toPlainString();
    }

    private static List<String> fieldIds(List<FieldSpec> fields, boolean requiredOnly) {
        List<String> result = new ArrayList<String>();
        for (FieldSpec field : fields) {
            if (!requiredOnly || field.required) result.add(field.infoId);
        }
        return result;
    }

    private static JsonNode requireObject(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) throw invalid(field + " must be an object");
        return value;
    }

    private static String requireText(JsonNode parent, String field) {
        String value = parent.path(field).isTextual() ? trimmedOrNull(parent.path(field).asText()) : null;
        if (value == null) throw invalid("missing " + field);
        return value;
    }

    private static void requireEquals(JsonNode parent, String field, String expected) {
        String actual = requireText(parent, field);
        if (!expected.equals(actual)) throw invalid(field + " must be " + expected);
    }

    private static String requireSha256(JsonNode parent, String field) {
        String value = requireText(parent, field);
        if (!SHA256.matcher(value).matches()) throw invalid(field + " is not a SHA-256");
        return value;
    }

    private static void requireFalse(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isBoolean() || value.asBoolean()) throw invalid(field + " must be false");
    }

    private static void requireTrue(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isBoolean() || !value.asBoolean()) throw invalid(field + " must be true");
    }

    private static boolean requireBoolean(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isBoolean()) throw invalid(field + " must be boolean");
        return value.asBoolean();
    }

    private static BigInteger requirePositiveInteger(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isIntegralNumber()) throw invalid(field + " must be a positive integer");
        BigInteger result = value.bigIntegerValue();
        if (result.signum() < 1) throw invalid(field + " must be a positive integer");
        return result;
    }

    private static Set<String> immutableTextSet(JsonNode array, String field) {
        return Collections.unmodifiableSet(new LinkedHashSet<String>(textList(array, field)));
    }

    private static List<String> textList(JsonNode array, String field) {
        if (!array.isArray() || array.isEmpty()) throw invalid(field + " must be a non-empty array");
        return textListAllowEmpty(array, field);
    }

    private static List<String> textListAllowEmpty(JsonNode array, String field) {
        if (!array.isArray()) throw invalid(field + " must be an array");
        List<String> values = new ArrayList<String>();
        for (JsonNode item : array) {
            if (!item.isTextual() || trimmedOrNull(item.asText()) == null) {
                throw invalid(field + " contains a non-text value");
            }
            String value = item.asText().trim();
            if (values.contains(value)) throw invalid(field + " contains a duplicate: " + value);
            values.add(value);
        }
        return values;
    }

    private static String trimmedOrNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid gnomAD population input profile: " + message);
    }

    private static final class FieldSpec {
        private final String metricId;
        private final String infoId;
        private final String number;
        private final String valueType;
        private final String unit;
        private final boolean required;

        private FieldSpec(String metricId,
                          String infoId,
                          String number,
                          String valueType,
                          String unit,
                          boolean required) {
            this.metricId = metricId;
            this.infoId = infoId;
            this.number = number;
            this.valueType = valueType;
            this.unit = unit;
            this.required = required;
        }
    }

    public static final class Result {
        private final List<Map<String, String>> measurements;
        private final String inputQualityStatus;
        private final String inputReadinessStatus;
        private final List<String> reasonCodes;

        private Result(List<Map<String, String>> measurements,
                       String inputQualityStatus,
                       String inputReadinessStatus,
                       List<String> reasonCodes) {
            this.measurements = immutableMeasurements(measurements);
            this.inputQualityStatus = inputQualityStatus;
            this.inputReadinessStatus = inputReadinessStatus;
            this.reasonCodes = Collections.unmodifiableList(new ArrayList<String>(reasonCodes));
        }

        public List<Map<String, String>> getMeasurements() {
            return measurements;
        }

        public String getInputQualityStatus() {
            return inputQualityStatus;
        }

        public String getInputReadinessStatus() {
            return inputReadinessStatus;
        }

        /** Same value as getInputReadinessStatus, with its E3-P0 record-level meaning explicit. */
        public String getRecordInputReadinessStatus() {
            return inputReadinessStatus;
        }

        public List<String> getReasonCodes() {
            return reasonCodes;
        }

        private static List<Map<String, String>> immutableMeasurements(
                List<Map<String, String>> values) {
            List<Map<String, String>> copied = new ArrayList<Map<String, String>>();
            for (Map<String, String> value : values) {
                copied.add(Collections.unmodifiableMap(new LinkedHashMap<String, String>(value)));
            }
            return Collections.unmodifiableList(copied);
        }
    }
}
