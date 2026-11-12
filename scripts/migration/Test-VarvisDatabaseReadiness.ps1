[CmdletBinding()]
param(
    [string]$ConfigPath,
    [string]$ConnectorJar,
    [string]$OutputPath,
    [switch]$FailOnBlocking
)

$ErrorActionPreference = 'Stop'

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$codeRoot = Split-Path -Parent (Split-Path -Parent $scriptRoot)
. (Join-Path $scriptRoot 'VarvisWorkspace.ps1')
$serverRoot = Join-Path $codeRoot 'varvis-server'
$probeSource = Join-Path $scriptRoot 'DatabaseReadinessProbe.java'
$knowledgeSchemaFileName = ([string][char]0x6570) + ([char]0x636E) + ([char]0x5E93) + '.txt'
$knowledgeSchemaSource = Join-Path (Join-Path $codeRoot 'local/private/reference-materials') $knowledgeSchemaFileName
$analysisSchemaSources = @(
    (Join-Path $serverRoot 'src\main\resources\db\variant-analysis-schema.sql'),
    (Join-Path $serverRoot 'src\main\resources\db\exomiser-analysis-schema.sql')
)

if (-not $ConfigPath) {
    $ConfigPath = Join-Path $serverRoot 'config\database-local.properties'
}
if (-not $OutputPath) {
    $OutputPath = Join-Path $codeRoot 'local\migration\database-readiness-current.json'
}

$OutputPath = Assert-VarvisLocalPath -Path $OutputPath -CodeRoot $codeRoot

function Read-LocalProperties {
    param([string]$Path)

    $properties = @{}
    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#') -or $line.StartsWith('!')) {
            continue
        }
        $separator = $line.IndexOf('=')
        if ($separator -lt 0) {
            continue
        }
        $key = $line.Substring(0, $separator).Trim()
        if ($key) {
            $properties[$key] = $line.Substring($separator + 1).Trim()
        }
    }
    return $properties
}

function Get-ExpectedSchema {
    param(
        [string]$Path,
        [string]$Group
    )

    $content = Get-Content -LiteralPath $Path -Raw
    $pattern = '(?is)CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+`?(?<table>[a-z0-9_]+)`?\s*\((?<body>.*?)\)\s*ENGINE\s*='
    foreach ($tableMatch in [regex]::Matches($content, $pattern)) {
        $body = $tableMatch.Groups['body'].Value
        $columns = New-Object System.Collections.Generic.List[string]
        foreach ($rawLine in ($body -split "`r?`n")) {
            $line = $rawLine.Trim()
            if ($line -match '^`(?<column>[a-z0-9_]+)`\s+') {
                $columns.Add($Matches['column'].ToLowerInvariant()) | Out-Null
            } elseif ($line -cmatch '^(?<column>[a-z][a-z0-9_]*)\s+') {
                $columns.Add($Matches['column'].ToLowerInvariant()) | Out-Null
            }
        }
        $primaryKeyColumns = New-Object System.Collections.Generic.List[string]
        $primaryKeyMatch = [regex]::Match($body, '(?is)PRIMARY\s+KEY\s*\((?<columns>[^)]*)\)')
        if ($primaryKeyMatch.Success) {
            foreach ($rawColumn in ($primaryKeyMatch.Groups['columns'].Value -split ',')) {
                if ($rawColumn.Trim() -match '^`?(?<column>[a-z0-9_]+)`?') {
                    $primaryKeyColumns.Add($Matches['column'].ToLowerInvariant()) | Out-Null
                }
            }
        }
        $indexNames = @([regex]::Matches(
            $body,
            '(?im)^\s*(?:UNIQUE\s+)?(?:KEY|INDEX)\s+`?(?<name>[a-z0-9_]+)`?\s*\('
        ) | ForEach-Object { $_.Groups['name'].Value.ToLowerInvariant() } | Sort-Object -Unique)
        $foreignKeyNames = @([regex]::Matches(
            $body,
            '(?im)CONSTRAINT\s+`?(?<name>[a-z0-9_]+)`?\s+FOREIGN\s+KEY'
        ) | ForEach-Object { $_.Groups['name'].Value.ToLowerInvariant() } | Sort-Object -Unique)
        [pscustomobject][ordered]@{
            name = $tableMatch.Groups['table'].Value.ToLowerInvariant()
            group = $Group
            source = $Path
            columns = @($columns | Sort-Object -Unique)
            primaryKeyColumns = @($primaryKeyColumns)
            indexNames = @($indexNames)
            foreignKeyNames = @($foreignKeyNames)
        }
    }
}

