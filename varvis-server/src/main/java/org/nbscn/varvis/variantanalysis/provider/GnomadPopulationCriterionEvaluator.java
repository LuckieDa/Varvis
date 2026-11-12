package org.nbscn.varvis.variantanalysis.provider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
/** Isolated, deterministic E3-P1A BA1/BS1 evaluator. */
public final class GnomadPopulationCriterionEvaluator {
    ObjectNode evaluateBs1Policy(JsonNode context, ObjectNode evidence, JsonNode policy, JsonNode selection, JsonNode quality) {
        return Bs1CriterionEvaluator.evaluate(mapper, context, evidence, policy, selection, quality);
    }
    public static final String EVALUATOR_ID = "gnomad-population-criterion-evaluator";
    public static final String EVALUATOR_VERSION = "1.0.0-e3-p1a";
    public static final String BA1_CONDITIONAL_EVALUATOR_VERSION = "2.4.0-catalog-ba1-native-bounds";
    private static final String PM2_EVALUATOR_VERSION = "1.2.0-pm2-multigene";
    private static final String BA1 = "ACMG_AMP_2015:BA1";
    private static final String BS1 = "ACMG_AMP_2015:BS1";
    private static final String PM2 = "ACMG_AMP_2015:PM2";
    private static final String C14N = "varvis-json-c14n-v1";
    private static final String GT = "GREATER_THAN";
    private static final String GTE = "GREATER_THAN_OR_EQUAL";
    private static final String LT = "LESS_THAN";
    private static final String LTE = "LESS_THAN_OR_EQUAL";
    private static final String EXOMES = "EXOMES";
    private static final String GENOMES = "GENOMES";
    private static final String EXOME_AN_METRIC = "gnomad.exomes.allSites.adjAn";
    private static final String GENOME_AN_METRIC = "gnomad.genomes.allSites.adjAn";
    private static final String EXOME_FLAG_PREFIX = "gnomad.exomes.allSites.regionFlag.";
    private static final Set<String> EXOME_REGION_FLAGS = Set.of(
            "outside_broad_capture_region",
            "outside_ukb_capture_region",
            "outside_broad_calling_region",
            "outside_ukb_calling_region");
    private static final Pattern SELECTION_ID =
            Pattern.compile("^cspec-selection-sha256-[0-9a-f]{64}$");
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter canonicalizer;
    public GnomadPopulationCriterionEvaluator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.canonicalizer = new RuntimeEvidenceV2Adapter(mapper);
    }
    /** Evaluates BA1 then BS1 from an already-loaded executable profile. */
    public List<ObjectNode> evaluate(JsonNode evidenceContext,
                                     ObjectNode atomicEvidence,
                                     GnomadPopulationInputProfile profile,
                                     ObjectNode successorRegistry,
                                     String cspecSelectionId) {
        Objects.requireNonNull(evidenceContext, "evidenceContext");
        Objects.requireNonNull(atomicEvidence, "atomicEvidence");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(successorRegistry, "successorRegistry");
        if (cspecSelectionId == null || !SELECTION_ID.matcher(cspecSelectionId).matches()) {
            throw new IllegalArgumentException("cspecSelectionId must be an opaque canonical selection ID");
        }
        Bindings bindings = bindings(profile, successorRegistry);
        Input input = input(bindings, evidenceContext, atomicEvidence);
        return List.of(
                evaluateBa1(bindings, input, evidenceContext, atomicEvidence, cspecSelectionId),
                evaluateBs1(bindings, input, evidenceContext, atomicEvidence, cspecSelectionId));
    }

    /** A selected, reviewed disease policy supplies knowledge; population parsing and arithmetic are shared. */
    ObjectNode evaluatePopulationPolicy(JsonNode context, ObjectNode evidence, JsonNode policy,
                                        String criterionId, String selectionId) {
        if (!Set.of(BA1, BS1).contains(criterionId) || selectionId == null
                || !SELECTION_ID.matcher(selectionId).matches()) throw new IllegalArgumentException("Population selection required");
        GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadExecutableV2();
        Bindings original = bindings(profile, profile.getSuccessorRegistryDocument());
        String issue = PopulationFrequencyPolicy.issue(policy, criterionId);
        if (issue == null && !profile.getProfileSha256().equals(policy.path("inputProfileSha256").asText()))
            issue = "POPULATION_FREQUENCY_INPUT_PROFILE_MISMATCH";
        Set<String> ancestries = textSet(policy.at("/engineeringConditions/applicableAncestries"));
        if (issue == null && !original.ancestries().containsAll(ancestries))
            issue = "POPULATION_FREQUENCY_ANCESTRY_POLICY_INVALID";
        Criterion ba1 = populationCriterion(original.ba1(), policy);
        Criterion bs1 = populationCriterion(original.bs1(), policy);
        Bindings selected = new Bindings(original.profile(), original.profileHash(), original.profileId(), original.profileVersion(),
                original.registryId(), original.registryHash(), original.resource(), original.gates(), ancestries,
                ba1, bs1, issue == null ? original.issue() : issue, policy);
        Input input = input(selected, context, evidence);
        if (input.blockedState() == null) {
            String designation = BA1.equals(criterionId) ? "BA1" : "BS1";
            BigInteger minimum = new BigInteger(policy.at("/engineeringConditions/minimumSelectedAncestryAn/" + designation).asText());
            input.parameters().add(parameter("frequency.policyMinimumSelectedAncestryAn", minimum.toString(), "alleles"));
            input.parameters().add(parameter("frequency.minimumAnBasis", policy.at("/engineeringConditions/minimumAnBasis").asText(), null));
            if (input.selectedAn().compareTo(minimum) < 0)
                input = blocked("POPULATION_FREQUENCY_COHORT_SIZE_REVIEW_REQUIRED", input.steps(), input.parameters(), evidence);
        }
        return BA1.equals(criterionId) ? evaluateBa1(selected, input, context, evidence, selectionId)
                : evaluateBs1(selected, input, context, evidence, selectionId);
    }

    private Criterion populationCriterion(Criterion original, JsonNode policy) {
        ObjectNode rule = ((ObjectNode) original.rule()).deepCopy();
        JsonNode threshold = policy.path("officialConditions").path(original.designation());
        rule.put("comparisonOperator", threshold.path("operator").asText());
        Set<String> authorities = new LinkedHashSet<>(original.authorities());
        for (JsonNode source : policy.path("sources")) authorities.add(source.path("authoritySourceId").asText());
        if (BA1.equals(original.id())) {
            rule.putObject("threshold").put("rawValue", threshold.path("decimal").asText())
                    .put("normalizedValue", threshold.path("decimal").asText()).put("unit", "fraction");
            rule.putObject("minimumSelectedAncestryAlleleNumber").put("comparisonOperator", GTE)
                    .put("rawValue", policy.at("/engineeringConditions/minimumSelectedAncestryAn/BA1").asText());
        } else {
            ObjectNode snapshot = rule.putObject("thresholdSnapshot").put("snapshotId", policy.path("policyId").asText())
                    .put("version", policy.path("policyVersion").asText());
            ObjectNode record = snapshot.putArray("records").addObject().put("thresholdId", policy.path("policyId").asText() + ":BS1")
                    .put("version", policy.path("policyVersion").asText()).put("geneId", policy.at("/scope/hgncId").asText())
                    .put("mondoId", policy.at("/scope/mondoId").asText()).put("moi", policy.at("/scope/moi").asText())
                    .put("metricId", policy.at("/officialConditions/metric").asText()).put("comparator", threshold.path("operator").asText())
                    .put("thresholdRaw", threshold.path("decimal").asText());
            authorities.forEach(record.putArray("authoritySourceIds")::add);
            snapshot.put("sha256", canonicalizer.canonicalSha256(snapshot));
        }
        return new Criterion(original.id(), original.designation(), original.strength(), rule, Set.copyOf(authorities));
    }

    /** Same BA1 policy, with an explicit allele scope. Not a fabricated evidence-context-v2 or CSpec selection. */
    ObjectNode evaluateBa1Allele(String alleleId, ObjectNode carrier, GnomadPopulationInputProfile profile) {
        canonicalizer.validatePopulationCarrier(carrier, carrier.path("queryAllele"));
        if (!alleleId.matches("allele-sha256-[0-9a-f]{64}")) throw new IllegalArgumentException("ALLELE_ID_REQUIRED");
        ObjectNode evidence = carrier.deepCopy();
        evidence.put("contextId", alleleId).put("canonicalizationProfile", C14N);
        evidence.put("evidenceId", "population-evidence-sha256-" + canonicalizer.canonicalSha256(carrier));
        evidence.putObject("provenance").put("producerId", RuntimeEvidenceV2Adapter.PRODUCER_ID);
        ObjectNode scope = mapper.createObjectNode().put("contextId", alleleId).put("canonicalizationProfile", C14N);
        scope.putObject("variant").set("evidenceQueryAllele", carrier.path("queryAllele"));
        Bindings bindings = bindings(profile, profile.getSuccessorRegistryDocument());
        State state = ba1State(bindings, input(bindings, scope, evidence), scope, evidence);
        return output(bindings, bindings.ba1(), evidence, null, state, true);
    }

    /** Catalog-selected BA1 uses the existing comparison engine; legacy profile behavior stays immutable. */
    ObjectNode evaluateCatalogBa1(String alleleId, ObjectNode carrier, GnomadPopulationInputProfile profile,
                                  ObjectNode selection) {
        return evaluateCatalogBa1(alleleId, carrier, profile, selection, null, "");
    }

    ObjectNode evaluateCatalogBa1(String alleleId, ObjectNode carrier, GnomadPopulationInputProfile profile,
                                  JsonNode selection, JsonNode subtypeSupplement, String jointRawSha256) {
        return evaluateCatalogBa1(alleleId, carrier, profile, selection, subtypeSupplement, jointRawSha256, null);
    }

    /** Only the native query supply can issue count material. Saved/public JSON never grants authority. */
    ObjectNode evaluateCatalogBa1(String alleleId, ObjectNode carrier, GnomadPopulationInputProfile profile,
                                  JsonNode selection, JsonNode subtypeSupplement, String jointRawSha256,
                                  Ba1NativeIndividualCountSupply.Material countMaterial) {
        canonicalizer.validatePopulationCarrier(carrier, carrier.path("queryAllele"));
        if (!alleleId.matches("allele-sha256-[0-9a-f]{64}")) throw new IllegalArgumentException("ALLELE_ID_REQUIRED");
        ObjectNode evidence = carrier.deepCopy();
        evidence.put("contextId", alleleId).put("canonicalizationProfile", C14N);
        evidence.put("evidenceId", "population-evidence-sha256-" + canonicalizer.canonicalSha256(carrier));
        evidence.putObject("provenance").put("producerId", RuntimeEvidenceV2Adapter.PRODUCER_ID);
        ObjectNode context = mapper.createObjectNode().put("contextId", alleleId).put("canonicalizationProfile", C14N);
        context.putObject("variant").set("evidenceQueryAllele", carrier.path("queryAllele"));
        Bindings original = bindings(profile, profile.getSuccessorRegistryDocument());
        ObjectNode selectedProfile = original.profile().deepCopy();
        selectedProfile.set("ba1CatalogPolicy", selection.path("policy"));
        boolean conditional = "CONDITIONAL_SELECTED".equals(selection.path("status").asText());
        if (conditional) selectedProfile.put("ba1ConditionalConsumerRequested", true);
        if (subtypeSupplement != null) selectedProfile.set("ba1SubtypeFilterSupplement", subtypeSupplement.deepCopy());
        selectedProfile.put("ba1JointRawSha256", jointRawSha256);
        JsonNode rule = selection.at("/policy/frequencyRule");
        Criterion ba1 = new Criterion(original.ba1().id(), "BA1", "STANDALONE", rule,
                original.ba1().authorities());
        Bindings bindings = new Bindings(selectedProfile, original.profileHash(), original.profileId(), original.profileVersion(),
                original.registryId(), original.registryHash(), original.resource(), original.gates(),
                textSet(selection.at("/policy/ancestries")), ba1, original.bs1(), original.issue(), null);
        List<ObjectNode> parameters = baseParameters(bindings, evidence);
        parameters.add(parameter("ba1.catalogSha256", selection.path("catalogSha256").asText(), "sha256"));
        parameters.add(parameter("ba1.selectionId", selection.path("selectionId").asText(), null));
        parameters.add(parameter("ba1.scope", selection.path("scope").asText(), null));
        if (subtypeSupplement != null) parameters.add(parameter("ba1.nativeSubtypeFilterSupplementSha256",
                canonicalizer.canonicalSha256(subtypeSupplement), "sha256"));
        State state;
        ObjectNode conditionDetails = null;
        if (conditional) {
            String countBindingIssue = countMaterial == null ? null
                    : countMaterial.issue(mapper, profile, alleleId, carrier, jointRawSha256);
            var evaluated = countBindingIssue == null
                    ? conditionalBa1State(bindings, context, evidence, selection, parameters, countMaterial)
                    : conditionalBlocked(countBindingIssue, new ArrayList<>(), parameters, evidence,
                            mapper.createObjectNode().put("schemaVersion", "ba1-condition-evaluation-v1")
                                    .put("contractVersion", Ba1ConditionalFrequencyRule.VERSION)
                                    .put("truth", "UNKNOWN").put("frequencyTruth", "UNKNOWN").put("countTruth", "UNKNOWN"));
            state = evaluated.state(); conditionDetails = evaluated.details();
        } else if (!"SELECTED".equals(selection.path("status").asText())) {
            state = notEvaluated("NOT_APPLICABLE".equals(selection.path("status").asText()) ? "NOT_APPLICABLE" : "UNKNOWN",
                    selection.path("reasonCode").asText("BA1_SPECIFICATION_UNAVAILABLE"), new ArrayList<>(), parameters, evidence);
        } else {
            ExceptionResult exception = ba1Exception(rule, context);
            if (!"NOT_HIT".equals(exception.outcome())) {
                parameters.add(parameter("ba1.exceptionOutcome", exception.outcome(), null));
                state = notEvaluated("HIT".equals(exception.outcome()) ? "NOT_APPLICABLE" : "UNKNOWN",
                        exception.reason(), new ArrayList<>(), parameters, evidence);
            } else {
                Input input = input(bindings, context, evidence);
                for (ObjectNode parameter : parameters)
                    if (parameter.path("name").asText().startsWith("ba1.")) input.parameters().add(parameter);
                state = ba1State(bindings, input, context, evidence);
            }
        }
        ObjectNode result = output(bindings, ba1, evidence, null, state, true);
        result.put("scope", selection.path("scope").asText("BA1_APPLICABILITY_UNRESOLVED"));
        ((ObjectNode) result.path("ruleBinding")).put("ba1SelectionId", selection.path("selectionId").asText());
        if (conditionDetails != null) {
            result.set("ruleConditionEvaluation", conditionDetails);
            ((ObjectNode) result.path("ruleBinding")).put("conditionRuntimeContract", Ba1ConditionalFrequencyRule.VERSION);
        }
        ObjectNode identity = result.deepCopy();
        identity.remove(List.of("evaluationId", "conflict", "review"));
        result.put("evaluationId", "ba1-allele-evaluation-sha256-" + canonicalizer.canonicalSha256(identity));
        return result;
    }

    private record ConditionalState(State state, ObjectNode details) { }

    private boolean conditionalCatalog(Bindings bindings) {
        return bindings.profile().path("ba1ConditionalConsumerRequested").asBoolean(false);
    }

    private ConditionalState conditionalBa1State(Bindings bindings, JsonNode context, ObjectNode evidence,
                                                 JsonNode selection, List<ObjectNode> catalogParameters,
                                                 Ba1NativeIndividualCountSupply.Material countMaterial) {
        JsonNode policy = selection.path("policy"), clause = policy.path("clause");
        ObjectNode details = mapper.createObjectNode().put("schemaVersion", "ba1-condition-evaluation-v1")
                .put("contractVersion", Ba1ConditionalFrequencyRule.VERSION)
                .put("truth", "UNKNOWN").put("frequencyTruth", "UNKNOWN").put("countTruth", "UNKNOWN");
        String issue = Ba1ConditionalFrequencyRule.VERSION.equals(policy.path("conditionRuntimeContract").asText())
                ? Ba1ConditionalFrequencyRule.issue(clause) : "BA1_CONDITIONAL_RUNTIME_CONTRACT_UNSUPPORTED";
        if (issue == null && localExomes(bindings)) issue = "BA1_CONDITIONAL_DATASET_UNSUPPORTED";
        if (issue != null) return conditionalBlocked(issue, new ArrayList<>(), catalogParameters, evidence, details);
        // Catalog selection, including complete publication constraints, is upstream. This
        // route never turns a blocked/unsupported publication into a selected policy.
        ExceptionResult exception = ba1Exception(bindings.ba1().rule(), context);
        if (!"NOT_HIT".equals(exception.outcome())) {
            details.put("reasonCode", exception.reason());
            return new ConditionalState(notEvaluated("HIT".equals(exception.outcome()) ? "NOT_APPLICABLE" : "UNKNOWN",
                    exception.reason(), new ArrayList<>(), catalogParameters, evidence), details);
        }
        Input common = input(bindings, context, evidence, true);
        for (ObjectNode parameter : catalogParameters)
            if (parameter.path("name").asText().startsWith("ba1.")) common.parameters().add(parameter);
        common.parameters().add(parameter("ba1.conditionRuntimeContract", Ba1ConditionalFrequencyRule.VERSION, null));
        if (common.reason() != null) return conditionalBlocked(common.reason(), common.steps(), common.parameters(), evidence, details);
        Input frequency = frequencyInput(bindings, context, evidence, copy(common.steps()), copy(common.parameters()));
        // The OR separates missing branch inputs, not contradictory source data.
        // A positive count must not hide a detected AC/AN/AF or FAF consistency error.
        if (frequency.reason() != null && !conditionalFrequencyUnavailable(bindings, evidence, frequency.reason()))
            return conditionalBlocked(frequency.reason(), frequency.steps(), frequency.parameters(), evidence, details);
        Ba1ConditionalFrequencyRule.FrequencyFact fact = null;
        if (frequency.reason() == null) {
            String calibration = bindings.profileId() + "@" + bindings.profileVersion();
            Reading group = reading(evidence, bindings.gates().path("frequencyAncestryMetricId").asText(), calibration);
            String ancestry = group.reason() == null ? textMeasurement(group.node(), "genetic-ancestry-group") : null;
            fact = new Ba1ConditionalFrequencyRule.FrequencyFact(frequency.frequency(), frequency.selectedAn(), ancestry);
        }
        // Count facts come only from the source-bound supply. Unknown classes and
        // incomplete scope remain unknown; neither AC nor a saved status mints people.
        var counts = countMaterial == null ? null : countMaterial.counts();
        var completeness = countMaterial == null
                ? Ba1ConditionalFrequencyRule.CountScopeCompleteness.UNVERIFIED_OR_PARTIAL : countMaterial.completeness();
        var upperBound = countMaterial == null ? null : countMaterial.upperBound();
        var decision = Ba1ConditionalFrequencyRule.evaluate(clause, fact, counts, upperBound, completeness);
        String frequencyReason = frequency.reason() == null ? decision.frequencyReasonCode() : frequency.reason();
        details.set("frequencyRequirement", clause.at("/expression/frequency").deepCopy());
        details.set("individualCountRequirement", clause.at("/expression/individualCount").deepCopy());
        if (countMaterial == null) {
            var qualification = details.putObject("countSourceQualification").put("status", "UNRESOLVED");
            qualification.putArray("required").add("UNIQUE_QUALIFYING_INDIVIDUALS")
                    .add("SAME_TARGET_ALT_RELEASE_AND_CLINICAL_COUNT_QC_SCOPE");
            qualification.putArray("available").add("PROFILE_HEADER_BOUND_HOMOZYGOUS_CALL_STATISTIC");
            qualification.putArray("missing").add("NATIVE_QUERY_BOUND_COUNT_MATERIAL");
            qualification.putArray("notInferred").add("HEMIZYGOTE_INDIVIDUALS")
                    .add("COMPLETE_INDIVIDUAL_COUNT_COVERAGE").add("WHOLE_POPULATION_PERSON_COUNT_UPPER_BOUND");
        } else details.set("countSourceQualification", countMaterial.publicDetails());
        String countReason = counts == null && upperBound == null ? countMaterial == null ? "BA1_COUNT_SOURCE_SCOPE_UNQUALIFIED"
                : countMaterial.reasonCode() : decision.countReasonCode();
        details.put("truth", decision.truth().name()).put("frequencyTruth", decision.frequencyTruth().name())
                .put("countTruth", decision.countTruth().name()).put("reasonCode", decision.reasonCode())
                .put("frequencyReasonCode", frequencyReason).put("countReasonCode", countReason)
                .put("countScopeCompleteness", completeness.name());
        String calibration = bindings.profileId() + "@" + bindings.profileVersion();
        Reading homo = reading(evidence, "gnomad.joint.nhomalt", calibration);
        var readings = details.putArray("frequencyReadings");
        readings.add(conditionalReading(evidence, "gnomad.joint.faf95max", "fraction", calibration));
        readings.add(conditionalReading(evidence, "gnomad.joint.faf95max.ancestry", "genetic-ancestry-group", calibration));
        Reading ancestryReading = reading(evidence, "gnomad.joint.faf95max.ancestry", calibration);
        String ancestry = ancestryReading.reason() == null ? textMeasurement(ancestryReading.node(), "genetic-ancestry-group") : null;
        if (ancestry != null && bindings.ancestries().contains(ancestry))
            for (String suffix : List.of("faf95", "af", "ac", "an"))
                readings.add(conditionalReading(evidence, "gnomad.joint.ancestry." + ancestry + "." + suffix,
                        Set.of("ac", "an").contains(suffix) ? "alleles" : "fraction", calibration));
        var observed = details.putObject("observedHomozygoteStatistic").put("metricId", "gnomad.joint.nhomalt")
                .put("unit", "individuals").put("qualificationStatus", counts != null && counts.homozygotes() != null
                        ? "QUALIFIED_LOWER_BOUND" : "UNRESOLVED").put("reasonCode", countReason);
        if (homo.reason() == null && integerMeasurement(homo.node(), "individuals") != null)
            observed.put("rawValue", homo.node().path("rawValue").asText());
        var parameters = frequency.reason() == null ? copy(frequency.parameters()) : copy(common.parameters());
        var steps = frequency.reason() == null ? copy(frequency.steps()) : copy(common.steps());
        parameters.add(parameter("ba1.condition.frequencyTruth", decision.frequencyTruth().name(), null));
        parameters.add(parameter("ba1.condition.frequencyReasonCode", frequencyReason, null));
        parameters.add(parameter("ba1.condition.countTruth", decision.countTruth().name(), null));
        parameters.add(parameter("ba1.condition.countReasonCode", countReason, null));
        parameters.add(parameter("ba1.condition.countScopeCompleteness", completeness.name(), null));
        if (countMaterial != null) parameters.add(parameter("ba1.condition.nativeCountMaterialId",
                countMaterial.publicDetails().path("materialId").asText(), null));
        steps.add(step("BA1_CONDITIONAL_FREQUENCY", decision.frequencyTruth().name(), evidence));
        steps.add(step("BA1_CONDITIONAL_INDIVIDUAL_COUNT", decision.countTruth().name(), evidence));
        steps.add(step("BA1_CONDITIONAL_ANY", decision.truth().name(), evidence));
        State state = decision.truth() == Ba1ConditionalFrequencyRule.Truth.UNKNOWN
                ? notEvaluated("UNKNOWN", decision.reasonCode(), steps, parameters, evidence)
                : completed(decision.truth() == Ba1ConditionalFrequencyRule.Truth.TRUE, steps, parameters);
        return new ConditionalState(state, details);
    }

    private boolean conditionalFrequencyUnavailable(Bindings bindings, ObjectNode evidence, String reason) {
        if (Set.of("REQUIRED_MEASUREMENT_MISSING", "FAF95_ANCESTRY_UNSUPPORTED").contains(reason)) return true;
        if (!"POPULATION_FREQUENCY_FAF_ANCESTRY_MISMATCH".equals(reason)) return false;
        String calibration = bindings.profileId() + "@" + bindings.profileVersion();
        Reading group = reading(evidence, bindings.gates().path("frequencyAncestryMetricId").asText(), calibration);
        String ancestry = group.reason() == null ? textMeasurement(group.node(), "genetic-ancestry-group") : null;
        return ancestry != null && "REQUIRED_MEASUREMENT_MISSING".equals(
                reading(evidence, "gnomad.joint.ancestry." + ancestry + ".faf95", calibration).reason());
    }

    private ObjectNode conditionalReading(ObjectNode evidence, String metric, String unit, String calibration) {
        Reading reading = reading(evidence, metric, calibration);
        var result = mapper.createObjectNode().put("metricId", metric).put("expectedUnit", unit);
        if (reading.reason() != null) return result.put("status", "UNAVAILABLE").put("reasonCode", reading.reason());
        boolean valid = "genetic-ancestry-group".equals(unit) ? textMeasurement(reading.node(), unit) != null
                : "alleles".equals(unit) ? integerMeasurement(reading.node(), unit) != null
                : decimalMeasurement(reading.node(), unit) != null;
        result.put("status", valid ? "AVAILABLE" : "INVALID");
        if (valid) result.put("rawValue", reading.node().path("rawValue").asText());
        else result.put("reasonCode", "BA1_CONDITIONAL_READING_VALUE_INVALID");
        return result;
    }

    private ConditionalState conditionalBlocked(String reason, List<ObjectNode> steps, List<ObjectNode> parameters,
                                                 ObjectNode evidence, ObjectNode details) {
        details.put("reasonCode", reason).put("frequencyReasonCode", reason).put("countReasonCode", reason);
        return new ConditionalState(notEvaluated("UNKNOWN", reason, steps, parameters, evidence), details);
    }

    /** Missing arm-specific data is not a quality waiver. Every non-absence parser
     * failure and every missing shared/core required metric remains a blocker.
     * The frequency branch is revalidated below, with its full existing QC.
     */
    private String conditionalPartialReadinessIssue(Bindings bindings, ObjectNode evidence, String calibration, String status) {
        if (!"NOT_READY".equals(status)) return "POPULATION_INPUT_NOT_READY";
        Reading reading = reading(evidence, "gnomad.recordInputReadinessReasonCodes", calibration);
        String codes = reading.reason() == null ? textMeasurement(reading.node(), "reason-code-list") : null;
        if (codes == null || codes.isBlank()) return "POPULATION_INPUT_NOT_READY";
        Set<String> allowed = Set.of("REQUIRED_INFO_FIELD_MISSING", "GROUPMAX_ANCESTRY_FIELDS_MISSING",
                "FAF95_ANCESTRY_FIELDS_MISSING", "FAF95_ANCESTRY_FIELD_MISSING");
        for (String code : codes.split(",")) if (!allowed.contains(code)) return "POPULATION_INPUT_NOT_READY";
        boolean missing = false;
        Set<String> armMetrics = Set.of("gnomad.joint.nhomalt", "gnomad.joint.groupmax.nhomalt",
                "gnomad.joint.faf95", "gnomad.joint.faf95max", "gnomad.joint.faf95max.ancestry");
        for (JsonNode field : bindings.profile().at("/fieldContract/fields")) {
            if (!field.path("required").asBoolean(false)) continue;
            String metric = field.path("metricId").asText(); Reading value = reading(evidence, metric, calibration);
            if (value.reason() == null) continue;
            if (!"REQUIRED_MEASUREMENT_MISSING".equals(value.reason()) || !armMetrics.contains(metric))
                return "POPULATION_INPUT_NOT_READY";
            missing = true;
        }
        // The parser's ancestry reasons are aggregate labels. Resolve both
        // selected groups through the pinned templates; only absent nhomalt or
        // the frequency arm's FAF can be treated as partial branch input.
        Set<String> groups = new LinkedHashSet<>();
        for (String metric : List.of("gnomad.joint.groupmax.ancestry", "gnomad.joint.faf95max.ancestry")) {
            Reading group = reading(evidence, metric, calibration);
            if ("REQUIRED_MEASUREMENT_MISSING".equals(group.reason()) && metric.endsWith("faf95max.ancestry")) continue;
            if (group.reason() != null) return "POPULATION_INPUT_NOT_READY";
            String ancestry = textMeasurement(group.node(), "genetic-ancestry-group");
            if (ancestry == null || !textSet(bindings.profile().at("/fieldContract/ancestryGroups")).contains(ancestry))
                return "POPULATION_INPUT_NOT_READY";
            groups.add(ancestry);
        }
        for (String ancestry : groups) {
            for (String suffix : List.of("ac", "an", "af", "nhomalt")) {
                Reading value = reading(evidence, "gnomad.joint.ancestry." + ancestry + "." + suffix, calibration);
                if (value.reason() == null) continue;
                if (!"nhomalt".equals(suffix) || !"REQUIRED_MEASUREMENT_MISSING".equals(value.reason()))
                    return "POPULATION_INPUT_NOT_READY";
                missing = true;
            }
            if (textSet(bindings.profile().at("/fieldContract/faf95AncestryGroups")).contains(ancestry)) {
                Reading value = reading(evidence, "gnomad.joint.ancestry." + ancestry + ".faf95", calibration);
                if ("REQUIRED_MEASUREMENT_MISSING".equals(value.reason())) missing = true;
                else if (value.reason() != null) return "POPULATION_INPUT_NOT_READY";
            }
        }
        return missing ? null : "POPULATION_INPUT_NOT_READY";
    }

    private String conditionalSharedQualityIssue(Bindings bindings, ObjectNode evidence, String calibration,
                                                  List<ObjectNode> parameters) {
        if (localExomes(bindings)) return "BA1_CONDITIONAL_DATASET_UNSUPPORTED";
        for (String prefix : List.of("gnomad.joint.", "gnomad.joint.groupmax.")) {
            Reading homo = reading(evidence, prefix + "nhomalt", calibration);
            if ("REQUIRED_MEASUREMENT_MISSING".equals(homo.reason())) continue;
            if (homo.reason() != null) return homo.reason();
            BigInteger hom = integerMeasurement(homo.node(), "individuals");
            Reading ac = reading(evidence, prefix + "ac", calibration);
            if (ac.reason() != null) return ac.reason();
            BigInteger alleles = integerMeasurement(ac.node(), "alleles");
            if (hom == null || hom.signum() < 0 || alleles == null || alleles.signum() < 0)
                return "BA1_HOMOZYGOUS_STATISTIC_INVALID";
            // Hail counts only diploid homozygous calls; each contributes two ALT
            // alleles. This is source-statistic QC, not unique-person qualification.
            if (hom.multiply(BigInteger.TWO).compareTo(alleles) > 0)
                return "BA1_HOMOZYGOUS_COUNT_ALLELE_COUNT_CONFLICT";
        }
        Qc qc = discordanceQc(bindings, evidence, calibration);
        parameters.add(parameter("frequency.discordanceQc", qc.status(), "qc-status"));
        if (!"NOT_DISCORDANT".equals(qc.status())) return "DISCORDANT".equals(qc.status())
                ? "POPULATION_FREQUENCY_DISCORDANT_REVIEW_REQUIRED" : "POPULATION_FREQUENCY_DISCORDANCE_UNKNOWN";
        for (String subtype : List.of("exomes", "genomes")) {
            Reading presence = reading(evidence, "gnomad.joint." + subtype + ".filtersPresence", calibration);
            String flag = presence.reason() == null ? textMeasurement(presence.node(), "vcf-filter-list") : null;
            if ("MISSING".equals(flag) && Ba1SubtypeFilterSupplement.verifiedPass(mapper,
                    bindings.profile().path("ba1SubtypeFilterSupplement"), subtype, evidence.path("queryAllele"),
                    bindings.profile().path("ba1JointRawSha256").asText())) {
                parameters.add(parameter("ba1.nativeFilter." + subtype, "VERIFIED_NATIVE_EXACT_ALLELE_PASS", null));
                continue;
            }
            if (flag == null || "MISSING".equals(flag) || flag.isBlank())
                return "BA1_" + subtype.toUpperCase(java.util.Locale.ROOT) + "_FILTER_INPUT_MISSING";
            if (!".".equals(flag) && !"PASS".equals(flag)) return "POPULATION_FREQUENCY_SUBTYPE_FILTER_REVIEW_REQUIRED";
        }
        return null;
    }

    /** Evaluates the isolated E3-P1B PM2_Supporting slice from explicit atomic evidence. */
    public ObjectNode evaluatePm2Supporting(JsonNode evidenceContext,
                                            ObjectNode jointEvidence,
                                            ObjectNode exomeAllSitesEvidence,
                                            ObjectNode genomeAllSitesEvidence,
                                            GnomadPopulationInputProfile profile,
                                            ObjectNode successorRegistry,
                                            String cspecSelectionId) {
        Objects.requireNonNull(evidenceContext, "evidenceContext");
        Objects.requireNonNull(jointEvidence, "jointEvidence");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(successorRegistry, "successorRegistry");
        if (cspecSelectionId == null || !SELECTION_ID.matcher(cspecSelectionId).matches()) {
            throw new IllegalArgumentException("cspecSelectionId must be an opaque canonical selection ID");
        }

        Pm2Bindings bindings = pm2Bindings(profile, successorRegistry);
        List<ObjectNode> evidence = pm2EvidenceSet(
                jointEvidence, exomeAllSitesEvidence, genomeAllSitesEvidence);
        List<ObjectNode> steps = new ArrayList<>();
        List<ObjectNode> parameters = new ArrayList<>(List.of(
                parameter("profile.id", bindings.profileId(), null),
                parameter("profile.version", bindings.profileVersion(), null),
                parameter("profile.sha256", bindings.profileHash(), "sha256")));
        State state;
        if (bindings.issue() != null) {
            state = notEvaluated("UNKNOWN", bindings.issue(), steps, parameters, jointEvidence);
        } else {
            PolicyResult policy = pm2Policy(bindings.pm2().rule(), evidenceContext, bindings);
            steps.add(step("PM2_EXACT_POLICY_SELECTED", policy.ready() ? "TRUE" : "FALSE",
                    jointEvidence));
            if (!policy.ready()) {
                state = notEvaluated("UNKNOWN", policy.reason(), steps, parameters, jointEvidence);
            } else {
                addPolicyParameters(parameters, policy, bindings.pm2().rule());
                state = evaluatePm2Policy(bindings, policy.record(), evidenceContext,
                        jointEvidence, exomeAllSitesEvidence, genomeAllSitesEvidence,
                        steps, parameters);
            }
        }
        return pm2Output(bindings, evidence, cspecSelectionId, state);
    }

    /** Local PM2 consumes verified neutral EXOMES facts; no JOINT relabeling or absence runner. */
    public ObjectNode evaluatePm2LocalExomes(JsonNode context, JsonNode observation,
                                           Pm2LocalExomesProfile profile, String sourceSnapshot) {
        return evaluatePm2LocalExomes(context, observation, profile, sourceSnapshot, null);
    }

    public ObjectNode evaluatePm2LocalExomes(JsonNode context, JsonNode observation,
                                           Pm2LocalExomesProfile profile, String sourceSnapshot, JsonNode allSites) {
        PopulationObservation.verify(mapper, observation, context, sourceSnapshot, profile.factProfile());
        var evidence = canonicalizer.adaptVersioned(context, (ObjectNode) observation.path("carrier"));
        var selection = profile.select(context);
        var historical = GnomadPopulationInputProfile.loadBusinessPm2Supporting();
        var registry = historical.getDocument().path("registryBinding");
        var policy = selection.path("policy");
        var authorities = new LinkedHashSet<>(textSet(find(historical.getSuccessorRegistryDocument().path("criteria"),
                "criterionId", PM2).at("/currentGeneral/authoritySourceIds")));
        if (policy.at("/authority/authoritySourceId").isTextual()) authorities.add(policy.at("/authority/authoritySourceId").asText());
        var bindings = new Pm2Bindings(profile.document(), profile.sha256(), "PM2_LOCAL_EXOMES_V1", Pm2LocalExomesProfile.PROFILE_VERSION,
                registry.path("registryId").asText(), registry.path("registrySha256").asText(),
                profile.document().path("resourceContract"), mapper.nullNode(), mapper.nullNode(),
                new Criterion(PM2, "PM2_SUPPORTING", "SUPPORTING", policy, authorities), null);
        var steps = new ArrayList<ObjectNode>();
        var parameters = new ArrayList<ObjectNode>();
        parameters.add(parameter("profile.sha256", profile.sha256(), "sha256"));
        parameters.add(parameter("population.dataset", "EXOMES", null));
        parameters.add(parameter("population.factProfileSha256", profile.factProfile().getProfileSha256(), "sha256"));
        parameters.add(parameter("population.observationId", observation.path("observationId").asText(), null));
        parameters.add(parameter("policy.adoptionStatus", selection.path("status").asText(), null));
        parameters.add(parameter("policy.syntheticOnly", profile.document().path("syntheticOnly").asText(), null));
        if (selection.has("adoptionScope")) parameters.add(parameter("policy.adoptionScope", selection.path("adoptionScope").asText(), null));
        if (profile.document().has("projectScope")) {
            parameters.add(parameter("policy.interpretation", "LOCAL_DATASET_CRITERION_ONLY", null));
            parameters.add(parameter("policy.wholeGnomadConclusion", "false", null));
            parameters.add(parameter("policy.clinicalAdoption", "NOT_ESTABLISHED", null));
        }
        String issue = readyAxesIssue(axesValue(evidence), true);
        boolean absence = "NO_RECORD".equals(evidence.at("/queryOutcome/status").asText());
        if (issue == null && absence) {
            State state = evaluateLocalAbsence(context, profile, selection, evidence, allSites, steps, parameters);
            return pm2Output(bindings, List.of(evidence), selection.path("selectionId").asText(), state,
                    Pm2LocalExomesProfile.PROFILE_VERSION + "-local-exomes-pm2");
        }
        if (issue == null) issue = localPm2QualityIssue(context, evidence, observation, profile.calibration());
        if (issue == null && !"APPLICABLE".equals(selection.path("status").asText())) issue = selection.path("reasonCode").asText();
        if (issue == null && "GENERAL_ABSENCE_ONLY".equals(policy.path("route").asText()))
            issue = "PM2_OBSERVED_RARITY_THRESHOLD_REQUIRED";
        if (issue == null) {
            var releases = policy.path("resourceReleaseBindings");
            var expected = profile.document().path("resourceContract");
            if (!releases.isArray() || releases.size() != 1
                    || !expected.path("resourceId").equals(releases.path(0).path("resourceId"))
                    || !expected.path("resourceVersion").equals(releases.path(0).path("resourceVersion"))
                    || !expected.path("identityFingerprintSha256").equals(releases.path(0).path("identityFingerprintSha256")))
                issue = "PM2_POLICY_RESOURCE_BINDING_INVALID";
        }
        State state = issue == null
                ? evaluatePm2Hit("gnomad.exomes.", profile.calibration(), "qualityRequirements", policy, evidence, steps, parameters)
                : notEvaluated("UNKNOWN", issue, steps, parameters, evidence);
        var result = pm2Output(bindings, List.of(evidence), selection.path("selectionId").asText(), state,
                Pm2LocalExomesProfile.PROFILE_VERSION + "-local-exomes-pm2");
        return result;
    }

    private State evaluateLocalAbsence(JsonNode context, Pm2LocalExomesProfile profile, JsonNode selection,
            ObjectNode evidence, JsonNode allSites, List<ObjectNode> steps, List<ObjectNode> parameters) {
        var allele = context.at("/variant/evidenceQueryAllele");
        String issue = null;
        if (!allele.path("reference").asText().matches("[ACGT]") || !allele.path("alternate").asText().matches("[ACGT]"))
            issue = "PM2_INDEL_DETECTION_PROOF_REQUIRED";
        else if (!"GRCh38".equals(allele.path("assembly").asText()) || !allele.path("contig").asText().matches("[1-9]|1[0-9]|2[0-2]"))
            issue = "PM2_LOCAL_VARIANT_SCOPE_UNSUPPORTED";
        else if (allSites != null && "PM2_ALL_SITES_RESOURCE_INVALID".equals(allSites.path("reasonCode").asText()))
            issue = "PM2_ALL_SITES_RESOURCE_INVALID";
        if (issue != null) return notEvaluated("UNKNOWN", issue, steps, parameters, evidence);
        if (allSites == null || !profile.document().path("allSitesAvailable").asBoolean(false))
            return notEvaluated("UNKNOWN", "PM2_LOCAL_ALL_SITES_DETECTION_PROOF_MISSING", steps, parameters, evidence);
        var expected = profile.document().path("allSitesBinding");
        for (var fields = expected.fieldNames(); fields.hasNext();) {
            String field = fields.next();
            if (!expected.path(field).equals(allSites.path(field))) issue = "PM2_ALL_SITES_RESOURCE_BINDING_MISMATCH";
        }
        if (!allele.equals(allSites.path("queryAllele"))) issue = "PM2_ALL_SITES_QUERY_MISMATCH";
        parameters.add(parameter("allSites.manifestSha256", allSites.path("manifestSha256").asText(), "sha256"));
        parameters.add(parameter("allSites.dataSha256", allSites.path("dataSha256").asText(), "sha256"));
        parameters.add(parameter("allSites.queryStatus", allSites.path("status").asText(), null));
        if (issue != null) return notEvaluated("UNKNOWN", issue, steps, parameters, evidence);
        if (!"HIT".equals(allSites.path("status").asText()))
            return notEvaluated("UNKNOWN", "NO_RECORD".equals(allSites.path("status").asText())
                    ? "PM2_ALL_SITES_LOCUS_NOT_DETECTED" : "PM2_ALL_SITES_RESOURCE_CHANGED_OR_UNREADABLE", steps, parameters, evidence);
        try {
            var row = GnomadLocalAllSites.parse(allSites.path("rawRecord").asText());
            if (row.contig() != allele.path("contig").asInt() || row.position() != allele.path("position").asInt()
                    || !Long.toString(row.an()).equals(allSites.path("adjAn").asText())) throw new IllegalArgumentException("raw identity");
            for (int i = 0; i < GnomadLocalAllSites.FLAGS.size(); i++) {
                var flag = allSites.path("regionFlags").path(GnomadLocalAllSites.FLAGS.get(i));
                if (!flag.isBoolean() || flag.asBoolean() != row.flags().get(i)) throw new IllegalArgumentException("raw flag");
            }
        } catch (Exception invalid) { return notEvaluated("UNKNOWN", "PM2_ALL_SITES_RAW_MISMATCH", steps, parameters, evidence); }
        parameters.add(parameter("allSites.rawRecord", allSites.path("rawRecord").asText(), null));
        parameters.add(parameter("allSites.adjAn", allSites.path("adjAn").asText(), "alleles"));
        if (!"APPLICABLE".equals(selection.path("status").asText()))
            return notEvaluated("UNKNOWN", selection.path("reasonCode").asText(), steps, parameters, evidence);
        var policy = selection.path("policy");
        if (!policy.path("allowWellCoveredAbsence").asBoolean(false))
            return notEvaluated("UNKNOWN", "PM2_ABSENCE_DETECTION_POLICY_REQUIRED", steps, parameters, evidence);
        var rule = policy.at("/allSitesCallability/exomes");
        var minimum = rule.path("minimumAdjAlleleNumber");
        BigInteger threshold = integer(minimum.path("rawValue")), an = integer(allSites.path("adjAn"));
        String operator = minimum.path("comparisonOperator").asText();
        if (threshold == null || an == null || threshold.signum() <= 0 || !Set.of("GREATER_THAN", "GREATER_THAN_OR_EQUAL").contains(operator)
                || !rule.path("excludedWhenAnyFlagsTrue").isArray() || rule.path("excludedWhenAnyFlagsTrue").isEmpty())
            return notEvaluated("UNKNOWN", "PM2_CALLABILITY_POLICY_INVALID", steps, parameters, evidence);
        parameters.add(parameter("allSites.minimumAn", threshold.toString(), "alleles"));
        parameters.add(parameter("allSites.comparisonOperator", operator, null));
        parameters.add(parameter("allSites.stratum", "ALL_RELEASE_SAMPLES_ADJ", null));
        parameters.add(parameter("allSites.ancestryMatchedConclusion", "false", null));
        if (an.compareTo(threshold) < 0 || "GREATER_THAN".equals(operator) && an.equals(threshold))
            return notEvaluated("UNKNOWN", "PM2_ALL_SITES_AN_INSUFFICIENT", steps, parameters, evidence);
        for (var flag : rule.path("excludedWhenAnyFlagsTrue")) {
            if (!allSites.path("regionFlags").path(flag.asText()).isBoolean())
                return notEvaluated("UNKNOWN", "PM2_CALLABILITY_POLICY_INVALID", steps, parameters, evidence);
            if (allSites.path("regionFlags").path(flag.asText()).asBoolean())
                return notEvaluated("UNKNOWN", "PM2_ALL_SITES_REGION_EXCLUDED", steps, parameters, evidence);
        }
        steps.add(step("LOCAL_EXOMES_EXACT_ALLELE_ABSENT", "TRUE", evidence));
        steps.add(step("LOCAL_EXOMES_SNV_DETECTION_SUFFICIENT", "TRUE", evidence));
        return completed(true, steps, parameters);
    }

    private String localPm2QualityIssue(JsonNode context, ObjectNode evidence, JsonNode observation, String calibration) {
        var allele = context.at("/variant/evidenceQueryAllele");
        if (!"GRCh38".equals(allele.path("assembly").asText())
                || !allele.path("contig").asText().matches("[1-9]|1[0-9]|2[0-2]")
                || !allele.path("reference").asText().matches("[ACGT]{1,50}")
                || !allele.path("alternate").asText().matches("[ACGT]{1,50}")) return "PM2_LOCAL_VARIANT_SCOPE_UNSUPPORTED";
        var raw = Bs1Quality.raw(observation.path("rawRecord").asText());
        if (!"PASS".equals(raw.get("FILTER"))) return "VARIANT_FILTER_NOT_PASS";
        // A VCF Flag is true by presence. PASS does not erase the independent interval coverage flag.
        if (raw.containsKey("fail_interval_qc")) return "PM2_LOCAL_INTERVAL_QC_FAILED";
        Reading ac = reading(evidence, "gnomad.exomes.ac", calibration);
        Reading an = reading(evidence, "gnomad.exomes.an", calibration);
        Reading af = reading(evidence, "gnomad.exomes.af", calibration);
        for (var r : List.of(ac, an, af)) if (r.reason() != null) return r.reason();
        BigInteger count = integerMeasurement(ac.node(), "alleles"), number = integerMeasurement(an.node(), "alleles");
        BigDecimal frequency = decimalMeasurement(af.node(), "fraction");
        if (count == null || number == null || count.signum() <= 0 || number.signum() <= 0 || count.compareTo(number) > 0
                || frequency == null || !Bs1Quality.fraction(frequency)
                || !Bs1Quality.countsAgree(new BigDecimal(count), new BigDecimal(number), frequency))
            return "PM2_OBSERVED_FREQUENCY_INPUT_INCONSISTENT";
        return null;
    }

    private State evaluatePm2Policy(Pm2Bindings bindings,
                                    JsonNode policy,
                                    JsonNode context,
                                    ObjectNode jointEvidence,
                                    ObjectNode exomeEvidence,
                                    ObjectNode genomeEvidence,
                                    List<ObjectNode> steps,
                                    List<ObjectNode> parameters) {
        String bindingIssue = pm2PolicyBindingIssue(policy, bindings);
        if (bindingIssue != null) {
            return notEvaluated("UNKNOWN", bindingIssue, steps, parameters, jointEvidence);
        }
        if (policy.has("scope")) {
            JsonNode allele = context.at("/variant/evidenceQueryAllele");
            boolean small = "NORMALIZED_SMALL_SEQUENCE_VARIANT".equals(policy.at("/scope/variantType").asText());
            if (!"GRCh38".equals(allele.path("assembly").asText())
                    || !allele.path("reference").asText().matches(small ? "[ACGT]{1,51}" : "[ACGT]")
                    || !allele.path("alternate").asText().matches(small ? "[ACGT]{1,51}" : "[ACGT]"))
                return notEvaluated("UNKNOWN", "PM2_NATIVE_SMALL_VARIANT_INPUT_UNSUPPORTED", steps, parameters, jointEvidence);
        }
        String jointIssue = evidenceBindingIssue(
                context, jointEvidence, bindings.jointResource(), bindings);
        if (jointIssue != null) {
            return notEvaluated("UNKNOWN", jointIssue, steps, parameters, jointEvidence);
        }

        AxesValue axes = axesValue(jointEvidence);
        parameters.add(parameter("joint.executionStatus", axes.execution(), null));
        parameters.add(parameter("joint.availabilityStatus", axes.availability(), null));
        parameters.add(parameter("joint.queryStatus", axes.query(), null));
        parameters.add(parameter("joint.applicabilityStatus", axes.applicability(), null));
        String axesIssue = readyAxesIssue(axes, true);
        if (axesIssue != null) {
            return notEvaluated("UNKNOWN", axesIssue, steps, parameters, jointEvidence);
        }

        if ("HIT".equals(axes.query())) {
            return evaluatePm2Hit(bindings, policy, jointEvidence, steps, parameters);
        }
        if (!"NO_RECORD".equals(axes.query()) || axes.recordCount() != 0
                || !jointEvidence.path("measurements").isArray()
                || !jointEvidence.path("measurements").isEmpty()) {
            return notEvaluated("UNKNOWN", "PM2_JOINT_QUERY_NOT_EXACT_NO_RECORD",
                    steps, parameters, jointEvidence);
        }
        steps.add(step("JOINT_EXACT_ALLELE_NO_RECORD_CONFIRMED", "TRUE", jointEvidence));
        JsonNode queryAllele = context.at("/variant/evidenceQueryAllele");
        if (queryAllele.path("reference").asText().length() != 1 || queryAllele.path("alternate").asText().length() != 1)
            return notEvaluated("UNKNOWN", "PM2_INDEL_DETECTION_PROOF_REQUIRED", steps, parameters, jointEvidence);
        if (!policy.path("allowWellCoveredAbsence").isBoolean()
                || !policy.path("allowWellCoveredAbsence").asBoolean()) {
            if ("PM2_ABSENCE_DETECTION_INSUFFICIENT".equals(policy.at("/allSitesCallability/reasonCode").asText()))
                return notEvaluated("UNKNOWN", "PM2_ABSENCE_DETECTION_INSUFFICIENT", steps, parameters, jointEvidence);
            if ("PM2_ABSENCE_DETECTION_POLICY_REQUIRED".equals(policy.at("/allSitesCallability/reasonCode").asText()))
                return notEvaluated("UNKNOWN", "PM2_ABSENCE_DETECTION_POLICY_REQUIRED", steps, parameters, jointEvidence);
            return notEvaluated("NOT_APPLICABLE", "PM2_WELL_COVERED_ABSENCE_NOT_ALLOWED",
                    steps, parameters, jointEvidence);
        }
        if (exomeEvidence == null || genomeEvidence == null) {
            return notEvaluated("UNKNOWN", "PM2_ALL_SITES_EVIDENCE_MISSING",
                    steps, parameters, jointEvidence);
        }

        String exomeBindingIssue = evidenceBindingIssue(
                context, exomeEvidence, bindings.exomeResource(), bindings);
        String genomeBindingIssue = evidenceBindingIssue(
                context, genomeEvidence, bindings.genomeResource(), bindings);
        if (exomeBindingIssue != null || genomeBindingIssue != null) {
            return notEvaluated("UNKNOWN",
                    exomeBindingIssue != null ? exomeBindingIssue : genomeBindingIssue,
                    steps, parameters, jointEvidence);
        }
        AxesValue exomeAxes = axesValue(exomeEvidence);
        AxesValue genomeAxes = axesValue(genomeEvidence);
        String failure = allSitesExecutionIssue(exomeAxes, genomeAxes);
        if (failure != null && !"SUCCEEDED".equals(exomeAxes.execution()) && !"SUCCEEDED".equals(genomeAxes.execution())) {
            return notEvaluated("UNKNOWN", failure, steps, parameters, jointEvidence);
        }

        JsonNode callability = policy.path("allSitesCallability");
        if (!"ANY_INDEPENDENT".equals(callability.path("satisfactionMode").asText())) {
            return notEvaluated("UNKNOWN", "PM2_CALLABILITY_POLICY_INVALID",
                    steps, parameters, jointEvidence);
        }
        Set<String> independentlySufficient = textSet(
                callability.path("independentlySufficientDatasets"));
        if (independentlySufficient.isEmpty()
                || !Set.of(EXOMES, GENOMES).containsAll(independentlySufficient)) {
            return notEvaluated("UNKNOWN", "PM2_CALLABILITY_POLICY_INVALID",
                    steps, parameters, jointEvidence);
        }

        DatasetResult exomes = evaluateAllSitesDataset(EXOMES, exomeEvidence,
                exomeAxes, callability.path("exomes"), independentlySufficient, bindings);
        DatasetResult genomes = evaluateAllSitesDataset(GENOMES, genomeEvidence,
                genomeAxes, callability.path("genomes"), independentlySufficient, bindings);
        if (exomes.error() != null || genomes.error() != null) {
            return notEvaluated("UNKNOWN",
                    exomes.error() != null ? exomes.error() : genomes.error(),
                    steps, parameters, jointEvidence);
        }
        parameters.add(parameter("callability.exomes.status", exomes.status(), null));
        parameters.add(parameter("callability.genomes.status", genomes.status(), null));
        if (exomes.rawAn() != null) {
            parameters.add(parameter("callability.exomes.adjAn", exomes.rawAn(), "alleles"));
        }
        if (genomes.rawAn() != null) {
            parameters.add(parameter("callability.genomes.adjAn", genomes.rawAn(), "alleles"));
        }
        steps.add(step("EXOME_CALLABILITY_SUFFICIENT",
                exomes.sufficient() ? "TRUE" : "FALSE", exomeEvidence));
        steps.add(step("GENOME_CALLABILITY_SUFFICIENT",
                genomes.sufficient() ? "TRUE" : "FALSE", genomeEvidence));
        if (!exomes.sufficient() && !genomes.sufficient()) {
            return notEvaluated("UNKNOWN", failure == null ? "PM2_CALLABILITY_NOT_SUFFICIENT" : failure,
                    steps, parameters, jointEvidence);
        }
        steps.add(step("WELL_COVERED_ABSENCE_CONFIRMED", "TRUE",
                List.of(jointEvidence, exomeEvidence, genomeEvidence)));
        return completed(true, steps, parameters);
    }

    private State evaluatePm2Hit(Pm2Bindings bindings,
                                 JsonNode policy,
                                 ObjectNode evidence,
                                 List<ObjectNode> steps,
                                 List<ObjectNode> parameters) {
        return evaluatePm2Hit("gnomad.joint.", bindings.profileId() + "@" + bindings.profileVersion(),
                "jointQualityRequirements", policy, evidence, steps, parameters);
    }

    private State evaluatePm2Hit(String namespace, String calibration, String qualityKey,
                                 JsonNode policy, ObjectNode evidence,
                                 List<ObjectNode> steps, List<ObjectNode> parameters) {
        if (evidence.path("queryOutcome").path("recordCount").asInt(-1) != 1) {
            return notEvaluated("UNKNOWN", "QUERY_NOT_SINGLE_EXACT_HIT",
                    steps, parameters, evidence);
        }

        Reading exact = reading(evidence, "gnomad.exactAlleleMatch", calibration);
        Reading filter = reading(evidence, namespace + "filter", calibration);
        if (exact.reason() != null || filter.reason() != null) {
            return notEvaluated("UNKNOWN",
                    exact.reason() != null ? exact.reason() : filter.reason(),
                    steps, parameters, evidence);
        }
        if (!booleanValue(exact.node(), "boolean")) {
            return notEvaluated("UNKNOWN", "EXACT_ALLELE_NOT_CONFIRMED",
                    steps, parameters, evidence);
        }
        if (!"PASS".equals(textMeasurement(filter.node(), "vcf-filter"))) {
            return notEvaluated("UNKNOWN", "VARIANT_FILTER_NOT_PASS",
                    steps, parameters, evidence);
        }
        JsonNode frequencyRule = policy.path("maximumAlleleFrequency");
        if (policy.has("maximumAlleleCount")) {
            return evaluatePm2AlleleCount(policy, evidence, calibration, namespace, steps, parameters);
        }
        if (policy.has("maximumHomozygoteCount")) {
            Reading homReading = reading(evidence, namespace + "nhomalt", calibration);
            if (homReading.reason() != null) return notEvaluated("UNKNOWN", homReading.reason(), steps, parameters, evidence);
            BigInteger hom = integerMeasurement(homReading.node(), "individuals");
            BigInteger maximum = integer(policy.path("maximumHomozygoteCount"));
            Reading acReading = reading(evidence, namespace + "ac", calibration);
            Reading anReading = reading(evidence, namespace + "an", calibration);
            BigInteger ac = acReading.reason() == null ? integerMeasurement(acReading.node(), "alleles") : null;
            BigInteger an = anReading.reason() == null ? integerMeasurement(anReading.node(), "alleles") : null;
            if (hom == null || maximum == null || hom.signum() < 0 || maximum.signum() < 0
                    || ac == null || an == null || ac.signum() <= 0 || ac.compareTo(an) > 0
                    || hom.multiply(BigInteger.TWO).compareTo(ac) > 0)
                return notEvaluated("UNKNOWN", "PM2_HOMOZYGOTE_INPUT_INVALID", steps, parameters, evidence);
            parameters.add(parameter("population.homozygotes", hom.toString(), "individuals"));
            steps.add(step("PM2_HOMOZYGOTE_LIMIT_SATISFIED", hom.compareTo(maximum) <= 0 ? "TRUE" : "FALSE", evidence));
            if (hom.compareTo(maximum) > 0) return completed(false, steps, parameters);
        }
        Reading selectedFrequency = reading(evidence, frequencyRule.path("metricId").asText(), calibration);
        if ("gnomad.exomes.".equals(namespace)
                && "PM2_LOCAL_NO_FAF_SCOPE_UNSUPPORTED".equals(policy.path("missingFafResult").asText())
                && "REQUIRED_MEASUREMENT_MISSING".equals(selectedFrequency.reason()))
            return notEvaluated("UNKNOWN", "PM2_LOCAL_NO_FAF_SCOPE_UNSUPPORTED", steps, parameters, evidence);
        if ("VERIFIED_SINGLETON".equals(policy.at("/missingFafPolicy/mode").asText())
                && "REQUIRED_MEASUREMENT_MISSING".equals(selectedFrequency.reason())) {
            // A partially missing FAF result is not a verified uncomputed singleton.
            for (JsonNode measurement : evidence.path("measurements")) {
                if (measurement.path("metricId").asText().contains("faf95"))
                    return notEvaluated("UNKNOWN", "REQUIRED_MEASUREMENT_MISSING", steps, parameters, evidence);
            }
            ObjectNode singleton = mapper.createObjectNode().put("maximumAlleleCount", "1").put("minimumObservedAlleleNumber", "1");
            State result = evaluatePm2AlleleCount(singleton, evidence, calibration, namespace, steps, parameters);
            // More than one observed allele is outside this implemented special case,
            // not evidence that the VCEP's entire no-FAF permission is false.
            Reading ac = reading(evidence, namespace + "ac", calibration);
            BigInteger count = ac.reason() == null ? integerMeasurement(ac.node(), "alleles") : null;
            return BigInteger.ONE.equals(count) ? result
                    : notEvaluated("UNKNOWN", "PM2_NO_FAF_SPECIAL_CASE_REVIEW_REQUIRED", steps, parameters, evidence);
        }
        if (policy.has(qualityKey)) {
            String qualityIssue = pm2ObservedQuality(policy, evidence, calibration, namespace, qualityKey, parameters);
            if (qualityIssue != null) return notEvaluated("UNKNOWN", qualityIssue, steps, parameters, evidence);
        }
        String metricId = textOr(frequencyRule.path("metricId"), null);
        String comparator = textOr(frequencyRule.path("comparisonOperator"), null);
        BigDecimal threshold = decimal(frequencyRule.path("rawValue"));
        if (metricId == null || !Set.of(LT, LTE).contains(comparator)
                || threshold == null || threshold.signum() < 0
                || threshold.compareTo(BigDecimal.ONE) > 0) {
            return notEvaluated("UNKNOWN", "PM2_FREQUENCY_POLICY_INVALID",
                    steps, parameters, evidence);
        }
        Reading frequencyReading = reading(evidence, metricId, calibration);
        if (frequencyReading.reason() != null) {
            return notEvaluated("UNKNOWN", frequencyReading.reason(), steps, parameters, evidence);
        }
        BigDecimal frequency = decimalMeasurement(frequencyReading.node(), "fraction");
        if (frequency == null || frequency.signum() < 0
                || frequency.compareTo(BigDecimal.ONE) > 0) {
            return notEvaluated("UNKNOWN", "FREQUENCY_INPUT_INVALID",
                    steps, parameters, evidence);
        }
        parameters.add(parameter("frequency.metricId", metricId, null));
        parameters.add(parameter("frequency.raw", frequencyReading.node().path("rawValue").asText(),
                "fraction"));
        parameters.add(parameter("comparison.operator", comparator, null));
        parameters.add(parameter("threshold.raw", frequencyRule.path("rawValue").asText(),
                "fraction"));
        int comparison = frequency.compareTo(threshold);
        boolean met = comparison < 0 || (comparison == 0 && LTE.equals(comparator));
        steps.add(step("FREQUENCY_WITHIN_PM2_MAXIMUM", met ? "TRUE" : "FALSE", evidence));
        return completed(met, steps, parameters);
    }

    private State evaluatePm2AlleleCount(JsonNode policy, ObjectNode evidence, String calibration, String namespace,
                                         List<ObjectNode> steps, List<ObjectNode> parameters) {
        Reading acReading = reading(evidence, namespace + "ac", calibration);
        Reading anReading = reading(evidence, namespace + "an", calibration);
        if (acReading.reason() != null || anReading.reason() != null)
            return notEvaluated("UNKNOWN", acReading.reason() != null ? acReading.reason() : anReading.reason(), steps, parameters, evidence);
        BigInteger ac = integerMeasurement(acReading.node(), "alleles"), an = integerMeasurement(anReading.node(), "alleles");
        BigInteger maximum = integer(policy.path("maximumAlleleCount")), minimum = integer(policy.path("minimumObservedAlleleNumber"));
        if (ac == null || an == null || maximum == null || minimum == null || ac.signum() <= 0 || an.signum() <= 0
                || ac.compareTo(an) > 0 || maximum.signum() < 0 || minimum.signum() <= 0)
            return notEvaluated("UNKNOWN", "PM2_OBSERVED_FREQUENCY_INPUT_INCONSISTENT", steps, parameters, evidence);
        Reading homReading = reading(evidence, namespace + "nhomalt", calibration);
        if (homReading.reason() != null)
            return notEvaluated("UNKNOWN", homReading.reason(), steps, parameters, evidence);
        BigInteger hom = integerMeasurement(homReading.node(), "individuals");
        if (hom == null || hom.signum() < 0 || hom.multiply(BigInteger.TWO).compareTo(ac) > 0)
            return notEvaluated("UNKNOWN", "PM2_HOMOZYGOTE_INPUT_INVALID", steps, parameters, evidence);
        parameters.add(parameter("population.observedAc", ac.toString(), "alleles"));
        parameters.add(parameter("population.observedAn", an.toString(), "alleles"));
        parameters.add(parameter("population.maximumAc", maximum.toString(), "alleles"));
        parameters.add(parameter("population.minimumAn", minimum.toString(), "alleles"));
        if (an.compareTo(minimum) < 0)
            return notEvaluated("UNKNOWN", "PM2_OBSERVED_SAMPLE_SIZE_INSUFFICIENT", steps, parameters, evidence);
        boolean met = ac.compareTo(maximum) <= 0;
        steps.add(step("PM2_ALLELE_COUNT_LIMIT_SATISFIED", met ? "TRUE" : "FALSE", evidence));
        return completed(met, steps, parameters);
    }

    private String pm2ObservedQuality(JsonNode policy, ObjectNode evidence, String calibration, String namespace, String qualityKey,
                                      List<ObjectNode> parameters) {
        if (!"fraction".equals(policy.at("/maximumAlleleFrequency/unit").asText())
                || !(namespace + "faf95max").equals(policy.at("/maximumAlleleFrequency/metricId").asText()))
            return "PM2_FREQUENCY_POLICY_INVALID";
        Reading group = reading(evidence, namespace + "faf95max.ancestry", calibration);
        if (group.reason() != null) return group.reason();
        String ancestry = textMeasurement(group.node(), "genetic-ancestry-group");
        if (ancestry == null || !textSet(policy.path(qualityKey).path("permittedAncestries")).contains(ancestry))
            return "PM2_ANCESTRY_NOT_SUPPORTED";
        Reading ac = reading(evidence, namespace + "ancestry." + ancestry + ".ac", calibration);
        Reading an = reading(evidence, namespace + "ancestry." + ancestry + ".an", calibration);
        Reading af = reading(evidence, namespace + "ancestry." + ancestry + ".af", calibration);
        Reading faf = reading(evidence, namespace + "ancestry." + ancestry + ".faf95", calibration);
        Reading selected = reading(evidence, namespace + "faf95max", calibration);
        for (Reading r : List.of(ac, an, af, faf, selected)) if (r.reason() != null) return r.reason();
        BigInteger count = integerMeasurement(ac.node(), "alleles"), number = integerMeasurement(an.node(), "alleles");
        BigDecimal frequency = decimalMeasurement(af.node(), "fraction"), filtering = decimalMeasurement(faf.node(), "fraction");
        BigDecimal maximum = decimalMeasurement(selected.node(), "fraction");
        if (count == null || number == null || count.signum() <= 0 || number.signum() <= 0 || count.compareTo(number) > 0
                || frequency == null || filtering == null || maximum == null || frequency.signum() <= 0
                || frequency.compareTo(BigDecimal.ONE) > 0 || filtering.signum() < 0
                || filtering.compareTo(frequency) > 0 || filtering.compareTo(maximum) != 0)
            return "PM2_OBSERVED_FREQUENCY_INPUT_INCONSISTENT";
        if ("gnomad.exomes.".equals(namespace)
                && !Bs1Quality.countsAgree(new BigDecimal(count), new BigDecimal(number), frequency))
            return "PM2_OBSERVED_FREQUENCY_INPUT_INCONSISTENT";
        parameters.add(parameter("frequency.selectedAncestry", ancestry, "genetic-ancestry-group"));
        parameters.add(parameter("frequency.selectedAc", count.toString(), "alleles"));
        parameters.add(parameter("frequency.selectedAn", number.toString(), "alleles"));
        parameters.add(parameter("frequency.selectedAf", af.node().path("rawValue").asText(), "fraction"));
        return null;
    }

    private Pm2Bindings pm2Bindings(GnomadPopulationInputProfile loadedProfile,
                                    ObjectNode registry) {
        ObjectNode profile = loadedProfile.getDocument();
        String profileHash = loadedProfile.getProfileSha256();
        String issue = profileHash.equals(canonicalizer.canonicalSha256(profile))
                ? null : "PROFILE_HASH_MISMATCH";
        JsonNode activation = profile.path("activation");
        if (issue == null && (!activation.path("runtimeBound").asBoolean(false)
                || !activation.path("evaluationEnabled").asBoolean(false)
                || activation.path("patientFlowEnabled").asBoolean(true)
                || activation.path("classificationEnabled").asBoolean(true))) {
            issue = "PROFILE_ACTIVATION_MISMATCH";
        }
        JsonNode registryBinding = profile.path("registryBinding");
        String registryId = textOr(registryBinding.path("registryId"), "");
        String registryHash = textOr(registryBinding.path("registrySha256"), "");
        ObjectNode pinnedRegistry = loadedProfile.getSuccessorRegistryDocument();
        Criterion pm2 = criterion(profile, pinnedRegistry, PM2, "PM2_SUPPORTING", "SUPPORTING");
        if (issue == null && (!pinnedRegistry.equals(registry)
                || !registryId.equals(registry.path("registryId").asText())
                || !criterionBound(pm2, pinnedRegistry))) {
            issue = "RULE_REGISTRY_BINDING_MISMATCH";
        }
        JsonNode joint = find(profile.path("resources"), "resourceId",
                GnomadPopulationInputProfile.JOINT_RESOURCE_ID);
        JsonNode exomes = find(profile.path("resources"), "resourceId",
                GnomadPopulationInputProfile.EXOME_ALL_SITES_RESOURCE_ID);
        JsonNode genomes = find(profile.path("resources"), "resourceId",
                GnomadPopulationInputProfile.GENOME_ALL_SITES_RESOURCE_ID);
        if (issue == null && (!joint.isObject() || !exomes.isObject() || !genomes.isObject()
                || profile.path("resources").size() != 3)) {
            issue = "PM2_RESOURCE_BINDING_MISMATCH";
        }
        return new Pm2Bindings(profile, profileHash, loadedProfile.getProfileId(),
                loadedProfile.getProfileVersion(), registryId, registryHash,
                joint, exomes, genomes, pm2, issue);
    }

    private List<ObjectNode> pm2EvidenceSet(ObjectNode joint,
                                            ObjectNode exomes,
                                            ObjectNode genomes) {
        List<ObjectNode> values = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (ObjectNode value : new ObjectNode[] {joint, exomes, genomes}) {
            if (value == null) continue;
            String id = value.path("evidenceId").asText();
            if (!id.isBlank() && ids.add(id)) values.add(value);
        }
        return values;
    }

    private PolicyResult pm2Policy(JsonNode rule,
                                   JsonNode context,
                                   Pm2Bindings bindings) {
        String gene = textOr(context.path("gene").path("hgncId"), null);
        String mondo = textOr(context.path("disease").path("mondoId"), null);
        String moi = textOr(context.path("moi"), null);
        if (gene == null || mondo == null || moi == null) {
            return PolicyResult.blocked("PM2_POLICY_NOT_FOUND_OR_AMBIGUOUS");
        }
        JsonNode snapshot = rule.path("policySnapshot");
        if (!snapshot.isObject() || !snapshot.path("records").isArray()
                || textOr(snapshot.path("snapshotId"), null) == null
                || textOr(snapshot.path("version"), null) == null) {
            return PolicyResult.blocked("PM2_POLICY_SNAPSHOT_INVALID");
        }
        ObjectNode projection = ((ObjectNode) snapshot).deepCopy();
        projection.remove("sha256");
        if (!snapshot.path("sha256").asText().equals(
                canonicalizer.canonicalSha256(projection))) {
            return PolicyResult.blocked("PM2_POLICY_SNAPSHOT_INVALID");
        }
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode record : snapshot.path("records")) {
            if (gene.equals(record.path("gene").path("hgncId").asText())
                    && mondo.equals(record.path("disease").path("mondoId").asText())
                    && moi.equals(record.path("moi").asText())) {
                matches.add(record);
            }
        }
        if (matches.size() != 1) {
            return PolicyResult.blocked("PM2_POLICY_NOT_FOUND_OR_AMBIGUOUS");
        }
        JsonNode record = matches.get(0);
        if (textOr(record.path("policyId"), null) == null
                || textOr(record.path("version"), null) == null) {
            return PolicyResult.blocked("PM2_POLICY_RECORD_INVALID");
        }
        return new PolicyResult(true, null, record,
                snapshot.path("snapshotId").asText(), snapshot.path("version").asText(),
                snapshot.path("sha256").asText());
    }

    private void addPolicyParameters(List<ObjectNode> parameters,
                                     PolicyResult policy,
                                     JsonNode rule) {
        parameters.add(parameter("pm2.policySnapshotId", policy.snapshotId(), null));
        parameters.add(parameter("pm2.policySnapshotVersion", policy.snapshotVersion(), null));
        parameters.add(parameter("pm2.policySnapshotSha256", policy.snapshotHash(), "sha256"));
        parameters.add(parameter("pm2.policyId", policy.record().path("policyId").asText(), null));
        parameters.add(parameter("pm2.policyVersion", policy.record().path("version").asText(), null));
        if (policy.record().path("authority").has("sourceUrl")) {
            parameters.add(parameter("pm2.authoritySource", policy.record().at("/authority/sourceUrl").asText(), null));
            parameters.add(parameter("pm2.authorityVersion", policy.record().at("/authority/authorityVersion").asText(), null));
            parameters.add(parameter("pm2.authoritySha256", policy.record().at("/authority/snapshotSha256").asText(), "sha256"));
        }
    }

    private String pm2PolicyBindingIssue(JsonNode policy, Pm2Bindings bindings) {
        JsonNode releases = policy.path("resourceReleaseBindings");
        if (!releases.isArray() || releases.size() != 3) {
            return "PM2_POLICY_RESOURCE_BINDING_INVALID";
        }
        Set<String> matched = new LinkedHashSet<>();
        for (JsonNode release : releases) {
            String resourceId = release.path("resourceId").asText();
            JsonNode expected = switch (resourceId) {
                case GnomadPopulationInputProfile.JOINT_RESOURCE_ID -> bindings.jointResource();
                case GnomadPopulationInputProfile.EXOME_ALL_SITES_RESOURCE_ID -> bindings.exomeResource();
                case GnomadPopulationInputProfile.GENOME_ALL_SITES_RESOURCE_ID -> bindings.genomeResource();
                default -> null;
            };
            if (expected == null || !matched.add(resourceId)
                    || !expected.path("resourceVersion").asText()
                    .equals(release.path("resourceVersion").asText())
                    || !expected.path("identityFingerprintSha256").asText()
                    .equals(release.path("identityFingerprintSha256").asText())) {
                return "PM2_POLICY_RESOURCE_BINDING_INVALID";
            }
        }
        if (matched.size() != 3 || !policy.path("allowWellCoveredAbsence").isBoolean()) {
            return "PM2_POLICY_RECORD_INVALID";
        }
        JsonNode authority = policy.path("authority");
        for (JsonNode binding : bindings.profile().path("contextPolicyAuthorityBindings")) {
            if (authority.path("authoritySourceId").equals(binding.path("authoritySourceId"))
                    && !authority.equals(binding)) return "PM2_POLICY_AUTHORITY_INVALID";
        }
        if (!authority.isObject()
                || !bindings.pm2().authorities().contains(
                authority.path("authoritySourceId").asText())
                || textOr(authority.path("authorityVersion"), null) == null
                || !authority.path("snapshotSha256").asText().matches("^[0-9a-f]{64}$")) {
            return "PM2_POLICY_AUTHORITY_INVALID";
        }
        return null;
    }

    private String evidenceBindingIssue(JsonNode context,
                                        ObjectNode evidence,
                                        JsonNode resource,
                                        Pm2Bindings bindings) {
        if (evidence == null || !resource.isObject()) return "PM2_ATOMIC_EVIDENCE_MISSING";
        if (!context.path("contextId").asText().equals(evidence.path("contextId").asText())) {
            return "ATOMIC_EVIDENCE_CONTEXT_MISMATCH";
        }
        if (!C14N.equals(context.path("canonicalizationProfile").asText())
                || !C14N.equals(evidence.path("canonicalizationProfile").asText())
                || !"atomic-evidence-v2".equals(evidence.path("schemaVersion").asText())
                || !"POPULATION_FREQUENCY".equals(evidence.path("evidenceType").asText())
                || !RuntimeEvidenceV2Adapter.PRODUCER_ID.equals(
                evidence.path("provenance").path("producerId").asText())) {
            return "ATOMIC_EVIDENCE_BINDING_MISMATCH";
        }
        JsonNode source = evidence.path("source");
        if (!resource.path("resourceId").asText().equals(source.path("sourceId").asText())
                || !resource.path("resourceVersion").asText()
                .equals(source.path("resourceVersion").asText())
                || !resource.path("identityFingerprintSha256").asText()
                .equals(source.path("resourceSha256").asText())) {
            return "RESOURCE_IDENTITY_MISMATCH";
        }
        if (!"GRCh38".equals(resource.path("assembly").asText())
                || !resource.path("assembly").asText().equals(
                context.path("variant").path("evidenceQueryAllele").path("assembly").asText())) {
            return "EVIDENCE_QUERY_ASSEMBLY_MISMATCH";
        }
        String queryStatus = evidence.path("queryOutcome").path("status").asText();
        if (Set.of("HIT", "NO_RECORD").contains(queryStatus)
                && !expectedQueryFingerprint(context, resource).equals(
                evidence.path("queryOutcome").path("queryFingerprint").asText())) {
            return "EVIDENCE_QUERY_ALLELE_MISMATCH";
        }
        String calibration = bindings.profileId() + "@" + bindings.profileVersion();
        for (JsonNode measurement : evidence.path("measurements")) {
            if (!calibration.equals(measurement.path("calibrationProfileId").asText())) {
                return "PROFILE_CALIBRATION_MISMATCH";
            }
        }
        return null;
    }

    private String expectedQueryFingerprint(JsonNode context, JsonNode resource) {
        JsonNode allele = context.path("variant").path("evidenceQueryAllele");
        ObjectNode projection = mapper.createObjectNode();
        projection.put("resourceId", resource.path("resourceId").asText());
        if (GnomadPopulationInputProfile.JOINT_RESOURCE_ID.equals(
                resource.path("resourceId").asText())) {
            projection.set("queryAllele", allele.deepCopy());
        } else {
            projection.put("identityFingerprintSha256",
                    resource.path("identityFingerprintSha256").asText());
            projection.set("assembly", allele.path("assembly").deepCopy());
            projection.set("canonicalContig", allele.path("contig").deepCopy());
            projection.set("position", allele.path("position").deepCopy());
        }
        return canonicalizer.canonicalSha256(projection);
    }

    private AxesValue axesValue(ObjectNode evidence) {
        JsonNode query = evidence.path("queryOutcome");
        return new AxesValue(
                evidence.path("resourceExecution").path("status").asText(),
                evidence.path("resourceAvailability").path("status").asText(),
                query.path("status").asText(), query.path("recordCount").asInt(-1),
                evidence.path("resourceApplicability").path("status").asText());
    }

    private String readyAxesIssue(AxesValue axes, boolean allowNoRecord) {
        if ("TIMEOUT".equals(axes.execution())) return "RESOURCE_EXECUTION_TIMEOUT";
        if (!"SUCCEEDED".equals(axes.execution())) return "RESOURCE_EXECUTION_NOT_SUCCEEDED";
        if (!"AVAILABLE".equals(axes.availability())) return "RESOURCE_NOT_AVAILABLE";
        if (!"APPLICABLE".equals(axes.applicability())) return "RESOURCE_NOT_APPLICABLE";
        if ("HIT".equals(axes.query()) && axes.recordCount() == 1) return null;
        if (allowNoRecord && "NO_RECORD".equals(axes.query()) && axes.recordCount() == 0) return null;
        return "QUERY_OUTCOME_NOT_READY";
    }

    private String allSitesExecutionIssue(AxesValue exomes, AxesValue genomes) {
        if ("TIMEOUT".equals(exomes.execution()) || "TIMEOUT".equals(genomes.execution())) {
            return "ALL_SITES_QUERY_TIMEOUT";
        }
        if (!"SUCCEEDED".equals(exomes.execution())
                || !"SUCCEEDED".equals(genomes.execution())) {
            return "ALL_SITES_QUERY_FAILED";
        }
        return null;
    }

    private DatasetResult evaluateAllSitesDataset(String dataset,
                                                  ObjectNode evidence,
                                                  AxesValue axes,
                                                  JsonNode policy,
                                                  Set<String> independentlySufficient,
                                                  Pm2Bindings bindings) {
        if (!independentlySufficient.contains(dataset)) {
            return new DatasetResult("NOT_SELECTED", false, null, null);
        }
        if (!"SUCCEEDED".equals(axes.execution())) {
            return new DatasetResult("TIMEOUT".equals(axes.execution()) ? "QUERY_TIMEOUT" : "QUERY_FAILED", false, null, null);
        }
        if (!policy.isObject()) {
            return DatasetResult.error("PM2_CALLABILITY_POLICY_INVALID");
        }
        JsonNode expectedResource = EXOMES.equals(dataset)
                ? bindings.exomeResource() : bindings.genomeResource();
        if (!expectedResource.path("resourceId").asText()
                .equals(policy.path("resourceId").asText())) {
            return DatasetResult.error("PM2_CALLABILITY_POLICY_INVALID");
        }
        if (!"AVAILABLE".equals(axes.availability())) {
            return new DatasetResult("UNAVAILABLE", false, null, null);
        }
        if (!"APPLICABLE".equals(axes.applicability())) {
            return new DatasetResult("NOT_APPLICABLE", false, null, null);
        }
        if ("NO_RECORD".equals(axes.query()) && axes.recordCount() == 0) {
            if (!evidence.path("measurements").isArray()
                    || !evidence.path("measurements").isEmpty()) {
                return DatasetResult.error("ALL_SITES_NO_RECORD_HAS_MEASUREMENTS");
            }
            return new DatasetResult("NO_RECORD", false, null, null);
        }
        if (!"HIT".equals(axes.query()) || axes.recordCount() != 1) {
            return DatasetResult.error("ALL_SITES_QUERY_OUTCOME_INVALID");
        }

        String calibration = bindings.profileId() + "@" + bindings.profileVersion();
        String metricId = EXOMES.equals(dataset) ? EXOME_AN_METRIC : GENOME_AN_METRIC;
        Reading reading = reading(evidence, metricId, calibration);
        if (reading.reason() != null) return DatasetResult.error(reading.reason());
        BigInteger alleleNumber = integerMeasurement(reading.node(), "alleles");
        if (alleleNumber == null || alleleNumber.signum() < 0) {
            return DatasetResult.error("ALL_SITES_ADJ_AN_INVALID");
        }

        if (EXOMES.equals(dataset)) {
            MapFlagResult flags = exomeRegionFlags(evidence, calibration);
            if (flags.error() != null) return DatasetResult.error(flags.error());
            Set<String> excluded = textSet(policy.path("excludedWhenAnyFlagsTrue"));
            if (!EXOME_REGION_FLAGS.containsAll(excluded)) {
                return DatasetResult.error("PM2_CALLABILITY_POLICY_INVALID");
            }
            for (String flag : excluded) {
                if (Boolean.TRUE.equals(flags.values().get(flag))) {
                    return new DatasetResult("EXCLUDED_BY_POLICY", false,
                            alleleNumber.toString(), null);
                }
            }
        }

        JsonNode minimum = policy.path("minimumAdjAlleleNumber");
        BigInteger threshold = integer(minimum.path("rawValue"));
        String comparator = minimum.path("comparisonOperator").asText();
        if (threshold == null || threshold.signum() < 1
                || !Set.of(GT, GTE).contains(comparator)) {
            return DatasetResult.error("PM2_CALLABILITY_POLICY_INVALID");
        }
        int comparison = alleleNumber.compareTo(threshold);
        boolean sufficient = comparison > 0 || (comparison == 0 && GTE.equals(comparator));
        return new DatasetResult(sufficient ? "SUFFICIENT" : "BELOW_MINIMUM",
                sufficient, alleleNumber.toString(), null);
    }

    private MapFlagResult exomeRegionFlags(ObjectNode evidence, String calibration) {
        java.util.Map<String, Boolean> values = new java.util.LinkedHashMap<>();
        for (String flag : new TreeSet<>(EXOME_REGION_FLAGS)) {
            Reading reading = reading(evidence, EXOME_FLAG_PREFIX + flag, calibration);
            if (reading.reason() != null) return MapFlagResult.error(reading.reason());
            Boolean value = booleanMeasurement(reading.node());
            if (value == null) return MapFlagResult.error("EXOME_REGION_FLAG_INVALID");
            values.put(flag, value);
        }
        return new MapFlagResult(Collections.unmodifiableMap(values), null);
    }

    private ObjectNode pm2Output(Pm2Bindings bindings,
                                 List<ObjectNode> evidence,
                                 String selectionId,
                                 State state) {
        return pm2Output(bindings, evidence, selectionId, state, PM2_EVALUATOR_VERSION);
    }

    private ObjectNode pm2Output(Pm2Bindings bindings, List<ObjectNode> evidence, String selectionId,
                                 State state, String evaluatorVersion) {
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", "criterion-evaluation-v2");
        result.put("contextId", evidence.get(0).path("contextId").asText());
        result.put("canonicalizationProfile", C14N);
        result.put("criterionId", bindings.pm2().id());
        ObjectNode ruleBinding = result.putObject("ruleBinding");
        ruleBinding.put("ruleRegistryId", bindings.registryId());
        ruleBinding.put("ruleRegistrySha256", bindings.registryHash());
        ruleBinding.put("currentGeneralDesignation", bindings.pm2().designation());
        ArrayNode authorities = ruleBinding.putArray("authoritySourceIds");
        new TreeSet<>(bindings.pm2().authorities()).forEach(authorities::add);
        ruleBinding.put("cspecSelectionId", selectionId);
        ObjectNode applicability = result.putObject("criterionApplicability");
        applicability.put("status", state.applicability());
        if (!"APPLICABLE".equals(state.applicability())) applicability.put("reasonCode", state.reason());
        ObjectNode evaluation = result.putObject("evaluationResult");
        evaluation.put("status", state.result());
        if ("NOT_EVALUATED".equals(state.result())) evaluation.put("reasonCode", state.reason());
        result.put("baseStrength", bindings.pm2().strength());
        result.put("appliedStrength", "MET".equals(state.result())
                ? bindings.pm2().strength() : "NONE");
        ArrayNode evidenceIds = result.putArray("evidenceIds");
        evidence.stream().map(value -> value.path("evidenceId").asText()).sorted().forEach(evidenceIds::add);
        ObjectNode trace = result.putObject("deterministicTrace");
        trace.put("executionStatus", state.completed() ? "COMPLETED" : "NOT_RUN");
        trace.put("evaluatorId", EVALUATOR_ID);
        trace.put("evaluatorVersion", evaluatorVersion);
        trace.set("inputEvidenceIds", evidenceIds.deepCopy());
        trace.set("parameters", array(sortedParameters(state.parameters())));
        trace.set("steps", array(state.steps()));
        // E1 treats evidence references as sets, while retaining the order of trace steps.
        for (var item : trace.path("steps")) {
            var ids = new ArrayList<String>(); item.path("inputEvidenceIds").forEach(id -> ids.add(id.asText()));
            Collections.sort(ids); ((ObjectNode) item).set("inputEvidenceIds", mapper.valueToTree(ids));
        }
        if (state.completed()) {
            trace.put("algorithmId", "GNOMAD_PM2_SUPPORTING_V1");
            trace.put("algorithmVersion", "1.0.0");
            trace.put("traceSha256", canonicalizer.canonicalSha256(trace));
        } else {
            trace.put("reasonCode", state.reason());
        }
        result.set("conflict", summary("NONE", "conflictIds"));
        ObjectNode review = summary("NOT_REQUIRED", "reviewRecordIds");
        review.put("decision", "NONE");
        result.set("review", review);
        ObjectNode identity = result.deepCopy();
        identity.remove(List.of("evaluationId", "conflict", "review"));
        result.put("evaluationId", "evaluation-sha256-" + canonicalizer.canonicalSha256(identity));
        return result;
    }
    private ObjectNode evaluateBa1(Bindings bindings,
                                   Input input,
                                   JsonNode context,
                                   ObjectNode evidence,
                                   String selectionId) {
        return output(bindings, bindings.ba1(), evidence, selectionId, ba1State(bindings, input, context, evidence));
    }
    private State ba1State(Bindings bindings, Input input, JsonNode context, ObjectNode evidence) {
        State state = input.blockedState();
        if (state == null) {
            List<ObjectNode> steps = copy(input.steps());
            List<ObjectNode> parameters = copy(input.parameters());
            JsonNode minimumRule = bindings.ba1().rule().path("minimumSelectedAncestryAlleleNumber");
            BigInteger minimumAn = integer(minimumRule.path("rawValue"));
            boolean catalogBa1 = bindings.profile().has("ba1CatalogPolicy");
            String minimumOperator = minimumRule.path("comparisonOperator").asText();
            if (!(GTE.equals(minimumOperator) || catalogBa1 && GT.equals(minimumOperator))
                    || minimumAn == null || minimumAn.signum() < 1) {
                state = notEvaluated("UNKNOWN", "BA1_MINIMUM_AN_RULE_INVALID", steps, parameters, evidence);
            } else {
                parameters.add(parameter("frequency.minimumSelectedAncestryAn", minimumAn.toString(), "alleles"));
                if (catalogBa1) parameters.add(parameter("frequency.minimumAnOperator", minimumOperator, null));
                if (input.selectedAn().compareTo(minimumAn) < 0
                        || catalogBa1 && GT.equals(minimumOperator) && input.selectedAn().equals(minimumAn)) {
                    state = notEvaluated("UNKNOWN", "SELECTED_ANCESTRY_AN_BELOW_MINIMUM",
                            steps, parameters, evidence);
                } else {
                    steps.add(step("SELECTED_ANCESTRY_AN_SUFFICIENT", "TRUE", evidence));
                    ExceptionResult exception = ba1Exception(bindings.ba1().rule(), context);
                    parameters.add(parameter("ba1.exceptionOutcome", exception.outcome(), null));
                    steps.add(step("BA1_EXCEPTION_NOT_HIT",
                            "NOT_HIT".equals(exception.outcome()) ? "TRUE" : "FALSE", evidence));
                    if (!"NOT_HIT".equals(exception.outcome())) {
                        state = notEvaluated("HIT".equals(exception.outcome())
                                        ? "NOT_APPLICABLE" : "UNKNOWN",
                                exception.reason(), steps, parameters, evidence);
                    } else {
                        JsonNode thresholdNode = bindings.ba1().rule().path("threshold");
                        BigDecimal threshold = decimal(thresholdNode.path("normalizedValue"));
                        BigDecimal rawThreshold = decimal(thresholdNode.path("rawValue"));
                        String comparator = bindings.ba1().rule().path("comparisonOperator").asText();
                        boolean disease = bindings.profile().has("diseaseFrequencyPolicy") || bindings.selectedPolicy() != null || catalogBa1;
                        if (!(GT.equals(comparator) || disease && GTE.equals(comparator))
                                || threshold == null || rawThreshold == null
                                || threshold.compareTo(rawThreshold) != 0) {
                            state = notEvaluated("UNKNOWN", "BA1_THRESHOLD_RULE_INVALID",
                                    steps, parameters, evidence);
                        } else {
                            parameters.add(parameter("comparison.operator", comparator, null));
                            parameters.add(parameter("threshold.raw", thresholdNode.path("rawValue").asText(),
                                    "fraction"));
                            int compared = input.frequency().compareTo(threshold);
                            boolean met = compared > 0 || compared == 0 && GTE.equals(comparator);
                            steps.add(step(disease ? "FREQUENCY_COMPARATOR_SATISFIED" : "FREQUENCY_GREATER_THAN_THRESHOLD", met ? "TRUE" : "FALSE", evidence));
                            state = completed(met, steps, parameters);
                        }
                    }
                }
            }
        }
        return state;
    }
    private ObjectNode evaluateBs1(Bindings bindings,
                                   Input input,
                                   JsonNode context,
                                   ObjectNode evidence,
                                   String selectionId) {
        State state = input.blockedState();
        if (state == null) {
            List<ObjectNode> steps = copy(input.steps());
            List<ObjectNode> parameters = copy(input.parameters());
            ThresholdResult threshold = bs1Threshold(bindings.bs1().rule(), context);
            steps.add(step("BS1_EXACT_THRESHOLD_SELECTED", threshold.ready() ? "TRUE" : "FALSE", evidence));
            if (!threshold.ready()) {
                state = notEvaluated("UNKNOWN", threshold.reason(), steps, parameters, evidence);
            } else {
                JsonNode snapshot = bindings.bs1().rule().path("thresholdSnapshot");
                parameters.add(parameter("bs1.thresholdSnapshotId", snapshot.path("snapshotId").asText(), null));
                parameters.add(parameter("bs1.thresholdSnapshotVersion", snapshot.path("version").asText(), null));
                parameters.add(parameter("bs1.thresholdSnapshotSha256", snapshot.path("sha256").asText(), "sha256"));
                parameters.add(parameter("bs1.thresholdId", threshold.id(), null));
                parameters.add(parameter("bs1.thresholdVersion", threshold.version(), null));
                parameters.add(parameter("comparison.operator", threshold.comparator(), null));
                parameters.add(parameter("threshold.raw", threshold.raw(), "fraction"));
                parameters.add(parameter("bs1.thresholdAuthoritySourceIds",
                        String.join(",", new TreeSet<>(threshold.authorities())), "authority-source-id-list"));
                int comparison = input.frequency().compareTo(threshold.value());
                boolean met = comparison > 0 || (comparison == 0
                        && "GREATER_THAN_OR_EQUAL".equals(threshold.comparator()));
                steps.add(step("FREQUENCY_COMPARATOR_SATISFIED", met ? "TRUE" : "FALSE", evidence));
                state = completed(met, steps, parameters);
            }
        }
        return output(bindings, bindings.bs1(), evidence, selectionId, state);
    }
    private Bindings bindings(GnomadPopulationInputProfile loadedProfile, ObjectNode registry) {
        ObjectNode profile = loadedProfile.getDocument();
        String profileHash = loadedProfile.getProfileSha256();
        String issue = profileHash.equals(canonicalizer.canonicalSha256(profile))
                ? null : "PROFILE_HASH_MISMATCH";
        JsonNode activation = profile.path("activation");
        if (issue == null && (!activation.path("runtimeBound").asBoolean(false)
                || !activation.path("evaluationEnabled").asBoolean(false)
                || activation.path("patientFlowEnabled").asBoolean(true)
                || activation.path("classificationEnabled").asBoolean(true))) {
            issue = "PROFILE_ACTIVATION_MISMATCH";
        }
        JsonNode registryBinding = profile.path("registryBinding");
        String registryId = registryBinding.path("registryId").asText();
        String registryHash = registryBinding.path("registrySha256").asText();
        ObjectNode pinnedRegistry = loadedProfile.getSuccessorRegistryDocument();
        Criterion ba1 = criterion(profile, pinnedRegistry, BA1, "BA1", "STANDALONE");
        Criterion bs1 = criterion(profile, pinnedRegistry, BS1, "BS1", "STRONG");
        if (issue == null && (!pinnedRegistry.equals(registry)
                || !registryId.equals(registry.path("registryId").asText())
                || !criterionBound(ba1, pinnedRegistry) || !criterionBound(bs1, pinnedRegistry))) {
            issue = "RULE_REGISTRY_BINDING_MISMATCH";
        }
        JsonNode resource = profile.path("resources").path(0);
        JsonNode gates = profile.path("criterionInputGates");
        Set<String> ancestries = textSet(profile.path("fieldContract").path("faf95AncestryGroups"));
        return new Bindings(profile, profileHash, loadedProfile.getProfileId(), loadedProfile.getProfileVersion(),
                registryId, registryHash, resource, gates, ancestries, ba1, bs1, issue, null);
    }
    private Criterion criterion(JsonNode profile, JsonNode registry, String id,
                                String fallbackDesignation, String fallbackStrength) {
        JsonNode declared = find(profile.path("evaluation").path("criteria"), "criterionId", id);
        JsonNode registered = find(registry.path("criteria"), "criterionId", id);
        String designation = textOr(declared.path("currentDesignation"), fallbackDesignation);
        String strength = textOr(declared.path("baseStrength"), fallbackStrength);
        Set<String> authorities = new LinkedHashSet<>(textSet(
                registered.path("currentGeneral").path("authoritySourceIds")));
        // A pinned successor profile may add a scoped VCEP policy authority without modifying
        // the historical general rule registry or applying that VCEP to other gene contexts.
        if (PM2.equals(id) || profile.has("diseaseFrequencyPolicy")) for (JsonNode binding : profile.path("contextPolicyAuthorityBindings"))
            authorities.add(binding.path("authoritySourceId").asText());
        JsonNode rule = declared.has("populationRule")
                ? declared.path("populationRule") : declared.path("frequencyRule");
        return new Criterion(id, designation, strength, rule,
                Collections.unmodifiableSet(authorities));
    }
    private boolean criterionBound(Criterion criterion, JsonNode registry) {
        JsonNode registered = find(registry.path("criteria"), "criterionId", criterion.id());
        return registered.isObject()
                && "V2_IMPLEMENTED".equals(registered.path("implementationStatus").asText())
                && criterion.designation().equals(registered.path("currentGeneral").path("designation").asText())
                && criterion.strength().equals(registered.path("currentGeneral").path("strength").asText());
    }
    private Input input(Bindings bindings, JsonNode context, ObjectNode evidence) {
        return input(bindings, context, evidence, false);
    }
    private Input input(Bindings bindings, JsonNode context, ObjectNode evidence, boolean conditional) {
        List<ObjectNode> steps = new ArrayList<>();
        List<ObjectNode> parameters = baseParameters(bindings, evidence);
        if (bindings.issue() != null) return blocked(bindings.issue(), steps, parameters, evidence);
        JsonNode selectedPolicy = bindings.selectedPolicy();
        if (selectedPolicy != null) {
            String issue = PopulationFrequencyPolicy.contextIssue(selectedPolicy, context);
            if (issue != null) return blocked(issue, steps, parameters, evidence);
            parameters.add(parameter("diseaseFrequency.policyId", selectedPolicy.path("policyId").asText(), null));
            parameters.add(parameter("diseaseFrequency.policyVersion", selectedPolicy.path("policyVersion").asText(), null));
            parameters.add(parameter("diseaseFrequency.policySha256", canonicalizer.canonicalSha256(selectedPolicy), "sha256"));
            for (JsonNode unresolved : selectedPolicy.at("/exceptionReview/unresolvedAlleles")) {
                if (!completeAllele(unresolved) || sameAllele(context.at("/variant/evidenceQueryAllele"), unresolved))
                    return blocked("POPULATION_FREQUENCY_EXCEPTION_REVIEW_REQUIRED", steps, parameters, evidence);
            }
            ExceptionResult exception = ba1Exception(bindings.ba1().rule(), context);
            if (!"NOT_HIT".equals(exception.outcome())) return blocked(exception.reason(), steps, parameters, evidence);
            parameters.add(parameter("diseaseFrequency.exceptionReviewId", selectedPolicy.at("/exceptionReview/reviewId").asText(), null));
        }
        if (bindings.profile().has("diseaseFrequencyPolicy")) {
            String issue = hnf1aContextIssue(context);
            if (issue != null) return blocked(issue, steps, parameters, evidence);
            JsonNode policy = bindings.profile().path("diseaseFrequencyPolicy");
            parameters.add(parameter("diseaseFrequency.policyId", policy.path("policyId").asText(), null));
            parameters.add(parameter("diseaseFrequency.policyVersion", policy.path("policyVersion").asText(), null));
            parameters.add(parameter("diseaseFrequency.policySha256", canonicalizer.canonicalSha256(policy), "sha256"));
            JsonNode review = policy.path("exceptionReview");
            if (!"REVIEWED_NO_RELEVANT_EXCEPTION_IDENTIFIED_IN_PINNED_SOURCES".equals(review.path("status").asText())
                    || !review.path("unresolvedAlleles").isArray())
                return blocked("HNF1A_EXCEPTION_REVIEW_REQUIRED", steps, parameters, evidence);
            for (JsonNode unresolved : review.path("unresolvedAlleles"))
                if (sameAllele(context.at("/variant/evidenceQueryAllele"), unresolved))
                    return blocked("HNF1A_EXCEPTION_REVIEW_REQUIRED", steps, parameters, evidence);
            ExceptionResult exception = ba1Exception(bindings.ba1().rule(), context);
            if (!"NOT_HIT".equals(exception.outcome())) return blocked(exception.reason(), steps, parameters, evidence);
            parameters.add(parameter("diseaseFrequency.exceptionReviewId", review.path("reviewId").asText(), null));
        }
        if (!context.path("contextId").asText().equals(evidence.path("contextId").asText()))
            return blocked("ATOMIC_EVIDENCE_CONTEXT_MISMATCH", steps, parameters, evidence);
        if (!C14N.equals(context.path("canonicalizationProfile").asText())
                || !C14N.equals(evidence.path("canonicalizationProfile").asText())
                || !"POPULATION_FREQUENCY".equals(evidence.path("evidenceType").asText())
                || !RuntimeEvidenceV2Adapter.PRODUCER_ID.equals(
                evidence.path("provenance").path("producerId").asText()))
            return blocked("ATOMIC_EVIDENCE_BINDING_MISMATCH", steps, parameters, evidence);
        JsonNode source = evidence.path("source");
        if (!bindings.resource().path("resourceId").asText().equals(source.path("sourceId").asText())
                || !bindings.resource().path("resourceVersion").asText().equals(source.path("resourceVersion").asText())
                || !bindings.resource().path("identityFingerprintSha256").asText()
                .equals(source.path("resourceSha256").asText()))
            return blocked("RESOURCE_IDENTITY_MISMATCH", steps, parameters, evidence);
        if (!bindings.resource().path("assembly").asText().equals(
                context.path("variant").path("evidenceQueryAllele").path("assembly").asText()))
            return blocked("EVIDENCE_QUERY_ASSEMBLY_MISMATCH", steps, parameters, evidence);
        String execution = evidence.path("resourceExecution").path("status").asText();
        String availability = evidence.path("resourceAvailability").path("status").asText();
        String query = evidence.path("queryOutcome").path("status").asText();
        String applicability = evidence.path("resourceApplicability").path("status").asText();
        parameters.add(parameter("resource.executionStatus", execution, null));
        parameters.add(parameter("resource.availabilityStatus", availability, null));
        parameters.add(parameter("resource.queryStatus", query, null));
        parameters.add(parameter("resource.applicabilityStatus", applicability, null));
        if (!"SUCCEEDED".equals(execution))
            return blocked("RESOURCE_EXECUTION_NOT_SUCCEEDED", steps, parameters, evidence);
        if (!"AVAILABLE".equals(availability))
            return blocked("RESOURCE_NOT_AVAILABLE", steps, parameters, evidence);
        if ("NO_RECORD".equals(query))
            return blocked("NO_RECORD_IS_NOT_FREQUENCY_ZERO", steps, parameters, evidence);
        if (!"HIT".equals(query) || evidence.path("queryOutcome").path("recordCount").asInt(-1) != 1)
            return blocked("QUERY_NOT_SINGLE_EXACT_HIT", steps, parameters, evidence);
        if (!"APPLICABLE".equals(applicability))
            return blocked("RESOURCE_NOT_APPLICABLE", steps, parameters, evidence);
        steps.add(step("RESOURCE_FOUR_AXES_READY", "TRUE", evidence));
        String calibration = bindings.profileId() + "@" + bindings.profileVersion();
        Reading profileId = reading(evidence, "gnomad.inputProfileId", calibration);
        Reading profileVersion = reading(evidence, "gnomad.inputProfileVersion", calibration);
        Reading profileHash = reading(evidence, "gnomad.inputProfileSha256", calibration);
        for (Reading profileBinding : List.of(profileId, profileVersion, profileHash))
            if (profileBinding.reason() != null) return blocked(profileBinding.reason(), steps, parameters, evidence);
        if (!bindings.profileId().equals(textMeasurement(profileId.node(), "profile-id"))
                || !bindings.profileVersion().equals(textMeasurement(profileVersion.node(), "profile-version"))
                || !bindings.profileHash().equals(textMeasurement(profileHash.node(), "sha256")))
            return blocked("PROFILE_EVIDENCE_BINDING_MISMATCH", steps, parameters, evidence);
        Reading exact = reading(evidence, bindings.gates().path("exactAlleleMatchMetricId").asText(), calibration);
        if (exact.reason() != null) return blocked(exact.reason(), steps, parameters, evidence);
        if (!booleanValue(exact.node(), "boolean"))
            return blocked("EXACT_ALLELE_NOT_CONFIRMED", steps, parameters, evidence);
        Reading filter = reading(evidence, bindings.gates().path("filterMetricId").asText(), calibration);
        if (filter.reason() != null) return blocked(filter.reason(), steps, parameters, evidence);
        if (!"PASS".equals(textMeasurement(filter.node(), "vcf-filter")))
            return blocked("VARIANT_FILTER_NOT_PASS", steps, parameters, evidence);
        Reading recordReady = reading(evidence, "gnomad.recordInputReadinessStatus", calibration);
        Reading datasetReady = reading(evidence, "gnomad.generalCriterionDatasetReadinessStatus", calibration);
        Reading evaluationEnabled = reading(evidence, "gnomad.criterionEvaluationEnabled", calibration);
        for (Reading readiness : List.of(recordReady, datasetReady, evaluationEnabled))
            if (readiness.reason() != null) return blocked(readiness.reason(), steps, parameters, evidence);
        boolean recordComplete = "READY".equals(textMeasurement(recordReady.node(), "status"));
        if ((!recordComplete && (!conditional || conditionalPartialReadinessIssue(bindings, evidence, calibration,
                textMeasurement(recordReady.node(), "status")) != null))
                || !(localExomes(bindings) ? "READY_FOR_LOCAL_EXOMES_BA1" : "READY_FOR_HIT_BASED_BA1_BS1").equals(
                textMeasurement(datasetReady.node(), "status"))
                || !booleanValue(evaluationEnabled.node(), "boolean"))
            return blocked("POPULATION_INPUT_NOT_READY", steps, parameters, evidence);
        steps.add(step(recordComplete ? "EXACT_PASS_AND_READINESS_CONFIRMED" : "BA1_CONDITIONAL_PARTIAL_INPUTS_IDENTIFIED", "TRUE", evidence));
        if (conditional) {
            String qualityIssue = conditionalSharedQualityIssue(bindings, evidence, calibration, parameters);
            return qualityIssue == null ? new Input(null, null, null, steps, parameters, evidence)
                    : blocked(qualityIssue, steps, parameters, evidence);
        }
        return frequencyInput(bindings, context, evidence, steps, parameters);
    }

    private Input frequencyInput(Bindings bindings, JsonNode context, ObjectNode evidence,
                                 List<ObjectNode> steps, List<ObjectNode> parameters) {
        String calibration = bindings.profileId() + "@" + bindings.profileVersion();
        JsonNode selectedPolicy = bindings.selectedPolicy();
        Reading frequencyReading = reading(evidence, bindings.gates().path("frequencyMetricId").asText(), calibration);
        if (frequencyReading.reason() != null) return blocked(frequencyReading.reason(), steps, parameters, evidence);
        BigDecimal frequency = decimalMeasurement(frequencyReading.node(), "fraction");
        if (frequency == null || frequency.signum() < 0 || frequency.compareTo(BigDecimal.ONE) > 0)
            return blocked("FREQUENCY_INPUT_INVALID", steps, parameters, evidence);
        Reading ancestryReading = reading(evidence,
                bindings.gates().path("frequencyAncestryMetricId").asText(), calibration);
        if (ancestryReading.reason() != null) return blocked(ancestryReading.reason(), steps, parameters, evidence);
        String ancestry = textMeasurement(ancestryReading.node(), "genetic-ancestry-group");
        if (!bindings.ancestries().contains(ancestry))
            return blocked("FAF95_ANCESTRY_UNSUPPORTED", steps, parameters, evidence);
        String anMetric = bindings.gates().path("selectedAncestryAlleleNumberMetricTemplate")
                .asText().replace("{ancestry}", ancestry);
        Reading anReading = reading(evidence, anMetric, calibration);
        if (anReading.reason() != null) return blocked(anReading.reason(), steps, parameters, evidence);
        BigInteger selectedAn = integerMeasurement(anReading.node(), "alleles");
        if (selectedAn == null || selectedAn.signum() < 0)
            return blocked("SELECTED_ANCESTRY_AN_INVALID", steps, parameters, evidence);
        String acMetric = bindings.gates().path("selectedAncestryAlleleCountMetricTemplate")
                .asText().replace("{ancestry}", ancestry);
        Reading acReading = reading(evidence, acMetric, calibration);
        if (acReading.reason() != null) return blocked(acReading.reason(), steps, parameters, evidence);
        BigInteger selectedAc = integerMeasurement(acReading.node(), "alleles");
        if (selectedAc == null || selectedAc.signum() < 0 || selectedAc.compareTo(selectedAn) > 0)
            return blocked("SELECTED_ANCESTRY_COUNT_INVALID", steps, parameters, evidence);
        parameters.add(parameter("frequency.metricId", frequencyReading.node().path("metricId").asText(), null));
        parameters.add(parameter("frequency.raw", frequencyReading.node().path("rawValue").asText(), "fraction"));
        parameters.add(parameter("frequency.ancestry", ancestry, "genetic-ancestry-group"));
        parameters.add(parameter("frequency.selectedAncestryAc", acReading.node().path("rawValue").asText(), "alleles"));
        parameters.add(parameter("frequency.selectedAncestryAn", anReading.node().path("rawValue").asText(), "alleles"));
        Qc qc = localExomes(bindings) ? new Qc("NOT_ASSESSED_LOCAL_EXOMES", "UNKNOWN")
                : discordanceQc(bindings, evidence, calibration);
        if (parameters.stream().noneMatch(value -> "frequency.discordanceQc".equals(value.path("name").asText())))
            parameters.add(parameter("frequency.discordanceQc", qc.status(), "qc-status"));
        steps.add(step("FREQUENCY_DISCORDANCE_QC_ONLY", qc.outcome(), evidence));
        if (bindings.profile().has("ba1CatalogPolicy")) {
            JsonNode policy = bindings.profile().path("ba1CatalogPolicy");
            parameters.add(parameter("frequency.afCountToleranceBasis", "HALF_BINARY32_ULP_PLUS_HALF_REPORTED_DECIMAL_ULP_QC_ONLY", null));
            String issue = selectedPolicyQualityIssue(policy, evidence, calibration, ancestry, frequency, selectedAn, selectedAc, qc, localExomes(bindings));
            if (issue != null) return blocked(issue, steps, parameters, evidence);
            for (String subtype : localExomes(bindings) ? List.<String>of() : List.of("exomes", "genomes")) {
                Reading presence = reading(evidence, "gnomad.joint." + subtype + ".filtersPresence", calibration);
                String flag = presence.reason() == null ? textMeasurement(presence.node(), "vcf-filter-list") : null;
                if ("MISSING".equals(flag) && Ba1SubtypeFilterSupplement.verifiedPass(mapper,
                        bindings.profile().path("ba1SubtypeFilterSupplement"), subtype, evidence.path("queryAllele"),
                        bindings.profile().path("ba1JointRawSha256").asText())) {
                    parameters.add(parameter("ba1.nativeFilter." + subtype, "VERIFIED_NATIVE_EXACT_ALLELE_PASS", null));
                    continue;
                }
                if (flag == null || "MISSING".equals(flag) || flag.isBlank())
                    return blocked("BA1_" + subtype.toUpperCase(java.util.Locale.ROOT) + "_FILTER_INPUT_MISSING", steps, parameters, evidence);
                if (!".".equals(flag) && !"PASS".equals(flag))
                    return blocked("POPULATION_FREQUENCY_SUBTYPE_FILTER_REVIEW_REQUIRED", steps, parameters, evidence);
            }
        }
        if (selectedPolicy != null) {
            parameters.add(parameter("frequency.afCountPrecision", selectedPolicy.at("/engineeringConditions/afCountPrecision").asText(), null));
            parameters.add(parameter("frequency.afCountToleranceBasis", "HALF_BINARY32_ULP_PLUS_HALF_REPORTED_DECIMAL_ULP_QC_ONLY", null));
            String issue = selectedPolicyQualityIssue(selectedPolicy, evidence, calibration, ancestry, frequency, selectedAn, selectedAc, qc, false);
            if (issue != null) return blocked(issue, steps, parameters, evidence);
        }
        if (bindings.profile().has("diseaseFrequencyPolicy")) {
            if (selectedAn.signum() <= 0) return blocked("SELECTED_ANCESTRY_AN_INVALID", steps, parameters, evidence);
            Reading selectedFaf = reading(evidence, "gnomad.joint.ancestry." + ancestry + ".faf95", calibration);
            BigDecimal associated = selectedFaf.reason() == null ? decimalMeasurement(selectedFaf.node(), "fraction") : null;
            if (associated == null || associated.compareTo(frequency) != 0)
                return blocked("HNF1A_FAF_ANCESTRY_MISMATCH", steps, parameters, evidence);
            if (!"NOT_DISCORDANT".equals(qc.status())) return blocked("DISCORDANT".equals(qc.status())
                    ? "HNF1A_FREQUENCY_DISCORDANT_REVIEW_REQUIRED" : "HNF1A_FREQUENCY_DISCORDANCE_UNKNOWN", steps, parameters, evidence);
            for (String subtype : List.of("exomes", "genomes")) {
                Reading flag = reading(evidence, "gnomad.joint." + subtype + ".filters", calibration);
                // The VCF uses '.' for an empty list; the parser omits that value.
                if (flag.reason() == null && !"PASS".equals(textMeasurement(flag.node(), "vcf-filter-list")))
                    return blocked("HNF1A_SUBTYPE_FILTER_REVIEW_REQUIRED", steps, parameters, evidence);
            }
        }
        return new Input(frequency, selectedAn, null, steps, parameters, evidence);
    }

    private String selectedPolicyQualityIssue(JsonNode policy, ObjectNode evidence, String calibration,
                                              String ancestry, BigDecimal frequency, BigInteger selectedAn,
                                              BigInteger selectedAc, Qc qc, boolean localExomes) {
        if (selectedAn.signum() <= 0) return "SELECTED_ANCESTRY_AN_INVALID";
        String prefix = localExomes ? "gnomad.exomes." : "gnomad.joint.";
        Reading selectedFaf = reading(evidence, prefix + "ancestry." + ancestry + ".faf95", calibration);
        BigDecimal associated = selectedFaf.reason() == null ? decimalMeasurement(selectedFaf.node(), "fraction") : null;
        if (associated == null || associated.compareTo(frequency) != 0) return "POPULATION_FREQUENCY_FAF_ANCESTRY_MISMATCH";
        Reading selectedAf = reading(evidence, prefix + "ancestry." + ancestry + ".af", calibration);
        BigDecimal rawAf = selectedAf.reason() == null ? decimalMeasurement(selectedAf.node(), "fraction") : null;
        if (rawAf == null || rawAf.signum() < 0 || rawAf.compareTo(BigDecimal.ONE) > 0 || associated.compareTo(rawAf) > 0)
            return "POPULATION_FREQUENCY_FAF_EXCEEDS_AF_OR_AF_INVALID";
        if (selectedAc.signum() == 0 && (rawAf.signum() > 0 || associated.signum() > 0)
                || selectedAc.signum() > 0 && rawAf.signum() == 0)
            return "POPULATION_FREQUENCY_COUNT_AF_CONFLICT";
        // VCF Float is IEEE754 binary32 and its printed decimal can be rounded too.
        // This tolerance applies only to count/AF consistency QC, never to the FAF95
        // threshold comparator. Use the upper decimal interval endpoint for a safe
        // ULP bound at powers of two, and represent that binary ULP exactly as decimal.
        BigDecimal reportedAf = new BigDecimal(selectedAf.node().path("rawValue").asText());
        BigDecimal denominator = new BigDecimal(selectedAn);
        BigDecimal residual = reportedAf.multiply(denominator).subtract(new BigDecimal(selectedAc)).abs();
        BigDecimal decimalHalfUlp = reportedAf.ulp().divide(BigDecimal.valueOf(2));
        float upper = reportedAf.add(decimalHalfUlp).floatValue();
        BigDecimal binaryHalfUlp = new BigDecimal((double) Math.ulp(upper)).divide(BigDecimal.valueOf(2));
        BigDecimal tolerance = decimalHalfUlp.add(binaryHalfUlp).multiply(denominator);
        if (residual.compareTo(tolerance) > 0) return "POPULATION_FREQUENCY_COUNT_AF_CONFLICT";
        if (!localExomes && !"NOT_DISCORDANT".equals(qc.status())) return "DISCORDANT".equals(qc.status())
                ? "POPULATION_FREQUENCY_DISCORDANT_REVIEW_REQUIRED" : "POPULATION_FREQUENCY_DISCORDANCE_UNKNOWN";
        for (String subtype : localExomes ? List.<String>of() : List.of("exomes", "genomes")) {
            Reading flag = reading(evidence, "gnomad.joint." + subtype + ".filters", calibration);
            if (flag.reason() != null && !"REQUIRED_MEASUREMENT_MISSING".equals(flag.reason())) return flag.reason();
            if (flag.reason() == null && !"PASS".equals(textMeasurement(flag.node(), "vcf-filter-list")))
                return "POPULATION_FREQUENCY_SUBTYPE_FILTER_REVIEW_REQUIRED";
        }
        JsonNode match = null;
        for (JsonNode applicability : policy.at("/engineeringConditions/ancestryApplicability")) {
            if (ancestry.equals(applicability.path("ancestry").asText())) {
                if (match != null) return "POPULATION_FREQUENCY_COHORT_APPLICABILITY_AMBIGUOUS";
                match = applicability;
            }
        }
        if (match == null || !"REVIEWED_APPLICABLE".equals(match.path("status").asText())
                || match.path("basis").asText().isBlank() || textSet(match.path("authoritySourceIds")).isEmpty())
            return "POPULATION_FREQUENCY_COHORT_APPLICABILITY_REQUIRED";
        Set<String> authorities = new HashSet<>();
        for (JsonNode source : policy.path("sources")) authorities.add(source.path("authoritySourceId").asText());
        if (!authorities.containsAll(textSet(match.path("authoritySourceIds")))) return "POPULATION_FREQUENCY_COHORT_SOURCE_UNBOUND";
        return null;
    }

    /** The disease-frequency marker is distinct from the legacy PM2-only declaration. */
    public static String hnf1aContextIssue(JsonNode context) {
        if (!"HGNC:11621".equals(context.at("/gene/hgncId").asText())
                || !"MONDO:0015967".equals(context.at("/disease/mondoId").asText())
                || !"AUTOSOMAL_DOMINANT".equals(context.path("moi").asText())) return "HNF1A_FREQUENCY_POLICY_NOT_APPLICABLE";
        String selection = context.at("/transcript/selectionPolicy").asText();
        if (selection.isBlank() || org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.PM2_CONTEXT_SELECTION.equals(selection)
                || "PM2_CONTEXT_ONLY".equals(selection)) return "HNF1A_FREQUENCY_DECLARATION_REQUIRED";
        JsonNode allele = context.at("/variant/evidenceQueryAllele");
        if (!"GRCh38".equals(allele.path("assembly").asText())) return "HNF1A_FREQUENCY_ASSEMBLY_UNSUPPORTED";
        if (!"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                || !"MATCH".equals(context.at("/variant/normalization/refValidation").asText())
                || !"SNV".equals(context.at("/variant/variantType").asText())
                || !"12".equals(allele.path("contig").asText())
                || !allele.path("reference").asText().matches("[ACGT]")
                || !allele.path("alternate").asText().matches("[ACGT]")
                || allele.path("reference").equals(allele.path("alternate"))) return "HNF1A_FREQUENCY_NATIVE_SNV_REQUIRED";
        return null;
    }
    private ExceptionResult ba1Exception(JsonNode rule, JsonNode context) {
        JsonNode allele = context.path("variant").path("evidenceQueryAllele");
        if (!completeAllele(allele)) return new ExceptionResult("UNKNOWN", "BA1_EXCEPTION_INPUT_INVALID");
        JsonNode policy = rule.path("exceptionPolicy");
        JsonNode entries = policy.path("entries");
        if (!"READY".equals(policy.path("snapshotStatus").asText()) || !entries.isArray()
                || (!"ba1-exception-snapshot-v2".equals(policy.path("schemaVersion").asText()) && entries.size() != 9)
                || policy.path("entryCount").asInt(-1) != entries.size()
                || !policy.path("entrySetSha256").asText().equals(canonicalizer.canonicalSha256(entries)))
            return new ExceptionResult("UNKNOWN", "BA1_EXCEPTION_SNAPSHOT_UNKNOWN");
        if ("ba1-exception-snapshot-v2".equals(policy.path("schemaVersion").asText())
                && (policy.path("snapshotDate").asText().isBlank()
                || !policy.path("sourceSnapshotSha256").asText().matches("[0-9a-f]{64}")
                || !policy.path("sourceUrl").asText().startsWith("https://")
                || !"EXACT_ASSEMBLY_CONTIG_POSITION_REF_ALT".equals(policy.path("matchMode").asText())))
            return new ExceptionResult("UNKNOWN", "BA1_EXCEPTION_SNAPSHOT_UNKNOWN");
        if ("ba1-exception-snapshot-v2".equals(policy.path("schemaVersion").asText())) {
            Set<String> sourceIds = textSet(policy.path("sourceEntryIds"));
            if (!policy.path("sourceEntryIds").isArray() || sourceIds.isEmpty()
                    || sourceIds.size() != policy.path("sourceEntryCount").asInt(-1)
                    || sourceIds.size() != policy.path("sourceEntryIds").size() || sourceIds.size() != entries.size()
                    || !policy.path("sourceEntryIdsSha256").asText().equals(canonicalizer.canonicalSha256(policy.path("sourceEntryIds")))
                    || !"GRCh38".equals(policy.path("evidenceAssembly").asText())
                    || !"REVIEWED_COMPLETE".equals(policy.path("mappingReviewStatus").asText())
                    || policy.path("mappingReviewId").asText().isBlank())
                return new ExceptionResult("UNKNOWN", "BA1_EXCEPTION_MAPPING_INCOMPLETE");
            Set<String> mappedIds = new HashSet<>();
            Set<String> mappedAlleles = new HashSet<>();
            for (JsonNode entry : entries) {
                if (!"GRCh38".equals(entry.path("assembly").asText())
                        || !sourceIds.contains(entry.path("caid").asText()) || !mappedIds.add(entry.path("caid").asText())
                        || !mappedAlleles.add(entry.path("assembly").asText() + ":" + entry.path("contig").asText() + ":"
                        + entry.path("position").asText() + ":" + entry.path("reference").asText() + ":" + entry.path("alternate").asText()))
                    return new ExceptionResult("UNKNOWN", "BA1_EXCEPTION_MAPPING_INCOMPLETE");
            }
        }
        int matches = 0;
        for (JsonNode entry : entries) {
            if (!completeAllele(entry)) return new ExceptionResult("UNKNOWN", "BA1_EXCEPTION_SNAPSHOT_UNKNOWN");
            if (sameAllele(allele, entry)) matches++;
        }
        if (matches == 0) return new ExceptionResult("NOT_HIT", null);
        if (matches == 1) return new ExceptionResult("HIT", "BA1_EXCEPTION_HIT");
        return new ExceptionResult("AMBIGUOUS", "BA1_EXCEPTION_MATCH_AMBIGUOUS");
    }
    private ThresholdResult bs1Threshold(JsonNode rule, JsonNode context) {
        String gene = textOr(context.path("gene").path("hgncId"), null);
        String mondo = textOr(context.path("disease").path("mondoId"), null);
        String moi = textOr(context.path("moi"), null);
        if (gene == null || mondo == null || moi == null)
            return ThresholdResult.blocked("BS1_CONTEXT_INCOMPLETE");
        JsonNode snapshot = rule.path("thresholdSnapshot");
        ObjectNode projection = snapshot.isObject() ? ((ObjectNode) snapshot).deepCopy() : null;
        if (projection == null || !snapshot.path("records").isArray()
                || textOr(snapshot.path("snapshotId"), null) == null
                || textOr(snapshot.path("version"), null) == null)
            return ThresholdResult.blocked("BS1_THRESHOLD_SNAPSHOT_INVALID");
        projection.remove("sha256");
        if (!snapshot.path("sha256").asText().equals(canonicalizer.canonicalSha256(projection)))
            return ThresholdResult.blocked("BS1_THRESHOLD_SNAPSHOT_INVALID");
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode record : snapshot.path("records"))
            if (gene.equals(record.path("geneId").asText())
                    && mondo.equals(record.path("mondoId").asText())
                    && moi.equals(record.path("moi").asText())) matches.add(record);
        if (matches.isEmpty()) return ThresholdResult.blocked("BS1_THRESHOLD_NOT_FOUND");
        if (matches.size() != 1) return ThresholdResult.blocked("BS1_THRESHOLD_MATCH_AMBIGUOUS");
        JsonNode record = matches.get(0);
        BigDecimal threshold = decimal(record.path("thresholdRaw"));
        Set<String> authorities = textSet(record.path("authoritySourceIds"));
        if (textOr(record.path("thresholdId"), null) == null
                || textOr(record.path("version"), null) == null
                || !rule.path("metricId").asText().equals(record.path("metricId").asText())
                || !Set.of(GT, "GREATER_THAN_OR_EQUAL").contains(record.path("comparator").asText())
                || threshold == null || threshold.signum() < 0 || threshold.compareTo(BigDecimal.ONE) > 0
                || authorities.isEmpty()) return ThresholdResult.blocked("BS1_THRESHOLD_RECORD_INVALID");
        return new ThresholdResult(true, null, record.path("thresholdId").asText(),
                record.path("version").asText(), record.path("comparator").asText(),
                record.path("thresholdRaw").asText(), threshold, authorities);
    }
    private ObjectNode output(Bindings bindings, Criterion criterion, ObjectNode evidence,
                              String selectionId, State state) {
        return output(bindings, criterion, evidence, selectionId, state, false);
    }
    private ObjectNode output(Bindings bindings, Criterion criterion, ObjectNode evidence,
                              String selectionId, State state, boolean alleleScoped) {
        ObjectNode result = mapper.createObjectNode();
        result.put("schemaVersion", alleleScoped ? "ba1-allele-evaluation-v1" : "criterion-evaluation-v2");
        result.put(alleleScoped ? "alleleId" : "contextId", evidence.path("contextId").asText());
        if (alleleScoped) result.put("scope", "CANDIDATE_ALLELE_GENERAL_POPULATION_EVIDENCE");
        result.put("canonicalizationProfile", C14N);
        result.put("criterionId", criterion.id());
        ObjectNode ruleBinding = result.putObject("ruleBinding");
        ruleBinding.put("ruleRegistryId", bindings.registryId());
        ruleBinding.put("ruleRegistrySha256", bindings.registryHash());
        ruleBinding.put("currentGeneralDesignation", criterion.designation());
        TreeSet<String> authorities = new TreeSet<>(criterion.authorities());
        ArrayNode authorityValues = ruleBinding.putArray("authoritySourceIds");
        authorities.forEach(authorityValues::add);
        if (!alleleScoped) ruleBinding.put("cspecSelectionId", selectionId);
        ObjectNode applicability = result.putObject("criterionApplicability");
        applicability.put("status", state.applicability());
        if (!"APPLICABLE".equals(state.applicability())) applicability.put("reasonCode", state.reason());
        ObjectNode evaluation = result.putObject("evaluationResult");
        evaluation.put("status", state.result());
        if ("NOT_EVALUATED".equals(state.result())) evaluation.put("reasonCode", state.reason());
        result.put("baseStrength", criterion.strength());
        result.put("appliedStrength", "MET".equals(state.result()) ? criterion.strength() : "NONE");
        result.putArray("evidenceIds").add(evidence.path("evidenceId").asText());
        ObjectNode trace = result.putObject("deterministicTrace");
        trace.put("executionStatus", state.completed() ? "COMPLETED" : "NOT_RUN");
        trace.put("evaluatorId", EVALUATOR_ID);
        trace.put("evaluatorVersion", conditionalCatalog(bindings) ? BA1_CONDITIONAL_EVALUATOR_VERSION : localExomes(bindings) ? "1.0.0-local-exomes-ba1" : bindings.profile().has("ba1CatalogPolicy") ? "2.1.0-catalog-ba1-native-qc" : bindings.selectedPolicy() != null ? "1.2.0-disease-policy"
                : bindings.profile().has("diseaseFrequencyPolicy") ? "1.1.0-hnf1a" : EVALUATOR_VERSION);
        trace.putArray("inputEvidenceIds").add(evidence.path("evidenceId").asText());
        trace.set("parameters", array(sortedParameters(state.parameters())));
        trace.set("steps", array(state.steps()));
        if (state.completed()) {
            trace.put("algorithmId", conditionalCatalog(bindings) ? "GNOMAD_JOINT_BA1_CONDITIONAL_V1"
                    : (localExomes(bindings) ? "GNOMAD_LOCAL_EXOMES_" : "GNOMAD_JOINT_") + criterion.designation() + "_V1");
            trace.put("algorithmVersion", conditionalCatalog(bindings) ? BA1_CONDITIONAL_EVALUATOR_VERSION : localExomes(bindings) ? "1.0.0-local-exomes-ba1" : bindings.profile().has("ba1CatalogPolicy") ? "2.1.0-catalog-ba1-native-qc" : bindings.selectedPolicy() != null ? "1.2.0-disease-policy"
                    : bindings.profile().has("diseaseFrequencyPolicy") ? "1.1.0-hnf1a" : "1.0.0");
            trace.put("traceSha256", canonicalizer.canonicalSha256(trace));
        } else trace.put("reasonCode", state.reason());
        result.set("conflict", summary("NONE", "conflictIds"));
        ObjectNode review = summary("NOT_REQUIRED", "reviewRecordIds");
        review.put("decision", "NONE");
        result.set("review", review);
        if (localExomes(bindings)) {
            result.set("dataScope", bindings.profile().path("localDataPolicy").deepCopy());
            ruleBinding.put("dataPolicyId", "BA1_LOCAL_EXOMES_V1").put("dataPolicyVersion", "1.0.0");
        }
        ObjectNode identity = result.deepCopy();
        identity.remove(List.of("evaluationId", "conflict", "review"));
        result.put("evaluationId", (alleleScoped ? "ba1-allele-evaluation-sha256-" : "evaluation-sha256-") + canonicalizer.canonicalSha256(identity));
        return result;
    }

    private boolean localExomes(Bindings bindings) {
        return GnomadPopulationInputProfile.LOCAL_BA1_PROFILE_ID.equals(bindings.profileId());
    }
    private Reading reading(ObjectNode evidence, String metricId, String calibration) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode value : evidence.path("measurements"))
            if (metricId.equals(value.path("metricId").asText())) found.add(value);
        if (found.isEmpty()) return new Reading(null, "REQUIRED_MEASUREMENT_MISSING");
        if (found.size() != 1) return new Reading(null, "REQUIRED_MEASUREMENT_AMBIGUOUS");
        if (!calibration.equals(found.get(0).path("calibrationProfileId").asText()))
            return new Reading(null, "PROFILE_CALIBRATION_MISMATCH");
        return new Reading(found.get(0), null);
    }
    private Qc discordanceQc(Bindings bindings, ObjectNode evidence, String calibration) {
        JsonNode qc = bindings.resource().path("inputSemantics").path("frequencyConflictQc");
        if (!"QC_ONLY".equals(qc.path("status").asText())) return new Qc("UNKNOWN", "UNKNOWN");
        JsonNode field = find(bindings.profile().path("fieldContract").path("fields"),
                "infoId", qc.path("sourceField").asText());
        Reading reading = reading(evidence, field.path("metricId").asText(), calibration);
        BigDecimal value = reading.reason() == null ? decimalMeasurement(reading.node(), "p-value") : null;
        BigDecimal threshold = decimal(qc.path("thresholdRaw"));
        if (value == null || threshold == null) return new Qc("UNKNOWN", "UNKNOWN");
        boolean discordant = value.compareTo(threshold) < 0;
        return new Qc(discordant ? "DISCORDANT" : "NOT_DISCORDANT", discordant ? "TRUE" : "FALSE");
    }
    private Input blocked(String reason, List<ObjectNode> steps, List<ObjectNode> parameters,
                          ObjectNode evidence) {
        List<ObjectNode> blockedSteps = copy(steps);
        blockedSteps.add(step(reason, "FALSE", evidence));
        return new Input(null, null, reason, blockedSteps, parameters, evidence);
    }
    private State notEvaluated(String applicability, String reason, List<ObjectNode> steps,
                               List<ObjectNode> parameters, ObjectNode evidence) {
        List<ObjectNode> values = copy(steps);
        if (values.isEmpty() || !reason.equals(values.get(values.size() - 1).path("stepCode").asText()))
            values.add(step(reason, "FALSE", evidence));
        return new State(applicability, "NOT_EVALUATED", reason, false, values, parameters);
    }
    private State completed(boolean met, List<ObjectNode> steps, List<ObjectNode> parameters) {
        return new State("APPLICABLE", met ? "MET" : "NOT_MET", null, true,
                steps, parameters);
    }
    private List<ObjectNode> baseParameters(Bindings bindings, ObjectNode evidence) {
        return new ArrayList<>(List.of(
                parameter("profile.id", bindings.profileId(), null),
                parameter("profile.version", bindings.profileVersion(), null),
                parameter("profile.sha256", bindings.profileHash(), "sha256"),
                parameter("resource.id", evidence.path("source").path("sourceId").asText(), null),
                parameter("resource.version", evidence.path("source").path("resourceVersion").asText(), null),
                parameter("resource.sha256", evidence.path("source").path("resourceSha256").asText(), "sha256")));
    }
    private BigDecimal decimalMeasurement(JsonNode node, String unit) {
        if (!unit.equals(node.path("unit").asText())) return null;
        BigDecimal raw = decimal(node.path("rawValue"));
        BigDecimal normalized = decimal(node.path("normalizedValue"));
        return raw != null && normalized != null && raw.compareTo(normalized) == 0 ? normalized : null;
    }
    private BigInteger integerMeasurement(JsonNode node, String unit) {
        if (!unit.equals(node.path("unit").asText())) return null;
        BigInteger raw = integer(node.path("rawValue"));
        BigInteger normalized = integer(node.path("normalizedValue"));
        return raw != null && raw.equals(normalized) ? normalized : null;
    }
    private boolean booleanValue(JsonNode node, String unit) {
        if (!unit.equals(node.path("unit").asText())) return false;
        return "true".equals(node.path("rawValue").asText())
                && "true".equals(node.path("normalizedValue").asText());
    }
    private Boolean booleanMeasurement(JsonNode node) {
        if (!"boolean".equals(node.path("unit").asText())
                || !node.path("rawValue").equals(node.path("normalizedValue"))) {
            return null;
        }
        if (node.path("rawValue").isBoolean()) return node.path("rawValue").booleanValue();
        if (!node.path("rawValue").isTextual()) return null;
        String value = node.path("rawValue").asText();
        if (!Set.of("true", "false").contains(value)) return null;
        return Boolean.valueOf(value);
    }
    private String textMeasurement(JsonNode node, String unit) {
        JsonNode raw = node.path("rawValue");
        JsonNode normalized = node.path("normalizedValue");
        return unit.equals(node.path("unit").asText()) && raw.isTextual() && raw.equals(normalized)
                ? normalized.asText() : null;
    }
    private static BigDecimal decimal(JsonNode value) {
        if (!value.isTextual()) return null;
        try { return new BigDecimal(value.asText()); }
        catch (NumberFormatException ignored) { return null; }
    }
    private static BigInteger integer(JsonNode value) {
        if (!value.isTextual() && !value.isIntegralNumber()) return null;
        try { return new BigInteger(value.asText()); }
        catch (NumberFormatException ignored) { return null; }
    }
    private static JsonNode find(JsonNode values, String field, String expected) {
        if (values.isArray()) for (JsonNode value : values)
            if (expected.equals(value.path(field).asText())) return value;
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
    private static Set<String> textSet(JsonNode values) {
        Set<String> result = new LinkedHashSet<>();
        if (values.isArray()) for (JsonNode value : values)
            if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText());
        return result;
    }
    private static String textOr(JsonNode value, String fallback) {
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : fallback;
    }
    private static boolean completeAllele(JsonNode allele) {
        return allele.isObject() && allele.path("position").isIntegralNumber()
                && !allele.path("assembly").asText().isBlank() && !allele.path("contig").asText().isBlank()
                && !allele.path("reference").asText().isBlank() && !allele.path("alternate").asText().isBlank();
    }
    private static boolean sameAllele(JsonNode a, JsonNode b) {
        return a.path("assembly").equals(b.path("assembly")) && a.path("contig").equals(b.path("contig"))
                && a.path("position").equals(b.path("position")) && a.path("reference").equals(b.path("reference"))
                && a.path("alternate").equals(b.path("alternate"));
    }
    private ObjectNode parameter(String name, String value, String unit) {
        ObjectNode result = mapper.createObjectNode().put("name", name).put("value", value);
        if (unit != null) result.put("unit", unit);
        return result;
    }
    private ObjectNode step(String code, String outcome, ObjectNode evidence) {
        ObjectNode result = mapper.createObjectNode().put("stepCode", code).put("outcome", outcome);
        result.putArray("inputEvidenceIds").add(evidence.path("evidenceId").asText());
        return result;
    }
    private ObjectNode step(String code, String outcome, List<ObjectNode> evidence) {
        ObjectNode result = mapper.createObjectNode().put("stepCode", code).put("outcome", outcome);
        ArrayNode ids = result.putArray("inputEvidenceIds");
        Set<String> unique = new LinkedHashSet<>();
        for (ObjectNode value : evidence) {
            String id = value.path("evidenceId").asText();
            if (!id.isBlank() && unique.add(id)) ids.add(id);
        }
        return result;
    }
    private ArrayNode array(List<ObjectNode> values) {
        ArrayNode result = mapper.createArrayNode();
        values.forEach(value -> result.add(value.deepCopy()));
        return result;
    }
    private ObjectNode summary(String status, String idsField) {
        ObjectNode result = mapper.createObjectNode().put("status", status);
        result.putArray(idsField);
        return result;
    }
    private static List<ObjectNode> copy(List<ObjectNode> values) {
        List<ObjectNode> result = new ArrayList<>();
        values.forEach(value -> result.add(value.deepCopy()));
        return result;
    }
    private static List<ObjectNode> sortedParameters(List<ObjectNode> values) {
        List<ObjectNode> result = copy(values);
        result.sort(Comparator.comparing((ObjectNode value) -> value.path("name").asText())
                .thenComparing(value -> value.path("unit").asText())
                .thenComparing(value -> value.path("value").asText()));
        return result;
    }
    private record Bindings(ObjectNode profile, String profileHash, String profileId, String profileVersion,
                            String registryId, String registryHash, JsonNode resource, JsonNode gates,
                            Set<String> ancestries, Criterion ba1, Criterion bs1, String issue, JsonNode selectedPolicy) {}
    private record Pm2Bindings(ObjectNode profile, String profileHash, String profileId,
                               String profileVersion, String registryId, String registryHash,
                               JsonNode jointResource, JsonNode exomeResource, JsonNode genomeResource,
                               Criterion pm2, String issue) {}
    private record Criterion(String id, String designation, String strength, JsonNode rule,
                             Set<String> authorities) {}
    private record AxesValue(String execution, String availability, String query,
                             int recordCount, String applicability) {}
    private record DatasetResult(String status, boolean sufficient, String rawAn, String error) {
        private static DatasetResult error(String reason) {
            return new DatasetResult("INVALID", false, null, reason);
        }
    }
    private record MapFlagResult(Map<String, Boolean> values, String error) {
        private static MapFlagResult error(String reason) {
            return new MapFlagResult(Map.of(), reason);
        }
    }
    private record PolicyResult(boolean ready, String reason, JsonNode record,
                                String snapshotId, String snapshotVersion, String snapshotHash) {
        private static PolicyResult blocked(String reason) {
            return new PolicyResult(false, reason, null, null, null, null);
        }
    }
    private record Reading(JsonNode node, String reason) {}
    private record Qc(String status, String outcome) {}
    private record ExceptionResult(String outcome, String reason) {}
    private record ThresholdResult(boolean ready, String reason, String id, String version,
                                   String comparator, String raw, BigDecimal value, Set<String> authorities) {
        private static ThresholdResult blocked(String reason) {
            return new ThresholdResult(false, reason, null, null, null, null, null, Set.of());
        }
    }
    private record State(String applicability, String result, String reason, boolean completed,
                         List<ObjectNode> steps, List<ObjectNode> parameters) {}
    private record Input(BigDecimal frequency, BigInteger selectedAn, String reason,
                         List<ObjectNode> steps, List<ObjectNode> parameters, ObjectNode evidence) {
        private State blockedState() {
            return reason == null ? null : new State("UNKNOWN", "NOT_EVALUATED", reason, false,
                    steps, parameters);
        }
    }
}
