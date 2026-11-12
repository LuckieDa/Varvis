package org.nbscn.varvis.variantanalysis.provider;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic rules/quality tests: local data scope never masquerades as joint evidence. */
class Ba1LocalExomesTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadLocalBa1();
    final PopulationFrequencyRuntimeTest facts = new PopulationFrequencyRuntimeTest();
    Map<String,String> info(String faf, String an) {
        var values = new LinkedHashMap<String,String>();
        facts.jointInfo(faf, an, "0.1").forEach((k,v) -> {
            if (!k.startsWith("stat_") && !k.endsWith("_filters")) values.put(k.replace("_joint", ""),v);
        });
        String af = new java.math.BigDecimal("200").divide(new java.math.BigDecimal(an),java.math.MathContext.DECIMAL64).toPlainString();
        for (String suffix : List.of("_nfe", "_grpmax")) { values.put("AC"+suffix,"200"); values.put("AF"+suffix,af); }
        return values;
    }
    ObjectNode evaluate(Map<String,String> values, String filter) throws Exception {
        var context = facts.diseaseContext("MYH7"); var carrier = baseCarrier(context);
        carrier.set("measurements",mapper.valueToTree(profile.parse(filter,values,0,1).getMeasurements()));
        var selection = new Ba1SpecificationCatalog(mapper,snapshot()).select(mapping("HGNC:7577"),null);
        return new GnomadPopulationCriterionEvaluator(mapper).evaluateCatalogBa1(context.at("/variant/alleleId").asText(),carrier,profile,Ba1LocalExomes.selection(mapper,selection));
    }
    @ParameterizedTest @CsvSource({"0.049999,2000,NOT_MET","0.05,2000,NOT_MET","0.050001,2000,MET","0.08,1999,NOT_EVALUATED","0.08,2000,MET"})
    void localFactsGiveRealDecisionsWithoutJoint(String faf,String an,String status) throws Exception {
        var r=evaluate(info(faf,an),"PASS");
        assertEquals(status,r.at("/evaluationResult/status").asText(),r.toPrettyString());
        assertEquals("BA1_LOCAL_EXOMES_V1",r.at("/dataScope/policyId").asText());
        assertEquals("EXOMES",r.at("/dataScope/dataset").asText());
        assertEquals(3,r.at("/dataScope/limitations").size());
        assertEquals("NOT_ESTABLISHED",r.at("/dataScope/clinicalAdoption").asText());
    }
    @Test void qualityGapsNeverBecomeNegativeResults() throws Exception {
        for(String fault:List.of("AF","AC","FAF","ANCESTRY","AN","FILTER","MISSING_FILTER")) {
            var v=info("0.01","2000"); String filter="PASS";
            switch(fault) {
                case "AF" -> v.put("AF_nfe","0.9");
                case "AC" -> v.put("AC_nfe","9999");
                case "FAF" -> v.put("faf95_nfe","0.09");
                case "ANCESTRY" -> v.put("fafmax_faf95_max_gen_anc","fin");
                case "AN" -> v.remove("AN_nfe");
                case "FILTER" -> filter="AC0";
                case "MISSING_FILTER" -> filter=".";
            }
            var r=evaluate(v,filter);
            assertEquals("NOT_EVALUATED",r.at("/evaluationResult/status").asText(),fault+r.toPrettyString());
            assertFalse(r.at("/evaluationResult/reasonCode").asText().isBlank());
        }
    }
    @Test void blockedCatalogSelectionKeepsItsSpecificReason() {
        var blocked=Ba1SpecificationCatalog.unavailable(mapper,"BA1_CATALOG_NOT_CONFIGURED").select(mapping("HGNC:7577"),null);
        var local=Ba1LocalExomes.selection(mapper,blocked);
        assertEquals(blocked.path("reasonCode"),local.path("reasonCode"));
        assertFalse(local.has("sourcePolicySha256"));
    }
    @Test void profileUsesOnlyNativeExomeMetricsAndLeavesOldProfilesUnchanged() {
        assertTrue(profile.isCriterionEvaluationEnabled());
        assertFalse(GnomadPopulationInputProfile.loadDefault().isCriterionEvaluationEnabled());
        assertEquals("JOINT",GnomadPopulationInputProfile.loadExecutableV2().getDatasetType());
        var parsed=profile.parse("PASS",info("0.08","2000"),0,1);
        assertFalse(mapper.valueToTree(parsed.getMeasurements()).toString().contains("gnomad.joint."));
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
    private ObjectNode mapping(String... genes) {
        var mapping = mapper.createObjectNode().put("status", "RESOLVED");
        for (String gene : genes) mapping.withArray("hgncIds").add(gene);
        return mapping;
    }
    ObjectNode baseCarrier(ObjectNode context) {
        JsonNode resource = profile.getDocument().path("resources").get(0);
        ObjectNode carrier = mapper.createObjectNode();
        carrier.put("evidenceType", "POPULATION_FREQUENCY");
        carrier.set("queryAllele", context.path("variant").path("evidenceQueryAllele").deepCopy());
        ObjectNode source = carrier.putObject("source");
        source.put("sourceId", resource.path("resourceId").asText());
        java.util.Objects.requireNonNull(resource, "Test resource must exist in the registry");
        source.put("resourceVersion", resource.path("resourceVersion").asText());
        source.put("resourceSha256", resource.path("identityFingerprintSha256").asText());
        source.put("retrievedAt", "2026-09-02T00:00:00Z");
        ObjectNode locator = mapper.createObjectNode();
        locator.put("locatorType", "GENOMIC_REGION");
        locator.put("locatorValue", "GNOMAD_EXOMES_V4_1_GRCH38_FULL_SITES:GRCh38:exact-allele");
        locator.put("sourceRecordId", "GNOMAD_EXOMES_V4_1_GRCH38_FULL_SITES:exact-allele");
        locator.put("artifactSha256", resource.path("identityFingerprintSha256").asText());
        locator.put("retrievedAt", "2026-09-02T00:00:00Z");
        ObjectNode record = mapper.createObjectNode();
        record.put("recordId", "GNOMAD_EXOMES_V4_1_GRCH38_FULL_SITES:exact-allele");
        record.set("locator", locator.deepCopy());
        source.putArray("sourceRecords").add(record);
        source.putArray("sourceLocators").add(locator);
        carrier.putObject("resourceExecution").put("status", "SUCCEEDED")
                .put("attemptedAt", "2026-09-01T23:59:58Z")
                .put("completedAt", "2026-09-01T23:59:59Z");
        carrier.putObject("resourceAvailability").put("status", "AVAILABLE");
        ObjectNode queryFingerprint = mapper.createObjectNode();
        queryFingerprint.put("resourceId", resource.path("resourceId").asText());
        queryFingerprint.set("queryAllele",
                context.path("variant").path("evidenceQueryAllele").deepCopy());
        carrier.putObject("queryOutcome").put("status", "HIT").put("recordCount", 1)
                .put("queryFingerprint", adapter.canonicalSha256(queryFingerprint));
        carrier.putObject("resourceApplicability").put("status", "APPLICABLE");
        carrier.putArray("measurements");
        carrier.put("producedAt", "2026-09-02T00:00:00Z");
        carrier.put("sourcePayloadSha256",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        return carrier;
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
    @Test void diseaseThresholdAndExceptionsRemainActiveUnderLocalScope() throws Exception {
        var context=facts.diseaseContext("MYH7");var snap=snapshot();
        entry(snap,"HGNC:7577","MONDO:0005045","THRESHOLD");
        var catalog=new Ba1SpecificationCatalog(mapper,snap);
        var c=baseCarrier(context);c.set("measurements",mapper.valueToTree(profile.parse("PASS",info("0.01","2000"),0,1).getMeasurements()));
        var evaluator=new GnomadPopulationCriterionEvaluator(mapper);
        var local=Ba1LocalExomes.selection(mapper,catalog.select(mapping("HGNC:7577"),context));
        var r=evaluator.evaluateCatalogBa1(context.at("/variant/alleleId").asText(),c,profile,local);
        assertEquals("MET",r.at("/evaluationResult/status").asText(),r.toPrettyString());
        assertEquals("DISEASE_SPECIFIC",r.path("scope").asText());
        var general=new Ba1SpecificationCatalog(mapper,snapshot()).select(mapping("HGNC:7577"),null);
        var exception=general.at("/policy/frequencyRule/exceptionPolicy/entries/0");
        var allele=mapper.createObjectNode();
        for(String key:List.of("assembly","contig","position","reference","alternate")) allele.set(key,exception.path(key));
        c.set("queryAllele",allele);
        var result=evaluator.evaluateCatalogBa1(context.at("/variant/alleleId").asText(),c,profile,Ba1LocalExomes.selection(mapper,general));
        assertEquals("BA1_EXCEPTION_HIT",result.at("/evaluationResult/reasonCode").asText(),result.toPrettyString());
    }
    @Test void localFileIdentityChangesAndRemotePathsAreRejected(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        var settings=new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties();
        var file=dir.resolve("gnomad.exomes.v4.1.sites.chr1.vcf.bgz");java.nio.file.Files.writeString(file,"first");
        settings.getGnomad().setGrch38Files(file.toString());var first=Ba1LocalExomes.identity(mapper,settings);
        java.nio.file.Files.writeString(file,"changed-size");assertNotEquals(first,Ba1LocalExomes.identity(mapper,settings));
        settings.getGnomad().setGrch38Files("\\\\example.test\\shared\\gnomad.vcf.bgz");
        assertThrows(IllegalArgumentException.class,()->Ba1LocalExomes.provider(settings));
    }
}
