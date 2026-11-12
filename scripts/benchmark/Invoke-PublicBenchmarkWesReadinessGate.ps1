[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $ToolRoot,
    [Parameter(Mandatory = $true)] [string] $ApplicationJar,
    [Parameter(Mandatory = $true)] [string] $ApplicationSourceCommit,
    [string] $RepositoryRoot = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)),
    [string] $RunId,
    [string] $BenchmarkDatabaseCatalog = "varvis_benchmark_stage2b4_wes_gate",
    [int] $TimeoutSeconds = 1800
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot "BenchmarkRealCaseRequest.ps1")
. (Join-Path $PSScriptRoot "BenchmarkCaseResult.ps1")

$GateVersion = "public-benchmark-wes-readiness-gate-v3"
$DatasetId = "synthetic-wes-telemetry-readiness-v2"
$CaseId = "synthetic-wes-case"
$SampleId = "NA12878"
$PhenopacketSubjectId = "SYNTHETIC_WES_GATE"
$Chromosome = "1"
$Position = 65400
$Reference = "A"
$Alternate = "T"
$GroupDefinitions = @(
    [ordered]@{ groupId = "G0"; hpoInputMode = "NONE"; hpoTerms = @(); provenance = "synthetic WES readiness G0" },
    [ordered]@{ groupId = "G1"; hpoInputMode = "SOURCE_PROVIDED"; hpoTerms = @("HP:0001250"); provenance = "fixed synthetic HPO input" }
)

function Get-Sha256([string] $Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-Utf8Sha256([string] $Value) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Write-JsonAtomic([object] $Value, [string] $Path, [int] $Depth = 20) {
    $temporary = "$Path.tmp"
    [IO.File]::WriteAllText($temporary, (($Value | ConvertTo-Json -Depth $Depth) + [Environment]::NewLine),
        (New-Object Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Require-File([string] $Path, [string] $Code) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw $Code }
    if ($Path -match '\.(part|crdownload|download)$') { throw "INCOMPLETE_RESOURCE_SUFFIX" }
}

function Get-BenchmarkJdbcUrl([string] $Catalog, [string] $Repository) {
    if ($Catalog -notmatch '(?i)^[a-z0-9_]*benchmark[a-z0-9_]*$') { throw "BENCHMARK_DATABASE_REQUIRED" }
    $configuration = Join-Path $Repository "varvis-server/config/database-local.properties"
    Require-File $configuration "DATABASE_CONFIGURATION_MISSING"
    $line = Get-Content -LiteralPath $configuration | Where-Object { $_ -match '^varvis\.datasource\.jdbc-url=' } | Select-Object -First 1
    if (-not $line) { throw "DATABASE_URL_MISSING" }
    $url = ($line -split '=', 2)[1]
    if ($url -notmatch '^(jdbc:mysql://[^/]+/)([^?]+)(.*)$') { throw "DATABASE_URL_UNSUPPORTED" }
    return $Matches[1] + $Catalog + $Matches[3] + ($(if ($Matches[3]) { "&" } else { "?" })) + "createDatabaseIfNotExist=true"
}

$repository = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$tool = (Resolve-Path -LiteralPath $ToolRoot).Path
$jar = (Resolve-Path -LiteralPath $ApplicationJar).Path
$samtools = Join-Path $tool "msys64/ucrt64/bin/samtools.exe"
$bcftools = Join-Path $tool "msys64/ucrt64/bin/bcftools.exe"
$tabix = Join-Path $tool "msys64/ucrt64/bin/tabix.exe"
$pheval = Join-Path $tool "benchmark/pheval/0.7.14/venv/Scripts/pheval-utils.exe"
$phevalPython = Join-Path (Split-Path -Parent $pheval) "python.exe"
$backgroundDirectory = Join-Path $tool "benchmark/1000_genomes/grch38_20190312/merged/na12878_exome_v1"
$backgroundManifestPath = Join-Path $backgroundDirectory "merged-resource-manifest.json"
$backgroundVcf = Join-Path $backgroundDirectory "NA12878.GRCh38.exome-like.chr1-22-X.vcf.gz"
$backgroundIndex = "$backgroundVcf.tbi"
$benchmarkBed = Join-Path $tool "benchmark/regions/gencode_v47_exome_v1/derived/gencode_v47_grch38_protein_coding_exon_union_pad20.bed"
$properties = Join-Path $repository "varvis-server/config/variant-analysis-local.properties"
$grch38ContigAlias = Join-Path $repository "varvis-server/config/grch38-numeric-to-ucsc-contigs.tsv"
Require-File $samtools "SAMTOOLS_MISSING"
foreach ($required in @($bcftools, $tabix, $pheval, $phevalPython, $backgroundManifestPath,
        $backgroundVcf, $backgroundIndex, $benchmarkBed)) {
    Require-File $required "SYNTHETIC_WES_RESOURCE_MISSING"
}

function Test-BedContainsPosition([string] $BedPath, [string] $Contig, [long] $OneBasedPosition) {
    foreach ($line in [IO.File]::ReadLines($BedPath)) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith("#")) { continue }
        $fields = $line.Split("`t")
        if ($fields.Count -lt 3 -or $fields[0] -ne $Contig) { continue }
        $start = 0L
        $end = 0L
        if (-not [long]::TryParse($fields[1], [ref]$start) -or
                -not [long]::TryParse($fields[2], [ref]$end)) { continue }
        if ($OneBasedPosition -gt $start -and $OneBasedPosition -le $end) { return $true }
    }
    return $false
}
Require-File $properties "LOCAL_ANALYSIS_CONFIGURATION_MISSING"
Require-File $grch38ContigAlias "GRCH38_CONTIG_ALIAS_MISSING"
$referenceLine = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^variant-analysis\.normalization\.grch38-reference=' } | Select-Object -First 1
if (-not $referenceLine) { throw "GRCH38_REFERENCE_CONFIGURATION_MISSING" }
$referenceFasta = ($referenceLine -split '=', 2)[1]
Require-File $referenceFasta "GRCH38_REFERENCE_MISSING"
$referenceFai = "$referenceFasta.fai"
Require-File $referenceFai "GRCH38_REFERENCE_FAI_MISSING"

