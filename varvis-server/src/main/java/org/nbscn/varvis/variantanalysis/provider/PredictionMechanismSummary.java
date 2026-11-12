package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/** Whitelisted, read-only explanation of existing decisions, never qualification or rule inputs. */
public final class PredictionMechanismSummary {
    public static final String VERSION = "1.4.1";
    private static final String SCHEMA = "prediction-mechanism-summary-v1";
    private static final List<String> MECHANISMS = List.of("PROTEIN_MISSENSE", "SPLICING");
    private static final Set<String> ROUTES = Set.of("BLOCKED", "GENERAL_LOCAL_ALPHAMISSENSE", "GENERAL_LOCAL_REVEL",
            "PUBLISHED_LOCAL_REVEL", "REVEL_EXISTING_SUBSET", "REVIEWED_POLICY", "EXISTING_DEVELOPMENT_CORE");
    private PredictionMechanismSummary() { }

    public static ObjectNode fromResult(ObjectMapper mapper, JsonNode result) {
        JsonNode plan = result.path("plan");
        var out = mapper.createObjectNode().put("schemaVersion", SCHEMA).put("purpose", "EXPLANATION_ONLY")
                .put("route", token(plan.path("route"), ROUTES, "BLOCKED"))
                .put("primaryMechanism", token(plan.path("primaryMechanism"), Set.copyOf(MECHANISMS), "UNRESOLVED"))
                .put("fallbackAfterScore", false);
        out.set("functionalReferences", EffectReferenceReadiness.view(plan.path("effectRestrictions")));
        publishedView(out, plan.at("/publishedSelection/selected"));
        for (String field : List.of("allowedPp3Mechanisms", "requiredBp4Mechanisms")) {
            var list = out.putArray(field);
            for (String mechanism : MECHANISMS) for (var value : plan.path(field)) if (mechanism.equals(value.asText())) { list.add(mechanism); break; }
        }
        if (plan.path("transcriptMaterial").isObject()) {
            var material = plan.path("transcriptMaterial");
            var view = materialView(mapper, material);
            out.set("transcriptMaterial", view);
        }
        var rows = out.putArray("mechanisms");
        for (String name : MECHANISMS) {
            JsonNode evaluated = result.path("mechanisms").path(name);
            String status = token(evaluated.path("status"), Set.of("CALIBRATED", "NOT_EVALUATED", "NOT_APPLICABLE"), "NOT_EVALUATED");
            String reason = reason(evaluated.path("reasonCode").asText(plan.path("reasonCode").asText("PREDICTION_REQUIRED_MECHANISM_UNRESOLVED")));
            var row = rows.addObject().put("mechanism", name).put("model", model(result, name)).put("status", status)
                    .put("direction", token(evaluated.path("direction"), Set.of("HARMFUL", "BENIGN", "UNINFORMATIVE"), "UNRESOLVED"))
                    .put("appliedStrength", token(evaluated.path("appliedStrength"), Set.of("SUPPORTING", "MODERATE", "STRONG", "VERY_STRONG"), "NONE"))
                    .put("reasonCode", reason).put("reason", explain(reason));
            // Only the actually selected mechanism's recorded result, not an auxiliary
            // observation or a recomputed maximum, supplies display numbers.
            displayNumbers(mapper, evaluated, row);
            // Keep precomputed observation limits separate from the actual chosen evaluator's qualification.
            // A masked auxiliary record does not invalidate a separately qualified RAW reviewed route.
            JsonNode observed = "SPLICING".equals(name) ? result.at("/sharedSpliceObservation/sourceRecord")
                    : "GENERAL_LOCAL_ALPHAMISSENSE".equals(plan.path("route").asText()) ? result.at("/generalAlphaResult/observation")
                    : "GENERAL_LOCAL_REVEL".equals(plan.path("route").asText()) ? result.at("/generalRevelResult/observation")
                    : "PUBLISHED_LOCAL_REVEL".equals(plan.path("route").asText()) ? result.at("/publishedRevelResult/observation")
                    : result.at("/developmentResult/observation");
            String observationReason = "SPLICING".equals(name) ? observed.at("/resourceExecution/reasonCode").asText("OBSERVATION_NOT_RECORDED")
                    : observed.path("reasonCode").asText("OBSERVATION_NOT_RECORDED");
            row.put("observationStatus", token("SPLICING".equals(name) ? observed.at("/queryOutcome/status") : observed.path("status"),
                    Set.of("HIT", "NO_RECORD", "DISABLED", "UNAVAILABLE", "NOT_QUERIED"), "UNKNOWN"));
            row.put("observationReasonCode", reason(observationReason));
            row.put("scoreMode", token(observed.at("/resource/scoreMode"), Set.of("RAW", "MASKED"), "UNKNOWN"));
            String qualification = "SPLICING".equals(name)
                    ? observed.at("/compatibility/reasonCode").asText("SPLICE_ANNOTATION_COMPATIBILITY_UNVERIFIED") : reason;
            row.put("observationQualificationReasonCode", reason(qualification));
            row.put("observationQualificationReason", explain(reason(qualification)));
            if ("SPLICING".equals(name) && result.path("nativeSpliceResult").isObject()
                    && Set.of("GENERAL_LOCAL_REVEL", "GENERAL_LOCAL_ALPHAMISSENSE", "EXISTING_DEVELOPMENT_CORE").contains(plan.path("route").asText())) {
                var nativeResult = result.path("nativeSpliceResult");
                row.put("observationStatus", token(nativeResult.at("/observation/status"), Set.of("HIT", "NO_RECORD", "UNAVAILABLE"), "NOT_QUERIED"));
                row.put("scoreMode", "RAW");
                String nativeReason = reason(nativeResult.at("/mechanism/reasonCode").asText("NATIVE_SPLICE_EXECUTION_UNAVAILABLE"));
                row.put("observationReasonCode", nativeReason).put("observationQualificationReasonCode", nativeReason)
                        .put("observationQualificationReason", explain(nativeReason));
            }
        }
        return out;
    }

