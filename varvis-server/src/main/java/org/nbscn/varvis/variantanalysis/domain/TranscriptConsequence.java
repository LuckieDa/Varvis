package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Published candidate byte identities must not depend on JVM getter discovery order.
// Keep the historical field order, including the two derived HGVS aliases.
@JsonPropertyOrder({"geneSymbol", "entrezGeneId", "transcriptAccession", "hgvsCoding", "hgvsProtein",
        "consequence", "geneId", "transcriptId", "transcriptSource", "impact", "exon", "intron",
        "canonical", "maneSelect", "manePlusClinical", "proteinPosition", "aminoAcids", "codons",
        "lof", "lofFilter", "lofFlags", "source", "status", "version", "timestamp",
        "synonymousExonPosition", "hgvsp", "hgvsc"})
public class TranscriptConsequence {

    private static final Pattern VERSIONED_IDENTIFIER = Pattern.compile("^(.+)\\.(\\d+)$");
    private static final Pattern SINGLE_PROTEIN_POSITION = Pattern.compile("^\\d+$");
    private static final Pattern SINGLE_AMINO_ACID_CHANGE = Pattern.compile("^([^/]+)/([^/]+)$");
    private static final Pattern SIMPLE_INTRONIC_SUBSTITUTION = Pattern.compile(
            "^(?:[^:]+:)?(?:c|n)\\.(?:\\*?-?\\d+)([+-])(\\d+)[A-Za-z*]+>[A-Za-z*]+$");

    private final String geneSymbol;
    private final String entrezGeneId;
    private final String transcriptAccession;
    private final String hgvsCoding;
    private final String hgvsProtein;
    private final String consequence;
    private final String geneId;
    private final String transcriptId;
    private final String transcriptSource;
    private final String impact;
    private final String exon;
    private final String intron;
    private final Boolean canonical;
    private final Boolean maneSelect;
    private final Boolean manePlusClinical;
    private final String proteinPosition;
    private final String aminoAcids;
    private final String codons;
    private final String lof;
    private final String lofFilter;
    private final String lofFlags;
    private final String source;
    private final String status;
    private final String version;
    private final Instant timestamp;
    @JsonProperty
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private SynonymousExonPosition synonymousExonPosition;
    private final Boolean bp7SynonymousAtExonFirstBase;
    private final Integer bp7SynonymousDistanceFromExonEnd;

