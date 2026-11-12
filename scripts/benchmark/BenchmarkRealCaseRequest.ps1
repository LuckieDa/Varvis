function New-BenchmarkRealCaseRequest {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)] [string] $RunId,
        [Parameter(Mandatory = $true)] [string] $DatasetId,
        [Parameter(Mandatory = $true)] [string] $DatasetVersion,
        [Parameter(Mandatory = $true)] [string] $CaseId,
        [Parameter(Mandatory = $true)] [string] $GroupId,
        [Parameter(Mandatory = $true)] [string] $ResultId,
        [Parameter(Mandatory = $true)] [string] $AttemptId,
        [Parameter(Mandatory = $true)] [string] $PhenopacketSha256,
        [Parameter(Mandatory = $true)] [string] $TruthSetFingerprint,
        [Parameter(Mandatory = $true)] [string] $ExecutionProvenanceFingerprint,
        [Parameter(Mandatory = $true)] [string] $ConfigurationFingerprint,
        [Parameter(Mandatory = $true)] [string] $InputVcf,
        [Parameter(Mandatory = $true)] [string] $InputVcfSha256,
        [Parameter(Mandatory = $true)] [string] $SampleId,
        [string[]] $HpoTerms = @(),
        [string[]] $HpoProvenance = @(),
        [Parameter(Mandatory = $true)] [object[]] $TruthVariants,
        [Parameter(Mandatory = $true)] [string] $RunManifest,
        [Parameter(Mandatory = $true)] [int] $TimeoutSeconds,
        [Parameter(Mandatory = $true)] [string] $ExpectedDatabaseCatalog,
        [Parameter(Mandatory = $true)] [string] $OutputDirectory
    )

    $schemaVersion = "benchmark-real-case-request-v2"
    $fingerprintFields = @($schemaVersion, $RunId, $DatasetId, $DatasetVersion, $CaseId,
        $GroupId, $PhenopacketSha256, $InputVcfSha256, $TruthSetFingerprint,
        $ExecutionProvenanceFingerprint, $ConfigurationFingerprint, $AttemptId)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $requestFingerprint = ([BitConverter]::ToString($sha.ComputeHash(
            [Text.Encoding]::UTF8.GetBytes(($fingerprintFields -join "`n"))))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }

    return [ordered]@{
        schemaVersion = $schemaVersion
        runId = $RunId
        datasetId = $DatasetId
        datasetVersion = $DatasetVersion
        caseId = $CaseId
        groupId = $GroupId
        resultId = $ResultId
        attemptId = $AttemptId
        requestFingerprint = $requestFingerprint
        phenopacketSha256 = $PhenopacketSha256
        truthSetFingerprint = $TruthSetFingerprint
        executionProvenanceFingerprint = $ExecutionProvenanceFingerprint
        configurationFingerprint = $ConfigurationFingerprint
        inputVcf = $InputVcf
        inputVcfSha256 = $InputVcfSha256
        sampleId = $SampleId
        assembly = "GRCh38"
        analysisMode = "WES"
        hpoTerms = @($HpoTerms)
        hpoProvenance = @($HpoProvenance)
        truthVariants = @($TruthVariants)
        runManifest = $RunManifest
        timeoutSeconds = $TimeoutSeconds
        expectedDatabaseCatalog = $ExpectedDatabaseCatalog
        outputDirectory = $OutputDirectory
    }
}
