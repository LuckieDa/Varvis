# Shared Code-local path contract. Dot-source; no filesystem or environment writes.
function Assert-VarvisLocalPath {
    param([string]$Path, [string]$CodeRoot)
    $root = [IO.Path]::GetFullPath($CodeRoot).TrimEnd('\', '/')
    $full = [IO.Path]::GetFullPath($Path)
    if ($full -ne $root -and -not $full.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path is outside Code: $full"
    }
    $cursor = $full
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Linked paths require separate migration review: $cursor"
            }
        }
        $cursor = Split-Path -Parent $cursor
    }
    return $full
}

function Expand-VarvisRoots {
    param([string]$Value, [string]$CodeRoot, [string]$ResourceRoot)
    if (-not $ResourceRoot) { $ResourceRoot = Join-Path $CodeRoot 'local\resources' }
    return $Value.Replace('${VARVIS_PROJECT_ROOT}', $CodeRoot.Replace('\', '/')).Replace(
        '${VARVIS_RESOURCE_ROOT}', $ResourceRoot.Replace('\', '/')).Replace(
        '${VARVIS_TOOL_ROOT}', (Join-Path $CodeRoot 'local\tools').Replace('\', '/'))
}

function ConvertTo-VarvisLocalLayout {
    param([string]$Content)
    # Replace only recognized path prefixes, including explicit index paths and lists.
    # Never change feature flags, assembly, versions, checksums or historical files.
    $result = $Content.Replace('D:/LSD/Project/Tool/', '${VARVIS_RESOURCE_ROOT}/Tool/').Replace(
        'F:/LSD/Project/Tool/', '${VARVIS_RESOURCE_ROOT}/Tool/').Replace(
        'D:/LSD/Project/Code/', '${VARVIS_PROJECT_ROOT}/').Replace(
        'F:/LSD/Project/Code/', '${VARVIS_PROJECT_ROOT}/').Replace(
        '${VARVIS_PROJECT_ROOT}/Code/', '${VARVIS_PROJECT_ROOT}/')
    $map = [ordered]@{
        'exomiser/data/' = '${VARVIS_RESOURCE_ROOT}/exomiser/data/'
        'exomiser/data' = '${VARVIS_RESOURCE_ROOT}/exomiser/data'
        'alphagenome/' = '${VARVIS_TOOL_ROOT}/alphagenome/'
        'lirical/' = '${VARVIS_TOOL_ROOT}/lirical/'
        'VarvisHailRuntime/' = '${VARVIS_TOOL_ROOT}/VarvisHailRuntime/'
        'exomiser/' = '${VARVIS_TOOL_ROOT}/exomiser/'
        'msys64/' = '${VARVIS_TOOL_ROOT}/msys64/'
        'vep/' = '${VARVIS_TOOL_ROOT}/vep/'
        'liftover/picard.jar' = '${VARVIS_TOOL_ROOT}/liftover/picard.jar'
        'liftover/' = '${VARVIS_RESOURCE_ROOT}/liftover/'
        'm3/' = '${VARVIS_RESOURCE_ROOT}/alphamissense/'
        'benchmark/' = '${VARVIS_RESOURCE_ROOT}/benchmark/'
        'clingen/' = '${VARVIS_RESOURCE_ROOT}/clingen/'
        'gencode/' = '${VARVIS_RESOURCE_ROOT}/gencode/'
        'gene-intervals/' = '${VARVIS_RESOURCE_ROOT}/gene-intervals/'
        'nstd/' = '${VARVIS_RESOURCE_ROOT}/nstd/'
        'phen2gene/' = '${VARVIS_RESOURCE_ROOT}/phen2gene/'
        'revel/' = '${VARVIS_RESOURCE_ROOT}/revel/'
        'acmg-context/' = '${VARVIS_RESOURCE_ROOT}/acmg-context/'
        'reference/' = '${VARVIS_RESOURCE_ROOT}/reference/'
        'cadd/' = '${VARVIS_RESOURCE_ROOT}/cadd/'
        'clinvar/' = '${VARVIS_RESOURCE_ROOT}/clinvar/'
        'gnomad/' = '${VARVIS_RESOURCE_ROOT}/gnomad/'
        'spliceai/' = '${VARVIS_RESOURCE_ROOT}/spliceai/'
    }
    foreach ($entry in $map.GetEnumerator()) {
        $result = $result.Replace('${VARVIS_RESOURCE_ROOT}/Tool/' + $entry.Key, $entry.Value)
    }
    $result = $result.Replace('${VARVIS_PROJECT_ROOT}/Varvis-runtime/', '${VARVIS_PROJECT_ROOT}/local/runtime/')
    $result = [regex]::Replace($result,
        '(?m)^(variant-analysis\.storage-root\s*=)\$\{VARVIS_PROJECT_ROOT\}',
        '$1file:$${VARVIS_PROJECT_ROOT}')
    return $result
}
