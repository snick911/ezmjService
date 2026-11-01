# Build script: compile, test, then package.
#   powershell -ExecutionPolicy Bypass -File build.ps1
#   powershell -ExecutionPolicy Bypass -File build.ps1 -Ezmj D:\path\to\ezmj
#
# Output in dist\:
#   ezmj-server-<version>.jar    runnable jar, needs Java 17+, includes the game engine
#
# The game engine (rules, table flow, bots) is compiled from the ezmj repository,
# by default the sibling folder ..\ezmj. Its UI is left out, so the server needs no display.
#
# The version comes from src\ezmj\server\version.properties.
# Stops before touching anything unless CHANGELOG.md has a section for that version
# and dist\ does not already hold it. See "打包流程" in README.md.

param([string]$Ezmj = '..\ezmj')

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Invoke-Tool {
    param([string]$Tool, [string[]]$Arguments)
    $ErrorActionPreference = 'Continue'
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Tool failed with exit code $LASTEXITCODE"
    }
}

function Read-Version {
    param([string]$Path)
    (Select-String -Path $Path -Pattern '^version=(.+)$').Matches[0].Groups[1].Value.Trim()
}

$engineVersionFile = Join-Path $Ezmj 'src\ezmj\version.properties'
if (-not (Test-Path $engineVersionFile)) {
    throw "Cannot find the ezmj repository at '$Ezmj'. Clone it next to this folder, or pass -Ezmj <path>."
}
$engineSource = (Resolve-Path (Join-Path $Ezmj 'src')).Path
$engineVersion = Read-Version $engineVersionFile
$version = Read-Version 'src\ezmj\server\version.properties'
Write-Host "== EZMJ Server $version (engine ezmj $engineVersion) =="

if (-not (Select-String -Path 'CHANGELOG.md' -Pattern "^## $([regex]::Escape($version))( |$)" -Quiet)) {
    throw "CHANGELOG.md has no '## $version' section. Record the changes before packaging."
}
if (Test-Path "dist\ezmj-server-$version.jar") {
    throw "$version is already packaged in dist\. Bump src\ezmj\server\version.properties, or delete dist\ to rebuild the same version."
}

foreach ($dir in 'build', 'dist') {
    if (Test-Path $dir) {
        Remove-Item -Recurse -Force -Confirm:$false $dir
    }
}
$classes = 'build\classes'
$testClasses = 'build\test-classes'
New-Item -ItemType Directory -Force $classes, $testClasses, 'dist' | Out-Null

Write-Host '== Compile =='
$uiFolder = Join-Path $engineSource 'ezmj\ui\'
$engineMain = Join-Path $engineSource 'ezmj\Main.java'
$engineSources = Get-ChildItem -Recurse $engineSource -Filter *.java |
    Where-Object { -not $_.FullName.StartsWith($uiFolder) -and $_.FullName -ne $engineMain } |
    ForEach-Object { $_.FullName }
$sources = Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName }
Invoke-Tool javac (@('--release', '17', '-encoding', 'UTF-8', '-d', $classes) + $engineSources + $sources)
Get-ChildItem -Recurse src -File | Where-Object { $_.Extension -ne '.java' } | ForEach-Object {
    $relative = $_.FullName.Substring((Resolve-Path src).Path.Length + 1)
    $target = Join-Path $classes $relative
    New-Item -ItemType Directory -Force (Split-Path $target) | Out-Null
    Copy-Item $_.FullName $target
}

Write-Host '== Test =='
$testSources = Get-ChildItem -Recurse test -Filter *.java | ForEach-Object { $_.FullName }
Invoke-Tool javac (@('--release', '17', '-encoding', 'UTF-8', '-cp', $classes, '-d', $testClasses) + $testSources)
Invoke-Tool java @('-Dfile.encoding=UTF-8', '-cp', "$classes;$testClasses", 'ezmj.server.AllTests')

Write-Host '== Jar =='
$jarName = "ezmj-server-$version.jar"
$manifest = 'build\MANIFEST.MF'
"Implementation-Title: EZMJ Server`r`nImplementation-Version: $version`r`nEzmj-Engine-Version: $engineVersion`r`n" | Set-Content -Encoding Ascii $manifest
Invoke-Tool jar @('--create', '--file', "dist\$jarName", '--manifest', $manifest, '--main-class', 'ezmj.server.EzmjServer', '-C', $classes, '.')

Write-Host '== Done =='
Get-ChildItem dist | Select-Object Name, Length | Format-Table -AutoSize | Out-String | Write-Host
