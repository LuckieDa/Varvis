"""Reusable raw public-paper reviews; no saved diagnosis, context or score adopted.

Exact paper bytes and public target isolate entries. Every read revalidates the
original request, response, parent chain, receipt and approved implementation.
The normal evaluator still derives facts and applies its current context/ledger.
"""
from pathlib import Path
import os
import re
from tempfile import NamedTemporaryFile

from model_literature_contract import canonical, digest, parse
from model_transport import bounded, exclusive
from pp1_literature_contract import require
from run_pp1_literature_model import verify, reassess
from literature_manual_review import entries as manual_entries, read as read_manual


def target_key(target):
    def notation(value):
        value=re.sub(r'\s+', '', value)
        match=re.fullmatch(r'p\.?\(([A-Za-z*]+[1-9][0-9]*[A-Za-z*=]+)\)',value)
        return 'p.'+match[1] if match else value
    return {'geneSymbol':target['geneSymbol'],
            'variantAliases':sorted({notation(v) for v in target['variantAliases']})}


def folder(budget, pmcid, pin, target):
    return Path(budget)/'pp1-reviewed-artifacts'/digest(canonical([pmcid, pin, target_key(target)]))


def archive(library, pmcid, pin, target):
    require(re.fullmatch(r'PMC[1-9][0-9]*', pmcid) is not None, 'PP1_REVIEW_CACHE_IDENTITY_CHANGED')
    key = digest(canonical([pin, target_key(target)]))
    return Path(library)/'reviewed-evidence'/pmcid/'reviews'/'PP1'/'automated'/key


