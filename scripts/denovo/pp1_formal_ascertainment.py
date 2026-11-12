"""Bounded, source-bound review of the formal PP1 selection mechanism.

An audited excerpt can propose a sufficient condition; it cannot hide another
sentence about sampling, genotyping or reporting. All supplied source units are
therefore checked, including units outside the model input. This is a finite
English extractor, not unrestricted scientific or clinical adoption. Ambiguous
scope, unsupported mechanism prose and exceeded budgets remain UNKNOWN.
"""
from copy import deepcopy
import re

from model_literature_contract import canonical, digest
from pp1_literature_contract import require

VERSION = 'pp1-formal-ascertainment-review-v1'
PHENOTYPE_FIRST = 'PHENOTYPE_BEFORE_GENOTYPING'
CONDITIONAL_INDEPENDENCE = 'SELECTION_AND_REPORTING_INDEPENDENT_OF_TARGET_GIVEN_PHENOTYPE'
MAX_UNITS = 5000
MAX_CHARACTERS = 2000000
UNSUPPORTED = 'PP1_FORMAL_ASCERTAINMENT_UNVERIFIED_EXTRACTOR_UNSUPPORTED'
_LOCATION_FIELDS = ('kind', 'sections', 'page', 'bbox', 'sourcePart', 'sourcePath',
                    'originalName', 'sheet', 'cell', 'archiveMemberName', 'archiveMemberSha256')
_ACTIONS = r'(?:ascertain\w*|recruit\w*|enrol\w*|select\w*|sampl\w*|includ\w*|inclusion|exclud\w*|report\w*|segregation analys\w*)'
_G = r'(?:variant[- ]positive|variant[- ]negative|non[- ]?carriers?|carriers?|genotyp\w*|mutation[- ]positive|mutation[- ]negative|target variant|test results|molecular results|mutation status|variant status|genetic status)'
_PEOPLE = r'(?:relatives|family members|members|individuals|patients|participants|cousins|siblings|parents|children)'
_REPRESENTATION = r'(?:presented|described|retained|shown|represented|depicted|displayed|analysed|analyzed|sequenced|tested|included|excluded|omitted|reported|entered|eligible|available|appeared)'
_POSITIVE = r'(?:variant[- ]positive|mutation[- ]positive|mutation[- ]bearing|variant[- ]bearing|carrier)'
_NEGATIVE = r'(?:variant[- ]negative|mutation[- ]negative|non[- ]?carrier)'
_UNCERTAIN = re.compile(r'\b(?:may|might|could|possibly|possible|whether|unclear|uncertain|unknown|hypothetical|suspected|cannot|not known)\b', re.I)


def _word(value):
    return r'(?<![\w:-])' + re.escape(value) + r'(?![\w:-])'


def _sentences(text):
    """Return exact spans, preserving an outer clause omitted from a citation."""
    start = 0
    for boundary in re.finditer(r'(?<=[.!?;])\s+|\n\s*\n', text):
        end = boundary.start()
        if text[start:end].strip():
            left = start + len(text[start:end]) - len(text[start:end].lstrip())
            yield left, end, text[left:end]
        start = boundary.end()
    if text[start:].strip():
        left = start + len(text[start:]) - len(text[start:].lstrip())
        yield left, len(text), text[left:]


def _ref(source, unit, start, end):
    return {'pmcid': source['pmcid'], 'bundleSha256': source['bundleSha256'],
            'unitId': unit['unitId'], 'sourceSha256': unit.get('sourceSha256'),
            'quote': unit['text'][start:end], 'start': start, 'end': end,
            **{key: deepcopy(unit[key]) for key in _LOCATION_FIELDS if key in unit}}


