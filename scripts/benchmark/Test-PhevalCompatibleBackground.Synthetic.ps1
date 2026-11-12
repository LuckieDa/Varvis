$ErrorActionPreference = "Stop"
$root = Join-Path ([System.IO.Path]::GetTempPath()) ("varvis-pheval-background-" + [guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $bcftools = if ($env:VARVIS_BCFTOOLS) { $env:VARVIS_BCFTOOLS } else { throw "VARVIS_BCFTOOLS is required" }
    $tabix = if ($env:VARVIS_TABIX) { $env:VARVIS_TABIX } else { throw "VARVIS_TABIX is required" }
    $plain = Join-Path $root "input.vcf"
    $input = "$plain.gz"
    $lines = @("##fileformat=VCFv4.2", "##FORMAT=<ID=GT,Number=1,Type=String,Description=`"Genotype`">")
    foreach ($contig in @((1..22 | ForEach-Object { [string]$_ }) + "X")) { $lines += "##contig=<ID=$contig>" }
    $lines += "#CHROM`tPOS`tID`tREF`tALT`tQUAL`tFILTER`tINFO`tFORMAT`tNA12878"
    $lines += "1`t10`t.`tA`tG`t.`tPASS`t.`tGT`t0/1"
    [System.IO.File]::WriteAllLines($plain, $lines, (New-Object System.Text.UTF8Encoding($false)))
    & $bcftools view -Oz -o $input $plain
    if ($LASTEXITCODE -ne 0) { throw "synthetic bgzf failed" }
    & $tabix -f -p vcf $input
    if ($LASTEXITCODE -ne 0) { throw "synthetic index failed" }
    $fai = Join-Path $root "reference.fai"
    $faiLines = @((1..22 | ForEach-Object { "chr$_`t1000`t0`t80`t81" }) + "chrX`t1000`t0`t80`t81")
    [System.IO.File]::WriteAllLines($fai, $faiLines, (New-Object System.Text.UTF8Encoding($false)))
    $outputDirectory = Join-Path $root "output"
    $script = Join-Path $PSScriptRoot "New-PhevalCompatibleBackground.ps1"
    & $script -InputVcf $input -ReferenceFai $fai -OutputDirectory $outputDirectory `
        -BcftoolsExecutable $bcftools -TabixExecutable $tabix | Out-Null
    $output = Join-Path $outputDirectory "NA12878.GRCh38.exome-like.pheval-compatible.vcf.gz"
    $header = @(& $bcftools view -h $output)
    if ($header -notcontains "##contig=<ID=chr1,length=1000>" -or
            $header -notcontains "##contig=<ID=chrX,length=1000>") { throw "prefix/length injection failed" }
    if ((& $bcftools index -n $output) -ne 1) { throw "record count changed" }
    $firstSha = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash
    & $script -InputVcf $input -ReferenceFai $fai -OutputDirectory $outputDirectory `
        -BcftoolsExecutable $bcftools -TabixExecutable $tabix | Out-Null
    if ((Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash -ne $firstSha) { throw "idempotency failed" }
    "PHEVAL_COMPATIBLE_BACKGROUND_SYNTHETIC_PASS"
} finally {
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}
