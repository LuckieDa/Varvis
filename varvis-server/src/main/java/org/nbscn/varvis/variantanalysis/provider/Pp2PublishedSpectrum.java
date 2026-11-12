package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Exact-scope published PP2 evidence when the background has no matching specialist specification. */
final class Pp2PublishedSpectrum {
    static final String TYPE = "PP2_PUBLISHED_AUTHORITY_SPECTRUM_V1";
    static final String CONSTRAINT_TYPE = "PP2_ACGS_CONSTRAINT_NEGATIVE_V1";
    static final String BASIS = "PUBLISHED_AUTHORITY_SPECTRUM";
    static final String URL = "https://pmc.ncbi.nlm.nih.gov/articles/PMC4544753/#S19";
    static final String ACGS_URL = "https://www.acgs.uk.com/media/12533/_media_12533_uk-practice-guidelines-for-variant-classification-v12-2024.pdf#page=13";
    static final String ACGS_LOCATOR = "Section 5 PP2, pp. 13-14";
    static final String ACGS_SPECIFICATION = "ACGS_2024@1.2";
    static final String GNOMAD_TRANSCRIPT_URL = "https://gnomad.broadinstitute.org/transcript/";
    private static final double MINIMUM_MISSENSE_Z = 3.09;
    private static final Set<String> CONDITIONS = Set.of("missenseMechanism", "lowBenignMissense");
    private final Map<Scope, Assessment> records = new HashMap<>();

    Pp2PublishedSpectrum(JsonNode spectrum, JsonNode constraint) {
        if (!spectrum.isMissingNode()) {
            var source = spectrum.path("source");
            for (var row : checkedRows(spectrum, "pp2-authority-spectrum-v1", URL, "S19")) {
                var conditions = row.path("conditions");
                require(negativeConditions(conditions));
                register(row, source, TYPE, "ACMG_AMP_2015@2015", conditions);
            }
        }
        if (!constraint.isMissingNode()) {
            var source = constraint.path("source");
            for (var row : checkedRows(constraint, "pp2-acgs-constraint-negative-v1", ACGS_URL, ACGS_LOCATOR)) {
                register(row, source, CONSTRAINT_TYPE, ACGS_SPECIFICATION, constraintConditions(row));
            }
        }
    }

    private static JsonNode checkedRows(JsonNode bundle, String schema, String url, String locator) {
        var source = bundle.path("source");
        var rows = bundle.path("records");
        require(schema.equals(bundle.path("schemaVersion").asText()) && url.equals(source.path("url").asText())
                && locator.equals(source.path("locator").asText()) && sha(source.path("rawSha256"))
                && rows.isArray() && !rows.isEmpty() && rows.size() <= 512);
        return rows;
    }

