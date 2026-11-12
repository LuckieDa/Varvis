package org.nbscn.varvis.variantanalysis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class LocalEvidenceBatchConfigurationTest {
    @Test void pinnedRawScopeDirectoryIsWiredAndSharedWithoutChangingOtherSettings() throws Exception {
        var raw = mapper.readTree("""
          {"data":[{"entId":"GN900","entContent":{"version":"1.0","states":[{"name":"Released","current":true}]},"ld":{"CriteriaCode":[],"RuleSet":[]}}]}
          """);
        byte[] bytes=mapper.writeValueAsBytes(raw);
        Files.write(temporary.resolve("raw.json"),bytes);
        String rawSha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var catalog=mapper.createObjectNode().put("schemaVersion","a01-ba1-cspec-snapshot-v1");
        catalog.putObject("metadata").put("status","COMPLETE").put("entryCount",1);
        var e=catalog.putArray("entries").addObject().put("specificationId","GN900").put("version","1.0");
        e.putObject("scope").putArray("hgncIds").add("HGNC:123");
        e.putObject("source").put("rawRef","raw.json").put("rawSha256",rawSha).put("jsonPointer","/data/0");
        bytes=mapper.writeValueAsBytes(catalog); Path path=temporary.resolve("catalog.json");Files.write(path,bytes);
        String sha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var env=new MockEnvironment().withProperty(PREFIX+"prediction-specialization-catalog-file",java.util.Objects.requireNonNull(path.toString()))
                .withProperty(PREFIX+"prediction-specialization-catalog-sha256",java.util.Objects.requireNonNull(sha))
                .withProperty(PREFIX+"prediction-specialization-source-directory",java.util.Objects.requireNonNull(temporary.toString()));
        var config=new LocalEvidenceBatchConfiguration(mapper,new VariantAnalysisProperties(),env,null);
        var scope=config.predictionSpecializations();
        assertEquals(1,scope.identity().path("sourceEntitiesVerified").asInt());
        assertSame(scope,config.predictionSpecializations());
        Files.writeString(temporary.resolve("raw.json"),"{}");
        assertThrows(IllegalArgumentException.class,()->new LocalEvidenceBatchConfiguration(mapper,new VariantAnalysisProperties(),env,null).predictionSpecializations());
    }
    @Test void pm3PopulationIdentityTracksOnlyItsResourcesAndUsesSharedFactory() throws Exception {
        var env=new MockEnvironment();var config=new LocalEvidenceBatchConfiguration(mapper,new VariantAnalysisProperties(),env,null);
        var original=config.populationIdentity();
        env.withProperty(PREFIX+"pvs1-bundle-sha256",java.util.Objects.requireNonNull("a".repeat(64)));assertEquals(original,config.populationIdentity());
        Path manifest=temporary.resolve("all-sites.json");
        Files.writeString(manifest,"{\"dataFile\":\"d.bgz\",\"indexFile\":\"d.bgz.tbi\"}");
        env.withProperty(PREFIX+"local-batch-pm2-all-sites-file",java.util.Objects.requireNonNull(manifest.toString()));
        var before=config.populationIdentity();assertNotEquals(original,before);
        Files.writeString(temporary.resolve("d.bgz"),"synthetic metadata identity only");
        assertNotEquals(before,config.populationIdentity());
        env.withProperty(PREFIX+"local-batch-pm2-all-sites-file","")
                .withProperty(PREFIX+"local-batch-pm2-general-absence-enabled","true");
        assertEquals("PM2_GENERAL_ABSENCE_RESOURCE_REQUIRED",assertThrows(IllegalArgumentException.class,config::population).getMessage());
        var disabled=new AcmgBusinessConfig().pm3LiteraturePopulation(mapper,new MockEnvironment());
        assertFalse(disabled.identity().path("enabled").asBoolean());
        assertEquals("PM3_LOCAL_POPULATION_WORKFLOW_DISABLED",assertThrows(IllegalArgumentException.class,disabled::create).getMessage());
    }
    @Test void retainedReferenceAuditPinsAffectBusinessIdentityOnlyWhenSelected() throws Exception {
        var env=new MockEnvironment().withProperty(PREFIX+"local-batch-reference-splice-mode","NATIVE_SELECTED");
        var config=new LocalEvidenceBatchConfiguration(mapper,new VariantAnalysisProperties(),env,null);
        var before=config.create(p->mapper.nullNode()).identity();
        Path audit=temporary.resolve("retained-native-audit.json");Files.writeString(audit,"TEST_ONLY_PIN_IDENTITY");
        env.withProperty(PREFIX+"reference-native-retained-audit-file",java.util.Objects.requireNonNull(audit.toString()))
                .withProperty(PREFIX+"reference-native-retained-audit-sha256",java.util.Objects.requireNonNull("a".repeat(64)));
        var bound=config.create(p->mapper.nullNode()).identity();assertNotEquals(before,bound);
        Files.writeString(audit,"TEST_ONLY_CHANGED_PIN_IDENTITY");assertNotEquals(bound,config.create(p->mapper.nullNode()).identity());
    }
    @Test void unavailableOptionalWideRuntimeDoesNotPreventLegacyRuntimeConstruction() {
        var env=new MockEnvironment().withProperty(PREFIX+"bp1-wide-splice-enabled","true");
        var config=new LocalEvidenceBatchConfiguration(mapper,new VariantAnalysisProperties(),env,null);
        assertNull(config.bp1WideSplice());
        // The BP1 runtime itself enforces that a regional clause cannot use this null as a legacy fallback.
        env.withProperty(PREFIX+"bp1-wide-splice-enabled","false");assertNull(config.bp1WideSplice());
    }
    @Test void elevenIsExplicitAndBindsAllNewPinsWithoutOpeningScienceResources() throws Exception {
        var env=new MockEnvironment();var config=new LocalEvidenceBatchConfiguration(mapper,new VariantAnalysisProperties(),env,null);
        var original=config.create(p->mapper.nullNode()).identity();
        env.withProperty(PREFIX+"local-batch-pvs1-enabled","false").withProperty(PREFIX+"local-batch-bp3-enabled","false");
        assertEquals(original,config.create(p->mapper.nullNode()).identity());
        env.withProperty(PREFIX+"local-batch-pvs1-enabled","true");assertThrows(IllegalArgumentException.class,()->config.create(p->mapper.nullNode()));
        for(String flag:List.of("local-batch-bp3-enabled","local-batch-pm4-enabled","local-batch-bp7-enabled"))env.withProperty(PREFIX+flag,"true");
        var batch=config.create(p->mapper.nullNode());assertEquals(9,batch.enabledCriteria().size());
        assertEquals("1.4.0-extended",AcmgExecutionProfile.localBatch(mapper,batch.identity()).document().path("profileVersion").asText());
        assertEquals("PVS1_SELECTED_KNOWLEDGE_REQUIRED",assertThrows(IllegalArgumentException.class,config::pvs1).getMessage());
        for(String key:List.of("pvs1-bundle-file-sha256","pvs1-bundle-sha256","pvs1-knowledge-source-sha256","pvs1-knowledge-adoption-sha256","pvs1-applicability-source-sha256","bp3-reviewed-region-assertions-sha256","bp3-published-knowledge-sha256")) {
            var before=config.create(p->mapper.nullNode()).identity();env.withProperty(PREFIX+key,java.util.Objects.requireNonNull("a".repeat(64)));assertNotEquals(before,config.create(p->mapper.nullNode()).identity(),key);
        }
        for(String key:List.of("pvs1-bundle-file","pvs1-knowledge-source-file","pvs1-knowledge-adoption-file","pvs1-applicability-source-file","bp3-reviewed-region-assertions-file","bp3-published-knowledge-file")) {
            Path file=temporary.resolve(key+".json");env.withProperty(PREFIX+key,java.util.Objects.requireNonNull(file.toString()));
            var before=config.create(p->mapper.nullNode()).identity();Files.writeString(file,"TEST_ONLY_IDENTITY_NOT_SCIENTIFIC_MATERIAL");
            assertNotEquals(before,config.create(p->mapper.nullNode()).identity(),key);
        }
    }
    @Test void pm4AndBp3ShareOneRequestOwnedFactoryIncludingInitializationFailure() {
        var env=new MockEnvironment();for(String flag:List.of("local-batch-pvs1-enabled","local-batch-bp3-enabled","local-batch-pm4-enabled","local-batch-bp7-enabled"))env.withProperty(PREFIX+flag,"true");
        var config=org.mockito.Mockito.spy(new LocalEvidenceBatchConfiguration(mapper,new VariantAnalysisProperties(),env,null));
        org.mockito.Mockito.doThrow(new IllegalArgumentException("TEST_FIXED_SOURCE_INVALID")).when(config).pm4();
        var runtime=config.create(p->mapper.nullNode());
        var pm4=(java.util.function.Supplier<?>)ReflectionTestUtils.getField(java.util.Objects.requireNonNull(runtime),"pm4Factory");
        var bp3=(java.util.function.Supplier<?>)ReflectionTestUtils.getField(java.util.Objects.requireNonNull(runtime),"bp3Factory");
        assertNotNull(pm4);assertNotNull(bp3);assertThrows(IllegalArgumentException.class,pm4::get);assertThrows(IllegalArgumentException.class,bp3::get);
        org.mockito.Mockito.verify(config,org.mockito.Mockito.times(1)).pm4();
    }
    @Test void bp7AndPm4ComposeWithoutDroppingEitherOrExpandingTheEightRuleEntry() {
        var env = new MockEnvironment().withProperty(PREFIX + "local-batch-bp7-enabled", "true")
                .withProperty(PREFIX + "local-batch-pm4-enabled", "true");
        var config = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), env, null);
        var runtime = config.create(p -> mapper.nullNode());
        var profile = AcmgExecutionProfile.localBatch(mapper, runtime.identity());
        assertEquals("1.3.0-bp7-pm4", profile.document().path("profileVersion").asText());
        assertEquals(runtime.enabledCriteria(), profile.criteria());
        assertEquals(List.of("ACMG_AMP_2015:BP7", "ACMG_AMP_2015:PM4"), profile.criteria().subList(5, 7));
        assertTrue(profile.document().at("/moduleBindings/bp7/runtimeBinding").isObject());
        assertTrue(profile.document().at("/moduleBindings/pm4/runtimeBinding/pm4Configured").asBoolean());
        assertEquals("NOT_CHECKED", runtime.identity().path("resourceReadiness").asText());
        assertEquals("UNIFIED_LOCAL_EIGHT_COMPONENTS_REQUIRED", assertThrows(IllegalArgumentException.class,
                () -> AcmgExecutionProfile.unifiedLocal(mapper, runtime.identity(), mapper.createObjectNode())).getMessage());
    }

    @Test void bp7OptInBindsCapturedCatalogWithoutAddingItToTheDefaultFive() throws Exception {
        var env = new MockEnvironment(); var settings = new VariantAnalysisProperties();
        var config = new LocalEvidenceBatchConfiguration(mapper, settings, env, null);
        var old = config.create(p -> mapper.nullNode()).identity();
        assertNull(config.bp7()); assertFalse(old.has("bp7"));
        env.withProperty(PREFIX + "local-batch-bp7-enabled", "false");
        assertEquals(old, config.create(p -> mapper.nullNode()).identity());
        env.withProperty(PREFIX + "local-batch-bp7-enabled", "true");
        var first = config.create(p -> mapper.nullNode()); assertEquals(6, first.enabledCriteria().size());
        assertEquals("AVAILABLE", first.identity().at("/bp7/experimentSourceStatus").asText());
        assertFalse(first.identity().at("/bp7/clinicalApproval").asBoolean());
        Path copy = temporary.resolve("catalog.json");
        try (var in = getClass().getResourceAsStream("/acmg/public-splicing-experiments-v1.json")) {
            Files.copy(java.util.Objects.requireNonNull(in), copy);
        }
        env.withProperty(PREFIX + "splicing-experiment-catalog", java.util.Objects.requireNonNull(copy.toString()));
        var pinned = config.create(p -> mapper.nullNode()).identity();
        Files.writeString(copy, "\n", java.nio.file.StandardOpenOption.APPEND);
        var changed = config.create(p -> mapper.nullNode()).identity();
        assertNotEquals(pinned.at("/bp7/experimentSnapshotId"), changed.at("/bp7/experimentSnapshotId"));
        assertEquals("AVAILABLE", changed.at("/bp7/experimentSourceStatus").asText());
        env.withProperty(PREFIX + "local-batch-bp7-enabled", "false");
        assertEquals(old, config.create(p -> mapper.nullNode()).identity());
    }
    @Test void bp7UnavailableCatalogRemainsUnavailableAndRemoteLocationsAreNotFetched() {
        var env = new MockEnvironment().withProperty(PREFIX + "local-batch-bp7-enabled", "true")
                .withProperty(PREFIX + "splicing-experiment-catalog", java.util.Objects.requireNonNull(temporary.resolve("absent.json").toString()));
        var config = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), env, null);
        assertEquals("UNAVAILABLE", config.create(p -> mapper.nullNode()).identity().at("/bp7/experimentSourceStatus").asText());
        env.withProperty(PREFIX + "splicing-experiment-catalog", "https://invalid.example/catalog.json");
        assertEquals("BP7_EXPERIMENT_CATALOG_LOCAL_ONLY", assertThrows(IllegalArgumentException.class, config::bp7).getMessage());
    }
    @Test void referenceNativeModeIsExplicitAndCannotInheritApprovalOrFallback() {
        var env = new MockEnvironment(); var config = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), env, null);
        var original = config.identity(); assertFalse(original.has("referenceNativeSpliceVersion"));
        env.withProperty(PREFIX + "local-batch-reference-splice-mode", "NATIVE_SELECTED");
        assertNotEquals(original, config.identity());
        assertEquals(MissenseNativeSpliceBridge.VERSION, config.identity().path("referenceNativeSpliceVersion").asText());
        assertEquals("REFERENCE_NATIVE_SPLICE_CONFIGURATION_UNAVAILABLE", assertThrows(IllegalArgumentException.class, config::referenceNativeSplice).getMessage());
        assertEquals("REFERENCE_APPROVED_MATERIAL_UNAVAILABLE", ReflectionTestUtils.invokeMethod(config,
                "referenceUseIssue", MissenseComparisonRuntime.PS1, mapper.createObjectNode().put("dataClass", "PRIVACY_BOUNDED_RUNTIME")));
        env.withProperty(PREFIX + "local-batch-reference-splice-mode", "AUTO_FALLBACK");
        assertEquals("REFERENCE_SPLICE_MODE_INVALID", assertThrows(IllegalArgumentException.class, config::identity).getMessage());
    }
    @Test void alphaSelectionIsExplicitExclusiveAndSourceIndexInvalidatesSavedIdentity() throws Exception {
        var env = new MockEnvironment(); var config = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), env, null);
        assertNull(config.generalAlpha()); var old = config.identity();
        env.withProperty(PREFIX + "local-batch-general-protein-model", "ALPHAMISSENSE")
                .withProperty(PREFIX + "local-batch-general-revel-enabled", "true")
                .withProperty(PREFIX + "alphamissense-source-transcript-index-directory", java.util.Objects.requireNonNull(temporary.toString()));
        assertNull(config.generalRevel()); assertNotEquals(old, config.identity());
        assertEquals(GeneralAlphaMissensePolicy.VERSION, config.identity().path("generalAlphaMissenseVersion").asText());
        var first = config.identity(); Files.writeString(temporary.resolve("index.tsv"), "changed"); assertNotEquals(first, config.identity());
        assertThrows(IllegalArgumentException.class, config::generalAlpha);
        env.withProperty(PREFIX + "local-batch-general-protein-model", "AUTO_MAX"); assertThrows(IllegalArgumentException.class, config::identity);
    }
    @Test void generalRevelDefaultIsOffAndBothPolicyAndDataAreInExecutionIdentity() throws Exception {
        var env = new MockEnvironment(); var config = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), env, null);
        assertNull(config.generalRevel()); var old = config.identity();
        env.withProperty(PREFIX + "local-batch-general-revel-enabled", "true")
                .withProperty(PREFIX + "revel-publisher-index-directory", java.util.Objects.requireNonNull(temporary.toString()));
        Files.writeString(temporary.resolve("revel-grch38.lookup.tsv.gz"), "original");
        var first = config.identity(); assertNotEquals(old, first);
        assertEquals(GeneralRevelPolicy.VERSION, first.path("generalRevelVersion").asText());
        assertTrue(first.path("generalRevelPolicySha256").asText().matches("[0-9a-f]{64}"));
        Files.writeString(temporary.resolve("revel-grch38.lookup.tsv.gz"), "changed-content");
        assertNotEquals(first, config.identity());
        assertEquals("GENERAL_REVEL_CONFIGURATION_UNAVAILABLE", assertThrows(IllegalArgumentException.class, config::generalRevel).getMessage());
    }
    @Test void gencodeConfigurationAndDerivedFileChangesInvalidateSavedIdentityWithoutQueries() throws Exception {
        var env = new MockEnvironment();
        var config = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), env, null);
        var old = config.identity();
        env.withProperty(PREFIX + "gencode-transcript-index-directory", java.util.Objects.requireNonNull(temporary.toString()))
                .withProperty(PREFIX + "gencode-transcript-index-manifest-sha256", java.util.Objects.requireNonNull("a".repeat(64)));
        Files.writeString(temporary.resolve("manifest.json"), "{}");
        Files.writeString(temporary.resolve("annotations.jsonl"), "{}");
        Files.writeString(temporary.resolve("index.tsv"), "test");
        var first = config.identity(); assertNotEquals(old, first);
        assertEquals(GencodeTranscriptIndex.VERSION, first.path("gencodeTranscriptIndexVersion").asText());
        Files.writeString(temporary.resolve("annotations.jsonl"), "changed");
        assertNotEquals(first, config.identity());
        assertThrows(IllegalArgumentException.class, config::transcriptMaterials);
    }
    @Test void qualifiedReferenceOptInChangesIdentityButDoesNotSupplyMissingApproval() {
        var env = new MockEnvironment();
        var config = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), env, null);
        var original = config.identity();
        env.withProperty(PREFIX + "local-batch-reference-qualified-application-enabled", "true");
        assertNotEquals(original, config.identity());
        assertEquals("REFERENCE_APPROVED_MATERIAL_UNAVAILABLE", ReflectionTestUtils.invokeMethod(config,
                "referenceUseIssue", MissenseComparisonRuntime.PS1, mapper.createObjectNode().put("dataClass", "PRIVACY_BOUNDED_RUNTIME")));
        assertEquals("NOT_GRANTED", config.create(plan -> mapper.nullNode()).identity().path("scientificApproval").asText());
    }
    @Test void transcriptSupplyIsOptInAndChangesExecutionIdentityButCannotGrantApproval() {
        var env = new MockEnvironment();
        var config = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), env, null);
        assertNull(config.transcriptMaterials()); var original = config.identity();
        env.withProperty(PREFIX + "transcript-material-directory", java.util.Objects.requireNonNull(temporary.toString()))
                .withProperty(PREFIX + "transcript-material-manifest-sha256", java.util.Objects.requireNonNull("a".repeat(64)));
        assertNotEquals(original, config.identity());
        assertEquals(CodingTranscriptMaterialSupply.VERSION, config.identity().path("transcriptMaterialVersion").asText());
        assertEquals("TRANSCRIPT_MATERIAL_CONFIGURATION_UNAVAILABLE", assertThrows(IllegalArgumentException.class, config::transcriptMaterials).getMessage());
    }
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private static final String PREFIX = "variant-analysis.acmg-business.";
    @TempDir Path temporary;

    @Test void nativeSpliceIsOptInAndDoesNotInheritLegacyIdentityOrReadiness() {
        var env = new MockEnvironment();
        var config = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), env, null);
        assertNull(config.nativeSplice()); var previous = config.identity();
        env.withProperty(PREFIX + "local-batch-native-splice-enabled", "true");
        assertNotEquals(previous, config.identity());
        assertEquals(NativeSpliceAiRuntime.VERSION, config.identity().path("nativeSpliceVersion").asText());
        assertEquals("NATIVE_SPLICE_CONFIGURATION_UNAVAILABLE", assertThrows(IllegalArgumentException.class, config::nativeSplice).getMessage());
    }

    @Test void defaultScopeUnchangedAndNewScopeRequiresServerEnable() {
        var config = new AcmgBusinessConfig(); var settings = new VariantAnalysisProperties(); var env = new MockEnvironment();
        var original = config.acmgBusinessService(mapper, null, null, null, settings, env);
        assertEquals(AcmgBusinessRuleScope.CURRENT, ((AcmgExecutionProfile) java.util.Objects.requireNonNull(ReflectionTestUtils.getField(java.util.Objects.requireNonNull(original), "executionProfile"))).criteria());
        env.withProperty(PREFIX + "acceptance-scope", AcmgBusinessRuleScope.LOCAL_BATCH1);
        assertEquals("LOCAL_BATCH_BUSINESS_EXECUTION_DISABLED", assertThrows(IllegalArgumentException.class,
                () -> config.acmgBusinessService(mapper, null, null, null, settings, env)).getMessage());
        env.withProperty(PREFIX + "enabled", "true");
        var configured = config.acmgBusinessService(mapper, null, null, null, settings, env);
        var profile = ((AcmgExecutionProfile) java.util.Objects.requireNonNull(ReflectionTestUtils.getField(java.util.Objects.requireNonNull(configured), "executionProfile"))).document();
        assertEquals("acmg-local-computational-batch1-v1", profile.path("profileId").asText());
        assertEquals(mapper.valueToTree(LocalEvidenceBatchRuntime.CRITERIA), profile.path("enabledCriteria"));
        assertEquals("SELECTED_LOCAL_BATCH1_V1", profile.path("contextPreparation").asText());
        assertFalse(profile.path("formalEnabled").asBoolean(true)); assertFalse(profile.path("classificationEnabled").asBoolean(true));
        for (var module : List.of("pm2", "prediction", "reference")) assertTrue(profile.at("/moduleBindings/" + module).isObject());
        String pin = profile.remove("manifestSha256").asText();
        assertEquals(pin, new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(profile));
    }

    @Test void conflictingExecutionModesAreRejectedBeforeModuleAcquisition() {
        for (String mode : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only")) {
            var env = new MockEnvironment().withProperty(PREFIX + "enabled", "true")
                    .withProperty(PREFIX + "acceptance-scope", AcmgBusinessRuleScope.LOCAL_BATCH1).withProperty(PREFIX + mode, "true");
            assertEquals("LOCAL_BATCH_EXECUTION_MODE_CONFLICT", assertThrows(IllegalArgumentException.class,
                    () -> new AcmgBusinessConfig().acmgBusinessService(mapper, null, null, null, new VariantAnalysisProperties(), env)).getMessage());
        }
    }

    @Test void identityConstructionReadsNoScoresAndMetadataChangeInvalidatesBinding() throws Exception {
        Path data = temporary.resolve("not-a-score-table.tsv.gz"); Files.writeString(data, "intentionally invalid table");
        var settings = new VariantAnalysisProperties(); settings.getAlphaMissense().setEnabled(true);
        settings.getAlphaMissense().setGrch38File(data.toString());
        settings.getGnomad().setTabixExecutable(temporary.resolve("must-not-execute").toString());
        var config = new LocalEvidenceBatchConfiguration(mapper, settings, new MockEnvironment(), null);
        assertEquals(Pm2LocalExomesProfile.load(mapper).document().path("profileVersion"),
                config.identity().path("pm2LocalProfileVersion"));
        assertEquals(PredictionMechanismSummary.VERSION, config.identity().path("predictionMechanismSummaryVersion").asText());
        var first = config.create(plan -> mapper.nullNode()).identity();
        assertTrue(first.path("predictionConfigured").asBoolean());
        assertEquals("NOT_CHECKED", first.path("resourceReadiness").asText());
        assertEquals(first, config.create(plan -> mapper.nullNode()).identity());
        Files.writeString(data, "different and longer invalid score table");
        assertNotEquals(first, config.create(plan -> mapper.nullNode()).identity());
    }

    @Test void sourcePinsCannotGrantReadinessAndPm2DefaultDoesNotAdoptPolicy() throws Exception {
        Path source = temporary.resolve("source.json"); Files.writeString(source, "{}");
        var env = new MockEnvironment().withProperty(PREFIX + "local-batch-reference-source-file", java.util.Objects.requireNonNull(source.toString()))
                .withProperty(PREFIX + "local-batch-reference-source-sha256", java.util.Objects.requireNonNull("a".repeat(64)));
        var runtime = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), env, null)
                .create(plan -> mapper.nullNode());
        assertFalse(runtime.identity().path("referenceConfigured").asBoolean());
        assertEquals("NOT_GRANTED", runtime.identity().path("scientificApproval").asText());
        assertEquals(0, Pm2LocalExomesProfile.load(mapper).document().path("adoptedPolicies").size());
        assertEquals("NOT_CHECKED", runtime.identity().path("resourceReadiness").asText());
    }

    @Test void localRevelTransportChangesSavedExecutionIdentityWithoutGrantingQualification() {
        var env = new MockEnvironment();
        var config = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), env, null);
        var before = config.identity();
        env.withProperty(PREFIX + "revel-publisher-transport", "LOCAL_HTSJDK");
        assertNotEquals(before, config.identity());
        assertEquals("NOT_GRANTED", config.create(plan -> mapper.nullNode()).identity().path("scientificApproval").asText());
    }

    @Test void allSitesMetadataAndPolicySwitchInvalidateReuseWithoutReadingLargeData() throws Exception {
        Path data = temporary.resolve("all-sites.bgz"), index = temporary.resolve("all-sites.bin"), manifest = temporary.resolve("all-sites.json");
        Files.writeString(data, "not a resource; identity construction must not query"); Files.writeString(index, "not an index");
        mapper.writeValue(manifest.toFile(), mapper.createObjectNode().put("dataFile", data.getFileName().toString())
                .put("indexFile", index.getFileName().toString()));
        var env = new MockEnvironment().withProperty(PREFIX + "local-batch-pm2-all-sites-file", java.util.Objects.requireNonNull(manifest.toString()))
                .withProperty(PREFIX + "local-batch-pm2-all-sites-sha256", java.util.Objects.requireNonNull("a".repeat(64)));
        var config = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), env, null);
        var first = config.identity();
        Files.writeString(data, "data changed"); var second = config.identity(); assertNotEquals(first, second);
        Files.writeString(index, "index changed"); var third = config.identity(); assertNotEquals(second, third);
        env.withProperty(PREFIX + "local-batch-pm2-general-absence-enabled", "true"); assertNotEquals(third, config.identity());
    }

    @Test void pm4IsOptInAndDisabledKeysDoNotChangeOldIdentity() throws Exception {
        var env = new MockEnvironment();
        var config = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), env, null);
        var old = config.identity();
        env.withProperty(PREFIX + "pm4-combined-region-file", java.util.Objects.requireNonNull(temporary.resolve("combined.json").toString()));
        env.withProperty(PREFIX + "pm4-non-repeat-policy", "ANNOTATION_SEQUENCE_V1");
        assertEquals(old, config.identity());
        assertFalse(config.create(plan -> mapper.nullNode()).identity().has("pm4Configured"));
        env.withProperty(PREFIX + "local-batch-pm4-enabled", "true");
        assertEquals(6, config.create(plan -> mapper.nullNode()).capabilities().path("criteria").size());
        var bound = config.identity(); assertNotEquals(old, bound);
        env.withProperty(PREFIX + "pm4-non-repeat-policy", "REVIEWED_ONLY");
        assertNotEquals(bound, config.identity());
        Files.writeString(temporary.resolve("combined.json"), "{}"); assertNotEquals(bound, config.identity());
        assertEquals("PM4_CONFIGURATION_UNAVAILABLE", assertThrows(IllegalArgumentException.class, config::pm4).getMessage());
    }
    @Test void regionDemandIsExplicitAndVersionLimitsAreInBusinessIdentity() throws Exception {
        var env = new MockEnvironment(); var config = new LocalEvidenceBatchConfiguration(mapper,new VariantAnalysisProperties(),env,null);
        var old = config.identity(); assertNull(config.demand());
        env.withProperty(PREFIX+"pm4-region-demand-uniprot-release","2026_03");
        assertEquals(old,config.identity());
        env.withProperty(PREFIX+"local-batch-pm4-enabled","true").withProperty(PREFIX+"pm4-region-demand-enabled","true")
                .withProperty(PREFIX+"pm4-region-demand-interpro-release","110.0").withProperty(PREFIX+"pm4-region-demand-interpro-minor","0")
                .withProperty(PREFIX+"pm4-region-demand-cache-directory",java.util.Objects.requireNonNull(temporary.resolve("cache").toString()));
        var first = config.identity(); var demand = config.demand(); assertNotNull(demand);
        assertFalse(demand.identity().path("networkAllowed").asBoolean(true)); assertEquals(0,demand.counts().path("httpRequests").asInt());
        env.withProperty(PREFIX+"pm4-region-demand-max-requests","8"); assertNotEquals(first,config.identity());
        env.withProperty(PREFIX+"pm4-region-demand-uniprot-release",""); assertThrows(IllegalArgumentException.class,config::demand);
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    @SuppressWarnings("unchecked")
    void configuredIndexUsesCapturedBackgroundAndDoesNotFallbackToLegacyOnDrift(boolean pm5) throws Exception {
        var f = new GeneratedMissenseReferenceFixture(temporary.resolve("indexed"), mapper, pm5).build();
        Path index = temporary.resolve("index.json");
        var doc = mapper.createObjectNode().put("schemaVersion", "missense-material-index-v1");
        var row = doc.putArray("entries").addObject().put("entryId", "qualified").put("criterion", f.criterion())
                .put("path", f.bundlePath().toString()).put("canonicalSha256", f.bundleSha256());
        row.put("rawSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f.bundlePath()))));
        mapper.writeValue(index.toFile(), doc);
        String pin = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(index)));
        var env = new MockEnvironment(); var config = new LocalEvidenceBatchConfiguration(mapper,new VariantAnalysisProperties(),env,null);
        var original = config.identity();
        env.withProperty(PREFIX+"local-batch-reference-material-index-file",java.util.Objects.requireNonNull(index.toString()))
                .withProperty(PREFIX+"local-batch-reference-material-index-sha256",java.util.Objects.requireNonNull(pin))
                .withProperty(PREFIX+"local-batch-"+(pm5 ? "pm5" : "ps1")+"-bundle-file",java.util.Objects.requireNonNull(f.bundlePath().toString()))
                .withProperty(PREFIX+"local-batch-"+(pm5 ? "pm5" : "ps1")+"-bundle-sha256",java.util.Objects.requireNonNull(f.bundleSha256()));
        var identity = config.identity(); assertNotEquals(original,identity);
        assertEquals(1,identity.at("/referenceMaterialIndex/entries").size());
        var captures = (java.util.List<ClinVarConditionAwareComparatorInput.SnapshotCapture>) ReflectionTestUtils.getField(f,"captures");
        assertNotNull(captures);
        java.util.function.Function<java.util.List<SpliceAiRawBatchRunner.Query>,SpliceAiRawBatchRunner.BatchResult> raw = queries -> { throw new AssertionError("No scientific execution while routing"); };
        java.util.function.Supplier<MissenseNativeSpliceBridge> nativeRaw = () -> { throw new AssertionError("No native setup in legacy mode"); };
        var bundle = (ReviewedRuleBundle) ReflectionTestUtils.invokeMethod(config,"approvedReference",f.criterion(),captures.get(0),raw,"LEGACY_WSL",nativeRaw);
        assertNotNull(bundle); assertTrue(pm5 ? bundle.supportsPm5() : bundle.supportsPs1());
        assertNull(ReflectionTestUtils.invokeMethod(config,"referenceUseIssue",f.criterion(),f.context(0)));
        var wrong = f.context(0); ((com.fasterxml.jackson.databind.node.ObjectNode)wrong.path("disease")).put("mondoId","MONDO:9999999");
        assertEquals("REFERENCE_MATERIAL_BACKGROUND_NOT_FOUND",ReflectionTestUtils.invokeMethod(config,"referenceUseIssue",f.criterion(),wrong));
        Files.writeString(f.bundlePath()," ",java.nio.file.StandardOpenOption.APPEND);
        assertNotEquals(identity,config.identity(),"Drift must invalidate saved execution identity even when canonical content is unchanged");
        assertEquals("REFERENCE_MATERIAL_INDEX_INVALID",ReflectionTestUtils.invokeMethod(config,"referenceUseIssue",f.criterion(),f.context(0)));
        assertEquals("REFERENCE_MATERIAL_INDEX_INVALID",assertThrows(IllegalArgumentException.class,()->
                ReflectionTestUtils.invokeMethod(config,"approvedReference",f.criterion(),captures.get(0),raw,"LEGACY_WSL",nativeRaw)).getMessage());
    }


    @Test void publishedThreeAreOptInBindPinsAndRequireAllElevenComponents() throws Exception {
        var env = new MockEnvironment(); var config = new LocalEvidenceBatchConfiguration(mapper, new VariantAnalysisProperties(), env, null);
        var old = config.create(p -> mapper.nullNode()).identity();
        for (String code : List.of("pm1", "pp2", "bp1")) env.withProperty(PREFIX + "local-batch-" + code + "-enabled", "false");
        assertEquals(old, config.create(p -> mapper.nullNode()).identity());
        env.withProperty(PREFIX + "local-batch-pm1-enabled", "true");
        assertEquals("PUBLISHED_LOCAL_ALL_COMPONENTS_REQUIRED", assertThrows(IllegalArgumentException.class, () -> config.create(p -> mapper.nullNode())).getMessage());
        for (String code : List.of("pp2", "bp1", "pvs1", "pm4", "bp3", "bp7")) env.withProperty(PREFIX + "local-batch-" + code + "-enabled", "true");
        var first = config.create(p -> mapper.nullNode());
        assertEquals(12, first.enabledCriteria().size()); assertFalse(first.identity().path("pp2GermlineDeclared").asBoolean(true));
        assertEquals(org.nbscn.varvis.variantanalysis.service.LegacyContextProjection.CODING_SEQUENCE_VERSION,
                first.identity().path("bp3CodingSequenceProjectionVersion").asText());
        var previousIdentity = first.identity(); previousIdentity.remove("bp3CodingSequenceProjectionVersion");
        assertNotEquals(previousIdentity, first.identity(), "Annotation adapter changes must not reuse old BP3 failures");
        var missing = assertThrows(IllegalArgumentException.class, () -> config.publishedKnowledge("pm1"));
        assertEquals("PM1_PUBLISHED_KNOWLEDGE_UNAVAILABLE", missing.getMessage());
        for (String code : List.of("pm1", "pp2", "bp1")) {
            var before = config.create(p -> mapper.nullNode()).identity();
            env.withProperty(PREFIX + code + "-published-knowledge-sha256", java.util.Objects.requireNonNull("b".repeat(64)));
            assertNotEquals(before, config.create(p -> mapper.nullNode()).identity());
            Path file = temporary.resolve(code + "-knowledge.json"); env.withProperty(PREFIX + code + "-published-knowledge-file", java.util.Objects.requireNonNull(file.toString()));
            before = config.create(p -> mapper.nullNode()).identity(); Files.writeString(file, "TEST_ONLY_NOT_VALID_KNOWLEDGE");
            assertNotEquals(before, config.create(p -> mapper.nullNode()).identity());
        }
        var before = config.create(p -> mapper.nullNode()).identity();
        env.withProperty(PREFIX + "local-batch-pp2-germline-declared", "true");
        var declared = config.create(p -> mapper.nullNode()).identity(); assertTrue(declared.path("pp2GermlineDeclared").asBoolean()); assertNotEquals(before, declared);
        assertEquals(declared, config.create(p -> mapper.nullNode(), "c".repeat(64)).identity(), "Dynamic effect snapshot is bound by request identity, not server configuration identity");
    }

    @Test void publishedThreeShareMaterialFailureAndTheOriginalCatalogSnapshot() throws Exception {
        var env = new MockEnvironment();
        for (String code : List.of("pm1","pp2","bp1","pvs1","pm4","bp3","bp7")) env.withProperty(PREFIX+"local-batch-"+code+"-enabled","true");
        var config = org.mockito.Mockito.spy(new LocalEvidenceBatchConfiguration(mapper,new VariantAnalysisProperties(),env,null));
        org.mockito.Mockito.doThrow(new IllegalArgumentException("SYNTHETIC_MODEL_UNAVAILABLE")).when(config).transcriptMaterials();
        var batch = config.create(p -> mapper.nullNode());
        for (String code : List.of("pm1","pp2","bp1")) {
            var supplier = (java.util.function.Supplier<?>) ReflectionTestUtils.getField(java.util.Objects.requireNonNull(batch),code+"Factory");
            assertNotNull(supplier); assertEquals("SYNTHETIC_MODEL_UNAVAILABLE",assertThrows(IllegalArgumentException.class,supplier::get).getMessage());
        }
        org.mockito.Mockito.verify(config,org.mockito.Mockito.times(1)).transcriptMaterials();
        var material = org.mockito.Mockito.mock(CodingTranscriptMaterialSupply.class); org.mockito.Mockito.when(material.identity()).thenReturn(mapper.createObjectNode());
        var knowledge = org.mockito.Mockito.mock(PublishedCriterionKnowledge.class); org.mockito.Mockito.when(knowledge.identity()).thenReturn(mapper.createObjectNode());
        var raw = org.mockito.Mockito.mock(MissenseNativeSpliceBridge.class); org.mockito.Mockito.when(raw.identity()).thenReturn(mapper.createObjectNode());
        org.mockito.Mockito.doReturn(material).when(config).transcriptMaterials();
        org.mockito.Mockito.doReturn(knowledge).when(config).publishedKnowledge("bp1");
        org.mockito.Mockito.doReturn(raw).when(config).referenceNativeSplice();
        var captured = config.create(p -> mapper.nullNode(), "a".repeat(64));
        env.withProperty(PREFIX+"splicing-experiment-catalog",java.util.Objects.requireNonNull(temporary.resolve("now-missing.json").toString()));
        var factory = (java.util.function.Supplier<?>) ReflectionTestUtils.getField(java.util.Objects.requireNonNull(captured),"bp1Factory"); assertNotNull(factory);
        var bp1 = (GeneralBp1Runtime) factory.get();
        assertEquals(captured.identity().at("/bp7/experimentSnapshotId"),bp1.identity().at("/experimentCatalog/snapshotId"));
        assertEquals("a".repeat(64),bp1.identity().path("effectSourceSha256").asText());
    }

}
