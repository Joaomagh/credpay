#requires -Version 7.3
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'local-safety.ps1')
. (Join-Path $PSScriptRoot 'image-archive.ps1')
. (Join-Path $PSScriptRoot 'image-archive-fixture.ps1')
$repo=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$root=Join-Path $repo ('.local/image-archive-controls/' + [Guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $root
$images=@('credpay-transacoes:fixture','credpay-processamento:fixture','credpay-postgres:fixture','credpay-rabbit:fixture')
$script:count=0; $script:failures=@()
function CheckArchive([string]$Label,[scriptblock]$Run,[bool]$Reject=$false) {
    $script:count++; $thrown=$false
    try { $null=& $Run } catch { $thrown=$true }
    if ($thrown -ne $Reject) { $script:failures+=$Label }
}
$fixture=New-CredPayImageFixture $root $images
CheckArchive 'selected manifest to config chain' {
    $ids=@(Get-CredPayArchiveConfigIds $root $fixture.Archive $images $fixture.SelectedIds)
    if (($ids -join ',') -cne ($fixture.ConfigIds -join ',')) { throw 'Config chain mismatch.' }
}
CheckArchive 'legacy selected ID is already the config digest' {
    $ids=@(Get-CredPayArchiveConfigIds $root $fixture.Archive $images $fixture.ConfigIds)
    if (($ids -join ',') -cne ($fixture.ConfigIds -join ',')) { throw 'Legacy chain mismatch.' }
}
foreach ($fault in @('tag','duplicate-tag','duplicate-repo-tag','config-hash','manifest-hash','missing-config','layers','platform','json-duplicate','metadata-link','duplicate-tar-entry','oversized')) {
    $bad=New-CredPayImageFixture $root $images $fault
    CheckArchive "reject $fault" { Get-CredPayArchiveConfigIds $root $bad.Archive $images $bad.SelectedIds } $true
}
$badIds=@($fixture.SelectedIds); $badIds[0]='sha256:' + ('f' * 64)
CheckArchive 'reject unrelated selected manifest' { Get-CredPayArchiveConfigIds $root $fixture.Archive $images $badIds } $true
CheckArchive 'reject archive outside root before reading' { Get-CredPayArchiveConfigIds $root (Join-Path $root '../outside.tar') $images $fixture.SelectedIds } $true
if ($failures.Count) { throw "Image archive: failed=$($failures.Count)/$count; cases=$($failures -join ', ')" }
Write-Host "Image archive: $count offline controls passed; fictitious TAR metadata, no extraction or daemon."
