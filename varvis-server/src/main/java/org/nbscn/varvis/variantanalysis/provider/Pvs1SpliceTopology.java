package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import org.springframework.lang.Nullable;
import java.util.function.LongUnaryOperator;

/** Projects a source-bound CDS deletion onto the full transcript exon geometry.
 * The caller must first validate the model identity, sources and event qualification.
 * This class neither edits sequence nor infers that an RNA outcome is qualified.
 */
final class Pvs1SpliceTopology {
    static final String VERSION = "1.1.0";
    private Pvs1SpliceTopology() { }

    /** Inclusive, CDS-relative transcript offsets; UTR positions may be zero/negative
     * or greater than the altered CDS length. Reference ordinals retain policy identity.
     */
    record RetainedExon(int referenceOrdinal, int alteredOrdinal, int alteredCdsStart, int alteredCdsEnd) {
        int exonLength() { return Math.toIntExact((long) alteredCdsEnd - alteredCdsStart + 1); }
    }

    /** Offset of the last base before a retained exon-exon junction, in altered CDS coordinates. */
    record Junction(int leftReferenceOrdinal, int rightReferenceOrdinal, int leftAlteredOrdinal,
                    int rightAlteredOrdinal, int alteredCdsPosition) { }

    record PtcClassification(List<RetainedExon> exons) {
        PtcClassification { exons = List.copyOf(exons); }
        RetainedExon startExon() { return exons.get(0); }
        RetainedExon endExon() { return exons.get(exons.size() - 1); }
        boolean crossesJunction() { return exons.size() > 1; }
        int maximumExonLength() { return exons.stream().mapToInt(e -> java.util.Objects.requireNonNull(e).exonLength()).max().orElseThrow(); }
    }

    record Projection(List<RetainedExon> retainedExons, List<Junction> junctions, int alteredCdsLength) {
        Projection {
            retainedExons = List.copyOf(retainedExons);
            junctions = List.copyOf(junctions);
        }

        boolean singleExon() { return retainedExons.size() == 1; }
        Integer lastJunctionCdsPosition() {
            return junctions.isEmpty() ? null : junctions.get(junctions.size() - 1).alteredCdsPosition();
        }

        /** Classifies all three bases, including a stop codon spanning up to three exons.
         * This checks only location; translation/reading-frame validation belongs to the caller.
         */
        PtcClassification classifyPtc(int start1, int end1) {
            if (start1 < 1 || (long) end1 - start1 != 2 || end1 > alteredCdsLength)
                throw invalid();
            var carriers = new ArrayList<RetainedExon>();
            long covered = 0;
            for (var exon : retainedExons) {
                int lo = Math.max(start1, exon.alteredCdsStart());
                int hi = Math.min(end1, exon.alteredCdsEnd());
                if (lo <= hi) {
                    carriers.add(exon);
                    covered += (long) hi - lo + 1;
                }
            }
            if (covered != 3) throw invalid();
            return new PtcClassification(carriers);
        }
    }

    /** Projects only [start0,end0) of the reference CDS. The supplied sequence is the
     * already-derived result; its length must agree with this deletion. In particular,
     * deleting an exon's CDS portion does not imply deletion of its retained UTR portion.
     */
    static Projection project(JsonNode model, int start0, int end0, String alteredCds) {
        return project(model, start0, end0, alteredCds, null);
    }

    /** A qualified whole-exon skipping event may explicitly remove that exon's UTR
     * as well. The CDS deletion must equal its complete CDS overlap. Merely covering
     * an exon's coding portion never enables this behavior in the default overload.
     */
    static Projection project(JsonNode model, int start0, int end0, String alteredCds, Integer skippedWholeExonOrdinal) {
        validateInput(model, start0, end0);
        int length = model.path("cdsSequence").asText().length();
        if (alteredCds == null || alteredCds.length() != length - (end0 - start0) || !alteredCds.matches("[ACGT]*")) throw invalid();
        long deletionStart0 = start0, deletionEnd0 = end0;
        if (skippedWholeExonOrdinal != null) {
            var skipped = skippedExon(model, start0, end0, skippedWholeExonOrdinal);
            deletionStart0 = (long) skipped.path("cdsStart").asInt() - 1;
            deletionEnd0 = skipped.path("cdsEnd").asInt();
        }
        long lo = deletionStart0, hi = deletionEnd0, originShift = Math.max(0, -lo);
        return projectGeometry(model, alteredCds.length(), c -> boundary(c, lo, hi) + originShift);
    }

