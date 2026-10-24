#requires -Version 7.3
# Functions only; native commands are dispatched by local-runtime.ps1.
function Invoke-CredPayLocalAction([string]$Action, [string]$ImageTag, [string]$root) {
    Assert-LocalCondition ($Action -cin @('Create','Validate','Remove')) 'Ação local desconhecida; nenhuma operação autorizada.'
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
        $infra=@(
            @{Source='postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0';Alias='credpay-postgres:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0'},
            @{Source='rabbitmq:4.3.5-management-alpine@sha256:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1';Alias='credpay-rabbit:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1'})
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
            $null=Invoke-CredPayLocalTool $runtime $plan.Kind (@('load','docker-image','--name',$cluster) + $appImages + @($infra.Alias)) 300
            $phase='app-cache'
            for ($i=0;$i -lt 2;$i++) {
                $cached=Invoke-CredPayLocalTool $runtime $runtime.Docker @('exec',"$cluster-control-plane",'crictl','--runtime-endpoint','unix:///run/containerd/containerd.sock','--image-endpoint','unix:///run/containerd/containerd.sock','inspecti','--output','json',$appImages[$i]) | ConvertFrom-Json -Depth 30
                Assert-LocalCondition ($cached.status.id -ceq $appIds[$i]) 'Imagem do aplicativo no node diverge da imagem local.'
            }
            Write-Host "Kubernetes local: cluster=$cluster; propriedade/node/API loopback conferidos; quatro imagens carregadas."
        } catch { throw "Criação/carregamento local interrompido; fase=$phase; sessão preservada. Não adotar ou remover recurso por nome. Envie somente esta mensagem para diagnóstico." }
    } else {
        $session=Read-CredPayLocalJson $active $root
        Assert-LocalCondition ($session.schema -eq 1) 'Sessão local inválida.'
        $plan=New-CredPayLocalPlan $root $session.cluster $session.imageTag
        $owner=Read-CredPayLocalJson $plan.Marker $root
        $runtime=Get-CredPayLocalRuntime $plan
        Assert-CredPayLocalLive $runtime $owner
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
