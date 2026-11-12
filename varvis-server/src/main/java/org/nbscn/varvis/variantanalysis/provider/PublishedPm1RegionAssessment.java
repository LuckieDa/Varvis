package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.lang.Nullable;

/** Finite project interpretation of complete published PM1 clauses, independent of local review receipts. */
final class PublishedPm1RegionAssessment {
    static final String TYPE = "PUBLISHED_PM1_PROTEIN_LOCATIONS";
    static final String VERSION = "1.0.0";
    private static final String AMINO = "ACDEFGHIKLMNPQRSTVWY";
    private static final String SUBSTITUTION = "[" + AMINO + "][1-9][0-9]{0,5}[" + AMINO + "]";
    private static final Pattern EXPLICIT_SUBSTITUTIONS = Pattern.compile("^Defined to include missense alterations of "
            + "(one|two|three|four|five|six|seven|eight|nine|ten|[1-9][0-9]?) [A-Za-z0-9][A-Za-z0-9 -]{0,79} residues?: ("
            + SUBSTITUTION + "(?:(?:, | and |, and )" + SUBSTITUTION + "){0,31}) \\(PMID: [1-9][0-9]{0,8}\\)\\.$");
    private static final Pattern SUBSTITUTION_TOKEN = Pattern.compile("([" + AMINO + "])([1-9][0-9]{0,5})([" + AMINO + "])");
    private record Substitution(int position, String reference, String alternate) { }
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    PublishedPm1RegionAssessment(ObjectMapper mapper) { this.mapper = mapper; adapter = new RuntimeEvidenceV2Adapter(mapper); }

    ObjectNode assess(ObjectNode selection, ObjectNode model, JsonNode association) {
        return assess(selection, model, association, null, null);
    }

