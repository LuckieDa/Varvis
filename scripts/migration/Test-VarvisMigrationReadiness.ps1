[CmdletBinding()]
param(
    [string]$ProjectRoot,
    [string]$ResourceRoot,
    [string]$LocalConfigPath,
    [string]$OutputPath,
    [switch]$VerifyHashes,
    [switch]$FailOnBlocking
)

$ErrorActionPreference = 'Stop'

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$codeRoot = Split-Path -Parent (Split-Path -Parent $scriptRoot)
. (Join-Path $scriptRoot 'VarvisWorkspace.ps1')
$serverRoot = Join-Path $codeRoot 'varvis-server'
if ($ProjectRoot -and [IO.Path]::GetFullPath($ProjectRoot) -ne $codeRoot) {
    throw 'ProjectRoot must be this Code checkout.'
}
$ProjectRoot = $codeRoot
if (-not $ResourceRoot) { $ResourceRoot = Join-Path $codeRoot 'local/resources' }
$ResourceRoot = Assert-VarvisLocalPath -Path $ResourceRoot -CodeRoot $codeRoot

if (-not $LocalConfigPath) {
    $LocalConfigPath = Join-Path $serverRoot 'config\variant-analysis-local.properties'
}
if (-not $OutputPath) {
    $OutputPath = Join-Path $codeRoot 'local\migration\resource-readiness-current.json'
}

$OutputPath = Assert-VarvisLocalPath -Path $OutputPath -CodeRoot $codeRoot

function Read-LocalProperties {
    param([string]$Path)

    $properties = @{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $properties
    }
    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#') -or $line.StartsWith('!')) {
            continue
        }
        $separator = $line.IndexOf('=')
        if ($separator -lt 0) {
            continue
        }
        $key = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1).Trim()
        if ($key) {
            $properties[$key] = $value
        }
    }
    return $properties
}

$properties = Read-LocalProperties -Path $LocalConfigPath

function Get-PropertyValue {
    param([string]$Key)

    if ($properties.ContainsKey($Key)) {
        return [string]$properties[$Key]
    }
    return $null
}

function Get-BooleanProperty {
    param(
        [string]$Key,
        [bool]$Default = $false
    )

    $value = Get-PropertyValue -Key $Key
    if ([string]::IsNullOrWhiteSpace($value)) {
        return $Default
    }
    return $value.Trim().Equals('true', [StringComparison]::OrdinalIgnoreCase)
}

function Expand-RootTokens {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $Value
    }
    return Expand-VarvisRoots -Value $Value -CodeRoot $codeRoot -ResourceRoot $ResourceRoot
}

function Resolve-ConfiguredPath {
    param(
        [string]$Value,
        [string]$RelativeBase = $serverRoot
    )

    $expanded = Expand-RootTokens -Value $Value
    if ([string]::IsNullOrWhiteSpace($expanded)) {
        return $null
    }
    if ($expanded.StartsWith('file:', [StringComparison]::OrdinalIgnoreCase)) {
        $expanded = ([uri]$expanded).LocalPath
    }
    if ([IO.Path]::IsPathRooted($expanded)) {
        return [IO.Path]::GetFullPath($expanded)
    }
    return [IO.Path]::GetFullPath((Join-Path $RelativeBase $expanded))
}

$checks = New-Object System.Collections.Generic.List[object]

function Add-Check {
    param(
        [string]$Id,
        [string]$Category,
        [string]$Status,
        [bool]$Blocking,
        [string]$Path,
        [string]$Version,
        [string]$Details
    )

    $checks.Add([pscustomobject][ordered]@{
        id = $Id
        category = $Category
        status = $Status
        blocking = $Blocking
        path = $Path
        version = $Version
        details = $Details
    }) | Out-Null
}

