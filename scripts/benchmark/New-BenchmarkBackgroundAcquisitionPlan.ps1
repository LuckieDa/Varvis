[CmdletBinding()]
param(
    [string]$ManifestPath = (Join-Path $PSScriptRoot 'resources\1000-genomes-grch38-20190312-biallelic.manifest.json'),

    [string[]]$Chromosomes = @(),

    [string[]]$AcceptedChromosomes = @(),

    [switch]$IncludeConditionalChrX,

    [string]$LogicalResourceRoot = 'benchmark/1000_genomes/grch38_20190312',

    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-SafeLogicalPath {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path) -or
        $Path -notmatch '^[a-z0-9._/-]+$' -or
        $Path.StartsWith('/') -or
        $Path.Contains('..') -or
        $Path.Contains('//')) {
        throw 'UNSAFE_LOGICAL_RESOURCE_ROOT: Use a lowercase ASCII relative path without traversal.'
    }
}

function Assert-ResourceRecord {
    param([object]$Record)

    if ([string]::IsNullOrWhiteSpace($Record.chromosome) -or
        $Record.vcf.bytes -le 0 -or $Record.index.bytes -le 0 -or
        $Record.vcf.md5 -notmatch '^[a-f0-9]{32}$' -or
        $Record.index.md5 -notmatch '^[a-f0-9]{32}$' -or
        $Record.vcf.filename -match '[\\/]' -or $Record.index.filename -match '[\\/]') {
        throw "INVALID_RESOURCE_RECORD: Chromosome $($Record.chromosome) has invalid metadata."
    }
}

function Get-Sha256 {
    param([string]$Text)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        return (($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join '')
    } finally {
        $sha.Dispose()
    }
}

if ($ManifestPath -match '(?i)\.(part|crdownload|download)$') {
    throw 'INCOMPLETE_MANIFEST: The source manifest filename indicates an incomplete download.'
}
if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
    throw 'MANIFEST_MISSING: The source manifest does not exist.'
}
Assert-SafeLogicalPath -Path $LogicalResourceRoot

$manifestText = Get-Content -LiteralPath $ManifestPath -Raw
$manifest = $manifestText | ConvertFrom-Json
if ($manifest.schemaVersion -ne 'benchmark-background-source-manifest-v1') {
    throw "UNSUPPORTED_SCHEMA_VERSION: $($manifest.schemaVersion)"
}
if ($manifest.assembly -ne 'GRCh38' -or $manifest.sampleId -ne 'NA12878') {
    throw 'MANIFEST_IDENTITY_MISMATCH: Expected the frozen GRCh38 NA12878 source manifest.'
}

$records = @($manifest.resources)
foreach ($record in $records) {
    Assert-ResourceRecord -Record $record
}
$duplicateResources = @($records | Group-Object chromosome | Where-Object Count -ne 1)
if ($duplicateResources.Count -gt 0) {
    throw 'DUPLICATE_RESOURCE_CHROMOSOME: Resource chromosomes must be unique.'
}

$required = @($manifest.requiredPrimaryChromosomes)
$expectedRequired = @(1..22 | ForEach-Object { $_.ToString() })
if (($required -join ',') -ne ($expectedRequired -join ',')) {
    throw 'INVALID_PRIMARY_SCOPE: The frozen primary scope must be chromosomes 1 through 22 in numeric order.'
}

$selected = if ($Chromosomes.Count -gt 0) { @($Chromosomes) } else { @($required) }
if ($IncludeConditionalChrX -and $selected -notcontains 'X') {
    $selected += 'X'
}
if (@($selected | Group-Object | Where-Object Count -gt 1).Count -gt 0) {
    throw 'DUPLICATE_SELECTED_CHROMOSOME: Selected chromosomes must be unique.'
}
$known = @($records.chromosome)
foreach ($chromosome in @($selected + $AcceptedChromosomes)) {
    if ($known -notcontains $chromosome) {
        throw "UNKNOWN_CHROMOSOME: $chromosome"
    }
}

$items = New-Object System.Collections.Generic.List[object]
[Int64]$pendingVcfBytes = 0
[Int64]$pendingIndexBytes = 0
foreach ($chromosome in $selected) {
    $record = @($records | Where-Object chromosome -eq $chromosome)
    if ($record.Count -ne 1) {
        throw "RESOURCE_LOOKUP_FAILED: $chromosome"
    }
    $record = $record[0]
    $accepted = $AcceptedChromosomes -contains $chromosome
    if (-not $accepted) {
        $pendingVcfBytes += [Int64]$record.vcf.bytes
        $pendingIndexBytes += [Int64]$record.index.bytes
    }
    $relativeRoot = "$LogicalResourceRoot/chr$($chromosome.ToLowerInvariant())"
    $items.Add([ordered]@{
            chromosome = $chromosome
            action = if ($accepted) { 'ALREADY_ACCEPTED' } else { 'PENDING_USER_AUTHORIZATION' }
            vcf = [ordered]@{
                filename = $record.vcf.filename
                relativePath = "$relativeRoot/$($record.vcf.filename)"
                bytes = [Int64]$record.vcf.bytes
                md5 = $record.vcf.md5
            }
            index = [ordered]@{
                filename = $record.index.filename
                relativePath = "$relativeRoot/$($record.index.filename)"
                bytes = [Int64]$record.index.bytes
                md5 = $record.index.md5
            }
        })
}

$plan = [ordered]@{
    schemaVersion = 'benchmark-background-acquisition-plan-v1'
    status = 'PLAN_ONLY_NOT_AUTHORIZED_FOR_DOWNLOAD'
    sourceManifest = [ordered]@{
        resourceId = $manifest.resourceId
        sha256 = Get-Sha256 -Text $manifestText
        officialManifest = $manifest.officialManifest
    }
    assembly = $manifest.assembly
    sampleId = $manifest.sampleId
    selectedChromosomes = @($selected)
    acceptedChromosomes = @($AcceptedChromosomes)
    pending = [ordered]@{
        chromosomeCount = @($selected | Where-Object { $AcceptedChromosomes -notcontains $_ }).Count
        vcfBytes = $pendingVcfBytes
        indexBytes = $pendingIndexBytes
        combinedBytes = $pendingVcfBytes + $pendingIndexBytes
    }
    chromosomePolicy = [ordered]@{
        requiredPrimary = @($required)
        conditional = @($manifest.conditionalChromosomes)
        unavailable = $manifest.unavailableChromosomes
    }
    resources = @($items.ToArray())
}
$planJsonWithoutFingerprint = $plan | ConvertTo-Json -Depth 10 -Compress
$plan.planFingerprintSha256 = Get-Sha256 -Text $planJsonWithoutFingerprint
$json = ($plan | ConvertTo-Json -Depth 10) + [Environment]::NewLine

if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $parent = Split-Path -Parent $OutputPath
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    if (Test-Path -LiteralPath $OutputPath -PathType Leaf) {
        $existing = Get-Content -LiteralPath $OutputPath -Raw
        if ($existing -ne $json) {
            throw 'OUTPUT_EXISTS_MISMATCH: Refusing to overwrite a different acquisition plan.'
        }
    } else {
        $temporary = "$OutputPath.temporary-$([Guid]::NewGuid().ToString('N'))"
        try {
            [System.IO.File]::WriteAllText($temporary, $json, [System.Text.UTF8Encoding]::new($false))
            Move-Item -LiteralPath $temporary -Destination $OutputPath
        } finally {
            Remove-Item -LiteralPath $temporary -Force -ErrorAction SilentlyContinue
        }
    }
}

$json
