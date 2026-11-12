"""Fixed official full-text endpoints, shared cooldown, hard time/byte limits.

No publisher crawling, redirects, retry, model call or execution of downloaded files.
"""
import datetime as dt
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

from fetch_public_literature import NoRedirect
from literature_search_transport import SearchTransport, atomic_json
import pmc_open_data
from prepare_literature import sha
from select_public_papers import bounded, unique

ROOT = 'https://www.ebi.ac.uk/europepmc/webservices/rest/'
LIMITS = {'fullTextXML': 2 * 1024 * 1024, 'supplementaryFiles': 50 * 1024 * 1024,
          'authorManuscriptXML': 2 * 1024 * 1024, 'pmcArchiveList': 65536,
          'pmcArchiveMetadata': 262144, 'pmcArchiveXML': 2 * 1024 * 1024, 'pmcArchiveImage': 4 * 1024 * 1024}
MAX_TRANSFER = 50 * 1024 * 1024
TOTAL_SECONDS = 180


def require(value, code):
    if not value:
        raise ValueError('PAPER_DOWNLOAD_' + code)


def url_for(pmcid, kind, version=None, filename=None):
    require(isinstance(pmcid, str) and re.fullmatch(r'PMC[1-9][0-9]*', pmcid), 'ID_INVALID')
    require(kind in LIMITS, 'ENDPOINT_INVALID')
    if kind == 'pmcArchiveList':
        return pmc_open_data.listing_url(pmcid)
    if kind == 'pmcArchiveImage':
        return pmc_open_data.image_url(pmcid, version, filename)
    if kind in {'pmcArchiveXML', 'pmcArchiveMetadata'}:
        xml = pmc_open_data.xml_url(pmcid, version)
        return xml if kind == 'pmcArchiveXML' else xml.removesuffix('.xml') + '.json'
    if kind == 'authorManuscriptXML':
        return ('https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=pmc&id='
                + pmcid[3:] + '&retmode=xml')
    return ROOT + pmcid + '/' + kind


def worker(pmcid, kind, destination, limit, version=None, filename=None):
    limit = int(limit)
    require(0 < limit <= LIMITS[kind], 'BYTE_BUDGET')
    request = urllib.request.Request(url_for(pmcid, kind, version, filename), headers={
        'User-Agent': 'Varvis-literature-acquisition/1.0', 'Accept-Encoding': 'identity'})
    size = 0
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=30) as response:
            require(response.status == 200 and response.headers.get('Content-Encoding', 'identity') == 'identity',
                    'RESPONSE_UNSUPPORTED')
            length = response.headers.get('Content-Length')
            require(length is None or length.isdigit() and int(length) <= limit, 'BYTE_BUDGET')
            with Path(destination).open('xb') as output:
                while size < limit:
                    chunk = response.read(min(16384, limit - size))
                    if not chunk:
                        break
                    output.write(chunk)
                    size += len(chunk)
            # At an unknown-length exact cap, stop without reading a budget+1 byte.
            require(size < limit or length is not None and size == int(length), 'BYTE_BUDGET')
            require(length is None or size == int(length), 'BODY_TRUNCATED')
            require(size > 0, 'BODY_EMPTY')
            return {'status': 'OK', 'bytes': size, 'contentType': response.headers.get('Content-Type'),
                    'httpStatus': response.status}
    except urllib.error.HTTPError as error:
        result = {'status': 'UNAVAILABLE', 'httpStatus': error.code,
                  'reasonCode': 'PAPER_DOWNLOAD_NOT_AVAILABLE' if error.code in {404, 410} else
                  'PAPER_DOWNLOAD_RATE_LIMIT' if error.code == 429 else 'PAPER_DOWNLOAD_HTTP_FAILURE'}
        if error.code == 429:
            from email.utils import parsedate_to_datetime
            after, seconds = error.headers.get('Retry-After', '') if error.headers else '', 300
            try:
                seconds = max(seconds, int(after) if after.isdigit() else
                              int(parsedate_to_datetime(after).timestamp() - time.time()))
            except (ValueError, TypeError, OverflowError):
                pass
            result['cooldownSeconds'] = seconds
        return result
    except (ValueError, OSError, urllib.error.URLError) as error:
        code = str(error)
        return {'status': 'UNAVAILABLE', 'reasonCode': code if code.startswith('PAPER_DOWNLOAD_')
                else 'PAPER_DOWNLOAD_TRANSPORT_FAILURE'}


