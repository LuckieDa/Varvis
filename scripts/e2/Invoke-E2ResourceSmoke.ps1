[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ResourceRoot,

    [string]$RuntimeProperties
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
if ([string]::IsNullOrWhiteSpace($RuntimeProperties)) {
    $RuntimeProperties = Join-Path $repoRoot 'varvis-server\config\variant-analysis-local.properties'
}

$toolRoot = Join-Path ([IO.Path]::GetFullPath($ResourceRoot)) 'Tool'
$tabix = Join-Path $toolRoot 'msys64\ucrt64\bin\tabix.exe'
$wrapper = Join-Path $toolRoot 'vep\bin\varvis-vep.cmd'
$gnomad = Join-Path $toolRoot 'gnomad\compact\gnomad.exomes.v4.1.max-af.chr1.tsv.gz'
$clinvar37 = Join-Path $toolRoot 'clinvar\grch37\clinvar.vcf.gz'
$clinvar38 = Join-Path $toolRoot 'clinvar\clinvar.vcf.gz'
$manifestPath = Join-Path $repoRoot 'docs\execution\E2_RESOURCE_IDENTITY_MANIFEST.json'

function Assert-File([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required E2 resource is missing: $([IO.Path]::GetFileName($Path))"
    }
}

function Assert-Directory([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "Required E2 resource directory is missing: $([IO.Path]::GetFileName($Path))"
    }
}

function Assert-Equal($Actual, $Expected, [string]$Label) {
    if ([string]$Actual -ne [string]$Expected) {
        throw "E2 lightweight resource metadata drifted: $Label"
    }
}

function Assert-LightweightSha256([string]$Path, [string]$Expected, [string]$Label) {
    Assert-File $Path
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Equal $actual $Expected $Label
    return $actual
}

function Get-TabixHeaders([string]$File) {
    $savedErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $headers = @(& $tabix -H $File 2>$null)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $savedErrorAction
    }
    if ($exitCode -ne 0) {
        throw 'Tabix header query failed'
    }
    return $headers
}

function Assert-HeaderValue([string[]]$Headers, [string]$Name, [string]$Expected, [string]$Label) {
    $expectedLine = "##$Name=$Expected"
    if (@($Headers | Where-Object { $_ -eq $expectedLine }).Count -ne 1) {
        throw "E2 lightweight resource metadata drifted: $Label"
    }
}

function Invoke-Region([string]$File, [string]$Region) {
    $savedErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $rows = @(& $tabix $File $Region 2>$null)
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $savedErrorAction
    }
    if ($exitCode -ne 0) {
        throw "Tabix query failed for $Region"
    }
    return $rows
}

function Exact-AlleleCount(
    [string[]]$Rows,
    [int]$Position,
    [string]$Reference,
    [string]$Alternate,
    [int]$ReferenceColumn,
    [int]$AlternateColumn
) {
    return @($Rows | Where-Object {
        $columns = $_ -split "`t"
        $columns.Count -gt $AlternateColumn -and [int]$columns[1] -eq $Position -and
        $columns[$ReferenceColumn] -eq $Reference -and $columns[$AlternateColumn] -eq $Alternate
    }).Count
}

