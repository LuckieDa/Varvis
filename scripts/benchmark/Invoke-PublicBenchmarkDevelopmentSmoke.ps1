[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $ToolRoot,
    [string] $RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string] $ApplicationJar,
    [string] $ApplicationSourceCommit,
    [string] $RunId,
    [string] $BenchmarkDatabaseCatalog = "varvis_benchmark_stage2b4",
    [int] $MaximumCases = 5,
    [int] $TimeoutSeconds = 7200,
    [string] $DatasetRelative = "docs/benchmark/phenopacket-store-0.1.27-grch38-singleton-smoke-v1",
    [string] $DatasetManifestName = "dataset-manifest.json",
    [string] $PilotG2ManifestRelative,
    [string] $ApprovedExecutionPackage,
    [switch] $Execute
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot "BenchmarkRealCaseRequest.ps1")
. (Join-Path $PSScriptRoot "BenchmarkCaseResult.ps1")

$ScriptVersion = $(if ($ApprovedExecutionPackage) {
    "approved-public-pilot-execution-adapter-v2"
} else {
    "public-benchmark-development-smoke-v1"
})
$BackgroundRelative = "benchmark/1000_genomes/grch38_20190312/merged/na12878_exome_v1"
$PhenopacketRelative = "benchmark/phenopacket_store/0.1.27/extracted/0.1.27"
$HpoRelative = "benchmark/ontology/hpo_2026_06_23"
$PhevalRelative = "benchmark/pheval/0.7.14/venv/Scripts/pheval-utils.exe"
$BcftoolsRelative = "msys64/ucrt64/bin/bcftools.exe"
$TabixRelative = "msys64/ucrt64/bin/tabix.exe"

