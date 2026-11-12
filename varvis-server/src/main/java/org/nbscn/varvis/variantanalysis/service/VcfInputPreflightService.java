package org.nbscn.varvis.variantanalysis.service;

import htsjdk.samtools.SAMFormatException;
import htsjdk.samtools.FileTruncatedException;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.RuntimeEOFException;
import htsjdk.samtools.util.RuntimeIOException;
import htsjdk.tribble.TribbleException;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFFileReader;
import htsjdk.variant.vcf.VCFHeader;
import org.nbscn.varvis.exception.VariantAnalysisInputException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Performs a complete, sequential parse of a product VCF before an analysis job is persisted.
 * The result intentionally contains aggregate counters only and never retains variant or sample data.
 */
@Service
public class VcfInputPreflightService {

    private static final String INVALID_INPUT = "INVALID_INPUT";
    private static final String PARSE_FAILURE = "VCF_CONTENT_PARSE_FAILED";
    private static final Pattern AUTOSOME = Pattern.compile("(?:chr)?(?:[1-9]|1[0-9]|2[0-2])");
    private static final Pattern DNA_BASE = Pattern.compile("[ACGT]");

    public Result inspect(Path vcfPath, String selectedSampleId) {
        return inspect(vcfPath, selectedSampleId, false);
    }

    public Result inspectSingleVariant(Path vcfPath, String selectedSampleId) {
        return inspect(vcfPath, selectedSampleId, true);
    }

    private Result inspect(Path vcfPath, String selectedSampleId, boolean singleVariantReview) {
        Objects.requireNonNull(vcfPath, "vcfPath");
        String normalizedSampleId = StringUtils.hasText(selectedSampleId)
                ? selectedSampleId.trim()
                : null;
        Accumulator accumulator = new Accumulator();

        try (VCFFileReader reader = new VCFFileReader(vcfPath, false)) {
            VCFHeader header = reader.getFileHeader();
            if (singleVariantReview && (header == null || header.getNGenotypeSamples() != 1)) {
                throw singleVariantInputError();
            }
            accumulator.observeHeader(header);
            try (CloseableIterator<VariantContext> variants = reader.iterator()) {
                while (variants.hasNext()) {
                    if (singleVariantReview && accumulator.recordCount != 0) throw singleVariantInputError();
                    VariantContext variant = variants.next();
                    if (singleVariantReview) validateSingleVariant(variant, normalizedSampleId);
                    accumulator.observe(variant, normalizedSampleId);
                }
            }
            if (singleVariantReview && accumulator.recordCount != 1) throw singleVariantInputError();
            return accumulator.finish();
        } catch (TribbleException ex) {
            throw mapFormatFailure(ex);
        } catch (SAMFormatException ex) {
            throw mapFormatFailure(ex);
        } catch (NumberFormatException ex) {
            throw parseFailure();
        } catch (RuntimeIOException ex) {
            throw ex;
        }
    }

    private static void validateSingleVariant(VariantContext variant, String sampleId) {
        if (!AUTOSOME.matcher(variant.getContig()).matches() || !variant.isBiallelic()
                || !DNA_BASE.matcher(variant.getReference().getBaseString()).matches()
                || !DNA_BASE.matcher(variant.getAlternateAllele(0).getBaseString()).matches()) {
            throw singleVariantInputError();
        }
        Genotype genotype = sampleId == null ? null : variant.getGenotype(sampleId);
        if (genotype == null || genotype.getPloidy() != 2 || !(genotype.isHet() || genotype.isHomVar())) {
            throw singleVariantInputError();
        }
    }

    private static VariantAnalysisInputException singleVariantInputError() {
        return new VariantAnalysisInputException(INVALID_INPUT,
                "SINGLE_VARIANT_REVIEW_INPUT_INVALID: submit one autosomal biallelic SNV and one sample with a called diploid genotype carrying the alternate allele.");
    }

    private VariantAnalysisInputException parseFailure() {
        return new VariantAnalysisInputException(
                INVALID_INPUT,
                PARSE_FAILURE + ": uploaded VCF content could not be parsed completely."
        );
    }

    RuntimeException mapFormatFailure(RuntimeException failure) {
        if (Thread.currentThread().isInterrupted() || hasInfrastructureCause(failure)) {
            return failure;
        }
        if (failure instanceof TribbleException.InvalidHeader
                || failure instanceof TribbleException.InvalidDecodeLine
                || failure instanceof TribbleException.InternalCodecException
                || failure instanceof TribbleException.MalformedFeatureFile
                || failure.getClass().equals(TribbleException.class)
                || failure instanceof SAMFormatException) {
            return parseFailure();
        }
        return failure;
    }

