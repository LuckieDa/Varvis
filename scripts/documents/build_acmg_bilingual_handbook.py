from __future__ import annotations

import argparse
from pathlib import Path
from typing import Iterable

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph, Table, TableStyle


AS_OF = "2026-08-31"
PAGE_W, PAGE_H = A4

BURGUNDY = colors.HexColor("#7A2131")
BURGUNDY_LIGHT = colors.HexColor("#F6EDEF")
NAVY = colors.HexColor("#17365D")
NAVY_LIGHT = colors.HexColor("#EBF1F7")
INK = colors.HexColor("#20242A")
MUTED = colors.HexColor("#65707C")
LINE = colors.HexColor("#CBD2D9")
PALE = colors.HexColor("#F5F7F9")
AMBER = colors.HexColor("#9A5A00")
AMBER_LIGHT = colors.HexColor("#FFF4DB")
GREEN = colors.HexColor("#2B6848")
GRAY = colors.HexColor("#73777B")


def register_fonts() -> None:
    regular = Path(r"C:\Windows\Fonts\msyh.ttc")
    bold = Path(r"C:\Windows\Fonts\msyhbd.ttc")
    fallback = Path(r"C:\Windows\Fonts\simhei.ttf")
    if regular.exists() and bold.exists():
        pdfmetrics.registerFont(TTFont("Handbook", str(regular)))
        pdfmetrics.registerFont(TTFont("Handbook-Bold", str(bold)))
    else:
        pdfmetrics.registerFont(TTFont("Handbook", str(fallback)))
        pdfmetrics.registerFont(TTFont("Handbook-Bold", str(fallback)))
    pdfmetrics.registerFontFamily(
        "Handbook", normal="Handbook", bold="Handbook-Bold",
        italic="Handbook", boldItalic="Handbook-Bold"
    )


def esc(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\n", "<br/>")
    )


def bullets(items: Iterable[str]) -> str:
    return "<br/>".join(f"- {esc(item)}" for item in items)


def pstyle(size=8.2, leading=None, color=INK, bold=False, align=TA_LEFT, space_after=0):
    return ParagraphStyle(
        name=f"s-{size}-{bold}-{align}-{color}",
        fontName="Handbook-Bold" if bold else "Handbook",
        fontSize=size,
        leading=leading or size * 1.42,
        textColor=color,
        alignment=align,
        wordWrap="CJK",
        spaceAfter=space_after,
        allowWidows=0,
        allowOrphans=0,
    )


def draw_paragraph(c, text, x, top, width, style, max_height=None):
    para = Paragraph(text, style)
    _, h = para.wrap(width, max_height or PAGE_H)
    if max_height is not None and h > max_height:
        raise ValueError(f"Paragraph overflow: {h:.1f} > {max_height:.1f}: {text[:80]}")
    para.drawOn(c, x, top - h)
    return top - h


def footer(c, page_no: int) -> None:
    y = 11 * mm
    c.setStrokeColor(LINE)
    c.setLineWidth(0.45)
    c.line(17 * mm, y + 6 * mm, PAGE_W - 17 * mm, y + 6 * mm)
    c.setFont("Handbook", 6.8)
    c.setFillColor(MUTED)
    c.drawString(17 * mm, y + 1.5 * mm, f"学习资料 | 非临床报告 | 规范截止日期 {AS_OF}")
    c.drawRightString(PAGE_W - 17 * mm, y + 1.5 * mm, f"{page_no}")


def header(c, section: str, page_no: int, color=INK) -> None:
    c.setFillColor(color)
    c.setFont("Handbook-Bold", 7.3)
    c.drawString(17 * mm, PAGE_H - 12 * mm, section)
    footer(c, page_no)


def box(c, x, y, w, h, fill=PALE, stroke=LINE, radius=3 * mm):
    c.setFillColor(fill)
    c.setStrokeColor(stroke)
    c.setLineWidth(0.6)
    c.roundRect(x, y, w, h, radius, fill=1, stroke=1)


def fit_sections(c, sections, x, top, width, available, theme):
    """Fit labelled paragraphs into one column without clipping."""
    for body_size in (8.15, 7.9, 7.65, 7.4, 7.15, 6.95):
        heading_style = pstyle(body_size + 0.4, body_size * 1.25, theme, True)
        body_style = pstyle(body_size, body_size * 1.42, INK, False)
        blocks = []
        total = 0.0
        for label, body in sections:
            hp = Paragraph(esc(label), heading_style)
            _, hh = hp.wrap(width, PAGE_H)
            bp = Paragraph(body, body_style)
            _, bh = bp.wrap(width, PAGE_H)
            blocks.append((hp, hh, bp, bh))
            total += hh + bh + 3.4 * mm
        if total <= available:
            y = top
            for hp, hh, bp, bh in blocks:
                hp.drawOn(c, x, y - hh)
                y -= hh + 0.7 * mm
                bp.drawOn(c, x, y - bh)
                y -= bh + 2.7 * mm
            return body_size, total
    raise ValueError(f"Criterion column overflow ({total:.1f} > {available:.1f})")