function Add-PathCheck {
    param(
        [string]$Id,
        [string]$Category,
        [string]$ConfiguredValue,
        [ValidateSet('File', 'Directory', 'Either')]
        [string]$ExpectedType = 'File',
        [bool]$Blocking = $true,
        [string]$ExpectedSha256,
        [string]$RelativeBase = $serverRoot
    )

    $resolved = Resolve-ConfiguredPath -Value $ConfiguredValue -RelativeBase $RelativeBase
    if (-not $resolved) {
        Add-Check -Id $Id -Category $Category -Status 'MISSING' -Blocking $Blocking `
            -Path $null -Version $null -Details 'No path is configured.'
        return
    }

    try {
        $resolved = Assert-VarvisLocalPath -Path $resolved -CodeRoot $codeRoot
    } catch {
        Add-Check -Id $Id -Category $Category -Status 'OUTSIDE_LAYOUT' -Blocking $Blocking -Path $resolved -Details $_.Exception.Message
        return
    }

    $exists = switch ($ExpectedType) {
        'File' { Test-Path -LiteralPath $resolved -PathType Leaf }
        'Directory' { Test-Path -LiteralPath $resolved -PathType Container }
        default { Test-Path -LiteralPath $resolved }
    }
    if (-not $exists) {
        Add-Check -Id $Id -Category $Category -Status 'MISSING' -Blocking $Blocking `
            -Path $resolved -Version $null -Details "$ExpectedType does not exist."
        return
    }

    if ($ExpectedSha256 -and $VerifyHashes -and $ExpectedType -eq 'File') {
        $actualSha256 = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualSha256 -ne $ExpectedSha256.ToLowerInvariant()) {
            Add-Check -Id $Id -Category $Category -Status 'HASH_MISMATCH' -Blocking $Blocking `
                -Path $resolved -Version $null -Details "Expected SHA-256 $ExpectedSha256; found $actualSha256."
            return
        }
        Add-Check -Id $Id -Category $Category -Status 'READY' -Blocking $Blocking `
            -Path $resolved -Version $null -Details 'Exists and SHA-256 matches.'
        return
    }

    $detail = if ($ExpectedSha256) {
        'Exists; configured SHA-256 was not recalculated. Use -VerifyHashes for the full check.'
    } else {
        'Exists.'
    }
    Add-Check -Id $Id -Category $Category -Status 'READY' -Blocking $Blocking `
        -Path $resolved -Version $null -Details $detail
}

function Get-CompanionIndex {
    param([string]$Data)
    $resolved = Resolve-ConfiguredPath -Value $Data
    if ($resolved -and -not (Test-Path -LiteralPath "$resolved.tbi" -PathType Leaf) -and
        (Test-Path -LiteralPath "$resolved.csi" -PathType Leaf)) { return "$Data.csi" }
    return "$Data.tbi"
}

function Get-VersionMajor {
    param(
        [string]$Text,
        [switch]$JavaStyle
    )

    if ($JavaStyle -and $Text -match 'version\s+"1\.(\d+)') {
        return [int]$Matches[1]
    }
    if ($Text -match '(?m)(?:^|\s|v)(\d+)(?:\.\d+)+') {
        return [int]$Matches[1]
    }
    return $null
}

function Add-CommandCheck {
    param(
        [string]$Id,
        [string]$CommandName,
        [string[]]$VersionArguments,
        [Nullable[int]]$MinimumMajor,
        [Nullable[int]]$ExactMajor,
        [bool]$Blocking = $true,
        [switch]$JavaStyle
    )

    if ([string]::IsNullOrWhiteSpace($CommandName)) {
        Add-Check -Id $Id -Category 'toolchain' -Status 'MISSING' -Blocking $Blocking -Details 'No command is configured.'
        return
    }
    $command = Get-Command $CommandName -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $command) {
        Add-Check -Id $Id -Category 'toolchain' -Status 'MISSING' -Blocking $Blocking `
            -Path $CommandName -Version $null -Details 'Command was not found on PATH.'
        return
    }

    $versionText = ''
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell may surface native stderr (for example java
        # -version) as a NativeCommandError when the script default is Stop.
        $ErrorActionPreference = 'Continue'
        $versionText = (& $command.Source @VersionArguments 2>&1 | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) { throw "Version command exited with code $LASTEXITCODE" }
    } catch {
        Add-Check -Id $Id -Category 'toolchain' -Status 'VERSION_MISMATCH' -Blocking $Blocking `
            -Path $command.Source -Version $null -Details "Version command failed: $($_.Exception.Message)"
        return
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $major = Get-VersionMajor -Text $versionText -JavaStyle:$JavaStyle
    $firstLine = ($versionText -split "`r?`n" | Select-Object -First 1)

    if ($null -ne $ExactMajor -and $major -ne $ExactMajor) {
        Add-Check -Id $Id -Category 'toolchain' -Status 'VERSION_MISMATCH' -Blocking $Blocking `
            -Path $command.Source -Version $firstLine -Details "Expected major version $($ExactMajor); detected $major."
        return
    }
    if ($null -ne $MinimumMajor -and ($null -eq $major -or $major -lt $MinimumMajor)) {
        Add-Check -Id $Id -Category 'toolchain' -Status 'VERSION_MISMATCH' -Blocking $Blocking `
            -Path $command.Source -Version $firstLine -Details "Expected major version >= $($MinimumMajor); detected $major."
        return
    }

    Add-Check -Id $Id -Category 'toolchain' -Status 'READY' -Blocking $Blocking `
        -Path $command.Source -Version $firstLine -Details 'Command is available.'
}

