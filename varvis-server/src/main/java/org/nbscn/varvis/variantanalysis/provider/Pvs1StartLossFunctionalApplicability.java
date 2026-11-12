package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Source-bound candidate/evidence-use assessment. An accession mention is an
 * identity lead, never functional qualification or an ACMG decision. */
final class Pvs1StartLossFunctionalApplicability {
    static final String VERSION = "1.0.0";
    private static final Pattern TRANSCRIPT = Pattern.compile(
            "(?<![A-Za-z0-9_])(?:NM_[0-9]+|ENST[0-9]+)(?:\\.[1-9][0-9]*)?(?![A-Za-z0-9_]|\\.[A-Za-z0-9_])");
    private static final Map<String, String> LIMITS = Map.of(
            "FRAGMENT_CONSTRUCT_REPORTED", "FRAGMENT_ASSAY_NOT_WHOLE_ISOFORM",
            "EXON_DELETION_INTERVENTION_REPORTED", "INTERVENTION_EFFECT_NOT_BASELINE_COMPENSATION",
            "OVEREXPRESSION_INTERVENTION_REPORTED", "INTERVENTION_EFFECT_NOT_BASELINE_COMPENSATION",
            "PARTIAL_READOUT_RESCUE_REPORTED", "PARTIAL_ENDPOINT_NOT_WHOLE_FUNCTION",
            "PROTEIN_ABUNDANCE_INCREASE_REPORTED", "ABUNDANCE_NOT_FUNCTIONAL_RESCUE");

    private Pvs1StartLossFunctionalApplicability() { }
    private record Unit(JsonNode finding, JsonNode citation, Set<String> transcripts) { }
    private record Observation(JsonNode source, boolean replayed, Set<String> subjects, Set<String> context) { }
    private record Document(JsonNode source, List<Unit> units, List<Observation> observations) { }

    private static List<Document> index(JsonNode report) {
        var documents = new java.util.ArrayList<Document>();
        for (var doc : report.path("documents")) {
            var units = new LinkedHashMap<String, Unit>(); var all = new java.util.ArrayList<Unit>();
            var duplicates = new HashSet<String>();
            for (var finding : doc.path("findings")) {
                var citation = finding.path("citation"); String id = citation.path("unitId").asText();
                var unit = new Unit(finding, citation, tokens(citation.path("quote").asText())); all.add(unit);
                if (units.putIfAbsent(id, unit) != null) duplicates.add(id);
            }
            var observations = new java.util.ArrayList<Observation>();
            for (var observation : doc.path("experimentalAssessment").path("observations")) {
                String id = observation.path("unitId").asText(); var unit = units.get(id);
                boolean valid = unit != null && id.equals(unit.finding().path("unitId").asText()) && !duplicates.contains(id)
                        && Set.of("CITED_OR_BACKGROUND", "PRIMARY_OR_DISCUSSION_REQUIRES_REVIEW").contains(unit.finding().path("sourceContext").asText())
                        && unit.finding().path("sourceContext").equals(observation.path("sourceRole")) && replay(unit.citation(), observation);
                observations.add(new Observation(observation, valid, tokens(observation.path("sourceQuote").asText()),
                        unit == null ? Set.of() : unit.transcripts()));
            }
            documents.add(new Document(doc, all, observations));
        }
        return documents;
    }

