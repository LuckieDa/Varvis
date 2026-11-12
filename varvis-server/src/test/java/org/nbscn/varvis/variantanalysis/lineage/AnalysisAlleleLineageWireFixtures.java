package org.nbscn.varvis.variantanalysis.lineage;

import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleKey;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateSource;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Document;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.DocumentStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.EngineAlleleRef;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.EngineMatchStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.FooterRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.HeaderRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecordStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.LiftoverPolicy;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.LiftoverStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.LiftoverTransform;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Limits;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.NormalizationStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.NormalizationTransform;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RecordType;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RelationCardinality;

import java.util.List;

final class AnalysisAlleleLineageWireFixtures {

    static final String INPUT_SHA = "a".repeat(64);
    static final String REFERENCE_SHA = "b".repeat(64);
    static final String CANONICAL_SHA = "c".repeat(64);
    static final String ENGINE_SHA = "d".repeat(64);
    static final String COMMAND_SHA = "e".repeat(64);
    static final String RESOURCE_SHA = "f".repeat(64);
    static final String LIFTED_SHA = "0".repeat(64);
    static final String TOKEN = "0123456789abcdef01234567";
    static final String ENGINE_ID = AnalysisAlleleLineageWire.engineAlleleId("GRCh38|1|100|A|G");

    private static final AnalysisAlleleLineageCanonicalProjector PROJECTOR =
            new AnalysisAlleleLineageCanonicalProjector();

    private AnalysisAlleleLineageWireFixtures() {
    }

