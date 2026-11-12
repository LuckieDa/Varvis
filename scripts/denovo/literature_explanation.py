"""Source-backed explanation projection; never extracts, qualifies or scores.

The public citation and grouped requirement format is reusable by literature
criteria. The de novo adapter below describes existing checks, not new gates.
Only already validated package inputs are accepted by the production caller.
"""
import re
from pathlib import Path
from model_literature_contract import canonical, digest, parse
from core_material_contract import bounded, facts
from prepare_literature import prepare

VERSION = 'literature-explanation-v1'


class PublicSources:
    def __init__(self, doc, directory):
        self.doc = doc
        self.units = {}
        self.unit_list = []
        self.main = None
        self.title = ''
        self.pmcid = doc['caseKey'].split('#', 1)[0]
        self.url = 'https://pmc.ncbi.nlm.nih.gov/articles/' + self.pmcid + '/'
        root = Path(directory).resolve(strict=True)
        for key in ('paper', 'units'):
            spec = doc['sources'].get(key)
            if not spec:
                continue
            path = (root / spec['path']).resolve(strict=True)
            if not path.is_relative_to(root):
                raise ValueError('LITERATURE_EXPLANATION_SOURCE_PATH')
            raw = bounded(path)
            if digest(raw) != spec['sha256']:
                raise ValueError('LITERATURE_EXPLANATION_SOURCE_PIN')
            if key == 'paper':
                self.main = prepare(raw, self.pmcid, spec['sha256'])
                self.title = self.main['title']
                self.units.update({u['unitId']: {**u, 'sourceSha256': spec['sha256']} for u in self.main['units']})
            else:
                self.unit_list = parse(raw)
                self.units.update({u['unitId']: {**u['metadata'], **{k: v for k, v in u.items() if k != 'metadata'}} for u in self.unit_list})

    def native(self, ref, scope_only=False):
        uid, pin = ref.get('unitId'), ref.get('sourceSha256')
        unit = self.units.get(uid)
        quote = '' if scope_only else ref.get('quote', '')
        if not unit or unit['sourceSha256'] != pin or (not scope_only and (not quote or quote not in unit['text'])):
            raise ValueError('LITERATURE_EXPLANATION_CITATION_MISMATCH')
        attachment = uid.startswith('attachment:')
        label = ' / '.join(unit.get('sections', [])) or ('附件' if attachment else '正文或表格')
        name = unit.get('originalName', '')
        # Names and structural positions come from parsed originals. No guessed pages.
        if attachment:
            position = unit.get('sourcePath', '')
            paragraph = re.fullmatch(r'/document/body/p\[(\d+)\]', position)
            cell = re.search(r'/tbl\[(\d+)\]/tr\[(\d+)\]/tc\[(\d+)\]', position)
            readable = ('正文结构第' + paragraph[1] + '段（非页码）' if paragraph else
                        '表%s，第%s行，第%s个单元格（原始结构）' % tuple(cell.groups()) if cell else unit.get('location', uid))
            label = name + ' · ' + readable
        else:
            table = re.search(r'/table-wrap\[(\d+)\].*?/tr\[(\d+)\]/(?:td|th)\[(\d+)\]', uid)
            paragraph = re.search(r'/p\[(\d+)\]$', uid)
            if table:
                label += ' · XML表序号%s，%s内第%s行，第%s个单元格' % (table[1], '表头' if '/thead[' in uid else '表体', table[2], table[3])
            elif paragraph:
                label += ' · 所在章节第' + paragraph[1] + '段'
        return {'sourceSha256': pin, 'role': 'PAPER_ATTACHMENT' if attachment else 'PAPER_PRIMARY',
                'locator': uid, 'kind': 'SOURCE_UNIT', 'quote': quote, 'paperId': self.pmcid,
                'title': self.title, 'locationLabel': label, 'url': self.url,
                'linkScope': 'ARTICLE_LANDING_PAGE', 'scopeOnly': scope_only}

    def citation(self, ref, scope_only=False):
        source = self.doc['sources'][ref['sourceId']]
        if ref['sourceId'] == 'paper' and ref['kind'] == 'JATS_UNIT':
            return self.native({'unitId': ref['locator'], 'sourceSha256': source['sha256'], 'quote': ref.get('quote', '')}, scope_only)
        match = re.fullmatch(r'/(\d+)/text', ref['locator']) if ref['sourceId'] == 'units' else None
        if match:
            unit = self.unit_list[int(match[1])]
            return self.native({**unit, 'quote': ref.get('quote', '')}, scope_only)
        return None  # Internal policies/identity proofs must not become public-paper quotes.