    /** Uses A5's actual combined reference-boundary map while retaining full RNA
     * UTR exons from the independently bound model. CDS-only A5 junctions are not
     * treated as complete RNA/EJC evidence and no RNA sequence/hash is invented.
     */
    static Projection project(JsonNode model, int start0, int end0, CodingTranscriptEdits.Result result,
                              Integer skippedWholeExonOrdinal) {
        validateInput(model, start0, end0);
        if (result == null) throw invalid();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var facts = result.snapshot(mapper);
        if (!new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(model).equals(facts.at("/binding/modelSha256").asText())) throw invalid();
        int appliedSplices = 0;
        for (var edit : facts.path("edits")) if ("SPLICE_PROJECTION".equals(edit.path("origin").asText())
                && "APPLIED".equals(edit.path("status").asText())) {
            if (edit.path("referenceEditStart0").asInt() != start0 || edit.path("referenceEditEnd0").asInt() != end0
                    || !edit.path("insertedSequence").asText().isEmpty()) throw invalid();
            appliedSplices++;
        }
        if (appliedSplices != 1) throw invalid();
        long fivePrimeRemoved = 0, threePrimeRemoved = 0;
        int length = model.path("cdsSequence").asText().length();
        if (skippedWholeExonOrdinal != null) {
            var skipped = skippedExon(model, start0, end0, skippedWholeExonOrdinal);
            fivePrimeRemoved = Math.max(0L, 1L - skipped.path("cdsStart").asInt());
            threePrimeRemoved = Math.max(0L, (long) skipped.path("cdsEnd").asInt() - length);
        }
        long upstreamShift = fivePrimeRemoved, downstreamShift = threePrimeRemoved;
        var projected = projectGeometry(model, result.alteredCds().length(), c -> {
            if (c < 0) return c + upstreamShift;
            int mapped = result.mapReferenceBoundary(Math.toIntExact(c), CodingTranscriptEdits.Bias.BEFORE);
            if (mapped < 0) throw invalid();
            return mapped - (c > length ? downstreamShift : 0);
        }, skippedWholeExonOrdinal);
        for (var coding : result.exons()) {
            boolean covered = false;
            for (var exon : projected.retainedExons()) if (coding.referenceOrdinal() == exon.referenceOrdinal()
                    && coding.alteredCdsStart0() + 1 >= exon.alteredCdsStart()
                    && coding.alteredCdsEnd0() <= exon.alteredCdsEnd()) covered = true;
            if (!covered) throw invalid();
        }
        return projected;
    }

    static final String FULL_RNA_VERSION = "1.0.0";
    static final String FULL_RNA_SCHEMA = "pvs1-splice-full-rna-topology-v1";
    private static final String NOT_GRANTED = "NOT_GRANTED_BY_OPERATOR";

    enum RnaTopologyCompleteness { COMPLETE, UNKNOWN }

    /** A bound mechanical hypothesis, never an RNA assay, phase or source-qualification token.
     * Junctions are zero-based boundaries in the actual altered RNA. Even a COMPLETE
     * declaration still requires independent source qualification before scientific use.
     */
    record RnaTopologyDeclaration(String modelSha256, String referenceRnaSha256, String alteredRnaSha256,
                                  String resultSha256, RnaTopologyCompleteness completeness, List<Integer> junctions0) {
        RnaTopologyDeclaration {
            if (completeness == null || junctions0 == null) throw invalidFull("DECLARATION_INVALID");
            try { junctions0 = List.copyOf(junctions0); }
            catch (NullPointerException invalid) { throw invalidFull("DECLARATION_INVALID"); }
        }
    }

    /** A provenance span, not an actual exon or an EJC. Null referenceStart0 means
     * inserted/replacement bases; null referenceExonOrdinal means unassigned origin.
     */
    record RnaOriginSpan(int alteredStart0, int alteredEnd0, @Nullable Integer referenceStart0,
                         @Nullable Integer referenceExonOrdinal) { }

    /** Actual exon boundaries follow only a COMPLETE declaration. An exon may merge
     * several reference exons, or contain inserted bases with no reference owner.
     */
    record FullRnaExon(int alteredOrdinal, int alteredStart0, int alteredEnd0,
                       List<Integer> referenceOrdinals, boolean hasUnassignedOrigin) {
        FullRnaExon { referenceOrdinals = List.copyOf(referenceOrdinals); }
    }

    /** Java mapping uses zero for a removed/replaced reference base. Snapshots use JSON null. */
    record NativeRnaCodon(String status, int referenceStartRna1, int referenceEndRna1, List<Integer> alteredPositions1) {
        NativeRnaCodon { alteredPositions1 = List.copyOf(alteredPositions1); }
        boolean retainedContiguously() { return "RETAINED_CONTIGUOUS".equals(status); }
        @Nullable Integer alteredStartRna1() { return retainedContiguously() ? alteredPositions1.get(0) : null; }
    }

