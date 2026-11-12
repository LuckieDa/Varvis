param(
    [Parameter(Mandatory=$true)][string]$Java,
    [Parameter(Mandatory=$true)][string]$ClassPathFile,
    [Parameter(Mandatory=$true)][string]$Config,
    [Parameter(Mandatory=$true)][string]$ResourceRoot,
    [string]$InstanceRoot,
    [string]$SourceHead,
    [switch]$Restart,
    [switch]$UpgradeSource,
    [switch]$Joint,
    [string]$AcceptanceRoot,
    [string]$ConfigOverlay,
    [string]$BuildDirectory,
    [string]$RestoreDatabase,
    [string]$RestoreDatabaseSha256,
    [string]$PublishedJobsRoot,
    [ValidateRange(0,65535)][int]$Port = 0,
    [switch]$ReadOnlyPage,
    [switch]$JointReferenceEvaluation
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$serverRoot = Join-Path $repoRoot 'varvis-server'
$privateRoot = Join-Path $serverRoot 'target/ps1-pm5-private'
$networkRoot = Join-Path $serverRoot 'target/ps1-pm5-network'
if ($Joint) {
    if (-not $AcceptanceRoot) { throw 'Joint mode requires an explicit task-owned AcceptanceRoot outside every worktree.' }
    $acceptancePath = [IO.Path]::GetFullPath($AcceptanceRoot)
    foreach ($line in (& git -C $repoRoot worktree list --porcelain)) {
        if ($line.StartsWith('worktree ')) {
            $worktreePath = [IO.Path]::GetFullPath($line.Substring(9))
            if ($acceptancePath -eq $worktreePath -or $acceptancePath.StartsWith($worktreePath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Joint AcceptanceRoot must survive worktree cleanup.'
            }
        }
    }
    if ((Test-Path -LiteralPath $acceptancePath) -and -not $Restart) { throw 'Joint AcceptanceRoot must be new; existing evidence is never overwritten.' }
    $privateRoot = Join-Path $acceptancePath 'private'
    $networkRoot = Join-Path $acceptancePath 'network'
}
$port = if ($Port -gt 0) { $Port } elseif ($Joint) { 18227 } else { 18217 }
if ($ReadOnlyPage -and (-not $Joint -or -not $RestoreDatabase)) { throw 'ReadOnlyPage requires Joint mode and a verified saved database.' }
if ($JointReferenceEvaluation -and (-not $Joint -or -not $RestoreDatabase -or $ReadOnlyPage)) { throw 'JointReferenceEvaluation requires writable Joint evaluation over verified published sources.' }
$head = (& git -C $repoRoot rev-parse HEAD).Trim()
$branch = (& git -C $repoRoot branch --show-current).Trim()
if ($LASTEXITCODE -ne 0 -or $head -notmatch '^[a-f0-9]{40}$' -or (-not $Joint -and $branch -notlike 'feature/ps1-pm5*')) {
    throw 'Use the dedicated committed PS1/PM5 branch worktree.'
}
if ($Joint) {
    & git -C $repoRoot diff --quiet HEAD -- varvis-server/src scripts/ps1_pm5
    if ($LASTEXITCODE -ne 0) { throw 'Joint runtime and launcher must be committed before launch.' }
}
if ($SourceHead) {
    if ($SourceHead -notmatch '^[a-f0-9]{40}$') { throw 'SourceHead must be a full committed SHA.' }
    & git -C $repoRoot merge-base --is-ancestor $SourceHead HEAD
    if ($LASTEXITCODE -ne 0) { throw 'SourceHead must belong to this branch history.' }
    & git -C $repoRoot diff --quiet $SourceHead -- varvis-server/src scripts/ps1_pm5/Start-Ps1Pm5Network.ps1
    if ($LASTEXITCODE -ne 0) { throw 'Runtime sources differ from SourceHead; use a new validated instance.' }
    $head = $SourceHead
}
$javaPath = (Resolve-Path -LiteralPath $Java).Path
$classpathPath = (Resolve-Path -LiteralPath $ClassPathFile).Path
$configPath = (Resolve-Path -LiteralPath $Config).Path
$resourcePath = (Resolve-Path -LiteralPath $ResourceRoot).Path
if (-not $Joint -and -not $configPath.StartsWith($privateRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Configuration must belong to this worktree target/ps1-pm5-private.'
}
if (-not $InstanceRoot) { $InstanceRoot = Join-Path $networkRoot ('instance-' + [guid]::NewGuid().ToString()) }
$instancePath = [IO.Path]::GetFullPath($InstanceRoot)
if ([IO.Path]::GetDirectoryName($instancePath) -ne $networkRoot -or [IO.Path]::GetFileName($instancePath) -notmatch '^instance-[a-f0-9-]{36}$') {
    throw 'InstanceRoot must be a direct instance-UUID under this worktree target/ps1-pm5-network.'
}
if ((Test-Path -LiteralPath $instancePath) -and -not $Restart) { throw 'Existing instance requires explicit -Restart.' }
if ($Restart -and -not (Test-Path -LiteralPath (Join-Path $instancePath 'instance-manifest.json'))) { throw 'Restart requires the original identity manifest.' }
if ($UpgradeSource) {
    if (-not $Joint -or -not $Restart) { throw 'Source upgrade requires an existing isolated joint instance.' }
    $original = Get-Content -Raw -LiteralPath (Join-Path $instancePath 'instance-manifest.json') | ConvertFrom-Json
    & git -C $repoRoot merge-base --is-ancestor $original.sourceHead $head
    if ($LASTEXITCODE -ne 0 -or $original.sourceHead -eq $head) { throw 'Source upgrade must advance the recorded committed source.' }
}
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $port)
try { $listener.Start() } finally { $listener.Stop() }
$dependencies = (Get-Content -LiteralPath $classpathPath -Raw).Trim()
if (-not $dependencies) { throw 'An existing Maven test-scope dependency classpath file is required; this launcher does not build.' }
$buildPath = if ($BuildDirectory) { (Resolve-Path -LiteralPath $BuildDirectory).Path } else { Join-Path $serverRoot 'target' }
$testClasses = Join-Path $buildPath 'test-classes'
$mainClasses = Join-Path $buildPath 'classes'
foreach ($classes in @($testClasses, $mainClasses)) {
    if (-not (Test-Path -LiteralPath $classes -PathType Container)) { throw 'The selected build directory must contain compiled classes and test-classes.' }
}
$classpath = $testClasses + ';' + $mainClasses + ';' + $dependencies
if ($Joint) {
    if (-not $Restart) {
        [IO.Directory]::CreateDirectory($privateRoot) | Out-Null
        [IO.Directory]::CreateDirectory($networkRoot) | Out-Null
        Copy-Item -LiteralPath $configPath -Destination (Join-Path $privateRoot 'source.properties')
        $effective = [IO.File]::ReadAllBytes($configPath)
        if ($ConfigOverlay) {
            $overlayPath = (Resolve-Path -LiteralPath $ConfigOverlay).Path
            Copy-Item -LiteralPath $overlayPath -Destination (Join-Path $privateRoot 'overlay.properties')
            $effective = $effective + [Text.Encoding]::ASCII.GetBytes("`n") + [IO.File]::ReadAllBytes($overlayPath)
        }
        [IO.File]::WriteAllBytes((Join-Path $privateRoot 'runtime.properties'), $effective)
        [ordered]@{ sourceConfig=$configPath; sourceSha256=(Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash.ToLowerInvariant();
            effectiveSha256=(Get-FileHash -LiteralPath (Join-Path $privateRoot 'runtime.properties') -Algorithm SHA256).Hash.ToLowerInvariant();
            resourceRoot=$resourcePath; note='Resource receipts remain exact; only explicit overlay and enforced private output settings differ.' } |
            ConvertTo-Json | Set-Content -LiteralPath (Join-Path $privateRoot 'configuration-origin.json') -Encoding UTF8
    }
    $configPath = (Resolve-Path -LiteralPath (Join-Path $privateRoot 'runtime.properties')).Path
}
$launchId = [guid]::NewGuid().ToString()
$argumentFile = Join-Path $privateRoot ('network-java-' + $launchId + '.args')
$arguments = @('-Xms256m', '-Xmx4g', '-Dfile.encoding=UTF-8', '-Dspring.devtools.restart.enabled=false', '-cp', $classpath,
    'org.nbscn.varvis.ps1pm5acceptance.Ps1Pm5NetworkApplication',
    '--config', $configPath, '--root', $instancePath, '--resource-root', $resourcePath,
    '--head', $head, '--branch', $branch, '--port', [string]$port)
if ($Joint) { $arguments += @('--mode', 'JOINT', '--acceptance-root', $acceptancePath) }
if ($Restart) { $arguments += @('--restart', 'true') }
if ($UpgradeSource) { $arguments += @('--upgrade-source', 'true') }
if ($ReadOnlyPage) { $arguments += @('--read-only-page', 'true') }
if ($JointReferenceEvaluation) { $arguments += @('--joint-reference-evaluation', 'true') }
if ($RestoreDatabase) {
    if (-not $Joint -or -not $PublishedJobsRoot -or $RestoreDatabaseSha256 -notmatch '^[a-f0-9]{64}$') {
        throw 'Published-artifact reuse requires Joint mode, original logical jobs root and verified database SHA-256.'
    }
    $arguments += @('--restore-database', $RestoreDatabase, '--restore-database-sha256', $RestoreDatabaseSha256,
        '--published-jobs-root', $PublishedJobsRoot)
}
$encoded = $arguments | ForEach-Object { '"' + $_.Replace('\', '\\').Replace('"', '\"') + '"' }
[IO.File]::WriteAllLines($argumentFile, $encoded, [Text.UTF8Encoding]::new($false))
$stdout = Join-Path $privateRoot ('network-' + $launchId + '.stdout.log')
$stderr = Join-Path $privateRoot ('network-' + $launchId + '.stderr.log')
$process = Start-Process -FilePath $javaPath -ArgumentList ('@"' + $argumentFile + '"') -WorkingDirectory $serverRoot -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
$receipt = [ordered]@{ processId=$process.Id; sourceHead=$head; branch=$branch; instanceRoot=$instancePath;
    identityUrl="http://127.0.0.1:$port/__varvis_ps1pm5_instance"; mode=$(if ($Joint) { 'JOINT' } else { 'PS1_PM5' }); stdout=$stdout; stderr=$stderr;
    buildDirectory=$buildPath;
    status='LAUNCHED_NOT_YET_VERIFIED'; restart=[bool]$Restart }
$receiptPath = Join-Path $privateRoot ('network-launch-' + $launchId + '.json')
$receipt | ConvertTo-Json | Set-Content -LiteralPath $receiptPath -Encoding UTF8
$receipt | ConvertTo-Json
