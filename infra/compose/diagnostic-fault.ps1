# Transport is supplied by the isolated Compose runner; this file has no top-level IO.
function Assert-CredPayFaultMode($Action,$Project,$EnvFile,[bool]$Diagnostics) {
    if ($Action -cne 'Smoke' -or $EnvFile -cne '.env.example' -or -not $Diagnostics -or
        $Project -cnotmatch '^credpay-(ci-[0-9]+-[0-9]+|diag-[0-9]{8}-[0-9a-f]{8})$') {
        throw 'FaultDiagnostics exige Smoke descartável próprio, dados de exemplo e Diagnostics.'
    }
}
function Get-CredPayFaultRestoration([string]$Message) {
    if($Message -cmatch '^Diagnóstico interrompido; fase=[a-z-]+; restauração da rota NÃO comprovada; detalhes omitidos\.$') { return 'unverified' }
    if($Message -cmatch '^Diagnóstico interrompido; fase=[a-z-]+; rota restaurada; detalhes omitidos\.$') { return 'verified' }
    return 'not-attempted'
}
function Invoke-CredPayMetricFault([string]$Publisher) {
    if ($Publisher -cnotin @('transacoes','processamento')) { throw 'Publicador fora do diagnóstico.' }
    $phase='baseline'; $failed=$false; $restored=$false; $changed=$false
    try {
        CheckMetricFaultReady
        $baseline=ReadFaultCount $Publisher
        $phase='unbind'
        # A failed transport may still have removed the route; always restore once attempted.
        $changed=$true
        ChangeFaultBinding $Publisher $false
        AssertFaultBinding $Publisher $false
        $phase='create'; $record=NewFaultTransaction
        $phase='returned'; $deadline=[DateTime]::UtcNow.AddSeconds(30)
        do {
            AssertFaultProcessesStable
            $current=ReadFaultCount $Publisher
            if ($current -lt $baseline) { throw 'Counter restarted' }
            if ($current -ge $baseline+1) { break }
            if ([DateTime]::UtcNow -ge $deadline) { throw 'No returned attempt' }
            Start-Sleep -Milliseconds 300
        } while ($true)
        $phase='pending-proof'; $snapshot=ReadFaultPending $record $Publisher
    } catch { $failed=$true }
    finally {
        if ($changed) {
            try { ChangeFaultBinding $Publisher $true; AssertFaultBinding $Publisher $true; $restored=$true }
            catch { $restored=$false }
        } else { $restored=$true }
    }
    if (-not $restored) { throw "Diagnóstico interrompido; fase=$phase; restauração da rota NÃO comprovada; detalhes omitidos." }
    if ($failed -and -not $changed) { throw "Diagnóstico interrompido; fase=$phase; rota não alterada; detalhes omitidos." }
    if ($failed) { throw "Diagnóstico interrompido; fase=$phase; rota restaurada; detalhes omitidos." }
    try { AssertFaultProcessesStable; ConfirmFaultRecovery $record $Publisher $snapshot; AssertFaultProcessesStable }
    catch { throw 'Diagnóstico interrompido; fase=recovery; rota restaurada; detalhes omitidos.' }
    Write-Host "Compose causal: publicador=$Publisher; returned delta=$($current-$baseline); PENDENTE/outbox original preservados; rota restaurada e recuperação causal sem duplicação."
}
