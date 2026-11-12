package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.utils.CoordinateUtils;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.ExplicitInput;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Internal intake of independently supplied context sources. No business wiring or source production. */
final class SavedPredictionContextBridge {
    static final String VERSION = "saved-prediction-context-bridge-v1";
    static final String REVIEW_SCHEMA = "saved-prediction-context-review-v1";
    static final String REVIEW_SCOPE = "CONTEXT_SOURCE_BINDING_FOR_S1_ONLY";
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    record Key(String candidateId, String hypothesisId) { }
    record ReviewBytes(byte[] bytes) {
        ReviewBytes { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    /** The separately fixed manifest pin anchors resource identities, not resolver self-reporting. */
    static final class Snapshot {
        private final PatientDiseaseContextBindingResolver resolver;
        private final JsonNode resources;
        private final JsonNode crosswalk;
        private final JsonNode relationships;
        private final String pin;

        private Snapshot(PatientDiseaseContextBindingResolver resolver, JsonNode resources,
                         JsonNode crosswalk, JsonNode relationships, String pin) {
            this.resolver = resolver; this.resources = resources.deepCopy(); this.crosswalk = crosswalk.deepCopy();
            this.relationships = relationships.deepCopy(); this.pin = pin;
        }

        static Snapshot load(ObjectMapper mapper, Path manifest, Path mane, Path clingen, String independentPin) throws IOException {
            byte[] bytes = bounded(manifest);
            check(pinMatches(bytes, independentPin), "CONTEXT_MANIFEST_PIN_MISMATCH", "binding.resources.manifest");
            JsonNode parsed = parse(mapper, bytes);
            JsonNode resources = parsed.path("resources");
            byte[] crosswalk = bounded(mane), relationships = bounded(clingen);
            check(pinMatches(crosswalk, resources.at("/mane/derivedSha256").asText())
                    && pinMatches(relationships, resources.at("/clingen/derivedSha256").asText()),
                    "CONTEXT_RESOURCE_PIN_MISMATCH", "binding.resources");
            var resolver = PatientDiseaseContextBindingResolver.load(mapper, manifest, mane, clingen);
            // Detect a replaced snapshot during the existing path-based loader's read.
            check(pinMatches(bounded(manifest), independentPin) && java.util.Arrays.equals(crosswalk, bounded(mane))
                    && java.util.Arrays.equals(relationships, bounded(clingen)), "CONTEXT_RESOURCE_CHANGED_DURING_LOAD", "binding.resources");
            return new Snapshot(resolver, resources, parse(mapper, crosswalk), parse(mapper, relationships), independentPin);
        }
    }

    /** No public constructor accepting a resolver or VERIFIED JSON. Only bounded pinned sources enter here. */
    static final class TrustedContextSupply {
        private final Snapshot snapshot;
        private final List<ExplicitInput> inputs;
        private final List<ExplicitContextSourceLedger> ledgers;
        private final Map<Key, ReviewBytes> reviews;
        private final Map<Key, String> reviewPins;
        private final List<String> ledgerPins;

        private TrustedContextSupply(Snapshot snapshot, List<ExplicitInput> inputs, List<ExplicitContextSourceLedger> ledgers,
                                     Map<Key, ReviewBytes> reviews, Map<Key, String> reviewPins, List<String> ledgerPins) {
            this.snapshot = snapshot; this.inputs = List.copyOf(inputs); this.ledgers = List.copyOf(ledgers);
            this.reviews = Map.copyOf(reviews); this.reviewPins = Map.copyOf(reviewPins); this.ledgerPins = List.copyOf(ledgerPins);
        }
        static TrustedContextSupply empty() { return new TrustedContextSupply(null, List.of(), List.of(), Map.of(), Map.of(), List.of()); }

        /** Private persisted producer receipts, bound by the ordinary run's private-artifact digest. */
        static TrustedContextSupply fromBusiness(ObjectMapper mapper, Snapshot snapshot, JsonNode privateData,
                Map<Key, ReviewBytes> reviews, Map<Key, String> reviewPins) throws IOException {
            var inputs = new ArrayList<ExplicitInput>();
            for (JsonNode input : privateData.path("predictionContextInputs"))
                inputs.add(mapper.treeToValue(input, ExplicitInput.class));
            var sources = new ArrayList<ExplicitContextSourceLedger>();
            var pins = new ArrayList<String>();
            for (JsonNode receipt : privateData.path("sourceReceipts")) {
                String pin = receipt.path("sha256").asText();
                sources.add(ExplicitContextSourceLedger.verifiedBytes(mapper,
                        receipt.path("rawJson").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8), pin));
                pins.add(pin);
            }
            return new TrustedContextSupply(snapshot, inputs, sources, reviews, reviewPins, pins);
        }

        static TrustedContextSupply load(ObjectMapper mapper, Snapshot snapshot, List<ExplicitInput> inputs,
                                         Map<String, Path> independentlyPinnedLedgers, Map<Key, ReviewBytes> reviews,
                                         Map<Key, String> independentlyPinnedReviews) throws IOException {
            var ledgers = new ArrayList<ExplicitContextSourceLedger>();
            for (var entry : independentlyPinnedLedgers.entrySet())
                ledgers.add(ExplicitContextSourceLedger.load(mapper, entry.getValue(), entry.getKey()));
            return new TrustedContextSupply(snapshot, inputs, ledgers, reviews, independentlyPinnedReviews,
                    independentlyPinnedLedgers.keySet().stream().sorted().toList());
        }
    }

