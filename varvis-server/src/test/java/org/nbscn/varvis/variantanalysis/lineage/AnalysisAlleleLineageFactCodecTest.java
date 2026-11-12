package org.nbscn.varvis.variantanalysis.lineage;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageFactCodec.CoverageFact;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageFactCodec.Envelope;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageFactCodec.LinkFact;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleKey;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateSource;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.EngineAlleleRef;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RecordType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisAlleleLineageFactCodecTest {

    private static final byte FACT_FORMAT_VERSION = 0x01;
    private static final String PRIVACY_CANARY =
            "patient-Alice-C:\\private\\lineage\\secret-token";

    @Test
    void prefixesEveryKeyWithVersionAndDisjointKindAndHasStableCoverageKnownAnswer() {
        byte[] coverage = AnalysisAlleleLineageFactCodec.coverageKey(1L);
        assertArrayEquals(
                HexFormat.of().parseHex("01100000000000000001"), coverage);

        List<KeyExpectation> keys = List.of(
                new KeyExpectation(coverage, AnalysisAlleleLineageFactCodec.COVERAGE),
                new KeyExpectation(
                        AnalysisAlleleLineageFactCodec.tokenKey("token"),
                        AnalysisAlleleLineageFactCodec.TOKEN),
                new KeyExpectation(
                        AnalysisAlleleLineageFactCodec.sourceOutcomeKey(allele("token")),
                        AnalysisAlleleLineageFactCodec.SOURCE_OUTCOME),
                new KeyExpectation(
                        AnalysisAlleleLineageFactCodec.duplicateSourceKey(allele("token")),
                        AnalysisAlleleLineageFactCodec.DUPLICATE_SOURCE),
                new KeyExpectation(
                        AnalysisAlleleLineageFactCodec.liftedRelationKey(key(
                                GenomeAssembly.GRCh38, "1", 100, "A", "C")),
                        AnalysisAlleleLineageFactCodec.LIFTED_RELATION),
                new KeyExpectation(
                        AnalysisAlleleLineageFactCodec.canonicalRelationKey(key(
                                GenomeAssembly.GRCh38, "1", 100, "A", "C")),
                        AnalysisAlleleLineageFactCodec.CANONICAL_RELATION),
                new KeyExpectation(
                        AnalysisAlleleLineageFactCodec.engineJoinKey("engine-id"),
                        AnalysisAlleleLineageFactCodec.ENGINE_JOIN),
                new KeyExpectation(
                        AnalysisAlleleLineageFactCodec.candidateIdKey(
                                candidate("candidate", 1L, null, null)),
                        AnalysisAlleleLineageFactCodec.CANDIDATE_ID),
                new KeyExpectation(
                        AnalysisAlleleLineageFactCodec.candidateSourceKey(
                                candidate("candidate", 1L, null, null)),
                        AnalysisAlleleLineageFactCodec.CANDIDATE_SOURCE),
                new KeyExpectation(
                        AnalysisAlleleLineageFactCodec.candidateOrderKey(
                                candidate("candidate", 1L, null, null)),
                        AnalysisAlleleLineageFactCodec.CANDIDATE_ORDER)
        );

        for (KeyExpectation expectation : keys) {
            assertEquals(FACT_FORMAT_VERSION, expectation.key()[0]);
            assertEquals(expectation.kind(), expectation.key()[1]);
            assertEquals(expectation.kind(),
                    AnalysisAlleleLineageFactCodec.kind(expectation.key()));
        }
        assertEquals(keys.size(), keys.stream().map((KeyExpectation value) -> value.kind()).distinct().count());
    }

    @Test
    void lengthDelimitsEveryVariableComponentAndKeepsNamespacesCollisionFree() {
        CandidateLinkRecord left = candidate("c", 1L, "engine", "ab");
        CandidateLinkRecord right = candidate("bc", 1L, "engine", "a");
        assertFalse(Arrays.equals(
                AnalysisAlleleLineageFactCodec.candidateLinkValue(left),
                AnalysisAlleleLineageFactCodec.candidateLinkValue(right)),
                "['ab','c'] must not collide with ['a','bc']");

        AlleleRecord first = alleleWithSource("c", "ab");
        AlleleRecord second = alleleWithSource("bc", "a");
        AlleleKey lifted = key(GenomeAssembly.GRCh38, "1", 101, "A", "G");
        assertFalse(Arrays.equals(
                AnalysisAlleleLineageFactCodec.liftedRelationValue(first),
                AnalysisAlleleLineageFactCodec.liftedRelationValue(second)),
                "the relation envelope must delimit source identity and token");

        String shared = "same-logical-text";
        assertFalse(Arrays.equals(
                AnalysisAlleleLineageFactCodec.tokenKey(shared),
                AnalysisAlleleLineageFactCodec.engineJoinKey(shared)),
                "fact-family namespaces must be disjoint");
        assertFalse(Arrays.equals(
                AnalysisAlleleLineageFactCodec.liftedRelationKey(lifted),
                AnalysisAlleleLineageFactCodec.canonicalRelationKey(lifted)));
        assertFalse(Arrays.equals(
                AnalysisAlleleLineageFactCodec.tokenKey("a"),
                AnalysisAlleleLineageFactCodec.tokenKey("a\u0000")),
                "length prefixes must distinguish embedded NUL from a shorter string");
    }

    @Test
    void roundTripsCoverageFactsAndRejectsInvalidBooleanRepresentations() {
        InputRecord input = AnalysisAlleleLineageWireFixtures.inputRecord(1L, 7L, 2);
        CoverageFact inputFact = AnalysisAlleleLineageFactCodec.decodeCoverageValue(
                AnalysisAlleleLineageFactCodec.inputCoverageValue(input));
        assertTrue(inputFact.inputRecord());
        assertEquals(2, inputFact.sourceAlternateCount());
        assertEquals(0, inputFact.sourceAlternateIndex());
        assertEquals(input.inputRecordStatus().ordinal(), inputFact.inputStatusOrdinal());

        AlleleRecord allele = AnalysisAlleleLineageWireFixtures.matchedAllele(
                2L, 7L, 2, 3, "0123456789abcdef01234567",
                key(GenomeAssembly.GRCh38, "1", 100, "A", "G"), "engine-id");
        CoverageFact alleleFact = AnalysisAlleleLineageFactCodec.decodeCoverageValue(
                AnalysisAlleleLineageFactCodec.alleleCoverageValue(allele));
        assertFalse(alleleFact.inputRecord());
        assertEquals(3, alleleFact.sourceAlternateCount());
        assertEquals(2, alleleFact.sourceAlternateIndex());
        assertEquals(-1, alleleFact.inputStatusOrdinal());

        assertEquals(7L, AnalysisAlleleLineageFactCodec.decodeCoverageKey(
                AnalysisAlleleLineageFactCodec.coverageKey(7L)));
        assertFalse(AnalysisAlleleLineageFactCodec.decodeBoolean(new byte[] {0}));
        assertTrue(AnalysisAlleleLineageFactCodec.decodeBoolean(new byte[] {1}));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeBoolean(new byte[] {2}));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeBoolean(new byte[] {0, 0}));
    }

    @Test
    void roundTripsRelationEnvelopesAndEngineCandidateLinks() {
        AlleleRecord allele = allele("0123456789abcdef01234567");
        AlleleKey lifted = key(GenomeAssembly.GRCh38, "2", 200, "C", "T");
        Envelope liftedEnvelope = AnalysisAlleleLineageFactCodec.decodeEnvelope(
                AnalysisAlleleLineageFactCodec.liftedRelationValue(allele));
        assertArrayEquals(
                allele.sourceKey().canonicalVariantId().getBytes(StandardCharsets.UTF_8),
                liftedEnvelope.first());
        assertArrayEquals(
                allele.sourceAlleleToken().getBytes(StandardCharsets.US_ASCII),
                liftedEnvelope.second());

        Envelope canonicalEnvelope = AnalysisAlleleLineageFactCodec.decodeEnvelope(
                AnalysisAlleleLineageFactCodec.canonicalRelationValue(allele));
        AlleleRecord sameOutcomeDifferentToken = allele("fedcba9876543210fedcba98");
        Envelope sameOutcome = AnalysisAlleleLineageFactCodec.decodeEnvelope(
                AnalysisAlleleLineageFactCodec.canonicalRelationValue(
                        sameOutcomeDifferentToken));
        assertTrue(canonicalEnvelope.sameFirst(sameOutcome));
        assertArrayEquals(
                allele.sourceAlleleToken().getBytes(StandardCharsets.US_ASCII),
                canonicalEnvelope.second());

        EngineAlleleRef engine = new EngineAlleleRef(
                "engine-id", allele.sourceKey().canonicalVariantId());
        LinkFact engineFact = AnalysisAlleleLineageFactCodec.decodeLinkValue(
                AnalysisAlleleLineageFactCodec.engineDefinitionValue(allele, engine));
        assertTrue(engineFact.engineDefinition());
        assertArrayEquals(engine.analysisCanonicalVariantId().getBytes(StandardCharsets.UTF_8),
                engineFact.canonicalId());
        assertArrayEquals(allele.sourceAlleleToken().getBytes(StandardCharsets.UTF_8),
                engineFact.memberIdentity());

        CandidateLinkRecord candidate = candidate(
                "candidate-id", 1L, "engine-id", engine.analysisCanonicalVariantId());
        LinkFact candidateFact = AnalysisAlleleLineageFactCodec.decodeLinkValue(
                AnalysisAlleleLineageFactCodec.candidateLinkValue(candidate));
        assertFalse(candidateFact.engineDefinition());
        assertArrayEquals(engineFact.canonicalId(), candidateFact.canonicalId());
        assertArrayEquals("candidate-id".getBytes(StandardCharsets.UTF_8),
                candidateFact.memberIdentity());
        assertTrue(engineFact.sameCanonical(candidateFact));

        Envelope differentSource = AnalysisAlleleLineageFactCodec.decodeEnvelope(
                AnalysisAlleleLineageFactCodec.liftedRelationValue(
                        alleleWithSource(allele.sourceAlleleToken(),
                                lifted.canonicalVariantId())));
        assertFalse(liftedEnvelope.sameFirst(differentSource));
    }

    @Test
    void candidateOrderKeyMatchesA2ComparatorForRepresentativeBoundaries() {
        List<OrderedCandidate> candidates = List.of(
                ordered(GenomeAssembly.GRCh37, "1", 1, "A", "C", 1L, "candidate-a"),
                ordered(GenomeAssembly.GRCh37, "1", 2, "A", "C", 1L, "candidate-b"),
                ordered(GenomeAssembly.GRCh37, "2", 1, "A", "C", 1L, "candidate-c"),
                ordered(GenomeAssembly.GRCh37, "X", 1, "A", "C", 1L, "candidate-d"),
                ordered(GenomeAssembly.GRCh37, "GL0001", 1, "A", "C", 1L, "candidate-e"),
                ordered(GenomeAssembly.GRCh37, "GL0002", 1, "A", "C", 1L, "candidate-f"),
                ordered(GenomeAssembly.GRCh38, "1", 1, "A", "C", 1L, "candidate-g-a"),
                ordered(GenomeAssembly.GRCh38, "1", 1, "A", "C", 1L, "candidate-g-b"),
                ordered(GenomeAssembly.GRCh38, "1", 1, "A", "C", 2L, "candidate-g-c"),
                ordered(GenomeAssembly.GRCh38, "1", 1, "A", "G", 1L, "candidate-h"),
                ordered(GenomeAssembly.GRCh38, "1", 1, "AA", "G", 1L, "candidate-i"),
                ordered(GenomeAssembly.GRCh38, "1", Integer.MAX_VALUE, "A", "C",
                        Long.MAX_VALUE, "candidate-j"),
                ordered(null, null, 0, null, null, 1L, "candidate-unlinked-a"),
                ordered(null, null, 0, null, null, 2L, "candidate-unlinked-b")
        );

        Comparator<OrderedCandidate> reference = (left, right) -> {
            if (left.key() == null && right.key() != null) {
                return 1;
            }
            if (left.key() != null && right.key() == null) {
                return -1;
            }
            if (left.key() != null) {
                int keyComparison = AnalysisAlleleLineageWireValidator.ALLELE_KEY_COMPARATOR
                        .compare(left.key(), right.key());
                if (keyComparison != 0) {
                    return keyComparison;
                }
            }
            int sourceComparison = left.candidate().candidateSource()
                    .compareTo(right.candidate().candidateSource());
            if (sourceComparison != 0) {
                return sourceComparison;
            }
            int ordinalComparison = Long.compare(
                    left.candidate().candidateSourceOrdinal(),
                    right.candidate().candidateSourceOrdinal());
            return ordinalComparison != 0 ? ordinalComparison
                    : left.candidate().candidateId().compareTo(right.candidate().candidateId());
        };

        for (OrderedCandidate left : candidates) {
            for (OrderedCandidate right : candidates) {
                int expected = Integer.signum(reference.compare(left, right));
                int actual = Integer.signum(Arrays.compareUnsigned(
                        AnalysisAlleleLineageFactCodec.candidateOrderKey(left.candidate()),
                        AnalysisAlleleLineageFactCodec.candidateOrderKey(right.candidate())));
                assertEquals(expected, actual,
                        () -> "candidate order differs for " + left.label()
                                + " and " + right.label());
            }
        }

        List<OrderedCandidate> shuffled = new ArrayList<>(candidates);
        shuffled.sort((left, right) -> Arrays.compareUnsigned(
                AnalysisAlleleLineageFactCodec.candidateOrderKey(left.candidate()),
                AnalysisAlleleLineageFactCodec.candidateOrderKey(right.candidate())));
        assertEquals(candidates, shuffled, "the representative list is already in A2 order");
        assertEquals(Long.MAX_VALUE,
                AnalysisAlleleLineageFactCodec.decodeCandidateOrderValue(
                        AnalysisAlleleLineageFactCodec.candidateOrderValue(Long.MAX_VALUE)));
    }

    @Test
    void decodersFailClosedOnWrongVersionKindTruncationAndTrailingBytes() {
        byte[] coverageKey = AnalysisAlleleLineageFactCodec.coverageKey(1L);
        byte[] wrongVersion = coverageKey.clone();
        wrongVersion[0] = 2;
        byte[] wrongKind = coverageKey.clone();
        wrongKind[1] = AnalysisAlleleLineageFactCodec.TOKEN;

        assertInvalid(() -> AnalysisAlleleLineageFactCodec.kind(null));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.kind(new byte[] {1}));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.kind(wrongVersion));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeCoverageKey(wrongVersion));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeCoverageKey(wrongKind));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeCoverageKey(
                Arrays.copyOf(coverageKey, coverageKey.length - 1)));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeCoverageKey(
                append(coverageKey, (byte) 0)));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeCoverageKey(
                AnalysisAlleleLineageFactCodec.coverageKey(0L)));

        byte[] coverageValue = AnalysisAlleleLineageFactCodec.inputCoverageValue(
                AnalysisAlleleLineageWireFixtures.inputRecord(1L, 1L, 1));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeCoverageValue(new byte[] {99}));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeCoverageValue(
                Arrays.copyOf(coverageValue, coverageValue.length - 1)));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeCoverageValue(
                append(coverageValue, (byte) 0)));

        byte[] link = AnalysisAlleleLineageFactCodec.candidateLinkValue(
                candidate("candidate", 1L, "engine", "GRCh38|1|1|A|C"));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeLinkValue(
                Arrays.copyOf(link, link.length - 1)));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeLinkValue(
                append(link, (byte) 0)));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeLinkValue(
                new byte[] {99, 0, 0, 0, 0, 0, 0, 0, 0}));

        byte[] envelope = AnalysisAlleleLineageFactCodec.liftedRelationValue(allele("token"));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeEnvelope(
                Arrays.copyOf(envelope, envelope.length - 1)));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeEnvelope(
                append(envelope, (byte) 0)));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeEnvelope(
                new byte[] {-1, -1, -1, -1}));

        byte[] order = AnalysisAlleleLineageFactCodec.candidateOrderValue(4L);
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeCandidateOrderValue(
                Arrays.copyOf(order, order.length - 1)));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.decodeCandidateOrderValue(
                append(order, (byte) 0)));
        assertDoesNotThrow(() -> AnalysisAlleleLineageFactCodec.candidateOrderKey(
                candidate("candidate", 1L, "engine", "not-a-canonical-id")));
        assertInvalid(() -> AnalysisAlleleLineageFactCodec.candidateOrderKey(
                candidate("candidate-病例", 1L, null, null)));
    }

    @Test
    void decodedFactsDefensivelyCopyArraysAndRedactDiagnostics() {
        Envelope envelope = AnalysisAlleleLineageFactCodec.decodeEnvelope(
                AnalysisAlleleLineageFactCodec.liftedRelationValue(
                        alleleWithSource(PRIVACY_CANARY, "GRCh38|1|1|A|C")));
        byte[] exposedFirst = envelope.first();
        byte[] expectedFirst = envelope.first();
        exposedFirst[0] ^= 0x7f;
        assertArrayEquals(expectedFirst, envelope.first());
        assertEquals("Envelope[redacted]", envelope.toString());
        assertFalse(envelope.toString().contains(PRIVACY_CANARY));

        CandidateLinkRecord candidate = candidate(
                PRIVACY_CANARY, 1L, "engine", "GRCh38|1|1|A|C");
        LinkFact link = AnalysisAlleleLineageFactCodec.decodeLinkValue(
                AnalysisAlleleLineageFactCodec.candidateLinkValue(candidate));
        byte[] exposedCanonical = link.canonicalId();
        byte[] expectedCanonical = link.canonicalId();
        byte[] exposedMember = link.memberIdentity();
        byte[] expectedMember = link.memberIdentity();
        exposedCanonical[0] ^= 0x7f;
        exposedMember[0] ^= 0x7f;
        assertArrayEquals(expectedCanonical, link.canonicalId());
        assertArrayEquals(expectedMember, link.memberIdentity());
        assertEquals("LinkFact[redacted]", link.toString());
        assertFalse(link.toString().contains(PRIVACY_CANARY));

        CoverageFact coverage = AnalysisAlleleLineageFactCodec.decodeCoverageValue(
                AnalysisAlleleLineageFactCodec.inputCoverageValue(
                        AnalysisAlleleLineageWireFixtures.inputRecord(1L, 1L, 1)));
        assertEquals("CoverageFact[redacted]", coverage.toString());
    }

    @Test
    void capacityProofUsesExactMinimumKeyBytesAndNPlusOneArithmetic() {
        String minimumCanonicalKey = "{\"assembly\":\"GRCh37\",\"chromosome\":\"1\","
                + "\"position\":1,\"reference\":\"A\",\"alternate\":\"C\","
                + "\"canonicalVariantId\":\"GRCh37|1|1|A|C\"}";
        assertEquals(121, minimumCanonicalKey.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(121,
                AnalysisAlleleLineageStreamingValidator.MIN_CANONICAL_ALLELE_KEY_JSON_BYTES);

        long maximumLifted = AnalysisAlleleLineageWire.MAX_SIDECAR_BYTES / 121L;
        assertEquals(4_436_949L, maximumLifted);
        assertEquals(maximumLifted,
                AnalysisAlleleLineageStreamingValidator.MAX_LIFTED_RELATION_FACTS);
        assertTrue(maximumLifted * 121L <= AnalysisAlleleLineageWire.MAX_SIDECAR_BYTES);
        assertTrue((maximumLifted + 1L) * 121L
                > AnalysisAlleleLineageWire.MAX_SIDECAR_BYTES);

        long provenMaximum = AnalysisAlleleLineageWire.MAX_BODY_RECORD_COUNT
                + 5L * AnalysisAlleleLineageWire.MAX_INPUT_ALLELE_COUNT
                + 3L * AnalysisAlleleLineageWire.MAX_CANDIDATE_LINK_COUNT
                + maximumLifted;
        assertEquals(12_236_949L, provenMaximum);
        assertEquals(provenMaximum,
                AnalysisAlleleLineageStreamingValidator.PROVEN_MAX_SEMANTIC_FACTS);
    }

    private static OrderedCandidate ordered(GenomeAssembly assembly,
                                            String chromosome,
                                            int position,
                                            String reference,
                                            String alternate,
                                            long sourceOrdinal,
                                            String candidateId) {
        AlleleKey key = assembly == null ? null
                : key(assembly, chromosome, position, reference, alternate);
        return new OrderedCandidate(
                candidate(candidateId, sourceOrdinal,
                        key == null ? null : "engine-" + candidateId,
                        key == null ? null : key.canonicalVariantId()),
                key,
                candidateId);
    }

    private static AlleleRecord allele(String token) {
        AlleleKey key = key(GenomeAssembly.GRCh38, "1", 100, "A", "G");
        return AnalysisAlleleLineageWireFixtures.matchedAllele(
                2L, 1L, 1, 1, token, key, "engine-id");
    }

    private static AlleleRecord alleleWithSource(String token, String canonicalVariantId) {
        AlleleKey source = new AlleleKey(
                GenomeAssembly.GRCh38, "1", 100, "A", "G", canonicalVariantId);
        return AnalysisAlleleLineageWireFixtures.matchedAllele(
                2L, 1L, 1, 1, token, source, "engine-id");
    }

    private static AlleleKey key(GenomeAssembly assembly,
                                 String chromosome,
                                 int position,
                                 String reference,
                                 String alternate) {
        return AnalysisAlleleLineageWireFixtures.key(
                assembly, chromosome, position, reference, alternate);
    }

    private static CandidateLinkRecord candidate(String candidateId,
                                                 long sourceOrdinal,
                                                 String engineId,
                                                 String canonicalVariantId) {
        boolean linked = engineId != null || canonicalVariantId != null;
        return new CandidateLinkRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.CANDIDATE_LINK,
                1L,
                candidateId,
                CandidateSource.EXOMISER,
                sourceOrdinal,
                engineId,
                canonicalVariantId,
                linked ? CandidateLinkStatus.LINKED : CandidateLinkStatus.UNMATCHED,
                linked ? List.of() : List.of(ReasonCode.CANDIDATE_ALLELE_UNMATCHED)
        );
    }

    private static byte[] append(byte[] value, byte trailing) {
        byte[] result = Arrays.copyOf(value, value.length + 1);
        result[result.length - 1] = trailing;
        return result;
    }

    private static void assertInvalid(Runnable action) {
        LineageValidationException exception =
                assertThrows(LineageValidationException.class, action::run);
        assertEquals(ReasonCode.LINEAGE_FIELD_INVALID, exception.getReasonCode());
        assertEquals("semanticFact", exception.getField());
        assertFalse(exception.toString().contains(PRIVACY_CANARY));
    }

    private record KeyExpectation(byte[] key, byte kind) {
        private KeyExpectation {
            key = key.clone();
        }

        @Override
        public byte[] key() {
            return key.clone();
        }
    }

    private record OrderedCandidate(CandidateLinkRecord candidate,
                                    AlleleKey key,
                                    String label) {
    }
}