function Resolve-ConnectorJar {
    param([string]$Requested)

    $candidates = New-Object System.Collections.Generic.List[string]
    if ($Requested) {
        $candidates.Add($Requested) | Out-Null
    }
    foreach ($scope in @('Process', 'User')) {
        $configured = [Environment]::GetEnvironmentVariable('VARVIS_MYSQL_CONNECTOR_JAR', $scope)
        if ($configured) {
            $candidates.Add($configured) | Out-Null
        }
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }

    # Discovery is read-only; do not invoke Maven and download plugins here.
    $repository = Join-Path $codeRoot 'local/cache/maven'

    $artifactRoots = @(
        (Join-Path $repository 'mysql\mysql-connector-java'),
        (Join-Path $repository 'com\mysql\mysql-connector-j')
    )
    $jar = $artifactRoots |
        Where-Object { Test-Path -LiteralPath $_ -PathType Container } |
        ForEach-Object {
            Get-ChildItem -LiteralPath $_ -Recurse -File -Filter '*.jar' |
                Where-Object { $_.Name -notmatch '(sources|javadoc|tests)' }
        } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if (-not $jar) {
        throw 'MySQL Connector/J is not available in the Maven local repository.'
    }
    return $jar.FullName
}

foreach ($requiredPath in @($ConfigPath, $probeSource, $knowledgeSchemaSource) + $analysisSchemaSources) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Required file is missing: $requiredPath"
    }
}

$localProperties = Read-LocalProperties -Path $ConfigPath
$jdbcUrl = [string]$localProperties['varvis.datasource.jdbc-url']
if (-not $jdbcUrl) {
    $jdbcUrl = [string]$localProperties['varvis.datasource.url']
}
$usernamePresent = -not [string]::IsNullOrWhiteSpace([string]$localProperties['varvis.datasource.username'])
$passwordPresent = $localProperties.ContainsKey('varvis.datasource.password')
if ([string]::IsNullOrWhiteSpace($jdbcUrl) -or -not $usernamePresent) {
    throw 'The local database configuration does not contain a JDBC URL and username.'
}

$targetSchema = $null
$connectionLocation = 'UNKNOWN'
try {
    $targetUri = [Uri]$jdbcUrl.Substring('jdbc:'.Length)
    $targetSchema = [Uri]::UnescapeDataString($targetUri.AbsolutePath.Trim('/'))
    $connectionLocation = if ($targetUri.Host -in @('localhost', '127.0.0.1', '::1')) {
        'LOCAL'
    } else {
        'REMOTE'
    }
} catch {
    # The Java probe remains the authority for connection validation. Avoid
    # copying an unparseable URL into output because it may contain secrets.
}

$expectedSchema = @(
    Get-ExpectedSchema -Path $knowledgeSchemaSource -Group 'knowledge'
    foreach ($source in $analysisSchemaSources) {
        Get-ExpectedSchema -Path $source -Group 'analysis'
    }
) | Sort-Object name
if (($expectedSchema | Group-Object name | Where-Object Count -gt 1).Count -gt 0) {
    throw 'Duplicate expected table definitions were found in the schema sources.'
}
if ($expectedSchema.Count -eq 0) {
    throw 'No expected table definitions were found in the schema sources.'
}