$backgroundManifest = Get-Content -LiteralPath $backgroundManifestPath -Raw | ConvertFrom-Json
if ($backgroundManifest.status -ne "COMPLETED" -or $backgroundManifest.sampleId -ne "NA12878" -or
        $backgroundManifest.assembly -ne "GRCh38") { throw "BACKGROUND_MANIFEST_NOT_ACCEPTED" }
if ((Get-Sha256 $backgroundVcf) -ne $backgroundManifest.derived.sha256 -or
        (Get-Sha256 $backgroundIndex) -ne $backgroundManifest.derived.indexSha256) {
    throw "BACKGROUND_CHECKSUM_MISMATCH"
}
& $bcftools index -n $backgroundVcf | Out-Null
if ($LASTEXITCODE -ne 0) { throw "BACKGROUND_INDEX_INVALID" }
$backgroundSamples = @(& $bcftools query -l $backgroundVcf)
if ($LASTEXITCODE -ne 0 -or $backgroundSamples.Count -ne 1 -or $backgroundSamples[0] -ne "NA12878") {
    throw "BACKGROUND_SAMPLE_MISMATCH"
}
$phevalVersion = (& $phevalPython -c "import importlib.metadata; print(importlib.metadata.version('pheval'))").Trim()
if ($LASTEXITCODE -ne 0 -or $phevalVersion -ne "0.7.14") { throw "PHEVAL_VERSION_MISMATCH" }

