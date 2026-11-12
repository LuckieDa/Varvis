"""Compile a reusable PS1 splice source pool from pinned official XML and explicit policy data.

Reuses the original ClinVar parser and final-criteria extractor. Never writes a target/reference
pair, changes an expert assertion or grants clinical approval. Java independently checks the XML.
"""
import argparse
import hashlib
import json
from pathlib import Path
try:
    from . import prepare_reference_evidence as base
    from . import prepare_expert_reference_evidence as expert
except ImportError:
    import prepare_reference_evidence as base
    import prepare_expert_reference_evidence as expert


def source_bytes(root, name):
    base.require(Path(name).name == name and name not in ('', '.', '..'), 'SOURCE_FILENAME_REQUIRED')
    path = (root / name).resolve()
    base.require(path.parent == root and path.stat().st_size <= 2_000_000, 'SOURCE_BOUNDARY_OR_SIZE_INVALID')
    with path.open('rb') as stream:
        raw = stream.read(2_000_001)
    base.require(len(raw) <= 2_000_000, 'SOURCE_BOUNDARY_OR_SIZE_INVALID')
    return raw


def prepare(manifest_path, expected_sha, output):
    source = Path(manifest_path).resolve()
    base.require(source.stat().st_size <= 4_000_000, 'MANIFEST_SIZE_LIMIT')
    with source.open('rb') as stream:
        manifest_bytes = stream.read(4_000_001)
    base.require(len(manifest_bytes) <= 4_000_000, 'MANIFEST_SIZE_LIMIT')
    base.require(base.sha(manifest_bytes) == expected_sha, 'MANIFEST_HASH_MISMATCH')
    manifest = base.parse_json(manifest_bytes)
    output = Path(output)
    base.require(not output.exists(), 'OUTPUT_EXISTS_REFUSE_OVERWRITE')
    base.require(manifest['schemaVersion'] == 'ps1-splice-source-manifest-v1', 'MANIFEST_SCHEMA_INVALID')
    policies = manifest['policies']
    base.require(0 < len(policies) <= 128 and len(manifest['records']) <= 256, 'SOURCE_COUNT_LIMIT')
    files, records = [], []
    for policy in policies:
        raw = source_bytes(source.parent, policy['sourceFile'])
        base.require(len(raw) <= 2_000_000 and base.sha(raw) == policy['sourceSha256'], 'POLICY_SOURCE_HASH_MISMATCH')
        base.require(policy['sourceClause'] in raw.decode('utf8'), 'POLICY_CLAUSE_NOT_PRESENT')
        base.require(Path(policy['sourceFile']).name == policy['sourceFile'], 'SOURCE_FILENAME_REQUIRED')
        files.append((policy['sourceFile'], raw))
    for item in manifest['records']:
        raw = source_bytes(source.parent, item['xmlFile'])
        base.require(len(raw) <= 2_000_000 and base.sha(raw) == item['xmlSha256'], 'XML_SOURCE_HASH_MISMATCH')
        base.require(Path(item['xmlFile']).name == item['xmlFile'], 'SOURCE_FILENAME_REQUIRED')
        parsed = base.parse_vcv(raw, item['variationId'], item['allele'])
        selected = [a for a in parsed['assertions'] if a['scv'] == item['scv']]
        base.require(len(selected) == 1, 'EXACT_SOURCE_SCV_REQUIRED')
        assertion = selected[0]
        base.require(parsed['recordStatus'] == assertion['recordStatus'] == 'current'
                     and assertion['reviewStatus'] == 'reviewed by expert panel', 'CURRENT_EXPERT_REQUIRED')
        statements = expert.applied_criteria_statements(assertion['comments'], item['xmlSha256'])
        base.require(len(statements) == 1 and statements[0]['status'] == 'EXPLICIT_SOURCE_LIST', 'EXPLICIT_FINAL_LIST_REQUIRED')
        statement = statements[0]
        base.require(len(statement['specificationVersionMentions']) == 1, 'ORIGINAL_VERSION_REQUIRED')
        diseases = {n['attributes']['ID'] for n in expert.xml_nodes(assertion['condition'])
                    if n['tag'] == 'XRef' and n['attributes'].get('DB') == 'MONDO'}
        base.require(len(diseases) == 1 and item['geneSymbol'] in parsed['geneSymbols'], 'EXACT_DISEASE_GENE_REQUIRED')
        record = dict(item, classification=assertion['classification'].upper().replace(' ', '_'),
                      mondoId=next(iter(diseases)), expertOrgId=assertion['accession']['OrgID'],
                      originalVersion=statement['specificationVersionMentions'][0],
                      evaluationDate=assertion['dateLastEvaluated'], originalText=statement['originalText'],
                      rawSuffix=statement['rawSuffix'], criteria=[c['original'] for c in statement['criteria']],
                      originalAssertion=assertion, originalStatement=statement)
        records.append(record)
        files.append((item['xmlFile'], raw))
    models = manifest.get('transcriptModels', [])
    base.require(isinstance(models, list) and len(models) <= 128, 'TRANSCRIPT_MODEL_COUNT_INVALID')
    for model in models:
        raw = source_bytes(source.parent, model['file'])
        base.require(base.sha(raw) == model['sha256'], 'TRANSCRIPT_MODEL_HASH_MISMATCH')
        files.append((model['file'], raw))
    result = dict(schemaVersion='ps1-splice-knowledge-v1', purpose='PROJECT_RESEARCH',
                  scientificApprovalGranted=False, policies=policies, references=records,
                  preparation=dict(version='1.0.0', manifestRawSha256=expected_sha,
                                   sourceClassificationPreserved=True, pairSpecificApprovalCreated=False))
    if models:
        result['transcriptModels'] = models
    output.mkdir(parents=True)
    for name, raw in files:
        dest = output / name
        base.require(not dest.exists() or dest.read_bytes() == raw, 'SOURCE_FILENAME_COLLISION')
        dest.write_bytes(raw)
    encoded = json.dumps(result, ensure_ascii=False, indent=2).encode('utf8')
    (output / 'knowledge.json').write_bytes(encoded)
    return dict(status='COMPILED_PENDING_RUNTIME_SOURCE_CHECK', records=len(records), policies=len(policies),
                knowledgeRawSha256=hashlib.sha256(encoded).hexdigest(), scientificApprovalGranted=False)


if __name__ == '__main__':
    cli = argparse.ArgumentParser()
    cli.add_argument('--manifest', required=True)
    cli.add_argument('--sha256', required=True)
    cli.add_argument('--output', required=True)
    args = cli.parse_args()
    print(json.dumps(prepare(args.manifest, args.sha256, args.output), ensure_ascii=False))
