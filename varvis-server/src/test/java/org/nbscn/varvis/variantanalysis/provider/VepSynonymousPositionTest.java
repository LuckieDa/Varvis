package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class VepSynonymousPositionTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();
    @Test void pinnedPluginUsesIsolatedOutputAndPreservesLofteeSearchDirectory() throws Exception {
        var properties=new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties();
        var vep=properties.getVep();vep.setExecutable("vep");vep.setVersion("116.0");
        vep.setCacheDir(temporary.resolve("cache").toString());vep.setFasta(temporary.resolve("ref.fa").toString());
        var plugins=temporary.resolve("plugins with spaces");Files.createDirectories(plugins.resolve("loftee"));
        Files.writeString(plugins.resolve("loftee/LoF.pm"),"synthetic placeholder");vep.setPluginsDir(plugins.toString());vep.setLofteeEnabled(true);
        var output=temporary.resolve("work with spaces/output.json");
        var command=new VepCommandBuilder(properties).build(new VepCommandOptions(temporary.resolve("in.vcf"),output));
        assertTrue(command.contains("NearestExonJB"));assertTrue(command.stream().anyMatch(s->s.startsWith("LoF,")));
        var dirs=command.get(command.indexOf("--dir_plugins")+1);assertFalse(dirs.contains("\""));assertTrue(dirs.contains("plugins\\ with\\ spaces/loftee"));
        try(var files=Files.list(output.getParent())) {
            var staged=files.filter(Files::isDirectory).findFirst().orElseThrow().resolve("NearestExonJB.pm");
            assertEquals(SynonymousExonPosition.PLUGIN_SHA256,java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(staged))));
        }
    }
    ObjectNode input(String raw, int strand) {
        var record=mapper.createObjectNode().put("id","candidate").put("start",150).put("end",150)
                .put("strand",1).put("seq_region_name","1").put("assembly_name","GRCh38").put("allele_string","A/G");
        var tx=record.putArray("transcript_consequences").addObject().put("transcript_id","ENST000001")
                .put("gene_symbol","GENE").put("hgvsc","ENST000001.4:c.30A>G").put("strand",strand)
                .put("variant_allele","G").put("nearestexonjb",raw);
        tx.putArray("consequence_terms").add("synonymous_variant"); return record;
    }
    TranscriptConsequence parse(ObjectNode input,String version) throws Exception {
        var path=temporary.resolve("output.json");Files.writeString(path,input.toString());
        return new VepJsonOutputParser(mapper).parseByVariantId(path,"VEP",version).getAnnotationsByVariantId().get("candidate").get(0);
    }
    @ParameterizedTest @CsvSource({"1,start,0,99", "-1,start,99,0", "1,end,99,0", "-1,end,0,99", "1,start_end,49,49", "-1,start_end,49,49"})
    void pluginDistanceIsGenomicButBp7IsTranscriptOriented(int strand,String end,int startDistance,int endDistance) throws Exception {
        String raw="ENSE000001|"+(end.equals("start_end")?49:0)+"|"+end+"|"+(end.equals("start_end")?99:100);
        var tx=parse(input(raw,strand),"116.0"); var proof=tx.getSynonymousExonPosition(); assertNotNull(proof);
        assertEquals(startDistance,proof.distanceFromStart());assertEquals(endDistance,proof.distanceFromEnd());
        var roundtrip=mapper.readValue(mapper.writeValueAsBytes(tx),TranscriptConsequence.class);
        assertEquals(proof,roundtrip.getSynonymousExonPosition());assertEquals(tx.getBp7SynonymousAtExonFirstBase(),roundtrip.getBp7SynonymousAtExonFirstBase());
        assertTrue(proof.matches(NormalizedVariantKey.canonical(GenomeAssembly.GRCh38,"chr1",150,"A","G"),"ENST000001.4"));
        assertFalse(proof.matches(NormalizedVariantKey.canonical(GenomeAssembly.GRCh38,"chr1",151,"A","G"),"ENST000001.4"));
        assertFalse(proof.matches(NormalizedVariantKey.canonical(GenomeAssembly.GRCh38,"chr1",150,"A","T"),"ENST000001.4"));
        assertFalse(proof.matches(NormalizedVariantKey.canonical(GenomeAssembly.GRCh38,"chr1",150,"A","G"),"ENST000001.5"));
    }
    @Test void malformedMissingConflictingOrOtherAssemblyNeverCreatesProof() throws Exception {
        for(String raw:new String[]{"", "ENSE000001|-1|end|100", "ENSE000001|2|start_end|100", "ENSE000001|60|end|100", "ENSE000001|0|end|100,ENSE000002|0|end|100"})
            assertNull(parse(input(raw,1),"116.0").getSynonymousExonPosition(),raw);
        assertNull(parse(input("ENSE000001|0|end|100",0),"116.0").getSynonymousExonPosition());
        assertNull(parse(input("ENSE000001|0|end|100",1),"115.0").getSynonymousExonPosition());
        assertNull(parse(input("ENSE000001|0|end|100",1).put("assembly_name","GRCh37"),"116.0").getSynonymousExonPosition());
        assertNull(parse(input("ENSE000001|0|end|100",1).put("allele_string","A/T"),"116.0").getSynonymousExonPosition());
        var old=parse(input("",1),"116.0"); assertNull(old.getBp7SynonymousAtExonFirstBase()); assertNull(old.getBp7SynonymousDistanceFromExonEnd());
        assertFalse(mapper.valueToTree(old).has("synonymousExonPosition"));
        assertEquals("TRANSCRIPT_RELATIVE_POSITION_MISSING",new IntronicSplicePolicy(mapper).blocker(old));
        for(String conflict:new String[]{"intron_variant","missense_variant","stop_gained","splice_donor_variant"}) {
            var record=input("ENSE000001|1|start|100",1);
            ((com.fasterxml.jackson.databind.node.ArrayNode)record.at("/transcript_consequences/0/consequence_terms")).add(conflict);
            assertNotNull(new IntronicSplicePolicy(mapper).blocker(parse(record,"116.0")));
        }
        assertThrows(IllegalArgumentException.class,()->new SynonymousExonPosition("GRCh38","1",150,"A","G","ENST000001.4",
                "ENSE000001",1,149,248,"ENSE000001|2|start|100","116.0",SynonymousExonPosition.PLUGIN_SHA256));
    }
}
