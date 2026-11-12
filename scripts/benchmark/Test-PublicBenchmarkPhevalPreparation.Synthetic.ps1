[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $ToolRoot,
    [string] $RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$root = Join-Path ([System.IO.Path]::GetTempPath()) ("varvis-pheval-e2e-" + [guid]::NewGuid().ToString("N"))

function Invoke-Checked([string] $Executable, [string[]] $Arguments, [string] $Code) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Code (exit=$LASTEXITCODE)" }
}

try {
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $bcftools = Join-Path $ToolRoot "msys64/ucrt64/bin/bcftools.exe"
    $tabix = Join-Path $ToolRoot "msys64/ucrt64/bin/tabix.exe"
    $pheval = Join-Path $ToolRoot "benchmark/pheval/0.7.14/venv/Scripts/pheval-utils.exe"
    $referenceFai = Join-Path $ToolRoot "reference/grch38.p14.primary-chr.fa.fai"
    foreach ($path in @($bcftools, $tabix, $pheval, $referenceFai)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "RESOURCE_MISSING: $path" }
    }

    $lengths = @{}
    foreach ($line in Get-Content -LiteralPath $referenceFai) {
        $columns = $line -split "`t"
        if ($columns.Count -ge 2) { $lengths[($columns[0] -replace '^chr', '')] = $columns[1] }
    }
    $plain = Join-Path $root "background.vcf"
    $background = "$plain.gz"
    $vcf = @(
        "##fileformat=VCFv4.2",
        "##FORMAT=<ID=GT,Number=1,Type=String,Description=`"Genotype`">"
    )
    foreach ($contig in @((1..22 | ForEach-Object { [string]$_ }) + "X")) {
        $vcf += "##contig=<ID=$contig>"
    }
    $vcf += "#CHROM`tPOS`tID`tREF`tALT`tQUAL`tFILTER`tINFO`tFORMAT`tNA12878"
    foreach ($contig in @((1..22 | ForEach-Object { [string]$_ }) + "X")) {
        $vcf += "$contig`t10`t.`tA`tC`t.`tPASS`t.`tGT`t0/1"
    }
    [System.IO.File]::WriteAllLines($plain, $vcf, (New-Object System.Text.UTF8Encoding($false)))
    Invoke-Checked $bcftools @("view", "-Oz", "-o", $background, $plain) "SYNTHETIC_BACKGROUND_BGZF_FAILED"
    Invoke-Checked $tabix @("-f", "-p", "vcf", $background) "SYNTHETIC_BACKGROUND_INDEX_FAILED"

    $compatibilityDirectory = Join-Path $root "compatible"
    & (Join-Path $PSScriptRoot "New-PhevalCompatibleBackground.ps1") -InputVcf $background `
        -ReferenceFai $referenceFai -OutputDirectory $compatibilityDirectory `
        -BcftoolsExecutable $bcftools -TabixExecutable $tabix | Out-Null
    $template = Join-Path $compatibilityDirectory "NA12878.GRCh38.exome-like.pheval-compatible.vcf.gz"

    $fixtures = @(
        [ordered]@{
            name = "alpha"
            path = "varvis-server/src/test/resources/benchmark/pheval/phenopacket-case-alpha.pheval-0.7.14.synthetic.json"
            truths = @("1`t100`tA`tG")
        },
        [ordered]@{
            name = "beta"
            path = "varvis-server/src/test/resources/benchmark/pheval/phenopacket-case-beta.pheval-0.7.14.synthetic.json"
            truths = @("2`t200`tAT`tA", "3`t300`tC`tT")
        }
    )
    foreach ($fixture in $fixtures) {
        $source = Join-Path $RepositoryRoot $fixture.path
        $sourceSha = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
        $caseDirectory = Join-Path $root $fixture.name
        New-Item -ItemType Directory -Path $caseDirectory -Force | Out-Null
        $working = Join-Path $caseDirectory (Split-Path -Leaf $source)
        Copy-Item -LiteralPath $source -Destination $working
        Invoke-Checked $pheval @("create-spiked-vcfs", "--phenopacket-path", $working,
            "--hg38-template-vcf", $template, "--output-dir", $caseDirectory) "SYNTHETIC_PHEVAL_FAILED"
        if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $sourceSha) {
            throw "SOURCE_FIXTURE_MODIFIED"
        }
        $raw = Join-Path $caseDirectory ((Split-Path -Leaf $working) -replace '\.json$', '.vcf.gz')
        $normalized = Join-Path $caseDirectory "normalized.vcf.gz"
        & (Join-Path $PSScriptRoot "Convert-PhevalSpikedVcf.ps1") -InputVcf $raw `
            -OutputVcf $normalized -BcftoolsExecutable $bcftools -TabixExecutable $tabix | Out-Null
        $samples = @(& $bcftools query -l $normalized)
        if ($samples.Count -ne 1 -or $samples[0] -ne "SYNTHETIC_SAMPLE") { throw "SYNTHETIC_SAMPLE_MISMATCH" }
        $contigs = @(& $tabix -l $normalized)
        if ($contigs -contains "chr1" -or $contigs -notcontains "1" -or $contigs -notcontains "X") {
            throw "SYNTHETIC_CONTIG_NORMALIZATION_FAILED"
        }
        foreach ($truth in $fixture.truths) {
            $parts = $truth -split "`t"
            $rows = @(& $bcftools query -r "$($parts[0]):$($parts[1])-$($parts[1])" -f '%CHROM\t%POS\t%REF\t%ALT\n' $normalized)
            if ($rows -notcontains $truth) { throw "SYNTHETIC_TRUTH_MISSING: $truth" }
        }
        $firstOutputSha = (Get-FileHash -LiteralPath $normalized -Algorithm SHA256).Hash
        & (Join-Path $PSScriptRoot "Convert-PhevalSpikedVcf.ps1") -InputVcf $raw `
            -OutputVcf $normalized -BcftoolsExecutable $bcftools -TabixExecutable $tabix | Out-Null
        if ((Get-FileHash -LiteralPath $normalized -Algorithm SHA256).Hash -ne $firstOutputSha) {
            throw "SYNTHETIC_NORMALIZATION_NOT_IDEMPOTENT"
        }
    }
    "PUBLIC_BENCHMARK_PHEVAL_PREPARATION_SYNTHETIC_PASS"
} finally {
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
