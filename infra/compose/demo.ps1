#requires -Version 7.3
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('Prepare', 'Activate', 'Demo', 'Smoke', 'Down')][string]$Action,
    [ValidatePattern('^credpay-[a-z0-9][a-z0-9-]{0,40}$')][string]$Project = 'credpay-demo',
    [ValidateSet('.env', '.env.example')][string]$EnvFile = '.env',
    [switch]$Diagnostics,
    [switch]$FaultDiagnostics
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot 'json.ps1')
. (Join-Path $PSScriptRoot 'metrics.ps1')
. (Join-Path $PSScriptRoot 'diagnostic-fault.ps1')
if ($FaultDiagnostics) { Assert-CredPayFaultMode $Action $Project $EnvFile $Diagnostics }
if ($FaultDiagnostics) { . (Join-Path $PSScriptRoot 'diagnostic-evidence.ps1') }
$diagnosticScope=$null
if($FaultDiagnostics -and $Project -cmatch '^credpay-diag-') {
    . (Join-Path $PSScriptRoot 'diagnostic-local.ps1')
    . (Join-Path $PSScriptRoot 'diagnostic-http.ps1')
    $diagnosticScope=Get-CredPayDiagnosticChild $root $Project
}
$composeArgs = @('compose', '--project-name', $Project, '--env-file', (Join-Path $PSScriptRoot $EnvFile),
    '--file', (Join-Path $PSScriptRoot 'compose.yaml'))
if ($Diagnostics) { $composeArgs += @('--file', (Join-Path $PSScriptRoot 'compose.diagnostics.yaml')) }
if($null -ne $diagnosticScope) { $composeArgs+=@('--file',$diagnosticScope.Runtime.Plan.Overlay,'--ansi','never') }
$apps = @('transacoes', 'processamento')
$queueNames = @('credpay.processamento.transacao-criada.v1', 'credpay.processamento.transacao-criada.dlq.v1',
    'credpay.transacoes.transacao-processada.v1', 'credpay.transacoes.transacao-processada.dlq.v1')
$policies = @('processamento', 'transacoes' | ForEach-Object {
    (Get-Content -Raw (Join-Path $root "infra/rabbitmq/$_-policies.json") | ConvertFrom-Json).policies
})

