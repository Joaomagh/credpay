# Only the disposable Smoke runner supplies Compose/Rabbit/Request boundaries.
function FaultSql([string]$App,[string]$Query) {
    Require ($App -cin @('transacoes','processamento')) 'Banco fora do diagnóstico.'
    $user=if($App -ceq 'transacoes') { 'credpay_tx' } else { 'credpay_proc' }
    return (Compose exec -T "db-$App" psql -X -v ON_ERROR_STOP=1 -U $user -d "credpay_$App" -tA -c $Query)
}
function FaultText($Content) {
    if($Content -is [string]) { return $Content }
    try { return [Text.UTF8Encoding]::new($false,$true).GetString([byte[]]$Content) }
    catch { throw 'Resposta inválida; conteúdo omitido.' }
}
function FaultProcessIdentity {
    $result=@()
    foreach($app in $apps) {
        $id=Compose ps -q $app
        Require ($id -cmatch '^[0-9a-f]{64}$') 'Identidade do aplicativo inválida.'
        $state=& docker inspect --format '{{.Id}}/{{.State.StartedAt}}/{{.RestartCount}}' $id 2>$null
        Require ($LASTEXITCODE -eq 0 -and $state -cmatch '^[0-9a-f]{64}/[0-9TZ:.+-]+/[0-9]+$') 'Estado do aplicativo indisponível.'
        $result+=$state
    }
    return ($result -join '|')
}
function AssertFaultProcessesStable {
    Require ((FaultProcessIdentity) -ceq $script:faultProcessIdentity) 'Processo reiniciou durante a medição.'
}
function WaitFaultDrained {
    $deadline=[DateTime]::UtcNow.AddSeconds(30)
    do {
        $queues=@(Rabbit list_queues -p / name messages_ready messages_unacknowledged)
        $valid=$queues.Count -eq 4
        foreach($name in $queueNames) {
            $queue=Named $queues $name
            $valid=$valid -and $queue.messages_ready -eq 0 -and $queue.messages_unacknowledged -eq 0
        }
        foreach($app in $apps) { $valid=$valid -and (FaultSql $app 'SELECT count(*) FROM outbox_eventos WHERE published_at IS NULL;') -ceq '0' }
        if($valid) { return }
        Start-Sleep -Milliseconds 300
    } while([DateTime]::UtcNow -lt $deadline)
    throw 'Filas/outboxes não drenaram; nenhuma mensagem será removida.'
}
function CheckMetricFaultReady {
    CheckApps; CheckMetricsEndpoints; CheckPreparation; WaitFaultDrained
    $script:faultProcessIdentity=FaultProcessIdentity
}
function ReadFaultCount([string]$Publisher) {
    $response=Request GET "$($urls[$Publisher])/actuator/metrics/credpay.messaging.publish.attempts?tag=outcome:returned"
    if($response.StatusCode -eq 404) { return 0 }
    Require ($response.StatusCode -eq 200) 'Consulta returned não respondeu200/404.'
    return (PublishAttemptCount (Json $response.Content))
}
function FaultRoute([string]$Publisher) {
    if($Publisher -ceq 'transacoes') { return @{Exchange='credpay.transacoes.v1';Key='transacao.criada.v1';Queue=$queueNames[0]} }
    Require ($Publisher -ceq 'processamento') 'Publicador fora do diagnóstico.'
    return @{Exchange='credpay.processamento.v1';Key='transacao.processada.v1';Queue=$queueNames[2]}
}
function ChangeFaultBinding([string]$Publisher,[bool]$Present) {
    $route=FaultRoute $Publisher
    $operation=if($Present) { 'add' } else { 'remove' }
    # Fixed allowlisted values, no user data or credentials in Erlang code.
    $code='ok = rabbit_binding:'+$operation+'(rabbit_binding:new({resource,<<"/">>,exchange,<<"'+$route.Exchange+'">>},<<"'+$route.Key+'">>,{resource,<<"/">>,queue,<<"'+$route.Queue+'">>},[]),<<"credpay_demo">>), ok.'
    Require ((Compose exec -T rabbit rabbitmqctl --timeout 10 eval $code) -ceq 'ok') 'Alteração do binding não confirmou ok.'
}
function AssertFaultBinding([string]$Publisher,[bool]$Present) {
    $route=FaultRoute $Publisher
    $bindings=@(Rabbit list_bindings -p / source_name destination_name destination_kind routing_key)
    $matching=@($bindings | Where-Object { $_.source_name -ceq $route.Exchange -and $_.destination_name -ceq $route.Queue -and
        $_.destination_kind -ceq 'queue' -and $_.routing_key -ceq $route.Key })
    $expected=if($Present) { 1 } else { 0 }
    Require ($matching.Count -eq $expected) 'Binding exato divergente.'
    if(-not $Present) { Require (@($bindings | Where-Object source_name -CEQ $route.Exchange).Count -eq 0) 'Outra rota impede diagnóstico sem rota.' }
}
function NewFaultTransaction {
    $key=[Guid]::NewGuid().ToString('D')
    $post=Request POST "$($urls.transacoes)/transacoes" $key '50.000'
    $body=Json $post.Content; $id=[Guid]::Empty
    Require ($post.StatusCode -eq 201 -and $body.status -ceq 'PENDENTE' -and
        [Guid]::TryParseExact([string]$body.id,'D',[ref]$id) -and $id.ToString('D') -ceq [string]$body.id) 'POST não retornou201/PENDENTE/UUID canônico.'
    $location=[string]$post.Headers.Location[0]
    Require ($location -ceq "/transacoes/$id") 'Location divergente.'
    return @{Id=$id.ToString('D');Key=$key;Location=$location;Original=(FaultText $post.Content)}
}
function FaultOutboxHash($Record,[string]$App) {
    # Equality fingerprint of the original row, not a security/inventory hash.
    return (FaultSql $App "SELECT md5((to_jsonb(o)-'published_at')::text) FROM outbox_eventos o WHERE aggregate_id='$($Record.Id)'::uuid;")
}
function FaultTransactionHash($Record) {
    return (FaultSql transacoes "SELECT md5((to_jsonb(t)-'status')::text) FROM transacoes t WHERE id='$($Record.Id)'::uuid;")
}
function ReadFaultPending($Record,[string]$Publisher) {
    $id=$Record.Id
    Require ((FaultText (WaitFinal $Record.Location 'PENDENTE')) -ceq $Record.Original) 'GET pendente alterou pedido original.'
    Require ((FaultSql $Publisher "SELECT count(*) FROM outbox_eventos WHERE aggregate_id='$id'::uuid AND published_at IS NULL;") -ceq '1') 'Outbox bloqueada não conserva intenção pendente única.'
    Require ((FaultSql transacoes "SELECT count(*) FROM historico_transacoes WHERE transaction_id='$id'::uuid;") -ceq '0') 'Retorno criou histórico final.'
    $event=FaultSql transacoes "SELECT event_id FROM outbox_eventos WHERE aggregate_id='$id'::uuid;"
    $parsed=[Guid]::Empty
    Require ([Guid]::TryParseExact($event,'D',[ref]$parsed)) 'Evento de criação ausente/duplicado.'
    $decision=''
    if($Publisher -ceq 'transacoes') {
        Require ((FaultSql processamento "SELECT (SELECT count(*) FROM processamentos WHERE transaction_id='$id'::uuid)+(SELECT count(*) FROM outbox_eventos WHERE aggregate_id='$id'::uuid);") -ceq '0') 'Entrada sem rota criou decisão/saída.'
    } else {
        Require ((FaultSql processamento "SELECT count(*) FROM processamentos WHERE transaction_id='$id'::uuid AND event_id='$event'::uuid AND resultado='APROVADA';") -ceq '1') 'Decisão original divergente.'
        $decision=FaultSql processamento "SELECT md5(to_jsonb(p)::text) FROM processamentos p WHERE transaction_id='$id'::uuid;"
    }
    $hash=FaultOutboxHash $Record $Publisher; $tx=FaultTransactionHash $Record
    Require ($hash -cmatch '^[0-9a-f]{32}$' -and $tx -cmatch '^[0-9a-f]{32}$') 'Snapshot original ausente/duplicado.'
    return @{Hash=$hash;Tx=$tx;Event=$event;Decision=$decision}
}
function ConfirmFaultRecovery($Record,[string]$Publisher,$Snapshot) {
    $final=Json (WaitFinal $Record.Location 'APROVADA'); $id=$Record.Id
    Require ($final.id -ceq $id -and $final.moeda -ceq 'BRL' -and [decimal]$final.valor -eq 50) 'Recuperação alterou dados.'
    WaitFaultDrained
    Require ((FaultOutboxHash $Record $Publisher) -ceq $Snapshot.Hash -and (FaultTransactionHash $Record) -ceq $Snapshot.Tx) 'Recuperação alterou identidade/payload original.'
    if($Snapshot.Decision) { Require ((FaultSql processamento "SELECT md5(to_jsonb(p)::text) FROM processamentos p WHERE transaction_id='$id'::uuid;") -ceq $Snapshot.Decision) 'Decisão original alterada.' }
    $output=FaultSql processamento "SELECT output_event_id FROM processamentos WHERE transaction_id='$id'::uuid;"
    $parsed=[Guid]::Empty; Require ([Guid]::TryParseExact($output,'D',[ref]$parsed)) 'Evento de saída ausente/duplicado.'
    $creation=$Snapshot.Event
    Require ((FaultSql transacoes "SELECT count(*) FROM transacoes t JOIN outbox_eventos o ON o.aggregate_id=t.id JOIN historico_transacoes h ON h.transaction_id=t.id WHERE t.id='$id'::uuid AND o.event_id='$creation'::uuid AND o.published_at IS NOT NULL AND h.event_id='$output'::uuid AND h.causation_id=o.event_id AND h.correlation_id=t.id AND h.estado_anterior='PENDENTE' AND h.estado_final='APROVADA';") -ceq '1') 'Causalidade de criação/histórico divergente.'
    Require ((FaultSql processamento "SELECT count(*) FROM processamentos p JOIN outbox_eventos o ON o.event_id=p.output_event_id WHERE p.transaction_id='$id'::uuid AND p.event_id='$creation'::uuid AND p.output_event_id='$output'::uuid AND o.aggregate_id=p.transaction_id AND o.published_at IS NOT NULL AND o.payload->>'causationId'=p.event_id::text AND o.payload->>'correlationId'=p.transaction_id::text;") -ceq '1') 'Causalidade da decisão/outbox divergente.'
    foreach($table in @(@('transacoes','transacoes','id'),@('transacoes','outbox_eventos','aggregate_id'),@('transacoes','historico_transacoes','transaction_id'),@('processamento','processamentos','transaction_id'),@('processamento','outbox_eventos','aggregate_id'))) {
        Require ((FaultSql $table[0] "SELECT count(*) FROM $($table[1]) WHERE $($table[2])='$id'::uuid;") -ceq '1') 'Recuperação duplicou registros.'
    }
    $replay=Request POST "$($urls.transacoes)/transacoes" $Record.Key '50.000'
    Require ($replay.StatusCode -eq 201 -and (FaultText $replay.Content) -ceq $Record.Original -and [string]$replay.Headers.Location[0] -ceq $Record.Location) 'Replay original divergente.'
}
