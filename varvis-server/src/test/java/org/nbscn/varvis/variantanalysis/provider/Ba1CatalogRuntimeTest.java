package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.domain.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic branch/boundary checks. None of these observations is a real resource acceptance. */
class Ba1CatalogRuntimeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final PopulationFrequencyRuntimeTest facts = new PopulationFrequencyRuntimeTest();
    private final GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadExecutableV2();

    @ParameterizedTest
    @CsvSource({"0.049999999999999999,2000,NOT_MET", "0.05,2000,NOT_MET", "0.050000000000000001,2000,MET",
            "0.09,1999,NOT_EVALUATED", "0.09,2000,MET"})
    void strictGeneralBoundaryAndSelectedCohort(String faf, String an, String expected) throws Exception {
        var context = facts.diseaseContext("MYH7");
        var selected = catalog(snapshot()).select(mapping("HGNC:7577"), null);
        var result = evaluate(context, info(faf, an), selected);
        assertEquals(expected, result.at("/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("MET".equals(expected) ? "STANDALONE" : "NONE", result.path("appliedStrength").asText());
        assertEquals("2.1.0-catalog-ba1-native-qc", result.at("/deterministicTrace/evaluatorVersion").asText());
    }

    @ParameterizedTest
    @CsvSource({"0.00055999,NOT_MET", "0.00056000,NOT_MET", "0.00056001,MET"})
    void strictJointOnlyThresholdDoesNotAllowLocalExomes(String faf, String expected) throws Exception {
        var snapshot = snapshot();
        var context = facts.diseaseContext("MYH7");
        var clause = (ObjectNode) entry(snapshot, "HGNC:7577", "MONDO:0005045", "THRESHOLD").path("ba1");
        clause.put("requiredDataset", "JOINT").put("comparator", "GREATER_THAN").put("value", "0.00056");
        var mapping = mapping("HGNC:7577");
        var selection = catalog(snapshot).select(mapping, context);
        assertEquals("SELECTED", selection.path("status").asText(), selection.toPrettyString());
        var joint = evaluate(context, info(faf, "2000"), selection);
        assertEquals(expected, joint.at("/evaluationResult/status").asText(), joint.toPrettyString());
        assertEquals("MET".equals(expected) ? "STANDALONE" : "NONE", joint.path("appliedStrength").asText());

        var local = Ba1LocalExomes.selection(mapper, selection);
        assertEquals("BLOCKED", local.path("status").asText());
        assertEquals("BA1_JOINT_POPULATION_INPUT_REQUIRED", local.path("reasonCode").asText());
        assertEquals("jointPopulationObservation", local.at("/requiredInputs/0/field").asText());
        assertEquals(selection.path("policy"), local.path("policy"));
        assertEquals(selection.path("specification"), local.path("specification"));
        assertEquals("SELECTED", selection.path("status").asText());

        clause.put("requiredDataset", "EXOMES");
        assertEquals("UNSUPPORTED_CLAUSE", catalog(snapshot).select(mapping, context).path("reasonCode").asText());
    }

    @Test void qualityFailuresAreNeverValidNegativeEvidence() throws Exception {
        for (String fault : List.of("AF_COUNT", "FAF_AF", "FAF_GROUP", "MISSING_EXOME", "EMPTY_GENOME", "FILTER", "DISCORDANT", "MISSING_P", "FOUNDER")) {
            var values = info("0.01", "2000");
            switch (fault) {
                case "AF_COUNT" -> values.put("AC_joint_nfe", "500");
                case "FAF_AF" -> values.put("AF_joint_nfe", "0.0001");
                case "FAF_GROUP" -> values.put("faf95_joint_nfe", "0.001");
                case "MISSING_EXOME" -> values.remove("exomes_filters");
                case "EMPTY_GENOME" -> values.put("genomes_filters", "");
                case "FILTER" -> values.put("exomes_filters", "AC0");
                case "DISCORDANT" -> values.put("stat_union_p_value", "0.00009999");
                case "MISSING_P" -> values.remove("stat_union_p_value");
                case "FOUNDER" -> values.put("fafmax_faf95_max_gen_anc_joint", "fin");
                default -> throw new AssertionError(fault);
            }
            var result = evaluate(facts.diseaseContext("MYH7"), values, catalog(snapshot()).select(mapping("HGNC:7577"), null));
            assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText(), fault + result);
            assertFalse(result.at("/evaluationResult/reasonCode").asText().isBlank(), fault);
        }
        assertEquals("NOT_MET", evaluate(facts.diseaseContext("MYH7"), info("0.01", "2000"),
                catalog(snapshot()).select(mapping("HGNC:7577"), null)).at("/evaluationResult/status").asText());
    }

    @Test void diseaseAbsenceUnknownClausesAndOtherRulesDoNotCreateFalseGenerality() throws Exception {
        var snapshot = snapshot(); var entry = entry(snapshot, "HGNC:7577", "MONDO:0005045", "THRESHOLD");
        var selector = catalog(snapshot);
        assertEquals("BA1_DISEASE_CONTEXT_REQUIRED", selector.select(mapping("HGNC:7577"), null).path("reasonCode").asText());
        var context = facts.diseaseContext("MYH7");
        assertEquals("DISEASE_SPECIFIC", selector.select(mapping("HGNC:7577"), context).path("scope").asText());
        var pm2Only = context.deepCopy();
        ((ObjectNode) pm2Only.path("transcript")).put("selectionPolicy", "PM2_CONTEXT_ONLY");
        assertEquals("BA1_DISEASE_CONTEXT_CONFIRMATION_REQUIRED", selector.select(mapping("HGNC:7577"), pm2Only).path("reasonCode").asText());
        ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:9999999");
        assertEquals("BA1_DISEASE_SCOPE_UNRESOLVED", selector.select(mapping("HGNC:7577"), context).path("reasonCode").asText());
        var exclusion = snapshot.putArray("scopeExclusions").addObject().put("specificationId", "SYNTHETIC_NEW_GENE").put("version", "1")
                .put("hgncId", "HGNC:7577").put("mondoId", "MONDO:9999999").put("moi", "AUTOSOMAL_DOMINANT")
                .put("status", "REVIEWED_OUTSIDE_SPECIFICATION_SCOPE");
        exclusion.putArray("sourceRefs").addObject().put("url", "https://example.test/synthetic-explicit-exclusion").put("sha256", "c".repeat(64));
        assertEquals("GENERAL_BASELINE", catalog(snapshot).select(mapping("HGNC:7577"), context).path("scope").asText());
        ((ObjectNode) entry.path("ba1")).put("disposition", "UNSUPPORTED");
        assertEquals("UNSUPPORTED_CLAUSE", catalog(snapshot).select(mapping("HGNC:7577"), facts.diseaseContext("MYH7")).path("reasonCode").asText());
        ((ObjectNode) entry.path("ba1")).put("disposition", "DISABLED");
        assertEquals("NOT_APPLICABLE", catalog(snapshot).select(mapping("HGNC:7577"), facts.diseaseContext("MYH7")).path("status").asText());
        ((ObjectNode) entry.path("ba1")).put("disposition", "GENERAL");
        entry.put("otherCriteriaHaveSpecializations", true);
        assertEquals("SELECTED", catalog(snapshot).select(mapping("HGNC:7577"), null).path("status").asText());
        ((ObjectNode) entry.path("ba1")).remove("disposition");
        assertEquals("BLOCKED", catalog(snapshot).select(mapping("HGNC:7577"), null).path("status").asText());
    }

    @Test void transcriptAndOverlapMustBeResolvedBeforeEitherDirection() throws Exception {
        var snapshot = snapshot(); var entry = entry(snapshot, "HGNC:7577", "MONDO:0005045", "THRESHOLD");
        var context = facts.diseaseContext("MYH7");
        ((ObjectNode) entry.path("scope")).put("transcriptScope", "EXACT").putArray("transcripts").add("NM_000257.4");
        assertEquals("BA1_TRANSCRIPT_CONTEXT_REQUIRED", catalog(snapshot).select(mapping("HGNC:7577"), context).path("reasonCode").asText());
        ((ObjectNode) context.path("transcript")).put("selectionPolicy", "SYNTHETIC_VALIDATED_DISEASE_TRANSCRIPT").put("accession", "NM_000257").put("version", 4);
        assertEquals("DISEASE_SPECIFIC", catalog(snapshot).select(mapping("HGNC:7577"), context).path("scope").asText());
        ((ObjectNode) context.path("transcript")).put("version", 5);
        assertEquals("BA1_TRANSCRIPT_SCOPE_UNRESOLVED", catalog(snapshot).select(mapping("HGNC:7577"), context).path("reasonCode").asText());
        ((ObjectNode) context.path("transcript")).put("accession", "NM_999999");
        assertEquals("BA1_TRANSCRIPT_SCOPE_UNRESOLVED", catalog(snapshot).select(mapping("HGNC:7577"), context).path("reasonCode").asText());
        assertEquals("BA1_DISEASE_CONTEXT_REQUIRED", catalog(snapshot).select(mapping("HGNC:1", "HGNC:7577"), null).path("reasonCode").asText());
        ((ObjectNode) entry.path("scope")).put("status", "UNRESOLVED");
        assertEquals("BA1_SPECIFICATION_SCOPE_UNRESOLVED", catalog(snapshot).select(mapping("HGNC:7577"), context).path("reasonCode").asText());
        assertEquals("BA1_GENE_MAPPING_REQUIRED", catalog(snapshot).select(mapper.createObjectNode(), context).path("reasonCode").asText());
    }

    @Test void bindsOnlyVersionedManePairsInTheVerifiedBackground() throws Exception {
        var table = "#NCBI_GeneID\tEnsembl_Gene\tHGNC_ID\tsymbol\tname\tRefSeq_nuc\tRefSeq_prot\tEnsembl_nuc\tEnsembl_prot"
                + "\tMANE_status\tGRCh38_chr\tchr_start\tchr_end\tchr_strand\n"
                + "GeneID:1\tENSG00000000001.1\tHGNC:7577\tMYH7\tSynthetic\tNM_000257.4\tNP_000248.2"
                + "\tENST00000000017.3\tENSP00000000017.1\tMANE Select\tNC_000014.9\t23000000\t24000000\t-\n";
        var buffer = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(buffer)) {
            gzip.write(table.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        byte[] bytes = buffer.toByteArray();
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThrows(java.io.IOException.class, () -> Ba1ManeTranscriptPairs.read(bytes, "0".repeat(64)));
        var pairs = Ba1ManeTranscriptPairs.read(bytes, sha);
        assertNull(pairs.find("HGNC:1", "ENST00000000017.3", "14"));
        assertNull(pairs.find("HGNC:7577", "ENST00000000017.3", "13"));
        var snapshot = snapshot();
        var entry = entry(snapshot, "HGNC:7577", "MONDO:0005045", "UNSUPPORTED");
        var transcripts = ((ObjectNode) entry.path("scope")).put("transcriptScope", "EXACT").putArray("transcripts");
        transcripts.add("NM_000257.4");
        var context = facts.diseaseContext("MYH7");
        var selectedTranscript = (ObjectNode) context.path("transcript");
        selectedTranscript.put("accession", "ENST00000000017").put("version", 3)
                .put("selectionPolicy", "SYNTHETIC_VALIDATED_DISEASE_TRANSCRIPT");
        var mapping = mapping("HGNC:7577");
        assertEquals("BA1_TRANSCRIPT_SCOPE_UNRESOLVED", catalog(snapshot).select(mapping, context).path("reasonCode").asText());
        var catalog = new Ba1SpecificationCatalog(mapper, snapshot, pairs);
        var selected = catalog.select(mapping, context);
        assertEquals("UNSUPPORTED_CLAUSE", selected.path("reasonCode").asText());
        assertEquals("DISEASE_SPECIFIC", selected.path("scope").asText());
        assertEquals("MATCHED", selected.at("/transcriptScopeBinding/outcome").asText());
        assertEquals("NM_000257.4", selected.at("/transcriptScopeBinding/refseqTranscript").asText());
        assertEquals(sha, catalog.identity().at("/transcriptPairSource/sha256").asText());
        selectedTranscript.put("version", 2);
        assertFalse(catalog.select(mapping, context).has("transcriptScopeBinding"));
        selectedTranscript.put("version", 3);
        transcripts.removeAll().add("NM_000257.5");
        var mismatch = new Ba1SpecificationCatalog(mapper, snapshot, pairs).select(mapping, context);
        assertEquals("BA1_TRANSCRIPT_SCOPE_UNRESOLVED", mismatch.path("reasonCode").asText());
        assertEquals("RULE_TRANSCRIPT_MISMATCH", mismatch.at("/transcriptScopeBinding/outcome").asText());
        ((ObjectNode) context.at("/variant/inputAllele")).put("assembly", "GRCh37");
        assertFalse(catalog.select(mapping, context).has("transcriptScopeBinding"));
    }

    @Test void dataUpdateIntroducesNewBackgroundWithoutJavaOrCompletePolicyPackage() throws Exception {
        var first = snapshot(); var second = first.deepCopy();
        var entry = entry(second, "HGNC:7577", "MONDO:0005045", "THRESHOLD");
        ((ObjectNode) entry.path("ba1")).put("value", "0.007").put("comparator", "GREATER_THAN_OR_EQUAL")
                .put("minimumAnComparator", "GREATER_THAN");
        var context = facts.diseaseContext("MYH7");
        var oldCatalog = catalog(first); var newCatalog = catalog(second);
        assertNotEquals(oldCatalog.identity(), newCatalog.identity());
        assertEquals("NOT_MET", evaluate(context, info("0.007", "2001"), oldCatalog.select(mapping("HGNC:7577"), context)).at("/evaluationResult/status").asText());
        assertEquals("MET", evaluate(context, info("0.007", "2001"), newCatalog.select(mapping("HGNC:7577"), context)).at("/evaluationResult/status").asText());
        assertEquals("NOT_EVALUATED", evaluate(context, info("0.007", "2000"), newCatalog.select(mapping("HGNC:7577"), context)).at("/evaluationResult/status").asText());
        assertEquals("SELECTED", oldCatalog.select(mapping("HGNC:7577"), null).path("status").asText());
    }

    @Test void dynamicExceptionsUseVersionAndHashRatherThanNineConstant() throws Exception {
        var snapshot = snapshot(); var baseline = catalog(snapshot).baselinePolicy();
        ObjectNode exceptions = baseline.at("/frequencyRule/exceptionPolicy").deepCopy();
        var context = facts.diseaseContext("MYH7");
        ObjectNode added = context.at("/variant/evidenceQueryAllele").deepCopy();
        added.put("caid", "SYNTHETIC_NEW_SOURCE_ROW");
        ((ArrayNode) exceptions.path("entries")).add(added);
        ((ArrayNode) exceptions.path("sourceEntryIds")).add("SYNTHETIC_NEW_SOURCE_ROW");
        exceptions.put("sourceEntryCount", 10).put("sourceEntryIdsSha256", adapter.canonicalSha256(exceptions.path("sourceEntryIds")))
                .put("sourceSnapshotSha256", "c".repeat(64)).put("mappingReviewId", "SYNTHETIC_UPDATED_SOURCE_MAPPING");
        exceptions.put("entryCount", 10).put("snapshotDate", "2099-01-01-synthetic-only")
                .put("entrySetSha256", adapter.canonicalSha256(exceptions.path("entries")));
        snapshot.set("exceptionPolicy", exceptions);
        var selection = catalog(snapshot).select(mapping("HGNC:7577"), null);
        var result = evaluate(context, info("0.08", "2000"), selection);
        assertEquals("NOT_APPLICABLE", result.at("/criterionApplicability/status").asText());
        assertEquals("BA1_EXCEPTION_HIT", result.at("/evaluationResult/reasonCode").asText());
        var incomplete = snapshot.deepCopy();
        ObjectNode empty = (ObjectNode) incomplete.path("exceptionPolicy");
        empty.putArray("entries"); empty.put("entryCount", 0).put("entrySetSha256", adapter.canonicalSha256(empty.path("entries")));
        assertEquals("BA1_EXCEPTION_MAPPING_INCOMPLETE", evaluate(context, info("0.08", "2000"),
                catalog(incomplete).select(mapping("HGNC:7577"), null)).at("/evaluationResult/reasonCode").asText());
        incomplete = snapshot.deepCopy();
        ObjectNode wrongAssembly = (ObjectNode) incomplete.path("exceptionPolicy");
        for (JsonNode value : wrongAssembly.path("entries")) ((ObjectNode) value).put("assembly", "GRCh37");
        wrongAssembly.put("entrySetSha256", adapter.canonicalSha256(wrongAssembly.path("entries")));
        assertEquals("BA1_EXCEPTION_MAPPING_INCOMPLETE", evaluate(context, info("0.08", "2000"),
                catalog(incomplete).select(mapping("HGNC:7577"), null)).at("/evaluationResult/reasonCode").asText());
        exceptions.put("entrySetSha256", "0".repeat(64));
        assertEquals("BA1_EXCEPTION_SNAPSHOT_UNKNOWN", evaluate(context, info("0.08", "2000"),
                catalog(snapshot).select(mapping("HGNC:7577"), null)).at("/evaluationResult/reasonCode").asText());
    }

    @Test void incompleteAndConflictingCatalogNeverFallsBack() throws Exception {
        var snapshot = snapshot();
        ((ObjectNode) snapshot.path("metadata")).put("enumeratedEntityCount", 1);
        assertEquals("BLOCKED", catalog(snapshot).select(mapping("HGNC:7577"), null).path("status").asText());
        snapshot = snapshot();
        var first = entry(snapshot, "HGNC:7577", "MONDO:0005045", "THRESHOLD");
        var second = first.deepCopy().put("specificationId", "SYNTHETIC_SECOND");
        ((ArrayNode) snapshot.path("entries")).add(second);
        ((ObjectNode) snapshot.path("metadata")).put("entryCount", 2).put("selectedReleasedCount", 2);
        assertEquals("BA1_SPECIFICATION_CONFLICT", catalog(snapshot).select(mapping("HGNC:7577"), facts.diseaseContext("MYH7")).path("reasonCode").asText());
    }

    @Test void reviewIdentityAndMetricMigrationMustBindThisBatchAndBothSources() throws Exception {
        var snapshot = snapshot();
        ((ObjectNode) snapshot.at("/metadata/batchReview")).put("sourceCaptureSha256", "c".repeat(64));
        assertEquals("BA1_CATALOG_REVIEW_REQUIRED", catalog(snapshot).identity().path("reasonCode").asText());
        snapshot = snapshot();
        ((ObjectNode) snapshot.at("/metadata/batchReview")).put("compilerVersion", "another-compiler");
        assertEquals("UNAVAILABLE", catalog(snapshot).identity().path("status").asText());

        for (String original : List.of("MAF", "FAF")) {
            snapshot = snapshot();
            var clause = (ObjectNode) entry(snapshot, "HGNC:7577", "MONDO:0005045", "THRESHOLD").path("ba1");
            clause.put("originalMetric", original);
            String basis = "MAF".equals(original) ? "EXPLICIT_CALCULATED_FREQUENCY_MIGRATION" : "EXPLICIT_FAF_DEFINITION_ALIGNMENT";
            var review = ((ObjectNode) snapshot.path("metadata")).putObject("semanticReview")
                    .put("reviewId", "SYNTHETIC_SOURCE_REVIEW").put("reviewSha256", "c".repeat(64));
            review.putArray("sources").addObject().put("url", "https://example.test/guidance").put("sha256", "d".repeat(64));
            var migration = clause.putObject("migration").put("status", "REVIEWED").put("reviewId", "SYNTHETIC_SOURCE_REVIEW")
                    .put("reviewSha256", "c".repeat(64)).put("basis", basis);
            var sources = migration.putArray("sources");
            sources.addAll((ArrayNode) review.path("sources")); sources.addAll((ArrayNode) clause.path("sourceRefs"));
            assertEquals("SELECTED", catalog(snapshot).select(mapping("HGNC:7577"), facts.diseaseContext("MYH7")).path("status").asText());
            migration.put("basis", "UNREVIEWED_ALIGNMENT");
            assertEquals("UNSUPPORTED_CLAUSE", catalog(snapshot).select(mapping("HGNC:7577"), facts.diseaseContext("MYH7")).path("reasonCode").asText());
            migration.put("basis", basis).put("reviewSha256", "e".repeat(64));
            assertEquals("BLOCKED", catalog(snapshot).select(mapping("HGNC:7577"), facts.diseaseContext("MYH7")).path("status").asText());
            migration.put("reviewSha256", "c".repeat(64)); sources.remove(1);
            assertEquals("BLOCKED", catalog(snapshot).select(mapping("HGNC:7577"), facts.diseaseContext("MYH7")).path("status").asText());
        }
    }

    @Test void runtimeReusesObservationAndPreservesContextResultsWithoutFakeDisease() throws Exception {
        var context = facts.diseaseContext("MYH7");
        var allele = context.at("/variant/evidenceQueryAllele");
        var key = new NormalizedVariantKey("GRCh38", allele.path("contig").asText(), allele.path("position").asInt(), "G", "A");
        var candidate = RuntimeEvidenceV2ProviderWiringTest.variant(key);
        var annotation = mapper.createObjectNode().put("geneSymbol", "SYNTHETIC_GENE").put("geneId", "ENSG_SYNTHETIC")
                .put("source", "VEP_CACHED").put("version", "116.0").put("status", "COMPLETED");
        candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
        var snapshot = snapshot();
        snapshot.putArray("geneMappings").addObject().put("hgncId", "HGNC:7577").putArray("ensemblGeneIds").add("ENSG_SYNTHETIC");
        snapshot.putObject("geneMappingSource").put("status", "COMPLETE").put("rawSha256", "d".repeat(64))
                .put("url", "https://example.test/synthetic-hgnc").put("recordCount", 1);
        VariantEvidenceProvider noQuery = new VariantEvidenceProvider() {
            public String source() { return "GNOMAD"; }
            public String version() { return "4.1"; }
            public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(List<VariantInterpretation> values, AnalysisContext analysis) {
                throw new AssertionError("Compatible population observation must be reused");
            }
        };
        var runtime = new Ba1AlleleRuntime(mapper, profile, noQuery, catalog(snapshot));
        var observation = facts.observation(context, info("0.08", "2000"), "PASS");
        var before = observation.deepCopy();
        var result = runtime.evaluate(context.at("/variant/alleleId").asText(), candidate,
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key), observation, "a".repeat(64), mapper.createArrayNode().add(context));
        assertEquals(0, result.path("providerCalls").asInt());
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("MET", result.at("/contextResults/0/evaluation/evaluationResult/status").asText());
        assertFalse(result.path("evaluation").has("contextId"));
        assertEquals(before, observation);
        assertEquals(result, runtime.evaluate(context.at("/variant/alleleId").asText(), candidate,
                RuntimeEvidenceV2ProviderWiringTest.analysisContext(key), observation, "a".repeat(64), mapper.createArrayNode().add(context)));
    }

    @ParameterizedTest @CsvSource({"0.08,MET", "0.01,NOT_MET"})
    void nativePassSupplementRepairsOnlyMissingQcAndBindsExactJointObservation(String faf, String expected,
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        var context = facts.diseaseContext("MYH7");
        var allele = context.at("/variant/evidenceQueryAllele");
        var values = info(faf, "2000"); values.remove("exomes_filters"); values.remove("genomes_filters");
        var observation = facts.observation(context, values, "PASS");
        var raw = observation.path("rawPopulationRecord").asText();
        var rawSha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        var carrier = wiring.reparsePopulationObservation(context, observation, "a".repeat(64),
                GnomadPopulationInputProfile.loadBusinessPm2Supporting(), profile).carrier();
        wiring.preserveBa1SubtypeFilters(carrier, raw);
        var before = carrier.deepCopy();
        var settings = new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties();
        settings.getGnomad().setTabixExecutable("SYNTHETIC_NEVER_EXECUTED");
        JsonNode policy;
        try (var stream = getClass().getResourceAsStream("/acmg/ba1-native-subtype-source-policy-v1.json")) {
            policy = mapper.readTree(java.util.Objects.requireNonNull(stream));
        }
        var helper = new Ba1SubtypeFilterSupplement(mapper, settings, policy, temporary,
                (command, output, error, budget, resource, querySha, diagnostics) -> {
                    String line = "chr" + allele.path("contig").asText() + "\t" + allele.path("position").asInt() + "\t.\t"
                            + allele.path("reference").asText() + "\t" + allele.path("alternate").asText() + "\t.\tPASS\t.\n";
                    java.nio.file.Files.writeString(output, line);
                    diagnostics.add(Map.of("status", "SUCCEEDED", "attempt", 1, "maximumAttempts", 1,
                            "effectiveAttemptMillis", 60000L, "stdoutBytes", java.nio.file.Files.size(output), "stderrBytes", 0L));
                });
        var supplement = helper.supplement(allele, java.util.Set.of("exomes", "genomes"), "4.1", rawSha);
        var selection = catalog(snapshot()).select(mapping("HGNC:7577"), null);
        var evaluator = new GnomadPopulationCriterionEvaluator(mapper);
        String id = context.at("/variant/alleleId").asText();
        assertEquals("NOT_EVALUATED", evaluator.evaluateCatalogBa1(id, carrier, profile, selection).at("/evaluationResult/status").asText());
        var result = evaluator.evaluateCatalogBa1(id, carrier, profile, selection, supplement, rawSha);
        assertEquals(expected, result.at("/evaluationResult/status").asText(), result.toPrettyString());
        assertTrue(result.at("/deterministicTrace/parameters").toString().contains("ba1.nativeSubtypeFilterSupplementSha256"));
        assertEquals("NOT_EVALUATED", evaluator.evaluateCatalogBa1(id, carrier, profile, selection, supplement, "e".repeat(64))
                .at("/evaluationResult/status").asText());
        assertEquals(before, carrier, "Supplement never replaces missing INFO or joint frequency evidence");
        for (var measurement : carrier.path("measurements")) if ("gnomad.joint.exomes.filtersPresence".equals(measurement.path("metricId").asText()))
            ((ObjectNode) measurement).put("rawValue", "AC0").put("normalizedValue", "AC0");
        assertEquals("NOT_EVALUATED", evaluator.evaluateCatalogBa1(id, carrier, profile, selection, supplement, rawSha)
                .at("/evaluationResult/status").asText());
        carrier = before.deepCopy();
        for (var measurement : carrier.path("measurements")) if ("gnomad.joint.filter".equals(measurement.path("metricId").asText()))
            ((ObjectNode) measurement).put("rawValue", "FAIL").put("normalizedValue", "FAIL");
        assertEquals("NOT_EVALUATED", evaluator.evaluateCatalogBa1(id, carrier, profile, selection, supplement, rawSha)
                .at("/evaluationResult/status").asText());

        var key = new NormalizedVariantKey("GRCh38", allele.path("contig").asText(), allele.path("position").asInt(),
                allele.path("reference").asText(), allele.path("alternate").asText());
        VariantEvidenceProvider noQuery = new VariantEvidenceProvider() {
            public String source() { return "GNOMAD"; }
            public String version() { return "4.1"; }
            public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(List<VariantInterpretation> variants, AnalysisContext analysis) {
                throw new AssertionError("Expected reuse of the unchanged joint observation");
            }
        };
        var runtime = new Ba1AlleleRuntime(mapper, profile, noQuery, catalog(snapshot()), helper);
        var candidate = RuntimeEvidenceV2ProviderWiringTest.variant(key);
        var contexts = mapper.createArrayNode().add(context);
        var first = runtime.evaluate(id, candidate, RuntimeEvidenceV2ProviderWiringTest.analysisContext(key), observation, "a".repeat(64), contexts);
        assertEquals(2, first.path("providerCalls").asInt(), first.toPrettyString());
        assertEquals(expected, first.at("/contextResults/0/evaluation/evaluationResult/status").asText());
        var second = runtime.evaluate(id, candidate, RuntimeEvidenceV2ProviderWiringTest.analysisContext(key), observation, "a".repeat(64), contexts);
        assertEquals(0, second.path("providerCalls").asInt());
        assertEquals(first.path("subtypeFilterEvidence"), second.path("subtypeFilterEvidence"));
        runtime.beginExecution();
        assertEquals(2, runtime.evaluate(id, candidate, RuntimeEvidenceV2ProviderWiringTest.analysisContext(key), observation, "a".repeat(64), contexts)
                .path("providerCalls").asInt());
        values.put("exomes_filters", "AC0");
        var failedOriginal = facts.observation(context, values, "PASS");
        runtime.beginExecution();
        var refused = runtime.evaluate(id, candidate, RuntimeEvidenceV2ProviderWiringTest.analysisContext(key), failedOriginal, "a".repeat(64), contexts);
        assertEquals(0, refused.path("providerCalls").asInt());
        assertFalse(refused.has("subtypeFilterEvidence"));
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {
            "valid", "denominator", "percent", "unreviewed", "metric", "unknownCondition", "recomputed", "version"})
    void reciprocalFamilyRequiresEquivalentNumbersBoundReviewAndEveryCondition(String fault) throws Exception {
        var snapshot = snapshot();
        var clause = (ObjectNode) entry(snapshot, "HGNC:7577", "MONDO:0005045", "THRESHOLD").path("ba1");
        clause.put("semanticFamily", "RECIPROCAL_GRPMAX_FAF_THRESHOLD_V1").put("originalMetric", "FAF")
                .put("value", "0.0001").put("derivedPolicyVersion", "a01-ba1-reciprocal-faf-derived-v1");
        clause.putArray("conditions").add("GNOMAD_FILTERED_VARIANTS_EXCLUDED");
        var calculation = clause.putObject("calculationProvenance").put("reciprocalDenominator", "10000")
                .put("sourcePercent", "0.01").put("equivalentRepresentationsVerified", true).put("cutoffRecomputed", false);
        var review = ((ObjectNode) snapshot.path("metadata")).putObject("semanticReview")
                .put("reviewId", "SYNTHETIC_NOT_REAL_REVIEW").put("reviewSha256", "c".repeat(64));
        review.set("sources", clause.path("sourceRefs").deepCopy());
        var migration = clause.putObject("migration").put("status", "REVIEWED")
                .put("reviewId", "SYNTHETIC_NOT_REAL_REVIEW").put("reviewSha256", "c".repeat(64))
                .put("basis", "EXPLICIT_FAF_DEFINITION_ALIGNMENT");
        migration.set("sources", clause.path("sourceRefs").deepCopy());
        switch (fault) {
            case "denominator" -> calculation.put("reciprocalDenominator", "9999");
            case "percent" -> calculation.put("sourcePercent", "0.1");
            case "unreviewed" -> migration.put("reviewSha256", "d".repeat(64));
            case "metric" -> clause.put("originalMetric", "AF");
            case "unknownCondition" -> clause.withArray("conditions").add("UNKNOWN_ADDITIONAL_RESTRICTION");
            case "recomputed" -> calculation.put("cutoffRecomputed", true);
            case "version" -> clause.put("derivedPolicyVersion", "unreviewed-v2");
            default -> { }
        }
        var selection = catalog(snapshot).select(mapping("HGNC:7577"), facts.diseaseContext("MYH7"));
        assertEquals(fault.equals("valid") ? "SELECTED" : "BLOCKED", selection.path("status").asText(), selection.toPrettyString());
        if (fault.equals("valid")) {
            assertEquals("MET", evaluate(facts.diseaseContext("MYH7"), info("0.0001", "2000"), selection).at("/evaluationResult/status").asText());
            assertEquals("NOT_MET", evaluate(facts.diseaseContext("MYH7"), info("0.000099", "2000"), selection).at("/evaluationResult/status").asText());
        }
    }

    @Test void actualPythonCompiledScopesAndParametersUseTheSameJavaEvaluator() throws Exception {
        JsonNode fixture;
        try (var input = getClass().getResourceAsStream("/acmg/ba1-python-compiled-transfer.json")) {
            fixture = mapper.readTree(java.util.Objects.requireNonNull(input));
        }
        for (var row : fixture.path("rows")) {
            var document = snapshot();
            String hgnc = row.at("/scope/hgncIds/0").asText();
            String disease = row.at("/scope/mondoIds/0").asText();
            var entry = entry(document, hgnc, disease, "THRESHOLD");
            entry.set("scope", row.path("scope").deepCopy());
            entry.set("ba1", row.path("ba1").deepCopy());
            if (row.has("semanticReview")) ((ObjectNode) document.path("metadata")).set("semanticReview", row.path("semanticReview").deepCopy());
            var context = facts.diseaseContext("MYH7");
            ((ObjectNode) context.path("gene")).put("hgncId", hgnc).put("symbol", row.at("/scope/geneSymbols/0").asText());
            ((ObjectNode) context.path("disease")).put("mondoId", disease);
            context.put("moi", row.at("/scope/moi/0").asText());
            String transcript = row.at("/scope/transcripts/0").asText();
            ((ObjectNode) context.path("transcript")).put("accession", transcript.substring(0, transcript.lastIndexOf('.')))
                    .put("version", 1).put("selectionPolicy", "SYNTHETIC_VALIDATED_DISEASE_TRANSCRIPT");
            context.put("contextId", adapter.canonicalContextId(context));
            var selector = catalog(document);
            var selection = selector.select(mapping(hgnc), context);
            assertEquals("SELECTED", selection.path("status").asText(), selection.toString());
            for (var example : row.path("cases")) {
                var result = evaluate(context, info(example.path("faf").asText(), example.path("an").asText()), selection);
                assertEquals(example.path("expected").asText(), result.at("/evaluationResult/status").asText(), example + result.toString());
            }
            for (var fault : row.path("qualityFaults")) {
                for (String faf : List.of("0.00000001", "0.01")) {
                    var result = evaluateQualityFault(context, info(faf, "3000"), selection, fault.asText());
                    assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText(), fault + result.toString());
                    assertFalse(result.at("/evaluationResult/reasonCode").asText().isBlank());
                }
            }
            for (String fault : List.of("unknown", "duplicate", "string", "number", "object", "null", "missing")) {
                var altered = document.deepCopy();
                var clause = (ObjectNode) altered.at("/entries/0/ba1");
                switch (fault) {
                    case "unknown" -> clause.putArray("conditions").add("UNKNOWN_BA1_RESTRICTION");
                    case "duplicate" -> clause.putArray("conditions").add("GNOMAD_FILTERED_VARIANTS_EXCLUDED").add("GNOMAD_FILTERED_VARIANTS_EXCLUDED");
                    case "string" -> clause.put("conditions", "GNOMAD_FILTERED_VARIANTS_EXCLUDED");
                    case "number" -> clause.putArray("conditions").add(1);
                    case "object" -> clause.putObject("conditions");
                    case "null" -> clause.putNull("conditions");
                    case "missing" -> clause.remove("conditions");
                    default -> throw new AssertionError(fault);
                }
                assertEquals("BLOCKED", catalog(altered).select(mapping(hgnc), context).path("status").asText(), fault);
            }
            assertEquals("BLOCKED", selector.select(mapper.createObjectNode(), context).path("status").asText());
            assertEquals("BA1_CONTEXT_GENE_MAPPING_MISMATCH", selector.select(mapping("HGNC:123456"), context).path("reasonCode").asText());
            assertEquals("BA1_DISEASE_CONTEXT_REQUIRED", selector.select(mapping(hgnc), null).path("reasonCode").asText());
            var wrong = context.deepCopy(); wrong.put("moi", "AUTOSOMAL_DOMINANT");
            assertEquals("BLOCKED", selector.select(mapping(hgnc), wrong).path("status").asText());
            wrong.remove("moi");
            assertEquals("BLOCKED", selector.select(mapping(hgnc), wrong).path("status").asText());
            wrong = context.deepCopy(); ((ObjectNode) wrong.path("disease")).put("mondoId", "MONDO:9999999");
            assertEquals("BLOCKED", selector.select(mapping(hgnc), wrong).path("status").asText());
            ((ObjectNode) wrong.path("disease")).remove("mondoId");
            assertEquals("BLOCKED", selector.select(mapping(hgnc), wrong).path("status").asText());
            wrong = context.deepCopy(); ((ObjectNode) wrong.path("transcript")).put("version", 2);
            assertEquals("BLOCKED", selector.select(mapping(hgnc), wrong).path("status").asText());
            ((ObjectNode) wrong.path("transcript")).remove("accession");
            assertEquals("BLOCKED", selector.select(mapping(hgnc), wrong).path("status").asText());
            ((ObjectNode) document.at("/metadata/batchReview")).put("status", "PENDING");
            assertEquals("BLOCKED", catalog(document).select(mapping(hgnc), context).path("status").asText());
        }
    }

    @Test void sharedConditionVersionChangesCompleteCacheIdentityWithoutRewritingOldEvidence() throws Exception {
        var runtime = new Ba1AlleleRuntime(mapper,
                new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties(), catalog(snapshot()));
        var currentBinding = runtime.identity();
        assertEquals("2.2.1-catalog-ba1-shared-conditions", currentBinding.path("algorithmVersion").asText());
        var oldBinding = currentBinding.deepCopy().put("algorithmVersion", "2.2.0-catalog-ba1-conversion-source");
        var oldKeyInput = mapper.createObjectNode().put("analysisId", "synthetic-analysis").put("candidateId", "synthetic-candidate")
                .put("alleleId", "synthetic-normalized-allele").put("executionSourceSha256", "a".repeat(64)).put("dataClass", "SYNTHETIC");
        oldKeyInput.putObject("executionProfile").put("profileId", "SYNTHETIC_SAVED_PROFILE");
        oldKeyInput.putArray("declaredContexts"); oldKeyInput.set("ba1RuntimeBinding", oldBinding);
        var newKeyInput = oldKeyInput.deepCopy(); newKeyInput.set("ba1RuntimeBinding", currentBinding);
        var artifacts = new AcmgBusinessArtifacts(mapper);
        String oldKey = artifacts.requestHash(oldKeyInput), newKey = artifacts.requestHash(newKeyInput);
        assertNotEquals(oldKey, newKey);
        var saved = mapper.createObjectNode();
        var result = saved.putArray("candidates").addObject().putObject("ba1").put("reuseKey", oldKey);
        result.putObject("evidence").putObject("resourceExecution").put("status", "SUCCEEDED");
        ((ObjectNode) result.path("evidence")).putObject("resourceAvailability").put("status", "AVAILABLE");
        result.putObject("evaluation").putObject("evaluationResult").put("status", "NOT_EVALUATED");
        var preserved = saved.deepCopy();
        // This unit control tests the complete cache key. Verified durable history is covered by
        // the public routed-business test; do not mock public read to bypass trustedRead integrity.
        var restored = mapper.readTree(mapper.writeValueAsBytes(saved));
        assertEquals(oldKey, restored.at("/candidates/0/ba1/reuseKey").asText());
        assertNotEquals(newKey, restored.at("/candidates/0/ba1/reuseKey").asText());
        assertEquals(preserved, restored); assertEquals(preserved, saved);
    }

    private ObjectNode evaluateQualityFault(ObjectNode context, Map<String, String> values, ObjectNode selection, String fault) {
        switch (fault) {
            case "EXOMES_FILTER" -> values.put("exomes_filters", "AC0");
            case "GENOMES_FILTER" -> values.put("genomes_filters", "RF");
            case "MISSING_EXOMES", "INCOMPLETE_SUPPLEMENT" -> values.remove("exomes_filters");
            case "MISSING_GENOMES" -> values.remove("genomes_filters");
            case "EMPTY_GENOMES" -> values.put("genomes_filters", "");
            case "NO_RECORD" -> { }
            default -> throw new AssertionError(fault);
        }
        var observation = facts.observation(context, values, "PASS");
        var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        var carrier = wiring.reparsePopulationObservation(context, observation, "a".repeat(64),
                GnomadPopulationInputProfile.loadBusinessPm2Supporting(), profile).carrier();
        wiring.preserveBa1SubtypeFilters(carrier, observation.path("rawPopulationRecord").asText());
        if ("NO_RECORD".equals(fault)) {
            ((ObjectNode) carrier.path("queryOutcome")).put("status", "NO_RECORD").put("recordCount", 0);
            ((ArrayNode) carrier.at("/source/sourceRecords")).removeAll();
            ((ArrayNode) carrier.path("measurements")).removeAll();
        }
        // A status label without the required raw records/source identities is not quality proof.
        var incomplete = mapper.createObjectNode().put("status", "VERIFIED_PASS");
        incomplete.putArray("results").addObject().put("dataset", "exomes").put("status", "PASS");
        return new GnomadPopulationCriterionEvaluator(mapper).evaluateCatalogBa1(context.at("/variant/alleleId").asText(),
                carrier, profile, selection, "INCOMPLETE_SUPPLEMENT".equals(fault) ? incomplete : null, "a".repeat(64));
    }

    private ObjectNode evaluate(ObjectNode context, Map<String, String> values, ObjectNode selection) {
        var observation = facts.observation(context, values, "PASS");
        var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        var carrier = wiring.reparsePopulationObservation(context, observation, "a".repeat(64),
                GnomadPopulationInputProfile.loadBusinessPm2Supporting(), profile).carrier();
        wiring.preserveBa1SubtypeFilters(carrier, observation.path("rawPopulationRecord").asText());
        return new GnomadPopulationCriterionEvaluator(mapper).evaluateCatalogBa1(context.at("/variant/alleleId").asText(), carrier, profile, selection);
    }
    private Map<String, String> info(String faf, String an) {
        var info = facts.jointInfo(faf, an, "0.1");
        String af = new java.math.BigDecimal("200").divide(new java.math.BigDecimal(an), java.math.MathContext.DECIMAL64).toPlainString();
        info.put("AC_joint_nfe", "200"); info.put("AC_grpmax_joint", "200");
        info.put("AF_joint_nfe", af); info.put("AF_grpmax_joint", af);
        return info;
    }
    private Ba1SpecificationCatalog catalog(ObjectNode snapshot) { return new Ba1SpecificationCatalog(mapper, snapshot); }
    private ObjectNode mapping(String... genes) {
        var mapping = mapper.createObjectNode().put("status", "RESOLVED");
        for (String gene : genes) mapping.withArray("hgncIds").add(gene);
        return mapping;
    }
    private ObjectNode snapshot() {
        var snapshot = mapper.createObjectNode().put("schemaVersion", Ba1SpecificationCatalog.SCHEMA);
        var metadata = snapshot.putObject("metadata").put("snapshotId", "SYNTHETIC_DEVELOPER_ONLY")
                .put("sourceBase", "https://example.test/cspec").put("status", "COMPLETE").put("publicationScope", "Released")
                .put("declaredEntityCount", 0).put("enumeratedEntityCount", 0).put("entryCount", 0).put("selectedReleasedCount", 0);
        metadata.putArray("pages").addObject().put("page", 0).put("returnedCount", 0).put("rawSha256", "a".repeat(64));
        metadata.put("compilerVersion", "synthetic-test").put("captureManifestSha256", "a".repeat(64));
        metadata.putObject("batchReview").put("status", "REVIEWED").put("reviewId", "SYNTHETIC_ONLY")
                .put("compilerVersion", "synthetic-test").put("sourceCaptureSha256", "a".repeat(64));
        snapshot.putArray("entries"); return snapshot;
    }
    private ObjectNode entry(ObjectNode snapshot, String gene, String disease, String disposition) {
        var entry = ((ArrayNode) snapshot.path("entries")).addObject().put("specificationId", "SYNTHETIC_NEW_GENE")
                .put("version", "1").put("publicationStatus", "Released").put("rawSha256", "b".repeat(64));
        var scope = entry.putObject("scope").put("status", "RESOLVED").put("transcriptScope", "UNRESTRICTED");
        scope.putArray("hgncIds").add(gene); scope.putArray("mondoIds").add(disease); scope.putArray("moi").add("AUTOSOMAL_DOMINANT");
        var clause = entry.putObject("ba1").put("disposition", disposition).put("metric", "gnomad.joint.faf95max")
                .put("semanticFamily", "SIMPLE_FAF95_THRESHOLD_V1").put("originalMetric", "gnomad.joint.faf95max")
                .put("unit", "fraction").put("comparator", "GREATER_THAN_OR_EQUAL").put("value", "0.001")
                .put("minimumAn", "2000").put("minimumAnComparator", "GREATER_THAN_OR_EQUAL");
        clause.putArray("conditions"); clause.putArray("sourceRefs").addObject().put("url", "https://example.test/synthetic").put("sha256", "b".repeat(64));
        clause.putArray("ancestries").add("afr").add("amr").add("eas").add("mid").add("nfe").add("sas");
        ((ObjectNode) snapshot.path("metadata")).put("entryCount", snapshot.path("entries").size()).put("selectedReleasedCount", snapshot.path("entries").size());
        return entry;
    }
}
