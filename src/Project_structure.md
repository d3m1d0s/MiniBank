# Where things are

A map of the repository, written to stay true. It gives the shape of each area and names the
files a reader has to find; it does not list all 139 classes, because a file that lists every file
is wrong the day the next one is added - which is what happened to the version this replaces.

```
VIS_project_minibank/
├─ pom.xml                      Maven build; three entry points, one test phase
├─ docker-compose.yml           PostgreSQL 14 only, published on ${MINIBANK_DB_PORT:-5432}
├─ db/                          the schema, and the ALTERs for a database that predates it
├─ src/main/java/               the application
├─ src/main/resources/          application.properties
├─ src/test/java/               54 test classes
├─ frontend-shared/             what the two web applications both talk to the API with
├─ minibank-web/                the customer application, which also carries the fraud desk
└─ minibank-fraud-web/          the analyst application
```

Everything under `data/`, `storage/`, `target/` and `node_modules/` is generated or runtime state
and is ignored by git.

## db

```
db/
├─ init/
│  ├─ schema.sql                every table, index, constraint and sequence
│  └─ test-database.sql         creates minibank_test and applies schema.sql to it as well
├─ migrate/                     run BY HAND, against BOTH databases; see each file's header
│  ├─ hold-alerted-transfers.sql
│  ├─ stored-fee-and-account-version.sql
│  ├─ transfer-version.sql
│  ├─ one-alert-per-transfer.sql
│  ├─ transfer-daily-total-index.sql
│  ├─ sequence-ownership.sql
│  └─ currency-is-czk.sql
└─ reset.sql                    drops everything; never run automatically
```

`db/init/` runs only on an **empty** Docker volume, and it applies `schema.sql` to two databases:
`minibank` for the application and `minibank_test` for the SQL tests. `db/migrate/` exists for a
volume that already has data: each script is an online `ALTER`, is mirrored back into
`schema.sql`, and states in its own header what it does and why. Nothing records which of them has
been run - there is no migration tool here - so their filenames carry description and no ordering.

## src/main/java/cz/vsb/minibank

Four layers, and the dependencies point inwards: `api` and `ui` know `application`, `application`
knows `domain`, `infrastructure` implements interfaces that `domain` declares. Nothing in `domain`
imports anything below it.

```
├─ App.java                     console entry point, JSON store
├─ AppSql.java                  console entry point, PostgreSQL
├─ ApiApplication.java          Spring Boot entry point
│
├─ domain/                      25 classes: the model, and no framework anywhere in it
│  ├─ Account, Transfer, Customer, Beneficiary, FraudAlert, User
│  ├─ Payment, CardPayment                  how a transfer was authorized
│  ├─ FeePolicy, SimpleFeePolicy, ZeroFeePolicy
│  ├─ RiskService, RuleBasedRiskService, RiskDecision
│  ├─ DomainEvent, DomainEventBus, RecordsDomainEvents, and the two events
│  ├─ value/                    Money, IBAN
│  ├─ repository/               5 interfaces the infrastructure implements
│  ├─ exceptions/               20 classes, one per way a request can be refused
│  └─ lazy/                     LazyRef, LazyList
│
├─ application/                 21 classes: use cases, and the rules that guard them
│  ├─ TransferApplicationService     create, authorize, cancel; PaymentOutcome is what they answer
│  ├─ FraudApplicationService        the analyst's three verdicts
│  ├─ AuthService, SessionStore, LoginThrottle, SecurityContext
│  ├─ OwnershipGuard                 the one place "is this the caller's?" is decided
│  └─ BootstrapServices              wires the services for every entry point
│
├─ infrastructure/              two adapters behind one set of repository interfaces
│  ├─ uow/                      UnitOfWork, UowScope, UowContext, IdentityMapAccounts
│  ├─ json/                     JsonDataStore and its unit of work, dto/, mapping/, repo/
│  ├─ sql/                      SqlUnitOfWork and repo/, five repositories
│  ├─ memory/                   InMemoryUserRepository, for the console
│  ├─ Bootstrap.java            picks the backend and builds the repositories
│  └─ StoredValue.java          refuses a stored enum or instant that cannot be read
│
├─ api/                         12 controllers and helpers, 16 records in dto/
│  ├─ PaymentController, AuthorizationController, FraudController, AuthController
│  ├─ RestExceptionHandler      the one place a domain exception becomes a status and a code
│  ├─ SessionAuthInterceptor    every /api/** call except the sign-in needs a session
│  ├─ WebConfig, MinibankApiConfig, DemoUsersInitializer
│  └─ dto/                      request and response records; MoneyDto is how money crosses the wire
│
├─ demo/                        DemoScenario builds the sample data, DemoRunner exercises it
└─ ui/console/                  ConsoleMenu and its input helper
```

## src/test/java/cz/vsb/minibank

54 classes, arranged by what they hold still rather than by what they call.

```
├─ application/    21  the money paths, ownership, the daily limit, the fraud gate, sessions
├─ uow/             8  the unit of work, the identity map, lazy loading, and the schema itself
├─ api/             7  the HTTP error contract, the two desks, role checks
├─ domain/          7  Money, IBAN, the fee policy, domain events, alert metadata
├─ infrastructure/  6  the JSON store: concurrency, atomic saves, money round trips
├─ ui/console/      2
├─ demo/            1
└─ SecurityContextCleanupExtension   clears the thread-local after every test in the suite
```

**15 of them need a database** and skip when none is reachable, so a fresh clone stays green:
`MinibankSqlUowTests` and `SqlSchemaPassTest`. Pass the URL to run them:

```
mvn -B test "-Dminibank.test.sql.url=jdbc:postgresql://localhost:55432/minibank_test"
```

The quotes matter in PowerShell; without them the argument is split on the colons.

## The web applications

```
frontend-shared/           imported by both through a @shared/* path alias
├─ http.ts                 the client, the session header, sign in and sign out
├─ fraud.ts                the alert queue's types and its three calls
├─ money.ts                Money on the wire, and the one function that prints it
└─ alertFilters.ts         the amount-range rules both desks apply before asking

minibank-web/              the customer application, port 5173
└─ src/  NewPaymentPage, WaitingAuthorizationsPage, FraudDeskPage, LoginDialog,
         money.ts (parsing what a customer types), api.ts (a barrel over the shared client),
         and the two vitest suites - the only test runner either front end has

minibank-fraud-web/        the analyst application, port 5174
└─ src/  FraudDesk, Login, api.ts (the same barrel)
```

Both carry a fraud desk on purpose: an analyst signing into the customer application lands on one,
and the analyst application is the same desk with its own shell. What they must not carry twice is
the description of the wire, which is why `frontend-shared/` exists. Each application proxies
`/api` to `http://localhost:8080` through its own vite config, so neither needs a cross-origin
allowlist in development.