    /** Scrub even a saved summary; no recursive copying of private source paths or original rows. */
    public static ObjectNode publicView(ObjectMapper mapper, JsonNode summary) {
        var out = mapper.createObjectNode();
        if (!SCHEMA.equals(summary.path("schemaVersion").asText()) || !"EXPLANATION_ONLY".equals(summary.path("purpose").asText())) return out;
        out.put("schemaVersion", SCHEMA).put("purpose", "EXPLANATION_ONLY").put("fallbackAfterScore", false);
        if (summary.path("functionalReferences").isObject())
            out.set("functionalReferences", EffectReferenceReadiness.view(summary.path("functionalReferences")));
        out.put("route", token(summary.path("route"), ROUTES, "BLOCKED"));
        publishedView(out, summary.path("publishedRule"));
        out.put("primaryMechanism", token(summary.path("primaryMechanism"), Set.copyOf(MECHANISMS), "UNRESOLVED"));
        for (String field : List.of("allowedPp3Mechanisms", "requiredBp4Mechanisms")) {
            var values = out.putArray(field);
            for (String name : MECHANISMS) for (var value : summary.path(field)) if (name.equals(value.asText())) { values.add(name); break; }
        }
        if (summary.path("transcriptMaterial").isObject())
            out.set("transcriptMaterial", materialView(mapper, summary.path("transcriptMaterial")));
        var rows = out.putArray("mechanisms");
        for (String name : MECHANISMS) {
            JsonNode value = null;
            for (var row : summary.path("mechanisms")) if (name.equals(row.path("mechanism").asText())) { if (value != null) return mapper.createObjectNode(); value = row; }
            if (value == null) return mapper.createObjectNode();
            var row = rows.addObject().put("mechanism", name);
            row.put("model", token(value.path("model"), Set.of("REVEL", "ALPHAMISSENSE", "SPLICEAI", "NOT_REQUIRED"), "UNRESOLVED"));
            row.put("status", token(value.path("status"), Set.of("CALIBRATED", "NOT_EVALUATED", "NOT_APPLICABLE"), "NOT_EVALUATED"));
            row.put("direction", token(value.path("direction"), Set.of("HARMFUL", "BENIGN", "UNINFORMATIVE"), "UNRESOLVED"));
            row.put("appliedStrength", token(value.path("appliedStrength"), Set.of("SUPPORTING", "MODERATE", "STRONG", "VERY_STRONG"), "NONE"));
            row.put("observationStatus", token(value.path("observationStatus"), Set.of("HIT", "NO_RECORD", "DISABLED", "UNAVAILABLE", "NOT_QUERIED"), "UNKNOWN"));
            row.put("scoreMode", token(value.path("scoreMode"), Set.of("RAW", "MASKED"), "UNKNOWN"));
            for (String field : List.of("reasonCode", "observationReasonCode", "observationQualificationReasonCode")) row.put(field, reason(value.path(field).asText()));
            row.put("reason", explain(row.path("reasonCode").asText()));
            row.put("observationQualificationReason", explain(row.path("observationQualificationReasonCode").asText()));
            displayNumbers(mapper, value, row);
        }
        return out;
    }