    ObjectNode assess(ObjectNode selection, ObjectNode model, JsonNode association, @Nullable ObjectNode evaluationContext,
            @Nullable PublishedPm1CrossCriterionConditions.Facts peerFacts) {
        var out = mapper.createObjectNode().put("status", "NOT_EVALUATED").put("appliedStrength", "NONE")
                .put("reasonCode", "PM1_PUBLISHED_SOURCE_SCOPE_NOT_COVERED").put("qualificationBasis", "PUBLISHED_EXPERT_SPECIFICATION")
                .put("benignCountsInferred", false).put("createsReviewReceipt", false);
        try {
            var row = selection.path("record"); var original = selection.at("/specification/original");
            var interpretation = row.path("interpretation");
            require(only(interpretation, Set.of("type", "version", "consequenceScope", "qualificationBasis", "proteinIdentity",
                    "rules", "precedence", "residualConditions", "crossCriterionRelations"))
                    && TYPE.equals(interpretation.path("type").asText()) && VERSION.equals(interpretation.path("version").asText())
                    && "PURE_MISSENSE_SNV".equals(interpretation.path("consequenceScope").asText())
                    && "PUBLISHED_EXPERT_SPECIFICATION".equals(interpretation.path("qualificationBasis").asText()),
                    "PM1_PUBLISHED_INTERPRETATION_UNSUPPORTED");
            require(interpretation.path("residualConditions").isArray() && interpretation.path("residualConditions").isEmpty(),
                    "PM1_PUBLISHED_ADDITIONAL_CONDITIONS_UNSUPPORTED");
            require(interpretation.path("crossCriterionRelations").isArray(), "PM1_PUBLISHED_RELATIONS_INVALID");
            PublishedPm1Pm5Exclusion.requireDeclared(selection, adapter);
            boolean hasPeerConditions = false;
            for (var relation : interpretation.path("crossCriterionRelations")) {
                if ("NOT_MUTUALLY_EXCLUSIVE".equals(relation.path("relation").asText())) {
                    require(only(relation, Set.of("criterionId", "relation", "sourcePointer", "sourceSha256"))
                            && relation.path("criterionId").asText().matches("ACMG_AMP_2015:[A-Z]+[0-9]+"),
                            "PM1_PUBLISHED_CROSS_CRITERION_CONDITION_UNSUPPORTED");
                    source(relation, original);
                } else {
                    PublishedPm1CrossCriterionConditions.validate(relation, selection, adapter);
                    hasPeerConditions = true;
                }
            }
            if (hasPeerConditions && peerFacts != null) require(evaluationContext != null
                    && PublishedPm1CrossCriterionConditions.sameContext(peerFacts, evaluationContext, mapper), "PM1_PEER_CONTEXT_MISMATCH");
            var protein = interpretation.path("proteinIdentity");
            require(only(protein, Set.of("mode", "accession", "sourcePointer", "sourceSha256")), "PM1_PUBLISHED_PROTEIN_IDENTITY_INVALID");
            if ("DECLARED_PROTEIN".equals(protein.path("mode").asText())) {
                String declared = protein.path("accession").asText();
                require(declared.matches("(?:NP_[0-9]+|ENSP[0-9]+)\\.[1-9][0-9]*"), "PM1_PUBLISHED_PROTEIN_IDENTITY_INVALID");
                source(protein, original);
                require(declared.equals(model.path("protein").asText()) ||
                        "FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE".equals(model.at("/transcriptBridge/status").asText())
                        && declared.equals(model.at("/transcriptBridge/refseqProtein").asText())
                        && selection.path("declaredTranscript").asText().equals(model.at("/transcriptBridge/refseqTranscript").asText()),
                        "PM1_PUBLISHED_PROTEIN_IDENTITY_MISMATCH");
            } else require("SOURCE_TRANSCRIPT_DEFINED".equals(protein.path("mode").asText()) && protein.size() == 1,
                    "PM1_PUBLISHED_PROTEIN_IDENTITY_INVALID");
            String cds = model.path("cdsSequence").asText();
            int length = model.path("proteinLength").asInt();
            require(cds.matches("[ACGT]+") && length > 0 && length <= 100_000 && cds.length() == (length + 1) * 3,
                    "PM1_PUBLISHED_MODEL_INVALID");
            var rules = interpretation.path("rules"); var priority = interpretation.path("precedence");
            require(rules.isArray() && !rules.isEmpty() && rules.size() <= 1000 && priority.isArray() && priority.size() <= 1000,
                    "PM1_PUBLISHED_RULES_INVALID");
            var ids = new HashSet<String>();
            for (var rule : rules) {
                require(only(rule, Set.of("ruleId", "start", "end", "regionType", "effect", "strength", "sourcePointer", "sourceSha256",
                        "referenceResidues", "expectedSequence", "exactSubstitutions")) && text(rule, "ruleId") && ids.add(rule.path("ruleId").asText()),
                        "PM1_PUBLISHED_RULE_INVALID");
                require(rule.path("start").isIntegralNumber() && rule.path("start").canConvertToInt()
                        && rule.path("end").isIntegralNumber() && rule.path("end").canConvertToInt()
                        && rule.path("start").asInt() >= 1 && rule.path("end").asInt() >= rule.path("start").asInt()
                        && rule.path("end").asInt() <= length, "PM1_PUBLISHED_INTERVAL_INVALID");
                require(Set.of("MUTATIONAL_HOTSPOT", "CRITICAL_FUNCTIONAL_REGION", "CRITICAL_RESIDUE", "EXCLUSION")
                        .contains(rule.path("regionType").asText()), "PM1_PUBLISHED_REGION_TYPE_UNSUPPORTED");
                var nativeSource = source(rule, original);
                boolean exclude = "EXCLUDE".equals(rule.path("effect").asText());
                if (exclude) require("EXCLUSION".equals(rule.path("regionType").asText()) && "NONE".equals(rule.path("strength").asText())
                        && nativeSource.isTextual() && !nativeSource.asText().isBlank(), "PM1_PUBLISHED_EXCLUSION_INVALID");
                else {
                    require("SUPPORT".equals(rule.path("effect").asText()) && !"EXCLUSION".equals(rule.path("regionType").asText()),
                            "PM1_PUBLISHED_RULE_INVALID");
                    require(Set.of("MODERATE", "SUPPORTING").contains(rule.path("strength").asText()), "PM1_PUBLISHED_STRENGTH_UNSUPPORTED");
                    String prefix = row.path("clausePointer").asText() + "/entContent/strengthDescriptor/";
                    require(rule.path("sourcePointer").asText().matches(java.util.regex.Pattern.quote(prefix) + "[0-9]+")
                            && nativeSource.isObject() && "Applicable".equals(nativeSource.path("applicability").asText())
                            && (!nativeSource.has("status") || "approved".equals(nativeSource.path("status").asText()))
                            && nativeSource.path("strength").asText().toUpperCase(java.util.Locale.ROOT).equals(rule.path("strength").asText())
                            && text(nativeSource, "text"), "PM1_PUBLISHED_SOURCE_STRENGTH_NOT_APPROVED");
                    PublishedPm1CrossCriterionConditions.requireDeclaredSourceCondition(rule, nativeSource, selection, adapter);
                    String sourceText = nativeSource.path("text").asText().replaceAll("(?U)\\s+", " ").strip();
                    if (sourceText.startsWith("Defined to include missense alterations of "))
                        require(rule.has("exactSubstitutions"), "PM1_PUBLISHED_EXACT_SUBSTITUTIONS_REQUIRED");
                }
                var residues = rule.path("referenceResidues");
                require(residues.isArray() && residues.size() <= 1000, "PM1_PUBLISHED_REFERENCE_RESIDUES_INVALID");
                var positions = new HashSet<Integer>();
                for (var residue : residues) {
                    require(only(residue, Set.of("position", "aminoAcid")) && residue.path("position").isIntegralNumber()
                            && residue.path("position").canConvertToInt() && positions.add(residue.path("position").asInt())
                            && Pm1RegionSelection.covers(rule, residue.path("position").asInt())
                            && residue.path("aminoAcid").asText().matches("[ACDEFGHIKLMNPQRSTVWY]"),
                            "PM1_PUBLISHED_REFERENCE_RESIDUES_INVALID");
                    int index = (residue.path("position").asInt() - 1) * 3;
                    require(residue.path("aminoAcid").asText().equals(Character.toString(TruncatingSequence.translateCodon(cds.substring(index, index + 3)))),
                            "PM1_PUBLISHED_REFERENCE_RESIDUE_MISMATCH");
                }
                if ("CRITICAL_RESIDUE".equals(rule.path("regionType").asText())) require(rule.path("start").equals(rule.path("end"))
                        && residues.size() == 1, "PM1_PUBLISHED_REFERENCE_RESIDUES_INVALID");
                if (rule.has("expectedSequence")) {
                    String expected = rule.path("expectedSequence").asText();
                    require(expected.matches("[ACDEFGHIKLMNPQRSTVWY]+")
                            && expected.length() == rule.path("end").asInt() - rule.path("start").asInt() + 1,
                            "PM1_PUBLISHED_MOTIF_INVALID");
                    for (int i = 0; i < expected.length(); i++) {
                        int index = (rule.path("start").asInt() - 1 + i) * 3;
                        require(expected.charAt(i) == TruncatingSequence.translateCodon(cds.substring(index, index + 3)),
                                "PM1_PUBLISHED_MOTIF_MISMATCH");
                    }
                }
                if (rule.has("exactSubstitutions")) validateSubstitutions(rule, nativeSource, cds);
            }
            var pairs = new HashSet<String>(); var graph = Pm1RegionSelection.graph(priority);
            for (var relation : priority) {
                require(only(relation, Set.of("higherRuleId", "lowerRuleId", "sourcePointer", "sourceSha256")), "PM1_PUBLISHED_PRECEDENCE_INVALID");
                String hi = relation.path("higherRuleId").asText(), lo = relation.path("lowerRuleId").asText();
                require(ids.contains(hi) && ids.contains(lo) && !hi.equals(lo) && pairs.add(hi + "\n" + lo), "PM1_PUBLISHED_PRECEDENCE_INVALID");
                source(relation, original);
                require(!Pm1RegionSelection.reaches(graph, lo, hi, new HashSet<>()), "PM1_PUBLISHED_PRECEDENCE_CONFLICT");
            }
            require(association.path("proteinPosition").isIntegralNumber() && association.path("proteinPosition").canConvertToInt()
                    && association.path("proteinPosition").asInt() > 0 && association.path("proteinPosition").asInt() <= length,
                    "PM1_PUBLISHED_POSITION_INVALID");
            int position = association.path("proteinPosition").asInt();
            var matches = new ArrayList<JsonNode>(); var matched = out.putArray("matchedRules");
            boolean substitutionNotCovered = false;
            for (var rule : rules) if (Pm1RegionSelection.covers(rule, position)) {
                if (rule.has("exactSubstitutions")) {
                    var observed = verifiedSubstitution(model, association);
                    boolean covered = false;
                    for (var condition : rule.path("exactSubstitutions")) if (observed.equals(substitution(condition))) covered = true;
                    out.withArray("conditionChecks").addObject().put("ruleId", rule.path("ruleId").asText())
                            .put("condition", "SOURCE_BOUND_EXACT_MISSENSE_SUBSTITUTION")
                            .put("status", covered ? "MATCH" : "NO_MATCH").put("proteinPosition", position)
                            .put("refAminoAcid", observed.reference()).put("altAminoAcid", observed.alternate());
                    if (!covered) { substitutionNotCovered = true; continue; }
                }
                matches.add(rule); matched.add(rule.deepCopy());
            }
            out.put("proteinPosition", position); out.set("crossCriterionRelations", interpretation.path("crossCriterionRelations").deepCopy());
            if (matches.isEmpty()) return substitutionNotCovered
                    ? out.put("reasonCode", "PM1_PUBLISHED_EXACT_SUBSTITUTION_NOT_COVERED") : out;
            var winner = Pm1RegionSelection.choose(matches, priority);
            if (winner == null) {
                winner = sameSourceSupportRepresentative(matches, interpretation);
                if (winner != null) out.putObject("sourceOverlapResolution")
                        .put("method", "SAME_PUBLISHED_SUPPORT_SINGLE_APPLICATION").put("matchedRulesRetained", true)
                        .put("criterionApplications", 1).put("scientificPrecedenceClaimed", false);
            }
            require(winner != null, "PM1_PUBLISHED_REGION_CONFLICT");
            var chosen = java.util.Objects.requireNonNull(winner); out.set("selectedRule", chosen.deepCopy());
            if ("EXCLUDE".equals(chosen.path("effect").asText()))
                return out.put("status", "NOT_MET").put("reasonCode", "PM1_PUBLISHED_EXPLICIT_EXCLUSION");
            if (hasPeerConditions) {
                var checked = PublishedPm1CrossCriterionConditions.assess(mapper, selection, peerFacts);
                out.set("crossCriterionConditionCheck", checked);
                if (!"READY".equals(checked.path("status").asText()))
                    return out.put("status", checked.path("status").asText()).put("reasonCode", checked.path("reasonCode").asText());
            }
            return out.put("status", "MET").put("reasonCode", "PM1_PUBLISHED_CRITICAL_LOCATION_MATCH")
                    .put("appliedStrength", chosen.path("strength").asText());
        } catch (IllegalArgumentException invalid) { return out.put("reasonCode", GeneralPm1Runtime.safeReason(invalid)); }
    }
    private JsonNode source(JsonNode item, JsonNode original) {
        String pointer = item.path("sourcePointer").asText();
        require(pointer.startsWith("/ld/") || pointer.startsWith("/entContent/"), "PM1_PUBLISHED_SOURCE_POINTER_INVALID");
        var value = original.at(pointer);
        require(!value.isMissingNode() && adapter.canonicalSha256(value).equals(item.path("sourceSha256").asText()), "PM1_PUBLISHED_SOURCE_BINDING_INVALID");
        return value;
    }
    /** Only already source-atom-verified support from the same complete conditional clause.
     * Tyr377 is listed both as an active residue and within the 377..379 cofactor interval.
     * These are two descriptions of one Moderate support, not conflicting strengths or two awards.
     */
    @Nullable private static JsonNode sameSourceSupportRepresentative(List<JsonNode> matches, JsonNode interpretation) {
        if (matches.size() < 2) return null;
        var first = matches.get(0); boolean knownAtomSource = false;
        for (var relation : interpretation.path("crossCriterionRelations"))
            if ("ACMG_AMP_2015:PP3".equals(relation.path("criterionId").asText())
                    && PublishedPm1CrossCriterionConditions.EXCLUDE.equals(relation.path("relation").asText())
                    && "STRONG".equals(relation.path("minimumStrength").asText())
                    && relation.path("sourcePointer").equals(first.path("sourcePointer"))
                    && relation.path("sourceSha256").equals(first.path("sourceSha256"))) knownAtomSource = true;
        if (!knownAtomSource) return null;
        for (var row : matches) if (!"SUPPORT".equals(row.path("effect").asText()) || row.has("exactSubstitutions")
                || !"MODERATE".equals(row.path("strength").asText())
                || !row.path("sourcePointer").equals(first.path("sourcePointer"))
                || !row.path("sourceSha256").equals(first.path("sourceSha256"))) return null;
        return matches.stream().min(java.util.Comparator.comparingInt((JsonNode row) -> row.path("end").asInt() - row.path("start").asInt())
                .thenComparing(row -> row.path("ruleId").asText())).orElse(null);
    }
    /** A finite positive source sentence, not token harvesting from arbitrary comments or exclusions. */
    private static void validateSubstitutions(JsonNode rule, JsonNode nativeSource, String cds) {
        require("SUPPORT".equals(rule.path("effect").asText()), "PM1_PUBLISHED_SUBSTITUTION_RULE_INVALID");
        String text = nativeSource.path("text").asText().replaceAll("(?U)\\s+", " ").strip();
        var source = EXPLICIT_SUBSTITUTIONS.matcher(text);
        require(text.length() <= 2048 && source.matches(), "PM1_PUBLISHED_SUBSTITUTION_SOURCE_UNSUPPORTED");
        String count = source.group(1);
        int wordCount = List.of("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten").indexOf(count) + 1;
        int expected = wordCount > 0 ? wordCount : Integer.parseInt(count);
        var tokens = SUBSTITUTION_TOKEN.matcher(source.group(2));
        var fromSource = new HashSet<Substitution>();
        int total = 0;
        while (tokens.find()) {
            int position = Integer.parseInt(tokens.group(2));
            var value = new Substitution(position, tokens.group(1), tokens.group(3)); total++;
            require(fromSource.add(value) && !value.reference().equals(value.alternate()), "PM1_PUBLISHED_SUBSTITUTION_SOURCE_INVALID");
            require(position <= cds.length() / 3 - 1 && value.reference().charAt(0)
                    == TruncatingSequence.translateCodon(cds.substring((position - 1) * 3, position * 3)),
                    "PM1_PUBLISHED_SUBSTITUTION_SOURCE_REFERENCE_MISMATCH");
        }
        require(total == expected && total <= 32, "PM1_PUBLISHED_SUBSTITUTION_SOURCE_INVALID");
        var declared = rule.path("exactSubstitutions");
        require(declared.isArray() && !declared.isEmpty() && declared.size() <= 32, "PM1_PUBLISHED_SUBSTITUTIONS_INVALID");
        var actual = new HashSet<Substitution>();
        for (var entry : declared) {
            require(only(entry, Set.of("position", "refAminoAcid", "altAminoAcid"))
                    && entry.path("position").isIntegralNumber() && entry.path("position").canConvertToInt()
                    && entry.path("position").asInt() > 0 && entry.path("refAminoAcid").asText().matches("[" + AMINO + "]")
                    && entry.path("altAminoAcid").asText().matches("[" + AMINO + "]"), "PM1_PUBLISHED_SUBSTITUTIONS_INVALID");
            var value = substitution(entry);
            require(actual.add(value) && Pm1RegionSelection.covers(rule, value.position()), "PM1_PUBLISHED_SUBSTITUTIONS_INVALID");
        }
        fromSource.removeIf(value -> !Pm1RegionSelection.covers(rule, value.position()));
        require(actual.equals(fromSource), "PM1_PUBLISHED_SUBSTITUTION_SOURCE_MISMATCH");
    }
    private Substitution verifiedSubstitution(ObjectNode model, JsonNode association) {
        int position = association.path("proteinPosition").asInt(), start = (position - 1) * 3;
        String cds = model.path("cdsSequence").asText(), reference = association.path("refCodon").asText(), alternate = association.path("altCodon").asText();
        require(association.path("refAminoAcid").asText().matches("[" + AMINO + "]")
                && association.path("altAminoAcid").asText().matches("[" + AMINO + "]")
                && reference.matches("[ACGT]{3}") && alternate.matches("[ACGT]{3}")
                && association.path("cdsPosition").isIntegralNumber() && association.path("cdsPosition").canConvertToInt(),
                "PM1_PUBLISHED_SUBSTITUTION_FACTS_REQUIRED");
        int coding = association.path("cdsPosition").asInt();
        require(reference.equals(cds.substring(start, start + 3))
                && adapter.canonicalSha256(model).equals(association.path("nativeModelSha256").asText()),
                "PM1_PUBLISHED_SUBSTITUTION_MODEL_MISMATCH");
        int changed = -1;
        for (int index = 0; index < 3; index++) if (reference.charAt(index) != alternate.charAt(index)) {
            require(changed < 0, "PM1_PUBLISHED_SUBSTITUTION_NOT_MISSENSE_SNV"); changed = index;
        }
        require(changed >= 0 && coding == start + changed + 1
                && association.path("refAminoAcid").asText().charAt(0) == TruncatingSequence.translateCodon(reference)
                && association.path("altAminoAcid").asText().charAt(0) == TruncatingSequence.translateCodon(alternate)
                && !association.path("refAminoAcid").equals(association.path("altAminoAcid")),
                "PM1_PUBLISHED_SUBSTITUTION_ASSOCIATION_MISMATCH");
        return new Substitution(position, association.path("refAminoAcid").asText(), association.path("altAminoAcid").asText());
    }
    private static Substitution substitution(JsonNode entry) {
        return new Substitution(entry.path("position").asInt(), entry.path("refAminoAcid").asText(), entry.path("altAminoAcid").asText());
    }
    private static boolean only(JsonNode node, Set<String> fields) {
        if (!node.isObject()) return false;
        var names = node.fieldNames(); while (names.hasNext()) if (!fields.contains(names.next())) return false;
        return true;
    }
    private static boolean text(JsonNode node, String field) { return node.path(field).isTextual() && !node.path(field).asText().isBlank(); }
    private static void require(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }
}
