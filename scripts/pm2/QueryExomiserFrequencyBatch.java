import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.mvstore.MVStore;
import org.monarchinitiative.exomiser.core.genome.GenomeAssembly;
import org.monarchinitiative.exomiser.core.genome.dao.AllelePropertiesDaoMvStore;
import org.monarchinitiative.exomiser.core.model.AlleleProtoAdaptor;
import org.monarchinitiative.exomiser.core.model.frequency.FrequencySource;
import org.monarchinitiative.exomiser.core.proto.AlleleProto;
import org.yaml.snakeyaml.Yaml;
import java.nio.file.*;
import java.util.*;

/** Read-only exact lookups using the installed Exomiser decoder; never exposes prediction scores. */
class QueryExomiserFrequencyBatch {
    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 53) throw new IllegalArgumentException("database effective-yaml output-json chr:pos:ref:alt [up to 50 small alleles]");
        Path database = Path.of(args[0]), yaml = Path.of(args[1]);
        if (!database.getFileName().toString().equals("2602_hg38_variants.mv.db")
                || !AlleleProtoAdaptor.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith("exomiser-core-15.1.0.jar"))
            throw new IllegalArgumentException("This audit is pinned to Exomiser 15.1.0 / 2602 hg38");
        Map<String, Object> policy = new Yaml().load(Files.readString(yaml));
        var selected = EnumSet.noneOf(FrequencySource.class);
        for (Object name : (List<?>) policy.get("frequencySources")) selected.add(FrequencySource.valueOf(name.toString()));
        var mapper = new ObjectMapper();
        var report = mapper.createObjectNode().put("exomiserVersion", "15.1.0").put("dataVersion", "2602")
                .put("databaseFile", database.getFileName().toString()).put("databaseBytes", Files.size(database))
                .put("assembly", "GRCh38").put("readOnly", true).put("frequencyUnit", "percent")
                .put("decoder", "Exomiser AlleleProtoAdaptor.toFrequencyData").put("predictionScoresRead", false);
        report.set("frequencySources", mapper.valueToTree(selected));
        report.set("inheritanceModesPercent", mapper.valueToTree(policy.get("inheritanceModes")));
        double threshold = -1;
        for (Object step : (List<?>) policy.get("steps")) {
            Object filter = ((Map<?, ?>) step).get("frequencyFilter");
            if (filter instanceof Map<?, ?> values) threshold = ((Number) values.get("maxFrequency")).doubleValue();
        }
        if (threshold < 0) throw new IllegalArgumentException("frequencyFilter threshold missing");
        report.put("frequencyFilterMaxPercent", threshold);
        var alleles = report.putArray("alleles");
        try (var store = new MVStore.Builder().fileName(database.toString()).readOnly().cacheSize(16).open()) {
            report.set("storeMapNames", mapper.valueToTree(store.getMapNames()));
            var dao = new AllelePropertiesDaoMvStore(store);
            for (int i = 3; i < args.length; i++) {
                String[] parts = args[i].split(":");
                if (parts.length != 4 || !parts[2].matches("[ACGT]{1,51}") || !parts[3].matches("[ACGT]{1,51}")) throw new IllegalArgumentException("Exact small allele required");
                var key = AlleleProto.AlleleKey.newBuilder().setChr(Integer.parseInt(parts[0])).setPosition(Integer.parseInt(parts[1]))
                        .setRef(parts[2]).setAlt(parts[3]).build();
                var properties = dao.getAlleleProperties(key, GenomeAssembly.HG38);
                var frequencies = AlleleProtoAdaptor.toFrequencyData(properties);
                var row = alleles.addObject().put("allele", "GRCh38:" + args[i]).put("rsId", frequencies.getRsId())
                        .put("hasKnownFrequency", frequencies.hasKnownFrequency());
                var values = row.putArray("frequencies");
                double maximum = -1;
                for (var f : frequencies.frequencies()) {
                    boolean active = selected.contains(f.source());
                    values.addObject().put("source", f.source().name()).put("percent", Float.toString(f.frequency()))
                            .put("ac", f.ac()).put("an", f.an()).put("homs", f.homs()).put("active", active);
                    if (active) maximum = Math.max(maximum, f.frequency());
                }
                row.put("selectedMaxPercent", maximum < 0 ? null : Float.toString((float) maximum));
                row.put("frequencyFilter", maximum < 0 ? "NO_SELECTED_RECORD_NOT_ZERO" : maximum > threshold ? "FAIL" : "PASS");
            }
        }
        Files.writeString(Path.of(args[2]), report.toPrettyString());
        System.out.println(report.toPrettyString());
    }
}
