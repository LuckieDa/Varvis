package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.lang.Nullable;

/** Pinned source facts plus separate project adoption. Neither a G2P label nor this loader grants approval. */
public final class Pvs1KnowledgeCatalog {
    public static final String VERSION = "1.1.0";
    private final boolean policySourcesMode;
    private final boolean sourceQualifiedMode;
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final ObjectNode identity, adoption;
    @Nullable private final Pvs1ApplicabilitySources applicabilitySources;
    private final Map<String, List<ObjectNode>> backgrounds = new LinkedHashMap<>();

    public static Pvs1KnowledgeCatalog load(ObjectMapper mapper, Path facts, String factsSha,
            Path adoption, String adoptionSha) throws IOException {
        var strict = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return new Pvs1KnowledgeCatalog(mapper, read(strict, facts, factsSha, 16_000_000), factsSha,
                read(strict, adoption, adoptionSha, 8_000_000), adoptionSha);
    }

    Pvs1KnowledgeCatalog(ObjectMapper mapper, ObjectNode facts, String factsSha, ObjectNode adoption, String adoptionSha) {
        this(mapper, facts, factsSha, adoption, adoptionSha, null);
    }

    public static Pvs1KnowledgeCatalog load(ObjectMapper mapper, Path facts, String factsSha,
            Path adoption, String adoptionSha, Path sources, String sourcesSha) throws IOException {
        var strict = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return new Pvs1KnowledgeCatalog(mapper, read(strict, facts, factsSha, 16_000_000), factsSha,
                read(strict, adoption, adoptionSha, 8_000_000), adoptionSha,
                Pvs1ApplicabilitySources.load(mapper, sources, sourcesSha, factsSha));
    }

