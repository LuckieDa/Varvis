package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.nbscn.varvis.variantanalysis.service.T04CurrentSessionFixture;

/** SYNTHETIC examples in the published GA4GH model. Never a complete medical qualification. */
public final class T04PhenopacketOriginalFixture {
    private T04PhenopacketOriginalFixture() { }
    public static ObjectNode family(ObjectNode context,String uri,String vcfSample) {
        var json=new ObjectMapper();var root=json.createObjectNode().put("id",context.at("/patientContext/familyContextId").asText());
        root.set("metaData",metadata());var packet=root.putObject("proband").put("id","synthetic-clinical-record");
        packet.set("metaData",metadata());String patient=context.at("/patientContext/patientContextId").asText();
        packet.putObject("subject").put("id",patient).put("sex","FEMALE").putObject("timeAtLastEncounter").putObject("age").put("iso8601duration","P20Y");
        var present=packet.putArray("phenotypicFeatures").addObject();present.putObject("type").put("id","HP:0001250");
        present.putObject("onset").putObject("age").put("iso8601duration","P6M");
        var absent=packet.withArray("phenotypicFeatures").addObject().put("excluded",true);absent.putObject("type").put("id","HP:0001252");
        var sample=packet.putArray("biosamples").addObject().put("id","synthetic-proband").put("individualId",patient);
        var file=sample.putArray("files").addObject().put("uri",uri);
        file.putObject("individualToFileIdentifiers").put("synthetic-proband",vcfSample);
        file.putObject("fileAttributes").put("genomeAssembly","GRCh38").put("fileFormat","vcf");
        return root;
    }
    /** Explicit synthetic numeric input using native fields, never a clinical assessment. */
    public static void addClinicalNumberAndUnrecordedRequiredDose(ObjectNode family,boolean decimal) {
        var packet=family.withObject("/proband");
        for(var metadata:java.util.List.of(family.withObject("/metaData"),packet.withObject("/metaData"))) {
            metadata.withArray("resources").addObject().put("id","ncit").put("name","NCI Thesaurus").put("namespacePrefix","NCIT").put("version","synthetic-engineering-release");
            metadata.withArray("resources").addObject().put("id","loinc").put("name","LOINC").put("namespacePrefix","LOINC").put("version","synthetic-engineering-release");
        }
        var measurement=packet.putArray("measurements").addObject();
        measurement.putObject("assay").put("id","LOINC:26474-7");var q=measurement.putObject("value").putObject("quantity");
        if(decimal) q.put("value",new java.math.BigDecimal("1.234567890123456789"));else q.put("value",17);
        q.putObject("unit").put("id","NCIT:C67245");measurement.putObject("timeObserved").put("timestamp","2024-01-01T00:00:00Z");
        var treatment=packet.putArray("medicalActions").addObject().putObject("treatment");treatment.putObject("agent").put("id","NCIT:C722");
        treatment.putArray("doseIntervals").addObject().putObject("interval").put("start","2024-01-01T00:00:00Z").put("end","2024-01-02T00:00:00Z");
    }
    public static ObjectNode metadata() {
        var value=new ObjectMapper().createObjectNode().put("created","2024-06-01T00:00:00Z").put("createdBy","SYNTHETIC clinical-source exporter")
                .put("phenopacketSchemaVersion","2.0");
        value.putArray("resources").addObject().put("id","hp").put("name","Human Phenotype Ontology")
                .put("namespacePrefix","HP").put("version","synthetic-engineering-resource-release");return value;
    }
    public static Path register(T04CurrentSessionFixture fixture,ObjectNode context,Path manifest,
            java.util.function.Consumer<ObjectNode> mutation) throws Exception {
        return register(context,fixture.recovered().execution().getInputSource().analysisVcf().toAbsolutePath().normalize().toUri().toASCIIString(),fixture.sampleId(),manifest,mutation);
    }
    public static Path register(ObjectNode context,String uri,String sampleId,Path manifest,java.util.function.Consumer<ObjectNode> mutation) throws Exception {
        var root=family(context,uri,sampleId);
        mutation.accept(root);var json=new ObjectMapper();byte[] raw=json.writeValueAsBytes(root);
        Path original=manifest.getParent().resolve("clinical-phenopacket.json");Files.write(original,raw);
        var doc=(ObjectNode)json.readTree(Files.readAllBytes(manifest));
        // The native source replaces the custom PMC sample_links table as the association source.
        // Other case/cohort/report qualifications remain their original independent sources.
        for(var source:doc.path("originals")) if("CLINICAL".equals(source.path("role").asText())) ((ObjectNode)source.path("tableIds")).remove("sample_links");
        doc.putObject("clinicalSampleSource").put("format",Pp4PhenopacketReplay.FORMAT).put("kind","FAMILY")
                .put("sourceId","synthetic-native-clinical-original").put("recordId",root.path("id").asText())
                .put("creator",root.at("/metaData/createdBy").asText()).put("file",original.getFileName().toString())
                .put("rawSha256",T04NativeOriginalFixture.rawSha(raw)).put("locator","urn:synthetic:phenopacket:source")
                .put("acquiredAt","2025-01-01T00:00:00Z");
        Files.write(manifest,json.writeValueAsBytes(doc));return manifest;
    }
}