def unique(items):
    return list({canonical(item): item for item in items}.values())


# Explanations of the existing material contract. These do not select or score rules.
REQUIREMENTS = {
    'family': ('家系与新发遗传相容', '需要对应当前病例的家系相容依据；已核显性背景可使用明确双亲未患病或相关阴性家族史。',
               '父母未检出目标变异只说明检测结果，不能据此推定双亲没有相关临床表现。',
               '补充能对应此病例的双亲相关临床表现或家系资料；未归属的队列病史不能代填。'),
    'testing': ('目标变异检测', '需要可定位到先证者及双亲、针对同一目标变异的明确检测结果，并核对检测限制。',
                '不强制写出Sanger等方法名；仅称新发或父母未携带不能替代明确的检测依据，亲缘检测也不能替代目标变异检测。',
                '核查缺少的检测对象、目标位点和实际检测结果；已报告的方法与质量限制保留，不代填未知方法。'),
    'parentage': ('亲子关系与适用路径', '需区分亲缘已确认、未确认及排除；未确认亲缘可按适用政策进入PM6，不能冒称PS2。',
                 '未确认亲缘本身不等于PM6不满足；排除或不明确的亲缘需单独核查。', '核对原文亲缘方法、结果及政策是否允许未确认亲缘。'),
    'phenotype': ('临床表现与疾病对应', '需要当前病例明确表型以及适用的基因—疾病表型政策。',
                  '不能把作者的变异分类当作独立临床表现或本系统评分。', '补充病例表型或可核实的疾病适用政策。'),
    'identity': ('病例与变异身份', '需要准确对应论文病例、基因、变异、参考版本及本次所选目标。',
                 '不能把其他病例或相似变异的资料转移到当前目标。', '补充或核对缺少的病例、转录本、等位基因和参考身份。'),
    'event': ('病例来源与独立事件', '需要明确作者原始病例及事件身份，排除重复报道或同一事件重复计分。',
              '同一病例被多篇文章引用不构成多个独立发生事件。', '核对原始病例来源及重复关系；保留已经核实的事实。'),
    'context': ('判定背景与适用政策', '需要与当前基因、疾病、遗传方式和变异范围相容的规则政策。',
                '病例事实已取得不等于疾病政策已经具备；该缺口不能归因于论文未记载。', '补充或核实相容政策；不通过模型生成科学批准。'),
    'supplement': ('条件触发的补充资料', '按已触发的特殊遗传或专家条件提供相应补充事实。',
                   '普通病例条件不能替代嵌合、相位或其他特殊模型的要求。', '核对具体触发条件，补充对应资料或明确程序支持范围。'),
}


def group_for(path):
    if path in ('/groups/family/compatibility', '/conditions/specialFamilyModel') or path.startswith('/groups/family/'):
        return 'family'
    if any(path.startswith('/groups/' + p + '/') for p in ('testing', 'mother', 'father', 'proband')):
        return 'testing'
    if path.startswith('/groups/parentage/'):
        return 'parentage'
    if path.startswith('/groups/phenotype/') or path == '/context/phenotypeCategory':
        return 'phenotype'
    if path.startswith('/groups/overlap/') or path == '/groups/case/origin':
        return 'event'
    if path.startswith('/groups/'):
        return 'identity'
    return 'context' if path.startswith('/context/') else 'supplement'


CATEGORIES = {
    'DATA_MISSING': ('缺少可核实的必要资料', '现有已核资料不足以确认该条件，不据此认定条件不成立。'),
    'EXTRACTION_OR_REVIEW_INCOMPLETE': ('提取或核验尚未完成', '不能据此断言论文没有记载；需核查已定位原文或补足读取能力。'),
    'SOURCE_UNAVAILABLE': ('原始资料尚不可用', '尚未取得或读通必要来源，不能推定该来源没有记录。'),
    'KNOWLEDGE_MISSING': ('缺少相容的判定知识', '这是规则知识供给缺口，不是已证明论文遗漏病例资料。'),
    'CONFLICT': ('资料存在冲突', '冲突未解决前不能采用该项事实；不能选择有利陈述覆盖冲突。'),
    'SOFTWARE_UNSUPPORTED': ('程序尚不支持此项核查', '已知程序范围不足不能包装成论文缺资料。'),
    'RULE_NOT_APPLICABLE': ('当前条件不适用', '适用性检查未通过；不等同于变异良性或致病。'),
    'NOT_APPLICABLE': ('当前条件不适用', '适用性检查未通过；不等同于变异良性或致病。'),
    'EXECUTION_FAILURE': ('执行失败', '执行失败没有产生可用判断，不能按阴性结果处理。'),
    'REVIEW_REQUIRED': ('资料仍待核实', '已有候选或不明确陈述不能直接作为已核事实使用。'),
}


