[CmdletBinding()]
param(
    [string]$ManifestPath = (Join-Path $PSScriptRoot 'resources\1000-genomes-grch38-20190312-biallelic.manifest.json'),

    [Parameter(Mandatory = $true)]
    [string]$ResourceRoot,

    [string[]]$Chromosomes = @('1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19','20','21','X'),

    [string[]]$AcceptedChromosomes = @('22'),

    [string]$MirrorBaseUri = 'https://hgdownload.soe.ucsc.edu/gbdb/hg38/1000Genomes/',

    [Parameter(Mandatory = $true)]
    [string]$CurlPath,

    [string]$CaBundlePath = '',

    [int]$MaximumAttempts = 3,

    [int]$RetryDelaySeconds = 15,

    [ValidateRange(1, 16)]
    [int]$SegmentCount = 1,

    [Int64]$SafetyMarginBytes = 10737418240,

    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$curlTlsArguments = @()
if (-not [string]::IsNullOrWhiteSpace($CaBundlePath)) {
    if (-not (Test-Path -LiteralPath $CaBundlePath -PathType Leaf)) {
        throw 'CA_BUNDLE_UNAVAILABLE: The configured curl CA bundle is unavailable.'
    }
    if ((Get-Item -LiteralPath $CaBundlePath).Length -le 0) {
        throw 'CA_BUNDLE_EMPTY: The configured curl CA bundle is empty.'
    }
    $curlTlsArguments = @('--cacert', $CaBundlePath)
}

function Get-FileIdentity {
    param(
        [string]$Path,
        [Int64]$ExpectedBytes,
        [string]$ExpectedMd5
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return [ordered]@{ status = 'MISSING' }
    }
    $bytes = (Get-Item -LiteralPath $Path).Length
    if ($bytes -ne $ExpectedBytes) {
        return [ordered]@{ status = 'SIZE_MISMATCH'; bytes = $bytes }
    }
    $md5 = (Get-FileHash -Algorithm MD5 -LiteralPath $Path).Hash.ToLowerInvariant()
    if ($md5 -ne $ExpectedMd5) {
        return [ordered]@{ status = 'MD5_MISMATCH'; bytes = $bytes; md5 = $md5 }
    }
    return [ordered]@{
        status = 'ACCEPTED'
        bytes = $bytes
        md5 = $md5
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
    }
}

function Get-SafeUri {
    param([string]$BaseUri, [string]$Filename)

    if (-not $BaseUri.EndsWith('/')) {
        $BaseUri += '/'
    }
    return $BaseUri + $Filename
}

function Add-FileContent {
    param([string]$Source, [string]$Destination)
    $inputStream = [IO.File]::OpenRead($Source)
    $outputStream = [IO.File]::Open($Destination, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $inputStream.CopyTo($outputStream) }
    finally { $outputStream.Dispose(); $inputStream.Dispose() }
}

function Invoke-SegmentedResumableDownload {
    param(
        [string]$Uri,
        [string]$Destination,
        [Int64]$ExpectedBytes,
        [string]$ExpectedMd5,
        [string]$ResourceLabel,
        [int]$Segments
    )

    $formalIdentity = Get-FileIdentity -Path $Destination -ExpectedBytes $ExpectedBytes -ExpectedMd5 $ExpectedMd5
    if ($formalIdentity.status -eq 'ACCEPTED') {
        return [ordered]@{ status = 'SKIPPED_ACCEPTED'; identity = $formalIdentity; attempts = 0; segments = 1 }
    }
    if ($formalIdentity.status -ne 'MISSING') {
        throw "FORMAL_RESOURCE_CONFLICT: $ResourceLabel has status $($formalIdentity.status); refusing overwrite."
    }

    $segmentRoot = "$Destination.segments"
    New-Item -ItemType Directory -Force -Path $segmentRoot | Out-Null
    $legacyPart = "$Destination.part"
    $segmentLength = [Math]::Ceiling($ExpectedBytes / [double]$Segments)
    $ranges = New-Object System.Collections.Generic.List[object]
    for ($index = 0; $index -lt $Segments; $index++) {
        [Int64]$start = $index * $segmentLength
        [Int64]$end = [Math]::Min($ExpectedBytes - 1, (($index + 1) * $segmentLength) - 1)
        if ($start -le $end) { $ranges.Add([ordered]@{ index = $index; start = $start; end = $end; bytes = ($end - $start + 1) }) }
    }
    $firstPart = Join-Path $segmentRoot ('segment-{0:D3}.part' -f 0)
    if ((Test-Path -LiteralPath $legacyPart -PathType Leaf) -and -not (Test-Path -LiteralPath $firstPart -PathType Leaf)) {
        $legacyBytes = (Get-Item -LiteralPath $legacyPart).Length
        if ($legacyBytes -gt $ranges[0].bytes) {
            throw "SEGMENT_PREFIX_TOO_LARGE: $ResourceLabel existing prefix cannot fit segment zero."
        }
        Move-Item -LiteralPath $legacyPart -Destination $firstPart
    } elseif ((Test-Path -LiteralPath $legacyPart -PathType Leaf) -and (Test-Path -LiteralPath $firstPart -PathType Leaf)) {
        throw "SEGMENT_PREFIX_AMBIGUOUS: $ResourceLabel has both legacy and segmented partial files."
    }

    $processes = New-Object System.Collections.Generic.List[object]
    try {
        foreach ($range in $ranges) {
            $part = Join-Path $segmentRoot ('segment-{0:D3}.part' -f $range.index)
            [Int64]$currentBytes = if (Test-Path -LiteralPath $part -PathType Leaf) { (Get-Item -LiteralPath $part).Length } else { 0 }
            if ($currentBytes -gt $range.bytes) { throw "SEGMENT_SIZE_CONFLICT: $ResourceLabel segment $($range.index) is too large." }
            if ($currentBytes -eq $range.bytes) { continue }
            $from = $range.start + $currentBytes
            $chunk = "$part.download"
            if (Test-Path -LiteralPath $chunk) { Remove-Item -LiteralPath $chunk -Force }
            $log = "$part.log"
            $arguments = @('--fail', '--location', '--retry', '3', '--retry-all-errors', '--connect-timeout', '30') + $curlTlsArguments + @(
                '--range', "$from-$($range.end)", '--output', $chunk, $Uri)
            $process = Start-Process -FilePath $CurlPath -ArgumentList $arguments -PassThru -WindowStyle Hidden `
                -RedirectStandardError $log
            $processes.Add([pscustomobject]@{ process = $process; range = $range; part = $part; chunk = $chunk; log = $log })
        }
        foreach ($item in $processes) {
            $item.process.WaitForExit()
            $item.process.Refresh()
            [int]$exitCode = $item.process.ExitCode
            if ($exitCode -ne 0) { throw "SEGMENT_DOWNLOAD_FAILED: $ResourceLabel segment $($item.range.index) exit $exitCode" }
            if (-not (Test-Path -LiteralPath $item.chunk -PathType Leaf)) { throw "SEGMENT_OUTPUT_MISSING: $ResourceLabel segment $($item.range.index)" }
            $expectedChunkBytes = $item.range.bytes - (if (Test-Path -LiteralPath $item.part -PathType Leaf) { (Get-Item -LiteralPath $item.part).Length } else { 0 })
            $actualChunkBytes = (Get-Item -LiteralPath $item.chunk).Length
            if ($actualChunkBytes -ne $expectedChunkBytes) { throw "SEGMENT_RANGE_UNSUPPORTED: $ResourceLabel segment $($item.range.index) expected $expectedChunkBytes bytes but received $actualChunkBytes" }
            Add-FileContent -Source $item.chunk -Destination $item.part
            Remove-Item -LiteralPath $item.chunk -Force
            Remove-Item -LiteralPath $item.log -Force -ErrorAction SilentlyContinue
        }
    } catch {
        foreach ($item in $processes) { if (-not $item.process.HasExited) { Stop-Process -Id $item.process.Id -Force -ErrorAction SilentlyContinue } }
        throw
    }

    foreach ($range in $ranges) {
        $part = Join-Path $segmentRoot ('segment-{0:D3}.part' -f $range.index)
        if (-not (Test-Path -LiteralPath $part -PathType Leaf) -or (Get-Item -LiteralPath $part).Length -ne $range.bytes) {
            throw "SEGMENT_INCOMPLETE: $ResourceLabel segment $($range.index)"
        }
    }
    $assembled = "$Destination.assembled-$([Guid]::NewGuid().ToString('N'))"
    try {
        foreach ($range in $ranges) { Add-FileContent -Source (Join-Path $segmentRoot ('segment-{0:D3}.part' -f $range.index)) -Destination $assembled }
        $identity = Get-FileIdentity -Path $assembled -ExpectedBytes $ExpectedBytes -ExpectedMd5 $ExpectedMd5
        if ($identity.status -ne 'ACCEPTED') { throw "DOWNLOADED_RESOURCE_MISMATCH: $ResourceLabel assembled result has status $($identity.status)." }
        Move-Item -LiteralPath $assembled -Destination $Destination
        Remove-Item -LiteralPath $segmentRoot -Recurse -Force
        return [ordered]@{ status = 'DOWNLOADED_AND_ACCEPTED'; identity = $identity; attempts = 1; segments = $Segments }
    } catch {
        Remove-Item -LiteralPath $assembled -Force -ErrorAction SilentlyContinue
        throw
    }
}

function Invoke-ResumableDownload {
    param(
        [string]$Uri,
        [string]$Destination,
        [Int64]$ExpectedBytes,
        [string]$ExpectedMd5,
        [string]$ResourceLabel,
        [int]$Segments = 1
    )

    if ($Segments -gt 1 -and $ExpectedBytes -ge 67108864) {
        return Invoke-SegmentedResumableDownload -Uri $Uri -Destination $Destination -ExpectedBytes $ExpectedBytes `
            -ExpectedMd5 $ExpectedMd5 -ResourceLabel $ResourceLabel -Segments $Segments
    }

    $formalIdentity = Get-FileIdentity -Path $Destination -ExpectedBytes $ExpectedBytes -ExpectedMd5 $ExpectedMd5
    if ($formalIdentity.status -eq 'ACCEPTED') {
        return [ordered]@{ status = 'SKIPPED_ACCEPTED'; identity = $formalIdentity; attempts = 0 }
    }
    if ($formalIdentity.status -ne 'MISSING') {
        throw "FORMAL_RESOURCE_CONFLICT: $ResourceLabel has status $($formalIdentity.status); refusing overwrite."
    }

    $part = "$Destination.part"
    for ($attempt = 1; $attempt -le $MaximumAttempts; $attempt++) {
        $arguments = @(
            '--fail', '--location', '--continue-at', '-', '--retry', '0',
            '--output', $part
        ) + $curlTlsArguments + @($Uri)
        & $CurlPath @arguments
        $exitCode = $LASTEXITCODE
        if ($exitCode -eq 0) {
            $partIdentity = Get-FileIdentity -Path $part -ExpectedBytes $ExpectedBytes -ExpectedMd5 $ExpectedMd5
            if ($partIdentity.status -eq 'ACCEPTED') {
                Move-Item -LiteralPath $part -Destination $Destination
                return [ordered]@{ status = 'DOWNLOADED_AND_ACCEPTED'; identity = $partIdentity; attempts = $attempt }
            }
            if ($partIdentity.status -eq 'MD5_MISMATCH' -or
                ($partIdentity.status -eq 'SIZE_MISMATCH' -and $partIdentity.bytes -gt $ExpectedBytes)) {
                $quarantine = "$Destination.rejected-$([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))"
                Move-Item -LiteralPath $part -Destination $quarantine
                throw "DOWNLOADED_RESOURCE_MISMATCH: $ResourceLabel was quarantined after $($partIdentity.status)."
            }
        }
        if ($attempt -lt $MaximumAttempts) {
            Start-Sleep -Seconds $RetryDelaySeconds
        }
    }
    throw "DOWNLOAD_ATTEMPTS_EXHAUSTED: $ResourceLabel failed after $MaximumAttempts attempts; resumable .part is retained when present."
}

if ($MaximumAttempts -le 0 -or $RetryDelaySeconds -lt 0 -or $SafetyMarginBytes -lt 0 -or $SegmentCount -lt 1) {
    throw 'INVALID_DOWNLOAD_POLICY: Attempts must be positive and delay/margin non-negative.'
}
if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
    throw 'MANIFEST_MISSING: Frozen resource manifest is unavailable.'
}
if (-not (Test-Path -LiteralPath $CurlPath -PathType Leaf)) {
    throw 'CURL_UNAVAILABLE: The approved curl executable is unavailable.'
}
$manifestText = Get-Content -LiteralPath $ManifestPath -Raw
$manifest = $manifestText | ConvertFrom-Json
if ($manifest.schemaVersion -ne 'benchmark-background-source-manifest-v1') {
    throw "UNSUPPORTED_SCHEMA_VERSION: $($manifest.schemaVersion)"
}
if (@($Chromosomes | Group-Object | Where-Object Count -gt 1).Count -gt 0) {
    throw 'DUPLICATE_CHROMOSOME: Download chromosomes must be unique.'
}
$known = @($manifest.resources.chromosome)
foreach ($chromosome in $Chromosomes) {
    if ($known -notcontains $chromosome -or $chromosome -in @('Y','MT')) {
        throw "UNAPPROVED_CHROMOSOME: $chromosome"
    }
}
if ($Chromosomes -contains '22' -or $AcceptedChromosomes -notcontains '22') {
    throw 'CHR22_REUSE_BOUNDARY_VIOLATION: chr22 must remain accepted and excluded from downloads.'
}

$records = @($manifest.resources | Where-Object { $Chromosomes -contains $_.chromosome })
[Int64]$pendingBytes = 0
[Int64]$largestFileBytes = 0
foreach ($record in $records) {
    $pendingBytes += [Int64]$record.vcf.bytes + [Int64]$record.index.bytes
    if ([Int64]$record.vcf.bytes -gt $largestFileBytes) {
        $largestFileBytes = [Int64]$record.vcf.bytes
    }
}
$requiredFreeBytes = $pendingBytes + $largestFileBytes + $SafetyMarginBytes
$driveRoot = [System.IO.Path]::GetPathRoot((Resolve-Path -LiteralPath (Split-Path -Parent $ResourceRoot)).Path)
$driveName = $driveRoot.TrimEnd('\').TrimEnd(':')
$freeBytes = (Get-PSDrive -Name $driveName).Free
if (-not $DryRun -and $freeBytes -lt $requiredFreeBytes) {
    throw "INSUFFICIENT_DISK_SPACE: Required $requiredFreeBytes bytes but only $freeBytes bytes are available."
}

$items = New-Object System.Collections.Generic.List[object]
foreach ($record in $records) {
    $chromosome = $record.chromosome
    $chromosomeRoot = Join-Path $ResourceRoot ("chr" + $chromosome.ToLowerInvariant())
    $vcfPath = Join-Path $chromosomeRoot $record.vcf.filename
    $indexPath = Join-Path $chromosomeRoot $record.index.filename
    $vcfUri = Get-SafeUri -BaseUri $MirrorBaseUri -Filename $record.vcf.filename
    $indexUri = Get-SafeUri -BaseUri $manifest.officialBaseUri -Filename $record.index.filename
    $item = [ordered]@{
        chromosome = $chromosome
        vcf = [ordered]@{
            uri = $vcfUri
            filename = $record.vcf.filename
            relativePath = "chr$($chromosome.ToLowerInvariant())/$($record.vcf.filename)"
            expectedBytes = [Int64]$record.vcf.bytes
            expectedMd5 = $record.vcf.md5
        }
        index = [ordered]@{
            uri = $indexUri
            filename = $record.index.filename
            relativePath = "chr$($chromosome.ToLowerInvariant())/$($record.index.filename)"
            expectedBytes = [Int64]$record.index.bytes
            expectedMd5 = $record.index.md5
        }
        status = if ($DryRun) { 'PLANNED_NOT_EXECUTED' } else { 'PENDING' }
    }
    if (-not $DryRun) {
        New-Item -ItemType Directory -Force -Path $chromosomeRoot | Out-Null
        $vcfResult = Invoke-ResumableDownload -Uri $vcfUri -Destination $vcfPath `
            -ExpectedBytes ([Int64]$record.vcf.bytes) -ExpectedMd5 $record.vcf.md5 `
            -ResourceLabel "chr$chromosome VCF" -Segments $SegmentCount
        $indexResult = Invoke-ResumableDownload -Uri $indexUri -Destination $indexPath `
            -ExpectedBytes ([Int64]$record.index.bytes) -ExpectedMd5 $record.index.md5 `
            -ResourceLabel "chr$chromosome index"
        $item.vcf.result = $vcfResult
        $item.index.result = $indexResult
        $item.status = 'DOWNLOADED_AND_CHECKSUM_ACCEPTED'
    }
    $items.Add($item)
}

$result = [ordered]@{
    schemaVersion = 'benchmark-background-download-run-v1'
    status = if ($DryRun) { 'DRY_RUN' } else { 'COMPLETED' }
    sourceManifestSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $ManifestPath).Hash.ToLowerInvariant()
    mirrorBaseUri = $MirrorBaseUri
    officialIndexBaseUri = $manifest.officialBaseUri
    requestedChromosomes = @($Chromosomes)
    acceptedChromosomes = @($AcceptedChromosomes)
    pendingDownloadBytes = $pendingBytes
    largestFileBytes = $largestFileBytes
    requiredFreeBytes = $requiredFreeBytes
    availableFreeBytesAtStart = [Int64]$freeBytes
    resources = @($items.ToArray())
}
$json = ($result | ConvertTo-Json -Depth 12) + [Environment]::NewLine
if (-not $DryRun) {
    $runLabel = ($Chromosomes | ForEach-Object { $_.ToLowerInvariant() }) -join '-'
    $runManifest = Join-Path $ResourceRoot "download-run-manifest.chr$runLabel.json"
    $temporary = "$runManifest.temporary-$([Guid]::NewGuid().ToString('N'))"
    [System.IO.File]::WriteAllText($temporary, $json, [System.Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $runManifest -Force
}
$json