PATHOGENIC = [
    dict(code="PVS1", name="Predicted loss of function / 预测功能丧失", strength="Very Strong / 极强", status="UPDATED",
         en="A predicted null variant supports pathogenicity only when loss of function is an established mechanism for the relevant gene-disease pair.",
         zh="变异预计造成基因功能丧失，同时必须已确认功能丧失是该基因导致目标疾病的致病机制。无义、移码、经典剪接、起始丢失或外显子缺失并不会自动满足 PVS1。",
         question="Will this variant truly remove clinically relevant gene function in the disease context? / 它是否会在目标疾病语境中真正破坏临床相关基因功能？",
         context=["明确的 gene-disease-MOI 与 LoF 致病机制", "临床相关转录本、外显子位置和变异后果", "NMD 是否发生；末端外显子和 NMD 逃逸", "替代剪接、替代起始、救援转录本与外显子重要性", "剪接变异的预测或实测 RNA 产物"],
         pitfalls=["见到 stop/frameshift/+/-1,2 就自动赋值", "使用错误转录本或忽略非关键外显子", "LoF 并非该疾病机制", "将同一 RNA 结果同时计入 PVS1 与 PS3"],
         update="ClinGen PVS1 决策树可按 NMD、转录本和区域重要性调整为 Very Strong、Strong、Moderate 或 Supporting。剪接 RNA 证据主要通过 PVS1_Strength(RNA)表达，避免与 PS3 重复。",
         example="合成例：某早发隐性病已确立 LoF 机制；变异位于主要转录本中部并预计触发 NMD。完成外显子重要性与救援转录本核查后，才考虑相应 PVS1 强度。", source="[R1] Table 3 PVS1; [R3] PVS1; [R10] splicing"),
    dict(code="PS1", name="Same amino-acid change / 相同氨基酸改变", strength="Strong / 强", status="UPDATED",
         en="A different nucleotide change produces the same amino-acid substitution as an independently established pathogenic variant.",
         zh="待评价变异的核苷酸改变不同，但在同一临床相关转录本上产生与一个已被独立确认为致病的变异完全相同的氨基酸替换。",
         question="Is the protein consequence truly identical, and is the reference variant independently pathogenic? / 蛋白后果是否真正相同，参照变异是否独立致病？",
         context=["两变异的准确 HGVS 与同一转录本", "相同残基、相同替换结果", "参照变异的独立底层证据", "两种核苷酸改变各自的剪接预测", "相同 gene-disease-mechanism 语境"],
         pitfalls=["参照变异只是 VUS 或冲突标签", "同一密码子但不同氨基酸，此时考虑 PM5", "忽略不同核苷酸改变的剪接差异", "参照分类依赖 PS1，形成循环论证"],
         update="蛋白层原始逻辑保留。ClinGen 剪接指南允许在预测 RNA 事件精确一致时使用强度调整后的 PS1，但不能把同一剪接区域简单等同为相同 RNA 后果。",
         example="合成例：c.100A>G 与独立致病的 c.100A>C 都产生 p.Lys34Glu，且剪接预测均无额外影响；核对参照变异证据后再评估 PS1。", source="[R1] Table 3 PS1; [R10] splicing"),
    dict(code="PS2", name="Confirmed de novo / 已确认新发", strength="Strong / 强", status="UPDATED",
         en="The variant is de novo in an affected individual, with parental relationships confirmed and a phenotype consistent with the gene-disease association.",
         zh="变异在患病个体中为新发，双亲均未检出，且生物学亲子关系已确认；患者表型应与目标基因-疾病关系一致。",
         question="How reliable and disease-specific is the de novo observation? / 这次新发观察有多可靠，表型有多特异？",
         context=["先证者与双亲可靠基因型及覆盖质量", "亲子关系确认状态", "标准化表型及其特异性", "遗传方式、外显率与家族史", "亲本低水平嵌合检测能力", "多个病例的独立性和去重"],
         pitfalls=["未确认亲子关系却使用 PS2", "父母未完整检测", "表型与基因疾病关系不一致", "忽略亲本嵌合或重复病例", "所有 de novo 固定记为 Strong"],
         update="ClinGen PS2/PM6 v1.1 按亲缘确认、表型特异性和独立病例累计选择强度；总分约 0.5/1/2/4 对应 Supporting/Moderate/Strong/Very Strong。隐性病缺少另一 P/LP 等位基因时通常降级。",
         example="合成例：三联体检测发现新发变异，亲子关系已确认，患者具有高度特异表型。该病例贡献按 ClinGen 表格计分，而不是自动固定 Strong。", source="[R1] Table 3 PS2; [R4] PS2/PM6 v1.1"),
    dict(code="PS3", name="Damaging functional evidence / 有害功能证据", strength="Strong / 强", status="UPDATED",
         en="A disease-relevant and well-validated functional assay demonstrates a damaging effect on the gene or gene product.",
         zh="经过充分验证、且确实反映目标疾病机制的功能实验显示该变异对基因、蛋白或相关细胞功能产生有害影响。",
         question="Does this assay measure the disease mechanism, and is this assay instance clinically calibrated? / 实验是否测量疾病机制，且该实验实例是否经过临床校准？",
         context=["明确的疾病机制与实验生物学相关性", "野生型、空白、阳性和阴性对照", "独立分类的良性/致病校准变异", "动态范围、阈值、重复、批次与统计", "原始读数、模型及组织相关性"],
         pitfalls=["任何论文实验都称 well-established", "只有野生型对照", "测到变化但与疾病机制无关", "计算预测冒充功能实验", "同一 RNA 结果与 PVS1 双计数"],
         update="ClinGen 四步法要求先确认机制，再评价实验类别、具体实验实例，最后应用到变异。可依据校准调整强度；OddsPath 约 >2.1、>4.3、>18.7、>350 分别对应 Supporting、Moderate、Strong、Very Strong。",
         example="合成例：细胞实验测量目标通路，包含独立 P/B 变异对照并预设阈值；待评价变异落入异常区。强度由验证性能决定，不由实验名称决定。", source="[R1] Table 3 PS3; [R5] PS3/BS3"),
    dict(code="PS4", name="Case enrichment / 病例富集", strength="Strong / 强", status="CSPEC CHECK",
         en="The variant is significantly enriched among affected individuals compared with appropriately matched controls.",
         zh="待评价变异在患病人群中的出现率显著高于适当匹配的对照；极罕见病中可用多个独立、表型一致病例形成降级证据。",
         question="Is there statistically credible, non-duplicated enrichment in relevant cases? / 是否存在统计可信、去重且匹配良好的病例富集？",
         context=["病例与对照的独立性、祖源和检测可比性", "2x2 计数、OR/RR 与置信区间", "表型一致性和纳入标准", "文献、实验室、ClinVar 间病例去重", "人群数据库覆盖与外显率"],
         pitfalls=["单个病例报告即 PS4", "重复计算同一患者", "祖源或平台不匹配", "无效应量/置信区间", "套用其他基因病例阈值"],
         update="截至截止日保留 2015 通用基线；具体病例数和强度应采用适用的 VCEP/CSpec。原始框架的病例-对照示例要求效应量足够且置信区间不跨 1。",
         example="合成例：20 个独立病例中 8 人携带，匹配对照中 1/2000；完成去重、祖源匹配和统计分析后，才评估 PS4。", source="[R1] Table 3 PS4; [R2]/[R13] check CSpec"),
    dict(code="PM1", name="Hotspot or critical domain / 热点或关键功能域", strength="Moderate / 中等", status="CSPEC CHECK",
         en="The variant lies in a well-established mutational hotspot or critical functional domain with little or no benign variation.",
         zh="变异位于已充分建立的致病热点或关键功能域，且该区域缺少可靠良性变异。重点是疾病相关的区域证据，而不是任意结构域注释。",
         question="Is this a validated disease-relevant hotspot/domain depleted of benign variation? / 该位置是否为经过验证的疾病相关热点或缺少良性变异的关键区域？",
         context=["准确蛋白位置和临床相关转录本", "热点/功能域的独立证据", "该区域致病与良性变异分布", "基因-疾病机制和变异类型", "适用 CSpec 定义的区域边界"],
         pitfalls=["位于任何 Pfam 域都给 PM1", "由待评价变异反向定义热点", "忽略区域中的良性变异", "与 PP3 或功能证据重复", "跨疾病/转录本套用同一区域"],
         update="没有覆盖所有基因的统一数值规则。优先采用 VCEP/CSpec 预先定义的热点、区域和允许强度。",
         example="合成例：VCEP 预先规定某受体激活环为 PM1 区域；变异准确落入且疾病机制一致，才按该规范使用。", source="[R1] Table 3 PM1; [R13] CSpec"),
    dict(code="PM2", name="Absent or sufficiently rare / 人群中缺失或足够罕见", strength="2015 Moderate; current Supporting", status="UPDATED",
         en="The variant is absent, or sufficiently rare for a recessive model, in population data with adequate coverage and quality.",
         zh="在适当的人群数据库和充分覆盖下，变异未观察到；对隐性病则频率足够低。数据库未命中不等于 AF=0，更不自动等于 PM2。",
         question="Was a valid population query completed, and is the observed rarity compatible with the disease model? / 查询是否有效，罕见程度是否符合疾病模型？",
         context=["组装版本、规范化表示、数据库版本", "祖源分层 AF/FAF、AC/AN、质量和覆盖", "遗传方式、患病率、外显率与异质性", "查询状态必须是成功，不是 unavailable", "适用 CSpec 阈值"],
         pitfalls=["NO_RECORD 直接写 AF=0", "资源不可用当作未观察到", "忽略低覆盖或小 AN", "用统一频率阈值", "与其他人群证据重复"],
         update="ClinGen PM2 v1.0 将通用强度从 Moderate 降为 Supporting，并增加 Very Strong + Supporting 可形成 Likely Pathogenic 的补充组合。疾病特异阈值仍优先。",
         example="合成例：gnomAD 查询成功且目标位点覆盖充分，所有合适祖源组 AC=0；结合疾病模型后，当前通用做法最多考虑 PM2_Supporting。", source="[R1] Table 3 PM2; [R6] PM2 v1.0"),
    dict(code="PM3", name="In trans in recessive disease / 隐性病反式观察", strength="Moderate / 中等", status="UPDATED",
         en="For a recessive disorder, the variant is observed in trans with an independently pathogenic allele.",
         zh="在隐性疾病中，待评价变异与另一条已被独立确认为致病/可能致病的等位基因位于反式，是复合杂合致病模型的证据。",
         question="Is phase reliable, and how strong is the other allele? / 相位是否可靠，另一条等位基因证据有多强？",
         context=["目标疾病为适用的隐性遗传", "另一等位基因的独立 P/LP 分类", "亲本或其他方法确认 phase", "纯合/复合杂合、病例独立性", "多个先证者去重"],
         pitfalls=["未定相却声称 in trans", "另一等位基因只是 VUS", "同一病例重复计数", "把普通携带状态当证据", "用 PM3 再支撑锚定等位基因，循环论证"],
         update="ClinGen PM3 v1.0 按 phase 与另一等位基因等级累计：确认 trans 的 P/LP 通常 1 分；未知 phase、纯合或 VUS 搭配贡献更低且有上限；累计约 0.5/1/2/4 对应 Supporting/Moderate/Strong/Very Strong。",
         example="合成例：两个独立病例均为复合杂合，亲本确认待评价变异分别与独立 P 变异 in trans。逐病例去重计分后选择 PM3 强度。", source="[R1] Table 3 PM3; [R7] PM3 v1.0"),
    dict(code="PM4", name="Protein length change / 蛋白长度改变", strength="Moderate / 中等", status="CSPEC CHECK",
         en="An in-frame insertion/deletion outside a nonfunctional repeat, or a stop-loss variant, changes protein length.",
         zh="不改变阅读框的插入/缺失若不在无功能重复区，或终止丢失变异导致蛋白延长，可作为中等致病证据。",
         question="Does the length change affect a functionally relevant, disease-related protein region? / 长度变化是否影响疾病相关的功能区域？",
         context=["正确转录本与 in-frame/stop-loss 后果", "重复区和功能域注释", "改变的氨基酸长度与位置", "疾病机制和已知变异谱", "剪接与比对质量"],
         pitfalls=["frameshift 也用 PM4（应评价 PVS1）", "所有 in-frame indel 自动 PM4", "忽略无功能重复区", "只看长度不看疾病机制", "与 PM1 重复权重"],
         update="截至截止日保留 2015 基线；具体区域和强度优先使用 CSpec/VCEP。",
         example="合成例：临床相关转录本中缺失 3 个高度保守氨基酸，位于已知功能区且不是重复序列；排除剪接影响后考虑 PM4。", source="[R1] Table 3 PM4; [R13] CSpec"),
    dict(code="PM5", name="Different missense at same residue / 同残基不同错义", strength="Moderate / 中等", status="UPDATED",
         en="A novel missense change affects a residue where a different missense change is independently established as pathogenic.",
         zh="同一氨基酸残基已经存在另一种独立确认为致病的错义替换；待评价变异产生不同的错义替换。",
         question="Is the reference substitution independently pathogenic and mechanistically comparable? / 参照替换是否独立致病并具有可比机制？",
         context=["同一转录本、同一残基、不同替换", "参照变异的独立底层证据", "两变异的剪接预测", "基因-疾病与错义致病机制", "相同密码子其他变异"],
         pitfalls=["参照仅为 LP/VUS 或数据库标签", "相同氨基酸替换误用 PM5（应 PS1）", "相邻残基也算 PM5", "循环使用 PM5", "忽略剪接差异"],
         update="蛋白层基线保留；剪接指南对相邻/同位点剪接预测关系另有强度规则。优先采用 CSpec 对参照等级、数量和强度的规定。",
         example="合成例：p.Arg100Trp 已凭独立功能与病例证据为 P；新变异 p.Arg100Gln。确认无循环证据和剪接差异后评估 PM5。", source="[R1] Table 3 PM5; [R10] splicing"),
    dict(code="PM6", name="Assumed de novo / 假定新发", strength="Moderate / 中等", status="UPDATED",
         en="The variant appears de novo in an affected individual, but biological parental relationships have not been confirmed.",
         zh="患者及父母检测支持变异为新发，但生物学亲子关系尚未确认，因此原始强度低于 PS2。",
         question="Is this a tested trio with unconfirmed relationships, rather than an untested family? / 这是亲缘未确认的已检测三联体，还是根本未检测的家系？",
         context=["患者及双亲变异检测结果", "亲缘确认状态明确记录", "患者表型和遗传方式", "检测深度与嵌合能力", "独立病例去重"],
         pitfalls=["双亲未检测仍使用 PM6", "与 PS2 同时计同一事件", "表型不一致仍计分", "始终固定 Moderate", "忽略亲本嵌合"],
         update="与 PS2 共用 ClinGen v1.1 积分框架；未确认亲缘的病例通常贡献为确认病例的一半，累计后仍可对应多个强度。",
         example="合成例：三联体均完成目标位点检测，父母未检出但未做亲缘确认；结合表型特异性按 PM6 分值计入。", source="[R1] Table 3 PM6; [R4] PS2/PM6 v1.1"),
    dict(code="PP1", name="Co-segregation / 共分离", strength="Supporting / 支持", status="UPDATED",
         en="The variant co-segregates with disease across informative relatives in a gene definitively associated with that disorder.",
         zh="在与目标疾病关系明确的基因中，变异在家系内随疾病共同分离；只计算真正有信息量的亲属和减数分裂事件。",
         question="How much independent segregation information does the pedigree provide? / 家系提供了多少独立且有信息量的共分离信息？",
         context=["完整家系、关系、逐人基因型与表型", "年龄、阴性表型、外显率和 phenocopy", "遗传方式；隐性病需要 phase", "独立家系去重", "复杂家系必要时 linkage/LOD"],
         pitfalls=["先证者加一位携带者即 PP1", "忽略未患病携带者年龄", "用定相亲本重复计共分离", "同一单倍型多个候选共享全部证据", "复杂家系机械数人头"],
         update="ClinGen 2023 将 PP1 与 PP4 作为相关的 locus-level 证据定量评价，并限制合并权重；低外显率、高 phenocopy 或大型谱系建议正式 linkage。",
         example="合成例：显性病三代家系中 4 名有信息量患病者携带，2 名足龄未患病者不携带；核对外显率与独立减数分裂后计量 PP1。", source="[R1] Table 3 PP1; [R11] PP1/BS4/PP4"),
    dict(code="PP2", name="Missense mechanism context / 错义机制背景", strength="Supporting / 支持", status="CSPEC CHECK",
         en="The variant is missense in a gene where benign missense variation is uncommon and pathogenic missense change is an established mechanism.",
         zh="变异为错义；该基因-疾病关系很少容忍良性错义变异，并且致病性错义改变已被证明是重要致病机制。",
         question="Does this specific gene-disease mechanism make missense change unusually informative? / 该特定基因-疾病机制是否使错义改变具有额外信息？",
         context=["正确转录本与错义后果", "基因-疾病与错义致病机制", "良性/致病错义谱与区域约束", "VCEP 是否预先允许 PP2", "与 PP3/PM1 独立性"],
         pitfalls=["所有罕见错义自动 PP2", "用 pLI/LOEUF 代替错义机制", "基因中良性错义很多仍使用", "由单个变异反向定义资格"],
         update="没有替代 2015 基线的通用资格阈值。最好由 VCEP/CSpec 预先指定可用基因或区域。",
         example="合成例：某疾病的绝大多数可靠致病变异为特定功能型错义，且该基因良性错义稀少；按适用 CSpec 才考虑 PP2。", source="[R1] Table 3 PP2; [R13] CSpec"),
    dict(code="PP3", name="Damaging computational evidence / 有害计算证据", strength="Supporting / 支持", status="UPDATED",
         en="A suitably calibrated computational predictor supports a damaging effect for the relevant variant type and mechanism.",
         zh="预先选定、经独立临床校准且适用于当前变异类型的计算工具，其分数落入支持有害影响的证据区间。",
         question="Is one appropriate predictor calibrated for this variant type, version and threshold? / 是否使用了适合该变异类型、版本与阈值的单一校准工具？",
         context=["工具名称、精确版本、原始分数", "适用变异类型和独立校准阈值", "转录本与组装版本", "分析前预选策略", "剪接预测的专门规范"],
         pitfalls=["SIFT/PolyPhen/CADD 等多数投票", "多个相关工具当独立证据", "开发者默认阈值冒充临床阈值", "无结果解释为良性", "计算证据升级为 PS3"],
         update="ClinGen 建议使用一个经校准的预测器及其分数区间，可支持 Supporting、Moderate 或 Strong；不同工具和版本不能共享阈值。剪接预测须遵循专门指南并避免与 PVS1/PS1 重复。",
         example="合成例：预先指定的 missense 模型 v2 分数进入已发表的 PP3_Moderate 区间；记录模型、版本和阈值，而不是再叠加其他模型投票。", source="[R1] Table 3 PP3; [R9] PP3/BP4; [R10] splicing"),
    dict(code="PP4", name="Phenotype specificity / 表型特异性", strength="Supporting / 支持", status="UPDATED",
         en="The phenotype or family history is sufficiently specific to implicate a single gene-disease explanation with high diagnostic yield.",
         zh="患者表型、家族史或特异性生化指标高度符合某一基因-疾病关系，使该基因成为高度特异或高诊断率的解释。",
         question="Is the phenotype truly specific, not merely compatible? / 表型是否真正高度特异，而不只是“不矛盾”？",
         context=["标准化阳性与阴性表型", "临床诊断标准与基因-疾病有效性", "遗传方式和历史诊断率", "locus heterogeneity 与替代诊断", "生化/酶学确认（如适用）"],
         pitfalls=["发育迟缓、癫痫等宽泛表型自动触发", "任意 HPO 相似度阈值等同 PP4", "忽略异质性", "与 PS2/PP1 重复使用相同表型信息"],
         update="ClinGen 2023 建议按实际诊断率将表型特异性定量，并控制与 PP1 的相关性及合并上限。",
         example="合成例：一种特异代谢物谱对单一基因缺陷具有极高诊断率；核对疾病模型与替代病因后评估 PP4，而非仅凭常见症状。", source="[R1] Table 3 PP4; [R11] PP1/BS4/PP4"),
    dict(code="PP5", name="Reputable-source assertion / 可信来源断言", strength="Historical Supporting; current NONE", status="RETIRED",
         en="Historically, a reputable source reported pathogenicity without exposing enough primary evidence for independent evaluation.",
         zh="2015 原始含义是可信来源报告致病，但实验室无法取得足够原始证据独立复核。当前通用实践不再使用该条款。",
         question="Can the underlying evidence be retrieved and evaluated instead of counting an assertion? / 能否回到原始证据，而不是给来源结论计分？",
         context=["来源身份、分类、日期与疾病语境", "底层证据是否可获得", "与文献/ClinVar 记录去重", "仅作历史可追溯，不进入当前组合"],
         pitfalls=["ClinVar Pathogenic 标签本身即 PP5", "使用底层证据后再加 PP5", "过时或无疾病语境断言", "用专家结论替代独立评价"],
         update="ClinGen SVI 2018 正式建议停止 PP5，并从其判读界面移除。数据库和专家提交只用于发现底层证据，不作为额外证据再次计分。",
         example="合成例：ClinVar 有两星 Pathogenic 提交但无公开原始数据。当前做法是联系/追溯证据；不能把标签自动转换为 PP5_MET。", source="[R1] Table 3 PP5; [R8] PP5/BP6 discontinuation"),
]


