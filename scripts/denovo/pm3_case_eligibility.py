"""Compile reviewed published case conditions and bind them to source facts.

The release manifest transcribes supported condition forms. It supplies neither
a diagnosis nor a case result. New or changed releases need their own review.
"""
from pathlib import Path
import re

from core_local_identity import pinned
from model_literature_contract import canonical, digest, parse
from prepare_applicability_sources import extract_entity, pointer, scopes


FAMILY = 'SVI_WITH_RELEASED_CASE_ELIGIBILITY'
RULES = Path(__file__).resolve().parents[2] / 'varvis-server/src/main/resources/acmg/pm3-published-case-rules.json'
METHOD = 'RELEASED_PHENOTYPE_ELIGIBILITY_NOT_DIAGNOSIS'
RAF = 'JOINT_POPMAX_FAF95_INCLUSIVE'
RELEASE_INDEX_UNAVAILABLE = 'PM3_PUBLISHED_RELEASE_INDEX_UNAVAILABLE'
RELEASE_SOURCE_UNAVAILABLE = 'PM3_PUBLISHED_RELEASE_SOURCE_UNAVAILABLE'
ATTACHMENT_UNAVAILABLE = 'PM3_PUBLISHED_ATTACHMENT_ORIGINAL_UNAVAILABLE'


def reviewed_releases(root, index_pin):
    """Load reviewed releases independently and retain the failing input for each."""
    registry = parse(RULES.read_bytes())
    if registry.get('schemaVersion') != 'pm3-published-case-rules-v1':
        raise ValueError('PM3_PUBLISHED_CASE_RULE_REGISTRY_INVALID')
    rules = registry['rules']
    try:
        index = parse(pinned(root, index_pin, 16 * 1024 * 1024))
        index_root = (root / index_pin['path']).parent.resolve(strict=True)
        if (index.get('schemaVersion') != 'varvis-clingen-cspec-source-library-v1'
                or not isinstance(index.get('specifications'), dict)
                or not isinstance(index.get('attachmentFiles'), dict)):
            raise ValueError(RELEASE_INDEX_UNAVAILABLE)
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        return {}, {rule['specificationKey']: RELEASE_INDEX_UNAVAILABLE for rule in rules}

    def read(item, limit):
        if Path(item['file']).is_absolute():
            raise ValueError('PM3_PUBLISHED_RELEASE_RELATIVE_PATH_REQUIRED')
        path = (index_root / item['file']).resolve(strict=True)
        if not path.is_relative_to(index_root):
            raise ValueError('PM3_PUBLISHED_RELEASE_PATH_OUTSIDE_LIBRARY')
        return pinned(root, {'path': path.relative_to(root).as_posix(), 'sha256': item['sha256']}, limit)

    released, issues = {}, {}
    for rule in rules:
        key = rule['specificationKey']
        try:
            specification, raw = release_document(index['specifications'][key], rule, index_pin, read)
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            issues[key] = RELEASE_SOURCE_UNAVAILABLE
            continue
        try:
            attachments, bodies = release_attachments(specification['original'], rule, index['attachmentFiles'], read)
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            issues[key] = ATTACHMENT_UNAVAILABLE
            continue
        released[key] = {**specification, 'specificationKey': key, 'reviewedCaseRule': rule,
                         'reviewedAttachments': attachments, 'originalBytes': raw, 'attachmentBytes': bodies}
    return released, issues


