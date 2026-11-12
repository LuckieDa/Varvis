package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.lang.Nullable;

/** Assesses pinned, explicitly reviewed regional evidence under the general PM1 criterion. */
public final class Pm1ReviewedRegions {
    public static final String SCHEMA = "pm1-reviewed-regions-v1";
    public static final String QUALIFICATION_SCHEMA = "pm1-reviewed-regions-v2";
    public static final String QUALIFICATION_METHOD_VERSION = "1.1.0";
    static final String ABSENT_PUBLISHED_BACKGROUND = "PUBLISHED_KNOWLEDGE_BACKGROUND_NOT_COVERED";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final String SHA = "[a-f0-9]{64}";
    static final String AA = "[ACDEFGHIKLMNPQRSTVWY]";
    static final Set<String> STRENGTHS = Set.of("SUPPORTING", "MODERATE");
    private final ObjectMapper mapper;
    private final String catalogSha256;
    private final String schemaVersion;
    private final Map<Scope, JsonNode> records;
    private final Function<JsonNode, ObjectNode> releasedScope;

    private record Scope(String hgnc, String disease, String moi, String assembly, String transcript, String protein) {
        static Scope read(JsonNode value) {
            return new Scope(value.path("hgncId").asText(), value.path("mondoId").asText(), value.path("moi").asText(),
                    value.path("assembly").asText(), value.path("transcript").asText(), value.path("protein").asText());
        }
        boolean valid() {
            return hgnc.matches("HGNC:[1-9][0-9]*") && disease.matches("MONDO:[0-9]{7}")
                    && Set.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE", "X_LINKED", "X_LINKED_DOMINANT",
                            "X_LINKED_RECESSIVE").contains(moi)
                    && "GRCh38".equals(assembly) && transcript.matches("(?:ENST[0-9]+|NM_[0-9]+)\\.[1-9][0-9]*")
                    && protein.matches("(?:ENSP[0-9]+|NP_[0-9]+)\\.[1-9][0-9]*");
        }
    }

    public static Pm1ReviewedRegions load(ObjectMapper mapper, Path file, String sha,
            Function<JsonNode, ObjectNode> releasedScope) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        check(sha.matches(SHA) && bytes.length <= MAX_BYTES && sha.equals(CodingTranscriptMaterialSupply.sha(bytes)));
        var document = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
        if (document == null) throw new IllegalArgumentException("PM1_REVIEWED_REGION_CATALOG_INVALID");
        String schema = document.path("schemaVersion").asText();
        check(only(document, Set.of("schemaVersion", "records")) && Set.of(SCHEMA, QUALIFICATION_SCHEMA).contains(schema)
                && array(document.path("records"), 256) && !document.path("records").isEmpty());
        var records = new HashMap<Scope, JsonNode>();
        for (var record : document.path("records")) {
            var scope = Scope.read(record.path("scope"));
            check(only(record.path("scope"), Set.of("hgncId", "mondoId", "moi", "assembly", "transcript", "protein"))
                    && scope.valid() && records.putIfAbsent(scope, record) == null);
            validateRecord(record, QUALIFICATION_SCHEMA.equals(schema));
        }
        return new Pm1ReviewedRegions(mapper, sha, schema, records, releasedScope);
    }

    private Pm1ReviewedRegions(ObjectMapper mapper, String catalogSha256, String schemaVersion, Map<Scope, JsonNode> records,
            Function<JsonNode, ObjectNode> releasedScope) {
        this.mapper = mapper;
        this.catalogSha256 = catalogSha256;
        this.schemaVersion = schemaVersion;
        this.records = Map.copyOf(records);
        this.releasedScope = releasedScope;
    }

    @Nullable ObjectNode assess(ObjectNode context, ObjectNode model, JsonNode association) {
        var scope = new Scope(context.at("/gene/hgncId").asText(), context.at("/disease/mondoId").asText(),
                context.path("moi").asText(), model.path("assembly").asText(), model.path("transcript").asText(),
                model.path("protein").asText());
        var record = records.get(scope);
        if (record == null) return null;
        var out = mapper.createObjectNode().put("status", "NOT_EVALUATED").put("appliedStrength", "NONE");
        var release = releasedScope.apply(context);
        if (!"NO_KNOWN_SPECIALIZATION_IN_LOCAL_SNAPSHOT".equals(release.path("status").asText()))
            return out.put("reasonCode", "PM1_REVIEWED_REGION_SPECIALIZATION_UNRESOLVED");
        String releaseSha = release.path("catalogSha256").asText();
        check(releaseSha.matches(SHA));
        var facts = out.putObject("reviewedRegion").put("schemaVersion", schemaVersion)
                .put("catalogSha256", catalogSha256).put("releaseCatalogSha256", releaseSha);
        facts.set("scope", record.path("scope").deepCopy());
        int position = association.path("proteinPosition").asInt();
        int length = model.path("proteinLength").asInt();
        String cds = model.path("cdsSequence").asText();
        check(position > 0 && position <= length && length <= 100_000 && cds.length() == (length + 1) * 3);
        if (QUALIFICATION_SCHEMA.equals(schemaVersion)) {
            facts.put("qualificationMethodVersion", QUALIFICATION_METHOD_VERSION);
            return Pm1RegionalQualification.assess(out, facts, record, association, length, cds);
        }
        var matches = record.path("regions").valueStream().filter(r -> Pm1RegionSelection.covers(r, position)).toList();
        if (matches.size() > 1) return out.put("reasonCode", "PM1_REVIEWED_REGION_OVERLAP");
        if (matches.isEmpty()) return outsideRegions(out, facts, record, position, length);
        return evaluateRegion(out, facts, record, matches.get(0), association, length, cds);
    }

    static ObjectNode outsideRegions(ObjectNode out, ObjectNode facts, JsonNode record, int position, int length) {
        var covered = record.path("reviewedCoverage").valueStream().filter(r -> Pm1RegionSelection.covers(r, position)).findFirst();
        if (covered.isEmpty()) return out.put("reasonCode", "PM1_REVIEWED_REGION_POSITION_UNCOVERED");
        check(covered.get().path("end").asInt() <= length);
        facts.set("reviewedCoverage", covered.get().deepCopy());
        addSources(facts, record, sourceIds(covered.get()));
        return out.put("status", "NOT_MET").put("reasonCode", "PM1_REVIEWED_REGION_OUTSIDE_QUALIFIED");
    }

    private static ObjectNode evaluateRegion(ObjectNode out, ObjectNode facts, JsonNode record, JsonNode region,
            JsonNode association, int length, String cds) {
        check(region.path("end").asInt() <= length);
        facts.set("region", region.deepCopy());
        Set<String> references = sourceIds(region);
        int independent = 0;
        boolean benign = false;
        boolean conflict = false;
        boolean unresolved = false;
        for (var observation : region.path("observations")) {
            references.addAll(sourceIds(observation));
            int position = observation.path("proteinPosition").asInt();
            int offset = (position - 1) * 3;
            check(observation.path("refAminoAcid").asText().equals(
                    Character.toString(TruncatingSequence.translateCodon(cds.substring(offset, offset + 3)))));
            String role = observation.path("role").asText();
            benign |= "QUALIFIED_BENIGN".equals(role);
            conflict |= "CONFLICTING".equals(role);
            unresolved |= "UNRESOLVED".equals(role);
            if ("INDEPENDENT_PATHOGENIC".equals(role)
                    && !(observation.path("proteinPosition").equals(association.path("proteinPosition"))
                            && observation.path("altAminoAcid").equals(association.path("altAminoAcid")))) independent++;
        }
        facts.put("independentPathogenicCount", independent);
        addSources(facts, record, references);
        if (benign) return out.put("status", "NOT_MET").put("reasonCode", "PM1_REVIEWED_REGION_BENIGN_OBSERVATION");
        if (conflict) return out.put("reasonCode", "PM1_REVIEWED_REGION_OBSERVATION_UNRESOLVED");
        if (unresolved) return out.put("reasonCode", "PM1_REVIEWED_REGION_OBSERVATION_INCOMPLETE");
        String review = region.path("reviewStatus").asText();
        if ("NOT_QUALIFIED".equals(review))
            return out.put("status", "NOT_MET").put("reasonCode", "PM1_REVIEWED_REGION_NOT_QUALIFIED");
        if (!"QUALIFIED".equals(review)) return out.put("reasonCode", "PM1_REVIEWED_REGION_REVIEW_PENDING");
        if (!"COMPLETE".equals(region.path("benignSurvey").asText()))
            return out.put("reasonCode", "PM1_REVIEWED_REGION_BENIGN_SURVEY_INCOMPLETE");
        if (independent == 0) return out.put("reasonCode", "PM1_REVIEWED_REGION_INDEPENDENT_OBSERVATION_MISSING");
        return out.put("status", "MET").put("reasonCode", "PM1_REVIEWED_REGION_MATCH")
                .put("appliedStrength", region.path("strength").asText());
    }

    private static void validateRecord(JsonNode record, boolean qualificationSchema) {
        var fields = qualificationSchema ? Set.of("scope", "sources", "regions", "reviewedCoverage", "survey", "observations")
                : Set.of("scope", "sources", "regions", "reviewedCoverage");
        check(only(record, fields)
                && array(record.path("sources"), 64) && array(record.path("regions"), 128)
                && array(record.path("reviewedCoverage"), 128) && !record.path("regions").isEmpty());
        Set<String> sources = new HashSet<>();
        for (var source : record.path("sources")) {
            String id = source.path("sourceId").asText();
            var uri = URI.create(source.path("url").asText());
            check(only(source, Set.of("sourceId", "title", "url", "locator"))
                    && id.matches("[A-Za-z0-9._-]{1,96}") && sources.add(id) && text(source.path("url"))
                    && "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && text(source.path("title")) && text(source.path("locator")));
        }
        for (var coverage : record.path("reviewedCoverage")) {
            check(only(coverage, Set.of("start", "end", "sourceIds")) && interval(coverage));
            validateSources(coverage, sources);
        }
        if (qualificationSchema) {
            Pm1RegionalQualification.validate(record, sources);
            return;
        }
        Set<String> regionIds = new HashSet<>();
        for (var region : record.path("regions")) {
            check(only(region, Set.of("regionId", "label", "start", "end", "kind", "reviewStatus", "strength",
                    "reviewRationale", "benignSurvey", "sourceIds", "observations"))
                    && interval(region) && text(region.path("regionId")) && regionIds.add(region.path("regionId").asText())
                    && text(region.path("label")) && text(region.path("reviewRationale"))
                    && Set.of("MUTATIONAL_HOTSPOT", "CRITICAL_FUNCTIONAL_REGION").contains(region.path("kind").asText())
                    && Set.of("QUALIFIED", "NOT_QUALIFIED", "PENDING").contains(region.path("reviewStatus").asText())
                    && Set.of("COMPLETE", "INCOMPLETE").contains(region.path("benignSurvey").asText())
                    && ("QUALIFIED".equals(region.path("reviewStatus").asText()) ? STRENGTHS.contains(region.path("strength").asText())
                            : "NONE".equals(region.path("strength").asText()))
                    && array(region.path("observations"), 200));
            validateSources(region, sources);
            validateObservations(region, sources);
        }
    }

    private static void validateObservations(JsonNode region, Set<String> sources) {
        Set<String> keys = new HashSet<>();
        for (var item : region.path("observations")) {
            var position = item.path("proteinPosition");
            String ref = item.path("refAminoAcid").asText(), alt = item.path("altAminoAcid").asText();
            check(only(item, Set.of("proteinPosition", "refAminoAcid", "altAminoAcid", "role", "sourceIds"))
                    && position.isIntegralNumber() && position.canConvertToInt() && Pm1RegionSelection.covers(region, position.asInt())
                    && ref.matches(AA) && alt.matches(AA) && !ref.equals(alt) && keys.add(ref + position.asInt() + alt)
                    && Set.of("INDEPENDENT_PATHOGENIC", "QUALIFIED_BENIGN", "CONFLICTING", "UNRESOLVED")
                            .contains(item.path("role").asText()));
            validateSources(item, sources);
        }
    }

    static void addSources(ObjectNode facts, JsonNode record, Set<String> references) {
        var sources = facts.putArray("sources");
        for (var source : record.path("sources")) if (references.contains(source.path("sourceId").asText())) {
            var item = sources.addObject();
            for (String field : Set.of("sourceId", "url", "title", "locator")) item.set(field, source.path(field).deepCopy());
        }
    }

    static void validateSources(JsonNode value, Set<String> known) {
        var ids = value.path("sourceIds");
        var refs = sourceIds(value);
        check(array(ids, 64) && ids.valueStream().allMatch(id -> id != null && id.isTextual())
                && !refs.isEmpty() && refs.size() == ids.size() && known.containsAll(refs));
    }

    static Set<String> sourceIds(JsonNode value) {
        var ids = new HashSet<String>();
        value.path("sourceIds").forEach(id -> ids.add(id.asText()));
        return ids;
    }

    static boolean interval(JsonNode value) {
        var start = value.path("start");
        var end = value.path("end");
        return start.isIntegralNumber() && end.isIntegralNumber() && start.canConvertToInt() && end.canConvertToInt()
                && start.asInt() > 0 && end.asInt() >= start.asInt() && end.asInt() <= 100_000;
    }

    static boolean array(JsonNode value, int maximum) { return value.isArray() && value.size() <= maximum; }
    static boolean only(JsonNode value, Set<String> fields) {
        return value.isObject() && value.properties().stream().allMatch(entry -> fields.contains(entry.getKey()));
    }
    static boolean text(JsonNode value) { return value.isTextual() && !value.asText().isBlank() && value.asText().length() <= 512; }
    static void check(boolean valid) { if (!valid) throw new IllegalArgumentException("PM1_REVIEWED_REGION_CATALOG_INVALID"); }
}
