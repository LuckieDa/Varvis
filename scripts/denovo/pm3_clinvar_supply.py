"""Exact local ClinVar locator -> bounded official VCV/cache. Never a classification.

The VCF is a locator snapshot, not a substitute for current SCV evidence. It is
not scanned: existing tabix reads only one verified GRCh38 coordinate per key.
"""
from copy import deepcopy
import gzip
from pathlib import Path
import re
import subprocess
import time
from core_local_identity import local_file, pinned
from literature_allele_identity import read_record
from model_literature_contract import canonical, digest
from model_transport import bounded


def require(ok, reason):
    if not ok: raise ValueError('PM3_CLINVAR_' + reason)


def vcv_endpoint(accession):
    require(re.fullmatch(r'VCV[0-9]{9}', accession or ''), 'ACCESSION_INVALID')
    url = ('https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=clinvar&rettype=vcv&id=' + accession)
    def decode(raw):
        return read_record(raw, digest(raw), accession)
    return url, decode


class ClinVarSupply:
    def __init__(self, root, spec, archive, transport):
        self.root, self.spec, self.archive, self.transport = Path(root), spec, Path(archive), transport
        self.memo = {}; self.queries = 0; self.started=time.monotonic()
        self.archive.mkdir(parents=True, exist_ok=True)

    def resources(self):
        spec = self.spec
        vcf = local_file(self.root, spec['vcf']['path'])
        index = local_file(self.root, spec['index']['path'])
        tool = local_file(self.root, spec['tabix']['path'])
        require(index == Path(str(vcf) + '.tbi'), 'INDEX_PATH_MISMATCH')
        pinned(self.root, spec['index'], 8*1024*1024)
        pinned(self.root, spec['tabix'], 8*1024*1024)
        require(vcf.stat().st_size == spec['vcf']['size'] and vcf.stat().st_mtime_ns == spec['vcf']['mtimeNs'],
                'LOCAL_SNAPSHOT_CHANGED')
        header = bytearray()
        with gzip.open(vcf, 'rb') as stream:
            while len(header) <= 131072:
                line = stream.readline(131073-len(header)); header.extend(line)
                if line.startswith(b'#CHROM\t'): break
                require(line.startswith(b'##'), 'HEADER_INVALID')
        require(len(header) <= 131072 and line.startswith(b'#CHROM\t'), 'HEADER_LIMIT')
        require(b'##source=ClinVar\n' in header and b'##reference=GRCh38\n' in header
                and digest(header) == spec['headerSha256'], 'HEADER_BINDING_INVALID')
        return vcf, index, tool

    def lookup(self, allele, gene):
        key = digest(canonical({'allele':allele, 'gene':gene}))
        if key in self.memo: return deepcopy(self.memo[key])
        result = {'records':[], 'issues':[], 'status':'SOURCE_READ_INCOMPLETE',
                  'lookup':{'allele':allele, 'geneSymbol':gene, 'method':'LOCAL_EXACT_VCF_ID_OFFICIAL_VCV_V1'}}
        run = self.archive / key; run.mkdir(exist_ok=False)
        try:
            require(allele.get('assembly') == 'GRCh38' and re.fullmatch(r'[1-9]|1[0-9]|2[0-2]', allele.get('contig',''))
                    and type(allele.get('position')) is int and 0 < allele['position'] <= 300000000
                    and all(re.fullmatch('[ACGT]',allele.get(k,'')) for k in ('reference','alternate'))
                    and allele['reference'] != allele['alternate'], 'SNV_SCOPE_REQUIRED')
            require(re.fullmatch(r'[A-Za-z0-9_-]{1,64}',gene or ''), 'GENE_INVALID')
            require(self.queries < 50, 'LOCAL_QUERY_LIMIT')
            remaining=120-(time.monotonic()-self.started)
            require(remaining>0,'TOTAL_TIMEOUT')
            vcf, index, tool = self.resources()
            before = [(p.stat().st_size,p.stat().st_mtime_ns) for p in (vcf,index,tool)]
            region = f"{allele['contig']}:{allele['position']}-{allele['position']}"
            command = [str(tool), str(vcf), region]
            self.queries += 1
            with (run/'records.vcf').open('xb') as out, (run/'stderr.log').open('xb') as err:
                try: code = subprocess.run(command, stdout=out, stderr=err, timeout=min(15,remaining), check=False).returncode
                except subprocess.TimeoutExpired: raise ValueError('PM3_CLINVAR_LOCAL_TIMEOUT') from None
            require(code == 0, 'LOCAL_QUERY_FAILED')
            require(before == [(p.stat().st_size,p.stat().st_mtime_ns) for p in (vcf,index,tool)], 'LOCAL_SNAPSHOT_CHANGED')
            raw = bounded(run/'records.vcf',1024*1024)
            result['lookup'].update(region=region, rawSha256=digest(raw), snapshot=deepcopy(self.spec['vcf']),
                                    headerSha256=self.spec['headerSha256'],indexSha256=self.spec['index']['sha256'])
            ids = set()
            for line in raw.decode('utf-8').splitlines():
                cols = line.split('\t'); require(len(cols)>=8, 'VCF_ROW_INVALID')
                if cols[0]!=allele['contig'] or cols[1]!=str(allele['position']) or cols[3]!=allele['reference'] or cols[4]!=allele['alternate']: continue
                info = dict(x.split('=',1) for x in cols[7].split(';') if '=' in x)
                genes = {x.rsplit(':',1)[0] for x in info.get('GENEINFO','').split('|')}
                require(genes == {gene}, 'EXACT_GENE_MISMATCH_OR_AMBIGUOUS')
                require(re.fullmatch(r'[1-9][0-9]{0,8}',cols[2]), 'VARIATION_ID_INVALID')
                ids.add('VCV'+cols[2].zfill(9))
            result['lookup']['accessions'] = sorted(ids)
            require(len(ids)<=4, 'RECORD_COUNT_LIMIT')
            if not ids:
                result.update(status='NOT_FOUND_IN_LOCAL_SNAPSHOT',issues=['PM3_CLINVAR_EXACT_RECORD_NOT_IN_LOCAL_SNAPSHOT'])
            else:
                for accession in sorted(ids):
                    raw = self.transport.get_clinvar(accession)
                    record = read_record(raw,digest(raw),accession)
                    require(record['allele']==allele and record['geneSymbol']==gene,'FETCHED_IDENTITY_MISMATCH')
                    path = run/(accession+'.xml'); path.write_bytes(raw)
                    spec={'path':path.relative_to(self.root).as_posix(),'sha256':digest(raw),'accession':accession}
                    result['records'].append(spec)
                result['status']='EXACT_RECORDS_ACQUIRED'
        except (ValueError,OSError,KeyError,TypeError) as error:
            code = str(error)
            result['issues']=[code if re.fullmatch('[A-Z][A-Z0-9_]{1,150}',code) else 'PM3_CLINVAR_SOURCE_FAILURE']
            result['records']=[]
        (run/'lookup.json').write_bytes(canonical(result))
        self.memo[key]=deepcopy(result)
        return result
