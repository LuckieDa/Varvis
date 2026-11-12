package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/** Pure disease-architecture calculation, not an automatic scientific adoption.
 * Equations: Whiffin et al., 2017, doi:10.1038/gim.2017.26 (AD and AR).
 * A directed upper bound avoids false MET at a rounded irrational threshold.
 */
public final class Bs1MaximumCredibleFrequency {
    public static final String VERSION = "whiffin-2017-ad-ar-v1";
    private static final MathContext UP = new MathContext(40, RoundingMode.CEILING);
    private Bs1MaximumCredibleFrequency() { }

    public static ObjectNode calculate(ObjectMapper mapper, String moi, JsonNode model) {
        var result = mapper.createObjectNode().put("modelVersion", VERSION);
        var missing = result.putArray("missingParameters");
        if (!Set.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE").contains(moi))
            return blocked(result, "BS1_MODEL_MOI_UNSUPPORTED");
        if (!model.isObject() || !Bs1Policy.keys(model, Set.of("prevalence", "penetrance", "maximumGeneContribution", "maximumAlleleContribution", "population", "applicability")))
            return blocked(result, "BS1_MODEL_SCHEMA_INVALID");
        for (String name : List.of("prevalence", "penetrance", "maximumGeneContribution", "maximumAlleleContribution"))
            if (!model.has(name)) missing.add(name);
        if (!missing.isEmpty()) return blocked(result, "BS1_DISEASE_PARAMETERS_MISSING");
        if (!"MENDELIAN_RARE_DISEASE".equals(model.path("applicability").asText()) || model.path("population").asText().isBlank())
            return blocked(result, "BS1_MODEL_APPLICABILITY_REQUIRED");
        try {
            BigDecimal prevalence = parameter(model, "prevalence"), penetrance = parameter(model, "penetrance");
            BigDecimal gene = parameter(model, "maximumGeneContribution"), allele = parameter(model, "maximumAlleleContribution");
            if (!"POINT_PREVALENCE".equals(model.at("/prevalence/statistic").asText()))
                return blocked(result, "BS1_PREVALENCE_STATISTIC_UNSUPPORTED");
            BigDecimal threshold;
            if ("AUTOSOMAL_DOMINANT".equals(moi)) {
                threshold = prevalence.multiply(gene).multiply(allele).divide(penetrance.multiply(new BigDecimal("2")), UP);
                result.put("formula", "prevalence * maximumGeneContribution * maximumAlleleContribution / (2 * penetrance)");
            } else {
                var radicand = prevalence.multiply(gene).divide(penetrance, UP);
                var root = radicand.sqrt(UP);
                if (root.multiply(root).compareTo(radicand) < 0) root = root.add(root.ulp());
                threshold = root.multiply(allele, UP);
                result.put("formula", "sqrt(prevalence * maximumGeneContribution / penetrance) * maximumAlleleContribution");
            }
            if (threshold.signum() <= 0 || threshold.compareTo(BigDecimal.ONE) >= 0)
                return blocked(result, "BS1_MODEL_THRESHOLD_OUT_OF_RANGE");
            result.set("inputs", model.deepCopy());
            return result.put("status", "CALCULATED_NOT_ADOPTED").put("maximumCredibleAf", threshold.stripTrailingZeros().toPlainString())
                    .put("comparison", "GT").put("rounding", "UPPER_BOUND_40_SIGNIFICANT_DIGITS");
        } catch (IllegalArgumentException invalid) { return blocked(result, "BS1_MODEL_PARAMETER_INVALID"); }
    }

    private static BigDecimal parameter(JsonNode model, String name) {
        var p = model.path(name);
        if (!Bs1Policy.keys(p, Set.of("value", "unit", "statistic", "sourceId", "locator"))
                || !p.path("value").isTextual() || !p.path("value").asText().matches("(?:0|1)(?:\\.[0-9]{1,30})?")
                || !"fraction".equals(p.path("unit").asText()) || p.path("sourceId").asText().isBlank()
                || p.path("locator").asText().isBlank()) throw new IllegalArgumentException("parameter");
        var value = new BigDecimal(p.path("value").asText());
        if (value.signum() <= 0 || value.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("range");
        return value;
    }
    private static ObjectNode blocked(ObjectNode result, String reason) { return result.put("status", "BLOCKED").put("reasonCode", reason); }
}
