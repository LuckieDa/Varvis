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
$mergeScript = Join-Path $PSScriptRoot 'Merge-BenchmarkBackgroundResources.ps1'
$workspace = Join-Path ([IO.Path]::GetTempPath()) ('varvis-background-merge-' + [Guid]::NewGuid().ToString('N'))

function Assert-ThrowsCode {
    param([string]$Code, [scriptblock]$Action)
    try { & $Action } catch {
        if ($_.Exception.Message -notlike "$Code*") { throw "Expected $Code but received $($_.Exception.Message)" }
        return
    }
    throw "Expected $Code but operation succeeded."
}

function New-DerivedFixture {
    param([string]$Chromosome, [int]$Position, [string]$BedSha256)
    $root = Join-Path $workspace "resources\chr$($Chromosome.ToLowerInvariant())\derived\na12878_exome_v1"
    New-Item -ItemType Directory -Force -Path $root | Out-Null
    $plain = Join-Path $root 'fixture.vcf'
    $vcf = Join-Path $root "NA12878.$Chromosome.exome-like.vcf.gz"
    $text = @(
        '##fileformat=VCFv4.2', '##reference=GRCh38', "##contig=<ID=$Chromosome>",
        '##FORMAT=<ID=GT,Number=1,Type=String,Description="Genotype">',
        "#CHROM`tPOS`tID`tREF`tALT`tQUAL`tFILTER`tINFO`tFORMAT`tNA12878",
        "$Chromosome`t$Position`t.`tA`tC`t.`tPASS`t.`tGT`t0/1"
    ) -join "`n"
    [IO.File]::WriteAllText($plain, $text + "`n", [Text.UTF8Encoding]::new($false))
    & $BgzipPath -f $plain
    if ($LASTEXITCODE -ne 0) { throw 'Fixture bgzip failed.' }
    Move-Item -LiteralPath "$plain.gz" -Destination $vcf
    & $TabixPath -f -p vcf $vcf
    if ($LASTEXITCODE -ne 0) { throw 'Fixture tabix failed.' }
    $manifest = [ordered]@{
        schemaVersion = 'benchmark-single-sample-derived-v1'; status = 'COMPLETED'
        sampleId = 'NA12878'; contig = $Chromosome; assembly = 'GRCh38'
        bed = [ordered]@{ filename = 'synthetic.bed'; sha256 = $BedSha256 }
        derivationContract = [ordered]@{ contractVersion = 'benchmark-single-sample-derivation-parameters-v1' }
        derivationParameterFingerprint = ('f' * 64)
        source = [ordered]@{
            filename = "source.$Chromosome.vcf.gz"; bytes = 1; md5 = ('a' * 32); sha256 = ('b' * 64)
            index = [ordered]@{ filename = "source.$Chromosome.vcf.gz.tbi"; bytes = 1; md5 = ('c' * 32); sha256 = ('d' * 64) }
        }
        derived = [ordered]@{
            filename = (Split-Path -Leaf $vcf)
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $vcf).Hash.ToLowerInvariant()
            indexSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath "$vcf.tbi").Hash.ToLowerInvariant()
            records = 1; snv = 1; smallIndel = 0
        }
        genotypeStatistics = [ordered]@{ het = 1; homAlt = 0 }
    }
    [IO.File]::WriteAllText((Join-Path $root 'derived-resource-manifest.json'), ($manifest | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
}

try {
    foreach ($tool in @($BcftoolsPath, $BgzipPath, $TabixPath)) {
        if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Required test tool is unavailable: $tool" }
    }
    New-Item -ItemType Directory -Force -Path $workspace | Out-Null
    $bed = Join-Path $workspace 'synthetic.bed'
    [IO.File]::WriteAllText($bed, "1`t0`t1000`n2`t0`t1000`n", [Text.UTF8Encoding]::new($false))
    $bedSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $bed).Hash.ToLowerInvariant()
    New-DerivedFixture -Chromosome '1' -Position 101 -BedSha256 $bedSha256
    New-DerivedFixture -Chromosome '2' -Position 202 -BedSha256 $bedSha256
    $output = Join-Path $workspace 'merged'
    $result = (& $mergeScript -ResourceRoot (Join-Path $workspace 'resources') -OutputDirectory $output `
        -BedPath $bed -BcftoolsPath $BcftoolsPath -BgzipPath $BgzipPath -TabixPath $TabixPath -Chromosomes @('2','1')) | ConvertFrom-Json
    if ($result.status -ne 'COMPLETED' -or ($result.chromosomes -join ',') -ne '1,2' -or
        $result.derived.records -ne 2 -or $result.derived.snv -ne 2 -or $result.derived.het -ne 2 -or
        $result.derivationParameterFingerprint -ne ('f' * 64) -or ($result.inputs.chromosome -join ',') -ne '1,2') {
        throw 'Merged fixture did not preserve deterministic chromosome order and record totals.'
    }
    $reused = (& $mergeScript -ResourceRoot (Join-Path $workspace 'resources') -OutputDirectory $output `
        -BedPath $bed -BcftoolsPath $BcftoolsPath -BgzipPath $BgzipPath -TabixPath $TabixPath -Chromosomes @('1','2') -ReuseExisting) | ConvertFrom-Json
    if ($reused.derived.sha256 -ne $result.derived.sha256) { throw 'Idempotent merge changed output identity.' }
    Assert-ThrowsCode -Code 'MERGED_OUTPUT_EXISTS' -Action {
        & $mergeScript -ResourceRoot (Join-Path $workspace 'resources') -OutputDirectory $output `
            -BedPath $bed -BcftoolsPath $BcftoolsPath -BgzipPath $BgzipPath -TabixPath $TabixPath -Chromosomes @('1','2') | Out-Null
    }
    $manifest2 = Join-Path $workspace 'resources\chr2\derived\na12878_exome_v1\derived-resource-manifest.json'
    $changed = Get-Content -LiteralPath $manifest2 -Raw | ConvertFrom-Json
    $changed.bed.sha256 = 'b' * 64
    [IO.File]::WriteAllText($manifest2, ($changed | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    Assert-ThrowsCode -Code 'DERIVED_BED_MISMATCH' -Action {
        & $mergeScript -ResourceRoot (Join-Path $workspace 'resources') -OutputDirectory (Join-Path $workspace 'other') `
            -BedPath $bed -BcftoolsPath $BcftoolsPath -BgzipPath $BgzipPath -TabixPath $TabixPath -Chromosomes @('1','2') | Out-Null
    }
    $changed.bed.sha256 = $bedSha256
    $changed.derivationParameterFingerprint = 'e' * 64
    [IO.File]::WriteAllText($manifest2, ($changed | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    Assert-ThrowsCode -Code 'DERIVED_PARAMETER_FINGERPRINT_MISMATCH' -Action {
        & $mergeScript -ResourceRoot (Join-Path $workspace 'resources') -OutputDirectory (Join-Path $workspace 'fingerprint') `
            -BedPath $bed -BcftoolsPath $BcftoolsPath -BgzipPath $BgzipPath -TabixPath $TabixPath -Chromosomes @('1','2') | Out-Null
    }
    Write-Output 'Benchmark background merge checks passed.'
} finally {
    if (Test-Path -LiteralPath $workspace) { Remove-Item -LiteralPath $workspace -Recurse -Force }
}
