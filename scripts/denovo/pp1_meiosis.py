"""Bounded affected-only AD informative meiosis counting for published PP1 rules."""
from collections import defaultdict
from itertools import combinations
import re

from pp1_literature_contract import require

METHOD = 'PUBLISHED_INFORMATIVE_MEIOSES_COUNT'
AFFECTED_NOTE = ('Note: individuals must have disease consistent with reported phenotype '
                 '(even if on the mild end of spectrum of the disease).')
BENIGN_COMBINATION_NOTE = ('1 Strong AND 3 Supporting added to benign criteria code. '
                           '1 Strong added to likely benign criteria code.')
SCOPE_AND_SYMBOLS_NOTE = r'Minor Changes: 1\. Added disease name and MOI 2\. Updated PS3 and PS4 \< and > signs to ≥ or ≤ if necessary'
THRESHOLD_PREFIX = 'Co-segregation with disease in multiple affected family members. * '
FAMILY_THRESHOLD_PREFIX = 'Co-segregation with disease in multiple affected family members, with '
AWARDS = {'SUPPORTING': 1, 'MODERATE': 2, 'STRONG': 4}


def threshold(text):
    if text.startswith(FAMILY_THRESHOLD_PREFIX):
        form = text.removeprefix(FAMILY_THRESHOLD_PREFIX)
        minimum = re.fullmatch(r'≥([1-9][0-9]{0,3}) meioses observed across at least two families\.', form)
        bounded = re.fullmatch(r'([1-9][0-9]{0,3}) or ([1-9][0-9]{0,3}) meioses observed\.', form)
        require(minimum or bounded and int(bounded[1]) <= int(bounded[2]), 'PUBLISHED_PP1_MEIOSIS_THRESHOLD_UNSUPPORTED')
        return {'minimum': int((minimum or bounded)[1]), 'maximum': None if minimum else int(bounded[2]),
                'minimumIndependentFamilies': 2 if minimum else 1, 'sourceText': text}
    match = re.fullmatch(r'(≥|>=)?([1-9][0-9]{0,3})(?:[-–]([1-9][0-9]{0,3}))? informative meios(?:is|es)\.?',
                         text.removeprefix(THRESHOLD_PREFIX))
    require(match is not None, 'PUBLISHED_PP1_MEIOSIS_THRESHOLD_UNSUPPORTED')
    lower = int(match[2])
    upper = None if match[1] else int(match[3] or match[2])
    require(not (match[1] and match[3]) and (upper is None or lower <= upper), 'PUBLISHED_PP1_THRESHOLD_INVALID')
    return {'minimum': lower, 'maximum': upper, 'minimumIndependentFamilies': 1, 'sourceText': text}


def score(thresholds, count, families=1):
    for strength in reversed(AWARDS):
        row = thresholds[strength]
        if (row['minimum'] <= count and (row['maximum'] is None or count <= row['maximum'])
                and families >= row['minimumIndependentFamilies']):
            return strength, AWARDS[strength]
    return None, 0


def duplicated_threshold_summary(thresholds):
    supporting, moderate, strong = (thresholds[k] for k in AWARDS)
    require(strong['minimumIndependentFamilies'] == 2, 'PUBLISHED_PP1_MEIOSIS_FAMILY_REQUIREMENT_MISSING')
    return (f"PP1: Requires {supporting['minimum']} or {supporting['maximum']} meioses in order to apply. "
            f"PP1_Strong: At least {strong['minimum']} meioses required across at least two families. "
            f"PP1_Moderate: Requires {moderate['minimum']} or {moderate['maximum']} meioses in order to apply.")