    private static void publishedView(ObjectNode out, JsonNode selected) {
        String key = selected.path("specificationKey").asText();
        if (!key.matches("GN[0-9]{3,5}@[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")) return;
        var rule = out.putObject("publishedRule").put("specificationKey", key)
                .put("relation", reason(selected.path("relation").asText()));
        String transcript = selected.path("publishedTranscript").asText();
        if (transcript.matches("(?:NM_|ENST)[0-9]+\\.[1-9][0-9]*")) rule.put("publishedTranscript", transcript);
        var thresholds = selected.path("revelThresholds");
        if ("0.7".equals(thresholds.path("pp3Minimum").asText()) && "0.3".equals(thresholds.path("bp4Maximum").asText())) {
            var view = rule.putObject("revelThresholds").put("pp3Minimum", "0.7").put("bp4Maximum", "0.3");
            for (String field : List.of("pp3Pointer", "bp4Pointer")) {
                String pointer = thresholds.path(field).asText();
                if (pointer.matches("/data/ld/CriteriaCode/[0-9]+/entContent/strengthDescriptor/[0-9]+")) view.put(field, pointer);
            }
        }
    }

    private static void displayNumbers(ObjectMapper mapper, JsonNode input, ObjectNode out) {
        JsonNode raw = input.has("values") ? input.at("/values/rawScore") : input.path("rawScore");
        var number = PredictionCalibration.decimal(raw.asText());
        if (number != null && number.signum() >= 0 && number.compareTo(java.math.BigDecimal.ONE) <= 0)
            out.putObject("values").put("rawScore", raw.asText());
        JsonNode band = input.has("thresholds") ? input.path("thresholds") : input.path("band");
        if (band.isMissingNode() && "SPLICING".equals(out.path("mechanism").asText()))
            band = SpliceAiBusinessRuntime.recordedDevelopmentInterval(mapper, input);
        var lower = PredictionCalibration.decimal(band.path("lower").asText());
        var upper = PredictionCalibration.decimal(band.path("upper").asText());
        if (lower != null && upper != null && lower.signum() >= 0 && upper.compareTo(java.math.BigDecimal.ONE) <= 0
                && lower.compareTo(upper) <= 0 && band.path("lowerInclusive").isBoolean() && band.path("upperInclusive").isBoolean()) {
            var interval = mapper.createObjectNode().put("lower", band.path("lower").asText()).put("upper", band.path("upper").asText())
                    .put("lowerInclusive", band.path("lowerInclusive").asBoolean()).put("upperInclusive", band.path("upperInclusive").asBoolean());
            out.set("thresholds", interval);
        }
    }

