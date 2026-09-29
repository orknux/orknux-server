<#
.SYNOPSIS
    Retakes every picture in the manual, against an installation made for it.

.DESCRIPTION
    One command. It builds the jar, stands up an installation of its own, seeds
    the demonstration workspace into it, photographs it, and takes it down again:

        .\scripts\screenshots.ps1

    The pictures land in orknux-ui/public/screens in this working copy. Commit
    them there and move the submodule pin, as any other change to the interface.

    Five minutes, of which half is the seed talking to a model and the capture
    walking fifty pages; seven the first time, while the interface container
    fetches its dependencies and a browser into volumes it then keeps.

    WHY THERE IS AN INSTALLATION AT ALL

    seed-demo.mjs does not add to a workspace, it deletes and rebuilds one, and
    the capture then signs in and walks the whole product. Pointed at a
    developer's own server that is a script editing somebody's real data - which
    is why this used to be done by building Dockerfile.one, a full Maven build
    inside Docker, and photographing the container. That was twenty minutes of
    the forty this job took, to produce a server the reactor had just produced
    anyway. So the jar out of app/target is run on the host instead, against a
    SQLite file in a temporary directory that is deleted when the run ends. The
    isolation is the point and it is kept; only the image is gone.

    WHAT IS IN THE OTHER HALF

    scripts/screens-compose.yaml, which holds what could not be a jar: a
    directory, so alice signs in through one and the users page has its External
    row; a Temporal, so Monitoring draws the row the manual describes; a Postgres
    for Temporal to store in; and a vite container that serves the interface and
    runs both scripts. Its own project name and its own ports, so it cannot meet
    the containers a developer already has up.

    THINGS THAT COST AN AFTERNOON TO LEARN

    - The jar is rebuilt every run, and not reused. Monitoring prints the
      server's version, so a jar from before the release bump photographs the
      wrong number onto a page that is otherwise right.
    - ORKNUX_ALLOWED_ORIGINS has to name the origin the browser actually uses.
      Where it does not, every GraphQL call from the page answers 403 while curl
      is served perfectly, and it reads as a broken build. So there is one port
      for the interface, decided here and handed to the compose file, and vite
      listens on it inside the container as well as outside: an origin that has
      only one spelling cannot be allowed in the wrong one.
    - The model has to have tool support. gemma3:12b has none: the agent pictures
      come out of a run that could not call anything. gemma4:e4b does.
    - Run this from PowerShell. Under Git Bash, MSYS rewrites a path that looks
      like one - the SQLite URL and the LDAP DN both do - and the damage turns up
      inside a screenshot rather than as an error.
    - orknux-ui/package.json carries the version the footer prints on every
      signed-in screen, so it is bumped to the release being photographed before
      any of this. This script refuses to run until it matches the pom.

.PARAMETER Endpoint
    Where a model answers, as the server reaches it. The server runs on this
    machine, so the default is Ollama's own address here. Give it the bare root:
    the provider type supplies the rest.

.PARAMETER Model
    The model id to ask for. It must have tool support; see above.

.PARAMETER Only
    A comma-separated list of picture names, passed to ORKNUX_ONLY. For fixing
    one shot without waiting for fifty.

.PARAMETER SkipBuild
    Use the jar already in app/target. For a second run in the same afternoon,
    against a tree that has not moved. Never for a release.

.PARAMETER KeepData
    Leave the temporary installation - database, logs, attachments - on disk, and
    say where. For working out why a picture came out wrong.

.PARAMETER ServerPort
    Where the jar listens. Anything nobody else on this machine is on; both this
    and -UiPort are checked before anything is built, because the alternative is
    a JVM that says "port already in use" into a log file nobody is reading.

.PARAMETER UiPort
    Where the interface is served, inside the container and out - they have to be
    the same number, because it is the origin the server is told to allow.

.PARAMETER DrawPort
    Where the stand-in drawing model listens, as the server on this machine
    reaches it. See screens-draw.mjs: the manual's chapters about drawing are
    photographed off pictures the product really drew, with this as its model.
