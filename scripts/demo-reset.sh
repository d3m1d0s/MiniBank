#!/usr/bin/env sh
# Empties the MiniBank tables so the next start of the API seeds the demo dataset again.
# db/demo-reset.sql says what it removes and why the logins go with the rest.
#
#   scripts/demo-reset.sh                 minibank
#   scripts/demo-reset.sh minibank_test   some other database
#
# The alternative this replaces is docker compose down -v, which throws the volume away and takes
# both databases and every migration applied to them with it.
#
# It does not restart the API, and cannot: the API is started by hand, in a window of its own,
# and this script has no way to know which. Seeding runs once at startup under the demo profile,
# so the screens stay empty until that process comes back.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
script="$root/db/demo-reset.sql"

database=${1:-minibank}

if [ ! -f "$script" ]; then
    echo "demo-reset: $script is missing" >&2
    exit 1
fi

cd "$root"
echo "demo-reset: emptying $database"
docker compose exec -T db psql -U minibank -d "$database" -v ON_ERROR_STOP=1 -q < "$script"
echo "demo-reset: done. Restart the API to seed the demo dataset again:"
echo "  ./mvnw -B spring-boot:run \"-Dspring-boot.run.jvmArguments=-Dminibank.storage=sql -Dminibank.sql.url=jdbc:postgresql://localhost:55432/minibank\""
