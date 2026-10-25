#requires -Version 7.3
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'diagnostic-local.ps1')
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$root=Join-Path $repo ('.local/compose-process-controls/'+[Guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $root
$p=New-CredPayDiagnosticPlan $root 'credpay-diag-20261025-a1b2c3d4' ('a'*40) ('b'*32)
$null=New-Item -ItemType Directory -Path $p.Temp,$p.DockerConfig -Force
$runtime=@{Plan=$p;Docker=(Join-Path $p.Temp 'not-docker.exe');HostAddress='fixture-no-daemon';Pwsh=(Get-Process -Id $PID).Path}
$probe=Join-Path $p.Temp 'probe.ps1'
[IO.File]::WriteAllText($probe,@'
param($ExpectedHome,$ExpectedDockerConfig,$Literal)
$ErrorActionPreference='Stop'
if($env:HOME -cne $ExpectedHome -or $env:DOCKER_CONFIG -cne $ExpectedDockerConfig -or
    $env:DOCKER_HOST -cne 'fixture-no-daemon' -or $env:CREDPAY_NEGATIVE_SECRET -or
    $env:DOCKER_CONTEXT -or $env:HTTP_PROXY -or $env:HTTPS_PROXY -or $Literal -cne '{"a":"with spaces"}') { throw 'Fixture environment or arguments changed' }
if($IsWindows -and ($env:USERPROFILE -cne $ExpectedHome -or $env:PATHEXT -cnotin @('.EXE','.EXE;.CPL'))) { throw 'Native environment unavailable' }
[Console]::Error.WriteLine('fictitious CLI warning, not response data')
[Console]::Write('fixture-process-ok')
'@)
$saved=$env:CREDPAY_NEGATIVE_SECRET
try {
    $env:CREDPAY_NEGATIVE_SECRET='fictitious-control-not-a-secret'
    $start=New-CredPayDiagnosticProcess $runtime $runtime.Pwsh @('-NoLogo','-NoProfile','-File',$probe,$p.Temp,$p.DockerConfig,'{"a":"with spaces"}')
} finally { $env:CREDPAY_NEGATIVE_SECRET=$saved }
if($start.UseShellExecute -or -not $start.CreateNoWindow -or $start.Environment.ContainsKey('CREDPAY_NEGATIVE_SECRET')) { throw 'Inherited environment or shell execution' }
if((Invoke-CredPayLocalProcess $start 10) -cne 'fixture-process-ok') { throw 'Actual isolated child failed' }
foreach($bad in @(@('../outside',('a'*40),('b'*32)),@('credpay-ci-1-1',('a'*40),('b'*32)),
    @($p.Project,'latest',('b'*32)),@($p.Project,('a'*40),'nonce'),
    @('CREDPAY-diag-20261025-a1b2c3d4',('a'*40),('b'*32)),@($p.Project,('a'*39),('b'*32)))) {
    $refused=$false
    try { $null=New-CredPayDiagnosticPlan $root @bad } catch { $refused=$true }
    if(-not $refused) { throw 'Unsafe plan accepted' }
}
Write-Host 'Diagnostic process: real benign isolated PowerShell child, literal arguments and six unsafe plans checked; no Docker/network/profile.'
