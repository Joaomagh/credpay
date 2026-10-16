#requires -Version 7.3
[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^credpay-ci-[0-9]+-[0-9]+$')][string]$Cluster)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$kubeconfig = Join-Path $root ".local/k8s/$Cluster.yaml"
$marker = Join-Path $root ".local/k8s/$Cluster.created"
$kubectl = Join-Path $root '.local/bin/kubectl'
. (Join-Path $root 'infra/compose/json.ps1')
$queueNames = @('credpay.processamento.transacao-criada.v1', 'credpay.processamento.transacao-criada.dlq.v1',
    'credpay.transacoes.transacao-processada.v1', 'credpay.transacoes.transacao-processada.dlq.v1')
$policies = @('processamento', 'transacoes' | ForEach-Object {
    (Get-Content -Raw (Join-Path $root "infra/rabbitmq/$_-policies.json") | ConvertFrom-Json).policies
})

function Require([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Kube {
    $requestTimeout = if ($args[0] -ceq 'rollout') { '190s' } else { '30s' }
    $output = & $kubectl --kubeconfig $kubeconfig --context "kind-$Cluster" --namespace credpay "--request-timeout=$requestTimeout" @args 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "kubectl fluxo falhou: operação=$($args[0]), exit=$LASTEXITCODE; conteúdo omitido." }
    return $output.Trim()
}
function Rabbit {
    return (Json (Kube exec rabbit-0 '--' rabbitmqctl --timeout 10 --quiet --formatter=json @args))
}
function Named($Rows, [string]$Name) {
    $found = @($Rows | Where-Object name -CEQ $Name)
    Require ($found.Count -eq 1) 'Recurso Rabbit ausente ou duplicado.'
    return $found[0]
}
function SameDefinition($Actual, $Expected) {
    Require ($null -ne $Actual) 'Definição de política ausente.'
    Require ((@($Actual.PSObject.Properties.Name | Sort-Object) -join ',') -ceq
        (@($Expected.PSObject.Properties.Name | Sort-Object) -join ',')) 'Chaves da política divergentes.'
    foreach ($key in $Expected.PSObject.Properties.Name) {
        Require ($Actual.$key -ceq $Expected.$key) 'Valor da política divergente.'
    }
}
function NoConsumers {
    Require (@(Rabbit list_consumers -p /).Count -eq 0) 'Preparação exige consumidores ausentes.'
}
function CheckPolicies {
    Require (@(Rabbit list_operator_policies -p /).Count -eq 0) 'Operator policy exige revisão.'
    foreach ($actual in @(Rabbit list_policies -p /)) {
        $expected = Named $policies $actual.name
        Require ($actual.pattern -ceq $expected.pattern -and $actual.'apply-to' -ceq $expected.'apply-to' -and
            $actual.priority -eq $expected.priority -and $actual.vhost -ceq '/') 'Política concorrente exige revisão.'
        $definition = if ($actual.definition -is [string]) { Json $actual.definition } else { $actual.definition }
        SameDefinition $definition $expected.definition
    }
}
function CheckPreparation {
    Require ((Named @(Rabbit list_feature_flags name state) 'stream_queue').state -ceq 'enabled') 'Flag stream_queue ausente.'
    CheckPolicies
    Require (@(Rabbit list_policies -p /).Count -eq 4) 'Esperadas quatro políticas.'
    $queues = @(Rabbit list_queues -p / name type durable arguments policy operator_policy effective_policy_definition)
    Require ($queues.Count -eq 4) 'Esperadas quatro filas próprias.'
    for ($i = 0; $i -lt 4; $i++) {
        $queue = Named $queues $queueNames[$i]
        Require ($queue.type -ceq 'quorum' -and $queue.durable -eq $true) 'Fila deve ser quorum durável.'
        Require ((ConvertTo-Json -InputObject $queue.arguments -Depth 5 -Compress) -ceq
            '[["x-queue-type","longstr","quorum"]]') 'Argumentos da fila divergentes.'
        Require ($queue.policy -ceq $policies[$i].name -and $queue.operator_policy -ceq '') 'Política efetiva divergente.'
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
        Require (@($bindings | Where-Object { $_.source_name -ceq $sources[$i] -and $_.destination_name -ceq $queueNames[$i] -and
            $_.destination_kind -ceq 'queue' -and $_.routing_key -ceq $routes[$i] }).Count -eq 1) 'Binding divergente.'
    }
}
function StartApps([bool]$Flow) {
    $enabled = $Flow.ToString().ToLowerInvariant()
    foreach ($service in @('transacoes', 'processamento')) {
        $arguments = @("--credpay.$service.consumer.topology.enabled=true", "--credpay.$service.consumer.listener.enabled=$enabled",
            "--credpay.outbox.publisher.enabled=$enabled")
        if ($service -ceq 'processamento') { $arguments = @('--credpay.processamento.limites.BRL=$(BRL_LIMIT)') + $arguments }
        $patch = ConvertTo-Json -InputObject @(@{op='replace';path='/spec/template/spec/containers/0/args';value=$arguments}) -Depth 5 -Compress
        $null = Kube patch "deployment/$service" --type=json -p $patch
        $null = Kube rollout status "deployment/$service" --timeout=180s
    }
}
function PrepareFlow {
    NoConsumers
    StartApps $false
    NoConsumers
    CheckPolicies
    foreach ($service in @('processamento', 'transacoes')) {
        $null = Kube cp (Join-Path $root "infra/rabbitmq/$service-policies.json") "rabbit-0:/tmp/$service-policies.json"
        $null = Kube exec rabbit-0 '--' rabbitmqctl --timeout 10 import_definitions "/tmp/$service-policies.json"
    }
    CheckPreparation
    NoConsumers
    Write-Host 'Kubernetes fluxo: quatro filas quorum, políticas e bindings conferidos antes de consumo.'
    StartApps $true
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $consumers = @(Rabbit list_consumers -p /)
        $valid = $consumers.Count -eq 2
        foreach ($index in @(0, 2)) {
            $prefetch = if ($index -eq 0) { 10 } else { 1 }
            $valid = $valid -and @($consumers | Where-Object { $_.queue_name -ceq $queueNames[$index] -and
                $_.ack_required -eq $true -and $_.prefetch_count -eq $prefetch }).Count -eq 1
        }
        if ($valid) {
            CheckPreparation
            Write-Host 'Kubernetes fluxo: dois consumidores, ACK obrigatório e prefetch10/1.'
            return
        }
        Start-Sleep -Milliseconds 300
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Consumidores não convergiram após preparo; interromper fluxo.'
}
function Request([string]$Method, [string]$Path, [string]$Key = '', [string]$Body = '') {
    $options = @{ Uri = "$script:url$Path"; Method = $Method; TimeoutSec = 5; SkipHttpErrorCheck = $true; MaximumRedirection = 0 }
    if ($Method -ceq 'POST') {
        $options.ContentType = 'application/json'
        $options.Headers = @{ 'Idempotency-Key' = $Key }
        $options.Body = $Body
    }
    try { return (Invoke-WebRequest @options) } catch { throw 'HTTP local indisponível; conteúdo omitido.' }
}
function WaitHealth {
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    do {
        Require (-not $script:forward.HasExited) 'Port-forward próprio encerrou antes de healthUP.'
        try {
            $response = Request GET '/actuator/health'
            if ($response.StatusCode -eq 200 -and (Json $response.Content).status -ceq 'UP') { return }
        } catch { # Port-forward pode ainda não ter aberto o socket; prazo permanece limitado.
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Port-forward não alcançou healthUP no prazo.'
}
function WaitFinal([string]$Location, [string]$Expected) {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    $status = 'INDEFINIDO'
    do {
        $response = Request GET $Location
        Require ($response.StatusCode -eq 200) 'Consulta de transação não retornou HTTP200.'
        $body = Json $response.Content
        $status = [string]$body.status
        Require ($status -cin @('PENDENTE', 'APROVADA', 'REJEITADA', 'FALHOU')) 'Estado de transação inválido; conteúdo omitido.'
        if ($status -ceq $Expected) { return $body }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "GET esperado=$Expected, observado=$status."
}

Require ($env:GITHUB_ACTIONS -ceq 'true') 'Fluxo autorizado somente no GitHub CI descartável.'
Require (Test-Path -LiteralPath $kubeconfig) 'Kubeconfig próprio ausente; contexto padrão nunca será usado.'
Require ((Test-Path -LiteralPath $marker) -and [IO.File]::ReadAllText($marker) -ceq $Cluster) 'Marcador do cluster próprio ausente ou divergente.'
Require (Test-Path -LiteralPath $kubectl) 'Cliente pinado do workspace ausente.'
Require ($env:CREDPAY_IMAGE_TAG -cmatch '^[0-9a-f]{40}$') 'Checkout deve ter SHA completo.'
$context = & $kubectl --kubeconfig $kubeconfig config current-context
Require ($LASTEXITCODE -eq 0 -and $context -ceq "kind-$Cluster") 'Contexto não corresponde ao cluster descartável.'

$script:forward = $null
try {
    try { PrepareFlow } catch {
        try { $null = Kube scale deployment/transacoes deployment/processamento --replicas=0 }
        catch { Write-Warning 'Não foi possível parar apps após falha de preparo; cleanup do cluster permanece obrigatório.' }
        throw
    }
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $listener.Start(); $port = $listener.LocalEndpoint.Port } finally { $listener.Stop() }
    $script:url = "http://127.0.0.1:$port"
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $kubectl
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    if ($IsWindows) { $start.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden }
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @('--kubeconfig', $kubeconfig, '--context', "kind-$Cluster", '--namespace', 'credpay',
            'port-forward', '--address=127.0.0.1', 'service/transacoes', "${port}:8080")) {
        $start.ArgumentList.Add($argument)
    }
    $script:forward = [Diagnostics.Process]::new()
    $script:forward.StartInfo = $start
    Require ($script:forward.Start()) 'Port-forward próprio não iniciou.'
    $forwardOutput = $script:forward.StandardOutput.ReadToEndAsync()
    $forwardError = $script:forward.StandardError.ReadToEndAsync()
    WaitHealth

    $key = [Guid]::NewGuid().ToString('D')
    $created = Request POST '/transacoes' $key '{"valor":50.000,"moeda":"BRL"}'
    Require ($created.StatusCode -eq 201) 'Criação real deve retornar HTTP201.'
    $transaction = Json $created.Content
    $identity = [Guid]::Empty
    Require ([Guid]::TryParseExact([string]$transaction.id, 'D', [ref]$identity)) 'Identidade da transação inválida.'
    Require ($identity.ToString('D') -ceq [string]$transaction.id) 'Identidade deve ser UUID canônico.'
    Require ($transaction.status -ceq 'PENDENTE') 'POST real deve retornar PENDENTE.'
    $location = [string]$created.Headers.Location[0]
    Require ($location -ceq "/transacoes/$identity") 'Location não corresponde à transação.'
    $null = WaitFinal $location 'APROVADA'
    Write-Host 'Kubernetes fluxo: POST201/PENDENTE e GET APROVADA comprovados.'
} finally {
    if ($null -ne $script:forward) {
        try {
            if (-not $script:forward.HasExited) {
                $script:forward.Kill()
                Require ($script:forward.WaitForExit(10000)) 'Port-forward filho não encerrou no prazo.'
            }
        } finally { $script:forward.Dispose() }
    }
}
