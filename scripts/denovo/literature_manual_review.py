"""Register source-bound human reviews through a local curator command.

Each evidence adapter validates its own structured facts. The registry stores
original observations and their locators, independent of model or scoring state.
"""
import argparse
from pathlib import Path
import re
from xml.etree import ElementTree

from bundle_model_contract import cite, load
from model_literature_contract import canonical, digest, parse
from model_transport import atomic, bounded, exclusive
from pp1_literature_contract import validate_target
from pp1_reviewed_pages import KIND as PAGE_KIND, check_reference

VERSION = 'public-literature-manual-review-v1'
LIMIT = 2 * 1024 * 1024
INDEX = 'public-literature-manual-review-index-v1'


def require(condition, code):
    if not condition:
        raise ValueError('LITERATURE_MANUAL_' + code)


def short(value, size=200):
    return isinstance(value, str) and bool(value.strip()) and len(value) <= size


def validate_reference(library, source, ref, figures):
    require(ref.get('bundleSha256') == source['bundleSha256'], 'CITATION_BUNDLE_CHANGED')
    if ref.get('sourceKind') == PAGE_KIND:
        check_reference(source, ref)
        return
    if ref.get('sourceKind') != 'ORIGINAL_PUBLISHED_FIGURE':
        checked = cite(source, {'unitId':ref['unitId'], 'quote':ref['quote']})
        require(checked == ref, 'TEXT_CITATION_CHANGED')
        return
    from pp1_pedigree import load_figure
    pin = ref['sourceSha256']
    require(re.fullmatch('[a-f0-9]{64}', pin) and short(ref.get('locator'), 1000), 'FIGURE_LOCATOR_INVALID')
    path = ref['unitId']
    if (pin,path) not in figures:
        folder = Path(library)/'papers'/source['pmcid']/'pedigree-figures'/pin
        figure, _, _ = load_figure(source,folder/'source.json')
        require(figure['imageSha256'] == pin, 'FIGURE_SOURCE_CHANGED')
        require(re.fullmatch(r'/article\[1\](?:/[a-z-]+\[[1-9][0-9]*\])*/fig\[[1-9][0-9]*\]', path), 'FIGURE_UNIT_INVALID')
        node = ElementTree.fromstring(source['raw']).find('.'+path[len('/article[1]'):])
        require(node is not None and node.get('id') == figure['figureId'], 'FIGURE_UNIT_CHANGED')
        figures[pin,path] = (figure['retrieval'].get('finalUrl',figure['retrieval'].get('url')),node.findtext('label'))
    require(figures[pin,path] == (ref['url'],ref['figureLabel']), 'FIGURE_LOCATOR_CHANGED')


