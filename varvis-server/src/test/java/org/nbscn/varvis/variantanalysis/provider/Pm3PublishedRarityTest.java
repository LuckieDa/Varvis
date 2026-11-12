package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class Pm3PublishedRarityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void exactFrequencyUsesStrictThresholdAndCallableAbsenceKeepsAfMissing() {
        var rare = qualify("T","PASS",2);
        assertEquals("VERIFIED",rare.path("qualification").asText());
        assertEquals("0.0002",rare.at("/populationEvidence/maximumObservedAf").asText());
        assertEquals("FAILED",qualify("T","PASS",4).path("qualification").asText());
        var absent = qualify("G","PASS",2);
        assertEquals("VERIFIED",absent.path("qualification").asText());
        assertEquals("NO_RECORD",absent.at("/populationEvidence/outcome").asText());
        assertEquals(10000,absent.at("/populationEvidence/ancestries/eas/AN").asInt());
        assertTrue(absent.at("/populationEvidence/ancestries/eas/AF").isMissingNode());
        assertEquals("UNKNOWN",qualify("G","EXOMES_FILTERED",2).path("qualification").asText());
    }

    @Test void releasedFafIsInclusiveAndDoesNotSubstituteObservedFrequencyOrNoRecord() {
        var exactly = qualify("T","PASS",4,"0.0002","eas");
        assertEquals("VERIFIED",exactly.path("qualification").asText());
        assertEquals("0.0004",exactly.at("/populationEvidence/maximumObservedAf").asText());
        assertEquals("0.0002",exactly.at("/populationEvidence/popmaxFaf95").asText());
        assertEquals("VERIFIED",qualify("T","PASS",4,"1.99999e-04","eas").path("qualification").asText());
        assertEquals("FAILED",qualify("T","PASS",4,"0.000200001","eas").path("qualification").asText());
        assertEquals("CONFLICT",qualify("T","PASS",4,"0.0002","afr").path("qualification").asText());
        assertEquals("UNKNOWN",qualify("G","PASS",4,"0.0002","eas").path("qualification").asText());
        assertEquals("UNKNOWN",qualify("T","PASS",4,".","eas").path("qualification").asText());
    }

    private ObjectNode qualify(String recordAlt,String filter,int ac) {
        return qualify(recordAlt,filter,ac,null,null);
    }

    private ObjectNode qualify(String recordAlt,String filter,int ac,String faf,String maxGroup) {
        var allele = json.createObjectNode().put("assembly","GRCh38").put("contig","19")
                .put("position",100).put("reference","C").put("alternate","T");
        var context = json.createObjectNode().put("contextId","test");
        context.putObject("variant").set("evidenceQueryAllele",allele);
        var row = json.createObjectNode();
        row.putObject("identities").putObject("partner").set("allele",allele);
        var policy = row.putObject("policySelection").putArray("matches").addObject();
        var spec = json.createObjectNode();
        var rule = faf==null?spec.putObject("rule"):spec.putObject("pm2").putObject("entContent")
                .putArray("strengthDescriptor").addObject();
        rule.put("applicability","Applicable").put("strength","Supporting").put("text",faf==null
                ?"**Allele frequency <0.0004 (<0.04%) in all populations in gnomAD.**"
                :"Absent/rare from controls in an ethnically-matched cohort population sample. "
                    +"* Used if the gnomAD PopMax Filtering Allele Frequency (FAF) is ≤ 2 x 10<sup>-4</sup>.");
        byte[] ruleRaw = spec.toString().getBytes(StandardCharsets.UTF_8);
        String rulePin = FamilyDeNovoJson.sha256(ruleRaw);
        policy.putObject("source").put("rawSha256",rulePin).put("jsonPointer","");
        var rarity=policy.putObject("jointRarity").put("clauseSha256",Pm3SourceDigest.sha256(json,rule));
        if(faf==null)rarity.put("clausePointer","/rule").put("maximumAlleleFrequency","0.0004");
        else {
            rarity.put("clausePointer","/pm2/entContent/strengthDescriptor/0")
                    .put("maximumAlleleFrequency","0.0002").put("method","JOINT_POPMAX_FAF95_INCLUSIVE");
            policy.putObject("reviewedCaseRule").put("releaseSha256",rulePin).put("pm2Pointer","/pm2");
        }
        var observations = json.createObjectNode();
        observations.putObject("specificationOriginals").put(rulePin,Base64.getEncoder().encodeToString(ruleRaw));

        var vcf = new StringBuilder("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n");
        vcf.append("chr19\t100\t.\tC\t").append(recordAlt).append("\t.\t").append(filter).append("\tAC_joint=")
                .append(ac).append(";AN_joint=100000;AN_exomes=90000;AN_genomes=10000;nhomalt_joint=0");
        for(String group:List.of("afr","ami","amr","asj","eas","fin","mid","nfe","remaining","sas")) {
            vcf.append(";AN_joint_").append(group).append("=10000;AC_joint_").append(group)
                    .append('=').append(group.equals("eas")?ac:0).append(";nhomalt_joint_").append(group).append("=0");
        }
        if(faf!=null) {
            for(String group:List.of("afr","amr","eas","mid","nfe","sas"))
                vcf.append(";faf95_joint_").append(group).append('=').append(group.equals("eas")?faf:"0");
            vcf.append(";fafmax_faf95_max_joint=").append(faf).append(";fafmax_faf95_max_gen_anc_joint=").append(maxGroup);
        }
        byte[] vcfRaw = vcf.toString().getBytes(StandardCharsets.UTF_8);
        var snapshot = observations.putObject("jointPopulationOriginal").put("retrievalSha256","a".repeat(64))
                .put("vcf",Base64.getEncoder().encodeToString(vcfRaw));
        snapshot.putObject("source").put("assembly","GRCh38").put("chromosome","19").put("dataset","gnomAD v4.1 joint sites")
                .put("sourceUrl","https://storage.googleapis.com/gcp-public-data--gnomad/release/4.1/vcf/joint/gnomad.joint.v4.1.sites.chr19.vcf.bgz")
                .put("indexedIntervalStart",100).put("indexedIntervalEnd",100).put("rowCount",1)
                .put("sha256",FamilyDeNovoJson.sha256(vcfRaw)).putArray("queriedPositions").add(100);
        return new Pm3PublishedRarity(json).qualify(observations,row,"partner",context);
    }
}
