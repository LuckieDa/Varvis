"""Current public PP2 source observation. No review/approval bundle or model call.

The Java owner supplies the exact native source bytes it just acquired. Existing
native XML/expert readers remain the only assertion parsers. The declared LitVar
query scope is replayed from real responses, separately from causal assessment.
"""
import argparse
import base64
from copy import deepcopy
import json
from pathlib import Path
import re
import sys

import prepare_expert_reference_evidence as expert
import prepare_reference_evidence as native
from read_pp2_other_phenotype_sources import raw_blob, reason
from pp2_discovery_scope import canonical, replay

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'denovo'))
from discover_public_literature import discover, targets_from, implementation_pin
from literature_allele_identity import read_record
from literature_search_transport import SearchTransport
from prepare_literature import sha
from select_public_papers import bounded

VERSION = 'pp2-other-phenotype-workflow-reader-v1'
# Exact existing PublishedCriterionKnowledge primary-reference contract. A
# chromosome prefix or a GRCh37 accession is never accepted as GRCh38.
GRCH38 = dict(zip(('NC_' + str(i).zfill(6) + '.' + str(v) for i, v in enumerate(
    (11, 12, 12, 12, 10, 12, 14, 11, 12, 11, 10, 12, 11, 9, 10, 10,
     11, 10, 10, 11, 9, 11, 11, 10), 1)),
    [str(i) for i in range(1, 23)] + ['X', 'Y']))


def need(condition, code):
    native.require(condition, 'PP2_AUTO_' + code)


def original(originals, pin):
    need(isinstance(pin, str) and pin in originals, 'ORIGINAL_REQUIRED')
    return originals[pin]


def summary_allele(row):
    """One explicit GRCh38 atomic SNV, otherwise unresolved, never a guess."""
    alleles = set()
    need(isinstance(row.get('hgvs'), list), 'SUMMARY_GENOMIC_IDENTITY_UNRESOLVED')
    for expression in row['hgvs']:
        if not isinstance(expression, str) or ':' not in expression:
            continue
        accession, change = expression.split(':', 1)
        if accession not in GRCH38:
            continue
        match = re.fullmatch(r'g\.([1-9][0-9]*)([ACGT])>([ACGT])', change)
        need(match is not None, 'SUMMARY_GENOMIC_IDENTITY_UNRESOLVED')
        alleles.add((GRCH38[accession], int(match[1]), match[2], match[3]))
    need(len(alleles) == 1, 'SUMMARY_GENOMIC_IDENTITY_UNRESOLVED')
    contig, position, reference, alternate = next(iter(alleles))
    return dict(assembly='GRCh38', contig=contig, position=position,
                reference=reference, alternate=alternate)


def gene_rows(gene, originals):
    """Replay the source owner's two stable, count-bounded native traversals."""
    need(gene.get('sourceEnumerationComplete') is True, 'NATIVE_ENUMERATION_INCOMPLETE')
    passes, descriptors = [{}, {}], {}
    pages = gene.get('pages')
    need(isinstance(pages, list) and 2 <= len(pages) <= 32, 'NATIVE_PAGES_REQUIRED')
    for number in (1, 2):
        selected = [p for p in pages if p.get('pass') == number]
        need(bool(selected) and [p.get('page') for p in selected] == list(range(1, len(selected) + 1)),
             'NATIVE_PAGE_SEQUENCE_INVALID')
        ended = False
        for page in selected:
            need(not ended and page.get('status') == 'AVAILABLE', 'NATIVE_PAGE_UNAVAILABLE')
            url = page['sourceUrl']
            expected = ('https://erepo.genome.network/evrepo/api/summary/classifications'
                        '?columns=gene&matchTypes=exact&values=' + gene['geneSymbol']
                        + '&pgSize=100&pg=' + str(page['page']))
            need(url == expected, 'NATIVE_PAGE_SCOPE_MISMATCH')
            raw = original(originals, page['sha256'])
            doc = native.parse_json(raw)
            if doc.get('status', {}).get('code') == 404:
                need(page.get('semanticEmptyGeneQuery') is True
                     and doc.get('status') == {'code': 404, 'name': 'Not Found',
                         'msg': 'No records were found for given query'}
                     and doc.get('metadata', {}).get('rendered', {}).get('by') ==
                         'https://erepo.genome.network/evrepo/api/summary/srvc',
                     'NATIVE_EMPTY_QUERY_NOT_VERIFIED')
                ended = True
                continue
            rows = doc.get('data')
            need(doc.get('status', {}).get('code') == 200 and isinstance(rows, list)
                 and len(rows) <= 100, 'NATIVE_PAGE_INVALID')
            for row in rows:
                uuid = row.get('uuid')
                need(isinstance(uuid, str) and re.fullmatch(r'[a-f0-9-]{36}', uuid)
                     and row.get('gene') == gene['geneSymbol'] and uuid not in passes[number - 1],
                     'NATIVE_ROW_SCOPE_OR_DUPLICATE')
                passes[number - 1][uuid] = row
                descriptors.setdefault(uuid, dict(sourceId='native-summary-' + page['sha256'][:24],
                    rawSha256=page['sha256'], kind='EREPO_SUMMARY', url=url,
                    version='ORIGINAL_API_PAGE'))
            ended = len(rows) < 100
        need(ended, 'NATIVE_PAGE_END_NOT_PROVEN')
    need(passes[0] == passes[1], 'NATIVE_ENUMERATION_CHANGED')
    return passes[0], descriptors


