package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonCreator;
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
import java.util.Objects;
import java.util.TreeMap;

/** Server-configuration acquisition boundary, not an HTTP JSON registration/approval port.
 * All configured files are captured and pinned before request hashing. Loading original
 * signed/published claims does not independently validate their external medical truth.
 */
public final class Pp4ServerRegisteredInputs {
    public static final String SCHEMA = "pp4-server-registered-inputs-v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final FamilyDeNovoJson SHAPE = new FamilyDeNovoJson("PP4_SERVER_");
    private static final List<String> LINK_HEADERS = List.of("case_id", "person_id", "family_id", "clinical_sample_id",
            "vcf_raw_sha256", "vcf_sample_id", "vcf_sample_ordinal", "analysis_assembly", "issuer", "issued_at", "basis_record_id");
    private final Pp4ProductionMethodRegistration registration;
    private final Pp4RegisteredRuleInputs rules;
    private final List<SampleLink> links;
    private final ObjectNode identity;
    private final Map<String, byte[]> artifacts;

    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    private Pp4ServerRegisteredInputs(Pp4ProductionMethodRegistration registration, Pp4RegisteredRuleInputs rules,
            List<SampleLink> links, ObjectNode identity, Map<String, byte[]> artifacts) {
        this.registration = registration; this.rules = rules; this.links = List.copyOf(links);
        this.identity = identity.deepCopy(); this.artifacts = copiedArtifacts(artifacts);
    }
    public static Pp4ServerRegisteredInputs load(Path manifest) throws IOException {
        Objects.requireNonNull(manifest);
        Path absolute = manifest.toAbsolutePath().normalize().toRealPath(); Path directory = absolute.getParent();
        if (directory == null) throw new IOException("PP4_SERVER_MANIFEST_DIRECTORY_REQUIRED");
        byte[] bytes = bounded(absolute, 65536); ObjectNode doc;
        try {
            doc = SHAPE.parse(JSON, bytes);
            SHAPE.shape(doc, "schemaVersion:s registrationId:s registrationVersion:s registeredAt:s dataClass:s guidance:o rules:o originals:a clinicalSampleSource:!o");
            SHAPE.equal(doc, "schemaVersion", SCHEMA);
            for (String field : List.of("registrationId", "registrationVersion")) need(Pp4ProductionMethodRegistration.text(doc.path(field).asText()), "PP4_SERVER_REGISTRATION_ID_INVALID");
            Instant registeredAt = Instant.parse(doc.path("registeredAt").asText());
            var guidance = doc.path("guidance"); SHAPE.shape(guidance, "file:s rawSha256:s acquiredAt:s");
            byte[] guidanceBytes = original(directory, guidance, registeredAt);
            var rawRules = doc.path("rules"); SHAPE.shape(rawRules, "file:s rawSha256:s canonicalSha256:s acquiredAt:s");
            byte[] ruleBytes = original(directory, rawRules, registeredAt);
            var rules = Pp4RegisteredRuleInputs.load(ruleBytes, rawRules.path("rawSha256").asText(), rawRules.path("canonicalSha256").asText());
            need(doc.path("dataClass").equals(rules.identity().path("dataClass")), "PP4_SERVER_RULE_DATA_CLASS_MISMATCH");
            var artifacts = new TreeMap<String, byte[]>(); var index = JSON.createArrayNode();
            add(artifacts, index, "MANIFEST", bytes, Pp4ProductionMethodRegistration.canonical(doc));
            add(artifacts, index, "GUIDANCE", guidanceBytes, null);
            add(artifacts, index, "RULES", ruleBytes, rawRules.path("canonicalSha256").asText());
            need(doc.has("clinicalSampleSource") ? doc.path("originals").size()<=6 : doc.path("originals").size()==6,"PP4_SERVER_SIX_ORIGINAL_ROLES_REQUIRED");
            var originals = new ArrayList<Pp4ProductionMethodRegistration.PrimaryArtifact>();
            for (var node : doc.path("originals")) {
                SHAPE.shape(node, "role:s sourceId:s pmcid:s file:s rawSha256:s locator:s acquiredAt:s tableIds:o");
                byte[] raw = original(directory, node, registeredAt); var tableIds = new LinkedHashMap<String, String>();
                var fields = node.path("tableIds").properties().iterator();
                while (fields.hasNext()) { var entry = fields.next(); need(entry.getValue().isTextual(), "PP4_SERVER_TABLE_ID_INVALID"); tableIds.put(entry.getKey(), entry.getValue().asText()); }
                originals.add(new Pp4ProductionMethodRegistration.PrimaryArtifact(node.path("role").asText(), node.path("sourceId").asText(),
                        node.path("pmcid").asText(), tableIds, raw, node.path("rawSha256").asText(), node.path("locator").asText(), node.path("acquiredAt").asText()));
                add(artifacts, index, node.path("role").asText(), raw, null);
            }
            ObjectNode nativeIdentity=null;Pp4PhenopacketReplay.Replay nativeReplay=null;
            if(doc.has("clinicalSampleSource")) {
                JsonNode nativeSource=doc.path("clinicalSampleSource");
                SHAPE.shape(nativeSource,"format:s kind:s sourceId:s recordId:s creator:s file:s rawSha256:s locator:s acquiredAt:s");
                need(Pp4PhenopacketReplay.FORMAT.equals(nativeSource.path("format").asText()),"PP4_SERVER_NATIVE_FORMAT_UNSUPPORTED");
                byte[] nativeBytes=original(directory,nativeSource,registeredAt);
                nativeReplay=Pp4PhenopacketReplay.read(nativeBytes,nativeSource.path("rawSha256").asText(),nativeSource.path("kind").asText());
                nativeIdentity=verifiedNativeIdentity(nativeSource,nativeReplay);
                need(originals.stream().noneMatch(value->value.sourceId().equals(nativeSource.path("sourceId").asText())),"PP4_SERVER_NATIVE_SOURCE_ID_AMBIGUOUS");
                add(artifacts,index,"CLINICAL_STANDARD",nativeBytes,null);
            }
            var inputs=nativeReplay==null ? Pp4ProductionMethodRegistration.RegisteredInputConfiguration.load(guidanceBytes,originals,doc.path("dataClass").asText())
                    :Pp4ProductionMethodRegistration.RegisteredInputConfiguration.loadWithNativeClinicalRecord(guidanceBytes,originals,doc.path("dataClass").asText(),nativeReplay);
            var registration=Pp4ProductionMethodRegistration.register(inputs);
            List<SampleLink> links=nativeReplay==null ? sampleLinks(registration)
                    :originals.stream().noneMatch(value->value.role().equals("CLINICAL"))?List.of():nativeSampleLinks(registration,nativeReplay,Objects.requireNonNull(nativeIdentity));
            var linkIndex = JSON.createArrayNode(); links.forEach(link -> linkIndex.add(link.snapshot()));
            var id = JSON.createObjectNode().put("schemaVersion", SCHEMA)
                    .put("registrationId", doc.path("registrationId").asText()).put("registrationVersion", doc.path("registrationVersion").asText())
                    .put("registeredAt", doc.path("registeredAt").asText()).put("dataClass", doc.path("dataClass").asText())
                    .put("manifestRawSha256", Pp4ProductionMethodRegistration.rawSha(bytes)).put("manifestCanonicalSha256", Pp4ProductionMethodRegistration.canonical(doc))
                    .put("registrationSha256", registration.sha256()).put("registeredRuleInputsSha256", rules.sha256())
                    .put("clinicalSampleLinksSha256", Pp4ProductionMethodRegistration.canonical(linkIndex)).put("clinicalSampleLinkCount", links.size())
                    .put("authorityBoundary", "SERVER_CONFIGURATION_REGISTERED_ORIGINALS_NOT_CALLER_JSON")
                    .put("sampleOrdinalConvention", "ONE_BASED_GENOTYPE_SAMPLE_INDEX")
                    .put("vcfDigestConvention", "EXACT_ANALYSIS_VCF_BYTES_USED_FOR_ANALYSIS_VCF_CALL");
            id.set("archiveIndex", index);
            if(nativeIdentity!=null) id.set("clinicalSampleSource",nativeIdentity);
            for(String field:List.of("nativeClinicalSourceQuality","nativeClinicalProjectionFailure"))
                if(inputs.identity().has(field)) id.set(field,inputs.identity().path(field));
            var clinicalProjection=inputs.nativeClinicalProjection();
            if(clinicalProjection.isPresent()) {
                var projection=clinicalProjection.orElseThrow();byte[] projectionBytes=JSON.writeValueAsBytes(projection.snapshot());
                String pin=Pp4ProductionMethodRegistration.rawSha(projectionBytes),canonicalPin=Pp4ProductionMethodRegistration.canonical(projection.snapshot());
                add(artifacts,index,"CLINICAL_PROJECTION",projectionBytes,canonicalPin);
                var projectionIdentity=projection.identity().put("rawSha256",pin).put("canonicalSha256",canonicalPin);
                id.set("clinicalProjection",projectionIdentity);
            }
            return new Pp4ServerRegisteredInputs(registration, rules, links, id, artifacts);
        } catch (IllegalArgumentException invalid) { throw new IOException("PP4_SERVER_MANIFEST_OR_ORIGINAL_INVALID", invalid); }
    }
    public Pp4ProductionMethodRegistration registration() { return registration; }
    public Pp4RegisteredRuleInputs rules() { return rules; }
    public ObjectNode identity() { return identity.deepCopy(); }
    public List<SampleLink> clinicalSampleLinks() { return links; }
    public Map<String, byte[]> archiveArtifacts() { return copiedArtifacts(artifacts); }

