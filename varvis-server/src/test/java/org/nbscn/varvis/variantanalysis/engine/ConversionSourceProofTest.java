package org.nbscn.varvis.variantanalysis.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;

import java.nio.file.*;
import java.util.List;
import java.util.jar.*;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic chain and TSV transport; actual parser, record scan, chain mapping and source verification. */
class ConversionSourceProofTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    record Case(ParsedCandidateFixture.Prepared prepared, EngineExecutionResult execution, BuildConversionResult conversion) { }

    Case prepare(String firstTarget, String chainExtra) throws Exception {
        String original = ParsedCandidateFixture.vcf("11\t2\tfirst\tT\tA\t.\tPASS\t.\tGT\t0/1",
                "11\t3\tsecond\tC\tG\t.\tPASS\t.\tGT\t0/1",
                "11\t4\tunmapped\tA\tG\t.\tPASS\t.\tGT\t0/1").replace("##reference=GRCh38", "##reference=GRCh37");
        var prepared = ParsedCandidateFixture.prepare(root, "synthetic-conversion-proof", GenomeAssembly.GRCh37, original,
                ParsedCandidateFixture.tsv("1\t11\t102\tT\tA\tHBB\t3043\tAR\t0.8", "2\t11\t103\tC\tG\tHBB\t3043\tAR\t0.7"), null, false);
        Path target = prepared.conversion().getOutputFile();
        String targetVcf = ParsedCandidateFixture.vcf(firstTarget,
                "chr11\t103\tsecond\tC\tG\t.\tPASS\tVARVIS_UPLOAD_RECORD=2\tGT\t0/1")
                .replace("#CHROM", "##INFO=<ID=SwappedAlleles,Number=0,Type=Flag,Description=\"Picard swapped alleles\">\n##INFO=<ID=VARVIS_UPLOAD_RECORD,Number=1,Type=Integer,Description=\"Synthetic record association\">\n#CHROM");
        Files.writeString(target, targetVcf);
        var settings = new VariantAnalysisProperties();
        Path chain = root.resolve("synthetic.chain");
        Files.writeString(chain, "chain 100 chr11 1000 + 0 1000 chr11 2000 + 100 1100 1\n1000\n\n" + chainExtra);
        settings.getLiftOver().setHg19ToHg38Chain(chain.toString());
        Path jar = root.resolve("synthetic-not-executed.jar");
        Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Implementation-Version", "SYNTHETIC-NOT-PICARD");
        try (var ignored = new JarOutputStream(Files.newOutputStream(jar), manifest)) { }
        settings.getLiftOver().setPicardJar(jar.toString());
        settings.getNormalization().setGrch37ReferenceSha256("a".repeat(64));
        settings.getNormalization().setGrch38ReferenceSha256("b".repeat(64));
        Path rejected = Files.writeString(root.resolve("rejected.vcf"), ParsedCandidateFixture.vcf(
                "11\t4\tunmapped\tA\tG\t.\tNoTarget\tVARVIS_UPLOAD_RECORD=3\tGT\t0/1")
                .replace("#CHROM", "##FILTER=<ID=NoTarget,Description=\"Synthetic unmapped record\">\n##INFO=<ID=VARVIS_UPLOAD_RECORD,Number=1,Type=Integer,Description=\"Record\">\n#CHROM"));
        Path tagged = ConversionSourceProof.tagInput(prepared.job().getUploadedFile(), root);
        var proof = ConversionSourceProof.completed(mapper, ConversionSourceProof.artifact(mapper, prepared.job().getUploadedFile()),
                prepared.job().getUploadedFile(), tagged, target, rejected, settings);
        var conversion = new BuildConversionResult(target, true, "SYNTHETIC_CHAIN_NOT_PICARD", List.of(), rejected, 1, 3L, 2L, proof);
        var execution = prepared.adapter().prepare(prepared.job(), target, GenomeAssembly.GRCh38);
        return new Case(prepared, execution, conversion);
    }

    ObjectNode publish(Case value) throws Exception {
        return ConversionSourceProof.publish(mapper, value.prepared().job(), value.conversion(), value.execution(), value.execution().getInterpretations());
    }

    @Test void associatesExactOriginalRecordAltAndGenotypeWithoutClaimingReferenceValidation() throws Exception {
        var value = prepare("chr11\t102\tfirst\tT\tA\t.\tPASS\tVARVIS_UPLOAD_RECORD=1\tGT\t0/1", "");
        var proof = publish(value);
        assertEquals(2, proof.path("candidates").size());
        assertEquals(1, proof.at("/rejectedRecords/reasonCounts/CONVERSION_UNMAPPED").asInt());
        assertEquals(3, proof.at("/rejectedRecords/records/0/originalRecordOrdinal").asInt());
        var row = proof.path("candidates").get(value.execution().getInterpretations().get(0).getCandidateId());
        assertEquals("ASSOCIATED_PENDING_REFERENCE_VALIDATION", row.path("status").asText());
        assertEquals(1, row.path("originalRecordOrdinal").asInt());
        assertEquals(1, row.path("originalAltIndex").asInt());
        assertEquals(2, row.at("/originalAllele/position").asInt());
        assertEquals(102, row.at("/mappedAllele/position").asInt());
        assertEquals("GRCh37", row.at("/originalAllele/assembly").asText());
        assertNotEquals(proof.at("/originalArtifact/sha256"), proof.at("/analysisArtifact/sha256"));
        ObjectNode copy = value.conversion().getSourceProof(); assertNotNull(copy);
        copy.put("version", "tampered");
        assertEquals(ConversionSourceProof.VERSION, value.conversion().getSourceProof().path("version").asText());
        var restoredProof = mapper.readTree(mapper.writeValueAsBytes(proof));
        assertDoesNotThrow(() -> ConversionSourceProof.verifyTask(restoredProof, value.prepared().job(),
                value.conversion().getOutputFile(), value.execution().getInputSource().observedVcfSha256()));
    }

    @ParameterizedTest @ValueSource(strings = {"missingTag", "unknownRecord", "badGenotype", "swapped"})
    void invalidCandidateDoesNotPoisonValidSibling(String mode) throws Exception {
        String info = mode.equals("missingTag") ? "." : "VARVIS_UPLOAD_RECORD=" + (mode.equals("unknownRecord") ? "99" : "1");
        String genotype = mode.equals("badGenotype") ? "1/1" : "0/1";
        if (mode.equals("swapped")) info += ";SwappedAlleles";
        var value = prepare("chr11\t102\tfirst\tT\tA\t.\tPASS\t" + info + "\tGT\t" + genotype, "");
        var proof = publish(value); int blocked = 0, associated = 0;
        for (var row : proof.path("candidates")) {
            if ("BLOCKED".equals(row.path("status").asText())) { blocked++; assertFalse(row.path("reasonCode").asText().isBlank()); }
            if ("ASSOCIATED_PENDING_REFERENCE_VALIDATION".equals(row.path("status").asText())) associated++;
        }
        assertEquals(1, blocked); assertEquals(1, associated);
    }

    @Test void multipleCompleteMappingsAreExplicitlyBlocked() throws Exception {
        var value = prepare("chr11\t102\tfirst\tT\tA\t.\tPASS\tVARVIS_UPLOAD_RECORD=1\tGT\t0/1",
                "chain 100 chr11 1000 + 0 1000 chr11 2000 + 200 1200 2\n1000\n\n");
        for (var row : publish(value).path("candidates")) assertEquals("CONVERSION_MAPPING_AMBIGUOUS", row.path("reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"version", "originalSha", "targetSha", "changedOriginal"})
    void incompatibleOrChangedGlobalSourceCannotBeIgnored(String mode) throws Exception {
        var value = prepare("chr11\t102\tfirst\tT\tA\t.\tPASS\tVARVIS_UPLOAD_RECORD=1\tGT\t0/1", "");
        var proof = value.conversion().getSourceProof(); assertNotNull(proof);
        if (mode.equals("version")) proof.put("version", "old-unproven");
        if (mode.equals("originalSha")) ((ObjectNode) proof.path("originalArtifact")).put("sha256", "c".repeat(64));
        if (mode.equals("targetSha")) ((ObjectNode) proof.path("analysisArtifact")).put("sha256", "c".repeat(64));
        if (mode.equals("changedOriginal")) Files.writeString(value.prepared().job().getUploadedFile(), "changed", StandardOpenOption.APPEND);
        assertThrows(IllegalArgumentException.class, () -> ConversionSourceProof.verifyTask(proof, value.prepared().job(),
                value.conversion().getOutputFile(), value.execution().getInputSource().observedVcfSha256()));
    }

    @Test void reservedRecordTagCannotBeInjectedByAnUpload() throws Exception {
        Path input = Files.writeString(root.resolve("injected.vcf"), ParsedCandidateFixture.vcf("11\t2\t.\tT\tA\t.\tPASS\tVARVIS_UPLOAD_RECORD=5\tGT\t0/1"));
        assertEquals("CONVERSION_RESERVED_RECORD_TAG", assertThrows(IllegalArgumentException.class,
                () -> ConversionSourceProof.tagInput(input, root)).getMessage());
    }
}
