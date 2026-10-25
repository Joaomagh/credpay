#requires -Version 7.3
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'diagnostic-fault.ps1')
$script:cases=0; $script:failures=@()
function Check($Name,[scriptblock]$Test) {
    $script:cases++
    try { & $Test } catch { $script:failures+= $Name }
}
function ResetFixture {
    $script:trace=[Collections.Generic.List[string]]::new()
    $script:binding=$true; $script:metric=0; $script:failed=$false
    $script:restoreFails=$false; $script:restart=$false
    $script:decrease=$false; $script:badPending=$false; $script:reads=0
    $script:badReady=$false
}
function CheckMetricFaultReady { $script:trace.Add('ready'); if($script:badReady) { throw 'Fictitious failed baseline' } }
function AssertFaultProcessesStable {}
function ReadFaultCount($Publisher) {
    $script:reads++
    if($script:decrease) { if($script:reads -eq 1) { return 3 }; return 1 }
    return $script:metric
}
function ChangeFaultBinding($Publisher,[bool]$Present) {
    $script:trace.Add("binding=$Present")
    if($Present -and $script:restoreFails) { throw 'fictitious restore error containing private text' }
    $script:binding=$Present
}
function AssertFaultBinding($Publisher,[bool]$Present) { if($script:binding -ne $Present) { throw 'Wrong route' }; $script:trace.Add("checked=$Present") }
function NewFaultTransaction {
    $script:trace.Add('post')
    if($script:failed) { throw 'fictitious post failure containing private text' }
    $script:metric=3
    return @{Id='e72ba712-955d-426a-a4f9-23764e194356'}
}
function ReadFaultPending($Record,$Publisher) {
    if($script:badPending) { throw 'Fictitious changed pending row' }
    if($script:binding) { throw 'Expected route absent' }
    $script:trace.Add('pending-proof')
    return @{Hash=('a'*32);Event=('b'*36)}
}
function ConfirmFaultRecovery($Record,$Publisher,$Snapshot) {
    if(-not $script:binding -or $Snapshot.Hash -cne ('a'*32)) { throw 'Recovery evidence lost' }
    $script:trace.Add('recovered')
}
function Start-Sleep {} # No real delays in fictitious fixture.
foreach($publisher in @('transacoes','processamento')) {
    ResetFixture
    Check "causal route failure and recovery $publisher" {
        Invoke-CredPayMetricFault $publisher
        if(($script:trace -join ',') -cne 'ready,binding=False,checked=False,post,pending-proof,binding=True,checked=True,recovered') { throw 'Missing causal evidence or wrong order' }
    }
}
ResetFixture; $script:failed=$true
Check 'failed POST still restores and verifies route, sanitized error' {
    $message=''
    try { Invoke-CredPayMetricFault 'transacoes' } catch { $message=$_.Exception.Message }
    if(-not $script:binding -or -not $script:trace.Contains('checked=True') -or $message -cne 'Diagnóstico interrompido; fase=create; rota restaurada; detalhes omitidos.') { throw 'Failure restoration not proved' }
}
ResetFixture; $script:failed=$true; $script:restoreFails=$true
Check 'restore failure is explicit without private original errors' {
    $message=''
    try { Invoke-CredPayMetricFault 'transacoes' } catch { $message=$_.Exception.Message }
    if($message -cne 'Diagnóstico interrompido; fase=create; restauração da rota NÃO comprovada; detalhes omitidos.') { throw 'Restore error hidden' }
}
ResetFixture
Check 'unknown publisher rejected before mutation' {
    $refused=$false
    try { Invoke-CredPayMetricFault '../other' } catch { $refused=$true }
    if(-not $refused -or $script:trace.Count -ne 0) { throw 'Unsafe publisher allowed' }
}
ResetFixture; $script:decrease=$true
Check 'counter decrease refuses diagnosis and restores route' {
    $message=''
    try { Invoke-CredPayMetricFault 'transacoes' } catch { $message=$_.Exception.Message }
    if($message -cne 'Diagnóstico interrompido; fase=returned; rota restaurada; detalhes omitidos.' -or -not $script:binding -or $script:trace.Contains('recovered')) { throw 'Counter reset accepted' }
}
ResetFixture; $script:badPending=$true
Check 'changed pending evidence refuses diagnosis and restores route' {
    $message=''
    try { Invoke-CredPayMetricFault 'processamento' } catch { $message=$_.Exception.Message }
    if($message -cne 'Diagnóstico interrompido; fase=pending-proof; rota restaurada; detalhes omitidos.' -or -not $script:binding -or $script:trace.Contains('recovered')) { throw 'Changed pending evidence accepted' }
}
foreach($bad in @(
    @('Demo','credpay-diag-20261025-1234abcd','.env.example',$true),
    @('Smoke','credpay-demo','.env.example',$true),
    @('Smoke','credpay-ci-12-1','.env',$true),
    @('Smoke','credpay-ci-12-1','.env.example',$false))) {
    Check 'fault mode rejects unsafe destination/configuration' {
        $rejected=$false
        try { Assert-CredPayFaultMode @bad } catch { $rejected=$true }
        if(-not $rejected) { throw 'Fault destination accepted' }
    }
}
ResetFixture; $script:badReady=$true
Check 'baseline refusal does not claim route restoration' {
    $message=''
    try { Invoke-CredPayMetricFault 'transacoes' } catch { $message=$_.Exception.Message }
    if($message -cne 'Diagnóstico interrompido; fase=baseline; rota não alterada; detalhes omitidos.' -or $script:trace.Contains('binding=True')) { throw 'Restoration claimed without attempt' }
}
foreach($pair in @(@('Diagnóstico interrompido; fase=create; rota restaurada; detalhes omitidos.','verified'),
    @('Diagnóstico interrompido; fase=create; restauração da rota NÃO comprovada; detalhes omitidos.','unverified'),
    @('fictitious private text rota restaurada','not-attempted'))) {
    Check 'restoration projection only accepts exact safe error format' { if((Get-CredPayFaultRestoration $pair[0]) -cne $pair[1]) { throw 'Wrong safe state' } }
}
Check 'fault mode accepts fresh CI target contract' { Assert-CredPayFaultMode 'Smoke' 'credpay-ci-12-1' '.env.example' $true }
if($script:failures.Count) { throw "Fault controls: failed=$($script:failures.Count)/$script:cases; cases=$($script:failures -join ', ')" }
Write-Host "Fault controls: $script:cases offline causal scenarios passed; no Docker or HTTP."
