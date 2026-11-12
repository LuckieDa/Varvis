"""Conservative native-text qualification of revalidated PP1 source proposals.

The supported grammars are reusable, not gene-specific. Unsupported prose,
figures, ambiguous subjects and same-variant references stay visible. A model
audit alone never produces a typed genotype, diagnosis or transmission.
"""
from collections import defaultdict
from copy import deepcopy
import re

from model_literature_contract import canonical, digest
from pp1_literature_contract import require
from pp1_meiosis import family_relationships
from pp1_hht_phenotype import source_facts as hht_source_facts

VERSION = 'pp1-source-qualification-v1'


def word(value):
    return r'(?<![\w:-])' + re.escape(value) + r'(?![\w:-])'


def assertive(unit_text, quote, start):
    """A quoted substring cannot hide a negated or conditional outer clause."""
    at = unit_text.find(quote)
    if at < 0: return False
    prefix = re.split(r'(?<=[.!?;])\s+', unit_text[:at+start])[-1]
    return not re.search(r'\b(not|no|never|if|whether|may|might|could|hypothetical|suspected|possible|without|neither)\b',prefix,re.I)


def explicit_source_sentences(unit_text, quote, pattern):
    """Match complete native assertions, not a shortened quoted inner clause.

    Formal ascertainment and attained-age facts have stricter meaning than a
    reported label. Require the whole sentence (apart from final punctuation)
    in the citation and the original unit, so outer uncertainty/negation and
    additional selection conditions cannot disappear through a short quote.
    """
    for sentence in re.split(r'(?<=[.!?;])\s+', unit_text):
        statement = sentence.strip().rstrip('.!?;')
        if not statement or statement not in quote:
            continue
        match = re.fullmatch(pattern, statement, re.I)
        if match and assertive(unit_text, statement, 0):
            yield match, statement


def target_allele_origin(unit_text, quote, child, other_members, transcript, coding):
    """Read a complete statement about the named child's exact allele origin."""
    if not other_members:
        return
    names = '|'.join(re.escape(name) for name in sorted(other_members, key=lambda n: (-len(n), n)))
    variant = rf'(?:{re.escape(transcript)}:)?{re.escape(coding)}'
    pattern = (rf'{word(child)} inherited (?:the variant )?{variant} '
               rf'from (?P<parent>{names})')
    for sentence in re.split(r'(?<=[.!?])\s+', unit_text):
        # Keep question marks and trailing qualifications so they cannot
        # become affirmative evidence after clipping a model citation.
        statement = sentence.strip().removesuffix('.')
        if statement in quote:
            match = re.fullmatch(pattern, statement)
            if match:
                yield match['parent']


def formal_ascertainment(unit_text, quote, subject, *, family=False):
    """Explicit phenotype-led ascertainment before genetic testing only.

    A proband label, testing performed after a diagnosis, or ascertainment of
    variant-positive relatives alone establishes none of this assumption.
    The scope is named literally; no generic cohort-to-family propagation.
    """
    name = word(subject)
    if family:
        scope = rf'(?:[Tt]he )?(?:(?:members|individuals) of )?(?:family|pedigree) {name}'
    else:
        scope = rf'(?:[Tt]he )?(?:(?:patient|individual|member) )?\(?{name}\)?'
    basis = r'(?:clinical phenotype|disease phenotype|phenotype|clinical diagnosis|clinical findings|disease status)'
    testing = r'(?:genotyping|genetic testing|molecular testing|testing for the target variant)'
    pattern = (rf'{scope} (?:was|were) (?:ascertained|recruited|enrolled|selected) '
               rf'(?:on the basis of|based on|by) {basis} '
               rf'(?:before|prior to) {testing}')
    return any(explicit_source_sentences(unit_text, quote, pattern))


def examination_ages(unit_text, quote, member):
    """Attained age at an explicit examination/follow-up, never onset age."""
    name = rf'(?:[Tt]he )?(?:(?:patient|individual|member) )?\(?{word(member)}\)?'
    years = r'(?P<age>\d+) (?:years old|years of age|years)'
    at_age = rf'at (?:(?:the )?age (?:of )?)?{years}'
    patterns = [
        rf'{name} (?:was|is) (?:examined|clinically (?:examined|assessed|evaluated)) {at_age}',
        rf'{name} underwent (?:a )?clinical examination {at_age}',
        rf'{name} was (?:last )?followed up {at_age}',
        rf'{name} (?:was|is) {years} at (?:the |his |her )?(?:last |clinical )?(?:examination|follow-up|clinical assessment|clinical evaluation)',
        rf'At (?:the |his |her )?(?:last |clinical )?(?:examination|follow-up), {name} (?:was|is) {years}',
    ]
    for pattern in patterns:
        for match, unused in explicit_source_sentences(unit_text, quote, pattern):
            yield int(match['age'])


def explicit_consanguinity(unit_text, quote, source_value, subject, *, family=False):
    """Qualify only the consanguinity assertion covered by this caution claim.

    A broader caution containing other assertions is deliberately not marked
    handled just because its citation also mentions consanguinity. Both the
    typed observation and retained caution keep the original claim/proof.
    """
    name = word(subject)
    if family:
        scope = rf'(?:family|pedigree) {name}'
        patterns = [rf'(?:[Tt]he )?parents in {scope} (?:were|are) consanguineous',
                    rf'Consanguinity was reported in {scope}',
                    rf'(?:[Tt]he )?{scope} (?:was|is) consanguineous']
    else:
        person = rf'\(?{name}\)?'
        patterns = [rf'(?:[Tt]he )?parents of {person} (?:were|are) consanguineous',
                    rf'{person} was born to consanguineous parents',
                    rf'{person} (?:was|is) the offspring of consanguineous parents']
    for pattern in patterns:
        for unused, statement in explicit_source_sentences(unit_text, quote, pattern):
            if source_value.strip().rstrip('.!?;') in statement:
                return True
    return False


