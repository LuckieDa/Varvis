"""Bounded public citation retrieval for exact, pinned ClinVar allele identities.

No patient data, arbitrary URLs, broad gene search, model calls, or qualification.
Use retrieved JATS with register_materials.py and the existing production reader.
"""
import argparse
import datetime as dt
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from register_materials import read, sha, unique
from prepare_literature import prepare
from literature_allele_identity import read_record

VERSION = 'denovo-public-citation-supply-1.0.0'
ROOT = 'https://www.ebi.ac.uk/europepmc/webservices/rest/'
MAX_BODY = 1_048_576
MAX_REQUESTS = 8
MAX_PAPERS = 4
TOTAL_SECONDS = 120
REQUEST_SECONDS = 20


def url_for(kind, identifier):
    if kind == 'metadata' and re.fullmatch(r'[1-9][0-9]*', identifier):
        return ROOT + 'search?' + urllib.parse.urlencode({'query': f'EXT_ID:{identifier} AND SRC:MED', 'format': 'json', 'pageSize': '2'})
    if kind == 'fulltext' and re.fullmatch(r'PMC[1-9][0-9]*', identifier):
        return ROOT + identifier + '/fullTextXML'
    raise ValueError('DENOVO_PUBLIC_QUERY_ID_INVALID')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('DENOVO_PUBLIC_REDIRECT_REJECTED')


def worker(kind, identifier, output):
    """A separate bounded process makes DNS/connect/read subject to a hard deadline."""
    url = url_for(kind, identifier)
    opener = urllib.request.build_opener(NoRedirect)
    request = urllib.request.Request(url, headers={'User-Agent': 'Varvis-public-literature/1.0', 'Accept-Encoding': 'identity'})
    try:
        with opener.open(request, timeout=REQUEST_SECONDS) as response:
            if response.status != 200 or response.headers.get('Content-Encoding', 'identity') != 'identity':
                raise ValueError('DENOVO_PUBLIC_RESPONSE_UNSUPPORTED')
            if int(response.headers.get('Content-Length', '0')) > MAX_BODY:
                raise ValueError('DENOVO_PUBLIC_BODY_LIMIT')
            size = 0
            with Path(output).open('xb') as stream:
                while True:
                    chunk = response.read(min(16384, MAX_BODY + 1 - size))
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > MAX_BODY:
                        raise ValueError('DENOVO_PUBLIC_BODY_LIMIT')
                    stream.write(chunk)
            if size == 0:
                raise ValueError('DENOVO_PUBLIC_EMPTY_BODY')
        return {'status': 'OK', 'bytes': size}
    except urllib.error.HTTPError as error:
        return {'status': 'UNAVAILABLE', 'reasonCode': 'DENOVO_PUBLIC_RATE_LIMIT' if error.code == 429 else 'DENOVO_PUBLIC_HTTP_FAILURE', 'httpStatus': error.code}
    except ValueError as error:
        return {'status': 'UNAVAILABLE', 'reasonCode': str(error) if str(error).startswith('DENOVO_') else 'DENOVO_PUBLIC_RESPONSE_INVALID'}
    except (OSError, urllib.error.URLError):
        return {'status': 'UNAVAILABLE', 'reasonCode': 'DENOVO_PUBLIC_TRANSPORT_FAILURE'}


