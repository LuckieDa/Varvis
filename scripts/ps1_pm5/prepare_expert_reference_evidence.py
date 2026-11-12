"""Offline batch extraction of pinned ClinVar / ClinGen expert evidence.

This feeds the existing review-material workflow, not a clinical classifier.
No HTTP, target selection, gene-specific policy, approval or runtime activation.
"""
import argparse
import copy
import json
from pathlib import Path
import re
from urllib.parse import urlsplit, parse_qs

try:
    from . import prepare_reference_evidence as base
except ImportError:
    import prepare_reference_evidence as base


def nodes(value, pointer=''):
    yield value, pointer
    if isinstance(value, dict):
        for key, child in value.items():
            yield from nodes(child, pointer + '/' + key.replace('~', '~0').replace('/', '~1'))
    elif isinstance(value, list):
        for i, child in enumerate(value):
            yield from nodes(child, pointer + '/' + str(i))


def strings(value, keys, pointer=''):
    return [dict(pointer=p, value=v) for v, p in nodes(value, pointer)
            if isinstance(v, str) and p.rsplit('/', 1)[-1] in keys]


def xml_nodes(value):
    for n, _ in nodes(value):
        if isinstance(n, dict) and 'tag' in n:
            yield n


def normalized_condition(value):
    return value if value.startswith('MONDO:') else 'MONDO:' + value


def criteria_from_sepio(document):
    rows = []
    for obj, pointer in nodes(document, '/data'):
        if not isinstance(obj, dict) or obj.get('type') != 'CriterionAssessment':
            continue
        criterion = obj.get('criterion') or {}
        rows.append(dict(pointer=pointer, code=criterion.get('label'),
            outcome=(obj.get('statementOutcome') or {}).get('label'),
            sourceDeclaredStrength=criterion.get('defaultStrength'),
            comments=strings(obj, {'comments', 'description'}, pointer),
            original=copy.deepcopy(obj)))
    return rows


def parse_codes(values):
    # Preserve malformed source labels (e.g. PM6_Very) as gaps, never infer Strong.
    result = []
    for value in values or []:
        match = re.fullmatch(r'(PVS1|PS[1-4]|PM[1-6]|PP[1-5]|BA1|BS[1-4]|BP[1-7])'
                            r'(?:_(VeryStrong|Very_Strong|Strong|Moderate|Supporting|StandAlone))?', value)
        result.append(dict(original=value, code=match[1] if match else None,
                           strengthSuffix=match[2] if match else None))
    return result


