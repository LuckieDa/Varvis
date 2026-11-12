[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$downloader = Join-Path $PSScriptRoot 'Download-BenchmarkBackgroundResources.ps1'
$workspace = Join-Path ([System.IO.Path]::GetTempPath()) ('varvis-background-download-' + [Guid]::NewGuid().ToString('N'))

function Get-Md5 {
    param([string]$Path)
    return (Get-FileHash -Algorithm MD5 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Assert-ThrowsCode {
    param([string]$Code, [scriptblock]$Action)
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -notlike "$Code*") {
            throw "Expected $Code but received: $($_.Exception.Message)"
        }
        return
    }
    throw "Expected $Code but the operation succeeded."
}

try {
    $source = Join-Path $workspace 'source'
    $target = Join-Path $workspace 'target'
    New-Item -ItemType Directory -Force -Path $source, $target | Out-Null
    $vcfName = 'synthetic.chr1.vcf.gz'
    $indexName = 'synthetic.chr1.vcf.gz.tbi'
    [IO.File]::WriteAllText((Join-Path $source $vcfName), 'synthetic-vcf-content', [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $source $indexName), 'synthetic-index', [Text.UTF8Encoding]::new($false))
    $vcf = Get-Item -LiteralPath (Join-Path $source $vcfName)
    $index = Get-Item -LiteralPath (Join-Path $source $indexName)

    $manifestPath = Join-Path $workspace 'manifest.json'
    $manifest = [ordered]@{
        schemaVersion = 'benchmark-background-source-manifest-v1'
        resourceId = 'synthetic-background'
        assembly = 'GRCh38'
        sampleId = 'NA12878'
        officialBaseUri = 'https://official.example/'
        resources = @(
            [ordered]@{
                chromosome = '1'
                vcf = [ordered]@{ filename = $vcfName; bytes = $vcf.Length; md5 = Get-Md5 $vcf.FullName }
                index = [ordered]@{ filename = $indexName; bytes = $index.Length; md5 = Get-Md5 $index.FullName }
            },
            [ordered]@{
                chromosome = '22'
                vcf = [ordered]@{ filename = 'accepted.chr22.vcf.gz'; bytes = 1; md5 = '00000000000000000000000000000000' }
                index = [ordered]@{ filename = 'accepted.chr22.vcf.gz.tbi'; bytes = 1; md5 = '00000000000000000000000000000000' }
            }
        )
    }
    [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))

    $fakeCurl = Join-Path $workspace 'fake-curl.ps1'
    $fakeCurlBody = @'
param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Tokens)
$countPath = Join-Path $env:VARVIS_FAKE_CURL_SOURCE 'attempts.txt'
$count = if (Test-Path $countPath) { [int](Get-Content $countPath -Raw) } else { 0 }
[IO.File]::WriteAllText($countPath, ($count + 1).ToString())
if ($env:VARVIS_FAKE_CURL_MODE -eq 'FAIL') { exit 7 }
$outputIndex = [Array]::IndexOf($Tokens, '--output')
$destination = $Tokens[$outputIndex + 1]
$uri = $Tokens[-1]
$filename = [IO.Path]::GetFileName(([Uri]$uri).AbsolutePath)
Copy-Item -LiteralPath (Join-Path $env:VARVIS_FAKE_CURL_SOURCE $filename) -Destination $destination -Force
exit 0
'@
    [IO.File]::WriteAllText($fakeCurl, $fakeCurlBody, [Text.UTF8Encoding]::new($false))
    $env:VARVIS_FAKE_CURL_SOURCE = $source
    $env:VARVIS_FAKE_CURL_MODE = 'SUCCESS'

    $dry = (& $downloader -ManifestPath $manifestPath -ResourceRoot $target -Chromosomes '1' `
        -CurlPath $fakeCurl -MirrorBaseUri 'https://mirror.example/' -DryRun) | ConvertFrom-Json
    if ($dry.status -ne 'DRY_RUN' -or $dry.resources[0].status -ne 'PLANNED_NOT_EXECUTED' -or
        $dry.resources[0].vcf.uri -ne "https://mirror.example/$vcfName") {
        throw 'Dry-run did not preserve deterministic resource metadata.'
    }

    New-Item -ItemType Directory -Force -Path (Join-Path $target 'chr1') | Out-Null
    Copy-Item -LiteralPath $vcf.FullName -Destination (Join-Path $target "chr1\$vcfName")
    Copy-Item -LiteralPath $index.FullName -Destination (Join-Path $target "chr1\$indexName")
    $skip = (& $downloader -ManifestPath $manifestPath -ResourceRoot $target -Chromosomes '1' `
        -CurlPath $fakeCurl -MirrorBaseUri 'https://mirror.example/' -MaximumAttempts 1) | ConvertFrom-Json
    if ($skip.resources[0].vcf.result.status -ne 'SKIPPED_ACCEPTED' -or
        $skip.resources[0].index.result.status -ne 'SKIPPED_ACCEPTED') {
        throw 'Accepted resources were not skipped idempotently.'
    }

    Remove-Item -LiteralPath (Join-Path $target "chr1\$vcfName")
    $partialPath = Join-Path $target "chr1\$vcfName.part"
    [IO.File]::WriteAllText($partialPath, 'partial', [Text.UTF8Encoding]::new($false))
    $resume = (& $downloader -ManifestPath $manifestPath -ResourceRoot $target -Chromosomes '1' `
        -CurlPath $fakeCurl -MirrorBaseUri 'https://mirror.example/' -MaximumAttempts 1) | ConvertFrom-Json
    if ($resume.resources[0].vcf.result.status -ne 'DOWNLOADED_AND_ACCEPTED' -or
        (Test-Path -LiteralPath $partialPath)) {
        throw 'Resumable .part input was not accepted into the formal filename.'
    }

    [IO.File]::WriteAllText((Join-Path $target "chr1\$vcfName"), 'conflict', [Text.UTF8Encoding]::new($false))
    Assert-ThrowsCode -Code 'FORMAL_RESOURCE_CONFLICT' -Action {
        & $downloader -ManifestPath $manifestPath -ResourceRoot $target -Chromosomes '1' `
            -CurlPath $fakeCurl -MirrorBaseUri 'https://mirror.example/' -MaximumAttempts 1 | Out-Null
    }
    Remove-Item -LiteralPath (Join-Path $target "chr1\$vcfName")
    Remove-Item -LiteralPath (Join-Path $target "chr1\$indexName")

    $env:VARVIS_FAKE_CURL_MODE = 'FAIL'
    Set-Content -LiteralPath (Join-Path $source 'attempts.txt') -Value '0' -NoNewline
    Assert-ThrowsCode -Code 'DOWNLOAD_ATTEMPTS_EXHAUSTED' -Action {
        & $downloader -ManifestPath $manifestPath -ResourceRoot $target -Chromosomes '1' `
            -CurlPath $fakeCurl -MirrorBaseUri 'https://mirror.example/' -MaximumAttempts 2 `
            -RetryDelaySeconds 0 | Out-Null
    }
    if ([int](Get-Content -LiteralPath (Join-Path $source 'attempts.txt') -Raw) -ne 2) {
        throw 'Download retry policy was not bounded by MaximumAttempts.'
    }
    Assert-ThrowsCode -Code 'CHR22_REUSE_BOUNDARY_VIOLATION' -Action {
        & $downloader -ManifestPath $manifestPath -ResourceRoot $target -Chromosomes '22' `
            -CurlPath $fakeCurl -DryRun | Out-Null
    }

    Write-Output 'Benchmark background download checks passed.'
} finally {
    Remove-Item Env:VARVIS_FAKE_CURL_SOURCE -ErrorAction SilentlyContinue
    Remove-Item Env:VARVIS_FAKE_CURL_MODE -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $workspace) {
        Remove-Item -LiteralPath $workspace -Recurse -Force
    }
}