BENIGN = [
    dict(code="BA1", name="High population frequency / 高人群频率", strength="Stand-alone / 独立", status="UPDATED",
         en="A sufficiently high population allele frequency can by itself support a benign classification; the 2015 baseline used greater than 5%.",
         zh="如果变异在合适普通人群中的频率高到与罕见孟德尔病致病作用明显不相容，可独立支持良性。2015 原始阈值为 >5%。",
         question="Is the frequency estimate robust and unquestionably incompatible with the disease model? / 频率估计是否可靠，并与疾病模型明确不相容？",
         context=["数据库版本、组装、规范化表示", "祖源分层 AF/FAF、AC、AN、纯合子数", "覆盖、质量和样本相关性", "患病率、外显率、MOI 与异质性", "BA1 exception list 和 CSpec 阈值"],
         pitfalls=["用 global AF 代替合适祖源组", "低 AN/低质量仍触发", "把 founder 人群直接当一般人群", "忽略例外清单", "NO_RECORD 当 AF=0", "与 BS1 重复累计"],
         update="ClinGen 2018 增加至少 2,000 个等位基因、一般大陆人群与例外机制等条件。2025 gnomAD v4 指南建议记录版本并优先使用 GroupMax Filtering AF；疾病特异规范优先。",
         example="合成例：高质量位点在合适大陆祖源组 FAF 为 7%，AN>20,000，且不在例外清单；核对疾病特异阈值后才可考虑 BA1。", source="[R1] Table 4 BA1; [R12] BA1; [R14] gnomAD v4"),
    dict(code="BS1", name="Frequency above disease maximum / 频率超过疾病上限", strength="Strong / 强", status="UPDATED",
         en="The variant is more frequent in the population than the disease model can plausibly accommodate.",
         zh="变异频率高于该特定疾病、遗传方式、外显率和异质性所能容许的最大可信频率，强烈反对其致病性。",
         question="What is the disease-specific maximum credible frequency, and is the estimate above it? / 疾病特异的最大可信频率是多少，观察值是否确实更高？",
         context=["患病率、外显率、MOI 与异质性", "疾病特异 maximum credible AF", "祖源分层 FAF/AF、AC/AN", "数据库覆盖和质量", "founder/bottleneck 情况"],
         pitfalls=["所有疾病统一用 1%", "只看 global AF", "忽略迟发/低外显率", "低质量或小 AN", "NO_RECORD/缺失值解释为 BS1", "与 BA1 双计数"],
         update="没有一个适用于所有疾病的统一 BS1 数值。ClinGen 2025 gnomAD v4 指南强调基于疾病模型的阈值和 GroupMax FAF，不建议用“严格缺失”代替频率模型。",
         example="合成例：疾病模型计算最大可信 FAF 为 0.01%，而合适祖源组高质量 FAF 为 0.2%；完成 founder 与外显率核查后考虑 BS1。", source="[R1] Table 4 BS1; [R14] gnomAD v4"),
    dict(code="BS2", name="Healthy adult with causal genotype / 健康成人携带预期致病基因型", strength="Strong / 强", status="CSPEC CHECK",
         en="The variant is observed in a healthy adult in a genotype expected to cause a fully penetrant, early-onset disorder.",
         zh="对于预期早发且完全外显的疾病，健康成人携带按遗传模式本应致病的基因型：隐性病纯合、显性病杂合、X 连锁病半合子。",
         question="Would this genotype be expected to cause disease by this age with near-complete penetrance? / 按年龄和外显率，该基因型是否本应导致疾病？",
         context=["可靠合子性、性别、亲缘和样本质量", "明确 MOI", "受检者年龄与系统表型", "早发且完全/近完全外显", "排除伪缺陷、轻表型与低外显率"],
         pitfalls=["隐性病普通杂合携带者也算 BS2", "把 gnomAD 默认视为深度表型健康人", "用于迟发或低外显率疾病", "忽略 X 染色体性别/PAR", "不核对纯合子质量"],
         update="截至截止日保留 2015 基线；具体数量、年龄、表型和外显率门槛通常由 CSpec/VCEP 指定。",
         example="合成例：完全外显、儿童期起病的显性病中，一名 70 岁经系统检查健康者可靠携带该杂合变异；排除样本/表型问题后评估 BS2。", source="[R1] Table 4 BS2; [R13] CSpec"),
    dict(code="BS3", name="Normal functional evidence / 正常功能证据", strength="Strong / 强", status="UPDATED",
         en="A well-validated, disease-relevant functional assay shows a normal result for the variant.",
         zh="经过充分验证且测量目标疾病机制的功能实验显示待评价变异没有有害功能影响；强度由实验校准质量决定。",
         question="Does a validated assay rule against the relevant damaging mechanism? / 经验证实验是否反对该疾病相关的有害机制？",
         context=["疾病机制与实验生物学相关性", "阳性、阴性和野生型对照", "独立分类的校准变异", "阈值、动态范围、重复与统计", "实验是否覆盖完整潜在功能"],
         pitfalls=["任何“结果正常”都算 BS3", "实验只测不相关功能", "无对照或无重复", "cDNA 系统评估 NMD/剪接", "多个相关实验重复计分", "RNA 阴性再与 BP7 双计数"],
         update="ClinGen 四步法允许按验证性能调整强度；OddsPath 约 <0.48、<0.23、<0.053 对应 Supporting、Moderate、Strong。RNA 证明无剪接影响主要由 BP7_Strong(RNA) 表达。",
         example="合成例：已校准酶活实验含独立 P/B 对照，待评价变异重复测量均落入正常区；依据 OddsPath 或验证对照选择 BS3 强度。", source="[R1] Table 4 BS3; [R5] PS3/BS3; [R10] splicing"),
    dict(code="BS4", name="Non-segregation / 非共分离", strength="Strong / 强", status="UPDATED",
         en="The variant fails to segregate with the phenotype in an informative family.",
         zh="在具有信息量的家系中，变异与疾病表型不共同遗传，例如按模型应携带的患病成员没有该变异。",
         question="Is the non-segregation informative after accounting for penetrance, phenocopy and heterogeneity? / 考虑外显率、表型模拟和异质性后，该非共分离是否真正有信息量？",
         context=["完整家系、亲缘、逐人基因型与表型", "年龄、外显率、phenocopy", "MOI、phase 和位点异质性", "检测范围、样本身份与质量", "同一家系相关观察的依赖性"],
         pitfalls=["一个患病非携带者即 BS4", "低/年龄相关外显率中用未患病携带者", "未排除第二诊断", "隐性复合杂合家系机械归因某一等位基因", "重复计算同一家系"],
         update="ClinGen 2023 提供 Bayesian/points 方法；复杂、低外显率或高 phenocopy 家系应正式 linkage/maximum-likelihood 分析。AR 复合杂合非共分离常不能区分哪条等位基因无害。",
         example="合成例：完全外显显性家系中，两名明确患病、足龄且样本可靠的亲属不携带该变异；先排除 phenocopy/第二诊断后评估 BS4。", source="[R1] Table 4 BS4; [R11] PP1/BS4/PP4"),
    dict(code="BP1", name="Missense in truncation-driven disease / 截短机制疾病中的错义", strength="Supporting / 支持", status="CSPEC CHECK",
         en="The variant is missense in a gene-disease relationship where pathogenic variants are predominantly truncating.",
         zh="若该特定基因-疾病关系的致病机制主要是截短/LoF，而错义通常不是致病机制，则错义变异可获得弱良性支持。",
         question="Is missense change genuinely inconsistent with this disease mechanism? / 错义改变是否确实不符合该疾病机制？",
         context=["准确转录本和后果", "gene-disease-MOI 与机制", "已知致病变异谱", "LoF/GoF/dominant-negative 区分", "错义热点和功能域"],
         pitfalls=["基因中有截短致病变异就给所有错义 BP1", "忽略同基因不同疾病/遗传方式", "忽略 GoF/DN 或错义热点", "只凭 constraint 指标"],
         update="无替代 2015 基线的通用规则；许多 VCEP 会按具体 gene-disease 删除、限制或细化 BP1。",
         example="合成例：某隐性病只由双等位 LoF 导致，可靠病例中未建立错义机制；按 CSpec 允许范围才考虑 BP1。", source="[R1] Table 4 BP1; [R13] CSpec"),
    dict(code="BP2", name="Cis/trans observation / 顺反式观察", strength="Supporting / 支持", status="CSPEC CHECK",
         en="In the same gene, the variant is in trans with a pathogenic allele for a fully penetrant dominant disorder, or in cis with a pathogenic allele under any inheritance model.",
         zh="完全外显显性病中，待评价变异与足以致病的等位基因处于 trans；或任何遗传模式下与致病等位基因确定处于 cis，可提供弱良性支持。",
         question="Is phase proven, and does the anchor allele fully explain the same disease context? / 相位是否可靠，锚定等位基因是否已充分解释同一疾病？",
         context=["可靠 cis/trans phase", "同一基因及疾病语境", "锚定等位基因独立为 P", "显性 trans 分支需完全外显", "复合等位基因与修饰作用"],
         pitfalls=["未定相就假定 cis/trans", "隐性病 in trans 错用 BP2（通常评价 PM3）", "跨基因替代诊断错用 BP2", "锚定变异只是 VUS", "忽略复杂/多基因机制"],
         update="截至截止日保留 2015 基线；CSpec/VCEP 可限定锚定等级、相位证明和场景。",
         example="合成例：完全外显显性病中，待评价变异经长读长确认与独立 P 变异处于 trans；核对双重诊断可能后评估 BP2。", source="[R1] Table 4 BP2; [R13] CSpec"),
    dict(code="BP3", name="In-frame indel in nonfunctional repeat / 无功能重复区内框内变异", strength="Supporting / 支持", status="CSPEC CHECK",
         en="An in-frame insertion or deletion lies in a repetitive region with no established function.",
         zh="不改变阅读框的插入/缺失位于重复或低复杂度区域，而且该区域没有已知功能，可形成弱良性证据。",
         question="Is this truly a nonfunctional repeat where length variation is tolerated? / 这是否真是可容忍长度变化的无功能重复区？",
         context=["正确转录本、规范化 indel 与 in-frame 后果", "重复/低复杂度区域注释", "功能域、保守性和已知致病变异", "剪接影响和比对质量", "疾病是否由重复长度致病"],
         pitfalls=["所有 in-frame indel 都给 BP3", "无注释等同无功能", "忽略重复扩增疾病", "位于保守功能域仍使用", "未排除比对伪影/剪接影响"],
         update="截至截止日保留 2015 基线；具体基因规范可能将其设为不适用。",
         example="合成例：规范化后为 3 bp 缺失，位于人群中高度可变的非功能性低复杂度区；核对疾病机制后考虑 BP3。", source="[R1] Table 4 BP3; [R13] CSpec"),
    dict(code="BP4", name="Benign computational evidence / 良性计算证据", strength="Supporting / 支持", status="UPDATED",
         en="A preselected and appropriately calibrated predictor supports no damaging effect on the relevant molecular mechanism.",
         zh="预先选定且经临床校准、适合当前变异类型的计算工具，分数落入支持无有害影响的区间。不能依赖多工具简单投票。",
         question="Is the benign prediction calibrated, versioned and applicable to this mechanism? / 良性预测是否经校准、可追溯且适用于该机制？",
         context=["工具、精确版本和原始分数", "变异类型、机制与校准阈值", "分析前预选策略", "转录本和组装", "缺失值、Indeterminate 与冲突处理"],
         pitfalls=["多个相关工具多数投票", "开发者默认阈值当临床阈值", "事后挑有利工具", "缺分当 benign", "蛋白与剪接影响混成一次投票"],
         update="ClinGen 建议单个预选、经校准工具，可按分数区间形成 Supporting/Moderate/Strong。不同工具版本不可共享阈值；剪接预测按 2023 指南单独校准。",
         example="合成例：预先选定的模型版本和阈值将错义变异置于 BP4_Supporting 区间；结果处于 Indeterminate 时则不评价，不能当 BP4。", source="[R1] Table 4 BP4; [R9] PP3/BP4; [R10] splicing"),
    dict(code="BP5", name="Alternative molecular diagnosis / 替代分子诊断", strength="Supporting / 支持", status="CSPEC CHECK",
         en="A clear alternative molecular diagnosis explains the patient's phenotype better than the variant under assessment.",
         zh="患者已有明确的另一种分子病因，能更好解释当前表型，因此待评价变异可获得弱良性支持。",
         question="Does the alternative diagnosis truly explain the complete phenotype without excluding a dual diagnosis? / 替代诊断是否真正解释完整表型，并已考虑双重诊断？",
         context=["替代变异独立为 P/LP", "替代疾病、MOI 与患者表型一致", "待评价 gene-disease 语境", "双重诊断、混合表型和修饰作用", "隐性病携带状态"],
         pitfalls=["另一候选只是 VUS", "一有诊断就排除所有其他变异", "忽略双重诊断", "把普通携带者当替代病因", "与同基因 phase 的 BP2 混淆"],
         update="截至截止日无替代 2015 基线的通用规则。原始框架已要求警惕双重/多基因诊断；具体疾病可由 CSpec 限定。",
         example="合成例：患者有已确认的代谢病 P/LP 双等位基因并解释全部特异表型；另一基因的偶然错义变异可谨慎评估 BP5，但仍检查双重诊断。", source="[R1] Table 4 BP5; [R13] CSpec"),
    dict(code="BP6", name="Reputable-source assertion / 可信来源断言", strength="Historical Supporting; current NONE", status="RETIRED",
         en="Historically, a reputable source reported a benign classification without exposing enough primary evidence for independent evaluation.",
         zh="2015 原意是可信来源称变异为良性但原始证据无法复核。ClinGen 已建议停止使用；仅保留历史可追溯记录。",
         question="Can the primary evidence be recovered instead of counting another laboratory's conclusion? / 能否取得原始证据，而不是给他人结论计分？",
         context=["来源、日期、疾病语境与历史分类", "底层证据可得性", "与数据库/文献证据去重", "当前组合强度为 NONE"],
         pitfalls=["ClinVar 星级/标签本身当证据", "使用原始证据后再加 BP6", "数据库共识当事实源", "无疾病语境的良性结论"],
         update="ClinGen SVI 2018 明确建议停止 PP5/BP6，并从 Variant Curation Interface 移除。当前应回到人群、功能、家系等原始证据逐条评价。",
         example="合成例：数据库显示 Benign，但提交没有公开依据。当前系统应记录 source assertion 并追溯证据，不能产生 BP6_MET。", source="[R1] Table 4 BP6; [R8] PP5/BP6 discontinuation"),
    dict(code="BP7", name="No predicted/observed splice effect / 无剪接影响", strength="Supporting; RNA may be Strong", status="UPDATED",
         en="For an eligible synonymous or sufficiently remote intronic variant, calibrated prediction indicates no splice effect; suitable RNA evidence may support BP7_Strong(RNA).",
         zh="对适用的同义或远离剪接区的内含子变异，可靠预测显示不影响剪接可给 BP7；高质量 RNA 证明无异常剪接时，可在严格条件下给 BP7_Strong(RNA)。",
         question="Is the variant position eligible, and have splice and protein consequences both been handled correctly? / 位置是否适用，剪接与蛋白后果是否分别得到正确处理？",
         context=["临床相关转录本与精确 exon/intron 位置", "BP4 剪接预测及版本阈值", "RNA 来源、对照、等位表达、NMD 与组织相关性", "异常转录本检出能力", "外显子变异的蛋白层影响"],
         pitfalls=["见 synonymous 就给 BP7", "未先满足 BP4", "仅靠低保守触发", "剪接区域内机械使用", "RNA 阴性与 BS3 双计数", "改变蛋白的外显子变异只评剪接"],
         update="ClinGen 2023：预测型 BP7 以 BP4 已满足为前提；同义变异需位于外显子首个碱基及末端 3 个碱基之外，内含子/非编码变异需位于保守定义的剪接区域之外。保守性不再是通用必要条件。RNA 阴性主要记 BP7_Strong(RNA)。",
         example="合成例：同义变异位于外显子中部，校准剪接模型满足 BP4；高质量相关组织 RNA 显示等位表达正常且无异常转录本，可按指南评估 BP7 强度。", source="[R1] Table 4 BP7; [R10] splicing"),
]


