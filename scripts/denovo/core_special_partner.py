"""Source-audited A7 partner qualification using the unchanged PM3 parser.

Reported partner status stays in the event. This separate result requires exact
ClinVar originals, disease and a complete target-independent criterion list.
No network, classification recomputation, comparator guessing or caller approval.
"""
from copy import deepcopy
from pathlib import Path
from model_literature_contract import canonical, digest, parse

VERSION = 'denovo-special-partner-qualification-v1'
DEPENDENCIES = ('core_special_partner.py', 'pm3_partner_sources.py', 'pm3_partner_qualification.py',
    'pm3_partner_dependencies.py', 'literature_allele_identity.py', 'core_local_identity.py',
    'core_material_contract.py', 'model_literature_contract.py', 'prepare_literature.py',
    '../ps1_pm5/prepare_reference_evidence.py', '../ps1_pm5/prepare_expert_reference_evidence.py')


def require(condition, code):
    if not condition:
        raise ValueError('DENOVO_SPECIAL_PARTNER_' + code)


def implementation():
    return {name: digest((Path(__file__).parent / name).read_bytes()) for name in DEPENDENCIES}


def binding(document, identity):
    from core_material_contract import evaluation_value
    def value(group, name):
        return evaluation_value('/' + group + '/' + name, document[group][name])
    scope = {key: value('context', key) for key in ('hgncId', 'diseaseId', 'inheritance')}
    gene = evaluation_value('/groups/gene/symbol', document['groups']['gene']['symbol'])
    target = value('context', 'allele')
    valid = (identity.get('status') == 'LOCAL_IDENTITY_VERIFIED' and identity.get('geneSymbol') == gene
             and identity.get('hgncId') == scope['hgncId'] and identity.get('allele') == target
             and value('context', 'geneDiseaseApplicability') == 'APPLICABLE')
    return target, gene, scope, valid


def result(document, identity, source_read, source_records, extra_issues=()):
    from pm3_partner_qualification import qualify
    target, gene, scope, valid = binding(document, identity)
    event = document['extensions']['specialEvent']['value']; partner = event['partner']['allele']
    context = {'qualification': 'VERIFIED' if valid else 'UNRESOLVED', 'diseaseId': scope['diseaseId']}
    qualification = qualify(source_read, context, target=identity)
    issues = list(extra_issues)
    if not valid:
        issues.append('DENOVO_PARTNER_CONTEXT_OR_SOURCE_IDENTITY_UNRESOLVED')
    if partner is None:
        issues.append('DENOVO_PARTNER_ALLELE_REQUIRED')
    elif partner == target:
        issues.append('DENOVO_PARTNER_EQUALS_TARGET')
    verified = (valid and partner is not None and partner != target and not issues
        and source_read.get('status') == 'EXACT_SOURCE_ASSERTIONS_AVAILABLE'
        and qualification.get('independentClassification') == 'VERIFIED'
        and qualification.get('classification') in {'PATHOGENIC', 'LIKELY_PATHOGENIC'}
        and bool(qualification.get('qualifiedScvs')))
    return {'schemaVersion': VERSION, 'status': 'VERIFIED' if verified else 'UNRESOLVED',
        'targetAllele': target, 'partnerAllele': deepcopy(partner), 'scope': scope, 'geneSymbol': gene,
        'sourceRead': source_read, 'qualification': qualification,
        'issues': sorted(set(issues + qualification.get('issues', []))),
        'sourceRecords': source_records, 'implementation': implementation()}