def release_document(entry, rule, index_pin, read):
    release = entry['releaseSource']
    if (release['sha256'] != rule['releaseSha256'] or release['apiVersion'] != rule['apiVersion']
            or release['jsonPointer'] != '/data'
            or release['url'] != ('https://cspec.genome.network/cspec/SequenceVariantInterpretation/id/'
                                  + entry['specificationId'] + '/version/' + rule['apiVersion'] + '?detail=high')):
        raise ValueError('PM3_PUBLISHED_RELEASE_INDEX_BINDING_MISMATCH')
    raw = read(release, 8_000_000)
    original = pointer(parse(raw), release['jsonPointer'])
    if (original['entId'] + '@' + entry['version'] != rule['specificationKey']
            or original['entContent']['version'] != rule['apiVersion']
            or re.sub(r'(?:\.0)+$', '', original['entContent']['version']) != re.sub(r'(?:\.0)+$', '', entry['version'])):
        raise ValueError('PM3_PUBLISHED_RELEASE_VERSION_MISMATCH')
    source = {'rawSha256': digest(raw), 'jsonPointer': release['jsonPointer'], 'sourceUrl': release['url'],
              'apiVersion': release['apiVersion'], 'doi': entry['officialDoi'],
              'libraryIndexSha256': index_pin['sha256']}
    specification, _ = extract_entity(original, source)
    return specification, raw


def release_attachments(original, rule, files, read):
    attachments, bodies = {}, {}
    for purpose, expected in rule['attachments'].items():
        file_id = expected['fileId']
        attached = pointer(original, expected['pointer'])
        body = files[file_id]
        if (attached['entId'] != file_id or body['sha256'] != expected['sha256']
                or body['officialUrl'] != 'https://cspec.genome.network/cspec/File/id/' + file_id + '/data'
                or attached['entContent']['fileName'] != body['originalName']
                or attached['entContent']['size'] != body['sizeBytes']):
            raise ValueError('PM3_PUBLISHED_ATTACHMENT_BINDING_MISMATCH')
        pdf = read(body, 2_000_000)
        if len(pdf) != body['sizeBytes'] or not pdf.startswith(b'%PDF-'):
            raise ValueError('PM3_PUBLISHED_ATTACHMENT_ORIGINAL_INVALID')
        attachments[purpose] = {**expected, 'name': body['originalName'], 'url': body['officialUrl'],
                                'revision': attached['rev']}
        bodies[expected['sha256']] = pdf
    return attachments, bodies


def published_rarity(spec):
    rule = spec['reviewedCaseRule']
    clause = pointer(spec['original'], rule['pm2Pointer'])['entContent']
    found = []
    for i, desc in enumerate(clause['strengthDescriptor']):
        match = re.fullmatch(
            r'Absent/rare from controls in an ethnically-matched cohort population sample\. '
            r'\* Used if the gnomAD PopMax Filtering Allele Frequency \(FAF\) is ≤ '
            r'(?P<coefficient>[0-9]+(?:\.[0-9]+)?) x 10<sup>(?P<exponent>-[0-9]+)</sup>\.',
            ' '.join(desc['text'].split()))
        if (clause['label'] == 'PM2' and clause['applicability'] == 'Applicable'
                and desc['applicability'] == 'Applicable' and desc['strength'] == 'Supporting' and match):
            from decimal import Decimal
            limit = Decimal(match['coefficient']).scaleb(int(match['exponent']))
            found.append({'clausePointer': f'{rule["pm2Pointer"]}/entContent/strengthDescriptor/{i}',
                          'clauseSha256': digest(canonical(desc)), 'method': RAF,
                          'maximumAlleleFrequency': format(limit.normalize(), 'f')})
    if len(found) != 1:
        raise ValueError('PM3_PUBLISHED_JOINT_RARITY_CLAUSE_MISMATCH')
    return found[0]


