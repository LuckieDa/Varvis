param(
    [string]$DependencyCache
)
$ErrorActionPreference = 'Stop'
$client = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repo = [IO.Path]::GetFullPath((Join-Path $client '..'))
if (-not $DependencyCache) {
    $DependencyCache = Join-Path $repo 'local/cache/varvis-client-pro/node_modules'
}
$branch = (& git -C $repo branch --show-current).Trim()
if ($branch -notmatch '^feature/ps1-pm5(?:-|$)') { throw "Unexpected branch: $branch" }
$sourceHead = (& git -C $repo rev-parse HEAD).Trim()
$cache = (Resolve-Path -LiteralPath $DependencyCache).Path
$modules = Join-Path $client 'node_modules'
New-Item -ItemType Directory -Path $modules -Force | Out-Null
if ((Get-Item -LiteralPath $modules).LinkType) { throw 'node_modules must be a local directory, not a shared junction.' }
$localCache = Join-Path $modules '.cache'
New-Item -ItemType Directory -Path $localCache -Force | Out-Null
if ((Get-Item -LiteralPath $localCache).LinkType) { throw '.cache must remain local.' }
# Link packages only. Never link or write the dependency cache's generated cache.
foreach ($package in (Get-ChildItem -LiteralPath $cache -Directory | Where-Object { $_.Name -ne '.cache' })) {
    $destination = Join-Path $modules $package.Name
    if (Test-Path -LiteralPath $destination) {
        $existing = Get-Item -LiteralPath $destination
        if ($existing.LinkType -ne 'Junction' -or [IO.Path]::GetFullPath($existing.Target[0]) -ne $package.FullName) {
            throw "Existing dependency entry is not the expected junction: $destination"
        }
    } else {
        New-Item -ItemType Junction -Path $destination -Target $package.FullName | Out-Null
    }
}
$work = Join-Path $repo 'varvis-server/target/ps1-pm5-expansion/frontend'
New-Item -ItemType Directory -Path $work -Force | Out-Null
$buildId = 'build-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$buildWork = Join-Path $work $buildId
$output = Join-Path $buildWork 'dist'
New-Item -ItemType Directory -Path $buildWork | Out-Null
$savedEnv = @{}
$settings = @{
    UMI_ENV = 'ps1pm5'; VARVIS_PS1PM5_OUTPUT = $output;
    VARVIS_API_BASE = 'http://127.0.0.1:8216'; BROWSERSLIST_IGNORE_OLD_DATA = '1';
    NODE_PATH = $cache; TEMP = (Join-Path $buildWork 'temp'); TMP = (Join-Path $buildWork 'temp')
}
New-Item -ItemType Directory -Path $settings.TEMP | Out-Null
foreach ($key in $settings.Keys) { $savedEnv[$key] = [Environment]::GetEnvironmentVariable($key, 'Process'); [Environment]::SetEnvironmentVariable($key, $settings[$key], 'Process') }
$utf8 = New-Object Text.UTF8Encoding($false)
Push-Location $client
try {
    # Umi generates only this worktree's src/.umi-production and node_modules/.cache.
    $ErrorActionPreference = 'Continue'
    & node (Join-Path $cache 'umi/bin/umi.js') build *> (Join-Path $buildWork 'build.log')
    $buildExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($buildExit -ne 0) { throw "Umi build failed ($buildExit). See $buildWork/build.log" }
    if (!(Test-Path -LiteralPath (Join-Path $output 'index.html'))) { throw 'Build produced no index.html.' }
    $assets = @(Get-ChildItem -LiteralPath $output -File -Recurse | ForEach-Object {
        @{ path = $_.FullName.Substring($output.Length + 1).Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
    $sourceFiles = @(& git -C $repo ls-files -- varvis-client-pro/src varvis-client-pro/config varvis-client-pro/package.json varvis-client-pro/package-lock.json)
    $sourceFiles += @(& git -C $repo ls-files --others --exclude-standard -- varvis-client-pro/config)
    $sources = @($sourceFiles | Sort-Object -Unique | ForEach-Object {
        @{ path = $_; sha256 = (Get-FileHash -LiteralPath (Join-Path $repo $_) -Algorithm SHA256).Hash.ToLowerInvariant() }
    })
    $manifest = @{
        branch = $branch; sourceHead = $sourceHead; buildId = $buildId; builtAtUtc = [DateTime]::UtcNow.ToString('o');
        sourceStatus = @(& git -C $repo status --short -- varvis-client-pro); sources = $sources;
        frontendOrigin = 'http://127.0.0.1:8216'; backendOrigin = 'http://127.0.0.1:18217';
        nodeVersion = (& node --version); assets = $assets; buildExit = $buildExit
    }
    [IO.File]::WriteAllText((Join-Path $buildWork 'build-manifest.json'), ($manifest | ConvertTo-Json -Depth 8), $utf8)
    [IO.File]::WriteAllText((Join-Path $work 'latest-build.txt'), $buildWork, $utf8)
    Write-Output "BUILD_OK $buildWork"
} finally {
    Pop-Location
    foreach ($key in $settings.Keys) { [Environment]::SetEnvironmentVariable($key, $savedEnv[$key], 'Process') }
}
