[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $InputVcf,
    [Parameter(Mandatory = $true)] [string] $OutputVcf,
    [Parameter(Mandatory = $true)] [string] $BcftoolsExecutable,
    [Parameter(Mandatory = $true)] [string] $TabixExecutable
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$ContractVersion = "pheval-spiked-vcf-normalization-v1"

function Require-File([string] $Path, [string] $Code) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Code`: $Path" }
    if ($Path -match '\.(part|crdownload|download)$') { throw "INCOMPLETE_RESOURCE_SUFFIX: $Path" }
}

function Get-Sha256([string] $Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

foreach ($path in @($InputVcf, $BcftoolsExecutable, $TabixExecutable)) {
    Require-File $path "RESOURCE_MISSING"
}
$outputDirectory = Split-Path -Parent $OutputVcf
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$manifestPath = "$OutputVcf.normalization.json"
$inputSha = Get-Sha256 $InputVcf

if (Test-Path -LiteralPath $OutputVcf) {
    Require-File "$OutputVcf.tbi" "EXISTING_OUTPUT_INDEX_MISSING"
    Require-File $manifestPath "EXISTING_OUTPUT_MANIFEST_MISSING"
    $existing = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($existing.contractVersion -ne $ContractVersion -or $existing.inputSha256 -ne $inputSha -or
            $existing.outputSha256 -ne (Get-Sha256 $OutputVcf) -or
            $existing.indexSha256 -ne (Get-Sha256 "$OutputVcf.tbi")) {
        throw "EXISTING_NORMALIZED_OUTPUT_CONFLICT"
    }
    $existing | ConvertTo-Json -Depth 10
    exit 0
}

$mapping = Join-Path $outputDirectory "rename-from-chr.tmp.tsv"
$renamed = "$OutputVcf.renamed.tmp.gz"
$sorted = "$OutputVcf.sorted.tmp.gz"
try {
    $lines = @((1..22 | ForEach-Object { "chr$_`t$_" }) + "chrX`tX")
    [System.IO.File]::WriteAllLines($mapping, $lines, (New-Object System.Text.UTF8Encoding($false)))
    & $BcftoolsExecutable annotate --rename-chrs $mapping -Oz -o $renamed $InputVcf
    if ($LASTEXITCODE -ne 0) { throw "SPIKED_CONTIG_NORMALIZATION_FAILED" }
    & $BcftoolsExecutable sort -Oz -o $sorted $renamed
    if ($LASTEXITCODE -ne 0) { throw "SPIKED_SORT_FAILED" }
    & $TabixExecutable -f -p vcf $sorted
    if ($LASTEXITCODE -ne 0) { throw "SPIKED_INDEX_FAILED" }
    & $TabixExecutable -l $sorted | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "SPIKED_INDEX_INVALID" }
    $inputRecords = [long]((& $BcftoolsExecutable view -H $InputVcf | Measure-Object).Count)
    $outputRecords = [long]((& $BcftoolsExecutable view -H $sorted | Measure-Object).Count)
    if ($LASTEXITCODE -ne 0 -or $inputRecords -ne $outputRecords) { throw "SPIKED_RECORD_COUNT_MISMATCH" }
    Move-Item -LiteralPath "$sorted.tbi" -Destination "$OutputVcf.tbi" -Force
    Move-Item -LiteralPath $sorted -Destination $OutputVcf -Force
    $manifest = [ordered]@{
        contractVersion = $ContractVersion
        status = "COMPLETED"
        inputSha256 = $inputSha
        outputFileName = Split-Path -Leaf $OutputVcf
        outputSha256 = Get-Sha256 $OutputVcf
        indexFileName = Split-Path -Leaf "$OutputVcf.tbi"
        indexSha256 = Get-Sha256 "$OutputVcf.tbi"
        records = $outputRecords
        inputContigStyle = "CHR_PREFIXED"
        outputContigStyle = "NUMERIC_1_22_X"
    }
    $temporaryManifest = "$manifestPath.tmp"
    [System.IO.File]::WriteAllText($temporaryManifest,
        ($manifest | ConvertTo-Json -Depth 10) + [Environment]::NewLine,
        (New-Object System.Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporaryManifest -Destination $manifestPath -Force
    $manifest | ConvertTo-Json -Depth 10
} finally {
    Remove-Item -LiteralPath $mapping -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $renamed -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath "$renamed.tbi" -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $sorted -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath "$sorted.tbi" -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath "$manifestPath.tmp" -Force -ErrorAction SilentlyContinue
}
