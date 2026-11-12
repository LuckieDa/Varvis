package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import htsjdk.samtools.reference.IndexedFastaSequenceFile;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;

/** Opt-in real pinned reference and actual installed tool; sequence-derived input remains SYNTHETIC. */
class TopNNormalizationRealReferenceTest {
    @TempDir Path temporary;
    @Test void realReferenceTrimmingHasPreFrozenExpectedAllele() throws Exception {
        String root = System.getProperty("varvis.n01.resourceRoot"); Assumptions.assumeTrue(root != null);
        Path receiptFile = Path.of(System.getProperty("varvis.n01.receipt"));
        var mapper = new ObjectMapper();
        var ledger = ExplicitContextSourceLedger.load(mapper, receiptFile, sha(Files.readAllBytes(receiptFile)));
        var receipt = ledger.resolve(ledger.reference("reference")).path("actualChecksumReceipt");
        Path fasta = Path.of(root).resolve(receipt.path("logicalName").asText());
        String sequence;
        try (var ref = new IndexedFastaSequenceFile(fasta)) {
            sequence = new String(ref.getSubsequenceAt("chr1", 1_000_000, 1_000_002).getBases(), java.nio.charset.StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
        }
        assertTrue(sequence.matches("[ACGT]{3}"));
        String change = sequence.charAt(1) == 'A' ? "C" : "A";
        String alternate = sequence.substring(0, 1) + change + sequence.substring(2);
        var expected = mapper.createObjectNode().put("source", "REAL_INDEXED_REFERENCE_PLUS_HAND_CHECKABLE_TRIM")
                .put("referenceSequence", sequence).put("inputPosition", 1_000_000).put("inputAlt", alternate)
                .put("expectedPosition", 1_000_001).put("expectedRef", sequence.substring(1, 2)).put("expectedAlt", change);
        Path evidence = Path.of(System.getProperty("varvis.n01.evidence"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("real-reference-expected-before-run.json").toFile(), expected);
        var p = new VariantAnalysisProperties(); p.getNormalization().setMode(VariantAnalysisProperties.Mode.BCFTOOLS);
        p.getNormalization().setBcftoolsExecutable(System.getProperty("varvis.n01.bcftools"));
        p.getNormalization().setGrch38Reference(fasta.toString()); p.getNormalization().setGrch38ReferenceSha256(receipt.path("observedSha256").asText());
        p.getNormalization().setGrch38ProfileId("grch38-primary-chr");
        var producer = new NativeSnvSourceProducer(mapper, p, Path.of(root), List.of(ledger), ledger.reference("reference"));
        var run = ParsedCandidateFixture.run(temporary, "synthetic-real-reference-n01", GenomeAssembly.GRCh38,
                vcf("chr1\t1000000\t.\t" + sequence + "\t" + alternate + "\t.\tPASS\t.\tGT\t0/1"),
                tsv("1\t1\t1000000\t" + sequence + "\t" + alternate + "\tHBB\t3043\tAR\t0.8"));
        var result = producer.produceBa1Allele(run.job(), run.conversion(), run.execution(),
                new AnalysisContext(run.job().getId(), GenomeAssembly.GRCh38, AnalysisMode.WES), run.execution().getInterpretations().get(0), "SYNTHETIC");
        mapper.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("real-reference-production.json").toFile(), result.provenance());
        if (result.blockers(result.inputs().get(0)).isEmpty()) {
            var allele = result.inputs().get(0).normalization().normalizedAllele();
            assertEquals(1_000_001, allele.position()); assertEquals(sequence.substring(1, 2), allele.reference()); assertEquals(change, allele.alternate());
        } else {
            // Do not alter a stale production receipt or equate tool success with an accepted source proof.
            assertEquals("REFERENCE_CHECKSUM_NOT_APPLICABLE", result.blockers(result.inputs().get(0)).get(0).reasonCode());
        }
        var receipts = mapper.createArrayNode(); result.sources().forEach(s -> receipts.add(s.receipt(mapper)));
        mapper.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("real-reference-ledgers.json").toFile(), receipts);
        Path direct = Files.createDirectory(temporary.resolve("direct-real-tool"));
        var normalized = new org.nbscn.varvis.variantanalysis.service.impl.BcftoolsVcfNormalizer(p)
                .normalize(run.job().getUploadedFile(), direct, GenomeAssembly.GRCh38);
        assertTrue(normalized.isNormalizationApplied());
        try (var reader = new htsjdk.variant.vcf.VCFFileReader(normalized.getOutputFile(), false); var iterator = reader.iterator()) {
            var row = iterator.next(); assertFalse(iterator.hasNext()); assertEquals(1_000_001, row.getStart());
            assertEquals(sequence.substring(1, 2), row.getReference().getDisplayString()); assertEquals(change, row.getAlternateAllele(0).getDisplayString());
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("real-tool-manifest.json").toFile(), normalized.getManifest());
        Files.copy(run.job().getUploadedFile(), evidence.resolve("real-tool-input.vcf"), StandardCopyOption.REPLACE_EXISTING);
        try (var files = Files.walk(direct)) {
            for (var file : files.filter(Files::isRegularFile).toList())
                Files.copy(file, evidence.resolve("real-tool-" + file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
