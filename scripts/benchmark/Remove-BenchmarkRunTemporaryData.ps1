[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [Parameter(Mandatory = $true)] [string] $RunsRoot,
    [Parameter(Mandatory = $true)] [string[]] $RunId,
    [switch] $RemoveEmptyRun
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$root = (Resolve-Path -LiteralPath $RunsRoot).Path.TrimEnd(
    [IO.Path]::DirectorySeparatorChar,
    [IO.Path]::AltDirectorySeparatorChar
)
$allowedTemporaryDirectories = @("input", "preparation", "workspace")

foreach ($id in $RunId) {
    if ($id -notmatch '^[a-z0-9][a-z0-9._-]+$') { throw "RUN_ID_INVALID:$id" }
    $candidate = Join-Path $root $id
    if (-not (Test-Path -LiteralPath $candidate -PathType Container)) { continue }
    $run = (Resolve-Path -LiteralPath $candidate).Path
    if (-not $run.StartsWith($root + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "RUN_PATH_OUTSIDE_ROOT:$id"
    }

    foreach ($directoryName in $allowedTemporaryDirectories) {
        $temporaryCandidate = Join-Path $run $directoryName
        if (-not (Test-Path -LiteralPath $temporaryCandidate)) { continue }
        $temporary = (Resolve-Path -LiteralPath $temporaryCandidate).Path
        if (-not $temporary.StartsWith($run + [IO.Path]::DirectorySeparatorChar,
                [StringComparison]::OrdinalIgnoreCase)) {
            throw "TEMPORARY_PATH_OUTSIDE_RUN:${id}:$directoryName"
        }
        if ($PSCmdlet.ShouldProcess($temporary, "Remove benchmark temporary directory")) {
            Remove-Item -LiteralPath $temporary -Recurse -Force
        }
    }

    if ($RemoveEmptyRun -and -not (Get-ChildItem -LiteralPath $run -Force | Select-Object -First 1)) {
        if ($PSCmdlet.ShouldProcess($run, "Remove empty benchmark run directory")) {
            Remove-Item -LiteralPath $run -Force
        }
    }
}
