package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Finite source-defined PM1 dependencies. Consumes actual module subtrees, never selection flags.
 * MAIN resolves saved module pointers and verifies its source snapshot before constructing Facts.
 * Hashes bind supplied bytes; they are not publisher signatures or client authentication.
 */
final class PublishedPm1CrossCriterionConditions {
    static final String VERSION = "1.1.0";
    static final String REQUIRE = "REQUIRE_MET_AT_LEAST", EXCLUDE = "EXCLUDE_IF_MET_AT_LEAST";
    private static final String AA3 = "Ala|Arg|Asn|Asp|Cys|Gln|Glu|Gly|His|Ile|Leu|Lys|Met|Phe|Pro|Ser|Thr|Trp|Tyr|Val";
    private static final String POSITION = "[1-9][0-9]{0,5}";
    private static final String RESIDUE = "(?:(?:" + AA3 + ")" + POSITION + "|" + POSITION + "-" + POSITION + ")";
    private static final Map<String, String> AMINO_ACIDS = Map.ofEntries(
            Map.entry("Ala", "A"), Map.entry("Arg", "R"), Map.entry("Asn", "N"), Map.entry("Asp", "D"),
            Map.entry("Cys", "C"), Map.entry("Gln", "Q"), Map.entry("Glu", "E"), Map.entry("Gly", "G"),
            Map.entry("His", "H"), Map.entry("Ile", "I"), Map.entry("Leu", "L"), Map.entry("Lys", "K"),
            Map.entry("Met", "M"), Map.entry("Phe", "F"), Map.entry("Pro", "P"), Map.entry("Ser", "S"),
            Map.entry("Thr", "T"), Map.entry("Trp", "W"), Map.entry("Tyr", "Y"), Map.entry("Val", "V"));
    private static final String LOCATIONS = RESIDUE + "(?:, " + RESIDUE + ")*";
    private static final java.util.regex.Pattern PP3_SOURCE = java.util.regex.Pattern.compile(
            "^\\* Active site residues in ([A-Za-z0-9_.-]{1,100}) include: (" + LOCATIONS
            + ") \\* Substrate binding residues in \\1 are: (" + LOCATIONS + ") \\* Cofactor binding residues in \\1 are: ("
            + LOCATIONS + ") \\* Do not apply if PP3_Strong applies$");
    private static final java.util.regex.Pattern PM2_SOURCE = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(
            "Located in a mutational hot spot and/or critical and well-established functional domain (_e.g._ active site of an enzyme) without benign variation "
            + "* The variant must be rare (meeting PM2_Supporting) in order to be considered for PM1. "
            + "* Met by a rare variant within the pore helix (amino acids ")
            + "([1-9][0-9]{0,5}) to ([1-9][0-9]{0,5})" + java.util.regex.Pattern.quote(")" 
            + ". This region is known to be a critical region of ") + "[A-Za-z0-9_.-]{1,100}" + java.util.regex.Pattern.quote(
            " and has been confirmed to show an absence of likely benign or benign variants listed in gnomAD.") + "$");
    private PublishedPm1CrossCriterionConditions() { }

    /** Immutable internal adapter. No constructor or JSON receipt can mint an adopted peer result. */
    static final class Facts {
        private final ObjectNode binding;
        private final Map<String, ObjectNode> peers;
        private Facts(ObjectNode binding, Map<String, ObjectNode> peers) {
            this.binding = binding.deepCopy(); this.peers = new LinkedHashMap<>();
            peers.forEach((key, value) -> this.peers.put(key, value.deepCopy()));
        }
    }

    /** Called by the central runtime with its actual resolved module and evaluation/context pointers.
     * Module resolution, sourceSnapshot and wrapper approval remain the caller's existing duty.
     */
    static Facts fromActualModule(ObjectMapper mapper, ObjectNode expectedProjection, ObjectNode module,
            String evaluationPointer, String contextPointer) {
        return fromActualModule(mapper, expectedProjection, module, evaluationPointer, contextPointer, false);
    }
    private static Facts fromActualModule(ObjectMapper mapper, ObjectNode expectedProjection, ObjectNode module,
            String evaluationPointer, String contextPointer, boolean transportAudit) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var binding = binding(mapper, expectedProjection);
        require(evaluationPointer.startsWith("/") && contextPointer.startsWith("/"), "PM1_PEER_POINTER_INVALID");
        var actualContext = module.at(contextPointer);
        require(actualContext.equals(expectedProjection.path("evaluationContext"))
                || actualContext.equals(expectedProjection.path("originalContext")), "PM1_PEER_CONTEXT_MISMATCH");
        for (String field : Set.of("candidateId", "hypothesisId")) {
            var actual = module.path(field).isTextual() ? module.path(field) : module.path("plan").path(field);
            require(actual.isTextual() && actual.equals(expectedProjection.path(field)), "PM1_PEER_CANDIDATE_OR_HYPOTHESIS_MISMATCH");
        }
        var evaluation = module.at(evaluationPointer);
        String criterion = evaluation.path("criterionId").asText();
        require(Set.of("ACMG_AMP_2015:PM2", "ACMG_AMP_2015:PP3", "ACMG_AMP_2015:PM5").contains(criterion), "PM1_PEER_CRITERION_UNSUPPORTED");
        String status = evaluation.has("evaluationResult") ? evaluation.at("/evaluationResult/status").asText()
                : evaluation.path("status").asText();
        if (evaluation.has("contextId"))
            require(evaluation.path("contextId").equals(actualContext.path("contextId")), "PM1_PEER_EVALUATION_CONTEXT_MISMATCH");
        else require(("ACMG_AMP_2015:PP3".equals(criterion)
                && Set.of("/criteria/ACMG_AMP_2015:PP3", "/criteria/PP3").contains(evaluationPointer)
                || transportAudit && "ACMG_AMP_2015:PM5".equals(criterion) && "NOT_EVALUATED".equals(status)
                && evaluationPointer.matches("/evaluations/PM5/criterionEvaluations/(?:0|[1-9][0-9]{0,5})"))
                && module.path("sourceProjection").equals(expectedProjection), "PM1_PEER_EVALUATION_CONTEXT_MISMATCH");
        String strength = transportAudit ? currentPeerStrength(evaluation) : evaluation.path("appliedStrength").asText();
        require(Set.of("MET", "NOT_MET", "NOT_EVALUATED").contains(status), "PM1_PEER_STATUS_INVALID");
        require("MET".equals(status) ? rank(strength) > 0 : "NONE".equals(strength), "PM1_PEER_STRENGTH_INVALID");
        var peer = mapper.createObjectNode().put("criterionId", criterion).put("status", status)
                .put("appliedStrength", strength).put("actualModuleSha256", transportAudit ? currentAuditSha256(mapper, module) : adapter.canonicalSha256(module))
                .put("actualEvaluationSha256", transportAudit ? currentAuditSha256(mapper, evaluation) : adapter.canonicalSha256(evaluation)).put("evaluationPointer", evaluationPointer)
                .put("contextPointer", contextPointer);
        if (transportAudit) peer.put("auditHashMethod", "TRANSPORT_JSON_VALUE_C14N_V1");
        return new Facts(binding, Map.of(criterion, peer));
    }

    /** The batch resolves and checks this actual row before calling; no saved/client summary is accepted. */
    static Facts fromBoundCurrentModule(ObjectMapper mapper, ObjectNode expectedProjection, ObjectNode module,
            String evaluationPointer, String contextPointer, String expectedSourceSha256, String modulePointer) {
        require(expectedSourceSha256 != null && expectedSourceSha256.matches("[a-f0-9]{64}")
                && expectedSourceSha256.equals(module.path("executionSourceSha256").asText()), "PM1_PEER_SOURCE_SNAPSHOT_MISMATCH");
        require(modulePointer != null && modulePointer.matches("/modules/(?:pm2|prediction|reference)/results/(?:0|[1-9][0-9]{0,5})"),
                "PM1_PEER_MODULE_POINTER_INVALID");
        var actual = fromActualModule(mapper, expectedProjection, module, evaluationPointer, contextPointer, true);
        var bound = actual.binding.deepCopy().put("executionSourceSha256", expectedSourceSha256);
        var peers = new LinkedHashMap<String, ObjectNode>();
        actual.peers.forEach((key, value) -> peers.put(key, value.deepCopy().put("actualModulePointer", modulePointer)
                .put("executionSourceSha256", expectedSourceSha256).put("reasonCode", currentPeerReason(module.at(evaluationPointer)))));
        return new Facts(bound, peers);
    }
    static String currentPeerReason(JsonNode evaluation) {
        String reason = evaluation.has("evaluationResult") ? evaluation.at("/evaluationResult/reasonCode").asText() : evaluation.path("reasonCode").asText();
        return reason.matches("[A-Z][A-Z0-9_]{2,180}") ? reason : "PM1_PEER_ORIGINAL_REASON_NOT_PROVIDED";
    }
    /** Same non-MET normalization as the ordinary batch wrapper; never infer a MET strength. */
    static String currentPeerStrength(JsonNode evaluation) {
        String status = evaluation.has("evaluationResult") ? evaluation.at("/evaluationResult/status").asText() : evaluation.path("status").asText();
        return !evaluation.hasNonNull("appliedStrength") && Set.of("NOT_MET", "NOT_EVALUATED").contains(status)
                ? "NONE" : evaluation.path("appliedStrength").asText();
    }

    /** Audit the already produced JSON transport value, not a scientific numeric input.
     * Type prefixes prevent text/numeric collisions; numeric scale is transport-independent.
     * Context/allele/source and rule identities retain the unchanged strict scientific c14n.
     */
    static String currentAuditSha256(ObjectMapper mapper, JsonNode node) {
        return new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(transportAuditProjection(mapper, node));
    }
    private static JsonNode transportAuditProjection(ObjectMapper mapper, JsonNode node) {
        if (node.isNumber()) {
            require(!(node.isDouble() || node.isFloat()) || Double.isFinite(node.doubleValue()), "PM1_PEER_AUDIT_NONFINITE_VALUE");
            return mapper.getNodeFactory().textNode("number:" + node.decimalValue().stripTrailingZeros().toPlainString());
        }
        if (node.isTextual()) return mapper.getNodeFactory().textNode("string:" + node.asText());
        if (node.isObject()) {
            var copy = mapper.createObjectNode(); node.properties().iterator().forEachRemaining(entry -> copy.set(entry.getKey(), transportAuditProjection(mapper, entry.getValue()))); return copy;
        }
        if (node.isArray()) {
            var copy = mapper.createArrayNode(); node.forEach(value -> copy.add(transportAuditProjection(mapper, value))); return copy;
        }
        require(node.isNull() || node.isBoolean(), "PM1_PEER_AUDIT_JSON_VALUE_UNSUPPORTED"); return node.deepCopy();
    }

    static Facts combine(Facts first, Facts next) {
        require(first.binding.equals(next.binding), "PM1_PEER_CONTEXT_MISMATCH");
        var peers = new LinkedHashMap<>(first.peers);
        next.peers.forEach((criterion, peer) -> {
            var previous = peers.putIfAbsent(criterion, peer);
            require(previous == null || previous.equals(peer), "PM1_PEER_RESULT_CONFLICT");
        });
        return new Facts(first.binding, peers);
    }

    static void validate(JsonNode relation, JsonNode selection, RuntimeEvidenceV2Adapter adapter) {
        if ("ACMG_AMP_2015:PM5".equals(relation.path("criterionId").asText())) {
            PublishedPm1Pm5Exclusion.validate(relation, selection, adapter); return;
        }
        require(only(relation, Set.of("criterionId", "relation", "minimumStrength", "sourcePointer", "sourceSha256")),
                "PM1_PUBLISHED_CROSS_CRITERION_CONDITION_UNSUPPORTED");
        String operator = relation.path("relation").asText(), criterion = relation.path("criterionId").asText();
        String strength = relation.path("minimumStrength").asText();
        boolean requiresPm2 = REQUIRE.equals(operator) && "ACMG_AMP_2015:PM2".equals(criterion) && "SUPPORTING".equals(strength);
        boolean excludesPp3 = EXCLUDE.equals(operator) && "ACMG_AMP_2015:PP3".equals(criterion) && "STRONG".equals(strength);
        require(requiresPm2 || excludesPp3, "PM1_PUBLISHED_CROSS_CRITERION_CONDITION_UNSUPPORTED");
        String pointer = relation.path("sourcePointer").asText();
        String prefix = selection.at("/record/clausePointer").asText() + "/entContent/strengthDescriptor/";
        require(pointer.matches(java.util.regex.Pattern.quote(prefix) + "[0-9]+"), "PM1_PUBLISHED_PEER_SOURCE_INVALID");
        var source = selection.at("/specification/original").at(pointer);
        require(source.isObject() && "Applicable".equals(source.path("applicability").asText())
                && (!source.has("status") || "approved".equals(source.path("status").asText()))
                && "Moderate".equals(source.path("strength").asText())
                && adapter.canonicalSha256(source).equals(relation.path("sourceSha256").asText()), "PM1_PUBLISHED_PEER_SOURCE_INVALID");
        String text = source.path("text").asText().replace("\\_", "_").replace('\u00a0', ' ').replaceAll("\\s+", " ").strip();
        // The complete supported clause must match. A familiar sentence inside an unknown
        // instruction, negation or extra requirement is not permission to drop that text.
        var match = (requiresPm2 ? PM2_SOURCE : PP3_SOURCE).matcher(text);
        require(text.length() <= 8192 && match.matches(), "PM1_PUBLISHED_PEER_SOURCE_SEMANTICS_UNSUPPORTED");
        if (requiresPm2) require(Integer.parseInt(match.group(1)) <= Integer.parseInt(match.group(2))
                && Integer.parseInt(match.group(2)) <= 100000, "PM1_PUBLISHED_PEER_SOURCE_SEMANTICS_UNSUPPORTED");
    }

    /** A compiler cannot remove a mandatory source condition by omitting its interpreted field. */
    static void requireDeclaredSourceCondition(JsonNode rule, JsonNode source, JsonNode selection, RuntimeEvidenceV2Adapter adapter) {
        String text = source.path("text").asText().replace("\\_", "_").replace('\u00a0', ' ').replaceAll("\\s+", " ").strip();
        boolean pm2 = text.contains("PM2"), pp3 = text.contains("PP3");
        if (!pm2 && !pp3) return;
        require(pm2 != pp3, "PM1_PUBLISHED_CROSS_CRITERION_CONDITION_UNSUPPORTED");
        JsonNode found = null;
        for (var relation : selection.at("/record/interpretation/crossCriterionRelations"))
            if (relation.path("sourcePointer").equals(rule.path("sourcePointer"))
                    && relation.path("sourceSha256").equals(rule.path("sourceSha256"))
                    && (pm2 ? "ACMG_AMP_2015:PM2" : "ACMG_AMP_2015:PP3").equals(relation.path("criterionId").asText())) {
                require(found == null, "PM1_PUBLISHED_PEER_CONDITION_DUPLICATE"); found = relation;
            }
        require(found != null, "PM1_PUBLISHED_REQUIRED_PEER_CONDITION_OMITTED");
        validate(java.util.Objects.requireNonNull(found), selection, adapter);
        require(rule.path("start").isIntegralNumber() && rule.path("start").canConvertToInt()
                && rule.path("end").isIntegralNumber() && rule.path("end").canConvertToInt()
                && "SUPPORT".equals(rule.path("effect").asText()), "PM1_PUBLISHED_PEER_SOURCE_REGION_MISMATCH");
        int start = rule.path("start").asInt(), end = rule.path("end").asInt();
        if (pm2) {
            var pore = PM2_SOURCE.matcher(text);
            require(pore.matches() && start == Integer.parseInt(pore.group(1)) && end == Integer.parseInt(pore.group(2)),
                    "PM1_PUBLISHED_PEER_SOURCE_REGION_MISMATCH");
        } else {
            var atoms = PP3_SOURCE.matcher(text);
            require(atoms.matches(), "PM1_PUBLISHED_PEER_SOURCE_SEMANTICS_UNSUPPORTED");
            boolean matches = false;
            for (int group = 2; group <= 4; group++) for (String atom : atoms.group(group).split(", ")) {
                if (Character.isDigit(atom.charAt(0))) {
                    String[] bounds = atom.split("-");
                    int lo = Integer.parseInt(bounds[0]), hi = Integer.parseInt(bounds[1]);
                    require(lo <= hi && hi <= 100000, "PM1_PUBLISHED_PEER_SOURCE_SEMANTICS_UNSUPPORTED");
                    if (start == lo && end == hi) matches = true;
                } else {
                    int position = Integer.parseInt(atom.substring(3));
                    require(position <= 100000, "PM1_PUBLISHED_PEER_SOURCE_SEMANTICS_UNSUPPORTED");
                    if (start == position && end == position) {
                        var residues = rule.path("referenceResidues");
                        String expectedAminoAcid = AMINO_ACIDS.get(atom.substring(0, 3));
                        require(expectedAminoAcid != null && "CRITICAL_RESIDUE".equals(rule.path("regionType").asText())
                                && residues.isArray() && residues.size() == 1
                                && residues.get(0).path("position").isIntegralNumber()
                                && residues.get(0).path("position").canConvertToInt()
                                && residues.get(0).path("position").asInt() == position
                                && expectedAminoAcid.equals(residues.get(0).path("aminoAcid").asText()),
                                "PM1_PUBLISHED_PEER_SOURCE_RESIDUE_MISMATCH");
                        matches = true;
                    }
                }
            }
            require(matches, "PM1_PUBLISHED_PEER_SOURCE_REGION_MISMATCH");
        }
    }

    static ObjectNode assess(ObjectMapper mapper, JsonNode selection, @Nullable Facts facts) {
        var out = mapper.createObjectNode().put("schemaVersion", "pm1-published-peer-conditions-v1")
                .put("methodVersion", VERSION).put("status", "READY").put("reasonCode", "PM1_PUBLISHED_PEER_CONDITIONS_MET")
                .put("selectionAbsenceMeansNegative", false);
        var checks = out.putArray("checks");
        try {
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            var seen = new java.util.HashSet<String>();
            for (var relation : selection.at("/record/interpretation/crossCriterionRelations")) {
                if ("NOT_MUTUALLY_EXCLUSIVE".equals(relation.path("relation").asText())) continue;
                validate(relation, selection, adapter);
                String criterion = relation.path("criterionId").asText();
                require(seen.add(criterion), "PM1_PUBLISHED_PEER_CONDITION_DUPLICATE");
                var check = checks.addObject(); check.set("required", relation.deepCopy());
                var peer = facts == null ? null : facts.peers.get(criterion);
                if (peer == null || "NOT_EVALUATED".equals(peer.path("status").asText())) {
                    check.put("status", "UNAVAILABLE").put("missingInformation", "ACTUAL_SAME_CONTEXT_ADOPTED_CRITERION_RESULT");
                    if (peer != null) check.set("available", peer.deepCopy());
                    if (!"NOT_MET".equals(out.path("status").asText()))
                        out.put("status", "NOT_EVALUATED").put("reasonCode", "PM1_PUBLISHED_PEER_RESULT_UNAVAILABLE");
                    continue;
                }
                check.set("available", peer.deepCopy());
                boolean meets = "MET".equals(peer.path("status").asText())
                        && rank(peer.path("appliedStrength").asText()) >= rank(relation.path("minimumStrength").asText());
                boolean pass = REQUIRE.equals(relation.path("relation").asText()) ? meets : !meets;
                check.put("status", pass ? "MET" : "NOT_MET");
                if (!pass) out.put("status", "NOT_MET").put("reasonCode", REQUIRE.equals(relation.path("relation").asText())
                        ? "PM1_PUBLISHED_REQUIRED_PEER_NOT_MET" : "PM1_PUBLISHED_PEER_EXCLUSION_APPLIES");
            }
            if (facts != null) out.set("binding", facts.binding.deepCopy());
            return out;
        } catch (IllegalArgumentException invalid) {
            return out.put("status", "NOT_EVALUATED").put("reasonCode", invalid.getMessage());
        }
    }

    static boolean sameContext(Facts facts, JsonNode context, ObjectMapper mapper) {
        return facts.binding.path("evaluationContextSha256").asText().equals(new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(context));
    }
    static boolean sameCurrentExecution(Facts facts, @Nullable ObjectNode expectedProjection, @Nullable String sourceSha256, ObjectMapper mapper) {
        if (expectedProjection == null || sourceSha256 == null || !sourceSha256.matches("[a-f0-9]{64}")) return false;
        var expected = binding(mapper, expectedProjection).put("executionSourceSha256", sourceSha256);
        return expected.equals(facts.binding);
    }

    private static ObjectNode binding(ObjectMapper mapper, ObjectNode projection) {
        require("selected-legacy-context-projection-v1".equals(projection.path("schemaVersion").asText())
                && projection.path("originalContext").isObject() && projection.path("evaluationContext").isObject(), "PM1_PEER_PROJECTION_REQUIRED");
        for (String field : Set.of("candidateId", "hypothesisId"))
            require(projection.path(field).isTextual() && !projection.path(field).asText().isBlank(), "PM1_PEER_PROJECTION_REQUIRED");
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var body = projection.deepCopy(); body.remove("bindingSha256");
        require(adapter.canonicalSha256(body).equals(projection.path("bindingSha256").asText()), "PM1_PEER_PROJECTION_BINDING_INVALID");
        adapter.validateVersionedContext((ObjectNode) projection.path("originalContext"));
        adapter.validateCompleteContext((ObjectNode) projection.path("evaluationContext"));
        var original = projection.path("originalContext"); var projected = (ObjectNode) projection.path("evaluationContext");
        var expected = original.deepCopy();
        ((ObjectNode) expected).put("schemaVersion", "evidence-context-v2");
        ((ObjectNode) expected).put("contextId", adapter.canonicalContextId((ObjectNode) expected));
        require(expected.equals(projected), "PM1_PEER_PROJECTION_CONTEXT_MISMATCH");
        var binding = mapper.createObjectNode().put("originalContextSha256", adapter.canonicalSha256(original))
                .put("evaluationContextSha256", adapter.canonicalSha256(projected)).put("projectionBindingSha256", projection.path("bindingSha256").asText());
        binding.set("candidateId", projection.path("candidateId")); binding.set("hypothesisId", projection.path("hypothesisId"));
        return binding;
    }

    private static int rank(String strength) {
        return switch (strength) { case "SUPPORTING" -> 1; case "MODERATE" -> 2; case "STRONG" -> 3; case "VERY_STRONG" -> 4; default -> 0; };
    }
    private static boolean only(JsonNode node, Set<String> fields) {
        if (!node.isObject()) return false; var names = node.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) return false; return true;
    }
    private static void require(boolean ok, String reason) { if (!ok) throw new IllegalArgumentException(reason); }
}
