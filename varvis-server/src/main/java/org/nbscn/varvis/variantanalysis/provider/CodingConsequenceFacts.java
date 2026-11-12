package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Mechanical consequences of an already-composed, reference-bound CDS edit result.
 * This does not compose events, establish phase, infer RNA/EJC completeness, choose initiation,
 * establish functional loss, or select an evidence rule. Exact base provenance replaces alignment.
 */
public final class CodingConsequenceFacts {
    public static final String VERSION = "1.0.1";
    public static final String SCHEMA = "coding-consequence-facts-v1";
    public static final String RNA_VERSION = "1.0.1";
    public static final String RNA_SCHEMA = "full-rna-consequence-facts-v1";
    public static final String MECHANICAL_EFFECT_SCHEMA = "coding-mechanical-effect-identity-v1";
    private CodingConsequenceFacts() { }

    public record ReferenceResidueRange(int start, int end) { }

    public static final class Facts {
        private final ObjectNode value;
        private final String sha256;
        private Facts(ObjectMapper mapper, ObjectNode value) {
            this.value = value.deepCopy(); sha256 = new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(this.value);
        }
        public ObjectNode snapshot() { return value.deepCopy(); }
        public String canonicalSha256() { return sha256; }
        /** Source bindings remain in canonicalSha256; this identity binds only the exact mechanical effect. */
        public ObjectNode mechanicalEffectIdentity() { return ((ObjectNode) value.path("mechanicalEffectIdentity")).deepCopy(); }
        public String mechanicalEffectSha256() { return value.path("mechanicalEffectSha256").asText(); }
        public boolean nativeStartRetained() { return value.path("nativeStartRetained").asBoolean(); }
        public String firstStopKind() { return value.at("/termination/kind").asText(); }
        @org.springframework.lang.Nullable public Boolean proteinSequenceChanged() {
            var field = value.path("proteinSequenceChanged"); return field.isBoolean() ? field.asBoolean() : null;
        }
        @org.springframework.lang.Nullable public Integer netDeletedProteinResidues() {
            var field = value.path("netDeletedProteinResidues"); return field.isIntegralNumber() ? field.intValue() : null;
        }
        @org.springframework.lang.Nullable public Integer inFrameNetDeletedProteinResidues() {
            var field = value.path("inFrameNetDeletedProteinResidues"); return field.isIntegralNumber() ? field.intValue() : null;
        }
        public List<ReferenceResidueRange> wholeDeletedReferenceResidueRanges() { return ranges("wholeDeletedReferenceResidueRanges"); }
        public List<ReferenceResidueRange> touchedReferenceResidueRanges() { return ranges("touchedReferenceResidueRanges"); }
        private List<ReferenceResidueRange> ranges(String field) {
            var ranges = new ArrayList<ReferenceResidueRange>();
            for (var row : value.path(field)) ranges.add(new ReferenceResidueRange(row.path("start").asInt(), row.path("end").asInt()));
            return List.copyOf(ranges);
        }
    }

