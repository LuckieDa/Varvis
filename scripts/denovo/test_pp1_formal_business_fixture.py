"""TEST_ONLY source fixture for Java's normal PP1 business adapter.

The paper, reference, parameter adoption and model transport are synthetic.
Raw extraction/review, LocalIdentity, qualification and scoring are production
implementations. No model or network client is called.
"""
from copy import deepcopy
from html import escape
from pathlib import Path
import shutil
import sys

from acquire_public_literature import source_bundle
from bundle_model_contract import load
from core_local_identity import LocalIdentity
from evaluate_pp1_snapshot import calculate
from model_literature_contract import canonical, digest, parse
from paper_library_registration import register
from pp1_context import VERSION as CONTEXT_VERSION, select
from pp1_formal_policy import FORMAL_METHOD
from pp1_literature_contract import AUDIT_VERSION, VERSION, claim_id
from prepare_literature import prepare
from run_pp1_literature_model import run as extract
from test_bundle_model import configuration, response
from test_core_context_review import LocalFixture
from test_literature_acquisition import jats
from test_pp1_formal_policy import model
from test_pp1_segregation import context


def write_fixture(root, binding_path, mode, python):
    root = Path(root).resolve()
    root.mkdir(parents=True, exist_ok=True)
    binding = parse(Path(binding_path).read_bytes())
    selected = binding['selection']
    allele = selected['allele']
    gene, tx = selected['geneSymbol'], 'NM_000001.1'
    coding = 'c.2' + allele['reference'] + '>' + allele['alternate']
    local = root / 'local'
    local.mkdir(exist_ok=True)
    scripts = root / 'scripts/denovo'
    scripts.mkdir(parents=True, exist_ok=True)
    for source in Path(__file__).parent.glob('*.py'):
        if not source.name.startswith('test_'):
            shutil.copyfile(source, scripts / source.name)
    shutil.copyfile(Path(__file__).with_name('bundle_replay_profiles.json'), scripts / 'bundle_replay_profiles.json')
    dictionary = root / 'docs/design/pp1'
    dictionary.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(Path(__file__).resolve().parents[2] / 'docs/design/pp1/field-dictionary.json', dictionary / 'field-dictionary.json')
    support = root / 'scripts/acmg-generalization'
    support.mkdir(exist_ok=True)
    for name in ('normalize_public_panel.py', 'build_transcript_model.py'):
        shutil.copyfile(Path(__file__).parent.parent / 'acmg-generalization' / name, support / name)
    relationship = root / 'scripts/ps1_pm5/pp2_relationship_material.py'
    relationship.parent.mkdir(exist_ok=True)
    shutil.copyfile(Path(__file__).resolve().parents[1] / 'ps1_pm5' / relationship.name, relationship)
    (local / 'python.cmd').write_text('@echo off\r\n"' + str(Path(python).resolve()) + '" %*\r\n', encoding='utf-8')

    f = LocalFixture(root)
    hgnc = binding['identity']['preparedContext']['gene']['hgncId']
    position = allele['position']
    f.annotation.update(geneSymbol=gene, hgncId=hgnc, contig=allele['contig'])
    f.annotation['exons'] = [{'cdsStart': 1, 'cdsEnd': 3, 'genomicStart': position - 1, 'genomicEnd': position + 1}]
    f.seal_annotation()
    names = ['RefSeq_nuc', 'symbol', 'HGNC_ID', 'Ensembl_nuc', 'Ensembl_prot', 'chr_strand']
    import gzip
    mane = f.put('mane.gz', gzip.compress(('\t'.join(names) + '\n' + '\t'.join(
        [tx, gene, hgnc, 'ENST000001.1', 'ENSP000001.1', '+']) + '\n').encode(), mtime=0))
    f.config['mane'] = mane
    f.config['contextManifest'] = f.put('context-manifest.json', canonical({'resources': {'mane': {'rawSha256': mane['sha256']}}}))
    seq = list('A' * 300)
    seq[position - 2:position + 1] = list('A' + allele['reference'] + 'C')
    header = ('>chr' + allele['contig'] + '\n').encode()
    raw_reference = header + b'\n'.join(''.join(seq)[i:i + 50].encode() for i in range(0, 300, 50)) + b'\n'
    fasta = local / 'reference.fa'
    fasta.write_bytes(raw_reference)
    fai = ('chr' + allele['contig'] + '\t300\t' + str(len(header)) + '\t50\t51\n').encode()
    f.put('reference.fa.fai', fai)
    receipt = parse((local / 'receipt.json').read_bytes())
    record = receipt['records']['reference']
    pin = digest(raw_reference)
    record.update(expectedSha256=pin, observedSha256=pin)
    record['actualChecksumReceipt'].update(expectedSha256=pin, observedSha256=pin,
        byteLength=len(raw_reference), mtimeNs=fasta.stat().st_mtime_ns, indexSha256=digest(fai))
    f.config['reference'].update(receipt=f.put('receipt.json', canonical(receipt)), sha256=pin,
        aliases=f.put('aliases.txt', (allele['contig'] + ' chr' + allele['contig'] + '\n').encode()))
    core = f.put('core-config.json', canonical({'schemaVersion': 'denovo-core-context-config-v1', 'identity': f.config, 'contexts': []}))

    source_pin = f.put('formal-source.txt', b'SYNTHETIC independently adopted formal policy and explicit parameters.')
    row = deepcopy(context()['policy'])
    row['scope'] = {k: selected[k] for k in ('geneSymbol', 'diseaseId', 'inheritance')}
    row.update(method=FORMAL_METHOD, penetrance='EXPLICIT_FORMAL_MODEL', phenocopy='EXPLICIT_FORMAL_MODEL',
               formalModel=model(), validFrom='2026-01-01', validThrough='2027-12-31',
               sources={'S': {**source_pin, 'quote': 'SYNTHETIC independently adopted formal policy', 'locator': 'line1'}})
    decisions = ('method', 'geneDiseaseValidity', 'expertPolicyStatus', 'affectedTerms', 'penetrance', 'phenocopy',
                 'locusModel', 'candidateAssessmentMethods', 'formalModel', 'formalModel.ascertainment', 'formalModel.pointBase')
    row['decisionSources'] = {name: ['S'] for name in decisions}
    if mode == 'missing':
        del row['formalModel']['phenotypeModel']['probabilities']['2']
    catalog = f.put('formal-catalog.json', canonical({'schemaVersion': CONTEXT_VERSION, 'entries': [row]}))
    adoption = f.put('formal-adoption.json', canonical({'catalogSha256': catalog['sha256'], 'status': 'ACCEPTED', 'scope': 'SYNTHETIC_TEST'}))

    relatives = [('II-2', 0, True)] if mode == 'negative' else [('II-2', 1, True), ('II-3', 0, False)]
    members = ['I-1', 'I-2', 'II-1'] + [v[0] for v in relatives]
    claims = []

    def add(field, member, quote, related=None, kind='NONE'):
        claims.append({'fieldId': field, 'familyLabel': 'F1', 'memberLabel': member,
                       'sourceValue': quote, 'relatedMembers': related or [], 'relationKind': kind})

    add('F02.5', 'FAMILY', 'Only one candidate variant was identified in family F1.')
    add('F06.5', 'FAMILY', 'Candidate assessment in family F1 used SYNTHETIC complete candidate assessment.')
    add('F04.1', 'FAMILY', 'Family F1 was ascertained on the basis of clinical phenotype before genotyping.')
    add('F04.2', 'II-1', 'The proband II-1 was ascertained.')
    for member, dosage in [('I-1', 1), ('I-2', 0), ('II-1', 1)] + [(r[0], r[1]) for r in relatives]:
        add('F06.4', member, f'{member} is heterozygous for {coding}.' if dosage else f'No {coding} was detected in {member}.')
    for member, affected in [('II-1', True)] + [(r[0], r[2]) for r in relatives]:
        add('F05.2', member, f'{member} is diagnosed with TEST disease.' if affected else f'{member} is clinically unaffected.')
    for child in ['II-1'] + [r[0] for r in relatives]:
        for parent, role in [('I-1', 'father'), ('I-2', 'mother')]:
            add('F07.1', parent, f'{parent} is the {role} of {child}.', [child], 'PARENT_OF')
    text = f'SYNTHETIC fixture. Family F1: {gene} {tx}:{coding}. ' + ' '.join(f'Member {m}.' for m in members)
    raw = jats(False).replace(b'<p>A public source.</p>', ''.join('<p>' + escape(v) + '</p>' for v in [text] + [c['sourceValue'] for c in claims]).encode())
    library, work = local / 'library', local / 'work'
    library.mkdir(exist_ok=True); work.mkdir(exist_ok=True)
    files, bundle = source_bundle({'pmcid': 'PMC123'}, raw, prepare(raw, 'PMC123', digest(raw)), None, False)
    paper_pin = register(library, 'PMC123', files, bundle, {'sha256': digest(raw),
        'acquisitionUrl': 'https://www.ebi.ac.uk/europepmc/webservices/rest/PMC123/fullTextXML'}, criteria=('PP1',))['bundleSha256']
    source = load(library, 'PMC123', paper_pin, criterion='PP1')
    seed = next(u for u in source['units'] if 'SYNTHETIC fixture.' in u['text'])
    for claim in claims:
        unit = next(u for u in source['units'] if u['text'] == claim['sourceValue'])
        claim['citations'] = [{'unitId': unit['unitId'], 'quote': claim['sourceValue']}]
    proposal = {'schemaVersion': VERSION, 'families': [{'familyLabel': 'F1',
        'anchor': {'unitId': seed['unitId'], 'quote': 'Family F1:'}, 'members': [
            {'memberLabel': m, 'anchor': {'unitId': seed['unitId'], 'quote': f'Member {m}.'}} for m in members]}],
        'facts': claims, 'coverage': 'REVIEWED_SUPPLIED_SCOPE',
        'groupChecks': [{'group': f'F{n:02}', 'outcome': 'UNRESOLVED'} for n in range(1, 11)]}

    def transport(config, key, body):
        request = parse(parse(body)['messages'][1]['content'])
        if 'original' not in request:
            return response(deepcopy(proposal))
        original = request['original']
        return response({'schemaVersion': AUDIT_VERSION, 'audits': [{'claimId': claim_id(c), 'verdict': 'SUPPORTED',
            'reason': 'Exact SYNTHETIC source.', 'evidence': c['citations']} for c in original['facts']],
            'additions': [], 'additionalFamilies': [], 'coverage': original['coverage'], 'groupChecks': original['groupChecks']})

    model_config = local / 'model.json'
    model_config.write_bytes(canonical(configuration()))
    (local / 'key.txt').write_bytes(b'SYNTHETIC_TEST_KEY_NOT_A_CREDENTIAL')
    target = {'geneSymbol': gene, 'variantAliases': [coding]}
    args = (library, 'PMC123', paper_pin, target, [u['unitId'] for u in source['units']],
            configuration(), model_config, work / 'budget')
    extract(*args, work / 'extraction', execute=True, transport=transport)
    extract(*args, work / 'review', previous=work / 'extraction/artifact.json', execute=True, transport=transport)
    artifact = work / 'review/artifact.json'
    workflow = {'schemaVersion': 'denovo-public-literature-workflow-v1', 'enabled': True, 'projectRoot': str(root),
        'work': 'local/work', 'library': 'local/library', 'modelConfig': 'local/model.json', 'python': 'local/python.cmd',
        'pp1Knowledge': {'catalog': catalog, 'adoption': adoption}, 'coreContext': core}
    (local / 'workflow.json').write_bytes(canonical(workflow))
    document = {'taskId': 'a' * 64, 'request': {'pmcid': 'PMC123', 'bundleSha256': paper_pin, 'analysisBinding': binding},
                'result': {'target': target, 'status': 'SYNTHETIC_SOURCE_AUDITED'},
                'artifacts': [{'path': str(artifact.relative_to(root)), 'sha256': digest(artifact.read_bytes())}]}
    expected = calculate(root, library, work, [document], binding,
                         select(root, workflow['pp1Knowledge'], selected), LocalIdentity(root, f.config))
    summary = {'workflow': str(local / 'workflow.json'), 'pmcid': 'PMC123', 'bundleSha256': paper_pin,
               'target': target, 'artifact': str(artifact), 'mode': mode, 'expected': expected,
               'noNetworkOrModel': True, 'dataClass': 'SYNTHETIC_TEST'}
    (local / 'fixture.json').write_bytes(canonical(summary))
    return summary


if __name__ == '__main__':
    write_fixture(*sys.argv[1:])
