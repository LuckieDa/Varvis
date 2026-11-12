param(
    [int]$Port = 8086,
    [switch]$NoAutoStop,
    [switch]$ValidateConfigurationOnly
)

$ErrorActionPreference = 'Stop'

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$serverRoot = Split-Path -Parent $scriptRoot
$codeRoot = Split-Path -Parent $serverRoot
. (Join-Path $codeRoot 'scripts/migration/VarvisWorkspace.ps1')
& (Join-Path $codeRoot 'scripts/migration/Initialize-VarvisWorkspace.ps1')
$stopScript = Join-Path $scriptRoot 'stop-varvis-server.ps1'
$localVariantConfig = Join-Path $serverRoot 'config\variant-analysis-local.properties'

function Read-LocalProperties {
    param([string]$Path)
    $properties = @{}
    if (-not (Test-Path -LiteralPath $Path)) {
        return $properties
    }
    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#') -or $line.StartsWith('!')) {
            continue
        }
        $separator = $line.IndexOf('=')
        if ($separator -lt 0) {
            $separator = $line.IndexOf(':')
        }
        if ($separator -lt 0) {
            continue
        }
        $key = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1).Trim()
        if ($key) {
            $properties[$key] = $value
        }
    }
    return $properties
}

function Test-SettingPresent {
    param(
        [hashtable]$Properties,
        [string]$PropertyName,
        [string]$EnvironmentName
    )
    $environmentValue = [Environment]::GetEnvironmentVariable($EnvironmentName, 'Process')
    if (-not $environmentValue) {
        $environmentValue = [Environment]::GetEnvironmentVariable($EnvironmentName, 'User')
    }
    if ($environmentValue) {
        return $true
    }
    return $Properties.ContainsKey($PropertyName) -and -not [string]::IsNullOrWhiteSpace($Properties[$PropertyName])
}

function Get-SettingSource {
    param(
        [hashtable]$Properties,
        [string]$PropertyName,
        [string]$EnvironmentName
    )
    $processValue = [Environment]::GetEnvironmentVariable($EnvironmentName, 'Process')
    $userValue = [Environment]::GetEnvironmentVariable($EnvironmentName, 'User')
    if ($processValue) {
        return 'environment'
    }
    if ($userValue) {
        return 'user-environment'
    }
    if ($Properties.ContainsKey($PropertyName) -and -not [string]::IsNullOrWhiteSpace($Properties[$PropertyName])) {
        return 'local-properties'
    }
    return 'default-or-missing'
}

function Get-SettingValue {
    param(
        [hashtable]$Properties,
        [string]$PropertyName,
        [string]$EnvironmentName,
        [string]$DefaultValue = 'not-configured'
    )
    $processValue = [Environment]::GetEnvironmentVariable($EnvironmentName, 'Process')
    if ($processValue) {
        return $processValue
    }
    $userValue = [Environment]::GetEnvironmentVariable($EnvironmentName, 'User')
    if ($userValue) {
        return $userValue
    }
    if ($Properties.ContainsKey($PropertyName) -and -not [string]::IsNullOrWhiteSpace($Properties[$PropertyName])) {
        return [string]$Properties[$PropertyName]
    }
    return $DefaultValue
}

function Get-JavaMajorVersion {
    param([string]$JavaExecutable)

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $JavaExecutable
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            return $null
        }
        $standardOutput = $process.StandardOutput.ReadToEnd()
        $standardError = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            return $null
        }
        $versionOutput = "$standardOutput`n$standardError"
        if ($versionOutput -notmatch 'version\s+"(?<version>[^"\s]+)"') {
            return $null
        }
        $versionParts = $Matches['version'].Split('.')
        if ($versionParts[0] -eq '1' -and $versionParts.Length -gt 1) {
            return [int]$versionParts[1]
        }
        return [int]($versionParts[0] -replace '[^0-9].*$', '')
    } finally {
        $process.Dispose()
    }
}

