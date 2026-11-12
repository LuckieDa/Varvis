"""Replay trusted source/config into original Java-scoring inputs, never scores.

Called only by the offline reader with service-owned configuration. Submitted
models cannot supply configuration, qualification flags or approved policies.
"""
from pathlib import Path
import base64
import gzip
from model_literature_contract import canonical, digest, parse
from core_context_review import context_package, load_config, verified
from core_material_extraction import write_package
from core_material_review import review_package, implementation
from core_material_contract import facts as all_facts, assess, evaluation_value
from literature_explanation import attach_explanation
from core_special_partner import DEPENDENCIES as PARTNER_FILES

VERSION = 'denovo-core-evaluation-intake-1.1.0'
FILES = tuple(dict.fromkeys((*implementation(), 'core_source_event_discovery.py', 'core_special_event.py', *PARTNER_FILES, 'core_evaluation_intake.py', 'core_context_review.py',
    'core_case_semantics.py', 'core_context_policy.py', 'core_local_identity.py', 'refseq_identity.py', 'literature_explanation.py')))


def explain(readiness, report):
    result = []
    for b in readiness['blockers']:
        item = dict(b)
        related = [i for i in report['issues'] if b['path'] in i['paths']]
        if related:
            item['category'] = related[0]['category']
            item['causeCodes'] = [i['code'] for i in related]
        elif b['code'] == 'NOT_EXTRACTED':
            item['category'] = 'EXTRACTION_OR_REVIEW_INCOMPLETE'
        item['meaning'] = ('NO_CLAIM_OF_SOURCE_ABSENCE' if b['code'] == 'NOT_EXTRACTED'
                           else 'DECLARED_SOURCE_SCOPE_ONLY' if b['code'] == 'NOT_REPORTED_IN_CHECKED_SCOPE'
                           else 'QUALIFICATION_REQUIRED')
        result.append(item)
    return result


def project_package(directory, index):
    directory = Path(directory); projections = {}
    for row in index['cases']:
        doc = parse((directory / row['path']).read_bytes())
        ledger = parse((directory / row['ledgerPath']).read_bytes())
        report = parse((directory / row['reportPath']).read_bytes())
        readiness = assess(doc, directory, ledger, digest(canonical(ledger)))
        values = {p: evaluation_value(p, f) for p, f in all_facts(doc).items()}
        policy = doc['sources'].get('policy')
        identity = doc['sources'].get('local-identity')
        projections[doc['caseKey']] = {'schemaVersion': VERSION, 'status': readiness['status'],
            'caseKey': doc['caseKey'], 'dataClass': doc['dataClass'], 'values': values,
            'policySha256': policy['sha256'] if policy else None,
            'identityProof': parse((directory / identity['path']).read_bytes()) if identity else None,
            'documentSha256': row['sha256'], 'ledgerSha256': row['ledgerSha256'],
            'evaluationKey': readiness['evaluationKey'], 'verifiedFactsKey': readiness['verifiedFactsKey'],
            'eventReview': report.get('eventReview'), 'sourceDiscovery': report.get('specialEventDiscovery'), 'blockers': explain(readiness, report),
            'fieldProofs': {p: {'fact': f, 'check': ledger['checks'].get(p)} for p, f in all_facts(doc).items()
                            if f['verification'] == 'VERIFIED' or f['issues']},
            'sourcePins': {k: {'sha256': v['sha256'], 'role': v['role']} for k, v in doc['sources'].items()},
            'coverageLimitations': readiness['coverageLimitations'], 'criterionDecision': 'NOT_RUN',
            'display': display_summary(doc, row, readiness, report, directory)}
        special = doc['extensions'].get('specialEvent')
        if special is not None and special['verification'] == 'VERIFIED' and special['availability'] == 'REPORTED':
            # assess() has replayed every leaf against registered original bytes.
            core = projections[doc['caseKey']]
            core['specialEvent'] = special['value']
            from core_special_event import event_identity_proof, SOURCE_ID
            original = doc['sources'][SOURCE_ID]
            original_raw = (directory / original['path']).read_bytes()
            if digest(original_raw) != original['sha256']:
                raise ValueError('DENOVO_SPECIAL_EVENT_IDENTITY_SOURCE_PIN_MISMATCH')
            envelope = parse(original_raw)
            expected_identity = event_identity_proof(envelope, original['sha256'])
            if report['specialEventSource'].get('eventIdentityProof') != expected_identity:
                raise ValueError('DENOVO_SPECIAL_EVENT_IDENTITY_PROOF_REPLAY_MISMATCH')
            core['specialEventSource'] = report['specialEventSource']
            core['fieldProofs']['/extensions/specialEvent']['factCanonicalUtf8'] = canonical(special).decode('utf-8')
            from core_special_partner import project as project_partner
            partner = project_partner(directory, doc)
            if partner is not None:
                core['partnerQualification'], core['partnerQualificationProof'] = partner
    return projections


