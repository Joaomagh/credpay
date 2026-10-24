#requires -Version 7.3
# Metadata only; never extracts files or invokes native commands.
function Assert-CredPayUniqueJson($Element) {
    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Object) {
        $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        foreach ($property in $Element.EnumerateObject()) {
            Assert-LocalCondition ($names.Add($property.Name)) 'Chave JSON ambígua em metadados de imagem.'
            Assert-CredPayUniqueJson $property.Value
        }
    } elseif ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Array) {
        foreach ($item in $Element.EnumerateArray()) { Assert-CredPayUniqueJson $item }
    }
}
function Read-CredPayTarJson([string]$Root,[string]$Archive,[string]$Name) {
    Assert-CredPayLocalPath $Root $Archive
    Assert-LocalCondition ($Name -cmatch '^(manifest\.json|blobs/sha256/[0-9a-f]{64}|[0-9a-f]{64}\.json)$') 'Nome de metadado TAR fora da baseline.'
    Assert-LocalCondition ([IO.File]::Exists($Archive) -and (Get-Item -LiteralPath $Archive).Length -le 8GB) 'Arquivo de imagem ausente ou acima do limite.'
    $stream=[IO.File]::OpenRead($Archive)
    $reader=[System.Formats.Tar.TarReader]::new($stream,$false)
    $bytes=$null; $count=0
    try {
        while ($null -ne ($entry=$reader.GetNextEntry())) {
            $count++
            Assert-LocalCondition ($count -le 4096) 'Arquivo TAR tem entradas demais.'
            if ($entry.Name -ceq $Name -or $entry.Name -ceq "./$Name") {
                Assert-LocalCondition ($null -eq $bytes) 'Metadado TAR duplicado.'
                Assert-LocalCondition ($entry.EntryType -in @([System.Formats.Tar.TarEntryType]::RegularFile,[System.Formats.Tar.TarEntryType]::V7RegularFile) -and
                    $entry.Length -gt 0 -and $entry.Length -le 1MB) 'Tipo ou tamanho de metadado TAR inválido.'
                $bytes=[byte[]]::new([int]$entry.Length)
                $entry.DataStream.ReadExactly($bytes,0,$bytes.Length)
            }
        }
    } catch { throw 'Leitura de metadados TAR recusada; conteúdo omitido.' }
    finally { $reader.Dispose() }
    Assert-LocalCondition ($null -ne $bytes) 'Metadado TAR necessário ausente.'
    if ($Name -cne 'manifest.json') {
        $digest=($Name -split '/')[-1] -replace '\.json$',''
        Assert-LocalCondition ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant() -ceq $digest) 'Hash do metadado TAR divergente.'
    }
    $json=$null
    try {
        $text=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)
        $json=[System.Text.Json.JsonDocument]::Parse($text)
        Assert-CredPayUniqueJson $json.RootElement
        $value=ConvertFrom-Json -InputObject $text -Depth 32 -NoEnumerate
        return ,$value
    } catch { throw 'JSON de imagem inválido ou ambíguo; conteúdo omitido.' }
    finally { if ($null -ne $json) { $json.Dispose() } }
}
function Get-CredPayArchiveConfigIds([string]$Root, [string]$Archive, [string[]]$Images, [string[]]$SelectedIds) {
    Assert-LocalCondition ($Images.Count -eq 4 -and $SelectedIds.Count -eq 4) 'Conjunto de quatro identidades incompleto.'
    $entries=@(Read-CredPayTarJson $Root $Archive 'manifest.json')
    # Preserve the JSON array rather than an extra wrapper introduced by function output.
    if ($entries.Count -eq 1 -and $entries[0] -is [array]) { $entries=@($entries[0]) }
    Assert-LocalCondition ($entries.Count -eq 4) 'Manifesto Docker deve identificar quatro imagens.'
    $result=@()
    for ($i=0;$i -lt 4;$i++) {
        Assert-LocalCondition ($SelectedIds[$i] -cmatch '^sha256:[0-9a-f]{64}$') 'ID selecionado inválido.'
        $reference=$Images[$i]
        $tagEntries=@(); $tagCount=0
        foreach ($item in $entries) {
            $references=@($item.RepoTags | Where-Object { $_ -ceq $reference -or $_ -ceq "docker.io/library/$reference" })
            $tagCount+=$references.Count
            if ($references.Count -gt 0) { $tagEntries+=$item }
        }
        Assert-LocalCondition ($tagEntries.Count -eq 1 -and $tagCount -eq 1) 'Referência TAR ausente ou ambígua.'
        $entry=$tagEntries[0]
        Assert-LocalCondition ($entry.Config -cmatch '^(blobs/sha256/[0-9a-f]{64}|[0-9a-f]{64}\.json)$') 'Caminho da config fora da baseline.'
        $configId='sha256:' + (($entry.Config -split '/')[-1] -replace '\.json$','')
        $config=Read-CredPayTarJson $Root $Archive $entry.Config
        Assert-LocalCondition ($config.os -ceq 'linux' -and $config.architecture -ceq 'amd64') 'Config fora da plataforma linux/amd64.'
        if ($SelectedIds[$i] -cne $configId) {
            $manifest=Read-CredPayTarJson $Root $Archive ('blobs/sha256/' + $SelectedIds[$i].Substring(7))
            Assert-LocalCondition ($manifest.schemaVersion -eq 2 -and $manifest.config.digest -ceq $configId -and
                $manifest.mediaType -cin @('application/vnd.oci.image.manifest.v1+json','application/vnd.docker.distribution.manifest.v2+json')) 'Manifesto selecionado não identifica a config exportada.'
            Assert-LocalCondition ('layers' -cin @($manifest.PSObject.Properties.Name)) 'Camadas do manifesto ausentes.'
            $layers=@()
            foreach ($layer in @($manifest.layers)) {
                Assert-LocalCondition ($layer.digest -cmatch '^sha256:[0-9a-f]{64}$') 'Digest de camada inválido.'
                $layers+= 'blobs/sha256/' + $layer.digest.Substring(7)
            }
            Assert-LocalCondition ((ConvertTo-Json -InputObject $layers -Compress) -ceq (ConvertTo-Json -InputObject @($entry.Layers) -Compress)) 'Camadas exportadas não correspondem ao manifesto selecionado.'
        }
        $result+=$configId
    }
    return $result
}
