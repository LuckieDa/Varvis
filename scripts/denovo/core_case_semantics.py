"""Case-owned literal clinical facts for context qualification, without model votes.

This supplements, rather than rewrites, the saved extraction/source review. It
does not resolve HPO, disease policy, target assays, parentage or family safety.
Unrecognized prose is unresolved, not absent. No gene/paper/phenotype allowlist.
"""
from copy import deepcopy
import re
import xml.etree.ElementTree as ET

from core_material_contract import pointer, digest as object_digest
from model_literature_contract import canonical
from project_core_material import observed
from literature_case_structure import paragraph_owned, MENTION
from qualify_literature_cases import CASE_TITLE, sentences
from prepare_literature import text

VERSION = 'denovo-case-semantics-1.0.0'
# These words require a richer clause/actor interpretation; never strip them to
# manufacture an affirmative finding. Normal examinations are not negative HPOs.
UNSAFE = re.compile(r'\b(?:no|not|never|without|normal|absent|absence|lack|lacking|free|neither|'
                    r'negative|denied|unremarkable|unlikely|excluded|resolved|may|might|could|'
                    r'possible|possibly|possibility|suspected|suggestive|suggestion|uncertain|if|would|previously|'
                    r'mother|father|parents?|parental|maternal|paternal|siblings?|brother|sister|family|families)\b', re.I)


def owned_paragraphs(document, label):
    sections = set()
    for u in document['units']:
        title = CASE_TITLE.fullmatch(u['sections'][-1]) if u['sections'] else None
        if (u['kind'] == 'BODY_PARAGRAPH' and title and title[1] == label and any(
                s.lower() in {'results', 'case reports', 'case report', 'clinical report'} for s in u['sections'][:-1])):
            sections.add(u['unitId'].rsplit('/p[', 1)[0])
    result = []
    for u in document['units']:
        if u['kind'] != 'BODY_PARAGRAPH':
            continue
        in_section = len(sections) == 1 and u['unitId'].rsplit('/p[', 1)[0] in sections
        if (in_section or paragraph_owned(document, u, label)) and all(m[1] == label for m in MENTION.finditer(u['text'])):
            result.append(u)
    return result


def citation(unit, quote=None):
    return {'sourceId': 'paper', 'kind': 'JATS_UNIT', 'locator': unit['unitId'],
            'quote': unit['text'] if quote is None else quote}


def clinical_clauses(unit, label):
    subject = r'(?:[Tt]he (?:proband|patient|child)|(?:Case|Patient|Individual|Proband) ' + re.escape(label) + r')'
    actor_is_proband = True
    for _, _, sentence in sentences(unit['text']):
        # A semicolon terminates the assertion. Its other clause retains its own
        # polarity and is never silently interpreted as a finding.
        clause = sentence.split(';', 1)[0].rstrip('.')
        clause = re.sub(r'^(?:In addition, |On examination, )', '', clause)
        clause = re.sub(r' \((?:Figure|Fig\.|Supplementary Video) [0-9]+[A-Z]?(?: and [A-Z])?\)$', '', clause)
        if re.match(subject + r' (?:had|is|was|exhibited|presented)\b', clause):
            actor_is_proband = True
        elif re.search(r'\b(?:mother|father|parents?|parental|maternal|paternal|siblings?|brother|sister)\b', sentence, re.I):
            actor_is_proband = False
        if not actor_is_proband:
            continue
        negatives = [subject + r' (?:had no|did not have) (?P<value>[A-Za-z][A-Za-z -]+)',
                     r'No (?P<value>[A-Za-z][A-Za-z -]+) was (?:noted|observed)(?: in (?:all |both )?(?:four |two )?(?:limbs|arms|legs|hands|feet))?',
                     r'(?P<value>[A-Za-z][A-Za-z -]+) was absent']
        denied = next((m for pattern in negatives if (m := re.fullmatch(pattern, clause))), None)
        if denied and not UNSAFE.search(denied['value']):
            yield 'negative', denied['value'].strip(), citation(unit, sentence)
            continue
        if UNSAFE.search(clause):
            continue
        patterns = [
            subject + r' (?:had|exhibited|presented with) (?P<value>[^.!?]+)',
            r'(?P<value>[A-Za-z][A-Za-z -]+) was noted in (?:all |both )?(?:four |two )?(?:limbs|arms|legs|hands|feet)',
            r'[Tt]here was (?P<value>[^.!?]+)',
            r'MRI of the brain revealed (?P<value>[^.!?]+)',
            r'The X-ray of (?:both |the )?[A-Za-z -]+ showed (?P<value>[^.!?]+)',
            r'Follow[- ]up at [0-9]+ (?:years?|months?|days?) revealed (?P<value>[^.!?]+)',
        ]
        for pattern in patterns:
            match = re.fullmatch(pattern, clause)
            if match:
                value = match['value'].strip()
                # Do not turn an embedded clinical event into general disease
                # onset. Keep event/timing wording together in the literal value.
                if ' and was noted to have ' in value:
                    value = value.split(' and was noted to have ', 1)[0]
                yield 'positive', value, citation(unit, sentence)
                # Retain the full assertion alongside explicit conjuncts. Only
                # severity/location modifiers are removed, never negation or
                # uncertainty (which already vetoed the complete clause).
                terms = re.split(r', | and | with ', value) if pattern.startswith((r'The X-ray', r'Follow')) else []
                for term in terms:
                    term = re.sub(r'^(?:(?:persisting|severe|mild|moderate|bilateral|superior|proximal) )+', '', term)
                    if term != value and term:
                        yield 'positive', term, citation(unit, sentence)
                break


