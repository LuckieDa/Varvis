package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.lang.Nullable;

/** Immutable BA1 projection of a complete released CSpec batch. No gene-specific executable policies. */
public final class Ba1SpecificationCatalog {
    public static final String SCHEMA = "a01-ba1-cspec-snapshot-v1";
    public static final String VERSION = "2.2.1-catalog-ba1-shared-conditions";
    static final String JOINT_DATASET = "JOINT";
    private static final String GT = "GREATER_THAN", GTE = "GREATER_THAN_OR_EQUAL";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter canonicalizer;
    private final ObjectNode document;
    private final String sha256;
    private final String issue;
    @Nullable private final Ba1ManeTranscriptPairs maneTranscriptPairs;
    private final java.util.Map<String, Set<String>> ensemblGenes = new java.util.HashMap<>();
    private final java.util.Map<String, Set<String>> symbolGenes = new java.util.HashMap<>();

    public Ba1SpecificationCatalog(ObjectMapper mapper, ObjectNode snapshot) {
        this(mapper, snapshot, null);
    }

    Ba1SpecificationCatalog(ObjectMapper mapper, ObjectNode snapshot, @Nullable Ba1ManeTranscriptPairs maneTranscriptPairs) {
        this(mapper, snapshot, new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(snapshot), null, maneTranscriptPairs);
    }

    private Ba1SpecificationCatalog(ObjectMapper mapper, ObjectNode snapshot, String sha256, String failure,
                                    @Nullable Ba1ManeTranscriptPairs maneTranscriptPairs) {
        this.mapper = mapper;
        this.canonicalizer = new RuntimeEvidenceV2Adapter(mapper);
        this.document = snapshot.deepCopy();
        this.sha256 = sha256;
        this.maneTranscriptPairs = maneTranscriptPairs;
        this.issue = failure == null ? validate() : failure;
        for (JsonNode gene : document.path("geneMappings")) {
            String hgncId = gene.path("hgncId").asText();
            for (String id : strings(gene.path("ensemblGeneIds"))) ensemblGenes.computeIfAbsent(id, ignored -> new HashSet<>()).add(hgncId);
            for (String symbol : strings(gene.path("geneSymbols"))) symbolGenes.computeIfAbsent(symbol, ignored -> new HashSet<>()).add(hgncId);
        }
    }

    public static Ba1SpecificationCatalog load(ObjectMapper mapper, Path path, String expectedFileSha256) throws IOException {
        return load(mapper, path, expectedFileSha256, null);
    }

    public static Ba1SpecificationCatalog load(ObjectMapper mapper, Path path, String expectedFileSha256,
                                               @Nullable Ba1ManeTranscriptPairs maneTranscriptPairs) throws IOException {
        if (Files.size(path) > 64L * 1024 * 1024) throw new IllegalArgumentException("BA1_CATALOG_SIZE_LIMIT");
        byte[] bytes = Files.readAllBytes(path);
        String actual = digest(bytes);
        if (expectedFileSha256 == null || !expectedFileSha256.matches("[0-9a-f]{64}") || !actual.equals(expectedFileSha256))
            throw new IllegalArgumentException("BA1_CATALOG_FILE_IDENTITY_MISMATCH");
        JsonNode parsed = mapper.readTree(bytes);
        if (!(parsed instanceof ObjectNode object)) throw new IllegalArgumentException("BA1_CATALOG_INVALID");
        return new Ba1SpecificationCatalog(mapper, object, actual, null, maneTranscriptPairs);
    }

    public static Ba1SpecificationCatalog bundled(ObjectMapper mapper) {
        return bundled(mapper, null);
    }

    public static Ba1SpecificationCatalog bundled(ObjectMapper mapper, @Nullable Ba1ManeTranscriptPairs maneTranscriptPairs) {
        try (var stream = Ba1SpecificationCatalog.class.getResourceAsStream("/acmg/ba1-cspec-snapshot-v1.json");
             var seal = Ba1SpecificationCatalog.class.getResourceAsStream("/acmg/ba1-cspec-snapshot-v1.sha256")) {
            if (stream == null || seal == null) return unavailable(mapper, "BA1_CATALOG_NOT_CONFIGURED");
            byte[] bytes = stream.readAllBytes();
            String sha = new String(seal.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).trim();
            if (!digest(bytes).equals(sha)) return unavailable(mapper, "BA1_CATALOG_FILE_IDENTITY_MISMATCH");
            JsonNode parsed = mapper.readTree(bytes);
            if (!(parsed instanceof ObjectNode object)) return unavailable(mapper, "BA1_CATALOG_INVALID");
            return new Ba1SpecificationCatalog(mapper, object, sha, null, maneTranscriptPairs);
        } catch (IOException | IllegalArgumentException failure) {
            return unavailable(mapper, "BA1_CATALOG_UNAVAILABLE");
        }
    }

