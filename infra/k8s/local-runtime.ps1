#requires -Version 7.3
. (Join-Path $PSScriptRoot 'local-safety.ps1')

function Read-CredPayLocalJson([string]$Path, [string]$Root) {
    Assert-CredPayLocalPath $Root $Path
    Assert-LocalCondition ((Get-Item -LiteralPath $Path).Length -le 131072) 'Arquivo local excede o limite.'
    try { return [IO.File]::ReadAllText($Path, [Text.UTF8Encoding]::new($false, $true)) | ConvertFrom-Json -Depth 50 }
    catch { throw 'Registro local ilegível; conteúdo omitido.' }
}
function Write-CredPayLocalJson($Value, [string]$Path, [string]$Root, [switch]$New) {
    Assert-CredPayLocalPath $Root $Path
    $text = $Value | ConvertTo-Json -Depth 20
    if ($New) {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        try { $bytes=[Text.Encoding]::UTF8.GetBytes($text); $stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
    } else {
        $temporary = $Path + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
        [IO.File]::WriteAllText($temporary, $text, [Text.UTF8Encoding]::new($false))
        [IO.File]::Move($temporary, $Path, $true)
    }
}
function Get-CredPayLocalRuntime($Plan) {
    Assert-LocalCondition $IsWindows 'O perfil Local preparado exige Windows.'
    foreach ($tool in @(
        @{Path=$Plan.Kind;Hash='4b22adaa135368c5a465d56bbd8e520cbea87272a06ca00b6078e7b81515c9fc'},
        @{Path=$Plan.Kubectl;Hash='31947857be7583f3e4e6d889a3fb781ca813a45c20f11ccb3ef309714d01a9f3'})) {
        Assert-CredPayLocalPath $Plan.Root $tool.Path
        Assert-LocalCondition ((Get-FileHash -LiteralPath $tool.Path -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $tool.Hash) 'Ferramenta portátil diverge da baseline.'
    }
    $docker = (Get-Command docker.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $expected = Join-Path $env:ProgramFiles 'Docker/Docker/resources/bin/docker.exe'
    Assert-LocalCondition ([IO.Path]::GetFullPath($docker) -ieq [IO.Path]::GetFullPath($expected)) 'Docker CLI fora da instalação Desktop prevista.'
    $client = Join-Path $Plan.DockerConfig 'config.json'
    $config = Read-CredPayLocalJson $client $Plan.Root
    Assert-CredPayLocalDockerConfig $config
    return @{Plan=$Plan;Docker=$docker;SystemDirectory=[Environment]::SystemDirectory;Pwsh=(Get-Process -Id $PID).Path}
}
function Invoke-CredPayLocalTool($Runtime, [string]$File, [string[]]$Arguments, [int]$Timeout = 30) {
    $start=New-CredPayLocalProcess $File $Arguments $Runtime.Plan $Runtime.Docker $Runtime.SystemDirectory
    return Invoke-CredPayLocalProcess $start $Timeout
}
function Get-CredPayLocalDaemon($Runtime) {
    $format='{"os":{{json .OSType}},"arch":{{json .Architecture}},"driver":{{json .Driver}},"cpus":{{.NCPU}},"memory":{{.MemTotal}},"id":{{json .ID}},"security":{{json .SecurityOptions}}}'
    $data=Invoke-CredPayLocalTool $Runtime $Runtime.Docker @('info','--format',$format) | ConvertFrom-Json
    Assert-CredPayLocalDaemon $data
    return $data
}
function Get-CredPayLocalNode($Runtime) {
    $format='{"id":{{json .Id}},"cluster":{{json (index .Config.Labels "io.x-k8s.kind.cluster")}},"role":{{json (index .Config.Labels "io.x-k8s.kind.role")}},"image":{{json .Config.Image}},"privileged":{{json .HostConfig.Privileged}},"userns":{{json .HostConfig.UsernsMode}},"cgroupns":{{json .HostConfig.CgroupnsMode}},"devices":{{json .HostConfig.Devices}},"security":{{json .HostConfig.SecurityOpt}},"env":{{json .Config.Env}},"tmpfs":{{json .HostConfig.Tmpfs}},"mounts":{{json .Mounts}},"ports":{{json .HostConfig.PortBindings}}}'
    return (Invoke-CredPayLocalTool $Runtime $Runtime.Docker @('inspect','--format',$format,"$($Runtime.Plan.Cluster)-control-plane") | ConvertFrom-Json -Depth 30)
}
function Assert-CredPayLocalLive($Runtime, $Owner) {
    $plan=$Runtime.Plan
    $names=Invoke-CredPayLocalTool $Runtime $plan.Kind @('get','nodes','--name',$plan.Cluster)
    Assert-LocalCondition ($names -ceq "$($plan.Cluster)-control-plane") 'Conjunto de nodes divergente; operação recusada.'
    $daemon=Get-CredPayLocalDaemon $Runtime
    $node=Get-CredPayLocalNode $Runtime
    Assert-CredPayLocalOwner $Owner $plan $daemon $node
    Assert-CredPayLocalPath $plan.Root $plan.Kubeconfig
    $config=Invoke-CredPayLocalTool $Runtime $plan.Kubectl @('--kubeconfig',$plan.Kubeconfig,'config','view','--minify','-o','json') | ConvertFrom-Json -Depth 30
    Assert-CredPayLocalKubeconfig $config $plan ([int]$node.ports.'6443/tcp'[0].HostPort)
}
function Get-CredPayKubernetesExecution([string]$Root, [string]$Cluster, [string]$ExecutionProfile = 'CI') {
    if ($ExecutionProfile -ceq 'CI') {
        Assert-LocalCondition ($Cluster -cmatch '^credpay-ci-[0-9]+-[0-9]+$' -and $env:GITHUB_ACTIONS -ceq 'true') 'Perfil CI exige GitHub Actions e nome exclusivo CI.'
        return @{Kubeconfig=(Join-Path $Root ".local/k8s/$Cluster.yaml");Kubectl=(Join-Path $Root '.local/bin/kubectl');Docker='docker';Marker=(Join-Path $Root ".local/k8s/$Cluster.created")}
    }
    Assert-LocalCondition ($ExecutionProfile -ceq 'Local') 'Perfil Kubernetes desconhecido.'
    $plan=New-CredPayLocalPlan $Root $Cluster $env:CREDPAY_IMAGE_TAG
    Assert-CredPayLocalEnvironment ([Environment]::GetEnvironmentVariables('Process')) $plan
    $owner=Read-CredPayLocalJson $plan.Marker $Root
    Assert-LocalCondition ($owner.stage -cin @('Owned','Validated')) 'Criação local incompleta; não adotar ou remover por nome.'
    $runtime=Get-CredPayLocalRuntime $plan
    Assert-CredPayLocalLive $runtime $owner
    return @{Kubeconfig=$plan.Kubeconfig;Kubectl=$plan.Kubectl;Docker=$runtime.Docker;Marker=$plan.Marker;Runtime=$runtime;Owner=$owner}
}
