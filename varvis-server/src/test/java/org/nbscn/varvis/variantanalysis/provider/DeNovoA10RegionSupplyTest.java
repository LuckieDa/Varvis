package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.DeNovoEvidenceCalculator.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.lang.Nullable;

/** Executes the reviewed, committed S0 in an isolated loader; family/knowledge facts are synthetic. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeNovoA10RegionSupplyTest {
    private static final String PEER = "c0bf6bbf0e5f8e14c33cf4837d380e12eecaa360";
    private static final String CATALOG = "41b99cb587f84e0f9823a7994ea29573a3f6aad619ebe02d011e02b964b820aa";
    private static final String TYPE = "org.nbscn.varvis.variantanalysis.provider.SexChromosomeRegionResolver";
    private static final String RESOURCE = "acmg/sex-chromosome-regions-v1.json";
    @TempDir static Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    @Nullable private URLClassLoader loader;
    @Nullable private Method method;

    @BeforeAll void loadCommittedS0() throws Exception {
        String requested = System.getProperty("denovo.test.a10.commit");
        Assumptions.assumeTrue(requested != null, "Explicit reviewed A10 commit required; no stub substitutes for S0");
        assertEquals(PEER, requested, "Only the reviewed S0 commit is authorized by this test");
        Path current = Path.of("").toAbsolutePath();
        Path repository = Path.of(new String(git(current, "rev-parse", "--show-toplevel"), StandardCharsets.UTF_8).strip());
        Path source = temporary.resolve("source/org/nbscn/varvis/variantanalysis/provider/SexChromosomeRegionResolver.java");
        Path classes = temporary.resolve("classes");
        Files.createDirectories(source.getParent()); Files.createDirectories(classes.resolve("acmg"));
        Files.write(source, git(repository, "show", PEER + ":varvis-server/src/main/java/org/nbscn/varvis/variantanalysis/provider/SexChromosomeRegionResolver.java"));
        byte[] catalog = git(repository, "show", PEER + ":varvis-server/src/main/resources/" + RESOURCE);
        assertEquals(CATALOG, FamilyDeNovoJson.sha256(catalog)); Files.write(classes.resolve(RESOURCE), catalog);
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK compiler required for actual committed S0 verification");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        if (classpath == null || classpath.isBlank()) throw new IllegalStateException("Current test classpath is required");
        var diagnostics = new ByteArrayOutputStream();
        assertEquals(0, compiler.run(null, diagnostics, diagnostics, "--release", "21", "-classpath", classpath,
                "-d", classes.toString(), source.toString()), diagnostics.toString(StandardCharsets.UTF_8));
        var isolated = new PeerLoader(classes.toUri().toURL(), getClass().getClassLoader()); loader = isolated;
        var peer = Class.forName(TYPE, true, isolated);
        assertSame(isolated, peer.getClassLoader());
        assertEquals(CATALOG, peer.getField("CATALOG_SHA256").get(null));
        method = peer.getMethod("assess", ObjectMapper.class, JsonNode.class);
    }

    @AfterAll void closeCommittedS0() throws IOException {
        var isolated = loader; if (isolated != null) isolated.close();
    }

    private static byte[] git(Path directory, String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>(); command.add("git"); command.addAll(List.of(arguments));
        var process = new ProcessBuilder(command).directory(directory.toFile()).start();
        byte[] output = process.getInputStream().readAllBytes(), error = process.getErrorStream().readAllBytes();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Read-only committed-source lookup timed out");
        assertEquals(0, process.exitValue(), new String(error, StandardCharsets.UTF_8));
        return output;
    }

    private static final class PeerLoader extends URLClassLoader {
        PeerLoader(URL classes, ClassLoader parent) { super(new URL[]{classes}, parent); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!TYPE.equals(name)) return super.loadClass(name, resolve);
            Class<?> type = findLoadedClass(name); if (type == null) type = findClass(name);
            if (resolve) resolveClass(type); return type;
        }
        @Override @Nullable public URL getResource(String name) { return RESOURCE.equals(name) ? findResource(name) : super.getResource(name); }
        @Override @Nullable public InputStream getResourceAsStream(String name) {
            if (!RESOURCE.equals(name)) return super.getResourceAsStream(name);
            URL resource = findResource(name); if (resource == null) return null;
            try { return resource.openStream(); } catch (IOException unavailable) { return null; }
        }
    }

    private ObjectNode assess(JsonNode allele) {
        var entry = method;
        if (entry == null) throw new IllegalStateException("Reviewed S0 was not loaded");
        try {
            Object result = entry.invoke(null, mapper, allele);
            if (result instanceof ObjectNode receipt) return receipt;
            throw new IllegalStateException("S0 returned no receipt");
        } catch (IllegalAccessException | InvocationTargetException failed) { throw new IllegalStateException("S0 execution failed", failed); }
    }

    private ObjectNode allele(String assembly, String contig, int position, String ref, String alt) {
        return mapper.createObjectNode().put("assembly", assembly).put("contig", contig).put("position", position)
                .put("reference", ref).put("alternate", alt);
    }

    @Test void actualS0PreservesAssemblyAliasesParNonParAndEntireInsertionBoundaries() {
        String[][] vectors = {{"GRCh37", "X", "60001", "A", "G", "PAR"}, {"GRCh37", "X", "60000", "A", "G", "NON_PAR"},
                {"GRCh37", "X", "154931044", "A", "G", "PAR"}, {"GRCh38", "X", "10001", "A", "G", "PAR"},
                {"GRCh38", "X", "10000", "A", "G", "NON_PAR"}, {"GRCh38", "X", "155701383", "A", "G", "PAR"},
                {"GRCh38", "chrX", "10001", "A", "G", "PAR"}, {"GRCh38", "X", "9999", "A", "AT", "NON_PAR"},
                {"GRCh38", "X", "10000", "A", "AT", "CROSS_BOUNDARY"}, {"GRCh38", "X", "10001", "A", "AT", "PAR"},
                {"GRCh38", "X", "2781479", "A", "AT", "CROSS_BOUNDARY"}, {"GRCh38", "X", "10000", "AC", "GT", "CROSS_BOUNDARY"},
                {"GRCh38", "X", "10000", "AC", "A", "PAR"}};
        var supply = DeNovoA10RegionSupply.fromAssessor(mapper, CATALOG, this::assess);
        for (var vector : vectors) {
            var query = allele(vector[0], vector[1], Integer.parseInt(vector[2]), vector[3], vector[4]); var before = query.deepCopy();
            var result = supply.resolve(query);
            assertEquals(vector[5], result.region(), query.toString()); assertEquals(before, query);
            assertEquals(vector[0], result.assembly()); assertEquals(CATALOG, result.sourceSha256());
            assertEquals(new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(query), result.alleleSha256());
            if ("CROSS_BOUNDARY".equals(result.region())) assertEquals("SEX_REGION_EVENT_CROSSES_PAR_BOUNDARY", result.reasonCode());
        }
    }

    @Test void unresolvedS0AndAutosomalResultsNeverBecomeNonPar() {
        var supply = DeNovoA10RegionSupply.fromAssessor(mapper, CATALOG, this::assess);
        var wrongBuild = supply.resolve(allele("GRCh37.p13", "X", 10001, "A", "G"));
        assertEquals("UNKNOWN", wrongBuild.region()); assertEquals("SEX_REGION_ASSEMBLY_UNSUPPORTED", wrongBuild.reasonCode());
        var autosomal = supply.resolve(allele("GRCh38", "1", 10001, "A", "G"));
        assertEquals("UNKNOWN", autosomal.region()); assertEquals("SEX_REGION_AUTOSOMAL_QUERY", autosomal.reasonCode());
        var rawRegion = supply.resolve(allele("GRCh38", "X", 10001, "A", "G").put("region", "NON_PAR"));
        assertEquals("UNKNOWN", rawRegion.region()); assertEquals("SEX_REGION_ALLELE_INVALID", rawRegion.reasonCode());
    }

    @Test void mutatedReceiptsPinsInputAndQualificationClaimsCannotGrantARegion() {
        var query = allele("GRCh38", "X", 5000000, "A", "G");
        List<Consumer<ObjectNode>> corruptions = List.of(r -> r.put("methodVersion", "2.0.0"),
                r -> r.put("sourceCatalogSha256", "0".repeat(64)), r -> r.put("queryAlleleSha256", "0".repeat(64)),
                r -> ((ObjectNode) r.path("queryAllele")).put("position", 5000001),
                r -> r.put("clinicalQualificationGranted", true), r -> r.put("patientPloidyInferred", true),
                r -> r.put("resolvedContig", "Y"), r -> r.put("contigAliasUsed", true),
                r -> r.put("coordinateSystem", "ZERO_BASED"), r -> r.put("boundaryConvention", "ANCHOR_BASE_ONLY"),
                r -> ((ArrayNode) r.path("sourceBindings")).remove(0),
                r -> ((ObjectNode) r.path("sourceBindings").get(0)).put("sha256", "invalid"),
                r -> ((ObjectNode) r.path("sourceBindings").get(0)).put("url", "https://example.org/claimed-source"));
        for (var corruption : corruptions) {
            Function<JsonNode, JsonNode> corrupted = a -> { var receipt = assess(a); corruption.accept(receipt); return receipt; };
            assertEquals("UNKNOWN", DeNovoA10RegionSupply.fromAssessor(mapper, CATALOG, corrupted).resolve(query).region());
        }
        assertEquals("UNKNOWN", DeNovoA10RegionSupply.fromAssessor(mapper, "0".repeat(64), this::assess).resolve(query).region());
        assertThrows(IllegalArgumentException.class, () -> DeNovoA10RegionSupply.fromAssessor(mapper, "bad-pin", this::assess));
        var before = query.deepCopy();
        var mutated = DeNovoA10RegionSupply.fromAssessor(mapper, CATALOG, a -> { ((ObjectNode) a).put("position", 10001); return assess(a); });
        assertEquals("UNKNOWN", mutated.resolve(query).region()); assertEquals(before, query);
    }

    @Test void actualS0NonParReachesCalculatorAndCrossBoundaryOrParRuleRemainsExplicit() throws Exception {
        var supply = DeNovoA10RegionSupply.fromAssessor(mapper, CATALOG, this::assess);
        var nonPar = calculate(allele("GRCh38", "X", 5000000, "A", "G"), supply, false);
        assertEquals("MET", nonPar.status(), nonPar.toString()); assertEquals("PS2", nonPar.code());
        assertEquals("MODERATE", nonPar.strength()); assertEquals(4, nonPar.effectiveQuarterPoints());
        var crossing = calculate(allele("GRCh38", "X", 10000, "A", "AT"), supply, false);
        assertEquals("NOT_EVALUATED", crossing.status());
        assertTrue(crossing.events().stream().anyMatch(e -> "SEX_REGION_EVENT_CROSSES_PAR_BOUNDARY".equals(e.reasonCode())), crossing.toString());
        var par = calculate(allele("GRCh38", "X", 10001, "A", "G"), supply, true);
        assertEquals("NOT_EVALUATED", par.status());
        assertTrue(par.events().stream().anyMatch(e -> "DENOVO_PAR_INHERITANCE_RULE_NOT_IMPLEMENTED".equals(e.reasonCode())), par.toString());
    }

    private Result calculate(JsonNode query, DeNovoSpecialEvent.RegionSupply supply, boolean diploid) throws Exception {
        byte[] source = "SYNTHETIC source-pinned X phenotype knowledge".getBytes(StandardCharsets.UTF_8);
        var p = mapper.createObjectNode().put("schemaVersion", DeNovoApplicabilityPolicy.SCHEMA).put("policyId", "SYNTHETIC-X")
                .put("version", "1").put("materialClass", "SYNTHETIC").put("allowAssumedParentage", true).put("expertPolicyStatus", "GENERAL_ALLOWED");
        p.putObject("scope").put("hgncId", "HGNC:1").put("diseaseId", "MONDO:0000001").put("inheritance", "X_LINKED");
        p.putArray("sources").addObject().put("sourceId", "s").put("sha256", FamilyDeNovoJson.sha256(source)).put("locator", "/")
                .put("quote", new String(source, StandardCharsets.UTF_8));
        var condition = p.putArray("conditions").addObject().put("conditionId", "consistent").put("category", "CONSISTENT");
        condition.putArray("presentAll").add("HP:0001250"); condition.putArray("absentAll"); condition.putArray("sourceIds").add("s");
        p.putObject("specialEvents").put("mode", "SVI_1_1").putArray("sourceIds").add("s");
        byte[] policyRaw = mapper.writeValueAsBytes(p);
        var policy = DeNovoApplicabilityPolicy.load(policyRaw, FamilyDeNovoJson.sha256(policyRaw), Map.of("s", source), DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION);
        var event = mapper.createObjectNode().put("schemaVersion", "denovo-special-event-v1").put("kind", "CONSTITUTIONAL")
                .put("familyId", "SYNTHETIC-F").put("eventId", "SYNTHETIC-E").put("personId", "P").put("probandId", "P")
                .put("sex", diploid ? "FEMALE" : "MALE").put("ploidy", diploid ? "DIPLOID" : "HAPLOID")
                .put("genotype", diploid ? "HETEROZYGOUS" : "HEMIZYGOUS_ALT").put("affectedStatus", "AFFECTED")
                .put("parentalAvailability", "AVAILABLE").put("otherAffectedMales", "UNKNOWN").put("eventOrigin", "UNKNOWN");
        event.putObject("trio").put("personId", "P").put("motherId", "M").put("fatherId", "F")
                .put("subjectTarget", "PRESENT").put("motherTarget", "ABSENT").put("fatherTarget", "ABSENT")
                .put("maternity", "CONFIRMED").put("paternity", "CONFIRMED").put("maternityMethod", "SNP_PANEL").put("paternityMethod", "SNP_PANEL")
                .put("method", "SANGER").put("targetCoverage", "EXPLICIT_TARGET").put("detectionCapability", "ADEQUATE").put("quality", "VALID");
        var partner = event.putObject("partner").put("status", "UNRESOLVED").put("phase", "UNKNOWN"); partner.putNull("allele"); partner.putArray("sourceRefs");
        event.putArray("siblings"); event.putArray("tissues");
        var group = new Group(query.path("assembly").asText() + ":X:" + query.path("position").asLong() + ":" + query.path("reference").asText()
                + ":" + query.path("alternate").asText(), "HGNC:1", "MONDO:0000001", "X_LINKED");
        var read = DeNovoSpecialEvent.read(event, "9".repeat(64), query, group, policy.specialEvents(), supply);
        assertTrue(read.issues().isEmpty(), read.toString()); var verified = read.value(); assertNotNull(verified);
        var facts = new Facts(group, TargetTest.PRESENT, TargetTest.ABSENT_QUALIFIED, TargetTest.ABSENT_QUALIFIED,
                Parentage.CONFIRMED, Parentage.CONFIRMED, Phenotype.CONSISTENT, FamilyHistory.COMPATIBLE, verified);
        return new DeNovoEvidenceCalculator().evaluate(group, policy.calculation(), List.of(new Observation("SYNTHETIC-source", verified.eventId(),
                SourceKind.CURRENT_CASE, facts, Independence.ESTABLISHED, verified.sourceRefs(), List.of())));
    }
}