    public static Ba1SpecificationCatalog unavailable(ObjectMapper mapper, String reasonCode) {
        return new Ba1SpecificationCatalog(mapper, mapper.createObjectNode(), "UNAVAILABLE", reasonCode, null);
    }

    public ObjectNode identity() {
        var identity = mapper.createObjectNode().put("schemaVersion", SCHEMA).put("catalogSha256", sha256)
                .put("mappingDiagnosticVersion", "ba1-gene-mapping-diagnostics-v1")
                .put("snapshotId", document.at("/metadata/snapshotId").asText())
                .put("status", issue == null ? "READY" : "UNAVAILABLE").put("reasonCode", issue);
        var pairs = maneTranscriptPairs;
        if (pairs != null) identity.set("transcriptPairSource", pairs.identity(mapper));
        for (var entry : document.path("entries")) if (Ba1ConditionalFrequencyRule.FAMILY.equals(entry.at("/ba1/semanticFamily").asText())) {
            identity.put("conditionalFrequencyContract", Ba1ConditionalFrequencyRule.VERSION); break;
        }
        return identity;
    }

    private String validate() {
        JsonNode metadata = document.path("metadata"), entries = document.path("entries");
        if (!SCHEMA.equals(document.path("schemaVersion").asText()) || !entries.isArray()
                || !"COMPLETE".equals(metadata.path("status").asText())
                || !"Released".equals(metadata.path("publicationScope").asText())
                || metadata.path("snapshotId").asText().isBlank()
                || !metadata.path("sourceBase").asText().startsWith("https://")
                || metadata.path("declaredEntityCount").asInt(-1) < 0
                || metadata.path("declaredEntityCount").asInt(-1) != metadata.path("enumeratedEntityCount").asInt(-2)
                || metadata.path("entryCount").asInt(-1) != entries.size()
                || !metadata.path("pages").isArray() || metadata.path("pages").isEmpty()) return "BA1_CATALOG_INCOMPLETE";
        JsonNode review = metadata.path("batchReview");
        if (!"REVIEWED".equals(review.path("status").asText()) || review.path("reviewId").asText().isBlank()
                || review.path("compilerVersion").asText().isBlank()
                || !review.path("compilerVersion").equals(metadata.path("compilerVersion"))
                || !review.path("sourceCaptureSha256").equals(metadata.path("captureManifestSha256"))
                || !review.path("sourceCaptureSha256").asText().matches("[0-9a-f]{64}")) return "BA1_CATALOG_REVIEW_REQUIRED";
        Set<String> pages = new HashSet<>();
        int count = 0;
        for (JsonNode page : metadata.path("pages")) {
            if (!page.path("page").isIntegralNumber() || !pages.add(page.path("page").asText())
                    || page.path("returnedCount").asInt(-1) < 0
                    || !page.path("rawSha256").asText().matches("[0-9a-f]{64}")) return "BA1_CATALOG_PAGE_RECEIPT_INVALID";
            count += page.path("returnedCount").asInt();
        }
        if (count != metadata.path("enumeratedEntityCount").asInt()) return "BA1_CATALOG_PAGE_COUNT_MISMATCH";
        Set<String> ids = new HashSet<>(), specifications = new HashSet<>();
        for (JsonNode entry : entries) {
            String spec = entry.path("specificationId").asText(), version = entry.path("version").asText();
            if (spec.isBlank() || version.isBlank() || !"Released".equals(entry.path("publicationStatus").asText())
                    || !entry.path("rawSha256").asText().matches("[0-9a-f]{64}")) return "BA1_CATALOG_ENTRY_IDENTITY_INVALID";
            String id = spec + "@" + version + ":" + canonicalizer.canonicalSha256(entry.path("scope"));
            if (!ids.add(id)) return "BA1_CATALOG_DUPLICATE_SCOPE";
            specifications.add(spec + "@" + version);
        }
        if (specifications.size() != metadata.path("selectedReleasedCount").asInt(-1)) return "BA1_CATALOG_RELEASE_COUNT_MISMATCH";
        return null;
    }