    private boolean hasInfrastructureCause(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof IOException
                    || current instanceof UncheckedIOException
                    || current instanceof RuntimeIOException
                    || current instanceof RuntimeEOFException
                    || current instanceof FileTruncatedException
                    || current instanceof SecurityException
                    || current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class Accumulator {
        private long recordCount;
        private long alternateAlleleCount;
        private long selectedCalledGenotypeCount;
        private long selectedNoCallGenotypeCount;
        private long selectedMissingDpCount;
        private long selectedMissingGqCount;
        private long selectedMissingAdCount;
        private long multiAllelicRecordCount;
        private long symbolicAlleleCount;
        private long breakendAlleleCount;
        private long spanningDeletionAlleleCount;
        private long nonRefAlleleCount;
        private long filteredRecordCount;
        private long missingQualRecordCount;
        private long missingFilterRecordCount;
        private long missingInfoRecordCount;
        private boolean gtDeclared;
        private boolean dpDeclared;
        private boolean gqDeclared;
        private boolean adDeclared;

        private void observeHeader(VCFHeader header) {
            if (header == null) {
                return;
            }
            gtDeclared = header.hasFormatLine("GT");
            dpDeclared = header.hasFormatLine("DP");
            gqDeclared = header.hasFormatLine("GQ");
            adDeclared = header.hasFormatLine("AD");
        }

        private void observe(VariantContext variant, String selectedSampleId) {
            recordCount++;
            List<Allele> alternates = variant.getAlternateAlleles();
            alternateAlleleCount += alternates.size();
            if (alternates.size() > 1) {
                multiAllelicRecordCount++;
            }
            for (Allele alternate : alternates) {
                observeAlternate(alternate);
            }

            if (variant.isFiltered()) {
                filteredRecordCount++;
            }
            if (!variant.hasLog10PError()) {
                missingQualRecordCount++;
            }
            if (!variant.filtersWereApplied()) {
                missingFilterRecordCount++;
            }
            if (variant.getAttributes().isEmpty()) {
                missingInfoRecordCount++;
            }

            Genotype genotype = selectedSampleId != null && variant.hasGenotype(selectedSampleId)
                    ? variant.getGenotype(selectedSampleId)
                    : null;
            if (genotype == null || !genotype.isCalled()) {
                selectedNoCallGenotypeCount++;
            } else {
                selectedCalledGenotypeCount++;
            }
            if (genotype == null || !genotype.hasDP()) {
                selectedMissingDpCount++;
            }
            if (genotype == null || !genotype.hasGQ()) {
                selectedMissingGqCount++;
            }
            if (genotype == null || !genotype.hasAD()) {
                selectedMissingAdCount++;
            }
        }

        private void observeAlternate(Allele alternate) {
            if (alternate == null) {
                return;
            }
            String display = alternate.getDisplayString();
            if (alternate.isSymbolic()) {
                symbolicAlleleCount++;
            }
            if (display.indexOf('[') >= 0 || display.indexOf(']') >= 0) {
                breakendAlleleCount++;
            }
            if (Allele.SPAN_DEL.equals(alternate) || "*".equals(display)) {
                spanningDeletionAlleleCount++;
            }
            if (Allele.NON_REF_ALLELE.equals(alternate) || "<NON_REF>".equals(display)) {
                nonRefAlleleCount++;
            }
        }

        private Result finish() {
            List<String> warnings = new ArrayList<String>();
            addWarning(!gtDeclared, warnings, "FORMAT_GT_NOT_DECLARED");
            addWarning(!dpDeclared, warnings, "FORMAT_DP_NOT_DECLARED");
            addWarning(!gqDeclared, warnings, "FORMAT_GQ_NOT_DECLARED");
            addWarning(!adDeclared, warnings, "FORMAT_AD_NOT_DECLARED");
            addWarning(recordCount == 0, warnings, "NO_VARIANT_RECORDS");
            addWarning(recordCount > 0 && alternateAlleleCount == 0, warnings, "NO_ALTERNATE_ALLELES");
            addWarning(selectedNoCallGenotypeCount > 0, warnings, "SELECTED_GENOTYPE_NO_CALL_PRESENT");
            addWarning(selectedMissingDpCount > 0, warnings, "SELECTED_GENOTYPE_DP_MISSING");
            addWarning(selectedMissingGqCount > 0, warnings, "SELECTED_GENOTYPE_GQ_MISSING");
            addWarning(selectedMissingAdCount > 0, warnings, "SELECTED_GENOTYPE_AD_MISSING");
            addWarning(multiAllelicRecordCount > 0, warnings, "MULTI_ALLELIC_RECORDS_PRESENT");
            addWarning(symbolicAlleleCount > 0, warnings, "SYMBOLIC_ALLELES_PRESENT");
            addWarning(breakendAlleleCount > 0, warnings, "BREAKEND_ALLELES_PRESENT");
            addWarning(spanningDeletionAlleleCount > 0, warnings, "SPANNING_DELETION_ALLELES_PRESENT");
            addWarning(nonRefAlleleCount > 0, warnings, "NON_REF_ALLELES_PRESENT");
            addWarning(filteredRecordCount > 0, warnings, "FILTERED_RECORDS_PRESENT");
            addWarning(missingQualRecordCount > 0, warnings, "MISSING_QUAL_PRESENT");
            addWarning(missingFilterRecordCount > 0, warnings, "MISSING_FILTER_PRESENT");
            addWarning(missingInfoRecordCount > 0, warnings, "MISSING_INFO_PRESENT");
            return new Result(
                    recordCount,
                    alternateAlleleCount,
                    selectedCalledGenotypeCount,
                    selectedNoCallGenotypeCount,
                    multiAllelicRecordCount,
                    symbolicAlleleCount,
                    breakendAlleleCount,
                    spanningDeletionAlleleCount,
                    nonRefAlleleCount,
                    filteredRecordCount,
                    missingQualRecordCount,
                    missingFilterRecordCount,
                    missingInfoRecordCount,
                    warnings
            );
        }

        private void addWarning(boolean condition, List<String> warnings, String warning) {
            if (condition) {
                warnings.add(warning);
            }
        }
    }

    public static final class Result {
        private final long recordCount;
        private final long alternateAlleleCount;
        private final long selectedCalledGenotypeCount;
        private final long selectedNoCallGenotypeCount;
        private final long multiAllelicRecordCount;
        private final long symbolicAlleleCount;
        private final long breakendAlleleCount;
        private final long spanningDeletionAlleleCount;
        private final long nonRefAlleleCount;
        private final long filteredRecordCount;
        private final long missingQualRecordCount;
        private final long missingFilterRecordCount;
        private final long missingInfoRecordCount;
        private final List<String> warnings;

        private Result(long recordCount,
                       long alternateAlleleCount,
                       long selectedCalledGenotypeCount,
                       long selectedNoCallGenotypeCount,
                       long multiAllelicRecordCount,
                       long symbolicAlleleCount,
                       long breakendAlleleCount,
                       long spanningDeletionAlleleCount,
                       long nonRefAlleleCount,
                       long filteredRecordCount,
                       long missingQualRecordCount,
                       long missingFilterRecordCount,
                       long missingInfoRecordCount,
                       List<String> warnings) {
            this.recordCount = recordCount;
            this.alternateAlleleCount = alternateAlleleCount;
            this.selectedCalledGenotypeCount = selectedCalledGenotypeCount;
            this.selectedNoCallGenotypeCount = selectedNoCallGenotypeCount;
            this.multiAllelicRecordCount = multiAllelicRecordCount;
            this.symbolicAlleleCount = symbolicAlleleCount;
            this.breakendAlleleCount = breakendAlleleCount;
            this.spanningDeletionAlleleCount = spanningDeletionAlleleCount;
            this.nonRefAlleleCount = nonRefAlleleCount;
            this.filteredRecordCount = filteredRecordCount;
            this.missingQualRecordCount = missingQualRecordCount;
            this.missingFilterRecordCount = missingFilterRecordCount;
            this.missingInfoRecordCount = missingInfoRecordCount;
            this.warnings = warnings == null || warnings.isEmpty()
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(warnings));
        }

        public long getRecordCount() { return recordCount; }
        public long getAlternateAlleleCount() { return alternateAlleleCount; }
        public long getSelectedCalledGenotypeCount() { return selectedCalledGenotypeCount; }
        public long getSelectedNoCallGenotypeCount() { return selectedNoCallGenotypeCount; }
        public long getMultiAllelicRecordCount() { return multiAllelicRecordCount; }
        public long getSymbolicAlleleCount() { return symbolicAlleleCount; }
        public long getBreakendAlleleCount() { return breakendAlleleCount; }
        public long getSpanningDeletionAlleleCount() { return spanningDeletionAlleleCount; }
        public long getNonRefAlleleCount() { return nonRefAlleleCount; }
        public long getFilteredRecordCount() { return filteredRecordCount; }
        public long getMissingQualRecordCount() { return missingQualRecordCount; }
        public long getMissingFilterRecordCount() { return missingFilterRecordCount; }
        public long getMissingInfoRecordCount() { return missingInfoRecordCount; }
        public List<String> getWarnings() { return warnings; }

        public String safeEventMessage() {
            return "Input content preflight passed: records=" + recordCount
                    + ", alternateAlleles=" + alternateAlleleCount
                    + ", selectedCalledGenotypes=" + selectedCalledGenotypeCount
                    + ", warnings=" + warnings.size();
        }
    }
}
