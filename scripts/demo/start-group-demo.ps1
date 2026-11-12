param(
    [Parameter(Mandatory = $true)][string]$RuntimeRoot,
    [string]$ResourceRoot,
    [string]$Maven = 'mvn.cmd',
    [switch]$SkipBuild,
    [switch]$SkipFrontend,
    [switch]$ForegroundBackend
)

$ErrorActionPreference = 'Stop'
if ($ForegroundBackend) { $SkipFrontend = $true }
$demoCodeRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$demoServerRoot = Join-Path $demoCodeRoot 'varvis-server'
$demoClientRoot = Join-Path $demoCodeRoot 'varvis-client-pro'
if (-not $ResourceRoot) { $ResourceRoot = Join-Path $demoCodeRoot 'local/resources' }
$demoRuntime = (Resolve-Path -LiteralPath $RuntimeRoot).Path
$demoOverrides = Join-Path $demoRuntime 'demo-overrides.properties'
$demoSettings = @{}
foreach ($line in Get-Content -LiteralPath $demoOverrides) {
    if ($line -and -not $line.StartsWith('#')) {
        $pair = $line.Split('=', 2)
        $demoSettings[$pair[0]] = $pair[1]
    }
}
$demoJdbcPrefix = 'jdbc:h2:file:' + $demoRuntime.Replace('\', '/') + '/database/'
foreach ($key in @('varvis.datasource.jdbc-url', 'varvis.datasource.url')) {
    if (-not $demoSettings[$key].StartsWith($demoJdbcPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Demo startup requires an isolated H2 database inside RuntimeRoot.'
    }
}
if ($demoSettings['varvis.datasource.driver-class-name'] -ne 'org.h2.Driver') { throw 'Only the local H2 driver is allowed.' }
if (Get-NetTCPConnection -State Listen -LocalPort 8086 -ErrorAction SilentlyContinue) { throw 'Port 8086 is occupied; no process was changed.' }
if (-not $SkipFrontend -and (Get-NetTCPConnection -State Listen -LocalPort 8000 -ErrorAction SilentlyContinue)) { throw 'Port 8000 is occupied; no process was changed.' }

$env:VARVIS_PROJECT_ROOT = $demoCodeRoot
$env:VARVIS_RESOURCE_ROOT = $ResourceRoot
$demoJavaRoot = Join-Path $demoCodeRoot 'local/tools/exomiser/java/temurin-21.0.11+10-x64-windows'
$demoJava = Join-Path $demoJavaRoot 'bin/java.exe'
if (-not (Test-Path -LiteralPath $demoJava)) { throw 'The existing Exomiser Java 21 runtime is required.' }
$env:JAVA_HOME = $demoJavaRoot
Push-Location $demoServerRoot
try {
    if (-not $SkipBuild) {
        $demoMavenRepo = Join-Path $demoCodeRoot 'local/cache/maven'
        & $Maven -o ("-Dmaven.repo.local=$demoMavenRepo") '-DskipTests' compile dependency:build-classpath '-Dmdep.outputFile=target/demo-runtime-classpath.txt' '-DincludeScope=runtime'
        if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    }
    $demoClasspath = (Join-Path $demoServerRoot 'target/classes') + ';' + (Get-Content -Raw -LiteralPath 'target/demo-runtime-classpath.txt').Trim()
} finally { Pop-Location }
$demoStamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$demoStdout = Join-Path $demoRuntime ('server-' + $demoStamp + '.stdout.log')
$demoStderr = Join-Path $demoRuntime ('server-' + $demoStamp + '.stderr.log')
$demoArgs = @('-Xmx2g', '-Dspring.devtools.restart.enabled=false', '-cp', ('"' + $demoClasspath + '"'), 'org.nbscn.varvis.VarvisServerApplication')
$demoArgs += $demoSettings.GetEnumerator() | ForEach-Object { '"--' + $_.Key + '=' + $_.Value + '"' }
if ($ForegroundBackend) {
    # Run the ordinary Spring Boot application in the user's terminal with the
    # same validated command-line configuration used by the accepted demo.
    $demoForegroundArgs = @('-Xmx2g', '-Dspring.devtools.restart.enabled=false', '-cp', $demoClasspath, 'org.nbscn.varvis.VarvisServerApplication')
    $demoForegroundArgs += $demoSettings.GetEnumerator() | ForEach-Object { '--' + $_.Key + '=' + $_.Value }
    Write-Host 'Starting the Varvis backend in this terminal. Start the frontend separately with npm start.'
    Write-Host 'Open http://localhost:8000/#/varvis/chromosome-visualization and use Analysis Lab.'
    Write-Host 'Press Ctrl+C in this terminal to stop the backend. No background session is created.'
    Push-Location $demoServerRoot
    try {
        & $demoJava @demoForegroundArgs
        $demoExitCode = $LASTEXITCODE
    } finally { Pop-Location }
    exit $demoExitCode
}
$demoServer = Start-Process -FilePath $demoJava -ArgumentList $demoArgs -WorkingDirectory $demoServerRoot -WindowStyle Hidden -RedirectStandardOutput $demoStdout -RedirectStandardError $demoStderr -PassThru
$demoSession = @{ serverPid = $demoServer.Id; serverStartedAtUtc = $demoServer.StartTime.ToUniversalTime().ToString('o'); serverLog = $demoStdout; serverErrorLog = $demoStderr; runtimeRoot = $demoRuntime; codeRoot = $demoCodeRoot; url = 'http://localhost:8000/#/varvis/chromosome-visualization' }
if (-not $SkipFrontend) {
    $env:PORT = '8000'
    $env:HOST = '127.0.0.1'
    $env:BROWSER = 'none'
    $env:REACT_APP_ENV = 'dev'
    $env:VARVIS_API_BASE = 'http://127.0.0.1:8086'
    $demoNode = (Get-Command node.exe).Source
    $demoUmi = Join-Path $demoClientRoot 'node_modules/umi/bin/umi.js'
    $demoFrontend = Start-Process -FilePath $demoNode -ArgumentList @(('"' + $demoUmi + '"'), 'dev') -WorkingDirectory $demoClientRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $demoRuntime ('frontend-' + $demoStamp + '.stdout.log')) -RedirectStandardError (Join-Path $demoRuntime ('frontend-' + $demoStamp + '.stderr.log')) -PassThru
    $demoSession.frontendPid = $demoFrontend.Id
    $demoSession.frontendStartedAtUtc = $demoFrontend.StartTime.ToUniversalTime().ToString('o')
} elseif (Test-Path -LiteralPath (Join-Path $demoRuntime 'session.json')) {
    $demoPrevious = Get-Content -Raw -LiteralPath (Join-Path $demoRuntime 'session.json') | ConvertFrom-Json
    $demoSession.frontendPid = $demoPrevious.frontendPid
    $demoSession.frontendStartedAtUtc = $demoPrevious.frontendStartedAtUtc
}
$demoSession | ConvertTo-Json | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $demoRuntime 'session.json')
$demoSession | ConvertTo-Json