function Get-ServerJavaCandidate {
    param(
        [string]$JavaHomePath,
        [string]$Source
    )

    if ([string]::IsNullOrWhiteSpace($JavaHomePath)) {
        return $null
    }
    $javaExecutable = Join-Path $JavaHomePath 'bin\java.exe'
    $javacExecutable = Join-Path $JavaHomePath 'bin\javac.exe'
    $javaExists = Test-Path -LiteralPath $javaExecutable -PathType Leaf
    $javacExists = Test-Path -LiteralPath $javacExecutable -PathType Leaf
    if (-not $javaExists -or -not $javacExists) {
        return $null
    }
    $majorVersion = Get-JavaMajorVersion -JavaExecutable $javaExecutable
    if ($null -eq $majorVersion) {
        return $null
    }
    return [pscustomobject]@{
        Home = (Resolve-Path -LiteralPath $JavaHomePath).Path
        Java = (Resolve-Path -LiteralPath $javaExecutable).Path
        Major = $majorVersion
        Source = $Source
    }
}

function Test-VepProfilePresent {
    param(
        [hashtable]$Properties,
        [string]$Assembly
    )
    $upper = $Assembly.ToUpperInvariant()
    $propertyPrefix = "variant-analysis.vep.resources.$Assembly"
    $required = @(
        @("$propertyPrefix.enabled", "VARVIS_VEP_${upper}_ENABLED"),
        @("$propertyPrefix.executable", "VARVIS_VEP_${upper}_EXECUTABLE"),
        @("$propertyPrefix.cache-dir", "VARVIS_VEP_${upper}_CACHE"),
        @("$propertyPrefix.fasta", "VARVIS_VEP_${upper}_FASTA")
    )
    foreach ($entry in $required) {
        if (-not (Test-SettingPresent -Properties $Properties -PropertyName $entry[0] -EnvironmentName $entry[1])) {
            return $false
        }
    }
    return $true
}

$listener = $null
if (-not $ValidateConfigurationOnly) {
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue |
        Select-Object -First 1

    if ($listener) {
        if ($NoAutoStop) {
            throw "Port $Port is already in use by PID $($listener.OwningProcess)."
        }
        & $stopScript -Port $Port
    }
}

$userAlphaGenomeKey = [Environment]::GetEnvironmentVariable('ALPHAGENOME_API_KEY', 'User')
if ($userAlphaGenomeKey) {
    $env:ALPHAGENOME_API_KEY = $userAlphaGenomeKey
}

$userAlphaGenomeEnabled = [Environment]::GetEnvironmentVariable('VARVIS_ALPHAGENOME_ENABLED', 'User')
if ($userAlphaGenomeEnabled) {
    $env:VARVIS_ALPHAGENOME_ENABLED = $userAlphaGenomeEnabled
}

$projectRoot = $codeRoot
$resourceRoot = Join-Path $codeRoot 'local/resources'

