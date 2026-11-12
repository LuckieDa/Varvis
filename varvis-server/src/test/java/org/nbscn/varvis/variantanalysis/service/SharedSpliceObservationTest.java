package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.provider.SharedSpliceObservation;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SharedSpliceObservationTest {
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode raw() {
        var r = mapper.createObjectNode().put("schemaVersion", SharedSpliceObservation.SOURCE_SCHEMA);
        r.putObject("queryAllele").put("assembly", "GRCh38").put("contig", "1").put("position", 100).put("reference", "A").put("alternate", "G");
        r.putObject("resource").put("model", "SPLICEAI").put("scoreMode", "MASKED").put("version", "SYNTHETIC");
        r.putObject("resourceExecution").put("status", "SUCCEEDED");
        r.putObject("resourceAvailability").put("status", "AVAILABLE");
        r.putObject("resourceApplicability").put("status", "APPLICABLE");
        r.putObject("queryOutcome").put("status", "HIT");
        var row = r.putArray("records").addObject().put("allele", "G").put("geneSymbol", "SYNTHETIC");
        for (String kind : List.of("AG", "AL", "DG", "DL")) row.put("DS_" + kind, "0.10000000000000000001").put("DP_" + kind, -3);
        row.put("originalRecord", "unaltered synthetic raw row");
        return r;
    }
    ExplicitContextSourceLedger source(ObjectNode r) throws java.io.IOException {
        var envelope = mapper.createObjectNode().put("source", "SYNTHETIC_SPLICE").put("version", "1").put("dataClass", "SYNTHETIC");
        envelope.putObject("records").set("fact", r);
        return ExplicitContextSourceLedger.create(mapper, envelope);
    }
    SharedSpliceObservation capture(ObjectNode raw) throws java.io.IOException {
        var ledger = source(raw);
        return SharedSpliceObservation.capture(mapper, raw.path("queryAllele"), ledger.reference("fact"), List.of(ledger));
    }

    @Test void retainsPrecisionMissingIdentityAndMaskedModeWithoutGrantingApproval() throws Exception {
        var raw = raw(); var observation = capture(raw); var snapshot = observation.snapshot();
        assertEquals(raw, snapshot.path("sourceRecord"));
        assertEquals("NOT_EVALUATED", snapshot.path("applicationStatus").asText());
        assertEquals("NOT_ESTABLISHED", snapshot.path("rnaApproval").asText());
        assertFalse(snapshot.at("/sourceRecord/resource").has("distance"));
        assertFalse(snapshot.at("/sourceRecord/records/0").has("transcriptId"));
        assertEquals("0.10000000000000000001", observation.events(0).at("/events/0/rawScore").asText());
        assertEquals(97, observation.events(0).at("/events/0/genomicPosition").asLong());
        snapshot.removeAll(); raw.removeAll();
        assertEquals("MASKED", observation.snapshot().at("/sourceRecord/resource/scoreMode").asText());
    }

    @Test void requiresExactLedgerReferenceAndQuery() throws Exception {
        var r = raw(); var ledger = source(r); var ref = ledger.reference("fact");
        var wrong = ((ObjectNode)r.path("queryAllele")).deepCopy().put("position", 101);
        assertThrows(IllegalArgumentException.class, () -> SharedSpliceObservation.capture(mapper, wrong, ref, List.of(ledger)));
        assertThrows(IllegalArgumentException.class, () -> SharedSpliceObservation.capture(mapper, r.path("queryAllele"), ref, List.of()));
        assertThrows(IllegalArgumentException.class, () -> SharedSpliceObservation.capture(mapper, r.path("queryAllele"), ref, List.of(ledger, ledger)));
    }

    @Test void failedMissingNumericAndWrongAlleleRecordsNeverBecomeParsedFacts() throws Exception {
        var failed = raw(); failed.withObject("/resourceExecution").put("status", "FAILED");
        assertEquals("NOT_EVALUATED", capture(failed).events(0).path("status").asText());
        var missing = raw(); missing.withObject("/records/0").remove("DS_AG");
        assertEquals("SPLICE_SCORE_ORIGINAL_PRECISION_REQUIRED", capture(missing).events(0).path("reasonCode").asText());
        var numeric = raw(); numeric.withObject("/records/0").put("DS_AG", 0.1);
        assertThrows(IllegalArgumentException.class, () -> capture(numeric), "Binary floating point cannot acquire a canonical identity");
        var wrong = raw(); wrong.withObject("/records/0").put("allele", "T");
        assertEquals("SPLICE_RECORD_ALLELE_MISMATCH", capture(wrong).events(0).path("reasonCode").asText());
        assertThrows(IllegalArgumentException.class, () -> capture(raw()).events(1));
    }
}
