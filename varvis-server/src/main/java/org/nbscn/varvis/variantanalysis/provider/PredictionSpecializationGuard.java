package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/** Reuses the complete local CSpec inventory ONLY to block potential overrides, never BA1 thresholds. */
public final class PredictionSpecializationGuard {
    private final ObjectNode identity;
    private final Set<String> genes;
    private final java.util.Map<String, java.util.List<ObjectNode>> entries;
    private final java.util.Map<String, JsonNode> originals;
    private final java.util.Map<String, String> rawDocuments;
    @org.springframework.lang.Nullable private PublishedPredictionCatalog releasedCatalog;

    /** Configuration attaches this before the guard is shared by request runtimes. */
    public PredictionSpecializationGuard withReleasedCatalog(PublishedPredictionCatalog catalog) {
        var copy = new PredictionSpecializationGuard(identity, genes, entries, originals, rawDocuments);
        copy.releasedCatalog = java.util.Objects.requireNonNull(catalog);
        return copy;
    }

    public static PredictionSpecializationGuard load(ObjectMapper mapper, Path path, String sha) throws IOException {
        return load(mapper, path, sha, null);
    }

    /** Raw entities are optional, but must match the existing pinned inventory before narrowing scope. */
    public static PredictionSpecializationGuard load(ObjectMapper mapper, Path path, String sha, Path sourceDirectory) throws IOException {
        if (Files.size(path) > 64L * 1024 * 1024) throw new IllegalArgumentException("PREDICTION_SPEC_CATALOG_SIZE_LIMIT");
        byte[] bytes = Files.readAllBytes(path);
        if (!PredictionCalibration.sha256(bytes).equals(sha)) throw new IllegalArgumentException("PREDICTION_SPEC_CATALOG_IDENTITY_MISMATCH");
        JsonNode data = mapper.readTree(bytes);
        if (data == null || !Ba1SpecificationCatalog.SCHEMA.equals(data.path("schemaVersion").asText())
                || !"COMPLETE".equals(data.at("/metadata/status").asText()) || !data.path("entries").isArray()
                || data.path("entries").size() != data.at("/metadata/entryCount").asInt(-1))
            throw new IllegalArgumentException("PREDICTION_SPEC_CATALOG_INCOMPLETE");
        var genes = new HashSet<String>();
        var entries = new java.util.HashMap<String, java.util.List<ObjectNode>>();
        var originals = new java.util.HashMap<String, JsonNode>();
        var rawCache = new java.util.HashMap<String, JsonNode>();
        var rawDocuments = new java.util.HashMap<String, String>();
        for (var entry : data.path("entries")) {
            var ids = entry.at("/scope/hgncIds");
            if (!ids.isArray()) throw new IllegalArgumentException("PREDICTION_SPEC_SCOPE_UNKNOWN");
            if (ids.isEmpty() && !"MITOCHONDRIAL".equals(entry.at("/scope/sequenceScope").asText()))
                throw new IllegalArgumentException("PREDICTION_SPEC_SCOPE_UNKNOWN");
            for (var id : ids) {
                if (!id.asText().matches("HGNC:[0-9]+")) throw new IllegalArgumentException("PREDICTION_SPEC_SCOPE_UNKNOWN");
                genes.add(id.asText());
                entries.computeIfAbsent(id.asText(), ignored -> new java.util.ArrayList<>()).add(((ObjectNode) entry).deepCopy());
            }
            if (sourceDirectory != null) {
                var source = entry.path("source");
                String ref = source.path("rawRef").asText(), digest = source.path("rawSha256").asText();
                String pointer = source.path("jsonPointer").asText(), key = entry.path("specificationId").asText() + "@" + entry.path("version").asText();
                if (!ref.matches("(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_.-]+\\.json") || !digest.matches("[0-9a-f]{64}") || !pointer.matches("/data(?:/[0-9]+)?"))
                    throw new IllegalArgumentException("PREDICTION_SPEC_SOURCE_BINDING_INVALID");
                String rawKey = ref + ":" + digest;
                JsonNode raw = rawCache.get(rawKey);
                if (raw == null) {
                    Path file = sourceDirectory.resolve(ref);
                    if (!Files.isRegularFile(file) || Files.size(file) > 64L * 1024 * 1024)
                        throw new IllegalArgumentException("PREDICTION_SPEC_SOURCE_UNAVAILABLE");
                    byte[] content = Files.readAllBytes(file);
                    if (!digest.equals(PredictionCalibration.sha256(content))) throw new IllegalArgumentException("PREDICTION_SPEC_SOURCE_IDENTITY_MISMATCH");
                    raw = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(content);
                    rawCache.put(rawKey, raw);
                    rawDocuments.put(digest, java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                            .decode(java.nio.ByteBuffer.wrap(content)).toString());
                }
                JsonNode original = raw.at(pointer);
                var states = new java.util.ArrayList<String>();
                for (var state : original.at("/entContent/states")) if (state.path("current").asBoolean()) states.add(state.path("name").asText());
                if (!key.equals(original.path("entId").asText() + "@" + original.at("/entContent/version").asText())
                        || !states.equals(java.util.List.of("Released")) || !original.at("/ld/CriteriaCode").isArray()
                        || !original.at("/ld/RuleSet").isArray()) throw new IllegalArgumentException("PREDICTION_SPEC_ENTITY_IDENTITY_MISMATCH");
                var previous = originals.putIfAbsent(key, original.deepCopy());
                if (previous != null && !previous.equals(original)) throw new IllegalArgumentException("PREDICTION_SPEC_SOURCE_CONFLICT");
            }
        }
        var identity = mapper.createObjectNode().put("catalogSha256", sha).put("snapshotId", data.at("/metadata/snapshotId").asText())
                .put("scope", "PINNED_LOCAL_CSPEC_GENE_INVENTORY_POTENTIAL_OVERRIDE_ONLY").put("genes", genes.size())
                .put("worldwideAbsenceClaim", false).put("ba1ThresholdsUsed", false);
        identity.put("scopeSelectionVersion", "2.0.0").put("sourceEntitiesVerified", originals.size())
                .put("referenceOriginalScopeVersion", "2.0.0").put("historicalOriginalRuleLookupVersion", "1.0.0");
        return new PredictionSpecializationGuard(identity, genes, entries, originals, rawDocuments);
    }

