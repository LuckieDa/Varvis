package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runtime/source-scope and finite projection checks; no real TPM1 sequence or clear review is invented. */
class GeneralPp2HcmRuntimeTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();

    private Pp2OtherPhenotypeSource source(AtomicInteger calls) {
        return new Pp2OtherPhenotypeSource(mapper, Path.of(System.getProperty("varvis.pp2.python", "not-used")),
                Path.of("..").toAbsolutePath().normalize(), root.resolve("source-work"), List.of(), List.of(),
                new Pp2OtherPhenotypeDiseaseSemantics(mapper), (command, directory, timeout) -> {
                    calls.incrementAndGet(); throw new IOException("SOURCE_MUST_NOT_BE_READ_FOR_RAS");
                });
    }
    private GeneralPp2RuntimeTest fixture() throws Exception {
        var fixture = new GeneralPp2RuntimeTest(); fixture.root = root; fixture.setup(); return fixture;
    }
    private PublishedCriterionKnowledge hcmKnowledge() throws Exception {
        String file = System.getProperty("t05.hcm.knowledge");
        org.junit.jupiter.api.Assumptions.assumeTrue(file != null, "Actual compiler output required");
        if (file == null) throw new IllegalStateException("HCM_KNOWLEDGE_PATH_REQUIRED");
        byte[] bytes = Files.readAllBytes(Path.of(file));
        return PublishedCriterionKnowledge.load(mapper, Path.of(file), CodingTranscriptMaterialSupply.sha(bytes));
    }

    @Test void existingRasMethodDoesNotAcquireTheHcmDependencyEvenWhenConfigured() throws Exception {
        var fixture = fixture(); var calls = new AtomicInteger(); var source = source(calls);
        var runtime = new GeneralPp2Runtime(mapper, fixture.material, fixture.load(fixture.document()), true, source);
        var firstIdentity = runtime.identity(); assertEquals(firstIdentity, runtime.identity()); assertEquals(0, calls.get());
        var out = fixture.run(runtime); assertEquals("MET", out.path("status").asText(), out.toPrettyString());
        assertEquals("SUPPORTING", out.at("/evaluation/appliedStrength").asText());
        assertEquals("1.1.0", out.at("/publicSummary/policyVersion").asText());
        assertEquals("PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS", out.at("/publicSummary/scope").asText());
        assertFalse(out.has("hcmSourceMethod")); assertFalse(out.has("otherPhenotypeReview"));
        assertFalse(Files.exists(root.resolve("source-work"))); assertEquals(0, calls.get());
    }

    @Test void actualGn100CannotBeBorrowedByAnUnrelatedSyntheticGene() throws Exception {
        var fixture = fixture(); var knowledge = hcmKnowledge();
        var calls = new AtomicInteger(); var runtime = new GeneralPp2Runtime(mapper, fixture.material, knowledge, true, source(calls));
        var before = fixture.data.input.fixture().context().deepCopy(); var out = fixture.run(runtime);
        assertEquals("NOT_EVALUATED", out.path("status").asText());
        assertEquals("PUBLISHED_KNOWLEDGE_BACKGROUND_NOT_COVERED", out.path("reasonCode").asText());
        assertEquals("NONE", out.at("/evaluation/appliedStrength").asText());
        assertEquals(before, fixture.data.input.fixture().context()); assertEquals(0, calls.get());
    }

    @Test void sourceMethodProjectionHasNoRawClauseOrPhantomQualifiedReview() throws Exception {
        var selected = new Pp2HcmPublishedSourceTest().selection();
        var out = mapper.createObjectNode().put("status", "NOT_EVALUATED").put("reasonCode", "PP2_HCM_OTHER_PHENOTYPE_SOURCE_UNAVAILABLE");
        out.putObject("policy").put("germlineDeclared", true);
        out.set("knowledgeSelection", selected); out.set("hcmSourceMethod", Pp2HcmPublishedSource.inspect(mapper, selected).snapshot());
        var before = out.deepCopy(); var summary = GeneralPp2Runtime.publicSummary(out);
        assertEquals(Pp2HcmPublishedSource.TYPE, summary.path("method").asText());
        assertEquals("1.0.0", summary.path("methodVersion").asText());
        assertEquals("PROJECT_RESEARCH_PUBLISHED_HCM_ENRICHMENT", summary.path("scope").asText());
        assertEquals("TPM1", summary.at("/hcmSourceMethod/scope/geneSymbol").asText());
        assertFalse(summary.has("otherPhenotypeReview")); assertFalse(summary.has("conditions"));
        assertFalse(summary.toString().contains("Data from HCM case cohorts"));
        assertFalse(summary.toString().contains("strengthDescriptor")); assertEquals(before, out);
        String artifacts = System.getProperty("t05.hcm.publicArtifacts");
        if (artifacts != null) {
            Path directory = Path.of(artifacts); Files.createDirectories(directory);
            var sample = mapper.createObjectNode().put("layer", "ACTUAL_NORMATIVE_SOURCE_PROJECTION_ONLY_NOT_NORMAL_RUNTIME")
                    .put("variantAdoptionGranted", false); sample.set("publicSummary", summary);
            Files.write(directory.resolve("source-method-public-summary.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(sample));
        }
    }
    @Test void dualContextContractPreservesGenomicTargetAndOriginalPatientWithoutGuessingTranscriptVersions() throws Exception {
        var selected = new Pp2HcmPublishedSourceTest().selection();
        var prepared = mapper.createObjectNode().put("moi", "AUTOSOMAL_DOMINANT").put("fixtureMeaning", "CONTEXT_WIRE_CONTROL_NOT_SEQUENCE_QUALIFICATION");
        prepared.putObject("gene").put("symbol", "TPM1").put("hgncId", "HGNC:12010");
        prepared.putObject("disease").put("mondoId", "MONDO:0005045"); prepared.putObject("patientContext").put("patientContextId", "wire-only");
        prepared.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "15").put("position", 1).put("reference", "A").put("alternate", "G");
        prepared.putObject("transcript").put("accession", "ENST00000403994").put("version", 9);
        var execution = prepared.deepCopy(); execution.putObject("transcript").put("accession", "NM_001018005").put("version", 2);
        assertTrue(GeneralPp2Runtime.hcmContextsMatch(selected, prepared, execution));
        for (String change : List.of("GENE_SYMBOL", "HGNC", "ALLELE", "PATIENT", "TX", "DISEASE")) {
            var altered = execution.deepCopy();
            switch (change) {
                case "GENE_SYMBOL" -> ((ObjectNode) altered.path("gene")).put("symbol", "HNF1A");
                case "HGNC" -> ((ObjectNode) altered.path("gene")).put("hgncId", "HGNC:5024");
                case "ALLELE" -> ((ObjectNode) altered.at("/variant/evidenceQueryAllele")).put("position", 2);
                case "PATIENT" -> ((ObjectNode) altered.path("patientContext")).put("patientContextId", "other");
                case "TX" -> ((ObjectNode) altered.path("transcript")).put("version", 3);
                case "DISEASE" -> ((ObjectNode) altered.path("disease")).put("mondoId", "MONDO:0015967");
                default -> fail(change);
            }
            assertFalse(GeneralPp2Runtime.hcmContextsMatch(selected, prepared, altered), change);
        }
    }
    @Test void actualHcmBackgroundNeedIsMetadataOnlyAndDoesNotRequireASelectedTranscriptOrReadSources() throws Exception {
        var fixture = fixture(); var calls = new AtomicInteger();
        var runtime = new GeneralPp2Runtime(mapper, fixture.material, hcmKnowledge(), true).withOtherPhenotypeSource(source(calls));
        var context = mapper.createObjectNode().put("moi", "AUTOSOMAL_DOMINANT");
        context.putObject("gene").put("hgncId", "HGNC:12010"); context.putObject("disease").put("mondoId", "MONDO:0005045");
        context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38");
        assertTrue(runtime.requiresOtherPhenotypeSource(context));
        for (String change : List.of("GENE", "DISEASE", "ASSEMBLY", "MOI")) {
            var altered = context.deepCopy();
            switch (change) {
                case "GENE" -> ((ObjectNode) altered.path("gene")).put("hgncId", "HGNC:5024");
                case "DISEASE" -> ((ObjectNode) altered.path("disease")).put("mondoId", "MONDO:0015967");
                case "ASSEMBLY" -> ((ObjectNode) altered.at("/variant/evidenceQueryAllele")).put("assembly", "GRCh37");
                case "MOI" -> altered.put("moi", "AUTOSOMAL_RECESSIVE");
                default -> fail(change);
            }
            assertFalse(runtime.requiresOtherPhenotypeSource(altered), change);
        }
        runtime.identity(); assertEquals(0, calls.get()); assertEquals(0, runtime.evaluations());
        assertFalse(Files.exists(root.resolve("source-work")));
    }
}