def pp1_material(library, source, material):
    scanned = bool(source.get('reviewedPages'))
    fields = {'scope','families','observations'} | ({'variantIdentity'} if scanned else set())
    require(set(material) == fields, 'PP1_MATERIAL_SCHEMA')
    scope, families, observations = (material[key] for key in ('scope','families','observations'))
    require(set(scope) == {'geneSymbol','diseaseId','inheritance','allele'} and isinstance(scope['allele'], dict), 'PP1_SCOPE_INVALID')
    require(1 <= len(families) <= 32 and 1 <= len(observations) <= 512, 'PP1_MATERIAL_LIMIT')
    # A member identifier is local to its published family. Derive the same
    # identity as the native source reader so repeat registrations deduplicate.
    parts = [source['pmcid'], source['main']['sourceSha256']]
    roster = {}
    for label, names in families.items():
        require(short(label) and 1 <= len(names) <= 128 and all(short(n) for n in names)
                and len(set(names)) == len(names), 'PP1_FAMILY_INVALID')
        family = digest(canonical(parts+[label]))
        roster[family] = {'sourceFamilyLabel':label, 'literalFamilyLabel':label,
                          'members':{n:digest(canonical(parts+[label,n])) for n in names}}
    checked = {}
    references, figures = set(), {}
    for item in observations:
        require(set(item) == {'family','member','kind','value','sourceRefs'}, 'PP1_OBSERVATION_SCHEMA')
        label, member, kind, value = (item[k] for k in ('family','member','kind','value'))
        family = digest(canonical(parts+[label]))
        members = roster[family]['members']
        require(member in members and 1 <= len(item['sourceRefs']) <= 16, 'PP1_OBSERVATION_OWNER_INVALID')
        if kind in ('parentOf','childOf','alleleOriginParent'):
            require(value in members and value != member, 'PP1_RELATION_INVALID')
            value = members[value]
        elif kind == 'genotype':
            require(value in ('HETEROZYGOUS','HOMOZYGOUS','HEMIZYGOUS','CARRIER','NONCARRIER'), 'PP1_GENOTYPE_INVALID')
        elif kind in ('proband', 'conditionalReference'):
            require(value is True, 'PP1_PROBAND_INVALID')
        else:
            require((kind in ('diagnosis','reportedDiagnosticCriteria','caution') and short(value, 500))
                    or (kind == 'clinical' and value in ('AFFECTED','UNAFFECTED')), 'PP1_OBSERVATION_UNSUPPORTED')
        for ref in item['sourceRefs']:
            key = canonical(ref)
            if key not in references:
                validate_reference(library, source, ref, figures)
                references.add(key)
        pin = digest(canonical([family,members[member],kind,value]))
        require(pin not in checked, 'PP1_OBSERVATION_DUPLICATE')
        checked[pin] = {'id':pin,'familyKey':family,'memberKey':members[member],
                        'memberLabel':member,'kind':kind,'value':value,'sourceRefs':item['sourceRefs'],
                        'method':'MANUAL_PUBLIC_SOURCE_REVIEW'}
    if scanned:
        identity = material['variantIdentity']
        require(set(identity) == {'transcript', 'coding', 'proteinChange', 'sourceRefs'}
                and re.fullmatch(r'NM_[0-9]+\.[1-9][0-9]*', identity['transcript'])
                and re.fullmatch(r'c\.[1-9][0-9]*[ACGT]>[ACGT]', identity['coding'])
                and re.fullmatch(r'[A-Z][1-9][0-9]*[A-Z*]', identity['proteinChange'])
                and 1 <= len(identity['sourceRefs']) <= 8, 'PP1_SCANNED_VARIANT_IDENTITY_INVALID')
        for ref in identity['sourceRefs']:
            require(ref.get('sourceKind') == PAGE_KIND, 'PP1_SCANNED_VARIANT_PAGE_REQUIRED')
            validate_reference(library, source, ref, figures)
    return roster, list(checked.values())


def read(library, document):
    require(set(document) == {'schemaVersion','criterion','binding','review','material'}
            and document['schemaVersion'] == VERSION, 'DOCUMENT_SCHEMA')
    binding, review = document['binding'], document['review']
    require(set(binding) == {'stage','pmcid','bundleSha256','target'} and binding['stage'] == 'SOURCE_REVIEW', 'BINDING_INVALID')
    require(set(review) == {'reviewedBy','recordSha256','basis'} and short(review['reviewedBy'])
            and short(review['basis'], 1000) and re.fullmatch('[a-f0-9]{64}', review['recordSha256']), 'REVIEW_REQUIRED')
    criterion = document['criterion']
    require(criterion in {'PP1','PM3'}, 'CRITERION_ADAPTER_REQUIRED')
    if criterion == 'PM3':
        from pm3_literature_contract import validate_target as validate_pm3_target
        validate_pm3_target(binding['target'])
    else:
        validate_target(binding['target'])
    source = load(library, binding['pmcid'], binding['bundleSha256'], criterion=criterion)
    if criterion == 'PM3':
        from pm3_literature_contract import review as check_pm3_quotes
        require(isinstance(document['material'],dict) and bool(document['material'].get('cases')), 'PM3_CASES_REQUIRED')
        return source, check_pm3_quotes(source,document['material'],allow_missing_assessment=True)
    require(document['material']['scope']['geneSymbol'] == binding['target']['geneSymbol'], 'GENE_MISMATCH')
    return source, pp1_material(library,source,document['material'])


