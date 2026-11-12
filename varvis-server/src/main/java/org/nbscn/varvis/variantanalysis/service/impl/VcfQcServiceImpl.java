package org.nbscn.varvis.variantanalysis.service.impl;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFHeader;
import org.nbscn.varvis.exception.InvalidVcfUploadException;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcSummary;
import org.nbscn.varvis.variantanalysis.domain.VcfQcResult;
import org.nbscn.varvis.variantanalysis.service.VcfQcService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class VcfQcServiceImpl implements VcfQcService {

    @Override
    public VcfQcResult extract(Path normalizedVcf,
                               GenomeAssembly genomeAssembly,
                               String requestedSampleId) {
        try (VCFFileReader reader = new VCFFileReader(normalizedVcf.toFile(), false)) {
            VCFHeader header = reader.getFileHeader();
            List<String> samples = header == null
                    ? Collections.emptyList()
                    : header.getGenotypeSamples();
            String selectedSample = selectSample(samples, requestedSampleId);
            Accumulator accumulator = new Accumulator(samples.size());
            List<VariantQcObservation> observations = new ArrayList<VariantQcObservation>();

            for (VariantContext variantContext : reader) {
                accumulator.inputSiteCount++;
                List<Allele> alternates = variantContext.getAlternateAlleles();
                if (alternates.size() > 1) {
                    accumulator.multiAllelicSiteCount++;
                }
                for (Allele alternate : alternates) {
                    VariantQcObservation observation = toObservation(
                            variantContext,
                            alternate,
                            selectedSample,
                            genomeAssembly
                    );
                    observations.add(observation);
                    accumulator.accept(observation);
                }
            }
            return new VcfQcResult(selectedSample, accumulator.toSummary(), observations, accumulator.warnings());
        } catch (InvalidVcfUploadException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new InvalidVcfUploadException("VCF or BCF file could not be parsed for QC", ex);
        }
    }

    private String selectSample(List<String> sampleNames, String requestedSampleId) {
        List<String> samples = sampleNames == null ? Collections.emptyList() : sampleNames;
        String requested = StringUtils.hasText(requestedSampleId) ? requestedSampleId.trim() : null;
        if (samples.isEmpty()) {
            throw new InvalidVcfUploadException("VCF must contain at least one sample");
        }
        if (requested != null) {
            if (!samples.contains(requested)) {
                throw new InvalidVcfUploadException(
                        "Requested sampleId '" + requested
                                + "' was not found in the VCF header. Available sampleId values: "
                                + samplePreview(samples)
                );
            }
            return requested;
        }
        if (samples.size() > 1) {
            throw new InvalidVcfUploadException(
                    "Multiple samples found; sampleId must explicitly select one sample. Available sampleId values: "
                            + samplePreview(samples)
            );
        }
        return samples.get(0);
    }

    private String samplePreview(List<String> samples) {
        if (samples == null || samples.isEmpty()) {
            return "-";
        }
        int previewSize = Math.min(samples.size(), 8);
        String preview = samples.subList(0, previewSize).stream()
                .collect(Collectors.joining(", "));
        if (samples.size() > previewSize) {
            preview += ", ... (" + samples.size() + " total)";
        }
        return preview;
    }

    private VariantQcObservation toObservation(VariantContext variantContext,
                                               Allele alternate,
                                               String sampleId,
                                               GenomeAssembly genomeAssembly) {
        Genotype genotype = variantContext.hasGenotype(sampleId)
                ? variantContext.getGenotype(sampleId)
                : null;
        int[] alleleDepths = genotype != null && genotype.hasAD() ? genotype.getAD() : null;
        int alternateIndex = variantContext.getAlleleIndex(alternate);
        NormalizedVariantKey key = NormalizedVariantKey.canonical(
                genomeAssembly,
                variantContext.getContig(),
                variantContext.getStart(),
                variantContext.getReference().getDisplayString(),
                alternate.getDisplayString()
        );
        return new VariantQcObservation(
                key,
                sampleId,
                formatGenotype(variantContext, genotype),
                genotype != null && genotype.hasDP() ? genotype.getDP() : null,
                genotype != null && genotype.hasGQ() ? genotype.getGQ() : null,
                formatAlleleDepths(alleleDepths),
                alleleBalance(alleleDepths, alternateIndex),
                filterStatus(variantContext),
                variantType(variantContext.getReference(), alternate),
                originalRecord(variantContext, alternate, alternateIndex)
        );
    }

    private String formatGenotype(VariantContext variantContext, Genotype genotype) {
        if (genotype == null || genotype.getAlleles() == null || genotype.getAlleles().isEmpty()) {
            return null;
        }
        String separator = genotype.isPhased() ? "|" : "/";
        return genotype.getAlleles().stream()
                .map(allele -> {
                    if (allele == null || allele.isNoCall()) {
                        return ".";
                    }
                    int index = variantContext.getAlleleIndex(allele);
                    return index >= 0 ? String.valueOf(index) : ".";
                })
                .collect(Collectors.joining(separator));
    }

    private String formatAlleleDepths(int[] alleleDepths) {
        if (alleleDepths == null) {
            return null;
        }
        return Arrays.stream(alleleDepths)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private Double alleleBalance(int[] alleleDepths, int alternateIndex) {
        if (alleleDepths == null || alleleDepths.length == 0
                || alternateIndex <= 0 || alternateIndex >= alleleDepths.length) {
            return null;
        }
        int denominator = alleleDepths[0] + alleleDepths[alternateIndex];
        if (denominator <= 0) {
            return null;
        }
        return alleleDepths[alternateIndex] / (double) denominator;
    }

    private String filterStatus(VariantContext variantContext) {
        if (!variantContext.isFiltered()) {
            return "PASS";
        }
        return String.join(";", variantContext.getFilters());
    }

    private String variantType(Allele reference, Allele alternate) {
        if (alternate == null || alternate.isNoCall() || alternate.isSymbolic()) {
            return "OTHER";
        }
        int referenceLength = reference.length();
        int alternateLength = alternate.length();
        if (referenceLength == 1 && alternateLength == 1) {
            return "SNV";
        }
        if (referenceLength != alternateLength) {
            return "INDEL";
        }
        if (referenceLength > 1) {
            return "MNV";
        }
        return "OTHER";
    }

    private String originalRecord(VariantContext variantContext, Allele alternate, int alternateIndex) {
        String originalAlternates = variantContext.getAlternateAlleles().stream()
                .filter(allele -> allele != null)
                .map(allele -> allele.getDisplayString())
                .collect(Collectors.joining(","));
        String fallback = variantContext.getContig()
                + "|" + variantContext.getStart()
                + "|" + variantContext.getReference().getDisplayString()
                + "|" + originalAlternates
                + "|" + alternateIndex;
        List<Object> values = variantContext.getAttributeAsList("VARVIS_ORIGINAL_VARIANT");
        if (values.isEmpty()) {
            return fallback;
        }
        return values.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private static class Accumulator {

        private final int sampleCount;
        private int inputSiteCount;
        private int variantCount;
        private int multiAllelicSiteCount;
        private int snvCount;
        private int indelCount;
        private int mnvCount;
        private int otherCount;
        private int passCount;
        private int filteredCount;
        private int missingDepthCount;
        private int missingGenotypeQualityCount;
        private int missingGenotypeCount;
        private int referenceGenotypeCount;

        private Accumulator(int sampleCount) {
            this.sampleCount = sampleCount;
        }

        private void accept(VariantQcObservation observation) {
            variantCount++;
            if ("SNV".equals(observation.getVariantType())) {
                snvCount++;
            } else if ("INDEL".equals(observation.getVariantType())) {
                indelCount++;
            } else if ("MNV".equals(observation.getVariantType())) {
                mnvCount++;
            } else {
                otherCount++;
            }
            if ("PASS".equals(observation.getFilterStatus())) {
                passCount++;
            } else {
                filteredCount++;
            }
            if (observation.getDepth() == null) {
                missingDepthCount++;
            }
            if (observation.getGenotypeQuality() == null) {
                missingGenotypeQualityCount++;
            }
            if (!StringUtils.hasText(observation.getGenotype())
                    || observation.getGenotype().contains(".")) {
                missingGenotypeCount++;
            } else if (!carriesAlternateAllele(observation.getGenotype())) {
                referenceGenotypeCount++;
            }
        }

        private VariantQcSummary toSummary() {
            return new VariantQcSummary(
                    sampleCount,
                    inputSiteCount,
                    variantCount,
                    multiAllelicSiteCount,
                    snvCount,
                    indelCount,
                    mnvCount,
                    otherCount,
                    passCount,
                    filteredCount,
                    missingDepthCount,
                    missingGenotypeQualityCount
            );
        }

        private List<String> warnings() {
            List<String> result = new ArrayList<String>();
            if (filteredCount > 0) {
                result.add("QC warning: " + filteredCount
                        + " variant(s) have a non-PASS FILTER value and are down-ranked because the caller flagged them as lower confidence.");
            }
            if (multiAllelicSiteCount > 0) {
                result.add("QC warning: " + multiAllelicSiteCount
                        + " multi-allelic site(s) were split into per-allele observations so each ALT allele can be scored independently.");
            }
            if (missingDepthCount > 0) {
                result.add("QC warning: " + missingDepthCount
                        + " variant observation(s) are missing sample depth (DP), so read-support confidence is limited.");
            }
            if (missingGenotypeQualityCount > 0) {
                result.add("QC warning: " + missingGenotypeQualityCount
                        + " variant observation(s) are missing genotype quality (GQ), so genotype confidence is limited.");
            }
            if (missingGenotypeCount > 0) {
                result.add("QC warning: " + missingGenotypeCount
                        + " variant observation(s) have missing or partial genotype calls and should be reviewed cautiously.");
            }
            if (referenceGenotypeCount > 0) {
                result.add("QC warning: " + referenceGenotypeCount
                        + " variant observation(s) are 0/0 for the selected sample and are down-ranked because the submitted sample does not carry the ALT allele.");
            }
            if (otherCount > 0) {
                result.add("QC warning: " + otherCount
                        + " non-SNV/InDel/MNV observation(s) have limited local evidence support in the current VCF engine.");
            }
            return result;
        }

        private boolean carriesAlternateAllele(String genotype) {
            String[] alleles = genotype.trim().split("[/|]");
            boolean sawCalledAllele = false;
            for (String allele : alleles) {
                if (!StringUtils.hasText(allele) || ".".equals(allele.trim())) {
                    continue;
                }
                sawCalledAllele = true;
                if (!"0".equals(allele.trim())) {
                    return true;
                }
            }
            return !sawCalledAllele;
        }
    }
}
