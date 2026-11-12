"""Build bounded search clues from formal candidates and pinned local ClinVar.

Coordinates stay local: only gene/HGVS retrieval terms and public PMIDs reach
the search transport. No new scientific identity or public-data claim is made.
"""
import re
from pathlib import Path

from discover_public_literature import GENE, TERM, require
from literature_allele_identity import read_record
from model_literature_contract import canonical, digest
from core_local_identity import pinned


def allele(value):
    if not isinstance(value, dict):
        return None
    if set(value) != {'genomeAssembly', 'chromosome', 'position', 'reference', 'alternate'}:
        return None
    if (value['genomeAssembly'] not in {'GRCh37', 'GRCh38'} or
            not isinstance(value['chromosome'], str) or type(value['position']) is not int or value['position'] < 1 or
            any(not isinstance(value[k], str) or not re.fullmatch(r'[ACGT]{1,4096}', value[k])
                for k in ('reference', 'alternate'))):
        return None
    return {'assembly': value['genomeAssembly'], 'contig': value['chromosome'].removeprefix('chr'),
            'position': value['position'], 'reference': value['reference'], 'alternate': value['alternate']}


def build_targets(selected, root, context, work):
    require(isinstance(selected, list) and 1 <= len(selected) <= 50, 'SELECTED_TARGET_LIMIT')
    records, record_issues = [], []
    specs = context.get('identity', {}).get('clinvar', []) if context else []
    require(isinstance(specs, list) and len(specs) <= 100, 'CLINVAR_SOURCE_LIMIT')
    for spec in specs:
        # An altered source is a failure, never a fresh no-record result.
        raw = pinned(root, spec, 4 * 1024 * 1024)
        try:
            records.append(read_record(raw, spec['sha256'], spec['accession']))
        except ValueError as error:
            record_issues.append(str(error).split(':')[0])
    targets = []
    for index, row in enumerate(selected):
        require(isinstance(row, dict) and set(row) == {'geneSymbol', 'allele', 'variantAliases'}, 'SELECTED_TARGET_SCHEMA')
        gene, aliases = row['geneSymbol'], row['variantAliases']
        require(isinstance(gene, str) and re.fullmatch(GENE, gene) and isinstance(aliases, list)
                and len(aliases) <= 64 and all(isinstance(a, str) and re.fullmatch(TERM, a) for a in aliases), 'SELECTED_SIGNAL_INVALID')
        key = allele(row['allele'])
        sources, pmids, public_aliases = [], [], []
        for record in records:
            if key != record['allele'] or gene != record['geneSymbol']:
                continue
            sources.append({'vcv': record['vcv'], 'sha256': record['sourceSha256']})
            pmids.extend(p['pmid'] for p in record['pubmed'])
            for pair in record['pairedHgvs']:
                public_aliases.extend([pair['transcript'] + ':' + pair['coding'], pair['coding'], 'p.' + pair['protein']])
        issues = list(record_issues)
        if not sources:
            issues.append('NO_EXACT_CONFIGURED_CLINVAR_RECORD' if specs else 'CLINVAR_RECORDS_NOT_CONFIGURED')
        if key is None:
            issues.append('SELECTED_ALLELE_UNAVAILABLE_FOR_CITATION_MATCH')
        merged = list(dict.fromkeys([*public_aliases, *aliases]))
        ids = sorted(set(pmids), key=int)
        require(len(merged) <= 64 and len(ids) <= 100, 'SIGNAL_LIMIT')
        if not merged:
            issues.append('VARIANT_SEARCH_ALIASES_UNAVAILABLE')
        source = {'schemaVersion': 'denovo-selected-search-signals-v1', 'geneSymbol': gene,
                  'variantAliases': merged, 'citationPmids': ids, 'citationSources': sources, 'issues': issues}
        raw = canonical(source)
        path = Path(work) / f'target-{index}.json'
        path.write_bytes(raw)
        targets.append({'targetId': f'target-{index}', 'sourceKind': 'SELECTED_VARIANT_SIGNALS',
                        'source': {'path': path.name, 'sha256': digest(raw)}})
    return targets
