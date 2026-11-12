package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossExpertFactsTest {
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode context() {
        var c = mapper.createObjectNode().put("moi", "AUTOSOMAL_DOMINANT");
        c.putObject("gene").put("hgncId", "HGNC:1").put("symbol", "TEST");
        c.putObject("disease").put("mondoId", "MONDO:0000001");
        c.putObject("transcript").put("accession", "NM_000001").put("version", "1"); return c;
    }
    ObjectNode scope() { var s = mapper.createObjectNode().put("transcriptScope", "EXACT"); s.putArray("transcripts").add("NM_000001.1"); return s; }
    ObjectNode entity(String text) {
        var e = mapper.createObjectNode(); var ld = e.putObject("ld");
        ld.putArray("Gene").addObject().putObject("entContent").putObject("HGNC").put("hgnc_id", "HGNC:1").put("symbol", "TEST");
        var content = ld.putArray("CriteriaCode").addObject().putObject("entContent").put("label", "PVS1").put("instructionsToUse", text);
        content.putArray("gene").add("TEST");
        var gene = ld.putArray("RuleSet").addObject().putObject("entContent").putArray("genes").addObject().put("gene", "TEST");
        gene.putArray("diseases").addObject().put("preferredMondoId", "MONDO:0000001").put("preferredModeOfInheritance", "Autosomal dominant inheritance");
        return e;
    }
    @Test void explicitSourceAssertionIsNotExpressionOrCodingAnnotation() {
        for (String text : new String[]{"Expression of an alternative transcript was detected.", "An alternative functional transcript may exist.",
                "If no known alternative start codon in other transcripts.", "It is not true: No known alternative start codon in other transcripts."})
            assertEquals("UNRESOLVED", Pvs1StartLossExpertFacts.read(mapper, entity(text), scope(), context()).path("status").asText());
        var result = Pvs1StartLossExpertFacts.read(mapper, entity("No known alternative start codon in other transcripts."), scope(), context());
        assertEquals("QUALIFIED", result.path("status").asText());
        assertEquals("UNRESOLVED", result.path("policyDisposition").asText(), "A source fact is not permission to apply a different rule");
    }
    @Test void alternativeExpertAssertionHasNoGeneSpecificBranchAndExactScopeIsRequired() {
        var e = entity("Initiation codon variants are not applicable due to the TEST_A alternative isoform that excludes exon 1 with an alternative start codon.");
        assertEquals("FOUND_FUNCTIONAL_ALTERNATIVE", Pvs1StartLossExpertFacts.read(mapper, e, scope(), context()).path("transcriptStatus").asText());
        var wrong = context(); wrong.withObject("transcript").put("version", "2");
        assertEquals("UNRESOLVED", Pvs1StartLossExpertFacts.read(mapper, e, scope(), wrong).path("status").asText());
        wrong = context(); wrong.withObject("disease").put("mondoId", "MONDO:0000002");
        assertEquals("UNRESOLVED", Pvs1StartLossExpertFacts.read(mapper, e, scope(), wrong).path("status").asText());
    }
    @Test void contradictoryAssertionsNeverResolveToTheLastOne() {
        String text = "No known alternative start codon in other transcripts.\nInitiation codon variants are not applicable due to the TEST_A alternative isoform that excludes exon 1 with an alternative start codon.";
        assertEquals("CONFLICT", Pvs1StartLossExpertFacts.read(mapper, entity(text), scope(), context()).path("status").asText());
        assertEquals("CONFLICT", Pvs1StartLossExpertFacts.read(mapper, entity(text.replace('\n', ' ')), scope(), context()).path("status").asText());
    }

    @Test void trailingExceptionAndUnresolvedApplicableSourceDoNotBecomeNegativeCoverage() {
        var result = Pvs1StartLossExpertFacts.read(mapper,
                entity("No known alternative start codon in other transcripts. However, this applies only if the alternate isoform is absent."), scope(), context());
        assertEquals("UNRESOLVED", result.path("status").asText());
        var assertions = mapper.createObjectNode();
        assertions.putArray("entries").add(Pvs1StartLossExpertFacts.read(mapper,
                entity("No known alternative start codon in other transcripts."), scope(), context()));
        assertions.withArray("entries").addObject().put("status", "UNRESOLVED");
        var review = Pvs1StartLossTranscriptQualifier.withExpertAssertions(mapper.createObjectNode(), assertions);
        assertEquals("UNRESOLVED", review.path("status").asText()); assertFalse(review.path("complete").asBoolean());
    }
}
