package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic stale derived-catalog scope: raw source facts must win even when
 * byte-pinned compiled scope claims the desired match. No scientific adoption. */
class Pvs1StartLossReferenceSpecificationScopeTest {
    @TempDir Path directory;
    final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode context() {
        var c = mapper.createObjectNode().put("moi", "AUTOSOMAL_RECESSIVE");
        c.putObject("gene").put("hgncId", "HGNC:1").put("symbol", "GENE1"); c.putObject("disease").put("mondoId", "MONDO:0000001");
        c.putObject("transcript").put("accession", "NM_000001").put("version", 1); return c;
    }
    private ObjectNode summary() {
        return mapper.createObjectNode().put("gene", "GENE1").put("mondoId", "MONDO:0000001")
                .put("moi", "Autosomal recessive inheritance").put("uuid", "synthetic-existing-source");
    }
    private ObjectNode method() {
        return mapper.createObjectNode().put("@id", "https://cspec.genome.network/cspec/SequenceVariantInterpretation/id/123")
                .put("version", "1.0.0");
    }
    private PredictionSpecializationGuard guard(String change) throws Exception {
        var entity = mapper.createObjectNode().put("entId", "GN001").put("ldhId", "123");
        var content = entity.putObject("entContent").put("version", "1.0"); content.putArray("states").addObject().put("current", true).put("name", "Released");
        var ld = entity.putObject("ld"); ld.putArray("CriteriaCode");
        var identity = ld.putArray("Gene").addObject().putObject("entContent").putObject("HGNC").put("hgnc_id", "HGNC:1").put("symbol", "GENE1");
        var gene = ld.putArray("RuleSet").addObject().putObject("entContent").putArray("genes").addObject()
                .put("gene", "GENE1").put("preferredTranscript", "NM_000001.1");
        var disease = gene.putArray("diseases").addObject().put("preferredMondoId", "MONDO:0000001").put("preferredModeOfInheritance", "Autosomal recessive inheritance");
        switch (change) {
            case "gene" -> identity.put("hgnc_id", "HGNC:2");
            case "disease" -> disease.put("preferredMondoId", "MONDO:0000002");
            case "transcript" -> gene.put("preferredTranscript", "NM_000001.2");
            case "moi" -> disease.put("preferredModeOfInheritance", "Autosomal dominant inheritance");
            case "missing" -> disease.remove("preferredModeOfInheritance");
            case "empty" -> disease.put("preferredModeOfInheritance", "");
            default -> { }
        }
        var raw = mapper.createObjectNode(); raw.set("data", entity); byte[] bytes = mapper.writeValueAsBytes(raw);
        Files.write(directory.resolve("raw.json"), bytes);
        var catalog = mapper.createObjectNode().put("schemaVersion", Ba1SpecificationCatalog.SCHEMA);
        catalog.putObject("metadata").put("status", "COMPLETE").put("entryCount", 1);
        var entry = catalog.putArray("entries").addObject().put("specificationId", "GN001").put("version", "1.0");
        var scope = entry.putObject("scope").put("status", "RESOLVED").put("transcriptScope", "EXACT");
        scope.putArray("hgncIds").add("HGNC:1"); scope.putArray("mondoIds").add("MONDO:0000001");
        scope.putArray("moi").add("AUTOSOMAL_RECESSIVE"); scope.putArray("transcripts").add("NM_000001.1");
        entry.putObject("source").put("rawRef", "raw.json").put("rawSha256", PredictionCalibration.sha256(bytes)).put("jsonPointer", "/data");
        byte[] catalogBytes = mapper.writeValueAsBytes(catalog); Path path = directory.resolve("catalog.json"); Files.write(path, catalogBytes);
        return PredictionSpecializationGuard.load(mapper, path, PredictionCalibration.sha256(catalogBytes), directory);
    }
    @ParameterizedTest @ValueSource(strings = {"gene", "disease", "transcript", "moi", "empty"})
    void staleCompiledMatchCannotReplaceContradictoryNativeScope(String change) throws Exception {
        var guard = guard(change);
        assertNotNull(guard.referenceSpecification(context(), method()), "Legacy inventory-only lookup is deliberately a weaker control");
        assertNull(guard.referenceSpecification(context(), method(), summary()), change);
    }
    @Test void matchingNativeScopeWorksAndMissingOnlyJoinPreservesOriginalBytes() throws Exception {
        assertNotNull(guard("same").referenceSpecification(context(), method(), summary()));
        var missing = guard("missing").referenceSpecification(context(), method(), summary()); assertNotNull(missing);
        missing = java.util.Objects.requireNonNull(missing);
        assertFalse(missing.entity().at("/ld/RuleSet/0/entContent/genes/0/diseases/0").has("preferredModeOfInheritance"));
        assertFalse(missing.source().at("/inheritanceScopeJoin/originalGuidelineModified").asBoolean());
        assertEquals("ORIGINAL_EXPERT_SUMMARY_EXPLICIT_INHERITANCE", missing.source().at("/inheritanceScopeJoin/basis").asText());
    }
}