def applied_criteria_statements(comments, source_id):
    """Extract an explicit final SCV list, never infer adopted evidence from mentions.

    A missing SEPIO label stays missing. These are source-declared tokens, not
    reviewed strengths, independence findings or a newly calculated classification.
    """
    rows = []
    marker = 'ACMG/AMP criteria applied'
    for comment in comments:
        text, xpath = comment['text'], comment['xpath']
        if not re.search(r'/Classification\[\d+\]/Comment\[\d+\]$', xpath) or marker not in text:
            continue
        prefix, _, tail = text.partition(marker)
        header, separator, raw_suffix = tail.partition(':')
        tokens = raw_suffix.strip()
        if tokens.endswith('.'):
            tokens = tokens[:-1].rstrip()
        # Some source SCVs have one unmatched closing parenthesis after the list.
        # Preserve it in rawSuffix; do not repair any actual criterion token.
        if tokens.endswith(')'):
            tokens = tokens[:-1].rstrip()
        parsed = parse_codes([part.strip() for part in tokens.split(',')])
        valid = (text.count(marker) == 1 and (not prefix.strip() or prefix.rstrip().endswith('.'))
            and separator and len(header) <= 1024
            and (not header.strip() or re.fullmatch(
                r'(?:,\s+as specified by [^:]+|\s*\(specification version [^:]+\))', header))
            and not re.search(r'\b(?:not|no|none|hypothetical|proposed|example|considered|may|would|could)\b', header, re.I)
            and all(c['code'] for c in parsed)
            and len({c['code'] for c in parsed}) == len(parsed))
        if not valid:
            # A complete concluding list can follow criterion-by-criterion narrative.
            # Keep the whole comment and literal suffix. This extracts source tokens
            # only; it neither supplies a missing version nor approves the reference.
            summary = re.search(
                r'(?:^|(?<=\.))\s*In summary this variant meets criteria to be classified as '
                r'(?:pathogenic|likely pathogenic) for [^\r\n.]{1,512} based on the '
                r'ACMG/AMP criteria applied as specified by (?P<header>[^:\r\n]{1,256}):'
                r'\s*\((?P<list>[^()\r\n]{1,512})\)(?P<ending>\.?)\s*$', text)
            if summary:
                final = parse_codes([p.strip() for p in summary['list'].split(',')])
                earlier = text[:summary.start()]
                # An earlier direct list is a competing declaration, even if it agrees.
                competing = re.search(r'ACMG/AMP criteria applied[^:\r\n]{0,1024}:\s*\(?'
                                      r'(?:PVS1|PS[1-4]|PM[1-6]|PP[1-5]|BA1|BS[1-4]|BP[1-7])'
                                      r'(?:_[A-Za-z_]+)?\s*(?:,|[).])', earlier)
                valid = (not competing and all(c['code'] for c in final)
                         and len({c['code'] for c in final}) == len(final)
                         and not re.search(r'\b(?:not|no|none|hypothetical|proposed|example|considered|may|would|could)\b',
                                           summary['header'], re.I))
                if valid:
                    parsed = final
                    header = summary['header']
                    raw_suffix = text[summary.start('list'):]
        rows.append(dict(sourceId=source_id, xpath=xpath, originalText=text,
            rawSuffix=raw_suffix, status='EXPLICIT_SOURCE_LIST' if valid else 'UNPARSED_SOURCE_STATEMENT',
            criteria=parsed if valid else [],
            specificationVersionMentions=sorted(set(re.findall(
                r'\bspecification version\s+(\d+(?:\.\d+)+)', header, re.I))),
            scientificAdoption=False))
    return rows


def official_source(source):
    url = urlsplit(source['url'])
    base.require(url.scheme == 'https' and not url.username and not url.fragment, 'OFFICIAL_HTTPS_SOURCE_REQUIRED')
    if source['kind'] == 'CLINVAR_VCV':
        q = parse_qs(url.query, keep_blank_values=True)
        base.require(url.netloc == 'eutils.ncbi.nlm.nih.gov'
            and url.path == '/entrez/eutils/efetch.fcgi' and q.get('db') == ['clinvar']
            and q.get('rettype') == ['vcv'] and len(q.get('id', [])) == 1
            and (re.fullmatch(r'VCV\d+(?:\.\d+)?', q['id'][0])
                 or ('is_variationid' in q and re.fullmatch(r'[1-9][0-9]*', q['id'][0]))),
            'OFFICIAL_VCV_URL_REQUIRED')
    else:
        base.require(url.netloc in ('erepo.genome.network', 'erepo.clinicalgenome.org'), 'OFFICIAL_EREPO_URL_REQUIRED')
        if source['kind'] == 'EREPO_SUMMARY':
            base.require(url.path == '/evrepo/api/summary/classifications', 'EREPO_SUMMARY_URL_REQUIRED')
        else:
            base.require(source['kind'] == 'EREPO_SEPIO' and re.fullmatch(
                r'/evrepo/api/summary/classification/[a-f0-9-]{36}/doc/sepio/version/\d+\.\d+\.\d+', url.path),
                'VERSIONED_EREPO_URL_REQUIRED')