    /** Selects only; observations never influence policy selection or thresholds. */
    public ObjectNode select(JsonNode mapping, JsonNode declaredContext) {
        ObjectNode result = mapper.createObjectNode().put("catalogSha256", sha256).put("scope", "GENERAL_BASELINE");
        result.putArray("sources").addObject().put("url", document.at("/metadata/sourceBase").asText())
                .put("snapshotId", document.at("/metadata/snapshotId").asText()).put("sha256", sha256);
        result.putArray("requiredInputs");
        result.set("geneMapping", mapping.deepCopy());
        boolean declared = declaredContext != null && declaredContext.isObject();
        if (declared && declaredContext != null) result.put("contextId", declaredContext.path("contextId").asText());
        if (issue != null) return finish(result, "BLOCKED", issue, "specificationCatalog");
        if (!"RESOLVED".equals(mapping.path("status").asText()))
            return finish(result, "BLOCKED", "BA1_GENE_MAPPING_REQUIRED", "verifiedAlleleGeneMapping");
        Set<String> genes = strings(mapping.path("hgncIds"));
        if (genes.isEmpty() || genes.stream().anyMatch(value -> !value.matches("HGNC:[0-9]+")))
            return finish(result, "BLOCKED", "BA1_GENE_MAPPING_REQUIRED", "verifiedAlleleGeneMapping");
        if (declared && declaredContext != null) {
            String gene = declaredContext.at("/gene/hgncId").asText();
            if (gene.isBlank() || !genes.contains(gene)) return finish(result, "BLOCKED", "BA1_CONTEXT_GENE_MAPPING_MISMATCH", "gene");
            genes = Set.of(gene);
        }
        List<JsonNode> matches = new ArrayList<>();
        ObjectNode matchedTranscriptBinding = null;
        for (JsonNode entry : document.path("entries")) {
            JsonNode scope = entry.path("scope");
            JsonNode allele = declared && declaredContext != null ? declaredContext.at("/variant/evidenceQueryAllele") : mapping.path("queryAllele");
            if ("MITOCHONDRIAL".equals(scope.path("sequenceScope").asText()) && strings(scope.path("contigs")).equals(Set.of("MT"))
                    && "GRCh38".equals(allele.path("assembly").asText())
                    && allele.path("contig").asText().matches("(?:[1-9]|1[0-9]|2[0-2]|X|Y)")) continue;
            Set<String> scopedGenes = strings(scope.path("hgncIds"));
            if (scopedGenes.isEmpty()) return finish(result, "BLOCKED", "BA1_SPECIFICATION_SCOPE_UNRESOLVED", "specificationScope");
            if (java.util.Collections.disjoint(genes, scopedGenes)) continue;
            String disposition = entry.at("/ba1/disposition").asText();
            // Explicit unchanged BA1 cannot acquire restrictions belonging to another criterion.
            if ("GENERAL".equals(disposition) && validSources(entry.at("/ba1/sourceRefs"))) continue;
            if (!"RESOLVED".equals(scope.path("status").asText()))
                return finish(result, "BLOCKED", "BA1_SPECIFICATION_SCOPE_UNRESOLVED", "specificationScope");
            if (!declared || declaredContext == null) return withEntry(finish(result, "BLOCKED", "BA1_DISEASE_CONTEXT_REQUIRED", "disease.mondoId,moi"), entry);
            String declarationPolicy = declaredContext.at("/transcript/selectionPolicy").asText();
            if (declarationPolicy.contains("PM2"))
                return withEntry(finish(result, "BLOCKED", "BA1_DISEASE_CONTEXT_CONFIRMATION_REQUIRED", "ba1DiseaseContextConfirmation"), entry);
            Set<String> diseases = strings(scope.path("mondoIds")), mois = strings(scope.path("moi"));
            String disease = declaredContext.at("/disease/mondoId").asText(), moi = declaredContext.path("moi").asText();
            if (diseases.isEmpty() || mois.isEmpty()) return withEntry(finish(result, "BLOCKED", "BA1_SPECIFICATION_SCOPE_UNRESOLVED", "specificationScope"), entry);
            if (disease.isBlank() || moi.isBlank()) return withEntry(finish(result, "BLOCKED", "BA1_DISEASE_CONTEXT_REQUIRED", "disease.mondoId,moi"), entry);
            if (!diseases.contains(disease) || !mois.contains(moi)) {
                if (explicitlyExcluded(entry, declaredContext)) continue;
                return withEntry(finish(result, "BLOCKED", "BA1_DISEASE_SCOPE_UNRESOLVED", "verifiedDiseaseAndInheritanceScopeRelation"), entry);
            }
            String transcriptScope = scope.path("transcriptScope").asText();
            if (!"UNRESTRICTED".equals(transcriptScope)) {
                String policy = declaredContext.at("/transcript/selectionPolicy").asText();
                String accession = declaredContext.at("/transcript/accession").asText();
                String version = declaredContext.at("/transcript/version").asText();
                if (!"EXACT".equals(transcriptScope) || policy.isBlank() || policy.contains("PM2")
                        || org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.populationOnlySelection(policy)
                        || accession.isBlank() || version.isBlank())
                    return withEntry(finish(result, "BLOCKED", "BA1_TRANSCRIPT_CONTEXT_REQUIRED", "validatedDiseaseTranscript"), entry);
                Set<String> expectedTranscripts = strings(scope.path("transcripts"));
                String selectedTranscript = accession + "." + version;
                if (!expectedTranscripts.contains(selectedTranscript)) {
                    var binding = maneTranscriptBinding(declaredContext, selectedTranscript, expectedTranscripts);
                    if (binding == null || !"MATCHED".equals(binding.path("outcome").asText())) {
                        if (binding != null) result.set("transcriptScopeBinding", binding);
                        return withEntry(finish(result, "BLOCKED", "BA1_TRANSCRIPT_SCOPE_UNRESOLVED", "reviewedTranscriptScopeOrVersionBridge"), entry);
                    }
                    matchedTranscriptBinding = binding;
                }
            }
            matches.add(entry);
        }
        if (matches.size() > 1) return finish(result, "BLOCKED", "BA1_SPECIFICATION_CONFLICT", "specificationConflictResolution");
        ObjectNode policy = baselinePolicy();
        if (!matches.isEmpty()) {
            JsonNode entry = matches.get(0), ba1 = entry.path("ba1");
            if (matchedTranscriptBinding != null) result.set("transcriptScopeBinding", matchedTranscriptBinding);
            withEntry(result, entry);
            result.put("scope", "DISEASE_SPECIFIC");
            if ("DISABLED".equals(ba1.path("disposition").asText())) return finish(result, "NOT_APPLICABLE", "BA1_DISABLED_BY_SPECIFICATION", null);
            if ("CONDITIONAL".equals(ba1.path("disposition").asText())) {
                String conditionalIssue = Ba1ConditionalFrequencyRule.issue(ba1);
                if (conditionalIssue != null) return finish(result, "BLOCKED", conditionalIssue, "reviewedExecutableBa1Clause");
                // Retain shared exception/source policy, but never let an older scalar consumer
                // interpret a complete OR clause as only its frequency arm.
                var frequency = ba1.at("/expression/frequency");
                var rule = (ObjectNode) policy.path("frequencyRule");
                rule.put("comparisonOperator", frequency.path("comparator").asText());
                ((ObjectNode) rule.path("threshold")).put("rawValue", frequency.path("value").asText())
                        .put("normalizedValue", frequency.path("value").asText());
                ((ObjectNode) rule.path("minimumSelectedAncestryAlleleNumber"))
                        .put("rawValue", frequency.path("minimumAn").asText())
                        .put("comparisonOperator", frequency.path("minimumAnComparator").asText());
                policy.put("policyId", entry.path("specificationId").asText() + ":BA1")
                        .put("policyVersion", entry.path("version").asText())
                        .put("conditionRuntimeContract", Ba1ConditionalFrequencyRule.VERSION);
                policy.set("ancestries", frequency.path("ancestries").deepCopy());
                policy.set("clause", ba1.deepCopy()); result.set("policy", policy);
                return finish(result, "CONDITIONAL_SELECTED", "BA1_CONDITIONAL_EVALUATOR_REQUIRED", null);
            }
            if (!"THRESHOLD".equals(ba1.path("disposition").asText()) || !validThreshold(ba1))
                return finish(result, "BLOCKED", "UNSUPPORTED_CLAUSE", "reviewedExecutableBa1Clause");
            ObjectNode rule = (ObjectNode) policy.path("frequencyRule");
            rule.put("comparisonOperator", ba1.path("comparator").asText());
            ((ObjectNode) rule.path("threshold")).put("rawValue", ba1.path("value").asText()).put("normalizedValue", ba1.path("value").asText());
            ((ObjectNode) rule.path("minimumSelectedAncestryAlleleNumber")).put("rawValue", ba1.path("minimumAn").asText())
                    .put("comparisonOperator", ba1.path("minimumAnComparator").asText());
            policy.put("policyId", entry.path("specificationId").asText() + ":BA1").put("policyVersion", entry.path("version").asText());
            policy.set("ancestries", ba1.path("ancestries").deepCopy());
            policy.set("clause", ba1.deepCopy());
        }
        result.set("policy", policy);
        return finish(result, "SELECTED", "BA1_POLICY_SELECTED", null);
    }

