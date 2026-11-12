[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ResourceRoot,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [Parameter(Mandatory = $true)]
    [string]$BedPath,

    [Parameter(Mandatory = $true)]
    [string]$BcftoolsPath,

    [Parameter(Mandatory = $true)]
    [string]$BgzipPath,

    [Parameter(Mandatory = $true)]
    [string]$TabixPath,

    [string[]]$Chromosomes = @('1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19','20','21','22','X'),

    [switch]$ReuseExisting
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$canonicalOrder = @('1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19','20','21','22','X')

function Invoke-Tool {
    param([string]$Executable, [string[]]$Arguments, [string]$Code)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "${Code}: $([IO.Path]::GetFileName($Executable)) failed."
    }
}

function Get-StatValue {
    param([string[]]$Lines, [string]$Label)
    foreach ($line in $Lines) {
        $fields = $line -split "`t"
        if ($fields.Count -ge 4 -and $fields[0] -eq 'SN' -and $fields[2] -eq $Label) {
            return [Int64]$fields[3]
        }
    }
    return $null
}

if (@($Chromosomes | Group-Object | Where-Object Count -gt 1).Count -gt 0) {
    throw 'DUPLICATE_CHROMOSOME: Merge chromosomes must be unique.'
}
foreach ($chromosome in $Chromosomes) {
    if ($canonicalOrder -notcontains $chromosome) {
        throw "UNAPPROVED_CHROMOSOME: $chromosome"
    }
}
$ordered = @($canonicalOrder | Where-Object { $Chromosomes -contains $_ })
$inputs = New-Object System.Collections.Generic.List[string]
$inputIdentity = New-Object System.Collections.Generic.List[object]
$bedSha256 = $null
$derivationParameterFingerprint = $null
$derivationContract = $null
[Int64]$expectedRecords = 0
[Int64]$expectedSnv = 0
[Int64]$expectedSmallIndel = 0
[Int64]$expectedHet = 0
[Int64]$expectedHomAlt = 0

if (-not (Test-Path -LiteralPath $BedPath -PathType Leaf)) {
    throw 'BED_MISSING: The accepted benchmark BED is unavailable.'
}
$resolvedBed = (Resolve-Path -LiteralPath $BedPath).Path
$acceptedBedSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedBed).Hash.ToLowerInvariant()

