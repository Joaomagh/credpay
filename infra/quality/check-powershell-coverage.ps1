#requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$PackagePath)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$baseline=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pester-baseline.json') -Raw | ConvertFrom-Json
$package=(Resolve-Path -LiteralPath $PackagePath).Path
$folder=Join-Path $root '.local/sonar-powershell'
if (-not $package.StartsWith($folder + [IO.Path]::DirectorySeparatorChar, [StringComparison]::Ordinal)) {
    throw 'Pester package must remain inside the owned workspace directory.'
}
$expected=[Convert]::ToHexString([Convert]::FromBase64String($baseline.sha512Base64))
if ((Get-FileHash -LiteralPath $package -Algorithm SHA512).Hash -cne $expected) { throw 'Pester package checksum differs.' }
$module=Join-Path $folder ('module-' + [Guid]::NewGuid().ToString('N'))
[IO.Compression.ZipFile]::ExtractToDirectory($package,$module)
Import-Module (Join-Path $module 'Pester.psd1') -ErrorAction Stop
if ((Get-Module Pester).Version.ToString() -cne $baseline.version) { throw 'Unexpected Pester version.' }
$config=New-PesterConfiguration
$config.Run.Path=Join-Path $PSScriptRoot 'check-kubernetes-secret.Tests.ps1'
$config.Run.RepoRoot=$root
$config.Run.PassThru=$true
$config.CodeCoverage.Enabled=$true
$config.CodeCoverage.Path=@((Join-Path $root 'infra/k8s/disposable-secret.ps1'),(Join-Path $root 'infra/k8s/kubectl.ps1'),(Join-Path $root 'infra/security/report.ps1'))
$config.CodeCoverage.OutputFormat='Cobertura'
$config.CodeCoverage.OutputPath=Join-Path $folder 'coverage.xml'
$config.CodeCoverage.CoveragePercentTarget=80
$result=Invoke-Pester -Configuration $config
if ($result.Result -cne 'Passed' -or $result.SkippedCount -ne 0 -or $result.CodeCoverage.CoveragePercent -lt 80) {
    throw 'PowerShell controls or measured command coverage failed.'
}
Write-Host 'PowerShell: real execution coverage collected; no Docker, external network or synthetic hits.'