def store_review(path, raw):
    with NamedTemporaryFile(dir=path.parent, suffix='.tmp', delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    try:
        # The complete artifact becomes discoverable under its content address.
        if not path.exists():
            os.replace(temporary, path)
        require(bounded(path,16*1024*1024) == raw, 'PP1_REVIEW_CACHE_CHANGED')
    finally:
        temporary.unlink(missing_ok=True)


def register(library, budget, artifacts):
    """Also usable for explicit migration of existing, unchanged review originals."""
    added = []
    for artifact in artifacts:
        raw = bounded(artifact, 16*1024*1024)
        original = verify(library, parse(raw), verify_derived=False)
        b = original['binding']
        require(b['stage'] == 'SOURCE_REVIEW', 'PP1_REVIEW_CACHE_AUDIT_REQUIRED')
        require(reassess(library, artifact)['checked'].get('status') != 'SOURCE_CHECK_REJECTED',
                'PP1_REVIEW_CACHE_SOURCE_REJECTED')
        directory = archive(library, b['pmcid'], b['bundleSha256'], b['target'])
        sha = digest(raw)
        directory.mkdir(parents=True, exist_ok=True)
        path = directory/(sha+'.json')
        if path.exists():
            require(bounded(path,16*1024*1024) == raw, 'PP1_REVIEW_CACHE_CHANGED')
        else:
            with exclusive(directory):
                require(len(list(directory.glob('*.json'))) < 32 or path.exists(), 'PP1_REVIEW_CACHE_LIMIT')
                store_review(path, raw)
        added.append({'sha256':sha, 'path':str(path.resolve())})
    return added


def restore(library, budget, pmcid, pin, target, output):
    directories = dict.fromkeys((archive(library, pmcid, pin, target), folder(budget, pmcid, pin, target),
                                Path(budget)/'pp1-reviewed-artifacts'/digest(canonical([pmcid,pin,target]))))
    paths = [path for directory in directories for path in sorted(directory.glob('*.json'))]
    if not paths: return []
    output = Path(output)
    require(not output.exists(), 'PP1_REVIEW_CACHE_NEW_OUTPUT_REQUIRED')
    entries = {}
    total = 0
    for path in paths:
        require(re.fullmatch('[a-f0-9]{64}', path.stem), 'PP1_REVIEW_CACHE_NAME')
        raw = bounded(path,16*1024*1024); total += len(raw)
        require(total <= 128*1024*1024, 'PP1_REVIEW_CACHE_BYTES_LIMIT')
        require(digest(raw) == path.stem, 'PP1_REVIEW_CACHE_CHANGED')
        if path.name in entries:
            continue
        original = verify(library, parse(raw), verify_derived=False)
        b = original['binding']
        require(b['stage'] == 'SOURCE_REVIEW' and b['pmcid'] == pmcid
                and b['bundleSha256'] == pin and target_key(b['target']) == target_key(target),
                'PP1_REVIEW_CACHE_IDENTITY_CHANGED')
        require(reassess(library, path)['checked'].get('status') != 'SOURCE_CHECK_REJECTED',
                'PP1_REVIEW_CACHE_SOURCE_REJECTED')
        entries[path.name] = raw
    require(len(entries) <= 32, 'PP1_REVIEW_CACHE_LIMIT')
    if not entries: return []
    output.mkdir(parents=True)
    for name,raw in entries.items(): (output/name).write_bytes(raw)
    return [output/name for name in entries]


def manual_matches(library, target, selection, pmcid=None, pin=None):
    current = target_key(target)
    for path, document in manual_entries(library,'PP1',pmcid):
        binding = document['binding']
        cached = target_key(binding['target'])
        if (cached['geneSymbol'] == current['geneSymbol'] and set(cached['variantAliases']).intersection(current['variantAliases'])
                and (pin is None or (binding['bundleSha256'] == pin and cached == current))
                and all(selection.get(k) == v for k,v in document['material']['scope'].items())):
            read_manual(library,document)
            yield path, document


def discover(library, budget, target, selection=None):
    """Locate existing reviewed targets, not papers by gene co-occurrence.

    This bounded migration-compatible index walk reads review headers only for
    filtering. Matching receipts/source chains are verified before returning a
    retrieval candidate. Context and scores are never reused.
    """
    root=Path(budget)/'pp1-reviewed-artifacts'; candidates={};issues=[];total=0;count=0
    if selection is not None:
        try:
            for path, document in manual_matches(library,target,selection):
                binding = document['binding']
                key = (binding['pmcid'],binding['bundleSha256'])
                row = candidates.setdefault(key,{'pmcid':key[0],'pmid':None,'doi':None,'title':'',
                    'flags':[],'origins':[],'matches':[],'localReviewedBundleSha256':key[1]})
                row['origins'].append({'kind':'LOCAL_MANUAL_REVIEW','sha256':path.stem})
        except (ValueError,OSError,KeyError,TypeError):
            issues.append('PP1_LOCAL_MANUAL_REVIEW_REJECTED')
    stable = (Path(library)/'reviewed-evidence').glob('PMC*/reviews/PP1/automated/*/*.json')
    from itertools import chain
    for path in chain(stable, root.glob('*/*.json')):
        count+=1
        if count>2048 or total+path.stat().st_size>128*1024*1024:
            issues.append('PP1_LOCAL_REVIEW_INDEX_LIMIT');break
        try:
            raw=bounded(path,16*1024*1024);total+=len(raw);value=parse(raw);b=value.get('binding',{})
            cached,current=target_key(b.get('target',{})),target_key(target)
            # Retrieval may match one of several transcript spellings. The paper
            # worker still projects aliases actually printed in this source,
            # restores exact reviews and resolves genomic identity before scoring.
            if cached['geneSymbol']!=current['geneSymbol'] or not set(cached['variantAliases']).intersection(current['variantAliases']):continue
            require(path.stem==digest(raw),'PP1_REVIEW_CACHE_CHANGED')
            verified=verify(library,value,verify_derived=False);b=verified['binding']
            require(b['stage']=='SOURCE_REVIEW','PP1_REVIEW_CACHE_AUDIT_REQUIRED')
            require(reassess(library,path)['checked'].get('status')!='SOURCE_CHECK_REJECTED','PP1_REVIEW_CACHE_SOURCE_REJECTED')
            key=(b['pmcid'],b['bundleSha256'])
            row=candidates.setdefault(key,{'pmcid':b['pmcid'],'pmid':None,'doi':None,'title':'',
                'flags':[],'origins':[],'matches':[], 'localReviewedBundleSha256':b['bundleSha256']})
            origin={'kind':'LOCAL_REVIEWED_ARTIFACT','sha256':digest(raw)}
            if origin not in row['origins']:row['origins'].append(origin)
        except (ValueError,OSError,KeyError,TypeError):issues.append('PP1_LOCAL_REVIEW_ENTRY_REJECTED')
    # Different snapshots of one paper are not arbitrarily merged or promoted.
    pmcs=[key[0] for key in candidates]
    rows=[v for (pmcid,_),v in candidates.items() if pmcs.count(pmcid)==1]
    if len(rows)!=len(candidates):issues.append('PP1_LOCAL_REVIEW_BUNDLE_CONFLICT')
    return {'candidates':rows,'issues':sorted(set(issues)),'complete':not issues,
            'meaning':'RETRIEVAL_ONLY_CURRENT_IDENTITY_CONTEXT_AND_RULE_RECHECK_REQUIRED'}