    @Nullable
    private ObjectNode maneTranscriptBinding(JsonNode context, String ensembl, Set<String> expectedTranscripts) {
        var pairs = maneTranscriptPairs;
        var queryAllele = context.at("/variant/evidenceQueryAllele");
        if (pairs == null || !"GRCh38".equals(queryAllele.path("assembly").asText())
                || !"GRCh38".equals(context.at("/variant/inputAllele/assembly").asText())) return null;
        var pair = pairs.find(context.at("/gene/hgncId").asText(), ensembl, queryAllele.path("contig").asText());
        if (pair == null) return null;
        boolean matched = expectedTranscripts.contains(pair.refseqTranscript());
        var binding = mapper.createObjectNode().put("outcome", matched ? "MATCHED" : "RULE_TRANSCRIPT_MISMATCH")
                .put("hgncId", pair.hgncId()).put("ensemblTranscript", ensembl).put("refseqTranscript", pair.refseqTranscript())
                .put("maneStatus", pair.status()).put("referenceAccession", pair.referenceAccession()).put("sourceRow", pair.row());
        binding.set("source", pairs.identity(mapper));
        return binding;
    }

    private boolean validThreshold(JsonNode clause) {
        try {
            Set<String> supportedFields = Set.of("disposition", "reasonCode", "semanticFamily", "originalMetric", "metric", "comparator",
                    "value", "unit", "minimumAn", "minimumAnComparator", "ancestries", "conditions", "sourceRefs", "originalStatisticText",
                    "originalThresholdText", "derivedPolicyVersion", "migration", "calculationProvenance", "clauses", "requiredDataset");
            var fields = clause.fieldNames();
            while (fields.hasNext()) if (!supportedFields.contains(fields.next())) return false;
            if (clause.has("requiredDataset") && !JOINT_DATASET.equals(clause.path("requiredDataset").asText())) return false;
            var value = new java.math.BigDecimal(clause.path("value").asText());
            var an = new java.math.BigInteger(clause.path("minimumAn").asText());
            Set<String> ancestries = strings(clause.path("ancestries"));
            String originalMetric = clause.path("originalMetric").asText();
            boolean migration = "gnomad.joint.faf95max".equals(originalMetric);
            if (Set.of("MAF", "FAF").contains(originalMetric)) {
                JsonNode receipt = clause.path("migration");
                JsonNode review = document.at("/metadata/semanticReview");
                String basis = "MAF".equals(originalMetric) ? "EXPLICIT_CALCULATED_FREQUENCY_MIGRATION" : "EXPLICIT_FAF_DEFINITION_ALIGNMENT";
                migration = "REVIEWED".equals(receipt.path("status").asText()) && !receipt.path("reviewId").asText().isBlank()
                        && receipt.path("reviewSha256").asText().matches("[0-9a-f]{64}")
                        && receipt.path("reviewId").equals(review.path("reviewId"))
                        && receipt.path("reviewSha256").equals(review.path("reviewSha256"))
                        && basis.equals(receipt.path("basis").asText())
                        && includesSources(receipt.path("sources"), review.path("sources"))
                        && includesSources(receipt.path("sources"), clause.path("sourceRefs"));
            }
            boolean reciprocal = "RECIPROCAL_GRPMAX_FAF_THRESHOLD_V1".equals(clause.path("semanticFamily").asText());
            boolean family = "SIMPLE_FAF95_THRESHOLD_V1".equals(clause.path("semanticFamily").asText());
            if (reciprocal) {
                JsonNode calculation = clause.path("calculationProvenance");
                var denominator = new java.math.BigDecimal(calculation.path("reciprocalDenominator").asText());
                var percent = new java.math.BigDecimal(calculation.path("sourcePercent").asText());
                family = "FAF".equals(originalMetric) && denominator.compareTo(java.math.BigDecimal.ONE) >= 0
                        && value.multiply(denominator).compareTo(java.math.BigDecimal.ONE) == 0
                        && value.multiply(java.math.BigDecimal.valueOf(100)).compareTo(percent) == 0
                        && calculation.path("equivalentRepresentationsVerified").isBoolean() && calculation.path("equivalentRepresentationsVerified").booleanValue()
                        && calculation.path("cutoffRecomputed").isBoolean() && !calculation.path("cutoffRecomputed").booleanValue()
                        && "a01-ba1-reciprocal-faf-derived-v1".equals(clause.path("derivedPolicyVersion").asText());
            }
            Set<String> conditions = strings(clause.path("conditions"));
            boolean supportedConditions = clause.path("conditions").isArray()
                    && conditions.size() == clause.path("conditions").size()
                    && Set.of("GNOMAD_FILTERED_VARIANTS_EXCLUDED").containsAll(conditions);
            return migration && family
                    && "gnomad.joint.faf95max".equals(clause.path("metric").asText()) && "fraction".equals(clause.path("unit").asText())
                    && Set.of(GT, GTE).contains(clause.path("comparator").asText()) && value.signum() > 0 && value.compareTo(java.math.BigDecimal.ONE) <= 0
                    && Set.of(GT, GTE).contains(clause.path("minimumAnComparator").asText()) && an.compareTo(java.math.BigInteger.valueOf(2000)) >= 0
                    && !ancestries.isEmpty() && ancestries.size() == clause.path("ancestries").size()
                    && Set.of("afr", "amr", "eas", "mid", "nfe", "sas").containsAll(ancestries)
                    && supportedConditions
                    && validSources(clause.path("sourceRefs"));
        } catch (NumberFormatException invalid) { return false; }
    }