    Pvs1KnowledgeCatalog(ObjectMapper mapper, ObjectNode facts, String factsSha, ObjectNode adoption, String adoptionSha,
            @Nullable Pvs1ApplicabilitySources applicabilitySources) {
        this.applicabilitySources = applicabilitySources;
        sourceQualifiedMode = Pvs1SourceQualification.SCHEMA.equals(adoption.path("schemaVersion").asText());
        policySourcesMode = sourceQualifiedMode || "pvs1-knowledge-adoption-v2".equals(adoption.path("schemaVersion").asText());
        this.mapper = mapper; adapter = new RuntimeEvidenceV2Adapter(mapper); this.adoption = adoption.deepCopy();
        require("pvs1-mechanism-source-candidates-v1".equals(facts.path("schemaVersion").asText())
                && "1.0.0".equals(facts.path("methodVersion").asText())
                && "UNREVIEWED".equals(facts.path("status").asText()) && isFalse(facts.path("runtimeEligible"))
                && isFalse(facts.path("clinicalAcceptance")) && facts.path("records").isArray()
                && !facts.path("records").isEmpty() && facts.path("records").size() <= 20_000
                && facts.path("records").size() == facts.at("/counts/sourceRecords").asInt()
                && facts.at("/source/sha256").asText().matches("[0-9a-f]{64}")
                && text(facts.at("/source/version")), "PVS1_KNOWLEDGE_SOURCE_INVALID");
        var catalogFields = new HashSet<>(Set.of("schemaVersion", "catalogId", "catalogVersion", "sourceCandidatesSha256", "sourceDataClass", "entries"));
        if (sourceQualifiedMode) catalogFields.add("qualificationPolicy");
        require(only(adoption, catalogFields)
                && (policySourcesMode || "pvs1-knowledge-adoption-v1".equals(adoption.path("schemaVersion").asText()))
                && text(adoption.path("catalogId")) && text(adoption.path("catalogVersion"))
                && factsSha.equals(adoption.path("sourceCandidatesSha256").asText())
                && Set.of("SYNTHETIC", "PUBLIC_REFERENCE").contains(adoption.path("sourceDataClass").asText())
                && adoption.path("entries").isArray() && adoption.path("entries").size() <= 20_000,
                "PVS1_KNOWLEDGE_ADOPTION_IDENTITY_INVALID");
        require(!sourceQualifiedMode || (Pvs1SourceQualification.policyValid(adoption.path("qualificationPolicy"))
                && "PUBLIC_REFERENCE".equals(adoption.path("sourceDataClass").asText())), "PVS1_SOURCE_QUALIFICATION_POLICY_INVALID");
        Set<String> ids = new HashSet<>(); int ordinal = 0;
        for (var item : facts.path("records")) {
            ordinal++;
            require(item instanceof ObjectNode && item.path("sourceRecord").isObject()
                    && item.path("sourceRow").isIntegralNumber() && item.path("sourceRow").canConvertToInt() && item.path("sourceRow").asInt() == ordinal
                    && item.at("/sourceRecord/g2p id").asText().strip().matches("G2P[0-9]+")
                    && item.path("recordId").asText().equals(item.at("/sourceRecord/g2p id").asText().strip() + ":" + ordinal)
                    && ids.add(item.path("recordId").asText()) && "UNREVIEWED".equals(item.path("status").asText())
                    && isFalse(item.path("runtimeEligible"))
                    && sourceRecordSha(mapper, item.path("sourceRecord")).equals(item.path("sourceRecordSha256").asText()),
                    "PVS1_KNOWLEDGE_SOURCE_RECORD_INVALID");
            var raw = item.path("sourceRecord");
            String hgnc = raw.path("hgnc id").asText().strip();
            if (hgnc.matches("[1-9][0-9]*")) hgnc = "HGNC:" + hgnc;
            String mondo = raw.path("disease MONDO").asText().strip();
            // Derive lookup from original fields, not potentially inconsistent convenience projections.
            if (hgnc.matches("HGNC:[1-9][0-9]*") && mondo.matches("MONDO:[0-9]{7}"))
                backgrounds.computeIfAbsent(hgnc + "|" + mondo, ignored -> new ArrayList<>()).add(((ObjectNode) item).deepCopy());
        }
        ids.clear();
        var entryFields = new HashSet<>(Set.of("entryId", "scope", "sourceRecordSetSha256", "policySha256",
                "modelSha256", "exonNmdScopeSha256", "mechanismStatus", "transcriptRelevance", "review", "purpose", "allowedDataClasses"));
        if (policySourcesMode) entryFields.add("mechanismEvidence");
        if (sourceQualifiedMode) { entryFields.remove("review"); entryFields.add("qualification"); }
        for (var entry : adoption.path("entries")) require(only(entry, entryFields)
                && (!policySourcesMode || (only(entry.path("mechanismEvidence"), Set.of("type", "policyReviewId", "sourceIds", "sourceSetSha256"))
                    && "REVIEWED_POLICY_ORIGINAL_SOURCES".equals(entry.at("/mechanismEvidence/type").asText())
                    && text(entry.at("/mechanismEvidence/policyReviewId")) && entry.at("/mechanismEvidence/sourceIds").isArray()
                    && !entry.at("/mechanismEvidence/sourceIds").isEmpty()
                    && entry.at("/mechanismEvidence/sourceSetSha256").asText().matches("[0-9a-f]{64}")))
                && only(entry.path("scope"), Set.of("hgncId", "mondoId", "moi", "assembly", "transcript"))
                && (sourceQualifiedMode ? Pvs1SourceQualification.entryValid(entry.path("qualification"))
                    : only(entry.path("review"), Set.of("status", "reviewId", "reviewedBy", "reviewedAt", "validUntil", "basis")))
                && text(entry.path("entryId")) && ids.add(entry.path("entryId").asText()), "PVS1_KNOWLEDGE_ADOPTION_ENTRY_INVALID");
        identity = mapper.createObjectNode().put("schemaVersion", "pvs1-knowledge-binding-v1").put("version", sourceQualifiedMode ? "1.2.0" : policySourcesMode ? VERSION : "1.0.0")
                .put("sourceCandidatesRawSha256", factsSha).put("adoptionRawSha256", adoptionSha)
                .put("sourceRecords", facts.path("records").size()).put("backgrounds", backgrounds.size())
                .put("catalogId", adoption.path("catalogId").asText()).put("catalogVersion", adoption.path("catalogVersion").asText())
                .put("sourceDataClass", adoption.path("sourceDataClass").asText()).put("clinicalAcceptance", false);
        identity.set("source", facts.path("source").deepCopy());
        if (sourceQualifiedMode) identity.set("qualificationPolicy", adoption.path("qualificationPolicy").deepCopy());
        if (applicabilitySources != null) identity.set("applicabilitySources", applicabilitySources.identity());
    }

    public ObjectNode identity() { return identity.deepCopy().put("startLossAssociationVersion", "1.0.0"); }
    boolean sourceQualified() { return sourceQualifiedMode; }

