package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Fictional source grammar only. No real expert clause is expanded or scientific approval created. */
class ProteinLengthFactoryPublishedScopeTest {
    @TempDir Path root;

    @Test void technicalFactoryCannotBroadenAnExistingSimpleOnlyPublishedRuleOrFallBack() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var fixture = new ProteinLengthChangeMaterialSupplyTest();
        fixture.root = Files.createDirectory(root.resolve("material"));
        fixture.setup(1, 106, "CCT", "GCTGAA", "7_9delinsGCTGAA", "Pro3delinsAlaGlu");
        fixture.data.context.withObject("variant").put("variantType", "SMALL_DELINS");
        fixture.data.context.put("contextId", fixture.data.adapter.canonicalContextId(fixture.data.context));
        // Existing support writes invented source files; the evaluated facts use the real new factory below.
        GeneralPm4TestSupport.create(mapper, fixture.root, fixture.data.model, fixture::read, false, false, true);
        var material = ProteinLengthMaterialFactory.reviewedSmallAlleles(mapper, fixture.root,
                sha(fixture.root.resolve("manifest.json")), fixture::read, null);
        var source = new PublishedBp3RuntimeTest(); source.root = Files.createDirectory(root.resolve("published"));
        var document = source.runtimeDocument(source.document(), source.fixture());
        var uniprot = new UniProtProteinRegionSupply(mapper, fixture.root.resolve("uniprot"),
                sha(fixture.root.resolve("uniprot/manifest.json")));
        var facts = new ProteinLengthRegionFactSupply(mapper, material, uniprot, null, null);
        var runtime = new GeneralBp3Runtime(mapper, facts, new Bp3ProteinRegionAssessment(mapper),
                PredictionSpecializationGuard.load(mapper, fixture.root.resolve("catalog.json"), sha(fixture.root.resolve("catalog.json"))),
                source.source.load(document));
        var result = runtime.evaluate(fixture.data.context, fixture.data.candidate, mapper.createObjectNode());
        assertEquals("READY", result.at("/sequenceMaterial/status").asText(), result::toPrettyString);
        assertEquals("READY", result.at("/knowledgeSelection/status").asText(), result::toPrettyString);
        assertEquals("SINGLE_SIMPLE_INFRAME_COMPLETE_EQUIVALENT_ENVELOPE",
                result.at("/knowledgeSelection/record/interpretation/supportedVariantScope").asText());
        assertEquals("NOT_EVALUATED", result.path("status").asText());
        assertEquals("BP3_PUBLISHED_COMPLEX_ALLELE_SCOPE_UNSUPPORTED", result.path("reasonCode").asText());
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
        assertFalse(result.has("proteinAnnotations"), "A scope mismatch cannot fall back to generic region search");
    }

    private static String sha(Path file) throws Exception {
        return CodingTranscriptMaterialSupply.sha(Files.readAllBytes(file));
    }
}