    private boolean explicitlyExcluded(JsonNode entry, JsonNode context) {
        for (JsonNode exclusion : document.path("scopeExclusions"))
            if (entry.path("specificationId").equals(exclusion.path("specificationId")) && entry.path("version").equals(exclusion.path("version"))
                    && context.at("/gene/hgncId").equals(exclusion.path("hgncId"))
                    && context.at("/disease/mondoId").equals(exclusion.path("mondoId")) && context.path("moi").equals(exclusion.path("moi"))
                    && "REVIEWED_OUTSIDE_SPECIFICATION_SCOPE".equals(exclusion.path("status").asText())
                    && validSources(exclusion.path("sourceRefs"))) return true;
        return false;
    }

    private static boolean validSources(JsonNode sources) {
        if (!sources.isArray() || sources.isEmpty()) return false;
        for (JsonNode source : sources) if (!source.path("url").asText().startsWith("https://")
                || !source.path("sha256").asText().matches("[0-9a-f]{64}")) return false;
        return true;
    }

    private static boolean includesSources(JsonNode actual, JsonNode required) {
        if (!validSources(actual) || !validSources(required)) return false;
        for (JsonNode source : required) {
            boolean present = false;
            for (JsonNode candidate : actual) {
                if (source.path("url").equals(candidate.path("url")) && source.path("sha256").equals(candidate.path("sha256"))
                        && source.path("specificationId").equals(candidate.path("specificationId"))
                        && source.path("version").equals(candidate.path("version"))
                        && source.path("jsonPointer").equals(candidate.path("jsonPointer"))) { present = true; break; }
            }
            if (!present) return false;
        }
        return true;
    }