function Get-Sha256([string] $Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-Utf8Sha256([string] $Value) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Write-JsonAtomic([object] $Value, [string] $Path, [int] $Depth = 20) {
    $parent = Split-Path -Parent $Path
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $temporary = "$Path.tmp"
    $json = $Value | ConvertTo-Json -Depth $Depth
    [System.IO.File]::WriteAllText($temporary, $json + [Environment]::NewLine,
        (New-Object System.Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Require-File([string] $Path, [string] $Code) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Code`: $Path" }
    if ($Path -match '\.(part|crdownload|download)$') { throw "INCOMPLETE_RESOURCE_SUFFIX: $Path" }
}

function Invoke-Checked([string] $Executable, [string[]] $Arguments, [string] $Code) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Code (exit=$LASTEXITCODE)" }
}

function Get-BenchmarkJdbcUrl([string] $Catalog) {
    if ($Catalog -notmatch '(?i)^[a-z0-9_]*benchmark[a-z0-9_]*$') {
        throw "BENCHMARK_DATABASE_REQUIRED"
    }
    $configuration = Join-Path $RepositoryRoot "varvis-server/config/database-local.properties"
    Require-File $configuration "DATABASE_CONFIGURATION_MISSING"
    $line = Get-Content -LiteralPath $configuration | Where-Object { $_ -match '^varvis\.datasource\.jdbc-url=' } | Select-Object -First 1
    if (-not $line) { throw "DATABASE_URL_MISSING" }
    $url = ($line -split '=', 2)[1]
    if ($url -notmatch '^(jdbc:mysql://[^/]+/)([^?]+)(.*)$') { throw "DATABASE_URL_UNSUPPORTED" }
    return $Matches[1] + $Catalog + $Matches[3] + ($(if ($Matches[3]) { "&" } else { "?" })) + "createDatabaseIfNotExist=true"
}

function Convert-TruthIdentity([string] $Identity, [string] $Gene) {
    $parts = $Identity -split '\|'
    if ($parts.Count -ne 5 -or $parts[0] -ne "GRCh38") { throw "INVALID_TRUTH_IDENTITY: $Identity" }
    return [ordered]@{
        gene = $Gene
        canonicalIdentity = [ordered]@{
            genomeAssembly = $parts[0]
            chromosome = $parts[1]
            position = [long]$parts[2]
            reference = $parts[3]
            alternate = $parts[4]
        }
    }
}

function Assert-SpikedVcf([string] $Vcf, [object[]] $Truths, [string] $Bcftools, [string] $Tabix) {
    Require-File $Vcf "SPIKED_VCF_MISSING"
    Require-File "$Vcf.tbi" "SPIKED_INDEX_MISSING"
    $samples = @(& $Bcftools query -l $Vcf)
    if ($LASTEXITCODE -ne 0 -or $samples.Count -ne 1 -or [string]::IsNullOrWhiteSpace($samples[0])) {
        throw "SPIKED_SAMPLE_MISMATCH"
    }
    $contigs = @(& $Tabix -l $Vcf)
    if ($LASTEXITCODE -ne 0) { throw "SPIKED_INDEX_INVALID" }
    foreach ($expected in @((1..22 | ForEach-Object { [string]$_ }) + "X")) {
        if ($contigs -notcontains $expected) { throw "SPIKED_CONTIG_SCOPE_MISMATCH: $expected" }
    }
    foreach ($truth in $Truths) {
        $key = $truth.canonicalIdentity
        $region = "$($key.chromosome):$($key.position)-$($key.position)"
        $rows = @(& $Bcftools query -r $region -f '%CHROM\t%POS\t%REF\t%ALT\n' $Vcf)
        if ($LASTEXITCODE -ne 0) { throw "SPIKED_TRUTH_QUERY_FAILED: $region" }
        $expectedRow = "$($key.chromosome)`t$($key.position)`t$($key.reference)`t$($key.alternate)"
        if ($rows -notcontains $expectedRow) { throw "SPIKED_TRUTH_MISSING: $expectedRow" }
    }
    return $samples[0]
}

$repository = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$tool = (Resolve-Path -LiteralPath $ToolRoot).Path
$datasetDirectory = Join-Path $repository $DatasetRelative
$datasetPath = Join-Path $datasetDirectory $DatasetManifestName
$selectionPath = Join-Path $datasetDirectory "selection-acceptance.json"
$inputPlanPath = Join-Path $datasetDirectory "truth-masked-hpo-review-v1/final-decisions/g0-g1-g2-reviewed-input-plan.json"
$pilotExecutionManifestPath = Join-Path $datasetDirectory "execution-preflight/pilot-execution-manifest.json"
$pilotG2ManifestPath = $null
if ($PilotG2ManifestRelative) { $pilotG2ManifestPath = Join-Path $repository $PilotG2ManifestRelative }
$backgroundDirectory = Join-Path $tool $BackgroundRelative
$backgroundManifestPath = Join-Path $backgroundDirectory "merged-resource-manifest.json"
$backgroundVcf = Join-Path $backgroundDirectory "NA12878.GRCh38.exome-like.chr1-22-X.vcf.gz"
$backgroundIndex = "$backgroundVcf.tbi"
$hpoDirectory = Join-Path $tool $HpoRelative
$hpoManifestPath = Join-Path $hpoDirectory "resource-manifest.json"
$hpoFile = Join-Path $hpoDirectory "hp.obo"
$pheval = Join-Path $tool $PhevalRelative
$phevalPython = Join-Path (Split-Path -Parent $pheval) "python.exe"
$bcftools = Join-Path $tool $BcftoolsRelative
$tabix = Join-Path $tool $TabixRelative
$localProperties = Join-Path $repository "varvis-server/config/variant-analysis-local.properties"
$grch38ContigAlias = Join-Path $repository "varvis-server/config/grch38-numeric-to-ucsc-contigs.tsv"

foreach ($item in @($datasetPath, $selectionPath, $backgroundManifestPath,
        $backgroundVcf, $backgroundIndex, $hpoManifestPath, $hpoFile, $pheval, $phevalPython,
        $bcftools, $tabix)) {
    Require-File $item "PREFLIGHT_RESOURCE_MISSING"
}
if ($pilotG2ManifestPath) { Require-File $pilotG2ManifestPath "PILOT_G2_MANIFEST_MISSING" }
else { Require-File $inputPlanPath "HPO_INPUT_PLAN_MISSING" }
Require-File $localProperties "LOCAL_ANALYSIS_CONFIGURATION_MISSING"
Require-File $grch38ContigAlias "GRCH38_CONTIG_ALIAS_MISSING"
$referenceLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^variant-analysis\.normalization\.grch38-reference=' } | Select-Object -First 1
if (-not $referenceLine) { throw "GRCH38_REFERENCE_CONFIGURATION_MISSING" }
$referenceFai = (($referenceLine -split '=', 2)[1] + ".fai")
Require-File $referenceFai "GRCH38_REFERENCE_FAI_MISSING"

$dataset = Get-Content -LiteralPath $datasetPath -Raw | ConvertFrom-Json
$selection = Get-Content -LiteralPath $selectionPath -Raw | ConvertFrom-Json
$inputPlan = $(if ($pilotG2ManifestPath) { $null } else { Get-Content -LiteralPath $inputPlanPath -Raw | ConvertFrom-Json })
$pilotG2Manifest = $(if ($pilotG2ManifestPath) { Get-Content -LiteralPath $pilotG2ManifestPath -Raw | ConvertFrom-Json } else { $null })
$backgroundManifest = Get-Content -LiteralPath $backgroundManifestPath -Raw | ConvertFrom-Json
$hpoManifest = Get-Content -LiteralPath $hpoManifestPath -Raw | ConvertFrom-Json
if ($dataset.datasetId -ne $selection.datasetId -or
        ($inputPlan -and $dataset.datasetId -ne $inputPlan.datasetId) -or
        ($pilotG2Manifest -and $dataset.datasetId -ne $pilotG2Manifest.datasetId)) {
    throw "FROZEN_DATASET_ID_MISMATCH"
}
if ($ApprovedExecutionPackage) {
    Require-File $ApprovedExecutionPackage "APPROVED_EXECUTION_PACKAGE_MISSING"
    Require-File $pilotExecutionManifestPath "APPROVED_EXECUTION_CONFIGURATION_MISSING"
    $package = Get-Content -LiteralPath $ApprovedExecutionPackage -Raw | ConvertFrom-Json
    if ($package.status -ne "READY_PENDING_EXECUTION_AUTHORIZATION" -or
            $package.executionStatus -ne "NOT_STARTED" -or
            $package.datasetId -ne $dataset.datasetId -or $package.caseCount -ne 30 -or
            $package.groupCount -ne 90 -or $package.rankingContractVersion -ne "ranking-contract-v1") {
        throw "APPROVED_EXECUTION_PACKAGE_INVALID"
    }
}
if ($backgroundManifest.status -ne "COMPLETED" -or $backgroundManifest.sampleId -ne "NA12878" -or
        $backgroundManifest.assembly -ne "GRCh38") { throw "BACKGROUND_MANIFEST_NOT_ACCEPTED" }
if ((Get-Sha256 $backgroundVcf) -ne $backgroundManifest.derived.sha256 -or
        (Get-Sha256 $backgroundIndex) -ne $backgroundManifest.derived.indexSha256) {
    throw "BACKGROUND_CHECKSUM_MISMATCH"
}
if ($hpoManifest.releaseVersion -ne "2026-06-23" -or $hpoManifest.validationStatus -ne "VALID" -or
        (Get-Sha256 $hpoFile) -ne $hpoManifest.sha256) { throw "HPO_RESOURCE_MISMATCH" }
Invoke-Checked $bcftools @("index", "-n", $backgroundVcf) "BACKGROUND_INDEX_INVALID"
if ((@(& $bcftools query -l $backgroundVcf)).Count -ne 1) { throw "BACKGROUND_SAMPLE_COUNT_MISMATCH" }
if ((& $bcftools query -l $backgroundVcf) -ne "NA12878") { throw "BACKGROUND_SAMPLE_MISMATCH" }
$phevalVersion = (& $phevalPython -c "import importlib.metadata; print(importlib.metadata.version('pheval'))").Trim()
if ($LASTEXITCODE -ne 0 -or $phevalVersion -ne "0.7.14") { throw "PHEVAL_VERSION_MISMATCH" }

$gitCommit = (& git -C $repository rev-parse HEAD).Trim()
$branch = (& git -C $repository branch --show-current).Trim()
$workingTreeState = $(if (@(& git -C $repository status --porcelain --untracked-files=no).Count -eq 0) { "CLEAN" } else { "DIRTY" })
$applicationArtifactSha = $null
if ($Execute) {
    if (-not $ApplicationJar) { throw "APPLICATION_JAR_REQUIRED_FOR_EXECUTION" }
    Require-File $ApplicationJar "APPLICATION_JAR_MISSING"
    if ($ApplicationSourceCommit -notmatch '^[0-9a-f]{40}$') { throw "INVALID_APPLICATION_SOURCE_COMMIT" }
    & git -C $repository cat-file -e "$ApplicationSourceCommit`^{commit}"
    if ($LASTEXITCODE -ne 0) { throw "APPLICATION_SOURCE_COMMIT_NOT_FOUND" }
    $applicationArtifactSha = Get-Sha256 (Resolve-Path -LiteralPath $ApplicationJar).Path
}
if (-not $RunId) { $RunId = "stage2b4-development-smoke-" + $gitCommit.Substring(0, 7) }
if ($MaximumCases -lt 1 -or $MaximumCases -gt @($selection.cases).Count) { throw "INVALID_CASE_LIMIT" }
if ($TimeoutSeconds -le 0) { throw "INVALID_TIMEOUT" }

$selectedCases = @($selection.cases | Select-Object -First $MaximumCases)
$truthText = (($selectedCases | ForEach-Object { $_.truthCanonicalIdentities } | Sort-Object) -join "`n")
$configurationCore = [ordered]@{
    assembly = "GRCh38"
    normalizationMode = "BCFTOOLS"
    normalizationProfile = "grch38-primary-chr"
    hpoOntologyVersion = "2026-06-23"
    phen2GeneVersion = "Phen2Gene-1.2.3;hpo2gene-1.1.0"
    vepVersion = "116.0"
    clinvarVersion = "ClinVar GRCh38 2026-03-21"
    gnomadVersion = "gnomAD exomes v4.1 GRCh38"
    caddVersion = "v1.7"
    candidateCap = 20000
    vepCap = 8000
    featureFlags = [ordered]@{
        clinvar = $true; gnomad = $true; cadd = $true; spliceAi = $true
        phenotypePrior = $true; preRankingVep = $true; compactClinvar = $false
    }
}
$configurationFingerprint = Get-Utf8Sha256 ($configurationCore | ConvertTo-Json -Depth 10 -Compress)
$configuration = [ordered]@{}
foreach ($property in $configurationCore.GetEnumerator()) { $configuration[$property.Key] = $property.Value }
$configuration.configurationFingerprint = $configurationFingerprint
if ($package) {
    $frozenExecution = Get-Content -LiteralPath $pilotExecutionManifestPath -Raw | ConvertFrom-Json
    $frozenConfiguration = $frozenExecution.configuration
    if ($frozenExecution.status -ne "PREFLIGHT_READY_PENDING_EXECUTION_AUTHORIZATION" -or
            $frozenConfiguration.configurationFingerprint -ne $package.configurationFingerprint -or
            $frozenConfiguration.analysisMode -ne "WES" -or
            $frozenConfiguration.candidateHardCap -ne 20000 -or $frozenConfiguration.vepCap -ne 8000 -or
            $frozenConfiguration.rankingContractVersion -ne "ranking-contract-v1" -or
            $frozenConfiguration.backgroundFingerprint -ne $backgroundManifest.derived.sha256) {
        throw "APPROVED_EXECUTION_CONFIGURATION_BINDING_MISMATCH"
    }
    $configuration = $frozenConfiguration
    $configurationFingerprint = $frozenConfiguration.configurationFingerprint
}

$groups = @()
foreach ($case in $selectedCases) {
    if ($pilotG2Manifest) {
        $g2Case = @($pilotG2Manifest.cases | Where-Object { $_.caseId -eq $case.caseId })
        if ($g2Case.Count -ne 1 -or $g2Case[0].readiness -ne "READY_IDENTICAL_TO_G1" -or
                $g2Case[0].excludedTermsArePositiveInput) {
            throw "PILOT_G2_CASE_NOT_READY: $($case.caseId)"
        }
        foreach ($group in @(
                [ordered]@{ groupId = "G0"; hpoInputMode = "NONE"; hpoTerms = @(); hpoInputChecksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"; hpoProvenance = "NONE" },
                [ordered]@{ groupId = "G1"; hpoInputMode = "SOURCE_PROVIDED"; hpoTerms = @($g2Case[0].observedHpoTerms); hpoInputChecksum = $g2Case[0].g1HpoInputChecksum; hpoProvenance = "PHENOPACKET_SOURCE" },
                [ordered]@{ groupId = "G2"; hpoInputMode = "HUMAN_REVIEWED"; hpoTerms = @($g2Case[0].observedHpoTerms); hpoInputChecksum = $g2Case[0].hpoInputChecksum; hpoProvenance = $g2Case[0].reviewProvenance }
            )) {
            $groups += [ordered]@{
                groupId = $group.groupId; caseId = $case.caseId; hpoInputMode = $group.hpoInputMode
                hpoTerms = @($group.hpoTerms); hpoInputChecksum = $group.hpoInputChecksum
                hpoProvenance = $group.hpoProvenance
                transformationProvenance = $(if ($group.groupId -eq "G2") { "truth-masked development review; G2 identical to G1" } else { "NONE" })
                configurationFingerprint = $configurationFingerprint
            }
        }
    } else {
        $casePlan = $inputPlan.cases | Where-Object { $_.caseId -eq $case.caseId }
        if (@($casePlan).Count -ne 1) { throw "HPO_INPUT_PLAN_CASE_MISMATCH: $($case.caseId)" }
        foreach ($group in $casePlan.groups) {
            if ($group.status -notin @("READY", "READY_IDENTICAL_TO_G1")) {
                throw "HPO_GROUP_NOT_READY: $($case.caseId)/$($group.groupId)"
            }
        $groups += [ordered]@{
            groupId = $group.groupId
            caseId = $case.caseId
            hpoInputMode = $group.hpoInputMode
            hpoTerms = @($group.hpoTerms)
            hpoInputChecksum = $group.hpoInputChecksum
            hpoProvenance = $group.hpoProvenance
            transformationProvenance = $(if ($group.groupId -eq "G2") { "truth-masked development review; G2 identical to G1" } else { "NONE" })
            configurationFingerprint = $configurationFingerprint
        }
        }
    }
}
if ($package) {
    $packageRequestDirectory = Join-Path (Split-Path -Parent $ApprovedExecutionPackage) "requests"
    if (@($package.requests).Count -ne 90) { throw "APPROVED_EXECUTION_PACKAGE_REQUEST_COUNT_INVALID" }
    foreach ($requestId in @($package.requests)) {
        $detailPath = Join-Path $packageRequestDirectory ($requestId + ".json")
        Require-File $detailPath "APPROVED_EXECUTION_REQUEST_MISSING"
        $detail = Get-Content -LiteralPath $detailPath -Raw | ConvertFrom-Json
        $matchingGroup = @($groups | Where-Object { $_.caseId -eq $detail.caseId -and $_.groupId -eq $detail.groupId })
        $hpoMatches = $matchingGroup.Count -eq 1 -and
                ((@($detail.positiveObservedHpoTerms) -join "`n") -eq (@($matchingGroup[0].hpoTerms) -join "`n"))
        $bindingMatches = $matchingGroup.Count -eq 1 -and
                $detail.configurationFingerprint -eq $configurationFingerprint -and
                $detail.applicationArtifactSha256 -eq $package.applicationArtifact.sha256 -and
                $detail.logicalCatalogId -eq $BenchmarkDatabaseCatalog -and
                $detail.authorization -eq "PENDING_EXPLICIT_USER_AUTHORIZATION" -and $hpoMatches
        if (-not $bindingMatches) {
            throw "APPROVED_EXECUTION_REQUEST_BINDING_MISMATCH: $requestId groups=$($matchingGroup.Count) config=$($detail.configurationFingerprint -eq $configurationFingerprint) expectedConfig=$($detail.configurationFingerprint) actualConfig=$configurationFingerprint artifact=$($detail.applicationArtifactSha256 -eq $package.applicationArtifact.sha256) catalog=$($detail.logicalCatalogId -eq $BenchmarkDatabaseCatalog) authorization=$($detail.authorization -eq 'PENDING_EXPLICIT_USER_AUTHORIZATION') hpo=$hpoMatches"
        }
    }
}

$runRoot = Join-Path $tool "benchmark/runs/$RunId"
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$runManifestPath = Join-Path $runRoot "run-manifest-$RunId.json"
$now = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
$runManifest = [ordered]@{
    schemaVersion = $(if ($Execute) { "benchmark-run-manifest-v2" } else { "benchmark-run-manifest-v1" })
    runId = $RunId
    createdAt = $now
    datasetId = $dataset.datasetId
    datasetVersion = $dataset.datasetVersion
    protocolVersion = $dataset.protocolVersion
    startedAt = $now
    endedAt = $null
    status = $(if ($Execute) { "RUNNING" } else { "PLANNED" })
    failureSummary = $null
    gitCommit = $gitCommit
    branch = $branch
    applicationVersion = "0.0.1-SNAPSHOT"
    javaVersion = "1.8.0_492"
    osFamily = "Windows"
    rankingContractVersion = "ranking-contract-v1"
    backgroundFingerprint = $backgroundManifest.derived.sha256
    truthFingerprint = Get-Utf8Sha256 $truthText
    configuration = $configuration
    sharedConfigurationFingerprint = $configurationFingerprint
    groups = $groups
}
if ($Execute) {
    $runManifest.Remove("gitCommit")
    $runManifest["executionProvenance"] = [ordered]@{
        schemaVersion = "benchmark-execution-provenance-v1"
        applicationSourceCommit = $ApplicationSourceCommit
        orchestrationSourceCommit = $gitCommit
        applicationArtifactSha256 = $applicationArtifactSha
        configurationFingerprint = $configurationFingerprint
        workingTreeState = $workingTreeState
    }
}
$executionProvenanceFingerprint = $null
if ($Execute) {
    $executionProvenanceFingerprint = Get-Utf8Sha256 (@(
        "benchmark-execution-provenance-v1", $ApplicationSourceCommit, $gitCommit,
        $applicationArtifactSha, $configurationFingerprint, $workingTreeState) -join "`n")
}
if (Test-Path -LiteralPath $runManifestPath) {
    $existing = Get-Content -LiteralPath $runManifestPath -Raw | ConvertFrom-Json
    if ($existing.status -in @("COMPLETED", "COMPLETED_WITH_FAILURES")) {
        throw "COMPLETED_RUN_MANIFEST_EXISTS"
    }
    if (-not $Execute) { throw "PREFLIGHT_RUN_MANIFEST_EXISTS" }
    if ($existing.status -notin @("RUNNING", "FAILED")) {
        throw "EXECUTION_RUN_MANIFEST_STATE_INVALID"
    }
}
Write-JsonAtomic $runManifest $runManifestPath

$preflight = [ordered]@{
    schemaVersion = "public-benchmark-real-preflight-v1"
    status = "READY"
    executionMode = $(if ($Execute) { "REAL_DEVELOPMENT_SMOKE" } else { "PREFLIGHT_ONLY" })
    scriptVersion = $ScriptVersion
    runId = $RunId
    datasetId = $dataset.datasetId
    selectedCases = @($selectedCases.caseId)
    selectedGroups = $groups.Count
    backgroundSha256 = $backgroundManifest.derived.sha256
    backgroundIndexSha256 = $backgroundManifest.derived.indexSha256
    bedSha256 = $backgroundManifest.bedSha256
    hpoVersion = $hpoManifest.releaseVersion
    hpoSha256 = $hpoManifest.sha256
    phevalVersion = $phevalVersion
    rankingContractVersion = "ranking-contract-v1"
    databaseIsolation = "DEDICATED_BENCHMARK_CATALOG_REQUIRED"
}
Write-JsonAtomic $preflight (Join-Path $runRoot "preflight.json")

if (-not $Execute) {
    $preflight | ConvertTo-Json -Depth 10
    exit 0
}

try {
$databaseUrl = Get-BenchmarkJdbcUrl $BenchmarkDatabaseCatalog
$compatibilityDirectory = Join-Path $runRoot "preparation/background"
& (Join-Path $PSScriptRoot "New-PhevalCompatibleBackground.ps1") -InputVcf $backgroundVcf `
    -ReferenceFai $referenceFai -OutputDirectory $compatibilityDirectory `
    -BcftoolsExecutable $bcftools -TabixExecutable $tabix | Out-Null
$phevalBackground = Join-Path $compatibilityDirectory "NA12878.GRCh38.exome-like.pheval-compatible.vcf.gz"
Require-File $phevalBackground "PHEVAL_COMPATIBLE_BACKGROUND_MISSING"
$storageRoot = Join-Path $runRoot "varvis-workspace"
New-Item -ItemType Directory -Path $storageRoot -Force | Out-Null
$results = @()

foreach ($case in $selectedCases) {
    $caseRoot = Join-Path $runRoot ("cases/" + $case.caseId)
    $sourcePhenopacket = Join-Path (Join-Path $tool $PhenopacketRelative) $case.sourceRelativePath
    Require-File $sourcePhenopacket "PHENOPACKET_MISSING"
    if ((Get-Sha256 $sourcePhenopacket) -ne $case.phenopacketSha256) {
        throw "PHENOPACKET_CHECKSUM_MISMATCH: $($case.caseId)"
    }
    $truths = @()
    $truthGene = $(if ($case.PSObject.Properties.Name -contains "truthGenes") {
        $case.truthGenes[0]
    } else { $case.genes[0] })
    foreach ($identity in $case.truthCanonicalIdentities) {
        $truths += Convert-TruthIdentity $identity $truthGene
    }
    $spikeDirectory = Join-Path $caseRoot "pheval"
    $workingPhenopacket = Join-Path $spikeDirectory (Split-Path -Leaf $sourcePhenopacket)
    $rawSpiked = Join-Path $spikeDirectory ((Split-Path -Leaf $workingPhenopacket) -replace '\.json$', '.vcf.gz')
    $spikedVcf = Join-Path $caseRoot "spiked/NA12878.GRCh38.spiked.vcf.gz"
    if (-not (Test-Path -LiteralPath $spikedVcf)) {
        New-Item -ItemType Directory -Path $spikeDirectory -Force | Out-Null
        Copy-Item -LiteralPath $sourcePhenopacket -Destination $workingPhenopacket -Force
        Invoke-Checked $pheval @("create-spiked-vcfs", "--phenopacket-path", $workingPhenopacket,
            "--hg38-template-vcf", $phevalBackground, "--output-dir", $spikeDirectory) "PHEVAL_SPIKE_FAILED"
        if ((Get-Sha256 $sourcePhenopacket) -ne $case.phenopacketSha256) {
            throw "SOURCE_PHENOPACKET_WAS_MODIFIED"
        }
        Require-File $rawSpiked "PHEVAL_OUTPUT_MISSING"
        New-Item -ItemType Directory -Path (Split-Path -Parent $spikedVcf) -Force | Out-Null
        & (Join-Path $PSScriptRoot "Convert-PhevalSpikedVcf.ps1") -InputVcf $rawSpiked `
            -OutputVcf $spikedVcf -BcftoolsExecutable $bcftools -TabixExecutable $tabix | Out-Null
    }
    $spikedSample = Assert-SpikedVcf $spikedVcf $truths $bcftools $tabix
    $spikedSha = Get-Sha256 $spikedVcf
    $truthIdentities = @($case.truthCanonicalIdentities | Sort-Object -Unique)
    $truthSetFingerprint = Get-Utf8Sha256 ($truthIdentities -join "`n")
    foreach ($group in @($groups | Where-Object { $_.caseId -eq $case.caseId })) {
        $resultId = "$($case.caseId)-$($group.groupId)"
        $resultDirectory = Join-Path $caseRoot ("results/" + $group.groupId)
        $resultPath = Join-Path $resultDirectory "case-result-$resultId.json"
        $requestPath = Join-Path $caseRoot ("requests/$($group.groupId).json")
        $attemptProvenancePath = Join-Path $resultDirectory "attempt-provenance.json"
        if (Test-Path -LiteralPath $resultPath) {
            Require-File $requestPath "BENCHMARK_RESUME_REQUEST_MISSING"
            $resumeRequest = Get-Content -LiteralPath $requestPath -Raw | ConvertFrom-Json
            $results += Read-BenchmarkTerminalCaseResult -Path $resultPath -Request $resumeRequest `
                -ExpectedTruthIdentities $truthIdentities -ProcessExitCode 0 `
                -AttemptProvenancePath $attemptProvenancePath -ResumeValidation
            continue
        }
        if (Test-Path -LiteralPath $requestPath) {
            throw "INCOMPLETE_BENCHMARK_ATTEMPT_REQUIRES_EXPLICIT_RETRY: $resultId"
        }
        $serviceHpoProvenance = @()
        foreach ($hpoTerm in @($group.hpoTerms)) {
            $serviceHpoProvenance += ([ordered]@{
                hpoId = $hpoTerm
                source = $(if ($group.groupId -eq "G2") { "HUMAN_REVIEWED" } else { "PHENOPACKET_SOURCE" })
                matchedTerm = $hpoTerm
                confidence = 1.0
            } | ConvertTo-Json -Compress)
        }
        $attemptId = "attempt-" + [guid]::NewGuid().ToString("N")
        $request = New-BenchmarkRealCaseRequest -RunId $RunId -DatasetId $dataset.datasetId `
            -DatasetVersion $dataset.datasetVersion -CaseId $case.caseId -GroupId $group.groupId `
            -ResultId $resultId -AttemptId $attemptId -PhenopacketSha256 $case.phenopacketSha256 `
            -TruthSetFingerprint $truthSetFingerprint `
            -ExecutionProvenanceFingerprint $executionProvenanceFingerprint `
            -ConfigurationFingerprint $configurationFingerprint -InputVcf $spikedVcf `
            -InputVcfSha256 $spikedSha -SampleId $spikedSample -HpoTerms @($group.hpoTerms) `
            -HpoProvenance $serviceHpoProvenance -TruthVariants $truths -RunManifest $runManifestPath `
            -TimeoutSeconds $TimeoutSeconds -ExpectedDatabaseCatalog $BenchmarkDatabaseCatalog `
            -OutputDirectory $resultDirectory
        Write-JsonAtomic $request $requestPath
        New-Item -ItemType Directory -Path $resultDirectory -Force | Out-Null
        $stdoutPath = Join-Path $resultDirectory "stdout.log"
        $stderrPath = Join-Path $resultDirectory "stderr.log"
        $arguments = @(
            "-jar", (Resolve-Path -LiteralPath $ApplicationJar).Path,
            "--server.port=0",
            "--varvis.datasource.jdbc-url=$databaseUrl",
            "--variant-analysis.storage-root=$storageRoot",
            "--variant-analysis.normalization.grch38-contig-alias-file=$grch38ContigAlias",
            "--variant-analysis.retention=PT168H",
            "--varvis.benchmark.real-case.enabled=true",
            "--varvis.benchmark.real-case.request-file=$requestPath"
        )
        $process = Start-Process -FilePath "java" -ArgumentList $arguments -Wait -PassThru -NoNewWindow `
            -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
        $results += Read-BenchmarkTerminalCaseResult -Path $resultPath -Request $request `
            -ExpectedTruthIdentities $truthIdentities -ProcessExitCode $process.ExitCode `
            -StdoutPath $stdoutPath -StderrPath $stderrPath `
            -AttemptProvenancePath $attemptProvenancePath
    }
}

$summaries = [ordered]@{}
foreach ($groupId in @("G0", "G1", "G2")) {
    $groupResults = @($results | Where-Object { $_.groupId -eq $groupId })
    $bestRanks = @()
    $notIn = 0; $unavailable = 0
    $failureCounts = [ordered]@{}
    $stagePresenceCounts = [ordered]@{}
    foreach ($result in $groupResults) {
        $ranked = @($result.truthMatches | Where-Object { $_.rankStatus -eq "RANKED" } | ForEach-Object { [int]$_.rank })
        if ($ranked.Count -gt 0) { $bestRanks += ($ranked | Measure-Object -Minimum).Minimum }
        elseif (@($result.truthMatches | Where-Object { $_.rankStatus -eq "RANK_UNAVAILABLE" }).Count -gt 0) { $unavailable++ }
        else { $notIn++ }
        if ($result.primaryFailureCode) {
            $key = [string]$result.primaryFailureCode
            if (-not $failureCounts.Contains($key)) { $failureCounts[$key] = 0 }
            $failureCounts[$key]++
        }
        foreach ($stage in @($result.stageRetention | Where-Object { $_.presence -eq "PRESENT" })) {
            $stageKey = [string]$stage.stage
            if (-not $stagePresenceCounts.Contains($stageKey)) { $stagePresenceCounts[$stageKey] = 0 }
            $stagePresenceCounts[$stageKey]++
        }
    }
    $eligible = $groupResults.Count
    $recall = [ordered]@{}
    foreach ($cutoff in @(1, 5, 10, 50)) {
        $hits = @($bestRanks | Where-Object { $_ -le $cutoff }).Count
        $recall[[string]$cutoff] = $(if ($eligible) { [double]$hits / $eligible } else { 0.0 })
    }
    $rr = 0.0; foreach ($rank in $bestRanks) { $rr += 1.0 / $rank }
    $median = $null
    if ($bestRanks.Count) {
        $orderedRanks = @($bestRanks | Sort-Object)
        $middle = [math]::Floor($orderedRanks.Count / 2)
        $median = $(if ($orderedRanks.Count % 2) { $orderedRanks[$middle] } else { ($orderedRanks[$middle - 1] + $orderedRanks[$middle]) / 2.0 })
    }
    $summaries[$groupId] = [ordered]@{
        eligibleCases = $eligible; rankedCases = $bestRanks.Count
        notInRankingUniverseCases = $notIn; rankUnavailableCases = $unavailable
        recallAtK = $recall; meanReciprocalRank = $(if ($eligible) { $rr / $eligible } else { 0.0 })
        medianRank = $median; failureCounts = $failureCounts; stagePresenceCounts = $stagePresenceCounts
    }
}
$caseRows = @()
$rankShifts = @()
$equivalenceMismatches = @()
foreach ($case in $selectedCases) {
    $byGroup = @{}
    foreach ($result in @($results | Where-Object { $_.caseId -eq $case.caseId })) {
        $ranked = @($result.truthMatches | Where-Object { $_.rankStatus -eq "RANKED" } | ForEach-Object { [int]$_.rank })
        $bestRank = $(if ($ranked.Count) { ($ranked | Measure-Object -Minimum).Minimum } else { $null })
        $status = $(if ($bestRank) { "RANKED" } elseif (@($result.truthMatches | Where-Object { $_.rankStatus -eq "RANK_UNAVAILABLE" }).Count) { "RANK_UNAVAILABLE" } else { "NOT_IN_RANKING_UNIVERSE" })
        $universe = @($result.truthMatches | ForEach-Object { $_.rankingUniverseSize } | Where-Object { $_ -ne $null } | Sort-Object -Descending | Select-Object -First 1)
        $row = [ordered]@{
            caseId = $case.caseId; groupId = $result.groupId
            executionStatus = $result.caseExecutionStatus; rankStatus = $status
            bestRank = $bestRank; rankingUniverseSize = $(if ($universe.Count) { $universe[0] } else { $null })
            reciprocalRank = $(if ($bestRank) { 1.0 / $bestRank } else { 0.0 })
            recallAt1 = [int]($bestRank -and $bestRank -le 1)
            recallAt5 = [int]($bestRank -and $bestRank -le 5)
            recallAt10 = [int]($bestRank -and $bestRank -le 10)
            recallAt50 = [int]($bestRank -and $bestRank -le 50)
            primaryFailureCode = $result.primaryFailureCode
            providerStatuses = $(if ($result.providerStatuses) { $result.providerStatuses | ConvertTo-Json -Compress } else { "{}" })
        }
        $caseRows += [pscustomobject]$row
        $byGroup[$result.groupId] = $row
    }
    if ($byGroup.ContainsKey("G0") -and $byGroup.ContainsKey("G1")) {
        $rankShifts += [ordered]@{
            caseId = $case.caseId
            g0Rank = $byGroup["G0"].bestRank
            g1Rank = $byGroup["G1"].bestRank
            shift = $(if ($byGroup["G0"].bestRank -and $byGroup["G1"].bestRank) { $byGroup["G0"].bestRank - $byGroup["G1"].bestRank } else { $null })
        }
    }
    if ($byGroup.ContainsKey("G1") -and $byGroup.ContainsKey("G2")) {
        if ($byGroup["G1"].rankStatus -ne $byGroup["G2"].rankStatus -or
                $byGroup["G1"].bestRank -ne $byGroup["G2"].bestRank -or
                $byGroup["G1"].rankingUniverseSize -ne $byGroup["G2"].rankingUniverseSize) {
            $equivalenceMismatches += $case.caseId
        }
    }
}
$aggregate = [ordered]@{
    schemaVersion = "public-benchmark-development-smoke-summary-v1"
    runId = $RunId
    purpose = "DEVELOPMENT_SMOKE_NOT_CLINICAL_ACCURACY"
    caseCount = $selectedCases.Count
    groupCount = $groups.Count
    summaries = $summaries
    g1G2ExpectedEquivalent = $true
    g1G2Equivalent = ($equivalenceMismatches.Count -eq 0)
    g1G2EquivalenceMismatches = $equivalenceMismatches
    g0ToG1RankShifts = $rankShifts
}
Write-JsonAtomic $aggregate (Join-Path $runRoot "aggregate-summary.json")
$caseRows | Export-Csv -LiteralPath (Join-Path $runRoot "aggregate-case-results.csv") -NoTypeInformation -Encoding UTF8
$markdown = @(
    "# Public Benchmark Development Smoke",
    "",
    "- Run: ``$RunId``",
    "- Purpose: development smoke only; not a clinical accuracy estimate",
    "- Cases: $($selectedCases.Count)",
    "- G1/G2 equivalent: $($equivalenceMismatches.Count -eq 0)",
    "",
    "| Group | Cases | Ranked | Recall@1 | Recall@5 | Recall@10 | Recall@50 | MRR | Median rank |",
    "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"
)
foreach ($groupId in @("G0", "G1", "G2")) {
    $summary = $summaries[$groupId]
    $markdown += "| $groupId | $($summary.eligibleCases) | $($summary.rankedCases) | $($summary.recallAtK['1']) | $($summary.recallAtK['5']) | $($summary.recallAtK['10']) | $($summary.recallAtK['50']) | $($summary.meanReciprocalRank) | $($summary.medianRank) |"
}
[System.IO.File]::WriteAllLines((Join-Path $runRoot "aggregate-summary.md"), $markdown,
    (New-Object System.Text.UTF8Encoding($false)))
$runManifest.status = $(if (@($results | Where-Object { $_.caseExecutionStatus -ne "COMPLETED" }).Count) { "COMPLETED_WITH_FAILURES" } else { "COMPLETED" })
if ($equivalenceMismatches.Count) { $runManifest.status = "COMPLETED_WITH_FAILURES"; $runManifest.failureSummary = "G1_G2_EQUIVALENCE_MISMATCH" }
$runManifest.endedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
Write-JsonAtomic $runManifest $runManifestPath
$aggregate | ConvertTo-Json -Depth 20
} catch {
    $message = [string]$_.Exception.Message
    $failureCode = ($message -split '[:(]', 2)[0].Trim()
    if ($failureCode -notmatch '^[A-Z0-9_\-]+$') { $failureCode = "EXECUTION_FAILED" }
    $runManifest.status = "FAILED"
    $runManifest.endedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    $runManifest.failureSummary = $failureCode
    Write-JsonAtomic $runManifest $runManifestPath
    throw
}