def named_carrier_results(unit_text, quote, coding, members):
    """Read positive clauses whose subjects are explicitly named in the source.

    A role followed by a printed ID names a subject; it does NOT establish whose
    son/brother that subject is. Coordinated subjects share the result, not an
    inferred pedigree or zygosity. Anaphora remains local to one unambiguous unit.
    """
    names = '(?:' + '|'.join(re.escape(m) for m in sorted(members, key=len, reverse=True)) + ')'
    identifier = r'(?<![\w:-])' + names + r'(?![\w:-])'
    role = r'(?:proband|index patient|patient|individual|father|mother|parent|brother|sister|son|daughter|child|grandson|granddaughter)'
    subject = rf'(?:(?:[Tt]he|[Hh]is|[Hh]er|[Tt]heir) )?(?:{role} )?\(?{identifier}\)?'
    subjects = subject + rf'(?:(?:,\s*(?:and )?| and ){subject})*'
    verb = r'(?:(?:has|had|have|carries|carried|carry) |(?:was|were) (?:revealed|found|shown) to (?:have|carry) )'
    explicit = r'(?:(?:the |a )?variant (?:of )?)?(?:NM_\d+\.\d+:)?' + re.escape(coding) + r'(?![\w>+-])'
    pattern = rf'(?P<subjects>{subjects}) {verb}(?P<result>the same variant\b|{explicit})'
    # Include indels/other coding forms in the ambiguity check, not just SNVs.
    forms = {m.rstrip(').') for m in re.findall(r'\bc\.\(?[0-9*+\-][^\s,;:]*', unit_text)}
    other_variant = re.search(r'\b(?:another|different|second|additional) (?:\w+ )?(?:variant|mutation)\b', unit_text, re.I)
    for match in re.finditer(pattern, quote):
        if not assertive(unit_text, quote, match.start()):
            continue
        # Do not parse the last member of a quantified group as a standalone
        # subject: the group's count and full membership must be checked below.
        prefix = re.split(r'(?<=[.!?;])\s+', quote[:match.start()])[-1]
        if re.search(r'\ball\s+(?:\d+|one|two|three|four|five|six|seven|eight|nine|ten)\s+(?:patients|individuals|members)\s*\(?[^)]*$', prefix, re.I):
            continue
        same = match['result'] == 'the same variant'
        if same and (forms != {coding} or other_variant
                     or unit_text.find(coding) >= unit_text.find(quote) + match.start()):
            continue
        labels = [m for m in members if re.search(word(m), match['subjects'])]
        yield labels, same
    numbers = {name:i for i,name in enumerate(('one','two','three','four','five','six','seven','eight','nine','ten'),1)}
    group = rf'\bAll (?P<count>\d+|{"|".join(numbers)}) (?:patients|individuals|members) \((?P<ids>[^)]+)\) (?:carried|carry|had) {explicit}'
    for match in re.finditer(group, quote, re.I):
        labels = [s.strip() for s in re.split(r',\s*(?:and\s+)?|\s+and\s+',match['ids'])]
        count = numbers.get(match['count'].lower(),int(match['count']) if match['count'].isdigit() else -1)
        if (len(labels)==len(set(labels))==count and all(m in members for m in labels)
                and assertive(unit_text,quote,match.start())):
            yield labels,False


def local_subject_facts(unit_text, quote, members):
    """Bounded discourse: explicit index person, adjacent pronoun, named roles.

    No cross-paragraph coreference, pedigree numbering or inferred maternal
    grandparent. Every derived value retains the actual antecedent sentences.
    A new/ambiguous subject terminates the pronoun chain.
    """
    names='|'.join(re.escape(m) for m in sorted(members,key=len,reverse=True))
    if not names:return
    anchor=re.compile(rf'^(?:The )?(?:proband|index patient|index case)(?: in (?:family|pedigree) [\w-]+)? is a (?P<sex>female|male) \((?P<id>{names})\)\.$',re.I)
    sentences=re.split(r'(?<=[.!?])\s+',unit_text)
    current=None;proof=[];sex=None;definitions={}
    for sentence in sentences:
        match=anchor.fullmatch(sentence)
        if match:
            current=match['id'];sex=match['sex'].lower();proof=[sentence];definitions={}
            if sentence in quote:
                yield current,'proband',True,proof
                yield current,'sex',sex.upper(),proof
            continue
        if not current:continue
        pronoun='She' if sex=='female' else 'He'
        possessive='Her' if sex=='female' else 'His'
        if re.match(rf'^{pronoun}\s',sentence):
            # An explicit second person/relationship invalidates sole-subject
            # resolution, even if the model proposes the original person.
            if re.search(r'\b(mother|father|sister|brother|husband|wife|patient|proband)\b',sentence,re.I) or any(re.search(word(m),sentence) for m in members):
                current=None;continue
            if re.search(r'\b(not|never|might|may|could|suspected|possible|whether)\b',sentence,re.I):
                current=None;continue
            # A nested clause ("She reported that X was diagnosed...") has a
            # different subject and must not become the proband's diagnosis.
            diagnosis=re.fullmatch(rf'{pronoun} (?:was|is) (?:a \d+-year-old (?:woman|man) who (?:was|is) )?diagnosed with ([^.;]+)\.',sentence)
            if diagnosis and assertive(sentence,sentence,diagnosis.start()):
                term=diagnosis[1].strip()
                definition=re.fullmatch(r'([A-Za-z][A-Za-z -]+) \(([A-Z][A-Z0-9-]{1,11})\)',term)
                if definition:
                    # Only the author's explicit definition in this subject
                    # chain, never a global disease synonym or LLM guess.
                    term=definition[1].strip()
                    definitions[definition[2]]=term
                if sentence in quote:
                    yield current,'diagnosis',term,proof+[sentence]
            proof=proof+[sentence]
            if len(proof)>2:current=None
            continue
        role=re.match(rf'^{possessive} (?P<roles>[a-z, ]+) (?:are all|are|were|is|was) (?P<diagnosis>[A-Za-z][A-Za-z -]*?) patients\b',sentence)
        if role and assertive(sentence,sentence,role.start()):
            labels=[x.strip() for x in re.split(r',\s*(?:and\s+)?|\s+and\s+',role['roles'])]
            if all(x in {'mother','father','grandfather','grandmother','brother','sister'} and x in members for x in labels):
                statement=role[0]
                if statement in quote:
                    for label in labels:
                        term=role['diagnosis'].strip()
                        yield label,'diagnosis',definitions.get(term,term),proof+[sentence]
        current=None


