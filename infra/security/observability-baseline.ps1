function ReadObservabilityBaseline {
    $path = Join-Path $PSScriptRoot '../observability/images.env'
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($path)) {
        if ($line -cmatch '^\s*(#|$)') { continue }
        if ($line -cnotmatch '^(PROMETHEUS|GRAFANA)_(IMAGE|PLATFORM_DIGEST)=(.+)$') { throw 'Baseline de observação inválida.' }
        $key = "$($Matches[1])_$($Matches[2])"
        if ($values.ContainsKey($key)) { throw 'Baseline de observação duplicada.' }
        $values[$key] = $Matches[3]
    }
    $targets = @()
    foreach ($name in @('prometheus', 'grafana')) {
        $prefix = $name.ToUpperInvariant()
        $reference = $values["${prefix}_IMAGE"]
        $platform = $values["${prefix}_PLATFORM_DIGEST"]
        $repository = if ($name -ceq 'prometheus') { 'prom/prometheus' } else { 'grafana/grafana' }
        if ($reference -cnotmatch ('^' + [regex]::Escape($repository) + ':[a-zA-Z0-9._-]+@sha256:[a-f0-9]{64}$') -or
            $platform -cnotmatch '^sha256:[a-f0-9]{64}$') { throw 'Referência pinada/plataforma inválida.' }
        $targets += @{Name=$name; Reference=$reference; PlatformDigest=$platform; Repository=$repository}
    }
    if ($values.Count -ne 4) { throw 'Baseline de observação incompleta.' }
    return $targets
}