function Add-ConfiguredExecutableCheck {
    param(
        [string]$Id,
        [string]$Category,
        [string]$ConfiguredValue,
        [bool]$Blocking = $true
    )

    $expanded = Expand-RootTokens -Value $ConfiguredValue
    if ([string]::IsNullOrWhiteSpace($expanded)) {
        Add-Check -Id $Id -Category $Category -Status 'MISSING' -Blocking $Blocking `
            -Path $null -Version $null -Details 'No executable is configured.'
        return
    }
    if ([IO.Path]::IsPathRooted($expanded) -or $expanded.Contains('/') -or $expanded.Contains('\')) {
        Add-PathCheck -Id $Id -Category $Category -ConfiguredValue $ConfiguredValue `
            -ExpectedType File -Blocking $Blocking
        return
    }
    $command = Get-Command $expanded -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($command) {
        Add-Check -Id $Id -Category $Category -Status 'READY' -Blocking $Blocking `
            -Path $command.Source -Version $null -Details 'Executable is available on PATH.'
    } else {
        Add-Check -Id $Id -Category $Category -Status 'MISSING' -Blocking $Blocking `
            -Path $expanded -Version $null -Details 'Configured executable was not found on PATH.'
    }
}

Add-PathCheck -Id 'project-root' -Category 'workspace' -ConfiguredValue $ProjectRoot -ExpectedType Directory
Add-PathCheck -Id 'resource-root' -Category 'workspace' -ConfiguredValue $ResourceRoot -ExpectedType Directory
Add-PathCheck -Id 'local-variant-config' -Category 'configuration' -ConfiguredValue $LocalConfigPath -ExpectedType File

Add-CommandCheck -Id 'git' -CommandName 'git.exe' -VersionArguments @('--version') -MinimumMajor 2
Add-CommandCheck -Id 'maven' -CommandName 'mvn.cmd' -VersionArguments @('--version') -MinimumMajor 3
Add-CommandCheck -Id 'backend-java' -CommandName 'java.exe' -VersionArguments @('-version') -MinimumMajor 17 -JavaStyle
Add-CommandCheck -Id 'node' -CommandName 'node.exe' -VersionArguments @('--version') -MinimumMajor 20
Add-CommandCheck -Id 'npm' -CommandName 'npm.cmd' -VersionArguments @('--version') -MinimumMajor 9
Add-CommandCheck -Id 'python' -CommandName 'python.exe' -VersionArguments @('--version') -MinimumMajor 3 -Blocking $false

