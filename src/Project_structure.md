# Where things are

A map of the repository, written to stay true. It gives the shape of each area and names the
files a reader has to find; it does not list all 156 source files, because a file that lists every
file is wrong the day the next one is added, which is what happened to the version this replaces.

```
VIS_project_minibank/
├─ pom.xml                      Maven build; three entry points, one test phase
├─ mvnw, mvnw.cmd, .mvn/        the Maven wrapper, 3.9.6; script only, so there is no jar
├─ docker-compose.yml           PostgreSQL 14 only, published on ${MINIBANK_DB_PORT:-55432}
├─ .env.example                 what may be set, all of it optional
├─ scripts/                     up, migrate and demo-reset, each in a PowerShell and a sh copy
├─ .github/workflows/ci.yml     the build that runs on main and on every pull request
├─ LICENSE                      MIT
├─ db/                          the schema, the scripts that bring an older database to it
├─ src/main/java/               the application
├─ src/main/resources/          application.properties
├─ src/test/java/               68 test classes and three helpers
├─ frontend-shared/             what the two web applications both talk to the API with
├─ minibank-web/                the customer application, dark, web idiom
└─ minibank-fraud-web/          the workstation, light, window idiom
```

Both front ends serve both roles. The skins differ on purpose; the structure and the wording do
not, which is what `frontend-shared/` is for.

Everything under `storage/`, `target/` and `node_modules/` is generated or runtime state and is
ignored by git. `storage/` is where the JSON store, the demo store and the application log are
written, so a run of any entry point leaves nothing behind at the repository root.

## db

```
db/
├─ init/
│  ├─ schema.sql                every table, index, constraint and sequence
│  └─ test-database.sql         creates minibank_test and applies schema.sql to it as well
├─ migrate/                     13 scripts for a database that predates the schema above
│  └─ order.txt                 the order they run in, oldest first, and why it is a list
├─ demo-reset.sql               empties the seven tables so the next API start seeds them again
└─ reset.sql                    drops everything; never run automatically
```

`db/init/` runs only on an **empty** Docker volume, and it applies `schema.sql` to two databases:
`minibank` for the application and `minibank_test` for the SQL tests.

`db/migrate/` exists for a volume that already has data, and `scripts/migrate.ps1` and
`scripts/migrate.sh` apply all of it to both databases in the declared order. The filenames are not
repeated here: `order.txt` carries them, the two scripts refuse to run when it and the directory
have drifted apart, so that file is checked and a list in this document would not be.

Most of the scripts change the shape of a table and are mirrored back into `schema.sql`, but they
are not all that. `hold-alerted-transfers.sql` only moves rows,
`fraud-alert-comment-and-notes-journal.sql` creates a table and then carries the old values into
it, and `2026-08-24-daily-limit-on-the-customer.sql` copies two columns onto another table before
dropping them. Each states in its own header what it does and why. Nothing records which of them a
given database has already had, so each is written to survive being run twice.

`demo-reset.sql` and `reset.sql` are the two ways back, and they are not the same one:
`demo-reset.sql` empties the tables and leaves the schema, the second database and every applied
migration standing, while `reset.sql` drops the tables and has to be followed by
`db/init/schema.sql`.

## src/main/java/cz/vsb/minibank

Four layers, and the dependencies point inwards: `api` and `ui` know `application`, `application`
knows `domain`, `infrastructure` implements interfaces that `domain` declares. Nothing in `domain`
imports anything below it.

```
├─ App.java                     console entry point, JSON store
├─ AppSql.java                  console entry point, PostgreSQL
├─ ApiApplication.java          Spring Boot entry point
│
├─ domain/                      27 classes: the model, and no framework anywhere in it
│  ├─ Account, Transfer, Customer, Beneficiary, FraudAlert, User
│  ├─ Payment, CardPayment                  how a transfer was authorized
│  ├─ TransferStatus, DispatchState         where a payment is, and what it still owes the network
│  ├─ FeePolicy, SimpleFeePolicy, ZeroFeePolicy
│  ├─ RiskService, RuleBasedRiskService, RiskDecision
│  ├─ DomainEvent, DomainEventBus, RecordsDomainEvents, and the two events
│  ├─ value/                    Money, IBAN
│  ├─ repository/               5 interfaces the infrastructure implements
│  ├─ exceptions/               21 classes, one per way a request can be refused
│  └─ lazy/                     LazyRef, LazyList
│
├─ application/                 23 classes: use cases, and the rules that guard them
│  ├─ TransferApplicationService     create, authorize, cancel; PaymentOutcome is what they answer
│  ├─ FraudApplicationService        the analyst's three verdicts
│  ├─ AuthService, SessionStore, LoginThrottle, SecurityContext
│  ├─ OtpValidator                   FixedOtpValidator under the demo profile, RefusingOtpValidator
│  │                                 without it; the seam a real provider would go into
│  ├─ PaymentDispatcher, PaymentNetworkGateway   what a settled payment still owes the network
│  ├─ OwnershipGuard                 the one place "is this the caller's?" is decided
│  ├─ MinibankProperties             every configuration key, its default, and the renamed names
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
├─ api/                         14 controllers and helpers, 23 records in dto/
│  ├─ PaymentController, AuthorizationController, FraudController, AuthController
│  ├─ RestExceptionHandler      the one place a domain exception becomes a status and a code
│  ├─ SessionAuthInterceptor    every /api/** call except the sign-in needs a session
│  ├─ ApiStartupCheck           what the configuration says, and the database that did not answer
│  ├─ StartupSequence           seeding and the pending sweep, once the context is up
│  ├─ WebConfig, MinibankApiConfig, DemoUsersInitializer
│  └─ dto/                      request and response records; MoneyDto is how money crosses the wire
│
├─ demo/                        DemoScenario builds the sample data, DemoRunner exercises it
└─ ui/console/                  ConsoleMenu and its command interface, 750 lines
```

