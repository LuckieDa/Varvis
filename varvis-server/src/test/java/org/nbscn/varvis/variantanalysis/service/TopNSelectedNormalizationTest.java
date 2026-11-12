package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.service.impl.BcftoolsVcfNormalizer;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;

/** Real installed BCFtools on synthetic reference; production source scan/parser, no Exomiser process. */
class TopNSelectedNormalizationTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper();
    record Fixture(NativeSnvSourceProducer producer, NativeSnvReference reference, VariantAnalysisProperties properties) { }
    Fixture fixture(String sequence) throws Exception {
        var p = new VariantAnalysisProperties();
        Path fasta = Files.writeString(temporary.resolve("reference.fa"), ">chr11\n" + sequence + "\n>chr13\nAACT\n");
        Path index = Files.writeString(Path.of(fasta + ".fai"), "chr11\t" + sequence.length() + "\t7\t" + sequence.length() + "\t" + (sequence.length()+1)
                + "\nchr13\t4\t" + (sequence.length()+15) + "\t4\t5\n");
        String hash = sha(Files.readAllBytes(fasta));
        var receipt = mapper.createObjectNode().put("resourceId", "SYNTHETIC_N01").put("resourceVersion", "1")
                .put("assembly", "GRCh38").put("logicalName", "reference.fa").put("expectedSha256", hash).put("observedSha256", hash)
                .put("hashStatus", "VERIFIED").put("hashAlgorithm", "SHA-256").put("hashScope", "RAW_BYTES").put("unchangedDuringHash", true)
                .put("byteLength", Files.size(fasta)).put("mtimeNs", Files.getLastModifiedTime(fasta).to(java.util.concurrent.TimeUnit.NANOSECONDS))
                .put("startedAt", "2026-09-01T00:00:00Z").put("completedAt", "2026-09-01T00:00:01Z")
                .put("indexSha256", sha(Files.readAllBytes(index)));
        var envelope = mapper.createObjectNode().put("source", "N01_SYNTHETIC_REFERENCE").put("version", "1").put("dataClass", "SYNTHETIC");
        envelope.putObject("records").putObject("checksum").set("actualChecksumReceipt", receipt);
        var ledger = ExplicitContextSourceLedger.create(mapper, envelope);
        p.getNormalization().setMode(VariantAnalysisProperties.Mode.BCFTOOLS);
        p.getNormalization().setBcftoolsExecutable(System.getProperty("varvis.n01.bcftools", "MISSING_N01_TOOL"));
        p.getNormalization().setGrch38Reference(fasta.toString()); p.getNormalization().setGrch38ReferenceSha256(hash);
        p.getNormalization().setGrch38ProfileId("SYNTHETIC_N01");
        var validator = new NormalizationResourceValidator(p) {
            @Override public NormalizationReferenceReadiness validate(GenomeAssembly assembly) {
                return new NormalizationReferenceReadiness(assembly, "READY", "SYNTHETIC_N01", "reference.fa", hash,
                        true, true, 2, "CHR_PREFIXED", List.of(), List.of("SYNTHETIC_READINESS_ONLY"));
            }
        };
        var producer = new NativeSnvSourceProducer(mapper, p, temporary, List.of(ledger), ledger.reference("checksum"), validator);
        var reference = new NativeSnvReference(mapper, p, validator, temporary, List.of(ledger), ledger.reference("checksum"));
        var normalizer = new BcftoolsVcfNormalizer(p);
        ReflectionTestUtils.setField(normalizer, "resourceValidator", validator);
        ReflectionTestUtils.setField(producer, "selectedNormalizer", new SelectedAlleleNormalizer(mapper, normalizer));
        return new Fixture(producer, reference, p);
    }
    ParsedCandidateFixture.Run run(int pos, String ref, String alts, String target, String gt) throws Exception {
        String input = vcf("chr11\t" + pos + "\t.\t" + ref + "\t" + alts + "\t.\tPASS\t.\tGT:AD\t" + gt + ":10,8,7")
                .replace("#CHROM", "##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"Depths\">\n#CHROM");
        return ParsedCandidateFixture.run(Files.createTempDirectory(temporary, "run-"), UUID.randomUUID().toString(), GenomeAssembly.GRCh38,
                input, tsv("1\t11\t" + pos + "\t" + ref + "\t" + target + "\tHBB\t3043\tAR\t0.8"));
    }
    NativeSnvSourceProducer.Production produce(Fixture f, ParsedCandidateFixture.Run run) {
        return f.producer.produceBa1Allele(run.job(), run.conversion(), run.execution(),
                new AnalysisContext(run.job().getId(), GenomeAssembly.GRCh38, AnalysisMode.WES), run.execution().getInterpretations().get(0), "SYNTHETIC");
    }
    void archive(String name, Object data) throws Exception {
        String directory = System.getProperty("varvis.n01.evidence");
        if (directory != null) mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(directory).resolve(name + ".json").toFile(), data);
    }
    @ParameterizedTest @CsvSource({"2,TC,TA,TA,0|1,3,C,A", "2,T,TT,TT,0/1,1,A,AT",
            "2,TC,AG,AG,0|1,2,TC,AG", "1,ATC,AAG,AAG,0/1,2,TC,AG",
            "2,TC,TA|TG,TA,1|2,3,C,A", "2,TC,TA|TG,TG,1/2,3,C,G"})
    void realToolPreservesAllelesAndSource(int pos, String ref, String alts, String target, String gt,
                                        int expectedPos, String expectedRef, String expectedAlt) throws Exception {
        Assumptions.assumeTrue(System.getProperty("varvis.n01.bcftools") != null);
        var f = fixture("ATCG"); var run = run(pos, ref, alts.replace('|', ','), target, gt);
        var candidate = run.execution().getInterpretations().get(0); var before = mapper.valueToTree(candidate);
        var produced = produce(f, run); var input = produced.inputs().get(0);
        assertTrue(produced.blockers(input).isEmpty(), produced.provenance().toString());
        assertEquals(expectedPos, input.normalization().normalizedAllele().position());
        assertEquals(expectedRef, input.normalization().normalizedAllele().reference());
        assertEquals(expectedAlt, input.normalization().normalizedAllele().alternate());
        assertEquals(before, mapper.valueToTree(candidate));
        assertEquals(candidate.getAnalysisVcfCall().getTargetAlleleIndex(), input.normalization().selectedAltIndex());
        var proof = produced.resolve(input.normalization().source());
        if (SelectedAlleleNormalizer.METHOD.equals(input.normalization().method())) {
            assertEquals(mapper.valueToTree(candidate.getAnalysisVcfCall()), proof.path("analysisVcfCall"));
            assertEquals(mapper.valueToTree(candidate.getObservation()), proof.path("sampleObservation"));
            produced.resolve(input.normalization().reference()); produced.resolve(input.normalization().lineage().source());
        }
        archive("success-" + pos + "-" + ref + "-" + target, produced.provenance());
    }
    @Test void longRepeatAndEquivalentRepresentations() throws Exception {
        Assumptions.assumeTrue(System.getProperty("varvis.n01.bcftools") != null);
        var f = fixture("C" + "A".repeat(120) + "G");
        var a = produce(f, run(110, "A", "AA", "AA", "0|1"));
        var b = produce(f, run(50, "A", "AA", "AA", "0/1"));
        assertTrue(a.blockers(a.inputs().get(0)).isEmpty(), a.provenance().toString());
        assertTrue(b.blockers(b.inputs().get(0)).isEmpty(), b.provenance().toString());
        var n = a.inputs().get(0).normalization();
        assertEquals(1, n.normalizedAllele().position()); assertEquals("C", n.normalizedAllele().reference()); assertEquals("CA", n.normalizedAllele().alternate());
        assertEquals(n.normalizedAllele(), b.inputs().get(0).normalization().normalizedAllele());
        assertNotEquals(n.source(), b.inputs().get(0).normalization().source());
        archive("long-repeat", a.provenance());
    }
    @Test void realToolRenamesSelectedContigFromValidHeader() throws Exception {
        Assumptions.assumeTrue(System.getProperty("varvis.n01.bcftools") != null);
        var f = fixture("ATCG");
        Path aliases = Files.writeString(temporary.resolve("aliases.tsv"), "11\tchr11\n13\tchr13\n");
        f.properties.getNormalization().setGrch38ContigAliasFile(aliases.toString());
        var run = ParsedCandidateFixture.run(Files.createTempDirectory(temporary, "unprefixed-"), UUID.randomUUID().toString(), GenomeAssembly.GRCh38,
                vcf("11\t2\t.\tT\tTT\t.\tPASS\t.\tGT\t0/1"), tsv("1\t11\t2\tT\tTT\tHBB\t3043\tAR\t0.8"));
        var result = produce(f, run); var input = result.inputs().get(0);
        archive("contig-alias-header", result.provenance());
        assertTrue(result.blockers(input).isEmpty(), result.provenance().toString());
        assertEquals(1, input.normalization().normalizedAllele().position());
        assertEquals("A", input.normalization().normalizedAllele().reference());
        assertEquals("AT", input.normalization().normalizedAllele().alternate());
        assertEquals("11", input.normalization().normalizedAllele().contig());
    }
    @ParameterizedTest @CsvSource({"COPY_ONLY,NORMALIZATION_NOT_REFERENCE_APPLIED", "MISSING,NORMALIZATION_TOOL_UNAVAILABLE", "REF,REF_VALIDATION_NOT_MATCH"})
    void failuresDoNotProduceProof(String mode, String reason) throws Exception {
        var f = fixture(mode.equals("REF") ? "AACG" : "ATCG");
        if (mode.equals("COPY_ONLY")) f.properties.getNormalization().setMode(VariantAnalysisProperties.Mode.COPY_ONLY);
        if (mode.equals("MISSING")) f.properties.getNormalization().setBcftoolsExecutable(temporary.resolve("absent.exe").toString());
        var produced = produce(f, run(2, "T", "TT", "TT", "0/1"));
        assertEquals(reason, produced.blockers(produced.inputs().get(0)).get(0).reasonCode());
        assertNull(produced.inputs().get(0).normalization());
    }

    @ParameterizedTest @CsvSource({"EMPTY,NORMALIZATION_OUTPUT_EMPTY", "AMBIGUOUS,NORMALIZATION_OUTPUT_ASSOCIATION_AMBIGUOUS",
            "WRONG_ID,NORMALIZATION_OUTPUT_ASSOCIATION_AMBIGUOUS", "REF,NORMALIZATION_OUTPUT_REF_MISMATCH",
            "CHANGE,NORMALIZATION_NON_EQUIVALENT_ALLELE", "NOT_MINIMAL,NORMALIZATION_OUTPUT_NOT_MINIMAL",
            "TIMEOUT,NORMALIZATION_TOOL_TIMEOUT", "NONZERO,NORMALIZATION_TOOL_FAILED", "MISSING,NORMALIZATION_OUTPUT_MISSING_OR_UNBOUNDED"})
    void invalidToolOutputsNeverBecomeProof(String mode, String expected) throws Exception {
        var f = fixture("ATCG");
        VcfNormalizer invalid = (input, work, assembly) -> {
            if (mode.equals("TIMEOUT")) throw new IllegalStateException("bcftools command timed out");
            if (mode.equals("NONZERO")) throw new IllegalStateException("bcftools command failed with exit code 7");
            String record = "chr11\t1\tVARVIS_SELECTED_ALT\tA\tAT\t.\tPASS\t.\n";
            if (mode.equals("EMPTY")) record = "";
            if (mode.equals("AMBIGUOUS")) record += record;
            if (mode.equals("WRONG_ID")) record = record.replace("VARVIS_SELECTED_ALT", "UNRELATED");
            if (mode.equals("REF")) record = "chr11\t2\tVARVIS_SELECTED_ALT\tC\tCA\t.\tPASS\t.\n";
            if (mode.equals("CHANGE")) record = "chr11\t3\tVARVIS_SELECTED_ALT\tC\tG\t.\tPASS\t.\n";
            if (mode.equals("NOT_MINIMAL")) record = "chr11\t2\tVARVIS_SELECTED_ALT\tT\tTT\t.\tPASS\t.\n";
            Path output = work.resolve("mock-output.vcf");
            if (!mode.equals("MISSING")) Files.writeString(output, "##fileformat=VCFv4.2\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n" + record);
            var manifest = new NormalizationRunManifest("PASSED", "GRCh38", "SYNTHETIC", f.properties.getNormalization().referenceSha256For(GenomeAssembly.GRCh38),
                    "bcftools", "SYNTHETIC_FAILURE_TEST", "SYNTHETIC", false, 1, 1, 0, 1, 0, 1, "2026-10-07T00:00:00Z", "2026-10-07T00:00:01Z", null, null, "SYNTHETIC");
            return new NormalizationResult(output, true, "SYNTHETIC", List.of(), manifest);
        };
        ReflectionTestUtils.setField(requireNonNull(f.producer), "selectedNormalizer", new SelectedAlleleNormalizer(mapper, invalid));
        var result = produce(f, run(2, "T", "TT", "TT", "0/1"));
        assertEquals(expected, result.blockers(result.inputs().get(0)).get(0).reasonCode());
        assertNull(result.inputs().get(0).normalization()); archive("invalid-tool-" + mode, result.provenance());
    }

    @Test void frozenLengthBoundaryIncludesAnchorAndAllowsTrimmingLongInput() throws Exception {
        Assumptions.assumeTrue(System.getProperty("varvis.n01.bcftools") != null);
        var f = fixture("C" + "A".repeat(120) + "G");
        var inScope = produce(f, run(1, "C", "C" + "A".repeat(49), "C" + "A".repeat(49), "0/1"));
        assertTrue(inScope.blockers(inScope.inputs().get(0)).isEmpty());
        var out = produce(f, run(1, "C", "C" + "A".repeat(50), "C" + "A".repeat(50), "0/1"));
        assertEquals("NORMALIZED_ALLELE_OUTSIDE_FROZEN_SCOPE", out.blockers(out.inputs().get(0)).get(0).reasonCode());
        String original = "A".repeat(60), alternate = "A".repeat(29) + "T" + "A".repeat(30);
        var trimmed = produce(f, run(2, original, alternate, alternate, "0/1"));
        assertTrue(trimmed.blockers(trimmed.inputs().get(0)).isEmpty(), trimmed.provenance().toString());
        assertEquals(31, trimmed.inputs().get(0).normalization().normalizedAllele().position());
        assertEquals("A", trimmed.inputs().get(0).normalization().normalizedAllele().reference());
        assertEquals("T", trimmed.inputs().get(0).normalization().normalizedAllele().alternate());
    }

    @Test void convertedProofIsCheckedBeforeSecondNormalization() throws Exception {
        Assumptions.assumeTrue(System.getProperty("varvis.n01.bcftools") != null);
        var f = fixture("ATCG");
        var oldReference = new ConvertedAlleleSourceTest(); oldReference.root = temporary;
        var original = oldReference.reference(GenomeAssembly.GRCh37, "ATCG", true);
        ReflectionTestUtils.setField(requireNonNull(f.producer), "originalReference", original.query());
        var run = ParsedCandidateFixture.run(temporary.resolve("converted"), "converted-n01", GenomeAssembly.GRCh37,
                vcf("chr11\t2\t.\tTC\tTA\t.\tPASS\t.\tGT\t0|1"), tsv("1\t11\t2\tTC\tTA\tHBB\t3043\tAR\t0.8"));
        var candidate = run.execution().getInterpretations().get(0);
        var proof = mapper.createObjectNode().put("version", org.nbscn.varvis.variantanalysis.engine.ConversionSourceProof.VERSION)
                .put("inputAssembly", "GRCh37").put("analysisAssembly", "GRCh38")
                .put("sourceReferenceSha256", original.sha()).put("targetReferenceSha256", f.properties.getNormalization().referenceSha256For(GenomeAssembly.GRCh38));
        proof.set("originalArtifact", org.nbscn.varvis.variantanalysis.engine.ConversionSourceProof.artifact(mapper, run.job().getUploadedFile()));
        proof.set("analysisArtifact", org.nbscn.varvis.variantanalysis.engine.ConversionSourceProof.artifact(mapper, run.conversion().getOutputFile()));
        var row = proof.putObject("candidates").putObject(candidate.getCandidateId()).put("status", "ASSOCIATED_PENDING_REFERENCE_VALIDATION")
                .put("originalRecordOrdinal", 1).put("analysisRecordOrdinal", 1).put("originalAltIndex", 1).put("analysisAltIndex", 1)
                .put("strand", "+").put("originalRecord", "chr11|2|TC|TA|1").put("genotypeAssociation", "EXACT_ALLELE_INDEX_AND_PHASE_MATCH");
        row.putObject("genotype").putArray("alleleIndexes").add(0).add(1);
        for (String name : List.of("originalAllele", "mappedAllele", "analysisAllele")) row.putObject(name)
                .put("assembly", name.equals("originalAllele") ? "GRCh37" : "GRCh38").put("contig", "chr11").put("position", 2).put("reference", "TC").put("alternate", "TA");
        var conversion = new BuildConversionResult(run.conversion().getOutputFile(), true, "SYNTHETIC_PROOF", List.of(), null, 0, 1, 1, proof);
        var analysis = new AnalysisContext(run.job().getId(), GenomeAssembly.GRCh38, AnalysisMode.WES);
        var result = f.producer.produceBa1Allele(run.job(), conversion, run.execution(), analysis, candidate, "SYNTHETIC");
        assertTrue(result.blockers(result.inputs().get(0)).isEmpty(), result.provenance().toString());
        var n = result.inputs().get(0).normalization(); assertEquals("GRCh37", n.originalAllele().assembly());
        assertEquals("GRCh38", n.normalizedAllele().assembly()); assertEquals(3, n.normalizedAllele().position());
        assertEquals("MAPPED", n.lineage().preRankingMappingStatus());
        assertEquals("VERIFIED", result.resolve(n.source()).at("/conversionEvidence/status").asText());
        archive("converted-normalization", result.provenance());
        var missing = f.producer.produceBa1Allele(run.job(), run.conversion(), run.execution(), analysis, candidate, "SYNTHETIC");
        assertFalse(missing.blockers(missing.inputs().get(0)).isEmpty());
        ((ObjectNode) row.path("analysisAllele")).put("alternate", "TG");
        var bad = new BuildConversionResult(run.conversion().getOutputFile(), true, "SYNTHETIC_PROOF", List.of(), null, 0, 1, 1, proof);
        var mismatch = f.producer.produceBa1Allele(run.job(), bad, run.execution(), analysis, candidate, "SYNTHETIC");
        assertFalse(mismatch.blockers(mismatch.inputs().get(0)).isEmpty());
    }

    @Test void timeoutTerminatesOwnedChild() throws Exception {
        String python = System.getProperty("varvis.n01.python"); Assumptions.assumeTrue(python != null);
        var f = fixture("ATCG"); Path marker = temporary.resolve("owned-pid.txt");
        Path command = Files.writeString(temporary.resolve("synthetic-sleep.cmd"), "@echo off\r\n\"" + python
                + "\" -c \"import os,time,pathlib;pathlib.Path(r'" + marker + "').write_text(str(os.getpid()));time.sleep(30)\"\r\n");
        f.properties.getNormalization().setBcftoolsExecutable(command.toString());
        f.properties.getNormalization().setTimeout(java.time.Duration.ofSeconds(2));
        var result = produce(f, run(2, "T", "TT", "TT", "0/1"));
        assertEquals("NORMALIZATION_TOOL_TIMEOUT", result.blockers(result.inputs().get(0)).get(0).reasonCode());
        long pid = Long.parseLong(Files.readString(marker));
        assertFalse(ProcessHandle.of(pid).map(mappedItem -> mappedItem.isAlive()).orElse(false));
        archive("timeout-child-exit", mapper.createObjectNode().put("pid", pid).put("alive", false)
                .put("transport", "SYNTHETIC_SLEEP_COMMAND_ONLY").set("production", result.provenance()));
    }

    @Test void exhaustedReferenceSpanIsTechnicalBlockerNotPartialNormalization() throws Exception {
        Assumptions.assumeTrue(System.getProperty("varvis.n01.bcftools") != null);
        var f = fixture("C" + "A".repeat(100_010) + "G");
        var result = produce(f, run(100_005, "A", "AA", "AA", "0/1"));
        assertEquals("NORMALIZATION_EQUIVALENCE_SPAN_LIMIT", result.blockers(result.inputs().get(0)).get(0).reasonCode());
        assertNull(result.inputs().get(0).normalization()); archive("technical-span-limit", result.provenance());
    }
}