def local_kinship(unit_text, quote, members):
    """Resolve possessive kinship only against the preceding unique subject.

    Grandchildren are not silently made children of a named son, and a reported
    sibling is not assumed to be a full sibling. These retained relations do not
    by themselves create an informative meiosis or expert-count connection.
    """
    sentences = re.split(r'(?<=[.!?])\s+', unit_text)
    names = '|'.join(re.escape(m) for m in sorted(members, key=len, reverse=True))
    if not names: return
    person = rf'(?P<person>{names})(?![\w:-])'
    subject = re.compile(rf'^(?:However,\s*)?(?:(?:The|the)\s+)?'
        rf'(?:patient|proband|index patient|individual|he|she)\s+\(?{person}\)?\s', re.I)
    relative = re.compile(rf'(?P<role>son|daughter|child|father|mother|parent|brother|sister|sibling|grandson|granddaughter|grandchild)'
                          rf'\s+\(?(?P<person>{names})\)?(?![\w:-])', re.I)
    kinds = {'son':'childOf','daughter':'childOf','child':'childOf',
             'father':'parentOf','mother':'parentOf','parent':'parentOf',
             'brother':'siblingOf','sister':'siblingOf','sibling':'siblingOf',
             'grandson':'grandchildOf','granddaughter':'grandchildOf','grandchild':'grandchildOf'}
    for previous, sentence in zip(sentences, sentences[1:]):
        head = subject.match(previous)
        if not head or len({m for m in members if re.search(word(m),previous)}) != 1:
            continue
        lead = re.match(r'^(?:His|Her)\s+', sentence)
        if not lead: continue
        rest = sentence[lead.end():]
        relations = []
        offset = lead.end()
        while True:
            match = relative.match(rest)
            if not match: break
            span = sentence[offset:offset+match.end()]
            if span in quote and match['person'] != head['person']:
                relations.append((match['person'],kinds[match['role'].lower()],head['person']))
            rest = rest[match.end():]; offset += match.end()
            join = re.match(r'(?:,\s*(?:and\s+)?|\s+and\s+)',rest)
            if not join: break
            rest=rest[join.end():];offset+=join.end()
        # A negated/hypothetical relationship is not an established relation.
        # Negated phenotype descriptions in the antecedent do not negate the ID.
        if re.search(r'\b(not|never|if|whether|may|might|could|possible|suspected|neither)\b', sentence, re.I):
            continue
        for member,kind,other in relations:
            yield member,kind,other,previous,sentence


def bind_native_family_components(source, inventory):
    """Resolve a display-only family label through explicit native kinship.

    A connected component binds local people, never proband status, disease,
    direct parentage across missing generations or cross-paper independence.
    Every roster member must be connected within the cited, reviewed unit.
    Raw model proposals and their stored audit artifacts remain unchanged.
    """
    result = deepcopy(inventory)
    units = {u['unitId']: u for u in source['units']}
    for label, roster in result['families'].items():
        if roster.get('canonicalLabel') or label == 'UNRESOLVED': continue
        anchor = roster.get('anchor', {})
        unit = units.get(anchor.get('unitId'))
        members = roster['members']
        if not unit or unit['unitId'] not in result['inputScope']['unitIds'] or len(members) < 2: continue
        if anchor.get('quote', '') not in unit['text'] or not anchor.get('quote'): continue
        relations = list(local_kinship(unit['text'], unit['text'], members))
        graph = {m:set() for m in members}
        proof = []
        for member,kind,other,previous,sentence in relations:
            graph[member].add(other); graph[other].add(member)
            proof.append({'unitId':unit['unitId'],'quote':previous+' '+sentence,
                          'meaning':'EXPLICIT_KINSHIP_FAMILY_MEMBERSHIP_ONLY'})
        reached = {next(iter(members))}
        while True:
            expanded = reached | {other for m in reached for other in graph[m]}
            if expanded == reached: break
            reached = expanded
        if reached != set(members): continue
        # Keep the source's exact spacing, and require all component evidence to
        # be actual source text rather than reconstructed prose.
        if not proof or any(q['quote'] not in unit['text'] for q in proof): continue
        canonical_label = 'NATIVE_KINSHIP:' + digest(canonical([unit['unitId'],sorted(members)]))
        roster.update(canonicalLabel=canonical_label, identityBasis='EXPLICIT_NATIVE_KINSHIP_COMPONENT',
                      identityEvidence=proof)
        family_key = digest(canonical([source['pmcid'],source['main']['sourceSha256'],canonical_label]))
        for fact in result['facts']:
            if fact['raw']['familyLabel'] != label: continue
            fact['familyKey'] = family_key
            fact['memberKey'] = digest(canonical([source['pmcid'],source['main']['sourceSha256'],
                                                   canonical_label,fact['raw']['memberLabel']]))
            fact['issues'] = [i for i in fact['issues'] if i != 'FAMILY_IDENTITY_UNRESOLVED']
            fact['familyIdentityEvidence'] = proof
    return result


