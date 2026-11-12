# Native Windows SpliceAI feasibility adapter

This is a CPU feasibility path, not an activated ACMG provider. It consumes the
existing `spliceai-raw-batch-request-v1` JSON for at most ten GRCh38 sequence
alleles. The result schema is deliberately different:
`spliceai-native-feasibility-result-v1`. Do not point the production runner at it
or reuse the old WSL profile as its runtime identity.

## Runtime and installation

Use a separate Python 3.11 x64 environment at
`local/tools/VarvisSpliceAIRuntime/native-windows-v1/venv`. Its base Python is the
existing Code-local installation. Do not install into the AlphaGenome venv or
replace the retained Linux lockfile.

Place `keras/keras.json` beside the venv, with `floatx=float32`, `epsilon=1e-7`,
`backend=tensorflow`, `image_data_format=channels_last`. Adapter 1.0.1 checks this
file, includes its identity, and sets process-local `KERAS_HOME` before import.
It will not silently create or consume the user's global Keras configuration.

`spliceai_native_windows_requirements.txt` lists direct dependencies;
`spliceai_native_windows.lock.txt` pins the resolved Windows wheels and SHA-256
values, including SpliceAI. Reinstallation uses the official PyPI index, binary
wheels, `--require-hashes` and **`--no-deps`** with this complete explicit list.
Inspect changes to the platform lock before installation; do not automatically
upgrade TensorFlow, Keras, NumPy, the official models or the frozen source pins.

SpliceAI's official distribution declares `pysam`. This adapter intentionally
omits that CLI-only dependency and therefore `pip check` reports it missing.
The expected exception is only `spliceai requires pysam`; any additional missing
or conflicting dependency is a failure. This is not a claim that the official
VCF CLI works on Windows. The installed package and its metadata are not edited,
and no fake pysam module is provided.

The adapter calls unchanged official `Annotator/get_delta_scores` functions
using records with `chrom/pos/ref/alts`. It hashes the five models, gene annotation
and fixed official source files against the original asset pins. Package versions,
adapter/helper bytes, CPU settings and reference metadata form a separate probe
identity. The original reference is shared read-only; metadata/index checks do
not claim a full FASTA byte hash.

## Execution and limits

An asset-only check can be run from the repository root:

```powershell
& ./local/tools/VarvisSpliceAIRuntime/native-windows-v1/venv/Scripts/python.exe -B `
  ./scripts/e4_b3/run_spliceai_native_probe.py --readiness-only `
  --reference-fasta ./local/tools/vep/Homo_sapiens.GRCh38.dna.primary_assembly.fa `
  --output <new-task-directory>/readiness.json
```

For inference, use `--batch-request <frozen-request.json>` instead of
`--readiness-only`. A task supervisor must impose the wall-clock timeout and
terminate/confirm exit of its own process tree. The adapter does not fork a model
worker, but Windows venv launchers can introduce a child interpreter. Calling
`kill()` only on a launcher is insufficient evidence of model termination.

Each batch creates one Annotator and counts five real model loads; all variants
reuse them. CPU settings are fixed before importing TensorFlow: four intra-op
threads, one inter-op thread, oneDNN disabled, no GPU. Counts wrap the actual
loader and predict methods without replacing their computations.

Parameters remain `D=4999/M=0`; outputs preserve the official two-decimal DS
lexemes and all four DP values. Invalid REF, absent reference contig, incomplete
window and unsupported complex alleles receive explicit reasons. An empty gene
annotation result is distinct from a failed prediction. A returned gene symbol
does not establish compatibility with the requested transcript.

No existing output is overwritten. Runtime exceptions exit nonzero and retain a
structured failure when possible. This probe neither evaluates PP3/BP4 nor
changes an existing result, ranking, policy, database, reference or daily config.

## Verification

Offline boundary tests (no model inference):

```powershell
& ./local/tools/VarvisSpliceAIRuntime/native-windows-v1/venv/Scripts/python.exe -B `
  -m unittest discover -s ./scripts/e4_b3 -p test_spliceai_native_probe.py -v
```

The 2026-10-12 task's frozen inputs, original process logs and independent-process
comparisons live at
`local/development/handoffs/PP3-BP4-windows-native-20261012/dev-01/`. Reuse those
results rather than rerunning their supervisor into the same output paths.
Production integration needs a separate adopted runtime identity, exact
transcript association, real Top-N execution and save/restart/reuse validation.
