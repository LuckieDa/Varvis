package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Pure single-record adapter. Original fields remain in the immutable source; no inferred PASS or reference call. */
public final class NuclearFamilyRecordAdapter {
    public static final String QUALITY_PROFILE = "pp1-diploid-snv-dp20-gq20-explicit-ft-v1";
    private final FamilyDeNovoJson json;
    public NuclearFamilyRecordAdapter() { this(new FamilyDeNovoJson("PP1_")); }
    NuclearFamilyRecordAdapter(FamilyDeNovoJson json) { this.json = json; }
    public ObjectNode adapt(ObjectNode record) {
        var j = json;
        new NuclearFamilyShape(json).recordShape(record);
        String[] fields = record.path("format").asText().split(":", -1), values = record.path("rawSample").asText().split(":", -1);
        if (fields.length != values.length) throw j.invalid("RECORD_FIELDS_INCOMPLETE");
        Map<String, String> raw = new HashMap<>();
        for (int i = 0; i < fields.length; i++) if (fields[i].isBlank() || raw.put(fields[i], values[i]) != null) throw j.invalid("RECORD_FORMAT_INVALID");
        for (String key : List.of("GT", "DP", "GQ", "AD", "FT")) if (!raw.containsKey(key) || raw.get(key).isBlank() || ".".equals(raw.get(key))) throw j.invalid("RECORD_" + key + "_REQUIRED");
        String gt = raw.get("GT");
        if (!gt.matches("[01][/|][01]")) throw j.invalid("RECORD_DIPLOID_BIALLELIC_GT_REQUIRED");
        int depth = number(raw.get("DP")), gq = number(raw.get("GQ"));
        String[] ad = raw.get("AD").split(",", -1);
        if (ad.length != 2) throw j.invalid("RECORD_AD_INVALID");
        int ref = number(ad[0]), alt = number(ad[1]);
        if (!"PASS".equals(record.path("recordFilter").asText())) throw j.invalid("RECORD_FILTER_NOT_PASS");
        if (!"PASS".equals(raw.get("FT"))) throw j.invalid("SAMPLE_FT_NOT_PASS");
        String call = gt.charAt(0) != gt.charAt(2) ? "HET_ALT" : gt.charAt(0) == '0' ? "HOM_REF" : "HOM_ALT";
        if (depth < 20 || gq < 20 || (long) ref + alt != depth || (call.equals("HET_ALT") && (ref == 0 || alt == 0)) || (call.equals("HOM_REF") && alt != 0)) throw j.invalid("RECORD_QUALITY_UNSUPPORTED");
        ObjectNode observed = new ObjectMapper().createObjectNode().put("genotype", gt).put("phased", gt.contains("|")) .put("depth", depth).put("genotypeQuality", gq).put("alleleDepth", raw.get("AD")).put("sampleFilter", raw.get("FT")) .put("call", call).put("targetAltIndex", 1).put("ploidy", 2);
        if (!observed.equals(record.path("observation"))) throw j.invalid("RECORD_RAW_PROJECTION_CONFLICT");
        return observed;
    }
    private int number(String value) {
        if (!value.matches("0|[1-9][0-9]{0,8}")) throw json.invalid("RECORD_INTEGER_INVALID");
        return Integer.parseInt(value);
    }
}
