package org.nbscn.varvis.variantanalysis.persistence;

import static java.util.Objects.requireNonNull;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class AcmgBusinessStoreReadTest {
    @Test void largeTwoColumnWriteIsAtomicAndPreservesBytes() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:large" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        try {
            jdbc.execute("CREATE TABLE variant_analysis_acmg_run (run_id VARCHAR PRIMARY KEY, analysis_id VARCHAR, request_sha256 VARCHAR, request_json CLOB, status VARCHAR, created_at VARCHAR, response_sha256 VARCHAR, response_json CLOB, private_json CLOB, CONSTRAINT inline_limit CHECK (CHAR_LENGTH(private_json)<1000000 AND CHAR_LENGTH(response_json)<1000000))");
            jdbc.execute("CREATE TABLE variant_analysis_acmg_chunk (run_id VARCHAR,column_name VARCHAR,chunk_index INT,payload_sha256 VARCHAR,payload_text CLOB,PRIMARY KEY(run_id,column_name,chunk_index),CONSTRAINT private_limit CHECK (column_name<>'private_json'),CONSTRAINT chunk_limit CHECK (CHAR_LENGTH(payload_text)<=262144))");
            var store = new AcmgBusinessStore(ds);
            byte[] random = new byte[5 * 1024 * 1024]; new java.util.Random(218).nextBytes(random);
            String response = java.util.Base64.getEncoder().encodeToString(random), privateJson = response + "独立私有尾部";
            assertTrue(AcmgStoredJson.encode(response).length() + AcmgStoredJson.encode(privateJson).length() > 12 * 1024 * 1024);
            assertTrue(store.claim(new AcmgBusinessStore.Run("r", "a", "hash", "{}", "RUNNING", "now", null, null, null)));
            assertThrows(org.springframework.dao.DataAccessException.class, () -> store.finish("r", "COMPLETED", response, privateJson));
            var failedWrite = store.run("a", "r").orElseThrow();
            assertEquals("RUNNING", failedWrite.status()); assertNull(failedWrite.responseJson());
            assertNull(failedWrite.privateJson()); assertNull(failedWrite.responseSha256());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM variant_analysis_acmg_chunk", Integer.class));
            jdbc.execute("ALTER TABLE variant_analysis_acmg_chunk DROP CONSTRAINT private_limit");
            store.finish("r", "COMPLETED", response, privateJson);
            var saved = store.run("a", "r").orElseThrow();
            assertEquals("COMPLETED", saved.status()); assertEquals(response, saved.responseJson());
            assertEquals(privateJson, saved.privateJson()); assertEquals(AcmgBusinessStore.sha(response), saved.responseSha256());
            String fingerprint = store.resultFingerprint("a", "r");
            assertEquals(fingerprint, store.resultFingerprint("a", "r"));
            assertThrows(IllegalArgumentException.class, () -> store.resultFingerprint("another-analysis", "r"));
            // Corrupt a part while preserving the old digest, then remove a part. Neither may read as success.
            String original = jdbc.queryForObject("SELECT payload_text FROM variant_analysis_acmg_chunk WHERE run_id='r' AND column_name='private_json' AND chunk_index=0", String.class);
            jdbc.update("UPDATE variant_analysis_acmg_chunk SET payload_text=? WHERE run_id='r' AND column_name='private_json' AND chunk_index=0", "!" + requireNonNull(original).substring(1));
            assertNotEquals(fingerprint, store.resultFingerprint("a", "r"));
            assertThrows(IllegalStateException.class, () -> store.run("a", "r"));
            jdbc.update("DELETE FROM variant_analysis_acmg_chunk WHERE run_id='r' AND column_name='private_json' AND chunk_index=0");
            assertThrows(IllegalStateException.class, () -> store.run("a", "r"));
        } finally { jdbc.execute("SHUTDOWN"); }
    }
    @Test void sourceReadIsBoundedAndRejectsChangedBytesWithAnOldHash() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:source" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        try {
            jdbc.execute("CREATE TABLE variant_analysis_acmg_source (analysis_id VARCHAR PRIMARY KEY, snapshot_sha256 VARCHAR, snapshot_json CLOB)");
            var store = new AcmgBusinessStore(ds);
            String source = "汉字😀abc".repeat(90000);
            assertTrue(store.source("missing").isEmpty());
            store.saveSource("a", source);
            assertEquals(source, store.source("a").orElseThrow().json());
            assertEquals(source, store.source("a").orElseThrow().json());
            jdbc.update("UPDATE variant_analysis_acmg_source SET snapshot_json='changed' WHERE analysis_id='a'");
            assertEquals("ACMG_SOURCE_IDENTITY_CONFLICT", assertThrows(IllegalStateException.class, () -> store.source("a")).getMessage());
        } finally { jdbc.execute("SHUTDOWN"); }
    }
    @Test void chunkedLegacyAndCompressedResultsRetainAllCharactersAndDetectUpdatedBytes() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:read" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        try {
            jdbc.execute("CREATE TABLE variant_analysis_acmg_run (run_id VARCHAR PRIMARY KEY, analysis_id VARCHAR, request_sha256 VARCHAR, request_json CLOB, status VARCHAR, created_at VARCHAR, response_sha256 VARCHAR, response_json CLOB, private_json CLOB)");
            var store = new AcmgBusinessStore(ds);
            String request = "汉字abc".repeat(90000), response = "结果abcd".repeat(120000);
            assertTrue(store.claim(new AcmgBusinessStore.Run("r", "a", AcmgBusinessStore.sha(request), request, "RUNNING", "now", null, null, null)));
            assertEquals(request, store.run("a", "r").orElseThrow().requestJson());
            assertNull(store.run("a", "r").orElseThrow().responseJson());
            store.finish("r", "COMPLETED", response, request);
            var saved = store.run("a", "r").orElseThrow();
            assertEquals(response, saved.responseJson()); assertEquals(request, saved.privateJson());
            String fingerprint = store.resultFingerprint("a", "r");
            jdbc.update("UPDATE variant_analysis_acmg_run SET response_json=? WHERE run_id='r'", response);
            assertEquals(response, store.run("a", "r").orElseThrow().responseJson());
            jdbc.update("UPDATE variant_analysis_acmg_run SET private_json='changed' WHERE run_id='r'");
            assertNotEquals(fingerprint, store.resultFingerprint("a", "r"));
            assertEquals("changed", store.run("a", "r").orElseThrow().privateJson());
        } finally { jdbc.execute("SHUTDOWN"); }
    }
}