def qualify(source, inventory, selection, resolver=None):
    require(inventory['sourceIdentity']['bundleSha256'] == source['bundleSha256'], 'QUALIFICATION_SOURCE_MISMATCH')
    require(inventory['target']['geneSymbol'] == selection['geneSymbol'], 'QUALIFICATION_GENE_MISMATCH')
    inventory = bind_native_family_components(source, inventory)
    units = {u['unitId']: u for u in source['units']}
    reviewed = set(inventory['inputScope']['unitIds'])
    ownership = defaultdict(set)
    for label, roster in inventory['families'].items():
        for member in roster['members']: ownership[member].add(roster.get('canonicalLabel') or ('UNRESOLVED:'+label))
    counts = {member:len(families) for member,families in ownership.items()}
    result = {'schemaVersion': VERSION, 'sourceIdentity': inventory['sourceIdentity'],
        'target': selection, 'families': {}, 'observations': [], 'unresolved': [],
        'identity': None, 'identityIssue': None, 'identityEvidence': [], 'reviewedUnitIds': sorted(reviewed),
        'businessPublishable': False}
    # Without a published transcript version, only an exact coding/genomic
    # pair can corroborate the pinned mapping. Local resolution rechecks REF.
    text = '\n'.join(units[i]['text'] for i in reviewed)
    txs = set(re.findall(r'\bNM_\d+\.[1-9]\d*\b', text))
    matches = [a for a in inventory['target']['variantAliases'] if a in text]
    coding = sorted({a.split(':')[-1] for a in matches if a.split(':')[-1].startswith('c.')})
    # Typography around the substitution operator is not a different allele.
    # Keep original aliases/quotes untouched; normalize only this complete SNV
    # spelling for the existing transcript/reference resolver, never free text.
    coding = sorted({re.sub(r'^(c\.[0-9]+[ACGT])\s*>\s*([ACGT])$', r'\1>\2', a)
                     for a in coding})
    result['identityEvidence'] = [{'kind':'reportedVariant','value':u['text'],
        'sourceRefs':[{'unitId':u['unitId'],'quote':u['text'],'sourceSha256':u.get('sourceSha256')}]} for u in source['units']
        if u['unitId'] in reviewed and any(a in u['text'] for a in matches)]
    try:
        require(len(coding) == 1, 'SOURCE_CODING_TRANSCRIPT_MISSING_OR_AMBIGUOUS')
        deletion = re.fullmatch(r'c\.[1-9][0-9]*(?:_[1-9][0-9]*)?del', coding[0])
        coding_snv = re.fullmatch(r'c\.[1-9][0-9]*[ACGT]>[ACGT]', coding[0])
        require(len(txs) <= 1 if deletion or coding_snv else len(txs) == 1,
                'SOURCE_CODING_TRANSCRIPT_MISSING_OR_AMBIGUOUS')
        require(resolver is not None, 'LOCAL_IDENTITY_PROVIDER_UNAVAILABLE')
        tx = next(iter(txs), None)
        if deletion:
            resolved = deletion_identity(source, reviewed, selection, coding[0], tx, resolver)
        elif coding_snv and tx is None:
            require(selection['allele']['assembly'] == 'GRCh38', 'SOURCE_SNV_ASSEMBLY_UNSUPPORTED')
            position, refs = native_grch38_table_pair(
                source, reviewed, coding[0], 'SOURCE_SNV_GRCH38_PAIR_MISSING_OR_AMBIGUOUS')
            resolved = resolver.resolve_coding_snv_at_position(selection['geneSymbol'], coding[0], position)
            resolved['sourceRefs'] = refs
        else:
            resolved = resolver.resolve_coding(selection['geneSymbol'], coding[0], tx)
        require(resolved['allele'] == selection['allele'], 'SELECTED_ALLELE_MISMATCH')
        result['identity'] = resolved
    except (ValueError, OSError) as error:
        code = str(error)
        result['identityIssue'] = code if re.fullmatch('[A-Z][A-Z0-9_]{1,150}', code) else 'PP1_IDENTITY_EXECUTION_FAILED'
        # An explicitly paired genomic SNV in the paper can resolve an HGVS
        # form outside the coding-only helper. Reuse its bounded reference
        # reader; do not implement a new intronic normalization algorithm.
        if code == 'DENOVO_IDENTITY_CODING_SNV_REQUIRED' and len(coding)==1:
            try:
                result['identity'] = genomic_identity(source,reviewed,selection,coding[0],resolver)
                result['identityIssue'] = None
            except (ValueError,OSError) as fallback:
                reason=str(fallback)
                result['identityIssue']=reason if re.fullmatch('[A-Z][A-Z0-9_]{1,150}',reason) else 'PP1_GENOMIC_IDENTITY_EXECUTION_FAILED'

    def emit(fact, kind, value, refs):
        observation = {'familyKey': fact['familyKey'], 'memberKey': fact['memberKey'],
            'memberLabel': fact['raw']['memberLabel'], 'kind': kind, 'value': value,
            'claimIds': [fact['claimId']], 'sourceRefs': refs + fact.get('familyIdentityEvidence', []),
            'method': 'EXPLICIT_NATIVE_TEXT_GRAMMAR'}
        observation['id'] = digest(canonical([observation[k] for k in ('familyKey','memberKey','kind','value')]))
        result['observations'].append(observation)

    facts = inventory['facts']
    for label, roster in inventory['families'].items():
        canonical_label = roster.get('canonicalLabel') or ('UNRESOLVED:'+label)
        parts = [source['pmcid'], source['main']['sourceSha256'], canonical_label]
        family = digest(canonical(parts))
        result['families'].setdefault(family,{'members':{},'sourceFamilyLabel':label})['members'].update(
            {member:digest(canonical(parts+[member])) for member in roster['members']})
        if roster.get('identityBasis') == 'LITERAL_LABEL' and roster.get('canonicalLabel') == label:
            result['families'][family]['literalFamilyLabel'] = label
    for f in facts:
        raw, label, family = f['raw'], f['raw']['memberLabel'], f['familyKey']
        roster = result['families'].setdefault(family, {'members': {}, 'sourceFamilyLabel': raw['familyLabel']})
        roster['members'][label] = f['memberKey']
    for f in facts:
        raw, label = f['raw'], f['raw']['memberLabel']
        # Multiple literal values may agree semantically. Do not suppress a
        # corrected value because an older, rejected claim still exists.
        issues = set(f['issues']) - {'MULTIPLE_VALUES_REQUIRE_RECONCILIATION', 'TARGET_ALLELE_LINK_NOT_EXPLICIT_IN_CITATIONS'}
        audit = f.get('sourceAudit', {})
        if f.get('verification') == 'REJECTED' or issues or audit.get('verdict') != 'SUPPORTED':
            result['unresolved'].append({'claimId': f['claimId'], 'reasonCode': 'PP1_SOURCE_AUDIT_UNRESOLVED',
                'fieldId': raw['fieldId'], 'familyKey': f['familyKey'], 'memberKey': f['memberKey'],
                'memberLabel': label, 'sourceValue': raw['sourceValue'],
                'reviewStatus': 'PENDING_ADDITION_REVIEW' if f.get('sourceSupport') == 'ADDITION_REQUIRES_AUDIT'
                    and f.get('verification') != 'REJECTED' else 'UNRESOLVED_OR_REJECTED',
                'issues': sorted(issues), 'sourceRefs': f['sourceRefs']}); continue
        if label == 'FAMILY':
            prior=len(result['observations'])
            for ref in f['sourceRefs']:
                q = ref['quote']
                if raw['fieldId']=='F04.1':
                    for person,kind,value,proof in local_subject_facts(units[ref['unitId']]['text'],q,roster_members := result['families'][f['familyKey']]['members']):
                        if kind!='proband' or counts.get(person)!=1:continue
                        bound=deepcopy(f);bound['memberKey']=roster_members[person];bound['raw']['memberLabel']=person
                        emit(bound,kind,value,[ref]+[{'unitId':ref['unitId'],'quote':s,'meaning':'EXPLICIT_INDEX_PERSON_IN_FAMILY_DESCRIPTION'} for s in proof])
                    if formal_ascertainment(units[ref['unitId']]['text'],q,raw['familyLabel'],family=True):
                        emit(f,'formalAscertainment','PHENOTYPE_BEFORE_GENOTYPING',[ref])
                family_name = word(raw['familyLabel'])
                if raw['fieldId'] == 'F02.5':
                    m=re.search(rf'Only one candidate (?:variant|genotype) was identified in family {family_name}\b', q)
                    if m and assertive(units[ref['unitId']]['text'],q,m.start()):
                        emit(f, 'candidateScope', 'SINGLE_REPORTED_CANDIDATE', [ref])
                if raw['fieldId'] == 'F06.5':
                    m=re.search(rf'Candidate assessment in family {family_name} used ([^.;]+)',q)
                    if m and assertive(units[ref['unitId']]['text'],q,m.start()):
                        emit(f,'candidateMethod',m[1].strip(),[ref])
                if raw['fieldId'].startswith('F09.') or raw['fieldId']=='F07.3':
                    emit(f, 'caution', raw['sourceValue'], [ref])
                    if raw['fieldId']=='F07.3' and explicit_consanguinity(
                            units[ref['unitId']]['text'],q,raw['sourceValue'],raw['familyLabel'],family=True):
                        emit(f,'consanguinity',True,[ref])
            if len(result['observations'])==prior and raw['fieldId']=='F02.5':
                emit(f,'candidateScopeUnresolved',raw['sourceValue'],f['sourceRefs'])
            elif len(result['observations'])==prior and raw['fieldId']=='F04.1':
                result['unresolved'].append({'claimId':f['claimId'],'reasonCode':'PP1_TYPED_SEMANTICS_UNRESOLVED',
                    'fieldId':raw['fieldId'],'familyKey':f['familyKey'],'memberKey':f['memberKey'],
                    'memberLabel':label,'sourceValue':raw['sourceValue'],'sourceRefs':f['sourceRefs']})
            continue
        if counts[label] != 1:
            result['unresolved'].append({'claimId': f['claimId'], 'reasonCode': 'PP1_SUBJECT_SCOPE_UNRESOLVED',
                'sourceRefs': f['sourceRefs']}); continue
        before = len(result['observations'])
        member = word(label)
        for ref in f['sourceRefs']:
            quote = ref['quote']; refs = [ref]
            allowed_kinds={'F04.2':{'proband'},'F05.2':{'diagnosis'},'F05.5':{'sex'}}.get(raw['fieldId'],set())
            for person,kind,value,proof in local_subject_facts(units[ref['unitId']]['text'],quote,result['families'][f['familyKey']]['members']):
                if person==label and kind in allowed_kinds:
                    emit(f,kind,value,refs+[{'unitId':ref['unitId'],'quote':s,'meaning':'LOCAL_EXPLICIT_SUBJECT_CHAIN'} for s in proof])
            def asserted(pattern, flags=0):
                return [m for m in re.finditer(pattern,quote,flags)
                        if assertive(units[ref['unitId']]['text'],quote,m.start())]
            if raw['fieldId']=='F04.1' and formal_ascertainment(units[ref['unitId']]['text'],quote,label):
                emit(f,'formalAscertainment','PHENOTYPE_BEFORE_GENOTYPING',refs)
            # Actual row/header coordinates, not model-imagined column meaning.
            if raw['fieldId'] in {'F05.2','F05.3','F05.4'}:
                for table in source['main']['tables']:
                    headers = {c['sourceCellIndex']:c for r in table['rows'] for c in r['cells'] if c.get('header')}
                    person_cols = [i for i,c in headers.items() if c['text'].strip().lower() in {'family member','individual','individual id','patient id'}]
                    if len(person_cols)!=1: continue
                    for row in table['rows']:
                        cells = {c['sourceCellIndex']:c for c in row['cells']}
                        if any(c.get('rowspan',1)!=1 or c.get('colspan',1)!=1 for c in row['cells']): continue
                        person = cells.get(person_cols[0])
                        if not person or person['text'].strip()!=label: continue
                        for index,cell in cells.items():
                            header=headers.get(index)
                            if cell.get('unitId')!=ref['unitId'] or not header: continue
                            proof=[ref]+[{'unitId':c['unitId'],'quote':c['text']} for c in (person,header,headers[person_cols[0]])]
                            if not all(r['unitId'] in reviewed for r in proof): continue
                            heading = header['text'].strip().lower()
                            if raw['fieldId']=='F05.4' and re.fullmatch(
                                    r'age at (?:last )?(?:clinical examination|examination|follow-up|clinical assessment|clinical evaluation)(?: \(years\))?',heading):
                                age = re.fullmatch(r'(\d+)(?: (?:years|years old|y\.o\.))?',cell['text'].strip())
                                if age and ('(years)' in heading or ' ' in cell['text'].strip()):
                                    emit(f,'examinationAge',int(age[1]),proof)
                            if heading!='phenotype': continue
                            tokens=[v.strip() for v in cell['text'].split(',')]
                            for token in tokens:
                                match=re.fullmatch(r'(\d+) (?:y\.o\.|years old)',token)
                                if match: emit(f,'age',int(match[1]),proof)
                            if 'healthy' in tokens: emit(f,'clinical','UNAFFECTED',proof)
                            terms=[v for v in tokens if v and v!='healthy' and not re.fullmatch(r'\d+ (?:y\.o\.|years old)',v)]
                            if terms: emit(f,'nativePhenotypes',terms,proof)
            # Quote validity was already independently replayed. No substring
            # containing a different subject may lend that subject's result.
            if raw['fieldId'] in {'F06.3', 'F06.4'} and result['identity']:
                unit_text = units[ref['unitId']]['text']
                changes = set(re.findall(r'c\.[0-9]+(?:[+-][0-9]+)?[ACGT]>[ACGT]', unit_text))
                if coding[0] not in quote and changes != {coding[0]}:
                    continue
                roster = result['families'][f['familyKey']]['members']
                resolved_transcript = result['identity'].get('transcript')
                if selection['inheritance'] == 'AD' and resolved_transcript:
                    other_members = [name for name in roster if name not in {label, 'FAMILY'}
                                     and counts.get(name) == 1]
                    for parent in target_allele_origin(unit_text, quote, label, other_members,
                                                       resolved_transcript, coding[0]):
                        emit(f, 'alleleOriginParent', roster[parent], refs)
                change = re.escape(coding[0])
                variant = r'(?:NM_\d+\.\d+:)?' + change
                # Explicit lists in the single positive/negative result clause.
                patterns = [
                    (rf'confirmed {variant} in (?:the genome of )?(?:patients? |individuals? )?(?P<subjects>[^.;]+)', 'CARRIER'),
                    (rf'(?:a )?wildtype sequence in (?P<subjects>[^.;]+)', 'NONCARRIER'),
                    (rf'(?P<subjects>{member}) (?:is|was|are|were) heterozygous for (?:\w+ )?{variant}', 'HETEROZYGOUS'),
                    (rf'(?P<subjects>{member}) (?:is|was|are|were) homozygous for (?:\w+ )?{variant}', 'HOMOZYGOUS'),
                    (rf'(?P<subjects>{member}) (?:is|was|are|were) hemizygous for (?:\w+ )?{variant}', 'HEMIZYGOUS'),
                    (rf'(?P<subjects>{member}) (?:has|had|carries|carried) (?:the )?(?:variant )?{variant}', 'CARRIER'),
                    (rf'No (?:\w+ )?{variant} was detected in (?P<subjects>[^.;]+)', 'NONCARRIER')]
                for pattern, value in patterns:
                    for m in asserted(pattern):
                        subjects = m['subjects']
                        # Stop at the opposing result of the same sentence.
                        subjects = re.split(r' and (?:a |the )?(?:wildtype|variant|mutant) ', subjects)[0]
                        allowed = sorted(result['families'][f['familyKey']]['members'], key=len, reverse=True)
                        ids_pattern = '(?:' + '|'.join(re.escape(x) for x in allowed) + ')'
                        if re.fullmatch(ids_pattern + r'(?:(?:,\s*(?:and )?| and )' + ids_pattern + r')*', subjects.strip()) \
                                and re.search(member, subjects):
                            emit(f, 'genotype', value, refs)
                for labels, same in named_carrier_results(unit_text, quote, coding[0],
                                                          result['families'][f['familyKey']]['members']):
                    if label not in labels:
                        continue
                    proof = refs
                    if same:
                        proof = refs + [{'unitId': ref['unitId'], 'quote': unit_text,
                                         'meaning': 'SINGLE_VARIANT_IN_SAME_UNIT_NOT_PROTEIN_ONLY'}]
                    emit(f, 'genotype', 'CARRIER', proof)
            if raw['fieldId'] == 'F05.2':
                for m in asserted(rf'{member}\)? (?:is|was|has been) (?:diagnosed with|affected with) ([^.;]+)'):
                    emit(f, 'diagnosis', m[1].strip(), refs)
                if asserted(rf'{member}\)? (?:is|was) (?:clinically )?(?:unaffected|healthy)\b'):
                    emit(f, 'clinical', 'UNAFFECTED', refs)
            if raw['fieldId'] in {'F05.2', 'F05.3'}:
                for kind, value in hht_source_facts(member, units[ref['unitId']]['text'], quote):
                    emit(f, kind, value, refs)
                for m in asserted(rf'{member}\)? (?:fulfilled|met|meets|fulfills|fulfils) (?:the )?([^.;]+? criteria)\b'):
                    if re.search(r'\b(no|not|never|neither|possible|suspected)\b', m[0], re.I): continue
                    # Preserve the author's explicit diagnostic-criteria claim.
                    # Mapping it to this disease's affected status requires
                    # qualified knowledge, not an LLM or an implicit synonym.
                    emit(f, 'reportedDiagnosticCriteria', m[1].strip(), refs)
            if raw['fieldId'] == 'F05.4':
                for m in asserted(rf'{member}\)? (?:is|was|aged) (\d+) years old'):
                    emit(f, 'age', int(m[1]), refs)
                for age in examination_ages(units[ref['unitId']]['text'],quote,label):
                    emit(f,'examinationAge',age,refs)
            if raw['fieldId'] == 'F05.5':
                for sex in ('male', 'female'):
                    if asserted(rf'{member}\)? (?:is|was) (?:a )?{sex}\b'): emit(f, 'sex', sex.upper(), refs)
            if raw['fieldId'] == 'F07.1':
                for relative, kind, other, antecedent, statement in local_kinship(
                        units[ref['unitId']]['text'], quote, result['families'][f['familyKey']]['members']):
                    if relative == label and counts.get(other) == 1:
                        emit(f, kind, result['families'][f['familyKey']]['members'][other], refs + [
                            {'unitId':ref['unitId'],'quote':antecedent,'meaning':'UNIQUE_PRECEDING_NAMED_SUBJECT'},
                            {'unitId':ref['unitId'],'quote':statement,'meaning':'EXPLICIT_KINSHIP_NOT_INFERRED_INTERMEDIATE'}])
                for other, other_key in result['families'][f['familyKey']]['members'].items():
                    if other == label: continue
                    op = word(other)
                    if asserted(rf'{member} (?:is|was) the (?:father|mother|parent) of {op}'):
                        emit(f, 'parentOf', other_key, refs)
                    if asserted(rf'{member} (?:is|was) the (?:son|daughter|child) of {op}'):
                        emit(f, 'childOf', other_key, refs)
                    if asserted(rf'{member} (?:is|was) (?:the |a )?full (?:sibling|brother|sister) of {op}'):
                        emit(f, 'fullSiblingOf', other_key, refs)
            if raw['fieldId'] == 'F08.1' and result['identity']:
                pattern = rf'{member} (?:has|had) (NM_\d+\.\d+):(c\.[0-9]+[ACGT]>[ACGT]) in trans with (NM_\d+\.\d+):(c\.[0-9]+[ACGT]>[ACGT])'
                for m in asserted(pattern):
                    try:
                        alleles = [resolver.resolve_coding(selection['geneSymbol'], m[i+1], m[i])['allele'] for i in (1,3)]
                        require(selection['allele'] in alleles and alleles[0] != alleles[1], 'PHASE_ALLELES_MISMATCH')
                        emit(f, 'biallelic', {'alleles':sorted(alleles,key=lambda a:canonical(a)),
                            'phase':'IN_TRANS','basis':'EXPLICIT_SOURCE_PHASE_AND_LOCAL_IDENTITIES'}, refs)
                    except (ValueError,OSError):
                        result['unresolved'].append({'claimId':f['claimId'],'reasonCode':'PP1_PARTNER_IDENTITY_UNRESOLVED','sourceRefs':refs})
            if raw['fieldId'] == 'F04.2':
                roles = r'(?:proband|index patient|index case)'
                # The semantic ascertainment role matters, not one mandatory
                # literal word. A referral/test/first mention alone is weaker.
                patterns = [rf'{roles} \(?{member}\)?',
                            rf'{member}\)? (?:is|was) the {roles}\b',
                            rf'(?:family|pedigree) was ascertained through (?:the )?(?:(?:patient|individual) )?\(?{member}\)?']
                if any(asserted(p, re.I) for p in patterns):
                    emit(f, 'proband', True, refs)
            if raw['fieldId'].startswith('F09.') or raw['fieldId']=='F07.3':
                emit(f, 'caution', raw['sourceValue'], refs)
                if raw['fieldId']=='F07.3' and explicit_consanguinity(
                        units[ref['unitId']]['text'],quote,raw['sourceValue'],label):
                    emit(f,'consanguinity',True,refs)
        if len(result['observations']) == before:
            result['unresolved'].append({'claimId': f['claimId'], 'reasonCode': 'PP1_TYPED_SEMANTICS_UNRESOLVED',
                'fieldId': raw['fieldId'], 'familyKey':f['familyKey'],'memberKey':f['memberKey'],
                'memberLabel':label,'sourceValue':raw['sourceValue'],'sourceRefs': f['sourceRefs']})
    unique = {}
    for row in result['observations']:
        if row['id'] not in unique: unique[row['id']] = row
        else:
            old = unique[row['id']]
            old['claimIds'] = sorted(set(old['claimIds'] + row['claimIds']))
            old['sourceRefs'] = list({digest(canonical(q)): q for q in old['sourceRefs'] + row['sourceRefs']}.values())
    result['observations'] = list(unique.values())
    # Unit IDs are local to a paper/attachment, not globally unique. Bind every
    # displayed proof to its actual source before cross-paper aggregation.
    for row in result['observations'] + result['unresolved'] + result['identityEvidence'] + ([result['identity']] if result['identity'] else []):
        if 'sourceRefs' in row:
            row['sourceRefs'] = [{**ref, 'pmcid': source['pmcid'], 'bundleSha256': source['bundleSha256']}
                                 for ref in row['sourceRefs']]
    if result['identity'] and selection['inheritance'] == 'AD':
        relationships = family_relationships(source, reviewed, result['families'])
        if relationships:
            result['familyRelationshipEvidence'] = relationships
    # Formal source admission reviews the complete supplied context, rather
    # than reusing one supported phenotype-first sentence as proof that later
    # sampling, testing and reporting are genotype independent.
    from pp1_formal_ascertainment import review_ascertainment
    review = review_ascertainment(source, inventory, selection)
    result['ascertainmentReview'] = review
    from pp1_formal_observations import qualify_assessments
    result['clinicalAssessments'] = qualify_assessments(source, inventory, selection)
    result['observations'] = [r for r in result['observations'] if r['kind'] != 'formalAscertainment']
    accepted = set()
    for family, mechanism in review['families'].items():
        if mechanism['status'] != 'QUALIFIED_SUFFICIENT_CONDITION':
            continue
        for condition in mechanism['sufficientConditions']:
            label = condition['subjectLabel']
            member = next((f['memberKey'] for f in facts if f['familyKey'] == family
                           and f['raw']['memberLabel'] == label), None)
            if member is None:
                continue
            row = {'familyKey': family, 'memberKey': member, 'memberLabel': label,
                   'kind': 'formalAscertainment', 'value': condition['sourceBasis'],
                   'claimIds': condition['claimIds'], 'sourceRefs': condition['sourceRefs'],
                   'method': 'COMPLETE_SOURCE_MECHANISM_REVIEW'}
            row['id'] = digest(canonical([row[k] for k in ('familyKey','memberKey','kind','value')]))
            result['observations'].append(row)
            accepted.update(condition['claimIds'])
    result['unresolved'] = [r for r in result['unresolved']
        if not (r.get('fieldId') == 'F04.1' and r.get('reasonCode') == 'PP1_TYPED_SEMANTICS_UNRESOLVED'
                and r.get('claimId') in accepted)]
    return result


