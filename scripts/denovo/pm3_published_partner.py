"""Replay a release-bound expert classification after target-dependent evidence is removed."""
from datetime import date
import base64
import re
import xml.etree.ElementTree as ET

from core_local_identity import pinned
from literature_allele_identity import protein, read_record, walk
from model_literature_contract import parse
from pm3_partner_dependencies import INTRINSIC


CLASS = {'Pathogenic': 'PATHOGENIC', 'Likely pathogenic': 'LIKELY_PATHOGENIC',
         'Uncertain significance': 'VUS', 'Benign': 'BENIGN', 'Likely benign': 'LIKELY_BENIGN'}
SNV = r'c\.[1-9][0-9]*[ACGT]>[ACGT]'
INSERTION = r'c\.[1-9][0-9]*_[1-9][0-9]*ins[ACGT]{1,50}'
POINTS = {'supporting': 1, 'moderate': 2, 'strong': 4, 'verystrong': 8, 'very_strong': 8}
BASE = {'PVS1': 'verystrong', 'PS': 'strong', 'PM': 'moderate', 'PP': 'supporting'}
EVENT_CODES = {'PM3', 'PP1', 'PP4'}


def asserted_criteria(comment, label, version):
    summary = re.search(
        r'(?:^|(?<=\.))\s*In summary,? this variant meets (?:the )?criteria to be classified as '
        r'(?P<label>pathogenic|likely pathogenic) for [^\r\n.]{1,256} based on the ACMG/AMP criteria applied'
        r'(?:,\s+as specified by [^:\r\n]{1,256})?:\s*(?P<criteria>[A-Za-z0-9_, ]{1,512})\.\s*'
        r'\(VCEP specifications version (?P<version>[0-9.]+); date of approval [0-9/]+\)\.?$', comment, re.I)
    if (summary is None or summary['label'].casefold() != label.casefold() or summary['version'] != version
            or len(re.findall(r'ACMG/AMP criteria applied', comment, re.I)) != 1):
        return None
    rows = []
    for token in summary['criteria'].split(','):
        source = token.strip()
        parsed = re.fullmatch(r'(PVS1|PS[1-4]|PM[1-6]|PP[1-5])(?:_(Supporting|Moderate|Strong|VeryStrong|Very_Strong))?',
                              source, re.I)
        if parsed is None:
            return None  # A mixed or unsupported basis needs its own reviewed combination.
        code = parsed[1].upper()
        strength = parsed[2].casefold() if parsed[2] else BASE['PVS1' if code == 'PVS1' else code[:2]]
        rows.append({'code': code, 'sourceCriterion': source, 'strength': strength, 'points': POINTS[strength]})
    if len({r['code'] for r in rows}) != len(rows):
        return None
    return rows, comment[:summary.start()]


def exact_assertion(sources, identity, binding, disease, match, raw_originals, lookup=None):
    """Re-read the native SCV, including its own assertion method and exact transcript."""
    identity_key = ('allele', 'hgncId', 'geneSymbol')
    if lookup is None:
        lookup = sources.lookup(identity['allele'], identity['geneSymbol'])
    rule = match['reviewedCaseRule']
    method = rule['assertionMethod']
    captured, seen = [], set()
    for candidate in lookup.get('assertions', []):
        if candidate.get('sourceAccession', {}).get('SubmitterName') != method['issuer']:
            continue
        sha, vcv, scv = (candidate[k] for k in ('sourceSha256', 'vcv', 'scv'))
        key = (sha, scv)
        if key in seen:
            continue
        seen.add(key)
        raw = next((raw for _, raw, spec in sources.records if spec['sha256'] == sha), None)
        if raw is None:
            specs = [s for s in lookup.get('acquisition', {}).get('records', []) if s['sha256'] == sha]
            if len(specs) != 1:
                continue
            raw = pinned(sources.root, specs[0], 4 * 1024 * 1024)
        record = read_record(raw, sha, vcv.split('.')[0])
        if record['vcv'] != vcv or any(record[k] != identity[k] for k in identity_key):
            continue
        aliases = [a['citation'] for a in record['pairedHgvs'] if a['transcript'] == binding['ruleTranscript']
                   and a['coding'] == binding['coding'] and a['protein'] == identity.get('proteinChange')]
        if not aliases:
            continue
        xml = ET.fromstring(raw)
        locations = {id(node): path for node, path in walk(xml, '/' + xml.tag + '[1]')}
        for assertion in xml.iter('ClinicalAssertion'):
            acc = assertion.find('ClinVarAccession')
            if (acc is None or acc.get('Accession', '') + '.' + acc.get('Version', '') != scv
                    or acc.get('SubmitterName') != method['issuer'] or not re.fullmatch(r'[1-9][0-9]*', acc.get('OrgID', ''))
                    or assertion.findtext('RecordStatus') != 'current'):
                continue
            label = assertion.findtext('Classification/GermlineClassification')
            ids = {x.get('ID', '').removeprefix('MONDO:') for x in assertion.findall('TraitSet/Trait/XRef') if x.get('DB') == 'MONDO'}
            modes = {a.text for a in assertion.findall('AttributeSet/Attribute[@Type="ModeOfInheritance"]')}
            if (label not in CLASS or assertion.findtext('Classification/ReviewStatus') != 'reviewed by expert panel'
                    or ids != {disease.removeprefix('MONDO:')} or modes != {'Autosomal recessive inheritance'}):
                continue
            methods = [a for a in assertion.findall('AttributeSet') if a.find('Attribute[@Type="AssertionMethod"]') is not None]
            if (len(methods) != 1 or methods[0].findtext('Attribute[@Type="AssertionMethod"]') != method['label']
                    or [e.text for e in methods[0].findall('Citation/URL')] != [method['url']]):
                continue
            date_value = date.fromisoformat(assertion.find('Classification').get('DateLastEvaluated', ''))
            if date_value > date.today():
                continue
            comments = [{'quote': c.text or '', 'locator': locations[id(c)]} for c in assertion.findall('Classification/Comment')]
            versions = {v for c in comments for v in re.findall(r'Version\s+([0-9]+(?:\.[0-9]+)+)', c['quote'], re.I)}
            if versions and versions != {rule['apiVersion']}:
                continue
            raw_originals[sha] = base64.b64encode(raw).decode('ascii')
            captured.append({'scv': scv, 'vcv': vcv, 'sourceClassification': label, 'sourceSha256': sha,
                'sourceUrl': candidate['sourceUrl'], 'sourceLocator': locations[id(assertion)], 'sourceAllele': record['allele'],
                'specificationKey': match['specificationKey'], 'version': rule['apiVersion'], 'method': method,
                'methodLocator': locations[id(methods[0])], 'transcript': binding['ruleTranscript'], 'aliasCitation': aliases[0],
                'dateLastEvaluated': date_value.isoformat(), 'comments': comments})
    if len(captured) == 1:
        return 'VERIFIED', captured[0]
    return ('CONFLICT' if captured else 'UNKNOWN'), None


