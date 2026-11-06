Describe 'Scanner controller with an offline executable fixture' {
    BeforeAll {
        $workspace=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
        $scanScript=Join-Path $workspace 'infra/security/scan.ps1'
        $rootQuery=Join-Path (Split-Path $scanScript) '../..'
        $tokens=$null; $errors=$null
        $ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $workspace 'infra/security/check-reports.ps1'),[ref]$tokens,[ref]$errors)
        if($errors.Count) { throw 'Report fixture did not parse.' }
        foreach($name in @('Save-Json','Reset-Fixture')) {
            $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$true)
            . ([scriptblock]::Create($definition.Extent.Text))
        }
    }
    BeforeEach {
        $fixtureRoot=Join-Path $workspace ('.local/security/runtime-controls/'+[Guid]::NewGuid().ToString('N'))
        $directory=Join-Path $fixtureRoot 'expected'
        $raw=Join-Path $fixtureRoot '.local/security/raw'
        $bin=Join-Path $fixtureRoot '.local/security/bin'
        foreach($path in @($directory,$raw,$bin)) { $null=New-Item -ItemType Directory -Path $path -Force }
        $utf8=[Text.UTF8Encoding]::new($false)
        Reset-Fixture arm64
        foreach($file in @('checkout.txt','jars.sha256','transacoes-image.txt','processamento-image.txt')) {
            Copy-Item -LiteralPath (Join-Path $directory $file) -Destination (Join-Path $raw $file)
        }
        $fake=Join-Path $fixtureRoot 'fake-scanner.ps1'
        $code=@'
$ErrorActionPreference='Stop'
$arguments=@($args)
Add-Content -LiteralPath (Join-Path $PSScriptRoot 'calls.txt') -Value $arguments[0]
if(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'fail-download')) { exit 7 }
if($arguments -contains 'version') { Get-Content -LiteralPath (Join-Path $PSScriptRoot 'expected/scanner.json') -Raw; exit 0 }
if($arguments -contains '--download-db-only' -or $arguments -contains '--download-java-db-only') { exit 0 }
$index=[Array]::IndexOf($arguments,'--output')
if($index -lt 0 -or $arguments -notcontains '--list-all-pkgs' -or $arguments -contains '--ignore-unfixed') { exit 9 }
$destination=[IO.Path]::GetFullPath($arguments[$index+1])
$owned=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '.local/security/raw'))+[IO.Path]::DirectorySeparatorChar
if(-not $destination.StartsWith($owned,[StringComparison]::Ordinal)) { exit 10 }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot ('expected/'+[IO.Path]::GetFileName($destination))) -Destination $destination
exit 0
'@
        [IO.File]::WriteAllText($fake,$code,$utf8)
        if($IsWindows) {
            $launcher='@echo off'+"`r`n"+'"'+(Join-Path $PSHOME 'pwsh.exe')+'" -NoLogo -NoProfile -File "'+$fake+'" %*'+"`r`n"
            [IO.File]::WriteAllText((Join-Path $bin 'trivy.cmd'),$launcher,$utf8)
        } else {
            $launcher='#!/usr/bin/env pwsh'+"`n"+$code.Replace('$PSScriptRoot',"'$($fixtureRoot.Replace("'","''"))'")
            $executable=Join-Path $bin 'trivy'
            [IO.File]::WriteAllText($executable,$launcher,$utf8)
            [IO.File]::SetUnixFileMode($executable,[IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite -bor [IO.UnixFileMode]::UserExecute)
        }
        Mock Resolve-Path { [pscustomobject]@{Path=$fixtureRoot} } -ParameterFilter { $Path -ceq $rootQuery }
        $output=Join-Path $fixtureRoot '.local/security/reports/inventory.json'
        $savedSummary=$env:GITHUB_STEP_SUMMARY
        $env:GITHUB_STEP_SUMMARY=Join-Path $fixtureRoot 'job-summary.md'
    }
    AfterEach { $env:GITHUB_STEP_SUMMARY=$savedSummary }
    It 'runs four scans and forwards the ARM64 profile to the real report validator' {
        & $scanScript -ExpectedArchitecture arm64
        $published=Get-Content -LiteralPath $output -Raw
        $inventory=$published | ConvertFrom-Json -AsHashtable
        if($inventory.Architecture -cne 'arm64' -or $inventory.Reports.Count -ne 4 -or $published.Contains('SYNTHETIC_ENV_MUST_NOT_BE_PUBLISHED')) { throw 'Controller lost architecture/reports or exposed environment.' }
        if(@($inventory.Reports | Where-Object { $_.Counts.HIGH -ne 1 }).Count -ne 0) { throw 'Controller suppressed findings.' }
        if(@(Get-Content -LiteralPath (Join-Path $fixtureRoot 'calls.txt')).Count -ne 7) { throw 'Controller did not execute the complete scan protocol.' }
    }
    It 'refuses a report profile inconsistent with the actual ARM64 manifest' {
        $message=''
        try { & $scanScript -ExpectedArchitecture amd64 } catch { $message=$_.Exception.Message }
        if($message -notlike '*Identidade/arquitetura*' -or (Test-Path -LiteralPath $output)) { throw 'Controller accepted an inconsistent architecture.' }
    }
    It 'stops without publishing an inventory after scanner failure' {
        $null=New-Item -ItemType File -Path (Join-Path $fixtureRoot 'fail-download')
        $message=''
        try { & $scanScript -ExpectedArchitecture arm64 } catch { $message=$_.Exception.Message }
        if($message -notlike '*Download da base falhou*' -or (Test-Path -LiteralPath $output)) { throw 'Scanner failure became a valid report.' }
    }
}
