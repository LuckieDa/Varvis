"""Bounded Europe PMC and LitVar discovery. No full text, model, or arbitrary URL.

The old pinned citation worker is deliberately unchanged. A batch holds a shared
cache lock; another process fails busy, rather than racing cooldown or transfers.
"""
import datetime as dt
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from fetch_public_literature import NoRedirect
from select_public_papers import bounded, unique
from prepare_literature import sha

ROOT = 'https://www.ebi.ac.uk/europepmc/webservices/rest/search'
MAX_BODY = 2 * 1024 * 1024
REQUEST_SECONDS = 20
TOTAL_SECONDS = 120
MAX_REQUESTS = 20
PAGE_SIZE = 25


def require(value, reason):
    if not value:
        raise ValueError('LITERATURE_SEARCH_' + reason)


def decode(raw):
    try:
        doc = json.loads(raw, object_pairs_hook=unique)
    except (ValueError, UnicodeError) as error:
        raise ValueError('LITERATURE_SEARCH_INVALID_RESPONSE') from error
    require(isinstance(doc, dict) and type(doc.get('hitCount')) is int and doc['hitCount'] >= 0
            and isinstance(doc.get('resultList'), dict), 'INVALID_RESPONSE')
    rows = doc['resultList'].get('result')
    require(isinstance(rows, list) and len(rows) <= PAGE_SIZE and len(rows) <= doc['hitCount']
            and all(isinstance(row, dict) for row in rows), 'INVALID_RESPONSE')
    cursor = doc.get('nextCursorMark')
    require(cursor is None or isinstance(cursor, str) and 0 < len(cursor) <= 4096, 'INVALID_CURSOR')
    return doc


def url_for(query, cursor='*'):
    require(isinstance(query, str) and 0 < len(query) <= 4096
            and not any(ord(c) < 32 for c in query), 'QUERY_INVALID')
    require(isinstance(cursor, str) and 0 < len(cursor) <= 4096, 'INVALID_CURSOR')
    return ROOT + '?' + urllib.parse.urlencode({'query': query, 'format': 'json',
        'resultType': 'core', 'pageSize': str(PAGE_SIZE), 'cursorMark': cursor})


def worker(query, cursor, destination, source='europepmc'):
    url, decoder = endpoint(source, query, cursor)
    request = urllib.request.Request(url, headers={
        'User-Agent': 'Varvis-literature-discovery/1.0', 'Accept-Encoding': 'identity'})
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=REQUEST_SECONDS) as response:
            require(response.status == 200 and response.headers.get('Content-Encoding', 'identity') == 'identity',
                    'RESPONSE_UNSUPPORTED')
            require(int(response.headers.get('Content-Length', '0')) <= MAX_BODY, 'BODY_LIMIT')
            size = 0
            with Path(destination).open('xb') as stream:
                while True:
                    chunk = response.read(min(16384, MAX_BODY + 1 - size))
                    if not chunk:
                        break
                    size += len(chunk)
                    require(size <= MAX_BODY, 'BODY_LIMIT')
                    stream.write(chunk)
            decoder(bounded(Path(destination), MAX_BODY))
        return {'status': 'OK', 'bytes': size}
    except urllib.error.HTTPError as error:
        result = {'status': 'FAILED', 'reasonCode': 'LITERATURE_SEARCH_RATE_LIMIT' if error.code == 429
                  else 'LITERATURE_SEARCH_HTTP_FAILURE', 'httpStatus': error.code}
        # Do not read error bodies. Respect numeric Retry-After, with a 5 minute floor.
        after = error.headers.get('Retry-After', '') if error.headers else ''
        seconds = 300
        if after.isdigit():
            seconds = max(seconds, int(after))
        else:
            from email.utils import parsedate_to_datetime
            try:
                seconds = max(seconds, int(parsedate_to_datetime(after).timestamp() - time.time()))
            except (ValueError, TypeError, OverflowError):
                pass
        result['cooldownSeconds'] = seconds
        return result
    except (ValueError, OSError, urllib.error.URLError) as error:
        code = str(error)
        return {'status': 'FAILED', 'reasonCode': code if code.startswith('LITERATURE_SEARCH_')
                else 'LITERATURE_SEARCH_TRANSPORT_FAILURE'}


