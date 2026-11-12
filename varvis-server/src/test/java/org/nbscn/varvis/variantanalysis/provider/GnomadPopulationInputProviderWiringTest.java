package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GnomadPopulationInputProviderWiringTest {

    @Test
    void springRegistrationUsesConfiguredConstructorWithoutAnExplicitProfileBean() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(VariantAnalysisProperties.class, VariantAnalysisProperties::new)
                .withUserConfiguration(GnomadTabixProvider.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals("GNOMAD", context.getBean(GnomadTabixProvider.class).source());
                });
    }

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-01T08:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @Test
    void explicitPopulationInputUsesIndexedFullSitesBatchAndKeepsPassSeparateFromHit() throws Exception {
        Path full = indexedFile("gnomad.exomes.v4.1.sites.chr1.vcf.bgz");
        Path compact = indexedFile("gnomad.exomes.v4.1.max-af.chr1.tsv.gz");
        Path tabix = tabixScript("population-tabix.cmd",
                "echo x>> \"%~dp0count.txt\"\r\n"
                        + "echo %* > \"%~dp0args.txt\"\r\n"
                        + "echo " + vcf(100, "A", "G", "PASS", validInfo("1.2300e-7")) + "\r\n"
                        + "echo " + vcf(101, "C", "T", "AS_VQSR", validInfo("2.5e-7")) + "\r\n"
                        + "exit /b 0\r\n");
        GnomadTabixProvider provider = new GnomadTabixProvider(
                populationProperties(full, compact, tabix));
        NormalizedVariantKey passKey = key(100, "A", "G");
        NormalizedVariantKey filteredKey = key(101, "C", "T");

        Map<NormalizedVariantKey, List<EvidenceItem>> result = provider.annotate(
                Arrays.asList(variant(passKey), variant(filteredKey)),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(passKey));

        EvidenceItem pass = evidence(result, passKey);
        EvidenceItem filtered = evidence(result, filteredKey);
        assertEquals("HIT", pass.getDetails().get("queryOutcomeStatus"));
        assertEquals("PASS", pass.getDetails().get("populationInputQualityStatus"));
        assertEquals("READY", pass.getDetails().get("populationInputReadinessStatus"));
        assertEquals("HIT", filtered.getDetails().get("queryOutcomeStatus"));
        assertEquals("FAIL", filtered.getDetails().get("populationInputQualityStatus"));
        assertEquals("NOT_READY", filtered.getDetails().get("populationInputReadinessStatus"));
        assertTrue(reasonCodes(filtered).contains("FILTER_NOT_PASS"));
        assertEquals(1, countByte(tempDir.resolve("count.txt"), (byte) 'x'));
        String args = Files.readString(tempDir.resolve("args.txt"), StandardCharsets.UTF_8);
        assertTrue(args.contains("-R"));
        assertTrue(args.contains(full.getFileName().toString()));
        assertFalse(args.contains(compact.getFileName().toString()));
    }

    @Test
    void executableV2UsesOneProfilePinnedRemoteBatchAndKeepsExactAllelesDistinct() throws Exception {
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadExecutableV2();
        Path tabix = tabixScript("remote-joint-tabix.cmd",
                "echo x>> \"%~dp0remote-count.txt\"\r\n"
                        + "echo %* > \"%~dp0remote-args.txt\"\r\n"
                        + "echo " + vcf(150, "A", "G", "PASS", validJointInfo("5.5000e-2"))
                        + "\r\nexit /b 0\r\n");
        VariantAnalysisProperties properties = baseProperties(tabix);
        properties.getGnomad().setVersion(resourceVersion(profile));
        properties.getGnomad().setPopulationInputEnabled(true);
        properties.getGnomad().setTabixCacheMb(32);
        GnomadTabixProvider provider = new GnomadTabixProvider(properties, profile);
        NormalizedVariantKey matchedKey = key(150, "A", "G");
        NormalizedVariantKey differentAltKey = key(150, "A", "T");

        Map<NormalizedVariantKey, List<EvidenceItem>> result = provider.annotate(
                Arrays.asList(variant(matchedKey), variant(differentAltKey)),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(matchedKey));

        EvidenceItem matched = evidence(result, matchedKey);
        EvidenceItem differentAlt = evidence(result, differentAltKey);
        assertEquals("HIT", matched.getDetails().get("queryOutcomeStatus"));
        assertEquals("REMOTE_GNOMAD", matched.getDetails().get("source"));
        assertEquals("joint", matched.getDetails().get("sourceDataset"));
        assertEquals(profile.getPopulationResourceId(), matched.getDetails().get("populationResourceId"));
        assertEquals(Boolean.TRUE, matched.getDetails().get("criterionEvaluationEnabled"));
        assertMeasurement(providerMeasurements(matched), "gnomad.joint.faf95max",
                "5.5000e-2", "0.055");
        assertEquals("NO_RECORD", differentAlt.getDetails().get("queryOutcomeStatus"));
        assertNull(differentAlt.getRawScore());
        assertNull(differentAlt.getRawValue());
        assertFalse(differentAlt.getDetails().containsKey("populationMeasurements"));
        assertEquals(Boolean.TRUE, differentAlt.getDetails().get("criterionEvaluationEnabled"));
        assertEquals(1, countByte(tempDir.resolve("remote-count.txt"), (byte) 'x'));
        String args = Files.readString(tempDir.resolve("remote-args.txt"), StandardCharsets.UTF_8);
        assertTrue(args.contains("--cache 32"));
        assertTrue(args.contains("-R"));
        assertTrue(args.contains(profile.getRemoteJointUri("1")));

        provider.annotate(Collections.singletonList(variant(matchedKey)),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(matchedKey));
        assertEquals(1, countByte(tempDir.resolve("remote-count.txt"), (byte) 'x'));
    }

    @Test
    void executableV2RemoteFailureIsStructuredAndNeverBecomesNoRecord() throws Exception {
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadExecutableV2();
        Path tabix = tabixScript("remote-joint-failure.cmd", "exit /b 7\r\n");
        VariantAnalysisProperties properties = baseProperties(tabix);
        properties.getGnomad().setVersion(resourceVersion(profile));
        properties.getGnomad().setPopulationInputEnabled(true);
        NormalizedVariantKey key = key(151, "C", "T");

        EvidenceItem item = providerEvidence(new GnomadTabixProvider(properties, profile), key);

        assertEquals("UNAVAILABLE", item.getApplicability());
        assertEquals("REMOTE_GNOMAD", item.getDetails().get("source"));
        assertEquals("FAILED", item.getDetails().get("resourceExecutionStatus"));
        assertEquals("NOT_QUERIED", item.getDetails().get("queryOutcomeStatus"));
        assertNull(item.getRawScore());
        assertNull(item.getRawValue());
        assertFalse(item.getDetails().containsKey("populationMeasurements"));
    }

    @Test
    void failedGroupDoesNotDiscardIndependentSuccessAndFailedCacheCanRecover() throws Exception {
        var profile = GnomadPopulationInputProfile.loadExecutableV2();
        String successful = "echo " + vcf(150, "A", "G", "PASS", validJointInfo("5.5000e-2")) + "\r\nexit /b 0\r\n";
        Path tabix = tabixScript("groups.cmd", "echo %~5 | findstr /C:\"sites.chr2.\" >nul\r\n"
                + "if not errorlevel 1 (\r\necho timed out 1>&2\r\nexit /b 7\r\n)\r\n" + successful);
        var properties = baseProperties(tabix); properties.getGnomad().setPopulationInputEnabled(true);
        properties.getGnomad().setTabixCacheMb(32);
        properties.getGnomad().setVersion(resourceVersion(profile));
        var provider = new GnomadTabixProvider(properties, profile);
        var success = key(150, "A", "G");
        var failure = new NormalizedVariantKey("GRCh38", "2", 150, "A", "G");
        var rows = List.of(variant(failure), variant(success));
        var first = provider.annotate(rows, RuntimeEvidenceV2ProviderWiringTest.analysisContext(success));
        assertEquals("TIMEOUT", evidence(first, failure).getDetails().get("resourceExecutionStatus"));
        assertEquals("HIT", evidence(first, success).getDetails().get("queryOutcomeStatus"));
        assertEquals(2, provider.queryDiagnostics().size());
        Files.writeString(tabix, "@echo off\r\n" + successful.replace("chr1\t", "chr2\t"));
        var recovered = provider.annotate(rows, RuntimeEvidenceV2ProviderWiringTest.analysisContext(success));
        assertEquals("HIT", evidence(recovered, failure).getDetails().get("queryOutcomeStatus"));
        assertEquals(evidence(first, success), evidence(recovered, success));
        assertEquals(3, provider.queryDiagnostics().size(), "Success reused; timeout actually re-queried");
    }

    @Test
    void failedBatchSplitsOnceWithinOriginalBudgetAndPreservesEachOutcome() throws Exception {
        var profile = GnomadPopulationInputProfile.loadExecutableV2();
        Path tabix = tabixScript("split.cmd", "cd >> \"%~dp0cache-directories.txt\"\r\nset lines=0\r\nfor /f \"usebackq delims=\" %%a in (\"%~4\") do set /a lines+=1 >nul\r\n"
                + "if %lines% GTR 1 (\r\necho timed out 1>&2\r\nexit /b 7\r\n)\r\n"
                + "findstr /C:\"151\" \"%~4\" >nul\r\nif not errorlevel 1 (\r\necho child error 1>&2\r\nexit /b 7\r\n)\r\n"
                + "echo " + vcf(150, "A", "G", "PASS", validJointInfo("5.5000e-2")) + "\r\nexit /b 0\r\n");
        var properties = baseProperties(tabix); var g = properties.getGnomad();
        g.setPopulationInputEnabled(true); g.setTabixCacheMb(32); g.setRemoteMaxAttempts(2);
        g.setTimeout(java.time.Duration.ofSeconds(2)); g.setRemoteTotalTimeout(java.time.Duration.ofSeconds(4));
        Path indexCache = tempDir.resolve("split-index-cache");
        g.setRemoteIndexCacheDir(indexCache.toString());
        var provider = new GnomadTabixProvider(properties, profile);
        var success = key(150, "A", "G"); var failure = key(151, "A", "G");
        var values = provider.annotate(List.of(variant(failure), variant(success)), RuntimeEvidenceV2ProviderWiringTest.analysisContext(success));
        assertEquals("NOT_QUERIED", evidence(values, failure).getDetails().get("queryOutcomeStatus"));
        assertEquals("HIT", evidence(values, success).getDetails().get("queryOutcomeStatus"));
        var diagnostics = provider.queryDiagnostics(); assertEquals(3, diagnostics.size());
        assertEquals(List.of("PRIMARY", "SPLIT_RETRY", "SPLIT_RETRY"), diagnostics.stream().map(d -> d.get("retryPhase")).toList());
        assertTrue(diagnostics.stream().allMatch(d -> Long.valueOf(4000).equals(d.get("configuredGroupTotalTimeoutMillis"))));
        assertTrue((long) diagnostics.get(1).get("totalTimeoutMillis") <= 2000);
        List<String> directories = Files.readAllLines(tempDir.resolve("cache-directories.txt"));
        assertEquals(3, directories.size());
        for (String directory : directories) {
            assertEquals(indexCache.toRealPath(), Path.of(directory.strip()).toRealPath());
        }
    }

    @Test
    void defaultConstructorNeverOptsIntoTheRemoteJointTarget() throws Exception {
        Path tabix = tabixScript("default-path-must-not-query-remote.cmd",
                "echo x>> \"%~dp0default-remote-count.txt\"\r\nexit /b 0\r\n");
        VariantAnalysisProperties properties = baseProperties(tabix);
        properties.getGnomad().setPopulationInputEnabled(true);
        NormalizedVariantKey key = key(152, "G", "A");

        EvidenceItem item = providerEvidence(new GnomadTabixProvider(properties), key);

        assertEquals("UNAVAILABLE", item.getApplicability());
        assertEquals("NOT_RUN", item.getDetails().get("resourceExecutionStatus"));
        assertEquals("NOT_QUERIED", item.getDetails().get("queryOutcomeStatus"));
        assertFalse(Files.exists(tempDir.resolve("default-remote-count.txt")));
    }

    @Test
    void numberASelectionAndBigDecimalLexemesSurviveProviderCarrierAndExistingAdapter() throws Exception {
        Path full = indexedFile("gnomad.exomes.v4.1.sites.chr1.vcf.bgz");
        Path compact = indexedFile("gnomad.exomes.v4.1.max-af.chr1.tsv.gz");
        String info = multiAllelicInfo();
        Path tabix = tabixScript("multiallelic-tabix.cmd",
                "echo " + vcf(200, "A", "G,T", "PASS", info) + "\r\nexit /b 0\r\n");
        GnomadTabixProvider provider = new GnomadTabixProvider(
                populationProperties(full, compact, tabix));
        NormalizedVariantKey key = key(200, "A", "T");
        EvidenceItem item = providerEvidence(provider, key);

        assertNull(item.getRawScore(), "exact criterion input must not be reduced to a double score");
        assertEquals("READY", item.getDetails().get("populationInputReadinessStatus"));
        Map<String, Map<String, String>> providerMeasurements = providerMeasurements(item);
        assertMeasurement(providerMeasurements, "gnomad.exomes.ac", "5", "5");
        assertMeasurement(providerMeasurements, "gnomad.exomes.an", "200", "200");
        assertMeasurement(providerMeasurements, "gnomad.exomes.af",
                "0.123456789012345678901234567890", "0.12345678901234567890123456789");
        assertMeasurement(providerMeasurements, "gnomad.exomes.nhomalt", "1", "1");
        assertMeasurement(providerMeasurements, "gnomad.exomes.groupmax.ancestry", "nfe", "nfe");
        assertMeasurement(providerMeasurements, "gnomad.exomes.groupmax.ac", "4", "4");
        assertMeasurement(providerMeasurements, "gnomad.exomes.groupmax.an", "150", "150");
        assertMeasurement(providerMeasurements, "gnomad.exomes.groupmax.af",
                "0.026666666666666666666666666667", "0.026666666666666666666666666667");
        assertMeasurement(providerMeasurements, "gnomad.exomes.groupmax.nhomalt", "1", "1");
        assertMeasurement(providerMeasurements, "gnomad.exomes.faf95",
                "1.2300e-7", "0.000000123");
        assertMeasurement(providerMeasurements, "gnomad.exomes.faf95max",
                "1.5000e-7", "0.00000015");
        assertMeasurement(providerMeasurements, "gnomad.exomes.ancestry.nfe.ac", "4", "4");
        assertMeasurement(providerMeasurements, "gnomad.exomes.ancestry.nfe.an", "150", "150");
        assertMeasurement(providerMeasurements, "gnomad.exomes.ancestry.nfe.af",
                "0.026666666666666666666666666667", "0.026666666666666666666666666667");
        assertMeasurement(providerMeasurements, "gnomad.exomes.ancestry.nfe.nhomalt", "1", "1");
        assertMeasurement(providerMeasurements, "gnomad.exomes.ancestry.nfe.faf95",
                "1.4000e-7", "0.00000014");

        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadDefault();
        RuntimeEvidenceV2ProviderWiring wiring = wiring();
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                profile.getDocument(), profile.getFullSitesResourceId());
        ObjectNode atomic = wiring.annotateVariantProvider(
                context(key), fixedProvider(item), variant(key),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key), binding).get(0);
        Map<String, JsonNode> atomicMeasurements = atomicMeasurements(atomic);

        assertEquals(profile.getFullSitesResourceId(), atomic.path("source").path("sourceId").asText());
        assertEquals("HIT", atomic.path("queryOutcome").path("status").asText());
        assertEquals("0.123456789012345678901234567890",
                atomicMeasurements.get("gnomad.exomes.af").path("rawValue").asText());
        assertEquals("0.12345678901234567890123456789",
                atomicMeasurements.get("gnomad.exomes.af").path("normalizedValue").asText());
        assertEquals("0.000000123",
                atomicMeasurements.get("gnomad.exomes.faf95").path("normalizedValue").asText());
        assertNoCriterionResult(atomic);

        ObjectNode tamperedJson = mapper.valueToTree(item);
        ((ObjectNode) tamperedJson.path("details")).put(
                "populationResourceIdentityFingerprintSha256", "0".repeat(64));
        EvidenceItem tampered = mapper.treeToValue(tamperedJson, EvidenceItem.class);
        ObjectNode rejected = wiring.annotateVariantProvider(
                context(key), fixedProvider(tampered), variant(key),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key), binding).get(0);
        assertEquals("FAILED", rejected.path("resourceExecution").path("status").asText());
        assertEquals("NOT_QUERIED", rejected.path("queryOutcome").path("status").asText());
        assertTrue(rejected.path("measurements").isEmpty());
    }

    @Test
    void missingZeroAndInvalidFieldsRemainHitsButAreNotReady() throws Exception {
        Path full = indexedFile("gnomad.exomes.v4.1.sites.chr1.vcf.bgz");
        Path compact = indexedFile("gnomad.exomes.v4.1.max-af.chr1.tsv.gz");
        String missingAf = validInfo("1e-7").replace(";AF=1e-7", "");
        String zeroAn = validInfo("1e-7").replace("AN=200", "AN=0");
        String invalidAf = validInfo("1e-7").replace("AF=1e-7", "AF=not-a-decimal");
        Path tabix = tabixScript("invalid-tabix.cmd",
                "echo " + vcf(300, "A", "G", "PASS", missingAf) + "\r\n"
                        + "echo " + vcf(301, "A", "G", "PASS", zeroAn) + "\r\n"
                        + "echo " + vcf(302, "A", "G", "PASS", invalidAf) + "\r\n"
                        + "exit /b 0\r\n");
        GnomadTabixProvider provider = new GnomadTabixProvider(
                populationProperties(full, compact, tabix));
        NormalizedVariantKey missingKey = key(300, "A", "G");
        NormalizedVariantKey zeroKey = key(301, "A", "G");
        NormalizedVariantKey invalidKey = key(302, "A", "G");

        Map<NormalizedVariantKey, List<EvidenceItem>> result = provider.annotate(
                Arrays.asList(variant(missingKey), variant(zeroKey), variant(invalidKey)),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(missingKey));

        assertHitNotReady(evidence(result, missingKey), "REQUIRED_INFO_FIELD_MISSING");
        assertHitNotReady(evidence(result, zeroKey), "ALLELE_NUMBER_BELOW_PROFILE_MINIMUM");
        assertHitNotReady(evidence(result, invalidKey), "INVALID_NUMERIC_VALUE");
        assertNoCriterionResult(mapper.valueToTree(result));
    }

    @Test
    void noRecordNeverBecomesZeroFrequencyAndProducesNoAtomicMeasurements() throws Exception {
        Path full = indexedFile("gnomad.exomes.v4.1.sites.chr1.vcf.bgz");
        Path compact = indexedFile("gnomad.exomes.v4.1.max-af.chr1.tsv.gz");
        Path tabix = tabixScript("empty-tabix.cmd", "exit /b 0\r\n");
        GnomadTabixProvider provider = new GnomadTabixProvider(
                populationProperties(full, compact, tabix));
        NormalizedVariantKey key = key(400, "C", "T");
        EvidenceItem item = providerEvidence(provider, key);

        assertEquals("NO_RECORD", item.getDetails().get("queryOutcomeStatus"));
        assertEquals("NOT_READY", item.getDetails().get("populationInputReadinessStatus"));
        assertNull(item.getRawScore());
        assertNull(item.getRawValue());
        assertFalse(item.getDetails().containsKey("populationMeasurements"));

        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadDefault();
        RuntimeEvidenceV2ProviderWiring wiring = wiring();
        ObjectNode atomic = wiring.annotateVariantProvider(
                context(key), fixedProvider(item), variant(key),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key),
                wiring.bind(profile.getDocument(), profile.getFullSitesResourceId())).get(0);

        assertEquals("NO_RECORD", atomic.path("queryOutcome").path("status").asText());
        assertEquals(0, atomic.path("queryOutcome").path("recordCount").asInt());
        assertTrue(atomic.path("measurements").isEmpty());
        assertTrue(atomic.path("source").path("sourceRecords").isEmpty());
        assertNoCriterionResult(atomic);
    }

    @Test
    void ordinaryCompactModeIsExplicitlyRetrievalOnly() throws Exception {
        Path compact = indexedFile("gnomad.exomes.v4.1.max-af.chr1.tsv.gz");
        Path full = indexedFile("gnomad.exomes.v4.1.sites.chr1.vcf.bgz");
        Path tabix = tabixScript("compact-tabix.cmd",
                "echo chr1\t500\tA\tG\t0.010\t0.020\t1\t200\t0\texomes\tgnomAD v4.1\tPASS\r\n"
                        + "exit /b 0\r\n");
        VariantAnalysisProperties properties = baseProperties(tabix);
        properties.getGnomad().setPopulationInputEnabled(false);
        properties.getGnomad().setGrch38CompactFiles(compact.toString());
        properties.getGnomad().setGrch38Files(full.toString());
        GnomadTabixProvider provider = new GnomadTabixProvider(properties);
        NormalizedVariantKey key = key(500, "A", "G");

        EvidenceItem item = providerEvidence(provider, key);

        assertEquals("compact", item.getDetails().get("querySource"));
        assertEquals("NOT_READY", item.getDetails().get("populationInputReadinessStatus"));
        assertEquals(List.of("COMPACT_RETRIEVAL_ONLY"), reasonCodes(item));
        assertEquals(Boolean.FALSE, item.getDetails().get("criterionEvaluationEnabled"));
        assertEquals(Boolean.FALSE, item.getDetails().get("legacySuggestionEligible"));
        assertNoCriterionResult(mapper.valueToTree(item));

        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadDefault();
        RuntimeEvidenceV2ProviderWiring wiring = wiring();
        ObjectNode rejected = wiring.annotateVariantProvider(
                context(key), fixedProvider(item), variant(key),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key),
                wiring.bind(profile.getDocument(), profile.getFullSitesResourceId())).get(0);
        assertEquals("FAILED", rejected.path("resourceExecution").path("status").asText());
        assertEquals("NOT_QUERIED", rejected.path("queryOutcome").path("status").asText());
        assertTrue(rejected.path("measurements").isEmpty());
    }

    @Test
    void populationMisconfigurationAndCommandFailuresNeverBecomeNoRecord() throws Exception {
        Path full = indexedFile("gnomad.exomes.v4.1.sites.chr1.vcf.bgz");
        Path compact = indexedFile("gnomad.exomes.v4.1.max-af.chr1.tsv.gz");

        Path shouldNotRun = tabixScript("misconfigured-tabix.cmd",
                "echo x>> \"%~dp0should-not-run.txt\"\r\nexit /b 0\r\n");
        NormalizedVariantKey misconfiguredKey = key(550, "A", "G");
        EvidenceItem misconfigured = providerEvidence(new GnomadTabixProvider(
                populationProperties(compact, compact, shouldNotRun)), misconfiguredKey);
        assertPopulationFailure(misconfigured, misconfiguredKey, "FAILED");
        assertFalse(Files.exists(tempDir.resolve("should-not-run.txt")));

        Path failedTabix = tabixScript("failed-tabix.cmd", "exit /b 7\r\n");
        NormalizedVariantKey failedKey = key(551, "A", "G");
        EvidenceItem failed = providerEvidence(new GnomadTabixProvider(
                populationProperties(full, compact, failedTabix)), failedKey);
        assertPopulationFailure(failed, failedKey, "FAILED");

        Path timedOutTabix = tabixScript("timeout-tabix.cmd",
                "echo simulated lookup timed out 1>&2\r\nexit /b 7\r\n");
        NormalizedVariantKey timedOutKey = key(552, "A", "G");
        EvidenceItem timedOut = providerEvidence(new GnomadTabixProvider(
                populationProperties(full, compact, timedOutTabix)), timedOutKey);
        assertPopulationFailure(timedOut, timedOutKey, "TIMEOUT");
    }

    @Test
    void changingOneRawPopulationFieldChangesPayloadAndEvidenceIdentityOnly() throws Exception {
        Path full = indexedFile("gnomad.exomes.v4.1.sites.chr1.vcf.bgz");
        Path compact = indexedFile("gnomad.exomes.v4.1.max-af.chr1.tsv.gz");
        NormalizedVariantKey key = key(600, "G", "A");
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadDefault();
        RuntimeEvidenceV2ProviderWiring wiring = wiring();
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                profile.getDocument(), profile.getFullSitesResourceId());

        Path firstTabix = tabixScript("identity-one.cmd",
                "echo " + vcf(600, "G", "A", "PASS", validInfo("1.000e-7"))
                        + "\r\nexit /b 0\r\n");
        ObjectNode first = atomic(new GnomadTabixProvider(
                populationProperties(full, compact, firstTabix)), key, wiring, binding);

        Path secondTabix = tabixScript("identity-two.cmd",
                "echo " + vcf(600, "G", "A", "PASS", validInfo("1.001e-7"))
                        + "\r\nexit /b 0\r\n");
        ObjectNode second = atomic(new GnomadTabixProvider(
                populationProperties(full, compact, secondTabix)), key, wiring, binding);

        assertNotEquals(first.path("provenance").path("sourcePayloadSha256").asText(),
                second.path("provenance").path("sourcePayloadSha256").asText());
        assertNotEquals(first.path("evidenceId").asText(), second.path("evidenceId").asText());
        assertEquals(first.path("deduplicationKey").asText(), second.path("deduplicationKey").asText());
        assertNoCriterionResult(first);
        assertNoCriterionResult(second);
    }

    private VariantAnalysisProperties populationProperties(Path full, Path compact, Path tabix) {
        VariantAnalysisProperties properties = baseProperties(tabix);
        properties.getGnomad().setPopulationInputEnabled(true);
        properties.getGnomad().setGrch38Files(full.toString());
        properties.getGnomad().setGrch38CompactFiles(compact.toString());
        return properties;
    }

    private VariantAnalysisProperties baseProperties(Path tabix) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getGnomad().setEnabled(true);
        properties.getGnomad().setVersion(resourceVersion());
        properties.getGnomad().setTabixExecutable(tabix.toString());
        properties.getGnomad().setTabixCacheMb(0);
        return properties;
    }

    private String resourceVersion() {
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadDefault();
        return resourceVersion(profile);
    }

    private String resourceVersion(GnomadPopulationInputProfile profile) {
        for (JsonNode resource : profile.getDocument().path("resources")) {
            if (profile.getPopulationResourceId().equals(resource.path("resourceId").asText())) {
                return resource.path("resourceVersion").asText();
            }
        }
        throw new IllegalStateException("population resource identity is missing");
    }

    private RuntimeEvidenceV2ProviderWiring wiring() {
        return new RuntimeEvidenceV2ProviderWiring(mapper, adapter, FIXED_CLOCK);
    }

    private ObjectNode atomic(GnomadTabixProvider provider,
                              NormalizedVariantKey key,
                              RuntimeEvidenceV2ProviderWiring wiring,
                              RuntimeEvidenceV2ProviderWiring.ResourceBinding binding) throws Exception {
        return wiring.annotateVariantProvider(
                context(key), provider, variant(key),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key), binding).get(0);
    }

    private ObjectNode context(NormalizedVariantKey key) throws IOException {
        return RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, key);
    }

    private VariantEvidenceProvider fixedProvider(EvidenceItem item) {
        return new VariantEvidenceProvider() {
            @Override
            public String source() {
                return "GNOMAD";
            }

            @Override
            public String version() {
                return resourceVersion();
            }

            @Override
            public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
                    List<VariantInterpretation> variants,
                    org.nbscn.varvis.variantanalysis.domain.AnalysisContext context) {
                return Collections.singletonMap(
                        variants.get(0).getObservation().getKey(), Collections.singletonList(item));
            }
        };
    }

    private EvidenceItem providerEvidence(GnomadTabixProvider provider,
                                          NormalizedVariantKey key) {
        return evidence(provider.annotate(
                Collections.singletonList(variant(key)),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key)), key);
    }

    private EvidenceItem evidence(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                                  NormalizedVariantKey key) {
        return result.get(key).get(0);
    }

    private VariantInterpretation variant(NormalizedVariantKey key) {
        return RuntimeEvidenceV2ProviderWiringTest.variant(key);
    }

    private NormalizedVariantKey key(int position, String reference, String alternate) {
        return RuntimeEvidenceV2ProviderWiringTest.key("GRCh38", position, reference, alternate);
    }

    private Path indexedFile(String name) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, new byte[] {1});
        Files.write(tempDir.resolve(name + ".tbi"), new byte[] {1});
        return file;
    }

    private Path tabixScript(String name, String body) throws IOException {
        Path script = tempDir.resolve(name);
        Files.write(script, ("@echo off\r\n" + body).getBytes(StandardCharsets.UTF_8));
        return script;
    }

    private String vcf(int position,
                       String reference,
                       String alternate,
                       String filter,
                       String info) {
        return "chr1\t" + position + "\t.\t" + reference + "\t" + alternate
                + "\t.\t" + filter + "\t" + info;
    }

    @Test void successfulPm2ObservationIsReboundButFailuresAndChangedIdentitiesAreNotReused() throws Exception {
        var profile = GnomadPopulationInputProfile.loadBusinessPm2Supporting();
        Path script = tabixScript("pm2-reuse.cmd", "echo " + vcf(150, "A", "G", "PASS", validJointInfo("5.5000e-2")) + "\r\nexit /b 0\r\n");
        var settings = baseProperties(script); settings.getGnomad().setVersion(resourceVersion(profile)); settings.getGnomad().setPopulationInputEnabled(true);
        var key = key(150, "A", "G"); var candidate = variant(key);
        var first = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, key, "ENST000001");
        var second = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, key, "ENST000002");
        var analysis = RuntimeEvidenceV2ProviderWiringTest.analysisContext(key);
        var live = new Pm2SupportingRuntime(mapper, profile, new GnomadTabixProvider(settings, profile));
        var saved = live.evaluate(first, candidate, analysis);
        assertEquals("SUCCEEDED", saved.at("/evidence/resourceExecution/status").asText());
        assertFalse(new Pm2SupportingRuntime(mapper, settings).reuseSuccessfulObservation(second, candidate, saved, "previous-run"),
                "A direct runtime result without a verified execution source is not a saved business observation");
        saved.put("executionSourceSha256", "a".repeat(64));
        var next = new Pm2SupportingRuntime(mapper, settings);
        assertTrue(next.reuseSuccessfulObservation(second, candidate, saved, "previous-run"));
        var rebound = next.evaluate(second, candidate, analysis);
        assertEquals(0, next.providerCalls()); assertNotEquals(saved.at("/evidence/evidenceId"), rebound.at("/evidence/evidenceId"));
        assertEquals(second.path("contextId"), rebound.at("/evidence/contextId"));
        assertEquals(saved.at("/evidence/provenance/sourcePayloadSha256"), rebound.at("/evidence/provenance/sourcePayloadSha256"));
        assertEquals("previous-run", rebound.at("/populationReuse/sourceRunId").asText());
        for (String field : List.of("timeout", "resource", "profile", "payload", "raw", "absence")) {
            var changed = saved.deepCopy();
            switch (field) {
                case "timeout" -> ((ObjectNode) changed.at("/evidence/resourceExecution")).put("status", "TIMEOUT");
                case "resource" -> ((ObjectNode) changed.at("/evidence/source")).put("resourceSha256", "0".repeat(64));
                case "profile" -> changed.put("profileSha256", "0".repeat(64));
                case "payload" -> ((ObjectNode) changed.at("/evidence/provenance")).put("sourcePayloadSha256", "0".repeat(64));
                case "raw" -> changed.put("rawPopulationRecord", "1\t151\t.\tA\tG\t.\tPASS\t.");
                case "absence" -> ((ObjectNode) changed.at("/evidence/queryOutcome")).put("status", "NO_RECORD");
                default -> throw new IllegalStateException();
            }
            assertFalse(new Pm2SupportingRuntime(mapper, settings).reuseSuccessfulObservation(second, candidate, changed, "previous-run"), field);
        }
        assertFalse(new Pm2SupportingRuntime(mapper, settings).reuseSuccessfulObservation(second, variant(key(150, "A", "T")), saved, "previous-run"));
    }

    private String validInfo(String af) {
        return "AC=1;AN=200;AF=" + af + ";nhomalt=0"
                + ";grpmax=nfe;AC_grpmax=1;AN_grpmax=180;AF_grpmax=" + af
                + ";nhomalt_grpmax=0;faf95=" + af
                + ";fafmax_faf95_max=" + af + ";fafmax_faf95_max_gen_anc=nfe"
                + ";AC_nfe=1;AN_nfe=180;AF_nfe=" + af + ";nhomalt_nfe=0;faf95_nfe=" + af;
    }

    private String validJointInfo(String faf95max) {
        return "AC_joint=500;AN_joint=10000;AF_joint=0.05;nhomalt_joint=25"
                + ";grpmax_joint=nfe;AC_grpmax_joint=450;AN_grpmax_joint=9000"
                + ";AF_grpmax_joint=0.05;nhomalt_grpmax_joint=22"
                + ";faf95_joint=0.054;fafmax_faf95_max_joint=" + faf95max
                + ";fafmax_faf95_max_gen_anc_joint=nfe"
                + ";AC_joint_nfe=450;AN_joint_nfe=9000;AF_joint_nfe=0.05"
                + ";nhomalt_joint_nfe=22;faf95_joint_nfe=0.054";
    }

    private String multiAllelicInfo() {
        return "AC=3,5;AN=200;AF=0.01,0.123456789012345678901234567890;nhomalt=0,1"
                + ";grpmax=afr,nfe;AC_grpmax=2,4;AN_grpmax=100,150;AF_grpmax=0.02,0.026666666666666666666666666667"
                + ";nhomalt_grpmax=0,1;faf95=0.001,1.2300e-7"
                + ";fafmax_faf95_max=0.002,1.5000e-7;fafmax_faf95_max_gen_anc=afr,nfe"
                + ";AC_afr=2,1;AN_afr=100;AF_afr=0.02,0.01;nhomalt_afr=0,0;faf95_afr=0.001,1e-8"
                + ";AC_nfe=1,4;AN_nfe=150;AF_nfe=0.006666666666666666666666666667,0.026666666666666666666666666667"
                + ";nhomalt_nfe=0,1;faf95_nfe=0.0005,1.4000e-7";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, String>> providerMeasurements(EvidenceItem item) {
        Map<String, Map<String, String>> byMetric = new LinkedHashMap<String, Map<String, String>>();
        Object value = item.getDetails().get("populationMeasurements");
        assertTrue(value instanceof List<?>);
        for (Object measurement : (List<?>) value) {
            assertTrue(measurement instanceof Map<?, ?>);
            Map<String, String> typed = (Map<String, String>) measurement;
            byMetric.put(typed.get("metricId"), typed);
        }
        return byMetric;
    }

    private Map<String, JsonNode> atomicMeasurements(ObjectNode atomic) {
        Map<String, JsonNode> byMetric = new LinkedHashMap<String, JsonNode>();
        for (JsonNode measurement : atomic.path("measurements")) {
            byMetric.put(measurement.path("metricId").asText(), measurement);
        }
        return byMetric;
    }

    private void assertMeasurement(Map<String, Map<String, String>> measurements,
                                   String metricId,
                                   String rawValue,
                                   String normalizedValue) {
        Map<String, String> measurement = measurements.get(metricId);
        assertTrue(measurement != null, "missing measurement " + metricId);
        assertEquals(rawValue, measurement.get("rawValue"));
        assertEquals(normalizedValue, measurement.get("normalizedValue"));
    }

    private void assertHitNotReady(EvidenceItem item, String reasonCode) {
        assertEquals("HIT", item.getDetails().get("queryOutcomeStatus"));
        assertEquals("APPLICABLE", item.getDetails().get("resourceApplicabilityStatus"));
        assertEquals("NOT_READY", item.getDetails().get("populationInputReadinessStatus"));
        assertTrue(reasonCodes(item).contains(reasonCode));
        assertEquals(Boolean.FALSE, item.getDetails().get("criterionEvaluationEnabled"));
        assertNull(item.getRawScore());
    }

    private void assertPopulationFailure(EvidenceItem item,
                                         NormalizedVariantKey key,
                                         String expectedExecution) throws Exception {
        assertEquals("UNAVAILABLE", item.getApplicability());
        assertEquals(expectedExecution, item.getDetails().get("resourceExecutionStatus"));
        assertEquals("NOT_QUERIED", item.getDetails().get("queryOutcomeStatus"));
        assertNull(item.getRawScore());
        assertFalse(item.getDetails().containsKey("populationMeasurements"));

        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadDefault();
        RuntimeEvidenceV2ProviderWiring wiring = wiring();
        ObjectNode atomic = wiring.annotateVariantProvider(
                context(key), fixedProvider(item), variant(key),
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key),
                wiring.bind(profile.getDocument(), profile.getFullSitesResourceId())).get(0);
        assertEquals(expectedExecution, atomic.path("resourceExecution").path("status").asText());
        assertEquals("NOT_QUERIED", atomic.path("queryOutcome").path("status").asText());
        assertTrue(atomic.path("measurements").isEmpty());
        assertNoCriterionResult(atomic);
    }

    @SuppressWarnings("unchecked")
    private List<String> reasonCodes(EvidenceItem item) {
        Object value = item.getDetails().get("populationInputReadinessReasonCodes");
        return value instanceof List<?> ? (List<String>) value : Collections.emptyList();
    }

    private int countByte(Path path, byte expected) throws IOException {
        int count = 0;
        for (byte value : Files.readAllBytes(path)) {
            if (value == expected) count++;
        }
        return count;
    }

    private void assertNoCriterionResult(JsonNode value) {
        List<String> textValues = new ArrayList<String>();
        collectText(value, textValues);
        assertFalse(textValues.contains("MET"));
        assertFalse(textValues.contains("NOT_MET"));
    }

    private void collectText(JsonNode value, List<String> target) {
        if (value == null) return;
        if (value.isTextual()) {
            target.add(value.asText());
        } else if (value.isArray()) {
            value.forEach(item -> collectText(item, target));
        } else if (value.isObject()) {
            value.elements().forEachRemaining(item -> collectText(item, target));
        }
    }
}