    /** A distinct prerequisite read; does not reuse NMD adoption as start-loss permission. */
    ObjectNode startLossMechanism(JsonNode context) {
        String key = context.at("/gene/hgncId").asText() + "|" + context.at("/disease/mondoId").asText();
        return Pvs1StartLossMechanism.assess(mapper, context, backgrounds.getOrDefault(key, List.of()), identity());
    }

    /** A curated disease association is distinct from G2P's mechanism support.
     * Only this pinned catalog can create the typed source-qualified anchor. */
    static final class StartLossAssociation {
        private final ObjectNode facts;
        private StartLossAssociation(ObjectNode facts) { this.facts = facts.deepCopy(); }
        ObjectNode snapshot() { return facts.deepCopy(); }
        boolean qualified() { return "CURATED_ASSOCIATION_SUPPORTED".equals(facts.path("status").asText()); }
    }

    StartLossAssociation startLossAssociation(JsonNode context) {
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-association-v1").put("methodVersion", "1.0.0")
                .put("status", "UNRESOLVED").put("basis", "PINNED_G2P_EXACT_DISEASE_ASSOCIATION")
                .put("reasonCode", "PVS1_START_LOSS_ASSOCIATION_UNRESOLVED").put("runtimeEligible", false)
                .put("mechanismEstablished", false).put("transcriptRelevanceEstablished", false)
                .put("contextSha256", Pvs1StartLossRuntimeMaterial.contextIdentitySha256(adapter, context));
        out.set("binding", identity()); var selected = out.putArray("records");
        if (!"PUBLIC_REFERENCE".equals(identity.path("sourceDataClass").asText())
                || !"https://www.ebi.ac.uk/gene2phenotype/api/panel/all/download/".equals(identity.at("/source/url").asText())
                || !"AUTOSOMAL_DOMINANT".equals(context.path("moi").asText())) return new StartLossAssociation(out);
        String key = context.at("/gene/hgncId").asText() + "|" + context.at("/disease/mondoId").asText();
        var records = backgrounds.getOrDefault(key, List.of()).stream().filter(row ->
                "monoallelic_autosomal".equals(row.at("/sourceRecord/allelic requirement").asText().strip())).toList();
        if (records.isEmpty() || records.size() > 20) return new StartLossAssociation(out);
        for (var row : records) {
            var raw = row.path("sourceRecord"); String hgnc = raw.path("hgnc id").asText().strip();
            if (hgnc.matches("[1-9][0-9]*")) hgnc = "HGNC:" + hgnc;
            if (!hgnc.equals(context.at("/gene/hgncId").asText()) || !raw.path("gene symbol").equals(context.at("/gene/symbol"))
                    || !raw.path("disease MONDO").asText().strip().equals(context.at("/disease/mondoId").asText())
                    || !Set.of("strong", "definitive").contains(raw.path("confidence").asText().strip())
                    || raw.path("disease name").asText().isBlank() || !raw.path("comments").asText().isBlank()
                    || !raw.path("review").asText().isBlank() || !raw.path("cross cutting modifier").asText().isBlank()
                    || !sourceRecordSha(mapper, raw).equals(row.path("sourceRecordSha256").asText())) return new StartLossAssociation(out);
            try { java.time.OffsetDateTime.parse(raw.path("date of last review").asText().replace(' ', 'T')); }
            catch (java.time.DateTimeException invalid) { return new StartLossAssociation(out); }
            selected.add(row.deepCopy());
        }
        out.put("status", "CURATED_ASSOCIATION_SUPPORTED").put("reasonCode", "PVS1_START_LOSS_PINNED_G2P_ASSOCIATION")
                .put("sourceRecordSetSha256", adapter.canonicalSha256(selected));
        return new StartLossAssociation(out);
    }

    Pvs1StartLossDiseaseScope startLossDiseaseScope(ObjectNode context) {
        String key = context.at("/gene/hgncId").asText() + "|" + context.at("/disease/mondoId").asText();
        return new Pvs1StartLossDiseaseScope(mapper, context, identity(), backgrounds.getOrDefault(key, List.of()));
    }

