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
# Exercise the actual request adapter without executing the runner's top-level actions.
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'demo.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count) { throw 'Runner did not parse.' }
foreach($name in @('Require','Request')) {
    $function=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$true))
    if($function.Count -ne 1) { throw 'Request adapter boundary missing/ambiguous.' }
    . ([ScriptBlock]::Create($function[0].Extent.Text))
}
function Invoke-WebRequest { throw 'Native HTTP fallback forbidden in fixture.' }
function docker { throw 'Native Docker fallback forbidden in fixture.' }
$diagnosticScope=@{}; $apps=@('transacoes','processamento'); $script:httpCalls=0
function Compose {
    return "fictitious CLI warning on stderr`nHTTP/1.1 200 OK`r`nContent-Type: application/json`r`n`r`n{`"status`":`"UP`"}`n200"
}
function DiagnosticComposeStdout([string[]]$Arguments) {
    $script:httpCalls++; $script:httpArguments=$Arguments
    return "HTTP/1.1 200 OK`r`nContent-Type: application/json`r`n`r`n{`"status`":`"UP`"}`n200"
}
$count++
try {
    $response=Request GET 'http://transacoes:8080/actuator/health'
    if($response.StatusCode -ne 200 -or $response.Content -cne '{"status":"UP"}' -or
        $script:httpArguments[2] -cne 'transacoes' -or $script:httpArguments -notcontains 'http://127.0.0.1:8080/actuator/health' -or
        $script:httpArguments -notcontains '--noproxy' -or $script:httpArguments -contains '--location') { throw 'Internal destination/response changed.' }
} catch { $failures+='actual request adapter' }
$count++; $before=$script:httpCalls; $refused=$false
try { $null=Request GET 'http://example.invalid/actuator/health' } catch { $refused=$true }
if(-not $refused -or $script:httpCalls -ne $before) { $failures+='external destination refused before transport' }
if($failures.Count) { throw "Internal HTTP: failed=$($failures.Count)/$count; cases=$($failures -join ', ')" }
Write-Host "Internal HTTP: $count pure parser controls passed; no network or payload logging."
