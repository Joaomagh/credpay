#requires -Version 7.3
[CmdletBinding()]
param([Parameter(Mandatory)][ValidateSet('Run','Remove')][string]$Action,
    [string]$ImageTag=$env:CREDPAY_IMAGE_TAG,[int]$TxPort=0,[int]$ProcPort=0)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'diagnostic-local.ps1')
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
try { Invoke-CredPayDiagnosticAction $Action $ImageTag $root $TxPort $ProcPort }
catch { throw 'Diagnóstico local interrompido; sessão/evidências preservadas. Envie somente a última fase/resumo seguro; não exporte logs/env.' }
