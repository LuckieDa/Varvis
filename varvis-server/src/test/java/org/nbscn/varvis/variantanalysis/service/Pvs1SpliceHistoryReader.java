package org.nbscn.varvis.variantanalysis.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Separate JVM reader of the actual synthetic business-store run. No runtime/evaluator. */
public final class Pvs1SpliceHistoryReader {
    private Pvs1SpliceHistoryReader() { }
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]);Path output=Path.of(args[3]);
        var ds=new DriverManagerDataSource("jdbc:h2:file:"+directory.resolve("business-db").toAbsolutePath().toString().replace('\\','/')+";MODE=MySQL","sa","");
        try {
            var store=new AcmgBusinessStore(ds);var saved=store.run(args[1],args[2]).orElseThrow();
            if(!Files.readString(output.resolve("expected-public.json")).equals(saved.responseJson())
                    || !Files.readString(output.resolve("expected-private.json")).equals(saved.privateJson())
                    || !AcmgBusinessStore.sha(saved.responseJson()).equals(saved.responseSha256()))
                throw new IllegalStateException("PVS1 new-method history/source/result bytes changed");
            Files.writeString(output.resolve("full-export.json"),saved.responseJson());
            System.out.println("PASS: actual business run read in another JVM; complete public export and private bytes unchanged");
        } finally { new JdbcTemplate(ds).execute("SHUTDOWN"); }
    }
}