    /** Metadata choices, not patient diagnoses. The normal declaration flow must confirm a matching clinical relation. */
    public com.fasterxml.jackson.databind.node.ArrayNode backgroundOptions(VariantInterpretation candidate) {
        var result = mapper.createArrayNode();
        if (issue != null) return result;
        var mapped = mapping(candidate);
        if (!"RESOLVED".equals(mapped.path("status").asText())) return result;
        Set<String> genes = strings(mapped.path("hgncIds"));
        for (JsonNode entry : document.path("entries")) {
            JsonNode scope = entry.path("scope");
            if (!"RESOLVED".equals(scope.path("status").asText())) continue;
            // Importer retains one original gene/disease/MOI association per entry.
            if (scope.path("hgncIds").size() != 1 || scope.path("mondoIds").size() != 1 || scope.path("moi").size() != 1) continue;
            String gene = scope.path("hgncIds").path(0).asText();
            if (!genes.contains(gene)) continue;
            ObjectNode option = result.addObject().put("hgncId", gene).put("mondoId", scope.path("mondoIds").path(0).asText())
                    .put("moi", scope.path("moi").path(0).asText()).put("specificationId", entry.path("specificationId").asText())
                    .put("version", entry.path("version").asText()).put("transcriptScope", scope.path("transcriptScope").asText());
            option.set("sources", entry.at("/ba1/sourceRefs").deepCopy());
        }
        return result;
    }

