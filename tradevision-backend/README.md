# TradeVision AI — Java Spring Boot Backend

Review finding (P1 #30 — "Documentation is inconsistent with the actual application"): this file
was rewritten rather than patched, since it had drifted from the real system in several places
(a build command that no longer matches the Dockerfile, an OTP claim that stopped being true once
console fallback was gated behind an explicit flag, no mention of the local/prod config split).
Documentation should describe exactly the system that ships — that's the standard this rewrite
aims for, not just fixing the specific lines the review named.

## Stack
- Java 21 + Spring Boot 3.2.5
- MongoDB (Atlas in production; local Docker container for development — see SETUP.md)
- JWT authentication (access + refresh tokens, refresh rotation, per-user tokenVersion for real logout)
- OTP-based login/registration (email via Brevo, SMS via MSG91 — both real integrations now, not placeholders)
- AES-256-GCM encrypted broker credentials, atomic Mongo-backed risk/exposure/slot reservations
- A Content-Security-Policy and standard security headers (see SecurityConfig)

## Run locally

No environment variables to set — `application-local.properties` (committed on purpose, every
value in it is a fake dev placeholder — see its own header) provides everything needed. The one
external requirement is MongoDB running locally:

```bash
docker run -d --name tradevision-mongo -p 27017:27017 mongo:7
mvn spring-boot:run
```

Server starts on **http://localhost:8080**. OTPs print straight to the console in this mode
(`app.otp.allow-console-fallback=true` in the local profile only — never in production, where a
misconfigured deployment silently logging real OTPs would defeat the whole point of using one).

## Build the Docker image

The image bundles a freshly-built Angular frontend — see SETUP.md for why this must be built
from the **repo root**, not from inside this directory:

```bash
docker build -f tradevision-backend/Dockerfile -t tradevision-backend .
```

This also runs the full test suite (`mvn clean verify`) as part of the build — a broken test
fails the image build rather than shipping silently.

## Production configuration

See **SETUP.md** for the full list of required environment variables, the local/prod properties
split, and the security-rotation checklist if you're inheriting this project from an earlier
version that had real secrets committed directly.

## API Endpoints (representative, not exhaustive)

### Auth (no token needed)
| Method | URL | Body |
|--------|-----|------|
| POST | /api/auth/register/initiate | {firstName, lastName?, mobile, tradingPlatform?} |
| POST | /api/auth/register/verify   | {mobile, otp} |
| POST | /api/auth/login/initiate    | {mobile} |
| POST | /api/auth/login/verify      | {mobile, otp} |
| POST | /api/auth/otp/resend?mobile=&purpose= | — |
| GET  | /api/auth/check-mobile?mobile= | — |

### User (JWT required)
| Method | URL | Body |
|--------|-----|------|
| GET  | /api/user/profile   | — |
| POST | /api/user/favorites | {symbol, type: STOCK/CRYPTO/FOREX, add: true/false} |

### Broker & auto-trading (JWT required) — see BrokerController for the full set
| Method | URL | Notes |
|--------|-----|-------|
| POST | /api/broker/connect | Connects a TESTNET credential |
| POST | /api/broker/connect/live/request | Step 1 of connecting a LIVE credential (separate key from testnet — see BrokerCredentialService) |
| POST | /api/broker/connect/live/confirm | Step 2 — persists the LIVE credential |
| GET  | /api/positions/{credentialId} | Position dashboard — open/closed positions, P&L, protection status |
| POST | /api/positions/{positionId}/emergency-flatten | Manual emergency close |
| POST | /api/positions/credential/{credentialId}/reconcile-now | Manual on-demand reconciliation |

## OTP delivery

Real providers are wired: Brevo for email (`EmailService`), MSG91 for SMS (`OtpUtil`). Both
report genuine success/failure back to the caller — a misconfigured or failed provider now
returns an honest error instead of silently claiming "OTP sent" for one that never went anywhere.

## MongoDB collections (not exhaustive — see each model's own @Document annotation)
- `users` — profiles, favorites, auth state
- `otp_records` — OTPs (hashed, never stored raw), TTL auto-expire
- `broker_credentials` — encrypted API keys, one row per environment (TESTNET/LIVE are separate credentials)
- `positions`, `executed_orders` — trading history and live position state
- `position_slot_reservations`, `exposure_reservations` — atomic concurrency-safe risk limits