foreach ($file in @(
    $RuntimeProperties, $manifestPath, $tabix, $wrapper, $gnomad, "$gnomad.tbi",
    $clinvar37, "$clinvar37.tbi", $clinvar38, "$clinvar38.tbi"
)) {
    Assert-File $file
}
foreach ($cacheVersion in @('116_GRCh37', '116_GRCh38')) {
    Assert-Directory (Join-Path $toolRoot ("vep\cache\homo_sapiens\" + $cacheVersion))
}

$properties = Get-Content -LiteralPath $RuntimeProperties
foreach ($setting in @(
    'variant-analysis.clin-var.allow-vcf-info-fallback=false',
    'variant-analysis.clin-var.grch37-version=ClinVar 2026-07-06 GRCh37',
    'variant-analysis.clin-var.grch38-version=ClinVar 2026-03-21 GRCh38',
    'variant-analysis.gnomad.allow-vcf-info-fallback=false',
    'variant-analysis.gnomad.version=gnomAD exomes v4.1 GRCh38',
    'variant-analysis.vep.loftee-enabled=false',
    'variant-analysis.vep.version=116.0',
    'variant-analysis.vep.resources.GRCh37.version=116.0',
    'variant-analysis.vep.resources.GRCh37.ensembl-cache-version=116_GRCh37',
    'variant-analysis.vep.resources.GRCh38.version=116.0',
    'variant-analysis.vep.resources.GRCh38.ensembl-cache-version=116_GRCh38'
)) {
    if ($properties -notcontains $setting) {
        throw "Required E2 smoke setting is absent: $setting"
    }
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
function Get-ManifestResource([string]$ResourceId) {
    $matches = @($manifest.resources | Where-Object { $_.resourceId -eq $ResourceId })
    if ($matches.Count -ne 1) {
        throw "E2 resource identity is missing or duplicated: $ResourceId"
    }
    return $matches[0]
}
$resourceIds = @(
    'GNOMAD_EXOMES_V4_1_GRCH38_COMPACT',
    'CLINVAR_2026_07_06_GRCH37',
    'CLINVAR_2026_03_21_GRCH38',
    'VEP_116_GRCH37',
    'VEP_116_GRCH38'
)
$resourceIdentity = [ordered]@{}
foreach ($resourceId in $resourceIds) {
    $resource = Get-ManifestResource $resourceId
    $resourceIdentity[$resourceId] = $resource.identityFingerprintSha256
}

$gnomadResource = Get-ManifestResource 'GNOMAD_EXOMES_V4_1_GRCH38_COMPACT'
$gnomadDirectory = Split-Path -Parent $gnomad
$gnomadDataFiles = @(Get-ChildItem -LiteralPath $gnomadDirectory -File | Where-Object {
    $_.Name -match '^gnomad\.exomes\.v4\.1\.max-af\.chr(?:[1-9]|1[0-9]|2[0-2]|X|Y)\.tsv\.gz$'
})
$gnomadIndexFiles = @(Get-ChildItem -LiteralPath $gnomadDirectory -File | Where-Object {
    $_.Name -match '^gnomad\.exomes\.v4\.1\.max-af\.chr(?:[1-9]|1[0-9]|2[0-2]|X|Y)\.tsv\.gz\.tbi$'
})
$gnomadDataBytes = [long](($gnomadDataFiles | Measure-Object -Property Length -Sum).Sum)
$gnomadIndexBytes = [long](($gnomadIndexFiles | Measure-Object -Property Length -Sum).Sum)
Assert-Equal $gnomadDataFiles.Count ([int]$gnomadResource.identity.dataComponentCount) `
    'gnomAD data component count'
Assert-Equal $gnomadDataBytes ([long]$gnomadResource.identity.dataTotalBytes) `
    'gnomAD data total bytes'
Assert-Equal $gnomadIndexFiles.Count ([int]$gnomadResource.identity.indexComponentCount) `
    'gnomAD index component count'
Assert-Equal $gnomadIndexBytes ([long]$gnomadResource.identity.indexTotalBytes) `
    'gnomAD index total bytes'

$clinvarMetadata = [ordered]@{}
foreach ($profile in @(
    @{ ResourceId = 'CLINVAR_2026_07_06_GRCH37'; Assembly = 'GRCh37'; File = $clinvar37 },
    @{ ResourceId = 'CLINVAR_2026_03_21_GRCH38'; Assembly = 'GRCh38'; File = $clinvar38 }
)) {
    $resource = Get-ManifestResource $profile.ResourceId
    $dataItem = Get-Item -LiteralPath $profile.File
    $indexPath = $profile.File + '.tbi'
    $indexItem = Get-Item -LiteralPath $indexPath
    Assert-Equal ([long]$dataItem.Length) ([long]$resource.identity.dataByteLength) `
        "$($profile.Assembly) ClinVar data bytes"
    Assert-Equal ([long]$indexItem.Length) ([long]$resource.identity.indexByteLength) `
        "$($profile.Assembly) ClinVar index bytes"
    $indexSha256 = Assert-LightweightSha256 $indexPath $resource.identity.indexSha256 `
        "$($profile.Assembly) ClinVar TBI SHA-256"
    $headers = @(Get-TabixHeaders $profile.File)
    Assert-HeaderValue $headers 'fileDate' $resource.identity.fileDate `
        "$($profile.Assembly) ClinVar fileDate"
    Assert-HeaderValue $headers 'source' $resource.identity.headerSource `
        "$($profile.Assembly) ClinVar source"
    Assert-HeaderValue $headers 'reference' $resource.identity.headerReference `
        "$($profile.Assembly) ClinVar reference"
    $clinvarMetadata[$profile.Assembly] = [ordered]@{
        dataByteLength = [long]$dataItem.Length
        indexByteLength = [long]$indexItem.Length
        indexSha256 = $indexSha256
        fileDate = $resource.identity.fileDate
        source = $resource.identity.headerSource
        reference = $resource.identity.headerReference
    }
}

$wrapperSha256 = (Get-FileHash -LiteralPath $wrapper -Algorithm SHA256).Hash.ToLowerInvariant()
$vepProfiles = [ordered]@{}
$vepToolVersions = @()
foreach ($resourceId in @('VEP_116_GRCH37', 'VEP_116_GRCH38')) {
    $resource = Get-ManifestResource $resourceId
    if ($resource.identity.wrapperSha256 -ne $wrapperSha256) {
        throw "VEP wrapper identity drifted for $resourceId"
    }
    $assembly = $resource.assembly
    $cacheInfo = Join-Path $toolRoot (($resource.identity.cache.logicalName + '/info.txt').Replace('/', '\'))
    $fasta = Join-Path $toolRoot ($resource.identity.fasta.logicalName.Replace('/', '\'))
    $fastaIndex = Join-Path $toolRoot ($resource.identity.fasta.indexLogicalName.Replace('/', '\'))
    Assert-File $fasta
    Assert-Equal ([long](Get-Item -LiteralPath $fasta).Length) `
        ([long]$resource.identity.fasta.byteLength) "$assembly VEP FASTA bytes"
    $cacheInfoSha256 = Assert-LightweightSha256 $cacheInfo $resource.identity.cache.infoSha256 `
        "$assembly VEP cache info SHA-256"
    $fastaIndexSha256 = Assert-LightweightSha256 $fastaIndex $resource.identity.fasta.indexSha256 `
        "$assembly VEP FASTA index SHA-256"
    $indexedContigCount = @(Get-Content -LiteralPath $fastaIndex).Count
    Assert-Equal $indexedContigCount ([int]$resource.identity.fasta.indexedContigCount) `
        "$assembly VEP indexed contig count"
    $vepToolVersions += $resource.identity.tool.version
    $vepProfiles[$assembly] = [ordered]@{
        cacheInfoSha256 = $cacheInfoSha256
        fastaByteLength = [long](Get-Item -LiteralPath $fasta).Length
        fastaIndexSha256 = $fastaIndexSha256
        indexedContigCount = $indexedContigCount
    }
}
$vepToolVersions = @($vepToolVersions | Select-Object -Unique)
if ($vepToolVersions.Count -ne 1) {
    throw 'VEP manifest tool versions are inconsistent'
}
$expectedVepToolVersion = [string]$vepToolVersions[0]
$savedErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $vepHelp = @(& $wrapper --help 2>&1)
    $vepHelpExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $savedErrorAction
}
if ($vepHelpExitCode -ne 0) {
    throw 'VEP help/version check failed'
}
$vepVersionPattern = '(?m)^\s*ensembl-vep\s*:\s*' +
    [regex]::Escape($expectedVepToolVersion) + '\s*$'
if (($vepHelp -join "`n") -notmatch $vepVersionPattern) {
    throw 'VEP executable version drifted from the resource manifest'
}

$resourceMetadata = [ordered]@{
    status = 'PASS'
    verificationScope = 'LIGHTWEIGHT_METADATA_ONLY'
    byteIntegrityClaim = $false
    gnomad = [ordered]@{
        dataComponentCount = $gnomadDataFiles.Count
        dataTotalBytes = $gnomadDataBytes
        indexComponentCount = $gnomadIndexFiles.Count
        indexTotalBytes = $gnomadIndexBytes
    }
    clinvar = $clinvarMetadata
    vep = [ordered]@{
        toolVersion = $expectedVepToolVersion
        wrapperSha256 = $wrapperSha256
        GRCh37 = $vepProfiles['GRCh37']
        GRCh38 = $vepProfiles['GRCh38']
    }
}

$gnomadRows = Invoke-Region $gnomad 'chr1:12138-12138'
$clinvar37Rows = Invoke-Region $clinvar37 '1:69134-69134'
$clinvar38Rows = Invoke-Region $clinvar38 '1:69134-69134'

$olderIndexes = @()
foreach ($pair in @(
    @{ Data = $gnomad; LogicalName = 'gnomad/compact/gnomad.exomes.v4.1.max-af.chr1.tsv.gz.tbi' },
    @{ Data = $clinvar37; LogicalName = 'clinvar/grch37/clinvar.vcf.gz.tbi' },
    @{ Data = $clinvar38; LogicalName = 'clinvar/clinvar.vcf.gz.tbi' }
)) {
    if ((Get-Item -LiteralPath ($pair.Data + '.tbi')).LastWriteTimeUtc -lt
        (Get-Item -LiteralPath $pair.Data).LastWriteTimeUtc) {
        $olderIndexes += $pair.LogicalName
    }
}

if ((Exact-AlleleCount $gnomadRows 12138 'C' 'A' 2 3) -ne 1 -or
    (Exact-AlleleCount $gnomadRows 12138 'C' 'G' 2 3) -ne 0) {
    throw 'gnomAD fixed HIT/NO_RECORD alleles drifted'
}
foreach ($rows in @($clinvar37Rows, $clinvar38Rows)) {
    if ((Exact-AlleleCount $rows 69134 'A' 'G' 3 4) -ne 1 -or
        (Exact-AlleleCount $rows 69134 'A' 'C' 3 4) -ne 0) {
        throw 'ClinVar fixed HIT/NO_RECORD alleles drifted'
    }
}

$tempBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$tempRoot = Join-Path $tempBase ("varvis-e2-smoke-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
try {
    $vepResults = @{}
    foreach ($assembly in @('GRCh37', 'GRCh38')) {
        $input = Join-Path $PSScriptRoot ("fixtures\vep-" + $assembly.ToLowerInvariant() + "-smoke.vcf.txt")
        $output = Join-Path $tempRoot ("vep-" + $assembly.ToLowerInvariant() + ".json")
        $cache = Join-Path $toolRoot 'vep\cache'
        $fasta = Join-Path $toolRoot ("vep\Homo_sapiens." + $assembly + ".dna.primary_assembly.fa")
        Assert-File $input
        Assert-File $fasta
        Assert-File ($fasta + '.fai')
        $savedErrorAction = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $vepCache = $cache.Replace('\', '/')
            $vepFasta = $fasta.Replace('\', '/')
            $vepInput = $input.Replace('\', '/')
            $vepOutput = $output.Replace('\', '/')
            & $wrapper --offline --cache --assembly $assembly --species homo_sapiens `
                --dir_cache $vepCache --fasta $vepFasta --format vcf --json `
                --input_file $vepInput --output_file $vepOutput --force_overwrite --no_stats `
                --symbol --canonical --mane --hgvs --protein --biotype --numbers
            $exitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $savedErrorAction
        }
        if ($exitCode -ne 0 -or -not (Test-Path -LiteralPath $output -PathType Leaf)) {
            throw "VEP $assembly smoke failed"
        }
        $records = @(Get-Content -LiteralPath $output | ForEach-Object { $_ | ConvertFrom-Json })
        if ($records.Count -ne 1 -or $records[0].assembly_name -ne $assembly) {
            throw "VEP $assembly smoke output is invalid"
        }
        $vepResults[$assembly] = @{ recordCount = $records.Count; assembly = $records[0].assembly_name }
    }

    [ordered]@{
        status = 'PASS'
        vcfInfoFallback = 'DISABLED'
        loftee = 'DISABLED'
        gnomad = @{ assembly = 'GRCh38'; hit = 'chr1:12138:C:A'; noRecord = 'chr1:12138:C:G' }
        clinvar = @{
            GRCh37 = @{ version = '2026-07-06'; hit = '1:69134:A:G'; noRecord = '1:69134:A:C' }
            GRCh38 = @{ version = '2026-03-21'; hit = '1:69134:A:G'; noRecord = '1:69134:A:C' }
        }
        vep = $vepResults
        resourceIdentity = $resourceIdentity
        resourceMetadata = $resourceMetadata
        wrapperSha256 = $wrapperSha256
        indexMtime = @{
            status = if ($olderIndexes.Count -gt 0) { 'INDEX_OLDER_THAN_DATA_OBSERVED' } else { 'NO_OLDER_INDEX_OBSERVED' }
            logicalIndexes = $olderIndexes
            byteIntegrityClaim = $false
        }
    } | ConvertTo-Json -Depth 6
}
finally {
    $resolvedTemp = [IO.Path]::GetFullPath($tempRoot)
    if ($resolvedTemp.StartsWith($tempBase, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedTemp).StartsWith('varvis-e2-smoke-', [StringComparison]::Ordinal)) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
