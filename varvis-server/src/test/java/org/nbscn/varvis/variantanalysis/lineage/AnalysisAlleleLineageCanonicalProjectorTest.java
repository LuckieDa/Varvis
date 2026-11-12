package org.nbscn.varvis.variantanalysis.lineage;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Document;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.FooterRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecordStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RecordType;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnalysisAlleleLineageCanonicalProjectorTest {

    private final AnalysisAlleleLineageCanonicalProjector projector =
            new AnalysisAlleleLineageCanonicalProjector();

    @Test
    void projectsFieldsInContractOrderWithExplicitNullsAndNoWhitespace() {
        String header = new String(projector.canonicalRecordBytes(
                AnalysisAlleleLineageWireFixtures.header()), StandardCharsets.UTF_8);

        assertTrue(header.startsWith(
                "{\"schemaVersion\":\"analysis-allele-lineage-v1\","
                        + "\"recordType\":\"HEADER\",\"recordOrdinal\":0,"
                        + "\"generatorVersion\":\"varvis-lineage-writer-v1\""));
        assertTrue(header.contains("\"liftoverTool\":null"));
        assertFalse(header.contains("\n"));
        assertFalse(header.contains("  "));
    }

    @Test
    void sealIsDeterministicAndDoesNotMutateDraft() {
        Document sealed = AnalysisAlleleLineageWireFixtures.validDocument();
        FooterRecord draftFooter = sealed.footer().withContentFingerprint(null);
        Document draft = sealed.withFooter(draftFooter);

        Document first = projector.seal(draft);
        Document second = projector.seal(draft);

        assertNull(draft.footer().contentFingerprint());
        assertEquals(first.footer().contentFingerprint(), second.footer().contentFingerprint());
        assertEquals(64, first.footer().contentFingerprint().length());
    }

    @Test
    void fingerprintProjectionOmitsEntireFooterFingerprintProperty() {
        Document sealed = AnalysisAlleleLineageWireFixtures.validDocument();
        String supplied = sealed.footer().contentFingerprint();
        String recomputed = projector.contentFingerprint(
                sealed.withFooter(sealed.footer().withContentFingerprint("0".repeat(64))));

        assertEquals(supplied, recomputed);
        String physicalFooter = new String(
                projector.canonicalRecordBytes(sealed.footer()), StandardCharsets.UTF_8);
        assertTrue(physicalFooter.endsWith(
                "\"contentFingerprint\":\"" + supplied + "\"}"));
    }

    @Test
    void canonicalProjectionHasStableKnownAnswer() {
        Document document = AnalysisAlleleLineageWireFixtures.validDocument();

        assertEquals("5567d1e2e74b6aa96327b74139fe56e072faa40ebc2eed4b4bd88a8f2f40de17",
                document.footer().contentFingerprint());
        assertArrayEquals(
                projector.canonicalRecordBytes(document.footer()),
                projector.canonicalRecordBytes(document.footer()));
    }

    @Test
    void nullArrayCannotAliasCanonicalEmptyArray() {
        InputRecord invalid = new InputRecord(
                AnalysisAlleleLineageWire.SCHEMA_VERSION,
                RecordType.INPUT_RECORD,
                1,
                1,
                1,
                InputRecordStatus.TARGETS_CAPTURED,
                null);

        LineageValidationException exception = assertThrows(
                LineageValidationException.class,
                () -> projector.canonicalRecordBytes(invalid));

        assertEquals(AnalysisAlleleLineageWire.ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING,
                exception.getReasonCode());
        assertEquals("LINEAGE_REQUIRED_FIELD_MISSING:reasonCodes", exception.getMessage());
    }
}
