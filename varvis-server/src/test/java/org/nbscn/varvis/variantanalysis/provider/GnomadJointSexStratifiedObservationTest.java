package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Independent JOINT controls; synthetic contexts never grant clinical or filter qualification. */
class GnomadJointSexStratifiedObservationTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadExecutableV2();
    static final String SOURCE = "a".repeat(64);
    static final String HEADER = "##INFO=<ID=AC_joint_XY,Number=A,Type=Integer,Description=\"Alternate allele count for XY samples in joint dataset\">\n"
            + "##INFO=<ID=AN_joint_XY,Number=1,Type=Integer,Description=\"Total number of alleles in XY samples in joint dataset\">\n"
            + "##INFO=<ID=AF_joint_XY,Number=A,Type=Float,Description=\"Alternate allele frequency in XY samples in joint dataset\">\n"
            + "##INFO=<ID=faf95_joint_XY,Number=A,Type=Float,Description=\"Filtering allele frequency (using Poisson 95% CI) for XY samples in joint dataset in non-PAR regions of sex chromosomes only\">\n";

    ObjectNode parse(String info, String region) {
        return GnomadSexStratifiedObservation.parseJointRecord(mapper,
                "chrX\t154535004\t.\tC\tA,T\t.\tPASS\t" + info, "T",
                GnomadSexStratifiedObservation.headerDefinitions(HEADER), region);
    }
    JsonNode stratum(JsonNode value, String ancestry, String karyotype) {
        for (var row : value.path("strata"))
            if (ancestry.equals(row.path("ancestry").asText()) && karyotype.equals(row.path("karyotype").asText())) return row;
        throw new AssertionError(ancestry + ":" + karyotype);
    }
    JsonNode xy(JsonNode value) { return stratum(value, "ALL", "XY").path("measurements"); }
    ObjectNode context() throws Exception {
        var value = new GnomadSexStratifiedObservationTest().context();
        value.withObject("/variant").put("alleleId", adapter.canonicalAlleleId(value.at("/variant/evidenceQueryAllele"), value.at("/variant/normalization")));
        value.put("contextId", adapter.canonicalVersionedContextId(value)); return value;
    }
    ObjectNode carrier(ObjectNode context) {
        var resource = profile.getDocument().path("resources").path(0);
        var value = mapper.createObjectNode().put("evidenceType", "POPULATION_FREQUENCY")
                .put("producedAt", "2026-09-02T00:00:00Z").put("sourcePayloadSha256", "b".repeat(64));
        value.set("queryAllele", context.at("/variant/evidenceQueryAllele").deepCopy());
        var source = value.putObject("source").put("sourceId", resource.path("resourceId").asText())
                .put("resourceVersion", resource.path("resourceVersion").asText())
                .put("resourceSha256", resource.path("identityFingerprintSha256").asText()).put("retrievedAt", "2026-09-02T00:00:00Z");
        var locator = mapper.createObjectNode().put("locatorType", "GENOMIC_REGION").put("locatorValue", "SYNTHETIC_JOINT:exact-allele")
                .put("sourceRecordId", "SYNTHETIC_JOINT:exact-allele").put("artifactSha256", resource.path("identityFingerprintSha256").asText())
                .put("retrievedAt", "2026-09-02T00:00:00Z");
        source.putArray("sourceRecords").addObject().put("recordId", "SYNTHETIC_JOINT:exact-allele").set("locator", locator.deepCopy());
        source.putArray("sourceLocators").add(locator);
        value.putObject("resourceExecution").put("status", "SUCCEEDED").put("attemptedAt", "2026-09-01T23:59:58Z").put("completedAt", "2026-09-01T23:59:59Z");
        value.putObject("resourceAvailability").put("status", "AVAILABLE");
        value.putObject("resourceApplicability").put("status", "APPLICABLE");
        var fingerprint = mapper.createObjectNode().put("resourceId", resource.path("resourceId").asText());
        fingerprint.set("queryAllele", context.at("/variant/evidenceQueryAllele").deepCopy());
        value.putObject("queryOutcome").put("status", "HIT").put("recordCount", 1).put("queryFingerprint", adapter.canonicalSha256(fingerprint));
        value.putArray("measurements"); return value;
    }
    ObjectNode parent(ObjectNode context, Map<String, String> info, String filter) { return parent(context, info, filter, SOURCE); }
    ObjectNode parent(ObjectNode context, Map<String, String> info, String filter, String source) {
        var value = carrier(context);
        value.set("measurements", mapper.valueToTree(profile.parse(filter, info, 0, 1).getMeasurements()));
        var q = context.at("/variant/evidenceQueryAllele");
        String raw = "chr" + q.path("contig").asText() + "\t" + q.path("position").asText() + "\t.\t" + q.path("reference").asText()
                + "\t" + q.path("alternate").asText() + "\t.\t" + filter + "\t"
                + String.join(";", info.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        return PopulationObservation.seal(mapper, value, raw, profile, source, "SYNTHETIC", "SYNTHETIC_JOINT_CONTROL");
    }
    Map<String, String> info() {
        var value = new LinkedHashMap<>(new PopulationFrequencyRuntimeTest().jointInfo("0.00004", "10000", "0.5"));
        value.put("AC_joint_XY", "2"); value.put("AN_joint_XY", "2000"); value.put("AF_joint_XY", "0.001"); value.put("faf95_joint_XY", "0.0002");
        return value;
    }

    @Test void jointNumberAAndAlleleDenominatorsAreIndependentOfComponentsAndExomeNames() {
        var value = parse("AC_joint_XY=1,2;AN_joint_XY=2000;AF_joint_XY=0.0005,0.001;faf95_joint_XY=0,0.0002;AC_XY=50,60;AN_XY=100;AC_exomes_XY=3,4;AC_genomes_XY=5,6", "NON_PAR");
        assertEquals("2", xy(value).at("/AC/rawValue").asText());
        assertEquals("2000", xy(value).at("/AN/rawValue").asText());
        assertEquals("AC_joint_XY", xy(value).at("/AC/infoId").asText());
        assertEquals("gnomad.joint.karyotype.xy.ancestry.all.ac", xy(value).at("/AC/metricId").asText());
        assertEquals("ALLELES", stratum(value, "ALL", "XY").path("denominatorUnit").asText());
        assertEquals("NOT_ESTABLISHED", stratum(value, "ALL", "XY").path("individualPloidy").asText());
    }
    @Test void missingStrataNeverBorrowAggregateExomeGenomeOrRawFrequencies() {
        var value = parse("AF_joint=0.2;AF_XY=0.3;AF_exomes_XY=0.4;AF_genomes_XY=0.5;AF_joint_XY_raw=0.6", "NON_PAR");
        assertEquals("MISSING_KEY", xy(value).at("/AF/state").asText());
        assertFalse(xy(value).path("AF").has("normalizedValue"));
        assertEquals("NOT_DECLARED_BY_HEADER", stratum(value, "remaining", "XX").at("/measurements/faf95/state").asText());
    }
    @Test void fafScopePreservesNonParRestrictionWithoutChangingAf() {
        String info = "AC_joint_XY=1,2;AN_joint_XY=2000;AF_joint_XY=0.0005,0.001;faf95_joint_XY=0,0.0002";
        assertEquals("APPLICABLE", xy(parse(info, "NON_PAR")).at("/faf95/applicability").asText());
        assertEquals("NOT_APPLICABLE", xy(parse(info, "PAR")).at("/faf95/applicability").asText());
        assertEquals("UNRESOLVED", xy(parse(info, "CROSSES_BOUNDARY")).at("/faf95/applicability").asText());
        assertEquals("PRESENT", xy(parse(info, "PAR")).at("/AF/state").asText());
    }
    @Test void absentEmptyDotCardinalityAndNumericalConflictsStayExplicit() {
        assertEquals("EMPTY", xy(parse("AF_joint_XY=", "NON_PAR")).at("/AF/state").asText());
        assertEquals("DOT", xy(parse("AF_joint_XY=.,.", "NON_PAR")).at("/AF/state").asText());
        assertEquals("CARDINALITY_MISMATCH", xy(parse("AN_joint_XY=2,3", "NON_PAR")).at("/AN/state").asText());
        assertEquals("INVALID", xy(parse("AC_joint_XY=0,1.0", "NON_PAR")).at("/AC/state").asText());
        assertEquals("INVALID", xy(parse("AF_joint_XY=0,1.1", "NON_PAR")).at("/AF/state").asText());
        assertTrue(stratum(parse("AC_joint_XY=1,3;AN_joint_XY=2;AF_joint_XY=0.5,0.5", "NON_PAR"), "ALL", "XY").path("issues").toString().contains("AC_EXCEEDS_AN"));
        assertTrue(stratum(parse("AC_joint_XY=1,2;AN_joint_XY=2000;AF_joint_XY=0,0.1", "NON_PAR"), "ALL", "XY").path("issues").toString().contains("COUNT_AF_CONFLICT"));
        assertTrue(stratum(parse("AC_joint_XY=1,2;AN_joint_XY=2000;AF_joint_XY=0.0005,0.001;faf95_joint_XY=0,0.1", "NON_PAR"), "ALL", "XY").path("issues").toString().contains("FAF_EXCEEDS_AF"));
    }
    @Test void lowAnIsRetainedAsSourceFactAndZeroAnConflictIsNeverRepaired() {
        var low = parse("AC_joint_XY=0,1;AN_joint_XY=1999;AF_joint_XY=0,0.00050025;faf95_joint_XY=0,0", "NON_PAR");
        assertEquals("1999", xy(low).at("/AN/rawValue").asText());
        assertEquals("OBSERVED", stratum(low, "ALL", "XY").path("status").asText());
        var zero = parse("AC_joint_XY=0,1;AN_joint_XY=0;AF_joint_XY=0,0;faf95_joint_XY=0,0", "NON_PAR");
        assertEquals("0", xy(zero).at("/AN/rawValue").asText());
        assertTrue(stratum(zero, "ALL", "XY").path("issues").toString().contains("ZERO_AN_CONFLICT"));
    }
    @Test void duplicateInfoWrongHeaderAndTargetAltAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> parse("AC_joint_XY=1,2;AC_joint_XY=3,4", "NON_PAR"));
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.parseJointRecord(mapper, "chrX\t1\t.\tC\tT\t.\tPASS\tAN_joint_XY=2000", "T",
                GnomadSexStratifiedObservation.headerDefinitions(HEADER.replace("ID=AN_joint_XY,Number=1", "ID=AN_joint_XY,Number=A")), "NON_PAR"));
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.parseJointRecord(mapper, "chrX\t1\t.\tC\tT,T\t.\tPASS\tAN_joint_XY=2000", "T",
                GnomadSexStratifiedObservation.headerDefinitions(HEADER), "NON_PAR"));
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.parseJointRecord(mapper, "chrX\t1\t.\tC\tA\t.\tPASS\tAN_joint_XY=2000", "T",
                GnomadSexStratifiedObservation.headerDefinitions(HEADER), "NON_PAR"));
    }
    @Test void fixedHeaderDeclaresRemainingCountsAndOnlySourceFafAncestries() {
        byte[] header = GnomadSexStratifiedObservation.loadPinnedJointHeader();
        assertEquals(126545, header.length);
        var definitions = GnomadSexStratifiedObservation.headerDefinitions(new String(header, StandardCharsets.UTF_8));
        assertEquals(664, definitions.size());
        assertNotNull(definitions.get("AN_joint_remaining_XY"));
        assertNull(definitions.get("faf95_joint_remaining_XY"));
        assertNotNull(definitions.get("faf95_joint_mid_XX"));
        assertEquals("66875ac50f53b8f491c263394fc85e17308c639bae4b7d5daa43fea6f773ca83", GnomadSexStratifiedObservation.HEADER_SHA256);
        assertEquals(71531, GnomadSexStratifiedObservation.loadPinnedHeader().length);
    }
    @Test void jointParentHeaderSourceRegionAndStorageAreBoundWithoutChangingTheParent() throws Exception {
        var context = context(); var parent = parent(context, info(), "PASS", SOURCE); var before = parent.deepCopy();
        var derived = GnomadSexStratifiedObservation.deriveJoint(mapper, parent, context, SOURCE);
        assertEquals(GnomadSexStratifiedObservation.JOINT_VERSION, derived.path("schemaVersion").asText());
        assertEquals("JOINT", derived.path("dataset").asText());
        assertEquals("4.1", derived.path("release").asText());
        assertEquals("NOT_EVALUATED", derived.path("statisticalQualification").asText());
        assertFalse(derived.path("criterionEvaluationEnabled").asBoolean());
        assertEquals(parent.path("observationId"), derived.path("parentObservationId"));
        assertEquals(parent.path("rawRecordSha256"), derived.path("rawRecordSha256"));
        assertEquals("NON_PAR", derived.at("/region/region").asText());
        assertEquals(SexChromosomeRegionResolver.CATALOG_SHA256, derived.at("/region/sourceCatalogSha256").asText());
        assertEquals("2", xy(derived).at("/AC/rawValue").asText());
        assertEquals(33, derived.path("strata").size());
        var readback = mapper.readTree(mapper.writeValueAsBytes(derived));
        assertEquals(derived, readback);
        GnomadSexStratifiedObservation.verifyJoint(mapper, readback, parent, context, SOURCE, GnomadSexStratifiedObservation.loadPinnedJointHeader());
        assertEquals(before, parent);
        var changed = derived.deepCopy(); changed.withObject("/strata/2/measurements/AC").put("rawValue", "0");
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.verifyJoint(mapper, changed, parent, context, SOURCE, GnomadSexStratifiedObservation.loadPinnedJointHeader()));
    }
    @Test void exomesProfileSourceRawAndCallerHeaderCannotBecomeJoint() throws Exception {
        var context = context(); var parent = parent(context, info(), "PASS", SOURCE);
        var exomeParent = new GnomadSexStratifiedObservationTest().parent(context, Map.of());
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.deriveJoint(mapper, exomeParent, context, SOURCE));
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.derive(mapper, parent, context, SOURCE));
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.deriveJoint(mapper, parent, context, "b".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.deriveJoint(mapper, parent, context, SOURCE, GnomadSexStratifiedObservation.loadPinnedHeader()));
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.deriveJoint(mapper, parent, context, SOURCE, HEADER.getBytes(StandardCharsets.UTF_8)));
        var changedRaw = parent.deepCopy().put("rawRecord", parent.path("rawRecord").asText().replace("AC_joint_XY=2", "AC_joint_XY=3"));
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.deriveJoint(mapper, changedRaw, context, SOURCE));
        var wrongDataset = parent.deepCopy(); wrongDataset.withObject("/resourceContract/identity").put("dataset", "exomes");
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.deriveJoint(mapper, wrongDataset, context, SOURCE));
    }
    @Test void jointAssemblyMustMatchTheQueryAndWrongContextCannotReuseTheParent() throws Exception {
        var context = context(); var parent = parent(context, info(), "PASS", SOURCE);
        var wrongContext = context.deepCopy(); wrongContext.withObject("/variant/evidenceQueryAllele").put("position", 154535005);
        wrongContext.put("contextId", adapter.canonicalVersionedContextId(wrongContext));
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.deriveJoint(mapper, parent, wrongContext, SOURCE));
        for (String path : List.of("/variant/inputAllele", "/variant/normalizedAllele", "/variant/evidenceQueryAllele")) context.withObject(path).put("assembly", "GRCh37");
        context.withObject("/variant").put("alleleId", adapter.canonicalAlleleId(context.at("/variant/evidenceQueryAllele"), context.at("/variant/normalization")));
        context.put("contextId", adapter.canonicalVersionedContextId(context));
        var wrongAssemblyParent = parent(context, info(), "PASS", SOURCE);
        PopulationObservation.verify(mapper, wrongAssemblyParent, context, SOURCE, profile);
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.deriveJoint(mapper, wrongAssemblyParent, context, SOURCE));
    }
    @Test void noRecordDoesNotInventZeroAndExecutionFailureRemainsUnavailable() throws Exception {
        var context = context(); var carrier = carrier(context);
        carrier.withObject("/queryOutcome").put("status", "NO_RECORD").put("recordCount", 0);
        carrier.withArray("/source/sourceRecords").removeAll();
        var parent = PopulationObservation.seal(mapper, carrier, "", profile, SOURCE, "SYNTHETIC", "SYNTHETIC_NO_RECORD");
        var derived = GnomadSexStratifiedObservation.deriveJoint(mapper, parent, context, SOURCE);
        assertEquals("NO_RECORD", derived.path("status").asText()); assertTrue(derived.path("strata").isEmpty());
        carrier.withObject("/queryOutcome").put("status", "NOT_QUERIED").remove("queryFingerprint");
        carrier.withObject("/resourceExecution").put("status", "FAILED").put("reasonCode", "SYNTHETIC_QUERY_FAILED");
        carrier.withObject("/resourceAvailability").put("status", "UNAVAILABLE").put("reasonCode", "SYNTHETIC_RESOURCE_UNAVAILABLE");
        carrier.withObject("/resourceApplicability").put("status", "UNKNOWN").put("reasonCode", "SYNTHETIC_QUERY_FAILED");
        parent = PopulationObservation.seal(mapper, carrier, "", profile, SOURCE, "SYNTHETIC", "SYNTHETIC_UNAVAILABLE");
        derived = GnomadSexStratifiedObservation.deriveJoint(mapper, parent, context, SOURCE);
        assertEquals("UNAVAILABLE", derived.path("status").asText()); assertTrue(derived.path("strata").isEmpty());
    }
    @Test @EnabledIfSystemProperty(named = "varvis.a10.gnomad.joint.header", matches = ".+")
    void retainedPublicJointHeaderEqualsThePinnedOriginal() throws Exception {
        assertArrayEquals(Files.readAllBytes(Path.of(System.getProperty("varvis.a10.gnomad.joint.header"))), GnomadSexStratifiedObservation.loadPinnedJointHeader());
    }
    @Test @EnabledIfSystemProperty(named = "varvis.a10.gnomad.joint.record", matches = ".+")
    void capturedSlc6a8JointOriginalReplaysWithSyntheticContextAndIndependentCounts() throws Exception {
        byte[] original = Files.readAllBytes(Path.of(System.getProperty("varvis.a10.gnomad.joint.record")));
        String source = org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(new String(original, StandardCharsets.UTF_8));
        assertEquals("74bd94f674f76ab931d187e46db06e41c5c4003e47d3cf83f96fa6d9f9441e76", source);
        String raw = new String(original, StandardCharsets.UTF_8);
        if (raw.endsWith("\n")) raw = raw.substring(0, raw.length() - 1);
        var c = raw.split("\t", -1); var context = context();
        for (String path : List.of("/variant/inputAllele", "/variant/normalizedAllele", "/variant/evidenceQueryAllele"))
            context.withObject(path).put("position", 153691089).put("reference", "G").put("alternate", "A");
        context.withObject("/variant").put("alleleId", adapter.canonicalAlleleId(context.at("/variant/evidenceQueryAllele"), context.at("/variant/normalization")));
        context.put("contextId", adapter.canonicalVersionedContextId(context));
        var info = new LinkedHashMap<String, String>();
        for (String item : c[7].split(";")) { var pair = item.split("=", 2); info.put(pair[0], pair.length == 2 ? pair[1] : "true"); }
        var carrier = carrier(context); carrier.set("measurements", mapper.valueToTree(profile.parse(c[6], info, 0, 1).getMeasurements()));
        var parent = PopulationObservation.seal(mapper, carrier, raw, profile, source, "SYNTHETIC", "REAL_PUBLIC_JOINT_RECORD_SYNTHETIC_CONTEXT");
        var derived = GnomadSexStratifiedObservation.deriveJoint(mapper, parent, context, source);
        assertEquals("6", stratum(derived, "ALL", "ALL").at("/measurements/AC/rawValue").asText());
        assertEquals("442785", stratum(derived, "ALL", "ALL").at("/measurements/AN/rawValue").asText());
        assertEquals("5", stratum(derived, "ALL", "XX").at("/measurements/AC/rawValue").asText());
        assertEquals("302816", stratum(derived, "ALL", "XX").at("/measurements/AN/rawValue").asText());
        assertEquals("1", xy(derived).at("/AC/rawValue").asText());
        assertEquals("139969", xy(derived).at("/AN/rawValue").asText());
        assertEquals("7.14444e-06", xy(derived).at("/AF/rawValue").asText());
        assertEquals("71808", stratum(derived, "nfe", "XY").at("/measurements/AN/rawValue").asText());
        assertEquals("0.00000", stratum(derived, "nfe", "XY").at("/measurements/faf95/rawValue").asText());
        assertFalse(derived.path("criterionEvaluationEnabled").asBoolean());
        GnomadSexStratifiedObservation.verifyJoint(mapper, mapper.readTree(mapper.writeValueAsBytes(derived)), parent, context, source, GnomadSexStratifiedObservation.loadPinnedJointHeader());
    }
}
