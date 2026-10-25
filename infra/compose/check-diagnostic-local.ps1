#requires -Version 7.3
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'diagnostic-local.ps1')
$p=@{Project='credpay-diag-20261025-1234abcd';Nonce=('a'*32)}
function Fixture {
    return @{Containers=@(@{Id=('b'*64);Name="$($p.Project)-transacoes-1";Service='transacoes';Project=$p.Project;Nonce=$p.Nonce;Privileged=$false;User='10001:10001';Mounts=@();Ports=@();Networks=@("$($p.Project)_default");Caps=@('ALL');Security=@('no-new-privileges:true');Devices=@()});
        Volumes=@(@{Name="$($p.Project)_transacoes-data";Created='created:2026-10-25T00:00:00Z';Project=$p.Project;Nonce=$p.Nonce;Driver='local';Options=@{}});
        Networks=@(@{Name="$($p.Project)_default";Id=('c'*64);Project=$p.Project;Nonce=$p.Nonce;Internal=$true;Driver='bridge';Containers=@('b'*64)})}
}
$count=0; $failed=@()
function Check($Name,[scriptblock]$Body,[bool]$Reject=$false) {
    $script:count++; $thrown=$false
    try { & $Body } catch { $thrown=$true }
    if($thrown -ne $Reject) { $script:failed+=$Name }
}
Check 'valid partial inventory' { Assert-CredPayDiagnosticInventory $p (Fixture) }
Check 'empty inventory' { Assert-CredPayDiagnosticInventory $p @{Containers=@();Volumes=@();Networks=@()} }
foreach($field in @('Nonce','Project','Privileged','User','Networks','Mounts','Ports','Caps','Security','Devices','Service','Name','Id')) {
    $f=Fixture
    $f.Containers[0][$field]=switch($field) {
        Privileged { $true }; Networks { @('host') }; Mounts { @(@{Type='bind';Destination='/workspace';Name='host'}) }
        Ports { @(@{Ip='0.0.0.0';Port=18080}) }; Caps { @('SYS_ADMIN') }; Security { @() }; Devices { @('host') }; default { 'other' }
    }
    Check "reject container $field" { Assert-CredPayDiagnosticInventory $p $f } $true
}
foreach($field in @('Name','Nonce','Project','Driver','Created','Options')) {
    $f=Fixture; $f.Volumes[0][$field]=if($field -ceq 'Options') { @{device='/host'} } else { 'other' }
    Check "reject volume $field" { Assert-CredPayDiagnosticInventory $p $f } $true
}
foreach($field in @('Name','Nonce','Project','Internal','Id','Containers','Driver')) {
    $f=Fixture; $f.Networks[0][$field]=if($field -ceq 'Internal') { $false } elseif($field -ceq 'Containers') { @('d'*64) } else { 'other' }
    Check "reject network $field" { Assert-CredPayDiagnosticInventory $p $f } $true
}
$f=Fixture; $f.Containers+= $f.Containers[0]
Check 'reject duplicate resources' { Assert-CredPayDiagnosticInventory $p $f } $true
Check 'exact identity removal' { Assert-CredPayDiagnosticRemoval (Fixture) (Fixture) }
$expected=Fixture; $changed=Fixture; $changed.Containers[0].Id='e'*64
Check 'refuse replaced container on cleanup' { Assert-CredPayDiagnosticRemoval $expected $changed } $true
$changed=Fixture; $changed.Volumes[0].Created='created:2026-10-25T01:00:00Z'
Check 'refuse replaced volume on cleanup' { Assert-CredPayDiagnosticRemoval $expected $changed } $true
Check 'refuse missing resource on cleanup' { Assert-CredPayDiagnosticRemoval (Fixture) @{Containers=@();Volumes=@();Networks=@()} } $true
if($failed.Count) { throw "Diagnostic ownership: failed=$($failed.Count)/$count; cases=$($failed -join ', ')" }
Write-Host "Diagnostic ownership: $count offline controls passed; no Docker or credentials."
