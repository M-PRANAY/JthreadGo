[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$OutputDirectory,
    [string]$HeadersDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot 'target\native' }
if (-not $HeadersDirectory) { $HeadersDirectory = Join-Path $projectRoot 'target\generated-native-headers' }
if (-not $JdkHome) { throw 'Set JAVA_HOME to a JDK 25 installation, or pass -JdkHome.' }

$JdkHome = [System.IO.Path]::GetFullPath($JdkHome)
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$HeadersDirectory = [System.IO.Path]::GetFullPath($HeadersDirectory)
$nativeSource = Join-Path $projectRoot 'native\jthreadgo.cpp'
$includeRoot = Join-Path $JdkHome 'include'
$includeWin = Join-Path $includeRoot 'win32'
foreach ($required in @(
    (Join-Path $includeRoot 'jvmti.h'),
    (Join-Path $includeWin 'jni_md.h'),
    (Join-Path $HeadersDirectory 'org_jthreadgo_JthreadGo.h'),
    $nativeSource
)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Missing build input: $required. Run Maven compile before this script."
    }
}

$releaseFile = Join-Path $JdkHome 'release'
if (Test-Path -LiteralPath $releaseFile) {
    $architecture = Get-Content -LiteralPath $releaseFile | Where-Object { $_ -match '^OS_ARCH=' }
    if ($architecture -and $architecture -notmatch '"(amd64|x86_64)"') {
        throw 'The Windows native build currently supports x64 JDKs only.'
    }
}

$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path -LiteralPath $vswhere -PathType Leaf)) {
    throw 'Install Visual Studio Build Tools with the Desktop development with C++ workload.'
}
$vsRoot = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if ($LASTEXITCODE -ne 0 -or -not $vsRoot) { throw 'MSVC x64 build tools were not found.' }
$vsDevCmd = Join-Path ($vsRoot | Select-Object -First 1) 'Common7\Tools\VsDevCmd.bat'

# Only compiler operations go through cmd.exe. Reject characters that cmd could
# expand inside quoted paths; all filesystem operations use PowerShell directly.
foreach ($pathValue in @($projectRoot, $JdkHome, $OutputDirectory, $HeadersDirectory, $vsDevCmd)) {
    if ($pathValue -match '[%\r\n"!]') { throw 'Build paths cannot contain %, !, quotes, or newlines.' }
}
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$library = Join-Path $OutputDirectory 'jthreadgo.dll'
$objectFile = Join-Path $OutputDirectory 'jthreadgo.obj'
$importLibrary = Join-Path $OutputDirectory 'jthreadgo.lib'
$nativeBuild = Join-Path $OutputDirectory 'compile-native.cmd'
$nativeCommands = @"
@echo off
call "$vsDevCmd" -no_logo -arch=x64 -host_arch=x64
if errorlevel 1 exit /b 1
cl /nologo /LD /MT /O2 /EHsc /std:c++17 /W4 /I"$includeRoot" /I"$includeWin" /I"$HeadersDirectory" /Fo"$objectFile" "$nativeSource" /link /OUT:"$library" /IMPLIB:"$importLibrary"
exit /b %errorlevel%
"@
[System.IO.File]::WriteAllText($nativeBuild, $nativeCommands, [System.Text.Encoding]::Default)
Push-Location -LiteralPath $OutputDirectory
try {
    & $env:ComSpec /d /v:off /c "`"$nativeBuild`""
    if ($LASTEXITCODE -ne 0) { throw 'Native agent compilation failed.' }
} finally {
    Pop-Location
}
Write-Host "Built $library"
