[CmdletBinding()]
param(
    [string]$JdkHome,
    [switch]$SkipTests,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scripts\maven.ps1')
$mavenArguments = @('verify')
if ($SkipTests) { $mavenArguments += '-DskipTests' }
if ($Offline) { $mavenArguments += '--offline' }
Invoke-JthreadGoMaven -ProjectRoot $PSScriptRoot -JdkHome $JdkHome -MavenArguments $mavenArguments
