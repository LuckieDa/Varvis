package org.nbscn.varvis.variantanalysis.benchmark.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.lang.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Generates reviewer-visible HPO packets without copying diagnostic truth fields. */
public class TruthMaskedHpoReviewPacketGenerator {
    public static final String PACKET_SCHEMA_VERSION = "truth-masked-hpo-review-packet-v1";
    public static final String DECISION_SCHEMA_VERSION = "truth-masked-hpo-review-decision-v1";
    public static final String REVIEW_PROTOCOL_VERSION = "truth-masked-development-review-v1";
    public static final String GENERATOR_VERSION = "hpo-review-packet-generator-v1";
    public static final String SOURCE_CONTEXT_UNAVAILABLE = "PATIENT_SPECIFIC_SOURCE_CONTEXT_UNAVAILABLE";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().enable(SerializationFeature.INDENT_OUTPUT);

    public HpoReviewGenerationResult generate(Path datasetManifest, Path selectionAcceptance, Path outputDirectory) throws IOException {
        return generate(datasetManifest, selectionAcceptance, outputDirectory,
                HpoReviewPacketGenerationOptions.smokeDefaults());
    }

    public HpoReviewGenerationResult generate(Path datasetManifest, Path selectionAcceptance,
                                               Path outputDirectory,
                                               HpoReviewPacketGenerationOptions options) throws IOException {
        JsonNode dataset = mapper.readTree(datasetManifest.toFile());
        JsonNode acceptance = mapper.readTree(selectionAcceptance.toFile());
        String datasetId = requiredText(dataset, "datasetId");
        String datasetVersion = requiredText(dataset, "datasetVersion");
        Map<String, String> sourceChecksums = sourceChecksums(acceptance);
        List<CaseInput> cases = inputs(dataset, sourceChecksums);
        if (cases.size() != options.getExpectedCaseCount()) {
            throw new IOException("REVIEW_PACKET_CASE_COUNT_INVALID: expected "
                    + options.getExpectedCaseCount() + " cases");
        }
        Collections.sort(cases, Comparator
                .comparing((@NonNull CaseInput input) -> input.getSourceChecksum())
                .thenComparing((@NonNull CaseInput input) -> input.getCaseId()));

        Path reviewerDirectory = outputDirectory.resolve("reviewer-visible");
        Path internalDirectory = outputDirectory.resolve("internal");
        Files.createDirectories(reviewerDirectory); Files.createDirectories(internalDirectory);
        List<HpoReviewPacket> packets = new ArrayList<HpoReviewPacket>();
        List<Map<String, String>> mappings = new ArrayList<Map<String, String>>();
        List<Map<String, String>> index = new ArrayList<Map<String, String>>();
        for (int i = 0; i < cases.size(); i++) {
            CaseInput input = cases.get(i);
            String reviewId = String.format(options.getReviewIdPrefix() + "%03d", i + 1);
            HpoReviewPacket packet = packet(input, datasetId, datasetVersion, reviewId,
                    options.getHpoOntologyVersion());
            HpoReviewDecisionDocument template = template(packet, options.getPriorTruthExposure());
            new HpoReviewDecisionValidator().validate(packet, template);
            writeJson(reviewerDirectory.resolve(reviewId + ".packet.json"), packet);
            writeDecisionTemplate(reviewerDirectory.resolve(reviewId + ".decision-template.json"),
                    template, options);
            writeText(reviewerDirectory.resolve(reviewId + ".review.md"), markdown(packet));
            packets.add(packet);
            Map<String, String> mapping = new LinkedHashMap<String, String>();
            mapping.put("reviewId", reviewId); mapping.put("caseId", input.getCaseId());
            mapping.put("packetFingerprint", packet.getPacketFingerprint()); mapping.put("datasetId", datasetId);
            mappings.add(mapping);
            Map<String, String> visible = new LinkedHashMap<String, String>();
            visible.put("reviewId", reviewId); visible.put("packetFingerprint", packet.getPacketFingerprint());
            visible.put("packetFile", reviewId + ".packet.json"); visible.put("decisionTemplateFile", reviewId + ".decision-template.json");
            index.add(visible);
        }
        Map<String, Object> reviewerIndex = new LinkedHashMap<String, Object>();
        reviewerIndex.put("schemaVersion", "truth-masked-hpo-review-index-v1"); reviewerIndex.put("reviewProtocolVersion", REVIEW_PROTOCOL_VERSION);
        reviewerIndex.put("purpose", options.getPurpose()); reviewerIndex.put("reviews", index);
        writeJson(reviewerDirectory.resolve("review-index.json"), reviewerIndex);
        Map<String, Object> internal = new LinkedHashMap<String, Object>();
        internal.put("schemaVersion", "truth-masked-hpo-review-internal-mapping-v1"); internal.put("handling", "DO_NOT_PROVIDE_TO_REVIEWER");
        internal.put("purpose", "minimal review-ID to frozen-case mapping for later G2 writeback"); internal.put("mappings", mappings);
        writeJson(internalDirectory.resolve("DO_NOT_PROVIDE_TO_REVIEWER.mapping.json"), internal);
        writeText(reviewerDirectory.resolve("REVIEW_INSTRUCTIONS_zh-CN.md"), instructions(options));
        return new HpoReviewGenerationResult(packets, reviewerDirectory, internalDirectory);
    }