def _valid_ref(ref, units, reviewed=None):
    unit = units.get(ref.get('unitId'))
    quote = ref.get('quote')
    return bool(unit and isinstance(quote, str) and quote and unit['text'].count(quote) == 1
                and (reviewed is None or unit['unitId'] in reviewed)
                and (not ref.get('sourceSha256') or ref['sourceSha256'] == unit.get('sourceSha256'))
                and ('start' not in ref or ref['start'] == unit['text'].index(quote))
                and ('end' not in ref or ref['end'] == unit['text'].index(quote) + len(quote)))


def _families(text):
    # Literal family IDs only; ordinary words after "families" are not IDs.
    out = set()
    identifier = r'(?-i:[A-Z0-9][A-Za-z0-9_-]*)'
    for match in re.finditer(rf'\b(?:family|pedigree|families|pedigrees)\s+({identifier}(?:(?:,\s*(?:and )?| and ){identifier})*)', text, re.I):
        out.update(re.split(r',\s*(?:and )?| and ', match[1]))
    return out


def _disease_scope(text, disease_terms):
    """Only explicit disease-purpose clauses; no inferred disease synonyms."""
    match = re.search(r'\bfor (?:the (?:disease|condition) )?(.+?)(?=\s+(?:before|prior to|after|given|conditional on)\b|[,.;]|$)', text, re.I)
    if not match:
        return 'TARGET_OR_UNSPECIFIED'
    # A purpose on one process cannot scope away a different process joined
    # later in the same sentence. Keep the compound unresolved until its
    # disease/process correspondence is explicitly parsed.
    coordinated = re.search(r'\b(?:and|but|whereas|while|however)\b', text, re.I)
    actions = re.findall(_ACTIONS + '|' + _REPRESENTATION, text, re.I)
    purposes = list(re.finditer(r'\bfor\b', text, re.I))
    if len(purposes) > 1:
        return 'UNRESOLVED_DISEASE'
    purpose = match[1].strip()
    # These are analysis/testing purposes, not another disease.
    if re.search(r'\b(?:segregation|analysis|analyses|genotyping|genetic testing|molecular testing|target variant|target genotype|transfer|implantation)\b', purpose, re.I):
        return 'TARGET_OR_UNSPECIFIED'
    if re.fullmatch(r'(?:the )?carriers(?: but not (?:the )?non[- ]?carriers)?', purpose, re.I):
        return 'TARGET_OR_UNSPECIFIED'
    if coordinated and len(actions) > 1:
        return 'UNRESOLVED_DISEASE'
    if re.fullmatch(r'(?:another|a different|the other) (?:disease|condition)', purpose, re.I):
        return 'OTHER_DISEASE'
    if disease_terms and any(purpose.casefold() == term.casefold() for term in disease_terms):
        return 'TARGET_DISEASE'
    return 'UNRESOLVED_DISEASE'


def _support(text, family, members):
    """Whole assertions only; every accepted grammar names its exact subject."""
    statement = text.strip().rstrip('.!?;')
    for label in ['FAMILY'] + sorted(members):
        subject = (rf'(?:The )?(?:(?:members|individuals) of )?(?:family|pedigree) {_word(family)}'
                   if label == 'FAMILY' else rf'(?:The )?(?:(?:patient|individual|member) )?\(?{_word(label)}\)?')
        basis = r'(?:clinical phenotype|disease phenotype|phenotype|clinical diagnosis|clinical findings|disease status)'
        testing = r'(?:genotyping|genetic testing|molecular testing|testing for the target variant)'
        if re.fullmatch(rf'{subject} (?:was|were) (?:ascertained|recruited|enrolled|selected) (?:on the basis of|based on|by) {basis} (?:before|prior to) {testing}', statement, re.I):
            return label, PHENOTYPE_FIRST
        scope = rf'(?:in|of relatives in) (?:family|pedigree) {_word(family)}'
        if label == 'FAMILY' and re.fullmatch(
                rf'(?:Selection, sampling, genotyping and reporting|Selection and reporting) {scope} '
                rf'(?:were independent of (?:the )?target genotype|did not depend on (?:the )?target genotype) '
                rf'(?:given|conditional on) (?:clinical )?phenotype(?: and (?:clinical )?covariates)?', statement, re.I):
            return label, CONDITIONAL_INDEPENDENCE
    return None


