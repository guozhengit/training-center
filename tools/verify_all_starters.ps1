[CmdletBinding()]
param(
    [string]$JavaHome = 'D:\jdk\jdk-17.0.12',
    [string]$MavenExecutable = 'D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd',
    [string]$PythonExecutable = 'C:\Users\gyz\AppData\Local\Microsoft\WindowsApps\python.exe',
    [string]$ExitCodeFile = '',
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$workspaceRoot = [System.IO.Path]::GetFullPath(
    [System.IO.Path]::Combine($PSScriptRoot, '..', '..'))
$reactorPom = [System.IO.Path]::Combine($workspaceRoot, 'training-center', 'pom.xml')
$javaHomeFull = [System.IO.Path]::GetFullPath($JavaHome)
$mavenFull = [System.IO.Path]::GetFullPath($MavenExecutable)
$pythonFull = [System.IO.Path]::GetFullPath($PythonExecutable)

if (-not [System.IO.Directory]::Exists($javaHomeFull)) {
    throw "Configured JDK home does not exist."
}
if (-not [System.IO.File]::Exists($mavenFull)) {
    throw "Configured Maven executable does not exist."
}
if (-not [System.IO.File]::Exists($pythonFull)) {
    throw "Configured Python executable does not exist."
}
if (-not [System.IO.File]::Exists($reactorPom)) {
    throw "Training Center reactor was not found relative to the script."
}

$javaExecutable = [System.IO.Path]::Combine($javaHomeFull, 'bin', 'java.exe')
$savedErrorPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVersion = & $javaExecutable '-version' 2>&1
$javaExit = $LASTEXITCODE
$ErrorActionPreference = $savedErrorPreference
if ($javaExit -ne 0 -or (($javaVersion -join "`n") -notmatch 'version "17\.')) {
    throw "Task 21 requires JDK 17."
}

$ErrorActionPreference = 'Continue'
$mavenVersion = & $mavenFull '-version' 2>&1
$mavenVersionExit = $LASTEXITCODE
$ErrorActionPreference = $savedErrorPreference
if ($mavenVersionExit -ne 0 -or (($mavenVersion -join "`n") -notmatch 'Apache Maven 3\.9\.9')) {
    throw "Task 21 requires Maven 3.9.9."
}

$ErrorActionPreference = 'Continue'
$pythonVersion = & $pythonFull '--version' 2>&1
$pythonExit = $LASTEXITCODE
$ErrorActionPreference = $savedErrorPreference
if ($pythonExit -ne 0 -or (($pythonVersion -join "`n") -notmatch 'Python 3\.13\.')) {
    throw "Task 21 requires Python 3.13."
}

if ($ValidateOnly) {
    Write-Output 'Task 21 runtime validation OK.'
    if (-not [string]::IsNullOrWhiteSpace($ExitCodeFile)) {
        [System.IO.File]::WriteAllText(
            [System.IO.Path]::GetFullPath($ExitCodeFile),
            '0',
            (New-Object System.Text.UTF8Encoding($false)))
    }
    exit 0
}

$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:Path
try {
    $env:JAVA_HOME = $javaHomeFull
    $pathParts = @(
        [System.IO.Path]::GetDirectoryName($mavenFull),
        [System.IO.Path]::GetDirectoryName($pythonFull),
        [System.IO.Path]::Combine($javaHomeFull, 'bin'),
        $originalPath
    )
    $env:Path = $pathParts -join [System.IO.Path]::PathSeparator

    & $mavenFull `
        '-f' $reactorPom `
        '-pl' 'training-core' `
        '-Dtest=RealSandboxMatrixTest' `
        '-Dreal.sandbox.matrix=true' `
        "-Dtraining.workspace=$workspaceRoot" `
        '-Dsurefire.failIfNoSpecifiedTests=false' `
        'test'
    $mavenExit = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $originalJavaHome
    $env:Path = $originalPath
}

if (-not [string]::IsNullOrWhiteSpace($ExitCodeFile)) {
    [System.IO.File]::WriteAllText(
        [System.IO.Path]::GetFullPath($ExitCodeFile),
        [string]$mavenExit,
        (New-Object System.Text.UTF8Encoding($false)))
}
exit $mavenExit