def family_relationships(source, reviewed, families):
    """Recognize only source-named pair declarations in the same paper."""
    aliases = defaultdict(set)
    for key, roster in families.items():
        label = roster.get('literalFamilyLabel', '')
        match = re.fullmatch(r'(?:family )?([a-zA-Z0-9][a-zA-Z0-9_:-]{0,23})', label, re.I)
        if match:
            aliases[match[1].lower()].add(key)
    aliases = {alias: next(iter(keys)) for alias, keys in aliases.items() if len(keys) == 1}
    if len(aliases) < 2:
        return []
    main = source['main']['sourceSha256']
    units = [unit for unit in source['units'] if unit['sourceSha256'] == main]
    if len(units) > 5000 or sum(len(unit['text']) for unit in units) > 2_000_000:
        return []
    name = '|'.join(re.escape(alias) for alias in sorted(aliases, key=lambda a: (-len(a), a)))
    pair = rf'(?:Family (?P<a>{name}) and family (?P<b>{name})|Families (?P<c>{name}) and (?P<d>{name}))'
    states = {'unrelated': 'INDEPENDENT', 'related': 'RELATED', 'from the same pedigree': 'SAME_FAMILY',
              'not unrelated': 'RELATED', 'of unknown relatedness': 'UNRESOLVED'}
    condition = '|'.join(re.escape(state) for state in states)
    findings = {}
    for unit in units:
        for sentence in re.finditer(r'\S.*?(?:[.!?](?=\s|$)|$)', unit['text']):
            quote = sentence[0].strip()
            match = re.fullmatch(rf'{pair} (?:are|were) (?P<condition>{condition})\.', quote, re.I)
            if match:
                status = states[match['condition'].lower()]
                families_in_statement = [(match['a'] or match['c']).lower(), (match['b'] or match['d']).lower()]
            else:
                families_in_statement = founder_family_range(quote)
                status = 'RELATED'
            if status == 'INDEPENDENT' and unit['unitId'] not in reviewed:
                continue
            roster_keys = sorted({aliases[family] for family in families_in_statement if family in aliases})
            for keys in combinations(roster_keys, 2):
                offset = sentence.start() + sentence[0].index(quote)
                ref = {'unitId': unit['unitId'], 'quote': quote, 'start': offset, 'end': offset + len(quote),
                       'sourceSha256': main, 'pmcid': source['pmcid'], 'bundleSha256': source['bundleSha256']}
                findings.setdefault((keys, status), {'familyKeys': list(keys), 'status': status, 'sourceRefs': [ref]})
    return list(findings.values())


def founder_family_range(statement):
    """A confirmed common ancestor blocks independence without merging member identities."""
    match = re.fullmatch(r'(?:Both )?haplotype analysis and genealogical examination confirmed the common ancestry of '
                         r'families ([1-9][0-9]{0,2})[–-]([1-9][0-9]{0,2})'
                         r'(?: sharing the [A-Z][A-Z0-9-]+ c\.[0-9]+\s*[+-]\s*[0-9]+\s*[ACGT]\s*>\s*[ACGT] mutation)?'
                         r'(?: \[[0-9]+\])?\.', statement, re.I)
    if match and 0 < int(match[2]) - int(match[1]) < 32:
        return [str(n) for n in range(int(match[1]), int(match[2]) + 1)]
    return []


def select_families(counts, relationship_records, thresholds):
    """Select an all-pairs-proven group. A proof chain is not transitive."""
    by_pair = defaultdict(dict)
    for record in relationship_records:
        pair = tuple(record['familyKeys'])
        by_pair[pair].setdefault(record['status'], record)
    aliases = {}

    def representative(key):
        while key in aliases:
            key = aliases[key]
        return key

    for pair, states in by_pair.items():
        if 'SAME_FAMILY' in states:
            left, right = map(representative, pair)
            if left != right:
                aliases[max(left, right)] = min(left, right)
    contrary = {tuple(sorted(map(representative, pair))) for pair, states in by_pair.items()
                if set(states) - {'INDEPENDENT'}}
    independent = {pair: states['INDEPENDENT'] for pair, states in by_pair.items()
                   if set(states) == {'INDEPENDENT'} and representative(pair[0]) != representative(pair[1])
                   and tuple(sorted(map(representative, pair))) not in contrary}
    candidates = []
    keys = sorted(counts)
    # Exact search over a bounded number of source families. Larger sets
    # retain a safe single-family result rather than use a greedy guess.
    maximum_size = len(keys) if len(keys) <= 12 else 1
    for size in range(1, maximum_size + 1):
        for group in combinations(keys, size):
            pairs = list(combinations(group, 2))
            if any(pair not in independent for pair in pairs):
                continue
            total = sum(counts[key] for key in group)
            _, points = score(thresholds, total, size)
            candidates.append((-points, -total, -size, group, pairs))
    if not candidates:
        return (), []
    _, _, _, group, pairs = min(candidates)
    return group, [independent[pair] for pair in pairs]


