# API contract (v1)

Base path `/api/v1`. JSON, UTF-8. Ids are UUID strings. **Amounts and counters are
decimal integer strings in minor units** (`"1050"` = \$10.50 in ledger 840). Timestamps are
ISO-8601 UTC. Errors are RFC 9457 `application/problem+json` with an extra `code` field
(a result code below) and, for validation, `errors: [{field, message}]`.

OpenAPI is served at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`.

## Ledgers

`GET /ledgers` → `[{ "id": 840, "currency": "USD", "assetScale": 2 }]`

## Accounts

```json
Account {
  "id": "uuid", "ledger": 840, "code": 1, "name": "Alice wallet",
  "flags": { "debitsMustNotExceedCredits": true, "creditsMustNotExceedDebits": false,
             "history": true, "closed": false },
  "debitsPending": "0", "debitsPosted": "0", "creditsPending": "0", "creditsPosted": "0",
  "createdAt": "2026-11-26T08:00:00Z"
}
```

- `POST /accounts` body: `id, ledger, code, name, flags` (all flags optional, default
  false; `closed` not allowed at creation in v1). → `201` + Account. Same id + same body
  → `200` + Account. Same id, different body → `409` `exists_with_different_fields`.
- `GET /accounts?limit=50&cursor=…` → `{ "items": [Account], "nextCursor": "…|null" }`
  (keyset pagination by `createdAt, id`, newest first; `limit` 1–200).
- `GET /accounts/{id}` → Account or `404` `account_not_found`.
- `GET /accounts/{id}/transfers?limit=&cursor=` → page of Transfer touching the account,
  newest first (by `seq`), as `{ items, nextCursor }`.
- `GET /accounts/{id}/balances?limit=&cursor=` → page of
  `{ transferId, seq, timestamp, debitsPending, debitsPosted, creditsPending, creditsPosted }`
  (balance right after each transfer, newest first; an expiry adds an entry carrying the
  expired pending transfer's id and seq; only for accounts with `history`, else `422`
  `history_not_enabled`).

Name: 1–100 chars. `code`: 1–65535. `ledger` must exist (`422` `ledger_not_found`).

Every "page of X" endpoint (accounts, an account's transfers, an account's balances,
transfers) returns `{ "items": [...], "nextCursor": "string|null" }`, newest first, with
`limit` 1–200 (default 50) and an opaque `cursor`. `GET /activity` is the one exception:
a bare array. Invalid `limit`/`cursor` → `400` `invalid_request`.

## Transfers

```json
Transfer {
  "id": "uuid", "debitAccountId": "uuid", "creditAccountId": "uuid",
  "amount": "1050", "ledger": 840, "code": 1,
  "flags": { "linked": false, "pending": false, "postPendingTransfer": false,
             "voidPendingTransfer": false },
  "pendingId": "uuid|null", "timeoutSeconds": 0, "reference": "string|null",
  "seq": "123", "timestamp": "…",
  "pendingStatus": "PENDING|POSTED|VOIDED|EXPIRED|null",
  "expiresAt": "…|null"
}
```

`code` is 1–65535 like an account code. `timeoutSeconds` is 0–31 536 000 and only valid
with `pending`; **`0` means no expiry** (`expiresAt` null). `reference` is free text up to 64 chars (e.g. an invoice number). `seq`, `timestamp`,
`pendingStatus`, `expiresAt` are server-set and ignored on input.

- `POST /transfers` — one transfer (must not have `linked`). → `201` + Transfer;
  idempotent replay → `200` + stored Transfer; rejection → problem with code and status
  per table below.
- `POST /transfers/batch` body `{ "transfers": [Transfer…] }` (1–100). Always `200` with
  `{ "results": [{ "index": 0, "id": "uuid", "code": "created|exists|<error>", "transfer": Transfer|null, "detail": "string|null" }] }`
  (`detail` explains conflicts such as the differing fields). A malformed item (bad
  amount string, missing required field) fails the whole request with `400`, field
  names like `transfers[1].amount`.
  Linked-chain semantics in ARCHITECTURE.md rule 6.
- `GET /transfers/{id}` → Transfer or `404` `transfer_not_found`.
- `GET /transfers?pendingStatus=PENDING&limit=&cursor=` → `{ items, nextCursor }` of Transfer
  (newest first; `pendingStatus` optional: PENDING, POSTED, VOIDED or EXPIRED).

For post/void: `pendingId` required; `debitAccountId`, `creditAccountId`, `ledger`,
`code` are omitted (inherited from the pending transfer); if given they must match.
`amount` is `"0"` for a void and for a full post; a partial post sends the explicit amount
(never more than the pending amount). A void with any other amount is rejected. The stored
post/void transfer carries the resolved amount. Closed accounts can still be resolved.
Request bodies are limited to 256 KiB (`413` `payload_too_large`; chunked bodies without
`Content-Length` get `411`).

### Result codes → HTTP status (single-transfer endpoint)

| code | status |
|---|---|
| `created` | 201 |
| `exists` | 200 |
| `exists_with_different_fields` (any field differs; `detail` names the fields) | 409 |
| `id_must_not_be_nil`, `flags_are_mutually_exclusive`, `amount_must_be_positive`, `accounts_must_be_different`, `pending_id_required`, `pending_id_must_be_null`, `timeout_reserved_for_pending`, `linked_not_allowed`, validation errors (`invalid_request`) | 400 |
| `debit_account_not_found`, `credit_account_not_found`, `pending_transfer_not_found` | 404 |
| `ledger_must_match`, `transfer_must_have_same_ledger_as_accounts`, `pending_transfer_not_pending`, `pending_transfer_already_posted`, `pending_transfer_already_voided`, `pending_transfer_expired`, `pending_transfer_has_different_<field>` (field: `debit_account_id`, `credit_account_id`, `ledger`, `code`, `amount`), `exceeds_pending_transfer_amount`, `debit_account_closed`, `credit_account_closed`, `exceeds_credits`, `exceeds_debits`, `overflows_balance` | 422 |
| `linked_event_failed`, `linked_event_chain_open` | batch only |
| `ledger_not_found` (account creation) | 422 |
| `not_found`, `method_not_allowed`, `unsupported_media_type`, `internal_error` | 404, 405, 415, 500 |

## Reconciliation

`GET /reconciliation` →
```json
{ "checkedAt": "…", "balanced": true,
  "ledgers": [{ "ledger": 840, "debitsPosted": "…", "creditsPosted": "…",
                "debitsPending": "…", "creditsPending": "…", "balanced": true }],
  "accountMismatches": [{ "accountId": "uuid", "field": "debitsPosted",
                          "stored": "…", "recomputed": "…" }] }
