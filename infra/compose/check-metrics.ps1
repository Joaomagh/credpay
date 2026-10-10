#requires -Version 7.3
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'json.ps1')
. (Join-Path $PSScriptRoot 'metrics.ps1')

foreach ($tags in @('[]', '[{"tag":"outcome","values":["confirmed"]}]',
        '[{"tag":"outcome","values":["confirmed","returned","nacked","error"]}]')) {
    $valid = Json ('{"name":"credpay.messaging.publish.attempts","measurements":[{"statistic":"COUNT","value":2}],"availableTags":' + $tags + '}')
    if ((PublishAttemptCount $valid) -ne 2) { throw 'COUNT válido não foi conservado.' }
}
foreach ($invalid in @(
        '{"name":"credpay.messaging.publish.attempts","measurements":[{"statistic":"COUNT","value":-1}],"availableTags":[]}',
        '{"name":"wrong","measurements":[{"statistic":"COUNT","value":2}],"availableTags":[]}',
        '{"name":"credpay.messaging.publish.attempts","measurements":[{"statistic":"VALUE","value":2}],"availableTags":[]}',
        '{"name":"credpay.messaging.publish.attempts","measurements":[{"statistic":"COUNT","value":"2"}],"availableTags":[]}',
        '{"name":"credpay.messaging.publish.attempts","measurements":[{"statistic":"COUNT","value":true}],"availableTags":[]}',
        '{"name":"credpay.messaging.publish.attempts","measurements":[],"availableTags":[]}',
        '{"name":"credpay.messaging.publish.attempts","availableTags":[]}',
        '{"name":"credpay.messaging.publish.attempts","measurements":{"statistic":"COUNT","value":2},"availableTags":[]}',
        '{"name":"credpay.messaging.publish.attempts","measurements":[{"statistic":"COUNT","value":2}]}',
        '{"name":"credpay.messaging.publish.attempts","measurements":[{"statistic":"COUNT","value":2}],"availableTags":{}}',
        '{"name":"credpay.messaging.publish.attempts","measurements":[{"statistic":"COUNT","value":2},{"statistic":"COUNT","value":3}],"availableTags":[]}',
        '{"name":"credpay.messaging.publish.attempts","measurements":[{"statistic":"COUNT","value":2}],"availableTags":[{"tag":"eventId","values":["anything"]}]}',
        '{"name":"credpay.messaging.publish.attempts","measurements":[{"statistic":"COUNT","value":2}],"availableTags":[{"tag":"outcome","values":["unknown"]}]}'
    )) {
    $rejected = $false
    try { $null = PublishAttemptCount (Json $invalid) } catch {
        if ($_.Exception.Message -cne 'Métrica de publicação inválida; conteúdo omitido.') { throw 'Erro da métrica não usa mensagem segura.' }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Métrica inválida foi aceita.' }
}
foreach ($value in @([double]::NaN, [double]::PositiveInfinity)) {
    $metric = Json '{"name":"credpay.messaging.publish.attempts","measurements":[{"statistic":"COUNT","value":2}],"availableTags":[]}'
    $metric.measurements[0].value = $value
    $rejected = $false
    try { $null = PublishAttemptCount $metric } catch {
        if ($_.Exception.Message -cne 'Métrica de publicação inválida; conteúdo omitido.') { throw 'Erro da métrica não usa mensagem segura.' }
        $rejected = $true
    }
    if (-not $rejected) { throw 'COUNT não finito foi aceito.' }
}
Write-Host 'Compose metrics: COUNT válido/etiquetas fixas preservados; inválidos rejeitados sem exposição.'
