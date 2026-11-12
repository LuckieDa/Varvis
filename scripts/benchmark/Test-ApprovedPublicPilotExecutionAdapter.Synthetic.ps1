[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$adapter = Join-Path $PSScriptRoot 'Invoke-ApprovedPublicPilot.ps1'
$runner = Join-Path $PSScriptRoot 'Invoke-PublicBenchmarkDevelopmentSmoke.ps1'
foreach ($path in @($adapter, $runner)) {
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$null, [ref]$errors) | Out-Null
    if ($errors) { throw "SCRIPT_PARSE_FAILED: $path" }
}

try {
    & $adapter -RepositoryRoot $RepositoryRoot -ToolRoot $RepositoryRoot -ApplicationJar 'missing.jar' `
        -ApplicationSourceCommit ('a' * 40) -BenchmarkDatabaseCatalog 'varvis_benchmark_pilot_0_1_27' `
        -RunId 'stage2b5-approved-public-pilot-v1' -ExecutionAuthorization '   ' -Execute
    throw 'UNAUTHORIZED_EXECUTION_WAS_NOT_REJECTED'
} catch {
    if ($_.Exception.Message -notmatch '^EXECUTION_AUTHORIZATION_REQUIRED_BEFORE_PROVIDER_OR_DATABASE_WRITE') {
        throw
    }
}

try {
    & $adapter -RepositoryRoot $RepositoryRoot -ToolRoot $RepositoryRoot -ApplicationJar 'missing.jar' `
        -ApplicationSourceCommit ('a' * 40) -BenchmarkDatabaseCatalog 'varvis_benchmark_pilot_0_1_27' `
        -RunId 'stage2b5-approved-public-pilot-v1' -ExecutionAuthorization 'synthetic-token'
    throw 'EXECUTE_SWITCH_WAS_NOT_REQUIRED'
} catch {
    if ($_.Exception.Message -notmatch '^EXECUTE_SWITCH_REQUIRED_AFTER_EXPLICIT_EXECUTION_AUTHORIZATION') {
        throw
    }
}

$package = Get-Content -LiteralPath (Join-Path $RepositoryRoot 'docs/benchmark/phenopacket-store-0.1.27-grch38-singleton-pilot-proposal-v1/execution-package-v2/execution-package.json') -Raw | ConvertFrom-Json
if ($package.caseCount -ne 30 -or $package.groupCount -ne 90 -or
        @($package.requests | Sort-Object -Unique).Count -ne 90 -or
        $package.status -ne 'READY_PENDING_EXECUTION_AUTHORIZATION') {
    throw 'APPROVED_PACKAGE_CONTRACT_INVALID'
}
if ((Get-Content -LiteralPath $adapter -Raw) -notmatch 'Invoke-PublicBenchmarkDevelopmentSmoke\.ps1' -or
        (Get-Content -LiteralPath $runner -Raw) -notmatch 'New-BenchmarkRealCaseRequest' -or
        (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'BenchmarkRealCaseRequest.ps1') -Raw) -notmatch 'analysisMode = "WES"') {
    throw 'PRODUCTION_WES_ROUTE_NOT_BOUND'
}
'APPROVED_PUBLIC_PILOT_EXECUTION_ADAPTER_SYNTHETIC_PASS'
