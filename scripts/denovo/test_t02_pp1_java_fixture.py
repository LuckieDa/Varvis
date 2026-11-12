"""T02-only source transport fixture for the real Java/Python paper chain.

CLI: project target.json binding.json EXTRACT_ONLY|COMPLETE existing-python.
All observations, source identifiers and model responses are SYNTHETIC_TEST.
No identity resolver, qualification, evaluator, request, profile or result is
stubbed.  The helper seeds exact cache receipts; the normal worker still replays
them and may correctly retain missing identity/context/UNKNOWN-ledger needs.
"""
import html
import json
from pathlib import Path
import sys

from acquire_public_literature import source_bundle
from bundle_model_contract import load
from model_literature_contract import canonical, digest, parse
from model_transport import atomic
from paper_library_registration import register
from pp1_literature_contract import VERSION, AUDIT_VERSION, claim_id, validate_target
from prepare_literature import prepare as prepare_source
from process_pp1_paper import initial_units, public_spellings
from run_pp1_literature_model import prepare as prepare_model, run as model_run


def configuration():
    return {'schemaVersion': 'denovo-model-config-v1', 'enabled': True, 'pricingConfirmed': True,
            'protocol': 'openai-chat-completions-json-schema', 'endpoint': 'https://example.invalid/v1/chat/completions',
            'model': 'gemini-3-flash', 'expectedResponseModel': 'gemini-3-flash', 'maxCalls': 100,
            'maxOutputTokens': 8192, 'maxBilledOutputTokens': 16384, 'timeoutSeconds': 1,
            'maxRequestBytes': 262144, 'maxResponseBytes': 1048576, 'inputCreditsPerMillion': 1,
            'outputCreditsPerMillion': 6, 'creditsPerRmb': 3, 'budgetRmb': 16.66,
            'apiKeyEnvironment': 'VARVIS_TEST_FAKE', 'apiKeyFile': 'key.txt'}


