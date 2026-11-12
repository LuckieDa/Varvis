[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BcftoolsPath,

    [Parameter(Mandatory = $true)]
    [string]$BgzipPath,

    [Parameter(Mandatory = $true)]
    [string]$TabixPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$acceptanceScript = Join-Path $PSScriptRoot 'Test-BenchmarkBackgroundResource.ps1'
$fixture = Join-Path $repoRoot 'varvis-server\src\test\resources\benchmark\resource-acceptance-grch38-na12878.vcf'
$workspace = Join-Path ([System.IO.Path]::GetTempPath()) ("varvis-resource-acceptance-" + [Guid]::NewGuid().ToString('N'))

function Assert-Result {
    param(
        [string]$Name,
        [string]$ExpectedStatus,
        [string]$ExpectedCode,
        [hashtable]$Parameters
    )

    & $acceptanceScript @Parameters | Out-Null
    $exitCode = $LASTEXITCODE
    $reportPath = Join-Path $Parameters.OutputDirectory 'benchmark-resource-acceptance.json'
    if (-not (Test-Path -LiteralPath $reportPath)) {
        throw "$Name did not write its structured acceptance report."
    }
    $json = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    if ($json.status -ne $ExpectedStatus) {
        throw "$Name returned $($json.status), expected $ExpectedStatus."
    }
    if ($ExpectedCode -and -not ($json.steps | Where-Object { $_.code -eq $ExpectedCode })) {
        throw "$Name did not return expected code $ExpectedCode."
    }
    if ($ExpectedStatus -eq 'FAILED' -and $exitCode -eq 0) {
        throw "$Name should return a non-zero exit code."
    }
    if ($ExpectedStatus -eq 'PASSED' -and $exitCode -ne 0) {
        throw "$Name should return exit code zero."
    }
}

function Copy-Parameters {
    param([hashtable]$Source)

    $copy = @{}
    foreach ($key in $Source.Keys) {
        $copy[$key] = $Source[$key]
    }
    return $copy
}

try {
    New-Item -ItemType Directory -Force -Path $workspace | Out-Null
    $validPlain = Join-Path $workspace 'valid.vcf'
    $validGz = "$validPlain.gz"
    Copy-Item -LiteralPath $fixture -Destination $validPlain
    & $BgzipPath -f $validPlain
    if ($LASTEXITCODE -ne 0) { throw 'Could not BGZF-compress synthetic fixture.' }
    & $TabixPath -f -p vcf $validGz
    if ($LASTEXITCODE -ne 0) { throw 'Could not Tabix-index synthetic fixture.' }
    $bed = Join-Path $workspace 'benchmark.bed'
    [System.IO.File]::WriteAllText($bed, "chr22`t199`t240`n", [System.Text.UTF8Encoding]::new($false))

    $size = (Get-Item -LiteralPath $validGz).Length
    $md5 = (Get-FileHash -Algorithm MD5 -LiteralPath $validGz).Hash.ToLowerInvariant()
    $base = @{
        VcfPath = $validGz
        IndexPath = "$validGz.tbi"
        ExpectedIndexByteSize = (Get-Item -LiteralPath "$validGz.tbi").Length
        ExpectedIndexMd5 = (Get-FileHash -Algorithm MD5 -LiteralPath "$validGz.tbi").Hash.ToLowerInvariant()
        ExpectedByteSize = $size
        ExpectedMd5 = $md5
        ExpectedSampleId = 'NA12878'
        ExpectedAssembly = 'GRCh38'
        ExpectedContig = 'chr22'
        TinyRegion = 'chr22:200-240'
        BedPath = $bed
        BcftoolsPath = $BcftoolsPath
        BgzipPath = $BgzipPath
        TabixPath = $TabixPath
    }

    $scenario = Copy-Parameters $base
    $scenario.OutputDirectory = Join-Path $workspace 'valid-output'
    Assert-Result -Name 'valid fixture' -ExpectedStatus 'PASSED' -ExpectedCode 'DERIVED_RESOURCE_VALID' -Parameters $scenario
    $validReport = Get-Content -LiteralPath (Join-Path $scenario.OutputDirectory 'benchmark-resource-acceptance.json') -Raw | ConvertFrom-Json
    if ($validReport.genotypeStatistics.het -ne 1 -or $validReport.genotypeStatistics.homRefExcluded -ne 1 -or $validReport.genotypeStatistics.missingNoCallExcluded -ne 1 -or $validReport.derived.records -ne 1) {
        throw 'valid fixture did not preserve expected non-reference genotype statistics.'
    }
    foreach ($field in @('AF', 'AC', 'AN', 'NS')) {
        if ($validReport.infoAudit.removedCohortLevelFields -notcontains $field) {
            throw "valid fixture did not remove cohort INFO field $field."
        }
    }
    $validManifestPath = Join-Path $scenario.OutputDirectory 'derived-resource-manifest.json'
    $validManifestText = Get-Content -LiteralPath $validManifestPath -Raw
    $validManifest = $validManifestText | ConvertFrom-Json
    if (-not $validManifest.source.index.sha256 -or -not $validManifest.bed.sha256 -or -not $validManifest.derived.indexSha256) {
        throw 'valid fixture manifest omitted source index, BED, or derived index checksum provenance.'
    }
    if (-not $validManifest.derivationParameterFingerprint -or
        $validManifest.derivationContract.contractVersion -ne 'benchmark-single-sample-derivation-parameters-v1') {
        throw 'valid fixture manifest omitted the versioned derivation parameter fingerprint.'
    }
    $firstParameterFingerprint = $validManifest.derivationParameterFingerprint
    if ($validManifestText -match '[A-Za-z]:[\\/]') {
        throw 'valid fixture manifest exposed an absolute machine path.'
    }
    $derivedHeader = & $BcftoolsPath view -h (Join-Path $scenario.OutputDirectory 'NA12878.chr22.exome-like.vcf.gz')
    if (($derivedHeader -join "`n") -match '(?m)^##INFO=<ID=(AF|AC|AN|NS),') {
        throw 'derived fixture retained a cohort-level INFO definition.'
    }

    $scenario.ReuseExistingDerived = $true
    Assert-Result -Name 'idempotent reuse' -ExpectedStatus 'PASSED' -ExpectedCode 'SINGLE_SAMPLE_REUSED_IDEMPOTENT' -Parameters $scenario
    $reusedManifest = Get-Content -LiteralPath $validManifestPath -Raw | ConvertFrom-Json
    if ($reusedManifest.derivationParameterFingerprint -ne $firstParameterFingerprint) {
        throw 'idempotent reuse changed the derivation parameter fingerprint.'
    }

    $scenario = Copy-Parameters $base
    $scenario.ExpectedByteSize = $size + 1
    $scenario.OutputDirectory = Join-Path $workspace 'size-output'
    Assert-Result -Name 'size mismatch' -ExpectedStatus 'FAILED' -ExpectedCode 'SIZE_MISMATCH' -Parameters $scenario

    $scenario = Copy-Parameters $base
    $scenario.ExpectedMd5 = '0' * 32
    $scenario.OutputDirectory = Join-Path $workspace 'md5-output'
    Assert-Result -Name 'checksum mismatch' -ExpectedStatus 'FAILED' -ExpectedCode 'MD5_MISMATCH' -Parameters $scenario

    $scenario = Copy-Parameters $base
    $scenario.ExpectedSampleId = 'MISSING_SAMPLE'
    $scenario.OutputDirectory = Join-Path $workspace 'sample-output'
    Assert-Result -Name 'missing sample' -ExpectedStatus 'FAILED' -ExpectedCode 'EXPECTED_SAMPLE_MISSING' -Parameters $scenario

    $scenario = Copy-Parameters $base
    $scenario.ExpectedAssembly = 'GRCh37'
    $scenario.OutputDirectory = Join-Path $workspace 'assembly-output'
    Assert-Result -Name 'assembly mismatch' -ExpectedStatus 'FAILED' -ExpectedCode 'ASSEMBLY_MISMATCH' -Parameters $scenario

    $missingContigPlain = Join-Path $workspace 'missing-contig.vcf'
    $missingContigGz = "$missingContigPlain.gz"
    Get-Content -LiteralPath $fixture | Where-Object { $_ -notmatch '^##contig=<ID=chr22,' } | Set-Content -LiteralPath $missingContigPlain -Encoding ASCII
    & $BgzipPath -f $missingContigPlain
    & $TabixPath -f -p vcf $missingContigGz
    $scenario = Copy-Parameters $base
    $scenario.VcfPath = $missingContigGz
    $scenario.IndexPath = "$missingContigGz.tbi"
    $scenario.ExpectedIndexByteSize = (Get-Item -LiteralPath "$missingContigGz.tbi").Length
    $scenario.ExpectedIndexMd5 = (Get-FileHash -Algorithm MD5 -LiteralPath "$missingContigGz.tbi").Hash.ToLowerInvariant()
    $scenario.ExpectedByteSize = (Get-Item -LiteralPath $missingContigGz).Length
    $scenario.ExpectedMd5 = (Get-FileHash -Algorithm MD5 -LiteralPath $missingContigGz).Hash.ToLowerInvariant()
    $scenario.OutputDirectory = Join-Path $workspace 'contig-output'
    Assert-Result -Name 'contig mismatch' -ExpectedStatus 'FAILED' -ExpectedCode 'CONTIG_MISSING' -Parameters $scenario

    $otherPlain = Join-Path $workspace 'other.vcf'
    $otherGz = "$otherPlain.gz"
    Copy-Item -LiteralPath $fixture -Destination $otherPlain
    (Get-Content -LiteralPath $otherPlain) -replace 'chr22', 'chr1' | Set-Content -LiteralPath $otherPlain -Encoding ASCII
    & $BgzipPath -f $otherPlain
    & $TabixPath -f -p vcf $otherGz
    Copy-Item -LiteralPath "$otherGz.tbi" -Destination "$validGz.tbi" -Force
    $scenario = Copy-Parameters $base
    $scenario.ExpectedIndexByteSize = (Get-Item -LiteralPath "$validGz.tbi").Length
    $scenario.ExpectedIndexMd5 = (Get-FileHash -Algorithm MD5 -LiteralPath "$validGz.tbi").Hash.ToLowerInvariant()
    $scenario.OutputDirectory = Join-Path $workspace 'index-output'
    Assert-Result -Name 'index mismatch' -ExpectedStatus 'FAILED' -ExpectedCode 'INDEX_MISMATCH_OR_UNREADABLE' -Parameters $scenario

    & $TabixPath -f -p vcf $validGz
    foreach ($suffix in @('.part', '.crdownload', '.download')) {
        $partial = Join-Path $workspace ("valid.vcf.gz$suffix")
        Copy-Item -LiteralPath $validGz -Destination $partial
        $scenario = Copy-Parameters $base
        $scenario.VcfPath = $partial
        $scenario.IndexPath = "$partial.tbi"
        $scenario.OutputDirectory = Join-Path $workspace ("partial-output-" + $suffix.TrimStart('.'))
        Assert-Result -Name "incomplete filename $suffix" -ExpectedStatus 'FAILED' -ExpectedCode 'INCOMPLETE_DOWNLOAD_NAME' -Parameters $scenario
    }

    Write-Output 'Synthetic benchmark resource acceptance checks passed.'
}
finally {
    if (Test-Path -LiteralPath $workspace) {
        Remove-Item -LiteralPath $workspace -Recurse -Force
    }
}
