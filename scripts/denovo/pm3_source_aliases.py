"""Same-paper, explicit coding/protein crosswalks; never inverse-translate a protein.

Only flat, unambiguous source tables supply aliases. A selected patient's DNA key
or a single hit in a database is not proof of the paper's allele identity.
"""
import re
from copy import deepcopy
from extract_literature_cases import table_grid
from bundle_model_contract import cite
from literature_allele_identity import protein

HEADINGS = {
    'coding': re.compile(r'(?:cDNA|coding|nucleotide)(?: change| variant| aberration)?|DNA change|HGVS c\.', re.I),
    'protein': re.compile(r'(?:protein|amino acid)(?: change| substitution)?|HGVS p\.', re.I),
    'transcript': re.compile(r'(?:RefSeq )?transcript(?: ID| accession| version)?', re.I),
    'gene': re.compile(r'gene(?: symbol)?', re.I),
}
CODING = re.compile(r'(?:(NM_[0-9]+\.[1-9][0-9]*):)?(c\.[1-9][0-9]*[ACGT]>[ACGT])')
PROTEIN = re.compile(r'(?:p\.)?(?:[A-Z]|[A-Z][a-z]{2})[1-9][0-9]*(?:[A-Z]|[A-Z][a-z]{2})')


def table_gene(caption):
    if caption is None: return None
    match = re.fullmatch(r'([A-Za-z][A-Za-z0-9.-]{0,39}) (?:variants|mutations|genotypes)\.?', caption['text'])
    if not match:
        match = re.fullmatch(r'([A-Za-z][A-Za-z0-9.-]{0,39}) (?:pathogenic )?variants '
            r'(?:identified|detected) by [A-Za-z0-9 -]{1,60} in [1-9][0-9]* patients\.', caption['text'])
    return match[1] if match else None


def table_reference(source, table):
    """Explicit reference statement in this table's foot, not a paper default."""
    notes = [u for u in source['main']['units'] if u['unitId'].startswith(table['tableId']+'/table-wrap-foot[')
             and re.search(r'\breference sequence\b', u['text'], re.I)]
    if not notes: return None
    if len(notes) != 1: return {'transcript':None, 'notes':notes, 'conflict':True}
    note = notes[0]
    match = re.match(r'^Reference sequence:\s*(NM_[0-9]+\.[1-9][0-9]*)(?=;|\.|$)', note['text'], re.I)
    valid = match is not None and set(re.findall(r'NM_[0-9]+\.[1-9][0-9]*', note['text'])) == {match[1]}
    return {'transcript':match[1] if valid else None, 'notes':notes, 'conflict':not valid}


def crosswalks(source, issues=None):
    rows = []
    for table in source['main']['tables']:
        try: grid = table_grid(table)
        except ValueError:
            if issues is not None:issues.append({'tableId':table['tableId'],'reasonCode':'PM3_SOURCE_CROSSWALK_TABLE_LAYOUT_UNRESOLVED'})
            continue
        if not grid: continue
        header = grid[0]
        columns = {k: [i for i, c in enumerate(header) if c and p.fullmatch(c['text'].strip())]
                   for k, p in HEADINGS.items()}
        if any(len(columns[k]) != 1 for k in ('coding', 'protein')) or any(len(v)>1 for v in columns.values()): continue
        captions = [u for u in source['main']['units'] if u['kind']=='CAPTION'
                    and u['unitId']==table['tableId']+'/caption[1]']
        caption = captions[0] if len(captions)==1 else None
        caption_gene = table_gene(caption)
        reference = table_reference(source, table)
        for cells in grid[1:]:
            if any(c and (c.get('rowspan',1)!=1 or c.get('colspan',1)!=1) for c in [*header,*cells]): continue
            def cell(k):
                i=columns[k][0] if columns[k] else -1
                return cells[i] if 0<=i<len(cells) else None
            c, p, t, g = (cell(k) for k in ('coding','protein','transcript','gene'))
            if not c or not p: continue
            cm=CODING.fullmatch(re.sub(r'\s+','',c['text'])); pm=PROTEIN.fullmatch(p['text'].strip())
            if not pm: continue
            genes={g['text'].strip()} if g else set()
            if caption_gene: genes.add(caption_gene)
            if len(genes)!=1: continue
            gene=next(iter(genes))
            if not re.fullmatch(r'[A-Za-z][A-Za-z0-9.-]{0,39}',gene): continue
            txs={cm[1]} if cm and cm[1] else set()
            if t: txs.add(t['text'].strip())
            if reference and reference['transcript']: txs.add(reference['transcript'])
            units=[c,p]+([t] if t else [])+([g] if g else [caption])
            if reference: units.extend(reference['notes'])
            units += [header[columns[k][0]] for k in columns if columns[k]]
            reference_conflict = reference is not None and reference['conflict']
            valid=bool(not reference_conflict and cm and len(txs)==1 and re.fullmatch(r'NM_[0-9]+\.[1-9][0-9]*',next(iter(txs))))
            missing_transcript=bool(not reference_conflict and cm and not txs)
            rows.append({'geneSymbol':gene,'protein':protein(p['text']), 'coding':cm[2] if cm else c['text'],
                'transcript':next(iter(txs)) if valid else None, 'method':'EXPLICIT_SAME_PAPER_CODING_PROTEIN_TABLE',
                'qualification':'SOURCE_LINK_PENDING_SEQUENCE_CHECK' if valid else
                    'SOURCE_TRANSCRIPT_LOOKUP_REQUIRED' if missing_transcript else 'UNRESOLVED',
                'citations':[cite(source,{'unitId':u['unitId'],'quote':u['text']}) for u in units]})
    return rows


def matching(case, gene, value, transcripts):
    matches=[r for r in case.get('sourceAlleleCrosswalks',[]) if r['geneSymbol']==gene and r['protein']==protein(value)]
    # Never choose a convenient entry by the currently selected patient's key.
    if not matches: raise ValueError('PM3_PROTEIN_SOURCE_TABLE_UNRESOLVED' if case.get('sourceAlleleCrosswalkIssues') else 'PM3_PROTEIN_ONLY_SOURCE_CODING_LINK_MISSING')
    if any(r.get('qualification')=='UNRESOLVED' for r in matches):raise ValueError('PM3_PROTEIN_SOURCE_CODING_LINK_UNRESOLVED')
    keys={(r['transcript'],r['coding']) for r in matches}
    if len(keys)!=1: raise ValueError('PM3_PROTEIN_ONLY_SOURCE_CODING_LINK_AMBIGUOUS')
    tx, change=next(iter(keys))
    if transcripts:
        if len(set(transcripts))!=1 or (tx is not None and set(transcripts)!={tx}):
            raise ValueError('PM3_TRANSCRIPT_CONFLICT')
        tx=transcripts[0]
    return tx,change,deepcopy(matches)


def matching_coding(case, gene, change, expected_protein=None):
    matches = [r for r in case.get('sourceAlleleCrosswalks', []) if r['geneSymbol'] == gene and r['coding'] == change]
    if not matches: return None
    if any(r.get('qualification') == 'UNRESOLVED' for r in matches):
        raise ValueError('PM3_PROTEIN_SOURCE_CODING_LINK_UNRESOLVED')
    keys = {(r['transcript'], r['protein']) for r in matches}
    if len(keys) != 1: raise ValueError('PM3_TRANSCRIPT_CONFLICT')
    tx, aa = next(iter(keys))
    if expected_protein is not None and protein(expected_protein) != aa:
        raise ValueError('PM3_SOURCE_PROTEIN_CONFLICT')
    return (tx, aa, deepcopy(matches)) if tx else None