$normalizationEnabled = (Get-PropertyValue -Key 'variant-analysis.normalization.mode') -eq 'BCFTOOLS'
Add-ConfiguredExecutableCheck -Id 'bcftools' -Category 'normalization' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.normalization.bcftools-executable') `
    -Blocking $normalizationEnabled
Add-PathCheck -Id 'grch37-reference' -Category 'normalization' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.normalization.grch37-reference') `
    -ExpectedType File -Blocking $normalizationEnabled `
    -ExpectedSha256 (Get-PropertyValue -Key 'variant-analysis.normalization.grch37-reference-sha256')
Add-PathCheck -Id 'grch38-reference' -Category 'normalization' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.normalization.grch38-reference') `
    -ExpectedType File -Blocking $normalizationEnabled `
    -ExpectedSha256 (Get-PropertyValue -Key 'variant-analysis.normalization.grch38-reference-sha256')
Add-PathCheck -Id 'grch37-contig-alias' -Category 'normalization' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.normalization.grch37-contig-alias-file') `
    -ExpectedType File -Blocking $normalizationEnabled

$liftOverEnabled = (Get-PropertyValue -Key 'variant-analysis.lift-over.mode') -eq 'PICARD'
Add-ConfiguredExecutableCheck -Id 'liftover-java' -Category 'liftover' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.lift-over.java-executable') `
    -Blocking $liftOverEnabled
Add-PathCheck -Id 'picard' -Category 'liftover' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.lift-over.picard-jar') `
    -ExpectedType File -Blocking $liftOverEnabled
Add-PathCheck -Id 'hg19-to-hg38-chain' -Category 'liftover' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.lift-over.hg19-to-hg38-chain') `
    -ExpectedType File -Blocking $liftOverEnabled

$caddEnabled = Get-BooleanProperty -Key 'variant-analysis.cadd.enabled'
Add-ConfiguredExecutableCheck -Id 'cadd-tabix' -Category 'cadd' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.cadd.tabix-executable') -Blocking $caddEnabled
$caddSnv = Get-PropertyValue -Key 'variant-analysis.cadd.grch38-snv-file'
$caddIndel = Get-PropertyValue -Key 'variant-analysis.cadd.grch38-indel-file'
Add-PathCheck -Id 'cadd-snv' -Category 'cadd' -ConfiguredValue $caddSnv -Blocking $caddEnabled
Add-PathCheck -Id 'cadd-snv-index' -Category 'cadd' -ConfiguredValue "$caddSnv.tbi" -Blocking $caddEnabled
Add-PathCheck -Id 'cadd-indel' -Category 'cadd' -ConfiguredValue $caddIndel -Blocking $caddEnabled
Add-PathCheck -Id 'cadd-indel-index' -Category 'cadd' -ConfiguredValue "$caddIndel.tbi" -Blocking $caddEnabled

$clinVarEnabled = Get-BooleanProperty -Key 'variant-analysis.clin-var.enabled'
$clinVar37 = Get-PropertyValue -Key 'variant-analysis.clin-var.grch37-file'
$clinVar38 = Get-PropertyValue -Key 'variant-analysis.clin-var.grch38-file'
Add-PathCheck -Id 'clinvar-grch37' -Category 'clinvar' -ConfiguredValue $clinVar37 -Blocking $clinVarEnabled
Add-PathCheck -Id 'clinvar-grch37-index' -Category 'clinvar' -ConfiguredValue "$clinVar37.tbi" -Blocking $clinVarEnabled
Add-PathCheck -Id 'clinvar-grch38' -Category 'clinvar' -ConfiguredValue $clinVar38 -Blocking $clinVarEnabled
Add-PathCheck -Id 'clinvar-grch38-index' -Category 'clinvar' -ConfiguredValue "$clinVar38.tbi" -Blocking $clinVarEnabled