#>
#Requires -Version 5.1
[CmdletBinding()]
param(
    [string] $Endpoint = 'http://localhost:11434',
    [string] $Model = 'gemma4:e4b',
    [string] $Only = '',
    [switch] $SkipBuild,
    [switch] $KeepData,
    # Not 8080 and 5173, which are the development installation's. Neither number
    # is sacred and both are checked before anything is built: the first choice
    # here was 8099, which turned out to have another server of this product on
    # it, and what that cost was a Maven build followed by a JVM refusing to bind
    # into a log file nobody was reading.
    [int] $ServerPort = 8181,
    [int] $UiPort = 5199,
    [int] $DrawPort = 8299
)

$ErrorActionPreference = 'Stop'
$started = Get-Date

$root = Split-Path -Parent $PSScriptRoot
$compose = Join-Path $PSScriptRoot 'screens-compose.yaml'

# The one port each, decided here and handed to compose, which defaults to the
# same two. Nothing may invent a third number.
$env:ORKNUX_SCREENS_SERVER_PORT = "$ServerPort"
$env:ORKNUX_SCREENS_UI_PORT = "$UiPort"
$env:ORKNUX_SCREENS_DRAW_PORT = "$DrawPort"
$UI_ORIGIN = "http://localhost:$UiPort"

function Step($message) {
    $elapsed = (Get-Date) - $started
    Write-Host ("[{0:mm\:ss}] {1}" -f $elapsed, $message) -ForegroundColor Cyan
}

function Invoke-Checked {
    param([string] $What, [scriptblock] $Command)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$What failed with exit code $LASTEXITCODE." }
}

# Up, for anything that speaks HTTP. A status code is a status code: 401 from the
# sign-in endpoint is the server answering, which is all this is asking. `000` is
# curl's way of saying there was nothing there to ask.
#
# curl rather than Invoke-WebRequest, which was what this did first and which sat
# there timing out against a port curl was being answered on from the same shell,
# on this machine. Whatever is between PowerShell's HTTP client and the loopback
# address here, a wait that reports "never answered" about something that is
# plainly answering is worse than no wait at all. curl.exe has shipped with
# Windows since 1803 and is what scripts/verify-image.sh already uses.
function Wait-ForHttp {
    param([string] $Url, [int] $Seconds, [string] $What)
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $code = & curl.exe -s -o NUL -m 5 -w '%{http_code}' $Url
        if ($code -ne '000') { return }
        Start-Sleep -Seconds 2
    }
    throw "$What never answered at $Url."
}

# Free, and asked by binding rather than by reading a table, because a table only
# knows what this account is allowed to see. The failure this replaces was a JVM
# reporting "port already in use" twelve seconds into a run that had already
# built a jar, into a log file the run was not showing anybody.
function Assert-PortFree {
    param([int] $Port, [string] $What, [string] $Parameter)
    try {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Any, $Port)
        $listener.Start()
        $listener.Stop()
    } catch {
        throw "Port $Port is taken, and $What needs it. Pass $Parameter with one that is not."
    }
}

# --------------------------------------------------------------- preconditions

# The two halves are released together under one number and the footer prints the
# interface's on every screen, so a set taken while they disagree is a set that
# has to be taken again. Checked before the build, which is the expensive part.
$version = ([xml](Get-Content (Join-Path $root 'pom.xml'))).project.version
$uiVersion = (Get-Content (Join-Path $root 'orknux-ui/package.json') -Raw | ConvertFrom-Json).version
if ($version -ne $uiVersion) {
    throw "pom.xml is $version and orknux-ui/package.json is $uiVersion. " +
          "Bump the interface to the release being photographed first: the footer carries it on every screen."
}
Assert-PortFree -Port $ServerPort -What 'the server' -Parameter '-ServerPort'
Assert-PortFree -Port $UiPort -What 'the interface' -Parameter '-UiPort'
Assert-PortFree -Port $DrawPort -What 'the drawing model' -Parameter '-DrawPort'

Step "Photographing $version."

# ------------------------------------------------------- the installation, part one
#
# Started before the build rather than after it. Temporal applies its schema on
# every start and the interface container installs its dependencies and a
# browser; that is a few minutes which may as well be the same few minutes Maven
# is using. Waited for further down.
Step 'Starting the directory, Temporal, the drawing model and the interface.'
Invoke-Checked 'docker compose up' { docker compose -f $compose up -d }

