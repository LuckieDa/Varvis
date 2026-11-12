"""Discover pinned PM3 library sources offline; matches are reading hints only.

No directory scan, network request, model call or saved score adoption. The
ordinary paper processor must still resolve allele/case identity and policy.
"""
from pathlib import Path
import re

from model_literature_contract import parse
from model_transport import bounded
from bundle_model_contract import load, require
from pm3_literature_contract import validate_target
from literature_allele_identity import protein


def discover(library, target):
    validate_target(target)
    index = Path(library)/'evidence/PM3/index.json'
    out = {'candidates':[], 'issues':[], 'complete':True,
           'meaning':'RETRIEVAL_ONLY_CURRENT_IDENTITY_CONTEXT_AND_RULE_RECHECK_REQUIRED'}
    if not index.exists(): return out
    document = parse(bounded(index, 1048576))
    require(document.get('schemaVersion') == 'public-paper-evidence-index-v1'
            and document.get('criterion') == 'PM3' and isinstance(document.get('documents'), list),
            'PM3_LOCAL_INDEX_INVALID')
    require(len(document['documents']) <= 200, 'PM3_LOCAL_INDEX_LIMIT')
    aliases = set()
    for alias in target['variantAliases']:
        alias = alias.split(':')[-1]
        aliases.add(alias)
        if alias.startswith(('c.', 'p.')): aliases.add(alias[2:])
        if alias.startswith('p.'):
            short = protein(alias)
            if short: aliases.update((short, 'p.'+short))
    patterns = [re.compile(r'(?<![A-Za-z0-9])'+r'\s*'.join(re.escape(c) for c in alias)
                           +r'(?![A-Za-z0-9])', re.I) for alias in aliases]
    gene = re.compile(r'(?<![A-Za-z0-9])'+re.escape(target['geneSymbol'])+r'(?![A-Za-z0-9])', re.I)
    seen = set()
    for entry in document['documents']:
        pmcid = entry.get('pmcid')
        try:
            require(pmcid not in seen, 'PM3_LOCAL_DUPLICATE_PAPER'); seen.add(pmcid)
            bundles = entry.get('acquisitionBundles', [])
            # Without an unambiguous source snapshot, do not pick a convenient
            # older bundle or omit attachments to manufacture a cache hit.
            require(len(bundles) == 1, 'PM3_LOCAL_BUNDLE_AMBIGUOUS')
            pin = bundles[0]['sha256']
            source = load(library, pmcid, pin, criterion='PM3')
            units = source['units']
            if not any(gene.search(u['text']) for u in units): continue
            hits = [u['unitId'] for u in units if any(p.search(u['text']) for p in patterns)]
            if not hits: continue
            out['candidates'].append({'pmcid':pmcid, 'pmid':None, 'doi':None, 'title':'',
                'flags':[], 'matches':[], 'localReviewedBundleSha256':pin,
                'origins':[{'kind':'REGISTERED_LOCAL_SOURCE', 'bundleSha256':pin,
                            'matchedUnitIds':hits[:20], 'totalMatchedUnits':len(hits),
                            'matchMeaning':'GENE_AND_NOTATION_READING_HINT_NOT_IDENTITY_PROOF'}]})
        except (ValueError, OSError, KeyError, TypeError) as error:
            code = str(error)
            if not re.fullmatch('[A-Z][A-Z0-9_]{1,160}', code): code='PM3_LOCAL_SOURCE_INVALID'
            out['issues'].append({'pmcid':pmcid, 'reasonCode':code})
    out['complete'] = not out['issues']
    return out
