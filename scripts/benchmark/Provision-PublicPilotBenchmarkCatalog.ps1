[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$RepositoryRoot,
    [Parameter(Mandatory = $true)][string]$ToolRoot
)

$ErrorActionPreference = 'Stop'
$catalog = 'varvis_benchmark_pilot_0_1_27'
$properties = Join-Path $RepositoryRoot 'varvis-server/config/database-local.properties'
if (-not (Test-Path -LiteralPath $properties)) { throw 'DATABASE_CONFIGURATION_MISSING' }
$report = Join-Path $ToolRoot 'benchmark/varvis-pilot/catalog/catalog-provenance.json'
$server = Join-Path $RepositoryRoot 'varvis-server'
Push-Location $server
try {
    mvn -q -DskipTests compile
    mvn -q dependency:build-classpath '-Dmdep.outputFile=target/benchmark-catalog-classpath.txt'
    $classpath = (Get-Content -LiteralPath 'target/benchmark-catalog-classpath.txt' -Raw).Trim()
    $fullClasspath = "target/classes$([IO.Path]::PathSeparator)$classpath"
    & java -cp $fullClasspath org.nbscn.varvis.variantanalysis.benchmark.execution.BenchmarkCatalogProvisioner `
        --properties $properties --catalog $catalog --report $report
    if ($LASTEXITCODE -ne 0) { throw "CATALOG_PROVISION_FAILED:$LASTEXITCODE" }
} finally {
    Pop-Location
}