$localProperties = Read-LocalProperties -Path $localVariantConfig
$localConfigFound = Test-Path -LiteralPath $localVariantConfig
$evidenceConfig = Join-Path $codeRoot 'local/private/config/acmg-evidence-current.properties'
$evidenceConfigFound = Test-Path -LiteralPath $evidenceConfig
$minimumServerJavaMajor = 17
$configuredServerJavaHome = [Environment]::GetEnvironmentVariable('VARVIS_SERVER_JAVA_HOME', 'Process')
if (-not $configuredServerJavaHome) {
    $configuredServerJavaHome = [Environment]::GetEnvironmentVariable('VARVIS_SERVER_JAVA_HOME', 'User')
}
$serverJava = $null
if ($configuredServerJavaHome) {
    $serverJava = Get-ServerJavaCandidate `
        -JavaHomePath $configuredServerJavaHome `
        -Source 'VARVIS_SERVER_JAVA_HOME'
    if ($null -eq $serverJava -or $serverJava.Major -lt $minimumServerJavaMajor) {
        throw "VARVIS_SERVER_JAVA_HOME must point to a JDK $minimumServerJavaMajor or newer: $configuredServerJavaHome"
    }
}
if ($null -eq $serverJava -and $env:JAVA_HOME) {
    $currentJava = Get-ServerJavaCandidate -JavaHomePath $env:JAVA_HOME -Source 'JAVA_HOME'
    if ($null -ne $currentJava -and $currentJava.Major -ge $minimumServerJavaMajor) {
        $serverJava = $currentJava
    }
}
if ($null -eq $serverJava) {
    $exomiserJavaExecutable = Get-SettingValue `
        -Properties $localProperties `
        -PropertyName 'variant-analysis.exomiser.java-executable' `
        -EnvironmentName 'VARVIS_EXOMISER_JAVA' `
        -DefaultValue ''
    $exomiserJavaExecutable = Expand-VarvisRoots -Value $exomiserJavaExecutable -CodeRoot $codeRoot
    if ($exomiserJavaExecutable -and (Test-Path -LiteralPath $exomiserJavaExecutable -PathType Leaf)) {
        $exomiserJavaBin = Split-Path -Parent $exomiserJavaExecutable
        $exomiserJavaHome = Split-Path -Parent $exomiserJavaBin
        $exomiserJava = Get-ServerJavaCandidate `
            -JavaHomePath $exomiserJavaHome `
            -Source 'Exomiser JDK fallback'
        if ($null -ne $exomiserJava -and $exomiserJava.Major -ge $minimumServerJavaMajor) {
            $serverJava = $exomiserJava
        }
    }
}
if ($null -eq $serverJava) {
    throw "varvis-server requires JDK $minimumServerJavaMajor or newer. Set VARVIS_SERVER_JAVA_HOME to a full JDK installation."
}
$env:JAVA_HOME = $serverJava.Home
$serverJavaBin = Join-Path $serverJava.Home 'bin'
$env:Path = "$serverJavaBin;$env:Path"
$assemblyPolicySource = Get-SettingSource `
    -Properties $localProperties `
    -PropertyName 'variant-analysis.analysis-assembly-policy' `
    -EnvironmentName 'VARVIS_ANALYSIS_ASSEMBLY_POLICY'
$vepExecutionPresent = Test-SettingPresent `
    -Properties $localProperties `
    -PropertyName 'variant-analysis.vep.execution-enabled' `
    -EnvironmentName 'VARVIS_VEP_EXECUTION_ENABLED'
$preRankingPresent = Test-SettingPresent `
    -Properties $localProperties `
    -PropertyName 'variant-analysis.vep.pre-ranking-enabled' `
    -EnvironmentName 'VARVIS_VEP_PRE_RANKING_ENABLED'
$vepExecutionValue = Get-SettingValue `
    -Properties $localProperties `
    -PropertyName 'variant-analysis.vep.execution-enabled' `
    -EnvironmentName 'VARVIS_VEP_EXECUTION_ENABLED' `
    -DefaultValue 'false'
$preRankingValue = Get-SettingValue `
    -Properties $localProperties `
    -PropertyName 'variant-analysis.vep.pre-ranking-enabled' `
    -EnvironmentName 'VARVIS_VEP_PRE_RANKING_ENABLED' `
    -DefaultValue 'true'
$grch37ProfileEnabled = Get-SettingValue `
    -Properties $localProperties `
    -PropertyName 'variant-analysis.vep.resources.GRCh37.enabled' `
    -EnvironmentName 'VARVIS_VEP_GRCH37_ENABLED' `
    -DefaultValue 'false'
$grch38ProfileEnabled = Get-SettingValue `
    -Properties $localProperties `
    -PropertyName 'variant-analysis.vep.resources.GRCh38.enabled' `
    -EnvironmentName 'VARVIS_VEP_GRCH38_ENABLED' `
    -DefaultValue 'false'
$grch37ProfilePresent = Test-VepProfilePresent -Properties $localProperties -Assembly 'GRCh37'
$grch38ProfilePresent = Test-VepProfilePresent -Properties $localProperties -Assembly 'GRCh38'
$serverMaxHeap = [Environment]::GetEnvironmentVariable('VARVIS_SERVER_MAX_HEAP', 'Process')
if (-not $serverMaxHeap) {
    $serverMaxHeap = [Environment]::GetEnvironmentVariable('VARVIS_SERVER_MAX_HEAP', 'User')
}
if (-not $serverMaxHeap) {
    $serverMaxHeap = '6g'
}
if ($serverMaxHeap -notmatch '^\d+[mMgG]$') {
    throw "VARVIS_SERVER_MAX_HEAP must be a JVM memory size such as 4096m or 6g."
}
$tempRoot = Join-Path $codeRoot 'local/temp'
$mavenRepository = $env:VARVIS_MAVEN_REPOSITORY
if ([string]::IsNullOrWhiteSpace($mavenRepository)) {
    $mavenRepository = Join-Path $codeRoot 'local/cache/maven'
}
$mavenRepository = Assert-VarvisLocalPath -Path $mavenRepository -CodeRoot $codeRoot
$springBootJvmArguments = "-Xms512m -Xmx$serverMaxHeap -Djava.io.tmpdir=`"$tempRoot`""

