#requires -Version 7.3
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-safety.ps1')
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$name = 'credpay-local-20261024-a1b2c3d4'
$tag = 'f412652af18674c781f867d01240cf6012a35e4b'
$script:count = 0
$script:failures = @()
function Check([string]$CaseLabel, [scriptblock]$Action, [bool]$Reject = $false) {
    $script:count++
    $thrown = $false
    try { $null = & $Action } catch { $thrown = $true }
    if ($thrown -ne $Reject) { $script:failures += $CaseLabel }
}
function CopyFixture($Value) { return ($Value | ConvertTo-Json -Depth 30 | ConvertFrom-Json -Depth 30) }
$plan = New-CredPayLocalPlan $root $name $tag
Check 'valid local plan' { New-CredPayLocalPlan $root $name $tag }
foreach ($bad in @('credpay-ci-1-1','credpay-local-../../host','other','CREDPAY-local-20261024-a1b2c3d4')) {
    Check "reject name $bad" { New-CredPayLocalPlan $root $bad $tag } $true
}
Check 'reject mutable tag' { New-CredPayLocalPlan $root $name 'latest' } $true
$daemon = @{ os='linux'; arch='x86_64'; driver='overlayfs'; cpus=12; memory=12431081472; id='fixture-daemon'; security=@('name=cgroupns') }
Check 'supported daemon' { Assert-CredPayLocalDaemon $daemon }
foreach ($field in @('os','arch','driver','id')) {
    $bad = CopyFixture $daemon; $bad.$field = 'unsupported'
    if ($field -ceq 'id') { $bad.id = '' }
    Check "reject daemon $field" { Assert-CredPayLocalDaemon $bad } $true
}
foreach ($option in @('name=userns','name=rootless')) {
    $bad = CopyFixture $daemon; $bad.security = @($option)
    Check "reject daemon $option" { Assert-CredPayLocalDaemon $bad } $true
}
$node = [pscustomobject]@{
    id=('a' * 64); cluster=$name; role='control-plane'
    image='kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed'
    privileged=$true; userns=''; cgroupns='private'; devices=@()
    security=@('seccomp=unconfined','apparmor=unconfined'); env=@('PATH=/usr/bin','KUBECONFIG=/etc/kubernetes/admin.conf')
    tmpfs=[pscustomobject]@{'/tmp'='';'/run'=''}
    mounts=@(@{Type='volume';Destination='/var';Name='fixture-volume';RW=$true},
        @{Type='bind';Destination='/lib/modules';Source='/lib/modules';RW=$false})
    ports=[pscustomobject]@{'6443/tcp'=@(@{HostIp='127.0.0.1';HostPort='54321'})}
}
Check 'supported node' { Assert-CredPayLocalNode $node $plan $node.id }
$desktopNode=CopyFixture $node; $desktopNode.security+= 'label=disable'
Check 'Desktop privileged node with Docker SELinux normalization' { Assert-CredPayLocalNode $desktopNode $plan $desktopNode.id }
foreach ($options in @(
    @('apparmor=unconfined,seccomp=unconfined'),
    @('apparmor=unconfined,label=disable','seccomp=unconfined'),
    @('seccomp=unconfined','apparmor=unconfined','label=other'),
    @('seccomp=unconfined','apparmor=unconfined','label=disable','label=disable'),
    @('seccomp=unconfined','label=disable'),
    @('apparmor=unconfined','label=disable'),
    @('seccomp=unconfined','apparmor=unconfined','label=disable','unknown=value')
)) {
    $bad=CopyFixture $node; $bad.security=$options
    Check "reject unexpected security options $($options -join ',')" { Assert-CredPayLocalNode $bad $plan } $true
}
Check 'reject replaced node' { Assert-CredPayLocalNode $node $plan ('b' * 64) } $true
foreach ($field in @('cluster','role','image','userns','cgroupns')) {
    $bad = CopyFixture $node; $bad.$field='other'
    Check "reject node $field" { Assert-CredPayLocalNode $bad $plan } $true
}
$bad=CopyFixture $node; $bad.devices=@(@{PathOnHost='/dev/fuse'})
Check 'reject extra device' { Assert-CredPayLocalNode $bad $plan } $true
$bad=CopyFixture $node; $bad.mounts+=@{Type='bind';Destination='/workspace';Source=$root;RW=$true}
Check 'reject workspace mount' { Assert-CredPayLocalNode $bad $plan } $true
$bad=CopyFixture $node; $bad.mounts[1].RW=$true
Check 'reject writable modules' { Assert-CredPayLocalNode $bad $plan } $true
$bad=CopyFixture $node; $bad.mounts[1].Source='/other'
Check 'reject different mount source' { Assert-CredPayLocalNode $bad $plan } $true
$bad=CopyFixture $node; $bad.ports.'6443/tcp'[0].HostIp='0.0.0.0'
Check 'reject public API' { Assert-CredPayLocalNode $bad $plan } $true
$bad=CopyFixture $node; $bad.ports | Add-Member NoteProperty '8080/tcp' @(@{HostIp='127.0.0.1';HostPort='8080'})
Check 'reject extra port' { Assert-CredPayLocalNode $bad $plan } $true
$bad=CopyFixture $node; $bad.env+= 'https_proxy=http://fixture.invalid:3128'
Check 'reject forwarded proxy' { Assert-CredPayLocalNode $bad $plan } $true
$bad=CopyFixture $node; $bad.tmpfs | Add-Member NoteProperty '/extra' ''
Check 'reject extra tmpfs' { Assert-CredPayLocalNode $bad $plan } $true
$config = [pscustomobject]@{
    'current-context'="kind-$name"
    contexts=@(@{name="kind-$name";context=@{cluster="kind-$name";user="kind-$name"}})
    clusters=@(@{name="kind-$name";cluster=@{server='https://127.0.0.1:54321';'certificate-authority-data'='fixture'}})
    users=@(@{name="kind-$name";user=@{'client-certificate-data'='fixture';'client-key-data'='fixture'}})
}
$config = CopyFixture $config
Check 'supported kubeconfig' { Assert-CredPayLocalKubeconfig $config $plan 54321 }
$bad=CopyFixture $config; $bad.clusters[0].cluster.server='https://external.invalid:54321'
Check 'reject remote cluster' { Assert-CredPayLocalKubeconfig $bad $plan 54321 } $true
$bad=CopyFixture $config; $bad.users[0].user | Add-Member NoteProperty exec @{command='fixture'}
Check 'reject credential exec plugin' { Assert-CredPayLocalKubeconfig $bad $plan 54321 } $true
$bad=CopyFixture $config; $bad.users[0].user | Add-Member NoteProperty 'client-key' '../outside.key'
Check 'reject credential file' { Assert-CredPayLocalKubeconfig $bad $plan 54321 } $true
$bad=CopyFixture $config; $bad.clusters[0].cluster | Add-Member NoteProperty 'insecure-skip-tls-verify' $true
Check 'reject insecure TLS' { Assert-CredPayLocalKubeconfig $bad $plan 54321 } $true
$owner = [pscustomobject]@{schema=1;stage='Owned';cluster=$name;imageTag=$tag;daemonId=$daemon.id;nodeId=$node.id;kubeconfig=$plan.Kubeconfig}
Check 'supported owner' { Assert-CredPayLocalOwner $owner $plan $daemon $node }
foreach ($field in @('schema','stage','cluster','imageTag','daemonId','nodeId','kubeconfig')) {
    $bad=CopyFixture $owner; $bad.$field=if ($field -ceq 'schema') { 2 } else { 'other' }
    Check "reject owner $field" { Assert-CredPayLocalOwner $bad $plan $daemon $node } $true
}
Check 'reject outside path' { Assert-CredPayLocalPath $root (Join-Path $root '../outside') } $true
$controls=Join-Path $root ('.local/k8s-controls/' + [Guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $controls -Force
$target=Join-Path $controls 'target'; $null=New-Item -ItemType Directory -Path $target
$link=Join-Path $controls 'link'
$linkType=if ($IsWindows) { 'Junction' } else { 'SymbolicLink' }
$null=New-Item -ItemType $linkType -Path $link -Target $target
Check 'reject redirected path' { Assert-CredPayLocalPath $root (Join-Path $link 'config.json') } $true
$moved=Join-Path $controls 'moved-target'
Assert-CredPayLocalPath $root $target
Assert-CredPayLocalPath $root $moved
Move-Item -LiteralPath $target -Destination $moved
Check 'reject dangling redirected path' { Assert-CredPayLocalPath $root (Join-Path $link 'config.json') } $true
$pwsh=(Get-Process -Id $PID).Path
$systemDirectory=if ($IsWindows) { [Environment]::SystemDirectory } else { '/usr/bin' }
$probe=Join-Path $controls 'arguments.ps1'
[IO.File]::WriteAllText($probe, '[Console]::Write(($args | ConvertTo-Json -Compress))', [Text.UTF8Encoding]::new($false))
$payload=@('with spaces','{"field":"a b"}','--')
$start=New-CredPayLocalProcess $pwsh (@('-NoProfile','-File',$probe) + $payload) $plan (Join-Path $controls 'docker.exe') $systemDirectory
Check 'private child environment' {
    if ($start.UseShellExecute -or -not $start.CreateNoWindow -or $start.WorkingDirectory -cne $root -or
        $start.Environment['DOCKER_CONFIG'] -cne $plan.DockerConfig -or $start.Environment['DOCKER_HOST'] -cne $plan.DockerHost -or
        $start.Environment.ContainsKey('HTTP_PROXY') -or $start.Environment.ContainsKey('DOCKER_CONTEXT') -or
        $start.Environment.ContainsKey('DOCKER_TLS_VERIFY') -or
        ($IsWindows -and $start.Environment['USERPROFILE'] -cne $plan.Temp) -or
        (-not $IsWindows -and $start.Environment.ContainsKey('USERPROFILE')) -or
        ($IsWindows -and -not $start.Environment.ContainsKey('ProgramFiles'))) { throw 'Unsafe child environment.' }
}
Check 'private child home is the session temp' {
    if ($start.Environment['HOME'] -cne $plan.Temp) { throw 'Home outside private session.' }
}
Check 'Windows native executable recognition is minimal and explicit' {
    if (($IsWindows -and $start.Environment['PATHEXT'] -cne '.EXE') -or
        (-not $IsWindows -and $start.Environment.ContainsKey('PATHEXT'))) { throw 'Executable extension environment is unsafe.' }
}
$nativeProbe=Join-Path $controls 'native.ps1'
[IO.File]::WriteAllText($nativeProbe, @'
param($Executable)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
# Refuse before invocation when the extension is absent: never open a detached document/window.
if ($IsWindows -and $env:PATHEXT -cnotin @('.EXE','.EXE;.CPL')) { throw 'Native extension is missing.' }
$result=@(& $Executable -NoLogo -NoProfile -Command '[Console]::Write("native-output"); exit 7')
if ($LASTEXITCODE -ne 7 -or $result.Count -ne 1 -or $result[0] -cne 'native-output') { throw 'Native output/exit was not captured.' }
[Console]::Write('native-probe-passed')
'@)
$nativeStart=New-CredPayLocalProcess $pwsh @('-NoLogo','-NoProfile','-File',$nativeProbe,$pwsh) $plan (Join-Path $controls 'docker.exe') $systemDirectory
Check 'actual child PowerShell captures native output and nonzero exit' {
    if ((Invoke-CredPayLocalProcess $nativeStart 10) -cne 'native-probe-passed') { throw 'Native invocation unavailable.' }
}
Check 'literal arguments reach child' {
    $actual=@((Invoke-CredPayLocalProcess $start 10) | ConvertFrom-Json)
    if (($actual -join '|') -cne ($payload -join '|')) { throw 'Arguments changed.' }
}
$sleep=Join-Path $controls 'sleep.ps1'; [IO.File]::WriteAllText($sleep, 'Start-Sleep -Seconds 5')
$slow=New-CredPayLocalProcess $pwsh @('-NoProfile','-File',$sleep) $plan (Join-Path $controls 'docker.exe') $systemDirectory
Check 'child timeout refuses success' {
    $timeoutObserved=$false
    try { $null=Invoke-CredPayLocalProcess $slow 1 } catch { $timeoutObserved=$_.Exception.Message -like '*excedeu o prazo*' }
    if (-not $timeoutObserved) { throw 'Expected deadline was not observed.' }
}
$exitFile=Join-Path $controls 'exit.ps1'; [IO.File]::WriteAllText($exitFile, '[Console]::Error.Write("fixture-error"); exit 7')
$failed=New-CredPayLocalProcess $pwsh @('-NoProfile','-File',$exitFile) $plan (Join-Path $controls 'docker.exe') $systemDirectory
Check 'nonzero exit is refused without raw stderr' {
    $refused=$false
    try { $null=Invoke-CredPayLocalProcess $failed 10 } catch { $refused=$_.Exception.Message -like '*exit=7*' -and $_.Exception.Message -notlike '*fixture-error*' }
    if (-not $refused) { throw 'Exit status or redaction changed.' }
}
$environment=@{DOCKER_HOST=$plan.DockerHost;DOCKER_CONFIG=$plan.DockerConfig;KUBECONFIG=$plan.Kubeconfig;KIND_EXPERIMENTAL_PROVIDER='docker';CREDPAY_IMAGE_TAG=$tag;HOME=$plan.Temp}
if ($IsWindows) { $environment.USERPROFILE=$plan.Temp; $environment.PATHEXT='.EXE' }
Check 'supported verifier environment' { Assert-CredPayLocalEnvironment $environment $plan }
foreach ($field in @('DOCKER_HOST','DOCKER_CONFIG','KUBECONFIG','KIND_EXPERIMENTAL_PROVIDER','CREDPAY_IMAGE_TAG','HOME') + $(if ($IsWindows) { @('USERPROFILE','PATHEXT') })) {
    $bad=@{}+$environment; $bad[$field]='other'
    Check "reject verifier $field" { Assert-CredPayLocalEnvironment $bad $plan } $true
}
foreach ($field in @('DOCKER_CONTEXT','DOCKER_TLS_VERIFY','https_proxy','DOCKER_AUTH_CONFIG','KIND_EXPERIMENTAL_DOCKER_NETWORK')) {
    $bad=@{}+$environment; $bad[$field]='fixture'
    Check "reject inherited $field" { Assert-CredPayLocalEnvironment $bad $plan } $true
}
$entry=Join-Path $controls 'entry.ps1'
[IO.File]::WriteAllText($entry, 'param($Library,$Root,$Cluster,$Tag); . $Library; $p=New-CredPayLocalPlan $Root $Cluster $Tag; Initialize-CredPayLocalEnvironment $p; if ($IsWindows -and (-not $env:ProgramFiles -or $env:PATHEXT -cne ".EXE")) { throw "Native entry environment missing" }; [Console]::Write("child-environment-ready")')
$entryStart=New-CredPayLocalProcess $pwsh @('-NoProfile','-File',$entry,(Join-Path $PSScriptRoot 'local-runtime.ps1'),$root,$name,$tag) $plan (Join-Path $controls 'docker.exe') $systemDirectory
Check 'verifier entry accepts actual isolated child environment' {
    if ((Invoke-CredPayLocalProcess $entryStart 10) -cne 'child-environment-ready') { throw 'Verifier environment unavailable.' }
}
Check 'empty Docker client config' { Assert-CredPayLocalDockerConfig ([pscustomobject]@{}) }
foreach ($field in @('auths','proxies','credsStore')) {
    $config=[pscustomobject]@{}; $config | Add-Member NoteProperty $field 'fixture'
    Check "reject Docker client $field" { Assert-CredPayLocalDockerConfig $config } $true
}
if ($failures.Count -gt 0) { throw "Local safety: failed=$($failures.Count)/$count; cases=$($failures -join ', ')" }
Write-Host "Local safety: $count offline controls passed; only fictitious local children, no daemon or credentials accessed."
