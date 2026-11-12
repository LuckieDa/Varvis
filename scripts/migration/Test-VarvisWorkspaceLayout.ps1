# Focused regression test; synthetic files only, no network or database access.
$ErrorActionPreference = 'Stop'
$codeRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $PSScriptRoot 'VarvisWorkspace.ps1')
$testRoot = Assert-VarvisLocalPath -Path (Join-Path $codeRoot ('local/temp/layout-' + [guid]::NewGuid().ToString('N'))) -CodeRoot $codeRoot
$fixture = Join-Path $testRoot 'Code with spaces'
$fixtureScripts = Join-Path $fixture 'scripts/migration'
New-Item -ItemType Directory -Path $fixtureScripts -Force | Out-Null
foreach ($name in @('VarvisWorkspace.ps1', 'Initialize-VarvisWorkspace.ps1', 'Convert-VarvisLocalPaths.ps1', 'Test-VarvisMigrationReadiness.ps1', 'Move-VarvisLocalAssets.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $fixtureScripts
}
$script:assertions = 0
function Assert-That {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
    $script:assertions++
}
function Invoke-Fixture {
    param([string]$Name, [string[]]$Arguments)
    $savedPath = $env:PATH
    $savedPreference = $ErrorActionPreference
    try {
        # No native tool execution or download; toolchain checks must report MISSING.
        $env:PATH = ''
        $ErrorActionPreference = 'Continue'
        $output = & "$PSHOME/powershell.exe" -NoProfile -ExecutionPolicy Bypass -File (Join-Path $fixtureScripts $Name) @Arguments 2>&1
        return [pscustomobject]@{Code = $LASTEXITCODE; Output = ($output | Out-String)}
    } finally { $env:PATH = $savedPath; $ErrorActionPreference = $savedPreference }
}
$result = Invoke-Fixture -Name 'Initialize-VarvisWorkspace.ps1'
Assert-That ($result.Code -eq 0) $result.Output
Assert-That (Test-Path (Join-Path $fixture 'local/cache/maven')) 'Initialization failed in a path with spaces.'
$rejected = $false
try { Assert-VarvisLocalPath -Path (Join-Path $fixture '../escape') -CodeRoot $fixture | Out-Null } catch { $rejected = $true }
Assert-That $rejected 'Parent traversal was not rejected.'
$rejected = $false
try { Assert-VarvisLocalPath -Path ($fixture + '-sibling/file') -CodeRoot $fixture | Out-Null } catch { $rejected = $true }
Assert-That $rejected 'Sibling prefix was not rejected.'

$junctionTarget = Join-Path $testRoot 'junction-target'
New-Item -ItemType Directory -Path $junctionTarget | Out-Null
New-Item -ItemType Junction -Path (Join-Path $fixture 'linked') -Target $junctionTarget | Out-Null
$rejected = $false
try { Assert-VarvisLocalPath -Path (Join-Path $fixture 'linked/file') -CodeRoot $fixture | Out-Null } catch { $rejected = $true }
Assert-That $rejected 'An external junction was not rejected.'

$config = Join-Path $fixture 'local/migration/fixture.properties'
$original = @'
# Historical F:/LSD/Project/Tool/spliceai/ reference stays unchanged.
variant-analysis.splice-ai.enabled=true
variant-analysis.splice-ai.version=v1.3.1
variant-analysis.splice-ai.genome-build=GRCh38
variant-analysis.splice-ai.snv-resource-path=F:/LSD/Project/Tool/spliceai/snv.vcf.gz
variant-analysis.splice-ai.snv-index-path=F:/LSD/Project/Tool/spliceai/snv.vcf.gz.tbi
variant-analysis.splice-ai.indel-resource-path=${VARVIS_RESOURCE_ROOT}/Tool/spliceai/indel.vcf.gz
variant-analysis.splice-ai.indel-index-path=${VARVIS_RESOURCE_ROOT}/Tool/spliceai/indel.vcf.gz.tbi
variant-analysis.gnomad.enabled=true
variant-analysis.gnomad.grch38-compact-files=${VARVIS_RESOURCE_ROOT}/Tool/gnomad/a.tsv.gz;${VARVIS_RESOURCE_ROOT}/Tool/gnomad/b.tsv.gz
variant-analysis.normalization.mode=BCFTOOLS
variant-analysis.normalization.grch38-reference=${VARVIS_RESOURCE_ROOT}/Tool/reference/test.fa
variant-analysis.storage-root=${VARVIS_PROJECT_ROOT}/Varvis-runtime/product-variant-analyses
'@
[IO.File]::WriteAllText($config, $original)
$result = Invoke-Fixture -Name 'Convert-VarvisLocalPaths.ps1' -Arguments @('-ConfigPath', $config)
Assert-That ($result.Code -eq 0 -and [IO.File]::ReadAllText($config) -ceq $original) 'Dry run changed a configuration.'
$result = Invoke-Fixture -Name 'Convert-VarvisLocalPaths.ps1' -Arguments @('-ConfigPath', $config, '-Apply')
Assert-That ($result.Code -eq 0) $result.Output
$converted = [IO.File]::ReadAllText($config)
Assert-That ($converted.Contains('variant-analysis.splice-ai.enabled=true') -and $converted.Contains('variant-analysis.gnomad.enabled=true')) 'Feature flags changed.'
Assert-That ($converted.Contains('version=v1.3.1') -and $converted.Contains('genome-build=GRCh38')) 'Version or assembly changed.'
Assert-That ($converted.Contains('# Historical F:/LSD/Project/Tool/spliceai/')) 'Historical comment changed.'
Assert-That ($converted.Contains('storage-root=file:${VARVIS_PROJECT_ROOT}/local/runtime/')) 'Storage URI was not migrated.'
Assert-That ($converted.Contains('snv-index-path=${VARVIS_RESOURCE_ROOT}/spliceai/snv.vcf.gz.tbi')) 'Explicit index path was not converted.'
Assert-That ($converted.Contains('a.tsv.gz;${VARVIS_RESOURCE_ROOT}/gnomad/b.tsv.gz')) 'Resource list was not converted.'
$backup = Get-ChildItem (Join-Path $fixture 'local/migration') -Filter '*.bak'
Assert-That ($backup.Count -eq 1 -and [IO.File]::ReadAllText($backup.FullName) -ceq $original) 'Original backup was not preserved.'
$result = Invoke-Fixture -Name 'Convert-VarvisLocalPaths.ps1' -Arguments @('-ConfigPath', $config, '-Apply')
Assert-That ($result.Code -eq 0 -and [IO.File]::ReadAllText($config) -ceq $converted) 'Conversion is not idempotent.'

foreach ($name in @('spliceai/snv.vcf.gz', 'spliceai/snv.vcf.gz.tbi', 'spliceai/indel.vcf.gz',
    'gnomad/a.tsv.gz', 'gnomad/a.tsv.gz.csi', 'gnomad/b.tsv.gz', 'gnomad/b.tsv.gz.tbi', 'reference/test.fa')) {
    $path = Join-Path $fixture "local/resources/$name"
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    [IO.File]::WriteAllText($path, 'synthetic layout fixture, not valid biological data')
}
$reportPath = Join-Path $fixture 'local/migration/resource-readiness-current.json'
$result = Invoke-Fixture -Name 'Test-VarvisMigrationReadiness.ps1' -Arguments @('-LocalConfigPath', $config, '-FailOnBlocking')
Assert-That ($result.Code -eq 2 -and (Test-Path $reportPath)) $result.Output
$report = Get-Content -Raw $reportPath | ConvertFrom-Json
Assert-That (($report.checks | Where-Object id -eq 'spliceai-snv-index').status -eq 'READY') 'Existing explicit index was not found.'
Assert-That (($report.checks | Where-Object id -eq 'spliceai-indel-index').status -eq 'MISSING') 'Missing explicit index was not detected.'
Assert-That (($report.checks | Where-Object id -eq 'grch38-fai').status -eq 'MISSING') 'Missing FASTA index was not detected.'
Assert-That (($report.checks | Where-Object id -eq 'gnomad-grch38-compact-files-1-index').status -eq 'READY') 'CSI companion was not detected.'
Assert-That (($report.checks | Where-Object id -eq 'gnomad-grch38-compact-files-2-index').status -eq 'READY') 'TBI companion was not detected.'
Assert-That ($report.knownDeferredResources.Count -eq 0) 'Historical deferral leaked into current readiness.'
$result = Invoke-Fixture -Name 'Test-VarvisMigrationReadiness.ps1' -Arguments @('-OutputPath', (Join-Path $testRoot 'outside.json'))
Assert-That ($result.Code -ne 0 -and -not (Test-Path (Join-Path $testRoot 'outside.json'))) 'Report escaped its Code checkout.'
[IO.File]::WriteAllText($config, 'path=F:/unknown/resource.vcf.gz')
$result = Invoke-Fixture -Name 'Convert-VarvisLocalPaths.ps1' -Arguments @('-ConfigPath', $config, '-Apply')
Assert-That ($result.Code -ne 0 -and [IO.File]::ReadAllText($config) -eq 'path=F:/unknown/resource.vcf.gz') 'Unknown legacy paths did not stop conversion.'
$templateDirectory = Join-Path $fixture 'local/private/config'
New-Item -ItemType Directory -Path $templateDirectory -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $templateDirectory 'pm2-all-sites-command.template.json'), '["wsl.exe","${VARVIS_WSL_CODE_ROOT}/scripts/probe.py"]')
$result = Invoke-Fixture -Name 'Initialize-VarvisWorkspace.ps1'
$command = Get-Content (Join-Path $templateDirectory 'pm2-all-sites-command.json') -Raw | ConvertFrom-Json
Assert-That ($result.Code -eq 0 -and $command[1].EndsWith('/Code with spaces/scripts/probe.py') -and -not $command[1].Contains('${')) 'WSL command template did not relocate.'
$asset = Join-Path $testRoot 'asset'
New-Item -ItemType Directory -Path $asset | Out-Null
[IO.File]::WriteAllText((Join-Path $asset 'data.txt'), 'paired-data')
[IO.File]::WriteAllText((Join-Path $asset 'data.txt.tbi'), 'paired-index')
$assetDestination = Join-Path $fixture 'local/resources/moved'
$planPath = Join-Path $fixture 'local/migration/move-plan.json'
@([pscustomobject]@{source=$asset;destination=$assetDestination;status='PLANNED'}) | ConvertTo-Json -Depth 4 | Set-Content $planPath -Encoding UTF8
$result = Invoke-Fixture -Name 'Move-VarvisLocalAssets.ps1' -Arguments @('-PlanPath', $planPath)
Assert-That ($result.Code -eq 0 -and (Test-Path $asset) -and -not (Test-Path $assetDestination)) 'Move dry-run mutated assets.'
$result = Invoke-Fixture -Name 'Move-VarvisLocalAssets.ps1' -Arguments @('-PlanPath', $planPath, '-Apply')
Assert-That ($result.Code -eq 0 -and -not (Test-Path $asset) -and (Test-Path (Join-Path $assetDestination 'data.txt.tbi'))) $result.Output
$result = Invoke-Fixture -Name 'Move-VarvisLocalAssets.ps1' -Arguments @('-PlanPath', $planPath, '-Apply')
Assert-That ($result.Code -eq 0) 'Completed move plan was not safely resumable.'
Write-Host "PASS: $script:assertions assertions. Synthetic fixtures retained at $testRoot"
