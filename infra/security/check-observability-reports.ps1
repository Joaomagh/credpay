#requires -Version 7.3
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'observability-baseline.ps1')
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$directory = Join-Path $root ".local/security/observability-controls/$([Guid]::NewGuid())"
$null = New-Item -ItemType Directory -Force $directory
$utf8 = [Text.UTF8Encoding]::new($false)
function Save([string]$Name, $Value) {
    [IO.File]::WriteAllText((Join-Path $directory $Name), ($Value | ConvertTo-Json -Depth 20), $utf8)
}
function ResetFixture {
    $database = @{Version=2;UpdatedAt='2026-10-16T01:13:54.947090392Z';DownloadedAt='2026-10-16T02:00:00Z'}
    $targets = @()
    foreach ($baseline in @(ReadObservabilityBaseline)) {
        $id = 'sha256:' + ($(if ($baseline.Name -ceq 'prometheus') { 'a' } else { 'b' }) * 64)
        $targets += @{Name=$baseline.Name;Reference=$baseline.Reference;PlatformDigest=$baseline.PlatformDigest;
            ImageId=$id;Os='linux';Architecture='amd64';TarSha256=('c' * 64)}
        $module = if ($baseline.Name -ceq 'prometheus') { 'github.com/prometheus/prometheus' } else { 'github.com/grafana/grafana' }
        $binary = if ($baseline.Name -ceq 'prometheus') { 'bin/prometheus' } else { 'usr/share/grafana/bin/grafana' }
        Save "$($baseline.Name).json" @{SchemaVersion=2;Metadata=@{ImageID=$id;OS=@{Family='alpine'};
            ImageConfig=@{os='linux';architecture='amd64';Env=@('SYNTHETIC_ENV_MUST_NOT_BE_PUBLISHED')}};
            Results=@(@{Class='os-pkgs';Type='alpine';Packages=@(@{Name='synthetic-os';Version='1.0'})},
                @{Class='lang-pkgs';Type='gobinary';Target=$binary;Packages=@(@{Name='stdlib';Version='v1.27.1'},@{Name=$module;Version='v1.0.0'});
                    Vulnerabilities=@(@{VulnerabilityID='CVE-2099-0001';PkgName='synthetic';InstalledVersion='1.0';FixedVersion='1.1';Severity='HIGH'})})}
        if ($baseline.Name -ceq 'prometheus') {
            $saved = Get-Content -Raw (Join-Path $directory 'prometheus.json') | ConvertFrom-Json -AsHashtable
            $saved.Results += @{Class='lang-pkgs';Type='gobinary';Target='bin/promtool';Packages=@(
                @{Name='stdlib';Version='v1.27.1'},@{Name=$module;Version='v1.0.0'})}
            Save 'prometheus.json' $saved
        }
    }
    Save 'images.json' @{Checkout=('d' * 40);Scanner=@{Version='0.75.0';VulnerabilityDB=$database;JavaDB=$database};Targets=$targets}
}
function Reject([string]$Name, [scriptblock]$Mutation, [string]$Expected) {
    ResetFixture
    & $Mutation
    $output = Join-Path $directory $Name
    $message = $null
    try { & (Join-Path $PSScriptRoot 'report-observability.ps1') -Directory $directory -OutputDirectory $output }
    catch { $message = $_.Exception.Message }
    if (-not $message -or $message -notlike "*$Expected*") { throw "Controle ${Name}: não recusou pelo motivo esperado ($Expected)." }
    if (Test-Path (Join-Path $output 'inventory.json')) { throw 'Controle publicou inventário inválido.' }
    Write-Host "PASS observação: $Name recusado."
}
ResetFixture
$output = Join-Path $directory 'valid'
& (Join-Path $PSScriptRoot 'report-observability.ps1') -Directory $directory -OutputDirectory $output
$text = Get-Content -Raw (Join-Path $output 'inventory.json')
$result = $text | ConvertFrom-Json -AsHashtable
if ($result.Reports.Count -ne 2 -or @($result.Reports | Where-Object { $_.Counts.HIGH -ne 1 }).Count -ne 0 -or
    $text.Contains('SYNTHETIC_ENV_MUST_NOT_BE_PUBLISHED') -or -not $text.Contains('2026-10-16T01:13:54.947090392Z') -or
    @(Get-ChildItem -LiteralPath $output -File).Count -ne 4) { throw 'Controle válido perdeu contagens/timestamps/arquivos ou publicou ambiente.' }
