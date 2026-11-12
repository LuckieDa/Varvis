[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$RepositoryRoot,
    [Parameter(Mandatory = $true)][string]$ToolRoot,
    [Parameter(Mandatory = $true)][string]$ApplicationJar,
    [Parameter(Mandatory = $true)][string]$ApplicationSourceCommit,
    [Parameter(Mandatory = $true)][string]$BenchmarkDatabaseCatalog,
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$ExecutionAuthorization,
    [int]$TimeoutSeconds = 7200,
    [switch]$Execute
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$CatalogId = 'varvis_benchmark_pilot_0_1_27'
$DatasetRelative = 'docs/benchmark/phenopacket-store-0.1.27-grch38-singleton-pilot-proposal-v1'
$G2Relative = 'docs/benchmark/phenopacket-store-0.1.27-grch38-singleton-pilot-proposal-v1/truth-masked-hpo-review-v1/approved-g2/pilot-g2-input-manifest.json'
$PackageRelative = 'docs/benchmark/phenopacket-store-0.1.27-grch38-singleton-pilot-proposal-v1/execution-package-v2/execution-package.json'
$BackgroundRelative = 'benchmark/1000_genomes/grch38_20190312/merged/na12878_exome_v1/merged-resource-manifest.json'
$ArtifactRelative = 'benchmark/varvis-pilot/artifacts/application-artifact-manifest.json'
$CatalogRelative = 'benchmark/varvis-pilot/catalog/catalog-provenance.json'

function Require-File([string]$Path, [string]$Code) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Code`: $Path" }
    if ($Path -match '\.(part|crdownload|download)$') { throw "INCOMPLETE_RESOURCE_SUFFIX: $Path" }
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

if ($BenchmarkDatabaseCatalog -ne $CatalogId) { throw 'BENCHMARK_CATALOG_ID_MISMATCH' }
if ([string]::IsNullOrWhiteSpace($ExecutionAuthorization)) {
    throw 'EXECUTION_AUTHORIZATION_REQUIRED_BEFORE_PROVIDER_OR_DATABASE_WRITE'
}
if (-not $Execute) { throw 'EXECUTE_SWITCH_REQUIRED_AFTER_EXPLICIT_EXECUTION_AUTHORIZATION' }
if ($TimeoutSeconds -le 0) { throw 'INVALID_TIMEOUT' }

$repository = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$tool = (Resolve-Path -LiteralPath $ToolRoot).Path
$packagePath = Join-Path $repository $PackageRelative
$backgroundManifestPath = Join-Path $tool $BackgroundRelative
$artifactManifestPath = Join-Path $tool $ArtifactRelative
$catalogManifestPath = Join-Path $tool $CatalogRelative
foreach ($path in @($packagePath, $backgroundManifestPath, $artifactManifestPath, $catalogManifestPath, $ApplicationJar)) {
    Require-File $path 'APPROVED_PILOT_EXECUTION_INPUT_MISSING'
}

$package = Get-Content -LiteralPath $packagePath -Raw | ConvertFrom-Json
$artifact = Get-Content -LiteralPath $artifactManifestPath -Raw | ConvertFrom-Json
$catalog = Get-Content -LiteralPath $catalogManifestPath -Raw | ConvertFrom-Json
$background = Get-Content -LiteralPath $backgroundManifestPath -Raw | ConvertFrom-Json
if ($package.status -ne 'READY_PENDING_EXECUTION_AUTHORIZATION' -or $package.executionStatus -ne 'NOT_STARTED' -or
        $package.caseCount -ne 30 -or $package.groupCount -ne 90 -or $package.catalog.logicalCatalogId -ne $CatalogId -or
        $catalog.logicalCatalogId -ne $CatalogId -or $catalog.status -ne 'READY_EMPTY' -or
        $artifact.status -ne 'VALID' -or $artifact.applicationSourceCommit -ne $ApplicationSourceCommit -or
        $artifact.sha256 -ne $package.applicationArtifact.sha256 -or (Get-Sha256 $ApplicationJar) -ne $artifact.sha256 -or
        $background.status -ne 'COMPLETED' -or $background.derived.sha256 -ne $package.backgroundSha256) {
    throw 'APPROVED_PILOT_BINDING_MISMATCH'
}

$python = Get-Command python -ErrorAction Stop
& $python.Source (Join-Path $repository 'scripts/benchmark/revalidate_pilot_execution_enablement.py') `
    --repository-root $repository --artifact-manifest $artifactManifestPath --catalog-manifest $catalogManifestPath `
    --configuration-fingerprint $package.configurationFingerprint --background-sha256 $background.derived.sha256 `
    --output-relative 'execution-package-v2'
if ($LASTEXITCODE -ne 0) { throw "APPROVED_PILOT_PACKAGE_REVALIDATION_FAILED:$LASTEXITCODE" }

# The delegated runner owns the only production WES submission, persistence,
# terminal recovery and complete-rank path. This wrapper only binds the approved
# package and rejects any stale or unapproved execution before that point.
& (Join-Path $PSScriptRoot 'Invoke-PublicBenchmarkDevelopmentSmoke.ps1') `
    -RepositoryRoot $repository -ToolRoot $tool -ApplicationJar $ApplicationJar `
    -ApplicationSourceCommit $ApplicationSourceCommit -RunId $RunId `
    -BenchmarkDatabaseCatalog $CatalogId -MaximumCases 30 -TimeoutSeconds $TimeoutSeconds `
    -DatasetRelative $DatasetRelative -DatasetManifestName 'approved-dataset-manifest.json' -PilotG2ManifestRelative $G2Relative `
    -ApprovedExecutionPackage $packagePath -Execute
if ($LASTEXITCODE -ne 0) { throw "APPROVED_PILOT_EXECUTION_FAILED:$LASTEXITCODE" }