# ------------------------------------------------------------------------ the jar

if ($SkipBuild) {
    Step 'Using the jar already in app/target, as asked.'
} else {
    Step 'Building the jar.'
    Invoke-Checked 'Maven' { & (Join-Path $root 'mvnw.cmd') -q -DskipTests -pl app -am package }
}

$jar = Get-ChildItem -Path (Join-Path $root 'app/target') -Filter "orknux-app-$version.jar" -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -eq $jar) { throw "No app/target/orknux-app-$version.jar. Run without -SkipBuild." }

# ---------------------------------------------------- the installation, part two

$data = Join-Path ([IO.Path]::GetTempPath()) ("orknux-screens-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $data | Out-Null

# Forward slashes, and an absolute path. SQLite is handed this verbatim.
$dbPath = ($data -replace '\\', '/') + '/orknux.db'

# A key of its own, generated and thrown away with the database it protects. The
# server reads it on first use, so without one it starts, reports itself healthy,
# and fails on the first credential the seed stores.
$keyBytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($keyBytes)

$env:ORKNUX_PORT = "$ServerPort"
$env:ORKNUX_DB_URL = "jdbc:sqlite:$dbPath"
$env:ORKNUX_SECRET_KEY = [Convert]::ToBase64String($keyBytes)
$env:ORKNUX_LDAP_URLS = 'ldap://localhost:3899'
$env:ORKNUX_TEMPORAL_TARGET = 'localhost:7299'
# The origin the browser will actually be on. See the note at the top.
$env:ORKNUX_ALLOWED_ORIGINS = $UI_ORIGIN
$env:ORKNUX_BASE_URL = $UI_ORIGIN
# Left alone deliberately: orknux.attachments.location stays `data/attachments`,
# relative to the working directory below, because that is the string the Doctor
# page prints and the capture's redaction is written against it.

$server = $null
try {
    Step "Starting the server on $ServerPort, storing in $dbPath."
    $server = Start-Process -FilePath 'java' -ArgumentList '-jar', $jar.FullName `
        -WorkingDirectory $data -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $data 'server.log') `
        -RedirectStandardError (Join-Path $data 'server.err')

    # Everything the server needs has to be up before it is: it reads the
    # directory at the first sign-in and Temporal at the first run.
    Step 'Waiting for the containers.'
    Invoke-Checked 'docker compose up --wait' { docker compose -f $compose up -d --wait --wait-timeout 900 }
    Wait-ForHttp -Url "$UI_ORIGIN/" -Seconds 600 -What 'The interface'
    Wait-ForHttp -Url "http://localhost:$ServerPort/api/session" -Seconds 300 -What 'The server'

    Step "Seeding the demonstration workspace ($Model at $Endpoint)."
    Invoke-Checked 'The seed' {
        docker compose -f $compose exec -T `
            -e "ORKNUX_DEMO_ENDPOINT=$Endpoint" -e "ORKNUX_DEMO_MODEL=$Model" `
            -e "ORKNUX_DEMO_IMAGE_ENDPOINT=http://localhost:$DrawPort" `
            ui node scripts/seed-demo.mjs
    }

    Step 'Taking the pictures.'
    Invoke-Checked 'The capture' {
        docker compose -f $compose exec -T -e "ORKNUX_ONLY=$Only" ui node scripts/screenshots.mjs
    }

    Step 'Done. They are in orknux-ui/public/screens; git status there says which moved.'
} finally {
    if ($null -ne $server -and -not $server.HasExited) {
        Stop-Process -Id $server.Id -Force
        $server.WaitForExit(30000) | Out-Null
    }
    # Without -v: the browser, the dependency tree and Temporal's schema are worth
    # keeping between runs, and none of them is anybody's data.
    docker compose -f $compose down --remove-orphans | Out-Null

    if ($KeepData) {
        Step "The installation is still at $data."
    } else {
        Remove-Item -Recurse -Force $data -ErrorAction SilentlyContinue
    }
}
