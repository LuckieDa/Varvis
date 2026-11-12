$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot "BenchmarkRealCaseRequest.ps1")
. (Join-Path $PSScriptRoot "BenchmarkCaseResult.ps1")

function Get-Utf8Sha256([string] $Value) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash(
            [Text.Encoding]::UTF8.GetBytes($Value)))).Replace("-", "").ToLowerInvariant()
    } finally { $sha.Dispose() }
}

function New-TestRequest([string] $Directory) {
    $truth = [ordered]@{ gene = "SYN1"; canonicalIdentity = [ordered]@{
        genomeAssembly = "GRCh38"; chromosome = "7"; position = 140753336
        reference = "A"; alternate = "T"
    } }
    return New-BenchmarkRealCaseRequest -RunId "run-1" -DatasetId "dataset-1" `
        -DatasetVersion "1" -CaseId "CASE" -GroupId "G0" -ResultId "CASE-G0" `
        -AttemptId "attempt-00000000000000000000000000000001" `
        -PhenopacketSha256 ("1" * 64) -TruthSetFingerprint ("2" * 64) `
        -ExecutionProvenanceFingerprint ("3" * 64) -ConfigurationFingerprint ("4" * 64) `
        -InputVcf (Join-Path $Directory "input.vcf") -InputVcfSha256 ("5" * 64) `
        -SampleId "SYNTHETIC" -TruthVariants @($truth) -RunManifest (Join-Path $Directory "run.json") `
        -TimeoutSeconds 60 -ExpectedDatabaseCatalog "varvis_benchmark_test" -OutputDirectory $Directory
}

function New-TestResult([object] $Request, [string] $Status = "COMPLETED_WITH_FAILURES") {
    $analysisReference = "analysis-1"
    $persistedCount = 6001
    $attestationText = @($Request.requestFingerprint, $analysisReference, "READY",
        [string]$persistedCount, [string]$persistedCount) -join "`n"
    return [ordered]@{
        schemaVersion = "benchmark-case-result-v2"
        resultId = $Request.resultId
        runId = $Request.runId
        datasetId = $Request.datasetId
        caseId = $Request.caseId
        groupId = $Request.groupId
        caseExecutionStatus = $Status
        analysisReference = $analysisReference
        resultBinding = [ordered]@{
            attemptId = $Request.attemptId
            requestFingerprint = $Request.requestFingerprint
            datasetVersion = $Request.datasetVersion
            phenopacketSha256 = $Request.phenopacketSha256
            inputVcfSha256 = $Request.inputVcfSha256
            truthSetFingerprint = $Request.truthSetFingerprint
            executionProvenanceFingerprint = $Request.executionProvenanceFingerprint
            configurationFingerprint = $Request.configurationFingerprint
        }
        databaseAttestation = [ordered]@{
            analysisReference = $analysisReference
            jobStatus = "READY"
            persistedInterpretationCount = $persistedCount
            rankedInterpretationCount = $persistedCount
            verifiedAt = "2026-07-17T00:00:00Z"
            attestationFingerprint = Get-Utf8Sha256 $attestationText
        }
        telemetryArtifact = [ordered]@{
            schemaVersion = "benchmark-analysis-telemetry-v1"
            logicalFileName = "telemetry.json"
            sha256 = Get-Utf8Sha256 '{"status":"COMPLETED"}'
            status = "COMPLETED"
        }
        truthMatches = @([ordered]@{
            truthCanonicalIdentity = [ordered]@{
                genomeAssembly = "GRCh38"; chromosome = "7"; position = 140753336
                reference = "A"; alternate = "T"
            }
            rankStatus = "NOT_IN_RANKING_UNIVERSE"
            rank = $null
            rankingUniverseSize = $persistedCount
        })
    }
}

function Write-TestTelemetry([string] $Directory) {
    [IO.File]::WriteAllText((Join-Path $Directory "telemetry.json"), '{"status":"COMPLETED"}',
        (New-Object Text.UTF8Encoding($false)))
}

function Write-TestJson([object] $Value, [string] $Path) {
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    [IO.File]::WriteAllText($Path, (($Value | ConvertTo-Json -Depth 12) + [Environment]::NewLine),
        (New-Object Text.UTF8Encoding($false)))
}

function Invoke-RejectionCase([string] $Name, [string] $ExpectedCode, [scriptblock] $Mutation) {
    $directory = Join-Path $temporary $Name
    New-Item -ItemType Directory -Path $directory | Out-Null
    $request = New-TestRequest $directory
    $result = New-TestResult $request
    $path = Join-Path $directory "case-result-CASE-G0.json"
    Write-TestTelemetry $directory
    Write-TestJson $result $path
    & $Mutation $request $result $directory
    if ($null -ne $result.schemaVersion -and (Test-Path -LiteralPath $path)) { Write-TestJson $result $path }
    try {
        Read-BenchmarkTerminalCaseResult -Path $path -Request $request `
            -ExpectedTruthIdentities @("GRCh38|7|140753336|A|T") -ProcessExitCode 1 `
            -AttemptProvenancePath (Join-Path $directory "attempt.json") | Out-Null
        throw "$ExpectedCode was accepted"
    } catch {
        if ($_.Exception.Message -notlike "$ExpectedCode*" -and
                $_.Exception.Message -notlike "*$ExpectedCode*") { throw }
    }
}

