"""Load exact-source PM3 observations from the shared curator registry."""
from literature_allele_identity import protein
from literature_manual_review import entries, read
from public_literature_workflow import safe_error


def alias_keys(target):
    keys = set()
    for alias in target['variantAliases']:
        notation = alias.split(':')[-1]
        keys.add(notation)
        amino_acid = protein(notation)
        if amino_acid:
            keys.add(amino_acid)
    return keys


def reviewed_papers(library, target, pmcid=None):
    papers, issues = {}, []
    aliases = alias_keys(target)
    try:
        for path, document in entries(library, 'PM3', pmcid):
            binding = document['binding']
            reviewed_target = binding['target']
            if target['geneSymbol'] != reviewed_target['geneSymbol'] or not aliases & alias_keys(reviewed_target):
                continue
            paper = binding['pmcid']
            try:
                source, checked = read(library,document)
            except (ValueError,OSError,KeyError,TypeError) as error:
                issues.append({'pmcid':paper, 'reasonCode':safe_error(error)})
                continue
            receipt = {**document['review'], 'artifactSha256':path.stem}
            papers.setdefault(paper,{}).setdefault(source['bundleSha256'],[]).append((receipt,checked))
    except (ValueError,OSError,KeyError,TypeError) as error:
        # An incomplete or corrupt registry cannot certify a conflict-free set.
        return {}, [{'reasonCode':safe_error(error)}]
    return papers, issues
