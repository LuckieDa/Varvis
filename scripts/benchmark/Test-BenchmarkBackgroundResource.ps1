[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$VcfPath = $env:VARVIS_BENCHMARK_VCF,

    [Parameter(Mandatory = $true)]
    [string]$IndexPath = $env:VARVIS_BENCHMARK_VCF_INDEX,

    [Int64]$ExpectedIndexByteSize = 0,

    [string]$ExpectedIndexMd5,

    [Parameter(Mandatory = $true)]
    [Int64]$ExpectedByteSize = [Int64]$env:VARVIS_BENCHMARK_EXPECTED_BYTES,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedMd5 = $env:VARVIS_BENCHMARK_EXPECTED_MD5,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedSampleId = $env:VARVIS_BENCHMARK_EXPECTED_SAMPLE,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedAssembly = $env:VARVIS_BENCHMARK_EXPECTED_ASSEMBLY,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedContig = $env:VARVIS_BENCHMARK_EXPECTED_CONTIG,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory = $env:VARVIS_BENCHMARK_ACCEPTANCE_OUTPUT,

    [string]$TinyRegion = $env:VARVIS_BENCHMARK_TINY_REGION,

    [string]$BedPath = $env:VARVIS_BENCHMARK_BED,

    [switch]$ReuseExistingDerived,

    [Parameter(Mandatory = $true)]
    [string]$BcftoolsPath = $env:VARVIS_BCFTOOLS_EXECUTABLE,

    [Parameter(Mandatory = $true)]
    [string]$BgzipPath = $env:VARVIS_BGZIP_EXECUTABLE,

    [Parameter(Mandatory = $true)]
    [string]$TabixPath = $env:VARVIS_TABIX_EXECUTABLE
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:steps = New-Object System.Collections.Generic.List[object]
$script:commands = New-Object System.Collections.Generic.List[object]
$script:failed = $false
$script:commandTemp = $null
$script:source = $null
$script:derived = $null
$script:genotypeStats = $null
$script:infoAudit = $null
$script:tools = $null
$script:derivationContract = $null
$script:derivationParameterFingerprint = $null

function Get-StringSha256 {
    param([string]$Value)

    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

function New-DerivationContract {
    param(
        [string]$SampleId,
        [string]$Assembly,
        [string]$BedSha256
    )

    $parameters = [ordered]@{
        contractVersion = 'benchmark-single-sample-derivation-parameters-v1'
        sampleId = $SampleId
        assembly = $Assembly
        regionSelection = if ($BedSha256) { 'BED_RECORD_OVERLAP' } else { 'BOUNDED_REGION' }
        bedSha256 = $BedSha256
        variantSelection = 'BIALLELIC_SNV_SMALL_INDEL'
        genotypeSelection = 'GT_ALT_HET_OR_HOM_ALT_EXCLUDE_HOM_REF_MISSING_NO_CALL'
        removedCohortInfoFields = @(
            'AF', 'AC', 'AN', 'NS', 'EAS_AF', 'EUR_AF', 'AFR_AF', 'AMR_AF',
            'SAS_AF', 'AF_popmax', 'gnomAD_AF', 'gnomADg_AF', 'gnomADe_AF'
        )
        outputFormat = 'BGZF_VCF_WITH_TABIX'
    }
    $canonical = $parameters | ConvertTo-Json -Depth 5 -Compress
    return [ordered]@{
        parameters = $parameters
        fingerprint = Get-StringSha256 -Value $canonical
    }
}

function Add-Step {
    param(
        [string]$Name,
        [string]$Status,
        [string]$Code,
        [string]$Message,
        [hashtable]$Details = @{}
    )

    $script:steps.Add([ordered]@{
            name = $Name
            status = $Status
            code = $Code
            message = $Message
            details = $Details
        })
}

function Complete-NotRunSteps {
    param([string[]]$Names)

    foreach ($name in $Names) {
        Add-Step -Name $name -Status 'NOT_RUN' -Code 'BLOCKED_BY_PREVIOUS_FAILURE' `
            -Message 'A required preceding acceptance step failed.'
    }
}

function Fail-Acceptance {
    param(
        [string]$Name,
        [string]$Code,
        [string]$Message,
        [string[]]$RemainingSteps = @()
    )

    Add-Step -Name $Name -Status 'FAILED' -Code $Code -Message $Message
    Complete-NotRunSteps -Names $RemainingSteps
    $script:failed = $true
    throw $Message
}

function Is-IncompleteName {
    param([string]$Path)

    return $Path -match '(?i)(\.crdownload|\.part|\.download|\.partial|\.tmp)$'
}

function Get-ReportedArgument {
    param([string]$Argument)

    if ([string]::IsNullOrWhiteSpace($Argument)) {
        return $Argument
    }
    if ($Argument -match '^[A-Za-z]:[\\/]' -or $Argument -match '^[\\/]{2}') {
        return (Split-Path -Leaf $Argument)
    }
    return $Argument
}

function Get-ReportedDiagnostic {
    param(
        [string]$Diagnostic,
        [string[]]$Arguments
    )

    if ([string]::IsNullOrWhiteSpace($Diagnostic)) {
        return ''
    }
    $safe = $Diagnostic
    foreach ($argument in $Arguments) {
        if (-not [string]::IsNullOrWhiteSpace($argument) -and
            ($argument -match '^[A-Za-z]:[\\/]' -or $argument -match '^[\\/]{2}')) {
            $safe = $safe.Replace($argument, (Split-Path -Leaf $argument))
        }
    }
    if ($PSCommandPath) {
        $safe = $safe.Replace($PSCommandPath, (Split-Path -Leaf $PSCommandPath))
    }
    $lines = @($safe -split "`r?`n" | Where-Object {
            $_ -notmatch '^At ' -and
            $_ -notmatch '^\s*\+\s' -and
            $_ -notmatch '^\s*~' -and
            $_ -notmatch '^\s*\+ CategoryInfo' -and
            $_ -notmatch '^\s*\+ FullyQualifiedErrorId'
        })
    $summary = (($lines | Select-Object -First 3) -join ' ').Trim()
    return ($summary -replace '^\S+\.exe\s*:\s*', '')
}

function Invoke-Native {
    param(
        [string]$Executable,
        [string[]]$Arguments,
        [string]$FailureCode
    )

    $reportedArguments = @($Arguments | ForEach-Object { Get-ReportedArgument -Argument $_ })
    $script:commands.Add([ordered]@{
            executable = (Split-Path -Leaf $Executable)
            arguments = $reportedArguments
        })

    $stderrPath = Join-Path $script:commandTemp (([Guid]::NewGuid().ToString('N')) + '.stderr')
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # HTSlib writes non-fatal diagnostics to stderr. Native command success is
        # determined by its exit code, while stderr is retained for provenance.
        $ErrorActionPreference = 'Continue'
        $output = & $Executable @Arguments 2> $stderrPath
        $exitCode = $LASTEXITCODE
        $stderr = if (Test-Path -LiteralPath $stderrPath) {
            $stderrContent = Get-Content -LiteralPath $stderrPath -Raw
            if ($null -eq $stderrContent) { '' } else { $stderrContent.Trim() }
        } else {
            ''
        }
        if ($exitCode -ne 0) {
            $summary = if ($stderr) {
                Get-ReportedDiagnostic -Diagnostic $stderr -Arguments $Arguments
            } else {
                (($output | Select-Object -First 3) -join ' ').Trim()
            }
            throw "${FailureCode}: $summary"
        }
        return [pscustomobject]@{
            Output = @($output | ForEach-Object { $_.ToString() })
            Stderr = $stderr
        }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Remove-Item -LiteralPath $stderrPath -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-NativeToFile {
    param(
        [string]$Executable,
        [string[]]$Arguments,
        [string]$FailureCode,
        [string]$OutputPath
    )

    $reportedArguments = @($Arguments | ForEach-Object { Get-ReportedArgument -Argument $_ })
    $script:commands.Add([ordered]@{
            executable = (Split-Path -Leaf $Executable)
            arguments = $reportedArguments
        })

    $stderrPath = Join-Path $script:commandTemp (([Guid]::NewGuid().ToString('N')) + '.stderr')
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Executable @Arguments 1> $OutputPath 2> $stderrPath
        $exitCode = $LASTEXITCODE
        $stderr = if (Test-Path -LiteralPath $stderrPath) {
            $stderrContent = Get-Content -LiteralPath $stderrPath -Raw
            if ($null -eq $stderrContent) { '' } else { $stderrContent.Trim() }
        } else {
            ''
        }
        if ($exitCode -ne 0) {
            $summary = Get-ReportedDiagnostic -Diagnostic $stderr -Arguments $Arguments
            throw "${FailureCode}: $summary"
        }
        return [pscustomobject]@{
            OutputPath = $OutputPath
            Stderr = $stderr
        }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
        Remove-Item -LiteralPath $stderrPath -Force -ErrorAction SilentlyContinue
    }
}

function Get-StatValue {
    param(
        [string[]]$Lines,
        [string]$Label
    )

    foreach ($line in $Lines) {
        $fields = $line -split "`t"
        if ($fields.Count -ge 4 -and $fields[0] -eq 'SN' -and $fields[2] -eq $Label) {
            return [Int64]$fields[3]
        }
    }
    return $null
}

function Read-GenotypeStats {
    param([string]$Path)

    $stats = [ordered]@{
        records = 0
        het = 0
        homAlt = 0
        homRefExcluded = 0
        missingNoCallExcluded = 0
        otherGenotype = 0
        multiallelic = 0
        abnormalAllele = 0
        snv = 0
        smallIndel = 0
    }
    $reader = New-Object System.IO.StreamReader($Path)
    try {
        while (($line = $reader.ReadLine()) -ne $null) {
            $stats.records++
            $fields = $line -split "`t"
            if ($fields.Count -lt 5) {
                $stats.abnormalAllele++
                $stats.otherGenotype++
                continue
            }
            $ref = $fields[2]
            $alt = $fields[3]
            $gt = $fields[4]
            if ($alt.Contains(',')) {
                $stats.multiallelic++
            }
            if ($ref -match '[\[\]<>*]' -or $alt -match '[\[\]<>*]') {
                $stats.abnormalAllele++
            } elseif ($ref.Length -eq 1 -and $alt.Length -eq 1) {
                $stats.snv++
            } elseif ($ref.Length -ne $alt.Length) {
                $stats.smallIndel++
            }

            if ([string]::IsNullOrWhiteSpace($gt) -or $gt -match '\.') {
                $stats.missingNoCallExcluded++
                continue
            }
            $alleles = @($gt -split '[|/]')
            if ($alleles.Count -eq 0 -or @($alleles | Where-Object { $_ -notmatch '^\d+$' }).Count -gt 0) {
                $stats.otherGenotype++
            } elseif (@($alleles | Where-Object { $_ -eq '0' }).Count -eq $alleles.Count) {
                $stats.homRefExcluded++
            } elseif (@($alleles | Where-Object { $_ -ne '0' }).Count -eq $alleles.Count -and (@($alleles | Select-Object -Unique).Count -eq 1)) {
                $stats.homAlt++
            } elseif (@($alleles | Where-Object { $_ -ne '0' }).Count -gt 0) {
                $stats.het++
            } else {
                $stats.otherGenotype++
            }
        }
    } finally {
        $reader.Dispose()
    }
    return $stats
}

function Get-InfoIds {
    param([string[]]$Header)

    $ids = New-Object System.Collections.Generic.List[string]
    foreach ($line in $Header) {
        if ($line -match '^##INFO=<ID=([^,>]+)') {
            $ids.Add($Matches[1])
        }
    }
    return @($ids.ToArray())
}

$remaining = @(
    'size', 'official_md5', 'sha256', 'bgzf_and_header', 'tabix_index',
    'sample_list', 'assembly', 'contig', 'bounded_query', 'bed_filter',
    'genotype_statistics', 'single_sample_extract', 'derived_bgzf_and_tabix'
)

$reportPath = $null
try {
    if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
        throw 'OUTPUT_DIRECTORY_REQUIRED: OutputDirectory is required.'
    }
    New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
    $script:commandTemp = Join-Path $OutputDirectory ('.temporary-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $script:commandTemp | Out-Null

    foreach ($tool in @(@{ Name = 'bcftools'; Path = $BcftoolsPath }, @{ Name = 'bgzip'; Path = $BgzipPath }, @{ Name = 'tabix'; Path = $TabixPath })) {
        if ([string]::IsNullOrWhiteSpace($tool.Path) -or -not (Test-Path -LiteralPath $tool.Path -PathType Leaf)) {
            throw "TOOL_UNAVAILABLE: $($tool.Name) executable is missing."
        }
    }
    $bcftoolsVersion = Invoke-Native -Executable $BcftoolsPath -Arguments @('--version') -FailureCode 'BCFTOOLS_VERSION_UNAVAILABLE'
    $bgzipVersion = Invoke-Native -Executable $BgzipPath -Arguments @('--version') -FailureCode 'BGZIP_VERSION_UNAVAILABLE'
    $tabixVersion = Invoke-Native -Executable $TabixPath -Arguments @('--version') -FailureCode 'TABIX_VERSION_UNAVAILABLE'
    $script:tools = [ordered]@{
        bcftools = @($bcftoolsVersion.Output | Select-Object -First 2)
        bgzip = @($bgzipVersion.Output | Select-Object -First 1)
        tabix = @($tabixVersion.Output | Select-Object -First 1)
        lifecycle = 'PERSISTENT_THROUGH_STAGE_2_BENCHMARK'
    }
    if ($ExpectedByteSize -le 0) {
        Fail-Acceptance -Name 'input' -Code 'INVALID_EXPECTED_SIZE' -Message 'ExpectedByteSize must be positive.' -RemainingSteps $remaining
    }
    if ($ExpectedMd5 -notmatch '^[a-fA-F0-9]{32}$') {
        Fail-Acceptance -Name 'input' -Code 'INVALID_EXPECTED_MD5' -Message 'ExpectedMd5 must be a 32-character hexadecimal MD5.' -RemainingSteps $remaining
    }
    if ([string]::IsNullOrWhiteSpace($TinyRegion)) {
        $TinyRegion = "$ExpectedContig`:1-1"
    }
    if (-not $TinyRegion.StartsWith("$ExpectedContig`:", [System.StringComparison]::Ordinal)) {
        Fail-Acceptance -Name 'input' -Code 'INVALID_TINY_REGION' -Message 'TinyRegion must use ExpectedContig.' -RemainingSteps $remaining
    }
    $bedNameIncomplete = $false
    if (-not [string]::IsNullOrWhiteSpace($BedPath)) {
        $bedNameIncomplete = Is-IncompleteName -Path $BedPath
    }
    if ((Is-IncompleteName -Path $VcfPath) -or (Is-IncompleteName -Path $IndexPath) -or $bedNameIncomplete) {
        Fail-Acceptance -Name 'file' -Code 'INCOMPLETE_DOWNLOAD_NAME' -Message 'An input filename indicates an incomplete browser or transfer download.' -RemainingSteps $remaining
    }
    if (-not (Test-Path -LiteralPath $VcfPath -PathType Leaf)) {
        Fail-Acceptance -Name 'file' -Code 'VCF_MISSING' -Message 'The requested VCF does not exist.' -RemainingSteps $remaining
    }
    if (-not (Test-Path -LiteralPath $IndexPath -PathType Leaf)) {
        Fail-Acceptance -Name 'index' -Code 'INDEX_MISSING' -Message 'The requested Tabix index does not exist.' -RemainingSteps $remaining
    }
    if (-not [string]::IsNullOrWhiteSpace($BedPath) -and -not (Test-Path -LiteralPath $BedPath -PathType Leaf)) {
        Fail-Acceptance -Name 'bed_filter' -Code 'BED_MISSING' -Message 'The supplied benchmark BED does not exist.' -RemainingSteps $remaining
    }

    $resolvedVcf = (Resolve-Path -LiteralPath $VcfPath).Path
    $resolvedIndex = (Resolve-Path -LiteralPath $IndexPath).Path
    if (-not [string]::Equals($resolvedIndex, "$resolvedVcf.tbi", [System.StringComparison]::OrdinalIgnoreCase)) {
        Fail-Acceptance -Name 'index' -Code 'INDEX_PATH_MISMATCH' -Message 'The supplied index must be the VCF sidecar with the .tbi suffix.' -RemainingSteps $remaining
    }
    $actualIndexSize = (Get-Item -LiteralPath $resolvedIndex).Length
    if ($ExpectedIndexByteSize -gt 0 -and $actualIndexSize -ne $ExpectedIndexByteSize) {
        Fail-Acceptance -Name 'tabix_index' -Code 'INDEX_SIZE_MISMATCH' -Message 'Tabix index size does not match the supplied manifest value.' -RemainingSteps ($remaining | Select-Object -Skip 5)
    }
    $actualIndexMd5 = (Get-FileHash -Algorithm MD5 -LiteralPath $resolvedIndex).Hash.ToLowerInvariant()
    if (-not [string]::IsNullOrWhiteSpace($ExpectedIndexMd5)) {
        if ($ExpectedIndexMd5 -notmatch '^[a-fA-F0-9]{32}$') {
            Fail-Acceptance -Name 'tabix_index' -Code 'INVALID_EXPECTED_INDEX_MD5' -Message 'ExpectedIndexMd5 must be a 32-character hexadecimal MD5.' -RemainingSteps ($remaining | Select-Object -Skip 5)
        }
        if ($actualIndexMd5 -ne $ExpectedIndexMd5.ToLowerInvariant()) {
            Fail-Acceptance -Name 'tabix_index' -Code 'INDEX_MD5_MISMATCH' -Message 'Tabix index MD5 does not match the supplied official manifest value.' -RemainingSteps ($remaining | Select-Object -Skip 5)
        }
    }
    $indexSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedIndex).Hash.ToLowerInvariant()
    $actualSize = (Get-Item -LiteralPath $resolvedVcf).Length
    if ($actualSize -ne $ExpectedByteSize) {
        Fail-Acceptance -Name 'size' -Code 'SIZE_MISMATCH' -Message 'VCF size does not match the supplied manifest value.' -RemainingSteps ($remaining | Select-Object -Skip 1)
    }
    Add-Step -Name 'size' -Status 'PASSED' -Code 'SIZE_MATCH' -Message 'VCF size matches the supplied manifest value.' -Details @{ expectedBytes = $ExpectedByteSize; actualBytes = $actualSize }

    $actualMd5 = (Get-FileHash -Algorithm MD5 -LiteralPath $resolvedVcf).Hash.ToLowerInvariant()
    if ($actualMd5 -ne $ExpectedMd5.ToLowerInvariant()) {
        Fail-Acceptance -Name 'official_md5' -Code 'MD5_MISMATCH' -Message 'VCF MD5 does not match the supplied official manifest value.' -RemainingSteps ($remaining | Select-Object -Skip 2)
    }
    $sourceSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedVcf).Hash.ToLowerInvariant()
    Add-Step -Name 'official_md5' -Status 'PASSED' -Code 'MD5_MATCH' -Message 'VCF MD5 matches the supplied official manifest value.' -Details @{ md5 = $actualMd5 }
    Add-Step -Name 'sha256' -Status 'PASSED' -Code 'SHA256_RECORDED' -Message 'VCF SHA-256 was calculated locally.' -Details @{ sha256 = $sourceSha256 }
    $script:source = [ordered]@{
        filename = (Split-Path -Leaf $resolvedVcf)
        bytes = $actualSize
        md5 = $actualMd5
        sha256 = $sourceSha256
        index = [ordered]@{
            filename = (Split-Path -Leaf $resolvedIndex)
            bytes = $actualIndexSize
            md5 = $actualIndexMd5
            sha256 = $indexSha256
        }
    }

    $headerResult = Invoke-Native -Executable $BcftoolsPath -Arguments @('view', '-h', $resolvedVcf) -FailureCode 'VCF_HEADER_UNREADABLE'
    Invoke-Native -Executable $BgzipPath -Arguments @('-t', $resolvedVcf) -FailureCode 'BGZF_INVALID' | Out-Null
    Add-Step -Name 'bgzf_and_header' -Status 'PASSED' -Code 'BGZF_AND_HEADER_VALID' -Message 'BGZF compression and VCF header are readable.'
    $header = @($headerResult.Output)

    $indexResult = Invoke-Native -Executable $TabixPath -Arguments @('-l', $resolvedVcf) -FailureCode 'INDEX_UNREADABLE'
    if ($indexResult.Output -notcontains $ExpectedContig) {
        Fail-Acceptance -Name 'tabix_index' -Code 'INDEX_MISMATCH_OR_UNREADABLE' -Message 'The Tabix index does not expose the expected VCF contig.' -RemainingSteps ($remaining | Select-Object -Skip 5)
    }
    $indexDetails = @{ contigs = @($indexResult.Output); validation = 'READABLE_AND_BOUNDED_QUERYABLE' }
    if ($indexResult.Stderr -match 'index file is older than the data file') {
        $indexDetails.timestampWarning = 'INDEX_TIMESTAMP_OLDER_THAN_DATA_TIMESTAMP'
        $indexDetails.timestampWarningMeaning = 'The downloaded VCF has a newer local timestamp; checksum and bounded index queries remain authoritative.'
    } elseif ($indexResult.Stderr) {
        $indexDetails.warning = 'NON_EMPTY_INDEX_DIAGNOSTIC_RECORDED_WITHOUT_LOCAL_PATH'
    }
    Add-Step -Name 'tabix_index' -Status 'PASSED' -Code 'INDEX_READABLE' -Message 'The Tabix index is readable and contains the expected contig.' -Details $indexDetails

    $sourceRecordResult = Invoke-Native -Executable $BcftoolsPath -Arguments @('index', '-n', $resolvedVcf) -FailureCode 'SOURCE_RECORD_COUNT_UNAVAILABLE'
    $sourceRecordText = (@($sourceRecordResult.Output | Select-Object -First 1) -join '').Trim()
    if ($sourceRecordText -notmatch '^\d+$') {
        Fail-Acceptance -Name 'tabix_index' -Code 'SOURCE_RECORD_COUNT_INVALID' -Message 'The source index did not return a valid record count.' -RemainingSteps ($remaining | Select-Object -Skip 5)
    }
    $script:source.records = [Int64]$sourceRecordText

    $rawHeaderResult = Invoke-Native -Executable $TabixPath -Arguments @('-H', $resolvedVcf, $TinyRegion) -FailureCode 'RAW_HEADER_UNREADABLE'

    $samplesResult = Invoke-Native -Executable $BcftoolsPath -Arguments @('query', '-l', $resolvedVcf) -FailureCode 'SAMPLE_LIST_UNREADABLE'
    $samples = @($samplesResult.Output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($samples -notcontains $ExpectedSampleId) {
        Fail-Acceptance -Name 'sample_list' -Code 'EXPECTED_SAMPLE_MISSING' -Message 'The expected VCF sample ID is absent.' -RemainingSteps ($remaining | Select-Object -Skip 6)
    }
    Add-Step -Name 'sample_list' -Status 'PASSED' -Code 'SAMPLE_PRESENT' -Message 'The expected VCF sample ID is present.' -Details @{ sampleCount = $samples.Count; expectedSampleId = $ExpectedSampleId; aliases = @('HG001') }

    $headerText = $header -join "`n"
    $rawHeaderText = $rawHeaderResult.Output -join "`n"
    $assemblyTokenPattern = '(?:^|[^A-Za-z0-9])' + [regex]::Escape($ExpectedAssembly) + '(?:[^A-Za-z0-9]|$)'
    $referenceLines = @($rawHeaderResult.Output | Where-Object { $_ -match '^##reference=' })
    if (@($referenceLines | Where-Object { $_ -match ('(?i)' + $assemblyTokenPattern) }).Count -eq 0) {
        Fail-Acceptance -Name 'assembly' -Code 'ASSEMBLY_MISMATCH' -Message 'The VCF header does not declare the expected assembly.' -RemainingSteps ($remaining | Select-Object -Skip 7)
    }
    Add-Step -Name 'assembly' -Status 'PASSED' -Code 'ASSEMBLY_MATCH' -Message 'The VCF header declares the expected assembly.'
    if ($rawHeaderText -notmatch ('(?im)^##contig=<ID=' + [regex]::Escape($ExpectedContig) + '(?:,|>)')) {
        Fail-Acceptance -Name 'contig' -Code 'CONTIG_MISSING' -Message 'The expected contig is absent from the VCF header.' -RemainingSteps ($remaining | Select-Object -Skip 8)
    }
    Add-Step -Name 'contig' -Status 'PASSED' -Code 'CONTIG_PRESENT' -Message 'The expected contig is present in the VCF header.'

    $boundedResult = Invoke-Native -Executable $BcftoolsPath -Arguments @('view', '-H', '-r', $TinyRegion, $resolvedVcf) -FailureCode 'BOUNDED_QUERY_FAILED'
    Add-Step -Name 'bounded_query' -Status 'PASSED' -Code 'BOUNDED_QUERY_VALID' -Message 'The bounded VCF query completed.' -Details @{ region = $TinyRegion; records = @($boundedResult.Output).Count }

    $bedEnabled = -not [string]::IsNullOrWhiteSpace($BedPath)
    if ($bedEnabled) {
        $resolvedBed = (Resolve-Path -LiteralPath $BedPath).Path
        $bedSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolvedBed).Hash.ToLowerInvariant()
        $bedStatsResult = Invoke-Native -Executable $BcftoolsPath -Arguments @('stats', '-R', $resolvedBed, $resolvedVcf) -FailureCode 'BED_QUERY_FAILED'
        $bedRecords = Get-StatValue -Lines $bedStatsResult.Output -Label 'number of records:'
        Add-Step -Name 'bed_filter' -Status 'PASSED' -Code 'BED_SCOPE_READABLE' -Message 'The formal benchmark BED was accepted for bounded source filtering.' -Details @{ bedFilename = (Split-Path -Leaf $resolvedBed); recordsInsideBed = $bedRecords }
    } else {
        $resolvedBed = $null
        $bedSha256 = $null
        $bedRecords = $null
        Add-Step -Name 'bed_filter' -Status 'NOT_APPLICABLE' -Code 'BED_NOT_SUPPLIED' -Message 'No BED was supplied; this run uses the bounded tiny-region compatibility path.'
    }
    $derivationIdentity = New-DerivationContract -SampleId $ExpectedSampleId -Assembly $ExpectedAssembly -BedSha256 $bedSha256
    $script:derivationContract = $derivationIdentity.parameters
    $script:derivationParameterFingerprint = $derivationIdentity.fingerprint

    $sourceQueryPath = Join-Path $script:commandTemp 'source-genotypes.tsv'
    $queryArguments = if ($bedEnabled) {
        @('query', '-R', $resolvedBed, '-s', $ExpectedSampleId, '-f', '%CHROM\t%POS\t%REF\t%ALT\t[%GT]\n', $resolvedVcf)
    } else {
        @('query', '-r', $TinyRegion, '-s', $ExpectedSampleId, '-f', '%CHROM\t%POS\t%REF\t%ALT\t[%GT]\n', $resolvedVcf)
    }
    Invoke-NativeToFile -Executable $BcftoolsPath -Arguments $queryArguments -FailureCode 'GENOTYPE_QUERY_FAILED' -OutputPath $sourceQueryPath | Out-Null
    $script:genotypeStats = Read-GenotypeStats -Path $sourceQueryPath
    Add-Step -Name 'genotype_statistics' -Status 'PASSED' -Code 'GENOTYPE_STATISTICS_RECORDED' -Message 'The selected sample genotype categories were counted without retaining cohort records in memory.' -Details $script:genotypeStats

    $safeSample = $ExpectedSampleId -replace '[^A-Za-z0-9._-]', '_'
    $safeContig = $ExpectedContig -replace '[^A-Za-z0-9._-]', '_'
    $derivedName = if ($bedEnabled) { "$safeSample.$safeContig.exome-like.vcf.gz" } else { "$safeSample.$safeContig.tiny.vcf.gz" }
    $derivedVcf = Join-Path $OutputDirectory $derivedName
    $derivedTbi = "$derivedVcf.tbi"
    if ((Test-Path -LiteralPath $derivedVcf) -or (Test-Path -LiteralPath $derivedTbi)) {
        if (-not $ReuseExistingDerived) {
            Fail-Acceptance -Name 'single_sample_extract' -Code 'DERIVED_OUTPUT_EXISTS' -Message 'A derived output already exists; use ReuseExistingDerived only after manifest identity verification.' -RemainingSteps @('single_sample_extract', 'derived_bgzf_and_tabix')
        }
        $existingSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $derivedVcf).Hash.ToLowerInvariant()
        $existingManifestPath = Join-Path $OutputDirectory 'derived-resource-manifest.json'
        if (-not (Test-Path -LiteralPath $existingManifestPath -PathType Leaf)) {
            Fail-Acceptance -Name 'single_sample_extract' -Code 'DERIVED_MANIFEST_MISSING' -Message 'Existing derived output has no identity manifest.' -RemainingSteps @('single_sample_extract', 'derived_bgzf_and_tabix')
        }
        $existingManifest = Get-Content -LiteralPath $existingManifestPath -Raw | ConvertFrom-Json
        if ($existingManifest.source.sha256 -ne $sourceSha256 -or $existingManifest.sampleId -ne $ExpectedSampleId -or $existingManifest.contig -ne $ExpectedContig) {
            Fail-Acceptance -Name 'single_sample_extract' -Code 'DERIVED_OUTPUT_IDENTITY_MISMATCH' -Message 'Existing derived output does not match the requested source/sample/contig identity.' -RemainingSteps @('single_sample_extract', 'derived_bgzf_and_tabix')
        }
        if ($existingManifest.derived.sha256 -ne $existingSha) {
            Fail-Acceptance -Name 'single_sample_extract' -Code 'DERIVED_OUTPUT_CHECKSUM_MISMATCH' -Message 'Existing derived output does not match its identity manifest checksum.' -RemainingSteps @('single_sample_extract', 'derived_bgzf_and_tabix')
        }
        if ($existingManifest.PSObject.Properties.Name -contains 'derivationParameterFingerprint' -and
            $existingManifest.derivationParameterFingerprint -ne $script:derivationParameterFingerprint) {
            Fail-Acceptance -Name 'single_sample_extract' -Code 'DERIVED_PARAMETER_FINGERPRINT_MISMATCH' -Message 'Existing derived output was produced under a different derivation parameter contract.' -RemainingSteps @('single_sample_extract', 'derived_bgzf_and_tabix')
        }
        $script:derived = [ordered]@{
            filename = $derivedName
            sha256 = $existingSha
            sampleId = $existingManifest.derived.sampleId
            contig = $existingManifest.derived.contig
            records = $existingManifest.derived.records
            snv = $existingManifest.derived.snv
            smallIndel = $existingManifest.derived.smallIndel
            multiallelicSites = $existingManifest.derived.multiallelicSites
            bgzf = $existingManifest.derived.bgzf
            index = $existingManifest.derived.index
            reused = $true
        }
        $script:infoAudit = $existingManifest.infoAudit
        Add-Step -Name 'single_sample_extract' -Status 'PASSED' -Code 'SINGLE_SAMPLE_REUSED_IDEMPOTENT' -Message 'An identity-matched derived VCF was reused without overwriting it.' -Details @{ artifact = $derivedName }
        Invoke-Native -Executable $BgzipPath -Arguments @('-t', $derivedVcf) -FailureCode 'DERIVED_BGZF_INVALID' | Out-Null
        Invoke-Native -Executable $TabixPath -Arguments @('-l', $derivedVcf) -FailureCode 'DERIVED_INDEX_UNREADABLE' | Out-Null
        Add-Step -Name 'derived_bgzf_and_tabix' -Status 'PASSED' -Code 'DERIVED_RESOURCE_VALID' -Message 'The reused derived VCF is BGZF-compressed and Tabix-indexed.'
    } else {
        $sampleTemp = Join-Path $script:commandTemp 'single-sample.vcf.gz'
        $selectedTemp = Join-Path $script:commandTemp 'selected.vcf.gz'
        $sanitizedTemp = Join-Path $script:commandTemp 'sanitized.vcf.gz'
        $regionArguments = if ($bedEnabled) { @('-R', $resolvedBed) } else { @('-r', $TinyRegion) }
        $sampleArguments = @('view') + $regionArguments + @('-s', $ExpectedSampleId, '-Oz', '-o', $sampleTemp, $resolvedVcf)
        Invoke-Native -Executable $BcftoolsPath -Arguments $sampleArguments -FailureCode 'SINGLE_SAMPLE_EXTRACT_FAILED' | Out-Null
        Invoke-Native -Executable $BcftoolsPath -Arguments @('view', '-m2', '-M2', '-v', 'snps,indels', '-i', "GT='alt'", '-Oz', '-o', $selectedTemp, $sampleTemp) -FailureCode 'NON_REFERENCE_FILTER_FAILED' | Out-Null

        $configuredFrequencyFields = @('AF', 'AF_popmax', 'gnomAD_AF', 'gnomADg_AF', 'gnomADe_AF')
        $sourceInfoIds = Get-InfoIds -Header $header
        $removeFields = @('AF', 'AC', 'AN', 'NS', 'EAS_AF', 'EUR_AF', 'AFR_AF', 'AMR_AF', 'SAS_AF', 'AF_popmax', 'gnomAD_AF', 'gnomADg_AF', 'gnomADe_AF') | Where-Object { $sourceInfoIds -contains $_ }
        $script:infoAudit = [ordered]@{
            configuredVarvisFrequencyFields = $configuredFrequencyFields
            sourceFrequencyFieldsPresent = @($sourceInfoIds | Where-Object { $configuredFrequencyFields -contains $_ })
            removedCohortLevelFields = @($removeFields)
            policy = 'Remove cohort-level frequency/count fields before singleton Varvis input; preserve other INFO fields.'
        }
        if (@($removeFields).Count -gt 0) {
            $removeExpression = ($removeFields | ForEach-Object { "INFO/$_" }) -join ','
            Invoke-Native -Executable $BcftoolsPath -Arguments @('annotate', '-x', $removeExpression, '-Oz', '-o', $sanitizedTemp, $selectedTemp) -FailureCode 'INFO_SANITIZATION_FAILED' | Out-Null
        } else {
            Invoke-Native -Executable $BcftoolsPath -Arguments @('view', '-Oz', '-o', $sanitizedTemp, $selectedTemp) -FailureCode 'SINGLE_SAMPLE_REWRITE_FAILED' | Out-Null
        }
        Invoke-Native -Executable $TabixPath -Arguments @('-f', '-p', 'vcf', $sanitizedTemp) -FailureCode 'DERIVED_TABIX_FAILED' | Out-Null
        $derivedSamples = Invoke-Native -Executable $BcftoolsPath -Arguments @('query', '-l', $sanitizedTemp) -FailureCode 'DERIVED_SAMPLE_LIST_UNREADABLE'
        $derivedSampleList = @($derivedSamples.Output | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        if ($derivedSampleList.Count -ne 1 -or $derivedSampleList[0] -ne $ExpectedSampleId) {
            Fail-Acceptance -Name 'single_sample_extract' -Code 'DERIVED_SAMPLE_MISMATCH' -Message 'The derived VCF is not exactly the expected single sample.' -RemainingSteps @('single_sample_extract', 'derived_bgzf_and_tabix')
        }
        Move-Item -LiteralPath $sanitizedTemp -Destination $derivedVcf
        Move-Item -LiteralPath "$sanitizedTemp.tbi" -Destination $derivedTbi
        $derivedSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $derivedVcf).Hash.ToLowerInvariant()
        $derivedStatsResult = Invoke-Native -Executable $BcftoolsPath -Arguments @('stats', $derivedVcf) -FailureCode 'DERIVED_STATS_FAILED'
        $script:derived = [ordered]@{
            filename = $derivedName
            sha256 = $derivedSha
            sampleId = $ExpectedSampleId
            contig = $ExpectedContig
            records = Get-StatValue -Lines $derivedStatsResult.Output -Label 'number of records:'
            snv = Get-StatValue -Lines $derivedStatsResult.Output -Label 'number of SNPs:'
            smallIndel = Get-StatValue -Lines $derivedStatsResult.Output -Label 'number of indels:'
            multiallelicSites = Get-StatValue -Lines $derivedStatsResult.Output -Label 'number of multiallelic sites:'
            bgzf = 'VALID'
            index = (Split-Path -Leaf $derivedTbi)
        }
        Add-Step -Name 'single_sample_extract' -Status 'PASSED' -Code 'SINGLE_SAMPLE_VALID' -Message 'The derived VCF contains exactly the expected sample and only selected non-reference SNV/indel records.' -Details $script:derived
        Invoke-Native -Executable $BgzipPath -Arguments @('-t', $derivedVcf) -FailureCode 'DERIVED_BGZF_INVALID' | Out-Null
        $derivedIndexResult = Invoke-Native -Executable $TabixPath -Arguments @('-l', $derivedVcf) -FailureCode 'DERIVED_INDEX_UNREADABLE'
        if ($derivedIndexResult.Output -notcontains $ExpectedContig) {
            Fail-Acceptance -Name 'derived_bgzf_and_tabix' -Code 'DERIVED_CONTIG_MISSING' -Message 'The derived index does not contain the expected contig.'
        }
        Add-Step -Name 'derived_bgzf_and_tabix' -Status 'PASSED' -Code 'DERIVED_RESOURCE_VALID' -Message 'The derived VCF is BGZF-compressed and Tabix-indexed.' -Details @{ boundedContig = $ExpectedContig }
    }
    $script:derived.indexBytes = (Get-Item -LiteralPath $derivedTbi).Length
    $script:derived.indexSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $derivedTbi).Hash.ToLowerInvariant()
    $bedManifest = if ($bedEnabled) {
        [ordered]@{
            filename = (Split-Path -Leaf $resolvedBed)
            sha256 = $bedSha256
        }
    } else {
        $null
    }
    $derivedManifest = [ordered]@{
        schemaVersion = 'benchmark-single-sample-derived-v1'
        status = 'COMPLETED'
        source = $script:source
        sampleId = $ExpectedSampleId
        contig = $ExpectedContig
        assembly = $ExpectedAssembly
        bed = $bedManifest
        derivationContract = $script:derivationContract
        derivationParameterFingerprint = $script:derivationParameterFingerprint
        derived = $script:derived
        genotypeStatistics = $script:genotypeStats
        infoAudit = $script:infoAudit
        tools = $script:tools
        commands = @($script:commands.ToArray())
    }
    $manifestPath = Join-Path $OutputDirectory 'derived-resource-manifest.json'
    $manifestTemp = Join-Path $OutputDirectory ('.temporary-manifest-' + [Guid]::NewGuid().ToString('N') + '.json')
    [IO.File]::WriteAllText($manifestTemp, ($derivedManifest | ConvertTo-Json -Depth 10) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $manifestTemp -Destination $manifestPath -Force
}
catch {
    if (-not $script:failed) {
        Add-Step -Name 'execution' -Status 'FAILED' -Code 'UNEXPECTED_FAILURE' -Message $_.Exception.Message
        Complete-NotRunSteps -Names $remaining
        $script:failed = $true
    }
}
finally {
    if (-not [string]::IsNullOrWhiteSpace($script:commandTemp) -and (Test-Path -LiteralPath $script:commandTemp)) {
        Remove-Item -LiteralPath $script:commandTemp -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (-not [string]::IsNullOrWhiteSpace($OutputDirectory)) {
        New-Item -ItemType Directory -Force -Path $OutputDirectory -ErrorAction SilentlyContinue | Out-Null
        $reportPath = Join-Path $OutputDirectory 'benchmark-resource-acceptance.json'
    }
    $result = [ordered]@{
        schemaVersion = 'benchmark-resource-acceptance-v1'
        status = if ($script:failed) { 'FAILED' } else { 'PASSED' }
        expected = [ordered]@{
            sampleId = $ExpectedSampleId
            assembly = $ExpectedAssembly
            contig = $ExpectedContig
            tinyRegion = $TinyRegion
            bed = if ([string]::IsNullOrWhiteSpace($BedPath)) { $null } else { Split-Path -Leaf $BedPath }
        }
        source = $script:source
        derived = $script:derived
        genotypeStatistics = $script:genotypeStats
        infoAudit = $script:infoAudit
        derivationContract = $script:derivationContract
        derivationParameterFingerprint = $script:derivationParameterFingerprint
        tools = $script:tools
        commands = @($script:commands.ToArray())
        steps = $script:steps.ToArray()
    }
    $json = $result | ConvertTo-Json -Depth 12
    if ($reportPath) {
        [IO.File]::WriteAllText($reportPath, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    }
    $json
}

if ($script:failed) {
    exit 1
}
