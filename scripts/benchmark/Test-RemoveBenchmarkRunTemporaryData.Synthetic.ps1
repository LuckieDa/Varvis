$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$cleanupScript = Join-Path $PSScriptRoot "Remove-BenchmarkRunTemporaryData.ps1"
$root = Join-Path ([IO.Path]::GetTempPath()) ("varvis-benchmark-cleanup-" + [Guid]::NewGuid().ToString("N"))

try {
    $run = Join-Path $root "synthetic-run-1"
    foreach ($name in @("input", "preparation", "workspace", "results")) {
        New-Item -ItemType Directory -Path (Join-Path $run $name) -Force | Out-Null
        [IO.File]::WriteAllText((Join-Path $run "$name/evidence.txt"), $name)
    }

    & $cleanupScript -RunsRoot $root -RunId "synthetic-run-1"
    foreach ($name in @("input", "preparation", "workspace")) {
        if (Test-Path -LiteralPath (Join-Path $run $name)) {
            throw "TEMPORARY_DIRECTORY_NOT_REMOVED:$name"
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $run "results/evidence.txt") -PathType Leaf)) {
        throw "RESULT_EVIDENCE_REMOVED"
    }

    $rejected = $false
    try {
        & $cleanupScript -RunsRoot $root -RunId "../outside"
    } catch {
        $rejected = $_.Exception.Message -like "RUN_ID_INVALID:*"
    }
    if (-not $rejected) { throw "TRAVERSAL_RUN_ID_NOT_REJECTED" }
    Write-Output "PASS: benchmark run temporary cleanup"
} finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}