if ($ValidateConfigurationOnly) {
    Write-Host 'Validating varvis-server local configuration...'
} else {
    Write-Host "Starting varvis-server on port $Port..."
}
Write-Host "Project root: $projectRoot"
Write-Host "Resource root: $resourceRoot"
Write-Host "Backend Java: $($serverJava.Java) (major $($serverJava.Major), $($serverJava.Source))"
Write-Host "Local variant-analysis config: $(if ($localConfigFound) { 'FOUND' } else { 'MISSING' })"
if ($localConfigFound) {
    Write-Host "Spring optional import will load config/variant-analysis-local.properties."
} else {
    Write-Warning "config/variant-analysis-local.properties was not found; dual-assembly VEP readiness will reflect committed defaults and environment variables only."
}
Write-Host "Analysis assembly policy: $assemblyPolicySource"
$ba1Local = Get-SettingValue -Properties $localProperties -PropertyName 'variant-analysis.acmg-business.ba1-local-exomes-enabled' -EnvironmentName 'VARVIS_ACMG_BA1_LOCAL_EXOMES_ENABLED' -DefaultValue 'false'
Write-Host "BA1 local exomes mode: $ba1Local (evidence scope follows the effective ACMG configuration)"
Write-Host "Optional current evidence config: $(if ($evidenceConfigFound) { 'FOUND' } else { 'NOT CONFIGURED' })"
Write-Host 'After startup, GET /variant-analyses/acmg/configuration reports the actual CURRENT criteria without running an analysis.'
Write-Host "VEP execution enabled: $vepExecutionValue (configuration $(if ($vepExecutionPresent) { 'present' } else { 'absent' }))"
Write-Host "VEP pre-ranking enabled: $preRankingValue (configuration $(if ($preRankingPresent) { 'present' } else { 'absent' }))"
Write-Host "GRCh37 VEP profile enabled: $grch37ProfileEnabled (configuration $(if ($grch37ProfilePresent) { 'present' } else { 'absent' }))"
Write-Host "GRCh38 VEP profile enabled: $grch38ProfileEnabled (configuration $(if ($grch38ProfilePresent) { 'present' } else { 'absent' }))"
Write-Host "Backend JVM heap: $serverMaxHeap maximum"
Write-Host "Maven repository: $mavenRepository"
if ($ValidateConfigurationOnly) {
    Write-Host 'Configuration validation completed; the server was not started.'
    exit 0
}
Write-Host "Press Ctrl+C in this window to stop it."
Push-Location -LiteralPath $serverRoot
try {
    mvn "-Dmaven.repo.local=$mavenRepository" spring-boot:run "-Dspring-boot.run.jvmArguments=$springBootJvmArguments"
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}