function Require([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function DiagnosticProgress([string]$Phase,[string]$Category='running',[string]$Restoration='not-attempted') {
    if($null -eq $diagnosticScope) { return }
    Require ($Phase -cmatch '^[a-z-]{1,60}$' -and $Category -cin @('running','binding','outbox','http','metric','scope','unclassified','complete')) 'Fase diagnóstica inválida.'
    Require ($Restoration -cin @('not-attempted','verified','unverified')) 'Estado de restauração inválido.'
    $plan=$diagnosticScope.Runtime.Plan
    $http=if($script:diagnosticHttpFailure -cin @('none','destination','transport','size','last-status','header-separator','status-line','header-field')) { $script:diagnosticHttpFailure } else { 'none' }
    $httpStatus=[int]$script:diagnosticHttpStatus
    if($httpStatus -lt 0 -or $httpStatus -gt 599) { $httpStatus=0 }
    Write-CredPayLocalJson @{phase=$Phase;category=$Category;restoration=$Restoration;http=$http;httpStatus=$httpStatus} (Join-Path $plan.Directory 'progress.json') $plan.Root
}

function Compose {
    $operationArguments=@($args)
    if($null -ne $diagnosticScope -and ($args[0] -cin @('up','stop','down','cp') -or
        ($args[0] -ceq 'exec' -and (@($args) -contains 'eval' -or @($args) -contains 'import_definitions')))) {
        $null=Assert-CredPayDiagnosticScope $diagnosticScope.Runtime $diagnosticScope.Owner
    }
    if($FaultDiagnostics -and $args[0] -ceq 'up') { $operationArguments=@('up','--pull','never')+@($args | Select-Object -Skip 1) }
    if($null -ne $diagnosticScope -and $diagnosticScope.Runtime.Compose) {
        $directArgs=@($composeArgs | Select-Object -Skip 1)
        $output=& $diagnosticScope.Runtime.Compose @directArgs @operationArguments 2>&1 | Out-String
    } else { $output = & docker @composeArgs @operationArguments 2>&1 | Out-String }
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose falhou em '$($args[0])', exit=$LASTEXITCODE; manter consumo desligado e diagnosticar localmente sem exportar env/logs."
    }
    return $output.Trim()
}

function Rabbit {
    $output = Compose exec -T rabbit rabbitmqctl --timeout 10 --quiet --formatter=json @args
    try { return ($output | ConvertFrom-Json) } catch { throw 'Consulta RabbitMQ não retornou JSON válido; saída omitida.' }
}

function Named($Rows, [string]$Name) {
    $foundRows = @($Rows | Where-Object name -CEQ $Name)
    Require ($foundRows.Count -eq 1) 'Recurso RabbitMQ ausente ou duplicado.'
    return $foundRows[0]
}

function SameDefinition($Actual, $Expected) {
    Require ($null -ne $Actual) 'Definição de política ausente.'
    $keys = @($Expected.PSObject.Properties.Name | Sort-Object)
    Require ((@($Actual.PSObject.Properties.Name | Sort-Object) -join ',') -ceq ($keys -join ',')) 'Chaves da política divergentes.'
    foreach ($key in $keys) { Require ($Actual.$key -ceq $Expected.$key) 'Valor da política divergente.' }
}

function NoConsumers {
    Require (@(Rabbit list_consumers).Count -eq 0) 'Preparação encontrou consumidores; não importar nem ativar.'
}

function CheckPoliciesBeforeImport {
    Require (@(Rabbit list_operator_policies -p /).Count -eq 0) 'Operator policy exige revisão; importação interrompida.'
    foreach ($actual in @(Rabbit list_policies -p /)) {
        $expected = Named $policies $actual.name
        Require ($actual.pattern -ceq $expected.pattern -and $actual.'apply-to' -ceq $expected.'apply-to' -and
            $actual.priority -eq $expected.priority -and $actual.vhost -ceq '/') 'Política concorrente exige revisão.'
        # list_policies codifica definition como texto JSON; a definição efetiva da fila é objeto.
        $definition = if ($actual.definition -is [string]) { Json $actual.definition } else { $actual.definition }
        SameDefinition $definition $expected.definition
    }
}

function CheckPreparation {
    $flag = Named @(Rabbit list_feature_flags name state) 'stream_queue'
    Require ($flag.state -ceq 'enabled') 'Flag stream_queue não habilitada.'
    CheckPoliciesBeforeImport
    Require (@(Rabbit list_policies -p /).Count -eq 4) 'Esperadas quatro políticas.'
    $queues = @(Rabbit list_queues -p / name type durable arguments policy operator_policy effective_policy_definition)
    Require ($queues.Count -eq 4) 'Esperadas quatro filas próprias.'
    for ($i = 0; $i -lt 4; $i++) {
        $queue = Named $queues $queueNames[$i]
        Require ($queue.type -ceq 'quorum' -and $queue.durable -eq $true) 'Fila deve ser quorum durável.'
        $argumentsJson = ConvertTo-Json -InputObject $queue.arguments -Depth 5 -Compress
        Require ($argumentsJson -ceq '[["x-queue-type","longstr","quorum"]]') 'Argumentos da fila divergentes.'
        Require ($queue.policy -ceq $policies[$i].name -and $queue.operator_policy -ceq '') 'Política efetiva da fila divergente.'
        SameDefinition $queue.effective_policy_definition $policies[$i].definition
    }
    $exchanges = @(Rabbit list_exchanges -p / name type durable)
    foreach ($name in @('credpay.transacoes.v1', 'credpay.processamento.v1', 'credpay.transacoes.dlx.v1', 'credpay.processamento.dlx.v1')) {
        $exchange = Named $exchanges $name
        Require ($exchange.type -ceq 'direct' -and $exchange.durable -eq $true) 'Exchange deve ser direct durável.'
    }
    $bindings = @(Rabbit list_bindings -p / source_name destination_name destination_kind routing_key)
    $sources = @('credpay.transacoes.v1', 'credpay.processamento.dlx.v1', 'credpay.processamento.v1', 'credpay.transacoes.dlx.v1')
    $routes = @('transacao.criada.v1', 'transacao.criada.dlq.v1', 'transacao.processada.v1', 'transacao.processada.dlq.v1')
    for ($i = 0; $i -lt 4; $i++) {
        $matched = @($bindings | Where-Object { $_.source_name -ceq $sources[$i] -and
            $_.destination_name -ceq $queueNames[$i] -and $_.destination_kind -ceq 'queue' -and $_.routing_key -ceq $routes[$i] })
        Require ($matched.Count -eq 1) 'Binding esperado ausente ou duplicado.'
    }
}

function CheckApps {
    foreach ($app in $apps) {
        Require ((Compose exec -T $app id -u) -ceq '10001') 'Aplicativo não executa como UID10001.'
        $id = Compose ps -q $app
        $raw = & docker inspect $id 2>&1 | Out-String
        Require ($LASTEXITCODE -eq 0) 'Inspect do aplicativo falhou.'
        $info = ($raw | ConvertFrom-Json)[0]
        Require ($info.Config.User -ceq '10001:10001' -and @($info.Mounts).Count -eq 0 -and
            $info.HostConfig.Privileged -eq $false) 'Usuário/mounts/privilégios do aplicativo divergentes.'
        $health = Request 'GET' "$($urls[$app])/actuator/health"
        Require ($health.StatusCode -eq 200 -and (Json $health.Content).status -ceq 'UP') 'Health do aplicativo não está UP.'
        Write-Host "Compose: $app health content type=$($health.Content.GetType().Name)."
    }
    Write-Host 'Compose: apps UID10001, mounts=0, privileged=false, health=UP.'
}

function StartApps([bool]$Topology, [bool]$Flow) {
    $env:CREDPAY_TOPOLOGY_ENABLED = $Topology.ToString().ToLowerInvariant()
    $env:CREDPAY_FLOW_ENABLED = $Flow.ToString().ToLowerInvariant()
    $null = Compose up -d --no-build --force-recreate --wait --wait-timeout 150 transacoes processamento
    CheckApps
    if ($Diagnostics) { CheckMetricsEndpoints }
}

function CheckMetricsEndpoints {
    foreach ($app in $apps) {
        $response = Request 'GET' "$($urls[$app])/actuator/metrics"
        Require ($response.StatusCode -eq 200) "Diagnostics exige /actuator/metrics HTTP200 após healthUP; observado=$([int]$response.StatusCode)."
        $body = Json $response.Content
        Require ($null -ne $body.names -and $body.names -is [array]) 'Lista de métricas inválida.'
    }
    Write-Host 'Compose diagnostics: lista metrics HTTP200 nos dois apps.'
}

function Prepare {
    $null = Compose stop transacoes processamento
    $null = Compose up -d --wait --wait-timeout 150 db-transacoes db-processamento rabbit
    NoConsumers
    StartApps $false $false
    NoConsumers
    foreach ($name in @('credpay.transacoes.v1', 'credpay.processamento.v1')) {
        $exchange = Named @(Rabbit list_exchanges -p / name type durable) $name
        Require ($exchange.type -ceq 'direct' -and $exchange.durable -eq $true) 'Exchange produtora ausente.'
    }
    Write-Host 'Compose: fase1 flags false, exchanges produtoras e consumidores=0.'
    StartApps $true $false
    NoConsumers
    CheckPoliciesBeforeImport
    foreach ($service in @('processamento', 'transacoes')) {
        $null = Compose cp (Join-Path $root "infra/rabbitmq/$service-policies.json") "rabbit:/tmp/$service-policies.json"
        $null = Compose exec -T rabbit rabbitmqctl --timeout 10 import_definitions "/tmp/$service-policies.json"
    }
    CheckPreparation
    NoConsumers
    Write-Host 'Compose: fase2 quatro filas quorum/políticas/bindings conferidos, consumidores=0.'
}

function Activate {
    NoConsumers
    CheckPreparation
    StartApps $true $true
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $consumers = @(Rabbit list_consumers -p /)
        $valid = $consumers.Count -eq 2
        foreach ($index in @(0, 2)) {
            $expectedPrefetch = if ($index -eq 0) { 10 } else { 1 }
            $matching = @($consumers | Where-Object { $_.queue_name -ceq $queueNames[$index] -and
                $_.ack_required -eq $true -and $_.prefetch_count -eq $expectedPrefetch })
            $valid = $valid -and $matching.Count -eq 1
        }
        if ($valid) { Write-Host 'Compose: fase3 consumidores=2, ack obrigatório, prefetch=10/1.'; return }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Consumidores não convergiram; parar apps e diagnosticar antes de publicar.'
}

function Request([string]$Method, [string]$Url, [string]$Key = '', [string]$Value = '') {
    if($null -ne $diagnosticScope) {
        $script:diagnosticHttpFailure='destination'
        $uri=[Uri]$Url
        Require ($uri.Scheme -ceq 'http' -and $uri.Host -cin $apps -and $uri.Port -eq 8080 -and $uri.UserInfo -ceq '') 'Destino HTTP interno fora do roteiro.'
        $curl=@('exec','-T',$uri.Host,'curl','--silent','--show-error','--max-time','3','--noproxy','*','--max-redirs','0',
            '--include','--write-out',"`n%{http_code}",'--request',$Method,'--url',('http://127.0.0.1:8080'+$uri.PathAndQuery))
        if($Method -ceq 'POST') {
            $parsedKey=[Guid]::Empty
            Require ([Guid]::TryParseExact($Key,'D',[ref]$parsedKey) -and $Value -cmatch '^[0-9]+(\.[0-9]{1,3})?$') 'Pedido fictício fora do roteiro.'
            $curl+=@('--header','Content-Type: application/json','--header',"Idempotency-Key: $Key",'--data',('{"valor":'+$Value+',"moeda":"BRL"}'))
        }
        $script:diagnosticHttpFailure='transport'
        return (Read-CredPayDiagnosticHttp (DiagnosticComposeStdout $curl))
    }
    try {
        if ($Method -ceq 'POST') {
            return Invoke-WebRequest -Method POST -Uri $Url -Headers @{'Idempotency-Key'=$Key} -ContentType 'application/json' `
                -Body ('{"valor":' + $Value + ',"moeda":"BRL"}') -TimeoutSec 3 -SkipHttpErrorCheck -MaximumRedirection 0 -NoProxy
        }
        return Invoke-WebRequest -Method GET -Uri $Url -TimeoutSec 3 -SkipHttpErrorCheck -MaximumRedirection 0 -NoProxy
    } catch { throw 'Requisição HTTP falhou; corpo/credenciais omitidos.' }
}

function WaitFinal([string]$Location, [string]$Status) {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $response = Request 'GET' "$($urls.transacoes)$Location"
        Require ($response.StatusCode -eq 200) 'GET não retornou200.'
        $body = Json $response.Content
        if ($body.status -ceq $Status) { return $response.Content }
        Require ($body.status -ceq 'PENDENTE') 'GET retornou estado inesperado.'
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'GET não convergiu no prazo.'
}

function Demo {
    $records = @()
    $baselines = @{}
    if ($Diagnostics) {
        CheckMetricsEndpoints
        foreach ($app in $apps) { $baselines[$app] = ReadConfirmedCount $app }
    }
    foreach ($case in @(@{Value='50.000'; Status='APROVADA'}, @{Value='150.000'; Status='REJEITADA'})) {
        $key = [Guid]::NewGuid().ToString()
        $post = Request 'POST' "$($urls.transacoes)/transacoes" $key $case.Value
        Require ($post.StatusCode -eq 201 -and (Json $post.Content).status -ceq 'PENDENTE') 'POST não retornou201/PENDENTE.'
        $id = (Json $post.Content).id
        $parsedId = [Guid]::Empty
        Require ([Guid]::TryParse($id, [ref]$parsedId)) 'POST não retornou UUID.'
        $location = [string]$post.Headers.Location[0]
        Require ($location -ceq "/transacoes/$id") 'Location divergente.'
        $final = WaitFinal $location $case.Status
        $body = Json $final
        Require ($body.id -ceq $id -and $body.moeda -ceq 'BRL' -and [decimal]$body.valor -eq [decimal]$case.Value) 'GET alterou dados da transação.'
        $replay = Request 'POST' "$($urls.transacoes)/transacoes" $key ([decimal]$case.Value).ToString([Globalization.CultureInfo]::InvariantCulture)
        Require ($replay.StatusCode -eq 201 -and $replay.Content -ceq $post.Content -and [string]$replay.Headers.Location[0] -ceq $location) 'Replay alterou POST original.'
        $records += @{Key=$key; Location=$location; Post=$post.Content; Final=$final; Status=$case.Status; Value=$case.Value}
        Write-Host "Compose: fluxo=$($case.Status), replay POST original estável."
    }
    if ($Diagnostics) { WaitPublicationMetrics $baselines }
    return $records
}

function ReadConfirmedCount([string]$App) {
    $response = Request 'GET' "$($urls[$App])/actuator/metrics/credpay.messaging.publish.attempts?tag=outcome:confirmed"
    if ($response.StatusCode -eq 404) { return 0 }
    Require ($response.StatusCode -eq 200) 'Consulta do contador de publicação não retornou HTTP200/404.'
    return (PublishAttemptCount (Json $response.Content))
}

function WaitPublicationMetrics($Baselines) {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $ready = $true
        $deltas = @{}
        foreach ($app in $apps) {
            $current = ReadConfirmedCount $app
            Require ($current -ge $Baselines[$app]) 'Contador reiniciou durante a demo; repetir em processo estável.'
            $deltas[$app] = $current - $Baselines[$app]
            $ready = $ready -and $deltas[$app] -ge 2
        }
        if ($ready) {
            Write-Host "Compose diagnostics: confirmed delta transacoes=$($deltas.transacoes), processamento=$($deltas.processamento)."
            return
        }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Diagnostics não observou delta confirmed>=2 em ambos no prazo; transacoes=$($deltas.transacoes), processamento=$($deltas.processamento)."
}

$savedTopology = $env:CREDPAY_TOPOLOGY_ENABLED
$savedFlow = $env:CREDPAY_FLOW_ENABLED
$smokeOwned = $false
try {
    DiagnosticProgress 'config'
    $env:CREDPAY_TOPOLOGY_ENABLED = 'false'
    $env:CREDPAY_FLOW_ENABLED = 'false'
    $config = Json (Compose config --format json)
    foreach ($app in $apps) { Require ($config.services.$app.image -cmatch ('^credpay-' + $app + ':[0-9a-f]{40}$')) 'Tag de imagem deve ser SHA completo.' }
    $urls = @{}
    foreach ($app in $apps) {
        if($null -ne $diagnosticScope) {
            Require (@($config.services.$app.ports | Where-Object { $null -ne $_ }).Count -eq 0) 'Diagnóstico não publica portas no host.'
            $urls[$app]="http://${app}:8080"
            continue
        }
        $port = @($config.services.$app.ports)[0]
        Require ($port.host_ip -ceq '127.0.0.1' -and [int]$port.published -gt 0) 'HTTP deve estar vinculado ao loopback.'
        $urls[$app] = "http://127.0.0.1:$($port.published)"
    }
    switch ($Action) {
        Prepare { Prepare }
        Activate { Activate }
        Demo { $null = Demo }
        Down { $null = Compose down --timeout 30; Write-Host 'Compose: projeto desligado; volumes preservados.' }
        Smoke {
            Require ((Compose ps --all -q) -ceq '') 'Smoke exige projeto descartável sem containers anteriores.'
            $volumesBefore = @(& docker volume ls --quiet --filter "label=com.docker.compose.project=$Project")
            Require ($LASTEXITCODE -eq 0 -and $volumesBefore.Count -eq 0) 'Smoke exige projeto sem volumes anteriores.'
            $networksBefore = @(& docker network ls --quiet --filter "label=com.docker.compose.project=$Project")
            Require ($LASTEXITCODE -eq 0 -and $networksBefore.Count -eq 0) 'Smoke exige projeto sem redes anteriores.'
            $smokeOwned = $true
            DiagnosticProgress 'prepare'
            Prepare
            DiagnosticProgress 'activate'
            Activate
            DiagnosticProgress 'demo'
            $records = @(Demo)
            if ($FaultDiagnostics) {
                foreach ($publisher in $apps) { DiagnosticProgress "fault-$publisher"; Invoke-CredPayMetricFault $publisher }
            }
            $volumes = @(& docker volume ls --quiet --filter "label=com.docker.compose.project=$Project" | Sort-Object)
            Require ($LASTEXITCODE -eq 0 -and $volumes.Count -eq 3) 'Esperados três volumes próprios.'
            $null = Compose down --timeout 30
            DiagnosticProgress 'persistence-prepare'
            Prepare
            DiagnosticProgress 'persistence-activate'
            Activate
            DiagnosticProgress 'persistence-check'
            foreach ($record in $records) {
                Require ((WaitFinal $record.Location $record.Status) -ceq $record.Final) 'GET persistido mudou após down/up.'
                $replay = Request 'POST' "$($urls.transacoes)/transacoes" $record.Key $record.Value
                Require ($replay.StatusCode -eq 201 -and $replay.Content -ceq $record.Post -and [string]$replay.Headers.Location[0] -ceq $record.Location) 'Replay persistido mudou após down/up.'
            }
            $volumesAfter = @(& docker volume ls --quiet --filter "label=com.docker.compose.project=$Project" | Sort-Object)
            Require ($LASTEXITCODE -eq 0 -and ($volumesAfter -join ',') -ceq ($volumes -join ',')) 'Volumes mudaram após down/up.'
            Write-Host 'Compose: down/up conservou três volumes, GET final e POST original dos dois resultados.'
            DiagnosticProgress 'complete' 'complete'
        }
    }
} catch {
    if($null -ne $diagnosticScope) {
        $progressPath=Join-Path $diagnosticScope.Runtime.Plan.Directory 'progress.json'
        $progress=if(Test-Path -LiteralPath $progressPath) { Read-CredPayLocalJson $progressPath $root } else { @{phase='startup'} }
        $category=if($_.Exception.Message -match '(?i)binding|rota') { 'binding' } elseif($_.Exception.Message -match '(?i)outbox|causalidade|registros') { 'outbox' }
            elseif($_.Exception.Message -match '(?i)HTTP|GET|POST') { 'http' } elseif($_.Exception.Message -match '(?i)métrica|contador|returned') { 'metric' }
            elseif($_.Exception.Message -match '(?i)propriedade|daemon|container|volume|recurso') { 'scope' } else { 'unclassified' }
        DiagnosticProgress $progress.phase $category (Get-CredPayFaultRestoration $_.Exception.Message)
    }
    if ($Action -in @('Prepare', 'Activate') -or ($Action -ceq 'Smoke' -and $smokeOwned)) {
        try { $null = Compose stop transacoes processamento } catch { Write-Warning 'Não foi possível parar apps; verificar o projeto antes de continuar.' }
    }
    throw
} finally {
    $env:CREDPAY_TOPOLOGY_ENABLED = $savedTopology
    $env:CREDPAY_FLOW_ENABLED = $savedFlow
}
