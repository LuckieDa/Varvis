"""Read-only, source-linked human views of saved PP1/PM3 literature records.

This is a display projection. It neither runs extraction nor grants evidence
qualification. Original model and scientific states remain in their source files.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import re
from collections import OrderedDict
from datetime import datetime, timezone
from pathlib import Path


PP1_FIELDS = {
    'F05.2': '临床状态（原文）', 'F05.3': '症状与检查', 'F05.4': '年龄',
    'F05.5': '性别', 'F06.2': '检测是否进行', 'F06.3': '目标变异检测结果',
    'F07.1': '亲属关系',
}
PP1_KINDS = {'F05.2': {'diagnosis', 'clinicalStatus'}, 'F05.4': {'age'},
             'F05.5': {'sex'}, 'F06.3': {'targetResult'}, 'F07.1': {'parentOf', 'childOf'}}
PM3_FIELDS = {
    'geneSymbol': '基因', 'transcript': '转录本（文中版本）',
    'targetVariant': 'DNA／蛋白变化', 'reportedZygosity': '两份基因的变异情况',
    'diagnosis': '文中疾病名称', 'affectedStatus': '文中患病状态',
    'parentalTransmission': '父母携带情况', 'testingMethod': '检测方法',
    'partnerVariant': '另一变异', 'partnerClassification': '另一变异的分类依据',
    'reportedPhase': '两个变异的位置关系', 'phaseMethod': '位置关系核实方法',
    'testingLimitations': '检测局限', 'classificationBasis': '分类依据',
}
MEMBERS = {'husband': '丈夫', 'father': '父亲', 'mother': '母亲',
           'aunt': '姑／姨辈亲属（原文 aunt）',
           'grandfather': '祖辈男性亲属（原文 grandfather）',
           'proband': '文中受检者'}
VALUES = {
    'female': '女性', 'male': '男性', 'affected': '文中报告患病',
    'apparently healthy': '原文称“表面健康”',
    'homozygous': '原文报告纯合（两份拷贝均有该变化）',
    'homozygote': '原文报告纯合（两份拷贝均有该变化）',
    'exome sequencing': '外显子测序', 'Sanger sequencing': 'Sanger测序',
    'Marfan syndrome': 'Marfan综合征',
    'diagnosed with Marfan syndrome (MFS)': '原文记载诊断为Marfan综合征',
    'Asparagine synthetase deficiency': '天冬酰胺合成酶缺乏症（原文疾病名称）',
    'A heterozygous variant was detected in both parents': '原文报告父母均检出杂合变异',
}


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def read_json(path):
    raw = Path(path).read_bytes()
    if len(raw) > 20 * 1024 * 1024:
        raise ValueError('资料文件过大，请选择逐篇结果。')
    value = json.loads(raw.decode('utf-8-sig'))
    if not isinstance(value, dict):
        raise ValueError('资料文件必须是JSON对象。')
    return value


def text(value, fallback='尚未记录'):
    return value.strip() if isinstance(value, str) and value.strip() else fallback


def display_value(value, context=''):
    value = text(value)
    if value == 'MFS patients' and 'Marfan syndrome (MFS)' in context:
        return '原文称Marfan综合征患者'
    if value in VALUES:
        return VALUES[value]
    age = re.fullmatch(r'(\d+(?:\.\d+)?)-year-old', value)
    if age:
        return age[1] + '岁（年龄类型待核定）'
    return value


def unique(values):
    return list(dict.fromkeys(values))


def refs_for(raw_refs):
    out, seen = [], set()
    for ref in raw_refs if isinstance(raw_refs, list) else []:
        if not isinstance(ref, dict) or not ref.get('unitId') or not ref.get('quote'):
            continue
        key = (ref['unitId'], ref['quote'])
        if key in seen:
            continue
        seen.add(key)
        out.append({'label': '表格单元格' if '/td[' in ref['unitId'] else '正文／图注原文',
                    'quote': ref['quote'], 'unitId': ref['unitId']})
    return out


def same_locator(a, b):
    keys = ('unitId', 'bundleSha256', 'sourceSha256', 'start', 'end')
    return all(a.get(k) is not None and a.get(k) == b.get(k) for k in keys)


def bind_pp1_runtime(artifact, artifact_path, result, result_path):
    """Join saved display stages only after exact artifact and source binding."""
    if read_json(artifact_path) != artifact:
        return None
    binding = artifact.get('binding', {})
    q = result.get('reading', {}).get('qualification', {})
    if q.get('schemaVersion') != 'pp1-source-qualification-v1':
        return None
    if any(result.get(k) != binding.get(k) or not binding.get(k)
           for k in ('pmcid', 'bundleSha256', 'target')):
        return None
    if any(q.get('sourceIdentity', {}).get(k) != binding.get(k)
           for k in ('pmcid', 'bundleSha256')):
        return None
    # The document target contains printed aliases; the qualified target contains
    # the current allele/disease/selection. These are different identity roles.
    qualified_target = q.get('target')
    if qualified_target != binding.get('target'):
        if (not isinstance(qualified_target, dict)
                or qualified_target.get('geneSymbol') != binding['target'].get('geneSymbol')
                or result.get('evaluation', {}).get('target') != qualified_target):
            return None
    manifest_path = Path(result_path).parent / 'artifacts.json'
    if not manifest_path.is_file():
        return None
    manifest_raw = manifest_path.read_bytes()
    manifest = json.loads(manifest_raw.decode('utf-8-sig'))
    if result.get('artifactManifestSha256') not in (sha(manifest_raw), sha(canonical(manifest))):
        return None
    expected = sha(Path(artifact_path).read_bytes())
    owner = Path(result_path).parent.resolve()
    found = False
    for name in manifest.get('acceptedArtifacts', []):
        path = Path(name).resolve()
        if not path.is_relative_to(owner) or not path.is_file():
            return None
        if sha(path.read_bytes()) == expected:
            found = True
    return q if found else None


def typed_for(fact, qualification):
    if not qualification:
        return []
    kinds = PP1_KINDS.get(fact.get('raw', {}).get('fieldId'), set())
    source_refs = fact.get('sourceRefs', [])
    return [o for o in qualification.get('observations', [])
            if o.get('kind') in kinds and fact.get('claimId') in o.get('claimIds', [])
            and o.get('familyKey') == fact.get('familyKey')
            and o.get('memberKey') == fact.get('memberKey')
            and any(same_locator(a, b) for a in source_refs for b in o.get('sourceRefs', []))]


def base_document(pmcid, gene, target, kind, library, input_path):
    if not re.fullmatch(r'PMC[1-9][0-9]*', pmcid):
        raise ValueError('资料缺少有效论文身份。')
    paper = Path(library) / 'papers' / pmcid
    metadata = read_json(paper / 'metadata.json')
    return {
        'id': pmcid + '-' + kind, 'pmcid': pmcid, 'kind': kind, 'gene': gene,
        'title': gene + (' 家系核心资料' if kind == 'PP1' else ' 病例核心资料'),
        'targetVariants': list(target.get('variantAliases', []))[:2],
        'cases': [], 'gaps': [],
        'sources': {'articleUrl': 'https://pmc.ncbi.nlm.nih.gov/articles/' + pmcid + '/',
                    'articleTitle': metadata.get('title', ''), 'inputPath': str(Path(input_path).resolve()),
                    'originalPath': str((paper / (pmcid + '.xml')).resolve())},
        'limits': ['本卡是已有记录的中文阅读投影，不新增提取、不重新计算证据。',
                   '原文支持、程序识别与最终证据采用分别核对；未展示不代表论文没有报告。'],
    }


def pp1_document(artifact_path, result_path, library):
    artifact, result = read_json(artifact_path), read_json(result_path)
    if artifact.get('schemaVersion') != 'pp1-literature-artifact-v1':
        raise ValueError('请选择PP1来源提取／审核资料文件。')
    binding = artifact.get('binding', {})
    target = binding.get('target', {})
    q = bind_pp1_runtime(artifact, artifact_path, result, result_path)
    doc = base_document(binding.get('pmcid', ''), text(target.get('geneSymbol')), target, 'PP1', library, artifact_path)
    cases, count, recognized, supported = OrderedDict(), 0, 0, 0
    for fact in artifact.get('checked', {}).get('facts', []):
        raw = fact.get('raw', {})
        if raw.get('fieldId') not in PP1_FIELDS:
            continue
        count += 1
        typed = typed_for(fact, q)
        recognized += bool(typed)
        audit = fact.get('sourceAudit', {}).get('verdict') == 'SUPPORTED'
        supported += audit
        context = ' '.join(r.get('sourceContext', '') for r in fact.get('sourceRefs', []))
        family, member = text(raw.get('familyLabel')), text(raw.get('memberLabel'))
        label = MEMBERS.get(member, member + ('（文中受检者）' if member.startswith(('I-', 'II-', 'III-')) else ''))
        case = cases.setdefault((family, member), {'label': label, 'family': family, 'rows': []})
        note = '后续程序已形成对应观察。' if typed else '现有程序尚未核定其判定含义。'
        if not audit:
            note += '原审核片段的补充记录仍待复核。'
        elif not typed:
            note += '已有原文匹配和模型复核支持。'
        if raw.get('sourceValue') == 'apparently healthy':
            note += '不等同于正式排除疾病，也不表示变异检测阴性。'
        if member in ('aunt', 'grandfather'):
            note += '原文未在此记录中明确父系／母系。'
        case['rows'].append({'label': PP1_FIELDS[raw['fieldId']],
                             'value': display_value(raw.get('sourceValue'), context),
                             'status': 'supported' if typed else 'pending',
                             'statusText': '程序已识别，未计分' if typed else '判定含义待核',
                             'note': note, 'sourceRefs': refs_for(fact.get('sourceRefs', raw.get('citations', []))),
                             'audit': {'inputField': raw['fieldId'], 'claimId': fact.get('claimId'),
                                       'rawValue': raw.get('sourceValue'), 'originalVerification': fact.get('verification'),
                                       'sourceAuditSupported': audit, 'runtimeObservationMatched': bool(typed)}})
    doc['cases'] = list(cases.values())
    diagnoses = unique([o['value'] for o in (q or {}).get('observations', [])
                        if o.get('kind') == 'diagnosis' and isinstance(o.get('value'), str)])
    doc['disease'] = '、'.join(display_value(x) for x in diagnoses) + '（文中记载）' if diagnoses else '见下方临床状态；适用背景待核'
    doc['purpose'] = '用于核对家系中各成员的临床信息，供后续共分离证据判断使用。'
    doc['contextLabel'] = '当前审核片段中的家系成员'
    doc['summary'] = {'headline': '已提取家系核心信息；本次PP1仍未评价',
                      'detail': f'本片段{count}条核心记录，{recognized}条已形成程序可识别观察；'
                                f'{supported}条有模型来源复核支持。上述进度不等于证据已成立。'}
    if q and result.get('evaluation', {}).get('status') != 'NOT_EVALUATED':
        doc['summary']['headline'] = '已提取家系信息；另有保存的PP1评价记录'
    if not q:
        doc['summary']['headline'] = '已提取家系信息；当前运行与资料尚不能可靠对应'
        doc['gaps'].append({'title': '资料与当前运行的对应', 'required': '同一论文、资料包、目标及原件身份。',
                            'available': '当前审核片段仍可逐条查看。', 'difference': '未拼接其他运行的资格或结论，避免借用结果。'})
    else:
        reasons = {n.get('reasonCode') for n in result.get('evaluation', {}).get('needs', [])}
        if 'PP1_PUBLISHED_EXACT_CONTEXT_UNAVAILABLE' in reasons:
            doc['gaps'].append({'title': '当前疾病背景的适用判定依据',
                                'required': '与当前疾病及分析方法相容、具有来源依据的PP1知识。',
                                'available': '已有家系摘录和部分程序可识别的临床观察。',
                                'difference': '现有保存结果缺少该背景知识，因此保持未评价，尚未授予PP1分数。'})
        else:
            doc['gaps'].append({'title': '最终证据采用', 'required': '在当前背景下完成规范适用和计分核查。',
                                'available': '本卡展示已保存资料及处理进度。',
                                'difference': '本阅读器不据提取进度推导证据分数，请对照原运行结果。'})
    if recognized < count:
        doc['gaps'].append({'title': '年龄与“表面健康”等信息的具体含义',
                            'required': '区分观察年龄、发病年龄，以及正式排除疾病等不同含义。',
                            'available': '原文已经记载年龄和部分亲属的表面健康状态。',
                            'difference': '尚未核定的记录保留待核，不能直接改成无病、无变异或可计分。'})
    doc['coverage'] = {'text': '仅展示当前所选审核片段；后续运行汇总过其他原件时，须通过精确对应才显示其进度。'}
    doc['sources']['resultPath'] = str(Path(result_path).resolve())
    return doc


def pm3_documents(result_path, library):
    result = read_json(result_path)
    if result.get('schemaVersion') not in ('pm3-discovery-process-v1', 'pm3-paper-process-v1'):
        raise ValueError('请选择PM3逐篇资料或发现任务结果。')
    papers = result.get('papers', []) if result.get('schemaVersion') == 'pm3-discovery-process-v1' else [result]
    out = []
    for paper in papers:
        observations = paper.get('observations', {})
        if not observations.get('cases'):
            continue
        target = paper.get('target') or result.get('target', {})
        doc = base_document(paper.get('pmcid', ''), text(target.get('geneSymbol')), target, 'PM3', library, result_path)
        core_count, raw_count, diagnoses = 0, 0, []
        for case in observations['cases']:
            facts = case.get('facts', [])
            raw_count += len(facts)
            grouped = OrderedDict()
            for verified in case.get('semantics', {}).get('verifiedLiteralFacts', []):
                i = verified.get('factIndex')
                if not isinstance(i, int) or isinstance(i, bool) or not 0 <= i < len(facts):
                    continue
                raw, field = facts[i], verified.get('field')
                if field not in PM3_FIELDS or raw.get('field') != field:
                    continue
                cite = verified.get('citation', {})
                if not same_locator(raw.get('citation', {}), cite):
                    continue
                if verified.get('reportedValue') != raw.get('sourceValue'):
                    continue
                value = text(verified.get('value'))
                key = (field, value)
                row = grouped.setdefault(key, {'label': PM3_FIELDS[field], 'value': display_value(value),
                    'status': 'supported', 'statusText': '文内信息已核对',
                    'note': '保留的是文内已核信息，尚不表示PM3已正式采用。', 'sourceRefs': [],
                    'audit': {'inputField': field, 'factIndexes': [], 'rawFieldState': case.get('fields', {}).get(field, {}).get('status')}})
                row['audit']['factIndexes'].append(i)
                row['sourceRefs'] += refs_for([cite])
                row['sourceRefs'] = list({(r['unitId'], r['quote']): r for r in row['sourceRefs']}.values())
                if field == 'diagnosis':
                    diagnoses.append(value)
            core = list(grouped.values())
            core_count += len(core)
            # A different phrasing alone is not a scientific contradiction. Preserve
            # unresolved source statements separately, without inventing a new gap.
            supplementary = OrderedDict()
            for i in case.get('semantics', {}).get('unverifiedFactIndexes', []):
                if not isinstance(i, int) or isinstance(i, bool) or not 0 <= i < len(facts):
                    continue
                f = facts[i]; field = f.get('field')
                if field not in PM3_FIELDS or field in ('geneSymbol', 'transcript'):
                    continue
                value = display_value(f.get('sourceValue'))
                row = supplementary.setdefault(field, {'label': PM3_FIELDS[field] + '（补充原文）',
                    'value': '', 'status': 'pending', 'statusText': '原文陈述待核',
                    'note': '这条原文陈述尚未通过对应语义处理；多种写法不自动等于冲突。',
                    'sourceRefs': [], 'audit': {'inputField': field, 'factIndexes': [], 'values': []}})
                row['audit']['values'] = unique(row['audit']['values'] + [value])
                row['value'] = '；'.join(row['audit']['values'])
                row['sourceRefs'] += refs_for([f.get('citation', {})]); row['audit']['factIndexes'].append(i)
            independence = case.get('primarySourceQualification', {}).get('crossPaperIndependence')
            if independence is None:
                independence = case.get('independenceStatus')
            scope_label = ('本篇病例，跨论文独立性仍需核对'
                           if independence not in ('ESTABLISHED', 'CONFIRMED_INDEPENDENT') else '本篇病例')
            doc['cases'].append({'label': MEMBERS.get(case.get('caseLabel'), text(case.get('caseLabel'))),
                                  'family': scope_label, 'rows': core})
            if supplementary:
                doc['cases'].append({'label': '补充陈述：仍需核对', 'family': '对应同一病例，不作为新增病例累计',
                                      'rows': list(supplementary.values())})
        doc['disease'] = '、'.join(display_value(x) for x in unique(diagnoses)) or '原文疾病名称尚未核定'
        doc['purpose'] = '展示原文病例及变异信息，供PM3规范适用、病例独立性及采用核查使用。'
        doc['contextLabel'] = f'本篇{len(observations["cases"])}个病例；重复字段合并展示并保留出处'
        doc['summary'] = {'headline': '已提取病例核心信息；尚无正式PM3采用结论',
                          'detail': f'{raw_count}条原始字段记录整理为{core_count}类文内已核核心信息；补充陈述另列待核。'}
        assessment = result.get('pm3Assessment', {})
        if assessment.get('pm3FinalBusinessExecution') is True:
            doc['summary']['headline'] = '已有病例信息及保存的PM3执行记录'
            doc['summary']['detail'] += ' 本阅读器不重新计分，采用结论请对照原运行。'
        doc['gaps'] = []
        if assessment.get('pm3FinalBusinessExecution') is not True:
            doc['gaps'].append({'title': '当前规范与正式采用', 'required': '确定本次疾病背景适用的规则，并完成正式采用。',
             'available': '已有文内病例及变异资料。',
             'difference': '当前记录未提供已完成的正式采用；内部候选计算不能显示为PM3已满足。'})
        if any(c.get('primarySourceQualification', {}).get('crossPaperIndependence')
               not in ('ESTABLISHED', 'CONFIRMED_INDEPENDENT') for c in observations['cases']):
            doc['gaps'].append({'title': '跨论文病例是否重复', 'required': '合并不同文章时，核实是否为同一个病例。',
             'available': '病例已定位到本篇原文。',
             'difference': '当前保存记录尚不能证明它是其他论文之外新增的独立病例。'})
        full_read = result.get('readingLoop', {}).get('fullPaperRead', paper.get('observations', {}).get('allChunksProcessed'))
        coverage_text = ('保存记录标记本次所供文本范围已读完；新增附件或新问题仍须按范围核查。'
                         if full_read is True else '全文尚未全部核查；本次相关片段的处理进度不代表资料完整。')
        doc['coverage'] = {'text': coverage_text,
                           'modelCalls': result.get('modelCalls'),
                           'cachedExtractionChunks': len(paper.get('cacheReuse', {}).get('extractionChunks', [])),
                           'cachedReviewChunks': len(paper.get('cacheReuse', {}).get('reviewChunks', []))}
        doc['limits'].append('纯合观察按其适用规则处理；本卡不额外制造必须补伙伴分类或反式证据的要求。')
        out.append(doc)
    return out


def render_html(report, template):
    marker = '__LITERATURE_READABLE_DATA__'
    if template.count(marker) != 1:
        raise ValueError('阅读模板占位符无效。')
    payload = json.dumps(report, ensure_ascii=False, separators=(',', ':'), allow_nan=False)
    # A JSON string must never close the surrounding script element.
    payload = payload.replace('&', '\\u0026').replace('<', '\\u003c').replace('>', '\\u003e')
    payload = payload.replace('\u2028', '\\u2028').replace('\u2029', '\\u2029')
    return template.replace(marker, payload)


def csv_cell(value):
    value = str(value)
    return "'" + value if value.lstrip().startswith(('=', '+', '-', '@')) else value


def export(report, output):
    output = Path(output).resolve()
    if output.exists() and any(output.iterdir()):
        raise ValueError('请指定新的空输出目录，避免覆盖已有资料。')
    output.mkdir(parents=True, exist_ok=True)
    template = Path(__file__).with_name('literature_readable_template.html').read_text(encoding='utf-8')
    (output / 'literature-core-fields.html').write_text(render_html(report, template), encoding='utf-8')
    (output / 'literature-core-fields.view.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    with (output / 'literature-core-fields.csv').open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(['资料', '基因', '目标变异', '对象', '核心信息', '已提取内容', '核查情况', '说明', '原文位置'])
        for doc in report['documents']:
            for case in doc['cases']:
                for row in case['rows']:
                    writer.writerow([csv_cell(x) for x in [doc['pmcid'], doc['gene'], '；'.join(doc['targetVariants']),
                        case['label'], row['label'], row['value'], row['statusText'], row['note'],
                        '；'.join(unique(ref['unitId'] for ref in row['sourceRefs']))]])
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pp1-artifact', type=Path, required=True)
    parser.add_argument('--pp1-result', type=Path, required=True)
    parser.add_argument('--pm3-result', type=Path, required=True)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    inputs = [args.pp1_artifact, args.pp1_result, args.pm3_result]
    before = {str(p.resolve()): sha(p.read_bytes()) for p in inputs}
    report = {'schemaVersion': 'literature-readable-view-v1',
              'generatedAt': datetime.now(timezone.utc).isoformat(),
              'documents': [pp1_document(args.pp1_artifact, args.pp1_result, args.library)]
                           + pm3_documents(args.pm3_result, args.library)}
    output = export(report, args.output)
    if before != {str(p.resolve()): sha(p.read_bytes()) for p in inputs}:
        raise ValueError('源文件在导出期间发生变化，请重新核查。')
    receipt = {'kind': 'READ_ONLY_DISPLAY_PROJECTION', 'sourcesUnchanged': True, 'inputSha256': before,
               'modelCalls': 0, 'scientificReevaluation': False,
               'outputs': {p.name: sha(p.read_bytes()) for p in output.iterdir() if p.is_file()}}
    (output / 'provenance.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'output': str(output), 'documents': len(report['documents']), 'sourcesUnchanged': True}, ensure_ascii=False))


if __name__ == '__main__':
    main()
