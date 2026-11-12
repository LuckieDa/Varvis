[CmdletBinding(SupportsShouldProcess = $true)]
param([Parameter(Mandatory = $true)][string]$StatePath)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $StatePath -PathType Leaf)) { throw 'ACQUISITION_STATE_MISSING' }
$state = Get-Content -LiteralPath $StatePath -Raw | ConvertFrom-Json
$rootPid = [int]$state.pid
$targets = New-Object System.Collections.Generic.List[int]
function Add-ProcessTree([int]$ParentPid) {
    foreach ($child in @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$ParentPid" -ErrorAction SilentlyContinue)) {
        Add-ProcessTree -ParentPid ([int]$child.ProcessId)
    }
    if (-not $targets.Contains($ParentPid)) { $targets.Add($ParentPid) }
}
Add-ProcessTree -ParentPid $rootPid
foreach ($target in $targets) {
    if (Get-Process -Id $target -ErrorAction SilentlyContinue) {
        if ($PSCmdlet.ShouldProcess("PID $target", 'Stop controlled acquisition process')) { Stop-Process -Id $target -Force }
    }
}
[ordered]@{ status = 'STOP_REQUESTED'; rootPid = $rootPid; targetedPids = @($targets.ToArray()) } | ConvertTo-Json
