<#
.SYNOPSIS
    Stands up the docker dependencies and runs the server with the interface in it.

.DESCRIPTION
    The PowerShell version of docker-deploy.sh:

        .\scripts\docker-deploy.ps1

    Since 0.9.9.8 the server serves the interface itself, so this runs the
    two together at http://localhost:8080, the way both images ship them. The
    bundle is built in the interface's container (there is no Node on the
    host) and packaged by -Pwith-ui. -am is not optional: without it a change
    in a module is invisible to the app build.

    ORKNUX_SECRET_KEY must be in the environment, or every stored secret fails
    to decrypt on read.
#>

$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
Set-Location "$repo\orknux-ui"

docker compose run --rm dev sh -c "npm install --no-fund --no-audit && npm run build"
if ($LASTEXITCODE -ne 0) { throw "interface build failed ($LASTEXITCODE)" }

Set-Location $repo

docker compose up -d
if ($LASTEXITCODE -ne 0) { throw "docker compose up -d failed ($LASTEXITCODE)" }

& "$repo\mvnw.cmd" spring-boot:run -Pwith-ui -pl app -am
if ($LASTEXITCODE -ne 0) { throw "spring-boot:run failed ($LASTEXITCODE)" }