def display_summary(doc, row, readiness, report, directory):
    """Public-paper/synthetic view only. Never exports configured paths or review authority."""
    fields = []
    for path, fact in all_facts(doc).items():
        citations = []
        for citation in fact['citations']:
            source = doc['sources'][citation['sourceId']]
            # Public source excerpts only; internal knowledge and review files keep hash references.
            public = source['role'] in ('PAPER_PRIMARY', 'PAPER_ATTACHMENT')
            citations.append({'sourceSha256': source['sha256'], 'role': source['role'],
                'locator': citation['locator'] if public else 'INTERNAL_REVIEW',
                'quote': citation['quote'] if public else '', 'kind': citation['kind']})
        fields.append({'path': path, 'value': fact['value'], 'availability': fact['availability'],
            'verification': fact['verification'], 'origin': fact['origin'], 'subject': fact['subject'],
            'issues': fact['issues'], 'citations': citations})
    return attach_explanation({'schemaVersion': 'denovo-core-display-v1', 'caseKey': doc['caseKey'],
        'dataClass': doc['dataClass'], 'status': readiness['status'], 'criterionDecision': 'NOT_RUN',
        'documentSha256': row['sha256'], 'ledgerSha256': row['ledgerSha256'],
        'fields': fields, 'blockers': explain(readiness, report),
        'coverageLimitations': readiness['coverageLimitations'],
        'sourceDiscovery': report.get('specialEventDiscovery')}, doc, report, directory)


def archive_package(directory, index, intake=None):
    """Bounded, byte-exact audit sources survive the reader's temporary directory."""
    directory = Path(directory); files = {}; originals = {}
    for row in index['cases']:
        doc = parse((directory / row['path']).read_bytes())
        paths = [row['path'], row['ledgerPath'], row['reportPath'], row['readinessPath']]
        paths += [s['path'] for s in doc['sources'].values()]
        for name in paths:
            raw = (directory / name).read_bytes(); pin = digest(raw)
            files[name] = pin; originals[pin] = base64.b64encode(raw).decode('ascii')
    if intake is not None:
        raw = canonical(intake); pin = digest(raw)
        files['original-intake.json'] = pin
        originals[pin] = base64.b64encode(raw).decode('ascii')
    raw = canonical({'files': files, 'originalsBySha256': originals})
    if len(raw) > 16 * 1024 * 1024:
        raise ValueError('DENOVO_CORE_AUDIT_ARCHIVE_SIZE_LIMIT')
    return {'encoding': 'gzip+base64', 'uncompressedBytes': len(raw), 'sha256': digest(raw),
            'payload': base64.b64encode(gzip.compress(raw, mtime=0)).decode('ascii')}


def enrich(source, facts, reviews, intake, output, options):
    root = Path(options['root']).resolve(strict=True)
    config, pin = load_config(root, {'path': options['path'], 'sha256': options['sha256']})
    output = Path(output)
    receipts = [a['receipt'] for a in intake['artifacts']]
    receipts += [r['artifact']['receipt'] for r in intake.get('sourceReviews', [])]
    data_class = 'SYNTHETIC' if any(r['transportKind'] == 'SYNTHETIC_TEST' for r in receipts) else 'PUBLIC_PAPER_RESEARCH'
    write_package(source, facts, reviews, output / 'extraction', intake=intake, data_class=data_class)
    source_pin = digest((output / 'extraction/index.json').read_bytes())
    review_package(source, output / 'extraction', source_pin, output / 'review')
    review_pin = digest((output / 'review/index.json').read_bytes())
    index = context_package(source, output / 'review', review_pin, output / 'context', root, config, pin, complete_events=True)
    projections = project_package(output / 'context', index)
    for row in facts['cases']:
        core = projections[source['pmcid'] + '#' + row['caseLabel']]
        row['coreEvaluation'] = core
        allele, gene = core['values']['/groups/variant/resolvedAllele'], core['values']['/context/hgncId']
        # Configured new route must never fall back to a successful legacy key.
        row['alleleIdentity'] = {'allele': allele, 'hgncId': gene, 'status': 'VERIFIED' if allele else 'UNRESOLVED',
                                'issues': [i['code'] for i in core['blockers'] if i['path'] == '/groups/variant/resolvedAllele']}
    facts['coreEvaluationVersion'] = VERSION
    facts['coreContextConfigSha256'] = pin
    facts['coreImplementation'] = {n: digest((Path(__file__).parent / n).read_bytes()) for n in FILES}
    facts['coreAuditArchive'] = archive_package(output / 'context', index, intake)
    return facts
