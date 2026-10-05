#requires -Version 7.3
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'json.ps1')

$body = '{"status":"UP","texto":"ação"}'
foreach ($content in @($body, [Text.Encoding]::UTF8.GetBytes($body))) {
    $parsed = Json $content
    if ($parsed.status -cne 'UP' -or $parsed.texto -cne 'ação') { throw 'JSON não conservou campos/UTF-8.' }
}
foreach ($invalid in @('{invalid', [byte[]]@(255))) {
    $rejected = $false
    try { $null = Json $invalid } catch {
        if ($_.Exception.Message -cne 'JSON inválido; conteúdo omitido.') { throw 'Erro JSON não usa mensagem segura.' }
        $rejected = $true
    }
    if (-not $rejected) { throw 'Conteúdo inválido foi aceito.' }
}
Write-Host 'Compose JSON: texto/bytes UTF-8 preservados; conteúdo inválido rejeitado sem exposição.'