    static final class PreparedContexts {
        private final ObjectNode identity;
        private final List<ObjectNode> entries;
        private final String batchIssue;
        private PreparedContexts(ObjectNode identity, List<ObjectNode> entries, String batchIssue) {
            this.identity = identity.deepCopy(); this.entries = entries.stream().map(e -> Objects.requireNonNull(e).deepCopy()).toList();
            this.batchIssue = batchIssue;
        }
        ObjectNode identity() { return identity.deepCopy(); }
        List<ObjectNode> entries() { return entries.stream().map(e -> Objects.requireNonNull(e).deepCopy()).toList(); }
        boolean matches(SavedPredictionApplicationInputs.Input input) { return identity.path("savedSourceIdentity").equals(input.identity()); }
        String batchIssue() { return batchIssue; }
        List<ObjectNode> candidateEntries(String id) {
            return entries.stream().filter(e -> id.equals(e.path("candidateId").asText())).map(e -> Objects.requireNonNull(e).deepCopy()).toList();
        }
    }

    PreparedContexts prepare(ObjectMapper mapper, SavedPredictionApplicationInputs.Input saved, TrustedContextSupply supplied) {
        Objects.requireNonNull(saved); Objects.requireNonNull(supplied);
        ObjectNode identity = mapper.createObjectNode().put("bridgeVersion", VERSION);
        identity.set("savedSourceIdentity", saved.identity());
        if (!saved.issue().isEmpty()) return prepared(mapper, identity, List.of(), saved.issue());
        identity.set("ledgerPins", mapper.valueToTree(supplied.ledgerPins));
        ArrayNode reviewPins = identity.putArray("reviewPins");
        supplied.reviewPins.entrySet().stream().sorted(java.util.Comparator.comparing(e -> e.getKey().candidateId() + "\n" + e.getKey().hypothesisId()))
                .forEach(e -> reviewPins.addObject().put("candidateId", e.getKey().candidateId()).put("hypothesisId", e.getKey().hypothesisId())
                        .put("sourceSha256", e.getValue()));
        if (supplied.snapshot != null) {
            identity.put("manifestSha256", supplied.snapshot.pin);
            // A verified byte pin does not make arbitrary resource extensions canonical identity values.
            if (!canonical(mapper, supplied.snapshot.resources))
                return prepared(mapper, identity, List.of(), "CONTEXT_RESOURCE_IDENTITY_INVALID");
            identity.set("resources", supplied.snapshot.resources.deepCopy());
        }
        ObjectNode settings = saved.settings();
        String boundsIssue = bounds(settings, supplied.inputs);
        var entries = new ArrayList<ObjectNode>();
        var sources = new LinkedHashMap<String, SavedPredictionApplicationInputs.ContextSource>();
        var accepted = new ArrayList<ExplicitInput>();
        var pending = new HashMap<Key, ObjectNode>();
        for (JsonNode candidate : settings.path("candidates")) {
            String id = candidate.path("candidateId").asText();
            List<ExplicitInput> inputs = supplied.inputs.stream().filter(i -> id.equals(i.candidateId())).toList();
            if (inputs.isEmpty()) {
                ObjectNode entry = entry(mapper, candidate, ""); entries.add(entry);
                block(entry, "MISSING_INPUT", "EXPLICIT_CONTEXT_MISSING", "hypotheses", "EXPLICIT_DECLARATION", "CALLER_CONFIRMATION");
                if (!boundsIssue.isEmpty()) block(entry, "ASSEMBLY_BLOCKED", boundsIssue, "inputs", "INPUT_SCOPE", "CALLER");
                continue;
            }
            String sourceIssue = SavedPredictionApplicationInputs.candidateIssue(mapper, saved, candidate);
            SavedPredictionApplicationInputs.ContextSource source = null;
            if (sourceIssue.isEmpty() && boundsIssue.isEmpty()) {
                try { source = SavedPredictionApplicationInputs.contextSource(mapper, saved, candidate); }
                catch (IllegalArgumentException invalid) { sourceIssue = safeCode(invalid, "CONTEXT_SOURCE_INVALID"); }
            }
            if (source != null) sources.put(id, source);
            for (ExplicitInput explicit : inputs) {
                ObjectNode entry = entry(mapper, candidate, explicit.hypothesisId()); entries.add(entry);
                if (!bindExplicitIdentity(mapper, explicit, entry)) continue;
                if (!boundsIssue.isEmpty()) { block(entry, "ASSEMBLY_BLOCKED", boundsIssue, "inputs", "INPUT_SCOPE", "CALLER"); continue; }
                if (!sourceIssue.isEmpty() || source == null) {
                    block(entry, sourceIssue.contains("UNSUPPORTED") ? "UNSUPPORTED" : "ASSEMBLY_BLOCKED", sourceIssue,
                            "publication", "SAVED_SOURCE", "SOURCE_MAINTAINER"); continue;
                }
                try {
                    preassemble(mapper, saved, source, explicit, supplied, entry);
                    accepted.add(explicit); pending.put(new Key(id, explicit.hypothesisId()), entry);
                } catch (Blocked invalid) {
                    block(entry, invalid.code.contains("UNSUPPORTED") ? "UNSUPPORTED" : "ASSEMBLY_BLOCKED",
                            invalid.code, invalid.field, role(invalid.field), party(invalid.field));
                }
            }
        }
        if (!accepted.isEmpty()) {
            var usedSources = sources.values().stream().filter(s -> accepted.stream().anyMatch(i -> i.candidateId().equals(s.candidate().getCandidateId()))).toList();
            List<VariantInterpretation> candidates = usedSources.stream().map(s -> Objects.requireNonNull(s).candidate()).toList();
            if (supplied.snapshot == null) throw new IllegalStateException("VERIFIED_SNAPSHOT_REQUIRED");
            var assembled = new ExplicitEvidenceContextAssembler(mapper, supplied.snapshot.resolver, supplied.ledgers)
                    .assemble(usedSources.get(0).analysis(), candidates, candidates.size(), accepted);
            for (var row : assembled.candidates()) for (var attempt : row.attempts()) {
                Key key = new Key(row.candidateId(), attempt.hypothesisId());
                ObjectNode entry = pending.get(key);
                if (entry == null) throw new IllegalStateException("ASSEMBLY_SCOPE_CHANGED");
                if (!attempt.blockers().isEmpty()) {
                    for (var blocker : attempt.blockers()) block(entry, "ASSEMBLY_BLOCKED", blocker.reasonCode(), blocker.field(),
                            role(blocker.field()), party(blocker.field()));
                    continue;
                }
                ObjectNode context = attempt.context();
                try {
                    check(context != null, "CONTEXT_ASSEMBLY_EMPTY", "context");
                    if (context == null) throw new Blocked("CONTEXT_ASSEMBLY_EMPTY", "context");
                    var hashes = new RuntimeEvidenceV2Adapter(mapper);
                    try { hashes.validateCompleteContext(context); }
                    catch (IllegalArgumentException invalid) { throw new Blocked("CONTEXT_CONTRACT_INVALID", "context"); }
                    var source = sources.get(row.candidateId());
                    if (source == null) throw new IllegalStateException("ASSEMBLY_SOURCE_MISSING");
                    check(context.path("dataClass").equals(settings.at("/task/dataClass"))
                            && context.at("/gene/hgncId").equals(entry.path("verifiedHgncId"))
                            && contextInputAlleleMatches(source.view(), context.at("/variant/inputAllele"))
                            && queryAllele(mapper, source.view().path("queryAllele")).equals(context.at("/variant/evidenceQueryAllele"))
                            && (context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asInt())
                            .equals(source.selection().path("versionedTranscriptId").asText())
                            && context.at("/transcript/annotationSourceVersion").equals(source.selection().path("version")),
                            "CONTEXT_ASSEMBLED_IDENTITY_CONFLICT", "context");
                    entry.set("context", context.deepCopy());
                    entry.put("contextSha256", hashes.canonicalSha256(context));
                    entry.set("assemblyProvenance", mapper.valueToTree(attempt.provenance()));
                    review(mapper, saved, supplied, key, entry, source);
                    entry.put("preparationStatus", "VERIFIED_FOR_S1");
                } catch (Blocked invalid) {
                    block(entry, invalid.field.startsWith("review") ? "REVIEW_MISSING_OR_INVALID" : "ASSEMBLY_BLOCKED",
                            invalid.code, invalid.field, role(invalid.field), party(invalid.field));
                }
            }
        }
        return prepared(mapper, identity, entries, boundsIssue);
    }

