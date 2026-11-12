"""Released affected-only segregation rules with exact allele scope evidence."""
from decimal import Decimal
import re

from core_local_identity import pinned
from model_literature_contract import parse
from pp1_literature_contract import require

ANCHOR = 'AFFECTED_SIBSHIP_CONDITIONAL_REFERENCE'
SHARED_CONDITIONS = (
    'Only genotype positive/phenotype positive individuals are counted as segregations, which can include affected '
    'obligate carriers. Genotype positive/phenotype negative individuals are generally less informative for '
    'cardiomyopathy genes due to variable age at onset and reduced penetrance. Phenotypes should be clinically '
    'confirmed, whenever possible, and should not include individuals with a suspected diagnosis. Important considerations include: '
    '1. Segregation of a variant within a single family or haplotype has the potential to represent linkage '
    'disequilibrium with another undetected variant. If linkage disequilibrium is a concern, consider downgrading '
    'strength of segregation. 2. Use of segregation criteria should be carefully evaluated if variant frequency '
    'meets criteria for BS1. 3. Caution is needed when counting segregations in presence of other possible '
    'disease-causing variants, as both variants may be contributing to the phenotype. 4. Caution is needed '
    'when distantly related (≥3<sup>rd</sup> degree) affected individuals are connected by unknown or unaffected '
    'relatives (raises possibility of multiple causes of disease).'
)
ORDINARY = ('Due to the genotypic and phenotypic heterogeneity of inherited cardiomyopathies, segregation '
            'thresholds have been conservatively set at ')
METHOD_REFERENCE = 'Jarvik and Browning (2016)[<sup>12</sup>](#pmid_27236918)'
ALTERNATE = ('Although rare for inherited cardiomyopathies, when the phenotype/presentation of a variant within '
             'and across families is highly specific (e.g., early-onset severe RCM in all affected individuals), '
             f'the following thresholds as proposed by {METHOD_REFERENCE} can be considered:')


def threshold(text, strength):
    plain = text.replace('**', '')
    start, separator, conditions = plain.partition(' Only genotype positive/')
    require(separator and separator.lstrip() + conditions.replace('BS1 (see below).', 'BS1.') == SHARED_CONDITIONS,
            'PUBLISHED_PP1_AFFECTED_SEGREGATION_CONDITIONS_UNSUPPORTED')
    label = strength.upper()
    base = re.escape(ORDINARY) + rf'≥([1-9][0-9]?) segregations \(LOD score of ([0-9]+\.[0-9]+)\) for {label}\. '
    if strength == 'Supporting':
        tail = (rf'The thresholds as proposed by {re.escape(METHOD_REFERENCE)} are the same at '
                r'≥\1 segregations \(LOD score of \2\) for supporting\.')
    else:
        tail = re.escape(ALTERNATE) + rf' \* {label} evidence requires ≥([1-9][0-9]?) segregations \(LOD score of ([0-9]+\.[0-9]+)\)'
    match = re.fullmatch(base + tail, start)
    require(match, 'PUBLISHED_PP1_AFFECTED_SEGREGATION_THRESHOLD_UNSUPPORTED')
    row = {'minimum': int(match[1]), 'maximum': None, 'minimumIndependentFamilies': 1, 'sourceText': text}
    if strength != 'Supporting':
        require(int(match[3]) < row['minimum'], 'PUBLISHED_PP1_AFFECTED_SEGREGATION_THRESHOLD_INVALID')
        row['highlySpecificMinimum'] = int(match[3])
    return row