def prepare(project, target_path, binding_path, mode, existing_python):
    project = Path(project).resolve(strict=True)
    if mode not in {'EXTRACT_ONLY', 'COMPLETE'} or not Path(existing_python).is_file():
        raise ValueError('T02_FIXTURE_ARGUMENTS_INVALID')
    target = parse(Path(target_path).read_bytes()); validate_target(target)
    binding = parse(Path(binding_path).read_bytes())
    if (binding.get('schemaVersion') != 'pp1-analysis-binding-v1'
            or binding['selection']['geneSymbol'] != target['geneSymbol']):
        raise ValueError('T02_FIXTURE_BINDING_INVALID')
    local = project / 'local'; local.mkdir(exist_ok=True)
    model_path = local / 'model.json'; key = local / 'key.txt'; record_path = local / 't02-fixture.json'
    model = configuration(); model_raw = canonical(model)
    if model_path.exists() and model_path.read_bytes() != model_raw:
        raise ValueError('T02_FIXTURE_MODEL_CONFIGURATION_MISMATCH')
    if not model_path.exists(): model_path.write_bytes(model_raw)
    if key.exists() and key.read_bytes() != b'T02_SYNTHETIC_NOT_A_SECRET':
        raise ValueError('T02_FIXTURE_KEY_MISMATCH')
    if not key.exists(): key.write_bytes(b'T02_SYNTHETIC_NOT_A_SECRET')
    pmcid = 'PMC4417711'; library = local / 'library'
    budget_name = 'local/budget'
    workflow = local / 'workflow.json'
    if workflow.exists():
        configured = parse(workflow.read_bytes())
        budget_name = configured.get('modelBudget')
    if not isinstance(budget_name, str) or not budget_name.strip() or Path(budget_name).is_absolute():
        raise ValueError('T02_FIXTURE_BUDGET_PATH_INVALID')
    budget = (project / budget_name).resolve()
    if not budget.is_relative_to(local.resolve()):
        raise ValueError('T02_FIXTURE_BUDGET_PATH_INVALID')
    budget_relative = budget.relative_to(project).as_posix()
    library.mkdir(exist_ok=True)
    if record_path.exists():
        record = parse(record_path.read_bytes())
        if (record['target'] != target or record['analysisBindingSha256'] != digest(canonical(binding))
                or record['modelConfigurationSha256'] != digest(model_raw)
                or record.get('modelBudget', 'local/budget') != budget_relative):
            raise ValueError('T02_FIXTURE_IDENTITY_CHANGED')
        pin = record['bundleSha256']
    else:
        # This is published-looking synthetic source data, never a real report
        # about the actual gene.  Every printed spelling comes from the current
        # normal Java target; no fixed variant/clinical interpretation is used.
        alias = next((a for a in target['variantAliases'] if 'c.' in a), target['variantAliases'][0])
        sentences = [f"Family FT02: {target['geneSymbol']} {'; '.join(target['variantAliases'])}.",
            'Only one candidate variant was identified in family FT02.',
            'Candidate assessment in family FT02 used SYNTHETIC complete candidate assessment.',
            f'I-1 is heterozygous for {alias}.', 'I-1 is diagnosed with SYNTHETIC source phenotype.',
            'II-1 is diagnosed with SYNTHETIC source phenotype.', 'I-1 is the father of II-1.',
            'The proband II-1 was ascertained.', f'II-1 is heterozygous for {alias}.']
        text = ' '.join(sentences)
        raw = ('<article><front><article-meta><article-id pub-id-type="pmcid">' + pmcid
            + '</article-id><article-id pub-id-type="pmid">4417711</article-id><title-group>'
            '<article-title>SYNTHETIC T02 source transport</article-title></title-group></article-meta></front>'
            '<body><p>' + html.escape(text) + '</p></body></article>').encode()
        files, source = source_bundle({'pmcid': pmcid}, raw, prepare_source(raw, pmcid, digest(raw)), None, False)
        pin = register(library, pmcid, files, source, {'sha256': digest(raw),
            'acquisitionUrl': 'https://www.ebi.ac.uk/europepmc/webservices/rest/' + pmcid + '/fullTextXML'},
            criteria=('PP1',))['bundleSha256']
        record = {'schemaVersion': 't02-pp1-java-fixture-v1', 'sourceClass': 'SYNTHETIC_TEST_ONLY',
                  'clinicalApproval': 'NONE', 'pmcid': pmcid, 'bundleSha256': pin, 'target': target,
                  'analysisBindingSha256': digest(canonical(binding)), 'sourceRawSha256': digest(raw),
                  'modelConfigurationSha256': digest(model_raw), 'syntheticTransportCalls': 0,
                  'modelBudget': budget_relative,
                  'liveModelCalls': 0, 'publicNetworkCalls': 0}
    source = load(library, pmcid, pin, criterion='PP1')
    printed = public_spellings('\n'.join(u['text'] for u in source['units']), target['variantAliases'])
    paper_target = {'geneSymbol': target['geneSymbol'], 'variantAliases': printed}
    ids = initial_units(source, paper_target,
        lambda scope: prepare_model(library, pmcid, pin, paper_target, scope, model))
    units = {u['unitId']: u['text'] for u in source['units']}
    unit_id = next(i for i in ids if 'Family FT02:' in units[i]); paragraph = units[unit_id]
    def fact(field, member, quote, related=(), kind='NONE'):
        if quote not in paragraph: raise ValueError('T02_FIXTURE_QUOTE_NOT_IN_SOURCE')
        return {'fieldId': field, 'familyLabel': 'FT02', 'memberLabel': member, 'sourceValue': quote,
                'relatedMembers': list(related), 'relationKind': kind,
                'citations': [{'unitId': unit_id, 'quote': quote}]}
    alias = next((a for a in target['variantAliases'] if 'c.' in a), target['variantAliases'][0])
    candidate = {'schemaVersion': VERSION, 'families': [{'familyLabel': 'FT02',
        'anchor': {'unitId': unit_id, 'quote': 'Family FT02:'},
        'members': [{'memberLabel': m, 'anchor': {'unitId': unit_id, 'quote': m}} for m in ['I-1', 'II-1']]}],
        'facts': [fact('F02.5', 'FAMILY', 'Only one candidate variant was identified in family FT02.'),
                  fact('F06.5', 'FAMILY', 'Candidate assessment in family FT02 used SYNTHETIC complete candidate assessment.'),
                  fact('F06.4', 'I-1', f'I-1 is heterozygous for {alias}.'),
                  fact('F06.4', 'II-1', f'II-1 is heterozygous for {alias}.'),
                  fact('F05.2', 'I-1', 'I-1 is diagnosed with SYNTHETIC source phenotype.'),
                  fact('F05.2', 'II-1', 'II-1 is diagnosed with SYNTHETIC source phenotype.'),
                  fact('F07.1', 'I-1', 'I-1 is the father of II-1.', ['II-1'], 'PARENT_OF'),
                  fact('F04.2', 'II-1', 'The proband II-1 was ascertained.')],
        'coverage': 'REVIEWED_SUPPLIED_SCOPE',
        'groupChecks': [{'group': f'F{n:02}', 'outcome': 'UNRESOLVED'} for n in range(1, 11)]}
    def transport(unused_config, unused_key, body):
        record['syntheticTransportCalls'] += 1
        request = parse(parse(body)['messages'][1]['content'])
        value = candidate
        if 'original' in request:
            original = request['original']
            value = {'schemaVersion': AUDIT_VERSION,
                'audits': [{'claimId': claim_id(f), 'verdict': 'SUPPORTED', 'reason': 'Exact SYNTHETIC source.',
                            'evidence': f['citations']} for f in original['facts']],
                'additions': [], 'additionalFamilies': [], 'coverage': original['coverage'],
                'groupChecks': original['groupChecks']}
        return canonical({'model': 'gemini-3-flash',
            'usage': {'prompt_tokens': 10, 'completion_tokens': 20, 'total_tokens': 30},
            'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps(value)}}]})
    output = local / 'work/t02-cache-preparation' / mode
    if output.exists(): raise ValueError('T02_FIXTURE_PHASE_ALREADY_PREPARED')
    common = (library, pmcid, pin, paper_target, ids, model, model_path, budget)
    extract = output / 'extract'
    extracted = model_run(*common, extract, execute=True, transport=transport)
    if mode == 'COMPLETE':
        audited = model_run(*common, output / 'audit', previous=extract / 'artifact.json', execute=True, transport=transport)
        record['auditArtifactSha256'] = digest((output / 'audit/artifact.json').read_bytes())
        record['auditSourceStatus'] = audited['status']
    record.update(phase=mode, extractionArtifactSha256=digest((extract / 'artifact.json').read_bytes()),
                  paperTarget=paper_target, sourceUnitIds=ids, lastExtractionCacheHit=extracted['cacheHit'])
    atomic(record_path, record)
    return record


if __name__ == '__main__':
    if len(sys.argv) != 6: raise SystemExit('project target.json binding.json EXTRACT_ONLY|COMPLETE existing-python')
    print(canonical(prepare(*sys.argv[1:])).decode())
