package org.nbscn.varvis.variantanalysis.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisAlleleTokenFactoryTest {

    @Test
    void tokenIsStableOpaqueAndPairSpecificWithinOneFactory() {
        try (AnalysisAlleleTokenFactory factory = new AnalysisAlleleTokenFactory()) {
            String first = factory.token(17L, 2);

            assertEquals(first, factory.token(17L, 2));
            assertTrue(first.matches("[0-9a-f]{24}"));
            assertNotEquals(first, factory.token(18L, 2));
            assertNotEquals(first, factory.token(17L, 1));
        }
    }

    @Test
    void separateFixedScopesCannotLinkTheSameSourcePair() {
        try (AnalysisAlleleTokenFactory firstFactory = new AnalysisAlleleTokenFactory(secret(0));
             AnalysisAlleleTokenFactory secondFactory = new AnalysisAlleleTokenFactory(secret(1))) {
            assertNotEquals(firstFactory.token(17L, 2), secondFactory.token(17L, 2));
        }
    }

    @Test
    void fixedProfileHasAStableKnownAnswer() {
        try (AnalysisAlleleTokenFactory factory = new AnalysisAlleleTokenFactory(secret(0))) {
            assertEquals("587c46380f436f66ae42f282",
                    factory.token(0x0102030405060708L, 0x01020304));
        }
    }

    @Test
    void fixedSecretIsDefensivelyCopiedAndMustBeExactly256Bits() {
        byte[] secret = secret(0);
        try (AnalysisAlleleTokenFactory factory = new AnalysisAlleleTokenFactory(secret)) {
            String beforeMutation = factory.token(1L, 1);
            java.util.Arrays.fill(secret, (byte) 0x7f);

            assertEquals(beforeMutation, factory.token(1L, 1));
        }
        assertInvalidSecret(null);
        assertInvalidSecret(new byte[31]);
        assertInvalidSecret(new byte[33]);
    }

    @Test
    void rejectsNonPositiveSourceRecordOrdinalWithStableCode() {
        try (AnalysisAlleleTokenFactory factory = new AnalysisAlleleTokenFactory()) {
            assertInvalidRecordOrdinal(factory, 0L);
            assertInvalidRecordOrdinal(factory, -1L);
        }
    }

    @Test
    void rejectsNonPositiveSourceAltIndexWithStableCode() {
        try (AnalysisAlleleTokenFactory factory = new AnalysisAlleleTokenFactory()) {
            assertInvalidAltIndex(factory, 0);
            assertInvalidAltIndex(factory, -1);
        }
    }

    @Test
    void closeIsIdempotentClearsTheScopeAndPreventsReuse() throws Exception {
        AnalysisAlleleTokenFactory factory = new AnalysisAlleleTokenFactory(secret(1));
        factory.token(1L, 1);
        java.lang.reflect.Field secretField = AnalysisAlleleTokenFactory.class
                .getDeclaredField("scopeSecret");
        secretField.setAccessible(true);
        byte[] internalSecret = (byte[]) secretField.get(factory);

        factory.close();
        factory.close();

        assertArrayEquals(new byte[32], internalSecret);
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> factory.token(1L, 1)
        );
        assertEquals("ANALYSIS_ALLELE_TOKEN_FACTORY_CLOSED", failure.getMessage());
    }

    private void assertInvalidRecordOrdinal(AnalysisAlleleTokenFactory factory, long ordinal) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> factory.token(ordinal, 1)
        );
        assertEquals(
                "ANALYSIS_ALLELE_TOKEN_SOURCE_RECORD_ORDINAL_INVALID",
                failure.getMessage()
        );
    }

    private void assertInvalidAltIndex(AnalysisAlleleTokenFactory factory, int altIndex) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> factory.token(1L, altIndex)
        );
        assertEquals("ANALYSIS_ALLELE_TOKEN_SOURCE_ALT_INDEX_INVALID", failure.getMessage());
    }

    private void assertInvalidSecret(byte[] value) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new AnalysisAlleleTokenFactory(value)
        );
        assertEquals("ANALYSIS_ALLELE_TOKEN_SCOPE_SECRET_INVALID", failure.getMessage());
    }

    private byte[] secret(int offset) {
        byte[] value = new byte[32];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (index + offset);
        }
        return value;
    }
}