def filesystem_path(path):
    """Use Windows extended paths for filesystem access, without changing identity."""
    path = Path(path).absolute()
    if os.name == 'nt' and not str(path).startswith('\\\\?\\'):
        name = str(path)
        path = Path('\\\\?\\UNC\\' + name[2:] if name.startswith('\\\\') else '\\\\?\\' + name)
    return path


def atomic_json(path, value):
    # Task identities use nested digest directories. Windows' legacy path
    # limit can be exceeded only by the unique temporary filename, even when
    # the final receipt path is valid. Keep the same-directory atomic replace.
    path = filesystem_path(path)
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    temporary.replace(path)


def endpoint(source, query, cursor):
    if source == 'europepmc':
        return url_for(query, cursor), decode
    if source == 'clinvar':
        from pm3_clinvar_supply import vcv_endpoint
        return vcv_endpoint(query)
    require(source == 'litvar', 'SOURCE_UNSUPPORTED')
    from litvar_literature import url_for as litvar_url, decode as litvar_decode
    return litvar_url(cursor, query), lambda raw: litvar_decode(raw, cursor)


class SearchTransport:
    def __init__(self, cache, archive, online=False):
        self.cache, self.archive = Path(cache), Path(archive)
        self.online, self.requests, self.events, self.stopped = online, 0, [], None
        self.started = time.monotonic()
        self.cache.mkdir(parents=True, exist_ok=True)
        self.archive.mkdir(parents=True, exist_ok=True)
        self.lock = self.cache / 'discovery.lock'
        self.owned = False
        self.source_stops = {}
        self.litvar_requests = 0
        self.litvar_seconds = 0

    def __enter__(self):
        try:
            with self.lock.open('x', encoding='utf-8') as stream:
                import os
                stream.write(json.dumps({'pid': os.getpid(), 'createdAt': time.time()}))
            self.owned = True
        except FileExistsError as error:
            raise ValueError('LITERATURE_SEARCH_CACHE_BUSY_OR_INTERRUPTED') from error
        return self

    def __exit__(self, *unused):
        if self.owned:
            self.lock.unlink()

    def get(self, query, cursor='*'):
        return self._get(query, cursor, 'europepmc')

    def get_litvar(self, operation, value):
        return self._get(value, operation, 'litvar')

    def get_clinvar(self, accession):
        return self._get(accession, 'vcv', 'clinvar')

    def _get(self, query, cursor, source):
        require(self.owned, 'LOCK_REQUIRED')
        url, decoder = endpoint(source, query, cursor)
        key = sha(url.encode())
        index = self.cache / (key + '.json')
        event = {'query': query, 'cursor': cursor, 'url': url, 'source': source}
        self.events.append(event)
        try:
            if index.exists():
                cached = json.loads(bounded(index, 16384), object_pairs_hook=unique)
                pin = cached['sha256']
                import re
                require(isinstance(pin, str) and re.fullmatch('[0-9a-f]{64}', pin), 'CACHE_INVALID')
                raw = bounded(self.cache / (pin + '.body'), MAX_BODY)
                require(sha(raw) == pin and cached['url'] == url, 'CACHE_INVALID')
                doc = decoder(raw)
                if source == 'europepmc':
                    empty = doc['hitCount'] == 0
                elif source == 'litvar':
                    from litvar_literature import empty_response
                    empty = empty_response(doc, cursor)
                else:
                    empty = False
                ttl = 86400 if empty else 7 * 86400
                age = time.time() - cached['retrievedEpoch']
                if 0 <= age < ttl:
                    event.update(status='CACHE_HIT', sha256=pin, retrievedAt=cached['retrievedAt'])
                    return raw
                event['expiredCache'] = True
            stopped = self.stopped if source == 'europepmc' else self.source_stops.get(source)
            require(not stopped, stopped.removeprefix('LITERATURE_SEARCH_') if stopped else 'STOPPED')
            require(self.online, 'NETWORK_DISABLED')
            schedule = self.cache / ('schedule.json' if source == 'europepmc' else source + '-schedule.json')
            state = json.loads(bounded(schedule, 16384), object_pairs_hook=unique) if schedule.exists() else {}
            require(time.time() >= state.get('cooldownUntil', 0), 'RATE_LIMIT_COOLDOWN')
            require(self.requests < MAX_REQUESTS, 'REQUEST_BUDGET')
            delay = max(0, state.get('nextRequestAt', 0) - time.time())
            remaining = TOTAL_SECONDS - (time.monotonic() - self.started)
            require(delay < remaining, 'TOTAL_TIMEOUT')
            if delay:
                time.sleep(delay)
            remaining = TOTAL_SECONDS - (time.monotonic() - self.started)
            require(remaining > 0, 'TOTAL_TIMEOUT')
            atomic_json(schedule, {**state, 'nextRequestAt': time.time() + 1})
            self.requests += 1
            if source == 'litvar': self.litvar_requests += 1
            run = self.archive / ('request-' + str(self.requests))
            run.mkdir(exist_ok=False)
            body = run / 'response.body'
            command = [sys.executable, '-B', '-X', 'utf8', str(Path(__file__).resolve()),
                       '--worker', query, cursor, str(body)]
            if source != 'europepmc': command += [source]
            with (run / 'stdout.json').open('xb') as out, (run / 'stderr.log').open('xb') as err:
                began = time.monotonic()
                try:
                    completed = subprocess.run(command, stdout=out, stderr=err, check=False,
                                               timeout=min(REQUEST_SECONDS, remaining))
                except subprocess.TimeoutExpired:
                    # subprocess.run kills and waits for this direct child on timeout.
                    raise ValueError('LITERATURE_SEARCH_TIMEOUT') from None
                finally:
                    if source == 'litvar': self.litvar_seconds += time.monotonic() - began
            require(completed.returncode == 0, 'WORKER_FAILURE')
            result = json.loads(bounded(run / 'stdout.json', 16384), object_pairs_hook=unique)
            if completed.returncode != 0 or result.get('status') != 'OK':
                reason = result.get('reasonCode', 'LITERATURE_SEARCH_WORKER_FAILURE')
                if reason == 'LITERATURE_SEARCH_RATE_LIMIT':
                    seconds = result.get('cooldownSeconds', 300)
                    require(type(seconds) is int and seconds >= 300, 'INVALID_COOLDOWN')
                    atomic_json(schedule, {**state, 'cooldownUntil': time.time() + seconds})
                raise ValueError(reason)
            raw = bounded(body, MAX_BODY)
            decoder(raw)
            pin = sha(raw)
            store = self.cache / (pin + '.body')
            if not store.exists():
                with store.open('xb') as stream:
                    stream.write(raw)
            require(sha(bounded(store, MAX_BODY)) == pin, 'CACHE_INVALID')
            event.update(status='FETCHED', sha256=pin, bytes=len(raw), retrievedEpoch=time.time(),
                         retrievedAt=dt.datetime.now(dt.timezone.utc).isoformat())
            atomic_json(index, event)
            return raw
        except (ValueError, KeyError, TypeError, OSError) as error:
            code = str(error)
            code = code if code.startswith('LITERATURE_SEARCH_') else 'LITERATURE_SEARCH_CACHE_OR_RESPONSE_INVALID'
            # Stop the failing source, while preserving the other discovery route.
            if code not in {'LITERATURE_SEARCH_NETWORK_DISABLED'}:
                if source == 'europepmc': self.stopped = code
                else: self.source_stops[source] = code
            event.update(status='UNAVAILABLE', reasonCode=code)
            raise ValueError(code) from error


if __name__ == '__main__':
    if len(sys.argv) not in (5, 6) or sys.argv[1] != '--worker':
        raise SystemExit('Internal search worker only')
    print(json.dumps(worker(*sys.argv[2:])))