class Retriever:
    def __init__(self, cache, archive, online=False):
        self.cache, self.archive = Path(cache), Path(archive)
        self.cache.mkdir(parents=True, exist_ok=True)
        self.online, self.started, self.last_request = online, time.monotonic(), None
        self.events, self.requests, self.stopped = [], 0, None
        self.cooldown = self.cache/'rate-limit-until.json'

    def get(self, kind, identifier):
        url = url_for(kind, identifier)
        key = sha(url.encode()); entry = self.cache/(key + '.json')
        event = {'kind': kind, 'identifier': identifier, 'url': url}
        self.events.append(event)
        try:
            if entry.exists():
                cached = json.loads(read(entry), object_pairs_hook=unique)
                pin = cached.get('sha256', '')
                if cached.get('url') != url or not re.fullmatch('[0-9a-f]{64}', pin):
                    raise ValueError('DENOVO_PUBLIC_CACHE_INVALID')
                body = self.cache/(pin + '.body')
                if not body.resolve().is_relative_to(self.cache.resolve()):
                    raise ValueError('DENOVO_PUBLIC_CACHE_INVALID')
                raw = read(body, pin)
                event.update(status='CACHE_HIT', sha256=pin, retrievedAt=cached['retrievedAt'], bytes=len(raw))
                return raw
            if self.stopped:
                raise ValueError(self.stopped)
            if not self.online:
                raise ValueError('DENOVO_PUBLIC_NETWORK_DISABLED')
            if self.cooldown.exists():
                until = json.loads(read(self.cooldown))['untilEpoch']
                if time.time() < until:
                    raise ValueError('DENOVO_PUBLIC_RATE_LIMIT_COOLDOWN')
            if self.requests >= MAX_REQUESTS:
                raise ValueError('DENOVO_PUBLIC_REQUEST_BUDGET')
            if self.last_request is not None:
                time.sleep(max(0, 0.5 - (time.monotonic() - self.last_request)))
            remaining = TOTAL_SECONDS - (time.monotonic() - self.started)
            if remaining <= 0:
                raise ValueError('DENOVO_PUBLIC_TOTAL_TIMEOUT')
            run = self.archive/('request-' + str(self.requests + 1))
            run.mkdir(exist_ok=False)
            raw_path = run/'response.body'
            self.requests += 1; self.last_request = time.monotonic()
            event['requestNumber'] = self.requests
            command = [sys.executable, '-X', 'utf8', str(Path(__file__).resolve()), '--worker', kind, identifier, str(raw_path)]
            with (run/'stdout.json').open('xb') as out, (run/'stderr.log').open('xb') as err:
                try:
                    completed = subprocess.run(command, stdout=out, stderr=err, timeout=min(REQUEST_SECONDS, remaining), check=False)
                except subprocess.TimeoutExpired:
                    self.stopped = 'DENOVO_PUBLIC_TIMEOUT'
                    raise ValueError(self.stopped) from None
            status = json.loads((run/'stdout.json').read_bytes(), object_pairs_hook=unique)
            if completed.returncode != 0 or status.get('status') != 'OK':
                reason = status.get('reasonCode', 'DENOVO_PUBLIC_WORKER_FAILURE')
                if reason == 'DENOVO_PUBLIC_RATE_LIMIT':
                    self.stopped = reason
                    self.cooldown.write_text(json.dumps({'untilEpoch': time.time()+300, 'reasonCode':reason}), encoding='utf-8')
                raise ValueError(reason)
            raw = read(raw_path); pin = sha(raw)
            event.update(status='FETCHED', sha256=pin, bytes=len(raw), retrievedAt=dt.datetime.now(dt.timezone.utc).isoformat())
            # Immutable content-addressed body. Atomic URL index; never cache errors or partial bodies.
            body = self.cache/(pin + '.body')
            if not body.exists():
                with body.open('xb') as stream:
                    stream.write(raw)
            if read(body, pin) != raw:
                raise ValueError('DENOVO_PUBLIC_CACHE_INVALID')
            temporary = self.cache/(key + '.' + uuid.uuid4().hex + '.tmp')
            temporary.write_text(json.dumps(event), encoding='utf-8'); temporary.replace(entry)
            return raw
        except (ValueError, KeyError, TypeError, OSError) as error:
            reason = str(error) if str(error).startswith('DENOVO_') else 'DENOVO_PUBLIC_SOURCE_OR_CACHE_INVALID'
            event.update(status='UNAVAILABLE', reasonCode=reason)
            raise ValueError(reason) from error


def pmcid_from_metadata(raw, pmid):
    data = json.loads(raw, object_pairs_hook=unique)
    if not isinstance(data,dict) or not isinstance(data.get('resultList'),dict):
        raise ValueError('DENOVO_PUBLIC_RESPONSE_INVALID')
    rows = data.get('resultList', {}).get('result', [])
    if data.get('hitCount') == 0 and rows == []:
        raise ValueError('DENOVO_PUBLIC_CITATION_NOT_FOUND')
    if type(data.get('hitCount')) is not int or data.get('hitCount') != 1 or not isinstance(rows,list) or len(rows) != 1 or not isinstance(rows[0],dict) or rows[0].get('id') != pmid or rows[0].get('source') != 'MED':
        raise ValueError('DENOVO_PUBLIC_CITATION_IDENTITY_CONFLICT')
    row = rows[0]
    if row.get('isOpenAccess') != 'Y' or not re.fullmatch(r'PMC[1-9][0-9]*', row.get('pmcid', '')):
        raise ValueError('DENOVO_PUBLIC_FULLTEXT_UNAVAILABLE')
    return row['pmcid']


