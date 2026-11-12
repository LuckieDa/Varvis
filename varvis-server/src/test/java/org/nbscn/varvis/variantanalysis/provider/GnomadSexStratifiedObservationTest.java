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

/** Independent lexical counterexamples; no threshold, disease policy or inferred patient ploidy. */
class GnomadSexStratifiedObservationTest {
    final ObjectMapper mapper = new ObjectMapper();
    static final String SOURCE = "a".repeat(64);
    static final String HEADER = "##INFO=<ID=AC_XY,Number=A,Type=Integer,Description=\"Alternate allele count for XY samples\">\n"
            + "##INFO=<ID=AN_XY,Number=1,Type=Integer,Description=\"Total number of alleles in XY samples\">\n"
            + "##INFO=<ID=AF_XY,Number=A,Type=Float,Description=\"Alternate allele frequency in XY samples\">\n"
            + "##INFO=<ID=faf95_XY,Number=A,Type=Float,Description=\"Filtering allele frequency (using Poisson 95% CI) for XY samples in non-PAR regions of sex chromosomes only\">\n";

    ObjectNode parse(String info) { return parse(info, "NON_PAR"); }
    ObjectNode parse(String info, String region) {
        return GnomadSexStratifiedObservation.parseRecord(mapper, "chrX\t154535004\t.\tC\tA,T\t.\tPASS\t" + info,
                "T", GnomadSexStratifiedObservation.headerDefinitions(HEADER), region);
    }
    JsonNode xy(JsonNode value) { return value.at("/strata/2"); }
    JsonNode measurement(JsonNode value, String field) { return xy(value).path("measurements").path(field); }

    @Test void numberASelectsTargetAltAndNumberOnePreservesAlleleDenominator() {
        var value = parse("AC_XY=1,2;AN_XY=2000;AF_XY=0.0005,1.000e-3;faf95_XY=0,0.0002");
        assertEquals("2", measurement(value, "AC").path("rawValue").asText());
        assertEquals("2000", measurement(value, "AN").path("rawValue").asText());
        assertEquals("1.000e-3", measurement(value, "AF").path("rawValue").asText());
        assertEquals("0.001", measurement(value, "AF").path("normalizedValue").asText());
        assertEquals("OBSERVED", xy(value).path("status").asText());
        assertEquals("ALLELES", xy(value).path("denominatorUnit").asText());
        assertEquals("NOT_ESTABLISHED", xy(value).path("individualPloidy").asText());
        assertFalse(xy(value).has("patientSex"));
    }

    @Test void absentEmptyDotAndInvalidNeverBecomeZeroOrBorrowAggregateAf() {
        for (String input : List.of("AC_XY=1,2;AN_XY=2000;AF=0.5,0.6", "AC_XY=1,2;AN_XY=2000;AF_XY=",
                "AC_XY=1,2;AN_XY=2000;AF_XY=.,.", "AC_XY=1,2;AN_XY=2000;AF_XY=0,1.1")) {
            var value = parse(input);
            assertEquals("INCOMPLETE", xy(value).path("status").asText());
            assertFalse(measurement(value, "AF").has("normalizedValue"));
        }
        assertEquals("MISSING_KEY", measurement(parse("AN_XY=2000"), "AF").path("state").asText());
        assertEquals("EMPTY", measurement(parse("AF_XY="), "AF").path("state").asText());
        assertEquals("DOT", measurement(parse("AF_XY=.,."), "AF").path("state").asText());
        assertEquals("INVALID", measurement(parse("AF_XY=0,1.1"), "AF").path("state").asText());
    }

    @Test void afFafScopeAndSourceFieldsStayIndependentAtParAndCrossing() {
        String input = "AC_XY=1,2;AN_XY=2000;AF_XY=0.0005,0.001;faf95_XY=0,0.0002";
        assertEquals("APPLICABLE", measurement(parse(input), "faf95").path("applicability").asText());
        assertEquals("NOT_APPLICABLE", measurement(parse(input, "PAR"), "faf95").path("applicability").asText());
        assertEquals("UNRESOLVED", measurement(parse(input, "CROSSES_BOUNDARY"), "faf95").path("applicability").asText());
        var missingFaf = parse("AC_XY=1,2;AN_XY=2000;AF_XY=0.0005,0.001");
        assertEquals("PRESENT", measurement(missingFaf, "AF").path("state").asText());
        assertEquals("MISSING_KEY", measurement(missingFaf, "faf95").path("state").asText());
    }