SOURCES = [
    ("R1", "Richards et al. ACMG/AMP 2015 sequence variant interpretation guideline, Tables 3-5. DOI 10.1038/gim.2015.30; PMID 25741868.", "https://pmc.ncbi.nlm.nih.gov/articles/PMC4544753/"),
    ("R2", "ClinGen Variant Classification Guidance hub (last updated July 2025).", "https://www.clinicalgenome.org/tools/clingen-variant-classification-guidance/"),
    ("R3", "ClinGen PVS1 recommendation, 2018 publication. DOI 10.1002/humu.23626.", "https://www.clinicalgenome.org/docs/recommendations-for-interpreting-the-loss-of-function-pvs1-acmg-amp-variant-criterion/"),
    ("R4", "ClinGen PS2/PM6 recommendation, version 1.1.", "https://www.clinicalgenome.org/docs/ps2-pm6-recommendation-for-de-novo-ps2-and-pm6-acmg-amp-criteria-version-1.0/"),
    ("R5", "ClinGen PS3/BS3 functional evidence guidance, 2019 publication. DOI 10.1186/s13073-019-0690-2.", "https://www.clinicalgenome.org/docs/recommendations-for-application-of-the-functional-evidence-ps3-bs3-criterion-using-the-acmg-amp-sequence-variant-interpretation/"),
    ("R6", "ClinGen PM2 recommendation, version 1.0, approved 2020-09-04.", "https://www.clinicalgenome.org/docs/pm2-recommendation-for-absence-rarity/"),
    ("R7", "ClinGen PM3 recommendation, version 1.0, approved 2019-05-02.", "https://www.clinicalgenome.org/docs/pm3-recommendation-for-in-trans-criterion-pm3-version-1.0/"),
    ("R8", "ClinGen recommendation to discontinue PP5/BP6, 2018 publication. DOI 10.1038/gim.2018.42.", "https://www.clinicalgenome.org/docs/the-acmg-amp-reputable-source-criteria-for-the-interpretation-of-sequence-variants/"),
    ("R9", "ClinGen PP3/BP4 computational calibration, 2022 publication. DOI 10.1016/j.ajhg.2022.10.013.", "https://www.clinicalgenome.org/docs/calibration-of-computational-tools-for-missense-variant-pathogenicity-classification-and-clingen-recommendations-for-pp3-bp4-cri/"),
    ("R10", "ClinGen splicing evidence guidance, 2023 publication. DOI 10.1016/j.ajhg.2023.06.002.", "https://www.clinicalgenome.org/docs/application-of-the-acmg-amp-framework-to-capture-evidence-relevant-to-predicted-and-observed-impact-on-splicing-recommendations/"),
    ("R11", "ClinGen PP1/BS4/PP4 segregation and phenotype guidance, 2023 publication. DOI 10.1016/j.ajhg.2023.11.009.", "https://www.clinicalgenome.org/docs/clingen-guidance-for-use-of-the-pp1-bs4-co-segregation-and-pp4-phenotype-specificity-criteria-for-sequence-variant/"),
    ("R12", "ClinGen updated BA1 recommendation, 2018 publication. DOI 10.1002/humu.23642.", "https://www.clinicalgenome.org/docs/updated-recommendation-for-the-benign-stand-alone-acmg-amp-criterion/"),
    ("R13", "ClinGen CSpec Registry for approved gene-disease-MOI specifications.", "https://cspec.genome.network/cspec/ui/svi/"),
    ("R14", "ClinGen guidance to VCEPs on gnomAD v4, version 3.0, 2025-06-23.", "https://www.clinicalgenome.org/site/assets/files/9445/june_2025_communication_to_clingen_vceps_from_clingen_vcep_review_committee.pdf"),
    ("R15", "Tavtigian et al. 2020 Bayesian points framework. DOI 10.1016/j.gim.2020.08.004.", "https://pmc.ncbi.nlm.nih.gov/articles/PMC8011844/"),
    ("R16", "ACMG documents in development: revised sequence variant classification guidance remains in development as of the cutoff.", "https://www.acmg.net/ACMG/Medical-Genetics-Practice-Resources/Documents_in_Development.aspx"),
]