    static ObjectNode assess(ObjectMapper mapper, JsonNode transcripts, JsonNode libraryReport) {
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-functional-applicability-v1")
                .put("methodVersion", VERSION).put("status", "ASSESSMENT_INCOMPLETE")
                .put("functionalQualificationGranted", false).put("negativeFunctionalCoverageGranted", false)
                .put("contradictionAssessment", "EXPERIMENT_CONTEXT_NOT_SUFFICIENT_TO_COMPARE");
        var candidates = out.putArray("candidates");
        var documents = index(libraryReport); int unassessed = 0;
        // Preserve duplicate source rows as provenance, but assess the same exact
        // version only once. Distinct versions are never joined by accession base.
        var byTranscript = new LinkedHashMap<String, ObjectNode>();
        for (var row : transcripts.path("findings")) {
            if (!"UNRESOLVED".equals(row.path("disposition").asText())) continue;
            if (!row.path("differentStart").isBoolean()) { unassessed++; continue; }
            if (!row.path("differentStart").asBoolean()) continue;
            String tx = row.path("transcript").asText();
            if (!tx.matches("(?:NM_[0-9]+|ENST[0-9]+)\\.[1-9][0-9]*")) { unassessed++; continue; }
            var candidate = byTranscript.computeIfAbsent(tx, key -> {
                var item = mapper.createObjectNode().put("transcript", key).put("status", "IDENTITY_NOT_ESTABLISHED")
                        .put("functionalQualificationGranted", false).put("negativeFunctionalCoverageGranted", false);
                item.putArray("structuralSources"); item.putArray("documents"); return item;
            });
            var source = candidate.withArray("structuralSources").addObject();
            for (String field : java.util.List.of("sourceSha256", "sourceLocator", "startPositions", "sequenceVerified", "rnaSha256", "cdsSha256"))
                if (row.has(field)) source.set(field, row.path(field).deepCopy());
        }
        for (var entry : byTranscript.entrySet()) {
            String tx = entry.getKey(); var candidate = entry.getValue();
            boolean exact = false, invalid = false;
            for (var indexed : documents) {
                var doc = indexed.source();
                var result = candidate.withArray("documents").addObject().put("pmcid", doc.path("pmcid").asText());
                var matches = result.putArray("identityMentions");
                String identity = "NO_EXACT_TRANSCRIPT_IDENTITY_IN_REVIEWED_TEXT";
                for (var unit : indexed.units()) {
                    var citation = unit.citation();
                    String relation = relation(tx, unit.transcripts());
                    if (priority(relation) > priority(identity)) identity = relation;
                    if (!"NO_EXACT_TRANSCRIPT_IDENTITY_IN_REVIEWED_TEXT".equals(relation)) {
                        var mention = matches.addObject().put("relation", relation);
                        for (String field : java.util.List.of("unitId", "sourceSha256", "start", "end"))
                            mention.set(field, citation.path(field).deepCopy());
                    }
                }
                result.put("identityRelation", identity);
                exact |= "EXACT_VERSION_MENTION_NOT_FUNCTION".equals(identity);
                var observations = result.putArray("observations");
                int unbound = 0, invalidCount = 0;
                for (var indexedObservation : indexed.observations()) {
                    var observation = indexedObservation.source();
                    String unit = observation.path("unitId").asText();
                    String contextRelation = relation(tx, indexedObservation.context());
                    if (!indexedObservation.replayed()) {
                        invalidCount++;
                        invalid |= !"NO_EXACT_TRANSCRIPT_IDENTITY_IN_REVIEWED_TEXT".equals(contextRelation)
                                || !"NO_EXACT_TRANSCRIPT_IDENTITY_IN_REVIEWED_TEXT".equals(relation(tx, indexedObservation.subjects()));
                        continue;
                    }
                    // Unrelated observations remain once in the reading report;
                    // do not replicate their details for every gene transcript.
                    if ("NO_EXACT_TRANSCRIPT_IDENTITY_IN_REVIEWED_TEXT".equals(contextRelation)) {
                        unbound++; continue;
                    }
                    var item = observations.addObject().put("unitId", unit).put("kind", observation.path("kind").asText())
                            .put("sourceReplay", "QUOTE_AND_LOCATION_MATCH")
                            .put("functionalQualificationGranted", false);
                    for (String field : java.util.List.of("sourceSha256", "sourceStart", "sourceEnd")) item.set(field, observation.path(field).deepCopy());
                    Set<String> subjects = indexedObservation.subjects();
                    String scope = relation(tx, subjects);
                    if (subjects.contains(tx) && subjects.size() > 1) scope = "AMBIGUOUS_EXPERIMENT_SUBJECT";
                    else if (!subjects.contains(tx) && "EXACT_VERSION_MENTION_NOT_FUNCTION".equals(contextRelation))
                        scope = "TRANSCRIPT_MENTION_ONLY_IN_CONTEXT";
                    item.put("identityRelation", scope);
                    if (!"EXACT_VERSION_MENTION_NOT_FUNCTION".equals(scope)) item.put("useDisposition", "EXPERIMENT_SUBJECT_NOT_BOUND");
                    else if ("CITED_OR_BACKGROUND".equals(observation.path("sourceRole").asText())) item.put("useDisposition", "PRIMARY_EXPERIMENT_NOT_ESTABLISHED");
                    else item.put("useDisposition", LIMITS.getOrDefault(observation.path("kind").asText(), "EXPERIMENT_SEMANTICS_UNRESOLVED"));
                }
                result.put("unboundObservationCount", unbound).put("invalidObservationCount", invalidCount);
                result.put("pendingUnitCount", doc.path("pendingUnits").size());
                result.set("supplementCoverage", doc.path("supplementCoverage").deepCopy());
            }
            candidate.put("status", invalid ? "OBSERVATION_PROVENANCE_INVALID" : exact ? "IDENTITY_MENTION_ONLY_FUNCTION_UNRESOLVED" : "IDENTITY_NOT_ESTABLISHED");
            candidates.add(candidate);
        }
        out.put("candidateCount", candidates.size()).put("unassessedUnresolvedRowCount", unassessed);
        if (candidates.isEmpty()) out.put("status", "NO_STRUCTURALLY_BOUND_CANDIDATE_TO_ASSESS");
        return out;
    }

