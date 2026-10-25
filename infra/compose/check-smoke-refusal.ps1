#requires -Version 7.3
$ErrorActionPreference='Stop'
$global:cpSmokeCalls=[Collections.Generic.List[string]]::new()
function docker {
    $global:cpSmokeCalls.Add(($args -join ' '))
    $global:LASTEXITCODE=0
    if ($args -contains 'config') {
        return '{"services":{"transacoes":{"image":"credpay-transacoes:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ports":[{"host_ip":"127.0.0.1","published":"18080"}]},"processamento":{"image":"credpay-processamento:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","ports":[{"host_ip":"127.0.0.1","published":"18081"}]}}}'
    }
    if ($args -contains 'ps') { return 'fictitious-existing-container' }
    throw 'Unexpected fictitious command; no native fallback.'
}
$refused=$false
try { & (Join-Path $PSScriptRoot 'demo.ps1') -Action Smoke -Project credpay-existing -EnvFile .env.example }
catch { $refused=$_.Exception.Message -like 'Smoke exige projeto*'; $observed=$_.Exception.Message; $sourceLine=$_.InvocationInfo.ScriptLineNumber }
if(-not $refused) { throw "Existing project was not refused as expected: $observed line=$sourceLine calls=$($global:cpSmokeCalls -join '|')" }
if(@($global:cpSmokeCalls | Where-Object { $_ -match '\b(stop|up|down|exec|cp)\b' }).Count) { throw 'Smoke mutated an existing project after refusing ownership.' }
Write-Host 'Smoke refusal: existing project refused with zero mutations; fictitious command transport only.'