$ConnectorJar = Resolve-ConnectorJar -Requested $ConnectorJar
$javac = Get-Command javac.exe, javac -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
$java = Get-Command java.exe, java -CommandType Application -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $javac -or -not $java) {
    throw 'A JDK with java and javac is required for the database readiness probe.'
}

$reportDirectory = Split-Path -Parent ([IO.Path]::GetFullPath($OutputPath))
$classDirectory = Join-Path $reportDirectory 'database-probe-classes'
New-Item -ItemType Directory -Force -Path $classDirectory | Out-Null

$previousPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $compileOutput = @(& $javac.Source -encoding UTF-8 -cp $ConnectorJar -d $classDirectory $probeSource 2>&1 |
        ForEach-Object { [string]$_ })
    $compileExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
}
if ($compileExitCode -ne 0) {
    throw "The read-only database probe did not compile: $($compileOutput -join ' ')"
}

$classPath = "$classDirectory;$ConnectorJar"
try {
    $ErrorActionPreference = 'Continue'
    $probeLines = @(& $java.Source -cp $classPath DatabaseReadinessProbe $ConfigPath 2>&1 |
        ForEach-Object { [string]$_ })
    $probeExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
}

$metadata = @{}
$actualTables = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
$actualColumns = @{}
$tableEstimates = @{}
$primaryKeys = @{}
$indexes = @{}
$foreignKeys = @{}
$probeErrors = New-Object System.Collections.Generic.List[string]

foreach ($line in $probeLines) {
    $fields = $line -split "`t"
    if ($fields.Count -eq 0) {
        continue
    }
    switch ($fields[0]) {
        'META' {
            if ($fields.Count -ge 3) {
                $metadata[$fields[1]] = $fields[2]
            }
        }
        'TABLE' {
            if ($fields.Count -ge 2) {
                $actualTables.Add($fields[1]) | Out-Null
            }
        }
        'COLUMN' {
            if ($fields.Count -ge 3) {
                $table = $fields[1].ToLowerInvariant()
                if (-not $actualColumns.ContainsKey($table)) {
                    $actualColumns[$table] = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
                }
                $actualColumns[$table].Add($fields[2]) | Out-Null
            }
        }
        'TABLE_STATS' {
            if ($fields.Count -ge 4) {
                $estimate = 0L
                if ([long]::TryParse($fields[3], [ref]$estimate)) {
                    $tableEstimates[$fields[1].ToLowerInvariant()] = $estimate
                }
            }
        }
        'PRIMARY_KEY' {
            if ($fields.Count -ge 3) {
                $table = $fields[1].ToLowerInvariant()
                if (-not $primaryKeys.ContainsKey($table)) {
                    $primaryKeys[$table] = New-Object System.Collections.Generic.List[string]
                }
                $primaryKeys[$table].Add($fields[2]) | Out-Null
            }
        }
        'INDEX' {
            if ($fields.Count -ge 3) {
                $table = $fields[1].ToLowerInvariant()
                if (-not $indexes.ContainsKey($table)) {
                    $indexes[$table] = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
                }
                $indexes[$table].Add($fields[2]) | Out-Null
            }
        }
        'FOREIGN_KEY' {
            if ($fields.Count -ge 3) {
                $table = $fields[1].ToLowerInvariant()
                if (-not $foreignKeys.ContainsKey($table)) {
                    $foreignKeys[$table] = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
                }
                $foreignKeys[$table].Add($fields[2]) | Out-Null
            }
        }
        'ERROR' {
            $probeErrors.Add(($fields -join ':')) | Out-Null
        }
    }
}

