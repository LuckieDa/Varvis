param([string]$RuntimeRoot)
$ErrorActionPreference = 'Stop'
$code = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $RuntimeRoot) { $RuntimeRoot = Join-Path $code 'local/runtime/bp7-evidence-workbench' }
$runtime = (Resolve-Path -LiteralPath $RuntimeRoot).Path
$s = Get-Content -Raw -LiteralPath (Join-Path $runtime 'session.json') | ConvertFrom-Json
if ($s.runtimeRoot -ne $runtime -or $s.codeRoot -ne $code) { throw 'Session root mismatch.' }
$targets = @()
foreach ($kind in @('server','frontend')) {
    $taskPid = $s.($kind+'Pid')
    $p = Get-Process -Id $taskPid -ErrorAction SilentlyContinue
    if (-not $p) { continue }
    if ($p.StartTime.ToUniversalTime().Ticks -ne ([datetime]$s.($kind+'StartedAtUtc')).ToUniversalTime().Ticks) { throw 'Process identity changed.' }
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $taskPid"
    $expected = if ($kind -eq 'server') { $s.serverArgs } else { Join-Path $PSScriptRoot 'serve-local-evidence.mjs' }
    if (-not $processInfo.CommandLine.Contains($expected)) { throw 'Command identity mismatch.' }
    if (Get-CimInstance Win32_Process -Filter "ParentProcessId = $taskPid" | Where-Object Name -ne 'conhost.exe') { throw 'Active child process; wait for work to finish.' }
    $targets += $p
}
$targets | Stop-Process
'Stopped only this local instance; database and saved results retained.'
