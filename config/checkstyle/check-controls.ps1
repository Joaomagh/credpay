#requires -Version 7.3
param([ValidateSet('processamento-service', 'transacoes-service')][string]$Module = 'processamento-service')
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$directory = Join-Path $root ('.local/checkstyle/' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path (Join-Path $directory 'src/main/java') -Force
$utf8 = [Text.UTF8Encoding]::new($false)
# Copiar o plugin real evita um controle com baseline/regras diferentes do build.
[xml]$modulePom = [IO.File]::ReadAllText((Join-Path $root "$Module/pom.xml"))
$plugin = $modulePom.project.build.plugins.plugin | Where-Object { $_.artifactId -ceq 'maven-checkstyle-plugin' }
if (@($plugin).Count -ne 1) { throw 'Plugin Checkstyle único não encontrado.' }
$configPath = [Security.SecurityElement]::Escape((Join-Path $PSScriptRoot 'checkstyle.xml'))
$pluginXml = $plugin.OuterXml.Replace(' xmlns="http://maven.apache.org/POM/4.0.0"', '').Replace(
    '${project.basedir}/../config/checkstyle/checkstyle.xml', $configPath)
$pom = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>' +
    '<groupId>br.com.credpay.controls</groupId><artifactId>checkstyle-controls</artifactId><version>1</version>' +
    '<properties><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>' +
    '<build><plugins>' + $pluginXml + '</plugins></build></project>'
$pomPath = Join-Path $directory 'pom.xml'
[IO.File]::WriteAllText($pomPath, $pom, $utf8)
$sourcePath = Join-Path $directory 'src/main/java/Probe.java'
$wrapper = Join-Path $root $(if ($IsWindows) { "$Module/mvnw.cmd" } else { "$Module/mvnw" })
$valid = @'
record Probe(String value) {
    String describe(Object input) {
        return switch (input) {
            case String text -> """
                    ação
                    """ + text;
            case null -> "ausente";
            default -> value;
        };
    }
}
'@
$cases = @(
    @{Name='Java21-LF'; Source=$valid + "`n"; Rule=$null},
    @{Name='Java21-CRLF'; Source=($valid -replace '\r?\n', "`r`n") + "`r`n"; Rule=$null},
    @{Name='interno'; Source="import jdk.internal.misc.Unsafe;`nclass Probe { Unsafe value; }`n"; Rule='IllegalImport'},
    @{Name='star'; Source="import java.util.*;`nclass Probe { List<String> value; }`n"; Rule='AvoidStarImport'},
    @{Name='unused'; Source="import java.util.List;`nclass Probe {}`n"; Rule='UnusedImports'},
    @{Name='tab'; Source="class Probe {`n`tint value;`n}`n"; Rule='FileTabCharacter'},
    @{Name='newline'; Source='class Probe {}'; Rule='NewlineAtEndOfFile'}
)
foreach ($case in $cases) {
    [IO.File]::WriteAllText($sourcePath, $case.Source, $utf8)
    $output = & $wrapper -f $pomPath --batch-mode --no-transfer-progress validate 2>&1 | Out-String
    $code = $LASTEXITCODE
    [IO.File]::WriteAllText((Join-Path $directory ($case.Name + '.log')), $output, $utf8)
    if ($null -eq $case.Rule) {
        if ($code -ne 0 -or $output -notmatch '0 Checkstyle violations') { throw "Controle válido falhou: $($case.Name). Log em .local/checkstyle." }
    } elseif ($code -eq 0 -or $output -notmatch ([regex]::Escape($case.Rule))) {
        throw "Controle não rejeitou pela regra esperada: $($case.Rule). Log em .local/checkstyle."
    }
    Write-Host "Checkstyle: controle $($case.Name) passou."
}
