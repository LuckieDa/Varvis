package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fixed real source clauses plus explicitly synthetic peptide-position probes; not real variant evaluation. */
class PublishedPm1LocalSourceTest {
    @Test void threeFixedSourceBackgroundsUseTheSameLocationGrammar() throws Exception {
        String file=System.getProperty("varvis.pm1.published.file"),sha=System.getProperty("varvis.pm1.published.sha");
        assumeTrue(file!=null && sha!=null,"Explicit fixed source file and pin required");
        var mapper=new ObjectMapper();var doc=(ObjectNode)mapper.readTree(Files.readAllBytes(Path.of(file)));
        var knowledge=PublishedCriterionKnowledge.load(mapper,Path.of(file),sha);assertEquals(3,doc.path("records").size());
        var outputs=mapper.createArrayNode();
        for(var record:doc.path("records")) {
            var s=record.path("scope");String tx=s.path("transcript").asText();int dot=tx.lastIndexOf('.');
            var context=mapper.createObjectNode().put("moi",s.path("moi").asText());
            context.putObject("gene").set("hgncId",s.path("hgncId"));context.putObject("disease").set("mondoId",s.path("mondoId"));
            context.putObject("transcript").put("accession",tx.substring(0,dot)).put("version",tx.substring(dot+1));
            context.putObject("variant").putObject("evidenceQueryAllele").put("assembly","GRCh38");
            var i=record.path("interpretation");int length=200;String[] codons=new String[length];java.util.Arrays.fill(codons,"GCT");
            for(var rule:i.path("rules")) {
                for(var r:rule.path("referenceResidues")) codons[r.path("position").asInt()-1]=codon(r.path("aminoAcid").asText().charAt(0));
                String motif=rule.path("expectedSequence").asText();
                for(int j=0;j<motif.length();j++)codons[rule.path("start").asInt()-1+j]=codon(motif.charAt(j));
            }
            var model=mapper.createObjectNode().put("transcript",tx).put("hgncId",s.path("hgncId").asText()).put("assembly","GRCh38")
                    .put("proteinLength",length).put("cdsSequence",String.join("",codons)+"TAA")
                    .put("protein",i.path("proteinIdentity").path("accession").asText("NP_999999.1"));
            var selected=knowledge.select(GeneralPm1Runtime.CRITERION,context,model);assertEquals("READY",selected.path("status").asText(),selected.toString());
            for(var rule:i.path("rules")) for(int position:List.of(rule.path("start").asInt(),rule.path("end").asInt())) {
                var out=new PublishedPm1RegionAssessment(mapper).assess(selected,model,mapper.createObjectNode().put("proteinPosition",position));
                assertEquals("MET",out.path("status").asText(),out.toPrettyString());assertEquals("MODERATE",out.path("appliedStrength").asText());
                out.put("sourceRecordId",record.path("recordId").asText()).put("dataClass","REAL_SOURCE_WITH_SYNTHETIC_PEPTIDE_PROBE");outputs.add(out);
            }
            var noMatch=new PublishedPm1RegionAssessment(mapper).assess(selected,model,mapper.createObjectNode().put("proteinPosition",1));
            assertEquals("PM1_PUBLISHED_SOURCE_SCOPE_NOT_COVERED",noMatch.path("reasonCode").asText());
        }
        String output=System.getProperty("varvis.pm1.published.artifacts");
        if(output!=null){Path target=Files.createDirectories(Path.of(output));mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve("real-source-synthetic-position-results.json").toFile(),outputs);}
    }
    private static String codon(char aa) {return switch(aa){case 'F'->"TTT";case 'Q'->"CAA";case 'M'->"ATG";case 'Y'->"TAT";case 'P'->"CCT";default->throw new IllegalArgumentException("Unexpectedfixedaminoacid");};}
}
