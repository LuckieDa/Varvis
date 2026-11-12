package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Immutable server-pinned knowledge. No online lookup or client-supplied adoption. */
public final class Bs1KnowledgeCatalog {
    public static final String ACTIVATION = "LOCAL_EXOMES_KNOWLEDGE_V1";
    public static final String JOINT_ACTIVATION = "JOINT_SEX_KNOWLEDGE_V1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JsonNode document;
    private final String rawSha;
    private final List<JsonNode> policies;
    private final Map<String, JsonNode> backgrounds = new LinkedHashMap<>();
    private final Map<String, JsonNode> parameterSupplies = new LinkedHashMap<>();

    private Bs1KnowledgeCatalog(JsonNode document, String rawSha) {
        this.document = document.deepCopy(); this.rawSha = rawSha;
        var sourceIds = new HashSet<String>();
        for (var source : document.path("sources"))
            if (!sourceIds.add(source.path("sourceId").asText()) || source.path("sourceId").asText().isBlank()
                    || source.path("version").asText().isBlank() || !Bs1Policy.sha(source.path("sha256")) || !source.path("url").asText().startsWith("https://"))
                throw new IllegalArgumentException("BS1_KNOWLEDGE_SOURCE_INVALID");
        if (document.has("expertScopes") && !document.path("expertScopes").isArray())
            throw new IllegalArgumentException("BS1_KNOWLEDGE_EXPERT_SCOPE_INVALID");
        for (var scope : document.path("expertScopes"))
            if (!scope.path("hgncId").asText().matches("HGNC:[1-9][0-9]*")
                    || !(scope.path("mondoId").asText().matches("MONDO:[0-9]{7}") || "*".equals(scope.path("mondoId").asText()))
                    || !sourceIds.contains(scope.path("sourceId").asText()) || scope.path("specificationId").asText().isBlank()
                    || scope.path("version").asText().isBlank() || !"STRUCTURING_REQUIRED".equals(scope.path("status").asText()))
                throw new IllegalArgumentException("BS1_KNOWLEDGE_EXPERT_SCOPE_INVALID");
        var values = new ArrayList<JsonNode>(); var keys = new HashSet<String>();
        for (var record : document.path("records")) {
            String key = key(record.path("scope"));
            if (!keys.add(key)) throw new IllegalArgumentException("BS1_KNOWLEDGE_DUPLICATE_RECORD");
            validateSources(record.path("sources"), document.path("sources"));
            if ("MAX_CREDIBLE_AF".equals(record.path("kind").asText()) && hasExpertScope(record.at("/scope/hgncId").asText(), record.at("/scope/mondoId").asText()))
                throw new IllegalArgumentException("BS1_EXPERT_SPECIFICATION_TAKES_PRECEDENCE");
            values.add(policy(record));
        }
        for (var background : document.path("backgrounds")) {
            if (background.has("parameterSupply")) {
                Bs1ParameterSupply.validate(background.path("parameterSupply"), background, document.path("sources"));
                String supplyKey = background.path("hgncId").asText() + "|" + background.path("mondoId").asText() + "|" + background.path("moi").asText();
                if (parameterSupplies.putIfAbsent(supplyKey, background.path("parameterSupply").deepCopy()) != null)
                    throw new IllegalArgumentException("BS1_KNOWLEDGE_DUPLICATE_BACKGROUND");
            }
            String mode = background.path("moi").asText();
            if (!Set.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE", "X_LINKED_DOMINANT", "X_LINKED_RECESSIVE", "Y_LINKED").contains(mode)) continue;
            var scope = JSON.createObjectNode().put("hgncId", background.path("hgncId").asText()).put("mondoId", background.path("mondoId").asText());
            scope.putArray("modes").add(mode);
            String key = key(scope);
            var assertions = background.path("assertions");
            if (!assertions.isArray() || assertions.isEmpty()) throw new IllegalArgumentException("BS1_KNOWLEDGE_RELATIONSHIP_SOURCE_REQUIRED");
            var classifications = new HashSet<String>();
            for (var a : assertions) {
                if (!sourceIds.contains(a.path("sourceId").asText()) || !a.path("hgncId").equals(background.path("hgncId"))
                        || !a.path("mondoId").equals(background.path("mondoId")) || !a.path("moi").equals(background.path("moi")))
                    throw new IllegalArgumentException("BS1_KNOWLEDGE_RELATIONSHIP_SOURCE_MISMATCH");
                classifications.add(a.path("classification").asText());
            }
            boolean established = Set.of("Definitive", "Strong", "Moderate").containsAll(classifications);
            String expectedReason = established ? "BS1_DISEASE_PARAMETERS_MISSING" : classifications.size() > 1
                    ? "BS1_RELATIONSHIP_CONFLICT" : "BS1_RELATIONSHIP_NOT_ESTABLISHED";
            boolean sexMode = !Set.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE").contains(mode);
            boolean unsupportedSexModel = sexMode && "BS1_MODEL_MOI_UNSUPPORTED".equals(background.path("reasonCode").asText());
            if (!expectedReason.equals(background.path("reasonCode").asText()) && !unsupportedSexModel)
                throw new IllegalArgumentException("BS1_KNOWLEDGE_RELATIONSHIP_DECISION_MISMATCH");
            var relationship = ((ObjectNode) background).deepCopy(); relationship.remove("parameterSupply");
            if (unsupportedSexModel && !established) relationship.put("sourceReasonCode", "BS1_MODEL_MOI_UNSUPPORTED")
                    .put("reasonCode", expectedReason);
            if (backgrounds.putIfAbsent(key, relationship) != null) throw new IllegalArgumentException("BS1_KNOWLEDGE_DUPLICATE_BACKGROUND");
            if (keys.contains(key) && (sexMode ? !established
                    : !"BS1_DISEASE_PARAMETERS_MISSING".equals(background.path("reasonCode").asText())))
                throw new IllegalArgumentException("BS1_KNOWLEDGE_RELATIONSHIP_CONFLICT");
        }
        policies = List.copyOf(values);
    }

    public static Bs1KnowledgeCatalog load(Path file, String expectedSha) throws IOException {
        if (!expectedSha.matches("[0-9a-f]{64}") || Files.size(file) > 32L * 1024 * 1024)
            throw new IllegalArgumentException("BS1_KNOWLEDGE_PIN_OR_SIZE_INVALID");
        byte[] raw;
        try (var in = Files.newInputStream(file)) { raw = in.readNBytes(32 * 1024 * 1024 + 1); }
        if (raw.length > 32 * 1024 * 1024 || !sha(raw).equals(expectedSha)) throw new IllegalArgumentException("BS1_KNOWLEDGE_SHA_MISMATCH");
        var mapper = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var doc = mapper.readTree(raw);
        if (!"bs1-knowledge-bundle-v1".equals(doc.path("schemaVersion").asText()) || doc.path("version").asText().isBlank()
                || !doc.path("sources").isArray() || !doc.path("records").isArray() || !doc.path("backgrounds").isArray()
                || doc.path("records").size() > 20000 || doc.path("backgrounds").size() > 50000)
            throw new IllegalArgumentException("BS1_KNOWLEDGE_SCHEMA_INVALID");
        return new Bs1KnowledgeCatalog(doc, expectedSha);
    }
    public List<JsonNode> merge(List<JsonNode> existing) {
        var result = new ArrayList<JsonNode>(existing);
        // All pre-existing expert scope, including unresolved conditions, takes precedence.
        var pairs = new HashSet<String>();
        for (var p : existing) pairs.add(pair(p.path("scope")));
        for (var p : policies) if (!pairs.contains(pair(p.path("scope")))) result.add(p.deepCopy());
        return List.copyOf(result);
    }
    public ObjectNode identity() {
        var value = JSON.createObjectNode().put("schemaVersion", "bs1-knowledge-identity-v1").put("rawSha256", rawSha)
                .put("version", document.path("version").asText()).put("modelVersion", Bs1MaximumCredibleFrequency.VERSION);
        if (!parameterSupplies.isEmpty()) value.put("parameterSupplyVersion", Bs1ParameterSupply.VERSION);
        for (var record : document.path("records")) if (record.has("qualification")) {
            value.put("qualificationVersion", Bs1KnowledgeQualification.VERSION); break;
        }
        for (var record : document.path("records")) if (Bs1FrequencyStatistic.METRIC.equals(record.at("/populationStatistic/metricId").asText())
                || Bs1FrequencyStatistic.METRIC.equals(record.at("/expertPolicy/statistic/metricId").asText())) {
            value.put("frequencyStatisticVersion", Bs1FrequencyStatistic.VERSION); break;
        }
        for (var record : document.path("records")) if (Bs1SexChromosomePolicy.isStatistic(policy(record))) {
            value.put("sexChromosomeStatisticVersion", Bs1SexChromosomeStatistic.version(policy(record)))
                    .put("sexChromosomeRegionVersion", SexChromosomeRegionResolver.VERSION)
                    .put("sexChromosomeRegionCatalogSha256", SexChromosomeRegionResolver.CATALOG_SHA256);
            break;
        }
        for (var record : document.path("records")) if (Bs1SexChromosomePolicy.isJoint(record.path("expertPolicy"))) {
            value.put("jointSexChromosomeStatisticVersion", Bs1SexChromosomeStatistic.JOINT_VERSION)
                    .put("jointSexObservationVersion", GnomadSexStratifiedObservation.JOINT_VERSION)
                    .put("jointSexHeaderSha256", GnomadSexStratifiedObservation.JOINT_HEADER_SHA256)
                    .put("jointSexFilterSourceSha256", hash(Bs1JointFilterSource.sexContract(JSON))); break;
        }
        appendCountEligibilityMethodIdentity(value, policies);
        return value;
    }

    /** Only the new count-eligibility feature adds version sets. Legacy catalog/runtime
     * identity bytes stay unchanged, while mixed new methods never depend on row order.
     * These fields declare methods; they do not prove a policy is scientifically adopted. */
    static void appendCountEligibilityMethodIdentity(ObjectNode identity, List<JsonNode> policies) {
        if (policies.stream().noneMatch(p -> Bs1SexChromosomePolicy.isStatistic(p)
                && p.path("statistic").has("eligibleAc"))) return;
        var all = new TreeSet<String>(); var exomes = new TreeSet<String>(); var joint = new TreeSet<String>();
        for (var policy : policies) {
            if (!Bs1SexChromosomePolicy.isStatistic(policy)) continue;
            String version = Bs1SexChromosomeStatistic.version(policy); all.add(version);
            (Bs1SexChromosomePolicy.isJoint(policy) ? joint : exomes).add(version);
        }
        methodVersions(identity, "sexChromosomeStatisticVersion", "sexChromosomeStatisticVersions", all);
        methodVersions(identity, "exomesSexChromosomeStatisticVersion", "exomesSexChromosomeStatisticVersions", exomes);
        methodVersions(identity, "jointSexChromosomeStatisticVersion", "jointSexChromosomeStatisticVersions", joint);
        // Runtime identities already expose this shorter JOINT field; preserve its meaning.
        if (identity.has("jointSexStatisticVersion") && !joint.isEmpty())
            identity.put("jointSexStatisticVersion", joint.size() == 1 ? joint.first() : "MIXED");
    }

    private static void methodVersions(ObjectNode identity, String singleField, String versionsField, TreeSet<String> versions) {
        if (versions.isEmpty()) return;
        identity.put(singleField, versions.size() == 1 ? versions.first() : "MIXED");
        var values = identity.putArray(versionsField); versions.forEach(values::add);
    }

    public int backgroundCount() { return backgrounds.size(); }
    /** Candidate relationships only; the binding resolver still checks the patient's explicit choice. */
    public List<JsonNode> relationships() { return backgrounds.values().stream().<JsonNode>map(n -> n.deepCopy()).toList(); }
    /** Published criterion scope is not a gene-validity classification or a patient diagnosis. */
    public List<JsonNode> expertRelationships() {
        var result = new ArrayList<JsonNode>();
        for (var p : policies) {
            var record = p.path("knowledgeRecord"); var r = record.path("relationship");
            if (!"EXPERT_POLICY".equals(record.path("kind").asText()) || r.isMissingNode() || Bs1Policy.issue(p) != null) continue;
            var scope = record.path("scope");
            if (!Bs1Policy.keys(r, Set.of("symbol", "diseaseLabel", "sourceId", "specificationId", "specificationVersion", "approvedOn", "panel", "url"))
                    || r.path("symbol").asText().isBlank() || r.path("diseaseLabel").asText().isBlank()
                    || r.path("panel").asText().isBlank() || r.path("approvedOn").asText().isBlank()
                    || !r.path("specificationId").asText().matches("GN[0-9]+")
                    || !r.path("specificationVersion").equals(record.path("version"))
                    || !("https://cspec.clinicalgenome.org/cspec/ui/svi/doc/" + r.path("specificationId").asText()).equals(r.path("url").asText()))
                throw new IllegalArgumentException("BS1_EXPERT_RELATIONSHIP_INVALID");
            boolean bound = false;
            for (var source : record.path("sources")) bound |= source.path("sourceId").equals(r.path("sourceId"));
            if (!bound) throw new IllegalArgumentException("BS1_EXPERT_RELATIONSHIP_SOURCE_UNBOUND");
            var row = JSON.createObjectNode().put("hgncId", scope.path("hgncId").asText()).put("symbol", r.path("symbol").asText())
                    .put("mondoId", scope.path("mondoId").asText()).put("diseaseLabel", r.path("diseaseLabel").asText())
                    .put("moi", switch (scope.at("/modes/0").asText()) {
                        case "AUTOSOMAL_DOMINANT" -> "AD";
                        case "AUTOSOMAL_RECESSIVE" -> "AR";
                        case "X_LINKED_DOMINANT" -> "X_LINKED_DOMINANT";
                        case "X_LINKED_RECESSIVE" -> "X_LINKED_RECESSIVE";
                        case "Y_LINKED" -> "Y_LINKED";
                        default -> throw new IllegalArgumentException("BS1_EXPERT_RELATIONSHIP_MODE_UNSUPPORTED");
                    })
                    .put("classification", "BS1_VCEP_APPLICABLE").put("classificationIsGeneValidity", false)
                    .put("relationshipType", "BS1_EXPERT_SPECIFICATION_SCOPE").put("classificationDate", r.path("approvedOn").asText())
                    .put("gcep", r.path("panel").asText()).put("reportLocator", r.path("url").asText())
                    .put("knowledgeRecordSha256", record.at("/review/contentSha256").asText());
            row.set("relationshipSource", identity()); row.set("specification", r.deepCopy()); result.add(row);
        }
        return List.copyOf(result);
    }
    public ObjectNode explain(String gene, String disease, String moi) {
        var result = JSON.createObjectNode().put("available", false).put("reasonCode", "BS1_KNOWLEDGE_RELATIONSHIP_NO_RECORD");
        var row = backgrounds.get(gene + "|" + disease + "|" + moi);
        result.set("knowledgeIdentity", identity());
        if (row != null) { result.put("reasonCode", row.path("reasonCode").asText()); result.set("background", row.deepCopy()); }
        if (hasExpertScope(gene, disease)) result.put("reasonCode", "BS1_EXPERT_SPECIFICATION_REQUIRES_STRUCTURING");
        var supply = parameterSupplies.get(gene + "|" + disease + "|" + moi);
        if (supply != null) result.set("parameterSupply", supply.deepCopy());
        else result.put("parameterSupplyStatus", "NOT_RECORDED_IN_THIS_SNAPSHOT");
        return result;
    }
    private boolean hasExpertScope(String gene, String disease) {
        for (var scope : document.path("expertScopes"))
            if (gene.equals(scope.path("hgncId").asText()) && (disease.equals(scope.path("mondoId").asText()) || "*".equals(scope.path("mondoId").asText()))) return true;
        return false;
    }
    static boolean isPolicy(JsonNode policy) { return Set.of(ACTIVATION, JOINT_ACTIVATION).contains(policy.path("activation").asText()); }
    static String issue(JsonNode p) {
        try {
            var expected = policy(p.path("knowledgeRecord"));
            if (!expected.equals(p)) return "BS1_KNOWLEDGE_POLICY_DERIVATION_MISMATCH";
            String reason = expected.at("/knowledgeDecision/reasonCode").asText();
            return reason.isBlank() ? null : reason;
        } catch (IllegalArgumentException invalid) { return "BS1_KNOWLEDGE_RECORD_INVALID"; }
    }

    static ObjectNode policy(JsonNode record) {
        if (!record.isObject() || record.path("recordId").asText().isBlank() || record.path("version").asText().isBlank())
            throw new IllegalArgumentException("BS1_KNOWLEDGE_RECORD_ID_REQUIRED");
        if (!Bs1Policy.keys(record, Set.of("recordId", "version", "kind", "scope", "sources", "model", "exceptionSet", "review",
                "expertPolicy", "publicationStatus", "supportedAncestries", "reasonCode", "relationship", "qualification", "modelRestrictions", "populationStatistic")))
            throw new IllegalArgumentException("BS1_KNOWLEDGE_UNKNOWN_CONDITION");
        key(record.path("scope"));
        boolean jointSex = Bs1SexChromosomePolicy.isJoint(record.path("expertPolicy"));
        var p = JSON.createObjectNode().put("schemaVersion", Bs1Policy.SCHEMA).put("criterionId", Bs1Policy.CRITERION)
                .put("policyId", "BS1_KNOWLEDGE_" + hash(record)).put("version", record.path("version").asText())
                .put("activation", jointSex ? JOINT_ACTIVATION : ACTIVATION).put("sourceCompleteness", "COMPLETE").put("semanticSupport", "SUPPORTED").put("identityResolution", "RESOLVED");
        p.set("knowledgeRecord", record.deepCopy()); p.set("sources", record.path("sources").deepCopy());
        var scope = ((ObjectNode) record.path("scope")).deepCopy().put("modeSelection", "EXACT_DECLARED_MODE");
        scope.putArray("variantTypes").add("SNV").add("SMALL_INSERTION").add("SMALL_DELETION");
        p.set("scope", scope);
        var statistic = p.putObject("statistic").put("metricId", "gnomad.exomes.faf95max").put("unit", "fraction").put("confidence", "95_PERCENT_LOWER_BOUND");
        statistic.putArray("ancestries").add("afr").add("amr").add("eas").add("nfe").add("sas").add("mid");
        p.set("qualityContract", Bs1LocalExomes.qualityContract()); p.set("dataScope", Bs1LocalExomes.scope(JSON));
        p.withObject("/dataScope").put("description", "Local exomes population facts with an explicitly adopted, data-defined BS1 threshold.")
                .put("authorityNote", "Threshold basis and source parameters are recorded in knowledgeDecision and knowledgeRecord; a calculated model is not an official VCEP threshold.");
        if (jointSex) {
            p.set("qualityContract", Bs1SexChromosomePolicy.jointQuality(JSON));
            p.set("dataScope", Bs1SexChromosomePolicy.jointScope(JSON));
        }
        var resource = (jointSex ? GnomadPopulationInputProfile.loadExecutableV2() : GnomadPopulationInputProfile.loadLocalBa1())
                .getDocument().path("resources").get(0);
        p.putObject("resourceCompatibility").put("status", "COMPATIBLE").put("resourceVersion", resource.path("resourceVersion").asText())
                .put("resourceSha256", resource.path("identityFingerprintSha256").asText()).put("basis", jointSex ? "EXPLICIT_JOINT_SEX_KNOWLEDGE_ADOPTION" : "EXPLICIT_LOCAL_EXOMES_KNOWLEDGE_ADOPTION");
        p.putObject("requiredContext").put("transcript", "NOT_REQUIRED_BY_POLICY");
        p.putArray("commonConditions"); p.putArray("tiers"); p.set("exceptionSet", record.path("exceptionSet").deepCopy());
        var decision = p.putObject("knowledgeDecision");
        String kind = record.path("kind").asText();
        if ("KNOWLEDGE_GAP".equals(kind)) {
            String reason = record.path("reasonCode").asText();
            if (!Set.of("BS1_DISEASE_PARAMETERS_MISSING", "BS1_RELATIONSHIP_CONFLICT", "BS1_RELATIONSHIP_NOT_ESTABLISHED", "BS1_EXPERT_SPECIFICATION_REQUIRES_STRUCTURING").contains(reason))
                throw new IllegalArgumentException("BS1_KNOWLEDGE_REASON_INVALID");
            decision.put("reasonCode", reason); return p;
        }
        if (!Set.of("MAX_CREDIBLE_AF", "EXPERT_POLICY").contains(kind)) throw new IllegalArgumentException("BS1_KNOWLEDGE_KIND_INVALID");
        var review = record.path("review"); var content = ((ObjectNode) record).deepCopy(); content.remove("review");
        if (!"REVIEWED_FOR_LOCAL_USE".equals(review.path("status").asText()) || !hash(content).equals(review.path("contentSha256").asText())
                || review.path("reviewer").asText().isBlank() || review.path("reviewedAt").asText().isBlank() || review.path("basis").asText().isBlank()
                || !(jointSex ? Bs1SexChromosomePolicy.JOINT_SCOPE : "BS1_LOCAL_EXOMES_V1").equals(review.path("dataScopePolicyId").asText())) {
            decision.put("reasonCode", "BS1_KNOWLEDGE_REVIEW_REQUIRED"); return p;
        }
        String qualificationIssue = Bs1KnowledgeQualification.issue(record);
        if (qualificationIssue != null) { decision.put("reasonCode", qualificationIssue); return p; }
        if ("EXPERT_POLICY".equals(kind)) {
            var expert = record.path("expertPolicy");
            if (!"COMPLETE".equals(expert.path("sourceCompleteness").asText()) || !"SUPPORTED".equals(expert.path("semanticSupport").asText())
                    || !"RESOLVED".equals(expert.path("identityResolution").asText()) || !Bs1Policy.keys(expert, Set.of("schemaVersion", "policyId", "version", "criterionId", "sources", "sourceCompleteness",
                    "semanticSupport", "identityResolution", "activation", "scope", "statistic", "tiers", "commonConditions", "exceptionSet", "requiredContext", "qualityContract", "resourceCompatibility",
                    "interpretation", "dataScope", "sourcePolicyId", "sourcePolicyVersion", "sourcePolicySha256"))) {
                decision.put("reasonCode", "BS1_EXPERT_SPECIFICATION_REQUIRES_STRUCTURING"); return p;
            }
            if (!record.path("scope").path("hgncId").equals(expert.at("/scope/hgncId"))
                    || !record.path("scope").path("mondoId").equals(expert.at("/scope/mondoId"))
                    || !record.path("scope").path("modes").equals(expert.at("/scope/modes"))) throw new IllegalArgumentException("BS1_EXPERT_SCOPE_CONFLICT");
            for (String field : List.of("scope", "tiers", "statistic", "commonConditions", "exceptionSet", "requiredContext")) p.set(field, expert.path(field).deepCopy());
            if (!"Released".equals(record.path("publicationStatus").asText())) decision.put("reasonCode", "BS1_EXPERT_SPECIFICATION_NOT_RELEASED");
        } else {
            Bs1KnowledgeQualification.applyModelRestrictions(record, p);
            var ancestries = record.path("supportedAncestries");
            if (!ancestries.isArray() || ancestries.isEmpty()) { decision.put("reasonCode", "BS1_MODEL_POPULATION_SCOPE_REQUIRED"); return p; }
            statistic.set("ancestries", ancestries.deepCopy());
            if (record.has("populationStatistic")) {
                if (!ancestries.equals(record.at("/populationStatistic/ancestries"))) {
                    decision.put("reasonCode", "BS1_MODEL_STATISTIC_ANCESTRY_CONFLICT"); return p;
                }
                statistic.removeAll(); statistic.setAll((ObjectNode) record.path("populationStatistic"));
            }
            var calculation = Bs1MaximumCredibleFrequency.calculate(JSON, scope.path("modes").path(0).asText(), record.path("model"));
            decision.set("calculation", calculation);
            if (!"CALCULATED_NOT_ADOPTED".equals(calculation.path("status").asText())) {
                decision.put("reasonCode", calculation.path("reasonCode").asText()); return p;
            }
            var sourceIds = new HashSet<String>(); for (var s : record.path("sources")) sourceIds.add(s.path("sourceId").asText());
            for (String field : List.of("prevalence", "penetrance", "maximumGeneContribution", "maximumAlleleContribution"))
                if (!sourceIds.contains(record.path("model").path(field).path("sourceId").asText())) throw new IllegalArgumentException("BS1_PARAMETER_SOURCE_UNBOUND");
            if (!"NO_APPLICABLE_EXPERT_SPECIFICATION".equals(review.path("expertSpecificationCheck").asText())) {
                decision.put("reasonCode", "BS1_EXPERT_SPECIFICATION_CHECK_REQUIRED"); return p;
            }
            String strength = review.path("strength").asText();
            if (!Set.of("STRONG", "SUPPORTING").contains(strength)) { decision.put("reasonCode", "BS1_MODEL_STRENGTH_ADOPTION_REQUIRED"); return p; }
            var tier = p.withArray("tiers").addObject().put("tierId", "MAX_CREDIBLE_AF").put("priority", 1)
                    .put("moi", scope.path("modes").path(0).asText()).put("strength", strength).put("sourceLocator", "knowledgeRecord/model");
            tier.putArray("conditions").addObject().put("fact", Bs1FrequencyStatistic.isAf(p) ? "AF" : "FAF95").put("operator", "GT")
                    .put("decimal", calculation.path("maximumCredibleAf").asText()).put("unit", "fraction")
                    .put("basis", "REVIEWED_MODEL_CONDITION").put("originalText", calculation.path("formula").asText()).put("sourceLocator", "knowledgeRecord/model");
        }
        decision.put("basis", kind).put("clinicalAdoption", "NOT_ESTABLISHED");
        return p;
    }
    private static void validateSources(JsonNode references, JsonNode sources) {
        if (!references.isArray() || references.isEmpty()) throw new IllegalArgumentException("BS1_KNOWLEDGE_SOURCE_REQUIRED");
        for (var ref : references) {
            if (!Bs1Policy.sha(ref.path("sha256")) || ref.path("locator").asText().isBlank() || !ref.path("url").asText().startsWith("https://"))
                throw new IllegalArgumentException("BS1_KNOWLEDGE_SOURCE_INVALID");
            var matches = new ArrayList<JsonNode>(); for (var s : sources) if (s.path("sourceId").equals(ref.path("sourceId"))) matches.add(s);
            if (matches.size() != 1) throw new IllegalArgumentException("BS1_KNOWLEDGE_SOURCE_UNBOUND");
            for (String field : List.of("url", "sha256", "version")) if (!ref.path(field).equals(matches.get(0).path(field)))
                throw new IllegalArgumentException("BS1_KNOWLEDGE_SOURCE_UNBOUND");
        }
    }
    private static String pair(JsonNode scope) { return scope.path("hgncId").asText() + "|" + scope.path("mondoId").asText(); }
    private static String key(JsonNode scope) {
        if (!scope.path("hgncId").asText().matches("HGNC:[1-9][0-9]*") || !scope.path("mondoId").asText().matches("MONDO:[0-9]{7}")
                || !scope.path("modes").isArray() || scope.path("modes").size() != 1
                || !Set.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE", "X_LINKED_DOMINANT", "X_LINKED_RECESSIVE", "Y_LINKED").contains(scope.at("/modes/0").asText()))
            throw new IllegalArgumentException("BS1_KNOWLEDGE_SCOPE_INVALID");
        return pair(scope) + "|" + scope.at("/modes/0").asText();
    }
    static String hash(JsonNode node) { return new RuntimeEvidenceV2Adapter(JSON).canonicalSha256(node); }
    public static String sha(byte[] raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
