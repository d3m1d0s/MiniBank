#!/usr/bin/env sh
# Brings the database up and returns only once it is ready to take a connection.
#
#   scripts/up.sh
#
# --wait is the whole point of it. docker compose up -d returns as soon as the container has been
# started, which on a fresh volume is a good half minute before PostgreSQL will answer: initdb is
# still running, and the first thing a reader did with the old instructions was watch
# docker compose ps until the word healthy appeared. The healthcheck was already there and
# already correct, and --wait is what makes docker compose do that watching. On a stack that is
# already up this returns at once.
#
# PowerShell has its own copy, scripts/up.ps1, that does the same thing.
#
# The API and the two front ends are not started here. Each is a process that holds a terminal
# and prints to it, and there are three of them; the commands are printed below instead, with the
# port this run actually published filled in.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

echo "up: starting the database and waiting for its healthcheck"
docker compose up -d --wait

# Asked rather than assumed: the published port is ${MINIBANK_DB_PORT:-55432}, and a .env or an
# exported variable can have moved it. Every command printed below carries a port, so printing
# one that is merely the default would be printing a guess.
published=$(docker compose port db 5432 2>/dev/null || true)
case "$published" in
    *:*) port=${published##*:} ;;
    *)   port=55432 ;;
esac

echo "up: PostgreSQL is ready on localhost:$port, databases minibank and minibank_test"
echo
echo "Three windows are left. Each of these holds the terminal it runs in:"
echo
echo "  ./mvnw -B spring-boot:run \"-Dspring-boot.run.jvmArguments=-Dminibank.storage=sql -Dminibank.sql.url=jdbc:postgresql://localhost:$port/minibank\""
echo "  cd minibank-web; npm install; npm run dev"
echo "  cd minibank-fraud-web; npm install; npm run dev"
echo
echo "Then the customer front end is on http://localhost:5173 and the workstation on http://localhost:5174."