    private PredictionSpecializationGuard(ObjectNode identity, Set<String> genes,
            java.util.Map<String, java.util.List<ObjectNode>> entries, java.util.Map<String, JsonNode> originals,
            java.util.Map<String, String> rawDocuments) {
        this.identity = identity.deepCopy(); this.genes = Set.copyOf(genes);
        this.entries = entries; this.originals = originals;
        this.rawDocuments = java.util.Map.copyOf(rawDocuments);
    }

    /** Reuses source identity, never the BA1 thresholds or prediction rules. */
    public ObjectNode startLossAssertions(ObjectMapper mapper, JsonNode context) {
        return startLossAssertions(mapper, context, null);
    }

    /** Planning identities only; each original clause is still independently
     * scoped and replayed before any source-based conclusion may be adopted. */
    Set<String> startLossTranscriptCandidates(JsonNode context) {
        var out = new java.util.LinkedHashSet<String>();
        for (var entry : entries.getOrDefault(context.at("/gene/hgncId").asText(), java.util.List.of()))
            if ("EXACT".equals(entry.at("/scope/transcriptScope").asText())) for (var tx : entry.at("/scope/transcripts"))
                if (tx.asText().matches("NM_[0-9]+\\.[1-9][0-9]*")) out.add(tx.asText());
        return java.util.Collections.unmodifiableSet(out);
    }