    public void assertReviewerVisibleContentIsMasked(Path reviewerDirectory, List<String> forbiddenValues) throws IOException {
        try (Stream<Path> files = Files.list(reviewerDirectory)) {
            for (Path file : files.filter(Files::isRegularFile).toArray(Path[]::new)) {
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (content.matches("(?s).*\\b[A-Za-z]:[\\\\/].*|.*/home/.*|.*/Users/.*")) throw new IOException("REVIEW_PACKET_UNSAFE_PATH: " + file.getFileName());
                for (String forbidden : forbiddenValues) {
                    if (forbidden != null && !forbidden.trim().isEmpty() && content.contains(forbidden)) {
                        throw new IOException("REVIEW_PACKET_TRUTH_LEAK: " + file.getFileName());
                    }
                }
            }
        }
    }

    private HpoReviewPacket packet(CaseInput input, String datasetId, String datasetVersion,
                                   String reviewId, String hpoOntologyVersion) {
        HpoReviewPacket packet = new HpoReviewPacket();
        packet.setSchemaVersion(PACKET_SCHEMA_VERSION); packet.setReviewProtocolVersion(REVIEW_PROTOCOL_VERSION); packet.setReviewId(reviewId);
        packet.setDatasetId(datasetId); packet.setDatasetVersion(datasetVersion); packet.setHpoOntologyVersion(hpoOntologyVersion);
        packet.setGeneratorVersion(GENERATOR_VERSION); packet.setSourceContext(SOURCE_CONTEXT_UNAVAILABLE);
        List<HpoReviewTerm> terms = new ArrayList<HpoReviewTerm>();
        for (String id : input.getObserved()) terms.add(term(id, input.label(id), HpoReviewOriginalStatus.OBSERVED));
        for (String id : input.getExcluded()) terms.add(term(id, input.label(id), HpoReviewOriginalStatus.EXCLUDED));
        packet.setTerms(terms);
        packet.setPacketContentChecksum(calculatePacketContentChecksum(packet));
        packet.setPacketFingerprint(fingerprint(datasetId, datasetVersion, input.getSourceChecksum(), packet));
        return packet;
    }

    private HpoReviewTerm term(String id, String label, HpoReviewOriginalStatus status) {
        HpoReviewTerm term = new HpoReviewTerm(); term.setHpoId(id); term.setLabel(label); term.setOriginalStatus(status); term.setOnset(null);
        term.setDecisionOptions(status == HpoReviewOriginalStatus.OBSERVED
                ? Arrays.asList("KEEP_OBSERVED", "REMOVE_MAPPING_ERROR", "UNCERTAIN")
                : Arrays.asList("KEEP_EXCLUDED", "REMOVE_EXCLUSION_MAPPING_ERROR", "UNCERTAIN"));
        return term;
    }

