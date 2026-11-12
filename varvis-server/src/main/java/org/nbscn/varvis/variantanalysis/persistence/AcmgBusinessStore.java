package org.nbscn.varvis.variantanalysis.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Private server-owned persistence. Neither source documents nor requests have a public read route. */
public class AcmgBusinessStore {
    private final JdbcTemplate jdbc;
    private final String sourceTable;
    private final String runTable;
    private final String interpretationTable;
    private final AcmgResultChunks chunks;
    private final org.springframework.transaction.support.TransactionTemplate transaction;
    private Boolean mysql;
    private final java.util.LinkedHashMap<String, String> readCache = new java.util.LinkedHashMap<>(16, .75f, true);
    private int cachedCharacters;
    private synchronized boolean mysql() {
        if (mysql == null) mysql = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)
                c -> "MySQL".equalsIgnoreCase(c.getMetaData().getDatabaseProductName()));
        return Boolean.TRUE.equals(mysql);
    }
    private synchronized String cached(String hash) { return readCache.get(hash); }
    private synchronized void cache(String hash, String value) {
        if (hash == null || value.length() > 12 * 1024 * 1024) return;
        String old = readCache.put(hash, value);
        cachedCharacters += value.length() - (old == null ? 0 : old.length());
        while (cachedCharacters > 16 * 1024 * 1024) {
            var first = readCache.entrySet().iterator();
            cachedCharacters -= first.next().getValue().length(); first.remove();
        }
    }
    public AcmgBusinessStore(DataSource dataSource) { this(dataSource, ""); }
    public AcmgBusinessStore(DataSource dataSource, String prefix) {
        String checked = new VariantAnalysisTableNames(prefix).prefix();
        jdbc = new JdbcTemplate(java.util.Objects.requireNonNull(dataSource)); sourceTable = checked + "variant_analysis_acmg_source"; runTable = checked + "variant_analysis_acmg_run";
        // MySQL metadata locks can wait far longer than the HTTP/model deadline. Bound each SQL
        // statement, including SELECT FOR UPDATE; a pool acquisition timeout does not cover this.
        jdbc.setQueryTimeout(30);
        interpretationTable = checked + "variant_analysis_interpretation";
        chunks = new AcmgResultChunks(jdbc, checked + "variant_analysis_acmg_chunk");
        transaction = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
    }
    /** Serialize BA1 reuse and result publication across service instances. Keep long model execution outside. */
    public <T> T withAnalysisLock(String analysisId, java.util.function.Supplier<T> action) {
        return transaction.execute(status -> {
            jdbc.query("SELECT analysis_id FROM " + sourceTable + " WHERE analysis_id=? FOR UPDATE", (r, n) -> r.getString(1), analysisId);
            return action.get();
        });
    }
    public record Source(String sha256, String json) { }
    public record Run(String id, String analysisId, String requestSha256, String requestJson,
                      String status, String createdAt, String responseSha256, String responseJson, String privateJson) { }

    public void saveCandidateAnnotationPayload(String analysisId, String candidateId, String payload) {
        // Publication only, before READY. The existing allele-wide updater would also overwrite
        // completed same-allele siblings; update precisely the selected candidate's payload column.
        if (jdbc.update("UPDATE " + interpretationTable + " SET payload_json=? WHERE analysis_id=? AND candidate_id=?",
                payload, analysisId, candidateId) != 1) throw new IllegalStateException("ACMG_ANNOTATION_SAVE_FAILED");
    }

    public void saveSource(String analysisId, String json) {
        // A published analysis is immutable for this slice; a second publication cannot silently replace provenance.
        jdbc.update("INSERT INTO " + sourceTable + " (analysis_id,snapshot_sha256,snapshot_json) VALUES (?,?,?)",
                analysisId, sha(json), json);
    }
    public Optional<Source> source(String id) {
        var hashes = jdbc.query("SELECT snapshot_sha256 FROM " + sourceTable + " WHERE analysis_id=?",
                (r, n) -> r.getString(1), id);
        if (hashes.isEmpty()) return Optional.empty();
        String expected = hashes.get(0);
        String json = readText(sourceTable, "snapshot_json", " WHERE analysis_id=? AND snapshot_sha256=?",
                new Object[]{id, expected});
        if (json == null || !sha(json).equals(expected)) throw new IllegalStateException("ACMG_SOURCE_IDENTITY_CONFLICT");
        return Optional.of(new Source(expected, json));
    }

    /** Serialize new workbench operations; a transport repeat keeps its original run. */
    public boolean claimSelected(Run run) {
        return withAnalysisLock(run.analysisId(), () -> {
            if (countRuns(" WHERE analysis_id=? AND request_sha256=?", run.analysisId(), run.requestSha256()) > 0) return false;
            if (countRuns(" WHERE analysis_id=? AND status='RUNNING'", run.analysisId()) > 0) throw new IllegalStateException("ACMG_ANALYSIS_ALREADY_RUNNING");
            return claim(run);
        });
    }
    private int countRuns(String conditions, Object... args) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + runTable + conditions, Integer.class, args);
        if (count == null) throw new IllegalStateException("ACMG_RUN_COUNT_UNAVAILABLE");
        return count;
    }

    public boolean claim(Run run) {
        try {
            jdbc.update("INSERT INTO " + runTable + " (run_id,analysis_id,request_sha256,request_json,status,created_at) VALUES (?,?,?,?,?,?)",
                    run.id(), run.analysisId(), run.requestSha256(), run.requestJson(), "RUNNING", run.createdAt());
            return true;
        } catch (DuplicateKeyException duplicate) { return false; }
    }
    public Optional<Run> byRequest(String analysisId, String hash) {
        return rows("analysis_id=? AND request_sha256=?", analysisId, hash).stream().findFirst();
    }
    public Optional<Run> run(String analysisId, String id) {
        return rows("analysis_id=? AND run_id=?", analysisId, id).stream().findFirst();
    }
    public List<Run> runs(String analysisId) {
        return jdbc.query("SELECT run_id,status,created_at FROM " + runTable + " WHERE analysis_id=? ORDER BY created_at DESC,run_id DESC LIMIT 100",
                (r, n) -> new Run(r.getString(1), analysisId, null, null, r.getString(2), r.getString(3), null, null, null), analysisId);
    }
    public Optional<Run> metadata(String analysisId, String id) {
        return jdbc.query("SELECT run_id,request_sha256,status,created_at,response_sha256 FROM " + runTable + " WHERE analysis_id=? AND run_id=?",
                (r,n) -> new Run(r.getString(1),analysisId,r.getString(2),null,r.getString(3),r.getString(4),r.getString(5),null,null),analysisId,id).stream().findFirst();
    }
    /** A read-cache key computed from live bytes. Stored/claimed digests alone cannot detect corruption. */
    public String resultFingerprint(String analysisId, String id) {
        boolean serverHash = mysql();
        String columns = serverHash
                ? "SHA2(request_json,256),SHA2(response_json,256),SHA2(private_json,256),LEFT(response_json,24),LEFT(private_json,24)"
                : "request_json,response_json,private_json,LEFT(response_json,24),LEFT(private_json,24)";
        var rows = jdbc.query("SELECT status,request_sha256,response_sha256," + columns + " FROM " + runTable
                + " WHERE analysis_id=? AND run_id=?", (r,n) -> {
            var key = new StringBuilder(analysisId).append(':').append(id);
            for (int i=1;i<=6;i++) {
                String value = r.getString(i);
                key.append('|').append(value == null ? "NULL" : i>=4 && !serverHash ? sha(value) : value);
            }
            return new String[]{key.toString(),r.getString(7),r.getString(8)};
        },analysisId,id);
        if (rows.size()!=1) throw new IllegalArgumentException("ACMG_RUN_NOT_FOUND");
        var row = rows.get(0); var key = new StringBuilder(row[0]);
        for (int i=1;i<=2;i++) if (row[i]!=null && row[i].startsWith("VARVIS_CHUNKED_JSON_V1:")) {
            String column=i==1?"response_json":"private_json";
            var parts=jdbc.query("SELECT chunk_index,payload_sha256," + (serverHash?"SHA2(payload_text,256)":"payload_text")
                    + " FROM " + chunks.tableName() + " WHERE run_id=? AND column_name=? ORDER BY chunk_index",
                    (r,n)->r.getInt(1)+":"+r.getString(2)+":"+(serverHash || r.getString(3)==null?r.getString(3):sha(r.getString(3))),id,column);
            key.append('|').append(column).append(parts);
        }
        return sha(key.toString());
    }
    public String controlData(String analysis, String run) {
        var values = jdbc.query("SELECT CASE WHEN CHAR_LENGTH(private_json)<=8192 THEN private_json ELSE NULL END FROM " + runTable
                + " WHERE analysis_id=? AND run_id=? AND status='RUNNING' AND response_sha256 IS NULL",
                (r,n)->r.getString(1),analysis,run);
        return values.isEmpty()?null:values.get(0);
    }
    public void saveControl(String analysis, String run, String value) {
        if (value.length()>8192 || jdbc.update("UPDATE " + runTable + " SET private_json=? WHERE analysis_id=? AND run_id=? AND status='RUNNING' AND response_sha256 IS NULL",value,analysis,run)!=1)
            throw new IllegalStateException("ACMG_CONTROL_STATE_CHANGED");
    }
    public void endControlled(String run, String state) {
        if (!List.of("CANCELLED","INTERRUPTED").contains(state)) throw new IllegalArgumentException("ACMG_CONTROL_STATE_INVALID");
        jdbc.update("UPDATE " + runTable + " SET status=? WHERE run_id=? AND status='RUNNING' AND response_sha256 IS NULL",state,run);
    }
    /** Complete bounded case index, separate from the display list's silent LIMIT 100. */
    public List<Run> effectScopeRuns(String analysisId) {
        var index = jdbc.query("SELECT run_id,request_sha256,status,created_at,response_sha256 FROM " + runTable
                + " WHERE analysis_id=? ORDER BY created_at,run_id LIMIT 1001",
                (r, n) -> new Run(r.getString(1), analysisId, r.getString(2), null, r.getString(3), r.getString(4), r.getString(5), null, null), analysisId);
        // Requests can contain complete literature snapshots. Never transfer every historical
        // request in one unbounded JDBC result; use the same fingerprinted bounded reader as
        // saved results. Still verify every request before deciding it is non-functional.
        if (index.size() > 1000) return index;
        return index.stream().map(row -> new Run(row.id(), analysisId, row.requestSha256(),
                readColumn(row, "request_json"), row.status(), row.createdAt(), row.responseSha256(), null, null)).toList();
    }
    private List<Run> rows(String predicate, Object... args) {
        var metadata = jdbc.query("SELECT run_id,analysis_id,request_sha256,status,created_at,response_sha256 FROM " + runTable + " WHERE " + predicate,
                (r, n) -> new Run(r.getString(1), r.getString(2), r.getString(3), null, r.getString(4),
                        r.getString(5), r.getString(6), null, null), args);
        return metadata.stream().map(row -> new Run(row.id(), row.analysisId(), row.requestSha256(),
                readColumn(row, "request_json"), row.status(), row.createdAt(), row.responseSha256(),
                AcmgStoredJson.decode(chunks.read(row.id(), "response_json", readColumn(row, "response_json"))),
                AcmgStoredJson.decode(chunks.read(row.id(), "private_json", readColumn(row, "private_json"))))).toList();
    }
    /** Bound each network transfer, without extending SQL timeouts or changing server configuration.
     * The metadata predicate prevents mixing a RUNNING snapshot with a concurrently committed result.
     * Callers still validate the complete public/private/request SHA chain after reassembly. */
    private String readColumn(Run row, String column) {
        String where = " WHERE analysis_id=? AND run_id=? AND status=? AND request_sha256=?"
                + " AND (response_sha256=? OR (response_sha256 IS NULL AND ? IS NULL))";
        Object[] identity = {row.analysisId(), row.id(), row.status(), row.requestSha256(), row.responseSha256(), row.responseSha256()};
        return readText(runTable, column, where, identity);
    }
    private String readText(String table, String column, String where, Object[] identity) {
        var lengths = jdbc.query("SELECT CHAR_LENGTH(" + column + ") FROM " + table + where,
                (r, n) -> { int length = r.getInt(1); return r.wasNull() ? null : length; }, identity);
        if (lengths.size() != 1) throw new IllegalStateException("ACMG_SAVED_RESULT_CHANGED_DURING_READ");
        Integer length = lengths.get(0);
        if (length == null) return null;
        if (length < 0 || length > AcmgStoredJson.MAX_STORED) throw new IllegalStateException("ACMG_STORED_JSON_SIZE_LIMIT");
        // Verify the live database bytes even on a cache hit; never hide mutation/corruption.
        String hash = mysql() ? jdbc.queryForObject("SELECT SHA2(" + column + ",256) FROM " + table + where, String.class, identity) : null;
        if (hash != null) { String hit = cached(hash); if (hit != null) return hit; }
        var value = new StringBuilder(Math.min(length, 1048576));
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(5).toNanos();
        int position = 1;
        while (position <= length) {
            long checkpointStart = System.nanoTime();
            org.nbscn.varvis.variantanalysis.service.AcmgRunControl.checkpoint();
            deadline += System.nanoTime() - checkpointStart; // User pause is not database read time.
            if (System.nanoTime() > deadline) throw new IllegalStateException("ACMG_RESULT_READ_BUDGET_EXCEEDED");
            int count = Math.min(262144, length - position + 1);
            Object[] parameters = new Object[identity.length + 2];
            parameters[0] = position; parameters[1] = count;
            System.arraycopy(identity, 0, parameters, 2, identity.length);
            var chunks = jdbc.query("SELECT SUBSTRING(" + column + ",?,?) FROM " + table + where,
                    (r, n) -> r.getString(1), parameters);
            if (chunks.size() != 1 || chunks.get(0) == null || chunks.get(0).isEmpty())
                throw new IllegalStateException("ACMG_SAVED_RESULT_CHANGED_DURING_READ");
            value.append(chunks.get(0)); position += count;
        }
        String complete = value.toString();
        if (hash != null && !hash.equals(sha(complete))) throw new IllegalStateException("ACMG_SAVED_RESULT_CHANGED_DURING_READ");
        cache(hash, complete);
        return complete;
    }
    public void finish(String id, String status, String response, String privateJson) {
        String storedResponse = AcmgStoredJson.encode(response), storedPrivate = AcmgStoredJson.encode(privateJson);
        if (storedResponse.length() > AcmgResultChunks.INLINE_LIMIT
                || (storedPrivate != null && storedPrivate.length() > AcmgResultChunks.INLINE_LIMIT)) {
            // Keep each transfer bounded. Publish status only after both complete columns have
            // been verified; rollback leaves no partial result, including when called standalone.
            transaction.executeWithoutResult(tx -> {
                if (jdbc.update("UPDATE " + runTable + " SET response_json='',private_json=? WHERE run_id=? AND status='RUNNING'",
                        storedPrivate == null ? null : "", id) != 1) throw new IllegalStateException("ACMG_RESULT_SAVE_FAILED");
                String responseRef = chunks.write(id, "response_json", storedResponse);
                String privateRef = chunks.write(id, "private_json", storedPrivate);
                if (jdbc.update("UPDATE " + runTable + " SET status=?,response_sha256=?,response_json=?,private_json=? WHERE run_id=? AND status='RUNNING'",
                        status, sha(response), responseRef, privateRef, id) != 1) throw new IllegalStateException("ACMG_RESULT_SAVE_FAILED");
            });
            return;
        }
        if (jdbc.update("UPDATE " + runTable + " SET status=?,response_sha256=?,response_json=?,private_json=? WHERE run_id=? AND status='RUNNING'",
                status, sha(response), storedResponse, storedPrivate, id) != 1) throw new IllegalStateException("ACMG_RESULT_SAVE_FAILED");
    }
    public void failed(String id) {
        jdbc.update("UPDATE " + runTable + " SET status='FAILED' WHERE run_id=? AND status='RUNNING'", id);
    }
    public static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