def extract(reference, sources):
    """Bind exact source identities; scientific adequacy remains a review task."""
    vid, allele = reference['variationId'], reference['allele']
    cv_desc, cv_raw = sources[reference['clinvarSource']]
    base.require(cv_desc['kind'] == 'CLINVAR_VCV', 'CLINVAR_SOURCE_KIND_REQUIRED')
    cv = base.parse_vcv(cv_raw, vid, allele)
    base.require(cv_desc['version'] == cv['vcv'], 'VCV_DESCRIPTOR_VERSION_MISMATCH')
    requested = parse_qs(urlsplit(cv_desc['url']).query)['id'][0]
    base.require(requested in (cv['vcv'], cv['vcv'].split('.')[0], vid), 'REQUESTED_VCV_MISMATCH')
    scvs = [s for s in cv['assertions'] if s['scv'] == reference['scv']]
    base.require(len(scvs) == 1, 'EXACT_SCV_VERSION_NOT_PRESENT')
    scv = scvs[0]
    base.require(cv['recordStatus'] == scv['recordStatus'] == 'current', 'SOURCE_RECORD_NOT_CURRENT')
    base.require(scv['reviewStatus'] in ('reviewed by expert panel', 'practice guideline'), 'EXPERT_SCV_REQUIRED')
    uuid, version = reference['erepoUuid'], reference['erepoVersion']
    keys = [n['attributes'].get('localKey') for n in scv['submissionIdentity']]
    condition_xrefs = {(n['attributes'].get('DB'), n['attributes'].get('ID'))
                      for n in xml_nodes(scv['condition']) if n['tag'] == 'XRef'}
    # Some original submissions qualify their record UUID with their exact
    # submitted disease identifier. Never strip an arbitrary suffix or use a
    # substring UUID match; the entire suffix must name the sole source xref.
    composite_key = (len(condition_xrefs) == 1 and len(keys) == 1
        and all(isinstance(v, str) and v for v in next(iter(condition_xrefs)))
        and keys == [uuid + '|' + ':'.join(next(iter(condition_xrefs)))])
    base.require(keys == [uuid] or composite_key, 'EXACT_SCV_EREPO_LINK_REQUIRED')
    sepio = None
    if reference.get('sepioSource'):
        ep_desc, ep_raw = sources[reference['sepioSource']]
        base.require(ep_desc['kind'] == 'EREPO_SEPIO' and ep_desc['version'] == version
            and urlsplit(ep_desc['url']).path.endswith('/classification/' + uuid + '/doc/sepio/version/' + version),
            'EREPO_URL_IDENTITY_MISMATCH')
        sepio = base.parse_json(ep_raw)['data']
        base.require(sepio['uuid'] == uuid and sepio['metadata']['version'] == version, 'EREPO_IDENTITY_MISMATCH')
        base.require(sepio.get('type') == 'VariantPathogenicityInterpretation', 'EREPO_SCHEMA_UNSUPPORTED')
    diseases = {normalized_condition(n['attributes']['ID']) for n in xml_nodes(scv['condition'])
                if n['tag'] == 'XRef' and n['attributes'].get('DB') == 'MONDO'}
    if sepio:
        base.require(not diseases or sepio['condition']['id'] in diseases,
                     'EXACT_SCV_DISEASE_BINDING_MISSING')
        base.require(sepio['statementOutcome']['label'].casefold() == scv['classification'].casefold(),
                     'EREPO_SCV_CLASSIFICATION_MISMATCH')
    summary = None
    summary_index = None
    if reference.get('summarySource'):
        sd, raw = sources[reference['summarySource']]
        base.require(sd['kind'] == 'EREPO_SUMMARY', 'SUMMARY_SOURCE_KIND_REQUIRED')
        rows = base.parse_json(raw)['data']
        base.require(isinstance(rows, list) and len(rows) <= 256, 'SUMMARY_ROW_LIMIT')
        matches = [r for r in rows if r.get('uuid') == uuid and r.get('docVersion') == version]
        base.require(len(matches) == 1, 'EXACT_SUMMARY_VERSION_NOT_PRESENT')
        summary = matches[0]
        summary_index = rows.index(summary)
        base.require(str(summary.get('cvId')) == vid and summary.get('retracted') is False,
                     'SUMMARY_VARIATION_MISMATCH_OR_RETRACTED')
        base.require((not diseases or summary.get('mondoId') in diseases) and summary.get('gene') in cv['geneSymbols']
                     and summary.get('classification', '').casefold() == scv['classification'].casefold(),
                     'SUMMARY_CONTEXT_MISMATCH')
        if sepio:
            base.require(summary['mondoId'] == sepio['condition']['id']
                         and summary.get('caId') == sepio['variant']['id'].removeprefix('CAR:'),
                         'SUMMARY_SEPIO_CONTEXT_MISMATCH')
        accession = cv['nativeLocation'].get('Accession')
        genomic = f"{accession}:g.{allele['position']}{allele['reference']}>{allele['alternate']}"
        base.require(genomic in summary.get('hgvs', []), 'SUMMARY_NATIVE_ALLELE_MISMATCH')
    base.require(sepio is not None or summary is not None, 'EXPERT_EVIDENCE_SOURCE_NOT_PROVIDED')
    assessments = criteria_from_sepio(sepio) if sepio else []
    met = parse_codes((summary or {}).get('metCodes'))
    unmet = parse_codes((summary or {}).get('unMetCodes'))
    statements = applied_criteria_statements(scv['comments'], reference['clinvarSource'])
    explicit = [s for s in statements if s['status'] == 'EXPLICIT_SOURCE_LIST']
    explicit_sets = {frozenset(c['original'] for c in s['criteria']) for s in explicit}
    statement_status = ('CONFLICTING_SOURCE_LISTS' if len(explicit_sets) > 1 else
        'UNPARSED_SOURCE_STATEMENT' if len(statements) != len(explicit) else
        'EXPLICIT_SOURCE_LIST' if explicit else 'NOT_PRESENT')
    source_codes = {c['code'] for s in explicit for c in s['criteria']}
    reasons = []
    if not diseases:
        reasons.append('EXACT_SCV_DISEASE_MAPPING_UNRESOLVED')
    if statement_status in ('CONFLICTING_SOURCE_LISTS', 'UNPARSED_SOURCE_STATEMENT'):
        reasons.append('SCV_APPLIED_CRITERIA_' + statement_status)
    if sepio is None:
        reasons.append('VERSIONED_SEPIO_SOURCE_NOT_PROVIDED')
    if summary is None:
        reasons.append('SUMMARY_VERSION_RETRACTION_AND_FINAL_CRITERIA_NOT_BOUND')
    if any(r['code'] is None for r in met + unmet):
        reasons.append('SUMMARY_CRITERION_TOKEN_UNSUPPORTED')
    if any(r['code'] is None for r in assessments):
        reasons.append('SEPIO_CRITERION_LABEL_MISSING')
    if any(r['code'] is not None and not parse_codes([r['code']])[0]['code'] for r in assessments):
        reasons.append('SEPIO_CRITERION_LABEL_UNSUPPORTED')
    if any(r['outcome'] not in ('Met', 'Not Met') for r in assessments):
        reasons.append('SEPIO_CRITERION_OUTCOME_UNSUPPORTED')
    if not assessments:
        reasons.append('SEPIO_CRITERION_ASSESSMENTS_MISSING')
    known_met = {r['code'] for r in assessments if r['outcome'] == 'Met' and r['code']}
    known_unmet = {r['code'] for r in assessments if r['outcome'] == 'Not Met' and r['code']}
    if summary is not None:
        if not known_met <= {r['code'] for r in met} or not known_unmet <= {r['code'] for r in unmet}:
            reasons.append('SUMMARY_SEPIO_CRITERIA_DISAGREE')
    if known_met & known_unmet:
        reasons.append('SEPIO_CRITERION_OUTCOME_CONFLICT')
    if explicit:
        if summary is not None and any(s != frozenset(r['original'] for r in met) for s in explicit_sets):
            reasons.append('SCV_SUMMARY_APPLIED_CRITERIA_DISAGREE')
        if any(not known_met <= {c['code'] for c in s['criteria']}
               or known_unmet & {c['code'] for c in s['criteria']} for s in explicit):
            reasons.append('SCV_SEPIO_APPLIED_CRITERIA_DISAGREE')
    comparison = sorted((known_met | {r['code'] for r in met} | source_codes) & {'PS1', 'PM5'})
    if comparison:
        reasons.append('COMPARISON_CRITERION_MET_REQUIRES_INDEPENDENT_REASSESSMENT')
    method = (sepio or {}).get('assertionMethod', {})
    statement_versions = {v for s in explicit for v in s['specificationVersionMentions']}
    if (len(statement_versions) > 1 or method.get('version') and statement_versions
            and statement_versions != {method['version']}):
        reasons.append('SCV_STATEMENT_SPECIFICATION_VERSION_DISAGREEMENT')
    narrative = (summary or {}).get('summaryDesc', '')
    narrative_matches = [c['xpath'] for c in scv['comments'] if narrative
                         and ' '.join(c['text'].split()) == ' '.join(narrative.split())]
    if summary and not narrative_matches:
        reasons.append('SCV_EREPO_NARRATIVE_EQUIVALENCE_NOT_ESTABLISHED')
    versions = sorted(set(re.findall(r'\bspecification version\s+(\d+(?:\.\d+)+)', narrative, re.I)))
    if not method.get('version'):
        reasons.append('STRUCTURED_SPECIFICATION_VERSION_MISSING')
    elif versions and versions != [method['version']]:
        reasons.append('SPECIFICATION_VERSION_DISAGREEMENT')
    if scv['classification'].casefold() not in ('pathogenic', 'likely pathogenic'):
        reasons.append('REFERENCE_CLASS_NOT_PATHOGENIC_OR_LIKELY_PATHOGENIC')
    texts = [dict(t, sourceId=reference['sepioSource'])
             for t in strings(sepio or {}, {'comments', 'description', 'label'}, '/data')]
    if summary:
        texts += [dict(sourceId=reference['summarySource'], pointer=f'/data/{summary_index}/summaryDesc', value=narrative)]
    texts += [dict(sourceId=reference['clinvarSource'], xpath=c['xpath'], value=c['text']) for c in scv['comments']]
    clues = [t for t in texts if re.search(r'\b(?:PS1|PM5|same amino acid|same codon|splice|splicing|RNA)\b', t['value'], re.I)]
    return dict(referenceId=reference['referenceId'], variationId=vid, allele=allele,
        bindingStatus=('EXACT_SOURCE_BOUND' if diseases else 'EXACT_RECORD_BOUND_CONDITION_UNRESOLVED'),
        reviewStatus='UNREVIEWED', scientificAdoption=False,
        vcv=cv['vcv'], geneSymbols=cv['geneSymbols'], originalAssertion=scv, otherAssertions=cv['assertions'],
        erepoUuid=uuid, erepoVersion=version, sourceIds=[reference[k] for k in
            ('clinvarSource', 'sepioSource', 'summarySource') if reference.get(k)],
        disease=sepio['condition'] if sepio else dict(id=summary['mondoId'], label=summary.get('condition')),
        originalExpertSummary=summary, originalRecordMetadata=(sepio or {}).get('metadata'),
        narrativeMatchLocators=narrative_matches,
        specification=dict(originalAssertionMethod=method, narrativeVersionMentions=versions,
                           originalClinVarMethod=scv['assertionAttributes'], versionEquivalence='NOT_INFERRED'),
        criterionAssessments=assessments, summaryMetCriteria=met, summaryUnmetCriteria=unmet,
        criterionStrengthSemantics='SEPIO_CRITERION_DEFAULT_NOT_ADOPTED',
        sourceAppliedCriteriaStatements=statements, sourceAppliedCriteriaStatus=statement_status,
        appliedComparisonCriteria=comparison, reviewClues=clues,
        gapReasons=reasons, reviewRequirements=['TARGET_SPECIFIC_INDEPENDENCE_AND_EVIDENCE_SUFFICIENCY',
            'CURRENT_POLICY_APPLICABILITY', 'EXACT_TARGET_DISEASE_AND_TRANSCRIPT',
            'EXACT_PAIR_RNA_REVIEW', 'EXTERNAL_REVIEW_AND_FINAL_MATERIAL_APPROVAL'],
        independence='NOT_EVALUATED', normalRnaClaimed=False)


