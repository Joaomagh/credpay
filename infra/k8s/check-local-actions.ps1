#requires -Version 7.3
# Actual lifecycle and file writes, fictitious command transport. Never invokes Docker/kind/kubectl.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'local-runtime.ps1')
. (Join-Path $PSScriptRoot 'local-actions.ps1')
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$tag='f412652af18674c781f867d01240cf6012a35e4b'
$script:caseCount=0
function NewFixture {
    $directory=Join-Path $repo ('.local/k8s-action-controls/' + [Guid]::NewGuid().ToString('N'))
    $null=New-Item -ItemType Directory -Path (Join-Path $directory '.local/evidence') -Force
    $artifacts=@()
    foreach ($module in @('transacoes-service','processamento-service')) {
        $file="$module/target/app.jar"; $full=Join-Path $directory $file
        $null=New-Item -ItemType Directory -Path (Split-Path $full -Parent) -Force
        [IO.File]::WriteAllText($full,'fixture-not-a-java-artifact')
        $artifacts+=@{path=$file;sha256=(Get-FileHash $full -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    Write-CredPayLocalJson @{sourceRevision=$tag;artifacts=$artifacts} (Join-Path $directory '.local/evidence/local-acceptance-prepared.json') $directory -New
    $script:fixture=@{Root=$directory;Created=$false;Existing=$false;FailCreate=$false;BadAlias=$false;BadId=$false;BadCache=$false;BadPolicy=$false;BadReady=$false;SparseIndex=$false;FailSave=$false;CommandCount=0;Events=[Collections.Generic.List[string]]::new()}
}
function Get-CredPayLocalRuntime($Plan) {
    # OS, installed executables and command launch are the external boundaries of this offline control.
    $script:fixture.Plan=$Plan
    return @{Plan=$Plan;Docker='fixture-docker';Pwsh='fixture-pwsh';SystemDirectory='fixture-system'}
}
function Invoke-CredPayLocalTool($Runtime,[string]$File,[string[]]$Arguments,[int]$Timeout=30) {
    $fixture.CommandCount++
    $p=$Runtime.Plan; $a=$Arguments
    if ($File -ceq 'fixture-docker') {
        if ($a[0] -ceq 'save') {
            if ($a[1] -cne '--platform=linux/amd64' -or $a[2] -cne '--output' -or $a.Count -ne 8) { throw 'Expected four-image amd64-only export.' }
            Assert-CredPayLocalPath $fixture.Root $a[3]
            if (-not $a[3].StartsWith($p.Temp + [IO.Path]::DirectorySeparatorChar)) { throw 'Archive outside session temp.' }
            $fixture.Events.Add('save')
            if ($fixture.FailSave) { throw 'Fixture: export failed.' }
            [IO.File]::WriteAllText($a[3],'fixture-archive-not-an-image')
            return ''
        }
        if ($a[0] -ceq 'info') { return '{"os":"linux","arch":"x86_64","driver":"overlayfs","cpus":12,"memory":12431081472,"id":"fixture-daemon","security":[]}' }
        if ($a[0] -ceq 'image') {
            $reference=$a[-1]
            $letter=if ($reference.StartsWith('credpay-transacoes:')) {'a'} elseif ($reference.StartsWith('credpay-processamento:')) {'b'} elseif ($reference.Contains('postgres')) {'c'} else {'d'}
            if ($fixture.BadAlias -and $reference.StartsWith('credpay-postgres:')) { $letter='e' }
            return ('sha256:' + ($letter * 64))
        }
        if ($a[0] -ceq 'inspect') {
            $identity=if ($fixture.BadId) { 'f' * 64 } else { 'a' * 64 }
            $data=@{id=$identity;cluster=$p.Cluster;role='control-plane';image=$p.NodeImage;privileged=$true;userns='';cgroupns='private';devices=@();security=@('seccomp=unconfined','apparmor=unconfined');env=@();tmpfs=@{'/run'='';'/tmp'=''};mounts=@(@{Type='volume';Destination='/var';Name='fixture-volume';RW=$true},@{Type='bind';Destination='/lib/modules';Source='/lib/modules';RW=$false});ports=@{'6443/tcp'=@(@{HostIp='127.0.0.1';HostPort='54321'})}}
            if ($fixture.BadPolicy) { $data.security+= 'unexpected=fixture' }
            return ($data | ConvertTo-Json -Depth 30 -Compress)
        }
        if ($a[0] -ceq 'exec') {
            $letter=if ($a[-1].StartsWith('credpay-transacoes:')) {'a'} elseif ($a[-1].StartsWith('credpay-processamento:')) {'b'} elseif ($a[-1].StartsWith('credpay-postgres:')) {'c'} else {'d'}
            if ($fixture.BadCache) { $letter='e' }
            if ($fixture.BadInfraCache -and $a[-1].StartsWith('credpay-rabbit:')) { $letter='e' }
            return (@{status=@{id=('sha256:' + ($letter * 64))}} | ConvertTo-Json -Compress)
        }
    } elseif ($File -ceq $p.Kind) {
        switch ($a[0]) {
            version { return 'kind v0.33.0 fixture windows/amd64' }
            get {
                if ($a[1] -ceq 'clusters') { if ($fixture.Existing -or $fixture.Created) { return $p.Cluster }; return '' }
                if ($a[1] -ceq 'nodes' -and $fixture.Created) { return "$($p.Cluster)-control-plane" }
            }
            create {
                $fixture.Events.Add('create'); $fixture.Created=$true
                if ($fixture.FailCreate) { throw 'Fixture: partial creation.' }
                [IO.File]::WriteAllText($p.Kubeconfig,'fixture-kubeconfig-not-a-credential')
                return ''
            }
            load {
                if ($fixture.SparseIndex -and $a[1] -cne 'image-archive') { throw 'Fixture: all-platform import references unavailable content.' }
                if ($a[1] -ceq 'image-archive') {
                    if ($a.Count -ne 5 -or $a[2] -cne '--name' -or $a[3] -cne $p.Cluster -or -not [IO.File]::Exists($a[4])) { throw 'Archive or target mismatch.' }
                    Assert-CredPayLocalPath $fixture.Root $a[4]
                }
                $fixture.Events.Add('load'); return ''
            }
            delete { $fixture.Events.Add('delete'); $fixture.Created=$false; return '' }
        }
    } elseif ($File -ceq $p.Kubectl) {
        if ($a -contains 'version') { return '{"clientVersion":{"gitVersion":"v1.36.4"}}' }
        if ($a -contains '--raw=/readyz') {
            $fixture.Events.Add('ready')
            if ($fixture.BadReady) { throw 'Fixture: TLS/API unavailable.' }
            return 'ok'
        }
        $context="kind-$($p.Cluster)"
        return (@{'current-context'=$context;contexts=@(@{name=$context;context=@{cluster=$context;user=$context}});clusters=@(@{name=$context;cluster=@{server='https://127.0.0.1:54321';'certificate-authority-data'='fixture'}});users=@(@{name=$context;user=@{'client-certificate-data'='fixture';'client-key-data'='fixture'}})} | ConvertTo-Json -Depth 30 -Compress)
    } elseif ($File -ceq 'fixture-pwsh') {
        if ($a[-2] -cne '-ExecutionProfile' -or $a[-1] -cne 'Local') { throw 'Verifier did not select Local explicitly.' }
        $fixture.Events.Add('verify'); return 'Fixture verification only.'
    }
    throw 'Unrecognized fixture command; native fallback is forbidden.'
}
function MustReject([scriptblock]$Run) {
    $rejected=$false
    try { & $Run 6>$null } catch { $rejected=$true }
    if (-not $rejected) { throw 'Expected refusal was absent.' }
}
function Case([string]$Label,[scriptblock]$Run) {
    $script:caseCount++
    try { & $Run } catch { throw "Local lifecycle fixture failed: $Label" }
}
Case 'create validate remove own lifecycle' {
    NewFixture
    Invoke-CredPayLocalAction Create $tag $fixture.Root 6>$null
    $p=$fixture.Plan
    if ((Read-CredPayLocalJson $p.Marker $fixture.Root).stage -cne 'Owned') { throw 'Owner not recorded.' }
    Invoke-CredPayLocalAction Validate $tag $fixture.Root 6>$null
    if ((Read-CredPayLocalJson $p.Marker $fixture.Root).stage -cne 'Validated' -or @($fixture.Events | Where-Object {$_ -ceq 'verify'}).Count -ne 3) { throw 'Verification sequence missing.' }
    $keep=Join-Path $fixture.Root '.local/k8s/unrelated.txt'; [IO.File]::WriteAllText($keep,'fixture-preserve')
    Invoke-CredPayLocalAction Remove $tag $fixture.Root 6>$null
    if ((Read-CredPayLocalJson $p.Marker $fixture.Root).stage -cne 'Removed' -or
        (Test-Path (Join-Path $fixture.Root '.local/k8s/active-local.json')) -or -not (Test-Path $keep)) { throw 'Cleanup scope mismatch.' }
}
Case 'existing cluster cannot be adopted' {
    NewFixture; $fixture.Existing=$true
    MustReject { Invoke-CredPayLocalAction Create $tag $fixture.Root }
    if ($fixture.Events.Count -ne 0) { throw 'Existing cluster was modified.' }
}
Case 'existing alias cannot be overwritten' {
    NewFixture; $fixture.BadAlias=$true
    MustReject { Invoke-CredPayLocalAction Create $tag $fixture.Root }
    if ($fixture.Events.Count -ne 0) { throw 'Cluster created after wrong alias.' }
}
Case 'partial creation cannot be adopted or deleted' {
    NewFixture; $fixture.FailCreate=$true
    MustReject { Invoke-CredPayLocalAction Create $tag $fixture.Root }
    MustReject { Invoke-CredPayLocalAction Remove $tag $fixture.Root }
    if ($fixture.Events -contains 'delete') { throw 'Partial cluster deleted without ownership.' }
    if ((Read-CredPayLocalJson $fixture.Plan.Marker $fixture.Root).stage -cne 'Creating') { throw 'Partial state not preserved.' }
}
Case 'replaced node cannot be deleted' {
    NewFixture; Invoke-CredPayLocalAction Create $tag $fixture.Root 6>$null; $fixture.BadId=$true
    MustReject { Invoke-CredPayLocalAction Remove $tag $fixture.Root }
    if ($fixture.Events -contains 'delete') { throw 'Replaced node deleted.' }
}
Case 'unknown action cannot delete cluster' {
    NewFixture; Invoke-CredPayLocalAction Create $tag $fixture.Root 6>$null
    MustReject { Invoke-CredPayLocalAction Unknown $tag $fixture.Root }
    if ($fixture.Events -contains 'delete') { throw 'Unknown action deleted a cluster.' }
}
Case 'default CI cannot accept local name' {
    NewFixture
    $saved=$env:GITHUB_ACTIONS
    try {
        $env:GITHUB_ACTIONS='true'
        MustReject { Get-CredPayKubernetesExecution $fixture.Root 'credpay-local-20261024-a1b2c3d4' }
        if ($fixture.CommandCount -ne 0) { throw 'CI touched a local runtime.' }
    } finally { $env:GITHUB_ACTIONS=$saved }
}
Case 'direct Local entry rejects another daemon before command dispatch' {
    NewFixture
    $savedHost=$env:DOCKER_HOST; $savedTag=$env:CREDPAY_IMAGE_TAG
    try {
        $env:DOCKER_HOST='tcp://fixture.invalid:2375'; $env:CREDPAY_IMAGE_TAG=$tag
        MustReject { Get-CredPayKubernetesExecution $fixture.Root 'credpay-local-20261024-a1b2c3d4' 'Local' }
        if ($fixture.CommandCount -ne 0) { throw 'Wrong daemon was contacted.' }
    } finally { $env:DOCKER_HOST=$savedHost; $env:CREDPAY_IMAGE_TAG=$savedTag }
}
Case 'failed image load keeps ownership and permits scoped cleanup' {
    NewFixture; $fixture.BadCache=$true
    MustReject { Invoke-CredPayLocalAction Create $tag $fixture.Root }
    if ((Read-CredPayLocalJson $fixture.Plan.Marker $fixture.Root).stage -cne 'Owned') { throw 'Proven ownership lost.' }
    Invoke-CredPayLocalAction Remove $tag $fixture.Root 6>$null
    if (-not ($fixture.Events -contains 'delete')) { throw 'Owned partial resources could not be removed.' }
}
function NewInterruptedFixture {
    NewFixture; $fixture.BadPolicy=$true
    MustReject { Invoke-CredPayLocalAction Create $tag $fixture.Root }
    $fixture.BadPolicy=$false
    $fixture.Events.Clear()
}
Case 'explicit resume finishes images without creating or deleting a cluster' {
    NewInterruptedFixture
    Invoke-CredPayLocalAction Resume $tag $fixture.Root ('a' * 64) 6>$null
    if ((Read-CredPayLocalJson $fixture.Plan.Marker $fixture.Root).stage -cne 'Owned' -or
        ($fixture.Events -join ',') -cne 'ready,save,load') { throw 'Recovery did not validate API before image export/load.' }
}
foreach ($fault in @('missing-id','wrong-id','bad-policy','bad-daemon','missing-config','bad-ready','bad-alias','wrong-app-ids','missing-original-config')) {
    Case "resume refuses $fault without promoting incomplete ownership" {
        NewInterruptedFixture
        $expected='a' * 64
        $p=$fixture.Plan
        switch ($fault) {
            missing-id { $expected='' }
            wrong-id { $expected='b' * 64 }
            bad-policy { $fixture.BadPolicy=$true }
            bad-daemon {
                $o=Read-CredPayLocalJson $p.Marker $fixture.Root; $o.daemonId='other-daemon'
                Write-CredPayLocalJson $o $p.Marker $fixture.Root
            }
            missing-config {
                Assert-CredPayLocalPath $fixture.Root $p.Kubeconfig
                Remove-Item -LiteralPath $p.Kubeconfig
            }
            bad-ready { $fixture.BadReady=$true }
            bad-alias { $fixture.BadAlias=$true }
            wrong-app-ids {
                $o=Read-CredPayLocalJson $p.Marker $fixture.Root; $o.appIds[0]='sha256:' + ('e' * 64)
                Write-CredPayLocalJson $o $p.Marker $fixture.Root
            }
            missing-original-config {
                $o=Read-CredPayLocalJson $p.Marker $fixture.Root; $o.kubeconfig='other'
                Write-CredPayLocalJson $o $p.Marker $fixture.Root
            }
        }
        MustReject { Invoke-CredPayLocalAction Resume $tag $fixture.Root $expected }
        if ((Read-CredPayLocalJson $p.Marker $fixture.Root).stage -cne 'Creating' -or
            $fixture.Events -contains 'load' -or $fixture.Events -contains 'create' -or $fixture.Events -contains 'delete') { throw 'Unproven session changed.' }
    }
}
Case 'owned sparse OCI cache loads only amd64 archive' {
    NewFixture
    Invoke-CredPayLocalAction Create $tag $fixture.Root 6>$null
    $fixture.SparseIndex=$true; $fixture.Events.Clear()
    Invoke-CredPayLocalAction LoadImages $tag $fixture.Root 6>$null
    if (($fixture.Events -join ',') -cne 'save,load' -or
        (Read-CredPayLocalJson $fixture.Plan.Marker $fixture.Root).stage -cne 'Owned') { throw 'Image load changed lifecycle or skipped platform export.' }
}
Case 'failed platform export never imports or changes owner' {
    NewFixture; Invoke-CredPayLocalAction Create $tag $fixture.Root 6>$null
    $fixture.FailSave=$true; $fixture.Events.Clear()
    MustReject { Invoke-CredPayLocalAction LoadImages $tag $fixture.Root }
    if (($fixture.Events -join ',') -cne 'save' -or
        (Read-CredPayLocalJson $fixture.Plan.Marker $fixture.Root).stage -cne 'Owned') { throw 'Import ran after export failure.' }
}
foreach ($fault in @('creating','replaced-node','validated')) {
    Case "LoadImages refuses $fault without exporting or importing" {
        if ($fault -ceq 'creating') { NewInterruptedFixture }
        else { NewFixture; Invoke-CredPayLocalAction Create $tag $fixture.Root 6>$null }
        if ($fault -ceq 'replaced-node') { $fixture.BadId=$true }
        if ($fault -ceq 'validated') { Invoke-CredPayLocalAction Validate $tag $fixture.Root 6>$null }
        $fixture.Events.Clear()
        MustReject { Invoke-CredPayLocalAction LoadImages $tag $fixture.Root }
        if ($fixture.Events.Count -ne 0) { throw 'Rejected LoadImages mutated the runtime.' }
    }
}
Case 'runtime infrastructure ID mismatch preserves Owned and refuses success' {
    NewFixture; Invoke-CredPayLocalAction Create $tag $fixture.Root 6>$null
    $fixture.BadInfraCache=$true; $fixture.Events.Clear()
    MustReject { Invoke-CredPayLocalAction LoadImages $tag $fixture.Root }
    if (($fixture.Events -join ',') -cne 'save,load' -or
        (Read-CredPayLocalJson $fixture.Plan.Marker $fixture.Root).stage -cne 'Owned') { throw 'Runtime identity failure changed ownership.' }
}
Write-Host "Local lifecycle: $caseCount offline scenarios passed; commands fictitious, no daemon accessed."
