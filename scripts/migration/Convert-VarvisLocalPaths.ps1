[CmdletBinding()]
param([string]$ConfigPath, [switch]$Apply)
$ErrorActionPreference = 'Stop'
$codeRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $PSScriptRoot 'VarvisWorkspace.ps1')
if (-not $ConfigPath) { $ConfigPath = Join-Path $codeRoot 'varvis-server/config/variant-analysis-local.properties' }
$ConfigPath = Assert-VarvisLocalPath -Path $ConfigPath -CodeRoot $codeRoot
$original = [IO.File]::ReadAllText($ConfigPath)
# Comments are provenance, not runtime references.
$updated = (($original -split "(?<=`n)" | ForEach-Object {
    if ($_ -match '^\s*[#!]') { $_ } else { ConvertTo-VarvisLocalLayout -Content $_ }
}) -join '')
$unmapped = @($updated -split "`r?`n" | Where-Object {
    $_ -notmatch '^\s*[#!]' -and $_ -match '(?i)[DF]:[/\\]|/mnt/[df]/|\$\{VARVIS_RESOURCE_ROOT\}/Tool/'
})
$summary = [ordered]@{changed = ($updated -cne $original); applyRequested = [bool]$Apply; unmappedLines = $unmapped.Count}
if ($Apply) {
    if ($unmapped.Count) { throw 'Unmapped machine paths remain. Review the local config; no changes written.' }
    if ($summary.changed) {
        $backupRoot = Assert-VarvisLocalPath -Path (Join-Path $codeRoot 'local/migration') -CodeRoot $codeRoot
        New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null
        $backup = Join-Path $backupRoot ('variant-config-' + [guid]::NewGuid().ToString('N') + '.bak')
        Copy-Item -LiteralPath $ConfigPath -Destination $backup
        [IO.File]::WriteAllText($ConfigPath, $updated, (New-Object Text.UTF8Encoding($false)))
        $summary.backupPath = $backup
    }
}
[pscustomobject]$summary | ConvertTo-Json