# Shared implementation for the convenience wrappers. Maven owns the build.
function Invoke-JthreadGoMaven {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [string]$JdkHome,
        [Parameter(Mandatory = $true)][string[]]$MavenArguments
    )
    $ErrorActionPreference = 'Stop'
    $maven = Get-Command mvn -CommandType Application -ErrorAction Stop | Select-Object -First 1
    $previousJdk = $env:JAVA_HOME
    if ($JdkHome) {
        $JdkHome = (Resolve-Path -LiteralPath $JdkHome -ErrorAction Stop).Path
        if (-not (Test-Path -LiteralPath (Join-Path $JdkHome 'include\jvmti.h'))) {
            throw 'JdkHome must point to a full JDK installation containing include/jvmti.h.'
        }
    }
    Push-Location -LiteralPath $ProjectRoot
    try {
        if ($JdkHome) { $env:JAVA_HOME = $JdkHome }
        & $maven.Source --batch-mode --no-transfer-progress @MavenArguments
        if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE." }
    } finally {
        $env:JAVA_HOME = $previousJdk
        Pop-Location
    }
}