    static HeaderRecord header() {
        return new HeaderRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.HEADER,
                0,
                AnalysisAlleleLineageWire.GENERATOR_VERSION,
                AnalysisAlleleLineageWire.PIPELINE_PROFILE_ID,
                AnalysisAlleleLineageWire.SOURCE_TOKEN_ALGORITHM,
                GenomeAssembly.GRCh38,
                GenomeAssembly.GRCh38,
                INPUT_SHA,
                LiftoverPolicy.NOT_REQUIRED,
                null,
                null,
                null,
                null,
                AnalysisAlleleLineageWire.NORMALIZATION_PROFILE_ID,
                REFERENCE_SHA,
                AnalysisAlleleLineageWire.NORMALIZATION_TOOL,
                "1.23.1",
                COMMAND_SHA,
                AnalysisAlleleLineageWire.ENGINE_NAME,
                "14.0.0",
                RESOURCE_SHA,
                AnalysisAlleleLineageWire.ENGINE_PARSER_SCHEMA_VERSION,
                AnalysisAlleleLineageWire.CANDIDATE_IDENTITY_SCHEMA_VERSION,
                AnalysisAlleleLineageWire.LIMIT_PROFILE_ID,
                new Limits(
                        AnalysisAlleleLineageWire.MAX_SIDECAR_BYTES,
                        AnalysisAlleleLineageWire.MAX_LINE_BYTES,
                        100,
                        20,
                        20,
                        AnalysisAlleleLineageWire.MAX_ALLELE_BYTES,
                        20,
                        20
                )
        );
    }

    static AlleleKey key(String chromosome, int position, String reference, String alternate) {
        return key(GenomeAssembly.GRCh38, chromosome, position, reference, alternate);
    }

    static AlleleKey key(GenomeAssembly assembly,
                         String chromosome,
                         int position,
                         String reference,
                         String alternate) {
        return new AlleleKey(
                assembly,
                chromosome,
                position,
                reference,
                alternate,
                assembly.name() + "|" + chromosome + "|" + position
                        + "|" + reference + "|" + alternate
        );
    }

    static String engineId(AlleleKey key) {
        return AnalysisAlleleLineageWire.engineAlleleId(key.canonicalVariantId());
    }

    static HeaderRecord crossAssemblyHeader() {
        HeaderRecord source = header();
        return new HeaderRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(),
                source.generatorVersion(), source.pipelineProfileId(),
                source.sourceAlleleTokenAlgorithm(), GenomeAssembly.GRCh37, GenomeAssembly.GRCh38,
                source.inputArtifactSha256(), LiftoverPolicy.REQUIRED, "picard-liftover-vcf",
                "3.4.0", "1".repeat(64), "2".repeat(64), source.normalizationProfileId(),
                source.normalizationReferenceSha256(), source.normalizationTool(),
                source.normalizationToolVersion(), source.normalizationCommandDigest(),
                source.engineName(), source.engineVersion(), source.engineResourceFingerprint(),
                source.engineParserSchemaVersion(), source.candidateIdentitySchemaVersion(),
                source.limitProfileId(), source.limits());
    }

    static HeaderRecord withLimits(HeaderRecord source, Limits limits) {
        return new HeaderRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(),
                source.generatorVersion(), source.pipelineProfileId(),
                source.sourceAlleleTokenAlgorithm(), source.inputAssembly(),
                source.analysisAssembly(), source.inputArtifactSha256(),
                source.liftoverPolicy(), source.liftoverTool(),
                source.liftoverToolVersion(), source.liftoverChainSha256(),
                source.liftoverReferenceSha256(), source.normalizationProfileId(),
                source.normalizationReferenceSha256(), source.normalizationTool(),
                source.normalizationToolVersion(), source.normalizationCommandDigest(),
                source.engineName(), source.engineVersion(),
                source.engineResourceFingerprint(), source.engineParserSchemaVersion(),
                source.candidateIdentitySchemaVersion(), source.limitProfileId(), limits);
    }

    static InputRecord inputRecord(long recordOrdinal,
                                   long sourceRecordOrdinal,
                                   int alternateCount) {
        return new InputRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.INPUT_RECORD,
                recordOrdinal,
                sourceRecordOrdinal,
                alternateCount,
                alternateCount == 0
                        ? InputRecordStatus.NO_TARGET_ALLELES
                        : InputRecordStatus.TARGETS_CAPTURED,
                alternateCount == 0 ? List.of(ReasonCode.INPUT_ALT_EMPTY) : List.of()
        );
    }

    static AlleleRecord matchedAllele(long recordOrdinal,
                                      long sourceRecordOrdinal,
                                      int alternateIndex,
                                      int alternateCount,
                                      String token,
                                      AlleleKey key,
                                      String engineId) {
        return new AlleleRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.ALLELE_LINEAGE,
                recordOrdinal,
                token,
                sourceRecordOrdinal,
                alternateIndex,
                alternateCount,
                InputStatus.ACCEPTED,
                List.of(),
                key,
                LiftoverStatus.NOT_REQUIRED,
                List.of(ReasonCode.LIFTOVER_NOT_REQUIRED),
                List.of(),
                RelationCardinality.ONE_TO_ZERO,
                List.of(),
                null,
                NormalizationStatus.IDENTITY,
                List.of(),
                List.of(key),
                RelationCardinality.ONE_TO_ONE,
                List.of(NormalizationTransform.IDENTITY),
                EngineMatchStatus.MATCHED,
                List.of(),
                List.of(new EngineAlleleRef(engineId, key.canonicalVariantId()))
        );
    }

    static CandidateLinkRecord linkedCandidate(long recordOrdinal,
                                                long sourceOrdinal,
                                                String engineId,
                                                String canonicalVariantId) {
        return new CandidateLinkRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.CANDIDATE_LINK,
                recordOrdinal,
                "exomiser-v1-" + String.format("%08d", sourceOrdinal),
                CandidateSource.EXOMISER,
                sourceOrdinal,
                engineId,
                canonicalVariantId,
                CandidateLinkStatus.LINKED,
                List.of()
        );
    }

    static AlleleRecord crossAssemblyMatchedAllele(long recordOrdinal,
                                                   long sourceRecordOrdinal,
                                                   String token,
                                                   AlleleKey sourceKey,
                                                   AlleleKey liftedKey,
                                                   String engineId) {
        return new AlleleRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.ALLELE_LINEAGE,
                recordOrdinal,
                token,
                sourceRecordOrdinal,
                1,
                1,
                InputStatus.ACCEPTED,
                List.of(),
                sourceKey,
                LiftoverStatus.LIFTED,
                List.of(),
                List.of(liftedKey),
                RelationCardinality.ONE_TO_ONE,
                List.of(LiftoverTransform.FORWARD),
                true,
                NormalizationStatus.IDENTITY,
                List.of(),
                List.of(liftedKey),
                RelationCardinality.ONE_TO_ONE,
                List.of(NormalizationTransform.IDENTITY),
                EngineMatchStatus.MATCHED,
                List.of(),
                List.of(new EngineAlleleRef(engineId, liftedKey.canonicalVariantId()))
        );
    }

    static FooterRecord footer(long recordOrdinal,
                               long inputRecordCount,
                               long inputAlleleCount,
                               long liftedAlleleCount,
                               long canonicalAlleleCount,
                               long engineAlleleCount,
                               long candidateLinkCount,
                               long unresolvedAlleleCount,
                               long rejectedAlleleCount) {
        return new FooterRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.FOOTER,
                recordOrdinal,
                DocumentStatus.COMPLETE,
                List.of(),
                inputRecordCount,
                inputAlleleCount,
                liftedAlleleCount,
                canonicalAlleleCount,
                engineAlleleCount,
                candidateLinkCount,
                unresolvedAlleleCount,
                rejectedAlleleCount,
                null,
                CANONICAL_SHA,
                ENGINE_SHA,
                AnalysisAlleleLineageWire.FINGERPRINT_ALGORITHM,
                null
        );
    }

    static Document validDocument() {
        AlleleKey key = key("1", 100, "A", "G");
        Document draft = new Document(
                header(),
                List.of(inputRecord(1, 1, 1)),
                List.of(matchedAllele(2, 1, 1, 1, TOKEN, key, ENGINE_ID)),
                List.of(linkedCandidate(3, 1, ENGINE_ID, key.canonicalVariantId())),
                footer(4, 1, 1, 0, 1, 1, 1, 0, 0)
        );
        return PROJECTOR.seal(draft);
    }

    static Document reseal(Document document) {
        return PROJECTOR.seal(document.withFooter(document.footer().withContentFingerprint(null)));
    }

    static FooterRecord copyFooter(FooterRecord source,
                                   long inputRecordCount,
                                   long inputAlleleCount,
                                   long liftedAlleleCount,
                                   long canonicalAlleleCount,
                                   long engineAlleleCount,
                                   long candidateLinkCount,
                                   long unresolvedAlleleCount,
                                   long rejectedAlleleCount) {
        return new FooterRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(), source.status(),
                source.reasonCodes(), inputRecordCount, inputAlleleCount, liftedAlleleCount,
                canonicalAlleleCount, engineAlleleCount, candidateLinkCount, unresolvedAlleleCount,
                rejectedAlleleCount, source.liftedArtifactSha256(), source.canonicalArtifactSha256(),
                source.engineArtifactSha256(), source.fingerprintAlgorithm(), source.contentFingerprint()
        );
    }

    static FooterRecord withLiftedArtifact(FooterRecord source, String liftedArtifactSha256) {
        return new FooterRecord(
                source.schemaVersion(), source.recordType(), source.recordOrdinal(), source.status(),
                source.reasonCodes(), source.inputRecordCount(), source.inputAlleleCount(),
                source.liftedAlleleCount(), source.canonicalAlleleCount(), source.engineAlleleCount(),
                source.candidateLinkCount(), source.unresolvedAlleleCount(),
                source.rejectedAlleleCount(), liftedArtifactSha256,
                source.canonicalArtifactSha256(), source.engineArtifactSha256(),
                source.fingerprintAlgorithm(), source.contentFingerprint());
    }
}