def deletion_identity(source, reviewed, selection, coding, tx, resolver):
    """A native table cell must pair coding and genomic deletion coordinates."""
    require(selection['allele']['assembly'] == 'GRCh38', 'SOURCE_DELETION_ASSEMBLY_UNSUPPORTED')
    position, refs = native_grch38_table_pair(
        source, reviewed, coding, 'SOURCE_DELETION_GRCH38_PAIR_MISSING_OR_AMBIGUOUS')
    result = resolver.resolve_coding_deletion(selection['geneSymbol'], coding, tx, position)
    result['sourceRefs'] = refs
    return result


def native_grch38_table_pair(source, reviewed, coding, missing_reason):
    original_sha = source['main']['sourceSha256']
    units = [unit for unit in source['units'] if unit.get('sourceSha256') == original_sha]
    captions = {u['unitId'].rsplit('/', 1)[0]: u for u in units
                if re.search(r'/table-wrap\[\d+\]/caption\[1\]$', u['unitId'])
                and re.search(r'\bgenomic coordinates\b.*\bGRCh38\b', u['text'], re.I)
                and not re.search(r'\b(?:GRCh37|hg19)\b', u['text'], re.I)}
    rows = []
    for u in units:
        if u['unitId'] not in reviewed or coding not in u['text']:
            continue
        table = re.match(r'(.+/table-wrap\[\d+\])/table\[\d+\]/', u['unitId'])
        if not table or table[1] not in captions:
            continue
        changes = set(re.findall(r'(?<!\w)c\.[^\s,;()]+', u['text']))
        coordinates = re.findall(r'(?<!\w)g\.([1-9][0-9]*)(?=\s*[,;])', u['text'])
        if changes == {coding} and len(coordinates) == 1 and assertive(u['text'], u['text'], u['text'].index(coding)):
            rows.append((int(coordinates[0]), u, captions[table[1]]))
    require(rows and len({position for position, _, _ in rows}) == 1, missing_reason)
    return rows[0][0], [{'unitId': u['unitId'], 'quote': u['text'], 'sourceSha256': original_sha}
                        for _, cell, caption in rows for u in (cell, caption)]


