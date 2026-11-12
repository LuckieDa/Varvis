[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ReviewDirectory,
    [Parameter(Mandatory = $true)][string]$InternalMappingPath,
    [Parameter(Mandatory = $true)][string]$InputPlanPath,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [Parameter(Mandatory = $true)][ValidatePattern('^\d{4}-\d{2}-\d{2}$')][string]$ReviewDate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-Sha256([string]$Path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant() }
function Get-TermChecksum([object[]]$Terms) {
    $text = [string]::Join("`n", @($Terms | Sort-Object))
    $bytes = [Text.Encoding]::UTF8.GetBytes($text)
    ([Security.Cryptography.SHA256]::Create().ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''
}
function Write-AtomicJson([string]$Path, [object]$Value) {
    $json = $Value | ConvertTo-Json -Depth 20
    $temporary = "$Path.temporary-$([Guid]::NewGuid().ToString('N'))"
    [IO.File]::WriteAllText($temporary, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    if (Test-Path -LiteralPath $Path) {
        if ((Get-Content -LiteralPath $Path -Raw) -ne (Get-Content -LiteralPath $temporary -Raw)) { throw "REVIEW_OUTPUT_CONFLICT: $Path" }
        Remove-Item -LiteralPath $temporary -Force
    } else { Move-Item -LiteralPath $temporary -Destination $Path }
}

foreach ($path in @($ReviewDirectory, $InternalMappingPath, $InputPlanPath)) { if (-not (Test-Path -LiteralPath $path)) { throw "REVIEW_INPUT_MISSING: $path" } }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$mapping = Get-Content -LiteralPath $InternalMappingPath -Raw | ConvertFrom-Json
if ($mapping.handling -ne 'DO_NOT_PROVIDE_TO_REVIEWER') { throw 'REVIEW_MAPPING_HANDLING_INVALID' }
$plan = Get-Content -LiteralPath $InputPlanPath -Raw | ConvertFrom-Json
$reviewProvenance = [ordered]@{
    reviewMethod = 'TRUTH_MASKED_DEVELOPMENT_REVIEW'; priorTruthExposure = 'REPORT_PREVIOUSLY_DISPLAYED'
    activeTruthReviewReported = $false; eligibleForStrictBlindedEvaluation = $false
}
$aiAssistance = [ordered]@{
    aiAssistanceUsed = $true; assistanceScope = 'HPO_ID_LABEL_AND_INTERNAL_CONSISTENCY_CHECK'
    humanFinalConfirmationRequired = $true; humanFinalConfirmation = $true
    provider = 'OpenAI ChatGPT'; modelIdentifier = 'NOT_RECORDED'
    sourceHpoOntologyVersion = 'UNAVAILABLE_IN_FROZEN_MANIFEST'; semanticReferenceHpoVersion = '2026-06-23'
    semanticReferenceSource = 'https://purl.obolibrary.org/obo/hp.obo'
}
$entries = New-Object System.Collections.Generic.List[object]
foreach ($map in @($mapping.mappings | Sort-Object reviewId)) {
    $packetPath = Join-Path $ReviewDirectory ($map.reviewId + '.packet.json')
    if (-not (Test-Path -LiteralPath $packetPath)) { throw "REVIEW_PACKET_MISSING: $($map.reviewId)" }
    $packet = Get-Content -LiteralPath $packetPath -Raw | ConvertFrom-Json
    if ($packet.packetFingerprint -ne $map.packetFingerprint) { throw "REVIEW_PACKET_FINGERPRINT_MISMATCH: $($map.reviewId)" }
    $termDecisions = @($packet.terms | ForEach-Object {
        [ordered]@{ hpoId = $_.hpoId; originalStatus = $_.originalStatus; decision = if ($_.originalStatus -eq 'OBSERVED') { 'KEEP_OBSERVED' } else { 'KEEP_EXCLUDED' }; mappingErrorReason = $null }
    })
    $notes = @()
    if ($packet.terms | Where-Object { $_.hpoId -eq 'HP:0009098' -and $_.originalStatus -eq 'EXCLUDED' }) {
        $notes += [ordered]@{ hpoId = 'HP:0009098'; sourceLabel = 'Chronic oral candidiasis'; canonicalLabelAtSemanticReference = 'Recurrent oral thrush'; relationship = 'EXACT_SYNONYM'; decisionImpact = 'NONE' }
    }
    $decision = [ordered]@{
        schemaVersion = 'truth-masked-hpo-review-decision-v1'; reviewProtocolVersion = $packet.reviewProtocolVersion
        reviewId = $packet.reviewId; packetFingerprint = $packet.packetFingerprint; packetContentChecksum = $packet.packetContentChecksum
        reviewMethod = $reviewProvenance.reviewMethod; reviewerRole = 'PROJECT_RESEARCHER'; priorTruthExposure = $reviewProvenance.priorTruthExposure
        activeTruthReviewReported = $false; eligibleForStrictBlindedEvaluation = $false; sourceContext = $packet.sourceContext
        aiAssistance = $aiAssistance; semanticAuditNotes = $notes; reviewCompletionStatus = 'READY_IDENTICAL_TO_G1'; reviewDate = $ReviewDate
        termDecisions = $termDecisions; additions = @(); decisionCounts = [ordered]@{ totalOriginalTerms = $termDecisions.Count; completedOriginalTermDecisions = $termDecisions.Count; additions = 0 }
    }
    $decisionPath = Join-Path $OutputDirectory ($packet.reviewId + '.decision.json')
    Write-AtomicJson -Path $decisionPath -Value $decision
    $observed = @($packet.terms | Where-Object originalStatus -eq 'OBSERVED' | ForEach-Object hpoId | Sort-Object)
    $excluded = @($packet.terms | Where-Object originalStatus -eq 'EXCLUDED' | ForEach-Object hpoId | Sort-Object)
    $entries.Add([ordered]@{ caseId = $map.caseId; reviewId = $packet.reviewId; packetFingerprint = $packet.packetFingerprint; decisionSha256 = Get-Sha256 $decisionPath; readiness = 'READY_IDENTICAL_TO_G1'; observedHpoTerms = $observed; excludedHpoTerms = $excluded; hpoInputChecksum = Get-TermChecksum $observed; reviewProvenance = $reviewProvenance; aiAssistance = $aiAssistance; semanticAuditNotes = $notes; g1G2Equivalence = 'OBSERVED_HPO_IDENTICAL_TO_G1' })
}
if ($entries.Count -ne 5) { throw 'REVIEW_CASE_COUNT_INVALID' }
$reviewedPlan = $plan | ConvertTo-Json -Depth 20 | ConvertFrom-Json
foreach ($case in @($reviewedPlan.cases)) {
    $entry = @($entries | Where-Object caseId -eq $case.caseId)
    if ($entry.Count -ne 1) { throw "G2_CASE_MAPPING_INVALID: $($case.caseId)" }
    $g1 = @($case.groups | Where-Object groupId -eq 'G1'); $g2 = @($case.groups | Where-Object groupId -eq 'G2')
    if ($g1.Count -ne 1 -or $g2.Count -ne 1) { throw "G2_GROUP_MISSING: $($case.caseId)" }
    if ((@($g1[0].hpoTerms | Sort-Object) -join '|') -ne (@($entry[0].observedHpoTerms | Sort-Object) -join '|')) { throw "G1_G2_EQUIVALENCE_FAILED: $($case.caseId)" }
    $g2[0].status = 'READY_IDENTICAL_TO_G1'; $g2[0].hpoTerms = @($entry[0].observedHpoTerms); $g2[0].hpoInputChecksum = $entry[0].hpoInputChecksum
    $g2[0].hpoProvenance = 'human-reviewed source HPO; truth-masked development review; G2 identical to G1'
    $g2[0] | Add-Member -NotePropertyName reviewId -NotePropertyValue $entry[0].reviewId
    $g2[0] | Add-Member -NotePropertyName packetFingerprint -NotePropertyValue $entry[0].packetFingerprint
    $g2[0] | Add-Member -NotePropertyName decisionSha256 -NotePropertyValue $entry[0].decisionSha256
}
$reviewedPlan.executable = $false; $reviewedPlan.publicBenchmarkExecuted = $false
$g2Manifest = [ordered]@{ schemaVersion = 'reviewed-g2-input-manifest-v1'; status = 'READY_IDENTICAL_TO_G1'; datasetId = $plan.datasetId; sourceInputPlanSchemaVersion = $plan.schemaVersion; reviewDate = $ReviewDate; cases = @($entries.ToArray()); g1G2Equivalence = 'ALL_FIVE_OBSERVED_HPO_INPUTS_IDENTICAL'; publicBenchmarkExecuted = $false }
Write-AtomicJson -Path (Join-Path $OutputDirectory 'g2-reviewed-input-manifest.json') -Value $g2Manifest
Write-AtomicJson -Path (Join-Path $OutputDirectory 'g0-g1-g2-reviewed-input-plan.json') -Value $reviewedPlan
[ordered]@{ status = 'COMPLETED'; reviewCount = $entries.Count; outputDirectory = (Split-Path -Leaf $OutputDirectory); decisionFiles = @($entries | ForEach-Object { $_.reviewId + '.decision.json' }); g2Status = 'READY_IDENTICAL_TO_G1' } | ConvertTo-Json -Depth 8