def reviewed_dependencies(root, pin):
    if pin is None:
        return {}
    raw = parse(pinned(root, pin, 262144))
    if raw.get('schemaVersion') != 'pm3-reviewed-assertion-dependencies-v1' or len(raw['assertions']) > 32:
        raise ValueError('PM3_PARTNER_DEPENDENCY_REVIEW_SCHEMA_INVALID')
    result = {}
    for row in raw['assertions']:
        key = (row['scv'], row['sourceSha256'], row['transcript'])
        if key in result or not isinstance(row['criteria'], dict) or not set(row['criteria']) <= EVENT_CODES:
            raise ValueError('PM3_PARTNER_DEPENDENCY_REVIEW_CONFLICT')
        result[key] = row['criteria']
    return result


def mentioned_variants(text):
    return set(re.findall(r'(?<![\w.])(?:' + SNV + '|' + INSERTION + r')(?![A-Za-z0-9_>])', text))


def mentioned_pmids(text):
    return set(re.findall(r'\bPMID:\s*([1-9][0-9]*)\b', text))


def supported_counterpart(quote, coding, protein_change):
    if coding in mentioned_variants(quote):
        return True
    aliases = re.findall(r'\bp\.[A-Za-z]{1,3}[1-9][0-9]*[A-Za-z*]{1,3}\b', quote)
    for alias in aliases:
        try:
            if protein(alias) == protein_change:
                return True
        except ValueError:
            pass
    return False