def family_panels(source, owned, label):
    """Exact JATS rid + case's opening figure panel + pedigree caption only."""
    nodes = {}; ids = {}

    def visit(node, path):
        nodes[path] = node
        if node.get('id'):
            ids.setdefault(node.get('id'), []).append((path, node))
        counts = {}
        for child in node:
            counts[child.tag] = counts.get(child.tag, 0) + 1
            visit(child, path + '/' + child.tag + '[' + str(counts[child.tag]) + ']')

    visit(ET.fromstring(source['raw']), '/article[1]')  # source already bounded/prepared
    units = {u['unitId']: u for u in source['main']['units']}
    result = []
    for unit in owned:
        # Restrict the link to the proband's opening identification, not a later
        # image of their mother or an unrelated comparison in the same paragraph.
        opening = re.match(r'^(?:Case|Patient|Individual|Proband) ' + re.escape(label)
                           + r' (?:is|was) (?:a |an )?[A-Za-z0-9 -]{1,80} \((?P<figure>(?:Figure|Fig\.) [0-9]+)(?P<panel>[A-Z])\)', unit['text'])
        if not opening or UNSAFE.search(opening[0]):
            continue
        paragraph = nodes[unit['unitId']]
        links = [x for x in paragraph.iter('xref') if x.get('ref-type') == 'fig' and
                 re.match(re.escape(opening['figure'] + opening['panel']) + r'\)', text(x) + (x.tail or ''))]
        if len(links) != 1 or len(ids.get(links[0].get('rid'), [])) != 1:
            continue
        path, fig = ids[links[0].get('rid')][0]
        caption = units.get(path + '/caption[1]')
        if fig.tag != 'fig' or caption is None or len(fig.findall('caption')) != 1:
            continue
        pattern = re.compile(r'(?:^|(?<=\. ))' + opening['panel'] + r': Pedigree of (?P<family>Family [A-Za-z0-9_-]+)\.')
        matches = list(pattern.finditer(caption['text']))
        if len(matches) == 1:
            result.append((matches[0]['family'], [citation(unit, opening[0]), citation(caption, matches[0][0])]))
    return result


