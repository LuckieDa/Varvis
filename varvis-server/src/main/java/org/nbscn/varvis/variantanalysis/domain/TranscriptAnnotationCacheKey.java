package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

public class TranscriptAnnotationCacheKey {

    private final String genomeAssembly;
    private final String chromosome;
    private final int position;
    private final String reference;
    private final String alternate;
    private final String vepVersion;
    private final String ensemblCacheVersion;
    private final String maneVersion;
    private final String lofteeVersion;
    private final String providerName;
    private final String providerVersion;
    private final String cacheResourceVersion;
    private final String optionsHash;

    public TranscriptAnnotationCacheKey(
            String genomeAssembly,
            String chromosome,
            int position,
            String reference,
            String alternate,
            String vepVersion,
            String ensemblCacheVersion,
            String maneVersion,
            String lofteeVersion) {
        this(
                genomeAssembly,
                chromosome,
                position,
                reference,
                alternate,
                vepVersion,
                ensemblCacheVersion,
                maneVersion,
                lofteeVersion,
                "vep",
                vepVersion,
                ensemblCacheVersion,
                legacyOptionsHash(maneVersion, lofteeVersion)
        );
    }

    @JsonCreator
    public TranscriptAnnotationCacheKey(
            @JsonProperty("genomeAssembly") String genomeAssembly,
            @JsonProperty("chromosome") String chromosome,
            @JsonProperty("position") int position,
            @JsonProperty("reference") String reference,
            @JsonProperty("alternate") String alternate,
            @JsonProperty("vepVersion") String vepVersion,
            @JsonProperty("ensemblCacheVersion") String ensemblCacheVersion,
            @JsonProperty("maneVersion") String maneVersion,
            @JsonProperty("lofteeVersion") String lofteeVersion,
            @JsonProperty("providerName") String providerName,
            @JsonProperty("providerVersion") String providerVersion,
            @JsonProperty("cacheResourceVersion") String cacheResourceVersion,
            @JsonProperty("optionsHash") String optionsHash) {
        this.genomeAssembly = normalizeAssembly(genomeAssembly);
        this.chromosome = normalizeChromosome(chromosome);
        this.position = position;
        this.reference = normalizeAllele(reference);
        this.alternate = normalizeAllele(alternate);
        this.vepVersion = vepVersion;
        this.ensemblCacheVersion = ensemblCacheVersion;
        this.maneVersion = maneVersion;
        this.lofteeVersion = lofteeVersion;
        this.providerName = normalizeProvider(providerName);
        this.providerVersion = hasText(providerVersion) ? providerVersion : vepVersion;
        this.cacheResourceVersion = hasText(cacheResourceVersion) ? cacheResourceVersion : ensemblCacheVersion;
        this.optionsHash = hasText(optionsHash) ? optionsHash : legacyOptionsHash(maneVersion, lofteeVersion);
    }

    public static TranscriptAnnotationCacheKey from(
            NormalizedVariantKey key,
            String vepVersion,
            String ensemblCacheVersion,
            String maneVersion,
            String lofteeVersion) {
        return from(key, "vep", vepVersion, ensemblCacheVersion, maneVersion, lofteeVersion,
                legacyOptionsHash(maneVersion, lofteeVersion));
    }

    public static TranscriptAnnotationCacheKey from(
            NormalizedVariantKey key,
            String providerName,
            String providerVersion,
            String cacheResourceVersion,
            String maneVersion,
            String lofteeVersion,
            String optionsHash) {
        return new TranscriptAnnotationCacheKey(
                key == null ? "GRCh38" : key.getGenomeAssembly(),
                key == null ? null : key.getChromosome(),
                key == null ? 0 : key.getPosition(),
                key == null ? null : key.getReference(),
                key == null ? null : key.getAlternate(),
                providerVersion,
                cacheResourceVersion,
                maneVersion,
                lofteeVersion,
                providerName,
                providerVersion,
                cacheResourceVersion,
                optionsHash
        );
    }

    public String getGenomeAssembly() {
        return genomeAssembly;
    }

    public String getChromosome() {
        return chromosome;
    }

    public int getPosition() {
        return position;
    }

    public String getReference() {
        return reference;
    }

    public String getAlternate() {
        return alternate;
    }

    public String getVepVersion() {
        return vepVersion;
    }

    public String getEnsemblCacheVersion() {
        return ensemblCacheVersion;
    }

    public String getManeVersion() {
        return maneVersion;
    }

    public String getLofteeVersion() {
        return lofteeVersion;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getProviderVersion() {
        return providerVersion;
    }

    public String getCacheResourceVersion() {
        return cacheResourceVersion;
    }

    public String getOptionsHash() {
        return optionsHash;
    }

    public String cacheKeyHash() {
        return sha256(
                nullToEmpty(genomeAssembly)
                        + "|" + nullToEmpty(chromosome)
                        + "|" + position
                        + "|" + nullToEmpty(reference)
                        + "|" + nullToEmpty(alternate)
                        + "|" + nullToEmpty(providerName)
                        + "|" + nullToEmpty(providerVersion)
                        + "|" + nullToEmpty(cacheResourceVersion)
                        + "|" + nullToEmpty(optionsHash)
        );
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TranscriptAnnotationCacheKey)) {
            return false;
        }
        TranscriptAnnotationCacheKey that = (TranscriptAnnotationCacheKey) other;
        return position == that.position
                && Objects.equals(genomeAssembly, that.genomeAssembly)
                && Objects.equals(chromosome, that.chromosome)
                && Objects.equals(reference, that.reference)
                && Objects.equals(alternate, that.alternate)
                && Objects.equals(vepVersion, that.vepVersion)
                && Objects.equals(ensemblCacheVersion, that.ensemblCacheVersion)
                && Objects.equals(maneVersion, that.maneVersion)
                && Objects.equals(lofteeVersion, that.lofteeVersion)
                && Objects.equals(providerName, that.providerName)
                && Objects.equals(providerVersion, that.providerVersion)
                && Objects.equals(cacheResourceVersion, that.cacheResourceVersion)
                && Objects.equals(optionsHash, that.optionsHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                genomeAssembly,
                chromosome,
                position,
                reference,
                alternate,
                vepVersion,
                ensemblCacheVersion,
                maneVersion,
                lofteeVersion,
                providerName,
                providerVersion,
                cacheResourceVersion,
                optionsHash
        );
    }

    private static String normalizeAssembly(String value) {
        if (!hasText(value)) {
            return "GRCh38";
        }
        return value.trim();
    }

    private static String normalizeChromosome(String value) {
        if (!hasText(value)) {
            return value;
        }
        String normalized = value.trim();
        if (normalized.toLowerCase(Locale.ROOT).startsWith("chr")) {
            normalized = normalized.substring(3);
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private static String normalizeAllele(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeProvider(String value) {
        return hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "vep";
    }

    private static String legacyOptionsHash(String maneVersion, String lofteeVersion) {
        return sha256("mane=" + nullToEmpty(maneVersion) + "|loftee=" + nullToEmpty(lofteeVersion));
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte current : hash) {
                builder.append(String.format("%02x", current & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest is not available", ex);
        }
    }
}
