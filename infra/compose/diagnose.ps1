#requires -Version 7.3
[CmdletBinding()]
param([Parameter(Mandatory)][ValidateSet('Run','Remove')][string]$Action,
    [string]$ImageTag=$env:CREDPAY_IMAGE_TAG)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'diagnostic-local.ps1')
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
try { Invoke-CredPayDiagnosticAction $Action $ImageTag $root }
catch {
    $phase=if($script:diagnosticLaunchPhase -cmatch '^[a-z-]{1,40}$') { $script:diagnosticLaunchPhase } else { 'entry' }
    throw "Diagnóstico local interrompido; fase=$phase; sessão/evidências preservadas; detalhes brutos omitidos."
}
