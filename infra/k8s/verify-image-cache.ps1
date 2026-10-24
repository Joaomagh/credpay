#requires -Version 7.3
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Cluster,
    [ValidateSet('CI','Local')][string]$ExecutionProfile = 'CI')

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot 'local-runtime.ps1')
$execution = Get-CredPayKubernetesExecution $root $Cluster $ExecutionProfile
if ($ExecutionProfile -ceq 'Local') {
    . (Join-Path $PSScriptRoot 'local-actions.ps1')
    Assert-CredPayLocalImageCache $execution.Runtime $execution.Owner
    Write-Host 'Kubernetes: quatro imagens conferidas por origem/plataforma/config hash e IDs CRI correspondentes.'
    return
}
$docker = $execution.Docker
function ReadPinnedDocker {
    if ($ExecutionProfile -ceq 'Local') {
        return Invoke-CredPayLocalTool $execution.Runtime $docker $args
    }
    $output = & $docker @args 2>$null | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'Leitura da imagem/runtime falhou; conteúdo omitido.' }
    return $output.Trim()
}
$images = @(
    @{
        Source = 'postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0'
        Alias = 'credpay-postgres:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0'
    },
    @{
        Source = 'rabbitmq:4.3.5-management-alpine@sha256:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1'
        Alias = 'credpay-rabbit:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1'
    }
)
foreach ($image in $images) {
    $sourceId = ReadPinnedDocker image inspect --format '{{.Id}}' $image.Source
    if ($sourceId -cnotmatch '^sha256:[0-9a-f]{64}$') {
        throw 'Imagem fonte pinada não encontrada no host.'
    }
    $aliasId = ReadPinnedDocker image inspect --format '{{.Id}}' $image.Alias
    if ($aliasId -cne $sourceId) {
        throw 'Alias local não corresponde à imagem fonte pinada.'
    }
    $raw = ReadPinnedDocker exec "$Cluster-control-plane" crictl --runtime-endpoint unix:///run/containerd/containerd.sock --image-endpoint unix:///run/containerd/containerd.sock inspecti --output json $image.Alias
    try { $cached = $raw | ConvertFrom-Json -Depth 100 }
    catch { throw 'Identidade de imagem retornada pelo runtime é ilegível.' }
    if ($cached.status.id -cnotmatch '^sha256:[0-9a-f]{64}$') { throw 'Formato de ID do runtime inesperado.' }
    if ($cached.status.id -cne $sourceId) {
        Write-Host "Kubernetes cache: fonte=$sourceId runtime=$($cached.status.id)"
        throw 'ID no runtime não corresponde à imagem fonte pinada.'
    }
}
Write-Host 'Kubernetes: duas imagens de infraestrutura conferidas por ID fonte/alias/runtime iguais.'