    private static Set<String> tokens(String text) {
        Set<String> values = new HashSet<>(); var matcher = TRANSCRIPT.matcher(text);
        while (matcher.find()) values.add(matcher.group()); return values;
    }

    /** Display limits only. The full preparation and original reading remain in
     * the private result; retain counts so omission is explicit. */
    static void summarize(ObjectMapper mapper, ObjectNode assessment) {
        assessment.put("displayTruncated", assessment.path("displayTruncated").asBoolean() || assessment.path("candidates").size() > 30);
        limit(mapper, assessment, "candidates", 30);
        for (var candidate : assessment.path("candidates")) for (var document : candidate.path("documents"))
            if (document instanceof ObjectNode row) {
                row.put("identityMentionCount", Math.max(row.path("identityMentionCount").asInt(), row.path("identityMentions").size()))
                        .put("observationCount", Math.max(row.path("observationCount").asInt(), row.path("observations").size()))
                        .put("observationDisplayTruncated", row.path("observationDisplayTruncated").asBoolean() || row.path("observations").size() > 10);
                limit(mapper, row, "identityMentions", 10); limit(mapper, row, "observations", 10);
            }
    }
    private static void limit(ObjectMapper mapper, ObjectNode parent, String field, int maximum) {
        var visible = mapper.createArrayNode();
        for (var item : parent.path(field)) { if (visible.size() >= maximum) break; visible.add(item); }
        parent.set(field, visible);
    }
    private static String relation(String tx, Set<String> tokens) {
        if (tokens.contains(tx)) return "EXACT_VERSION_MENTION_NOT_FUNCTION";
        String base = tx.substring(0, tx.lastIndexOf('.'));
        if (tokens.stream().anyMatch(t -> t.startsWith(base + "."))) return "TRANSCRIPT_VERSION_MISMATCH";
        if (tokens.contains(base)) return "UNVERSIONED_ACCESSION_REQUIRES_BINDING";
        return "NO_EXACT_TRANSCRIPT_IDENTITY_IN_REVIEWED_TEXT";
    }
    private static int priority(String relation) {
        return switch (relation) {
            case "EXACT_VERSION_MENTION_NOT_FUNCTION" -> 3;
            case "TRANSCRIPT_VERSION_MISMATCH" -> 2;
            case "UNVERSIONED_ACCESSION_REQUIRES_BINDING" -> 1;
            default -> 0;
        };
    }
    private static boolean replay(JsonNode citation, JsonNode observation) {
        if (!citation.path("sourceSha256").asText().matches("[a-f0-9]{64}")
                || !citation.path("sourceSha256").equals(observation.path("sourceSha256"))
                || !citation.path("quote").isTextual() || !observation.path("sourceQuote").isTextual()) return false;
        for (String field : java.util.List.of("start", "end"))
            if (!citation.path(field).isIntegralNumber() || !citation.path(field).canConvertToInt()) return false;
        for (String field : java.util.List.of("sourceStart", "sourceEnd"))
            if (!observation.path(field).isIntegralNumber() || !observation.path(field).canConvertToInt()) return false;
        String text = citation.path("quote").asText(); int length = text.codePointCount(0, text.length());
        long start = observation.path("sourceStart").asLong() - citation.path("start").asLong();
        long end = observation.path("sourceEnd").asLong() - citation.path("start").asLong();
        if (citation.path("start").asInt() < 0 || (long) citation.path("end").asInt() - citation.path("start").asInt() != length
                || start < 0 || end <= start || end > length) return false;
        return text.substring(text.offsetByCodePoints(0, (int) start), text.offsetByCodePoints(0, (int) end))
                .equals(observation.path("sourceQuote").asText());
    }
}