    private static ObjectNode materialView(ObjectMapper mapper, JsonNode material) {
        String tx = material.path("transcript").asText();
        String origin = material.path("modelOrigin").asText();
        if (!Set.of("GENCODE_GTF_PLUS_REFERENCE", "PREBUILT_LOCAL").contains(origin)) origin = "PREBUILT_LOCAL";
        return mapper.createObjectNode().put("purpose", "TECHNICAL_MATERIAL_ONLY")
                .put("status", token(material.path("status"), Set.of("READY", "UNAVAILABLE"), "UNAVAILABLE"))
                .put("transcript", tx.matches("(?:ENST[0-9]+|NM_[0-9]+)\\.[1-9][0-9]*") ? tx : "UNRESOLVED")
                .put("modelOrigin", origin).put("reasonCode", reason(material.path("reasonCode").asText()))
                .put("reason", explain(reason(material.path("reasonCode").asText()))).put("scientificApproval", false);
    }

    private static String model(JsonNode result, String mechanism) {
        if ("BLOCKED".equals(result.at("/plan/route").asText())) return "UNRESOLVED";
        if ("SPLICING".equals(mechanism)) return "PUBLISHED_LOCAL_REVEL".equals(result.at("/plan/route").asText()) ? "NOT_REQUIRED" : "SPLICEAI";
        if ("SPLICING".equals(result.at("/plan/primaryMechanism").asText())) return "NOT_REQUIRED";
        if (Set.of("REVEL_EXISTING_SUBSET", "GENERAL_LOCAL_REVEL", "PUBLISHED_LOCAL_REVEL").contains(result.at("/plan/route").asText())) return "REVEL";
        if ("GENERAL_LOCAL_ALPHAMISSENSE".equals(result.at("/plan/route").asText())) return "ALPHAMISSENSE";
        if ("EXISTING_DEVELOPMENT_CORE".equals(result.at("/plan/route").asText()))
            return token(result.at("/plan/developmentPreparation/model"), Set.of("REVEL", "ALPHAMISSENSE"), "UNRESOLVED");
        String found = null;
        for (String code : List.of("PP3", "BP4")) {
            JsonNode clause = result.at("/reviewedResult/clauses/" + code + "/result");
            if (!mechanism.equals(clause.path("evidenceMechanism").asText())) continue;
            String selected = token(clause.path("preselectedModel"), Set.of("REVEL", "ALPHAMISSENSE"), "UNRESOLVED");
            if (found != null && !found.equals(selected)) return "UNRESOLVED";
            found = selected;
        }
        return found == null ? "UNRESOLVED" : found;
    }
    private static String token(JsonNode value, Set<String> allowed, String fallback) { return allowed.contains(value.asText()) ? value.asText() : fallback; }
    private static String reason(String value) { return value.matches("[A-Z][A-Z0-9_]{0,160}") ? value : "PREDICTION_DETAIL_REDACTED"; }
    private static String explain(String reason) {
        return switch (reason) {
            case "NATIVE_SPLICE_TRANSCRIPT_STRUCTURE_MISMATCH" -> "RAW 分数已经取得，但官方基因注释的完整外显子结构与所选转录本不一致，不能套用该分数。";
            case "NATIVE_SPLICE_REFERENCE_WINDOW_MISMATCH", "NATIVE_SPLICE_REFERENCE_CHANGED" -> "本次推理窗口与已核定参考不一致或运行中发生变化，不能采用分数。";
            case "NATIVE_SPLICE_GENE_NOT_ANNOTATED", "NO_MATCHING_GENE_ANNOTATION" -> "官方模型注释未覆盖当前所选基因位置，没有返回分数；不代表正常剪接。";
            case "NATIVE_BATCH_LIMIT_EXCEEDED" -> "本次原生 RAW 推理超过 10 个变异与转录本组合的批次上限；请缩小 Top-N。";
            case "NATIVE_SELECTED_ANNOTATION_RECEIPT_MISMATCH", "NATIVE_SELECTED_ANNOTATION_RECEIPT_MISSING" -> "模型返回的注释身份与所选精确转录本不符或缺少回执，不能采用分数。";
            case "NATIVE_SELECTED_EXONS_INVALID", "NATIVE_SELECTED_ANNOTATION_IDENTITY_INVALID" -> "所选转录本的完整注释结构无效，未启动剪接计算。";
            case "NATIVE_SELECTED_TRANSCRIPT_RANGE_INVALID" -> "当前变异不在所选转录本的注释范围内，未启动剪接计算。";
            case "NATIVE_SPLICE_TIMEOUT" -> "本次本地 RAW 推理达到时间上限，未采用部分输出。";
            case "NATIVE_SPLICE_TOTAL_BUDGET_EXHAUSTED" -> "本次所选变异的剪接推理总时间预算已耗尽；保留此前完成批次的合格结果，本组合未取得可采用结果。";
            case "NATIVE_TOTAL_UNIT_LIMIT_EXCEEDED" -> "本次超过50个不同的变异与转录本组合，未启动剪接推理；请减少本次选择数量。";
            case "NATIVE_BATCH_RECORDS_INVALID" -> "本组合所在剪接批次的返回数量或身份不一致，该批次结果不采用；其他已核对批次单独保留。";
            case "NATIVE_SPLICE_INTERRUPTED" -> "本次剪接推理已取消，后续批次未执行；此前完成批次的合格结果保留。";
            case "NATIVE_SPLICE_SNV_APPLICATION_SCOPE" -> "本轮 RAW 业务采用范围为原生 GRCh38 单碱基替换，其他变异类型尚未核定。";
            case "SPLICE_EVENT_OUTSIDE_CONFIGURED_WINDOW" -> "剪接事件位置超出本次 SpliceAI 的预测窗口，不能用该记录判定证据。";
            case "BP4_REQUIRED_MECHANISM_PLAN_INVALID" -> "BP4 所需机制清单不完整或不一致；错义变异须同时核对蛋白与剪接影响。";
            case "ALPHAMISSENSE_SOURCE_TRANSCRIPT_MATERIAL_REQUIRED", "ALPHAMISSENSE_SOURCE_TRANSCRIPT_MATERIAL_UNAVAILABLE" -> "AlphaMissense 与所选转录本版本不同，缺少源版本的合格注释或参考材料；未换模型或删除版本。";
            case "ALPHAMISSENSE_CDS_OR_POSITION_NOT_EQUIVALENT" -> "源版本与所选版本的完整编码序列或变异编码位置不一致，不能跨版本采用。";
            case "ALPHAMISSENSE_DIFFERENT_TRANSCRIPT_UNSUPPORTED" -> "AlphaMissense 记录对应另一转录本；当前只允许同一转录本的精确版本或完整编码序列等价桥接。";
            case "ALPHAMISSENSE_SINGLE_SOURCE_ROW_REQUIRED" -> "同一等位基因存在多条预测记录，本通路不取最高分或投票。";
            case "ALPHAMISSENSE_TRANSCRIPT_VERSION_MISMATCH" -> "本地 AlphaMissense 有该等位基因记录，但转录本版本与当前选择不一致；尚无完整序列等价证明。";
            case "ALPHAMISSENSE_PROTEIN_IDENTITY_MISMATCH" -> "AlphaMissense 转录本一致，但蛋白位置或氨基酸变化不一致，不能套用该分数。";
            case "ALPHAMISSENSE_NO_MATCHING_RECORD" -> "本次 AlphaMissense 查询未取得匹配记录；不是零分或良性结论。";
            case "PREDICTION_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION" -> "本地目录存在该基因的专家专规，须先核定预测条款，不能直接套用通用阈值。";
            case "PREDICTION_PUBLISHED_TRANSCRIPT_MISMATCH" -> "本次选择的转录本与发布规范指定的转录本不一致。";
            case "PREDICTION_PUBLISHED_CLAUSE_UNSUPPORTED" -> "当前疾病背景适用专家规范，该规范的预测条件尚未接入。";
            case "PREDICTION_PUBLISHED_SCOPE_UNRESOLVED", "PREDICTION_PUBLISHED_SCOPE_AMBIGUOUS" -> "现有资料尚无法确定专家预测规范对当前疾病和遗传方式的适用范围。";
            case "PREDICTION_PUBLISHED_SOURCE_UNAVAILABLE" -> "本地发布原件无法读取或校验未通过。";
            case "PREDICTION_PUBLISHED_REVEL_RESOURCE_UNAVAILABLE" -> "专家规范要求 REVEL，本地缺少可核验的 REVEL 资料。";
            case "PREDICTION_PUBLISHED_MISSENSE_BRANCH_OUTSIDE_CONSEQUENCE" -> "当前变异后果超出已接入的专家错义预测分支。";
            case "PREDICTION_PUBLISHED_REVEL_INTERVAL" -> "按发布规范的 REVEL 阈值核对。";
            case "REVEL_NO_RECORD" -> "本地 REVEL 没有匹配当前等位基因与转录本的记录；不能当作良性或零分。";
            case "REVEL_ISOFORM_SCORE_CONFLICT" -> "同一等位基因在不同转录本的 REVEL 分数不一致；当前通用路线不选最高分或投票。";
            case "GENERAL_REVEL_VERIFIED_TRANSCRIPT_MATERIAL_REQUIRED" -> "尚未取得精确转录本、当前参考与原 VEP 一致的材料。";
            case "CALIBRATED_INTERVAL" -> "原始分数落入已登记校准区间；此处是单一机制结果，最终证据仍核对完整条件。";
            case "SCORE_UNINFORMATIVE" -> "原始分数落入灰区，对此机制既不支持有害也不支持良性。";
            case "SPLICEAI_DISABLED" -> "当前未启用剪接资源读取。";
            case "SPLICE_LOCAL_RESOURCE_UNAVAILABLE" -> "缺少本地剪接数据或对应索引。";
            case "SPLICE_EXACT_ALLELE_NO_RECORD" -> "本地剪接数据没有精确等位基因记录；不能按零分或正常剪接处理。";
            case "SPLICE_PARAMETERS_INCOMPATIBLE" -> "当前预计算剪接数据条件不符合 RAW 校准要求，masked 分数不能直接代入。";
            case "SPLICE_ANNOTATION_COMPATIBILITY_UNVERIFIED", "SELECTED_TRANSCRIPT_ASSOCIATION_NOT_ESTABLISHED" -> "剪接注释、所选转录本或运行参数尚未证明相容。";
            case "CALIBRATION_RESOURCE_COMPATIBILITY_UNVERIFIED", "AM_STUDY_MAPPING_AND_AGGREGATION_UNVERIFIED" -> "分数资源与校准研究的产品、映射或聚合方式尚未核定。";
            case "PREDICTION_PUBLIC_CONTROL_NOT_REGISTERED", "USE_RESTRICTION_CONFLICT" -> "当前资源绑定的使用范围不覆盖此输入，不能借用公共对照资格。";
            case "PROTEIN_MISSENSE_NOT_REQUIRED" -> "此计划不需要错义蛋白机制。";
            case "EXISTING_PAIRED_CLAUSE_ASSESSMENT" -> "蛋白机制沿用原有成对证据评价；不替代剪接机制。";
            case "PREDICTION_CONSEQUENCE_OUTSIDE_LOCAL_SCOPE" -> "所选转录本后果不在当前错义或剪接预测范围内。";
            case "EXACT_MODEL_REFERENCE_AND_VEP_AGREE" -> "精确转录本、当前参考与原注释一致；材料就绪不代表预测规则已经可用。";
            case "GENCODE_EXACT_TRANSCRIPT_VERSION_UNAVAILABLE", "TRANSCRIPT_MATERIAL_EXACT_VERSION_NOT_AVAILABLE" -> "本地材料没有这个精确转录本版本；未改用其他版本。";
            case "GENCODE_CONTEXT_IDENTITY_MISMATCH" -> "本地转录本与所选基因、组装或染色体身份不一致。";
            case "GENCODE_RESOURCE_CHANGED", "GENCODE_ANNOTATION_HASH_MISMATCH" -> "转录本资源发生变化或记录摘要不符，需要重新核定资源。";
            default -> "保留原机制或资源检查结果（" + reason + "）。";
        };
    }
}
