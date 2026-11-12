package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import static org.junit.jupiter.api.Assertions.*;

/** Literal independent 363bp vectors frozen before implementation. All reference/clinical data are
 * SYNTHETIC engineering transport. Actual MaterialSupply reads/pins the complete tiny reference;
 * no Material constructor, PVS1 policy, scientific approval or core stub is used. */
class Bp1TruncatingConsequenceTest {
    @TempDir Path temporary;

    static final class Fixture {
        final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
        final Path root;
        final String kind;
        final int strand;
        final ObjectNode context, model;
        ObjectNode annotation;
        VariantInterpretation candidate;
        CodingTranscriptMaterialSupply supply;
        CodingTranscriptMaterialSupply.Material lastMaterial;
        String prefix = "", tail = "", genome, referenceSha;
        Fixture(Path root, String kind, int strand) throws Exception {
            this.root = root; this.kind = kind; this.strand = strand; Files.createDirectories(root);
            context = new RevelPredictionObservationTest().input("0.99", "GE", "0.7").fixture().context().deepCopy();
            String cds = switch (kind) {
                case "STOP" -> "ATG" + "AAA".repeat(59) + "CGA" + "AAA".repeat(59) + "TAA";
                case "DEL" -> "ATG" + "AAA".repeat(59) + "ATA" + "GAA" + "AAA".repeat(58) + "TAA";
                case "INS" -> "ATG" + "AAA".repeat(59) + "GAA" + "AAA".repeat(59) + "TAA";
                default -> throw new IllegalArgumentException(kind);
            };
            model = mapper.createObjectNode().put("schemaVersion", "native-coding-transcript-model-v1")
                    .put("modelId", "SYNTHETIC_BP1_TRUNCATION_363").put("modelVersion", "1")
                    .put("geneSymbol", "SYNTH1").put("hgncId", "HGNC:1").put("assembly", "GRCh38").put("contig", "1")
                    .put("strand", strand).put("transcript", "ENST000001.4").put("protein", "NP_000001.1")
                    .put("cdsSequence", cds).put("cdsLength", cds.length()).put("proteinLength", 120);
            model.putArray("sourceRecords").addObject().put("sourceId", "SYNTHETIC_BP1_MECHANICS_ONLY").put("version", "1")
                    .put("url", "https://example.invalid/engineering-only")
                    .put("sha256", CodingTranscriptMaterialSupply.sha("SYNTHETIC_BP1_MECHANICS_ONLY"));
            exons(210, 330, 363); rebuild();
        }
        void exons(int... ends) {
            var exons = model.putArray("exons"); int start = 1;
            for (int i = 0; i < ends.length; i++) {
                int genomicStart = strand == 1 ? (i + 1) * 1000 : (ends.length - i) * 1000;
                exons.addObject().put("ordinal", i + 1).put("genomicStart", genomicStart)
                        .put("genomicEnd", genomicStart + ends[i] - start).put("cdsStart", start).put("cdsEnd", ends[i]);
                start = ends[i] + 1;
            }
            if (ends.length == 1) model.remove("lastJunctionCdsPosition");
            else model.put("lastJunctionCdsPosition", ends[ends.length - 2]);
        }
        int genomic(int c) {
            for (var exon : model.path("exons")) if (c >= exon.path("cdsStart").asInt() && c <= exon.path("cdsEnd").asInt())
                return strand == 1 ? exon.path("genomicStart").asInt() + c - exon.path("cdsStart").asInt()
                        : exon.path("genomicEnd").asInt() - c + exon.path("cdsStart").asInt();
            throw new IllegalArgumentException("Unmapped synthetic position " + c);
        }
        void rebuild() throws Exception {
            String spliced = prefix + model.path("cdsSequence").asText() + tail;
            var bases = new char[6500]; Arrays.fill(bases, 'N');
            for (var exon : model.path("exons")) for (int i = 0; i <= exon.path("genomicEnd").asInt() - exon.path("genomicStart").asInt(); i++) {
                char base = spliced.charAt(prefix.length() + exon.path("cdsStart").asInt() - 1 + i);
                int g = strand == 1 ? exon.path("genomicStart").asInt() + i : exon.path("genomicEnd").asInt() - i;
                bases[g - 1] = strand == 1 ? base : TruncatingSequence.reverseComplement(Character.toString(base)).charAt(0);
            }
            genome = new String(bases); referenceSha = CodingTranscriptMaterialSupply.sha(genome);
            model.putObject("sequenceVerification").put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(spliced))
                    .put("cdsSha256", CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText()));
            ((ObjectNode) context.at("/variant/normalization")).put("referenceSequenceSha256", referenceSha);
            annotation = mapper.createObjectNode().put("geneSymbol", model.path("geneSymbol").asText())
                    .put("transcriptAccession", model.path("transcript").asText()).put("transcriptId", "ENST000001")
                    .put("transcriptSource", "ENSEMBL").put("source", "VEP").put("status", "COMPLETED")
                    .put("version", "116.0").put("timestamp", "2026-09-01T00:00:00Z");
            if ("STOP".equals(kind)) {
                setAllele(genomic(181), oriented("C"), oriented("T"), "SNV");
                annotation.put("hgvsCoding", "ENST000001.4:c.181C>T").put("hgvsProtein", "NP_000001.1:p.Arg61Ter")
                        .put("consequence", "stop_gained").put("proteinPosition", "61").put("aminoAcids", "R/*").put("codons", "CGA/TGA");
            } else if ("DEL".equals(kind)) {
                int position = strand == 1 ? genomic(180) : genomic(182);
                String ref = strand == 1 ? "AA" : "AT";
                setAllele(position, ref, ref.substring(0, 1), "SMALL_DELETION");
                annotation.put("hgvsCoding", "ENST000001.4:c.181del").put("hgvsProtein", "NP_000001.1:p.Ile61Ter")
                        .put("consequence", "frameshift_variant").put("proteinPosition", "61").put("aminoAcids", "I/X").put("codons", "ATA/TA");
            } else {
                int position = strand == 1 ? genomic(180) : genomic(181); String ref = strand == 1 ? "A" : "C";
                setAllele(position, ref, ref + oriented("T"), "SMALL_INSERTION");
                annotation.put("hgvsCoding", "ENST000001.4:c.180_181insT").put("hgvsProtein", "NP_000001.1:p.Glu61Ter")
                        .put("consequence", "frameshift_variant").put("proteinPosition", "60-61").put("aminoAcids", "-/X").put("codons", "-/T");
            }
            var exon = CodingTranscriptMapping.exonForGenomicInterval(model, candidate.getObservation().getKey().getPosition(),
                    candidate.getObservation().getKey().getPosition() + candidate.getObservation().getKey().getReference().length() - 1);
            if (exon == null) throw new IllegalArgumentException("Synthetic exon interval invalid");
            annotation.put("exon", exon.path("ordinal").asInt() + "/" + model.path("exons").size()); applyAnnotation(); writeSupply();
        }
        String oriented(String sequence) { return strand == 1 ? sequence : TruncatingSequence.reverseComplement(sequence); }
        void canonicalizeContext() {
            var variant = (ObjectNode) context.path("variant");
            variant.put("alleleId", adapter.canonicalAlleleId(variant.path("evidenceQueryAllele"), variant.path("normalization")));
            context.put("contextId", adapter.canonicalContextId(context));
        }
        void setAllele(int position, String ref, String alt, String type) {
            var allele = mapper.createObjectNode().put("assembly", "GRCh38").put("contig", "1").put("position", position)
                    .put("reference", ref).put("alternate", alt);
            var variant = (ObjectNode) context.path("variant");
            for (String name : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) variant.set(name, allele.deepCopy());
            variant.put("variantType", type); canonicalizeContext();
            var key = new NormalizedVariantKey("GRCh38", "1", position, ref, alt);
            var qc = new VariantQcObservation(key, "SYNTHETIC_ENGINEERING_ONLY", "0/1", null, null, null, null, "PASS", type, null);
            candidate = new VariantInterpretation("SYNTHETIC_" + kind + "_" + strand, qc, List.of(), List.of());
        }
        void applyAnnotation() throws Exception { candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class))); }
        void writeSupply() throws Exception {
            String transcript = model.path("transcript").asText(); byte[] bytes = mapper.writeValueAsBytes(model);
            Files.write(root.resolve(transcript + ".model.json"), bytes);
            var manifest = mapper.createObjectNode().put("schemaVersion", "portable-native-transcript-model-derivation-v1");
            manifest.putArray("entries").addObject().put("transcript", transcript).put("file", transcript + ".model.json")
                    .put("fileBytes", bytes.length).put("fileSha256", CodingTranscriptMaterialSupply.sha(bytes))
                    .put("canonicalSha256", adapter.canonicalSha256(model)).put("modelId", model.path("modelId").asText())
                    .put("modelVersion", model.path("modelVersion").asText());
            byte[] manifestBytes = mapper.writeValueAsBytes(manifest); Files.write(root.resolve("manifest.json"), manifestBytes);
            supply = new CodingTranscriptMaterialSupply(mapper, root, CodingTranscriptMaterialSupply.sha(manifestBytes), (c, s, n) -> {
                if (!"1".equals(c) || s < 1 || s - 1 + n > genome.length()) throw new IOException("Synthetic reference interval invalid");
                return new CodingTranscriptMaterialSupply.ReferenceSpan(genome.substring(s - 1, s - 1 + n), mapper.createObjectNode()
                        .put("sourceKind", "SYNTHETIC_TINY_REFERENCE").put("expectedSha256", referenceSha));
            });
        }
        CodingTranscriptMaterialSupply.Material material() { return supply.resolveStructure(context, candidate); }
        Bp1TruncatingConsequence.Observation observe() {
            lastMaterial = material(); return Bp1TruncatingConsequence.observe(mapper, context, candidate, lastMaterial);
        }
    }

    @ParameterizedTest @CsvSource({"STOP,1,147", "STOP,-1,147", "DEL,1,146", "DEL,-1,146", "INS,1,148", "INS,-1,148"})
    void independentLiteralVectorsConfirmOnlySequenceTruncationOnBothStrands(String kind, int strand, int distance) throws Exception {
        var f = new Fixture(temporary, kind, strand); var observation = f.observe(); var out = observation.snapshot();
        assertTrue(observation.ready(), out::toPrettyString); assertTrue(observation.sequenceConfirmed());
        assertEquals("TRUNCATION_CANDIDATE", out.path("status").asText()); assertEquals(181, out.path("ptcStart").asInt());
        assertEquals(183, out.path("ptcEnd").asInt()); assertEquals(distance, out.at("/junctionGeometry/distanceToLastJunction").asInt());
        assertEquals("NONE", out.path("appliedStrength").asText()); assertFalse(out.path("lossOfFunctionEstablished").asBoolean(true));
        assertFalse(out.path("bp1Adoption").asBoolean(true)); assertFalse(out.path("createsScientificApproval").asBoolean(true));
        assertEquals("NOT_ASSESSED", out.path("nmdPrediction").asText()); assertFalse(out.at("/junctionGeometry/thresholdApplied").asBoolean(true));
        String expected = switch (kind) {
            case "STOP" -> "7427e9c150d62a5fa160e9ceab8cd575cb774198fd03eae25afb713ddf48b96a";
            case "DEL" -> "719f8273d1555dc26dd37c1a5a2e9ec11dccab15681f536c478e6915323a07b9";
            default -> "0a51f16b6e57cac0a133def9648240e59836e1908e3b1d45dcd5775f2b4bf8a8";
        };
        assertEquals(expected, out.at("/binding/alteredCdsSha256").asText());
        assertEquals(f.adapter.canonicalSha256(f.context), out.path("contextCanonicalSha256").asText());
        assertEquals(f.context.at("/variant/evidenceQueryAllele"), out.path("queryAllele"));
        assertEquals(f.adapter.canonicalSha256(f.lastMaterial.snapshot()), out.path("structureMaterialSha256").asText());
        assertEquals(f.adapter.canonicalSha256(f.model), out.path("nativeModelSha256").asText());
        if ("INS".equals(kind)) assertTrue(out.path("ptcReferenceStart").isNull());
    }

    @ParameterizedTest @ValueSource(strings = {"source", "status", "version", "timestamp", "txVersion", "alias", "protein", "hgvsc", "codons", "amino", "position", "exon", "splice", "start", "gene", "missingProtein"})
    void wrongOrMissingAnnotationNeverUsesItsSoLabelAsProof(String fault) throws Exception {
        var f = new Fixture(temporary, "STOP", 1); var material = f.material(); assertTrue(material.ready());
        switch (fault) {
            case "source" -> f.annotation.put("source", "VEP_SYNTHETIC_UNQUALIFIED");
            case "status" -> f.annotation.put("status", "PENDING");
            case "version" -> f.annotation.put("version", "115.0");
            case "timestamp" -> f.annotation.remove("timestamp");
            case "txVersion" -> f.annotation.put("transcriptAccession", "ENST000001.5");
            case "alias" -> f.annotation.put("transcriptId", "ENST000002");
            case "protein" -> f.annotation.put("hgvsProtein", "NP_000001.2:p.Arg61Ter");
            case "hgvsc" -> f.annotation.put("hgvsCoding", "ENST000001.4:c.182G>T");
            case "codons" -> f.annotation.put("codons", "CGA/TAA");
            case "amino" -> f.annotation.put("aminoAcids", "R/X");
            case "position" -> f.annotation.put("proteinPosition", "60-61");
            case "exon" -> f.annotation.put("exon", "2/3");
            case "splice" -> f.annotation.put("consequence", "stop_gained&splice_region_variant");
            case "start" -> f.annotation.put("consequence", "start_lost&stop_gained");
            case "gene" -> f.annotation.put("geneSymbol", "OTHER");
            case "missingProtein" -> f.annotation.remove("hgvsProtein");
            default -> throw new AssertionError(fault);
        }
        f.applyAnnotation(); assertFalse(Bp1TruncatingConsequence.observe(f.mapper, f.context, f.candidate, material).ready());
    }

    @ParameterizedTest @ValueSource(strings = {"contextId", "gene", "referencePin", "candidate", "genomicRef", "variantType"})
    void currentContextAlleleAndSourceMustBeBoundAgainWhenStructureIsReused(String fault) throws Exception {
        var f = new Fixture(temporary, "STOP", 1); var material = f.material(); assertTrue(material.ready());
        switch (fault) {
            case "contextId" -> f.context.put("contextId", "WRONG");
            case "gene" -> { ((ObjectNode) f.context.path("gene")).put("symbol", "OTHER"); f.canonicalizeContext(); }
            case "referencePin" -> { ((ObjectNode) f.context.at("/variant/normalization")).put("referenceSequenceSha256", "0".repeat(64)); f.canonicalizeContext(); }
            case "candidate" -> f.candidate = new VariantInterpretation("WRONG_CANDIDATE", new VariantQcObservation(
                    new NormalizedVariantKey("GRCh38", "1", 1000, "A", "T"), "synthetic", "0/1", null, null, null, null, "PASS", "SNV", null), List.of(), List.of());
            case "genomicRef" -> { f.setAllele(f.genomic(181), "A", "T", "SNV"); f.applyAnnotation(); }
            case "variantType" -> { ((ObjectNode) f.context.path("variant")).put("variantType", "MNV"); f.canonicalizeContext(); }
            default -> throw new AssertionError(fault);
        }
        assertFalse(Bp1TruncatingConsequence.observe(f.mapper, f.context, f.candidate, material).ready());
    }

    @Test void sameCompleteStructureCanRecomputeAnotherAlleleWithoutReusingTheOldConclusion() throws Exception {
        var f = new Fixture(temporary, "STOP", 1); var material = f.material();
        var first = Bp1TruncatingConsequence.observe(f.mapper, f.context, f.candidate, material).snapshot();
        f.setAllele(f.genomic(184), "A", "T", "SNV");
        f.annotation.put("hgvsCoding", "ENST000001.4:c.184A>T").put("hgvsProtein", "NP_000001.1:p.Lys62Ter")
                .put("proteinPosition", "62").put("aminoAcids", "K/*").put("codons", "AAA/TAA"); f.applyAnnotation();
        var next = Bp1TruncatingConsequence.observe(f.mapper, f.context, f.candidate, material);
        assertTrue(next.ready(), next.snapshot()::toPrettyString); assertEquals(184, next.snapshot().path("ptcStart").asInt());
        assertNotEquals(first.path("contextCanonicalSha256"), next.snapshot().path("contextCanonicalSha256"));
        assertNotEquals(first.at("/binding/alteredCdsSha256"), next.snapshot().at("/binding/alteredCdsSha256"));
    }

    @ParameterizedTest @ValueSource(ints = {50, 51, 55})
    void rawDistancesDoNotSelectANmdThreshold(int distance) throws Exception {
        var f = new Fixture(temporary, "STOP", 1); f.exons(210, 183 + distance, 363); f.rebuild();
        var out = f.observe().snapshot(); assertEquals("TRUNCATION_CANDIDATE", out.path("status").asText(), out::toPrettyString);
        assertEquals(distance, out.at("/junctionGeometry/distanceToLastJunction").asInt());
        assertEquals("NOT_ASSESSED", out.path("nmdPrediction").asText()); assertFalse(out.at("/junctionGeometry/thresholdApplied").asBoolean(true));
    }

    @Test void fullModelExonsDeriveTheJunctionWhenTheOptionalTopLevelFieldIsAbsent() throws Exception {
        var f = new Fixture(temporary, "STOP", 1); f.model.remove("lastJunctionCdsPosition"); f.writeSupply();
        var out = f.observe().snapshot(); assertEquals("TRUNCATION_CANDIDATE", out.path("status").asText(), out::toPrettyString);
        assertEquals(330, out.at("/junctionGeometry/referenceLastTranscriptJunctionCdsBoundary0").asInt());
    }

    @Test void inconsistentPresentJunctionIsNotSilentlyReplaced() throws Exception {
        var f = new Fixture(temporary, "STOP", 1); f.model.put("lastJunctionCdsPosition", 329); f.writeSupply();
        assertFalse(f.observe().ready());
    }

    @Test void singleExonDoesNotInventAJunctionOrNmdPrediction() throws Exception {
        var f = new Fixture(temporary, "STOP", 1); f.exons(363); f.rebuild(); var observation = f.observe();
        assertTrue(observation.ready(), observation.snapshot()::toPrettyString);
        assertEquals("SINGLE_EXON", observation.snapshot().at("/junctionGeometry/kind").asText());
        assertTrue(observation.snapshot().at("/junctionGeometry/distanceToLastJunction").isNull());
    }

    @Test void fullUtrOnlyJunctionIsNotReplacedByTheLastCodingJunction() throws Exception {
        var f = new Fixture(temporary, "STOP", 1); f.tail = "AAA".repeat(10);
        f.model.withArray("exons").addObject().put("ordinal", 4).put("genomicStart", 4000).put("genomicEnd", 4029)
                .put("cdsStart", 364).put("cdsEnd", 393); f.model.put("lastJunctionCdsPosition", 363); f.rebuild();
        var observation = f.observe(); assertTrue(observation.ready(), observation.snapshot()::toPrettyString);
        var geometry = observation.snapshot().path("junctionGeometry");
        assertEquals("LAST_JUNCTION_IN_3_PRIME_UTR", geometry.path("kind").asText());
        assertEquals(363, geometry.path("referenceLastTranscriptJunctionCdsBoundary0").asInt());
        assertTrue(geometry.path("alteredLastTranscriptJunctionCdsBoundary0").isNull()); assertTrue(geometry.path("distanceToLastJunction").isNull());
        assertEquals("[210,330]", geometry.path("alteredCodingJunctions0").toString());
    }

    @Test void identicalAlteredCdsCannotMoveAnHgvsDeletionAcrossAnExonBoundary() throws Exception {
        var f = new Fixture(temporary, "DEL", 1); f.exons(90, 330, 363); f.rebuild();
        f.annotation.put("hgvsCoding", "ENST000001.4:c.4del"); f.applyAnnotation();
        var out = f.observe().snapshot(); assertEquals("UNAVAILABLE", out.path("status").asText());
        assertEquals("BP1_TRUNCATING_CODING_EXON_BOUNDARY_UNSUPPORTED", out.path("reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"ENST000001.4:c.[181C>T;182G>A]", "ENST000001.4:c.[181C>T];[182G>A]"})
    void multiEventHgvsCannotMintAPhasedOrUnphasedPhysicalAllele(String coding) throws Exception {
        var f = new Fixture(temporary, "STOP", 1); f.annotation.put("hgvsCoding", coding); f.applyAnnotation();
        assertFalse(f.observe().ready());
    }

    @Test void noStopFrameshiftRemainsUnresolvedRatherThanAssumingLossOfFunction() throws Exception {
        var f = new Fixture(temporary, "INS", 1); f.setAllele(f.genomic(180), "A", "AC", "SMALL_INSERTION");
        f.annotation.put("hgvsCoding", "ENST000001.4:c.180_181insC").put("hgvsProtein", "NP_000001.1:p.Glu61ArgfsTer?")
                .put("codons", "-/C"); f.applyAnnotation();
        var out = f.observe().snapshot(); assertEquals("BP1_TRUNCATING_TERMINATION_UNRESOLVED", out.path("reasonCode").asText());
        assertFalse(out.path("lossOfFunctionEstablished").asBoolean(true));
    }

    @Test void duplicateSelectedAnnotationsAreNotCollapsedIntoPermission() throws Exception {
        var f = new Fixture(temporary, "STOP", 1); var material = f.material(); var tx = f.candidate.getTranscriptAnnotations().get(0);
        f.candidate.applyTranscriptAnnotations(List.of(tx, tx));
        assertFalse(Bp1TruncatingConsequence.observe(f.mapper, f.context, f.candidate, material).ready());
    }

    @Test void observationCopiesItsProofAndCannotBeMintedFromItsJson() throws Exception {
        var f = new Fixture(temporary, "STOP", 1); var observation = f.observe(); assertTrue(observation.ready());
        var before = observation.snapshot(); observation.snapshot().removeAll(); f.lastMaterial.snapshot().removeAll();
        java.util.Objects.requireNonNull(f.lastMaterial.model()).removeAll(); f.context.removeAll(); f.model.removeAll();
        assertEquals(before, observation.snapshot());
        for (var constructor : Bp1TruncatingConsequence.Observation.class.getDeclaredConstructors()) assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        assertThrows(com.fasterxml.jackson.databind.exc.InvalidDefinitionException.class,
                () -> f.mapper.treeToValue(before, Bp1TruncatingConsequence.Observation.class));
        var forged = f.mapper.createObjectNode().put("creation", "OBSERVED");
        forged.set("value", before.deepCopy().put("sequenceTruncationCandidate", true));
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> f.mapper.treeToValue(forged, Bp1TruncatingConsequence.Observation.class));
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                () -> new ObjectMapper().treeToValue(forged, Bp1TruncatingConsequence.Observation.class));
    }

    @Test void completeFivePrimeUtrKeepsRealExonOrdinalsAndDoesNotChangeCdsDistance() throws Exception {
        var f = new Fixture(temporary, "STOP", 1); f.prefix = "AAA".repeat(10);
        var old = f.model.path("exons").deepCopy(); var exons = f.model.putArray("exons");
        exons.addObject().put("ordinal", 1).put("genomicStart", 500).put("genomicEnd", 529).put("cdsStart", -29).put("cdsEnd", 0);
        for (var exon : old) exons.add(((ObjectNode) exon).deepCopy().put("ordinal", exon.path("ordinal").asInt() + 1));
        f.rebuild(); var observation = f.observe(); assertTrue(observation.ready(), observation.snapshot()::toPrettyString);
        assertEquals(2, observation.snapshot().path("exonOrdinal").asInt()); assertEquals(4, observation.snapshot().path("exonTotal").asInt());
        assertEquals(147, observation.snapshot().at("/junctionGeometry/distanceToLastJunction").asInt());
    }

    @ParameterizedTest @CsvSource({"AC,GT,MNV", "A,ATTT,SMALL_INSERTION"})
    void equalLengthMnvAndInFrameEditsDoNotBecomeFrameshift(String ref, String alt, String type) throws Exception {
        var f = new Fixture(temporary, "STOP", 1); var material = f.material();
        f.setAllele(f.genomic(181), ref, alt, type); f.annotation.put("consequence", "frameshift_variant"); f.applyAnnotation();
        assertFalse(Bp1TruncatingConsequence.observe(f.mapper, f.context, f.candidate, material).ready());
    }

    @Test void aStopLabelDoesNotAuthorizeASenseCodonOrAnEditOfTheNativeStop() throws Exception {
        var f = new Fixture(temporary, "STOP", 1); var material = f.material();
        f.setAllele(f.genomic(181), "C", "G", "SNV"); f.annotation.put("hgvsCoding", "ENST000001.4:c.181C>G"); f.applyAnnotation();
        assertFalse(Bp1TruncatingConsequence.observe(f.mapper, f.context, f.candidate, material).ready());
        f.setAllele(f.genomic(361), "T", "C", "SNV"); f.annotation.put("hgvsCoding", "ENST000001.4:c.361T>C"); f.applyAnnotation();
        var out = Bp1TruncatingConsequence.observe(f.mapper, f.context, f.candidate, material).snapshot();
        assertEquals("BP1_TRUNCATING_NATIVE_STOP_REGION_UNSUPPORTED", out.path("reasonCode").asText());
    }

    @Test void startCodonEditingAndGenomicExonBoundaryRemainOutsideTheCodingSlice() throws Exception {
        var f = new Fixture(temporary, "INS", 1); var material = f.material();
        f.setAllele(f.genomic(2), "T", "TA", "SMALL_INSERTION");
        f.annotation.put("hgvsCoding", "ENST000001.4:c.2_3insA"); f.applyAnnotation();
        var first = Bp1TruncatingConsequence.observe(f.mapper, f.context, f.candidate, material).snapshot();
        assertEquals("BP1_TRUNCATING_START_REGION_UNSUPPORTED", first.path("reasonCode").asText());
        f.setAllele(f.genomic(210), "AA", "A", "SMALL_DELETION"); f.applyAnnotation();
        assertFalse(Bp1TruncatingConsequence.observe(f.mapper, f.context, f.candidate, material).ready());
    }

    @Test void missingOrTamperedActualSourceMaterialCannotBeReplacedByJsonLabels() throws Exception {
        var f = new Fixture(temporary, "STOP", 1);
        assertFalse(Bp1TruncatingConsequence.observe(f.mapper, f.context, f.candidate, null).ready());
        Files.writeString(f.root.resolve("ENST000001.4.model.json"), "{}");
        var material = f.material(); assertFalse(material.ready());
        assertFalse(Bp1TruncatingConsequence.observe(f.mapper, f.context, f.candidate, material).ready());
    }
}