def resolve_binding(root, pin, selection):
    if not pin:
        return None
    manifest = parse(pinned(root, pin, 1048576))
    require(set(manifest) == {'schemaVersion', 'entries'}
            and manifest['schemaVersion'] == 'pp1-released-allele-scope-v1'
            and isinstance(manifest['entries'], list) and len(manifest['entries']) <= 64,
            'PUBLISHED_PP1_ALLELE_SCOPE_MANIFEST_INVALID')
    scope = {k: selection[k] for k in ('geneSymbol', 'diseaseId', 'inheritance', 'allele')}
    matches = [row for row in manifest['entries'] if row.get('scope') == scope]
    require(len(matches) <= 1, 'PUBLISHED_PP1_ALLELE_SCOPE_CONFLICT')
    if not matches:
        return None
    row, = matches
    require(set(row) == {'scope', 'release', 'expertRecord', 'frequency', 'attachments'},
            'PUBLISHED_PP1_ALLELE_SCOPE_ENTRY_INVALID')
    original = parse(pinned(root, row['release'], 2097152))['data']
    expert = parse(pinned(root, row['expertRecord'], 1048576))['data']
    version = original['entContent']['version']
    method = expert['assertionMethod']
    require(method['@id'] == 'https://cspec.genome.network/cspec/SequenceVariantInterpretation/id/' + str(original['ldhId'])
            and method['version'] == version and expert['condition']['id'] == scope['diseaseId'],
            'PUBLISHED_PP1_EXPERT_SCOPE_RELEASE_MISMATCH')
    preferred = [name['name'] for alias in expert['variant']['relatedContextualAllele'] if alias.get('preferred') is True
                 for name in alias['alleleName']]
    require(len(preferred) == 1 and re.fullmatch(r'NM_[0-9]+\.[1-9][0-9]*:c\.[1-9][0-9]*[ACGT]>[ACGT]', preferred[0]),
            'PUBLISHED_PP1_EXPERT_TRANSCRIPT_UNRESOLVED')
    tx, coding = preferred[0].split(':')
    source = {'rawRef': 'published-source.json', 'rawSha256': row['release']['sha256'], 'jsonPointer': '/data',
              'sourceUrl': method['@id']}
    spec = {'specificationKey': original['entId'] + '@' + version, 'original': original, 'source': source}
    frequency = parse(pinned(root, row['frequency'], 65536))
    allele = scope['allele']
    variant_id = '-'.join(str(allele[k]) for k in ('contig', 'position', 'reference', 'alternate'))
    query = frequency['query']
    response = frequency['response']['data']['variant']
    require(allele['assembly'] == 'GRCh38' and frequency['url'] == 'https://gnomad.broadinstitute.org/api'
            and f'variantId: "{variant_id}", dataset: gnomad_r4' in query and response['variant_id'] == variant_id,
            'PUBLISHED_PP1_BS1_FREQUENCY_ALLELE_MISMATCH')
    joint = response['joint']
    observed = Decimal(str(joint['faf95']['popmax']))
    require(observed.is_finite() and 0 <= observed <= 1 and type(joint['an']) is int and joint['an'] > 0,
            'PUBLISHED_PP1_BS1_FREQUENCY_UNRESOLVED')
    files = original.get('ld', {}).get('File', []) + [
        item for ruleset in original['ld']['RuleSet'] for item in ruleset.get('ld', {}).get('File', [])]
    attachment_pins = row['attachments']
    require(len(files) == len(attachment_pins), 'PUBLISHED_PP1_ATTACHMENT_SCOPE_UNRESOLVED')
    verified_attachments = []
    for attachment in files:
        content, key = attachment['entContent'], attachment['entId']
        require(re.search(r'\bPS4\b', content['fileLabel']) and content['type'] == 'pdf'
                and content['description'] == 'Three example scenarios for application of the new PS4 specification.',
                'PUBLISHED_PP1_ATTACHMENT_SCOPE_UNRESOLVED')
        data = pinned(root, attachment_pins[key], 4 * 1024 * 1024)
        require(data.startswith(b'%PDF-') and len(data) == content['size'], 'PUBLISHED_PP1_ATTACHMENT_INVALID')
        verified_attachments.append({'fileId': key, 'criterion': 'PS4', 'sha256': attachment_pins[key]['sha256']})
    return spec, {'scope': scope, 'transcript': tx, 'coding': coding, 'method': method['@id'],
                  'expertRecordSha256': row['expertRecord']['sha256'], 'expertRecordId': expert['uuid'],
                  'frequency': {'dataset': 'gnomad_r4', 'variantId': variant_id, 'faf95': str(observed),
                                'population': joint['faf95']['popmax_population'], 'alleleNumber': joint['an'],
                                'sourceSha256': row['frequency']['sha256']},
                  'otherCriterionAttachments': verified_attachments}


def bs1_check(original, binding):
    scope = binding['scope']
    matches = [(index, node['entContent']) for index, node in enumerate(original['ld']['CriteriaCode'])
               if node['entContent'].get('label') == 'BS1' and node['entContent'].get('gene') == [scope['geneSymbol']]
               and scope['diseaseId'] in node['entContent'].get('disease', [])]
    require(len(matches) == 1, 'PUBLISHED_PP1_BS1_CLAUSE_UNRESOLVED')
    index, clause = matches[0]
    active = [row for row in clause['strengthDescriptor'] if row['applicability'] == 'Applicable']
    require(len(active) == 1 and active[0]['strength'] == 'Strong' and active[0]['status'] == 'approved',
            'PUBLISHED_PP1_BS1_CLAUSE_UNRESOLVED')
    text = ' '.join(active[0]['text'].replace('**', '').replace('_', '').split())
    start = (r'Allele frequency is ≥(0\.[0-9]+) for ' + re.escape(scope['geneSymbol'])
             + r' based on the filtering allele frequency \(FAF\) in gnomAD in the subpopulation with the highest frequency \(popmax\)\.')
    match = re.match(start, text)
    require(match, 'PUBLISHED_PP1_BS1_CLAUSE_UNRESOLVED')
    require(Decimal(binding['frequency']['faf95']) < Decimal(match[1]), 'PUBLISHED_PP1_BS1_REVIEW_REQUIRED')
    return {'status': 'BELOW_PUBLISHED_BS1_FAF_THRESHOLD', 'minimum': match[1],
            'clausePointer': f'/ld/CriteriaCode/{index}', **binding['frequency']}
