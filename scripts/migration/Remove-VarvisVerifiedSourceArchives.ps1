[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [switch]$Execute,
    [switch]$Rehash
)

$ErrorActionPreference = 'Stop'

$scriptsRoot = Split-Path -Parent $PSScriptRoot
$codeRoot = Split-Path -Parent $scriptsRoot
$projectRoot = Split-Path -Parent $codeRoot
$toolRoot = Join-Path $projectRoot 'Tool'

$artifacts = @(
    [pscustomobject]@{
        Group = 'Exomiser'
        RelativePath = 'exomiser\data\2602_hg38.zip'
        ExpectedBytes = 23576028714L
        ExpectedSha256 = '2ed14f8b63b5612068591648d8c3c6e34ce9d1c66c08a78990597f78031df447'
        ExpectedLastWriteTimeUtcTicks = 639231040658243217L
    },
    [pscustomobject]@{
        Group = 'Exomiser'
        RelativePath = 'exomiser\data\2602_phenotype.zip'
        ExpectedBytes = 13478530504L
        ExpectedSha256 = 'aa923530ca43cf5051ed2ba7b221bb73deb2b0dd66695b2ba60460db052f0493'
        ExpectedLastWriteTimeUtcTicks = 639230879566214147L
    },
    [pscustomobject]@{
        Group = 'AlphaMissense'
        RelativePath = 'm3\alphamissense_hg38.official.tsv.gz'
        ExpectedBytes = 642961469L
        ExpectedSha256 = '0516cfd71c0767ac8f9c469252d429000e94e02c008b6e3a46d4b4646fcd3475'
        ExpectedLastWriteTimeUtcTicks = 639228681939803623L
    }
)