    /** The separately selected policy remains authoritative, including specialization and NMD/sequence gates. */
    public ObjectNode assess(JsonNode context, @Nullable JsonNode policy) {
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-knowledge-assessment-v1")
                .put("status", "UNAVAILABLE").put("clinicalAcceptance", false);
        out.set("binding", identity());
        if (applicabilitySources != null) out.set("applicabilitySources", applicabilitySources.describe(context));
        String key = context.at("/gene/hgncId").asText() + "|" + context.at("/disease/mondoId").asText();
        var all = backgrounds.getOrDefault(key, List.of());
        if (policySourcesMode) return assessPolicySources(context, policy, out, all);
        String allelic = switch (context.path("moi").asText()) {
            case "AUTOSOMAL_DOMINANT" -> "monoallelic_autosomal";
            case "AUTOSOMAL_RECESSIVE" -> "biallelic_autosomal";
            default -> "";
        };
        if (all.isEmpty()) return blocked(out, "PVS1_MECHANISM_SOURCE_NOT_FOUND");
        if (allelic.isEmpty()) return blocked(out, "PVS1_MECHANISM_INHERITANCE_MAPPING_NOT_IMPLEMENTED");
        var records = all.stream().filter(r -> allelic.equals(r.at("/sourceRecord/allelic requirement").asText().strip())).toList();
        out.set("sourceRecords", mapper.valueToTree(records));
        if (applicabilitySources != null) {
            var evidence = out.putArray("mechanismEvidenceDetails");
            for (var record : records) {
                var raw = record.path("sourceRecord");
                var row = evidence.addObject().put("recordId", record.path("recordId").asText())
                        .put("sourceRecordSha256", record.path("sourceRecordSha256").asText()).put("runtimeEligible", false);
                for (String field : List.of("molecular mechanism support", "molecular mechanism categorisation", "molecular mechanism evidence",
                        "variant types", "variant consequence", "comments", "date of last review")) row.set(field, raw.path(field).deepCopy());
                row.set("curatedPublicationSourceText", raw.path("publications").deepCopy());
                row.set("minedPublicationLeadsSourceText", raw.path("additional mined publications").deepCopy());
            }
        }
        if (records.isEmpty()) return blocked(out, "PVS1_MECHANISM_INHERITANCE_NOT_COVERED");
        String recordSetSha = adapter.canonicalSha256(mapper.valueToTree(records));
        out.put("sourceRecordSetSha256", recordSetSha);
        var mechanisms = new HashSet<String>();
        for (var r : records) mechanisms.add(r.at("/sourceRecord/molecular mechanism").asText().strip());
        if (mechanisms.size() > 1) return blocked(out, "PVS1_MECHANISM_SOURCE_CONFLICT");
        if (!mechanisms.contains("loss of function")) return blocked(out, "PVS1_LOF_SOURCE_NOT_ESTABLISHED");
        if (records.stream().anyMatch(r -> !Set.of("strong", "definitive").contains(r.at("/sourceRecord/confidence").asText().strip())))
            return blocked(out, "PVS1_MECHANISM_SOURCE_STRENGTH_REQUIRES_REVIEW");
        String tx = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
        var matches = new ArrayList<JsonNode>();
        for (var e : adoption.path("entries")) if (context.at("/gene/hgncId").equals(e.at("/scope/hgncId"))
                && context.at("/disease/mondoId").equals(e.at("/scope/mondoId")) && context.path("moi").equals(e.at("/scope/moi"))
                && context.at("/variant/evidenceQueryAllele/assembly").equals(e.at("/scope/assembly"))
                && tx.equals(e.at("/scope/transcript").asText())) matches.add(e);
        if (matches.isEmpty()) return blocked(out, "PVS1_MECHANISM_TRANSCRIPT_ADOPTION_MISSING");
        if (matches.size() != 1) return blocked(out, "PVS1_KNOWLEDGE_ADOPTION_AMBIGUOUS");
        var entry = matches.get(0); out.set("adoption", entry.deepCopy());
        String issue = adoptionIssue(context, policy, entry, recordSetSha);
        if (issue != null) return blocked(out, issue);
        return out.put("status", "READY").put("reasonCode", "PVS1_SOURCE_AND_REVIEWED_APPLICABILITY_BOUND");
    }

