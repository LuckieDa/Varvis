package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;

/** Neutral coding-sequence observation. Neither PVS1 policy nor LoF/disease qualification is consumed.
 * The caller supplies its actual current resolveStructure material; no JSON receipt can mint it. */
final class Bp1TruncatingConsequence {
    static final String VERSION = "1.0.0";
    private Bp1TruncatingConsequence() { }
    private enum Creation { OBSERVED }

    static final class Observation {
        private final ObjectNode value;
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DISABLED)
        private Observation(ObjectNode value, Creation creation) {
            if (creation != Creation.OBSERVED) throw new IllegalArgumentException("Observation creation required");
            this.value = value.deepCopy();
        }
        ObjectNode snapshot() { return value.deepCopy(); }
        boolean ready() { return value.path("sequenceTruncationCandidate").asBoolean(false); }
        boolean sequenceTruncationCandidate() { return ready(); }
        boolean sequenceConfirmed() { return ready(); }
    }

    static Observation observe(ObjectMapper mapper, ObjectNode context, VariantInterpretation candidate,
            @Nullable CodingTranscriptMaterialSupply.Material structure) {
        var out = mapper.createObjectNode().put("schemaVersion", "bp1-truncating-consequence-v1").put("methodVersion", VERSION)
                .put("status", "UNAVAILABLE").put("reasonCode", "BP1_TRUNCATING_STRUCTURE_MATERIAL_REQUIRED")
                .put("sequenceTruncationCandidate", false).put("appliedStrength", "NONE")
                .put("lossOfFunctionEstablished", false).put("bp1Adoption", false).put("clinicalValidationCompleted", false)
                .put("createsScientificApproval", false).put("nmdPrediction", "NOT_ASSESSED");
        try {
            context = context.deepCopy();
            if (structure == null || !structure.ready()) throw new Rejected("BP1_TRUNCATING_STRUCTURE_MATERIAL_REQUIRED");
            var audit = structure.snapshot(); var model = structure.model();
            if (model == null) throw new Rejected("BP1_TRUNCATING_STRUCTURE_MATERIAL_REQUIRED");
            require("EXACT_TRANSCRIPT_LOCAL_STRUCTURE_SUPPLY".equals(audit.path("method").asText())
                    && CodingTranscriptMaterialSupply.STRUCTURE_VERSION.equals(audit.path("version").asText())
                    && "EXACT_MODEL_REFERENCE_AND_SELECTED_ANNOTATION_BOUND".equals(audit.path("reasonCode").asText())
                    && audit.path("alleleEffectVerified").isBoolean() && !audit.path("alleleEffectVerified").asBoolean(),
                    "BP1_TRUNCATING_STRUCTURE_PURPOSE_MISMATCH");
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            adapter.validateCompleteContext(context);
            require(adapter.canonicalContextId(context).equals(context.path("contextId").asText()), "BP1_TRUNCATING_CONTEXT_BINDING_INVALID");
            require("GRCh38".equals(context.at("/variant/evidenceQueryAllele/assembly").asText())
                    && "NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText()), "BP1_TRUNCATING_NATIVE_GRCH38_REQUIRED");
            require(CodingTranscriptMapping.modelIssue(model, context) == null, "BP1_TRUNCATING_MODEL_CONTEXT_MISMATCH");
            var allele = context.at("/variant/evidenceQueryAllele");
            if (candidate == null) throw new Rejected("BP1_TRUNCATING_CANDIDATE_ALLELE_MISMATCH");
            var observation = candidate.getObservation();
            if (observation == null) throw new Rejected("BP1_TRUNCATING_CANDIDATE_ALLELE_MISMATCH");
            var key = observation.getKey();
            if (key == null) throw new Rejected("BP1_TRUNCATING_CANDIDATE_ALLELE_MISMATCH");
            String candidateId = candidate.getCandidateId();
            if (candidateId == null || candidateId.isBlank()) throw new Rejected("BP1_TRUNCATING_CANDIDATE_ID_REQUIRED");
            require(Objects.equals(key.getGenomeAssembly(), allele.path("assembly").asText())
                    && Objects.equals(key.getChromosome(), allele.path("contig").asText())
                    && key.getPosition() == allele.path("position").asInt()
                    && Objects.equals(key.getReference(), allele.path("reference").asText())
                    && Objects.equals(key.getAlternate(), allele.path("alternate").asText()), "BP1_TRUNCATING_CANDIDATE_ALLELE_MISMATCH");
            String cds = model.path("cdsSequence").asText(), modelSha = adapter.canonicalSha256(model);
            var verification = audit.path("verification");
            require(modelSha.equals(audit.path("modelCanonicalSha256").asText())
                    && CodingTranscriptMaterialSupply.sha(cds).equals(verification.path("cdsSha256").asText())
                    && verification.path("fullSplicedTranscriptSha256").equals(model.at("/sequenceVerification/fullSplicedTranscriptSha256"))
                    && verification.at("/referenceIdentity/expectedSha256").equals(context.at("/variant/normalization/referenceSequenceSha256")),
                    "BP1_TRUNCATING_STRUCTURE_BINDING_INVALID");
            var tx = new RuntimeEvidenceV2ProviderWiring(mapper, adapter).selectedVepConsequence(context, candidate);
            if (tx == null) throw new Rejected("BP1_TRUNCATING_SELECTED_ANNOTATION_NOT_UNIQUE");
            String transcript = model.path("transcript").asText(), protein = model.path("protein").asText();
            require(Set.of("VEP", "VEP_CACHED").contains(Objects.toString(tx.getSource(), ""))
                    && "COMPLETED".equals(tx.getStatus()) && "116.0".equals(tx.getVersion()) && tx.getTimestamp() != null
                    && "116.0".equals(context.at("/transcript/annotationSourceVersion").asText())
                    && transcript.equals(tx.getVersionedTranscriptId()) && transcript.equals(audit.path("transcript").asText())
                    && protein.equals(tx.getProteinIdentity()), "BP1_TRUNCATING_ANNOTATION_IDENTITY_MISMATCH");
            String accession = transcript.substring(0, transcript.lastIndexOf('.'));
            for (String alias : new String[] { tx.getTranscriptId(), tx.getTranscriptAccession() })
                require(alias == null || alias.isBlank() || alias.equals(accession) || alias.equals(transcript),
                        "BP1_TRUNCATING_ANNOTATION_IDENTITY_MISMATCH");
            String consequence = Objects.toString(tx.getConsequence(), "");
            require(!consequence.contains("splice") && !consequence.contains("intron")
                    && (tx.getIntron() == null || tx.getIntron().isBlank() || "-".equals(tx.getIntron())),
                    "BP1_TRUNCATING_SPLICE_CONSEQUENCE_UNSUPPORTED");
            require(!consequence.contains("start_lost"), "BP1_TRUNCATING_START_LOSS_UNSUPPORTED");
            String ref = allele.path("reference").asText(), alt = allele.path("alternate").asText();
            boolean nonsense = ref.length() == 1 && alt.length() == 1;
            String type = ref.length() == alt.length() ? nonsense ? "SNV" : "MNV"
                    : ref.length() == 1 ? "SMALL_INSERTION" : alt.length() == 1 ? "SMALL_DELETION" : "SMALL_DELINS";
            require(type.equals(context.at("/variant/variantType").asText()), "BP1_TRUNCATING_VARIANT_TYPE_MISMATCH");
            require(nonsense || (alt.length() - ref.length()) % 3 != 0, "BP1_TRUNCATING_FRAMESHIFT_NOT_CONFIRMED");
            require((nonsense ? "stop_gained" : "frameshift_variant").equals(consequence), "BP1_TRUNCATING_CONSEQUENCE_SCOPE_UNSUPPORTED");
            var reference = CodingTranscriptEdits.bind(mapper, context, model, modelSha, CodingTranscriptMaterialSupply.sha(cds));
            // Exactly one physical allele. There is no event-list or unphased-composition input route.
            var edit = CodingTranscriptEdits.projectAllele(reference, allele, adapter.canonicalSha256(allele));
            var exon = CodingTranscriptMapping.exonForGenomicInterval(model, allele.path("position").asInt(),
                    Math.addExact(allele.path("position").asInt(), ref.length() - 1));
            if (exon == null) throw new Rejected("BP1_TRUNCATING_CODING_EXON_BOUNDARY_UNSUPPORTED");
            require(sameExon(edit, exon, cds.length()), "BP1_TRUNCATING_CODING_EXON_BOUNDARY_UNSUPPORTED");
            require(edit.referenceEditStart0() >= 3, "BP1_TRUNCATING_START_REGION_UNSUPPORTED");
            require(edit.referenceEditEnd0() <= cds.length() - 3 && edit.referenceEditStart0() < cds.length() - 3,
                    "BP1_TRUNCATING_NATIVE_STOP_REGION_UNSUPPORTED");
            require((exon.path("ordinal").asInt() + "/" + model.path("exons").size()).equals(tx.getExon()), "BP1_TRUNCATING_EXON_ANNOTATION_MISMATCH");
            String hgvsc = Objects.toString(tx.getHgvsc(), "");
            require(hgvsc.startsWith(transcript + ":c."), "BP1_TRUNCATING_HGVSC_TRANSCRIPT_MISMATCH");
            if (nonsense) require(hgvsc.matches(Pattern.quote(transcript) + ":c\\.[1-9][0-9]*[ACGT]>[ACGT]"),
                    "BP1_TRUNCATING_HGVSC_SCOPE_UNSUPPORTED");
            var hgvs = CodingTranscriptEdits.codingHgvsEdit(reference, hgvsc, CodingTranscriptMaterialSupply.sha(hgvsc));
            require(sameExon(hgvs, exon, cds.length()) && hgvs.referenceEditStart0() >= 3 && hgvs.referenceEditEnd0() <= cds.length() - 3,
                    "BP1_TRUNCATING_CODING_EXON_BOUNDARY_UNSUPPORTED");
            require(CodingTranscriptEdits.sequenceEquivalent(reference, edit, hgvs), "BP1_TRUNCATING_HGVSC_SEQUENCE_MISMATCH");
            var changed = CodingTranscriptEdits.apply(reference, List.of(edit));
            String altered = changed.alteredCds(), originalProtein = translate(cds), alteredProtein = translate(altered);
            require(nonsense ? changed.delta() == 0 : changed.delta() % 3 != 0, "BP1_TRUNCATING_FRAMESHIFT_NOT_CONFIRMED");
            int stop = alteredProtein.indexOf('*'), first = 0;
            require(stop >= 0, "BP1_TRUNCATING_TERMINATION_UNRESOLVED");
            while (first < Math.min(originalProtein.length(), alteredProtein.length()) && originalProtein.charAt(first) == alteredProtein.charAt(first)) first++;
            int ptcStart = stop * 3 + 1, ptcEnd = ptcStart + 2;
            int mappedNativeStop = changed.mapReferenceBoundary(cds.length() - 3, CodingTranscriptEdits.Bias.AFTER);
            require(first < originalProtein.length() - 1 && first <= stop && mappedNativeStop >= 0 && ptcStart < mappedNativeStop + 1,
                    "BP1_TRUNCATING_PTC_NOT_PREMATURE");
            if (nonsense) require(first == stop, "BP1_TRUNCATING_NONSENSE_NOT_CONFIRMED");
            verifyProtein(tx, model, cds, altered, originalProtein, alteredProtein, edit, first, stop, nonsense);
            var annotationMapper = mapper.copy().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            JsonNode annotation = annotationMapper.valueToTree(tx);
            var binding = out.putObject("binding").put("contextId", context.path("contextId").asText())
                    .put("contextSha256", adapter.canonicalSha256(context)).put("alleleSha256", adapter.canonicalSha256(allele))
                    .put("structureMaterialSha256", adapter.canonicalSha256(audit)).put("modelCanonicalSha256", modelSha)
                    .put("referenceCdsSha256", reference.cdsSha256()).put("alteredCdsSha256", changed.alteredCdsSha256())
                    .put("referenceSequenceSha256", context.at("/variant/normalization/referenceSequenceSha256").asText())
                    .put("fullSplicedReferenceTranscriptSha256", verification.path("fullSplicedTranscriptSha256").asText())
                    .put("storedAnnotationSha256", adapter.canonicalSha256(annotation));
            binding.put("transcript", transcript).put("protein", protein).put("candidateId", candidateId);
            out.put("contextId", context.path("contextId").asText()).put("contextCanonicalSha256", adapter.canonicalSha256(context))
                    .put("nativeModelSha256", modelSha).put("structureMaterialSha256", adapter.canonicalSha256(audit))
                    .put("candidateId", candidateId);
            out.set("queryAllele", allele.deepCopy());
            out.put("mechanism", nonsense ? "NONSENSE" : "FRAMESHIFT").put("coordinateSystem", "ALTERED_CDS")
                    .put("referenceEditStart0", edit.referenceEditStart0()).put("referenceEditEnd0", edit.referenceEditEnd0())
                    .put("netLengthChange", changed.delta()).put("referenceCdsLength", cds.length()).put("alteredCdsLength", altered.length())
                    .put("exonOrdinal", exon.path("ordinal").asInt()).put("exonTotal", model.path("exons").size())
                    .put("firstChangedProteinPosition", first + 1).put("ptcStart", ptcStart).put("ptcEnd", ptcEnd)
                    .put("terminationDistance", stop - first + 1).put("mappedNativeStopStart", mappedNativeStop + 1)
                    .put("annotationCorrespondence", "COMPLETE").put("hgvscSequenceEquivalent", true);
            boolean mapped = true;
            for (int index = 0; index < 3; index++) {
                String name = List.of("ptcReferenceStart", "ptcReferenceMiddle", "ptcReferenceEnd").get(index);
                int position = changed.alteredToReferenceBase(ptcStart + index);
                if (position > 0) out.put(name, position); else { out.putNull(name); mapped = false; }
            }
            out.put("ptcReferenceMapping", mapped ? "REFERENCE_BASES" : "CONTAINS_INSERTED_BASES");
            topology(mapper, model, changed, ptcEnd, out);
            out.put("status", "TRUNCATION_CANDIDATE").put("reasonCode", "BP1_TRUNCATING_SEQUENCE_AND_ANNOTATION_AGREE")
                    .put("sequenceTruncationCandidate", true);
        } catch (Rejected rejected) { out.put("reasonCode", rejected.getMessage()); }
        catch (CodingTranscriptEdits.Rejected rejected) {
            out.put("reasonCode", "BP1_TRUNCATING_CODING_EDIT_REJECTED").put("codingEditReasonCode", rejected.reasonCode());
        } catch (RuntimeException invalid) { out.put("reasonCode", "BP1_TRUNCATING_INPUT_BINDING_INVALID"); }
        return new Observation(out, Creation.OBSERVED);
    }

    private static boolean sameExon(CodingTranscriptEdits.Edit edit, JsonNode exon, int length) {
        int lo = Math.max(0, exon.path("cdsStart").asInt() - 1), hi = Math.min(length, exon.path("cdsEnd").asInt());
        return edit.referenceEditStart0() == edit.referenceEditEnd0()
                ? edit.referenceEditStart0() > lo && edit.referenceEditStart0() < hi
                : edit.referenceEditStart0() >= lo && edit.referenceEditEnd0() <= hi;
    }

    private static void verifyProtein(TranscriptConsequence tx, JsonNode model, String cds, String changed,
            String refProtein, String altProtein, CodingTranscriptEdits.Edit edit, int first, int stop, boolean nonsense) {
        char from = refProtein.charAt(first), to = altProtein.charAt(first);
        String expected = model.path("protein").asText() + ":p." + TruncatingSequence.aminoName(from) + (first + 1)
                + (to == '*' ? "Ter" : TruncatingSequence.aminoName(to) + "fsTer" + (stop - first + 1));
        require(expected.equals(tx.getHgvsp()), "BP1_TRUNCATING_PROTEIN_ANNOTATION_MISMATCH");
        String position, refCodons, altCodons;
        if (nonsense) {
            position = Integer.toString(first + 1); refCodons = cds.substring(first * 3, first * 3 + 3);
            altCodons = changed.substring(first * 3, first * 3 + 3);
        } else if (edit.referenceEditStart0() == edit.referenceEditEnd0() && edit.referenceEditStart0() % 3 == 0) {
            position = (edit.referenceEditStart0() / 3) + "-" + (edit.referenceEditStart0() / 3 + 1);
            refCodons = ""; altCodons = edit.insertedSequence();
        } else {
            int lo = edit.referenceEditStart0() / 3 * 3;
            int hi = (Math.max(edit.referenceEditStart0(), edit.referenceEditEnd0() - 1) / 3 + 1) * 3;
            position = lo / 3 + 1 == hi / 3 ? Integer.toString(lo / 3 + 1) : (lo / 3 + 1) + "-" + hi / 3;
            refCodons = cds.substring(lo, hi); altCodons = changed.substring(lo, hi + changed.length() - cds.length());
        }
        String codons = (refCodons.isEmpty() ? "-" : refCodons) + "/" + (altCodons.isEmpty() ? "-" : altCodons);
        require(position.equals(tx.getProteinPosition()) && codons.equals(Objects.toString(tx.getCodons(), "").toUpperCase(Locale.ROOT))
                && TruncatingFrameshiftResolver.localAminoAcids(refCodons, altCodons).equals(tx.getAminoAcids()),
                "BP1_TRUNCATING_LOCAL_ANNOTATION_MISMATCH");
    }

    private static void topology(ObjectMapper mapper, JsonNode model, CodingTranscriptEdits.Result changed, int ptcEnd, ObjectNode out) {
        var topology = out.putObject("junctionGeometry").put("nmdPrediction", "NOT_ASSESSED").put("thresholdApplied", false);
        var original = topology.putArray("referenceCodingJunctions0"); int length = model.path("cdsLength").asInt();
        for (var exon : model.path("exons")) {
            int end = exon.path("cdsEnd").asInt(); if (end > 0 && end < length) original.add(end);
        }
        topology.set("alteredCodingJunctions0", mapper.valueToTree(changed.junctions()));
        topology.putNull("alteredLastTranscriptJunctionCdsBoundary0").putNull("distanceToLastJunction");
        var exons = model.path("exons");
        if (exons.size() == 1) {
            require(!model.hasNonNull("lastJunctionCdsPosition"), "BP1_TRUNCATING_JUNCTION_MODEL_CONFLICT");
            topology.put("kind", "SINGLE_EXON").putNull("referenceLastTranscriptJunctionCdsBoundary0"); return;
        }
        int junction = exons.get(exons.size() - 2).path("cdsEnd").asInt();
        if (model.has("lastJunctionCdsPosition")) require(model.path("lastJunctionCdsPosition").isIntegralNumber()
                && model.path("lastJunctionCdsPosition").canConvertToInt()
                && model.path("lastJunctionCdsPosition").asInt() == junction, "BP1_TRUNCATING_JUNCTION_MODEL_CONFLICT");
        topology.put("referenceLastTranscriptJunctionCdsBoundary0", junction);
        if (junction >= length) { topology.put("kind", "LAST_JUNCTION_IN_3_PRIME_UTR"); return; }
        if (junction <= 0) { topology.put("kind", "LAST_JUNCTION_BEFORE_OR_AT_CDS_START"); return; }
        int mapped = changed.mapReferenceBoundary(junction, CodingTranscriptEdits.Bias.AFTER);
        require(mapped >= 0, "BP1_TRUNCATING_JUNCTION_MAPPING_UNRESOLVED");
        topology.put("kind", "LAST_JUNCTION_WITHIN_CDS").put("alteredLastTranscriptJunctionCdsBoundary0", mapped)
                .put("distanceToLastJunction", mapped - ptcEnd).put("distanceCoordinateSystem", "ALTERED_CDS");
    }

    private static String translate(String cds) {
        var protein = new StringBuilder();
        for (int index = 0; index + 3 <= cds.length(); index += 3) protein.append(TruncatingSequence.translateCodon(cds.substring(index, index + 3)));
        return protein.toString();
    }
    private static final class Rejected extends IllegalArgumentException { private Rejected(String reason) { super(reason); } }
    private static void require(boolean value, String reason) { if (!value) throw new Rejected(reason); }
}