def sibship_reference(reference, parents, by, genotypes):
    """Resolve a phased reference sibship without imputing an ancestor's test."""
    origins = {row['value'] for row in by[reference]['alleleOriginParent']}
    if len(origins) != 1 or len(parents[reference]) != 2 or not origins <= parents[reference]:
        return None
    parent, = origins
    other, = parents[reference] - origins
    if genotypes.get(other) == 'NONCARRIER' and genotypes.get(parent) in (None, 'HETEROZYGOUS'):
        return parent, other
    return None


def informative_transmissions(family, members, parents, edge_refs, by, statuses, genotypes, need, rule_clause,
                              *, conditional=False, inferred=None):
    """One unit per anchored, positive, distinguishable biological transmission."""
    inferred = inferred or {}
    positive = {m for m in members if genotypes[m] == 'HETEROZYGOUS' or m in inferred}
    probands = [member for member in members if {r['value'] for r in by[member]['proband']} == {True}]
    references = [m for m in members if by[m]['conditionalReference']] if conditional else []
    anchors = references or probands
    if (len(anchors) != 1 or references and probands and probands != references
            or statuses[anchors[0]] != 'AFFECTED' or anchors[0] not in positive):
        need('PP1_MEIOSIS_AFFECTED_INDEX_REQUIRED', 'LITERATURE', family, (), 'BLOCKS_FAMILY', rule_clause)
        return []
    index, = anchors
    role = 'conditionalReference' if references else 'proband'
    index_proof = by[index][role] + by[index]['genotype'] + by[index]['diagnosis'] + inferred.get(index, [])
    phased = sibship_reference(index, parents, by, genotypes) if references else None
    if references and (phased is None or genotypes[index] != 'HETEROZYGOUS'):
        need('PP1_CONDITIONAL_SIBSHIP_PHASE_REQUIRED', 'LITERATURE', family, index_proof, 'BLOCKS_FAMILY', rule_clause)
        return []
    witnessed, graph = [], defaultdict(set)
    if phased:
        origin, other = phased
        index_phase_proof = (by[index]['alleleOriginParent'] + by[other]['genotype']
                             + edge_refs[origin, index] + edge_refs[other, index])
        graph[origin].add(index)
        graph[index].add(origin)
    for child in sorted(members):
        ps = parents[child]
        if statuses[child] != 'AFFECTED' or child not in positive or not ps or (references and child == index):
            continue
        origins = by[child]['alleleOriginParent']
        named = {r['value'] for r in origins}
        if len(named) > 1 or any(genotypes.get(parent) == 'NONCARRIER' for parent in named):
            need('PP1_MEIOSIS_ORIGIN_CONFLICT', 'SOURCE_REVIEW', family, origins, 'BLOCKS_FAMILY', rule_clause)
            continue
        heterozygous_parents = {p for p in ps if p in positive and statuses[p] == 'AFFECTED'}
        parent = next(iter(named), None)
        proof = list(origins)
        if not named and len(ps) == 2 and len(heterozygous_parents) == 1:
            candidate, = heterozygous_parents
            other, = ps - {candidate}
            if genotypes[other] == 'NONCARRIER':
                parent = candidate
                proof = by[other]['genotype'] + edge_refs[other, child]
        sibling = (phased is not None and ps == parents[index] and named == {origin}
                   and genotypes[child] == 'HETEROZYGOUS' and parent not in heterozygous_parents)
        if sibling:
            proof += index_phase_proof + edge_refs[other, child]
        elif parent not in heterozygous_parents:
            if heterozygous_parents:
                need('PP1_MEIOSIS_ORIGIN_UNRESOLVED', 'LITERATURE',
                     {'familyKey': family, 'memberKey': child}, origins + by[child]['genotype'], 'MAY_ADD_EVENT', rule_clause)
            continue
        proof = proof + edge_refs[parent, child] + index_proof
        for member in (parent, child):
            proof += by[member]['genotype'] + by[member]['diagnosis'] + inferred.get(member, [])
        witnessed.append((parent, child, proof, index if sibling else None))
        graph[parent].add(child)
        graph[child].add(parent)
    reached, pending = {index}, [index]
    while pending:
        relatives = graph[pending.pop()] - reached
        reached.update(relatives)
        pending.extend(relatives)
    connected = [item for item in witnessed if item[0] in reached]
    if len(connected) != len(witnessed):
        need('PP1_MEIOSIS_INDEX_CONNECTION_UNRESOLVED', 'LITERATURE', family, (), 'MAY_ADD_EVENT', rule_clause)
    return connected