def status_colors(status: str):
    if status == "RETIRED":
        return GRAY, colors.HexColor("#EEEEEE")
    if status == "UPDATED":
        return GREEN, colors.HexColor("#EAF4EE")
    return AMBER, AMBER_LIGHT


def criterion_page(c, item, page_no, pathogenic=True):
    theme = BURGUNDY if pathogenic else NAVY
    pale = BURGUNDY_LIGHT if pathogenic else NAVY_LIGHT
    direction = "PATHOGENIC EVIDENCE / 致病方向" if pathogenic else "BENIGN EVIDENCE / 良性方向"
    header(c, direction, page_no, theme)
    c.bookmarkPage(item["code"])
    c.addOutlineEntry(f'{item["code"]} {item["name"]}', item["code"], level=0, closed=False)

    left = 17 * mm
    right = PAGE_W - 17 * mm
    top = PAGE_H - 23 * mm
    c.setFillColor(theme)
    c.setFont("Handbook-Bold", 26)
    c.drawString(left, top - 10 * mm, item["code"])
    c.setFont("Handbook-Bold", 12.5)
    c.drawString(left + 31 * mm, top - 5.3 * mm, item["name"])
    c.setFont("Handbook", 8.4)
    c.setFillColor(INK)
    c.drawString(left + 31 * mm, top - 11 * mm, f'2015 direction/strength: {item["strength"]}')
    status_color, status_fill = status_colors(item["status"])
    badge_w = 31 * mm
    box(c, right - badge_w, top - 12.5 * mm, badge_w, 10 * mm, status_fill, status_color, 2 * mm)
    c.setFillColor(status_color)
    c.setFont("Handbook-Bold", 7.2)
    c.drawCentredString(right - badge_w / 2, top - 8.9 * mm, item["status"])
    c.setStrokeColor(theme)
    c.setLineWidth(1.2)
    c.line(left, top - 17 * mm, right, top - 17 * mm)

    content_top = top - 23 * mm
    bottom = 24 * mm
    gap = 7 * mm
    col_w = (right - left - gap) / 2
    available = content_top - bottom
    left_sections = [
        ("Faithful English summary (paraphrased)", esc(item["en"])),
        ("中文解释 / Chinese explanation", esc(item["zh"])),
        ("Core question / 核心问题", esc(item["question"])),
        ("Required context / 必要上下文", bullets(item["context"])),
    ]
    right_sections = [
        ("Common pitfalls / 常见误用", bullets(item["pitfalls"])),
        ("Current ClinGen note / 当前通用修订", esc(item["update"])),
        ("Synthetic example / 合成示例", esc(item["example"])),
        ("Source locator / 原文定位", esc(item["source"])),
    ]
    fit_sections(c, left_sections, left, content_top, col_w, available, theme)
    fit_sections(c, right_sections, left + col_w + gap, content_top, col_w, available, theme)
    c.setStrokeColor(LINE)
    c.setLineWidth(0.55)
    c.line(left + col_w + gap / 2, bottom, left + col_w + gap / 2, content_top)
    c.showPage()


