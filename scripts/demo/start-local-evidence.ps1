param([string]$RuntimeRoot)
$ErrorActionPreference = 'Stop'
$code = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $RuntimeRoot) { $RuntimeRoot = Join-Path $code 'local/runtime/bp7-evidence-workbench' }
$runtime = (Resolve-Path -LiteralPath $RuntimeRoot).Path
$allowed = (Resolve-Path -LiteralPath (Join-Path $code 'local/runtime')).Path + '\'
if (-not $runtime.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Runtime must be inside Code-local runtime.' }
foreach ($port in @(8000,8086)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Port $port is occupied; no process was stopped." }
}
$java = Join-Path $code 'local/tools/java/jdk-21.0.12.1+1/bin/java.exe'
$node = Join-Path $code 'local/tools/node/node-v22.21.1-win-x64/node.exe'
$jar = Join-Path $code 'varvis-server/target/varvis-server.jar'
$dist = Join-Path $code 'varvis-client-pro/dist'
$release = Join-Path $runtime 'release'
if (Test-Path -LiteralPath $release) {
    # Keep the installed server and frontend together, independent of subsequent developer builds.
    $jar = Join-Path $release 'varvis-server.jar'
    $dist = Join-Path $release 'frontend'
}
$base = Join-Path $runtime 'evidence-base.properties'
$overrides = Join-Path $runtime 'evidence-overrides.properties'
foreach ($file in @($java,$node,$jar,$base,$overrides,(Join-Path $dist 'index.html'))) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing local prerequisite: $file" }
}
$env:VARVIS_PROJECT_ROOT = $code.Replace('\','/')
$env:VARVIS_TOOL_ROOT = (Join-Path $code 'local/tools').Replace('\','/')
$env:VARVIS_RESOURCE_ROOT = (Join-Path $code 'local/resources').Replace('\','/')
$db = 'jdbc:h2:file:' + $runtime.Replace('\','/') + '/database/varvis;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$log = Join-Path $runtime ('logs/server-' + $stamp)
$imports = 'file:' + $base.Replace('\','/') + ',file:' + $overrides.Replace('\','/')
# Command-line database bindings take precedence over inherited environment and imported files.
$argsFile = Join-Path $runtime ('logs/java-' + $stamp + '.args')
$jargs = @('-Xmx2g','-Dspring.devtools.restart.enabled=false',('-Djava.io.tmpdir='+ (Join-Path $runtime 'tmp')),
    '-jar',$jar,('--spring.config.import='+$imports),'--server.address=127.0.0.1','--server.port=8086',
    '--varvis.datasource.enabled=true','--varvis.datasource.driver-class-name=org.h2.Driver',
    ('--varvis.datasource.url='+$db),('--varvis.datasource.jdbc-url='+$db),
    '--varvis.datasource.username=sa','--varvis.datasource.password=')
$encoded = $jargs | ForEach-Object { '"' + $_.Replace('\','\\').Replace('"','\"') + '"' }
[IO.File]::WriteAllLines($argsFile,$encoded,[Text.UTF8Encoding]::new($false))
$server = Start-Process -FilePath $java -ArgumentList @('"@'+$argsFile+'"') -WorkingDirectory (Join-Path $code 'varvis-server') -WindowStyle Hidden -RedirectStandardOutput ($log+'.out.log') -RedirectStandardError ($log+'.err.log') -PassThru
$webScript = Join-Path $PSScriptRoot 'serve-local-evidence.mjs'
$webLog = Join-Path $runtime ('logs/web-' + $stamp)
$front = Start-Process -FilePath $node -ArgumentList @(('"'+$webScript+'"'),('"'+$dist+'"'),('"'+$webLog+'.requests.jsonl"')) -WorkingDirectory $code -WindowStyle Hidden -RedirectStandardOutput ($webLog+'.out.log') -RedirectStandardError ($webLog+'.err.log') -PassThru
$session = @{runtimeRoot=$runtime;codeRoot=$code;serverPid=$server.Id;frontendPid=$front.Id;
    serverStartedAtUtc=$server.StartTime.ToUniversalTime().ToString('o');frontendStartedAtUtc=$front.StartTime.ToUniversalTime().ToString('o');
    serverArgs=$argsFile;serverLog=($log+'.out.log');serverErrorLog=($log+'.err.log');frontendLog=($webLog+'.out.log');
    frontendRequestLog=($webLog+'.requests.jsonl');url='http://127.0.0.1:8000/#/varvis/chromosome-visualization';database=$db}
$session | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtime 'session.json') -Encoding UTF8
$session | ConvertTo-Json