    /** Triplet coordinates are RNA one-based inclusive. Reference positions use zero
     * internally for an inserted/replacement base; no reference identity is inferred.
     */
    record FullRnaTripletClassification(int startRna1, int endRna1, List<FullRnaExon> exons,
                                        List<Integer> referencePositions1) {
        FullRnaTripletClassification { exons = List.copyOf(exons);referencePositions1 = List.copyOf(referencePositions1); }
        boolean crossesJunction() { return exons.size() > 1; }
        FullRnaExon startExon() { return exons.get(0); }
        FullRnaExon endExon() { return exons.get(exons.size() - 1); }
    }

    static final class FullRnaProjection {
        private final CodingTranscriptEdits.RnaResult result;
        private final String resultSha256;
        private final RnaTopologyCompleteness completeness;
        private final List<Integer> junctions;
        private final List<RnaOriginSpan> origins;
        private final List<FullRnaExon> exons;
        private final NativeRnaCodon nativeStart, nativeStop;

        private FullRnaProjection(CodingTranscriptEdits.RnaResult result, String resultSha256,
                RnaTopologyCompleteness completeness, List<Integer> junctions, List<RnaOriginSpan> origins,
                List<FullRnaExon> exons, NativeRnaCodon nativeStart, NativeRnaCodon nativeStop) {
            this.result = result;this.resultSha256 = resultSha256;this.completeness = completeness;
            this.junctions = List.copyOf(junctions);this.origins = List.copyOf(origins);this.exons = List.copyOf(exons);
            this.nativeStart = nativeStart;this.nativeStop = nativeStop;
        }
        RnaTopologyCompleteness completeness() { return completeness; }
        List<Integer> junctions0() { return junctions; }
        List<RnaOriginSpan> originSpans() { return origins; }
        List<FullRnaExon> exons() { return exons; }
        NativeRnaCodon nativeStart() { return nativeStart; }
        NativeRnaCodon nativeStop() { return nativeStop; }
        String sourceQualificationStatus() { return NOT_GRANTED; }
        @Nullable Boolean singleExon() { return completeness == RnaTopologyCompleteness.COMPLETE ? exons.size() == 1 : null; }
        @Nullable Integer lastJunctionRnaPosition0() {
            return completeness == RnaTopologyCompleteness.COMPLETE && !junctions.isEmpty() ? junctions.get(junctions.size() - 1) : null;
        }
        FullRnaTripletClassification classifyTriplet(int startRna1, int endRna1) {
            if (completeness != RnaTopologyCompleteness.COMPLETE) throw invalidFull("UNRESOLVED");
            if (startRna1 < 1 || (long) endRna1 - startRna1 != 2 || endRna1 > result.alteredRna().length())
                throw invalidFull("TRIPLET_INVALID");
            var carriers = new ArrayList<FullRnaExon>();int covered = 0;
            for (var exon : exons) {
                int lo = Math.max(startRna1, exon.alteredStart0() + 1), hi = Math.min(endRna1, exon.alteredEnd0());
                if (lo <= hi) { carriers.add(exon);covered += hi - lo + 1; }
            }
            if (covered != 3) throw invalidFull("TRIPLET_INVALID");
            return new FullRnaTripletClassification(startRna1, endRna1, carriers,
                    List.of(result.alteredToReferenceRnaBase(startRna1), result.alteredToReferenceRnaBase(startRna1 + 1),
                            result.alteredToReferenceRnaBase(endRna1)));
        }
        ObjectNode snapshot() { return snapshot(new ObjectMapper()); }
        ObjectNode snapshot(ObjectMapper mapper) {
            var out = mapper.createObjectNode().put("schemaVersion", FULL_RNA_SCHEMA).put("methodVersion", FULL_RNA_VERSION)
                    .put("coordinateSystem", "ALTERED_RNA_0_BASED_HALF_OPEN_BOUNDARIES")
                    .put("alteredRnaLength", result.alteredRna().length()).put("topologyCompleteness", completeness.name())
                    .put("topologyBasis", "BOUND_MECHANICAL_DECLARATION")
                    .put("sourceQualificationStatus", NOT_GRANTED).put("scientificQualificationGranted", false)
                    .put("phaseQualificationGranted", false).put("ejcQualificationGranted", false);
            var material = result.reference().material();
            out.putObject("binding").put("modelSha256", material.modelSha256()).put("contextSha256", material.contextSha256())
                    .put("referenceRnaSha256", material.referenceRnaSha256()).put("alteredRnaSha256", result.alteredRnaSha256())
                    .put("resultSha256", resultSha256);
            out.set("junctions0", mapper.valueToTree(junctions));out.set("originSpans", mapper.valueToTree(origins));
            out.set("exons", mapper.valueToTree(exons));
            if (completeness == RnaTopologyCompleteness.COMPLETE) out.put("singleExon", exons.size() == 1);else out.putNull("singleExon");
            Integer last = lastJunctionRnaPosition0();if (last == null) out.putNull("lastJunctionRnaPosition0");else out.put("lastJunctionRnaPosition0", last);
            out.set("nativeStart", codonSnapshot(mapper, nativeStart));out.set("nativeStop", codonSnapshot(mapper, nativeStop));
            return out;
        }
    }