def transcript_bindings(row, scope, resolver):
    """Re-read each coding allele on the exact rule transcript; retain both names."""
    source = scope['rawGeneAssociation']
    transcripts = set(source.get('transcripts', [])) | ({source['preferredTranscript']} if source.get('preferredTranscript') else set())
    expected_roles = {'target'} if row['reportedObservation']['kind'] == 'REPORTED_HOMOZYGOUS' else {'target', 'partner'}
    if len(transcripts) != 1 or set(row.get('identities', {})) != expected_roles or row.get('status') != 'LOCAL_IDENTITIES_VERIFIED':
        return None
    transcript, = transcripts
    result = {}
    for role, identity in row['identities'].items():
        reported = identity.get('transcript', '')
        if not re.fullmatch(r'NM_[0-9]+\.[1-9][0-9]*', reported) or not re.fullmatch(r'NM_[0-9]+\.[1-9][0-9]*', transcript):
            return None
        if (identity.get('hgncId') != scope['scope']['hgncId'] or identity.get('geneSymbol') != scope['geneSymbol']
                or identity.get('status') != 'LOCAL_IDENTITY_VERIFIED'):
            return None
        if reported == transcript:
            checked, method = identity, 'EXACT_SOURCE_TRANSCRIPT'
        elif resolver is not None and reported.split('.')[0] == transcript.split('.')[0]:
            try:
                checked = resolver.resolve_coding(identity['geneSymbol'], identity['coding'], transcript, identity['proteinChange'])
            except (ValueError, OSError, KeyError):
                return None
            method = 'BOTH_VERSIONS_RESOLVE_TO_EXACT_GRCH38_ALLELE'
        else:
            return None
        if any(checked.get(k) != identity.get(k) for k in ('allele', 'hgncId', 'geneSymbol', 'coding', 'proteinChange')):
            return None
        result[role] = {'method': method, 'reportedTranscript': reported, 'ruleTranscript': transcript,
                        'coding': checked['coding'], 'allele': checked['allele'],
                        'sourcePins': {name: {k: ref[k] for k in ('sha256', 'url')} for name, ref in checked.get('mappingSources', {}).items()},
                        'gencodeRecordSha256': checked.get('gencodeRecordSha256')}
    return result


def same_row(fact, anchor):
    citation = fact.get('citation', {})
    cell, source_cell = re.match(r'R([1-9][0-9]*)C[1-9][0-9]*$', citation.get('cell', '')), re.match(
        r'R([1-9][0-9]*)C[1-9][0-9]*$', anchor.get('cell', ''))
    return (cell and source_cell and cell[1] == source_cell[1]
            and citation.get('sourceKind') == anchor.get('sourceKind') == 'SUPPLEMENT_CELL'
            and all(citation.get(key) and citation[key] == anchor.get(key) for key in ('bundleSha256', 'sourceSha256', 'sheet'))
            and citation.get('quote') == fact.get('value'))


# Bounded observations, not fuzzy synonyms or inferred absence.
ROD = 'ABSENT_OR_SEVERELY_DECREASED_ROD_ERG'
NIGHT = 'CONGENITAL_NYCTALOPIA_OR_NIGHT_BLINDNESS'
DIAGNOSIS = 'LCA_OR_EARLY_ONSET_RETINAL_DYSTROPHY'
PHENOTYPES = (ROD, NIGHT, DIAGNOSIS)
EARLY = {'leber congenital amaurosis', 'leber congenital amaurosis/early onset retinal dystrophy',
         'lca', 'early onset retinal dystrophy', 'early-onset retinal dystrophy', 'eord'}


