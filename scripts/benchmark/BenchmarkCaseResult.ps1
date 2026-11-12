Set-StrictMode -Version Latest

function Get-BenchmarkFileSha256 {
    param([string] $Path)
    if (-not $Path -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        $sha = [Security.Cryptography.SHA256]::Create()
        try {
            return ([BitConverter]::ToString($sha.ComputeHash([byte[]]@()))).Replace("-", "").ToLowerInvariant()
        } finally { $sha.Dispose() }
    }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-BenchmarkCanonicalIdentity {
    param([object] $Identity)
    return "$($Identity.genomeAssembly)|$($Identity.chromosome)|$($Identity.position)|$($Identity.reference)|$($Identity.alternate)"
}

function Write-BenchmarkAttemptProvenance {
    param(
        [string] $Path,
        [object] $Request,
        [int] $ProcessExitCode,
        [string] $StdoutPath,
        [string] $StderrPath,
        [string] $AttemptStatus,
        [string] $RecoveryDecision,
        [string] $RecoveryReason,
        [string] $ResultSha256,
        [object] $Result
    )
    if (-not $Path) { return }
    $value = [ordered]@{
        schemaVersion = "benchmark-attempt-provenance-v1"
        attemptId = $Request.attemptId
        requestFingerprint = $Request.requestFingerprint
        runId = $Request.runId
        datasetId = $Request.datasetId
        datasetVersion = $Request.datasetVersion
        caseId = $Request.caseId
        groupId = $Request.groupId
        processExitCode = $ProcessExitCode
        stdoutSha256 = Get-BenchmarkFileSha256 $StdoutPath
        stderrSha256 = Get-BenchmarkFileSha256 $StderrPath
        attemptStatus = $AttemptStatus
        recoveryDecision = $RecoveryDecision
        recoveryReason = $RecoveryReason
        resultSha256 = $ResultSha256
        recoveredResultId = $(if ($Result) { $Result.resultId } else { $null })
        recoveredAnalysisReference = $(if ($Result) { $Result.analysisReference } else { $null })
        successfulCaseResult = $(if ($Result) { $Result.caseExecutionStatus -in @("COMPLETED", "COMPLETED_WITH_FAILURES") } else { $false })
    }
    $temporary = "$Path.tmp"
    [IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    [IO.File]::WriteAllText($temporary, (($value | ConvertTo-Json -Depth 8) + [Environment]::NewLine),
        (New-Object Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Read-BenchmarkTerminalCaseResult {
    param(
        [Parameter(Mandatory = $true)] [string] $Path,
        [Parameter(Mandatory = $true)] [object] $Request,
        [Parameter(Mandatory = $true)] [string[]] $ExpectedTruthIdentities,
        [Parameter(Mandatory = $true)] [int] $ProcessExitCode,
        [string] $StdoutPath,
        [string] $StderrPath,
        [string] $AttemptProvenancePath,
        [switch] $ResumeValidation
    )

    $result = $null
    $resultSha = $null
    $decision = "REJECTED"
    $reason = "UNCLASSIFIED_RESULT_REJECTION"
    try {
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
            $reason = "CASE_RESULT_MISSING"
            throw "$reason`: $($Request.resultId)"
        }
        $siblings = @(Get-ChildItem -LiteralPath (Split-Path -Parent $Path) -File -Filter "case-result-*.json")
        if ($siblings.Count -ne 1 -or $siblings[0].FullName -ne (Resolve-Path -LiteralPath $Path).Path) {
            $reason = "CONFLICTING_CASE_RESULTS"
            throw "$reason`: $($Request.resultId)"
        }
        try {
            $result = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
        } catch {
            $reason = "CASE_RESULT_MALFORMED"
            throw "$reason`: $($Request.resultId)"
        }
        $resultSha = Get-BenchmarkFileSha256 $Path
        if ($result.schemaVersion -ne "benchmark-case-result-v2") {
            $reason = "CASE_RESULT_SCHEMA_UNSUPPORTED"
            throw "$reason`: $($Request.resultId)"
        }
        if ($result.runId -ne $Request.runId -or $result.datasetId -ne $Request.datasetId -or
                $result.caseId -ne $Request.caseId -or $result.groupId -ne $Request.groupId -or
                $result.resultId -ne $Request.resultId) {
            $reason = "CASE_RESULT_IDENTITY_MISMATCH"
            throw "$reason`: $($Request.resultId)"
        }
        $binding = $result.resultBinding
        if (-not $binding -or $binding.attemptId -ne $Request.attemptId -or
                $binding.requestFingerprint -ne $Request.requestFingerprint -or
                $binding.datasetVersion -ne $Request.datasetVersion -or
                $binding.phenopacketSha256 -ne $Request.phenopacketSha256 -or
                $binding.inputVcfSha256 -ne $Request.inputVcfSha256 -or
                $binding.truthSetFingerprint -ne $Request.truthSetFingerprint -or
                $binding.executionProvenanceFingerprint -ne $Request.executionProvenanceFingerprint -or
                $binding.configurationFingerprint -ne $Request.configurationFingerprint) {
            $reason = "CASE_RESULT_REQUEST_BINDING_MISMATCH"
            throw "$reason`: $($Request.resultId)"
        }
        $actualTruths = @($result.truthMatches | ForEach-Object {
            Get-BenchmarkCanonicalIdentity $_.truthCanonicalIdentity
        } | Sort-Object -Unique)
        $expectedTruths = @($ExpectedTruthIdentities | Sort-Object -Unique)
        if ($actualTruths.Count -ne $expectedTruths.Count -or
                @(Compare-Object $expectedTruths $actualTruths).Count -ne 0) {
            $reason = "CASE_RESULT_TRUTH_SET_INCOMPLETE"
            throw "$reason`: $($Request.resultId)"
        }
        if ($result.caseExecutionStatus -notin @("COMPLETED", "COMPLETED_WITH_FAILURES", "FAILED")) {
            $reason = "CASE_RESULT_NOT_TERMINAL"
            throw "$reason`: $($Request.resultId)"
        }
        $attestation = $result.databaseAttestation
        if (-not $attestation -or $attestation.analysisReference -ne $result.analysisReference -or
                $attestation.jobStatus -ne "READY" -or
                [int]$attestation.persistedInterpretationCount -lt 0 -or
                [int]$attestation.rankedInterpretationCount -lt 0 -or
                [int]$attestation.persistedInterpretationCount -ne [int]$attestation.rankedInterpretationCount) {
            $reason = "CASE_RESULT_DATABASE_ATTESTATION_INVALID"
            throw "$reason`: $($Request.resultId)"
        }
        $attestationText = @($Request.requestFingerprint, $result.analysisReference, "READY",
            [string]$attestation.persistedInterpretationCount, [string]$attestation.rankedInterpretationCount) -join "`n"
        $sha = [Security.Cryptography.SHA256]::Create()
        try {
            $expectedAttestation = ([BitConverter]::ToString($sha.ComputeHash(
                [Text.Encoding]::UTF8.GetBytes($attestationText)))).Replace("-", "").ToLowerInvariant()
        } finally { $sha.Dispose() }
        if ($attestation.attestationFingerprint -ne $expectedAttestation) {
            $reason = "CASE_RESULT_DATABASE_ATTESTATION_MISMATCH"
            throw "$reason`: $($Request.resultId)"
        }
        $telemetry = $result.telemetryArtifact
        if (-not $telemetry -or $telemetry.schemaVersion -ne "benchmark-analysis-telemetry-v1" -or
                $telemetry.status -ne "COMPLETED" -or
                [string]::IsNullOrWhiteSpace($telemetry.logicalFileName) -or
                $telemetry.logicalFileName -match '[/\\]' -or
                $telemetry.sha256 -notmatch '^[0-9a-f]{64}$') {
            $reason = "CASE_RESULT_TELEMETRY_INVALID"
            throw "$reason`: $($Request.resultId)"
        }
        $telemetryPath = Join-Path (Split-Path -Parent $Path) $telemetry.logicalFileName
        if (-not (Test-Path -LiteralPath $telemetryPath -PathType Leaf) -or
                (Get-BenchmarkFileSha256 $telemetryPath) -ne $telemetry.sha256) {
            $reason = "CASE_RESULT_TELEMETRY_CHECKSUM_MISMATCH"
            throw "$reason`: $($Request.resultId)"
        }
        if ($result.caseExecutionStatus -eq "FAILED") {
            $reason = "FAILED_TERMINAL_ATTEMPT_NOT_METRIC_RESULT"
            throw "$reason`: $($Request.resultId)"
        }
        $decision = $(if ($ProcessExitCode -eq 0) { "ACCEPTED_ZERO_EXIT" } else { "RECOVERED_BOUND_TERMINAL_RESULT" })
        $reason = $(if ($ProcessExitCode -eq 0) { "PROCESS_COMPLETED" } else { "NONZERO_EXIT_WITH_FRESH_BOUND_RESULT" })
        if (-not $ResumeValidation) {
            Write-BenchmarkAttemptProvenance -Path $AttemptProvenancePath -Request $Request `
                -ProcessExitCode $ProcessExitCode -StdoutPath $StdoutPath -StderrPath $StderrPath `
                -AttemptStatus "TERMINAL_RESULT_PARSED" -RecoveryDecision $decision `
                -RecoveryReason $reason -ResultSha256 $resultSha -Result $result
        }
        if ($ProcessExitCode -ne 0) {
            Write-Warning "BENCHMARK_PROCESS_NONZERO_WITH_BOUND_TERMINAL_RESULT: $($Request.resultId) (exit=$ProcessExitCode)"
        }
        return $result
    } catch {
        if (-not $ResumeValidation) {
            Write-BenchmarkAttemptProvenance -Path $AttemptProvenancePath -Request $Request `
                -ProcessExitCode $ProcessExitCode -StdoutPath $StdoutPath -StderrPath $StderrPath `
                -AttemptStatus "TERMINAL_RESULT_REJECTED" -RecoveryDecision $decision `
                -RecoveryReason $reason -ResultSha256 $resultSha -Result $result
        }
        throw
    }
}
