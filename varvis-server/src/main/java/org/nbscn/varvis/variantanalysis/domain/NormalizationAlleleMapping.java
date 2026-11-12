package org.nbscn.varvis.variantanalysis.domain;

import org.nbscn.varvis.utils.CoordinateUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Reconstructs the durable raw-record to normalized-allele mapping stored on an
 * observation. This object is created on demand so large analyses do not keep a
 * second per-allele collection in memory.
 */
public class NormalizationAlleleMapping {

    public static final String IDENTITY = "IDENTITY";
    public static final String NORMALIZED = "NORMALIZED";
    public static final String UNRESOLVED_ORIGINAL = "UNRESOLVED_ORIGINAL";

    private final String rawRecordId;
    private final String rawChromosome;
    private final Integer rawPosition;
    private final String rawReference;
    private final List<String> rawAlternates;
    private final Integer rawAlternateIndex;
    private final NormalizedVariantKey normalizedKey;
    private final String canonicalVariantId;
    private final String status;

    private NormalizationAlleleMapping(String rawRecordId,
                                       String rawChromosome,
                                       Integer rawPosition,
                                       String rawReference,
                                       List<String> rawAlternates,
                                       Integer rawAlternateIndex,
                                       NormalizedVariantKey normalizedKey,
                                       String status) {
        this.rawRecordId = rawRecordId;
        this.rawChromosome = rawChromosome;
        this.rawPosition = rawPosition;
        this.rawReference = rawReference;
        this.rawAlternates = rawAlternates == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(rawAlternates));
        this.rawAlternateIndex = rawAlternateIndex;
        this.normalizedKey = normalizedKey;
        this.canonicalVariantId = normalizedKey == null ? null : normalizedKey.canonicalId();
        this.status = status;
    }

    public static NormalizationAlleleMapping from(String originalRecord,
                                                  NormalizedVariantKey normalizedKey) {
        if (originalRecord == null || originalRecord.trim().isEmpty() || normalizedKey == null) {
            return unresolved(normalizedKey);
        }
        String[] fields = originalRecord.trim().split("\\|", -1);
        if (fields.length != 4 && fields.length != 5) {
            return unresolved(normalizedKey);
        }
        try {
            int rawPosition = Integer.parseInt(fields[1]);
            List<String> rawAlternates = splitAlternates(fields[3]);
            int rawAlternateIndex = fields.length == 5
                    ? Integer.parseInt(fields[4])
                    : rawAlternates.size() == 1 ? 1 : -1;
            if (rawPosition <= 0 || fields[0].trim().isEmpty() || fields[2].trim().isEmpty()
                    || rawAlternateIndex <= 0 || rawAlternateIndex > rawAlternates.size()) {
                return unresolved(normalizedKey);
            }
            String rawRecordId = fields[0] + "|" + rawPosition + "|" + fields[2] + "|" + fields[3];
            String selectedRawAlternate = rawAlternates.get(rawAlternateIndex - 1);
            String status = equivalent(
                    fields[0],
                    rawPosition,
                    fields[2],
                    selectedRawAlternate,
                    normalizedKey
            ) ? IDENTITY : NORMALIZED;
            return new NormalizationAlleleMapping(
                    rawRecordId,
                    fields[0],
                    rawPosition,
                    fields[2],
                    rawAlternates,
                    rawAlternateIndex,
                    normalizedKey,
                    status
            );
        } catch (NumberFormatException ex) {
            return unresolved(normalizedKey);
        }
    }

    private static NormalizationAlleleMapping unresolved(NormalizedVariantKey normalizedKey) {
        return new NormalizationAlleleMapping(
                null, null, null, null, Collections.<String>emptyList(), null,
                normalizedKey, UNRESOLVED_ORIGINAL
        );
    }

    private static List<String> splitAlternates(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String[] values = value.split(",", -1);
        List<String> result = new ArrayList<String>(values.length);
        for (String alternate : values) {
            String normalized = alternate == null ? null : alternate.trim();
            if (normalized == null || normalized.isEmpty()) {
                return Collections.emptyList();
            }
            result.add(normalized);
        }
        return result;
    }

    private static boolean equivalent(String rawChromosome,
                                      int rawPosition,
                                      String rawReference,
                                      String rawAlternate,
                                      NormalizedVariantKey normalizedKey) {
        return rawPosition == normalizedKey.getPosition()
                && equalsIgnoreCase(CoordinateUtils.normalizeChromosome(rawChromosome),
                normalizedKey.getChromosome())
                && equalsIgnoreCase(rawReference, normalizedKey.getReference())
                && equalsIgnoreCase(rawAlternate, normalizedKey.getAlternate());
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null
                && left.toUpperCase(Locale.ROOT).equals(right.toUpperCase(Locale.ROOT));
    }

    public String getRawRecordId() { return rawRecordId; }
    public String getRawChromosome() { return rawChromosome; }
    public Integer getRawPosition() { return rawPosition; }
    public String getRawReference() { return rawReference; }
    public List<String> getRawAlternates() { return rawAlternates; }
    public Integer getRawAlternateIndex() { return rawAlternateIndex; }
    public NormalizedVariantKey getNormalizedKey() { return normalizedKey; }
    public String getCanonicalVariantId() { return canonicalVariantId; }
    public String getStatus() { return status; }
    public boolean isResolved() { return !UNRESOLVED_ORIGINAL.equals(status); }
}