def phenotype(case, reported):
    findings = {key: [] for key in PHENOTYPES}
    anchors = case.get('anchors', [])
    status = case.get('primarySourceQualification', {}).get('qualification')
    if status == 'VERIFIED' and reported.get('reportedAffectedStatus') == 'AFFECTED':
        for fact in reported['facts']:
            if len([anchor for anchor in anchors if same_row(fact, anchor)]) != 1:
                continue
            field = fact['field']
            heading = fact.get('columnHeading')
            value = ' '.join(fact['value'].casefold().split())
            route, state = None, 'UNKNOWN'
            if field == 'reportedClinicalAssay' and heading in {'fferg', 'rod erg', 'scotopic erg'}:
                route = ROD
                if value in {'non-recordable responses', 'no recordable responses', 'no detectable responses',
                             'absent rod response', 'absent rod responses', 'severely decreased rod response',
                             'severely decreased rod responses'} or (
                        heading != 'fferg' and value in {'absent', 'non-recordable', 'severely decreased'}):
                    state = 'VERIFIED'
                elif value in {'normal responses', 'normal rod response', 'normal rod responses', 'normal'}:
                    state = 'FAILED'
            elif field == 'reportedSymptom':
                route = NIGHT
                if value in {'congenital nyctalopia', 'congenital night blindness', 'present since birth'} or (
                        heading in {'congenital nyctalopia', 'congenital night blindness'} and value in {'yes', 'present'}):
                    state = 'VERIFIED'
                elif value in {'no nyctalopia', 'no night blindness', 'no congenital nyctalopia', 'no congenital night blindness',
                               'absent', 'no'}:
                    state = 'FAILED'
            elif field == 'diagnosis':
                route = DIAGNOSIS
                if value in EARLY:
                    state = 'VERIFIED'
                elif value in {'leber congenital amaurosis and early onset retinal dystrophy excluded',
                               'lca and eord excluded'}:
                    state = 'FAILED'
            if route:
                findings[route].append({'qualification': state, 'fact': fact})
    results = []
    for route, values in findings.items():
        states = {finding['qualification'] for finding in values} - {'UNKNOWN'}
        qualification = 'CONFLICT' if len(states) > 1 else next(iter(states), 'UNKNOWN')
        results.append({'route': route, 'qualification': qualification, 'sourceFacts': values})
    qualifications = {r['qualification'] for r in results}
    result = ('CONFLICT' if 'CONFLICT' in qualifications or status == 'CONFLICT' or reported.get('reportedAffectedStatus') == 'CONFLICT'
              else 'VERIFIED' if 'VERIFIED' in qualifications else 'FAILED' if qualifications == {'FAILED'} else 'UNKNOWN')
    reason = {'VERIFIED': 'PM3_PUBLISHED_CASE_PHENOTYPE_MET', 'FAILED': 'PM3_PUBLISHED_CASE_PHENOTYPE_NOT_MET',
              'UNKNOWN': 'PM3_PUBLISHED_CASE_PHENOTYPE_MISSING', 'CONFLICT': 'PM3_PUBLISHED_CASE_PHENOTYPE_CONFLICT'}[result]
    return {'qualification': result, 'reasonCode': reason, 'method': METHOD, 'routes': results,
            'caseLabel': case['caseLabel']}


def prepare_case_context(row, case, releases, disease_catalog, unavailable=None):
    context = row.get('diseaseContext', {})
    # A positive phenotype can support eligibility within an exact published
    # scope. Do not reinterpret a known or ambiguous diagnosis as a second one.
    if context.get('reasonCode') != 'PM3_CASE_DIAGNOSIS_NOT_MATCHED' or disease_catalog is None:
        return
    target = row.get('identities', {}).get('target', {})
    if target.get('status') != 'LOCAL_IDENTITY_VERIFIED':
        return
    unavailable = unavailable or {}
    candidates = [(spec, scope['scope']) for spec in releases
                  if spec.get('reviewedCaseRule') or spec['specificationKey'] in unavailable
                  for scope in scopes(spec['original'])
                  if scope['scopeStatus'] == 'EXACT' and scope['geneSymbol'] == target.get('geneSymbol')
                  and scope['scope']['hgncId'] == target.get('hgncId') and scope['scope']['moi'] == 'AUTOSOMAL_RECESSIVE']
    if len(candidates) != 1:
        return
    (specification, chosen), = candidates
    matching = [r for r in disease_catalog.rows if r.get('hgncId') == chosen['hgncId']
                and r.get('symbol') == target.get('geneSymbol') and r.get('mondoId') == chosen['mondoId']]
    if not matching or any(r.get('moi') != 'AR' or r.get('classification') not in {'Definitive', 'Strong', 'Moderate'}
                           for r in matching):
        return
    if issue := unavailable.get(specification['specificationKey']):
        row['diseaseContext'] = {**context, 'reasonCode': issue, 'reportedDiagnosisReasonCode': context['reasonCode']}
        return
    proof = phenotype(case, row['reportedObservation'])
    row['diseaseContext'] = {**context, 'diseaseId': chosen['mondoId'], 'inheritance': 'AR',
                             'qualification': proof['qualification'], 'reasonCode': proof['reasonCode'],
                             'scopeBasis': METHOD, 'matchedRelationships': matching}