def attach_existing(packet, rows):
    """Supplement only exact original assertions; never substitute newly found SCVs."""
    base.require(packet.get('schemaVersion') == 'missense-full-reference-evidence-v1'
                 and packet.get('reviewStatus') == 'UNREVIEWED' and packet.get('scientificAdoption') is False,
                 'UNREVIEWED_REFERENCE_EVIDENCE_REQUIRED')
    result = copy.deepcopy(packet)
    for ref in result['references']:
        for binding in ref['assertionBindings']:
            original = binding['sourceAssertion']
            exact = [r for r in rows if r['bindingStatus'] == 'EXACT_SOURCE_BOUND'
                and r['variationId'] == ref['variationId'] and r['allele'] == ref['allele']
                and r['originalAssertion']['scv'] == original['scv']]
            base.require(len(exact) <= 1, 'AMBIGUOUS_EXPERT_ATTACHMENT')
            if exact and binding['sourceBindingStatus'] == 'EXACT_VERSION_BOUND':
                selected = exact[0]
                reasons = base.bind_assertion(original, selected['originalAssertion'], 'current',
                                               selected['geneSymbols'])
                binding['expertEvidence'] = dict(status='EXACT_SOURCE_BOUND' if not reasons else 'UNBOUND',
                    referenceId=selected['referenceId'] if not reasons else None, reasons=reasons,
                    scientificAdoption=False)
            else:
                binding['expertEvidence'] = dict(status='NOT_AVAILABLE_FOR_EXACT_ASSERTION', scientificAdoption=False)
    return result


