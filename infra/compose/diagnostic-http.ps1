# Pure parsing for curl --include --write-out; never returns raw errors.
function Read-CredPayDiagnosticHttp([string]$Text) {
    $message='Resposta HTTP interna inválida; conteúdo omitido.'
    try {
        if($Text.Length -gt 131072) { throw 'Limit' }
        $last=$Text.LastIndexOf("`n")
        if($last -lt 0) { throw 'Missing status' }
        $status=$Text.Substring($last+1).Trim()
        if($status -cnotmatch '^[1-5][0-9]{2}$') { throw 'Bad status' }
        $response=$Text.Substring(0,$last)
        $separator=[regex]::Match($response,"\r?\n\r?\n")
        if(-not $separator.Success) { throw 'Missing headers' }
        $lines=@($response.Substring(0,$separator.Index) -split '\r?\n')
        if($lines[0] -cnotmatch ('^HTTP/(1\.[01]|2) '+$status+'(?: |$)')) { throw 'Status mismatch' }
        $headers=@{}
        foreach($line in @($lines | Select-Object -Skip 1)) {
            if($line -cnotmatch '^([A-Za-z0-9-]+):[ \t]*(.*)$') { throw 'Bad header' }
            $name=$Matches[1]; $value=$Matches[2]
            if($name -ieq 'Location' -and $headers.ContainsKey($name)) { throw 'Ambiguous location' }
            $headers[$name]=@($value)
        }
        return @{StatusCode=[int]$status;Headers=$headers;Content=$response.Substring($separator.Index+$separator.Length)}
    } catch { throw $message }
}
