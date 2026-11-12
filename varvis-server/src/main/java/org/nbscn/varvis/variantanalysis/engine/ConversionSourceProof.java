package org.nbscn.varvis.variantanalysis.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import htsjdk.samtools.liftover.LiftOver;
import htsjdk.samtools.util.Interval;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFIteratorBuilder;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/** Private upload-to-analysis witness. Record ordinals are inserted before Picard, never inferred from nearby loci. */
public final class ConversionSourceProof {
    public static final String VERSION = "grch37-grch38-source-proof-v1";
    public static final String TAG = "VARVIS_UPLOAD_RECORD";
    private ConversionSourceProof() { }

    public static Path tagInput(Path input, Path workspace) throws IOException {
        Path tagged = workspace.resolve("liftover-record-linked-" + UUID.randomUUID() + ".vcf");
        try (var raw = new BufferedInputStream(Files.newInputStream(input));
             var reader = new BufferedReader(new InputStreamReader(uncompressed(raw), StandardCharsets.UTF_8));
             var writer = Files.newBufferedWriter(tagged, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            String line; long ordinal = 0;
            while ((line = reader.readLine()) != null) {
                require(!line.startsWith("##INFO=<ID=" + TAG + ","), "CONVERSION_RESERVED_RECORD_TAG");
                if (line.startsWith("#CHROM")) {
                    writer.write("##INFO=<ID=" + TAG + ",Number=1,Type=Integer,Description=\"Original upload record ordinal; private conversion linkage\">\n");
                } else if (!line.startsWith("#") && !line.isBlank()) {
                    String[] fields = line.split("\t", -1);
                    require(fields.length >= 8, "CONVERSION_ORIGINAL_RECORD_INVALID");
                    require(Arrays.stream(fields[7].split(";")).noneMatch(v -> v.equals(TAG) || v.startsWith(TAG + "=")), "CONVERSION_RESERVED_RECORD_TAG");
                    fields[7] = (fields[7].equals(".") || fields[7].isBlank() ? "" : fields[7] + ";") + TAG + "=" + (++ordinal);
                    line = String.join("\t", fields);
                }
                writer.write(line); writer.newLine();
            }
        }
        return tagged;
    }

    public static ObjectNode completed(ObjectMapper mapper, JsonNode originalIdentity, Path original, Path tagged,
                                       Path output, Path rejected, VariantAnalysisProperties properties) throws IOException {
        require(originalIdentity.equals(artifact(mapper, original)), "CONVERSION_ORIGINAL_ARTIFACT_CHANGED");
        var result = mapper.createObjectNode().put("version", VERSION).put("inputAssembly", "GRCh37")
                .put("analysisAssembly", "GRCh38").put("method", "PICARD_LIFTOVER_VCF")
                .put("recordAssociation", TAG).put("refAltSwapPolicy", "REJECT_FOR_EVIDENCE")
                .put("minimumMappingMatch", properties.getLiftOver().getMinMatch());
        result.set("originalArtifact", originalIdentity.deepCopy());
        result.set("taggedArtifact", artifact(mapper, tagged));
        result.set("analysisArtifact", artifact(mapper, output));
        result.set("rejectedArtifact", artifact(mapper, rejected));
        result.set("rejectedRecords", rejectedRecords(mapper, rejected));
        result.set("chain", artifact(mapper, properties.getLiftOver().getHg19ToHg38Chain()));
        result.set("implementation", artifact(mapper, properties.getLiftOver().getPicardJar()));
        try (var jar = new java.util.jar.JarFile(properties.getLiftOver().getPicardJar().toFile())) {
            var manifest = jar.getManifest();
            String version = manifest == null ? null : manifest.getMainAttributes().getValue("Implementation-Version");
            result.put("implementationVersion", version == null ? "UNREPORTED_SHA256_PINNED" : version);
        }
        result.put("verificationImplementation", "HTSJDK_2.24.1_UNIQUE_COMPLETE_INTERVAL_AND_ALLELE_ASSOCIATION");
        result.put("sourceReferenceSha256", properties.getNormalization().referenceSha256For(GenomeAssembly.GRCh37));
        result.put("targetReferenceSha256", properties.getNormalization().referenceSha256For(GenomeAssembly.GRCh38));
        return result;
    }

    /** Preserve every Picard rejection category and a bounded sample of private record ordinals. */
    private static ObjectNode rejectedRecords(ObjectMapper mapper, Path rejected) throws IOException {
        var summary = mapper.createObjectNode();
        var counts = summary.putObject("reasonCounts");
        var rows = summary.putArray("records");
        long total = 0;
        try (var iterator = new VCFIteratorBuilder().open(rejected)) {
            while (iterator.hasNext()) {
                var record = iterator.next(); total++;
                var reasons = new TreeSet<String>();
                for (String filter : record.getFilters()) reasons.add(switch (filter) {
                    case "NoTarget" -> "CONVERSION_UNMAPPED";
                    case "MismatchedRefAllele" -> "CONVERSION_TARGET_REF_MISMATCH";
                    case "IndelStraddlesMultipleIntevals" -> "CONVERSION_MAPPING_INCOMPLETE";
                    case "CannotLiftOver" -> "CONVERSION_ALLELE_RELATION_UNRESOLVED";
                    default -> "CONVERSION_PICARD_REJECTED";
                });
                if (reasons.isEmpty()) reasons.add("CONVERSION_PICARD_REJECTED");
                for (String reason : reasons) counts.put(reason, counts.path(reason).asLong() + 1);
                if (rows.size() < AcmgExecutionSources.MAX_CANDIDATES) {
                    var row = rows.addObject().put("originalRecordOrdinal", record.getAttributeAsInt(TAG, -1));
                    row.set("reasonCodes", mapper.valueToTree(reasons)); row.set("picardFilters", mapper.valueToTree(record.getFilters()));
                }
            }
        }
        return summary.put("totalCount", total).put("recordDetailsTruncated", total > rows.size());
    }

    /** One bounded-memory original/target scan for the published candidates, not a second scientific analysis. */
    static ObjectNode publish(ObjectMapper mapper, VariantAnalysisJob job, BuildConversionResult conversion,
                              EngineExecutionResult execution, List<VariantInterpretation> candidates) throws IOException {
        ObjectNode proof = conversion.getSourceProof();
        if (proof == null) throw new IllegalArgumentException("CONVERSION_SOURCE_PROOF_MISSING");
        require(proof != null && VERSION.equals(proof.path("version").asText()), "CONVERSION_SOURCE_PROOF_MISSING");
        verifyTask(proof, job, conversion.getOutputFile(), execution.getInputSource().observedVcfSha256());
        require(proof.path("originalArtifact").equals(artifact(mapper, job.getUploadedFile())), "CONVERSION_ORIGINAL_ARTIFACT_CHANGED");
        require(proof.path("analysisArtifact").equals(artifact(mapper, conversion.getOutputFile())), "CONVERSION_ANALYSIS_ARTIFACT_CHANGED");
        var needed = new LinkedHashSet<Integer>();
        for (var candidate : candidates.stream().limit(AcmgExecutionSources.MAX_CANDIDATES).toList()) {
            if (candidate == null || candidate.getObservation() == null || candidate.getObservation().getKey() == null) continue;
            var record = execution.getInputSource().matchedRecord(candidate.getObservation().getKey());
            if (record != null) needed.add(record.recordOrdinal());
        }
        Map<Integer, VariantContext> targets = selectedRecords(conversion.getOutputFile(), needed);
        Set<Integer> originalOrdinals = new LinkedHashSet<>();
        for (var target : targets.values()) {
            try {
                int ordinal = target.getAttributeAsInt(TAG, -1);
                if (ordinal > 0) originalOrdinals.add(ordinal);
            } catch (IllegalArgumentException invalidTag) { /* The individual row is blocked below. */ }
        }
        var originals = selectedRecords(job.getUploadedFile(), originalOrdinals);
        Path chainPath = Path.of(proof.path("chain").path("path").asText());
        require(proof.path("chain").equals(artifact(mapper, chainPath)), "CONVERSION_CHAIN_IDENTITY_CHANGED");
        var lift = new LiftOver(chainPath.toFile());
        var rows = proof.putObject("candidates");
        for (var candidate : candidates.stream().limit(AcmgExecutionSources.MAX_CANDIDATES).toList()) {
            if (candidate == null || candidate.getCandidateId() == null) continue;
            var row = rows.putObject(candidate.getCandidateId());
            try {
                require(candidate.getObservation() != null && candidate.getObservation().getKey() != null, "CONVERSION_ANALYSIS_ALLELE_MISSING");
                var matched = execution.getInputSource().matchedRecord(candidate.getObservation().getKey());
                if (matched == null) throw new IllegalArgumentException("CONVERSION_ANALYSIS_RECORD_MISSING");
                var target = targets.get(matched.recordOrdinal());
                if (target == null) throw new IllegalArgumentException("CONVERSION_ANALYSIS_RECORD_MISSING");
                int ordinal = target.getAttributeAsInt(TAG, -1), alt = matched.selectedAltIndex();
                var original = originals.get(ordinal);
                if (original == null || ordinal <= 0) throw new IllegalArgumentException("CONVERSION_ORIGINAL_RECORD_MISSING");
                require(alt > 0 && alt < original.getNAlleles() && original.getNAlleles() == target.getNAlleles(), "CONVERSION_ALT_ASSOCIATION_INVALID");
                require(target.getContig().equals(matched.contig()) && target.getStart() == matched.position()
                        && target.getReference().getDisplayString().equals(matched.reference())
                        && target.getAlternateAlleles().stream().map(a -> a.getDisplayString()).toList().equals(matched.alternates()), "CONVERSION_ANALYSIS_RECORD_CONFLICT");
                require(!target.hasAttribute("SwappedAlleles"), "CONVERSION_REF_ALT_SWAP_UNSUPPORTED");
                String sourceContig = original.getContig();
                if (!lift.getContigMap().containsKey(sourceContig) && sourceContig.matches("(?:[1-9]|1[0-9]|2[0-2]|X|Y|MT)"))
                    sourceContig = "chr" + (sourceContig.equals("MT") ? "M" : sourceContig);
                var mapped = lift.liftOver(new Interval(sourceContig, original.getStart(), original.getEnd()), 1.0);
                if (mapped == null) throw new IllegalArgumentException(lift.diagnosticLiftover(new Interval(sourceContig, original.getStart(), original.getEnd())).size() > 1
                        ? "CONVERSION_MAPPING_AMBIGUOUS" : "CONVERSION_UNMAPPED_OR_INCOMPLETE");
                require(mapped.length() == original.getReference().length() && mapped.getContig().equals(target.getContig()), "CONVERSION_ALLELE_RELATION_UNRESOLVED");
                String ref = original.getReference().getDisplayString(), alternate = original.getAlleles().get(alt).getDisplayString();
                require(ref.matches("[ACGT]{1,50}") && alternate.matches("[ACGT]{1,50}") && (ref.length() == 1 || alternate.length() == 1), "CONVERSION_VARIANT_TYPE_UNSUPPORTED");
                String sample = job.getSelectedSampleId();
                if (sample == null || sample.isBlank()) sample = job.getRequestedSampleId();
                var sourceGt = genotype(mapper, original, sample);
                var targetGt = genotype(mapper, target, sample);
                require(sourceGt.equals(targetGt), "CONVERSION_GENOTYPE_ASSOCIATION_CONFLICT");
                row.put("status", "ASSOCIATED_PENDING_REFERENCE_VALIDATION").put("originalRecordOrdinal", ordinal)
                        .put("analysisRecordOrdinal", matched.recordOrdinal()).put("originalAltIndex", alt).put("analysisAltIndex", alt)
                        .put("strand", mapped.isNegativeStrand() ? "-" : "+")
                        .put("originalRecord", original.getContig() + "|" + original.getStart() + "|" + ref + "|"
                                + String.join(",", original.getAlternateAlleles().stream().map(a -> a.getDisplayString()).toList()) + "|" + alt)
                        .put("genotypeAssociation", "EXACT_ALLELE_INDEX_AND_PHASE_MATCH");
                row.set("genotype", sourceGt);
                row.set("originalAllele", allele(mapper, "GRCh37", original.getContig(), original.getStart(), ref, alternate));
                row.set("mappedAllele", allele(mapper, "GRCh38", mapped.getContig(), mapped.getStart(),
                        mapped.isNegativeStrand() ? reverseComplement(ref) : ref, mapped.isNegativeStrand() ? reverseComplement(alternate) : alternate));
                row.set("analysisAllele", allele(mapper, "GRCh38", matched.contig(), matched.position(), matched.reference(), matched.alternates().get(alt - 1)));
            } catch (IllegalArgumentException invalid) {
                row.put("status", "BLOCKED").put("reasonCode", AcmgExecutionSources.code(invalid));
            }
        }
        verifyStamp(proof.path("originalArtifact"), job.getUploadedFile());
        verifyStamp(proof.path("analysisArtifact"), conversion.getOutputFile());
        proof.put("publicationAssociationVcfScans", 2).put("publicationFullReferenceHashes", 0);
        return proof;
    }

    public static void verifyTask(JsonNode proof, VariantAnalysisJob job, Path analysis, String observedSha) throws IOException {
        require(VERSION.equals(proof.path("version").asText()) && "GRCh37".equals(proof.path("inputAssembly").asText())
                && "GRCh38".equals(proof.path("analysisAssembly").asText()), "CONVERSION_SOURCE_VERSION_UNSUPPORTED");
        require(job.getGenomeAssembly() == GenomeAssembly.GRCh37 && Objects.equals(job.getFileSha256(), proof.at("/originalArtifact/sha256").asText()), "CONVERSION_ORIGINAL_ARTIFACT_CONFLICT");
        require(observedSha != null && observedSha.equals(proof.at("/analysisArtifact/sha256").asText()), "CONVERSION_ANALYSIS_ARTIFACT_CONFLICT");
        verifyStamp(proof.path("originalArtifact"), job.getUploadedFile());
        verifyStamp(proof.path("analysisArtifact"), analysis);
        for (String key : List.of("sourceReferenceSha256", "targetReferenceSha256"))
            require(proof.path(key).asText().matches("[0-9a-f]{64}"), "CONVERSION_REFERENCE_IDENTITY_MISSING");
    }

    public static ObjectNode artifact(ObjectMapper mapper, Path path) throws IOException {
        require(path != null && Files.isRegularFile(path), "CONVERSION_ARTIFACT_MISSING");
        var before = stamp(mapper, path);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) { byte[] buffer = new byte[65536]; int n; while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n); }
            require(before.equals(stamp(mapper, path)), "CONVERSION_ARTIFACT_CHANGED_DURING_HASH");
            return before.put("sha256", HexFormat.of().formatHex(digest.digest()));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static ObjectNode stamp(ObjectMapper mapper, Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        return mapper.createObjectNode().put("path", path.toAbsolutePath().normalize().toString()).put("bytes", attributes.size())
                .put("mtimeNs", attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS)).put("fileKey", Objects.toString(attributes.fileKey(), null));
    }
    private static void verifyStamp(JsonNode saved, Path path) throws IOException {
        var actual = stamp(new ObjectMapper(), path);
        for (String field : List.of("path", "fileKey")) require(actual.path(field).equals(saved.path(field)), "CONVERSION_ARTIFACT_CHANGED");
        // JSON round trips may narrow a small LongNode to IntNode; compare exact integer values, never node implementation types.
        for (String field : List.of("bytes", "mtimeNs")) require(saved.path(field).isIntegralNumber()
                && saved.path(field).canConvertToLong() && actual.path(field).longValue() == saved.path(field).longValue(), "CONVERSION_ARTIFACT_CHANGED");
    }
    private static Map<Integer, VariantContext> selectedRecords(Path path, Set<Integer> ordinals) throws IOException {
        var result = new LinkedHashMap<Integer, VariantContext>();
        try (var iterator = new VCFIteratorBuilder().open(path)) {
            int ordinal = 0;
            while (iterator.hasNext()) { var record = iterator.next(); if (ordinals.contains(++ordinal)) result.put(ordinal, record.fullyDecode(iterator.getHeader(), false)); }
        }
        return result;
    }
    private static ObjectNode genotype(ObjectMapper mapper, VariantContext record, String sample) {
        var gt = record.getGenotype(sample);
        if (gt == null || gt.getPloidy() <= 0) throw new IllegalArgumentException("CONVERSION_GENOTYPE_ASSOCIATION_MISSING");
        var result = mapper.createObjectNode().put("phased", gt.isPhased());
        var indexes = result.putArray("alleleIndexes");
        for (var allele : gt.getAlleles()) { int index = allele.isNoCall() ? -1 : record.getAlleleIndex(allele); require(allele.isNoCall() || index >= 0, "CONVERSION_GENOTYPE_ASSOCIATION_CONFLICT"); indexes.add(index); }
        return result;
    }
    private static ObjectNode allele(ObjectMapper mapper, String assembly, String contig, int position, String ref, String alt) {
        return mapper.createObjectNode().put("assembly", assembly).put("contig", contig).put("position", position).put("reference", ref).put("alternate", alt);
    }
    private static String reverseComplement(String value) {
        StringBuilder output = new StringBuilder();
        for (int i = value.length() - 1; i >= 0; i--) output.append(switch (value.charAt(i)) { case 'A' -> 'T'; case 'C' -> 'G'; case 'G' -> 'C'; case 'T' -> 'A'; default -> throw new IllegalArgumentException("CONVERSION_ALLELE_UNSUPPORTED"); });
        return output.toString();
    }
    private static InputStream uncompressed(BufferedInputStream input) throws IOException {
        input.mark(2); int first = input.read(), second = input.read(); input.reset();
        return first == 0x1f && second == 0x8b ? new GZIPInputStream(input) : input;
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
