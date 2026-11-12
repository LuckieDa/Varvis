package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.Ba1ConditionalFrequencyRule.*;

/** Pinned real rule/header/profile/RAW into finite direct mechanics. No gene/context fixture,
 * new population query, scientific adoption, CURRENT, patient interpretation or store proof.
 * Reader precondition counterexamples below are test-local provenance checks, not a claim
 * that the finite arithmetic helper independently implements production source qualification. */
@EnabledIfSystemProperty(named = "varvis.t03.ba1.real.compiled", matches = ".+")
class Ba1ConditionalOriginalSourceReplayTest {
    private static final String RAW_SHA = "74bd94f674f76ab931d187e46db06e41c5c4003e47d3cf83f96fa6d9f9441e76";
    private static final String RULE_SHA = "01df51cabebe125cf9dc19bad5d02116000be49eaf96ac2307b7095603c0e289";
    private static final String HEADER_SHA = "2875792c14a97bc0d69d96267f3532594d0cef1c1c9578cd7f0c43159aa0cc00";
    private static final String PROFILE_RAW_SHA = "d608adb7ca2b2f89c5aeb31a99443a330ad57154035e8f6fd3c485f9a61073da";
    private static final String PROFILE_CANONICAL_SHA = "626320cc24bb480adb4d34c97646326c3ca358c5c68b8282eb7edf177731a0a3";
    private static final String HEADER_RESOURCE = "/acmg/gnomad-joint-4.1-sex-observation-header.vcf.txt";
    private static final String PROFILE_RESOURCE = "/acmg/gnomad-population-input-profile-v2.json";
    private static final String RULE_POINTER = "/data/3/ld/CriteriaCode/18/entContent/strengthDescriptor/0/text";
    private final ObjectMapper mapper = new ObjectMapper();

    private record Originals(Path compiledPath, byte[] compiledBytes, ObjectNode clause,
                             Path rawPath, byte[] rawBytes, String[] columns,
                             Map<String, String> info, byte[] headerBytes,
                             byte[] profileBytes, GnomadPopulationInputProfile profile) { }

    private Originals originals() throws Exception {
        Path compiled = Path.of(required("varvis.t03.ba1.real.compiled"));
        byte[] compiledBytes = readVerified(compiled, required("varvis.t03.ba1.real.compiled.sha"));
        var clause = (ObjectNode) mapper.readTree(compiledBytes); assertNull(issue(clause), clause.toPrettyString());
        byte[] rule = readVerified(Path.of(required("varvis.t03.ba1.real.ruleSource")), RULE_SHA);
        verifyCompiledSourceScope(clause, mapper.readTree(rule));
        Path raw = Path.of(required("varvis.t03.ba1.real.raw"));
        assertEquals(RAW_SHA, required("varvis.t03.ba1.real.raw.sha"), "This replay is pinned to the retained original allele row");
        byte[] rawBytes = readVerified(raw, RAW_SHA);
        String line = new String(rawBytes, StandardCharsets.UTF_8);
        require(line.endsWith("\n") && !line.contains("\r"), "RAW_LINE_ENDING_UNEXPECTED");
        line = line.substring(0, line.length() - 1); require(!line.contains("\n"), "RAW_NOT_ONE_RECORD");
        String[] columns = line.split("\t", -1); verifyRawAllele(columns, "A");
        var info = new LinkedHashMap<String, String>();
        for (String field : columns[7].split(";")) {
            int eq = field.indexOf('='); String name = eq < 0 ? field : field.substring(0, eq);
            require(info.put(name, eq < 0 ? "" : field.substring(eq + 1)) == null, "RAW_INFO_DUPLICATE");
        }
        byte[] header = resource(HEADER_RESOURCE), profileBytes = resource(PROFILE_RESOURCE);
        assertEquals(HEADER_SHA, Bs1KnowledgeCatalog.sha(header)); assertEquals(GnomadSexStratifiedObservation.JOINT_HEADER_SHA256, HEADER_SHA);
        assertEquals(PROFILE_RAW_SHA, Bs1KnowledgeCatalog.sha(profileBytes));
        var profile = GnomadPopulationInputProfile.loadExecutableV2();
        assertEquals(PROFILE_CANONICAL_SHA, profile.getProfileSha256()); assertEquals("JOINT", profile.getDatasetType());
        var definition = field(profile.getDocument(), "nhomalt_joint");
        assertEquals("A", definition.path("number").asText()); assertEquals("BIG_INTEGER", definition.path("valueType").asText());
        assertEquals("individuals", definition.path("unit").asText()); assertEquals("gnomad.joint.nhomalt", definition.path("metricId").asText());
        String headerText = new String(header, StandardCharsets.UTF_8);
        assertEquals("##INFO=<ID=nhomalt_joint,Number=A,Type=Integer,Description=\"Count of homozygous individuals in joint dataset\">",
                headerLine(headerText, "nhomalt_joint"));
        assertTrue(headerLine(headerText, "fafmax_faf95_max_joint").contains("Number=A,Type=Float"));
        assertTrue(headerLine(headerText, "faf95_joint_nfe").contains("Number=A,Type=Float"));
        assertTrue(headerLine(headerText, "AN_joint_nfe").contains("Number=1,Type=Integer"));
        return new Originals(compiled, compiledBytes, clause, raw, rawBytes, columns, info, header, profileBytes, profile);
    }

