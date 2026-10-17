#requires -Version 7.3
[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^credpay-observe-ci-[0-9]+-[0-9]+$')][string]$Project)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $root 'infra/compose/json.ps1')
. (Join-Path $PSScriptRoot 'http.ps1')
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
    # HTTP real pela rede interna; não depende de publicar a UI no host.
    $response = Compose exec -T transacoes curl --silent --show-error --max-time 5 `
        --header $script:authorization --write-out "`nCREDPAY_HTTP_STATUS:%{http_code}" "http://grafana:3000$Path"
    return (ParseInternalHttp $response)
}
function Query([string]$Expression, [double]$At = 0) {
    $encoded = [Uri]::EscapeDataString($Expression)
    $path = "/api/datasources/proxy/uid/credpay-prometheus/api/v1/query?query=$encoded&timeout=3s"
    if ($At -gt 0) { $path += '&time=' + $At.ToString('F3', [Globalization.CultureInfo]::InvariantCulture) }
    $response = Request $path
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
$credential = "$($config.services.grafana.environment.GF_SECURITY_ADMIN_USER):$($config.services.grafana.environment.GF_SECURITY_ADMIN_PASSWORD)"
$script:authorization = 'Authorization: Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($credential))
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
    Write-Host 'Observação: API Grafana pela rede interna; acesso à UI pelo host ainda não comprovado.'
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
    $dashboard = Request '/api/dashboards/uid/credpay-publication'
    Require ($dashboard.StatusCode -eq 200) "Painel provisionado esperado HTTP200, observado=$($dashboard.StatusCode)."
    $model = (Json $dashboard.Content).dashboard
    Require ($model.uid -ceq 'credpay-publication' -and $model.panels.Count -eq 2 -and $model.editable -eq $false) 'Painel deve conter os dois sinais de diagnóstico e ser somente leitura.'
    foreach ($panel in $model.panels) {
        Require ($panel.datasource.uid -ceq 'credpay-prometheus' -and $panel.targets.Count -eq 1) 'Painel deve consultar somente o datasource próprio.'
    }
    Require ($model.panels[0].targets[0].expr -clike 'up{*' -and
        $model.panels[1].targets[0].expr -clike 'credpay_messaging_publish_attempts_total{*') 'Consultas do painel devem usar os sinais existentes.'
    $null = & (Join-Path $root 'infra/compose/demo.ps1') -Action Demo -Project $Project -EnvFile .env.example -Observability
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $attempts = @(Query $model.panels[1].targets[0].expr)
        $confirmed = @($attempts | Where-Object { $_.metric.outcome -ceq 'confirmed' -and [double]$_.value[1] -ge 2 })
        if (@($confirmed | Where-Object { $_.metric.job -ceq 'credpay-transacoes' }).Count -eq 1 -and
            @($confirmed | Where-Object { $_.metric.job -ceq 'credpay-processamento' }).Count -eq 1) { break }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    Require ($confirmed.Count -eq 2) 'Consulta do painel deve observar tentativas confirmadas dos dois apps após a demo real.'
    Write-Host 'Observação: painel provisionado disponível pela API; renderização visual não comprovada.'
    . (Join-Path $PSScriptRoot 'diagnose.ps1')
    DiagnosePublication
    $returned = @(Query 'credpay_messaging_publish_attempts_total{job="credpay-transacoes",outcome="returned"}')
    Require ($returned.Count -eq 1 -and [double]$returned[0].value[1] -gt 0) 'Ensaio diagnóstico deve tornar returned positivo; série ainda ausente sem falha controlada.'
} finally {
    if ((Test-Path -LiteralPath $marker) -and [IO.File]::ReadAllText($marker) -ceq $Project) {
        $null = Compose down --volumes --timeout 30
    }
}
