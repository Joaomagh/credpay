# Definitions only. Real Docker calls are performed exclusively by the user or hosted CI.
. (Join-Path $PSScriptRoot '../k8s/local-runtime.ps1')
function Assert-CredPayDiagnosticInventory($Plan,$Inventory) {
    $services=@('transacoes','processamento','db-transacoes','db-processamento','rabbit')
    $volumes=@('transacoes-data','processamento-data','rabbit-data' | ForEach-Object { "$($Plan.Project)_$_" })
    $all=@($Inventory.Containers)+@($Inventory.Volumes)+@($Inventory.Networks)
    Assert-LocalCondition ($Inventory.Containers.Count -le 5 -and $Inventory.Volumes.Count -le 3 -and $Inventory.Networks.Count -le 1) 'Recursos extras no projeto; operação recusada.'
    Assert-LocalCondition (@($all.Name | Sort-Object -Unique).Count -eq $all.Count) 'Recursos duplicados.'
    foreach($item in $all) {
        Assert-LocalCondition ($item.Project -ceq $Plan.Project -and $item.Nonce -ceq $Plan.Nonce) 'Recurso sem propriedade da sessão; não adotar por nome.'
    }
    foreach($c in @($Inventory.Containers)) {
        Assert-LocalCondition ($c.Service -cin $services -and $c.Name -ceq "$($Plan.Project)-$($c.Service)-1" -and $c.Id -cmatch '^[0-9a-f]{64}$') 'Container divergente.'
        Assert-LocalCondition ($c.Privileged -eq $false -and @($c.Devices).Count -eq 0 -and
            @($c.Networks).Count -eq 1 -and $c.Networks[0] -ceq "$($Plan.Project)_default") 'Privilégios/rede/dispositivos divergentes.'
        if($c.Service -cin @('transacoes','processamento')) {
            $port=if($c.Service -ceq 'transacoes') { $Plan.TxPort } else { $Plan.ProcPort }
            Assert-LocalCondition ($c.User -ceq '10001:10001' -and @($c.Mounts).Count -eq 0 -and
                @($c.Ports).Count -eq 1 -and $c.Ports[0].Ip -ceq '127.0.0.1' -and [int]$c.Ports[0].Port -eq $port -and
                @($c.Caps).Count -eq 1 -and $c.Caps[0] -ceq 'ALL' -and @($c.Security).Count -eq 1 -and
                $c.Security[0] -ceq 'no-new-privileges:true') 'Apps exigem UID10001, mounts0, loopback e capabilities removidas.'
        } else {
            $volume=if($c.Service -ceq 'rabbit') { "$($Plan.Project)_rabbit-data" } elseif($c.Service -ceq 'db-transacoes') { "$($Plan.Project)_transacoes-data" } else { "$($Plan.Project)_processamento-data" }
            $destination=if($c.Service -ceq 'rabbit') { '/var/lib/rabbitmq' } else { '/var/lib/postgresql/data' }
            Assert-LocalCondition (@($c.Ports).Count -eq 0 -and @($c.Mounts).Count -eq 1 -and
                $c.Mounts[0].Type -ceq 'volume' -and $c.Mounts[0].Name -ceq $volume -and
                $c.Mounts[0].Destination -ceq $destination) 'Infraestrutura com porta/mount adicional.'
        }
    }
    foreach($v in @($Inventory.Volumes)) {
        Assert-LocalCondition ($v.Name -cin $volumes -and $v.Driver -ceq 'local' -and
            $v.Created -cmatch '^created:[0-9]{4}-[0-9TZ:.+-]+$' -and
            ($null -eq $v.Options -or (ConvertTo-Json $v.Options -Compress) -ceq '{}')) 'Volume externo/opção de host divergente.'
    }
    foreach($n in @($Inventory.Networks)) {
        Assert-LocalCondition ($n.Name -ceq "$($Plan.Project)_default" -and $n.Id -cmatch '^[0-9a-f]{64}$' -and
            $n.Driver -ceq 'bridge' -and $n.Internal -eq $true) 'Rede própria interna ausente/divergente.'
        foreach($id in @($n.Containers)) { Assert-LocalCondition ($id -cin @($Inventory.Containers.Id)) 'Rede possui container externo; remoção recusada.' }
    }
}
function Get-CredPayDiagnosticIdentity($Inventory) {
    $keys=@()
    foreach($c in @($Inventory.Containers)) { $keys+="container:$($c.Name):$($c.Id)" }
    foreach($v in @($Inventory.Volumes)) { $keys+="volume:$($v.Name):$($v.Created)" }
    foreach($n in @($Inventory.Networks)) { $keys+="network:$($n.Name):$($n.Id)" }
    return (@($keys | Sort-Object) -join '|')
}
function Assert-CredPayDiagnosticRemoval($Expected,$Actual) {
    Assert-LocalCondition ((Get-CredPayDiagnosticIdentity $Expected) -ceq (Get-CredPayDiagnosticIdentity $Actual)) 'Recursos mudaram desde o registro; remoção recusada.'
}
function New-CredPayDiagnosticPlan([string]$Root,[string]$Project,[string]$Tag,[string]$Nonce,[int]$TxPort,[int]$ProcPort) {
    Assert-LocalCondition ($Project -cmatch '^credpay-diag-[0-9]{8}-[0-9a-f]{8}$' -and $Nonce -cmatch '^[0-9a-f]{32}$' -and
        $Tag -cmatch '^[0-9a-f]{40}$' -and $TxPort -ge 1024 -and $TxPort -le 65535 -and $ProcPort -ge 1024 -and $ProcPort -le 65535 -and $TxPort -ne $ProcPort) 'Plano diagnóstico inválido.'
    $directory=Join-Path $Root ".local/compose-diagnostics/$Project"
    foreach($path in @($directory,(Join-Path $directory 'temp'),(Join-Path $directory 'docker'),(Join-Path $directory 'override.json'))) { Assert-CredPayLocalPath $Root $path }
    return @{Root=$Root;Project=$Project;Tag=$Tag;Nonce=$Nonce;TxPort=$TxPort;ProcPort=$ProcPort;Directory=$directory;
        Temp=(Join-Path $directory 'temp');DockerConfig=(Join-Path $directory 'docker');Marker=(Join-Path $directory 'owner.json');Overlay=(Join-Path $directory 'override.json')}
}
function Get-CredPayDiagnosticOverride($Plan) {
    $labels=@{'io.credpay.diagnostics.session'=$Plan.Nonce}
    $services=@{}
    foreach($service in @('transacoes','processamento','db-transacoes','db-processamento','rabbit')) { $services[$service]=@{labels=$labels} }
    $volumes=@{}
    foreach($volume in @('transacoes-data','processamento-data','rabbit-data')) { $volumes[$volume]=@{labels=$labels} }
    return @{services=$services;volumes=$volumes;networks=@{default=@{internal=$true;labels=$labels}}}
}
function Get-CredPayDiagnosticRuntime($Plan) {
    if($IsWindows) {
        $docker=(Get-Command docker.exe -CommandType Application -ErrorAction Stop).Source
        $expected=Join-Path ([Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles)) 'Docker/Docker/resources/bin/docker.exe'
        Assert-LocalCondition ([IO.Path]::GetFullPath($docker) -ieq [IO.Path]::GetFullPath($expected)) 'Docker CLI fora da instalação Desktop prevista.'
        $compose=Join-Path (Split-Path $docker -Parent) 'docker-compose.exe'
        Assert-LocalCondition (Test-Path -LiteralPath $compose -PathType Leaf) 'Compose Desktop instalado não encontrado; nenhuma instalação automática.'
        $hostAddress='npipe:////./pipe/dockerDesktopLinuxEngine'
    } else {
        Assert-LocalCondition ($env:GITHUB_ACTIONS -ceq 'true' -and $env:RUNNER_OS -ceq 'Linux') 'Execução Linux deste launcher permitida somente no CI hospedado.'
        $docker=(Get-Command docker -CommandType Application -ErrorAction Stop).Source
        $hostAddress='unix:///var/run/docker.sock'
    }
    $config=Read-CredPayLocalJson (Join-Path $Plan.DockerConfig 'config.json') $Plan.Root
    Assert-CredPayLocalDockerConfig $config
    return @{Plan=$Plan;Docker=$docker;Compose=$compose;HostAddress=$hostAddress;Pwsh=(Get-Process -Id $PID).Path}
}
function New-CredPayDiagnosticProcess($Runtime,[string]$File,[string[]]$Arguments) {
    $p=$Runtime.Plan
    foreach($path in @($p.Directory,$p.Temp,$p.DockerConfig)) { Assert-CredPayLocalPath $p.Root $path }
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName=$File; $start.WorkingDirectory=$p.Root; $start.UseShellExecute=$false; $start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
    foreach($arg in $Arguments) { $start.ArgumentList.Add($arg) }
    $start.Environment.Clear()
    $systemDirectory=if($IsWindows) { [Environment]::SystemDirectory } else { '/usr/bin' }
    $start.Environment['PATH']=@((Split-Path $Runtime.Docker -Parent),$systemDirectory) -join [IO.Path]::PathSeparator
    $start.Environment['HOME']=$p.Temp
    if($IsWindows) {
        $start.WindowStyle=[Diagnostics.ProcessWindowStyle]::Hidden
        $start.Environment['SystemRoot']=[IO.Directory]::GetParent($systemDirectory).FullName
        $start.Environment['WINDIR']=$start.Environment['SystemRoot']
        $start.Environment['USERPROFILE']=$p.Temp; $start.Environment['PATHEXT']='.EXE'
        $start.Environment['ProgramFiles']=[Environment]::GetFolderPath([Environment+SpecialFolder]::ProgramFiles)
    } else { $start.Environment['GITHUB_ACTIONS']='true'; $start.Environment['RUNNER_OS']='Linux' }
    foreach($key in @('TEMP','TMP','TMPDIR')) { $start.Environment[$key]=$p.Temp }
    $start.Environment['DOCKER_HOST']=$Runtime.HostAddress; $start.Environment['DOCKER_CONFIG']=$p.DockerConfig
    $start.Environment['CREDPAY_IMAGE_TAG']=$p.Tag; $start.Environment['CREDPAY_DIAG_NONCE']=$p.Nonce
    $start.Environment['TX_HTTP_PORT']=[string]$p.TxPort; $start.Environment['PROC_HTTP_PORT']=[string]$p.ProcPort
    # All values are the same fictitious accounts as .env.example; never inherit real credentials.
    foreach($entry in @{TX_DB_USER='credpay_tx';TX_DB_PASSWORD='demo_tx_only';PROC_DB_USER='credpay_proc';PROC_DB_PASSWORD='demo_proc_only';RABBIT_USER='credpay_demo';RABBIT_PASSWORD='demo_rabbit_only';BRL_LIMIT='100.00'}.GetEnumerator()) { $start.Environment[$entry.Key]=$entry.Value }
    return $start
}
function Invoke-CredPayDiagnosticTool($Runtime,[string]$File,[string[]]$Arguments,[int]$Timeout=30) {
    return (Invoke-CredPayLocalProcess (New-CredPayDiagnosticProcess $Runtime $File $Arguments) $Timeout)
}
function Get-CredPayDiagnosticDaemon($Runtime) {
    $data=Invoke-CredPayDiagnosticTool $Runtime $Runtime.Docker @('info','--format','{"id":{{json .ID}},"os":{{json .OSType}},"arch":{{json .Architecture}},"driver":{{json .Driver}},"cpus":{{.NCPU}},"memory":{{.MemTotal}},"security":{{json .SecurityOptions}}}') | ConvertFrom-Json
    Assert-CredPayLocalDaemon $data
    return $data.id
}
function Get-CredPayDiagnosticInventory($Runtime) {
    $p=$Runtime.Plan; $result=@{Containers=@();Volumes=@();Networks=@()}
    foreach($kind in @('container','volume','network')) {
        $arguments=if($kind -ceq 'container') { @('ps','--all','--no-trunc','--quiet','--filter',"name=$($p.Project)") }
            elseif($kind -ceq 'volume') { @('volume','ls','--quiet','--filter',"name=$($p.Project)") }
            else { @('network','ls','--no-trunc','--quiet','--filter',"name=$($p.Project)") }
        $raw=Invoke-CredPayDiagnosticTool $Runtime $Runtime.Docker $arguments
        $names=if($raw) { @($raw -split '\r?\n') } else { @() }
        Assert-LocalCondition ($names.Count -le 10) 'Inventário inesperadamente grande; operação recusada.'
        foreach($name in $names) {
            Assert-LocalCondition ($name -cmatch '^[a-z0-9_-]{1,150}$') 'Identidade de recurso inválida.'
            $labels='"Project":{{json (index .Labels "com.docker.compose.project")}},"Nonce":{{json (index .Labels "io.credpay.diagnostics.session")}}'
            if($kind -ceq 'container') {
                $format='{"Id":{{json .Id}},"Name":{{json .Name}},"Service":{{json (index .Config.Labels "com.docker.compose.service")}},"Project":{{json (index .Config.Labels "com.docker.compose.project")}},"Nonce":{{json (index .Config.Labels "io.credpay.diagnostics.session")}},"Privileged":{{json .HostConfig.Privileged}},"User":{{json .Config.User}},"Mounts":{{json .Mounts}},"Bindings":{{json .HostConfig.PortBindings}},"Networks":{{json .NetworkSettings.Networks}},"Caps":{{json .HostConfig.CapDrop}},"Security":{{json .HostConfig.SecurityOpt}},"Devices":{{json .HostConfig.Devices}}}'
            } elseif($kind -ceq 'volume') { $format='{"Name":{{json .Name}},"Created":{{json .CreatedAt}},"Driver":{{json .Driver}},"Options":{{json .Options}},'+$labels+'}' }
            else { $format='{"Id":{{json .Id}},"Name":{{json .Name}},"Driver":{{json .Driver}},"Internal":{{json .Internal}},"Containers":{{json .Containers}},'+$labels+'}' }
            $json=Invoke-CredPayDiagnosticTool $Runtime $Runtime.Docker @($kind,'inspect','--format',$format,$name)
            $row=$json | ConvertFrom-Json -Depth 30
            if($kind -ceq 'container') {
                $row.Name=$row.Name.TrimStart('/')
                $row.Networks=@($row.Networks.PSObject.Properties.Name)
                $ports=@()
                foreach($binding in @($row.Bindings.PSObject.Properties | Where-Object { $null -ne $_ })) {
                    Assert-LocalCondition ($binding.Name -ceq '8080/tcp') 'Porta adicional no container.'
                    foreach($port in @($binding.Value)) { $ports+=@{Ip=$port.HostIp;Port=$port.HostPort} }
                }
                $row | Add-Member NoteProperty Ports $ports
                $row.Mounts=@($row.Mounts); $row.Caps=@($row.Caps | Where-Object { $null -ne $_ }); $row.Security=@($row.Security | Where-Object { $null -ne $_ }); $row.Devices=@($row.Devices | Where-Object { $null -ne $_ })
                $result.Containers+=$row
            } elseif($kind -ceq 'volume') {
                # Preserve the exact timestamp across PowerShell versions that auto-parse ISO dates.
                $document=[Text.Json.JsonDocument]::Parse([string]$json)
                try { $row.Created='created:'+$document.RootElement.GetProperty('Created').GetString() } finally { $document.Dispose() }
                $result.Volumes+=$row
            }
            else { $row.Containers=@($row.Containers.PSObject.Properties.Name | Where-Object { $null -ne $_ }); $result.Networks+=$row }
        }
    }
    Assert-CredPayDiagnosticInventory $p $result
    foreach($volume in $result.Volumes) {
        $users=Invoke-CredPayDiagnosticTool $Runtime $Runtime.Docker @('ps','--all','--no-trunc','--quiet','--filter',"volume=$($volume.Name)")
        if($users) { foreach($id in @($users -split '\r?\n')) { Assert-LocalCondition ($id -cin @($result.Containers.Id)) 'Volume usado por container externo; operação recusada.' } }
    }
    return $result
}
function Read-CredPayDiagnosticSession([string]$Root,[string]$Project) {
    Assert-LocalCondition ($Project -cmatch '^credpay-diag-[0-9]{8}-[0-9a-f]{8}$') 'Projeto diagnóstico inválido.'
    $record=Read-CredPayLocalJson (Join-Path $Root ".local/compose-diagnostics/$Project/owner.json") $Root
    $p=New-CredPayDiagnosticPlan $Root $Project $record.tag $record.nonce $record.txPort $record.procPort
    Assert-LocalCondition ($record.schema -eq 1 -and $record.project -ceq $Project -and $record.stage -cin @('Creating','Owned') -and
        $record.daemonId -cmatch '^[a-zA-Z0-9:-]{8,100}$' -and $record.overlayHash -cmatch '^[0-9a-f]{64}$') 'Registro diagnóstico incompleto.'
    Assert-LocalCondition ((Get-FileHash -LiteralPath $p.Overlay -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $record.overlayHash) 'Overlay alterado; operação recusada.'
    Assert-CredPayDiagnosticInputs $Root $record.inputs
    return @{Plan=$p;Owner=$record}
}
function Assert-CredPayDiagnosticScope($Runtime,$Owner) {
    Assert-CredPayDiagnosticInputs $Runtime.Plan.Root $Owner.inputs
    Assert-CredPayLocalPath $Runtime.Plan.Root $Runtime.Plan.Overlay
    Assert-LocalCondition ((Get-FileHash -LiteralPath $Runtime.Plan.Overlay -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $Owner.overlayHash) 'Overlay mudou desde a criação; operação recusada.'
    Assert-LocalCondition ((Get-CredPayDiagnosticDaemon $Runtime) -ceq $Owner.daemonId) 'Daemon diferente da criação.'
    return (Get-CredPayDiagnosticInventory $Runtime)
}
function Get-CredPayDiagnosticInputs([string]$Root) {
    $hashes=@{}
    foreach($name in @('compose.yaml','compose.diagnostics.yaml','.env.example')) {
        $path=Join-Path $Root "infra/compose/$name"
        Assert-CredPayLocalPath $Root $path
        Assert-LocalCondition ((Get-Item -LiteralPath $path).Length -le 1MB) 'Entrada Compose excede limite.'
        $hashes[$name]=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    return $hashes
}
function Assert-CredPayDiagnosticInputs([string]$Root,$Expected) {
    $actual=Get-CredPayDiagnosticInputs $Root
    Assert-LocalCondition ($null -ne $Expected) 'Hashes das entradas ausentes; operação recusada.'
    foreach($name in @('compose.yaml','compose.diagnostics.yaml','.env.example')) {
        Assert-LocalCondition ($Expected.$name -cmatch '^[0-9a-f]{64}$' -and $Expected.$name -ceq $actual[$name]) 'Entradas Compose mudaram desde a criação; não remover por nome.'
    }
}
function Get-CredPayDiagnosticChild([string]$Root,[string]$Project) {
    $session=Read-CredPayDiagnosticSession $Root $Project; $p=$session.Plan
    Assert-LocalCondition ($env:CREDPAY_DIAG_NONCE -ceq $p.Nonce -and $env:CREDPAY_IMAGE_TAG -ceq $p.Tag -and
        $env:DOCKER_CONFIG -ceq $p.DockerConfig -and $env:HOME -ceq $p.Temp -and
        $env:TX_HTTP_PORT -ceq [string]$p.TxPort -and $env:PROC_HTTP_PORT -ceq [string]$p.ProcPort) 'Diagnóstico exige ambiente isolado do launcher.'
    foreach($key in [Environment]::GetEnvironmentVariables('Process').Keys) {
        $name=([string]$key).ToUpperInvariant()
        Assert-LocalCondition ($name -notin @('HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','NO_PROXY') -and
            (-not $name.StartsWith('DOCKER_') -or $name -cin @('DOCKER_HOST','DOCKER_CONFIG'))) 'Ambiente/proxy herdado recusado.'
    }
    if($IsWindows) {
        Assert-LocalCondition ($env:USERPROFILE -ceq $p.Temp -and $env:PATHEXT -cin @('.EXE','.EXE;.CPL')) 'Ambiente executável/home divergente.'
        $env:PATHEXT='.EXE'
    }
    $runtime=Get-CredPayDiagnosticRuntime $p
    Assert-LocalCondition ($env:DOCKER_HOST -ceq $runtime.HostAddress) 'Endpoint Docker divergente.'
    $null=Assert-CredPayDiagnosticScope $runtime $session.Owner
    return @{Runtime=$runtime;Owner=$session.Owner}
}
function Get-CredPayDiagnosticImages($Runtime) {
    $references=@("credpay-transacoes:$($Runtime.Plan.Tag)","credpay-processamento:$($Runtime.Plan.Tag)",
        'postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0',
        'rabbitmq:4.3.5-management-alpine@sha256:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1')
    $ids=@()
    foreach($reference in $references) {
        $value=Invoke-CredPayDiagnosticTool $Runtime $Runtime.Docker @('image','inspect','--platform=linux/amd64','--format','{{.Id}}/{{.Os}}/{{.Architecture}}',$reference)
        Assert-LocalCondition ($value -cmatch '^sha256:[0-9a-f]{64}/linux/amd64$') 'Imagem amd64 ausente/divergente; construir/carregar antes, sem pull automático.'
        $ids+=$value
    }
    return $ids
}
function Get-CredPayDiagnosticPort {
    $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
    try { $listener.Start(); return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}
function Invoke-CredPayDiagnosticAction([string]$Action,[string]$Tag,[string]$Root,[int]$TxPort=0,[int]$ProcPort=0) {
    Assert-LocalCondition ($Action -cin @('Run','Remove')) 'Ação diagnóstica desconhecida.'
    $active=Join-Path $Root '.local/compose-diagnostics/active.json'
    Assert-CredPayLocalPath $Root $active
    if($Action -ceq 'Run') {
        Assert-LocalCondition (-not (Test-Path -LiteralPath $active)) 'Há ensaio diagnóstico registrado; não sobrescrever sessão.'
        $nonce=[Guid]::NewGuid().ToString('N')
        $project='credpay-diag-'+[DateTime]::UtcNow.ToString('yyyyMMdd')+'-'+$nonce.Substring(0,8)
        if($TxPort -eq 0) { $TxPort=Get-CredPayDiagnosticPort }
        if($ProcPort -eq 0) { do { $ProcPort=Get-CredPayDiagnosticPort } while($ProcPort -eq $TxPort) }
        $p=New-CredPayDiagnosticPlan $Root $project $Tag $nonce $TxPort $ProcPort
        Assert-LocalCondition (-not (Test-Path -LiteralPath $p.Directory)) 'Diretório diagnóstico já existe.'
        $null=New-Item -ItemType Directory -Path $p.Directory,$p.Temp,$p.DockerConfig
        Write-CredPayLocalJson ([pscustomobject]@{}) (Join-Path $p.DockerConfig 'config.json') $Root -New
        Write-CredPayLocalJson (Get-CredPayDiagnosticOverride $p) $p.Overlay $Root -New
        $runtime=Get-CredPayDiagnosticRuntime $p
        $daemon=Get-CredPayDiagnosticDaemon $runtime
        $images=@(Get-CredPayDiagnosticImages $runtime)
        $inventory=Get-CredPayDiagnosticInventory $runtime
        Assert-LocalCondition ((Get-CredPayDiagnosticIdentity $inventory) -ceq '') 'Projeto diagnóstico já possui recursos; não adotar.'
        $owner=@{schema=1;stage='Creating';project=$project;nonce=$nonce;tag=$Tag;txPort=$TxPort;procPort=$ProcPort;daemonId=$daemon;
            overlayHash=(Get-FileHash -LiteralPath $p.Overlay -Algorithm SHA256).Hash.ToLowerInvariant();inputs=(Get-CredPayDiagnosticInputs $Root);images=$images;inventory=$inventory;result='NotRun'}
        Write-CredPayLocalJson $owner $p.Marker $Root -New
        Write-CredPayLocalJson @{project=$project} $active $Root -New
        $success=$false; $phase='smoke'
        try {
            Write-Host 'Diagnóstico local: projeto exclusivo, dados fictícios e rede interna; iniciando preparo/falha/recuperação.'
            $output=Invoke-CredPayDiagnosticTool $runtime $runtime.Pwsh @('-NoLogo','-NoProfile','-File',(Join-Path $Root 'infra/compose/demo.ps1'),'-Action','Smoke','-Project',$project,'-EnvFile','.env.example','-Diagnostics','-FaultDiagnostics') 1200
            Write-Host $output
            $phase='images'
            Assert-LocalCondition ((@(Get-CredPayDiagnosticImages $runtime) -join '|') -ceq ($images -join '|')) 'Imagens mudaram durante o diagnóstico.'
            $success=$true
        } finally {
            # Record actual resources even on a failed scenario. Never delete on failure.
            $owner.inventory=Assert-CredPayDiagnosticScope $runtime $owner
            $owner.stage='Owned'; $owner.result=if($success) { 'Validated' } else { 'Failed' }
            Write-CredPayLocalJson $owner $p.Marker $Root
            if(-not $success) {
                $progressPath=Join-Path $p.Directory 'progress.json'
                if(Test-Path -LiteralPath $progressPath) {
                    $progress=Read-CredPayLocalJson $progressPath $Root
                    if($progress.phase -cmatch '^[a-z-]{1,60}$' -and $progress.category -cin @('running','binding','outbox','http','metric','scope','unclassified','complete') -and
                        $progress.restoration -cin @('not-attempted','verified','unverified')) {
                        Write-Host "Diagnóstico local: fase=$($progress.phase); categoria=$($progress.category); restauração=$($progress.restoration); detalhes brutos omitidos."
                    }
                }
                Write-Host "Diagnóstico local: falha na fase=$phase; sessão preservada para revisão/Remove próprio."
            }
        }
        Write-Host 'Diagnóstico local: dois publicadores, retorno sem rota e recuperação causal conferidos; executar Remove separadamente.'
    } else {
        $pointer=Read-CredPayLocalJson $active $Root
        $session=Read-CredPayDiagnosticSession $Root $pointer.project; $p=$session.Plan; $owner=$session.Owner
        Assert-LocalCondition ($owner.stage -ceq 'Owned') 'Criação incompleta; revisar propriedade, sem adotar/remover por nome.'
        $runtime=Get-CredPayDiagnosticRuntime $p
        $actual=Assert-CredPayDiagnosticScope $runtime $owner
        Assert-CredPayDiagnosticRemoval $owner.inventory $actual
        $again=Assert-CredPayDiagnosticScope $runtime $owner
        Assert-CredPayDiagnosticRemoval $actual $again
        $arguments=@('compose','--project-name',$p.Project,'--env-file',(Join-Path $Root 'infra/compose/.env.example'),
            '--file',(Join-Path $Root 'infra/compose/compose.yaml'),'--file',(Join-Path $Root 'infra/compose/compose.diagnostics.yaml'),'--file',$p.Overlay,'down','--volumes','--timeout','30')
        if($runtime.Compose) { $null=Invoke-CredPayDiagnosticTool $runtime $runtime.Compose @($arguments | Select-Object -Skip 1) 180 }
        else { $null=Invoke-CredPayDiagnosticTool $runtime $runtime.Docker $arguments 180 }
        Assert-LocalCondition ((Get-CredPayDiagnosticIdentity (Get-CredPayDiagnosticInventory $runtime)) -ceq '') 'Recursos próprios ainda presentes; preservar sessão.'
        $owner.stage='Removed'; Write-CredPayLocalJson $owner $p.Marker $Root
        Assert-CredPayLocalPath $Root $active; Remove-Item -LiteralPath $active
        Write-Host 'Diagnóstico local: somente projeto/volumes/rede próprios removidos; Compose credpay-demo preservado.'
    }
}