$alphaMissenseEnabled = Get-BooleanProperty -Key 'variant-analysis.alpha-missense.enabled'
$alphaMissense = Get-PropertyValue -Key 'variant-analysis.alpha-missense.grch38-file'
Add-PathCheck -Id 'alphamissense' -Category 'alphamissense' `
    -ConfiguredValue $alphaMissense -Blocking $alphaMissenseEnabled
Add-PathCheck -Id 'alphamissense-index' -Category 'alphamissense' `
    -ConfiguredValue "$alphaMissense.tbi" -Blocking $alphaMissenseEnabled

$alphaGenomeEnabled = Get-BooleanProperty -Key 'variant-analysis.alpha-genome.enabled'
Add-ConfiguredExecutableCheck -Id 'alphagenome-python' -Category 'alphagenome' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.alpha-genome.python-executable') `
    -Blocking $alphaGenomeEnabled
Add-PathCheck -Id 'alphagenome-script' -Category 'alphagenome' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.alpha-genome.script-path') `
    -ExpectedType File -Blocking $alphaGenomeEnabled -RelativeBase $serverRoot

$phenotypeEnabled = Get-BooleanProperty -Key 'variant-analysis.phenotype-prior.enabled'
Add-PathCheck -Id 'phen2gene-knowledgebase' -Category 'phenotype' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.phenotype-prior.local-file') `
    -ExpectedType Directory -Blocking $phenotypeEnabled
Add-PathCheck -Id 'phen2gene-manifest' -Category 'phenotype' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.phenotype-prior.manifest-file') `
    -ExpectedType File -Blocking $phenotypeEnabled
Add-PathCheck -Id 'gene-intervals' -Category 'phenotype' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.large-file.phenotype-prior-rescue-interval-file') `
    -ExpectedType File -Blocking $phenotypeEnabled

$exomiserEnabled = Get-BooleanProperty -Key 'variant-analysis.exomiser.enabled'
$exomiserJava = Get-PropertyValue -Key 'variant-analysis.exomiser.java-executable'
Add-ConfiguredExecutableCheck -Id 'exomiser-java' -Category 'exomiser' `
    -ConfiguredValue $exomiserJava `
    -Blocking $exomiserEnabled
Add-CommandCheck -Id 'exomiser-java-version' `
    -CommandName (Resolve-ConfiguredPath -Value $exomiserJava) `
    -VersionArguments @('-version') -ExactMajor 21 -Blocking $exomiserEnabled -JavaStyle
Add-PathCheck -Id 'exomiser-cli' -Category 'exomiser' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.exomiser.cli-jar') `
    -ExpectedType File -Blocking $exomiserEnabled
Add-PathCheck -Id 'exomiser-data' -Category 'exomiser' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.exomiser.data-directory') `
    -ExpectedType Directory -Blocking $exomiserEnabled
Add-PathCheck -Id 'exomiser-template' -Category 'exomiser' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.exomiser.analysis-template') `
    -ExpectedType File -Blocking $exomiserEnabled

Add-PathCheck -Id 'analysis-storage-root' -Category 'workspace' `
    -ConfiguredValue (Get-PropertyValue -Key 'variant-analysis.storage-root') `
    -ExpectedType Directory -Blocking $true