    public ObjectNode baselinePolicy() {
        JsonNode profile = GnomadPopulationInputProfile.loadExecutableV2().getDocument();
        ObjectNode policy = mapper.createObjectNode().put("policyId", "CLINGEN_SVI_GENERAL_BA1").put("policyVersion", VERSION);
        for (JsonNode criterion : profile.at("/evaluation/criteria")) if ("ACMG_AMP_2015:BA1".equals(criterion.path("criterionId").asText()))
            policy.set("frequencyRule", criterion.path("frequencyRule").deepCopy());
        ObjectNode exceptions = ((ObjectNode) policy.at("/frequencyRule/exceptionPolicy"));
        exceptions.put("schemaVersion", "ba1-exception-snapshot-v2");
        var sourceIds = exceptions.putArray("sourceEntryIds");
        for (JsonNode entry : exceptions.path("entries")) sourceIds.add(entry.path("caid").asText());
        exceptions.put("sourceEntryCount", sourceIds.size()).put("sourceEntryIdsSha256", canonicalizer.canonicalSha256(sourceIds))
                .put("evidenceAssembly", "GRCh38").put("mappingReviewStatus", "REVIEWED_COMPLETE")
                .put("mappingReviewId", "INHERITED_PINNED_E3_P1A_ALLELE_REGISTRY");
        if (document.path("exceptionPolicy").isObject()) ((ObjectNode) policy.path("frequencyRule")).set("exceptionPolicy", document.path("exceptionPolicy").deepCopy());
        policy.set("ancestries", profile.at("/fieldContract/faf95AncestryGroups").deepCopy());
        var sources = policy.putArray("sources");
        for (JsonNode id : policy.at("/frequencyRule/authoritySourceIds")) sources.addObject().put("authoritySourceId", id.asText());
        var coverage = policy.putObject("engineeringConditions").putArray("ancestryApplicability");
        for (JsonNode ancestry : policy.path("ancestries")) coverage.addObject().put("ancestry", ancestry.asText())
                .put("status", "REVIEWED_APPLICABLE").put("basis", "PINNED_CLINGEN_GNOMAD_V4_NONFOUNDER_GROUPMAX_GUIDANCE")
                .putArray("authoritySourceIds").add("authority-clingen-vcep-gnomad-v4-v3.0");
        return policy;
    }

