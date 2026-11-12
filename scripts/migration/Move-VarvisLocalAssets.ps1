[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$PlanPath, [switch]$Apply)
$ErrorActionPreference = 'Stop'
$codeRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$projectRoot = Split-Path -Parent $codeRoot
. (Join-Path $PSScriptRoot 'VarvisWorkspace.ps1')
$PlanPath = Assert-VarvisLocalPath -Path $PlanPath -CodeRoot $codeRoot
$plan = Get-Content -LiteralPath $PlanPath -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($entry in $plan) {
    $source = [IO.Path]::GetFullPath($entry.source)
    $destination = Assert-VarvisLocalPath -Path $entry.destination -CodeRoot $codeRoot
    if (-not $source.StartsWith($projectRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
        $source -eq $codeRoot -or
        -not $destination.StartsWith((Join-Path $codeRoot 'local') + '\', [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetPathRoot($source) -ne [IO.Path]::GetPathRoot($destination)) {
        throw 'Only explicitly planned same-volume assets under the project root may be moved into Code/local.'
    }
    if ($entry.status -eq 'MOVED') { continue }
    if (-not (Test-Path -LiteralPath $source)) { throw "Source missing: $source" }
    if ((Get-Item -LiteralPath $source -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "Move a link only after a separate target review: $source"
    }
    if (Test-Path -LiteralPath $destination) {
        if (-not (Test-Path -LiteralPath $destination -PathType Container) -or
            @(Get-ChildItem -LiteralPath $destination -Force).Count) { throw "Destination is not empty: $destination" }
    }
}
if (-not $Apply) { $plan | Select-Object source,destination,status; return }
foreach ($entry in $plan) {
    if ($entry.status -eq 'MOVED') { continue }
    $source = [IO.Path]::GetFullPath($entry.source)
    $destination = Assert-VarvisLocalPath -Path $entry.destination -CodeRoot $codeRoot
    $item = Get-Item -LiteralPath $source -Force
    $before = if ($item.PSIsContainer) {
        @(Get-ChildItem -LiteralPath $source -Force | Sort-Object Name | Select-Object Name,Length,LastWriteTimeUtc)
    } else { @($item | Select-Object Name,Length,LastWriteTimeUtc) }
    $entry | Add-Member -NotePropertyName immediateChildrenBefore -NotePropertyValue $before -Force
    $plan | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $PlanPath -Encoding UTF8
    if (Test-Path -LiteralPath $destination) {
        # Remove only a verified empty destination scaffold, never a resource tree.
        if (@(Get-ChildItem -LiteralPath $destination -Force).Count) { throw "Destination changed: $destination" }
        Remove-Item -LiteralPath $destination
    }
    New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
    Move-Item -LiteralPath $source -Destination $destination
    $after = if ($item.PSIsContainer) {
        @(Get-ChildItem -LiteralPath $destination -Force | Sort-Object Name | Select-Object Name,Length,LastWriteTimeUtc)
    } else { @(Get-Item -LiteralPath $destination | Select-Object Name,Length,LastWriteTimeUtc) }
    if ((ConvertTo-Json -InputObject $before -Depth 4 -Compress) -cne (ConvertTo-Json -InputObject $after -Depth 4 -Compress)) {
        throw "Immediate-child metadata mismatch: $destination"
    }
    $entry.status = 'MOVED'
    $entry | Add-Member -NotePropertyName verification -NotePropertyValue 'Same-volume rename; immediate child names, sizes and mtimes preserved. Not a full content hash.' -Force
    $plan | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $PlanPath -Encoding UTF8
    Write-Host "Moved: $source -> $destination"
}
