[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Medium')]
param(
    [switch]$Execute
)

$ErrorActionPreference = 'Stop'

$scriptsRoot = Split-Path -Parent $PSScriptRoot
$codeRoot = Split-Path -Parent $scriptsRoot
$projectRoot = Split-Path -Parent $codeRoot
$toolRoot = Join-Path $projectRoot 'Tool'

$candidatePaths = New-Object 'System.Collections.Generic.List[string]'

function Add-CandidateFile {
    param([string]$Path)

    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        $candidatePaths.Add((Resolve-Path -LiteralPath $Path).Path)
    }
}

function Add-CandidateFiles {
    param(
        [string]$Root,
        [string]$Filter = '*',
        [switch]$Recurse
    )

    if (Test-Path -LiteralPath $Root -PathType Container) {
        Get-ChildItem -LiteralPath $Root -File -Force -Filter $Filter -Recurse:$Recurse |
            ForEach-Object { $candidatePaths.Add($_.FullName) }
    }
}

Add-CandidateFile (Join-Path $toolRoot 'cadd\gnomad.genomes.r4.0.indel.tsv.gz.partial-single')
Add-CandidateFiles (Join-Path $toolRoot 'cadd') '*.log'
Add-CandidateFile (Join-Path $toolRoot 'clinvar\prepare-clinvar.log')
Add-CandidateFile (Join-Path $toolRoot 'm3\AlphaMissense_hg38.tsv.gz')
Add-CandidateFiles (Join-Path $toolRoot 'm3') '*.log'
Add-CandidateFile (Join-Path $toolRoot 'downloads\msys2-x86_64-20260322.exe')
Add-CandidateFile (Join-Path $toolRoot 'alphagenome\downloads\python-3.11.9-amd64.exe')
Add-CandidateFile (Join-Path $toolRoot 'exomiser\raw\releases\OpenJDK21U-jdk_x64_windows_hotspot_21.0.11_10.zip')
Add-CandidateFile (Join-Path $toolRoot 'exomiser\raw\releases\exomiser-cli-15.1.0-distribution.zip')
Add-CandidateFile (Join-Path $toolRoot 'exomiser\raw\releases\OpenJDK21U-jre_x64_linux_hotspot_21.0.11_10.tar.gz')
Add-CandidateFiles (Join-Path $toolRoot 'exomiser\logs') '*' -Recurse
Add-CandidateFiles (Join-Path $toolRoot 'exomiser\tmp') '*' -Recurse
Add-CandidateFiles (Join-Path $toolRoot 'lirical\downloads') '*' -Recurse
Add-CandidateFiles (Join-Path $toolRoot 'lirical\data\2.4.0') 'hg38_refseq.ser.invalid-*'
Add-CandidateFiles (Join-Path $toolRoot 'phen2gene\logs') '*' -Recurse
Add-CandidateFiles (Join-Path $toolRoot 'phen2gene\tmp') '*' -Recurse
Add-CandidateFiles (Join-Path $toolRoot 'phen2gene\raw\Phen2Gene-1.2.3\calcs') '.nfs*' -Recurse
Add-CandidateFiles (Join-Path $toolRoot 'gene-intervals\logs') '*' -Recurse
Add-CandidateFile (Join-Path $toolRoot 'gene-intervals\sources\gencode.v19.annotation.gtf.gz')
Add-CandidateFile (Join-Path $codeRoot 'migration-reports\database-probe-classes\DatabaseReadinessProbe.class')

