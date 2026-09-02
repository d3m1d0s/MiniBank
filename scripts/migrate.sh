#!/usr/bin/env sh
# Applies the scripts in db/migrate, in the order db/migrate/order.txt declares, to each database
# named on the command line, and to both of them when none is named. Both, because
# db/init/test-database.sql builds minibank_test out of the same schema.sql: the two go out of
# shape together, and nearly every header in db/migrate says in capitals that they have to be
# brought back together. That instruction used to be a pair of psql lines a reader retyped once
# per script and once per database, twenty six times for the thirteen scripts here.
#
#   scripts/migrate.sh                  minibank and minibank_test
#   scripts/migrate.sh --dry-run        print the plan and connect to nothing
#   scripts/migrate.sh minibank_test    only the named database
#
# PowerShell has its own copy, scripts/migrate.ps1, that does the same thing.
#
# psql runs inside the container, so nothing needs installing on the machine and the published
# port does not come into it. Nothing records which scripts a database has already had, so this
# runs all of them every time; order.txt says why that is safe and names the two to read first.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
migrations="$root/db/migrate"
order="$migrations/order.txt"

dry_run=no
databases=

for arg in "$@"; do
    case "$arg" in
        --dry-run) dry_run=yes ;;
        -*)
            echo "migrate: unknown option $arg" >&2
            echo "usage: scripts/migrate.sh [--dry-run] [database ...]" >&2
            exit 2
            ;;
        *) databases="$databases $arg" ;;
    esac
done

if [ -z "$databases" ]; then
    databases="minibank minibank_test"
fi

if [ ! -f "$order" ]; then
    echo "migrate: $order is missing, and it is what declares the order" >&2
    exit 1
fi

# tr strips the carriage returns a checkout with CRLF endings leaves behind; without it every
# filename would end in one and none of them would be found.
declared=$(sed 's/#.*//' "$order" | tr -d '\r' | awk 'NF')
present=$(cd "$migrations" && ls -1 ./*.sql | sed 's|^\./||' | LC_ALL=C sort)

# A file in one list and not the other is the failure this manifest exists to make loud: a script
# added to the directory and never ordered would silently never run, and a name left here after
# its file was renamed would stop the run halfway through with a confusing error from psql.
drift=no
for name in $declared; do
    if [ ! -f "$migrations/$name" ]; then
        echo "migrate: order.txt lists $name, which is not in db/migrate" >&2
        drift=yes
    fi
done
for name in $present; do
    case " $(echo $declared) " in
        *" $name "*) ;;
        *)
            echo "migrate: db/migrate/$name is not listed in order.txt, so its place in the order is unknown" >&2
            drift=yes
            ;;
    esac
done
if [ "$drift" = yes ]; then
    exit 1
fi

count=$(echo "$declared" | awk 'END { print NR }')
echo "migrate: $count script(s), databases:$databases"

if [ "$dry_run" = yes ]; then
    for database in $databases; do
        for name in $declared; do
            echo "  would apply db/migrate/$name to $database"
        done
    done
    echo "migrate: dry run, nothing was applied"
    exit 0
fi

cd "$root"
for database in $databases; do
    echo "migrate: $database"
    for name in $declared; do
        printf '  %s ... ' "$name"
        docker compose exec -T db \
            psql -U minibank -d "$database" -v ON_ERROR_STOP=1 -q < "$migrations/$name"
        echo "ok"
    done
done
echo "migrate: done"
