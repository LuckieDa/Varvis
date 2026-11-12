package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

/** PM2 adoption is separate from the shared EXOMES fact parser and the historical JOINT policies. */
public final class Pm2LocalExomesProfile {
    public static final String VERSION = "pm2-local-exomes-v1";
    public static final String PROFILE_VERSION = "1.3.1";
    static final String WHOLE_DATABASE_GAP = "PM2_LOCAL_WHOLE_DATABASE_HOMOZYGOTE_PROOF_MISSING";
    private final ObjectMapper mapper;
    private final ObjectNode document;
    private final boolean synthetic;
    private final GnomadPopulationInputProfile facts = GnomadPopulationInputProfile.loadLocalBa1();

    public static Pm2LocalExomesProfile load(ObjectMapper mapper) {
        return new Pm2LocalExomesProfile(mapper, List.of(), false, null);
    }

    /** The path and expected byte SHA must come from trusted application configuration, never a submission. */
    public static Pm2LocalExomesProfile loadReviewed(ObjectMapper mapper, Path path, String expectedByteSha256) {
        try {
            if (path == null || !path.isAbsolute() || path.toString().startsWith("\\\\") || path.toString().contains("://")
                    || !Files.isRegularFile(path) || Files.size(path) > 2_097_152
                    || expectedByteSha256 == null || !expectedByteSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("PM2_ADOPTION_SOURCE_REQUIRED");
            byte[] bytes;
            try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(2_097_153); }
            if (bytes.length > 2_097_152) throw new IllegalArgumentException("PM2_ADOPTION_SOURCE_TOO_LARGE");
            String actual = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equals(expectedByteSha256)) throw new IllegalArgumentException("PM2_ADOPTION_SOURCE_SHA_MISMATCH");
            var base = load(mapper); var parsed = mapper.reader()
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(bytes);
            if (!(parsed instanceof ObjectNode release) || !"pm2-local-exomes-adoption-v1".equals(parsed.path("schemaVersion").asText()))
                throw new IllegalArgumentException("PM2_ADOPTION_SCHEMA_INVALID");
            onlyFields(release, "schemaVersion", "snapshotId", "version", "purpose", "sourcePolicyProfileSha256", "sourcePackSha256",
                    "factProfileSha256", "resourceContract", "records", "projectScope");
            boolean syntheticRelease = "SYNTHETIC_TEST_ONLY".equals(release.path("purpose").asText());
            boolean projectLocal = "PROJECT_LOCAL_EXOMES_HIT".equals(release.path("purpose").asText());
            if (!syntheticRelease && !projectLocal && !"REVIEWED_LOCAL_EXOMES_HIT".equals(release.path("purpose").asText()))
                throw new IllegalArgumentException("PM2_ADOPTION_PURPOSE_REQUIRED");
            if (projectLocal ? !projectLocalScope(mapper).equals(release.path("projectScope")) : release.has("projectScope"))
                throw new IllegalArgumentException("PM2_PROJECT_LOCAL_SCOPE_INVALID");
            for (String field : List.of("sourcePolicyProfileSha256", "sourcePackSha256", "factProfileSha256", "resourceContract"))
                if (!base.document.path(field).equals(release.path(field))) throw new IllegalArgumentException("PM2_ADOPTION_BINDING_MISMATCH:" + field);
            for (String field : List.of("snapshotId", "version")) requireText(release, field);
            if (!release.path("records").isArray()) throw new IllegalArgumentException("PM2_ADOPTION_RECORDS_REQUIRED");
            var policies = new java.util.ArrayList<ObjectNode>(); var ids = new java.util.HashSet<String>();
            for (var row : release.path("records")) {
                onlyFields(row, "sourcePolicyId", "sourcePolicySha256", "authoritySnapshotSha256", "policyId", "version", "scope", "review");
                JsonNode source = null;
                for (var item : base.document.path("policyInventory"))
                    if (item.at("/sourcePolicy/policyId").equals(row.path("sourcePolicyId"))) source = item.path("sourcePolicy");
                if (source == null || !base.hash(source).equals(row.path("sourcePolicySha256").asText())
                        || !source.at("/authority/snapshotSha256").equals(row.path("authoritySnapshotSha256")))
                    throw new IllegalArgumentException("PM2_ADOPTION_SOURCE_POLICY_MISMATCH");
                requireText(row, "policyId"); requireText(row, "version");
                if (!ids.add(row.path("policyId").asText()) || !ids.add("source:" + source.path("policyId").asText()))
                    throw new IllegalArgumentException("PM2_ADOPTION_POLICY_DUPLICATE");
                var review = row.path("review");
                onlyFields(review, "status", "reviewId", "reviewedBy", "reviewedAt", "rationale", "checks", "sourceRef");
                onlyFields(review.path("checks"), "sourceScope", "datasetSemantics", "statisticSemantics", "qualityRequirements", "additionalConditions");
                onlyFields(review.path("sourceRef"), "url", "sha256");
                if (!(projectLocal ? "ACCEPTED_FOR_PROJECT_LOCAL_USE" : "APPROVED_FOR_LOCAL_EXOMES_HIT").equals(review.path("status").asText())
                        || !"AUTOSOMAL_GRCH38_SMALL_VARIANT_HIT_MAX_50_BASES".equals(row.path("scope").asText()))
                    throw new IllegalArgumentException("PM2_ADOPTION_REVIEW_REQUIRED");
                for (String field : List.of("reviewId", "reviewedBy", "reviewedAt", "rationale")) requireText(review, field);
                java.time.Instant.parse(review.path("reviewedAt").asText());
                for (String check : List.of("sourceScope", "datasetSemantics", "statisticSemantics", "qualityRequirements", "additionalConditions"))
                    if (!(projectLocal ? "CHECKED_FOR_PROJECT_LOCAL_SCOPE" : "APPROVED").equals(review.path("checks").path(check).asText()))
                        throw new IllegalArgumentException("PM2_ADOPTION_CHECK_REQUIRED:" + check);
                if (!source.at("/authority/sourceUrl").equals(review.at("/sourceRef/url"))
                        || !source.at("/authority/snapshotSha256").equals(review.at("/sourceRef/sha256")))
                    throw new IllegalArgumentException("PM2_ADOPTION_REVIEW_SOURCE_MISMATCH");
                // A review label cannot turn a subset observation into whole-database absence.
                if (!syntheticRelease && source.has("maximumHomozygoteCount"))
                    throw new IllegalArgumentException(WHOLE_DATABASE_GAP);
                var p = ((ObjectNode) source).deepCopy();
                p.put("sourcePolicyId", source.path("policyId").asText()).put("sourcePolicySha256", base.hash(source))
                        .put("policyId", row.path("policyId").asText()).put("version", row.path("version").asText())
                        .put("allowWellCoveredAbsence", false);
                // These fixed field mappings become legal only under the pinned resource/statistic review above.
                if (p.has("maximumAlleleFrequency")) {
                    if (!"gnomad.joint.faf95max".equals(p.at("/maximumAlleleFrequency/metricId").asText()))
                        throw new IllegalArgumentException("PM2_ADOPTION_STATISTIC_UNSUPPORTED");
                    p.withObject("/maximumAlleleFrequency").put("metricId", "gnomad.exomes.faf95max");
                }
                if (p.has("jointQualityRequirements")) {
                    p.set("qualityRequirements", p.remove("jointQualityRequirements"));
                    p.withObject("/qualityRequirements").put("ancestrySelection", "GNOMAD_EXOMES_FAF95MAX");
                }
                p.remove("allSitesCallability"); p.set("adoptionReview", review.deepCopy());
                if (!syntheticRelease) {
                    var qualification = base.qualify(source);
                    qualification.put("status", projectLocal ? "PROJECT_LOCAL_SCOPE_ADOPTED" : "REVIEWED_LOCAL_SCOPE_ONLY").remove("reasonCode");
                    qualification.put("explanation", "已绑定显式本地采用回执，仅适用 EXOMES HIT；不代表 JOINT、全库未见或临床批准。");
                    p.set("localQualification", qualification);
                    // The original whole-gnomAD no-FAF permission is not proved by an EXOMES singleton.
                    p.remove("missingFafPolicy");
                    p.put("missingFafResult", "PM2_LOCAL_NO_FAF_SCOPE_UNSUPPORTED");
                }
                var binding = p.putArray("resourceReleaseBindings").addObject();
                for (String field : List.of("resourceId", "resourceVersion", "identityFingerprintSha256"))
                    binding.set(field, base.document.path("resourceContract").path(field));
                policies.add(p);
            }
            var receipt = mapper.createObjectNode().put("byteSha256", actual);
            receipt.set("release", release);
            return new Pm2LocalExomesProfile(mapper, policies, syntheticRelease, receipt);
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException | java.time.format.DateTimeParseException invalid) {
            throw new IllegalArgumentException("PM2_ADOPTION_SOURCE_INVALID", invalid);
        }
    }

    // Exercises the comparator without manufacturing a reviewed production release.
    static Pm2LocalExomesProfile synthetic(ObjectMapper mapper, List<ObjectNode> policies) {
        return new Pm2LocalExomesProfile(mapper, policies, true, null);
    }

    private Pm2LocalExomesProfile(ObjectMapper mapper, List<ObjectNode> adopted, boolean synthetic, ObjectNode receipt) {
        this.mapper = java.util.Objects.requireNonNull(mapper);
        this.synthetic = synthetic;
        var old = GnomadPopulationInputProfile.loadBusinessPm2Supporting();
        var snapshot = old.getDocument().at("/evaluation/criteria/2/populationRule/policySnapshot");
        document = mapper.createObjectNode().put("schemaVersion", VERSION).put("profileId", "PM2_LOCAL_EXOMES_V1")
                .put("profileVersion", PROFILE_VERSION).put("dataset", "EXOMES").put("release", "4.1")
                .put("factProfileSha256", facts.getProfileSha256()).put("sourcePolicyProfileSha256", old.getProfileSha256())
                .put("sourcePackSha256", snapshot.path("sourcePackSha256").asText())
                .put("clinicalAdoption", "NOT_ESTABLISHED").put("syntheticOnly", synthetic)
                .put("allSitesAvailable", false).put("remoteQueriesEnabled", false);
        document.putObject("localQualityRequirements").put("rawFilter", "PASS")
                .put("rejectFailIntervalQcFlag", true);
        document.set("resourceContract", facts.getDocument().path("resources").get(0));
        document.set("registryBinding", old.getDocument().path("registryBinding"));
        document.set("knownSpecificationScopes", snapshot.path("knownSpecificationScopes"));
        if (receipt != null) {
            document.set("adoptionReceipt", receipt.deepCopy());
            if (receipt.at("/release/projectScope").isObject())
                document.set("projectScope", receipt.at("/release/projectScope").deepCopy());
        }
        var inventory = document.putArray("policyInventory");
        for (var policy : snapshot.path("records")) {
            var row = inventory.addObject().put("status", "REVIEW_REQUIRED")
                    .put("reasonCode", "PM2_LOCAL_EXOMES_POLICY_REVIEW_REQUIRED")
                    .put("sourcePolicySha256", hash(policy));
            row.set("sourcePolicy", policy.deepCopy());
            row.set("localQualification", qualify(policy));
            var missing = row.putArray("missingRequirements");
            missing.add("EXOMES_RESOURCE_SCOPE_ADOPTION");
            if (policy.has("maximumAlleleFrequency")) missing.add("EXOMES_FAF95_STATISTIC_COMPATIBILITY");
            if (policy.has("maximumHomozygoteCount")) missing.add("WHOLE_DATABASE_HOMOZYGOTE_SCOPE");
            if (policy.has("maximumAlleleCount")) missing.add("POPULATION_DATABASE_ALLELE_COUNT_SCOPE");
            row.put("absenceStatus", "MISSING_LOCAL_ALL_SITES_DETECTION_PROOF");
        }
        var active = document.putArray("adoptedPolicies");
        adopted.forEach(p -> active.add(p.deepCopy()));
        int pending = 0;
        for (var row : inventory) {
            boolean adoptedHere = adopted.stream().anyMatch(p -> p.path("sourcePolicyId").equals(row.at("/sourcePolicy/policyId")));
            if (adoptedHere) {
                var activeRow = (ObjectNode) row;
                activeRow.put("status", "APPLICABLE").remove("reasonCode"); activeRow.putArray("missingRequirements");
                adopted.stream().filter(p -> p.path("sourcePolicyId").equals(row.at("/sourcePolicy/policyId")))
                        .filter(p -> p.has("localQualification")).findFirst()
                        .ifPresent(p -> activeRow.set("localQualification", p.path("localQualification").deepCopy()));
            }
            else pending++;
        }
        document.putObject("counts").put("APPLICABLE", active.size()).put("REVIEW_REQUIRED", pending)
                .put("MISSING_LOCAL_ALL_SITES", inventory.size());
        long blocked = 0;
        for (var policy : snapshot.path("records")) if (policy.has("maximumHomozygoteCount")) blocked++;
        document.withObject("/counts").put("LOCAL_DATA_SCOPE_BLOCKED", blocked)
                .put("LOCAL_REVIEW_CANDIDATES", inventory.size() - blocked);
    }

    public ObjectNode document() { return document.deepCopy(); }
    public String sha256() { return hash(document); }
    public GnomadPopulationInputProfile factProfile() { return facts; }
    String calibration() { return facts.getProfileId() + "@" + facts.getProfileVersion(); }

    private Pm2LocalExomesProfile(ObjectMapper mapper, ObjectNode document, boolean synthetic) {
        this.mapper = mapper; this.document = document; this.synthetic = synthetic;
    }

    /** Explicit local-only adoption; specialist restrictions and the existing HIT policies remain authoritative. */
    public Pm2LocalExomesProfile withLocalAbsence(ObjectNode resourceBinding, boolean generalAbsence) {
        var copy = new Pm2LocalExomesProfile(mapper, document.deepCopy(), synthetic);
        copy.document.set("allSitesBinding", resourceBinding.deepCopy());
        copy.document.put("allSitesAvailable", true).put("generalAbsenceEnabled", generalAbsence);
        copy.document.set("projectScope", projectAbsenceScope(mapper));
        for (var active : copy.document.path("adoptedPolicies")) {
            // Only reinstate an absence branch that already exists in that exact source policy.
            for (var row : copy.document.path("policyInventory")) {
                var source = row.path("sourcePolicy");
                if (source.path("policyId").equals(active.path("sourcePolicyId")) && source.path("allowWellCoveredAbsence").asBoolean(false)) {
                    ((ObjectNode) active).put("allowWellCoveredAbsence", true);
                    ((ObjectNode) active).set("allSitesCallability", source.path("allSitesCallability").deepCopy());
                    var qualification = ((ObjectNode) active).withObject("/localQualification");
                    qualification.put("absenceSupported", true).remove("absenceReasonCode");
                    qualification.put("explanation", "本地 HIT 采用保持；当前 EXOMES all-sites 补齐原政策允许的 SNV 未检出条件，仍不代表全库或临床批准。");
                    ((ObjectNode) row).put("absenceStatus", "LOCAL_EXOMES_DETECTION_AVAILABLE");
                }
            }
        }
        int absencePolicies = 0;
        for (var row : copy.document.path("policyInventory")) {
            var inventory = (ObjectNode) row;
            if ("LOCAL_EXOMES_DETECTION_AVAILABLE".equals(row.path("absenceStatus").asText())) {
                absencePolicies++;
                inventory.withObject("/localQualification").put("absenceSupported", true);
            } else inventory.put("absenceStatus", row.at("/sourcePolicy/allowWellCoveredAbsence").asBoolean(false)
                    ? "LOCAL_POLICY_ADOPTION_REQUIRED" : "SOURCE_POLICY_DOES_NOT_PERMIT_ABSENCE");
        }
        copy.document.withObject("/counts").put("MISSING_LOCAL_ALL_SITES", 0).put("LOCAL_ABSENCE_POLICIES", absencePolicies);
        if (generalAbsence) {
            var policy = mapper.createObjectNode().put("policyId", "PROJECT_LOCAL_EXOMES_ABSENCE_V1").put("version", "1.0.0")
                    .put("route", "GENERAL_ABSENCE_ONLY").put("allowWellCoveredAbsence", true)
                    .put("purpose", "PROJECT_LOCAL_EXOMES_ONLY").put("ancestryMatchedConclusion", false)
                    .put("clinicalAdoption", "NOT_ESTABLISHED").put("scope", "AUTOSOMAL_GRCH38_SNV");
            policy.putArray("sourceUrls").add("https://www.nature.com/articles/gim201530")
                    .add("https://www.clinicalgenome.org/docs/pm2-recommendation-for-absence-rarity/");
            // >1,000 diploid individuals in the original general recommendation; never a universal rarity AF cutoff.
            var callability = policy.putObject("allSitesCallability").put("satisfactionMode", "ANY_INDEPENDENT");
            callability.putArray("independentlySufficientDatasets").add("EXOMES");
            var exomes = callability.putObject("exomes");
            exomes.putObject("minimumAdjAlleleNumber").put("rawValue", "2000").put("comparisonOperator", "GREATER_THAN");
            exomes.set("excludedWhenAnyFlagsTrue", mapper.valueToTree(GnomadLocalAllSites.FLAGS));
            copy.document.set("generalAbsencePolicy", policy);
        }
        return copy;
    }

    public static ObjectNode projectAbsenceScope(ObjectMapper mapper) {
        return projectLocalScope(mapper).put("schemaVersion", "pm2-project-local-scope-v2")
                .put("allowedQueryOutcome", "HIT_OR_NO_RECORD").put("allowNoRecord", true);
    }

    public static boolean validProjectScope(ObjectMapper mapper, JsonNode scope) {
        return projectLocalScope(mapper).equals(scope) || projectAbsenceScope(mapper).equals(scope);
    }

    /** Explicit project limitation, not a statement of external VCEP approval or clinical equivalence. */
    public static ObjectNode projectLocalScope(ObjectMapper mapper) {
        return mapper.createObjectNode().put("schemaVersion", "pm2-project-local-scope-v1")
                .put("dataset", "EXOMES").put("release", "4.1").put("interpretation", "LOCAL_DATASET_CRITERION_ONLY")
                .put("wholeGnomadConclusion", false).put("clinicalAdoption", "NOT_ESTABLISHED")
                .put("externalSpecificationEquivalence", "NOT_ESTABLISHED").put("allowedQueryOutcome", "HIT")
                .put("allowNoRecord", false).put("allowMissingFafSingleton", false)
                .put("allowWholeDatabaseHomozygoteRequirement", false).put("reviewOwnership", "IMPLEMENTER_TECHNICAL_REVIEW");
    }

    /** A preparation artifact only. Neither this method nor its output grants adoption. */
    public ObjectNode adoptionDraft() {
        var draft = mapper.createObjectNode().put("schemaVersion", "pm2-local-exomes-adoption-v1")
                .put("snapshotId", "pm2-local-exomes-unreviewed-draft").put("version", "1.0.0")
                .put("purpose", "DRAFT_LOCAL_EXOMES_HIT");
        for (String field : List.of("sourcePolicyProfileSha256", "sourcePackSha256", "factProfileSha256", "resourceContract"))
            draft.set(field, document.path(field).deepCopy());
        var rows = draft.putArray("records");
        for (var item : document.path("policyInventory")) {
            if (item.at("/localQualification/status").asText().equals("DATA_INSUFFICIENT")) continue;
            var source = item.path("sourcePolicy");
            var row = rows.addObject().put("sourcePolicyId", source.path("policyId").asText())
                    .put("sourcePolicySha256", hash(source))
                    .put("authoritySnapshotSha256", source.at("/authority/snapshotSha256").asText())
                    .put("policyId", "LOCAL_EXOMES_" + source.path("policyId").asText()).put("version", "1.0.0")
                    .put("scope", "AUTOSOMAL_GRCH38_SMALL_VARIANT_HIT_MAX_50_BASES");
            var review = row.putObject("review").put("status", "UNREVIEWED")
                    .put("reviewId", "").put("reviewedBy", "").put("reviewedAt", "")
                    .put("rationale", "待核定：仅限本地 EXOMES 4.1 的精确 PASS HIT；保留源阈值，不能代表 JOINT、全库未见或临床批准。");
            var checks = review.putObject("checks");
            for (String field : List.of("sourceScope", "datasetSemantics", "statisticSemantics", "qualityRequirements", "additionalConditions"))
                checks.put(field, "PENDING");
            review.set("sourceRef", mapper.createObjectNode().put("url", source.at("/authority/sourceUrl").asText())
                    .put("sha256", source.at("/authority/snapshotSha256").asText()));
        }
        return draft;
    }

    private ObjectNode qualify(JsonNode policy) {
        boolean wholeDatabase = policy.has("maximumHomozygoteCount");
        var q = mapper.createObjectNode().put("schemaVersion", "pm2-local-qualification-v1")
                .put("status", wholeDatabase ? "DATA_INSUFFICIENT" : "REVIEW_REQUIRED")
                .put("reasonCode", wholeDatabase ? WHOLE_DATABASE_GAP : "PM2_LOCAL_EXOMES_POLICY_REVIEW_REQUIRED")
                .put("observedDataset", "EXOMES").put("observedRelease", "4.1")
                .put("sourcePolicySha256", hash(policy)).put("automaticAdoption", false)
                .put("absenceSupported", false).put("wholeDatabaseAbsenceProved", false)
                .put("noFafSingletonSupported", false);
        q.put("statistic", policy.has("maximumAlleleCount") ? "OBSERVED_ALLELE_COUNT_WITH_AN" : "GROUPMAX_FAF95");
        q.put("explanation", wholeDatabase
                ? "原条款要求整个 gnomAD 中未见纯合个体；外显子 nhomalt=0 只说明本子集，缺少其他数据集证明。"
                : "本地可提供该类条件的观测，但源条款在 EXOMES 子集中的适用范围仍待明确采用；不是已批准政策。");
        var requirements = q.putArray("requiredHitFacts");
        for (String field : List.of("EXACT_NORMALIZED_ALLELE", "RAW_FILTER_PASS", "CONSISTENT_AC_AN_AF", "PINNED_EXOMES_4_1_RESOURCE"))
            requirements.add(field);
        if (policy.has("maximumAlleleCount")) {
            requirements.add("SOURCE_MINIMUM_OBSERVED_AN").add("SOURCE_MAXIMUM_OBSERVED_AC")
                    .add("CONSISTENT_NHOMALT_COUNT");
            q.put("datasetLimitation", "不能将 EXOMES 中至多一个等位基因解释成所有人口数据库合计至多一个。");
        } else {
            requirements.add("SOURCE_FAF95_THRESHOLD").add("PERMITTED_SELECTED_ANCESTRY")
                    .add("SAME_ANCESTRY_AC_AN_AF_FAF95");
            q.put("datasetLimitation", "EXOMES FAF95 不等于 JOINT FAF95；不能用普通 AF 或置信上界替换。");
        }
        if (wholeDatabase) requirements.add("WHOLE_GNOMAD_HOMOZYGOTE_PROOF");
        q.put("absenceReasonCode", "PM2_LOCAL_ALL_SITES_DETECTION_PROOF_MISSING");
        return q;
    }

    public ObjectNode select(JsonNode context) {
        var selection = mapper.createObjectNode().put("schemaVersion", "pm2-local-exomes-selection-v1")
                .put("contextId", context.path("contextId").asText()).put("profileSha256", sha256());
        var matches = new java.util.ArrayList<JsonNode>();
        for (var p : document.path("adoptedPolicies")) if (matches(p, context)) matches.add(p);
        if (synthetic && !"SYNTHETIC".equals(context.path("dataClass").asText())) {
            selection.put("status", "BLOCKED").put("reasonCode", "PM2_SYNTHETIC_POLICY_RUNTIME_FORBIDDEN");
        } else if (matches.size() == 1) {
            selection.put("status", "APPLICABLE").put("adoptionScope", synthetic
                    ? "SYNTHETIC_COMPARATOR_TEST_ONLY" : document.has("projectScope")
                    ? "PROJECT_LOCAL_EXOMES_ONLY" : "TRUSTED_REVIEWED_LOCAL_EXOMES_HIT");
            selection.set("policy", matches.get(0).deepCopy());
        } else if (matches.size() > 1) {
            selection.put("status", "BLOCKED").put("reasonCode", "PM2_POLICY_AMBIGUOUS");
        } else {
            var pending = selection.putArray("pendingPolicies");
            for (var row : document.path("policyInventory")) if (matches(row.path("sourcePolicy"), context)) pending.add(row.deepCopy());
            var known = selection.putArray("knownSpecifications");
            for (var scope : document.path("knownSpecificationScopes"))
                if (scope.path("hgncId").equals(context.at("/gene/hgncId"))) known.add(scope.deepCopy());
            selection.put("status", pending.isEmpty() ? "MISSING" : "REVIEW_REQUIRED")
                    .put("reasonCode", !pending.isEmpty() ? "PM2_LOCAL_EXOMES_POLICY_REVIEW_REQUIRED"
                            : !known.isEmpty() ? "PM2_SPECIALIST_POLICY_REVIEW_REQUIRED" : "PM2_DISEASE_FREQUENCY_PARAMETERS_REQUIRED");
            if (pending.size() == 1) {
                var qualification = pending.get(0).path("localQualification");
                selection.set("localQualification", qualification.deepCopy());
                if ("DATA_INSUFFICIENT".equals(qualification.path("status").asText()))
                    selection.put("status", "BLOCKED").put("reasonCode", qualification.path("reasonCode").asText());
            }
            if (pending.isEmpty() && known.isEmpty() && document.path("generalAbsenceEnabled").asBoolean(false)) {
                selection.put("status", "APPLICABLE").put("adoptionScope", "PROJECT_LOCAL_EXOMES_ONLY").remove("reasonCode");
                selection.set("policy", document.path("generalAbsencePolicy").deepCopy());
            }
        }
        selection.put("selectionId", "cspec-selection-sha256-" + hash(selection));
        return selection;
    }

    private static boolean matches(JsonNode p, JsonNode c) {
        return p.at("/gene/hgncId").equals(c.at("/gene/hgncId"))
                && p.at("/disease/mondoId").equals(c.at("/disease/mondoId")) && p.path("moi").equals(c.path("moi"));
    }
    private String hash(JsonNode value) { return new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value); }
    private static void requireText(JsonNode value, String field) {
        if (!value.path(field).isTextual() || value.path(field).asText().isBlank())
            throw new IllegalArgumentException("PM2_ADOPTION_FIELD_REQUIRED:" + field);
    }
    private static void onlyFields(JsonNode value, String... allowed) {
        if (!value.isObject()) throw new IllegalArgumentException("PM2_ADOPTION_OBJECT_REQUIRED");
        var expected = java.util.Set.of(allowed);
        value.fieldNames().forEachRemaining(field -> {
            if (!expected.contains(field)) throw new IllegalArgumentException("PM2_ADOPTION_UNKNOWN_FIELD:" + field);
        });
    }
}
