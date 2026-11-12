package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies a pair of selected-transcript missense consequences without
 * evaluating PS1 or PM5.
 */
public final class VepProteinRelationClassifier {

    private static final Pattern SINGLE_POSITION = Pattern.compile("^\\d+$");
    private static final Pattern MISSENSE_AMINO_ACIDS = Pattern.compile("^([^/])/([^/])$");

    public Result classify(TranscriptConsequence target, TranscriptConsequence comparator) {
        ProteinChange targetChange = proteinChange(target);
        ProteinChange comparatorChange = proteinChange(comparator);
        if (targetChange == null || comparatorChange == null) {
            return Result.notEvaluated("PROTEIN_RELATION_CONTEXT_INCOMPLETE",
                    targetChange, comparatorChange);
        }
        if (!targetChange.transcriptId.equals(comparatorChange.transcriptId)) {
            return Result.evaluated(Relation.DIFFERENT_TRANSCRIPT, targetChange, comparatorChange);
        }
        if (!targetChange.proteinPosition.equals(comparatorChange.proteinPosition)) {
            return Result.evaluated(Relation.DIFFERENT_PROTEIN_POSITION, targetChange, comparatorChange);
        }
        if (!targetChange.referenceAminoAcid.equals(comparatorChange.referenceAminoAcid)) {
            return Result.notEvaluated("REFERENCE_AMINO_ACID_MISMATCH",
                    targetChange, comparatorChange);
        }
        Relation relation = targetChange.alternateAminoAcid.equals(comparatorChange.alternateAminoAcid)
                ? Relation.SAME_AMINO_ACID_CHANGE
                : Relation.SAME_RESIDUE_DIFFERENT_AMINO_ACID;
        return Result.evaluated(relation, targetChange, comparatorChange);
    }

    private ProteinChange proteinChange(TranscriptConsequence consequence) {
        if (consequence == null
                || !"COMPLETED".equals(consequence.getStatus())
                || consequence.getVersionedTranscriptId() == null
                || consequence.getProteinPosition() == null
                || !SINGLE_POSITION.matcher(consequence.getProteinPosition().trim()).matches()
                || consequence.getAminoAcids() == null) {
            return null;
        }
        Matcher aminoAcids = MISSENSE_AMINO_ACIDS.matcher(consequence.getAminoAcids().trim());
        if (!aminoAcids.matches()) {
            return null;
        }
        return new ProteinChange(
                consequence.getVersionedTranscriptId(),
                consequence.getProteinPosition().trim(),
                aminoAcids.group(1),
                aminoAcids.group(2));
    }

    public enum Relation {
        SAME_AMINO_ACID_CHANGE,
        SAME_RESIDUE_DIFFERENT_AMINO_ACID,
        DIFFERENT_TRANSCRIPT,
        DIFFERENT_PROTEIN_POSITION
    }

    public static final class Result {
        private final Relation relation;
        private final String reasonCode;
        private final ProteinChange target;
        private final ProteinChange comparator;

        private Result(Relation relation,
                       String reasonCode,
                       ProteinChange target,
                       ProteinChange comparator) {
            this.relation = relation;
            this.reasonCode = reasonCode;
            this.target = target;
            this.comparator = comparator;
        }

        private static Result evaluated(Relation relation,
                                        ProteinChange target,
                                        ProteinChange comparator) {
            return new Result(relation, null, target, comparator);
        }

        private static Result notEvaluated(String reasonCode,
                                           ProteinChange target,
                                           ProteinChange comparator) {
            return new Result(null, reasonCode, target, comparator);
        }

        public boolean isEvaluated() {
            return relation != null;
        }

        public Relation getRelation() {
            return relation;
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public ProteinChange getTarget() {
            return target;
        }

        public ProteinChange getComparator() {
            return comparator;
        }
    }

    public static final class ProteinChange {
        private final String transcriptId;
        private final String proteinPosition;
        private final String referenceAminoAcid;
        private final String alternateAminoAcid;

        private ProteinChange(String transcriptId,
                              String proteinPosition,
                              String referenceAminoAcid,
                              String alternateAminoAcid) {
            this.transcriptId = transcriptId;
            this.proteinPosition = proteinPosition;
            this.referenceAminoAcid = referenceAminoAcid;
            this.alternateAminoAcid = alternateAminoAcid;
        }

        public String getTranscriptId() {
            return transcriptId;
        }

        public String getProteinPosition() {
            return proteinPosition;
        }

        public String getReferenceAminoAcid() {
            return referenceAminoAcid;
        }

        public String getAlternateAminoAcid() {
            return alternateAminoAcid;
        }
    }
}
