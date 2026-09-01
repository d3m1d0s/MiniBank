<#
    Brings the database up and returns only once it is ready to take a connection.

        .\scripts\up.ps1

    --wait is the whole point of it. docker compose up -d returns as soon as the container has
    been started, which on a fresh volume is a good half minute before PostgreSQL will answer:
    initdb is still running, and the first thing a reader did with the old instructions was watch
    docker compose ps until the word healthy appeared. The healthcheck was already there and
    already correct, and --wait is what makes docker compose do that watching. On a stack that is
    already up this returns at once.

    Git Bash has its own copy, scripts/up.sh, that does the same thing.

    The API and the two front ends are not started here. Each is a process that holds a terminal
    and prints to it, and there are three of them; the commands are printed below instead, with
    the port this run actually published filled in.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot

Push-Location $root
try {
    Write-Host 'up: starting the database and waiting for its healthcheck'
    docker compose up -d --wait
    if ($LASTEXITCODE -ne 0) {
        throw "up: docker compose up --wait exit $LASTEXITCODE"
    }

    # Asked rather than assumed: the published port is ${MINIBANK_DB_PORT:-55432}, and a .env or
    # an environment variable can have moved it. Every command printed below carries a port, so
    # printing one that is merely the default would be printing a guess.
    $published = docker compose port db 5432
    if ($LASTEXITCODE -eq 0 -and $published -match ':(\d+)\s*$') {
        $port = $Matches[1]
    }
    else {
        $port = '55432'
    }
}
finally {
    Pop-Location
}

Write-Host "up: PostgreSQL is ready on localhost:$port, databases minibank and minibank_test"
Write-Host ''
Write-Host 'Three windows are left. Each of these holds the terminal it runs in:'
Write-Host ''
Write-Host "  .\mvnw.cmd -B spring-boot:run `"-Dspring-boot.run.jvmArguments=-Dminibank.storage=sql -Dminibank.sql.url=jdbc:postgresql://localhost:$port/minibank`""
Write-Host '  cd minibank-web; npm install; npm run dev'
Write-Host '  cd minibank-fraud-web; npm install; npm run dev'
Write-Host ''
Write-Host 'Then the customer front end is on http://localhost:5173 and the workstation on http://localhost:5174.'