    /** Full RNA sequence and provenance come exclusively from A5 and the opaque
     * reference supply. A declaration supplies boundaries, not scientific eligibility.
     * No owner change, deletion cut, or insertion endpoint is promoted to a junction.
     */
    static FullRnaProjection projectFullRna(CodingTranscriptEdits.RnaResult result, @Nullable RnaTopologyDeclaration declaration) {
        if (result == null) throw invalidFull("RESULT_REQUIRED");
        var mapper = new ObjectMapper();var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var facts = result.snapshot(mapper);String resultSha = adapter.canonicalSha256(facts);
        var material = result.reference().material();
        var completeness = declaration == null ? RnaTopologyCompleteness.UNKNOWN : declaration.completeness();
        List<Integer> junctions = declaration == null ? List.of() : declaration.junctions0();
        if (declaration != null && (!material.modelSha256().equals(declaration.modelSha256())
                || !material.referenceRnaSha256().equals(declaration.referenceRnaSha256())
                || !result.alteredRnaSha256().equals(declaration.alteredRnaSha256()) || !resultSha.equals(declaration.resultSha256())))
            throw invalidFull("DECLARATION_BINDING_MISMATCH");
        if (junctions.size() > 1000) throw invalidFull("DECLARATION_INVALID");
        int previous = 0;
        for (int junction : junctions) {
            if (junction <= previous || junction >= result.alteredRna().length()) throw invalidFull("DECLARATION_INVALID");
            previous = junction;
        }
        var origins = new ArrayList<RnaOriginSpan>();
        for (var row : facts.path("originSpans")) origins.add(new RnaOriginSpan(row.path("alteredStart0").asInt(), row.path("alteredEnd0").asInt(),
                row.path("referenceStart0").isNull() ? null : row.path("referenceStart0").asInt(),
                row.path("referenceExonOrdinal").isNull() ? null : row.path("referenceExonOrdinal").asInt()));
        var exons = new ArrayList<FullRnaExon>();
        if (completeness == RnaTopologyCompleteness.COMPLETE && !result.alteredRna().isEmpty()) {
            int start = 0;
            for (int i = 0; i <= junctions.size(); i++) {
                int end = i < junctions.size() ? junctions.get(i) : result.alteredRna().length();
                var referenceOrdinals = new LinkedHashSet<Integer>();boolean unassigned = false;
                for (var origin : origins) if (origin.alteredEnd0() > start && origin.alteredStart0() < end) {
                    Integer ordinal = origin.referenceExonOrdinal();
                    if (ordinal == null) unassigned = true;else referenceOrdinals.add(ordinal);
                }
                exons.add(new FullRnaExon(i + 1, start, end, new ArrayList<>(referenceOrdinals), unassigned));start = end;
            }
        }
        return new FullRnaProjection(result, resultSha, completeness, junctions, origins, exons,
                nativeCodon(result, material.cdsStartRna1()), nativeCodon(result, material.cdsEndRna1() - 2));
    }

