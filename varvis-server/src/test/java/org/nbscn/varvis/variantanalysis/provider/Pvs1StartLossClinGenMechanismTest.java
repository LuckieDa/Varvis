package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossClinGenMechanismTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String[] DH = {"GENE SYMBOL", "HGNC ID", "HAPLOINSUFFICIENCY", "TRIPLOSENSITIVITY", "ONLINE REPORT", "DATE"};
    private static final String[] VH = {"GENE SYMBOL", "GENE ID (HGNC)", "DISEASE LABEL", "DISEASE ID (MONDO)", "MOI", "SOP", "CLASSIFICATION", "ONLINE REPORT", "CLASSIFICATION DATE", "GCEP"};
    private static final String[] TH = {"Gene Symbol", "Gene ID", "cytoBand", "Genomic Location", "Haploinsufficiency Score", "Haploinsufficiency Description",
            "Haploinsufficiency PMID1", "Haploinsufficiency PMID2", "Haploinsufficiency PMID3", "Haploinsufficiency PMID4", "Haploinsufficiency PMID5", "Haploinsufficiency PMID6",
            "Triplosensitivity Score", "Triplosensitivity Description", "Triplosensitivity PMID1", "Triplosensitivity PMID2", "Triplosensitivity PMID3", "Triplosensitivity PMID4", "Triplosensitivity PMID5", "Triplosensitivity PMID6",
            "Date Last Evaluated", "Haploinsufficiency Disease ID", "Triplosensitivity Disease ID"};

    ObjectNode context() {
        try {
            var c = new Pvs1StartLossRuntimeTest().fixture(1).data().context;
            c.withObject("gene").put("symbol", "GENE1").put("hgncId", "HGNC:100");
            c.withObject("disease").put("mondoId", "MONDO:0000001"); c.put("moi", "AUTOSOMAL_DOMINANT"); return c;
        } catch (Exception invalidFixture) { throw new AssertionError(invalidFixture); }
    }
    String[] dosage() { return new String[]{"GENE1", "HGNC:100", "Sufficient Evidence for Haploinsufficiency", "No Evidence for Triplosensitivity",
            "https://search.clinicalgenome.org/kb/gene-dosage/HGNC:100", "2025-01-01T12:00:00+00:00"}; }
    String[] validity() { return new String[]{"GENE1", "HGNC:100", "Specific disease", "MONDO:0000001", "AD", "SOP8", "Definitive",
            "https://search.clinicalgenome.org/kb/gene-validity/CGGV:assertion_12345678-abcd-ef01-abcd-123456789012", "2025-01-02T12:00:00.000Z", "Example GCEP"}; }
    String[] diseaseDosage() {
        String[] row = new String[23]; Arrays.fill(row, "");
        row[0] = "GENE1"; row[1] = "123"; row[2] = "1p1"; row[3] = "chr1:100-1000";
        row[4] = "3"; row[5] = "Sufficient evidence for dosage pathogenicity"; row[6] = "12345";
        row[12] = "0"; row[13] = "No evidence available"; row[20] = "2025-01-01"; row[21] = "MONDO:0000001"; return row;
    }
    String encode(List<String> values) { return values.stream().map(s -> "\"" + s.replace("\"", "\"\"") + "\"").collect(java.util.stream.Collectors.joining(",")) + "\r\n"; }
    String csv(String title, String[] header, String[] row) {
        String[] empty = new String[header.length]; Arrays.fill(empty, "");
        empty[0] = title; String text = encode(Arrays.asList(empty)); empty[0] = "FILE CREATED: 2026-11-03";
        text += encode(Arrays.asList(empty)); empty[0] = "WEBPAGE: official"; text += encode(Arrays.asList(empty));
        Arrays.fill(empty, "+"); return text + encode(Arrays.asList(empty)) + encode(Arrays.asList(header)) + encode(Arrays.asList(empty)) + encode(Arrays.asList(row));
    }
    String tsv(String[] row) {
        return "#ClinGen Gene Curation Results\n#02 Nov,2026\n#Genomic Locations are reported on GRCh38 (hg38): GCF_000001405.36\n#source\n#links\n#"
                + String.join("\t", TH) + "\n" + String.join("\t", row) + "\n";
    }
    Pvs1StartLossPublicSources.Snapshot snapshot(String url, String raw) {
        return new Pvs1StartLossPublicSources.Snapshot(mapper.createObjectNode().put("status", "AVAILABLE").put("sourceUrl", url)
                .put("sha256", CodingTranscriptMaterialSupply.sha(raw.getBytes(StandardCharsets.UTF_8)))
                .put("capturedAt", "2026-11-03T12:00:00Z"), mapper.createObjectNode().put("rawCsv", raw));
    }
    Pvs1StartLossClinGenMechanism.Assessment assess(ObjectNode c, String[] d, String[] v, String[] t) {
        return Pvs1StartLossClinGenMechanism.assess(mapper, c,
                snapshot(Pvs1StartLossClinGenMechanism.DOSAGE_URL, csv("CLINGEN DOSAGE SENSITIVITY CURATIONS", DH, d)),
                snapshot(Pvs1StartLossClinGenMechanism.VALIDITY_URL, csv("CLINGEN GENE DISEASE VALIDITY CURATIONS", VH, v)),
                snapshot(Pvs1StartLossClinGenMechanism.DISEASE_DOSAGE_URL, tsv(t)));
    }
    Pvs1KnowledgeCatalog catalog(ObjectNode c, java.util.function.Consumer<ObjectNode> mutate) {
        var policy = mapper.createObjectNode(); var scope = policy.putObject("scope");
        scope.put("hgncId", c.at("/gene/hgncId").asText()).put("mondoId", c.at("/disease/mondoId").asText()).put("moi", c.path("moi").asText());
        var facts = Pvs1KnowledgeCatalogTest.facts(mapper, List.of(policy));
        facts.withObject("source").put("url", "https://www.ebi.ac.uk/gene2phenotype/api/panel/all/download/");
        var raw = (ObjectNode) facts.at("/records/0/sourceRecord");
        raw.put("gene symbol", c.at("/gene/symbol").asText()).put("disease name", "Specific disease")
                .put("date of last review", "2025-01-01 12:00:00+00:00").put("comments", "").put("review", "").put("cross cutting modifier", "");
        mutate.accept(raw); ((ObjectNode) facts.at("/records/0")).put("sourceRecordSha256", Pvs1KnowledgeCatalog.sourceRecordSha(mapper, raw));
        String pin = CodingTranscriptMaterialSupply.sha(facts.toString());
        var adoption = mapper.createObjectNode().put("schemaVersion", "pvs1-knowledge-adoption-v1").put("catalogId", "TEST_ONLY")
                .put("catalogVersion", "1").put("sourceCandidatesSha256", pin).put("sourceDataClass", "PUBLIC_REFERENCE");
        adoption.putArray("entries");
        return new Pvs1KnowledgeCatalog(mapper, facts, pin, adoption, "b".repeat(64));
    }
    Pvs1StartLossClinGenMechanism.Assessment alternative(ObjectNode c, Pvs1KnowledgeCatalog.StartLossAssociation a, String[] validityRow) {
        return Pvs1StartLossClinGenMechanism.assess(mapper, c,
                snapshot(Pvs1StartLossClinGenMechanism.DOSAGE_URL, csv("CLINGEN DOSAGE SENSITIVITY CURATIONS", DH, dosage())),
                snapshot(Pvs1StartLossClinGenMechanism.VALIDITY_URL, csv("CLINGEN GENE DISEASE VALIDITY CURATIONS", VH, validityRow)),
                snapshot(Pvs1StartLossClinGenMechanism.DISEASE_DOSAGE_URL, tsv(diseaseDosage())), a);
    }
    String[] unrelatedValidity() { var v = validity(); v[0] = "OTHER"; v[1] = "HGNC:999"; v[3] = "MONDO:0000999"; return v; }
    @Test void pinnedG2pAssociationAndIndependentExactHi3DoNotRelabelInferredMechanism() {
        var c = context(); var catalog = catalog(c, r -> {}); var anchor = catalog.startLossAssociation(c);
        assertTrue(anchor.qualified()); assertFalse(anchor.snapshot().path("mechanismEstablished").asBoolean(true));
        assertEquals("inferred", anchor.snapshot().at("/records/0/sourceRecord/molecular mechanism support").asText());
        var a = alternative(c, anchor, unrelatedValidity()); assertTrue(a.qualified(), a.snapshot()::toPrettyString);
        assertEquals("CLINGEN_SAME_DISEASE_HAPLOINSUFFICIENCY_AND_G2P_ASSOCIATION", a.snapshot().path("basis").asText());
        assertEquals(2, a.snapshot().at("/binding/sources").size());
        assertEquals("NO_EXACT_DISEASE_ASSOCIATION", a.snapshot().at("/clinGenValidityCheck/status").asText());
        var joined = Pvs1StartLossMechanism.withClinGen(mapper, c, catalog.startLossMechanism(c), a);
        assertEquals("CURATED_MECHANISM_SUPPORTED", joined.path("status").asText());
        assertEquals("inferred", joined.at("/g2pAssessment/findings/0/sourceRecord/molecular mechanism support").asText());
        var snapshot = anchor.snapshot(); snapshot.put("status", "CHANGED"); assertTrue(anchor.qualified());
    }
    @Test void associationStrengthRestrictionsIdentityAndContraryCurationsRemainBoundaries() {
        var c = context();
        for (String issue : List.of("weak", "restricted", "gene", "disease", "moi", "date")) {
            var catalog = catalog(c, raw -> { switch (issue) {
                case "weak" -> raw.put("confidence", "limited"); case "restricted" -> raw.put("comments", "Only a subset");
                case "gene" -> raw.put("gene symbol", "OTHER"); case "disease" -> raw.put("disease MONDO", "MONDO:0000002");
                case "moi" -> raw.put("allelic requirement", "biallelic_autosomal"); case "date" -> raw.put("date of last review", "unknown");
                default -> throw new AssertionError(); } });
            assertFalse(alternative(c, catalog.startLossAssociation(c), unrelatedValidity()).qualified(), issue);
        }
        var anchor = catalog(c, r -> {}).startLossAssociation(c);
        for (String classification : List.of("Refuted", "Disputed", "No Known Disease Relationship")) {
            var v = validity(); v[6] = classification; assertFalse(alternative(c, anchor, v).qualified(), classification);
        }
        var v = validity(); v[4] = "AR"; assertFalse(alternative(c, anchor, v).qualified());
        var other = c.deepCopy(); ((ObjectNode) other.at("/variant/evidenceQueryAllele")).put("position", 999);
        assertFalse(alternative(other, anchor, unrelatedValidity()).qualified());
        var contrary = catalog(c, r -> r.put("molecular mechanism", "gain of function"));
        var a = alternative(c, contrary.startLossAssociation(c), unrelatedValidity()); assertTrue(a.qualified());
        assertEquals("CONFLICT", Pvs1StartLossMechanism.withClinGen(mapper, c, contrary.startLossMechanism(c), a).path("status").asText());
    }
    @Test void sameDiseaseHi3AndStrongValidityAreOnlyTheMechanismPrerequisite() {
        var a = assess(context(), dosage(), validity(), diseaseDosage()); var facts = a.snapshot();
        assertTrue(a.qualified(), facts::toPrettyString);
        assertEquals("CLINGEN_SAME_DISEASE_HAPLOINSUFFICIENCY_AND_VALIDITY", facts.path("basis").asText());
        assertEquals(3, facts.at("/binding/sources").size());
        assertFalse(facts.path("runtimeEligible").asBoolean(true)); assertFalse(facts.path("transcriptRelevanceEstablished").asBoolean(true));
        facts.put("status", "CHANGED"); assertTrue(a.qualified());
    }
    @Test void geneLevelHiCannotBeTransferredToAnotherDiseaseOrInheritance() {
        var c = context(); c.withObject("disease").put("mondoId", "MONDO:0000002");
        assertFalse(assess(c, dosage(), validity(), diseaseDosage()).qualified());
        c = context(); c.put("moi", "AUTOSOMAL_RECESSIVE"); assertFalse(assess(c, dosage(), validity(), diseaseDosage()).qualified());
        var t = diseaseDosage(); t[21] = "MONDO:0000002"; assertFalse(assess(context(), dosage(), validity(), t).qualified());
        var v = validity(); v[4] = "AR"; assertFalse(assess(context(), dosage(), v, diseaseDosage()).qualified());
    }
    @Test void weakerUnknownConflictingScoresAndValidityCannotQualify() {
        for (String score : List.of("2", "0", "30", "Not yet evaluated")) {
            var t = diseaseDosage(); t[4] = score; assertFalse(assess(context(), dosage(), validity(), t).qualified());
        }
        for (String strength : List.of("Moderate", "Limited", "Disputed", "Refuted", "")) {
            var v = validity(); v[6] = strength; assertFalse(assess(context(), dosage(), v, diseaseDosage()).qualified());
        }
        var d = dosage(); d[2] = "Little Evidence for Haploinsufficiency"; assertFalse(assess(context(), d, validity(), diseaseDosage()).qualified());
        var t = diseaseDosage(); t[20] = "2024-01-01"; assertFalse(assess(context(), dosage(), validity(), t).qualified());
    }
    @Test void sourcePinUrlsSchemaDuplicateRowsAndForgedFlagsCannotBypassParsing() {
        String d = csv("CLINGEN DOSAGE SENSITIVITY CURATIONS", DH, dosage()), v = csv("CLINGEN GENE DISEASE VALIDITY CURATIONS", VH, validity());
        var ds = snapshot(Pvs1StartLossClinGenMechanism.DOSAGE_URL, d); var vs = snapshot(Pvs1StartLossClinGenMechanism.VALIDITY_URL, v);
        var ts = snapshot(Pvs1StartLossClinGenMechanism.DISEASE_DOSAGE_URL, tsv(diseaseDosage()));
        var receipt = ds.receipt(); receipt.put("sha256", "a".repeat(64));
        assertFalse(Pvs1StartLossClinGenMechanism.assess(mapper, context(), new Pvs1StartLossPublicSources.Snapshot(receipt, ds.document()), vs, ts).qualified());
        assertFalse(Pvs1StartLossClinGenMechanism.assess(mapper, context(), snapshot("https://example.com/download", d), vs, ts).qualified());
        assertFalse(Pvs1StartLossClinGenMechanism.assess(mapper, context(), snapshot(Pvs1StartLossClinGenMechanism.DOSAGE_URL, d + encode(Arrays.asList(dosage()))), vs, ts).qualified());
        var document = mapper.createObjectNode().put("rawCsv", "{}").put("qualified", true).put("status", "CURATED_MECHANISM_SUPPORTED");
        var forged = snapshot(Pvs1StartLossClinGenMechanism.DOSAGE_URL, "{}");
        assertFalse(Pvs1StartLossClinGenMechanism.assess(mapper, context(), new Pvs1StartLossPublicSources.Snapshot(forged.receipt(), document), vs, ts).qualified());
        assertFalse(Pvs1StartLossClinGenMechanism.assess(mapper, context(), snapshot(Pvs1StartLossClinGenMechanism.DOSAGE_URL, d + "\"unfinished"), vs, ts).qualified());
    }
    @Test void csvEscapedAndMultilineFieldsDoNotChangeColumnMeaning() {
        var v = validity(); v[2] = "Disease, with \"quoted\" label\nand a second line";
        var a = assess(context(), dosage(), v, diseaseDosage()); assertTrue(a.qualified(), a.snapshot()::toPrettyString);
        assertEquals(v[2], a.snapshot().path("diseaseLabel").asText());
    }
    @Test void alternativeSourceDoesNotRelabelInferredG2pOrEraseContraryOrRestrictedMechanisms() {
        var c = context(); var a = assess(c, dosage(), validity(), diseaseDosage());
        var g = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-mechanism-v1").put("status", "UNRESOLVED").put("basis", "G2P");
        var raw = g.putArray("findings").addObject().putObject("sourceRecord").put("hgnc id", "100").put("gene symbol", "GENE1")
                .put("disease MONDO", "MONDO:0000001").put("allelic requirement", "monoallelic_autosomal")
                .put("molecular mechanism", "loss of function").put("molecular mechanism support", "inferred");
        var result = Pvs1StartLossMechanism.withClinGen(mapper, c, g, a);
        assertEquals("CURATED_MECHANISM_SUPPORTED", result.path("status").asText());
        assertEquals("inferred", result.at("/g2pAssessment/findings/0/sourceRecord/molecular mechanism support").asText());
        assertEquals("UNRESOLVED", g.path("status").asText());
        raw.put("molecular mechanism", "gain of function");
        assertEquals("CONFLICT", Pvs1StartLossMechanism.withClinGen(mapper, c, g, a).path("status").asText());
        raw.put("molecular mechanism", "loss of function").put("comments", "Only a restricted variant set");
        assertEquals("UNRESOLVED", Pvs1StartLossMechanism.withClinGen(mapper, c, g, a).path("status").asText());
        var other = context(); other.withObject("disease").put("mondoId", "MONDO:0000002");
        assertEquals("PVS1_CLINGEN_MECHANISM_CONTEXT_MISMATCH", Pvs1StartLossMechanism.withClinGen(mapper, other, g, a).path("reasonCode").asText());
    }
    @Test @EnabledIfSystemProperty(named = "pvs1.nativeExpertRoot", matches = ".+")
    void nativeThreeOfficialDownloadsQualifyTwist1ExactDiseaseWithoutChangingG2p() throws Exception {
        Path folder = Path.of(System.getProperty("pvs1.nativeExpertRoot"), "local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102/goal-20261103");
        var c = context(); c.withObject("gene").put("hgncId", "HGNC:12428").put("symbol", "TWIST1");
        c.withObject("disease").put("mondoId", "MONDO:0007042");
        var a = Pvs1StartLossClinGenMechanism.assess(mapper, c,
                nativeSource(folder, "ClinGen-dosage-genes-current.csv", Pvs1StartLossClinGenMechanism.DOSAGE_URL, "03f9177f0a7277042fdaf2859be174d0d5100d4c45285e0c2aaa27a4f6f645b6"),
                nativeSource(folder, "ClinGen-validity-current.csv", Pvs1StartLossClinGenMechanism.VALIDITY_URL, "91a3bc89bd42d717daf0f744e6a1ebc09689bb4cadc6239abe6b7e5bfa40b372"),
                nativeSource(folder, "ClinGen-gene-dosage-GRCh38-current.tsv", Pvs1StartLossClinGenMechanism.DISEASE_DOSAGE_URL, "feed05fbd8b3f577ec9ff2ca107035f227bb4b066873a76710d28a35a98da28c"));
        assertTrue(a.qualified(), a.snapshot()::toPrettyString);
        assertEquals("MONDO:0007042", a.snapshot().at("/binding/mondoId").asText());
        assertEquals("Definitive", a.snapshot().path("validityClassification").asText());
        c.withObject("disease").put("mondoId", "MONDO:0060592");
        assertEquals("MONDO:0007042", a.snapshot().at("/binding/mondoId").asText(), "Bound qualified result is immutable across contexts");
    }
    @Test @EnabledIfSystemProperty(named = "pvs1.nativeExpertRoot", matches = ".+")
    void nativeFoxl2UsesPinnedDefinitiveAssociationWithIndependentSameDiseaseHi3() throws Exception {
        Path root = Path.of(System.getProperty("pvs1.nativeExpertRoot")); var props = new java.util.Properties();
        try (var reader = Files.newBufferedReader(root.resolve("local/runtime/bp7-evidence-workbench/evidence-overrides.properties"))) { props.load(reader); }
        String prefix = "variant-analysis.acmg-business.";
        var catalog = Pvs1KnowledgeCatalog.load(mapper, Path.of(props.getProperty(prefix + "pvs1-knowledge-source-file")), props.getProperty(prefix + "pvs1-knowledge-source-sha256"),
                Path.of(props.getProperty(prefix + "pvs1-knowledge-adoption-file")), props.getProperty(prefix + "pvs1-knowledge-adoption-sha256"));
        Path folder = root.resolve("local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102/goal-20261103");
        var c = context(); c.withObject("gene").put("hgncId", "HGNC:1092").put("symbol", "FOXL2"); c.withObject("disease").put("mondoId", "MONDO:0007201");
        var a = Pvs1StartLossClinGenMechanism.assess(mapper, c,
                nativeSource(folder, "ClinGen-dosage-genes-current.csv", Pvs1StartLossClinGenMechanism.DOSAGE_URL, "03f9177f0a7277042fdaf2859be174d0d5100d4c45285e0c2aaa27a4f6f645b6"),
                nativeSource(folder, "ClinGen-validity-current.csv", Pvs1StartLossClinGenMechanism.VALIDITY_URL, "91a3bc89bd42d717daf0f744e6a1ebc09689bb4cadc6239abe6b7e5bfa40b372"),
                nativeSource(folder, "ClinGen-gene-dosage-GRCh38-current.tsv", Pvs1StartLossClinGenMechanism.DISEASE_DOSAGE_URL, "feed05fbd8b3f577ec9ff2ca107035f227bb4b066873a76710d28a35a98da28c"), catalog.startLossAssociation(c));
        assertTrue(a.qualified(), a.snapshot()::toPrettyString);
        assertEquals("G2P00393:381", a.snapshot().at("/associationAnchor/records/0/recordId").asText());
        assertEquals("definitive", a.snapshot().at("/associationAnchor/records/0/sourceRecord/confidence").asText());
        assertEquals("inferred", a.snapshot().at("/associationAnchor/records/0/sourceRecord/molecular mechanism support").asText());
        assertEquals("CURATED_MECHANISM_SUPPORTED", Pvs1StartLossMechanism.withClinGen(mapper, c, catalog.startLossMechanism(c), a).path("status").asText());
    }
    private Pvs1StartLossPublicSources.Snapshot nativeSource(Path folder, String name, String url, String pin) throws Exception {
        byte[] bytes = Files.readAllBytes(folder.resolve(name)); assertEquals(pin, CodingTranscriptMaterialSupply.sha(bytes));
        var receipt = mapper.readTree(folder.resolve(name + ".receipt.json").toFile());
        assertEquals(url, receipt.path("sourceURL").asText()); assertEquals(url, receipt.path("finalURL").asText());
        assertEquals(200, receipt.path("httpStatus").asInt()); assertEquals(pin, receipt.path("sha256").asText());
        return new Pvs1StartLossPublicSources.Snapshot(mapper.createObjectNode().put("status", "AVAILABLE").put("sourceUrl", url)
                .put("sha256", pin).put("capturedAt", receipt.path("captureTime").asText()),
                mapper.createObjectNode().put("rawCsv", new String(bytes, StandardCharsets.UTF_8)));
    }
}
