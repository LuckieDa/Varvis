param(
    [Parameter(Mandatory)][ValidateSet('StartBackend', 'StartFrontend', 'StopBackend', 'StopAll')][string]$Action,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][string]$TaskCache,
    [string]$ResourceRoot = $env:VARVIS_RESOURCE_ROOT
)
$ErrorActionPreference = 'Stop'
$tree = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$output = (Resolve-Path -LiteralPath $OutputDirectory).Path
$cache = (Resolve-Path -LiteralPath $TaskCache).Path
if (!(($output + [IO.Path]::DirectorySeparatorChar).StartsWith($cache + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase))) {
    throw 'Acceptance output must be inside the task cache'
}
if ((git -C $tree branch --show-current).Trim() -ne 'feature/ps3-bs3-hnf1a-integration') { throw 'PS3 worktree required' }
$ledger = Join-Path $output 'owned-processes.json'
$owned = @()
if (Test-Path -LiteralPath $ledger) {
    $saved = Get-Content -LiteralPath $ledger -Encoding UTF8 -Raw | ConvertFrom-Json
    if ($saved.schemaVersion -ne 1) { throw 'Unsupported process ledger format' }
    foreach ($item in $saved.processes) {
        if ($item.kind -notin 'backend','frontend' -or !$item.pid -or !$item.startUtc -or !$item.marker) { throw 'Invalid process identity' }
        $owned += $item
    }
}
if ($Action.StartsWith('Stop')) {
    foreach ($entry in $owned) {
        if ($entry.stoppedAt -or ($Action -eq 'StopBackend' -and $entry.kind -ne 'backend')) { continue }
        $proc = Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
        if ($proc) {
            $info = Get-CimInstance Win32_Process -Filter ('ProcessId=' + $entry.pid)
            if ($proc.StartTime.ToUniversalTime().ToString('o') -ne $entry.startUtc -or !$info.CommandLine -or !$info.CommandLine.Contains($entry.marker)) {
                throw 'Recorded PID/start time/command marker does not match; no termination performed'
            }
            Stop-Process -Id $entry.pid -ErrorAction Stop
            $proc.WaitForExit(10000) | Out-Null
        }
        $entry.stoppedAt = [DateTime]::UtcNow.ToString('o')
    }
} else {
    $backend = $Action -eq 'StartBackend'
    $port = if ($backend) { 18237 } else { 8236 }
    $occupied = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -eq $port })
    if ($occupied.Count) { $occupied | Format-Table LocalAddress,LocalPort,OwningProcess; throw 'Port occupied; do not stop its owner' }
    $kind = if ($backend) { 'backend' } else { 'frontend' }
    $sequence = @($owned | Where-Object { $_.kind -eq $kind }).Count + 1
    if ($backend) {
        if (!$ResourceRoot) { throw 'Existing resource root required' }
        $exe = Join-Path $ResourceRoot 'Tool/exomiser/java/temurin-21.0.11+10-x64-windows/bin/java.exe'
        $marker = Join-Path $cache 'network.args'
        if ((Get-FileHash -LiteralPath $marker -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'ff88f349cbf706b67a4d42668b08a318ef6f480bd609f91afaad7a6b143ddf13') { throw 'Original network.args identity changed' }
        $arguments = @('-Dspring.devtools.restart.enabled=false', ('@"' + $marker + '"'))
        $work = Join-Path $tree 'varvis-server'
    } else {
        $exe = (Get-Command node -CommandType Application).Source
        $original = Join-Path $cache 'premerge-review-20260910/serve-review.cjs'
        if ((Get-FileHash -LiteralPath $original -Algorithm SHA256).Hash.ToLowerInvariant() -ne '45a09a0619ba20cff5e622101568963d9fda0df04c08ac9700629ea29deab861') { throw 'Reviewed UI server identity changed' }
        $marker = Join-Path $output 'serve-v101.cjs'
        $scriptText = [IO.File]::ReadAllText($original).Replace("path.join(__dirname, 'frontend/dist')", 'path.resolve(process.argv[2])')
        if (Test-Path -LiteralPath $marker) {
            if ([IO.File]::ReadAllText($marker) -ne $scriptText) { throw 'Existing task UI helper differs' }
        } else { [IO.File]::WriteAllText($marker, $scriptText, (New-Object Text.UTF8Encoding $false)) }
        $dist = Join-Path $cache 'premerge-review-20260910/frontend/dist'
        $arguments = @(('"' + $marker + '"'), ('"' + $dist + '"'))
        $work = $output
    }
    $stdout = Join-Path $output "$kind-$sequence.out.log"
    $stderr = Join-Path $output "$kind-$sequence.err.log"
    $proc = Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $work -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $owned += [pscustomobject]@{ kind=$kind; pid=$proc.Id; startUtc=$proc.StartTime.ToUniversalTime().ToString('o'); marker=$marker; port=$port; stdout=$stdout; stderr=$stderr; stoppedAt=$null }
}
[pscustomobject]@{ schemaVersion=1; processes=$owned } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ledger -Encoding UTF8
$owned | Format-Table kind,pid,port,startUtc,stoppedAt