    ObjectNode mapping(VariantInterpretation candidate) {
        ObjectNode result = mapper.createObjectNode().put("status", "UNRESOLVED")
                .put("diagnosticVersion", "ba1-gene-mapping-diagnostics-v1")
                .put("inputScope", "ALL_CANDIDATE_TRANSCRIPT_ANNOTATIONS");
        var key = candidate.getObservation().getKey();
        result.putObject("queryAllele").put("assembly", key.getGenomeAssembly()).put("contig", key.getChromosome())
                .put("position", key.getPosition()).put("reference", key.getReference()).put("alternate", key.getAlternate());
        var ids = result.putArray("hgncIds");
        var sources = result.putArray("sources");
        Set<String> unique = new LinkedHashSet<>();
        List<TranscriptConsequence> annotations = candidate.getTranscriptAnnotations();
        int total = annotations == null ? 0 : annotations.size();
        result.put("totalAnnotationCount", total).put("checkedAnnotationCount", 0).put("uncheckedAnnotationCount", total);
        if (annotations == null || annotations.isEmpty())
            return mappingFailure(result, -1, total, "BA1_MAPPING_ANNOTATIONS_MISSING", "transcriptAnnotations", mapper.createObjectNode());
        JsonNode source = document.path("geneMappingSource");
        if (!"COMPLETE".equals(source.path("status").asText()) || !source.path("rawSha256").asText().matches("[0-9a-f]{64}")
                || !source.path("url").asText().startsWith("https://") || source.path("recordCount").asInt(-1) != document.path("geneMappings").size())
            return mappingFailure(result, -1, total, "BA1_MAPPING_CATALOG_IDENTITY_INVALID", "geneMappingSource", mapper.createObjectNode());
        result.set("geneMappingSource", source.deepCopy());
        for (int index = 0; index < total; index++) {
            TranscriptConsequence annotation = annotations.get(index);
            var observed = mapper.createObjectNode();
            if (annotation == null)
                return mappingFailure(result, index, total, "BA1_MAPPING_ANNOTATION_MISSING", "annotation", observed);
            observed.put("geneId", annotation.getGeneId()).put("geneSymbol", annotation.getGeneSymbol())
                    .put("transcriptId", annotation.getTranscriptId()).put("consequence", annotation.getConsequence())
                    .put("source", annotation.getSource()).put("version", annotation.getVersion()).put("status", annotation.getStatus());
            if (annotation.getSource() == null || !annotation.getSource().startsWith("VEP"))
                return mappingFailure(result, index, total, "BA1_MAPPING_SOURCE_INVALID", "source", observed);
            if (annotation.getVersion() == null || annotation.getVersion().isBlank())
                return mappingFailure(result, index, total, "BA1_MAPPING_VERSION_MISSING", "version", observed);
            if (annotation.getStatus() == null || !Set.of("COMPLETED", "ANNOTATED", "AVAILABLE", "HIT", "SUCCESS").contains(annotation.getStatus()))
                return mappingFailure(result, index, total, "BA1_MAPPING_STATUS_INVALID", "status", observed);
            Set<String> mapped = new HashSet<>();
            Set<String> byId = ensemblGenes.getOrDefault(annotation.getGeneId(), Set.of());
            Set<String> bySymbol = symbolGenes.getOrDefault(annotation.getGeneSymbol(), Set.of());
            observed.set("ensemblMatches", mapper.valueToTree(byId.stream().sorted().toList()));
            observed.set("symbolMatches", mapper.valueToTree(bySymbol.stream().sorted().toList()));
            if (annotation.getGeneId() != null && annotation.getGeneId().startsWith("ENSG") && byId.isEmpty())
                return mappingFailure(result, index, total, "BA1_MAPPING_ENSEMBL_NOT_FOUND", "geneId", observed);
            mapped.addAll(byId); mapped.addAll(bySymbol);
            if (mapped.isEmpty()) {
                boolean missing = (annotation.getGeneId() == null || annotation.getGeneId().isBlank())
                        && (annotation.getGeneSymbol() == null || annotation.getGeneSymbol().isBlank());
                return mappingFailure(result, index, total, missing ? "BA1_MAPPING_IDENTIFIERS_MISSING" : "BA1_MAPPING_NOT_FOUND", "geneId/geneSymbol", observed);
            }
            if (mapped.size() != 1)
                return mappingFailure(result, index, total, byId.size() == 1 && bySymbol.size() == 1
                        ? "BA1_MAPPING_IDENTIFIER_CONFLICT" : "BA1_MAPPING_AMBIGUOUS", "geneId/geneSymbol", observed);
            if (!mapped.iterator().next().matches("HGNC:[0-9]+"))
                return mappingFailure(result, index, total, "BA1_MAPPING_HGNC_INVALID", "hgncId", observed);
            unique.addAll(mapped);
            sources.addObject().put("annotationIndex", index).put("source", annotation.getSource()).put("version", annotation.getVersion())
                    .put("geneId", annotation.getGeneId()).put("geneSymbol", annotation.getGeneSymbol())
                    .put("mappingBasis", !byId.isEmpty() && !bySymbol.isEmpty() ? "CONCORDANT_ENSEMBL_AND_APPROVED_SYMBOL"
                            : !byId.isEmpty() ? "ENSEMBL_GENE_ID" : "APPROVED_SYMBOL")
                    .put("symbolResolved", !bySymbol.isEmpty());
        }
        unique.forEach(ids::add);
        result.put("checkedAnnotationCount", total).put("uncheckedAnnotationCount", 0);
        return result.put("status", unique.isEmpty() ? "UNRESOLVED" : "RESOLVED");
    }

    private ObjectNode mappingFailure(ObjectNode result, int index, int total, String reason, String field, ObjectNode observed) {
        int checked = index < 0 ? 0 : index + 1;
        result.put("checkedAnnotationCount", checked).put("uncheckedAnnotationCount", total - checked);
        result.set("failure", observed.put("annotationIndex", index).put("reasonCode", reason).put("field", field));
        // Successful source rows are partial evidence only; do not publish a complete HGNC set after early exit.
        return result;
    }

    private ObjectNode withEntry(ObjectNode result, JsonNode entry) {
        result.set("specification", entry.deepCopy());
        for (JsonNode source : entry.at("/ba1/sourceRefs")) ((com.fasterxml.jackson.databind.node.ArrayNode) result.path("sources")).add(source.deepCopy());
        if (result.has("selectionId")) {
            result.remove("selectionId");
            result.put("selectionId", "ba1-selection-sha256-" + canonicalizer.canonicalSha256(result));
        }
        return result;
    }
    private ObjectNode finish(ObjectNode result, String status, String reason, String missing) {
        result.put("status", status).put("reasonCode", reason);
        if (missing != null) for (String field : missing.split(","))
            ((com.fasterxml.jackson.databind.node.ArrayNode) result.path("requiredInputs")).addObject().put("field", field).put("reason", reason);
        result.remove("selectionId");
        result.put("selectionId", "ba1-selection-sha256-" + canonicalizer.canonicalSha256(result));
        return result;
    }
    private static Set<String> strings(JsonNode array) {
        Set<String> result = new LinkedHashSet<>();
        for (JsonNode value : array) if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText());
        return result;
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
