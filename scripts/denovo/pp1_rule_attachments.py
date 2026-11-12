"""Read and verify PP1 rule-attachment provenance without enabling an uncompiled rule."""
from pathlib import Path
import re
from urllib.parse import urlsplit
from zipfile import BadZipFile, ZipFile
from io import BytesIO
from xml.etree.ElementTree import ParseError

from core_local_identity import pinned
from model_literature_contract import parse
from pm3_specification_context import normal
from pp1_literature_contract import require
from pp1_hht_phenotype import compile_attachment
from prepare_applicability_sources import pointer

VERSION = 'pp1-rule-attachment-manifest-v1'
POINTER = re.compile(r'/ld/RuleSet/([0-9]+)/ld/File/([0-9]+)')
OFFICIAL_HOSTS = {'cspec.genome.network','cspec.clinicalgenome.org','zenodo.org','clinicalgenome.org','www.clinicalgenome.org'}


def referenced(clause):
    fields = ['instructionsToUse','additionalComments']
    values = [normal(clause.get(key)) for key in fields]
    for row in clause.get('strengthDescriptor', []):
        values += [normal(row.get(key)) for key in ('instructionsToUse','text','additionalComments')]
    names = []
    for value in values:
        pattern = r'\bSee (?:the )?([\w -]{1,75}?) document in attachments\b'
        for label in re.findall(pattern, value, re.I):
            if label.casefold() not in {n.casefold() for n in names}:
                names.append(label)
        if '' not in names and re.search(r'\battach(?:ed|ment|ments)\b', re.sub(pattern, '', value, flags=re.I), re.I):
            names.append('')
    return names


def load_manifest(root, pin):
    value = parse(local_bytes(root, pin, 1024 * 1024))
    require(set(value) == {'schemaVersion','entries'} and value['schemaVersion'] == VERSION
            and isinstance(value['entries'], list) and len(value['entries']) <= 128,
            'RULE_ATTACHMENT_MANIFEST_INVALID')
    return value['entries']


def local_bytes(root, pin, limit):
    path = pin.get('path') if isinstance(pin, dict) else None
    require(isinstance(path, str) and not Path(path).is_absolute() and (root / path).resolve().is_relative_to(root / 'local'),
            'RULE_ATTACHMENT_PATH_OUTSIDE_LOCAL')
    return pinned(root, pin, limit)


def version_tuple(value):
    require(isinstance(value, str) and re.fullmatch(r'\d+(?:\.\d+){1,2}', value), 'RULE_ATTACHMENT_VERSION_INVALID')
    return tuple(int(part) for part in (value.split('.') + ['0'])[:3])


def comparison_clause(clause):
    # Release export has different record IDs and display metadata. Compare
    # every operative text, status, strength and declared scope, in order.
    keys = ('label','gene','disease','geneType','baseStrength','defaultStrength','applicability','instructionsToUse','additionalComments')
    value = {key: clause.get(key) for key in keys}
    value['strengthDescriptor'] = [{k: row.get(k) for k in ('strength','applicability','text','instructionsToUse','additionalComments','status')}
                                   for row in clause.get('strengthDescriptor', [])]
    return value