foreach ($chromosome in $ordered) {
    $derivedRoot = Join-Path (Join-Path $ResourceRoot ("chr" + $chromosome.ToLowerInvariant())) 'derived\na12878_exome_v1'
    $manifestPath = Join-Path $derivedRoot 'derived-resource-manifest.json'
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "DERIVED_MANIFEST_MISSING: chr$chromosome"
    }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($manifest.status -ne 'COMPLETED' -or $manifest.sampleId -ne 'NA12878' -or
        $manifest.assembly -ne 'GRCh38' -or $manifest.contig -ne $chromosome) {
        throw "DERIVED_IDENTITY_MISMATCH: chr$chromosome"
    }
    if ($null -eq $manifest.bed -or [string]::IsNullOrWhiteSpace($manifest.bed.sha256)) {
        throw "DERIVED_BED_PROVENANCE_MISSING: chr$chromosome"
    }
    if ($null -eq $bedSha256) { $bedSha256 = $manifest.bed.sha256 }
    if ($manifest.bed.sha256 -ne $bedSha256) {
        throw "DERIVED_BED_MISMATCH: chr$chromosome"
    }
    if ($manifest.bed.sha256 -ne $acceptedBedSha256) {
        throw "DERIVED_BED_FILE_MISMATCH: chr$chromosome"
    }
    if ([string]::IsNullOrWhiteSpace($manifest.derivationParameterFingerprint)) {
        throw "DERIVED_PARAMETER_FINGERPRINT_MISSING: chr$chromosome"
    }
    if ($null -eq $derivationParameterFingerprint) {
        $derivationParameterFingerprint = $manifest.derivationParameterFingerprint
        $derivationContract = $manifest.derivationContract
    }
    if ($manifest.derivationParameterFingerprint -ne $derivationParameterFingerprint) {
        throw "DERIVED_PARAMETER_FINGERPRINT_MISMATCH: chr$chromosome"
    }
    $vcfPath = Join-Path $derivedRoot $manifest.derived.filename
    $indexPath = "$vcfPath.tbi"
    if (-not (Test-Path -LiteralPath $vcfPath -PathType Leaf) -or -not (Test-Path -LiteralPath $indexPath -PathType Leaf)) {
        throw "DERIVED_ARTIFACT_MISSING: chr$chromosome"
    }
    $sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $vcfPath).Hash.ToLowerInvariant()
    $indexSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $indexPath).Hash.ToLowerInvariant()
    if ($sha256 -ne $manifest.derived.sha256 -or $indexSha256 -ne $manifest.derived.indexSha256) {
        throw "DERIVED_CHECKSUM_MISMATCH: chr$chromosome"
    }
    $inputs.Add($vcfPath)
    $expectedRecords += [Int64]$manifest.derived.records
    $expectedSnv += [Int64]$manifest.derived.snv
    $expectedSmallIndel += [Int64]$manifest.derived.smallIndel
    $expectedHet += [Int64]$manifest.genotypeStatistics.het
    $expectedHomAlt += [Int64]$manifest.genotypeStatistics.homAlt
    $inputIdentity.Add([ordered]@{
            chromosome = $chromosome
            source = [ordered]@{
                filename = $manifest.source.filename
                bytes = [Int64]$manifest.source.bytes
                md5 = $manifest.source.md5
                sha256 = $manifest.source.sha256
                indexFilename = $manifest.source.index.filename
                indexBytes = [Int64]$manifest.source.index.bytes
                indexMd5 = $manifest.source.index.md5
                indexSha256 = $manifest.source.index.sha256
            }
            derived = [ordered]@{
                filename = $manifest.derived.filename
                sha256 = $sha256
                indexSha256 = $indexSha256
                records = [Int64]$manifest.derived.records
                snv = [Int64]$manifest.derived.snv
                smallIndel = [Int64]$manifest.derived.smallIndel
                het = [Int64]$manifest.genotypeStatistics.het
                homAlt = [Int64]$manifest.genotypeStatistics.homAlt
            }
        })
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$outputVcf = Join-Path $OutputDirectory 'NA12878.GRCh38.exome-like.chr1-22-X.vcf.gz'
$outputIndex = "$outputVcf.tbi"
$manifestOutput = Join-Path $OutputDirectory 'merged-resource-manifest.json'
if ((Test-Path -LiteralPath $outputVcf) -or (Test-Path -LiteralPath $outputIndex) -or (Test-Path -LiteralPath $manifestOutput)) {
    if (-not $ReuseExisting -or -not (Test-Path -LiteralPath $manifestOutput -PathType Leaf)) {
        throw 'MERGED_OUTPUT_EXISTS: Refusing silent overwrite.'
    }
    $existing = Get-Content -LiteralPath $manifestOutput -Raw | ConvertFrom-Json
    $existingSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $outputVcf).Hash.ToLowerInvariant()
    $existingIndexSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $outputIndex).Hash.ToLowerInvariant()
    if ($existing.derived.sha256 -ne $existingSha -or $existing.derived.indexSha256 -ne $existingIndexSha -or
        $existing.derivationParameterFingerprint -ne $derivationParameterFingerprint -or
        @($existing.inputs).Count -ne $inputIdentity.Count) {
        throw 'MERGED_OUTPUT_IDENTITY_MISMATCH: Existing output cannot be reused.'
    }
    for ($index = 0; $index -lt $inputIdentity.Count; $index++) {
        if ($existing.inputs[$index].chromosome -ne $inputIdentity[$index].chromosome -or
            $existing.inputs[$index].derived.sha256 -ne $inputIdentity[$index].derived.sha256 -or
            $existing.inputs[$index].derived.indexSha256 -ne $inputIdentity[$index].derived.indexSha256) {
            throw 'MERGED_OUTPUT_IDENTITY_MISMATCH: Existing input identity differs from the accepted set.'
        }
    }
    $existing | ConvertTo-Json -Depth 10
    exit 0
}

