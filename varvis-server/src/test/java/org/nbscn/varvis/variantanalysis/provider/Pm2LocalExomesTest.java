package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic adoption tests are comparator/transport evidence, never EXOMES scientific approval. */
class Pm2LocalExomesTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final Bs1LocalExomesTest facts = new Bs1LocalExomesTest();
    static final String SOURCE = "a".repeat(64);

    ObjectNode policy(int index) {
        var policy = (ObjectNode) GnomadPopulationInputProfile.loadBusinessPm2Supporting().getDocument()
                .at("/evaluation/criteria/2/populationRule/policySnapshot/records/" + index).deepCopy();
        policy.put("policyId", "SYNTHETIC_LOCAL_PM2_" + index);
        var resource = Pm2LocalExomesProfile.load(mapper).document().path("resourceContract");
        var release = policy.putArray("resourceReleaseBindings").addObject();
        for (String key : List.of("resourceId", "resourceVersion", "identityFingerprintSha256")) release.set(key, resource.path(key));
        if (policy.has("maximumAlleleFrequency"))
            policy.withObject("/maximumAlleleFrequency").put("metricId", "gnomad.exomes.faf95max");
        if (policy.has("jointQualityRequirements")) policy.set("qualityRequirements", policy.remove("jointQualityRequirements"));
        policy.put("allowWellCoveredAbsence", false);
        return policy;
    }

    ObjectNode context(int index) throws Exception {
        var c = new Bs1CoreTest().context(); var p = policy(index);
        c.withObject("/gene").set("hgncId", p.at("/gene/hgncId")); c.withObject("/gene").put("symbol", index == 0 ? "VHL" : "SYNTHETIC");
        c.withObject("/disease").set("mondoId", p.at("/disease/mondoId")); c.set("moi", p.path("moi"));
        c.put("contextId", adapter.canonicalVersionedContextId(c)); return c;
    }

    Map<String, String> info(String ac, String an, String faf) {
        var values = facts.facts.info(faf, an);
        values.put("AC", ac); values.put("AN", an);
        values.put("AF", new java.math.BigDecimal(ac).divide(new java.math.BigDecimal(an), java.math.MathContext.DECIMAL64).toPlainString());
        return values;
    }

    ObjectNode observation(ObjectNode c, Map<String, String> values, String filter) {
        var shared = facts.shared(c, values, filter);
        return PopulationObservation.seal(mapper, (ObjectNode) shared.path("evidence"), shared.path("rawPopulationRecord").asText(),
                GnomadPopulationInputProfile.loadLocalBa1(), SOURCE, c.path("dataClass").asText(), "SYNTHETIC_FIXTURE");
    }

    ObjectNode run(ObjectNode c, ObjectNode observation, ObjectNode policy) {
        return new GnomadPopulationCriterionEvaluator(mapper).evaluatePm2LocalExomes(c, observation,
                Pm2LocalExomesProfile.synthetic(mapper, List.of(policy)), SOURCE);
    }

    @ParameterizedTest @CsvSource({"1,2000,MET", "2,2000,NOT_MET", "1,1999,NOT_EVALUATED"})
    void alleleCountComparatorKeepsInclusiveBoundaryAndSampleSize(String ac, String an, String status) throws Exception {
        var c = context(9); var o = observation(c, info(ac, an, "0.000001"), "PASS");
        var r = run(c, o, policy(9));
        assertEquals(status, r.at("/evaluationResult/status").asText(), r.toPrettyString());
        assertEquals("MET".equals(status) ? "SUPPORTING" : "NONE", r.path("appliedStrength").asText());
        assertFalse(o.toString().contains("gnomad.joint."));
    }

    @ParameterizedTest @CsvSource({"0.00000155,MET", "0.00000156,MET", "0.00000157,NOT_MET"})
    void fafUsesOriginalExactDecimalComparator(String faf, String status) throws Exception {
        var c = context(0); var r = run(c, observation(c, info("100", "10000", faf), "PASS"), policy(0));
        assertEquals(status, r.at("/evaluationResult/status").asText(), r.toPrettyString());
    }

    @Test void pendingProductionPoliciesNeverBorrowSyntheticOrJointAdoption() throws Exception {
        var production = Pm2LocalExomesProfile.load(mapper); var c = context(9);
        assertEquals(12, production.document().path("policyInventory").size());
        assertEquals(0, production.document().at("/counts/APPLICABLE").asInt());
        var o = observation(c, info("1", "2000", "0.000001"), "PASS");
        var r = new GnomadPopulationCriterionEvaluator(mapper).evaluatePm2LocalExomes(c, o, production, SOURCE);
        assertEquals("PM2_LOCAL_EXOMES_POLICY_REVIEW_REQUIRED", r.at("/evaluationResult/reasonCode").asText());
        c.put("dataClass", "PRIVACY_BOUNDED_RUNTIME"); c.put("contextId", adapter.canonicalVersionedContextId(c));
        r = run(c, observation(c, info("1", "2000", "0.000001"), "PASS"), policy(9));
        assertEquals("PM2_SYNTHETIC_POLICY_RUNTIME_FORBIDDEN", r.at("/evaluationResult/reasonCode").asText());
    }

    @Test void rawFilterCountsMissingStatisticsAndAncestryAreNotNotMet() throws Exception {
        var c = context(0);
        for (String fault : List.of("FILTER", "AN", "AF", "FAF", "ANCESTRY", "RESOURCE")) {
            var values = info("100", "10000", "0.000001"); var p = policy(0);
            switch (fault) {
                case "AN" -> values.remove("AN");
                case "AF" -> values.put("AF", "0.9");
                case "FAF" -> values.remove("faf95_nfe");
                case "ANCESTRY" -> values.put("fafmax_faf95_max_gen_anc", "fin");
                case "RESOURCE" -> p.withArray("resourceReleaseBindings").removeAll();
            }
            var r = run(c, observation(c, values, "FILTER".equals(fault) ? "AC0" : "PASS"), p);
            assertEquals("NOT_EVALUATED", r.at("/evaluationResult/status").asText(), fault + r.toPrettyString());
        }
    }

    @Test void noRecordAndFailureRetainDifferentAxesWithoutAllSites() throws Exception {
        var c = context(9);
        for (String status : List.of("NO_RECORD", "NOT_QUERIED")) {
            var carrier = facts.facts.baseCarrier(c);
            carrier.withObject("/source").putArray("sourceRecords");
            carrier.withObject("/queryOutcome").put("status", status).put("recordCount", 0);
            if ("NOT_QUERIED".equals(status)) {
                carrier.withObject("/queryOutcome").remove("queryFingerprint");
                carrier.withObject("/resourceExecution").put("status", "FAILED").put("reasonCode", "SYNTHETIC_FAILURE");
                carrier.withObject("/resourceAvailability").put("status", "UNKNOWN").put("reasonCode", "NOT_CHECKED");
                carrier.withObject("/resourceApplicability").put("status", "UNKNOWN").put("reasonCode", "NOT_EVALUATED");
            }
            var o = PopulationObservation.seal(mapper, carrier, "", GnomadPopulationInputProfile.loadLocalBa1(), SOURCE, "SYNTHETIC", "SYNTHETIC_FIXTURE");
            var r = run(c, o, policy(9));
            assertEquals("NO_RECORD".equals(status) ? "PM2_LOCAL_ALL_SITES_DETECTION_PROOF_MISSING" : "RESOURCE_EXECUTION_NOT_SUCCEEDED",
                    r.at("/evaluationResult/reasonCode").asText());
        }
    }

    @Test void forgedRawWrongSourceAndWrongProfileAreRejected() throws Exception {
        var c = context(9); var o = observation(c, info("1", "2000", "0.000001"), "PASS");
        var changed = o.deepCopy().put("rawRecord", o.path("rawRecord").asText().replace("PASS", "AC0"));
        assertThrows(IllegalArgumentException.class, () -> run(c, changed, policy(9)));
        assertThrows(IllegalArgumentException.class, () -> new GnomadPopulationCriterionEvaluator(mapper)
                .evaluatePm2LocalExomes(c, o, Pm2LocalExomesProfile.load(mapper), "b".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> PopulationObservation.verify(mapper, o, c, SOURCE,
                GnomadPopulationInputProfile.loadBusinessPm2Supporting()));
        assertEquals(o, PopulationObservation.restoreSealed(mapper, mapper.readTree(mapper.writeValueAsBytes(o)), c, SOURCE,
                GnomadPopulationInputProfile.loadLocalBa1()));
    }

    @Test void multiAltRawSelectsOnlyTheRequestedAlleleAndSpecialPloidyRemainsUnsupported() throws Exception {
        var c = context(9); var q = c.at("/variant/evidenceQueryAllele");
        var values = Map.of("AC", "9,1", "AN", "2000", "AF", "0.0045,0.0005", "nhomalt", "0,0");
        var carrier = facts.facts.baseCarrier(c); var factProfile = GnomadPopulationInputProfile.loadLocalBa1();
        carrier.set("measurements", mapper.valueToTree(factProfile.parse("PASS", values, 1, 2).getMeasurements()));
        String otherAlt = "T".equals(q.path("alternate").asText()) ? "A" : "T";
        String raw = "chr" + q.path("contig").asText() + "\t" + q.path("position").asText() + "\t.\t" + q.path("reference").asText()
                + "\t" + otherAlt + "," + q.path("alternate").asText() + "\t.\tPASS\tAC=9,1;AN=2000;AF=0.0045,0.0005;nhomalt=0,0";
        var o = PopulationObservation.seal(mapper, carrier, raw, factProfile, SOURCE, "SYNTHETIC", "SYNTHETIC_MULTIALT");
        assertEquals("MET", run(c, o, policy(9)).at("/evaluationResult/status").asText());
        assertEquals(raw, o.path("rawRecord").asText());
        var x = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter,
                RuntimeEvidenceV2ProviderWiringTest.key("GRCh38", "X", 100, "C", "A"));
        var r = run(x, observation(x, info("1", "2000", "0.000001"), "PASS"), policy(9));
        assertEquals("PM2_LOCAL_VARIANT_SCOPE_UNSUPPORTED", r.at("/evaluationResult/reasonCode").asText());
    }

    ObjectNode adoption() {
        var base = Pm2LocalExomesProfile.load(mapper).document();
        var source = base.at("/policyInventory/9/sourcePolicy");
        var release = mapper.createObjectNode().put("schemaVersion", "pm2-local-exomes-adoption-v1")
                .put("snapshotId", "SYNTHETIC_REVIEW_FIXTURE").put("version", "1").put("purpose", "SYNTHETIC_TEST_ONLY");
        for (String field : List.of("sourcePolicyProfileSha256", "sourcePackSha256", "factProfileSha256", "resourceContract"))
            release.set(field, base.path(field));
        var row = release.putArray("records").addObject().put("sourcePolicyId", source.path("policyId").asText())
                .put("sourcePolicySha256", adapter.canonicalSha256(source))
                .put("authoritySnapshotSha256", source.at("/authority/snapshotSha256").asText())
                .put("policyId", "SYNTHETIC_LOADED_PM2").put("version", "1")
                .put("scope", "AUTOSOMAL_GRCH38_SMALL_VARIANT_HIT_MAX_50_BASES");
        var review = row.putObject("review").put("status", "APPROVED_FOR_LOCAL_EXOMES_HIT")
                .put("reviewId", "SYNTHETIC_ONLY").put("reviewedBy", "SYNTHETIC_TEST")
                .put("reviewedAt", "2026-10-10T00:00:00Z").put("rationale", "SYNTHETIC parser test; no real review or scientific adoption.");
        var checks = review.putObject("checks");
        for (String check : List.of("sourceScope", "datasetSemantics", "statisticSemantics", "qualityRequirements", "additionalConditions"))
            checks.put(check, "APPROVED");
        review.putObject("sourceRef").put("url", source.at("/authority/sourceUrl").asText())
                .put("sha256", source.at("/authority/snapshotSha256").asText());
        return release;
    }

    Pm2LocalExomesProfile load(ObjectNode release) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(release); var file = temporary.resolve("synthetic-adoption.json");
        java.nio.file.Files.write(file, bytes);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        return Pm2LocalExomesProfile.loadReviewed(mapper, file, sha);
    }

    @Test void trustedLoaderRetainsOriginalRuleAndSyntheticReviewCannotBecomeRuntimeApproval() throws Exception {
        var loaded = load(adoption()); var c = context(9);
        assertTrue(loaded.document().path("syntheticOnly").asBoolean());
        assertEquals(1, loaded.document().at("/counts/APPLICABLE").asInt());
        var p = loaded.select(c).path("policy");
        assertEquals("1", p.path("maximumAlleleCount").asText());
        assertEquals("2000", p.path("minimumObservedAlleleNumber").asText());
        var evaluator = new GnomadPopulationCriterionEvaluator(mapper);
        assertEquals("MET", evaluator.evaluatePm2LocalExomes(c, observation(c, info("1", "2000", "0.000001"), "PASS"), loaded, SOURCE)
                .at("/evaluationResult/status").asText());
        c.put("dataClass", "PRIVACY_BOUNDED_RUNTIME"); c.put("contextId", adapter.canonicalVersionedContextId(c));
        assertEquals("PM2_SYNTHETIC_POLICY_RUNTIME_FORBIDDEN", loaded.select(c).path("reasonCode").asText());
    }

    @Test void reviewLoaderRejectsUnreviewedUnknownDuplicateModifiedSourceAndUnpinnedBytes() throws Exception {
        for (String fault : List.of("UNKNOWN", "THRESHOLD", "REVIEW", "CHECK", "SOURCE", "RESOURCE", "DUPLICATE", "PURPOSE")) {
            var a = adoption(); var row = (ObjectNode) a.path("records").get(0);
            switch (fault) {
                case "UNKNOWN" -> a.put("enabled", true);
                case "THRESHOLD" -> row.put("maximumAlleleCount", "99");
                case "REVIEW" -> row.withObject("/review").put("status", "UNREVIEWED");
                case "CHECK" -> row.withObject("/review/checks").remove("additionalConditions");
                case "SOURCE" -> row.put("sourcePolicySha256", "b".repeat(64));
                case "RESOURCE" -> a.put("factProfileSha256", "b".repeat(64));
                case "DUPLICATE" -> a.withArray("records").add(row.deepCopy());
                case "PURPOSE" -> a.remove("purpose");
            }
            assertThrows(IllegalArgumentException.class, () -> load(a), fault);
        }
        load(adoption());
        assertThrows(IllegalArgumentException.class, () -> Pm2LocalExomesProfile.loadReviewed(mapper,
                temporary.resolve("synthetic-adoption.json"), "b".repeat(64)));
        var file = temporary.resolve("duplicate-json.json");
        byte[] bytes = "{\"schemaVersion\":\"pm2-local-exomes-adoption-v1\",\"schemaVersion\":\"duplicate\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Files.write(file, bytes);
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThrows(IllegalArgumentException.class, () -> Pm2LocalExomesProfile.loadReviewed(mapper, file, sha));
    }
}
