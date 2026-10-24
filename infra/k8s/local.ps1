#requires -Version 7.3
[CmdletBinding()]
param([Parameter(Mandatory)][ValidateSet('Create','Validate','Remove')][string]$Action,
    [string]$ImageTag = $env:CREDPAY_IMAGE_TAG)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'local-runtime.ps1')
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
. (Join-Path $PSScriptRoot 'local-actions.ps1')
Invoke-CredPayLocalAction $Action $ImageTag $root