    public static final class RnaFacts {
        private final ObjectNode value;
        private final String sha256;
        private RnaFacts(ObjectMapper mapper, ObjectNode value) {
            this.value = value.deepCopy();sha256 = new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value);
        }
        public ObjectNode snapshot() { return value.deepCopy(); }
        public String canonicalSha256() { return sha256; }
        public String mechanicalEffectSha256() { return value.path("mechanicalEffectSha256").asText(); }
        public boolean nativeStartRetained() { return value.at("/nativeStart/retainedContiguously").asBoolean(); }
        public String firstStopKind() { return value.at("/firstStop/kind").asText(); }
    }

    /** Scans only the actually verified complete RNA, from the retained native ATG.
     * No downstream initiation, tissue/phase, functional qualification or NMD rule is inferred.
     */
    public static RnaFacts deriveRna(ObjectMapper mapper, JsonNode context, CodingTranscriptEdits.RnaResult result) {
        if (mapper == null || context == null || result == null) throw rejected("RNA_INPUT_REQUIRED");
        var material = result.reference().material();var hashes = new RuntimeEvidenceV2Adapter(mapper);
        require(hashes.canonicalSha256(context).equals(material.contextSha256()), "RNA_CONTEXT_BINDING_MISMATCH");
        var edits = result.snapshot(mapper);String altered = result.alteredRna();
        var out = mapper.createObjectNode().put("schemaVersion", RNA_SCHEMA).put("methodVersion", RNA_VERSION)
                .put("sequenceScope", "FULL_SPLICED_REFERENCE_RNA").put("coordinateSystem", "TRANSCRIPT_RNA_1_BASED_INCLUSIVE")
                .put("scientificQualificationGranted", false).put("phaseQualificationGranted", false)
                .put("functionalQualificationGranted", false).put("topologyQualificationGranted", false)
                .put("referenceRnaLength", material.referenceRnaSequence().length()).put("alteredRnaLength", altered.length())
                .put("alteredRnaSequence", altered).put("referenceProteinLength", material.model().path("proteinLength").asInt());
        out.putObject("binding").put("modelSha256", material.modelSha256()).put("contextSha256", material.contextSha256())
                .put("referenceRnaSha256", material.referenceRnaSha256()).put("alteredRnaSha256", result.alteredRnaSha256())
                .put("editFactsSha256", hashes.canonicalSha256(edits));
        out.set("editFacts", edits);
        var identity = mapper.createObjectNode().put("schemaVersion", "full-rna-mechanical-effect-identity-v1")
                .put("methodVersion", RNA_VERSION).put("editMethodVersion", CodingTranscriptEdits.RNA_VERSION);
        identity.putObject("binding").put("modelSha256", material.modelSha256()).put("contextSha256", material.contextSha256())
                .put("referenceRnaSha256", material.referenceRnaSha256()).put("alteredRnaSha256", result.alteredRnaSha256());
        var operations = identity.putArray("appliedOperations");
        for (var edit : result.appliedEdits()) operations.addObject().put("origin", edit.origin().name())
                .put("start0", edit.referenceEditStart0()).put("end0", edit.referenceEditEnd0())
                .put("expectedReference", edit.expectedReference()).put("insertedSequence", edit.insertedSequence())
                .put("alleleGroupSha256", edit.alleleGroupSha256()).put("insertionExonOrdinal", edit.insertionExonOrdinal());
        out.set("mechanicalEffectIdentity", identity);out.put("mechanicalEffectSha256", hashes.canonicalSha256(identity));
        var start = describeNativeRnaCodon(mapper, result, material.cdsStartRna1());
        var nativeStop = describeNativeRnaCodon(mapper, result, material.cdsEndRna1() - 2);
        out.set("nativeStart", start);out.set("nativeStop", nativeStop);
        out.put("declaredRnaEndInterpretation", "END_OF_EDITED_REFERENCE_RNA_NOT_ASSUMED_BIOLOGICAL_TERMINUS");
        if (!start.path("retainedContiguously").asBoolean()) {
            out.put("translationStatus", "REFERENCE_START_NOT_RETAINED_NO_ALTERNATE_INITIATION_SELECTED")
                    .putNull("alteredProteinThroughFirstTermination").putNull("alteredProteinLength")
                    .putNull("netProteinLengthChange").putNull("firstStop");
            nativeStop.putNull("inReadingFrameFromRetainedNativeStart");
            out.putObject("codingEffects").put("status", "INITIATION_UNRESOLVED")
                    .put("scientificQualificationGranted", false).put("localInFrameLengthChangeResolved", false);
            return new RnaFacts(mapper, out);
        }
        int origin = start.path("alteredStartRna1").asInt() - 1;
        require(origin >= 0 && origin + 3 <= altered.length() && altered.substring(origin, origin + 3).equals("ATG"), "RNA_NATIVE_START_SEQUENCE_MISMATCH");
        int nativeStopStart = nativeStop.path("alteredStartRna1").asInt(0);
        if (nativeStopStart > 0) nativeStop.put("inReadingFrameFromRetainedNativeStart", (nativeStopStart - 1 - origin) % 3 == 0);
        else nativeStop.putNull("inReadingFrameFromRetainedNativeStart");
        var protein = new StringBuilder();int stopStart0 = -1;
        for (int p = origin; p + 2 < altered.length(); p += 3) {
            char amino = TruncatingSequence.translateCodon(altered.substring(p, p + 3));protein.append(amino);
            if (amino == '*') { stopStart0 = p;break; }
        }
        out.put("translationStatus", "DECLARED_FRAME_FROM_RETAINED_NATIVE_START")
                .put("alteredStartRna1", origin + 1).put("alteredProteinThroughFirstTermination", protein.toString());
        out.set("codingEffects", describeRnaCodingEffects(mapper, result, origin, stopStart0, protein.toString()));
        if (stopStart0 < 0) {
            out.putNull("alteredProteinLength").putNull("netProteinLengthChange")
                    .put("trailingIncompleteCodonBases", (altered.length() - origin) % 3)
                    .putObject("firstStop").put("kind", "NO_STOP_WITHIN_DECLARED_ALTERED_RNA");
            return new RnaFacts(mapper, out);
        }
        int changedLength = (stopStart0 - origin) / 3;
        int delta = changedLength - material.model().path("proteinLength").asInt();
        out.put("alteredProteinLength", changedLength).put("netProteinLengthChange", delta)
                .put("proteinLengthOutcome", delta < 0 ? "SHORTER" : delta > 0 ? "LONGER" : "SAME_LENGTH");
        var stop = out.putObject("firstStop").put("alteredStartRna1", stopStart0 + 1).put("alteredEndRna1", stopStart0 + 3)
                .put("alteredStartFromNativeCodingOrigin1", stopStart0 - origin + 1)
                .put("codon", altered.substring(stopStart0, stopStart0 + 3));
        int[] refs = new int[3];int known = 0;
        var origins = stop.putArray("bases");
        for (int i = 0; i < 3; i++) {
            int ref = result.alteredToReferenceRnaBase(stopStart0 + i + 1);refs[i] = ref;if (ref > 0) known++;
            var base = origins.addObject().put("alteredRnaPosition1", stopStart0 + i + 1);
            if (ref > 0) base.put("referenceRnaPosition1", ref);else base.putNull("referenceRnaPosition1");
            if (ref >= material.cdsStartRna1() && ref <= material.cdsEndRna1())
                base.put("referenceCdsPosition1", ref - material.cdsStartRna1() + 1).put("origin", "REFERENCE_CDS");
            else { base.putNull("referenceCdsPosition1").put("origin", ref == 0 ? "INSERTED_OR_REPLACEMENT" : "REFERENCE_UTR"); }
        }
        points(stop, "referenceRnaPositions1", refs);
        stop.put("baseOrigin", known == 3 ? "REFERENCE_BASES" : known == 0 ? "INSERTED_OR_REPLACEMENT_BASES" : "MIXED");
        String kind = nativeStopStart == stopStart0 + 1 && nativeStop.path("inReadingFrameFromRetainedNativeStart").asBoolean()
                ? "NATIVE_REFERENCE_STOP" : nativeStopStart > stopStart0 + 1 ? "NON_NATIVE_STOP_BEFORE_RETAINED_REFERENCE_STOP"
                : nativeStopStart > 0 ? "NON_NATIVE_STOP_AFTER_RETAINED_REFERENCE_STOP" : "NON_NATIVE_STOP_REFERENCE_STOP_NOT_RETAINED";
        stop.put("kind", kind);
        return new RnaFacts(mapper, out);
    }

    /** Native CDS effects use the actual retained RNA start and base maps, never total-RNA delta modulo three. */
    private static ObjectNode describeRnaCodingEffects(ObjectMapper mapper, CodingTranscriptEdits.RnaResult result,
            int alteredOrigin0, int firstStopRna0, String actualProtein) {
        var material = result.reference().material();String referenceCds = material.model().path("cdsSequence").asText();
        String referenceProtein = decode(referenceCds);int offset = material.cdsStartRna1() - 1;
        int[] inverse = new int[referenceCds.length() + 1];byte[] loss = new byte[referenceCds.length()];
        var projected = new ArrayList<JsonNode>();
        for (var edit : result.appliedEdits()) {
            int lo = Math.max(offset, edit.referenceEditStart0()), hi = Math.min(material.cdsEndRna1(), edit.referenceEditEnd0());
            if (lo < hi) {
                Arrays.fill(loss, lo - offset, hi - offset, edit.insertedSequence().isEmpty() ? (byte) 1 : (byte) 2);
                projected.add(mapper.createObjectNode().put("referenceEditStart0", lo - offset).put("referenceEditEnd0", hi - offset));
            } else if (edit.referenceEditStart0() == edit.referenceEditEnd0()
                    && edit.referenceEditStart0() > offset && edit.referenceEditStart0() < material.cdsEndRna1())
                projected.add(mapper.createObjectNode().put("referenceEditStart0", edit.referenceEditStart0() - offset)
                        .put("referenceEditEnd0", edit.referenceEditEnd0() - offset));
        }
        for (int p = 1; p <= referenceCds.length(); p++) {
            int mapped = result.referenceRnaToAlteredBase(offset + p);
            if (mapped > 0) { require(mapped > alteredOrigin0, "RNA_CODING_BASE_PRECEDES_NATIVE_START"); inverse[p] = mapped - alteredOrigin0; }
            else require(loss[p - 1] != 0, "RNA_REFERENCE_CODING_LOSS_UNEXPLAINED");
        }
        int firstStop = firstStopRna0 < 0 ? -1 : (firstStopRna0 - alteredOrigin0) / 3;
        var out = mapper.createObjectNode().put("status", firstStop < 0 ? "TERMINATION_UNRESOLVED" : "ACTUAL_CODING_EFFECTS_RESOLVED")
                .put("metricMeaning", "ACTUAL_NATIVE_START_CODING_BASE_PROVENANCE_NOT_TOTAL_RNA_LENGTH")
                .put("referenceCdsLength", referenceCds.length()).put("referenceProteinLength", referenceProtein.length() - 1)
                .put("referenceProteinThroughTermination", referenceProtein).put("scientificQualificationGranted", false);
        int boundary = result.mapReferenceRnaBoundary(material.cdsEndRna1(), CodingTranscriptEdits.Bias.BEFORE);
        if (boundary >= alteredOrigin0) out.put("referenceCdsBoundaryAfterEditRna0", boundary)
                .put("editedReferenceCdsIntervalLength", boundary - alteredOrigin0)
                .put("referenceCdsIntervalLengthChange", boundary - alteredOrigin0 - referenceCds.length());
        else out.putNull("referenceCdsBoundaryAfterEditRna0").putNull("editedReferenceCdsIntervalLength").putNull("referenceCdsIntervalLengthChange");
        int stopA = inverse[referenceCds.length() - 2], stopB = inverse[referenceCds.length() - 1], stopC = inverse[referenceCds.length()];
        boolean retainedStop = stopA > 0 && stopB == stopA + 1 && stopC == stopB + 1;
        if (retainedStop) out.put("frameOffsetAtRetainedNativeStop", (stopA - 1) % 3);else out.putNull("frameOffsetAtRetainedNativeStop");
        out.put("localInFrameLengthChangeResolved", retainedStop && (stopA - 1) % 3 == 0 && firstStopRna0 >= 0 && firstStop * 3 + 1 == stopA);
        if (firstStop >= 0) out.put("alteredProteinLength", firstStop).put("netProteinLengthChange", firstStop - referenceProtein.length() + 1)
                .put("proteinSequenceChanged", !referenceProtein.equals(actualProtein));
        else out.putNull("alteredProteinLength").putNull("netProteinLengthChange").putNull("proteinSequenceChanged");
        referenceEffects(referenceProtein, inverse, loss, firstStop, out);
        describeRanges(referenceProtein.length() - 1, projected, out);
        // The existing touched ranges already include re-encoded codons between
        // separated edits. Also expose translation coverage and altered residues.
        boolean[] referenceEffects = new boolean[referenceProtein.length() - 1];
        for (var range : out.path("touchedReferenceResidueRanges"))
            for (int i=range.path("start").asInt();i<=range.path("end").asInt();i++) referenceEffects[i-1]=true;
        for (var row : out.path("referenceResidueEffects"))
            if (!"EXACT_RETAINED_REFERENCE_CODON".equals(row.path("effect").asText())
                    || !"BEFORE_FIRST_STOP".equals(row.path("translationStatus").asText()))
                for(int i=row.path("startReferenceResidue").asInt();i<=row.path("endReferenceResidue").asInt();i++) referenceEffects[i-1]=true;
        rangeRows(out,"proteinEffectReferenceResidueRanges",referenceEffects);
        int residues=firstStop>=0?firstStop:actualProtein.length();boolean[] alteredEffects=new boolean[residues];
        for(int i=0;i<residues;i++) {
            int a=result.alteredToReferenceRnaBase(alteredOrigin0+i*3+1)-offset;
            int b=result.alteredToReferenceRnaBase(alteredOrigin0+i*3+2)-offset;
            int c=result.alteredToReferenceRnaBase(alteredOrigin0+i*3+3)-offset;
            if(a<1 || c>referenceCds.length() || b!=a+1 || c!=b+1 || (a-1)%3!=0) alteredEffects[i]=true;
        }
        for(var edit:result.appliedEdits()) {
            int a=result.mapReferenceRnaBoundary(edit.referenceEditStart0(),CodingTranscriptEdits.Bias.BEFORE);
            int b=result.mapReferenceRnaBoundary(edit.referenceEditEnd0(),CodingTranscriptEdits.Bias.AFTER);
            if(a<0 || b<0)continue;
            int first=Math.max(1,Math.floorDiv(a-alteredOrigin0-1,3)+1);
            int last=Math.min(residues,Math.floorDiv(b-alteredOrigin0,3)+1);
            for(int i=first;i<=last;i++)alteredEffects[i-1]=true;
        }
        rangeRows(out,"proteinEffectAlteredResidueRanges",alteredEffects);
        return out;
    }

    private static ObjectNode describeNativeRnaCodon(ObjectMapper mapper, CodingTranscriptEdits.RnaResult result, int referenceStart1) {
        int[] positions = new int[3];int retained = 0;
        for (int i = 0; i < 3; i++) { positions[i] = result.referenceRnaToAlteredBase(referenceStart1 + i);if (positions[i] > 0) retained++; }
        boolean contiguous = retained == 3 && positions[1] == positions[0] + 1 && positions[2] == positions[1] + 1;
        var row = mapper.createObjectNode().put("referenceStartRna1", referenceStart1).put("referenceEndRna1", referenceStart1 + 2)
                .put("retainedContiguously", contiguous).put("status", contiguous ? "RETAINED_CONTIGUOUS"
                        : retained == 3 ? "BASES_SURVIVE_NONCONTIGUOUS" : retained == 0 ? "REMOVED_OR_REPLACED" : "PARTIALLY_RETAINED");
        points(row, "alteredPositions1", positions);
        if (contiguous) row.put("alteredStartRna1", positions[0]);else row.putNull("alteredStartRna1");
        return row;
    }

    public static Facts derive(ObjectMapper mapper, CodingTranscriptEdits.Reference reference, CodingTranscriptEdits.Result result) {
        if (mapper == null || reference == null || result == null) throw rejected("INPUT_REQUIRED");
        var editFacts = result.snapshot(mapper);
        var binding = editFacts.path("binding");
        require(reference.modelSha256().equals(binding.path("modelSha256").asText()), "MODEL_BINDING_MISMATCH");
        require(reference.cdsSha256().equals(binding.path("referenceCdsSha256").asText()), "CDS_BINDING_MISMATCH");
        require(reference.contextSha256().equals(binding.path("contextSha256").asText()), "CONTEXT_BINDING_MISMATCH");
        String original = reference.cds(), altered = result.alteredCds();
        require(sha(original).equals(reference.cdsSha256()) && sha(altered).equals(result.alteredCdsSha256())
                && result.alteredCdsSha256().equals(editFacts.path("alteredCdsSha256").asText())
                && editFacts.path("referenceCdsLength").asInt(-1) == original.length()
                && editFacts.path("alteredCdsLength").asInt(-1) == altered.length(), "SEQUENCE_BINDING_MISMATCH");
        require(original.matches("[ACGT]+") && original.length() >= 6 && original.length() % 3 == 0
                && altered.matches("[ACGT]*"), "SEQUENCE_INVALID");
        String referenceProtein = decode(original), allAlteredCodons = decode(altered);
        require(referenceProtein.indexOf('*') == referenceProtein.length() - 1, "REFERENCE_TERMINATION_INVALID");
        int firstStop = allAlteredCodons.indexOf('*');
        String protein = firstStop < 0 ? allAlteredCodons : allAlteredCodons.substring(0, firstStop + 1);
        int[] inverse = inverse(reference, result);
        var applied = appliedEdits(editFacts);
        byte[] referenceLoss = referenceLoss(original.length(), applied);
        byte[] insertedOrigin = insertedOrigins(altered.length(), result, applied);
        var out = mapper.createObjectNode().put("schemaVersion", SCHEMA).put("methodVersion", VERSION)
                .put("sequenceScope", "CDS_ONLY").put("topologyScope", "CODING_EXONS_ONLY")
                .put("translationAssumption", "REFERENCE_READING_FRAME_FROM_FIRST_DECLARED_CDS_BASE")
                .put("downstreamInitiationChosen", false).put("fullRnaOrEjcProofGranted", false)
                .put("scientificQualificationGranted", false).put("functionalQualificationGranted", false)
                .put("referenceProteinThroughTermination", referenceProtein).put("alteredProteinThroughTermination", protein)
                .put("referenceProteinSha256", sha(referenceProtein)).put("alteredProteinThroughTerminationSha256", sha(protein))
                .put("referenceProteinLength", referenceProtein.length() - 1)
                .put("completeTranslatedProductsCompared", firstStop >= 0)
                .put("completeAlteredCodons", altered.length() / 3).put("trailingIncompleteCodonBases", altered.length() % 3)
                .put("netCdsLengthChange", result.delta()).put("finalFrameOffset", Math.floorMod(result.delta(), 3));
        var pins = out.putObject("binding");
        pins.put("modelSha256", reference.modelSha256()).put("contextSha256", reference.contextSha256())
                .put("referenceCdsSha256", reference.cdsSha256()).put("alteredCdsSha256", result.alteredCdsSha256())
                .put("editFactsSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(editFacts))
                .put("editMethodVersion", CodingTranscriptEdits.VERSION);
        out.set("editFacts", editFacts.deepCopy());
        var mechanicalIdentity = mechanicalIdentity(mapper, reference, result, applied);
        out.set("mechanicalEffectIdentity", mechanicalIdentity);
        out.put("mechanicalEffectSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(mechanicalIdentity));
        describeCounts(editFacts, out);
        describeFrameChanges(applied, out);
        boolean startRetained = inverse[1] == 1 && inverse[2] == 2 && inverse[3] == 3;
        out.put("referenceStartRetained", startRetained).put("nativeStartRetained", startRetained);
        var start = out.putObject("startCodon").put("referenceCodon", original.substring(0, 3));
        points(start, "referencePositions", new int[]{1, 2, 3}); points(start, "alteredPositions", new int[]{inverse[1], inverse[2], inverse[3]});
        if (altered.length() >= 3) start.put("alteredFirstCodon", altered.substring(0, 3)); else start.putNull("alteredFirstCodon");
        start.put("status", startRetained ? "REFERENCE_START_RETAINED_AT_DECLARED_ORIGIN"
                : inverse[1] > 0 && inverse[2] == inverse[1] + 1 && inverse[3] == inverse[2] + 1 ? "REFERENCE_START_RETAINED_AT_DIFFERENT_OFFSET"
                : inverse[1] > 0 && inverse[2] > 0 && inverse[3] > 0 ? "REFERENCE_START_BASES_RETAINED_NONCONTIGUOUS"
                : inverse[1] > 0 || inverse[2] > 0 || inverse[3] > 0 ? "REFERENCE_START_PARTIALLY_RETAINED" : "REFERENCE_START_REMOVED_OR_REPLACED");
        var termination = termination(mapper, reference, result, inverse, firstStop);
        out.set("termination", termination);
        out.set("nativeStop", nativeStop(mapper, reference, inverse));
        if (firstStop >= 0) {
            out.put("alteredProteinLength", firstStop).put("netTranslatedAminoAcidChange", firstStop - referenceProtein.length() + 1)
                    .put("proteinSequenceChanged", !protein.equals(referenceProtein));
        } else {
            out.putNull("alteredProteinLength").putNull("netTranslatedAminoAcidChange");
            String referenceAminoAcids = referenceProtein.substring(0, referenceProtein.length() - 1);
            if (allAlteredCodons.length() > referenceAminoAcids.length() || !referenceAminoAcids.startsWith(allAlteredCodons))
                out.put("proteinSequenceChanged", true);
            else out.putNull("proteinSequenceChanged");
        }
        if (firstStop >= 0 && startRetained) out.put("netDeletedProteinResidues", Math.max(0, referenceProtein.length() - 1 - firstStop));
        else out.putNull("netDeletedProteinResidues");
        boolean localInFrame = startRetained && firstStop >= 0 && "NATIVE_STOP".equals(termination.path("kind").asText()) && result.delta() % 3 == 0;
        out.put("localInFrameLengthChangeResolved", localInFrame);
        if (localInFrame) out.put("inFrameNetDeletedProteinResidues", Math.max(0, referenceProtein.length() - 1 - firstStop));
        else out.putNull("inFrameNetDeletedProteinResidues");
        referenceEffects(referenceProtein, inverse, referenceLoss, firstStop, out);
        describeRanges(referenceProtein.length() - 1, applied, out);
        alteredEffects(altered, allAlteredCodons, result, insertedOrigin, firstStop, out);
        return new Facts(mapper, out);
    }

    /** An explicit, closed projection avoids review -> source-fact -> edit-facts -> review hash cycles.
     * It grants no source authority: the full facts SHA separately retains every evidence binding/alias.
     */
    private static ObjectNode mechanicalIdentity(ObjectMapper mapper, CodingTranscriptEdits.Reference reference,
            CodingTranscriptEdits.Result result, List<JsonNode> applied) {
        var identity = mapper.createObjectNode().put("schemaVersion", MECHANICAL_EFFECT_SCHEMA)
                .put("methodVersion", VERSION).put("editMethodVersion", CodingTranscriptEdits.VERSION);
        identity.putObject("binding").put("modelSha256", reference.modelSha256()).put("contextSha256", reference.contextSha256())
                .put("referenceCdsSha256", reference.cdsSha256()).put("alteredCdsSha256", result.alteredCdsSha256());
        var rows = identity.putArray("appliedOperations");
        var operations = new ArrayList<ObjectNode>();
        for (var edit : applied) {
            var row = mapper.createObjectNode().put("origin", edit.path("origin").asText())
                    .put("start0", edit.path("referenceEditStart0").asInt()).put("end0", edit.path("referenceEditEnd0").asInt())
                    .put("expectedReference", edit.path("expectedReference").asText()).put("insertedSequence", edit.path("insertedSequence").asText())
                    .put("insertionExonOrdinal", edit.path("insertionExonOrdinal").asInt()).put("alleleGroupSha256", edit.path("alleleGroupSha256").asText());
            operations.add(row);
        }
        operations.sort(Comparator.comparingInt((ObjectNode e) -> e.path("start0").asInt()).thenComparingInt(e -> e.path("end0").asInt())
                .thenComparing(e -> e.path("origin").asText()).thenComparing(e -> e.path("expectedReference").asText())
                .thenComparing(e -> e.path("insertedSequence").asText()).thenComparingInt(e -> e.path("insertionExonOrdinal").asInt())
                .thenComparing(e -> e.path("alleleGroupSha256").asText()));
        operations.forEach(rows::add);
        return identity;
    }

    private static int[] inverse(CodingTranscriptEdits.Reference reference, CodingTranscriptEdits.Result result) {
        int[] inverse = new int[reference.cds().length() + 1]; int previous = 0;
        for (int altered = 1; altered <= result.alteredCds().length(); altered++) {
            int original = result.alteredToReferenceBase(altered);
            require(original >= 0 && original <= reference.cds().length(), "BASE_MAPPING_INVALID");
            if (original == 0) continue;
            require(original > previous && inverse[original] == 0
                    && reference.cds().charAt(original - 1) == result.alteredCds().charAt(altered - 1), "BASE_MAPPING_INVALID");
            inverse[original] = altered; previous = original;
        }
        return inverse;
    }

    private static List<JsonNode> appliedEdits(JsonNode facts) {
        var applied = new ArrayList<JsonNode>();
        for (var row : facts.path("edits")) if ("APPLIED".equals(row.path("status").asText())) applied.add(row);
        applied.sort(Comparator.comparingInt((JsonNode e) -> e.path("referenceEditStart0").asInt())
                .thenComparingInt(e -> e.path("referenceEditEnd0").asInt()).thenComparing(e -> e.path("origin").asText())
                .thenComparing(e -> e.path("insertedSequence").asText()).thenComparing(e -> e.path("id").asText()));
        return List.copyOf(applied);
    }

    private static byte[] referenceLoss(int length, List<JsonNode> applied) {
        byte[] loss = new byte[length];
        for (var edit : applied) Arrays.fill(loss, edit.path("referenceEditStart0").asInt(), edit.path("referenceEditEnd0").asInt(),
                (byte) (edit.path("insertedSequence").asText().isEmpty() ? 1 : 2));
        return loss;
    }

    private static byte[] insertedOrigins(int length, CodingTranscriptEdits.Result result, List<JsonNode> applied) {
        byte[] origins = new byte[length];
        for (var edit : applied) {
            int count = edit.path("insertedSequence").asText().length(); if (count == 0) continue;
            int boundary = result.mapReferenceBoundary(edit.path("referenceEditStart0").asInt(), CodingTranscriptEdits.Bias.BEFORE);
            require(boundary >= 0 && (long) boundary + count <= length, "INSERTED_MAPPING_INVALID");
            byte kind = (byte) (edit.path("referenceEditStart0").equals(edit.path("referenceEditEnd0")) ? 1 : 2);
            for (int p = boundary; p < boundary + count; p++) {
                require(result.alteredToReferenceBase(p + 1) == 0 && origins[p] == 0, "INSERTED_MAPPING_INVALID"); origins[p] = kind;
            }
        }
        for (int p = 0; p < length; p++) require(result.alteredToReferenceBase(p + 1) != 0 || origins[p] > 0, "INSERTED_MAPPING_INVALID");
        return origins;
    }

    private static void describeCounts(JsonNode facts, ObjectNode out) {
        int applied = 0, aliases = 0, removed = 0;
        for (var edit : facts.path("edits")) switch (edit.path("status").asText()) {
            case "APPLIED" -> applied++;
            case "DUPLICATE_SEQUENCE_EDIT" -> aliases++;
            case "REMOVED_BY_SPLICE" -> removed++;
            default -> throw rejected("EDIT_STATUS_INVALID");
        }
        out.putObject("editCounts").put("inputBindings", facts.path("edits").size()).put("applied", applied)
                .put("duplicateSequenceAliases", aliases).put("removedBySplice", removed);
    }

    private static void describeFrameChanges(List<JsonNode> applied, ObjectNode out) {
        int shift = 0; boolean transientShift = false;
        var rows = out.putArray("frameChanges");
        for (var edit : applied) {
            int before = Math.floorMod(shift, 3), change = edit.path("insertedSequence").asText().length()
                    - edit.path("referenceEditEnd0").asInt() + edit.path("referenceEditStart0").asInt();
            shift += change; int after = Math.floorMod(shift, 3); transientShift |= after != 0;
            var row = rows.addObject();
            for (String field : List.of("id", "origin", "referenceEditStart0", "referenceEditEnd0", "evidenceBindingSha256", "alleleGroupSha256"))
                row.set(field, edit.path(field).deepCopy());
            row.put("netNucleotideChange", change).put("frameBefore", before).put("frameAfter", after);
        }
        out.put("transientReadingFrameChange", transientShift).put("frameRestoredAfterTransientChange", transientShift && Math.floorMod(shift, 3) == 0);
    }

    private static ObjectNode nativeStop(ObjectMapper mapper, CodingTranscriptEdits.Reference reference, int[] inverse) {
        int last = reference.cds().length(), a = inverse[last - 2], b = inverse[last - 1], c = inverse[last];
        var out = mapper.createObjectNode().put("referenceCodon", reference.cds().substring(last - 3));
        points(out, "referencePositions", new int[]{last - 2, last - 1, last}); points(out, "alteredPositions", new int[]{a, b, c});
        out.put("status", a > 0 && b == a + 1 && c == b + 1 ? (a - 1) % 3 == 0 ? "RETAINED_IN_FRAME" : "RETAINED_OUT_OF_FRAME"
                : a > 0 && b > 0 && c > 0 ? "REPARTITIONED" : a > 0 || b > 0 || c > 0 ? "PARTIALLY_RETAINED" : "REMOVED_OR_REPLACED");
        return out;
    }

    private static ObjectNode termination(ObjectMapper mapper, CodingTranscriptEdits.Reference reference,
            CodingTranscriptEdits.Result result, int[] inverse, int firstStop) {
        int last = reference.cds().length(), nativeStart = inverse[last - 2];
        boolean nativeInFrame = nativeStart > 0 && inverse[last - 1] == nativeStart + 1 && inverse[last] == nativeStart + 2 && (nativeStart - 1) % 3 == 0;
        int projected = result.mapReferenceBoundary(last - 3, CodingTranscriptEdits.Bias.AFTER);
        var out = mapper.createObjectNode().put("terminationScope", "DECLARED_EDITED_CDS_ONLY");
        if (projected >= 0) out.put("projectedNativeStopBoundaryStart1", projected + 1); else out.putNull("projectedNativeStopBoundaryStart1");
        if (firstStop < 0) {
            out.put("kind", "NO_STOP_IN_DECLARED_CDS").putNull("alteredStart1").putNull("alteredEnd1").putNull("codon")
                    .put("baseOrigin", "UNRESOLVED"); points(out, "referencePositions", new int[]{0, 0, 0}); return out;
        }
        int start = firstStop * 3 + 1, comparison = nativeInFrame ? nativeStart : projected >= 0 ? projected + 1 : -1;
        String kind = nativeInFrame && start == nativeStart ? "NATIVE_STOP"
                : comparison > 0 && start < comparison ? "PREMATURE_STOP"
                : comparison > 0 && start > comparison ? "DOWNSTREAM_STOP_IN_DECLARED_SEQUENCE"
                : comparison > 0 ? "NON_NATIVE_STOP_AT_PROJECTED_NATIVE_BOUNDARY" : "NON_NATIVE_STOP_RELATION_UNRESOLVED";
        int[] refs = new int[]{result.alteredToReferenceBase(start), result.alteredToReferenceBase(start + 1), result.alteredToReferenceBase(start + 2)};
        int known = (refs[0] > 0 ? 1 : 0) + (refs[1] > 0 ? 1 : 0) + (refs[2] > 0 ? 1 : 0);
        out.put("kind", kind).put("alteredStart1", start).put("alteredEnd1", start + 2)
                .put("codon", result.alteredCds().substring(start - 1, start + 2))
                .put("baseOrigin", known == 3 ? "REFERENCE_BASES" : known == 0 ? "INSERTED_OR_REPLACEMENT_BASES" : "MIXED_REFERENCE_AND_INSERTED_BASES");
        points(out, "referencePositions", refs); return out;
    }

    private static void referenceEffects(String protein, int[] inverse, byte[] loss, int firstStop, ObjectNode out) {
        var rows = out.putArray("referenceResidueEffects"); ObjectNode previous = null; int[] previousPositions = null;
        int deleted = 0, replaced = 0, partial = 0, repartitioned = 0;
        for (int residue = 1; residue < protein.length(); residue++) {
            int start = residue * 3 - 2, count = 0, mask = 0, deletionBases = 0, replacedBases = 0;
            int[] positions = new int[]{inverse[start], inverse[start + 1], inverse[start + 2]};
            for (int i = 0; i < 3; i++) {
                if (positions[i] > 0) { count++; mask |= 1 << i; }
                else { require(loss[start + i - 1] != 0, "REFERENCE_LOSS_MAPPING_INVALID"); if (loss[start + i - 1] == 1) deletionBases++; else replacedBases++; }
            }
            boolean consecutive = count == 3 && positions[1] == positions[0] + 1 && positions[2] == positions[1] + 1;
            String effect;
            if (consecutive && (positions[0] - 1) % 3 == 0) effect = "EXACT_RETAINED_REFERENCE_CODON";
            else if (count == 0 && deletionBases == 3) { effect = "WHOLE_REFERENCE_CODON_DELETED"; deleted++; }
            else if (count == 0) { effect = "REFERENCE_CODON_REPLACED_OR_DELETED_AND_REPLACED"; replaced++; }
            else if (count < 3) { effect = "REFERENCE_CODON_PARTIALLY_DELETED_OR_REPLACED"; partial++; }
            else { effect = "REFERENCE_CODON_REPARTITIONED"; repartitioned++; }
            String stage = referenceTranslationStage(positions, firstStop);
            String composition = deletionBases > 0 && replacedBases > 0 ? "DELETION_AND_REPLACEMENT" : deletionBases > 0 ? "DELETION" : replacedBases > 0 ? "REPLACEMENT" : "NO_REFERENCE_BASE_LOSS";
            if (previous != null && effect.equals(previous.path("effect").asText()) && stage.equals(previous.path("translationStatus").asText())
                    && composition.equals(previous.path("lossComposition").asText()) && previous.path("retainedBaseMask").asInt() == mask
                    && shifted(previousPositions, positions)) {
                previous.put("endReferenceResidue", residue).put("codonCount", residue - previous.path("startReferenceResidue").asInt() + 1);
                points(previous, "alteredPositionsAtLastReferenceCodon", positions);
            } else {
                previous = rows.addObject().put("startReferenceResidue", residue).put("endReferenceResidue", residue).put("codonCount", 1)
                        .put("effect", effect).put("retainedBaseMask", mask).put("lossComposition", composition)
                        .put("translationStatus", stage).put("referenceAminoAcids", String.valueOf(protein.charAt(residue - 1)));
                points(previous, "alteredPositionsAtFirstReferenceCodon", positions); points(previous, "alteredPositionsAtLastReferenceCodon", positions);
                previous.put("mappingStridePerCodon", count == 0 ? 0 : 3);
            }
            previousPositions = positions;
        }
        for (var row : rows) ((ObjectNode) row).put("referenceAminoAcids",
                protein.substring(row.path("startReferenceResidue").asInt() - 1, row.path("endReferenceResidue").asInt()));
        out.put("wholeDeletedReferenceCodons", deleted).put("replacedReferenceCodons", replaced)
                .put("partiallyDeletedOrReplacedReferenceCodons", partial).put("repartitionedReferenceCodons", repartitioned);
    }

    private static String referenceTranslationStage(int[] positions, int firstStop) {
        int minimum = Integer.MAX_VALUE, maximum = 0;
        for (int p : positions) if (p > 0) { minimum = Math.min(minimum, p); maximum = Math.max(maximum, p); }
        if (maximum == 0) return "NO_SURVIVING_REFERENCE_BASES";
        if (firstStop < 0) return "DECLARED_FRAME_SCAN_WITHOUT_TERMINATION";
        int stopStart = firstStop * 3 + 1;
        for (int p : positions) if (p >= stopStart && p <= stopStart + 2) return "OVERLAPS_FIRST_STOP";
        return maximum < stopStart ? "BEFORE_FIRST_STOP" : minimum > stopStart + 2 ? "DOWNSTREAM_OF_FIRST_STOP_NOT_TRANSLATED"
                : "STRADDLES_FIRST_STOP_WITHOUT_BASE_OVERLAP";
    }

    private static void alteredEffects(String altered, String codons, CodingTranscriptEdits.Result result,
            byte[] origins, int firstStop, ObjectNode out) {
        var rows = out.putArray("alteredCodonEffects"); ObjectNode previous = null; int[] previousRefs = null;
        for (int index = 0; index < codons.length(); index++) {
            int start = index * 3 + 1; int[] refs = new int[]{result.alteredToReferenceBase(start), result.alteredToReferenceBase(start + 1), result.alteredToReferenceBase(start + 2)};
            int known = (refs[0] > 0 ? 1 : 0) + (refs[1] > 0 ? 1 : 0) + (refs[2] > 0 ? 1 : 0);
            boolean consecutive = known == 3 && refs[1] == refs[0] + 1 && refs[2] == refs[1] + 1;
            String effect = consecutive ? (refs[0] - 1) % 3 == 0 ? "RETAINED_REFERENCE_CODON" : "REFERENCE_BASES_REPARTITIONED_IN_READING_FRAME"
                    : known == 3 ? "FUSED_REFERENCE_CODON" : known > 0 ? "MIXED_REFERENCE_AND_INSERTED_CODON"
                    : origins[start - 1] == 1 && origins[start] == 1 && origins[start + 1] == 1 ? "INSERTED_CODON"
                    : origins[start - 1] == 2 && origins[start] == 2 && origins[start + 1] == 2 ? "REENCODED_CODON" : "INSERTED_OR_REENCODED_CODON";
            String stage = firstStop < 0 ? "DECLARED_FRAME_SCAN_WITHOUT_TERMINATION" : index < firstStop ? "TRANSLATED_FROM_DECLARED_ORIGIN"
                    : index == firstStop ? "FIRST_STOP" : "DOWNSTREAM_OF_FIRST_STOP_NOT_TRANSLATED";
            boolean compactable = consecutive || known == 0;
            if (previous != null && compactable && effect.equals(previous.path("effect").asText())
                    && stage.equals(previous.path("translationStatus").asText()) && shifted(previousRefs, refs)) {
                previous.put("endAlteredCodon", index + 1).put("codonCount", index + 2 - previous.path("startAlteredCodon").asInt());
                points(previous, "referencePositionsAtLastCodon", refs);
            } else {
                previous = rows.addObject().put("startAlteredCodon", index + 1).put("endAlteredCodon", index + 1).put("codonCount", 1)
                        .put("effect", effect).put("translationStatus", stage).put("aminoAcidsFromCodonTable", String.valueOf(codons.charAt(index)));
                points(previous, "referencePositionsAtFirstCodon", refs); points(previous, "referencePositionsAtLastCodon", refs);
                previous.put("referenceMappingStridePerCodon", known == 0 ? 0 : consecutive ? 3 : 0);
                if (!compactable) previous.put("alteredCodon", altered.substring(start - 1, start + 2));
            }
            previousRefs = refs;
        }
        for (var row : rows) ((ObjectNode) row).put("aminoAcidsFromCodonTable",
                codons.substring(row.path("startAlteredCodon").asInt() - 1, row.path("endAlteredCodon").asInt()));
    }

    /** Direct edits plus all reference codons whose base/reading-frame identity changed.
     * Pure insertions include their two reference flanks, without calling either residue deleted.
     */
    private static void describeRanges(int residues, List<JsonNode> applied, ObjectNode out) {
        boolean[] deleted = new boolean[residues], touched = new boolean[residues];
        for (var row : out.path("referenceResidueEffects")) {
            int lo = row.path("startReferenceResidue").asInt() - 1, hi = row.path("endReferenceResidue").asInt();
            if ("WHOLE_REFERENCE_CODON_DELETED".equals(row.path("effect").asText())) Arrays.fill(deleted, lo, hi, true);
            if (!"EXACT_RETAINED_REFERENCE_CODON".equals(row.path("effect").asText())) Arrays.fill(touched, lo, hi, true);
        }
        for (var edit : applied) {
            int start = edit.path("referenceEditStart0").asInt(), end = edit.path("referenceEditEnd0").asInt();
            int lo = start == end ? Math.max(0, start - 1) / 3 : start / 3;
            int hi = start == end ? start / 3 : (end - 1) / 3;
            for (int r = lo; r <= hi && r < residues; r++) touched[r] = true;
        }
        rangeRows(out, "wholeDeletedReferenceResidueRanges", deleted); rangeRows(out, "touchedReferenceResidueRanges", touched);
    }
    private static void rangeRows(ObjectNode out, String field, boolean[] flags) {
        var rows = out.putArray(field); int p = 0;
        while (p < flags.length) {
            if (!flags[p]) { p++; continue; }
            int start = p; while (p < flags.length && flags[p]) p++;
            rows.addObject().put("start", start + 1).put("end", p);
        }
    }

    private static boolean shifted(int[] prior, int[] current) {
        if (prior == null) return false;
        for (int i = 0; i < 3; i++) if (prior[i] == 0 ? current[i] != 0 : current[i] != prior[i] + 3) return false;
        return true;
    }
    /** A5 uses zero internally for bases without reference identity; public/private facts use JSON null. */
    private static void points(ObjectNode out, String field, int[] positions) {
        var values = out.putArray(field); for (int position : positions) if (position > 0) values.add(position); else values.addNull();
    }
    private static String decode(String cds) {
        var protein = new StringBuilder(cds.length() / 3);
        for (int p = 0; p + 2 < cds.length(); p += 3) protein.append(TruncatingSequence.translateCodon(cds.substring(p, p + 3)));
        return protein.toString();
    }
    private static String sha(String sequence) { return CodingTranscriptMaterialSupply.sha(sequence); }
    private static IllegalArgumentException rejected(String code) { return new IllegalArgumentException("CODING_CONSEQUENCE_" + code); }
    private static void require(boolean condition, String code) { if (!condition) throw rejected(code); }
}
