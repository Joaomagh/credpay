#requires -Version 7.3
# Actual session files and inventory projection; native Docker/HTTP transport is fictitious with no fallback.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'diagnostic-local.ps1')
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$tag='f412652af18674c781f867d01240cf6012a35e4b'
$script:checks=0
function NewFixture {
    $directory=Join-Path $repo ('.local/compose-controls/'+[Guid]::NewGuid().ToString('N'))
    $null=New-Item -ItemType Directory -Path $directory
    $null=New-Item -ItemType Directory -Path (Join-Path $directory 'infra/compose')
    foreach($name in @('compose.yaml','compose.diagnostics.yaml','.env.example')) { [IO.File]::WriteAllText((Join-Path $directory "infra/compose/$name"),'fictitious-input') }
    $script:fixture=@{Root=$directory;Created=$false;Existing=$false;FailSmoke=$false;Replacement=$false;External=$false;WrongDaemon=$false;Commands=[Collections.Generic.List[string]]::new()}
}
function Get-CredPayDiagnosticRuntime($Plan) { $fixture.Plan=$Plan; return @{Plan=$Plan;Docker='fixture-docker';HostAddress='fixture-host';Pwsh='fixture-pwsh'} }
function Invoke-CredPayDiagnosticTool($Runtime,[string]$File,[string[]]$Arguments,[int]$Timeout=30) {
    $a=$Arguments; $p=$Runtime.Plan; $fixture.Commands.Add(($a -join ' '))
    if($File -ceq 'fixture-pwsh') {
        if($a -notcontains '-FaultDiagnostics' -or $a -notcontains '.env.example' -or $a -notcontains $p.Project) { throw 'Wrong child contract' }
        $fixture.Created=$true
        if($fixture.FailSmoke) {
            if($fixture.Restoration) { Write-CredPayLocalJson @{phase='fault-transacoes';category='binding';restoration=$fixture.Restoration} (Join-Path $p.Directory 'progress.json') $p.Root }
            throw 'Fictitious smoke failure; private text'
        }
        return 'Fictitious smoke completed; no real application.'
    }
    if($File -cne 'fixture-docker') { throw 'Unknown tool; no native fallback' }
    if($a[0] -ceq 'info') { return (@{id=$(if($fixture.WrongDaemon){'different-daemon'}else{'fixture-daemon'});os='linux';arch='amd64';driver='overlayfs';cpus=12;memory=12GB;security=@()} | ConvertTo-Json -Compress) }
    if($a[0] -ceq 'image') { return ('sha256:'+('f'*64)+'/linux/amd64') }
    if($a[0] -ceq 'compose') {
        if($a -notcontains 'down' -or $a -notcontains '--volumes' -or $a -contains '--remove-orphans') { throw 'Unexpected mutation' }
        $fixture.Created=$false; return ''
    }
    $services=@('transacoes','processamento','db-transacoes','db-processamento','rabbit')
    $ids=@('a','b','c','d','e' | ForEach-Object { $_*64 })
    $volumes=@('transacoes-data','processamento-data','rabbit-data' | ForEach-Object { "$($p.Project)_$_" })
    $present=$fixture.Created -or $fixture.Existing
    if($a[0] -ceq 'ps') {
        if(-not $present) { return '' }
        $volumeFilter=@($a | Where-Object { $_ -clike 'volume=*' })
        if($volumeFilter.Count) {
            $index=[array]::IndexOf($volumes,$volumeFilter[0].Substring(7))
            if($fixture.External) { return ('9'*64) }
            return $ids[$index+2]
        }
        return ($ids -join "`n")
    }
    if($a[1] -ceq 'ls') {
        if(-not $present) { return '' }
        if($a[0] -ceq 'volume') { return ($volumes -join "`n") }
        if($a[0] -ceq 'network') { return ('1'*64) }
    }
    if($a[1] -ceq 'inspect') {
        $nonce=if($fixture.Existing) { 'foreign-owner' } else { $p.Nonce }
        if($a[0] -ceq 'container') {
            $index=[array]::IndexOf($ids,$a[-1]); if($index -lt 0) { throw 'Unexpected container' }
            $service=$services[$index]; $containerId=if($fixture.Replacement -and $index -eq 0) { '8'*64 } else { $ids[$index] }
            $row=@{Id=$containerId;Name="/$($p.Project)-$service-1";Service=$service;Project=$p.Project;Nonce=$nonce;Privileged=$false;
                User='';Mounts=@();Bindings=$null;Networks=@{("$($p.Project)_default")=@{}};Caps=$null;Security=$null;Devices=$null}
            if($index -lt 2) {
                $port=if($index -eq 0) { $p.TxPort } else { $p.ProcPort }
                $row.User='10001:10001'; $row.Bindings=@{'8080/tcp'=@(@{HostIp='127.0.0.1';HostPort=[string]$port})}; $row.Caps=@('ALL'); $row.Security=@('no-new-privileges:true')
            } else { $row.Mounts=@(@{Type='volume';Name=$volumes[$index-2];Destination=$(if($index -eq 4){'/var/lib/rabbitmq'}else{'/var/lib/postgresql/data'})}) }
            return ($row | ConvertTo-Json -Depth 15 -Compress)
        }
        if($a[0] -ceq 'volume') { return (@{Name=$a[-1];Created='2026-10-25T00:00:00Z';Driver='local';Options=$null;Project=$p.Project;Nonce=$nonce} | ConvertTo-Json -Compress) }
        if($a[0] -ceq 'network') {
            $attachments=@{}; foreach($id in $ids) { $attachments[$id]=@{} }
            if($fixture.Replacement) { $attachments.Remove($ids[0]); $attachments[('8'*64)]=@{} }
            return (@{Id=('1'*64);Name="$($p.Project)_default";Driver='bridge';Internal=$true;Containers=$attachments;Project=$p.Project;Nonce=$nonce} | ConvertTo-Json -Depth 10 -Compress)
        }
    }
    throw 'Unrecognized fictitious command; no native fallback'
}
function Check([string]$Name,[scriptblock]$Action) {
    $script:checks++
    try { & $Action } catch { throw "Lifecycle fixture failed: $Name; $($_.Exception.Message)" }
}
NewFixture
Check 'run records real projected inventory without automatic cleanup' {
    Invoke-CredPayDiagnosticAction Run $tag $fixture.Root 18080 18081
    $s=Read-CredPayDiagnosticSession $fixture.Root $fixture.Plan.Project
    if($s.Owner.result -cne 'Validated' -or $s.Owner.inventory.Containers.Count -ne 5 -or -not $fixture.Created -or @($fixture.Commands | Where-Object { $_ -match ' down ' }).Count) { throw 'Missing lifecycle proof' }
}
Check 'active session refuses second run before commands' {
    $before=$fixture.Commands.Count; $refused=$false
    try { Invoke-CredPayDiagnosticAction Run $tag $fixture.Root } catch { $refused=$true }
    if(-not $refused -or $fixture.Commands.Count -ne $before) { throw 'Active session overwritten' }
}
Check 'replacement refuses cleanup with zero down' {
    $fixture.Replacement=$true; $refused=$false
    try { Invoke-CredPayDiagnosticAction Remove '' $fixture.Root } catch { $refused=$true }
    if(-not $refused -or -not $fixture.Created -or @($fixture.Commands | Where-Object { $_ -match ' down ' }).Count) { throw 'Replacement removed' }
    $fixture.Replacement=$false
}
Check 'foreign volume user refuses cleanup' {
    $fixture.External=$true; $refused=$false
    try { Invoke-CredPayDiagnosticAction Remove '' $fixture.Root } catch { $refused=$true }
    if(-not $refused -or -not $fixture.Created) { throw 'Foreign volume detached' }
    $fixture.External=$false
}
Check 'wrong daemon refuses cleanup' {
    $fixture.WrongDaemon=$true; $refused=$false
    try { Invoke-CredPayDiagnosticAction Remove '' $fixture.Root } catch { $refused=$true }
    if(-not $refused -or -not $fixture.Created) { throw 'Wrong daemon mutated' }
    $fixture.WrongDaemon=$false
}
Check 'changed Compose model refuses cleanup before any down' {
    $file=Join-Path $fixture.Root 'infra/compose/compose.yaml'
    [IO.File]::WriteAllText($file,'fictitious-changed-model-with-foreign-volume')
    $before=$fixture.Commands.Count; $refused=$false
    try { Invoke-CredPayDiagnosticAction Remove '' $fixture.Root } catch { $refused=$true }
    [IO.File]::WriteAllText($file,'fictitious-input')
    if(-not $refused -or -not $fixture.Created -or @($fixture.Commands | Select-Object -Skip $before | Where-Object { $_ -match ' down ' }).Count) { throw 'Changed model deleted resources' }
}
Check 'remove exact recorded resources and active pointer only' {
    Invoke-CredPayDiagnosticAction Remove '' $fixture.Root
    if($fixture.Created -or (Test-Path (Join-Path $fixture.Root '.local/compose-diagnostics/active.json')) -or -not (Test-Path $fixture.Plan.Overlay)) { throw 'Incorrect cleanup scope' }
}
NewFixture; $fixture.FailSmoke=$true
Check 'partial failure records owned resources for separate cleanup' {
    $refused=$false
    try { Invoke-CredPayDiagnosticAction Run $tag $fixture.Root 18080 18081 } catch { $refused=$true }
    $s=Read-CredPayDiagnosticSession $fixture.Root $fixture.Plan.Project
    if(-not $refused -or $s.Owner.stage -cne 'Owned' -or $s.Owner.result -cne 'Failed' -or -not $fixture.Created) { throw 'Partial state lost' }
    Invoke-CredPayDiagnosticAction Remove '' $fixture.Root
}
foreach($restoration in @('verified','unverified')) {
    NewFixture; $fixture.FailSmoke=$true; $fixture.Restoration=$restoration
    Check "sanitized restoration state crosses launcher boundary $restoration" {
        $captured=@(& { try { Invoke-CredPayDiagnosticAction Run $tag $fixture.Root 18080 18081 } catch { } } 6>&1)
        $summary=($captured | ForEach-Object { [string]$_ }) -join '|'
        if($summary -notlike "*restauração=$restoration*" -or $summary -like '*private text*') { throw 'Restoration state hidden or raw error leaked' }
    }
}
NewFixture; $fixture.Existing=$true
Check 'existing foreign namespace refuses all mutations' {
    $refused=$false
    try { Invoke-CredPayDiagnosticAction Run $tag $fixture.Root 18080 18081 } catch { $refused=$true }
    if(-not $refused -or $fixture.Created -or @($fixture.Commands | Where-Object { $_ -match '-FaultDiagnostics| down ' }).Count) { throw 'Foreign project mutated' }
}
Write-Host "Diagnostic lifecycle: $checks offline scenarios passed; actual files/projection, fictitious commands only."
