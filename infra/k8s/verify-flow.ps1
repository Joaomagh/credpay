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

function Require([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Kube {
    $output = & $kubectl --kubeconfig $kubeconfig --context "kind-$Cluster" --namespace credpay --request-timeout=30s @args 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "kubectl fluxo falhou: operação=$($args[0]), exit=$LASTEXITCODE; conteúdo omitido." }
    return $output.Trim()
}
function PrepareFlow {
    # Primeiro red: apps permanecem com topologia/listener/publicação desligados.
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
    PrepareFlow
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