    @Nullable
    private String adoptionIssue(JsonNode context, @Nullable JsonNode policy, JsonNode entry, String recordSetSha) {
        if (sourceQualifiedMode) {
            if (policy == null) return "PVS1_SOURCE_POLICY_CONDITIONS_MISSING";
            String issue = Pvs1SourceQualification.issue(entry, policy, adapter);
            if (issue != null) return issue;
            if (!entry.path("purpose").equals(adoption.at("/qualificationPolicy/purpose"))
                    || !entry.path("allowedDataClasses").equals(adoption.at("/qualificationPolicy/allowedDataClasses")))
                return "PVS1_KNOWLEDGE_DATA_USE_NOT_QUALIFIED";
        } else {
            if (!"REVIEWED_FOR_PVS1_NMD".equals(entry.at("/review/status").asText())
                    || !text(entry.at("/review/reviewId")) || !text(entry.at("/review/reviewedBy")) || !text(entry.at("/review/basis")))
                return "PVS1_KNOWLEDGE_REVIEW_REQUIRED";
            try {
                Instant reviewed = Instant.parse(entry.at("/review/reviewedAt").asText());
                Instant until = Instant.parse(entry.at("/review/validUntil").asText());
                if (reviewed.isAfter(Instant.now()) || !until.isAfter(Instant.now()) || !until.isAfter(reviewed))
                    return "PVS1_KNOWLEDGE_REVIEW_TIME_INVALID";
            } catch (RuntimeException invalid) { return "PVS1_KNOWLEDGE_REVIEW_TIME_INVALID"; }
        }
        String dataClass = context.path("dataClass").asText(), purpose = entry.path("purpose").asText();
        if (!Set.of("SYNTHETIC", "PRIVACY_BOUNDED_RUNTIME").contains(dataClass)
                || !Set.of("TEST_ONLY", "PROJECT_RESEARCH").contains(purpose)
                || !contains(entry.path("allowedDataClasses"), dataClass)
                || "PRIVACY_BOUNDED_RUNTIME".equals(dataClass) && (!"PROJECT_RESEARCH".equals(purpose)
                    || !"PUBLIC_REFERENCE".equals(adoption.path("sourceDataClass").asText())))
            return "PVS1_KNOWLEDGE_DATA_USE_NOT_QUALIFIED";
        if (!recordSetSha.equals(entry.path("sourceRecordSetSha256").asText()))
            return "PVS1_KNOWLEDGE_SOURCE_SET_MISMATCH";
        if (!"ESTABLISHED_LOSS_OF_FUNCTION".equals(entry.path("mechanismStatus").asText())
                || !"REVIEWED_RELEVANT".equals(entry.path("transcriptRelevance").asText()))
            return "PVS1_KNOWLEDGE_APPLICABILITY_NOT_REVIEWED";
        if (policy == null || !adapter.canonicalSha256(policy).equals(entry.path("policySha256").asText())
                || !policy.at("/transcriptModelBinding/canonicalSha256").equals(entry.path("modelSha256"))
                || !adapter.canonicalSha256(policy.path("nmdScope")).equals(entry.path("exonNmdScopeSha256").asText()))
            return "PVS1_KNOWLEDGE_POLICY_OR_EXON_BINDING_MISMATCH";
        return null;
    }

