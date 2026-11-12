package org.nbscn.varvis.variantanalysis.engine;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/**
 * Builds a bounded-in-memory lookup for candidate alleles while making exactly
 * one sequential pass over the Exomiser analysis VCF.
 */
final class AnalysisVcfAlleleIndex {

    private static final SecureRandom PHASE_SCOPE_RANDOM = new SecureRandom();

    Map<NormalizedVariantKey, AnalysisVcfCall> index(
            Path analysisVcf,
            GenomeAssembly assembly,
            String sampleId,
            Collection<NormalizedVariantKey> candidateKeys) {
        return indexWithSource(analysisVcf, assembly, sampleId, candidateKeys).calls();
    }

    IndexedCalls indexWithSource(Path analysisVcf, GenomeAssembly assembly, String sampleId,
                                 Collection<NormalizedVariantKey> candidateKeys) {
        validateInputs(analysisVcf, assembly, sampleId, candidateKeys);
        LinkedHashSet<NormalizedVariantKey> candidates = canonicalCandidates(assembly, candidateKeys);
        try {
            ArtifactStamp before = ArtifactStamp.capture(analysisVcf);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var raw = new DigestInputStream(Files.newInputStream(analysisVcf), digest);
                 SourceVcfIterator reader = new SourceVcfIterator(raw)) {
                if (!reader.getHeader().getGenotypeSamples().contains(sampleId)) {
                    throw new IllegalArgumentException("ANALYSIS_VCF_SAMPLE_NOT_FOUND");
                }
                if (candidates.isEmpty()) {
                    return new IndexedCalls(Map.of(), Map.of(), null, before, 0);
                }
                Map<NormalizedVariantKey, EngineExecutionResult.MatchedRecord> records = new LinkedHashMap<>();
                Map<NormalizedVariantKey, PublishedSampleFilter> filters = new LinkedHashMap<>();
                Map<NormalizedVariantKey, AnalysisVcfCall> calls = scan(reader, assembly, sampleId, candidates, newPhaseScopeSecret(), records, filters);
                raw.transferTo(OutputStream.nullOutputStream()); // Finish this same stream, including compressed EOF bytes.
                if (!before.equals(ArtifactStamp.capture(analysisVcf))) {
                    throw new IllegalStateException("ANALYSIS_VCF_CHANGED_DURING_SCAN");
                }
                return new IndexedCalls(calls, Map.copyOf(records), HexFormat.of().formatHex(digest.digest()), before, 1, Map.copyOf(filters));
            }
        } catch (IOException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("ANALYSIS_VCF_UNREADABLE", ex);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (IllegalStateException ex) {
            if (hasAnalysisVcfErrorCode(ex)) {
                throw ex;
            }
            throw new IllegalStateException("ANALYSIS_VCF_UNREADABLE", ex);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("ANALYSIS_VCF_UNREADABLE", ex);
        }
    }

