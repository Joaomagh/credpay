#requires -Version 7.3
# Offline: exercise actual functions; replace only the kubectl boundary.
$ErrorActionPreference = 'Stop'
$callerNativeExit = $global:LASTEXITCODE
. (Join-Path $PSScriptRoot 'disposable-secret.ps1')
$script:failures = @()
$script:checks = 0
function Check([string]$Label, [scriptblock]$Action) {
    $script:checks++
    try { $null = & $Action } catch { $script:failures += $Label }
}
function Reject([scriptblock]$Action) {
    $thrown=$false
    try { $null = & $Action } catch { $thrown=$true }
    Require $thrown 'Expected refusal.'
}
function Require([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function WriteStateDiagnostics { }
function CopyFixture($Value) { $Value | ConvertTo-Json -Depth 20 | ConvertFrom-Json -Depth 20 }
$Cluster = 'credpay-ci-123-1'
$kubeconfig = 'fixture-kubeconfig'
$kubectl = 'Invoke-FixtureKubectl'
function Invoke-FixtureKubectl {
    $script:cliCalls++
    $script:cliArgs = @($args)
    $script:cliInput = @($input)
    # Native status belongs to this fixture script, never to the workflow caller.
    $script:LASTEXITCODE = $script:cliExit
    if ($script:cliExit -ne 0) { return 'fixture output must never escape' }
    if ($args -ccontains 'get') { return $script:stored }
    if ($args -ccontains 'create') {
        if ($script:createConflict) { $script:LASTEXITCODE=1; return 'fixture conflict output must never escape' }
        $created = $script:cliInput[0] | ConvertFrom-Json -Depth 20
        $created.metadata | Add-Member NoteProperty uid '5f955e8e-9e0b-4f28-a2b9-87617a750423'
        $script:stored = $created | ConvertTo-Json -Depth 20 -Compress
        return $script:stored
    }
    return ''
}
# Extract only Kube: never execute startup, Docker, kubectl or a cluster.
$tokens = $null; $errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'verify-startup.ps1'), [ref]$tokens, [ref]$errors)
Require ($errors.Count -eq 0) 'Startup parser failed.'
$definition = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Kube' }, $true)
. ([scriptblock]::Create($definition.Extent.Text))
$fixture = [pscustomobject]@{
    apiVersion='v1'; kind='Secret'; type='Opaque'; immutable=$true
    metadata=@{name='credpay-demo';namespace='credpay';labels=@{'app.kubernetes.io/managed-by'='credpay-disposable';'credpay.test/cluster'=$Cluster}}
    data=@{}
}
foreach ($pair in @{ 'tx-user'='credpay_tx'; 'proc-user'='credpay_proc'; 'rabbit-user'='credpay_demo'; 'tx-password'=('a'*64); 'proc-password'=('b'*64); 'rabbit-password'=('c'*64) }.GetEnumerator()) {
    $fixture.data[$pair.Key] = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair.Value))
}
$json = $fixture | ConvertTo-Json -Depth 20 -Compress
function ResetFixture { $script:cliCalls=0; $script:cliExit=0; $script:createConflict=$false; $script:cliArgs=@(); $script:cliInput=@(); $script:stored='' }
Check 'create receives the complete Secret through stdin' {
    ResetFixture
    $null = $json | Kube create -f '-'
    Require ($script:cliCalls -eq 1 -and $script:cliInput.Count -eq 1 -and $script:cliInput[0] -ceq $json) 'Secret stdin lost.'
    Require (($script:cliArgs -join ' ') -notmatch 'password|aaaaaaaa') 'Secret leaked in arguments.'
}
Check 'existing valid immutable Secret is reused without mutation' {
    ResetFixture
    $existing = CopyFixture $fixture
    $existing.metadata | Add-Member NoteProperty uid 'a2d4d28b-73ea-4683-a1e5-cb2a478a26d4'
    $script:stored = $existing | ConvertTo-Json -Depth 20 -Compress
    Ensure-CredPayKubernetesSecret $Cluster
    Require ($script:cliCalls -eq 1 -and $script:cliArgs -ccontains 'get') 'Existing Secret overwritten.'
}
Check 'absent Secret is created once then reused with unchanged credentials' {
    ResetFixture
    $uid = Ensure-CredPayKubernetesSecret $Cluster
    Require ($script:cliCalls -eq 2 -and $uid -ceq '5f955e8e-9e0b-4f28-a2b9-87617a750423') 'Creation not confirmed.'
    $original = $script:stored
    Require ((Ensure-CredPayKubernetesSecret $Cluster) -ceq $uid) 'Identity changed.'
    Require ($script:stored -ceq $original -and $script:cliCalls -eq 3) 'Credentials rotated on reuse.'
}
Check 'generator produces different valid credentials for independent clusters' {
    $first = New-CredPayKubernetesSecret $Cluster
    $second = New-CredPayKubernetesSecret 'credpay-local-20261105-a1b2c3d4'
    Assert-CredPayKubernetesSecret $first $Cluster
    Assert-CredPayKubernetesSecret $second 'credpay-local-20261105-a1b2c3d4'
    foreach ($key in @('tx-password','proc-password','rabbit-password')) {
        Require ($first.data.$key -cne $second.data.$key) 'Credential reused between clusters.'
    }
}
$mutations = @{
    namespace={param($s) $s.metadata.namespace='other'}
    name={param($s) $s.metadata.name='other'}
    cluster={param($s) $s.metadata.labels.'credpay.test/cluster'='credpay-ci-999-1'}
    owner={param($s) $s.metadata.labels.'app.kubernetes.io/managed-by'='other'}
    mutable={param($s) $s.immutable=$false}
    stringBoolean={param($s) $s.immutable='true'}
    kind={param($s) $s.kind='ConfigMap'}
    type={param($s) $s.type='kubernetes.io/basic-auth'}
    weak={param($s) $s.data.'tx-password'=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('public-fixture-only'))}
    duplicate={param($s) $s.data.'tx-password'=$s.data.'proc-password'}
    malformedBase64={param($s) $s.data.'tx-password'='?invalid'}
    whitespaceBase64={param($s) $s.data.'tx-password'=" $($s.data.'tx-password')"}
    wrongUser={param($s) $s.data.'tx-user'=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('other'))}
    extraField={param($s) $s.data | Add-Member NoteProperty extra 'Zml4dHVyZQ=='}
    missingField={param($s) $s.data.PSObject.Properties.Remove('tx-password')}
    stringData={param($s) $s | Add-Member NoteProperty stringData @{extra='fixture'}}
    annotation={param($s) $s.metadata | Add-Member NoteProperty annotations @{'kubectl.kubernetes.io/last-applied-configuration'='fixture'}}
}
foreach ($case in $mutations.Keys) {
    Check "unsafe stdin rejected before CLI: $case" {
        ResetFixture
        $bad = CopyFixture $fixture
        & $mutations[$case] $bad
        Reject { ($bad | ConvertTo-Json -Depth 20 -Compress) | Kube create -f '-' }
        Require ($script:cliCalls -eq 0) 'Unsafe input reached CLI.'
    }
    Check "divergent stored Secret preserved: $case" {
        ResetFixture
        $bad = CopyFixture $fixture
        $bad.metadata | Add-Member NoteProperty uid 'a2d4d28b-73ea-4683-a1e5-cb2a478a26d4'
        & $mutations[$case] $bad
        $script:stored = $bad | ConvertTo-Json -Depth 20 -Compress
        $before = $script:stored
        Reject { Ensure-CredPayKubernetesSecret $Cluster }
        Require ($script:cliCalls -eq 1 -and $script:cliArgs -ccontains 'get' -and $script:stored -ceq $before) 'Divergent Secret mutated.'
    }
}
Check 'stored Secret without server identity is refused' {
    ResetFixture; $script:stored=$json
    Reject { Ensure-CredPayKubernetesSecret $Cluster }
    Require ($script:cliCalls -eq 1) 'Mutation after missing UID.'
}
Check 'create conflict stops without adoption or raw output' {
    ResetFixture; $script:createConflict=$true
    $message=''
    try { $null = Ensure-CredPayKubernetesSecret $Cluster } catch { $message=$_.Exception.Message }
    Require ($message.Contains('exit=1') -and -not $message.Contains('fixture output')) 'Failure hidden or output leaked.'
    Require (-not $message.Contains('fixture conflict') -and $script:cliCalls -eq 2 -and $script:stored -ceq '') 'Conflict retried/adopted or output leaked.'
}
foreach ($command in @(@('apply','-f','-'),@('create','-f','-','--dry-run=client'),@('get','secret'))) {
    Check "stdin disallowed for $($command[0])" {
        ResetFixture
        Reject { $json | Kube @command }
        Require ($script:cliCalls -eq 0) 'Unsupported stdin reached CLI.'
    }
}
Check 'multiple stdin objects refused before CLI' {
    ResetFixture; Reject { @($json,$json) | Kube create -f '-' }
    Require ($script:cliCalls -eq 0) 'Multiple documents reached CLI.'
}
Check 'malformed JSON refused before CLI' {
    ResetFixture; Reject { '{invalid' | Kube create -f '-' }
    Require ($script:cliCalls -eq 0) 'Malformed JSON reached CLI.'
}
Check 'stdin create without payload refused before CLI' {
    ResetFixture; Reject { Kube create -f '-' }
    Require ($script:cliCalls -eq 0) 'Empty payload reached CLI.'
}
foreach ($badCluster in @('production','credpay-ci-../../host','credpay-local-20261105-INVALID')) {
    Check 'non-disposable cluster refused before CLI' {
        ResetFixture; Reject { Ensure-CredPayKubernetesSecret $badCluster }
        Require ($script:cliCalls -eq 0) 'Other cluster reached CLI.'
    }
}
Check 'fixture preserves native exit status of its caller' {
    Require ($global:LASTEXITCODE -ceq $callerNativeExit) 'Fixture changed caller exit status.'
}
if ($script:failures.Count -gt 0) { throw "Secret controls failed ($($script:failures.Count)): $($script:failures -join '; ')" }
Write-Host "Kubernetes Secret: $script:checks offline controls passed; no secret values emitted."
