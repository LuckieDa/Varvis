package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.lang.Nullable;

/** Local routing over already qualified bundles. Index membership never grants scientific approval. */
public final class MissenseMaterialIndex {
    public static final String VERSION = "1.0.0";
    static final int MAX_ENTRIES = 16, MAX_MANIFEST = 1_000_000, MAX_BUNDLE = 8_000_000;
    private final ObjectMapper mapper;
    private final String rawSha256;
    private final List<Entry> entries;
    private record Entry(String id, String criterion, String raw, String canonical, ObjectNode document,
                         ReviewedRuleBundle checked) { }

    private MissenseMaterialIndex(ObjectMapper mapper, String rawSha256, List<Entry> entries) {
        this.mapper = mapper; this.rawSha256 = rawSha256; this.entries = List.copyOf(entries);
    }

    static ObjectMapper strictMapper() {
        return new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    static byte[] read(Path path, String pin, int max) throws IOException {
        require(pin.matches("[0-9a-f]{64}"), "INPUT_RAW_SHA_REQUIRED");
        require(Files.isRegularFile(path) && Files.size(path) <= max, "INPUT_FILE_MISSING_OR_SIZE_LIMIT");
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(max + 1);
            require(bytes.length <= max, "INPUT_SIZE_LIMIT");
            require(pin.equals(Pm4MaterialPreparation.sha(bytes)), "INPUT_RAW_SHA_MISMATCH");
            return bytes;
        }
    }

    public static MissenseMaterialIndex load(Path path, String pin) throws IOException {
        ObjectMapper mapper = strictMapper();
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        JsonNode index = mapper.readTree(read(path, pin, MAX_MANIFEST));
        if (!(index != null && "missense-material-index-v1".equals(index.path("schemaVersion").asText())
                && index.path("entries").isArray() && index.path("entries").size() <= MAX_ENTRIES)) {
            throw new IllegalArgumentException("REFERENCE_MATERIAL_INDEX_INVALID");
        }
        List<Entry> entries = new ArrayList<>(); Set<String> ids = new HashSet<>(), bundles = new HashSet<>();
        Path root = Objects.requireNonNull(path.toAbsolutePath().normalize().getParent());
        long total = 0;
        for (JsonNode item : index.path("entries")) {
            String id = item.path("entryId").asText(), criterion = item.path("criterion").asText();
            require(id.matches("[A-Za-z0-9_-]{1,80}") && ids.add(id), "REFERENCE_MATERIAL_INDEX_ID_INVALID_OR_DUPLICATE");
            require(Set.of(MissenseComparisonRuntime.PS1, MissenseComparisonRuntime.PM5).contains(criterion), "CRITERION_UNSUPPORTED");
            require(!item.path("path").asText().isBlank(), "REFERENCE_MATERIAL_INDEX_PATH_REQUIRED");
            byte[] bytes = read(root.resolve(item.path("path").asText()), item.path("rawSha256").asText(), MAX_BUNDLE);
            total += bytes.length; require(total <= 64_000_000, "REFERENCE_MATERIAL_INDEX_TOTAL_SIZE_LIMIT");
            JsonNode parsed = mapper.readTree(bytes);
            require(parsed instanceof ObjectNode, "REFERENCE_MATERIAL_BUNDLE_OBJECT_REQUIRED");
            ObjectNode document = (ObjectNode) parsed;
            String canonical = adapter.canonicalSha256(document);
            require(canonical.equals(item.path("canonicalSha256").asText()), "REFERENCE_MATERIAL_CANONICAL_SHA_MISMATCH");
            require(bundles.add(canonical), "REFERENCE_MATERIAL_INDEX_BUNDLE_DUPLICATE");
            ReviewedRuleBundle checked = new ReviewedRuleBundle(mapper, document, canonical);
            require(MissenseComparisonRuntime.PS1.equals(criterion) ? checked.supportsPs1() : checked.supportsPm5(),
                    "REFERENCE_BUNDLE_CRITERION_MISMATCH");
            require(document.path("materialQualification").isObject(), "REFERENCE_MATERIAL_FINAL_APPROVAL_REQUIRED");
            JsonNode backgrounds = document.at("/materialQualification/candidateMetadata/backgrounds");
            require(backgrounds.isArray() && !backgrounds.isEmpty(), "REFERENCE_MATERIAL_BACKGROUNDS_REQUIRED");
            entries.add(new Entry(id, criterion, item.path("rawSha256").asText(), canonical, document, checked));
        }
        return new MissenseMaterialIndex(mapper, pin, entries);
    }

