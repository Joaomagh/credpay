#requires -Version 7.3
# Functions only; native commands are dispatched by local-runtime.ps1.
. (Join-Path $PSScriptRoot 'image-archive.ps1')
function Get-CredPayLocalInfraImages {
    return @(
        @{Source='postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0';Alias='credpay-postgres:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0'},
        @{Source='rabbitmq:4.3.5-management-alpine@sha256:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1';Alias='credpay-rabbit:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1'})
}
function Get-CredPayLocalImageSet($Runtime, $Owner) {
    $images=@("credpay-transacoes:$($Runtime.Plan.ImageTag)","credpay-processamento:$($Runtime.Plan.ImageTag)")
    Assert-LocalCondition (@($Owner.appIds).Count -eq 2) 'Registro das imagens originais incompleto.'
    for ($i=0;$i -lt 2;$i++) {
        $id=Invoke-CredPayLocalTool $Runtime $Runtime.Docker @('image','inspect','--format','{{.Id}}',$images[$i])
        Assert-LocalCondition ($id -cmatch '^sha256:[0-9a-f]{64}$' -and $id -ceq $Owner.appIds[$i]) 'Imagem do aplicativo diverge da criação original.'
    }
    foreach ($image in @(Get-CredPayLocalInfraImages)) {
        $id=Invoke-CredPayLocalTool $Runtime $Runtime.Docker @('image','inspect','--format','{{.Id}}',$image.Source)
        $aliasId=Invoke-CredPayLocalTool $Runtime $Runtime.Docker @('image','inspect','--format','{{.Id}}',$image.Alias)
        Assert-LocalCondition ($id -cmatch '^sha256:[0-9a-f]{64}$' -and $aliasId -ceq $id) 'Alias existente diverge da fonte; não sobrescrever.'
        $images+=$image.Alias
    }
    return $images
}
function Get-CredPayLocalSelectedIds($Runtime,[string[]]$Images) {
    $ids=@()
    foreach ($image in $Images) {
        $format='{"id":{{json .Id}},"os":{{json .Os}},"arch":{{json .Architecture}}}'
        $selected=Invoke-CredPayLocalTool $Runtime $Runtime.Docker @('image','inspect','--platform=linux/amd64','--format',$format,$image) | ConvertFrom-Json
        Assert-LocalCondition ($selected.id -cmatch '^sha256:[0-9a-f]{64}$' -and $selected.os -ceq 'linux' -and $selected.arch -ceq 'amd64') 'Imagem selecionada fora da baseline linux/amd64.'
        $ids+=$selected.id
    }
    return $ids
}
function Assert-CredPayLocalRuntimeConfigIds($Runtime,[string[]]$Images,[string[]]$ConfigIds) {
    Assert-LocalCondition ($Images.Count -eq 4 -and $ConfigIds.Count -eq 4) 'Conjunto de IDs de config incompleto.'
    for ($i=0;$i -lt 4;$i++) {
        $cached=Invoke-CredPayLocalTool $Runtime $Runtime.Docker @('exec',"$($Runtime.Plan.Cluster)-control-plane",'crictl','--runtime-endpoint','unix:///run/containerd/containerd.sock','--image-endpoint','unix:///run/containerd/containerd.sock','inspecti','--output','json',$Images[$i]) | ConvertFrom-Json -Depth 30
        Assert-LocalCondition ($ConfigIds[$i] -cmatch '^sha256:[0-9a-f]{64}$' -and $cached.status.id -ceq $ConfigIds[$i]) 'Config no runtime não corresponde à imagem selecionada e exportada.'
    }
}
function Assert-CredPayLocalImageCache($Runtime,$Owner) {
    Assert-CredPayLocalLive $Runtime $Owner
    $plan=$Runtime.Plan
    $proof=Read-CredPayLocalJson (Join-Path $plan.Directory 'image-proof.json') $plan.Root
    Assert-LocalCondition ($proof.schema -eq 1 -and $proof.cluster -ceq $plan.Cluster -and
        $proof.nodeId -ceq $Owner.nodeId -and $proof.imageTag -ceq $plan.ImageTag) 'Registro da prova de imagens divergente.'
    Assert-CredPayLocalPath $plan.Temp $proof.archive
    $images=@(Get-CredPayLocalImageSet $Runtime $Owner)
    $selectedIds=@(Get-CredPayLocalSelectedIds $Runtime $images)
    $configIds=@(Get-CredPayArchiveConfigIds $plan.Root $proof.archive $images $selectedIds)
    Assert-CredPayLocalRuntimeConfigIds $Runtime $images $configIds
}
function Complete-CredPayLocalImageLoad($Runtime, $Owner) {
    Assert-CredPayLocalLive $Runtime $Owner
    $images=@(Get-CredPayLocalImageSet $Runtime $Owner)
    $plan=$Runtime.Plan
    $selectedIds=@(Get-CredPayLocalSelectedIds $Runtime $images)
    $archive=Join-Path $plan.Temp ('images-amd64-' + [Guid]::NewGuid().ToString('N') + '.tar')
    Assert-CredPayLocalPath $plan.Root $archive
    Assert-LocalCondition (-not [IO.File]::Exists($archive)) 'Arquivo próprio já existe; não sobrescrever.'
    $phase='export-amd64'
    try {
        # A sparse multi-platform OCI index cannot be imported with kind's --all-platforms.
        $null=Invoke-CredPayLocalTool $Runtime $Runtime.Docker (@('save','--platform=linux/amd64','--output',$archive) + $images) 300
        Assert-LocalCondition ([IO.File]::Exists($archive) -and (Get-Item -LiteralPath $archive).Length -gt 0) 'Exportação não produziu arquivo.'
        $phase='archive-identity'
        Assert-CredPayLocalLive $Runtime $Owner
        $null=Get-CredPayLocalImageSet $Runtime $Owner
        $currentIds=@(Get-CredPayLocalSelectedIds $Runtime $images)
        Assert-LocalCondition ((ConvertTo-Json -InputObject $selectedIds -Compress) -ceq (ConvertTo-Json -InputObject $currentIds -Compress)) 'Plataforma selecionada mudou durante exportação.'
        $configIds=@(Get-CredPayArchiveConfigIds $plan.Root $archive $images $selectedIds)
        $phase='image-import'
        Assert-CredPayLocalLive $Runtime $Owner
        Assert-CredPayLocalPath $plan.Root $archive
        $null=Invoke-CredPayLocalTool $Runtime $plan.Kind @('load','image-archive','--name',$plan.Cluster,$archive) 300
        $phase='runtime-ids'
        Assert-CredPayLocalRuntimeConfigIds $Runtime $images $configIds
        Write-CredPayLocalJson @{schema=1;cluster=$plan.Cluster;nodeId=$Owner.nodeId;imageTag=$plan.ImageTag;archive=$archive} (Join-Path $plan.Directory 'image-proof.json') $plan.Root
    } catch { throw "Carga local interrompida; fase=$phase; sessão e arquivo próprios preservados; detalhes brutos omitidos." }
}
function Invoke-CredPayLocalAction([string]$Action, [string]$ImageTag, [string]$root, [string]$ExpectedNodeId = '') {
    Assert-LocalCondition ($Action -cin @('Create','Resume','LoadImages','Validate','Remove')) 'Ação local desconhecida; nenhuma operação autorizada.'
    $active=Join-Path $root '.local/k8s/active-local.json'
    Assert-CredPayLocalPath $root $active

    if ($Action -ceq 'Create') {
        Assert-LocalCondition (-not (Test-Path -LiteralPath $active)) 'Há sessão local registrada; não sobrescrever ou adotar recursos existentes.'
        $prepared=Read-CredPayLocalJson (Join-Path $root '.local/evidence/local-acceptance-prepared.json') $root
        Assert-LocalCondition ($prepared.sourceRevision -ceq $ImageTag) 'Tag não corresponde aos JARs preparados; não reutilizar imagem antiga.'
        Assert-LocalCondition (@($prepared.artifacts).Count -eq 2) 'Manifesto dos dois JARs ausente.'
        foreach ($artifact in $prepared.artifacts) {
            $file=Join-Path $root $artifact.path
            Assert-CredPayLocalPath $root $file
            Assert-LocalCondition ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $artifact.sha256) 'JAR diverge do manifesto preparado.'
        }
        $cluster='credpay-local-' + [DateTime]::UtcNow.ToString('yyyyMMdd') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
        $plan=New-CredPayLocalPlan $root $cluster $ImageTag
        Assert-CredPayLocalPath $root $plan.Directory
        Assert-LocalCondition (-not (Test-Path -LiteralPath $plan.Directory)) 'Diretório de sessão já existe.'
        $null=New-Item -ItemType Directory -Path $plan.Directory,$plan.DockerConfig,$plan.Temp
        Write-CredPayLocalJson ([pscustomobject]@{}) (Join-Path $plan.DockerConfig 'config.json') $root -New
        $runtime=Get-CredPayLocalRuntime $plan
        $daemon=Get-CredPayLocalDaemon $runtime
        $existing=Invoke-CredPayLocalTool $runtime $plan.Kind @('get','clusters')
        Assert-LocalCondition ($cluster -cnotin @($existing -split '\r?\n')) 'Cluster já existia; criação recusada.'
        $kindVersion=Invoke-CredPayLocalTool $runtime $plan.Kind @('version')
        Assert-LocalCondition ($kindVersion -cmatch '^kind v0\.33\.0\b') 'Versão kind divergente.'
        $client=Invoke-CredPayLocalTool $runtime $plan.Kubectl @('--kubeconfig',$plan.Kubeconfig,'version','--client=true','-o','json') | ConvertFrom-Json
        Assert-LocalCondition ($client.clientVersion.gitVersion -ceq 'v1.36.4') 'Versão kubectl divergente.'
        $appImages=@("credpay-transacoes:$ImageTag","credpay-processamento:$ImageTag")
        $appIds=@()
        foreach ($image in $appImages) {
            $id=Invoke-CredPayLocalTool $runtime $runtime.Docker @('image','inspect','--format','{{.Id}}',$image)
            Assert-LocalCondition ($id -cmatch '^sha256:[0-9a-f]{64}$') 'Imagem do aplicativo ausente ou ilegível.'
            $appIds+=$id
        }
        $infra=@(Get-CredPayLocalInfraImages)
        foreach ($image in $infra) {
            try { $id=Invoke-CredPayLocalTool $runtime $runtime.Docker @('image','inspect','--format','{{.Id}}',$image.Source) }
            catch { $null=Invoke-CredPayLocalTool $runtime $runtime.Docker @('pull',$image.Source) 300; $id=Invoke-CredPayLocalTool $runtime $runtime.Docker @('image','inspect','--format','{{.Id}}',$image.Source) }
            try { $aliasId=Invoke-CredPayLocalTool $runtime $runtime.Docker @('image','inspect','--format','{{.Id}}',$image.Alias) }
            catch { $null=Invoke-CredPayLocalTool $runtime $runtime.Docker @('tag',$image.Source,$image.Alias); $aliasId=$id }
            Assert-LocalCondition ($id -cmatch '^sha256:[0-9a-f]{64}$' -and $aliasId -ceq $id) 'Alias existente diverge da fonte; não sobrescrever.'
        }
        $kindConfig=Join-Path $plan.Directory 'kind.yaml'
        [IO.File]::WriteAllText($kindConfig, "kind: Cluster`napiVersion: kind.x-k8s.io/v1alpha4`nnetworking:`n  apiServerAddress: 127.0.0.1`n  apiServerPort: 0`n  ipFamily: ipv4`nnodes:`n- role: control-plane`n", [Text.UTF8Encoding]::new($false))
        $owner=[pscustomobject]@{schema=1;stage='Creating';cluster=$cluster;imageTag=$ImageTag;daemonId=$daemon.id;nodeId='';kubeconfig=$plan.Kubeconfig;appIds=$appIds}
        Write-CredPayLocalJson $owner $plan.Marker $root -New
        Write-CredPayLocalJson ([pscustomobject]@{schema=1;cluster=$cluster;imageTag=$ImageTag}) $active $root -New
        $phase='create'
        Write-Host 'Kubernetes local: criando um cluster próprio; o download do node pode levar alguns minutos.'
        try {
            $null=Invoke-CredPayLocalTool $runtime $plan.Kind @('create','cluster','--name',$cluster,'--kubeconfig',$plan.Kubeconfig,'--config',$kindConfig,'--image',$plan.NodeImage,'--wait','180s') 600
            $phase='node-policy'
            $node=Get-CredPayLocalNode $runtime
            Assert-CredPayLocalNode $node $plan
            $owner.nodeId=$node.id; $owner.stage='Owned'
            $phase='ownership'
            Assert-CredPayLocalLive $runtime $owner
            Write-CredPayLocalJson $owner $plan.Marker $root
            $phase='image-load'
            Complete-CredPayLocalImageLoad $runtime $owner
            Write-Host "Kubernetes local: cluster=$cluster; propriedade/node/API loopback conferidos; quatro imagens carregadas."
        } catch { throw "Criação/carregamento local interrompido; fase=$phase; sessão preservada. Não adotar ou remover recurso por nome. Envie somente esta mensagem para diagnóstico." }
    } else {
        $session=Read-CredPayLocalJson $active $root
        Assert-LocalCondition ($session.schema -eq 1) 'Sessão local inválida.'
        $plan=New-CredPayLocalPlan $root $session.cluster $session.imageTag
        $owner=Read-CredPayLocalJson $plan.Marker $root
        $runtime=Get-CredPayLocalRuntime $plan
        if ($Action -ceq 'Resume') {
            Assert-LocalCondition ($ExpectedNodeId -cmatch '^[0-9a-f]{64}$') 'Resume exige o ID completo do node conferido; não selecionar somente por nome.'
            Assert-LocalCondition ($owner.schema -eq 1 -and $owner.stage -ceq 'Creating' -and
                $owner.cluster -ceq $plan.Cluster -and $owner.imageTag -ceq $plan.ImageTag -and
                $owner.nodeId -ceq '' -and $owner.kubeconfig -ceq $plan.Kubeconfig) 'Registro de criação original incompleto ou divergente.'
            Assert-CredPayLocalPath $root $plan.Kubeconfig
            Assert-LocalCondition ([IO.File]::Exists($plan.Kubeconfig)) 'Kubeconfig original ausente; não recuperar por nome.'
            $daemon=Get-CredPayLocalDaemon $runtime
            Assert-LocalCondition ($daemon.id -ceq $owner.daemonId) 'Daemon diferente da criação original.'
            $node=Get-CredPayLocalNode $runtime
            Assert-CredPayLocalNode $node $plan $ExpectedNodeId
            # Only a candidate in memory: do not promote the on-disk marker yet.
            $owner.nodeId=$ExpectedNodeId; $owner.stage='Owned'
            Assert-CredPayLocalLive $runtime $owner
            $null=Get-CredPayLocalImageSet $runtime $owner
            $ready=Invoke-CredPayLocalTool $runtime $plan.Kubectl @('--kubeconfig',$plan.Kubeconfig,'--request-timeout=15s','get','--raw=/readyz')
            Assert-LocalCondition ($ready -ceq 'ok') 'API original não confirmou prontidão; sessão incompleta preservada.'
            Assert-CredPayLocalLive $runtime $owner
            Write-CredPayLocalJson $owner $plan.Marker $root
            Complete-CredPayLocalImageLoad $runtime $owner
            Write-Host 'Kubernetes local: retomada conferida; quatro imagens carregadas; executar Validate separadamente.'
            return
        }
        Assert-CredPayLocalLive $runtime $owner
        if ($Action -ceq 'LoadImages') {
            Assert-LocalCondition ($owner.stage -ceq 'Owned') 'LoadImages exige sessão própria Owned, antes de Validate.'
            Complete-CredPayLocalImageLoad $runtime $owner
            Write-Host 'Kubernetes local: quatro imagens linux/amd64 carregadas e IDs conferidos; executar Validate separadamente.'
            return
        }
        if ($Action -ceq 'Validate') {
            foreach ($script in @('verify-image-cache.ps1','verify-startup.ps1','verify-flow.ps1')) {
                Assert-CredPayLocalLive $runtime $owner
                $output=Invoke-CredPayLocalTool $runtime $runtime.Pwsh @('-NoLogo','-NoProfile','-File',(Join-Path $PSScriptRoot $script),'-Cluster',$plan.Cluster,'-ExecutionProfile','Local') 1200
                Write-Host $output
            }
            $owner.stage='Validated'; Write-CredPayLocalJson $owner $plan.Marker $root
            Write-Host 'Kubernetes local: validação concluída; cluster mantido até Remove explícito.'
        } else {
            Assert-CredPayLocalLive $runtime $owner
            $null=Invoke-CredPayLocalTool $runtime $plan.Kind @('delete','cluster','--name',$plan.Cluster) 180
            $remaining=Invoke-CredPayLocalTool $runtime $plan.Kind @('get','clusters')
            Assert-LocalCondition ($plan.Cluster -cnotin @($remaining -split '\r?\n')) 'Cluster ainda listado; manter registro e revisar.'
            $owner.stage='Removed'; Write-CredPayLocalJson $owner $plan.Marker $root
            Assert-CredPayLocalPath $root $active
            Remove-Item -LiteralPath $active
            Write-Host 'Kubernetes local: somente o cluster próprio removido; Compose e seus volumes preservados.'
        }
    }
}
