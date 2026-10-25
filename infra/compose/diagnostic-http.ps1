# Pure parsing for curl --include --write-out; never returns raw errors.
function DiagnosticComposeStdout([string[]]$Arguments) {
    Require ($null -ne $diagnosticScope -and $Arguments[0] -ceq 'exec') 'HTTP exige o projeto diagnóstico próprio.'
    $runtime=$diagnosticScope.Runtime
    $null=Assert-CredPayDiagnosticScope $runtime $diagnosticScope.Owner
    # CLI diagnostics on stderr are not an HTTP header; keep the streams separate.
    if($runtime.Compose) { return (Invoke-CredPayDiagnosticTool $runtime $runtime.Compose (@($composeArgs | Select-Object -Skip 1)+$Arguments)) }
    return (Invoke-CredPayDiagnosticTool $runtime $runtime.Docker ($composeArgs+$Arguments))
}
function Read-CredPayDiagnosticHttp([string]$Text) {
    $message='Resposta HTTP interna inválida; conteúdo omitido.'
    $stage='size'; $script:diagnosticHttpStatus=0
    try {
        if($Text.Length -gt 131072) { throw 'Limit' }
        $stage='last-status'; $last=$Text.LastIndexOf("`n")
        if($last -lt 0) { throw 'Missing status' }
        $status=$Text.Substring($last+1).Trim()
        if($status -cnotmatch '^[1-5][0-9]{2}$') { throw 'Bad status' }
        $script:diagnosticHttpStatus=[int]$status
        $response=$Text.Substring(0,$last)
        $stage='header-separator'; $separator=[regex]::Match($response,"\r?\n\r?\n")
        if(-not $separator.Success) { throw 'Missing headers' }
        $lines=@($response.Substring(0,$separator.Index) -split '\r?\n')
        $stage='status-line'
        if($lines[0] -cnotmatch ('^HTTP/(1\.[01]|2) '+$status+'(?: |$)')) { throw 'Status mismatch' }
        $headers=@{}
        foreach($line in @($lines | Select-Object -Skip 1)) {
            $stage='header-field'
            if($line -cnotmatch '^([A-Za-z0-9-]+):[ \t]*(.*)$') { throw 'Bad header' }
            $name=$Matches[1]; $value=$Matches[2]
            if($name -ieq 'Location' -and $headers.ContainsKey($name)) { throw 'Ambiguous location' }
            $headers[$name]=@($value)
        }
        $script:diagnosticHttpFailure='none'
        return @{StatusCode=[int]$status;Headers=$headers;Content=$response.Substring($separator.Index+$separator.Length)}
    } catch { $script:diagnosticHttpFailure=$stage; throw $message }
}
