#requires -Version 7.3
param([Parameter(Mandatory)][string]$Directory, [Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
function Require($condition, [string]$message) { if (-not $condition) { throw $message } }
function Read-Json([string]$name) {
    return [IO.File]::ReadAllText((Join-Path $Directory $name)) | ConvertFrom-Json -AsHashtable
}
$scanner = Read-Json 'scanner.json'
Require ($scanner.Version -ceq '0.75.0') 'Versão do scanner divergente.'
foreach ($database in @('VulnerabilityDB', 'JavaDB')) {
    Require ($scanner[$database].Version -gt 0 -and $scanner[$database].UpdatedAt -and
        $scanner[$database].DownloadedAt) "Base/metadados ausentes: $database"
}
$checkout = ([IO.File]::ReadAllText((Join-Path $Directory 'checkout.txt'))).Trim()
Require ($checkout -cmatch '^[a-f0-9]{40}$') 'SHA do checkout inválido.'
$hashes = @([IO.File]::ReadAllLines((Join-Path $Directory 'jars.sha256')))
Require ($hashes.Count -eq 2) 'Manifesto dos JARs incompleto.'
$images = @{}
foreach ($module in @('transacoes', 'processamento')) {
    Require (@($hashes | Where-Object { $_ -cmatch "^[a-f0-9]{64}  \.local/security/inputs/$module/app\.jar$" }).Count -eq 1) "Hash do JAR inválido: $module"
    $image = ([IO.File]::ReadAllText((Join-Path $Directory "$module-image.txt"))).Trim()
    Require ($image -cmatch '^sha256:[a-f0-9]{64} linux amd64$') "Identidade/arquitetura da imagem inválida: $module"
    $images[$module] = ($image -split ' ')[0]
}
$reports = @()
$summary = @('# Inventário de vulnerabilidades — triagem pendente', '',
    "Checkout: $checkout. Scanner: 0.75.0. Este resultado não aprova segurança.", '',
    '| Alvo | Pacotes | UNKNOWN | LOW | MEDIUM | HIGH | CRITICAL |', '|---|---:|---:|---:|---:|---:|---:|')
foreach ($module in @('transacoes', 'processamento')) {
    foreach ($kind in @('jar', 'image')) {
        $name = "$module-$kind"
        $report = Read-Json "$name.json"
        Require ($report.SchemaVersion -eq 2 -and $report.Results.Count -gt 0) "Relatório vazio/inválido: $name"
        $java = @($report.Results | Where-Object { $_.Class -ceq 'lang-pkgs' -and $_.Type -ceq 'jar' })
        $packages = @($java | ForEach-Object { $_.Packages } | Where-Object { $null -ne $_ })
        foreach ($library in @('spring-boot', 'postgresql', 'spring-rabbit')) {
            Require (@($packages | Where-Object { $_.Name -match ":$library$" }).Count -gt 0) "Cobertura Java incompleta: $name/$library"
        }
        if ($kind -ceq 'image') {
            Require ($report.Metadata.ImageID -ceq $images[$module] -and
                $report.Metadata.ImageConfig.os -ceq 'linux' -and
                $report.Metadata.ImageConfig.architecture -ceq 'amd64') "Relatório de outra imagem/arquitetura: $name"
            Require ($report.Metadata.OS.Family -ceq 'ubuntu' -and
                @($report.Results | Where-Object { $_.Class -ceq 'os-pkgs' -and $_.Packages.Count -gt 0 }).Count -gt 0) "Cobertura OS ausente: $name"
        }
        $results = @()
        $counts = @{UNKNOWN=0; LOW=0; MEDIUM=0; HIGH=0; CRITICAL=0}
        $packageCount = 0
        foreach ($result in $report.Results) {
            $safePackages = @($result.Packages | Where-Object { $null -ne $_ } | ForEach-Object {
                Require ($_.Name -and $_.Version) "Pacote sem identidade/versão: $name"
                @{Name=$_.Name; Version=$_.Version}
            })
            $packageCount += $safePackages.Count
            $vulnerabilities = @($result.Vulnerabilities | Where-Object { $null -ne $_ } | ForEach-Object {
                Require ($_.VulnerabilityID -and $_.PkgName -and $_.InstalledVersion -and $counts.ContainsKey($_.Severity)) "Achado sem identidade/severidade: $name"
                $counts[$_.Severity]++
                @{Id=$_.VulnerabilityID; Package=$_.PkgName; Installed=$_.InstalledVersion;
                    Fixed=$_.FixedVersion; Severity=$_.Severity; Status=$_.Status; Url=$_.PrimaryURL}
            })
            $results += @{Class=$result.Class; Type=$result.Type; Packages=$safePackages; Vulnerabilities=$vulnerabilities}
        }
        $safeReport = @{Name=$name; Results=$results; Counts=$counts}
        if ($kind -ceq 'image') { $safeReport.Image = @{Id=$report.Metadata.ImageID; Os=$report.Metadata.ImageConfig.os; Architecture=$report.Metadata.ImageConfig.architecture} }
        $reports += $safeReport
        $summary += "| $name | $packageCount | $($counts.UNKNOWN) | $($counts.LOW) | $($counts.MEDIUM) | $($counts.HIGH) | $($counts.CRITICAL) |"
        Write-Host "$name : pacotes=$packageCount HIGH=$($counts.HIGH) CRITICAL=$($counts.CRITICAL); triagem pendente."
        foreach ($finding in @($results.Vulnerabilities | Where-Object { $_.Severity -in @('HIGH', 'CRITICAL') })) {
            Write-Host ("{0}: {1} {2} {3} instalado={4} corrigido={5}" -f $name, $finding.Severity, $finding.Id, $finding.Package, $finding.Installed, $finding.Fixed)
        }
    }
}
$summary += @('', 'Não somar JAR e imagem como achados únicos: bibliotecas se repetem.',
    'Escopo: dependências empacotadas e imagens dos apps; não inclui PostgreSQL, RabbitMQ, build ou Kubernetes.')
$null = New-Item -ItemType Directory -Path $OutputDirectory -Force
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'inventory.json'),
    (@{Checkout=$checkout; Scanner=@{Version=$scanner.Version; VulnerabilityDB=$scanner.VulnerabilityDB; JavaDB=$scanner.JavaDB}; Reports=$reports} | ConvertTo-Json -Depth 15), $utf8)
[IO.File]::WriteAllLines((Join-Path $OutputDirectory 'summary.md'), $summary, $utf8)
foreach ($file in @('checkout.txt','jars.sha256','transacoes-image.txt','processamento-image.txt')) {
    Copy-Item -LiteralPath (Join-Path $Directory $file) -Destination (Join-Path $OutputDirectory $file)
}