def _restriction(text):
    """Recognize relationships between a G-defined group and an action.

    Merely containing "carrier", "only" or "reported" is insufficient. This
    recognizes quantified inclusion, explicit exclusion, limited operations and
    genotype-dependent operations; unsupported related prose stays unresolved.
    """
    positive = rf'{_POSITIVE} {_PEOPLE}'
    negative = rf'{_NEGATIVE} {_PEOPLE}'
    patterns = [
        rf'\b(?:only|solely|exclusively) (?:the )?{positive}\b[^.!?;]*\b(?:selected|recruited|enrolled|sampled|genotyped|{_REPRESENTATION})\b',
        rf'\b(?:selection|sampling|genotyping|testing|reporting|analysis|analyses|inclusion)\b[^.!?;]*\b(?:restricted|limited) to (?:the )?{positive}\b',
        rf'\b{negative}\b[^.!?;]*\b(?:excluded|omitted|not (?:included|genotyped|tested|reported|analysed|analyzed))\b',
        rf'\b(?:selection|sampling|genotyping|reporting|inclusion)\b[^.!?;]*\b(?:depended on|depends on|was based on|were based on|was conditional on|were conditional on) (?:the )?(?:target )?genotyp\w*\b',
        rf'\b(?:target )?genotyp\w* (?:determined|dictated|decided) which {_PEOPLE} (?:were )?(?:included|reported|selected|retained|entered|appeared)\b',
        rf'\b(?:results|data)\b[^.!?;]*\b(?:presented|reported|shown|displayed) for (?:the )?carriers but not (?:the )?non[- ]?carriers\b',
    ]
    return any(re.search(pattern, text, re.I) for pattern in patterns)


def _mechanism(text):
    # A count of candidate alleles is not a G-defined filter on people.
    if re.fullmatch(r'Only one candidate (?:variant|genotype) was (?:identified|reported)(?: in (?:family|pedigree) [\w-]+)?[.!?;]?', text, re.I):
        return False
    # Reporting synonyms are not positive grammars. A restrictive operation on
    # people remains an unresolved mechanism even when it uses other wording.
    people_or_data = re.search(rf'\b(?:{_PEOPLE}|results|data|pedigree|table|figure)\b', text, re.I)
    representation = re.search(rf'\b{_REPRESENTATION}\b', text, re.I)
    genetic_group = re.search(rf'\b(?:{_G}|mutation[- ]bearing|variant[- ]bearing)\b', text, re.I)
    restricted = re.search(r'\b(?:only|solely|exclusively|alone|restricted|limited|excluded|omitted)\b', text, re.I)
    causal = re.search(r'\b(?:determined|dictated|decided|influenced|dependent|conditional)\b', text, re.I)
    if people_or_data and ((restricted and representation) or (genetic_group and (representation or causal))):
        return True
    return bool(re.search(_ACTIONS, text, re.I) and
                (re.search(_G, text, re.I) or re.search(r'\b(?:phenotype|clinical diagnosis|clinical findings|disease status|only|solely|restricted|limited|depended|excluded|omitted|positive|negative)\b', text, re.I)))


def _reproductive_selection(text):
    """A finite embryo-transfer process is not adult segregation sampling.

    Requiring the entire statement prevents an embryo clause from hiding a
    subsequent genotype-defined restriction on relatives in the same sentence.
    """
    return bool(re.fullmatch(
        r'(?:Only |The )?(?:(?:unaffected|healthy|mutation-free|variant-negative) )?'
        r'(?:embryos|blastocysts)(?: diagnosed as unaffected)? were (?:selected|chosen) '
        r'for (?:transfer|implantation)[.!?;]?', text, re.I))