def register(library, document, expected_pin):
    raw = canonical(document)
    require(len(raw) <= LIMIT and digest(raw) == expected_pin, 'REVIEW_PIN_CHANGED')
    read(library, document)
    root = Path(library)/'manual-reviews'
    criterion, pmcid = document['criterion'], document['binding']['pmcid']
    folder = Path(library)/'reviewed-evidence'/pmcid/'reviews'/criterion
    with exclusive(root):
        index = index_rows(root)
        row = [criterion,pmcid,expected_pin]
        require(row in index or len(index) < 2048, 'INDEX_LIMIT')
        path = folder/(expected_pin+'.json')
        if path.exists():
            require(bounded(path,LIMIT) == raw, 'REGISTERED_BYTES_CHANGED')
        else:
            folder.mkdir(parents=True,exist_ok=True)
            atomic(path, document)
        if row not in index:
            atomic(root/'index.json',{'schemaVersion':INDEX,'reviews':sorted(index+[row])})
    return path


def index_rows(root):
    path = root/'index.json'
    if not path.exists():
        return []
    index = parse(bounded(path,1048576))
    require(index.get('schemaVersion') == INDEX and isinstance(index.get('reviews'),list), 'INDEX_INVALID')
    rows = index['reviews']
    require(len(rows) <= 2048 and all(isinstance(r,list) and len(r)==3
                and re.fullmatch('[A-Z][A-Z0-9]{0,15}',r[0]) and re.fullmatch('PMC[1-9][0-9]*',r[1])
                and re.fullmatch('[a-f0-9]{64}',r[2]) for r in rows), 'INDEX_INVALID')
    require(len({tuple(r) for r in rows}) == len(rows), 'INDEX_DUPLICATE')
    return rows


def entries(library, criterion, pmcid=None):
    root = Path(library)/'manual-reviews'
    total = 0
    for kind, paper, pin in index_rows(root):
        if kind != criterion or (pmcid is not None and paper != pmcid):
            continue
        path = Path(library)/'reviewed-evidence'/paper/'reviews'/kind/(pin+'.json')
        if not path.exists():
            path = root/kind/paper/(pin+'.json')
        raw = bounded(path,LIMIT)
        total += len(raw)
        require(total <= 128*1024*1024, 'INDEX_BYTES_LIMIT')
        document = parse(raw)
        require(digest(raw) == path.stem and document.get('criterion') == criterion
                and document.get('binding',{}).get('pmcid') == paper, 'REGISTERED_BYTES_CHANGED')
        yield path, document


def qualify_pp1(library, document, selection, resolver):
    from pp1_source_qualification import qualify
    source, (families, observations) = read(library,document)
    binding, material = document['binding'], document['material']
    require(all(selection.get(k) == v for k,v in material['scope'].items()), 'PP1_SCOPE_CHANGED')
    ids = sorted({ref['unitId'] for row in observations for ref in row['sourceRefs']
                  if ref.get('sourceKind') not in {'ORIGINAL_PUBLISHED_FIGURE', PAGE_KIND}})
    identity_inventory = {'sourceIdentity':{k:binding[k] for k in ('pmcid','bundleSha256')},
                          'target':binding['target'],'inputScope':{'unitIds':ids}, 'families':{}, 'facts':[]}
    result = qualify(source,identity_inventory,selection,resolver)
    if source.get('reviewedPages'):
        notation = material['variantIdentity']
        result['identityEvidence'] = [{'kind': 'reviewedPageVariant', 'value': notation['coding'],
                                       'sourceRefs': notation['sourceRefs']}]
        try:
            require(resolver is not None, 'PP1_SCANNED_VARIANT_RESOLVER_REQUIRED')
            identity = resolver.resolve_coding(selection['geneSymbol'], notation['coding'],
                                               notation['transcript'], notation['proteinChange'])
            require(identity['allele'] == selection['allele'], 'PP1_SCANNED_VARIANT_ALLELE_MISMATCH')
        except (ValueError, OSError, KeyError) as error:
            result.update(identity=None, identityIssue=str(error))
        else:
            result.update(identity=identity, identityIssue=None)
    result.update(families=families, observations=observations,
                  manualReview={**document['review'],'artifactSha256':digest(canonical(document))})
    return source, identity_inventory, result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--library', required=True, type=Path)
    parser.add_argument('--review', required=True, type=Path)
    parser.add_argument('--sha256', required=True)
    args = parser.parse_args()
    raw = bounded(args.review,LIMIT)
    require(digest(raw) == args.sha256, 'REVIEW_PIN_CHANGED')
    print(register(args.library,parse(raw),args.sha256))


if __name__ == '__main__':
    main()