def enrich(source, doc, ledger):
    """Merge source-proven additions; keep raw candidates and conflicting facts."""
    label = doc['caseKey'].split('#', 1)[1]
    owned = owned_paragraphs(source['main'], label)
    rows = []
    for unit in owned:
        for polarity, value, ref in clinical_clauses(unit, label):
            rows.append(('/groups/phenotype/' + polarity, [value], 'PROBAND', [ref], 'OWNED_EXPLICIT_CLINICAL_CLAUSE_' + polarity.upper()))
    for value, refs in family_panels(source, owned, label):
        rows.append(('/groups/case/familyId', value, 'FAMILY', refs, 'JATS_CASE_XREF_EXACT_PEDIGREE_PANEL'))
    # Family history/compatibility stay with the original qualification reader.
    # A literal prose phrase must not compete with a normalized native-table
    # value (e.g. "None") as if different spellings were a factual conflict.
    units = {u['unitId']: u for u in source['main']['units']}
    checked = {r['locator'] for row in rows for r in row[3]}
    report = {'version': VERSION, 'changedPaths': [], 'proofs': [], 'issues': [],
              'checkedUnitIds': sorted(checked), 'meaning': 'REPORTED_FACTS_NOT_DISEASE_OR_ASSAY_QUALIFICATION'}
    if len(checked) > 96 or sum(len(units[u]['text'].encode('utf-8')) for u in checked) > 65536:
        report['issues'].append('CASE_SEMANTIC_SCOPE_LIMIT'); return report
    for path in dict.fromkeys(row[0] for row in rows):
        selected = [r for r in rows if r[0] == path]; fact = pointer(doc, path)
        if fact['verification'] == 'CONFLICT':
            report['issues'].append('EXISTING_FACT_CONFLICT:' + path); continue
        refs = [c for row in selected for c in row[3]]
        for c in refs:
            if c['locator'] not in units or not c['quote'] or c['quote'] not in units[c['locator']]['text']:
                raise ValueError('DENOVO_CASE_SEMANTIC_SOURCE_MISMATCH')
        values = [r[1] for r in selected]
        if fact['verification'] == 'VERIFIED':
            values.insert(0, deepcopy(fact['value'])); refs = deepcopy(fact['citations']) + refs
        if all(isinstance(v, list) for v in values):
            value = list(dict.fromkeys(s for v in values for s in v)); conflict = False
        else:
            values = list({canonical(v): v for v in values}.values())
            value = values[0] if len(values) == 1 else None; conflict = len(values) != 1
        if fact['verification'] == 'VERIFIED' and value == fact['value']:
            continue
        refs = list({canonical(r): r for r in refs}.values())
        previous = deepcopy(fact)
        replacement = observed(doc['caseKey'], selected[0][2], value, canonical(values).decode(), refs, 'PROGRAM_DERIVED')
        replacement['verification'] = 'CONFLICT' if conflict else 'VERIFIED'
        replacement['checkedLocations'] = [{k: v for k, v in c.items() if k != 'quote'} for c in refs]
        fact.clear(); fact.update(replacement)
        if conflict:
            fact['issues'] = ['CASE_SEMANTIC_SOURCE_CONFLICT']; ledger['checks'].pop(path, None)
            doc['reviewPlan']['unresolvedDisagreements'].append(path)
        else:
            previous_methods = ledger['checks'].get(path, {}).get('method', [])
            if isinstance(previous_methods, str):
                previous_methods = [previous_methods]
            ledger['checks'][path] = {'factSha256': object_digest(fact), 'method': sorted(set(previous_methods) | {r[4] for r in selected}),
                                     'basis': refs, 'authority': 'PROGRAM_SOURCE_FACT_CHECK_ONLY'}
        report['proofs'].append({'path': path, 'before': previous, 'after': deepcopy(fact), 'methods': [r[4] for r in selected]})
        report['changedPaths'].append(path)
    # Preserve a pre-existing explicit negative's veto on newly found positives.
    positive, negative = (doc['groups']['phenotype'][k] for k in ('positive', 'negative'))
    if positive['verification'] == negative['verification'] == 'VERIFIED':
        normalize = lambda s: re.sub(r'\s+', ' ', s).strip().casefold()
        if any(re.search(r'(?<!\w)' + re.escape(normalize(n)) + r'(?!\w)', normalize(p))
               for n in negative['value'] for p in positive['value']):
            for name in ('positive', 'negative'):
                path = '/groups/phenotype/' + name; fact = pointer(doc, path)
                fact['verification'] = 'CONFLICT'; fact['issues'].append('POSITIVE_NEGATIVE_SOURCE_CLAIMS_REQUIRE_CONTEXT_RECONCILIATION')
                ledger['checks'].pop(path, None); doc['reviewPlan']['unresolvedDisagreements'].append(path)
            report['issues'].append('POSITIVE_NEGATIVE_SOURCE_CONFLICT')
    doc['reviewPlan']['checkedScope'] = list(dict.fromkeys(doc['reviewPlan']['checkedScope'] + ['paper:' + u for u in sorted(checked)]))
    doc['reviewPlan']['unresolvedDisagreements'] = sorted(set(doc['reviewPlan']['unresolvedDisagreements']))
    confirmed = {p for p in report['changedPaths'] if pointer(doc, p)['verification'] == 'VERIFIED'}
    doc['reviewPlan']['unprocessedScope'] = [p for p in doc['reviewPlan']['unprocessedScope'] if p not in confirmed]
    for p in report['proofs']:
        p['after'] = deepcopy(pointer(doc, p['path']))
    return report