def title_page(c, page_no):
    c.setFillColor(BURGUNDY)
    c.rect(0, PAGE_H - 67 * mm, PAGE_W, 67 * mm, fill=1, stroke=0)
    c.setFillColor(NAVY)
    c.rect(0, 0, PAGE_W, 19 * mm, fill=1, stroke=0)
    c.setFillColor(colors.white)
    c.setFont("Handbook-Bold", 24)
    c.drawString(19 * mm, PAGE_H - 30 * mm, "ACMG/AMP 2015")
    c.setFont("Handbook-Bold", 20)
    c.drawString(19 * mm, PAGE_H - 43 * mm, "变异证据 28 项标准")
    c.setFont("Handbook", 12)
    c.drawString(19 * mm, PAGE_H - 54 * mm, "中英文学习手册 | Bilingual Study Handbook")

    y = PAGE_H - 92 * mm
    c.setFillColor(INK)
    c.setFont("Handbook-Bold", 13)
    c.drawString(19 * mm, y, "2015 原始框架 + 已发布 ClinGen 通用修订")
    y -= 10 * mm
    c.setFont("Handbook", 9.5)
    c.drawString(19 * mm, y, f"资料截止日期 / Authority cutoff: {AS_OF}")
    y -= 16 * mm
    box(c, 19 * mm, y - 62 * mm, PAGE_W - 38 * mm, 62 * mm, PALE, LINE, 3 * mm)
    draw_paragraph(c,
        "<b>适用范围：</b>germline Mendelian small sequence variants（生殖系孟德尔遗传小序列变异）。<br/><br/>"
        "<b>本册提供：</b>16 项致病方向证据、12 项良性方向证据、必要上下文、常见误用、当前修订提示、2015 组合基线、学习示例、工作表和权威来源。<br/><br/>"
        "<b>重要：</b>英文为忠实改写的学习摘要，不是官方逐字原文；中文为解释性翻译，不是 ACMG、AMP 或 ClinGen 官方译文。",
        27 * mm, y - 8 * mm, PAGE_W - 54 * mm, pstyle(10.2, 15.0, INK), 48 * mm)
    c.setFillColor(colors.white)
    c.setFont("Handbook", 7.4)
    c.drawString(19 * mm, 7.2 * mm, "Study and research use only | Not a clinical report")
    c.drawRightString(PAGE_W - 19 * mm, 7.2 * mm, str(page_no))
    c.showPage()


def text_page(c, page_no, section, title, subtitle, columns, theme=INK):
    header(c, section, page_no, theme)
    left = 17 * mm
    right = PAGE_W - 17 * mm
    top = PAGE_H - 26 * mm
    c.setFillColor(theme)
    c.setFont("Handbook-Bold", 17)
    c.drawString(left, top, title)
    top -= 8 * mm
    if subtitle:
        top = draw_paragraph(c, esc(subtitle), left, top, right - left, pstyle(8.7, 12.6, MUTED)) - 4 * mm
    gap = 8 * mm
    col_w = (right - left - gap) / 2
    bottom = 24 * mm
    fit_sections(c, columns[0], left, top, col_w, top - bottom, theme)
    fit_sections(c, columns[1], left + col_w + gap, top, col_w, top - bottom, theme)
    c.setStrokeColor(LINE)
    c.line(left + col_w + gap / 2, bottom, left + col_w + gap / 2, top)
    c.showPage()


def overview_page(c, page_no):
    header(c, "QUICK REFERENCE / 快速索引", page_no, INK)
    left, right = 17 * mm, PAGE_W - 17 * mm
    top = PAGE_H - 27 * mm
    c.setFillColor(INK)
    c.setFont("Handbook-Bold", 17)
    c.drawString(left, top, "28 项证据标准总览 / The 28 evidence criteria")
    top -= 11 * mm
    rows = [["方向 / Direction", "强度 / Strength", "代码 / Codes", "数量"]]
    rows += [
        ["致病 Pathogenic", "Very Strong", "PVS1", "1"],
        ["致病 Pathogenic", "Strong", "PS1-PS4", "4"],
        ["致病 Pathogenic", "Moderate", "PM1-PM6", "6"],
        ["致病 Pathogenic", "Supporting", "PP1-PP5", "5"],
        ["良性 Benign", "Stand-alone", "BA1", "1"],
        ["良性 Benign", "Strong", "BS1-BS4", "4"],
        ["良性 Benign", "Supporting", "BP1-BP7", "7"],
    ]
    table = Table(rows, colWidths=[46 * mm, 42 * mm, 59 * mm, 18 * mm], rowHeights=[9 * mm] + [8 * mm] * 7)
    table.setStyle(TableStyle([
        ("FONTNAME", (0, 0), (-1, 0), "Handbook-Bold"),
        ("FONTNAME", (0, 1), (-1, -1), "Handbook"),
        ("FONTSIZE", (0, 0), (-1, -1), 8),
        ("BACKGROUND", (0, 0), (-1, 0), INK),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("BACKGROUND", (0, 1), (-1, 4), BURGUNDY_LIGHT),
        ("BACKGROUND", (0, 5), (-1, 7), NAVY_LIGHT),
        ("GRID", (0, 0), (-1, -1), 0.4, LINE),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LEFTPADDING", (0, 0), (-1, -1), 3 * mm),
    ]))
    _, th = table.wrap(right - left, PAGE_H)
    table.drawOn(c, left, top - th)
    y = top - th - 13 * mm
    box(c, left, y - 50 * mm, right - left, 50 * mm, AMBER_LIGHT, colors.HexColor("#D8B46B"))
    draw_paragraph(c,
        "<b>三个必须牢记的例外</b><br/><br/>"
        "1. PP5 与 BP6 是 2015 历史条款；ClinGen 已建议当前通用实践停止使用。<br/>"
        "2. PM2 在 2015 为 Moderate；当前 ClinGen 通用建议为 Supporting。<br/>"
        "3. PVS1、PS2/PM6、PS3/BS3、PM3、PP1/BS4/PP4、PP3/BP4 等可根据已发布规范和证据质量调整强度，代码前缀不再等同于永远固定的实际强度。",
        left + 7 * mm, y - 7 * mm, right - left - 14 * mm, pstyle(9, 13, INK), 38 * mm)
    y -= 63 * mm
    draw_paragraph(c,
        "<b>规则优先级：</b>适用且已批准的 gene-disease-MOI CSpec/VCEP > 已发布 ClinGen 通用/SVI 指南 > 2015 基线中尚未被修订的部分。不要从不同版本中挑选有利条款进行混搭。",
        left, y, right - left, pstyle(9.2, 13.5, INK))
    c.showPage()


