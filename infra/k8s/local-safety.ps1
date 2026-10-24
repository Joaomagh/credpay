#requires -Version 7.3
# Pure guards: no native commands or network access in this file.
function New-CredPayLocalPlan([string]$Root, [string]$Cluster, [string]$ImageTag) {
    Assert-LocalCondition ($Cluster -cmatch '^credpay-local-[0-9]{8}-[0-9a-f]{8}$') 'Nome local inválido; nomes CI não são aceitos.'
    Assert-LocalCondition ($ImageTag -cmatch '^[0-9a-f]{40}$') 'Tag local deve identificar uma revisão completa.'
    $resolved = [IO.Path]::GetFullPath($Root)
    Assert-LocalCondition ([IO.Directory]::Exists($resolved)) 'Workspace local ausente.'
    $directory = Join-Path $resolved ".local/k8s/$Cluster"
    return @{
        Cluster=$Cluster; ImageTag=$ImageTag; Root=$resolved; Directory=$directory
        DockerHost='npipe:////./pipe/dockerDesktopLinuxEngine'
        NodeImage='kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed'
        Kubeconfig=(Join-Path $directory 'kubeconfig.yaml'); Marker=(Join-Path $directory 'owner.json')
        DockerConfig=(Join-Path $directory 'docker'); Temp=(Join-Path $directory 'temp')
        Kind=(Join-Path $resolved '.local/k8s-tools/kind.exe')
        Kubectl=(Join-Path $resolved '.local/k8s-tools/kubectl.exe')
    }
}
function Assert-LocalCondition([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Assert-CredPayLocalDaemon($Daemon) {
    Assert-LocalCondition ($Daemon.os -ceq 'linux' -and $Daemon.arch -cin @('amd64','x86_64')) 'Daemon deve ser Linux amd64.'
    Assert-LocalCondition ($Daemon.driver -cin @('overlayfs','overlay2')) 'Driver fora da baseline local; requer revisão.'
    Assert-LocalCondition ($Daemon.id -cmatch '^[a-zA-Z0-9:-]{8,100}$') 'Identidade do daemon ausente.'
    Assert-LocalCondition ([int]$Daemon.cpus -ge 2 -and [long]$Daemon.memory -ge 4GB) 'Recursos abaixo do mínimo de preflight; não é dimensionamento de produção.'
    foreach ($option in @($Daemon.security)) {
        Assert-LocalCondition ($option -cnotmatch 'userns|rootless') 'Runtime com remapeamento/rootless exige outra análise.'
    }
}
function Assert-CredPayLocalNode($Node, $Plan, [string]$ExpectedId = '') {
    Assert-LocalCondition ($Node.id -cmatch '^[0-9a-f]{64}$') 'Identidade de container do node inválida.'
    if ($ExpectedId -ne '') { Assert-LocalCondition ($Node.id -ceq $ExpectedId) 'Node substituído; operação recusada.' }
    Assert-LocalCondition ($Node.cluster -ceq $Plan.Cluster -and $Node.role -ceq 'control-plane') 'Node não pertence ao cluster próprio.'
    Assert-LocalCondition ($Node.image -ceq $Plan.NodeImage -and $Node.privileged -eq $true) 'Node diverge da baseline privilegiada autorizada.'
    Assert-LocalCondition ($Node.userns -ceq '' -and $Node.cgroupns -ceq 'private') 'Namespaces do node divergentes.'
    Assert-LocalCondition (@($Node.devices).Where({$null -ne $_}).Count -eq 0) 'Dispositivo adicional no node.'
    # Docker may append label=disable when normalizing a privileged container.
    # Accept only these complete lists; keep rejecting unknown/duplicate options.
    $security = @($Node.security | Sort-Object) -join ','
    Assert-LocalCondition ($security -cin @('apparmor=unconfined,seccomp=unconfined',
        'apparmor=unconfined,label=disable,seccomp=unconfined')) 'Security options do node divergentes.'
    foreach ($entry in @($Node.env)) {
        Assert-LocalCondition ($entry -notmatch '^(?i:HTTP_PROXY|HTTPS_PROXY|ALL_PROXY|NO_PROXY|KIND_EXPERIMENTAL_CONTAINERD_SNAPSHOTTER)=.+') 'Proxy/experimental encaminhado ao node; conteúdo omitido.'
    }
    Assert-LocalCondition ((@($Node.tmpfs.PSObject.Properties.Name | Sort-Object) -join ',') -ceq '/run,/tmp') 'Tmpfs adicionais ou ausentes.'
    Assert-LocalCondition (@($Node.mounts).Count -eq 2) 'Mounts do node divergentes.'
    $volume = @($Node.mounts | Where-Object Destination -CEQ '/var')
    $modules = @($Node.mounts | Where-Object Destination -CEQ '/lib/modules')
    Assert-LocalCondition ($volume.Count -eq 1 -and $volume[0].Type -ceq 'volume' -and $volume[0].RW -eq $true -and $volume[0].Name -ne '') 'Volume interno do node divergente.'
    Assert-LocalCondition ($modules.Count -eq 1 -and $modules[0].Type -ceq 'bind' -and $modules[0].Source -ceq '/lib/modules' -and $modules[0].RW -eq $false) 'Mount de módulos divergente.'
    Assert-LocalCondition ((@($Node.ports.PSObject.Properties.Name) -join ',') -ceq '6443/tcp') 'Porta extra ou API ausente.'
    $ports = @($Node.ports.'6443/tcp')
    Assert-LocalCondition ($ports.Count -eq 1 -and $ports[0].HostIp -ceq '127.0.0.1' -and
        $ports[0].HostPort -cmatch '^[0-9]{1,5}$' -and [int]$ports[0].HostPort -ge 1 -and [int]$ports[0].HostPort -le 65535) 'API deve estar somente no loopback.'
}
function Assert-CredPayLocalKubeconfig($Config, $Plan, [int]$Port) {
    $expected = "kind-$($Plan.Cluster)"
    Assert-LocalCondition ($Config.'current-context' -ceq $expected -and @($Config.contexts).Count -eq 1 -and
        @($Config.clusters).Count -eq 1 -and @($Config.users).Count -eq 1) 'Kubeconfig deve conter somente o cluster próprio.'
    Assert-LocalCondition ($Config.contexts[0].name -ceq $expected -and $Config.contexts[0].context.cluster -ceq $expected -and
        $Config.contexts[0].context.user -ceq $expected -and $Config.clusters[0].name -ceq $expected -and $Config.users[0].name -ceq $expected) 'Identidades do kubeconfig divergentes.'
    $cluster = $Config.clusters[0].cluster
    Assert-LocalCondition ($cluster.server -ceq "https://127.0.0.1:$Port" -and $cluster.'certificate-authority-data') 'Servidor/CA do kubeconfig divergentes.'
    foreach ($key in @($cluster.PSObject.Properties.Name)) {
        Assert-LocalCondition ($key -cin @('server','certificate-authority-data')) 'Opção extra no cluster do kubeconfig.'
    }
    $user = $Config.users[0].user
    Assert-LocalCondition ($user.'client-certificate-data' -and $user.'client-key-data') 'Credenciais próprias embutidas ausentes.'
    foreach ($key in @($user.PSObject.Properties.Name)) {
        Assert-LocalCondition ($key -cin @('client-certificate-data','client-key-data')) 'Plugin/arquivo/credencial adicional no kubeconfig.'
    }
}
function Assert-CredPayLocalPath([string]$Root, [string]$Path) {
    $base = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $full = [IO.Path]::GetFullPath($Path)
    $comparison = if ($IsWindows) { [StringComparison]::OrdinalIgnoreCase } else { [StringComparison]::Ordinal }
    Assert-LocalCondition ($full.StartsWith($base + [IO.Path]::DirectorySeparatorChar, $comparison)) 'Caminho fora do workspace.'
    $relative = $full.Substring($base.Length + 1)
    $current = $base
    foreach ($segment in $relative.Split([IO.Path]::DirectorySeparatorChar)) {
        $current = Join-Path $current $segment
        $item = Get-Item -LiteralPath $current -Force -ErrorAction SilentlyContinue
        if ($null -ne $item) {
            Assert-LocalCondition ($item.LinkType -notin @('SymbolicLink','Junction')) 'Caminho redirecionado; operação recusada.'
        }
    }
}
function Assert-CredPayLocalOwner($Owner, $Plan, $Daemon, $Node) {
    Assert-LocalCondition ($Owner.schema -eq 1 -and $Owner.stage -cin @('Owned','Validated') -and
        $Owner.cluster -ceq $Plan.Cluster -and $Owner.imageTag -ceq $Plan.ImageTag -and
        $Owner.kubeconfig -ceq $Plan.Kubeconfig -and $Owner.daemonId -ceq $Daemon.id -and
        $Owner.nodeId -cmatch '^[0-9a-f]{64}$') 'Marcador de propriedade incompleto ou divergente.'
    Assert-CredPayLocalDaemon $Daemon
    Assert-CredPayLocalNode $Node $Plan $Owner.nodeId
}
function New-CredPayLocalProcess([string]$File, [string[]]$Arguments, $Plan, [string]$DockerExe, [string]$SystemDirectory) {
    Assert-CredPayLocalPath $Plan.Root $Plan.Directory
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $File
    $start.WorkingDirectory = $Plan.Root
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    if ($IsWindows) { $start.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden }
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $start.ArgumentList.Add($argument) }
    # Deliberately do not inherit proxies, Docker credentials/config, TLS or experimental options.
    $start.Environment.Clear()
    $start.Environment['SystemRoot'] = [IO.Directory]::GetParent($SystemDirectory).FullName
    $start.Environment['WINDIR'] = $start.Environment['SystemRoot']
    if ($IsWindows) { $start.Environment['ProgramFiles'] = [Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles) }
    $start.Environment['PATH'] = @((Split-Path $DockerExe -Parent), (Split-Path $Plan.Kind -Parent), $SystemDirectory) -join [IO.Path]::PathSeparator
    foreach ($key in @('TEMP','TMP','TMPDIR')) { $start.Environment[$key] = $Plan.Temp }
    $start.Environment['DOCKER_HOST'] = $Plan.DockerHost
    $start.Environment['DOCKER_CONFIG'] = $Plan.DockerConfig
    $start.Environment['KUBECONFIG'] = $Plan.Kubeconfig
    $start.Environment['KIND_EXPERIMENTAL_PROVIDER'] = 'docker'
    $start.Environment['CREDPAY_IMAGE_TAG'] = $Plan.ImageTag
    return $start
}
function Assert-CredPayLocalEnvironment($EnvironmentMap, $Plan) {
    foreach ($entry in @{
        DOCKER_HOST=$Plan.DockerHost;DOCKER_CONFIG=$Plan.DockerConfig;KUBECONFIG=$Plan.Kubeconfig
        KIND_EXPERIMENTAL_PROVIDER='docker';CREDPAY_IMAGE_TAG=$Plan.ImageTag
    }.GetEnumerator()) {
        Assert-LocalCondition ($EnvironmentMap[$entry.Key] -ceq $entry.Value) 'Perfil Local exige o ambiente isolado do launcher.'
    }
    foreach ($key in @($EnvironmentMap.Keys)) {
        $name = ([string]$key).ToUpperInvariant()
        Assert-LocalCondition ($name -notin @('HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','NO_PROXY')) 'Proxy herdado; executar somente pelo launcher local.'
        if ($name.StartsWith('DOCKER_')) { Assert-LocalCondition ($name -cin @('DOCKER_HOST','DOCKER_CONFIG')) 'Configuração Docker herdada não autorizada.' }
        if ($name.StartsWith('KIND_')) { Assert-LocalCondition ($name -ceq 'KIND_EXPERIMENTAL_PROVIDER') 'Opção kind herdada não autorizada.' }
    }
}
function Assert-CredPayLocalDockerConfig($Config) {
    Assert-LocalCondition ($null -ne $Config -and $Config -is [pscustomobject] -and @($Config.PSObject.Properties).Count -eq 0) 'Configuração Docker deve estar vazia e própria.'
}
function Invoke-CredPayLocalProcess($StartInfo, [int]$TimeoutSeconds = 30) {
    Assert-LocalCondition ($TimeoutSeconds -ge 1 -and $TimeoutSeconds -le 1200) 'Timeout local fora do limite.'
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $StartInfo
    $started = $false
    try {
        try { $started = $process.Start() } catch { throw 'Ferramenta local não iniciou; detalhes omitidos.' }
        Assert-LocalCondition $started 'Ferramenta local não iniciou.'
        $output = $process.StandardOutput.ReadToEndAsync()
        $errors = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $process.Kill($true)
            $null = $process.WaitForExit(10000)
            throw 'Ferramenta local excedeu o prazo; somente o processo filho foi encerrado.'
        }
        $text = $output.GetAwaiter().GetResult()
        $null = $errors.GetAwaiter().GetResult()
        Assert-LocalCondition ($process.ExitCode -eq 0) "Ferramenta local falhou, exit=$($process.ExitCode); saída omitida."
        return $text.Trim()
    } finally {
        if ($started -and -not $process.HasExited) { $process.Kill($true); $null = $process.WaitForExit(10000) }
        $process.Dispose()
    }
}