def prepare(root, config, document, identity):
    from pm3_partner_sources import PartnerSources
    if config is None:
        source = {'status': 'NOT_CONFIGURED', 'assertions': [], 'issues': ['DENOVO_PARTNER_SOURCES_NOT_CONFIGURED']}
        return result(document, identity, source, [], source['issues']), []
    require(type(config) is dict and set(config) == {'clinvar'}, 'CONFIG_SHAPE')
    supply = PartnerSources(root, config)
    _, gene, _, _ = binding(document, identity)
    partner = document['extensions']['specialEvent']['value']['partner']['allele']
    source = supply.lookup(partner, gene) if partner is not None else {
        'status': 'NOT_APPLICABLE', 'assertions': [], 'issues': ['DENOVO_PARTNER_ALLELE_REQUIRED']}
    records, originals = [], []
    for parsed, raw, spec in supply.records:
        if parsed['allele'] != partner or parsed['geneSymbol'] != gene:
            continue
        # ClinVar is retained as XML bytes, never a fabricated JSON assertion.
        raw.decode('utf-8-sig')
        sid = 'special-partner-vcv-' + str(len(records))
        records.append({'sourceId': sid, 'sha256': digest(raw), 'accession': spec['accession']})
        originals.append((sid, raw))
    return result(document, identity, source, records), originals


def verify(directory, document, wrapper):
    """Re-run both original parsers for every positive before projection."""
    from pm3_partner_sources import PartnerSources
    from literature_allele_identity import read_record
    from core_material_contract import bounded
    directory = Path(directory)
    identity_spec = document['sources'].get('local-identity')
    require(identity_spec is not None, 'SOURCE_IDENTITY_REQUIRED')
    identity_raw = bounded(directory / identity_spec['path'])
    require(digest(identity_raw) == identity_spec['sha256'], 'SOURCE_IDENTITY_PIN_MISMATCH')
    identity = parse(identity_raw)
    require(wrapper.get('schemaVersion') == VERSION and wrapper.get('implementation') == implementation(), 'IMPLEMENTATION_MISMATCH')
    # An unresolved result cannot authorize scoring. Its reported gaps remain
    # visible even when no readable configured original was available.
    if wrapper.get('status') != 'VERIFIED':
        return wrapper
    supply = PartnerSources(directory, {'clinvar': []})
    records = wrapper.get('sourceRecords')
    require(type(records) is list and records and len(records) <= 100, 'ORIGINALS_REQUIRED')
    for row in records:
        require(type(row) is dict and set(row) == {'sourceId', 'sha256', 'accession'}, 'RECORD_SHAPE')
        metadata = document['sources'].get(row['sourceId'], {})
        require(metadata.get('sha256') == row['sha256'] and metadata.get('role') == 'DERIVED_FACTS'
                and metadata.get('format') == 'XML', 'ORIGINAL_SOURCE_PIN_MISMATCH')
        raw = bounded(directory / metadata['path'], 4 * 1024 * 1024)
        require(digest(raw) == row['sha256'], 'ORIGINAL_SOURCE_PIN_MISMATCH')
        original = read_record(raw, row['sha256'], row['accession'])
        supply.records.append((original, raw, {'sha256': row['sha256'], 'accession': row['accession']}))
    _, gene, _, _ = binding(document, identity)
    source = supply.lookup(document['extensions']['specialEvent']['value']['partner']['allele'], gene)
    expected = result(document, identity, source, records)
    require(wrapper == expected and expected['status'] == 'VERIFIED', 'ORIGINAL_QUALIFICATION_REPLAY_MISMATCH')
    return wrapper


def project(directory, document):
    from core_material_contract import bounded
    metadata = document['sources'].get('special-partner-qualification')
    if metadata is None:
        return None
    raw = bounded(Path(directory) / metadata['path'])
    require(digest(raw) == metadata['sha256'] and metadata['format'] == 'JSON'
            and metadata['role'] == 'DERIVED_FACTS', 'QUALIFICATION_PIN_MISMATCH')
    wrapper = verify(directory, document, parse(raw))
    refs = [{'sourceRef': row['sha256'] + '#/', 'role': 'DERIVED_FACTS', 'format': 'XML'}
            for row in wrapper['sourceRecords']]
    return wrapper, {'canonicalUtf8': raw.decode('utf-8'), 'sha256': digest(raw), 'sourceRefs': refs}