def genomic_identity(source, reviewed, selection, coding, resolver):
    """Exact source paired GRCh38 genomic SNV + current reference, not liftover."""
    matches=[]
    for u in source['units']:
        if u['unitId'] not in reviewed or coding not in u['text'] or selection['geneSymbol'] not in u['text']:continue
        if set(re.findall(r'c\.[0-9]+(?:[+-][0-9]+)?[ACGT]>[ACGT]',u['text']))!={coding}:continue
        for m in re.finditer(r'g\.hg38\.chr([0-9]+|X|Y):([1-9][0-9]*)([ACGT])>([ACGT])',u['text']):
            if not assertive(u['text'],u['text'],m.start()):continue
            allele={'assembly':'GRCh38','contig':m[1],'position':int(m[2]),'reference':m[3],'alternate':m[4]}
            matches.append((allele,{'unitId':u['unitId'],'quote':u['text'],'sourceSha256':u.get('sourceSha256')}))
    require(matches and len({canonical(a) for a,_ in matches})==1,'SOURCE_GENOMIC_PAIR_MISSING_OR_AMBIGUOUS')
    allele=matches[0][0]
    require(allele==selection['allele'] and allele['reference']!=allele['alternate'],'SOURCE_GENOMIC_TARGET_MISMATCH')
    resolver.load()
    require(resolver.base(allele['contig'],allele['position'])==allele['reference'],'SOURCE_GENOMIC_REFERENCE_MISMATCH')
    resolver.reference.recheck()
    return {'status':'LOCAL_IDENTITY_VERIFIED','allele':allele,'geneSymbol':selection['geneSymbol'],
        'method':'EXPLICIT_PAPER_CODING_GENOMIC_PAIR_AND_CURRENT_REFERENCE','coding':coding,
        'sourceRefs':[q for _,q in matches],'referenceIdentity':resolver.reference.identity,
        'referenceReads':list(resolver.reference.observations),
        'scope':'EXACT_REPORTED_GRCH38_GENOMIC_SNV_NOT_INTRONIC_HGVS_DERIVATION'}
