function Json($Content) {
    try {
        if ($Content -is [byte[]]) {
            $text = [Text.UTF8Encoding]::new($false, $true).GetString($Content)
        } elseif ($Content -is [string]) {
            $text = $Content
        } else { throw 'Tipo não suportado' }
        return ($text | ConvertFrom-Json)
    } catch { throw 'JSON inválido; conteúdo omitido.' }
}
