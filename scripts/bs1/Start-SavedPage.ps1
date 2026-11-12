param(
    [Parameter(Mandatory=$true)][string]$SessionConfig,
    [ValidateSet('start','restart','stop')][string]$Action='start'
)
$ErrorActionPreference='Stop'
$configPath=(Resolve-Path -LiteralPath $SessionConfig).Path
$session=Get-Content -Raw -Encoding utf8 $configPath | ConvertFrom-Json
$taskRoot=[IO.Path]::GetFullPath($session.taskRoot)
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$receiptPath=Join-Path $taskRoot "$($session.dataset)-page-launch.json"
function Stop-Owned($item) {
    $process=Get-CimInstance Win32_Process -Filter "ProcessId=$($item.processId)"
    if (-not $process) { return }
    $command=$process.CommandLine.Replace('\','/')
    if ($process.Name -ne $item.name -or -not $command.Contains($item.commandMarker.Replace('\','/')) -or
        $process.CreationDate.ToUniversalTime().ToString('o') -ne $item.createdAt) { throw 'PID ownership mismatch; refusing to stop' }
    Stop-Process -Id $process.ProcessId
    Wait-Process -Id $process.ProcessId -Timeout 30 -ErrorAction SilentlyContinue
}
if ($Action -ne 'start') {
    $old=Get-Content -Raw -Encoding utf8 $receiptPath | ConvertFrom-Json
    Stop-Owned $old.page
    Stop-Owned $old.backend
    if ($Action -eq 'stop') {
        $remaining=Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in @($session.backendPort,$session.pagePort) }
        @{at=[DateTime]::UtcNow;portsFree=(-not $remaining);stopped=@($old.page.processId,$old.backend.processId)} |
            ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $taskRoot "$($session.dataset)-page-stop.json")
        return
    }
} elseif (Test-Path -LiteralPath $receiptPath) { throw 'Existing receipt requires restart; never overwrite a live launch' }
foreach ($port in @($session.backendPort,$session.pagePort)) {
    $probe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$port)
    try { $probe.Start() } finally { $probe.Stop() }
}
$parameters=@{
    Java=$session.java; ClassPathFile=$session.classPathFile; Config=$session.config
    ResourceRoot=$session.resourceRoot; Joint=$true; AcceptanceRoot=$session.acceptanceRoot
    BuildDirectory=$session.buildDirectory; SourceHead=$session.sourceHead
    RestoreDatabase=$session.database; RestoreDatabaseSha256=$session.databaseSha256
    PublishedJobsRoot=$session.publishedJobs; Port=$session.backendPort; ReadOnlyPage=$true
}
if ($Action -eq 'restart') { $parameters.Restart=$true; $parameters.InstanceRoot=$old.instanceRoot }
$launch=& (Join-Path $repo 'scripts/ps1_pm5/Start-Ps1Pm5Network.ps1') @parameters | ConvertFrom-Json
$backend=Get-CimInstance Win32_Process -Filter "ProcessId=$($launch.processId)"
$proxyRoot=Join-Path $taskRoot "$($session.dataset)-page"
[IO.Directory]::CreateDirectory($proxyRoot) | Out-Null
$proxyConfig=Join-Path $proxyRoot 'page-config.json'
@{dist=$session.dist;backendPort=$session.backendPort;pagePort=$session.pagePort} | ConvertTo-Json | Set-Content -Encoding utf8 $proxyConfig
$proxyScript=Join-Path $repo 'scripts/bs1/serve_saved_page.py'
$page=Start-Process -FilePath $session.python -ArgumentList @('-S',('"'+$proxyScript+'"'),'--config',('"'+$proxyConfig+'"')) -WindowStyle Hidden -WorkingDirectory $proxyRoot -RedirectStandardOutput (Join-Path $proxyRoot 'stdout.log') -RedirectStandardError (Join-Path $proxyRoot 'stderr.log') -PassThru
$pageInfo=Get-CimInstance Win32_Process -Filter "ProcessId=$($page.Id)"
$receipt=@{dataset=$session.dataset;instanceRoot=$launch.instanceRoot;sourceHead=$session.sourceHead
    pageUrl="http://127.0.0.1:$($session.pagePort)/#/varvis/chromosome-visualization"
    backend=@{processId=$backend.ProcessId;name=$backend.Name;createdAt=$backend.CreationDate.ToUniversalTime().ToString('o');commandMarker=$session.acceptanceRoot}
    page=@{processId=$pageInfo.ProcessId;name=$pageInfo.Name;createdAt=$pageInfo.CreationDate.ToUniversalTime().ToString('o');commandMarker=$proxyConfig}
}
$receipt | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 $receiptPath
$receipt | ConvertTo-Json -Depth 5
