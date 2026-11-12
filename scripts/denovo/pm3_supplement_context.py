"""Explicit JATS supplement links and source cohort context, not model adoption.

The supported path is a current-study complete-genotype appendix, a uniquely
named gene-deficient cohort, and a uniquely named disease cohort. Anonymous row
locators do not establish independent patients, phase or a transcript. Every
derived fact retains the source chain; ambiguous/mixed cohorts fail closed.
"""
import re
from copy import deepcopy
from pathlib import PurePosixPath
import xml.etree.ElementTree as ET
from bundle_model_contract import cite

UNSAFE = re.compile(r'\b(?:healthy controls?|unaffected controls?|control group|mixed cohort|second cohort|another cohort)\b', re.I)


def context(source, scope):
    result = {'facts': [], 'bindings': [], 'primarySourceVerified': False,
              'reasonCode': 'PM3_SUPPLEMENT_COHORT_LINK_UNRESOLVED'}
    owned = [v for v in scope.values() if v.get('method') == 'ANONYMOUS_SOURCE_GENOTYPE_ROW']
    if len(owned) != 1 or not source.get('raw'): return result
    owner = owned[0]
    units = {u['unitId']: u for u in source['units']}
    anchor = units.get(owner['anchorUnitId'])
    if not anchor or not anchor.get('originalName'): return result
    root = ET.fromstring(source['raw'])
    paragraphs = [u for u in source['main']['units'] if u['kind'] == 'BODY_PARAGRAPH']
    if any(UNSAFE.search(u['text']) for u in paragraphs): return result
    supplements = []
    for node in root.iter('supplementary-material'):
        files = [PurePosixPath(m.get('{http://www.w3.org/1999/xlink}href', '')).name
                 for m in node.iter('media')]
        if node.get('id') and files == [anchor['originalName']]: supplements.append(node)
    if len(supplements) != 1: return result
    sid = supplements[0].get('id')
    if sum(n.get('id') == sid for n in root.iter()) != 1: return result
    links = []
    for unit in paragraphs:
        node = root.find('./' + unit['unitId'].removeprefix('/article[1]/'))
        if node is None: continue
        linked = any(x.get('ref-type') == 'supplementary-material' and x.get('rid') == sid for x in node.iter('xref'))
        complete = re.search(r'\b(?:overview|summary|list) of all [0-9]+ complete genotypes\b', unit['text'], re.I)
        if linked and complete and not re.search(r'\b(?:previous|published|literature|excluding|except|not available)\b', unit['text'], re.I):
            links.append(unit)
    if len(links) != 1: return result
    link = links[0]
    # A gene named only for a few novel variants cannot scope the whole appendix.
    # Require a separate explicit, current-study gene-deficient patient cohort.
    genes = []
    for u in paragraphs:
        if not re.search(r'\bwe (?:investigated|studied|evaluated)\b', u['text'], re.I): continue
        if re.search(r'\b(?:previously|published|not|suspected|possible|controls)\b', u['text'], re.I): continue
        for m in re.finditer(r'\bcohort of (?:[A-Z][a-z]+ )?([A-Z][A-Z0-9-]{1,39})-deficient patients\b', u['text']):
            genes.append((m[1], u))
    if len(genes) != 1: return result
    gene, gene_unit = genes[0]
    if not re.search(r'\b' + re.escape(gene) + r' gene\b', link['text']): return result
    citations = [cite(source, {'unitId': u['unitId'], 'quote': u['text']}) for u in (link, gene_unit, anchor)]
    binding = {'method': 'EXPLICIT_JATS_COMPLETE_GENOTYPE_SUPPLEMENT', 'supplementXmlId': sid,
               'originalName': anchor['originalName'], 'sourceSha256': anchor['sourceSha256'],
               'container': owner['container'], 'citations': citations, 'independenceEstablished': False}
    result['bindings'].append(binding)

    def fact(name, value, unit, quote=None, more=()):
        result['facts'].append({'field': name, 'sourceValue': value, 'subject': 'PROBAND', 'issues': [],
            'producer': 'SOURCE_NATIVE_SUPPLEMENT_CONTEXT', 'status': 'CASE_OWNED_SEMANTICS_PENDING',
            'citation': cite(source, {'unitId': unit['unitId'], 'quote': quote or unit['text']}),
            'supportingCitations': deepcopy(citations + list(more)),
            'ownership': {'method': binding['method'], 'container': owner['container'],
                          'anchorUnitId': anchor['unitId'], 'binding': deepcopy(binding)}})
    fact('geneSymbol', gene, gene_unit)
    # Disease abbreviation expansion must be explicit in this paper. A gene
    # symbol/target disease or treatment response does not supply a diagnosis.
    diseases = []
    for u in paragraphs:
        m = re.match(r'This study, in a (?:large )?cohort of ([A-Z][A-Z0-9-]{1,20}) patients,', u['text'])
        if m: diseases.append((m[1], u))
    if len(diseases) == 1:
        abbr, disease_unit = diseases[0]
        expansions = []
        for u in paragraphs:
            if not any(s.casefold() == 'abbreviations' for s in u.get('sections', [])): continue
            for m in re.finditer(r'(?:^|; )' + re.escape(abbr) + r': ([A-Za-z][A-Za-z -]{1,120})(?=;|\.$|$)', u['text']):
                expansions.append((m[1], u, m[0].removeprefix('; ')))
        if len(expansions) == 1:
            diagnosis, expansion_unit, quote = expansions[0]
            more = [cite(source, {'unitId': disease_unit['unitId'], 'quote': disease_unit['text']})]
            fact('diagnosis', diagnosis, expansion_unit, quote, more)
            fact('affectedStatus', 'affected', disease_unit, more=more)
    origins = []
    from qualify_literature_cases import sentences
    for u in paragraphs:
        if not any(s.casefold() == 'methods' for s in u.get('sections', [])): continue
        for unused_start, unused_end, sentence in sentences(u['text']):
            if (re.search(r'\bdata were collected from [0-9]+ patients who participated in\b', sentence)
                    and re.search(r'\b(?:medical centres|medical centers|hospitals)\b', sentence)
                    and not re.search(r'\b(?:literature|published|pooled|database|previously|not|controls)\b', sentence, re.I)):
                origins.append((u, sentence))
    if len(origins) == 1:
        result['primarySourceVerified'] = True
        result['primarySourceCitations'] = citations + [cite(source, {'unitId': origins[0][0]['unitId'], 'quote': origins[0][1]})]
    result['reasonCode'] = 'PM3_SUPPLEMENT_SOURCE_CONTEXT_BOUND'
    return result


def accepts(fact):
    own = fact.get('ownership') or {}
    return (fact.get('producer') == 'SOURCE_NATIVE_SUPPLEMENT_CONTEXT'
            and own.get('method') == 'EXPLICIT_JATS_COMPLETE_GENOTYPE_SUPPLEMENT'
            and not fact.get('issues') and fact.get('subject') == 'PROBAND'
            and len(own.get('binding', {}).get('citations', [])) == 3
            and fact.get('field') in ('geneSymbol', 'diagnosis', 'affectedStatus'))