def native_read(packet, originals):
    scopes, coverage, aliases = [], [], {}
    genes = {}
    for gene in packet['nativeGenes']:
        key = gene['geneSymbol']
        need(key not in genes, 'NATIVE_GENE_DUPLICATE')
        try:
            genes[key] = (*gene_rows(gene, originals), None)
        except (ValueError, KeyError, TypeError, OSError) as error:
            # A partial traversal cannot clear the scope, but an independently
            # valid expert assertion on an already read official page remains
            # usable as an exclusion. Never choose between conflicting UUIDs.
            partial, descriptors, conflicting = {}, {}, set()
            for page in gene.get('pages', []):
                try:
                    need(page.get('pass') in (1, 2) and page.get('status') == 'AVAILABLE', 'PARTIAL_PAGE_NOT_READ')
                    url = page['sourceUrl']
                    need(url == ('https://erepo.genome.network/evrepo/api/summary/classifications'
                        '?columns=gene&matchTypes=exact&values=' + key + '&pgSize=100&pg=' + str(page['page'])), 'PARTIAL_PAGE_SCOPE_INVALID')
                    doc = native.parse_json(original(originals, page['sha256']))
                    need(doc.get('status', {}).get('code') == 200 and isinstance(doc.get('data'), list)
                         and len(doc['data']) <= 100, 'PARTIAL_PAGE_INVALID')
                    for row in doc['data']:
                        uuid = row.get('uuid')
                        need(isinstance(uuid, str) and re.fullmatch(r'[a-f0-9-]{36}', uuid)
                             and row.get('gene') == key, 'PARTIAL_ROW_SCOPE_INVALID')
                        if uuid in partial and partial[uuid] != row:
                            conflicting.add(uuid)
                        partial.setdefault(uuid, row)
                        descriptors.setdefault(uuid, dict(sourceId='native-summary-' + page['sha256'][:24],
                            rawSha256=page['sha256'], kind='EREPO_SUMMARY', url=url, version='ORIGINAL_API_PAGE'))
                except (ValueError, KeyError, TypeError, OSError):
                    continue
            for uuid in conflicting:
                partial.pop(uuid, None)
                descriptors.pop(uuid, None)
            genes[key] = (partial, descriptors, reason(error))
    records = {r['variationId']: r for r in packet['nativeRecords']}
    need(len(records) == len(packet['nativeRecords']), 'NATIVE_RECORD_DUPLICATE')
    for target in packet['targets']:
        target_id, allele = target['targetId'], target['genomicAllele']
        ledger = dict(targetId=target_id, scope='CURRENT_EREPO_EXPERT_ASSERTIONS_FOR_DECLARED_GENE',
            status='INCOMPLETE', rows=[], pending=[], sourceRecordIds=[])
        coverage.append(ledger)
        if (allele.get('assembly') != 'GRCh38' or allele.get('contig') not in set(GRCH38.values())
                or type(allele.get('position')) is not int or not 1 <= allele['position'] <= 2147483647
                or not isinstance(allele.get('reference'), str) or not re.fullmatch('[ACGT]', allele['reference'])
                or not isinstance(allele.get('alternate'), str) or not re.fullmatch('[ACGT]', allele['alternate'])
                or allele['reference'] == allele['alternate']):
            ledger['status'] = 'SOFTWARE_UNSUPPORTED'
            ledger['pending'].append(dict(reasonCode='PP2_AUTO_NATIVE_GRCH38_SNV_SCOPE_UNSUPPORTED'))
            continue
        rows, descriptors, issue = genes.get(target['geneSymbol'], ({}, {}, 'PP2_AUTO_NATIVE_GENE_NOT_OBSERVED'))
        if issue:
            ledger['pending'].append(dict(reasonCode=issue))
            for gene in packet['nativeGenes']:
                if gene.get('geneSymbol') != target['geneSymbol']:
                    continue
                for page in gene.get('pages', []):
                    if page.get('status') == 'AVAILABLE':
                        continue
                    cause = page.get('reasonCode')
                    if isinstance(cause, str) and re.fullmatch('[A-Z][A-Z0-9_]{1,150}', cause):
                        ledger['pending'].append(dict(reasonCode=cause, sourceKind='NATIVE_EXPERT_ENUMERATION',
                            passNumber=page.get('pass'), pageNumber=page.get('page')))
                cause = gene.get('coverageReason')
                if isinstance(cause, str) and re.fullmatch('[A-Z][A-Z0-9_]{1,150}', cause):
                    ledger['pending'].append(dict(reasonCode=cause, sourceKind='NATIVE_EXPERT_ENUMERATION'))
        wanted = set(target.get('formalCandidate', {}).get('clinvarVariationIds', []))
        relevant = []
        for uuid, row in rows.items():
            item = dict(uuid=uuid, sourceId=descriptors[uuid]['sourceId'],
                        sourceRawSha256=descriptors[uuid]['rawSha256'])
            try:
                declared = summary_allele(row)
                item['declaredAllele'] = declared
                if declared != allele:
                    item.update(status='EXCLUDED_PRECISE_NON_TARGET_ALLELE',
                                reasonCode='PP2_AUTO_SUMMARY_EXACT_GRCH38_ALLELE_DIFFERS')
                else:
                    vid = str(row.get('cvId', ''))
                    need(re.fullmatch(r'[1-9][0-9]{0,8}', vid), 'SUMMARY_VCV_ID_UNRESOLVED')
                    wanted.add(vid)
                    relevant.append((uuid, row))
                    item.update(status='EXACT_TARGET_REQUIRES_NATIVE_ASSERTION', variationId=vid)
            except (ValueError, KeyError, TypeError) as error:
                item.update(status='UNRESOLVED', reasonCode=reason(error))
                ledger['pending'].append(deepcopy(item))
            ledger['rows'].append(item)
        for vid in sorted(wanted):
            record = records.get(vid)
            try:
                need(record is not None and record.get('status') == 'AVAILABLE', 'NATIVE_VCV_UNAVAILABLE')
                pin, url = record['rawSha256'], record['sourceUrl']
                raw = original(originals, pin)
                cv = native.parse_vcv(raw, vid, allele)
                need(cv['recordStatus'] == 'current', 'NATIVE_VCV_NOT_CURRENT')
                need(target['geneSymbol'] in cv['geneSymbols'], 'NATIVE_VCV_GENE_MISMATCH')
                cv_desc = dict(sourceId='native-vcv-' + pin[:24], rawSha256=pin,
                    kind='CLINVAR_VCV', url=url, version=cv['vcv'])
                expert.official_source(cv_desc)
                ledger['sourceRecordIds'].append(dict(variationId=vid, rawSha256=pin, vcv=cv['vcv']))
                try:
                    alias = read_record(raw, pin, cv['vcv'].split('.')[0])
                    need(alias['allele'] == allele and alias['geneSymbol'] == target['geneSymbol']
                         and alias['hgncId'] == target['hgncId'], 'NATIVE_ALIAS_TARGET_MISMATCH')
                    aliases.setdefault(target_id, []).append(alias)
                except (ValueError, KeyError, TypeError) as error:
                    ledger['pending'].append(dict(variationId=vid, reasonCode=reason(error)))
                for scv in cv['assertions']:
                    if scv['recordStatus'] != 'current':
                        need(scv['recordStatus'] in ('withdrawn', 'replaced', 'removed'), 'SCV_CURRENT_STATUS_UNRESOLVED')
                        continue  # Explicit retired statements are retained, not current expert support.
                    if scv['reviewStatus'] not in ('reviewed by expert panel', 'practice guideline'):
                        continue  # This finite route is explicitly native expert assertions.
                    if scv['classification'].strip().lower() not in ('pathogenic', 'likely pathogenic'):
                        continue  # Retained in raw VCV; not a positive relationship under this route.
                    matches = []
                    for uuid, row in relevant:
                        if str(row.get('cvId')) != vid:
                            continue
                        keys = [n['attributes'].get('localKey') for n in scv['submissionIdentity']]
                        if any(k == uuid or isinstance(k, str) and k.startswith(uuid + '|') for k in keys):
                            matches.append((uuid, row))
                    need(len(matches) == 1, 'SCV_CURRENT_EREPO_LINK_UNRESOLVED')
                    uuid, row = matches[0]
                    descriptor = descriptors[uuid]
                    reference = dict(referenceId=target_id + '-' + scv['scv'], variationId=vid,
                        allele=deepcopy(allele), scv=scv['scv'], erepoUuid=uuid,
                        erepoVersion=row['docVersion'], clinvarSource=cv_desc['sourceId'],
                        summarySource=descriptor['sourceId'])
                    supplied = {cv_desc['sourceId']: (cv_desc, raw), descriptor['sourceId']:
                        (descriptor, original(originals, descriptor['rawSha256']))}
                    facts = expert.extract(reference, supplied)
                    scopes.append(dict(scopeId=target_id + '-native',
                        sourcePins={k: v[0]['rawSha256'] for k, v in supplied.items()},
                        references=[dict(referenceId=reference['referenceId'], declaredAllele=deepcopy(allele),
                            requiredSourceIds=list(supplied), status='SOURCE_FACTS_READ', facts=facts)]))
            except (ValueError, KeyError, TypeError, OSError) as error:
                ledger['pending'].append(dict(variationId=vid, reasonCode=reason(error)))
        if not ledger['pending']:
            ledger['status'] = 'COMPLETE'
    return scopes, coverage, aliases


