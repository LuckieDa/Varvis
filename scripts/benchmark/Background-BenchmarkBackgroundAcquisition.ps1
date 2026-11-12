[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ResourceRoot,
    [Parameter(Mandatory = $true)][string]$BedPath,
    [Parameter(Mandatory = $true)][string]$CurlPath,
    [string]$CaBundlePath = '',
    [Parameter(Mandatory = $true)][string]$BcftoolsPath,
    [Parameter(Mandatory = $true)][string]$BgzipPath,
    [Parameter(Mandatory = $true)][string]$TabixPath,
    [Parameter(Mandatory = $true)][string]$StatePath,
    [Parameter(Mandatory = $true)][string]$WorkerOutPath,
    [Parameter(Mandatory = $true)][string]$WorkerErrorPath,
    [int]$HeartbeatSeconds = 60,
    [ValidateRange(1, 16)]
    [int]$SegmentCount = 4
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$order = @('1','2','3','4','5','6','7','8','9','10','11','12','13','14','15','16','17','18','19','20','21','X')
$pipeline = Join-Path $PSScriptRoot 'Invoke-BenchmarkBackgroundAcquisition.ps1'
$manifest = Get-Content (Join-Path $PSScriptRoot 'resources\1000-genomes-grch38-20190312-biallelic.manifest.json') -Raw | ConvertFrom-Json
$startedAt = [DateTime]::UtcNow.ToString('o')
$worker = $null

function Write-State {
    param([string]$Status, [string]$LastError = '')
    $completed = New-Object System.Collections.Generic.List[string]
    [Int64]$acceptedBytes = 0
    [int]$retries = 0
    foreach ($chromosome in $order) {
        $root = Join-Path $ResourceRoot ("chr" + $chromosome.ToLowerInvariant())
        $derivedManifest = Join-Path $root 'derived\na12878_exome_v1\derived-resource-manifest.json'
        if (Test-Path -LiteralPath $derivedManifest -PathType Leaf) {
            $derived = Get-Content -LiteralPath $derivedManifest -Raw | ConvertFrom-Json
            if ($derived.status -eq 'COMPLETED') { $completed.Add($chromosome) }
        }
        $downloadManifest = Join-Path $ResourceRoot "download-run-manifest.chr$($chromosome.ToLowerInvariant()).json"
        if (Test-Path -LiteralPath $downloadManifest -PathType Leaf) {
            $download = Get-Content -LiteralPath $downloadManifest -Raw | ConvertFrom-Json
            foreach ($resource in @($download.resources)) {
                if ($null -ne $resource.vcf.result.identity.bytes) { $acceptedBytes += [Int64]$resource.vcf.result.identity.bytes }
                if ($null -ne $resource.index.result.identity.bytes) { $acceptedBytes += [Int64]$resource.index.result.identity.bytes }
                if ($null -ne $resource.vcf.result.attempts -and [int]$resource.vcf.result.attempts -gt 0) { $retries += [int]$resource.vcf.result.attempts - 1 }
                if ($null -ne $resource.index.result.attempts -and [int]$resource.index.result.attempts -gt 0) { $retries += [int]$resource.index.result.attempts - 1 }
            }
        }
    }
    $part = @(Get-ChildItem -LiteralPath $ResourceRoot -Filter '*.part' -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1)
    [Int64]$partBytes = if ($part.Count -eq 1) { $part[0].Length } else { 0 }
    $current = if ($part.Count -eq 1 -and $part[0].Directory.Name -match '^chr(.+)$') { $Matches[1].ToUpperInvariant() } else { @($order | Where-Object { $completed -notcontains $_ } | Select-Object -First 1) }
    if ($current -is [array]) { $current = if ($current.Count -gt 0) { $current[0] } else { $null } }
    $state = [ordered]@{
        schemaVersion = 'benchmark-background-process-state-v1'
        status = $Status
        pid = $PID
        workerPid = if ($null -ne $worker) { $worker.Id } else { $null }
        startedAt = $startedAt
        heartbeatAt = [DateTime]::UtcNow.ToString('o')
        currentChromosome = $current
        completedChromosomes = @($completed.ToArray())
        acceptedBytes = $acceptedBytes
        currentPartBytes = $partBytes
        downloadedBytes = $acceptedBytes + $partBytes
        retryCount = $retries
        resumablePart = if ($part.Count -eq 1) { $part[0].Name } else { $null }
        lastError = $LastError
    }
    $temporary = "$StatePath.temporary-$([Guid]::NewGuid().ToString('N'))"
    [IO.File]::WriteAllText($temporary, ($state | ConvertTo-Json -Depth 8) + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $StatePath -Force
}

try {
    Write-State -Status 'STARTING'
    $arguments = @(
        '-NoProfile', '-File', $pipeline,
        '-ResourceRoot', $ResourceRoot, '-BedPath', $BedPath,
        '-CurlPath', $CurlPath, '-CaBundlePath', $CaBundlePath, '-BcftoolsPath', $BcftoolsPath,
        '-BgzipPath', $BgzipPath, '-TabixPath', $TabixPath,
        '-SegmentCount', ([string]$SegmentCount)
    )
    $worker = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $WorkerOutPath -RedirectStandardError $WorkerErrorPath
    while (-not $worker.HasExited) {
        Write-State -Status 'RUNNING'
        Start-Sleep -Seconds $HeartbeatSeconds
        $worker.Refresh()
    }
    if ($worker.ExitCode -ne 0) {
        $lastError = if (Test-Path -LiteralPath $WorkerErrorPath) { (Get-Content $WorkerErrorPath | Select-Object -Last 5) -join ' ' } else { "Worker exit $($worker.ExitCode)" }
        Write-State -Status 'FAILED' -LastError $lastError
        exit $worker.ExitCode
    }
    Write-State -Status 'COMPLETED'
} catch {
    Write-State -Status 'FAILED' -LastError $_.Exception.Message
    exit 1
}
