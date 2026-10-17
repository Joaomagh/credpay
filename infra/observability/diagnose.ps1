# Chamado pelo verify.ps1 somente após os guards de CI/projeto próprio e a demo normal.
function DiagnosePublication {
    $queueNames = @('credpay.processamento.transacao-criada.v1', 'credpay.processamento.transacao-criada.dlq.v1',
        'credpay.transacoes.transacao-processada.v1', 'credpay.transacoes.transacao-processada.dlq.v1')
    $txUrl = "http://127.0.0.1:$(@($config.services.transacoes.ports)[0].published)"
    $rabbit = $config.services.rabbit.environment
    $credential = "$($rabbit.RABBITMQ_DEFAULT_USER):$($rabbit.RABBITMQ_DEFAULT_PASS)"
    $rabbitHeader = 'Authorization: Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($credential))
    function RabbitHttp([string]$Method, [string]$Path, [string]$Body = '') {
        $options = @('exec', '-T', 'transacoes', 'curl', '--silent', '--show-error', '--max-time', '5',
            '--header', $rabbitHeader, '--request', $Method, '--write-out', "`nCREDPAY_HTTP_STATUS:%{http_code}")
        if ($Body) { $options += @('--header', 'Content-Type: application/json', '--data-binary', $Body) }
        $response = Compose @options "http://rabbit:15672$Path"
        $match = [regex]::Match($response, '(?s)^(.*)\r?\nCREDPAY_HTTP_STATUS:([0-9]{3})$')
        Require $match.Success 'Management HTTP sem status; conteúdo omitido.'
        return [pscustomobject]@{StatusCode=[int]$match.Groups[2].Value;Content=$match.Groups[1].Value}
    }
    function Sql([string]$Service, [string]$Query) {
        Require ($Service -cin @('transacoes', 'processamento')) 'Banco fora do roteiro.'
        $user = if ($Service -ceq 'transacoes') { 'credpay_tx' } else { 'credpay_proc' }
        return (Compose exec -T "db-$Service" psql -X -v ON_ERROR_STOP=1 -U $user -d "credpay_$Service" -tA -c $Query)
    }
    function TxHttp([string]$Method, [string]$Path, [string]$Key = '', [string]$Body = '') {
        $options = @{Uri="$txUrl$Path";Method=$Method;TimeoutSec=5;SkipHttpErrorCheck=$true;MaximumRedirection=0}
        if ($Method -ceq 'POST') { $options.Headers=@{'Idempotency-Key'=$Key}; $options.ContentType='application/json'; $options.Body=$Body }
        try { return (Invoke-WebRequest @options) } catch { throw 'HTTP da transação indisponível; conteúdo omitido.' }
    }
    function Text($Content) {
        if ($Content -is [string]) { return $Content }
        try { return [Text.UTF8Encoding]::new($false, $true).GetString([byte[]]$Content) }
        catch { throw 'HTTP não retornou UTF8 válido; conteúdo omitido.' }
    }
    function Processes {
        $values = @()
        foreach ($app in @('transacoes', 'processamento')) {
            $id = Compose ps -q $app
            $state = & docker inspect --format '{{.Id}}|{{.State.StartedAt}}|{{.RestartCount}}' $id 2>&1 | Out-String
            Require ($LASTEXITCODE -eq 0) 'Estado do processo indisponível.'
            $values += $state.Trim()
        }
        return ($values -join '/')
    }
    function Drained {
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        do {
            $response = RabbitHttp GET '/api/queues/%2F'
            Require ($response.StatusCode -eq 200) 'Consulta das filas deve retornar200.'
            $queues = @(Json $response.Content)
            $valid = $queues.Count -eq 4
            foreach ($name in $queueNames) {
                $matching = @($queues | Where-Object { $_.name -ceq $name -and $_.messages_ready -eq 0 -and $_.messages_unacknowledged -eq 0 })
                $valid = $valid -and $matching.Count -eq 1
            }
            if ($valid) { return }
            Start-Sleep -Milliseconds 300
        } while ([DateTime]::UtcNow -lt $deadline)
        throw 'Quatro filas não drenaram; nenhuma mensagem será removida pelo roteiro.'
    }
    function Returned {
        $at = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000.0
        $expression = 'credpay_messaging_publish_attempts_total{job="credpay-transacoes",outcome="returned"}'
        $series = @(Query $expression $at)
        if ($series.Count -eq 0) { return $null }
        Require ($series.Count -eq 1 -and $series[0].metric.instance -ceq 'transacoes:8080' -and $series[0].value.Count -eq 2) 'Série returned inválida.'
        $value = 0.0
        Require ([double]::TryParse([string]$series[0].value[1], [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$value) -and [double]::IsFinite($value) -and $value -ge 0) 'Valor returned inválido.'
        # timestamp(metric) devolve a data da amostra, não a data de avaliação da consulta.
        $timestamps = @(Query "timestamp($expression)" $at)
        Require ($timestamps.Count -eq 1 -and $timestamps[0].metric.job -ceq 'credpay-transacoes' -and
            $timestamps[0].metric.instance -ceq 'transacoes:8080' -and $timestamps[0].metric.outcome -ceq 'returned') 'Timestamp returned divergente.'
        $time = [double]$timestamps[0].value[1]
        $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() / 1000.0
        Require ([double]::IsFinite($time) -and $time -ge $now - 10 -and $time -le $now + 2) 'Amostra returned não é recente.'
        return @{Count=$value;Time=$time}
    }
    function Pending($Record) {
        $id = $Record.Id
        $response = TxHttp GET $Record.Location
        Require ($response.StatusCode -eq 200 -and (Json $response.Content).status -ceq 'PENDENTE') 'Sem rota deve preservar PENDENTE.'
        Require ((Sql transacoes "SELECT event_id::text||'|'||md5(row_to_json(o)::text) FROM outbox_eventos o WHERE aggregate_id='$id'::uuid AND published_at IS NULL;") -ceq $Record.CreationFingerprint) 'Intenção original deve permanecer íntegra e pendente.'
        Require ((Sql transacoes "SELECT count(*) FROM historico_transacoes WHERE transaction_id='$id'::uuid;") -ceq '0') 'Retorno sem rota não deve fabricar histórico.'
        Require ((Sql processamento "SELECT (SELECT count(*) FROM processamentos WHERE transaction_id='$id'::uuid)+(SELECT count(*) FROM outbox_eventos WHERE aggregate_id='$id'::uuid);") -ceq '0') 'Retorno sem rota não deve fabricar decisão.'
    }
    function Snapshot([string]$Id) {
        $tx = Sql transacoes @"
SELECT md5(jsonb_build_object(
'tx',(SELECT jsonb_agg(t ORDER BY id) FROM transacoes t WHERE id='$Id'::uuid),
'key',(SELECT jsonb_agg(i ORDER BY chave) FROM idempotencias_transacao i WHERE transacao_id='$Id'::uuid),
'outbox',(SELECT jsonb_agg(o ORDER BY event_id) FROM outbox_eventos o WHERE aggregate_id='$Id'::uuid),
'history',(SELECT jsonb_agg(h ORDER BY event_id) FROM historico_transacoes h WHERE transaction_id='$Id'::uuid))::text);
"@
        $proc = Sql processamento @"
SELECT md5(jsonb_build_object(
'processing',(SELECT jsonb_agg(p ORDER BY event_id) FROM processamentos p WHERE transaction_id='$Id'::uuid),
'outbox',(SELECT jsonb_agg(o ORDER BY event_id) FROM outbox_eventos o WHERE aggregate_id='$Id'::uuid))::text);
"@
        return "$tx/$proc"
    }
    Drained
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $pending = (Sql transacoes 'SELECT count(*) FROM outbox_eventos WHERE published_at IS NULL;') -ceq '0' -and
            (Sql processamento 'SELECT count(*) FROM outbox_eventos WHERE published_at IS NULL;') -ceq '0'
        if ($pending) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    Require $pending 'Outboxes anteriores devem estar publicadas antes da falha.'
    $processes = Processes
    $baseline = Returned
    $source = 'credpay.transacoes.v1'
    $destination = $queueNames[0]
    $routingKey = 'transacao.criada.v1'
    $path = '/api/bindings/%2F/e/' + [Uri]::EscapeDataString($source) + '/q/' + [Uri]::EscapeDataString($destination)
    $response = RabbitHttp GET $path
    Require ($response.StatusCode -eq 200) 'Binding original deve estar acessível.'
    $bindings = @(Json $response.Content)
    Require ($bindings.Count -eq 1) 'Esperado um único binding original.'
    $binding = $bindings[0]
    Require ($binding.source -ceq $source -and $binding.destination -ceq $destination -and
        $binding.destination_type -ceq 'queue' -and $binding.routing_key -ceq $routingKey -and $binding.properties_key) 'Binding original divergente.'
    $restore = @{routing_key=$binding.routing_key;arguments=$binding.arguments} | ConvertTo-Json -Depth 10 -Compress
    try {
        $removed = RabbitHttp DELETE ($path + '/' + [Uri]::EscapeDataString($binding.properties_key))
        Require ($removed.StatusCode -eq 204) 'Retirada do único binding deve retornar204.'
        $response = RabbitHttp GET $path
        Require ($response.StatusCode -eq 200 -and @(Json $response.Content).Count -eq 0) 'Binding deve estar ausente antes do POST.'
        $key = [Guid]::NewGuid().ToString('D')
        $body = '{"valor":75.000,"moeda":"BRL"}'
        $created = TxHttp POST '/transacoes' $key $body
        Require ($created.StatusCode -eq 201) 'POST real deve retornar201.'
        $transaction = Json $created.Content
        $identity = [Guid]::Empty
        Require ([Guid]::TryParseExact([string]$transaction.id, 'D', [ref]$identity) -and
            $identity.ToString('D') -ceq [string]$transaction.id -and $transaction.status -ceq 'PENDENTE') 'POST deve retornar UUID canônico/PENDENTE.'
        $id = $identity.ToString('D')
        $location = [string]$created.Headers.Location[0]
        Require ($location -ceq "/transacoes/$id") 'Location divergente.'
        $fingerprint = Sql transacoes "SELECT event_id::text||'|'||md5(row_to_json(o)::text) FROM outbox_eventos o WHERE aggregate_id='$id'::uuid AND published_at IS NULL;"
        Require ($fingerprint -cmatch '^[a-f0-9-]{36}\|[a-f0-9]{32}$') 'Criação original ausente ou duplicada.'
        $record = @{Id=$id;Key=$key;Body=$body;Original=(Text $created.Content);Location=$location;
            CreationFingerprint=$fingerprint;Creation=($fingerprint -split '\|')[0]}
        $first = $baseline
        $increased = $false
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        do {
            Pending $record
            $current = Returned
            if ($null -ne $current) {
                if ($null -eq $first) { $first = $current }
                elseif ($current.Time -gt $first.Time -and $current.Count -gt $first.Count) { $increased = $true; break }
            }
            Start-Sleep -Milliseconds 300
        } while ([DateTime]::UtcNow -lt $deadline)
        Require $increased 'Painel deve observar aumento returned em amostra posterior; ausência não vale zero.'
        Pending $record
        Drained
        Require ((Processes) -ceq $processes) 'Processos não podem reiniciar durante a comparação de contadores.'
        Write-Host 'Observação: returned aumentou em amostras reais; PENDENTE/intenção íntegra, sem decisão/histórico.'
    } finally {
        $restored = RabbitHttp POST $path $restore
        Require ($restored.StatusCode -cin @(201, 204)) 'Restauração do binding falhou.'
        $response = RabbitHttp GET $path
        Require ($response.StatusCode -eq 200) 'Conferência do binding restaurado falhou.'
        $bindings = @(Json $response.Content)
        Require ($bindings.Count -eq 1 -and $bindings[0].properties_key -ceq $binding.properties_key -and
            $bindings[0].routing_key -ceq $binding.routing_key -and
            (ConvertTo-Json $bindings[0].arguments -Depth 10 -Compress) -ceq (ConvertTo-Json $binding.arguments -Depth 10 -Compress)) 'Binding restaurado deve ser exatamente o original.'
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $final = TxHttp GET $record.Location
        Require ($final.StatusCode -eq 200) 'GET da recuperação deve retornar200.'
        $transaction = Json $final.Content
        if ($transaction.status -ceq 'APROVADA') { break }
        Require ($transaction.status -ceq 'PENDENTE') 'Recuperação retornou estado inesperado.'
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    Require ($transaction.status -ceq 'APROVADA' -and $transaction.id -ceq $record.Id -and
        $transaction.moeda -ceq 'BRL' -and [decimal]$transaction.valor -eq 75) 'Mesma transação deve recuperar para APROVADA.'
    $id = $record.Id
    $creation = $record.Creation
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $txCausal = Sql transacoes @"
SELECT count(*) FROM transacoes t JOIN idempotencias_transacao i ON i.transacao_id=t.id
JOIN outbox_eventos o ON o.aggregate_id=t.id
JOIN historico_transacoes h ON h.transaction_id=t.id AND h.causation_id=o.event_id
WHERE t.id='$id'::uuid AND i.chave='$key'::uuid AND t.status='APROVADA' AND t.valor=75 AND t.moeda='BRL'
AND o.event_id='$creation'::uuid AND o.published_at IS NOT NULL AND o.event_type='TransacaoCriada' AND o.event_version=1
AND o.payload->>'correlationId'=t.id::text AND h.estado_anterior='PENDENTE' AND h.estado_final=t.status;
"@
        $procCausal = Sql processamento @"
SELECT count(*) FROM processamentos p JOIN outbox_eventos o ON o.event_id=p.output_event_id
WHERE p.transaction_id='$id'::uuid AND p.event_id='$creation'::uuid AND p.valor=75 AND p.moeda='BRL'
AND p.limite_aplicado=100 AND p.input_status='PENDENTE' AND p.resultado='APROVADA'
AND o.aggregate_id=p.transaction_id AND o.published_at IS NOT NULL
AND o.payload->>'causationId'=p.event_id::text AND o.payload->>'correlationId'=p.transaction_id::text;
"@
        if ($txCausal -ceq '1' -and $procCausal -ceq '1') { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    Require ($txCausal -ceq '1' -and $procCausal -ceq '1') 'Recuperação deve preservar causalidade e publicações originais.'
    $output = Sql processamento "SELECT output_event_id FROM processamentos WHERE transaction_id='$id'::uuid;"
    Require ((Sql transacoes "SELECT event_id FROM historico_transacoes WHERE transaction_id='$id'::uuid;") -ceq $output) 'Histórico deve referenciar o resultado original do processador.'
    Drained
    $before = Snapshot $id
    $replay = TxHttp POST '/transacoes' $record.Key $record.Body
    Require ($replay.StatusCode -eq 201 -and (Text $replay.Content) -ceq $record.Original -and
        [string]$replay.Headers.Location[0] -ceq $record.Location) 'Replay deve conservar POST/Location originais.'
    Drained
    Require ((Snapshot $id) -ceq $before -and (Processes) -ceq $processes) 'Replay/processos devem conservar estado após recuperação.'
    CheckCollection
    Write-Host 'Observação: rota original restaurada, mesma transação APROVADA com causa preservada, replay estável e quatro filas drenadas.'
}
