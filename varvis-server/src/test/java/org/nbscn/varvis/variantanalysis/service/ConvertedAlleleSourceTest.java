package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.*;
import org.nbscn.varvis.variantanalysis.provider.*;

import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.sha;

/** Bounded real FASTA queries on synthetic sequence/proofs; no real-resource or Picard acceptance claim. */
class ConvertedAlleleSourceTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    record Reference(NativeSnvReference query, String sha) { }

    Reference reference(GenomeAssembly assembly, String sequence, boolean useManifest) throws Exception {
        String name = assembly.name() + ".fa";
        Path fasta = Files.writeString(root.resolve(name), ">chr11\n" + sequence + "\n");
        Path index = Files.writeString(Path.of(fasta + ".fai"), "chr11\t" + sequence.length() + "\t7\t" + sequence.length() + "\t" + (sequence.length() + 1) + "\n");
        String checksum = sha(Files.readAllBytes(fasta)), indexSha = sha(Files.readAllBytes(index));
        var properties = new VariantAnalysisProperties();
        if (assembly == GenomeAssembly.GRCh37) {
            properties.getNormalization().setGrch37Reference(fasta.toString());
            properties.getNormalization().setGrch37ReferenceSha256(checksum);
            properties.getNormalization().setGrch37ProfileId("SYNTHETIC_REFERENCE");
        } else {
            properties.getNormalization().setGrch38Reference(fasta.toString());
            properties.getNormalization().setGrch38ReferenceSha256(checksum);
            properties.getNormalization().setGrch38ProfileId("SYNTHETIC_REFERENCE");
        }
        var record = mapper.createObjectNode();
        long modified = Files.getLastModifiedTime(fasta).to(TimeUnit.NANOSECONDS);
        if (useManifest) {
            var manifest = mapper.createObjectNode().put("schemaVersion", "varvis-reference-resource-manifest-v1")
                    .put("assembly", "GRCh37.p13").put("validationStatus", "CHECKSUM_INDEX_AND_SYNTHETIC_SMOKE_VALIDATED")
                    .put("generatedAtUtc", Instant.now().minusSeconds(2).toString());
            var files = manifest.putObject("files");
            files.putObject("fasta").put("name", name).put("bytes", Files.size(fasta)).put("sha256", checksum);
            files.putObject("fai").put("name", name + ".fai").put("sha256", indexSha);
            manifest.putObject("validation").put("expandedSha256", "PASS").put("faiIdentity", "PASS");
            String raw = mapper.writeValueAsString(manifest);
            record.putObject("acceptedReferenceManifest").put("rawUtf8", raw)
                    .put("manifestSha256", sha(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .put("logicalName", name).put("acceptedFileMtimeNs", modified).put("acceptedAt", Instant.now().toString());
        } else {
            record.putObject("actualChecksumReceipt").put("resourceId", "SYNTHETIC_REFERENCE").put("resourceVersion", "1")
                    .put("assembly", assembly.name()).put("logicalName", name).put("expectedSha256", checksum).put("observedSha256", checksum)
                    .put("hashStatus", "VERIFIED").put("hashAlgorithm", "SHA-256").put("hashScope", "RAW_BYTES")
                    .put("unchangedDuringHash", true).put("byteLength", Files.size(fasta)).put("mtimeNs", modified)
                    .put("startedAt", Instant.now().minusSeconds(2).toString()).put("completedAt", Instant.now().minusSeconds(1).toString()).put("indexSha256", indexSha);
        }
        var envelope = mapper.createObjectNode().put("source", assembly.name() + "_SYNTHETIC").put("version", "1").put("dataClass", "SYNTHETIC");
        envelope.putObject("records").set("checksum", record);
        var ledger = ExplicitContextSourceLedger.create(mapper, envelope);
        var validator = new NormalizationResourceValidator(properties) {
            @Override public NormalizationReferenceReadiness validate(GenomeAssembly requested) {
                return new NormalizationReferenceReadiness(requested, "READY", "SYNTHETIC_REFERENCE", name, checksum,
                        true, true, 1, "CHR_PREFIXED", List.of(), List.of("SYNTHETIC_TINY_REFERENCE"));
            }
        };
        return new Reference(new NativeSnvReference(mapper, properties, validator, root, List.of(ledger), ledger.reference("checksum"), assembly), checksum);
    }

    @ParameterizedTest @ValueSource(strings = {"snv", "insertion", "deletion", "sourceRef", "targetRef", "alt", "record", "version", "relation"})
    void actualLocalReferenceAndAlleleRelationDetermineReadiness(String mode) throws Exception {
        String ref = mode.equals("deletion") ? "TC" : "T", alt = mode.equals("insertion") ? "TA" : mode.equals("deletion") ? "T" : "A";
        var original = reference(GenomeAssembly.GRCh37, mode.equals("sourceRef") ? "AACG" : "ATCG", true);
        var target = reference(GenomeAssembly.GRCh38, mode.equals("targetRef") ? "AAACG" : "AATCG", false);
        var run = ParsedCandidateFixture.run(root.resolve("run"), "synthetic-ref", GenomeAssembly.GRCh38,
                ParsedCandidateFixture.vcf("chr11\t3\t.\t" + ref + "\t" + alt + "\t.\tPASS\t.\tGT\t0/1"),
                ParsedCandidateFixture.tsv("1\t11\t3\t" + ref + "\t" + alt + "\tHBB\t3043\tAR\t0.8"));
        var candidate = run.execution().getInterpretations().get(0);
        var proof = mapper.createObjectNode().put("version", mode.equals("version") ? "old" : ConversionSourceProof.VERSION)
                .put("sourceReferenceSha256", original.sha()).put("targetReferenceSha256", target.sha());
        var row = proof.putObject("candidates").putObject(candidate.getCandidateId()).put("status", "ASSOCIATED_PENDING_REFERENCE_VALIDATION")
                .put("originalRecordOrdinal", 1).put("analysisRecordOrdinal", 1).put("originalAltIndex", mode.equals("alt") ? 2 : 1).put("analysisAltIndex", 1)
                .put("originalRecord", "chr11|" + (mode.equals("record") ? 4 : 2) + "|" + ref + "|" + alt + "|1")
                .put("genotypeAssociation", "EXACT_ALLELE_INDEX_AND_PHASE_MATCH");
        row.putObject("genotype").putArray("alleleIndexes").add(0).add(1);
        row.putObject("originalAllele").put("assembly", "GRCh37").put("contig", "chr11").put("position", 2).put("reference", ref).put("alternate", alt);
        row.putObject("mappedAllele").put("assembly", "GRCh38").put("contig", "chr11").put("position", 3).put("reference", ref).put("alternate", mode.equals("relation") ? "G" : alt);
        row.putObject("analysisAllele").put("assembly", "GRCh38").put("contig", "chr11").put("position", 3).put("reference", ref).put("alternate", alt);
        var conversion = new BuildConversionResult(run.conversion().getOutputFile(), true, "SYNTHETIC_PROOF", List.of(), null, 0, 1, 1, proof);
        var observed = mapper.createObjectNode();
        var matched = run.execution().getInputSource().matchedRecord(candidate.getObservation().getKey());
        if (List.of("snv", "insertion", "deletion").contains(mode)) {
            var checked = ConvertedAlleleSource.verify(mapper, conversion, candidate, matched, target.query(), original.query(), observed);
            assertEquals("VERIFIED", observed.path("status").asText());
            assertEquals("GRCh37", checked.original().assembly()); assertEquals("GRCh38", checked.target().assembly());
            assertEquals("11", checked.original().contig());
            assertEquals("chr11", observed.at("/originalAllele/contig").asText());
            assertEquals("ACCEPTED_MANIFEST_INDEX_AND_LOCAL_REF_V1", checked.originalReference().path("verificationVersion").asText());
            assertFalse(checked.originalReference().has("actualChecksumReceipt"));
            assertFalse(checked.originalReference().path("fullReferenceHashComputed").asBoolean(true));
            assertEquals(3, observed.path("referenceQueryAttempts").asInt());
        } else assertThrows(NativeSnvReference.Blocked.class,
                () -> ConvertedAlleleSource.verify(mapper, conversion, candidate, matched, target.query(), original.query(), observed));
    }

    @Test void manifestCannotBypassNative38ReceiptAndChangedReferenceIsRejected() throws Exception {
        var native38 = reference(GenomeAssembly.GRCh38, "ATCG", true);
        assertEquals("REFERENCE_CHECKSUM_RECEIPT_REQUIRED", assertThrows(NativeSnvReference.Blocked.class,
                () -> native38.query().observe("chr11", 2, "T")).reason);
        var original = reference(GenomeAssembly.GRCh37, "ATCG", true);
        Files.writeString(root.resolve("GRCh37.fa"), "changed", StandardOpenOption.APPEND);
        assertEquals("REFERENCE_CHECKSUM_NOT_APPLICABLE", assertThrows(NativeSnvReference.Blocked.class,
                () -> original.query().observe("chr11", 2, "T")).reason);
    }
}
