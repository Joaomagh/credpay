#requires -Version 7.3
# Fictitious OCI metadata for offline controls; no executable container images.
function New-CredPayImageFixture([string]$Root, [string[]]$Images, [string]$Fault = '') {
    $blobs=[ordered]@{}; $entries=@(); $sources=@(); $selected=@(); $configs=@()
    foreach ($image in $Images) {
        $config=@{os='linux';architecture='amd64';config=@{Labels=@{fixture=$image}};rootfs=@{type='layers';diff_ids=@()}}
        if ($Fault -ceq 'platform') { $config.architecture='arm64' }
        $configText=ConvertTo-Json -InputObject $config -Depth 20 -Compress
        $configHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($configText))).ToLowerInvariant()
        $configPath="blobs/sha256/$configHash"; $blobs[$configPath]=$configText
        $manifest=@{schemaVersion=2;mediaType='application/vnd.oci.image.manifest.v1+json';config=@{mediaType='application/vnd.oci.image.config.v1+json';digest="sha256:$configHash";size=[Text.Encoding]::UTF8.GetByteCount($configText)};layers=@()}
        $manifestText=ConvertTo-Json -InputObject $manifest -Depth 20 -Compress
        $manifestHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($manifestText))).ToLowerInvariant()
        $blobs["blobs/sha256/$manifestHash"]=$manifestText
        $index=@{schemaVersion=2;manifests=@(@{digest="sha256:$manifestHash";platform=@{os='linux';architecture='amd64'}})}
        $indexText=ConvertTo-Json -InputObject $index -Depth 20 -Compress
        $indexHash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($indexText))).ToLowerInvariant()
        $blobs["blobs/sha256/$indexHash"]=$indexText
        $entries+=@{Config=$configPath;RepoTags=@("docker.io/library/$image");Layers=@()}
        $sources+="sha256:$indexHash"; $selected+="sha256:$manifestHash"; $configs+="sha256:$configHash"
    }
    if ($Fault -ceq 'tag') { $entries[0].RepoTags=@('other:fixture') }
    if ($Fault -ceq 'duplicate-tag') { $entries+= $entries[0] }
    if ($Fault -ceq 'config-hash') { $blobs[$entries[0].Config]='{}' }
    if ($Fault -ceq 'manifest-hash') { $blobs['blobs/sha256/' + $selected[0].Substring(7)]='{}' }
    if ($Fault -ceq 'missing-config') { $blobs.Remove($entries[0].Config) }
    if ($Fault -ceq 'layers') { $entries[0].Layers=@('blobs/sha256/' + ('f' * 64)) }
    $blobs['manifest.json']=ConvertTo-Json -InputObject $entries -Depth 20 -Compress
    if ($Fault -ceq 'json-duplicate') { $blobs['manifest.json']=$blobs['manifest.json'].Replace('"Config":','"Config":"duplicate-fixture","Config":') }
    if ($Fault -ceq 'duplicate-repo-tag') {
        $entries[0].RepoTags+= $entries[0].RepoTags[0]
        $blobs['manifest.json']=ConvertTo-Json -InputObject $entries -Depth 20 -Compress
    }
    if ($Fault -ceq 'oversized') { $blobs['manifest.json']=' ' * 1048577 }
    $archive=Join-Path $Root ('fixture-' + [Guid]::NewGuid().ToString('N') + '.tar')
    Assert-CredPayLocalPath $Root $archive
    $stream=[IO.File]::Open($archive,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write)
    $writer=[System.Formats.Tar.TarWriter]::new($stream,$false)
    try {
        foreach ($name in @($blobs.Keys)) {
            $entry=[System.Formats.Tar.PaxTarEntry]::new([System.Formats.Tar.TarEntryType]::RegularFile,$name)
            if ($Fault -ceq 'metadata-link' -and $name -ceq 'manifest.json') {
                $entry=[System.Formats.Tar.PaxTarEntry]::new([System.Formats.Tar.TarEntryType]::SymbolicLink,$name)
                $entry.LinkName='outside-fixture'
                $writer.WriteEntry($entry)
            } else {
                $entry.DataStream=[IO.MemoryStream]::new([Text.Encoding]::UTF8.GetBytes($blobs[$name]),$false)
                try { $writer.WriteEntry($entry) } finally { $entry.DataStream.Dispose() }
                if ($Fault -ceq 'duplicate-tar-entry' -and $name -ceq 'manifest.json') {
                    $entry.DataStream=[IO.MemoryStream]::new([Text.Encoding]::UTF8.GetBytes($blobs[$name]),$false)
                    try { $writer.WriteEntry($entry) } finally { $entry.DataStream.Dispose() }
                }
            }
        }
    } finally { $writer.Dispose() }
    return @{Archive=$archive;Images=$Images;SourceIds=$sources;SelectedIds=$selected;ConfigIds=$configs}
}
