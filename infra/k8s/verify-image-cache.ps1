#requires -Version 7.3
[CmdletBinding()]
param([Parameter(Mandatory)][ValidatePattern('^credpay-ci-[0-9]+-[0-9]+$')][string]$Cluster)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
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
    $sourceId = (& docker image inspect --format '{{.Id}}' $image.Source 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $sourceId -cnotmatch '^sha256:[0-9a-f]{64}$') {
        throw 'Imagem fonte pinada não encontrada no host.'
    }
    $aliasId = (& docker image inspect --format '{{.Id}}' $image.Alias 2>$null | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $aliasId -cne $sourceId) {
        throw 'Alias local não corresponde à imagem fonte pinada.'
    }
    $raw = & docker exec "$Cluster-control-plane" crictl --runtime-endpoint unix:///run/containerd/containerd.sock --image-endpoint unix:///run/containerd/containerd.sock inspecti --output json $image.Alias 2>$null | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'Alias local não encontrado no runtime do node próprio.' }
    try { $cached = $raw | ConvertFrom-Json -Depth 100 }
    catch { throw 'Identidade de imagem retornada pelo runtime é ilegível.' }
    if ($cached.status.id -cnotmatch '^sha256:[0-9a-f]{64}$') { throw 'Formato de ID do runtime inesperado.' }
    if ($cached.status.id -cne $sourceId) {
        Write-Host "Kubernetes cache: fonte=$sourceId runtime=$($cached.status.id)"
        throw 'ID no runtime não corresponde à imagem fonte pinada.'
    }
}
Write-Host 'Kubernetes: duas imagens de infraestrutura conferidas por ID fonte/alias/runtime iguais.'
