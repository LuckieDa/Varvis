package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic catalog controls; actual source compilation is a separate, explicitly pinned layer. */
class Ba1ConditionalCatalogTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PopulationFrequencyRuntimeTest facts = new PopulationFrequencyRuntimeTest();

    @Test void completeOrIsSelectedOnlyForAnExplicitConditionalConsumer() throws Exception {
        var doc=snapshot(); var catalog=new Ba1SpecificationCatalog(mapper,doc);
        var selected=catalog.select(mapping(),facts.diseaseContext("MYH7"));
        assertEquals("CONDITIONAL_SELECTED",selected.path("status").asText(),selected.toPrettyString());
        assertEquals("BA1_CONDITIONAL_EVALUATOR_REQUIRED",selected.path("reasonCode").asText());
        assertEquals(Ba1ConditionalFrequencyRule.VERSION,selected.at("/policy/conditionRuntimeContract").asText());
        assertEquals(doc.at("/entries/0/ba1"),selected.at("/policy/clause"));
        assertEquals("10",selected.at("/policy/clause/expression/individualCount/value").asText());
        assertEquals(Ba1ConditionalFrequencyRule.VERSION,catalog.identity().path("conditionalFrequencyContract").asText());
    }

    @Test void diseaseModeTranscriptAndConflictingScopeStillPrecedeOrSelection() throws Exception {
        var doc=snapshot();var catalog=new Ba1SpecificationCatalog(mapper,doc);var context=facts.diseaseContext("MYH7");
        assertEquals("BA1_DISEASE_CONTEXT_REQUIRED",catalog.select(mapping(),null).path("reasonCode").asText());
        for (String path:List.of("/disease/mondoId","/moi")) {
            var wrong=context.deepCopy(); if(path.startsWith("/disease"))wrong.withObject("/disease").put("mondoId","MONDO:9999999");else wrong.put("moi","AUTOSOMAL_RECESSIVE");
            assertEquals("BA1_DISEASE_SCOPE_UNRESOLVED",catalog.select(mapping(),wrong).path("reasonCode").asText());
        }
        doc.withObject("/entries/0/scope").put("transcriptScope","EXACT").putArray("transcripts").add("NM_000257.4");
        assertEquals("BA1_TRANSCRIPT_CONTEXT_REQUIRED",new Ba1SpecificationCatalog(mapper,doc).select(mapping(),context).path("reasonCode").asText());
    }

    @Test void aTamperedOrUnknownConditionNeverBecomesScalarPolicy() throws Exception {
        for (String fault:List.of("COUNT","AND","EXTRA")) {
            var doc=snapshot();var clause=doc.withObject("/entries/0/ba1");
            if("COUNT".equals(fault))clause.withObject("/expression/individualCount").put("value","11");
            else if("AND".equals(fault))clause.withObject("/expression").put("operator","ALL");
            else clause.put("patientConfirmedCarrier",true);
            var selection=new Ba1SpecificationCatalog(mapper,doc).select(mapping(),facts.diseaseContext("MYH7"));
            assertEquals("BLOCKED",selection.path("status").asText());assertFalse(selection.has("policy"));
        }
    }

    @Test void explicitConditionalConsumerPreservesUnknownCountWhenFrequencySatisfiesTheOr() throws Exception {
        var context=facts.diseaseContext("MYH7");var selection=new Ba1SpecificationCatalog(mapper,snapshot()).select(mapping(),context);
        var profile=GnomadPopulationInputProfile.loadExecutableV2();var adapter=new RuntimeEvidenceV2Adapter(mapper);
        var observation=facts.observation(context,facts.jointInfo("0.003","10000","0.1"),"PASS");
        var wiring=new RuntimeEvidenceV2ProviderWiring(mapper,adapter);
        var carrier=wiring.reparsePopulationObservation(context,observation,"a".repeat(64),GnomadPopulationInputProfile.loadBusinessPm2Supporting(),profile).carrier();
        wiring.preserveBa1SubtypeFilters(carrier,observation.path("rawPopulationRecord").asText());
        var result=new GnomadPopulationCriterionEvaluator(mapper).evaluateCatalogBa1(context.at("/variant/alleleId").asText(),carrier,profile,selection);
        assertEquals("MET",result.at("/evaluationResult/status").asText(),result.toPrettyString());
        assertEquals("UNKNOWN",result.at("/ruleConditionEvaluation/countTruth").asText());
        assertEquals("BA1_COUNT_SOURCE_SCOPE_UNQUALIFIED",result.at("/ruleConditionEvaluation/countReasonCode").asText());
        assertEquals(GnomadPopulationCriterionEvaluator.BA1_CONDITIONAL_EVALUATOR_VERSION,
                result.at("/deterministicTrace/evaluatorVersion").asText());
    }

    @Test @EnabledIfSystemProperty(named="varvis.t03.ba1.conditional.compiled",matches=".+")
    void actualPythonCompiledOriginalHasTheSameStrictJavaRepresentation() throws Exception {
        var path=Path.of(System.getProperty("varvis.t03.ba1.conditional.compiled"));
        var compiled=mapper.readTree(Files.readAllBytes(path));
        assertNull(Ba1ConditionalFrequencyRule.issue(compiled));
        assertEquals("CONDITIONAL",compiled.path("disposition").asText());
        assertEquals("10",compiled.at("/expression/individualCount/value").asText());
    }

    ObjectNode mapping(){var m=mapper.createObjectNode().put("status","RESOLVED");m.putArray("hgncIds").add("HGNC:7577");return m;}
    ObjectNode snapshot() throws Exception {
        var doc=mapper.createObjectNode().put("schemaVersion",Ba1SpecificationCatalog.SCHEMA);
        var meta=doc.putObject("metadata").put("snapshotId","SYNTHETIC_T03_CONDITIONAL_NOT_CLINICAL")
                .put("sourceBase","https://example.test/SYNTHETIC/catalog").put("status","COMPLETE").put("publicationScope","Released")
                .put("declaredEntityCount",0).put("enumeratedEntityCount",0).put("entryCount",1).put("selectedReleasedCount",1)
                .put("compilerVersion","SYNTHETIC").put("captureManifestSha256","a".repeat(64));
        meta.putArray("pages").addObject().put("page",0).put("returnedCount",0).put("rawSha256","a".repeat(64));
        meta.putObject("batchReview").put("status","REVIEWED").put("reviewId","SYNTHETIC_ONLY_NOT_APPROVAL")
                .put("compilerVersion","SYNTHETIC").put("sourceCaptureSha256","a".repeat(64));
        var entry=doc.putArray("entries").addObject().put("specificationId","SYNTHETIC_GENERIC_OR_SCOPE")
                .put("version","1").put("publicationStatus","Released").put("rawSha256","a".repeat(64));
        var scope=entry.putObject("scope").put("status","RESOLVED").put("transcriptScope","UNRESTRICTED");
        scope.putArray("hgncIds").add("HGNC:7577");scope.putArray("mondoIds").add("MONDO:0005045");scope.putArray("moi").add("AUTOSOMAL_DOMINANT");
        entry.set("ba1",Ba1ConditionalFrequencyRuleTest.clause(mapper));return doc;
    }
}