    /** A factual association issued only by direct replay of the registered CLINICAL original.
     * It does not prove genotype, native VCF call, consumer completion or clinical adoption.
     */
    public static final class SampleLink {
        private final ObjectNode value;
        private final String registrationSha256;
        @JsonCreator(mode = JsonCreator.Mode.DISABLED)
        private SampleLink(ObjectNode value, String registrationSha256) { this.value = value.deepCopy(); this.registrationSha256 = registrationSha256; }
        public String caseId() { return value.path("caseId").asText(); }
        public String personId() { return value.path("personId").asText(); }
        public String familyId() { return value.path("familyId").asText(); }
        public String clinicalSampleId() { return value.path("clinicalSampleId").asText(); }
        public String vcfRawSha256() { return value.path("vcfRawSha256").asText(); }
        public String vcfSampleId() { return value.path("vcfSampleId").asText(); }
        public int sampleOrdinal() { return value.path("sampleOrdinal").asInt(); }
        public String analysisAssembly() { return value.path("analysisAssembly").asText(); }
        public ObjectNode snapshot() { return value.deepCopy(); }
        public boolean matchesNativeCall(JsonNode actualProof) {
            if(value.hasNonNull("nativeAnalysisVcfUriSha256"))
                return actualProof.path("analysisVcfUriSha256").equals(value.path("nativeAnalysisVcfUriSha256"))
                        && vcfSampleId().equals(actualProof.path("vcfSampleId").asText())
                        && analysisAssembly().equals(actualProof.path("assembly").asText())
                        && actualProof.path("sampleOrdinal").asInt(0)>0
                        && Pp4ProductionMethodRegistration.hash(actualProof.path("vcfRawSha256").asText());
            return vcfRawSha256().equals(actualProof.path("vcfRawSha256").asText())
                    && vcfSampleId().equals(actualProof.path("vcfSampleId").asText())
                    && sampleOrdinal()==actualProof.path("sampleOrdinal").asInt()
                    && analysisAssembly().equals(actualProof.path("assembly").asText());
        }
        public boolean matches(Pp4QualifiedMaterial material) {
            if (material == null || !registrationSha256.equals(material.registration().sha256())) return false;
            var c = material.nativeFacts().path("case");
            return caseId().equals(c.path("case_id").asText()) && personId().equals(c.path("person_id").asText())
                    && familyId().equals(c.path("family_id").asText()) && clinicalSampleId().equals(c.path("sample_id").asText())
                    && analysisAssembly().equals(c.path("assembly").asText());
        }
    }
    /** Source format and server acquisition checks, not a diagnostic approval. */
    public static ObjectNode verifiedNativeIdentity(JsonNode source,Pp4PhenopacketReplay.Replay replay) throws IOException {
        var facts=replay.snapshot();
        need(Pp4PhenopacketReplay.FORMAT.equals(source.path("format").asText())
                && source.path("kind").equals(facts.path("kind")) && source.path("recordId").equals(facts.path("recordId"))
                && source.path("creator").equals(facts.path("creator")) && source.path("rawSha256").equals(facts.path("rawSha256")),
                "PP4_SERVER_NATIVE_ORIGINAL_IDENTITY_MISMATCH");
        need(Pp4ProductionMethodRegistration.text(source.path("sourceId").asText()),"PP4_SERVER_NATIVE_SOURCE_ID_REQUIRED");
        try {
            java.net.URI locator=java.net.URI.create(source.path("locator").asText());
            need(locator.isAbsolute() && java.util.Set.of("https","urn").contains(locator.getScheme())
                    && locator.getUserInfo()==null && locator.getRawQuery()==null && locator.getFragment()==null
                    && (!"https".equals(locator.getScheme()) || locator.getHost()!=null),"PP4_SERVER_NATIVE_LOCATOR_INVALID");
            Instant acquiredAt=Instant.parse(source.path("acquiredAt").asText());
            need(!Instant.parse(facts.path("created").asText()).isAfter(acquiredAt),"PP4_SERVER_NATIVE_ACQUISITION_MISMATCH");
            for(var packet:facts.path("packets")) need(!Instant.parse(packet.path("created").asText()).isAfter(acquiredAt),"PP4_SERVER_NATIVE_ACQUISITION_MISMATCH");
        } catch(IllegalArgumentException|java.time.DateTimeException invalid) { throw new IOException("PP4_SERVER_NATIVE_ACQUISITION_INVALID",invalid); }
        var identity=JSON.createObjectNode();
        for(String field:List.of("format","kind","sourceId","recordId","creator","rawSha256","locator","acquiredAt")) identity.set(field,source.path(field).deepCopy());
        identity.set("readerIdentity",facts.path("readerIdentity").deepCopy());
        identity.set("declaredSchemaVersion",facts.path("declaredSchemaVersion").deepCopy());
        identity.set("factsSha256",facts.path("factsSha256").deepCopy());
        identity.put("clinicalAuthority",false).put("useScope","EXPLICIT_SAMPLE_ASSOCIATION_ONLY_OTHER_CLINICAL_ASSERTIONS_RETAINED_NOT_ADOPTED");
        return identity;
    }
    private static List<SampleLink> nativeSampleLinks(Pp4ProductionMethodRegistration registration,
            Pp4PhenopacketReplay.Replay replay,ObjectNode sourceIdentity) throws IOException {
        var clinical=registration.registeredInputs().originalArtifacts().stream().filter(a->a.role().equals("CLINICAL")).findFirst()
                .orElseThrow(()->new IOException("PP4_SERVER_CLINICAL_ORIGINAL_REQUIRED"));
        var cases=Pp4PrimaryTableReplay.replay(clinical.raw(),clinical.expectedRawSha256(),clinical.pmcid(),clinical.tableIds().get("case"));
        var headers=Pp4NativeMaterialVerifier.headers().get("case");
        need(cases.headerRows().size()==1 && cases.bodyRows().size()==1 && cases.headerRows().get(0).cells().size()==headers.size()
                && cases.bodyRows().get(0).cells().size()==headers.size(),"PP4_SERVER_CLINICAL_CASE_UNRESOLVED");
        var record=new LinkedHashMap<String,String>();
        for(int i=0;i<headers.size();i++) {
            var header=cases.headerRows().get(0).cells().get(i);var cell=cases.bodyRows().get(0).cells().get(i);
            need(header.text().equals(headers.get(i))&&header.rowSpan()==1&&header.columnSpan()==1&&cell.rowSpan()==1&&cell.columnSpan()==1&&cell.references().isEmpty(),"PP4_SERVER_CLINICAL_CASE_UNRESOLVED");
            record.put(headers.get(i),cell.text().trim());
        }
        var facts=replay.snapshot();var links=new ArrayList<SampleLink>();
        for(var mapping:facts.path("sampleAssociations")) {
            // Exact patient AND original biosample are required for this first native profile.
            // A subject-only file mapping remains available as a fact but cannot invent a clinical sample ID.
            if(!mapping.path("subjectId").asText().equals(record.get("person_id")) || !mapping.path("biosampleId").isTextual()
                    || !mapping.path("biosampleId").asText().equals(record.get("sample_id"))) continue;
            if(facts.path("kind").asText().equals("FAMILY")) need(facts.path("recordId").asText().equals(record.get("family_id")),"PP4_SERVER_NATIVE_FAMILY_SCOPE_MISMATCH");
            String assembly=mapping.path("genomeAssembly").asText();
            if(!assembly.equals(record.get("assembly")) || !"GRCh38".equals(assembly)) continue;
            String format=mapping.has("fileFormat")?mapping.path("fileFormat").asText():mapping.path("htsFormat").asText();
            if(!"VCF".equalsIgnoreCase(format)) continue;
            String uriPin;
            try { uriPin=Pp4PhenopacketReplay.localFileIdentitySha256(mapping.path("fileUri").asText()); }
            catch(IOException unsupportedLocation) { continue; } // Replay kept; no URI is fetched or granted by its format alone.
            var value=JSON.createObjectNode().put("schemaVersion","pp4-registered-clinical-vcf-sample-link-v1")
                    .put("caseId",record.get("case_id")).put("personId",record.get("person_id")).put("familyId",record.get("family_id"))
                    .put("clinicalSampleId",record.get("sample_id")).put("vcfSampleId",mapping.path("vcfSampleId").asText())
                    .put("analysisAssembly",assembly).put("nativeAnalysisVcfUriSha256",uriPin)
                    .putNull("vcfRawSha256").putNull("sampleOrdinal")
                    .put("sampleOrdinalConvention","DERIVE_ONLY_FROM_VERIFIED_CURRENT_NATIVE_HEADER")
                    .put("issuer",facts.path("creator").asText()).put("issuedAt",facts.path("created").asText())
                    .put("basisRecordId",facts.path("recordId").asText());
            var ref=value.putObject("sourceRef").put("kind",Pp4PhenopacketReplay.FORMAT)
                    .put("sourceId",sourceIdentity.path("sourceId").asText()).put("rawSha256",sourceIdentity.path("rawSha256").asText())
                    .put("jsonPointer",mapping.path("jsonPointer").asText()).put("factsSha256",facts.path("factsSha256").asText());
            ref.putObject("joinedClinicalCase").put("sourceId",clinical.sourceId()).put("rawSha256",clinical.expectedRawSha256())
                    .put("tableId",cases.tableId()).put("bodyRowNumber",1);
            links.add(new SampleLink(value,registration.sha256()));
        }
        // Multiple candidate associations are never resolved by traversal order.
        need(links.size()<=1,"PP4_SERVER_NATIVE_SAMPLE_ASSOCIATION_AMBIGUOUS");
        return List.copyOf(links);
    }
    private static List<SampleLink> sampleLinks(Pp4ProductionMethodRegistration registration) throws IOException {
        var clinical = registration.registeredInputs().originalArtifacts().stream().filter(a -> a.role().equals("CLINICAL")).findFirst()
                .orElseThrow(() -> new IOException("PP4_SERVER_CLINICAL_ORIGINAL_REQUIRED"));
        need(clinical.tableIds().containsKey("sample_links"), "PP4_SERVER_CLINICAL_SAMPLE_LINKS_REQUIRED");
        var table = Pp4PrimaryTableReplay.replay(clinical.raw(), clinical.expectedRawSha256(), clinical.pmcid(), clinical.tableIds().get("sample_links"));
        need(table.headerRows().size() == 1 && table.footerRows().isEmpty() && table.headerRows().get(0).cells().size() == LINK_HEADERS.size(), "PP4_SERVER_SAMPLE_LINK_HEADERS_INVALID");
        for (int i = 0; i < LINK_HEADERS.size(); i++) {
            var cell = table.headerRows().get(0).cells().get(i);
            need(cell.text().equals(LINK_HEADERS.get(i)) && cell.rowSpan() == 1 && cell.columnSpan() == 1, "PP4_SERVER_SAMPLE_LINK_HEADERS_INVALID");
        }
        need(!table.bodyRows().isEmpty(), "PP4_SERVER_CLINICAL_SAMPLE_LINKS_REQUIRED");
        need(clinical.tableIds().containsKey("case"), "PP4_SERVER_CLINICAL_CASE_REQUIRED");
        var caseTable = Pp4PrimaryTableReplay.replay(clinical.raw(), clinical.expectedRawSha256(), clinical.pmcid(), clinical.tableIds().get("case"));
        var caseHeaders = Pp4NativeMaterialVerifier.headers().get("case");
        need(caseTable.headerRows().size() == 1 && caseTable.bodyRows().size() == 1 && caseTable.footerRows().isEmpty()
                && caseTable.headerRows().get(0).cells().size() == caseHeaders.size() && caseTable.bodyRows().get(0).cells().size() == caseHeaders.size(), "PP4_SERVER_CLINICAL_CASE_UNRESOLVED");
        var nativeCase = new LinkedHashMap<String, String>();
        for (int i = 0; i < caseHeaders.size(); i++) {
            var header = caseTable.headerRows().get(0).cells().get(i); var cell = caseTable.bodyRows().get(0).cells().get(i);
            need(header.text().equals(caseHeaders.get(i)) && header.rowSpan() == 1 && header.columnSpan() == 1
                    && cell.rowSpan() == 1 && cell.columnSpan() == 1 && cell.references().isEmpty(), "PP4_SERVER_CLINICAL_CASE_UNRESOLVED");
            nativeCase.put(caseHeaders.get(i), cell.text().trim());
        }
        var result = new ArrayList<SampleLink>(); var samples = new HashSet<String>(); var records = new HashSet<String>();
        for (var row : table.bodyRows()) {
            need(row.cells().size() == LINK_HEADERS.size(), "PP4_SERVER_SAMPLE_LINK_ROW_INVALID");
            var values = new LinkedHashMap<String, String>();
            for (int i = 0; i < LINK_HEADERS.size(); i++) {
                var cell = row.cells().get(i);
                need(cell.rowSpan() == 1 && cell.columnSpan() == 1 && cell.references().isEmpty()
                        && Pp4ProductionMethodRegistration.text(cell.text()), "PP4_SERVER_SAMPLE_LINK_CELL_INVALID");
                values.put(LINK_HEADERS.get(i), cell.text());
            }
            need(values.get("case_id").equals(nativeCase.get("case_id")) && values.get("person_id").equals(nativeCase.get("person_id"))
                    && values.get("family_id").equals(nativeCase.get("family_id")) && values.get("clinical_sample_id").equals(nativeCase.get("sample_id"))
                    && values.get("analysis_assembly").equals(nativeCase.get("assembly")), "PP4_SERVER_SAMPLE_LINK_CLINICAL_SCOPE_MISMATCH");
            String ordinal = values.get("vcf_sample_ordinal");
            need(ordinal.matches("[1-9][0-9]*"), "PP4_SERVER_SAMPLE_LINK_ORDINAL_INVALID"); int sample = Integer.parseInt(ordinal);
            need(Pp4ProductionMethodRegistration.hash(values.get("vcf_raw_sha256")) && "GRCh38".equals(values.get("analysis_assembly")), "PP4_SERVER_SAMPLE_LINK_SOURCE_INVALID");
            need(!Instant.parse(values.get("issued_at")).isAfter(Instant.parse(clinical.acquiredAt())), "PP4_SERVER_SAMPLE_LINK_ACQUISITION_MISMATCH");
            need(samples.add(values.get("vcf_raw_sha256") + ":" + sample) && records.add(values.get("basis_record_id")), "PP4_SERVER_SAMPLE_LINK_AMBIGUOUS");
            for (String field : List.of("issuer", "basis_record_id")) need(!java.util.Set.of("UNKNOWN", "PENDING", "NOT_PERFORMED").contains(values.get(field)), "PP4_SERVER_SAMPLE_LINK_ISSUER_REQUIRED");
            var value = JSON.createObjectNode().put("schemaVersion", "pp4-registered-clinical-vcf-sample-link-v1");
            for (var pair : Map.of("caseId", "case_id", "personId", "person_id", "familyId", "family_id", "clinicalSampleId", "clinical_sample_id",
                    "vcfRawSha256", "vcf_raw_sha256", "vcfSampleId", "vcf_sample_id", "analysisAssembly", "analysis_assembly",
                    "issuer", "issuer", "issuedAt", "issued_at", "basisRecordId", "basis_record_id").entrySet()) value.put(pair.getKey(), values.get(pair.getValue()));
            value.put("sampleOrdinal", sample);
            var ref = value.putObject("sourceRef").put("sourceId", clinical.sourceId()).put("rawSha256", clinical.expectedRawSha256())
                    .put("tableId", table.tableId()).put("tableLocator", table.tableLocator()).put("sourceRowNumber", row.sourceRowNumber()).put("bodyRowNumber", row.sectionRowNumber());
            var cells = ref.putArray("cells"); row.cells().forEach(cell -> cells.addObject().put("cellNumber", cell.cellNumber()).put("locator", cell.locator()).put("text", cell.text()));
            result.add(new SampleLink(value, registration.sha256()));
        }
        return List.copyOf(result);
    }
    private static byte[] original(Path directory, JsonNode node, Instant registeredAt) throws IOException {
        need(Pp4ProductionMethodRegistration.hash(node.path("rawSha256").asText()), "PP4_SERVER_ORIGINAL_PIN_REQUIRED");
        Path relative = Path.of(node.path("file").asText());
        need(!relative.isAbsolute(), "PP4_SERVER_ORIGINAL_PATH_OUTSIDE_BUNDLE");
        Path file = directory.resolve(relative).normalize(); need(file.startsWith(directory), "PP4_SERVER_ORIGINAL_PATH_OUTSIDE_BUNDLE");
        file = file.toRealPath(); need(file.startsWith(directory), "PP4_SERVER_ORIGINAL_PATH_OUTSIDE_BUNDLE");
        byte[] bytes = bounded(file, 1048576);
        need(Pp4ProductionMethodRegistration.rawSha(bytes).equals(node.path("rawSha256").asText()), "PP4_SERVER_ORIGINAL_PIN_MISMATCH");
        need(!Instant.parse(node.path("acquiredAt").asText()).isAfter(registeredAt), "PP4_SERVER_REGISTRATION_CHRONOLOGY_INVALID");
        return bytes;
    }
    private static byte[] bounded(Path path, int maximum) throws IOException {
        need(Files.isRegularFile(path), "PP4_SERVER_ORIGINAL_FILE_REQUIRED");
        try (var input = Files.newInputStream(path)) { byte[] value = input.readNBytes(maximum + 1); need(value.length > 0 && value.length <= maximum, "PP4_SERVER_ORIGINAL_CAPACITY_UNSUPPORTED"); return value; }
    }
    private static void add(Map<String, byte[]> bytes, com.fasterxml.jackson.databind.node.ArrayNode index, String kind, byte[] original, String canonical) throws IOException {
        String sha = Pp4ProductionMethodRegistration.rawSha(original); byte[] previous = bytes.putIfAbsent(sha, original.clone());
        need(previous == null || java.util.Arrays.equals(previous, original), "PP4_SERVER_ARTIFACT_PIN_CONFLICT");
        var entry = index.addObject().put("kind", kind).put("rawSha256", sha).put("byteLength", original.length);
        if (canonical != null) entry.put("canonicalSha256", canonical);
    }
    private static Map<String, byte[]> copiedArtifacts(Map<String, byte[]> input) {
        var result = new TreeMap<String, byte[]>(); input.forEach((pin, raw) -> result.put(pin, raw.clone())); return java.util.Collections.unmodifiableMap(result);
    }
    private static void need(boolean condition, String reason) throws IOException { if (!condition) throw new IOException(reason); }
}
