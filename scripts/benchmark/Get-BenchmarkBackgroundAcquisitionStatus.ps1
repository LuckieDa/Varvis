[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$StatePath)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $StatePath -PathType Leaf)) { throw 'ACQUISITION_STATE_MISSING' }
$state = Get-Content -LiteralPath $StatePath -Raw | ConvertFrom-Json
$state | Add-Member -NotePropertyName controllerAlive -NotePropertyValue ([bool](Get-Process -Id $state.pid -ErrorAction SilentlyContinue)) -Force
$state | Add-Member -NotePropertyName workerAlive -NotePropertyValue ([bool]($null -ne $state.workerPid -and (Get-Process -Id $state.workerPid -ErrorAction SilentlyContinue))) -Force
$state | ConvertTo-Json -Depth 8
