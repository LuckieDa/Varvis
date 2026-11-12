package org.nbscn.varvis.variantanalysis.lineage;

import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleKey;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkStatus;
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
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.NormalizationStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RecordType;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RelationCardinality;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.WireRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strict collection-level validator for small, synthetic Phase A2 lineage documents.
 *
 * <p>The 10,000-body-record cap is intentionally much lower than the product wire ceiling. This
 * validator proves the contract without introducing a million-record in-memory graph. Phase B
 * must implement a bounded streaming/spool validator before product sidecars can be accepted.</p>
 */
public final class AnalysisAlleleLineageWireValidator {

    public static final int MAX_IN_MEMORY_BODY_RECORDS = 10_000;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SOURCE_TOKEN = Pattern.compile("[0-9a-f]{24}");
    private static final Pattern ENGINE_ALLELE_ID = Pattern.compile("engine-allele-v1-[0-9a-f]{24}");
    private static final Pattern TOOL_VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+){0,3}");
    private static final Pattern SEQUENCE_ALLELE = Pattern.compile("[ACGTN]+");
    private static final Pattern CANONICAL_CONTIG = Pattern.compile(
            "(?:[1-9]|1[0-9]|2[0-2]|X|Y|MT)"
    );
    private static final Pattern ACCESSION_CONTIG = Pattern.compile(
            "(?:GL|KI|JH)[0-9]{6}(?:V[0-9]+|\\.[0-9]+)?"
    );
    private static final Pattern UCSC_AUXILIARY_CONTIG = Pattern.compile(
            "(?:(?:[1-9]|1[0-9]|2[0-2]|X|Y|MT)_(?:GL|KI|JH)[0-9]{6}"
                    + "(?:V[0-9]+|\\.[0-9]+)?_(?:ALT|RANDOM)"
                    + "|UN_(?:GL|KI|JH)[0-9]{6}(?:V[0-9]+|\\.[0-9]+)?(?:_RANDOM)?)"
    );

    private static final Set<ReasonCode> INPUT_REJECTION_REASONS = EnumSet.of(
            ReasonCode.INPUT_ALT_EMPTY,
            ReasonCode.INPUT_ALLELE_UNSUPPORTED
    );
    private static final Set<ReasonCode> LIFTOVER_UNMAPPED_REASONS = EnumSet.of(
            ReasonCode.LIFTOVER_NO_TARGET,
            ReasonCode.LIFTOVER_CANNOT_LIFT_OVER
    );
    private static final Set<ReasonCode> LIFTOVER_REJECTION_REASONS = EnumSet.of(
            ReasonCode.LIFTOVER_REVERSE_COMPLEMENTED_INDEL,
            ReasonCode.LIFTOVER_INDEL_STRADDLES_MULTIPLE_INTERVALS,
            ReasonCode.LIFTOVER_OTHER_REJECT,
            ReasonCode.LIFTOVER_TRANSFORM_UNSUPPORTED
    );
    private static final Set<ReasonCode> NORMALIZATION_REJECTION_REASONS = EnumSet.of(
            ReasonCode.NORMALIZATION_REFERENCE_MISMATCH,
            ReasonCode.NORMALIZATION_UNSUPPORTED_CONTIG,
            ReasonCode.NORMALIZATION_INVALID_ALLELE,
            ReasonCode.NORMALIZATION_PROCESS_FAILED,
            ReasonCode.NORMALIZATION_TOOL_UNAVAILABLE,
            ReasonCode.NORMALIZATION_TOOL_TIMEOUT,
            ReasonCode.NORMALIZATION_OUTPUT_INVALID,
            ReasonCode.NORMALIZATION_ONE_TO_MANY_UNSUPPORTED
    );
    private static final Set<ReasonCode> NORMALIZATION_UNRESOLVED_REASONS = EnumSet.of(
            ReasonCode.NORMALIZATION_ORIGINAL_UNRESOLVED,
            ReasonCode.LEGACY_LINEAGE_UNAVAILABLE,
            ReasonCode.UNKNOWN_MAPPING_FAILURE
    );

    public static final Comparator<AlleleKey> ALLELE_KEY_COMPARATOR = Comparator
            .comparing((AlleleKey key) -> key.assembly().ordinal())
            .thenComparingInt(key -> contigRank(key.chromosome()))
            .thenComparing(key -> contigRank(key.chromosome()) >= 100 ? key.chromosome() : "")
            .thenComparingInt((AlleleKey key) -> key.position())
            .thenComparing((AlleleKey key) -> key.reference())
            .thenComparing((AlleleKey key) -> key.alternate())
            .thenComparing((AlleleKey key) -> key.canonicalVariantId());

    private final AnalysisAlleleLineageCanonicalProjector projector;

    public AnalysisAlleleLineageWireValidator() {
        this(new AnalysisAlleleLineageCanonicalProjector());
    }

    AnalysisAlleleLineageWireValidator(AnalysisAlleleLineageCanonicalProjector projector) {
        this.projector = projector;
    }

    /** Validates a sealed, canonical, in-memory synthetic document or throws a stable code. */
    public void validate(Document document) {
        require(document, "document");
        HeaderRecord header = require(document.header(), "header");
        FooterRecord footer = require(document.footer(), "footer");
        List<InputRecord> inputRecords = requireList(document.inputRecords(), "inputRecords");
        List<AlleleRecord> alleleRecords = requireList(document.alleleRecords(), "alleleRecords");
        List<CandidateLinkRecord> candidateLinks = requireList(document.candidateLinks(), "candidateLinks");

        long bodyCount = (long) inputRecords.size() + alleleRecords.size() + candidateLinks.size();
        if (bodyCount > MAX_IN_MEMORY_BODY_RECORDS) {
            fail(ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED, "document");
        }

        validateHeaderRecord(header);
        if (bodyCount > header.limits().maxRecordCount()) {
            fail(ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED, "maxRecordCount");
        }
        if (alleleRecords.size() > header.limits().maxInputAlleleCount()) {
            fail(ReasonCode.LINEAGE_ALLELE_LIMIT_EXCEEDED, "maxInputAlleleCount");
        }
        if (candidateLinks.size() > header.limits().maxCandidateLinkCount()) {
            fail(ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED, "maxCandidateLinkCount");
        }

        long expectedRecordOrdinal = 1L;
        Map<Long, InputRecord> inputByOrdinal = new HashMap<>();
        for (int index = 0; index < inputRecords.size(); index++) {
            InputRecord inputRecord = require(inputRecords.get(index), "inputRecords");
            validateCommon(inputRecord, RecordType.INPUT_RECORD, expectedRecordOrdinal++);
            validateInputRecord(inputRecord, index + 1L);
            if (inputByOrdinal.put(inputRecord.sourceRecordOrdinal(), inputRecord) != null) {
                fail(ReasonCode.LINEAGE_DUPLICATE_ID, "sourceRecordOrdinal");
            }
        }

        Map<String, SourcePair> tokenToPair = new HashMap<>();
        Map<SourcePair, String> pairToToken = new HashMap<>();
        Map<Long, List<AlleleRecord>> allelesByInput = new HashMap<>();
        Map<String, AlleleKey> canonicalById = new HashMap<>();
        Map<String, String> engineIdToCanonicalId = new HashMap<>();
        Map<AlleleKey, AlleleKey> liftedToSource = new HashMap<>();
        Map<String, Set<String>> liftedFanIn = new HashMap<>();
        Map<String, Set<String>> canonicalFanIn = new HashMap<>();
        Map<String, Set<String>> engineFanIn = new HashMap<>();
        Set<String> matchedEngineIds = new HashSet<>();
        Map<String, EngineOutcome> engineOutcomeByCanonicalId = new HashMap<>();
        Map<AlleleKey, SourceOutcome> sourceOutcomeBySourceKey = new HashMap<>();

        AlleleRecord previousAllele = null;
        for (AlleleRecord allele : alleleRecords) {
            require(allele, "alleleRecords");
            validateCommon(allele, RecordType.ALLELE_LINEAGE, expectedRecordOrdinal++);
            InputRecord parent = inputByOrdinal.get(allele.sourceRecordOrdinal());
            if (parent == null || parent.inputRecordStatus() != InputRecordStatus.TARGETS_CAPTURED) {
                fail(ReasonCode.LINEAGE_DANGLING_REFERENCE, "sourceRecordOrdinal");
            }
            validateAllele(allele, header, parent);
            if (allele.inputStatus() == InputStatus.ACCEPTED) {
                SourceOutcome outcome = SourceOutcome.from(allele);
                SourceOutcome previousOutcome = sourceOutcomeBySourceKey.putIfAbsent(
                        allele.sourceKey(), outcome);
                if (previousOutcome != null && !previousOutcome.equals(outcome)) {
                    fail(ReasonCode.LINEAGE_CARDINALITY_CONFLICT, "sourceKey");
                }
            }
            SourcePair pair = new SourcePair(allele.sourceRecordOrdinal(), allele.sourceAlternateIndex());
            SourcePair oldPair = tokenToPair.put(allele.sourceAlleleToken(), pair);
            if (oldPair != null && !oldPair.equals(pair)) {
                fail(ReasonCode.LINEAGE_DUPLICATE_ID, "sourceAlleleToken");
            }
            String oldToken = pairToToken.put(pair, allele.sourceAlleleToken());
            if (oldToken != null) {
                fail(ReasonCode.LINEAGE_DUPLICATE_ID, "sourceAlleleToken");
            }
            if (previousAllele != null && compareAlleleRecords(previousAllele, allele) >= 0) {
                fail(ReasonCode.LINEAGE_ORDER_INVALID, "alleleRecords");
            }
            previousAllele = allele;
            allelesByInput.computeIfAbsent(allele.sourceRecordOrdinal(), ignored -> new ArrayList<>())
                    .add(allele);

            for (AlleleKey lifted : allele.liftedKeys()) {
                AlleleKey previousSource = liftedToSource.putIfAbsent(lifted, allele.sourceKey());
                if (previousSource != null && !previousSource.equals(allele.sourceKey())) {
                    fail(ReasonCode.LINEAGE_CARDINALITY_CONFLICT, "liftedKeys");
                }
                liftedFanIn.computeIfAbsent(
                        lifted.canonicalVariantId(), ignored -> new HashSet<>())
                        .add(allele.sourceAlleleToken());
            }
            for (AlleleKey canonical : allele.canonicalKeys()) {
                AlleleKey previous = canonicalById.putIfAbsent(canonical.canonicalVariantId(), canonical);
                if (previous != null && !previous.equals(canonical)) {
                    fail(ReasonCode.LINEAGE_DUPLICATE_ID, "canonicalVariantId");
                }
                canonicalFanIn.computeIfAbsent(canonical.canonicalVariantId(), ignored -> new HashSet<>())
                        .add(allele.sourceAlleleToken());
                EngineOutcome outcome = new EngineOutcome(
                        allele.engineMatchStatus(),
                        allele.engineReasonCodes(),
                        allele.engineAlleles());
                EngineOutcome previousOutcome = engineOutcomeByCanonicalId.putIfAbsent(
                        canonical.canonicalVariantId(), outcome);
                if (previousOutcome != null && !previousOutcome.equals(outcome)) {
                    fail(ReasonCode.LINEAGE_CARDINALITY_CONFLICT, "engineMatchStatus");
                }
            }
            for (EngineAlleleRef engine : allele.engineAlleles()) {
                String previousCanonical = engineIdToCanonicalId.putIfAbsent(
                        engine.engineAlleleId(), engine.analysisCanonicalVariantId());
                if (previousCanonical != null
                        && !previousCanonical.equals(engine.analysisCanonicalVariantId())) {
                    fail(ReasonCode.LINEAGE_DUPLICATE_ID, "engineAlleleId");
                }
                engineFanIn.computeIfAbsent(engine.engineAlleleId(), ignored -> new HashSet<>())
                        .add(allele.sourceAlleleToken());
                if (allele.engineMatchStatus() == EngineMatchStatus.MATCHED) {
                    matchedEngineIds.add(engine.engineAlleleId());
                }
            }
        }

        validateInputAlleleCoverage(inputRecords, allelesByInput);
        validateFanIn(liftedFanIn, header.limits().maxRelationFanIn(), "liftedKeys");
        validateFanIn(canonicalFanIn, header.limits().maxRelationFanIn(), "canonicalKeys");
        validateFanIn(engineFanIn, header.limits().maxRelationFanIn(), "engineAlleles");

        Set<String> candidateIds = new HashSet<>();
        Set<CandidateSourceKey> candidateSources = new HashSet<>();
        Set<String> linkedEngineIds = new HashSet<>();
        Map<String, Integer> candidateFanOut = new HashMap<>();
        CandidateLinkRecord previousCandidate = null;
        for (CandidateLinkRecord candidate : candidateLinks) {
            require(candidate, "candidateLinks");
            validateCommon(candidate, RecordType.CANDIDATE_LINK, expectedRecordOrdinal++);
            validateCandidate(candidate, engineIdToCanonicalId, matchedEngineIds);
            if (!candidateIds.add(candidate.candidateId())) {
                fail(ReasonCode.LINEAGE_DUPLICATE_ID, "candidateId");
            }
            if (!candidateSources.add(new CandidateSourceKey(
                    candidate.candidateSource(), candidate.candidateSourceOrdinal()))) {
                fail(ReasonCode.LINEAGE_DUPLICATE_ID, "candidateSourceOrdinal");
            }
            if (candidate.linkStatus() == CandidateLinkStatus.LINKED) {
                linkedEngineIds.add(candidate.engineAlleleId());
                int fanOut = candidateFanOut.merge(candidate.engineAlleleId(), 1, (left, right) -> left + right);
                if (fanOut > header.limits().maxRelationFanOut()) {
                    fail(ReasonCode.LINEAGE_FAN_OUT_LIMIT_EXCEEDED, "candidateLinks");
                }
            }
            if (previousCandidate != null
                    && compareCandidates(previousCandidate, candidate, canonicalById) >= 0) {
                fail(ReasonCode.LINEAGE_ORDER_INVALID, "candidateLinks");
            }
            previousCandidate = candidate;
        }
        if (!linkedEngineIds.containsAll(matchedEngineIds)) {
            fail(ReasonCode.LINEAGE_DANGLING_REFERENCE, "candidateLinks");
        }

        validateCommon(footer, RecordType.FOOTER, expectedRecordOrdinal);
        validateFooter(
                footer,
                header,
                inputRecords,
                alleleRecords,
                candidateLinks,
                engineIdToCanonicalId
        );
        validateCanonicalByteLimits(document, header);
        validateFingerprint(document);
    }

    /**
     * Validates the self-contained header contract before a codec accepts any body records.
     *
     * <p>Package visibility is intentional: the strict NDJSON reader uses this narrow check to
     * reject an invalid producer/resource/limit binding immediately after the first bounded line.
     * It does not replace full document validation.</p>
     */
    static void validateHeaderRecord(HeaderRecord header) {
        validateCommon(header, RecordType.HEADER, 0L);
        exact(header.generatorVersion(), AnalysisAlleleLineageWire.GENERATOR_VERSION,
                "generatorVersion");
        exact(header.pipelineProfileId(), AnalysisAlleleLineageWire.PIPELINE_PROFILE_ID,
                "pipelineProfileId");
        exact(header.sourceAlleleTokenAlgorithm(), AnalysisAlleleLineageWire.SOURCE_TOKEN_ALGORITHM,
                "sourceAlleleTokenAlgorithm");
        require(header.inputAssembly(), "inputAssembly");
        require(header.analysisAssembly(), "analysisAssembly");
        sha256(header.inputArtifactSha256(), "inputArtifactSha256");
        require(header.liftoverPolicy(), "liftoverPolicy");

        boolean sameAssembly = header.inputAssembly() == header.analysisAssembly();
        if (sameAssembly) {
            if (header.liftoverPolicy() != LiftoverPolicy.NOT_REQUIRED) {
                fail(ReasonCode.LINEAGE_ASSEMBLY_TRANSITION_INVALID, "liftoverPolicy");
            }
            allNull("liftoverBinding", header.liftoverTool(), header.liftoverToolVersion(),
                    header.liftoverChainSha256(), header.liftoverReferenceSha256());
        } else if (header.liftoverPolicy() == LiftoverPolicy.REQUIRED) {
            exact(header.liftoverTool(), "picard-liftover-vcf", "liftoverTool");
            toolVersion(header.liftoverToolVersion(), "liftoverToolVersion");
            sha256(header.liftoverChainSha256(), "liftoverChainSha256");
            sha256(header.liftoverReferenceSha256(), "liftoverReferenceSha256");
        } else if (header.liftoverPolicy() == LiftoverPolicy.DISABLED) {
            allNull("liftoverBinding", header.liftoverTool(), header.liftoverToolVersion(),
                    header.liftoverChainSha256(), header.liftoverReferenceSha256());
        } else {
            fail(ReasonCode.LINEAGE_ASSEMBLY_TRANSITION_INVALID, "liftoverPolicy");
        }

        exact(header.normalizationProfileId(), AnalysisAlleleLineageWire.NORMALIZATION_PROFILE_ID,
                "normalizationProfileId");
        sha256(header.normalizationReferenceSha256(), "normalizationReferenceSha256");
        exact(header.normalizationTool(), AnalysisAlleleLineageWire.NORMALIZATION_TOOL,
                "normalizationTool");
        toolVersion(header.normalizationToolVersion(), "normalizationToolVersion");
        sha256(header.normalizationCommandDigest(), "normalizationCommandDigest");
        exact(header.engineName(), AnalysisAlleleLineageWire.ENGINE_NAME, "engineName");
        toolVersion(header.engineVersion(), "engineVersion");
        sha256(header.engineResourceFingerprint(), "engineResourceFingerprint");
        exact(header.engineParserSchemaVersion(),
                AnalysisAlleleLineageWire.ENGINE_PARSER_SCHEMA_VERSION,
                "engineParserSchemaVersion");
        exact(header.candidateIdentitySchemaVersion(),
                AnalysisAlleleLineageWire.CANDIDATE_IDENTITY_SCHEMA_VERSION,
                "candidateIdentitySchemaVersion");
        exact(header.limitProfileId(), AnalysisAlleleLineageWire.LIMIT_PROFILE_ID,
                "limitProfileId");
        validateLimits(require(header.limits(), "limits"));
    }

    /** Reuses the A2 input-row rules without exposing the internal collection validator. */
    static void validateStreamingInputRecord(InputRecord record, long expectedSourceOrdinal) {
        validateInputRecord(record, expectedSourceOrdinal);
    }

    /**
     * Reuses every record-local allele rule. Parent existence and the parent's declared ALT count
     * are deliberately left to the bounded cross-record pass.
     */
    static void validateStreamingAlleleRecord(AlleleRecord allele, HeaderRecord header) {
        InputRecord localParent = new InputRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.INPUT_RECORD,
                0L,
                allele.sourceRecordOrdinal(),
                allele.sourceAlternateCount(),
                InputRecordStatus.TARGETS_CAPTURED,
                List.of()
        );
        validateAllele(allele, header, localParent);
    }

    /** Reuses record-type, schema and ordinal validation in the streaming state machine. */
    static void validateStreamingCommon(WireRecord record,
                                        RecordType expectedType,
                                        long expectedOrdinal) {
        validateCommon(record, expectedType, expectedOrdinal);
    }

    static boolean streamingRejected(AlleleRecord allele) {
        return isRejected(allele);
    }

    static boolean streamingUnresolved(AlleleRecord allele) {
        return isUnresolved(allele);
    }

    static boolean streamingSuccessful(AlleleRecord allele) {
        return allele.engineMatchStatus() == EngineMatchStatus.MATCHED
                || allele.engineMatchStatus() == EngineMatchStatus.NOT_FOUND;
    }

    static List<ReasonCode> streamingFailureReasons(AlleleRecord allele) {
        if (!isRejected(allele) && !isUnresolved(allele)) {
            return List.of();
        }
        if (allele.inputStatus() == InputStatus.REJECTED) {
            return allele.inputReasonCodes();
        }
        if (isLiftoverFailure(allele.liftoverStatus())) {
            return allele.liftoverReasonCodes();
        }
        if (allele.normalizationStatus() == NormalizationStatus.REJECTED
                || allele.normalizationStatus() == NormalizationStatus.UNRESOLVED) {
            return allele.normalizationReasonCodes();
        }
        return allele.engineReasonCodes();
    }

    static boolean streamingLiftoverFailure(LiftoverStatus status) {
        return isLiftoverFailure(status);
    }

    static boolean streamingRequiresLiftoverArtifact(LiftoverStatus status) {
        return requiresLiftoverArtifactBinding(status);
    }

    static int streamingContigRank(String contig) {
        return contigRank(contig);
    }

    private static void validateLimits(AnalysisAlleleLineageWire.Limits limits) {
        positiveWithin(limits.maxSidecarBytes(), AnalysisAlleleLineageWire.MAX_SIDECAR_BYTES,
                "maxSidecarBytes");
        positiveWithin(limits.maxLineBytes(), AnalysisAlleleLineageWire.MAX_LINE_BYTES,
                "maxLineBytes");
        positiveWithin(limits.maxRecordCount(), AnalysisAlleleLineageWire.MAX_BODY_RECORD_COUNT,
                "maxRecordCount");
        positiveWithin(limits.maxInputAlleleCount(), AnalysisAlleleLineageWire.MAX_INPUT_ALLELE_COUNT,
                "maxInputAlleleCount");
        positiveWithin(limits.maxCandidateLinkCount(),
                AnalysisAlleleLineageWire.MAX_CANDIDATE_LINK_COUNT,
                "maxCandidateLinkCount");
        positiveWithin(limits.maxAlleleBytes(), AnalysisAlleleLineageWire.MAX_ALLELE_BYTES,
                "maxAlleleBytes");
        positiveWithin(limits.maxRelationFanIn(), AnalysisAlleleLineageWire.MAX_RELATION_FAN_IN,
                "maxRelationFanIn");
        positiveWithin(limits.maxRelationFanOut(), AnalysisAlleleLineageWire.MAX_RELATION_FAN_OUT,
                "maxRelationFanOut");
    }

    private static void validateInputRecord(InputRecord record, long expectedSourceOrdinal) {
        if (record.sourceRecordOrdinal() != expectedSourceOrdinal) {
            fail(ReasonCode.LINEAGE_ORDER_INVALID, "sourceRecordOrdinal");
        }
        require(record.inputRecordStatus(), "inputRecordStatus");
        validateReasons(record.reasonCodes(), "reasonCodes");
        if (record.inputRecordStatus() == InputRecordStatus.TARGETS_CAPTURED) {
            if (record.sourceAlternateCount() <= 0 || !record.reasonCodes().isEmpty()) {
                fail(ReasonCode.LINEAGE_FIELD_INVALID, "sourceAlternateCount");
            }
        } else if (record.sourceAlternateCount() != 0
                || !record.reasonCodes().equals(List.of(ReasonCode.INPUT_ALT_EMPTY))) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, "inputRecordStatus");
        }
    }

    private static void validateAllele(AlleleRecord allele,
                                       HeaderRecord header,
                                       InputRecord parent) {
        if (allele.sourceRecordOrdinal() <= 0
                || allele.sourceAlternateIndex() <= 0
                || allele.sourceAlternateCount() <= 0
                || allele.sourceAlternateIndex() > allele.sourceAlternateCount()
                || allele.sourceAlternateCount() != parent.sourceAlternateCount()) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, "sourceAlternateIndex");
        }
        if (allele.sourceAlleleToken() == null
                || !SOURCE_TOKEN.matcher(allele.sourceAlleleToken()).matches()) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, "sourceAlleleToken");
        }
        require(allele.inputStatus(), "inputStatus");
        require(allele.liftoverStatus(), "liftoverStatus");
        require(allele.liftoverCardinality(), "liftoverCardinality");
        require(allele.normalizationStatus(), "normalizationStatus");
        require(allele.normalizationCardinality(), "normalizationCardinality");
        require(allele.engineMatchStatus(), "engineMatchStatus");
        validateReasons(allele.inputReasonCodes(), "inputReasonCodes");
        validateReasons(allele.liftoverReasonCodes(), "liftoverReasonCodes");
        validateReasons(allele.normalizationReasonCodes(), "normalizationReasonCodes");
        validateReasons(allele.engineReasonCodes(), "engineReasonCodes");
        validateEnumList(allele.liftoverTransforms(), "liftoverTransforms");
        validateEnumList(allele.normalizationTransforms(), "normalizationTransforms");
        if (allele.liftoverTransforms().contains(
                AnalysisAlleleLineageWire.LiftoverTransform.IDENTITY)
                && allele.liftoverTransforms().size() != 1) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, "liftoverTransforms");
        }
        validateKeyList(allele.liftedKeys(), header.limits().maxRelationFanOut(),
                header.limits().maxAlleleBytes(), "liftedKeys");
        validateKeyList(allele.canonicalKeys(), header.limits().maxRelationFanOut(),
                header.limits().maxAlleleBytes(), "canonicalKeys");
        validateEngineList(allele.engineAlleles(), allele.canonicalKeys(),
                header.limits().maxRelationFanOut());
        cardinality(allele.liftoverCardinality(), allele.liftedKeys().size(),
                "liftoverCardinality");
        cardinality(allele.normalizationCardinality(), allele.canonicalKeys().size(),
                "normalizationCardinality");

        if (allele.inputStatus() == InputStatus.REJECTED) {
            if (allele.sourceKey() != null
                    || allele.inputReasonCodes().isEmpty()
                    || !INPUT_REJECTION_REASONS.containsAll(allele.inputReasonCodes())
                    || allele.liftoverStatus() != LiftoverStatus.NOT_SUBMITTED
                    || allele.normalizationStatus() != NormalizationStatus.NOT_SUBMITTED
                    || allele.engineMatchStatus() != EngineMatchStatus.NOT_SUBMITTED
                    || !allele.liftoverReasonCodes().isEmpty()
                    || !allele.normalizationReasonCodes().isEmpty()
                    || !allele.liftedKeys().isEmpty()
                    || !allele.canonicalKeys().isEmpty()
                    || !allele.liftoverTransforms().isEmpty()
                    || !allele.normalizationTransforms().isEmpty()
                    || allele.targetReferenceValidated() != null
                    || !allele.engineAlleles().isEmpty()) {
                fail(ReasonCode.LINEAGE_FIELD_INVALID, "inputStatus");
            }
            requireExactReasons(allele.engineReasonCodes(), ReasonCode.ENGINE_NOT_SUBMITTED,
                    "engineReasonCodes");
            return;
        }

        validateKey(allele.sourceKey(), header.limits().maxAlleleBytes(), "sourceKey");
        if (allele.sourceKey().assembly() != header.inputAssembly()) {
            fail(ReasonCode.LINEAGE_ASSEMBLY_TRANSITION_INVALID, "sourceKey");
        }
        if (!allele.inputReasonCodes().isEmpty()
                && !allele.inputReasonCodes().equals(List.of(ReasonCode.INPUT_DUPLICATE_ALT))) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, "inputReasonCodes");
        }

        validateLiftover(allele, header);
        validateNormalization(allele, header);
        validateEngine(allele);
    }

    private static void validateLiftover(AlleleRecord allele, HeaderRecord header) {
        boolean sameAssembly = header.inputAssembly() == header.analysisAssembly();
        if (sameAssembly) {
            if (allele.liftoverStatus() != LiftoverStatus.NOT_REQUIRED
                    || !allele.liftedKeys().isEmpty()
                    || allele.liftoverCardinality() != RelationCardinality.ONE_TO_ZERO
                    || !allele.liftoverTransforms().isEmpty()
                    || allele.targetReferenceValidated() != null) {
                fail(ReasonCode.LINEAGE_ASSEMBLY_TRANSITION_INVALID, "liftoverStatus");
            }
            requireExactReasons(allele.liftoverReasonCodes(), ReasonCode.LIFTOVER_NOT_REQUIRED,
                    "liftoverReasonCodes");
            return;
        }
        if (header.liftoverPolicy() == LiftoverPolicy.DISABLED
                && allele.liftoverStatus() != LiftoverStatus.NOT_SUBMITTED) {
            fail(ReasonCode.LINEAGE_ASSEMBLY_TRANSITION_INVALID, "liftoverStatus");
        }
        switch (allele.liftoverStatus()) {
            case LIFTED -> {
                if (allele.liftedKeys().size() != 1
                        || allele.targetReferenceValidated() != Boolean.TRUE
                        || allele.liftoverTransforms().isEmpty()
                        || !allele.liftoverReasonCodes().isEmpty()) {
                    fail(ReasonCode.LINEAGE_CARDINALITY_CONFLICT, "liftedKeys");
                }
                for (AlleleKey key : allele.liftedKeys()) {
                    if (key.assembly() != header.analysisAssembly()) {
                        fail(ReasonCode.LINEAGE_ASSEMBLY_TRANSITION_INVALID, "liftedKeys");
                    }
                }
            }
            case AMBIGUOUS -> {
                if (allele.liftedKeys().size() < 2
                        || allele.targetReferenceValidated() != Boolean.TRUE
                        || allele.liftoverTransforms().isEmpty()) {
                    fail(ReasonCode.LINEAGE_CARDINALITY_CONFLICT, "liftedKeys");
                }
                requireExactReasons(allele.liftoverReasonCodes(), ReasonCode.LIFTOVER_AMBIGUOUS,
                        "liftoverReasonCodes");
                for (AlleleKey key : allele.liftedKeys()) {
                    if (key.assembly() != header.analysisAssembly()) {
                        fail(ReasonCode.LINEAGE_ASSEMBLY_TRANSITION_INVALID, "liftedKeys");
                    }
                }
            }
            case UNMAPPED -> {
                requireTerminalLiftover(allele);
                requireOnlyReasons(allele.liftoverReasonCodes(), LIFTOVER_UNMAPPED_REASONS,
                        "liftoverReasonCodes");
            }
            case REF_MISMATCH -> {
                requireTerminalLiftover(allele);
                requireExactReasons(allele.liftoverReasonCodes(),
                        ReasonCode.LIFTOVER_MISMATCHED_REFERENCE, "liftoverReasonCodes");
            }
            case REJECTED -> {
                requireTerminalLiftover(allele);
                requireOnlyReasons(allele.liftoverReasonCodes(), LIFTOVER_REJECTION_REASONS,
                        "liftoverReasonCodes");
            }
            case UNAVAILABLE -> {
                requireTerminalLiftover(allele);
                requireExactReasons(allele.liftoverReasonCodes(), ReasonCode.LIFTOVER_UNAVAILABLE,
                        "liftoverReasonCodes");
            }
            case SKIPPED -> {
                requireTerminalLiftover(allele);
                requireExactReasons(allele.liftoverReasonCodes(), ReasonCode.LIFTOVER_SKIPPED_BY_CAP,
                        "liftoverReasonCodes");
            }
            case NOT_SUBMITTED -> {
                requireTerminalLiftover(allele);
                if (header.liftoverPolicy() != LiftoverPolicy.DISABLED
                        || !allele.liftoverReasonCodes().isEmpty()) {
                    fail(ReasonCode.LINEAGE_ASSEMBLY_TRANSITION_INVALID, "liftoverStatus");
                }
            }
            case NOT_REQUIRED -> fail(ReasonCode.LINEAGE_ASSEMBLY_TRANSITION_INVALID,
                    "liftoverStatus");
        }
    }

    private static void requireTerminalLiftover(AlleleRecord allele) {
        if (!allele.liftedKeys().isEmpty()
                || allele.liftoverCardinality() != RelationCardinality.ONE_TO_ZERO
                || !allele.liftoverTransforms().isEmpty()
                || allele.targetReferenceValidated() != null) {
            fail(ReasonCode.LINEAGE_CARDINALITY_CONFLICT, "liftoverStatus");
        }
    }

    private static void validateNormalization(AlleleRecord allele, HeaderRecord header) {
        boolean liftoverReady = allele.liftoverStatus() == LiftoverStatus.NOT_REQUIRED
                || allele.liftoverStatus() == LiftoverStatus.LIFTED;
        if (!liftoverReady) {
            if (allele.normalizationStatus() != NormalizationStatus.NOT_SUBMITTED
                    || !allele.normalizationReasonCodes().isEmpty()
                    || !allele.canonicalKeys().isEmpty()
                    || !allele.normalizationTransforms().isEmpty()) {
                fail(ReasonCode.LINEAGE_FIELD_INVALID, "normalizationStatus");
            }
            return;
        }
        AlleleKey normalizationInput = allele.liftoverStatus() == LiftoverStatus.NOT_REQUIRED
                ? allele.sourceKey() : allele.liftedKeys().get(0);
        switch (allele.normalizationStatus()) {
            case IDENTITY -> {
                if (allele.canonicalKeys().size() != 1
                        || !allele.canonicalKeys().get(0).equals(normalizationInput)
                        || !allele.normalizationTransforms().equals(
                        List.of(AnalysisAlleleLineageWire.NormalizationTransform.IDENTITY))
                        || !allele.normalizationReasonCodes().isEmpty()) {
                    fail(ReasonCode.LINEAGE_CARDINALITY_CONFLICT, "normalizationStatus");
                }
            }
            case NORMALIZED -> {
                if (allele.canonicalKeys().size() != 1
                        || allele.canonicalKeys().get(0).equals(normalizationInput)
                        || allele.normalizationTransforms().isEmpty()
                        || allele.normalizationTransforms().contains(
                        AnalysisAlleleLineageWire.NormalizationTransform.IDENTITY)
                        || !allele.normalizationReasonCodes().isEmpty()) {
                    fail(ReasonCode.LINEAGE_CARDINALITY_CONFLICT, "normalizationStatus");
                }
                for (AlleleKey key : allele.canonicalKeys()) {
                    if (key.assembly() != header.analysisAssembly()) {
                        fail(ReasonCode.LINEAGE_ASSEMBLY_TRANSITION_INVALID, "canonicalKeys");
                    }
                }
            }
            case UNRESOLVED -> {
                requireTerminalNormalization(allele);
                if (allele.normalizationReasonCodes().isEmpty()
                        || !NORMALIZATION_UNRESOLVED_REASONS.containsAll(
                        allele.normalizationReasonCodes())) {
                    fail(ReasonCode.LINEAGE_FIELD_INVALID, "normalizationReasonCodes");
                }
            }
            case REJECTED -> {
                requireTerminalNormalization(allele);
                if (allele.normalizationReasonCodes().isEmpty()
                        || !NORMALIZATION_REJECTION_REASONS.containsAll(
                        allele.normalizationReasonCodes())) {
                    fail(ReasonCode.LINEAGE_FIELD_INVALID, "normalizationReasonCodes");
                }
            }
            case NOT_SUBMITTED -> {
                fail(ReasonCode.LINEAGE_FIELD_INVALID, "normalizationStatus");
            }
        }
    }

    private static void requireTerminalNormalization(AlleleRecord allele) {
        if (!allele.canonicalKeys().isEmpty()
                || allele.normalizationCardinality() != RelationCardinality.ONE_TO_ZERO
                || !allele.normalizationTransforms().isEmpty()) {
            fail(ReasonCode.LINEAGE_CARDINALITY_CONFLICT, "normalizationStatus");
        }
    }

    private static void validateEngine(AlleleRecord allele) {
        boolean canonicalReady = allele.normalizationStatus() == NormalizationStatus.IDENTITY
                || allele.normalizationStatus() == NormalizationStatus.NORMALIZED;
        if (!canonicalReady && allele.engineMatchStatus() != EngineMatchStatus.NOT_SUBMITTED) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, "engineMatchStatus");
        }
        switch (allele.engineMatchStatus()) {
            case MATCHED -> {
                if (!canonicalReady || allele.engineAlleles().size() != 1
                        || !allele.engineReasonCodes().isEmpty()) {
                    fail(ReasonCode.LINEAGE_FIELD_INVALID, "engineMatchStatus");
                }
            }
            case NOT_FOUND -> {
                if (!canonicalReady || !allele.engineAlleles().isEmpty()) {
                    fail(ReasonCode.LINEAGE_FIELD_INVALID, "engineMatchStatus");
                }
                requireExactReasons(allele.engineReasonCodes(), ReasonCode.ENGINE_ALLELE_NOT_FOUND,
                        "engineReasonCodes");
            }
            case AMBIGUOUS -> {
                if (!canonicalReady || !allele.engineAlleles().isEmpty()) {
                    fail(ReasonCode.LINEAGE_FIELD_INVALID, "engineMatchStatus");
                }
                requireExactReasons(allele.engineReasonCodes(), ReasonCode.ENGINE_ALLELE_AMBIGUOUS,
                        "engineReasonCodes");
            }
            case CONFLICT -> {
                if (!canonicalReady || !allele.engineAlleles().isEmpty()) {
                    fail(ReasonCode.LINEAGE_FIELD_INVALID, "engineMatchStatus");
                }
                requireExactReasons(allele.engineReasonCodes(), ReasonCode.ENGINE_ALLELE_CONFLICT,
                        "engineReasonCodes");
            }
            case NOT_SUBMITTED -> {
                if (!allele.engineAlleles().isEmpty()) {
                    fail(ReasonCode.LINEAGE_FIELD_INVALID, "engineAlleles");
                }
                requireExactReasons(allele.engineReasonCodes(), ReasonCode.ENGINE_NOT_SUBMITTED,
                        "engineReasonCodes");
            }
        }
    }

    private static void validateInputAlleleCoverage(List<InputRecord> inputRecords,
                                                    Map<Long, List<AlleleRecord>> allelesByInput) {
        for (InputRecord inputRecord : inputRecords) {
            List<AlleleRecord> alleles = allelesByInput.getOrDefault(
                    inputRecord.sourceRecordOrdinal(), List.of());
            if (inputRecord.inputRecordStatus() == InputRecordStatus.NO_TARGET_ALLELES) {
                if (!alleles.isEmpty()) {
                    fail(ReasonCode.LINEAGE_COUNT_MISMATCH, "sourceAlternateCount");
                }
                continue;
            }
            if (alleles.size() != inputRecord.sourceAlternateCount()) {
                fail(ReasonCode.LINEAGE_COUNT_MISMATCH, "sourceAlternateCount");
            }
            for (int index = 0; index < alleles.size(); index++) {
                if (alleles.get(index).sourceAlternateIndex() != index + 1) {
                    fail(ReasonCode.LINEAGE_COUNT_MISMATCH, "sourceAlternateIndex");
                }
            }
            Map<AlleleKey, List<AlleleRecord>> acceptedBySourceKey = new HashMap<>();
            for (AlleleRecord allele : alleles) {
                if (allele.inputStatus() == InputStatus.ACCEPTED) {
                    acceptedBySourceKey.computeIfAbsent(allele.sourceKey(), ignored -> new ArrayList<>())
                            .add(allele);
                }
            }
            for (List<AlleleRecord> sameSourceKey : acceptedBySourceKey.values()) {
                boolean duplicate = sameSourceKey.size() > 1;
                for (AlleleRecord allele : sameSourceKey) {
                    boolean declaredDuplicate = allele.inputReasonCodes().equals(
                            List.of(ReasonCode.INPUT_DUPLICATE_ALT));
                    if (duplicate != declaredDuplicate) {
                        fail(ReasonCode.LINEAGE_FIELD_INVALID, "inputReasonCodes");
                    }
                }
            }
        }
    }

    private static void validateCandidate(CandidateLinkRecord candidate,
                                          Map<String, String> engineIdToCanonicalId,
                                          Set<String> matchedEngineIds) {
        validateStreamingCandidateRecord(candidate);
        if (candidate.linkStatus() != CandidateLinkStatus.LINKED) {
            return;
        }
        String canonical = engineIdToCanonicalId.get(candidate.engineAlleleId());
        if (canonical == null || !matchedEngineIds.contains(candidate.engineAlleleId())) {
            throw new LineageValidationException(ReasonCode.LINEAGE_DANGLING_REFERENCE, "engineAlleleId");
        }
        if (!canonical.equals(candidate.analysisCanonicalVariantId())) {
            fail(ReasonCode.LINEAGE_DANGLING_REFERENCE, "analysisCanonicalVariantId");
        }
    }

    /** Reuses every candidate-local rule; cross-record engine binding remains spool-validated. */
    static void validateStreamingCandidateRecord(CandidateLinkRecord candidate) {
        require(candidate.candidateSource(), "candidateSource");
        require(candidate.linkStatus(), "linkStatus");
        validateReasons(candidate.reasonCodes(), "reasonCodes");
        if (candidate.candidateSourceOrdinal() <= 0
                || candidate.candidateSourceOrdinal() > Integer.MAX_VALUE) {
            fail(ReasonCode.CANDIDATE_SOURCE_ORDINAL_INVALID, "candidateSourceOrdinal");
        }
        String expectedId = "exomiser-v1-"
                + String.format(Locale.ROOT, "%08d", candidate.candidateSourceOrdinal());
        exact(candidate.candidateId(), expectedId, "candidateId");
        switch (candidate.linkStatus()) {
            case LINKED -> {
                if (candidate.engineAlleleId() == null
                        || candidate.analysisCanonicalVariantId() == null
                        || !candidate.reasonCodes().isEmpty()) {
                    fail(ReasonCode.LINEAGE_FIELD_INVALID, "linkStatus");
                }
            }
            case UNMATCHED -> validateUnlinkedCandidate(
                    candidate, ReasonCode.CANDIDATE_ALLELE_UNMATCHED);
            case AMBIGUOUS -> validateUnlinkedCandidate(
                    candidate, ReasonCode.CANDIDATE_ALLELE_AMBIGUOUS);
            case CONFLICT -> validateUnlinkedCandidate(
                    candidate, ReasonCode.CANDIDATE_IDENTITY_CONFLICT);
        }
    }

    private static void validateUnlinkedCandidate(CandidateLinkRecord candidate,
                                                  ReasonCode expectedReason) {
        if (candidate.engineAlleleId() != null || candidate.analysisCanonicalVariantId() != null) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, "linkStatus");
        }
        requireExactReasons(candidate.reasonCodes(), expectedReason, "reasonCodes");
    }

    private static void validateFooter(FooterRecord footer,
                                       HeaderRecord header,
                                       List<InputRecord> inputRecords,
                                       List<AlleleRecord> alleles,
                                       List<CandidateLinkRecord> candidates,
                                       Map<String, String> engineIdToCanonicalId) {
        long liftedCount = alleles.stream().mapToLong(value -> value.liftedKeys().size()).sum();
        long canonicalCount = alleles.stream().mapToLong(value -> value.canonicalKeys().size()).sum();
        long unresolvedCount = alleles.stream().filter(
                AnalysisAlleleLineageWireValidator::isUnresolved).count();
        long rejectedCount = alleles.stream().filter(
                AnalysisAlleleLineageWireValidator::isRejected).count();
        long successfulAlleles = alleles.stream().filter(value ->
                value.engineMatchStatus() == EngineMatchStatus.MATCHED
                        || value.engineMatchStatus() == EngineMatchStatus.NOT_FOUND).count();
        long unlinkedCandidates = candidates.stream().filter(value ->
                value.linkStatus() != CandidateLinkStatus.LINKED).count();
        List<ReasonCode> derivedFailureReasons = derivedFailureReasons(alleles, candidates,
                successfulAlleles > 0);
        boolean liftoverArtifactRequired = alleles.stream().anyMatch(value ->
                requiresLiftoverArtifactBinding(value.liftoverStatus()));
        boolean engineSubmitted = alleles.stream().anyMatch(value ->
                value.engineMatchStatus() != EngineMatchStatus.NOT_SUBMITTED);
        validateStreamingFooterRecord(footer, header, new StreamingFooterFacts(
                inputRecords.size(),
                alleles.size(),
                liftedCount,
                canonicalCount,
                engineIdToCanonicalId.size(),
                candidates.size(),
                unresolvedCount,
                rejectedCount,
                successfulAlleles,
                unlinkedCandidates,
                derivedFailureReasons,
                liftoverArtifactRequired,
                engineSubmitted
        ));
    }

    /** Shared footer semantics for the in-memory oracle and the bounded streaming validator. */
    static void validateStreamingFooterRecord(FooterRecord footer,
                                              HeaderRecord header,
                                              StreamingFooterFacts facts) {
        require(footer, "footer");
        require(header, "header");
        require(facts, "footerFacts");
        require(footer.status(), "status");
        validateReasons(footer.reasonCodes(), "reasonCodes");
        nonNegativeCounts(footer);
        if (footer.inputRecordCount() != facts.inputRecordCount()
                || footer.inputAlleleCount() != facts.inputAlleleCount()
                || footer.liftedAlleleCount() != facts.liftedAlleleCount()
                || footer.canonicalAlleleCount() != facts.canonicalAlleleCount()
                || footer.engineAlleleCount() != facts.engineAlleleCount()
                || footer.candidateLinkCount() != facts.candidateLinkCount()
                || footer.unresolvedAlleleCount() != facts.unresolvedAlleleCount()
                || footer.rejectedAlleleCount() != facts.rejectedAlleleCount()) {
            fail(ReasonCode.LINEAGE_COUNT_MISMATCH, "footerCounts");
        }

        long failureCount = facts.unresolvedAlleleCount()
                + facts.rejectedAlleleCount()
                + facts.unlinkedCandidateCount();
        switch (footer.status()) {
            case COMPLETE -> {
                if (failureCount != 0
                        || facts.successfulAlleleCount() != facts.inputAlleleCount()
                        || !footer.reasonCodes().isEmpty()) {
                    fail(ReasonCode.LINEAGE_FIELD_INVALID, "status");
                }
            }
            case PARTIAL -> {
                if (facts.successfulAlleleCount() == 0 || failureCount == 0
                        || !footer.reasonCodes().equals(facts.derivedFailureReasons())) {
                    fail(ReasonCode.LINEAGE_FIELD_INVALID, "status");
                }
            }
            case FAILED -> {
                if (facts.successfulAlleleCount() != 0
                        || failureCount == 0
                        || !footer.reasonCodes().equals(facts.derivedFailureReasons())) {
                    fail(ReasonCode.LINEAGE_FIELD_INVALID, "status");
                }
            }
            case LIMIT_EXCEEDED -> fail(ReasonCode.LINEAGE_REUSE_STATUS_INELIGIBLE, "status");
        }

        if (header.liftoverPolicy() == LiftoverPolicy.REQUIRED
                && facts.liftoverArtifactRequired()) {
            sha256(footer.liftedArtifactSha256(), "liftedArtifactSha256");
        } else if (footer.liftedArtifactSha256() != null) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, "liftedArtifactSha256");
        }
        if (footer.status() == DocumentStatus.COMPLETE || facts.canonicalAlleleCount() > 0) {
            sha256(footer.canonicalArtifactSha256(), "canonicalArtifactSha256");
        } else {
            optionalSha256(footer.canonicalArtifactSha256(), "canonicalArtifactSha256");
        }
        if (footer.status() == DocumentStatus.COMPLETE
                || facts.engineSubmitted()
                || facts.candidateLinkCount() > 0) {
            sha256(footer.engineArtifactSha256(), "engineArtifactSha256");
        } else {
            optionalSha256(footer.engineArtifactSha256(), "engineArtifactSha256");
        }
        exact(footer.fingerprintAlgorithm(), AnalysisAlleleLineageWire.FINGERPRINT_ALGORITHM,
                "fingerprintAlgorithm");
        sha256(footer.contentFingerprint(), "contentFingerprint");
    }

    record StreamingFooterFacts(long inputRecordCount,
                                long inputAlleleCount,
                                long liftedAlleleCount,
                                long canonicalAlleleCount,
                                long engineAlleleCount,
                                long candidateLinkCount,
                                long unresolvedAlleleCount,
                                long rejectedAlleleCount,
                                long successfulAlleleCount,
                                long unlinkedCandidateCount,
                                List<ReasonCode> derivedFailureReasons,
                                boolean liftoverArtifactRequired,
                                boolean engineSubmitted) {

        StreamingFooterFacts {
            derivedFailureReasons = List.copyOf(derivedFailureReasons);
        }

        @Override
        public String toString() {
            return "StreamingFooterFacts[redacted]";
        }
    }

    private void validateCanonicalByteLimits(Document document, HeaderRecord header) {
        long totalBytes = 0L;
        for (WireRecord record : document.recordsInCanonicalSections()) {
            int lineBytes = projector.canonicalRecordBytes(record).length;
            if (lineBytes > header.limits().maxLineBytes()
                    || lineBytes > AnalysisAlleleLineageWire.MAX_LINE_BYTES) {
                fail(ReasonCode.LINEAGE_LINE_LIMIT_EXCEEDED, "record");
            }
            totalBytes += lineBytes + 1L;
            if (totalBytes > header.limits().maxSidecarBytes()
                    || totalBytes > AnalysisAlleleLineageWire.MAX_SIDECAR_BYTES) {
                fail(ReasonCode.LINEAGE_SIZE_LIMIT_EXCEEDED, "document");
            }
        }
    }

    private void validateFingerprint(Document document) {
        byte[] expected = projector.contentFingerprint(document).getBytes(StandardCharsets.US_ASCII);
        byte[] supplied = document.footer().contentFingerprint().getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, supplied)) {
            fail(ReasonCode.LINEAGE_FINGERPRINT_MISMATCH, "contentFingerprint");
        }
    }

    private static void validateKeyList(List<AlleleKey> keys,
                                        int maxFanOut,
                                        int maxAlleleBytes,
                                        String field) {
        requireList(keys, field);
        if (keys.size() > maxFanOut) {
            fail(ReasonCode.LINEAGE_FAN_OUT_LIMIT_EXCEEDED, field);
        }
        AlleleKey previous = null;
        for (AlleleKey key : keys) {
            validateKey(key, maxAlleleBytes, field);
            if (previous != null && ALLELE_KEY_COMPARATOR.compare(previous, key) >= 0) {
                fail(ReasonCode.LINEAGE_ORDER_INVALID, field);
            }
            previous = key;
        }
    }

    private static void validateKey(AlleleKey key, int maxAlleleBytes, String field) {
        require(key, field);
        require(key.assembly(), field);
        if (!safeContig(key.chromosome())
                || key.position() <= 0
                || !explicitAllele(key.reference(), maxAlleleBytes)
                || !explicitAllele(key.alternate(), maxAlleleBytes)
                || key.reference().equals(key.alternate())) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
        }
        String canonicalId = key.assembly().name()
                + "|" + key.chromosome()
                + "|" + key.position()
                + "|" + key.reference()
                + "|" + key.alternate();
        if (!canonicalId.equals(key.canonicalVariantId())) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, "canonicalVariantId");
        }
    }

    private static void validateEngineList(List<EngineAlleleRef> values,
                                           List<AlleleKey> canonicalKeys,
                                           int maxFanOut) {
        requireList(values, "engineAlleles");
        if (values.size() > maxFanOut) {
            fail(ReasonCode.LINEAGE_FAN_OUT_LIMIT_EXCEEDED, "engineAlleles");
        }
        Map<String, AlleleKey> keysById = new HashMap<>();
        for (AlleleKey key : canonicalKeys) {
            keysById.put(key.canonicalVariantId(), key);
        }
        EngineAlleleRef previous = null;
        Set<String> ids = new HashSet<>();
        for (EngineAlleleRef value : values) {
            require(value, "engineAlleles");
            if (value.engineAlleleId() == null
                    || !ENGINE_ALLELE_ID.matcher(value.engineAlleleId()).matches()
                    || !keysById.containsKey(value.analysisCanonicalVariantId())
                    || !value.engineAlleleId().equals(AnalysisAlleleLineageWire
                    .engineAlleleId(value.analysisCanonicalVariantId()))) {
                fail(ReasonCode.LINEAGE_DANGLING_REFERENCE, "engineAlleles");
            }
            if (!ids.add(value.engineAlleleId())) {
                fail(ReasonCode.LINEAGE_DUPLICATE_ID, "engineAlleleId");
            }
            if (previous != null && compareEngine(previous, value, keysById) >= 0) {
                fail(ReasonCode.LINEAGE_ORDER_INVALID, "engineAlleles");
            }
            previous = value;
        }
    }

    private static int compareEngine(EngineAlleleRef left,
                                     EngineAlleleRef right,
                                     Map<String, AlleleKey> keysById) {
        int key = ALLELE_KEY_COMPARATOR.compare(
                keysById.get(left.analysisCanonicalVariantId()),
                keysById.get(right.analysisCanonicalVariantId()));
        return key != 0 ? key : left.engineAlleleId().compareTo(right.engineAlleleId());
    }

    private static void validateReasons(List<ReasonCode> values, String field) {
        requireList(values, field);
        if (values.size() > AnalysisAlleleLineageWire.MAX_REASON_CODES) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
        }
        ReasonCode previous = null;
        for (ReasonCode value : values) {
            require(value, field);
            if (previous != null && previous.ordinal() >= value.ordinal()) {
                fail(ReasonCode.LINEAGE_ORDER_INVALID, field);
            }
            previous = value;
        }
    }

    private static <T extends Enum<T>> void validateEnumList(List<T> values, String field) {
        requireList(values, field);
        T previous = null;
        for (T value : values) {
            require(value, field);
            if (previous != null && previous.ordinal() >= value.ordinal()) {
                fail(ReasonCode.LINEAGE_ORDER_INVALID, field);
            }
            previous = value;
        }
    }

    private static void validateFanIn(Map<String, Set<String>> fanIn,
                                      int maxFanIn,
                                      String field) {
        for (Set<String> sources : fanIn.values()) {
            if (sources.size() > maxFanIn) {
                fail(ReasonCode.LINEAGE_FAN_IN_LIMIT_EXCEEDED, field);
            }
        }
    }

    private static boolean isRejected(AlleleRecord allele) {
        return allele.inputStatus() == InputStatus.REJECTED
                || allele.liftoverStatus() == LiftoverStatus.UNMAPPED
                || allele.liftoverStatus() == LiftoverStatus.REF_MISMATCH
                || allele.liftoverStatus() == LiftoverStatus.REJECTED
                || allele.liftoverStatus() == LiftoverStatus.UNAVAILABLE
                || allele.liftoverStatus() == LiftoverStatus.SKIPPED
                || allele.normalizationStatus() == NormalizationStatus.REJECTED;
    }

    private static boolean isUnresolved(AlleleRecord allele) {
        return !isRejected(allele)
                && (allele.liftoverStatus() == LiftoverStatus.AMBIGUOUS
                || allele.normalizationStatus() == NormalizationStatus.UNRESOLVED
                || allele.normalizationStatus() == NormalizationStatus.NOT_SUBMITTED
                || allele.engineMatchStatus() == EngineMatchStatus.AMBIGUOUS
                || allele.engineMatchStatus() == EngineMatchStatus.CONFLICT
                || allele.engineMatchStatus() == EngineMatchStatus.NOT_SUBMITTED);
    }

    private static List<ReasonCode> derivedFailureReasons(List<AlleleRecord> alleles,
                                                          List<CandidateLinkRecord> candidates,
                                                          boolean hasSuccessfulAllele) {
        EnumSet<ReasonCode> reasons = EnumSet.noneOf(ReasonCode.class);
        boolean hasLiftoverFailure = false;
        for (AlleleRecord allele : alleles) {
            if (!isRejected(allele) && !isUnresolved(allele)) {
                continue;
            }
            if (allele.inputStatus() == InputStatus.REJECTED) {
                reasons.addAll(allele.inputReasonCodes());
            } else if (isLiftoverFailure(allele.liftoverStatus())) {
                reasons.addAll(allele.liftoverReasonCodes());
            } else if (allele.normalizationStatus() == NormalizationStatus.REJECTED
                    || allele.normalizationStatus() == NormalizationStatus.UNRESOLVED) {
                reasons.addAll(allele.normalizationReasonCodes());
            } else {
                reasons.addAll(allele.engineReasonCodes());
            }
            hasLiftoverFailure |= isLiftoverFailure(allele.liftoverStatus());
        }
        for (CandidateLinkRecord candidate : candidates) {
            if (candidate.linkStatus() != CandidateLinkStatus.LINKED) {
                reasons.addAll(candidate.reasonCodes());
            }
        }
        if (hasSuccessfulAllele && hasLiftoverFailure) {
            reasons.add(ReasonCode.LIFTOVER_PARTIAL);
        }
        return List.copyOf(reasons);
    }

    private static int compareAlleleRecords(AlleleRecord left, AlleleRecord right) {
        int record = Long.compare(left.sourceRecordOrdinal(), right.sourceRecordOrdinal());
        return record != 0 ? record
                : Integer.compare(left.sourceAlternateIndex(), right.sourceAlternateIndex());
    }

    private static int compareCandidates(CandidateLinkRecord left,
                                         CandidateLinkRecord right,
                                         Map<String, AlleleKey> canonicalById) {
        AlleleKey leftKey = canonicalById.get(left.analysisCanonicalVariantId());
        AlleleKey rightKey = canonicalById.get(right.analysisCanonicalVariantId());
        if (leftKey == null && rightKey != null) {
            return 1;
        }
        if (leftKey != null && rightKey == null) {
            return -1;
        }
        if (leftKey != null) {
            int key = ALLELE_KEY_COMPARATOR.compare(leftKey, rightKey);
            if (key != 0) {
                return key;
            }
        }
        int source = left.candidateSource().compareTo(right.candidateSource());
        if (source != 0) {
            return source;
        }
        int ordinal = Long.compare(left.candidateSourceOrdinal(), right.candidateSourceOrdinal());
        return ordinal != 0 ? ordinal : left.candidateId().compareTo(right.candidateId());
    }

    private static void cardinality(RelationCardinality supplied, int size, String field) {
        RelationCardinality expected = size == 0
                ? RelationCardinality.ONE_TO_ZERO
                : size == 1 ? RelationCardinality.ONE_TO_ONE : RelationCardinality.ONE_TO_MANY;
        if (supplied != expected) {
            fail(ReasonCode.LINEAGE_CARDINALITY_CONFLICT, field);
        }
    }

    private static void validateCommon(WireRecord record,
                                       RecordType expectedType,
                                       long expectedOrdinal) {
        exact(record.schemaVersion(), AnalysisAlleleLineageWire.SCHEMA_VERSION, "schemaVersion",
                ReasonCode.LINEAGE_SCHEMA_UNSUPPORTED);
        if (record.recordType() != expectedType) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, "recordType");
        }
        if (record.recordOrdinal() != expectedOrdinal) {
            fail(ReasonCode.LINEAGE_ORDER_INVALID, "recordOrdinal");
        }
    }

    private static void nonNegativeCounts(FooterRecord footer) {
        if (footer.inputRecordCount() < 0
                || footer.inputAlleleCount() < 0
                || footer.liftedAlleleCount() < 0
                || footer.canonicalAlleleCount() < 0
                || footer.engineAlleleCount() < 0
                || footer.candidateLinkCount() < 0
                || footer.unresolvedAlleleCount() < 0
                || footer.rejectedAlleleCount() < 0) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, "footerCounts");
        }
    }

    private static boolean explicitAllele(String value, int maxAlleleBytes) {
        return value != null
                && utf8Length(value) <= maxAlleleBytes
                && utf8Length(value) <= AnalysisAlleleLineageWire.MAX_ALLELE_BYTES
                && SEQUENCE_ALLELE.matcher(value).matches();
    }

    private static boolean safeContig(String value) {
        return value != null
                && utf8Length(value) <= 128
                && (CANONICAL_CONTIG.matcher(value).matches()
                || ACCESSION_CONTIG.matcher(value).matches()
                || UCSC_AUXILIARY_CONTIG.matcher(value).matches());
    }

    private static int contigRank(String contig) {
        if (contig == null) {
            return Integer.MAX_VALUE;
        }
        if (contig.matches("[1-9]|1[0-9]|2[0-2]")) {
            return Integer.parseInt(contig);
        }
        return switch (contig) {
            case "X" -> 23;
            case "Y" -> 24;
            case "MT" -> 25;
            default -> 100;
        };
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static void toolVersion(String value, String field) {
        if (value == null || utf8Length(value) > 64 || !TOOL_VERSION.matcher(value).matches()) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
        }
    }

    private static void sha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
        }
    }

    private static void optionalSha256(String value, String field) {
        if (value != null) {
            sha256(value, field);
        }
    }

    private static void requireExactReasons(List<ReasonCode> values,
                                            ReasonCode expected,
                                            String field) {
        if (!values.equals(List.of(expected))) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
        }
    }

    private static void requireOnlyReasons(List<ReasonCode> values,
                                           Set<ReasonCode> allowed,
                                           String field) {
        if (values.isEmpty() || !allowed.containsAll(values)) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
        }
    }

    private static void allNull(String field, Object... values) {
        for (Object value : values) {
            if (value != null) {
                fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
            }
        }
    }

    private static void positiveWithin(long value, long maximum, String field) {
        if (value <= 0 || value > maximum) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
        }
    }

    private static void exact(String actual, String expected, String field) {
        exact(actual, expected, field, ReasonCode.LINEAGE_FIELD_INVALID);
    }

    private static void exact(String actual,
                              String expected,
                              String field,
                              ReasonCode code) {
        if (!expected.equals(actual)) {
            fail(code, field);
        }
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            fail(ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING, field);
        }
        return value;
    }

    private static <T> List<T> requireList(List<T> values, String field) {
        return require(values, field);
    }

    private static void fail(ReasonCode code, String field) {
        throw new LineageValidationException(code, field);
    }

    private record SourcePair(long sourceRecordOrdinal, int sourceAlternateIndex) {
    }

    private record CandidateSourceKey(AnalysisAlleleLineageWire.CandidateSource source,
                                      long sourceOrdinal) {
    }

    private record EngineOutcome(EngineMatchStatus status,
                                 List<ReasonCode> reasonCodes,
                                 List<EngineAlleleRef> engineAlleles) {
    }

    private record SourceOutcome(LiftoverStatus liftoverStatus,
                                 List<ReasonCode> liftoverReasonCodes,
                                 List<AlleleKey> liftedKeys,
                                 RelationCardinality liftoverCardinality,
                                 List<AnalysisAlleleLineageWire.LiftoverTransform> liftoverTransforms,
                                 Boolean targetReferenceValidated,
                                 NormalizationStatus normalizationStatus,
                                 List<ReasonCode> normalizationReasonCodes,
                                 List<AlleleKey> canonicalKeys,
                                 RelationCardinality normalizationCardinality,
                                 List<AnalysisAlleleLineageWire.NormalizationTransform>
                                         normalizationTransforms,
                                 EngineMatchStatus engineMatchStatus,
                                 List<ReasonCode> engineReasonCodes,
                                 List<EngineAlleleRef> engineAlleles) {

        private static SourceOutcome from(AlleleRecord allele) {
            return new SourceOutcome(
                    allele.liftoverStatus(),
                    allele.liftoverReasonCodes(),
                    allele.liftedKeys(),
                    allele.liftoverCardinality(),
                    allele.liftoverTransforms(),
                    allele.targetReferenceValidated(),
                    allele.normalizationStatus(),
                    allele.normalizationReasonCodes(),
                    allele.canonicalKeys(),
                    allele.normalizationCardinality(),
                    allele.normalizationTransforms(),
                    allele.engineMatchStatus(),
                    allele.engineReasonCodes(),
                    allele.engineAlleles()
            );
        }
    }

    private static boolean isLiftoverFailure(LiftoverStatus status) {
        return status == LiftoverStatus.UNMAPPED
                || status == LiftoverStatus.REF_MISMATCH
                || status == LiftoverStatus.AMBIGUOUS
                || status == LiftoverStatus.REJECTED
                || status == LiftoverStatus.UNAVAILABLE
                || status == LiftoverStatus.SKIPPED;
    }

    private static boolean requiresLiftoverArtifactBinding(LiftoverStatus status) {
        return status == LiftoverStatus.LIFTED
                || status == LiftoverStatus.AMBIGUOUS
                || status == LiftoverStatus.UNMAPPED
                || status == LiftoverStatus.REF_MISMATCH
                || status == LiftoverStatus.REJECTED;
    }
}
