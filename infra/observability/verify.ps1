#requires -Version 7.3
[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^credpay-observe-ci-[0-9]+-[0-9]+$')][string]$Project)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $root 'infra/compose/json.ps1')
function Require([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
Require ($env:GITHUB_ACTIONS -ceq 'true') 'Ensaio automático autorizado somente no CI descartável.'
Require ($env:CREDPAY_IMAGE_TAG -cmatch '^[0-9a-f]{40}$') 'Checkout deve ter SHA completo.'
$composeArgs = @('compose', '--project-name', $Project, '--env-file', (Join-Path $root 'infra/compose/.env.example'),
    '--env-file', (Join-Path $PSScriptRoot 'images.env'), '--file', (Join-Path $root 'infra/compose/compose.yaml'),
    '--file', (Join-Path $root 'infra/compose/compose.diagnostics.yaml'), '--file', (Join-Path $PSScriptRoot 'compose.yaml'))
$marker = Join-Path $root ".local/observability/$Project.created"
function Compose {
    $output = & docker @composeArgs @args 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "Compose observação falhou: operação=$($args[0]); conteúdo omitido." }
    return $output.Trim()
}
function Request([string]$Path) {
    try { return (Invoke-WebRequest -Uri "$script:grafana$Path" -Headers $script:headers -TimeoutSec 5 -SkipHttpErrorCheck -MaximumRedirection 0) }
    catch { throw 'HTTP de observação indisponível; conteúdo omitido.' }
}
function Query([string]$Expression) {
    $encoded = [Uri]::EscapeDataString($Expression)
    $response = Request "/api/datasources/proxy/uid/credpay-prometheus/api/v1/query?query=$encoded&timeout=3s"
    Require ($response.StatusCode -eq 200) 'Consulta Prometheus deve retornar HTTP200.'
    $body = Json $response.Content
    Require ($body.status -ceq 'success' -and $body.data.resultType -ceq 'vector' -and
        $body.data.result -is [array]) 'Resposta da consulta Prometheus inválida.'
    return $body.data.result
}
function CheckCollection {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $series = @(Query 'up{job=~"credpay-(transacoes|processamento)"}')
        $valid = $series.Count -eq 2
        foreach ($service in @('transacoes', 'processamento')) {
            $matching = @($series | Where-Object { $_.metric.job -ceq "credpay-$service" -and
                $_.metric.instance -ceq "${service}:8080" -and $_.value.Count -eq 2 -and $_.value[1] -ceq '1' })
            $valid = $valid -and $matching.Count -eq 1
        }
        if ($valid) { Write-Host 'Observação: dois jobs próprios com scrape UP.'; return }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Coleta esperada=2 jobs UP, observada=$($series.Count) séries."
}
Require (-not (Test-Path -LiteralPath $marker)) 'Marcador já existe; não assumir propriedade de execução anterior.'
Require ((Compose ps --all -q) -ceq '') 'Ensaio exige projeto sem containers anteriores.'
foreach ($resource in @('volume', 'network')) {
    $existing = @(& docker $resource ls --quiet --filter "label=com.docker.compose.project=$Project")
    Require ($LASTEXITCODE -eq 0 -and $existing.Count -eq 0) 'Ensaio exige projeto sem volumes/redes anteriores.'
}
$config = Json (Compose config --format json)
foreach ($service in @('transacoes', 'processamento', 'grafana')) {
    $port = @($config.services.$service.ports)[0]
    Require ($port.host_ip -ceq '127.0.0.1' -and [int]$port.published -gt 0) 'HTTP deve estar vinculado ao loopback.'
}
Require (($null -eq $config.services.prometheus.ports -or @($config.services.prometheus.ports).Count -eq 0) -and
    $config.networks.observability.internal -eq $true) 'Coletor sem porta host e rede interna são obrigatórios.'
$script:grafana = "http://127.0.0.1:$(@($config.services.grafana.ports)[0].published)"
$credential = "$($config.services.grafana.environment.GF_SECURITY_ADMIN_USER):$($config.services.grafana.environment.GF_SECURITY_ADMIN_PASSWORD)"
$script:headers = @{Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($credential))}
$null = New-Item -ItemType Directory -Force (Split-Path $marker)
[IO.File]::WriteAllText($marker, $Project)
try {
    & (Join-Path $root 'infra/compose/demo.ps1') -Action Prepare -Project $Project -EnvFile .env.example -Observability
    & (Join-Path $root 'infra/compose/demo.ps1') -Action Activate -Project $Project -EnvFile .env.example -Observability
    $null = Compose up -d --wait --wait-timeout 150 prometheus grafana
    foreach ($service in @('prometheus', 'grafana')) {
        $containerId = Compose ps -q $service
        $raw = & docker inspect $containerId 2>&1 | Out-String
        Require ($LASTEXITCODE -eq 0) 'Inspect do serviço de observação falhou.'
        $info = (Json $raw)[0]
        $expected = if ($service -ceq 'prometheus') { '65534:65534' } else { '472:472' }
        Require ($info.Config.User -ceq $expected -and $info.HostConfig.Privileged -eq $false -and
            $info.HostConfig.ReadonlyRootfs -eq $true -and @($info.NetworkSettings.Networks.PSObject.Properties).Count -eq 1) 'Usuário/privilégios/filesystem/rede divergentes.'
        foreach ($mount in $info.Mounts) {
            if ($mount.Type -ceq 'bind') {
                Require ($mount.RW -eq $false -and $mount.Source.StartsWith($PSScriptRoot + [IO.Path]::DirectorySeparatorChar)) 'Config deve ser bind mínimo/somente leitura do diretório próprio.'
            } elseif ($mount.Type -ceq 'volume') {
                Require ($mount.Destination -cin @('/prometheus', '/var/lib/grafana')) 'Volume fora dos dados próprios.'
            } else { Require ($mount.Type -ceq 'tmpfs' -and $mount.Destination -ceq '/tmp') 'Mount inesperado no serviço de observação.' }
        }
    }
    $health = Request '/api/health'
    Require ($health.StatusCode -eq 200 -and (Json $health.Content).database -ceq 'ok') 'Grafana deve estar saudável.'
    $ready = Request '/api/datasources/proxy/uid/credpay-prometheus/-/ready'
    Require ($ready.StatusCode -eq 200) 'Prometheus deve estar ready antes da prova de coleta.'
    foreach ($service in @('transacoes', 'processamento')) {
        $port = @($config.services.$service.ports)[0].published
        try { $response = Invoke-WebRequest -Uri "http://127.0.0.1:$port/actuator/prometheus" -TimeoutSec 5 -SkipHttpErrorCheck -MaximumRedirection 0 }
        catch { throw 'Exportador do aplicativo indisponível; conteúdo omitido.' }
        Require ($response.StatusCode -eq 200) 'Exportador real deve estar acessível antes da prova de coleta.'
        try {
            $text = if ($response.Content -is [byte[]]) { [Text.UTF8Encoding]::new($false, $true).GetString($response.Content) }
                else { [string]$response.Content }
        } catch { throw 'Exportador não retornou UTF8 válido; conteúdo omitido.' }
        Require ($text -cmatch '(?m)^# TYPE [a-zA-Z_:][a-zA-Z0-9_:]* (counter|gauge|histogram|summary|untyped)\r?$') 'Exportador não retornou formato Prometheus antes da prova de coleta.'
    }
    Write-Host 'Observação: apps/exportadores200, Grafana saudável e Prometheus ready; consulta real próxima.'
    CheckCollection
} finally {
    if ((Test-Path -LiteralPath $marker) -and [IO.File]::ReadAllText($marker) -ceq $Project) {
        $null = Compose down --volumes --timeout 30
    }
}
