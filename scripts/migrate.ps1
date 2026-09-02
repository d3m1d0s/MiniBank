<#
    Applies the scripts in db/migrate, in the order db/migrate/order.txt declares, to each
    database named on the command line, and to both of them when none is named. Both, because
    db/init/test-database.sql builds minibank_test out of the same schema.sql: the two go out of
    shape together, and nearly every header in db/migrate says in capitals that they have to be
    brought back together. That instruction used to be a psql line a reader retyped once per
    script and once per database, twenty six times for the thirteen scripts here, and the
    PowerShell half of the README only ever showed one of the two databases.

        .\scripts\migrate.ps1                       minibank and minibank_test
        .\scripts\migrate.ps1 -DryRun               print the plan and connect to nothing
        .\scripts\migrate.ps1 -Database minibank    only the named database

    Git Bash has its own copy, scripts/migrate.sh, that does the same thing.

    psql runs inside the container, so nothing needs installing on the machine and the published
    port does not come into it. Nothing records which scripts a database has already had, so this
    runs all of them every time; order.txt says why that is safe and names the two to read first.
#>
[CmdletBinding()]
param(
    [string[]] $Database = @('minibank', 'minibank_test'),
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'

$root       = Split-Path -Parent $PSScriptRoot
$migrations = Join-Path $root 'db\migrate'
$order      = Join-Path $migrations 'order.txt'

if (-not (Test-Path -LiteralPath $order)) {
    throw "migrate: $order is missing, and it is what declares the order"
}

$declared = Get-Content -LiteralPath $order |
    ForEach-Object { ($_ -replace '#.*', '').Trim() } |
    Where-Object { $_ -ne '' }

$present = Get-ChildItem -LiteralPath $migrations -Filter '*.sql' -File | ForEach-Object { $_.Name }

# A file in one list and not the other is the failure this manifest exists to make loud: a script
# added to the directory and never ordered would silently never run, and a name left here after
# its file was renamed would stop the run halfway through with a confusing error from psql.
$drift = $false
foreach ($name in $declared) {
    if (-not (Test-Path -LiteralPath (Join-Path $migrations $name))) {
        Write-Error "migrate: order.txt lists $name, which is not in db/migrate" -ErrorAction Continue
        $drift = $true
    }
}
foreach ($name in $present) {
    if ($declared -notcontains $name) {
        Write-Error "migrate: db/migrate/$name is not listed in order.txt, so its place in the order is unknown" -ErrorAction Continue
        $drift = $true
    }
}
if ($drift) { exit 1 }

Write-Host "migrate: $($declared.Count) script(s), databases: $($Database -join ', ')"

if ($DryRun) {
    foreach ($database in $Database) {
        foreach ($name in $declared) {
            Write-Host "  would apply db/migrate/$name to $database"
        }
    }
    Write-Host 'migrate: dry run, nothing was applied'
    exit 0
}

Push-Location $root
try {
    foreach ($database in $Database) {
        Write-Host "migrate: $database"
        foreach ($name in $declared) {
            Write-Host "  $name ... " -NoNewline
            # -Raw so the whole script reaches psql as one string: fed line by line, the dollar
            # quoted function bodies several of these scripts use would be broken apart.
            $sql = Get-Content -LiteralPath (Join-Path $migrations $name) -Raw
            $sql | docker compose exec -T db psql -U minibank -d $database -v ON_ERROR_STOP=1 -q
            if ($LASTEXITCODE -ne 0) {
                throw "migrate: db/migrate/$name failed against $database (psql exit $LASTEXITCODE)"
            }
            Write-Host 'ok'
        }
    }
}
finally {
    Pop-Location
}
Write-Host 'migrate: done'