    private void register(JsonNode row, JsonNode source, String method, String specification, JsonNode conditions) {
        var scope = row.path("scope");
        var key = new Scope(scope.path("hgncId").asText(), scope.path("mondoId").asText(),
                scope.path("moi").asText(), scope.path("assembly").asText());
        var audit = row.path("specialistCheck");
        var corroboration = row.path("mechanismCorroboration");
        require(key.gene().matches("HGNC:[1-9][0-9]*") && key.disease().matches("MONDO:[0-9]{7}")
                && Set.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE", "X_LINKED_DOMINANT",
                        "X_LINKED_RECESSIVE", "X_LINKED").contains(key.inheritance())
                && "GRCh38".equals(key.assembly())
                && scope.path("transcript").asText().matches("(?:NM_[0-9]+|ENST[0-9]+)\\.[1-9][0-9]*")
                && boundedText(row.path("recordId"))
                && "NO_MATCHING_SPECIFICATION".equals(audit.path("status").asText()) && sha(audit.path("inventoryRawSha256"))
                && corroboration.path("url").asText().startsWith("https://search.clinicalgenome.org/kb/gene-validity/")
                && validSource(corroboration));
        require(records.putIfAbsent(key, new Assessment(row.deepCopy(), source.deepCopy(), method,
                specification, conditions.deepCopy())) == null);
    }

    private static ObjectNode constraintConditions(JsonNode row) {
        String transcript = row.at("/scope/transcript").asText();
        var mechanism = row.path("mechanism");
        var mechanismSource = mechanism.path("source");
        var constraint = row.path("constraint");
        var constraintSource = constraint.path("source");
        var observed = constraint.path("observed");
        var expected = constraint.path("expected");
        var z = constraint.path("z");
        require("SUPPORTED".equals(mechanism.path("conclusion").asText()) && boundedText(mechanism.path("sourceText"))
                && mechanismSource.path("url").asText().matches("https://pmc\\.ncbi\\.nlm\\.nih\\.gov/articles/PMC[0-9]+/#[A-Za-z0-9_-]+")
                && validSource(mechanismSource)
                && transcript.matches("ENST[0-9]+\\.[1-9][0-9]*") && transcript.equals(constraint.path("transcript").asText())
                && (GNOMAD_TRANSCRIPT_URL + transcript.substring(0, transcript.indexOf('.')) + "?dataset=gnomad_r4")
                        .equals(constraintSource.path("url").asText())
                && validSource(constraintSource)
                && !mechanismSource.path("rawSha256").equals(constraintSource.path("rawSha256"))
                && "4.1.2".equals(constraint.path("release").asText()) && "GRCh38".equals(constraint.path("assembly").asText())
                && constraint.path("flags").isArray() && constraint.path("flags").isEmpty()
                && observed.isIntegralNumber() && observed.canConvertToInt() && observed.intValue() >= 0
                && expected.isNumber() && Double.isFinite(expected.doubleValue()) && expected.doubleValue() > 0
                && z.isNumber() && Double.isFinite(z.doubleValue()) && z.doubleValue() < MINIMUM_MISSENSE_Z);
        var conditions = JsonNodeFactory.instance.objectNode();
        var mechanismCondition = conditions.putObject("missenseMechanism").put("conclusion", "SUPPORTED")
                .put("basisType", "PUBLISHED_DISEASE_MISSENSE_SPECTRUM").put("sourceText", mechanism.path("sourceText").asText());
        mechanismCondition.set("source", mechanismSource.deepCopy());
        var benignCondition = conditions.putObject("lowBenignMissense").put("conclusion", "NOT_SUPPORTED")
                .put("basisType", "PUBLISHED_MISSENSE_Z_THRESHOLD");
        benignCondition.set("source", constraintSource.deepCopy());
        var measurement = benignCondition.putObject("measurement").put("transcript", transcript)
                .put("assembly", "GRCh38").put("release", "4.1.2").put("operator", "GTE").put("threshold", MINIMUM_MISSENSE_Z);
        measurement.set("observed", observed);
        measurement.set("expected", expected);
        measurement.set("z", z);
        return conditions;
    }

    /** Only the already verified coding-missense selector calls this after excluding specialist records. */
    boolean select(ObjectNode out, ObjectNode context, String executionTranscript, Predicate<String> verifiedBridge) {
        var assessment = records.get(new Scope(context.at("/gene/hgncId").asText(), context.at("/disease/mondoId").asText(),
                context.path("moi").asText(), context.at("/variant/evidenceQueryAllele/assembly").asText()));
        if (assessment == null) return false;
        var row = assessment.row();
        String declared = row.at("/scope/transcript").asText();
        out.put("declaredTranscript", declared);
        if (!declared.equals(executionTranscript) && !verifiedBridge.test(declared)) {
            out.put("reasonCode", "PUBLISHED_KNOWLEDGE_TRANSCRIPT_NOT_COVERED");
            return true;
        }
        var proof = out.putObject("authoritySpectrum").put("method", assessment.method());
        proof.set("source", assessment.source().deepCopy());
        proof.set("mechanismCorroboration", row.path("mechanismCorroboration").deepCopy());
        proof.set("specialistCheck", row.path("specialistCheck").deepCopy());
        var selected = out.putObject("record").put("recordId", row.path("recordId").asText())
                .put("specificationKey", assessment.specification());
        selected.set("scope", row.path("scope").deepCopy());
        selected.putObject("interpretation").put("type", assessment.method()).set("conditions", assessment.conditions().deepCopy());
        out.put("status", "READY").put("reasonCode", "PP2_PUBLISHED_AUTHORITY_SPECTRUM_READY")
                .put("sourceDisposition", "EXECUTABLE").put("sourceClausePointer", assessment.source().path("locator").asText());
        return true;
    }

    static boolean negativeConditions(JsonNode conditions) {
        if (!conditions.isObject() || conditions.size() != CONDITIONS.size()) return false;
        for (String key : CONDITIONS) {
            var condition = conditions.path(key);
            if (!BASIS.equals(condition.path("basisType").asText()) || !boundedText(condition.path("sourceText"))
                    || !"S19".equals(condition.path("sourceLocator").asText())) return false;
        }
        String reason = Pp2ConditionDecision.reason(conditions);
        return "PP2_MISSENSE_MECHANISM_NOT_SUPPORTED".equals(reason) || "PP2_LOW_BENIGN_BACKGROUND_NOT_SUPPORTED".equals(reason);
    }

    private static boolean boundedText(JsonNode value) {
        return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 1024;
    }

    private static boolean sha(JsonNode value) {
        return value.asText().matches("[a-f0-9]{64}");
    }

    private static boolean validSource(JsonNode source) {
        return sha(source.path("rawSha256")) && boundedText(source.path("locator"));
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("PP2_AUTHORITY_SPECTRUM_INVALID");
    }

    private record Scope(String gene, String disease, String inheritance, String assembly) { }
    private record Assessment(JsonNode row, JsonNode source, String method, String specification, JsonNode conditions) { }
}