# Check the current configuration, not a historical decision to defer resources.
foreach ($assembly in @('grch37', 'grch38')) {
    $fasta = Get-PropertyValue -Key "variant-analysis.normalization.$assembly-reference"
    if ($fasta) {
        Add-PathCheck -Id "$assembly-fai" -Category 'normalization' -ConfiguredValue "$fasta.fai" -Blocking $normalizationEnabled
    }
}
$spliceEnabled = Get-BooleanProperty -Key 'variant-analysis.splice-ai.enabled'
foreach ($kind in @('snv', 'indel')) {
    $data = Get-PropertyValue -Key "variant-analysis.splice-ai.$kind-resource-path"
    $index = Get-PropertyValue -Key "variant-analysis.splice-ai.$kind-index-path"
    if (-not $index -and $data) { $index = Get-CompanionIndex -Data $data }
    Add-PathCheck -Id "spliceai-$kind" -Category 'spliceai' -ConfiguredValue $data -Blocking $spliceEnabled
    Add-PathCheck -Id "spliceai-$kind-index" -Category 'spliceai' -ConfiguredValue $index -Blocking $spliceEnabled
}
$gnomadEnabled = Get-BooleanProperty -Key 'variant-analysis.gnomad.enabled'
foreach ($key in @('grch38-files', 'grch38-compact-files')) {
    $required = $gnomadEnabled -and ($key -eq 'grch38-compact-files' -or
        (Get-BooleanProperty -Key 'variant-analysis.gnomad.allow-full-vcf-fallback'))
    $number = 0
    foreach ($data in ((Get-PropertyValue -Key "variant-analysis.gnomad.$key") -split ';')) {
        if (-not $data) { continue }
        $number++
        Add-PathCheck -Id "gnomad-$key-$number" -Category 'gnomad' -ConfiguredValue $data.Trim() -Blocking $required
        Add-PathCheck -Id "gnomad-$key-$number-index" -Category 'gnomad' -ConfiguredValue (Get-CompanionIndex -Data $data.Trim()) -Blocking $required
    }
}
$vepEnabled = Get-BooleanProperty -Key 'variant-analysis.vep.enabled'
foreach ($key in @('executable', 'cache-dir', 'fasta', 'plugins-dir')) {
    $type = if ($key -in @('cache-dir', 'plugins-dir')) { 'Directory' } else { 'File' }
    Add-PathCheck -ExpectedType $type -Id "vep-$key" -Category 'vep' -ConfiguredValue (Get-PropertyValue -Key "variant-analysis.vep.$key") -Blocking $vepEnabled
}

$blockingIssues = @($checks | Where-Object {
    $_.blocking -and $_.status -in @('MISSING', 'VERSION_MISMATCH', 'HASH_MISMATCH', 'OUTSIDE_LAYOUT')
})
$deferredChecks = @($checks | Where-Object { $_.status -eq 'DEFERRED_STORAGE' })
$overallStatus = if ($blockingIssues.Count -gt 0) {
    'ATTENTION_REQUIRED'
} elseif ($deferredChecks.Count -gt 0) {
    'READY_WITH_DEFERRED'
} else {
    'READY'
}

$statusCounts = [ordered]@{}
foreach ($group in ($checks | Group-Object status | Sort-Object Name)) {
    $statusCounts[$group.Name] = $group.Count
}
$checkArray = $checks.ToArray()

$report = [pscustomobject][ordered]@{
    schemaVersion = 'varvis-migration-resource-readiness-v1'
    generatedAt = (Get-Date).ToString('o')
    overallStatus = $overallStatus
    projectRoot = $ProjectRoot
    resourceRoot = $ResourceRoot
    codeRoot = $codeRoot
    localConfigPath = [IO.Path]::GetFullPath($LocalConfigPath)
    verifyHashes = [bool]$VerifyHashes
    knownDeferredResources = @()
    deferredReason = $null
    nextPhaseExclusions = @(
        'Database recovery is P1-2.',
        'Full build and test execution is P1-3.',
        'Synthetic end-to-end execution is P1-4.'
    )
    summary = [pscustomobject][ordered]@{
        total = $checks.Count
        blockingIssues = $blockingIssues.Count
        countsByStatus = $statusCounts
    }
    checks = $checkArray
}

$outputDirectory = Split-Path -Parent ([IO.Path]::GetFullPath($OutputPath))
if (-not (Test-Path -LiteralPath $outputDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
}
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText(
    [IO.Path]::GetFullPath($OutputPath),
    ($report | ConvertTo-Json -Depth 8),
    $utf8WithoutBom
)

$checks |
    Select-Object id, category, status, blocking, version, path |
    Format-Table -AutoSize
Write-Host "Overall status: $overallStatus"
Write-Host "Blocking issues: $($blockingIssues.Count)"
Write-Host "Report: $([IO.Path]::GetFullPath($OutputPath))"

if ($FailOnBlocking -and $blockingIssues.Count -gt 0) {
    exit 2
}
