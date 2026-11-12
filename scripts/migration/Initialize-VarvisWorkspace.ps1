[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$codeRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $PSScriptRoot 'VarvisWorkspace.ps1')
$directories = @('resources', 'tools', 'runtime/product-variant-analyses', 'cache/maven',
    'cache/npm', 'development/handoffs', 'development/checkouts', 'development/task-bundles',
    'archive/acceptance', 'archive/task-bundles', 'archive/source-recovery',
    'private/reference-materials', 'knowledge', 'temp', 'migration')
# Validate the complete set before creating anything.
$paths = @($directories | ForEach-Object {
    Assert-VarvisLocalPath -Path (Join-Path $codeRoot "local/$_") -CodeRoot $codeRoot
})
foreach ($path in $paths) { New-Item -ItemType Directory -Path $path -Force | Out-Null }
$env:VARVIS_PROJECT_ROOT = $codeRoot.Replace('\', '/')
$env:VARVIS_RESOURCE_ROOT = (Join-Path $codeRoot 'local/resources').Replace('\', '/')
$env:VARVIS_TOOL_ROOT = (Join-Path $codeRoot 'local/tools').Replace('\', '/')
$drive = $codeRoot.Substring(0, 1).ToLowerInvariant()
$env:VARVIS_WSL_CODE_ROOT = '/mnt/' + $drive + $codeRoot.Substring(2).Replace('\', '/')
$env:VARVIS_WSL_TOOL_ROOT = $env:VARVIS_WSL_CODE_ROOT + '/local/tools'
$env:VARVIS_VARIANT_ANALYSIS_ROOT = ([uri](Join-Path $codeRoot 'local/runtime/product-variant-analyses')).AbsoluteUri
$env:npm_config_cache = Join-Path $codeRoot 'local/cache/npm'
$commandTemplate = Join-Path $codeRoot 'local/private/config/pm2-all-sites-command.template.json'
if (Test-Path -LiteralPath $commandTemplate -PathType Leaf) {
    $commandTemplate = Assert-VarvisLocalPath -Path $commandTemplate -CodeRoot $codeRoot
    $commandOutput = Assert-VarvisLocalPath -Path (Join-Path $codeRoot 'local/private/config/pm2-all-sites-command.json') -CodeRoot $codeRoot
    $command = [IO.File]::ReadAllText($commandTemplate).Replace('${VARVIS_WSL_CODE_ROOT}', $env:VARVIS_WSL_CODE_ROOT)
    $null = $command | ConvertFrom-Json
    if (-not (Test-Path -LiteralPath $commandOutput) -or [IO.File]::ReadAllText($commandOutput) -cne $command) {
        [IO.File]::WriteAllText($commandOutput, $command, (New-Object Text.UTF8Encoding($false)))
    }
}
Write-Host 'Code-local directories initialized. Environment changes apply only to this PowerShell process.'
