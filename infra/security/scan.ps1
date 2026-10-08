#requires -Version 7.3
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $root
try {
    $binary = Join-Path $root '.local/security/bin/trivy'
    $raw = Join-Path $root '.local/security/raw'
    $cache = Join-Path $root '.local/security/cache'
    $ignore = Join-Path $raw 'empty-ignore'
    [IO.File]::WriteAllText($ignore, '')
    foreach ($databaseFlag in @('--download-db-only', '--download-java-db-only')) {
        & $binary image --cache-dir $cache $databaseFlag --timeout 10m
        if ($LASTEXITCODE -ne 0) { throw "Download da base falhou: $databaseFlag" }
    }
    $options = @('--cache-dir', $cache, '--scanners', 'vuln', '--format', 'json',
        '--list-all-pkgs', '--exit-code', '0', '--ignorefile', $ignore, '--timeout', '10m',
        '--skip-db-update', '--skip-java-db-update')
    foreach ($module in @('transacoes', 'processamento')) {
        & $binary fs @options --output "$raw/$module-jar.json" ".local/security/inputs/$module"
        if ($LASTEXITCODE -ne 0) { throw "Scan JAR falhou: $module" }
        & $binary image @options --input ".local/security/inputs/$module.tar" --output "$raw/$module-image.json"
        if ($LASTEXITCODE -ne 0) { throw "Scan imagem falhou: $module" }
    }
    $version = & $binary --cache-dir $cache version --format json | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'Metadados do scanner indisponíveis.' }
    [IO.File]::WriteAllText((Join-Path $raw 'scanner.json'), $version)
    & "$PSScriptRoot/report.ps1" -Directory $raw -OutputDirectory '.local/security/reports'
    if ($env:GITHUB_STEP_SUMMARY) {
        Get-Content '.local/security/reports/summary.md' | Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY
    }
} finally { Pop-Location }
exit 0