def combination_page(c, page_no):
    header(c, "2015 COMBINATION BASELINE / 2015 组合基线", page_no, INK)
    left, right = 17 * mm, PAGE_W - 17 * mm
    top = PAGE_H - 26 * mm
    c.setFillColor(INK)
    c.setFont("Handbook-Bold", 16)
    c.drawString(left, top, "2015 ACMG/AMP 原始组合规则")
    top -= 7 * mm
    draw_paragraph(c, "以下来自 Richards 2015 Table 5，只是历史基线，不等同于截至今日的完整实践规范。V/S/M/P 分别表示致病方向 Very Strong/Strong/Moderate/Supporting。",
                   left, top, right-left, pstyle(8.5, 12.2, MUTED))
    top -= 21 * mm
    gap = 8 * mm
    col_w = (right-left-gap)/2
    left_sections = [
        ("Pathogenic / 致病", bullets(["1V + >=1S", "1V + >=2M", "1V + 1M + 1P", "1V + >=2P", ">=2S", "1S + >=3M", "1S + 2M + >=2P", "1S + 1M + >=4P"])),
        ("Benign / 良性", bullets(["BA1", ">=2BS"])),
    ]
    right_sections = [
        ("Likely pathogenic / 可能致病", bullets(["1V + 1M", "1S + 1-2M", "1S + >=2P", ">=3M", "2M + >=2P", "1M + >=4P"])),
        ("Likely benign / 可能良性", bullets(["1BS + 1BP", ">=2BP"])),
        ("VUS / 临床意义未明", esc("未达到上述组合，或致病与良性证据相互矛盾。VUS 不应单独作为临床干预依据。")),
    ]
    fit_sections(c, left_sections, left, top, col_w, top-35*mm, BURGUNDY)
    fit_sections(c, right_sections, left+col_w+gap, top, col_w, top-35*mm, NAVY)
    box(c, left, 24 * mm, right-left, 37 * mm, AMBER_LIGHT, colors.HexColor("#D8B46B"))
    draw_paragraph(c,
        "<b>当前使用提醒：</b>PP5/BP6 不再计入；PM2 通常降为 Supporting；PM2 v1.0 补充了 1 Very Strong + 1 Supporting -> Likely Pathogenic。若选择 CSpec 或预先确定的 quantitative/points protocol，应完整遵循该版本，不能与 2015 组合随意拼接。",
        left+6*mm, 54*mm, right-left-12*mm, pstyle(8.2, 11.7, INK), 25*mm)
    c.showPage()


def worksheet_page(c, page_no):
    header(c, "WORKSHEET / 学习工作表", page_no, INK)
    left, right = 17*mm, PAGE_W-17*mm
    top = PAGE_H-27*mm
    c.setFillColor(INK); c.setFont("Handbook-Bold", 16)
    c.drawString(left, top, "空白人工判读工作表 / Blank review worksheet")
    top -= 11*mm
    fields = [
        "Variant (assembly + normalized HGVS)", "Gene / transcript", "Disease / MONDO", "MOI / mechanism",
        "Proband and family context", "Applicable CSpec/VCEP + version", "Authority cutoff / database versions"
    ]
    for label in fields:
        c.setFont("Handbook-Bold", 7.5); c.setFillColor(MUTED)
        c.drawString(left, top, label)
        top -= 3*mm
        c.setStrokeColor(LINE); c.line(left, top, right, top)
        top -= 8*mm
    top -= 1*mm
    rows = [["Code", "Applied strength", "Outcome", "Atomic evidence / locator", "Independent?"]]
    for _ in range(9): rows.append(["", "", "", "", ""])
    table = Table(rows, colWidths=[18*mm, 32*mm, 27*mm, 78*mm, 23*mm], rowHeights=[8*mm]+[10*mm]*9)
    table.setStyle(TableStyle([
        ("FONTNAME", (0,0), (-1,0), "Handbook-Bold"), ("FONTNAME", (0,1), (-1,-1), "Handbook"),
        ("FONTSIZE", (0,0), (-1,-1), 6.8), ("BACKGROUND", (0,0), (-1,0), PALE),
        ("GRID", (0,0), (-1,-1), 0.45, LINE), ("VALIGN", (0,0), (-1,-1), "MIDDLE"),
        ("LEFTPADDING", (0,0), (-1,-1), 2*mm),
    ]))
    _, h = table.wrap(right-left, PAGE_H); table.drawOn(c, left, top-h)
    y = top-h-7*mm
    c.setFont("Handbook-Bold", 7.5); c.setFillColor(MUTED)
    c.drawString(left, y, "Conflicts / missing information / review decision")
    y -= 3*mm
    for _ in range(3):
        c.setStrokeColor(LINE); c.line(left, y, right, y); y -= 8*mm
    c.showPage()


def sources_pages(c, start_page):
    per_page = 8
    page_no = start_page
    for offset in range(0, len(SOURCES), per_page):
        header(c, "AUTHORITATIVE SOURCES / 权威来源", page_no, INK)
        left, right = 17*mm, PAGE_W-17*mm
        top = PAGE_H-27*mm
        c.setFillColor(INK); c.setFont("Handbook-Bold", 16)
        c.drawString(left, top, "权威原文入口与定位" if offset == 0 else "权威来源（续）")
        top -= 10*mm
        if offset == 0:
            top = draw_paragraph(c, "点击 PDF 中的网址可访问原始页面。正式判读时应记录版本、发布日期、访问日期和具体 locator；网页入口不是固定规则版本。",
                                 left, top, right-left, pstyle(8.2, 11.8, MUTED)) - 5*mm
        for sid, title, url in SOURCES[offset:offset+per_page]:
            c.setFillColor(BURGUNDY if sid == "R1" else NAVY)
            c.setFont("Handbook-Bold", 8.3)
            c.drawString(left, top, f"[{sid}]")
            text_x = left + 13*mm
            top = draw_paragraph(c, esc(title), text_x, top+1.5*mm, right-text_x, pstyle(7.8, 11.2, INK)) - 1.2*mm
            url_para = Paragraph(f'<link href="{url}" color="#17365D"><u>{esc(url)}</u></link>', pstyle(6.25, 8.6, NAVY))
            _, uh = url_para.wrap(right-text_x, PAGE_H)
            url_para.drawOn(c, text_x, top-uh)
            top -= uh + 5.2*mm
        if offset + per_page >= len(SOURCES):
            box(c, left, 24*mm, right-left, 32*mm, AMBER_LIGHT, colors.HexColor("#D8B46B"))
            draw_paragraph(c,
                "<b>版本声明：</b>截至 2026-08-31，本文以 2015 ACMG/AMP 基线及已正式发布的 ClinGen/CSpec 修订为准。ACMG 官方页面仍将下一代序列变异分类文件列为开发中，因此本手册未将草案或未发布内容当作现行规范。",
                left+6*mm, 50*mm, right-left-12*mm, pstyle(8, 11.4, INK), 21*mm)
        c.showPage(); page_no += 1
    return page_no