$temporary = Join-Path ([IO.Path]::GetTempPath()) ("varvis-benchmark-result-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temporary | Out-Null
try {
    $validDirectory = Join-Path $temporary "valid-nonzero"
    New-Item -ItemType Directory -Path $validDirectory | Out-Null
    $request = New-TestRequest $validDirectory
    $result = New-TestResult $request
    $path = Join-Path $validDirectory "case-result-CASE-G0.json"
    $stdout = Join-Path $validDirectory "stdout.log"
    $stderr = Join-Path $validDirectory "stderr.log"
    $attempt = Join-Path $validDirectory "attempt.json"
    Write-TestTelemetry $validDirectory
    Write-TestJson $result $path
    [IO.File]::WriteAllText($stdout, "bounded output", (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText($stderr, "bounded error", (New-Object Text.UTF8Encoding($false)))
    $accepted = Read-BenchmarkTerminalCaseResult -Path $path -Request $request `
        -ExpectedTruthIdentities @("GRCh38|7|140753336|A|T") -ProcessExitCode 7 `
        -StdoutPath $stdout -StderrPath $stderr -AttemptProvenancePath $attempt `
        -WarningAction SilentlyContinue
    if ($accepted.caseExecutionStatus -ne "COMPLETED_WITH_FAILURES") {
        throw "VALID_NONZERO_TERMINAL_RESULT_WAS_NOT_ACCEPTED"
    }
    $provenance = Get-Content $attempt -Raw | ConvertFrom-Json
    if ($provenance.recoveryDecision -ne "RECOVERED_BOUND_TERMINAL_RESULT" -or
            -not $provenance.successfulCaseResult -or
            $provenance.resultSha256 -ne (Get-BenchmarkFileSha256 $path) -or
            $provenance.stdoutSha256 -ne (Get-BenchmarkFileSha256 $stdout) -or
            $provenance.stderrSha256 -ne (Get-BenchmarkFileSha256 $stderr)) {
        throw "ATTEMPT_PROVENANCE_INCOMPLETE"
    }
    $attemptHash = Get-BenchmarkFileSha256 $attempt
    Read-BenchmarkTerminalCaseResult -Path $path -Request $request `
        -ExpectedTruthIdentities @("GRCh38|7|140753336|A|T") -ProcessExitCode 0 `
        -AttemptProvenancePath $attempt -ResumeValidation | Out-Null
    if ((Get-BenchmarkFileSha256 $attempt) -ne $attemptHash) { throw "RESUME_OVERWROTE_ATTEMPT_PROVENANCE" }

    Invoke-RejectionCase "wrong-attempt" "CASE_RESULT_REQUEST_BINDING_MISMATCH" {
        param($request, $result, $directory) $result.resultBinding.attemptId = "attempt-stale"
    }
    Invoke-RejectionCase "wrong-request" "CASE_RESULT_REQUEST_BINDING_MISMATCH" {
        param($request, $result, $directory) $result.resultBinding.requestFingerprint = "6" * 64
    }
    Invoke-RejectionCase "wrong-identity" "CASE_RESULT_IDENTITY_MISMATCH" {
        param($request, $result, $directory) $result.groupId = "G1"
    }
    Invoke-RejectionCase "missing-database" "CASE_RESULT_DATABASE_ATTESTATION_INVALID" {
        param($request, $result, $directory) $result.databaseAttestation = $null
    }
    Invoke-RejectionCase "missing-telemetry" "CASE_RESULT_TELEMETRY_CHECKSUM_MISMATCH" {
        param($request, $result, $directory) Remove-Item -LiteralPath (Join-Path $directory "telemetry.json")
    }
    Invoke-RejectionCase "incomplete-truth" "CASE_RESULT_TRUTH_SET_INCOMPLETE" {
        param($request, $result, $directory) $result.truthMatches = @()
    }
    Invoke-RejectionCase "failed-terminal" "FAILED_TERMINAL_ATTEMPT_NOT_METRIC_RESULT" {
        param($request, $result, $directory) $result.caseExecutionStatus = "FAILED"
    }
    Invoke-RejectionCase "nonterminal" "CASE_RESULT_NOT_TERMINAL" {
        param($request, $result, $directory) $result.caseExecutionStatus = "RUNNING"
    }
    Invoke-RejectionCase "unknown-schema" "CASE_RESULT_SCHEMA_UNSUPPORTED" {
        param($request, $result, $directory) $result.schemaVersion = "benchmark-case-result-v999"
    }
    Invoke-RejectionCase "malformed" "CASE_RESULT_MALFORMED" {
        param($request, $result, $directory)
        $result.schemaVersion = $null
        [IO.File]::WriteAllText((Join-Path $directory "case-result-CASE-G0.json"), '{"partial":',
            (New-Object Text.UTF8Encoding($false)))
    }
    Invoke-RejectionCase "conflict" "CONFLICTING_CASE_RESULTS" {
        param($request, $result, $directory)
        Write-TestJson $result (Join-Path $directory "case-result-OTHER.json")
    }
    Invoke-RejectionCase "missing" "CASE_RESULT_MISSING" {
        param($request, $result, $directory)
        $result.schemaVersion = $null
        $resultPath = Join-Path $directory "case-result-CASE-G0.json"
        if (Test-Path $resultPath) { Remove-Item -LiteralPath $resultPath }
    }
} finally {
    Remove-Item -LiteralPath $temporary -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Output "Benchmark terminal result synthetic checks passed."
