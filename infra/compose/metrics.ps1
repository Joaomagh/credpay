function PublishAttemptCount($Metric) {
    $invalidMessage = 'Métrica de publicação inválida; conteúdo omitido.'
    if ($Metric.name -cne 'credpay.messaging.publish.attempts' -or
            $Metric.measurements -isnot [array] -or $Metric.measurements.Count -ne 1 -or
            $Metric.availableTags -isnot [array] -or $Metric.availableTags.Count -gt 1) { throw $invalidMessage }
    $measurement = $Metric.measurements[0]
    $value = $measurement.value
    $numeric = $value -is [int] -or $value -is [long] -or $value -is [double] -or
        $value -is [float] -or $value -is [decimal] -or $value -is [System.Numerics.BigInteger]
    if ($measurement.statistic -cne 'COUNT' -or -not $numeric -or
            -not [double]::IsFinite([double]$value) -or $value -lt 0) { throw $invalidMessage }
    foreach ($tag in $Metric.availableTags) {
        if ($tag.tag -cne 'outcome' -or $tag.values -isnot [array] -or $tag.values.Count -eq 0) { throw $invalidMessage }
        foreach ($outcome in $tag.values) {
            if ($outcome -cnotin @('confirmed', 'returned', 'nacked', 'error')) { throw $invalidMessage }
        }
    }
    return [double]$value
}