$temporaryRoot = Join-Path $OutputDirectory ('.temporary-merge-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
    $temporaryVcf = Join-Path $temporaryRoot 'merged.vcf.gz'
    Invoke-Tool -Executable $BcftoolsPath -Arguments (@('concat', '-a', '-Oz', '-o', $temporaryVcf) + @($inputs.ToArray())) -Code 'MERGE_FAILED'
    Invoke-Tool -Executable $TabixPath -Arguments @('-f', '-p', 'vcf', $temporaryVcf) -Code 'MERGED_INDEX_FAILED'
    Invoke-Tool -Executable $BgzipPath -Arguments @('-t', $temporaryVcf) -Code 'MERGED_BGZF_INVALID'

    $samples = @(& $BcftoolsPath query -l $temporaryVcf)
    if ($LASTEXITCODE -ne 0 -or $samples.Count -ne 1 -or $samples[0] -ne 'NA12878') {
        throw 'MERGED_SAMPLE_MISMATCH: Expected exactly NA12878.'
    }
    $contigs = @(& $TabixPath -l $temporaryVcf)
    if ($LASTEXITCODE -ne 0 -or ($contigs -join ',') -ne ($ordered -join ',')) {
        throw 'MERGED_CONTIG_ORDER_MISMATCH: Output scope/order differs from the frozen contract.'
    }
    $recordsText = (& $BcftoolsPath index -n $temporaryVcf | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or $recordsText -notmatch '^\d+$' -or [Int64]$recordsText -ne $expectedRecords) {
        throw 'MERGED_RECORD_COUNT_MISMATCH: Output does not equal the accepted input sum.'
    }
    $stats = @(& $BcftoolsPath stats $temporaryVcf)
    if ($LASTEXITCODE -ne 0 -or
        (Get-StatValue -Lines $stats -Label 'number of SNPs:') -ne $expectedSnv -or
        (Get-StatValue -Lines $stats -Label 'number of indels:') -ne $expectedSmallIndel) {
        throw 'MERGED_VARIANT_STATISTICS_MISMATCH: SNV/indel totals differ from accepted inputs.'
    }
    $forbiddenInfo = @('AF','AC','AN','NS','EAS_AF','EUR_AF','AFR_AF','AMR_AF','SAS_AF','AF_popmax','gnomAD_AF','gnomADg_AF','gnomADe_AF')
    $header = @(& $BcftoolsPath view -h $temporaryVcf)
    if ($LASTEXITCODE -ne 0) { throw 'MERGED_HEADER_UNREADABLE: Header query failed.' }
    foreach ($field in $forbiddenInfo) {
        if (@($header | Where-Object { $_ -match ('^##INFO=<ID=' + [regex]::Escape($field) + '(?:,|>)') }).Count -gt 0) {
            throw "MERGED_COHORT_INFO_PRESENT: $field"
        }
    }
    $invalidGt = @(& $BcftoolsPath view -H -e "GT='alt'" $temporaryVcf | Select-Object -First 1)
    if ($LASTEXITCODE -ne 0 -or $invalidGt.Count -gt 0) {
        throw 'MERGED_NON_ALT_GENOTYPE_PRESENT: Output contains a non-alt/no-call record.'
    }
    [Int64]$actualHet = 0
    [Int64]$actualHomAlt = 0
    & $BcftoolsPath query -f '[%GT\n]' $temporaryVcf | ForEach-Object {
        if ($_ -match '^(0[/|]1|1[/|]0)$') { $actualHet++ }
        elseif ($_ -match '^1[/|]1$') { $actualHomAlt++ }
        else { throw "MERGED_UNEXPECTED_GENOTYPE: $_" }
    }
    if ($LASTEXITCODE -ne 0 -or $actualHet -ne $expectedHet -or $actualHomAlt -ne $expectedHomAlt) {
        throw 'MERGED_GENOTYPE_STATISTICS_MISMATCH: Het/hom-alt totals differ from accepted inputs.'
    }
    $insideBed = (& $BcftoolsPath view -H -R $resolvedBed $temporaryVcf | Measure-Object -Line).Lines
    if ($LASTEXITCODE -ne 0 -or [Int64]$insideBed -ne $expectedRecords) {
        throw 'MERGED_BED_LEAKAGE: Not every merged record overlaps the accepted BED.'
    }
    & $BcftoolsPath view -H -r '1:1-1000000' $temporaryVcf | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'MERGED_BOUNDED_QUERY_FAILED: Bounded query failed.' }
    $seen = New-Object 'System.Collections.Generic.HashSet[string]'
    $duplicateIdentity = $null
    & $BcftoolsPath query -f '%CHROM\t%POS\t%REF\t%ALT\n' $temporaryVcf | ForEach-Object {
        if (-not $seen.Add($_) -and $null -eq $duplicateIdentity) { $duplicateIdentity = $_ }
    }
    if ($LASTEXITCODE -ne 0) { throw 'MERGED_IDENTITY_QUERY_FAILED: Duplicate scan failed.' }
    if ($null -ne $duplicateIdentity) { throw 'MERGED_DUPLICATE_RECORD: Exact duplicate variant identity is present.' }

    Move-Item -LiteralPath $temporaryVcf -Destination $outputVcf
    Move-Item -LiteralPath "$temporaryVcf.tbi" -Destination $outputIndex
    $result = [ordered]@{
        schemaVersion = 'benchmark-background-merged-resource-v1'
        status = 'COMPLETED'
        sampleId = 'NA12878'
        assembly = 'GRCh38'
        chromosomes = $ordered
        chromosomeY = 'NOT_APPLICABLE'
        mitochondrial = 'SOURCE_UNAVAILABLE'
        bedSha256 = $bedSha256
        bedFilename = (Split-Path -Leaf $resolvedBed)
        derivationContract = $derivationContract
        derivationParameterFingerprint = $derivationParameterFingerprint
        inputs = @($inputIdentity.ToArray())
        derived = [ordered]@{
            filename = (Split-Path -Leaf $outputVcf)
            bytes = (Get-Item -LiteralPath $outputVcf).Length
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $outputVcf).Hash.ToLowerInvariant()
            indexFilename = (Split-Path -Leaf $outputIndex)
            indexBytes = (Get-Item -LiteralPath $outputIndex).Length
            indexSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $outputIndex).Hash.ToLowerInvariant()
            records = $expectedRecords
            snv = $expectedSnv
            smallIndel = $expectedSmallIndel
            het = $expectedHet
            homAlt = $expectedHomAlt
            duplicateRecords = 0
            recordsOutsideBed = 0
            bgzf = 'VALID'
            index = 'VALID'
        }
        infoPolicy = 'Cohort-level frequency/count INFO fields are absent from the singleton merged input.'
    }
    $temporaryManifest = Join-Path $OutputDirectory ('.temporary-manifest-' + [Guid]::NewGuid().ToString('N') + '.json')
    [IO.File]::WriteAllText($temporaryManifest, ($result | ConvertTo-Json -Depth 10) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporaryManifest -Destination $manifestOutput
    $result | ConvertTo-Json -Depth 10
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}
