# Security

## Scope of phase 1

`ledger-core` phase 1 is a portfolio project meant to run on a developer machine and in
CI. It holds no real money and no real personal data. It is **not** hardened for the
public internet yet:

- The API has no authentication. Anyone who can reach port 8080 can create accounts and
  transfers. Keep it bound to localhost.
- There is no rate limiting.

Planned before any public deployment: OAuth2 resource server (JWT) with scopes for read
and write, per-client rate limits, and audit logging of every write.

## What is already in place

- Input validation on every request, batch size limits and request size limits.
- CORS restricted to the configured web origin.
- Actuator limited to health, info and Prometheus metrics.
- No secrets in the repository; configuration comes from environment variables.
- The transfer history is append-only, enforced in the database.

## Reporting

Please report a vulnerability through GitHub's private vulnerability reporting on this
repository rather than in a public issue.
