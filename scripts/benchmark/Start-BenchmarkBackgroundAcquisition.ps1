[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ResourceRoot,
    [Parameter(Mandatory = $true)][string]$BedPath,
    [Parameter(Mandatory = $true)][string]$CurlPath,
    [string]$CaBundlePath = '',
    [Parameter(Mandatory = $true)][string]$BcftoolsPath,
    [Parameter(Mandatory = $true)][string]$BgzipPath,
    [Parameter(Mandatory = $true)][string]$TabixPath,
    [ValidateRange(1, 16)]
    [int]$SegmentCount = 4
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'Background-BenchmarkBackgroundAcquisition.ps1'
$control = Join-Path $ResourceRoot 'control'
New-Item -ItemType Directory -Force -Path $control | Out-Null
$state = Join-Path $control 'acquisition-state.json'
if (Test-Path -LiteralPath $state) {
    $existing = Get-Content -LiteralPath $state -Raw | ConvertFrom-Json
    if ($existing.status -in @('STARTING','RUNNING') -and (Get-Process -Id $existing.pid -ErrorAction SilentlyContinue)) {
        throw "ACQUISITION_ALREADY_RUNNING: PID $($existing.pid)"
    }
}
$out = Join-Path $control 'worker.out.log'
$err = Join-Path $control 'worker.error.log'
$controllerOut = Join-Path $control 'controller.out.log'
$controllerErr = Join-Path $control 'controller.error.log'
$arguments = @(
    '-NoProfile', '-File', $runner,
    '-ResourceRoot', $ResourceRoot, '-BedPath', $BedPath,
    '-CurlPath', $CurlPath, '-CaBundlePath', $CaBundlePath, '-BcftoolsPath', $BcftoolsPath,
    '-BgzipPath', $BgzipPath, '-TabixPath', $TabixPath,
    '-StatePath', $state, '-WorkerOutPath', $out, '-WorkerErrorPath', $err,
    '-SegmentCount', ([string]$SegmentCount)
)
$process = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput $controllerOut -RedirectStandardError $controllerErr
[ordered]@{
    schemaVersion = 'benchmark-background-process-launch-v1'
    status = 'STARTED'
    pid = $process.Id
    stateRelativePath = 'control/acquisition-state.json'
    stopCommand = 'Stop-BenchmarkBackgroundAcquisition.ps1 -StatePath <resource-root>/control/acquisition-state.json'
} | ConvertTo-Json -Depth 5
