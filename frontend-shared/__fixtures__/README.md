# Captured bodies

Three answers from the running API, saved exactly as they arrived. They are the wire, not examples
of it: nothing in this directory was typed by hand, and nothing in it may be.

That rule is the whole point. Every test in this directory used to build its own object and hand it
to the function under test, which proves the function reads the object the test wrote and nothing
about the answer the server sends. One field drifted for exactly that reason: the waiting list DTO
was declared with `beneficiaryIban`, the server had always sent `toIban`, and the screen reached
the value through a cast because the field it wanted was not on the type it had. Tests were green
throughout. See `WaitingTransferItem` in `minibank-web/src/api.ts` for the repair.

## What is here, and where each came from

| File | Route |
| --- | --- |
| `fraud-alerts.json` | `GET /api/fraud/alerts?page=0&size=5`, signed in as `fraud` |
| `fraud-alert-detail.json` | `GET /api/fraud/alerts/1`, signed in as `fraud` |
| `waiting-transfers.json` | `GET /api/me/waiting-transfers?page=0&size=10`, signed in as `alice` |

Captured on 2026-08-30 against the demo data, over `X-Session-Id` from `POST /api/auth/login`.
Read only calls, all three.

The bytes are the response bodies re-indented to two spaces and nothing else. No value was
shortened, rounded, renamed or invented, and a row was neither added nor removed: the alert queue
really does hold two alerts and the customer really is waiting on two payments.

## Refreshing them

Re-fetch. Do not edit. A body that no longer matches what `wire.test.ts` expects is a report about
the wire, and the answer to it is a change to the interfaces in `fraud.ts` or to the server, never
a change to these files.