    private HpoReviewDecisionDocument template(HpoReviewPacket packet, String priorTruthExposure) {
        HpoReviewDecisionDocument document = new HpoReviewDecisionDocument();
        document.setSchemaVersion(DECISION_SCHEMA_VERSION); document.setReviewProtocolVersion(REVIEW_PROTOCOL_VERSION);
        document.setReviewId(packet.getReviewId()); document.setPacketFingerprint(packet.getPacketFingerprint());
        document.setPacketContentChecksum(packet.getPacketContentChecksum());
        document.setReviewMethod("TRUTH_MASKED_DEVELOPMENT_REVIEW"); document.setReviewerRole("UNASSIGNED");
        document.setPriorTruthExposure(priorTruthExposure); document.setActiveTruthReviewReported(Boolean.FALSE);
        document.setEligibleForStrictBlindedEvaluation(Boolean.FALSE); document.setSourceContext(packet.getSourceContext());
        document.setReviewCompletionStatus(HpoReviewReadinessStatus.HUMAN_REVIEW_PENDING);
        List<HpoReviewTermDecision> decisions = new ArrayList<HpoReviewTermDecision>();
        for (HpoReviewTerm term : packet.getTerms()) { HpoReviewTermDecision decision = new HpoReviewTermDecision(); decision.setHpoId(term.getHpoId()); decision.setOriginalStatus(term.getOriginalStatus()); decisions.add(decision); }
        document.setTermDecisions(decisions); return document;
    }

    private List<CaseInput> inputs(JsonNode dataset, Map<String, String> sourceChecksums) throws IOException {
        List<CaseInput> cases = new ArrayList<CaseInput>();
        for (JsonNode node : dataset.path("cases")) {
            String caseId = requiredText(node, "caseId"); String sourceChecksum = sourceChecksums.get(caseId);
            if (sourceChecksum == null) throw new IOException("REVIEW_PACKET_SOURCE_CHECKSUM_MISSING");
            List<String> observed = strings(node.path("originalHpoTerms")); List<String> excluded = strings(node.path("excludedHpoTerms"));
            Map<String, String> labels = mapper.convertValue(node.path("hpoTermLabels"), new TypeReference<Map<String, String>>() { });
            cases.add(new CaseInput(caseId, sourceChecksum, observed, excluded, labels));
        }
        return cases;
    }

