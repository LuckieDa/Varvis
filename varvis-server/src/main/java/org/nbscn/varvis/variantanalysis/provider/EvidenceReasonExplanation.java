package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explanation only. Does not change evaluation, acquisition, adoption or strength. */
public final class EvidenceReasonExplanation {
    private EvidenceReasonExplanation() { }
    public record Definition(String category, String summary, String missing, String impact) { }
    private static final Map<String, Definition> DEFINITIONS = new LinkedHashMap<>();
    static {
        DEFINITIONS.put("PVS1_CODING_BRANCH_NOT_APPLICABLE", new Definition("NOT_APPLICABLE", "已核对所选转录本注释：当前是错义或同义变化，不属于 PVS1 无义或移码编码分支。", "本次编码分支无需补取功能缺失机制和 NMD 材料；若另有 RNA 或剪接影响，需按剪接分支单独核查。", "仅说明本次编码分支不适用，不证明没有剪接影响，也不代表变异良性。"));
        DEFINITIONS.put("PM1_PUBLISHED_SOURCE_SCOPE_NOT_COVERED", new Definition("MISSING", "当前变化未命中已加载规范可执行的关键区域或位点。", "覆盖当前变化的合格区域或位点依据。", "不能由已加载区域未覆盖推断其他功能区域不存在，也不自动给予 PM1。"));
        DEFINITIONS.put("PM1_REVIEWED_REGION_OBSERVATION_UNRESOLVED", new Definition("CONFLICT", "区域内有错义变异的分类资料存在冲突。", "核实所列错义变异的分类依据及其适用的疾病背景。", "暂未采用 PM1。"));
        DEFINITIONS.put("PM1_REVIEWED_REGION_OBSERVATION_INCOMPLETE", new Definition("MISSING", "区域内有错义变异缺少可核实的分类或疾病背景。", "所列错义变异适用的分类依据和疾病背景。", "暂未采用 PM1。"));
        DEFINITIONS.put("PM1_REVIEWED_REGION_REVIEW_PENDING", new Definition("MISSING", "区域边界、疾病相关性或采用强度尚待复核。", "适用于当前背景的区域与强度复核结论。", "暂未采用 PM1。"));
        DEFINITIONS.put("PM1_REVIEWED_REGION_BENIGN_SURVEY_INCOMPLETE", new Definition("MISSING", "该区域的良性错义变异分布尚未核查完整。", "该蛋白区间的良性错义记录及其证据核查。", "暂未采用 PM1。"));
        DEFINITIONS.put("PM1_REVIEWED_REGION_INDEPENDENT_OBSERVATION_MISSING", new Definition("MISSING", "缺少独立于当前变异的区域致病错义观察。", "同一疾病背景下可独立核实的区域致病变异。", "暂未采用 PM1。"));
        DEFINITIONS.put("BP1_INSIDE_PUBLISHED_EXCLUDED_REGION", new Definition("NOT_APPLICABLE", "当前变异位于所选规范明确排除的蛋白区域，本次不能采用该 BP1 区域外条款。", "该条款的排除条件已明确；不能通过补资料绕过排除区域。", "这是当前条款不适用，不等于已经判定变异致病。"));
        DEFINITIONS.put("DENOVO_AFFECTED_STATUS_REQUIRED", new Definition("MISSING", "当前分析样本的患病状态未满足现有新生变异分支的前置要求；这不表示文献病例或父母检测资料缺失。", "当前病例若要作为新生事件计分，仍需其自身患病与表型依据；论文病例按独立来源核查。", "本条保存结果保持原状，不能用论文病例替用户填写患病状态；旧运行不会自动重新评价。"));
        DEFINITIONS.put("PVS1_LOF_SOURCE_NOT_ESTABLISHED", new Definition("MISSING", "已找到机制来源，但该来源未确立当前基因—疾病背景的功能缺失致病机制。", "当前背景可采用的功能缺失机制依据。", "不能仅凭截短或预测后果授予 PVS1；不代表该变异已证实良性。"));
        DEFINITIONS.put("PS1_SPLICE_NATIVE_SNV_SCOPE_REQUIRED", new Definition("UNSUPPORTED", "当前 PS1 原生剪接比较路径仅支持其规定的 SNV 范围，本次变异未满足该路径范围要求。", "适用于该变异类型的已实现剪接比较路径。", "属于当前软件范围限制，不表示文献没有相关剪接依据。"));
        DEFINITIONS.put("DENOVO_ASSEMBLY_OR_CHROMOSOME_NOT_SUPPORTED", new Definition("UNSUPPORTED", "当前新生变异实现仅支持 GRCh38 常染色体，本次参考版本或染色体不在实现范围。", "支持当前参考版本及染色体的执行路径。", "PS2/PM6 保持未评价，不能据此认定医学上不适用。"));
        DEFINITIONS.put("DENOVO_INHERITANCE_NOT_SUPPORTED", new Definition("UNSUPPORTED", "当前新生变异实现仅接通常染色体显性背景，本次遗传方式不在实现范围。", "与当前遗传方式相容的执行路径。", "属于实现范围限制，不等于该遗传方式不能使用新生变异证据。"));
        DEFINITIONS.put("DENOVO_VARIANT_TYPE_NOT_SUPPORTED", new Definition("UNSUPPORTED", "当前新生变异路径未支持本次等位基因表示或长度范围。", "支持本次变异类型并经身份核验的执行路径。", "保留输入身份和未评价结果，不以改写变异表示绕过门禁。"));
        DEFINITIONS.put("BP7_NATIVE_PREPARATION_UNAVAILABLE", new Definition("UNAVAILABLE", "未取得状态为 PREPARED 的原生剪接准备计划，尚不能完成 BP7 所需的剪接核验。", "与当前变异、方法及版本绑定的剪接准备结果。", "不能把尚未完成的剪接准备当作正常剪接，也不表示程序必然崩溃。"));
        DEFINITIONS.put("PM2_LOCAL_INTERVAL_QC_FAILED", new Definition("QUALITY_REJECTED", "已取得本地人口记录，但记录带有区间覆盖质控失败标记，不能用于本次 PM2 判定。", "满足所用规则质量要求的人口观测。", "这是记录质量不合格，不是程序执行失败；不能认作频率为零或充分检测后未检出。"));
        DEFINITIONS.put("VARIANT_FILTER_NOT_PASS", new Definition("QUALITY_REJECTED", "用于本项核查的变异记录未满足 FILTER=PASS 质量要求。", "满足本项质量要求的记录；具体记录来源见保存事实。", "保留未评价，不能从质量未通过推出良性或致病，也不应反复重跑工具掩盖质控。"));
        DEFINITIONS.put("BS1_LOCAL_FILTER_NOT_PASS", new Definition("QUALITY_REJECTED", "本地外显子组人口记录的原生质量标记不是 PASS，不能用于本次 BS1 判断。", "质量合格且身份相容的人口记录。", "资料已查得但未通过质量要求，不是查询程序崩溃。"));
        DEFINITIONS.put("BS1_EXPERT_SPECIFICATION_REQUIRES_STRUCTURING", new Definition("POLICY_NOT_READY", "已找到相关专家规范，但完整条件尚未转成可执行规则，不能用通用阈值绕过。", "对应专家条款的结构化条件及执行接线。", "属于规范接入缺口，不是数据库没有变异。"));
        DEFINITIONS.put("PM2_SPECIALIST_POLICY_REVIEW_REQUIRED", new Definition("POLICY_NOT_READY", "已找到相关疾病专用规范，但适用关系或评价条件尚未完整接通。", "明确适用于当前背景的专规条件与接线。", "保留未评价，不能绕过专规套用通用阈值。"));
        DEFINITIONS.put("CLAUSE_SEARCH_SCOPE_NOT_COVERED", new Definition("POLICY_NOT_READY", "当前加载的政策目录未覆盖本项证据的解释背景，尚无相容的可执行政策。", "与本条证据及当前背景相容的适用政策。", "不能据此推断参考数据库没有变异或原文没有资料。"));
        DEFINITIONS.put("PUBLISHED_KNOWLEDGE_BACKGROUND_NOT_COVERED", new Definition("MISSING", "当前已加载知识未覆盖所选基因、疾病和遗传方式的精确背景。", "适用于当前背景的合格知识。", "不能借用同基因其他疾病背景的结论。"));
        DEFINITIONS.put("PUBLISHED_KNOWLEDGE_TRANSCRIPT_NOT_COVERED", new Definition("MISSING", "当前知识未覆盖所选带版本转录本，尚无可采用的对应关系。", "所选转录本的适用依据或经过核实的版本对应关系。", "不能直接更换转录本名称来取得得分。"));
        DEFINITIONS.put("PP2_GENERIC_AUTHORITY_DEFINITION_ONLY", new Definition("MISSING", "已找到 PP2 通用定义，但缺少当前背景的错义致病机制和低良性错义两项采用依据。", "当前背景同时满足两项要求的知识资料。", "通用定义本身不能作为 PP2 授分依据。"));
        DEFINITIONS.put("PM4_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION", new Definition("POLICY_NOT_READY", "已知存在相关专门规范，尚需核定适用于当前背景的 PM4 条款。", "适用的完整 PM4 专家条款。", "不能跳过专规直接使用通用蛋白长度条件。"));
        DEFINITIONS.put("PREDICTION_CONSEQUENCE_OUTSIDE_LOCAL_SCOPE", new Definition("UNSUPPORTED", "本次注释后果超出当前预测实现的支持范围，尚无对应评价路径。", "支持该后果的预测方法、校准与执行路径。", "不表示变异无影响，也不能由未评价反推另一条预测证据。"));
        DEFINITIONS.put("CODING_MISSENSE_PROTEIN_EFFECT_NOT_SUPPORTED", new Definition("UNSUPPORTED", "本次蛋白后果不满足当前纯编码错义实现的支持条件，可能涉及混合或其他后果。", "适用于实际后果组合的实现及资料。", "保留具体后果，不强行按纯错义路径评分。"));
        DEFINITIONS.put("PVS1_MECHANISM_SOURCE_NOT_FOUND", new Definition("MISSING", "当前加载的知识中未找到相容的基因—疾病机制来源。", "该精确背景的机制来源及适用依据。", "未找到来源不表示已经排除功能缺失机制。"));
        DEFINITIONS.put("PVS1_CONSEQUENCE_NOT_APPLICABLE", new Definition("NOT_APPLICABLE", "原评价器已确认所选转录本后果不属于本次 PVS1 执行分支。", "本次分支无需继续补取资料；其他分支适用性仍按各自依据判断。", "保留原未评价状态，不将当前分支不适用扩大为不存在任何潜在功能影响。"));
        DEFINITIONS.put("PM3_NO_COMPATIBLE_LITERATURE_OBSERVATIONS", new Definition("UNAVAILABLE", "本次尚无与所选变异相容、可采用的 PM3 文献观察。", "身份、版本和适用条件均通过核查的病例观察。", "可能涉及旧缓存未采用；不表示世界范围内没有相关论文。"));
    }
    public static Map<String, Definition> definitions() { return Map.copyOf(DEFINITIONS); }
    public static ObjectNode build(ObjectMapper json, JsonNode evaluation) {
        JsonNode result = evaluation.has("evaluationResult") ? evaluation.path("evaluationResult") : evaluation;
        String status = result.path("status").asText(), reason = result.path("reasonCode").asText();
        // Positive/negative evaluation remains authoritative; no reason may override it.
        if (!"NOT_EVALUATED".equals(status) || !DEFINITIONS.containsKey(reason)) return null;
        Definition d = DEFINITIONS.get(reason);
        return json.createObjectNode().put("schemaVersion", "evidence-reason-explanation-v1")
                .put("status", status).put("reasonCode", reason).put("category", d.category())
                .put("summary", d.summary()).put("missing", d.missing()).put("impact", d.impact());
    }
}