    /** Explicit existing-policy source path, never a silent fallback from failed G2P adoption. */
    private ObjectNode assessPolicySources(JsonNode context, @Nullable JsonNode policy, ObjectNode out, List<ObjectNode> all) {
        String tx = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
        var matches = new ArrayList<JsonNode>();
        for (var e : adoption.path("entries")) if (context.at("/gene/hgncId").equals(e.at("/scope/hgncId"))
                && context.at("/disease/mondoId").equals(e.at("/scope/mondoId")) && context.path("moi").equals(e.at("/scope/moi"))
                && context.at("/variant/evidenceQueryAllele/assembly").equals(e.at("/scope/assembly"))
                && tx.equals(e.at("/scope/transcript").asText())) matches.add(e);
        if (sourceQualifiedMode && (matches.isEmpty() || policy == null)) return missingConditions(context, out, all);
        if (matches.isEmpty()) return blocked(out, "PVS1_MECHANISM_TRANSCRIPT_ADOPTION_MISSING");
        if (matches.size() != 1) return blocked(out, "PVS1_KNOWLEDGE_ADOPTION_AMBIGUOUS");
        var entry = matches.get(0); out.set("adoption", entry.deepCopy());
        if (policy == null || !"REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText())
                || !text(policy.at("/review/reviewId"))) return blocked(out, "PVS1_POLICY_SOURCE_REVIEW_REQUIRED");
        String relevance = policy.at("/nmdScope/exonRelevance").asText();
        if (!"ESTABLISHED_LOSS_OF_FUNCTION".equals(policy.at("/mechanism/status").asText())
                || relevance.isBlank() || relevance.startsWith("PENDING")) return blocked(out, "PVS1_POLICY_SOURCE_APPLICABILITY_INCOMPLETE");
        for (String f : List.of("hgncId", "mondoId", "moi", "assembly")) if (!entry.at("/scope/"+f).equals(policy.at("/scope/"+f)))
            return blocked(out, "PVS1_KNOWLEDGE_POLICY_OR_EXON_BINDING_MISMATCH");
        if (policy.at("/scope/transcripts").size()!=1 || !tx.equals(policy.at("/scope/transcripts/0/accession").asText()+"."+policy.at("/scope/transcripts/0/version").asText()))
            return blocked(out, "PVS1_KNOWLEDGE_POLICY_OR_EXON_BINDING_MISMATCH");
        var sources = mapper.createArrayNode(); var ids = new HashSet<String>();
        JsonNode evidence = entry.path("mechanismEvidence");
        if (!TruncatingPvs1Model.sourcesValid(policy.path("scientificSources"))
                || !evidence.path("sourceIds").equals(policy.at("/mechanism/sourceIds"))
                || !evidence.path("policyReviewId").equals(policy.at("/review/reviewId")))
            return blocked(out, "PVS1_POLICY_MECHANISM_SOURCE_BINDING_MISMATCH");
        for (var id : evidence.path("sourceIds")) {
            if (!text(id) || !ids.add(id.asText())) return blocked(out, "PVS1_POLICY_MECHANISM_SOURCE_BINDING_MISMATCH");
        }
        for (var source : policy.path("scientificSources")) if (ids.contains(source.path("sourceId").asText())) sources.add(source.deepCopy());
        if (sources.size()!=ids.size() || !adapter.canonicalSha256(sources).equals(evidence.path("sourceSetSha256").asText()))
            return blocked(out, "PVS1_POLICY_MECHANISM_SOURCE_BINDING_MISMATCH");
        out.set("policyMechanismSources", sources);
        out.put("mechanismSource", "REVIEWED_POLICY_ORIGINAL_SOURCES");
        String allelic = switch (context.path("moi").asText()) {
            case "AUTOSOMAL_DOMINANT" -> "monoallelic_autosomal";
            case "AUTOSOMAL_RECESSIVE" -> "biallelic_autosomal";
            default -> "";
        };
        if (allelic.isEmpty()) return blocked(out, "PVS1_MECHANISM_INHERITANCE_MAPPING_NOT_IMPLEMENTED");
        var records = all.stream().filter(r -> allelic.equals(r.at("/sourceRecord/allelic requirement").asText().strip())).toList();
        out.set("sourceRecords", mapper.valueToTree(records));
        if (!all.isEmpty() && records.isEmpty()) return blocked(out, "PVS1_MECHANISM_INHERITANCE_NOT_COVERED");
        if (records.stream().anyMatch(r -> !"loss of function".equals(r.at("/sourceRecord/molecular mechanism").asText().strip())))
            return blocked(out, "PVS1_MECHANISM_SOURCE_CONFLICT");
        if (records.stream().anyMatch(r -> !Set.of("strong", "definitive").contains(r.at("/sourceRecord/confidence").asText().strip())))
            return blocked(out, "PVS1_MECHANISM_SOURCE_STRENGTH_REQUIRES_REVIEW");
        String recordSetSha = adapter.canonicalSha256(mapper.valueToTree(records));
        out.put("sourceRecordSetSha256", recordSetSha).put("g2pConsistency", records.isEmpty() ? "NO_EXACT_BACKGROUND" : "AGREES");
        String issue = adoptionIssue(context, policy, entry, recordSetSha);
        if (issue != null) return blocked(out, issue);
        return out.put("status", "READY").put("reasonCode", sourceQualifiedMode
                ? "PVS1_SOURCE_QUALIFIED_CONDITIONS_BOUND" : "PVS1_REVIEWED_POLICY_SOURCES_BOUND");
    }