    public TranscriptConsequence(String geneSymbol,
                                 String entrezGeneId,
                                 String transcriptAccession,
                                 String hgvsCoding,
                                 String hgvsProtein,
                                 String consequence) {
        this(
                geneSymbol,
                entrezGeneId,
                transcriptAccession,
                hgvsCoding,
                hgvsProtein,
                consequence,
                entrezGeneId,
                transcriptAccession,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    @JsonCreator
    public TranscriptConsequence(@JsonProperty("geneSymbol") String geneSymbol,
                                 @JsonProperty("entrezGeneId") String entrezGeneId,
                                 @JsonProperty("transcriptAccession") String transcriptAccession,
                                 @JsonProperty("hgvsCoding") String hgvsCoding,
                                 @JsonProperty("hgvsProtein") String hgvsProtein,
                                 @JsonProperty("consequence") String consequence,
                                 @JsonProperty("geneId") String geneId,
                                 @JsonProperty("transcriptId") String transcriptId,
                                 @JsonProperty("transcriptSource") String transcriptSource,
                                 @JsonProperty("impact") String impact,
                                 @JsonProperty("exon") String exon,
                                 @JsonProperty("intron") String intron,
                                 @JsonProperty("canonical") Boolean canonical,
                                 @JsonProperty("maneSelect") Boolean maneSelect,
                                 @JsonProperty("manePlusClinical") Boolean manePlusClinical,
                                 @JsonProperty("proteinPosition") String proteinPosition,
                                 @JsonProperty("aminoAcids") String aminoAcids,
                                 @JsonProperty("codons") String codons,
                                 @JsonProperty("lof") String lof,
                                 @JsonProperty("lofFilter") String lofFilter,
                                 @JsonProperty("lofFlags") String lofFlags,
                                 @JsonProperty("source") String source,
                                 @JsonProperty("status") String status,
                                 @JsonProperty("version") String version,
                                 @JsonProperty("timestamp") Instant timestamp) {
        this(geneSymbol, entrezGeneId, transcriptAccession, hgvsCoding, hgvsProtein,
                consequence, geneId, transcriptId, transcriptSource, impact, exon, intron,
                canonical, maneSelect, manePlusClinical, proteinPosition, aminoAcids, codons,
                lof, lofFilter, lofFlags, source, status, version, timestamp, null, null);
    }

    private TranscriptConsequence(String geneSymbol,
                                  String entrezGeneId,
                                  String transcriptAccession,
                                  String hgvsCoding,
                                  String hgvsProtein,
                                  String consequence,
                                  String geneId,
                                  String transcriptId,
                                  String transcriptSource,
                                  String impact,
                                  String exon,
                                  String intron,
                                  Boolean canonical,
                                  Boolean maneSelect,
                                  Boolean manePlusClinical,
                                  String proteinPosition,
                                  String aminoAcids,
                                  String codons,
                                  String lof,
                                  String lofFilter,
                                  String lofFlags,
                                  String source,
                                  String status,
                                  String version,
                                  Instant timestamp,
                                  Boolean bp7SynonymousAtExonFirstBase,
                                  Integer bp7SynonymousDistanceFromExonEnd) {
        this.geneSymbol = geneSymbol;
        this.entrezGeneId = entrezGeneId;
        this.transcriptAccession = transcriptAccession;
        this.hgvsCoding = hgvsCoding;
        this.hgvsProtein = hgvsProtein;
        this.consequence = consequence;
        this.geneId = geneId == null ? entrezGeneId : geneId;
        this.transcriptId = transcriptId == null ? transcriptAccession : transcriptId;
        this.transcriptSource = transcriptSource;
        this.impact = impact;
        this.exon = exon;
        this.intron = intron;
        this.canonical = canonical;
        this.maneSelect = maneSelect;
        this.manePlusClinical = manePlusClinical;
        this.proteinPosition = proteinPosition;
        this.aminoAcids = aminoAcids;
        this.codons = codons;
        this.lof = lof;
        this.lofFilter = lofFilter;
        this.lofFlags = lofFlags;
        this.source = source;
        this.status = status;
        this.version = version;
        this.timestamp = timestamp;
        this.bp7SynonymousAtExonFirstBase = bp7SynonymousAtExonFirstBase;
        this.bp7SynonymousDistanceFromExonEnd = bp7SynonymousDistanceFromExonEnd;
    }

    public String getGeneSymbol() {
        return geneSymbol;
    }

    public String getEntrezGeneId() {
        return entrezGeneId;
    }

    public String getTranscriptAccession() {
        return transcriptAccession;
    }

    public String getHgvsCoding() {
        return hgvsCoding;
    }

    public String getHgvsProtein() {
        return hgvsProtein;
    }

    public String getConsequence() {
        return consequence;
    }

    public static TranscriptConsequence ann(String geneSymbol,
                                            String geneId,
                                            String transcriptId,
                                            String transcriptSource,
                                            String consequence,
                                            String impact,
                                            String hgvsCoding,
                                            String hgvsProtein,
                                            String exon,
                                            String intron,
                                            String proteinPosition,
                                            String aminoAcids,
                                            String codons,
                                            Instant timestamp) {
        return new TranscriptConsequence(
                geneSymbol,
                geneId,
                transcriptId,
                hgvsCoding,
                hgvsProtein,
                consequence,
                geneId,
                transcriptId,
                transcriptSource,
                impact,
                exon,
                intron,
                null,
                null,
                null,
                proteinPosition,
                aminoAcids,
                codons,
                null,
                null,
                null,
                "ANN",
                "COMPLETED",
                "VCF_ANN",
                timestamp
        );
    }

    public TranscriptConsequence withAnnotationMetadata(String source,
                                                        String status,
                                                        String version,
                                                        Instant timestamp) {
        var copy = new TranscriptConsequence(
                geneSymbol,
                entrezGeneId,
                transcriptAccession,
                hgvsCoding,
                hgvsProtein,
                consequence,
                geneId,
                transcriptId,
                transcriptSource,
                impact,
                exon,
                intron,
                canonical,
                maneSelect,
                manePlusClinical,
                proteinPosition,
                aminoAcids,
                codons,
                lof,
                lofFilter,
                lofFlags,
                source,
                status,
                version,
                timestamp,
                bp7SynonymousAtExonFirstBase,
                bp7SynonymousDistanceFromExonEnd
        );
        copy.synonymousExonPosition = synonymousExonPosition;
        return copy;
    }

    /**
     * Carries an explicitly derived selected-transcript exon position for BP7.
     * The standard VEP exon Number/Total field is not sufficient to derive it.
     */
    public TranscriptConsequence withBp7SynonymousPosition(boolean atExonFirstBase,
                                                           int distanceFromExonEnd) {
        if (distanceFromExonEnd < 0) {
            throw new IllegalArgumentException("distanceFromExonEnd must be zero or greater");
        }
        return new TranscriptConsequence(
                geneSymbol, entrezGeneId, transcriptAccession, hgvsCoding, hgvsProtein,
                consequence, geneId, transcriptId, transcriptSource, impact, exon, intron,
                canonical, maneSelect, manePlusClinical, proteinPosition, aminoAcids, codons,
                lof, lofFilter, lofFlags, source, status, version, timestamp,
                atExonFirstBase, distanceFromExonEnd);
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public SynonymousExonPosition getSynonymousExonPosition() { return synonymousExonPosition; }

    public TranscriptConsequence withSynonymousExonPosition(SynonymousExonPosition position) {
        if (position == null || !position.transcriptId().equals(getVersionedTranscriptId()))
            throw new IllegalArgumentException("SYNONYMOUS_POSITION_TRANSCRIPT_MISMATCH");
        var copy = withBp7SynonymousPosition(position.distanceFromStart() == 0, position.distanceFromEnd());
        copy.synonymousExonPosition = position;
        return copy;
    }

    public static TranscriptConsequence unavailable(String summary,
                                                    String source,
                                                    String version,
                                                    Instant timestamp) {
        return statusPlaceholder(summary, source, "UNAVAILABLE", version, timestamp);
    }

    public static TranscriptConsequence statusPlaceholder(String summary,
                                                          String source,
                                                          String status,
                                                          String version,
                                                          Instant timestamp) {
        return new TranscriptConsequence(
                null,
                null,
                null,
                null,
                null,
                summary,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                source,
                status,
                version,
                timestamp
        );
    }

    public String getGeneId() {
        return geneId;
    }

    public String getTranscriptId() {
        return transcriptId;
    }

    public String getTranscriptSource() {
        return transcriptSource;
    }

    public String getImpact() {
        return impact;
    }

    public String getHgvsc() {
        return hgvsCoding;
    }

    public String getHgvsp() {
        return hgvsProtein;
    }

    /**
     * Returns the versioned transcript selected by the annotation source without
     * introducing another persisted transcript model. It accepts an already
     * versioned accession and otherwise derives the identity from HGVSc.
     */
    @JsonIgnore
    public String getVersionedTranscriptId() {
        String versioned = versionedIdentifier(transcriptAccession);
        if (versioned != null) {
            return versioned;
        }
        return versionedIdentifier(hgvsIdentity(hgvsCoding));
    }

    @JsonIgnore
    public Integer getTranscriptVersion() {
        String versioned = getVersionedTranscriptId();
        Matcher matcher = versioned == null ? null : VERSIONED_IDENTIFIER.matcher(versioned);
        if (matcher == null || !matcher.matches()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(2));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Returns the versioned protein identifier carried by HGVSp, when present. */
    @JsonIgnore
    public String getProteinIdentity() {
        return textOrNull(hgvsIdentity(hgvsProtein));
    }

    /**
     * Converts VEP's bounded missense fields (for example K/R at 34) into the
     * AlphaMissense protein-variant identity (K34R). Complex changes deliberately
     * remain unresolved.
     */
    @JsonIgnore
    public String getAminoAcidChange() {
        if (proteinPosition == null || aminoAcids == null
                || !SINGLE_PROTEIN_POSITION.matcher(proteinPosition.trim()).matches()) {
            return null;
        }
        Matcher aminoAcid = SINGLE_AMINO_ACID_CHANGE.matcher(aminoAcids.trim());
        if (!aminoAcid.matches()
                || aminoAcid.group(1).length() != 1
                || aminoAcid.group(2).length() != 1) {
            return null;
        }
        return aminoAcid.group(1) + proteinPosition.trim() + aminoAcid.group(2);
    }

    public String getExon() {
        return exon;
    }

    public String getIntron() {
        return intron;
    }

    public Boolean getCanonical() {
        return canonical;
    }

    public Boolean getManeSelect() {
        return maneSelect;
    }

    public Boolean getManePlusClinical() {
        return manePlusClinical;
    }

    public String getProteinPosition() {
        return proteinPosition;
    }

    public String getAminoAcids() {
        return aminoAcids;
    }

    public String getCodons() {
        return codons;
    }

    public String getLof() {
        return lof;
    }

    public String getLofFilter() {
        return lofFilter;
    }

    public String getLofFlags() {
        return lofFlags;
    }

    public String getSource() {
        return source;
    }

    public String getStatus() {
        return status;
    }

    public String getVersion() {
        return version;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @JsonIgnore
    public Boolean getBp7SynonymousAtExonFirstBase() {
        if (synonymousExonPosition == null) return bp7SynonymousAtExonFirstBase;
        return synonymousExonPosition.distanceFromStart() == 0;
    }

    /** 0-based distance: the final exon base is 0 and the fourth-last base is 3. */
    @JsonIgnore
    public Integer getBp7SynonymousDistanceFromExonEnd() {
        if (synonymousExonPosition == null) return bp7SynonymousDistanceFromExonEnd;
        return synonymousExonPosition.distanceFromEnd();
    }

    /** Returns a signed offset only for a simple, unambiguous intronic substitution HGVSc. */
    @JsonIgnore
    public Integer getBp7IntronicSignedOffset() {
        Matcher matcher = hgvsCoding == null
                ? null : SIMPLE_INTRONIC_SUBSTITUTION.matcher(hgvsCoding.trim());
        if (matcher == null || !matcher.matches()) {
            return null;
        }
        try {
            int offset = Integer.parseInt(matcher.group(2));
            if (offset == 0) {
                return null;
            }
            return "+".equals(matcher.group(1)) ? offset : -offset;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String hgvsIdentity(String hgvs) {
        if (hgvs == null) return null;
        int separator = hgvs.indexOf(':');
        return separator > 0 ? hgvs.substring(0, separator).trim() : null;
    }

    private String versionedIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        String normalized = identifier.trim();
        return VERSIONED_IDENTIFIER.matcher(normalized).matches() ? normalized : null;
    }

    private String textOrNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
