package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/** Invented sources and approvals for engineering tests only. No real-protein approval is created. */
public final class GeneralPm4TestSupport {
    private GeneralPm4TestSupport() { }
    public static GeneralBp3Runtime bp3(ObjectMapper mapper, Path root, GeneralPm4Runtime pm4, ObjectNode context,
            org.nbscn.varvis.variantanalysis.domain.VariantInterpretation candidate, String function) throws Exception {
        var supplied = pm4.sharedFactSupply().resolve(context,candidate,true);
        if(!supplied.material().ready()) throw new AssertionError(supplied.snapshot().toPrettyString());
        var k = new Bp3ProteinRegionAssessmentTest(); k.root=root;k.context=context;k.model=supplied.material().model();
        k.annotations=(ObjectNode)supplied.snapshot().path("proteinAnnotations");k.extra=(ObjectNode)supplied.snapshot().path("combinedRegions");
        var assessment=new Bp3ProteinRegionAssessment(mapper);
        if(!"MISSING".equals(function)) {
            var doc=k.qualified();((ObjectNode)doc.at("/records/0/intervals/0")).put("start",1).put("end",6).put("functionalStatus",function);
            assessment=k.accepted(doc);
        }
        return new GeneralBp3Runtime(mapper,pm4.sharedFactSupply(),assessment,
                PredictionSpecializationGuard.load(mapper,root.resolve("catalog.json"),CodingTranscriptMaterialSupply.sha(Files.readAllBytes(root.resolve("catalog.json")))));
    }
    public static GeneralPm4Runtime create(ObjectMapper mapper, Path root, ObjectNode inputModel,
            CodingTranscriptMaterialSupply.ReferenceReader reader, boolean repeat, boolean negative, boolean knownSpec) throws Exception {
        return create(mapper, root, inputModel, reader, repeat, negative, knownSpec, "REVIEWED_ONLY", raw -> { });
    }
    public static GeneralPm4Runtime create(ObjectMapper mapper, Path root, ObjectNode inputModel,
            CodingTranscriptMaterialSupply.ReferenceReader reader, boolean repeat, boolean negative, boolean knownSpec,
            String mode, java.util.function.Consumer<ObjectNode> mutateRaw) throws Exception {
        return create(mapper, root, inputModel, reader, repeat, negative, knownSpec, mode, mutateRaw, false);
    }
    public static GeneralPm4Runtime create(ObjectMapper mapper, Path root, ObjectNode inputModel,
            CodingTranscriptMaterialSupply.ReferenceReader reader, boolean repeat, boolean negative, boolean knownSpec,
            String mode, java.util.function.Consumer<ObjectNode> mutateRaw, boolean complexAlleles) throws Exception {
        Files.createDirectories(root);
        var model = inputModel.deepCopy();
        if (!model.has("sequenceVerification"))
            model.putObject("sequenceVerification").put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText()))
                    .put("cdsSha256", CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText()));
        byte[] bytes = mapper.writeValueAsBytes(model); Files.write(root.resolve(model.path("transcript").asText() + ".model.json"), bytes);
        var manifest = mapper.createObjectNode().put("schemaVersion", "portable-native-transcript-model-derivation-v1");
        manifest.putArray("entries").addObject().put("transcript", model.path("transcript").asText()).put("file", model.path("transcript").asText() + ".model.json")
                .put("fileBytes", bytes.length).put("fileSha256", CodingTranscriptMaterialSupply.sha(bytes))
                .put("canonicalSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(model))
                .put("modelId", model.path("modelId").asText()).put("modelVersion", model.path("modelVersion").asText());
        bytes = mapper.writeValueAsBytes(manifest); Files.write(root.resolve("manifest.json"), bytes);
        var sequences = new CodingTranscriptMaterialSupply(mapper, root, CodingTranscriptMaterialSupply.sha(bytes), reader, null, complexAlleles);
        var fixture = new UniProtProteinRegionSupplyTest(); fixture.root = Files.createDirectory(root.resolve("uniprot"));
        var raw = fixture.raw("P12345");
        ((ObjectNode)raw.at("/uniProtKBCrossReferences/1")).put("id", model.path("transcript").asText());
        ((ObjectNode)raw.at("/uniProtKBCrossReferences/1/properties/0")).put("value", model.path("protein").asText());
        if (!repeat) raw.putArray("features");
        mutateRaw.accept(raw);
        var uniprot = fixture.supply(raw); var mapped = uniprot.mapModel(model, 2, 3);
        if (!"READY".equals(mapped.path("status").asText())) throw new IllegalStateException(mapped.toString());
        var doc = combined(mapper, mapped); bytes = mapper.writeValueAsBytes(doc); var file = root.resolve("combined.json"); Files.write(file, bytes);
        var combined = new CombinedProteinRegionSupply(mapper, file, CodingTranscriptMaterialSupply.sha(bytes));
        var assessment = new Pm4ProteinRegionAssessment(mapper);
        if (negative) {
            var review = mapper.createObjectNode().put("schemaVersion", "pm4-reviewed-protein-repeat-assertions-v1")
                    .put("coordinateSystem", "REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE").put("dataClass", "SYNTHETIC_TEST_ONLY");
            review.putObject("review").put("status", "REVIEWED_APPLICABLE").put("reviewId", "SYNTHETIC_TEST_ONLY")
                    .put("reviewer", "SYNTHETIC_TEST_ONLY").put("basis", "INVENTED_TEST_PROTEIN_ONLY")
                    .put("evaluatedAt", LocalDate.now(java.time.Clock.systemUTC()).toString())
                    .put("validUntil", LocalDate.now(java.time.Clock.systemUTC()).plusDays(1).toString());
            var record = review.putArray("records").addObject();
            for (String key : new String[]{"hgncId", "transcript", "protein"}) record.set(key, model.path(key));
            record.set("proteinSequenceSha256WithoutStop", mapped.path("proteinSequenceSha256WithoutStop"));
            record.set("uniProtRawSha256", mapped.at("/source/rawSha256"));
            record.putArray("intervals").addObject().put("start", 1).put("end", 6).put("repeatStatus", "NON_REPEAT")
                    .put("basis", "SYNTHETIC_TEST_ONLY").putArray("sourceLocators").add("https://example.invalid/TEST_ONLY");
            bytes = mapper.writeValueAsBytes(review); file = root.resolve("assertions.json"); Files.write(file, bytes);
            assessment = new Pm4ProteinRegionAssessment(mapper, file, CodingTranscriptMaterialSupply.sha(bytes));
        }
        var catalog = mapper.createObjectNode().put("schemaVersion", Ba1SpecificationCatalog.SCHEMA);
        catalog.putObject("metadata").put("status", "COMPLETE").put("entryCount", 1).put("snapshotId", "SYNTHETIC_TEST_ONLY");
        catalog.putArray("entries").addObject().putObject("scope").putArray("hgncIds").add(knownSpec ? "HGNC:4827" : "HGNC:999999");
        bytes = mapper.writeValueAsBytes(catalog); file = root.resolve("catalog.json"); Files.write(file, bytes);
        return new GeneralPm4Runtime(mapper, sequences, uniprot, combined, assessment,
                PredictionSpecializationGuard.load(mapper, file, CodingTranscriptMaterialSupply.sha(bytes)), mode);
    }
    static ObjectNode combined(ObjectMapper mapper, ObjectNode annotations) {
        var doc = mapper.createObjectNode();
        var row = doc.putArray("results").addObject().put("accession", "P12345").put("status", "FACTS_READY");
        var f = row.putObject("facts").put("schemaVersion", "combined-protein-region-facts-v1")
                .put("accession", "P12345").put("coordinateSystem", "ONE_BASED_CLOSED").put("length", 6);
        f.set("sequenceSha256", annotations.path("proteinSequenceSha256WithoutStop"));
        f.putObject("sources").putObject("pins").putObject("uniprot").set("sha256", annotations.at("/source/rawSha256"));
        f.put("adapterVersion", "1.1.0").put("sourceScope", "UNIPROT_FEATURES_AND_INTEGRATED_INTERPRO_ENTRIES_ONLY");
        var sources = (ObjectNode) f.path("sources"); sources.put("interproRelease", "110.0").put("interproMinor", "0");
        sources.set("uniprotRelease", annotations.at("/source/release"));
        for (String key : java.util.List.of("root", "rootReceipt", "protein", "proteinReceipt", "entries", "entriesReceipt", "uniprotAcquisition"))
            ((ObjectNode) sources.path("pins")).putObject(key).put("sha256", "a".repeat(64));
        f.putObject("sourceCompleteness").put("uniprotFeaturesComplete", true).put("interproEntriesComplete", true)
                .put("exactFullSequenceMatch", true).put("uniprotFeatureCount", annotations.at("/featureSummary/sourceFeatureCount").asInt())
                .put("interproEntryCount", 0);
        f.putArray("context"); f.putArray("regions"); f.putArray("unplaced"); return doc;
    }
}
