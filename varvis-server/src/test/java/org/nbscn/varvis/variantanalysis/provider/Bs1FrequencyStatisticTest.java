package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic population/source controls, not clinical adoption. */
public class Bs1FrequencyStatisticTest {
    @TempDir Path temporary;
    final Bs1KnowledgeCatalogTest f = new Bs1KnowledgeCatalogTest();
    public static void useAf(ObjectNode record, String term, String... ancestries) {
        var s = record.putObject("populationStatistic").put("metricId", Bs1FrequencyStatistic.METRIC)
                .put("unit", "fraction").put("confidence", "POINT_ESTIMATE").put("sourceTerm", term)
                .put("interpretation", "MAF".equals(term) ? "TARGET_ALT_AF_NOT_ABOVE_HALF" : "TARGET_ALT_AF")
                .put("interpretationBasis", "SYNTHETIC: target variant allele count / same-ancestry allele number; no complement");
        s.putObject("eligibleAn").put("operator", "GT").put("decimal", "2000");
        var names = s.putArray("ancestries"); var supported = record.putArray("supportedAncestries");
        for (String name : ancestries) { names.add(name); supported.add(name); }
        Bs1KnowledgeQualificationTest.qualify(record);
    }
    ObjectNode record(String term) {
        var r = f.record("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_RECESSIVE");
        useAf(r, term, "nfe", "afr"); return r; // independently known model boundary .002
    }
    Map<String,String> values(String nfeAc, String afrAc) {
        var v = f.local.facts.info("0.0001", "10000");
        population(v, "nfe", nfeAc, "10000"); population(v, "afr", afrAc, "10000"); return v;
    }
    void population(Map<String,String> v, String a, String ac, String an) {
        v.put("AC_"+a,ac);v.put("AN_"+a,an);
        v.put("AF_"+a,new java.math.BigDecimal(ac).divide(new java.math.BigDecimal(an),java.math.MathContext.DECIMAL64).toPlainString());
    }
    ObjectNode run(ObjectNode record, Map<String,String> values) throws Exception { return run(record,values,"PASS"); }
    ObjectNode run(ObjectNode record, Map<String,String> values, String filter) throws Exception {
        f.temporary=temporary;var c=f.context(record);
        return f.runtime(f.load(f.bundle(record))).evaluateLocal(c,f.local.shared(c,values,filter),Bs1LocalExomesTest.SOURCE);
    }
    String status(ObjectNode result) { return result.at("/evaluation/evaluationResult/status").asText(); }
    String reason(ObjectNode result) { return result.at("/evaluation/evaluationResult/reasonCode").asText(); }
    @ParameterizedTest @CsvSource({"19,NOT_MET","20,NOT_MET","21,MET"})
    void targetAfUsesOwnMaximumAndExactBoundary(String ac,String expected) throws Exception {
        var result=run(record("AF"),values("10",ac));assertEquals(expected,status(result),result.toPrettyString());
        assertEquals("afr",result.at("/qualityDecision/facts/ancestry").asText());
        assertEquals("nfe",Bs1Quality.measure(result.path("evidence"),"gnomad.exomes.faf95max.ancestry"));
        assertFalse(result.at("/qualityDecision/facts").has("FAF95"));
        assertEquals(Bs1FrequencyStatistic.VERSION,result.at("/evaluation/deterministicTrace/evaluatorVersion").asText());
    }
    @Test void afDoesNotRequireOrSubstituteFaf() throws Exception {
        var v=values("10","30");v.keySet().removeIf(k->k.startsWith("faf"));
        assertEquals("MET",status(run(record("AF"),v)));
        v.remove("AF_afr"); assertEquals("BS1_AF_POPULATION_AF_INVALID",reason(run(record("AF"),v)));
    }
    @Test void eligibilityRunsBeforeMaximumAndDoesNotBorrowCounts() throws Exception {
        var v=values("30","50");population(v,"afr","50","2000");
        var r=run(record("AF"),v);assertEquals("MET",status(r));
        assertEquals("nfe",r.at("/qualityDecision/facts/ancestry").asText());
        assertEquals("EXCLUDED",r.at("/qualityDecision/frequencySelection/populations/0/status").asText());
        population(v,"nfe","30","1999");assertEquals("BS1_AF_NO_ELIGIBLE_POPULATION",reason(run(record("AF"),v)));
    }
    @ParameterizedTest @ValueSource(strings={"MISSING_AN","MISSING_AC","BAD_AN","BAD_AF","COUNT","FILTER","NO_MAF_INTERPRETATION"})
    void missingOrConflictingFactsDoNotProduceNegativeConclusion(String fault) throws Exception {
        var r=record("AF");var v=values("30","10");String filter="PASS";
        switch(fault) {
            case "MISSING_AN" -> v.remove("AN_afr");
            case "MISSING_AC" -> v.remove("AC_afr");
            case "BAD_AN" -> v.put("AN_afr","2000.5");
            case "BAD_AF" -> v.put("AF_afr","1.1");
            case "COUNT" -> v.put("AF_afr","0.01");
            case "FILTER" -> filter="LowQual";
            case "NO_MAF_INTERPRETATION" -> {r.withObject("/populationStatistic").put("sourceTerm","MAF");Bs1KnowledgeQualificationTest.qualify(r);}
        }
        var result=run(r,v,filter);assertEquals("NOT_EVALUATED",status(result),result.toPrettyString());assertFalse(reason(result).isBlank());
    }
    @Test void mafNeverFlipsToReferenceOrClaimsAfOverHalfIsMinor() throws Exception {
        assertEquals("MET",status(run(record("MAF"),values("30","10"))));
        var v=values("6000","10");
        assertEquals("MET",status(run(record("AF"),v)));
        assertEquals("BS1_MAF_TARGET_ALLELE_INTERPRETATION_UNRESOLVED",reason(run(record("MAF"),v)));
    }
    @Test void tiesAndRoundedAfAreDeterministicAndUseExactCounts() throws Exception {
        var v=values("30","30");assertEquals("afr",run(record("AF"),v).at("/qualityDecision/facts/ancestry").asText());
        // .00200002 rounds to .002; compare counts instead of this rounded display value.
        population(v,"afr","200002","100000000");v.put("AF_afr","0.0020000");population(v,"nfe","20","10000");
        var r=run(record("AF"),v);assertEquals("MET",status(r));assertEquals("afr",r.at("/qualityDecision/facts/ancestry").asText());
    }
    @Test void sameFrequencyWithLowerSampleSupportIsExplicitlyExcluded() throws Exception {
        var r=record("AF");r.withObject("/populationStatistic/eligibleAn").put("decimal","5000");Bs1KnowledgeQualificationTest.qualify(r);
        var v=values("30","10");population(v,"nfe","30","3000");
        assertEquals("NOT_MET",status(run(r,v))); // only afr qualifies; nfe is outside declared sample scope
        v.remove("AN_nfe");assertEquals("NOT_EVALUATED",status(run(r,v))); // unknown eligibility cannot be assumed excluded
    }
    @Test void changedMetricConditionsAndUnqualifiedOrUnknownScopesAreRejected() {
        var r=record("AF");r.withObject("/populationStatistic").put("confidence","95_PERCENT_LOWER_BOUND");Bs1KnowledgeQualificationTest.qualify(r);
        assertEquals("BS1_AF_STATISTIC_INVALID",Bs1Policy.issue(Bs1KnowledgeCatalog.policy(r)));
        r=record("AF");r.withObject("/populationStatistic/eligibleAn").put("decimal","100");Bs1KnowledgeQualificationTest.qualify(r);
        assertEquals("BS1_AF_AN_REQUIREMENT_INVALID",Bs1Policy.issue(Bs1KnowledgeCatalog.policy(r)));
        r=record("AF");r.remove("qualification");f.review(r);
        assertEquals("BS1_QUALIFICATION_REQUIRED",Bs1Policy.issue(Bs1KnowledgeCatalog.policy(r)));
        r=record("AF");r.withObject("/populationStatistic").put("unknownCondition",true);Bs1KnowledgeQualificationTest.qualify(r);
        assertEquals("BS1_AF_STATISTIC_INVALID",Bs1Policy.issue(Bs1KnowledgeCatalog.policy(r)));
    }
    @Test void multiAltUsesQueryAlleleIndexAndCannotBorrowAnotherAlt() throws Exception {
        var r=record("AF");var c=f.context(r);var v=values("10","30");
        var ba1=f.local.shared(c,v,"PASS");
        String raw=ba1.path("rawPopulationRecord").asText();String[] fields=raw.split("\t",-1);
        fields[4]="G,"+c.at("/variant/evidenceQueryAllele/alternate").asText();
        var paired=new LinkedHashMap<String,String>();v.forEach((k,value)->paired.put(k,k.startsWith("AN_") ? value : "0,"+value));
        // Every A-valued profile field is paired, while AN Number=1 remains scalar.
        paired.put("fafmax_faf95_max_gen_anc","nfe,nfe");paired.put("grpmax","nfe,nfe");
        fields[7]=String.join(";",paired.entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList());
        ba1.put("rawPopulationRecord",String.join("\t",fields));
        ((ObjectNode)ba1.path("evidence")).set("measurements",f.mapper.valueToTree(GnomadPopulationInputProfile.loadLocalBa1().parse("PASS",paired,1,2).getMeasurements()));
        f.temporary=temporary;var result=f.runtime(f.load(f.bundle(r))).evaluateLocal(c,ba1,Bs1LocalExomesTest.SOURCE);
        assertEquals("MET",status(result),result.toPrettyString());assertEquals("30",result.at("/qualityDecision/facts/AC").asText());
    }
    @Test void fixedOfficialClauseCanReplayWithoutAdoptingRealKnowledge() throws Exception {
        String name=System.getProperty("bs1.af.source-replay","");
        org.junit.jupiter.api.Assumptions.assumeFalse(name.isBlank(),"No frozen source replay supplied");
        var file=Path.of(name);byte[] bytes=java.nio.file.Files.readAllBytes(file);
        var catalog=Bs1KnowledgeCatalog.load(file,Bs1KnowledgeCatalog.sha(bytes));var rt=f.runtime(catalog);
        var record=(ObjectNode)f.mapper.readTree(bytes).path("records").get(0);var context=f.context(record);
        assertTrue(record.at("/review/reviewer").asText().startsWith("SYNTHETIC"));
        var rows=f.mapper.createArrayNode();
        for(int ac:List.of(34,35,36)) {
            var v=values("0","0");for(String group:List.of("afr","amr","eas","nfe","sas","mid"))population(v,group,"0","10000");
            population(v,"nfe",Integer.toString(ac),"10000");
            var result=rt.evaluateLocal(context,f.local.shared(context,v,"PASS"),Bs1LocalExomesTest.SOURCE);
            assertEquals(ac<35?"NOT_MET":"MET",status(result),result.toPrettyString());
            rows.add(result);
        }
        java.nio.file.Files.write(Path.of(System.getProperty("bs1.af.source-report")),f.mapper.writeValueAsBytes(rows),java.nio.file.StandardOpenOption.CREATE_NEW);
    }
}
