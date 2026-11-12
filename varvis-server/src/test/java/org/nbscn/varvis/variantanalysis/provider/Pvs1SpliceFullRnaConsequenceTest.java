package org.nbscn.varvis.variantanalysis.provider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Full RNA/anchor/junction expectations were independently fixed before this consumer.
 * The synthetic causal allele is wholly intronic, so these splice-only RNA edits
 * do not omit an unmodelled retained exonic DNA component. Junction lists and
 * full-product witnesses are explicit engineering declarations, never eligibility.
 */
class Pvs1SpliceFullRnaConsequenceTest {
    @TempDir Path root;
    record Vector(String name,int start,int end,String insert,String altered,String protein,List<Integer> junctions,Integer distance) { }
    static List<Vector> vectors() { return List.of(
        new Vector("INSERT_TAA",11,11,"TAA","CCATGAAACCCTAAGGGTTTGAATAAGGGTGA","MKP*",List.of(8,20,28),14),
        new Vector("REMOVE_STOP",20,23,"","CCATGAAACCCGGGTTTGAAGGGTGA","MKPGFEG*",List.of(8,17,22),-4),
        new Vector("CROSS_STOP_UTR",21,24,"","CCATGAAACCCGGGTTTGAATGGTGA","MKPGFEW*",List.of(8,17,22),-4),
        new Vector("REMOVE_START",2,5,"","CCAAACCCGGGTTTGAATAAGGGTGA",null,List.of(5,14,22),null),
        new Vector("REMOVE_FIRST_EXON",0,8,"","CCCGGGTTTGAATAAGGGTGA",null,List.of(9,17),null),
        new Vector("REMOVE_LAST_EXON",25,29,"","CCATGAAACCCGGGTTTGAATAAGG","MKPGFE*",List.of(8,17),-6),
        new Vector("RETAIN_INTRON",8,8,"ACGT","CCATGAAAACGTCCCGGGTTTGAATAAGGGTGA","MKTSRV*",List.of(21,29),6)); }
    static Stream<Arguments> cases() { return vectors().stream().flatMap(v->Stream.of(Arguments.of(v,1),Arguments.of(v,-1))); }
    private CodingTranscriptRnaFixture fixture(int strand) throws Exception {
        var f=new CodingTranscriptRnaFixture(root,strand);
        var exon=f.model.path("exons").path(0);
        int position=strand==1?exon.path("genomicEnd").asInt()+1:exon.path("genomicStart").asInt()-1;
        return f.withGenomicReference(position,"G").alleleAtGenomic(position,"G","A");
    }
    private CodingTranscriptEdits.RnaResult edit(CodingTranscriptRnaFixture f,Vector v) {
        var r=CodingTranscriptEdits.bindRna(f.mapper,f.context,f.token);
        String group=f.adapter.canonicalSha256(f.context.at("/variant/evidenceQueryAllele"));
        var edit=new CodingTranscriptEdits.Edit(v.name(),CodingTranscriptEdits.Origin.SPLICE_PROJECTION,v.start(),v.end(),v.insert(),
                r.referenceRna().substring(v.start(),v.end()),"b".repeat(64),group,0);
        return CodingTranscriptEdits.applyRna(r,new CodingTranscriptEdits.RnaEditSet(CodingTranscriptEdits.RNA_COORDINATES,
                f.token.referenceRnaSha256(),CodingTranscriptEdits.RnaGrouping.DECLARED_SINGLE_PHYSICAL_ALLELE,List.of(edit),v.altered()));
    }
    private Pvs1SpliceTopology.RnaTopologyDeclaration declaration(CodingTranscriptRnaFixture f,CodingTranscriptEdits.RnaResult r,List<Integer> junctions) {
        return new Pvs1SpliceTopology.RnaTopologyDeclaration(f.token.modelSha256(),f.token.referenceRnaSha256(),r.alteredRnaSha256(),
                f.adapter.canonicalSha256(r.snapshot(f.mapper)),Pvs1SpliceTopology.RnaTopologyCompleteness.COMPLETE,junctions);
    }
    @ParameterizedTest @MethodSource("cases")
    void completeRnaConsumerPreservesNativeAnchorsAndActualSequence(Vector vector,int strand) throws Exception {
        var f=fixture(strand);var result=edit(f,vector);var declared=declaration(f,result,vector.junctions());
        var assessment=Pvs1SpliceConsequence.describeFullRnaMechanics(f.context,result,declared);
        assertEquals(vector.altered(),result.alteredRna());
        if(vector.protein()==null) {
            assertTrue(assessment.at("/sequence/alteredProteinThroughFirstTermination").isNull());
            assertEquals("INITIATION_UNRESOLVED",assessment.at("/nmdGeometry/status").asText());
        } else {
            assertEquals(vector.protein(),assessment.at("/sequence/alteredProteinThroughFirstTermination").asText());
            assertEquals(vector.distance().intValue(),assessment.at("/nmdGeometry/distanceToLastJunctionRna").asInt());
        }
        assertFalse(assessment.path("evidenceAdoptionGranted").asBoolean());assertFalse(assessment.path("sourceQualificationGranted").asBoolean());
        assertEquals("NOT_GRANTED_BY_OPERATOR",assessment.at("/topology/sourceQualificationStatus").asText());
        assertFalse(assessment.has("nmdPrediction"));assertFalse(assessment.has("adoptedStrength"));
        for(var row:assessment.path("independentAdoptionRequirements")) assertEquals("NOT_GRANTED_BY_MECHANICAL_CONSUMER",row.path("state").asText());
        if(vector.name().equals("RETAIN_INTRON")) {
            var projection=Pvs1SpliceTopology.projectFullRna(result,declared);
            assertTrue(projection.classifyTriplet(21,23).crossesJunction());
        }
        String output=System.getProperty("varvis.t07.rna.artifacts");
        if(output!=null) {
            Path dir=Path.of(output).resolve(vector.name()).resolve(strand==1?"plus":"minus");Files.createDirectories(dir);
            var sample=f.mapper.createObjectNode().put("layer","ACTUAL_OPAQUE_SUPPLY_AND_MECHANICAL_CONSUMERS_WITH_DECLARED_TOPOLOGY")
                    .put("dataClass","SYNTHETIC").put("normalSelectedBusinessVerified",false).put("scientificAdoptionGranted",false);
            sample.set("context",f.context.deepCopy());sample.set("model",f.model.deepCopy());sample.put("referenceRna",f.token.referenceRnaSequence());
            sample.set("referenceMaterial",f.token.snapshot());sample.set("rnaEditFacts",result.snapshot(f.mapper));
            sample.set("declaredTopology",f.mapper.valueToTree(declared));sample.set("assessment",assessment);
            Files.write(dir.resolve("rna-consumer-sample.json"),f.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(sample),StandardOpenOption.CREATE_NEW);
        }
    }
    @Test void sameActualRnaWithoutCompleteJunctionsDoesNotGetNmdGeometry() throws Exception {
        var f=fixture(1);var result=edit(f,vectors().get(0));
        var assessment=Pvs1SpliceConsequence.describeFullRnaMechanics(f.context,result,null);
        assertEquals("MKP*",assessment.at("/sequence/alteredProteinThroughFirstTermination").asText());
        assertEquals("TOPOLOGY_UNRESOLVED",assessment.at("/nmdGeometry/status").asText());
        assertTrue(assessment.at("/nmdGeometry/distanceToLastJunctionRna").isNull());assertTrue(assessment.at("/topology/singleExon").isNull());
    }
    @ParameterizedTest @CsvSource({"60,49","61,50","62,51"})
    void distanceBoundariesRemainGeometryAndDoNotChooseAnNmdThreshold(int boundary,int distance) throws Exception {
        var f=fixture(1);String rna="CCATGAAACCCGGGTTTGAATAA"+"GGG".repeat(30)+"TGA";
        f.configure(rna,3,23,8,17,boundary,rna.length());
        int intron=f.model.path("exons").path(0).path("genomicEnd").asInt()+1;
        f.withGenomicReference(intron,"G").alleleAtGenomic(intron,"G","A");
        String changed=rna.substring(0,11)+"TAA"+rna.substring(11);
        var v=new Vector("DISTANCE",11,11,"TAA",changed,"MKP*",List.of(8,20,boundary+3),distance);
        var result=edit(f,v);var assessment=Pvs1SpliceConsequence.describeFullRnaMechanics(f.context,result,declaration(f,result,v.junctions()));
        assertEquals(distance,assessment.at("/nmdGeometry/distanceToLastJunctionRna").asInt());
        assertEquals("DISTANCE_RESOLVED_NO_POLICY_APPLIED",assessment.at("/nmdGeometry/status").asText());assertFalse(assessment.has("nmdPrediction"));
    }
    @Test void completeSingleExonHasNoDistanceInsteadOfZero() throws Exception {
        var f=fixture(1);var result=edit(f,vectors().get(0));
        var assessment=Pvs1SpliceConsequence.describeFullRnaMechanics(f.context,result,declaration(f,result,List.of()));
        assertEquals("NO_EXON_JUNCTION_IN_DECLARED_TOPOLOGY",assessment.at("/nmdGeometry/status").asText());
        assertTrue(assessment.at("/nmdGeometry/distanceToLastJunctionRna").isNull());
    }
    @Test void topologyForAnotherEditedRnaCannotBeBorrowed() throws Exception {
        var f=fixture(1);var a=edit(f,vectors().get(0));var b=edit(f,vectors().get(1));
        assertEquals("PVS1_SPLICE_FULL_RNA_TOPOLOGY_DECLARATION_BINDING_MISMATCH",assertThrows(IllegalArgumentException.class,
                ()->Pvs1SpliceConsequence.describeFullRnaMechanics(f.context,b,declaration(f,a,vectors().get(0).junctions()))).getMessage());
    }
}