    private Map<String, String> sourceChecksums(JsonNode acceptance) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (JsonNode node : acceptance.path("cases")) result.put(node.path("caseId").asText(), node.path("phenopacketSha256").asText());
        return result;
    }

    private List<String> strings(JsonNode node) { List<String> result = new ArrayList<String>(); for (JsonNode item : node) result.add(item.asText()); Collections.sort(result); return result; }
    private String requiredText(JsonNode node, String field) throws IOException { String value = node.path(field).asText(); if (value.trim().isEmpty()) throw new IOException("REVIEW_PACKET_REQUIRED_FIELD_MISSING: " + field); return value; }
    private String fingerprint(String datasetId, String datasetVersion, String sourceChecksum, HpoReviewPacket packet) {
        StringBuilder value = new StringBuilder(datasetId).append('|').append(datasetVersion).append('|').append(sourceChecksum).append('|')
                .append(packet.getPacketContentChecksum());
        return sha256(value.toString());
    }
    public static String calculatePacketContentChecksum(HpoReviewPacket packet) {
        StringBuilder value = new StringBuilder(packet.getSchemaVersion()).append('|').append(packet.getReviewProtocolVersion()).append('|')
                .append(packet.getReviewId()).append('|').append(packet.getDatasetId()).append('|').append(packet.getDatasetVersion()).append('|')
                .append(packet.getHpoOntologyVersion()).append('|').append(packet.getGeneratorVersion()).append('|').append(packet.getSourceContext());
        for (HpoReviewTerm term : packet.getTerms()) value.append('|').append(term.getHpoId()).append('|').append(term.getLabel()).append('|').append(term.getOriginalStatus()).append('|').append(term.getOnset());
        try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format("%02x", b)); return result.toString(); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String sha256(String value) { try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder result = new StringBuilder(); for (byte b : bytes) result.append(String.format("%02x", b)); return result.toString(); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    private String markdown(HpoReviewPacket packet) {
        StringBuilder text = new StringBuilder();
        text.append("# Truth-masked HPO review: ").append(packet.getReviewId()).append("\n\n");
        text.append("Packet fingerprint: `").append(packet.getPacketFingerprint()).append("`\n\n");
        text.append("This is a development review packet. It is not a strict blinded evaluation. Do not use genes, diseases, variants, rankings, or external disease resources to add terms.\n\n");
        text.append("Patient-specific source context: `").append(packet.getSourceContext()).append("`\n\n");
        text.append("| HPO | Label | Original status | Allowed decision | Mapping-error reason if removed |\n|---|---|---|---|---|\n");
        for (HpoReviewTerm term : packet.getTerms()) text.append('|').append(term.getHpoId()).append('|').append(term.getLabel()).append('|').append(term.getOriginalStatus()).append('|').append(String.join(", ", term.getDecisionOptions())).append("||\n");
        text.append("\nDo not add HPO terms because this packet has no patient-specific source context. Mark uncertain terms as `UNCERTAIN`; an uncertain review is incomplete. Save decisions only in the matching JSON template.\n");
        return text.toString();
    }
    private String instructions(HpoReviewPacketGenerationOptions options) {
        if ("SMOKE-HPO-".equals(options.getReviewIdPrefix())) {
            return instructions();
        }
        if ("PILOT-HPO-".equals(options.getReviewIdPrefix())) {
            return pilotInstructions(options);
        }
        return "# Truth-masked HPO 复核说明\n\n"
                + "1. 只打开本目录中以 `" + options.getReviewIdPrefix()
                + "` 开头的 `.review.md`、`.packet.json` 和 `.decision-template.json`。\n"
                + "2. 不要打开 `../internal/DO_NOT_PROVIDE_TO_REVIEWER.mapping.json`，也不要查看病例 truth、gene、disease 或排名资料。\n"
                + "3. Observed term 只能填写 `KEEP_OBSERVED`、`REMOVE_MAPPING_ERROR` 或 `UNCERTAIN`；Excluded term 只能填写 `KEEP_EXCLUDED`、`REMOVE_EXCLUSION_MAPPING_ERROR` 或 `UNCERTAIN`。\n"
                + "4. 删除 term 必须填写 mapping-error 理由；`UNCERTAIN` 表示复核尚未完成。\n"
                + "5. 当前 packet 没有患者特异的原始临床文本，因此不得新增 HPO，也不得根据疾病、基因、变异或典型症状推断表型。\n"
                + "6. 这是 development pilot 的 truth-masked 人工复核材料，不是严格盲法临床评估。\n"
                + "7. 在对应 JSON 模板中填写决定，并保留 `reviewId`、`packetFingerprint` 和 `packetContentChecksum`。\n"
                + "8. 全部完成后只需告知 Codex 已完成复核；完成前不得运行 G2，也不需要上传论文、患者 VCF 或医院病例。\n";
    }

    private String pilotInstructions(HpoReviewPacketGenerationOptions options) {
        return "# Truth-masked HPO \u590d\u6838\u8bf4\u660e\n\n"
                + "1. \u53ea\u6253\u5f00\u672c\u76ee\u5f55\u4e2d\u4ee5 `" + options.getReviewIdPrefix()
                + "` \u5f00\u5934\u7684 `.review.md`\u3001`.packet.json` \u548c `.decision-template.json`\u3002\n"
                + "2. \u4e0d\u8981\u6253\u5f00 `../internal/DO_NOT_PROVIDE_TO_REVIEWER.mapping.json`\uff0c\u4e5f\u4e0d\u8981\u67e5\u770b\u75c5\u4f8b truth\u3001gene\u3001disease \u6216\u6392\u540d\u8d44\u6599\u3002\n"
                + "3. Observed term \u53ea\u80fd\u586b\u5199 `KEEP_OBSERVED`\u3001`REMOVE_MAPPING_ERROR` \u6216 `UNCERTAIN`\uff1bExcluded term \u53ea\u80fd\u586b\u5199 `KEEP_EXCLUDED`\u3001`REMOVE_EXCLUSION_MAPPING_ERROR` \u6216 `UNCERTAIN`\u3002\n"
                + "4. \u5220\u9664 term \u5fc5\u987b\u586b\u5199 mapping-error \u7406\u7531\uff1b`UNCERTAIN` \u8868\u793a\u590d\u6838\u5c1a\u672a\u5b8c\u6210\u3002\n"
                + "5. \u5f53\u524d packet \u6ca1\u6709\u60a3\u8005\u7279\u5f02\u7684\u539f\u59cb\u4e34\u5e8a\u6587\u672c\uff0c\u56e0\u6b64\u4e0d\u5f97\u65b0\u589e HPO\uff0c\u4e5f\u4e0d\u5f97\u6839\u636e\u75be\u75c5\u3001\u57fa\u56e0\u3001\u53d8\u5f02\u6216\u5178\u578b\u75c7\u72b6\u63a8\u65ad\u8868\u578b\u3002\n"
                + "6. \u8fd9\u662f development pilot \u7684 truth-masked \u4eba\u5de5\u590d\u6838\u6750\u6599\uff0c\u4e0d\u662f\u4e25\u683c\u76f2\u6cd5\u4e34\u5e8a\u8bc4\u4f30\u3002\n"
                + "7. \u5728\u5bf9\u5e94 JSON \u6a21\u677f\u4e2d\u586b\u5199\u51b3\u5b9a\uff0c\u5e76\u4fdd\u7559 `reviewId`\u3001`packetFingerprint` \u548c `packetContentChecksum`\u3002\n"
                + "8. \u5168\u90e8\u5b8c\u6210\u540e\u53ea\u9700\u544a\u77e5 Codex \u5df2\u5b8c\u6210\u590d\u6838\uff1b\u5b8c\u6210\u524d\u4e0d\u5f97\u8fd0\u884c G2\uff0c\u4e5f\u4e0d\u9700\u8981\u4e0a\u4f20\u8bba\u6587\u3001\u60a3\u8005 VCF \u6216\u533b\u9662\u75c5\u4f8b\u3002\n";
    }

    private String instructions() {
        return "# Truth-masked HPO 复核说明\n\n"
                + "1. 只打开本目录中以 `SMOKE-HPO-` 开头的 `.review.md`、`.packet.json` 和 `.decision-template.json`。\n"
                + "2. 不要打开 `../internal/DO_NOT_PROVIDE_TO_REVIEWER.mapping.json`，也不要回看 Stage 2B-4b 的 truth 表。\n"
                + "3. Observed term 只能填写 `KEEP_OBSERVED`、`REMOVE_MAPPING_ERROR` 或 `UNCERTAIN`；Excluded term 只能填写 `KEEP_EXCLUDED`、`REMOVE_EXCLUSION_MAPPING_ERROR` 或 `UNCERTAIN`。\n"
                + "4. 删除必须填写 mapping-error 理由。`UNCERTAIN` 表示尚未完成，不能生成 G2。\n"
                + "5. 本轮没有患者特异原始临床文本，因此不得新增 HPO。不要根据疾病、基因、变异、数据库或典型症状推断表型。\n"
                + "6. 这是真值遮蔽的 development review，但此前报告曾展示 truth 字段，因此不是严格盲法评估。\n"
                + "7. 在对应 JSON 模板中填写决定，保留 `reviewId` 和 `packetFingerprint`，不要修改这两个字段。\n"
                + "8. 五份表全部完成后，只需告诉 Codex：`5份 truth-masked HPO 复核表已填写完成`。完成前不要运行真实 G2，也不需要上传论文、患者 VCF 或医院病例。\n";
    }
    private void writeJson(Path path, Object value) throws IOException { writeText(path, mapper.writeValueAsString(value) + "\n"); }
    private void writeDecisionTemplate(Path path, HpoReviewDecisionDocument template,
                                       HpoReviewPacketGenerationOptions options) throws IOException {
        JsonNode serialized = mapper.valueToTree(template);
        if ("SMOKE-HPO-".equals(options.getReviewIdPrefix())) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) serialized).remove(Arrays.asList(
                    "aiAssistance", "semanticAuditNotes"));
        }
        writeJson(path, serialized);
    }
    private void writeText(Path path, String content) throws IOException { byte[] bytes = content.getBytes(StandardCharsets.UTF_8); if (Files.exists(path)) { if (Arrays.equals(bytes, Files.readAllBytes(path))) return; throw new IOException("REVIEW_ARTIFACT_CONFLICT: refusing overwrite " + path.getFileName()); } Path temporary = path.resolveSibling(path.getFileName() + ".temporary"); Files.write(temporary, bytes); try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, path); } }

    private static class CaseInput {
        private final String caseId; private final String sourceChecksum; private final List<String> observed; private final List<String> excluded; private final Map<String, String> labels;
        private CaseInput(String caseId, String sourceChecksum, List<String> observed, List<String> excluded, Map<String, String> labels) { this.caseId = caseId; this.sourceChecksum = sourceChecksum; this.observed = observed; this.excluded = excluded; this.labels = labels; }
        private String getCaseId() { return caseId; } private String getSourceChecksum() { return sourceChecksum; } private List<String> getObserved() { return observed; } private List<String> getExcluded() { return excluded; } private String label(String id) { String value = labels.get(id); return value == null ? "Label unavailable" : value; }
    }
}
