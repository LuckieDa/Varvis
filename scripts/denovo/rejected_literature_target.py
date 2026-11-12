"""Archive a narrowly isolated cited-only target; never create a case artifact.

Original prompt, transport and artifact replay remain version-bound and unchanged.
Only an unanchored CITED_ONLY response with valid exact quotes can be isolated.
All other attribution, integrity and transport failures still stop continuation.
"""
import base64

from bundle_model_contract import cite, require
from bundle_model_transport import cached, cache_identity, decode_response
from model_literature_contract import canonical, digest, parse


def isolate(source, config, payload, budget, binding, reason, path):
    if reason not in {'DENOVO_PROMPT_CASE_LABEL_NOT_ANCHORED', 'DENOVO_BUNDLE_CASE_LABEL_NOT_ANCHORED'}:
        return None
    found = cached(config, payload, budget, binding)
    require(found is not None, 'CONTINUATION_CACHE_MISSING')
    raw, receipt, unused = found
    candidate, unused = decode_response(raw, config)
    if not candidate['cases'] or any(row['caseOrigin'] != 'CITED_ONLY' for row in candidate['cases']):
        return None
    content = parse(payload['messages'][1]['content'])
    allowed = {u['unitId'] for u in content['units']}
    targets = set(content['workUnit']['targetUnitIds'])

    def quoted(citation):
        require(citation['unitId'] in allowed, 'CITATION_OUTSIDE_ACTUAL_INPUT')
        return cite(source, citation)

    for assessment in candidate['sourceAssessments']:
        require(assessment['citation']['unitId'] in targets, 'ASSESSMENT_OUTSIDE_TARGET')
        quoted(assessment['citation'])
    hints = []
    for row in candidate['cases']:
        require(any(c['unitId'] in targets for c in [row['caseCitation'], *[f['citation'] for f in row['fields']]]),
                'CASE_OUTSIDE_TARGET')
        anchor = quoted(row['caseCitation'])
        origins = [quoted(c) for c in row['originCitations']]
        require(bool(origins), 'ORIGIN_CITATION_REQUIRED')
        fields = []
        for field in row['fields']:
            citation = quoted(field['citation'])
            require(field['sourceValue'] in citation['quote'], 'VALUE_NOT_IN_QUOTE')
            fields.append({**field, 'citation': citation})
        hints.append({'proposedLabel': row['caseLabel'], 'proposedOrigin': 'CITED_ONLY',
                      'originVerified': False, 'caseIdentityEstablished': False,
                      'status': 'QUOTES_CHECKED_NOT_A_CASE', 'caseCitation': anchor,
                      'originCitations': origins, 'fields': fields})
    unused, key = cache_identity(config, payload, binding)
    value = {'schemaVersion': 'denovo-rejected-target-v1', 'status': 'NOT_ELIGIBLE_FOR_SCORING',
             'reasonCode': reason, 'binding': binding, 'cacheKey': key,
             'receipt': receipt, 'responseBase64': base64.b64encode(raw).decode(),
             'sourceHints': hints, 'businessPublishable': False, 'clinicalAdoption': 'NONE'}
    body = canonical(value)
    with path.open('xb') as stream:
        stream.write(body)
    return {'chunkIndex': binding['chunkIndex'], 'reasonCode': reason, 'cacheKey': key,
            'path': path.name, 'sha256': digest(body), 'status': value['status'],
            'sourceHints': hints, 'responseSha256': receipt['responseSha256']}