def search_signals(target, aliases):
    terms, citations, citation_sources = [], [], []
    for record in aliases:
        for pair in record['pairedHgvs']:
            terms.extend([pair['coding'], pair['transcript'] + ':' + pair['coding'], 'p.' + pair['protein']])
        for item in record['pubmed']:
            citations.append(item['pmid'])
            citation_sources.append(dict(sourceSha256=record['sourceSha256'], citation=item['citation']))
    if not aliases:
        for annotation in target.get('formalCandidate', {}).get('transcriptAnnotations', []):
            if annotation.get('geneSymbol') != target['geneSymbol']:
                continue
            for key in ('hgvsCoding', 'hgvsProtein'):
                term = annotation.get(key)
                if isinstance(term, str) and re.fullmatch(r'[A-Za-z0-9_=*+().:>/-]{1,160}', term):
                    terms.append(term)
    terms = list(dict.fromkeys(terms))
    need(0 < len(terms) <= 64, 'TARGET_ALIASES_MISSING_OR_TECHNICAL_LIMIT')
    return dict(schemaVersion='denovo-selected-search-signals-v1', geneSymbol=target['geneSymbol'],
        variantAliases=terms, citationPmids=list(dict.fromkeys(citations)),
        citationSources=citation_sources, issues=[])


def observe(packet, work, cache, online=False, transport_factory=SearchTransport):
    need(packet.get('schemaVersion') == 'pp2-other-phenotype-auto-input-v1', 'INPUT_SCHEMA_INVALID')
    originals = {pin: raw_blob(value) for pin, value in packet['originals'].items()}
    provenance = {}
    need(all(pin == sha(raw) for pin, raw in originals.items()), 'ORIGINAL_KEY_MISMATCH')
    scopes, coverage, aliases = native_read(packet, originals)
    result = dict(schemaVersion='pp2-other-phenotype-native-reader-v1', status='READ',
        methodVersion=VERSION, expertScopes=scopes, nativeCoverage=coverage,
        literature=[], discoveryScopes=[], scientificAdoption=False)
    work = Path(work).resolve(strict=True)
    for index, target in enumerate(packet['targets']):
        scope = dict(targetId=target['targetId'], status='INCOMPLETE', reasonCode='PP2_AUTO_DISCOVERY_UNAVAILABLE')
        result['discoveryScopes'].append(scope)
        try:
            folder = work / ('target-' + str(index))
            folder.mkdir()
            signals = search_signals(target, aliases.get(target['targetId'], []))
            signal_raw = canonical(signals)
            (folder / 'signals.json').write_bytes(signal_raw)
            entry = dict(targetId=target['targetId'], sourceKind='SELECTED_VARIANT_SIGNALS',
                         source=dict(path='signals.json', sha256=sha(signal_raw)))
            request = dict(schemaVersion='denovo-literature-discovery-input-v1', targets=[entry])
            request_raw = canonical(request)
            path = folder / 'discovery-input.json'
            path.write_bytes(request_raw)
            originals[sha(signal_raw)], originals[sha(request_raw)] = signal_raw, request_raw
            parsed = targets_from(path)
            before = implementation_pin()
            checkpoint, previous, response_map = None, None, {}
            for batch in range(1, 17):
                archive = folder / ('requests-' + str(batch))
                with transport_factory(cache, archive, online) as transport:
                    # Reserve the existing transport's maximum body before any
                    # new read. A technical cap stops before unarchived bytes
                    # are acquired; it never means no supporting material.
                    class RetainedTransport:
                        def __getattr__(self, name):
                            return getattr(transport, name)
                        def take(self, method, *args):
                            need(sum(len(v) for v in originals.values()) + 2 * 1024 * 1024 <= 32 * 1024 * 1024,
                                 'SOURCE_ARCHIVE_TECHNICAL_BUDGET')
                            raw = method(*args)
                            originals[sha(raw)] = raw
                            return raw
                        def get(self, *args):
                            return self.take(transport.get, *args)
                        def get_litvar(self, *args):
                            return self.take(transport.get_litvar, *args)
                    found = discover(parsed, RetainedTransport(), precision_first=True, litvar=True, checkpoint=checkpoint)
                for event in transport.events:
                    pin, url = event.get('sha256'), event['url']
                    if not pin:
                        continue
                    raw = bounded(Path(cache) / (pin + '.body'), 2 * 1024 * 1024)
                    need(sha(raw) == pin, 'DISCOVERY_RESPONSE_PIN_MISMATCH')
                    need(url not in response_map or response_map[url] == raw, 'DISCOVERY_SAME_URL_CONFLICT')
                    response_map[url] = raw
                    originals[pin] = raw
                    receipt = {k: deepcopy(event[k]) for k in ('url', 'source', 'sha256', 'status', 'retrievedAt', 'bytes') if k in event}
                    if receipt not in provenance.setdefault(pin, []):
                        provenance[pin].append(receipt)
                checkpoint = found['checkpoint']
                progress = sha(canonical(checkpoint))
                if not found['resumeAvailable'] or progress == previous:
                    break
                previous = progress
            need(before == implementation_pin(), 'DISCOVERY_METHOD_CHANGED_DURING_OBSERVATION')
            ledger = replay(path, response_map)
            scope.update(status=ledger['status'], reasonCode=None, retrieval=ledger,
                aliasIdentity='NATIVE_VCV_EXACT_ALLELE' if aliases.get(target['targetId']) else 'PREPARED_TRANSCRIPT_VERIFICATION_REQUIRED',
                aliasSources=[dict(vcv=r['vcv'], rawSha256=r['sourceSha256']) for r in aliases.get(target['targetId'], [])],
                declaredAliases=signals['variantAliases'], knownCitationReferences=[dict(pmid=p['pmid'],
                    sourceRawSha256=r['sourceSha256'], citation=p['citation'])
                    for r in aliases.get(target['targetId'], []) for p in r['pubmed']])
            if ledger['candidates'] or scope['knownCitationReferences']:
                scope.update(status='RELATIONSHIP_REVIEW_PENDING',
                             reasonCode='PP2_OTHER_PHENOTYPE_TARGET_PAPERS_NOT_REVIEWED')
        except (ValueError, KeyError, TypeError, OSError) as error:
            scope['reasonCode'] = reason(error)
    result['observedOriginals'] = {pin: dict(rawSha256=pin, bytes=len(raw),
        base64=base64.b64encode(raw).decode('ascii'), **({'provenance': provenance[pin]} if pin in provenance else {}))
        for pin, raw in originals.items()}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('input', 'output', 'work', 'cache'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--allow-public-network', action='store_true')
    args = parser.parse_args()
    raw = bounded(args.input, 64 * 1024 * 1024)
    packet = native.parse_json(raw)
    result = observe(packet, args.work, args.cache, args.allow_public_network)
    result['inputRawSha256'] = sha(raw)
    # Pure code fingerprints only: no credentials, model configuration or paid call.
    from bundle_model_contract import implementation as reading_implementation
    result['readingMethodSources'] = reading_implementation()
    with args.output.open('xb') as stream:
        stream.write(canonical(result))


if __name__ == '__main__':
    main()