    private static NativeRnaCodon nativeCodon(CodingTranscriptEdits.RnaResult result, int startRna1) {
        var positions = new ArrayList<Integer>();int retained = 0;
        for (int p = startRna1; p <= startRna1 + 2; p++) {
            int altered = result.referenceRnaToAlteredBase(p);positions.add(altered);if (altered > 0) retained++;
        }
        boolean contiguous = retained == 3 && positions.get(1) == positions.get(0) + 1 && positions.get(2) == positions.get(1) + 1;
        String status = contiguous ? "RETAINED_CONTIGUOUS" : retained == 3 ? "REPARTITIONED"
                : retained > 0 ? "PARTIALLY_RETAINED" : "REMOVED_OR_REPLACED";
        return new NativeRnaCodon(status, startRna1, startRna1 + 2, positions);
    }
    private static ObjectNode codonSnapshot(ObjectMapper mapper, NativeRnaCodon codon) {
        var out = mapper.createObjectNode().put("status", codon.status()).put("referenceStartRna1", codon.referenceStartRna1())
                .put("referenceEndRna1", codon.referenceEndRna1());
        var positions = out.putArray("alteredPositions1");
        for (int position : codon.alteredPositions1()) if (position == 0) positions.addNull();else positions.add(position);
        Integer start = codon.alteredStartRna1();if (start == null) out.putNull("alteredStartRna1");else out.put("alteredStartRna1", start);
        return out;
    }
    private static IllegalArgumentException invalidFull(String suffix) {
        return new IllegalArgumentException("PVS1_SPLICE_FULL_RNA_TOPOLOGY_" + suffix);
    }
    private static void validateInput(JsonNode model, int start0, int end0) {
        if (model == null || !model.isObject() || !model.path("cdsSequence").isTextual()
                || !integer(model.path("cdsLength"))) throw invalid();
        String referenceCds = model.path("cdsSequence").asText();
        int length = referenceCds.length();
        if (!referenceCds.matches("[ACGT]+") || model.path("cdsLength").asInt() != length
                || start0 < 0 || end0 <= start0 || end0 > length)
            throw invalid();
        var sourceExons = model.path("exons");
        if (!sourceExons.isArray() || sourceExons.isEmpty() || sourceExons.size() > 1000) throw invalid();
    }

    private static JsonNode skippedExon(JsonNode model, int start0, int end0, int ordinal) {
        if (ordinal < 1 || ordinal > model.path("exons").size()) throw invalid();
        var skipped = model.path("exons").path(ordinal - 1);
        if (!integer(skipped.path("cdsStart")) || !integer(skipped.path("cdsEnd"))) throw invalid();
        if (start0 != Math.max(skipped.path("cdsStart").asInt(), 1) - 1
                || end0 != Math.min(skipped.path("cdsEnd").asInt(), model.path("cdsSequence").asText().length())) throw invalid();
        return skipped;
    }

    private static Projection projectGeometry(JsonNode model, int alteredLength, LongUnaryOperator mapping) {
        return projectGeometry(model, alteredLength, mapping, null);
    }

    private static Projection projectGeometry(JsonNode model, int alteredLength, LongUnaryOperator mapping, Integer skippedOrdinal) {
        var sourceExons = model.path("exons");
        var remaining = new ArrayList<RetainedExon>();
        long expectedStart = 0;
        for (int i = 0; i < sourceExons.size(); i++) {
            var exon = sourceExons.path(i);
            if (!integer(exon.path("ordinal")) || !integer(exon.path("cdsStart")) || !integer(exon.path("cdsEnd")))
                throw invalid();
            int lo = exon.path("cdsStart").asInt(), hi = exon.path("cdsEnd").asInt();
            if (exon.path("ordinal").asInt() != i + 1 || hi < lo || (long) hi - lo + 1 > Integer.MAX_VALUE
                    || (i == 0 ? lo > 1 : lo != expectedStart)) throw invalid();
            expectedStart = (long) hi + 1;
            if (skippedOrdinal != null && skippedOrdinal == i + 1) continue;
            long projectedStart0 = mapping.applyAsLong((long) lo - 1);
            long projectedEnd0 = mapping.applyAsLong(hi);
            if (projectedEnd0 == projectedStart0) continue;
            if (projectedEnd0 < projectedStart0) throw invalid();
            remaining.add(new RetainedExon(i + 1, remaining.size() + 1,
                    Math.toIntExact(projectedStart0 + 1), Math.toIntExact(projectedEnd0)));
        }
        if (expectedStart <= model.path("cdsSequence").asText().length() || remaining.isEmpty()) throw invalid();
        var junctions = new ArrayList<Junction>();
        for (int i = 1; i < remaining.size(); i++) {
            var left = remaining.get(i - 1);
            var right = remaining.get(i);
            if ((long) left.alteredCdsEnd() + 1 != right.alteredCdsStart()) throw invalid();
            junctions.add(new Junction(left.referenceOrdinal(), right.referenceOrdinal(), left.alteredOrdinal(),
                    right.alteredOrdinal(), left.alteredCdsEnd()));
        }
        return new Projection(remaining, junctions, alteredLength);
    }

    private static long boundary(long coordinate0, long start0, long end0) {
        if (coordinate0 <= start0) return coordinate0;
        if (coordinate0 >= end0) return coordinate0 - (end0 - start0);
        return start0;
    }

    private static boolean integer(JsonNode node) { return node.isIntegralNumber() && node.canConvertToInt(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("PVS1_SPLICE_TOPOLOGY_INVALID"); }
}
