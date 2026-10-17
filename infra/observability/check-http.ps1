#requires -Version 7.3
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'http.ps1')
# Compose.Trim() remove a quebra inicial de uma resposta204 sem corpo.
$response = ParseInternalHttp 'CREDPAY_HTTP_STATUS:204'
if ($response.StatusCode -ne 204 -or $response.Content -cne '') { throw 'HTTP204 deve preservar status e corpo vazio.' }
Write-Host 'PASS HTTP interno: resposta204 sem corpo.'
$body = "{`n  `"ok`":true`n}"
$response = ParseInternalHttp ($body + "`nCREDPAY_HTTP_STATUS:200")
if ($response.StatusCode -ne 200 -or $response.Content -cne $body) { throw 'HTTP200 deve preservar o corpo multilinha.' }
$response = ParseInternalHttp "erro fictício`nCREDPAY_HTTP_STATUS:401"
if ($response.StatusCode -ne 401 -or $response.Content -cne 'erro fictício') { throw 'Erro HTTP deve preservar status para o chamador rejeitar.' }
$message = $null
try { $null = ParseInternalHttp 'SYNTHETIC_BODY_MUST_NOT_BE_PRINTED' } catch { $message = $_.Exception.Message }
if ($message -cne 'HTTP interno sem status; conteúdo omitido.') { throw 'Resposta sem status deve ser recusada sem reproduzir conteúdo.' }
Write-Host '4 controles HTTP interno passaram, sem Docker/rede.'
