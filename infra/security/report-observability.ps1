#requires -Version 7.3
param([Parameter(Mandatory)][string]$Directory, [Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
. (Join-Path $PSScriptRoot 'observability-baseline.ps1')
function Require([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
foreach ($path in @($Directory, $OutputDirectory)) {
    Require ([IO.Path]::GetFullPath($path).StartsWith($root + [IO.Path]::DirectorySeparatorChar)) 'Relatórios somente no workspace.'
}
$manifest = Get-Content -Raw (Join-Path $Directory 'images.json') | ConvertFrom-Json -AsHashtable
Require ($manifest.Checkout -cmatch '^[a-f0-9]{40}$') 'SHA do checkout inválido.'
Require ($manifest.Scanner.Version -ceq '0.75.0') 'Versão do scanner divergente.'
# JsonDocument conserva os nove dígitos originais dos timestamps das bases.
$document = [Text.Json.JsonDocument]::Parse([IO.File]::ReadAllText((Join-Path $Directory 'images.json')))
try {
    $scanner = @{Version=$manifest.Scanner.Version}
    foreach ($name in @('VulnerabilityDB', 'JavaDB')) {
        $database = $manifest.Scanner[$name]
        Require ($database.Version -gt 0 -and $database.UpdatedAt -and $database.DownloadedAt) 'Base/metadados ausentes.'
        $rawDatabase = $document.RootElement.GetProperty('Scanner').GetProperty($name)
        $updated = $rawDatabase.GetProperty('UpdatedAt').GetString()
        $downloaded = $rawDatabase.GetProperty('DownloadedAt').GetString()
        foreach ($timestamp in @($updated, $downloaded)) {
            Require ($timestamp -cmatch '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$') 'Timestamp da base inválido.'
        }
        $scanner[$name] = @{Version=$database.Version;UpdatedAt=$updated;DownloadedAt=$downloaded}
    }
} finally { $document.Dispose() }
$baseline = @(ReadObservabilityBaseline)
Require ($manifest.Targets -is [array] -and $manifest.Targets.Count -eq 2) 'Esperados dois alvos distintos.'
$safeTargets = @()
foreach ($expected in $baseline) {
    $matched = @($manifest.Targets | Where-Object { $_.Name -ceq $expected.Name })
    Require ($matched.Count -eq 1) 'Esperados dois alvos distintos.'
    $target = $matched[0]
    Require ($target.Reference -ceq $expected.Reference -and $target.PlatformDigest -ceq $expected.PlatformDigest) 'Fonte/plataforma pinada divergente.'
    Require ($target.ImageId -cmatch '^sha256:[a-f0-9]{64}$' -and $target.Os -ceq 'linux' -and
        $target.Architecture -ceq 'amd64') 'Plataforma/ImageID inválidos.'
    Require ($target.TarSha256 -cmatch '^[a-f0-9]{64}$') 'Hash do tar ausente ou inválido.'
    $safeTargets += @{Name=$target.Name;Reference=$target.Reference;PlatformDigest=$target.PlatformDigest;
        ImageId=$target.ImageId;Os=$target.Os;Architecture=$target.Architecture;TarSha256=$target.TarSha256}
}
Require ($safeTargets[0].ImageId -cne $safeTargets[1].ImageId) 'ImageIDs dos dois alvos devem ser distintos.'
$reports = @()
$completeOS = $true
$summary = @('# Inventário das duas imagens de observabilidade', '',
    "Checkout: $($manifest.Checkout). Scanner0.75.0; triagem pendente. Não aprova segurança.", '',
    '| Alvo | Go | OS | UNKNOWN | LOW | MEDIUM | HIGH | CRITICAL |', '|---|---:|---:|---:|---:|---:|---:|---:|')
foreach ($target in $safeTargets) {
    $raw = Get-Content -Raw (Join-Path $Directory "$($target.Name).json") | ConvertFrom-Json -AsHashtable
    Require ($raw.SchemaVersion -eq 2 -and $raw.Results.Count -gt 0) 'Relatório vazio/inválido.'
    Require ($raw.Metadata.ImageID -ceq $target.ImageId -and $raw.Metadata.ImageConfig.os -ceq 'linux' -and
        $raw.Metadata.ImageConfig.architecture -ceq 'amd64') 'Identidade da imagem divergente.'
    $go = @($raw.Results | Where-Object { $_.Class -ceq 'lang-pkgs' -and $_.Type -ceq 'gobinary' } |
        ForEach-Object { $_.Packages } | Where-Object { $null -ne $_ })
    $os = @($raw.Results | Where-Object { $_.Class -ceq 'os-pkgs' } |
        ForEach-Object { $_.Packages } | Where-Object { $null -ne $_ })
    Write-Host "Observação inventário: $($target.Name) Go=$($go.Count) OS=$($os.Count)."
    foreach ($binary in @($raw.Results | Where-Object { $_.Class -ceq 'lang-pkgs' -and $_.Type -ceq 'gobinary' })) {
        $path = if ($binary.Target -cmatch '^[A-Za-z0-9_./-]{1,150}$') { $binary.Target } else { 'omitido' }
        $mainModule = if ($target.Name -ceq 'prometheus') { 'github.com/prometheus/prometheus' } else { 'github.com/grafana/grafana' }
        $recognized = @($binary.Packages | Where-Object { $_.Name -ceq $mainModule }).Count -gt 0
        Write-Host "Observação cobertura parcial: $($target.Name) GoTarget=$path moduloPrincipal=$recognized."
    }
    Require ($go.Count -gt 0 -and @($go | Where-Object { $_.Name -ceq 'stdlib' }).Count -gt 0) 'Cobertura Go/stdlib ausente.'
    $modulePrefix = if ($target.Name -ceq 'prometheus') { '^github[.]com/prometheus/' } else { '^github[.]com/grafana/' }
    Require (@($go | Where-Object { $_.Name -cmatch $modulePrefix }).Count -gt 0) 'Módulos Go da organização ausentes.'
    $completeOS = $completeOS -and $os.Count -gt 0 -and $raw.Metadata.OS.Family -cin @('alpine', 'debian', 'ubuntu', 'busybox')
    $results = @()
    $counts = @{UNKNOWN=0;LOW=0;MEDIUM=0;HIGH=0;CRITICAL=0}
    foreach ($result in $raw.Results) {
        Require ($result.Class -and $result.Type) 'Tipo/classe de resultado ausente.'
        $packages = @($result.Packages | Where-Object { $null -ne $_ } | ForEach-Object {
            Require ($_.Name -and $_.Version) 'Pacote sem identidade/versão.'
            @{Name=$_.Name;Version=$_.Version}
        })
        $findings = @($result.Vulnerabilities | Where-Object { $null -ne $_ } | ForEach-Object {
            Require ($_.VulnerabilityID -and $_.PkgName -and $_.InstalledVersion -and $counts.ContainsKey($_.Severity)) 'Achado sem identidade/severidade.'
            $counts[$_.Severity]++
            @{Id=$_.VulnerabilityID;Package=$_.PkgName;Installed=$_.InstalledVersion;Fixed=$_.FixedVersion;
                Severity=$_.Severity;Status=$_.Status;Url=$_.PrimaryURL}
        })
        $results += @{Class=$result.Class;Type=$result.Type;Packages=$packages;Vulnerabilities=$findings}
    }
    $reports += @{Name=$target.Name;Image=@{Id=$raw.Metadata.ImageID;Os=$raw.Metadata.ImageConfig.os;
        Architecture=$raw.Metadata.ImageConfig.architecture};Counts=$counts;Results=$results;
        Coverage=@{GoPackages=$go.Count;OSPackages=$os.Count;OSFamily=$raw.Metadata.OS.Family}}
    Write-Host "Observação achados: $($target.Name) UNKNOWN=$($counts.UNKNOWN) LOW=$($counts.LOW) MEDIUM=$($counts.MEDIUM) HIGH=$($counts.HIGH) CRITICAL=$($counts.CRITICAL)."
    foreach ($finding in @($results.Vulnerabilities | Where-Object { $_.Severity -cin @('HIGH', 'CRITICAL') })) {
        $fields = @($finding.Id, $finding.Package, $finding.Installed, $finding.Fixed)
        $safe = @($fields | ForEach-Object { if ([string]$_ -cmatch '^[A-Za-z0-9_./+():@~-]{1,180}$') { [string]$_ } else { 'ausente-ou-omitido' } })
        Write-Host "Observação achado: $($target.Name) $($finding.Severity) id=$($safe[0]) pacote=$($safe[1]) instalado=$($safe[2]) corrigido=$($safe[3])."
    }
    $summary += "| $($target.Name) | $($go.Count) | $($os.Count) | $($counts.UNKNOWN) | $($counts.LOW) | $($counts.MEDIUM) | $($counts.HIGH) | $($counts.CRITICAL) |"
}
Require $completeOS 'Cobertura OS ausente.'
$summary += @('', 'Escopo somente Prometheus/Grafana. Quatro alvos dos apps estão no artefato separado da mesma execução.',
    'Mesmas bases da execução; não somar repetições como vulnerabilidades únicas. Sem Env/config/history/tars/logs/relatórios brutos.')
Require (-not (Test-Path -LiteralPath $OutputDirectory) -or @(Get-ChildItem -LiteralPath $OutputDirectory -Force).Count -eq 0) 'Diretório de saída deve estar vazio.'
$null = New-Item -ItemType Directory -Force $OutputDirectory
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'inventory.json'),
    (@{Checkout=$manifest.Checkout;Scanner=$scanner;Reports=$reports} | ConvertTo-Json -Depth 20), $utf8)
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'images.json'),
    (@{Checkout=$manifest.Checkout;Scanner=$scanner;Targets=$safeTargets} | ConvertTo-Json -Depth 10), $utf8)
[IO.File]::WriteAllText((Join-Path $OutputDirectory 'checkout.txt'), $manifest.Checkout + "`n", $utf8)
[IO.File]::WriteAllLines((Join-Path $OutputDirectory 'summary.md'), $summary, $utf8)
