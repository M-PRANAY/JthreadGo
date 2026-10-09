[CmdletBinding()]
param(
    [string]$JdkHome,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\maven.ps1')
$mavenArguments = @('test')
if ($Offline) { $mavenArguments += '--offline' }
Invoke-JthreadGoMaven -ProjectRoot $PSScriptRoot -JdkHome $JdkHome -MavenArguments $mavenArguments