Write-Host 'PASS observação: dois alvos/contagens preservados, ambiente excluído.'
Reject 'swapped-image' {
    $report = Get-Content -Raw (Join-Path $directory 'grafana.json') | ConvertFrom-Json -AsHashtable
    $report.Metadata.ImageID = 'sha256:' + ('a' * 64)
    Save 'grafana.json' $report
} 'Identidade da imagem divergente'
Reject 'missing-target' {
    $manifest = Get-Content -Raw (Join-Path $directory 'images.json') | ConvertFrom-Json -AsHashtable
    $manifest.Targets = @($manifest.Targets[0])
    Save 'images.json' $manifest
} 'Esperados dois alvos distintos'
Reject 'bad-source' {
    $manifest = Get-Content -Raw (Join-Path $directory 'images.json') | ConvertFrom-Json -AsHashtable
    $manifest.Targets[0].Reference = 'other/image:unreviewed'
    Save 'images.json' $manifest
} 'Fonte/plataforma pinada divergente'
Reject 'wrong-platform' {
    $manifest = Get-Content -Raw (Join-Path $directory 'images.json') | ConvertFrom-Json -AsHashtable
    $manifest.Targets[0].Architecture = 'arm64'
    Save 'images.json' $manifest
} 'Plataforma/ImageID inválidos'
Reject 'missing-db' {
    $manifest = Get-Content -Raw (Join-Path $directory 'images.json') | ConvertFrom-Json -AsHashtable
    $manifest.Scanner.JavaDB = @{}
    Save 'images.json' $manifest
} 'Base/metadados ausentes'
Reject 'missing-go' {
    $report = Get-Content -Raw (Join-Path $directory 'grafana.json') | ConvertFrom-Json -AsHashtable
    $report.Results = @($report.Results | Where-Object { $_.Type -cne 'gobinary' })
    Save 'grafana.json' $report
} 'Cobertura Go/stdlib ausente'
Reject 'missing-organization-module' {
    $report = Get-Content -Raw (Join-Path $directory 'grafana.json') | ConvertFrom-Json -AsHashtable
    $report.Results[1].Packages = @($report.Results[1].Packages | Where-Object { $_.Name -ceq 'stdlib' })
    Save 'grafana.json' $report
} 'Módulos Go da organização ausentes'
Reject 'missing-os' {
    $report = Get-Content -Raw (Join-Path $directory 'prometheus.json') | ConvertFrom-Json -AsHashtable
    $report.Results = @($report.Results | Where-Object { $_.Class -cne 'os-pkgs' })
    Save 'prometheus.json' $report
} 'Cobertura OS ausente'
Reject 'bad-severity' {
    $report = Get-Content -Raw (Join-Path $directory 'grafana.json') | ConvertFrom-Json -AsHashtable
    $report.Results[1].Vulnerabilities[0].Severity = 'UNRECOGNIZED'
    Save 'grafana.json' $report
} 'Achado sem identidade/severidade'
Reject 'missing-tar-hash' {
    $manifest = Get-Content -Raw (Join-Path $directory 'images.json') | ConvertFrom-Json -AsHashtable
    $manifest.Targets[0].TarSha256 = ''
    Save 'images.json' $manifest
} 'Hash do tar ausente ou inválido'
ResetFixture
$report = Get-Content -Raw (Join-Path $directory 'grafana.json') | ConvertFrom-Json -AsHashtable
$report.Results[1].Packages += @{Name='github.com/grafana/grafana/pkg/local';Version=''}
Save 'grafana.json' $report
$output = Join-Path $directory 'unversioned-go'
& (Join-Path $PSScriptRoot 'report-observability.ps1') -Directory $directory -OutputDirectory $output
$result = Get-Content -Raw (Join-Path $output 'inventory.json') | ConvertFrom-Json -AsHashtable
$grafana = @($result.Reports | Where-Object { $_.Name -ceq 'grafana' })[0]
$local = @($grafana.Results.Packages | Where-Object { $_.Name -ceq 'github.com/grafana/grafana/pkg/local' })
if ($grafana.Coverage.UnversionedGoPackages -ne 1 -or $local.Count -ne 1 -or
    $null -ne $local[0].Version -or $local[0].VersionStatus -cne 'unversioned') { throw 'Módulo Go local sem versão deve permanecer explícito no inventário.' }
Write-Host 'PASS observação: módulo Go sem versão preservado como lacuna explícita.'
Reject 'missing-main-binary' {
    $report = Get-Content -Raw (Join-Path $directory 'grafana.json') | ConvertFrom-Json -AsHashtable
    $report.Results[1].Target = 'bin/auxiliary-tool'
    Save 'grafana.json' $report
} 'Binário principal Go ausente ou incompleto'
Write-Host '13 controles observação passaram; fixtures sintéticas não são scan real.'