    private Map<NormalizedVariantKey, AnalysisVcfCall> scan(
            SourceVcfIterator reader,
            GenomeAssembly assembly,
            String sampleId,
            Set<NormalizedVariantKey> candidates,
            byte[] phaseScopeSecret,
            Map<NormalizedVariantKey, EngineExecutionResult.MatchedRecord> records,
            Map<NormalizedVariantKey, PublishedSampleFilter> filters) {
        Map<NormalizedVariantKey, MatchAccumulator> matches =
                new LinkedHashMap<NormalizedVariantKey, MatchAccumulator>();
        for (NormalizedVariantKey candidate : candidates) {
            matches.put(candidate, new MatchAccumulator());
        }

        int recordOrdinal = 0;
        try {
            while (reader.hasNext()) {
                var row = reader.next();
                VariantContext variant = row.variant();
                String rawGenotypes = row.rawGenotypes();
                if (recordOrdinal == Integer.MAX_VALUE) {
                    throw new IllegalStateException("ANALYSIS_VCF_RECORD_LIMIT_EXCEEDED");
                }
                recordOrdinal++;
                List<Allele> alternates = variant.getAlternateAlleles();
                for (int alternateOffset = 0; alternateOffset < alternates.size(); alternateOffset++) {
                    Allele alternate = alternates.get(alternateOffset);
                    NormalizedVariantKey key = keyFor(assembly, variant, alternate);
                    MatchAccumulator accumulator = matches.get(key);
                    if (accumulator == null) {
                        continue;
                    }
                    accumulator.observe(buildCall(
                            assembly,
                            sampleId,
                            variant,
                            alternate,
                            alternateOffset + 1,
                            recordOrdinal,
                            phaseScopeSecret
                    ));
                    if (accumulator.count == 1) {
                        records.put(key, new EngineExecutionResult.MatchedRecord(variant.getContig(), variant.getStart(),
                                variant.getReference().getDisplayString(), alternates.stream().map((Allele allele) -> allele.getDisplayString()).toList(),
                                alternateOffset + 1, recordOrdinal));
                        filters.put(key, PublishedSampleFilter.capture(rawGenotypes, reader.getHeader().getGenotypeSamples().indexOf(sampleId),
                                sampleId, assembly.name(), records.get(key)));
                    } else {
                        records.remove(key);
                        filters.remove(key);
                    }
                }
            }
        } catch (IllegalArgumentException ex) {
            if (hasAnalysisVcfErrorCode(ex)) {
                throw ex;
            }
            throw new IllegalStateException("ANALYSIS_VCF_UNREADABLE", ex);
        } catch (IllegalStateException ex) {
            if (hasAnalysisVcfErrorCode(ex)) {
                throw ex;
            }
            throw new IllegalStateException("ANALYSIS_VCF_UNREADABLE", ex);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("ANALYSIS_VCF_UNREADABLE", ex);
        }

        Map<NormalizedVariantKey, AnalysisVcfCall> result =
                new LinkedHashMap<NormalizedVariantKey, AnalysisVcfCall>();
        for (Map.Entry<NormalizedVariantKey, MatchAccumulator> entry : matches.entrySet()) {
            MatchAccumulator accumulator = entry.getValue();
            if (accumulator.count == 0) {
                result.put(entry.getKey(), AnalysisVcfCall.notFound(assembly));
            } else if (accumulator.count == 1) {
                result.put(entry.getKey(), accumulator.first);
            } else {
                result.put(entry.getKey(), AnalysisVcfCall.ambiguous(assembly, accumulator.count));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private AnalysisVcfCall buildCall(GenomeAssembly assembly,
                                      String sampleId,
                                      VariantContext variant,
                                      Allele targetAllele,
                                      int targetAlleleIndex,
                                      int recordOrdinal,
                                      byte[] phaseScopeSecret) {
        LinkedHashSet<String> missing = new LinkedHashSet<String>();
        LinkedHashSet<String> invalid = new LinkedHashSet<String>();
        Genotype genotype = variant.hasGenotype(sampleId) ? variant.getGenotype(sampleId) : null;

        GenotypeProjection projection = projectGenotype(variant, genotype, targetAllele, missing, invalid);
        Integer depth = genotypeInteger(genotype, GenotypeInteger.DP, missing, invalid);
        Integer genotypeQuality = genotypeInteger(genotype, GenotypeInteger.GQ, missing, invalid);
        AlleleDepthProjection alleleDepths = projectAlleleDepths(
                genotype,
                targetAlleleIndex,
                missing,
                invalid
        );
        AnalysisVcfCall.FilterEvaluation filterEvaluation = filterEvaluation(variant);
        PhaseProjection phase = phaseProjection(
                phaseScopeSecret,
                variant.getContig(),
                genotype,
                projection.call,
                missing,
                invalid
        );

        return AnalysisVcfCall.matched(
                projection.call,
                projection.candidateRelativeGenotype,
                targetAlleleIndex,
                depth,
                genotypeQuality,
                alleleDepths.referenceDepth,
                alleleDepths.targetDepth,
                alleleDepths.alleleBalance,
                filterEvaluation,
                phase.status,
                phase.token,
                recordOrdinal,
                assembly,
                new ArrayList<String>(missing),
                new ArrayList<String>(invalid)
        );
    }

    private GenotypeProjection projectGenotype(VariantContext variant,
                                               Genotype genotype,
                                               Allele targetAllele,
                                               Set<String> missing,
                                               Set<String> invalid) {
        if (genotype == null || genotype.getAlleles() == null || genotype.getAlleles().isEmpty()) {
            missing.add("GT");
            return new GenotypeProjection(AnalysisVcfCall.TargetAlleleCall.NO_CALL, ".");
        }
        List<String> tokens = new ArrayList<String>();
        int reference = 0;
        int target = 0;
        int other = 0;
        int noCall = 0;
        for (Allele allele : genotype.getAlleles()) {
            if (allele == null || allele.isNoCall()) {
                tokens.add(".");
                noCall++;
            } else if (allele.equals(variant.getReference())) {
                tokens.add("REF");
                reference++;
            } else if (allele.equals(targetAllele)) {
                tokens.add("TARGET_ALT");
                target++;
            } else {
                tokens.add("OTHER_ALT");
                other++;
            }
        }
        if (noCall > 0) {
            missing.add("GT");
        }
        String relative = String.join(genotype.isPhased() ? "|" : "/", tokens);
        AnalysisVcfCall.TargetAlleleCall call = classifyCall(
                tokens.size(),
                reference,
                target,
                other,
                noCall
        );
        if (call == AnalysisVcfCall.TargetAlleleCall.UNSUPPORTED) {
            invalid.add("GT");
        }
        return new GenotypeProjection(call, relative);
    }

    private AnalysisVcfCall.TargetAlleleCall classifyCall(int ploidy,
                                                          int reference,
                                                          int target,
                                                          int other,
                                                          int noCall) {
        if (ploidy <= 0) {
            return AnalysisVcfCall.TargetAlleleCall.NO_CALL;
        }
        if (noCall == ploidy) {
            return AnalysisVcfCall.TargetAlleleCall.NO_CALL;
        }
        if (target > 0 && noCall > 0) {
            return AnalysisVcfCall.TargetAlleleCall.PARTIAL_TARGET;
        }
        if (target > 0 && other > 0) {
            return AnalysisVcfCall.TargetAlleleCall.TARGET_WITH_OTHER_ALT;
        }
        if (ploidy == 1 && target == 1) {
            return AnalysisVcfCall.TargetAlleleCall.HEMI_TARGET;
        }
        if (ploidy == 1 && reference == 1) {
            return AnalysisVcfCall.TargetAlleleCall.HEMI_REF;
        }
        if (target == ploidy) {
            return AnalysisVcfCall.TargetAlleleCall.HOM_TARGET;
        }
        if (reference == ploidy) {
            return AnalysisVcfCall.TargetAlleleCall.HOM_REF;
        }
        if (target > 0 && reference > 0 && other == 0 && noCall == 0) {
            return AnalysisVcfCall.TargetAlleleCall.HET_TARGET;
        }
        if (target == 0 && other > 0 && noCall == 0) {
            return AnalysisVcfCall.TargetAlleleCall.OTHER_ALT_ONLY;
        }
        return AnalysisVcfCall.TargetAlleleCall.UNSUPPORTED;
    }

    private Integer genotypeInteger(Genotype genotype,
                                    GenotypeInteger field,
                                    Set<String> missing,
                                    Set<String> invalid) {
        if (genotype == null || !field.isPresent(genotype)) {
            missing.add(field.name());
            return null;
        }
        try {
            int value = field.value(genotype);
            if (value < 0) {
                invalid.add(field.name());
                return null;
            }
            return value;
        } catch (RuntimeException ex) {
            invalid.add(field.name());
            return null;
        }
    }

    private AlleleDepthProjection projectAlleleDepths(Genotype genotype,
                                                      int targetAlleleIndex,
                                                      Set<String> missing,
                                                      Set<String> invalid) {
        if (genotype == null || !genotype.hasAD()) {
            missing.add("AD");
            missing.add("AB");
            return AlleleDepthProjection.empty();
        }
        int[] depths;
        try {
            depths = genotype.getAD();
        } catch (RuntimeException ex) {
            invalid.add("AD");
            missing.add("AB");
            return AlleleDepthProjection.empty();
        }
        if (depths == null || depths.length == 0) {
            missing.add("AD");
            missing.add("AB");
            return AlleleDepthProjection.empty();
        }
        Integer referenceDepth = validDepth(depths, 0, "AD:REF", invalid);
        Integer targetDepth = validDepth(depths, targetAlleleIndex, "AD:TARGET", invalid);
        if (referenceDepth == null || targetDepth == null) {
            missing.add("AB");
            return new AlleleDepthProjection(referenceDepth, targetDepth, null);
        }
        long denominator = (long) referenceDepth + targetDepth;
        if (denominator <= 0L) {
            missing.add("AB");
            return new AlleleDepthProjection(referenceDepth, targetDepth, null);
        }
        return new AlleleDepthProjection(
                referenceDepth,
                targetDepth,
                targetDepth / (double) denominator
        );
    }

    private Integer validDepth(int[] values, int index, String field, Set<String> invalid) {
        if (index < 0 || index >= values.length) {
            invalid.add(field);
            return null;
        }
        if (values[index] < 0) {
            invalid.add(field);
            return null;
        }
        return values[index];
    }

    private AnalysisVcfCall.FilterEvaluation filterEvaluation(VariantContext variant) {
        if (!variant.filtersWereApplied()) {
            return AnalysisVcfCall.FilterEvaluation.UNASSESSED;
        }
        return variant.isFiltered()
                ? AnalysisVcfCall.FilterEvaluation.FILTERED
                : AnalysisVcfCall.FilterEvaluation.PASS;
    }

    private PhaseProjection phaseProjection(byte[] phaseScopeSecret,
                                            String contig,
                                            Genotype genotype,
                                            AnalysisVcfCall.TargetAlleleCall call,
                                            Set<String> missing,
                                            Set<String> invalid) {
        if (genotype == null
                || call == AnalysisVcfCall.TargetAlleleCall.NO_CALL
                || call == AnalysisVcfCall.TargetAlleleCall.UNSUPPORTED) {
            return new PhaseProjection(AnalysisVcfCall.PhasingStatus.UNKNOWN, null);
        }
        String rawPhaseSet = phaseSet(genotype);
        if (!genotype.isPhased()) {
            if (rawPhaseSet != null) {
                invalid.add("PS:UNPHASED");
            }
            return new PhaseProjection(AnalysisVcfCall.PhasingStatus.UNPHASED, null);
        }
        if (rawPhaseSet == null) {
            missing.add("PS");
            return new PhaseProjection(AnalysisVcfCall.PhasingStatus.RECORD_ONLY, null);
        }
        return new PhaseProjection(
                AnalysisVcfCall.PhasingStatus.PHASE_SET,
                phaseToken(phaseScopeSecret, contig, rawPhaseSet)
        );
    }

    private String phaseSet(Genotype genotype) {
        try {
            Object value = genotype.getExtendedAttribute("PS", null);
            if (value == null) {
                return null;
            }
            String normalized = String.valueOf(value).trim();
            return normalized.isEmpty() || ".".equals(normalized) ? null : normalized;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String phaseToken(byte[] phaseScopeSecret,
                              String contig,
                              String rawPhaseSet) {
        String scopedValue = contig + '\u0000' + rawPhaseSet;
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.update(phaseScopeSecret);
            messageDigest.update((byte) 0);
            byte[] digest = messageDigest.digest(scopedValue.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(24);
            for (int i = 0; i < 12; i++) {
                hex.append(String.format("%02x", digest[i] & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("ANALYSIS_VCF_PHASE_HASH_UNAVAILABLE", ex);
        }
    }

    private byte[] newPhaseScopeSecret() {
        byte[] secret = new byte[32];
        PHASE_SCOPE_RANDOM.nextBytes(secret);
        return secret;
    }

    private NormalizedVariantKey keyFor(GenomeAssembly assembly,
                                        VariantContext variant,
                                        Allele alternate) {
        return NormalizedVariantKey.canonical(
                assembly,
                variant.getContig(),
                variant.getStart(),
                variant.getReference().getDisplayString(),
                alternate.getDisplayString()
        );
    }

    private LinkedHashSet<NormalizedVariantKey> canonicalCandidates(
            GenomeAssembly assembly,
            Collection<NormalizedVariantKey> candidateKeys) {
        LinkedHashSet<NormalizedVariantKey> candidates = new LinkedHashSet<NormalizedVariantKey>();
        for (NormalizedVariantKey candidate : candidateKeys) {
            if (candidate == null) {
                throw new IllegalArgumentException("ANALYSIS_VCF_CANDIDATE_KEY_REQUIRED");
            }
            if (!assembly.name().equals(candidate.getGenomeAssembly())) {
                throw new IllegalArgumentException("ANALYSIS_VCF_CANDIDATE_ASSEMBLY_MISMATCH");
            }
            NormalizedVariantKey canonical;
            try {
                canonical = NormalizedVariantKey.canonical(
                        assembly,
                        candidate.getChromosome(),
                        candidate.getPosition(),
                        candidate.getReference(),
                        candidate.getAlternate()
                );
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("ANALYSIS_VCF_CANDIDATE_KEY_INVALID");
            }
            if (!canonical.equals(candidate)) {
                throw new IllegalArgumentException("ANALYSIS_VCF_CANDIDATE_KEY_NOT_CANONICAL");
            }
            candidates.add(candidate);
        }
        return candidates;
    }

    private void validateInputs(Path analysisVcf,
                                GenomeAssembly assembly,
                                String sampleId,
                                Collection<NormalizedVariantKey> candidateKeys) {
        if (analysisVcf == null) {
            throw new IllegalArgumentException("ANALYSIS_VCF_PATH_REQUIRED");
        }
        if (!Files.isRegularFile(analysisVcf) || !Files.isReadable(analysisVcf)) {
            throw new IllegalStateException("ANALYSIS_VCF_UNREADABLE");
        }
        if (assembly == null) {
            throw new IllegalArgumentException("ANALYSIS_VCF_ASSEMBLY_REQUIRED");
        }
        if (sampleId == null || sampleId.trim().isEmpty()) {
            throw new IllegalArgumentException("ANALYSIS_VCF_SAMPLE_ID_REQUIRED");
        }
        if (candidateKeys == null) {
            throw new IllegalArgumentException("ANALYSIS_VCF_CANDIDATE_KEYS_REQUIRED");
        }
    }

    record IndexedCalls(Map<NormalizedVariantKey, AnalysisVcfCall> calls,
                        Map<NormalizedVariantKey, EngineExecutionResult.MatchedRecord> records,
                        String rawSha256, ArtifactStamp stamp, int fullScans,
                        Map<NormalizedVariantKey, PublishedSampleFilter> sampleFilters) {
        IndexedCalls(Map<NormalizedVariantKey, AnalysisVcfCall> calls, Map<NormalizedVariantKey, EngineExecutionResult.MatchedRecord> records,
                     String rawSha256, ArtifactStamp stamp, int fullScans) {
            this(calls, records, rawSha256, stamp, fullScans, Map.of());
        }
    }

    record ArtifactStamp(long byteLength, long mtimeNs, Object fileKey) {
        static ArtifactStamp capture(Path path) throws IOException {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return new ArtifactStamp(attributes.size(), attributes.lastModifiedTime().to(TimeUnit.NANOSECONDS), attributes.fileKey());
        }
    }

    private boolean hasAnalysisVcfErrorCode(RuntimeException error) {
        return error.getMessage() != null
                && (error.getMessage().startsWith("ANALYSIS_VCF_")
                || error.getMessage().startsWith("ANALYSIS_VCF_CALL_"));
    }

    private enum GenotypeInteger {
        DP {
            @Override
            boolean isPresent(Genotype genotype) { return genotype.hasDP(); }

            @Override
            int value(Genotype genotype) { return genotype.getDP(); }
        },
        GQ {
            @Override
            boolean isPresent(Genotype genotype) { return genotype.hasGQ(); }

            @Override
            int value(Genotype genotype) { return genotype.getGQ(); }
        };

        abstract boolean isPresent(Genotype genotype);
        abstract int value(Genotype genotype);
    }

    private static final class MatchAccumulator {
        private int count;
        private AnalysisVcfCall first;

        private void observe(AnalysisVcfCall call) {
            if (count == Integer.MAX_VALUE) {
                throw new IllegalStateException("ANALYSIS_VCF_MATCH_LIMIT_EXCEEDED");
            }
            count++;
            if (count == 1) {
                first = call;
            } else {
                first = null;
            }
        }
    }

    private static final class GenotypeProjection {
        private final AnalysisVcfCall.TargetAlleleCall call;
        private final String candidateRelativeGenotype;

        private GenotypeProjection(AnalysisVcfCall.TargetAlleleCall call,
                                   String candidateRelativeGenotype) {
            this.call = call;
            this.candidateRelativeGenotype = candidateRelativeGenotype;
        }
    }

    private static final class AlleleDepthProjection {
        private final Integer referenceDepth;
        private final Integer targetDepth;
        private final Double alleleBalance;

        private AlleleDepthProjection(Integer referenceDepth,
                                      Integer targetDepth,
                                      Double alleleBalance) {
            this.referenceDepth = referenceDepth;
            this.targetDepth = targetDepth;
            this.alleleBalance = alleleBalance;
        }

        private static AlleleDepthProjection empty() {
            return new AlleleDepthProjection(null, null, null);
        }
    }

    private static final class PhaseProjection {
        private final AnalysisVcfCall.PhasingStatus status;
        private final String token;

        private PhaseProjection(AnalysisVcfCall.PhasingStatus status, String token) {
            this.status = status;
            this.token = token;
        }
    }
}