```

## Activity (live)

- `GET /activity?limit=50` → `[ActivityItem]` newest first.
- `GET /activity/stream` → `text/event-stream`. Event name `activity`, `id` = CloudEvent
  id, data = ActivityItem. Heartbeat comment every 15 s. Supports `Last-Event-ID`.

```json
ActivityItem { "eventId": "uuid", "type": "transfer.created|transfer.posted|transfer.voided|transfer.expired|account.created",
               "seq": "123", "transferId": "uuid|null", "accountId": "uuid|null",
               "amount": "1050|null", "ledger": 840, "occurredAt": "…", "summary": "string" }
```

## Kafka events

CloudEvents 1.0, structured JSON mode, header `content-type: application/cloudevents+json`.

```json
{ "specversion": "1.0", "id": "uuid", "source": "/ledger-core",
  "type": "dev.justcallmepratt.ledger.transfer.created.v1",
  "time": "…", "subject": "<transfer id>", "datacontenttype": "application/json",
  "data": { "transfer": Transfer,
            "debitAccount": { "id": "…", "debitsPending": "…", "debitsPosted": "…", "creditsPending": "…", "creditsPosted": "…" },
            "creditAccount": { …same… } } }
```

Types: `transfer.created.v1` (single-phase or new pending), `transfer.posted.v1`,
`transfer.voided.v1`, `transfer.expired.v1` on `ledger.transfers.v1`;
`account.created.v1` on `ledger.accounts.v1` (`data` = Account).
