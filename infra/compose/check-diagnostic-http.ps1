#requires -Version 7.3
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'diagnostic-http.ps1')
$count=0; $failures=@()
foreach($newline in @("`r`n","`n")) {
    $count++
    $body='{"status":"PENDENTE","moeda":"BRL"}'
    $text='HTTP/1.1 201 Created'+$newline+'Content-Type: application/json'+$newline+'Location: /transacoes/e72ba712-955d-426a-a4f9-23764e194356'+$newline+$newline+$body+"`n201"
    $r=Read-CredPayDiagnosticHttp $text
    if($r.StatusCode -ne 201 -or $r.Content -cne $body -or $r.Headers.Location.Count -ne 1 -or $r.Headers.Location[0] -cne '/transacoes/e72ba712-955d-426a-a4f9-23764e194356') { $failures+='valid HTTP body/header preservation' }
}
foreach($bad in @('private raw text',"HTTP/1.1 200 OK`n`n{}`n201", "HTTP/1.1 200 OK`nBadHeader`n`n{} `n200",
    "HTTP/1.1 201 Created`nLocation: /one`nLocation: /two`n`n{}`n201", "HTTP/1.1 200 OK`n`n{}`n000")) {
    $count++; $refused=$false
    try { $null=Read-CredPayDiagnosticHttp $bad } catch { $refused=$_.Exception.Message -ceq 'Resposta HTTP interna inválida; conteúdo omitido.' }
    if(-not $refused) { $failures+='invalid/ambiguous HTTP refused safely' }
}
if($failures.Count) { throw "Internal HTTP: failed=$($failures.Count)/$count; cases=$($failures -join ', ')" }
Write-Host "Internal HTTP: $count pure parser controls passed; no network or payload logging."
