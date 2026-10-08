#requires -Version 7.3
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$directory = Join-Path $root ".local/security/controls/$([Guid]::NewGuid())"
$null = New-Item -ItemType Directory -Path $directory -Force
$utf8 = [Text.UTF8Encoding]::new($false)
function Save-Json($name, $value) {
    [IO.File]::WriteAllText((Join-Path $directory $name), ($value | ConvertTo-Json -Depth 20), $utf8)
}
function Reset-Fixture {
    $db = @{Version=2; UpdatedAt='2026-10-08T00:00:00Z'; DownloadedAt='2026-10-08T01:00:00Z'}
    Save-Json 'scanner.json' @{Version='0.75.0'; VulnerabilityDB=$db; JavaDB=$db}
    [IO.File]::WriteAllText((Join-Path $directory 'checkout.txt'), ('a' * 40), $utf8)
    $hashes = @()
    foreach ($module in @('transacoes', 'processamento')) {
        $hashes += ('b' * 64) + "  .local/security/inputs/$module/app.jar"
        $imageId = 'sha256:' + ($(if ($module -eq 'transacoes') { 'c' } else { 'd' }) * 64)
        [IO.File]::WriteAllText((Join-Path $directory "$module-image.txt"), ($imageId + ' linux amd64'), $utf8)
        foreach ($kind in @('jar', 'image')) {
            $java = @{Class='lang-pkgs'; Type='jar'; Packages=@(
                @{Name='org.springframework.boot:spring-boot'; Version='3.5.16'},
                @{Name='org.postgresql:postgresql'; Version='42.7.0'},
                @{Name='org.springframework.amqp:spring-rabbit'; Version='3.2.0'}
            ); Vulnerabilities=@(@{VulnerabilityID='CVE-2099-0001'; PkgName='synthetic-package';
                InstalledVersion='1.0'; FixedVersion='1.1'; Severity='HIGH'})}
            $results = @($java)
            if ($kind -eq 'image') { $results += @{Class='os-pkgs'; Type='ubuntu'; Packages=@(@{Name='synthetic-os'; Version='1.0'})} }
            Save-Json "$module-$kind.json" @{SchemaVersion=2; Metadata=@{ImageID=$imageId; OS=@{Family='ubuntu'}; ImageConfig=@{os='linux'; architecture='amd64'; Env=@('SYNTHETIC_ENV_MUST_NOT_BE_PUBLISHED')}}; Results=$results}
        }
    }
    [IO.File]::WriteAllLines((Join-Path $directory 'jars.sha256'), $hashes, $utf8)
}
function Reject([string]$name, [scriptblock]$mutate, [string]$expected) {
    Reset-Fixture
    & $mutate
    $caught = $null
    try { & "$PSScriptRoot/report.ps1" -Directory $directory -OutputDirectory (Join-Path $directory $name) }
    catch { $caught = $_.Exception.Message }
    if (-not $caught -or $caught -notlike "*$expected*") { throw "Controle ${name}: esperado '$expected'; observado '$caught'." }
    if (Test-Path (Join-Path $directory "$name/inventory.json")) { throw "Controle $name publicou relatório inválido." }
    Write-Host "PASS: $name rejeitado pelo motivo esperado."
}
Reset-Fixture
$output = Join-Path $directory 'valid'
& "$PSScriptRoot/report.ps1" -Directory $directory -OutputDirectory $output
$published = [IO.File]::ReadAllText((Join-Path $output 'inventory.json'))
$inventory = $published | ConvertFrom-Json -AsHashtable
if ($inventory.Reports.Count -ne 4 -or $inventory.Scanner.Version -ne '0.75.0' -or
    @($inventory.Reports | Where-Object { $_.Counts.HIGH -ne 1 }).Count -ne 0 -or
    $published.Contains('SYNTHETIC_ENV_MUST_NOT_BE_PUBLISHED')) { throw 'Inventário válido perdeu contagens/metadados ou publicou ambiente.' }
Write-Host 'PASS: quatro alvos válidos, metadados/contagens preservados e ambiente excluído.'
Reject 'empty-java' { Save-Json 'transacoes-jar.json' @{SchemaVersion=2; Results=@()} } 'Relatório vazio/inválido'
Reject 'missing-library' {
    $report = Get-Content (Join-Path $directory 'processamento-jar.json') -Raw | ConvertFrom-Json -AsHashtable
    $report.Results[0].Packages = @($report.Results[0].Packages | Where-Object { $_.Name -notmatch ':postgresql$' })
    Save-Json 'processamento-jar.json' $report
} 'Cobertura Java incompleta'
Reject 'missing-os' {
    $report = Get-Content (Join-Path $directory 'transacoes-image.json') -Raw | ConvertFrom-Json -AsHashtable
    $report.Results = @($report.Results | Where-Object { $_.Class -ne 'os-pkgs' })
    Save-Json 'transacoes-image.json' $report
} 'Cobertura OS ausente'
Reject 'missing-db' { Save-Json 'scanner.json' @{Version='0.75.0'; VulnerabilityDB=@{}} } 'Base/metadados ausentes'
Reject 'bad-provenance' { [IO.File]::WriteAllText((Join-Path $directory 'checkout.txt'), 'invalid', $utf8) } 'SHA do checkout inválido'
Reject 'bad-image' { [IO.File]::WriteAllText((Join-Path $directory 'transacoes-image.txt'), 'unknown', $utf8) } 'Identidade/arquitetura'
Reject 'swapped-image' {
    $report = Get-Content (Join-Path $directory 'transacoes-image.json') -Raw | ConvertFrom-Json -AsHashtable
    Save-Json 'processamento-image.json' $report
} 'Relatório de outra imagem/arquitetura'
Reject 'bad-severity' {
    $report = Get-Content (Join-Path $directory 'transacoes-jar.json') -Raw | ConvertFrom-Json -AsHashtable
    $report.Results[0].Vulnerabilities[0].Severity = 'UNRECOGNIZED'
    Save-Json 'transacoes-jar.json' $report
} 'Achado sem identidade/severidade'
Write-Host '9 controles passaram; fixtures sintéticas não são resultado de scan real.'
exit 0
