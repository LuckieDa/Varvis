param(
    [Parameter(Mandatory = $true)][string]$RuntimeRoot,
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$codeRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$runtime = (Resolve-Path -LiteralPath $RuntimeRoot).Path
$source = Join-Path $runtime 'demo-overrides.properties'
$target = Join-Path $codeRoot 'varvis-server/config/variant-analysis-local.properties'
$values = @{}
foreach ($line in Get-Content -LiteralPath $source -Encoding UTF8) {
    if ($line -and !$line.StartsWith('#') -and $line.Contains('=')) {
        $pair = $line.Split('=', 2)
        $values[$pair[0]] = $pair[1]
    }
}
$keys = @(
    'variant-analysis.acmg-business.enabled',
    'variant-analysis.acmg-business.binding-directory',
    'variant-analysis.acmg-business.resource-root',
    'variant-analysis.acmg-business.reference-receipt-file',
    'variant-analysis.acmg-business.reference-receipt-sha256',
    'variant-analysis.acmg-business.reference-receipt-locator',
    'variant-analysis.vep.enabled',
    'variant-analysis.vep.execution-enabled',
    'variant-analysis.vep.resources.GRCh38.enabled'
)
foreach ($key in $keys) {
    if ([string]::IsNullOrWhiteSpace($values[$key])) { throw "Missing teaching setting: $key" }
}
foreach ($key in @($keys[0], $keys[6], $keys[7], $keys[8])) {
    if ($values[$key] -ne 'true') { throw "Teaching prerequisite must be enabled: $key" }
}
$receipt = $values['variant-analysis.acmg-business.reference-receipt-file']
if ((Get-FileHash -LiteralPath $receipt -Algorithm SHA256).Hash.ToLowerInvariant() -ne
    $values['variant-analysis.acmg-business.reference-receipt-sha256']) {
    throw 'Reference receipt checksum mismatch'
}
foreach ($asset in @(
    (Join-Path $values['variant-analysis.acmg-business.binding-directory'] 'manifest.json'),
    (Join-Path $values['variant-analysis.acmg-business.resource-root'] 'Tool/m3/alphamissense_hg38.tabix.tsv.gz'),
    (Join-Path $values['variant-analysis.acmg-business.resource-root'] 'Tool/vep/bin/varvis-vep.cmd')
)) {
    if (!(Test-Path -LiteralPath $asset -PathType Leaf)) { throw 'Required local teaching resource is missing' }
}
if ($ValidateOnly) { Write-Output 'PASS: explicit ACMG/VEP settings and local teaching prerequisites'; exit 0 }
$backup = Join-Path $runtime ('variant-analysis-before-acmg-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.properties')
Copy-Item -LiteralPath $target -Destination $backup
$lines = [IO.File]::ReadAllLines($target)
$retained = @($lines | Where-Object {
    $key = ($_ -split '=', 2)[0].Trim()
    $keys -notcontains $key
})
$retained += '# Explicit teaching ACMG/VEP activation; database settings are maintained separately.'
$retained += $keys | ForEach-Object { $_ + '=' + $values[$_] }
[IO.File]::WriteAllLines($target, $retained, (New-Object Text.UTF8Encoding($false)))
Write-Output 'Configured 9 explicit ACMG/VEP settings in the existing ignored local configuration.'
Write-Output 'Database, storage and Exomiser ranking settings are unchanged. Restart the local backend and submit a new analysis.'