    @Test void numericAndCardinalityConflictsRemainExplicit() {
        assertEquals("CARDINALITY_MISMATCH", measurement(parse("AN_XY=2000,3000"), "AN").path("state").asText());
        assertEquals("CARDINALITY_MISMATCH", measurement(parse("AC_XY=1"), "AC").path("state").asText());
        assertEquals("INVALID", measurement(parse("AC_XY=0,1.0"), "AC").path("state").asText());
        assertTrue(xy(parse("AC_XY=1,3;AN_XY=2;AF_XY=0.5,0.5")).path("issues").toString().contains("AC_EXCEEDS_AN"));
        assertTrue(xy(parse("AC_XY=0,0;AN_XY=0;AF_XY=0,0.5")).path("issues").toString().contains("ZERO_AN_CONFLICT"));
        assertTrue(xy(parse("AC_XY=1,2;AN_XY=2000;AF_XY=0.0005,0.1")).path("issues").toString().contains("COUNT_AF_CONFLICT"));
        assertTrue(xy(parse("AC_XY=1,2;AN_XY=2000;AF_XY=0.0005,0.001;faf95_XY=0,0.1")).path("issues").toString().contains("FAF_EXCEEDS_AF"));
        assertEquals("OBSERVED", xy(parse("AC_XY=0,0;AN_XY=0;AF_XY=0,0;faf95_XY=0,0")).path("status").asText());
    }