def _negated_or_uncertain(text, restriction=False):
    if _UNCERTAIN.search(text):
        return True
    # "noncarriers were not reported" is itself an exclusion. Outer negation
    # and denial of a restriction must not be turned into a positive fact.
    denial_text = text
    if restriction:
        denial_text = re.sub(r'\b((?:variant[- ]negative|mutation[- ]negative|non[- ]?carrier) (?:relatives|members|individuals|patients)) (?:were|was) not (?:included|genotyped|tested|reported|analysed|analyzed)\b', r'\1 excluded', text, flags=re.I)
    if re.search(r'\b(?:not true|no evidence|not established|never|neither|denied|did not|was not|were not|is not)\b', denial_text, re.I):
        return True
    if not restriction and re.search(r'\bnot\b', text, re.I):
        return True
    return False


def review_ascertainment(source, inventory, target, *, disease_terms=()):
    """Review already audited family proposals against ALL supplied raw units.

    ``inventory`` is the existing audit_check/bind_native_family_components
    inventory, not model JSON. Family keys use its already bound facts or the
    existing source-local identity formula. ``disease_terms`` are literal target
    labels only; they grant no scientific applicability. A qualified conclusion
    describes a source sufficient condition and never adopts model parameters.
    """
    require(inventory['sourceIdentity']['bundleSha256'] == source['bundleSha256'], 'ASCERTAINMENT_SOURCE_MISMATCH')
    require(inventory['sourceIdentity'].get('pmcid') == source['pmcid'], 'ASCERTAINMENT_PAPER_MISMATCH')
    require(inventory['target']['geneSymbol'] == target['geneSymbol'], 'ASCERTAINMENT_TARGET_MISMATCH')
    units = {unit['unitId']: unit for unit in source['units']}
    require(len(units) == len(source['units']), 'ASCERTAINMENT_DUPLICATE_UNIT')
    reviewed = set(inventory.get('inputScope', {}).get('unitIds', ()))
    terms = tuple(disease_terms) + tuple(target.get('diseaseTerms', ())) + (target['diseaseId'],)
    out = {'schemaVersion': VERSION, 'sourceIdentity': deepcopy(inventory['sourceIdentity']),
           'target': deepcopy(target), 'families': {}, 'businessPublishable': False,
           'scopeStatement': 'BOUNDED_SOURCE_MECHANISM_REVIEW_NOT_SCIENTIFIC_ADOPTION'}
    budget_exceeded = len(units) > MAX_UNITS or sum(len(u['text']) for u in units.values()) > MAX_CHARACTERS
    for label, roster in inventory['families'].items():
        related = [f for f in inventory['facts'] if f['raw']['familyLabel'] == label]
        keys = {f['familyKey'] for f in related}
        default_key = digest(canonical([source['pmcid'], source['main']['sourceSha256'],
                                       roster.get('canonicalLabel') or ('UNRESOLVED:' + label)]))
        family_key = next(iter(keys)) if len(keys) == 1 else default_key
        result = {'familyKey': family_key, 'sourceFamilyLabel': label, 'status': 'UNKNOWN',
                  'qualifiedSubjectLabels': [], 'sufficientConditions': [], 'records': [],
                  'conflicts': [], 'unresolved': [], 'reviewedUnitIds': sorted(reviewed), 'scannedUnitIds': []}
        out['families'][family_key] = result

        def unresolved(code, refs=(), **details):
            result['unresolved'].append({'reasonCode': code, 'sourceRefs': list(refs), **details})

        if budget_exceeded:
            unresolved('PP1_FORMAL_ASCERTAINMENT_REVIEW_BUDGET_EXCEEDED')
            continue
        if len(keys) > 1 or not roster.get('canonicalLabel'):
            unresolved('PP1_FORMAL_ASCERTAINMENT_FAMILY_SCOPE_UNRESOLVED')
        anchor = roster.get('anchor', {})
        # The literal family declaration in an audited source fact can itself
        # serve as an anchor if the caller omitted the inventory display anchor.
        anchor_valid = _valid_ref(anchor, units) or any(
            _valid_ref(ref, units, reviewed) and label in _families(ref['quote'])
            for fact in related for ref in fact.get('sourceRefs', []))
        if not anchor_valid:
            unresolved('PP1_FORMAL_ASCERTAINMENT_FAMILY_ANCHOR_UNVERIFIED')
        members = set(roster['members'])
        ownership = {member: sum(member in r['members'] for r in inventory['families'].values()) for member in members}
        other_members = {m for name, r in inventory['families'].items() if name != label for m in r['members']} - members
        candidates = []
        for fact in related:
            if fact['raw']['fieldId'] != 'F04.1':
                continue
            issues = set(fact.get('issues', ())) - {'MULTIPLE_VALUES_REQUIRE_RECONCILIATION', 'TARGET_ALLELE_LINK_NOT_EXPLICIT_IN_CITATIONS'}
            good_audit = (fact.get('sourceAudit', {}).get('verdict') == 'SUPPORTED'
                          and fact.get('verification') != 'REJECTED' and not issues)
            for ref in fact.get('sourceRefs', []):
                if good_audit and _valid_ref(ref, units, reviewed):
                    candidates.append((fact, ref))
                else:
                    unresolved('PP1_FORMAL_ASCERTAINMENT_SOURCE_AUDIT_UNRESOLVED', [ref], claimId=fact['claimId'])

        previous_families = set()
        previous_unit = None
        for unit in source['units']:
            result['scannedUnitIds'].append(unit['unitId'])
            same_context = bool(previous_unit and unit.get('sourceSha256') == previous_unit.get('sourceSha256')
                                and unit.get('sections', []) == previous_unit.get('sections', []))
            local_families = previous_families if same_context else set()
            for start, end, sentence in _sentences(unit['text']):
                named = _families(sentence)
                if named:
                    local_families = named
                section_families = _families(' '.join(unit.get('sections', [])))
                persons = {m for m in members if re.search(_word(m), sentence)}
                other_persons = {m for m in other_members if re.search(_word(m), sentence)}
                cohort_scope = bool(re.search(r'\b(?:cohort|study-wide|(?:all|the) (?:study |reported |participating )?families)\b', sentence, re.I))
                scope = ('EXPLICIT_FAMILY' if label in named else 'OTHER_FAMILY' if named else
                         'EXPLICIT_MEMBER' if persons and all(ownership[m] == 1 for m in persons) else
                         'OTHER_FAMILY' if other_persons and not persons else
                         'UNRESOLVED_SCOPE' if cohort_scope or persons else
                         'LOCAL_FAMILY_CONTEXT' if local_families == {label} else
                         'SECTION_FAMILY_CONTEXT' if section_families == {label} else
                         'OTHER_FAMILY' if local_families and label not in local_families else 'UNRESOLVED_SCOPE')
                if (scope == 'OTHER_FAMILY' and re.search(r'\b(?:and|but|whereas|while|however)\b', sentence, re.I)
                        and len(re.findall(_ACTIONS + '|' + _REPRESENTATION, sentence, re.I)) > 1):
                    scope = 'UNRESOLVED_SCOPE'
                positive = _support(sentence, label, members)
                restriction = _restriction(sentence)
                reproductive = _reproductive_selection(sentence)
                relevant = positive or restriction or reproductive or _mechanism(sentence)
                # Unicode letters outside ASCII are not silently treated as
                # supported English. Pure-ASCII foreign languages still cannot
                # be detected universally by this bounded extractor.
                multilingual = any(ch.isalpha() and not ch.isascii() for ch in sentence)
                if not relevant and not multilingual:
                    continue
                reference = _ref(source, unit, start, end)
                disease_scope = _disease_scope(sentence, terms)
                record = {'subjectLabels': sorted(persons) or ['FAMILY'], 'scopeBasis': scope,
                          'diseaseScope': disease_scope, 'sourceRefs': [reference], 'claimIds': [],
                          'classification': 'UNRESOLVED', 'sourceBasis': None}
                result['records'].append(record)
                if reproductive:
                    record.update(classification='OUT_OF_SCOPE', purposeScope='EMBRYO_TRANSFER_NOT_ADULT_SEGREGATION')
                    continue
                if scope == 'OTHER_FAMILY' or disease_scope == 'OTHER_DISEASE':
                    record['classification'] = 'OUT_OF_SCOPE'
                    continue
                if disease_scope == 'UNRESOLVED_DISEASE':
                    unresolved('PP1_FORMAL_ASCERTAINMENT_DISEASE_SCOPE_UNRESOLVED', [reference])
                    continue
                if scope == 'UNRESOLVED_SCOPE':
                    unresolved('PP1_FORMAL_ASCERTAINMENT_SCOPE_UNRESOLVED', [reference])
                    continue
                if multilingual:
                    unresolved('PP1_FORMAL_ASCERTAINMENT_LANGUAGE_UNSUPPORTED', [reference])
                    continue
                # A denial of the finite independence statement is a relevant
                # unresolved mechanism; its inner phrase cannot authorize use.
                if positive:
                    subject, source_basis = positive
                    for fact, ref in candidates:
                        at = unit['text'].find(ref['quote'])
                        if (ref['unitId'] == unit['unitId'] and at <= start and end <= at + len(ref['quote'])
                                and fact['raw']['memberLabel'] == subject
                                and (subject == 'FAMILY' or ownership.get(subject) == 1)):
                            record['claimIds'].append(fact['claimId'])
                    record.update(subjectLabels=[subject], sourceBasis=source_basis)
                    if record['claimIds']:
                        record['classification'] = 'SUPPORTED_SUFFICIENT_CONDITION'
                        result['sufficientConditions'].append({'subjectLabel': subject, 'sourceBasis': source_basis,
                            'claimIds': sorted(set(record['claimIds'])), 'sourceRefs': [reference]})
                        continue
                    unresolved('PP1_FORMAL_ASCERTAINMENT_POSITIVE_REQUIRES_SOURCE_AUDIT', [reference])
                    continue
                if restriction and not _negated_or_uncertain(sentence, restriction=True):
                    record['classification'] = 'GENOTYPE_DEPENDENT_RESTRICTION'
                    continue
                if _negated_or_uncertain(sentence):
                    record['classification'] = 'NEGATED_OR_UNCERTAIN'
                    unresolved('PP1_FORMAL_ASCERTAINMENT_NEGATED_OR_UNCERTAIN', [reference])
                    continue
                unresolved(UNSUPPORTED, [reference])
            previous_families, previous_unit = local_families, unit

        restrictions = [r for r in result['records'] if r['classification'] == 'GENOTYPE_DEPENDENT_RESTRICTION']
        if restrictions:
            result['status'] = 'INELIGIBLE'
            if result['sufficientConditions']:
                result['conflicts'].append({'reasonCode': 'PP1_FORMAL_ASCERTAINMENT_SUPPORT_RESTRICTION_CONFLICT',
                    'supportSourceRefs': [ref for r in result['sufficientConditions'] for ref in r['sourceRefs']],
                    'restrictionSourceRefs': [ref for r in restrictions for ref in r['sourceRefs']]})
        elif result['sufficientConditions'] and not result['unresolved']:
            result['status'] = 'QUALIFIED_SUFFICIENT_CONDITION'
            result['qualifiedSubjectLabels'] = sorted({r['subjectLabel'] for r in result['sufficientConditions']})
        else:
            unresolved(UNSUPPORTED)
    return out