def build(output: Path):
    register_fonts()
    output.parent.mkdir(parents=True, exist_ok=True)
    c = canvas.Canvas(str(output), pagesize=A4, pageCompression=1)
    c.setTitle("ACMG/AMP 2015 28 Criteria Bilingual Study Handbook")
    c.setAuthor("Varvis project study material")
    c.setSubject("Bilingual educational guide to the 28 ACMG/AMP 2015 evidence criteria")
    c.setKeywords("ACMG, AMP, ClinGen, variant interpretation, bilingual, evidence criteria")
    page = 1
    title_page(c, page); page += 1
    text_page(c, page, "SCOPE AND DISCLAIMER / 范围与免责声明", "使用范围与临床安全声明",
              "本页应在任何教学、研发或演示使用前阅读。",
              ([
                  ("Scope / 适用范围", esc("本册用于 germline Mendelian small sequence variants。它不直接覆盖 CNV/SV、体细胞肿瘤变异、药物基因组学、复杂病风险等位基因、线粒体专门规范或独立的拷贝数分类框架。")),
                  ("What this is / 本册是什么", esc("供学习、科研与软件设计参考的中英文手册；帮助理解每条 criterion 要回答的问题、需要的数据、常见误区与已发布修订。")),
                  ("What this is not / 本册不是什么", esc("不构成临床诊断、医疗建议、遗传咨询或正式检验报告，也不得单独用于患者管理决策。")),
              ], [
                  ("Professional review / 专业复核", esc("正式分类必须围绕明确 variant-gene-disease-MOI-transcript 上下文，由具备资质的遗传学或分子病理人员结合完整表型、家系、原始证据和检测质量完成。")),
                  ("VUS safety / VUS 安全", esc("临床意义未明变异不应单独作为不可逆临床干预依据。冲突和缺失信息必须保留，不能为了得到五级结果而强制折叠。")),
                  ("Translation and copyright / 翻译与版权", esc("英文内容为作者整理的忠实学习摘要，不是官方逐字原文；中文为解释性翻译，不是 ACMG、AMP 或 ClinGen 官方译文。逐字定义请核对 Richards 2015 Tables 3-5。")),
              ]), INK); page += 1
    text_page(c, page, "HOW TO USE / 使用方法", "从变异上下文到证据条款",
              "先定义问题，再搜集原子证据，最后才进行条款评价和组合分类。",
              ([
                  ("Step 1 - Assertion context / 断言上下文", bullets(["规范化 variant + assembly", "gene + 临床相关 transcript", "disease + MONDO/其他稳定标识", "MOI + disease mechanism", "患者/家系与表型上下文"])),
                  ("Step 2 - Select authority / 选择规范", esc("先检查适用且已批准的 CSpec/VCEP；其次使用已发布 ClinGen 通用指南；未被修订部分再回到 2015 基线。")),
                  ("Step 3 - Gather evidence / 搜集证据", esc("记录每条原子证据的来源、版本、locator、参数和查询状态。资源不可用、未运行、无记录、未达到阈值必须区分。")),
              ], [
                  ("Step 4 - Evaluate criteria / 评价条款", esc("将适用性、评价结果、冲突和人工审阅分开记录。数据库 annotation 或自动 suggestion 只能提示候选，不自动等于 MET。")),
                  ("Step 5 - Control dependence / 控制依赖", esc("识别同一病例、同一实验、同一预测特征和同一数据库断言，避免在多个条款中重复计数。")),
                  ("Step 6 - Combine and review / 组合与复核", esc("使用同一版本的组合框架，保留冲突和不确定性；最终结果应有人工复核、证据表和可追溯来源。")),
              ]), INK); page += 1
    text_page(c, page, "LEGEND AND AUTHORITY / 图例与规范", "证据方向、强度与规范优先级",
              "代码名称来自 2015 基线；实际应用强度可被已发布的 ClinGen/CSpec 规范调整。",
              ([
                  ("Pathogenic direction / 致病方向", bullets(["PVS: Very Strong / 极强", "PS: Strong / 强", "PM: Moderate / 中等", "PP: Supporting / 支持"])),
                  ("Benign direction / 良性方向", bullets(["BA: Stand-alone / 独立", "BS: Strong / 强", "BP: Supporting / 支持"])),
                  ("Status badges / 状态标签", bullets(["UPDATED: 已有已发布通用细化", "CSPEC CHECK: 先查疾病专项规范", "RETIRED: 历史保留，当前不使用"])),
              ], [
                  ("Authority priority / 权威优先级", bullets(["1. 匹配且有效的 gene-disease-MOI CSpec/VCEP", "2. 已发布 ClinGen 通用/SVI 指南", "3. 2015 基线中尚未被修订的部分"])),
                  ("Do not mix / 禁止混搭", esc("不得从不同版本、不同 VCEP 或 points/qualitative 框架中只挑选有利条款。多个 overlay 同时匹配或适用范围不清时，应暂停并人工核查。")),
                  ("Version freeze / 版本冻结", esc("至少记录 specification ID、版本、approved/released 日期、来源 locator 与访问日期；仅保存一个会变化的网址不足以复算。")),
              ]), INK); page += 1
    overview_page(c, page); page += 1
    text_page(c, page, "EVIDENCE HYGIENE / 证据卫生", "独立性、缺失状态与冲突",
              "正确处理证据关系，往往比增加更多打分更重要。",
              ([
                  ("No result is not benign / 无结果不等于良性", bullets(["NO_RECORD 不是 AF=0", "UNAVAILABLE/DISABLED/TIMEOUT 不是阴性", "NOT_EVALUATED 不是 NOT_MET", "NOT_APPLICABLE 不是反向证据"])),
                  ("Atomic provenance / 原子证据来源", esc("每条结论应能回溯到具体病例、实验、频率数据集、预测模型或文献段落，并记录版本、参数、质量与 locator。")),
                  ("Legacy suggestions / 旧提示", esc("旧系统的 SUGGESTED 只能作为待审候选，不能机械升级为 ACMG criterion MET。")),
              ], [
                  ("Common dependence / 常见依赖", bullets(["同一 RNA 结果：PVS1 与 PS3/BS3/BP7", "同一表型：PS2、PP1 与 PP4", "相关计算工具多数投票", "同一患者跨论文/ClinVar 重复", "数据库断言与其底层证据"])),
                  ("Conflict handling / 冲突处理", esc("保留致病和良性双方原始证据、强度与不确定性。冲突不是删除其中一方，也不是取绝对值较大者；需核查数据质量、适用性和依赖后再组合。")),
                  ("Human review / 人工复核", esc("自动化适合检索、规范化、预计算和提示；最终条款评价及临床分类需要可追溯的专业复核。")),
              ]), INK); page += 1

    for item in PATHOGENIC:
        criterion_page(c, item, page, pathogenic=True); page += 1
    for item in BENIGN:
        criterion_page(c, item, page, pathogenic=False); page += 1

    combination_page(c, page); page += 1
    text_page(c, page, "CURRENT UPDATE MAP / 当前修订图", "2015 基线与当前通用实践的关键差异",
              "这不是所有 CSpec 的替代品，只列最容易造成系统性错误的通用变化。",
              ([
                  ("Population / 人群证据", bullets(["BA1: 增加数据质量、样本量和例外机制", "BS1: 使用疾病特异最大可信频率", "PM2: Moderate 降为 Supporting", "gnomAD v4: 记录版本并使用合适祖源 GroupMax FAF"])),
                  ("Mechanism and function / 机制与功能", bullets(["PVS1: 决策树与可变强度", "PS3/BS3: 按实验实例验证性能定强度", "剪接 RNA 证据重新分工，避免双计数"])),
                  ("Reputable-source codes / 来源断言", esc("PP5/BP6 保留为 2015 历史条目，但当前通用实践停止使用；应追溯底层证据。")),
              ], [
                  ("Cases and families / 病例与家系", bullets(["PS2/PM6: 亲缘确认 + 表型特异 + 独立病例积分", "PM3: phase + 另一等位基因等级 + 病例累计", "PP1/BS4/PP4: 定量并控制相关性"])),
                  ("Computational / 计算证据", bullets(["PP3/BP4: 单个预选且经校准的工具", "记录工具、版本、原始分数和阈值", "Indeterminate/无结果不是反向证据", "剪接预测按专门规范"])),
                  ("Disease-specific overlay / 疾病专项", esc("PS4、PM1、PM4、PP2、BS2、BP1-BP5 等尤其依赖 gene-disease-MOI 的 VCEP/CSpec 资格、阈值和强度。")),
              ]), INK); page += 1
    text_page(c, page, "QUANTITATIVE BOUNDARIES / 定量边界", "三类“积分”不要混在一起",
              "名称相似，但它们位于不同层级，不能把病例分直接加到最终分类总分。",
              ([
                  ("PS2/PM6 internal points", esc("对每次 de novo 观察，根据亲缘是否确认、表型特异性和病例独立性计分；总分只用于选择 PS2/PM6 的应用强度。")),
                  ("PM3 internal points", esc("对隐性病的每个 in-trans/phase 观察，根据另一等位基因等级和相位可信度计分；总分只用于选择 PM3 强度。")),
                  ("PP1/BS4/PP4 quantitative evidence", esc("家系和表型证据可转换为 Bayesian/points，并控制同一 locus-level 信息的相关性和上限。")),
              ], [
                  ("Criterion combination points", esc("Tavtigian 2020 将应用后的证据强度映射为组合层权重，用于五级分类。只有在预先选定并完整遵循该协议时使用。")),
                  ("Do not improvise / 不自行拼接", esc("不要同时套用 2015 Table 5、任意自定义分数、PS2/PM6 病例分和某 VCEP points 表。先选定适用规范，再把内部病例证据转换为 criterion strength。")),
                  ("Future revisions / 未来修订", esc("截至本册截止日，ACMG 官方仍将下一代序列变异分类文件列为开发中。未发布草案不进入当前规则；正式发布后应重新审查本册。")),
              ]), INK); page += 1
    text_page(c, page, "SYNTHETIC WALKTHROUGH / 合成演示", "示例：证据提示不等于自动分类",
              "以下为完全合成的教学案例，不对应真实患者，也不构成临床判读。",
              ([
                  ("Context / 上下文", bullets(["GRCh38 规范化 SNV", "Gene X - Disease Y，AD，LoF 已确立", "主要转录本中部 stop-gain", "患者表型与 Disease Y 一致", "双亲检测但亲缘未确认"])),
                  ("Resource observations / 资源观察", bullets(["gnomAD 查询成功、覆盖充分、未观察到", "VEP: stop_gained", "剪接模型无额外影响", "无可用疾病特异 CSpec"])),
                  ("Candidate criteria / 候选条款", bullets(["PVS1: 需运行决策树并核查 NMD/转录本", "PM2_Supporting: 成功查询后可评", "PM6: 按表型与亲缘状态计分"])),
              ], [
                  ("What automation may do / 自动化可做", bullets(["规范化和资源查询", "生成带来源的 candidate", "提示缺失上下文和潜在重复", "展示规则版本与需要人工判断的节点"])),
                  ("What automation must not do / 自动化不能做", bullets(["把 stop_gained 直接当 PVS1_MET", "把 NO_RECORD 直接当 AF=0", "把 HPO 相似度自动当 PP4", "在未核查依赖时直接给临床五级结果"])),
                  ("Review outcome / 教学结论", esc("评审者补齐 NMD、转录本、疾病机制、家系和版本信息后，才能确定每条 criterion 的 applicability、outcome 与 applied strength，再进入同一版本的组合框架。")),
              ]), INK); page += 1
    worksheet_page(c, page); page += 1
    text_page(c, page, "GLOSSARY / 术语表", "中英文核心术语",
              "建议打印后在页边补充本实验室采用的缩写和规范版本。",
              ([
                  ("Variant and context", bullets(["variant / 变异", "criterion, criteria / 证据标准", "evidence code / 证据代码", "direction / 证据方向", "strength / 证据强度", "transcript / 转录本", "assembly / 基因组组装", "disease mechanism / 致病机制", "mode of inheritance (MOI) / 遗传模式"])),
                  ("Classification", bullets(["pathogenic / 致病", "likely pathogenic / 可能致病", "VUS / 临床意义未明", "likely benign / 可能良性", "benign / 良性", "applicability / 适用性", "evaluation outcome / 评价结果"])),
              ], [
                  ("Molecular and family", bullets(["loss of function (LoF) / 功能丧失", "nonsense-mediated decay (NMD) / 无义介导降解", "de novo / 新生变异", "phase / 相位", "in trans / 反式", "in cis / 顺式", "segregation / 共分离", "penetrance / 外显率", "phenocopy / 表型模拟"])),
                  ("Evidence infrastructure", bullets(["allele frequency (AF) / 等位基因频率", "filtering AF (FAF) / 过滤频率", "functional assay / 功能实验", "computational predictor / 计算预测器", "OddsPath / 致病优势比", "CSpec / 条款规范注册表", "VCEP / 变异解读专家组", "SVI / 序列变异解释工作组"])),
              ]), INK); page += 1
    page = sources_pages(c, page)
    c.save()
    print(f"WROTE {output}")
    print(f"PAGES {page-1}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Build the dated 2026-08-31 bilingual study handbook.")
    parser.add_argument("--output", type=Path, required=True, help="Destination PDF path")
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output already exists; choose a new path to preserve archived material.")
    build(args.output)