def target_exclusion(assertion, target, target_identity, partner_identity, review, version):
    """Retain the original list, but never count a target-dependent criterion."""
    candidates = [(comment, parsed) for comment in assertion['comments']
                  if (parsed := asserted_criteria(comment['quote'], assertion['sourceClassification'], version)) is not None]
    out = {'classification': 'UNKNOWN', 'independentClassification': 'UNKNOWN',
           'method': 'SOURCE_REVIEWED_TARGET_EXCLUSION_AND_RELEASED_POINTS',
           'sourceClassification': CLASS[assertion['sourceClassification']], 'sourceAssertions': [assertion],
           'assertionAssessments': [], 'issues': [], 'businessPublishable': False}
    if len(candidates) != 1:
        out['issues'] = ['PM3_PARTNER_RELEASED_COMPLETE_CRITERIA_LIST_REQUIRED']
        return out
    comment, (criteria, narrative) = candidates[0]
    audit = out['assertionAssessments']
    target_coding, partner_coding = target_identity['coding'], partner_identity['coding']
    for criterion in criteria:
        code = criterion['code']
        item = {**criterion, 'sourceLocator': comment['locator'], 'status': 'UNRESOLVED'}
        audit.append(item)
        if code in INTRINSIC:
            item['status'] = 'RETAINED_INTRINSIC'
            continue
        source = review.get(code, {})
        quote = source.get('quote', '')
        pmids = source.get('pmids', [])
        if (not quote or narrative.count(quote) != 1 or not pmids or len(pmids) != len(set(pmids))
                or set(pmids) != mentioned_pmids(quote)
                or not re.search(r'\b' + re.escape(criterion['sourceCriterion']) + r'\b', quote, re.I)):
            continue
        item.update(sourceQuote=quote, sourcePmids=pmids)
        linked = source.get('targetAssertionQuote')
        if linked:
            refs = [c for c in target['comments'] if c['quote'].count(linked) == 1]
            # The review identifies a shared proband, not merely a shared PMID.
            # Confirm both cited passages and the exact alternate participant.
            if (len(refs) != 1 or not mentioned_pmids(linked).intersection(pmids)
                    or not supported_counterpart(linked, partner_coding, partner_identity['proteinChange'])):
                continue
            item.update(status='EXCLUDED_TARGET_DEPENDENT',
                        targetCitation={'scv': target['scv'], 'sourceSha256': target['sourceSha256'],
                                        'sourceUrl': target['sourceUrl'], 'locator': refs[0]['locator'], 'quote': linked})
        elif code in {'PM3', 'PP1'}:
            comparators = source.get('otherCodingAlleles', [])
            if (not re.fullmatch(SNV, target_coding) or not re.fullmatch(SNV, partner_coding) or not comparators
                    or len(comparators) != len(set(comparators))
                    or any(not re.fullmatch(SNV + '|' + INSERTION, c) for c in comparators)
                    or set(comparators) != mentioned_variants(quote) - {partner_coding}):
                continue
            item.update(status='EXCLUDED_TARGET_DEPENDENT' if target_coding in comparators else 'RETAINED_TARGET_INDEPENDENT',
                        comparisonTranscript=assertion['transcript'], otherCodingAlleles=comparators)
    unresolved = [c['code'] for c in audit if c['status'] == 'UNRESOLVED']
    if unresolved:
        out['issues'] = ['PM3_PARTNER_TARGET_EXCLUSION_SOURCES_REQUIRED']
        out['missingCriteria'] = unresolved
        return out
    points = sum(c['points'] for c in audit if c['status'].startswith('RETAINED_'))
    classification = 'PATHOGENIC' if points >= 10 and out['sourceClassification'] == 'PATHOGENIC' else (
        'LIKELY_PATHOGENIC' if points >= 6 else 'UNKNOWN')
    out.update(independentPoints=points, classification=classification, pathogenicThreshold=10, likelyPathogenicThreshold=6)
    if classification == 'UNKNOWN':
        out.update(independentClassification='FAILED', issues=['PM3_PARTNER_INDEPENDENT_P_LP_THRESHOLD_NOT_MET'])
    else:
        out.update(independentClassification='VERIFIED', qualifiedScvs=[assertion['scv']])
    return out


def attach(observations, sources, reviews, review_issue=None):
    for case in observations.get('cases', []):
        for row in case.get('resolvedObservations', []):
            selection = row.get('policySelection', {})
            matches = selection.get('matches', [])
            if (selection.get('status') != 'SELECTED' or len(matches) != 1 or not matches[0].get('reviewedCaseRule')
                    or row['reportedObservation'].get('phase') == 'CIS'):
                continue
            match = matches[0]
            classified = {}
            originals = observations.setdefault('partnerSourceOriginals', {})
            for role, binding in row['publishedTranscriptBinding'].items():
                identity = row['identities'][role]
                try:
                    status, assertion = exact_assertion(sources, identity, binding, row['diseaseContext']['diseaseId'],
                        match, originals, row.get('partnerClassificationSources') if role == 'partner' else None)
                except (ValueError, OSError, KeyError, TypeError):
                    status, assertion = 'UNKNOWN', None
                classified[role] = {'qualification': status, 'reasonCode': 'PM3_SAME_RELEASE_CLASSIFICATION_VERIFIED' if assertion else
                    'PM3_SAME_RELEASE_CLASSIFICATION_CONFLICT' if status == 'CONFLICT' else 'PM3_SAME_RELEASE_CLASSIFICATION_REQUIRED'}
                if assertion:
                    classified[role]['assertion'] = assertion
            row['publishedClassifications'] = classified
            if 'partner' not in classified:
                continue
            target = classified['target'].get('assertion')
            partner = classified['partner'].get('assertion')
            if not target or not partner:
                row['partnerQualification'] = {'classification': 'UNKNOWN', 'independentClassification': 'UNKNOWN',
                    'issues': ['PM3_SAME_RELEASE_CLASSIFICATION_REQUIRED'], 'businessPublishable': False}
                continue
            key = (partner['scv'], partner['sourceSha256'], partner['transcript'])
            qualification = target_exclusion(partner, target, row['identities']['target'], row['identities']['partner'],
                                             reviews.get(key, {}), match['reviewedCaseRule']['apiVersion'])
            if review_issue and qualification.get('missingCriteria'):
                qualification['issues'].insert(0, review_issue)
            qualification['classificationPointsSource'] = match['reviewedAttachments']['classificationPoints']
            row['partnerQualification'] = qualification
