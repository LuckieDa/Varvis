param(
    [Parameter(Mandatory = $true)][string]$RuntimeRoot,
    [switch]$ServerOnly
)

$ErrorActionPreference = 'Stop'
$demoRuntime = (Resolve-Path -LiteralPath $RuntimeRoot).Path
$demoSession = Get-Content -Raw -LiteralPath (Join-Path $demoRuntime 'session.json') | ConvertFrom-Json
if ($demoSession.runtimeRoot -ne $demoRuntime) { throw 'Session runtime root mismatch.' }
$demoTargets = @('server')
if (-not $ServerOnly) { $demoTargets += 'frontend' }
$demoProcesses = @()
function Get-DemoChildProcesses([int]$parent, [datetime]$started, [int]$depth = 0) {
    if ($depth -gt 8) { throw 'Unexpected demo process tree depth.' }
    foreach ($child in Get-CimInstance Win32_Process -Filter "ParentProcessId = $parent") {
        if ($child.Name -eq 'conhost.exe') { continue }
        if ($child.CreationDate -lt $started) { throw 'Reused process identity in demo tree.' }
        Get-DemoChildProcesses $child.ProcessId $started ($depth + 1)
        Get-Process -Id $child.ProcessId -ErrorAction Stop
    }
}
foreach ($kind in $demoTargets) {
    $demoPid = $demoSession.($kind + 'Pid')
    if (-not $demoPid) { continue }
    $demoProcess = Get-Process -Id $demoPid -ErrorAction SilentlyContinue
    if (-not $demoProcess) { continue }
    $demoRecordedStart = $demoSession.($kind + 'StartedAtUtc')
    if (-not $demoRecordedStart -or $demoProcess.StartTime.ToUniversalTime().ToString('o') -ne $demoRecordedStart) {
        throw "The $kind process identity could not be verified; nothing was stopped."
    }
    $demoCommand = (Get-CimInstance Win32_Process -Filter "ProcessId = $demoPid").CommandLine
    if ($kind -eq 'server') {
        if (-not $demoCommand.Contains('org.nbscn.varvis.VarvisServerApplication') -or
            -not $demoCommand.Contains($demoRuntime.Replace('\', '/') + '/database/')) {
            throw 'Server command is outside the recorded demo runtime.'
        }
        $demoChildren = Get-CimInstance Win32_Process -Filter "ParentProcessId = $demoPid" |
            Where-Object { $_.Name -ne 'conhost.exe' }
        if ($demoChildren) { throw 'Analysis subprocesses are still running. Wait for completion before stopping the demo.' }
    } else {
        if (-not $demoCommand.Contains((Join-Path $demoSession.codeRoot 'varvis-client-pro/node_modules/umi/bin/umi.js'))) {
            throw 'Frontend command does not match the recorded demo repository.'
        }
        $demoProcesses += @(Get-DemoChildProcesses $demoPid $demoProcess.StartTime)
    }
    $demoProcesses += $demoProcess
}
# All identities are checked before any process is stopped. No files are deleted.
$demoProcesses | Stop-Process -ErrorAction Stop
Write-Output 'Stopped only the verified demo processes; saved analyses and database were retained.'