$tableChecks = New-Object System.Collections.Generic.List[object]
foreach ($expected in $expectedSchema) {
    $actualColumnSet = $actualColumns[$expected.name]
    $actualPrimaryKeySet = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    if ($primaryKeys.ContainsKey($expected.name)) {
        $primaryKeys[$expected.name] | ForEach-Object { $actualPrimaryKeySet.Add($_) | Out-Null }
    }
    $actualIndexSet = $indexes[$expected.name]
    $actualForeignKeySet = $foreignKeys[$expected.name]
    $missingColumns = @($expected.columns | Where-Object {
        -not $actualColumnSet -or -not $actualColumnSet.Contains($_)
    })
    $missingPrimaryKeyColumns = @($expected.primaryKeyColumns | Where-Object {
        -not $actualPrimaryKeySet.Contains($_)
    })
    $missingIndexes = @($expected.indexNames | Where-Object {
        -not $actualIndexSet -or -not $actualIndexSet.Contains($_)
    })
    $missingForeignKeys = @($expected.foreignKeyNames | Where-Object {
        -not $actualForeignKeySet -or -not $actualForeignKeySet.Contains($_)
    })
    $tablePresent = $actualTables.Contains($expected.name)
    $status = if (-not $tablePresent) {
        'MISSING_TABLE'
    } elseif ($missingColumns.Count -gt 0) {
        'MISSING_COLUMNS'
    } elseif (
        $missingPrimaryKeyColumns.Count -gt 0 -or
        $missingIndexes.Count -gt 0 -or
        $missingForeignKeys.Count -gt 0
    ) {
        'STRUCTURE_MISMATCH'
    } else {
        'READY'
    }
    $tableChecks.Add([pscustomobject][ordered]@{
        name = $expected.name
        group = $expected.group
        status = $status
        expectedColumnCount = $expected.columns.Count
        actualColumnCount = if ($actualColumnSet) { $actualColumnSet.Count } else { 0 }
        missingColumns = @($missingColumns)
        missingPrimaryKeyColumns = @($missingPrimaryKeyColumns)
        missingIndexes = @($missingIndexes)
        missingForeignKeys = @($missingForeignKeys)
        estimatedRows = if ($tableEstimates.ContainsKey($expected.name)) { $tableEstimates[$expected.name] } else { $null }
        primaryKeyColumns = if ($primaryKeys.ContainsKey($expected.name)) { @($primaryKeys[$expected.name]) } else { @() }
        indexNames = if ($indexes.ContainsKey($expected.name)) { @($indexes[$expected.name] | Sort-Object) } else { @() }
        foreignKeyCount = if ($foreignKeys.ContainsKey($expected.name)) { $foreignKeys[$expected.name].Count } else { 0 }
    }) | Out-Null
}

$tableCheckArray = $tableChecks.ToArray()
$missingTables = @($tableCheckArray | Where-Object status -eq 'MISSING_TABLE' | ForEach-Object name)
$tablesWithMissingColumns = @($tableCheckArray | Where-Object status -eq 'MISSING_COLUMNS' | ForEach-Object name)
$tablesWithStructureMismatch = @($tableCheckArray | Where-Object status -eq 'STRUCTURE_MISMATCH' | ForEach-Object name)
$missingColumnCount = @($tableCheckArray | ForEach-Object missingColumns).Count
$missingPrimaryKeyColumnCount = @($tableCheckArray | ForEach-Object missingPrimaryKeyColumns).Count
$missingIndexCount = @($tableCheckArray | ForEach-Object missingIndexes).Count
$missingForeignKeyCount = @($tableCheckArray | ForEach-Object missingForeignKeys).Count
$expectedNames = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
$expectedSchema.name | ForEach-Object { $expectedNames.Add($_) | Out-Null }
$unexpectedTables = @($actualTables | Where-Object { -not $expectedNames.Contains($_) } | Sort-Object)
$knowledgeChecks = @($tableCheckArray | Where-Object group -eq 'knowledge')
$knowledgeTablesWithEstimatedRows = @($knowledgeChecks | Where-Object {
    $null -ne $_.estimatedRows -and $_.estimatedRows -gt 0
}).Count