def build_explanation(doc, fields, blockers, report, sources):
    """Versioned public explanation; field references keep facts, not duplicated scores."""
    groups = {}
    for b in blockers:
        family = group_for(b['path'])
        causes = sorted(set(b.get('causeCodes') or [b['code']]))
        # Same cause + same requirement only; do not merge unrelated conflicts.
        key = (family, b['category'], tuple(causes))
        item = groups.setdefault(key, {'id': 'requirement-' + str(len(groups) + 1), 'fieldPaths': [],
            'category': b['category'], 'reasonCodes': causes, 'checkedScope': []})
        item['fieldPaths'].append(b['path'])
    family_report = report.get('familyReview', {})
    for (group, category, _), item in groups.items():
        label, requirement, distinction, next_step = REQUIREMENTS[group]
        state, reason = CATEGORIES.get(category, CATEGORIES['REVIEW_REQUIRED'])
        item.update(label=label, requirement=requirement, finding=state, explanation=reason,
                    distinction=distinction, nextStep=next_step,
                    effect='此项尚未通过资料资格核验，不能直接采用该病例贡献证据；不把它改称已评价不满足。',
                    observedFieldPaths=[f['path'] for f in fields if f['verification'] == 'VERIFIED' and f['availability'] == 'REPORTED'
                        and (group_for(f['path']) == group or (group == 'family' and group_for(f['path']) in ('testing', 'parentage')))])
        for path in item['fieldPaths']:
            for ref in facts(doc).get(path, {}).get('checkedLocations', []):
                resolved = sources.citation(ref, True)
                if resolved:
                    item['checkedScope'].append(resolved)
        if group == 'family':
            for uid in family_report.get('checkedUnitIds', []):
                unit = sources.units[uid]
                item['checkedScope'].append(sources.native({**unit, 'unitId': uid}, True))
            item['scopeStatement'] = '自动家系核查限已记录的正文结果/病例段落；附件读取和字面事实核实不等于全部附件家系语义已核完。'
        else:
            item['scopeStatement'] = '仅限下列已记录检查位置和逐字段引用；位置未登记不代表完成了整篇检索。'
        item['checkedScope'] = unique(item['checkedScope'])
        if any(b['path'] in item['fieldPaths'] and b['meaning'] == 'DECLARED_SOURCE_SCOPE_ONLY' for b in blockers):
            item['explanation'] += '存在仅限已检查位置的未报告记录；不能外推到整篇论文。'
    warnings = []
    for ref in family_report.get('unassignedHistory', []):
        warnings.append({'text': '队列相关病史未明确归属当前病例；保留提示，不自动代填为当前病例异常或正常。',
                         'source': sources.native(ref)})
    return {'schemaVersion': VERSION, 'caseKey': doc['caseKey'],
            'paper': {'paperId': sources.pmcid, 'title': sources.title, 'url': sources.url},
            'summary': '资料已具备进入原规则核验的条件；是否采用和证据强度以已保存的实际规则结果为准。' if not blockers else
                       '当前病例资料仍有以下未通过的必要条件；这不等于已经完成规则评价且不满足。',
            'items': list(groups.values()), 'warnings': warnings,
            'scopeStatement': '仅说明已核实资料和记录的检查范围；不宣称已完整检索所有文献、正文及附件。'}


def attach_explanation(view, doc, report, directory):
    sources = PublicSources(doc, directory)
    for field in view['fields']:
        field['sourceEvidence'] = unique([r for ref in facts(doc)[field['path']]['citations']
                                          if (r := sources.citation(ref)) is not None])
        if field['path'] in ('/groups/family/compatibility', '/conditions/specialFamilyModel') and field['verification'] == 'VERIFIED':
            family = report.get('familyReview', {})
            refs = [p['citation'] for p in family.get('explicitHistoryProofs', []) + family.get('unaffectedParentProofs', [])]
            field['sourceEvidence'] = unique(field['sourceEvidence'] + [sources.native(r) for r in refs])
    view['explanation'] = build_explanation(doc, view['fields'], view['blockers'], report, sources)
    return view
