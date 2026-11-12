"""Bounded public-source retrieval with immutable response and failure receipts."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import urllib.error
import urllib.request


def fetch(url, output, limit=5_000_000, timeout=30):
    output = Path(output)
    receipt = output.with_name(output.name + '.receipt.json')
    if output.exists() or receipt.exists():
        raise ValueError('Refusing to overwrite source or prior attempt')
    output.parent.mkdir(parents=True, exist_ok=True)
    event = {'url': url, 'retrievedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
             'maximumBytes': limit, 'timeoutSeconds': timeout}
    try:
        request = urllib.request.Request(url, headers={'User-Agent': 'Varvis public evidence verification'})
        with urllib.request.urlopen(request, timeout=timeout) as response:
            event.update(status=response.status, finalUrl=response.url,
                         headers={k: v for k, v in response.headers.items()
                                  if k.lower() in {'content-type', 'content-length', 'etag', 'last-modified', 'date'}})
            data = response.read(limit + 1)
            if len(data) > limit:
                raise ValueError('Response exceeds declared byte limit')
            output.write_bytes(data)
            event.update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
    except Exception as failure:
        event.update(errorType=type(failure).__name__, error=str(failure))
        if isinstance(failure, urllib.error.HTTPError):
            event['status'] = failure.code
        raise
    finally:
        receipt.write_text(json.dumps(event, indent=2) + '\n', encoding='utf-8')
    return event


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('url')
    parser.add_argument('output')
    args = parser.parse_args()
    print(json.dumps(fetch(args.url, args.output), indent=2))
