#requires -Version 7.3
# Startup provides the owned context, Require and sanitized state diagnostics.
function Kube {
    $stdin = @($input)
    $secretCreate = ($args.Count -in @(3,5) -and $args[0] -ceq 'create' -and $args[1] -ceq '-f' -and
        $args[2] -ceq '-' -and ($args.Count -eq 3 -or ($args[3] -ceq '-o' -and $args[4] -ceq 'json')))
    Require (-not $secretCreate -or $stdin.Count -eq 1) 'Criação de Secret exige uma entrada própria.'
    if ($stdin.Count -gt 0) {
        Require ($stdin.Count -eq 1 -and $stdin[0] -is [string] -and $secretCreate) 'Entrada kubectl recusada; somente Secret próprio por create.'
        $secret = Read-CredPaySecretJson $stdin[0]
        Assert-CredPayKubernetesSecret $secret $Cluster
    }
    $requestTimeout = switch ($args[0]) { 'rollout' { '190s' } 'delete' { '70s' } default { '30s' } }
    $output = if ($stdin.Count -eq 1) {
        $stdin[0] | & $kubectl --kubeconfig $kubeconfig --context "kind-$Cluster" --namespace credpay "--request-timeout=$requestTimeout" @args 2>&1 | Out-String
    } else {
        & $kubectl --kubeconfig $kubeconfig --context "kind-$Cluster" --namespace credpay "--request-timeout=$requestTimeout" @args 2>&1 | Out-String
    }
    if ($LASTEXITCODE -ne 0) {
        $exitCode = $LASTEXITCODE
        # Never arguments, messages, env or process output.
        $operation = if ($args[0] -ceq 'exec' -and $args.Count -ge 4) {
            @($args | Where-Object { $_ -cin @('id', 'curl', 'rabbitmqctl', 'list_consumers', 'list_queues', 'list_exchanges', 'psql') }) -join '/'
        } else { [string]$args[0] }
        WriteStateDiagnostics
        throw "kubectl falhou em '$operation', exit=$exitCode; saída omitida, sem exportar configuração/logs."
    }
    return $output.Trim()
}