$approvedRoots = @(
    (Join-Path $toolRoot 'cadd'),
    (Join-Path $toolRoot 'clinvar'),
    (Join-Path $toolRoot 'm3'),
    (Join-Path $toolRoot 'downloads'),
    (Join-Path $toolRoot 'alphagenome\downloads'),
    (Join-Path $toolRoot 'exomiser\logs'),
    (Join-Path $toolRoot 'exomiser\tmp'),
    (Join-Path $toolRoot 'exomiser\raw\releases'),
    (Join-Path $toolRoot 'lirical\downloads'),
    (Join-Path $toolRoot 'lirical\data\2.4.0'),
    (Join-Path $toolRoot 'phen2gene\logs'),
    (Join-Path $toolRoot 'phen2gene\tmp'),
    (Join-Path $toolRoot 'phen2gene\raw\Phen2Gene-1.2.3\calcs'),
    (Join-Path $toolRoot 'gene-intervals\logs'),
    (Join-Path $toolRoot 'gene-intervals\sources'),
    (Join-Path $codeRoot 'migration-reports\database-probe-classes')
) | ForEach-Object { [IO.Path]::GetFullPath($_).TrimEnd('\') + '\' }

$candidates = @(
    $candidatePaths |
        Sort-Object -Unique |
        ForEach-Object {
            $item = Get-Item -LiteralPath $_ -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Refusing to delete a reparse-point file: $($item.FullName)"
            }
            $fullPath = [IO.Path]::GetFullPath($item.FullName)
            $approved = @($approvedRoots | Where-Object {
                $fullPath.StartsWith($_, [StringComparison]::OrdinalIgnoreCase)
            }).Count -gt 0
            if (-not $approved) {
                throw "Candidate is outside the approved cleanup roots: $fullPath"
            }
            [pscustomobject]@{
                Path = $fullPath
                Bytes = $item.Length
            }
        }
)

$totalBytes = [long](($candidates | Measure-Object Bytes -Sum).Sum)
$summary = [pscustomobject]@{
    Mode = if ($Execute) { 'EXECUTE' } else { 'PREVIEW' }
    CandidateFiles = $candidates.Count
    CandidateBytes = $totalBytes
    CandidateMiB = [math]::Round($totalBytes / 1MB, 2)
}
$summary | Format-List

if (-not $Execute) {
    $candidates | Sort-Object Bytes -Descending | Format-Table -AutoSize
    Write-Host 'Preview only. Re-run with -Execute to delete exactly these files.'
    exit 0
}

$relatedProcesses = @(
    Get-CimInstance Win32_Process |
        Where-Object {
            $_.ProcessId -ne $PID -and $_.CommandLine -match
            '(?i)exomiser_data_download|aria2c|lirical|phen2gene|alphamissense|whole_genome_snvs|gnomad.genomes.r4.0.indel'
        }
)
if ($relatedProcesses.Count -gt 0) {
    throw "Related download/preparation processes are still running; cleanup stopped. PIDs: $($relatedProcesses.ProcessId -join ', ')"
}

foreach ($candidate in $candidates) {
    if ($PSCmdlet.ShouldProcess($candidate.Path, 'Delete approved obsolete local artifact')) {
        Remove-Item -LiteralPath $candidate.Path -Force -ErrorAction Stop
    }
}

$remaining = @($candidates | Where-Object { Test-Path -LiteralPath $_.Path })
if ($remaining.Count -gt 0) {
    throw "Deletion verification failed for $($remaining.Count) file(s)."
}

$requiredFiles = @(
    (Join-Path $toolRoot 'msys64\ucrt64\bin\tabix.exe'),
    (Join-Path $toolRoot 'exomiser\java\temurin-21.0.11+10-x64-windows\bin\java.exe'),
    (Join-Path $toolRoot 'exomiser\env\exomiser-15.1.0\exomiser-cli-15.1.0.jar'),
    (Join-Path $toolRoot 'cadd\whole_genome_snvs.tsv.gz'),
    (Join-Path $toolRoot 'cadd\whole_genome_snvs.tsv.gz.tbi'),
    (Join-Path $toolRoot 'cadd\gnomad.genomes.r4.0.indel.tsv.gz'),
    (Join-Path $toolRoot 'cadd\gnomad.genomes.r4.0.indel.tsv.gz.tbi'),
    (Join-Path $toolRoot 'clinvar\clinvar.vcf.gz'),
    (Join-Path $toolRoot 'clinvar\clinvar.vcf.gz.tbi'),
    (Join-Path $toolRoot 'clinvar\grch37\clinvar.vcf.gz'),
    (Join-Path $toolRoot 'clinvar\grch37\clinvar.vcf.gz.tbi'),
    (Join-Path $toolRoot 'm3\alphamissense_hg38.tabix.tsv.gz'),
    (Join-Path $toolRoot 'm3\alphamissense_hg38.tabix.tsv.gz.tbi'),
    (Join-Path $toolRoot 'alphagenome\venv\Scripts\python.exe'),
    (Join-Path $toolRoot 'lirical\data\2.4.0\hg38_refseq.ser'),
    (Join-Path $toolRoot 'phen2gene\manifest\H2GKBs-1.1.0.sha256'),
    (Join-Path $toolRoot 'reference\grch38.p14.primary-chr.fa'),
    (Join-Path $toolRoot 'reference\grch38.p14.primary-chr.fa.fai'),
    (Join-Path $toolRoot 'reference\grch38.p14.primary-chr.dict'),
    (Join-Path $toolRoot 'liftover\picard.jar'),
    (Join-Path $toolRoot 'liftover\hg19ToHg38.over.chain.gz'),
    (Join-Path $toolRoot 'gene-intervals\generated\gene_feature_intervals_grch37_grch38.tsv')
)
$requiredDirectories = @(
    (Join-Path $toolRoot 'exomiser\data\2602_hg38'),
    (Join-Path $toolRoot 'exomiser\data\2602_phenotype'),
    (Join-Path $toolRoot 'phen2gene\index\hpo2gene-1.1.0\Knowledgebase')
)
$missingRequired = @(
    $requiredFiles | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) }
    $requiredDirectories | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Container) }
)
if ($missingRequired.Count -gt 0) {
    throw "Cleanup completed, but required-resource verification failed: $($missingRequired -join '; ')"
}

Write-Host "Cleanup completed: removed $($candidates.Count) files and $totalBytes bytes."
Write-Host 'Required local runtime resources remain present.'
