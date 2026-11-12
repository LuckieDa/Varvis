[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ResourceRoot,

    [Parameter(Mandatory = $true)]
    [string]$BedPath,

    [Parameter(Mandatory = $true)]
    [string]$CurlPath,

    [string]$CaBundlePath = '',

    [Parameter(Mandatory = $true)]
    [string]$BcftoolsPath,

    [Parameter(Mandatory = $true)]
    [string]$BgzipPath,

    [Parameter(Mandatory = $true)]
    [string]$TabixPath,

    [string[]]$Chromosomes = @('1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19','20','21','X'),

    [int]$MaximumAttempts = 3,

    [int]$RetryDelaySeconds = 15,
    [ValidateRange(1, 16)]
    [int]$SegmentCount = 1
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$manifestPath = Join-Path $PSScriptRoot 'resources\1000-genomes-grch38-20190312-biallelic.manifest.json'
$downloadScript = Join-Path $PSScriptRoot 'Download-BenchmarkBackgroundResources.ps1'
$acceptanceScript = Join-Path $PSScriptRoot 'Test-BenchmarkBackgroundResource.ps1'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$allowedOrder = @('1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19','20','21','X')

if (@($Chromosomes | Group-Object | Where-Object Count -gt 1).Count -gt 0) {
    throw 'DUPLICATE_CHROMOSOME: Acquisition chromosomes must be unique.'
}
foreach ($chromosome in $Chromosomes) {
    if ($allowedOrder -notcontains $chromosome) {
        throw "UNAPPROVED_CHROMOSOME: $chromosome"
    }
}
$orderedChromosomes = @($allowedOrder | Where-Object { $Chromosomes -contains $_ })
$completed = New-Object System.Collections.Generic.List[object]

foreach ($chromosome in $orderedChromosomes) {
    $record = @($manifest.resources | Where-Object chromosome -eq $chromosome)
    if ($record.Count -ne 1) {
        throw "MANIFEST_RESOURCE_AMBIGUOUS: chr$chromosome"
    }
    $record = $record[0]
    $chromosomeRoot = Join-Path $ResourceRoot ("chr" + $chromosome.ToLowerInvariant())
    $derivedRoot = Join-Path $chromosomeRoot 'derived\na12878_exome_v1'
    $vcfPath = Join-Path $chromosomeRoot $record.vcf.filename
    $indexPath = Join-Path $chromosomeRoot $record.index.filename

    & $downloadScript -ManifestPath $manifestPath -ResourceRoot $ResourceRoot `
        -Chromosomes $chromosome -CurlPath $CurlPath -CaBundlePath $CaBundlePath -MaximumAttempts $MaximumAttempts `
        -RetryDelaySeconds $RetryDelaySeconds -SegmentCount $SegmentCount | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "DOWNLOAD_FAILED: chr$chromosome"
    }

    $arguments = @(
        '-NoProfile', '-File', $acceptanceScript,
        '-VcfPath', $vcfPath,
        '-IndexPath', $indexPath,
        '-ExpectedIndexByteSize', ([string]$record.index.bytes),
        '-ExpectedIndexMd5', $record.index.md5,
        '-ExpectedByteSize', ([string]$record.vcf.bytes),
        '-ExpectedMd5', $record.vcf.md5,
        '-ExpectedSampleId', $manifest.sampleId,
        '-ExpectedAssembly', $manifest.assembly,
        '-ExpectedContig', $chromosome,
        '-OutputDirectory', $derivedRoot,
        '-TinyRegion', "${chromosome}:1-1000000",
        '-BedPath', $BedPath,
        '-BcftoolsPath', $BcftoolsPath,
        '-BgzipPath', $BgzipPath,
        '-TabixPath', $TabixPath
    )
    if (Test-Path -LiteralPath (Join-Path $derivedRoot 'derived-resource-manifest.json')) {
        $arguments += '-ReuseExistingDerived'
    }
    $output = & powershell.exe @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "RESOURCE_ACCEPTANCE_FAILED: chr$chromosome"
    }
    $acceptance = $output -join [Environment]::NewLine | ConvertFrom-Json
    if ($acceptance.status -ne 'PASSED') {
        throw "RESOURCE_ACCEPTANCE_FAILED: chr$chromosome"
    }
    $completed.Add([ordered]@{
            chromosome = $chromosome
            status = 'COMPLETED'
            sourceSha256 = $acceptance.source.sha256
            sourceRecords = $acceptance.source.records
            derivedSha256 = $acceptance.derived.sha256
            derivedRecords = $acceptance.derived.records
            snv = $acceptance.derived.snv
            smallIndel = $acceptance.derived.smallIndel
        })

    $progress = [ordered]@{
        schemaVersion = 'benchmark-background-acquisition-progress-v1'
        status = if ($completed.Count -eq $orderedChromosomes.Count) { 'COMPLETED' } else { 'RUNNING' }
        requestedChromosomes = $orderedChromosomes
        completed = @($completed.ToArray())
    }
    $progressPath = Join-Path $ResourceRoot 'acquisition-progress.json'
    $temporary = "$progressPath.temporary-$([Guid]::NewGuid().ToString('N'))"
    [IO.File]::WriteAllText($temporary, ($progress | ConvertTo-Json -Depth 8) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $progressPath -Force
}

[ordered]@{
    schemaVersion = 'benchmark-background-acquisition-result-v1'
    status = 'COMPLETED'
    chromosomes = @($completed.ToArray())
} | ConvertTo-Json -Depth 8
