#requires -Version 7.3
function Assert-CredPaySecretCluster([string]$Cluster) {
    if ($Cluster -cnotmatch '^credpay-(ci-[0-9]+-[0-9]+|local-[0-9]{8}-[0-9a-f]{8})$') {
        throw 'Secret permitido somente no cluster descartável próprio.'
    }
}
function Assert-CredPayKubernetesSecret($Secret, [string]$Cluster, [switch]$Stored) {
    Assert-CredPaySecretCluster $Cluster
    try {
        if ($Secret.apiVersion -cne 'v1' -or $Secret.kind -cne 'Secret' -or $Secret.type -cne 'Opaque' -or
            $Secret.immutable -isnot [bool] -or $Secret.immutable -ne $true -or
            $Secret.metadata.name -cne 'credpay-demo' -or $Secret.metadata.namespace -cne 'credpay' -or
            $Secret.metadata.labels.'app.kubernetes.io/managed-by' -cne 'credpay-disposable' -or
            $Secret.metadata.labels.'credpay.test/cluster' -cne $Cluster -or $null -ne $Secret.stringData -or
            $null -ne $Secret.metadata.annotations.'kubectl.kubernetes.io/last-applied-configuration') { throw 'policy' }
        if ($Stored -and $Secret.metadata.uid -cnotmatch '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') { throw 'identity' }
        $keys = @('proc-password','proc-user','rabbit-password','rabbit-user','tx-password','tx-user')
        if ((@($Secret.data.PSObject.Properties.Name | Sort-Object) -join ',') -cne ($keys -join ',')) { throw 'fields' }
        $passwords = @()
        foreach ($key in $keys) {
            $encoded = $Secret.data.$key
            if ($encoded -isnot [string] -or $encoded.Length -gt 128) { throw 'encoding' }
            $bytes = [Convert]::FromBase64String($encoded)
            if ([Convert]::ToBase64String($bytes) -cne $encoded) { throw 'canonical encoding' }
            $value = [Text.UTF8Encoding]::new($false,$true).GetString($bytes)
            if ($key.EndsWith('-password')) {
                if ($value -cnotmatch '^[0-9a-f]{64}$') { throw 'strength' }
                $passwords += $value
            } else {
                $expected = @{ 'tx-user'='credpay_tx';'proc-user'='credpay_proc';'rabbit-user'='credpay_demo' }[$key]
                if ($value -cne $expected) { throw 'user' }
            }
        }
        if (@($passwords | Select-Object -Unique).Count -ne 3) { throw 'distinct' }
    } catch { throw 'Secret existente ou entrada divergente; conteúdo omitido, sem sobrescrever ou rotacionar.' }
}
function New-CredPayKubernetesSecret([string]$Cluster) {
    Assert-CredPaySecretCluster $Cluster
    $data = @{}
    foreach ($pair in @{ 'tx-user'='credpay_tx';'proc-user'='credpay_proc';'rabbit-user'='credpay_demo' }.GetEnumerator()) {
        $data[$pair.Key] = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair.Value))
    }
    $values = @()
    foreach ($key in @('tx-password','proc-password','rabbit-password')) {
        do { $value = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant() }
        while ($value -cin $values)
        $values += $value
        $data[$key] = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($value))
    }
    $secret = @{
        apiVersion='v1';kind='Secret';type='Opaque';immutable=$true;data=$data
        metadata=@{name='credpay-demo';namespace='credpay';labels=@{'app.kubernetes.io/managed-by'='credpay-disposable';'credpay.test/cluster'=$Cluster}}
    } | ConvertTo-Json -Depth 10 -Compress | ConvertFrom-Json -Depth 10
    Assert-CredPayKubernetesSecret $secret $Cluster
    return $secret
}
function Read-CredPaySecretJson([string]$Text) {
    try {
        if ($Text.Length -gt 16384) { throw 'size' }
        return ($Text | ConvertFrom-Json -Depth 20 -ErrorAction Stop)
    } catch { throw 'Secret JSON ilegível; conteúdo omitido.' }
}
function Ensure-CredPayKubernetesSecret([string]$Cluster) {
    Assert-CredPaySecretCluster $Cluster
    $raw = Kube get secret credpay-demo --ignore-not-found -o json
    if ($raw -cne '') {
        $existing = Read-CredPaySecretJson $raw
        Assert-CredPayKubernetesSecret $existing $Cluster -Stored
        return [string]$existing.metadata.uid
    }
    $secret = New-CredPayKubernetesSecret $Cluster
    # Create refuses a concurrent object; never apply/overwrite, persist or pass values in argv.
    $created = Read-CredPaySecretJson (($secret | ConvertTo-Json -Depth 10 -Compress) | Kube create -f '-' -o json)
    Assert-CredPayKubernetesSecret $created $Cluster -Stored
    foreach ($key in $secret.data.PSObject.Properties.Name) {
        if ($created.data.$key -cne $secret.data.$key) { throw 'Secret criado divergente; sessão preservada.' }
    }
    return [string]$created.metadata.uid
}
