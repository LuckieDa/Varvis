package org.nbscn.varvis.variantanalysis.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Separate JVM reads actual saved synthetic runs, without any evaluator or source adapter. */
public final class Pp1FormalHistoryReader {
    private Pp1FormalHistoryReader() { }
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]); Path output = Path.of(args[4]);
        var ds = new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("business-db").toAbsolutePath()
                .toString().replace('\\', '/') + ";MODE=MySQL", "sa", "");
        try {
            var store = new AcmgBusinessStore(ds);
            for (int i = 0; i < 2; i++) {
                var run = store.run(args[1], args[2 + i]).orElseThrow();
                Path folder = output.resolve(i == 0 ? "single" : "all");
                if (!Files.readString(folder.resolve("expected-public.json")).equals(run.responseJson())
                        || !Files.readString(folder.resolve("expected-private.json")).equals(run.privateJson())
                        || !AcmgBusinessStore.sha(run.responseJson()).equals(run.responseSha256()))
                    throw new IllegalStateException("PP1 formal history/source/result bytes changed");
                Files.writeString(folder.resolve("full-export.json"), run.responseJson());
            }
            System.out.println("PASS: formal PP1 single/all saved public/private bytes and complete export in another JVM");
        } finally { new JdbcTemplate(ds).execute("SHUTDOWN"); }
    }
}
