package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.service.CodingTranscriptReferenceReader;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger;
import org.nbscn.varvis.variantanalysis.service.LegacyContextProjection;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Existing public originals only. Constructed edits on real reference are not reported alleles,
 * observed RNA/protein, scientific evidence adoption, or fresh Top-N acceptance.
 * Opt in with -Da5.nativeRoot=<existing Code repository>; this test never downloads or writes resources.
 */
@EnabledIfSystemProperty(named = "a5.nativeRoot", matches = ".+")
class CodingTranscriptEditsNativeReplayTest {
    private static final String DISPATCH = "local/development/handoffs/acmg-six-framework-tasks-20261101/"
            + "parallel-branches-20261103/dispatch/a5/";
    private static final String RPGR = "local/development/handoffs/bp3-completion-20261016/stage-02/";
    private static final String MPZ = "local/development/handoffs/pm4-completion-20261015/";
    private static final String REFERENCE_SHA = "8d5e868ddc6d9600b82cd7b7e625c240a43f369f6f1a9f16b0c516eb781e7988";
    private static final String EXPECTED_SHA = "7408b9633fa998913ac995c910a95b6e6b7a12302f3368050ec18aac3d120385";
    private static final String RECEIPT_SHA = "96b7c59dc9afec9f60f968bbb6a362ea6436c80352c9479f969c8e96893ca23f";
    private static final String INDEX_SHA = "21d5d8db2fc4015d4f44ff2e6efbc5ca2f52a03352e08144b5177a7f2717c801";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    @Test void constructedRpgrDelinsTraversesTheOptedInMaterialConsumerWithSyntheticAnnotation() throws Exception {
        Path root = root();
        verifyOriginals(root);
        Path recipePath = root.resolve(DISPATCH + "native-verification/rpgr-material-replay-recipe.json");
        assertEquals("4ffe0ed3dacb89300dd1363c186dc417a2ec8f923631bc9f2235ccfd9c1a1e45", sha(Files.readAllBytes(recipePath)));
        JsonNode recipe = mapper.readTree(recipePath.toFile());
        assertEquals("CONSTRUCTED_ON_REAL_REFERENCE", recipe.path("scope").asText());
        assertEquals("SYNTHETIC_VEP_SHAPED_INPUT_NOT_ACTUAL_VEP_EXECUTION", recipe.path("annotationScope").asText());
        for (JsonNode source : recipe.path("sources")) {
            Path path = root.resolve(source.path("path").asText());
            assertEquals(source.path("bytes").asLong(), Files.size(path));
            assertEquals(source.path("rawSha256").asText(), sha(Files.readAllBytes(path)));
        }
        JsonNode saved = mapper.readTree(root.resolve(RPGR + "real-chain-04/result.json").toFile());
        ObjectNode original = object(saved.at("/candidates/0/attempts/0/context"));
        String originalHash = adapter.canonicalSha256(original);
        adapter.validateVersionedContext(original);
        assertEquals("SYNTHETIC", original.path("dataClass").asText());
        assertEquals("HGNC:10295", original.at("/gene/hgncId").asText());
        assertEquals("MONDO:0100437", original.at("/disease/mondoId").asText());
        assertEquals("X_LINKED_RECESSIVE", original.path("moi").asText());
        var reader = reference(root);
        assertEquals("CGA", reader.read("X", 38323520, 3).bases());
        var key = new NormalizedVariantKey("GRCh38", "X", 38323520, "CGA", "AGCAGC");
        ObjectNode constructed = original.deepCopy();
        ObjectNode variant = object(constructed.path("variant"));
        ObjectNode allele = object(recipe.path("queryAllele")).deepCopy();
        for (String field : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele"))
            variant.set(field, allele.deepCopy());
        variant.put("variantType", "SMALL_DELINS");
        if (variant.has("legacyCanonicalVariantId")) variant.put("legacyCanonicalVariantId", key.canonicalId());
        ObjectNode normalization = object(variant.path("normalization"));
        normalization.put("status", "UNCHANGED").put("method", "A5_CONSTRUCTED_ON_REAL_REFERENCE")
                .put("methodVersion", "1.0.0-test-only").put("refValidation", "MATCH");
        variant.put("alleleId", adapter.canonicalAlleleId(allele, normalization));
        constructed.put("contextId", adapter.canonicalVersionedContextId(constructed));
        adapter.validateVersionedContext(constructed);
        ObjectNode context = java.util.Objects.requireNonNull(ReflectionTestUtils.invokeMethod(
                LegacyContextProjection.class, "projectContext", mapper, constructed));
        adapter.validateCompleteContext(context);
        assertEquals(original.path("gene"), context.path("gene"));
        assertEquals(original.path("disease"), context.path("disease"));
        assertEquals(original.path("moi"), context.path("moi"));
        assertEquals(original.path("transcript"), context.path("transcript"));

        JsonNode originals = mapper.readTree(root.resolve(RPGR + "real-chain-01/formal-candidates.json").toFile());
        ObjectNode originalCandidate = object(originals.path(0));
        String originalCandidateHash = sha(mapper.writeValueAsBytes(originalCandidate));
        ObjectNode candidateProjection = candidateConstructorProjection(originalCandidate);
        var templates = java.util.stream.StreamSupport.stream(candidateProjection.path("transcriptAnnotations").spliterator(), false)
                .filter(tx -> tx.path("hgvsCoding").asText().startsWith("ENST00000645032.1:c.")).toList();
        assertEquals(1, templates.size());
        ObjectNode annotation = object(templates.get(0)).deepCopy();
        // This is a synthetic VEP-shaped test input, not a new VEP execution or a reported
        // allele. Required provider fields exercise the existing technical contract only.
        annotation.put("hgvsCoding", recipe.path("hgvsc").asText()).put("hgvsProtein", recipe.path("hgvsp").asText())
                .put("consequence", "inframe_insertion").put("exon", "2/15").put("proteinPosition", "11")
                .put("aminoAcids", "S/AA").put("codons", "tcg/GCTGCT").put("timestamp", "2026-11-03T00:00:00Z");
        var observation = new VariantQcObservation(key, "CONSTRUCTED_ON_REAL_REFERENCE", null, null, null,
                null, null, "PASS", "SMALL_DELINS", "CONSTRUCTED_ON_REAL_REFERENCE;SYNTHETIC_ANNOTATION_NOT_ACTUAL_VEP");
        var candidate = new VariantInterpretation(key.canonicalId(), observation, List.of(), List.of());
        candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
        Path models = root.resolve(RPGR + "models");
        String manifestSha = "310af7d76cb64ed872081d3d5f36b6b42ffd838b30c2cd7f38f5b2640c80da02";
        var material = new CodingTranscriptMaterialSupply(mapper, models, manifestSha, reader, null, true)
                .resolveProteinLengthChange(context, candidate);
        assertTrue(material.ready(), material.snapshot().toString());
        ObjectNode model = java.util.Objects.requireNonNull(material.model());
        assertEquals("7ca418e4db6a42bb46966e2487462539165728c7b161348d3193ab5f78ccde2b", adapter.canonicalSha256(model));
        assertTrue(model.at("/sequenceVerification/independentRnaVerification").asBoolean());
        JsonNode audit = material.snapshot();
        assertEquals("PREBUILT_LOCAL", audit.path("modelOrigin").asText());
        assertEquals(4733, audit.at("/verification/verifiedExonBases").asInt());
        assertEquals("8964af6fdabd812d79cc384bd306d539f3390b32f5e015ca905a245b4189c459",
                audit.at("/verification/fullSplicedTranscriptSha256").asText());
        JsonNode facts = audit.path("alleleAssociation");
        JsonNode expected = vector(root, "constructed-same-exon-delins");
        assertEquals(expected.path("alteredCdsSha256").asText(), facts.path("alteredCdsSha256").asText());
        assertEquals(3462, facts.path("alteredCdsLength").asInt());
        assertEquals(3, facts.path("netNucleotideChange").asInt());
        assertEquals(1, facts.path("netAminoAcidChange").asInt());
        assertEquals(expected.path("alteredProteinSha256").asText(), facts.path("alteredProteinSha256").asText());
        assertTrue(facts.path("hgvscSequenceEquivalent").asBoolean());
        assertTrue(facts.path("hgvspSequenceEquivalent").asBoolean());
        assertFalse(audit.path("scientificEvaluationPerformed").asBoolean(true));
        var legacy = new CodingTranscriptMaterialSupply(mapper, models, manifestSha, reader, null, false)
                .resolveProteinLengthChange(context, candidate);
        assertFalse(legacy.ready());
        assertEquals("PM4_COMPLEX_ALLELE_UNSUPPORTED", legacy.snapshot().path("reasonCode").asText());
        assertEquals(originalHash, adapter.canonicalSha256(original));
        assertEquals(originalCandidateHash, sha(mapper.writeValueAsBytes(originalCandidate)));
    }

    @Test void constructedCrossExonAlignmentKeepsNewBasesUnmappedAndDoesNotSupplyAnObservedSpliceOutcome() throws Exception {
        Path root = root();
        verifyOriginals(root);
        ObjectNode model = rpgrModel(root);
        JsonNode expected = vector(root, "constructed-cross-exon-delins");
        assertEquals("CONSTRUCTED_ON_REAL_REFERENCE", expected.path("scope").asText());
        assertFalse(expected.path("spliceOutcomeObserved").asBoolean(true));
        assertFalse(expected.path("scientificSpliceConsequenceClaim").asBoolean(true));
        var reference = CodingTranscriptEdits.bind(mapper, rpgrContext(model), model,
                adapter.canonicalSha256(model), sha(model.path("cdsSequence").asText()));
        // The owner is an explicit constructed alignment assignment. There is no real RNA
        // insertion witness and this is deliberately not an Origin.SPLICE_PROJECTION.
        String binding = sha("CONSTRUCTED_ON_REAL_REFERENCE:RPGR:c.27_30delinsGCT:insert-owner-exon-1");
        var edit = new CodingTranscriptEdits.Edit("constructed-cross-exon-delins", CodingTranscriptEdits.Origin.CODING_ALLELE,
                26, 30, "GCT", "CGAT", binding, binding, 1);
        var altered = CodingTranscriptEdits.apply(reference, List.of(edit));
        assertEquals(-1, altered.delta());
        assertEquals(expected.path("alteredCdsLength").asInt(), altered.alteredCds().length());
        assertEquals(expected.path("alteredCdsSha256").asText(), sha(altered.alteredCds()));
        assertEquals(26, altered.alteredToReferenceBase(26));
        for (int p = 27; p <= 29; p++) assertEquals(0, altered.alteredToReferenceBase(p));
        assertEquals(31, altered.alteredToReferenceBase(30));
        assertEquals(3459, altered.alteredToReferenceBase(3458));
        assertEquals(-1, altered.mapReferenceBoundary(28, CodingTranscriptEdits.Bias.BEFORE));
        assertEquals(29, altered.mapReferenceBoundary(30, CodingTranscriptEdits.Bias.AFTER));
        assertEquals(1, altered.exons().get(0).referenceOrdinal());
        assertEquals(29, altered.exons().get(0).alteredCdsEnd0());
        assertEquals(2, altered.exons().get(1).referenceOrdinal());
        assertEquals(29, altered.exons().get(1).alteredCdsStart0());
    }

    @Test void actualCrossBoundaryGenomicGeometryCannotSubstituteForAnExactSpliceResult() throws Exception {
        Path root = root();
        verifyOriginals(root);
        ObjectNode model = rpgrModel(root);
        ObjectNode context = rpgrContext(model);
        var reference = CodingTranscriptEdits.bind(mapper, context, model,
                adapter.canonicalSha256(model), sha(model.path("cdsSequence").asText()));
        var interval = CodingTranscriptEdits.genomicInterval(reference, 38323523, 38327341);
        assertTrue(interval.crossesExons());
        assertEquals(3815, interval.unmappedBases());
        assertEquals(2, interval.blocks().size());
        assertEquals(26, interval.blocks().get(0).referenceCdsStart0());
        assertEquals(28, interval.blocks().get(0).referenceCdsEnd0());
        assertEquals(28, interval.blocks().get(1).referenceCdsStart0());
        assertEquals(30, interval.blocks().get(1).referenceCdsEnd0());
        ObjectNode allele = object(context.at("/variant/evidenceQueryAllele"));
        String actualReference = reference(root).read("X", 38327339, 2).bases();
        allele.put("position", 38327339).put("reference", actualReference).put("alternate", "A");
        object(context.path("variant")).set("normalizedAllele", allele.deepCopy());
        var boundaryReference = CodingTranscriptEdits.bind(mapper, context, model,
                adapter.canonicalSha256(model), sha(model.path("cdsSequence").asText()));
        var rejected = assertThrows(CodingTranscriptEdits.Rejected.class,
                () -> CodingTranscriptEdits.projectAllele(boundaryReference, allele, adapter.canonicalSha256(allele)));
        assertEquals("CODING_EDIT_GENOMIC_BOUNDARY_REQUIRES_SPLICE_RESULT", rejected.reasonCode());
    }

    @Test void constructedSameExonMnvAndDelinsMatchPredeclaredIndependentRealReferenceExpectations() throws Exception {
        Path root = root();
        verifyOriginals(root);
        ObjectNode model = rpgrModel(root);
        // RefSeq plus the actual reference is verified separately above; these are deliberately
        // constructed alleles, not the two reported ClinVar alleles in the same source bundle.
        for (String id : List.of("constructed-same-exon-mnv", "constructed-same-exon-delins")) {
            JsonNode expected = vector(root, id);
            assertEquals("CONSTRUCTED_ON_REAL_REFERENCE", expected.path("scope").asText());
            ObjectNode context = rpgrContext(model);
            String inserted = expected.path("insertedSequence").asText();
            ObjectNode allele = object(context.at("/variant/evidenceQueryAllele"));
            allele.put("position", 38323520).put("reference", "CGA").put("alternate", reverseComplement(inserted));
            object(context.path("variant")).set("normalizedAllele", allele.deepCopy());
            var reference = CodingTranscriptEdits.bind(mapper, context, model,
                    adapter.canonicalSha256(model), sha(model.path("cdsSequence").asText()));
            var edit = CodingTranscriptEdits.projectAllele(reference, allele, adapter.canonicalSha256(allele));
            String hgvs = "ENST00000645032.1:c.31_33delins" + inserted;
            var annotation = CodingTranscriptEdits.codingHgvsEdit(reference, hgvs, sha(hgvs));
            assertTrue(CodingTranscriptEdits.sequenceEquivalent(reference, edit, annotation));
            assertEquals(30, edit.referenceEditStart0());
            assertEquals(33, edit.referenceEditEnd0());
            var altered = CodingTranscriptEdits.apply(reference, List.of(edit));
            assertEquals(expected.path("delta").asInt(), altered.delta());
            assertEquals(expected.path("alteredCdsLength").asInt(), altered.alteredCds().length());
            assertEquals(expected.path("alteredCdsSha256").asText(), sha(altered.alteredCds()));
        }
    }

    @Test void reportedRpgrDeletionAndDuplicationRemainSequenceEquivalentAcrossNegativeStrandRepresentations() throws Exception {
        Path root = root();
        verifyOriginals(root);
        ObjectNode model = rpgrModel(root);
        String[] ids = {"reported-rpgr-deletion", "reported-rpgr-duplication"};
        int[] positions = {38286146, 38286158};
        String[] refs = {"CTCCTCT", "C"};
        String[] alts = {"C", "CTCCCCTTCTCCATCCTCCCCT"};
        String[] hgvs = {"ENST00000645032.1:c.2847_2852del", "ENST00000645032.1:c.2820_2840dup"};
        for (int i = 0; i < ids.length; i++) {
            JsonNode expected = vector(root, ids[i]);
            assertEquals("REPORTED_PUBLIC_VARIANT", expected.path("scope").asText());
            ObjectNode context = rpgrContext(model);
            ObjectNode allele = object(context.at("/variant/evidenceQueryAllele"));
            allele.put("position", positions[i]).put("reference", refs[i]).put("alternate", alts[i]);
            object(context.path("variant")).set("normalizedAllele", allele.deepCopy());
            assertEquals(refs[i], reference(root).read("X", positions[i], refs[i].length()).bases());
            var reference = CodingTranscriptEdits.bind(mapper, context, model,
                    adapter.canonicalSha256(model), sha(model.path("cdsSequence").asText()));
            var edit = CodingTranscriptEdits.projectAllele(reference, allele, adapter.canonicalSha256(allele));
            var annotation = CodingTranscriptEdits.codingHgvsEdit(reference, hgvs[i], sha(hgvs[i]));
            assertTrue(CodingTranscriptEdits.sequenceEquivalent(reference, edit, annotation));
            var altered = CodingTranscriptEdits.apply(reference, List.of(edit));
            assertEquals(expected.path("delta").asInt(), altered.delta());
            assertEquals(expected.path("alteredCdsLength").asInt(), altered.alteredCds().length());
            assertEquals(expected.path("alteredCdsSha256").asText(), sha(altered.alteredCds()));
        }
    }

    @Test void pinnedRefseqAndCurrentQualifiedReferenceIndependentlyMatchTheExactRpgrModel() throws Exception {
        Path root = root();
        verifyOriginals(root);
        ObjectNode model = rpgrModel(root);
        ObjectNode context = rpgrContext(model);
        assertNull(CodingTranscriptMapping.modelIssue(model, context));
        assertEquals("native-coding-transcript-model-v1", model.path("schemaVersion").asText());
        assertEquals("1.1.0-independent-rna", model.path("modelVersion").asText());
        assertTrue(model.at("/sequenceVerification/independentRnaVerification").asBoolean());
        String genbank = Files.readString(root.resolve(RPGR + "sources/NM_001034853.2.gb"), StandardCharsets.US_ASCII);
        assertTrue(genbank.contains("VERSION     NM_001034853.2"));
        String rna = genbank.substring(genbank.indexOf("ORIGIN") + 6).split("//", 2)[0]
                .replaceAll("[^acgtACGT]", "").toUpperCase(java.util.Locale.ROOT);
        assertEquals(4733, rna.length());
        String current = spliced(model, reference(root));
        assertEquals(rna, current, "Independent RefSeq RNA and current genomic exon spans must agree in full");
        assertEquals(model.at("/sequenceVerification/fullSplicedTranscriptSha256").asText(), sha(current));
        int codingOffset = 1 - model.at("/exons/0/cdsStart").asInt();
        assertEquals(model.path("cdsSequence").asText(), current.substring(codingOffset, codingOffset + 3459));
        assertEquals("d06bd4a166b5565ad537cfc540126a86801303c96742024bd26436d378146791",
                sha(model.path("cdsSequence").asText()));
        assertEquals(1152, model.path("proteinLength").asInt());
        assertEquals(15, model.path("exons").size());
    }

    @Test void reportedMpzStopLossReplaysExistingMaterialPathAgainstCurrentReceiptBoundReference() throws Exception {
        Path root = root();
        verifyOriginals(root);
        JsonNode saved = mapper.readTree(root.resolve(MPZ + "real-chain-02/result.json").toFile());
        ObjectNode sourceContext = object(saved.at("/candidates/0/attempts/0/context")).deepCopy();
        String sourceContextSha = adapter.canonicalSha256(sourceContext);
        adapter.validateVersionedContext(sourceContext);
        var wrongContract = assertThrows(IllegalArgumentException.class, () -> adapter.validateCompleteContext(sourceContext));
        assertEquals("Invalid runtime evidence v2 carrier: evidence-context-v2 is required", wrongContract.getMessage());
        // Exercise the existing production projection without making its package-private
        // helper public. It validates the complete v3 source before producing v2 identity.
        ObjectNode context = java.util.Objects.requireNonNull(ReflectionTestUtils.invokeMethod(
                LegacyContextProjection.class, "projectContext", mapper, sourceContext));
        adapter.validateCompleteContext(context);
        ObjectNode restored = context.deepCopy();
        restored.set("schemaVersion", sourceContext.path("schemaVersion"));
        restored.set("contextId", sourceContext.path("contextId"));
        assertEquals(sourceContext, restored, "The production projection must preserve every semantic field");
        assertEquals(sourceContextSha, adapter.canonicalSha256(sourceContext), "Archived v3 source must remain unchanged");
        assertEquals(saved.at("/candidates/0/attempts/0/context"), sourceContext);
        JsonNode candidates = mapper.readTree(root.resolve(MPZ + "real-chain-02/formal-candidates.json").toFile());
        ObjectNode originalCandidate = object(candidates.path(0));
        ObjectNode projectedCandidate = candidateConstructorProjection(originalCandidate);
        assertTrue(originalCandidate.at("/transcriptAnnotations/0").has("hgvsp"), "Archived source must remain unchanged");
        ObjectNode conflict = originalCandidate.deepCopy();
        object(conflict.at("/transcriptAnnotations/0")).put("hgvsp", "CONFLICTING_GETTER_ALIAS");
        assertThrows(AssertionError.class, () -> candidateConstructorProjection(conflict));
        VariantInterpretation candidate = mapper.treeToValue(projectedCandidate, VariantInterpretation.class);
        assertNotNull(candidate);
        assertEquals("ENST00000533357", context.at("/transcript/accession").asText());
        assertEquals(5, context.at("/transcript/version").asInt());
        assertEquals("HGNC:7225", context.at("/gene/hgncId").asText());
        assertEquals("MONDO:0015626", context.at("/disease/mondoId").asText());
        assertEquals("AUTOSOMAL_DOMINANT", context.path("moi").asText());
        assertEquals(161305878, context.at("/variant/evidenceQueryAllele/position").asInt());
        var reader = reference(root);
        assertEquals("A", reader.read("1", 161305878, 1).bases());
        var index = new GencodeTranscriptIndex(mapper,
                root.resolve("local/resources/gencode/derived/v38-transcripts-v1"), INDEX_SHA);
        var supply = new CodingTranscriptMaterialSupply(mapper, null, "", reader, index);
        var result = supply.resolveStopLoss(context, candidate);
        assertTrue(result.ready(), result.snapshot().toString());
        ObjectNode model = java.util.Objects.requireNonNull(result.model(), "Ready material must retain its exact model");
        assertEquals(-1, model.path("strand").asInt());
        assertEquals(747, model.path("cdsLength").asInt());
        JsonNode expected = expected(root).path("mpz");
        assertEquals(expected.path("referenceCds").asText(), model.path("cdsSequence").asText());
        assertEquals(expected.path("fullSplicedTranscriptSha256").asText(), sha(spliced(model, reader)));
        JsonNode facts = result.snapshot().path("alleleAssociation");
        assertEquals(248, facts.path("referenceProteinLength").asInt());
        assertEquals(312, facts.path("alteredProteinLength").asInt());
        assertEquals(64, facts.path("netAminoAcidChange").asInt());
        assertEquals(937, facts.path("alteredStopCdsPosition").asInt());
        assertEquals(1141, facts.path("availableThreePrimeBases").asInt());
        assertEquals("TAG", facts.path("referenceStopCodon").asText());
        assertEquals("CAG", facts.path("alteredStopCodon").asText());
        assertEquals("5b9eb39d117d91e00482538f75693066f3b51a067acba7ad57e11ea440d5be89",
                facts.path("alteredProteinSha256").asText());
        assertEquals("8248e93a485835069b72784e81928fd828a489d6d97c5eca6f6d86e460166a92",
                facts.path("alteredCdsSha256").asText());
        assertFalse(result.snapshot().path("scientificEvaluationPerformed").asBoolean(true));
    }

    private Path root() throws Exception {
        String configured = System.getProperty("a5.nativeRoot");
        assertNotNull(configured);
        return Path.of(configured).toRealPath();
    }

    private JsonNode expected(Path root) throws Exception {
        Path path = root.resolve(DISPATCH + "native-replay-independent-expected.json");
        assertEquals(EXPECTED_SHA, sha(Files.readAllBytes(path)));
        return mapper.readTree(path.toFile());
    }

    private JsonNode vector(Path root, String id) throws Exception {
        for (JsonNode vector : expected(root).at("/rpgr/vectors")) {
            if (id.equals(vector.path("id").asText())) return vector;
        }
        throw new AssertionError("No predeclared independent vector: " + id);
    }

    private void verifyOriginals(Path root) throws Exception {
        Path path = root.resolve(DISPATCH + "native-replay-sources-receipt.json");
        assertEquals(RECEIPT_SHA, sha(Files.readAllBytes(path)));
        JsonNode receipt = mapper.readTree(path.toFile());
        for (JsonNode source : receipt.path("files")) {
            Path file = root.resolve(source.path("path").asText()).normalize();
            assertTrue(file.startsWith(root));
            assertEquals(source.path("bytes").asLong(), Files.size(file), file.toString());
            assertEquals(source.path("rawSha256").asText(), sha(Files.readAllBytes(file)), file.toString());
        }
        assertEquals(0, receipt.path("newDownloads").asInt(-1));
        assertEquals(0, receipt.path("newModelCalls").asInt(-1));
        assertFalse(expected(root).path("realComplexReportedAlleleAvailable").asBoolean(true));
    }

    private ObjectNode rpgrModel(Path root) throws Exception {
        ObjectNode model = object(mapper.readTree(root.resolve(RPGR + "models/ENST00000645032.1.model.json").toFile()));
        assertEquals("7ca418e4db6a42bb46966e2487462539165728c7b161348d3193ab5f78ccde2b", adapter.canonicalSha256(model));
        return model;
    }

    /** Published bytes contain getter aliases; project only after checking their exact agreement.
     * Keep strict unknown-property deserialization for every other field and preserve originals.
     */
    private ObjectNode candidateConstructorProjection(ObjectNode original) {
        ObjectNode copy = original.deepCopy();
        for (String field : List.of("transcriptAnnotations", "consequences")) {
            assertTrue(copy.path(field).isArray(), "Expected archived annotation list: " + field);
            for (JsonNode annotation : copy.path(field)) {
                ObjectNode row = object(annotation);
                for (String[] names : List.of(new String[]{"hgvsCoding", "hgvsc"}, new String[]{"hgvsProtein", "hgvsp"})) {
                    if (row.has(names[1])) {
                        assertTrue(row.has(names[0]), "Getter alias lacks its constructor field: " + names[1]);
                        assertEquals(row.path(names[0]), row.path(names[1]), "Conflicting archived HGVS alias: " + names[1]);
                        row.remove(names[1]);
                    }
                }
            }
        }
        return copy;
    }

    private ObjectNode rpgrContext(ObjectNode model) {
        ObjectNode context = mapper.createObjectNode();
        context.putObject("gene").put("hgncId", model.path("hgncId").asText()).put("symbol", model.path("geneSymbol").asText());
        context.putObject("transcript").put("accession", "ENST00000645032").put("version", 1);
        ObjectNode variant = context.putObject("variant");
        ObjectNode allele = variant.putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "X")
                .put("position", 38286146).put("reference", "CTCCTCT").put("alternate", "C");
        variant.set("normalizedAllele", allele.deepCopy());
        variant.putObject("normalization").put("referenceSequenceSha256", REFERENCE_SHA);
        variant.putObject("assemblyLineage").put("mappingStatus", "NATIVE");
        return context;
    }

