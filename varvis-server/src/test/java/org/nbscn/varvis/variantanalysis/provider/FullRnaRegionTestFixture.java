package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Synthetic native-format source files are really prepared/read; no provider or qualification stub. */
final class FullRnaRegionTestFixture {
    final FullRnaCriterionFixture rna;final ObjectMapper mapper;final Path root;
    final UniProtProteinRegionSupply uniprot;final CombinedProteinRegionSupply combined;
    final Pm4ProteinRegionAssessment pm4;final Bp3ProteinRegionAssessment bp3;
    final PredictionSpecializationGuard guard;final ProteinLengthRegionFactSupply facts;
    FullRnaRegionTestFixture(Path root,FullRnaCriterionFixture rna,boolean repeat,boolean nonRepeat,String function) throws Exception {
        this.root=root;this.rna=rna;mapper=rna.f.mapper;Files.createDirectories(root);var model=rna.f.token.model();
        var helper=new UniProtProteinRegionSupplyTest();helper.root=Files.createDirectory(root.resolve("uniprot"));
        var raw=helper.raw("P12345");String protein=ProteinLengthChangeObservation.translate(model.path("cdsSequence").asText());
        raw.withObject("sequence").put("value",protein.substring(0,protein.length()-1)).put("length",protein.length()-1);
        var refs=raw.putArray("uniProtKBCrossReferences");refs.addObject().put("database","HGNC").put("id",model.path("hgncId").asText());
        if(model.path("transcript").asText().startsWith("NM_"))refs.addObject().put("database","RefSeq").put("id",model.path("protein").asText())
                .putArray("properties").addObject().put("key","NucleotideSequenceId").put("value",model.path("transcript").asText());
        else refs.addObject().put("database","Ensembl").put("id",model.path("transcript").asText())
                .putArray("properties").addObject().put("key","ProteinId").put("value",model.path("protein").asText());
        var features=raw.putArray("features");if(repeat)features.add(helper.feature("Repeat",2,5));
        uniprot=helper.supply(raw);var mapped=uniprot.mapModel(model,3,3);
        if(!"READY".equals(mapped.path("status").asText()))throw new AssertionError(mapped.toPrettyString());
        var doc=GeneralPm4TestSupport.combined(mapper,mapped);
        ((ObjectNode)doc.at("/results/0/facts")).put("length",model.path("proteinLength").asInt());
        // Replace old placeholder fixture pins by actual bounded synthetic upstream files.
        for(var family:List.of("uniprotAcquisition","root","rootReceipt","protein","proteinReceipt","entries","entriesReceipt")) {
            byte[] original=mapper.writeValueAsBytes(mapper.createObjectNode().put("purpose","SYNTHETIC_TEST_ONLY").put("kind",family));
            Files.write(root.resolve(family+".json"),original);
            ((ObjectNode)doc.at("/results/0/facts/sources/pins/"+family)).put("sha256",CodingTranscriptMaterialSupply.sha(original));
        }
        byte[] bytes=mapper.writeValueAsBytes(doc);Files.write(root.resolve("combined.json"),bytes);
        combined=new CombinedProteinRegionSupply(mapper,root.resolve("combined.json"),CodingTranscriptMaterialSupply.sha(bytes));
        pm4=nonRepeat?negative(model,mapped):new Pm4ProteinRegionAssessment(mapper);
        var catalog=mapper.createObjectNode().put("schemaVersion",Ba1SpecificationCatalog.SCHEMA);catalog.putObject("metadata")
                .put("status","COMPLETE").put("entryCount",0).put("snapshotId","SYNTHETIC_EMPTY_CATALOG");catalog.putArray("entries");
        bytes=mapper.writeValueAsBytes(catalog);Files.write(root.resolve("catalog.json"),bytes);
        guard=PredictionSpecializationGuard.load(mapper,root.resolve("catalog.json"),CodingTranscriptMaterialSupply.sha(bytes));
        facts=new ProteinLengthRegionFactSupply(mapper,rna.f.supply,uniprot,combined,null);
        if(function.equals("MISSING"))bp3=new Bp3ProteinRegionAssessment(mapper);
        else {
            var k=new Bp3ProteinRegionAssessmentTest();k.context=rna.f.context;k.model=model;k.annotations=mapped;k.extra=combined.resolveForBp3(mapped);
            var knowledge=k.qualified();var record=(ObjectNode)knowledge.at("/records/0");
            record.withObject("review").put("evaluatedAt",LocalDate.now(Clock.systemUTC()).toString()).put("validUntil",LocalDate.now(Clock.systemUTC()).plusDays(1).toString());
            ((ObjectNode)record.at("/intervals/0")).put("start",1).put("end",model.path("proteinLength").asInt())
                    .put("repeatStatus",repeat?"REPEAT":"NON_REPEAT").put("functionalStatus",function);
            bytes=mapper.writeValueAsBytes(knowledge);Files.write(root.resolve("bp3-knowledge.json"),bytes);
            bp3=new Bp3ProteinRegionAssessment(mapper,root.resolve("bp3-knowledge.json"),CodingTranscriptMaterialSupply.sha(bytes));
        }
    }
    private Pm4ProteinRegionAssessment negative(ObjectNode model,ObjectNode mapped) throws Exception {
        var review=mapper.createObjectNode().put("schemaVersion","pm4-reviewed-protein-repeat-assertions-v1")
                .put("coordinateSystem","REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE").put("dataClass","SYNTHETIC_TEST_ONLY");
        review.putObject("review").put("status","REVIEWED_APPLICABLE").put("reviewId","SYNTHETIC_RESEARCH_ONLY").put("reviewer","SYNTHETIC")
                .put("basis","Invented engineering source, no clinical qualification").put("evaluatedAt",LocalDate.now(Clock.systemUTC()).toString())
                .put("validUntil",LocalDate.now(Clock.systemUTC()).plusDays(1).toString());
        var record=review.putArray("records").addObject();for(String key:List.of("hgncId","transcript","protein"))record.set(key,model.path(key));
        record.set("proteinSequenceSha256WithoutStop",mapped.path("proteinSequenceSha256WithoutStop"));record.set("uniProtRawSha256",mapped.at("/source/rawSha256"));
        record.putArray("intervals").addObject().put("start",1).put("end",model.path("proteinLength").asInt()).put("repeatStatus","NON_REPEAT")
                .put("basis","SYNTHETIC explicit negative coverage").putArray("sourceLocators").add("https://example.test/synthetic-nonrepeat");
        byte[] bytes=mapper.writeValueAsBytes(review);Files.write(root.resolve("pm4-knowledge.json"),bytes);
        return new Pm4ProteinRegionAssessment(mapper,root.resolve("pm4-knowledge.json"),CodingTranscriptMaterialSupply.sha(bytes));
    }
    GeneralPm4Runtime pm4(){return new GeneralPm4Runtime(mapper,rna.f.supply,uniprot,combined,pm4,guard);}
    GeneralBp3Runtime bp3(){return new GeneralBp3Runtime(mapper,facts,bp3,guard);}
}