    public ObjectNode identity() {
        ObjectNode out = mapper.createObjectNode().put("version", VERSION).put("indexRawSha256", rawSha256);
        var rows = out.putArray("entries");
        for (Entry e : entries) rows.addObject().put("entryId", e.id()).put("criterion", e.criterion())
                .put("rawSha256", e.raw()).put("canonicalSha256", e.canonical());
        return out;
    }

    /** Preflight has no target annotation yet; full protein identity is checked when binding the capture. */
    @Nullable public String useIssue(String criterion, JsonNode context) {
        List<Entry> matching = matching(criterion, context, null);
        if (matching.isEmpty()) return "REFERENCE_MATERIAL_BACKGROUND_NOT_FOUND";
        Set<String> issues = new LinkedHashSet<>();
        for (Entry entry : matching) {
            String issue = entry.checked().missenseUseIssue(context);
            if (issue == null) return null;
            issues.add(issue);
        }
        return issues.size() == 1 ? issues.iterator().next() : "REFERENCE_MATERIAL_CONTEXT_USE_NOT_APPROVED";
    }

    public ReviewedRuleBundle bind(String criterion, JsonNode context, JsonNode annotation,
                                  Supplier<MissenseComparisonRuntime> runtime, JsonNode runtimeBinding) {
        String protein = annotation.path("hgvsProtein").asText().split(":", 2)[0];
        require(protein.matches("(?:ENSP[0-9]+|NP_[0-9]+)\\.[1-9][0-9]*"), "REFERENCE_MATERIAL_PROTEIN_IDENTITY_REQUIRED");
        List<Entry> matching = matching(criterion, context, protein);
        require(!matching.isEmpty(), "REFERENCE_MATERIAL_BACKGROUND_NOT_FOUND");
        // Do not silently choose among conflicting revisions, even when one would give a favorable result.
        require(matching.size() == 1, "REFERENCE_MATERIAL_SELECTION_AMBIGUOUS");
        Entry selected = matching.get(0);
        String issue = selected.checked().missenseUseIssue(context);
        require(issue == null, issue == null ? "REFERENCE_MATERIAL_CONTEXT_USE_NOT_APPROVED" : issue);
        ObjectNode binding = mapper.createObjectNode(); binding.set("runtime", runtimeBinding.deepCopy());
        binding.set("materialIndex", identity()); binding.put("selectedEntryId", selected.id());
        return new ReviewedRuleBundle(mapper, selected.document().deepCopy(), selected.canonical(), runtime, binding);
    }

    private List<Entry> matching(String criterion, JsonNode context, @Nullable String protein) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) if (criterion.equals(entry.criterion())) {
            for (JsonNode row : entry.document().at("/materialQualification/candidateMetadata/backgrounds")) {
                JsonNode background = row.path("background");
                boolean same = true;
                for (String[] pair : List.of(new String[]{"assembly", "/variant/evidenceQueryAllele/assembly"},
                        new String[]{"contig", "/variant/evidenceQueryAllele/contig"}, new String[]{"hgncId", "/gene/hgncId"},
                        new String[]{"geneSymbol", "/gene/symbol"}, new String[]{"mondoId", "/disease/mondoId"}, new String[]{"moi", "/moi"}))
                    same &= !background.path(pair[0]).asText().isBlank() && background.path(pair[0]).equals(context.at(pair[1]));
                same &= context.at("/transcript/version").isIntegralNumber()
                        && background.path("transcript").asText().equals(context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asInt());
                if (same && (protein == null || protein.equals(background.path("protein").asText()))) { result.add(entry); break; }
            }
        }
        return result;
    }

    static void require(boolean valid, String reason) { if (!valid) throw new IllegalArgumentException(reason); }
    static String reason(Exception error) {
        if (error instanceof com.fasterxml.jackson.core.JsonProcessingException) return "INPUT_JSON_INVALID_OR_DUPLICATE_KEY";
        if (error instanceof IOException) return "INPUT_OUTPUT_IO_FAILURE";
        String reason = error.getMessage();
        return reason != null && reason.matches("[A-Z0-9_]+") ? reason : "REFERENCE_MATERIAL_INPUT_INVALID";
    }
}