    ObjectNode startLossAssertions(ObjectMapper mapper, JsonNode context,
            @org.springframework.lang.Nullable Pvs1StartLossDiseaseScope diseaseScope) {
        var out = mapper.createObjectNode().put("status", "NO_KNOWN_SPECIALIZATION_IN_LOCAL_SNAPSHOT");
        out.set("catalogIdentity", identity()); var results = out.putArray("entries");
        var documents = out.putObject("sourceDocuments"); var seen = new HashSet<String>();
        for (var entry : entries.getOrDefault(context.at("/gene/hgncId").asText(), java.util.List.of())) {
            String key = entry.path("specificationId").asText() + "@" + entry.path("version").asText();
            // Entries may split disease/transcript scopes. Preserve those separate
            // contexts even when they point to the same native entity.
            String entryKey = key + ":" + entry.path("entryId").asText();
            if (!seen.add(entryKey)) continue;
            var original = originals.get(key);
            var finding = original == null ? mapper.createObjectNode().put("status", "UNRESOLVED")
                    .put("reasonCode", "EXPERT_ORIGINAL_UNAVAILABLE") : Pvs1StartLossExpertFacts.read(mapper, original, entry.path("scope"), context, diseaseScope);
            if ("OUTSIDE_EXPLICIT_SCOPE".equals(finding.path("scopeRelation").asText())) continue;
            if (original != null) {
                var rule = Pvs1StartLossPublishedRule.read(mapper, original, context, finding);
                finding.set("startLossRule", rule);
                if ("CONFLICT".equals(rule.path("status").asText())) finding.put("status", "CONFLICT").put("reasonCode", rule.path("reasonCode").asText());
            }
            finding.put("specificationKey", key); finding.set("source", entry.path("source").deepCopy()); results.add(finding);
            var source = entry.path("source"); String sha = source.path("rawSha256").asText();
            String raw = rawDocuments.get(sha);
            if (raw != null && !documents.has(sha)) documents.putObject(sha).put("rawJson", raw).set("receipt", source.deepCopy());
        }
        if (!results.isEmpty()) out.put("status", "SPECIALIST_SCOPE_REQUIRES_SELECTION");
        return out;
    }

    /** Both criteria share prediction acquisition; either applicable override protects that shared route. */
    ObjectNode checkPredictions(JsonNode context) {
        if (releasedCatalog != null) {
            var resolution = releasedCatalog.select(context);
            if ("PUBLISHED_REVEL_MISSENSE".equals(resolution.path("status").asText()))
                resolution.put("status", "BLOCKED").put("reasonCode", "PREDICTION_PUBLISHED_POLICY_REQUIRED");
            return resolution;
        }
        var out = check(context);
        if (!"BLOCKED".equals(out.path("status").asText())) return out;
        var details = out.putArray("scopeChecks");
        boolean blocked = false;
        var seen = new HashSet<String>();
        for (var entry : entries.getOrDefault(context.at("/gene/hgncId").asText(), java.util.List.of())) {
            String key = entry.path("specificationId").asText() + "@" + entry.path("version").asText();
            if (!seen.add(key)) continue;
            var original = originals.get(key);
            for (String criterion : java.util.List.of("PP3", "BP4")) {
                String relation = original == null ? "SOURCE_NOT_AVAILABLE" : PredictionSpecificationScope.relation(original, context, criterion);
                var detail = details.addObject().put("specificationKey", key).put("criterion", criterion).put("relation", relation);
                detail.set("source", entry.path("source").deepCopy());
                var pointers = detail.putArray("clausePointers");
                if (original != null) for (int i = 0; i < original.at("/ld/CriteriaCode").size(); i++)
                    if (criterion.equals(original.at("/ld/CriteriaCode/" + i + "/entContent/label").asText()))
                        pointers.add("/ld/CriteriaCode/" + i);
                blocked |= !"OUTSIDE_EXPLICIT_SCOPE".equals(relation);
            }
        }
        out.put("selectionBasis", "RAW_CRITERION_AND_RULESET_SCOPE_ONLY");
        return out.put("status", blocked ? "BLOCKED" : "NO_KNOWN_SPECIALIZATION_IN_LOCAL_SNAPSHOT")
                .put("reasonCode", blocked ? "PREDICTION_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION" : "GENERAL_REVEL_LOCAL_SPEC_SEARCH_COMPLETE");
    }