def inspect(root, spec, match, association, clause, labels, records):
    output = []
    for label in labels:
        key = re.fullmatch(r'(GN\d+)@([\d.]+)', spec['specificationKey'])
        out = {'label': label or '规则附件', 'status': 'REFERENCE_NOT_REGISTERED', 'clausePointer': match['clausePointer']}
        if key:
            out['ruleSourceUrl'] = f'https://cspec.genome.network/cspec/ui/svi/doc/{key[1]}?version={key[2]}'
        output.append(out)
        if records is None:
            out.update(status='VERIFICATION_FAILED', reasonCode='PP1_RULE_ATTACHMENT_MANIFEST_INVALID')
            continue
        candidates = [record for record in records if isinstance(record, dict)
                      and record.get('specificationKey') == spec['specificationKey']
                      and record.get('scopePointer') == match['scopePointer']
                      and normal(record.get('label')).casefold() == label.casefold()]
        if not candidates:
            continue
        try:
            require(len(candidates) == 1 and label, 'RULE_ATTACHMENT_REFERENCE_AMBIGUOUS')
            entry, = candidates
            require(set(entry) == {'specificationKey','scopePointer','clauseSha256','label','releaseMetadata','metadataUrl','filePointer','file'}
                    and entry['clauseSha256'] == match['clauseSha256'], 'RULE_ATTACHMENT_CLAUSE_MISMATCH')
            url = urlsplit(entry['metadataUrl'])
            require(url.scheme == 'https' and url.netloc in OFFICIAL_HOSTS, 'RULE_ATTACHMENT_SOURCE_URL_UNVERIFIED')
            release = parse(local_bytes(root, entry['releaseMetadata'], 4 * 1024 * 1024))
            namespace, version = spec['specificationKey'].split('@')
            require(release.get('entId') == namespace and release['entContent'].get('namespace') == namespace
                    and version_tuple(release['entContent'].get('version')) == version_tuple(version), 'RULE_ATTACHMENT_VERSION_MISMATCH')
            path = POINTER.fullmatch(entry['filePointer'])
            require(path is not None, 'RULE_ATTACHMENT_POINTER_INVALID')
            ruleset = pointer(release, f'/ld/RuleSet/{path[1]}')
            scope = association['scope']
            original_scope = pointer(spec['original'], match['scopePointer'])
            declarations = [d for g in ruleset['entContent']['genes'] if g.get('gene') == association['geneSymbol']
                            for d in g.get('diseases', []) if d.get('preferredMondoId') == scope['mondoId']]
            require(len(declarations) == 1 and normal(declarations[0].get('preferredModeOfInheritance')) ==
                    normal(original_scope.get('preferredModeOfInheritance')), 'RULE_ATTACHMENT_SCOPE_MISMATCH')
            release_clauses = [r['entContent'] for r in ruleset['ld']['CriteriaCode'] if r['entContent'].get('label') == 'PP1'
                               and association['geneSymbol'] in r['entContent'].get('gene', [])]
            require(len(release_clauses) == 1 and comparison_clause(release_clauses[0]) == comparison_clause(clause),
                    'RULE_ATTACHMENT_CLAUSE_MISMATCH')
            file_record = pointer(release, entry['filePointer'])
            file_id = file_record.get('entId', '')
            require(file_record.get('entType') == 'File' and re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', file_id),
                    'RULE_ATTACHMENT_DECLARATION_MISMATCH')
            file_node = file_record['entContent']
            require(normal(file_node.get('fileLabel')).casefold() == label.casefold() and file_node.get('public') in (True, 'True'),
                    'RULE_ATTACHMENT_DECLARATION_MISMATCH')
            name, kind = file_node['fileName'], file_node['type']
            require(kind in {'docx','pdf','xlsx'} and Path(name).name == name and Path(name).suffix.lower() == '.' + kind,
                    'RULE_ATTACHMENT_FILE_TYPE_UNSUPPORTED')
            out.update(status='REGISTERED_FILE_UNAVAILABLE', metadataUrl=entry['metadataUrl'],
                       metadataSha256=entry['releaseMetadata']['sha256'], filePointer=entry['filePointer'], fileName=name)
            out['fileSourceUrl'] = f'https://cspec.genome.network/cspec/File/id/{file_id}/data'
            if entry['file'] is None:
                continue
            raw = local_bytes(root, entry['file'], 8 * 1024 * 1024)
            require(len(raw) == int(file_node['size']), 'RULE_ATTACHMENT_FILE_SIZE_MISMATCH')
            phenotype = None
            if kind == 'pdf':
                require(raw.startswith(b'%PDF-'), 'RULE_ATTACHMENT_FILE_TYPE_MISMATCH')
            else:
                with ZipFile(BytesIO(raw)) as archive:
                    core = 'word/document.xml' if kind == 'docx' else 'xl/workbook.xml'
                    require(core in archive.namelist() and '[Content_Types].xml' in archive.namelist(),
                            'RULE_ATTACHMENT_FILE_TYPE_MISMATCH')
                    if kind == 'docx' and label.casefold() == 'hht phenotype':
                        require(archive.getinfo(core).file_size <= 1024 * 1024, 'RULE_ATTACHMENT_XML_SIZE_LIMIT')
                        phenotype = compile_attachment(archive.read(core))
            out.update(status='VERIFIED_CONDITIONS_NOT_IMPLEMENTED', fileSha256=entry['file']['sha256'])
            if phenotype:
                out.update(status='VERIFIED_CONDITIONS_IMPLEMENTED', phenotypeQualification=phenotype)
        except (ValueError, OSError, KeyError, IndexError, AttributeError, TypeError, BadZipFile, ParseError) as error:
            reason = str(error)
            out.update(status='VERIFICATION_FAILED', reasonCode=reason if re.fullmatch('[A-Z0-9_]{1,120}', reason)
                       else 'PP1_RULE_ATTACHMENT_VERIFICATION_FAILED')
    return output