$gitCommit = (& git -C $repository rev-parse HEAD).Trim()
$branch = (& git -C $repository branch --show-current).Trim()
if ($ApplicationSourceCommit -notmatch '^[0-9a-f]{40}$') { throw "INVALID_APPLICATION_SOURCE_COMMIT" }
& git -C $repository cat-file -e "$ApplicationSourceCommit`^{commit}"
if ($LASTEXITCODE -ne 0) { throw "APPLICATION_SOURCE_COMMIT_NOT_FOUND" }
$applicationArtifactSha = Get-Sha256 $jar
$workingTreeState = $(if (@(& git -C $repository status --porcelain --untracked-files=no).Count -eq 0) { "CLEAN" } else { "DIRTY" })
if (-not $RunId) { $RunId = "stage2b4-wes-gate-" + $gitCommit.Substring(0, 7) }
$runRoot = Join-Path $tool "benchmark/runs/$RunId"
if (Test-Path -LiteralPath $runRoot) { throw "WES_GATE_RUN_EXISTS" }
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$runManifestPath = Join-Path $runRoot "run-manifest-$RunId.json"
$gateManifestPath = Join-Path $runRoot "wes-readiness-gate.json"

try {
    $referenceBase = @(& $samtools faidx $referenceFasta "chr$Chromosome`:$Position-$Position") | Where-Object { $_ -notmatch '^>' } | ForEach-Object { $_.Trim() }
    if ($LASTEXITCODE -ne 0 -or (($referenceBase -join "").ToUpperInvariant() -ne $Reference)) {
        throw "SYNTHETIC_REFERENCE_MISMATCH"
    }

    $preparationDirectory = Join-Path $runRoot "preparation"
    $compatibilityDirectory = Join-Path $preparationDirectory "background"
    & (Join-Path $PSScriptRoot "New-PhevalCompatibleBackground.ps1") -InputVcf $backgroundVcf `
        -ReferenceFai $referenceFai -OutputDirectory $compatibilityDirectory `
        -BcftoolsExecutable $bcftools -TabixExecutable $tabix | Out-Null
    $phevalBackground = Join-Path $compatibilityDirectory "NA12878.GRCh38.exome-like.pheval-compatible.vcf.gz"
    Require-File $phevalBackground "PHEVAL_COMPATIBLE_BACKGROUND_MISSING"

    $phenopacketDirectory = Join-Path $preparationDirectory "phenopacket"
    New-Item -ItemType Directory -Path $phenopacketDirectory -Force | Out-Null
    $phenopacketPath = Join-Path $phenopacketDirectory "synthetic-wes-case.json"
    $phenopacket = [ordered]@{
        id = $CaseId
        subject = [ordered]@{ id = $PhenopacketSubjectId }
        phenotypicFeatures = @([ordered]@{ type = [ordered]@{ id = "HP:0001250"; label = "Seizure" } })
        interpretations = @([ordered]@{ diagnosis = [ordered]@{ genomicInterpretations = @(
            [ordered]@{ variantInterpretation = [ordered]@{ variationDescriptor = [ordered]@{
                vcfRecord = [ordered]@{ genomeAssembly = "GRCh38"; chrom = $Chromosome
                    pos = [string]$Position; ref = $Reference; alt = $Alternate }
                allelicState = [ordered]@{ id = "GENO:0000135"; label = "heterozygous" }
            } } }
        ) } })
    }
    Write-JsonAtomic $phenopacket $phenopacketPath
    $rawSpiked = Join-Path $phenopacketDirectory "synthetic-wes-case.vcf.gz"
    & $pheval create-spiked-vcfs --phenopacket-path $phenopacketPath `
        --hg38-template-vcf $phevalBackground --output-dir $phenopacketDirectory
    if ($LASTEXITCODE -ne 0) { throw "PHEVAL_SPIKE_FAILED" }
    Require-File $rawSpiked "PHEVAL_OUTPUT_MISSING"
    $inputDirectory = Join-Path $runRoot "input"
    New-Item -ItemType Directory -Path $inputDirectory -Force | Out-Null
    $inputVcf = Join-Path $inputDirectory "synthetic-wes-grch38.vcf.gz"
    & (Join-Path $PSScriptRoot "Convert-PhevalSpikedVcf.ps1") -InputVcf $rawSpiked `
        -OutputVcf $inputVcf -BcftoolsExecutable $bcftools -TabixExecutable $tabix | Out-Null
    Require-File $inputVcf "SYNTHETIC_WES_INPUT_MISSING"
    Require-File "$inputVcf.tbi" "SYNTHETIC_WES_INDEX_MISSING"
    $inputSamples = @(& $bcftools query -l $inputVcf)
    if ($LASTEXITCODE -ne 0 -or $inputSamples.Count -ne 1 -or
            [string]::IsNullOrWhiteSpace($inputSamples[0])) {
        throw "SYNTHETIC_WES_SAMPLE_MISMATCH"
    }
    $spikedSampleId = $inputSamples[0]
    $contigs = @(& $tabix -l $inputVcf)
    foreach ($contig in @((1..22 | ForEach-Object { [string]$_ }) + "X")) {
        if ($contigs -notcontains $contig) { throw "SYNTHETIC_WES_CONTIG_SCOPE_MISMATCH:$contig" }
    }
    $truthRows = @(& $bcftools query -r "$Chromosome`:$Position-$Position" -f '%CHROM\t%POS\t%REF\t%ALT\n' $inputVcf)
    $truthRow = "$Chromosome`t$Position`t$Reference`t$Alternate"
    if ($LASTEXITCODE -ne 0 -or $truthRows -notcontains $truthRow) { throw "SYNTHETIC_WES_TRUTH_MISSING" }
    if (-not (Test-BedContainsPosition $benchmarkBed $Chromosome $Position)) {
        throw "SYNTHETIC_WES_TRUTH_OUTSIDE_BENCHMARK_REGION"
    }
    $inputSha = Get-Sha256 $inputVcf
    $truthIdentity = "GRCh38|$Chromosome|$Position|$Reference|$Alternate"
    $truthSetFingerprint = Get-Utf8Sha256 $truthIdentity
    $phenopacketSha256 = Get-Sha256 $phenopacketPath
    $contigAliasSha = Get-Sha256 $grch38ContigAlias
    $benchmarkBedSha = Get-Sha256 $benchmarkBed
    $configurationFingerprint = Get-Utf8Sha256 "wes-gate-v3|$ApplicationSourceCommit|$gitCommit|$applicationArtifactSha|GRCh38|WES|$contigAliasSha|$benchmarkBedSha|$($backgroundManifest.derived.sha256)"
    $now = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    $runManifest = [ordered]@{
        schemaVersion = "benchmark-run-manifest-v2"; runId = $RunId; createdAt = $now
        datasetId = $DatasetId; datasetVersion = "1"; protocolVersion = "1.0"
        startedAt = $now; endedAt = $null; status = "RUNNING"; failureSummary = $null
        branch = $branch; applicationVersion = "0.0.1-SNAPSHOT"
        javaVersion = "1.8.0_492"; osFamily = "Windows"; rankingContractVersion = "ranking-contract-v1"
        executionProvenance = [ordered]@{
            schemaVersion = "benchmark-execution-provenance-v1"
            applicationSourceCommit = $ApplicationSourceCommit
            orchestrationSourceCommit = $gitCommit
            applicationArtifactSha256 = $applicationArtifactSha
            configurationFingerprint = $configurationFingerprint
            workingTreeState = $workingTreeState
        }
        backgroundFingerprint = $backgroundManifest.derived.sha256; truthFingerprint = Get-Utf8Sha256 $truthIdentity
        configuration = [ordered]@{
            assembly = "GRCh38"; normalizationMode = "BCFTOOLS"; normalizationProfile = "grch38-primary-chr"
            hpoOntologyVersion = "2026-06-23"; phen2GeneVersion = "configured-local"
            vepVersion = "configured-local"; clinvarVersion = "configured-local"
            gnomadVersion = "configured-local"; caddVersion = "configured-local"
            candidateCap = 20000; vepCap = 8000; featureFlags = [ordered]@{ syntheticWesReadiness = $true }
            configurationFingerprint = $configurationFingerprint
        }
        sharedConfigurationFingerprint = $configurationFingerprint
        groups = @($GroupDefinitions | ForEach-Object { [ordered]@{
            groupId = $_.groupId; caseId = $CaseId; hpoInputMode = $_.hpoInputMode
            hpoTerms = @($_.hpoTerms); hpoInputChecksum = Get-Utf8Sha256 (@($_.hpoTerms) -join "`n")
            hpoProvenance = $_.provenance; transformationProvenance = "NONE"
            configurationFingerprint = $configurationFingerprint
        } })
    }
    $executionProvenanceFingerprint = Get-Utf8Sha256 (@(
        "benchmark-execution-provenance-v1", $ApplicationSourceCommit, $gitCommit,
        $applicationArtifactSha, $configurationFingerprint, $workingTreeState) -join "`n")
    Write-JsonAtomic $runManifest $runManifestPath

    $truth = [ordered]@{ gene = "SYNTHETIC_GENE"; canonicalIdentity = [ordered]@{
        genomeAssembly = "GRCh38"; chromosome = $Chromosome; position = $Position
        reference = $Reference; alternate = $Alternate
    } }
    $databaseUrl = Get-BenchmarkJdbcUrl $BenchmarkDatabaseCatalog $repository
    $groupResults = @()
    foreach ($group in $GroupDefinitions) {
        $groupId = $group.groupId
        $resultId = "$CaseId-$groupId"
        $attemptId = "attempt-" + [guid]::NewGuid().ToString("N")
        $resultDirectory = Join-Path $runRoot "results/$groupId"
        New-Item -ItemType Directory -Path $resultDirectory -Force | Out-Null
        $hpoProvenance = @()
        foreach ($term in @($group.hpoTerms)) {
            $hpoProvenance += ([ordered]@{ hpoId = $term; source = "SYNTHETIC_FIXED"
                matchedTerm = $term; confidence = 1.0 } | ConvertTo-Json -Compress)
        }
        $request = New-BenchmarkRealCaseRequest -RunId $RunId -DatasetId $DatasetId -DatasetVersion "1" `
            -CaseId $CaseId -GroupId $groupId -ResultId $resultId -AttemptId $attemptId `
            -PhenopacketSha256 $phenopacketSha256 -TruthSetFingerprint $truthSetFingerprint `
            -ExecutionProvenanceFingerprint $executionProvenanceFingerprint `
            -ConfigurationFingerprint $configurationFingerprint `
            -InputVcf $inputVcf -InputVcfSha256 $inputSha -SampleId $spikedSampleId `
            -HpoTerms @($group.hpoTerms) -HpoProvenance $hpoProvenance `
            -TruthVariants @($truth) -RunManifest $runManifestPath `
            -TimeoutSeconds $TimeoutSeconds -ExpectedDatabaseCatalog $BenchmarkDatabaseCatalog `
            -OutputDirectory $resultDirectory
        $requestPath = Join-Path $runRoot "requests/$groupId.json"
        New-Item -ItemType Directory -Path (Split-Path -Parent $requestPath) -Force | Out-Null
        Write-JsonAtomic $request $requestPath

        $storageRoot = Join-Path $runRoot "workspace/$groupId"
        New-Item -ItemType Directory -Path $storageRoot -Force | Out-Null
        $stdoutPath = Join-Path $resultDirectory "stdout.log"
        $stderrPath = Join-Path $resultDirectory "stderr.log"
        $arguments = @("-jar", $jar, "--server.port=0", "--varvis.datasource.jdbc-url=$databaseUrl",
            "--variant-analysis.storage-root=$storageRoot", "--variant-analysis.retention=PT168H",
            "--variant-analysis.normalization.grch38-contig-alias-file=$grch38ContigAlias",
            "--varvis.benchmark.real-case.enabled=true", "--varvis.benchmark.real-case.request-file=$requestPath")
        $process = Start-Process -FilePath "java" -ArgumentList $arguments -Wait -PassThru -NoNewWindow `
            -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath

        $resultPath = Join-Path $resultDirectory "case-result-$resultId.json"
        Require-File $resultPath "SYNTHETIC_WES_RESULT_MISSING"
        $result = Read-BenchmarkTerminalCaseResult -Path $resultPath -Request $request `
            -ExpectedTruthIdentities @($truthIdentity) -ProcessExitCode $process.ExitCode `
            -StdoutPath $stdoutPath -StderrPath $stderrPath `
            -AttemptProvenancePath (Join-Path $resultDirectory "attempt-provenance.json")
        $match = @($result.truthMatches)
        if ($result.caseExecutionStatus -notin @("COMPLETED", "COMPLETED_WITH_FAILURES")) { throw "SYNTHETIC_WES_TERMINAL_STATE_INVALID" }
        if ([string]::IsNullOrWhiteSpace($result.analysisReference)) { throw "SYNTHETIC_WES_ANALYSIS_REFERENCE_MISSING" }
        if ($match.Count -ne 1 -or $match[0].rankStatus -ne "RANKED" -or [int]$match[0].rank -lt 1) { throw "SYNTHETIC_WES_RANK_INVALID" }
        if ([int]$match[0].rankingUniverseSize -lt [int]$match[0].rank) { throw "SYNTHETIC_WES_UNIVERSE_INVALID" }
        $persisted = @($result.stageRetention | Where-Object { $_.stage -eq "PERSISTED_RANKING_UNIVERSE" -and $_.presence -eq "PRESENT" })
        if ($persisted.Count -ne 1) { throw "SYNTHETIC_WES_PERSISTENCE_EVIDENCE_MISSING" }

        $telemetryPath = Join-Path $resultDirectory $result.telemetryArtifact.logicalFileName
        $telemetry = Get-Content -LiteralPath $telemetryPath -Raw | ConvertFrom-Json
        if ($telemetry.schemaVersion -ne "benchmark-analysis-telemetry-v1" -or
                $telemetry.status -ne "COMPLETED" -or $telemetry.attemptId -ne $attemptId -or
                $telemetry.requestFingerprint -ne $request.requestFingerprint -or
                $telemetry.analysisReference -ne $result.analysisReference -or
                $telemetry.configurationFingerprint -ne $configurationFingerprint) {
            throw "SYNTHETIC_WES_TELEMETRY_BINDING_INVALID"
        }
        if ([int]$telemetry.candidateHardLimit -ne 20000 -or [int]$telemetry.vepCandidateCap -ne 8000) {
            throw "SYNTHETIC_WES_TELEMETRY_CAP_INVALID"
        }
        $counts = $telemetry.counts
        if ([int]$counts.cheapGateEligible.value -ne
                ([int]$counts.retainedAfterHardCap.value + [int]$counts.releasedByHardCap.value)) {
            throw "SYNTHETIC_WES_TELEMETRY_COUNT_INVARIANT_FAILED"
        }
        if ([int]$counts.persistedInterpretations.value -ne [int]$match[0].rankingUniverseSize -or
                [int]$counts.rankedInterpretations.value -ne [int]$match[0].rankingUniverseSize) {
            throw "SYNTHETIC_WES_TELEMETRY_UNIVERSE_MISMATCH"
        }
        $truthTelemetry = @($telemetry.truths)
        if ($truthTelemetry.Count -ne 1 -or
                $truthTelemetry[0].stages.PARSED.status -ne "PRESENT" -or
                $truthTelemetry[0].stages.NORMALIZATION_RESULT.status -ne "PRESENT" -or
                $truthTelemetry[0].stages.RETAINED_AFTER_HARD_CAP.status -ne "PRESENT" -or
                $truthTelemetry[0].stages.PERSISTED_INTERPRETATION.status -ne "PRESENT" -or
                $truthTelemetry[0].stages.RANKED_STATUS.status -ne "RANKED") {
            throw "SYNTHETIC_WES_TRUTH_RETENTION_INCOMPLETE"
        }
        $resultSha = Get-Sha256 $resultPath
        $resume = Read-BenchmarkTerminalCaseResult -Path $resultPath -Request $request `
            -ExpectedTruthIdentities @($truthIdentity) -ProcessExitCode 0 `
            -AttemptProvenancePath (Join-Path $resultDirectory "attempt-provenance.json") -ResumeValidation
        if ($resume.analysisReference -ne $result.analysisReference -or (Get-Sha256 $resultPath) -ne $resultSha) {
            throw "SYNTHETIC_WES_RESUME_NOT_IDEMPOTENT"
        }
        $groupResults += [ordered]@{
            groupId = $groupId; hpoTerms = @($group.hpoTerms); terminalState = $result.caseExecutionStatus
            analysisReference = $result.analysisReference; rankStatus = $match[0].rankStatus
            rank = $match[0].rank; rankingUniverseSize = $match[0].rankingUniverseSize
            telemetrySha256 = $result.telemetryArtifact.sha256; resultSha256 = $resultSha
            candidateCounts = $counts; truthStages = $truthTelemetry[0].stages
        }
    }

    $runManifest.status = "COMPLETED"
    $runManifest.endedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
    Write-JsonAtomic $runManifest $runManifestPath
    $gate = [ordered]@{
        schemaVersion = $GateVersion; status = "COMPLETED"; runId = $RunId
        applicationSourceCommit = $ApplicationSourceCommit; orchestrationSourceCommit = $gitCommit
        applicationArtifactSha256 = $applicationArtifactSha; workingTreeState = $workingTreeState
        datasetId = $DatasetId; caseId = $CaseId; groups = $groupResults; analysisMode = "WES"
        assembly = "GRCh38"; backgroundSampleId = $SampleId; analysisSampleId = $spikedSampleId
        truthCanonicalIdentity = $truthIdentity
        hpoDifference = [ordered]@{ onlyAllowedDifference = "HPO_INPUT"
            g0Terms = @(); g1Terms = @("HP:0001250"); sameInputVcfSha256 = $inputSha }
        rankingContractVersion = "ranking-contract-v1"; persistedInterpretationRequired = $true
        backgroundSha256 = $backgroundManifest.derived.sha256; inputVcfSha256 = $inputSha
        configurationFingerprint = $configurationFingerprint; contigAliasSha256 = $contigAliasSha
        databaseIsolation = "DEDICATED_BENCHMARK_CATALOG"; resumeValidation = "PASSED"
        phevalVersion = $phevalVersion
    }
    Write-JsonAtomic $gate $gateManifestPath
    foreach ($temporaryName in @("workspace", "preparation")) {
        $temporaryPath = Join-Path $runRoot $temporaryName
        if (Test-Path -LiteralPath $temporaryPath) {
            Remove-Item -LiteralPath $temporaryPath -Recurse -Force
        }
    }
    $gate | ConvertTo-Json -Depth 10
} catch {
    if (Test-Path -LiteralPath $runManifestPath) {
        $failed = Get-Content -LiteralPath $runManifestPath -Raw | ConvertFrom-Json
        $failed.status = "FAILED"
        $failed.endedAt = [DateTime]::UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
        $failed.failureSummary = ([string]$_.Exception.Message -split '[:(]', 2)[0].Trim()
        Write-JsonAtomic $failed $runManifestPath
    }
    throw
}
