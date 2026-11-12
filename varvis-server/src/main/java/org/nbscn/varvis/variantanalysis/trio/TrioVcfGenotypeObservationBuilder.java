package org.nbscn.varvis.variantanalysis.trio;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TrioVcfGenotypeObservationBuilder {

    public TrioVcfParseResult parse(TrioVcfParseRequest request) {
        List<TrioVcfParseIssue> issues = new ArrayList<TrioVcfParseIssue>();
        if (request == null) {
            issues.add(error("TRIO_VCF_REQUEST_MISSING", "Trio VCF parse request is required.", null, null));
            return new TrioVcfParseResult(null, new TrioAnalysisInput(null, null), null, issues);
        }
        if (request.getInputMode() == TrioInputMode.JOINT_VCF) {
            return parseJoint(request, issues);
        }
        if (request.getInputMode() == TrioInputMode.THREE_SINGLE_SAMPLE_VCFS) {
            return parseThreeSingleSample(request, issues);
        }
        issues.add(error("TRIO_VCF_INPUT_MODE_UNSUPPORTED",
                "Only JOINT_VCF and THREE_SINGLE_SAMPLE_VCFS are supported by the VCF parser.",
                null,
                null));
        return new TrioVcfParseResult(request, new TrioAnalysisInput(request.getMembers(), null), null, issues);
    }

    private TrioVcfParseResult parseJoint(TrioVcfParseRequest request, List<TrioVcfParseIssue> issues) {
        ParsedVcf vcf = parseVcf(request, request.getJointVcfPath(), issues);
        List<TrioVariantObservation> variants = new ArrayList<TrioVariantObservation>();
        List<TrioVcfGenotypeRecord> genotypeRecords = new ArrayList<TrioVcfGenotypeRecord>();
        validateRequiredSamples(vcf, request.getSampleMapping(), issues);
        for (VcfVariantLine line : vcf.getLines()) {
            Map<TrioRole, TrioGenotypeObservation> genotypes =
                    new EnumMap<TrioRole, TrioGenotypeObservation>(TrioRole.class);
            for (TrioRole role : Arrays.asList(TrioRole.PROBAND, TrioRole.FATHER, TrioRole.MOTHER)) {
                TrioVcfGenotypeRecord record = parseRoleSample(request, line, role, issues);
                genotypeRecords.add(record);
                genotypes.put(role, record.getObservation());
            }
            variants.add(new TrioVariantObservation(
                    variantId(line),
                    request.geneSymbolFor(line.getKey(), line.getGeneSymbol()),
                    line.getKey().getChromosome(),
                    line.getKey().getPosition(),
                    genotypes));
        }
        return new TrioVcfParseResult(
                request,
                new TrioAnalysisInput(request.getMembers(), variants),
                genotypeRecords,
                issues);
    }

    private TrioVcfParseResult parseThreeSingleSample(TrioVcfParseRequest request, List<TrioVcfParseIssue> issues) {
        ParsedVcf proband = parseVcf(request, request.getProbandVcfPath(), issues);
        ParsedVcf father = parseVcf(request, request.getFatherVcfPath(), issues);
        ParsedVcf mother = parseVcf(request, request.getMotherVcfPath(), issues);
        validateSingleSample(proband, request.getSampleMapping().sampleId(TrioRole.PROBAND), issues);
        validateSingleSample(father, request.getSampleMapping().sampleId(TrioRole.FATHER), issues);
        validateSingleSample(mother, request.getSampleMapping().sampleId(TrioRole.MOTHER), issues);

        List<TrioVariantObservation> variants = new ArrayList<TrioVariantObservation>();
        List<TrioVcfGenotypeRecord> genotypeRecords = new ArrayList<TrioVcfGenotypeRecord>();
        for (VcfVariantLine probandLine : proband.getLines()) {
            TrioVcfVariantKey key = probandLine.getKey();
            Map<TrioRole, TrioGenotypeObservation> genotypes =
                    new EnumMap<TrioRole, TrioGenotypeObservation>(TrioRole.class);

            TrioVcfGenotypeRecord probandRecord =
                    parseRoleSample(request, probandLine, TrioRole.PROBAND, issues);
            TrioVcfGenotypeRecord fatherRecord =
                    parseRoleFromOptionalLine(request, father.lineByKey(key), key, probandLine, TrioRole.FATHER, issues);
            TrioVcfGenotypeRecord motherRecord =
                    parseRoleFromOptionalLine(request, mother.lineByKey(key), key, probandLine, TrioRole.MOTHER, issues);

            genotypeRecords.add(probandRecord);
            genotypeRecords.add(fatherRecord);
            genotypeRecords.add(motherRecord);
            genotypes.put(TrioRole.PROBAND, probandRecord.getObservation());
            genotypes.put(TrioRole.FATHER, fatherRecord.getObservation());
            genotypes.put(TrioRole.MOTHER, motherRecord.getObservation());

            variants.add(new TrioVariantObservation(
                    variantId(probandLine),
                    request.geneSymbolFor(key, probandLine.getGeneSymbol()),
                    key.getChromosome(),
                    key.getPosition(),
                    genotypes));
        }

        return new TrioVcfParseResult(
                request,
                new TrioAnalysisInput(request.getMembers(), variants),
                genotypeRecords,
                issues);
    }

    private ParsedVcf parseVcf(TrioVcfParseRequest request,
                               Path path,
                               List<TrioVcfParseIssue> issues) {
        if (path == null) {
            issues.add(error("TRIO_VCF_PATH_MISSING", "VCF path is required.", null, null));
            return new ParsedVcf(Collections.<String>emptyList(), Collections.<VcfVariantLine>emptyList());
        }
        List<String> sampleNames = new ArrayList<String>();
        List<VcfVariantLine> lines = new ArrayList<VcfVariantLine>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("##") || line.trim().isEmpty()) {
                    continue;
                }
                if (line.startsWith("#CHROM")) {
                    String[] columns = line.split("\t", -1);
                    for (int i = 9; i < columns.length; i++) {
                        sampleNames.add(columns[i]);
                    }
                    continue;
                }
                String[] columns = line.split("\t", -1);
                if (columns.length < 8) {
                    issues.add(error("TRIO_VCF_RECORD_MALFORMED",
                            "VCF record has fewer than 8 required columns.",
                            null,
                            null));
                    continue;
                }
                long position = parseLong(columns[1], -1L);
                if (position <= 0L) {
                    issues.add(error("TRIO_VCF_POSITION_INVALID",
                            "VCF record has invalid POS value: " + columns[1],
                            null,
                            null));
                    continue;
                }
                String[] alternates = columns[4].split(",");
                String format = columns.length > 8 ? columns[8] : "";
                Map<String, String> samples = new LinkedHashMap<String, String>();
                for (int i = 9; i < columns.length && i - 9 < sampleNames.size(); i++) {
                    samples.put(sampleNames.get(i - 9), columns[i]);
                }
                for (int i = 0; i < alternates.length; i++) {
                    TrioVcfVariantKey key =
                            new TrioVcfVariantKey(
                                    request.getAssembly(), columns[0], position, columns[3], alternates[i]);
                    if (!request.accepts(key)) {
                        continue;
                    }
                    lines.add(new VcfVariantLine(
                            key,
                            i + 1,
                            alternates.length > 1,
                            columns[2],
                            columns[6],
                            columns[7],
                            format,
                            samples));
                }
            }
        } catch (IOException e) {
            issues.add(error("TRIO_VCF_READ_FAILED", "Unable to read VCF: " + e.getMessage(), null, null));
        }
        return new ParsedVcf(sampleNames, lines);
    }

    private void validateRequiredSamples(ParsedVcf vcf,
                                         TrioVcfSampleMapping mapping,
                                         List<TrioVcfParseIssue> issues) {
        if (mapping == null) {
            issues.add(error("TRIO_VCF_SAMPLE_MAPPING_MISSING", "Sample mapping is required.", null, null));
            return;
        }
        for (TrioRole role : Arrays.asList(TrioRole.PROBAND, TrioRole.FATHER, TrioRole.MOTHER)) {
            String sampleId = mapping.sampleId(role);
            if (isBlank(sampleId) || !vcf.getSampleNames().contains(sampleId)) {
                issues.add(error("TRIO_VCF_SAMPLE_MISSING",
                        role.name() + " sample is not present in the VCF header: " + sampleId,
                        null,
                        sampleId));
            }
        }
    }

    private void validateSingleSample(ParsedVcf vcf, String sampleId, List<TrioVcfParseIssue> issues) {
        if (isBlank(sampleId) || !vcf.getSampleNames().contains(sampleId)) {
            issues.add(error("TRIO_VCF_SAMPLE_MISSING",
                    "Single-sample VCF does not contain expected sample: " + sampleId,
                    null,
                    sampleId));
        }
    }

    private TrioVcfGenotypeRecord parseRoleFromOptionalLine(TrioVcfParseRequest request,
                                                            VcfVariantLine line,
                                                            TrioVcfVariantKey key,
                                                            VcfVariantLine probandLine,
                                                            TrioRole role,
                                                            List<TrioVcfParseIssue> issues) {
        if (line == null) {
            String sampleId = request.getSampleMapping().sampleId(role);
            issues.add(warning("TRIO_VCF_PARENT_VARIANT_UNOBSERVED",
                    role.name() + " VCF does not contain " + key.stableVariantId()
                            + "; absence is not treated as confirmed 0/0.",
                    key,
                    sampleId));
            return new TrioVcfGenotypeRecord(
                    key,
                    role,
                    sampleId,
                    probandLine.getGeneSymbol(),
                    probandLine.getRecordFilter(),
                    null,
                    null,
                    TrioGenotypeObservation.unobserved(role));
        }
        return parseRoleSample(request, line, role, issues);
    }

    private TrioVcfGenotypeRecord parseRoleSample(TrioVcfParseRequest request,
                                                  VcfVariantLine line,
                                                  TrioRole role,
                                                  List<TrioVcfParseIssue> issues) {
        String sampleId = request.getSampleMapping() == null ? null : request.getSampleMapping().sampleId(role);
        if (isBlank(sampleId) || !line.getSamples().containsKey(sampleId)) {
            issues.add(error("TRIO_VCF_SAMPLE_MISSING",
                    role.name() + " sample value is missing for " + line.getKey().stableVariantId(),
                    line.getKey(),
                    sampleId));
            return new TrioVcfGenotypeRecord(
                    line.getKey(),
                    role,
                    sampleId,
                    line.getGeneSymbol(),
                    line.getRecordFilter(),
                    null,
                    null,
                    TrioGenotypeObservation.noCall(role));
        }
        String sampleValue = line.getSamples().get(sampleId);
        warnOnFormatSampleMismatch(line, sampleValue, sampleId, issues);
        Map<String, String> formatValues = formatValues(line.getFormat(), sampleValue);
        if (!formatValues.containsKey("GT")) {
            issues.add(warning("TRIO_VCF_GT_MISSING",
                    "FORMAT/sample value does not contain GT; genotype is treated as no-call.",
                    line.getKey(),
                    sampleId));
        }
        String gt = formatValues.get("GT");
        Integer dp = parseInteger(formatValues.get("DP"));
        Integer gq = parseInteger(formatValues.get("GQ"));
        String ad = formatValues.get("AD");
        String sampleFilter = formatValues.get("FT");
        TrioGenotypeCall call = parseCall(request, line, gt, dp, gq, ad, sampleId, issues);
        TrioGenotypeObservation observation =
                new TrioGenotypeObservation(role, gt, call, dp, gq, ad, gt != null && gt.indexOf('|') >= 0);
        return new TrioVcfGenotypeRecord(
                line.getKey(),
                role,
                sampleId,
                line.getGeneSymbol(),
                line.getRecordFilter(),
                sampleFilter,
                sampleValue,
                observation);
    }

    private TrioGenotypeCall parseCall(TrioVcfParseRequest request,
                                       VcfVariantLine line,
                                       String gt,
                                       Integer depth,
                                       Integer genotypeQuality,
                                       String alleleDepth,
                                       String sampleId,
                                       List<TrioVcfParseIssue> issues) {
        if (isBlank(gt) || ".".equals(gt) || "./.".equals(gt) || ".|.".equals(gt)) {
            return TrioGenotypeCall.NO_CALL;
        }
        String unphased = gt.replace('|', '/');
        String[] alleles = unphased.split("/", -1);
        int targetAltCount = 0;
        int refCount = 0;
        int otherAltCount = 0;
        int missingCount = 0;
        for (String allele : alleles) {
            if (".".equals(allele) || allele.trim().isEmpty()) {
                missingCount++;
                continue;
            }
            Integer alleleIndex = parseInteger(allele);
            if (alleleIndex == null) {
                issues.add(warning("TRIO_VCF_GT_UNSUPPORTED",
                        "Unsupported GT allele value: " + gt,
                        line.getKey(),
                        sampleId));
                return TrioGenotypeCall.OTHER;
            }
            if (alleleIndex.intValue() == 0) {
                refCount++;
            } else if (alleleIndex.intValue() == line.getAltIndex()) {
                targetAltCount++;
            } else {
                otherAltCount++;
            }
        }

        if (missingCount > 0) {
            if (targetAltCount > 0) {
                issues.add(warning("TRIO_VCF_HAPLOID_PARTIAL_GT",
                        "GT carries the target ALT with a missing allele; parsed as hemizygous/partial ALT.",
                        line.getKey(),
                        sampleId));
                return TrioGenotypeCall.HEMI_ALT;
            }
            return TrioGenotypeCall.NO_CALL;
        }

        if (alleles.length == 1) {
            if (targetAltCount == 1) {
                return TrioGenotypeCall.HEMI_ALT;
            }
            if (refCount == 1) {
                return referenceCall(request, line, depth, genotypeQuality, alleleDepth, sampleId, issues);
            }
            issues.add(warning("TRIO_VCF_MULTIALLELIC_OTHER_ALT",
                    "Haploid GT carries a different ALT allele than " + line.getKey().getAlternate(),
                    line.getKey(),
                    sampleId));
            return TrioGenotypeCall.OTHER;
        }

        if (targetAltCount == alleles.length) {
            return TrioGenotypeCall.HOM_ALT;
        }
        if (targetAltCount > 0 && otherAltCount > 0) {
            issues.add(warning("TRIO_VCF_MULTIALLELIC_COMPLEX_GT",
                    "GT carries the target ALT and another ALT allele; current ALT is retained with a warning.",
                    line.getKey(),
                    sampleId));
            return TrioGenotypeCall.HET_ALT;
        }
        if (targetAltCount > 0) {
            return TrioGenotypeCall.HET_ALT;
        }
        if (otherAltCount > 0) {
            issues.add(warning("TRIO_VCF_MULTIALLELIC_OTHER_ALT",
                    "GT carries another ALT allele in the same multi-ALT record; this ALT is not treated as reference.",
                    line.getKey(),
                    sampleId));
            return TrioGenotypeCall.OTHER;
        }
        return referenceCall(request, line, depth, genotypeQuality, alleleDepth, sampleId, issues);
    }

    private TrioGenotypeCall referenceCall(TrioVcfParseRequest request,
                                           VcfVariantLine line,
                                           Integer depth,
                                           Integer genotypeQuality,
                                           String alleleDepth,
                                           String sampleId,
                                           List<TrioVcfParseIssue> issues) {
        if (confirmedReference(request, depth, genotypeQuality, alleleDepth, line.getAltIndex())) {
            return TrioGenotypeCall.HOM_REF;
        }
        warnOnUnconfirmedReference(request, line, depth, genotypeQuality, alleleDepth, sampleId, issues);
        return TrioGenotypeCall.UNCONFIRMED_HOM_REF;
    }

    private boolean confirmedReference(TrioVcfParseRequest request,
                                       Integer depth,
                                       Integer genotypeQuality,
                                       String alleleDepth,
                                       int altIndex) {
        if (depth == null || depth.intValue() < request.getMinDepthForConfirmedRef()) {
            return false;
        }
        if (genotypeQuality == null || genotypeQuality.intValue() < request.getMinGqForConfirmedRef()) {
            return false;
        }
        Integer altDepth = altDepth(alleleDepth, altIndex);
        return altDepth == null || altDepth.intValue() == 0;
    }

    private void warnOnUnconfirmedReference(TrioVcfParseRequest request,
                                            VcfVariantLine line,
                                            Integer depth,
                                            Integer genotypeQuality,
                                            String alleleDepth,
                                            String sampleId,
                                            List<TrioVcfParseIssue> issues) {
        Integer altDepth = altDepth(alleleDepth, line.getAltIndex());
        if (altDepth != null && altDepth.intValue() > 0) {
            issues.add(warning("TRIO_VCF_REFERENCE_ALT_SUPPORT",
                    "GT is reference but AD contains target ALT read support; reference is not confirmed.",
                    line.getKey(),
                    sampleId));
            return;
        }
        if (depth == null || genotypeQuality == null) {
            issues.add(warning("TRIO_VCF_REFERENCE_QUALITY_MISSING",
                    "GT is reference but DP or GQ is missing; reference is not confirmed.",
                    line.getKey(),
                    sampleId));
            return;
        }
        if (depth.intValue() < request.getMinDepthForConfirmedRef()
                || genotypeQuality.intValue() < request.getMinGqForConfirmedRef()) {
            issues.add(warning("TRIO_VCF_REFERENCE_LOW_QUALITY",
                    "GT is reference but DP/GQ is below the confirmed-reference threshold.",
                    line.getKey(),
                    sampleId));
        }
    }

    private void warnOnFormatSampleMismatch(VcfVariantLine line,
                                            String sampleValue,
                                            String sampleId,
                                            List<TrioVcfParseIssue> issues) {
        if (isBlank(line.getFormat()) || isBlank(sampleValue)) {
            return;
        }
        int formatCount = line.getFormat().split(":", -1).length;
        int sampleCount = sampleValue.split(":", -1).length;
        if (sampleCount < formatCount) {
            issues.add(warning("TRIO_VCF_FORMAT_SAMPLE_LENGTH_MISMATCH",
                    "FORMAT has more fields than the sample value; missing fields are parsed as unavailable.",
                    line.getKey(),
                    sampleId));
        }
    }

    private Integer altDepth(String alleleDepth, int altIndex) {
        if (isBlank(alleleDepth)) {
            return null;
        }
        String[] values = alleleDepth.split(",", -1);
        if (altIndex < 1 || altIndex >= values.length) {
            return null;
        }
        return parseInteger(values[altIndex]);
    }

    private Map<String, String> formatValues(String format, String sampleValue) {
        if (isBlank(format) || isBlank(sampleValue)) {
            return Collections.emptyMap();
        }
        String[] keys = format.split(":", -1);
        String[] values = sampleValue.split(":", -1);
        Map<String, String> result = new HashMap<String, String>();
        for (int i = 0; i < keys.length; i++) {
            result.put(keys[i], i < values.length ? values[i] : "");
        }
        return result;
    }

    private Integer parseInteger(String value) {
        if (isBlank(value) || ".".equals(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String variantId(VcfVariantLine line) {
        if (line.isMultiAlt()) {
            return line.getKey().stableVariantId();
        }
        if (!isBlank(line.getId()) && !".".equals(line.getId())) {
            return line.getId();
        }
        return line.getKey().stableVariantId();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private TrioVcfParseIssue error(String code, String message, TrioVcfVariantKey key, String sampleId) {
        return new TrioVcfParseIssue(TrioVcfParseSeverity.ERROR, code, message, key, sampleId);
    }

    private TrioVcfParseIssue warning(String code, String message, TrioVcfVariantKey key, String sampleId) {
        return new TrioVcfParseIssue(TrioVcfParseSeverity.WARNING, code, message, key, sampleId);
    }

    private static class ParsedVcf {
        private final List<String> sampleNames;
        private final List<VcfVariantLine> lines;
        private final Map<TrioVcfVariantKey, VcfVariantLine> linesByKey;

        ParsedVcf(List<String> sampleNames, List<VcfVariantLine> lines) {
            this.sampleNames = sampleNames == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(sampleNames));
            this.lines = lines == null
                    ? Collections.<VcfVariantLine>emptyList()
                    : Collections.unmodifiableList(new ArrayList<VcfVariantLine>(lines));
            this.linesByKey = new LinkedHashMap<TrioVcfVariantKey, VcfVariantLine>();
            for (VcfVariantLine line : this.lines) {
                this.linesByKey.put(line.getKey(), line);
            }
        }

        List<String> getSampleNames() {
            return sampleNames;
        }

        List<VcfVariantLine> getLines() {
            return lines;
        }

        VcfVariantLine lineByKey(TrioVcfVariantKey key) {
            return linesByKey.get(key);
        }
    }

    private static class VcfVariantLine {
        private final TrioVcfVariantKey key;
        private final int altIndex;
        private final boolean multiAlt;
        private final String id;
        private final String recordFilter;
        private final String info;
        private final String format;
        private final Map<String, String> samples;

        VcfVariantLine(TrioVcfVariantKey key,
                       int altIndex,
                       boolean multiAlt,
                       String id,
                       String recordFilter,
                       String info,
                       String format,
                       Map<String, String> samples) {
            this.key = key;
            this.altIndex = altIndex;
            this.multiAlt = multiAlt;
            this.id = id;
            this.recordFilter = recordFilter;
            this.info = info;
            this.format = format;
            this.samples = samples == null
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, String>(samples));
        }

        TrioVcfVariantKey getKey() {
            return key;
        }

        int getAltIndex() {
            return altIndex;
        }

        String getId() {
            return id;
        }

        boolean isMultiAlt() {
            return multiAlt;
        }

        String getRecordFilter() {
            return recordFilter;
        }

        String getFormat() {
            return format;
        }

        Map<String, String> getSamples() {
            return samples;
        }

        String getGeneSymbol() {
            if (info == null) {
                return null;
            }
            for (String part : info.split(";")) {
                if (part.startsWith("GENE=")) {
                    return part.substring("GENE=".length());
                }
                if (part.startsWith("SYMBOL=")) {
                    return part.substring("SYMBOL=".length());
                }
            }
            return null;
        }
    }
}
