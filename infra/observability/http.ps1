function ParseInternalHttp([string]$Response) {
    $match = [regex]::Match($Response, '(?s)^(?:(.*)\r?\n)?CREDPAY_HTTP_STATUS:([0-9]{3})$')
    if (-not $match.Success) { throw 'HTTP interno sem status; conteúdo omitido.' }
    return [pscustomobject]@{StatusCode=[int]$match.Groups[2].Value;Content=$match.Groups[1].Value}
}