    private CodingTranscriptReferenceReader reference(Path root) throws Exception {
        var properties = new VariantAnalysisProperties();
        var normalization = properties.getNormalization();
        normalization.setGrch38Reference(root.resolve("local/resources/reference/grch38.p14.primary-chr.fa").toString());
        normalization.setGrch38ReferenceSha256(REFERENCE_SHA);
        normalization.setGrch38ProfileId("grch38-primary-chr");
        normalization.setGrch38ContigAliasFile(root.resolve("varvis-server/config/grch38-canonical-to-chr-contigs.tsv").toString());
        var ledger = ExplicitContextSourceLedger.load(mapper, root.resolve("local/private/receipts/reference38-runtime.json"),
                "c7f794b9f7831c6130ea190729777473931cb4046993027aec52f16711a31a7b");
        return new CodingTranscriptReferenceReader(mapper, properties, root.resolve("local/resources"), ledger, "reference");
    }

    private String spliced(JsonNode model, CodingTranscriptReferenceReader reader) throws Exception {
        StringBuilder spliced = new StringBuilder();
        for (JsonNode exon : model.path("exons")) {
            int start = exon.path("genomicStart").asInt();
            int length = exon.path("genomicEnd").asInt() - start + 1;
            String span = reader.read(model.path("contig").asText(), start, length).bases();
            spliced.append(model.path("strand").asInt() == -1 ? reverseComplement(span) : span);
        }
        return spliced.toString();
    }

    private static String reverseComplement(String value) {
        StringBuilder result = new StringBuilder();
        for (int i = value.length() - 1; i >= 0; i--) result.append(switch (value.charAt(i)) {
            case 'A' -> 'T'; case 'C' -> 'G'; case 'G' -> 'C'; case 'T' -> 'A';
            default -> throw new IllegalArgumentException("Non-ACGT reference");
        });
        return result.toString();
    }

    private static ObjectNode object(JsonNode value) {
        assertInstanceOf(ObjectNode.class, value);
        return (ObjectNode) value;
    }

    private static String sha(String value) { return sha(value.getBytes(StandardCharsets.US_ASCII)); }
    private static String sha(byte[] value) { return CodingTranscriptMaterialSupply.sha(value); }
}