def prepare(manifest_path, pin, output):
    manifest_path, output = Path(manifest_path), Path(output)
    base.require(not output.exists(), 'OUTPUT_EXISTS_REFUSE_OVERWRITE')
    total, inputs = 0, []

    def read(path, expected):
        nonlocal total
        base.require(re.fullmatch(r'[a-f0-9]{64}', expected or ''), 'RAW_SHA256_REQUIRED')
        with path.open('rb') as stream:
            raw = stream.read(base.MAX_FILE + 1)
        total += len(raw)
        base.require(len(raw) <= base.MAX_FILE and total <= base.MAX_TOTAL, 'INPUT_SIZE_LIMIT')
        base.require(base.sha(raw) == expected, 'RAW_SHA256_MISMATCH')
        inputs.append(dict(logicalName=path.name, rawSha256=expected, bytes=len(raw)))
        return raw

    manifest = base.parse_json(read(manifest_path, pin))
    base.require(manifest.get('schemaVersion') == 'missense-expert-evidence-manifest-v1', 'MANIFEST_VERSION_INVALID')
    refs, descriptions = manifest['references'], manifest['sources']
    base.require(0 < len(refs) <= 256 and len(descriptions) <= 768, 'INPUT_COUNT_LIMIT')
    base.require(len({r['referenceId'] for r in refs}) == len(refs), 'DUPLICATE_REFERENCE_ID')
    base.require(len({(r['variationId'], r['scv']) for r in refs}) == len(refs), 'DUPLICATE_REFERENCE_ASSERTION')
    sources, descriptors, artifacts = {}, [], []
    root = manifest_path.resolve().parent
    for index, desc in enumerate(descriptions):
        official_source(desc)
        source_id = desc['sourceId']
        base.require(source_id not in sources, 'DUPLICATE_SOURCE_ID')
        raw = read(root / desc['path'], desc['rawSha256'])
        sources[source_id] = (desc, raw)
        name = f'source-{index:03d}.raw'
        artifacts.append((name, raw))
        descriptors.append(dict(path=name, rawSha256=base.sha(raw), format='BYTES', sourceDigestKind='RAW_BYTES',
            sourceRecord=dict(sourceId=source_id, version=desc['version'], url=desc['url'], sha256=base.sha(raw))))
    rows = []
    for reference in refs:
        try:
            rows.append(extract(reference, sources))
        except (ValueError, KeyError, TypeError, AttributeError, base.ET.ParseError) as error:
            rows.append(dict(referenceId=reference['referenceId'], variationId=reference['variationId'],
                bindingStatus='BLOCKED_SOURCE_BINDING', gapReasons=[str(error)], reviewStatus='UNREVIEWED',
                scientificAdoption=False))
    packet = dict(schemaVersion='missense-expert-reference-evidence-v1', draft=True, reviewStatus='UNREVIEWED',
        scientificAdoption=False, runtimeExecution='NOT_RUN', references=rows,
        boundReferences=sum(r['bindingStatus'] == 'EXACT_SOURCE_BOUND' for r in rows),
        limitations=['EXTRACTION_NOT_INDEPENDENT_CLINICAL_REVIEW', 'NO_TARGET_MATCH_OR_CATALOG_ACTIVATION',
            'HISTORICAL_SNAPSHOT_NOT_CURRENT_SURVEILLANCE', 'NO_IMPLIED_NORMAL_RNA',
            'NO_COMPARISON_SUPPORT_REMOVAL_OR_RECLASSIFICATION'])
    if manifest.get('referenceEvidence'):
        desc = manifest['referenceEvidence']
        existing = base.parse_json(read(root / desc['path'], desc['rawSha256']))
        attached = attach_existing(existing, rows)
        artifacts.append(('reference-evidence.UNREVIEWED.json', attached))
    artifacts += [('expert-reference-evidence.UNREVIEWED.json', packet),
        ('review-evidence-sources.json', dict(schemaVersion='missense-review-evidence-sources-v1',
            reviewStatus='UNREVIEWED', scientificAdoption=False, reviewEvidence=descriptors))]
    # Serialize/size-check everything before creating the output directory.
    encoded = [(n, v if isinstance(v, bytes) else (json.dumps(v, ensure_ascii=False, indent=2) + '\n').encode())
               for n, v in artifacts]
    base.require(all(len(raw) <= base.MAX_FILE for _, raw in encoded), 'OUTPUT_SIZE_LIMIT')
    output.mkdir()
    for name, raw in encoded:
        with (output / name).open('xb') as stream:
            stream.write(raw)
    (output / 'manifest.json').write_text(json.dumps(dict(inputs=inputs,
        outputs=[dict(path=n, bytes=len(raw), rawSha256=base.sha(raw)) for n, raw in encoded]), indent=2) + '\n')
    return packet


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ('manifest', 'manifest-sha256', 'output'):
        parser.add_argument('--' + arg, required=True)
    args = parser.parse_args()
    try:
        packet = prepare(args.manifest, args.manifest_sha256, args.output)
        print(json.dumps(dict(status='EXTRACTED_UNREVIEWED', references=len(packet['references']),
                             boundReferences=packet['boundReferences'], scientificAdoption=False)))
        return 0 if packet['boundReferences'] == len(packet['references']) else 2
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(json.dumps(dict(status='BLOCKED', reason=str(error), scientificAdoption=False)))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