def collect(request_path, output, retriever_factory=Retriever, cache=None, online=False, local_papers=None):
    request_path, output = Path(request_path).resolve(), Path(output)
    request_raw = read(request_path)
    request = json.loads(request_raw, object_pairs_hook=unique)
    if not isinstance(request,dict) or set(request) != {'schemaVersion', 'records'} or request['schemaVersion'] != 'denovo-public-citation-request-v1' or not isinstance(request['records'], list) or len(request['records']) > 100:
        raise ValueError('DENOVO_PUBLIC_REQUEST_SCHEMA')
    output.mkdir(exist_ok=False); (output/'papers').mkdir(); (output/'clinvar').mkdir()
    retriever = retriever_factory(cache, output, online)
    results, all_pmids, outcomes = [], set(), {}
    for item in request['records']:
        row = {'status':'UNAVAILABLE', 'vcv':item.get('vcv') if isinstance(item,dict) else None}; results.append(row)
        try:
            if not isinstance(item,dict) or set(item) != {'path','sha256','vcv','allele','hgncId'}:
                raise ValueError('DENOVO_PUBLIC_RECORD_SCHEMA')
            path = (request_path.parent/item['path']).resolve()
            if Path(item['path']).is_absolute() or not path.is_relative_to(request_path.parent):
                raise ValueError('DENOVO_PUBLIC_PATH_OUTSIDE_REQUEST')
            raw = read(path, item['sha256']); record = read_record(raw, item['sha256'], item['vcv'])
            if record['allele'] != item['allele'] or record['hgncId'] != item['hgncId']:
                raise ValueError('DENOVO_PUBLIC_TARGET_IDENTITY_MISMATCH')
            dest = output/'clinvar'/(item['sha256']+'.xml')
            if not dest.exists():
                dest.write_bytes(raw)
            row.update(status='CITATIONS_CHECKED', sourceSha256=item['sha256'], citations=[])
            pmids = sorted({p['pmid'] for p in record['pubmed']}, key=int)
            if not pmids:
                row['issues']=['DENOVO_PUBLIC_NO_CITATION_CLUE']
            if len(pmids)>MAX_PAPERS:
                row.update(omittedCitationCount=len(pmids)-MAX_PAPERS, issues=['DENOVO_PUBLIC_PAPER_BUDGET'])
            for pmid in pmids[:MAX_PAPERS]:
                result={'pmid':pmid}; row['citations'].append(result)
                if pmid in all_pmids:
                    result.update(outcomes[pmid]); result['status']='DUPLICATE_CITATION'; continue
                if len(all_pmids) >= MAX_PAPERS:
                    result.update(status='UNAVAILABLE', reasonCode='DENOVO_PUBLIC_PAPER_BUDGET'); continue
                all_pmids.add(pmid)
                try:
                    if local_papers and pmid in local_papers:
                        pmcid, fulltext = local_papers[pmid]
                    else:
                        metadata = retriever.get('metadata', pmid)
                        pmcid = pmcid_from_metadata(metadata, pmid)
                        fulltext = retriever.get('fulltext', pmcid)
                    prepared = prepare(fulltext, pmcid, sha(fulltext))
                    if prepared['identifiers'].get('pmid') != pmid:
                        raise ValueError('DENOVO_PUBLIC_FULLTEXT_PMID_MISMATCH')
                    (output/'papers'/(pmcid+'.xml')).write_bytes(fulltext)
                    result.update(status='SOURCE_READY_FOR_QUALIFICATION', pmcid=pmcid, sha256=sha(fulltext))
                except (ValueError, KeyError, OSError) as error:
                    result.update(status='UNAVAILABLE', reasonCode=str(error) if str(error).startswith(('DENOVO_', 'LITERATURE_')) else 'DENOVO_PUBLIC_RESPONSE_INVALID')
                outcomes[pmid]=dict(result)
        except (ValueError, KeyError, TypeError, OSError) as error:
            row.update(reasonCode=str(error) if str(error).startswith(('DENOVO_', 'LITERATURE_')) else 'DENOVO_PUBLIC_RECORD_INVALID')
    receipt={'schemaVersion':VERSION,'requestSha256':sha(request_raw), 'records':results,'retrievals':retriever.events,
             'networkRequests':retriever.requests,'sourceScope':'EXACT_PINNED_CLINVAR_CITATIONS_ONLY', 'completeLiteratureSearch':False,
             'limits':{'papers':MAX_PAPERS,'requests':MAX_REQUESTS,'bodyBytes':MAX_BODY,'requestSeconds':REQUEST_SECONDS,'totalSeconds':TOTAL_SECONDS},
             'modelCalls':0,'modelValidation':'DEFERRED_NOT_CONFIGURED','scientificApproval':'NOT_GRANTED','clinicalApproval':'NONE'}
    (output/'retrieval.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8')
    return receipt


def main():
    if len(sys.argv) == 5 and sys.argv[1] == '--worker':
        print(json.dumps(worker(*sys.argv[2:]))); return
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--request',required=True,type=Path); parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--cache',required=True,type=Path); parser.add_argument('--allow-public-network',action='store_true')
    a=parser.parse_args(); receipt=collect(a.request,a.output,cache=a.cache,online=a.allow_public_network)
    print(json.dumps({'networkRequests':receipt['networkRequests'],'records':len(receipt['records']),'modelCalls':0,'completeLiteratureSearch':False}))


if __name__ == '__main__':
    main()