class DownloadTransport(SearchTransport):
    """Use the discovery cache lock and rate state; separate endpoint/body keys."""
    def __init__(self, cache, archive, online=False):
        super().__init__(cache, archive, online)
        self.transferred = 0

    def get_open_access(self, pmcid, candidate):
        try:
            return self.get(pmcid, 'fullTextXML'), url_for(pmcid, 'fullTextXML')
        except ValueError as error:
            if str(error) not in {'PAPER_DOWNLOAD_TIMEOUT', 'PAPER_DOWNLOAD_TRANSPORT_FAILURE',
                                 'PAPER_DOWNLOAD_HTTP_FAILURE', 'PAPER_DOWNLOAD_NOT_AVAILABLE',
                                 'PAPER_DOWNLOAD_NETWORK_DISABLED'}:
                raise
            if self.events[-1].get('response', {}).get('httpStatus') in {401, 403, 429}:
                raise
            # Continue on a different official host under the same budgets and
            # lock. Cache-only mode can reuse an already pinned archive.
            self.stopped = None
        raw, version, _ = self.get_archive_article(pmcid, candidate)
        return raw, url_for(pmcid, 'pmcArchiveXML', version)

    def get_archive_article(self, pmcid, candidate):
        version = pmc_open_data.latest_version(self.get(pmcid, 'pmcArchiveList'), pmcid)
        metadata_raw = self.get(pmcid, 'pmcArchiveMetadata', version)
        metadata = json.loads(metadata_raw, object_pairs_hook=unique)
        checksum = pmc_open_data.xml_digest(metadata, pmcid, version, candidate)
        raw = self.get(pmcid, 'pmcArchiveXML', version)
        pmc_open_data.verify_object(raw, checksum)
        return raw, version, metadata

    def get(self, pmcid, kind, version=None, filename=None):
        require(self.owned, 'LOCK_REQUIRED')
        url = url_for(pmcid, kind, version, filename)
        index = self.cache / (sha(url.encode()) + '.json')
        event = {'pmcid': pmcid, 'kind': kind, 'url': url}
        self.events.append(event)
        try:
            if index.exists():
                record = json.loads(bounded(index, 16384), object_pairs_hook=unique)
                pin = record['sha256']
                require(isinstance(pin, str) and re.fullmatch('[0-9a-f]{64}', pin), 'CACHE_INVALID')
                raw = bounded(self.cache / (pin + '.body'), LIMITS[kind])
                require(sha(raw) == pin and record['url'] == url and raw, 'CACHE_INVALID')
                # Immutable retrieved version: no automatic replacement or refresh.
                event.update(status='CACHE_HIT', sha256=pin, bytes=len(raw), retrievedAt=record['retrievedAt'])
                return raw
            require(not self.stopped, 'BATCH_STOPPED')
            require(self.online, 'NETWORK_DISABLED')
            schedule = self.cache / 'schedule.json'
            state = json.loads(bounded(schedule, 16384), object_pairs_hook=unique) if schedule.exists() else {}
            require(time.time() >= state.get('cooldownUntil', 0), 'RATE_LIMIT_COOLDOWN')
            require(self.requests < 10, 'REQUEST_BUDGET')
            limit = min(LIMITS[kind], MAX_TRANSFER - self.transferred)
            require(limit > 0, 'BYTE_BUDGET')
            delay = max(0, state.get('nextRequestAt', 0) - time.time())
            remaining = TOTAL_SECONDS - (time.monotonic() - self.started)
            require(delay < remaining, 'TOTAL_TIMEOUT')
            if delay:
                time.sleep(delay)
            remaining = TOTAL_SECONDS - (time.monotonic() - self.started)
            require(remaining > 0, 'TOTAL_TIMEOUT')
            atomic_json(schedule, {**state, 'nextRequestAt': time.time() + 1})
            self.requests += 1
            run = self.archive / ('request-' + str(self.requests))
            run.mkdir(exist_ok=False)
            body = run / 'response.body'
            event['startedAt'] = dt.datetime.now(dt.timezone.utc).isoformat()
            command = [sys.executable, '-B', '-X', 'utf8', str(Path(__file__).resolve()),
                       '--worker', pmcid, kind, str(body), str(limit)]
            if version is not None:
                command.append(version)
            if filename is not None:
                command.append(filename)
            try:
                with (run / 'stdout.json').open('xb') as out, (run / 'stderr.log').open('xb') as err:
                    completed = subprocess.run(command, stdout=out, stderr=err, check=False,
                                               timeout=min(60 if kind == 'supplementaryFiles' else 30, remaining))
            except subprocess.TimeoutExpired:
                # run() kills and waits for its one direct child; worker spawns no child.
                event['workerTerminatedAndWaited'] = True
                raise ValueError('PAPER_DOWNLOAD_TIMEOUT') from None
            finally:
                size = body.stat().st_size if body.exists() else 0
                self.transferred += size
                event.update(transferredBytes=size, endedAt=dt.datetime.now(dt.timezone.utc).isoformat())
            require(completed.returncode == 0, 'WORKER_FAILURE')
            result = json.loads(bounded(run / 'stdout.json', 16384), object_pairs_hook=unique)
            event['response'] = result
            if result.get('status') != 'OK':
                if result.get('reasonCode') == 'PAPER_DOWNLOAD_RATE_LIMIT':
                    seconds = result.get('cooldownSeconds', 300)
                    require(type(seconds) is int and seconds >= 300, 'INVALID_COOLDOWN')
                    atomic_json(schedule, {**state, 'cooldownUntil': time.time() + seconds})
                raise ValueError(result.get('reasonCode', 'PAPER_DOWNLOAD_WORKER_FAILURE'))
            raw = bounded(body, limit)
            require(raw and len(raw) == result.get('bytes'), 'BODY_SIZE_MISMATCH')
            pin = sha(raw)
            store = self.cache / (pin + '.body')
            if not store.exists():
                with store.open('xb') as stream:
                    stream.write(raw)
            require(sha(bounded(store, limit)) == pin, 'CACHE_INVALID')
            event.update(status='FETCHED', sha256=pin, bytes=len(raw), retrievedAt=event['endedAt'])
            atomic_json(index, event)
            return raw
        except (ValueError, KeyError, TypeError, OSError) as error:
            code = str(error)
            code = code if code.startswith('PAPER_DOWNLOAD_') else 'PAPER_DOWNLOAD_INVALID_CACHE_OR_RESPONSE'
            if code not in {'PAPER_DOWNLOAD_NOT_AVAILABLE', 'PAPER_DOWNLOAD_NETWORK_DISABLED'}:
                self.stopped = code
            event.update(status='UNAVAILABLE', reasonCode=code)
            raise ValueError(code) from error


if __name__ == '__main__':
    if len(sys.argv) not in {6, 7, 8} or sys.argv[1] != '--worker':
        raise SystemExit('Internal paper download worker only')
    print(json.dumps(worker(*sys.argv[2:])))
