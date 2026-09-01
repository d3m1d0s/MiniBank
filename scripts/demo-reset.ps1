<#
    Empties the MiniBank tables so the next start of the API seeds the demo dataset again.
    db/demo-reset.sql says what it removes and why the logins go with the rest.

        .\scripts\demo-reset.ps1
        .\scripts\demo-reset.ps1 -Database minibank_test

    The alternative this replaces is docker compose down -v, which throws the volume away and
    takes both databases and every migration applied to them with it.

    It does not restart the API, and cannot: the API is started by hand, in a window of its own,
    and this script has no way to know which. Seeding runs once at startup under the demo
    profile, so the screens stay empty until that process comes back.
#>
[CmdletBinding()]
param(
    [string] $Database = 'minibank'
)

$ErrorActionPreference = 'Stop'

$root   = Split-Path -Parent $PSScriptRoot
$script = Join-Path $root 'db\demo-reset.sql'

if (-not (Test-Path -LiteralPath $script)) {
    throw "demo-reset: $script is missing"
}

Push-Location $root
try {
    Write-Host "demo-reset: emptying $Database"
    $sql = Get-Content -LiteralPath $script -Raw
    $sql | docker compose exec -T db psql -U minibank -d $Database -v ON_ERROR_STOP=1 -q
    if ($LASTEXITCODE -ne 0) {
        throw "demo-reset: psql exit $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

Write-Host 'demo-reset: done. Restart the API to seed the demo dataset again:'
Write-Host '  .\mvnw.cmd -B spring-boot:run "-Dspring-boot.run.jvmArguments=-Dminibank.storage=sql -Dminibank.sql.url=jdbc:postgresql://localhost:55432/minibank"'