    @Test void duplicateOrWrongHeaderAndAltAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.headerDefinitions(HEADER + HEADER));
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.parseRecord(mapper,
                "chrX\t1\t.\tC\tA,T\t.\tPASS\tAN_XY=2000", "T",
                GnomadSexStratifiedObservation.headerDefinitions(HEADER.replace("ID=AN_XY,Number=1", "ID=AN_XY,Number=A")), "NON_PAR"));
        assertThrows(IllegalArgumentException.class, () -> parse("AC_XY=1,2;AC_XY=2,3"));
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.parseRecord(mapper,
                "chrX\t1\t.\tC\tT,T\t.\tPASS\tAN_XY=2", "T", GnomadSexStratifiedObservation.headerDefinitions(HEADER), "NON_PAR"));
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.parseRecord(mapper,
                "chrX\t1\t.\tC\tA\t.\tPASS\tAN_XY=2", "T", GnomadSexStratifiedObservation.headerDefinitions(HEADER), "NON_PAR"));
    }

    ObjectNode context() throws Exception {
        var context = new Bs1CoreTest().context();
        var allele = context.withObject("/variant/evidenceQueryAllele");
        allele.put("contig", "X").put("position", 154535004).put("reference", "C").put("alternate", "T");
        context.withObject("/variant/inputAllele").setAll(allele);
        context.withObject("/variant/normalizedAllele").setAll(allele);
        context.put("moi", "X_LINKED_RECESSIVE");
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        context.withObject("/variant").put("alleleId", adapter.canonicalAlleleId(allele, context.at("/variant/normalization")));
        context.put("contextId", adapter.canonicalVersionedContextId(context)); return context;
    }
    ObjectNode parent(ObjectNode context, Map<String, String> additions) {
        var fixture = new Bs1LocalExomesTest();
        var info = new LinkedHashMap<>(fixture.facts.info("0.00004", "10000")); info.putAll(additions);
        var shared = fixture.shared(context, info, "PASS");
        return Bs1LocalExomes.sharedObservation(mapper, context, shared, SOURCE);
    }

    @Test void productEntryDoesNotAcceptCallerInventedHeaderPins() throws Exception {
        var context = context(); var parent = parent(context, Map.of()); var before = parent.deepCopy();
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.derive(mapper, parent, context, SOURCE, HEADER.getBytes(StandardCharsets.UTF_8)));
        assertEquals(before, parent);
        var changed = parent.deepCopy().put("sourceSnapshot", "b".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.derive(mapper, changed, context, SOURCE, HEADER.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void noRecordHasNoMeasurementsAndCannotBecomeZero() throws Exception {
        var context = context(); var fixture = new Ba1LocalExomesTest(); var carrier = fixture.baseCarrier(context);
        carrier.withObject("/queryOutcome").put("status", "NO_RECORD").put("recordCount", 0);
        carrier.withArray("/source/sourceRecords").removeAll();
        var parent = PopulationObservation.seal(mapper, carrier, "", GnomadPopulationInputProfile.loadLocalBa1(), SOURCE, "SYNTHETIC", "SYNTHETIC_NO_RECORD");
        var before = parent.deepCopy(); var derived = GnomadSexStratifiedObservation.derive(mapper, parent, context, SOURCE);
        assertEquals("NO_RECORD", derived.path("status").asText());
        assertEquals("GNOMAD_SEX_NO_RECORD", derived.path("reasonCode").asText());
        assertTrue(derived.path("strata").isEmpty()); assertEquals(before, parent);
    }

    @Test void sourceAssemblyMustMatchQueryEvenWhenOldParentSealingAcceptsTheirMismatch() throws Exception {
        var context = context();
        for (String path : List.of("/variant/inputAllele", "/variant/normalizedAllele", "/variant/evidenceQueryAllele"))
            context.withObject(path).put("assembly", "GRCh37");
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        context.withObject("/variant").put("alleleId", adapter.canonicalAlleleId(context.at("/variant/evidenceQueryAllele"), context.at("/variant/normalization")));
        context.put("contextId", adapter.canonicalVersionedContextId(context));
        var parent = parent(context, Map.of()); var before = parent.deepCopy();
        // The legacy contract is intentionally unchanged; the new derivation owns this additional binding.
        PopulationObservation.verify(mapper, parent, context, SOURCE, GnomadPopulationInputProfile.loadLocalBa1());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> GnomadSexStratifiedObservation.derive(mapper, parent, context, SOURCE));
        assertEquals("GNOMAD_SEX_RESOURCE_ASSEMBLY_MISMATCH", failure.getMessage()); assertEquals(before, parent);
    }

    @Test void mappedGrch37InputIsAllowedWhenItsEvidenceQueryMatchesGrch38Resource() throws Exception {
        var context = context();
        context.withObject("/variant/inputAllele").put("assembly", "GRCh37");
        context.withObject("/variant/normalizedAllele").put("assembly", "GRCh37");
        var lineage = context.withObject("/variant/assemblyLineage"); lineage.removeAll(); lineage.put("mappingStatus", "MAPPED");
        var mapping = lineage.putObject("coordinateMapping").put("method", "SYNTHETIC_MAPPING_CONTROL")
                .put("methodVersion", "1").put("refAltValidation", "MATCH");
        mapping.set("sourceAllele", context.at("/variant/normalizedAllele").deepCopy());
        mapping.set("targetAllele", context.at("/variant/evidenceQueryAllele").deepCopy());
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        context.withObject("/variant").put("alleleId", adapter.canonicalAlleleId(context.at("/variant/normalizedAllele"), context.at("/variant/normalization")));
        context.put("contextId", adapter.canonicalVersionedContextId(context));
        var parent = parent(context, Map.of()); var derived = GnomadSexStratifiedObservation.derive(mapper, parent, context, SOURCE);
        assertEquals("GRCh37", context.at("/variant/inputAllele/assembly").asText());
        assertEquals("GRCh38", derived.at("/queryAllele/assembly").asText());
        assertEquals("NON_PAR", derived.at("/region/region").asText());
    }

    @Test void allKaryotypesAreReadFromTheirOwnRawFieldsWithoutSumming() throws Exception {
        var context = context(); var parent = parent(context, Map.of("AC", "4", "AN", "422036", "AF", "9.47786e-06",
                "AC_XY", "1", "AN_XY", "137364", "AF_XY", "7.27993e-06"));
        var derived = GnomadSexStratifiedObservation.derive(mapper, parent, context, SOURCE);
        assertEquals("ALL", derived.at("/strata/0/karyotype").asText());
        assertEquals("4", derived.at("/strata/0/measurements/AC/rawValue").asText());
        assertEquals("422036", derived.at("/strata/0/measurements/AN/rawValue").asText());
        assertEquals("MISSING_KEY", derived.at("/strata/1/measurements/AN/state").asText());
        assertEquals("1", derived.at("/strata/2/measurements/AC/rawValue").asText());
        assertEquals("NOT_ESTABLISHED", derived.at("/strata/0/individualPloidy").asText());
    }

    @Test
    void originalHeaderPinSupportsDerivedReplayAndKeepsOldObservationUntouched() throws Exception {
        byte[] header = GnomadSexStratifiedObservation.loadPinnedHeader();
        var context = context(); var parent = parent(context, Map.of("AC_XY", "1", "AN_XY", "137364", "AF_XY", "7.27993e-06",
                "AC_XX", "3", "AN_XX", "284672", "AF_XX", "1.05384e-05", "faf95_XY", "0", "faf95_XX", "2.80000e-06"));
        var before = parent.deepCopy(); var derived = GnomadSexStratifiedObservation.derive(mapper, parent, context, SOURCE, header);
        var readback = mapper.readTree(mapper.writeValueAsBytes(derived));
        assertEquals(derived, readback);
        GnomadSexStratifiedObservation.verify(mapper, readback, parent, context, SOURCE, header);
        assertEquals(before, parent);
        assertEquals(GnomadSexStratifiedObservation.HEADER_SHA256, derived.path("headerSha256").asText());
        assertEquals(parent.path("observationId"), derived.path("parentObservationId"));
        assertEquals("7.27993e-06", measurement(derived, "AF").path("rawValue").asText());
        assertEquals("INCOMPLETE", derived.path("status").asText());
        assertEquals("NOT_EVALUATED", derived.path("statisticalQualification").asText());
        var changed = derived.deepCopy(); changed.withObject("/strata/2/measurements/AF").put("rawValue", "0");
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.verify(mapper, changed, parent, context, SOURCE, header));
        header[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> GnomadSexStratifiedObservation.derive(mapper, parent, context, SOURCE, header));
    }

    @Test @EnabledIfSystemProperty(named = "varvis.a10.gnomad.header", matches = ".+")
    void retainedPublicHeaderEqualsBundledOriginalBytes() throws Exception {
        assertArrayEquals(Files.readAllBytes(Path.of(System.getProperty("varvis.a10.gnomad.header"))), GnomadSexStratifiedObservation.loadPinnedHeader());
    }

    @Test @EnabledIfSystemProperty(named = "varvis.a10.gnomad.record", matches = ".+")
    void capturedPublicNonParRecordReplaysWithSyntheticContextAndIndependentExpectedCounts() throws Exception {
        String raw = Files.readString(Path.of(System.getProperty("varvis.a10.gnomad.record")), StandardCharsets.UTF_8);
        String source = org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(raw);
        assertEquals("afa7296ccc10242d8f159e5ef94f936082c2576af82605c65b19803d90d87dda", source);
        String[] columns = raw.split("\t", -1); var values = new LinkedHashMap<String, String>();
        for (String field : columns[7].split(";")) {
            String[] pair = field.split("=", 2); values.put(pair[0], pair.length == 2 ? pair[1] : "true");
        }
        var context = context(); var profile = GnomadPopulationInputProfile.loadLocalBa1();
        var carrier = new Ba1LocalExomesTest().baseCarrier(context);
        carrier.set("measurements", mapper.valueToTree(profile.parse(columns[6], values, 0, 1).getMeasurements()));
        var parent = PopulationObservation.seal(mapper, carrier, raw, profile, source, "SYNTHETIC", "REAL_PUBLIC_RECORD_SYNTHETIC_CONTEXT");
        var derived = GnomadSexStratifiedObservation.derive(mapper, parent, context, source);
        assertEquals("4", derived.at("/strata/0/measurements/AC/rawValue").asText());
        assertEquals("422036", derived.at("/strata/0/measurements/AN/rawValue").asText());
        assertEquals("3", derived.at("/strata/1/measurements/AC/rawValue").asText());
        assertEquals("284672", derived.at("/strata/1/measurements/AN/rawValue").asText());
        assertEquals("1", measurement(derived, "AC").path("rawValue").asText());
        assertEquals("137364", measurement(derived, "AN").path("rawValue").asText());
        assertEquals("7.27993e-06", measurement(derived, "AF").path("rawValue").asText());
        assertEquals("0.00000", measurement(derived, "faf95").path("rawValue").asText());
        assertEquals("NON_PAR", derived.at("/region/region").asText());
        GnomadSexStratifiedObservation.verify(mapper, derived, parent, context, source, GnomadSexStratifiedObservation.loadPinnedHeader());
    }
}