    /** Only serialization/canonicalization is caught here; assembler invariants still propagate. */
    private static boolean bindExplicitIdentity(ObjectMapper mapper, ExplicitInput explicit, ObjectNode entry) {
        byte[] bytes = null;
        try {
            bytes = mapper.writeValueAsBytes(explicit);
            entry.put("explicitInputSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(mapper.valueToTree(explicit)));
            return true;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException invalid) {
            if (bytes != null) {
                entry.put("rejectedExplicitInputByteSha256", PredictionCalibration.sha256(bytes));
                entry.put("rejectedExplicitInputByteSha256Status", "AVAILABLE");
                entry.put("rejectedExplicitInputByteSha256Scope", "MAPPER_SERIALIZED_REJECTED_INPUT_ONLY");
            } else entry.put("rejectedExplicitInputByteSha256Status", "UNAVAILABLE");
            block(entry, "ASSEMBLY_BLOCKED", "CONTEXT_EXPLICIT_INPUT_IDENTITY_INVALID", "explicitInput", "EXPLICIT_DECLARATION", "CALLER");
            return false;
        }
    }

    /** Never return an extension tree that the consumer cannot hash, including on a blocked path. */
    private static PreparedContexts prepared(ObjectMapper mapper, ObjectNode identity, List<ObjectNode> entries, String issue) {
        ObjectNode safeIdentity = identity;
        if (!canonical(mapper, identity)) {
            safeIdentity = mapper.createObjectNode().put("bridgeVersion", VERSION);
            for (String field : List.of("savedSourceIdentity", "manifestSha256", "ledgerPins", "reviewPins"))
                if (identity.has(field) && canonical(mapper, identity.path(field))) safeIdentity.set(field, identity.path(field).deepCopy());
            issue = "CONTEXT_PREPARATION_IDENTITY_INVALID";
        }
        var safeEntries = new ArrayList<ObjectNode>();
        for (ObjectNode original : entries) {
            if (canonical(mapper, original)) { safeEntries.add(original); continue; }
            ObjectNode safe = entry(mapper, original, original.path("hypothesisId").asText());
            // Keep verified scalar pins only, never the subtree that caused the rejection.
            for (String field : List.of("explicitInputSha256", "contextSha256", "reviewSourceSha256"))
                if (original.path(field).isTextual()) safe.set(field, original.path(field));
            block(safe, "ASSEMBLY_BLOCKED", "CONTEXT_PREPARATION_ENTRY_IDENTITY_INVALID", "contextPreparation", "CONTEXT", "SOURCE_MAINTAINER");
            safeEntries.add(safe);
        }
        return new PreparedContexts(safeIdentity, safeEntries, issue);
    }

    private static boolean canonical(ObjectMapper mapper, JsonNode value) {
        try { new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static void preassemble(ObjectMapper mapper, SavedPredictionApplicationInputs.Input saved,
                                    SavedPredictionApplicationInputs.ContextSource source, ExplicitInput input,
                                    TrustedContextSupply supply, ObjectNode entry) {
        Snapshot snapshot = supply.snapshot;
        check(snapshot != null, "CONTEXT_RELATIONSHIP_SNAPSHOT_MISSING", "binding.resources");
        if (snapshot == null) throw new Blocked("CONTEXT_RELATIONSHIP_SNAPSHOT_MISSING", "binding.resources");
        var binding = input.binding();
        check(binding != null && binding.provenance() != null && binding.provenance().patientHypothesis() != null,
                "BINDING_REQUIRED", "binding");
        if (binding == null || binding.provenance() == null) throw new Blocked("BINDING_REQUIRED", "binding");
        var hypothesis = binding.provenance().patientHypothesis();
        check(input.candidateId().equals(hypothesis.candidateId()) && Objects.equals(input.hypothesisId(), hypothesis.hypothesisId()),
                "BINDING_INPUT_IDENTITY_CONFLICT", "binding");
        var resolved = snapshot.resolver.resolve(source.candidate(), List.of(hypothesis));
        check(resolved.bindings().size() == 1 && resolved.blockers().isEmpty(), "BINDING_REVALIDATION_FAILED", "binding");
        var verified = resolved.bindings().get(0);
        check("READY".equals(verified.status()), verified.reasonCode(), "binding");
        check(verified.equals(binding), "BINDING_PROVENANCE_CONFLICT", "binding");
        var provenance = verified.provenance();
        check(provenance != null, "BINDING_PROVENANCE_REQUIRED", "binding.provenance");
        if (provenance == null) throw new Blocked("BINDING_PROVENANCE_REQUIRED", "binding.provenance");
        check(provenance.maneResourceIdentity().equals(snapshot.resources.path("mane"))
                && provenance.clingenResourceIdentity().equals(snapshot.resources.path("clingen"))
                && !provenance.crosswalkRecords().isEmpty() && !provenance.relationshipRecords().isEmpty()
                && provenance.crosswalkRecords().stream().allMatch(r -> contains(snapshot.crosswalk, r))
                && provenance.relationshipRecords().stream().allMatch(r -> contains(snapshot.relationships, r)),
                "BINDING_RESOURCE_ANCHOR_CONFLICT", "binding.provenance");
        JsonNode options = snapshot.resolver.referenceOptions(mapper, source.candidate());
        check(verified.hgncId().equals(options.path("hgncId").asText())
                && snapshot.resources.path("mane").equals(options.path("geneIdentitySource"))
                && snapshot.resources.path("clingen").equals(options.path("relationshipSource")),
                "CONTEXT_INDEPENDENT_HGNC_CONFLICT", "binding.gene");
        check(!source.view().has("hgncId") || source.view().path("hgncId").equals(options.path("hgncId")),
                "CONTEXT_PUBLISHED_HGNC_CONFLICT", "publication.hgncId");
        entry.set("verifiedHgncId", options.path("hgncId"));
        entry.set("bindingProvenance", mapper.valueToTree(provenance));
        check(saved.settings().at("/task/dataClass").asText().equals(input.dataClass()), "CONTEXT_DATA_CLASS_CONFLICT", "dataClass");
        var choice = input.transcript();
        if (choice != null) {
            check((choice.accession() + "." + choice.version()).equals(source.selection().path("versionedTranscriptId").asText())
                    && Objects.equals(choice.annotationSource(), source.selection().path("source").asText())
                    && Objects.equals(choice.annotationSourceVersion(), source.selection().path("version").asText()),
                    "CONTEXT_SAVED_TRANSCRIPT_CHOICE_CONFLICT", "transcript");
            // The old assembler checks transcript source fields but not the saved annotation byte identity.
            if (choice.source() != null) {
                for (var ledger : supply.ledgers) {
                    JsonNode record;
                    try { record = ledger.resolve(choice.source()); } catch (IllegalArgumentException otherLedger) { continue; }
                    // Ordinary producer receipts preserve the full selected annotation instead of a detached digest.
                    // Hash those pinned bytes without rewriting the ledger or relaxing an explicit digest claim.
                    JsonNode expected = source.selection().path("annotationSourceSha256");
                    boolean claimedDigest = record.has("annotationSourceSha256");
                    boolean recordedAnnotation = record.path("annotation").isObject();
                    check((claimedDigest || recordedAnnotation)
                            && (!claimedDigest || record.path("annotationSourceSha256").equals(expected))
                            && (!recordedAnnotation || new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(record.path("annotation")).equals(expected.asText())),
                            "CONTEXT_TRANSCRIPT_RECORD_DIGEST_CONFLICT", "transcript.source.annotationSourceSha256");
                }
            }
        }
        var norm = input.normalization();
        if (norm != null) check(source.mappingToken().equals(norm.originalRecord()) && Integer.valueOf(source.selectedAltIndex()).equals(norm.selectedAltIndex()),
                "CONTEXT_MATCHED_RECORD_CONFLICT", "normalization.originalRecord");
        entry.set("selectedAnnotation", source.selection().deepCopy());
        entry.set("adoptedSources", adopted(mapper, input, snapshot));
    }

    private static ObjectNode adopted(ObjectMapper mapper, ExplicitInput input, Snapshot snapshot) {
        ObjectNode result = mapper.createObjectNode().put("manifestSha256", snapshot.pin);
        result.set("resources", snapshot.resources.deepCopy());
        ObjectNode refs = result.putObject("ledgerReferences");
        var normalization = input.normalization();
        putRef(mapper, refs, "patient", input.patient() == null ? null : input.patient().source());
        putRef(mapper, refs, "transcript", input.transcript() == null ? null : input.transcript().source());
        putRef(mapper, refs, "normalization", normalization == null ? null : normalization.source());
        putRef(mapper, refs, "reference", normalization == null ? null : normalization.reference());
        putRef(mapper, refs, "lineage", normalization == null || normalization.lineage() == null ? null : normalization.lineage().source());
        return result;
    }
    private static void putRef(ObjectMapper mapper, ObjectNode refs, String role, SourceRef ref) { refs.set(role, mapper.valueToTree(ref)); }

    private static void review(ObjectMapper mapper, SavedPredictionApplicationInputs.Input saved, TrustedContextSupply supply,
                               Key key, ObjectNode entry, SavedPredictionApplicationInputs.ContextSource source) {
        ReviewBytes artifact = supply.reviews.get(key); String pin = supply.reviewPins.get(key);
        check(artifact != null && pin != null, "CONTEXT_INDEPENDENT_REVIEW_MISSING", "review");
        if (artifact == null) throw new Blocked("CONTEXT_INDEPENDENT_REVIEW_MISSING", "review");
        byte[] bytes = artifact.bytes();
        check(bytes.length <= MAX_BYTES && pinMatches(bytes, pin), "CONTEXT_REVIEW_PIN_MISMATCH", "review.sourceSha256");
        JsonNode review;
        try { review = parse(mapper, bytes); }
        catch (IOException | IllegalArgumentException invalid) { throw new Blocked("CONTEXT_REVIEW_JSON_INVALID", "review"); }
        check(REVIEW_SCHEMA.equals(review.path("schemaVersion").textValue()) && REVIEW_SCOPE.equals(review.path("scope").textValue())
                && "APPROVED_FOR_S1".equals(review.path("status").textValue())
                && SavedPredictionApplicationInputs.nonblank(review, "reviewId", "reviewerId", "version"),
                "CONTEXT_REVIEW_SCOPE_OR_STATUS_INVALID", "review");
        ObjectNode expected = mapper.createObjectNode();
        expected.set("analysisId", saved.settings().path("analysisId")); expected.set("runId", saved.settings().path("runId"));
        expected.put("candidateId", key.candidateId()).put("hypothesisId", key.hypothesisId());
        expected.set("dataClass", entry.at("/context/dataClass")); expected.set("contextId", entry.at("/context/contextId"));
        expected.set("contextSha256", entry.path("contextSha256"));
        expected.set("publicationByteSha256", saved.identity().at("/sourceByteSha256/publication"));
        expected.set("annotationSourceSha256", source.selection().path("annotationSourceSha256"));
        expected.set("versionedTranscriptId", source.selection().path("versionedTranscriptId"));
        expected.set("adoptedSources", entry.path("adoptedSources"));
        for (var field : expected.properties()) check(field.getValue().equals(review.path(field.getKey())),
                "CONTEXT_REVIEW_BINDING_CONFLICT", "review." + field.getKey());
        ObjectNode projection = mapper.createObjectNode();
        for (String field : List.of("schemaVersion", "scope", "status", "reviewId", "reviewerId", "version", "validFrom", "validThrough")) {
            if ((field.equals("validFrom") || field.equals("validThrough")) && !review.has(field)) continue;
            check(SavedPredictionApplicationInputs.nonblank(review, field), "CONTEXT_REVIEW_FIELD_INVALID", "review." + field);
            projection.set(field, review.path(field));
        }
        entry.set("review", projection);
        // The full private original, including ignored fields, remains independently byte-pinned.
        entry.put("reviewSourceSha256", pin);
    }

    /** Only called on a native source already proven by publicationCandidate and candidateIssue. */
    static boolean contextInputAlleleMatches(JsonNode verifiedView, JsonNode contextAllele) {
        if (!SavedPredictionApplicationInputs.NATIVE_PUBLICATION.equals(verifiedView.at("/sourceProjection/adapterVersion").asText())) return false;
        JsonNode original = verifiedView.path("inputAllele");
        if (!original.isObject() || !contextAllele.isObject() || original.size() != 5 || contextAllele.size() != 5
                || !"GRCh38".equals(original.path("assembly").asText()) || !original.path("assembly").equals(contextAllele.path("assembly"))
                || !original.path("position").isIntegralNumber() || !contextAllele.path("position").isIntegralNumber()
                || !original.path("position").canConvertToInt() || original.path("position").intValue() <= 0) return false;
        for (String field : List.of("position", "reference", "alternate")) if (!original.path(field).equals(contextAllele.path(field))) return false;
        if (!SavedPredictionApplicationInputs.nonblank(original, "contig", "reference", "alternate") || !contextAllele.path("contig").isTextual()) return false;
        String normalized = CoordinateUtils.normalizeChromosome(original.path("contig").textValue());
        return normalized != null && normalized.matches("(?:[1-9]|1[0-9]|2[0-2]|X|Y)")
                && normalized.equals(contextAllele.path("contig").textValue());
    }
    static ObjectNode contigReceipt(ObjectMapper mapper, JsonNode view) {
        return mapper.createObjectNode().put("originalContig", view.at("/inputAllele/contig").asText())
                .put("comparisonContig", CoordinateUtils.normalizeChromosome(view.at("/inputAllele/contig").asText()))
                .put("method", "CoordinateUtils.normalizeChromosome").put("scope", "PROVEN_NATIVE_IDENTITY_REPRESENTATION_ONLY")
                .put("sourcePointer", view.at("/sourceProjection/originalPointer").asText().replace("/published", "/matchedRecord/contig"));
    }
    static ObjectNode queryAllele(ObjectMapper mapper, JsonNode key) {
        ObjectNode out = mapper.createObjectNode();
        out.set("assembly", key.path("genomeAssembly")); out.set("contig", key.path("chromosome")); out.set("position", key.path("position"));
        out.set("reference", key.path("reference")); out.set("alternate", key.path("alternate")); return out;
    }
    private static String bounds(JsonNode settings, List<ExplicitInput> inputs) {
        if (settings.path("candidates").size() > settings.at("/task/maxCandidates").asInt()) return "CONTEXT_CANDIDATE_BUDGET_EXCEEDED";
        var counts = new HashMap<String, Integer>(); var keys = new java.util.HashSet<Key>();
        for (ExplicitInput input : inputs) {
            if (input.candidateId() == null || input.hypothesisId() == null || input.hypothesisId().isBlank()) return "CONTEXT_EXPLICIT_ID_REQUIRED";
            boolean found = false;
            for (JsonNode candidate : settings.path("candidates")) if (input.candidateId().equals(candidate.path("candidateId").asText())) found = true;
            if (!found) return "INPUT_OUTSIDE_SELECTED_TOP_N";
            if (!keys.add(new Key(input.candidateId(), input.hypothesisId()))) return "HYPOTHESIS_ID_AMBIGUOUS";
            int count = counts.getOrDefault(input.candidateId(), 0) + 1; counts.put(input.candidateId(), count);
            if (count > settings.at("/task/maxHypothesesPerCandidate").asInt()) return "CONTEXT_HYPOTHESIS_BUDGET_EXCEEDED";
        }
        return "";
    }
    private static ObjectNode entry(ObjectMapper mapper, JsonNode candidate, String hypothesisId) {
        ObjectNode out = mapper.createObjectNode().put("candidateId", candidate.path("candidateId").asText())
                .put("rank", candidate.path("rank").asInt()).put("hypothesisId", hypothesisId).put("preparationStatus", "MISSING_INPUT");
        out.putArray("blockers"); return out;
    }
    private static void block(ObjectNode entry, String status, String code, String field, String sourceRole, String neededParty) {
        entry.put("preparationStatus", status);
        String missingField = field == null ? "context" : field;
        ObjectNode blocker = ((ArrayNode) entry.path("blockers")).addObject().put("reasonCode", code).put("field", missingField)
                .put("sourceRole", sourceRole).put("neededParty", neededParty)
                .put("kind", missingField.startsWith("review") ? "INDEPENDENT_REVIEW" : code.contains("CONFLICT") || code.contains("MISMATCH")
                        ? "IDENTITY_CONFLICT" : "EXPLICIT_CONTEXT_MISSING".equals(code) ? "DECLARATION_NOT_SUPPLIED" : "SOURCE_MISSING_OR_INVALID");
        blocker.set("candidateId", entry.path("candidateId")); blocker.set("hypothesisId", entry.path("hypothesisId"));
    }
    private static String role(String field) { return field == null ? "CONTEXT" : field.startsWith("review") ? "INDEPENDENT_CONTEXT_REVIEW" : field.split("\\.")[0].toUpperCase(java.util.Locale.ROOT); }
    private static String party(String field) { return field != null && field.startsWith("review") ? "INDEPENDENT_REVIEWER" : field != null && field.startsWith("patient") ? "CALLER_CONFIRMATION" : "SOURCE_MAINTAINER"; }
    private static boolean contains(JsonNode array, JsonNode value) { for (JsonNode row : array) if (row.equals(value)) return true; return false; }
    private static boolean pinMatches(byte[] bytes, String pin) { return pin != null && pin.matches("[0-9a-f]{64}") && PredictionCalibration.sha256(bytes).equals(pin); }
    private static byte[] bounded(Path path) throws IOException {
        check(Files.size(path) <= MAX_BYTES, "CONTEXT_SOURCE_TOO_LARGE", "sources");
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1); check(bytes.length <= MAX_BYTES, "CONTEXT_SOURCE_TOO_LARGE", "sources"); return bytes;
        }
    }
    private static JsonNode parse(ObjectMapper mapper, byte[] bytes) throws IOException {
        JsonNode value = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(bytes);
        if (value == null) throw new IllegalArgumentException("CONTEXT_SOURCE_JSON_INVALID"); return value;
    }
    private static String safeCode(IllegalArgumentException invalid, String fallback) {
        String code = invalid.getMessage(); return code != null && code.matches("[A-Z][A-Z0-9_]+") ? code : fallback;
    }
    private static void check(boolean condition, String code, String field) { if (!condition) throw new Blocked(code, field); }
    private static final class Blocked extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        final String code; final String field;
        Blocked(String code, String field) { super(code); this.code = code; this.field = field; }
    }
}