function Test-RequiredRuntimeResources {
    $requiredFiles = @(
        (Join-Path $toolRoot 'exomiser\java\temurin-21.0.11+10-x64-windows\bin\java.exe'),
        (Join-Path $toolRoot 'exomiser\env\exomiser-15.1.0\exomiser-cli-15.1.0.jar'),
        (Join-Path $toolRoot 'exomiser\data\2602_hg38\2602_hg38_clinvar.mv.db'),
        (Join-Path $toolRoot 'exomiser\data\2602_hg38\2602_hg38_genome.mv.db'),
        (Join-Path $toolRoot 'exomiser\data\2602_hg38\2602_hg38_transcripts_ensembl.ser'),
        (Join-Path $toolRoot 'exomiser\data\2602_hg38\2602_hg38_transcripts_refseq.ser'),
        (Join-Path $toolRoot 'exomiser\data\2602_hg38\2602_hg38_transcripts_ucsc.ser'),
        (Join-Path $toolRoot 'exomiser\data\2602_hg38\2602_hg38_variants.mv.db'),
        (Join-Path $toolRoot 'exomiser\data\2602_phenotype\2602_phenotype.mv.db'),
        (Join-Path $toolRoot 'exomiser\data\2602_phenotype\hp.obo'),
        (Join-Path $toolRoot 'exomiser\data\2602_phenotype\rw_string_10.mv'),
        (Join-Path $toolRoot 'm3\alphamissense_hg38.tabix.tsv.gz'),
        (Join-Path $toolRoot 'm3\alphamissense_hg38.tabix.tsv.gz.tbi'),
        (Join-Path $toolRoot 'msys64\ucrt64\bin\tabix.exe')
    )
    $missingFiles = @($requiredFiles | Where-Object {
        -not (Test-Path -LiteralPath $_ -PathType Leaf)
    })
    if ($missingFiles.Count -gt 0) {
        throw "Required runtime file is missing: $($missingFiles -join '; ')"
    }

    $dataExpectations = @(
        [pscustomobject]@{
            Path = Join-Path $toolRoot 'exomiser\data\2602_hg38'
            ExpectedFiles = 6
            ExpectedBytes = 32729036578L
        },
        [pscustomobject]@{
            Path = Join-Path $toolRoot 'exomiser\data\2602_phenotype'
            ExpectedFiles = 45
            ExpectedBytes = 25719160170L
        }
    )
    foreach ($expectation in $dataExpectations) {
        if (-not (Test-Path -LiteralPath $expectation.Path -PathType Container)) {
            throw "Required Exomiser data directory is missing: $($expectation.Path)"
        }
        $files = @(Get-ChildItem -LiteralPath $expectation.Path -File -Recurse -Force)
        $bytes = [long](($files | Measure-Object Length -Sum).Sum)
        if ($files.Count -ne $expectation.ExpectedFiles -or
                $bytes -ne $expectation.ExpectedBytes) {
            throw "Exomiser extracted-data metadata differs from the verified receipt: $($expectation.Path)"
        }
    }

    $tabix = Join-Path $toolRoot 'msys64\ucrt64\bin\tabix.exe'
    $alphaMissense = Join-Path $toolRoot 'm3\alphamissense_hg38.tabix.tsv.gz'
    $processInfo = New-Object Diagnostics.ProcessStartInfo
    $processInfo.FileName = $tabix
    $processInfo.Arguments = "`"$alphaMissense`" chr1:69094-69094"
    $processInfo.UseShellExecute = $false
    $processInfo.CreateNoWindow = $true
    $processInfo.RedirectStandardOutput = $true
    $processInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($processInfo)
    $standardOutput = $process.StandardOutput.ReadToEnd()
    $standardError = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    $queryRows = @($standardOutput -split "`r?`n" | Where-Object { $_ })
    if ($process.ExitCode -ne 0 -or $queryRows.Count -lt 1) {
        throw 'The retained AlphaMissense BGZF/index failed the bounded Tabix query.'
    }

    [pscustomobject]@{
        ExomiserHg38Files = 6
        ExomiserHg38Bytes = 32729036578L
        ExomiserPhenotypeFiles = 45
        ExomiserPhenotypeBytes = 25719160170L
        AlphaMissenseQueryRows = $queryRows.Count
    }
}

$present = New-Object 'System.Collections.Generic.List[object]'
foreach ($artifact in $artifacts) {
    $path = Join-Path $toolRoot $artifact.RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        continue
    }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Expected a regular file at the approved archive path: $path"
    }
    $item = Get-Item -LiteralPath $path -Force
    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw "Refusing to remove a reparse-point file: $path"
    }
    if ($item.Length -ne $artifact.ExpectedBytes -or
            $item.LastWriteTimeUtc.Ticks -ne $artifact.ExpectedLastWriteTimeUtcTicks) {
        throw "Archive metadata changed after SHA-256 verification; re-audit before deletion: $path"
    }
    if ($Rehash) {
        $actualHash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $artifact.ExpectedSha256) {
            throw "SHA-256 verification failed: $path"
        }
    }
    $present.Add([pscustomobject]@{
        Group = $artifact.Group
        Path = $item.FullName
        Bytes = $item.Length
        Sha256 = $artifact.ExpectedSha256
    })
}

$totalBytes = [long](($present | Measure-Object Bytes -Sum).Sum)
[pscustomobject]@{
    Mode = if ($Execute) { 'EXECUTE' } else { 'PREVIEW' }
    PresentCandidates = $present.Count
    AlreadyAbsent = $artifacts.Count - $present.Count
    CandidateBytes = $totalBytes
    CandidateGiB = [math]::Round($totalBytes / 1GB, 2)
    HashMode = if ($Rehash) { 'RECOMPUTED' } else { 'VERIFIED_RECEIPT_AND_UNCHANGED_METADATA' }
} | Format-List

$runtimeStatus = Test-RequiredRuntimeResources
$runtimeStatus | Format-List

if (-not $Execute) {
    $present | Format-Table -AutoSize
    Write-Host 'Preview only. Re-run with -Execute to remove exactly these verified source archives.'
    exit 0
}

$relatedProcesses = @(Get-CimInstance Win32_Process | Where-Object {
    $_.ProcessId -ne $PID -and $_.CommandLine -match
        '(?i)exomiser|alphamissense|aria2c|wget|curl'
})
if ($relatedProcesses.Count -gt 0) {
    throw "Related processes are still running; cleanup stopped. PIDs: $($relatedProcesses.ProcessId -join ', ')"
}

foreach ($group in @('Exomiser', 'AlphaMissense')) {
    $groupArtifacts = @($present | Where-Object Group -eq $group)
    foreach ($artifact in $groupArtifacts) {
        if ($PSCmdlet.ShouldProcess($artifact.Path, 'Remove verified redundant source archive')) {
            Remove-Item -LiteralPath $artifact.Path -Force -ErrorAction Stop
        }
    }
    if ($WhatIfPreference) {
        continue
    }
    $remaining = @($groupArtifacts | Where-Object { Test-Path -LiteralPath $_.Path })
    if ($remaining.Count -gt 0) {
        throw "Deletion verification failed for ${group}: $($remaining.Path -join '; ')"
    }
    Test-RequiredRuntimeResources | Out-Null
}

if ($WhatIfPreference) {
    Write-Host 'WhatIf completed; no files were removed.'
    exit 0
}

Write-Host "Cleanup completed: removed $($present.Count) source archives and $totalBytes bytes."
Write-Host 'Extracted Exomiser data and indexed AlphaMissense runtime resources remain queryable.'
