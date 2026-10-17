#requires -Version 7.3
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
. (Join-Path $PSScriptRoot 'observability-baseline.ps1')
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if ($env:GITHUB_ACTIONS -cne 'true') { throw 'Scan de imagens de observação somente no CI descartável.' }
function Require([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function RunDocker {
    $output = & docker @args 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "Docker scan observação falhou: operação=$($args[0]); conteúdo omitido." }
    return $output.Trim()
}
$binary = Join-Path $root '.local/security/bin/trivy'
$raw = Join-Path $root '.local/security/observability-raw'
$inputs = Join-Path $root '.local/security/inputs/observability'
$cache = Join-Path $root '.local/security/cache'
$shared = Join-Path $root '.local/security/raw'
$null = New-Item -ItemType Directory -Force $raw, $inputs
$checkout = [IO.File]::ReadAllText((Join-Path $shared 'checkout.txt')).Trim()
Require ($checkout -cmatch '^[a-f0-9]{40}$') 'Checkout do scan inválido.'
$scannerText = [IO.File]::ReadAllText((Join-Path $shared 'scanner.json'))
$scanner = $scannerText | ConvertFrom-Json -AsHashtable
# Preservar timestamps originais como strings, sem arredondar nanos na serialização.
$document = [Text.Json.JsonDocument]::Parse($scannerText)
try {
    foreach ($database in @('VulnerabilityDB', 'JavaDB')) {
        foreach ($field in @('UpdatedAt', 'DownloadedAt')) {
            $scanner[$database][$field] = $document.RootElement.GetProperty($database).GetProperty($field).GetString()
        }
    }
} finally { $document.Dispose() }
Require ($scanner.Version -ceq '0.75.0') 'Scanner de observação deve reutilizar versão verificada.'
$targets = @()
foreach ($baseline in @(ReadObservabilityBaseline)) {
    $reference = $baseline.Reference
    $null = RunDocker pull --platform linux/amd64 $reference
    $identity = (RunDocker image inspect --format '{{.Id}}|{{.Os}}|{{.Architecture}}' $reference) -split '\|'
    Require ($identity.Count -eq 3 -and $identity[0] -cmatch '^sha256:[a-f0-9]{64}$' -and
        $identity[1] -ceq 'linux' -and $identity[2] -ceq 'amd64') 'Identidade/arquitetura observação inválida.'
    $digests = @(RunDocker image inspect --format '{{json .RepoDigests}}' $reference | ConvertFrom-Json)
    Require (($baseline.Repository + '@' + ($reference -split '@')[1]) -cin $digests) 'Digest de origem não conferido.'
    $platformReference = "$($baseline.Repository)@$($baseline.PlatformDigest)"
    $null = RunDocker pull --platform linux/amd64 $platformReference
    Require ((RunDocker image inspect --format '{{.Id}}' $platformReference) -ceq $identity[0]) 'Digest da plataforma aponta a outro conteúdo.'
    $tar = Join-Path $inputs "$($baseline.Name).tar"
    $null = RunDocker save --output $tar $reference
    $hash = (Get-FileHash -LiteralPath $tar -Algorithm SHA256).Hash.ToLowerInvariant()
    & $binary image --cache-dir $cache --scanners vuln --format json --list-all-pkgs --exit-code 0 --offline-scan `
        --ignorefile (Join-Path $shared 'empty-ignore') --skip-db-update --skip-java-db-update --timeout 10m `
        --input $tar --output (Join-Path $raw "$($baseline.Name).json")
    Require ($LASTEXITCODE -eq 0) 'Scan de imagem observação falhou.'
    Require ((Get-FileHash -LiteralPath $tar -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $hash) 'Tar mudou durante o scan.'
    $targets += @{Name=$baseline.Name;Reference=$reference;PlatformDigest=$baseline.PlatformDigest;
        ImageId=$identity[0];Os=$identity[1];Architecture=$identity[2];TarSha256=$hash}
}
# A execução usa o mesmo cache congelado do inventário dos apps, sem atualização de bases.
Require ([IO.File]::ReadAllText((Join-Path $shared 'scanner.json')) -ceq $scannerText) 'Metadados compartilhados mudaram.'
[IO.File]::WriteAllText((Join-Path $raw 'images.json'),
    (@{Checkout=$checkout;Scanner=$scanner;Targets=$targets} | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
$output = Join-Path $root '.local/security/observability-reports'
& (Join-Path $PSScriptRoot 'report-observability.ps1') -Directory $raw -OutputDirectory $output
if ($env:GITHUB_STEP_SUMMARY) { Get-Content (Join-Path $output 'summary.md') | Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY }
exit 0
