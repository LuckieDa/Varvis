package org.nbscn.varvis.variantanalysis.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

/** Transaction-owned storage framing. References never appear in the public result. */
final class AcmgResultChunks {
    static final int INLINE_LIMIT = 1024 * 1024;
    static final int CHUNK_SIZE = 262144;
    private static final String PREFIX = "VARVIS_CHUNKED_JSON_V1:";
    private final JdbcTemplate jdbc;
    private final String table;

    AcmgResultChunks(JdbcTemplate jdbc, String table) { this.jdbc = jdbc; this.table = table; }
    String tableName() { return table; }

    String write(String runId, String column, String stored) {
        if (stored == null || stored.length() <= INLINE_LIMIT) return stored;
        if (stored.length() > AcmgStoredJson.MAX_STORED) throw new IllegalStateException("ACMG_STORED_JSON_SIZE_LIMIT");
        // Large encodings are ASCII gzip/base64; no UTF-8 character can be split here.
        int count = (stored.length() + CHUNK_SIZE - 1) / CHUNK_SIZE;
        for (int index = 0; index < count; index++) {
            String part = stored.substring(index * CHUNK_SIZE, Math.min(stored.length(), (index + 1) * CHUNK_SIZE));
            String digest = AcmgBusinessStore.sha(part);
            if (jdbc.update("INSERT INTO " + table + " (run_id,column_name,chunk_index,payload_sha256,payload_text) VALUES (?,?,?,?,?)",
                    runId, column, index, digest, part) != 1) throw new IllegalStateException("ACMG_RESULT_SAVE_FAILED");
            // Verify each actual stored part without a server-side CONCAT beyond max_allowed_packet.
            var read = jdbc.query("SELECT payload_text FROM " + table + " WHERE run_id=? AND column_name=? AND chunk_index=?",
                    (r, n) -> r.getString(1), runId, column, index);
            if (read.size() != 1 || read.get(0) == null || !digest.equals(AcmgBusinessStore.sha(read.get(0))))
                throw new IllegalStateException("ACMG_STORED_WRITE_INTEGRITY_FAILED");
        }
        return PREFIX + stored.length() + ":" + count + ":" + AcmgBusinessStore.sha(stored);
    }

    String read(String runId, String column, String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        try {
            String[] parts = stored.substring(PREFIX.length()).split(":", -1);
            if (parts.length != 3) throw new IllegalArgumentException();
            int length = Integer.parseInt(parts[0]), count = Integer.parseInt(parts[1]);
            if (length <= INLINE_LIMIT || length > AcmgStoredJson.MAX_STORED
                    || count != (length + CHUNK_SIZE - 1) / CHUNK_SIZE || !parts[2].matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException();
            Integer actual = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE run_id=? AND column_name=?",
                    Integer.class, runId, column);
            if (actual == null || actual != count) throw new IllegalArgumentException();
            var result = new StringBuilder(); long deadline = System.nanoTime() + java.time.Duration.ofMinutes(5).toNanos();
            for (int index = 0; index < count; index += 4) {
                if (System.nanoTime() > deadline) throw new IllegalArgumentException();
                int end = Math.min(count, index + 4);
                // At most 1 MiB per response; retain per-part length/order/hash and final envelope checks.
                var rows = jdbc.query("SELECT chunk_index,payload_sha256,CASE WHEN CHAR_LENGTH(payload_text)<=? THEN payload_text ELSE NULL END FROM "
                                + table + " WHERE run_id=? AND column_name=? AND chunk_index>=? AND chunk_index<? ORDER BY chunk_index",
                        (r, n) -> new String[]{r.getString(1), r.getString(2), r.getString(3)}, CHUNK_SIZE, runId, column, index, end);
                if (rows.size() != end-index)
                    throw new IllegalArgumentException();
                for (int offset=0;offset<rows.size();offset++) {
                    String[] row=rows.get(offset); int part=index+offset;
                    if (!Integer.toString(part).equals(row[0]) || row[2]==null
                            || row[2].length()!=Math.min(CHUNK_SIZE,length-part*CHUNK_SIZE)
                            || !AcmgBusinessStore.sha(row[2]).equals(row[1])) throw new IllegalArgumentException();
                    result.append(row[2]);
                }
            }
            String complete = result.toString();
            if (complete.length() != length || !AcmgBusinessStore.sha(complete).equals(parts[2])) throw new IllegalArgumentException();
            return complete;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("ACMG_STORED_CHUNK_INTEGRITY_FAILED", invalid);
        }
    }
}
