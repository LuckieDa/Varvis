package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class Pm2LocalAbsenceTest {
    final Pm2LocalExomesTest base = new Pm2LocalExomesTest();
    final com.fasterxml.jackson.databind.ObjectMapper mapper = base.mapper;
    final RuntimeEvidenceV2Adapter adapter = base.adapter;
    final Bs1LocalExomesTest facts = base.facts;
    static final String SOURCE = Pm2LocalExomesTest.SOURCE;
    ObjectNode context(int index) throws Exception { return base.context(index); }
    java.util.Map<String, String> info(String ac, String an, String faf) { return base.info(ac, an, faf); }
    ObjectNode observation(ObjectNode c, java.util.Map<String, String> info, String filter) { return base.observation(c, info, filter); }
    ObjectNode genericContext() throws Exception {
        var c = context(9); c.withObject("/gene").put("hgncId", "HGNC:999999").put("symbol", "SYNTHETIC_GENERAL");
        c.put("contextId", adapter.canonicalVersionedContextId(c)); return c;
    }
    ObjectNode binding() {
        return mapper.createObjectNode().put("schemaVersion", GnomadLocalAllSites.VERSION).put("manifestSha256", "b".repeat(64))
                .put("dataSha256", "c".repeat(64)).put("dataset", "EXOMES").put("release", "4.1").put("assembly", "GRCh38")
                .put("stratum", "ALL_RELEASE_SAMPLES_ADJ").put("ancestryStratified", false);
    }
    ObjectNode absent(ObjectNode c) {
        var carrier = facts.facts.baseCarrier(c); carrier.withObject("/source").putArray("sourceRecords");
        carrier.withObject("/queryOutcome").put("status", "NO_RECORD").put("recordCount", 0); carrier.putArray("measurements");
        return PopulationObservation.seal(mapper, carrier, "", GnomadPopulationInputProfile.loadLocalBa1(), SOURCE, "SYNTHETIC", "SYNTHETIC_FIXTURE");
    }
    ObjectNode detection(ObjectNode c, String an, boolean outside) {
        var out = binding().put("status", "HIT").put("adjAn", an); out.set("queryAllele", c.at("/variant/evidenceQueryAllele").deepCopy());
        var flags = out.putObject("regionFlags"); for (String flag : GnomadLocalAllSites.FLAGS) flags.put(flag, outside);
        out.put("rawRecord", "chr" + c.at("/variant/evidenceQueryAllele/contig").asText() + ":" + c.at("/variant/evidenceQueryAllele/position").asText()
                + "\t" + an + "\t" + outside + "\t" + outside + "\t" + outside + "\t" + outside);
        return out;
    }
    ObjectNode evaluate(ObjectNode c, ObjectNode observation, ObjectNode allSites) {
        return new GnomadPopulationCriterionEvaluator(mapper).evaluatePm2LocalExomes(c, observation,
                Pm2LocalExomesProfile.load(mapper).withLocalAbsence(binding(), true), SOURCE, allSites);
    }
    @ParameterizedTest @CsvSource({"2002,false,MET", "2000,false,NOT_EVALUATED", "0,false,NOT_EVALUATED", "100000,true,NOT_EVALUATED"})
    void generalAbsenceUsesDetectionAndDoesNotBecomeAUniversalRareFrequencyCutoff(String an, boolean outside, String expected) throws Exception {
        var c = genericContext(); var r = evaluate(c, absent(c), detection(c, an, outside));
        assertEquals(expected, r.at("/evaluationResult/status").asText(), r.toPrettyString());
        assertEquals("MET".equals(expected) ? "SUPPORTING" : "NONE", r.path("appliedStrength").asText());
    }
    @Test void specialistAndObservedRareRecordsCannotBorrowGeneralAbsence() throws Exception {
        var c = context(0); var r = evaluate(c, absent(c), detection(c, "100000", false));
        assertEquals("PM2_LOCAL_EXOMES_POLICY_REVIEW_REQUIRED", r.at("/evaluationResult/reasonCode").asText());
        c = genericContext(); r = evaluate(c, observation(c, info("1", "100000", "0.000001"), "PASS"), detection(c, "100000", false));
        assertEquals("PM2_OBSERVED_RARITY_THRESHOLD_REQUIRED", r.at("/evaluationResult/reasonCode").asText());
    }
    @Test void wrongBindingQueryRawFlagsOrMissingLocusCannotPass() throws Exception {
        var c = genericContext();
        for (String fault : new String[]{"binding", "query", "raw", "flags", "missing", "failed"}) {
            var d = detection(c, "100000", false);
            switch (fault) {
                case "binding" -> d.put("manifestSha256", "d".repeat(64));
                case "query" -> d.withObject("/queryAllele").put("position", 1);
                case "raw" -> d.put("adjAn", "100001");
                case "flags" -> d.withObject("/regionFlags").put("outside_broad_capture_region", true);
                case "missing" -> d.put("status", "NO_RECORD");
                case "failed" -> d.put("status", "UNAVAILABLE");
            }
            var r = evaluate(c, absent(c), d); assertEquals("NOT_EVALUATED", r.at("/evaluationResult/status").asText(), fault);
        }
    }
    @Test void indelStillRequiresItsOwnDetectionProof() throws Exception {
        var key = new org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey("GRCh38", "1", 12345, "A", "AT");
        var c = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, key);
        c.withObject("/gene").put("hgncId", "HGNC:999999").put("symbol", "SYNTHETIC_GENERAL");
        c.put("contextId", adapter.canonicalVersionedContextId(c));
        var r = evaluate(c, absent(c), detection(c, "100000", false));
        assertEquals("PM2_INDEL_DETECTION_PROOF_REQUIRED", r.at("/evaluationResult/reasonCode").asText());
        assertEquals("NOT_EVALUATED", r.at("/evaluationResult/status").asText());
    }

    @Test void badDetectionConfigurationDoesNotDisableTheIndependentHitComparator() throws Exception {
        var c = context(9); var p = base.policy(9);
        var profile = Pm2LocalExomesProfile.synthetic(mapper, java.util.List.of(p));
        var failed = mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", "PM2_ALL_SITES_RESOURCE_INVALID");
        var evaluator = new GnomadPopulationCriterionEvaluator(mapper);
        assertEquals("MET", evaluator.evaluatePm2LocalExomes(c, observation(c, info("1", "2000", "0.000001"), "PASS"),
                profile, SOURCE, failed).at("/evaluationResult/status").asText());
        assertEquals("PM2_ALL_SITES_RESOURCE_INVALID", evaluator.evaluatePm2LocalExomes(c, absent(c), profile, SOURCE, failed)
                .at("/evaluationResult/reasonCode").asText());
    }
}
