[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$planner = Join-Path $PSScriptRoot 'New-BenchmarkBackgroundAcquisitionPlan.ps1'
$manifest = Join-Path $PSScriptRoot 'resources\1000-genomes-grch38-20190312-biallelic.manifest.json'
$workspace = Join-Path ([System.IO.Path]::GetTempPath()) ('varvis-acquisition-plan-' + [Guid]::NewGuid().ToString('N'))

function Assert-ThrowsCode {
    param(
        [string]$Code,
        [scriptblock]$Action
    )

    try {
        & $Action
    } catch {
        if ($_.Exception.Message -notlike "$Code*") {
            throw "Expected $Code but received: $($_.Exception.Message)"
        }
        return
    }
    throw "Expected $Code but the operation succeeded."
}

try {
    New-Item -ItemType Directory -Force -Path $workspace | Out-Null
    $output = Join-Path $workspace 'remaining-primary.json'
    $json = & $planner -ManifestPath $manifest -AcceptedChromosomes '22' -OutputPath $output
    $plan = $json | ConvertFrom-Json
    if ($plan.status -ne 'PLAN_ONLY_NOT_AUTHORIZED_FOR_DOWNLOAD' -or
        $plan.pending.chromosomeCount -ne 21 -or
        $plan.pending.combinedBytes -ne 13139161008 -or
        @($plan.resources | Where-Object action -eq 'ALREADY_ACCEPTED').chromosome -ne '22') {
        throw 'Primary plan did not preserve the frozen chr1-22 scope and accepted chr22 boundary.'
    }
    if ((Get-Content -LiteralPath $output -Raw) -match '[A-Za-z]:[\\/]') {
        throw 'Acquisition plan exposed an absolute machine path.'
    }
    $firstHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $output).Hash
    & $planner -ManifestPath $manifest -AcceptedChromosomes '22' -OutputPath $output | Out-Null
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $output).Hash -ne $firstHash) {
        throw 'Idempotent plan generation changed the output.'
    }

    $withX = (& $planner -ManifestPath $manifest -AcceptedChromosomes '22' -IncludeConditionalChrX) | ConvertFrom-Json
    if ($withX.pending.chromosomeCount -ne 22 -or
        $withX.pending.combinedBytes -ne 13163210315 -or
        $withX.selectedChromosomes[-1] -ne 'X') {
        throw 'Conditional chrX planning did not preserve its separate scope.'
    }
    if ($withX.chromosomePolicy.unavailable.Y -notmatch '^NOT_APPLICABLE' -or
        $withX.chromosomePolicy.unavailable.MT -notmatch '^SOURCE_UNAVAILABLE') {
        throw 'Y and MT statuses were not explicit.'
    }

    Assert-ThrowsCode -Code 'UNKNOWN_CHROMOSOME' -Action {
        & $planner -ManifestPath $manifest -Chromosomes '23' | Out-Null
    }
    Assert-ThrowsCode -Code 'UNSAFE_LOGICAL_RESOURCE_ROOT' -Action {
        & $planner -ManifestPath $manifest -LogicalResourceRoot '../outside' | Out-Null
    }
    Set-Content -LiteralPath $output -Value '{}' -Encoding ASCII
    Assert-ThrowsCode -Code 'OUTPUT_EXISTS_MISMATCH' -Action {
        & $planner -ManifestPath $manifest -AcceptedChromosomes '22' -OutputPath $output | Out-Null
    }
    $incomplete = Join-Path $workspace 'manifest.json.crdownload'
    Copy-Item -LiteralPath $manifest -Destination $incomplete
    Assert-ThrowsCode -Code 'INCOMPLETE_MANIFEST' -Action {
        & $planner -ManifestPath $incomplete | Out-Null
    }

    Write-Output 'Benchmark background acquisition plan checks passed.'
} finally {
    if (Test-Path -LiteralPath $workspace) {
        Remove-Item -LiteralPath $workspace -Recurse -Force
    }
}
