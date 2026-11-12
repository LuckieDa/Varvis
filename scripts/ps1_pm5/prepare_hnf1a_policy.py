"""Build the small audited GN017 comparison catalog from preserved public bytes.

This is an explicit source-update utility, not a live clinical classifier. It
refuses to overwrite any output. Review all generated changes before activation.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return sha(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode())


def grantham_record(data):
    records = [s for s in data.decode('ascii').split('//') if 'H GRAR740104\n' in s]
    if len(records) != 1:
        raise ValueError('Unique GRAR740104 required')
    record = records[0].lstrip()
    match = re.search(r'M rows = ([A-Z]+), cols = ([A-Z]+)\n(.*)', record, re.S)
    if not match or match[1] != match[2] or len(match[1]) != 20:
        raise ValueError('Unexpected matrix axes')
    rows = [[int(float(v)) for v in line.split()] for line in match[3].strip().splitlines()]
    if [len(r) for r in rows] != list(range(1, 21)):
        raise ValueError('Expected lower triangular matrix including diagonal')
    matrix = {a: {b: rows[max(i, j)][min(i, j)] for j, b in enumerate(match[1])}
              for i, a in enumerate(match[1])}
    if any(matrix[a][a] != 0 for a in matrix):
        raise ValueError('Nonzero diagonal')
    return (record.rstrip() + '\n//\n').encode(), matrix


def prepare(archive, prior, resources, annotation):
    archive, prior, resources, annotation = map(Path, (archive, prior, resources, annotation))
    out = resources / 'hnf1a-ps1-pm5-v1'
    if out.exists():
        raise ValueError('Output already exists; use a reviewed successor version')
    out.mkdir()
    artifacts = []

    def write(name, value):
        data = (json.dumps(value, indent=2, ensure_ascii=True) + '\n').encode() if not isinstance(value, bytes) else value
        with (out / name).open('xb') as stream:
            stream.write(data)
        artifacts.append({'resource': '/acmg/hnf1a-ps1-pm5-v1/' + name, 'sha256': sha(data)})
        return artifacts[-1]

    for name, expected in [('GN017_3.1.0.json', '6195d0bf2f4cd7e86016ff13cdd78ad2696168b224fafc60f10cfc2409bc0ec1')]:
        data = (prior / name).read_bytes()
        if sha(data) != expected:
            raise ValueError('Prior official source changed')
        write(name, data)
    source_names = ['erepo-g20r-sepio.json', 'erepo-w206r-sepio.json', 'clinvar-g20r.xml',
                    'clinvar-w206r.xml', 'erepo-g20r-target.json', 'erepo-w206c-sepio.json',
                    'pubmed-reference-and-grantham.xml']
    source_manifest = []
    for name in source_names:
        data = (archive / 'resources' / name).read_bytes()
        receipt = json.loads((archive / 'resources' / (name + '.receipt.json')).read_text())
        if receipt.get('sha256') != sha(data) or receipt.get('status') != 200:
            raise ValueError('Unverified source: ' + name)
        artifact = write(name, data)
        source_manifest.append(dict(artifact, sourceUrl=receipt['url'], retrievedAt=receipt['retrievedAtUtc'], bytes=len(data)))
    matrix_source = (archive / 'resources/aaindex2.txt').read_bytes()
    record, matrix = grantham_record(matrix_source)
    write('grantham-GRAR740104.txt', record)
    write('grantham.json', {'sourceUrl': 'https://www.genome.jp/ftp/db/community/aaindex/aaindex2',
                           'sourceSha256': sha(matrix_source), 'recordSha256': sha(record),
                           'recordId': 'GRAR740104', 'pmid': '4843792', 'doi': '10.1126/science.185.4154.862',
                           'comparison': 'WILD_TYPE_TO_EACH_ALTERNATE_SYMMETRIC_DISTANCE', 'matrix': matrix})
    model = json.loads((resources / 'pvs1-hnf1a-transcript-model-v1.json').read_text())
    packaged = annotation.read_bytes()
    if sha(packaged) != '565c0ea83f66b2210182364c3d626fef526229e3d02f444bab247329b6695f2c':
        raise ValueError('SpliceAI annotation identity mismatch')
    matching = [(i+1, line) for i, line in enumerate(packaged.decode().splitlines()) if line.startswith('HNF1A\t')]
    if len(matching) != 1:
        raise ValueError('Unique packaged HNF1A annotation required')
    line_number, line = matching[0]
    fields = line.split('\t')
    starts = [int(v)+1 for v in fields[5].strip(',').split(',')]
    ends = [int(v) for v in fields[6].strip(',').split(',')]
    if fields[1:3] != ['12', '+'] or starts != [e['genomicStart'] for e in model['exons']] or ends != [e['genomicEnd'] for e in model['exons']]:
        raise ValueError('Full transcript exon equivalence failed')
    write('splice-annotation-proof.json', {'status': 'FULL_PACKAGED_EXON_BOUNDARIES_MATCH_PINNED_MODEL',
          'transcript': model['transcript'], 'refseqTranscript': model['refseqTranscript'],
          'modelCanonicalSha256': canonical(model), 'packagedAnnotationSha256': sha(packaged),
          'logicalName': 'spliceai/annotations/grch38.txt', 'lineNumber': line_number, 'line': line,
          'lineSha256': sha(line.encode()), 'coordinateConversion': 'START_ZERO_BASED_PLUS_ONE_END_UNCHANGED',
          'exons': model['exons'], 'normalRnaClaimed': False,
          'limitation': 'Annotation boundaries only; no experimental RNA or historical sequence identity claim'})
    vep = [json.loads(line) for line in (archive / 'preflight/raw-vep.jsonl').read_text().splitlines()]
    ref_defs = [('g20r', 1033090, 'SCV002032337', '1', '890f4355-b322-4d63-ab2e-02ceb1436a6a', '1.0.0', '1.0', 'c.58G>A'),
                ('w206r', 393434, 'SCV002499518', '2', '8bdb1080-f9e5-4bab-83e2-28a709bdcb9b', '1.2.0', '3.0.0', 'c.616T>A')]
    references = []
    for name, vid, scv, version, uuid, doc_version, spec_version, coding in ref_defs:
        tree = ET.parse(archive / 'resources' / ('clinvar-' + name + '.xml'))
        assertions = [e for e in tree.findall('.//ClinicalAssertion') if (e.find('ClinVarAccession') is not None
                      and e.find('ClinVarAccession').get('Accession') == scv)]
        if len(assertions) != 1:
            raise ValueError('Exact expert SCV missing')
        assertion = assertions[0]
        acc = assertion.find('ClinVarAccession')
        germline = assertion.find('Classification')
        if germline is None:
            raise ValueError('SCV germline classification missing')
        description = germline.findtext('GermlineClassification')
        if acc.get('Version') != version or acc.get('OrgID') != '508240' or assertion.find('ClinVarSubmissionID').get('localKey') != uuid:
            raise ValueError('Expert submission identity mismatch')
        if germline.findtext('ReviewStatus') != 'reviewed by expert panel' or (description or '').lower() not in ('pathogenic', 'likely pathogenic'):
            raise ValueError('Expert P/LP classification required')
        if not any(x.get('DB') == 'MONDO' and x.get('ID') in ('0015967', 'MONDO:0015967') for x in assertion.findall('.//XRef')):
            raise ValueError('Exact disease missing from SCV')
        summary_file = 'erepo-hnf1a-g20r.json' if name == 'g20r' else 'erepo-hnf1a-likely-pathogenic.json'
        summary = next(r for r in json.loads((archive / 'resources' / summary_file).read_text())['data'] if r['uuid'] == uuid)
        if summary['docVersion'] != doc_version or summary['retracted'] or summary['mondoId'] != 'MONDO:0015967' or summary['classification'].lower() != description.lower():
            raise ValueError('ERepo/SCV classification mismatch')
        if any(c.startswith(('PS1', 'PM5')) for c in summary['metCodes']):
            raise ValueError('Circular reference')
        tx_rows = [(r, t) for r in vep for t in r.get('transcript_consequences', []) if t.get('hgvsc') == model['transcript'] + ':' + coding]
        if len(tx_rows) != 1:
            raise ValueError('Exact real reference VEP missing')
        raw, tx = tx_rows[0]
        parts = raw['input'].split('\t')
        allele = dict(assembly='GRCh38', contig=parts[0], position=int(parts[1]), reference=parts[3], alternate=parts[4])
        if 'NM_000545.8:' + coding not in summary['hgvs'] or f"NC_000012.12:g.{parts[1]}{parts[3]}>{parts[4]}" not in summary['hgvs']:
            raise ValueError('ERepo native allele/transcript mismatch')
        refs = dict(allele=allele, scv=scv+'.'+version, variationId=str(vid), erepoId=uuid, erepoVersion=doc_version,
                    classification=description, evaluationDate=germline.get('DateLastEvaluated'),
                    geneSymbol='HNF1A', hgncId='HGNC:11621', mondoId='MONDO:0015967',
                    transcriptId=model['transcript'], refseqTranscript=model['refseqTranscript'],
                    proteinPosition=tx['protein_start'], referenceAminoAcid=tx['amino_acids'].split('/')[0],
                    alternateAminoAcid=tx['amino_acids'].split('/')[1], proteinChange=tx['hgvsp'],
                    codingChange='NM_000545.8:'+coding, expertPanel=acc.get('SubmitterName'), expertOrgId=acc.get('OrgID'),
                    originalSpecificationVersion=spec_version,
                    clinvarAssertionMethod=assertion.findtext("./AttributeSet/Attribute[@Type='AssertionMethod']"),
                    historicalVersionNote='ERepo/SCV classification narrative version retained verbatim; ClinVar assertion-method label retained separately, not normalized or relabelled as GN017 3.1.',
                    independentEvidenceStatus='VERIFIED',
                    independentCriteria=summary['metCodes'], independentCriteriaSufficient=True,
                    independentEvidenceReason='Exact MDEP P/LP assertion with no PS1/PM5; classification retained at original specification version, not recomputed.',
                    submittedAssembly=assertion.find('ClinVarSubmissionID').get('submittedAssembly'),
                    coordinateLineage='PUBLISHED_NATIVE_GRCH38_MAPPING_VERIFIED_AGAINST_FASTA_FULL_CDS_AND_VEP; ORIGINAL_SUBMISSION_ASSEMBLY_RETAINED',
                    sourceUrl='https://www.ncbi.nlm.nih.gov/clinvar/variation/'+str(vid)+'/',
                    erepoUrl='https://erepo.clinicalgenome.org/evrepo/ui/interpretation/'+uuid,
                    selectedVep=tx, vepRawSha256=sha((archive / 'preflight/raw-vep.jsonl').read_bytes()),
                    sourceArtifacts=[a for a in source_manifest if name in a['resource']],
                    rnaReview={'status':'REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED', 'rnaEffect':'UNKNOWN', 'normalRnaClaimed':False,
                               'scope':'Exact public expert evidence and SCV text; G20R original protein assay abstract PMID12107757. No proof of normal RNA.',
                               'sourceUrls':[s['sourceUrl'] for s in source_manifest if name in s['resource']]})
        references.append(refs)
    write('reference-audit.json', {'schemaVersion':'hnf1a-ps1-pm5-reference-audit-v1', 'reviewVersion':'1.0.0',
          'references':references, 'sourceManifest':source_manifest,
          'selection':'ALL_EXACT_AUDITED_SCVS_SCREENED; SAME_ALLELE_DEDUPLICATED; CONTRADICTORY_EXPERT_REFERENCE_BLOCKS; NO_AUTO_REFERENCE_CLASSIFICATION',
          'discoveryLimitation':'Bounded ERepo searches and two references; not a complete HNF1A catalog'})
    write('rna-review.json', {'reviewVersion':'1.0.0', 'geneSymbol':'HNF1A', 'transcript':model['transcript'],
          'normalRnaClaimed':False, 'rnaEffect':'UNKNOWN', 'status':'REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED',
          'basis':'Public MDEP protein comparisons at Gly20 and Trp206 plus exact reference SCV/SEPIO and G20R primary protein assay abstract; not exhaustive literature surveillance.',
          'reviewedProteinRelations':[{'referenceAminoAcid':'G','targetAlternate':'R','comparatorAlternate':'R','proteinPosition':20},
                                     {'referenceAminoAcid':'W','targetAlternate':'C','comparatorAlternate':'R','proteinPosition':206}],
          'sourceArtifacts':source_manifest, 'knownConcerns':[], 'unreviewedRelationOutcome':'NOT_EVALUATED'})
    write('source-manifest.json', {'artifacts':copy.deepcopy(artifacts)})
    print(json.dumps({'output':str(out), 'references':len(references), 'artifacts':len(artifacts)}, indent=2))


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    for field in ('archive', 'prior', 'resources', 'annotation'):
        p.add_argument('--'+field, required=True)
    prepare(**vars(p.parse_args()))