    ObjectNode check(JsonNode context) {
        var out = identity.deepCopy();
        boolean known = genes.contains(context.at("/gene/hgncId").asText());
        return out.put("status", known ? "BLOCKED" : "NO_KNOWN_SPECIALIZATION_IN_LOCAL_SNAPSHOT")
                .put("reasonCode", known ? "PREDICTION_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION" : "GENERAL_REVEL_LOCAL_SPEC_SEARCH_COMPLETE");
    }
    public ObjectNode identity() { return identity.deepCopy(); }

    /** Only this raw-source loader can construct a bound reference specification. */
    static final class ReferenceSpecification {
        private final JsonNode entity, source;
        private final String raw;
        private ReferenceSpecification(JsonNode entity, JsonNode source, String raw) {
            this.entity = entity.deepCopy(); this.source = source.deepCopy(); this.raw = raw;
        }
        JsonNode entity() { return entity.deepCopy(); }
        JsonNode source() { return source.deepCopy(); }
        String raw() { return raw; }
        boolean matchesMethod(JsonNode method) {
            return method.path("@id").asText().equals("https://cspec.genome.network/cspec/SequenceVariantInterpretation/id/" + entity.path("ldhId").asText())
                    && !normalizedRelease(method.path("version").asText()).isEmpty()
                    && normalizedRelease(method.path("version").asText()).equals(normalizedRelease(entity.at("/entContent/version").asText()));
        }
    }

    @org.springframework.lang.Nullable
    ReferenceSpecification referenceSpecification(JsonNode context, JsonNode assertionMethod) {
        ReferenceSpecification found = null;
        String methodId = assertionMethod.path("@id").asText();
        String version = normalizedRelease(assertionMethod.path("version").asText());
        if (version.isEmpty()) return null;
        for (var entry : entries.getOrDefault(context.at("/gene/hgncId").asText(), java.util.List.of())) {
            String key = entry.path("specificationId").asText() + "@" + entry.path("version").asText();
            JsonNode original = originals.get(key), scope = entry.path("scope");
            if (original == null || !version.equals(normalizedRelease(original.at("/entContent/version").asText()))
                    || !methodId.equals("https://cspec.genome.network/cspec/SequenceVariantInterpretation/id/" + original.path("ldhId").asText())
                    || !"RESOLVED".equals(scope.path("status").asText())
                    || !includes(scope.path("hgncIds"), context.at("/gene/hgncId").asText())
                    || !includes(scope.path("mondoIds"), context.at("/disease/mondoId").asText())
                    || !includes(scope.path("moi"), context.path("moi").asText())
                    || !"EXACT".equals(scope.path("transcriptScope").asText())
                    || !includes(scope.path("transcripts"), context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText())) continue;
            String raw = rawDocuments.get(entry.at("/source/rawSha256").asText());
            if (raw == null) return null;
            if (found != null && !found.entity().equals(original)) return null;
            found = new ReferenceSpecification(original, entry.path("source"), raw);
        }
        return found;
    }

    /** A missing inheritance field in the guideline is not a contradictory
     * inheritance assertion. For replay of an EXISTING expert interpretation,
     * bind the original official summary's explicit MOI and retain that join.
     * This does not supply disease mechanism or adopt a rule for a new target. */
    @org.springframework.lang.Nullable
    ReferenceSpecification referenceSpecification(JsonNode context, JsonNode assertionMethod, JsonNode summary) {
        if (!referenceSummaryMatches(context, summary)) return null;
        var strict = referenceSpecification(context, assertionMethod);
        if (strict != null) {
            var source = referenceSource(strict.source(), strict.entity(), context, summary);
            return source == null ? null : new ReferenceSpecification(strict.entity(), source, strict.raw());
        }
        ReferenceSpecification found = null;
        for (var entry : entries.getOrDefault(context.at("/gene/hgncId").asText(), java.util.List.of())) {
            var entity = originals.get(entry.path("specificationId").asText() + "@" + entry.path("version").asText());
            if (entity == null || !referenceAnchor(entry, entity, context, assertionMethod)) continue;
            if (!normalizedRelease(assertionMethod.path("version").asText()).equals(normalizedRelease(entity.at("/entContent/version").asText()))) continue;
            var source = referenceSource(entry.path("source"), entity, context, summary);
            String raw = rawDocuments.get(entry.at("/source/rawSha256").asText());
            if (source == null || raw == null) continue;
            if (found != null && !found.entity().equals(entity)) return null;
            found = new ReferenceSpecification(entity, source, raw);
        }
        return found;
    }