    @Test void realOriginalHomoCountIsReadAsIndividualsWhileUnimplementedHemiRemainsUnknown() throws Exception {
        var originals = originals();
        var parsed = originals.profile().parse(originals.columns()[6], originals.info(), 0, 1);
        var faf = measurement(parsed, "gnomad.joint.faf95max", "fraction");
        var ancestry = measurement(parsed, "gnomad.joint.faf95max.ancestry", "genetic-ancestry-group");
        var an = measurement(parsed, "gnomad.joint.ancestry.nfe.an", "alleles");
        var homo = measurement(parsed, "gnomad.joint.nhomalt", "individuals");
        assertEquals("nfe", ancestry.get("rawValue")); assertEquals("nfe", ancestry.get("normalizedValue"));
        assertEquals("9.74000e-06", faf.get("rawValue")); assertEquals(0, new BigDecimal(faf.get("normalizedValue")).compareTo(new BigDecimal("0.00000974000")));
        assertEquals("246676", an.get("rawValue")); assertEquals("246676", an.get("normalizedValue"));
        assertEquals("0", originals.info().get("nhomalt_joint")); assertEquals("0", homo.get("rawValue")); assertEquals("0", homo.get("normalizedValue"));
        assertEquals(faf.get("rawValue"), originals.info().get("faf95_joint_nfe"));
        assertEquals(an.get("rawValue"), originals.info().get("AN_joint_nfe"));
        assertTrue(parsed.getMeasurements().stream().noneMatch(m -> m.get("metricId").toLowerCase(java.util.Locale.ROOT).contains("hemi")));
        var frequency = new FrequencyFact(new BigDecimal(faf.get("normalizedValue")), new BigInteger(an.get("normalizedValue")), ancestry.get("normalizedValue"));
        // Exact source nhomalt is 0; no source-backed hemizygote-individual consumer exists here.
        // In particular AC_joint_XY=1 is alleles, not permission to set hemizygotes=1 or to infer ploidy.
        var counts = new IndividualCounts(new BigInteger(homo.get("normalizedValue")), null, SumRelationship.UNRESOLVED);
        var decision = evaluate(originals.clause(), frequency, counts);
        assertEquals(Truth.FALSE, decision.frequencyTruth()); assertEquals("FAF95_THRESHOLD_NOT_SATISFIED", decision.frequencyReasonCode());
        assertEquals(Truth.UNKNOWN, decision.countTruth()); assertEquals("INDIVIDUAL_COUNT_CLASS_MISSING", decision.countReasonCode());
        assertEquals(Truth.UNKNOWN, decision.truth()); assertEquals("BA1_CONDITIONAL_REQUIRED_FACTS_UNRESOLVED", decision.reasonCode());
        assertArrayEquals(originals.compiledBytes(), Files.readAllBytes(originals.compiledPath()));
        assertArrayEquals(originals.rawBytes(), Files.readAllBytes(originals.rawPath()));
        String output = required("varvis.t03.ba1.real.output"); Path folder = Path.of(output); Files.createDirectories(folder);
        var report = mapper.createObjectNode().put("layer", "REAL_PINNED_ORIGINALS_DIRECT_MATH_NOT_POLICY_ADOPTION")
                .put("clinicalAdoption", "NOT_CLAIMED").put("normalBusinessAndPersistence", "NOT_RUN")
                .put("geneDiseaseContext", "NOT_CONSTRUCTED_OR_ASSERTED_BY_THIS_REPLAY")
                .put("hemizygoteConsumer", "NOT_IMPLEMENTED_NOT_AC_XY_INFERENCE");
        var sources = report.putObject("sources");
        sources.putObject("compiled").put("sha256", Bs1KnowledgeCatalog.sha(originals.compiledBytes())).set("ruleSourceRefs", originals.clause().path("sourceRefs"));
        sources.putObject("originalRule").put("sha256", RULE_SHA).put("specificationId", "GN027").put("version", "2.1").put("jsonPointer", RULE_POINTER);
        sources.putObject("raw").put("sha256IncludingTerminalLF", RAW_SHA).put("contig", originals.columns()[0])
                .put("position", originals.columns()[1]).put("reference", originals.columns()[3]).put("alternate", originals.columns()[4])
                .put("recordFilter", originals.columns()[6]).put("selectedAltIndexZeroBased", 0).put("recordAltCount", 1);
        sources.putObject("profile").put("classpath", PROFILE_RESOURCE).put("rawSha256", PROFILE_RAW_SHA)
                .put("canonicalSha256", originals.profile().getProfileSha256()).put("dataset", originals.profile().getDatasetType());
        var header = sources.putObject("header").put("classpath", HEADER_RESOURCE).put("sha256", HEADER_SHA);
        var definitions = header.putArray("infoDefinitions"); String headerText = new String(originals.headerBytes(), StandardCharsets.UTF_8);
        for (String id : List.of("nhomalt_joint", "fafmax_faf95_max_joint", "faf95_joint_nfe", "AN_joint_nfe")) definitions.add(headerLine(headerText, id));
        var readings = report.putObject("typedReadings"); readings.set("faf95", mapper.valueToTree(faf)); readings.set("ancestry", mapper.valueToTree(ancestry));
        readings.set("alleleNumber", mapper.valueToTree(an)); readings.set("homozygoteIndividuals", mapper.valueToTree(homo)); readings.putNull("hemizygoteIndividuals");
        readings.putObject("notUsedAsHemizygoteCount").put("infoId", "AC_joint_XY").put("rawLexeme", originals.info().get("AC_joint_XY")).put("unit", "alleles");
        report.putObject("profileInputReadiness").put("qualityStatus", parsed.getInputQualityStatus())
                .put("readinessStatus", parsed.getInputReadinessStatus()).set("reasonCodes", mapper.valueToTree(parsed.getReasonCodes()));
        report.set("directDecision", mapper.valueToTree(decision));
        Files.write(folder.resolve("real-source-readings-and-decision.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
    }

    @Test void wrongRawShaTargetAltAndRuleScopeAreRejectedBeforeSupplyingFacts() throws Exception {
        var originals = originals();
        assertThrows(IllegalArgumentException.class, () -> readVerified(originals.rawPath(), "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> verifyRawAllele(originals.columns(), "G"));
        var outOfRange = originals.profile().parse(originals.columns()[6], originals.info(), 1, 1);
        assertTrue(outOfRange.getReasonCodes().contains("ALT_INDEX_OUT_OF_RANGE"));
        assertTrue(outOfRange.getMeasurements().stream().noneMatch(m -> "gnomad.joint.nhomalt".equals(m.get("metricId"))));
        JsonNode rule = mapper.readTree(readVerified(Path.of(required("varvis.t03.ba1.real.ruleSource")), RULE_SHA));
        var wrong = originals.clause().deepCopy(); ((ObjectNode) wrong.at("/sourceRefs/0")).put("specificationId", "GN900");
        var wrongScope = wrong; assertThrows(IllegalArgumentException.class, () -> verifyCompiledSourceScope(wrongScope, rule));
        wrong = originals.clause().deepCopy(); ((ObjectNode) wrong.at("/sourceRefs/0")).put("version", "2.2");
        var wrongVersion = wrong; assertThrows(IllegalArgumentException.class, () -> verifyCompiledSourceScope(wrongVersion, rule));
        wrong = originals.clause().deepCopy(); ((ObjectNode) wrong.at("/sourceRefs/0")).put("jsonPointer", "/data/3/ld/CriteriaCode/17/entContent/strengthDescriptor/2/text");
        var wrongClause = wrong; assertThrows(IllegalArgumentException.class, () -> verifyCompiledSourceScope(wrongClause, rule));
    }

    private static byte[] readVerified(Path path, String expectedSha) throws Exception {
        byte[] bytes = Files.readAllBytes(path); require(expectedSha.matches("[0-9a-f]{64}") && expectedSha.equals(Bs1KnowledgeCatalog.sha(bytes)), "ORIGINAL_BYTES_SHA_MISMATCH");
        return bytes;
    }
    private static void verifyRawAllele(String[] columns, String alternate) {
        require(columns.length == 8 && "chrX".equals(columns[0]) && "153691089".equals(columns[1])
                && "G".equals(columns[3]) && alternate.equals(columns[4]) && "A".equals(alternate), "ORIGINAL_TARGET_ALLELE_MISMATCH");
    }
    private static void verifyCompiledSourceScope(JsonNode clause, JsonNode rule) {
        require(clause.path("sourceRefs").size() == 1, "RULE_SOURCE_SCOPE_MISMATCH");
        var ref = clause.path("sourceRefs").path(0); var entity = rule.at("/data/3");
        require("GN027".equals(ref.path("specificationId").asText()) && "GN027".equals(entity.path("entId").asText())
                && "2.1".equals(ref.path("version").asText()) && "2.1".equals(entity.at("/entContent/version").asText())
                && "https://cspec.genome.network/cspec/SequenceVariantInterpretation/id".equals(ref.path("url").asText())
                && RULE_SHA.equals(ref.path("sha256").asText()) && RULE_POINTER.equals(ref.path("jsonPointer").asText())
                && "BA1".equals(entity.at("/ld/CriteriaCode/18/entContent/label").asText())
                && rule.at(RULE_POINTER).isTextual() && clause.path("originalText").equals(rule.at(RULE_POINTER)), "RULE_SOURCE_SCOPE_MISMATCH");
    }
    private static Map<String, String> measurement(GnomadPopulationInputProfile.Result parsed, String metric, String unit) {
        var found = new ArrayList<Map<String, String>>(); for (var value : parsed.getMeasurements()) if (metric.equals(value.get("metricId"))) found.add(value);
        require(found.size() == 1 && unit.equals(found.get(0).get("unit")), "TYPED_MEASUREMENT_SCOPE_OR_UNIT_MISMATCH"); return found.get(0);
    }
    private static JsonNode field(JsonNode profile, String infoId) {
        for (var field : profile.at("/fieldContract/fields")) if (infoId.equals(field.path("infoId").asText())) return field;
        throw new IllegalArgumentException("PROFILE_FIELD_MISSING");
    }
    private static String headerLine(String header, String infoId) {
        var matches = header.lines().filter(line -> line.startsWith("##INFO=<ID=" + infoId + ",")).toList();
        require(matches.size() == 1, "OFFICIAL_HEADER_FIELD_AMBIGUOUS_OR_MISSING"); return matches.get(0);
    }
    private static byte[] resource(String path) throws Exception {
        try (InputStream in = Ba1ConditionalOriginalSourceReplayTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("TRACKED_RESOURCE_MISSING"); return in.readAllBytes();
        }
    }
    private static String required(String name) {
        String value = System.getProperty(name); require(value != null && !value.isBlank(), "REPLAY_PROPERTY_REQUIRED:" + name); return value;
    }
    private static void require(boolean valid, String code) { if (!valid) throw new IllegalArgumentException(code); }
}
