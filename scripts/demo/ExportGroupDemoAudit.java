import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

/** Read-only export of this local teaching database after the demo server is stopped. */
class ExportGroupDemoAudit {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: <runtime-root> <analysis-id> <run-id>");
        Path root = Path.of(args[0]).toRealPath();
        Path database = root.resolve("database/varvis-demo");
        if (!Path.of(database + ".mv.db").toRealPath().startsWith(root))
            throw new IllegalArgumentException("Database is outside the demo root");
        var mapper = new ObjectMapper();
        var archive = mapper.createObjectNode();
        String url = "jdbc:h2:file:" + database.toString().replace('\\', '/')
                + ";MODE=MySQL;IFEXISTS=TRUE;ACCESS_MODE_DATA=r";
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            connection.setReadOnly(true);
            try (var query = connection.prepareStatement("SELECT request_json,response_sha256,response_json,private_json FROM variant_analysis_acmg_run WHERE analysis_id=? AND run_id=?")) {
                query.setString(1, args[1]); query.setString(2, args[2]);
                try (var result = query.executeQuery()) {
                    if (!result.next()) throw new IllegalArgumentException("Demo run missing");
                    archive.put("persistedRequestRaw", result.getString(1));
                    archive.put("persistedResponseSha256", result.getString(2));
                    archive.put("persistedResponseRaw", result.getString(3));
                    archive.put("persistedPrivateRaw", result.getString(4));
                }
            }
            try (var query = connection.prepareStatement("SELECT snapshot_sha256,snapshot_json FROM variant_analysis_acmg_source WHERE analysis_id=?")) {
                query.setString(1, args[1]);
                try (var result = query.executeQuery()) {
                    if (!result.next()) throw new IllegalArgumentException("Demo source snapshot missing");
                    archive.put("sourceSnapshotSha256", result.getString(1));
                    archive.put("sourceSnapshotRaw", result.getString(2));
                }
            }
        }
        Path capture = root.resolve("acceptance");
        archive.set("job", mapper.readTree(capture.resolve("job.json").toFile()));
        archive.set("ranking", mapper.readTree(capture.resolve("ranking.json").toFile()));
        archive.set("apiResult", mapper.readTree(capture.resolve("run.json").toFile()));
        Files.write(capture.resolve("private-audit.json"), mapper.writeValueAsBytes(archive));
        System.out.println("EXPORTED_LOCAL_READ_ONLY_AUDIT");
    }
}
