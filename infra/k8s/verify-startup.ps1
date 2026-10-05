#requires -Version 7.3
[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^credpay-ci-[0-9]+-[0-9]+$')][string]$Cluster)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$kubeconfig = Join-Path $root ".local/k8s/$Cluster.yaml"
. (Join-Path $root 'infra/compose/json.ps1')

function Require([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Kube {
    $output = & kubectl --kubeconfig $kubeconfig --context "kind-$Cluster" --namespace credpay @args 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "kubectl falhou em '$($args[0])'; saída omitida, sem exportar configuração/logs." }
    return $output.Trim()
}
function WaitPod([string]$Name, [string]$OldUid = '') {
    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    do {
        $raw = Kube get pod $Name --ignore-not-found -o json
        if ($raw -ne '') {
            $pod = Json $raw
            $ready = @($pod.status.conditions | Where-Object { $_.type -ceq 'Ready' -and $_.status -ceq 'True' }).Count -eq 1
            if ($ready -and $pod.metadata.uid -cne $OldUid) { return $pod }
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Pod não convergiu Ready com identidade esperada.'
}
function Rabbit {
    return (Json (Kube exec rabbit-0 -- rabbitmqctl --timeout 10 --quiet --formatter=json @args))
}
function CheckBroker {
    Require (@(Rabbit list_consumers -p /).Count -eq 0) 'Startup não deve ativar consumidores.'
    Require (@(Rabbit list_queues -p / name).Count -eq 0) 'Startup não deve declarar filas consumidoras.'
    $exchanges = @(Rabbit list_exchanges -p / name type durable)
    foreach ($name in @('credpay.transacoes.v1', 'credpay.processamento.v1')) {
        $matchesExchange = @($exchanges | Where-Object { $_.name -ceq $name -and $_.type -ceq 'direct' -and $_.durable -eq $true })
        Require ($matchesExchange.Count -eq 1) 'Exchange produtora ausente ou divergente.'
    }
}
function MigrationSnapshot([string]$Service) {
    $database = if ($Service -ceq 'transacoes') { 'credpay_transacoes' } else { 'credpay_processamento' }
    $user = if ($Service -ceq 'transacoes') { 'credpay_tx' } else { 'credpay_proc' }
    $snapshot = Kube exec "db-$Service-0" -- psql -U $user -d $database -tA -c 'SELECT md5(string_agg(installed_rank::text || version || installed_on::text, chr(10) ORDER BY installed_rank)) FROM flyway_schema_history'
    Require ($snapshot -cmatch '^[0-9a-f]{32}$') 'Histórico de migrations ausente.'
    return $snapshot
}

Require (Test-Path $kubeconfig) 'Kubeconfig próprio não encontrado; nunca usar contexto padrão.'
Require ($env:CREDPAY_IMAGE_TAG -cmatch '^[0-9a-f]{40}$') 'Tag deve ser SHA completo do checkout.'
$context = & kubectl --kubeconfig $kubeconfig config current-context
Require ($LASTEXITCODE -eq 0 -and $context -ceq "kind-$Cluster") 'Contexto não corresponde ao cluster descartável.'

$null = Kube apply -f (Join-Path $PSScriptRoot 'namespace.yaml')
foreach ($file in @('config.yaml', 'secret.example.yaml', 'databases.yaml', 'rabbit.yaml')) {
    $null = Kube apply -f (Join-Path $PSScriptRoot $file)
}
foreach ($name in @('db-transacoes-0', 'db-processamento-0', 'rabbit-0')) { $null = WaitPod $name }
$render = & kubectl --kubeconfig $kubeconfig kustomize $PSScriptRoot 2>&1 | Out-String
Require ($LASTEXITCODE -eq 0) 'Render Kustomize falhou.'
$manifest = Join-Path $root ".local/k8s/$Cluster-rendered.yaml"
[IO.File]::WriteAllText($manifest, $render.Replace('__CREDPAY_TAG__', $env:CREDPAY_IMAGE_TAG), [Text.UTF8Encoding]::new($false))
$null = Kube apply -f $manifest
foreach ($service in @('transacoes', 'processamento')) {
    $null = Kube rollout status "deployment/$service" --timeout=180s
    $podList = Json (Kube get pods -l "app=$service" -o json)
    Require (@($podList.items).Count -eq 1) 'Esperada uma instância do aplicativo.'
    $pod = $podList.items[0]
    Require ($pod.spec.automountServiceAccountToken -eq $false -and @($pod.spec.volumes).Where({$null -ne $_}).Count -eq 0) 'Aplicativo recebeu mount/token inesperado.'
    Require ($pod.spec.securityContext.runAsNonRoot -eq $true -and $pod.spec.securityContext.runAsUser -eq 10001) 'Usuário configurado divergente.'
    Require ((Kube exec $pod.metadata.name -- id -u) -ceq '10001') 'UID efetivo divergente.'
    $container = $pod.spec.containers[0]
    Require ($container.image -ceq "credpay-${service}:$($env:CREDPAY_IMAGE_TAG)" -and $container.imagePullPolicy -ceq 'Never') 'Imagem do app não corresponde ao checkout local.'
    Require ($container.securityContext.privileged -eq $false -and $container.securityContext.allowPrivilegeEscalation -eq $false) 'Privilégios do app divergentes.'
    foreach ($group in @('liveness', 'readiness')) {
        $health = Json (Kube exec $pod.metadata.name -- curl --fail --silent "http://localhost:8080/actuator/health/$group")
        Require ($health.status -ceq 'UP') 'Grupo health não está UP.'
    }
}
CheckBroker
$pvcBefore = Json (Kube get pvc -o json)
Require (@($pvcBefore.items).Count -eq 3) 'Esperados três PVCs próprios.'
foreach ($claim in $pvcBefore.items) { Require ($claim.status.phase -ceq 'Bound') 'PVC não está Bound.' }
Write-Host 'Kubernetes: cinco serviços Ready, apps UID10001/mounts0, probes UP, três PVCs Bound e consumo desligado.'

$migrations = @{}
foreach ($service in @('transacoes', 'processamento')) { $migrations[$service] = MigrationSnapshot $service }
foreach ($name in @('db-transacoes-0', 'db-processamento-0')) {
    $oldPod = Json (Kube get pod $Name -o json)
    $null = Kube delete pod $Name --wait=true --timeout=60s
    $null = WaitPod $name $oldPod.metadata.uid
}
foreach ($service in @('transacoes', 'processamento')) {
    Require ((MigrationSnapshot $service) -ceq $migrations[$service]) 'Recriação do Pod perdeu histórico persistido de migrations.'
}
# RabbitAdmin dos apps pode redeclarar exchanges após reconexão. Conferir recuperação sem apps ativos.
$null = Kube scale deployment/transacoes deployment/processamento --replicas=0
foreach ($service in @('transacoes', 'processamento')) {
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        $pods = Json (Kube get pods -l "app=$service" -o json)
        if (@($pods.items).Count -eq 0) { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    Require (@($pods.items).Count -eq 0) 'Aplicativos não pararam antes de conferir recuperação Rabbit.'
}
CheckBroker
$oldRabbit = Json (Kube get pod rabbit-0 -o json)
$null = Kube delete pod rabbit-0 --wait=true --timeout=60s
$null = WaitPod 'rabbit-0' $oldRabbit.metadata.uid
CheckBroker
Write-Host 'Kubernetes: exchanges recuperadas após nova UID Rabbit, com apps ausentes/sem redeclaração.'
$null = Kube scale deployment/transacoes deployment/processamento --replicas=1
foreach ($service in @('transacoes', 'processamento')) { $null = Kube rollout status "deployment/$service" --timeout=180s }
$pvcAfter = Json (Kube get pvc -o json)
foreach ($claim in $pvcBefore.items) {
    $matching = @($pvcAfter.items | Where-Object { $_.metadata.name -ceq $claim.metadata.name -and
        $_.metadata.uid -ceq $claim.metadata.uid -and $_.spec.volumeName -ceq $claim.spec.volumeName })
    Require ($matching.Count -eq 1) 'Identidade do PVC/PV mudou após recriação do Pod.'
}
CheckBroker
Write-Host 'Kubernetes: três Pods da infraestrutura recriados; PVCs/PVs, migrations e exchanges produtoras preservados.'