    /** Facts remain useful when no executable conditions exist; a LoF label is not a decision tree. */
    private ObjectNode missingConditions(JsonNode context, ObjectNode out, List<ObjectNode> all) {
        String allelic = switch (context.path("moi").asText()) {
            case "AUTOSOMAL_DOMINANT" -> "monoallelic_autosomal";
            case "AUTOSOMAL_RECESSIVE" -> "biallelic_autosomal";
            default -> "";
        };
        out.set("backgroundSourceRecords", mapper.valueToTree(all));
        var records = all.stream().filter(r -> allelic.equals(r.at("/sourceRecord/allelic requirement").asText().strip())).toList();
        out.set("sourceRecords", mapper.valueToTree(records));
        out.putArray("missingExecutableConditions").add("SOURCE_SUPPORTED_LOF_MECHANISM_AND_STRENGTH")
                .add("DISEASE_RELEVANT_TRANSCRIPT_AND_EXONS").add("NMD_AND_RESCUE_CONDITIONS");
        out.put("sourceLabelsAreNotExecutableConditions", true);
        if (all.isEmpty()) return blocked(out, "PVS1_MECHANISM_SOURCE_NOT_FOUND");
        if (allelic.isEmpty()) return blocked(out, "PVS1_MECHANISM_INHERITANCE_MAPPING_NOT_IMPLEMENTED");
        if (records.isEmpty()) return blocked(out, "PVS1_MECHANISM_INHERITANCE_NOT_COVERED");
        var mechanisms = new HashSet<String>();
        for (var record : records) mechanisms.add(record.at("/sourceRecord/molecular mechanism").asText().strip());
        if (mechanisms.size() > 1) return blocked(out, "PVS1_MECHANISM_SOURCE_CONFLICT");
        if (!mechanisms.contains("loss of function")) return blocked(out, "PVS1_LOF_SOURCE_NOT_ESTABLISHED");
        if (records.stream().anyMatch(r -> !Set.of("strong", "definitive").contains(r.at("/sourceRecord/confidence").asText().strip())))
            return blocked(out, "PVS1_MECHANISM_SOURCE_STRENGTH_REQUIRES_REVIEW");
        for (var item : out.at("/applicabilitySources/details"))
            if ("EXACT_GENE_DISEASE_INHERITANCE".equals(item.path("relation").asText()))
                return blocked(out, "PVS1_PUBLISHED_RULE_ASSEMBLY_REQUIRED");
        return blocked(out, "PVS1_SOURCE_POLICY_CONDITIONS_MISSING");
    }

    static String sourceRecordSha(ObjectMapper mapper, JsonNode raw) {
        var sorted = new TreeMap<String, String>();
        raw.properties().forEach(e -> { require(e.getValue().isTextual(), "PVS1_KNOWLEDGE_SOURCE_FIELD_INVALID"); sorted.put(e.getKey(), e.getValue().textValue()); });
        try { return CodingTranscriptMaterialSupply.sha(mapper.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                .writeValueAsBytes(sorted)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException("PVS1_KNOWLEDGE_SOURCE_FIELD_INVALID", invalid); }
    }
    private static ObjectNode read(ObjectMapper mapper, Path path, String sha, int bound) throws IOException {
        require(sha.matches("[0-9a-f]{64}") && Files.isRegularFile(path) && Files.size(path) <= bound, "PVS1_KNOWLEDGE_FILE_INVALID");
        byte[] bytes; try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(bound + 1); }
        require(bytes.length <= bound && sha.equals(CodingTranscriptMaterialSupply.sha(bytes)), "PVS1_KNOWLEDGE_FILE_SHA_MISMATCH");
        var value = mapper.readTree(bytes); require(value instanceof ObjectNode, "PVS1_KNOWLEDGE_FILE_INVALID"); return (ObjectNode) value;
    }
    private static boolean contains(JsonNode array, String value) { if (!array.isArray()) return false; for (var node : array) if (node.isTextual() && value.equals(node.asText())) return true; return false; }
    private static boolean only(JsonNode value, Set<String> names) {
        if (!value.isObject()) return false;
        var fields = value.fieldNames(); while (fields.hasNext()) if (!names.contains(fields.next())) return false; return true;
    }
    private static boolean isFalse(JsonNode n) { return n.isBoolean() && !n.asBoolean(); }
    private static boolean text(JsonNode n) { return n.isTextual() && !n.asText().isBlank(); }
    private static ObjectNode blocked(ObjectNode result, String reason) { return result.put("reasonCode", reason); }
    private static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
}
