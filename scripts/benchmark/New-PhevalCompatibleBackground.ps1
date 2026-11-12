[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $InputVcf,
    [Parameter(Mandatory = $true)] [string] $ReferenceFai,
    [Parameter(Mandatory = $true)] [string] $OutputDirectory,
    [Parameter(Mandatory = $true)] [string] $BcftoolsExecutable,
    [Parameter(Mandatory = $true)] [string] $TabixExecutable
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
$ContractVersion = "pheval-compatible-background-v1"

function Require-File([string] $Path, [string] $Code) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Code`: $Path" }
    if ($Path -match '\.(part|crdownload|download)$') { throw "INCOMPLETE_RESOURCE_SUFFIX: $Path" }
}

function Get-Sha256([string] $Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-JsonAtomic([object] $Value, [string] $Path) {
    $temporary = "$Path.tmp"
    [System.IO.File]::WriteAllText($temporary,
        ($Value | ConvertTo-Json -Depth 10) + [Environment]::NewLine,
        (New-Object System.Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

foreach ($path in @($InputVcf, "$InputVcf.tbi", $ReferenceFai, $BcftoolsExecutable, $TabixExecutable)) {
    Require-File $path "RESOURCE_MISSING"
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$output = Join-Path $OutputDirectory "NA12878.GRCh38.exome-like.pheval-compatible.vcf.gz"
$manifestPath = Join-Path $OutputDirectory "pheval-compatible-background-manifest.json"
$sourceSha = Get-Sha256 $InputVcf
$faiSha = Get-Sha256 $ReferenceFai

if (Test-Path -LiteralPath $output) {
    Require-File "$output.tbi" "EXISTING_COMPATIBLE_INDEX_MISSING"
    Require-File $manifestPath "EXISTING_COMPATIBLE_MANIFEST_MISSING"
    $existing = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($existing.contractVersion -ne $ContractVersion -or $existing.sourceSha256 -ne $sourceSha -or
            $existing.referenceFaiSha256 -ne $faiSha -or $existing.outputSha256 -ne (Get-Sha256 $output) -or
            $existing.indexSha256 -ne (Get-Sha256 "$output.tbi")) {
        throw "EXISTING_COMPATIBLE_BACKGROUND_CONFLICT"
    }
    $existing | ConvertTo-Json -Depth 10
    exit 0
}

$lengths = @{}
foreach ($line in Get-Content -LiteralPath $ReferenceFai) {
    $columns = $line -split "`t"
    if ($columns.Count -lt 2 -or $columns[1] -notmatch '^\d+$') { continue }
    $contig = $columns[0] -replace '^(?i)chr', ''
    if ($contig -eq "M") { $contig = "MT" }
    $lengths[$contig.ToUpperInvariant()] = [long]$columns[1]
}

$renamePath = Join-Path $OutputDirectory "rename-to-chr.tmp.tsv"
$renamedVcf = Join-Path $OutputDirectory "renamed.tmp.vcf.gz"
$inputHeader = @(& $BcftoolsExecutable view -h $InputVcf)
if ($LASTEXITCODE -ne 0 -or $inputHeader.Count -eq 0) { throw "INPUT_HEADER_UNREADABLE" }
$inputContigs = @($inputHeader | ForEach-Object {
    if ($_ -match '^##contig=<ID=([^,>]+)') { $Matches[1] }
} | Where-Object { $_ })
if ($inputContigs.Count -lt 23) { throw "INPUT_CONTIG_SCOPE_INCOMPLETE" }
$renameLines = @()
foreach ($contig in $inputContigs) {
    $normalized = $contig -replace '^(?i)chr', ''
    $renameLines += "$contig`tchr$normalized"
}
[System.IO.File]::WriteAllLines($renamePath, $renameLines, (New-Object System.Text.UTF8Encoding($false)))
& $BcftoolsExecutable annotate --rename-chrs $renamePath -Oz -o $renamedVcf $InputVcf
if ($LASTEXITCODE -ne 0) { throw "CONTIG_RENAME_FAILED" }

$header = @(& $BcftoolsExecutable view -h $renamedVcf)
if ($LASTEXITCODE -ne 0 -or $header.Count -eq 0) { throw "RENAMED_HEADER_UNREADABLE" }
$contigCount = 0
for ($i = 0; $i -lt $header.Count; $i++) {
    if ($header[$i] -match '^##contig=<ID=([^,>]+)(?:,[^>]*)?>$') {
        $contig = $Matches[1]
        $key = ($contig -replace '^(?i)chr', '').ToUpperInvariant()
        if (-not $lengths.ContainsKey($key)) { throw "REFERENCE_CONTIG_LENGTH_MISSING: $contig" }
        $header[$i] = "##contig=<ID=$contig,length=$($lengths[$key])>"
        $contigCount++
    }
}
if ($contigCount -lt 23) { throw "INPUT_CONTIG_SCOPE_INCOMPLETE" }

$headerPath = Join-Path $OutputDirectory "header.tmp.txt"
$temporary = "$output.tmp.gz"
try {
    [System.IO.File]::WriteAllLines($headerPath, $header, (New-Object System.Text.UTF8Encoding($false)))
    & $BcftoolsExecutable reheader -h $headerPath -o $temporary $renamedVcf
    if ($LASTEXITCODE -ne 0) { throw "BCFTOOLS_REHEADER_FAILED" }
    & $TabixExecutable -f -p vcf $temporary
    if ($LASTEXITCODE -ne 0) { throw "TABIX_INDEX_FAILED" }
    & $TabixExecutable -l $temporary | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "TABIX_INDEX_INVALID" }
    $sourceRecords = [long]((& $BcftoolsExecutable view -H $InputVcf | Measure-Object).Count)
    if ($LASTEXITCODE -ne 0) { throw "SOURCE_RECORD_COUNT_FAILED" }
    $outputRecords = [long]((& $BcftoolsExecutable view -H $temporary | Measure-Object).Count)
    if ($LASTEXITCODE -ne 0 -or $sourceRecords -ne $outputRecords) { throw "RECORD_COUNT_MISMATCH" }
    Move-Item -LiteralPath "$temporary.tbi" -Destination "$output.tbi" -Force
    Move-Item -LiteralPath $temporary -Destination $output -Force
    $manifest = [ordered]@{
        contractVersion = $ContractVersion
        status = "COMPLETED"
        sourceLogicalId = "na12878-grch38-exome-like-background-v1"
        sourceSha256 = $sourceSha
        referenceFaiSha256 = $faiSha
        outputFileName = Split-Path -Leaf $output
        outputSha256 = Get-Sha256 $output
        indexFileName = Split-Path -Leaf "$output.tbi"
        indexSha256 = Get-Sha256 "$output.tbi"
        records = $outputRecords
        contigsWithLength = $contigCount
        transformation = "CHR_PREFIX_AND_HEADER_CONTIG_LENGTH_INJECTION_ONLY"
    }
    Write-JsonAtomic $manifest $manifestPath
    $manifest | ConvertTo-Json -Depth 10
} finally {
    Remove-Item -LiteralPath $headerPath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $renamePath -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $renamedVcf -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath "$temporary.tbi" -Force -ErrorAction SilentlyContinue
}