    /** Fetches only the exact released version named by an existing source
     * interpretation. Current catalog data anchors identity/scope, never replaces
     * historical rule contents. Raw version, bytes and provenance are retained. */
    @org.springframework.lang.Nullable
    ReferenceSpecification historicalReferenceSpecification(JsonNode context, JsonNode assertionMethod, JsonNode summary,
            Pvs1StartLossPublicSources sources) {
        if (!referenceSummaryMatches(context, summary)) return null;
        String requested = normalizedRelease(assertionMethod.path("version").asText()); if (requested.isEmpty()) return null;
        ReferenceSpecification found = null;
        for (var entry : entries.getOrDefault(context.at("/gene/hgncId").asText(), java.util.List.of())) {
            var current = originals.get(entry.path("specificationId").asText() + "@" + entry.path("version").asText());
            if (current == null || !referenceAnchor(entry, current, context, assertionMethod)) continue;
            var snapshot = sources.cspecVersion(entry.path("specificationId").asText(), requested);
            var receipt = snapshot.receipt(); if (!"AVAILABLE".equals(receipt.path("status").asText())) continue;
            String sha = receipt.path("sha256").asText(), raw = sources.originals().path(sha).path("rawJson").asText();
            if (raw.isEmpty() || !sha.equals(CodingTranscriptMaterialSupply.sha(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)))) continue;
            var document = snapshot.document(); var entity = document.path("data");
            if (!document.at("/status/code").isIntegralNumber() || document.at("/status/code").asInt() != 200
                    || !entity.isObject() || !"SequenceVariantInterpretation".equals(entity.path("entType").asText())
                    || !entry.path("specificationId").equals(entity.path("entId"))
                    || !entry.path("specificationId").equals(entity.at("/entContent/namespace"))
                    || !current.path("ldhId").equals(entity.path("ldhId"))
                    || !requested.equals(normalizedRelease(entity.at("/entContent/version").asText()))) continue;
            var states = new java.util.ArrayList<String>();
            for (var state : entity.at("/entContent/states")) if (state.path("current").asBoolean()) states.add(state.path("name").asText());
            if (!states.equals(java.util.List.of("Released")) || !entity.at("/ld/RuleSet").isArray() || !entity.at("/ld/CriteriaCode").isArray()) continue;
            var source = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("rawSha256", sha).put("sourceUrl", receipt.path("sourceUrl").asText()).put("jsonPointer", "/data")
                    .put("specificationId", entry.path("specificationId").asText()).put("declaredVersion", entity.at("/entContent/version").asText())
                    .put("kind", "EXACT_RELEASED_ORIGINAL_INTERPRETATION_GUIDELINE")
                    .put("currentCatalogAnchorSha256", entry.at("/source/rawSha256").asText()).put("usedCurrentGuidelineRules", false);
            var qualified = referenceSource(source, entity, context, summary); if (qualified == null) continue;
            if (found != null && !found.entity().equals(entity)) return null;
            found = new ReferenceSpecification(entity, qualified, raw);
        }
        return found;
    }

    private static boolean referenceAnchor(JsonNode entry, JsonNode entity, JsonNode context, JsonNode method) {
        var scope = entry.path("scope");
        return !normalizedRelease(method.path("version").asText()).isEmpty()
                && method.path("@id").asText().equals("https://cspec.genome.network/cspec/SequenceVariantInterpretation/id/" + entity.path("ldhId").asText())
                && includes(scope.path("hgncIds"), context.at("/gene/hgncId").asText())
                && includes(scope.path("mondoIds"), context.at("/disease/mondoId").asText())
                && scope.path("moi").isArray() && (scope.path("moi").isEmpty()
                    || java.util.stream.StreamSupport.stream(scope.path("moi").spliterator(), false)
                        .anyMatch(mode -> modesAgree(context.path("moi").asText(), mode.asText())))
                && "EXACT".equals(scope.path("transcriptScope").asText())
                && includes(scope.path("transcripts"), context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText());
    }

    @org.springframework.lang.Nullable
    private static ObjectNode referenceSource(JsonNode source, JsonNode entity, JsonNode context, JsonNode summary) {
        String symbol = context.at("/gene/symbol").asText(), disease = context.at("/disease/mondoId").asText();
        String transcript = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
        String mode = sourceMode(summary.path("moi").asText());
        if (symbol.isBlank() || !symbol.equals(summary.path("gene").asText()) || !disease.equals(summary.path("mondoId").asText())
                || !modesAgree(context.path("moi").asText(), mode) || !source.isObject()) return null;
        boolean geneIdentity = false;
        for (var gene : entity.at("/ld/Gene")) if (context.at("/gene/hgncId").equals(gene.at("/entContent/HGNC/hgnc_id"))
                && symbol.equals(gene.at("/entContent/HGNC/symbol").asText())) geneIdentity = true;
        if (!geneIdentity) return null;
        int matched = 0; boolean joined = false;
        for (var rule : entity.at("/ld/RuleSet")) for (var gene : rule.at("/entContent/genes"))
            if (symbol.equals(gene.path("gene").asText()) && transcript.equals(gene.path("preferredTranscript").asText()))
                for (var item : gene.path("diseases")) if (disease.equals(item.path("preferredMondoId").asText())) {
                    matched++;
                    if (!item.has("preferredModeOfInheritance") && !item.has("modesOfInheritance")) joined = true;
                    else {
                        if (!modesAgree(context.path("moi").asText(), sourceMode(item.path("preferredModeOfInheritance").asText()))) return null;
                        for (var other : item.path("modesOfInheritance")) if (!modesAgree(context.path("moi").asText(), sourceMode(other.path("modeOfInheritance").asText()))) return null;
                    }
                }
        if (matched != 1) return null;
        var out = ((ObjectNode) source).deepCopy();
        if (joined) out.putObject("inheritanceScopeJoin").put("basis", "ORIGINAL_EXPERT_SUMMARY_EXPLICIT_INHERITANCE")
                .put("recordId", summary.path("uuid").asText()).put("sourceLocator", "/moi").put("sourceText", summary.path("moi").asText())
                .put("mode", mode).put("requestedMode", context.path("moi").asText()).put("originalGuidelineModified", false).put("newTargetPolicyAdopted", false);
        return out;
    }
    private static boolean referenceSummaryMatches(JsonNode context, JsonNode summary) {
        return !context.at("/gene/symbol").asText().isBlank() && context.at("/gene/symbol").equals(summary.path("gene"))
                && context.at("/disease/mondoId").equals(summary.path("mondoId"))
                && modesAgree(context.path("moi").asText(), sourceMode(summary.path("moi").asText()));
    }
    private static boolean modesAgree(String requested, String source) {
        return !source.isEmpty() && (requested.equals(source) || "X_LINKED".equals(source)
                && Set.of("X_LINKED_DOMINANT", "X_LINKED_RECESSIVE").contains(requested));
    }
    private static String sourceMode(String text) {
        return switch (text) {
            case "Autosomal dominant inheritance", "Autosomal Dominant" -> "AUTOSOMAL_DOMINANT";
            case "Autosomal recessive inheritance", "Autosomal Recessive" -> "AUTOSOMAL_RECESSIVE";
            case "X-linked inheritance" -> "X_LINKED";
            case "X-linked dominant inheritance" -> "X_LINKED_DOMINANT";
            case "X-linked recessive inheritance" -> "X_LINKED_RECESSIVE";
            default -> "";
        };
    }
    private static boolean includes(JsonNode list, String value) {
        if (list.isArray()) for (var item : list) if (item.isTextual() && item.asText().equals(value)) return true;
        return false;
    }
    private static String normalizedRelease(String version) {
        if (!version.matches("[0-9]{1,4}\\.[0-9]{1,4}(?:\\.[0-9]{1,4})?")) return "";
        return version.split("\\.").length == 2 ? version + ".0" : version;
    }
}
