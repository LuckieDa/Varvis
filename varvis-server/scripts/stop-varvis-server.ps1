param(
    [int]$Port = 8086
)

$ErrorActionPreference = 'Stop'

function Get-VarvisProcess {
    param([int]$ProcessId)

    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction SilentlyContinue
    if (-not $process) {
        return $null
    }

    $commandLine = [string]$process.CommandLine
    if ($commandLine.Contains('org.nbscn.varvis.VarvisServerApplication') -or
        $commandLine.Contains('varvis-server\target\classes')) {
        return $process
    }
    return $null
}

$listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty OwningProcess -Unique)

if ($listeners.Count -eq 0) {
    Write-Host "Port $Port is already free."
    exit 0
}

foreach ($owningProcessId in $listeners) {
    $varvisProcess = Get-VarvisProcess -ProcessId $owningProcessId
    if (-not $varvisProcess) {
        $owner = Get-CimInstance Win32_Process -Filter "ProcessId = $owningProcessId" -ErrorAction SilentlyContinue
        $name = if ($owner) { $owner.Name } else { 'unknown process' }
        throw "Port $Port is used by PID $owningProcessId ($name), but it does not look like varvis-server. Stop it manually if needed."
    }

    Write-Host "Stopping varvis-server PID $owningProcessId on port $Port..."
    Stop-Process -Id $owningProcessId -Force

    $parent = Get-CimInstance Win32_Process -Filter "ProcessId = $($varvisProcess.ParentProcessId)" -ErrorAction SilentlyContinue
    if ($parent -and [string]$parent.CommandLine -match 'mvn|spring-boot:run|varvis-server') {
        Stop-Process -Id $parent.ProcessId -Force -ErrorAction SilentlyContinue
    }
}

for ($attempt = 0; $attempt -lt 20; $attempt++) {
    Start-Sleep -Milliseconds 500
    $stillListening = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if (-not $stillListening) {
        Write-Host "Port $Port is free."
        exit 0
    }
}

throw "Timed out waiting for port $Port to be released."
