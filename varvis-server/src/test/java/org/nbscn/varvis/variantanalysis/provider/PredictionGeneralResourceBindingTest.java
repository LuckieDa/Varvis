package org.nbscn.varvis.variantanalysis.provider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import static org.junit.jupiter.api.Assertions.*;

class PredictionGeneralResourceBindingTest {
    @TempDir Path root;
    @ParameterizedTest @ValueSource(strings = {"c", "C"})
    void caseMaskedBasesKeepTheirBiologicalIdentityAndWrongBasesRemainRejected(String base) throws Exception {
        var m = new ObjectMapper(); Path data=root.resolve("data"), index=root.resolve("data.tbi"), fasta=root.resolve("ref.fa"), fai=root.resolve("ref.fa.fai");
        Files.writeString(data,"data"); Files.writeString(index,"index"); Files.writeString(fasta,">chr1\na"+base+"g\n"); Files.writeString(fai,"chr1\t3\t6\t3\t4\n");
        var doc=m.createObjectNode().put("schemaVersion","prediction-local-binding-v1").put("purpose","NONCOMMERCIAL_LOCAL_RESEARCH_SELECTED_VARIANTS").put("referencePath",fasta.toString());
        var resource=doc.putObject("resource").put("sha256","a".repeat(64)).put("indexSha256","b".repeat(64));
        resource.putObject("license").put("id","CC-BY-NC-SA-4.0").put("textSha256","c".repeat(64));
        var proof=doc.putObject("verification");proof.putArray("rawHeader").add("Licensed under CC BY-NC-SA 4.0 license");
        proof.set("runtimeDataArtifact",stamp(m,data).put("sha256","a".repeat(64)));proof.set("runtimeIndexArtifact",stamp(m,index).put("sha256","b".repeat(64)));
        var origin=proof.putObject("origin");origin.set("runtimeDataArtifact",proof.path("runtimeDataArtifact"));origin.set("runtimeIndexArtifact",proof.path("runtimeIndexArtifact"));
        doc.set("reference",stamp(m,fasta));doc.set("referenceIndex",stamp(m,fai));
        Path file=root.resolve("binding.json");Files.write(file,m.writeValueAsBytes(doc));
        var binding=PredictionLocalResourceBinding.load(m,file,CodingTranscriptMaterialSupply.sha(Files.readAllBytes(file)));
        var key=new NormalizedVariantKey("GRCh38","1",2,"C","T");
        assertNull(binding.generalIssue(data,key,binding.identity(),"SYNTHETIC"));
        assertNull(binding.generalIssue(data,key,binding.identity(),"PRIVACY_BOUNDED_RUNTIME"));
        assertEquals("USE_RESTRICTION_CONFLICT",binding.issue(data,key,binding.identity(),"SYNTHETIC"));
        assertEquals("USE_RESTRICTION_CONFLICT",binding.generalIssue(data,key,binding.identity(),"UNDECLARED"));
        assertEquals("PREDICTION_REFERENCE_ALLELE_MISMATCH",binding.generalIssue(data,new NormalizedVariantKey("GRCh38","1",2,"G","T"),binding.identity(),"SYNTHETIC"));
        Files.writeString(data,"changed");assertEquals("PREDICTION_RESOURCE_FILE_CHANGED",binding.generalIssue(data,key,binding.identity(),"SYNTHETIC"));
    }
    private ObjectNode stamp(ObjectMapper m,Path p)throws Exception{return m.createObjectNode().put("sizeBytes",Files.size(p)).put("mtimeMillis",Files.getLastModifiedTime(p).toMillis());}
}