$overallStatus = if ($probeExitCode -ne 0 -or $metadata['connection'] -ne 'READY') {
    'CONNECTION_FAILED'
} elseif ($missingTables.Count -gt 0) {
    'SCHEMA_MISSING'
} elseif ($tablesWithMissingColumns.Count -gt 0) {
    'SCHEMA_MISMATCH'
} elseif ($tablesWithStructureMismatch.Count -gt 0) {
    'SCHEMA_MISMATCH'
} elseif ($knowledgeTablesWithEstimatedRows -lt $knowledgeChecks.Count) {
    'READY_WITH_DATA_WARNINGS'
} else {
    'READY'
}

$report = [pscustomobject][ordered]@{
    schemaVersion = 'varvis-database-readiness-v1'
    generatedAt = (Get-Date).ToString('o')
    overallStatus = $overallStatus
    target = [pscustomobject][ordered]@{
        catalog = if ($metadata['catalog']) { $metadata['catalog'] } else { $targetSchema }
        connectionLocation = $connectionLocation
        credentialsConfigured = ($usernamePresent -and $passwordPresent)
    }
    safety = [pscustomobject][ordered]@{
        readOnlyMetadataProbe = $true
        rowContentsRead = $false
        mutationPerformed = $false
        credentialsIncludedInReport = $false
        rowEstimatesAreApproximate = $true
    }
    database = [pscustomobject][ordered]@{
        connection = if ($metadata['connection']) { $metadata['connection'] } else { 'FAILED' }
        readOnly = $metadata['readOnly']
        product = $metadata['databaseProduct']
        version = $metadata['databaseVersion']
        driverVersion = $metadata['driverVersion']
        probeExitCode = $probeExitCode
        errors = @($probeErrors.ToArray())
    }
    schemaSources = @(
        'local/private/reference-materials/<database-schema-reference>.txt'
        'Code/varvis-server/src/main/resources/db/variant-analysis-schema.sql'
        'Code/varvis-server/src/main/resources/db/exomiser-analysis-schema.sql'
    )
    summary = [pscustomobject][ordered]@{
        expectedTableCount = $expectedSchema.Count
        readyTableCount = @($tableCheckArray | Where-Object status -eq 'READY').Count
        missingTableCount = $missingTables.Count
        tableWithMissingColumnsCount = $tablesWithMissingColumns.Count
        tableWithStructureMismatchCount = $tablesWithStructureMismatch.Count
        missingColumnCount = $missingColumnCount
        missingPrimaryKeyColumnCount = $missingPrimaryKeyColumnCount
        missingIndexCount = $missingIndexCount
        missingForeignKeyCount = $missingForeignKeyCount
        knowledgeTableCount = $knowledgeChecks.Count
        knowledgeTablesWithEstimatedRows = $knowledgeTablesWithEstimatedRows
        unexpectedTableCount = $unexpectedTables.Count
    }
    missingTables = @($missingTables)
    tablesWithMissingColumns = @($tablesWithMissingColumns)
    tablesWithStructureMismatch = @($tablesWithStructureMismatch)
    unexpectedTables = @($unexpectedTables)
    tables = @($tableCheckArray)
}

New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $OutputPath -Encoding UTF8

Write-Host "Database readiness: $overallStatus"
Write-Host "Catalog: $($report.target.catalog) ($connectionLocation)"
Write-Host "Expected tables: $($report.summary.readyTableCount)/$($report.summary.expectedTableCount) ready"
Write-Host "Missing tables: $($report.summary.missingTableCount); missing columns: $missingColumnCount"
Write-Host "Missing PK columns/indexes/foreign keys: $missingPrimaryKeyColumnCount/$missingIndexCount/$missingForeignKeyCount"
Write-Host "Knowledge tables with non-zero row estimates: $knowledgeTablesWithEstimatedRows/$($knowledgeChecks.Count)"
Write-Host "Report: $([IO.Path]::GetFullPath($OutputPath))"

if ($FailOnBlocking -and $overallStatus -notlike 'READY*') {
    exit 2
}
