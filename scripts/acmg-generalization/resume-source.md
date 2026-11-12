# Bounded continuation of a settled public-source download

`resume_source.py` is a coordinator-only companion to `fetch_source.py`. It uses
that module's existing `NoRedirect`, `save_json` and timestamp functions, plus the
same exclusive `<ledger>.lock` file. It does not change the original downloader,
install tools, create a second budget ledger, retry, unzip or activate a resource.
The target execution environment is the existing Windows Python installation;
final promotion uses Windows `os.rename`, which refuses an existing destination.

The caller must independently pin the failed entry ID, exact source URL/version,
existing prefix byte count and SHA256, official whole-file size and MD5. The tool
requires a **new** destination distinct from the original entry's planned path.
It reads the old `.partial` but never appends to, renames or deletes it.

```text
python -I -S -B -X utf8 resume_source.py --task-root <task-resource-root> --ledger <task-resource-root>/download-ledger.json --failed-entry-id <original-id> --url <exact-official-https-url> --source-version <exact-original-version> --prefix <original-destination>.partial --prefix-size <pinned-prefix-bytes> --prefix-sha256 <pinned-prefix-sha256> --total-size <official-whole-bytes> --whole-md5 <official-whole-md5> --destination <new-path-inside-task-root> --purpose <audited-purpose>
```

This is an interface example, not authorization to execute a real download. Pins
must come from the reviewed source/execution evidence, not be guessed or silently
derived as a substitute for review. The tool calculates and records the completed
file's SHA256; it does not claim an independently published whole-file SHA256 when
only the official MD5 is available.

## Request and resource boundaries

The resolved ledger parent must equal the explicit task root. Prefix, destination
and both temporary outputs must resolve inside that root and cannot alias ledger
files. Existing output files or a coordinator lock cause refusal. The entire
preflight, transfer and accounting operation runs under the central ledger lock.

Before any request, the tool checks:

- the original entry is a finished `FAILED` with exact `Truncated response`, zero
  reservation, matching URL/effective URL/version/declared total/prefix byte count;
- the prefix is precisely that entry's original `.partial`, has exactly the
  supplied size and matches the pinned SHA256;
- the ledger's received counter equals the sum of all entry receive counters,
  including failures, and no entry has an unresolved reservation/lower-bound read;
- whole-file size fits both the ledger's single-file cap and 1,000,000,000 bytes;
  the remaining transfer fits the shared remaining budget, whose maximum cannot
  exceed 5,000,000,000 bytes.

Only the remainder is newly reserved, and it is saved atomically **before** the
one HTTPS request. Reusing the old prefix incurs no new external byte charge; its
original received bytes remain charged in the unchanged failed ledger entry.

The request sends `Accept-Encoding: identity` and an exact bounded
`Range: bytes=<prefix-size>-<whole-size-minus-one>`. It accepts only:

- HTTP 206, with the original exact effective URL;
- one exact `Content-Range: bytes <start>-<end>/<total>`;
- one exact `Content-Length` equal to the reserved remainder;
- absent/identity content encoding and no transfer encoding.

200, redirects, changed totals, missing/duplicate headers, compression and chunked
responses are rejected before any body read. Read sizes never exceed the remaining
reservation. The tool does not issue a second request or an extra EOF probe beyond
that cap. The existing HTTP redirect handler closes redirects without reading or
following their bodies.

## Completion and failure evidence

The newly received bytes go only into `<new-destination>.range.partial`. On exact
completion, the tool streams the unchanged original prefix and new range into a
new `<new-destination>.assembling`. Both component hashes and sizes are rechecked
during assembly, so a changed prefix cannot be promoted after its initial check.
The whole official MD5 must match. After flushing, the assembled file is read back
to verify exact size, MD5 and the independently recomputed on-disk SHA256, then
renamed to the new destination. The original prefix and the downloaded range are
retained. The new ledger entry points to its `resumeOf` original entry and records
the original entry's canonical hash, component identities and whole-file identity.
Success is `CAPTURED_PENDING_SOURCE_REVIEW`, not resource readiness or activation.

A normal premature EOF has a known received count: the new fragment is retained,
actual bytes are charged and unused reservation is released. `IncompleteRead`
preserves and charges its exposed partial bytes; nested/unexposed bytes remain
covered by the unused reservation. A read interruption, timeout or cancellation
records a lower-bound receive count and retains unused reservation conservatively,
following the original downloader's convention. A failure before any read charges
zero. A digest/assembly failure retains the received range and any assembly file;
all received bytes stay charged. No failed fragment is automatically deleted or
automatically selected as another resume source.

If an atomic ledger write fails, the tool retains its lock and the prior reservation
state for explicit coordinator recovery. It does not clear the lock and proceed
with uncertain accounting. A hard process crash has the same conservative recovery
boundary. Do not remove a lock or settle a reservation merely to retry; first inspect
the exact task-owned ledger, `.writing`, output and transfer evidence. The tool itself
has no automatic recovery or retry path.

## Offline tests

Set the process `TEMP`/`TMP` to a dedicated task build-cache directory, then run:

```text
python -I -S -B -X utf8 test_resume_source.py
python -I -S -B -X utf8 test_fetch_source.py
```

Tests use only temporary synthetic files and mock HTTP responses. They verify
reservation-before-request ordering, source/prefix pins, all failure bytes in the
budget, zero-read header rejection, exact chunk bounds, settled EOF versus unknown
read accounting, retained evidence, whole-file verification, prefix changes and
ledger write failures. They make no network requests, read no real resource prefix,
write no central task ledger and do not validate a real continuation.