## src/test/java/cz/vsb/minibank

68 classes, arranged by what they hold still rather than by what they call.

```
├─ application/    21  the money paths, ownership, the daily limit, the fraud gate, sessions
├─ infrastructure/ 15  the JSON store, the backend choice, the two SQL adapters
├─ api/            13  the HTTP error contract, the two desks, role checks, what startup says
├─ uow/             8  the unit of work, the identity map, lazy loading, and the schema itself
├─ domain/          7  Money, IBAN, the fee policy, domain events, alert metadata
├─ ui/console/      2
├─ demo/            1
└─ SecurityContextCleanupExtensionTest
```

Three files in there hold no cases of their own: `SecurityContextCleanupExtension`, which clears
the thread-local after every test in the suite, `TestClock` and `TestDatabase`.

**43 cases in three classes need a database**, and they are skipped when none is named:
`MinibankSqlUowTests`, `SqlSchemaPassTest` and `SqlUserRepositoryTest`. There is no default
address, so a run that gives none opens no connection at all. Pass the url to run them:

```
.\mvnw.cmd -B test "-Dminibank.test.sql.url=jdbc:postgresql://localhost:55432/minibank_test"
```

The quotes matter in PowerShell; without them the argument is split on the colons. In Git Bash the
wrapper is `./mvnw`.

## The web applications

```
frontend-shared/           imported by both through a @shared/* path alias
├─ http.ts                 the client, the session header, sign in and sign out
├─ fraud.ts, customer.ts   the two desks' types and calls
├─ money.ts, format.ts     Money on the wire, and how money and dates are printed
├─ glossary.ts             the words both applications use for the same thing
├─ navigation.ts, route.ts what each role may reach, and the hand written hash router
├─ alertFilters.ts         the amount-range rules both desks apply before asking
├─ apiErrors.ts, fields.ts, paging.ts
├─ tokens/                 scale.css and one theme file per skin
├─ ErrorBoundary.jsx       the one file in here that renders
└─ __fixtures__/           recorded API responses the tests read

minibank-web/              the customer application, port 5173
└─ src/  NewPaymentPage, WaitingAuthorizationsPage, HistoryPage, FraudDeskPage, LoginDialog,
         Nav, TableFrame, ErrorBox, money.ts (parsing what a customer types),
         api.ts (a barrel over the shared client), and seven vitest suites

minibank-fraud-web/        the workstation, port 5174
├─ public/                 favicon.svg and manifest.webmanifest, copied into dist as they are;
│                          the manifest is what makes this window installable
└─ src/  FraudDesk, NewPaymentScreen, WaitingAuthorizationsScreen, HistoryScreen, Login,
         NavRail, TableFrame, ErrorBox, locale.ts, api.ts (the same barrel)
```

`WaitingAuthorizationsScreen.tsx` and `HistoryScreen.tsx` are the customer's waiting
authorizations and payment history, drawn in the window skin from the same shared calls the
customer application makes. `NewPaymentScreen.tsx` is the payment form, kept mounted and parked
while another screen is on, because it holds a confirmation written down nowhere else.

Both applications carry both roles, which is why what describes the wire may not exist twice, and
why it is in `frontend-shared/`. Each proxies `/api` to `http://localhost:8080` through its own
vite config, so neither needs a cross-origin allowlist in development.

There is one JavaScript test runner in the tree. vitest is installed only in `minibank-web`, where
`vite.config.ts` names `../minibank-fraud-web` as a project and `../frontend-shared` in `include`,
so `npm test` there covers all three directories: fifteen files, 462 cases, all of them plain calls
into the shared module. Seven of those files are in `minibank-web/src` and eight in
`frontend-shared`; the workstation is named so that a case written there runs through this install,
and it holds none of its own today. `minibank-fraud-web` has no `test` script and is not meant to
get one.
