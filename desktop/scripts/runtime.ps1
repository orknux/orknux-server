# scripts/runtime.sh, for Windows. See there for why the module list is the image's.
#   .\scripts\runtime.ps1 [-Jdk <JDK home>]   (default: $env:JAVA_HOME)
param([string]$Jdk = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
if (-not $Jdk) { throw 'Give -Jdk or set JAVA_HOME' }
Set-Location (Join-Path $PSScriptRoot '..')
$modules = ((Get-Content scripts/modules.txt -Raw) -replace '\s', '')
if (Test-Path resources/jre) { Remove-Item -Recurse -Force resources/jre }
& (Join-Path $Jdk 'bin/jlink.exe') --add-modules $modules --strip-debug --no-man-pages --no-header-files --compress zip-6 --output resources/jre
if ($LASTEXITCODE -ne 0) { throw "jlink failed with $LASTEXITCODE" }
Write-Output "runtime at resources/jre"
